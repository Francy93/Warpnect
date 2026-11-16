package io.warpnect.platform.privileged.directshell.pairing

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileDescriptor
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform operations on an explicitly authorized disposable fixture; unsupported is not a skip. */
@RunWith(AndroidJUnit4::class)
class AndroidPairingStorageInstrumentationTest {
    private lateinit var fixture: AndroidPairingStorageFixture

    @Before fun createFixture() {
        fixture = AndroidPairingStorageFixture()
    }

    @After fun releaseFixture() {
        fixture.close()
    }

    @Test
    fun factoryAndInspectionNeverProvisionKeyOrIdentity() {
        val adapter = fixture.adapter()
        assertEquals(DirectShellKeyAvailability.NOT_CHECKED, fixture.keys.evidence.availability)
        assertFalse(Files.exists(fixture.directory))
        assertEquals(DirectShellCredentialStorage.Status.EMPTY, adapter.storage.inspect().status)
        assertNull(fixture.keys.existingAead())
        assertEquals(DirectShellKeyAvailability.MISSING, fixture.keys.evidence.availability)
        assertFalse(Files.exists(fixture.directory))
    }

    @Test
    fun realDirectorySynchronizationAndFileSynchronizationAreDistinct() {
        val adapter = fixture.adapter()
        fixture.provision()
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_READY,
            adapter.storage.begin(fixture.ticket, AndroidPairingStorageFixture.identity),
        )
        assertEquals(DirectShellStorageObservation.OBSERVED, adapter.filesystem.evidence.dataSync)
        assertEquals(DirectShellStorageObservation.OBSERVED, adapter.filesystem.evidence.directorySync)
        assertTrue(adapter.filesystem.resourcesDrained())
    }

    @Test
    fun canonicalPrivateBoundaryRejectsOutsideAndSymlinkArtifacts() {
        val adapter = fixture.adapter()
        assertThrows(DirectShellCredentialPlatformFailure::class.java) {
            adapter.filesystem.canonicalBase(fixture.base.parent)
        }
        adapter.filesystem.prepareNamespace(fixture.base)
        val outside = fixture.base.resolve("owned-outside-marker")
        Files.write(outside, byteArrayOf(5))
        val pending = fixture.directory.resolve(DirectShellCredentialStorage.PENDING)
        Files.createSymbolicLink(pending, outside)
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { adapter.filesystem.read(pending) }
        assertArrayEquals(byteArrayOf(5), Files.readAllBytes(outside))
    }

    @Test
    fun realAtomicPublicationPreservesWholeEncryptedRecordAndNoReplacement() {
        fixture.provision()
        val s = fixture.store()
        val candidate = fixture.begin(s)
        val before = Files.readAllBytes(fixture.directory.resolve(DirectShellCredentialStorage.PENDING))
        val r = DirectShellPairingCommitReservation.issue(candidate.prepared)
        val receipt = s.publish(candidate, r)
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, receipt.durability)
        assertArrayEquals(before, Files.readAllBytes(fixture.record))
        assertEquals(receipt, s.publish(candidate, r))
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION, fixture.store().inspect().status)
    }

    @Test
    fun existingCommittedEntryWinsNoReplaceRace() {
        fixture.provision()
        val s = fixture.store()
        val candidate = fixture.begin(s)
        val original = byteArrayOf(4, 8)
        Files.write(fixture.record, original)
        val result = s.publish(candidate, DirectShellPairingCommitReservation.issue(candidate.prepared))
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, result.publication)
        assertArrayEquals(original, Files.readAllBytes(fixture.record))
    }

    @Test
    fun twoAdaptersShareStableLeaseSameProcessNotCrossProcessQualification() {
        fixture.provision()
        val first = fixture.store()
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_READY,
            first.begin(fixture.ticket, AndroidPairingStorageFixture.identity),
        )
        assertEquals(
            DirectShellCredentialStorage.Status.BUSY,
            fixture.store().begin(fixture.ticket, AndroidPairingStorageFixture.identity),
        )
        assertEquals(DirectShellCredentialStorage.Status.BUSY, fixture.store().inspect().status)
        assertTrue(first.close())
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION, fixture.store().inspect().status)
    }

    @Test
    fun realKeystoreNonExportableKeyEncryptsAndDecryptsBoundedRecord() {
        fixture.provision()
        assertNull(fixture.syntheticKey().encoded)
        val aead = requireNotNull(fixture.keys.existingAead())
        val clear = "synthetic audio-free credential fixture".toByteArray()
        val aad = ByteArray(52) { it.toByte() }
        val encrypted = aead.seal(clear, aad)
        assertArrayEquals(clear, aead.open(encrypted.ciphertext, encrypted.iv, aad))
        assertEquals(12, encrypted.iv.size)
        assertEquals(DirectShellKeyAvailability.AVAILABLE, fixture.keys.evidence.availability)
        // Hardware level is collected where available, never required or inferred here.
        assertFalse(encrypted.toString().contains("synthetic"))
    }

    @Test
    fun keyRandomizationRequiresProviderIVNotCallerIV() {
        fixture.provision()
        assertThrows(java.security.InvalidAlgorithmParameterException::class.java) {
            Cipher.getInstance("AES/GCM/NoPadding").init(
                Cipher.ENCRYPT_MODE,
                fixture.syntheticKey(),
                GCMParameterSpec(128, ByteArray(12)),
            )
        }
        val aead = requireNotNull(fixture.keys.existingAead())
        val a = aead.seal(ByteArray(10), ByteArray(52))
        val b = aead.seal(ByteArray(10), ByteArray(52))
        assertFalse(a.iv.contentEquals(b.iv))
    }

    @Test
    fun invalidTagAndAADNeverYieldAuthenticatedPlaintext() {
        fixture.provision()
        val aead = requireNotNull(fixture.keys.existingAead())
        val encrypted = aead.seal(ByteArray(100), ByteArray(52))
        val damaged = encrypted.ciphertext.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { aead.open(damaged, encrypted.iv, ByteArray(52)) }
        assertThrows(AEADBadTagException::class.java) {
            aead.open(encrypted.ciphertext, encrypted.iv, ByteArray(52) { 7 })
        }
    }

    @Test
    fun wrongOwnedKeyCannotDecryptPreviousRecord() {
        fixture.provision()
        val encrypted = fixture.keys.existingAead()!!.seal(ByteArray(100), ByteArray(52))
        val other = fixture.alias + "-other"
        fixture.provision(other)
        val wrong = AndroidDirectShellCredentialKeyStore(other).existingAead()!!
        assertThrows(AEADBadTagException::class.java) { wrong.open(encrypted.ciphertext, encrypted.iv, ByteArray(52)) }
    }

    @Test
    fun unqualifiedKeyPurposesRemainUnavailable() {
        fixture.provision(purposes = KeyProperties.PURPOSE_ENCRYPT)
        assertNull(fixture.keys.existingAead())
        assertEquals(DirectShellKeyAvailability.POLICY_REJECTED, fixture.keys.evidence.availability)
        assertFalse(Files.exists(fixture.directory))
    }

    @Test
    fun missingKeyAfterRecordCreationPreservesCiphertextWithoutRepair() {
        fixture.provision()
        val s = fixture.store()
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_READY,
            s.begin(fixture.ticket, AndroidPairingStorageFixture.identity),
        )
        assertTrue(s.close())
        val path = fixture.directory.resolve(DirectShellCredentialStorage.ANCHOR)
        val before = Files.readAllBytes(path)
        fixture.removeOwnKey()
        assertEquals(DirectShellCredentialStorage.Status.KEY_UNAVAILABLE, fixture.store().inspect().status)
        assertArrayEquals(before, Files.readAllBytes(path))
        assertNull(fixture.keys.existingAead())
    }

    @Test
    fun truncatedAndCorruptRecordRemainBlockedAtRestart() {
        fixture.provision()
        val s = fixture.store()
        fixture.begin(s)
        assertTrue(s.close())
        val path = fixture.directory.resolve(DirectShellCredentialStorage.PENDING)
        Files.write(path, byteArrayOf(1, 2, 3))
        assertEquals(DirectShellCredentialStorage.Status.CORRUPT, fixture.store().inspect().status)
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(path))
    }

    @Test
    fun injectedPostPublicationUncertaintyPreservesRealCommittedEntry() {
        fixture.provision()
        val delegate = AndroidCredentialOs()
        val os = object : DirectShellCredentialOs by delegate {
            override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
                delegate.publish(directory)
                throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNKNOWN)
            }
        }
        val s = fixture.store(AndroidDirectShellCredentialFileSystem(fixture.base, os))
        val candidate = fixture.begin(s)
        val receipt = s.publish(candidate, DirectShellPairingCommitReservation.issue(candidate.prepared))
        assertEquals(DirectShellPairingPublication.UNKNOWN, receipt.publication)
        assertTrue(Files.exists(fixture.record))
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING, fixture.store().inspect().status)
    }

    @Test
    fun injectedCleanupFailureDoesNotUndoPublication() {
        fixture.provision()
        val os = object : DirectShellCredentialOs by AndroidCredentialOs() {
            override fun unlink(path: java.nio.file.Path) {
                throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNKNOWN)
            }
        }
        val s = fixture.store(AndroidDirectShellCredentialFileSystem(fixture.base, os))
        val candidate = fixture.begin(s)
        val receipt = s.publish(candidate, DirectShellPairingCommitReservation.issue(candidate.prepared))
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertFalse(receipt.cleanupComplete)
        assertTrue(Files.exists(fixture.record))
    }

    @Test
    fun interruptedAnchorWriteRemainsBlockedWithoutRecoveryPermission() {
        fixture.provision()
        val delegate = AndroidCredentialOs()
        val os = object : DirectShellCredentialOs by delegate {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
                delegate.write(fd, bytes, offset, minOf(count, 20))
                throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNKNOWN)
            }
        }
        val s = fixture.store(AndroidDirectShellCredentialFileSystem(fixture.base, os))
        assertEquals(
            DirectShellCredentialStorage.Status.IO_UNCERTAIN,
            s.begin(fixture.ticket, AndroidPairingStorageFixture.identity),
        )
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.CORRUPT, fixture.store().inspect().status)
    }

    @Test
    fun publicationWithoutDirectorySyncCannotBecomeDurableSuccess() {
        fixture.provision()
        val delegate = AndroidCredentialOs()
        val os = object : DirectShellCredentialOs by delegate {
            override fun sync(fd: FileDescriptor) {
                if (Files.exists(fixture.record)) {
                    throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNKNOWN)
                }
                delegate.sync(fd)
            }
        }
        val s = fixture.store(AndroidDirectShellCredentialFileSystem(fixture.base, os))
        val c = fixture.begin(s)
        val receipt = s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, receipt.durability)
        assertFalse(receipt.cleanupComplete)
        assertTrue(Files.exists(fixture.record))
    }

    @Test
    fun injectedAmbiguousCloseCannotClaimDrainAndIsNeverRetried() {
        val delegate = AndroidCredentialOs()
        var closes = 0
        val os = object : DirectShellCredentialOs by delegate {
            override fun close(fd: FileDescriptor) {
                delegate.close(fd) // Test physically releases it, then models ambiguous syscall knowledge.
                closes++
                throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNKNOWN)
            }
        }
        val fs = AndroidDirectShellCredentialFileSystem(fixture.base, os)
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(fixture.base) }
        assertFalse(fs.resourcesDrained())
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(fixture.base) }
        assertEquals(1, closes)
        assertTrue(fs.evidence.cleanupIncomplete)
    }
}
