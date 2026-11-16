package io.warpnect.platform.privileged.directshell.pairing

import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real host files/locks; Android UID/mode/open/fsync semantics below are explicitly modeled. */
class AndroidDirectShellCredentialFileSystemTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base get() = temporary.root.toPath().toRealPath()
    private val os = ModeledAndroidCredentialOs()
    private val fs by lazy { AndroidDirectShellCredentialFileSystem(base, os) }
    private val dir get() = base.resolve(DirectShellCredentialStorage.DIRECTORY)
    private val anchor get() = dir.resolve(DirectShellCredentialStorage.ANCHOR)
    private val pending get() = dir.resolve(DirectShellCredentialStorage.PENDING)
    private val record get() = dir.resolve(DirectShellCredentialStorage.RECORD)
    private val stores = mutableListOf<DirectShellCredentialStorage>()
    private val ticket = DirectShellPairingTicket("android-model", "attempt", 1, 1, 0)
    private fun store() = DirectShellCredentialStorage(base, { StorageFixture.key }, fs).also(stores::add)

    @After
    fun closeOwnedFixtures() {
        stores.forEach { assertTrue(it.close()) }
        assertEquals(0, os.handles.size)
    }

    @Test
    fun `construction and evidence snapshots are passive`() {
        repeat(5) { assertEquals(DirectShellStorageObservation.NOT_CHECKED, fs.evidence.directorySync) }
        assertEquals(0, os.calls)
        assertTrue(fs.resourcesDrained())
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `API26 fails closed before using the public API27 close on exec flag`() {
        val failure = assertThrows(DirectShellCredentialPlatformFailure::class.java) {
            AndroidCredentialOs { 26 }.open(base, DirectShellCredentialAccess.DIRECTORY)
        }
        assertEquals(DirectShellCredentialPlatformProblem.UNSUPPORTED, failure.problem)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `only exact normalized base and fixed artifact paths are accepted`() {
        fs.prepareNamespace(base)
        listOf(base.resolve("outside"), dir.resolve("other"), dir.resolve("../identity.pending")).forEach { path ->
            assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.writeNew(path, byteArrayOf(1)) }
        }
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.removeRedundant(record) }
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(base.parent) }
    }

    @Test
    fun `canonical base cannot be substituted by another owned directory`() {
        val other = temporary.newFolder("other").toPath()
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.canonicalBase(other) }
    }

    @Test
    fun `foreign root and public namespace modes are rejected`() {
        os.foreign = base
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.prepareNamespace(base) }
        os.foreign = null
        Files.createDirectory(dir)
        os.modeOverride = 511
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.validateNamespace(dir) }
    }

    @Test
    fun `file in namespace position is never followed or replaced`() {
        Files.write(dir, byteArrayOf(3))
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.prepareNamespace(base) }
        assertArrayEquals(byteArrayOf(3), Files.readAllBytes(dir))
    }

    @Test
    fun `modeled directory sync opens synchronizes and closes in order`() {
        fs.prepareNamespace(base)
        os.events.clear()
        fs.syncDirectory(base)
        assertEquals(listOf("open:DIRECTORY", "sync", "close"), os.events)
        assertEquals(DirectShellStorageObservation.OBSERVED, fs.evidence.directorySync)
        assertTrue(fs.resourcesDrained())
    }

    @Test
    fun `unsupported directory sync never opens recovery gate`() {
        os.failSync = DirectShellCredentialPlatformProblem.UNSUPPORTED
        val s = store()
        assertEquals(DirectShellCredentialStorage.Status.UNSUPPORTED, s.begin(ticket, StorageFixture.identity))
        assertFalse(Files.exists(anchor))
        assertEquals(DirectShellStorageObservation.UNSUPPORTED, fs.evidence.parentDirectorySync)
        assertTrue(fs.resourcesDrained())
    }

    @Test
    fun `unknown directory failure stays unknown rather than unsupported`() {
        os.failSync = DirectShellCredentialPlatformProblem.UNKNOWN
        val s = store()
        assertEquals(DirectShellCredentialStorage.Status.IO_UNCERTAIN, s.begin(ticket, StorageFixture.identity))
        assertEquals(DirectShellStorageObservation.UNKNOWN, fs.evidence.parentDirectorySync)
    }

    @Test
    fun `file data sync and directory sync are separate observations`() {
        fs.prepareNamespace(base)
        fs.writeNew(anchor, byteArrayOf(1, 2, 3))
        assertEquals(DirectShellStorageObservation.OBSERVED, fs.evidence.dataSync)
        assertEquals(DirectShellStorageObservation.NOT_CHECKED, fs.evidence.directorySync)
        assertArrayEquals(byteArrayOf(1, 2, 3), fs.read(anchor))
        fs.syncDirectory(dir)
        assertEquals(DirectShellStorageObservation.OBSERVED, fs.evidence.directorySync)
    }

    @Test
    fun `new no backup entry is synchronized before namespace creation`() {
        fs.prepareNamespace(base)
        assertEquals(listOf("open:DIRECTORY", "sync", "close"), os.events)
        assertEquals(DirectShellStorageObservation.OBSERVED, fs.evidence.parentDirectorySync)
        assertEquals(DirectShellStorageObservation.NOT_CHECKED, fs.evidence.dataSync)
        assertEquals(DirectShellStorageObservation.NOT_CHECKED, fs.evidence.directorySync)
        assertTrue(Files.isDirectory(dir))
    }

    @Test
    fun `bounded partial writes complete with no retry on zero progress`() {
        fs.prepareNamespace(base)
        os.chunk = 2
        val input = ByteArray(11) { it.toByte() }
        fs.writeNew(anchor, input)
        assertArrayEquals(input, fs.read(anchor))
        os.zeroWrite = true
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.writeNew(pending, input) }
        assertTrue(fs.resourcesDrained())
        assertEquals(0L, Files.size(pending))
    }

    @Test
    fun `read and write enforce record bounds`() {
        fs.prepareNamespace(base)
        assertThrows(IllegalArgumentException::class.java) { fs.writeNew(anchor, ByteArray(65_537)) }
        Files.write(anchor, ByteArray(65_537))
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.read(anchor) }
    }

    @Test
    fun `foreign record UID blocks reads without consuming payload`() {
        fs.prepareNamespace(base)
        Files.write(anchor, byteArrayOf(1))
        os.foreign = anchor
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.read(anchor) }
        assertEquals(0, os.handles.size)
    }

    @Test
    fun `open descriptor identity must match directory entry`() {
        fs.prepareNamespace(base)
        os.closes = 0
        os.wrongOpenedInode = true
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(dir) }
        assertTrue(fs.resourcesDrained())
        assertEquals(1, os.closes)
    }

    @Test
    fun `ambiguous close is never retried or reported as drain`() {
        fs.prepareNamespace(base)
        os.closes = 0
        os.failClose = true
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(dir) }
        assertFalse(fs.resourcesDrained())
        assertTrue(fs.evidence.cleanupIncomplete)
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(dir) }
        assertEquals(1, os.closes)
        assertEquals(0, os.handles.size)
    }

    @Test
    fun `modeled Android publication consumes source and preserves existing record`() {
        fs.prepareNamespace(base)
        fs.writeNew(pending, byteArrayOf(4, 5))
        assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, fs.publishNoReplace(record, pending))
        assertFalse(Files.exists(pending))
        fs.writeNew(pending, byteArrayOf(6))
        assertEquals(DirectShellCredentialPublishResult.COLLISION, fs.publishNoReplace(record, pending))
        assertArrayEquals(byteArrayOf(6), fs.read(pending))
        assertArrayEquals(byteArrayOf(4, 5), fs.read(record))
        assertEquals(DirectShellStorageObservation.OBSERVED, fs.evidence.noReplace)
    }

    @Test
    fun `known publication survives directory close ambiguity without retry`() {
        fs.prepareNamespace(base)
        fs.writeNew(pending, byteArrayOf(4))
        os.afterPublish = { os.failClose = true }
        val closes = os.closes
        assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, fs.publishNoReplace(record, pending))
        assertFalse(fs.resourcesDrained())
        assertTrue(fs.evidence.cleanupIncomplete)
        assertEquals(DirectShellCredentialSource.CONSUMED, fs.evidence.publicationSource)
        assertEquals(closes + 1, os.closes)
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(dir) }
        assertEquals(closes + 1, os.closes)
    }

    @Test
    fun `undrained native duplicate fences further IO even after original directory closed`() {
        fs.prepareNamespace(base)
        fs.writeNew(pending, byteArrayOf(4))
        os.afterPublish = { os.nativeDrained = false }
        assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, fs.publishNoReplace(record, pending))
        assertFalse(fs.resourcesDrained())
        assertThrows(DirectShellCredentialPlatformFailure::class.java) { fs.syncDirectory(dir) }
        assertEquals(DirectShellCredentialPublishResult.NOT_ATTEMPTED, fs.publishNoReplace(record, pending))
        os.nativeDrained = true // Explicit modeled duplicate drain; no actual ambiguous-close retry.
        assertTrue(fs.resourcesDrained())
    }

    @Test
    fun `stable host lease excludes second adapter without Android claim`() {
        fs.prepareNamespace(base)
        val lease = fs.acquireLease(dir, true)!!
        try {
            val second = AndroidDirectShellCredentialFileSystem(base, os)
            assertEquals(null, second.acquireLease(dir, true))
            assertTrue(second.evidence.busy)
            assertEquals(DirectShellStorageObservation.NOT_CHECKED, second.evidence.exclusion)
        } finally {
            assertTrue(lease.close())
        }
    }

    @Test
    fun `encrypted restart classification uses adapter without restoring consent`() {
        val s = store()
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
        assertTrue(s.close())
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION, store().inspect().status)
    }

    @Test
    fun `foreign encrypted record receives explicit blocking classification`() {
        val s = store()
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
        assertTrue(s.close())
        os.foreign = anchor
        assertEquals(DirectShellCredentialStorage.Status.FOREIGN_RECORD, store().inspect().status)
    }

    @Test
    fun `codec rejects and wipes oversized plaintext from a faulty AEAD port`() {
        val input = DirectShellCredentialCodec.Record(
            DirectShellCredentialCodec.Kind.RECOVERY,
            DirectShellCredentialCodec.binding(ticket),
            StorageFixture.identity,
            null,
        )
        val encrypted = DirectShellCredentialCodec.encode(input, StorageFixture.key)
        val oversized = ByteArray(65_536) { 9 }
        val faulty = object : DirectShellCredentialAead {
            override fun seal(plaintext: ByteArray, aad: ByteArray): DirectShellCredentialAead.Sealed = error("unused")
            override fun open(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray) = oversized
        }
        assertThrows(IllegalArgumentException::class.java) { DirectShellCredentialCodec.decode(encrypted, faulty) }
        assertTrue(oversized.all { it == 0.toByte() })
    }

    @Test
    fun `storage does not release lease until IO drain is proven`() {
        var drained = true
        val port = object : ModeledDirectorySync() {
            override fun resourcesDrained() = drained
        }
        val s = DirectShellCredentialStorage(base, { StorageFixture.key }, port).also(stores::add)
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_READY, s.begin(ticket, StorageFixture.identity))
        drained = false
        assertFalse(s.close())
        drained = true // Explicit modeled physical release, not a production ambiguous-close retry.
        assertTrue(s.close())
    }
}

/** Host-only fake UID/mode/directory fsync; real temp record IO and file-content force. */
internal class ModeledAndroidCredentialOs : DirectShellCredentialOs {
    data class Handle(val path: Path, val channel: FileChannel?)
    val handles = mutableMapOf<FileDescriptor, Handle>()
    val events = mutableListOf<String>()
    private val inodes = mutableMapOf<Path, Long>()
    var calls = 0
    var closes = 0
    var foreign: Path? = null
    var modeOverride: Int? = null
    var failSync: DirectShellCredentialPlatformProblem? = null
    var failClose = false
    var wrongOpenedInode = false
    var chunk = Int.MAX_VALUE
    var zeroWrite = false
    var afterPublish: () -> Unit = {}
    var nativeDrained = true
    override fun resourcesDrained(): Boolean = nativeDrained

    override fun lockAttributes(): Array<FileAttribute<*>> = emptyArray() // Windows has no POSIX attrs; modeled only.
    override fun node(path: Path): DirectShellCredentialNode? {
        calls++
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        return DirectShellCredentialNode(
            path != foreign,
            attrs.isDirectory,
            attrs.isRegularFile,
            modeOverride ?: if (attrs.isDirectory) 448 else 384,
            attrs.size(),
            1,
            inodes.getOrPut(path) { inodes.size.toLong() + 1 },
        )
    }
    override fun node(fd: FileDescriptor): DirectShellCredentialNode = node(handles.getValue(fd).path)!!.let {
        if (wrongOpenedInode) it.copy(inode = it.inode + 100) else it
    }
    override fun mkdir(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
    }
    override fun open(path: Path, access: DirectShellCredentialAccess): FileDescriptor {
        events += "open:$access"
        val channel = when (access) {
            DirectShellCredentialAccess.DIRECTORY -> null
            DirectShellCredentialAccess.READ -> FileChannel.open(path, StandardOpenOption.READ)
            DirectShellCredentialAccess.CREATE_NEW -> FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }
        return FileDescriptor().also { handles[it] = Handle(path, channel) }
    }
    override fun sync(fd: FileDescriptor) {
        events += "sync"
        failSync?.let { throw DirectShellCredentialPlatformFailure(it) }
        handles.getValue(fd).channel?.force(true)
    }
    override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
        handles.getValue(fd).channel!!.read(ByteBuffer.wrap(bytes, offset, count)).coerceAtLeast(0)
    override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
        if (zeroWrite) 0 else handles.getValue(fd).channel!!.write(ByteBuffer.wrap(bytes, offset, minOf(chunk, count)))
    override fun close(fd: FileDescriptor) {
        events += "close"
        closes++
        handles.remove(fd)!!.channel?.close()
        if (failClose) throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNKNOWN)
    }
    override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
        val root = handles.getValue(directory).path
        val source = root.resolve(DirectShellCredentialStorage.PENDING)
        val destination = root.resolve(DirectShellCredentialStorage.RECORD)
        // Host-only no-replace link + modeled source consumption, NOT an Android rename proof.
        return try {
            Files.createLink(destination, source)
            inodes[destination] = node(source)!!.inode
            Files.delete(source)
            afterPublish()
            DirectShellCredentialPublishResult.PUBLISHED_CONSUMED
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            DirectShellCredentialPublishResult.COLLISION
        }
    }
    override fun unlink(path: Path) {
        Files.delete(path)
    }
}
