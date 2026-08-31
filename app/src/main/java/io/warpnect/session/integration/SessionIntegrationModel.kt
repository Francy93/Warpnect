package io.warpnect.session.integration

import io.warpnect.session.DeviceId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoverySnapshot
import io.warpnect.session.handshake.ExpectedPeerConstraint
import io.warpnect.session.lifecycle.DisconnectReason
import io.warpnect.session.lifecycle.RecoverableSessionRecord
import io.warpnect.session.pairing.PairingVerificationPrompt
import io.warpnect.session.setup.SessionSetupPreferences

/** RFC-005I orchestration projection; protocol controllers retain their own detailed state. */
enum class SecureSessionCoordinatorState {
    Idle,
    Discovering,
    PairingRequired,
    Pairing,
    Connecting,
    Authenticating,
    Securing,
    NegotiatingCapabilities,
    ConfiguringSession,
    Starting,
    Running,
    Recovering,
    Stopping,
    Closed,
    Failed,
}

enum class SecureSessionIntegrationStage {
    Discovery,
    Pairing,
    Authentication,
    Security,
    Capabilities,
    Setup,
    Lifecycle,
    Video,
    SystemAudio,
    MicrophoneAudio,
    Input,
    Telemetry,
}

enum class SecureSessionIntegrationError {
    None,
    Busy,
    Closed,
    InvalidPresence,
    DiscoveryStartFailed,
    PairingRequired,
    PairingFailed,
    AuthenticationFailed,
    SecureSessionFailed,
    CapabilityNegotiationFailed,
    SessionSetupFailed,
    LifecycleStartFailed,
    RenderTargetUnavailable,
    VideoPipelineStartFailed,
    SystemAudioStartFailed,
    MicrophoneStartFailed,
    InputPipelineStartFailed,
    TelemetryStartFailed,
    PipelinePlanInvalid,
    PipelineStartFailed,
    RegistryCapacityExceeded,
    LocalPipelineFailure,
    Cancelled,
    Failed,
}

enum class SessionPipelineStartPhase {
    InboundSink,
    InboundTransport,
    OutboundProcessor,
    PhysicalSource,
}

enum class SessionPipelineState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Failed,
    Closed,
}

data class SecureSessionConnectRequest(
    /** Ephemeral RFC-005B metadata; this is deliberately not a DeviceId or manually entered endpoint. */
    val presence: DiscoveredPresence,
    val capabilityRequest: CapabilityRequest,
    val setupPreferences: SessionSetupPreferences,
    val expectedPeer: ExpectedPeerConstraint = ExpectedPeerConstraint.AnyTrustedPeer,
)

data class SessionPipelineComponentSnapshot(
    val name: String,
    val phase: SessionPipelineStartPhase,
    val channelKinds: Set<SessionChannelKind>,
    val started: Boolean,
)

data class SessionPipelineSnapshot(
    val state: SessionPipelineState,
    val selectedChannels: Set<SessionChannelKind>,
    val startedComponents: List<SessionPipelineComponentSnapshot>,
    val lastFailedComponent: String?,
    val lastError: SecureSessionIntegrationError,
)

data class SecureSessionCoordinatorSnapshot(
    val state: SecureSessionCoordinatorState,
    val localRole: SessionRole,
    val selectedPresenceAlias: String?,
    val sessionId: SessionId?,
    val generation: SessionGeneration?,
    val remoteDeviceId: DeviceId?,
    val activePathKind: NetworkPathKind?,
    val runningChannels: Set<SessionChannelKind>,
    val activeRuntimeCount: Int,
    val pairingVerificationPrompt: PairingVerificationPrompt?,
    /** Current bounded RFC-005B state for truthful discovery presentation; it contains no peer identity. */
    val discovery: DiscoverySnapshot?,
    val lastStage: SecureSessionIntegrationStage?,
    val lastError: SecureSessionIntegrationError,
)

data class SessionIntegrationResult(
    val error: SecureSessionIntegrationError,
    val snapshot: SecureSessionCoordinatorSnapshot,
) {
    val isSuccess: Boolean get() = error == SecureSessionIntegrationError.None
}

data class SessionPipelineComponentResult(
    val error: SecureSessionIntegrationError = SecureSessionIntegrationError.None,
) {
    val isSuccess: Boolean get() = error == SecureSessionIntegrationError.None
}

interface SessionPipelineComponent : AutoCloseable {
    val name: String
    val phase: SessionPipelineStartPhase
    val channelKinds: Set<SessionChannelKind>

    /** Components are stopped after RFC-005G and start only when RFC-005I owns the transaction. */
    fun start(): SessionPipelineComponentResult

    fun stop()

    /** Existing subsystem-specific recovery, such as video resync, remains behind this hook. */
    fun onPathMigrationCommitted() = Unit

    /** Sources may quiesce only their path-facing work while RFC-005H validates the standby. */
    fun onPathMigrationStarting() = Unit

    /** Real-time sources are stopped promptly while the lifecycle is suspended. */
    fun onSessionSuspended() = Unit

    /** A new generation owns fresh components; stale runtime objects are never resumed. */
    fun onSessionReconnected() = Unit

    /** Target-side Input V1 reset/convergence work is delegated to the existing input runtime. */
    fun onInputSafetyReset() = Unit

    override fun close()
}

interface SessionPipelineFactory {
    /** Returns every component required by the exact committed RFC-005G channel plan. */
    fun create(bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap): SessionPipelineFactoryResult
}

data class SessionPipelineFactoryResult(
    val error: SecureSessionIntegrationError,
    val components: List<SessionPipelineComponent> = emptyList(),
) {
    val isSuccess: Boolean get() = error == SecureSessionIntegrationError.None
}

interface ManagedLifecycleSession : AutoCloseable {
    val sessionId: SessionId
    val generation: SessionGeneration
    val activePathKind: NetworkPathKind?

    fun start(): SecureSessionIntegrationError

    fun gracefulDisconnect(reason: DisconnectReason)

    /** Advances bounded RFC-005H timers on the application-owned control scheduler. */
    fun advance() = Unit

    /**
     * Transfers a validated fresh RFC-005G bootstrap out of RFC-005H recovery ownership. The
     * current generation is then terminal; the coordinator creates the new runtime.
     */
    fun acceptFreshGeneration(
        bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.None

    override fun close()
}

interface SessionLifecycleSessionFactory {
    /** Takes RFC-005G ownership and installs the pipeline as the RFC-005H continuity participant. */
    fun create(
        bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap,
        pipeline: SessionPipelineRuntime,
        listener: SessionLifecycleRuntimeListener,
    ): SessionLifecycleFactoryResult
}

data class SessionLifecycleFactoryResult(
    val error: SecureSessionIntegrationError,
    val lifecycle: ManagedLifecycleSession? = null,
) {
    val isSuccess: Boolean get() = error == SecureSessionIntegrationError.None && lifecycle != null
}

/** Lifecycle callbacks are generation-scoped and must never resurrect a closed coordinator. */
interface SessionLifecycleRuntimeListener {
    fun onRecovering()

    /** Fresh 005D -> 005E -> 005F -> 005G output for the same logical SessionId. */
    fun onFreshGenerationPrepared(bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap)

    fun onClosed()
}

/**
 * Adapter boundary for 005B-005G. Implementations delegate to the existing controllers rather
 * than encoding WNPB/WNSH/WNCP/WNSN here. All callbacks are scoped to one coordinator attempt.
 */
interface SecureSessionPhaseDriver : AutoCloseable {
    fun startDiscovery(role: SessionRole): SecureSessionIntegrationError

    fun stopDiscovery()

    /** Bounded RFC-005B observations for the normal Client chooser; never persistent identity. */
    fun discoveredPresences(): List<io.warpnect.session.discovery.DiscoveredPresence> = emptyList()

    /** Exposes bounded discovery state to the coordinator/UI without creating a second state machine. */
    fun discoverySnapshot(): DiscoverySnapshot? = null

    /** Asynchronous platform start failures are reported by the existing control scheduler. */
    fun discoveryFailure(): SecureSessionIntegrationError = SecureSessionIntegrationError.None

    /** Bounded discovery-cache change notification for presentation; it does not create a new state machine. */
    fun setDiscoveryUpdateListener(listener: (() -> Unit)?) = Unit

    /** Host pairing prompts are exposed without teaching the coordinator any pairing protocol. */
    fun pendingPairingVerificationPrompt(): PairingVerificationPrompt? = null

    fun beginConnection(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError

    fun beginExplicitPairing(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError

    fun approvePairing(): SecureSessionIntegrationError

    /** Sends the RFC-005C user-rejection outcome for the currently displayed SAS prompt. */
    fun rejectPairing(): SecureSessionIntegrationError = SecureSessionIntegrationError.PairingRequired

    /** Creates RFC-005E from the fresh RFC-005D bootstrap and never returns plaintext control. */
    fun createSecureCapabilityBootstrap(
        bootstrap: io.warpnect.session.handshake.AuthenticatedSessionBootstrap,
    ): SecurePhaseResult

    fun beginCapabilities(
        bootstrap: io.warpnect.session.capability.SecureSessionCapabilityBootstrap,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError

    fun beginSetup(
        bootstrap: io.warpnect.session.capability.NegotiatedSessionBootstrap,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError

    /** RFC-005H recovery reuses user intent but starts fresh WNSH material for generation N + 1. */
    fun beginReconnect(
        record: RecoverableSessionRecord,
        nextGeneration: SessionGeneration,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    /** Advances only the existing bounded 005C-005G control timers on their serialized owner. */
    fun advance() = Unit

    /** Cancels only the bounded current attempt; it does not create an alternate manual transport. */
    fun cancel()

    fun refreshHostAvailability()

    override fun close()
}

data class SecurePhaseResult(
    val error: SecureSessionIntegrationError,
    val bootstrap: io.warpnect.session.capability.SecureSessionCapabilityBootstrap? = null,
) {
    val isSuccess: Boolean get() = error == SecureSessionIntegrationError.None && bootstrap != null
}

interface SecureSessionPhaseListener {
    fun onPairingRequired()

    /** A SAS is shown only after an explicit selected-peer connection entered RFC-005C pairing. */
    fun onPairingVerificationPrompt(prompt: PairingVerificationPrompt) = Unit

    fun onPairingCompleted()

    fun onAuthenticated(bootstrap: io.warpnect.session.handshake.AuthenticatedSessionBootstrap)

    fun onCapabilitiesNegotiated(bootstrap: io.warpnect.session.capability.NegotiatedSessionBootstrap)

    fun onPrepared(bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap)

    fun onFailed(stage: SecureSessionIntegrationStage, error: SecureSessionIntegrationError)
}
