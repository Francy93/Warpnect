package io.warpnect.input.reliability

import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputScroll
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.WarpnectInputEvent

/** The RFC-004F behavior remains available for deterministic before/after comparisons. */
enum class InputPerformanceProfile {
    BestEffortBaseline,
    UltraLowLatencyConvergent,
}

/** Local-only reliability meaning. It is never serialized in Input Payload V1. */
enum class InputReliabilityClass {
    FreshSnapshot,
    IncrementalDelta,
    CriticalTransition,
    Reset,
}

enum class InputReliabilityConfigurationError {
    None,
    InvalidCopies,
    InvalidCacheCapacity,
    InvalidTrackingCapacity,
    InvalidTouchRepairBound,
    ReorderWaitUnsupported,
}

/**
 * Fixed, inspectable Phase 4 reliability settings.
 *
 * The production profile is intentionally no-wait: copies are attempted synchronously and the
 * target only remembers bounded recent state. It never owns future work.
 */
data class InputReliabilityConfig(
    val profile: InputPerformanceProfile = InputPerformanceProfile.UltraLowLatencyConvergent,
    val criticalCopies: Int = DEFAULT_CRITICAL_COPIES,
    val resetCopies: Int = DEFAULT_RESET_COPIES,
    val recentTransportSequenceCapacity: Int = DEFAULT_TRANSPORT_SEQUENCE_CAPACITY,
    val recentSemanticDuplicateCapacity: Int = DEFAULT_SEMANTIC_DUPLICATE_CAPACITY,
    val touchRepairEnabled: Boolean = true,
    val maxTouchRepairEvents: Int = DEFAULT_MAX_TOUCH_REPAIR_EVENTS,
    val maxTrackedSlots: Int = DEFAULT_MAX_TRACKED_SLOTS,
    val maxTrackedKeysPerSlot: Int = DEFAULT_MAX_TRACKED_KEYS_PER_SLOT,
    val networkReorderWaitUs: Long = 0L,
) {
    fun validate(): InputReliabilityConfigurationError = when {
        networkReorderWaitUs != 0L -> InputReliabilityConfigurationError.ReorderWaitUnsupported
        criticalCopies !in 1..MAX_IMMEDIATE_COPIES || resetCopies !in 1..MAX_IMMEDIATE_COPIES ->
            InputReliabilityConfigurationError.InvalidCopies
        recentTransportSequenceCapacity !in 0..MAX_RECENT_SEQUENCE_CAPACITY ||
            recentSemanticDuplicateCapacity !in 0..MAX_SEMANTIC_DUPLICATE_CAPACITY ->
            InputReliabilityConfigurationError.InvalidCacheCapacity
        maxTrackedSlots !in 1..MAX_TRACKED_SLOTS ||
            maxTrackedKeysPerSlot !in 1..MAX_TRACKED_KEYS_PER_SLOT ->
            InputReliabilityConfigurationError.InvalidTrackingCapacity
        maxTouchRepairEvents !in 0..MAX_TOUCH_REPAIR_EVENTS ->
            InputReliabilityConfigurationError.InvalidTouchRepairBound
        profile == InputPerformanceProfile.BestEffortBaseline &&
            (
                criticalCopies != 1 || resetCopies != 1 ||
                    recentTransportSequenceCapacity != 0 || recentSemanticDuplicateCapacity != 0 ||
                    touchRepairEnabled || maxTouchRepairEvents != 0
                ) ->
            InputReliabilityConfigurationError.InvalidCopies
        else -> InputReliabilityConfigurationError.None
    }

    fun copyCountFor(reliabilityClass: InputReliabilityClass): Int = when {
        profile == InputPerformanceProfile.BestEffortBaseline -> 1
        reliabilityClass == InputReliabilityClass.CriticalTransition -> criticalCopies
        reliabilityClass == InputReliabilityClass.Reset -> resetCopies
        else -> 1
    }

    companion object {
        const val DEFAULT_CRITICAL_COPIES = 2
        const val DEFAULT_RESET_COPIES = 3
        const val DEFAULT_TRANSPORT_SEQUENCE_CAPACITY = 64
        const val DEFAULT_SEMANTIC_DUPLICATE_CAPACITY = 32
        const val DEFAULT_MAX_TOUCH_REPAIR_EVENTS = 64
        const val DEFAULT_MAX_TRACKED_SLOTS = 32
        const val DEFAULT_MAX_TRACKED_KEYS_PER_SLOT = 64

        const val MAX_IMMEDIATE_COPIES = 3
        const val MAX_RECENT_SEQUENCE_CAPACITY = 128
        const val MAX_SEMANTIC_DUPLICATE_CAPACITY = 64
        const val MAX_TOUCH_REPAIR_EVENTS = 64
        const val MAX_TRACKED_SLOTS = 32
        const val MAX_TRACKED_KEYS_PER_SLOT = 64

        fun bestEffortBaseline(): InputReliabilityConfig = InputReliabilityConfig(
            profile = InputPerformanceProfile.BestEffortBaseline,
            criticalCopies = 1,
            resetCopies = 1,
            recentTransportSequenceCapacity = 0,
            recentSemanticDuplicateCapacity = 0,
            touchRepairEnabled = false,
            maxTouchRepairEvents = 0,
        )

        fun ultraLowLatencyConvergent(): InputReliabilityConfig = InputReliabilityConfig()
    }
}

data class InputReliabilityClassification(
    val reliabilityClass: InputReliabilityClass,
    val capacityAvailable: Boolean = true,
)

data class InputReliabilityClassifierSnapshot(
    val eventsClassified: Long = 0L,
    val freshSnapshots: Long = 0L,
    val incrementalDeltas: Long = 0L,
    val criticalTransitions: Long = 0L,
    val resets: Long = 0L,
    val stateCapacityDrops: Long = 0L,
    val trackedSlots: Int = 0,
)

/**
 * Bounded sender-side semantic classifier. It remembers only the current button masks necessary
 * to decide whether a pointer or gamepad observation is a critical transition.
 */
class InputReliabilityClassifier(
    private val config: InputReliabilityConfig,
) {
    private val slots: Array<SourceSlot?>
    private var snapshot = InputReliabilityClassifierSnapshot()
    private var pendingRollback: SourceSlotRollback? = null

    init {
        require(config.validate() == InputReliabilityConfigurationError.None) {
            "Input reliability configuration is invalid"
        }
        slots = arrayOfNulls(config.maxTrackedSlots)
    }

    fun classify(event: WarpnectInputEvent): InputReliabilityClassification {
        pendingRollback = null
        val classification = when (event) {
            is InputKeyEvent -> InputReliabilityClassification(InputReliabilityClass.CriticalTransition)
            is InputTouchFrame -> InputReliabilityClassification(
                if (event.action.isTouchTransition()) {
                    InputReliabilityClass.CriticalTransition
                } else {
                    InputReliabilityClass.FreshSnapshot
                },
            )
            is InputPointerAbsolute -> pointerClassification(event.deviceSlot, event.buttonMask, fresh = true)
            is InputPointerRelative -> pointerClassification(event.deviceSlot, event.buttonMask, fresh = false)
            is InputScroll -> pointerClassification(event.deviceSlot, event.buttonMask, fresh = false)
            is InputGamepadState -> gamepadClassification(event)
            is InputResetState -> {
                clearForReset(event)
                InputReliabilityClassification(InputReliabilityClass.Reset)
            }
        }
        snapshot = snapshot.copy(
            eventsClassified = snapshot.eventsClassified + 1L,
            freshSnapshots = snapshot.freshSnapshots +
                if (classification.reliabilityClass == InputReliabilityClass.FreshSnapshot) 1L else 0L,
            incrementalDeltas = snapshot.incrementalDeltas +
                if (classification.reliabilityClass == InputReliabilityClass.IncrementalDelta) 1L else 0L,
            criticalTransitions = snapshot.criticalTransitions +
                if (classification.reliabilityClass == InputReliabilityClass.CriticalTransition) 1L else 0L,
            resets = snapshot.resets + if (classification.reliabilityClass == InputReliabilityClass.Reset) 1L else 0L,
            stateCapacityDrops = snapshot.stateCapacityDrops + if (!classification.capacityAvailable) 1L else 0L,
            trackedSlots = slots.count { it != null },
        )
        return classification
    }

    fun snapshot(): InputReliabilityClassifierSnapshot = snapshot

    /** Completes the immediate submission that immediately follows [classify]. */
    fun completeSubmission(anyCopySent: Boolean) {
        if (anyCopySent) {
            pendingRollback = null
            return
        }
        val rollback = pendingRollback ?: return
        val index = slots.indexOfFirst { it?.deviceSlot == rollback.deviceSlot }
        if (rollback.previous == null) {
            if (index >= 0) slots[index] = null
        } else if (index >= 0) {
            slots[index]?.restore(rollback.previous)
        } else {
            val emptyIndex = slots.indexOfFirst { it == null }
            if (emptyIndex >= 0) {
                slots[emptyIndex] = SourceSlot(
                    deviceSlot = rollback.deviceSlot,
                    pointerButtons = rollback.previous.pointerButtons,
                    gamepadButtons = rollback.previous.gamepadButtons,
                )
            }
        }
        pendingRollback = null
        snapshot = snapshot.copy(trackedSlots = slots.count { it != null })
    }

    fun clear() {
        slots.fill(null)
        pendingRollback = null
        snapshot = snapshot.copy(trackedSlots = 0)
    }

    private fun pointerClassification(
        deviceSlot: Int,
        buttonMask: Int,
        fresh: Boolean,
    ): InputReliabilityClassification {
        val existing = findSlot(deviceSlot)
        if (existing == null && buttonMask == 0) {
            return InputReliabilityClassification(
                if (fresh) InputReliabilityClass.FreshSnapshot else InputReliabilityClass.IncrementalDelta,
            )
        }
        pendingRollback = SourceSlotRollback(deviceSlot, existing?.state())
        val slot = existing ?: createSlot(deviceSlot)
            ?: return InputReliabilityClassification(InputReliabilityClass.CriticalTransition, false)
        val changed = slot.pointerButtons != buttonMask
        slot.pointerButtons = buttonMask
        removeIfIdle(slot)
        return InputReliabilityClassification(
            if (changed) {
                InputReliabilityClass.CriticalTransition
            } else if (fresh) {
                InputReliabilityClass.FreshSnapshot
            } else {
                InputReliabilityClass.IncrementalDelta
            },
        )
    }

    private fun gamepadClassification(event: InputGamepadState): InputReliabilityClassification {
        val existing = findSlot(event.deviceSlot)
        if (existing == null && event.buttonMask == 0) {
            return InputReliabilityClassification(InputReliabilityClass.FreshSnapshot)
        }
        pendingRollback = SourceSlotRollback(event.deviceSlot, existing?.state())
        val slot = existing ?: createSlot(event.deviceSlot)
            ?: return InputReliabilityClassification(InputReliabilityClass.CriticalTransition, false)
        val changed = slot.gamepadButtons != event.buttonMask
        slot.gamepadButtons = event.buttonMask
        removeIfIdle(slot)
        return InputReliabilityClassification(
            if (changed) InputReliabilityClass.CriticalTransition else InputReliabilityClass.FreshSnapshot,
        )
    }

    private fun clearForReset(event: InputResetState) {
        when (event.scope) {
            InputResetScope.ThisDevice -> clearSlot(event.deviceSlot)
            InputResetScope.AllDevices -> slots.fill(null)
            InputResetScope.Unknown -> Unit
        }
    }

    private fun findSlot(deviceSlot: Int): SourceSlot? = slots.firstOrNull { it?.deviceSlot == deviceSlot }

    private fun createSlot(deviceSlot: Int): SourceSlot? {
        val index = slots.indexOfFirst { it == null }
        if (index < 0) return null
        return SourceSlot(deviceSlot).also { slots[index] = it }
    }

    private fun clearSlot(deviceSlot: Int) {
        val index = slots.indexOfFirst { it?.deviceSlot == deviceSlot }
        if (index >= 0) slots[index] = null
    }

    private fun removeIfIdle(slot: SourceSlot) {
        if (slot.pointerButtons == 0 && slot.gamepadButtons == 0) clearSlot(slot.deviceSlot)
    }

    private data class SourceSlot(
        val deviceSlot: Int,
        var pointerButtons: Int = 0,
        var gamepadButtons: Int = 0,
    ) {
        fun state(): SourceSlotState = SourceSlotState(pointerButtons, gamepadButtons)

        fun restore(state: SourceSlotState) {
            pointerButtons = state.pointerButtons
            gamepadButtons = state.gamepadButtons
        }
    }

    /** A synchronous state rollback token, never an event or retransmission queue entry. */
    private data class SourceSlotRollback(
        val deviceSlot: Int,
        val previous: SourceSlotState?,
    )

    private data class SourceSlotState(
        val pointerButtons: Int,
        val gamepadButtons: Int,
    )
}

private fun InputTouchAction.isTouchTransition(): Boolean = when (this) {
    InputTouchAction.Down,
    InputTouchAction.Up,
    InputTouchAction.Cancel,
    InputTouchAction.PointerDown,
    InputTouchAction.PointerUp,
    -> true
    else -> false
}
