package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioCaptureSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioEncoderValidationTest {
    @Test
    fun supportedFrameDurationsCalculateExactSamples() {
        assertEquals(120, AudioEncoderValidation.samplesPerFrame(48_000, 2_500))
        assertEquals(240, AudioEncoderValidation.samplesPerFrame(48_000, 5_000))
        assertEquals(480, AudioEncoderValidation.samplesPerFrame(48_000, 10_000))
        assertEquals(960, AudioEncoderValidation.samplesPerFrame(48_000, 20_000))
    }

    @Test
    fun unsupportedSampleRateIsRejectedWithoutResampling() {
        val request = AudioEncoderRequest(
            source = AudioCaptureSource.MicrophoneAudio,
            sampleRateHz = 44_100,
            channelCount = 1,
        )

        assertEquals(AudioEncoderError.UnsupportedSampleRate, AudioEncoderValidation.validate(request))
    }

    @Test
    fun monoAndStereoAreSupportedButThreeChannelsAreRejected() {
        assertEquals(
            AudioEncoderError.None,
            AudioEncoderValidation.validate(
                AudioEncoderRequest(source = AudioCaptureSource.MicrophoneAudio, channelCount = 1),
            ),
        )
        assertEquals(
            AudioEncoderError.None,
            AudioEncoderValidation.validate(
                AudioEncoderRequest(source = AudioCaptureSource.SystemAudio, channelCount = 2),
            ),
        )
        assertEquals(
            AudioEncoderError.UnsupportedChannelCount,
            AudioEncoderValidation.validate(
                AudioEncoderRequest(source = AudioCaptureSource.SystemAudio, channelCount = 3),
            ),
        )
    }

    @Test
    fun bitrateAndComplexityAreValidated() {
        assertEquals(
            AudioEncoderError.InvalidBitrate,
            AudioEncoderValidation.validate(
                AudioEncoderRequest(
                    source = AudioCaptureSource.MicrophoneAudio,
                    channelCount = 1,
                    bitrateBps = 100,
                ),
            ),
        )
        assertEquals(
            AudioEncoderError.InvalidComplexity,
            AudioEncoderValidation.validate(
                AudioEncoderRequest(
                    source = AudioCaptureSource.MicrophoneAudio,
                    channelCount = 1,
                    complexity = 11,
                ),
            ),
        )
    }
}
