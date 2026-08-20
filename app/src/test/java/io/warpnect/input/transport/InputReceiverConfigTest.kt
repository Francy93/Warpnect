package io.warpnect.input.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class InputReceiverConfigTest {
    @Test
    fun secureSessionBudgetMayExceedFrozenInputV1StructuralMinimum() {
        assertEquals(
            InputReceiverError.InvalidConfiguration,
            config(INPUT_MAX_DATAGRAM_WIRE_SIZE - 1).validate(),
        )
        assertEquals(InputReceiverError.None, config(INPUT_MAX_DATAGRAM_WIRE_SIZE).validate())
        assertEquals(InputReceiverError.None, config(1_200).validate())
    }

    private fun config(maxWireDatagramSize: Int) = InputReceiverConfig(
        localAddress = "192.168.1.2",
        localPort = 40_001,
        expectedRemoteAddress = "192.168.1.1",
        expectedRemotePort = 40_002,
        maxWireDatagramSize = maxWireDatagramSize,
    )
}
