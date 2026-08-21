@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class TelemetrySourceDefinition(
    val scope: TelemetryScope,
    val metricIds: List<TelemetryMetricId>,
)

interface TelemetrySource : AutoCloseable {
    val sourceId: TelemetrySourceId?
    val enabled: Boolean

    fun counter(id: TelemetryMetricId): TelemetryCounterHandle

    fun gauge(id: TelemetryMetricId): TelemetryGaugeHandle

    fun histogram(id: TelemetryMetricId): TelemetryHistogramHandle
}

data class TelemetrySourceRegistration(
    val source: TelemetrySource,
    val error: TelemetrySnapshotError? = null,
)

class TelemetryHub private constructor(
    private val clock: TelemetryMonotonicClock,
    descriptors: List<TelemetryMetricDescriptor>,
    val enabled: Boolean,
) : AutoCloseable {
    private val registryLock = Any()
    private val sources = LinkedHashMap<TelemetrySourceId, RegisteredTelemetrySource>()
    private val providers = LinkedHashSet<TelemetrySnapshotProvider>()
    private val closed = AtomicBoolean(false)
    private val nextSourceId = AtomicLong(2L)
    private val snapshotSequence = AtomicLong(0L)
    private val descriptorsById: Map<TelemetryMetricId, TelemetryMetricDescriptor>

    private val sourceActive = TelemetryGaugeI64()
    private val sourceRegistrationRejected = TelemetryCounterU64()
    private val snapshotCount = TelemetryCounterU64()
    private val snapshotPartial = TelemetryCounterU64()
    private val snapshotProviderFailure = TelemetryCounterU64()
    private val updateOverflow = TelemetryCounterU64()
    private val snapshotDuration: TelemetryHistogramU64

    private val frameworkSourceId = TelemetrySourceId(1u)

    constructor(clock: TelemetryMonotonicClock = SystemTelemetryMonotonicClock) :
        this(
            clock = clock,
            descriptors = TelemetryDescriptorCatalog.descriptors,
            enabled = true,
        )

    internal constructor(
        clock: TelemetryMonotonicClock,
        descriptors: List<TelemetryMetricDescriptor>,
    ) : this(
        clock = clock,
        descriptors = descriptors,
        enabled = true,
    )

    companion object {
        /** Explicit no-op fallback for composition failures; it never changes Session behavior. */
        fun disabled(): TelemetryHub = TelemetryHub(
            clock = SystemTelemetryMonotonicClock,
            descriptors = TelemetryDescriptorCatalog.descriptors,
            enabled = false,
        )
    }

    init {
        TelemetryDescriptorCatalog.validate(descriptors)
        descriptorsById = descriptors.associateBy { it.id }
        snapshotDuration = TelemetryHistogramU64(
            descriptorsById.getValue(TelemetryMetricIds.SnapshotDuration).histogramBoundaries,
        )
    }

    fun registerSource(definition: TelemetrySourceDefinition): TelemetrySourceRegistration {
        if (!enabled) return TelemetrySourceRegistration(DisabledTelemetrySource, TelemetrySnapshotError.Disabled)
        if (closed.get()) return TelemetrySourceRegistration(DisabledTelemetrySource, TelemetrySnapshotError.Closed)
        val descriptors = definition.metricIds.map { descriptorsById[it] }
        if (
            definition.metricIds.size > MAX_TELEMETRY_METRICS_PER_SOURCE ||
            descriptors.any { it == null || definition.scope.kind !in it.allowedScopes } ||
            descriptors.count { it?.kind == TelemetryMetricKind.HistogramU64 } > MAX_TELEMETRY_HISTOGRAMS_PER_SOURCE
        ) {
            sourceRegistrationRejected.increment()
            return TelemetrySourceRegistration(DisabledTelemetrySource, TelemetrySnapshotError.DescriptorViolation)
        }

        synchronized(registryLock) {
            if (closed.get()) {
                return TelemetrySourceRegistration(DisabledTelemetrySource, TelemetrySnapshotError.Closed)
            }
            if (sources.size >= MAX_TELEMETRY_SOURCES) {
                sourceRegistrationRejected.increment()
                return TelemetrySourceRegistration(
                    DisabledTelemetrySource,
                    TelemetrySnapshotError.SourceCapacityExceeded,
                )
            }
            val rawId = nextSourceId.getAndIncrement()
            if (rawId <= 0L || rawId > UInt.MAX_VALUE.toLong()) {
                sourceRegistrationRejected.increment()
                return TelemetrySourceRegistration(
                    DisabledTelemetrySource,
                    TelemetrySnapshotError.SourceCapacityExceeded,
                )
            }
            val id = TelemetrySourceId(rawId.toUInt())
            val source = RegisteredTelemetrySource(
                sourceId = id,
                scope = definition.scope,
                descriptors = descriptors.filterNotNull(),
                onOverflow = updateOverflow::increment,
                onClose = ::unregisterSource,
            )
            sources[id] = source
            sourceActive.set(sources.size.toLong())
            return TelemetrySourceRegistration(source)
        }
    }

    fun registerProvider(provider: TelemetrySnapshotProvider): TelemetrySnapshotError? {
        synchronized(registryLock) {
            if (!enabled) return TelemetrySnapshotError.Disabled
            if (closed.get()) return TelemetrySnapshotError.Closed
            if (providers.size >= MAX_TELEMETRY_SNAPSHOT_PROVIDERS) {
                return TelemetrySnapshotError.ProviderCapacityExceeded
            }
            providers += provider
            return null
        }
    }

    fun unregisterProvider(provider: TelemetrySnapshotProvider) {
        synchronized(registryLock) {
            providers.remove(provider)
        }
        provider.close()
    }

    /** Cold-path, non-destructive and deliberately weakly consistent across atomic instruments. */
    fun snapshot(): TelemetrySnapshot {
        val sequence = snapshotSequence.incrementAndGet().toULong()
        val startedAt = clock.nowNs()
        if (!enabled) {
            return TelemetrySnapshot(
                TelemetrySnapshotStatus.Disabled,
                sequence,
                startedAt,
                emptyList(),
                setOf(TelemetrySnapshotError.Disabled),
            )
        }
        if (closed.get()) {
            return TelemetrySnapshot(
                TelemetrySnapshotStatus.Closed,
                sequence,
                startedAt,
                emptyList(),
                setOf(TelemetrySnapshotError.Closed),
            )
        }
        snapshotCount.increment()
        val sourceCopy: List<RegisteredTelemetrySource>
        val providerCopy: List<TelemetrySnapshotProvider>
        synchronized(registryLock) {
            sourceCopy = sources.values.toList()
            providerCopy = providers.toList()
        }

        val records = ArrayList<TelemetrySnapshotRecord>()
        val errors = LinkedHashSet<TelemetrySnapshotError>()
        appendBounded(records, frameworkRecords(), errors)
        sourceCopy.forEach { source -> appendBounded(records, source.snapshotRecords(), errors) }
        providerCopy.forEach { provider ->
            runCatching { provider.collect() }
                .onSuccess { provided ->
                    errors += provided.errors
                    appendBounded(records, provided.records, errors)
                }
                .onFailure {
                    errors += TelemetrySnapshotError.ProviderFailure
                    snapshotProviderFailure.increment()
                }
        }
        val durationNs = clock.nowNs().minus(startedAt)
        snapshotDuration.record((durationNs / 1_000u).coerceAtMost(ULong.MAX_VALUE))
        val partial = errors.isNotEmpty()
        if (partial) snapshotPartial.increment()
        return TelemetrySnapshot(
            if (partial) TelemetrySnapshotStatus.Partial else TelemetrySnapshotStatus.Complete,
            sequence,
            startedAt,
            records.toList(),
            errors.toSet(),
        )
    }

    override fun close() {
        val sourceCopy: List<RegisteredTelemetrySource>
        val providerCopy: List<TelemetrySnapshotProvider>
        synchronized(registryLock) {
            if (!closed.compareAndSet(false, true)) return
            sourceCopy = sources.values.toList()
            providerCopy = providers.toList()
            sources.clear()
            providers.clear()
            sourceActive.set(0)
        }
        sourceCopy.forEach { it.markClosedByHub() }
        providerCopy.forEach { it.close() }
    }

    private fun unregisterSource(id: TelemetrySourceId) {
        synchronized(registryLock) {
            sources.remove(id)
            sourceActive.set(sources.size.toLong())
        }
    }

    private fun appendBounded(
        destination: MutableList<TelemetrySnapshotRecord>,
        incoming: List<TelemetrySnapshotRecord>,
        errors: MutableSet<TelemetrySnapshotError>,
    ) {
        val available = MAX_TELEMETRY_SNAPSHOT_RECORDS - destination.size
        if (incoming.size <= available) {
            destination += incoming
        } else {
            if (available > 0) destination += incoming.take(available)
            errors += TelemetrySnapshotError.SnapshotRecordLimitExceeded
        }
    }

    private fun frameworkRecords(): List<TelemetrySnapshotRecord> = listOf(
        counterRecord(TelemetryMetricIds.SourceRegistrationRejected, sourceRegistrationRejected),
        counterRecord(TelemetryMetricIds.SnapshotCount, snapshotCount),
        counterRecord(TelemetryMetricIds.SnapshotPartial, snapshotPartial),
        counterRecord(TelemetryMetricIds.SnapshotProviderFailure, snapshotProviderFailure),
        counterRecord(TelemetryMetricIds.UpdateOverflow, updateOverflow),
        gaugeRecord(TelemetryMetricIds.SourceActive, sourceActive),
        histogramRecord(TelemetryMetricIds.SnapshotDuration, snapshotDuration),
    )

    private fun counterRecord(id: TelemetryMetricId, counter: TelemetryCounterU64) = TelemetrySnapshotRecord(
        frameworkSourceId,
        TelemetryScope.Process,
        id,
        TelemetryMetricKind.CounterU64,
        TelemetryMetricValue.Counter(counter.snapshot()),
    )

    private fun gaugeRecord(id: TelemetryMetricId, gauge: TelemetryGaugeI64): TelemetrySnapshotRecord {
        val value = gauge.snapshot()
        return TelemetrySnapshotRecord(
            frameworkSourceId,
            TelemetryScope.Process,
            id,
            TelemetryMetricKind.GaugeI64,
            TelemetryMetricValue.Gauge(value.valid, value.value),
        )
    }

    private fun histogramRecord(id: TelemetryMetricId, histogram: TelemetryHistogramU64): TelemetrySnapshotRecord {
        val value = histogram.snapshot()
        return TelemetrySnapshotRecord(
            frameworkSourceId,
            TelemetryScope.Process,
            id,
            TelemetryMetricKind.HistogramU64,
            TelemetryMetricValue.Histogram(value.count, value.sum, value.min, value.max, value.bucketCounts),
        )
    }
}

private object DisabledTelemetrySource : TelemetrySource {
    override val sourceId: TelemetrySourceId? = null
    override val enabled: Boolean = false

    override fun counter(id: TelemetryMetricId): TelemetryCounterHandle = DisabledTelemetryCounter

    override fun gauge(id: TelemetryMetricId): TelemetryGaugeHandle = DisabledTelemetryGauge

    override fun histogram(id: TelemetryMetricId): TelemetryHistogramHandle = DisabledTelemetryHistogram

    override fun close() = Unit
}

private class RegisteredTelemetrySource(
    override val sourceId: TelemetrySourceId,
    private val scope: TelemetryScope,
    descriptors: List<TelemetryMetricDescriptor>,
    onOverflow: () -> Unit,
    private val onClose: (TelemetrySourceId) -> Unit,
) : TelemetrySource {
    private val closed = AtomicBoolean(false)
    private val counters = HashMap<TelemetryMetricId, TelemetryCounterU64>()
    private val gauges = HashMap<TelemetryMetricId, TelemetryGaugeI64>()
    private val histograms = HashMap<TelemetryMetricId, TelemetryHistogramU64>()

    init {
        descriptors.forEach { descriptor ->
            when (descriptor.kind) {
                TelemetryMetricKind.CounterU64 -> counters[descriptor.id] = TelemetryCounterU64(onOverflow)
                TelemetryMetricKind.GaugeI64 -> gauges[descriptor.id] = TelemetryGaugeI64()
                TelemetryMetricKind.HistogramU64 -> histograms[descriptor.id] =
                    TelemetryHistogramU64(descriptor.histogramBoundaries, onOverflow)
            }
        }
    }

    override val enabled: Boolean
        get() = !closed.get()

    override fun counter(id: TelemetryMetricId): TelemetryCounterHandle = counters[id] ?: DisabledTelemetryCounter

    override fun gauge(id: TelemetryMetricId): TelemetryGaugeHandle = gauges[id] ?: DisabledTelemetryGauge

    override fun histogram(id: TelemetryMetricId): TelemetryHistogramHandle =
        histograms[id] ?: DisabledTelemetryHistogram

    fun snapshotRecords(): List<TelemetrySnapshotRecord> {
        if (closed.get()) return emptyList()
        val records = ArrayList<TelemetrySnapshotRecord>(counters.size + gauges.size + histograms.size)
        counters.forEach { (id, counter) ->
            records += TelemetrySnapshotRecord(
                sourceId,
                scope,
                id,
                TelemetryMetricKind.CounterU64,
                TelemetryMetricValue.Counter(counter.snapshot()),
            )
        }
        gauges.forEach { (id, gauge) ->
            val value = gauge.snapshot()
            records += TelemetrySnapshotRecord(
                sourceId,
                scope,
                id,
                TelemetryMetricKind.GaugeI64,
                TelemetryMetricValue.Gauge(value.valid, value.value),
            )
        }
        histograms.forEach { (id, histogram) ->
            val value = histogram.snapshot()
            records += TelemetrySnapshotRecord(
                sourceId,
                scope,
                id,
                TelemetryMetricKind.HistogramU64,
                TelemetryMetricValue.Histogram(value.count, value.sum, value.min, value.max, value.bucketCounts),
            )
        }
        return records
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose(sourceId)
    }

    fun markClosedByHub() {
        closed.set(true)
    }
}
