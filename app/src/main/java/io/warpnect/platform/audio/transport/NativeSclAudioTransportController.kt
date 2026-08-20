package io.warpnect.platform.audio.transport

import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.transport.AudioTransportCloseResult
import io.warpnect.audio.transport.AudioTransportConfig
import io.warpnect.audio.transport.AudioTransportController
import io.warpnect.audio.transport.AudioTransportError
import io.warpnect.audio.transport.AudioTransportOpenResult
import io.warpnect.audio.transport.AudioTransportSnapshot
import io.warpnect.audio.transport.AudioTransportState
import io.warpnect.audio.transport.AudioTransportSubmitResult
import io.warpnect.platform.session.channel.markNativeEndpointAdopted
import io.warpnect.platform.session.channel.nativeEndpointHandleForLiveRebind
import io.warpnect.session.setup.ChannelEndpointLease
import java.nio.ByteBuffer

class NativeSclAudioTransportController : AudioTransportController {
    private var nativeHandle: Long = 0L
    private var config: AudioTransportConfig? = null
    private var localSnapshot = AudioTransportSnapshot()

    /** RFC-005I adopts the exact RFC-005G sender handle; no replacement socket is created. */
    internal fun adoptPreparedTransport(handle: Long): AudioTransportError {
        if (handle == 0L || nativeHandle != 0L || localSnapshot.state == AudioTransportState.Closed) {
            return AudioTransportError.InvalidHandle
        }
        nativeHandle = handle
        localSnapshot = snapshot()
        return AudioTransportError.None
    }

    /** RFC-005H updates this native Opus sender in place and preserves its security packet space. */
    internal fun rebindLiveTransport(
        localEndpoint: ChannelEndpointLease,
        remoteAddress: String,
        remotePort: Int,
    ): Boolean {
        val handle = nativeHandle
        val endpointHandle = localEndpoint.nativeEndpointHandleForLiveRebind()
        val rebound = handle != 0L && endpointHandle != 0L &&
            AudioTransportError.fromNativeCode(
                NativeBridge.audioTransportRebind(
                    handle,
                    remoteAddress,
                    remotePort,
                    localEndpoint.localPort,
                    endpointHandle,
                ),
            ) == AudioTransportError.None
        if (rebound) localEndpoint.markNativeEndpointAdopted(endpointHandle)
        return rebound
    }

    internal fun adoptedNativeHandleForTesting(): Long = nativeHandle

    override fun open(config: AudioTransportConfig): AudioTransportOpenResult {
        if (localSnapshot.state == AudioTransportState.Closed) {
            return AudioTransportOpenResult(AudioTransportError.Closed, localSnapshot)
        }
        if (nativeHandle != 0L) {
            this.config = config
            return AudioTransportOpenResult(AudioTransportError.None, snapshot())
        }
        val validation = validateConfig(config)
        if (validation != AudioTransportError.None) {
            localSnapshot = localSnapshot.copy(
                state = AudioTransportState.Error,
                source = config.source,
                lastError = validation,
            )
            return AudioTransportOpenResult(validation, localSnapshot)
        }
        val handle = NativeBridge.audioTransportCreate(
            remoteAddress = config.remoteAddress,
            remotePort = config.remotePort,
            localPort = config.localPort,
            maxWireDatagramSize = config.maxWireDatagramSize,
            initialAudioSequence = config.initialAudioSequence,
            source = config.source.ordinal,
        )
        if (handle == 0L) {
            localSnapshot = localSnapshot.copy(
                state = AudioTransportState.Error,
                source = config.source,
                lastError = AudioTransportError.UdpOpenFailed,
            )
            return AudioTransportOpenResult(AudioTransportError.UdpOpenFailed, localSnapshot)
        }
        nativeHandle = handle
        this.config = config
        localSnapshot = snapshot()
        return AudioTransportOpenResult(localSnapshot.lastError, localSnapshot)
    }

    override fun submitStreamConfig(format: EncodedAudioFormat): AudioTransportError {
        val handle = nativeHandle
        if (handle == 0L) {
            return remember(AudioTransportError.InvalidHandle)
        }
        val validation = validateFormat(format)
        if (validation != AudioTransportError.None) {
            return remember(validation)
        }
        val error = AudioTransportError.fromNativeCode(
            NativeBridge.audioTransportSubmitConfig(
                handle = handle,
                sampleRateHz = format.sampleRateHz,
                channelCount = format.channelCount,
                frameDurationUs = format.frameDurationUs,
                lookaheadSamples = format.lookaheadSamples,
            ),
        )
        return remember(error)
    }

    override fun submitAudioFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
    ): AudioTransportError {
        val handle = nativeHandle
        if (handle == 0L) {
            return remember(AudioTransportError.InvalidHandle)
        }
        val rangeError = validateDirectRange(buffer, offset, sizeBytes, firstFramePosition, captureTimeNs)
        if (rangeError != AudioTransportError.None) {
            return remember(rangeError)
        }
        val error = AudioTransportError.fromNativeCode(
            NativeBridge.audioTransportSubmitFrame(
                handle = handle,
                buffer = buffer,
                offset = offset,
                size = sizeBytes,
                firstFramePosition = firstFramePosition,
                captureTimeNs = captureTimeNs,
                timestampQuality = timestampQuality.protocolCode,
                discontinuityBefore = discontinuityBefore,
            ),
        )
        return remember(error)
    }

    override fun resendCurrentConfig(): AudioTransportSubmitResult {
        val handle = nativeHandle
        if (handle == 0L) {
            val error = remember(AudioTransportError.InvalidHandle)
            return AudioTransportSubmitResult(error, localSnapshot)
        }
        val error = remember(
            AudioTransportError.fromNativeCode(NativeBridge.audioTransportResendConfig(handle)),
        )
        return AudioTransportSubmitResult(error, localSnapshot)
    }

    override fun snapshot(): AudioTransportSnapshot {
        val handle = nativeHandle
        if (handle == 0L) {
            return localSnapshot
        }
        localSnapshot = AudioTransportSnapshot.fromNative(NativeBridge.audioTransportSnapshot(handle))
        return localSnapshot
    }

    override fun closeResult(): AudioTransportCloseResult {
        val handle = nativeHandle
        if (handle == 0L) {
            localSnapshot = localSnapshot.copy(state = AudioTransportState.Closed)
            return AudioTransportCloseResult(AudioTransportError.None, localSnapshot)
        }
        nativeHandle = 0L
        val error = AudioTransportError.fromNativeCode(NativeBridge.audioTransportDestroy(handle))
        localSnapshot = localSnapshot.copy(state = AudioTransportState.Closed, lastError = error)
        config = null
        return AudioTransportCloseResult(error, localSnapshot)
    }

    private fun remember(error: AudioTransportError): AudioTransportError {
        val current = snapshot()
        localSnapshot = current.copy(
            state = if (error == AudioTransportError.None || error.isFrameSendBackpressure()) {
                current.state
            } else {
                AudioTransportState.Error
            },
            lastError = error,
        )
        return error
    }

    private fun AudioTransportError.isFrameSendBackpressure(): Boolean = this == AudioTransportError.WouldBlock ||
        this == AudioTransportError.UdpSendFailed ||
        this == AudioTransportError.PartialEmission

    private fun validateConfig(config: AudioTransportConfig): AudioTransportError {
        if (config.remotePort !in 1..U16_MAX || config.localPort !in 0..U16_MAX) {
            return AudioTransportError.UdpBindFailed
        }
        if (config.maxWireDatagramSize <= 0) {
            return AudioTransportError.InvalidDatagramBudget
        }
        if (!isUInt32(config.initialAudioSequence)) {
            return AudioTransportError.InvalidBufferRange
        }
        return AudioTransportError.None
    }

    private fun validateFormat(format: EncodedAudioFormat): AudioTransportError {
        val currentConfig = config
        if (currentConfig != null && format.source != currentConfig.source) {
            return AudioTransportError.UnsupportedAudioMessageType
        }
        if (format.codec != AudioCodec.Opus) {
            return AudioTransportError.UnsupportedAudioCodec
        }
        if (format.sampleRateHz !in SUPPORTED_SAMPLE_RATES) {
            return AudioTransportError.InvalidSampleRate
        }
        if (format.channelCount !in 1..2) {
            return AudioTransportError.InvalidChannelCount
        }
        if (format.frameDurationUs !in SUPPORTED_FRAME_DURATIONS_US) {
            return AudioTransportError.InvalidFrameDuration
        }
        if (format.lookaheadSamples < 0) {
            return AudioTransportError.InvalidLookahead
        }
        return AudioTransportError.None
    }

    private fun validateDirectRange(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
    ): AudioTransportError {
        if (!buffer.isDirect) {
            return AudioTransportError.NonDirectBuffer
        }
        if (firstFramePosition < 0) {
            return AudioTransportError.InvalidFramePosition
        }
        if (captureTimeNs < 0) {
            return AudioTransportError.InvalidCaptureTimestamp
        }
        if (offset < 0 || sizeBytes <= 0) {
            return AudioTransportError.InvalidBufferRange
        }
        val end = offset.toLong() + sizeBytes.toLong()
        if (end > buffer.capacity().toLong()) {
            return AudioTransportError.InvalidBufferRange
        }
        return AudioTransportError.None
    }

    private val AudioTimestampQuality.protocolCode: Int
        get() = when (this) {
            AudioTimestampQuality.Unavailable -> 0
            AudioTimestampQuality.AudioRecordTimestamp -> 1
            AudioTimestampQuality.EstimatedFromReadCompletion -> 2
        }

    private fun isUInt32(value: Long): Boolean = value in 0..UINT32_MAX

    private companion object {
        const val U16_MAX = 65_535
        const val UINT32_MAX = 0xFFFF_FFFFL
        val SUPPORTED_SAMPLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
        val SUPPORTED_FRAME_DURATIONS_US = setOf(2_500, 5_000, 10_000, 20_000)
    }
}
