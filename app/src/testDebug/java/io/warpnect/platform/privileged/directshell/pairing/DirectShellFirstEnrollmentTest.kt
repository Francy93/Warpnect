package io.warpnect.platform.privileged.directshell.pairing

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private typealias Status = DirectShellCredentialStorage.Status

/** AndroidKeyStore, sync acknowledgements and faults are modeled; files, locks and crypto are real. */
class DirectShellFirstEnrollmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base get() = temporary.root.toPath()
    private val dir get() = base.resolve(DirectShellCredentialStorage.DIRECTORY)
    private val anchor get() = dir.resolve(DirectShellCredentialStorage.ANCHOR)
    private val keys = ModeledFirstEnrollmentKey()
    private val stores = mutableListOf<DirectShellCredentialStorage>()
    private val ticket = DirectShellPairingTicket("owner", "attempt", 1, 2, 0)
    private var now = 1L
    private var admitted = true
    private var active = true
    private val scheduler = DirectShellPairingScheduler { _, _ -> DirectShellPairingTimer {} }
    private val clock = DirectShellPairingClock { now }
    private fun work(owner: DirectShellPairingTicket = ticket) =
        DirectShellPairingWorkContext(owner, 10, clock, scheduler) { admitted }

    private fun store(fs: DirectShellCredentialFileSystem = ModeledDirectorySync()) =
        DirectShellCredentialStorage(base, keys, fs).also(stores::add)

    private fun begin(store: DirectShellCredentialStorage = store()) =
        store.beginFirstEnrollment(ticket, work(), { active }) { StorageFixture.identity }

    @After fun closeStores() = stores.forEach { assertTrue(it.close()) }

    @Test fun authenticatedReadbackOnTheOriginalLeaseAndOneShotAdmission() {
        assertEquals(Status.EMPTY, store().inspect().status)
        assertFalse(Files.exists(dir))
        assertEquals(0, keys.creations)
        val owner = store()
        val first = begin(owner)
        assertEquals(Status.RECOVERY_READY, first.status)
        assertSame(StorageFixture.identity, first.identity)
        assertEquals(Status.STALE, begin(owner).status)
        assertEquals(Status.BUSY, begin().status)
        assertEquals(Status.RECOVERY_READY, owner.recoveryAdmission(ticket))
        assertEquals(Status.STALE, owner.recoveryAdmission(DirectShellPairingTicket("owner", "attempt", 1, 2, 0)))
        val bytes = Files.readAllBytes(anchor)
        assertFalse(StorageFixture.contains(bytes, StorageFixture.identity.privateKey.encoded))
        assertTrue(owner.close())
        assertEquals(Status.RECOVERY_RECONCILIATION, store().inspect().status)
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertEquals(1, keys.creations)
        assertArrayEquals(bytes, Files.readAllBytes(anchor))
    }

    @Test fun noDefaultAuthorityAndNoEquivalentOrNewerTicket() {
        val wrong = DirectShellPairingTicket("owner", "attempt", 1, 2, 0)
        val newer = DirectShellPairingTicket("owner", "attempt", 1, 2, 1)
        val plain = DirectShellPairingWorkContext(ticket, 10, clock, scheduler)
        fun start(ticket: DirectShellPairingTicket, work: DirectShellPairingWorkContext) =
            store().beginFirstEnrollment(ticket, work, { true }) { error("identity_not_allowed") }.status
        assertEquals(Status.STALE, start(ticket, plain))
        assertEquals(Status.STALE, start(ticket, work(wrong)))
        assertEquals(Status.REVISION_CONFLICT, start(newer, work(newer)))
        assertEquals(0, keys.probes)
        assertFalse(Files.exists(dir))
    }

    @Test fun unknownNamespaceEntryBlocksEvenWithMissingAlias() {
        Files.createDirectory(dir)
        val foreign = dir.resolve("foreign")
        Files.write(foreign, byteArrayOf(7))
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertEquals(0, keys.probes)
        assertEquals(0, keys.creations)
        assertArrayEquals(byteArrayOf(7), Files.readAllBytes(foreign))
    }

    @Test fun keyProbeFailureCannotBeInterpretedAsAbsence() {
        keys.probe = { error("key_store_unreachable") }
        assertEquals(Status.IO_UNCERTAIN, store().inspect().status)
        assertEquals(Status.IO_UNCERTAIN, begin().status)
        assertEquals(0, keys.creations)
        assertFalse(Files.exists(anchor))
    }

    @Test fun orphanAliasIsNotAdoptedEvenWithoutNamespace() {
        keys.key = StorageFixture.key
        assertEquals(Status.PROVISIONING_RECONCILIATION, store().inspect().status)
        assertFalse(Files.exists(dir))
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertEquals(0, keys.creations)
        assertSame(StorageFixture.key, keys.key)
    }

    @Test fun lostKeyOrWrongKeyNeverRepairsAnExistingAnchor() {
        val owner = store()
        assertEquals(Status.RECOVERY_READY, begin(owner).status)
        assertTrue(owner.close())
        val bytes = Files.readAllBytes(anchor)
        keys.key = null
        assertEquals(Status.KEY_UNAVAILABLE, store().inspect().status)
        val missing = store()
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin(missing).status)
        assertTrue(missing.close())
        keys.key = SecretKeySpec(ByteArray(32), "AES")
        assertEquals(Status.CORRUPT, store().inspect().status)
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertArrayEquals(bytes, Files.readAllBytes(anchor))
        assertEquals(1, keys.creations)
    }

    @Test fun cancellationOrExpiryBeforeProviderCallLeavesAliasAbsent() {
        keys.beforeGenerate = { now = 10 }
        val expiring = store()
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin(expiring).status)
        assertTrue(expiring.close())
        now = 1
        keys.beforeGenerate = { active = false }
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertFalse(Files.exists(anchor))
        assertEquals(0, keys.creations)
    }

    @Test fun cancellationAfterProviderCallRetainsOrphanAndBlocksFreshAttempt() {
        keys.afterGenerate = { admitted = false }
        val cancelled = store()
        val result = begin(cancelled)
        assertEquals(Status.PROVISIONING_RECONCILIATION, result.status)
        assertNull(result.identity)
        assertTrue(cancelled.close())
        assertFalse(Files.exists(anchor))
        admitted = true
        assertEquals(Status.PROVISIONING_RECONCILIATION, store().inspect().status)
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertEquals(1, keys.creations)
    }

    @Test fun lostCreateAcknowledgementLeavesOrphanWithoutRetry() {
        keys.afterGenerate = { error("response_lost_after_create") }
        val interrupted = store()
        assertEquals(Status.IO_UNCERTAIN, begin(interrupted).status)
        assertTrue(interrupted.close())
        assertFalse(Files.exists(anchor))
        assertEquals(Status.PROVISIONING_RECONCILIATION, begin().status)
        assertEquals(1, keys.creations)
    }

    @Test fun noReplacePublicationCollisionKeepsExistingRecordAndRecovery() {
        val owner = store()
        assertEquals(Status.RECOVERY_READY, begin(owner).status)
        val pending = requireNotNull(
            owner.prepare(StorageFixture.evidence(ticket), DirectShellStagedAuthorization {}).candidate,
        )
        val record = dir.resolve(DirectShellCredentialStorage.RECORD)
        val foreign = byteArrayOf(4, 5, 6)
        Files.write(record, foreign)
        val reservation = DirectShellPairingCommitReservation.issue(pending.prepared)
        val result = owner.publish(pending, reservation)
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, result.publication)
        assertEquals(result, owner.publish(pending, reservation))
        assertArrayEquals(foreign, Files.readAllBytes(record))
        assertTrue(Files.exists(anchor))
    }

    @Test fun independentJvmOwningEmptyNamespaceFencesFirstKeyCreation() {
        val fs = ModeledDirectorySync()
        fs.prepareNamespace(base)
        requireNotNull(fs.acquireLease(dir, true)).also { assertTrue(it.close()) }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val cp = Path.of(
            requireNotNull(DirectShellStorageProcess::class.java.protectionDomain).codeSource.location.toURI(),
        )
        val child = ProcessBuilder(
            java,
            "-cp",
            cp.toString(),
            DirectShellStorageProcess::class.java.name,
            "hold",
            dir.toString(),
        ).redirectErrorStream(true).start()
        val reader = child.inputStream.bufferedReader()
        val line = FutureTask { reader.readLine() }
        val observer = Thread(line, "first-enrollment-test-lease").also { it.start() }
        try {
            assertEquals("HELD", line.get(10, TimeUnit.SECONDS))
            val competitor = store()
            assertEquals(Status.BUSY, begin(competitor).status)
            assertTrue(competitor.close())
            assertEquals(0, keys.probes)
            assertEquals(0, keys.creations)
            child.outputStream.write(1)
            child.outputStream.flush()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            assertEquals(Status.RECOVERY_READY, begin().status)
            assertEquals(1, keys.creations)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            observer.join(1000)
            assertFalse(observer.isAlive)
            reader.close()
            child.outputStream.close()
            child.errorStream.close()
        }
    }
}

internal class ModeledFirstEnrollmentKey : DirectShellFirstEnrollmentKey {
    var key: SecretKey? = null
    var probes = 0
    var creations = 0
    var probe: () -> Unit = {}
    var beforeGenerate: () -> Unit = {}
    var afterGenerate: () -> Unit = {}

    override fun aliasAbsent(): Boolean {
        probe()
        probes++
        return key == null
    }

    override fun generateIfAbsent(mayGenerate: () -> Boolean): Boolean {
        beforeGenerate()
        if (!aliasAbsent() || !mayGenerate()) return false
        creations++
        key = StorageFixture.key
        afterGenerate()
        return true
    }

    override fun existing() = key
}
