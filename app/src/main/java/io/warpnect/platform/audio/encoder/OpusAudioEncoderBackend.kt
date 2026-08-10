package io.warpnect.platform.audio.encoder

import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.AudioEncoderSnapshot
import io.warpnect.audio.encoder.AudioEncoderState
import io.warpnect.audio.encoder.audioEncoderErrorFromCode
import java.nio.ByteBuffer

internal interface OpusAudioEncoderBackend {
    fun create(request: AudioEncoderRequest): OpusBackendCreateResult

    fun start(handle: Long): AudioEncoderError

    fun submitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ): OpusBackendSubmitResult

    fun updateBitrate(handle: Long, bitrateBps: Int): AudioEncoderError

    fun stop(handle: Long): OpusBackendStopResult

    fun snapshot(handle: Long, state: AudioEncoderState): AudioEncoderSnapshot

    fun destroy(handle: Long): AudioEncoderError
}

internal data class OpusBackendCreateResult(
    val error: AudioEncoderError,
    val handle: Long = 0L,
    val outputBuffer: ByteBuffer? = null,
    val snapshot: AudioEncoderSnapshot = AudioEncoderSnapshot(),
)

internal enum class OpusBackendSubmitStatus {
    NeedMoreInput,
    EncodedFrameReady,
    Discontinuity,
    Failure,
}

internal data class OpusBackendSubmitResult(
    val error: AudioEncoderError,
    val status: OpusBackendSubmitStatus,
    val nativeError: Int = 0,
    val consumedBytes: Int = 0,
    val packetSize: Int = 0,
    val firstFramePosition: Long = 0L,
    val captureTimeNs: Long = 0L,
    val timestampQuality: AudioTimestampQuality = AudioTimestampQuality.Unavailable,
    val encodedFrameIndex: Long = 0L,
    val expectedFramePosition: Long = 0L,
    val actualFramePosition: Long = 0L,
    val directFastPath: Boolean = false,
    val assemblerPath: Boolean = false,
)

internal data class OpusBackendStopResult(
    val error: AudioEncoderError,
    val tailFramesDropped: Long = 0L,
)

internal object NativeOpusAudioEncoderBackend : OpusAudioEncoderBackend {
    override fun create(request: AudioEncoderRequest): OpusBackendCreateResult {
        val handle = NativeBridge.audioEncoderCreate(
            source = request.source.ordinal,
            sampleRateHz = request.sampleRateHz,
            channelCount = request.channelCount,
            frameDurationUs = request.frameDurationUs,
            bitrateBps = request.bitrateBps,
            bitrateMode = request.bitrateMode.nativeCode,
            complexity = request.complexity,
        )
        if (handle == 0L) {
            return OpusBackendCreateResult(error = AudioEncoderError.EncoderCreateFailed)
        }
        val output = NativeBridge.audioEncoderOutputBuffer(handle)
        if (output == null) {
            NativeBridge.audioEncoderDestroy(handle)
            return OpusBackendCreateResult(error = AudioEncoderError.DependencyUnavailable)
        }
        return OpusBackendCreateResult(
            error = AudioEncoderError.None,
            handle = handle,
            outputBuffer = output,
            snapshot = snapshot(handle, AudioEncoderState.Prepared),
        )
    }

    override fun start(handle: Long): AudioEncoderError =
        audioEncoderErrorFromCode(NativeBridge.audioEncoderStart(handle))

    override fun submitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ): OpusBackendSubmitResult {
        val values = NativeBridge.audioEncoderSubmitPcm(
            handle = handle,
            buffer = buffer,
            offset = offset,
            size = sizeBytes,
            firstFramePosition = firstFramePosition,
            captureTimeNs = captureTimeNs,
            timestampQuality = timestampQuality.ordinal,
        )
        val status = when (values.getOrElse(1) { 3L }.toInt()) {
            0 -> OpusBackendSubmitStatus.NeedMoreInput
            1 -> OpusBackendSubmitStatus.EncodedFrameReady
            2 -> OpusBackendSubmitStatus.Discontinuity
            else -> OpusBackendSubmitStatus.Failure
        }
        val quality = AudioTimestampQuality.entries.getOrElse(values.getOrElse(7) { 2L }.toInt()) {
            AudioTimestampQuality.Unavailable
        }
        return OpusBackendSubmitResult(
            error = audioEncoderErrorFromCode(values.getOrElse(0) { 12L }.toInt()),
            status = status,
            nativeError = values.getOrElse(2) { 0L }.toInt(),
            consumedBytes = values.getOrElse(3) { 0L }.toInt(),
            packetSize = values.getOrElse(4) { 0L }.toInt(),
            firstFramePosition = values.getOrElse(5) { 0L },
            captureTimeNs = values.getOrElse(6) { 0L },
            timestampQuality = quality,
            encodedFrameIndex = values.getOrElse(8) { 0L },
            expectedFramePosition = values.getOrElse(9) { 0L },
            actualFramePosition = values.getOrElse(10) { 0L },
            directFastPath = values.getOrElse(11) { 0L } != 0L,
            assemblerPath = values.getOrElse(12) { 0L } != 0L,
        )
    }

    override fun updateBitrate(handle: Long, bitrateBps: Int): AudioEncoderError =
        audioEncoderErrorFromCode(NativeBridge.audioEncoderUpdateBitrate(handle, bitrateBps))

    override fun stop(handle: Long): OpusBackendStopResult {
        val values = NativeBridge.audioEncoderStop(handle)
        return OpusBackendStopResult(
            error = audioEncoderErrorFromCode(values.getOrElse(0) { 12L }.toInt()),
            tailFramesDropped = values.getOrElse(1) { 0L },
        )
    }

    override fun snapshot(handle: Long, state: AudioEncoderState): AudioEncoderSnapshot =
        snapshotFrom(NativeBridge.audioEncoderSnapshot(handle), state)

    override fun destroy(handle: Long): AudioEncoderError =
        audioEncoderErrorFromCode(NativeBridge.audioEncoderDestroy(handle))
}

internal fun snapshotFrom(values: LongArray, state: AudioEncoderState): AudioEncoderSnapshot {
    val source = AudioCaptureSource.entries.getOrElse(values.getOrElse(1) { 1L }.toInt()) {
        AudioCaptureSource.MicrophoneAudio
    }
    val codec = AudioCodec.entries.firstOrNull { it.code == values.getOrElse(0) { 0L }.toInt() }
        ?: AudioCodec.Unknown
    val bitrateMode = AudioBitrateMode.entries.firstOrNull {
        it.nativeCode == values.getOrElse(7) { 0L }.toInt()
    } ?: AudioBitrateMode.ConstantBitrate
    return AudioEncoderSnapshot(
        state = state,
        source = source,
        codec = codec,
        sampleRateHz = values.getOrElse(2) { 0L }.toInt(),
        channelCount = values.getOrElse(3) { 0L }.toInt(),
        frameDurationUs = values.getOrElse(4) { 0L }.toInt(),
        samplesPerFrame = values.getOrElse(5) { 0L }.toInt(),
        bitrateBps = values.getOrElse(6) { 0L }.toInt(),
        bitrateMode = bitrateMode,
        complexity = values.getOrElse(8) { 0L }.toInt(),
        lookaheadSamples = values.getOrElse(9) { 0L }.toInt(),
        pcmChunksReceived = values.getOrElse(10) { 0L },
        pcmFramesReceived = values.getOrElse(11) { 0L },
        encodedFrames = values.getOrElse(12) { 0L },
        encodedBytes = values.getOrElse(13) { 0L },
        directFastPathFrames = values.getOrElse(14) { 0L },
        assemblerFrames = values.getOrElse(15) { 0L },
        partialFrameSamples = values.getOrElse(16) { 0L }.toInt(),
        pcmDiscontinuities = values.getOrElse(17) { 0L },
        pcmFramesSkipped = values.getOrElse(18) { 0L },
        tailFramesDropped = values.getOrElse(19) { 0L },
        lastInputFramePosition = values.getOrElse(20) { 0L },
        lastEncodedFramePosition = values.getOrElse(21) { 0L },
        lastCaptureTimeNs = values.getOrElse(22) { 0L },
        lastNativeError = values.getOrElse(23) { 0L }.toInt(),
        lastError = audioEncoderErrorFromCode(values.getOrElse(26) { 0L }.toInt()),
    )
}
