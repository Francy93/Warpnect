package io.warpnect.platform.video.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactFormatEncoderProbeRunnerTest {
    @Test
    fun successfulProbeStopsAndReleasesEveryResource() {
        val codec = RecordingCodec()

        val result = ExactFormatEncoderProbeRunner.run { codec }

        assertEquals(ExactVideoEncoderCapabilityProbeResult.Supported, result)
        assertTrue(codec.stopped)
        assertTrue(codec.surfaceReleased)
        assertTrue(codec.released)
    }

    @Test
    fun configureFailureReleasesCodecWithoutStartingIt() {
        val codec = RecordingCodec(failAt = Stage.Configure)

        val result = ExactFormatEncoderProbeRunner.run { codec }

        assertEquals(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed, result)
        assertFalse(codec.stopped)
        assertFalse(codec.surfaceReleased)
        assertTrue(codec.released)
    }

    @Test
    fun inputSurfaceFailureReleasesCodecWithoutStartingIt() {
        val codec = RecordingCodec(failAt = Stage.InputSurface)

        val result = ExactFormatEncoderProbeRunner.run { codec }

        assertEquals(ExactVideoEncoderCapabilityProbeResult.InputSurfaceFailed, result)
        assertFalse(codec.stopped)
        assertFalse(codec.surfaceReleased)
        assertTrue(codec.released)
    }

    @Test
    fun startFailureReleasesInputSurfaceAndCodec() {
        val codec = RecordingCodec(failAt = Stage.Start)

        val result = ExactFormatEncoderProbeRunner.run { codec }

        assertEquals(ExactVideoEncoderCapabilityProbeResult.StartFailed, result)
        assertFalse(codec.stopped)
        assertTrue(codec.surfaceReleased)
        assertTrue(codec.released)
    }

    @Test
    fun codecCreationFailureDoesNotLeakAnUnrelatedException() {
        val result = ExactFormatEncoderProbeRunner.run(
            ExactFormatEncoderProbeCodecFactory { throw IllegalStateException("test") },
        )

        assertEquals(ExactVideoEncoderCapabilityProbeResult.CodecCreationFailed, result)
    }

    private enum class Stage {
        Configure,
        InputSurface,
        Start,
    }

    private class RecordingCodec(
        private val failAt: Stage? = null,
    ) : ExactFormatEncoderProbeCodec {
        var stopped = false
        var surfaceReleased = false
        var released = false

        override fun configure() {
            failIf(Stage.Configure)
        }

        override fun createInputSurface() {
            failIf(Stage.InputSurface)
        }

        override fun start() {
            failIf(Stage.Start)
        }

        override fun stop() {
            stopped = true
        }

        override fun releaseInputSurface() {
            surfaceReleased = true
        }

        override fun release() {
            released = true
        }

        private fun failIf(stage: Stage) {
            if (failAt == stage) throw IllegalStateException(stage.name)
        }
    }
}
