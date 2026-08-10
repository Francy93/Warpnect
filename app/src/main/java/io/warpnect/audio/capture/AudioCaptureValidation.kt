package io.warpnect.audio.capture

object AudioCaptureValidation {
    fun validate(request: AudioCaptureRequest): AudioCaptureError {
        val chunkDurationRange = LongRange(
            AudioCaptureRequest.MIN_TARGET_CHUNK_DURATION_US,
            AudioCaptureRequest.MAX_TARGET_CHUNK_DURATION_US,
        )
        val sharedRingSlotRange = IntRange(
            AudioCaptureRequest.MIN_SHARED_RING_SLOT_COUNT,
            AudioCaptureRequest.MAX_SHARED_RING_SLOT_COUNT,
        )
        if (request.preferredSampleRateHz != null && request.preferredSampleRateHz <= 0) {
            return AudioCaptureError.UnsupportedFormat
        }
        if (request.channelCount != null && request.channelCount <= 0) {
            return AudioCaptureError.UnsupportedFormat
        }
        if (request.targetChunkDurationUs !in chunkDurationRange) {
            return AudioCaptureError.InvalidChunkDuration
        }
        if (request.sharedRingSlotCount !in sharedRingSlotRange) {
            return AudioCaptureError.InvalidRequest
        }
        if (request.targetUid != null && request.targetUid < 0) {
            return AudioCaptureError.InvalidRequest
        }
        return AudioCaptureError.None
    }

    fun defaultChannelCount(source: AudioCaptureSource): Int = when (source) {
        AudioCaptureSource.SystemAudio -> 2
        AudioCaptureSource.MicrophoneAudio -> 1
    }

    fun buildFormat(
        request: AudioCaptureRequest,
        actualSampleRateHz: Int,
        actualChannelCount: Int,
    ): AudioCaptureFormat? {
        if (actualSampleRateHz <= 0 || actualChannelCount <= 0) {
            return null
        }
        val bytesPerFrame = actualChannelCount * request.encoding.bytesPerSample
        val frames = AudioChunkPlanner.targetFramesPerChunk(
            actualSampleRateHz,
            request.targetChunkDurationUs,
        )
        if (frames <= 0 || AudioChunkPlanner.chunkBytes(frames, bytesPerFrame) <= 0) {
            return null
        }
        return AudioCaptureFormat(
            source = request.source,
            sampleRateHz = actualSampleRateHz,
            channelCount = actualChannelCount,
            encoding = request.encoding,
            bytesPerFrame = bytesPerFrame,
            targetFramesPerChunk = frames,
            targetChunkDurationUs = request.targetChunkDurationUs,
        )
    }
}
