package io.warpnect.session.lifecycle

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleEngineTest {
    @Test
    fun authenticatedTrafficSuppressesIdleHeartbeatAndAckResetsMisses() {
        val engine = activeEngine()
        assertEquals(LifecycleDecision.Active, engine.onAuthenticatedReceive(450))
        assertEquals(LifecycleDecision.Ignored, engine.advance(900, heartbeat(1u)))
        val probe = engine.advance(951, heartbeat(1u)) as LifecycleDecision.SendHeartbeat
        assertEquals(path(1u), probe.activePathId)
        assertEquals(LifecycleDecision.Active, engine.onHeartbeatAck(probe.heartbeatId, path(1u), 1_000))
        assertEquals(0, engine.snapshot(1_000).consecutiveHeartbeatMisses)
    }

    @Test
    fun thresholdLossStartsMigrationOnlyAfterConfiguredMisses() {
        val engine = activeEngine()
        val first = engine.advance(500, heartbeat(1u)) as LifecycleDecision.SendHeartbeat
        assertEquals(LifecycleDecision.HeartbeatMissed(1), engine.advance(800, heartbeat(2u)))
        val second = engine.advance(801, heartbeat(2u)) as LifecycleDecision.SendHeartbeat
        assertEquals(LifecycleDecision.BeginMigration, engine.advance(1_101, heartbeat(3u)))
        assertTrue(first.heartbeatId.isValid && second.heartbeatId.isValid)
    }

    @Test
    fun hardActiveLossStartsChallengeAndCommitPreservesGeneration() {
        val engine = activeEngine()
        assertEquals(LifecycleDecision.BeginMigration, engine.onPlatformPathLost(path(1u), hard = true, nowMs = 1))
        val validation = engine.beginMigration(migration(1u), 1) as LifecycleDecision.ValidateStandby
        assertEquals(path(2u), validation.pathId)
        val committed = engine.onMigrationCommitted(migration(1u), oldPathStillUsable = false, nowMs = 50)
        assertTrue(committed is LifecycleDecision.MigrationCommitted)
        val snapshot = engine.snapshot(50)
        assertEquals(SessionGeneration.Initial, snapshot.generation)
        assertEquals(path(2u), snapshot.activePathId)
        assertEquals(LifecycleTransportGate.ActiveTransport, snapshot.transportGate)
    }

    @Test
    fun allPathLossCreatesBoundedFreshGenerationRecovery() {
        val engine = activeEngine(standby = false)
        val suspended = engine.onPlatformPathLost(path(1u), hard = true, nowMs = 100) as LifecycleDecision.Suspended
        assertEquals(SessionLifecycleState.Suspended, engine.snapshot(100).state)
        val reconnect = engine.beginReconnect(101) as LifecycleDecision.BeginReconnect
        assertEquals(SessionGeneration.requireValid(2u), reconnect.nextGeneration)
        assertEquals(
            LifecycleDecision.Reconnected(SessionGeneration.requireValid(2u)),
            engine.onReconnectPrepared(reconnect.nextGeneration, 200),
        )
        assertEquals(SessionGeneration.requireValid(2u), engine.snapshot(200).generation)
        assertEquals(SessionLifecycleState.Prepared, engine.snapshot(200).state)
        assertTrue(suspended.recoveryDeadlineMs > 100)
    }

    @Test
    fun reconnectFailuresUseBoundedBackoffAndNeverRetryPastLimit() {
        val config = LifecycleHealthConfig(reconnectBackoffMs = listOf(0L, 250L, 500L, 1_000L, 2_000L, 4_000L))
        val engine = SessionLifecycleEngine(session(1u), SessionGeneration.Initial, config)
        engine.consumePrepared(path(1u), NetworkPathKind.Lan, null, null, 0)
        engine.onPlatformPathLost(path(1u), hard = true, nowMs = 1)
        assertTrue(engine.beginReconnect(1) is LifecycleDecision.BeginReconnect)
        assertEquals(LifecycleDecision.ReconnectRetry(251), engine.onReconnectAttemptFailed(1))
        assertEquals(LifecycleDecision.Ignored, engine.advance(250, heartbeat(2u)))
        assertTrue(engine.advance(251, heartbeat(2u)) is LifecycleDecision.BeginReconnect)
        assertEquals(2, engine.snapshot(251).reconnectAttemptCount)
    }

    @Test
    fun recoveryDeadlineWinsOverScheduledReconnect() {
        val config =
            LifecycleHealthConfig(
                recoveryWindowMs = 2_000L,
                reconnectBackoffMs = listOf(0L, 250L, 500L, 1_000L, 2_000L, 4_000L),
            )
        val engine = SessionLifecycleEngine(session(1u), SessionGeneration.Initial, config)
        engine.consumePrepared(path(1u), NetworkPathKind.Lan, null, null, 0)
        engine.onPlatformPathLost(path(1u), hard = true, nowMs = 1)
        engine.beginReconnect(1)
        engine.onReconnectAttemptFailed(1)
        assertEquals(
            LifecycleDecision.Error(SessionLifecycleError.RecoveryExpired),
            engine.advance(2_001, heartbeat(3u)),
        )
        assertEquals(SessionLifecycleState.Failed, engine.snapshot(2_001).state)
    }

    @Test
    fun disabledRecoveryIsTerminalAndGenerationNeverWraps() {
        val disabled =
            SessionLifecycleEngine(
                session(1u),
                SessionGeneration.Initial,
                recoveryPolicy = SessionRecoveryPolicy.Disabled,
            )
        disabled.consumePrepared(path(1u), NetworkPathKind.Lan, null, null, 0)
        assertEquals(
            LifecycleDecision.Terminal(SessionLifecycleError.RecoveryDisabled),
            disabled.onPlatformPathLost(path(1u), true, 1),
        )

        val maximum = SessionLifecycleEngine(session(2u), SessionGeneration.requireValid(UInt.MAX_VALUE))
        maximum.consumePrepared(path(1u), NetworkPathKind.Lan, null, null, 0)
        maximum.onPlatformPathLost(path(1u), true, 1)
        assertEquals(
            LifecycleDecision.Error(SessionLifecycleError.SessionGenerationExhausted),
            maximum.beginReconnect(2),
        )
    }

    @Test
    fun standbyLossOnlyDegradesHealthySession() {
        val engine = activeEngine()
        assertEquals(
            LifecycleDecision.StandbyLost(path(2u)),
            engine.onPlatformPathLost(path(2u), hard = true, nowMs = 1),
        )
        assertEquals(SessionLifecycleState.Degraded, engine.snapshot(2).state)
        assertEquals(path(1u), engine.snapshot(2).activePathId)
    }

    private fun activeEngine(standby: Boolean = true): SessionLifecycleEngine = SessionLifecycleEngine(
        session(1u),
        SessionGeneration.Initial,
    ).also {
        it.consumePrepared(
            path(1u),
            NetworkPathKind.Direct,
            if (standby) path(2u) else null,
            if (standby) NetworkPathKind.Lan else null,
            0,
        )
    }

    private fun heartbeat(value: ULong): HeartbeatId = HeartbeatId.requireValid(value)
    private fun migration(value: ULong): PathMigrationId = PathMigrationId.requireValid(value)
    private fun path(value: UInt): PathId = PathId.requireValid(value)
    private fun session(value: ULong): SessionId = SessionId.requireValid(0u, value)
}
