package io.warpnect.platform.capture.experimental

import android.os.Bundle

/**
 * Runs only in the debug APK under the Shizuku UserService identity. It is deliberately separate
 * from Warpnect's production capture gateway and never creates a Session or transports media.
 */
class ExperimentalDisplayMirrorUserService : IExperimentalDisplayMirrorService.Stub() {
    private val lock = Any()
    private val probe = ExperimentalDisplayMirrorProbe()

    override fun runProbe(probeKind: Int): Bundle = synchronized(lock) {
        probe.run(ExperimentalDisplayMirrorProbeKind.fromCode(probeKind))
    }

    @Suppress("unused")
    fun destroy() = Unit
}
