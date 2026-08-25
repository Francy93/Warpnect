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
    private external fun nativeRuntimeTelemetrySnapshot(outputBuffer: ByteBuffer): LongArray

    @JvmStatic
    private external fun nativeDiagnosticEventSnapshot(outputBuffer: ByteBuffer, cursor: Long, limit: Int): LongArray

    @JvmStatic
    private external fun nativeRuntimeTelemetryRegisterSource(
        sourceId: Long,
        metricIds: ShortArray,
        metricKinds: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativeRuntimeTelemetryUnregisterSource(sourceId: Long)

    @JvmStatic
    private external fun nativeChannelNetworkTelemetryAttach(handle: Long, transportKind: Int, sourceId: Long): Boolean

    @JvmStatic
    private external fun nativeVideoReceiverClockSyncTelemetryAttach(handle: Long, sourceId: Long): Boolean

    @JvmStatic
    private external fun nativeSessionProtectionCreate(
        rootSecret: ByteArray,
        sessionId: ByteArray,
        sessionGeneration: Int,
        transcriptHash: ByteArray,
        localRole: Int,
        maxSecureDatagramSize: Int,
        replayWindowSize: Int,
        maxContexts: Int,
        maxPacketsPerEpoch: Long,
        previousEpochRetentionUs: Long,
        maxProtectedRetransmissionAgeUs: Long,
        expectedRemoteAddress: ByteArray,
        expectedRemotePort: Int,
    ): LongArray

    @JvmStatic
    private external fun nativeSessionProtectionDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeSessionProtectionCreateContext(
        handle: Long,
        scopeType: Int,
        scopeId: Long,
        expectedRemoteAddress: ByteArray?,
        expectedRemotePort: Int,
    ): LongArray

    @JvmStatic
    private external fun nativeSessionProtectionDestroyContext(handle: Long, scopeType: Int, scopeId: Long): Int

    @JvmStatic
    private external fun nativeSessionProtectionSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeSessionProtectionLastAuthenticatedReceiveUs(handle: Long): Long

    @JvmStatic
    private external fun nativeSessionProtectionProtectSessionControl(
        handle: Long,
        sequenceNumber: Long,
        timestampUs: Long,
        payload: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeSessionProtectionUnprotectSessionControl(
        handle: Long,
        sourceAddress: ByteArray,
        sourcePort: Int,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): ByteArray?

    @JvmStatic
    private external fun nativeSessionProtectionUnprotectCandidateSessionControl(
        handle: Long,
        sourceAddress: ByteArray,
        sourcePort: Int,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): ByteArray?

    @JvmStatic
    private external fun nativeSessionProtectionRebindSessionControl(
        handle: Long,
        remoteAddress: ByteArray,
        remotePort: Int,
    ): Int

    @JvmStatic
    private external fun nativeSessionProtectionRebindChannel(
        handle: Long,
        channelId: Long,
        remoteAddress: ByteArray,
        remotePort: Int,
    ): Int

    @JvmStatic
    private external fun nativePreparedUdpEndpointCreate(localAddress: String): LongArray

    @JvmStatic
    private external fun nativePreparedUdpEndpointDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativePreparedSecureChannelCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativePreparedSecureChannelDestroy(handle: Long): Int

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
    private external fun nativeAudioPlaybackCreate(
        source: Int,
        configGeneration: Long,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        framesPerCodecFrame: Int,
        lookaheadSamples: Int,
        ringCapacityCodecFrames: Int,
        startThresholdCodecFrames: Int,
        sharingPolicy: Int,
        requestedBufferBursts: Int,
        requireLowLatencyPerformanceMode: Boolean,
    ): LongArray

    @JvmStatic
    private external fun nativeAudioPlaybackDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioPlaybackSubmitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        frameCount: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: Int,
        discontinuityBefore: Boolean,
        frameKind: Int,
    ): Int

    @JvmStatic
    private external fun nativeAudioPlaybackStart(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioPlaybackStop(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioPlaybackPresentationTimestamp(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioPlaybackSourcePresentationAnchor(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioPlaybackSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioPlaybackAttachTelemetry(handle: Long, sourceId: Long): Int

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
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativeAudioTransportDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioTransportRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int

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
    private external fun nativeInputTransportCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialInputSequence: Long,
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativeInputTransportDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeInputTransportRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitKey(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        usagePage: Int,
        usageId: Int,
        action: Int,
        repeatCount: Int,
        modifierMask: Int,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitTouchFrame(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        action: Int,
        actionPointerId: Int,
        pointerCount: Int,
        contactScratch: ByteBuffer,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitPointerAbsolute(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        xNormalized: Int,
        yNormalized: Int,
        buttonMask: Int,
        pointerFlags: Int,
        pressure: Int,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitPointerRelative(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        deltaXQ1616: Int,
        deltaYQ1616: Int,
        buttonMask: Int,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitScroll(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        horizontalQ88: Int,
        verticalQ88: Int,
        buttonMask: Int,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitGamepadState(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        buttonMask: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSubmitReset(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        scope: Int,
        reason: Int,
    ): Int

    @JvmStatic
    private external fun nativeInputTransportSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeInputReceiverCreate(
        localAddress: String,
        localPort: Int,
        expectedRemoteAddress: String,
        expectedRemotePort: Int,
        maxWireDatagramSize: Int,
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativeInputReceiverDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeInputReceiverRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int

    @JvmStatic
    private external fun nativeInputReceiverWait(handle: Long, timeoutUs: Long, bridgeBuffer: ByteBuffer): Int

    @JvmStatic
    private external fun nativeInputReceiverInterrupt(handle: Long): Int

    @JvmStatic
    private external fun nativeInputReceiverWake(handle: Long): Int

    @JvmStatic
    private external fun nativeInputReceiverSnapshot(handle: Long): LongArray

    @JvmStatic
    private external fun nativeAudioReceiverCreate(
        localAddress: String,
        localPort: Int,
        remoteAddress: String?,
        remotePort: Int,
        restrictRemoteEndpoint: Boolean,
        maxWireDatagramSize: Int,
        maxLogicalAudioPayloadSize: Int,
        reassemblySlotCount: Int,
        readySlotCount: Int,
        reassemblyTimeoutUs: Long,
        source: Int,
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativeAudioReceiverDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeAudioReceiverRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int

    @JvmStatic
    private external fun nativeAudioReceiverPump(handle: Long, timeoutUs: Long): LongArray

    @JvmStatic
    private external fun nativeAudioReceiverReadyBuffer(handle: Long, slotIndex: Int): ByteBuffer?

    @JvmStatic
    private external fun nativeAudioReceiverReleaseSlot(handle: Long, slotIndex: Int): Int

    @JvmStatic
    private external fun nativeAudioReceiverSnapshot(handle: Long): LongArray

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
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativeVideoTransportDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeVideoTransportRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int

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
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long

    @JvmStatic
    private external fun nativeVideoReceiverDestroy(handle: Long): Int

    @JvmStatic
    private external fun nativeVideoReceiverRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int

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

    /** Cold-path batch collection only; no metric update crosses JNI. */
    fun runtimeTelemetrySnapshot(outputBuffer: ByteBuffer): LongArray = nativeRuntimeTelemetrySnapshot(outputBuffer)

    /** Cold-path WNDE history collection only; native event emission never crosses JNI. */
    fun diagnosticEventSnapshot(outputBuffer: ByteBuffer, cursor: Long, limit: Int): LongArray =
        nativeDiagnosticEventSnapshot(outputBuffer, cursor, limit)

    fun runtimeTelemetryRegisterSource(sourceId: Long, metricIds: ShortArray, metricKinds: ByteArray): Boolean =
        nativeRuntimeTelemetryRegisterSource(sourceId, metricIds, metricKinds)

    fun runtimeTelemetryUnregisterSource(sourceId: Long) = nativeRuntimeTelemetryUnregisterSource(sourceId)

    fun channelNetworkTelemetryAttach(handle: Long, transportKind: Int, sourceId: Long): Boolean =
        nativeChannelNetworkTelemetryAttach(handle, transportKind, sourceId)

    fun videoReceiverClockSyncTelemetryAttach(handle: Long, sourceId: Long): Boolean =
        nativeVideoReceiverClockSyncTelemetryAttach(handle, sourceId)

    fun sessionProtectionCreate(
        rootSecret: ByteArray,
        sessionId: ByteArray,
        sessionGeneration: Int,
        transcriptHash: ByteArray,
        localRole: Int,
        maxSecureDatagramSize: Int,
        replayWindowSize: Int,
        maxContexts: Int,
        maxPacketsPerEpoch: Long,
        previousEpochRetentionUs: Long,
        maxProtectedRetransmissionAgeUs: Long,
        expectedRemoteAddress: ByteArray,
        expectedRemotePort: Int,
    ): LongArray = nativeSessionProtectionCreate(
        rootSecret,
        sessionId,
        sessionGeneration,
        transcriptHash,
        localRole,
        maxSecureDatagramSize,
        replayWindowSize,
        maxContexts,
        maxPacketsPerEpoch,
        previousEpochRetentionUs,
        maxProtectedRetransmissionAgeUs,
        expectedRemoteAddress,
        expectedRemotePort,
    )

    fun sessionProtectionDestroy(handle: Long): Int = nativeSessionProtectionDestroy(handle)

    fun sessionProtectionCreateContext(
        handle: Long,
        scopeType: Int,
        scopeId: Long,
        expectedRemoteAddress: ByteArray? = null,
        expectedRemotePort: Int = 0,
    ): LongArray = nativeSessionProtectionCreateContext(
        handle,
        scopeType,
        scopeId,
        expectedRemoteAddress,
        expectedRemotePort,
    )

    fun sessionProtectionDestroyContext(handle: Long, scopeType: Int, scopeId: Long): Int =
        nativeSessionProtectionDestroyContext(handle, scopeType, scopeId)

    fun sessionProtectionSnapshot(handle: Long): LongArray = nativeSessionProtectionSnapshot(handle)

    fun sessionProtectionLastAuthenticatedReceiveUs(handle: Long): Long =
        nativeSessionProtectionLastAuthenticatedReceiveUs(handle)

    fun sessionProtectionProtectSessionControl(
        handle: Long,
        sequenceNumber: Long,
        timestampUs: Long,
        payload: ByteArray,
    ): ByteArray? = nativeSessionProtectionProtectSessionControl(handle, sequenceNumber, timestampUs, payload)

    fun sessionProtectionUnprotectSessionControl(
        handle: Long,
        sourceAddress: ByteArray,
        sourcePort: Int,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): ByteArray? = nativeSessionProtectionUnprotectSessionControl(
        handle,
        sourceAddress,
        sourcePort,
        protectedDatagram,
        nowUs,
    )

    fun sessionProtectionUnprotectCandidateSessionControl(
        handle: Long,
        sourceAddress: ByteArray,
        sourcePort: Int,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): ByteArray? = nativeSessionProtectionUnprotectCandidateSessionControl(
        handle,
        sourceAddress,
        sourcePort,
        protectedDatagram,
        nowUs,
    )

    fun sessionProtectionRebindSessionControl(handle: Long, remoteAddress: ByteArray, remotePort: Int): Int =
        nativeSessionProtectionRebindSessionControl(handle, remoteAddress, remotePort)

    fun sessionProtectionRebindChannel(handle: Long, channelId: Long, remoteAddress: ByteArray, remotePort: Int): Int =
        nativeSessionProtectionRebindChannel(handle, channelId, remoteAddress, remotePort)

    fun preparedUdpEndpointCreate(localAddress: String): LongArray = nativePreparedUdpEndpointCreate(localAddress)

    fun preparedUdpEndpointDestroy(handle: Long): Int = nativePreparedUdpEndpointDestroy(handle)

    fun preparedSecureChannelCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        protectionHandle: Long,
        channelId: Long,
        preparedEndpointHandle: Long,
    ): Long = nativePreparedSecureChannelCreate(
        remoteAddress,
        remotePort,
        localPort,
        maxWireDatagramSize,
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun preparedSecureChannelDestroy(handle: Long): Int = nativePreparedSecureChannelDestroy(handle)

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

    fun audioPlaybackCreate(
        source: Int,
        configGeneration: Long,
        sampleRateHz: Int,
        channelCount: Int,
        frameDurationUs: Int,
        framesPerCodecFrame: Int,
        lookaheadSamples: Int,
        ringCapacityCodecFrames: Int,
        startThresholdCodecFrames: Int,
        sharingPolicy: Int,
        requestedBufferBursts: Int,
        requireLowLatencyPerformanceMode: Boolean,
    ): LongArray = nativeAudioPlaybackCreate(
        source,
        configGeneration,
        sampleRateHz,
        channelCount,
        frameDurationUs,
        framesPerCodecFrame,
        lookaheadSamples,
        ringCapacityCodecFrames,
        startThresholdCodecFrames,
        sharingPolicy,
        requestedBufferBursts,
        requireLowLatencyPerformanceMode,
    )

    fun audioPlaybackDestroy(handle: Long): Int = nativeAudioPlaybackDestroy(handle)

    fun audioPlaybackSubmitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        frameCount: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: Int,
        discontinuityBefore: Boolean,
        frameKind: Int,
    ): Int = nativeAudioPlaybackSubmitPcm(
        handle,
        buffer,
        offset,
        size,
        frameCount,
        configGeneration,
        firstFramePosition,
        captureTimeUs,
        timestampQuality,
        discontinuityBefore,
        frameKind,
    )

    fun audioPlaybackStart(handle: Long): Int = nativeAudioPlaybackStart(handle)

    fun audioPlaybackStop(handle: Long): Int = nativeAudioPlaybackStop(handle)

    fun audioPlaybackPresentationTimestamp(handle: Long): LongArray = nativeAudioPlaybackPresentationTimestamp(handle)

    fun audioPlaybackSourcePresentationAnchor(handle: Long): LongArray =
        nativeAudioPlaybackSourcePresentationAnchor(handle)

    fun audioPlaybackSnapshot(handle: Long): LongArray = nativeAudioPlaybackSnapshot(handle)

    fun audioPlaybackAttachTelemetry(handle: Long, sourceId: Long): Int =
        nativeAudioPlaybackAttachTelemetry(handle, sourceId)

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
        protectionHandle: Long = 0L,
        channelId: Long = 0L,
        preparedEndpointHandle: Long = 0L,
    ): Long = nativeAudioTransportCreate(
        remoteAddress,
        remotePort,
        localPort,
        maxWireDatagramSize,
        initialAudioSequence,
        source,
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun audioTransportDestroy(handle: Long): Int = nativeAudioTransportDestroy(handle)

    fun audioTransportRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = nativeAudioTransportRebind(handle, remoteAddress, remotePort, localPort, preparedEndpointHandle)

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

    fun inputTransportCreate(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialInputSequence: Long,
        protectionHandle: Long = 0L,
        channelId: Long = 0L,
        preparedEndpointHandle: Long = 0L,
    ): Long = nativeInputTransportCreate(
        remoteAddress,
        remotePort,
        localPort,
        maxWireDatagramSize,
        initialInputSequence,
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun inputTransportDestroy(handle: Long): Int = nativeInputTransportDestroy(handle)

    fun inputTransportRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = nativeInputTransportRebind(handle, remoteAddress, remotePort, localPort, preparedEndpointHandle)

    fun inputTransportSubmitKey(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        usagePage: Int,
        usageId: Int,
        action: Int,
        repeatCount: Int,
        modifierMask: Int,
    ): Int = nativeInputTransportSubmitKey(
        handle,
        eventTimeUs,
        deviceSlot,
        usagePage,
        usageId,
        action,
        repeatCount,
        modifierMask,
    )

    fun inputTransportSubmitTouchFrame(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        action: Int,
        actionPointerId: Int,
        pointerCount: Int,
        contactScratch: ByteBuffer,
    ): Int = nativeInputTransportSubmitTouchFrame(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        action,
        actionPointerId,
        pointerCount,
        contactScratch,
    )

    fun inputTransportSubmitPointerAbsolute(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        xNormalized: Int,
        yNormalized: Int,
        buttonMask: Int,
        pointerFlags: Int,
        pressure: Int,
    ): Int = nativeInputTransportSubmitPointerAbsolute(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        xNormalized,
        yNormalized,
        buttonMask,
        pointerFlags,
        pressure,
    )

    fun inputTransportSubmitPointerRelative(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        deltaXQ1616: Int,
        deltaYQ1616: Int,
        buttonMask: Int,
    ): Int = nativeInputTransportSubmitPointerRelative(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        deltaXQ1616,
        deltaYQ1616,
        buttonMask,
    )

    fun inputTransportSubmitScroll(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        horizontalQ88: Int,
        verticalQ88: Int,
        buttonMask: Int,
    ): Int = nativeInputTransportSubmitScroll(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        horizontalQ88,
        verticalQ88,
        buttonMask,
    )

    fun inputTransportSubmitGamepadState(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        buttonMask: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
    ): Int = nativeInputTransportSubmitGamepadState(
        handle,
        eventTimeUs,
        deviceSlot,
        buttonMask,
        leftX,
        leftY,
        rightX,
        rightY,
        leftTrigger,
        rightTrigger,
    )

    fun inputTransportSubmitReset(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        scope: Int,
        reason: Int,
    ): Int = nativeInputTransportSubmitReset(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        scope,
        reason,
    )

    fun inputTransportSnapshot(handle: Long): LongArray = nativeInputTransportSnapshot(handle)

    fun inputReceiverCreate(
        localAddress: String,
        localPort: Int,
        expectedRemoteAddress: String,
        expectedRemotePort: Int,
        maxWireDatagramSize: Int,
        protectionHandle: Long = 0L,
        channelId: Long = 0L,
        preparedEndpointHandle: Long = 0L,
    ): Long = nativeInputReceiverCreate(
        localAddress,
        localPort,
        expectedRemoteAddress,
        expectedRemotePort,
        maxWireDatagramSize,
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun inputReceiverDestroy(handle: Long): Int = nativeInputReceiverDestroy(handle)

    fun inputReceiverRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = nativeInputReceiverRebind(handle, remoteAddress, remotePort, localPort, preparedEndpointHandle)

    fun inputReceiverWait(handle: Long, timeoutUs: Long, bridgeBuffer: ByteBuffer): Int =
        nativeInputReceiverWait(handle, timeoutUs, bridgeBuffer)

    fun inputReceiverInterrupt(handle: Long): Int = nativeInputReceiverInterrupt(handle)

    fun inputReceiverWake(handle: Long): Int = nativeInputReceiverWake(handle)

    fun inputReceiverSnapshot(handle: Long): LongArray = nativeInputReceiverSnapshot(handle)

    fun audioReceiverCreate(
        localAddress: String,
        localPort: Int,
        remoteAddress: String?,
        remotePort: Int,
        restrictRemoteEndpoint: Boolean,
        maxWireDatagramSize: Int,
        maxLogicalAudioPayloadSize: Int,
        reassemblySlotCount: Int,
        readySlotCount: Int,
        reassemblyTimeoutUs: Long,
        source: Int,
        protectionHandle: Long = 0L,
        channelId: Long = 0L,
        preparedEndpointHandle: Long = 0L,
    ): Long = nativeAudioReceiverCreate(
        localAddress,
        localPort,
        remoteAddress,
        remotePort,
        restrictRemoteEndpoint,
        maxWireDatagramSize,
        maxLogicalAudioPayloadSize,
        reassemblySlotCount,
        readySlotCount,
        reassemblyTimeoutUs,
        source,
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun audioReceiverDestroy(handle: Long): Int = nativeAudioReceiverDestroy(handle)

    fun audioReceiverRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = nativeAudioReceiverRebind(handle, remoteAddress, remotePort, localPort, preparedEndpointHandle)

    fun audioReceiverPump(handle: Long, timeoutUs: Long): LongArray = nativeAudioReceiverPump(handle, timeoutUs)

    fun audioReceiverReadyBuffer(handle: Long, slotIndex: Int): ByteBuffer? =
        nativeAudioReceiverReadyBuffer(handle, slotIndex)

    fun audioReceiverReleaseSlot(handle: Long, slotIndex: Int): Int = nativeAudioReceiverReleaseSlot(handle, slotIndex)

    fun audioReceiverSnapshot(handle: Long): LongArray = nativeAudioReceiverSnapshot(handle)

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
        protectionHandle: Long = 0L,
        channelId: Long = 0L,
        preparedEndpointHandle: Long = 0L,
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
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun videoTransportDestroy(handle: Long): Int = nativeVideoTransportDestroy(handle)

    fun videoTransportRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = nativeVideoTransportRebind(handle, remoteAddress, remotePort, localPort, preparedEndpointHandle)

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
        protectionHandle: Long = 0L,
        channelId: Long = 0L,
        preparedEndpointHandle: Long = 0L,
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
        protectionHandle,
        channelId,
        preparedEndpointHandle,
    )

    fun videoReceiverDestroy(handle: Long): Int = nativeVideoReceiverDestroy(handle)

    fun videoReceiverRebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = nativeVideoReceiverRebind(handle, remoteAddress, remotePort, localPort, preparedEndpointHandle)

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
