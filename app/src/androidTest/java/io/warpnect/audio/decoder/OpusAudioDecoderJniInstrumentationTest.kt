package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import io.warpnect.platform.audio.decoder.NativeOpusAudioDecoderController
import io.warpnect.platform.audio.encoder.NativeOpusAudioEncoderController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusAudioDecoderJniInstrumentationTest {
    @Test
    fun nativeEncoderOutputDecodesToBorrowedPcmBuffer() {
        val pcmSink = RecordingPcmSink()
        val encodedSink = DecodingEncodedSink(pcmSink)
        val encoder = NativeOpusAudioEncoderController()
        val request = AudioEncoderRequest(
            source = AudioCaptureSource.MicrophoneAudio,
            sampleRateHz = 48_000,
            channelCount = 1,
            frameDurationUs = 5_000,
        )

        assertEquals(AudioEncoderError.None, encoder.prepare(request, encodedSink).error)
        assertEquals(AudioEncoderError.None, encoder.start().error)
        val pcm = ByteBuffer.allocateDirect(240 * 2).order(ByteOrder.nativeOrder())
        repeat(240) { pcm.putShort(0) }

        val error = encoder.submitPcm(
            buffer = pcm,
            offset = 0,
            sizeBytes = 240 * 2,
            frameCount = 240,
            firstFramePosition = 7,
            captureTimeNs = 99_000,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        )

        assertEquals(AudioEncoderError.None, error)
        assertEquals(1, pcmSink.frames)
        assertEquals(240, pcmSink.lastFrameCount)
        assertEquals(7L, pcmSink.lastFramePosition)
        assertTrue(pcmSink.lastBuffer?.isDirect == true)
        encoder.close()
        encodedSink.close()
    }

    @Test
    fun nonDirectEncodedInputIsRejectedWithoutFallbackCopy() {
        val decoder = NativeOpusAudioDecoderController()
        val sink = RecordingPcmSink()
        val config = AudioDecoderConfig(
            source = AudioCaptureSource.MicrophoneAudio,
            configGeneration = 1,
            sampleRateHz = 48_000,
            channelCount = 1,
            frameDurationUs = 5_000,
            lookaheadSamples = 120,
        )
        assertEquals(AudioDecoderError.None, decoder.prepare(config, sink).error)
        assertEquals(AudioDecoderError.None, decoder.start().error)

        val result = decoder.decode(
            buffer = ByteBuffer.allocate(8),
            offset = 0,
            sizeBytes = 8,
            metadata = EncodedAudioFrameMetadata(
                configGeneration = 1,
                firstFramePosition = 0,
                captureTimeUs = 0,
                timestampQuality = AudioTimestampQuality.Unavailable,
            ),
        )

        assertEquals(AudioDecoderError.NonDirectBuffer, result.error)
        decoder.close()
    }
}

private class DecodingEncodedSink(
    private val pcmSink: RecordingPcmSink,
) : EncodedAudioSink {
    private val decoder = NativeOpusAudioDecoderController()
    private var prepared = false

    override fun onOutputFormatChanged(format: EncodedAudioFormat) {
        val config = AudioDecoderConfig(
            source = format.source,
            configGeneration = 1,
            sampleRateHz = format.sampleRateHz,
            channelCount = format.channelCount,
            frameDurationUs = format.frameDurationUs,
            lookaheadSamples = format.lookaheadSamples,
        )
        assertEquals(AudioDecoderError.None, decoder.prepare(config, pcmSink).error)
        assertEquals(AudioDecoderError.None, decoder.start().error)
        prepared = true
    }

    override fun onEncodedFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        encodedFrameIndex: Long,
    ) {
        assertTrue(prepared)
        val result = decoder.decode(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            metadata = EncodedAudioFrameMetadata(
                configGeneration = 1,
                firstFramePosition = firstFramePosition,
                captureTimeUs = captureTimeNs / 1_000L,
                timestampQuality = timestampQuality,
            ),
        )
        assertEquals(AudioDecoderError.None, result.error)
    }

    fun close() {
        decoder.close()
    }
}

private class RecordingPcmSink : DecodedPcmAudioSink {
    var frames = 0
    var lastFrameCount = 0
    var lastFramePosition = 0L
    var lastBuffer: ByteBuffer? = null

    override fun onOutputFormatChanged(format: DecodedAudioFormat) = Unit

    override fun onPcmFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
        frameKind: DecodedAudioFrameKind,
    ) {
        frames += 1
        lastFrameCount = frameCount
        lastFramePosition = firstFramePosition
        lastBuffer = buffer
    }
}
