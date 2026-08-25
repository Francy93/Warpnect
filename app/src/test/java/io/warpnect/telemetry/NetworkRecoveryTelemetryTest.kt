package io.warpnect.telemetry

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.security.SessionProtectionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryTelemetryTest {
    @Test
    fun networkRecoveryCatalogReservesExactlyTheFrozenDescriptors() {
        val descriptors = TelemetryDescriptorCatalog.descriptors.associateBy { it.id }

        assertEquals(143, descriptors.size)
        assertEquals(
            "warpnect.session.heartbeat.sent",
            descriptors.getValue(TelemetryMetricIds.SessionHeartbeatSent).canonicalName,
        )
        assertEquals(
            TelemetryUnit.Packets,
            descriptors.getValue(TelemetryMetricIds.UdpDatagramSent).unit,
        )
        assertEquals(
            TelemetryMetricKind.GaugeI64,
            descriptors.getValue(TelemetryMetricIds.PathActive).kind,
        )
        assertEquals(
            "warpnect.security.protection.replay_dropped",
            descriptors.getValue(TelemetryMetricIds.ProtectionReplayDropped).canonicalName,
        )
    }

    @Test
    fun lifecyclePathAndControlSourcesKeepSemanticsIndependent() {
        val hub = TelemetryHub()
        val sessionId = SessionId.requireValid(5u, 7u)
        val generation = SessionGeneration.requireValid(1u)
        val lifecycle = SessionLifecycleTelemetry.register(
            hub,
            TelemetryScope.Session(sessionId, generation),
        )
        val path = SessionPathTelemetry.register(
            hub,
            TelemetryScope.Path(sessionId, generation, PathId.requireValid(2u), NetworkPathKind.Lan),
        )
        val control = SessionControlNetworkTelemetry.register(
            hub,
            TelemetryScope.Session(sessionId, generation),
        )

        lifecycle.heartbeatSent.increment()
        lifecycle.migrationStarted.increment()
        lifecycle.migrationSucceeded.increment()
        path.platformAvailable.increment()
        path.validationStarted.increment()
        path.validationSucceeded.increment()
        path.active.set(1)
        path.validated.set(1)
        control.recordProduced()
        control.udpSent(96)
        control.udpReceived(112)
        control.recordUnprotectError(SessionProtectionError.None)
        control.recordUnprotectError(SessionProtectionError.ReplayDuplicate)
        control.recordUnprotectError(SessionProtectionError.EndpointMismatch)

        val snapshot = hub.snapshot()
        assertCounter(snapshot, TelemetryMetricIds.SessionHeartbeatSent, 1u)
        assertCounter(snapshot, TelemetryMetricIds.SessionPathMigrationStarted, 1u)
        assertCounter(snapshot, TelemetryMetricIds.SessionPathMigrationSucceeded, 1u)
        assertCounter(snapshot, TelemetryMetricIds.PathPlatformAvailable, 1u)
        assertCounter(snapshot, TelemetryMetricIds.PathValidationSucceeded, 1u)
        assertCounter(snapshot, TelemetryMetricIds.UdpDatagramSent, 1u)
        assertCounter(snapshot, TelemetryMetricIds.UdpByteSent, 96u)
        assertCounter(snapshot, TelemetryMetricIds.ProtectionRecordProduced, 1u)
        assertCounter(snapshot, TelemetryMetricIds.ProtectionRecordAccepted, 1u)
        assertCounter(snapshot, TelemetryMetricIds.ProtectionReplayDropped, 1u)
        assertCounter(snapshot, TelemetryMetricIds.ProtectionEndpointMismatch, 1u)
        assertTrue(
            snapshot.records.any {
                it.metricId == TelemetryMetricIds.PathActive &&
                    (it.value as TelemetryMetricValue.Gauge).value == 1L
            },
        )

        lifecycle.close()
        path.close()
        control.close()
        assertTrue(hub.snapshot().records.none { it.sourceId != TelemetrySourceId(1u) })
    }

    private fun assertCounter(snapshot: TelemetrySnapshot, id: TelemetryMetricId, value: ULong) {
        assertEquals(
            value,
            (snapshot.records.single { it.metricId == id }.value as TelemetryMetricValue.Counter).value,
        )
    }
}
