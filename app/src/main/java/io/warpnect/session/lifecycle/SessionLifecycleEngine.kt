package io.warpnect.session.lifecycle

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId

/**
 * Portable RFC-005H state machine. It deliberately has no Android, socket, crypto or media
 * dependency; the controller performs side effects only after this engine emits a decision.
 */
class SessionLifecycleEngine(
    private val sessionId: SessionId,
    private var generation: SessionGeneration,
    private val healthConfig: LifecycleHealthConfig = LifecycleHealthConfig(),
    private val recoveryPolicy: SessionRecoveryPolicy = SessionRecoveryPolicy.FastReconnect,
) {
    private var state = SessionLifecycleState.Prepared
    private var transportGate = LifecycleTransportGate.Suspended
    private var active: LifecyclePath? = null
    private var standby: LifecyclePath? = null
    private var lastAuthenticatedReceiveAtMs: Long? = null
    private var pendingHeartbeat: PendingHeartbeat? = null
    private var heartbeatMisses = 0
    private var migration: PendingMigration? = null
    private var recoveryDeadlineMs: Long? = null
    private var reconnectAttempts = 0
    private var reconnectRetryAtMs: Long? = null
    private var lastError = SessionLifecycleError.None

    init {
        require(healthConfig.isValid()) { "Lifecycle health configuration is invalid" }
    }

    fun consumePrepared(
        activePathId: PathId,
        activePathKind: NetworkPathKind,
        standbyPathId: PathId?,
        standbyPathKind: NetworkPathKind?,
        nowMs: Long,
    ): LifecycleDecision {
        if (state != SessionLifecycleState.Prepared || (standbyPathId == null) != (standbyPathKind == null)) {
            return failure(SessionLifecycleError.InvalidState)
        }
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        state = SessionLifecycleState.Establishing
        active = LifecyclePath(activePathId, activePathKind, LifecyclePathHealth.Active)
        standby = standbyPathId?.let {
            LifecyclePath(it, requireNotNull(standbyPathKind), LifecyclePathHealth.ValidatedStandby)
        }
        lastAuthenticatedReceiveAtMs = now
        state = SessionLifecycleState.Active
        transportGate = LifecycleTransportGate.ActiveTransport
        return LifecycleDecision.Active
    }

    /** Any successfully authenticated WNSD datagram is liveness, regardless of its SCL payload type. */
    fun onAuthenticatedReceive(nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        if (state.isTerminal() || state == SessionLifecycleState.Disconnecting) return LifecycleDecision.Ignored
        lastAuthenticatedReceiveAtMs = now
        pendingHeartbeat = null
        heartbeatMisses = 0
        return LifecycleDecision.Active
    }

    fun advance(nowMs: Long, heartbeatId: HeartbeatId): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        if (state.isTerminal() || state == SessionLifecycleState.Disconnecting) return LifecycleDecision.Ignored
        if (state == SessionLifecycleState.Suspended || state == SessionLifecycleState.Reconnecting) {
            return if (recoveryDeadlineMs != null && now >= requireNotNull(recoveryDeadlineMs)) {
                state = SessionLifecycleState.Failed
                transportGate = LifecycleTransportGate.Closed
                recoveryDeadlineMs = null
                reconnectRetryAtMs = null
                failure(SessionLifecycleError.RecoveryExpired)
            } else if (state == SessionLifecycleState.Reconnecting && reconnectRetryAtMs?.let { now >= it } == true) {
                reconnectRetryAtMs = null
                reconnectAttempts += 1
                LifecycleDecision.BeginReconnect(
                    nextGeneration(generation) ?: return failure(SessionLifecycleError.SessionGenerationExhausted),
                )
            } else {
                LifecycleDecision.Ignored
            }
        }
        val current = active ?: return failure(SessionLifecycleError.InvalidState)
        pendingHeartbeat?.let { pending ->
            if (now < pending.deadlineMs) return LifecycleDecision.Ignored
            pendingHeartbeat = null
            heartbeatMisses += 1
            if (heartbeatMisses < healthConfig.maxConsecutiveHeartbeatMisses) {
                return LifecycleDecision.HeartbeatMissed(
                    heartbeatMisses,
                )
            }
            return onActivePathUnavailable(now, SessionLifecycleError.PathLost)
        }
        val last = lastAuthenticatedReceiveAtMs ?: now
        if (now - last < healthConfig.idleProbeAfterMs) {
            return LifecycleDecision.Ignored
        }
        pendingHeartbeat = PendingHeartbeat(
            heartbeatId,
            current.pathId,
            now + healthConfig.heartbeatResponseTimeoutMs,
        )
        return LifecycleDecision.SendHeartbeat(heartbeatId, current.pathId)
    }

    fun onHeartbeatAck(heartbeatId: HeartbeatId, pathId: PathId, nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        val pending = pendingHeartbeat ?: return LifecycleDecision.Ignored
        if (pending.id != heartbeatId || pending.pathId != pathId || active?.pathId != pathId) {
            return LifecycleDecision.Ignored
        }
        pendingHeartbeat = null
        heartbeatMisses = 0
        lastAuthenticatedReceiveAtMs = now
        return LifecycleDecision.Active
    }

    /** Platform events are fast local hints, never peer authentication. */
    fun onPlatformPathLost(pathId: PathId, hard: Boolean, nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        val current = active
        if (current?.pathId == pathId) {
            active = current.copy(health = if (hard) LifecyclePathHealth.LocallyLost else LifecyclePathHealth.Suspect)
            return if (hard) {
                onActivePathUnavailable(
                    now,
                    SessionLifecycleError.PathLost,
                )
            } else {
                LifecycleDecision.PathSuspect(pathId)
            }
        }
        if (standby?.pathId == pathId) {
            standby = standby?.copy(health = LifecyclePathHealth.Lost)
            if (state == SessionLifecycleState.Active) state = SessionLifecycleState.Degraded
            return LifecycleDecision.StandbyLost(pathId)
        }
        return LifecycleDecision.Ignored
    }

    fun beginMigration(migrationId: PathMigrationId, nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        if (
            state !in setOf(SessionLifecycleState.Active, SessionLifecycleState.Degraded) ||
            migration != null
        ) {
            return failure(SessionLifecycleError.InvalidState)
        }
        val target = standby?.takeIf {
            it.health == LifecyclePathHealth.ValidatedStandby
        } ?: return failure(SessionLifecycleError.NoStandbyPath)
        state = SessionLifecycleState.MigratingPath
        transportGate = LifecycleTransportGate.Migrating
        migration = PendingMigration(
            migrationId,
            target,
            now + healthConfig.pathMigrationTimeoutMs,
        )
        return LifecycleDecision.ValidateStandby(migrationId, target.pathId, target.kind)
    }

    /** The Host is responder-only in V1; without a Client coordinator it proceeds to bounded recovery. */
    fun onMigrationCoordinatorUnavailable(nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        if (state !in setOf(SessionLifecycleState.Active, SessionLifecycleState.Degraded)) {
            return LifecycleDecision.Ignored
        }
        return enterRecovery(SessionLifecycleError.NoStandbyPath, now)
    }

    fun onMigrationValidationFailed(oldPathStillUsable: Boolean): LifecycleDecision {
        val current = migration ?: return LifecycleDecision.Ignored
        migration = null
        return if (oldPathStillUsable && active?.health != LifecyclePathHealth.LocallyLost) {
            state = if (standby?.health == LifecyclePathHealth.Lost) {
                SessionLifecycleState.Degraded
            } else {
                SessionLifecycleState.Active
            }
            transportGate = LifecycleTransportGate.ActiveTransport
            failure(SessionLifecycleError.PathValidationFailed)
        } else {
            enterRecovery(SessionLifecycleError.PathValidationFailed)
        }
    }

    fun onMigrationCommitted(
        migrationId: PathMigrationId,
        oldPathStillUsable: Boolean,
        nowMs: Long,
    ): LifecycleDecision {
        nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        val pending = migration ?: return failure(SessionLifecycleError.InvalidState)
        if (pending.id != migrationId) return failure(SessionLifecycleError.PathMigrationConflict)
        val old = active ?: return failure(SessionLifecycleError.InvalidState)
        active = pending.target.copy(health = LifecyclePathHealth.Active)
        standby = if (oldPathStillUsable && old.health != LifecyclePathHealth.LocallyLost) {
            old.copy(health = LifecyclePathHealth.ValidatedStandby)
        } else {
            null
        }
        migration = null
        heartbeatMisses = 0
        pendingHeartbeat = null
        state = SessionLifecycleState.Active
        transportGate = LifecycleTransportGate.ActiveTransport
        return LifecycleDecision.MigrationCommitted(
            active = requireNotNull(active),
            standby = standby,
        )
    }

    fun onMigrationTimeout(nowMs: Long, oldPathStillUsable: Boolean): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        val pending = migration ?: return LifecycleDecision.Ignored
        if (now < pending.deadlineMs) return LifecycleDecision.Ignored
        migration = null
        return if (oldPathStillUsable && active?.health != LifecyclePathHealth.LocallyLost) {
            state = SessionLifecycleState.Active
            transportGate = LifecycleTransportGate.ActiveTransport
            failure(SessionLifecycleError.PathMigrationTimeout)
        } else {
            enterRecovery(SessionLifecycleError.PathMigrationTimeout)
        }
    }

    fun beginReconnect(nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        if (state != SessionLifecycleState.Suspended || recoveryPolicy != SessionRecoveryPolicy.FastReconnect) {
            return failure(
                SessionLifecycleError.InvalidState,
            )
        }
        val deadline = recoveryDeadlineMs ?: return failure(SessionLifecycleError.RecoveryExpired)
        if (now >= deadline) {
            state = SessionLifecycleState.Failed
            transportGate = LifecycleTransportGate.Closed
            recoveryDeadlineMs = null
            return failure(SessionLifecycleError.RecoveryExpired)
        }
        val next = nextGeneration(generation) ?: run {
            state = SessionLifecycleState.Failed
            transportGate = LifecycleTransportGate.Closed
            recoveryDeadlineMs = null
            return failure(SessionLifecycleError.SessionGenerationExhausted)
        }
        state = SessionLifecycleState.Reconnecting
        reconnectAttempts = 1
        reconnectRetryAtMs = null
        return LifecycleDecision.BeginReconnect(next)
    }

    /** The controller calls this only after a bounded, fresh WNSH reconnect attempt fails. */
    fun onReconnectAttemptFailed(nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        if (state != SessionLifecycleState.Reconnecting) return failure(SessionLifecycleError.InvalidState)
        val deadline = recoveryDeadlineMs ?: return failure(SessionLifecycleError.RecoveryExpired)
        if (now >= deadline) {
            state = SessionLifecycleState.Failed
            transportGate = LifecycleTransportGate.Closed
            recoveryDeadlineMs = null
            return failure(SessionLifecycleError.RecoveryExpired)
        }
        if (reconnectAttempts >= healthConfig.maxReconnectAttempts) {
            state = SessionLifecycleState.Failed
            transportGate = LifecycleTransportGate.Closed
            recoveryDeadlineMs = null
            return failure(SessionLifecycleError.ReconnectHandshakeFailed)
        }
        val delay = healthConfig.reconnectBackoffMs[reconnectAttempts]
        reconnectRetryAtMs = (now + delay).coerceAtMost(deadline)
        return LifecycleDecision.ReconnectRetry(requireNotNull(reconnectRetryAtMs))
    }

    fun onReconnectPrepared(newGeneration: SessionGeneration, nowMs: Long): LifecycleDecision {
        val now = nowMs.validTime() ?: return failure(SessionLifecycleError.InvalidConfig)
        val next = nextGeneration(generation) ?: return failure(SessionLifecycleError.SessionGenerationExhausted)
        if (state != SessionLifecycleState.Reconnecting || newGeneration != next) {
            return failure(
                SessionLifecycleError.ReconnectGenerationMismatch,
            )
        }
        generation = newGeneration
        state = SessionLifecycleState.Prepared
        transportGate = LifecycleTransportGate.Suspended
        active = null
        standby = null
        lastAuthenticatedReceiveAtMs = now
        pendingHeartbeat = null
        heartbeatMisses = 0
        migration = null
        recoveryDeadlineMs = null
        reconnectRetryAtMs = null
        return LifecycleDecision.Reconnected(newGeneration)
    }

    fun beginDisconnect(): LifecycleDecision {
        if (state.isTerminal()) return LifecycleDecision.Ignored
        state = SessionLifecycleState.Disconnecting
        transportGate = LifecycleTransportGate.Closed
        pendingHeartbeat = null
        migration = null
        recoveryDeadlineMs = null
        reconnectRetryAtMs = null
        return LifecycleDecision.Disconnecting
    }

    fun finishDisconnect(): LifecycleDecision {
        state = SessionLifecycleState.Closed
        transportGate = LifecycleTransportGate.Closed
        return LifecycleDecision.Closed
    }

    fun snapshot(nowMs: Long): SessionLifecycleSnapshot {
        val now = nowMs.coerceAtLeast(0L)
        return SessionLifecycleSnapshot(
            sessionId, generation, state, transportGate, active?.pathId, active?.kind, standby?.pathId, standby?.kind,
            active?.health, standby?.health, lastAuthenticatedReceiveAtMs?.let { (now - it).coerceAtLeast(0L) },
            pendingHeartbeat?.id, heartbeatMisses, migration?.id,
            recoveryDeadlineMs?.let {
                (it - now).coerceAtLeast(
                    0L,
                )
            },
            reconnectAttempts, reconnectRetryAtMs?.let { (it - now).coerceAtLeast(0L) }, lastError,
        )
    }

    fun activePath(): LifecyclePath? = active
    fun standbyPath(): LifecyclePath? = standby
    fun currentGeneration(): SessionGeneration = generation

    /** The controller owns a single timer and schedules its next bounded control-plane wake here. */
    fun nextWakeAtMs(): Long? = buildList {
        lastAuthenticatedReceiveAtMs?.let { last -> add(last + healthConfig.idleProbeAfterMs) }
        pendingHeartbeat?.let { add(it.deadlineMs) }
        migration?.let { add(it.deadlineMs) }
        recoveryDeadlineMs?.let(::add)
        reconnectRetryAtMs?.let(::add)
    }.minOrNull()

    private fun onActivePathUnavailable(now: Long, error: SessionLifecycleError): LifecycleDecision {
        lastError = error
        if (standby?.health == LifecyclePathHealth.ValidatedStandby) return LifecycleDecision.BeginMigration
        return enterRecovery(error, now)
    }

    private fun enterRecovery(error: SessionLifecycleError, now: Long = 0L): LifecycleDecision {
        lastError = error
        pendingHeartbeat = null
        migration = null
        reconnectRetryAtMs = null
        transportGate = LifecycleTransportGate.Suspended
        return if (recoveryPolicy == SessionRecoveryPolicy.Disabled) {
            state = SessionLifecycleState.Failed
            LifecycleDecision.Terminal(SessionLifecycleError.RecoveryDisabled)
        } else {
            state = SessionLifecycleState.Suspended
            val base = if (now == 0L) (lastAuthenticatedReceiveAtMs ?: 0L) else now
            recoveryDeadlineMs = base + healthConfig.recoveryWindowMs
            LifecycleDecision.Suspended(recoveryDeadlineMs!!)
        }
    }

    private fun failure(error: SessionLifecycleError): LifecycleDecision {
        lastError = error
        return LifecycleDecision.Error(error)
    }

    private fun Long.validTime(): Long? = takeIf { it >= 0L }
    private fun nextGeneration(current: SessionGeneration): SessionGeneration? =
        current.value.takeIf { it != UInt.MAX_VALUE }?.let { SessionGeneration.from(it + 1u) }

    data class LifecyclePath(val pathId: PathId, val kind: NetworkPathKind, val health: LifecyclePathHealth)

    private data class PendingHeartbeat(val id: HeartbeatId, val pathId: PathId, val deadlineMs: Long)
    private data class PendingMigration(val id: PathMigrationId, val target: LifecyclePath, val deadlineMs: Long)
}

sealed interface LifecycleDecision {
    data object Active : LifecycleDecision
    data object Ignored : LifecycleDecision
    data object BeginMigration : LifecycleDecision
    data object Disconnecting : LifecycleDecision
    data object Closed : LifecycleDecision
    data class SendHeartbeat(val heartbeatId: HeartbeatId, val activePathId: PathId) : LifecycleDecision
    data class HeartbeatMissed(val misses: Int) : LifecycleDecision
    data class PathSuspect(val pathId: PathId) : LifecycleDecision
    data class StandbyLost(val pathId: PathId) : LifecycleDecision
    data class ValidateStandby(
        val migrationId: PathMigrationId,
        val pathId: PathId,
        val kind: NetworkPathKind,
    ) : LifecycleDecision
    data class MigrationCommitted(
        val active: SessionLifecycleEngine.LifecyclePath,
        val standby: SessionLifecycleEngine.LifecyclePath?,
    ) : LifecycleDecision
    data class Suspended(val recoveryDeadlineMs: Long) : LifecycleDecision
    data class BeginReconnect(val nextGeneration: SessionGeneration) : LifecycleDecision
    data class ReconnectRetry(val dueAtMs: Long) : LifecycleDecision
    data class Reconnected(val generation: SessionGeneration) : LifecycleDecision
    data class Terminal(val error: SessionLifecycleError) : LifecycleDecision
    data class Error(val error: SessionLifecycleError) : LifecycleDecision
}
