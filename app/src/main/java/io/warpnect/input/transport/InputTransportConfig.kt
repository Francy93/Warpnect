package io.warpnect.input.transport

const val INPUT_MAX_PAYLOAD_WIRE_SIZE: Int = 396
const val INPUT_MAX_DATAGRAM_WIRE_SIZE: Int = 417

data class InputTransportConfig(
    val remoteAddress: String,
    val remotePort: Int,
    val localPort: Int = 0,
    val maxWireDatagramSize: Int = INPUT_MAX_DATAGRAM_WIRE_SIZE,
    val initialInputSequence: Long = 0L,
) {
    fun validate(): InputTransportError = when {
        remoteAddress.isBlank() -> InputTransportError.InvalidEndpoint
        remotePort !in 1..U16_MAX || localPort !in 0..U16_MAX -> InputTransportError.InvalidEndpoint
        maxWireDatagramSize < INPUT_MAX_DATAGRAM_WIRE_SIZE -> InputTransportError.InvalidDatagramBudget
        initialInputSequence !in 0L..UINT32_MAX -> InputTransportError.InvalidConfiguration
        else -> InputTransportError.None
    }

    private companion object {
        const val U16_MAX = 65_535
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
