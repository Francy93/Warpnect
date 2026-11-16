package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellEnrollmentIntentPrototype.Generation
import io.warpnect.platform.privileged.directshell.pairing.DirectShellEnrollmentIntentPrototype.Lookup
import io.warpnect.platform.privileged.directshell.pairing.DirectShellEnrollmentIntentPrototype.Stage
import io.warpnect.platform.privileged.directshell.pairing.DirectShellEnrollmentIntentPrototype.State
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectShellEnrollmentIntentPrototypeTest {
    @get:Rule val temp = TemporaryFolder()
    private val base get() = temp.root.toPath()
    private val dir get() = base.resolve(DirectShellEnrollmentIntentPrototype.DIRECTORY)
    private val intent get() = dir.resolve(DirectShellEnrollmentIntentPrototype.INTENT)
    private val anchor get() = dir.resolve(DirectShellEnrollmentIntentPrototype.ANCHOR)
    private val keys = Keys()
    private val ticket = DirectShellPairingTicket("experimental", "ticket", 1, 2, 0)
    private val alias = DirectShellEnrollmentIntentPrototype.ALIAS_PREFIX + "ab".repeat(32)
    private var allowed = true
    private var now = 0L
    private val work = DirectShellPairingWorkContext(
        ticket,
        60_000,
        { now },
        { _, _ -> DirectShellPairingTimer {} },
    ) { allowed }

    private fun open(
        io: DirectShellCredentialFileSystem = ModeledDirectorySync(),
        root: Path = base,
        checkpoint: (Stage) -> Unit = {},
    ) = DirectShellEnrollmentIntentPrototype(root, io, keys, { ByteArray(32) { 0xab.toByte() } }, checkpoint)

    private fun run(owner: DirectShellEnrollmentIntentPrototype = open()): State = try {
        owner.attempt(ticket, work) { StorageFixture.identity }
    } finally {
        assertTrue(owner.drain())
    }

    @Test fun authenticatedAnchorNeverAdoptsOrUsesTheLegacyAlias() {
        val legacyAlias = AndroidDirectShellCredentialKeyStore.PRODUCTION_ALIAS
        keys.handles[legacyAlias] = key()
        val legacy = keys.handles.getValue(legacyAlias)
        val adopted = open()
        try {
            assertEquals(State.AUTHENTICATED_ANCHOR, adopted.attempt(ticket, work) { StorageFixture.identity })
            assertEquals(State.STALE, adopted.attempt(ticket, work) { error("duplicate") })
            assertEquals(State.BUSY, open().inspectReadOnly())
        } finally {
            assertTrue(adopted.drain())
        }
        assertEquals(State.AUTHENTICATED_ANCHOR, open().inspectReadOnly())
        assertEquals(State.RECONCILIATION, run())
        assertEquals(listOf(alias), keys.generated)
        assertSame(legacy, keys.handles[legacyAlias])
        assertFalse(Files.exists(base.resolve(DirectShellCredentialStorage.DIRECTORY)))
    }

    @Test fun alreadyPresentAndRacingCollisionNeverClobber() {
        val previous = key()
        keys.handles[alias] = previous
        assertEquals(State.ALIAS_CONFLICT, run())
        assertTrue(keys.generated.isEmpty())
        assertSame(previous, keys.handles[alias])
        val racing = temp.newFolder().toPath()
        keys.handles.remove(alias)
        keys.atGenerate = { keys.handles[alias] = previous }
        assertEquals(State.ALIAS_CONFLICT, run(open(root = racing)))
        assertSame(previous, keys.handles[alias])
        assertEquals(listOf(alias), keys.generated)
        assertEquals(State.RECONCILIATION, run(open(root = racing)))
    }

    @Test fun failedLookupIsNotAbsence() {
        keys.atLookup = { error("injected_lookup_failure") }
        assertEquals(State.PROVIDER_UNCERTAIN, run())
        assertTrue(Files.exists(intent))
        assertTrue(keys.generated.isEmpty())
        assertEquals(State.UNAUTHENTICATED_INTENT, open().inspectReadOnly())
    }

    @Test fun generatorFailureAndLostAcknowledgementDoNotRetry() {
        keys.atGenerate = { error("injected_generator_failure") }
        assertEquals(State.PROVIDER_UNCERTAIN, run())
        assertEquals(State.RECONCILIATION, run())
        assertTrue(keys.handles.isEmpty())
        val lost = temp.newFolder().toPath()
        keys.atGenerate = {}
        keys.afterGenerate = { error("injected_lost_ack") }
        assertEquals(State.PROVIDER_UNCERTAIN, run(open(root = lost)))
        assertEquals(State.RECONCILIATION, run(open(root = lost)))
        assertEquals(2, keys.generated.size)
        assertTrue(keys.handles.containsKey(alias))
        assertEquals(State.UNAUTHENTICATED_INTENT, open(root = lost).inspectReadOnly())
    }

    @Test fun abruptExitAtEachDurableBoundaryUsesRestartInspectionOnly() {
        for ((stage, observed) in mapOf(
            Stage.BEFORE_INTENT to State.EMPTY,
            Stage.INTENT_SYNCED to State.UNAUTHENTICATED_INTENT,
            Stage.KEY_ACKNOWLEDGED to State.UNAUTHENTICATED_INTENT,
            Stage.BEFORE_PUBLICATION to State.UNAUTHENTICATED_INTENT,
            Stage.PUBLISHED to State.AUTHENTICATED_ANCHOR,
        )) {
            val root = temp.newFolder().toPath()
            keys.handles.clear()
            val worker = open(root = root) { if (it == stage) throw SimulatedDeath() }
            try {
                run(worker)
                fail("checkpoint did not run: $stage")
            } catch (_: SimulatedDeath) {
                // run() releases the OS lock as process death would; no persistent effect is undone.
            }
            assertEquals(observed, open(root = root).inspectReadOnly())
            if (stage != Stage.BEFORE_INTENT) assertEquals(State.RECONCILIATION, run(open(root = root)))
        }
    }

    @Test fun deathInsideProviderRetainsPossibleAliasAndCannotReplayIntent() {
        keys.afterGenerate = { throw SimulatedDeath() }
        try {
            run()
            fail("provider checkpoint did not run")
        } catch (_: SimulatedDeath) {
            assertTrue(keys.handles.containsKey(alias))
        }
        assertEquals(State.UNAUTHENTICATED_INTENT, open().inspectReadOnly())
        assertEquals(State.RECONCILIATION, run())
        assertEquals(listOf(alias), keys.generated)
        assertFalse(Files.exists(anchor))
    }

    @Test fun tornOrUnknownIntentNeverReachesTheProvider() {
        val fs = object : ModeledDirectorySync() {
            override fun writeNew(path: Path, bytes: ByteArray) {
                super.writeNew(path, bytes.copyOf(5))
                throw SimulatedDeath()
            }
        }
        try {
            run(open(io = fs))
            fail("partial write did not occur")
        } catch (_: SimulatedDeath) {
            assertEquals(5L, Files.size(intent))
        }
        assertEquals(State.UNRECOGNIZED, open().inspectReadOnly())
        assertEquals(State.RECONCILIATION, run())
        assertTrue(keys.generated.isEmpty())
    }

    @Test fun alteredIntentBreaksTheAuthenticatedBindingEvenWithTheSameAlias() {
        assertEquals(State.AUTHENTICATED_ANCHOR, run())
        val original = Files.readAllBytes(anchor)
        val changed = Files.readAllBytes(intent)
        changed[12] = (changed[12].toInt() xor 1).toByte()
        Files.write(intent, changed)
        assertEquals(State.RECONCILIATION, open().inspectReadOnly())
        assertArrayEquals(original, Files.readAllBytes(anchor))
        assertEquals(State.RECONCILIATION, run())
        assertEquals(listOf(alias), keys.generated)
    }

    @Test fun wrongGenerationOrExpiredTicketFencesEachIrreversibleStage() {
        val wrong = DirectShellPairingTicket("experimental", "ticket", 1, 2, 0)
        val clone = open()
        try {
            assertEquals(State.STALE, clone.attempt(wrong, work) { error("foreign_work") })
        } finally {
            assertTrue(clone.drain())
        }
        assertEquals(State.STALE, run(open { if (it == Stage.BEFORE_INTENT) now = 60_000 }))
        assertFalse(Files.exists(intent))
        now = 0
        keys.atLookup = { allowed = false }
        assertEquals(State.RECONCILIATION, run())
        assertTrue(keys.generated.isEmpty())
        keys.atLookup = {}
        allowed = true
        val late = temp.newFolder().toPath()
        val held = open(root = late) { if (it == Stage.BEFORE_PUBLICATION) now = 60_000 }
        assertEquals(State.RECONCILIATION, run(held))
        assertEquals(State.UNAUTHENTICATED_INTENT, open(root = late).inspectReadOnly())
        assertEquals(listOf(alias), keys.generated)
    }

    @Test fun publicationCannotReplaceAnExistingAnchor() {
        val conflict = byteArrayOf(7, 4, 1)
        val conflicting = open {
            if (it == Stage.BEFORE_PUBLICATION) Files.write(anchor, conflict)
        }
        assertEquals(State.PUBLICATION_UNCERTAIN, run(conflicting))
        assertArrayEquals(conflict, Files.readAllBytes(anchor))
        assertEquals(State.RECONCILIATION, open().inspectReadOnly())
    }

    @Test fun independentJvmLeaseBlocksIntentAndGenerator() {
        Files.createDirectory(dir)
        val path = dir.resolve(DirectShellEnrollmentIntentPrototype.LOCK)
        requireNotNull(DirectShellCredentialLease.acquire(path, true)).also { assertTrue(it.close()) }
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val cp = Path.of(
            requireNotNull(DirectShellStorageProcess::class.java.protectionDomain).codeSource.location.toURI(),
        )
        val process = ProcessBuilder(
            java.toString(),
            "-cp",
            cp.toString(),
            DirectShellStorageProcess::class.java.name,
            "hold",
            dir.toString(),
        ).redirectErrorStream(true).start()
        val reader = process.inputStream.bufferedReader()
        val line = FutureTask { reader.readLine() }
        val observer = Thread(line).also { it.start() }
        try {
            assertEquals("HELD", line.get(10, TimeUnit.SECONDS))
            assertEquals(State.BUSY, run())
            assertFalse(Files.exists(intent))
            assertTrue(keys.generated.isEmpty())
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(State.AUTHENTICATED_ANCHOR, run())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            observer.join(1_000)
            assertFalse(observer.isAlive)
            reader.close()
            process.outputStream.close()
        }
    }

    private class SimulatedDeath : Error()

    private class Keys : DirectShellEnrollmentIntentPrototype.NoReplaceKeyProvider {
        val handles = mutableMapOf<String, DirectShellCredentialAead>()
        val generated = mutableListOf<String>()
        var atLookup: () -> Unit = {}
        var atGenerate: () -> Unit = {}
        var afterGenerate: () -> Unit = {}

        override fun lookup(alias: String): Lookup {
            atLookup()
            return if (handles.containsKey(alias)) Lookup.PRESENT else Lookup.ABSENT_CONFIRMED
        }

        override fun generateNoReplace(alias: String): Generation {
            generated.add(alias)
            atGenerate()
            if (handles.putIfAbsent(alias, key()) != null) return Generation.COLLISION
            afterGenerate()
            return Generation.CREATED
        }

        override fun verifiedExisting(alias: String) = handles[alias]
    }

    companion object {
        private fun key() = requireNotNull(DirectShellJcaCredentialAead.software(StorageFixture.key))
    }
}
