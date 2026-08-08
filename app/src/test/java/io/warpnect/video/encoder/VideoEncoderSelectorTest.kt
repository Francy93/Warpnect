package io.warpnect.video.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEncoderSelectorTest {
    private val request = VideoEncoderRequest(
        width = 1280,
        height = 720,
        frameRate = 60,
        bitrateBps = 8_000_000,
        iFrameIntervalSeconds = 1f,
    )

    @Test
    fun hardwareCandidateSelectedDeterministically() {
        val software = candidate(
            name = "z.software",
            hardwareAcceleration = VideoEncoderHardwareAcceleration.Software,
            softwareOnly = true,
        )
        val alias = candidate(name = "a.alias", alias = true)
        val hardware = candidate(name = "b.hardware", canonicalName = "b.hardware")

        val capabilities = VideoEncoderSelector.select(request, listOf(software, alias, hardware))

        assertEquals(VideoEncoderError.None, capabilities.error)
        assertEquals("b.hardware", capabilities.selectedCodec?.codecName)
        assertTrue(capabilities.isSupported)
    }

    @Test
    fun softwareOnlyCandidateIsRejected() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(
                candidate(
                    name = "software",
                    hardwareAcceleration = VideoEncoderHardwareAcceleration.Software,
                    softwareOnly = true,
                ),
            ),
        )

        assertEquals(VideoEncoderError.HardwareEncoderUnavailable, capabilities.error)
        assertNull(capabilities.selectedCodec)
    }

    @Test
    fun unknownHardwareClassificationIsRejected() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(candidate(name = "legacy", hardwareAcceleration = VideoEncoderHardwareAcceleration.Unknown)),
        )

        assertEquals(VideoEncoderError.HardwareClassificationUnavailable, capabilities.error)
    }

    @Test
    fun surfaceInputIsRequired() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(candidate(name = "no-surface", supportsSurfaceInput = false)),
        )

        assertEquals(VideoEncoderError.SurfaceInputUnsupported, capabilities.error)
    }

    @Test
    fun unsupportedDimensionsFailBeforeFrameRateAndBitrate() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(candidate(name = "bad-size", sizeSupported = false)),
        )

        assertEquals(VideoEncoderError.UnsupportedDimensions, capabilities.error)
    }

    @Test
    fun unsupportedFrameRateIsExplicit() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(candidate(name = "bad-fps", sizeAndRateSupported = false)),
        )

        assertEquals(VideoEncoderError.UnsupportedFrameRate, capabilities.error)
    }

    @Test
    fun unsupportedBitrateIsExplicit() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(candidate(name = "bad-bitrate", bitrateSupported = false)),
        )

        assertEquals(VideoEncoderError.UnsupportedBitrate, capabilities.error)
    }

    @Test
    fun unsupportedCbrIsExplicit() {
        val capabilities = VideoEncoderSelector.select(
            request,
            listOf(candidate(name = "bad-cbr", bitrateModeSupported = false)),
        )

        assertEquals(VideoEncoderError.UnsupportedBitrateMode, capabilities.error)
    }

    private fun candidate(
        name: String,
        canonicalName: String? = name,
        hardwareAcceleration: VideoEncoderHardwareAcceleration = VideoEncoderHardwareAcceleration.Hardware,
        softwareOnly: Boolean? = false,
        vendor: Boolean? = true,
        alias: Boolean? = false,
        supportsSurfaceInput: Boolean = true,
        widthSupported: Boolean = true,
        heightSupported: Boolean = true,
        sizeSupported: Boolean = true,
        sizeAndRateSupported: Boolean = true,
        bitrateSupported: Boolean = true,
        bitrateModeSupported: Boolean = true,
    ) = VideoEncoderCandidate(
        info = VideoEncoderCodecInfo(
            codecName = name,
            canonicalName = canonicalName,
            hardwareAcceleration = hardwareAcceleration,
            softwareOnly = softwareOnly,
            vendor = vendor,
            alias = alias,
        ),
        supportsAvc = true,
        supportsSurfaceInput = supportsSurfaceInput,
        widthSupported = widthSupported,
        heightSupported = heightSupported,
        sizeSupported = sizeSupported,
        sizeAndRateSupported = sizeAndRateSupported,
        bitrateSupported = bitrateSupported,
        bitrateModeSupported = bitrateModeSupported,
        minBitrateBps = 1,
        maxBitrateBps = 100_000_000,
    )
}
