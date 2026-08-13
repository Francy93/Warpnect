package io.warpnect.input.reliability

import io.warpnect.input.model.INPUT_MAX_TOUCH_CONTACTS
import io.warpnect.input.model.INPUT_NO_ACTION_POINTER_ID
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyEvent
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

/** Metadata owned by the target receive path, not by Input Payload V1. */
data class InputEventEnvelope(
    val sequenceNumber: Long,
    val sourceEventTimeUs: Long,
    val event: WarpnectInputEvent,
)

data class InputConvergenceDispatchResult(
    val accepted: Boolean,
)

fun interface InputConvergenceSink {
    fun dispatch(envelope: InputEventEnvelope): InputConvergenceDispatchResult
}

enum class InputConvergenceOutcome {
    Forwarded,
    ForwardedWithTouchRepair,
    TransportDuplicateDropped,
    SemanticDuplicateDropped,
    StaleSequenceDropped,
    PreResetSequenceDropped,
    CapacityDropped,
    DownstreamFailure,
    Closed,
}

data class InputStateConvergenceResult(
    val outcome: InputConvergenceOutcome,
    val eventsForwarded: Int = 0,
    val syntheticTouchRepairEvents: Int = 0,
) {
    val isSuccess: Boolean
        get() = outcome != InputConvergenceOutcome.DownstreamFailure &&
            outcome != InputConvergenceOutcome.Closed
}

data class InputStateConvergenceSnapshot(
    val profile: InputPerformanceProfile = InputPerformanceProfile.BestEffortBaseline,
    val eventsReceived: Long = 0L,
    val eventsForwarded: Long = 0L,
    val transportDuplicateDrops: Long = 0L,
    val semanticDuplicateDrops: Long = 0L,
    val staleSequenceDrops: Long = 0L,
    val staleResetDrops: Long = 0L,
    val preResetSequenceDrops: Long = 0L,
    val stalePointerButtonRewrites: Long = 0L,
    val touchRepairEvents: Long = 0L,
    val touchRepairResets: Long = 0L,
    val capacityDrops: Long = 0L,
    val resetWatermarkUpdates: Long = 0L,
    val localResetWatermarks: Long = 0L,
    val trackedSlots: Int = 0,
    val trackedKeys: Int = 0,
    val recentTransportSequences: Int = 0,
    val recentSemanticEvents: Int = 0,
    val latestObservedSequence: Long? = null,
    val latestAcceptedSequence: Long? = null,
    val globalResetSequence: Long? = null,
    val lastOutcome: InputConvergenceOutcome = InputConvergenceOutcome.Forwarded,
)

/**
 * Immediate, bounded target-side convergence for Input Payload V1.
 *
 * It deliberately has no pending-event storage: every invocation forwards, repairs, or drops the
 * supplied envelope before returning on the persistent input receive context.
 */
class InputStateConvergenceController(
    private val config: InputReliabilityConfig,
) : AutoCloseable {
    private val recentSequences = RecentSequenceTracker(config.recentTransportSequenceCapacity)
    private val semanticEvents = RecentSemanticEventCache(config.recentSemanticDuplicateCapacity)
    private val slots: Array<ConvergenceSlot?>

    private var latestObservedSequence: Long? = null
    private var latestAcceptedSequence: Long? = null
    private var globalResetSequence: Long? = null
    private var snapshot = InputStateConvergenceSnapshot(profile = config.profile)
    private var closed = false

    init {
        require(config.validate() == InputReliabilityConfigurationError.None) {
            "Input reliability configuration is invalid"
        }
        slots = arrayOfNulls(config.maxTrackedSlots)
    }

    fun process(envelope: InputEventEnvelope, downstream: InputConvergenceSink): InputStateConvergenceResult {
        if (closed) return complete(InputConvergenceOutcome.Closed)
        if (!InputSequenceMath.isValid(envelope.sequenceNumber)) {
            return complete(InputConvergenceOutcome.StaleSequenceDropped, stale = true)
        }
        snapshot = snapshot.copy(eventsReceived = snapshot.eventsReceived + 1L)
        if (config.profile == InputPerformanceProfile.BestEffortBaseline) {
            return forwardBaseline(envelope, downstream)
        }
        if (!recentSequences.rememberIfNew(envelope.sequenceNumber)) {
            return complete(InputConvergenceOutcome.TransportDuplicateDropped, transportDuplicate = true)
        }
        recordLatestObserved(envelope.sequenceNumber)

        if (isBlockedByReset(envelope)) {
            return complete(InputConvergenceOutcome.PreResetSequenceDropped, preReset = true)
        }

        val semanticIdentity = InputSemanticIdentity.from(envelope)
        if (semanticEvents.isImmediateDuplicate(semanticIdentity, envelope.sequenceNumber)) {
            advanceDuplicateWatermarks(envelope)
            return complete(InputConvergenceOutcome.SemanticDuplicateDropped, semanticDuplicate = true)
        }

        return when (val event = envelope.event) {
            is InputKeyEvent -> processKey(envelope, event, semanticIdentity, downstream)
            is InputTouchFrame -> processTouch(envelope, event, semanticIdentity, downstream)
            is InputPointerAbsolute -> processPointerAbsolute(envelope, event, semanticIdentity, downstream)
            is InputPointerRelative -> processPointerRelative(envelope, event, semanticIdentity, downstream)
            is InputScroll -> processScroll(envelope, event, semanticIdentity, downstream)
            is InputGamepadState -> processGamepad(envelope, event, semanticIdentity, downstream)
            is InputResetState -> processReset(envelope, event, semanticIdentity, downstream)
        }
    }

    /** Call only after a local emergency/final target reset has completed successfully. */
    fun onLocalResetSucceeded() {
        slots.fill(null)
        latestObservedSequence?.let { latest ->
            if (globalResetSequence == null || InputSequenceMath.isNewer(latest, requireNotNull(globalResetSequence))) {
                globalResetSequence = latest
            }
        }
        snapshot = snapshot.copy(localResetWatermarks = snapshot.localResetWatermarks + 1L)
        refreshBounds(InputConvergenceOutcome.Forwarded)
    }

    fun snapshot(): InputStateConvergenceSnapshot = snapshot

    override fun close() {
        if (closed) return
        closed = true
        slots.fill(null)
        recentSequences.clear()
        semanticEvents.clear()
        refreshBounds(InputConvergenceOutcome.Closed)
    }

    private fun forwardBaseline(
        envelope: InputEventEnvelope,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val result = downstream.dispatch(envelope)
        return if (result.accepted) {
            complete(InputConvergenceOutcome.Forwarded, forwarded = 1)
        } else {
            complete(InputConvergenceOutcome.DownstreamFailure)
        }
    }

    private fun processKey(
        envelope: InputEventEnvelope,
        event: InputKeyEvent,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val slot = slotFor(event.deviceSlot)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        val key = slot.keyFor(event.usagePage, event.usageId)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        if (key.sequence != null && !InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(key.sequence))) {
            return complete(InputConvergenceOutcome.StaleSequenceDropped, stale = true)
        }
        val dispatched = downstream.dispatch(envelope)
        if (!dispatched.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)
        key.sequence = envelope.sequenceNumber
        recordAccepted(event, envelope.sequenceNumber)
        semanticEvents.remember(identity, envelope.sequenceNumber, criticalCopyDistance())
        return complete(InputConvergenceOutcome.Forwarded, forwarded = 1)
    }

    private fun processTouch(
        envelope: InputEventEnvelope,
        event: InputTouchFrame,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val slot = slotFor(event.deviceSlot)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        if (slot.touchSequence != null &&
            !InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(slot.touchSequence))
        ) {
            return complete(InputConvergenceOutcome.StaleSequenceDropped, stale = true)
        }

        val touchResult = if (config.touchRepairEnabled) {
            slot.touch.reconcile(envelope, downstream)
        } else {
            TouchReconciliationResult.forwardOriginal(envelope, downstream)
        }
        if (!touchResult.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)

        if (touchResult.usedReset) {
            recordResetWatermark(
                InputResetState(
                    deviceKind = event.deviceKind,
                    deviceSlot = event.deviceSlot,
                    scope = InputResetScope.ThisDevice,
                    reason = InputResetReason.ErrorRecovery,
                ),
                envelope.sequenceNumber,
            )
        } else {
            slot.touchSequence = envelope.sequenceNumber
        }
        recordAccepted(event, envelope.sequenceNumber)
        if (event.action.isTouchTransition()) {
            semanticEvents.remember(identity, envelope.sequenceNumber, criticalCopyDistance())
        }
        return complete(
            if (touchResult.syntheticEvents > 0 || touchResult.usedReset) {
                InputConvergenceOutcome.ForwardedWithTouchRepair
            } else {
                InputConvergenceOutcome.Forwarded
            },
            forwarded = touchResult.eventsForwarded,
            repairs = touchResult.syntheticEvents,
            repairReset = touchResult.usedReset,
        )
    }

    private fun processPointerAbsolute(
        envelope: InputEventEnvelope,
        event: InputPointerAbsolute,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val slot = slotFor(event.deviceSlot)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        if (slot.pointerAbsoluteSequence != null &&
            !InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(slot.pointerAbsoluteSequence))
        ) {
            return complete(InputConvergenceOutcome.StaleSequenceDropped, stale = true)
        }
        val rewritten = rewriteStalePointerButtons(envelope.sequenceNumber, event.buttonMask, slot)
        val mappedEvent = if (rewritten.buttonMask == event.buttonMask) {
            event
        } else {
            event.copy(
                buttonMask = rewritten.buttonMask,
            )
        }
        val mappedEnvelope = envelope.copy(event = mappedEvent)
        val critical = rewritten.buttonMask != slot.pointerButtons
        val dispatched = downstream.dispatch(mappedEnvelope)
        if (!dispatched.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)
        slot.pointerAbsoluteSequence = envelope.sequenceNumber
        advancePointerButtons(slot, envelope.sequenceNumber, rewritten.buttonMask)
        recordAccepted(event, envelope.sequenceNumber)
        if (critical) semanticEvents.remember(identity, envelope.sequenceNumber, criticalCopyDistance())
        return complete(
            InputConvergenceOutcome.Forwarded,
            forwarded = 1,
            staleButtonRewrite = rewritten.rewritten,
        )
    }

    private fun processPointerRelative(
        envelope: InputEventEnvelope,
        event: InputPointerRelative,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val slot = slotFor(event.deviceSlot)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        val rewritten = rewriteStalePointerButtons(envelope.sequenceNumber, event.buttonMask, slot)
        val mappedEvent = if (rewritten.buttonMask == event.buttonMask) {
            event
        } else {
            event.copy(
                buttonMask = rewritten.buttonMask,
            )
        }
        val critical = rewritten.buttonMask != slot.pointerButtons
        val dispatched = downstream.dispatch(envelope.copy(event = mappedEvent))
        if (!dispatched.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)
        advancePointerButtons(slot, envelope.sequenceNumber, rewritten.buttonMask)
        recordAccepted(event, envelope.sequenceNumber)
        if (critical) semanticEvents.remember(identity, envelope.sequenceNumber, criticalCopyDistance())
        return complete(
            InputConvergenceOutcome.Forwarded,
            forwarded = 1,
            staleButtonRewrite = rewritten.rewritten,
        )
    }

    private fun processScroll(
        envelope: InputEventEnvelope,
        event: InputScroll,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val slot = slotFor(event.deviceSlot)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        val rewritten = rewriteStalePointerButtons(envelope.sequenceNumber, event.buttonMask, slot)
        val mappedEvent = if (rewritten.buttonMask == event.buttonMask) {
            event
        } else {
            event.copy(
                buttonMask = rewritten.buttonMask,
            )
        }
        val critical = rewritten.buttonMask != slot.pointerButtons
        val dispatched = downstream.dispatch(envelope.copy(event = mappedEvent))
        if (!dispatched.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)
        advancePointerButtons(slot, envelope.sequenceNumber, rewritten.buttonMask)
        recordAccepted(event, envelope.sequenceNumber)
        if (critical) semanticEvents.remember(identity, envelope.sequenceNumber, criticalCopyDistance())
        return complete(
            InputConvergenceOutcome.Forwarded,
            forwarded = 1,
            staleButtonRewrite = rewritten.rewritten,
        )
    }

    private fun processGamepad(
        envelope: InputEventEnvelope,
        event: InputGamepadState,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        val slot = slotFor(event.deviceSlot)
            ?: return complete(InputConvergenceOutcome.CapacityDropped, capacity = true)
        if (slot.gamepadSequence != null &&
            !InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(slot.gamepadSequence))
        ) {
            return complete(InputConvergenceOutcome.StaleSequenceDropped, stale = true)
        }
        val critical = event.buttonMask != slot.gamepadButtons
        val dispatched = downstream.dispatch(envelope)
        if (!dispatched.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)
        slot.gamepadSequence = envelope.sequenceNumber
        slot.gamepadButtons = event.buttonMask
        recordAccepted(event, envelope.sequenceNumber)
        if (critical) semanticEvents.remember(identity, envelope.sequenceNumber, criticalCopyDistance())
        return complete(InputConvergenceOutcome.Forwarded, forwarded = 1)
    }

    private fun processReset(
        envelope: InputEventEnvelope,
        event: InputResetState,
        identity: InputSemanticIdentity,
        downstream: InputConvergenceSink,
    ): InputStateConvergenceResult {
        if (isStaleResetAgainstAcceptedState(event, envelope.sequenceNumber)) {
            return complete(InputConvergenceOutcome.StaleSequenceDropped, stale = true, staleReset = true)
        }
        val dispatched = downstream.dispatch(envelope)
        if (!dispatched.accepted) return complete(InputConvergenceOutcome.DownstreamFailure)
        recordResetWatermark(event, envelope.sequenceNumber)
        recordAccepted(event, envelope.sequenceNumber)
        semanticEvents.remember(identity, envelope.sequenceNumber, resetCopyDistance())
        return complete(InputConvergenceOutcome.Forwarded, forwarded = 1)
    }

    private fun isBlockedByReset(envelope: InputEventEnvelope): Boolean {
        val event = envelope.event
        val sequence = envelope.sequenceNumber
        val globalReset = globalResetSequence
        if (event is InputResetState && event.scope == InputResetScope.AllDevices) {
            return globalReset != null && !InputSequenceMath.isNewer(sequence, globalReset)
        }
        if (globalReset != null && !InputSequenceMath.isNewer(sequence, globalReset)) {
            return true
        }
        val deviceSlot = event.deviceSlotOrNull() ?: return false
        val slotReset = findSlot(deviceSlot)?.resetSequence ?: return false
        return !InputSequenceMath.isNewer(sequence, slotReset)
    }

    private fun advanceDuplicateWatermarks(envelope: InputEventEnvelope) {
        when (val event = envelope.event) {
            is InputKeyEvent -> findSlot(event.deviceSlot)?.findKey(event.usagePage, event.usageId)?.let { key ->
                if (
                    key.sequence == null ||
                    InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(key.sequence))
                ) {
                    key.sequence = envelope.sequenceNumber
                }
            }
            is InputTouchFrame -> findSlot(event.deviceSlot)?.let { slot ->
                if (
                    slot.touchSequence == null ||
                    InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(slot.touchSequence))
                ) {
                    slot.touchSequence = envelope.sequenceNumber
                }
            }
            is InputPointerAbsolute -> findSlot(event.deviceSlot)?.let { slot ->
                if (slot.pointerAbsoluteSequence == null ||
                    InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(slot.pointerAbsoluteSequence))
                ) {
                    slot.pointerAbsoluteSequence = envelope.sequenceNumber
                }
                advancePointerButtons(slot, envelope.sequenceNumber, event.buttonMask)
            }
            is InputPointerRelative -> findSlot(event.deviceSlot)?.let { slot ->
                advancePointerButtons(slot, envelope.sequenceNumber, event.buttonMask)
            }
            is InputScroll -> findSlot(event.deviceSlot)?.let { slot ->
                advancePointerButtons(slot, envelope.sequenceNumber, event.buttonMask)
            }
            is InputGamepadState -> findSlot(event.deviceSlot)?.let { slot ->
                if (
                    slot.gamepadSequence == null ||
                    InputSequenceMath.isNewer(envelope.sequenceNumber, requireNotNull(slot.gamepadSequence))
                ) {
                    slot.gamepadSequence = envelope.sequenceNumber
                    slot.gamepadButtons = event.buttonMask
                }
            }
            is InputResetState -> recordResetWatermark(
                event,
                envelope.sequenceNumber,
                clearState = false,
            )
        }
        recordAccepted(envelope.event, envelope.sequenceNumber)
    }

    private fun recordResetWatermark(event: InputResetState, sequence: Long, clearState: Boolean = true) {
        when (event.scope) {
            InputResetScope.AllDevices -> {
                if (
                    globalResetSequence == null ||
                    InputSequenceMath.isNewer(sequence, requireNotNull(globalResetSequence))
                ) {
                    globalResetSequence = sequence
                    if (clearState) slots.fill(null)
                    snapshot = snapshot.copy(resetWatermarkUpdates = snapshot.resetWatermarkUpdates + 1L)
                }
            }
            InputResetScope.ThisDevice -> {
                val slot = slotFor(event.deviceSlot) ?: return
                if (
                    slot.resetSequence == null ||
                    InputSequenceMath.isNewer(sequence, requireNotNull(slot.resetSequence))
                ) {
                    if (clearState) slot.clearForReset()
                    slot.resetSequence = sequence
                    snapshot = snapshot.copy(resetWatermarkUpdates = snapshot.resetWatermarkUpdates + 1L)
                }
            }
            InputResetScope.Unknown -> Unit
        }
    }

    private fun rewriteStalePointerButtons(
        sequence: Long,
        incomingButtonMask: Int,
        slot: ConvergenceSlot,
    ): PointerButtonRewrite {
        val watermark = slot.pointerButtonSequence
        if (watermark != null && !InputSequenceMath.isNewer(sequence, watermark)) {
            return PointerButtonRewrite(slot.pointerButtons, rewritten = incomingButtonMask != slot.pointerButtons)
        }
        return PointerButtonRewrite(incomingButtonMask, rewritten = false)
    }

    private fun advancePointerButtons(slot: ConvergenceSlot, sequence: Long, buttonMask: Int) {
        if (slot.pointerButtonSequence == null ||
            InputSequenceMath.isNewer(sequence, requireNotNull(slot.pointerButtonSequence))
        ) {
            slot.pointerButtonSequence = sequence
            slot.pointerButtons = buttonMask
        }
    }

    private fun slotFor(deviceSlot: Int): ConvergenceSlot? {
        findSlot(deviceSlot)?.let { return it }
        val index = slots.indexOfFirst { it == null }
        if (index < 0) return null
        return ConvergenceSlot(deviceSlot, config.maxTrackedKeysPerSlot, config.maxTouchRepairEvents).also {
            slots[index] = it
        }
    }

    private fun findSlot(deviceSlot: Int): ConvergenceSlot? = slots.firstOrNull { it?.deviceSlot == deviceSlot }

    private fun recordLatestObserved(sequence: Long) {
        if (
            latestObservedSequence == null ||
            InputSequenceMath.isNewer(sequence, requireNotNull(latestObservedSequence))
        ) {
            latestObservedSequence = sequence
        }
    }

    private fun recordAccepted(event: WarpnectInputEvent, sequence: Long) {
        if (
            latestAcceptedSequence == null ||
            InputSequenceMath.isNewer(sequence, requireNotNull(latestAcceptedSequence))
        ) {
            latestAcceptedSequence = sequence
        }
        event.deviceSlotOrNull()?.let { deviceSlot ->
            val slot = findSlot(deviceSlot) ?: return@let
            if (
                slot.latestAcceptedSequence == null ||
                InputSequenceMath.isNewer(sequence, requireNotNull(slot.latestAcceptedSequence))
            ) {
                slot.latestAcceptedSequence = sequence
            }
        }
    }

    private fun isStaleResetAgainstAcceptedState(event: InputResetState, sequence: Long): Boolean = when (event.scope) {
        InputResetScope.AllDevices ->
            latestAcceptedSequence != null &&
                !InputSequenceMath.isNewer(sequence, requireNotNull(latestAcceptedSequence))
        InputResetScope.ThisDevice -> findSlot(event.deviceSlot)?.latestAcceptedSequence?.let { latest ->
            !InputSequenceMath.isNewer(sequence, latest)
        } ?: false
        InputResetScope.Unknown -> false
    }

    private fun criticalCopyDistance(): Int = config.criticalCopies - 1

    private fun resetCopyDistance(): Int = config.resetCopies - 1

    private fun complete(
        outcome: InputConvergenceOutcome,
        forwarded: Int = 0,
        transportDuplicate: Boolean = false,
        semanticDuplicate: Boolean = false,
        stale: Boolean = false,
        staleReset: Boolean = false,
        preReset: Boolean = false,
        staleButtonRewrite: Boolean = false,
        repairs: Int = 0,
        repairReset: Boolean = false,
        capacity: Boolean = false,
    ): InputStateConvergenceResult {
        snapshot = snapshot.copy(
            eventsForwarded = snapshot.eventsForwarded + forwarded.toLong(),
            transportDuplicateDrops = snapshot.transportDuplicateDrops + if (transportDuplicate) 1L else 0L,
            semanticDuplicateDrops = snapshot.semanticDuplicateDrops + if (semanticDuplicate) 1L else 0L,
            staleSequenceDrops = snapshot.staleSequenceDrops + if (stale) 1L else 0L,
            staleResetDrops = snapshot.staleResetDrops + if (staleReset) 1L else 0L,
            preResetSequenceDrops = snapshot.preResetSequenceDrops + if (preReset) 1L else 0L,
            stalePointerButtonRewrites = snapshot.stalePointerButtonRewrites + if (staleButtonRewrite) 1L else 0L,
            touchRepairEvents = snapshot.touchRepairEvents + repairs.toLong(),
            touchRepairResets = snapshot.touchRepairResets + if (repairReset) 1L else 0L,
            capacityDrops = snapshot.capacityDrops + if (capacity) 1L else 0L,
        )
        refreshBounds(outcome)
        return InputStateConvergenceResult(outcome, forwarded, repairs)
    }

    private fun refreshBounds(outcome: InputConvergenceOutcome) {
        snapshot = snapshot.copy(
            trackedSlots = slots.count { it != null },
            trackedKeys = slots.sumOf { it?.trackedKeys ?: 0 },
            recentTransportSequences = recentSequences.size,
            recentSemanticEvents = semanticEvents.size,
            latestObservedSequence = latestObservedSequence,
            latestAcceptedSequence = latestAcceptedSequence,
            globalResetSequence = globalResetSequence,
            lastOutcome = outcome,
        )
    }

    private data class ConvergenceSlot(
        val deviceSlot: Int,
        val keys: Array<KeyWatermark?>,
        val touch: TouchStateReconciler,
        var latestAcceptedSequence: Long? = null,
        var resetSequence: Long? = null,
        var gamepadSequence: Long? = null,
        var gamepadButtons: Int = 0,
        var pointerAbsoluteSequence: Long? = null,
        var pointerButtonSequence: Long? = null,
        var pointerButtons: Int = 0,
        var touchSequence: Long? = null,
    ) {
        constructor(deviceSlot: Int, maxKeys: Int, maxTouchRepairEvents: Int) : this(
            deviceSlot = deviceSlot,
            keys = arrayOfNulls(maxKeys),
            touch = TouchStateReconciler(maxTouchRepairEvents),
        )

        val trackedKeys: Int
            get() = keys.count { it != null }

        fun keyFor(usagePage: Int, usageId: Int): KeyWatermark? {
            findKey(usagePage, usageId)?.let { return it }
            val index = keys.indexOfFirst { it == null }
            if (index < 0) return null
            return KeyWatermark(usagePage, usageId).also { keys[index] = it }
        }

        fun findKey(usagePage: Int, usageId: Int): KeyWatermark? = keys.firstOrNull { candidate ->
            candidate?.usagePage == usagePage && candidate?.usageId == usageId
        }

        fun clearForReset() {
            keys.fill(null)
            touch.clear()
            gamepadSequence = null
            gamepadButtons = 0
            pointerAbsoluteSequence = null
            pointerButtonSequence = null
            pointerButtons = 0
            touchSequence = null
        }
    }

    private data class KeyWatermark(
        val usagePage: Int,
        val usageId: Int,
        var sequence: Long? = null,
    )

    private data class PointerButtonRewrite(
        val buttonMask: Int,
        val rewritten: Boolean,
    )
}

/** Unsigned 32-bit SCL sequence comparison shared by every target-side freshness guard. */
object InputSequenceMath {
    private const val U32_MASK = 0xFFFF_FFFFL
    private const val MAX_FORWARD_DISTANCE = 0x7FFF_FFFFL

    fun isValid(sequence: Long): Boolean = sequence in 0L..U32_MASK

    fun isNewer(candidate: Long, baseline: Long): Boolean {
        if (!isValid(candidate) || !isValid(baseline)) return false
        val distance = (candidate - baseline) and U32_MASK
        return distance in 1L..MAX_FORWARD_DISTANCE
    }

    fun isWithinDistance(candidate: Long, reference: Long, distance: Int): Boolean {
        if (!isValid(candidate) || !isValid(reference) || distance < 0) return false
        val maximum = distance.toLong()
        val forward = (candidate - reference) and U32_MASK
        val reverse = (reference - candidate) and U32_MASK
        return forward <= maximum || reverse <= maximum
    }
}

private class RecentSequenceTracker(capacity: Int) {
    private val entries = LongArray(capacity) { EMPTY_SEQUENCE }
    private var next = 0
    var size: Int = 0
        private set

    fun rememberIfNew(sequence: Long): Boolean {
        if (entries.isEmpty()) return true
        if (entries.any { it == sequence }) return false
        if (entries[next] == EMPTY_SEQUENCE) size += 1
        entries[next] = sequence
        next = (next + 1) % entries.size
        return true
    }

    fun clear() {
        entries.fill(EMPTY_SEQUENCE)
        next = 0
        size = 0
    }

    private companion object {
        const val EMPTY_SEQUENCE = -1L
    }
}

private data class InputSemanticIdentity(
    val sourceEventTimeUs: Long,
    val event: WarpnectInputEvent,
) {
    companion object {
        fun from(envelope: InputEventEnvelope): InputSemanticIdentity = InputSemanticIdentity(
            sourceEventTimeUs = envelope.sourceEventTimeUs,
            event = envelope.event,
        )
    }
}

private class RecentSemanticEventCache(capacity: Int) {
    private val entries: Array<SemanticCacheEntry?> = arrayOfNulls(capacity)
    private var next = 0
    var size: Int = 0
        private set

    fun isImmediateDuplicate(identity: InputSemanticIdentity, sequence: Long): Boolean = entries.any { entry ->
        entry?.identity == identity &&
            InputSequenceMath.isWithinDistance(sequence, entry.sequenceNumber, entry.maxSequenceDistance)
    }

    fun remember(identity: InputSemanticIdentity, sequence: Long, maxSequenceDistance: Int) {
        if (entries.isEmpty()) return
        val existingIndex = entries.indexOfFirst { it?.identity == identity }
        if (existingIndex >= 0) {
            entries[existingIndex] = SemanticCacheEntry(identity, sequence, maxSequenceDistance)
            return
        }
        if (entries[next] == null) size += 1
        entries[next] = SemanticCacheEntry(identity, sequence, maxSequenceDistance)
        next = (next + 1) % entries.size
    }

    fun clear() {
        entries.fill(null)
        next = 0
        size = 0
    }

    private data class SemanticCacheEntry(
        val identity: InputSemanticIdentity,
        val sequenceNumber: Long,
        val maxSequenceDistance: Int,
    )
}

private data class TouchReconciliationResult(
    val accepted: Boolean,
    val eventsForwarded: Int,
    val syntheticEvents: Int,
    val usedReset: Boolean,
) {
    companion object {
        fun forwardOriginal(
            envelope: InputEventEnvelope,
            downstream: InputConvergenceSink,
        ): TouchReconciliationResult = if (downstream.dispatch(envelope).accepted) {
            TouchReconciliationResult(true, 1, 0, false)
        } else {
            TouchReconciliationResult(false, 0, 0, false)
        }
    }
}

/** Stable pointer-ID reconciliation over one logical touch slot. */
private class TouchStateReconciler(
    private val maxRepairEvents: Int,
) {
    private val contactsById: Array<InputTouchContact?> = arrayOfNulls(INPUT_MAX_TOUCH_CONTACTS)
    private var activeOrder = IntArray(INPUT_MAX_TOUCH_CONTACTS)
    private var activeCount = 0

    fun reconcile(envelope: InputEventEnvelope, downstream: InputConvergenceSink): TouchReconciliationResult {
        val event = envelope.event as InputTouchFrame
        if (event.action == InputTouchAction.Cancel) {
            if (activeCount == 0) return TouchReconciliationResult(true, 0, 0, false)
            val cancel = InputTouchFrame(
                deviceKind = event.deviceKind,
                deviceSlot = event.deviceSlot,
                action = InputTouchAction.Cancel,
                actionPointerId = INPUT_NO_ACTION_POINTER_ID,
                contacts = emptyList(),
            )
            if (!dispatch(envelope, cancel, downstream).accepted) return TouchReconciliationResult(false, 0, 0, false)
            clear()
            return TouchReconciliationResult(true, 1, 1, false)
        }
        if (isConsistent(event)) {
            if (!downstream.dispatch(envelope).accepted) return TouchReconciliationResult(false, 0, 0, false)
            setActive(desiredContacts(event))
            return TouchReconciliationResult(true, 1, 0, false)
        }

        val desired = desiredContacts(event)
        var removals = currentContacts().filter { current -> desired.none { it.pointerId == current.pointerId } }
        var additions = desired.filter { desiredContact -> !contains(desiredContact.pointerId) }

        // Pointer IDs are reusable after an Up. A new Down therefore has to terminate any
        // locally active stream first, even when the newly observed contact has the same ID.
        when (event.action) {
            InputTouchAction.Down -> if (activeCount != 0) {
                removals = currentContacts()
                additions = desired
            }
            InputTouchAction.PointerDown -> if (contains(event.actionPointerId)) {
                removals = currentContacts().filter { current ->
                    current.pointerId == event.actionPointerId ||
                        desired.none { it.pointerId == current.pointerId }
                }
                additions = desired.filter { contact ->
                    contact.pointerId == event.actionPointerId || !contains(contact.pointerId)
                }
            }
            else -> Unit
        }
        val required = removals.size + additions.size + if (desired.isNotEmpty()) 1 else 0
        if (required > maxRepairEvents) {
            val reset = InputResetState(
                deviceKind = event.deviceKind,
                deviceSlot = event.deviceSlot,
                scope = InputResetScope.ThisDevice,
                reason = InputResetReason.ErrorRecovery,
            )
            if (!dispatch(envelope, reset, downstream).accepted) return TouchReconciliationResult(false, 0, 0, false)
            clear()
            return TouchReconciliationResult(true, 1, 0, true)
        }

        var forwarded = 0
        var repairs = 0
        for (contact in removals) {
            val release = releaseFrame(event, contact.pointerId)
            if (!dispatch(
                    envelope,
                    release,
                    downstream,
                ).accepted
            ) {
                return TouchReconciliationResult(false, forwarded, repairs, false)
            }
            remove(contact.pointerId)
            forwarded += 1
            repairs += 1
        }
        for (contact in additions) {
            val addition = additionFrame(event, contact)
            if (!dispatch(
                    envelope,
                    addition,
                    downstream,
                ).accepted
            ) {
                return TouchReconciliationResult(false, forwarded, repairs, false)
            }
            append(contact)
            forwarded += 1
            repairs += 1
        }
        if (desired.isNotEmpty()) {
            val move = InputTouchFrame(
                deviceKind = event.deviceKind,
                deviceSlot = event.deviceSlot,
                action = InputTouchAction.Move,
                actionPointerId = INPUT_NO_ACTION_POINTER_ID,
                contacts = desired,
            )
            if (!dispatch(
                    envelope,
                    move,
                    downstream,
                ).accepted
            ) {
                return TouchReconciliationResult(false, forwarded, repairs, false)
            }
            setActive(desired)
            forwarded += 1
            repairs += 1
        }
        return TouchReconciliationResult(true, forwarded, repairs, false)
    }

    fun clear() {
        contactsById.fill(null)
        activeCount = 0
    }

    private fun isConsistent(event: InputTouchFrame): Boolean {
        val incoming = event.contacts.map { it.pointerId }.toSet()
        return when (event.action) {
            InputTouchAction.Down -> activeCount == 0 && event.contacts.size == 1
            InputTouchAction.PointerDown -> {
                event.actionPointerId !in activeIds() &&
                    incoming - event.actionPointerId == activeIds()
            }
            InputTouchAction.Move -> incoming == activeIds()
            InputTouchAction.PointerUp -> {
                event.actionPointerId in activeIds() && incoming == activeIds()
            }
            InputTouchAction.Up -> activeCount == 1 && event.actionPointerId in activeIds() && incoming == activeIds()
            InputTouchAction.Cancel -> true
            InputTouchAction.Unknown -> false
        }
    }

    private fun desiredContacts(event: InputTouchFrame): List<InputTouchContact> = when (event.action) {
        InputTouchAction.Up,
        InputTouchAction.PointerUp,
        -> event.contacts.filterNot { it.pointerId == event.actionPointerId }
        InputTouchAction.Cancel -> emptyList()
        else -> event.contacts
    }

    private fun releaseFrame(event: InputTouchFrame, pointerId: Int): InputTouchFrame {
        val contacts = currentContacts()
        val action = if (contacts.size == 1) InputTouchAction.Up else InputTouchAction.PointerUp
        return InputTouchFrame(
            deviceKind = event.deviceKind,
            deviceSlot = event.deviceSlot,
            action = action,
            actionPointerId = pointerId,
            contacts = contacts,
        )
    }

    private fun additionFrame(event: InputTouchFrame, contact: InputTouchContact): InputTouchFrame {
        val contacts = currentContacts() + contact
        return InputTouchFrame(
            deviceKind = event.deviceKind,
            deviceSlot = event.deviceSlot,
            action = if (activeCount == 0) InputTouchAction.Down else InputTouchAction.PointerDown,
            actionPointerId = contact.pointerId,
            contacts = contacts,
        )
    }

    private fun dispatch(
        envelope: InputEventEnvelope,
        event: WarpnectInputEvent,
        downstream: InputConvergenceSink,
    ): InputConvergenceDispatchResult = downstream.dispatch(envelope.copy(event = event))

    private fun setActive(contacts: List<InputTouchContact>) {
        clear()
        contacts.forEach(::append)
    }

    private fun append(contact: InputTouchContact) {
        contactsById[contact.pointerId] = contact
        activeOrder[activeCount] = contact.pointerId
        activeCount += 1
    }

    private fun remove(pointerId: Int) {
        val index = (0 until activeCount).firstOrNull { activeOrder[it] == pointerId } ?: return
        for (cursor in index until activeCount - 1) activeOrder[cursor] = activeOrder[cursor + 1]
        activeCount -= 1
        contactsById[pointerId] = null
    }

    private fun contains(pointerId: Int): Boolean = contactsById[pointerId] != null

    private fun activeIds(): Set<Int> = currentContacts().map { it.pointerId }.toSet()

    private fun currentContacts(): List<InputTouchContact> = buildList(activeCount) {
        for (index in 0 until activeCount) {
            contactsById[activeOrder[index]]?.let(::add)
        }
    }
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

private fun WarpnectInputEvent.deviceSlotOrNull(): Int? = when (this) {
    is InputKeyEvent -> deviceSlot
    is InputTouchFrame -> deviceSlot
    is InputPointerAbsolute -> deviceSlot
    is InputPointerRelative -> deviceSlot
    is InputScroll -> deviceSlot
    is InputGamepadState -> deviceSlot
    is InputResetState -> if (scope == InputResetScope.ThisDevice) deviceSlot else null
}
