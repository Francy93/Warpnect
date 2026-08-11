package io.warpnect.platform.audio.playback

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.DecodedAudioFormat
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.decoder.DecodedPcmAudioSink
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.DecodedPcmMetadata
import java.nio.ByteBuffer

class OboeDecodedPcmAudioSink(
    private val playbackController: AudioPlaybackController,
) : DecodedPcmAudioSink {
    private var configGeneration: Long = 0

    override fun onOutputFormatChanged(format: DecodedAudioFormat) {
        configGeneration = format.configGeneration
    }

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
        playbackController.submitPcm(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            frameCount = frameCount,
            metadata = DecodedPcmMetadata(
                configGeneration = configGeneration,
                firstFramePosition = firstFramePosition,
                captureTimeUs = captureTimeUs,
                timestampQuality = timestampQuality,
                discontinuityBefore = discontinuityBefore,
                frameKind = frameKind,
            ),
        )
    }

    override fun onDecoderError(error: AudioDecoderError) = Unit
}
