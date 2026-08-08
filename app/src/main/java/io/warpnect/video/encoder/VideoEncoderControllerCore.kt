package io.warpnect.video.encoder

internal class VideoEncoderControllerCore(
    private val clockUs: () -> Long,
) {
    private var currentSnapshot = VideoEncoderSnapshot()
    private var prepareRequestedAtUs: Long? = null
    private var lastPtsUs: Long? = null

    fun snapshot(): VideoEncoderSnapshot = currentSnapshot

    fun beginPrepare(request: VideoEncoderRequest): VideoEncoderError {
        val validation = VideoEncoderRequestValidator.validate(request)
        if (validation != VideoEncoderError.None) {
            fail(validation)
            return validation
        }
        if (currentSnapshot.state != VideoEncoderState.Stopped) {
            currentSnapshot = currentSnapshot.copy(lastError = VideoEncoderError.AlreadyPrepared)
            return VideoEncoderError.AlreadyPrepared
        }
        prepareRequestedAtUs = clockUs()
        currentSnapshot = VideoEncoderSnapshot(
            state = VideoEncoderState.Preparing,
            codec = request.codec,
            width = request.width,
            height = request.height,
            targetFrameRate = request.frameRate,
            targetBitrateBps = request.bitrateBps,
            bitrateMode = request.bitrateMode,
            requestedLatencyFrames = VideoEncoderFormatPlanner.LOW_LATENCY_FRAMES,
        )
        lastPtsUs = null
        return VideoEncoderError.None
    }

    fun completePrepare(
        error: VideoEncoderError,
        capabilities: VideoEncoderCapabilities?,
        outputFormat: VideoEncoderOutputFormat? = null,
    ) {
        currentSnapshot = if (error == VideoEncoderError.None) {
            val selected = capabilities?.selectedCodec
            currentSnapshot.copy(
                state = VideoEncoderState.Prepared,
                codecName = selected?.codecName,
                canonicalCodecName = selected?.canonicalName,
                hardwareAcceleration = selected?.hardwareAcceleration
                    ?: VideoEncoderHardwareAcceleration.Unknown,
                reportedLatencyFrames = outputFormat?.reportedLatencyFrames,
                outputReorderDepth = outputFormat?.outputReorderDepth,
                prepareLatencyUs = prepareRequestedAtUs?.let { clockUs() - it },
                lastError = VideoEncoderError.None,
            )
        } else {
            currentSnapshot.copy(
                state = VideoEncoderState.Error,
                prepareLatencyUs = prepareRequestedAtUs?.let { clockUs() - it },
                lastError = error,
            )
        }
    }

    fun beginStart(): VideoEncoderError = when (currentSnapshot.state) {
        VideoEncoderState.Running,
        VideoEncoderState.Starting,
        -> VideoEncoderError.AlreadyRunning
        VideoEncoderState.Prepared -> {
            currentSnapshot = currentSnapshot.copy(state = VideoEncoderState.Starting)
            VideoEncoderError.None
        }
        else -> VideoEncoderError.NotPrepared
    }

    fun completeStart(error: VideoEncoderError) {
        currentSnapshot = if (error == VideoEncoderError.None) {
            currentSnapshot.copy(state = VideoEncoderState.Running, lastError = VideoEncoderError.None)
        } else {
            currentSnapshot.copy(state = VideoEncoderState.Error, lastError = error)
        }
    }

    fun beginStop(): VideoEncoderError {
        currentSnapshot = when (currentSnapshot.state) {
            VideoEncoderState.Stopped -> currentSnapshot
            VideoEncoderState.Running -> currentSnapshot.copy(state = VideoEncoderState.Draining)
            VideoEncoderState.Prepared,
            VideoEncoderState.Error,
            VideoEncoderState.Preparing,
            VideoEncoderState.Starting,
            VideoEncoderState.Draining,
            VideoEncoderState.Stopping,
            -> currentSnapshot.copy(state = VideoEncoderState.Stopping)
        }
        return VideoEncoderError.None
    }

    fun completeStop(error: VideoEncoderError) {
        currentSnapshot = if (error == VideoEncoderError.None) {
            VideoEncoderSnapshot(state = VideoEncoderState.Stopped)
        } else {
            currentSnapshot.copy(state = VideoEncoderState.Error, lastError = error)
        }
        prepareRequestedAtUs = null
        lastPtsUs = null
    }

    fun recordOutputFormat(format: VideoEncoderOutputFormat): VideoEncoderError {
        val reorderError = if ((format.outputReorderDepth ?: 0) > 0) {
            VideoEncoderError.UnexpectedOutputReordering
        } else {
            VideoEncoderError.None
        }
        currentSnapshot = currentSnapshot.copy(
            outputFormatChanges = currentSnapshot.outputFormatChanges + 1,
            reportedLatencyFrames = format.reportedLatencyFrames,
            outputReorderDepth = format.outputReorderDepth,
            lastError = reorderError,
        )
        if (reorderError != VideoEncoderError.None) {
            fail(reorderError)
        }
        return reorderError
    }

    fun recordCodecConfig(size: Int) {
        currentSnapshot = currentSnapshot.copy(
            codecConfigBuffers = currentSnapshot.codecConfigBuffers + 1,
            encodedBytes = currentSnapshot.encodedBytes + size.coerceAtLeast(0),
        )
    }

    fun recordAccessUnit(size: Int, presentationTimeUs: Long, keyFrame: Boolean) {
        val previousPts = lastPtsUs
        if (previousPts != null && presentationTimeUs < previousPts) {
            fail(VideoEncoderError.UnexpectedOutputReordering)
            return
        }
        val firstAccessUnitAt = currentSnapshot.firstAccessUnitAtMonotonicUs ?: clockUs()
        currentSnapshot = currentSnapshot.copy(
            accessUnitsEncoded = currentSnapshot.accessUnitsEncoded + 1,
            encodedBytes = currentSnapshot.encodedBytes + size.coerceAtLeast(0),
            keyFramesEncoded = currentSnapshot.keyFramesEncoded + if (keyFrame) 1 else 0,
            lastPresentationTimeUs = presentationTimeUs,
            firstAccessUnitAtMonotonicUs = firstAccessUnitAt,
        )
        lastPtsUs = presentationTimeUs
    }

    fun canUpdateBitrate(bitrateBps: Int, minBitrateBps: Int?, maxBitrateBps: Int?): VideoEncoderError {
        if (currentSnapshot.state != VideoEncoderState.Running) {
            return VideoEncoderError.NotRunning
        }
        if (bitrateBps <= 0) {
            return VideoEncoderError.UnsupportedBitrate
        }
        if (minBitrateBps != null && bitrateBps < minBitrateBps) {
            return VideoEncoderError.UnsupportedBitrate
        }
        if (maxBitrateBps != null && bitrateBps > maxBitrateBps) {
            return VideoEncoderError.UnsupportedBitrate
        }
        return VideoEncoderError.None
    }

    fun recordBitrate(bitrateBps: Int) {
        currentSnapshot = currentSnapshot.copy(
            targetBitrateBps = bitrateBps,
            lastError = VideoEncoderError.None,
        )
    }

    fun canRequestKeyFrame(): VideoEncoderError = if (currentSnapshot.state == VideoEncoderState.Running) {
        VideoEncoderError.None
    } else {
        VideoEncoderError.NotRunning
    }

    fun fail(
        error: VideoEncoderError,
        diagnosticInfo: String? = null,
        recoverable: Boolean? = null,
        transient: Boolean? = null,
    ) {
        currentSnapshot = currentSnapshot.copy(
            state = if (currentSnapshot.state == VideoEncoderState.Stopped) {
                VideoEncoderState.Stopped
            } else {
                VideoEncoderState.Error
            },
            runtimeErrors = currentSnapshot.runtimeErrors + if (error == VideoEncoderError.None) 0 else 1,
            lastCodecDiagnosticInfo = diagnosticInfo,
            lastCodecErrorRecoverable = recoverable,
            lastCodecErrorTransient = transient,
            lastError = error,
        )
    }
}
