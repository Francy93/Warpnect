package io.warpnect.platform.audio.encoder

import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.audio.encoder.AudioEncoderError
import java.nio.ByteBuffer

class OpusPcmAudioSink(
    private val encoder: NativeOpusAudioEncoderController,
) : PcmAudioSink {
    override fun onFormatChanged(format: AudioCaptureFormat) {
        val snapshot = encoder.snapshot()
        val error = when {
            format.encoding != AudioPcmEncoding.Pcm16 -> AudioEncoderError.InvalidPcmRange
            snapshot.sampleRateHz != 0 && format.sampleRateHz != snapshot.sampleRateHz ->
                AudioEncoderError.UnsupportedSampleRate
            snapshot.channelCount != 0 && format.channelCount != snapshot.channelCount ->
                AudioEncoderError.UnsupportedChannelCount
            else -> AudioEncoderError.None
        }
        if (error != AudioEncoderError.None) {
            encoder.reportInputError(error)
        }
    }

    override fun onPcmChunk(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ) {
        encoder.submitPcm(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            frameCount = frameCount,
            firstFramePosition = firstFramePosition,
            captureTimeNs = captureTimeNs,
            timestampQuality = timestampQuality,
        )
    }

    override fun onCaptureError(error: AudioCaptureError) {
        if (error != AudioCaptureError.None) {
            encoder.snapshot()
        }
    }
}
