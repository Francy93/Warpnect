package io.warpnect.session.integration

import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveryPresenceStatus
import io.warpnect.session.lifecycle.DisconnectReason
import io.warpnect.session.setup.PreparedSessionBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RFC-005I per-logical-session composition root. The driver owns RFC-005B through RFC-005G
 * controller calls; this class owns their ordered handoff to RFC-005H and real local pipelines.
 */
class SecureSessionCoordinator(
    private val localRole: SessionRole,
    private val phaseDriver: SecureSessionPhaseDriver,
    private val pipelineFactory: SessionPipelineFactory,
    private val lifecycleFactory: SessionLifecycleSessionFactory,
    private val hostRegistry: HostSessionRuntimeRegistry? = null,
    private val debugObserver: SessionStartupDebugObserver = SessionStartupDebugObserver.None,
    private val hostPreparedSessionDispatcher: SessionControlDispatcher = SessionControlDispatcher { action ->
        action()
        true
    },
) : AutoCloseable {
    private val lock = Any()
    private var state = SecureSessionCoordinatorState.Idle
    private var selectedRequest: SecureSessionConnectRequest? = null
    private var pairingVerificationPrompt: io.warpnect.session.pairing.PairingVerificationPrompt? = null
    private var runtime: RunningSessionRuntime? = null

    /** Generation-N runtime retained solely until RFC-005H confirms a fresh prepared bootstrap. */
    private var reconnectingRuntime: RunningSessionRuntime? = null
    private var currentToken = 0L
    private var lastStage: SecureSessionIntegrationStage? = null
    private var lastError = SecureSessionIntegrationError.None
    private var closed = false
    private var discoverySnapshotPublishedListener: ((Int) -> Unit)? = null

    private val _snapshot = MutableStateFlow(snapshotLocked())
    val snapshot: StateFlow<SecureSessionCoordinatorSnapshot> = _snapshot.asStateFlow()

    init {
        phaseDriver.setDiscoveryUpdateListener(::onDiscoveryUpdated)
    }

    fun startDiscovery(): SessionIntegrationResult {
        val permitted = synchronized(lock) {
            if (closed) return@synchronized false
            if (state !in setOf(SecureSessionCoordinatorState.Idle, SecureSessionCoordinatorState.Discovering)) {
                lastError = SecureSessionIntegrationError.Busy
                publishLocked()
                return@synchronized false
            }
            state = SecureSessionCoordinatorState.Discovering
            lastStage = SecureSessionIntegrationStage.Discovery
            lastError = SecureSessionIntegrationError.None
            publishLocked()
            true
        }
        if (!permitted) return result(lastError())
        val error = phaseDriver.startDiscovery(localRole)
        if (error != SecureSessionIntegrationError.None) fail(0L, SecureSessionIntegrationStage.Discovery, error)
        return result(error)
    }

    /** Called by the existing Phase 5 control scheduler; it never starts a per-session worker. */
    fun advance() {
        val discoveryActive = synchronized(lock) {
            if (closed || state in setOf(
                    SecureSessionCoordinatorState.Closed,
                    SecureSessionCoordinatorState.Failed,
                )
            ) {
                return
            }
            state == SecureSessionCoordinatorState.Discovering
        }
        phaseDriver.advance()
        if (discoveryActive) {
            val discoveryFailure = phaseDriver.discoveryFailure()
            if (discoveryFailure != SecureSessionIntegrationError.None) {
                fail(0L, SecureSessionIntegrationStage.Discovery, discoveryFailure)
                return
            }
            synchronized(lock) { publishLocked() }
        }
        synchronized(lock) { runtime }?.advance()
    }

    /** Leaves client browsing without terminally closing the reusable application-scoped controller. */
    fun cancelDiscovery(): SessionIntegrationResult {
        if (localRole != SessionRole.Client) return result(SecureSessionIntegrationError.InvalidPresence)
        val permitted = synchronized(lock) {
            if (closed || state !in setOf(
                    SecureSessionCoordinatorState.Discovering,
                    SecureSessionCoordinatorState.Failed,
                )
            ) {
                false
            } else {
                ++currentToken
                state = SecureSessionCoordinatorState.Stopping
                publishLocked()
                true
            }
        }
        if (!permitted) {
            return result(
                if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy,
            )
        }
        phaseDriver.stopDiscovery()
        synchronized(lock) {
            if (!closed) {
                selectedRequest = null
                pairingVerificationPrompt = null
                state = SecureSessionCoordinatorState.Idle
                lastStage = SecureSessionIntegrationStage.Discovery
                lastError = SecureSessionIntegrationError.None
                publishLocked()
            }
        }
        return result(SecureSessionIntegrationError.None)
    }

    /** The application owner republishes its immutable UI snapshot after an accepted cache update. */
    fun setDiscoverySnapshotPublishedListener(listener: ((Int) -> Unit)?) = synchronized(lock) {
        discoverySnapshotPublishedListener = listener
    }

    /** Exposes only the bounded ephemeral discovery cache needed by the normal Client chooser. */
    fun discoveredPresences(): List<io.warpnect.session.discovery.DiscoveredPresence> =
        phaseDriver.discoveredPresences()

    /** Starts a Client connection solely from RFC-005B presence; no manual IP/port exists here. */
    fun connect(request: SecureSessionConnectRequest): SessionIntegrationResult {
        if (localRole != SessionRole.Client || request.presence.offeredRole != SessionRole.Host ||
            request.presence.status != DiscoveryPresenceStatus.Usable
        ) {
            return result(SecureSessionIntegrationError.InvalidPresence)
        }
        val token = synchronized(lock) {
            if (closed) return@synchronized null
            if (state !in setOf(SecureSessionCoordinatorState.Idle, SecureSessionCoordinatorState.Discovering)) {
                lastError = SecureSessionIntegrationError.Busy
                publishLocked()
                return@synchronized null
            }
            selectedRequest = request
            pairingVerificationPrompt = null
            state = SecureSessionCoordinatorState.Connecting
            lastStage = SecureSessionIntegrationStage.Authentication
            lastError = SecureSessionIntegrationError.None
            ++currentToken
        }
        if (token == null) return result(lastError())
        synchronized(lock) { publishLocked() }
        val error = phaseDriver.beginConnection(request, listenerFor(token))
        if (error != SecureSessionIntegrationError.None) {
            fail(
                token,
                SecureSessionIntegrationStage.Authentication,
                error,
            )
        }
        return result(error)
    }

    /** A selected-peer connection begins RFC-005C only after RFC-005D proves trust is absent. */
    fun beginExplicitPairing(): SessionIntegrationResult {
        val requestAndToken = synchronized(lock) {
            val request = selectedRequest
            if (closed || state != SecureSessionCoordinatorState.PairingRequired || request == null) {
                return@synchronized null
            }
            state = SecureSessionCoordinatorState.Pairing
            lastStage = SecureSessionIntegrationStage.Pairing
            pairingVerificationPrompt = null
            publishLocked()
            request to currentToken
        } ?: return result(if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy)
        val error = phaseDriver.beginExplicitPairing(requestAndToken.first, listenerFor(requestAndToken.second))
        if (error != SecureSessionIntegrationError.None) {
            fail(
                requestAndToken.second,
                SecureSessionIntegrationStage.Pairing,
                error,
            )
        }
        return result(error)
    }

    fun approvePairing(): SessionIntegrationResult {
        val token = pairingDecisionToken()
            ?: return result(if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy)
        val error = phaseDriver.approvePairing()
        if (error != SecureSessionIntegrationError.None) fail(token, SecureSessionIntegrationStage.Pairing, error)
        return result(error)
    }

    /** Preserves RFC-005C's explicit user rejection rather than treating it as a local UI close. */
    fun rejectPairing(): SessionIntegrationResult {
        val token = pairingDecisionToken()
            ?: return result(if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy)
        val error = phaseDriver.rejectPairing()
        if (error != SecureSessionIntegrationError.None) fail(token, SecureSessionIntegrationStage.Pairing, error)
        return result(error)
    }

    /**
     * RFC-005I Host handoff. The responder controllers have already completed RFC-005D through
     * RFC-005G; from here Host and Client share the exact lifecycle and pipeline transaction.
     */
    fun acceptPreparedHostSession(bootstrap: PreparedSessionBootstrap): SessionIntegrationResult {
        if (localRole != SessionRole.Host || bootstrap.localRole != SessionRole.Host) {
            bootstrap.close()
            return result(SecureSessionIntegrationError.InvalidPresence)
        }
        val intake = synchronized(lock) {
            if (closed || state !in setOf(
                    SecureSessionCoordinatorState.Idle,
                    SecureSessionCoordinatorState.Discovering,
                    SecureSessionCoordinatorState.Recovering,
                )
            ) {
                null
            } else {
                val prior = if (state == SecureSessionCoordinatorState.Recovering) reconnectingRuntime else null
                state = SecureSessionCoordinatorState.ConfiguringSession
                lastStage = SecureSessionIntegrationStage.Setup
                lastError = SecureSessionIntegrationError.None
                HostPreparedIntake(++currentToken, prior)
            }
        }
        if (intake == null) {
            bootstrap.close()
            return result(if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy)
        }
        synchronized(lock) { publishLocked() }
        if (
            !hostPreparedSessionDispatcher.dispatch {
                consumePreparedHostSession(intake, bootstrap)
            }
        ) {
            bootstrap.close()
            fail(
                intake.token,
                SecureSessionIntegrationStage.Lifecycle,
                SecureSessionIntegrationError.LifecycleStartFailed,
            )
            return result(SecureSessionIntegrationError.LifecycleStartFailed)
        }
        return result(SecureSessionIntegrationError.None)
    }

    /** User cancellation is terminal for this coordinator and cannot leave later callbacks alive. */
    fun disconnect(reason: DisconnectReason = DisconnectReason.UserRequested): SessionIntegrationResult {
        val owned = synchronized(lock) {
            if (closed) return@synchronized null
            state = SecureSessionCoordinatorState.Stopping
            ++currentToken
            reconnectingRuntime = null
            runtime.also { runtime = null }
        }
        if (owned != null) {
            hostRegistry?.remove(owned.sessionId, owned.generation)
            owned.close(reason)
        }
        phaseDriver.cancel()
        synchronized(lock) {
            state = if (localRole == SessionRole.Host) {
                SecureSessionCoordinatorState.Discovering
            } else {
                SecureSessionCoordinatorState.Closed
            }
            lastError = SecureSessionIntegrationError.None
            publishLocked()
        }
        return result(SecureSessionIntegrationError.None)
    }

    /** Disables Host advertising/responder ownership while retaining the application composition. */
    fun stopHostReadiness(): SessionIntegrationResult {
        if (localRole != SessionRole.Host) return result(SecureSessionIntegrationError.InvalidPresence)
        val disconnected = disconnect(DisconnectReason.HostClosing)
        phaseDriver.stopDiscovery()
        synchronized(lock) {
            if (!closed) {
                state = SecureSessionCoordinatorState.Idle
                lastStage = SecureSessionIntegrationStage.Discovery
                lastError = SecureSessionIntegrationError.None
                publishLocked()
            }
        }
        return disconnected
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            discoverySnapshotPublishedListener = null
            state = SecureSessionCoordinatorState.Stopping
            ++currentToken
            reconnectingRuntime = null
            runtime.also { runtime = null }
        }
        owned?.let {
            hostRegistry?.remove(it.sessionId, it.generation)
            it.close(DisconnectReason.ApplicationStopping)
        }
        phaseDriver.setDiscoveryUpdateListener(null)
        phaseDriver.cancel()
        phaseDriver.stopDiscovery()
        phaseDriver.close()
        synchronized(lock) {
            state = SecureSessionCoordinatorState.Closed
            publishLocked()
        }
    }

    private fun listenerFor(token: Long): SecureSessionPhaseListener = object : SecureSessionPhaseListener {
        override fun onPairingRequired() {
            synchronized(lock) {
                if (!isCurrentLocked(token)) return
                state = SecureSessionCoordinatorState.PairingRequired
                lastStage = SecureSessionIntegrationStage.Pairing
                lastError = SecureSessionIntegrationError.PairingRequired
                publishLocked()
            }
        }

        override fun onPairingVerificationPrompt(prompt: io.warpnect.session.pairing.PairingVerificationPrompt) {
            synchronized(lock) {
                if (!isCurrentLocked(token)) return
                pairingVerificationPrompt = prompt
                state = SecureSessionCoordinatorState.Pairing
                lastStage = SecureSessionIntegrationStage.Pairing
                lastError = SecureSessionIntegrationError.None
                publishLocked()
            }
        }

        override fun onPairingCompleted() {
            val request = synchronized(lock) {
                if (!isCurrentLocked(token)) return
                selectedRequest
            } ?: return
            synchronized(lock) {
                if (!isCurrentLocked(token)) return
                state = SecureSessionCoordinatorState.Connecting
                lastStage = SecureSessionIntegrationStage.Authentication
                lastError = SecureSessionIntegrationError.None
                pairingVerificationPrompt = null
                publishLocked()
            }
            val error = phaseDriver.beginConnection(request, listenerFor(token))
            if (error != SecureSessionIntegrationError.None) {
                fail(
                    token,
                    SecureSessionIntegrationStage.Authentication,
                    error,
                )
            }
        }

        override fun onAuthenticated(bootstrap: io.warpnect.session.handshake.AuthenticatedSessionBootstrap) {
            emitDebug(SessionStartupDebugEventKind.Authenticated)
            if (!setState(token, SecureSessionCoordinatorState.Securing, SecureSessionIntegrationStage.Security)) {
                bootstrap.rootSecret.close()
                bootstrap.admissionReservation?.close()
                return
            }
            val secure = phaseDriver.createSecureCapabilityBootstrap(bootstrap)
            if (!secure.isSuccess) {
                fail(token, SecureSessionIntegrationStage.Security, secure.error)
                return
            }
            emitDebug(SessionStartupDebugEventKind.SecureControlReady)
            val request = synchronized(lock) { selectedRequest } ?: run {
                fail(token, SecureSessionIntegrationStage.Security, SecureSessionIntegrationError.Cancelled)
                return
            }
            if (!setState(
                    token,
                    SecureSessionCoordinatorState.NegotiatingCapabilities,
                    SecureSessionIntegrationStage.Capabilities,
                )
            ) {
                return
            }
            emitDebug(SessionStartupDebugEventKind.CapabilityNegotiationStarted)
            val error = phaseDriver.beginCapabilities(requireNotNull(secure.bootstrap), request, listenerFor(token))
            if (error != SecureSessionIntegrationError.None) {
                fail(
                    token,
                    SecureSessionIntegrationStage.Capabilities,
                    error,
                )
            }
        }

        override fun onCapabilitiesNegotiated(bootstrap: io.warpnect.session.capability.NegotiatedSessionBootstrap) {
            emitDebug(SessionStartupDebugEventKind.CapabilityNegotiated)
            val request = synchronized(lock) {
                if (!isCurrentLocked(token)) return
                selectedRequest
            } ?: return
            if (!setState(
                    token,
                    SecureSessionCoordinatorState.ConfiguringSession,
                    SecureSessionIntegrationStage.Setup,
                )
            ) {
                return
            }
            emitDebug(SessionStartupDebugEventKind.SessionSetupStarted)
            val error = phaseDriver.beginSetup(bootstrap, request, listenerFor(token))
            if (error != SecureSessionIntegrationError.None) fail(token, SecureSessionIntegrationStage.Setup, error)
        }

        override fun onPrepared(bootstrap: PreparedSessionBootstrap) {
            emitDebug(SessionStartupDebugEventKind.SessionPrepared)
            consumePreparedOrReconnect(token, bootstrap)
        }

        override fun onFailed(stage: SecureSessionIntegrationStage, error: SecureSessionIntegrationError) {
            fail(token, stage, error)
        }
    }

    private fun consumePrepared(token: Long, bootstrap: PreparedSessionBootstrap) {
        if (bootstrap.channels.any { it.descriptor.kind == SessionChannelKind.Video }) {
            emitDebug(SessionStartupDebugEventKind.VideoChannelReady)
        }
        emitDebug(SessionStartupDebugEventKind.MediaStartRequested)
        if (!setState(token, SecureSessionCoordinatorState.Starting, SecureSessionIntegrationStage.Lifecycle)) {
            bootstrap.close()
            return
        }
        val pipelineResult = pipelineFactory.create(bootstrap)
        if (!pipelineResult.isSuccess) {
            bootstrap.close()
            fail(token, stageFor(pipelineResult.error), pipelineResult.error)
            return
        }
        val pipeline = SessionPipelineRuntime(bootstrap, pipelineResult.components)
        val lifecycleResult = lifecycleFactory.create(bootstrap, pipeline, lifecycleListenerFor(token))
        if (!lifecycleResult.isSuccess) {
            pipeline.close()
            bootstrap.close()
            fail(token, SecureSessionIntegrationStage.Lifecycle, lifecycleResult.error)
            return
        }
        val lifecycle = requireNotNull(lifecycleResult.lifecycle)
        val lifecycleError = lifecycle.start()
        if (lifecycleError != SecureSessionIntegrationError.None) {
            lifecycle.close()
            pipeline.close()
            fail(token, SecureSessionIntegrationStage.Lifecycle, lifecycleError)
            return
        }
        val pipelineError = pipeline.start()
        if (pipelineError != SecureSessionIntegrationError.None) {
            lifecycle.gracefulDisconnect(DisconnectReason.FatalError)
            lifecycle.close()
            pipeline.close()
            fail(token, stageFor(pipelineError), pipelineError)
            return
        }
        emitDebug(SessionStartupDebugEventKind.MediaStartAccepted)
        val candidate = RunningSessionRuntime(lifecycle, pipeline)
        val registryError = if (localRole == SessionRole.Host) {
            hostRegistry?.register(candidate) ?: SecureSessionIntegrationError.RegistryCapacityExceeded
        } else {
            SecureSessionIntegrationError.None
        }
        if (registryError != SecureSessionIntegrationError.None) {
            candidate.close(DisconnectReason.FatalError)
            fail(token, SecureSessionIntegrationStage.Lifecycle, registryError)
            return
        }
        synchronized(lock) {
            if (!isCurrentLocked(token)) {
                if (localRole == SessionRole.Host) hostRegistry?.remove(candidate.sessionId, candidate.generation)
                candidate.close(DisconnectReason.SupersededGeneration)
                return
            }
            runtime = candidate
            state = SecureSessionCoordinatorState.Running
            lastStage = null
            lastError = SecureSessionIntegrationError.None
            publishLocked()
        }
        emitDebug(SessionStartupDebugEventKind.RuntimeRunning)
        phaseDriver.refreshHostAvailability()
    }

    private fun consumePreparedOrReconnect(token: Long, bootstrap: PreparedSessionBootstrap) {
        val previous = synchronized(lock) {
            if (!isCurrentLocked(token)) return@synchronized null
            reconnectingRuntime
        }
        if (previous == null) {
            consumePrepared(token, bootstrap)
            return
        }
        if (previous.lifecycle.acceptFreshGeneration(bootstrap) != SecureSessionIntegrationError.None) {
            bootstrap.close()
            fail(token, SecureSessionIntegrationStage.Lifecycle, SecureSessionIntegrationError.LifecycleStartFailed)
            return
        }
        synchronized(lock) {
            if (!isCurrentLocked(token)) {
                bootstrap.close()
                return
            }
            hostRegistry?.remove(previous.sessionId, previous.generation)
            previous.disposeForReconnect()
            reconnectingRuntime = null
            runtime = null
        }
        consumePrepared(token, bootstrap)
    }

    /** Keeps bootstrap UDP readers free while Host capture and encoder startup run on Session control ownership. */
    private fun consumePreparedHostSession(intake: HostPreparedIntake, bootstrap: PreparedSessionBootstrap) {
        if (!synchronized(lock) { isCurrentLocked(intake.token) }) {
            bootstrap.close()
            return
        }
        val previous = intake.previous
        if (previous != null) {
            if (previous.lifecycle.acceptFreshGeneration(bootstrap) != SecureSessionIntegrationError.None) {
                bootstrap.close()
                fail(
                    intake.token,
                    SecureSessionIntegrationStage.Lifecycle,
                    SecureSessionIntegrationError.LifecycleStartFailed,
                )
                return
            }
            synchronized(lock) {
                hostRegistry?.remove(previous.sessionId, previous.generation)
                previous.disposeForReconnect()
                reconnectingRuntime = null
                runtime = null
            }
        }
        consumePrepared(intake.token, bootstrap)
    }

    private fun lifecycleListenerFor(token: Long): SessionLifecycleRuntimeListener =
        object : SessionLifecycleRuntimeListener {
            override fun onRecovering() {
                setState(token, SecureSessionCoordinatorState.Recovering, SecureSessionIntegrationStage.Lifecycle)
                phaseDriver.refreshHostAvailability()
            }

            override fun onFreshGenerationPrepared(bootstrap: PreparedSessionBootstrap) {
                consumePreparedOrReconnect(token, bootstrap)
            }

            override fun onClosed() {
                val owned = synchronized(lock) {
                    if (!isCurrentLocked(token)) return
                    runtime.also { runtime = null }
                }
                owned?.let { hostRegistry?.remove(it.sessionId, it.generation) }
                owned?.pipeline?.close()
                synchronized(lock) {
                    if (!isCurrentLocked(token)) return
                    state = if (localRole == SessionRole.Host) {
                        SecureSessionCoordinatorState.Discovering
                    } else {
                        SecureSessionCoordinatorState.Closed
                    }
                    publishLocked()
                }
                phaseDriver.cancel()
                phaseDriver.refreshHostAvailability()
            }
        }

    private fun setState(
        token: Long,
        next: SecureSessionCoordinatorState,
        stage: SecureSessionIntegrationStage,
    ): Boolean = synchronized(lock) {
        if (!isCurrentLocked(token)) return@synchronized false
        state = next
        lastStage = stage
        lastError = SecureSessionIntegrationError.None
        publishLocked()
        true
    }

    private fun fail(token: Long, stage: SecureSessionIntegrationStage, error: SecureSessionIntegrationError) {
        emitDebug(SessionStartupDebugEventKind.Failed, error)
        synchronized(lock) {
            if (token != 0L && !isCurrentLocked(token)) return
            lastStage = stage
            lastError = error
            pairingVerificationPrompt = null
            state = if (localRole == SessionRole.Host) {
                SecureSessionCoordinatorState.Discovering
            } else {
                SecureSessionCoordinatorState.Failed
            }
            publishLocked()
        }
        if (stage == SecureSessionIntegrationStage.Discovery) {
            phaseDriver.stopDiscovery()
        } else if (localRole == SessionRole.Client) {
            phaseDriver.cancel()
        }
        phaseDriver.refreshHostAvailability()
    }

    private fun emitDebug(kind: SessionStartupDebugEventKind, error: SecureSessionIntegrationError? = null) {
        debugObserver.onEvent(SessionStartupDebugEvent(kind, error))
    }

    /**
     * The Client exposes its prompt through the coordinator's Pairing state. A Host responder is
     * intentionally still Discovering while it waits for inbound attempts, so its current prompt
     * remains owned by the Host phase driver. Both paths are one existing RFC-005C decision.
     */
    private fun pairingDecisionToken(): Long? {
        val hostPromptPending = localRole == SessionRole.Host && phaseDriver.pendingPairingVerificationPrompt() != null
        return synchronized(lock) {
            if (closed || (state != SecureSessionCoordinatorState.Pairing && !hostPromptPending)) null else currentToken
        }
    }

    /** Called only by the RFC-005H recovery delegate; it never reuses generation-N keys. */
    fun beginReconnect(
        record: io.warpnect.session.lifecycle.RecoverableSessionRecord,
        nextGeneration: io.warpnect.session.SessionGeneration,
    ): SecureSessionIntegrationError {
        val requestAndToken = synchronized(lock) {
            val request = selectedRequest
            if (closed || request == null || state != SecureSessionCoordinatorState.Recovering) {
                return@synchronized null
            }
            val existing = runtime ?: return@synchronized null
            reconnectingRuntime = existing
            ++currentToken
            lastStage = SecureSessionIntegrationStage.Authentication
            lastError = SecureSessionIntegrationError.None
            request to currentToken
        } ?: return if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy
        synchronized(lock) { publishLocked() }
        val error = phaseDriver.beginReconnect(
            record,
            nextGeneration,
            requestAndToken.first,
            listenerFor(requestAndToken.second),
        )
        if (error != SecureSessionIntegrationError.None) {
            fail(requestAndToken.second, SecureSessionIntegrationStage.Authentication, error)
        }
        return error
    }

    /** The Host stays a WNSH responder; it retains only the existing logical runtime lease. */
    fun awaitResponderReconnect(): SecureSessionIntegrationError = synchronized(lock) {
        if (closed) return@synchronized SecureSessionIntegrationError.Closed
        if (localRole != SessionRole.Host || state != SecureSessionCoordinatorState.Recovering || runtime == null) {
            return@synchronized SecureSessionIntegrationError.Busy
        }
        reconnectingRuntime = runtime
        SecureSessionIntegrationError.None
    }

    private fun stageFor(error: SecureSessionIntegrationError): SecureSessionIntegrationStage = when (error) {
        SecureSessionIntegrationError.VideoPipelineStartFailed,
        SecureSessionIntegrationError.RenderTargetUnavailable,
        -> SecureSessionIntegrationStage.Video
        SecureSessionIntegrationError.SystemAudioStartFailed -> SecureSessionIntegrationStage.SystemAudio
        SecureSessionIntegrationError.MicrophoneStartFailed -> SecureSessionIntegrationStage.MicrophoneAudio
        SecureSessionIntegrationError.InputPipelineStartFailed -> SecureSessionIntegrationStage.Input
        SecureSessionIntegrationError.TelemetryStartFailed -> SecureSessionIntegrationStage.Telemetry
        else -> SecureSessionIntegrationStage.Lifecycle
    }

    private fun isCurrentLocked(token: Long): Boolean = !closed && token == currentToken

    private fun result(error: SecureSessionIntegrationError): SessionIntegrationResult = synchronized(lock) {
        if (error != SecureSessionIntegrationError.None && error != SecureSessionIntegrationError.PairingRequired &&
            error != lastError
        ) {
            lastError = error
            publishLocked()
        }
        SessionIntegrationResult(error, _snapshot.value)
    }

    private fun lastError(): SecureSessionIntegrationError = synchronized(lock) {
        if (closed) SecureSessionIntegrationError.Closed else lastError
    }

    private fun publishLocked() {
        _snapshot.value = snapshotLocked()
    }

    /** The platform discovery control context calls this only after an accepted cache mutation. */
    private fun onDiscoveryUpdated() {
        val notification = synchronized(lock) {
            if (closed || state != SecureSessionCoordinatorState.Discovering) {
                null
            } else {
                publishLocked()
                val hostCount = _snapshot.value.discovery?.candidateCount ?: 0
                discoverySnapshotPublishedListener?.let { listener -> { listener(hostCount) } }
            }
        }
        notification?.invoke()
    }

    private fun snapshotLocked(): SecureSessionCoordinatorSnapshot {
        val owned = runtime
        // The long-lived Host stays Discovering while its responder owns a transient SAS prompt.
        // Read that live responder state on each cold snapshot so a remote/local rejection cannot
        // leave a cached prompt visible after RFC-005C has already terminated the attempt.
        val prompt = if (localRole == SessionRole.Host) {
            phaseDriver.pendingPairingVerificationPrompt()
        } else {
            pairingVerificationPrompt
        }
        return SecureSessionCoordinatorSnapshot(
            state = state,
            localRole = localRole,
            selectedPresenceAlias = selectedRequest?.presence?.displayAlias?.value,
            sessionId = owned?.sessionId,
            generation = owned?.generation,
            remoteDeviceId = selectedRequest?.expectedPeer.let { expected ->
                (expected as? io.warpnect.session.handshake.ExpectedPeerConstraint.ExactTrustedPeer)?.deviceId
            },
            activePathKind = owned?.lifecycle?.activePathKind,
            runningChannels = owned?.pipeline?.snapshot()?.selectedChannels ?: emptySet(),
            activeRuntimeCount = hostRegistry?.snapshotCount() ?: if (owned == null) 0 else 1,
            pairingVerificationPrompt = prompt,
            discovery = phaseDriver.discoverySnapshot(),
            lastStage = lastStage,
            lastError = lastError,
        )
    }

    private data class HostPreparedIntake(
        val token: Long,
        val previous: RunningSessionRuntime?,
    )
}
