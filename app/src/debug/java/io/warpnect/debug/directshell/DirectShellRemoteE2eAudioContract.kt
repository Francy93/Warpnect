package io.warpnect.debug.directshell

import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioPcmEncoding

/** Exact local contract shared by the DEBUG DirectShell producer and the existing Opus source. */
internal object DirectShellRemoteE2eAudioContract {
    const val MIN_API = 31
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val BYTES_PER_FRAME = CHANNEL_COUNT * Short.SIZE_BYTES
    const val TARGET_CHUNK_FRAMES = 240
    const val TARGET_CHUNK_DURATION_US = 5_000L
    const val PAYLOAD_CAPACITY_BYTES = TARGET_CHUNK_FRAMES * BYTES_PER_FRAME
    const val RING_SLOT_COUNT = 8

    fun validate(request: AudioCaptureRequest, apiLevel: Int): AudioCaptureError = when {
        apiLevel < MIN_API -> AudioCaptureError.UnsupportedPlatform
        request.source != AudioCaptureSource.SystemAudio -> AudioCaptureError.InvalidRequest
        request.encoding != AudioPcmEncoding.Pcm16 -> AudioCaptureError.UnsupportedFormat
        request.preferredSampleRateHz != null && request.preferredSampleRateHz != SAMPLE_RATE_HZ ->
            AudioCaptureError.UnsupportedFormat
        request.channelCount != null && request.channelCount != CHANNEL_COUNT -> AudioCaptureError.UnsupportedFormat
        request.targetChunkDurationUs != TARGET_CHUNK_DURATION_US -> AudioCaptureError.InvalidChunkDuration
        request.sharedRingSlotCount != RING_SLOT_COUNT -> AudioCaptureError.InvalidRequest
        else -> AudioCaptureError.None
    }

    fun format() = AudioCaptureFormat(
        source = AudioCaptureSource.SystemAudio,
        sampleRateHz = SAMPLE_RATE_HZ,
        channelCount = CHANNEL_COUNT,
        encoding = AudioPcmEncoding.Pcm16,
        bytesPerFrame = BYTES_PER_FRAME,
        targetFramesPerChunk = TARGET_CHUNK_FRAMES,
        targetChunkDurationUs = TARGET_CHUNK_DURATION_US,
    )
}
