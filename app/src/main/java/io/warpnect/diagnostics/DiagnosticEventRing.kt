@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.telemetry.ClockDomainId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Process-local bounded history. Writers publish into a preallocated slot and never wait for a
 * consumer; snapshots materialize immutable records only on the cold path.
 */
class DiagnosticEventRing(
    capacity: Int = MAX_KOTLIN_DIAGNOSTIC_EVENTS,
) {
    private val capacity = capacity
    private val nextSequence = AtomicLong(1L)
    private val sealed = AtomicBoolean(false)
    private val overwriteCount = AtomicLong(0L)
    private val publishedSequence = AtomicLongArray(capacity)
    private val timestamps = AtomicLongArray(capacity)
    private val metadata = AtomicLongArray(capacity)
    private val sourceAndGeneration = AtomicLongArray(capacity)
    private val sessionHigh = AtomicLongArray(capacity)
    private val sessionLow = AtomicLongArray(capacity)
    private val pathAndChannel = AtomicLongArray(capacity)
    private val kinds = AtomicLongArray(capacity)
    private val payload0 = AtomicLongArray(capacity)
    private val payload1 = AtomicLongArray(capacity)
    private val payload2 = AtomicLongArray(capacity)
    private val payload3 = AtomicLongArray(capacity)

    init {
        require(capacity in 1..MAX_KOTLIN_DIAGNOSTIC_EVENTS && capacity and (capacity - 1) == 0) {
            "Diagnostic history capacity must be a power of two no larger than $MAX_KOTLIN_DIAGNOSTIC_EVENTS"
        }
    }

    fun emit(
        descriptor: DiagnosticEventDescriptor,
        timestampNs: ULong,
        clockDomain: ClockDomainId,
        scope: DiagnosticScopeSnapshot,
        payload0Value: ULong = 0u,
        payload1Value: ULong = 0u,
        payload2Value: ULong = 0u,
        payload3Value: ULong = 0u,
    ): ULong {
        if (sealed.get() || scope.kind.toTelemetryScopeKind() !in descriptor.allowedScopes) return 0u
        if (!payloadIsValid(descriptor, payload0Value, payload1Value, payload2Value, payload3Value)) return 0u
        val sequence = nextSequence.getAndIncrement()
        if (sequence <= 0L) {
            sealed.set(true)
            return 0u
        }
        val index = sequence.toInt() and (capacity - 1)
        timestamps.set(index, timestampNs.toLong())
        metadata.set(
            index,
            packMetadata(
                clockDomain,
                descriptor.defaultSeverity,
                scope.kind,
                descriptor.payload.size,
                descriptor.id,
            ),
        )
        sourceAndGeneration.set(index, packUInts(scope.sourceId, scope.sessionGeneration))
        sessionHigh.set(index, scope.sessionIdHigh.toLong())
        sessionLow.set(index, scope.sessionIdLow.toLong())
        pathAndChannel.set(index, packUInts(scope.pathId, scope.channelId))
        kinds.set(index, packKinds(scope.pathKind, scope.channelKind, scope.channelDirection, scope.componentKind))
        payload0.set(index, payload0Value.toLong())
        payload1.set(index, payload1Value.toLong())
        payload2.set(index, payload2Value.toLong())
        payload3.set(index, payload3Value.toLong())
        publishedSequence.set(index, sequence)
        if (sequence > capacity) overwriteCount.incrementAndGet()
        return sequence.toULong()
    }

    fun snapshotSince(cursor: ULong = 0u, limit: Int = DEFAULT_DIAGNOSTIC_SNAPSHOT_LIMIT): DiagnosticEventBatch {
        require(limit in 1..MAX_DIAGNOSTIC_SNAPSHOT_LIMIT)
        val newest = nextSequence.get() - 1L
        if (newest <= 0L) return DiagnosticEventBatch.empty(cursor)
        val oldest = (newest - capacity + 1L).coerceAtLeast(1L)
        val requested = cursor.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
        val gap = requested != 0L && requested < oldest - 1L
        val hasNewRecords = requested < newest
        var sequence = if (hasNewRecords) maxOf(oldest, requested + 1L) else newest
        val records = ArrayList<DiagnosticEventRecord>(limit)
        while (hasNewRecords && sequence <= newest && records.size < limit) {
            read(sequence)?.let(records::add)
            sequence += 1L
        }
        val truncated = hasNewRecords && sequence <= newest
        val next = if (records.isNotEmpty()) records.last().sequence else newest.toULong()
        return DiagnosticEventBatch(
            events = records,
            oldestAvailableSequence = oldest.toULong(),
            newestAvailableSequence = newest.toULong(),
            nextCursor = if (truncated) next else newest.toULong(),
            gap = gap,
            overwritten = overwriteCount.get().coerceAtLeast(0L).toULong(),
            truncated = truncated,
        )
    }

    fun overwrittenCount(): ULong = overwriteCount.get().coerceAtLeast(0L).toULong()

    private fun payloadIsValid(
        descriptor: DiagnosticEventDescriptor,
        payload0Value: ULong,
        payload1Value: ULong,
        payload2Value: ULong,
        payload3Value: ULong,
    ): Boolean {
        for (index in descriptor.payload.indices) {
            val value = when (index) {
                0 -> payload0Value
                1 -> payload1Value
                2 -> payload2Value
                else -> payload3Value
            }
            val field = descriptor.payload[index]
            if (field.kind == DiagnosticScalarKind.Boolean && value > 1u) return false
            if (field.kind == DiagnosticScalarKind.Enum && !isKnownEnumValue(field.key, value)) return false
        }
        return true
    }

    private fun isKnownEnumValue(key: DiagnosticPayloadFieldKey, value: ULong): Boolean = when (key) {
        DiagnosticPayloadFieldKey.Reason -> DiagnosticReason.entries.any { it.code == value }
        DiagnosticPayloadFieldKey.FromState,
        DiagnosticPayloadFieldKey.ToState,
        -> DiagnosticSessionState.entries.any { it.code == value }
        else -> false
    }

    private fun read(sequence: Long): DiagnosticEventRecord? {
        val index = sequence.toInt() and (capacity - 1)
        if (publishedSequence.get(index) != sequence) return null
        val packedMetadata = metadata.get(index)
        val type = DiagnosticEventTypeId(((packedMetadata ushr 32) and 0xffffL).toInt())
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(type) ?: return null
        val severity = DiagnosticSeverity.fromBridgeId(((packedMetadata ushr 8) and 0xffL).toInt()) ?: return null
        val scopeKind = DiagnosticScopeKind.fromBridgeId((packedMetadata and 0xffL).toInt()) ?: return null
        val fieldCount = ((packedMetadata ushr 16) and 0xffL).toInt()
        val clockDomain = diagnosticClockDomainFromBridgeId(((packedMetadata ushr 24) and 0xffL).toInt()) ?: return null
        if (severity != descriptor.defaultSeverity || fieldCount != descriptor.payload.size) return null
        val sourceGeneration = sourceAndGeneration.get(index)
        val pathChannel = pathAndChannel.get(index)
        val packedKinds = kinds.get(index)
        val payload = ulongArrayOf(
            payload0.get(index).toULong(),
            payload1.get(index).toULong(),
            payload2.get(index).toULong(),
            payload3.get(index).toULong(),
        )
            .copyOf(fieldCount)
        if (publishedSequence.get(index) != sequence) return null
        return DiagnosticEventRecord(
            sequence = sequence.toULong(),
            timestampNs = timestamps.get(index).toULong(),
            clockDomain = clockDomain,
            severity = severity,
            scope = DiagnosticScopeSnapshot(
                kind = scopeKind,
                sourceId = unpackHighUInt(sourceGeneration),
                sessionGeneration = unpackLowUInt(sourceGeneration),
                sessionIdHigh = sessionHigh.get(index).toULong(),
                sessionIdLow = sessionLow.get(index).toULong(),
                pathId = unpackHighUInt(pathChannel),
                channelId = unpackLowUInt(pathChannel),
                pathKind = (packedKinds and 0xffL).toInt(),
                channelKind = ((packedKinds ushr 8) and 0xffL).toInt(),
                channelDirection = ((packedKinds ushr 16) and 0xffL).toInt(),
                componentKind = ((packedKinds ushr 24) and 0xffL).toInt(),
            ),
            typeId = type,
            payload = payload,
        )
    }

    private fun packMetadata(
        clockDomain: ClockDomainId,
        severity: DiagnosticSeverity,
        scopeKind: DiagnosticScopeKind,
        fieldCount: Int,
        typeId: DiagnosticEventTypeId,
    ): Long = scopeKind.bridgeId.toLong() or
        (severity.bridgeId.toLong() shl 8) or
        (fieldCount.toLong() shl 16) or
        (clockDomain.diagnosticBridgeId().toLong() shl 24) or
        (typeId.value.toLong() shl 32)

    private fun packUInts(high: UInt, low: UInt): Long = (high.toLong() shl 32) or low.toLong()

    private fun unpackHighUInt(value: Long): UInt = (value ushr 32).toUInt()

    private fun unpackLowUInt(value: Long): UInt = value.toUInt()

    private fun packKinds(pathKind: Int, channelKind: Int, direction: Int, component: Int): Long =
        pathKind.toLong() or (channelKind.toLong() shl 8) or (direction.toLong() shl 16) or (component.toLong() shl 24)
}

data class DiagnosticEventBatch(
    val events: List<DiagnosticEventRecord>,
    val oldestAvailableSequence: ULong,
    val newestAvailableSequence: ULong,
    val nextCursor: ULong,
    val gap: Boolean,
    val overwritten: ULong,
    val truncated: Boolean,
) {
    companion object {
        fun empty(cursor: ULong) = DiagnosticEventBatch(emptyList(), 0u, 0u, cursor, false, 0u, false)
    }
}

private fun DiagnosticScopeKind.toTelemetryScopeKind(): io.warpnect.telemetry.TelemetryScopeKind = when (this) {
    DiagnosticScopeKind.Process -> io.warpnect.telemetry.TelemetryScopeKind.Process
    DiagnosticScopeKind.Session -> io.warpnect.telemetry.TelemetryScopeKind.Session
    DiagnosticScopeKind.Path -> io.warpnect.telemetry.TelemetryScopeKind.Path
    DiagnosticScopeKind.Channel -> io.warpnect.telemetry.TelemetryScopeKind.Channel
    DiagnosticScopeKind.Component -> io.warpnect.telemetry.TelemetryScopeKind.Component
}
