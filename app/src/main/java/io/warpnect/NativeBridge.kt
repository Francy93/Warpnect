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
    private external fun nativeVideoTransportSnapshot(handle: Long): LongArray

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

    fun videoTransportSnapshot(handle: Long): LongArray = nativeVideoTransportSnapshot(handle)
}

internal data class NativeSclInfo(
    val protocolName: String,
    val protocolVersion: Int,
    val nativeBridgeAbiVersion: Int,
)
