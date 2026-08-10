package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.platform.audio.encoder.NativeOpusAudioEncoderController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusAudioEncoderJniInstrumentationTest {
    @Test
    fun directPcmProducesBorrowedOpusPacket() {
        val controller = NativeOpusAudioEncoderController()
        val sink = RecordingSink()
        val request = AudioEncoderRequest(
            source = AudioCaptureSource.MicrophoneAudio,
            sampleRateHz = 48_000,
            channelCount = 1,
            frameDurationUs = 5_000,
        )

        assertEquals(AudioEncoderError.None, controller.prepare(request, sink).error)
        assertEquals(AudioEncoderError.None, controller.start().error)
        val pcm = ByteBuffer.allocateDirect(240 * 2).order(ByteOrder.nativeOrder())
        repeat(240) { pcm.putShort(0) }

        val error = controller.submitPcm(
            buffer = pcm,
            offset = 0,
            sizeBytes = 240 * 2,
            frameCount = 240,
            firstFramePosition = 7,
            captureTimeNs = 99,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        )

        assertEquals(AudioEncoderError.None, error)
        assertEquals(1, sink.frames.size)
        assertEquals(7L, sink.frames.single().firstFramePosition)
        assertTrue(sink.frames.single().sizeBytes > 0)
        assertTrue(sink.frames.single().buffer.isDirect)
        controller.close()
    }

    @Test
    fun nonDirectPcmIsRejectedBeforeJni() {
        val controller = NativeOpusAudioEncoderController()
        val sink = RecordingSink()
        val request = AudioEncoderRequest(source = AudioCaptureSource.MicrophoneAudio, channelCount = 1)
        controller.prepare(request, sink)
        controller.start()

        val error = controller.submitPcm(
            buffer = ByteBuffer.allocate(240 * 2),
            offset = 0,
            sizeBytes = 240 * 2,
            frameCount = 240,
            firstFramePosition = 0,
            captureTimeNs = 0,
            timestampQuality = AudioTimestampQuality.Unavailable,
        )

        assertEquals(AudioEncoderError.NonDirectPcmBuffer, error)
        assertEquals(0, sink.frames.size)
        controller.close()
    }
}

private class RecordingSink : EncodedAudioSink {
    val frames = mutableListOf<Frame>()

    override fun onOutputFormatChanged(format: EncodedAudioFormat) = Unit

    override fun onEncodedFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        encodedFrameIndex: Long,
    ) {
        frames += Frame(buffer, sizeBytes, firstFramePosition)
    }
}

private data class Frame(
    val buffer: ByteBuffer,
    val sizeBytes: Int,
    val firstFramePosition: Long,
)
