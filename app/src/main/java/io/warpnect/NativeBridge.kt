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
    private external fun nativeAudioEncoderCreate(
        source: Int,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        bitrateBps: Int,
        bitrateMode: Int,
        complexity: Int,
    ): Long

    @JvmStatic
    private external fun nativeAudioDecoderCreate(
        source: Int,
        configGeneration: Long,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        lookaheadSamples: Int,
    ): Long

    @JvmStatic
    private external fun nativeAudioDecoderDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioDecoderOutputBuffer(handle: Long): ByteBuffer?

    @JvmStatic
    private external fun nativeAudioDecoderStart(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioDecoderDecode(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: Int,
        discontinuityBefore: Boolean,
    ): LongArray

    @JvmStatic
    private external fun nativeAudioDecoderConcealMissingFrame(
        handle: Long,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: Int,
    ): LongArray

    @JvmStatic
    private external fun nativeAudioDecoderStop(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioDecoderSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioEncoderDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioEncoderOutputBuffer(handle: Long): ByteBuffer?

    @JvmStatic
    private external fun nativeAudioEncoderStart(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioEncoderSubmitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: Int,
    ): LongArray

    @JvmStatic
    private external fun nativeAudioEncoderUpdateBitrate(handle: Long, bitrateBps: Int): Int

    @JvmStatic
    private external fun nativeAudioEncoderStop(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioEncoderSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioTransportCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialAudioSequence: Long,
        source: Int,
    ): Long

    @JvmStatic
    private external fun nativeAudioTransportDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioTransportSubmitConfig(
        handle: Long,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        lookaheadSamples: Int,
    ): Int

    @JvmStatic
    private external fun nativeAudioTransportResendConfig(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioTransportSubmitFrame(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: Int,
        discontinuityBefore: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeAudioTransportSnapshot(handle: Long): LongArray

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

    fun audioEncoderCreate(
        source: Int,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        bitrateBps: Int,
        bitrateMode: Int,
        complexity: Int,
    ): Long = nativeAudioEncoderCreate(
        source,
        sampleRateHz,
        channelCount,
        frameDurationUs,
        bitrateBps,
        bitrateMode,
        complexity,
    )

    fun audioDecoderCreate(
        source: Int,
        configGeneration: Long,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        lookaheadSamples: Int,
    ): Long = nativeAudioDecoderCreate(
        source,
        configGeneration,
        sampleRateHz,
        channelCount,
        frameDurationUs,
        lookaheadSamples,
    )

    fun audioDecoderDestroy(handle: Long): Int = nativeAudioDecoderDestroy(handle)

    fun audioDecoderOutputBuffer(handle: Long): ByteBuffer? = nativeAudioDecoderOutputBuffer(handle)

    fun audioDecoderStart(handle: Long): Int = nativeAudioDecoderStart(handle)

    fun audioDecoderDecode(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: Int,
        discontinuityBefore: Boolean,
    ): LongArray = nativeAudioDecoderDecode(
        handle,
        buffer,
        offset,
        size,
        configGeneration,
        firstFramePosition,
        captureTimeUs,
        timestampQuality,
        discontinuityBefore,
    )

    fun audioDecoderConcealMissingFrame(
        handle: Long,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: Int,
    ): LongArray = nativeAudioDecoderConcealMissingFrame(
        handle,
        configGeneration,
        firstFramePosition,
        captureTimeUs,
        timestampQuality,
    )

    fun audioDecoderStop(handle: Long): Int = nativeAudioDecoderStop(handle)

    fun audioDecoderSnapshot(handle: Long): LongArray = nativeAudioDecoderSnapshot(handle)

    fun audioEncoderDestroy(handle: Long): Int = nativeAudioEncoderDestroy(handle)

    fun audioEncoderOutputBuffer(handle: Long): ByteBuffer? = nativeAudioEncoderOutputBuffer(handle)

    fun audioEncoderStart(handle: Long): Int = nativeAudioEncoderStart(handle)

    fun audioEncoderSubmitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: Int,
    ): LongArray = nativeAudioEncoderSubmitPcm(
        handle,
        buffer,
        offset,
        size,
        firstFramePosition,
        captureTimeNs,
        timestampQuality,
    )

    fun audioEncoderUpdateBitrate(handle: Long, bitrateBps: Int): Int =
        nativeAudioEncoderUpdateBitrate(handle, bitrateBps)

    fun audioEncoderStop(handle: Long): LongArray = nativeAudioEncoderStop(handle)

    fun audioEncoderSnapshot(handle: Long): LongArray = nativeAudioEncoderSnapshot(handle)

    fun audioTransportCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialAudioSequence: Long,
        source: Int,
    ): Long = nativeAudioTransportCreate(
        remoteAddress,
        remotePort,
        localPort,
        maxWireDatagramSize,
        initialAudioSequence,
        source,
    )

    fun audioTransportDestroy(handle: Long): Int = nativeAudioTransportDestroy(handle)

    fun audioTransportSubmitConfig(
        handle: Long,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        lookaheadSamples: Int,
    ): Int = nativeAudioTransportSubmitConfig(
        handle,
        sampleRateHz,
        channelCount,
        frameDurationUs,
        lookaheadSamples,
    )

    fun audioTransportResendConfig(handle: Long): Int = nativeAudioTransportResendConfig(handle)

    fun audioTransportSubmitFrame(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: Int,
        discontinuityBefore: Boolean,
    ): Int = nativeAudioTransportSubmitFrame(
        handle,
        buffer,
        offset,
        size,
        firstFramePosition,
        captureTimeNs,
        timestampQuality,
        discontinuityBefore,
    )

    fun audioTransportSnapshot(handle: Long): LongArray = nativeAudioTransportSnapshot(handle)

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
