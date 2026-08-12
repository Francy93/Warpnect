package io.warpnect.input.transport

import io.warpnect.input.model.WarpnectInputEvent

interface InputTransportController : AutoCloseable {
    fun prepare(config: InputTransportConfig): InputTransportResult

    fun start(): InputTransportResult

    fun submit(eventTimeUs: Long, event: WarpnectInputEvent): InputTransportResult

    fun stop(): InputTransportResult

    fun snapshot(): InputTransportSnapshot

    override fun close()
}

data class InputTransportResult(
    val error: InputTransportError,
    val snapshot: InputTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == InputTransportError.None
}
