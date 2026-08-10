package io.warpnect.video.transport

import io.warpnect.video.decoder.VideoDecoderInputSource

data class VideoReceiverRuntimeConfig(
    val localAddress: String = "0.0.0.0",
    val localPort: Int,
    val remoteAddress: String? = null,
    val remotePort: Int = 0,
    val restrictRemoteEndpoint: Boolean = false,
    val maxWireDatagramSize: Int,
    val maxLogicalPayloadSize: Int,
    val reassemblySlotCount: Int,
    val readySlotCount: Int,
    val lossSlotCount: Int,
    val maxNacksPerPump: Int,
    val reorderDelayUs: Long,
    val renackIntervalUs: Long,
    val maxNackAttempts: Int,
    val initialControlSequence: Long = 0,
    val fec: VideoTransportFecConfig = VideoTransportFecConfig.Disabled,
    val reassemblyTimeoutUs: Long,
)

enum class VideoReceiverRuntimeState {
    Closed,
    Stopped,
    Running,
    Error,
}

enum class VideoReceiverRuntimeEventType {
    None,
    StreamConfigReady,
    AccessUnitReady,
    Discontinuity,
    TransportError,
    Timeout,
    Stopped,
}

data class VideoReceiverStreamConfig(
    val codec: Int,
    val width: Int,
    val height: Int,
    val configGeneration: Long,
    val codecSpecificData: List<ByteArray>,
)

data class VideoReceiverAccessUnitReady(
    val configGeneration: Long,
    val frameId: Long,
    val presentationTimeUs: Long,
    val keyframe: Boolean,
)

data class VideoReceiverRuntimeEvent(
    val type: VideoReceiverRuntimeEventType,
    val error: VideoTransportError = VideoTransportError.None,
    val configGeneration: Long = 0,
    val frameId: Long = 0,
    val presentationTimeUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val keyframe: Boolean = false,
)

data class VideoReceiverRuntimeSnapshot(
    val state: VideoReceiverRuntimeState = VideoReceiverRuntimeState.Stopped,
    val awaitingKeyframe: Boolean = true,
    val activeConfigGeneration: Long = 0,
    val latestConfigGeneration: Long = 0,
    val nextControlSequence: Long = 0,
    val datagramsReceived: Long = 0,
    val videoDatagramsReceived: Long = 0,
    val fecParityReceived: Long = 0,
    val fecRecoveries: Long = 0,
    val nacksSent: Long = 0,
    val streamConfigsReceived: Long = 0,
    val accessUnitsCompleted: Long = 0,
    val accessUnitsDelivered: Long = 0,
    val nonKeyframesDroppedAwaitingKeyframe: Long = 0,
    val discontinuities: Long = 0,
    val reassemblyTimeouts: Long = 0,
    val reassemblyWindowFull: Long = 0,
    val readyWindowFull: Long = 0,
    val reassemblySlotsUsed: Long = 0,
    val readyAccessUnits: Long = 0,
    val lastPresentationTimeUs: Long = 0,
    val lastFrameId: Long = 0,
    val lastError: VideoTransportError = VideoTransportError.None,
)

data class VideoReceiverRuntimeResult(
    val error: VideoTransportError,
    val snapshot: VideoReceiverRuntimeSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoTransportError.None
}

interface VideoReceiverRuntimeListener {
    fun onStreamConfig(config: VideoReceiverStreamConfig) = Unit

    fun onAccessUnitReady(accessUnit: VideoReceiverAccessUnitReady) = Unit

    fun onDiscontinuity(error: VideoTransportError) = Unit

    fun onRuntimeError(error: VideoTransportError) = Unit
}

interface VideoReceiverRuntimeController : AutoCloseable {
    val inputSource: VideoDecoderInputSource

    fun open(config: VideoReceiverRuntimeConfig): VideoReceiverRuntimeResult

    fun start(listener: VideoReceiverRuntimeListener): VideoReceiverRuntimeResult

    fun pumpOnce(timeoutUs: Long): VideoReceiverRuntimeEvent

    fun activateConfigGeneration(generation: Long): VideoTransportError

    fun setAwaitingKeyFrame(awaiting: Boolean)

    fun stop(): VideoReceiverRuntimeResult

    fun snapshot(): VideoReceiverRuntimeSnapshot

    override fun close()
}
