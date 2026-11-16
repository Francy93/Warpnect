package io.warpnect.platform.privileged.directshell.pairing

import java.io.BufferedReader
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real independent JVMs and files; directory synchronization is explicitly modeled, not power-loss qualified. */
class DirectShellStorageProcessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base get() = temporary.root.toPath()
    private val dir get() = base.resolve(DirectShellCredentialStorage.DIRECTORY)
    private val ticket = DirectShellPairingTicket("process-owner", "process-attempt", 1, 1, 0)
    private var keyReads = 0
    private var activeChild: Process? = null
    private var childOutput: BufferedReader? = null
    private fun store(fs: DirectShellCredentialFileSystem = ModeledDirectorySync()) =
        DirectShellCredentialStorage(base, {
            keyReads++
            StorageFixture.key
        }, fs)

    @Test
    fun `OS lease excludes a separate JVM writer without waiting`() {
        val s = store()
        try {
            assertEquals(DirectShellCredentialStorage.Status.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
            child("hold") { process ->
                assertEquals("BUSY", line(process))
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            }
        } finally {
            assertTrue(s.close())
        }
    }

    @Test
    fun `external lease owner blocks inspection and new enrollment until physical release`() {
        val s = store()
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
        assertTrue(s.close())
        val lock = dir.resolve(DirectShellCredentialStorage.LOCK)
        val identity = DirectShellCredentialFileSystem.attributes(lock)!!.fileKey()
        child("hold") { process ->
            assertEquals("HELD", line(process))
            val competitor = store()
            try {
                assertEquals(
                    DirectShellCredentialStorage.Status.BUSY,
                    competitor.begin(ticket, StorageFixture.identity),
                )
                assertEquals(DirectShellCredentialStorage.Status.BUSY, store().inspect().status)
            } finally {
                assertTrue(competitor.close())
            }
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        }
        assertEquals(identity, DirectShellCredentialFileSystem.attributes(lock)!!.fileKey())
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION, store().inspect().status)
    }

    @Test
    fun `process death after atomic directory publication leaves whole encrypted record and no consent`() {
        val fixture = preparedFixture()
        var childPid = -1L
        child("publish") { process ->
            childPid = processId(process)
            assertEquals("PUBLISHED_BEFORE_RECEIPT", line(process))
            assertTrue(process.isAlive)
            assertRetained(fixture, published = true)
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        }
        // A crash does not promise immediate OS lock availability. Never retry this inspection.
        val result = inspectPublished(fixture, expected = null)
        recordOutcome("A_FORCED_DEATH", childPid, result)
    }

    @Test
    fun `explicit child lock and channel release requires strict committed cleanup recovery`() {
        val fixture = preparedFixture()
        var childPid = -1L
        child("publish-release") { process ->
            childPid = processId(process)
            assertEquals("PUBLISHED_BEFORE_RECEIPT", line(process))
            assertTrue(process.isAlive)
            assertRetained(fixture, published = true)
            process.outputStream.write('R'.code)
            process.outputStream.flush()
            assertEquals("LEASE_RELEASED", line(process))
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
            assertEquals(0, process.exitValue())
        }
        val result = inspectPublished(fixture, DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING)
        recordOutcome("B_ACKNOWLEDGED_RELEASE", childPid, result)
    }

    @Test
    fun `live published owner proves busy inspection has no authentication or persistent mutation`() {
        val fixture = preparedFixture()
        child("publish") { process ->
            assertEquals("PUBLISHED_BEFORE_RECEIPT", line(process))
            assertTrue(process.isAlive)
            val result = inspectPublished(fixture, DirectShellCredentialStorage.Status.BUSY)
            assertTrue(process.isAlive)
            recordOutcome("G6_LIVE_PUBLISHED_OWNER", processId(process), result)
        }
        assertRetained(fixture, published = true)
        fixture.parent.assertReleased()
    }

    @Test
    fun `real same JVM channel overlap is refused without registry ownership`() {
        val fixture = preparedFixture()
        val lockPath = dir.resolve(DirectShellCredentialStorage.LOCK).toRealPath()
        FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            val lock = requireNotNull(channel.tryLock())
            lock.use {
                assertTrue(lock.isValid)
                assertNull(registryOwner(lockPath))
                val attempts = fixture.fs.attempts.size
                val mutations = fixture.fs.mutations
                val reads = keyReads
                val inspector = store(fixture.fs)
                try {
                    val inspected = inspector.inspect()
                    assertEquals(DirectShellCredentialStorage.Status.BUSY, inspected.status)
                    assertTrue(inspected.reconciliationRequired)
                    assertEquals(attempts + 1, fixture.fs.attempts.size)
                    assertFalse(fixture.fs.attempts.last())
                    assertNull(fixture.fs.lastLease)
                    assertEquals(reads, keyReads)
                    assertEquals(mutations, fixture.fs.mutations)
                    assertTrue(lock.isValid)
                    assertNull(registryOwner(lockPath))
                } finally {
                    assertTrue(inspector.close())
                }
            }
            assertFalse(lock.isValid)
        }
        assertRetained(fixture, published = false)
        fixture.parent.assertReleased()
        assertFalse(fixture.authorization.isResolved)
        assertEquals(0, fixture.resolutions.get())
    }

    private fun preparedFixture(): Prepared {
        val fs = AuditedFiles()
        val resolutions = AtomicInteger()
        val authorization = DirectShellStagedAuthorization { resolutions.incrementAndGet() }
        val owner = store(fs)
        val prepared = try {
            assertEquals(
                DirectShellCredentialStorage.Status.RECOVERY_READY,
                owner.begin(ticket, StorageFixture.identity),
            )
            assertEquals(
                DirectShellCredentialStorage.Status.PREPARED,
                owner.prepare(StorageFixture.evidence(ticket), authorization).status,
            )
            val parent = requireNotNull(fs.lastLease)
            assertTrue(parent.lock.isValid)
            assertTrue(parent.channel.isOpen)
            assertSame(parent.lease, registryOwner(parent.path))
            assertFalse(Files.exists(dir.resolve(DirectShellCredentialStorage.RECORD)))
            Prepared(
                fs,
                parent,
                Files.readAllBytes(dir.resolve(DirectShellCredentialStorage.ANCHOR)),
                Files.readAllBytes(dir.resolve(DirectShellCredentialStorage.PENDING)),
                authorization,
                resolutions,
            )
        } finally {
            assertTrue(owner.close())
        }
        prepared.parent.assertReleased()
        return prepared
    }

    private fun inspectPublished(
        fixture: Prepared,
        expected: DirectShellCredentialStorage.Status?,
    ): DirectShellCredentialStorage.Inspection {
        assertRetained(fixture, published = true)
        fixture.parent.assertReleased()
        val attempts = fixture.fs.attempts.size
        val mutations = fixture.fs.mutations
        val reads = keyReads
        val fileReads = fixture.fs.reads
        val inspector = store(fixture.fs)
        try {
            val inspected = inspector.inspect()
            assertEquals(attempts + 1, fixture.fs.attempts.size)
            assertFalse(fixture.fs.attempts.last())
            when (inspected.status) {
                DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING -> {
                    val acquired = requireNotNull(fixture.fs.lastLease)
                    assertNotSame(fixture.parent.lease, acquired.lease)
                    assertTrue(keyReads > reads)
                    assertTrue(fixture.fs.reads > fileReads)
                    acquired.assertReleased()
                }
                DirectShellCredentialStorage.Status.BUSY -> {
                    assertNull(fixture.fs.lastLease)
                    assertEquals(reads, keyReads)
                    assertEquals(fileReads, fixture.fs.reads)
                    assertNull(registryOwner(fixture.parent.path))
                }
                else -> fail("unexpected_inspection_status:${inspected.status}")
            }
            if (expected != null) assertEquals(expected, inspected.status)
            assertTrue(inspected.reconciliationRequired)
            assertFalse(fixture.authorization.isResolved)
            assertEquals(0, fixture.resolutions.get())
            assertEquals(mutations, fixture.fs.mutations)
            assertRetained(fixture, published = true)
            fixture.parent.assertReleased()
            return inspected
        } finally {
            // Resource close is not credential deletion, adoption or another acquisition.
            assertTrue(inspector.close())
            assertEquals(mutations, fixture.fs.mutations)
            assertRetained(fixture, published = true)
            fixture.parent.assertReleased()
        }
    }

    private fun assertRetained(fixture: Prepared, published: Boolean) {
        val names = setOf(
            DirectShellCredentialStorage.LOCK,
            DirectShellCredentialStorage.ANCHOR,
            DirectShellCredentialStorage.PENDING,
        ) + if (published) setOf(DirectShellCredentialStorage.RECORD) else emptySet()
        val actual = Files.newDirectoryStream(dir).use { entries ->
            entries.iterator().asSequence().take(5).map { it.fileName.toString() }.toSet()
        }
        assertEquals(names, actual)
        names.forEach { assertTrue(Files.isRegularFile(dir.resolve(it), LinkOption.NOFOLLOW_LINKS)) }
        assertArrayEquals(fixture.anchor, Files.readAllBytes(dir.resolve(DirectShellCredentialStorage.ANCHOR)))
        assertArrayEquals(fixture.pending, Files.readAllBytes(dir.resolve(DirectShellCredentialStorage.PENDING)))
        if (published) {
            assertArrayEquals(fixture.pending, Files.readAllBytes(dir.resolve(DirectShellCredentialStorage.RECORD)))
            assertTrue(
                Files.isSameFile(
                    dir.resolve(DirectShellCredentialStorage.RECORD),
                    dir.resolve(DirectShellCredentialStorage.PENDING),
                ),
            )
        }
    }

    private class AuditedFiles : ModeledDirectorySync() {
        val attempts = mutableListOf<Boolean>()
        var lastLease: ParentLease? = null
        var mutations = 0
        var reads = 0
        override fun acquireLease(directory: Path, create: Boolean): DirectShellCredentialLease? {
            attempts += create
            val lease = super.acquireLease(directory, create)
            lastLease = lease?.let(::ParentLease)
            return lease
        }
        override fun prepareNamespace(base: Path): Path {
            mutations++
            return super.prepareNamespace(base)
        }
        override fun syncDirectory(path: Path) {
            mutations++
            super.syncDirectory(path)
        }
        override fun writeNew(path: Path, bytes: ByteArray) {
            mutations++
            super.writeNew(path, bytes)
        }
        override fun linkNoReplace(record: Path, prepared: Path) {
            mutations++
            super.linkNoReplace(record, prepared)
        }
        override fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult {
            mutations++
            return super.publishNoReplace(record, prepared)
        }
        override fun removeRedundant(path: Path) {
            mutations++
            super.removeRedundant(path)
        }
        override fun read(path: Path): ByteArray {
            reads++
            return super.read(path)
        }
    }

    private class ParentLease(val lease: DirectShellCredentialLease) {
        val path = leaseField(lease, "path") as Path
        val lock = leaseField(lease, "lock") as FileLock
        val channel = leaseField(lease, "channel") as FileChannel
        fun assertReleased() {
            assertFalse(lock.isValid)
            assertFalse(channel.isOpen)
            assertNull(registryOwner(path))
        }
    }

    private class Prepared(
        val fs: AuditedFiles,
        val parent: ParentLease,
        val anchor: ByteArray,
        val pending: ByteArray,
        val authorization: DirectShellStagedAuthorization,
        val resolutions: AtomicInteger,
    )

    private fun recordOutcome(scenario: String, childPid: Long, result: DirectShellCredentialStorage.Inspection) {
        // Host-only metadata via reflection: these JVM APIs are absent from the Android compile stubs.
        val runtime = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getRuntimeMXBean").invoke(null)
        val arguments = Class.forName("java.lang.management.RuntimeMXBean")
            .getMethod("getInputArguments").invoke(runtime) as List<*>
        val agents = arguments.count { (it as String).startsWith("-javaagent:") }
        val handleType = Class.forName("java.lang.ProcessHandle")
        val parentPid = handleType.getMethod("pid").invoke(handleType.getMethod("current").invoke(null))
        println(
            "LEASE_CONTRACT scenario=$scenario status=${result.status} " +
                "reconciliation=${result.reconciliationRequired} " +
                "parentPid=$parentPid childPid=$childPid javaAgents=$agents " +
                "historical=P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED",
        )
    }

    private fun processId(process: Process): Long = Process::class.java.getMethod("pid").invoke(process) as Long

    private fun child(mode: String, block: (Process) -> Unit) {
        check(activeChild == null)
        val java = Path.of(requireNotNull(System.getProperty("java.home")), "bin", "java").toString()
        val classes = Path.of(
            requireNotNull(DirectShellStorageProcess::class.java.protectionDomain).codeSource.location.toURI(),
        )
        val process = ProcessBuilder(
            java,
            "-cp",
            classes.toString(),
            DirectShellStorageProcess::class.java.name,
            mode,
            dir.toString(),
        )
            .redirectErrorStream(true).start()
        activeChild = process
        childOutput = process.inputStream.bufferedReader()
        try {
            block(process)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS))
            check(!process.isAlive)
            childOutput!!.close()
            process.outputStream.close()
            process.errorStream.close()
            childOutput = null
            activeChild = null
        }
    }

    private fun line(process: Process): String? {
        check(activeChild === process)
        val output = requireNotNull(childOutput)
        val task = FutureTask { output.readLine() }
        val reader = Thread(task, "test-owned-child-handshake")
        reader.start()
        return try {
            task.get(10, TimeUnit.SECONDS)
        } finally {
            if (!task.isDone) process.destroyForcibly()
            reader.join(1000)
            assertFalse(reader.isAlive)
        }
    }

    companion object {
        // Test-only reads of the owned descriptor and one registry key; never releases or mutates them.
        private fun leaseField(lease: DirectShellCredentialLease, name: String): Any =
            DirectShellCredentialLease::class.java.getDeclaredField(name).run {
                isAccessible = true
                requireNotNull(get(lease))
            }

        private fun registryOwner(path: Path): Any? =
            DirectShellCredentialLease::class.java.getDeclaredField("owners").run {
                isAccessible = true
                (get(null) as Map<*, *>)[path]
            }
    }
}
