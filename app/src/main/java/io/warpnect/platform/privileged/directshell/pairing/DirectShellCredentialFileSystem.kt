package io.warpnect.platform.privileged.directshell.pairing

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.ConcurrentHashMap

/** Narrow worker-only platform boundary. No adapter is registered by application composition. */
internal open class DirectShellCredentialFileSystem {
    open fun canonicalBase(path: Path): Path {
        val base = path.toAbsolutePath().normalize()
        require(attributes(base)?.isDirectory == true) { "credential_base_invalid" }
        return base.toRealPath()
    }

    open fun prepareNamespace(base: Path): Path {
        val directory = base.resolve(DirectShellCredentialStorage.DIRECTORY)
        try {
            Files.createDirectory(directory)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Existing records are inspected under the stable lease, never overwritten.
        }
        validateNamespace(directory)
        return directory
    }

    open fun validateNamespace(directory: Path) {
        require(attributes(directory)?.isDirectory == true) { "credential_directory_invalid" }
    }

    open fun acquireLease(directory: Path, create: Boolean): DirectShellCredentialLease? =
        DirectShellCredentialLease.acquire(directory.resolve(DirectShellCredentialStorage.LOCK), create)

    open fun resourcesDrained(): Boolean = true

    open fun syncDirectory(path: Path) {
        // Optional provider capability. A failed call is never replaced by a no-op or file-only sync.
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    open fun writeNew(path: Path, bytes: ByteArray) {
        require(bytes.size <= DirectShellCredentialCodec.MAX_RECORD) { "credential_record_size" }
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "credential_write_incomplete" }
                channel.force(true)
            }
    }

    open fun linkNoReplace(record: Path, prepared: Path) {
        Files.createLink(record, prepared)
    }

    /** Portable host provider retains the source. Android overrides publication, never falls back here. */
    open fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult = try {
        linkNoReplace(record, prepared)
        DirectShellCredentialPublishResult.PUBLISHED_RETAINED
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        DirectShellCredentialPublishResult.COLLISION
    } catch (_: UnsupportedOperationException) {
        DirectShellCredentialPublishResult.UNAVAILABLE
    } catch (_: Exception) {
        DirectShellCredentialPublishResult.UNKNOWN
    }

    open fun removeRedundant(path: Path) {
        Files.delete(path)
    }

    open fun read(path: Path): ByteArray {
        require(attributes(path)?.isRegularFile == true) { "credential_not_regular" }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DirectShellCredentialCodec.MAX_RECORD + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                check(read > 0) { "credential_read_incomplete" }
                count += read
            }
            require(count <= DirectShellCredentialCodec.MAX_RECORD) { "credential_record_size" }
            buffer.copyOf(count)
        }
    }

    companion object {
        fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }
    }
}

/** Stable inode, one tryLock, no polling. Registry prevents POSIX close-on-another-FD lock loss in one JVM. */
internal class DirectShellCredentialLease private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val fileKey: Any?,
) {
    fun valid(): Boolean = lock.isValid &&
        DirectShellCredentialFileSystem.attributes(path)?.let {
            it.isRegularFile && (fileKey == null || it.fileKey() == fileKey)
        } == true

    fun close(): Boolean = try {
        lock.release()
        channel.close()
        owners.remove(path, this)
        true
    } catch (_: Exception) {
        // Retain a strong namespace fence, including descriptors, until explicit owned recovery.
        false
    }

    override fun toString(): String = "DirectShellCredentialLease(redacted)"

    companion object {
        private val owners = ConcurrentHashMap<Path, Any>()
        private class Opening(var channel: FileChannel? = null)

        fun acquire(
            path: Path,
            create: Boolean,
            attributes: Array<FileAttribute<*>> = emptyArray(),
        ): DirectShellCredentialLease? {
            val placeholder = Opening()
            if (owners.putIfAbsent(path, placeholder) != null) return null
            var channel: FileChannel? = null
            try {
                val existing = DirectShellCredentialFileSystem.attributes(path)
                require(existing == null || existing.isRegularFile) { "credential_lock_invalid" }
                val options: Array<OpenOption> = if (create) {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                } else {
                    arrayOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                }
                channel = FileChannel.open(path, options.toSet(), *attributes)
                placeholder.channel = channel
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: return null
                val key = DirectShellCredentialFileSystem.attributes(path)?.fileKey()
                // fileKey is optional (null on Windows NIO). Cooperating writers never unlink this inode.
                // OS lock + canonical-path registry is authoritative; hostile same-UID replacement is out of scope.
                val lease = DirectShellCredentialLease(path, channel, lock, key)
                owners[path] = lease
                channel = null
                return lease
            } finally {
                try {
                    channel?.close()
                } catch (_: Exception) {
                    throw DirectShellCredentialOwnershipUnresolved()
                }
                owners.remove(path, placeholder)
            }
        }
    }
}

internal class DirectShellCredentialOwnershipUnresolved : Exception()

internal enum class DirectShellCredentialSource { RETAINED, CONSUMED, UNKNOWN }

/** P knowledge only. Directory synchronization and physical drain are independently owned facts. */
internal enum class DirectShellCredentialPublishResult(
    val attempted: Boolean?,
    val publication: DirectShellPairingPublication,
    val source: DirectShellCredentialSource,
) {
    PUBLISHED_RETAINED(true, DirectShellPairingPublication.PUBLISHED, DirectShellCredentialSource.RETAINED),
    PUBLISHED_CONSUMED(true, DirectShellPairingPublication.PUBLISHED, DirectShellCredentialSource.CONSUMED),
    COLLISION(true, DirectShellPairingPublication.NOT_PUBLISHED, DirectShellCredentialSource.RETAINED),
    UNAVAILABLE(false, DirectShellPairingPublication.NOT_PUBLISHED, DirectShellCredentialSource.UNKNOWN),
    UNSUPPORTED(true, DirectShellPairingPublication.NOT_PUBLISHED, DirectShellCredentialSource.UNKNOWN),
    NOT_ATTEMPTED(false, DirectShellPairingPublication.NOT_PUBLISHED, DirectShellCredentialSource.UNKNOWN),
    UNKNOWN_EFFECT(true, DirectShellPairingPublication.UNKNOWN, DirectShellCredentialSource.UNKNOWN),
    UNKNOWN(null, DirectShellPairingPublication.UNKNOWN, DirectShellCredentialSource.UNKNOWN),
}

internal enum class DirectShellCredentialPlatformProblem { UNSUPPORTED, UNKNOWN, CLEANUP_INCOMPLETE, FOREIGN_RECORD }

internal class DirectShellCredentialPlatformFailure(val problem: DirectShellCredentialPlatformProblem) :
    Exception("credential_platform_${problem.name}")
