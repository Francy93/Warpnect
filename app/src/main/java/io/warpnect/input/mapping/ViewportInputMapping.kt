package io.warpnect.input.mapping

import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.INPUT_MAX_TOUCH_CONTACTS
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputModelError
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputScroll
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.video.render.VideoViewportGeometry
import io.warpnect.video.render.VideoViewportGeometryProvider

enum class OutsideVideoContentPolicy {
    RejectNewClampActive,
}

data class ViewportInputMappingConfig(
    val outsideVideoContentPolicy: OutsideVideoContentPolicy = OutsideVideoContentPolicy.RejectNewClampActive,
    val maxTrackedSlots: Int = DEFAULT_MAX_TRACKED_SLOTS,
) {
    fun isValid(): Boolean = maxTrackedSlots in 1..MAX_TRACKED_SLOTS

    companion object {
        const val DEFAULT_MAX_TRACKED_SLOTS = 32
        const val MAX_TRACKED_SLOTS = 32
    }
}

enum class ViewportInputMappingError {
    None,
    Closed,
    InvalidConfiguration,
    InvalidInput,
    GeometryUnavailable,
    GeometryChanged,
    OutsideContent,
    RelativeOverflow,
    CapacityExceeded,
    DownstreamRejected,
}

data class ViewportInputMappingSnapshot(
    val closed: Boolean = false,
    val geometryValid: Boolean = false,
    val surfaceGeneration: Long = 0L,
    val videoConfigGeneration: Long = 0L,
    val eventsReceived: Long = 0L,
    val eventsForwarded: Long = 0L,
    val eventsSuppressed: Long = 0L,
    val touchFramesMapped: Long = 0L,
    val pointerAbsoluteMapped: Long = 0L,
    val pointerRelativeMapped: Long = 0L,
    val outsideContentDowns: Long = 0L,
    val activeCoordinatesClamped: Long = 0L,
    val geometryUnavailableDrops: Long = 0L,
    val geometryChangeResets: Long = 0L,
    val trackedSlots: Int = 0,
    val lastError: ViewportInputMappingError = ViewportInputMappingError.None,
)

/**
 * Maps capture-surface coordinates into the visible aspect-fit video content rectangle.
 * It is a synchronous [InputEventSink] adapter: it owns no queue, worker, or timer.
 */
class RemoteVideoViewportInputMapper(
    private val geometryProvider: VideoViewportGeometryProvider,
    private val downstream: InputEventSink,
    private val config: ViewportInputMappingConfig = ViewportInputMappingConfig(),
) : InputEventSink, AutoCloseable {
    private val slots: Array<TrackedSlot?>
    private var lastGeometry: VideoViewportGeometry? = null
    private var closed = false
    private var snapshot = ViewportInputMappingSnapshot()

    init {
        require(config.isValid()) { "Viewport input mapping configuration is invalid" }
        slots = arrayOfNulls(config.maxTrackedSlots)
    }

    override fun onInputEvent(eventTimeUs: Long, event: WarpnectInputEvent): InputSinkResult {
        snapshot = snapshot.copy(eventsReceived = snapshot.eventsReceived + 1L)
        if (closed) return reject(ViewportInputMappingError.Closed)
        if (event.validationError() != InputModelError.None) return reject(ViewportInputMappingError.InvalidInput)
        return when (event) {
            is InputKeyEvent,
            is InputScroll,
            is InputGamepadState,
            -> forward(eventTimeUs, event)
            is InputTouchFrame -> mapTouchFrame(eventTimeUs, event)
            is InputPointerAbsolute -> mapPointerAbsolute(eventTimeUs, event)
            is InputPointerRelative -> mapPointerRelative(eventTimeUs, event)
            is InputResetState -> mapReset(eventTimeUs, event)
        }
    }

    fun snapshot(): ViewportInputMappingSnapshot = snapshot

    override fun close() {
        if (closed) return
        closed = true
        clearAllSlots()
        snapshot = snapshot.copy(closed = true, lastError = ViewportInputMappingError.Closed)
    }

    private fun mapTouchFrame(eventTimeUs: Long, event: InputTouchFrame): InputSinkResult {
        if (event.action == InputTouchAction.Cancel && event.contacts.isEmpty()) {
            val existing = findSlot(event.deviceSlot)
            if (existing?.touchActive != true) return suppress(ViewportInputMappingError.None)
            val result = forward(eventTimeUs, event)
            if (result is InputSinkResult.Accepted) clearTouch(existing)
            return result
        }
        val acquisition = acquireGeometry(eventTimeUs) ?: return reject(snapshot.lastError)
        if (acquisition.resetActiveCoordinates) return suppress(ViewportInputMappingError.GeometryChanged)
        val geometry = acquisition.geometry
        val existing = findSlot(event.deviceSlot)
        val active = existing?.touchActive == true
        val actionContact = event.contacts.firstOrNull { it.pointerId == event.actionPointerId }

        if (!active && event.action == InputTouchAction.Down && event.contacts.any { isOutside(it, geometry) }) {
            snapshot = snapshot.copy(outsideContentDowns = snapshot.outsideContentDowns + 1L)
            return suppress(ViewportInputMappingError.OutsideContent)
        }
        if (active && event.action == InputTouchAction.PointerDown && actionContact != null &&
            isOutside(actionContact, geometry)
        ) {
            val activeSlot = requireNotNull(existing)
            if (!emitGeometryReset(eventTimeUs, activeSlot)) return reject(ViewportInputMappingError.DownstreamRejected)
            clearSlot(activeSlot.deviceSlot)
            return suppress(ViewportInputMappingError.OutsideContent)
        }

        val clamp = active || event.action.isReleaseOrCancel()
        val mappedContacts = ArrayList<InputTouchContact>(event.contacts.size)
        for (contact in event.contacts) {
            val mapped = mapContact(contact, geometry, clamp)
                ?: return suppress(ViewportInputMappingError.OutsideContent)
            if (mapped.wasClamped) {
                snapshot = snapshot.copy(activeCoordinatesClamped = snapshot.activeCoordinatesClamped + 1L)
            }
            mappedContacts += mapped.contact
        }
        if (!active && event.action == InputTouchAction.Down && !hasTrackingCapacity(event.deviceSlot)) {
            return reject(ViewportInputMappingError.CapacityExceeded)
        }
        val mapped = event.copy(contacts = mappedContacts)
        val result = forward(eventTimeUs, mapped)
        if (result is InputSinkResult.Accepted) {
            updateTouchState(event)
            snapshot = snapshot.copy(touchFramesMapped = snapshot.touchFramesMapped + 1L)
        }
        return result
    }

    private fun mapPointerAbsolute(eventTimeUs: Long, event: InputPointerAbsolute): InputSinkResult {
        val acquisition = acquireGeometry(eventTimeUs) ?: return reject(snapshot.lastError)
        if (acquisition.resetActiveCoordinates) return suppress(ViewportInputMappingError.GeometryChanged)
        val geometry = acquisition.geometry
        val existing = findSlot(event.deviceSlot)
        val previousButtons = existing?.pointerButtons ?: 0
        val isActive = previousButtons != 0
        val outside = isOutside(event.xNormalized, event.yNormalized, geometry)
        if (outside && !isActive && event.buttonMask == 0) return suppress(ViewportInputMappingError.OutsideContent)
        if (outside && !isActive && event.buttonMask != 0) {
            snapshot = snapshot.copy(outsideContentDowns = snapshot.outsideContentDowns + 1L)
            return suppress(ViewportInputMappingError.OutsideContent)
        }
        val mapped = mapCoordinates(event.xNormalized, event.yNormalized, geometry, clamp = isActive)
            ?: return suppress(ViewportInputMappingError.OutsideContent)
        if (mapped.wasClamped) {
            snapshot = snapshot.copy(activeCoordinatesClamped = snapshot.activeCoordinatesClamped + 1L)
        }
        if (event.buttonMask != 0 && existing == null && !hasTrackingCapacity(event.deviceSlot)) {
            return reject(ViewportInputMappingError.CapacityExceeded)
        }
        val result = forward(
            eventTimeUs,
            event.copy(xNormalized = mapped.xNormalized, yNormalized = mapped.yNormalized),
        )
        if (result is InputSinkResult.Accepted) {
            updatePointerState(event.deviceSlot, event.deviceKind, event.buttonMask)
            snapshot = snapshot.copy(pointerAbsoluteMapped = snapshot.pointerAbsoluteMapped + 1L)
        }
        return result
    }

    private fun mapPointerRelative(eventTimeUs: Long, event: InputPointerRelative): InputSinkResult {
        val acquisition = acquireGeometry(eventTimeUs) ?: return reject(snapshot.lastError)
        if (acquisition.resetActiveCoordinates) return suppress(ViewportInputMappingError.GeometryChanged)
        val geometry = acquisition.geometry
        val scaledX = scaleRelative(event.deltaXQ16_16, geometry.surfaceWidthPx, geometry.contentWidthPx)
            ?: return reject(ViewportInputMappingError.RelativeOverflow)
        val scaledY = scaleRelative(event.deltaYQ16_16, geometry.surfaceHeightPx, geometry.contentHeightPx)
            ?: return reject(ViewportInputMappingError.RelativeOverflow)
        if (event.buttonMask != 0 && findSlot(event.deviceSlot) == null &&
            !hasTrackingCapacity(event.deviceSlot)
        ) {
            return reject(ViewportInputMappingError.CapacityExceeded)
        }
        val result = forward(
            eventTimeUs,
            event.copy(deltaXQ16_16 = scaledX, deltaYQ16_16 = scaledY),
        )
        if (result is InputSinkResult.Accepted) {
            updatePointerState(event.deviceSlot, event.deviceKind, event.buttonMask)
            snapshot = snapshot.copy(pointerRelativeMapped = snapshot.pointerRelativeMapped + 1L)
        }
        return result
    }

    private fun mapReset(eventTimeUs: Long, event: InputResetState): InputSinkResult {
        val result = forward(eventTimeUs, event)
        if (result is InputSinkResult.Accepted) {
            when (event.scope) {
                InputResetScope.ThisDevice -> clearSlot(event.deviceSlot)
                InputResetScope.AllDevices -> clearAllSlots()
                InputResetScope.Unknown -> Unit
            }
        }
        return result
    }

    private fun acquireGeometry(eventTimeUs: Long): GeometryAcquisition? {
        val geometry = geometryProvider.currentGeometry()
        if (!geometry.isUsable()) {
            snapshot = snapshot.copy(
                geometryValid = false,
                geometryUnavailableDrops = snapshot.geometryUnavailableDrops + 1L,
                lastError = ViewportInputMappingError.GeometryUnavailable,
            )
            return null
        }
        val previous = lastGeometry
        var resetActiveCoordinates = false
        if (previous != null && previous.geometryIdentityChanged(geometry)) {
            for (index in slots.indices) {
                val slot = slots[index] ?: continue
                if (!slot.hasActiveAbsoluteState) continue
                if (!emitGeometryReset(eventTimeUs, slot)) {
                    snapshot = snapshot.copy(lastError = ViewportInputMappingError.DownstreamRejected)
                    return null
                }
                slots[index] = null
                resetActiveCoordinates = true
            }
        }
        lastGeometry = geometry
        snapshot = snapshot.copy(
            geometryValid = true,
            surfaceGeneration = geometry.surfaceGeneration,
            videoConfigGeneration = geometry.videoConfigGeneration,
            lastError = ViewportInputMappingError.None,
        )
        return GeometryAcquisition(geometry, resetActiveCoordinates)
    }

    private fun emitGeometryReset(eventTimeUs: Long, slot: TrackedSlot): Boolean {
        val reset = InputResetState(
            deviceKind = slot.deviceKind,
            deviceSlot = slot.deviceSlot,
            scope = InputResetScope.ThisDevice,
            reason = InputResetReason.ErrorRecovery,
        )
        val result = forward(eventTimeUs, reset)
        if (result is InputSinkResult.Accepted) {
            snapshot = snapshot.copy(geometryChangeResets = snapshot.geometryChangeResets + 1L)
            return true
        }
        return false
    }

    private fun mapContact(
        contact: InputTouchContact,
        geometry: VideoViewportGeometry,
        clamp: Boolean,
    ): MappedContact? {
        val coordinates = mapCoordinates(contact.xNormalized, contact.yNormalized, geometry, clamp) ?: return null
        return MappedContact(
            contact = contact.copy(
                xNormalized = coordinates.xNormalized,
                yNormalized = coordinates.yNormalized,
            ),
            wasClamped = coordinates.wasClamped,
        )
    }

    private fun mapCoordinates(x: Int, y: Int, geometry: VideoViewportGeometry, clamp: Boolean): MappedCoordinates? {
        val mappedX = mapAxis(x, geometry.surfaceWidthPx, geometry.contentLeftPx, geometry.contentWidthPx, clamp)
            ?: return null
        val mappedY = mapAxis(y, geometry.surfaceHeightPx, geometry.contentTopPx, geometry.contentHeightPx, clamp)
            ?: return null
        return MappedCoordinates(
            xNormalized = mappedX.value,
            yNormalized = mappedY.value,
            wasClamped = mappedX.wasClamped || mappedY.wasClamped,
        )
    }

    private fun isOutside(contact: InputTouchContact, geometry: VideoViewportGeometry): Boolean =
        isOutside(contact.xNormalized, contact.yNormalized, geometry)

    private fun isOutside(x: Int, y: Int, geometry: VideoViewportGeometry): Boolean =
        mapAxis(x, geometry.surfaceWidthPx, geometry.contentLeftPx, geometry.contentWidthPx, clamp = false) == null ||
            mapAxis(y, geometry.surfaceHeightPx, geometry.contentTopPx, geometry.contentHeightPx, clamp = false) == null

    private fun mapAxis(
        normalized: Int,
        surfaceSize: Int,
        contentOffset: Int,
        contentSize: Int,
        clamp: Boolean,
    ): MappedAxis? {
        val surfaceCoordinate = normalized.toLong() * surfaceSize.toLong()
        val lower = contentOffset.toLong() * NORMALIZED_MAX.toLong()
        val upper = (contentOffset.toLong() + contentSize.toLong()) * NORMALIZED_MAX.toLong()
        if (surfaceCoordinate < lower || surfaceCoordinate > upper) {
            if (!clamp) return null
            val bounded = surfaceCoordinate.coerceIn(lower, upper)
            return MappedAxis(
                value = roundNonNegative(
                    bounded - lower,
                    contentSize.toLong(),
                ).coerceIn(0L, NORMALIZED_MAX.toLong()).toInt(),
                wasClamped = true,
            )
        }
        return MappedAxis(
            value = roundNonNegative(surfaceCoordinate - lower, contentSize.toLong())
                .coerceIn(0L, NORMALIZED_MAX.toLong())
                .toInt(),
            wasClamped = false,
        )
    }

    private fun scaleRelative(value: Int, surfaceSize: Int, contentSize: Int): Int? = try {
        val product = Math.multiplyExact(value.toLong(), surfaceSize.toLong())
        val scaled = roundSigned(product, contentSize.toLong())
        if (scaled in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) scaled.toInt() else null
    } catch (_: ArithmeticException) {
        null
    }

    private fun updateTouchState(event: InputTouchFrame) {
        when (event.action) {
            InputTouchAction.Down,
            InputTouchAction.PointerDown,
            InputTouchAction.Move,
            -> {
                val slot = findOrCreateSlot(event.deviceSlot, event.deviceKind) ?: return
                slot.deviceKind = event.deviceKind
                slot.touchActive = true
                slot.activeTouchIds.fill(false)
                event.contacts.forEach { slot.activeTouchIds[it.pointerId] = true }
            }
            InputTouchAction.PointerUp -> {
                findSlot(event.deviceSlot)?.let { slot ->
                    slot.touchActive = event.contacts.size > 1
                    slot.activeTouchIds.fill(false)
                    event.contacts.filter { it.pointerId != event.actionPointerId }
                        .forEach { slot.activeTouchIds[it.pointerId] = true }
                    removeSlotIfIdle(slot)
                }
            }
            InputTouchAction.Up,
            InputTouchAction.Cancel,
            InputTouchAction.Unknown,
            -> findSlot(event.deviceSlot)?.let(::clearTouch)
        }
    }

    private fun updatePointerState(deviceSlot: Int, deviceKind: InputDeviceKind, buttonMask: Int) {
        val slot = findSlot(deviceSlot) ?: if (buttonMask != 0) findOrCreateSlot(deviceSlot, deviceKind) else null
        if (slot == null) return
        slot.deviceKind = deviceKind
        slot.pointerButtons = buttonMask
        removeSlotIfIdle(slot)
    }

    private fun forward(eventTimeUs: Long, event: WarpnectInputEvent): InputSinkResult {
        val result = downstream.onInputEvent(eventTimeUs, event)
        snapshot = if (result is InputSinkResult.Accepted) {
            snapshot.copy(eventsForwarded = snapshot.eventsForwarded + 1L, lastError = ViewportInputMappingError.None)
        } else {
            snapshot.copy(lastError = ViewportInputMappingError.DownstreamRejected)
        }
        return result
    }

    private fun suppress(error: ViewportInputMappingError): InputSinkResult {
        snapshot = snapshot.copy(
            eventsSuppressed = snapshot.eventsSuppressed + 1L,
            lastError = error,
        )
        return InputSinkResult.Accepted
    }

    private fun reject(error: ViewportInputMappingError): InputSinkResult {
        snapshot = snapshot.copy(lastError = error)
        return InputSinkResult.Rejected("Viewport input mapping $error")
    }

    private fun findSlot(deviceSlot: Int): TrackedSlot? = slots.firstOrNull { it?.deviceSlot == deviceSlot }

    private fun findOrCreateSlot(deviceSlot: Int, deviceKind: InputDeviceKind): TrackedSlot? {
        findSlot(deviceSlot)?.let { return it }
        val emptyIndex = slots.indexOfFirst { it == null }
        if (emptyIndex < 0) return null
        return TrackedSlot(deviceSlot = deviceSlot, deviceKind = deviceKind).also { slots[emptyIndex] = it }
    }

    private fun hasTrackingCapacity(deviceSlot: Int): Boolean = findSlot(deviceSlot) != null || slots.any { it == null }

    private fun clearTouch(slot: TrackedSlot) {
        slot.touchActive = false
        slot.activeTouchIds.fill(false)
        removeSlotIfIdle(slot)
    }

    private fun clearSlot(deviceSlot: Int) {
        val index = slots.indexOfFirst { it?.deviceSlot == deviceSlot }
        if (index >= 0) slots[index] = null
    }

    private fun clearAllSlots() {
        slots.fill(null)
    }

    private fun removeSlotIfIdle(slot: TrackedSlot) {
        if (!slot.hasActiveAbsoluteState) clearSlot(slot.deviceSlot)
    }

    private fun WarpnectInputEvent.validationError(): InputModelError = when (this) {
        is InputKeyEvent -> validate()
        is InputTouchFrame -> validate()
        is InputPointerAbsolute -> validate()
        is InputPointerRelative -> validate()
        is InputScroll -> validate()
        is InputGamepadState -> validate()
        is InputResetState -> validate()
    }

    private fun InputTouchAction.isReleaseOrCancel(): Boolean = when (this) {
        InputTouchAction.Up,
        InputTouchAction.PointerUp,
        InputTouchAction.Cancel,
        -> true
        else -> false
    }

    private fun VideoViewportGeometry.geometryIdentityChanged(other: VideoViewportGeometry): Boolean =
        surfaceWidthPx != other.surfaceWidthPx ||
            surfaceHeightPx != other.surfaceHeightPx ||
            contentLeftPx != other.contentLeftPx ||
            contentTopPx != other.contentTopPx ||
            contentWidthPx != other.contentWidthPx ||
            contentHeightPx != other.contentHeightPx ||
            surfaceGeneration != other.surfaceGeneration ||
            videoConfigGeneration != other.videoConfigGeneration

    private fun roundNonNegative(numerator: Long, denominator: Long): Long =
        (numerator + denominator / 2L) / denominator

    private fun roundSigned(numerator: Long, denominator: Long): Long = if (numerator >= 0L) {
        (numerator + denominator / 2L) / denominator
    } else {
        -((-numerator + denominator / 2L) / denominator)
    }

    private data class TrackedSlot(
        val deviceSlot: Int,
        var deviceKind: InputDeviceKind,
        var touchActive: Boolean = false,
        val activeTouchIds: BooleanArray = BooleanArray(INPUT_MAX_TOUCH_CONTACTS),
        var pointerButtons: Int = 0,
    ) {
        val hasActiveAbsoluteState: Boolean
            get() = touchActive || pointerButtons != 0
    }

    private data class MappedAxis(
        val value: Int,
        val wasClamped: Boolean,
    )

    private data class MappedCoordinates(
        val xNormalized: Int,
        val yNormalized: Int,
        val wasClamped: Boolean,
    )

    private data class MappedContact(
        val contact: InputTouchContact,
        val wasClamped: Boolean,
    )

    private data class GeometryAcquisition(
        val geometry: VideoViewportGeometry,
        val resetActiveCoordinates: Boolean,
    )

    private companion object {
        const val NORMALIZED_MAX = 65_535
    }
}
