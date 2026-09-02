package io.warpnect.platform.video.transport

import io.warpnect.NativeBridge
import io.warpnect.platform.session.channel.markNativeEndpointAdopted
import io.warpnect.platform.session.channel.nativeEndpointHandleForLiveRebind
import io.warpnect.session.setup.ChannelEndpointLease
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.transport.VideoTransportCloseResult
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportController
import io.warpnect.video.transport.VideoTransportError
import io.warpnect.video.transport.VideoTransportOpenResult
import io.warpnect.video.transport.VideoTransportSnapshot
import io.warpnect.video.transport.VideoTransportState
import io.warpnect.video.transport.VideoTransportSubmitResult
import java.nio.ByteBuffer

class NativeSclVideoTransportController(
    private val debugObserver: VideoTransportDebugObserver = VideoTransportDebugObserver.None,
) : VideoTransportController {
    private var nativeHandle: Long = 0
    private var localSnapshot = VideoTransportSnapshot()
    private var firstVideoDatagramSentObserved = false

    /**
     * Cold-path RFC-005I adoption. The handle was created by RFC-005G with the negotiated socket
     * lease and Channel protection context already attached, so open() must not create another.
     */
    internal fun adoptPreparedTransport(handle: Long): VideoTransportError {
        if (handle == 0L || nativeHandle != 0L || localSnapshot.state == VideoTransportState.Closed) {
            return VideoTransportError.InvalidHandle
        }
        nativeHandle = handle
        localSnapshot = snapshot()
        return VideoTransportError.None
    }

    /** RFC-005H rebinds this already-adopted native sender; it never creates a second transport. */
    internal fun rebindLiveTransport(
        localEndpoint: ChannelEndpointLease,
        remoteAddress: String,
        remotePort: Int,
    ): Boolean {
        val handle = nativeHandle
        val endpointHandle = localEndpoint.nativeEndpointHandleForLiveRebind()
        val rebound = handle != 0L && endpointHandle != 0L &&
            VideoTransportError.fromNativeCode(
                NativeBridge.videoTransportRebind(
                    handle,
                    remoteAddress,
                    remotePort,
                    localEndpoint.localPort,
                    endpointHandle,
                ),
            ) == VideoTransportError.None
        if (rebound) localEndpoint.markNativeEndpointAdopted(endpointHandle)
        return rebound
    }

    internal fun adoptedNativeHandleForTesting(): Long = nativeHandle

    override fun open(config: VideoTransportConfig): VideoTransportOpenResult {
        if (localSnapshot.state == VideoTransportState.Closed) {
            return VideoTransportOpenResult(VideoTransportError.Closed, localSnapshot)
        }
        if (nativeHandle != 0L) {
            return VideoTransportOpenResult(VideoTransportError.None, snapshot())
        }

        val validation = validateConfig(config)
        if (validation != VideoTransportError.None) {
            localSnapshot = localSnapshot.copy(
                state = VideoTransportState.Error,
                lastError = validation,
            )
            return VideoTransportOpenResult(validation, localSnapshot)
        }

        nativeHandle = NativeBridge.videoTransportCreate(
            remoteAddress = config.remoteAddress,
            remotePort = config.remotePort,
            localPort = config.localPort,
            maxWireDatagramSize = config.maxWireDatagramSize,
            initialVideoSequence = config.initialVideoSequence,
            initialControlSequence = config.initialControlSequence,
            initialFrameId = config.initialFrameId,
            retransmissionCacheSlots = config.retransmissionCacheSlots,
            fecEnabled = config.fec.enabled,
            fecDataShards = config.fec.dataShards,
            fecParityShards = config.fec.parityShards,
            resyncRequestCooldownUs = config.resyncRequestCooldownUs,
        )
        if (nativeHandle == 0L) {
            localSnapshot = localSnapshot.copy(
                state = VideoTransportState.Error,
                lastError = VideoTransportError.UdpOpenFailed,
            )
            return VideoTransportOpenResult(VideoTransportError.UdpOpenFailed, localSnapshot)
        }

        localSnapshot = snapshot()
        return VideoTransportOpenResult(localSnapshot.lastError, localSnapshot)
    }

    override fun submitStreamConfig(format: VideoEncoderOutputFormat): VideoTransportError {
        val handle = nativeHandle
        if (handle == 0L) {
            return remember(VideoTransportError.InvalidHandle)
        }
        if (format.codec != VideoCodec.Avc) {
            return remember(VideoTransportError.UnsupportedVideoCodec)
        }
        if (format.width !in 1..U16_MAX || format.height !in 1..U16_MAX) {
            return remember(VideoTransportError.InvalidDimensions)
        }
        val csd = format.codecSpecificData.toTypedArray()
        val error = VideoTransportError.fromNativeCode(
            NativeBridge.videoTransportSubmitConfig(
                handle = handle,
                width = format.width,
                height = format.height,
                codecSpecificData = csd,
            ),
        )
        return remember(error)
    }

    override fun submitAccessUnit(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        keyframe: Boolean,
    ): VideoTransportError {
        val handle = nativeHandle
        if (handle == 0L) {
            return remember(VideoTransportError.InvalidHandle)
        }
        val rangeError = validateDirectRange(buffer, offset, size, presentationTimeUs)
        if (rangeError != VideoTransportError.None) {
            return remember(rangeError)
        }
        val error = VideoTransportError.fromNativeCode(
            NativeBridge.videoTransportSubmitAccessUnit(
                handle = handle,
                buffer = buffer,
                offset = offset,
                size = size,
                presentationTimeUs = presentationTimeUs,
                keyframe = keyframe,
            ),
        )
        val remembered = remember(error)
        if (remembered == VideoTransportError.None && !firstVideoDatagramSentObserved) {
            firstVideoDatagramSentObserved = true
            runCatching {
                debugObserver.onEvent(VideoTransportDebugEvent.FirstVideoDatagramSent)
            }
        }
        return remembered
    }

    override fun handleControlDatagram(buffer: ByteBuffer, offset: Int, size: Int): VideoTransportSubmitResult {
        val handle = nativeHandle
        if (handle == 0L) {
            val error = remember(VideoTransportError.InvalidHandle)
            return VideoTransportSubmitResult(error, localSnapshot)
        }
        val rangeError = validateDirectRange(buffer, offset, size, presentationTimeUs = 0)
        if (rangeError != VideoTransportError.None) {
            val error = remember(rangeError)
            return VideoTransportSubmitResult(error, localSnapshot)
        }
        val error = remember(
            VideoTransportError.fromNativeCode(
                NativeBridge.videoTransportHandleControlDatagram(
                    handle = handle,
                    buffer = buffer,
                    offset = offset,
                    size = size,
                ),
            ),
        )
        return VideoTransportSubmitResult(error, localSnapshot)
    }

    fun pumpControl(timeoutUs: Long): VideoTransportSubmitResult {
        val handle = nativeHandle
        if (handle == 0L) {
            val error = remember(VideoTransportError.InvalidHandle)
            return VideoTransportSubmitResult(error, localSnapshot)
        }
        if (timeoutUs < 0L) {
            val error = remember(VideoTransportError.InvalidBufferRange)
            return VideoTransportSubmitResult(error, localSnapshot)
        }
        val nativeError = VideoTransportError.fromNativeCode(
            NativeBridge.videoTransportPumpControl(handle, timeoutUs),
        )
        if (nativeError == VideoTransportError.NoData) {
            return VideoTransportSubmitResult(VideoTransportError.None, snapshot())
        }
        val error = remember(nativeError)
        return VideoTransportSubmitResult(error, localSnapshot)
    }

    override fun snapshot(): VideoTransportSnapshot {
        val handle = nativeHandle
        if (handle == 0L) {
            return localSnapshot
        }
        localSnapshot = VideoTransportSnapshot.fromNative(NativeBridge.videoTransportSnapshot(handle))
        return localSnapshot
    }

    override fun closeResult(): VideoTransportCloseResult {
        val handle = nativeHandle
        if (handle == 0L) {
            localSnapshot = localSnapshot.copy(state = VideoTransportState.Closed)
            return VideoTransportCloseResult(VideoTransportError.None, localSnapshot)
        }
        nativeHandle = 0
        val error = VideoTransportError.fromNativeCode(NativeBridge.videoTransportDestroy(handle))
        localSnapshot = localSnapshot.copy(state = VideoTransportState.Closed, lastError = error)
        return VideoTransportCloseResult(error, localSnapshot)
    }

    private fun remember(error: VideoTransportError): VideoTransportError {
        val current = snapshot()
        localSnapshot = current.copy(
            state = if (error == VideoTransportError.None) current.state else VideoTransportState.Error,
            lastError = error,
        )
        return error
    }

    private fun validateConfig(config: VideoTransportConfig): VideoTransportError {
        if (config.remotePort !in 1..U16_MAX || config.localPort !in 0..U16_MAX) {
            return VideoTransportError.UdpBindFailed
        }
        if (
            config.maxWireDatagramSize <= 0 ||
            config.retransmissionCacheSlots <= 0
        ) {
            return VideoTransportError.InvalidDatagramBudget
        }
        if (
            !isUInt32(config.initialVideoSequence) ||
            !isUInt32(config.initialControlSequence) ||
            !isUInt32(config.initialFrameId)
        ) {
            return VideoTransportError.InvalidFrameId
        }
        if (config.fec.enabled && (config.fec.dataShards <= 0 || config.fec.parityShards <= 0)) {
            return VideoTransportError.FecConfigurationInvalid
        }
        if (config.resyncRequestCooldownUs < 0L) {
            return VideoTransportError.PerformanceConfigInvalid
        }
        return VideoTransportError.None
    }

    private fun validateDirectRange(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
    ): VideoTransportError {
        if (!buffer.isDirect) {
            return VideoTransportError.NonDirectBuffer
        }
        if (presentationTimeUs < 0) {
            return VideoTransportError.InvalidPresentationTimestamp
        }
        if (offset < 0 || size <= 0) {
            return VideoTransportError.InvalidBufferRange
        }
        val end = offset.toLong() + size.toLong()
        if (end > buffer.capacity().toLong()) {
            return VideoTransportError.InvalidBufferRange
        }
        return VideoTransportError.None
    }

    private fun isUInt32(value: Long): Boolean = value in 0..UINT32_MAX

    private companion object {
        const val U16_MAX = 65_535
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
