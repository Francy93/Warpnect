package io.warpnect.platform.video.encoder

import io.warpnect.video.encoder.VideoEncoderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbrCapabilityFallbackTest {
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
    fun metadataSupportDoesNotInvokeActiveProbe() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = true,
            allOtherRequirementsSupported = true,
            key = key,
        )

        assertTrue(decision.supported)
        assertEquals(CbrCapabilityDecisionSource.Metadata, decision.source)
        assertEquals(0, probe.calls)
    }

    @Test
    fun metadataFalseNegativeUsesSuccessfulExactProbe() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = false,
            allOtherRequirementsSupported = true,
            key = key,
        )

        assertTrue(decision.supported)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, decision.source)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.Supported, decision.probeResult)
        assertEquals(1, probe.calls)
    }

    @Test
    fun failedExactProbeRetainsStrictCbrUnavailability() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = false,
            allOtherRequirementsSupported = true,
            key = key,
        )

        assertFalse(decision.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed, decision.probeResult)
        assertEquals(1, probe.calls)
    }

    @Test
    fun otherCapabilityFailureDoesNotInstantiateCodec() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = false,
            allOtherRequirementsSupported = false,
            key = key,
        )

        assertFalse(decision.supported)
        assertEquals(CbrCapabilityDecisionSource.NotEligible, decision.source)
        assertEquals(0, probe.calls)
    }

    @Test
    fun exactProbeResultIsCachedForTheProcessLifetimeBoundedCache() {
        val rawProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)
        val probe = CachedExactVideoEncoderCapabilityProbe(rawProbe)
        val fallback = CbrCapabilityFallback(probe)

        val first = fallback.resolve(false, true, key)
        val second = fallback.resolve(false, true, key)

        assertTrue(first.supported)
        assertTrue(second.supported)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, first.source)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbeCache, second.source)
        assertEquals(1, rawProbe.calls)
    }

    @Test
    fun failedExactProbeIsAlsoCached() {
        val rawProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed)
        val probe = CachedExactVideoEncoderCapabilityProbe(rawProbe)
        val fallback = CbrCapabilityFallback(probe)

        val first = fallback.resolve(false, true, key)
        val second = fallback.resolve(false, true, key)

        assertFalse(first.supported)
        assertFalse(second.supported)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbeCache, second.source)
        assertEquals(1, rawProbe.calls)
    }

    private class RecordingProbe(
        private val result: ExactVideoEncoderCapabilityProbeResult,
    ) : ExactVideoEncoderCapabilityProbe {
        var calls = 0

        override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision {
            calls += 1
            return CbrCapabilityDecision(
                supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
                source = CbrCapabilityDecisionSource.ActiveProbe,
                probeResult = result,
            )
        }
    }
}
