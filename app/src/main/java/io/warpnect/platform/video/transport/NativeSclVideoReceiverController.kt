package io.warpnect.platform.video.transport

import io.warpnect.NativeBridge
import io.warpnect.video.decoder.VideoDecoderError
import io.warpnect.video.decoder.VideoDecoderInputResult
import io.warpnect.video.decoder.VideoDecoderInputSource
import io.warpnect.video.transport.VideoReceiverAccessUnitReady
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoReceiverRuntimeController
import io.warpnect.video.transport.VideoReceiverRuntimeEvent
import io.warpnect.video.transport.VideoReceiverRuntimeEventType
import io.warpnect.video.transport.VideoReceiverRuntimeListener
import io.warpnect.video.transport.VideoReceiverRuntimeResult
import io.warpnect.video.transport.VideoReceiverRuntimeSnapshot
import io.warpnect.video.transport.VideoReceiverRuntimeState
import io.warpnect.video.transport.VideoReceiverStreamConfig
import io.warpnect.video.transport.VideoTransportError
import java.nio.ByteBuffer

class NativeSclVideoReceiverController(
    private val pumpTimeoutUs: Long = DEFAULT_PUMP_TIMEOUT_US,
) : VideoReceiverRuntimeController {
    @Volatile
    private var nativeHandle: Long = 0

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    private var localSnapshot = VideoReceiverRuntimeSnapshot()

    override val inputSource: VideoDecoderInputSource = object : VideoDecoderInputSource {
        override fun fillInput(target: ByteBuffer, capacity: Int): VideoDecoderInputResult =
            fillDecoderInput(target, capacity)
    }

    override fun open(config: VideoReceiverRuntimeConfig): VideoReceiverRuntimeResult {
        if (nativeHandle != 0L) {
            return VideoReceiverRuntimeResult(VideoTransportError.None, snapshot())
        }
        val validation = validateConfig(config)
        if (validation != VideoTransportError.None) {
            localSnapshot = localSnapshot.copy(
                state = VideoReceiverRuntimeState.Error,
                lastError = validation,
            )
            return VideoReceiverRuntimeResult(validation, localSnapshot)
        }
        nativeHandle = NativeBridge.videoReceiverCreate(
            localAddress = config.localAddress,
            localPort = config.localPort,
            remoteAddress = config.remoteAddress,
            remotePort = config.remotePort,
            restrictRemoteEndpoint = config.restrictRemoteEndpoint,
            maxWireDatagramSize = config.maxWireDatagramSize,
            maxLogicalPayloadSize = config.maxLogicalPayloadSize,
            reassemblySlotCount = config.reassemblySlotCount,
            readySlotCount = config.readySlotCount,
            lossSlotCount = config.lossSlotCount,
            maxNacksPerPump = config.maxNacksPerPump,
            reorderDelayUs = config.reorderDelayUs,
            renackIntervalUs = config.renackIntervalUs,
            maxNackAttempts = config.maxNackAttempts,
            initialControlSequence = config.initialControlSequence,
            fecEnabled = config.fec.enabled,
            fecDataShards = config.fec.dataShards,
            fecParityShards = config.fec.parityShards,
            reassemblyTimeoutUs = config.reassemblyTimeoutUs,
        )
        if (nativeHandle == 0L) {
            localSnapshot = localSnapshot.copy(
                state = VideoReceiverRuntimeState.Error,
                lastError = VideoTransportError.UdpOpenFailed,
            )
            return VideoReceiverRuntimeResult(VideoTransportError.UdpOpenFailed, localSnapshot)
        }
        localSnapshot = snapshot().copy(state = VideoReceiverRuntimeState.Stopped)
        return VideoReceiverRuntimeResult(VideoTransportError.None, localSnapshot)
    }

    override fun start(listener: VideoReceiverRuntimeListener): VideoReceiverRuntimeResult {
        val handle = nativeHandle
        if (handle == 0L) {
            return VideoReceiverRuntimeResult(VideoTransportError.InvalidHandle, localSnapshot)
        }
        if (running) {
            return VideoReceiverRuntimeResult(VideoTransportError.None, snapshot())
        }
        running = true
        worker = Thread({
            while (running) {
                val event = pumpOnce(pumpTimeoutUs)
                when (event.type) {
                    VideoReceiverRuntimeEventType.StreamConfigReady -> {
                        val config = readStreamConfig(event)
                        if (config != null) {
                            listener.onStreamConfig(config)
                        }
                    }
                    VideoReceiverRuntimeEventType.AccessUnitReady -> {
                        listener.onAccessUnitReady(
                            VideoReceiverAccessUnitReady(
                                configGeneration = event.configGeneration,
                                frameId = event.frameId,
                                presentationTimeUs = event.presentationTimeUs,
                                keyframe = event.keyframe,
                            ),
                        )
                    }
                    VideoReceiverRuntimeEventType.Discontinuity -> {
                        listener.onDiscontinuity(event.error)
                    }
                    VideoReceiverRuntimeEventType.TransportError -> {
                        listener.onRuntimeError(event.error)
                    }
                    else -> Unit
                }
            }
        }, THREAD_NAME).apply { start() }
        localSnapshot = snapshot().copy(state = VideoReceiverRuntimeState.Running)
        return VideoReceiverRuntimeResult(VideoTransportError.None, localSnapshot)
    }

    override fun pumpOnce(timeoutUs: Long): VideoReceiverRuntimeEvent {
        val handle = nativeHandle
        if (handle == 0L || timeoutUs < 0L) {
            return VideoReceiverRuntimeEvent(
                type = VideoReceiverRuntimeEventType.TransportError,
                error = VideoTransportError.InvalidHandle,
            )
        }
        return NativeBridge.videoReceiverPump(handle, timeoutUs).toReceiverEvent()
    }

    override fun activateConfigGeneration(generation: Long): VideoTransportError {
        val handle = nativeHandle
        if (handle == 0L) {
            return remember(VideoTransportError.InvalidHandle)
        }
        return remember(
            VideoTransportError.fromNativeCode(
                NativeBridge.videoReceiverActivateConfigGeneration(handle, generation),
            ),
        )
    }

    override fun setAwaitingKeyFrame(awaiting: Boolean) {
        val handle = nativeHandle
        if (handle != 0L) {
            NativeBridge.videoReceiverSetAwaitingKeyFrame(handle, awaiting)
        }
    }

    override fun stop(): VideoReceiverRuntimeResult {
        running = false
        val currentWorker = worker
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.join(STOP_JOIN_TIMEOUT_MS)
        }
        worker = null
        localSnapshot = snapshot().copy(state = VideoReceiverRuntimeState.Stopped)
        return VideoReceiverRuntimeResult(VideoTransportError.None, localSnapshot)
    }

    override fun snapshot(): VideoReceiverRuntimeSnapshot {
        val handle = nativeHandle
        if (handle == 0L) {
            return localSnapshot
        }
        localSnapshot = NativeBridge.videoReceiverSnapshot(handle).toReceiverSnapshot()
        return if (running) {
            localSnapshot.copy(state = VideoReceiverRuntimeState.Running)
        } else {
            localSnapshot.copy(state = VideoReceiverRuntimeState.Stopped)
        }
    }

    override fun close() {
        stop()
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) {
            NativeBridge.videoReceiverDestroy(handle)
        }
        localSnapshot = localSnapshot.copy(state = VideoReceiverRuntimeState.Closed)
    }

    private fun fillDecoderInput(target: ByteBuffer, capacity: Int): VideoDecoderInputResult {
        val handle = nativeHandle
        if (handle == 0L) {
            return VideoDecoderInputResult.Failure(VideoDecoderError.InvalidInputResult)
        }
        if (!target.isDirect) {
            return VideoDecoderInputResult.Failure(VideoDecoderError.InputBufferUnavailable)
        }
        val result = NativeBridge.videoReceiverFillDecoderInput(handle, target, capacity)
        val error = VideoTransportError.fromNativeCode(
            result.getOrElse(0) {
                VideoTransportError.InvalidHandle.ordinal.toLong()
            }.toInt(),
        )
        return when {
            error == VideoTransportError.NoData -> VideoDecoderInputResult.NoData
            error == VideoTransportError.InputBufferTooSmall -> VideoDecoderInputResult.Failure(
                VideoDecoderError.InputBufferTooSmall,
            )
            error != VideoTransportError.None -> VideoDecoderInputResult.Failure(VideoDecoderError.InvalidInputResult)
            result.getOrElse(1) { 0L } == 1L -> VideoDecoderInputResult.AccessUnit(
                size = result[2].toInt(),
                presentationTimeUs = result[3],
                configGeneration = result[4],
                frameId = result[5],
                isKeyFrame = result[6] == 1L,
            )
            else -> VideoDecoderInputResult.NoData
        }
    }

    private fun readStreamConfig(event: VideoReceiverRuntimeEvent): VideoReceiverStreamConfig? {
        val handle = nativeHandle
        if (handle == 0L) {
            return null
        }
        val csd = NativeBridge.videoReceiverReadStreamConfigCsd(handle)?.map { it.copyOf() }
            ?: return null
        return VideoReceiverStreamConfig(
            codec = AVC_CODEC_ID,
            width = event.width,
            height = event.height,
            configGeneration = event.configGeneration,
            codecSpecificData = csd,
        )
    }

    private fun remember(error: VideoTransportError): VideoTransportError {
        localSnapshot = snapshot().copy(
            state = if (error == VideoTransportError.None) {
                localSnapshot.state
            } else {
                VideoReceiverRuntimeState.Error
            },
            lastError = error,
        )
        return error
    }

    private fun validateConfig(config: VideoReceiverRuntimeConfig): VideoTransportError = when {
        config.localPort !in 0..U16_MAX -> VideoTransportError.UdpBindFailed
        config.remotePort !in 0..U16_MAX -> VideoTransportError.UdpBindFailed
        config.maxWireDatagramSize <= 0 -> VideoTransportError.InvalidDatagramBudget
        config.maxLogicalPayloadSize <= 0 -> VideoTransportError.PayloadTooLarge
        config.reassemblySlotCount <= 0 -> VideoTransportError.ReassemblyWindowFull
        config.readySlotCount <= 0 -> VideoTransportError.ReadyWindowFull
        config.lossSlotCount <= 0 -> VideoTransportError.NackDecodeFailed
        config.maxNacksPerPump <= 0 -> VideoTransportError.NackDecodeFailed
        config.reorderDelayUs < 0L -> VideoTransportError.NackDecodeFailed
        config.renackIntervalUs < 0L -> VideoTransportError.NackDecodeFailed
        config.maxNackAttempts <= 0 -> VideoTransportError.NackDecodeFailed
        config.initialControlSequence !in 0..UINT32_MAX -> VideoTransportError.InvalidFrameId
        config.reassemblyTimeoutUs < 0L -> VideoTransportError.ReassemblyTimeout
        config.fec.enabled && (config.fec.dataShards <= 0 || config.fec.parityShards <= 0) ->
            VideoTransportError.FecConfigurationInvalid
        else -> VideoTransportError.None
    }

    private fun LongArray.toReceiverEvent(): VideoReceiverRuntimeEvent {
        val type = VideoReceiverRuntimeEventType.entries.getOrElse(getOrElse(0) { 0L }.toInt()) {
            VideoReceiverRuntimeEventType.TransportError
        }
        return VideoReceiverRuntimeEvent(
            type = type,
            error = VideoTransportError.fromNativeCode(getOrElse(1) { 0L }.toInt()),
            configGeneration = getOrElse(2) { 0L },
            frameId = getOrElse(3) { 0L },
            presentationTimeUs = getOrElse(4) { 0L },
            width = getOrElse(5) { 0L }.toInt(),
            height = getOrElse(6) { 0L }.toInt(),
            keyframe = getOrElse(7) { 0L } == 1L,
        )
    }

    private fun LongArray.toReceiverSnapshot(): VideoReceiverRuntimeSnapshot = VideoReceiverRuntimeSnapshot(
        state = if (getOrElse(1) { 0L } == 1L) {
            VideoReceiverRuntimeState.Closed
        } else {
            VideoReceiverRuntimeState.Stopped
        },
        awaitingKeyframe = getOrElse(2) { 1L } == 1L,
        activeConfigGeneration = getOrElse(3) { 0L },
        latestConfigGeneration = getOrElse(4) { 0L },
        nextControlSequence = getOrElse(5) { 0L },
        datagramsReceived = getOrElse(6) { 0L },
        videoDatagramsReceived = getOrElse(7) { 0L },
        fecParityReceived = getOrElse(8) { 0L },
        fecRecoveries = getOrElse(9) { 0L },
        nacksSent = getOrElse(10) { 0L },
        streamConfigsReceived = getOrElse(11) { 0L },
        accessUnitsCompleted = getOrElse(12) { 0L },
        accessUnitsDelivered = getOrElse(13) { 0L },
        nonKeyframesDroppedAwaitingKeyframe = getOrElse(14) { 0L },
        discontinuities = getOrElse(15) { 0L },
        reassemblyTimeouts = getOrElse(16) { 0L },
        reassemblyWindowFull = getOrElse(17) { 0L },
        readyWindowFull = getOrElse(18) { 0L },
        reassemblySlotsUsed = getOrElse(19) { 0L },
        readyAccessUnits = getOrElse(20) { 0L },
        lastPresentationTimeUs = getOrElse(21) { 0L },
        lastFrameId = getOrElse(22) { 0L },
        lastError = VideoTransportError.fromNativeCode(getOrElse(23) { 0L }.toInt()),
    )

    private companion object {
        const val AVC_CODEC_ID = 1
        const val THREAD_NAME = "WarpnectVideoReceiver"
        const val DEFAULT_PUMP_TIMEOUT_US = 20_000L
        const val STOP_JOIN_TIMEOUT_MS = 250L
        const val U16_MAX = 65_535
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
