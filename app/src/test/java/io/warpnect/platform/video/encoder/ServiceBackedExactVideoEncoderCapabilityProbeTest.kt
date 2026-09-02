package io.warpnect.platform.video.encoder

import io.warpnect.video.encoder.VideoEncoderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceBackedExactVideoEncoderCapabilityProbeTest {
    private val key = ExactVideoEncoderCapabilityKey.from(
        codecName = "vendor.avc.encoder",
        request = VideoEncoderRequest(
            width = 1280,
            height = 720,
            frameRate = 60,
            bitrateBps = 8_000_000,
            iFrameIntervalSeconds = 1f,
        ),
    )

    @Test
    fun successfulDisposableProcessProbeAdmitsStrictCbr() {
        val caller = RecordingCaller(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = ServiceBackedExactVideoEncoderCapabilityProbe(caller) { false }.probe(key)

        assertTrue(decision.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.Supported, decision.probeResult)
        assertEquals(1, caller.calls)
    }

    @Test
    fun serviceProcessDeathBecomesTypedNegativeQualification() {
        val caller = RecordingCaller(ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied)

        val decision = ServiceBackedExactVideoEncoderCapabilityProbe(caller) { false }.probe(key)

        assertFalse(decision.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied, decision.probeResult)
        assertEquals(1, caller.calls)
    }

    @Test
    fun serviceTimeoutBecomesTypedNegativeQualification() {
        val caller = RecordingCaller(ExactVideoEncoderCapabilityProbeResult.ProbeTimedOut)

        val decision = ServiceBackedExactVideoEncoderCapabilityProbe(caller) { false }.probe(key)

        assertFalse(decision.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.ProbeTimedOut, decision.probeResult)
    }

    @Test
    fun mainThreadDoesNotBindOrInvokeTheProbeService() {
        val caller = RecordingCaller(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = ServiceBackedExactVideoEncoderCapabilityProbe(caller) { true }.probe(key)

        assertFalse(decision.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.MainThreadRejected, decision.probeResult)
        assertEquals(0, caller.calls)
    }

    private class RecordingCaller(
        private val result: ExactVideoEncoderCapabilityProbeResult,
    ) : ExactVideoEncoderProbeServiceCaller {
        var calls = 0

        override fun probe(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult {
            calls += 1
            return result
        }
    }
}
