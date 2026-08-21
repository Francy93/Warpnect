package io.warpnect.platform.audio.decoder

import io.warpnect.audio.decoder.AudioDecoderConfig
import io.warpnect.audio.decoder.AudioDecoderController
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.AudioDecoderResult
import io.warpnect.audio.decoder.AudioDecoderSnapshot
import io.warpnect.audio.decoder.AudioDecoderState
import io.warpnect.audio.decoder.AudioDecoderValidation
import io.warpnect.audio.decoder.DecodedAudioFormat
import io.warpnect.audio.decoder.DecodedPcmAudioSink
import io.warpnect.audio.decoder.EncodedAudioFrameMetadata
import io.warpnect.audio.decoder.MissingAudioFrameMetadata
import io.warpnect.telemetry.AudioReceiverTelemetry
import java.nio.ByteBuffer

class NativeOpusAudioDecoderController internal constructor(
    private val backend: OpusAudioDecoderBackend,
    private val telemetry: AudioReceiverTelemetry? = null,
) : AudioDecoderController {
    constructor(telemetry: AudioReceiverTelemetry? = null) : this(NativeOpusAudioDecoderBackend, telemetry)

    private val lock = Any()
    private var state = AudioDecoderState.Stopped
    private var handle: Long = 0L
    private var outputBuffer: ByteBuffer? = null
    private var config: AudioDecoderConfig? = null
    private var format: DecodedAudioFormat? = null
    private var sink: DecodedPcmAudioSink? = null
    private var localSnapshot = AudioDecoderSnapshot()
    private var localSinkFailures = 0L

    override fun prepare(config: AudioDecoderConfig, sink: DecodedPcmAudioSink): AudioDecoderResult {
        return synchronized(lock) {
            val validation = AudioDecoderValidation.validate(config)
            if (validation != AudioDecoderError.None) {
                failLocked(validation)
                return@synchronized resultLocked(validation)
            }
            if (state != AudioDecoderState.Stopped) {
                val error = if (state == AudioDecoderState.Closed) {
                    AudioDecoderError.Closed
                } else {
                    AudioDecoderError.AlreadyPrepared
                }
                failLocked(error)
                return@synchronized resultLocked(error)
            }
            state = AudioDecoderState.Preparing
            localSinkFailures = 0L
            localSnapshot = AudioDecoderSnapshot(
                state = state,
                source = config.source,
                codec = config.codec,
                configGeneration = config.configGeneration,
                sampleRateHz = config.sampleRateHz,
                channelCount = config.channelCount,
                frameDurationUs = config.frameDurationUs,
                samplesPerFrame = AudioDecoderValidation.samplesPerFrame(
                    config.sampleRateHz,
                    config.frameDurationUs,
                ),
                lookaheadSamples = config.lookaheadSamples,
            )

            val created = backend.create(config)
            if (created.error != AudioDecoderError.None ||
                created.handle == 0L ||
                created.outputBuffer == null
            ) {
                state = AudioDecoderState.Error
                localSnapshot = localSnapshot.copy(state = state, lastError = created.error)
                return@synchronized resultLocked(created.error)
            }

            handle = created.handle
            outputBuffer = created.outputBuffer
            this.config = config
            this.sink = sink
            state = AudioDecoderState.Prepared
            localSnapshot = created.snapshot.copy(state = state, lastError = AudioDecoderError.None)
            format = AudioDecoderValidation.decodedFormatFrom(config)
            try {
                sink.onOutputFormatChanged(requireNotNull(format))
            } catch (_: RuntimeException) {
                backend.destroy(handle)
                handle = 0L
                outputBuffer = null
                this.config = null
                this.sink = null
                localSinkFailures += 1
                state = AudioDecoderState.Error
                localSnapshot = localSnapshot.copy(
                    state = state,
                    sinkFailures = localSinkFailures,
                    lastError = AudioDecoderError.OutputSinkFailure,
                )
                return@synchronized resultLocked(AudioDecoderError.OutputSinkFailure)
            }
            resultLocked(AudioDecoderError.None)
        }
    }

    override fun start(): AudioDecoderResult = synchronized(lock) {
        if (state == AudioDecoderState.Running) {
            return@synchronized resultLocked(AudioDecoderError.AlreadyRunning)
        }
        if (state != AudioDecoderState.Prepared) {
            val error = if (state == AudioDecoderState.Closed) {
                AudioDecoderError.Closed
            } else {
                AudioDecoderError.NotPrepared
            }
            return@synchronized resultLocked(error)
        }
        val error = backend.start(handle)
        state = if (error == AudioDecoderError.None) AudioDecoderState.Running else AudioDecoderState.Error
        localSnapshot = snapshotWithLocalSinkFailures(backend.snapshot(handle, state)).copy(lastError = error)
        resultLocked(error)
    }

    override fun decode(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        metadata: EncodedAudioFrameMetadata,
    ): AudioDecoderResult = synchronized(lock) {
        val currentSink = sink ?: return@synchronized resultLocked(AudioDecoderError.NotPrepared)
        val currentOutput = outputBuffer ?: return@synchronized resultLocked(AudioDecoderError.NotPrepared)
        val rangeError = validateDecodeInput(buffer, offset, sizeBytes, metadata)
        if (rangeError != AudioDecoderError.None) {
            recordNonFatalOrFatal(rangeError)
            currentSink.onDecoderError(rangeError)
            return@synchronized resultLocked(rangeError)
        }
        val decoded = backend.decode(
            handle = handle,
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            configGeneration = metadata.configGeneration,
            firstFramePosition = metadata.firstFramePosition,
            captureTimeUs = metadata.captureTimeUs,
            timestampQuality = metadata.timestampQuality,
            discontinuityBefore = metadata.discontinuityBefore,
        )
        localSnapshot = snapshotWithLocalSinkFailures(backend.snapshot(handle, state)).copy(
            lastError = decoded.error,
            lastNativeError = decoded.nativeError,
        )
        if (decoded.error != AudioDecoderError.None) {
            recordNonFatalOrFatal(decoded.error, decoded.nativeError)
            currentSink.onDecoderError(decoded.error)
            return@synchronized resultLocked(decoded.error)
        }
        try {
            currentSink.onPcmFrame(
                buffer = currentOutput,
                offset = 0,
                sizeBytes = decoded.pcmSizeBytes,
                frameCount = decoded.frameCount,
                firstFramePosition = decoded.firstFramePosition,
                captureTimeUs = decoded.captureTimeUs,
                timestampQuality = decoded.timestampQuality,
                discontinuityBefore = decoded.discontinuityBefore,
                frameKind = decoded.frameKind,
            )
            telemetry?.decodedFrames?.increment()
            telemetry?.decodedSamples?.add(decoded.frameCount.toULong())
        } catch (_: RuntimeException) {
            localSinkFailures += 1
            failLocked(AudioDecoderError.OutputSinkFailure)
            return@synchronized resultLocked(AudioDecoderError.OutputSinkFailure)
        }
        resultLocked(AudioDecoderError.None)
    }

    override fun concealMissingFrame(metadata: MissingAudioFrameMetadata): AudioDecoderResult = synchronized(lock) {
        val currentSink = sink ?: return@synchronized resultLocked(AudioDecoderError.NotPrepared)
        val currentOutput = outputBuffer ?: return@synchronized resultLocked(AudioDecoderError.NotPrepared)
        val metadataError = validateMissingMetadata(metadata)
        if (metadataError != AudioDecoderError.None) {
            recordNonFatalOrFatal(metadataError)
            currentSink.onDecoderError(metadataError)
            return@synchronized resultLocked(metadataError)
        }
        val decoded = backend.concealMissingFrame(
            handle = handle,
            configGeneration = metadata.configGeneration,
            firstFramePosition = metadata.firstFramePosition,
            captureTimeUs = metadata.captureTimeUs,
            timestampQuality = metadata.timestampQuality,
        )
        localSnapshot = snapshotWithLocalSinkFailures(backend.snapshot(handle, state)).copy(
            lastError = decoded.error,
            lastNativeError = decoded.nativeError,
        )
        if (decoded.error != AudioDecoderError.None) {
            recordNonFatalOrFatal(decoded.error, decoded.nativeError)
            currentSink.onDecoderError(decoded.error)
            return@synchronized resultLocked(decoded.error)
        }
        try {
            currentSink.onPcmFrame(
                buffer = currentOutput,
                offset = 0,
                sizeBytes = decoded.pcmSizeBytes,
                frameCount = decoded.frameCount,
                firstFramePosition = decoded.firstFramePosition,
                captureTimeUs = decoded.captureTimeUs,
                timestampQuality = decoded.timestampQuality,
                discontinuityBefore = decoded.discontinuityBefore,
                frameKind = decoded.frameKind,
            )
            telemetry?.decodedSamples?.add(decoded.frameCount.toULong())
            telemetry?.plcFrames?.increment()
        } catch (_: RuntimeException) {
            localSinkFailures += 1
            failLocked(AudioDecoderError.OutputSinkFailure)
            return@synchronized resultLocked(AudioDecoderError.OutputSinkFailure)
        }
        resultLocked(AudioDecoderError.None)
    }

    override fun stop(): AudioDecoderResult = synchronized(lock) {
        if (state == AudioDecoderState.Stopped) {
            return@synchronized resultLocked(AudioDecoderError.None)
        }
        if (state == AudioDecoderState.Closed) {
            return@synchronized resultLocked(AudioDecoderError.Closed)
        }
        if (handle == 0L) {
            state = AudioDecoderState.Stopped
            localSnapshot = AudioDecoderSnapshot(state = state)
            return@synchronized resultLocked(AudioDecoderError.None)
        }
        state = AudioDecoderState.Stopping
        val error = backend.stop(handle)
        state = if (error == AudioDecoderError.None) AudioDecoderState.Prepared else AudioDecoderState.Error
        localSnapshot = snapshotWithLocalSinkFailures(backend.snapshot(handle, state)).copy(lastError = error)
        resultLocked(error)
    }

    override fun snapshot(): AudioDecoderSnapshot = synchronized(lock) {
        if (handle != 0L && state != AudioDecoderState.Closed && state != AudioDecoderState.Error) {
            localSnapshot = snapshotWithLocalSinkFailures(backend.snapshot(handle, state))
        }
        localSnapshot
    }

    override fun close() {
        synchronized(lock) {
            if (state == AudioDecoderState.Closed) return
            if (handle != 0L) {
                backend.destroy(handle)
            }
            handle = 0L
            outputBuffer = null
            config = null
            format = null
            sink = null
            state = AudioDecoderState.Closed
            localSnapshot = AudioDecoderSnapshot(state = state, lastError = AudioDecoderError.None)
        }
    }

    private fun validateDecodeInput(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        metadata: EncodedAudioFrameMetadata,
    ): AudioDecoderError {
        if (state != AudioDecoderState.Running) return AudioDecoderError.NotRunning
        if (metadata.configGeneration != config?.configGeneration) return AudioDecoderError.ReconfigurationRequired
        if (metadata.firstFramePosition < 0 || metadata.captureTimeUs < 0) return AudioDecoderError.InvalidBufferRange
        if (!buffer.isDirect) return AudioDecoderError.NonDirectBuffer
        if (offset < 0 || sizeBytes <= 0) return AudioDecoderError.InvalidBufferRange
        if (offset.toLong() + sizeBytes.toLong() > buffer.capacity().toLong()) {
            return AudioDecoderError.InvalidBufferRange
        }
        return AudioDecoderError.None
    }

    private fun validateMissingMetadata(metadata: MissingAudioFrameMetadata): AudioDecoderError {
        if (state != AudioDecoderState.Running) return AudioDecoderError.NotRunning
        if (metadata.configGeneration != config?.configGeneration) return AudioDecoderError.InvalidMissingFrameMetadata
        if (metadata.firstFramePosition < 0 || metadata.captureTimeUs < 0) {
            return AudioDecoderError.InvalidMissingFrameMetadata
        }
        return AudioDecoderError.None
    }

    private fun recordNonFatalOrFatal(error: AudioDecoderError, nativeError: Int = 0) {
        if (error == AudioDecoderError.ReconfigurationRequired) {
            telemetry?.decoderErrors?.increment()
            localSnapshot = localSnapshot.copy(lastError = error, lastNativeError = nativeError)
        } else {
            failLocked(error, nativeError)
        }
    }

    private fun resultLocked(error: AudioDecoderError): AudioDecoderResult =
        AudioDecoderResult(error = error, snapshot = localSnapshot.copy(state = state), format = format)

    private fun failLocked(error: AudioDecoderError, nativeError: Int = 0) {
        telemetry?.decoderErrors?.increment()
        state = if (state == AudioDecoderState.Closed) AudioDecoderState.Closed else AudioDecoderState.Error
        localSnapshot = localSnapshot.copy(
            state = state,
            sinkFailures = localSinkFailures,
            lastError = error,
            lastNativeError = nativeError,
        )
    }

    private fun snapshotWithLocalSinkFailures(snapshot: AudioDecoderSnapshot): AudioDecoderSnapshot =
        snapshot.copy(sinkFailures = localSinkFailures)
}
