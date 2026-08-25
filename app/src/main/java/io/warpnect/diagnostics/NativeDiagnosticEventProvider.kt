@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.NativeBridge
import io.warpnect.telemetry.TelemetryScopeKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val WNDE_HEADER_BYTES = 32
private const val WNDE_RECORD_BYTES = 96
private const val WNDE_VERSION = 1
private const val NATIVE_DIAGNOSTIC_SUCCESS = 0L
private const val NATIVE_DIAGNOSTIC_BUFFER_TOO_SMALL = 1L

/** One cold JNI transition reads a bounded native WNDE history batch. */
class NativeDiagnosticEventSnapshotProvider(
    private val collector: (ByteBuffer, Long, Int) -> LongArray = NativeBridge::diagnosticEventSnapshot,
) : NativeDiagnosticEventProvider {
    private val buffer = ByteBuffer.allocateDirect(MAX_NATIVE_DIAGNOSTIC_EVENT_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    @Synchronized
    override fun collect(cursor: ULong, limit: Int): NativeDiagnosticEventBatch {
        val raw = runCatching { collector(buffer, cursor.toLong(), limit) }.getOrElse {
            return NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Failed, DiagnosticEventBatch.empty(cursor))
        }
        if (raw.size != 11) {
            return NativeDiagnosticEventBatch(
                DiagnosticEventProviderStatus.Failed,
                DiagnosticEventBatch.empty(cursor),
            )
        }
        return when (raw[0]) {
            NATIVE_DIAGNOSTIC_SUCCESS -> {
                val bytes = raw[2]
                if (bytes !in WNDE_HEADER_BYTES.toLong()..MAX_NATIVE_DIAGNOSTIC_EVENT_BYTES.toLong()) {
                    NativeDiagnosticEventBatch(
                        DiagnosticEventProviderStatus.Malformed,
                        DiagnosticEventBatch.empty(cursor),
                    )
                } else {
                    buffer.position(0)
                    buffer.limit(bytes.toInt())
                    NativeDiagnosticEventBridge.parse(buffer.slice().order(ByteOrder.LITTLE_ENDIAN)).fold(
                        onSuccess = { events ->
                            NativeDiagnosticEventBatch(
                                DiagnosticEventProviderStatus.Available,
                                DiagnosticEventBatch(
                                    events = events,
                                    oldestAvailableSequence = raw[5].toULong(),
                                    newestAvailableSequence = raw[6].toULong(),
                                    nextCursor = raw[7].toULong(),
                                    gap = raw[9] != 0L,
                                    overwritten = raw[8].toULong(),
                                    truncated = raw[10] != 0L,
                                ),
                            )
                        },
                        onFailure = {
                            NativeDiagnosticEventBatch(
                                DiagnosticEventProviderStatus.Malformed,
                                DiagnosticEventBatch.empty(cursor),
                            )
                        },
                    )
                }
            }

            NATIVE_DIAGNOSTIC_BUFFER_TOO_SMALL ->
                NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Failed, DiagnosticEventBatch.empty(cursor))

            else -> NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Closed, DiagnosticEventBatch.empty(cursor))
        }
    }
}

/** Strict parser for the local-only, little-endian WNDE V1 batch format. */
object NativeDiagnosticEventBridge {
    fun parse(input: ByteBuffer): Result<List<DiagnosticEventRecord>> = runCatching {
        val buffer = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= WNDE_HEADER_BYTES)
        require(buffer.get().toInt() == 'W'.code)
        require(buffer.get().toInt() == 'N'.code)
        require(buffer.get().toInt() == 'D'.code)
        require(buffer.get().toInt() == 'E'.code)
        require((buffer.short.toInt() and 0xffff) == WNDE_VERSION)
        require((buffer.short.toInt() and 0xffff) == WNDE_HEADER_BYTES)
        buffer.long // Provider-local native batch sequence; it is not globally ordered with Kotlin.
        buffer.long // Provider-local native monotonic snapshot time.
        val count = buffer.int
        val total = buffer.int
        require(count in 0..MAX_NATIVE_DIAGNOSTIC_EVENTS)
        require(total == buffer.limit())
        require(total == WNDE_HEADER_BYTES + count * WNDE_RECORD_BYTES)
        List(count) { parseRecord(buffer) }.also { require(!buffer.hasRemaining()) }
    }

    private fun parseRecord(buffer: ByteBuffer): DiagnosticEventRecord {
        require(buffer.remaining() >= WNDE_RECORD_BYTES)
        val sequence = buffer.long.toULong()
        val timestamp = buffer.long.toULong()
        val domain = diagnosticClockDomainFromBridgeId(buffer.get().toInt() and 0xff) ?: malformed()
        val severity = DiagnosticSeverity.fromBridgeId(buffer.get().toInt() and 0xff) ?: malformed()
        val scopeKind = DiagnosticScopeKind.fromBridgeId(buffer.get().toInt() and 0xff) ?: malformed()
        val fieldCount = buffer.get().toInt() and 0xff
        val type = DiagnosticEventTypeId(buffer.short.toInt() and 0xffff)
        val flags = buffer.short.toInt() and 0xffff
        require(flags == 0 && fieldCount <= MAX_DIAGNOSTIC_EVENT_FIELDS)
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(type) ?: malformed()
        require(descriptor.defaultSeverity == severity)
        require(descriptor.payload.size == fieldCount)
        require(scopeKind.toTelemetryScopeKind() in descriptor.allowedScopes)
        val sourceId = buffer.int.toUInt()
        val generation = buffer.int.toUInt()
        val sessionHigh = buffer.long.toULong()
        val sessionLow = buffer.long.toULong()
        val pathId = buffer.int.toUInt()
        val channelId = buffer.int.toUInt()
        val pathKind = buffer.get().toInt() and 0xff
        val channelKind = buffer.get().toInt() and 0xff
        val direction = buffer.get().toInt() and 0xff
        val component = buffer.get().toInt() and 0xff
        require(buffer.int == 0)
        val values = ULongArray(MAX_DIAGNOSTIC_EVENT_FIELDS) { buffer.long.toULong() }
        validateScope(
            scopeKind,
            generation,
            sessionHigh,
            sessionLow,
            pathId,
            channelId,
            pathKind,
            channelKind,
            direction,
            component,
        )
        descriptor.payload.forEachIndexed { index, field -> validatePayload(field, values[index]) }
        return DiagnosticEventRecord(
            sequence,
            timestamp,
            domain,
            severity,
            DiagnosticScopeSnapshot(
                scopeKind,
                sourceId,
                generation,
                sessionHigh,
                sessionLow,
                pathId,
                channelId,
                pathKind,
                channelKind,
                direction,
                component,
            ),
            type,
            values.copyOf(fieldCount),
        )
    }

    private fun validateScope(
        kind: DiagnosticScopeKind,
        generation: UInt,
        high: ULong,
        low: ULong,
        pathId: UInt,
        channelId: UInt,
        pathKind: Int,
        channelKind: Int,
        direction: Int,
        component: Int,
    ) {
        when (kind) {
            DiagnosticScopeKind.Process -> Unit
            DiagnosticScopeKind.Session -> require(generation != 0u && (high != 0uL || low != 0uL))
            DiagnosticScopeKind.Path -> require(
                generation != 0u && (high != 0uL || low != 0uL) && pathId != 0u && pathKind in 1..2,
            )
            DiagnosticScopeKind.Channel -> require(
                generation != 0u &&
                    (high != 0uL || low != 0uL) &&
                    channelId != 0u &&
                    channelKind in 1..6 &&
                    direction in 1..3,
            )
            DiagnosticScopeKind.Component -> require(component in 1..19)
        }
    }

    private fun validatePayload(field: DiagnosticPayloadFieldDescriptor, value: ULong) {
        if (field.kind != DiagnosticScalarKind.Enum) return
        when (field.key) {
            DiagnosticPayloadFieldKey.Reason -> require(DiagnosticReason.entries.any { it.code == value })
            DiagnosticPayloadFieldKey.FromState,
            DiagnosticPayloadFieldKey.ToState,
            -> require(DiagnosticSessionState.entries.any { it.code == value })
            else -> Unit
        }
    }

    private fun malformed(): Nothing = throw IllegalArgumentException("Malformed WNDE diagnostic event batch")
}

private fun DiagnosticScopeKind.toTelemetryScopeKind(): TelemetryScopeKind = when (this) {
    DiagnosticScopeKind.Process -> TelemetryScopeKind.Process
    DiagnosticScopeKind.Session -> TelemetryScopeKind.Session
    DiagnosticScopeKind.Path -> TelemetryScopeKind.Path
    DiagnosticScopeKind.Channel -> TelemetryScopeKind.Channel
    DiagnosticScopeKind.Component -> TelemetryScopeKind.Component
}
