@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.telemetry.ClockDomainId
import io.warpnect.telemetry.TelemetryCounterHandle
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySourceDefinition
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun interface DiagnosticEventClock {
    fun nowNs(): ULong
}

object SystemDiagnosticEventClock : DiagnosticEventClock {
    override fun nowNs(): ULong = System.nanoTime().coerceAtLeast(0L).toULong()
}

fun interface DiagnosticLogSink {
    fun emit(descriptor: DiagnosticEventDescriptor, event: DiagnosticEventRecord)
}

object DisabledDiagnosticLogSink : DiagnosticLogSink {
    override fun emit(descriptor: DiagnosticEventDescriptor, event: DiagnosticEventRecord) = Unit
}

private object DisabledDiagnosticCounter : TelemetryCounterHandle {
    override fun increment() = Unit

    override fun add(delta: ULong) = Unit
}

fun interface NativeDiagnosticEventProvider : AutoCloseable {
    fun collect(cursor: ULong, limit: Int): NativeDiagnosticEventBatch

    override fun close() = Unit
}

data class NativeDiagnosticEventBatch(
    val status: DiagnosticEventProviderStatus,
    val batch: DiagnosticEventBatch,
)

enum class DiagnosticEventProviderStatus {
    Available,
    Disabled,
    Malformed,
    Failed,
    Closed,
}

data class DiagnosticEventCursor(
    val kotlinSequence: ULong = 0u,
    val nativeSequence: ULong = 0u,
)

data class DiagnosticEventSnapshot(
    val kotlin: DiagnosticEventBatch,
    val native: NativeDiagnosticEventBatch,
)

/**
 * Process-scoped, local-only diagnostic history. It has no delivery queue or scheduler; consumers
 * explicitly pull bounded batches and providers retain independent sequence/clock domains.
 */
class DiagnosticEventHub(
    private val clock: DiagnosticEventClock = SystemDiagnosticEventClock,
    private val clockDomain: ClockDomainId = ClockDomainId.AndroidMonotonic,
    telemetryHub: TelemetryHub? = null,
    private val nativeProvider: NativeDiagnosticEventProvider? = null,
    private val logSink: DiagnosticLogSink = DisabledDiagnosticLogSink,
    private val historyEnabled: Boolean = true,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Presentation consumers can report disabled history without treating an empty ring as healthy. */
    val enabled: Boolean
        get() = historyEnabled && !closed.get()
    private val ring = DiagnosticEventRing()
    private val lastObservedOverwrites = AtomicLong(0L)
    private val lastObservedNativeOverwrites = AtomicLong(0L)
    private val metricSource = if (historyEnabled) {
        telemetryHub?.registerSource(
            TelemetrySourceDefinition(
                TelemetryScope.Process,
                listOf(
                    TelemetryMetricIds.DiagnosticEventRecorded,
                    TelemetryMetricIds.DiagnosticEventOverwritten,
                    TelemetryMetricIds.DiagnosticSnapshotCount,
                    TelemetryMetricIds.DiagnosticNativeParseFailure,
                    TelemetryMetricIds.DiagnosticLogSinkFailure,
                ),
            ),
        )?.source
    } else {
        null
    }
    private val eventRecorded =
        metricSource?.counter(TelemetryMetricIds.DiagnosticEventRecorded) ?: DisabledDiagnosticCounter
    private val eventOverwritten =
        metricSource?.counter(TelemetryMetricIds.DiagnosticEventOverwritten) ?: DisabledDiagnosticCounter
    private val snapshotCount =
        metricSource?.counter(TelemetryMetricIds.DiagnosticSnapshotCount) ?: DisabledDiagnosticCounter
    private val nativeParseFailure =
        metricSource?.counter(TelemetryMetricIds.DiagnosticNativeParseFailure) ?: DisabledDiagnosticCounter
    private val logSinkFailure =
        metricSource?.counter(TelemetryMetricIds.DiagnosticLogSinkFailure) ?: DisabledDiagnosticCounter

    init {
        if (historyEnabled) writer(TelemetryScope.Process).emit(DiagnosticEventIds.HistoryStarted)
    }

    fun writer(
        scope: TelemetryScope,
        sourceId: io.warpnect.telemetry.TelemetrySourceId? = null,
    ): DiagnosticEventWriter = if (closed.get() || !historyEnabled) {
        DisabledDiagnosticEventWriter
    } else {
        BoundDiagnosticEventWriter(
            this,
            DiagnosticScopeSnapshot.from(scope, sourceId),
        )
    }

    fun snapshotSince(
        cursor: DiagnosticEventCursor = DiagnosticEventCursor(),
        limit: Int = DEFAULT_DIAGNOSTIC_SNAPSHOT_LIMIT,
    ): DiagnosticEventSnapshot {
        require(limit in 1..MAX_DIAGNOSTIC_SNAPSHOT_LIMIT)
        if (!historyEnabled || closed.get()) {
            val empty = DiagnosticEventBatch.empty(cursor.kotlinSequence)
            return DiagnosticEventSnapshot(
                empty,
                NativeDiagnosticEventBatch(
                    DiagnosticEventProviderStatus.Disabled,
                    DiagnosticEventBatch.empty(cursor.nativeSequence),
                ),
            )
        }
        snapshotCount.increment()
        val kotlinBatch = ring.snapshotSince(cursor.kotlinSequence, limit)
        val native = nativeProvider?.let { provider ->
            runCatching { provider.collect(cursor.nativeSequence, limit) }
                .getOrElse {
                    emitFrameworkFailure()
                    NativeDiagnosticEventBatch(
                        DiagnosticEventProviderStatus.Failed,
                        DiagnosticEventBatch.empty(cursor.nativeSequence),
                    )
                }
        } ?: NativeDiagnosticEventBatch(
            DiagnosticEventProviderStatus.Disabled,
            DiagnosticEventBatch.empty(cursor.nativeSequence),
        )
        if (native.status == DiagnosticEventProviderStatus.Malformed) {
            nativeParseFailure.increment()
            emitFrameworkMalformed()
        }
        recordNativeOverwrites(native.batch.overwritten)
        return DiagnosticEventSnapshot(kotlinBatch, native)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        metricSource?.close()
        nativeProvider?.close()
    }

    internal fun emit(
        scope: DiagnosticScopeSnapshot,
        typeId: DiagnosticEventTypeId,
        payload0: ULong = 0u,
        payload1: ULong = 0u,
        payload2: ULong = 0u,
        payload3: ULong = 0u,
    ): Boolean {
        if (closed.get() || !historyEnabled) return false
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(typeId) ?: return false
        val sequence = ring.emit(descriptor, clock.nowNs(), clockDomain, scope, payload0, payload1, payload2, payload3)
        if (sequence == 0uL) return false
        eventRecorded.increment()
        val overwritten = ring.overwrittenCount().toLong()
        val previous = lastObservedOverwrites.getAndSet(overwritten)
        if (overwritten > previous) eventOverwritten.add((overwritten - previous).toULong())
        if (logSink !== DisabledDiagnosticLogSink) {
            // Human-readable formatting remains the sink's responsibility; the retained model has no strings.
            runCatching {
                val event = ring.snapshotSince(sequence - 1u, 1).events.firstOrNull()
                if (event != null) logSink.emit(descriptor, event)
            }.onFailure { logSinkFailure.increment() }
        }
        return true
    }

    private fun emitFrameworkFailure() {
        if (!closed.get()) {
            writer(
                TelemetryScope.Process,
            ).emit(DiagnosticEventIds.ProviderFailed, DiagnosticReason.FatalInternalError.code)
        }
    }

    private fun emitFrameworkMalformed() {
        if (!closed.get()) writer(TelemetryScope.Process).emit(DiagnosticEventIds.NativeBridgeMalformed)
    }

    private fun recordNativeOverwrites(overwritten: ULong) {
        val current = overwritten.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
        val previous = lastObservedNativeOverwrites.getAndSet(current)
        if (current > previous) eventOverwritten.add((current - previous).toULong())
    }

    companion object {
        fun disabled(): DiagnosticEventHub = DiagnosticEventHub(historyEnabled = false)
    }
}

interface DiagnosticEventWriter {
    val enabled: Boolean

    fun emit(
        typeId: DiagnosticEventTypeId,
        payload0: ULong = 0u,
        payload1: ULong = 0u,
        payload2: ULong = 0u,
        payload3: ULong = 0u,
    ): Boolean
}

private class BoundDiagnosticEventWriter(
    private val hub: DiagnosticEventHub,
    private val scope: DiagnosticScopeSnapshot,
) : DiagnosticEventWriter {
    override val enabled: Boolean = true

    override fun emit(
        typeId: DiagnosticEventTypeId,
        payload0: ULong,
        payload1: ULong,
        payload2: ULong,
        payload3: ULong,
    ): Boolean = hub.emit(scope, typeId, payload0, payload1, payload2, payload3)
}

private object DisabledDiagnosticEventWriter : DiagnosticEventWriter {
    override val enabled: Boolean = false

    override fun emit(
        typeId: DiagnosticEventTypeId,
        payload0: ULong,
        payload1: ULong,
        payload2: ULong,
        payload3: ULong,
    ) = false
}
