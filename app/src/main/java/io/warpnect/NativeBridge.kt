package io.warpnect

import java.nio.ByteBuffer

internal object NativeBridge {
    init {
        System.loadLibrary("scl_core")
    }

    @JvmStatic
    private external fun nativeProtocolName(): String

    @JvmStatic
    private external fun nativeProtocolVersion(): Int

    @JvmStatic
    private external fun nativeProtocolAbiVersion(): Int

    @JvmStatic
    private external fun nativeVideoTransportCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialVideoSequence: Long,
        initialControlSequence: Long,
        initialFrameId: Long,
        retransmissionCacheSlots: Int,
        fecEnabled: Boolean,
        fecDataShards: Int,
        fecParityShards: Int,
        resyncRequestCooldownUs: Long,
    ): Long

    @JvmStatic
    private external fun nativeVideoTransportDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeVideoTransportSubmitConfig(
        handle: Long,
        width: Int,
        height: Int,
        codecSpecificData: Array<ByteArray>,
    ): Int

    @JvmStatic
    private external fun nativeVideoTransportSubmitAccessUnit(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        keyframe: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeVideoTransportHandleControlDatagram(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
    ): Int

    @JvmStatic
    private external fun nativeVideoTransportPumpControl(handle: Long, timeoutUs: Long): Int

    @JvmStatic
    private external fun nativeVideoTransportSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeVideoReceiverCreate(
        localAddress: String,
        localPort: Int,
        remoteAddress: String?,
        remotePort: Int,
        restrictRemoteEndpoint: Boolean,
        maxWireDatagramSize: Int,
        maxLogicalPayloadSize: Int,
        reassemblySlotCount: Int,
        readySlotCount: Int,
        lossSlotCount: Int,
        maxNacksPerPump: Int,
        reorderDelayUs: Long,
        renackIntervalUs: Long,
        maxNackAttempts: Int,
        initialControlSequence: Long,
        fecEnabled: Boolean,
        fecDataShards: Int,
        fecParityShards: Int,
        reassemblyTimeoutUs: Long,
        maxFrameRecoveryAgeUs: Long,
        resyncRequestCooldownUs: Long,
        clockSyncIntervalUs: Long,
        clockSyncSampleCapacity: Int,
    ): Long

    @JvmStatic
    private external fun nativeVideoReceiverDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeVideoReceiverPump(handle: Long, timeoutUs: Long): LongArray

    @JvmStatic
    private external fun nativeVideoReceiverRequestResync(
        handle: Long,
        reason: Int,
        generation: Long,
        nowUs: Long,
    ): Int

    @JvmStatic
    private external fun nativeVideoReceiverReadStreamConfigCsd(handle: Long): Array<ByteArray>?

    @JvmStatic
    private external fun nativeVideoReceiverFillDecoderInput(
        handle: Long,
        buffer: ByteBuffer,
        capacity: Int,
    ): LongArray

    @JvmStatic
    private external fun nativeVideoReceiverActivateConfigGeneration(handle: Long, generation: Long): Int

    @JvmStatic
    private external fun nativeVideoReceiverSetAwaitingKeyFrame(handle: Long, awaiting: Boolean)

    @JvmStatic
    private external fun nativeVideoReceiverSnapshot(handle: Long): LongArray

    fun sclInfo(): NativeSclInfo = NativeSclInfo(
        protocolName = nativeProtocolName(),
        protocolVersion = nativeProtocolVersion(),
        nativeBridgeAbiVersion = nativeProtocolAbiVersion(),
    )

    fun videoTransportCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialVideoSequence: Long,
        initialControlSequence: Long,
        initialFrameId: Long,
        retransmissionCacheSlots: Int,
        fecEnabled: Boolean,
        fecDataShards: Int,
        fecParityShards: Int,
        resyncRequestCooldownUs: Long,
    ): Long = nativeVideoTransportCreate(
        remoteAddress,
        remotePort,
        localPort,
        maxWireDatagramSize,
        initialVideoSequence,
        initialControlSequence,
        initialFrameId,
        retransmissionCacheSlots,
        fecEnabled,
        fecDataShards,
        fecParityShards,
        resyncRequestCooldownUs,
    )

    fun videoTransportDestroy(handle: Long): Int = nativeVideoTransportDestroy(handle)

    fun videoTransportSubmitConfig(handle: Long, width: Int, height: Int, codecSpecificData: Array<ByteArray>): Int =
        nativeVideoTransportSubmitConfig(handle, width, height, codecSpecificData)

    fun videoTransportSubmitAccessUnit(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        keyframe: Boolean,
    ): Int = nativeVideoTransportSubmitAccessUnit(
        handle,
        buffer,
        offset,
        size,
        presentationTimeUs,
        keyframe,
    )

    fun videoTransportHandleControlDatagram(handle: Long, buffer: ByteBuffer, offset: Int, size: Int): Int =
        nativeVideoTransportHandleControlDatagram(handle, buffer, offset, size)

    fun videoTransportPumpControl(handle: Long, timeoutUs: Long): Int =
        nativeVideoTransportPumpControl(handle, timeoutUs)

    fun videoTransportSnapshot(handle: Long): LongArray = nativeVideoTransportSnapshot(handle)

    fun videoReceiverCreate(
        localAddress: String,
        localPort: Int,
        remoteAddress: String?,
        remotePort: Int,
        restrictRemoteEndpoint: Boolean,
        maxWireDatagramSize: Int,
        maxLogicalPayloadSize: Int,
        reassemblySlotCount: Int,
        readySlotCount: Int,
        lossSlotCount: Int,
        maxNacksPerPump: Int,
        reorderDelayUs: Long,
        renackIntervalUs: Long,
        maxNackAttempts: Int,
        initialControlSequence: Long,
        fecEnabled: Boolean,
        fecDataShards: Int,
        fecParityShards: Int,
        reassemblyTimeoutUs: Long,
        maxFrameRecoveryAgeUs: Long,
        resyncRequestCooldownUs: Long,
        clockSyncIntervalUs: Long,
        clockSyncSampleCapacity: Int,
    ): Long = nativeVideoReceiverCreate(
        localAddress,
        localPort,
        remoteAddress,
        remotePort,
        restrictRemoteEndpoint,
        maxWireDatagramSize,
        maxLogicalPayloadSize,
        reassemblySlotCount,
        readySlotCount,
        lossSlotCount,
        maxNacksPerPump,
        reorderDelayUs,
        renackIntervalUs,
        maxNackAttempts,
        initialControlSequence,
        fecEnabled,
        fecDataShards,
        fecParityShards,
        reassemblyTimeoutUs,
        maxFrameRecoveryAgeUs,
        resyncRequestCooldownUs,
        clockSyncIntervalUs,
        clockSyncSampleCapacity,
    )

    fun videoReceiverDestroy(handle: Long): Int = nativeVideoReceiverDestroy(handle)

    fun videoReceiverPump(handle: Long, timeoutUs: Long): LongArray = nativeVideoReceiverPump(handle, timeoutUs)

    fun videoReceiverRequestResync(handle: Long, reason: Int, generation: Long, nowUs: Long): Int =
        nativeVideoReceiverRequestResync(handle, reason, generation, nowUs)

    fun videoReceiverReadStreamConfigCsd(handle: Long): Array<ByteArray>? {
        return nativeVideoReceiverReadStreamConfigCsd(handle)
    }

    fun videoReceiverFillDecoderInput(handle: Long, buffer: ByteBuffer, capacity: Int): LongArray =
        nativeVideoReceiverFillDecoderInput(handle, buffer, capacity)

    fun videoReceiverActivateConfigGeneration(handle: Long, generation: Long): Int =
        nativeVideoReceiverActivateConfigGeneration(handle, generation)

    fun videoReceiverSetAwaitingKeyFrame(handle: Long, awaiting: Boolean) =
        nativeVideoReceiverSetAwaitingKeyFrame(handle, awaiting)

    fun videoReceiverSnapshot(handle: Long): LongArray = nativeVideoReceiverSnapshot(handle)
}

internal data class NativeSclInfo(
    val protocolName: String,
    val protocolVersion: Int,
    val nativeBridgeAbiVersion: Int,
)
