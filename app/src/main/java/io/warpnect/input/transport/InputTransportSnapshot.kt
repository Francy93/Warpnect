package io.warpnect.input.transport

data class InputTransportSnapshot(
    val state: InputTransportState = InputTransportState.Stopped,
    val remoteAddress: String? = null,
    val remotePort: Int? = null,
    val localPort: Int? = null,
    val boundLocalAddress: String? = null,
    val boundLocalPort: Int? = null,
    val nextSequence: Long = 0L,
    val eventsSubmitted: Long = 0L,
    val datagramsAttempted: Long = 0L,
    val datagramsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val freshStateSubmitted: Long = 0L,
    val freshStateSent: Long = 0L,
    val freshStateDropped: Long = 0L,
    val criticalTransitionsSubmitted: Long = 0L,
    val criticalTransitionsSent: Long = 0L,
    val criticalTransitionsDropped: Long = 0L,
    val resetsSubmitted: Long = 0L,
    val resetsSent: Long = 0L,
    val resetSendFailures: Long = 0L,
    val keyEvents: Long = 0L,
    val touchFrames: Long = 0L,
    val pointerAbsoluteEvents: Long = 0L,
    val pointerRelativeEvents: Long = 0L,
    val scrollEvents: Long = 0L,
    val gamepadStates: Long = 0L,
    val resetEvents: Long = 0L,
    val wouldBlockCount: Long = 0L,
    val sendFailureCount: Long = 0L,
    val lastEventTimestampUs: Long? = null,
    val lastAttemptedSequence: Long? = null,
    val lastSentSequence: Long? = null,
    val lastError: InputTransportError = InputTransportError.None,
) {
    internal companion object {
        private const val NATIVE_VALUES = 34

        fun fromNative(
            values: LongArray,
            state: InputTransportState,
            config: InputTransportConfig?,
        ): InputTransportSnapshot {
            if (values.size < NATIVE_VALUES) {
                return InputTransportSnapshot(
                    state = InputTransportState.Error,
                    remoteAddress = config?.remoteAddress,
                    remotePort = config?.remotePort,
                    localPort = config?.localPort,
                    lastError = InputTransportError.InvalidHandle,
                )
            }
            return InputTransportSnapshot(
                state = state,
                remoteAddress = config?.remoteAddress,
                remotePort = config?.remotePort,
                localPort = config?.localPort,
                boundLocalAddress = when (values[33].toInt()) {
                    1 -> "0.0.0.0"
                    2 -> "::"
                    else -> null
                }.takeIf { values[32] != 0L },
                boundLocalPort = values[32].takeIf { it != 0L }?.toInt(),
                nextSequence = values[0],
                eventsSubmitted = values[1],
                datagramsAttempted = values[2],
                datagramsSent = values[3],
                bytesSent = values[4],
                freshStateSubmitted = values[5],
                freshStateSent = values[6],
                freshStateDropped = values[7],
                criticalTransitionsSubmitted = values[8],
                criticalTransitionsSent = values[9],
                criticalTransitionsDropped = values[10],
                resetsSubmitted = values[11],
                resetsSent = values[12],
                resetSendFailures = values[13],
                keyEvents = values[14],
                touchFrames = values[15],
                pointerAbsoluteEvents = values[16],
                pointerRelativeEvents = values[17],
                scrollEvents = values[18],
                gamepadStates = values[19],
                resetEvents = values[20],
                wouldBlockCount = values[21],
                sendFailureCount = values[22],
                lastEventTimestampUs = values[23].takeIf { values[27] != 0L },
                lastAttemptedSequence = values[24].takeIf { values[28] != 0L },
                lastSentSequence = values[25].takeIf { values[29] != 0L },
                lastError = InputTransportError.fromNativeCode(values[26].toInt()),
            )
        }
    }
}
