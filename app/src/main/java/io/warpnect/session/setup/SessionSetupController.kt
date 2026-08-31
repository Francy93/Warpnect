@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.setup

import io.warpnect.session.DeviceId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.NetworkPathState
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import java.net.InetAddress

fun interface SessionSetupMonotonicClock {
    fun nowMs(): Long
}

fun interface SessionSetupTimer {
    fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable
}

/** Cold-path setup milestones; payloads, endpoints, and identifiers are intentionally absent. */
data class SessionSetupDebugEvent(
    val kind: SessionSetupDebugEventKind,
    val error: SessionSetupError? = null,
)

enum class SessionSetupDebugEventKind {
    OfferBuildStarted,
    OfferBuilt,
    OfferSent,
    OfferReceived,
    PathSelectionBuilt,
    PathSelectionSent,
    PathSelectionReceived,
    ChannelPlanValidationStarted,
    ChannelPlanValidationSucceeded,
    ChannelPlanValidationFailed,
    SocketPathBindingStarted,
    SocketPathBindingSucceeded,
    Committed,
    Failed,
}

fun interface SessionSetupDebugObserver {
    fun onEvent(event: SessionSetupDebugEvent)

    companion object {
        val None = SessionSetupDebugObserver { }
    }
}

fun interface ExactStreamConfigurationValidator {
    fun validate(
        localRole: SessionRole,
        profile: io.warpnect.session.capability.NegotiatedCapabilityProfile,
        configurations: List<SetupConfiguration>,
    ): SessionSetupError
}

data class DirectPathHostRequest(
    val sessionId: SessionId,
    val setupId: SessionSetupId,
    val pathAttemptId: PathAttemptId,
    val profileHash: ByteArray,
    val targetPathId: PathId,
    val validationTimeoutMs: Long,
)

data class DirectPathClientRequest(
    val sessionId: SessionId,
    val setupId: SessionSetupId,
    val pathAttemptId: PathAttemptId,
    val profileHash: ByteArray,
    val targetPathId: PathId,
    val hostProbePort: Int,
    val routeToken: String,
    val validationTimeoutMs: Long,
)

sealed interface DirectPathPreparationEvent {
    data class HostReady(val probePort: Int) : DirectPathPreparationEvent
    data class Validated(val candidate: SetupPathCandidate, val lease: DirectPathLease) : DirectPathPreparationEvent
    data class Failure(val reason: PathFailureReason) : DirectPathPreparationEvent
}

/** Platform Direct work is asynchronous; callbacks are serialized back through this controller. */
interface DirectPathCoordinator : AutoCloseable {
    fun prepareHost(request: DirectPathHostRequest, listener: (DirectPathPreparationEvent) -> Unit)
    fun connectClient(request: DirectPathClientRequest, listener: (DirectPathPreparationEvent) -> Unit)
    fun cancel(setupId: SessionSetupId)
    override fun close()
}

fun interface SessionControlPathRebinder {
    fun rebind(candidate: SetupPathCandidate, lease: DirectPathLease): SessionSetupError
}

data class SessionSetupRuntime(
    val lanCandidate: SetupPathCandidate?,
    val endpointAllocator: ChannelEndpointAllocator,
    val transportPreparer: ChannelTransportPreparer,
    val exactValidator: ExactStreamConfigurationValidator,
    val directCoordinator: DirectPathCoordinator? = null,
    val directRouteToken: String? = null,
    val controlPathRebinder: SessionControlPathRebinder? = null,
)

data class HostSessionSetupPolicy(
    val streamPreferences: SessionSetupPreferences,
    val recoveryPolicy: SessionRecoveryPolicy = SessionRecoveryPolicy(),
) {
    fun isValidFor(bootstrap: NegotiatedSessionBootstrap): Boolean = streamPreferences.isValidFor(bootstrap.profile) &&
        SessionSetupPlanner.selectedChannelKinds(bootstrap.profile.selectedChannels)?.all { kind ->
            val template = recoveryPolicy.forKind(kind)
            template.isValid() && template.recoveryFlags and bootstrap.profile.recoveryFlags == template.recoveryFlags
        } == true
}

data class SessionSetupControllerConfig(
    val maxActiveSetups: Int = SessionSetupProtocol.DEFAULT_MAX_ACTIVE_SETUPS,
    val lanTimeoutMs: Long = SessionSetupProtocol.DEFAULT_LAN_TIMEOUT_MS,
    val directTimeoutMs: Long = SessionSetupProtocol.DIRECT_OVERALL_TIMEOUT_MS,
    val completionRetentionMs: Long = SessionSetupProtocol.COMPLETION_CACHE_RETENTION_MS,
    val preparedBootstrapTtlMs: Long = SessionSetupProtocol.PREPARED_BOOTSTRAP_TTL_MS,
) {
    fun isValid(): Boolean = maxActiveSetups in 1..SessionSetupProtocol.HARD_MAX_ACTIVE_SETUPS &&
        lanTimeoutMs > 0L && directTimeoutMs >= SessionSetupProtocol.DIRECT_SETUP_TIMEOUT_MS &&
        completionRetentionMs > 0L && preparedBootstrapTtlMs > 0L
}

data class SessionSetupSnapshot(
    val closed: Boolean,
    val activeSetups: Int,
    val completedSetups: Long,
    val retries: Long,
    val timeouts: Long,
    val malformedMessages: Long,
    val semanticConflicts: Long,
    val directAttempts: Long,
    val directFallbacks: Long,
    val channelCount: Int,
    val completionCacheSize: Int,
    val lastSetupId: SessionSetupId?,
    val lastSessionId: SessionId?,
    val lastPeerDeviceId: DeviceId?,
    val lastActivePath: NetworkPathKind?,
    val lastStandbyPath: NetworkPathKind?,
    val lastProposalGeneration: Int?,
    val lastDurationMs: Long?,
    val lastError: SessionSetupError,
)

/**
 * Bounded WNSN state machine. Its only network dependency is already protected SessionControl;
 * callers cannot feed raw UDP bytes into this controller.
 */
class SessionSetupController(
    private val clock: SessionSetupMonotonicClock,
    private val setupIdGenerator: SessionSetupIdGenerator = SecureSessionSetupIdGenerator,
    private val pathAttemptIdGenerator: PathAttemptIdGenerator = SecurePathAttemptIdGenerator,
    private val config: SessionSetupControllerConfig = SessionSetupControllerConfig(),
    private val timer: SessionSetupTimer? = null,
    private val onCompleted: (PreparedSessionBootstrap) -> Unit = {},
    private val debugObserver: SessionSetupDebugObserver = SessionSetupDebugObserver.None,
) : AutoCloseable {
    private val lock = Any()
    private val bindings = LinkedHashMap<SessionId, Binding>()
    private val active = LinkedHashMap<SetupKey, ManagedSetup>()
    private val completed = LinkedHashMap<SetupKey, CompletedSetup>()
    private val counters = Counters()
    private var closed = false

    fun registerHost(
        bootstrap: NegotiatedSessionBootstrap,
        runtime: SessionSetupRuntime,
        policy: HostSessionSetupPolicy,
    ): SessionSetupError = synchronized(lock) {
        val effectivePolicy = policy.copy(
            streamPreferences = policy.streamPreferences.retainOnlySelectedChannels(bootstrap.profile),
        )
        if (closed) return@synchronized record(SessionSetupError.Closed)
        if (!config.isValid() || bootstrap.localRole != SessionRole.Host || bootstrap.remoteRole != SessionRole.Client ||
            !effectivePolicy.isValidFor(bootstrap) || bindings.containsKey(bootstrap.sessionId) || !runtime.isValid()
        ) {
            return@synchronized record(SessionSetupError.InvalidConfig)
        }
        bindings[bootstrap.sessionId] = Binding(bootstrap, runtime, effectivePolicy)
        bootstrap.secureSessionControl.setPayloadListener { bytes -> receive(bootstrap.sessionId, bytes) }
        SessionSetupError.None
    }

    fun beginClient(
        bootstrap: NegotiatedSessionBootstrap,
        runtime: SessionSetupRuntime,
        preferences: SessionSetupPreferences,
    ): SessionSetupError = synchronized(lock) {
        if (closed) return@synchronized record(SessionSetupError.Closed)
        if (!config.isValid() || bootstrap.localRole != SessionRole.Client || bootstrap.remoteRole != SessionRole.Host ||
            !runtime.isValid() || !preferences.isValidFor(bootstrap.profile) || bindings.containsKey(bootstrap.sessionId) ||
            active.size >= config.maxActiveSetups
        ) {
            return@synchronized record(SessionSetupError.InvalidConfig)
        }
        SessionSetupPlanner.validateExactPreferences(bootstrap.profile, preferences)?.let {
            return@synchronized record(it)
        }
        emitDebug(SessionSetupDebugEventKind.OfferBuildStarted)
        val setupId = setupIdGenerator.next()
        val request = SessionSetupMessage.ClientSetupRequest(
            SessionSetupHeader(SessionSetupMessageType.ClientSetupRequest, setupId, 0),
            bootstrap.profileHash.copyOf(),
            bootstrap.profile.selectedChannels,
            preferences,
        )
        val bytes = SessionSetupCodec.encode(request) ?: return@synchronized record(SessionSetupError.InvalidConfig)
        emitDebug(SessionSetupDebugEventKind.OfferBuilt)
        val now = now()
        val managed = ManagedSetup(
            SetupKey(bootstrap.sessionId, setupId),
            Binding(bootstrap, runtime, null),
            SessionSetupState.ClientRequestSent,
            now,
            timeoutMs = timeoutFor(preferences.pathPreference),
            clientPreferences = preferences,
            clientRequestHash = SessionSetupCodec.hash(bytes),
        )
        bindings[bootstrap.sessionId] = managed.binding
        active[managed.key] = managed
        scheduleOverallTimeout(managed)
        bootstrap.secureSessionControl.setPayloadListener { payload -> receive(bootstrap.sessionId, payload) }
        counters.lastSetupId = setupId
        counters.lastSessionId = bootstrap.sessionId
        if (sendFlight(managed, bytes, SessionSetupDebugEventKind.OfferSent)) {
            SessionSetupError.None
        } else {
            SessionSetupError.SecureControlFailure
        }
    }

    fun receive(sessionId: SessionId, payload: ByteArray) = synchronized(lock) {
        if (closed) return@synchronized
        expireCompletedLocked()
        val packet = SessionSetupCodec.decode(payload)
        if (packet == null) {
            counters.malformedMessages += 1
            record(SessionSetupError.MalformedMessage)
            return@synchronized
        }
        val binding = bindings[sessionId] ?: return@synchronized
        val key = SetupKey(sessionId, packet.message.header.setupId)
        counters.lastSetupId = key.setupId
        when (val message = packet.message) {
            is SessionSetupMessage.ClientSetupRequest -> handleRequest(key, binding, packet, message)
            is SessionSetupMessage.HostPathDirective -> handleDirective(key, binding, packet, message)
            is SessionSetupMessage.DirectPathProbe,
            is SessionSetupMessage.DirectPathAck,
            -> record(SessionSetupError.UnexpectedMessage)
            is SessionSetupMessage.PathFailure -> handlePathFailure(key, packet, message)
            is SessionSetupMessage.ClientEndpointOffer -> handleEndpointOffer(key, binding, packet, message)
            is SessionSetupMessage.HostConfigurationProposal -> handleProposal(key, packet, message)
            is SessionSetupMessage.ClientConfigurationAccept -> handleAccept(key, packet, message)
            is SessionSetupMessage.ClientConfigurationDecline -> handleDecline(key, message)
            is SessionSetupMessage.HostCommit -> handleCommit(key, message)
            is SessionSetupMessage.Reject -> fail(active[key], message.reason.toLocalError(), true)
        }
    }

    fun advance() = synchronized(lock) {
        if (closed) return@synchronized
        expireCompletedLocked()
        val now = now()
        active.values.toList().forEach { setup -> advanceSetup(setup, now) }
    }

    fun snapshot(): SessionSetupSnapshot = synchronized(lock) {
        expireCompletedLocked()
        SessionSetupSnapshot(
            closed,
            active.size,
            counters.completed,
            counters.retries,
            counters.timeouts,
            counters.malformedMessages,
            counters.semanticConflicts,
            counters.directAttempts,
            counters.directFallbacks,
            counters.channelCount,
            completed.size,
            counters.lastSetupId,
            counters.lastSessionId,
            counters.lastPeerDeviceId,
            counters.lastActivePath,
            counters.lastStandbyPath,
            counters.lastProposalGeneration,
            counters.lastDurationMs,
            counters.lastError,
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        active.values.toList().forEach { fail(it, SessionSetupError.Closed, true) }
        bindings.values.forEach { binding -> binding.bootstrap.secureSessionControl.setPayloadListener(null) }
        bindings.clear()
        active.clear()
        completed.clear()
        closed = true
    }

    private fun handleRequest(
        key: SetupKey,
        binding: Binding,
        packet: DecodedSessionSetupPacket,
        request: SessionSetupMessage.ClientSetupRequest,
    ) {
        if (binding.bootstrap.localRole != SessionRole.Host || binding.policy == null) return
        emitDebug(SessionSetupDebugEventKind.OfferReceived)
        active[key]?.let { existing ->
            semanticDuplicate(existing, packet)?.let { duplicate ->
                if (duplicate) existing.outboundBytes?.let { sendFlight(existing, it) }
            }
            return
        }
        if (active.size >= config.maxActiveSetups) {
            sendReject(binding, key.setupId, SetupRejectStage.Request, SetupRejectReason.Busy, packet.hash)
            record(SessionSetupError.SetupBusy)
            return
        }
        if (!request.profileHash.contentEquals(binding.bootstrap.profileHash) ||
            request.selectedChannels != binding.bootstrap.profile.selectedChannels ||
            !request.preferences.isValidFor(binding.bootstrap.profile)
        ) {
            sendReject(binding, key.setupId, SetupRejectStage.Request, SetupRejectReason.Incompatible, packet.hash)
            binding.bootstrap.admissionReservation?.close()
            record(SessionSetupError.CapabilityProfileMismatch)
            return
        }
        val exact = SessionSetupPlanner.validateExactPreferences(binding.bootstrap.profile, request.preferences)
        if (exact != null) {
            sendReject(binding, key.setupId, SetupRejectStage.Request, SetupRejectReason.Incompatible, packet.hash)
            binding.bootstrap.admissionReservation?.close()
            record(exact)
            return
        }
        val managed = ManagedSetup(
            key,
            binding,
            SessionSetupState.ClientRequestSent,
            now(),
            timeoutFor(request.preferences.pathPreference),
            request.preferences,
            packet.hash,
        )
        managed.inboundHashes[SessionSetupMessageType.ClientSetupRequest] = packet.hash.copyOf()
        active[key] = managed
        scheduleOverallTimeout(managed)
        chooseHostPath(managed)
    }

    private fun chooseHostPath(setup: ManagedSetup) {
        val preference = setup.clientPreferences.pathPreference
        val directEligible = setup.binding.bootstrap.profile.eligiblePathKinds and CapabilityBits.PATH_DIRECT != 0
        val coordinator = setup.binding.runtime.directCoordinator
        val shouldAttempt = directEligible && coordinator != null && when (preference) {
            PathPreferencePolicy.DirectOnly,
            PathPreferencePolicy.PreferDirectThenLan,
            -> true
            PathPreferencePolicy.PreferLan -> setup.clientPreferences.secondaryPathPolicy == SecondaryPathPolicy.KeepValidatedStandby
            PathPreferencePolicy.LanOnly -> false
        }
        if (!shouldAttempt) {
            if (preference == PathPreferencePolicy.DirectOnly) {
                sendReject(
                    setup.binding,
                    setup.key.setupId,
                    SetupRejectStage.Path,
                    SetupRejectReason.DirectUnavailable,
                    setup.clientRequestHash,
                )
                fail(setup, SessionSetupError.DirectUnavailable, true)
            } else {
                setup.pathSelection = selectLan(setup) ?: run {
                    fail(setup, SessionSetupError.NoUsablePath, true)
                    return
                }
                sendDirective(setup, PathDirective.UseLan, 0, null)
            }
            return
        }
        counters.directAttempts += 1
        setup.directAttempted = true
        setup.pathAttemptId = pathAttemptIdGenerator.next()
        setup.state = SessionSetupState.AwaitingDirectValidation
        val targetPathId = if (preference == PathPreferencePolicy.PreferLan) standbyPathId() else activePathId()
        coordinator.prepareHost(
            DirectPathHostRequest(
                setup.key.sessionId,
                setup.key.setupId,
                requireNotNull(setup.pathAttemptId),
                setup.binding.bootstrap.profileHash.copyOf(),
                targetPathId,
                SessionSetupProtocol.DIRECT_VALIDATION_TIMEOUT_MS,
            ),
        ) { event -> onDirectHostEvent(setup.key, event) }
    }

    private fun onDirectHostEvent(key: SetupKey, event: DirectPathPreparationEvent) = synchronized(lock) {
        val setup = active[key] ?: return@synchronized
        when (event) {
            is DirectPathPreparationEvent.HostReady -> {
                if (event.probePort !in 1..0xffff) {
                    fail(setup, SessionSetupError.DirectGroupCreationFailed, true)
                } else {
                    sendDirective(setup, PathDirective.AttemptDirect, event.probePort, setup.pathAttemptId)
                }
            }
            is DirectPathPreparationEvent.Validated -> {
                setup.directCandidate = event.candidate
                setup.directLease = event.lease
                setup.pathSelection = selectPaths(setup, event.candidate, null)
                if (!rebindDirectControlIfActive(setup, event.candidate)) {
                    fail(setup, SessionSetupError.DirectProbeAuthenticationFailed, true)
                }
            }
            is DirectPathPreparationEvent.Failure -> handleDirectFailure(setup, event.reason, sendPathFailure = false)
        }
    }

    private fun handleDirective(
        key: SetupKey,
        binding: Binding,
        packet: DecodedSessionSetupPacket,
        directive: SessionSetupMessage.HostPathDirective,
    ) {
        val setup = active[key] ?: return
        if (binding.bootstrap.localRole != SessionRole.Client || !directive.profileHash.contentEquals(binding.bootstrap.profileHash)) {
            fail(setup, SessionSetupError.CapabilityProfileMismatch, true)
            return
        }
        emitDebug(SessionSetupDebugEventKind.PathSelectionReceived)
        semanticDuplicate(setup, packet)?.let { duplicate ->
            if (duplicate) setup.outboundBytes?.let { sendFlight(setup, it) }
            return
        }
        cancelRetry(setup)
        setup.inboundHashes[SessionSetupMessageType.HostPathDirective] = packet.hash.copyOf()
        if (directive.directive == PathDirective.UseLan) {
            setup.pathSelection = selectLan(setup) ?: run {
                fail(setup, SessionSetupError.NoUsablePath, true)
                return
            }
            allocateClientEndpointsAndSend(setup)
            return
        }
        val attemptId = directive.pathAttemptId
        val coordinator = binding.runtime.directCoordinator
        val routeToken = binding.runtime.directRouteToken
        if (attemptId == null || coordinator == null || routeToken.isNullOrBlank()) {
            handleDirectFailure(setup, PathFailureReason.DirectUnavailable, sendPathFailure = true)
            return
        }
        counters.directAttempts += 1
        setup.directAttempted = true
        setup.pathAttemptId = attemptId
        setup.state = SessionSetupState.AwaitingDirectValidation
        val targetPathId = if (directive.activeCandidate == NetworkPathKind.Direct) activePathId() else standbyPathId()
        coordinator.connectClient(
            DirectPathClientRequest(
                setup.key.sessionId,
                setup.key.setupId,
                attemptId,
                binding.bootstrap.profileHash.copyOf(),
                targetPathId,
                directive.hostDirectProbePort,
                routeToken,
                directive.directTimeoutMs.toLong(),
            ),
        ) { event -> onDirectClientEvent(setup.key, event) }
    }

    private fun onDirectClientEvent(key: SetupKey, event: DirectPathPreparationEvent) = synchronized(lock) {
        val setup = active[key] ?: return@synchronized
        when (event) {
            is DirectPathPreparationEvent.Validated -> {
                setup.directCandidate = event.candidate
                setup.directLease = event.lease
                setup.pathSelection = selectPaths(setup, event.candidate, null)
                if (setup.pathSelection == null) {
                    fail(setup, SessionSetupError.NoUsablePath, true)
                } else if (!rebindDirectControlIfActive(setup, event.candidate)) {
                    fail(setup, SessionSetupError.DirectProbeAuthenticationFailed, true)
                } else {
                    allocateClientEndpointsAndSend(setup)
                }
            }
            is DirectPathPreparationEvent.Failure -> handleDirectFailure(setup, event.reason, sendPathFailure = true)
            is DirectPathPreparationEvent.HostReady -> Unit
        }
    }

    private fun handleDirectFailure(setup: ManagedSetup, reason: PathFailureReason, sendPathFailure: Boolean) {
        setup.binding.runtime.directCoordinator?.cancel(setup.key.setupId)
        setup.directLease?.close()
        setup.directLease = null
        setup.directFailure = reason
        if (setup.clientPreferences.pathPreference == PathPreferencePolicy.DirectOnly) {
            if (!sendPathFailure && setup.binding.bootstrap.localRole == SessionRole.Host) {
                val attemptId = setup.pathAttemptId
                if (attemptId != null && setup.outboundBytes != null) {
                    val failure = SessionSetupMessage.PathFailure(
                        SessionSetupHeader(SessionSetupMessageType.PathFailure, setup.key.setupId, 0),
                        setup.binding.bootstrap.profileHash.copyOf(),
                        attemptId,
                        reason,
                    )
                    SessionSetupCodec.encode(failure)?.let { setup.binding.bootstrap.secureSessionControl.send(it) }
                } else {
                    sendReject(
                        setup.binding,
                        setup.key.setupId,
                        SetupRejectStage.Path,
                        SetupRejectReason.DirectUnavailable,
                        setup.clientRequestHash,
                    )
                }
            }
            fail(setup, SessionSetupError.DirectUnavailable, true)
            return
        }
        val fallback = selectLan(setup)
        if (fallback == null) {
            fail(setup, SessionSetupError.NoUsablePath, true)
            return
        }
        counters.directFallbacks += 1
        setup.pathSelection = fallback
        if (sendPathFailure) {
            val message = SessionSetupMessage.PathFailure(
                SessionSetupHeader(SessionSetupMessageType.PathFailure, setup.key.setupId, 0),
                setup.binding.bootstrap.profileHash.copyOf(),
                requireNotNull(setup.pathAttemptId),
                reason,
            )
            SessionSetupCodec.encode(message)?.let { setup.binding.bootstrap.secureSessionControl.send(it) }
            allocateClientEndpointsAndSend(setup)
        } else if (setup.outboundBytes == null) {
            sendDirective(setup, PathDirective.UseLan, 0, null)
        }
    }

    private fun handlePathFailure(
        key: SetupKey,
        packet: DecodedSessionSetupPacket,
        failure: SessionSetupMessage.PathFailure,
    ) {
        val setup = active[key] ?: return
        semanticDuplicate(setup, packet)?.let { return }
        cancelRetry(setup)
        if (failure.pathAttemptId != setup.pathAttemptId || !failure.profileHash.contentEquals(setup.binding.bootstrap.profileHash)) {
            fail(setup, SessionSetupError.SetupConflict, true)
            return
        }
        setup.inboundHashes[SessionSetupMessageType.PathFailure] = packet.hash.copyOf()
        handleDirectFailure(setup, failure.reason, sendPathFailure = false)
    }

    private fun sendDirective(
        setup: ManagedSetup,
        directive: PathDirective,
        probePort: Int,
        attemptId: PathAttemptId?,
    ) {
        val activeKind = when {
            directive == PathDirective.UseLan -> NetworkPathKind.Lan
            setup.clientPreferences.pathPreference == PathPreferencePolicy.PreferLan -> NetworkPathKind.Lan
            else -> NetworkPathKind.Direct
        }
        val standbyKind = when {
            setup.clientPreferences.secondaryPathPolicy != SecondaryPathPolicy.KeepValidatedStandby -> null
            activeKind == NetworkPathKind.Direct && setup.binding.runtime.lanCandidate != null -> NetworkPathKind.Lan
            activeKind == NetworkPathKind.Lan && directive == PathDirective.AttemptDirect -> NetworkPathKind.Direct
            else -> null
        }
        val message = SessionSetupMessage.HostPathDirective(
            SessionSetupHeader(SessionSetupMessageType.HostPathDirective, setup.key.setupId, 0),
            setup.binding.bootstrap.profileHash.copyOf(),
            directive,
            activeKind,
            standbyKind,
            attemptId,
            probePort,
            if (directive == PathDirective.AttemptDirect) SessionSetupProtocol.DIRECT_VALIDATION_TIMEOUT_MS.toInt() else 0,
        )
        val bytes = SessionSetupCodec.encode(message) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        emitDebug(SessionSetupDebugEventKind.PathSelectionBuilt)
        sendFlight(setup, bytes, SessionSetupDebugEventKind.PathSelectionSent)
    }

    private fun allocateClientEndpointsAndSend(setup: ManagedSetup) {
        if (setup.binding.bootstrap.localRole != SessionRole.Client || setup.localEndpointLeases.isNotEmpty()) return
        val selection = setup.pathSelection ?: return
        val binding = PathSocketBinding(selection.active.pathId, selection.active.kind, selection.active.localAddress)
        val allocated = allocateEndpoints(setup, binding) ?: return
        setup.localEndpointLeases += allocated
        val message = SessionSetupMessage.ClientEndpointOffer(
            SessionSetupHeader(SessionSetupMessageType.ClientEndpointOffer, setup.key.setupId, 0),
            setup.binding.bootstrap.profileHash.copyOf(),
            selection.active.kind,
            allocated.map { ChannelEndpointOffer(it.channelKind, 0, it.localPort) },
        )
        val bytes = SessionSetupCodec.encode(message) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        setup.clientEndpointOffer = message
        setup.clientEndpointOfferHash = SessionSetupCodec.hash(bytes)
        setup.state = SessionSetupState.ClientEndpointOfferSent
        sendFlight(setup, bytes)
    }

    private fun handleEndpointOffer(
        key: SetupKey,
        binding: Binding,
        packet: DecodedSessionSetupPacket,
        offer: SessionSetupMessage.ClientEndpointOffer,
    ) {
        val setup = active[key] ?: return
        if (binding.bootstrap.localRole != SessionRole.Host) return
        semanticDuplicate(setup, packet)?.let { duplicate ->
            if (duplicate) setup.outboundBytes?.let { sendFlight(setup, it) }
            return
        }
        cancelRetry(setup)
        val selection = setup.pathSelection ?: return
        val offerError = SessionSetupPlanner.validateEndpointOffer(
            binding.bootstrap.profile,
            selection.active.kind,
            offer.endpoints,
        )
        if (offerError != null || !offer.profileHash.contentEquals(binding.bootstrap.profileHash)) {
            fail(setup, offerError ?: SessionSetupError.CapabilityProfileMismatch, true)
            return
        }
        setup.inboundHashes[SessionSetupMessageType.ClientEndpointOffer] = packet.hash.copyOf()
        setup.clientEndpointOffer = offer
        setup.clientEndpointOfferHash = packet.hash.copyOf()
        prepareHostProposal(setup)
    }

    private fun prepareHostProposal(setup: ManagedSetup) {
        emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationStarted)
        val policy = requireNotNull(setup.binding.policy)
        val selection = requireNotNull(setup.pathSelection)
        val offer = requireNotNull(setup.clientEndpointOffer)
        val binding = PathSocketBinding(selection.active.pathId, selection.active.kind, selection.active.localAddress)
        val localLeases = allocateEndpoints(setup, binding) ?: return
        setup.localEndpointLeases += localLeases
        val channelIds = SessionSetupPlanner.allocateChannelIds(setup.binding.bootstrap.profile.selectedChannels)
            ?: run {
                fail(setup, SessionSetupError.ChannelPlanInvalid, true)
                return
            }
        val descriptors = channelIds.mapIndexed { index, (kind, channelId) ->
            val recovery = policy.recoveryPolicy.forKind(kind)
            ChannelDescriptor(
                channelId,
                kind,
                kind.defaultDirection(),
                0,
                selection.active.pathId,
                localLeases[index].localPort,
                offer.endpoints[index].localPort,
                setup.binding.bootstrap.profile.secureDatagramBytes,
                recovery.recoveryFlags,
            )
        }
        val plan = SessionSetupPlanner.buildConfigurations(
            setup.binding.bootstrap.profile,
            policy.streamPreferences,
            descriptors,
            policy.recoveryPolicy,
        )
        if (plan !is SetupPlanResult.Success) {
            emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationFailed, (plan as SetupPlanResult.Failure).error)
            fail(setup, (plan as SetupPlanResult.Failure).error, true)
            return
        }
        val exact = setup.binding.runtime.exactValidator.validate(
            SessionRole.Host,
            setup.binding.bootstrap.profile,
            plan.configurations,
        )
        if (exact != SessionSetupError.None) {
            emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationFailed, exact)
            fail(setup, exact, true)
            return
        }
        emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationSucceeded)
        val prepared = prepareChannels(setup, descriptors, plan.configurations, selection.active.remoteAddress)
            ?: return
        setup.preparedChannels += prepared
        val proposal = SessionSetupMessage.HostConfigurationProposal(
            SessionSetupHeader(SessionSetupMessageType.HostConfigurationProposal, setup.key.setupId, 0),
            setup.binding.bootstrap.profileHash.copyOf(),
            requireNotNull(setup.clientEndpointOfferHash).copyOf(),
            1,
            selection.active.kind,
            selection.standby?.kind,
            selection.active.pathId,
            selection.standby?.pathId,
            descriptors,
            plan.configurations,
        )
        val bytes = SessionSetupCodec.encode(proposal) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        setup.proposal = proposal
        setup.proposalHash = SessionSetupCodec.hash(bytes)
        setup.proposalGeneration = 1
        setup.state = SessionSetupState.HostProposalSent
        counters.lastProposalGeneration = 1
        sendFlight(setup, bytes)
    }

    private fun handleProposal(
        key: SetupKey,
        packet: DecodedSessionSetupPacket,
        proposal: SessionSetupMessage.HostConfigurationProposal,
    ) {
        val setup = active[key] ?: return
        if (setup.binding.bootstrap.localRole != SessionRole.Client) return
        emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationStarted)
        setup.proposalHash?.let { previousHash ->
            if (previousHash.contentEquals(packet.hash)) {
                setup.outboundBytes?.let { sendFlight(setup, it) }
                return
            }
            if (proposal.proposalGeneration != setup.proposalGeneration + 1 ||
                setup.state != SessionSetupState.HostProposalSent
            ) {
                counters.semanticConflicts += 1
                fail(setup, SessionSetupError.SetupConflict, true)
                return
            }
        }
        cancelRetry(setup)
        val offer = setup.clientEndpointOffer ?: return
        val selection = setup.pathSelection ?: return
        val validation = SessionSetupPlanner.validateProposal(
            setup.binding.bootstrap.profile,
            offer,
            proposal,
            selection.active,
        )
        if (validation != null || !proposal.clientEndpointOfferHash.contentEquals(setup.clientEndpointOfferHash)) {
            emitDebug(
                SessionSetupDebugEventKind.ChannelPlanValidationFailed,
                validation ?: SessionSetupError.EndpointMismatch,
            )
            decline(setup, packet.hash, proposal.proposalGeneration, validation ?: SessionSetupError.EndpointMismatch)
            return
        }
        if (!proposalAllowed(setup.clientPreferences, proposal.configurations)) {
            emitDebug(
                SessionSetupDebugEventKind.ChannelPlanValidationFailed,
                SessionSetupError.ExactVideoConfigurationUnavailable,
            )
            decline(
                setup,
                packet.hash,
                proposal.proposalGeneration,
                SessionSetupError.ExactVideoConfigurationUnavailable,
            )
            return
        }
        val exact = setup.binding.runtime.exactValidator.validate(
            SessionRole.Client,
            setup.binding.bootstrap.profile,
            proposal.configurations,
        )
        if (exact != SessionSetupError.None) {
            emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationFailed, exact)
            decline(setup, packet.hash, proposal.proposalGeneration, exact)
            return
        }
        emitDebug(SessionSetupDebugEventKind.ChannelPlanValidationSucceeded)
        setup.pathSelection = PathSelection(
            selection.active.copy(pathId = proposal.activePathId),
            selection.standby?.let { standby -> proposal.standbyPathId?.let { standby.copy(pathId = it) } },
            selection.directAttempted,
            selection.directAttemptFailure,
        )
        val prepared = prepareChannels(setup, proposal.descriptors, proposal.configurations, selection.active.remoteAddress)
            ?: return
        setup.preparedChannels += prepared
        setup.proposal = proposal
        setup.proposalHash = packet.hash.copyOf()
        setup.proposalGeneration = proposal.proposalGeneration
        val accept = SessionSetupMessage.ClientConfigurationAccept(
            SessionSetupHeader(SessionSetupMessageType.ClientConfigurationAccept, key.setupId, 0),
            setup.binding.bootstrap.profileHash.copyOf(),
            requireNotNull(setup.clientEndpointOfferHash).copyOf(),
            packet.hash.copyOf(),
        )
        val bytes = SessionSetupCodec.encode(accept) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        setup.state = SessionSetupState.ClientAcceptSent
        sendFlight(setup, bytes)
    }

    private fun decline(setup: ManagedSetup, proposalHash: ByteArray, generation: Int, error: SessionSetupError) {
        val decline = SessionSetupMessage.ClientConfigurationDecline(
            SessionSetupHeader(SessionSetupMessageType.ClientConfigurationDecline, setup.key.setupId, 0),
            proposalHash.copyOf(),
            generation,
            error,
        )
        val bytes = SessionSetupCodec.encode(decline) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        if (error == SessionSetupError.ExactVideoConfigurationUnavailable &&
            setup.clientPreferences.video?.policy == VideoPreferencePolicy.OrderedAllowedModes &&
            generation < setup.clientPreferences.video.modes.size &&
            generation < SessionSetupProtocol.MAX_PROPOSAL_GENERATION
        ) {
            setup.proposalHash = proposalHash.copyOf()
            setup.proposalGeneration = generation
            setup.state = SessionSetupState.HostProposalSent
            sendFlight(setup, bytes)
        } else {
            setup.binding.bootstrap.secureSessionControl.send(bytes)
            fail(setup, error, true)
        }
    }

    private fun handleAccept(
        key: SetupKey,
        packet: DecodedSessionSetupPacket,
        accept: SessionSetupMessage.ClientConfigurationAccept,
    ) {
        completed[key]?.let { cached ->
            if (cached.matches(
                    accept,
                )
            ) {
                cached.transport.send(cached.commitBytes)
            } else {
                record(SessionSetupError.SetupConflict)
            }
            return
        }
        val setup = active[key] ?: return
        if (setup.binding.bootstrap.localRole != SessionRole.Host) return
        semanticDuplicate(setup, packet)?.let { duplicate ->
            if (duplicate) setup.outboundBytes?.let { sendFlight(setup, it) }
            return
        }
        cancelRetry(setup)
        if (!accept.profileHash.contentEquals(setup.binding.bootstrap.profileHash) ||
            !accept.clientEndpointOfferHash.contentEquals(setup.clientEndpointOfferHash) ||
            !accept.proposalHash.contentEquals(setup.proposalHash)
        ) {
            fail(setup, SessionSetupError.SetupConflict, true)
            return
        }
        if (setup.binding.bootstrap.admissionReservation?.renew(config.preparedBootstrapTtlMs) == false) {
            fail(setup, SessionSetupError.AdmissionExpired, true)
            return
        }
        val commit = SessionSetupMessage.HostCommit(
            SessionSetupHeader(SessionSetupMessageType.HostCommit, key.setupId, 0),
            setup.binding.bootstrap.profileHash.copyOf(),
            requireNotNull(setup.clientEndpointOfferHash).copyOf(),
            requireNotNull(setup.proposalHash).copyOf(),
        )
        val bytes = SessionSetupCodec.encode(commit) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        setup.inboundHashes[SessionSetupMessageType.ClientConfigurationAccept] = packet.hash.copyOf()
        if (!sendFlight(setup, bytes)) return
        active.remove(key)
        trimCompletedLocked()
        completed[key] = CompletedSetup(
            setup.binding.bootstrap.secureSessionControl,
            setup.binding.bootstrap.profileHash.copyOf(),
            requireNotNull(setup.clientEndpointOfferHash).copyOf(),
            requireNotNull(setup.proposalHash).copyOf(),
            bytes,
            now() + config.completionRetentionMs,
        )
        finish(setup)
    }

    private fun handleDecline(key: SetupKey, decline: SessionSetupMessage.ClientConfigurationDecline) {
        val setup = active[key] ?: return
        cancelRetry(setup)
        if (decline.proposalGeneration != setup.proposalGeneration || !decline.proposalHash.contentEquals(setup.proposalHash)) {
            fail(setup, SessionSetupError.SetupConflict, true)
            return
        }
        // Alternatives are bounded by the explicit Host preference; proposal generation never exceeds four.
        val policy = setup.binding.policy ?: run {
            fail(setup, decline.reason, true)
            return
        }
        val next = setup.proposalGeneration
        if (decline.reason != SessionSetupError.ExactVideoConfigurationUnavailable ||
            next >= policy.streamPreferences.video?.modes?.size.orZero() ||
            next >= SessionSetupProtocol.MAX_PROPOSAL_GENERATION
        ) {
            fail(setup, decline.reason, true)
            return
        }
        val preferences = policy.streamPreferences.copy(
            video = policy.streamPreferences.video?.let { video ->
                VideoStreamPreference(VideoPreferencePolicy.Exact, listOf(video.modes[next]))
            },
        )
        val proposal = requireNotNull(setup.proposal)
        val plan = SessionSetupPlanner.buildConfigurations(
            setup.binding.bootstrap.profile,
            preferences,
            proposal.descriptors,
            policy.recoveryPolicy,
        )
        if (plan !is SetupPlanResult.Success) {
            fail(setup, (plan as SetupPlanResult.Failure).error, true)
            return
        }
        val exact = setup.binding.runtime.exactValidator.validate(
            SessionRole.Host,
            setup.binding.bootstrap.profile,
            plan.configurations,
        )
        if (exact != SessionSetupError.None) {
            fail(setup, exact, true)
            return
        }
        setup.preparedChannels.forEach { channel ->
            channel.updateConfiguration(plan.configurations.filter { it.channelId == channel.descriptor.channelId })
        }
        val nextProposal = proposal.copy(proposalGeneration = next + 1, configurations = plan.configurations)
        val bytes = SessionSetupCodec.encode(nextProposal) ?: run {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return
        }
        setup.proposal = nextProposal
        setup.proposalGeneration = next + 1
        setup.proposalHash = SessionSetupCodec.hash(bytes)
        counters.lastProposalGeneration = next + 1
        sendFlight(setup, bytes)
    }

    private fun handleCommit(key: SetupKey, commit: SessionSetupMessage.HostCommit) {
        val setup = active[key] ?: return
        cancelRetry(setup)
        if (setup.binding.bootstrap.localRole != SessionRole.Client ||
            !commit.profileHash.contentEquals(setup.binding.bootstrap.profileHash) ||
            !commit.clientEndpointOfferHash.contentEquals(setup.clientEndpointOfferHash) ||
            !commit.proposalHash.contentEquals(setup.proposalHash)
        ) {
            fail(setup, SessionSetupError.SetupConflict, true)
            return
        }
        if (setup.binding.bootstrap.admissionReservation?.renew(config.preparedBootstrapTtlMs) == false) {
            fail(setup, SessionSetupError.AdmissionExpired, true)
            return
        }
        active.remove(key)
        finish(setup)
    }

    private fun prepareChannels(
        setup: ManagedSetup,
        descriptors: List<ChannelDescriptor>,
        configurations: List<SetupConfiguration>,
        remoteAddress: String,
    ): List<PreparedChannel>? {
        if (descriptors.size != setup.localEndpointLeases.size) {
            fail(setup, SessionSetupError.ChannelPlanInvalid, true)
            return null
        }
        val result = mutableListOf<PreparedChannel>()
        descriptors.forEachIndexed { index, descriptor ->
            val remotePort = if (setup.binding.bootstrap.localRole == SessionRole.Host) {
                descriptor.clientLocalPort
            } else {
                descriptor.hostLocalPort
            }
            val endpoint = remoteEndpoint(remoteAddress, remotePort)
            if (endpoint == null) {
                result.asReversed().forEach(PreparedChannel::close)
                fail(setup, SessionSetupError.EndpointMismatch, true)
                return null
            }
            val protection = SessionChannelProtectionLease.create(
                setup.binding.bootstrap.protection,
                descriptor.channelId,
                endpoint,
            )
            if (!protection.isSuccess) {
                result.asReversed().forEach(PreparedChannel::close)
                fail(setup, protection.error, true)
                return null
            }
            val channelProtection = requireNotNull(protection.protection)
            val channelConfigurations = configurations.filter { it.channelId == descriptor.channelId }
            val transport = setup.binding.runtime.transportPreparer.prepare(
                ChannelTransportPreparationRequest(
                    setup.binding.bootstrap.localRole,
                    descriptor,
                    setup.localEndpointLeases[index],
                    remoteAddress,
                    channelConfigurations,
                    setup.binding.bootstrap.protection,
                    channelProtection,
                ),
            )
            if (!transport.isSuccess || requireNotNull(transport.transport).started || !transport.transport.protectedRequired) {
                channelProtection.close()
                result.asReversed().forEach(PreparedChannel::close)
                fail(
                    setup,
                    transport.error.takeIf {
                        it != SessionSetupError.None
                    } ?: SessionSetupError.TransportPreparationFailed,
                    true,
                )
                return null
            }
            result += PreparedChannel(
                descriptor,
                setup.localEndpointLeases[index],
                remoteAddress,
                channelConfigurations,
                channelProtection,
                transport.transport,
            )
        }
        return result
    }

    private fun allocateEndpoints(setup: ManagedSetup, binding: PathSocketBinding): List<ChannelEndpointLease>? {
        emitDebug(SessionSetupDebugEventKind.SocketPathBindingStarted)
        val kinds = SessionSetupPlanner.selectedChannelKinds(setup.binding.bootstrap.profile.selectedChannels)
            ?: run {
                fail(setup, SessionSetupError.ChannelPlanInvalid, true)
                return null
            }
        val leases = mutableListOf<ChannelEndpointLease>()
        kinds.forEach { kind ->
            val allocated = setup.binding.runtime.endpointAllocator.allocate(binding, kind)
            if (!allocated.isSuccess || requireNotNull(allocated.lease).localPort !in 1..0xffff) {
                leases.asReversed().forEach(ChannelEndpointLease::close)
                fail(
                    setup,
                    allocated.error.takeIf { it != SessionSetupError.None } ?: SessionSetupError.EndpointAllocationFailed,
                    true,
                )
                return null
            }
            leases += allocated.lease
        }
        emitDebug(SessionSetupDebugEventKind.SocketPathBindingSucceeded)
        return leases
    }

    private fun selectLan(setup: ManagedSetup): PathSelection? {
        val lan = setup.binding.runtime.lanCandidate ?: return null
        if (setup.binding.bootstrap.profile.eligiblePathKinds and CapabilityBits.PATH_LAN == 0) return null
        val normalized = lan.copy(pathId = activePathId(), binding = lan.binding.copy(pathId = activePathId()))
        val active = normalized.toPlan(NetworkPathState.Active)
        return PathSelection(active, null, setup.directAttempted, setup.directFailure)
    }

    private fun selectPaths(
        setup: ManagedSetup,
        direct: SetupPathCandidate,
        directFailure: PathFailureReason?,
    ): PathSelection? {
        val lanRaw = setup.binding.runtime.lanCandidate
        val preference = setup.clientPreferences.pathPreference
        val activeDirect = preference != PathPreferencePolicy.PreferLan
        val normalizedDirectId = if (activeDirect) activePathId() else standbyPathId()
        val normalizedDirect = direct.copy(
            pathId = normalizedDirectId,
            binding = direct.binding.copy(pathId = normalizedDirectId),
        )
        val normalizedLan = lanRaw?.let { lan ->
            val id = if (activeDirect) standbyPathId() else activePathId()
            lan.copy(pathId = id, binding = lan.binding.copy(pathId = id))
        }
        val selected = SessionSetupPlanner.selectPaths(
            setup.binding.bootstrap.profile,
            preference,
            setup.clientPreferences.secondaryPathPolicy,
            normalizedLan,
            normalizedDirect,
            directFailure,
        )
        return (selected as? SetupPathSelectionResult.Success)?.let {
            PathSelection(it.active, it.standby, it.directAttempted, it.directAttemptFailure)
        }
    }

    private fun rebindDirectControlIfActive(setup: ManagedSetup, candidate: SetupPathCandidate): Boolean {
        if (setup.pathSelection?.active?.kind != NetworkPathKind.Direct) return true
        setup.binding.runtime.controlPathRebinder?.let { rebinder ->
            return rebinder.rebind(candidate, setup.directLease ?: return false) == SessionSetupError.None
        }
        val endpoint = candidate.controlEndpoint ?: return false
        return setup.binding.bootstrap.secureSessionControl.rebindRemoteEndpoint(endpoint) ==
            io.warpnect.session.security.SessionProtectionError.None
    }

    private fun sendFlight(
        setup: ManagedSetup,
        bytes: ByteArray,
        event: SessionSetupDebugEventKind? = null,
        retry: Boolean = false,
    ): Boolean {
        if (bytes.size > setup.binding.bootstrap.secureSessionControl.maxPayloadBytes) {
            fail(setup, SessionSetupError.InvalidConfig, true)
            return false
        }
        setup.outboundBytes = bytes
        setup.retryArmed = true
        if (retry) setup.retryIndex += 1 else setup.retryIndex = 0
        val index = setup.retryIndex.coerceAtMost(SessionSetupProtocol.RETRY_DELAYS_MS.lastIndex)
        setup.nextRetryAtMs = now() + SessionSetupProtocol.RETRY_DELAYS_MS[index]
        setup.retryTimer?.close()
        setup.retryTimer = timer?.schedule(SessionSetupProtocol.RETRY_DELAYS_MS[index]) {
            synchronized(lock) {
                val current = active[setup.key]
                if (!closed && current === setup) advanceSetup(setup, now())
            }
        }
        val sent = setup.binding.bootstrap.secureSessionControl.send(bytes)
        if (!sent.isSuccess) {
            fail(setup, SessionSetupError.SecureControlFailure, true)
            return false
        }
        event?.let(::emitDebug)
        return true
    }

    private fun semanticDuplicate(setup: ManagedSetup, packet: DecodedSessionSetupPacket): Boolean? {
        val type = packet.message.header.messageType
        val previous = setup.inboundHashes[type] ?: return null
        if (!previous.contentEquals(packet.hash)) {
            counters.semanticConflicts += 1
            fail(setup, SessionSetupError.SetupConflict, true)
            return false
        }
        return true
    }

    private fun sendReject(
        binding: Binding,
        setupId: SessionSetupId,
        stage: SetupRejectStage,
        reason: SetupRejectReason,
        hash: ByteArray,
    ) {
        val message = SessionSetupMessage.Reject(
            SessionSetupHeader(SessionSetupMessageType.SetupReject, setupId, 0),
            stage,
            reason,
            hash,
        )
        SessionSetupCodec.encode(message)?.let { binding.bootstrap.secureSessionControl.send(it) }
    }

    private fun finish(setup: ManagedSetup) {
        cancelTimers(setup)
        val selection = requireNotNull(setup.pathSelection)
        val now = now()
        val prepared = PreparedSessionBootstrap(
            setup.binding.bootstrap.sessionId,
            setup.binding.bootstrap.generation,
            setup.binding.bootstrap.localDeviceId,
            setup.binding.bootstrap.remoteDeviceId,
            setup.binding.bootstrap.localRole,
            setup.binding.bootstrap.remoteRole,
            setup.binding.bootstrap.profile,
            setup.binding.bootstrap.profileHash.copyOf(),
            selection.active,
            selection.standby,
            setup.preparedChannels.toList(),
            setup.binding.bootstrap.secureSessionControl,
            setup.binding.bootstrap.protection,
            setup.binding.bootstrap.admissionReservation,
            now,
            now + config.preparedBootstrapTtlMs,
            setup.directLease,
            requireNotNull(setup.proposalHash).copyOf(),
            if (selection.active.kind == NetworkPathKind.Direct) {
                setup.directCandidate?.controlEndpoint ?: setup.binding.bootstrap.endpoint
            } else {
                setup.binding.bootstrap.endpoint
            },
            buildMap {
                selection.active.takeIf { it.kind == NetworkPathKind.Lan }?.let {
                    put(it.pathId, setup.binding.bootstrap.endpoint)
                }
                selection.standby?.takeIf { it.kind == NetworkPathKind.Lan }?.let {
                    put(it.pathId, setup.binding.bootstrap.endpoint)
                }
                setup.directCandidate?.controlEndpoint?.let { endpoint ->
                    selection.active.takeIf { it.kind == NetworkPathKind.Direct }?.let { put(it.pathId, endpoint) }
                    selection.standby?.takeIf { it.kind == NetworkPathKind.Direct }?.let { put(it.pathId, endpoint) }
                }
            },
        )
        setup.preparedChannels.clear()
        setup.localEndpointLeases.clear()
        setup.directLease = null
        timer?.schedule(config.preparedBootstrapTtlMs, prepared::close)?.let(prepared::armExpiry)
        setup.state = SessionSetupState.Prepared
        counters.completed += 1
        counters.channelCount = prepared.channels.size
        counters.lastSessionId = prepared.sessionId
        counters.lastPeerDeviceId = prepared.remoteDeviceId
        counters.lastActivePath = prepared.activePath.kind
        counters.lastStandbyPath = prepared.standbyPath?.kind
        counters.lastDurationMs = (now - setup.startedAtMs).coerceAtLeast(0L)
        record(SessionSetupError.None)
        emitDebug(SessionSetupDebugEventKind.Committed)
        onCompleted(prepared)
    }

    private fun fail(setup: ManagedSetup?, error: SessionSetupError, releaseReservation: Boolean) {
        if (setup == null) {
            record(error)
            return
        }
        active.remove(setup.key)
        cancelTimers(setup)
        setup.binding.runtime.directCoordinator?.cancel(setup.key.setupId)
        setup.preparedChannels.asReversed().forEach(PreparedChannel::close)
        setup.preparedChannels.clear()
        setup.localEndpointLeases.asReversed().forEach(ChannelEndpointLease::close)
        setup.localEndpointLeases.clear()
        setup.directLease?.close()
        setup.directLease = null
        if (releaseReservation) setup.binding.bootstrap.admissionReservation?.close()
        setup.state = when (error) {
            SessionSetupError.SetupTimeout -> SessionSetupState.TimedOut
            SessionSetupError.Closed -> SessionSetupState.Closed
            else -> SessionSetupState.Failed
        }
        record(error)
    }

    private fun expireCompletedLocked() {
        val now = now()
        completed.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private fun trimCompletedLocked() {
        while (completed.size >= SessionSetupProtocol.COMPLETION_CACHE_CAPACITY) {
            completed.remove(completed.entries.first().key)
        }
    }

    private fun advanceSetup(setup: ManagedSetup, now: Long) {
        if (now - setup.startedAtMs >= setup.timeoutMs) {
            counters.timeouts += 1
            fail(setup, SessionSetupError.SetupTimeout, true)
        } else if (setup.retryArmed && setup.outboundBytes != null && now >= setup.nextRetryAtMs) {
            if (setup.retryIndex >= SessionSetupProtocol.RETRY_DELAYS_MS.size) {
                counters.timeouts += 1
                fail(setup, SessionSetupError.SetupTimeout, true)
            } else {
                counters.retries += 1
                sendFlight(setup, requireNotNull(setup.outboundBytes), retry = true)
            }
        }
    }

    private fun scheduleOverallTimeout(setup: ManagedSetup) {
        setup.overallTimer?.close()
        setup.overallTimer = timer?.schedule(setup.timeoutMs) {
            synchronized(lock) {
                val current = active[setup.key]
                if (!closed && current === setup) advanceSetup(setup, now())
            }
        }
    }

    private fun cancelRetry(setup: ManagedSetup) {
        setup.retryArmed = false
        setup.retryTimer?.close()
        setup.retryTimer = null
        setup.nextRetryAtMs = Long.MAX_VALUE
    }

    private fun cancelTimers(setup: ManagedSetup) {
        cancelRetry(setup)
        setup.overallTimer?.close()
        setup.overallTimer = null
    }

    private fun timeoutFor(preference: PathPreferencePolicy): Long = when (preference) {
        PathPreferencePolicy.LanOnly -> config.lanTimeoutMs
        else -> config.directTimeoutMs
    }

    private fun SessionSetupRuntime.isValid(): Boolean = lanCandidate?.isValid() != false

    private fun remoteEndpoint(address: String, port: Int): HandshakeTransportEndpoint? = try {
        HandshakeTransportEndpoint.from(InetAddress.getByName(address).address, port)
    } catch (_: Exception) {
        null
    }

    private fun proposalAllowed(
        preferences: SessionSetupPreferences,
        configurations: List<SetupConfiguration>,
    ): Boolean {
        val video = configurations.filterIsInstance<SetupConfiguration.Video>().singleOrNull()
        if (video != null && preferences.video?.modes?.contains(video.mode) != true) return false
        val system = configurations.filterIsInstance<SetupConfiguration.SystemAudio>().singleOrNull()
        if (system != null && preferences.systemAudio?.modes?.contains(system.mode) != true) return false
        val microphone = configurations.filterIsInstance<SetupConfiguration.MicrophoneAudio>().singleOrNull()
        if (microphone != null && preferences.microphoneAudio?.modes?.contains(microphone.mode) != true) return false
        return true
    }

    private fun SetupPathCandidate.toPlan(state: NetworkPathState): SessionPathPlan =
        SessionPathPlan(pathId, kind, state, binding.localAddress, remoteAddress)

    private fun SessionChannelKind.defaultDirection() = when (this) {
        SessionChannelKind.Video,
        SessionChannelKind.SystemAudio,
        -> io.warpnect.session.SessionChannelDirection.HostToClient
        SessionChannelKind.MicrophoneAudio,
        SessionChannelKind.Input,
        -> io.warpnect.session.SessionChannelDirection.ClientToHost
        SessionChannelKind.Telemetry,
        SessionChannelKind.Control,
        -> io.warpnect.session.SessionChannelDirection.Bidirectional
    }

    private fun SetupRejectReason.toLocalError(): SessionSetupError = when (this) {
        SetupRejectReason.Busy -> SessionSetupError.SetupBusy
        SetupRejectReason.Conflict -> SessionSetupError.SetupConflict
        SetupRejectReason.DirectUnavailable -> SessionSetupError.DirectUnavailable
        SetupRejectReason.Malformed -> SessionSetupError.MalformedMessage
        SetupRejectReason.Incompatible -> SessionSetupError.CapabilityProfileMismatch
        SetupRejectReason.PreparationFailed -> SessionSetupError.TransportPreparationFailed
    }

    private fun activePathId(): PathId = PathId.requireValid(1u)
    private fun standbyPathId(): PathId = PathId.requireValid(2u)
    private fun Int?.orZero(): Int = this ?: 0
    private fun now(): Long = clock.nowMs().coerceAtLeast(0L)
    private fun record(error: SessionSetupError): SessionSetupError {
        counters.lastError = error
        if (error != SessionSetupError.None) emitDebug(SessionSetupDebugEventKind.Failed, error)
        return error
    }

    private fun emitDebug(kind: SessionSetupDebugEventKind, error: SessionSetupError? = null) {
        debugObserver.onEvent(SessionSetupDebugEvent(kind, error))
    }

    private data class Binding(
        val bootstrap: NegotiatedSessionBootstrap,
        val runtime: SessionSetupRuntime,
        val policy: HostSessionSetupPolicy?,
    )

    private data class SetupKey(val sessionId: SessionId, val setupId: SessionSetupId)

    private class ManagedSetup(
        val key: SetupKey,
        val binding: Binding,
        var state: SessionSetupState,
        val startedAtMs: Long,
        val timeoutMs: Long,
        val clientPreferences: SessionSetupPreferences,
        val clientRequestHash: ByteArray,
    ) {
        val inboundHashes = LinkedHashMap<SessionSetupMessageType, ByteArray>()
        val localEndpointLeases = mutableListOf<ChannelEndpointLease>()
        val preparedChannels = mutableListOf<PreparedChannel>()
        var pathAttemptId: PathAttemptId? = null
        var directAttempted = false
        var directFailure: PathFailureReason? = null
        var directCandidate: SetupPathCandidate? = null
        var directLease: DirectPathLease? = null
        var pathSelection: PathSelection? = null
        var clientEndpointOffer: SessionSetupMessage.ClientEndpointOffer? = null
        var clientEndpointOfferHash: ByteArray? = null
        var proposal: SessionSetupMessage.HostConfigurationProposal? = null
        var proposalHash: ByteArray? = null
        var proposalGeneration = 0
        var outboundBytes: ByteArray? = null
        var nextRetryAtMs = Long.MAX_VALUE
        var retryIndex = 0
        var retryArmed = false
        var retryTimer: AutoCloseable? = null
        var overallTimer: AutoCloseable? = null
    }

    private data class PathSelection(
        val active: SessionPathPlan,
        val standby: SessionPathPlan?,
        val directAttempted: Boolean,
        val directAttemptFailure: PathFailureReason?,
    )

    private data class CompletedSetup(
        val transport: SecureSessionControlTransport,
        val profileHash: ByteArray,
        val endpointOfferHash: ByteArray,
        val proposalHash: ByteArray,
        val commitBytes: ByteArray,
        val expiresAtMs: Long,
    ) {
        fun matches(accept: SessionSetupMessage.ClientConfigurationAccept): Boolean =
            accept.profileHash.contentEquals(profileHash) &&
                accept.clientEndpointOfferHash.contentEquals(endpointOfferHash) &&
                accept.proposalHash.contentEquals(proposalHash)
    }

    private data class Counters(
        var completed: Long = 0,
        var retries: Long = 0,
        var timeouts: Long = 0,
        var malformedMessages: Long = 0,
        var semanticConflicts: Long = 0,
        var directAttempts: Long = 0,
        var directFallbacks: Long = 0,
        var channelCount: Int = 0,
        var lastSetupId: SessionSetupId? = null,
        var lastSessionId: SessionId? = null,
        var lastPeerDeviceId: DeviceId? = null,
        var lastActivePath: NetworkPathKind? = null,
        var lastStandbyPath: NetworkPathKind? = null,
        var lastProposalGeneration: Int? = null,
        var lastDurationMs: Long? = null,
        var lastError: SessionSetupError = SessionSetupError.None,
    )
}
