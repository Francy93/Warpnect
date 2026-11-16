package io.warpnect.platform.privileged.directshell.pairing

import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One temporary duplicate of a borrowed FD, owned by the same synchronous storage operation. */
internal interface DirectShellPublicationDescriptor {
    val number: Int
    fun close()
}

internal data class DirectShellNativePublicationObservation(
    val result: DirectShellCredentialPublishResult,
    val errno: Int? = null,
)

/** Inert construction. No load, Android call or crypto occurs under the P1 monitor or on passive readiness. */
internal class DirectShellNativeCredentialPublisher(
    private val apiLevel: () -> Int = { Build.VERSION.SDK_INT },
    private val load: () -> Unit = { System.loadLibrary("warpnect_directshell_publication") },
    private val duplicate: (FileDescriptor) -> DirectShellPublicationDescriptor = { source ->
        val owned = ParcelFileDescriptor.dup(source)
        object : DirectShellPublicationDescriptor {
            override val number: Int get() = owned.fd
            override fun close() = owned.close()
        }
    },
    private val invoke: (Int) -> Long = { DirectShellPublicationNativeEntry().publishBorrowedDirectory(it) },
) {
    private val active = AtomicBoolean(false)
    private val held = AtomicReference<DirectShellPublicationDescriptor?>(null)
    fun resourcesDrained(): Boolean = !active.get() && held.get() == null

    fun publish(directory: FileDescriptor): DirectShellNativePublicationObservation {
        if (apiLevel() < 30) return unavailable()
        if (!active.compareAndSet(false, true)) return notAttempted()
        try {
            if (held.get() != null) return notAttempted()
            try {
                load()
            } catch (_: LinkageError) {
                return unavailable()
            } catch (_: Exception) {
                return unavailable()
            }
            val owned = try {
                duplicate(directory)
            } catch (_: Exception) {
                return notAttempted()
            }
            held.set(owned)
            return try {
                try {
                    decode(invoke(owned.number))
                } catch (_: LinkageError) {
                    DirectShellNativePublicationObservation(DirectShellCredentialPublishResult.UNKNOWN)
                } catch (_: Exception) {
                    DirectShellNativePublicationObservation(DirectShellCredentialPublishResult.UNKNOWN)
                }
            } finally {
                try {
                    owned.close()
                    held.set(null)
                } catch (_: Exception) {
                    // Keep ownership uncertainty; never retry a possibly reused integer descriptor.
                }
            }
        } finally {
            active.set(false)
        }
    }

    private fun unavailable() = DirectShellNativePublicationObservation(DirectShellCredentialPublishResult.UNAVAILABLE)
    private fun notAttempted() = DirectShellNativePublicationObservation(
        DirectShellCredentialPublishResult.NOT_ATTEMPTED,
    )

    companion object {
        /** Stable private JNI metadata, not SCL ABI. Unrecognized/contradictory values are Unknown. */
        internal fun decode(value: Long): DirectShellNativePublicationObservation {
            val code = (value and 0xffffffffL).toInt()
            val error = (value ushr 32).toInt()
            val result = when {
                code == 1 && error == 0 -> DirectShellCredentialPublishResult.PUBLISHED_CONSUMED
                code == 2 && error == 17 -> DirectShellCredentialPublishResult.COLLISION
                code == 3 && error in setOf(1, 13, 22, 38, 95) -> DirectShellCredentialPublishResult.UNSUPPORTED
                code == 4 && error in 1..4095 -> DirectShellCredentialPublishResult.NOT_ATTEMPTED
                code == 5 && error in 1..4095 -> DirectShellCredentialPublishResult.UNKNOWN_EFFECT
                else -> DirectShellCredentialPublishResult.UNKNOWN
            }
            return DirectShellNativePublicationObservation(result, error.takeIf { it in 0..4095 })
        }
    }
}

/** No initializer. Resolution/loading happens only after the API30 guard in the explicit worker call. */
internal class DirectShellPublicationNativeEntry {
    external fun publishBorrowedDirectory(directoryFd: Int): Long
}
