package io.warpnect.video.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderSelectorTest {
    private val config = VideoDecoderConfig(
        width = 1280,
        height = 720,
        configGeneration = 1,
        codecSpecificData = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
    )

    @Test
    fun hardwareAvcCandidateIsSelected() {
        val selected = VideoDecoderSelector.select(config, listOf(candidate(name = "hardware-avc")))

        assertTrue(selected.isSupported)
        assertEquals("hardware-avc", selected.selectedCodec?.codecName)
        assertEquals(VideoDecoderQualification.FrameworkHardware, selected.qualification)
    }

    @Test
    fun softwareOnlyCandidateIsRejected() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(
                    name = "software-avc",
                    hardwareAcceleration = VideoDecoderHardwareAcceleration.Software,
                    softwareOnly = true,
                ),
            ),
        )

        assertEquals(VideoDecoderError.HardwareDecoderUnavailable, selected.error)
    }

    @Test
    fun legacyUnknownCandidateRequiresActiveQualification() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(
                    name = "legacy-avc",
                    hardwareAcceleration = VideoDecoderHardwareAcceleration.Unknown,
                    softwareOnly = null,
                ),
            ),
        )

        assertEquals(VideoDecoderError.LegacyQualificationRequired, selected.error)
        assertEquals("legacy-avc", selected.selectedCodec?.codecName)
        assertEquals(VideoDecoderQualification.LegacyCandidate, selected.qualification)
    }

    @Test
    fun knownLegacySoftwareFamilyIsRejectedWithoutActiveQualification() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(
                    name = "OMX.google.h264.decoder",
                    hardwareAcceleration = VideoDecoderHardwareAcceleration.Unknown,
                    softwareOnly = null,
                ),
            ),
        )

        assertEquals(VideoDecoderError.HardwareDecoderUnavailable, selected.error)
        assertEquals(VideoDecoderQualification.LegacySoftwareFamilyRejected, selected.qualification)
    }

    @Test
    fun legacySamsungSoftwareFamilyIsRejectedWithoutActiveQualification() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(
                    name = "OMX.SEC.avc.sw.dec",
                    hardwareAcceleration = VideoDecoderHardwareAcceleration.Unknown,
                    softwareOnly = null,
                ),
            ),
        )

        assertEquals(VideoDecoderError.HardwareDecoderUnavailable, selected.error)
        assertEquals(VideoDecoderQualification.LegacySoftwareFamilyRejected, selected.qualification)
    }

    @Test
    fun legacyCandidateWithUnsupportedRateDoesNotAdvanceToActiveQualification() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(
                    name = "legacy-avc",
                    hardwareAcceleration = VideoDecoderHardwareAcceleration.Unknown,
                    softwareOnly = null,
                    sizeAndRateSupported = false,
                ),
            ),
        )

        assertEquals(VideoDecoderError.UnsupportedFrameRate, selected.error)
    }

    @Test
    fun unsupportedDimensionsAreRejected() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(candidate(name = "hardware-avc", sizeSupported = false)),
        )

        assertEquals(VideoDecoderError.UnsupportedDimensions, selected.error)
    }

    @Test
    fun unsupportedExpectedFrameRateIsRejected() {
        val selected = VideoDecoderSelector.select(
            config.copy(expectedFrameRate = 120),
            listOf(candidate(name = "hardware-avc", sizeAndRateSupported = false)),
        )

        assertEquals(VideoDecoderError.UnsupportedFrameRate, selected.error)
    }

    @Test
    fun lowLatencyCapableDecoderIsPreferredButNotRequired() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(name = "hardware-no-low-latency", lowLatency = false),
                candidate(name = "hardware-low-latency", lowLatency = true),
            ),
        )

        assertEquals("hardware-low-latency", selected.selectedCodec?.codecName)
    }

    @Test
    fun hardwareWithoutLowLatencyStillSucceeds() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(candidate(name = "hardware-no-low-latency", lowLatency = false)),
        )

        assertTrue(selected.isSupported)
        assertEquals(false, selected.selectedCodec?.lowLatencyFeatureSupported)
    }

    @Test
    fun deterministicTieBreakPrefersNonAliasThenName() {
        val selected = VideoDecoderSelector.select(
            config,
            listOf(
                candidate(name = "z-decoder", alias = false),
                candidate(name = "a-alias", alias = true),
                candidate(name = "b-decoder", alias = false),
            ),
        )

        assertEquals("b-decoder", selected.selectedCodec?.codecName)
    }

    private fun candidate(
        name: String,
        hardwareAcceleration: VideoDecoderHardwareAcceleration = VideoDecoderHardwareAcceleration.Hardware,
        softwareOnly: Boolean? = false,
        alias: Boolean? = false,
        sizeSupported: Boolean = true,
        sizeAndRateSupported: Boolean = true,
        lowLatency: Boolean? = null,
    ): VideoDecoderCandidate = VideoDecoderCandidate(
        info = VideoDecoderCodecInfo(
            codecName = name,
            canonicalName = name,
            hardwareAcceleration = hardwareAcceleration,
            softwareOnly = softwareOnly,
            vendor = true,
            alias = alias,
            lowLatencyFeatureSupported = lowLatency,
        ),
        supportsAvc = true,
        widthSupported = sizeSupported,
        heightSupported = sizeSupported,
        sizeSupported = sizeSupported,
        sizeAndRateSupported = sizeAndRateSupported,
        lowLatencyFeatureSupported = lowLatency,
        widthAlignment = 2,
        heightAlignment = 2,
        minWidth = 16,
        maxWidth = 4096,
        minHeight = 16,
        maxHeight = 4096,
    )
}
