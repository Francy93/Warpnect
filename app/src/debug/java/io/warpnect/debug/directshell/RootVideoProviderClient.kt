package io.warpnect.debug.directshell

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.Surface
import io.warpnect.platform.capture.PrivilegedSurfaceLease
import io.warpnect.platform.capture.RootVideoRequest
import io.warpnect.platform.capture.SurfaceLeaseProvider
import java.lang.reflect.Method

/** Legacy framework-mediated acquisition, selected by actual method signatures, not SDK/device rules. */
internal class RootVideoProviderClient private constructor(
    private val manager: Any,
    private val provider: Any,
    private val call: Method,
    private val remove: Method,
    private val token: IBinder,
) : AutoCloseable {
    private var closed = false

    fun surface(request: RootVideoRequest, key: ByteArray): Surface = receive(request, key)

    @Suppress("DEPRECATION")
    private fun receive(
        request: RootVideoRequest,
        key: ByteArray,
        kind: SurfaceLeaseProvider = SurfaceLeaseProvider.DIRECT_ROOT,
        magic: Int = PrivilegedSurfaceLease.MAGIC,
    ): Surface {
        check(!closed && Process.myUid() == 0)
        val proof = PrivilegedSurfaceLease.proof(
            key,
            kind,
            request.generation,
            request.surfaceGeneration,
            request.capture.outputWidth,
            request.capture.outputHeight,
        )
        try {
            val extras = Bundle().apply {
                putInt("magic", magic)
                putInt("version", PrivilegedSurfaceLease.VERSION)
                putString("provider", kind.name)
                putLong("generation", request.generation)
                putLong("surface_generation", request.surfaceGeneration)
                putInt("width", request.capture.outputWidth)
                putInt("height", request.capture.outputHeight)
                putByteArray("proof", proof)
            }
            // A UID0 helper is not com.android.shell and has no Application/package attribution.
            val reply = call.invoke(provider, null, DirectRootSurfaceHandoff.METHOD, null, extras) as? Bundle
                ?: error("root_surface_reply_absent")
            reply.classLoader = Surface::class.java.classLoader
            val surface = reply.getParcelable<Surface>("surface")
            if (!reply.getBoolean("accepted") || surface?.isValid != true) {
                surface?.release()
                error("root_surface_handoff_rejected")
            }
            return surface
        } finally {
            proof.fill(0)
        }
    }

    fun verifyStaleRejection(request: RootVideoRequest, key: ByteArray) {
        for (stale in listOf(
            request.copy(generation = request.generation - 1),
            request.copy(surfaceGeneration = request.surfaceGeneration - 1),
        )) {
            try {
                val unexpected = surface(stale, key)
                unexpected.release()
                error("root_surface_stale_request_accepted")
            } catch (error: IllegalStateException) {
                check(error.message == "root_surface_handoff_rejected")
            }
        }
        val wrongKey = key.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        try {
            rejected { receive(request, wrongKey) }
            rejected { receive(request, key, SurfaceLeaseProvider.DIRECT_SHELL) }
            rejected { receive(request, key, magic = 0) }
        } finally {
            wrongKey.fill(0)
        }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_surface_stale_rejection runtime=REJECTED surface=REJECTED " +
                "hmac=REJECTED provider=REJECTED magic=REJECTED",
        )
    }

    fun verifyConsumedRejection(request: RootVideoRequest, key: ByteArray) {
        rejected { receive(request, key) }
        Log.i("WarpnectDirectRoot", "direct_root_surface_replay result=REJECTED")
    }

    private fun rejected(operation: () -> Surface) {
        try {
            operation().release()
            error("root_surface_negative_request_accepted")
        } catch (error: IllegalStateException) {
            check(error.message == "root_surface_handoff_rejected")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        remove.invoke(manager, DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY, token)
    }

    companion object {
        fun open(): RootVideoProviderClient {
            check(Process.myUid() == 0)
            val service = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
            val manager = Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, service)
                ?: error("root_activity_manager_absent")
            val type = Class.forName("android.app.IActivityManager")
            val acquire = type.getMethod(
                "getContentProviderExternal",
                String::class.java,
                Int::class.javaPrimitiveType,
                IBinder::class.java,
            )
            val remove = type.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
            val call = Class.forName("android.content.IContentProvider").getMethod(
                "call",
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
            val token = Binder()
            val holder = acquire.invoke(
                manager, DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY, 0, token,
            )
                ?: error("root_surface_provider_absent")
            try {
                val provider = holder.javaClass.getDeclaredField("provider").apply { isAccessible = true }.get(holder)
                    ?: error("root_surface_provider_null")
                Log.i("WarpnectDirectRoot", "direct_root_surface_framework acquire=$acquire call=$call release=$remove")
                return RootVideoProviderClient(manager, provider, call, remove, token)
            } catch (error: Exception) {
                remove.invoke(manager, DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY, token)
                throw error
            }
        }
    }
}
