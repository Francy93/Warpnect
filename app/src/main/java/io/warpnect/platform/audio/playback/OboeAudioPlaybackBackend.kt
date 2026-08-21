package io.warpnect.platform.audio.playback

import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.playback.AudioPlaybackApi
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackPcmFormat
import io.warpnect.audio.playback.AudioPlaybackPerformanceMode
import io.warpnect.audio.playback.AudioPlaybackSharingMode
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPlaybackState
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import java.nio.ByteBuffer

internal interface OboeAudioPlaybackBackend {
    fun create(config: AudioPlaybackConfig): OboePlaybackCreateResult

    fun submitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackError

    fun start(handle: Long): AudioPlaybackError

    fun stop(handle: Long): AudioPlaybackError

    fun queryPresentationTimestamp(handle: Long): AudioPresentationTimestampResult

    fun querySourcePresentationAnchor(handle: Long): AudioSourcePresentationAnchorResult

    fun snapshot(handle: Long, state: AudioPlaybackState): AudioPlaybackSnapshot

    fun attachTelemetry(handle: Long, sourceId: Long): AudioPlaybackError = AudioPlaybackError.None

    fun destroy(handle: Long): AudioPlaybackError
}

internal data class OboePlaybackCreateResult(
    val error: AudioPlaybackError,
    val handle: Long = 0L,
    val snapshot: AudioPlaybackSnapshot = AudioPlaybackSnapshot(),
)

internal object NativeOboeAudioPlaybackBackend : OboeAudioPlaybackBackend {
    override fun create(config: AudioPlaybackConfig): OboePlaybackCreateResult {
        val values = NativeBridge.audioPlaybackCreate(
            source = config.source.ordinal,
            configGeneration = config.configGeneration,
            sampleRateHz = config.sampleRateHz,
            channelCount = config.channelCount,
            frameDurationUs = config.frameDurationUs,
            framesPerCodecFrame = config.framesPerCodecFrame,
            lookaheadSamples = config.lookaheadSamples,
            ringCapacityCodecFrames = config.ringCapacityCodecFrames,
            startThresholdCodecFrames = config.startThresholdCodecFrames,
            sharingPolicy = config.sharingPolicy.ordinal,
            requestedBufferBursts = config.requestedBufferBursts,
            requireLowLatencyPerformanceMode = config.requireLowLatencyPerformanceMode,
        )
        val handle = values.getOrElse(0) { 0L }
        val error = AudioPlaybackError.fromNativeCode(values.getOrElse(1) { 6L }.toInt())
        if (error != AudioPlaybackError.None || handle == 0L) {
            return OboePlaybackCreateResult(error = error)
        }
        return OboePlaybackCreateResult(
            error = AudioPlaybackError.None,
            handle = handle,
            snapshot = snapshot(handle, AudioPlaybackState.Prepared),
        )
    }

    override fun submitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackError = AudioPlaybackError.fromNativeCode(
        NativeBridge.audioPlaybackSubmitPcm(
            handle = handle,
            buffer = buffer,
            offset = offset,
            size = sizeBytes,
            frameCount = frameCount,
            configGeneration = metadata.configGeneration,
            firstFramePosition = metadata.firstFramePosition,
            captureTimeUs = metadata.captureTimeUs,
            timestampQuality = metadata.timestampQuality.ordinal,
            discontinuityBefore = metadata.discontinuityBefore,
            frameKind = metadata.frameKind.ordinal,
        ),
    )

    override fun start(handle: Long): AudioPlaybackError =
        AudioPlaybackError.fromNativeCode(NativeBridge.audioPlaybackStart(handle))

    override fun stop(handle: Long): AudioPlaybackError =
        AudioPlaybackError.fromNativeCode(NativeBridge.audioPlaybackStop(handle))

    override fun queryPresentationTimestamp(handle: Long): AudioPresentationTimestampResult =
        presentationTimestampFrom(NativeBridge.audioPlaybackPresentationTimestamp(handle))

    override fun querySourcePresentationAnchor(handle: Long): AudioSourcePresentationAnchorResult =
        sourcePresentationAnchorFrom(NativeBridge.audioPlaybackSourcePresentationAnchor(handle))

    override fun snapshot(handle: Long, state: AudioPlaybackState): AudioPlaybackSnapshot =
        audioPlaybackSnapshotFrom(NativeBridge.audioPlaybackSnapshot(handle), state)

    override fun attachTelemetry(handle: Long, sourceId: Long): AudioPlaybackError =
        AudioPlaybackError.fromNativeCode(NativeBridge.audioPlaybackAttachTelemetry(handle, sourceId))

    override fun destroy(handle: Long): AudioPlaybackError =
        AudioPlaybackError.fromNativeCode(NativeBridge.audioPlaybackDestroy(handle))
}

internal fun presentationTimestampFrom(values: LongArray): AudioPresentationTimestampResult =
    AudioPresentationTimestampResult(
        error = AudioPlaybackError.fromNativeCode(values.getOrElse(0) { 21L }.toInt()),
        timestampValid = values.getOrElse(1) { 0L } != 0L,
        streamFramePosition = values.getOrElse(2) { 0L },
        presentationTimeNs = values.getOrElse(3) { 0L },
        latencyUs = values.getOrElse(4) { 0L },
    )

internal fun sourcePresentationAnchorFrom(values: LongArray): AudioSourcePresentationAnchorResult =
    AudioSourcePresentationAnchorResult(
        error = AudioPlaybackError.fromNativeCode(values.getOrElse(0) { 21L }.toInt()),
        valid = values.getOrElse(1) { 0L } != 0L,
        sourceContentTimeUs = values.getOrElse(2) { 0L },
        sourceCaptureTimeUs = values.getOrElse(3) { 0L },
        sourceFramePosition = values.getOrElse(4) { 0L },
        outputFramePosition = values.getOrElse(5) { 0L },
        localPresentationTimeNs = values.getOrElse(6) { 0L },
        oboeFramePosition = values.getOrElse(7) { 0L },
        oboePresentationTimeNs = values.getOrElse(8) { 0L },
        ageNs = values.getOrElse(9) { 0L },
        configGeneration = values.getOrElse(10) { 0L },
        sampleRateHz = values.getOrElse(11) { 0L }.toInt(),
        lookaheadSamples = values.getOrElse(12) { 0L }.toInt(),
        timestampQuality = enumAt(values, 13, io.warpnect.audio.capture.AudioTimestampQuality.Unavailable),
        discontinuityBefore = values.getOrElse(14) { 0L } != 0L,
        frameKind = enumAt(values, 15, io.warpnect.audio.decoder.DecodedAudioFrameKind.Normal),
        latencyUs = values.getOrElse(16) { 0L },
    )

internal fun audioPlaybackSnapshotFrom(values: LongArray, state: AudioPlaybackState): AudioPlaybackSnapshot {
    val source = AudioCaptureSource.entries.getOrElse(values.getOrElse(0) { 1L }.toInt()) {
        AudioCaptureSource.MicrophoneAudio
    }
    return AudioPlaybackSnapshot(
        state = state,
        source = source,
        configGeneration = values.getOrElse(1) { 0L },
        requestedSampleRateHz = values.getOrElse(2) { 0L }.toInt(),
        actualSampleRateHz = values.getOrElse(3) { 0L }.toInt(),
        requestedChannelCount = values.getOrElse(4) { 0L }.toInt(),
        actualChannelCount = values.getOrElse(5) { 0L }.toInt(),
        frameDurationUs = values.getOrElse(6) { 0L }.toInt(),
        framesPerCodecFrame = values.getOrElse(7) { 0L }.toInt(),
        requestedPerformanceMode = enumAt(values, 8, AudioPlaybackPerformanceMode.Unknown),
        actualPerformanceMode = enumAt(values, 9, AudioPlaybackPerformanceMode.Unknown),
        requestedSharingMode = enumAt(values, 10, AudioPlaybackSharingMode.Unknown),
        actualSharingMode = enumAt(values, 11, AudioPlaybackSharingMode.Unknown),
        audioApi = enumAt(values, 12, AudioPlaybackApi.Unknown),
        requestedBufferBursts = values.getOrElse(13) { 0L }.toInt(),
        framesPerBurst = values.getOrElse(14) { 0L }.toInt(),
        requestedBufferFrames = values.getOrElse(15) { 0L }.toInt(),
        actualBufferFrames = values.getOrElse(16) { 0L }.toInt(),
        bufferCapacityFrames = values.getOrElse(17) { 0L }.toInt(),
        hardwareSampleRateHz = values.getOrElse(18) { 0L }.toInt(),
        hardwareChannelCount = values.getOrElse(19) { 0L }.toInt(),
        ringCapacityFrames = values.getOrElse(20) { 0L }.toInt(),
        ringOccupancyFrames = values.getOrElse(21) { 0L }.toInt(),
        ringHighWaterMark = values.getOrElse(22) { 0L }.toInt(),
        pcmFramesSubmitted = values.getOrElse(23) { 0L },
        pcmFramesConsumed = values.getOrElse(24) { 0L },
        pcmFramesRejected = values.getOrElse(25) { 0L },
        underrunCallbacks = values.getOrElse(26) { 0L },
        underrunFrames = values.getOrElse(27) { 0L },
        silenceFramesInserted = values.getOrElse(28) { 0L },
        xRunCount = values.getOrElse(29) { 0L }.toInt(),
        normalFrames = values.getOrElse(30) { 0L },
        plcFrames = values.getOrElse(31) { 0L },
        discontinuityFrames = values.getOrElse(32) { 0L },
        lastSourceFramePosition = values.getOrElse(33) { 0L },
        lastCaptureTimeUs = values.getOrElse(34) { 0L },
        lastPresentationFramePosition = values.getOrElse(35) { 0L },
        lastPresentationTimeNs = values.getOrElse(36) { 0L },
        presentationTimestampValid = values.getOrElse(37) { 0L } != 0L,
        lastError = AudioPlaybackError.fromNativeCode(values.getOrElse(38) { 0L }.toInt()),
        exclusiveRequestGranted = values.getOrElse(42) { 0L } != 0L,
        actualFormat = enumAt(values, 43, AudioPlaybackPcmFormat.Unknown),
        hardwareFormat = enumAt(values, 44, AudioPlaybackPcmFormat.Unknown),
        ringResidenceSamples = values.getOrElse(45) { 0L },
        lastRingResidenceNs = values.getOrElse(46) { 0L },
        maxRingResidenceNs = values.getOrElse(47) { 0L },
        lookaheadSamples = values.getOrElse(48) { 0L }.toInt(),
    )
}

private inline fun <reified T : Enum<T>> enumAt(values: LongArray, index: Int, fallback: T): T =
    enumValues<T>().getOrElse(values.getOrElse(index) { 0L }.toInt()) { fallback }
