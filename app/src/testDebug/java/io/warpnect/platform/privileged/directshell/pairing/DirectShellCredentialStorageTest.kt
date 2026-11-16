package io.warpnect.platform.privileged.directshell.pairing

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private typealias StorageStatus = DirectShellCredentialStorage.Status

/** Real files/crypto/locks/link, explicitly MODELED directory synchronization except the default-provider test. */
class DirectShellCredentialStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stores = mutableListOf<DirectShellCredentialStorage>()
    private val ticket = DirectShellPairingTicket("fixture-owner", "fixture-attempt", 1, 7, 0)
    private var key: SecretKey? = StorageFixture.key
    private val io = ModeledDirectorySync()
    private val base get() = temporary.root.toPath()
    private fun path(name: String) = base.resolve(DirectShellCredentialStorage.DIRECTORY).resolve(name)
    private fun store(fs: DirectShellCredentialFileSystem = io) =
        DirectShellCredentialStorage(base, { key }, fs).also(stores::add)
    private fun begin(s: DirectShellCredentialStorage = store()): DirectShellCredentialStorage {
        assertEquals(StorageStatus.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
        return s
    }
    private fun prepare(s: DirectShellCredentialStorage): DirectShellCredentialStorage.Candidate =
        s.prepare(StorageFixture.evidence(ticket), DirectShellStagedAuthorization {}).also {
            assertEquals(StorageStatus.PREPARED, it.status)
        }.candidate!!
    private fun publish(s: DirectShellCredentialStorage, c: DirectShellCredentialStorage.Candidate = prepare(s)) =
        s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
    private fun inspection(): StorageStatus = store().inspect().status

    @After
    fun releaseOwnedFixtures() {
        stores.forEach { assertTrue("test-owned storage must drain", it.close()) }
    }

    @Test
    fun `stable lease primitive is tested without sanitized storage wrapper`() {
        val directory = base.resolve(DirectShellCredentialStorage.DIRECTORY)
        Files.createDirectory(directory)
        val lease = DirectShellCredentialLease.acquire(directory.resolve(DirectShellCredentialStorage.LOCK), true)
        assertNotNull(lease)
        assertTrue(lease!!.valid())
        assertTrue(lease.close())
    }

    @Test
    fun `passive construction and inspection create no namespace or key`() {
        val s = store()
        repeat(3) { assertEquals(StorageStatus.EMPTY, s.inspect().status) }
        assertFalse(Files.exists(path(DirectShellCredentialStorage.LOCK)))
        assertEquals(0, io.syncs)
    }

    @Test
    fun `default provider never bypasses an unsupported directory synchronization`() {
        val s = store(DirectShellCredentialFileSystem())
        val result = s.begin(ticket, StorageFixture.identity)
        if (System.getProperty("os.name")?.startsWith("Windows") == true) {
            assertEquals(StorageStatus.UNSUPPORTED, result)
            assertFalse(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
        } else {
            assertTrue(result in setOf(StorageStatus.RECOVERY_READY, StorageStatus.UNSUPPORTED))
        }
        assertFalse(Files.exists(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `anchor is bounded authenticated encrypted and has no invented peer`() {
        begin()
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR))
        assertTrue(bytes.size <= DirectShellCredentialCodec.MAX_RECORD)
        assertFalse(StorageFixture.contains(bytes, StorageFixture.identity.privateKey.encoded))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Storage Canary"))
        val record = DirectShellCredentialCodec.decode(bytes, StorageFixture.key)
        assertEquals(DirectShellCredentialCodec.Kind.RECOVERY, record.kind)
        assertEquals(null, record.peerDigest)
        assertArrayEquals(StorageFixture.identity.privateKey.encoded, record.identity.privateKey.encoded)
        assertTrue(io.syncs >= 3)
    }

    @Test
    fun `missing key never creates or regenerates identity`() {
        key = null
        assertEquals(StorageStatus.KEY_UNAVAILABLE, store().begin(ticket, StorageFixture.identity))
        assertFalse(Files.exists(path(DirectShellCredentialStorage.LOCK)))
    }

    @Test
    fun `unexportable or non AES256 wrapping key remains unsupported`() {
        key = SecretKeySpec(ByteArray(16), "AES")
        assertEquals(StorageStatus.KEY_UNAVAILABLE, store().begin(ticket, StorageFixture.identity))
    }

    @Test
    fun `key certificate mismatch is rejected before any storage`() {
        val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val material = DirectShellCredentialIdentity(wrong, StorageFixture.identity.certificate)
        assertEquals(StorageStatus.INVALID_MATERIAL, store().begin(ticket, material))
        assertFalse(Files.exists(path(DirectShellCredentialStorage.LOCK)))
    }

    @Test
    fun `revision zero only no revision overflow or adoption`() {
        val newer = DirectShellPairingTicket("owner", "nonce", 1, 7, Long.MAX_VALUE)
        assertEquals(StorageStatus.REVISION_CONFLICT, store().begin(newer, StorageFixture.identity))
        assertFalse(Files.exists(path(DirectShellCredentialStorage.LOCK)))
    }

    @Test
    fun `one shot begin cannot overwrite retained anchor`() {
        val s = begin()
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR))
        assertEquals(StorageStatus.STALE, s.begin(ticket, StorageFixture.identity))
        assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `pre PAKE sync failure leaves no ready permission`() {
        val failing = object : DirectShellCredentialFileSystem() {
            override fun syncDirectory(path: Path) = throw UnsupportedOperationException("fixture")
        }
        assertEquals(StorageStatus.UNSUPPORTED, store(failing).begin(ticket, StorageFixture.identity))
        assertFalse(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `sync uncertainty after anchor write retains encrypted recovery`() {
        val fs = object : ModeledDirectorySync() {
            override fun syncDirectory(path: Path) {
                super.syncDirectory(path)
                if (syncs == 3) throw java.io.IOException("fixture")
            }
        }
        val s = store(fs)
        assertEquals(StorageStatus.IO_UNCERTAIN, s.begin(ticket, StorageFixture.identity))
        assertTrue(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
        assertEquals(
            StorageStatus.STALE,
            s.prepare(StorageFixture.evidence(ticket), DirectShellStagedAuthorization {}).status,
        )
        assertTrue(s.close())
        assertEquals(StorageStatus.RECOVERY_RECONCILIATION, inspection())
    }

    @Test
    fun `interrupted partial anchor is retained and blocks a new writer`() {
        val fs = object : ModeledDirectorySync() {
            override fun writeNew(path: Path, bytes: ByteArray) {
                super.writeNew(path, bytes.copyOf(20))
                throw java.io.IOException("fixture")
            }
        }
        val s = store(fs)
        assertEquals(StorageStatus.IO_UNCERTAIN, s.begin(ticket, StorageFixture.identity))
        assertTrue(s.close())
        assertEquals(StorageStatus.CORRUPT, inspection())
        assertEquals(StorageStatus.CORRUPT, store().begin(ticket, StorageFixture.identity))
        assertEquals(20L, Files.size(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `restart anchor classification has no automatic cleanup or consent`() {
        val s = begin()
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR))
        assertTrue(s.close())
        repeat(3) { assertEquals(StorageStatus.RECOVERY_RECONCILIATION, inspection()) }
        assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `candidate evidence must match exact attempt and local identity`() {
        val s = begin()
        val foreign = DirectShellPairingTicket("fixture-owner", "fixture-attempt", 1, 7, 0)
        assertEquals(
            StorageStatus.STALE,
            s.prepare(StorageFixture.evidence(foreign), DirectShellStagedAuthorization {}).status,
        )
        val wrong = DirectShellAuthenticatedPairingEvidence(ticket, ByteArray(32), ByteArray(32))
        assertEquals(StorageStatus.INVALID_MATERIAL, s.prepare(wrong, DirectShellStagedAuthorization {}).status)
        assertFalse(Files.exists(path(DirectShellCredentialStorage.PENDING)))
    }

    @Test
    fun `authenticated evidence snapshots caller arrays`() {
        val s = begin()
        val local = DirectShellCredentialCodec.digest(StorageFixture.identity.certificate.encoded)
        val peer = ByteArray(32) { 9 }
        val evidence = DirectShellAuthenticatedPairingEvidence(ticket, local, peer)
        local.fill(0)
        peer.fill(0)
        assertEquals(StorageStatus.PREPARED, s.prepare(evidence, DirectShellStagedAuthorization {}).status)
        assertArrayEquals(
            ByteArray(32) { 9 },
            DirectShellCredentialCodec.decode(
                Files.readAllBytes(path(DirectShellCredentialStorage.PENDING)),
                StorageFixture.key,
            ).peerDigest,
        )
    }

    @Test
    fun `candidate is encrypted complete and cannot be prepared twice`() {
        val s = begin()
        val c = prepare(s)
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.PENDING))
        assertFalse(StorageFixture.contains(bytes, StorageFixture.identity.privateKey.encoded))
        assertEquals(
            StorageStatus.STALE,
            s.prepare(StorageFixture.evidence(ticket), DirectShellStagedAuthorization {}).status,
        )
        assertEquals("DirectShellCredentialCandidate(redacted)", c.toString())
        assertTrue(s.close())
        assertEquals(StorageStatus.PREPARED_RECONCILIATION, inspection())
    }

    @Test
    fun `interrupted candidate never authorizes publication`() {
        val fs = object : ModeledDirectorySync() {
            override fun writeNew(path: Path, bytes: ByteArray) {
                if (path.fileName.toString() == DirectShellCredentialStorage.PENDING) {
                    super.writeNew(path, bytes.copyOf(10))
                    throw java.io.IOException("fixture")
                }
                super.writeNew(path, bytes)
            }
        }
        val s = begin(store(fs))
        assertEquals(
            StorageStatus.IO_UNCERTAIN,
            s.prepare(
                StorageFixture.evidence(
                    ticket,
                ),
                DirectShellStagedAuthorization {
                },
            ).status,
        )
        assertTrue(s.close())
        assertEquals(StorageStatus.CORRUPT, inspection())
        assertFalse(Files.exists(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `real hard link publication is whole no replace and separate from cleanup`() {
        val s = begin()
        val c = prepare(s)
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.PENDING))
        val receipt = publish(s, c)
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(1L, receipt.publishedCredentialRevision)
        assertTrue(receipt.cleanupComplete)
        assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.RECORD)))
        assertFalse(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
        assertTrue(s.close())
        assertEquals(StorageStatus.COMMITTED_RECONCILIATION, inspection())
    }

    @Test
    fun `existing record wins actual storage CAS and is never replaced`() {
        val s = begin()
        val c = prepare(s)
        val foreign = byteArrayOf(4, 5, 6)
        Files.write(path(DirectShellCredentialStorage.RECORD), foreign)
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, publish(s, c).publication)
        assertArrayEquals(foreign, Files.readAllBytes(path(DirectShellCredentialStorage.RECORD)))
        assertTrue(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `unsupported atomic link has no copy or replace fallback`() {
        val fs = object : ModeledDirectorySync() {
            override fun linkNoReplace(record: Path, prepared: Path) = throw UnsupportedOperationException("fixture")
        }
        val s = begin(store(fs))
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, publish(s).publication)
        assertFalse(Files.exists(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `exception after entering link cannot imply no publication`() {
        val fs = object : ModeledDirectorySync() {
            override fun linkNoReplace(record: Path, prepared: Path) {
                super.linkNoReplace(record, prepared)
                throw java.io.IOException("fixture secret must not escape")
            }
        }
        val s = begin(store(fs))
        val result = publish(s)
        assertEquals(DirectShellPairingPublication.UNKNOWN, result.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, result.durability)
        assertFalse(result.toString().contains("secret"))
        assertTrue(s.close())
        assertEquals(StorageStatus.COMMITTED_CLEANUP_PENDING, inspection())
    }

    @Test
    fun `publication with failed directory sync is preserved but unconfirmed`() {
        val fs = object : ModeledDirectorySync() {
            var published = false
            override fun linkNoReplace(record: Path, prepared: Path) {
                super.linkNoReplace(record, prepared)
                published = true
            }
            override fun syncDirectory(path: Path) {
                if (published) throw java.io.IOException("fixture")
                super.syncDirectory(path)
            }
        }
        val s = begin(store(fs))
        val result = publish(s)
        assertEquals(DirectShellPairingPublication.PUBLISHED, result.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, result.durability)
        assertFalse(result.cleanupComplete)
        assertTrue(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `cleanup failure is not publication failure and never deletes committed entry`() {
        val fs = object : ModeledDirectorySync() {
            override fun removeRedundant(path: Path) = throw java.io.IOException("fixture")
        }
        val s = begin(store(fs))
        val result = publish(s)
        assertEquals(DirectShellPairingPublication.PUBLISHED, result.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, result.durability)
        assertFalse(result.cleanupComplete)
        assertTrue(s.close())
        assertEquals(StorageStatus.COMMITTED_CLEANUP_PENDING, inspection())
    }

    @Test
    fun `missing key at restart preserves ciphertext and releases inspection lease`() {
        val s = begin()
        assertTrue(s.close())
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR))
        key = null
        assertEquals(StorageStatus.KEY_UNAVAILABLE, inspection())
        key = StorageFixture.key
        assertEquals(StorageStatus.RECOVERY_RECONCILIATION, inspection())
        assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `wrong wrapping key does not repair or replace encrypted identity`() {
        val s = begin()
        assertTrue(s.close())
        key = SecretKeySpec(ByteArray(32) { 99 }, "AES")
        assertEquals(StorageStatus.CORRUPT, inspection())
        assertEquals(StorageStatus.CORRUPT, store().begin(ticket, StorageFixture.identity))
    }

    @Test
    fun `corrupt tag header oversize trailing and incomplete records remain blocking`() {
        val s = begin()
        assertTrue(s.close())
        val original = Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR))
        val variants = listOf(
            byteArrayOf(1),
            original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
            original.copyOf().apply { this[4] = 99 },
            original + byteArrayOf(1),
            ByteArray(65_537),
        )
        variants.forEach { bytes ->
            Files.write(path(DirectShellCredentialStorage.ANCHOR), bytes)
            assertEquals(StorageStatus.CORRUPT, inspection())
            assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.ANCHOR)))
        }
    }

    @Test
    fun `duplicate reservation claim never publishes twice`() {
        val s = begin()
        val c = prepare(s)
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        val first = s.publish(c, r)
        assertEquals(DirectShellPairingPublication.PUBLISHED, first.publication)
        val before = Files.readAllBytes(path(DirectShellCredentialStorage.RECORD))
        assertEquals(first, s.publish(c, r))
        assertArrayEquals(before, Files.readAllBytes(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `foreign reservation and foreign candidate have no authority`() {
        val s = begin()
        val c = prepare(s)
        val foreign = DirectShellPairingPreparedCommit(ticket, DirectShellStagedAuthorization {})
        assertEquals(
            DirectShellPairingPublication.NOT_PUBLISHED,
            s.publish(c, DirectShellPairingCommitReservation.issue(foreign)).publication,
        )
        assertFalse(Files.exists(path(DirectShellCredentialStorage.RECORD)))
        assertFalse(DirectShellPairingCommitReservation.issue(c.prepared).wasClaimed)
    }

    @Test
    fun `modified candidate consumes its execution but cannot pass digest or publish`() {
        val s = begin()
        val c = prepare(s)
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        Files.write(path(DirectShellCredentialStorage.PENDING), byteArrayOf(1, 2))
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, s.publish(c, r).publication)
        assertTrue(r.wasClaimed)
        assertFalse(Files.exists(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `unclaimed stopped reservation abstains and preserves recovery`() {
        val s = begin()
        val c = prepare(s)
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        r.abstainUnclaimed()
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, s.publish(c, r).publication)
        assertTrue(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test
    fun `same JVM competing writer cannot reopen or close stable inode`() {
        val s = begin()
        val lockBefore = DirectShellCredentialFileSystem.attributes(path(DirectShellCredentialStorage.LOCK))!!.fileKey()
        assertEquals(StorageStatus.BUSY, store().begin(ticket, StorageFixture.identity))
        assertEquals(StorageStatus.BUSY, inspection())
        assertEquals(
            lockBefore,
            DirectShellCredentialFileSystem.attributes(path(DirectShellCredentialStorage.LOCK))!!.fileKey(),
        )
        assertEquals(DirectShellPairingPublication.PUBLISHED, publish(s).publication)
    }

    @Test
    fun `close before publication never discards recoverable local identity`() {
        val s = begin()
        val c = prepare(s)
        assertTrue(s.close())
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, publish(s, c).publication)
        assertEquals(StorageStatus.PREPARED_RECONCILIATION, inspection())
    }

    @Test
    fun `bounded namespace rejects unknown records without scanning history`() {
        val s = begin()
        assertTrue(s.close())
        Files.write(path("unexpected"), byteArrayOf(1))
        assertEquals(StorageStatus.CORRUPT, inspection())
        assertEquals(StorageStatus.CORRUPT, store().begin(ticket, StorageFixture.identity))
    }

    @Test
    fun `committed identity blocks a new enrollment without changing original bytes`() {
        val s = begin()
        publish(s)
        assertTrue(s.close())
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.RECORD))
        assertEquals(StorageStatus.COMMITTED_RECONCILIATION, store().begin(ticket, StorageFixture.identity))
        assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `prepared record without anchor remains reconciliation only`() {
        val s = begin()
        prepare(s)
        assertTrue(s.close())
        Files.delete(path(DirectShellCredentialStorage.ANCHOR))
        assertEquals(StorageStatus.PREPARED_RECONCILIATION, inspection())
        assertFalse(Files.exists(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test
    fun `mixed transaction bindings are corrupt rather than adopted`() {
        val s = begin()
        prepare(s)
        assertTrue(s.close())
        val other = DirectShellPairingTicket("other-owner", "other-attempt", 1, 7, 0)
        val record = DirectShellCredentialCodec.Record(
            DirectShellCredentialCodec.Kind.RECOVERY,
            DirectShellCredentialCodec.binding(other),
            StorageFixture.identity,
            null,
        )
        Files.write(
            path(DirectShellCredentialStorage.ANCHOR),
            DirectShellCredentialCodec.encode(record, StorageFixture.key),
        )
        assertEquals(StorageStatus.CORRUPT, inspection())
    }

    @Test
    fun `namespace replaced by regular file is unavailable not overwritten`() {
        val name = base.resolve(DirectShellCredentialStorage.DIRECTORY)
        val original = byteArrayOf(7, 8)
        Files.write(name, original)
        assertEquals(StorageStatus.IO_UNCERTAIN, store().begin(ticket, StorageFixture.identity))
        assertArrayEquals(original, Files.readAllBytes(name))
        assertEquals(StorageStatus.CORRUPT, inspection())
    }

    @Test
    fun `concurrent close cannot claim physical drain during actual publication`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fs = object : ModeledDirectorySync() {
            override fun linkNoReplace(record: Path, prepared: Path) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                super.linkNoReplace(record, prepared)
            }
        }
        val s = begin(store(fs))
        val c = prepare(s)
        val task = FutureTask { publish(s, c) }
        val thread = Thread(task, "test-owned-storage")
        thread.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(s.close())
            assertEquals(StorageStatus.BUSY, store().begin(ticket, StorageFixture.identity))
        } finally {
            release.countDown()
            assertNotNull(task.get(10, TimeUnit.SECONDS))
            thread.join(1000)
        }
        assertTrue(s.close())
    }
}

/** This NO-OP models a qualified sync receipt; it is never a production or Windows durability claim. */
internal open class ModeledDirectorySync : DirectShellCredentialFileSystem() {
    var syncs = 0
    override fun syncDirectory(path: Path) {
        syncs++
    }
}

internal object StorageFixture {
    val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    val identity: DirectShellCredentialIdentity by lazy {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Storage Canary")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
        )
        DirectShellCredentialIdentity(pair.private, certificate)
    }

    fun evidence(ticket: DirectShellPairingTicket) = DirectShellAuthenticatedPairingEvidence(
        ticket,
        DirectShellCredentialCodec.digest(identity.certificate.encoded),
        ByteArray(32) { 9 },
    )

    fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        needle.size <= bytes.size && (0..bytes.size - needle.size).any { start ->
            needle.indices.all { offset -> bytes[start + offset] == needle[offset] }
        }
}
