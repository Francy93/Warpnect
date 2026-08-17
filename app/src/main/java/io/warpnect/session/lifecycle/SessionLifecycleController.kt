@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.lifecycle

import io.warpnect.session.PathId
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionRole
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionPathPlan
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
interface SessionLifecycleMigrationAdapter {
    fun armCandidateWindow(binding: LifecyclePathBinding, migrationId: PathMigrationId, timeoutMs: Long): Boolean
    fun disarmCandidateWindow(migrationId: PathMigrationId)
    fun sendCandidate(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean
    fun prepareChannels(binding: LifecyclePathBinding, channels: List<SessionChannelKind>): ChannelMigrationPreparation?
    fun commit(
        binding: LifecyclePathBinding,
        preparation: ChannelMigrationPreparation,
        remoteEntries: List<PathMigrationEntry>,
    ): SessionLifecycleError
}

interface SessionLifecycleReconnectDelegate {
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
        handleDecision(result)
        armNextWake()
        SessionLifecycleError.None
    }

    /** Invoked by the 005E receive integration only after a record authenticated successfully. */
    fun onAuthenticatedReceive(nowMs: Long = now()) = synchronized(lock) {
        if (!closed) handleDecision(engine.onAuthenticatedReceive(nowMs))
    }

    fun onPlatformPathLoss(pathId: PathId, hard: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        handleDecision(engine.onPlatformPathLost(pathId, hard, now()))
        armNextWake()
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
            val transaction = candidate ?: return@synchronized
            if (sourceEndpoint != transaction.binding.remoteControlEndpoint) return@synchronized
            if (nowUs / 1_000L > transaction.deadlineMs) {
                abortMigration(SessionLifecycleError.PathMigrationTimeout, transaction.oldPathStillUsable)
                return@synchronized
            }
            val unprotected = control.unprotectCandidate(sourceEndpoint, protectedDatagram, nowUs)
            if (!unprotected.isSuccess) return@synchronized
            handleDecision(engine.onAuthenticatedReceive(nowUs / 1_000L))
            SessionLifecycleCodec.decode(requireNotNull(unprotected.payload))?.let { decoded ->
                receiveDecoded(decoded, candidatePath = true)
            }
        }

    fun snapshot(): SessionLifecycleSnapshot = synchronized(lock) { engine.snapshot(now()) }

    fun gracefulDisconnect(reason: DisconnectReason): SessionLifecycleError = synchronized(lock) {
        if (closed) return@synchronized SessionLifecycleError.Closed
        handleDecision(engine.beginDisconnect())
        continuityParticipants.forEach(SessionContinuityParticipant::onSessionClosing)
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
        // The old generation is terminal. The caller owns the new bootstrap and creates a new controller.
        continuityParticipants.forEach(SessionContinuityParticipant::onSessionReconnected)
        capacityOwner?.handoffToFreshGeneration()
        close()
        SessionLifecycleError.None
    }

    /** Schedules one of the bounded fresh-WNSH reconnect attempts; it never retries old keys. */
    fun onReconnectAttemptFailed(): SessionLifecycleError = synchronized(lock) {
        if (closed) return@synchronized SessionLifecycleError.Closed
        val decision = engine.onReconnectAttemptFailed(now())
        if (decision is LifecycleDecision.Error) {
            record(decision.error)
            if (engine.snapshot(now()).state == SessionLifecycleState.Failed) finishClose(markClosed = false)
        } else {
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
                handleDecision(engine.onHeartbeatAck(message.heartbeatId, message.activePathId, now()))
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
        if (standby?.pathId != message.targetPathId || standby.kind != message.targetPathKind || message.challenge.size != SessionLifecycleProtocol.CHALLENGE_BYTES) {
            record(SessionLifecycleError.PathValidationFailed)
            return
        }
        val binding = pathProvider.bindingFor(message.targetPathId) ?: run {
            record(SessionLifecycleError.PathValidationFailed)
            return
        }
        if (!migrationAdapter.armCandidateWindow(
                binding,
                message.migrationId,
                healthConfig.pathCandidateValidationTimeoutMs,
            )
        ) {
            record(SessionLifecycleError.PathValidationFailed)
            return
        }
        candidate = CandidateMigration(message.migrationId, binding, message.challenge.copyOf(), now() + healthConfig.pathCandidateValidationTimeoutMs, oldPathStillUsable = true)
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
        handleDecision(engine.beginDisconnect())
        continuityParticipants.forEach(SessionContinuityParticipant::onSessionClosing)
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
            is LifecycleDecision.SendHeartbeat -> sendMessage(
                SessionLifecycleMessage.Heartbeat(
                    header(SessionLifecycleMessageType.Heartbeat),
                    decision.heartbeatId,
                    decision.activePathId,
                ),
                candidatePath = false,
            )
            LifecycleDecision.BeginMigration -> startMigration()
            is LifecycleDecision.ValidateStandby -> startCandidateValidation(decision)
            is LifecycleDecision.MigrationCommitted -> continuityParticipants.forEach(
                SessionContinuityParticipant::onPathMigrationCommitted,
            )
            is LifecycleDecision.Suspended -> {
                continuityParticipants.forEach(SessionContinuityParticipant::onSessionSuspended)
                beginReconnect(decision.recoveryDeadlineMs)
            }
            is LifecycleDecision.BeginReconnect -> {
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
        activeMigration?.preparation?.close()
        activeMigration = null
        candidate?.let { migrationAdapter.disarmCandidateWindow(it.id) }
        candidate = null
        handleDecision(engine.onMigrationValidationFailed(oldPathStillUsable))
        record(error)
    }

    private fun finishClose(markClosed: Boolean = true): SessionLifecycleError {
        if (closed) return SessionLifecycleError.None
        closed = true
        timerHandle?.close()
        timerHandle = null
        activeMigration?.preparation?.close()
        activeMigration = null
        candidate?.let { migrationAdapter.disarmCandidateWindow(it.id) }
        candidate = null
        control.setPayloadListener(null)
        if (markClosed) engine.finishDisconnect()
        bootstrap.close()
        capacityOwner?.close()
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
