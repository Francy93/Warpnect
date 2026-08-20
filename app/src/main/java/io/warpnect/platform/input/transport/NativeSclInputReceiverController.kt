package io.warpnect.platform.input.transport

import io.warpnect.NativeBridge
import io.warpnect.input.transport.INPUT_RECEIVER_BRIDGE_CAPACITY_BYTES
import io.warpnect.input.transport.InputReceiverBridgeDecoder
import io.warpnect.input.transport.InputReceiverConfig
import io.warpnect.input.transport.InputReceiverError
import io.warpnect.input.transport.InputReceiverResult
import io.warpnect.input.transport.InputReceiverRuntime
import io.warpnect.input.transport.InputReceiverSnapshot
import io.warpnect.input.transport.InputReceiverState
import io.warpnect.input.transport.InputReceiverWaitResult
import io.warpnect.input.transport.InputReceiverWaitStatus
import io.warpnect.platform.session.channel.markNativeEndpointAdopted
import io.warpnect.platform.session.channel.nativeEndpointHandleForLiveRebind
import io.warpnect.session.setup.ChannelEndpointLease
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface InputReceiverNativeApi {
    fun create(
        localAddress: String,
        localPort: Int,
        expectedRemoteAddress: String,
        expectedRemotePort: Int,
        maxWireDatagramSize: Int,
    ): Long

    fun destroy(handle: Long): Int

    fun rebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = InputReceiverError.InvalidHandle.code

    fun waitForEvent(handle: Long, timeoutUs: Long, bridgeBuffer: ByteBuffer): Int

    fun interrupt(handle: Long): Int

    fun wake(handle: Long): Int

    fun snapshot(handle: Long): LongArray
}

internal object NativeBridgeInputReceiverApi : InputReceiverNativeApi {
    override fun create(
        localAddress: String,
        localPort: Int,
        expectedRemoteAddress: String,
        expectedRemotePort: Int,
        maxWireDatagramSize: Int,
    ): Long = NativeBridge.inputReceiverCreate(
        localAddress,
        localPort,
        expectedRemoteAddress,
        expectedRemotePort,
        maxWireDatagramSize,
    )

    override fun destroy(handle: Long): Int = NativeBridge.inputReceiverDestroy(handle)

    override fun rebind(
        handle: Long,
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        preparedEndpointHandle: Long,
    ): Int = NativeBridge.inputReceiverRebind(
        handle,
        remoteAddress,
        remotePort,
        localPort,
        preparedEndpointHandle,
    )

    override fun waitForEvent(handle: Long, timeoutUs: Long, bridgeBuffer: ByteBuffer): Int =
        NativeBridge.inputReceiverWait(handle, timeoutUs, bridgeBuffer)

    override fun interrupt(handle: Long): Int = NativeBridge.inputReceiverInterrupt(handle)

    override fun wake(handle: Long): Int = NativeBridge.inputReceiverWake(handle)

    override fun snapshot(handle: Long): LongArray = NativeBridge.inputReceiverSnapshot(handle)
}

class NativeSclInputReceiverController internal constructor(
    private val nativeApi: InputReceiverNativeApi = NativeBridgeInputReceiverApi,
) : InputReceiverRuntime {
    @Volatile
    private var handle: Long = 0L
    private var state = InputReceiverState.Stopped
    private var bridge: ByteBuffer? = null
    private var cachedSnapshot = InputReceiverSnapshot()

    /** RFC-005I adopts the stopped RFC-005G protected Input receiver and preserves its socket. */
    internal fun adoptPreparedTransport(handle: Long): InputReceiverError {
        if (handle == 0L || this.handle != 0L || state == InputReceiverState.Closed) {
            return InputReceiverError.InvalidHandle
        }
        this.handle = handle
        bridge = ByteBuffer.allocateDirect(INPUT_RECEIVER_BRIDGE_CAPACITY_BYTES).order(ByteOrder.nativeOrder())
        state = InputReceiverState.Prepared
        cachedSnapshot = snapshot()
        return InputReceiverError.None
    }

    /** RFC-005H rebinds the same receiver handle without replacing Input convergence state. */
    internal fun rebindLiveTransport(
        localEndpoint: ChannelEndpointLease,
        remoteAddress: String,
        remotePort: Int,
    ): Boolean {
        val activeHandle = handle
        val endpointHandle = localEndpoint.nativeEndpointHandleForLiveRebind()
        val rebound = activeHandle != 0L && endpointHandle != 0L &&
            InputReceiverError.fromNativeCode(
                nativeApi.rebind(activeHandle, remoteAddress, remotePort, localEndpoint.localPort, endpointHandle),
            ) == InputReceiverError.None
        if (rebound) localEndpoint.markNativeEndpointAdopted(endpointHandle)
        return rebound
    }

    internal fun adoptedNativeHandleForTesting(): Long = handle

    override fun prepare(config: InputReceiverConfig): InputReceiverResult {
        if (state == InputReceiverState.Closed) return result(InputReceiverError.Closed)
        val validation = config.validate()
        if (validation != InputReceiverError.None) return result(validation)
        if (handle != 0L) {
            val adopted = state == InputReceiverState.Prepared
            if (adopted) return result(InputReceiverError.None)
            stop()
        }
        state = InputReceiverState.Preparing
        val created = nativeApi.create(
            config.localAddress,
            config.localPort,
            config.expectedRemoteAddress,
            config.expectedRemotePort,
            config.maxWireDatagramSize,
        )
        if (created == 0L) {
            state = InputReceiverState.Error
            return result(InputReceiverError.UdpBindFailed)
        }
        handle = created
        bridge = ByteBuffer.allocateDirect(INPUT_RECEIVER_BRIDGE_CAPACITY_BYTES).order(ByteOrder.nativeOrder())
        state = InputReceiverState.Prepared
        return result(InputReceiverError.None)
    }

    override fun start(): InputReceiverResult {
        if (state == InputReceiverState.Closed) return result(InputReceiverError.Closed)
        if (state != InputReceiverState.Prepared) return result(InputReceiverError.NotPrepared)
        state = InputReceiverState.Running
        return result(InputReceiverError.None)
    }

    override fun waitForInputEvent(timeoutUs: Long): InputReceiverWaitResult {
        val activeHandle = handle
        val activeBridge = bridge
        if (state != InputReceiverState.Running || activeHandle == 0L || activeBridge == null || timeoutUs < 0L) {
            return InputReceiverWaitResult.Failure(InputReceiverError.NotRunning)
        }
        val packed = nativeApi.waitForEvent(activeHandle, timeoutUs, activeBridge)
        val status = InputReceiverWaitStatus.fromNativeCode(packed and 0xFF)
            ?: return InputReceiverWaitResult.Failure(InputReceiverError.UnknownFailure)
        val error = InputReceiverError.fromNativeCode((packed ushr 8) and 0xFF)
        return when (status) {
            InputReceiverWaitStatus.EventReady -> {
                val event = InputReceiverBridgeDecoder.decode(activeBridge)
                    ?: return InputReceiverWaitResult.Failure(InputReceiverError.InvalidBridgeRecord)
                InputReceiverWaitResult.EventReady(event)
            }
            InputReceiverWaitStatus.Interrupted -> InputReceiverWaitResult.Interrupted
            InputReceiverWaitStatus.Timeout -> InputReceiverWaitResult.Timeout
            InputReceiverWaitStatus.UnexpectedEndpointDropped,
            InputReceiverWaitStatus.MalformedDatagramDropped,
            InputReceiverWaitStatus.UnsupportedInputDropped,
            InputReceiverWaitStatus.OversizeDatagramDropped,
            -> InputReceiverWaitResult.Dropped(status)
            InputReceiverWaitStatus.SocketFailure,
            InputReceiverWaitStatus.Closed,
            -> InputReceiverWaitResult.Failure(
                if (error == InputReceiverError.None) InputReceiverError.UnknownFailure else error,
            )
        }
    }

    override fun interrupt(): InputReceiverResult {
        val activeHandle = handle
        if (activeHandle == 0L) return result(InputReceiverError.NotPrepared)
        return result(InputReceiverError.fromNativeCode(nativeApi.interrupt(activeHandle)))
    }

    override fun wake(): InputReceiverResult {
        val activeHandle = handle
        if (activeHandle == 0L) return result(InputReceiverError.NotPrepared)
        return result(InputReceiverError.fromNativeCode(nativeApi.wake(activeHandle)))
    }

    override fun stop(): InputReceiverResult {
        if (state == InputReceiverState.Closed) return result(InputReceiverError.Closed)
        val activeHandle = handle
        state = InputReceiverState.Stopping
        if (activeHandle != 0L) {
            nativeApi.interrupt(activeHandle)
            nativeApi.destroy(activeHandle)
        }
        handle = 0L
        bridge = null
        state = InputReceiverState.Stopped
        return result(InputReceiverError.None)
    }

    override fun snapshot(): InputReceiverSnapshot {
        val activeHandle = handle
        if (activeHandle == 0L) return cachedSnapshot.copy(state = state)
        val values = nativeApi.snapshot(activeHandle)
        if (values.size < 23) return cachedSnapshot.copy(state = state, lastError = InputReceiverError.UnknownFailure)
        cachedSnapshot = InputReceiverSnapshot(
            state = state,
            localEndpointPort = values[2].toInt(),
            datagramsReceived = values[3],
            eventsDelivered = values[4],
            unexpectedEndpointDrops = values[5],
            malformedDatagramDrops = values[6],
            unsupportedInputDrops = values[7],
            oversizeDatagramDrops = values[8],
            socketFailures = values[9],
            sequenceFirst = values[10],
            sequenceContiguous = values[11],
            sequenceGapEvents = values[12],
            sequenceGapCount = values[13],
            sequenceSame = values[14],
            sequenceOutOfOrder = values[15],
            latestSequence = values[16].takeIf { values[18] != 0L },
            latestSourceEventTimeUs = values[17].takeIf { values[19] != 0L },
            lastError = InputReceiverError.fromNativeCode(values[20].toInt()),
        )
        return cachedSnapshot
    }

    override fun close() {
        if (state == InputReceiverState.Closed) return
        stop()
        state = InputReceiverState.Closed
    }

    private fun result(error: InputReceiverError): InputReceiverResult = InputReceiverResult(error, snapshot())
}
