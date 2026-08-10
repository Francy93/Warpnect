package io.warpnect.platform.audio.encoder

import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioEncoderCapabilities
import io.warpnect.audio.encoder.AudioEncoderController
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.AudioEncoderResult
import io.warpnect.audio.encoder.AudioEncoderSnapshot
import io.warpnect.audio.encoder.AudioEncoderState
import io.warpnect.audio.encoder.AudioEncoderSupport
import io.warpnect.audio.encoder.AudioEncoderValidation
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import io.warpnect.audio.encoder.audioFrameOffsetTimeNs
import java.nio.ByteBuffer

class NativeOpusAudioEncoderController internal constructor(
    private val backend: OpusAudioEncoderBackend,
) : AudioEncoderController {
    constructor() : this(NativeOpusAudioEncoderBackend)

    private val lock = Any()
    private var state = AudioEncoderState.Stopped
    private var handle: Long = 0L
    private var outputBuffer: ByteBuffer? = null
    private var request: AudioEncoderRequest? = null
    private var format: EncodedAudioFormat? = null
    private var sink: EncodedAudioSink? = null
    private var localSnapshot = AudioEncoderSnapshot()

    override fun queryCapabilities(request: AudioEncoderRequest): AudioEncoderCapabilities {
        val error = AudioEncoderValidation.validate(request)
        val support = AudioEncoderSupport(
            codecSupported = error != AudioEncoderError.UnsupportedCodec,
            sampleRateSupported = error != AudioEncoderError.UnsupportedSampleRate,
            channelCountSupported = error != AudioEncoderError.UnsupportedChannelCount,
            frameDurationSupported = error != AudioEncoderError.UnsupportedFrameDuration,
            bitrateSupported = error != AudioEncoderError.InvalidBitrate,
            complexitySupported = error != AudioEncoderError.InvalidComplexity,
        )
        return AudioEncoderCapabilities(
            request = request,
            available = error == AudioEncoderError.None,
            support = support,
            selectedFormat = if (error == AudioEncoderError.None) {
                AudioEncoderValidation.encodedFormatFrom(request, lookaheadSamples = 0)
            } else {
                null
            },
            error = error,
        )
    }

    override fun prepare(request: AudioEncoderRequest, sink: EncodedAudioSink): AudioEncoderResult {
        return synchronized(lock) {
            val validation = AudioEncoderValidation.validate(request)
            if (validation != AudioEncoderError.None) {
                failLocked(validation)
                return@synchronized resultLocked(validation)
            }
            if (state != AudioEncoderState.Stopped) {
                val error = if (state == AudioEncoderState.Closed) {
                    AudioEncoderError.Closed
                } else {
                    AudioEncoderError.AlreadyPrepared
                }
                failLocked(error)
                return@synchronized resultLocked(error)
            }
            state = AudioEncoderState.Preparing
            localSnapshot = AudioEncoderSnapshot(
                state = state,
                source = request.source,
                codec = request.codec,
                sampleRateHz = request.sampleRateHz,
                channelCount = request.channelCount,
                frameDurationUs = request.frameDurationUs,
                samplesPerFrame = AudioEncoderValidation.samplesPerFrame(
                    request.sampleRateHz,
                    request.frameDurationUs,
                ),
                bitrateBps = request.bitrateBps,
                bitrateMode = request.bitrateMode,
                complexity = request.complexity,
            )

            val created = backend.create(request)
            if (created.error != AudioEncoderError.None ||
                created.handle == 0L ||
                created.outputBuffer == null
            ) {
                state = AudioEncoderState.Error
                localSnapshot = localSnapshot.copy(state = state, lastError = created.error)
                return@synchronized resultLocked(created.error)
            }

            handle = created.handle
            outputBuffer = created.outputBuffer
            this.request = request
            this.sink = sink
            state = AudioEncoderState.Prepared
            localSnapshot = created.snapshot.copy(state = state, lastError = AudioEncoderError.None)
            format = AudioEncoderValidation.encodedFormatFrom(request, localSnapshot.lookaheadSamples)
            try {
                sink.onOutputFormatChanged(requireNotNull(format))
            } catch (_: RuntimeException) {
                backend.destroy(handle)
                handle = 0L
                outputBuffer = null
                this.request = null
                this.sink = null
                state = AudioEncoderState.Error
                localSnapshot = localSnapshot.copy(state = state, lastError = AudioEncoderError.OutputSinkFailure)
                return@synchronized resultLocked(AudioEncoderError.OutputSinkFailure)
            }
            resultLocked(AudioEncoderError.None)
        }
    }

    override fun start(): AudioEncoderResult = synchronized(lock) {
        if (state == AudioEncoderState.Running) {
            return@synchronized resultLocked(AudioEncoderError.AlreadyRunning)
        }
        if (state != AudioEncoderState.Prepared) {
            val error = if (state == AudioEncoderState.Closed) {
                AudioEncoderError.Closed
            } else {
                AudioEncoderError.NotPrepared
            }
            return@synchronized resultLocked(error)
        }
        val error = backend.start(handle)
        state = if (error == AudioEncoderError.None) AudioEncoderState.Running else AudioEncoderState.Error
        localSnapshot = backend.snapshot(handle, state).copy(lastError = error)
        resultLocked(error)
    }

    override fun updateBitrate(bitrateBps: Int): AudioEncoderResult = synchronized(lock) {
        if (bitrateBps < AudioEncoderRequest.MIN_BITRATE_BPS ||
            bitrateBps > AudioEncoderRequest.MAX_BITRATE_BPS
        ) {
            return@synchronized resultLocked(AudioEncoderError.InvalidBitrate)
        }
        if (state != AudioEncoderState.Prepared && state != AudioEncoderState.Running) {
            val error = if (state == AudioEncoderState.Closed) {
                AudioEncoderError.Closed
            } else {
                AudioEncoderError.NotPrepared
            }
            return@synchronized resultLocked(error)
        }
        val error = backend.updateBitrate(handle, bitrateBps)
        val currentRequest = request
        if (error == AudioEncoderError.None && currentRequest != null) {
            request = currentRequest.copy(bitrateBps = bitrateBps)
        }
        localSnapshot = backend.snapshot(handle, state).copy(lastError = error)
        resultLocked(error)
    }

    override fun stop(): AudioEncoderResult = synchronized(lock) {
        if (state == AudioEncoderState.Stopped) {
            return@synchronized resultLocked(AudioEncoderError.None)
        }
        if (state == AudioEncoderState.Closed) {
            return@synchronized resultLocked(AudioEncoderError.Closed)
        }
        if (handle == 0L) {
            state = AudioEncoderState.Stopped
            localSnapshot = AudioEncoderSnapshot(state = state)
            return@synchronized resultLocked(AudioEncoderError.None)
        }
        state = AudioEncoderState.Stopping
        val stopped = backend.stop(handle)
        state = if (stopped.error == AudioEncoderError.None) AudioEncoderState.Prepared else AudioEncoderState.Error
        localSnapshot = backend.snapshot(handle, state).copy(lastError = stopped.error)
        resultLocked(stopped.error)
    }

    fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ): AudioEncoderError = synchronized(lock) {
        val currentRequest = request ?: return@synchronized AudioEncoderError.NotPrepared
        val currentSink = sink ?: return@synchronized AudioEncoderError.NotPrepared
        val currentOutput = outputBuffer ?: return@synchronized AudioEncoderError.NotPrepared
        if (state != AudioEncoderState.Running) {
            return@synchronized AudioEncoderError.NotRunning
        }
        val rangeError = validatePcmRange(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            frameCount = frameCount,
            channelCount = currentRequest.channelCount,
            firstFramePosition = firstFramePosition,
            captureTimeNs = captureTimeNs,
        )
        if (rangeError != AudioEncoderError.None) {
            failLocked(rangeError)
            currentSink.onEncoderError(rangeError)
            return@synchronized rangeError
        }

        var remainingBytes = sizeBytes
        var currentOffset = offset
        var consumedFramesFromChunk = 0L
        var discontinuityRetry = false
        while (remainingBytes > 0) {
            val framePosition = firstFramePosition + consumedFramesFromChunk
            val frameCaptureTimeNs = safeAddNs(
                captureTimeNs,
                audioFrameOffsetTimeNs(consumedFramesFromChunk, currentRequest.sampleRateHz),
            )
            val submitted = backend.submitPcm(
                handle = handle,
                buffer = buffer,
                offset = currentOffset,
                sizeBytes = remainingBytes,
                firstFramePosition = framePosition,
                captureTimeNs = frameCaptureTimeNs,
                timestampQuality = timestampQuality,
            )
            when (submitted.status) {
                OpusBackendSubmitStatus.NeedMoreInput -> {
                    if (submitted.error != AudioEncoderError.None) {
                        failLocked(submitted.error, submitted.nativeError)
                        currentSink.onEncoderError(submitted.error)
                        return@synchronized submitted.error
                    }
                    consumeProgress(submitted.consumedBytes).also {
                        currentOffset += it
                        remainingBytes -= it
                        consumedFramesFromChunk += it / bytesPerFrame(currentRequest.channelCount)
                    }
                    localSnapshot = backend.snapshot(handle, state)
                    return@synchronized AudioEncoderError.None
                }
                OpusBackendSubmitStatus.EncodedFrameReady -> {
                    if (submitted.error != AudioEncoderError.None) {
                        failLocked(submitted.error, submitted.nativeError)
                        currentSink.onEncoderError(submitted.error)
                        return@synchronized submitted.error
                    }
                    try {
                        currentSink.onEncodedFrame(
                            buffer = currentOutput,
                            offset = 0,
                            sizeBytes = submitted.packetSize,
                            firstFramePosition = submitted.firstFramePosition,
                            captureTimeNs = submitted.captureTimeNs,
                            timestampQuality = submitted.timestampQuality,
                            encodedFrameIndex = submitted.encodedFrameIndex,
                        )
                    } catch (_: RuntimeException) {
                        failLocked(AudioEncoderError.OutputSinkFailure)
                        return@synchronized AudioEncoderError.OutputSinkFailure
                    }
                    val consumed = consumeProgress(submitted.consumedBytes)
                    currentOffset += consumed
                    remainingBytes -= consumed
                    consumedFramesFromChunk += consumed / bytesPerFrame(currentRequest.channelCount)
                    localSnapshot = backend.snapshot(handle, state)
                    discontinuityRetry = false
                }
                OpusBackendSubmitStatus.Discontinuity -> {
                    if (discontinuityRetry) {
                        failLocked(AudioEncoderError.PcmDiscontinuity)
                        currentSink.onEncoderError(AudioEncoderError.PcmDiscontinuity)
                        return@synchronized AudioEncoderError.PcmDiscontinuity
                    }
                    currentSink.onAudioDiscontinuity(
                        expectedFramePosition = submitted.expectedFramePosition,
                        actualFramePosition = submitted.actualFramePosition,
                    )
                    localSnapshot = backend.snapshot(handle, state)
                    discontinuityRetry = true
                }
                OpusBackendSubmitStatus.Failure -> {
                    failLocked(submitted.error, submitted.nativeError)
                    currentSink.onEncoderError(submitted.error)
                    return@synchronized submitted.error
                }
            }
        }
        localSnapshot = backend.snapshot(handle, state)
        AudioEncoderError.None
    }

    fun reportInputError(error: AudioEncoderError): AudioEncoderError = synchronized(lock) {
        failLocked(error)
        sink?.onEncoderError(error)
        error
    }

    override fun snapshot(): AudioEncoderSnapshot = synchronized(lock) {
        if (handle != 0L && state != AudioEncoderState.Closed) {
            localSnapshot = backend.snapshot(handle, state)
        }
        localSnapshot
    }

    override fun close() {
        synchronized(lock) {
            if (state == AudioEncoderState.Closed) return
            if (handle != 0L) {
                backend.destroy(handle)
            }
            handle = 0L
            outputBuffer = null
            request = null
            format = null
            sink = null
            state = AudioEncoderState.Closed
            localSnapshot = AudioEncoderSnapshot(state = state, lastError = AudioEncoderError.None)
        }
    }

    private fun resultLocked(error: AudioEncoderError): AudioEncoderResult =
        AudioEncoderResult(error = error, snapshot = localSnapshot.copy(state = state), format = format)

    private fun failLocked(error: AudioEncoderError, nativeError: Int = 0) {
        state = if (state == AudioEncoderState.Closed) AudioEncoderState.Closed else AudioEncoderState.Error
        localSnapshot = localSnapshot.copy(state = state, lastError = error, lastNativeError = nativeError)
    }

    private fun validatePcmRange(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        channelCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
    ): AudioEncoderError {
        if (firstFramePosition < 0L || captureTimeNs < 0L) return AudioEncoderError.InvalidPcmRange
        if (!buffer.isDirect) return AudioEncoderError.NonDirectPcmBuffer
        if (offset < 0 || sizeBytes <= 0 || offset > buffer.capacity() || sizeBytes > buffer.capacity() - offset) {
            return AudioEncoderError.InvalidPcmRange
        }
        val bytesPerFrame = bytesPerFrame(channelCount)
        if (sizeBytes % bytesPerFrame != 0) return AudioEncoderError.InvalidPcmRange
        if (frameCount != sizeBytes / bytesPerFrame) return AudioEncoderError.InvalidPcmRange
        return AudioEncoderError.None
    }

    private fun bytesPerFrame(channelCount: Int): Int = AudioPcmEncoding.Pcm16.bytesPerSample * channelCount

    private fun consumeProgress(consumedBytes: Int): Int = consumedBytes.coerceAtLeast(0)

    private fun safeAddNs(lhs: Long, rhs: Long): Long {
        return if (rhs > Long.MAX_VALUE - lhs) Long.MAX_VALUE else lhs + rhs
    }
}
