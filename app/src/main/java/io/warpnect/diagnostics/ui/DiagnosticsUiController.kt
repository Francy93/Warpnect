@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.ui

import io.warpnect.diagnostics.DEFAULT_DIAGNOSTIC_SNAPSHOT_LIMIT
import io.warpnect.diagnostics.DiagnosticEventCursor
import io.warpnect.diagnostics.DiagnosticEventDescriptorCatalog
import io.warpnect.diagnostics.DiagnosticEventHub
import io.warpnect.diagnostics.DiagnosticEventRecord
import io.warpnect.diagnostics.DiagnosticEventSnapshot
import io.warpnect.diagnostics.DiagnosticPayloadFieldKey
import io.warpnect.diagnostics.DiagnosticReason
import io.warpnect.diagnostics.DiagnosticScalarKind
import io.warpnect.diagnostics.DiagnosticSessionState
import io.warpnect.diagnostics.DiagnosticSeverity
import io.warpnect.telemetry.TelemetryDescriptorCatalog
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySnapshot
import io.warpnect.telemetry.TelemetrySnapshotRecord
import io.warpnect.telemetry.TelemetrySnapshotStatus
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val UI_EVENT_HISTORY_LIMIT = 512

fun interface DiagnosticsUiClock {
    fun nowNs(): ULong
}

object SystemDiagnosticsUiClock : DiagnosticsUiClock {
    override fun nowNs(): ULong = System.nanoTime().coerceAtLeast(0L).toULong()
}

data class DiagnosticsRuntimeSummary(
    val role: String? = null,
    val lifecycleState: String? = null,
)

/** Test seam and the only controller boundary that reads the process-scoped diagnostic hubs. */
interface DiagnosticsSnapshotReader {
    val telemetryEnabled: Boolean
    val eventHistoryEnabled: Boolean

    fun telemetrySnapshot(): TelemetrySnapshot

    fun diagnosticEvents(cursor: DiagnosticEventCursor, limit: Int): DiagnosticEventSnapshot
}

class HubDiagnosticsSnapshotReader(
    private val telemetryHub: TelemetryHub,
    private val diagnosticEventHub: DiagnosticEventHub,
) : DiagnosticsSnapshotReader {
    override val telemetryEnabled: Boolean
        get() = telemetryHub.enabled
    override val eventHistoryEnabled: Boolean
        get() = diagnosticEventHub.enabled

    override fun telemetrySnapshot(): TelemetrySnapshot = telemetryHub.snapshot()

    override fun diagnosticEvents(cursor: DiagnosticEventCursor, limit: Int): DiagnosticEventSnapshot =
        diagnosticEventHub.snapshotSince(cursor, limit)
}

/**
 * Screen-owned, cold-path presentation controller. It never receives runtime producer updates;
 * it pulls one telemetry and one incremental event snapshot per bounded refresh.
 */
class DiagnosticsUiController(
    private val reader: DiagnosticsSnapshotReader,
    private val runtimeSummary: () -> DiagnosticsRuntimeSummary = { DiagnosticsRuntimeSummary() },
    private val clock: DiagnosticsUiClock = SystemDiagnosticsUiClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val controllerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateLock = Any()
    private val refreshInFlight = AtomicBoolean(false)
    private val applicationEvents = ArrayDeque<DiagnosticsEventUi>(UI_EVENT_HISTORY_LIMIT)
    private val nativeEvents = ArrayDeque<DiagnosticsEventUi>(UI_EVENT_HISTORY_LIMIT)
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private var scheduler: Job? = null
    private var samplingActive = false
    private var closed = false
    private var cursor = DiagnosticEventCursor()
    private var previousSnapshot: TelemetrySnapshot? = null
    private var currentSnapshot: TelemetrySnapshot? = null
    private var applicationGapSeen = false
    private var nativeGapSeen = false
    private var projectedSessions: List<DiagnosticsSessionUi> = emptyList()
    private var projectedMetrics: List<DiagnosticsMetricUi> = emptyList()
    private var lastRuntimeSummary = DiagnosticsRuntimeSummary()
    private var lastNativeStatus = io.warpnect.diagnostics.DiagnosticEventProviderStatus.Disabled
    private val mediaCategories = setOf(
        DiagnosticsMetricCategory.Video,
        DiagnosticsMetricCategory.Audio,
        DiagnosticsMetricCategory.Input,
    )
    private val networkCategories = setOf(
        DiagnosticsMetricCategory.Session,
        DiagnosticsMetricCategory.Network,
        DiagnosticsMetricCategory.Security,
    )

    constructor(
        telemetryHub: TelemetryHub,
        diagnosticEventHub: DiagnosticEventHub,
        runtimeSummary: () -> DiagnosticsRuntimeSummary = { DiagnosticsRuntimeSummary() },
        clock: DiagnosticsUiClock = SystemDiagnosticsUiClock,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(HubDiagnosticsSnapshotReader(telemetryHub, diagnosticEventHub), runtimeSummary, clock, dispatcher)

    fun startSampling() {
        val shouldRefresh = synchronized(stateLock) {
            if (closed || samplingActive) return
            samplingActive = true
            publishControlsLocked()
            if (scheduler == null) scheduler = launchSchedulerLocked()
            !_state.value.paused
        }
        if (shouldRefresh) requestRefresh()
    }

    fun stopSampling() = synchronized(stateLock) {
        if (!samplingActive) return
        samplingActive = false
        scheduler?.cancel()
        scheduler = null
        publishControlsLocked()
    }

    fun setPaused(paused: Boolean) {
        val shouldRefresh = synchronized(stateLock) {
            if (closed || _state.value.paused == paused) return
            _state.value = _state.value.copy(paused = paused)
            samplingActive && !paused
        }
        if (shouldRefresh) requestRefresh()
    }

    fun manualRefresh() {
        if (!closed) requestRefresh()
    }

    fun setRefreshInterval(interval: DiagnosticsRefreshInterval) = synchronized(stateLock) {
        if (closed || _state.value.refreshInterval == interval) return
        _state.value = _state.value.copy(refreshInterval = interval)
        if (samplingActive) {
            scheduler?.cancel()
            scheduler = launchSchedulerLocked()
        }
    }

    fun selectSection(section: DiagnosticsSection) = synchronized(stateLock) {
        if (!closed) _state.value = _state.value.copy(section = section)
    }

    fun selectSession(session: DiagnosticsSessionKey?) = synchronized(stateLock) {
        if (!closed) publishSelectionLocked(session)
    }

    fun setEventFilter(filter: DiagnosticsEventFilterUi) = synchronized(stateLock) {
        if (!closed) {
            _state.value = _state.value.copy(
                events = projectEventsUi(filter, _state.value.selectedSession, lastNativeStatus),
            )
        }
    }

    /** Returns false when an existing refresh owns the single bounded in-flight slot. */
    fun requestRefresh(): Boolean {
        if (closed || !refreshInFlight.compareAndSet(false, true)) return false
        controllerScope.launch {
            try {
                val telemetry = reader.telemetrySnapshot()
                val eventSnapshot = reader.diagnosticEvents(cursor, DEFAULT_DIAGNOSTIC_SNAPSHOT_LIMIT)
                val summary = runtimeSummary()
                publishRefresh(telemetry, eventSnapshot, summary)
            } catch (_: Throwable) {
                synchronized(stateLock) {
                    val old = _state.value
                    _state.value = old.copy(
                        phase = if (old.snapshotStatus == null) DiagnosticsUiPhase.Failed else old.phase,
                        refreshFailed = true,
                    )
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
        return true
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            samplingActive = false
            scheduler?.cancel()
            scheduler = null
        }
        controllerScope.cancel()
    }

    private fun launchSchedulerLocked(): Job = controllerScope.launch {
        while (isSamplingActive()) {
            delay(currentIntervalMillis())
            if (isSamplingActive() && !isPaused()) requestRefresh()
        }
    }

    private fun isSamplingActive(): Boolean = synchronized(stateLock) { samplingActive && !closed }

    private fun isPaused(): Boolean = synchronized(stateLock) { _state.value.paused }

    private fun currentIntervalMillis(): Long = synchronized(stateLock) { _state.value.refreshInterval.millis }

    private fun publishControlsLocked() {
        _state.value = _state.value.copy(samplingActive = samplingActive)
    }

    private fun publishRefresh(
        telemetry: TelemetrySnapshot,
        events: DiagnosticEventSnapshot,
        summary: DiagnosticsRuntimeSummary,
    ) = synchronized(stateLock) {
        cursor = DiagnosticEventCursor(events.kotlin.nextCursor, events.native.batch.nextCursor)
        applicationGapSeen = applicationGapSeen || events.kotlin.gap
        nativeGapSeen = nativeGapSeen || events.native.batch.gap

        val sessions = projectSessions(telemetry)
        val old = _state.value
        val selected = old.selectedSession?.takeIf { wanted ->
            sessions.any {
                it.key == wanted
            }
        } ?: sessions.firstOrNull()?.key
        val labels = sessions.associate { it.key to it.label }
        appendEvents(
            applicationEvents,
            events.kotlin.events.map {
                projectEvent(it, DiagnosticsEventProvider.Application, labels)
            },
        )
        appendEvents(
            nativeEvents,
            events.native.batch.events.map { projectEvent(it, DiagnosticsEventProvider.Native, labels) },
        )

        val rateBaseSnapshot = currentSnapshot
        val elapsedNs = rateBaseSnapshot?.let { previous ->
            telemetry.capturedAtMonotonicNs.takeIf { it >= previous.capturedAtMonotonicNs }
                ?.minus(previous.capturedAtMonotonicNs)
        }
        val metrics = telemetry.records.mapNotNull { record ->
            projectMetric(record, rateBaseSnapshot, elapsedNs, selected, labels)
        }
            .sortedWith(compareBy<DiagnosticsMetricUi> { it.scope.kind }.thenBy { it.sourceId }.thenBy { it.metricId })
        val filtered = metrics.filter { metric -> belongsToSelection(metric.scope.session, selected) }
        val eventsUi = projectEventsUi(old.events.filter, selected, events.native.status)
        val phase = when (telemetry.status) {
            TelemetrySnapshotStatus.Disabled,
            TelemetrySnapshotStatus.Closed,
            -> DiagnosticsUiPhase.Disabled
            else -> DiagnosticsUiPhase.Ready
        }
        val clockQualified = metrics.firstOrNull {
            it.metricId == TelemetryMetricIds.ClockSyncQualified.value
        }?.value ?: "Unavailable"
        val selectedSession = sessions.firstOrNull { it.key == selected }
        val age = clock.nowNs().takeIf { it >= telemetry.capturedAtMonotonicNs }?.minus(telemetry.capturedAtMonotonicNs)
        val overview = DiagnosticsOverviewUi(
            sourceCount = telemetry.records.map { it.sourceId }.toSet().size,
            sessionCount = sessions.size,
            selectedSession = selectedSession,
            role = summary.role,
            lifecycleState = summary.lifecycleState,
            clockSyncQualified = clockQualified,
            warningEventCount = applicationEvents.count { it.severity >= DiagnosticSeverity.Warning } +
                nativeEvents.count { it.severity >= DiagnosticSeverity.Warning },
            historyGap = applicationGapSeen || nativeGapSeen,
        )
        val latency = filtered.filter { it.category == DiagnosticsMetricCategory.Clock }.let { latencyMetrics ->
            latencyUi(latencyMetrics)
        }
        _state.value = DiagnosticsUiState(
            phase = phase,
            section = old.section,
            samplingActive = samplingActive,
            paused = old.paused,
            refreshInterval = old.refreshInterval,
            snapshotStatus = telemetry.status,
            snapshotAgeNs = age,
            refreshFailed = false,
            sessions = sessions,
            selectedSession = selected,
            overview = overview,
            media = filtered.filter { it.category in mediaCategories },
            network = filtered.filter { it.category in networkCategories },
            latency = latency,
            events = eventsUi,
            raw = metrics.filter { metric -> belongsToSelection(metric.scope.session, selected) },
        )
        previousSnapshot = currentSnapshot
        currentSnapshot = telemetry
        projectedSessions = sessions
        projectedMetrics = metrics
        lastRuntimeSummary = summary
        lastNativeStatus = events.native.status
    }

    private fun publishSelectionLocked(selected: DiagnosticsSessionKey?) {
        val old = _state.value
        val actual = selected?.takeIf { wanted -> projectedSessions.any { it.key == wanted } }
            ?: projectedSessions.firstOrNull()?.key
        val filtered = projectedMetrics.filter { belongsToSelection(it.scope.session, actual) }
        val selectedSession = projectedSessions.firstOrNull { it.key == actual }
        _state.value = old.copy(
            selectedSession = actual,
            overview = old.overview.copy(
                selectedSession = selectedSession,
                role = lastRuntimeSummary.role,
                lifecycleState = lastRuntimeSummary.lifecycleState,
            ),
            media = filtered.filter { it.category in mediaCategories },
            network = filtered.filter { it.category in networkCategories },
            latency = latencyUi(filtered.filter { it.category == DiagnosticsMetricCategory.Clock }),
            events = projectEventsUi(old.events.filter, actual, lastNativeStatus),
            raw = filtered,
        )
    }

    private fun latencyUi(metrics: List<DiagnosticsMetricUi>): DiagnosticsLatencyUi = DiagnosticsLatencyUi(
        metrics = metrics,
        unavailable = buildList {
            if (metrics.none { it.metricId == TelemetryMetricIds.TransportOneWay.value }) {
                add(
                    "Transport one-way latency: Unavailable - " +
                        "frozen payload timestamps do not provide compatible cross-device provenance.",
                )
            }
            if (metrics.none { it.metricId == TelemetryMetricIds.VideoSourceToRender.value }) {
                add(
                    "Video source-to-render latency: Unavailable - " +
                        "no exact source identity survives the frozen pipeline.",
                )
            }
            if (metrics.none { it.metricId == TelemetryMetricIds.InputSourceToInjection.value }) {
                add("Input source-to-injection latency: Unavailable - no supported cross-device correlation is bound.")
            }
        },
    )

    private fun projectSessions(snapshot: TelemetrySnapshot): List<DiagnosticsSessionUi> {
        val paths = snapshot.records.mapNotNull { record ->
            val scope = record.scope as? TelemetryScope.Path ?: return@mapNotNull null
            val active = (record.value as? TelemetryMetricValue.Gauge)?.takeIf { it.valid }?.value == 1L
            if (record.metricId == TelemetryMetricIds.PathActive && active) {
                sessionKey(scope) to scope.pathKind.name
            } else {
                null
            }
        }.toMap()
        return snapshot.records.mapNotNull { sessionKey(it.scope) }.distinct()
            .sortedWith(
                compareBy<DiagnosticsSessionKey> { it.generation }
                    .thenBy { it.sessionIdHigh }
                    .thenBy { it.sessionIdLow },
            )
            .mapIndexed { index, key -> DiagnosticsSessionUi(key, "Session ${index + 1}", key.generation, paths[key]) }
    }

    private fun projectMetric(
        record: TelemetrySnapshotRecord,
        previous: TelemetrySnapshot?,
        elapsedNs: ULong?,
        selected: DiagnosticsSessionKey?,
        labels: Map<DiagnosticsSessionKey, String>,
    ): DiagnosticsMetricUi? {
        val descriptor = TelemetryDescriptorCatalog.descriptors.firstOrNull { it.id == record.metricId } ?: return null
        val scope = projectScope(record.scope, labels)
        if (!belongsToSelection(scope.session, selected)) return null
        val previousRecord = previous?.records?.firstOrNull {
            it.sourceId == record.sourceId && it.metricId == record.metricId
        }
        val rate = elapsedNs?.let { DiagnosticsRateCalculator.counterRate(previousRecord, record, it) }
        val histogram = (record.value as? TelemetryMetricValue.Histogram)?.let {
            DiagnosticsHistogramFormatter.summary(it, descriptor.histogramBoundaries, descriptor.unit)
        }
        val value = when (val metricValue = record.value) {
            is TelemetryMetricValue.Counter -> DiagnosticsValueFormatter.value(metricValue.value, descriptor.unit)
            is TelemetryMetricValue.Gauge -> if (metricValue.valid) {
                if (descriptor.unit == io.warpnect.telemetry.TelemetryUnit.Boolean) {
                    if (metricValue.value == 0L) "No" else "Yes"
                } else {
                    DiagnosticsValueFormatter.signed(metricValue.value, descriptor.unit)
                }
            } else {
                "Unavailable"
            }
            is TelemetryMetricValue.Histogram -> {
                if (metricValue.count == 0uL) "No samples" else "${metricValue.count} samples"
            }
        }
        return DiagnosticsMetricUi(
            sourceId = record.sourceId.value,
            metricId = record.metricId.value,
            canonicalName = descriptor.canonicalName,
            displayName = humanize(descriptor.canonicalName.removePrefix("warpnect.")),
            category = metricCategory(record.metricId.value),
            kind = descriptor.kind,
            unit = descriptor.unit,
            value = value,
            rate = rate,
            histogram = histogram,
            scope = scope,
        )
    }

    private fun projectEventsUi(
        filter: DiagnosticsEventFilterUi,
        selected: DiagnosticsSessionKey?,
        nativeStatus: io.warpnect.diagnostics.DiagnosticEventProviderStatus,
    ): DiagnosticsEventsUi = DiagnosticsEventsUi(
        application = applicationEvents.filter { it.matches(filter, selected) }.sortedBy { it.sequence },
        native = nativeEvents.filter { it.matches(filter, selected) }.sortedBy { it.sequence },
        applicationStatus = if (reader.eventHistoryEnabled) "Available" else "Unavailable",
        nativeStatus = nativeStatus,
        applicationGap = applicationGapSeen,
        nativeGap = nativeGapSeen,
        filter = filter,
    )

    private fun DiagnosticsEventUi.matches(
        filter: DiagnosticsEventFilterUi,
        selected: DiagnosticsSessionKey?,
    ): Boolean = severity >= filter.minimumSeverity &&
        (filter.provider == null || provider == filter.provider) &&
        (filter.category == null || category == filter.category) &&
        (!filter.selectedSessionOnly || selected == null || scope.session == selected)

    private fun appendEvents(destination: ArrayDeque<DiagnosticsEventUi>, incoming: List<DiagnosticsEventUi>) {
        incoming.forEach { event ->
            if (destination.size == UI_EVENT_HISTORY_LIMIT) destination.removeFirst()
            destination.addLast(event)
        }
    }

    private fun projectEvent(
        event: DiagnosticEventRecord,
        provider: DiagnosticsEventProvider,
        labels: Map<DiagnosticsSessionKey, String>,
    ): DiagnosticsEventUi {
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(event.typeId)
        val fields = descriptor?.payload?.mapIndexed { index, field ->
            DiagnosticsEventFieldUi(
                humanize(field.key.name),
                formatEventField(field.kind, field.key, event.payload[index]),
            )
        } ?: emptyList()
        return DiagnosticsEventUi(
            provider = provider,
            sequence = event.sequence,
            severity = event.severity,
            title = descriptor?.canonicalName?.removePrefix("warpnect.event.")?.let(::humanize) ?: "Unknown event",
            category = eventCategory(event.typeId.value),
            scope = projectDiagnosticScope(event, labels),
            clockDomain = event.clockDomain.name,
            fields = fields,
        )
    }

    private fun formatEventField(kind: DiagnosticScalarKind, key: DiagnosticPayloadFieldKey, value: ULong): String =
        when (kind) {
            DiagnosticScalarKind.Boolean -> if (value == 0uL) "No" else "Yes"
            DiagnosticScalarKind.Signed -> value.toLong().toString()
            DiagnosticScalarKind.Enum -> when (key) {
                DiagnosticPayloadFieldKey.Reason -> DiagnosticReason.entries.firstOrNull {
                    it.code == value
                }?.let { humanize(it.name) } ?: "Unknown"
                DiagnosticPayloadFieldKey.FromState,
                DiagnosticPayloadFieldKey.ToState,
                -> DiagnosticSessionState.entries.firstOrNull {
                    it.code == value
                }?.let { humanize(it.name) } ?: "Unknown"
                else -> value.toString()
            }
            DiagnosticScalarKind.Unsigned -> value.toString()
        }

    private fun projectScope(scope: TelemetryScope, labels: Map<DiagnosticsSessionKey, String>): DiagnosticsScopeUi =
        when (scope) {
            TelemetryScope.Process -> DiagnosticsScopeUi("Process", "Process")
            is TelemetryScope.Session -> {
                val key = sessionKey(scope)
                DiagnosticsScopeUi("Session", "${labels[key] ?: "Session"} - Generation ${scope.generation.value}", key)
            }
            is TelemetryScope.Path -> {
                val key = sessionKey(scope)
                DiagnosticsScopeUi(
                    "Path",
                    "${labels[key] ?: "Session"} - ${scope.pathKind} path ${scope.pathId.value}",
                    key,
                    scope.pathId.value,
                )
            }
            is TelemetryScope.Channel -> {
                val key = sessionKey(scope)
                DiagnosticsScopeUi(
                    "Channel",
                    "${labels[key] ?: "Session"} - ${scope.channelKind} channel ${scope.channelId.value}",
                    key,
                    channelId = scope.channelId.value,
                )
            }
            is TelemetryScope.Component -> DiagnosticsScopeUi("Component", humanize(scope.component.name))
        }

    private fun projectDiagnosticScope(
        event: DiagnosticEventRecord,
        labels: Map<DiagnosticsSessionKey, String>,
    ): DiagnosticsScopeUi {
        val scope = event.scope
        val session = if (scope.sessionGeneration != 0u && (scope.sessionIdHigh != 0uL || scope.sessionIdLow != 0uL)) {
            DiagnosticsSessionKey(scope.sessionIdHigh, scope.sessionIdLow, scope.sessionGeneration)
        } else {
            null
        }
        val label = session?.let { labels[it] ?: "Session" }
        return when (scope.kind.name) {
            "Process" -> DiagnosticsScopeUi("Process", "Process")
            "Session" -> DiagnosticsScopeUi("Session", "$label - Generation ${scope.sessionGeneration}", session)
            "Path" -> DiagnosticsScopeUi("Path", "$label - Path ${scope.pathId}", session, scope.pathId)
            "Channel" -> DiagnosticsScopeUi(
                "Channel",
                "$label - Channel ${scope.channelId}",
                session,
                channelId = scope.channelId,
            )
            else -> DiagnosticsScopeUi("Component", "Component ${scope.componentKind}")
        }
    }

    private fun sessionKey(scope: TelemetryScope): DiagnosticsSessionKey? = when (scope) {
        is TelemetryScope.Session -> sessionKey(scope)
        is TelemetryScope.Path -> sessionKey(scope)
        is TelemetryScope.Channel -> sessionKey(scope)
        else -> null
    }

    private fun sessionKey(scope: TelemetryScope.Session): DiagnosticsSessionKey =
        DiagnosticsSessionKey(scope.sessionId.high, scope.sessionId.low, scope.generation.value)

    private fun sessionKey(scope: TelemetryScope.Path): DiagnosticsSessionKey =
        DiagnosticsSessionKey(scope.sessionId.high, scope.sessionId.low, scope.generation.value)

    private fun sessionKey(scope: TelemetryScope.Channel): DiagnosticsSessionKey =
        DiagnosticsSessionKey(scope.sessionId.high, scope.sessionId.low, scope.generation.value)

    private fun belongsToSelection(scopeSession: DiagnosticsSessionKey?, selected: DiagnosticsSessionKey?): Boolean =
        scopeSession == null || selected == null || scopeSession == selected

    private fun metricCategory(id: Int): DiagnosticsMetricCategory = when (id ushr 8) {
        0x00 -> DiagnosticsMetricCategory.Framework
        0x01 -> DiagnosticsMetricCategory.Session
        0x02 -> DiagnosticsMetricCategory.Network
        0x03 -> DiagnosticsMetricCategory.Video
        0x04 -> DiagnosticsMetricCategory.Audio
        0x05 -> DiagnosticsMetricCategory.Input
        0x06 -> DiagnosticsMetricCategory.Clock
        else -> DiagnosticsMetricCategory.Security
    }

    private fun eventCategory(id: Int): DiagnosticsEventCategory = when (id ushr 8) {
        0x00 -> DiagnosticsEventCategory.Diagnostic
        0x01 -> DiagnosticsEventCategory.Session
        0x02 -> DiagnosticsEventCategory.Network
        0x03 -> DiagnosticsEventCategory.Video
        0x04 -> DiagnosticsEventCategory.Audio
        0x05 -> DiagnosticsEventCategory.Input
        0x06 -> DiagnosticsEventCategory.Clock
        0x07 -> DiagnosticsEventCategory.Security
        else -> DiagnosticsEventCategory.Platform
    }

    private fun humanize(value: String): String = value
        .replace('.', ' ')
        .replace('_', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
}
