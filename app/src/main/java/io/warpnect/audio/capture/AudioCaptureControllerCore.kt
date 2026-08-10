package io.warpnect.audio.capture

internal class AudioCaptureControllerCore {
    private var currentSnapshot = AudioCaptureSnapshot()

    fun snapshot(): AudioCaptureSnapshot = currentSnapshot

    fun beginPrepare(request: AudioCaptureRequest): AudioCaptureError {
        val validation = AudioCaptureValidation.validate(request)
        if (validation != AudioCaptureError.None) {
            fail(validation)
            return validation
        }
        if (currentSnapshot.state != AudioCaptureState.Stopped) {
            currentSnapshot = currentSnapshot.copy(lastError = AudioCaptureError.AlreadyPrepared)
            return AudioCaptureError.AlreadyPrepared
        }
        currentSnapshot = AudioCaptureSnapshot(
            state = AudioCaptureState.Preparing,
            source = request.source,
            lastError = AudioCaptureError.None,
        )
        return AudioCaptureError.None
    }

    fun completePrepare(
        error: AudioCaptureError,
        format: AudioCaptureFormat?,
        actualBufferFrames: Int = 0,
        ringCapacity: Int = 0,
    ) {
        currentSnapshot = if (error == AudioCaptureError.None && format != null) {
            currentSnapshot.copy(
                state = AudioCaptureState.Prepared,
                source = format.source,
                sampleRateHz = format.sampleRateHz,
                channelCount = format.channelCount,
                bytesPerFrame = format.bytesPerFrame,
                targetChunkFrames = format.targetFramesPerChunk,
                actualAudioRecordBufferFrames = actualBufferFrames,
                ringCapacity = ringCapacity,
                lastError = AudioCaptureError.None,
            )
        } else {
            currentSnapshot.copy(
                state = AudioCaptureState.Error,
                lastError = error,
            )
        }
    }

    fun beginStart(): AudioCaptureError = when (currentSnapshot.state) {
        AudioCaptureState.Running,
        AudioCaptureState.Starting,
        -> AudioCaptureError.AlreadyRunning
        AudioCaptureState.Prepared -> {
            currentSnapshot = currentSnapshot.copy(state = AudioCaptureState.Starting)
            AudioCaptureError.None
        }
        else -> AudioCaptureError.NotPrepared
    }

    fun completeStart(error: AudioCaptureError) {
        currentSnapshot = if (error == AudioCaptureError.None) {
            currentSnapshot.copy(
                state = AudioCaptureState.Running,
                lastError = AudioCaptureError.None,
            )
        } else {
            currentSnapshot.copy(
                state = AudioCaptureState.Error,
                lastError = error,
            )
        }
    }

    fun beginStop(): AudioCaptureError {
        currentSnapshot = when (currentSnapshot.state) {
            AudioCaptureState.Stopped -> currentSnapshot
            AudioCaptureState.Closed -> return AudioCaptureError.Closed
            else -> currentSnapshot.copy(state = AudioCaptureState.Stopping)
        }
        return AudioCaptureError.None
    }

    fun completeStop(error: AudioCaptureError): AudioCaptureResult {
        currentSnapshot = if (error == AudioCaptureError.None || error == AudioCaptureError.NotRunning) {
            AudioCaptureSnapshot(state = AudioCaptureState.Stopped)
        } else {
            currentSnapshot.copy(
                state = AudioCaptureState.Error,
                lastError = error,
            )
        }
        return AudioCaptureResult(
            error = if (error == AudioCaptureError.NotRunning) AudioCaptureError.None else error,
            snapshot = currentSnapshot,
        )
    }

    fun recordChunk(
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ) {
        currentSnapshot = currentSnapshot.copy(
            chunksCaptured = currentSnapshot.chunksCaptured + 1L,
            framesCaptured = currentSnapshot.framesCaptured + frameCount.coerceAtLeast(0).toLong(),
            bytesCaptured = currentSnapshot.bytesCaptured + sizeBytes.coerceAtLeast(0).toLong(),
            timestampSuccesses = currentSnapshot.timestampSuccesses +
                if (timestampQuality == AudioTimestampQuality.AudioRecordTimestamp) 1L else 0L,
            timestampFallbacks = currentSnapshot.timestampFallbacks +
                if (timestampQuality != AudioTimestampQuality.AudioRecordTimestamp) 1L else 0L,
            lastFramePosition = firstFramePosition,
            lastCaptureTimeNs = captureTimeNs,
        )
    }

    fun recordRingState(occupancy: Int, highWaterMark: Int) {
        currentSnapshot = currentSnapshot.copy(
            ringOccupancy = occupancy.coerceAtLeast(0),
            ringHighWaterMark = maxOf(currentSnapshot.ringHighWaterMark, highWaterMark),
        )
    }

    fun recordDroppedChunk(frameCount: Int) {
        currentSnapshot = currentSnapshot.copy(
            ringOverruns = currentSnapshot.ringOverruns + 1L,
            chunksDropped = currentSnapshot.chunksDropped + 1L,
            framesDropped = currentSnapshot.framesDropped + frameCount.coerceAtLeast(0).toLong(),
        )
    }

    fun recordSinkFailure() {
        currentSnapshot = currentSnapshot.copy(
            sinkFailures = currentSnapshot.sinkFailures + 1L,
            lastError = AudioCaptureError.SinkFailure,
        )
    }

    fun fail(error: AudioCaptureError) {
        currentSnapshot = currentSnapshot.copy(
            state = if (currentSnapshot.state == AudioCaptureState.Stopped) {
                AudioCaptureState.Stopped
            } else {
                AudioCaptureState.Error
            },
            lastError = error,
        )
    }

    fun close() {
        currentSnapshot = AudioCaptureSnapshot(state = AudioCaptureState.Closed)
    }
}
