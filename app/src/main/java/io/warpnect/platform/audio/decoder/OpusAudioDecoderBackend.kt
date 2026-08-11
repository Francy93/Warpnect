package io.warpnect.platform.audio.decoder

import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.AudioDecoderConfig
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.AudioDecoderSnapshot
import io.warpnect.audio.decoder.AudioDecoderState
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.decoder.audioDecoderErrorFromCode
import io.warpnect.audio.encoder.AudioCodec
import java.nio.ByteBuffer

internal interface OpusAudioDecoderBackend {
    fun create(config: AudioDecoderConfig): OpusDecoderBackendCreateResult

    fun start(handle: Long): AudioDecoderError

    fun decode(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
    ): OpusDecoderBackendDecodeResult

    fun concealMissingFrame(
        handle: Long,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
    ): OpusDecoderBackendDecodeResult

    fun stop(handle: Long): AudioDecoderError

    fun snapshot(handle: Long, state: AudioDecoderState): AudioDecoderSnapshot

    fun destroy(handle: Long): AudioDecoderError
}

internal data class OpusDecoderBackendCreateResult(
    val error: AudioDecoderError,
    val handle: Long = 0L,
    val outputBuffer: ByteBuffer? = null,
    val snapshot: AudioDecoderSnapshot = AudioDecoderSnapshot(),
)

internal data class OpusDecoderBackendDecodeResult(
    val error: AudioDecoderError,
    val nativeError: Int = 0,
    val frameKind: DecodedAudioFrameKind = DecodedAudioFrameKind.Normal,
    val pcmSizeBytes: Int = 0,
    val frameCount: Int = 0,
    val firstFramePosition: Long = 0L,
    val captureTimeUs: Long = 0L,
    val timestampQuality: AudioTimestampQuality = AudioTimestampQuality.Unavailable,
    val discontinuityBefore: Boolean = false,
)

internal object NativeOpusAudioDecoderBackend : OpusAudioDecoderBackend {
    override fun create(config: AudioDecoderConfig): OpusDecoderBackendCreateResult {
        val handle = NativeBridge.audioDecoderCreate(
            source = config.source.ordinal,
            configGeneration = config.configGeneration,
            sampleRateHz = config.sampleRateHz,
            channelCount = config.channelCount,
            frameDurationUs = config.frameDurationUs,
            lookaheadSamples = config.lookaheadSamples,
        )
        if (handle == 0L) {
            return OpusDecoderBackendCreateResult(error = AudioDecoderError.DecoderCreateFailed)
        }
        val output = NativeBridge.audioDecoderOutputBuffer(handle)
        if (output == null) {
            NativeBridge.audioDecoderDestroy(handle)
            return OpusDecoderBackendCreateResult(error = AudioDecoderError.DecoderCreateFailed)
        }
        return OpusDecoderBackendCreateResult(
            error = AudioDecoderError.None,
            handle = handle,
            outputBuffer = output,
            snapshot = snapshot(handle, AudioDecoderState.Prepared),
        )
    }

    override fun start(handle: Long): AudioDecoderError =
        audioDecoderErrorFromCode(NativeBridge.audioDecoderStart(handle))

    override fun decode(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
    ): OpusDecoderBackendDecodeResult = decodeResultFrom(
        NativeBridge.audioDecoderDecode(
            handle = handle,
            buffer = buffer,
            offset = offset,
            size = sizeBytes,
            configGeneration = configGeneration,
            firstFramePosition = firstFramePosition,
            captureTimeUs = captureTimeUs,
            timestampQuality = timestampQuality.ordinal,
            discontinuityBefore = discontinuityBefore,
        ),
    )

    override fun concealMissingFrame(
        handle: Long,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
    ): OpusDecoderBackendDecodeResult = decodeResultFrom(
        NativeBridge.audioDecoderConcealMissingFrame(
            handle = handle,
            configGeneration = configGeneration,
            firstFramePosition = firstFramePosition,
            captureTimeUs = captureTimeUs,
            timestampQuality = timestampQuality.ordinal,
        ),
    )

    override fun stop(handle: Long): AudioDecoderError =
        audioDecoderErrorFromCode(NativeBridge.audioDecoderStop(handle))

    override fun snapshot(handle: Long, state: AudioDecoderState): AudioDecoderSnapshot =
        audioDecoderSnapshotFrom(NativeBridge.audioDecoderSnapshot(handle), state)

    override fun destroy(handle: Long): AudioDecoderError =
        audioDecoderErrorFromCode(NativeBridge.audioDecoderDestroy(handle))
}

internal fun decodeResultFrom(values: LongArray): OpusDecoderBackendDecodeResult {
    val quality = AudioTimestampQuality.entries.getOrElse(values.getOrElse(7) { 2L }.toInt()) {
        AudioTimestampQuality.Unavailable
    }
    val kind = DecodedAudioFrameKind.entries.getOrElse(values.getOrElse(2) { 0L }.toInt()) {
        DecodedAudioFrameKind.Normal
    }
    return OpusDecoderBackendDecodeResult(
        error = audioDecoderErrorFromCode(values.getOrElse(0) { 15L }.toInt()),
        nativeError = values.getOrElse(1) { 0L }.toInt(),
        frameKind = kind,
        pcmSizeBytes = values.getOrElse(3) { 0L }.toInt(),
        frameCount = values.getOrElse(4) { 0L }.toInt(),
        firstFramePosition = values.getOrElse(5) { 0L },
        captureTimeUs = values.getOrElse(6) { 0L },
        timestampQuality = quality,
        discontinuityBefore = values.getOrElse(8) { 0L } != 0L,
    )
}

internal fun audioDecoderSnapshotFrom(values: LongArray, state: AudioDecoderState): AudioDecoderSnapshot {
    val source = AudioCaptureSource.entries.getOrElse(values.getOrElse(1) { 1L }.toInt()) {
        AudioCaptureSource.MicrophoneAudio
    }
    val codec = AudioCodec.entries.firstOrNull { it.code == values.getOrElse(0) { 0L }.toInt() }
        ?: AudioCodec.Unknown
    return AudioDecoderSnapshot(
        state = state,
        source = source,
        codec = codec,
        configGeneration = values.getOrElse(2) { 0L },
        sampleRateHz = values.getOrElse(3) { 0L }.toInt(),
        channelCount = values.getOrElse(4) { 0L }.toInt(),
        frameDurationUs = values.getOrElse(5) { 0L }.toInt(),
        samplesPerFrame = values.getOrElse(6) { 0L }.toInt(),
        lookaheadSamples = values.getOrElse(7) { 0L }.toInt(),
        packetsSubmitted = values.getOrElse(8) { 0L },
        encodedBytesSubmitted = values.getOrElse(9) { 0L },
        framesDecoded = values.getOrElse(10) { 0L },
        pcmFramesDecoded = values.getOrElse(11) { 0L },
        pcmBytesDecoded = values.getOrElse(12) { 0L },
        plcFramesGenerated = values.getOrElse(13) { 0L },
        malformedPackets = values.getOrElse(14) { 0L },
        durationMismatches = values.getOrElse(15) { 0L },
        decodeFailures = values.getOrElse(16) { 0L },
        sinkFailures = values.getOrElse(17) { 0L },
        lastFramePosition = values.getOrElse(18) { 0L },
        lastCaptureTimeUs = values.getOrElse(19) { 0L },
        lastDecodedSamples = values.getOrElse(20) { 0L }.toInt(),
        lastNativeError = values.getOrElse(21) { 0L }.toInt(),
        lastError = audioDecoderErrorFromCode(values.getOrElse(22) { 0L }.toInt()),
    )
}
