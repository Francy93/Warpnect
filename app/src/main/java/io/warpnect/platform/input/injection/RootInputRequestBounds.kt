package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import kotlin.math.abs

/** Additional RPC resource bounds. Canonical tracker validation still owns actions and ordering. */
internal object RootInputRequestBounds {
    private fun common(time: Long, slot: Int, device: Int, display: Int): Boolean =
        time in 0..Long.MAX_VALUE / 1000 && slot in 0..65535 && device in 0..65535 && display in 0..65535
    private fun coordinate(value: Float): Boolean = value.isFinite() && abs(value) <= 65536f
    private fun unit(value: Float): Boolean = value.isFinite() && value in 0f..1f
    private fun axis(value: Float): Boolean = value.isFinite() && value in -1f..1f

    fun valid(event: AndroidKeyInjectionEvent): Boolean =
        common(event.sourceEventTimeUs, event.stateSlot, event.androidDeviceId, event.displayId) &&
            event.keyCode in 0..65535 && event.repeatCount in 0..10000

    fun valid(event: AndroidTouchInjectionEvent): Boolean =
        common(event.sourceEventTimeUs, event.stateSlot, event.androidDeviceId, event.displayId) &&
            event.pointers.all { coordinate(it.xPx) && coordinate(it.yPx) && unit(it.pressure) && unit(it.size) }

    fun valid(event: AndroidPointerInjectionEvent): Boolean =
        common(event.sourceEventTimeUs, event.stateSlot, event.androidDeviceId, event.displayId) &&
            listOf(
                event.xPx, event.yPx, event.relativeXPx, event.relativeYPx,
                event.horizontalScroll, event.verticalScroll,
            ).all(::coordinate) && unit(event.pressure) && unit(event.size)

    fun valid(event: AndroidJoystickInjectionEvent): Boolean =
        common(event.sourceEventTimeUs, event.stateSlot, event.androidDeviceId, event.displayId) &&
            listOf(event.leftX, event.leftY, event.rightX, event.rightY, event.hatX, event.hatY).all(::axis) &&
            unit(event.leftTrigger) && unit(event.rightTrigger)
}
