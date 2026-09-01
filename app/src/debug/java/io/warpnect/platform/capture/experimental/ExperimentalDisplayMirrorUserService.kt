package io.warpnect.platform.capture.experimental

import android.os.Bundle
import android.view.Surface

/**
 * Runs only in the debug APK under the Shizuku UserService identity. It is deliberately separate
 * from Warpnect's production capture gateway and never creates a Session or transports media.
 */
class ExperimentalDisplayMirrorUserService : IExperimentalDisplayMirrorService.Stub() {
    private val lock = Any()
    private val probe = ExperimentalDisplayMirrorProbeV2()

    override fun runProbe(probeKind: Int): Bundle = synchronized(lock) {
        probe.run(ExperimentalDisplayMirrorProbeKind.fromCode(probeKind))
    }

    override fun startLegacyMirror(targetSurface: Surface?, secure: Boolean): Bundle = Bundle().apply {
        putString("failure", "LegacyUserServiceDoesNotSupportSplitMirror")
    }

    override fun stopLegacyMirror(): Bundle = Bundle().apply {
        putBoolean("cleanup_succeeded", true)
    }

    @Suppress("unused")
    fun destroy() = Unit
}
