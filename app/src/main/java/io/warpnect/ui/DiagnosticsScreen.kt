package io.warpnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.warpnect.diagnostics.DiagnosticSeverity
import io.warpnect.diagnostics.ui.DiagnosticsEventCategory
import io.warpnect.diagnostics.ui.DiagnosticsEventFilterUi
import io.warpnect.diagnostics.ui.DiagnosticsEventProvider
import io.warpnect.diagnostics.ui.DiagnosticsEventUi
import io.warpnect.diagnostics.ui.DiagnosticsMetricUi
import io.warpnect.diagnostics.ui.DiagnosticsRefreshInterval
import io.warpnect.diagnostics.ui.DiagnosticsSection
import io.warpnect.diagnostics.ui.DiagnosticsUiController
import io.warpnect.diagnostics.ui.DiagnosticsUiPhase
import io.warpnect.diagnostics.ui.DiagnosticsUiState
import io.warpnect.diagnostics.ui.DiagnosticsValueFormatter

/** Read-only RFC-006F diagnostics surface. Hubs are never accessed from composable rendering. */
@Composable
fun DiagnosticsScreen(controller: DiagnosticsUiController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.startSampling()
                Lifecycle.Event.ON_STOP -> controller.stopSampling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.startSampling()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DiagnosticsToolbar(state, controller, onClose)
        DiagnosticsSections(state, controller)
        when (state.section) {
            DiagnosticsSection.Overview -> OverviewContent(state, controller)
            DiagnosticsSection.Media -> MetricsContent("Media", state.media)
            DiagnosticsSection.Network -> MetricsContent("Network and recovery", state.network)
            DiagnosticsSection.Latency -> LatencyContent(state)
            DiagnosticsSection.Events -> EventsContent(state, controller)
            DiagnosticsSection.Raw -> RawContent(state)
        }
    }
}

@Composable
private fun DiagnosticsToolbar(state: DiagnosticsUiState, controller: DiagnosticsUiController, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onClose) { Text("Close") }
        Button(onClick = { controller.setPaused(!state.paused) }) { Text(if (state.paused) "Resume" else "Pause") }
        OutlinedButton(onClick = controller::manualRefresh) { Text("Refresh") }
        Column(modifier = Modifier.weight(1f)) {
            Text("Runtime Diagnostics", fontWeight = FontWeight.SemiBold)
            Text(statusText(state))
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(DiagnosticsRefreshInterval.entries, key = { it.name }) { interval ->
            FilterChip(
                selected = state.refreshInterval == interval,
                onClick = { controller.setRefreshInterval(interval) },
                label = { Text(interval.label) },
            )
        }
    }
}

@Composable
private fun DiagnosticsSections(state: DiagnosticsUiState, controller: DiagnosticsUiController) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(DiagnosticsSection.entries, key = { it.name }) { section ->
            FilterChip(
                selected = state.section == section,
                onClick = { controller.selectSection(section) },
                label = { Text(section.title) },
            )
        }
    }
}

@Composable
private fun OverviewContent(state: DiagnosticsUiState, controller: DiagnosticsUiController) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Overview") }
        when (state.phase) {
            DiagnosticsUiPhase.Loading -> item { Text("Loading diagnostics...") }
            DiagnosticsUiPhase.Disabled -> item { Text("Runtime telemetry is unavailable.") }
            DiagnosticsUiPhase.Failed -> item { Text("Refresh failed. No runtime behavior was affected.") }
            DiagnosticsUiPhase.Ready -> Unit
        }
        if (state.snapshotStatus?.name == "Partial") {
            item { WarningText("Partial snapshot: available diagnostics remain visible.") }
        }
        if (state.refreshFailed) item { WarningText("Refresh failed; showing the last valid state.") }
        item {
            OverviewRow(
                "Sampling",
                if (state.paused) "Paused" else if (state.samplingActive) "Active" else "Stopped",
            )
        }
        item { OverviewRow("Snapshot", state.snapshotStatus?.name ?: "Unavailable") }
        item {
            OverviewRow(
                "Snapshot age",
                state.snapshotAgeNs?.let {
                    DiagnosticsValueFormatter.value(it, io.warpnect.telemetry.TelemetryUnit.Nanoseconds)
                } ?: "Unavailable",
            )
        }
        item { OverviewRow("Active sources", state.overview.sourceCount.toString()) }
        item { OverviewRow("Active sessions", state.overview.sessionCount.toString()) }
        state.overview.selectedSession?.let { selected ->
            item { OverviewRow("Selected", "${selected.label} - Generation ${selected.generation}") }
            item { OverviewRow("Active path", selected.activePath ?: "Unavailable") }
        }
        state.overview.role?.let { item { OverviewRow("Role", it) } }
        state.overview.lifecycleState?.let { item { OverviewRow("Lifecycle", it) } }
        item { OverviewRow("ClockSync", state.overview.clockSyncQualified) }
        item { OverviewRow("Warning/error events", state.overview.warningEventCount.toString()) }
        if (state.overview.historyGap) item { WarningText("Earlier diagnostic events were overwritten.") }
        if (state.sessions.isEmpty()) {
            item { Text("No active Sessions. Process diagnostics and retained event history remain available.") }
        } else {
            item { SectionTitle("Session") }
            items(
                state.sessions,
                key = { "${it.key.sessionIdHigh}:${it.key.sessionIdLow}:${it.key.generation}" },
            ) { session ->
                FilterChip(
                    selected = state.selectedSession == session.key,
                    onClick = { controller.selectSession(session.key) },
                    label = { Text("${session.label} - Generation ${session.generation}") },
                )
            }
        }
    }
}

@Composable
private fun MetricsContent(title: String, metrics: List<DiagnosticsMetricUi>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle(title) }
        if (metrics.isEmpty()) item { Text("No matching active diagnostics.") }
        items(metrics, key = { "${it.sourceId}:${it.metricId}" }) { metric -> MetricRow(metric) }
    }
}

@Composable
private fun LatencyContent(state: DiagnosticsUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Latency and ClockSync") }
        if (state.latency.metrics.isEmpty()) {
            item {
                Text(
                    "No supported latency diagnostics are bound for this Session.",
                )
            }
        }
        items(state.latency.metrics, key = { "${it.sourceId}:${it.metricId}" }) { metric -> MetricRow(metric) }
        if (state.latency.unavailable.isNotEmpty()) item { SectionTitle("Unavailable") }
        items(state.latency.unavailable) { message -> Text(message) }
    }
}

@Composable
private fun EventsContent(state: DiagnosticsUiState, controller: DiagnosticsUiController) {
    val filter = state.events.filter
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Event History") }
        item {
            Text(
                "Application and native events remain separate provider timelines. " +
                    "They are not globally timestamp-sorted.",
            )
        }
        if (state.events.applicationGap || state.events.nativeGap) {
            item { WarningText("Earlier diagnostic events were overwritten.") }
        }
        item { EventFilterControls(filter, controller) }
        item { SectionTitle("Application events") }
        item { Text(state.events.applicationStatus) }
        if (state.events.application.isEmpty()) item { Text("No matching application events.") }
        items(state.events.application, key = { "application:${it.sequence}" }) { event -> EventRow(event) }
        item { SectionTitle("Native events") }
        item { Text(state.events.nativeStatus.name) }
        if (state.events.native.isEmpty()) item { Text("No matching native events.") }
        items(state.events.native, key = { "native:${it.sequence}" }) { event -> EventRow(event) }
    }
}

@Composable
private fun EventFilterControls(filter: DiagnosticsEventFilterUi, controller: DiagnosticsUiController) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = filter.provider == null,
                    onClick = { controller.setEventFilter(filter.copy(provider = null)) },
                    label = { Text("All providers") },
                )
            }
            items(DiagnosticsEventProvider.entries, key = { it.name }) { provider ->
                FilterChip(
                    selected = filter.provider == provider,
                    onClick = { controller.setEventFilter(filter.copy(provider = provider)) },
                    label = { Text(provider.title) },
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DiagnosticSeverity.entries, key = { it.name }) { severity ->
                FilterChip(
                    selected = filter.minimumSeverity == severity,
                    onClick = { controller.setEventFilter(filter.copy(minimumSeverity = severity)) },
                    label = { Text("${severity.name}+") },
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = filter.category == null,
                    onClick = { controller.setEventFilter(filter.copy(category = null)) },
                    label = { Text("All categories") },
                )
            }
            items(DiagnosticsEventCategory.entries, key = { it.name }) { category ->
                FilterChip(
                    selected = filter.category == category,
                    onClick = { controller.setEventFilter(filter.copy(category = category)) },
                    label = { Text(category.title) },
                )
            }
        }
        FilterChip(
            selected = filter.selectedSessionOnly,
            onClick = { controller.setEventFilter(filter.copy(selectedSessionOnly = !filter.selectedSessionOnly)) },
            label = { Text("Selected Session only") },
        )
    }
}

@Composable
private fun RawContent(state: DiagnosticsUiState) {
    val grouped = state.raw.groupBy { "${it.scope.kind} - source ${it.sourceId}" }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Raw Metrics") }
        if (grouped.isEmpty()) item { Text("No raw records are available.") }
        grouped.forEach { (group, metrics) ->
            item { Text(group, fontWeight = FontWeight.SemiBold) }
            items(metrics, key = { "${it.sourceId}:${it.metricId}" }) { metric ->
                MetricRow(metric, raw = true)
            }
        }
    }
}

@Composable
private fun MetricRow(metric: DiagnosticsMetricUi, raw: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(metric.displayName, fontWeight = FontWeight.Medium)
        Text(metric.value)
        metric.rate?.let { Text(it.label) }
        metric.histogram?.let { histogram ->
            if (histogram.sampleCount == 0uL) {
                Text("No samples")
            } else {
                Text(
                    listOf(
                        "${histogram.sampleCount} samples",
                        "avg ${histogram.average}",
                        "min ${histogram.minimum}",
                        "max ${histogram.maximum}",
                    ).joinToString("; "),
                )
                Text("Approx upper bounds: p50 ${histogram.p50}; p95 ${histogram.p95}; p99 ${histogram.p99}")
            }
        }
        Text(metric.scope.summary)
        if (raw) {
            Text(
                "0x${metric.metricId.toString(
                    16,
                ).padStart(4, '0')} - ${metric.canonicalName} - ${metric.kind} - ${metric.unit}",
                fontFamily = FontFamily.Monospace,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun EventRow(event: DiagnosticsEventUi) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${event.severity.name}: ${event.title}", fontWeight = FontWeight.Medium)
        Text("${event.scope.summary} - sequence ${event.sequence} - ${event.clockDomain}")
        event.fields.forEach { field -> Text("${field.label}: ${field.value}") }
        HorizontalDivider()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun OverviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WarningText(text: String) {
    Text("Warning: $text", fontWeight = FontWeight.Medium)
}

private fun statusText(state: DiagnosticsUiState): String = when {
    state.paused -> "Paused"
    state.phase == DiagnosticsUiPhase.Loading -> "Loading"
    state.phase == DiagnosticsUiPhase.Disabled -> "Unavailable"
    state.snapshotStatus?.name == "Partial" -> "Partial"
    else -> "Ready"
}
