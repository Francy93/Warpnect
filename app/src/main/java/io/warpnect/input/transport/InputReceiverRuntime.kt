package io.warpnect.input.transport

import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.WarpnectInputEvent

const val INPUT_RECEIVER_BRIDGE_CAPACITY_BYTES: Int = 1024
const val INPUT_RECEIVER_BRIDGE_REQUIRED_BYTES: Int = 960

data class InputReceiverConfig(
    val localAddress: String,
    val localPort: Int,
    val expectedRemoteAddress: String,
    val expectedRemotePort: Int,
    val maxWireDatagramSize: Int = INPUT_MAX_DATAGRAM_WIRE_SIZE,
) {
    fun validate(): InputReceiverError = when {
        !localAddress.isNumericEndpointAddress() || !expectedRemoteAddress.isNumericEndpointAddress() ->
            InputReceiverError.InvalidEndpoint
        localPort !in 1..U16_MAX || expectedRemotePort !in 1..U16_MAX -> InputReceiverError.InvalidEndpoint
        // 417 bytes remains the frozen Input V1 structural datagram minimum. RFC-005G may pass
        // a larger outer WNSD budget; that never widens Input V1 logical-message validation.
        maxWireDatagramSize < INPUT_MAX_DATAGRAM_WIRE_SIZE -> InputReceiverError.InvalidConfiguration
        else -> InputReceiverError.None
    }

    private companion object {
        const val U16_MAX = 65_535
    }
}

enum class InputReceiverState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}

enum class InputReceiverError(
    val code: Int,
) {
    None(0),
    InvalidConfiguration(1),
    InvalidEndpoint(2),
    UdpOpenFailed(3),
    UdpBindFailed(4),
    DatagramReceiveFailed(5),
    BridgeBufferTooSmall(6),
    Closed(7),
    InvalidHandle(8),
    InvalidBridgeRecord(9),
    NotPrepared(10),
    NotRunning(11),
    UnknownFailure(12),
    ;

    companion object {
        fun fromNativeCode(code: Int): InputReceiverError = entries.firstOrNull { it.code == code }
            ?: UnknownFailure
    }
}

enum class InputReceiverWaitStatus(
    val nativeCode: Int,
) {
    EventReady(1),
    Interrupted(2),
    Timeout(3),
    UnexpectedEndpointDropped(4),
    MalformedDatagramDropped(5),
    UnsupportedInputDropped(6),
    OversizeDatagramDropped(7),
    SocketFailure(8),
    Closed(9),
    ;

    companion object {
        fun fromNativeCode(code: Int): InputReceiverWaitStatus? = entries.firstOrNull {
            it.nativeCode == code
        }
    }
}

data class InputReceivedEvent(
    val messageType: InputMessageTypeMetadata,
    val deviceKind: InputDeviceKind,
    val deviceSlot: Int,
    val sequenceNumber: Long,
    val sourceEventTimeUs: Long,
    val event: WarpnectInputEvent,
)

enum class InputMessageTypeMetadata {
    Key,
    TouchFrame,
    PointerAbsolute,
    PointerRelative,
    Scroll,
    GamepadState,
    ResetState,
}

sealed interface InputReceiverWaitResult {
    data class EventReady(
        val event: InputReceivedEvent,
    ) : InputReceiverWaitResult

    data object Interrupted : InputReceiverWaitResult

    data object Timeout : InputReceiverWaitResult

    data class Dropped(
        val status: InputReceiverWaitStatus,
    ) : InputReceiverWaitResult

    data class Failure(
        val error: InputReceiverError,
    ) : InputReceiverWaitResult
}

data class InputReceiverSnapshot(
    val state: InputReceiverState = InputReceiverState.Stopped,
    val localEndpointPort: Int = 0,
    val datagramsReceived: Long = 0L,
    val eventsDelivered: Long = 0L,
    val unexpectedEndpointDrops: Long = 0L,
    val malformedDatagramDrops: Long = 0L,
    val unsupportedInputDrops: Long = 0L,
    val oversizeDatagramDrops: Long = 0L,
    val socketFailures: Long = 0L,
    val sequenceFirst: Long = 0L,
    val sequenceContiguous: Long = 0L,
    val sequenceGapEvents: Long = 0L,
    val sequenceGapCount: Long = 0L,
    val sequenceSame: Long = 0L,
    val sequenceOutOfOrder: Long = 0L,
    val latestSequence: Long? = null,
    val latestSourceEventTimeUs: Long? = null,
    val lastError: InputReceiverError = InputReceiverError.None,
)

data class InputReceiverResult(
    val error: InputReceiverError,
    val snapshot: InputReceiverSnapshot,
) {
    val isSuccess: Boolean
        get() = error == InputReceiverError.None
}

interface InputReceiverRuntime : AutoCloseable {
    fun prepare(config: InputReceiverConfig): InputReceiverResult

    fun start(): InputReceiverResult

    fun waitForInputEvent(timeoutUs: Long): InputReceiverWaitResult

    fun interrupt(): InputReceiverResult

    fun wake(): InputReceiverResult

    fun stop(): InputReceiverResult

    fun snapshot(): InputReceiverSnapshot

    override fun close()
}

private fun String.isNumericEndpointAddress(): Boolean {
    if (isBlank()) return false
    if (contains(':')) return all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
    val segments = split('.')
    return segments.size == 4 && segments.all { segment ->
        segment.isNotEmpty() && segment.all(Char::isDigit) && segment.toIntOrNull() in 0..255
    }
}
