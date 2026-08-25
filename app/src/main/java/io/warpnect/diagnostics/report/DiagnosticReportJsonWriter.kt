@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.report

import java.io.Writer
import java.util.Locale

/** Deterministic streaming JSON writer. Raw runtime u64/i64 values arrive as decimal strings. */
class DiagnosticReportJsonWriter {
    fun write(report: DiagnosticReport, writer: Writer) {
        Json(writer).obj {
            name("schema").string(DIAGNOSTIC_REPORT_SCHEMA)
            name("schema_version").number(DIAGNOSTIC_REPORT_SCHEMA_VERSION)
            name("report_type").string(report.type.wireName)
            name("generated_at_utc").string(report.generatedAtUtc)
            name("runtime").runtime(report.runtime)
            name("platform").platform(report.platform)
            name("scope").scope(report.scope)
            name("capture").capture(report.capture)
            name("telemetry").array { report.telemetry.forEach { metric(it) } }
            name("events").obj {
                name("application").events(report.applicationEvents)
                name("native").events(report.nativeEvents)
            }
            name("benchmark")
            if (report.benchmark == null) nullValue() else benchmark(report.benchmark)
            name("limitations").array { report.limitations.forEach(::string) }
        }
    }

    private fun Json.runtime(value: ReportRuntimeMetadata) = obj {
        name("app_version_name").string(value.appVersionName)
        name("app_version_code").string(value.appVersionCode)
        name("architecture_version").string(value.architectureVersion)
        name("scl_protocol_version").number(value.sclProtocolVersion)
        name("native_bridge_abi_version").number(value.nativeBridgeAbiVersion)
        name("runtime_telemetry_model_version").number(value.runtimeTelemetryModelVersion)
        name("wntm_version").number(value.wntmVersion)
        name("diagnostic_event_model_version").number(value.diagnosticEventModelVersion)
        name("wnde_version").number(value.wndeVersion)
        name("report_schema_version").number(DIAGNOSTIC_REPORT_SCHEMA_VERSION)
    }

    private fun Json.platform(value: ReportPlatformMetadata) = obj {
        name("android_sdk").nullableNumber(value.androidSdk)
        name("supported_abi").nullableString(value.supportedAbi)
        name("manufacturer").nullableString(value.manufacturer)
        name("model").nullableString(value.model)
        name("real_device_validation").string(value.realDeviceValidation)
    }

    private fun Json.scope(value: ReportScope) = obj {
        name("kind").string(value.kind)
        value.sessionAlias?.let { name("session").number(it) }
        value.generation?.let { name("generation").number(it) }
        value.pathId?.let { name("path_id").number(it) }
        value.pathKind?.let { name("path_kind").string(it) }
        value.channelId?.let { name("channel_id").number(it) }
        value.channelKind?.let { name("channel_kind").string(it) }
        value.channelDirection?.let { name("channel_direction").string(it) }
        value.component?.let { name("component").string(it) }
        value.role?.let { name("role").string(it) }
    }

    private fun Json.capture(value: ReportCapture) = obj {
        name("telemetry_status").string(value.telemetryStatus)
        name("telemetry_sequence").string(value.telemetrySequence)
        name("captured_at_boottime_ns").string(value.capturedAtBootTimeNs)
        name("telemetry_snapshot_consistency").string(value.consistency)
        name("partial").bool(value.partial)
        name("telemetry_errors").array { value.telemetryErrors.forEach { string(it) } }
    }

    private fun Json.metric(metric: ReportMetric) = obj {
        name("source_id").number(metric.sourceId)
        name("scope").scope(metric.scope)
        name("metric_id").number(metric.metricId)
        name("name").string(metric.name)
        name("kind").string(metric.kind.name.lowercase(Locale.ROOT))
        name("unit").string(metric.unit.name.lowercase(Locale.ROOT))
        name("value").metricValue(metric.value)
    }

    private fun Json.metricValue(value: ReportMetricValue) = obj {
        when (value) {
            is ReportMetricValue.Counter -> {
                name("value").string(value.value)
            }
            is ReportMetricValue.Gauge -> {
                name("valid").bool(value.valid)
                value.value?.let { name("value").string(it) }
            }
            is ReportMetricValue.Histogram -> {
                name("count").string(value.count)
                name("sum").string(value.sum)
                name("min").nullableString(value.min)
                name("max").nullableString(value.max)
                name("finite_boundaries").array { value.finiteBoundaries.forEach(::string) }
                name("bucket_counts").array { value.bucketCounts.forEach(::string) }
            }
        }
    }

    private fun Json.events(value: ReportEventProvider) = obj {
        name("status").string(value.status)
        name("gap_detected").bool(value.gapDetected)
        name("oldest_available_sequence").string(value.oldestAvailableSequence)
        name("requested_after_sequence").string(value.requestedAfterSequence)
        name("overwritten_count").string(value.overwrittenCount)
        name("truncated").bool(value.truncated)
        name("records").array {
            value.events.forEach { event ->
                obj {
                    name("sequence").string(event.sequence)
                    name("timestamp_ns").string(event.timestampNs)
                    name("clock_domain").string(event.clockDomain)
                    name("severity").string(event.severity)
                    name("event_type_id").number(event.eventTypeId)
                    name("canonical_name").string(event.canonicalName)
                    name("scope").scope(event.scope)
                    name("payload").obj { event.payload.forEach { field -> name(field.key).string(field.value) } }
                }
            }
        }
    }

    private fun Json.benchmark(value: ReportBenchmark) = obj {
        name("duration_ns").nullableString(value.durationNs)
        value.durationSeconds?.let { name("duration_seconds").decimal(it) }
        name("status").string(value.status)
        name("interruption_reason").nullableString(value.interruptionReason)
        name("start_capture").capture(value.startCapture)
        name("end_capture").capture(value.endCapture)
        name("metrics").array {
            value.metrics.forEach { metric ->
                obj {
                    name("source_id").number(metric.sourceId)
                    name("scope").scope(metric.scope)
                    name("metric_id").number(metric.metricId)
                    name("name").string(metric.name)
                    when (metric) {
                        is ReportBenchmarkMetric.Counter -> {
                            name("kind").string("counter_u64")
                            name("start").string(metric.start)
                            name("end").string(metric.end)
                            name("delta").nullableString(metric.delta)
                            metric.ratePerSecond?.let { name("rate_per_second").decimal(it) }
                            name("unavailable_reason").nullableString(metric.unavailableReason)
                        }
                        is ReportBenchmarkMetric.Gauge -> {
                            name("kind").string("gauge_i64")
                            name("start").metricValue(metric.start)
                            name("end")
                            if (metric.end == null) nullValue() else metricValue(metric.end)
                            name("unavailable_reason").nullableString(metric.unavailableReason)
                        }
                        is ReportBenchmarkMetric.Histogram -> {
                            name("kind").string("histogram_u64")
                            name("start").metricValue(metric.start)
                            name("end")
                            if (metric.end == null) nullValue() else metricValue(metric.end)
                            name("window_count").nullableString(metric.windowCount)
                            name("window_sum").nullableString(metric.windowSum)
                            name("window_bucket_counts")
                            if (metric.windowBucketCounts == null) {
                                nullValue()
                            } else {
                                array {
                                    metric.windowBucketCounts.forEach(
                                        ::string,
                                    )
                                }
                            }
                            metric.windowAverage?.let { name("window_average").decimal(it) }
                            name("p50_upper_bound").nullableString(metric.p50UpperBound)
                            name("p95_upper_bound").nullableString(metric.p95UpperBound)
                            name("p99_upper_bound").nullableString(metric.p99UpperBound)
                            name("window_min").nullValue()
                            name("window_max").nullValue()
                            name("unavailable_reason").nullableString(metric.unavailableReason)
                        }
                    }
                }
            }
        }
    }
}

private class Json(private val out: Writer) {
    private val first = ArrayDeque<Boolean>()
    private var valueFollowsName = false
    private fun beforeValue() {
        if (valueFollowsName) {
            valueFollowsName = false
            return
        }
        if (first.isNotEmpty()) {
            val isFirst = first.removeLast()
            if (!isFirst) out.write(",")
            first.addLast(false)
        }
    }
    fun obj(block: Json.() -> Unit) {
        beforeValue()
        out.write("{")
        first.addLast(true)
        block()
        first.removeLast()
        out.write("}")
    }
    fun array(block: Json.() -> Unit) {
        beforeValue()
        out.write("[")
        first.addLast(true)
        block()
        first.removeLast()
        out.write("]")
    }
    fun name(value: String): Json {
        beforeValue()
        rawString(value)
        out.write(":")
        valueFollowsName = true
        return this
    }
    fun string(value: String) {
        beforeValue()
        rawString(value)
    }
    fun number(value: Any) {
        beforeValue()
        out.write(value.toString())
    }
    fun bool(value: Boolean) {
        beforeValue()
        out.write(value.toString())
    }
    fun nullValue() {
        beforeValue()
        out.write("null")
    }
    fun nullableString(value: String?) {
        if (value == null) nullValue() else string(value)
    }
    fun nullableNumber(value: Number?) {
        if (value == null) nullValue() else number(value)
    }
    fun decimal(value: Double) {
        beforeValue()
        out.write(String.format(Locale.ROOT, "%.6f", value))
    }
    private fun rawString(value: String) {
        out.write("\"")
        value.forEach { c ->
            when (c) {
                '"' -> out.write("\\\"")
                '\\' -> out.write("\\\\")
                '\b' -> out.write("\\b")
                '\u000c' -> out.write("\\f")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\t' -> out.write("\\t")
                else -> if (c.code < 0x20) out.write("\\u%04x".format(c.code)) else out.write(c.toString())
            }
        }
        out.write("\"")
    }
}
