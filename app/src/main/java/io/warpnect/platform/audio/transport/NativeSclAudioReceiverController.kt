package io.warpnect.platform.audio.transport

import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.transport.AudioReceiverFrameReady
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import io.warpnect.audio.transport.AudioReceiverRuntimeController
import io.warpnect.audio.transport.AudioReceiverRuntimeEvent
import io.warpnect.audio.transport.AudioReceiverRuntimeEventType
import io.warpnect.audio.transport.AudioReceiverRuntimeListener
import io.warpnect.audio.transport.AudioReceiverRuntimeResult
import io.warpnect.audio.transport.AudioReceiverRuntimeSnapshot
import io.warpnect.audio.transport.AudioReceiverRuntimeState
import io.warpnect.audio.transport.AudioReceiverStreamConfig
import io.warpnect.audio.transport.AudioTransportError
import io.warpnect.platform.session.channel.markNativeEndpointAdopted
import io.warpnect.platform.session.channel.nativeEndpointHandleForLiveRebind
import io.warpnect.session.setup.ChannelEndpointLease
import java.nio.ByteBuffer

class NativeSclAudioReceiverController(
    private val pumpTimeoutUs: Long = DEFAULT_PUMP_TIMEOUT_US,
) : AudioReceiverRuntimeController {
    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    private var currentConfig: AudioReceiverRuntimeConfig? = null
    private var localSnapshot = AudioReceiverRuntimeSnapshot()

    /** RFC-005I adopts the stopped RFC-005G protected receiver without rebinding a UDP port. */
    internal fun adoptPreparedTransport(handle: Long): AudioTransportError {
        if (handle == 0L || nativeHandle != 0L || running) return AudioTransportError.InvalidHandle
        nativeHandle = handle
        localSnapshot = snapshot().copy(state = AudioReceiverRuntimeState.Stopped)
        return AudioTransportError.None
    }

    /** RFC-005H replaces only the bound socket/peer endpoint of this live receiver. */
    internal fun rebindLiveTransport(
        localEndpoint: ChannelEndpointLease,
        remoteAddress: String,
        remotePort: Int,
    ): Boolean {
        val handle = nativeHandle
        val endpointHandle = localEndpoint.nativeEndpointHandleForLiveRebind()
        val rebound = handle != 0L && endpointHandle != 0L &&
            AudioTransportError.fromNativeCode(
                NativeBridge.audioReceiverRebind(
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

    override fun open(config: AudioReceiverRuntimeConfig): AudioReceiverRuntimeResult {
        if (nativeHandle != 0L) {
            currentConfig = config
            return AudioReceiverRuntimeResult(AudioTransportError.None, snapshot())
        }
        val validation = validateConfig(config)
        if (validation != AudioTransportError.None) {
            localSnapshot = localSnapshot.copy(
                state = AudioReceiverRuntimeState.Error,
                source = config.source,
                lastError = validation,
            )
            return AudioReceiverRuntimeResult(validation, localSnapshot)
        }
        nativeHandle = NativeBridge.audioReceiverCreate(
            localAddress = config.localAddress,
            localPort = config.localPort,
            remoteAddress = config.remoteAddress,
            remotePort = config.remotePort,
            restrictRemoteEndpoint = config.restrictRemoteEndpoint,
            maxWireDatagramSize = config.maxWireDatagramSize,
            maxLogicalAudioPayloadSize = config.maxLogicalAudioPayloadSize,
            reassemblySlotCount = config.reassemblySlotCount,
            readySlotCount = config.readySlotCount,
            reassemblyTimeoutUs = config.reassemblyTimeoutUs,
            source = config.source.ordinal,
        )
        if (nativeHandle == 0L) {
            localSnapshot = localSnapshot.copy(
                state = AudioReceiverRuntimeState.Error,
                source = config.source,
                lastError = AudioTransportError.UdpOpenFailed,
            )
            return AudioReceiverRuntimeResult(AudioTransportError.UdpOpenFailed, localSnapshot)
        }
        currentConfig = config
        localSnapshot = snapshot().copy(state = AudioReceiverRuntimeState.Stopped)
        return AudioReceiverRuntimeResult(AudioTransportError.None, localSnapshot)
    }

    override fun start(listener: AudioReceiverRuntimeListener): AudioReceiverRuntimeResult {
        val handle = nativeHandle
        if (handle == 0L) {
            return AudioReceiverRuntimeResult(AudioTransportError.InvalidHandle, localSnapshot)
        }
        if (running) {
            return AudioReceiverRuntimeResult(AudioTransportError.None, snapshot())
        }
        running = true
        worker = Thread({
            while (running) {
                val event = pumpOnce(pumpTimeoutUs)
                when (event.type) {
                    AudioReceiverRuntimeEventType.StreamConfigReady -> {
                        event.streamConfig?.let(listener::onStreamConfig)
                    }
                    AudioReceiverRuntimeEventType.AudioFrameReady -> {
                        event.frame?.let(listener::onAudioFrameReady)
                    }
                    AudioReceiverRuntimeEventType.TransportError -> {
                        listener.onRuntimeError(event.error)
                    }
                    else -> Unit
                }
            }
        }, THREAD_NAME).apply { start() }
        localSnapshot = snapshot().copy(state = AudioReceiverRuntimeState.Running)
        return AudioReceiverRuntimeResult(AudioTransportError.None, localSnapshot)
    }

    override fun pumpOnce(timeoutUs: Long): AudioReceiverRuntimeEvent {
        val handle = nativeHandle
        if (handle == 0L || timeoutUs < 0L) {
            return AudioReceiverRuntimeEvent(
                type = AudioReceiverRuntimeEventType.TransportError,
                error = AudioTransportError.InvalidHandle,
            )
        }
        return NativeBridge.audioReceiverPump(handle, timeoutUs).toReceiverEvent(currentConfig?.source)
    }

    override fun readyBuffer(slotIndex: Int): ByteBuffer? {
        val handle = nativeHandle
        if (handle == 0L || slotIndex < 0) {
            return null
        }
        return NativeBridge.audioReceiverReadyBuffer(handle, slotIndex)
    }

    override fun releaseReadySlot(slotIndex: Int): AudioTransportError {
        val handle = nativeHandle
        if (handle == 0L || slotIndex < 0) {
            return remember(AudioTransportError.InvalidHandle)
        }
        return remember(
            AudioTransportError.fromNativeCode(NativeBridge.audioReceiverReleaseSlot(handle, slotIndex)),
        )
    }

    override fun stop(): AudioReceiverRuntimeResult {
        running = false
        val currentWorker = worker
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.join(STOP_JOIN_TIMEOUT_MS)
        }
        worker = null
        localSnapshot = snapshot().copy(state = AudioReceiverRuntimeState.Stopped)
        return AudioReceiverRuntimeResult(AudioTransportError.None, localSnapshot)
    }

    override fun snapshot(): AudioReceiverRuntimeSnapshot {
        val handle = nativeHandle
        if (handle == 0L) {
            return localSnapshot
        }
        localSnapshot = NativeBridge.audioReceiverSnapshot(handle).toReceiverSnapshot()
        return if (running) {
            localSnapshot.copy(state = AudioReceiverRuntimeState.Running)
        } else {
            localSnapshot.copy(state = AudioReceiverRuntimeState.Stopped)
        }
    }

    override fun close() {
        stop()
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            NativeBridge.audioReceiverDestroy(handle)
        }
        currentConfig = null
        localSnapshot = localSnapshot.copy(state = AudioReceiverRuntimeState.Closed)
    }

    private fun validateConfig(config: AudioReceiverRuntimeConfig): AudioTransportError = when {
        config.localPort !in 0..U16_MAX -> AudioTransportError.UdpBindFailed
        config.remotePort !in 0..U16_MAX -> AudioTransportError.UdpBindFailed
        config.maxWireDatagramSize <= 0 -> AudioTransportError.InvalidDatagramBudget
        config.maxLogicalAudioPayloadSize <= 0 -> AudioTransportError.PayloadTooLarge
        config.reassemblySlotCount <= 0 -> AudioTransportError.ReassemblyWindowFull
        config.readySlotCount <= 0 -> AudioTransportError.ReadyWindowFull
        config.reassemblyTimeoutUs < 0L -> AudioTransportError.Timeout
        else -> AudioTransportError.None
    }

    private fun remember(error: AudioTransportError): AudioTransportError {
        localSnapshot = snapshot().copy(
            state = if (error == AudioTransportError.None) {
                localSnapshot.state
            } else {
                AudioReceiverRuntimeState.Error
            },
            lastError = error,
        )
        return error
    }

    private fun LongArray.toReceiverEvent(source: AudioCaptureSource?): AudioReceiverRuntimeEvent {
        val type = AudioReceiverRuntimeEventType.entries.getOrElse(getOrElse(0) { 0L }.toInt()) {
            AudioReceiverRuntimeEventType.TransportError
        }
        val error = AudioTransportError.fromNativeCode(getOrElse(1) { 0L }.toInt())
        return when (type) {
            AudioReceiverRuntimeEventType.StreamConfigReady -> AudioReceiverRuntimeEvent(
                type = type,
                error = error,
                streamConfig = AudioReceiverStreamConfig(
                    codec = AudioCodec.Opus,
                    source = source ?: AudioCaptureSource.SystemAudio,
                    configGeneration = getOrElse(2) { 0L },
                    sampleRateHz = getOrElse(3) { 0L }.toInt(),
                    channelCount = getOrElse(4) { 0L }.toInt(),
                    frameDurationUs = getOrElse(5) { 0L }.toInt(),
                    lookaheadSamples = getOrElse(6) { 0L }.toInt(),
                ),
            )
            AudioReceiverRuntimeEventType.AudioFrameReady -> AudioReceiverRuntimeEvent(
                type = type,
                error = error,
                frame = AudioReceiverFrameReady(
                    slotIndex = getOrElse(7) { 0L }.toInt(),
                    encodedOffset = getOrElse(8) { 0L }.toInt(),
                    encodedSizeBytes = getOrElse(9) { 0L }.toInt(),
                    configGeneration = getOrElse(2) { 0L },
                    firstFramePosition = getOrElse(10) { 0L },
                    captureTimeUs = getOrElse(11) { 0L },
                    timestampQuality = timestampQualityFromWire(getOrElse(12) { 0L }.toInt()),
                    discontinuityBefore = getOrElse(13) { 0L } == 1L,
                ),
            )
            else -> AudioReceiverRuntimeEvent(type = type, error = error)
        }
    }

    private fun LongArray.toReceiverSnapshot(): AudioReceiverRuntimeSnapshot {
        val closed = getOrElse(2) { 0L } == 1L
        val opened = getOrElse(1) { 0L } == 1L
        val error = AudioTransportError.fromNativeCode(getOrElse(25) { 0L }.toInt())
        val state = when {
            closed -> AudioReceiverRuntimeState.Closed
            error != AudioTransportError.None -> AudioReceiverRuntimeState.Error
            opened -> AudioReceiverRuntimeState.Stopped
            else -> AudioReceiverRuntimeState.Stopped
        }
        return AudioReceiverRuntimeSnapshot(
            state = state,
            source = sourceFromPayloadType(getOrElse(0) { 0L }.toInt()),
            latestConfigGeneration = getOrElse(3) { 0L },
            datagramsReceived = getOrElse(4) { 0L },
            audioDatagramsReceived = getOrElse(5) { 0L },
            unsupportedPayloadDatagrams = getOrElse(6) { 0L },
            streamConfigsReceived = getOrElse(7) { 0L },
            audioFramesCompleted = getOrElse(8) { 0L },
            audioFramesDelivered = getOrElse(9) { 0L },
            malformedPayloads = getOrElse(10) { 0L },
            reassemblyTimeouts = getOrElse(11) { 0L },
            reassemblyWindowFull = getOrElse(12) { 0L },
            readyWindowFull = getOrElse(13) { 0L },
            staleFramesReleased = getOrElse(14) { 0L },
            reassemblySlotsUsed = getOrElse(15) { 0L },
            readySlotsUsed = getOrElse(16) { 0L },
            reassemblySlotsHighWater = getOrElse(17) { 0L },
            readySlotsHighWater = getOrElse(18) { 0L },
            lastReassemblyLatencyUs = getOrElse(19) { 0L },
            maxReassemblyLatencyUs = getOrElse(20) { 0L },
            lastReadyWaitUs = getOrElse(21) { 0L },
            maxReadyWaitUs = getOrElse(22) { 0L },
            lastFramePosition = getOrElse(23) { 0L },
            lastCaptureTimeUs = getOrElse(24) { 0L },
            lastError = error,
        )
    }

    private fun timestampQualityFromWire(value: Int): AudioTimestampQuality = when (value) {
        1 -> AudioTimestampQuality.AudioRecordTimestamp
        2 -> AudioTimestampQuality.EstimatedFromReadCompletion
        else -> AudioTimestampQuality.Unavailable
    }

    private fun sourceFromPayloadType(payloadType: Int): AudioCaptureSource? = when (payloadType) {
        2 -> AudioCaptureSource.SystemAudio
        3 -> AudioCaptureSource.MicrophoneAudio
        else -> null
    }

    private companion object {
        const val THREAD_NAME = "WarpnectAudioReceiver"
        const val DEFAULT_PUMP_TIMEOUT_US = 20_000L
        const val STOP_JOIN_TIMEOUT_MS = 250L
        const val U16_MAX = 65_535
    }
}
