@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.lifecycle

import io.warpnect.diagnostics.DiagnosticReason
import io.warpnect.diagnostics.DiagnosticSessionState
import io.warpnect.diagnostics.SessionLifecycleDiagnosticEvents
import io.warpnect.session.PathId
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionRole
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionPathPlan
import io.warpnect.telemetry.SessionLifecycleTelemetry
import io.warpnect.telemetry.SessionPathTelemetry
import io.warpnect.telemetry.TelemetryScope
import java.security.SecureRandom

fun interface SessionLifecycleMonotonicClock {
    fun nowMs(): Long
}

object SystemSessionLifecycleMonotonicClock : SessionLifecycleMonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

fun interface SessionLifecycleTimer {
    fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable
}

/** Future RFC-005I participants clear/reset state; none may ask lifecycle to buffer real-time data. */
interface SessionContinuityParticipant {
    fun onPathMigrationStarting() = Unit
    fun onPathMigrationCommitted() = Unit
    fun onSessionSuspended() = Unit
    fun onSessionReconnected() = Unit
    fun onSessionClosing() = Unit

    /** Target-side input integration clears AllSlots/ResetState; no new Input wire format is used. */
    fun onInputSafetyReset(reason: LifecycleInputSafetyResetReason) = Unit
}

enum class LifecycleInputSafetyResetReason {
    PathUnavailable,
    SessionClosing,
}

data class LifecyclePathBinding(
    val plan: SessionPathPlan,
    val remoteControlEndpoint: HandshakeTransportEndpoint,
) {
    fun isValid(): Boolean = plan.isValid()
}

interface SessionLifecyclePathProvider {
    fun bindingFor(pathId: PathId): LifecyclePathBinding?
}

interface ChannelMigrationPreparation : AutoCloseable {
    val entries: List<PathMigrationEntry>
    override fun close()
}

/**
 * Platform/control adapter. It allocates target-path sockets before ports are sent, and commits
 * endpoint replacement without rebuilding the RFC-005E runtime or any Channel context.
 */
interface SessionLifecycleMigrationAdapter : AutoCloseable {
    fun armCandidateWindow(binding: LifecyclePathBinding, migrationId: PathMigrationId, timeoutMs: Long): Boolean
    fun disarmCandidateWindow(migrationId: PathMigrationId)
    fun sendCandidate(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean
    fun prepareChannels(binding: LifecyclePathBinding, channels: List<SessionChannelKind>): ChannelMigrationPreparation?
    fun commit(
        binding: LifecyclePathBinding,
        preparation: ChannelMigrationPreparation,
        remoteEntries: List<PathMigrationEntry>,
    ): SessionLifecycleError

    override fun close() = Unit
}

fun interface SessionLifecycleReconnectDelegate {
    fun onReconnectRequired(record: RecoverableSessionRecord, nextGeneration: io.warpnect.session.SessionGeneration)
}

data class RecoverableSessionRecord(
    val sessionId: io.warpnect.session.SessionId,
    val currentGeneration: io.warpnect.session.SessionGeneration,
    val expectedPeerDeviceId: io.warpnect.session.DeviceId,
    val localRole: SessionRole,
    val remoteRole: SessionRole,
    val intent: SessionRecoveryIntent?,
    val previousProfileHash: ByteArray,
    val previousPreparedConfigurationHash: ByteArray,
    val recoveryDeadlineMonotonicMs: Long,
    val reconnectAttemptCount: Int,
)

/**
 * RFC-005H controller. Call it only from the serialized SessionLifecycle control context. Native
 * WNSD remains the authentication boundary; this controller only sees decrypted SessionControl.
 */
class SessionLifecycleController(
    private val bootstrap: PreparedSessionBootstrap,
    private val pathProvider: SessionLifecyclePathProvider,
    private val migrationAdapter: SessionLifecycleMigrationAdapter,
    private val recoveryDelegate: SessionLifecycleReconnectDelegate? = null,
    private val recoveryIntent: SessionRecoveryIntent? = null,
    private val capacityOwner: SessionLifecycleCapacityOwner? = null,
    private val continuityParticipants: List<SessionContinuityParticipant> = emptyList(),
    private val healthConfig: LifecycleHealthConfig = LifecycleHealthConfig(),
    private val recoveryPolicy: SessionRecoveryPolicy = SessionRecoveryPolicy.FastReconnect,
    private val clock: SessionLifecycleMonotonicClock = SystemSessionLifecycleMonotonicClock,
    private val timer: SessionLifecycleTimer? = null,
    private val lifecycleIdGenerator: LifecycleMessageIdGenerator = SecureLifecycleMessageIdGenerator,
    private val heartbeatIdGenerator: HeartbeatIdGenerator = SecureHeartbeatIdGenerator,
    private val migrationIdGenerator: PathMigrationIdGenerator = SecurePathMigrationIdGenerator,
    private val telemetry: SessionLifecycleTelemetry? = null,
    private val pathTelemetry: Map<PathId, SessionPathTelemetry> = emptyMap(),
    private val diagnosticEvents: SessionLifecycleDiagnosticEvents? = null,
    private val pathDiagnosticScopes: Map<PathId, TelemetryScope.Path> = emptyMap(),
) : AutoCloseable {
    private val lock = Any()
    private val engine = SessionLifecycleEngine(bootstrap.sessionId, bootstrap.generation, healthConfig, recoveryPolicy)
    private val control: SecureSessionControlTransport = bootstrap.secureSessionControl
    private val channelKinds = bootstrap.channels.map { it.descriptor.kind }
    private val channelIds = bootstrap.channels.map { it.descriptor.channelId }.toSet()
    private val preparedConfigurationHash = bootstrap.preparedConfigurationHash.copyOf()
    private var candidate: CandidateMigration? = null
    private var activeMigration: ActiveMigration? = null
    private var closeDeadlineMs: Long? = null
    private var disconnectNotice: ByteArray? = null
    private var disconnectRetries = 0
    private var disconnectRetryAtMs: Long? = null
    private var observedRuntimeAuthenticatedReceiveUs = 0L
    private var timerHandle: AutoCloseable? = null
    private var closed = false
    private var closingNotified = false
    private var inputSafetyResetInvoked = false
    private var lastError = SessionLifecycleError.None

    init {
        require(healthConfig.isValid()) { "Invalid lifecycle health configuration" }
        require(
            preparedConfigurationHash.size == SessionLifecycleProtocol.HASH_BYTES,
        ) { "Invalid prepared configuration hash" }
    }

    /** Takes ownership from RFC-005G while all resources are still stopped. */
    fun start(): SessionLifecycleError = synchronized(lock) {
        if (closed) return@synchronized SessionLifecycleError.Closed
        if (bootstrap.localRole == SessionRole.Host && capacityOwner == null) {
            return@synchronized record(SessionLifecycleError.InvalidState)
        }
        val now = now()
        if (!bootstrap.transferToLifecycle(now)) return@synchronized record(SessionLifecycleError.InvalidState)
        if (bootstrap.localRole == SessionRole.Host && capacityOwner?.promote(bootstrap.sessionId, bootstrap.remoteDeviceId, bootstrap.generation) == false) {
            return@synchronized record(SessionLifecycleError.InvalidState)
        }
        control.setPayloadListener(::receiveActivePayload)
        val result = engine.consumePrepared(
            bootstrap.activePath.pathId,
            bootstrap.activePath.kind,
            bootstrap.standbyPath?.pathId,
            bootstrap.standbyPath?.kind,
            now,
        )
        if (bootstrap.generation != SessionGeneration.Initial) telemetry?.reconnectSucceeded?.increment()
        updatePathTelemetry()
        handleDecision(result)
        diagnosticEvents?.stateChanged(DiagnosticSessionState.Prepared, DiagnosticSessionState.Active)
        diagnosticEvents?.running()
        armNextWake()
        SessionLifecycleError.None
    }

    /** Invoked by the 005E receive integration only after a record authenticated successfully. */
    fun onAuthenticatedReceive(nowMs: Long = now()) = synchronized(lock) {
        if (!closed) handleDecision(engine.onAuthenticatedReceive(nowMs))
    }

    fun onPlatformPathLoss(pathId: PathId, hard: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        pathTelemetry[pathId]?.let { if (hard) it.platformLost.increment() else it.platformLosing.increment() }
        pathDiagnosticScopes[pathId]?.let { scope ->
            if (hard) {
                diagnosticEvents?.platformPathLost(
                    pathId,
                    scope,
                )
            } else {
                diagnosticEvents?.platformPathLosing(pathId, scope)
            }
        }
        handleDecision(engine.onPlatformPathLost(pathId, hard, now()))
        updatePathTelemetry()
        armNextWake()
    }

    /** Local platform availability is diagnostic only; RFC-005H validation remains authoritative. */
    fun onPlatformPathAvailable(pathId: PathId) = synchronized(lock) {
        if (!closed) pathTelemetry[pathId]?.platformAvailable?.increment()
    }

    /** Externally driven timer tick; no busy polling is introduced. */
    fun advance() = synchronized(lock) {
        if (closed) return@synchronized
        val now = now()
        syncRuntimeLiveness()
        activeMigration?.let { transaction ->
            handleDecision(engine.onMigrationTimeout(now, transaction.oldPathStillUsable))
        }
        if (engine.snapshot(now).state == SessionLifecycleState.Disconnecting) {
            if (closeDeadlineMs?.let { now >= it } == true) {
                finishClose()
                return@synchronized
            }
            if (disconnectRetries == 0 && disconnectRetryAtMs?.let { now >= it } == true) {
                disconnectNotice?.let(::sendActive)
                disconnectRetries = 1
                disconnectRetryAtMs = null
            }
            armNextWake()
            return@synchronized
        }
        handleDecision(engine.advance(now, heartbeatIdGenerator.next()))
        armNextWake()
    }

    /** Raw candidate datagrams are accepted only while the narrow WNSL candidate state is armed. */
    fun receiveCandidate(sourceEndpoint: HandshakeTransportEndpoint, protectedDatagram: ByteArray, nowUs: Long) =
        synchronized(lock) {
            if (closed) return@synchronized
            val existing = candidate
            val binding = existing?.binding ?: responderCandidateBinding(sourceEndpoint) ?: return@synchronized
            if (sourceEndpoint != binding.remoteControlEndpoint) return@synchronized
            if (existing != null && nowUs / 1_000L > existing.deadlineMs) {
                abortMigration(SessionLifecycleError.PathMigrationTimeout, existing.oldPathStillUsable)
                return@synchronized
            }
            val unprotected = control.unprotectCandidate(sourceEndpoint, protectedDatagram, nowUs)
            if (!unprotected.isSuccess) return@synchronized
            val decoded = SessionLifecycleCodec.decode(requireNotNull(unprotected.payload)) ?: run {
                record(SessionLifecycleError.MalformedMessage)
                return@synchronized
            }
            if (existing == null) {
                val challenge = decoded.message as? SessionLifecycleMessage.PathChallenge ?: return@synchronized
                val standby = engine.standbyPath()
                if (
                    bootstrap.localRole != SessionRole.Host ||
                    standby?.pathId != challenge.targetPathId ||
                    standby.kind != challenge.targetPathKind ||
                    challenge.challenge.size != SessionLifecycleProtocol.CHALLENGE_BYTES ||
                    !migrationAdapter.armCandidateWindow(
                        binding,
                        challenge.migrationId,
                        healthConfig.pathCandidateValidationTimeoutMs,
                    )
                ) {
                    record(SessionLifecycleError.PathValidationFailed)
                    return@synchronized
                }
                candidate = CandidateMigration(
                    challenge.migrationId,
                    binding,
                    challenge.challenge.copyOf(),
                    nowUs / 1_000L + healthConfig.pathCandidateValidationTimeoutMs,
                    oldPathStillUsable = engine.activePath()?.health != LifecyclePathHealth.LocallyLost,
                )
                continuityParticipants.forEach(SessionContinuityParticipant::onPathMigrationStarting)
            }
            handleDecision(engine.onAuthenticatedReceive(nowUs / 1_000L))
            receiveDecoded(decoded, candidatePath = true)
        }

    fun snapshot(): SessionLifecycleSnapshot = synchronized(lock) { engine.snapshot(now()) }

    fun gracefulDisconnect(reason: DisconnectReason): SessionLifecycleError = synchronized(lock) {
        if (closed) return@synchronized SessionLifecycleError.Closed
        val state = engine.snapshot(now()).state
        if (state == SessionLifecycleState.Disconnecting) return@synchronized SessionLifecycleError.None
        if (state == SessionLifecycleState.Reconnecting) {
            telemetry?.reconnectCancelled?.increment()
            diagnosticEvents?.reconnectCancelled(DiagnosticReason.UserRequested)
        }
        telemetry?.disconnectLocal?.increment()
        diagnosticEvents?.disconnectLocal(reason.toDiagnosticReason())
        handleDecision(engine.beginDisconnect())
        notifySessionClosing()
        invokeInputSafetyReset(LifecycleInputSafetyResetReason.SessionClosing)
        val active = engine.activePath()?.pathId ?: return@synchronized finishClose()
        val notice = SessionLifecycleMessage.DisconnectNotice(
            header(SessionLifecycleMessageType.DisconnectNotice),
            reason,
            engine.currentGeneration(),
            active,
        )
        val bytes = SessionLifecycleCodec.encode(notice) ?: return@synchronized record(SessionLifecycleError.MalformedMessage)
        disconnectNotice = bytes
        closeDeadlineMs = now() + healthConfig.gracefulDisconnectBudgetMs
        disconnectRetryAtMs = now() + (healthConfig.gracefulDisconnectBudgetMs / 2).coerceAtLeast(1L)
        sendActive(bytes)
        armNextWake()
        SessionLifecycleError.None
    }

    /** Reconnect completion is supplied only after fresh 005D -> 005E -> 005F -> 005G succeeds. */
    fun onFreshGenerationPrepared(fresh: PreparedSessionBootstrap): SessionLifecycleError = synchronized(lock) {
        if (closed || engine.snapshot(now()).state != SessionLifecycleState.Reconnecting || fresh.sessionId != bootstrap.sessionId) {
            return@synchronized record(SessionLifecycleError.InvalidState)
        }
        val decision = engine.onReconnectPrepared(fresh.generation, now())
        if (decision is LifecycleDecision.Error) return@synchronized record(decision.error)
        diagnosticEvents?.reconnectSucceeded(fresh.generation)
        // The old generation is terminal. The caller owns the new bootstrap and creates a new controller.
        continuityParticipants.forEach(SessionContinuityParticipant::onSessionReconnected)
        capacityOwner?.handoffToFreshGeneration()
        finishClose(notifyContinuityClosing = false)
        SessionLifecycleError.None
    }

    /** Schedules one of the bounded fresh-WNSH reconnect attempts; it never retries old keys. */
    fun onReconnectAttemptFailed(): SessionLifecycleError = synchronized(lock) {
        if (closed) return@synchronized SessionLifecycleError.Closed
        val decision = engine.onReconnectAttemptFailed(now())
        if (decision is LifecycleDecision.Error) {
            if (decision.error == SessionLifecycleError.RecoveryExpired) telemetry?.reconnectExpired?.increment()
            if (decision.error == SessionLifecycleError.RecoveryExpired) diagnosticEvents?.reconnectExpired()
            record(decision.error)
            if (engine.snapshot(now()).state == SessionLifecycleState.Failed) finishClose(markClosed = false)
        } else {
            if (decision is LifecycleDecision.ReconnectRetry) {
                telemetry?.reconnectAttemptFailed?.increment()
                diagnosticEvents?.reconnectAttemptFailed(
                    engine.snapshot(now()).reconnectAttemptCount,
                    DiagnosticReason.Timeout,
                )
            }
            handleDecision(decision)
            armNextWake()
        }
        SessionLifecycleError.None
    }

    override fun close() {
        synchronized(lock) { finishClose() }
    }

    private fun receiveActivePayload(payload: ByteArray) = synchronized(lock) {
        if (closed) return@synchronized
        onAuthenticatedReceive(now())
        SessionLifecycleCodec.decode(payload)?.let { receiveDecoded(it, candidatePath = false) }
            ?: record(SessionLifecycleError.MalformedMessage)
    }

    private fun receiveDecoded(decoded: DecodedSessionLifecyclePacket, candidatePath: Boolean) {
        val message = decoded.message
        when (message) {
            is SessionLifecycleMessage.Heartbeat -> {
                if (!candidatePath && message.activePathId == engine.activePath()?.pathId) {
                    sendMessage(
                        SessionLifecycleMessage.HeartbeatAck(
                            header(SessionLifecycleMessageType.HeartbeatAck),
                            message.heartbeatId,
                            message.activePathId,
                        ),
                        candidatePath = false,
                    )
                }
            }
            is SessionLifecycleMessage.HeartbeatAck -> if (!candidatePath) {
                val decision = engine.onHeartbeatAck(message.heartbeatId, message.activePathId, now())
                if (decision is LifecycleDecision.Active) telemetry?.heartbeatAckReceived?.increment()
                handleDecision(decision)
            }
            is SessionLifecycleMessage.PathChallenge -> if (candidatePath) handlePathChallenge(message)
            is SessionLifecycleMessage.PathResponse -> if (candidatePath) handlePathResponse(message)
            is SessionLifecycleMessage.PathMigrationPrepare -> if (candidatePath) handlePrepare(message)
            is SessionLifecycleMessage.PathMigrationReady -> if (candidatePath) handleReady(message)
            is SessionLifecycleMessage.PathMigrationCommit -> if (candidatePath) handleCommit(message)
            is SessionLifecycleMessage.PathMigrationAck -> if (!candidatePath) handleAck(message)
            is SessionLifecycleMessage.DisconnectNotice -> if (!candidatePath) handleDisconnectNotice(message)
            is SessionLifecycleMessage.DisconnectAck -> if (!candidatePath) finishClose()
        }
    }

    private fun handlePathChallenge(message: SessionLifecycleMessage.PathChallenge) {
        val standby = engine.standbyPath()
        val existing = candidate
        if (
            bootstrap.localRole != SessionRole.Host ||
            standby?.pathId != message.targetPathId ||
            standby.kind != message.targetPathKind ||
            existing?.id != message.migrationId ||
            existing?.binding?.plan?.pathId != message.targetPathId ||
            existing?.challenge?.contentEquals(message.challenge) != true ||
            message.challenge.size != SessionLifecycleProtocol.CHALLENGE_BYTES
        ) {
            record(SessionLifecycleError.PathValidationFailed)
            return
        }
        sendMessage(
            SessionLifecycleMessage.PathResponse(
                header(SessionLifecycleMessageType.PathResponse),
                message.migrationId,
                message.targetPathId,
                message.targetPathKind,
                message.challenge.copyOf(),
            ),
            candidatePath = true,
        )
    }

    private fun handlePathResponse(message: SessionLifecycleMessage.PathResponse) {
        val transaction = candidate ?: return
        if (transaction.id != message.migrationId || transaction.binding.plan.pathId != message.targetPathId ||
            transaction.binding.plan.kind != message.targetPathKind || !transaction.challenge.contentEquals(message.challenge)
        ) {
            record(SessionLifecycleError.PathValidationFailed)
            return
        }
        val preparation = migrationAdapter.prepareChannels(transaction.binding, channelKinds) ?: run {
            abortMigration(SessionLifecycleError.MigrationEndpointAllocationFailed, transaction.oldPathStillUsable)
            return
        }
        if (!preparation.entries.matchesChannelSet()) {
            preparation.close()
            abortMigration(SessionLifecycleError.MigrationEndpointAllocationFailed, transaction.oldPathStillUsable)
            return
        }
        val prepare = SessionLifecycleMessage.PathMigrationPrepare(
            header(SessionLifecycleMessageType.PathMigrationPrepare),
            transaction.id,
            transaction.binding.plan.pathId,
            preparedConfigurationHash.copyOf(),
            preparation.entries,
        )
        activeMigration = ActiveMigration(transaction, preparation, oldPathStillUsable = transaction.oldPathStillUsable, prepare = prepare)
        sendMessage(prepare, candidatePath = true)
    }

    private fun handlePrepare(message: SessionLifecycleMessage.PathMigrationPrepare) {
        if (!message.preparedConfigurationHash.contentEquals(preparedConfigurationHash) || !message.entries.matchesChannelSet()) {
            record(SessionLifecycleError.PathMigrationConflict)
            return
        }
        val existing = candidate
        if (existing?.id != message.migrationId || existing.binding.plan.pathId != message.targetPathId) {
            record(SessionLifecycleError.PathMigrationConflict)
            return
        }
        val transition = engine.beginMigration(message.migrationId, now())
        if (transition !is LifecycleDecision.ValidateStandby) {
            record(SessionLifecycleError.InvalidState)
            return
        }
        val preparation = migrationAdapter.prepareChannels(existing.binding, channelKinds) ?: run {
            abortMigration(SessionLifecycleError.MigrationEndpointAllocationFailed, true)
            return
        }
        if (!preparation.entries.matchesChannelSet()) {
            preparation.close()
            abortMigration(SessionLifecycleError.MigrationEndpointAllocationFailed, true)
            return
        }
        val ready = SessionLifecycleMessage.PathMigrationReady(
            header(SessionLifecycleMessageType.PathMigrationReady),
            message.migrationId,
            message.targetPathId,
            preparedConfigurationHash.copyOf(),
            preparation.entries,
        )
        activeMigration = ActiveMigration(
            existing,
            preparation,
            oldPathStillUsable = true,
            peerEntries = message.entries,
            prepare = message,
            ready = ready,
        )
        sendMessage(ready, candidatePath = true)
    }

    private fun handleReady(message: SessionLifecycleMessage.PathMigrationReady) {
        val transaction = activeMigration ?: return
        if (transaction.candidate.id != message.migrationId || transaction.candidate.binding.plan.pathId != message.targetPathId ||
            !message.preparedConfigurationHash.contentEquals(preparedConfigurationHash) || !message.entries.matchesChannelSet()
        ) {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        val prepare = transaction.prepare ?: run {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        val planHash = SessionLifecycleCodec.migrationPlanHash(prepare, message) ?: run {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        transaction.planHash = planHash
        transaction.peerEntries = message.entries
        sendMessage(
            SessionLifecycleMessage.PathMigrationCommit(
                header(SessionLifecycleMessageType.PathMigrationCommit),
                transaction.candidate.id,
                transaction.candidate.binding.plan.pathId,
                planHash,
            ),
            candidatePath = true,
        )
        val committed = migrationAdapter.commit(transaction.candidate.binding, transaction.preparation, message.entries)
        if (committed != SessionLifecycleError.None) {
            abortMigration(committed, transaction.oldPathStillUsable)
        }
    }

    private fun handleCommit(message: SessionLifecycleMessage.PathMigrationCommit) {
        val transaction = activeMigration ?: return
        if (transaction.candidate.id != message.migrationId || transaction.candidate.binding.plan.pathId != message.targetPathId ||
            transaction.peerEntries == null
        ) {
            record(SessionLifecycleError.PathMigrationConflict)
            return
        }
        val prepare = transaction.prepare ?: run {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        val ready = transaction.ready ?: run {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        val expected = SessionLifecycleCodec.migrationPlanHash(prepare, ready)
        if (expected == null || !expected.contentEquals(message.migrationPlanHash)) {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        val committed = migrationAdapter.commit(
            transaction.candidate.binding,
            transaction.preparation,
            requireNotNull(transaction.peerEntries),
        )
        if (committed != SessionLifecycleError.None) {
            abortMigration(committed, transaction.oldPathStillUsable)
            return
        }
        handleDecision(engine.onMigrationCommitted(message.migrationId, transaction.oldPathStillUsable, now()))
        migrationAdapter.disarmCandidateWindow(message.migrationId)
        sendMessage(
            SessionLifecycleMessage.PathMigrationAck(
                header(SessionLifecycleMessageType.PathMigrationAck),
                message.migrationId,
                message.targetPathId,
                message.migrationPlanHash,
            ),
            candidatePath = false,
        )
        transaction.preparation.close()
        activeMigration = null
        candidate = null
    }

    private fun handleAck(message: SessionLifecycleMessage.PathMigrationAck) {
        val transaction = activeMigration ?: return
        if (transaction.candidate.id != message.migrationId || transaction.planHash?.contentEquals(message.migrationPlanHash) != true) {
            abortMigration(SessionLifecycleError.PathMigrationConflict, transaction.oldPathStillUsable)
            return
        }
        handleDecision(engine.onMigrationCommitted(message.migrationId, transaction.oldPathStillUsable, now()))
        migrationAdapter.disarmCandidateWindow(message.migrationId)
        transaction.preparation.close()
        activeMigration = null
        candidate = null
    }

    private fun handleDisconnectNotice(message: SessionLifecycleMessage.DisconnectNotice) {
        if (message.sessionGeneration != engine.currentGeneration() || message.activePathId != engine.activePath()?.pathId) return
        telemetry?.disconnectRemote?.increment()
        diagnosticEvents?.disconnectRemote(message.reason.toDiagnosticReason())
        handleDecision(engine.beginDisconnect())
        notifySessionClosing()
        invokeInputSafetyReset(LifecycleInputSafetyResetReason.SessionClosing)
        sendMessage(
            SessionLifecycleMessage.DisconnectAck(
                header(SessionLifecycleMessageType.DisconnectAck),
                message.reason,
                message.sessionGeneration,
                message.activePathId,
            ),
            candidatePath = false,
        )
        finishClose()
    }

    private fun handleDecision(decision: LifecycleDecision) {
        when (decision) {
            is LifecycleDecision.SendHeartbeat -> {
                if (sendMessage(
                        SessionLifecycleMessage.Heartbeat(
                            header(SessionLifecycleMessageType.Heartbeat),
                            decision.heartbeatId,
                            decision.activePathId,
                        ),
                        candidatePath = false,
                    )
                ) {
                    telemetry?.heartbeatSent?.increment()
                }
            }
            is LifecycleDecision.HeartbeatMissed -> telemetry?.heartbeatMiss?.increment()
            LifecycleDecision.BeginMigration -> startMigration()
            is LifecycleDecision.ValidateStandby -> {
                telemetry?.migrationStarted?.increment()
                pathTelemetry[decision.pathId]?.validationStarted?.increment()
                diagnosticEvents?.migrationStarted(engine.activePath()?.pathId, decision.pathId)
                startCandidateValidation(decision)
            }
            is LifecycleDecision.MigrationCommitted -> {
                telemetry?.migrationSucceeded?.increment()
                pathTelemetry[decision.active.pathId]?.validationSucceeded?.increment()
                diagnosticEvents?.migrationSucceeded(null, decision.active.pathId)
                updatePathTelemetry()
                continuityParticipants.forEach(SessionContinuityParticipant::onPathMigrationCommitted)
            }
            is LifecycleDecision.Suspended -> {
                telemetry?.suspended?.increment()
                diagnosticEvents?.stateChanged(DiagnosticSessionState.Active, DiagnosticSessionState.Suspended)
                diagnosticEvents?.suspended(DiagnosticReason.NetworkLost)
                invokeInputSafetyReset(LifecycleInputSafetyResetReason.PathUnavailable)
                continuityParticipants.forEach(SessionContinuityParticipant::onSessionSuspended)
                beginReconnect(decision.recoveryDeadlineMs)
            }
            is LifecycleDecision.BeginReconnect -> {
                telemetry?.reconnectAttempt?.increment()
                diagnosticEvents?.reconnectStarted(decision.nextGeneration)
                recoveryDelegate?.onReconnectRequired(recoveryRecord(), decision.nextGeneration)
            }
            is LifecycleDecision.ReconnectRetry -> Unit
            is LifecycleDecision.Terminal -> {
                record(decision.error)
                finishClose(markClosed = false)
            }
            is LifecycleDecision.Error -> {
                record(decision.error)
                if (engine.snapshot(now()).state == SessionLifecycleState.Failed) finishClose(markClosed = false)
            }
            else -> Unit
        }
    }

    private fun startMigration() {
        if (bootstrap.localRole != SessionRole.Client) {
            handleDecision(engine.onMigrationCoordinatorUnavailable(now()))
            return
        }
        handleDecision(engine.beginMigration(migrationIdGenerator.next(), now()))
    }

    private fun startCandidateValidation(decision: LifecycleDecision.ValidateStandby) {
        val binding = pathProvider.bindingFor(decision.pathId) ?: run {
            abortMigration(SessionLifecycleError.PathValidationFailed, oldPathStillUsable = false)
            return
        }
        if (!migrationAdapter.armCandidateWindow(
                binding,
                decision.migrationId,
                healthConfig.pathCandidateValidationTimeoutMs,
            )
        ) {
            abortMigration(SessionLifecycleError.PathValidationFailed, oldPathStillUsable = false)
            return
        }
        val challenge = ByteArray(SessionLifecycleProtocol.CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        candidate = CandidateMigration(decision.migrationId, binding, challenge, now() + healthConfig.pathCandidateValidationTimeoutMs, oldPathStillUsable = false)
        continuityParticipants.forEach(SessionContinuityParticipant::onPathMigrationStarting)
        sendMessage(
            SessionLifecycleMessage.PathChallenge(
                header(SessionLifecycleMessageType.PathChallenge),
                decision.migrationId,
                decision.pathId,
                decision.kind,
                challenge,
            ),
            candidatePath = true,
        )
    }

    private fun beginReconnect(deadline: Long) {
        if (bootstrap.localRole == SessionRole.Host &&
            capacityOwner?.beginRecovery(bootstrap.sessionId, bootstrap.remoteDeviceId, engine.currentGeneration(), healthConfig.recoveryWindowMs) == false
        ) {
            record(SessionLifecycleError.RecoveryLeaseConflict)
            finishClose()
            return
        }
        val decision = engine.beginReconnect(now())
        if (decision is LifecycleDecision.BeginReconnect) {
            // A reconnect is a new security generation; old contexts and all old path resources die first.
            diagnosticEvents?.stateChanged(DiagnosticSessionState.Suspended, DiagnosticSessionState.Reconnecting)
            bootstrap.close()
            recoveryDelegate?.onReconnectRequired(recoveryRecord(deadline), decision.nextGeneration)
        } else {
            handleDecision(decision)
        }
    }

    private fun recoveryRecord(
        deadline: Long = engine.snapshot(now()).recoveryDeadlineRemainingMs?.let {
            now() + it
        } ?: now(),
    ): RecoverableSessionRecord = RecoverableSessionRecord(
        bootstrap.sessionId, engine.currentGeneration(), bootstrap.remoteDeviceId, bootstrap.localRole, bootstrap.remoteRole, recoveryIntent,
        bootstrap.profileHash.copyOf(), preparedConfigurationHash.copyOf(), deadline,
        engine.snapshot(
            now(),
        ).reconnectAttemptCount,
    )

    private fun sendMessage(message: SessionLifecycleMessage, candidatePath: Boolean): Boolean {
        val bytes = SessionLifecycleCodec.encode(message) ?: run {
            record(SessionLifecycleError.MalformedMessage)
            return false
        }
        return if (candidatePath) {
            val transaction = candidate ?: return false
            val protected = control.protectCandidate(bytes)
            if (!protected.isSuccess || !migrationAdapter.sendCandidate(transaction.binding, requireNotNull(protected.protectedDatagram))) {
                record(SessionLifecycleError.SecureControlFailure)
                false
            } else {
                true
            }
        } else {
            sendActive(bytes)
        }
    }

    private fun sendActive(bytes: ByteArray): Boolean = if (control.send(bytes).isSuccess) {
        true
    } else {
        record(SessionLifecycleError.SecureControlFailure)
        false
    }

    private fun abortMigration(error: SessionLifecycleError, oldPathStillUsable: Boolean) {
        val candidatePathId = activeMigration?.candidate?.binding?.plan?.pathId ?: candidate?.binding?.plan?.pathId
        activeMigration?.preparation?.close()
        activeMigration = null
        candidate?.let { migrationAdapter.disarmCandidateWindow(it.id) }
        candidate = null
        telemetry?.migrationFailed?.increment()
        pathTelemetry[candidatePathId]?.validationFailed?.increment()
        diagnosticEvents?.migrationFailed(null, candidatePathId, error.toDiagnosticReason())
        diagnosticEvents?.pathValidationFailed(
            candidatePathId,
            pathDiagnosticScopes[candidatePathId],
            error.toDiagnosticReason(),
        )
        handleDecision(engine.onMigrationValidationFailed(oldPathStillUsable))
        updatePathTelemetry()
        record(error)
    }

    private fun finishClose(
        markClosed: Boolean = true,
        notifyContinuityClosing: Boolean = true,
    ): SessionLifecycleError {
        if (closed) return SessionLifecycleError.None
        closed = true
        if (notifyContinuityClosing) notifySessionClosing()
        invokeInputSafetyReset(LifecycleInputSafetyResetReason.SessionClosing)
        timerHandle?.close()
        timerHandle = null
        activeMigration?.preparation?.close()
        activeMigration = null
        candidate?.let { migrationAdapter.disarmCandidateWindow(it.id) }
        candidate = null
        migrationAdapter.close()
        control.setPayloadListener(null)
        if (markClosed) engine.finishDisconnect()
        bootstrap.close()
        capacityOwner?.close()
        telemetry?.close()
        pathTelemetry.values.forEach(SessionPathTelemetry::close)
        return SessionLifecycleError.None
    }

    private fun armNextWake() {
        timerHandle?.close()
        if (closed) return
        val now = now()
        val fallbackDelay = healthConfig.heartbeatResponseTimeoutMs.coerceAtMost(healthConfig.idleProbeAfterMs)
        val lifecycleDelay = engine.nextWakeAtMs()?.let { (it - now).coerceAtLeast(1L) } ?: fallbackDelay
        val disconnectDelay = disconnectRetryAtMs?.let { (it - now).coerceAtLeast(1L) }
        timer?.schedule(listOfNotNull(lifecycleDelay, disconnectDelay).minOrNull() ?: fallbackDelay) {
            advance()
        }?.let { timerHandle = it }
    }

    private fun syncRuntimeLiveness() {
        val receivedUs = bootstrap.protectionRuntime.lastAuthenticatedReceiveMonotonicUs()
        if (receivedUs <= observedRuntimeAuthenticatedReceiveUs) return
        observedRuntimeAuthenticatedReceiveUs = receivedUs
        handleDecision(engine.onAuthenticatedReceive(receivedUs / 1_000L))
    }

    private fun updatePathTelemetry() {
        val snapshot = engine.snapshot(now())
        pathTelemetry.forEach { (pathId, telemetry) ->
            telemetry.active.set(if (snapshot.activePathId == pathId) 1 else 0)
            telemetry.validated.set(
                if (snapshot.activePathId == pathId || snapshot.standbyPathId == pathId) 1 else 0,
            )
        }
    }

    /**
     * The Host has no prior migration transaction when the Client's first candidate challenge
     * arrives. It may admit only that one authenticated challenge from the known standby route;
     * all other candidate traffic remains ignored until the transaction has been armed.
     */
    private fun responderCandidateBinding(sourceEndpoint: HandshakeTransportEndpoint): LifecyclePathBinding? {
        if (bootstrap.localRole != SessionRole.Host) return null
        val standby = engine.standbyPath() ?: return null
        return pathProvider.bindingFor(standby.pathId)?.takeIf { it.remoteControlEndpoint == sourceEndpoint }
    }

    private fun invokeInputSafetyReset(reason: LifecycleInputSafetyResetReason) {
        if (inputSafetyResetInvoked) return
        inputSafetyResetInvoked = true
        diagnosticEvents?.inputSafetyReset(
            if (reason == LifecycleInputSafetyResetReason.PathUnavailable) DiagnosticReason.NetworkLost else DiagnosticReason.UserRequested,
        )
        continuityParticipants.forEach { it.onInputSafetyReset(reason) }
    }

    private fun notifySessionClosing() {
        if (closingNotified) return
        closingNotified = true
        continuityParticipants.forEach(SessionContinuityParticipant::onSessionClosing)
    }

    private fun List<PathMigrationEntry>.matchesChannelSet(): Boolean =
        size == channelIds.size && all(PathMigrationEntry::isValid) && map {
            it.channelId
        }.toSet() == channelIds

    private fun header(type: SessionLifecycleMessageType, id: LifecycleMessageId = lifecycleIdGenerator.next()) =
        SessionLifecycleHeader(
            type,
            id,
            0,
        )
    private fun now(): Long = clock.nowMs().coerceAtLeast(0L)
    private fun record(error: SessionLifecycleError): SessionLifecycleError = error.also { lastError = it }

    private data class CandidateMigration(
        val id: PathMigrationId,
        val binding: LifecyclePathBinding,
        val challenge: ByteArray,
        val deadlineMs: Long,
        val oldPathStillUsable: Boolean,
    )

    private class ActiveMigration(
        val candidate: CandidateMigration,
        val preparation: ChannelMigrationPreparation,
        val oldPathStillUsable: Boolean,
        var peerEntries: List<PathMigrationEntry>? = null,
        var planHash: ByteArray? = null,
        val prepare: SessionLifecycleMessage.PathMigrationPrepare? = null,
        val ready: SessionLifecycleMessage.PathMigrationReady? = null,
    )
}

private fun DisconnectReason.toDiagnosticReason(): DiagnosticReason = when (this) {
    DisconnectReason.UserRequested -> DiagnosticReason.UserRequested
    DisconnectReason.ApplicationStopping,
    DisconnectReason.HostClosing,
    -> DiagnosticReason.ApplicationStopping
    DisconnectReason.PolicyChange -> DiagnosticReason.PolicyChange
    DisconnectReason.FatalError -> DiagnosticReason.FatalInternalError
    DisconnectReason.SupersededGeneration -> DiagnosticReason.SupersededGeneration
}

private fun SessionLifecycleError.toDiagnosticReason(): DiagnosticReason = when (this) {
    SessionLifecycleError.PathLost,
    SessionLifecycleError.NoStandbyPath,
    -> DiagnosticReason.NetworkLost
    SessionLifecycleError.PathValidationFailed,
    SessionLifecycleError.PathMigrationTimeout,
    SessionLifecycleError.PathMigrationConflict,
    SessionLifecycleError.MigrationEndpointAllocationFailed,
    SessionLifecycleError.MigrationCommitFailed,
    SessionLifecycleError.TransportRebindFailed,
    -> DiagnosticReason.ValidationFailure
    SessionLifecycleError.RecoveryExpired -> DiagnosticReason.RecoveryExpired
    SessionLifecycleError.RecoveryLeaseConflict -> DiagnosticReason.Capacity
    SessionLifecycleError.ReconnectHandshakeFailed,
    SessionLifecycleError.ReconnectPeerMismatch,
    SessionLifecycleError.ReconnectGenerationMismatch,
    -> DiagnosticReason.AuthenticationFailure
    SessionLifecycleError.CapabilityRenegotiationFailed,
    SessionLifecycleError.SessionSetupFailed,
    -> DiagnosticReason.PolicyChange
    else -> DiagnosticReason.FatalInternalError
}
