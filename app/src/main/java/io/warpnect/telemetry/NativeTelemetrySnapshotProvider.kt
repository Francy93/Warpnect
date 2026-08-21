@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import io.warpnect.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val WNTM_HEADER_BYTES = 32
private const val WNTM_RECORD_HEADER_BYTES = 16
private const val WNTM_BRIDGE_VERSION = 1
private const val NATIVE_SNAPSHOT_SUCCESS = 0L
private const val NATIVE_SNAPSHOT_BUFFER_TOO_SMALL = 1L

fun interface NativeTelemetrySourceScopeResolver {
    fun scopeFor(sourceId: TelemetrySourceId): TelemetryScope?
}

/**
 * Cold-path adapter for the local WNTM bridge. One provider collection makes one JNI call,
 * regardless of how many native sources or instruments it returns.
 */
class NativeTelemetrySnapshotProvider(
    private val sourceScopes: NativeTelemetrySourceScopeResolver,
    private val collector: (ByteBuffer) -> LongArray = NativeBridge::runtimeTelemetrySnapshot,
) : TelemetrySnapshotProvider {
    private val buffer = ByteBuffer.allocateDirect(MAX_NATIVE_TELEMETRY_SNAPSHOT_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    @Synchronized
    override fun collect(): TelemetryProviderSnapshot {
        val result = runCatching { collector(buffer) }.getOrElse {
            return TelemetryProviderSnapshot(emptyList(), setOf(TelemetrySnapshotError.ProviderFailure))
        }
        if (result.size != 5) {
            return TelemetryProviderSnapshot(emptyList(), setOf(TelemetrySnapshotError.ProviderFailure))
        }
        return when (result[0]) {
            NATIVE_SNAPSHOT_SUCCESS -> {
                val bytesWritten = result[2]
                if (bytesWritten !in WNTM_HEADER_BYTES.toLong()..MAX_NATIVE_TELEMETRY_SNAPSHOT_BYTES.toLong()) {
                    TelemetryProviderSnapshot(emptyList(), setOf(TelemetrySnapshotError.MalformedNativeSnapshot))
                } else {
                    buffer.position(0)
                    buffer.limit(bytesWritten.toInt())
                    NativeTelemetrySnapshotBridge.parse(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), sourceScopes)
                }
            }

            NATIVE_SNAPSHOT_BUFFER_TOO_SMALL ->
                TelemetryProviderSnapshot(emptyList(), setOf(TelemetrySnapshotError.BufferTooSmall))

            else -> TelemetryProviderSnapshot(emptyList(), setOf(TelemetrySnapshotError.ProviderFailure))
        }
    }
}

/** Parser for the local-only Native Telemetry Snapshot Bridge V1 (`WNTM`). */
object NativeTelemetrySnapshotBridge {
    fun parse(input: ByteBuffer, sourceScopes: NativeTelemetrySourceScopeResolver): TelemetryProviderSnapshot {
        val buffer = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        fun malformed(): TelemetryProviderSnapshot {
            return TelemetryProviderSnapshot(emptyList(), setOf(TelemetrySnapshotError.MalformedNativeSnapshot))
        }
        if (buffer.remaining() < WNTM_HEADER_BYTES) return malformed()
        if (
            buffer.get().toInt() != 'W'.code ||
            buffer.get().toInt() != 'N'.code ||
            buffer.get().toInt() != 'T'.code ||
            buffer.get().toInt() != 'M'.code
        ) {
            return malformed()
        }
        if (buffer.short.toInt() and 0xffff != WNTM_BRIDGE_VERSION) return malformed()
        if (buffer.short.toInt() and 0xffff != WNTM_HEADER_BYTES) return malformed()
        buffer.long // Native provider sequence is diagnostic-only in its own monotonic domain.
        buffer.long // Native provider timestamp is not subtracted from the Kotlin clock.
        val recordCount = buffer.int
        val totalBytes = buffer.int
        if (recordCount !in 0..MAX_TELEMETRY_SNAPSHOT_RECORDS || totalBytes != buffer.limit()) return malformed()

        val records = ArrayList<TelemetrySnapshotRecord>(recordCount)
        val seen = HashSet<ULong>(recordCount)
        repeat(recordCount) {
            if (buffer.remaining() < WNTM_RECORD_HEADER_BYTES) return malformed()
            val rawSourceId = buffer.int.toUInt()
            val rawMetricId = buffer.short.toInt() and 0xffff
            val kind = TelemetryMetricKind.fromBridgeId(buffer.get().toInt() and 0xff) ?: return malformed()
            val flags = buffer.get().toInt() and 0xff
            val payloadBytes = buffer.short.toInt() and 0xffff
            if (buffer.short.toInt() != 0 || buffer.int != 0 || flags != 0 || rawSourceId == 0u) return malformed()
            if (payloadBytes > buffer.remaining()) return malformed()
            val sourceId = TelemetrySourceId(rawSourceId)
            val metricId = runCatching { TelemetryMetricId(rawMetricId) }.getOrNull() ?: return malformed()
            val descriptor = TelemetryDescriptorCatalog.descriptor(metricId) ?: return malformed()
            if (descriptor.kind != kind) return malformed()
            val scope = sourceScopes.scopeFor(sourceId) ?: return malformed()
            if (scope.kind !in descriptor.allowedScopes) return malformed()
            val recordKey = (rawSourceId.toULong() shl 16) or rawMetricId.toULong()
            if (!seen.add(recordKey)) return malformed()

            val payloadStart = buffer.position()
            val value = when (kind) {
                TelemetryMetricKind.CounterU64 -> {
                    if (payloadBytes != 8) return malformed()
                    TelemetryMetricValue.Counter(buffer.long.toULong())
                }

                TelemetryMetricKind.GaugeI64 -> {
                    if (payloadBytes != 16) return malformed()
                    val valid = buffer.get().toInt() and 0xff
                    if (valid !in 0..1) return malformed()
                    repeat(7) { if (buffer.get().toInt() != 0) return malformed() }
                    TelemetryMetricValue.Gauge(valid == 1, buffer.long)
                }

                TelemetryMetricKind.HistogramU64 -> {
                    if (payloadBytes < 40) return malformed()
                    val count = buffer.long.toULong()
                    val sum = buffer.long.toULong()
                    val min = buffer.long.toULong()
                    val max = buffer.long.toULong()
                    val boundaryCount = buffer.get().toInt() and 0xff
                    if (boundaryCount != descriptor.histogramBoundaries.size) return malformed()
                    repeat(7) { if (buffer.get().toInt() != 0) return malformed() }
                    val expectedBytes = 40 + ((boundaryCount + 1) * Long.SIZE_BYTES)
                    if (payloadBytes != expectedBytes) return malformed()
                    val buckets = ULongArray(boundaryCount + 1) { buffer.long.toULong() }
                    TelemetryMetricValue.Histogram(
                        count,
                        sum,
                        if (count == 0uL) null else min,
                        if (count == 0uL) null else max,
                        buckets,
                    )
                }
            }
            if (buffer.position() - payloadStart != payloadBytes) return malformed()
            records += TelemetrySnapshotRecord(sourceId, scope, metricId, kind, value)
        }
        return if (buffer.hasRemaining()) malformed() else TelemetryProviderSnapshot(records)
    }
}
