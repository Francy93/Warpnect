package io.warpnect.platform.audio.capture.shared

import android.annotation.SuppressLint
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Memory ownership only; the PCM V1 layout and pipe protocol are independent of this adapter. */
internal interface PcmMemoryRegion : AutoCloseable {
    fun mapReadWrite(): ByteBuffer

    fun unmap(buffer: ByteBuffer)
}

@SuppressLint("NewApi")
internal class JavaSharedPcmMemory(private val memory: SharedMemory) : PcmMemoryRegion {
    override fun mapReadWrite(): ByteBuffer = memory.mapReadWrite()

    override fun unmap(buffer: ByteBuffer) = SharedMemory.unmap(buffer)

    override fun close() = memory.close()
}

/** API26 ashmem compatibility. No path, generic descriptor broker, or native transport change. */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LegacyAshmemPcmMemory private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val size: Int,
    private val creator: MemoryFile? = null,
) : PcmMemoryRegion {
    private val mappings = IdentityHashMap<ByteBuffer, Runnable>()
    private var closed = false

    @Synchronized
    fun duplicateDescriptor(): ParcelFileDescriptor {
        check(!closed)
        return ParcelFileDescriptor.dup(descriptor.fileDescriptor)
    }

    @Synchronized
    override fun mapReadWrite(): ByteBuffer {
        check(!closed)
        val address = Os.mmap(
            0,
            size.toLong(),
            OsConstants.PROT_READ or OsConstants.PROT_WRITE,
            OsConstants.MAP_SHARED,
            descriptor.fileDescriptor,
            0,
        )
        val unmapped = AtomicBoolean(false)
        val release = Runnable { if (unmapped.compareAndSet(false, true)) Os.munmap(address, size.toLong()) }
        try {
            val buffer = Class.forName("java.nio.DirectByteBuffer").getConstructor(
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                FileDescriptor::class.java,
                Runnable::class.java,
                Boolean::class.javaPrimitiveType,
            ).newInstance(size, address, descriptor.fileDescriptor, release, false) as ByteBuffer
            mappings[buffer] = release
            return buffer
        } catch (error: Exception) {
            release.run()
            throw error
        }
    }

    @Synchronized
    override fun unmap(buffer: ByteBuffer) {
        val release = mappings.remove(buffer) ?: return
        // Invalidate duplicates/slices sharing MemoryRef before the backing mapping is released.
        try {
            buffer.javaClass.getMethod("setAccessible", Boolean::class.javaPrimitiveType).invoke(buffer, false)
        } finally {
            release.run()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        mappings.keys.toList().forEach { runCatching { unmap(it) } }
        try {
            descriptor.close()
        } finally {
            creator?.close()
        }
    }

    companion object {
        const val MAX_BYTES = 1 shl 20

        fun create(name: String, size: Int): LegacyAshmemPcmMemory {
            require(size in 1..MAX_BYTES)
            val memory = MemoryFile(name, size)
            try {
                memory.allowPurging(false)
                val fd = MemoryFile::class.java.getMethod("getFileDescriptor").invoke(memory) as FileDescriptor
                return LegacyAshmemPcmMemory(ParcelFileDescriptor.dup(fd), size, memory)
            } catch (error: Exception) {
                memory.close()
                throw error
            }
        }

        /** Takes ownership of exactly the authenticated PCM-region descriptor. */
        fun receive(descriptor: ParcelFileDescriptor, size: Int): LegacyAshmemPcmMemory {
            try {
                require(size in 1..MAX_BYTES)
                val actual = MemoryFile::class.java.getMethod("getSize", FileDescriptor::class.java)
                    .invoke(null, descriptor.fileDescriptor) as Int
                require(actual == size) { "pcm_ashmem_size_mismatch" }
                return LegacyAshmemPcmMemory(descriptor, size)
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
        }
    }
}
