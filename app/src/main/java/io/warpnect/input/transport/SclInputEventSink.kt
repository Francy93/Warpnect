package io.warpnect.input.transport

import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.WarpnectInputEvent

class SclInputEventSink(
    private val transport: InputTransportController,
) : InputEventSink {
    override fun onInputEvent(eventTimeUs: Long, event: WarpnectInputEvent): InputSinkResult {
        val result = transport.submit(eventTimeUs, event)
        return if (result.isSuccess) {
            InputSinkResult.Accepted
        } else {
            InputSinkResult.Rejected("Input transport ${result.error}")
        }
    }
}
