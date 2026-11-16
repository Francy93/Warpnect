package io.warpnect.platform.privileged.directshell.pairing

import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class DirectShellStorageObservation { NOT_CHECKED, OBSERVED, UNSUPPORTED, UNKNOWN, REJECTED }
internal data class DirectShellAndroidStorageEvidence(
    val ownership: DirectShellStorageObservation = DirectShellStorageObservation.NOT_CHECKED,
    val dataSync: DirectShellStorageObservation = DirectShellStorageObservation.NOT_CHECKED,
    val directorySync: DirectShellStorageObservation = DirectShellStorageObservation.NOT_CHECKED,
    val parentDirectorySync: DirectShellStorageObservation = DirectShellStorageObservation.NOT_CHECKED,
    val noReplace: DirectShellStorageObservation = DirectShellStorageObservation.NOT_CHECKED,
    val exclusion: DirectShellStorageObservation = DirectShellStorageObservation.NOT_CHECKED,
    val busy: Boolean = false,
    val cleanupIncomplete: Boolean = false,
    val publicationAttempted: Boolean? = null,
    val publicationSource: DirectShellCredentialSource? = null,
    val publicationErrno: Int? = null,
)

internal enum class DirectShellCredentialAccess { DIRECTORY, READ, CREATE_NEW }
internal data class DirectShellCredentialNode(
    val owned: Boolean,
    val directory: Boolean,
    val regular: Boolean,
    val mode: Int,
    val size: Long,
    val device: Long,
    val inode: Long,
)

/** Host tests inject an explicitly modeled OS, not fake Android qualification. */
internal interface DirectShellCredentialOs {
    fun lockAttributes(): Array<FileAttribute<*>>
    fun node(path: Path): DirectShellCredentialNode?
    fun node(fd: FileDescriptor): DirectShellCredentialNode
    fun mkdir(path: Path)
    fun open(path: Path, access: DirectShellCredentialAccess): FileDescriptor
    fun sync(fd: FileDescriptor)
    fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int
    fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int
    fun close(fd: FileDescriptor)
    fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult
    fun resourcesDrained(): Boolean = true
    val publicationErrno: Int? get() = null
    fun unlink(path: Path)
}

/** Fixed app-private namespace; one tracked descriptor, no retry after ambiguous close. */
internal class AndroidDirectShellCredentialFileSystem(
    private val base: Path,
    private val os: DirectShellCredentialOs = AndroidCredentialOs(),
) : DirectShellCredentialFileSystem() {
    private val directory = base.resolve(DirectShellCredentialStorage.DIRECTORY)
    private val state = AtomicReference(DirectShellAndroidStorageEvidence())
    val evidence: DirectShellAndroidStorageEvidence get() = state.get()
    private val active = AtomicBoolean(false)
    private val descriptor = AtomicReference<FileDescriptor?>(null)

    override fun resourcesDrained(): Boolean = !active.get() && descriptor.get() == null && os.resourcesDrained()

    override fun canonicalBase(path: Path): Path {
        checkPath(path, setOf(base))
        requireDirectory(base, privateMode = false)
        return base
    }

    override fun prepareNamespace(base: Path): Path {
        canonicalBase(base)
        // Context may have just created no_backup. Persist its entry before opening the pre-PAKE gate.
        val parent = base.parent ?: fail(DirectShellCredentialPlatformProblem.UNKNOWN)
        requireDirectory(parent, privateMode = false)
        syncDirectoryAt(parent, parentEntry = true)
        os.mkdir(directory)
        validateNamespace(directory)
        return directory
    }

    override fun validateNamespace(directory: Path) {
        checkPath(directory, setOf(this.directory))
        requireDirectory(base, privateMode = false)
        requireDirectory(directory, privateMode = true)
    }

    override fun acquireLease(directory: Path, create: Boolean): DirectShellCredentialLease? {
        validateNamespace(directory)
        val lock = directory.resolve(DirectShellCredentialStorage.LOCK)
        os.node(lock)?.let { requireFile(it, lock = true) }
        val lease = DirectShellCredentialLease.acquire(
            lock,
            create,
            os.lockAttributes(),
        )
        state.updateAndGet {
            it.copy(
                exclusion = if (lease == null) it.exclusion else DirectShellStorageObservation.OBSERVED,
                busy = lease == null,
            )
        }
        if (lease != null) {
            try {
                requireFile(requireNotNull(os.node(lock)), lock = true)
                if (!lease.valid()) fail(DirectShellCredentialPlatformProblem.UNKNOWN)
            } catch (failure: Exception) {
                if (!lease.close()) throw DirectShellCredentialOwnershipUnresolved()
                throw failure
            }
        }
        return lease
    }

    override fun syncDirectory(path: Path) {
        checkPath(path, setOf(base, directory))
        requireDirectory(path, path == directory)
        syncDirectoryAt(path)
    }

    private fun syncDirectoryAt(path: Path, parentEntry: Boolean = false) {
        withDescriptor(path, DirectShellCredentialAccess.DIRECTORY) { fd ->
            try {
                os.sync(fd)
                state.updateAndGet {
                    if (parentEntry) {
                        it.copy(parentDirectorySync = DirectShellStorageObservation.OBSERVED)
                    } else {
                        it.copy(directorySync = DirectShellStorageObservation.OBSERVED)
                    }
                }
            } catch (failure: Exception) {
                state.updateAndGet {
                    if (parentEntry) {
                        it.copy(parentDirectorySync = observation(failure))
                    } else {
                        it.copy(directorySync = observation(failure))
                    }
                }
                throw failure
            }
        }
    }

    override fun writeNew(path: Path, bytes: ByteArray) {
        checkRecordPath(path, writable = true)
        require(bytes.size <= DirectShellCredentialCodec.MAX_RECORD) { "credential_record_size" }
        withDescriptor(path, DirectShellCredentialAccess.CREATE_NEW) { fd ->
            var offset = 0
            while (offset < bytes.size) {
                val written = os.write(fd, bytes, offset, bytes.size - offset)
                if (written !in 1..bytes.size - offset) fail(DirectShellCredentialPlatformProblem.UNKNOWN)
                offset += written
            }
            try {
                os.sync(fd)
                state.updateAndGet { it.copy(dataSync = DirectShellStorageObservation.OBSERVED) }
            } catch (failure: Exception) {
                state.updateAndGet { it.copy(dataSync = observation(failure)) }
                throw failure
            }
        }
    }

    override fun read(path: Path): ByteArray {
        checkRecordPath(path, writable = false)
        return withDescriptor(path, DirectShellCredentialAccess.READ) { fd ->
            val bytes = ByteArray(DirectShellCredentialCodec.MAX_RECORD + 1)
            var count = 0
            while (count < bytes.size) {
                val read = os.read(fd, bytes, count, bytes.size - count)
                if (read == 0) break
                if (read !in 1..bytes.size - count) fail(DirectShellCredentialPlatformProblem.UNKNOWN)
                count += read
            }
            if (count > DirectShellCredentialCodec.MAX_RECORD) fail(DirectShellCredentialPlatformProblem.UNKNOWN)
            bytes.copyOf(count)
        }
    }

    override fun linkNoReplace(record: Path, prepared: Path): Unit =
        throw UnsupportedOperationException("credential_android_link_unavailable")

    override fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult {
        var known: DirectShellCredentialPublishResult? = null
        var entered = false
        try {
            checkPath(record, setOf(directory.resolve(DirectShellCredentialStorage.RECORD)))
            checkPath(prepared, setOf(directory.resolve(DirectShellCredentialStorage.PENDING)))
            validateNamespace(directory)
            requireFile(requireNotNull(os.node(prepared)))
            withDescriptor(directory, DirectShellCredentialAccess.DIRECTORY) { fd ->
                entered = true
                known = os.publish(fd)
            }
        } catch (_: Exception) {
            // A close failure cannot erase already observed P. Missing call knowledge stays Unknown.
        }
        val result = known ?: if (entered) {
            DirectShellCredentialPublishResult.UNKNOWN
        } else {
            DirectShellCredentialPublishResult.NOT_ATTEMPTED
        }
        state.updateAndGet {
            it.copy(
                noReplace = when (result) {
                    DirectShellCredentialPublishResult.PUBLISHED_CONSUMED,
                    DirectShellCredentialPublishResult.COLLISION,
                    -> DirectShellStorageObservation.OBSERVED
                    DirectShellCredentialPublishResult.UNAVAILABLE,
                    DirectShellCredentialPublishResult.UNSUPPORTED,
                    -> DirectShellStorageObservation.UNSUPPORTED
                    else -> DirectShellStorageObservation.UNKNOWN
                },
                publicationAttempted = result.attempted,
                publicationSource = result.source,
                publicationErrno = os.publicationErrno,
                cleanupIncomplete = it.cleanupIncomplete || !os.resourcesDrained(),
            )
        }
        return result
    }

    override fun removeRedundant(path: Path) {
        checkRecordPath(path, writable = true)
        requireFile(requireNotNull(os.node(path)))
        os.unlink(path)
    }

    private fun checkRecordPath(path: Path, writable: Boolean) {
        val names = if (writable) {
            listOf(DirectShellCredentialStorage.ANCHOR, DirectShellCredentialStorage.PENDING)
        } else {
            listOf(
                DirectShellCredentialStorage.ANCHOR,
                DirectShellCredentialStorage.PENDING,
                DirectShellCredentialStorage.RECORD,
            )
        }
        checkPath(path, names.map(directory::resolve).toSet())
        validateNamespace(directory)
        os.node(path)?.let { requireFile(it) }
    }

    private fun checkPath(path: Path, allowed: Set<Path>) {
        if (path != path.toAbsolutePath().normalize() || path !in allowed) {
            fail(
                DirectShellCredentialPlatformProblem.FOREIGN_RECORD,
            )
        }
        if ((descriptor.get() != null && !active.get()) || !os.resourcesDrained()) {
            fail(DirectShellCredentialPlatformProblem.CLEANUP_INCOMPLETE)
        }
    }

    private fun requireDirectory(path: Path, privateMode: Boolean) {
        val node = os.node(path) ?: fail(DirectShellCredentialPlatformProblem.UNKNOWN)
        if (!node.owned || !node.directory || (privateMode && node.mode and 63 != 0)) {
            state.updateAndGet { it.copy(ownership = DirectShellStorageObservation.REJECTED) }
            fail(DirectShellCredentialPlatformProblem.FOREIGN_RECORD)
        }
        state.updateAndGet { it.copy(ownership = DirectShellStorageObservation.OBSERVED) }
    }

    private fun requireFile(node: DirectShellCredentialNode, lock: Boolean = false) {
        if (!node.owned || !node.regular || node.mode and 127 != 0 ||
            node.size !in 0..DirectShellCredentialCodec.MAX_RECORD.toLong() || (lock && node.size != 0L)
        ) {
            state.updateAndGet { it.copy(ownership = DirectShellStorageObservation.REJECTED) }
            fail(DirectShellCredentialPlatformProblem.FOREIGN_RECORD)
        }
    }

    private fun <T> withDescriptor(path: Path, access: DirectShellCredentialAccess, body: (FileDescriptor) -> T): T {
        if (!active.compareAndSet(false, true)) fail(DirectShellCredentialPlatformProblem.UNKNOWN)
        try {
            if (descriptor.get() != null) fail(DirectShellCredentialPlatformProblem.CLEANUP_INCOMPLETE)
            val fd = os.open(path, access)
            descriptor.set(fd)
            try {
                val opened = os.node(fd)
                val named = os.node(path) ?: fail(DirectShellCredentialPlatformProblem.UNKNOWN)
                if (opened.device != named.device || opened.inode != named.inode) {
                    fail(
                        DirectShellCredentialPlatformProblem.UNKNOWN,
                    )
                }
                if (access == DirectShellCredentialAccess.DIRECTORY) {
                    if (!opened.owned || !opened.directory) fail(DirectShellCredentialPlatformProblem.FOREIGN_RECORD)
                } else {
                    requireFile(opened)
                }
                return body(fd)
            } finally {
                try {
                    os.close(fd)
                    descriptor.set(null)
                } catch (_: Exception) {
                    // Never retry an ambiguous close: the integer descriptor may already have been reused.
                    state.updateAndGet { it.copy(cleanupIncomplete = true) }
                    fail(DirectShellCredentialPlatformProblem.CLEANUP_INCOMPLETE)
                }
            }
        } finally {
            active.set(false)
        }
    }

    private fun observation(failure: Exception): DirectShellStorageObservation =
        if ((failure as? DirectShellCredentialPlatformFailure)?.problem ==
            DirectShellCredentialPlatformProblem.UNSUPPORTED
        ) {
            DirectShellStorageObservation.UNSUPPORTED
        } else {
            DirectShellStorageObservation.UNKNOWN
        }

    private fun fail(problem: DirectShellCredentialPlatformProblem): Nothing =
        throw DirectShellCredentialPlatformFailure(
            problem,
        )
}

/** Public API only. API26 fails closed: the atomic close-on-exec open flag is public from27. */
internal class AndroidCredentialOs(
    private val apiLevel: () -> Int = { Build.VERSION.SDK_INT },
) : DirectShellCredentialOs {
    private val publisher = DirectShellNativeCredentialPublisher()
    private var lastPublication: DirectShellNativePublicationObservation? = null
    override val publicationErrno: Int? get() = lastPublication?.errno
    override fun resourcesDrained(): Boolean = publisher.resourcesDrained()
    override fun lockAttributes(): Array<FileAttribute<*>> =
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    override fun node(path: Path): DirectShellCredentialNode? = try {
        convert(Os.lstat(path.toString()))
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw sanitized(failure)
    }
    override fun node(fd: FileDescriptor): DirectShellCredentialNode = call { convert(Os.fstat(fd)) }
    override fun mkdir(path: Path) {
        try {
            Os.mkdir(path.toString(), 448)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EEXIST) throw sanitized(failure)
        }
    }
    override fun open(path: Path, access: DirectShellCredentialAccess): FileDescriptor = call {
        if (apiLevel() < 27) {
            throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNSUPPORTED)
        }
        // The injected negative-test gate cannot override actual platform availability.
        if (Build.VERSION.SDK_INT < 27) {
            throw DirectShellCredentialPlatformFailure(DirectShellCredentialPlatformProblem.UNSUPPORTED)
        }
        val common = OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW
        val flags = common or when (access) {
            // Public Android SDK has no O_DIRECTORY. fstat verifies the opened descriptor before fsync.
            DirectShellCredentialAccess.DIRECTORY -> OsConstants.O_RDONLY or OsConstants.O_NONBLOCK
            DirectShellCredentialAccess.READ -> OsConstants.O_RDONLY
            DirectShellCredentialAccess.CREATE_NEW -> OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL
        }
        Os.open(path.toString(), flags, 384)
    }
    override fun sync(fd: FileDescriptor) = call { Os.fsync(fd) }
    override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
        call { Os.read(fd, bytes, offset, count) }
    override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
        call { Os.write(fd, bytes, offset, count) }
    override fun close(fd: FileDescriptor) = call { Os.close(fd) }
    override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
        if (apiLevel() < 30 || Build.VERSION.SDK_INT < 30) return DirectShellCredentialPublishResult.UNAVAILABLE
        val result = publisher.publish(directory)
        lastPublication = result
        return result.result
    }
    override fun unlink(path: Path) = call { Os.remove(path.toString()) }

    private fun convert(stat: android.system.StructStat) = DirectShellCredentialNode(
        stat.st_uid == Process.myUid(),
        OsConstants.S_ISDIR(stat.st_mode),
        OsConstants.S_ISREG(stat.st_mode),
        stat.st_mode and 511,
        stat.st_size,
        stat.st_dev,
        stat.st_ino,
    )
    private inline fun <T> call(action: () -> T): T = try {
        action()
    } catch (failure: ErrnoException) {
        throw sanitized(failure)
    }
    private fun sanitized(failure: ErrnoException) = DirectShellCredentialPlatformFailure(
        if (failure.errno in setOf(OsConstants.ENOSYS, OsConstants.EOPNOTSUPP, OsConstants.EINVAL)) {
            DirectShellCredentialPlatformProblem.UNSUPPORTED
        } else {
            DirectShellCredentialPlatformProblem.UNKNOWN
        },
    )
}
