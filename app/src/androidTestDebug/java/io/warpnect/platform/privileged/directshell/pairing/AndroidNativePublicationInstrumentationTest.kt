package io.warpnect.platform.privileged.directshell.pairing

import android.security.keystore.KeyInfo
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Production JNI, real encrypted candidates/Keystore/OS; fault points are explicitly test-owned. */
class AndroidNativePublicationInstrumentationTest {
    private lateinit var f: AndroidPairingStorageFixture

    @Before fun setup() {
        f = AndroidPairingStorageFixture()
    }

    @After fun close() {
        f.close()
    }

    @Test fun actualProductionPublisherConsumesPendingWithNativeSuccess() {
        f.provision()
        val adapter = f.adapter()
        val c = f.begin(adapter.storage)
        val bytes = Files.readAllBytes(f.directory.resolve(DirectShellCredentialStorage.PENDING))
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        val receipt = adapter.storage.publish(c, r)
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, receipt.durability)
        assertTrue(receipt.cleanupComplete)
        assertFalse(Files.exists(f.directory.resolve(DirectShellCredentialStorage.PENDING)))
        assertArrayEquals(bytes, Files.readAllBytes(f.record))
        assertEquals(DirectShellCredentialSource.CONSUMED, adapter.filesystem.evidence.publicationSource)
        assertEquals(0, adapter.filesystem.evidence.publicationErrno)
        assertEquals(receipt, adapter.storage.publish(c, r))
        assertTrue(adapter.filesystem.resourcesDrained())
        f.emit(
            "JNI=production P=published source=consumed errno=0 S=sync_confirmed cached_receipt=identical D=fs_drained",
        )
    }

    @Test fun existingSymlinkRecordIsNeverReplacedByAuthorizedPublication() {
        f.provision()
        val adapter = f.adapter()
        val c = f.begin(adapter.storage)
        val pending = f.directory.resolve(DirectShellCredentialStorage.PENDING)
        val record = f.directory.resolve(DirectShellCredentialStorage.RECORD)
        val bytes = Files.readAllBytes(pending)
        Files.createSymbolicLink(record, pending.fileName)
        val receipt = adapter.storage.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, receipt.publication)
        assertTrue(Files.isSymbolicLink(record))
        assertArrayEquals(bytes, adapter.filesystem.read(pending))
        assertEquals(17, adapter.filesystem.evidence.publicationErrno)
        f.emit("JNI=production collision_errno=17 symlink_preserved=1 encrypted_source=retained")
    }

    @Test fun api29GateLeavesProductionLibraryUnloaded() {
        val marker = "libwarpnect_directshell_publication.so"
        assertFalse(File("/proc/self/maps").readText().contains(marker))
        val os = AndroidCredentialOs { 29 }
        assertEquals(DirectShellCredentialPublishResult.UNAVAILABLE, os.publish(FileDescriptor()))
        assertFalse(File("/proc/self/maps").readText().contains(marker))
        assertTrue(os.resourcesDrained())
        f.emit("modeled_API29_on_real_API30 gate=unavailable native_load=absent")
    }

    @Test fun keyEvidenceUsesSyntheticNonExportableAes256Only() {
        f.provision()
        val key = f.syntheticKey()
        assertEquals(null, key.encoded)
        val info = SecretKeyFactory.getInstance(
            key.algorithm,
            "AndroidKeyStore",
        ).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        f.emit(
            "keystore_bits=256 exportable=false inside_secure_hardware=${info.isInsideSecureHardware} " +
                "production_alias_used=false",
        )
    }

    @Test fun successfulRenameWithLostAcknowledgementRetainsUnknownP() {
        f.provision()
        val native = AndroidCredentialOs()
        val io = object : DirectShellCredentialOs by native {
            override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
                assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, native.publish(directory))
                throw java.io.IOException("synthetic_lost_receipt")
            }
        }
        val s = f.store(AndroidDirectShellCredentialFileSystem(f.base, io))
        val c = f.begin(s)
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        val receipt = s.publish(c, r)
        assertEquals(DirectShellPairingPublication.UNKNOWN, receipt.publication)
        assertEquals(receipt, s.publish(c, r))
        assertFalse(Files.exists(f.directory.resolve(DirectShellCredentialStorage.PENDING)))
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING, f.store().inspect().status)
        f.emit("P=unknown injected_lost_ack=1 actual_record_present=1 cached_unknown=1")
    }

    @Test fun sourceValidationRejectsSymlinkBeforeRename() {
        f.provision()
        val adapter = f.adapter()
        val c = f.begin(adapter.storage)
        val pending = f.directory.resolve(DirectShellCredentialStorage.PENDING)
        Files.delete(pending)
        Files.createSymbolicLink(pending, pending.fileName)
        val result = adapter.storage.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, result.publication)
        assertEquals(null, adapter.filesystem.evidence.publicationAttempted)
        assertTrue(Files.isSymbolicLink(pending))
        assertFalse(Files.exists(f.record))
        assertTrue(adapter.filesystem.resourcesDrained())
    }

    @Test fun boundedReaderSeesOnlyAbsentOrCompleteEncryptedRecord() {
        f.provision()
        val s = f.store()
        val candidate = f.begin(s)
        val expected = Files.readAllBytes(f.directory.resolve(DirectShellCredentialStorage.PENDING))
        val firstRead = CountDownLatch(1)
        val published = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val reader = Thread {
            try {
                repeat(256) { index ->
                    if (index == 128) check(published.await(10, TimeUnit.SECONDS))
                    try {
                        assertArrayEquals(expected, Files.readAllBytes(f.record))
                    } catch (_: NoSuchFileException) {
                        check(index < 128)
                    }
                    firstRead.countDown()
                }
            } catch (problem: Throwable) {
                failure.set(problem)
                firstRead.countDown()
            }
        }.apply { start() }
        try {
            assertTrue(firstRead.await(10, TimeUnit.SECONDS))
            val receipt = s.publish(candidate, DirectShellPairingCommitReservation.issue(candidate.prepared))
            assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        } finally {
            published.countDown()
            reader.join(10_000)
        }
        assertFalse(reader.isAlive)
        assertTrue("bounded_reader_failed", failure.get() == null)
        assertArrayEquals(expected, Files.readAllBytes(f.record))
        f.emit("encrypted_reader_observations=256 partial=0 worker_joined=true")
    }

    @Test fun boundedRestartCombinationsAreReadOnlyAndNeverAdopted() {
        f.provision()
        val s = f.store()
        f.begin(s)
        val anchor = f.directory.resolve(DirectShellCredentialStorage.ANCHOR)
        val pending = f.directory.resolve(DirectShellCredentialStorage.PENDING)
        val recoveryBytes = Files.readAllBytes(anchor)
        val candidateBytes = Files.readAllBytes(pending)
        assertTrue(s.close())
        val cases = listOf(
            setOf(anchor) to DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION,
            setOf(anchor, pending) to DirectShellCredentialStorage.Status.PREPARED_RECONCILIATION,
            setOf(f.record) to DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION,
            setOf(f.record, anchor) to DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING,
            setOf(f.record, pending) to DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING,
            setOf(f.record, pending, anchor) to DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING,
            emptySet<java.nio.file.Path>() to DirectShellCredentialStorage.Status.EMPTY,
        )
        cases.forEach { (entries, expected) ->
            // Explicit synthetic restart preparation, never production recovery or adoption.
            listOf(anchor, pending, f.record).forEach(Files::deleteIfExists)
            entries.forEach { Files.write(it, if (it == anchor) recoveryBytes else candidateBytes) }
            val before = entries.associateWith(Files::readAllBytes)
            val reader = f.store()
            assertEquals(expected, reader.inspect().status)
            assertTrue(reader.close())
            before.forEach { (entry, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(entry)) }
        }
        Files.write(f.record, byteArrayOf(3))
        assertEquals(DirectShellCredentialStorage.Status.CORRUPT, f.store().inspect().status)
        assertArrayEquals(byteArrayOf(3), Files.readAllBytes(f.record))
        f.emit("restart_combinations=8 read_only=1 adoption=0 consent=0")
    }
}
