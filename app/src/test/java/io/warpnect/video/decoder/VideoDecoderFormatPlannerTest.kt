package io.warpnect.video.decoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderFormatPlannerTest {
    private val config = VideoDecoderConfig(
        width = 1920,
        height = 1080,
        configGeneration = 7,
        codecSpecificData = listOf(byteArrayOf(0, 0, 1, 103), byteArrayOf(0, 0, 1, 104)),
        maxInputSizeBytes = 256 * 1024,
    )

    @Test
    fun decoderFormatPlanContainsOnlyDecoderFields() {
        val plan = VideoDecoderFormatPlanner.build(
            config = config,
            lowLatencyFeatureSupported = true,
            support = VideoDecoderFormatSupport(supportsLowLatencyKey = true),
        )

        assertEquals(VideoDecoderCodec.Avc.mimeType, plan.mimeType)
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        assertEquals(256 * 1024, plan.maxInputSizeBytes)
        assertTrue(plan.lowLatencyRequested)
        assertArrayEquals(byteArrayOf(0, 0, 1, 103), plan.codecSpecificData[0])
        assertArrayEquals(byteArrayOf(0, 0, 1, 104), plan.codecSpecificData[1])
    }

    @Test
    fun lowLatencyIsNotRequestedWithoutFeatureSupport() {
        val plan = VideoDecoderFormatPlanner.build(
            config = config,
            lowLatencyFeatureSupported = false,
            support = VideoDecoderFormatSupport(supportsLowLatencyKey = true),
        )

        assertFalse(plan.lowLatencyRequested)
    }

    @Test
    fun lowLatencyIsNotRequestedWithoutPlatformKeySupport() {
        val plan = VideoDecoderFormatPlanner.build(
            config = config,
            lowLatencyFeatureSupported = true,
            support = VideoDecoderFormatSupport(supportsLowLatencyKey = false),
        )

        assertFalse(plan.lowLatencyRequested)
    }

    @Test
    fun maxInputSizeIsOptional() {
        val plan = VideoDecoderFormatPlanner.build(
            config = config.copy(maxInputSizeBytes = null),
            lowLatencyFeatureSupported = false,
            support = VideoDecoderFormatSupport(supportsLowLatencyKey = false),
        )

        assertNull(plan.maxInputSizeBytes)
    }

    @Test
    fun csdValidationPreservesTypedErrors() {
        assertEquals(
            VideoDecoderError.MissingCodecSpecificData,
            VideoDecoderConfigValidator.validate(config.copy(codecSpecificData = emptyList())),
        )
        assertEquals(
            VideoDecoderError.MissingCodecSpecificData,
            VideoDecoderConfigValidator.validate(config.copy(codecSpecificData = listOf(byteArrayOf()))),
        )
        assertEquals(
            VideoDecoderError.InvalidConfiguration,
            VideoDecoderConfigValidator.validate(
                config.copy(
                    codecSpecificData = List(VideoDecoderConfig.MAX_CSD_ENTRIES + 1) {
                        byteArrayOf(it.toByte())
                    },
                ),
            ),
        )
    }
}
