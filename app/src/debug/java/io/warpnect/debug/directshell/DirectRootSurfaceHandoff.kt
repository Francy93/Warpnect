package io.warpnect.debug.directshell

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.warpnect.capture.CaptureRequest
import io.warpnect.platform.capture.PrivilegedSurfaceLease
import io.warpnect.platform.capture.SurfaceLeaseProvider
import io.warpnect.platform.privileged.runtime.RootHelperIdentity
import java.security.SecureRandom

/** Root-specific Binder method; the existing Shell v1 endpoint remains UID2000-only. */
internal object DirectRootSurfaceHandoff {
    const val METHOD = "open_root_encoder_surface_v1"
    private var lease: PrivilegedSurfaceLease? = null
    private var surface: Surface? = null

    @Synchronized
    fun arm(identity: RootHelperIdentity, key: ByteArray, request: CaptureRequest, target: Surface): Long {
        check(identity.uid == 0 && identity.gid == 0 && target.isValid)
        release()
        val surfaceGeneration = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
        lease = PrivilegedSurfaceLease(
            SurfaceLeaseProvider.DIRECT_ROOT, identity.generation, surfaceGeneration, identity.pid,
            request.outputWidth, request.outputHeight, SystemClock.elapsedRealtime() + 10_000, key,
        )
        surface = target // Borrowed; only the app encoder releases its input Surface.
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_surface_armed generation=${identity.generation} surface=$surfaceGeneration",
        )
        return surfaceGeneration
    }

    @Synchronized fun issue(uid: Int, pid: Int, extras: Bundle?): Bundle {
        val current = lease
        val provider = extras?.getString(
            "provider",
        )?.let { name -> SurfaceLeaseProvider.entries.find { it.name == name } }
        val accepted = current != null && extras != null && provider != null && surface?.isValid == true &&
            extras.getInt("magic") == PrivilegedSurfaceLease.MAGIC &&
            extras.getInt("version") == PrivilegedSurfaceLease.VERSION && current.accept(
                uid, pid, provider, extras.getLong("generation"), extras.getLong("surface_generation"),
                extras.getInt("width"), extras.getInt("height"), extras.getByteArray("proof"),
                SystemClock.elapsedRealtime(),
            )
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_surface_handoff result=${if (accepted) "PASS" else "REJECTED"} " +
                "uid=$uid pid=$pid generation=${current?.generation} surface=${current?.surfaceGeneration}",
        )
        return Bundle().apply {
            putBoolean("accepted", accepted)
            if (accepted) putParcelable("surface", surface)
        }
    }

    @Synchronized fun release() {
        lease?.close()
        lease = null
        surface = null
    }

    @Synchronized fun active(): Boolean = lease != null
}
