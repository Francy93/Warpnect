package io.warpnect.video.decoder

internal class VideoDecoderControllerCore(
    private val clockUs: () -> Long,
) {
    private var currentSnapshot = VideoDecoderSnapshot()
    private var prepareRequestedAtUs: Long? = null

    fun snapshot(): VideoDecoderSnapshot = currentSnapshot

    fun beginPrepare(config: VideoDecoderConfig): VideoDecoderError {
        val validation = VideoDecoderConfigValidator.validate(config)
        if (validation != VideoDecoderError.None) {
            fail(validation)
            return validation
        }
        if (currentSnapshot.state != VideoDecoderState.Stopped) {
            currentSnapshot = currentSnapshot.copy(lastError = VideoDecoderError.AlreadyPrepared)
            return VideoDecoderError.AlreadyPrepared
        }
        prepareRequestedAtUs = clockUs()
        currentSnapshot = VideoDecoderSnapshot(
            state = VideoDecoderState.Preparing,
            codec = config.codec,
            width = config.width,
            height = config.height,
            activeConfigGeneration = config.configGeneration,
        )
        return VideoDecoderError.None
    }

    fun completePrepare(
        error: VideoDecoderError,
        capabilities: VideoDecoderCapabilities?,
        lowLatencyRequested: Boolean = false,
    ) {
        currentSnapshot = if (error == VideoDecoderError.None) {
            val selected = capabilities?.selectedCodec
            currentSnapshot.copy(
                state = VideoDecoderState.Prepared,
                codecName = selected?.codecName,
                canonicalCodecName = selected?.canonicalName,
                hardwareAcceleration = selected?.hardwareAcceleration
                    ?: VideoDecoderHardwareAcceleration.Unknown,
                lowLatencyFeatureSupported = selected?.lowLatencyFeatureSupported,
                lowLatencyRequested = lowLatencyRequested,
                prepareLatencyUs = prepareRequestedAtUs?.let { clockUs() - it },
                lastError = VideoDecoderError.None,
            )
        } else {
            currentSnapshot.copy(
                state = VideoDecoderState.Error,
                prepareLatencyUs = prepareRequestedAtUs?.let { clockUs() - it },
                lastError = error,
            )
        }
    }

    fun beginStart(): VideoDecoderError = when (currentSnapshot.state) {
        VideoDecoderState.Running,
        VideoDecoderState.Starting,
        -> VideoDecoderError.AlreadyRunning
        VideoDecoderState.Prepared -> {
            currentSnapshot = currentSnapshot.copy(state = VideoDecoderState.Starting)
            VideoDecoderError.None
        }
        else -> VideoDecoderError.NotPrepared
    }

    fun completeStart(error: VideoDecoderError) {
        currentSnapshot = if (error == VideoDecoderError.None) {
            currentSnapshot.copy(state = VideoDecoderState.Running, lastError = VideoDecoderError.None)
        } else {
            currentSnapshot.copy(state = VideoDecoderState.Error, lastError = error)
        }
    }

    fun beginDrain(): VideoDecoderError = when (currentSnapshot.state) {
        VideoDecoderState.Running -> {
            currentSnapshot = currentSnapshot.copy(state = VideoDecoderState.Draining)
            VideoDecoderError.None
        }
        VideoDecoderState.Draining -> VideoDecoderError.None
        else -> VideoDecoderError.NotRunning
    }

    fun beginStop(): VideoDecoderError {
        currentSnapshot = when (currentSnapshot.state) {
            VideoDecoderState.Stopped -> currentSnapshot
            VideoDecoderState.Running,
            VideoDecoderState.Draining,
            -> currentSnapshot.copy(state = VideoDecoderState.Draining)
            VideoDecoderState.Prepared,
            VideoDecoderState.Error,
            VideoDecoderState.Preparing,
            VideoDecoderState.Starting,
            VideoDecoderState.Stopping,
            -> currentSnapshot.copy(state = VideoDecoderState.Stopping)
        }
        return VideoDecoderError.None
    }

    fun completeStop(error: VideoDecoderError) {
        currentSnapshot = if (error == VideoDecoderError.None) {
            VideoDecoderSnapshot(state = VideoDecoderState.Stopped)
        } else {
            currentSnapshot.copy(state = VideoDecoderState.Error, lastError = error)
        }
        prepareRequestedAtUs = null
    }

    fun validateAccessUnit(result: VideoDecoderInputResult.AccessUnit, capacity: Int): VideoDecoderError = when {
        result.size <= 0 -> VideoDecoderError.InvalidInputResult
        result.size > capacity -> VideoDecoderError.InputBufferTooSmall
        result.presentationTimeUs < 0L -> VideoDecoderError.InvalidInputResult
        result.configGeneration <= 0L -> VideoDecoderError.InvalidConfigGeneration
        result.configGeneration != currentSnapshot.activeConfigGeneration -> VideoDecoderError.ReconfigurationRequired
        result.frameId !in 0L..MAX_U32 -> VideoDecoderError.InvalidInputResult
        else -> VideoDecoderError.None
    }

    fun recordNoData() {
        currentSnapshot = currentSnapshot.copy(
            inputBackpressureEvents = currentSnapshot.inputBackpressureEvents + 1,
        )
    }

    fun recordInputQueued(size: Int, presentationTimeUs: Long) {
        currentSnapshot = currentSnapshot.copy(
            accessUnitsQueued = currentSnapshot.accessUnitsQueued + 1,
            encodedBytesQueued = currentSnapshot.encodedBytesQueued + size.coerceAtLeast(0),
            lastInputPtsUs = presentationTimeUs,
        )
    }

    fun recordInputBufferTooSmall() {
        currentSnapshot = currentSnapshot.copy(
            inputBufferTooSmall = currentSnapshot.inputBufferTooSmall + 1,
        )
    }

    fun recordOutput(action: DecodedVideoOutputAction, presentationTimeUs: Long) {
        val renderRequested = action == DecodedVideoOutputAction.RenderNow ||
            action is DecodedVideoOutputAction.RenderAt
        currentSnapshot = currentSnapshot.copy(
            decodedOutputBuffers = currentSnapshot.decodedOutputBuffers + 1,
            framesRenderedRequested = currentSnapshot.framesRenderedRequested +
                if (renderRequested) 1 else 0,
            framesDropped = currentSnapshot.framesDropped +
                if (action == DecodedVideoOutputAction.Drop) 1 else 0,
            framesRenderScheduled = currentSnapshot.framesRenderScheduled +
                if (action is DecodedVideoOutputAction.RenderAt) 1 else 0,
            lastOutputPtsUs = presentationTimeUs,
        )
    }

    fun recordOutputFormat() {
        currentSnapshot = currentSnapshot.copy(
            outputFormatChanges = currentSnapshot.outputFormatChanges + 1,
        )
    }

    fun recordFrameRendered(event: VideoDecoderFrameRenderedEvent) {
        currentSnapshot = currentSnapshot.copy(
            frameRenderedCallbacks = currentSnapshot.frameRenderedCallbacks + 1,
            lastRenderedPtsUs = event.presentationTimeUs,
            lastRenderedNanoTime = event.nanoTime,
        )
    }

    fun fail(
        error: VideoDecoderError,
        diagnosticInfo: String? = null,
        recoverable: Boolean? = null,
        transient: Boolean? = null,
    ) {
        if (error == VideoDecoderError.InputBufferTooSmall) {
            recordInputBufferTooSmall()
        }
        currentSnapshot = currentSnapshot.copy(
            state = if (currentSnapshot.state == VideoDecoderState.Stopped) {
                VideoDecoderState.Stopped
            } else {
                VideoDecoderState.Error
            },
            codecErrors = currentSnapshot.codecErrors + if (error == VideoDecoderError.None) 0 else 1,
            lastCodecDiagnosticInfo = diagnosticInfo,
            lastCodecErrorRecoverable = recoverable,
            lastCodecErrorTransient = transient,
            lastError = error,
        )
    }

    private companion object {
        const val MAX_U32 = 0xFFFF_FFFFL
    }
}
