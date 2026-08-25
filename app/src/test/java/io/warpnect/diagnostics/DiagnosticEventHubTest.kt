@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.telemetry.ClockDomainId
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventHubTest {
    @Test
    fun descriptorCatalogHasStaticBoundedSchemas() {
        DiagnosticEventDescriptorCatalog.validate(DiagnosticEventDescriptorCatalog.descriptors)

        assertEquals(52, DiagnosticEventDescriptorCatalog.descriptors.size)
        assertTrue(DiagnosticEventDescriptorCatalog.descriptors.all { it.id.value != 0 })
        assertTrue(DiagnosticEventDescriptorCatalog.descriptors.all { it.payload.size <= MAX_DIAGNOSTIC_EVENT_FIELDS })
        assertTrue(DiagnosticEventDescriptorCatalog.descriptors.all { it.canonicalName.startsWith("warpnect.event.") })
    }

    @Test
    fun ringOverwritesOldestAndReportsCursorGap() {
        val ring = DiagnosticEventRing(capacity = 4)
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(DiagnosticEventIds.HistoryStarted)!!
        repeat(6) { index ->
            ring.emit(descriptor, (index + 1).toULong(), ClockDomainId.AndroidBootTime, processScope())
        }

        val batch = ring.snapshotSince(cursor = 1u, limit = 4)
        assertTrue(batch.gap)
        assertEquals(2uL, batch.overwritten)
        assertEquals(3uL, batch.oldestAvailableSequence)
        assertEquals(listOf(3uL, 4uL, 5uL, 6uL), batch.events.map { it.sequence })
    }

    @Test
    fun incrementalReadsAreBoundedAndNonDestructive() {
        val ring = DiagnosticEventRing(capacity = 8)
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(DiagnosticEventIds.HistoryStarted)!!
        repeat(6) { ring.emit(descriptor, 1u, ClockDomainId.AndroidBootTime, processScope()) }

        val first = ring.snapshotSince(limit = 2)
        assertTrue(first.truncated)
        assertEquals(listOf(1uL, 2uL), first.events.map { it.sequence })
        val second = ring.snapshotSince(first.nextCursor, 4)
        assertFalse(second.truncated)
        assertEquals(listOf(3uL, 4uL, 5uL, 6uL), second.events.map { it.sequence })
        assertEquals(6uL, ring.snapshotSince().newestAvailableSequence)
    }

    @Test
    fun scopeSnapshotSurvivesWriterAndSessionLifetime() {
        val hub = DiagnosticEventHub(FakeClock(42u), ClockDomainId.AndroidBootTime)
        val sessionId = SessionId.requireValid(5u, 9u)
        val writer = hub.writer(TelemetryScope.Session(sessionId, SessionGeneration.requireValid(3u)))
        assertTrue(writer.emit(DiagnosticEventIds.SessionRunning))

        val event = hub.snapshotSince().kotlin.events.single { it.typeId == DiagnosticEventIds.SessionRunning }
        assertEquals(DiagnosticScopeKind.Session, event.scope.kind)
        assertEquals(3u, event.scope.sessionGeneration)
        assertEquals(5uL, event.scope.sessionIdHigh)
        assertEquals(9uL, event.scope.sessionIdLow)
        hub.close()
    }

    @Test
    fun disabledHistoryReturnsNoOpWriterWithoutChangingCallerBehavior() {
        val hub = DiagnosticEventHub.disabled()
        assertFalse(hub.writer(TelemetryScope.Process).enabled)
        assertFalse(hub.writer(TelemetryScope.Process).emit(DiagnosticEventIds.HistoryStarted))
        assertTrue(hub.snapshotSince().kotlin.events.isEmpty())
    }

    @Test
    fun selfMetricsAndMalformedNativeFailureRemainIsolated() {
        val telemetry = TelemetryHub()
        var nativeCalls = 0
        val malformed = NativeDiagnosticEventProvider { _, _ ->
            nativeCalls += 1
            if (nativeCalls == 1) {
                NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Malformed, DiagnosticEventBatch.empty(0u))
            } else {
                NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Available, DiagnosticEventBatch.empty(0u))
            }
        }
        val hub = DiagnosticEventHub(FakeClock(10u), ClockDomainId.AndroidBootTime, telemetry, malformed)
        hub.writer(TelemetryScope.Process).emit(DiagnosticEventIds.ProviderFailed, DiagnosticReason.Timeout.code)
        val snapshot = hub.snapshotSince()

        assertEquals(DiagnosticEventProviderStatus.Malformed, snapshot.native.status)
        val followUp = hub.snapshotSince(DiagnosticEventCursor(kotlinSequence = snapshot.kotlin.nextCursor))
        assertTrue(followUp.kotlin.events.any { it.typeId == DiagnosticEventIds.NativeBridgeMalformed })
        val metrics = telemetry.snapshot().records.associateBy { it.metricId }
        val snapshotMetric = metrics.getValue(TelemetryMetricIds.DiagnosticSnapshotCount)
        val parseFailureMetric = metrics.getValue(TelemetryMetricIds.DiagnosticNativeParseFailure)
        val snapshotCount = snapshotMetric.value as TelemetryMetricValue.Counter
        val parseFailureCount = parseFailureMetric.value as TelemetryMetricValue.Counter
        assertEquals(2uL, snapshotCount.value)
        assertEquals(1uL, parseFailureCount.value)
        hub.close()
        telemetry.close()
    }

    @Test
    fun wndeGoldenVectorParsesExactSessionRecord() {
        val event = NativeDiagnosticEventBridge.parse(littleEndianBuffer(validWnde())).getOrThrow().single()
        assertEquals(12uL, event.sequence)
        assertEquals(ClockDomainId.NativeSteady, event.clockDomain)
        assertEquals(DiagnosticEventIds.SessionStateChanged, event.typeId)
        assertTrue(event.payload.contentEquals(ulongArrayOf(1u, 2u)))
    }

    @Test
    fun malformedWndeVariantsAreRejectedWithoutRetainingRawProviderData() {
        val valid = validWnde()
        val malformed = listOf(
            valid.withByte(0, 'X'.code),
            valid.withByte(4, 2),
            valid.withByte(6, 31),
            valid.withByte(28, 0),
            valid.withByte(32 + 17, 4),
            valid.withByte(32 + 18, 0),
            valid.withByte(32 + 19, 5),
            valid.withByte(32 + 20, 0xff),
            valid.withByte(32 + 60, 1),
            valid.withByte(32 + 64, 99),
            valid.copyOf(127),
        )

        assertTrue(malformed.all { NativeDiagnosticEventBridge.parse(littleEndianBuffer(it)).isFailure })
    }

    private fun processScope() = DiagnosticScopeSnapshot(DiagnosticScopeKind.Process)

    private fun validWnde(): ByteArray = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("WNDE".encodeToByteArray())
        putShort(1)
        putShort(32)
        putLong(7)
        putLong(99)
        putInt(1)
        putInt(128)
        putLong(12)
        putLong(123_456)
        put(4) // NativeSteady
        put(2) // Info
        put(2) // Session
        put(2)
        putShort(0x0101)
        putShort(0)
        putInt(8)
        putInt(3)
        putLong(11)
        putLong(22)
        putInt(0)
        putInt(0)
        put(0)
        put(0)
        put(0)
        put(0)
        putInt(0)
        putLong(DiagnosticSessionState.Prepared.code.toLong())
        putLong(DiagnosticSessionState.Active.code.toLong())
        putLong(0)
        putLong(0)
    }.array()

    private fun littleEndianBuffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteArray.withByte(offset: Int, value: Int): ByteArray =
        copyOf().apply { this[offset] = value.toByte() }

    private class FakeClock(
        private val now: ULong,
    ) : DiagnosticEventClock {
        override fun nowNs(): ULong = now
    }
}
