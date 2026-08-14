package io.warpnect.session

import java.util.Collections
import java.util.LinkedHashMap

fun interface SessionMonotonicClock {
    fun nowUs(): Long
}

object SystemSessionMonotonicClock : SessionMonotonicClock {
    override fun nowUs(): Long = System.nanoTime() / 1_000L
}

/**
 * Platform-neutral, bounded owner for independent two-peer Warpnect session models.
 *
 * It provides synchronized state mutation only. It owns no discovery, network, handshake, media,
 * input, or worker loop.
 */
class SessionManager(
    private val config: SessionManagerConfig,
    private val clock: SessionMonotonicClock = SystemSessionMonotonicClock,
) : AutoCloseable {
    private val lock = Any()
    private val sessions = LinkedHashMap<SessionId, ManagedSession>()
    private var policy = config.initialPolicy
    private var lastError = SessionError.None
    private var closed = false

    init {
        require(config.validate() == SessionError.None) {
            "Session manager configuration is invalid"
        }
    }

    fun createSession(request: SessionCreateRequest): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        validateRequest(request)?.let { return@synchronized failed(it) }
        if (sessions.containsKey(request.sessionId)) {
            return@synchronized failed(SessionError.DuplicateSessionId)
        }
        if (sessions.size >= config.maxSessions) {
            return@synchronized failed(SessionError.SessionCapacityExceeded)
        }
        if (request.localRole == SessionRole.Host && hostLiveSessionCount() >= policy.maxConcurrentClients) {
            return@synchronized failed(SessionError.SessionCapacityExceeded)
        }
        if (request.localRole == SessionRole.Host &&
            policy.duplicatePeerSessionPolicy == DuplicatePeerSessionPolicy.SingleSessionPerPeer &&
            sessions.values.any {
                it.localParticipant.role == SessionRole.Host &&
                    it.remoteParticipant.deviceId == request.remotePeer.deviceId &&
                    it.state.isLive()
            }
        ) {
            return@synchronized failed(SessionError.DuplicatePeerSessionNotAllowed)
        }

        val participantIndex = resolveParticipantIndex(request)
            ?: return@synchronized failed(SessionError.DuplicateParticipantIndex)
        val effectivePolicy = request.policy ?: policy
        if (effectivePolicy.validate() != SessionError.None) {
            return@synchronized failed(SessionError.InvalidPolicy)
        }

        val nowUs = monotonicNowUs()
        val session = ManagedSession(
            sessionId = request.sessionId,
            generation = request.generation,
            localParticipant = SessionParticipant(config.localDeviceId, request.localRole),
            remoteParticipant = SessionParticipant(request.remotePeer.deviceId, request.remoteRole),
            participantIndex = participantIndex,
            policy = effectivePolicy,
            createdAtMonotonicUs = nowUs,
            maxPaths = config.maxPathsPerSession,
            maxChannels = config.maxChannelsPerSession,
            maxPeripherals = config.maxPeripheralsPerSession,
        )
        sessions[request.sessionId] = session
        succeeded(session)
    }

    fun replacePolicy(replacement: SessionBehaviorPolicy): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        if (replacement.validate() != SessionError.None ||
            replacement.maxConcurrentClients > config.maxSessions ||
            hostLiveSessionCount() > replacement.maxConcurrentClients
        ) {
            return@synchronized failed(SessionError.InvalidPolicy)
        }
        policy = replacement
        succeeded()
    }

    fun addChannel(sessionId: SessionId, channel: SessionChannel): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
        if (channel.state != SessionChannelState.Configured) {
            return@synchronized failed(SessionError.InvalidChannel, session)
        }
        val error = session.addChannel(channel)
        if (error == SessionError.None) succeeded(session) else failed(error, session)
    }

    fun updateChannelState(
        sessionId: SessionId,
        channelId: ChannelId,
        state: SessionChannelState,
    ): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
        val error = session.updateChannelState(channelId, state)
        if (error == SessionError.None) succeeded(session) else failed(error, session)
    }

    fun addPath(sessionId: SessionId, path: NetworkPath): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
        val error = session.addPath(path)
        if (error == SessionError.None) succeeded(session) else failed(error, session)
    }

    fun updatePathState(sessionId: SessionId, pathId: PathId, state: NetworkPathState): SessionOperationResult =
        synchronized(lock) {
            if (closed) return@synchronized failed(SessionError.Closed)
            val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
            val error = session.updatePathState(pathId, state)
            if (error == SessionError.None) succeeded(session) else failed(error, session)
        }

    fun addPeripheral(sessionId: SessionId, peripheral: SessionPeripheral): SessionOperationResult =
        synchronized(lock) {
            if (closed) return@synchronized failed(SessionError.Closed)
            val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
            val error = session.addPeripheral(peripheral, monotonicNowUs())
            if (error == SessionError.None) succeeded(session) else failed(error, session)
        }

    fun updatePeripheralPresence(
        sessionId: SessionId,
        peripheralId: LogicalPeripheralId,
        sourcePresence: SourcePeripheralPresence,
        targetExposure: TargetPeripheralExposureState,
    ): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
        val error = session.updatePeripheralPresence(
            peripheralId = peripheralId,
            sourcePresence = sourcePresence,
            targetExposure = targetExposure,
            nowUs = monotonicNowUs(),
        )
        if (error == SessionError.None) succeeded(session) else failed(error, session)
    }

    fun transitionState(sessionId: SessionId, state: SessionState): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        val session = findMutableSession(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
        val error = session.transitionState(state, monotonicNowUs())
        if (error == SessionError.None) succeeded(session) else failed(error, session)
    }

    fun session(sessionId: SessionId): SessionSnapshot? = synchronized(lock) {
        sessions[sessionId]?.snapshot()
    }

    fun sessionsForPeer(deviceId: DeviceId): List<SessionSnapshot> = synchronized(lock) {
        immutableList(
            sessions.values
                .filter { it.remoteParticipant.deviceId == deviceId }
                .map { it.snapshot() },
        )
    }

    fun removeSession(sessionId: SessionId): SessionOperationResult = synchronized(lock) {
        if (closed) return@synchronized failed(SessionError.Closed)
        val removed = sessions.remove(sessionId) ?: return@synchronized failed(SessionError.SessionNotFound)
        succeeded(removed)
    }

    fun snapshot(): SessionManagerSnapshot = synchronized(lock) {
        snapshotLocked()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            sessions.clear()
            closed = true
        }
    }

    private fun validateRequest(request: SessionCreateRequest): SessionError? {
        if (request.remotePeer.deviceId == config.localDeviceId) return SessionError.InvalidDeviceId
        if (request.localRole == request.remoteRole) return SessionError.InvalidRoleCombination
        if (request.policy?.validate()?.let { it != SessionError.None } == true) {
            return SessionError.InvalidPolicy
        }
        if (request.participantIndex != null && request.localRole != SessionRole.Host) {
            return SessionError.InvalidParticipantIndex
        }
        return null
    }

    private fun resolveParticipantIndex(request: SessionCreateRequest): ParticipantIndex? {
        if (request.localRole != SessionRole.Host) return request.participantIndex
        val requested = request.participantIndex
        if (requested != null) {
            return if (sessions.values.any {
                    it.localParticipant.role == SessionRole.Host &&
                        it.participantIndex == requested &&
                        it.state.isLive()
                }
            ) {
                null
            } else {
                requested
            }
        }
        return (0 until SessionBounds.HARD_MAX_CONCURRENT_CLIENTS)
            .mapNotNull(ParticipantIndex::from)
            .firstOrNull { candidate ->
                sessions.values.none {
                    it.localParticipant.role == SessionRole.Host &&
                        it.participantIndex == candidate &&
                        it.state.isLive()
                }
            }
    }

    private fun hostLiveSessionCount(): Int =
        sessions.values.count { it.localParticipant.role == SessionRole.Host && it.state.isLive() }

    private fun findMutableSession(sessionId: SessionId): ManagedSession? {
        if (closed) return null
        return sessions[sessionId]
    }

    private fun succeeded(session: ManagedSession? = null): SessionOperationResult = SessionOperationResult(
        error = SessionError.None,
        session = session?.snapshot(),
        manager = snapshotLocked(),
    )

    private fun failed(error: SessionError, session: ManagedSession? = null): SessionOperationResult {
        lastError = error
        session?.recordError(error)
        return SessionOperationResult(
            error = error,
            session = session?.snapshot(),
            manager = snapshotLocked(),
        )
    }

    private fun snapshotLocked(): SessionManagerSnapshot {
        val snapshots = immutableList(sessions.values.map { it.snapshot() })
        val peripherals = snapshots.flatMap { it.logicalPeripherals }
        return SessionManagerSnapshot(
            closed = closed,
            activeSessionCount = sessions.values.count { it.state.isLive() },
            registeredSessionCount = sessions.size,
            maxConcurrentClients = policy.maxConcurrentClients,
            maxSessions = config.maxSessions,
            hostSessions = sessions.values.count { it.localParticipant.role == SessionRole.Host },
            clientSessions = sessions.values.count { it.localParticipant.role == SessionRole.Client },
            sessionsByState = immutableList(
                SessionState.entries
                    .map { state -> SessionStateCount(state, sessions.values.count { it.state == state }) }
                    .filter { it.count > 0 },
            ),
            activePaths = snapshots.sumOf { snapshot ->
                snapshot.paths.count { it.state == NetworkPathState.Active }
            },
            standbyPaths = snapshots.sumOf { snapshot ->
                snapshot.paths.count { it.state == NetworkPathState.Standby }
            },
            logicalPeripheralCounts = peripherals.toCounts(),
            policy = policy,
            lastError = lastError,
            sessions = snapshots,
        )
    }

    private fun monotonicNowUs(): Long = clock.nowUs().coerceAtLeast(0L)
}

private class ManagedSession(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val localParticipant: SessionParticipant,
    val remoteParticipant: SessionParticipant,
    val participantIndex: ParticipantIndex?,
    val policy: SessionBehaviorPolicy,
    val createdAtMonotonicUs: Long,
    private val maxPaths: Int,
    private val maxChannels: Int,
    private val maxPeripherals: Int,
) {
    private val channels = LinkedHashMap<ChannelId, SessionChannel>()
    private val paths = LinkedHashMap<PathId, NetworkPath>()
    private val peripherals = LinkedHashMap<LogicalPeripheralId, SessionPeripheral>()

    var state: SessionState = SessionState.Created
        private set
    private var lastStateChangeAtMonotonicUs: Long = createdAtMonotonicUs
    private var lastError: SessionError = SessionError.None

    fun addChannel(channel: SessionChannel): SessionError {
        if (channels.containsKey(channel.channelId)) return SessionError.DuplicateChannelId
        if (channels.size >= maxChannels) return SessionError.ChannelCapacityExceeded
        channels[channel.channelId] = channel
        return SessionError.None
    }

    fun updateChannelState(channelId: ChannelId, next: SessionChannelState): SessionError {
        val channel = channels[channelId] ?: return SessionError.InvalidChannel
        if (!channel.state.canTransitionTo(next)) return SessionError.InvalidChannelStateTransition
        channels[channelId] = channel.copy(state = next)
        return SessionError.None
    }

    fun addPath(path: NetworkPath): SessionError {
        if (paths.containsKey(path.pathId)) return SessionError.DuplicatePathId
        if (paths.size >= maxPaths) return SessionError.PathCapacityExceeded
        if (path.state == NetworkPathState.Active && paths.values.any { it.state == NetworkPathState.Active }) {
            return SessionError.MultipleActivePaths
        }
        paths[path.pathId] = path
        return SessionError.None
    }

    fun updatePathState(pathId: PathId, next: NetworkPathState): SessionError {
        val path = paths[pathId] ?: return SessionError.InvalidPath
        if (!path.state.canTransitionTo(next)) return SessionError.InvalidPathStateTransition
        if (next == NetworkPathState.Active &&
            paths.values.any { it.pathId != pathId && it.state == NetworkPathState.Active }
        ) {
            return SessionError.MultipleActivePaths
        }
        paths[pathId] = path.copy(state = next)
        return SessionError.None
    }

    fun addPeripheral(peripheral: SessionPeripheral, nowUs: Long): SessionError {
        if (peripheral.id.sessionId != sessionId) return SessionError.InvalidPeripheral
        if (peripherals.containsKey(peripheral.id)) return SessionError.DuplicatePeripheral
        if (peripherals.size >= maxPeripherals) return SessionError.PeripheralCapacityExceeded
        if (!isValidPresence(peripheral.id.kind, peripheral.sourcePresence, peripheral.targetExposure)) {
            return SessionError.InvalidPeripheralPresence
        }
        peripherals[peripheral.id] = peripheral.copy(lastPresenceChangeAtMonotonicUs = nowUs)
        return SessionError.None
    }

    fun updatePeripheralPresence(
        peripheralId: LogicalPeripheralId,
        sourcePresence: SourcePeripheralPresence,
        targetExposure: TargetPeripheralExposureState,
        nowUs: Long,
    ): SessionError {
        val peripheral = peripherals[peripheralId] ?: return SessionError.InvalidPeripheral
        if (!isValidPresence(peripheral.id.kind, sourcePresence, targetExposure)) {
            return SessionError.InvalidPeripheralPresence
        }
        peripherals[peripheralId] = peripheral.copy(
            sourcePresence = sourcePresence,
            targetExposure = targetExposure,
            lastPresenceChangeAtMonotonicUs = nowUs,
        )
        return SessionError.None
    }

    fun transitionState(next: SessionState, nowUs: Long): SessionError {
        if (!state.canTransitionTo(next)) return SessionError.InvalidStateTransition
        state = next
        lastStateChangeAtMonotonicUs = nowUs
        return SessionError.None
    }

    fun recordError(error: SessionError) {
        lastError = error
    }

    fun snapshot(): SessionSnapshot {
        val peripheralSnapshots = immutableList(peripherals.values.map { it.snapshot() })
        return SessionSnapshot(
            sessionId = sessionId,
            generation = generation,
            localParticipant = localParticipant,
            remoteParticipant = remoteParticipant,
            participantIndex = participantIndex,
            state = state,
            channels = immutableList(channels.values.map { it.snapshot() }),
            paths = immutableList(paths.values.map { it.snapshot() }),
            activePathId = paths.values.firstOrNull { it.state == NetworkPathState.Active }?.pathId,
            logicalPeripherals = peripheralSnapshots,
            peripheralCounts = peripheralSnapshots.toCounts(),
            policy = policy,
            createdAtMonotonicUs = createdAtMonotonicUs,
            lastStateChangeAtMonotonicUs = lastStateChangeAtMonotonicUs,
            lastError = lastError,
        )
    }

    private fun isValidPresence(
        kind: PeripheralKind,
        sourcePresence: SourcePeripheralPresence,
        targetExposure: TargetPeripheralExposureState,
    ): Boolean {
        if (sourcePresence == SourcePeripheralPresence.Absent &&
            targetExposure == TargetPeripheralExposureState.Active
        ) {
            return false
        }
        return when (policy.peripheralPresencePolicies.forKind(kind)) {
            PeripheralPresencePolicy.MirrorPhysicalPresence -> when (sourcePresence) {
                SourcePeripheralPresence.Absent ->
                    targetExposure == TargetPeripheralExposureState.NotExposed
                SourcePeripheralPresence.Present ->
                    targetExposure != TargetPeripheralExposureState.RetainedInactive
            }
            PeripheralPresencePolicy.StableSessionPresence -> when (sourcePresence) {
                SourcePeripheralPresence.Absent ->
                    targetExposure != TargetPeripheralExposureState.Active
                SourcePeripheralPresence.Present ->
                    targetExposure != TargetPeripheralExposureState.RetainedInactive
            }
        }
    }
}

private fun SessionChannel.snapshot(): SessionChannelSnapshot = SessionChannelSnapshot(
    channelId = channelId,
    kind = kind,
    direction = direction,
    state = state,
)

private fun NetworkPath.snapshot(): NetworkPathSnapshot = NetworkPathSnapshot(
    pathId = pathId,
    kind = kind,
    state = state,
)

private fun SessionPeripheral.snapshot(): SessionPeripheralSnapshot = SessionPeripheralSnapshot(
    id = id,
    sourcePresence = sourcePresence,
    targetExposure = targetExposure,
    lastPresenceChangeAtMonotonicUs = lastPresenceChangeAtMonotonicUs,
)

private fun List<SessionPeripheralSnapshot>.toCounts(): SessionPeripheralCounts = SessionPeripheralCounts(
    total = size,
    sourcePresent = count { it.sourcePresence == SourcePeripheralPresence.Present },
    targetActive = count { it.targetExposure == TargetPeripheralExposureState.Active },
    retainedInactive = count { it.targetExposure == TargetPeripheralExposureState.RetainedInactive },
    byKind = immutableList(
        PeripheralKind.entries
            .map { kind ->
                val matching = filter { it.id.kind == kind }
                PeripheralKindCount(
                    kind = kind,
                    total = matching.size,
                    sourcePresent = matching.count {
                        it.sourcePresence == SourcePeripheralPresence.Present
                    },
                    targetActive = matching.count {
                        it.targetExposure == TargetPeripheralExposureState.Active
                    },
                    retainedInactive = matching.count {
                        it.targetExposure == TargetPeripheralExposureState.RetainedInactive
                    },
                )
            }
            .filter { it.total > 0 },
    ),
)

private fun <T> immutableList(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
