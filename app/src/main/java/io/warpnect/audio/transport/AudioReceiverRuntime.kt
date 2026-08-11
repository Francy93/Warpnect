package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioCodec
import java.nio.ByteBuffer

data class AudioReceiverRuntimeConfig(
    val source: AudioCaptureSource,
    val localAddress: String = "0.0.0.0",
    val localPort: Int,
    val remoteAddress: String? = null,
    val remotePort: Int = 0,
    val restrictRemoteEndpoint: Boolean = false,
    val maxWireDatagramSize: Int,
    val maxLogicalAudioPayloadSize: Int = DEFAULT_MAX_LOGICAL_AUDIO_PAYLOAD_SIZE,
    val reassemblySlotCount: Int = DEFAULT_REASSEMBLY_SLOT_COUNT,
    val readySlotCount: Int = DEFAULT_READY_SLOT_COUNT,
    val reassemblyTimeoutUs: Long = DEFAULT_REASSEMBLY_TIMEOUT_US,
) {
    companion object {
        const val DEFAULT_MAX_LOGICAL_AUDIO_PAYLOAD_SIZE = 4096
        const val DEFAULT_REASSEMBLY_SLOT_COUNT = 4
        const val DEFAULT_READY_SLOT_COUNT = 4
        const val DEFAULT_REASSEMBLY_TIMEOUT_US = 20_000L
    }
}

enum class AudioReceiverRuntimeState {
    Closed,
    Stopped,
    Running,
    Error,
}

enum class AudioReceiverRuntimeEventType {
    None,
    StreamConfigReady,
    AudioFrameReady,
    TransportError,
    Timeout,
    Stopped,
}

data class AudioReceiverStreamConfig(
    val codec: AudioCodec = AudioCodec.Opus,
    val source: AudioCaptureSource,
    val configGeneration: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val frameDurationUs: Int,
    val lookaheadSamples: Int,
)

data class AudioReceiverFrameReady(
    val slotIndex: Int,
    val encodedOffset: Int,
    val encodedSizeBytes: Int,
    val configGeneration: Long,
    val firstFramePosition: Long,
    val captureTimeUs: Long,
    val timestampQuality: AudioTimestampQuality,
    val discontinuityBefore: Boolean,
)

data class AudioReceiverRuntimeEvent(
    val type: AudioReceiverRuntimeEventType,
    val error: AudioTransportError = AudioTransportError.None,
    val streamConfig: AudioReceiverStreamConfig? = null,
    val frame: AudioReceiverFrameReady? = null,
)

data class AudioReceiverRuntimeSnapshot(
    val state: AudioReceiverRuntimeState = AudioReceiverRuntimeState.Stopped,
    val source: AudioCaptureSource? = null,
    val latestConfigGeneration: Long = 0,
    val datagramsReceived: Long = 0,
    val audioDatagramsReceived: Long = 0,
    val unsupportedPayloadDatagrams: Long = 0,
    val streamConfigsReceived: Long = 0,
    val audioFramesCompleted: Long = 0,
    val audioFramesDelivered: Long = 0,
    val malformedPayloads: Long = 0,
    val reassemblyTimeouts: Long = 0,
    val reassemblyWindowFull: Long = 0,
    val readyWindowFull: Long = 0,
    val staleFramesReleased: Long = 0,
    val reassemblySlotsUsed: Long = 0,
    val readySlotsUsed: Long = 0,
    val reassemblySlotsHighWater: Long = 0,
    val readySlotsHighWater: Long = 0,
    val lastReassemblyLatencyUs: Long = 0,
    val maxReassemblyLatencyUs: Long = 0,
    val lastReadyWaitUs: Long = 0,
    val maxReadyWaitUs: Long = 0,
    val lastFramePosition: Long = 0,
    val lastCaptureTimeUs: Long = 0,
    val lastError: AudioTransportError = AudioTransportError.None,
)

data class AudioReceiverRuntimeResult(
    val error: AudioTransportError,
    val snapshot: AudioReceiverRuntimeSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AudioTransportError.None
}

interface AudioReceiverRuntimeListener {
    fun onStreamConfig(config: AudioReceiverStreamConfig) = Unit

    fun onAudioFrameReady(frame: AudioReceiverFrameReady) = Unit

    fun onRuntimeError(error: AudioTransportError) = Unit
}

interface AudioReceiverRuntimeController : AutoCloseable {
    fun open(config: AudioReceiverRuntimeConfig): AudioReceiverRuntimeResult

    fun start(listener: AudioReceiverRuntimeListener): AudioReceiverRuntimeResult

    fun pumpOnce(timeoutUs: Long): AudioReceiverRuntimeEvent

    fun readyBuffer(slotIndex: Int): ByteBuffer?

    fun releaseReadySlot(slotIndex: Int): AudioTransportError

    fun stop(): AudioReceiverRuntimeResult

    fun snapshot(): AudioReceiverRuntimeSnapshot

    override fun close()
}
