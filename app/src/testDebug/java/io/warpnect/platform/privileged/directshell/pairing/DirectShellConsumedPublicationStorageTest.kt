package io.warpnect.platform.privileged.directshell.pairing

import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real encrypted host files/lease; rename effect and directory sync explicitly modeled, never JNI. */
class DirectShellConsumedPublicationStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base get() = temporary.root.toPath()
    private val ticket = DirectShellPairingTicket("consumed-owner", "attempt", 1, 1, 0)
    private val io = ConsumedFs()
    private val stores = mutableListOf<DirectShellCredentialStorage>()
    private fun store() = DirectShellCredentialStorage(base, { StorageFixture.key }, io).also(stores::add)
    private fun path(name: String) = base.resolve(DirectShellCredentialStorage.DIRECTORY).resolve(name)
    private fun begin(): Pair<DirectShellCredentialStorage, DirectShellCredentialStorage.Candidate> {
        val s = store()
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
        val preparation = s.prepare(StorageFixture.evidence(ticket), DirectShellStagedAuthorization {})
        return s to requireNotNull(preparation.candidate)
    }

    @After fun drain() {
        stores.forEach { assertTrue(it.close()) }
    }

    @Test fun `known consumed source is not deleted twice and only recovery is cleaned`() {
        val (s, c) = begin()
        val bytes = Files.readAllBytes(path(DirectShellCredentialStorage.PENDING))
        val receipt = s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertTrue(receipt.cleanupComplete)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, receipt.durability)
        assertArrayEquals(bytes, Files.readAllBytes(path(DirectShellCredentialStorage.RECORD)))
        assertEquals(listOf(DirectShellCredentialStorage.ANCHOR), io.removed)
        assertFalse(Files.exists(path(DirectShellCredentialStorage.PENDING)))
    }

    @Test fun `collision protects old record and retains exact pending bytes`() {
        val (s, c) = begin()
        val pending = Files.readAllBytes(path(DirectShellCredentialStorage.PENDING))
        val old = byteArrayOf(4, 9)
        Files.write(path(DirectShellCredentialStorage.RECORD), old)
        val receipt = s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, receipt.publication)
        assertArrayEquals(old, Files.readAllBytes(path(DirectShellCredentialStorage.RECORD)))
        assertArrayEquals(pending, Files.readAllBytes(path(DirectShellCredentialStorage.PENDING)))
        assertTrue(io.removed.isEmpty())
    }

    @Test fun `duplicate execution returns identical receipt and calls primitive once`() {
        val (s, c) = begin()
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        val first = s.publish(c, r)
        assertTrue(first === s.publish(c, r))
        assertEquals(1, io.publications)
    }

    @Test fun `wrong prepared reservation cannot publish then valid reservation works once`() {
        val (s, c) = begin()
        val wrong = DirectShellPairingPreparedCommit(ticket, DirectShellStagedAuthorization {}, 1)
        assertEquals(
            DirectShellPairingPublication.NOT_PUBLISHED,
            s.publish(c, DirectShellPairingCommitReservation.issue(wrong)).publication,
        )
        assertEquals(0, io.publications)
        assertEquals(
            DirectShellPairingPublication.PUBLISHED,
            s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared)).publication,
        )
    }

    @Test fun `changed authenticated candidate prevents native boundary entry`() {
        val (s, c) = begin()
        Files.write(path(DirectShellCredentialStorage.PENDING), byteArrayOf(1))
        assertEquals(
            DirectShellPairingPublication.NOT_PUBLISHED,
            s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared)).publication,
        )
        assertEquals(0, io.publications)
    }

    @Test fun `post P sync failure preserves consumed source knowledge and recovery`() {
        val (s, c) = begin()
        io.afterPublication = { io.failSync = true }
        val receipt = s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, receipt.durability)
        assertFalse(receipt.cleanupComplete)
        assertFalse(Files.exists(path(DirectShellCredentialStorage.PENDING)))
        assertTrue(Files.exists(path(DirectShellCredentialStorage.ANCHOR)))
        assertTrue(io.removed.isEmpty())
    }

    @Test fun `recovery cleanup failure is independent from known P and S`() {
        val (s, c) = begin()
        io.failCleanup = true
        val receipt = s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, receipt.durability)
        assertFalse(receipt.cleanupComplete)
        assertTrue(Files.exists(path(DirectShellCredentialStorage.RECORD)))
    }

    @Test fun `unknown after publication does not manufacture earlier P by inspection`() {
        val (s, c) = begin()
        io.afterPublication = { throw java.io.IOException("synthetic") }
        val r = DirectShellPairingCommitReservation.issue(c.prepared)
        val receipt = s.publish(c, r)
        assertEquals(DirectShellPairingPublication.UNKNOWN, receipt.publication)
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING, store().inspect().status)
        assertEquals(1, io.publications)
        assertTrue(io.removed.isEmpty())
    }

    @Test fun `resource uncertainty blocks lease release after known publication`() {
        val (s, c) = begin()
        io.afterPublication = { io.drained = false }
        assertEquals(
            DirectShellPairingPublication.PUBLISHED,
            s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared)).publication,
        )
        assertFalse(s.close())
        assertEquals(DirectShellCredentialStorage.Status.BUSY, store().inspect().status)
        io.drained = true // Explicit modeled physical release, not a production timeout or retry.
        assertTrue(s.close())
    }

    @Test fun `restart record only remains reconciliation not enrollment success`() {
        val (s, c) = begin()
        s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION, store().inspect().status)
    }

    @Test fun `restart uncertain record with recovery and pending remains bounded and read only`() {
        val (s, c) = begin()
        val pending = Files.readAllBytes(path(DirectShellCredentialStorage.PENDING))
        io.afterPublication = { throw java.io.IOException("synthetic") }
        s.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        Files.write(path(DirectShellCredentialStorage.PENDING), pending)
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING, store().inspect().status)
        assertArrayEquals(pending, Files.readAllBytes(path(DirectShellCredentialStorage.PENDING)))
        assertTrue(io.removed.isEmpty())
    }

    private class ConsumedFs : ModeledDirectorySync() {
        var publications = 0
        var failSync = false
        var failCleanup = false
        var drained = true
        var afterPublication: () -> Unit = {}
        val removed = mutableListOf<String>()
        override fun resourcesDrained() = drained
        override fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult {
            publications++
            return try {
                super.linkNoReplace(record, prepared)
                Files.delete(prepared)
                afterPublication()
                DirectShellCredentialPublishResult.PUBLISHED_CONSUMED
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                DirectShellCredentialPublishResult.COLLISION
            } catch (_: Exception) {
                DirectShellCredentialPublishResult.UNKNOWN
            }
        }
        override fun syncDirectory(path: Path) {
            if (failSync) throw java.io.IOException("synthetic")
            super.syncDirectory(path)
        }
        override fun removeRedundant(path: Path) {
            removed += path.fileName.toString()
            if (failCleanup) throw java.io.IOException("synthetic")
            super.removeRedundant(path)
        }
    }
}
