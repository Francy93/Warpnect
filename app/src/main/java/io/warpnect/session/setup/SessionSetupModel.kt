@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.setup

import io.warpnect.session.ChannelId
import io.warpnect.session.DeviceId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.NetworkPathState
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.AuthenticatedSessionAdmissionReservation
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionRuntime
import java.security.SecureRandom

/** RFC-005G application control protocol carried only inside RFC-005E protected SessionControl. */
object SessionSetupProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 20
    const val MAX_PAYLOAD_BYTES = 1_024
    const val HASH_BYTES = 32
    const val MAX_CHANNELS = 5
    const val MAX_CONFIGURATION_TLVS = 12
    const val MAX_PROPOSAL_GENERATION = 4
    const val DEFAULT_MAX_ACTIVE_SETUPS = 4
    const val HARD_MAX_ACTIVE_SETUPS = 8
    const val COMPLETION_CACHE_CAPACITY = 64
    const val COMPLETION_CACHE_RETENTION_MS = 30_000L
    const val DEFAULT_LAN_TIMEOUT_MS = 8_000L
    const val DIRECT_SETUP_TIMEOUT_MS = 15_000L
    const val DIRECT_VALIDATION_TIMEOUT_MS = 5_000L
    const val DIRECT_OVERALL_TIMEOUT_MS = 30_000L
    const val PREPARED_BOOTSTRAP_TTL_MS = 30_000L
    val RETRY_DELAYS_MS = longArrayOf(100L, 250L, 500L, 1_000L, 2_000L)
    val MAGIC = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte())
}

@JvmInline
value class SessionSetupId(val value: ULong) {
    val isValid: Boolean get() = value != 0uL

    companion object {
        fun from(value: ULong): SessionSetupId? = value.takeIf { it != 0uL }?.let(::SessionSetupId)

        fun requireValid(value: ULong): SessionSetupId = requireNotNull(from(value)) { "SessionSetupId cannot be zero" }
    }
}

@JvmInline
value class PathAttemptId(val value: ULong) {
    val isValid: Boolean get() = value != 0uL

    companion object {
        fun from(value: ULong): PathAttemptId? = value.takeIf { it != 0uL }?.let(::PathAttemptId)

        fun requireValid(value: ULong): PathAttemptId = requireNotNull(from(value)) { "PathAttemptId cannot be zero" }
    }
}

fun interface SessionSetupIdGenerator {
    fun next(): SessionSetupId
}

fun interface PathAttemptIdGenerator {
    fun next(): PathAttemptId
}

object SecureSessionSetupIdGenerator : SessionSetupIdGenerator {
    private val random = SecureRandom()

    override fun next(): SessionSetupId {
        do {
            SessionSetupId.from(random.nextLong().toULong())?.let { return it }
        } while (true)
    }
}

object SecurePathAttemptIdGenerator : PathAttemptIdGenerator {
    private val random = SecureRandom()

    override fun next(): PathAttemptId {
        do {
            PathAttemptId.from(random.nextLong().toULong())?.let { return it }
        } while (true)
    }
}

enum class SessionSetupMessageType(val wireId: Int) {
    ClientSetupRequest(1),
    HostPathDirective(2),
    DirectPathProbe(3),
    DirectPathAck(4),
    PathFailure(5),
    ClientEndpointOffer(6),
    HostConfigurationProposal(7),
    ClientConfigurationAccept(8),
    ClientConfigurationDecline(9),
    HostCommit(10),
    SetupReject(11),
    ;

    companion object {
        fun fromWireId(value: Int): SessionSetupMessageType? = entries.firstOrNull { it.wireId == value }
    }
}

enum class SessionSetupState {
    Idle,
    ClientRequestSent,
    AwaitingDirectValidation,
    ClientEndpointOfferSent,
    HostProposalSent,
    ClientAcceptSent,
    Prepared,
    Rejected,
    TimedOut,
    Failed,
    Closed,
}

enum class SessionSetupError {
    None,
    InvalidConfig,
    CapabilityProfileMismatch,
    SetupConflict,
    SetupBusy,
    SetupTimeout,
    AdmissionExpired,
    NoUsablePath,
    DirectUnavailable,
    DirectPermissionRequired,
    DirectGroupCreationFailed,
    DirectConnectFailed,
    DirectConnectionTimeout,
    UnexpectedGroupOwnerRole,
    DirectProbeFailed,
    DirectProbeAuthenticationFailed,
    DirectValidationTimeout,
    PathBindingFailed,
    EndpointAllocationFailed,
    EndpointMismatch,
    ChannelPlanInvalid,
    ChannelCapacityExceeded,
    ProtectionContextFailed,
    SecureBudgetInvalid,
    ExactVideoConfigurationUnavailable,
    ExactAudioConfigurationUnavailable,
    InputConfigurationUnavailable,
    RecoveryConfigurationInvalid,
    ProposalLimitExceeded,
    TransportPreparationFailed,
    SecureControlFailure,
    MalformedMessage,
    UnexpectedMessage,
    Closed,
}

enum class SetupRejectStage(val wireId: Int) {
    Request(1),
    Path(2),
    Endpoints(3),
    Proposal(4),
    Commit(5),
    ;

    companion object {
        fun fromWireId(value: Int): SetupRejectStage? = entries.firstOrNull { it.wireId == value }
    }
}

enum class SetupRejectReason(val wireId: Int) {
    Incompatible(1),
    Busy(2),
    Malformed(3),
    Conflict(4),
    DirectUnavailable(5),
    PreparationFailed(6),
    ;

    companion object {
        fun fromWireId(value: Int): SetupRejectReason? = entries.firstOrNull { it.wireId == value }
    }
}

enum class PathDirective(val wireId: Int) {
    UseLan(1),
    AttemptDirect(2),
    ;

    companion object {
        fun fromWireId(value: Int): PathDirective? = entries.firstOrNull { it.wireId == value }
    }
}

enum class PathFailureReason(val wireId: Int) {
    DirectUnavailable(1),
    GroupCreationFailed(2),
    ConnectFailed(3),
    ConnectionTimeout(4),
    UnexpectedGroupOwner(5),
    ProbeTimeout(6),
    ProbeAuthenticationFailed(7),
    PermissionRequired(8),
    ;

    companion object {
        fun fromWireId(value: Int): PathFailureReason? = entries.firstOrNull { it.wireId == value }
    }
}

enum class VideoPreferencePolicy(val wireId: Int) {
    Exact(1),
    OrderedAllowedModes(2),
    ;

    companion object {
        fun fromWireId(value: Int): VideoPreferencePolicy? = entries.firstOrNull { it.wireId == value }
    }
}

data class SessionSetupHeader(
    val messageType: SessionSetupMessageType,
    val setupId: SessionSetupId,
    val bodyLength: Int,
)

data class VideoStreamMode(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Long,
    val flags: Int = 0,
) {
    fun isValid(): Boolean = width in 1..0xffff && height in 1..0xffff && fps in 1..0xffff &&
        bitrateBps in 1..0xffff_ffffL && flags and VIDEO_STREAM_FLAG_MASK.inv() == 0
}

private const val VIDEO_STREAM_FLAG_MASK = CapabilityBits.VIDEO_LOW_LATENCY_DECODE or
    CapabilityBits.VIDEO_DYNAMIC_BITRATE or CapabilityBits.VIDEO_KEYFRAME_REQUEST or CapabilityBits.VIDEO_RESYNC

data class VideoStreamPreference(
    val policy: VideoPreferencePolicy,
    val modes: List<VideoStreamMode>,
) {
    fun isValid(): Boolean = modes.size in 1..4 && modes.all(VideoStreamMode::isValid) &&
        (policy != VideoPreferencePolicy.Exact || modes.size == 1) && modes.distinct().size == modes.size
}

data class AudioStreamMode(
    val sampleRateHz: Int = 48_000,
    val frameDurationUs: Int = 5_000,
    val channelCount: Int = 1,
    val bitrateBps: Long = 64_000L,
) {
    fun isValid(): Boolean = sampleRateHz == 48_000 && frameDurationUs in setOf(2_500, 5_000, 10_000, 20_000) &&
        channelCount in 1..2 && bitrateBps in 1..0xffff_ffffL
}

data class AudioStreamPreference(
    val modes: List<AudioStreamMode>,
) {
    fun isValid(): Boolean = modes.size in 1..4 && modes.all(AudioStreamMode::isValid) &&
        modes.distinct().size == modes.size
}

data class InputStreamConfiguration(
    val inputKinds: Int,
    val stablePresenceKinds: Int,
    val featureFlags: Int,
    val criticalCopies: Int = 2,
    val resetCopies: Int = 3,
    val networkReorderWaitUs: Long = 0L,
    val transportDuplicateWindow: Int = 64,
    val semanticIdentityCache: Int = 32,
) {
    fun isValid(): Boolean = inputKinds != 0 && inputKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
        stablePresenceKinds and inputKinds == stablePresenceKinds &&
        featureFlags and CapabilityBits.INPUT_FLAG_MASK.inv() == 0 && criticalCopies in 1..3 && resetCopies in 1..3 &&
        networkReorderWaitUs == 0L && transportDuplicateWindow in 0..128 && semanticIdentityCache in 0..64
}

data class RecoveryConfiguration(
    val channelId: ChannelId,
    val recoveryFlags: Int,
    val fecDataShards: Int = 0,
    val fecParityShards: Int = 0,
    val retransmissionCacheSlots: Int = 0,
) {
    fun isValid(): Boolean = recoveryFlags and CapabilityBits.RECOVERY_MASK.inv() == 0 &&
        (
            (recoveryFlags and CapabilityBits.RECOVERY_FEC == 0 && fecDataShards == 0 && fecParityShards == 0) ||
                (fecDataShards in 1..255 && fecParityShards in 1..255)
            ) &&
        (
            (recoveryFlags and CapabilityBits.RECOVERY_NACK == 0 && retransmissionCacheSlots == 0) ||
                (recoveryFlags and CapabilityBits.RECOVERY_NACK != 0 && retransmissionCacheSlots in 1..0xffff)
            )
}

data class SessionRecoveryPolicy(
    val video: RecoveryConfigurationTemplate = RecoveryConfigurationTemplate(),
    val systemAudio: RecoveryConfigurationTemplate = RecoveryConfigurationTemplate(),
    val microphoneAudio: RecoveryConfigurationTemplate = RecoveryConfigurationTemplate(),
    val input: RecoveryConfigurationTemplate = RecoveryConfigurationTemplate(),
    val telemetry: RecoveryConfigurationTemplate = RecoveryConfigurationTemplate(),
) {
    fun forKind(kind: SessionChannelKind): RecoveryConfigurationTemplate = when (kind) {
        SessionChannelKind.Video -> video
        SessionChannelKind.SystemAudio -> systemAudio
        SessionChannelKind.MicrophoneAudio -> microphoneAudio
        SessionChannelKind.Input -> input
        SessionChannelKind.Telemetry -> telemetry
        SessionChannelKind.Control -> RecoveryConfigurationTemplate()
    }
}

data class RecoveryConfigurationTemplate(
    val recoveryFlags: Int = 0,
    val fecDataShards: Int = 0,
    val fecParityShards: Int = 0,
    val retransmissionCacheSlots: Int = 0,
) {
    fun bind(channelId: ChannelId): RecoveryConfiguration = RecoveryConfiguration(
        channelId,
        recoveryFlags,
        fecDataShards,
        fecParityShards,
        retransmissionCacheSlots,
    )

    fun isValid(): Boolean = bind(ChannelId.requireValid(1u)).isValid()
}

sealed interface SetupConfiguration {
    val channelId: ChannelId

    data class Video(
        override val channelId: ChannelId,
        val mode: VideoStreamMode,
    ) : SetupConfiguration

    data class SystemAudio(
        override val channelId: ChannelId,
        val mode: AudioStreamMode,
    ) : SetupConfiguration

    data class MicrophoneAudio(
        override val channelId: ChannelId,
        val mode: AudioStreamMode,
    ) : SetupConfiguration

    data class Input(
        override val channelId: ChannelId,
        val config: InputStreamConfiguration,
    ) : SetupConfiguration

    data class Telemetry(
        override val channelId: ChannelId,
        val featureFlags: Int = 0,
    ) : SetupConfiguration

    data class Recovery(
        override val channelId: ChannelId,
        val config: RecoveryConfiguration,
    ) : SetupConfiguration
}

data class SessionSetupPreferences(
    val pathPreference: PathPreferencePolicy = PathPreferencePolicy.PreferDirectThenLan,
    val secondaryPathPolicy: SecondaryPathPolicy = SecondaryPathPolicy.KeepValidatedStandby,
    val video: VideoStreamPreference? = null,
    val systemAudio: AudioStreamPreference? = null,
    val microphoneAudio: AudioStreamPreference? = null,
    val input: InputStreamConfiguration? = null,
) {
    fun isValidFor(profile: NegotiatedCapabilityProfile): Boolean {
        if (!profile.isValid()) return false
        val videoSelected = profile.selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0
        val systemAudioSelected = profile.selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0
        val microphoneSelected = profile.selectedChannels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0
        val inputSelected = profile.selectedChannels and CapabilityBits.CHANNEL_INPUT != 0
        return (videoSelected == (video != null)) && (systemAudioSelected == (systemAudio != null)) &&
            (microphoneSelected == (microphoneAudio != null)) && (inputSelected == (input != null)) &&
            (video?.isValid() ?: true) && (systemAudio?.isValid() ?: true) && (microphoneAudio?.isValid() ?: true) &&
            (input?.isValid() ?: true)
    }
}

/** Removes local preferences for optional channels that WNCP did not select. */
fun SessionSetupPreferences.retainOnlySelectedChannels(profile: NegotiatedCapabilityProfile): SessionSetupPreferences =
    copy(
        video = video.takeIf { profile.selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0 },
        systemAudio = systemAudio.takeIf { profile.selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0 },
        microphoneAudio = microphoneAudio.takeIf { profile.selectedChannels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0 },
        input = input.takeIf { profile.selectedChannels and CapabilityBits.CHANNEL_INPUT != 0 },
    )

data class ChannelEndpointOffer(
    val kind: SessionChannelKind,
    val instanceIndex: Int = 0,
    val localPort: Int,
) {
    fun isValid(): Boolean = kind != SessionChannelKind.Control && instanceIndex == 0 && localPort in 1..0xffff
}

data class ChannelDescriptor(
    val channelId: ChannelId,
    val kind: SessionChannelKind,
    val direction: SessionChannelDirection,
    val instanceIndex: Int,
    val pathId: PathId,
    val hostLocalPort: Int,
    val clientLocalPort: Int,
    val maxSecureDatagramBytes: Int,
    val recoveryFlags: Int,
) {
    fun isValid(): Boolean = kind != SessionChannelKind.Control && direction == kind.defaultDirection() &&
        instanceIndex == 0 && hostLocalPort in 1..0xffff && clientLocalPort in 1..0xffff &&
        maxSecureDatagramBytes in 1..0xffff && recoveryFlags and CapabilityBits.RECOVERY_MASK.inv() == 0
}

data class SessionPathPlan(
    val pathId: PathId,
    val kind: NetworkPathKind,
    val state: NetworkPathState,
    val localAddress: String,
    val remoteAddress: String,
) {
    fun isValid(): Boolean = localAddress.isNotBlank() && remoteAddress.isNotBlank() &&
        state in setOf(NetworkPathState.Active, NetworkPathState.Standby)
}

data class PathSocketBinding(
    val pathId: PathId,
    val kind: NetworkPathKind,
    val localAddress: String,
) {
    fun isValid(): Boolean = localAddress.isNotBlank()
}

private fun SessionChannelKind.defaultDirection(): SessionChannelDirection = when (this) {
    SessionChannelKind.Video,
    SessionChannelKind.SystemAudio,
    -> SessionChannelDirection.HostToClient
    SessionChannelKind.MicrophoneAudio,
    SessionChannelKind.Input,
    -> SessionChannelDirection.ClientToHost
    SessionChannelKind.Telemetry,
    SessionChannelKind.Control,
    -> SessionChannelDirection.Bidirectional
}

/** An endpoint is allocated before its port is advertised and owns no receive worker in RFC-005G. */
interface ChannelEndpointLease : AutoCloseable {
    val binding: PathSocketBinding
    val localPort: Int
    val channelKind: SessionChannelKind
    override fun close()
}

/** Opaque prepared Channel scope; it never exposes 005E key material. */
interface PreparedChannelProtection : AutoCloseable {
    val channelId: ChannelId
    val contextIds: ProtectionContextIds
    override fun close()
}

/** Stopped native transport state. It owns no running receive/capture worker in RFC-005G. */
interface PreparedChannelTransport : AutoCloseable {
    val protectedRequired: Boolean
    val started: Boolean
    override fun close()
}

/**
 * Cold-control-path owner of a Channel transport after RFC-005I adopts the stopped RFC-005G
 * native handle into a running Phase 2/3/4 controller. It changes only path-bound endpoint
 * state; the ChannelId and RFC-005E context remain owned by this PreparedChannel.
 */
fun interface LiveChannelTransportRebinder {
    fun rebind(localEndpoint: ChannelEndpointLease, remoteAddress: String, remotePort: Int): Boolean
}

/** A real Direct group/path resource is shared separately from prepared media channel endpoints. */
interface DirectPathLease : AutoCloseable {
    override fun close()
}

class PreparedChannel(
    descriptor: ChannelDescriptor,
    localLease: ChannelEndpointLease,
    remoteAddress: String,
    configuration: List<SetupConfiguration>,
    val protection: PreparedChannelProtection,
    transport: PreparedChannelTransport,
) : AutoCloseable {
    var descriptor: ChannelDescriptor = descriptor
        private set
    var localLease: ChannelEndpointLease = localLease
        private set
    var remoteAddress: String = remoteAddress
        private set
    var transport: PreparedChannelTransport = transport
        private set
    var configuration: List<SetupConfiguration> = configuration.toList()
        private set
    private var liveTransportRebinder: LiveChannelTransportRebinder? = null

    internal fun updateConfiguration(value: List<SetupConfiguration>) {
        configuration = value.toList()
    }

    internal fun closePreparation() {
        transport.close()
        protection.close()
    }

    /**
     * RFC-005I transfers the native handle exactly once, then registers its final controller as
     * the only endpoint-rebind owner. Registration is deliberately one-shot per generation.
     */
    internal fun adoptLiveTransport(rebinder: LiveChannelTransportRebinder) {
        check(liveTransportRebinder == null) { "Live Channel transport already adopted" }
        liveTransportRebinder = rebinder
    }

    internal fun hasLiveTransport(): Boolean = liveTransportRebinder != null

    /**
     * Same-generation migration keeps the adopted transport object and its RFC-005E state. Only
     * the path socket/remote endpoint changes; the prior spent endpoint lease is released.
     */
    internal fun replaceLiveEndpoint(
        replacementDescriptor: ChannelDescriptor,
        replacementLease: ChannelEndpointLease,
        replacementRemoteAddress: String,
    ): Boolean {
        require(
            replacementDescriptor.channelId == descriptor.channelId && replacementDescriptor.kind == descriptor.kind,
        )
        val remotePort = replacementDescriptor.remotePortForMigration(replacementLease.localPort)
        if (remotePort !in 1..0xffff ||
            liveTransportRebinder?.rebind(replacementLease, replacementRemoteAddress, remotePort) != true
        ) {
            return false
        }
        val oldLease = localLease
        descriptor = replacementDescriptor
        localLease = replacementLease
        remoteAddress = replacementRemoteAddress
        oldLease.close()
        return true
    }

    /** Same-generation endpoint swap: the Channel protection lease remains unchanged. */
    internal fun replaceEndpoint(
        replacementDescriptor: ChannelDescriptor,
        replacementLease: ChannelEndpointLease,
        replacementRemoteAddress: String,
        replacementTransport: PreparedChannelTransport,
    ) {
        require(
            replacementDescriptor.channelId == descriptor.channelId && replacementDescriptor.kind == descriptor.kind,
        )
        require(replacementTransport.protectedRequired && !replacementTransport.started)
        val oldTransport = transport
        val oldLease = localLease
        descriptor = replacementDescriptor
        localLease = replacementLease
        remoteAddress = replacementRemoteAddress
        transport = replacementTransport
        oldTransport.close()
        oldLease.close()
    }

    override fun close() {
        closePreparation()
        localLease.close()
    }
}

private fun ChannelDescriptor.remotePortForMigration(localPort: Int): Int =
    if (hostLocalPort == localPort) clientLocalPort else hostLocalPort

class PreparedSessionBootstrap(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val localDeviceId: DeviceId,
    val remoteDeviceId: DeviceId,
    val localRole: SessionRole,
    val remoteRole: SessionRole,
    val profile: NegotiatedCapabilityProfile,
    val profileHash: ByteArray,
    val activePath: SessionPathPlan,
    val standbyPath: SessionPathPlan?,
    val channels: List<PreparedChannel>,
    val secureSessionControl: SecureSessionControlTransport,
    val protectionRuntime: SessionProtectionRuntime,
    val admissionReservation: AuthenticatedSessionAdmissionReservation?,
    val createdAtMonotonicMs: Long,
    val expiresAtMonotonicMs: Long,
    private val directLease: DirectPathLease? = null,
    /** Canonical committed RFC-005G proposal hash, retained only for same-generation migration correlation. */
    val preparedConfigurationHash: ByteArray = ByteArray(32),
    /** Existing protected SessionControl endpoint retained for RFC-005H lifecycle path ownership. */
    val initialControlEndpoint: io.warpnect.session.handshake.HandshakeTransportEndpoint? = null,
    /**
     * Authenticated control endpoints keyed by their committed PathId. These are in-memory route
     * bindings only, never negotiated IP fields; RFC-005H uses them for standby revalidation.
     */
    val pathControlEndpoints: Map<PathId, io.warpnect.session.handshake.HandshakeTransportEndpoint> = emptyMap(),
) : AutoCloseable {
    private var closed = false
    private var expiryHandle: AutoCloseable? = null

    /**
     * RFC-005H takes deterministic ownership before the RFC-005G prepared TTL fires. This does
     * not start a pipeline and does not duplicate any prepared endpoint or security context.
     */
    @Synchronized
    fun transferToLifecycle(nowMonotonicMs: Long): Boolean {
        if (closed || nowMonotonicMs < createdAtMonotonicMs || nowMonotonicMs >= expiresAtMonotonicMs) return false
        expiryHandle?.close()
        expiryHandle = null
        return true
    }

    @Synchronized
    fun isClosed(): Boolean = closed

    @Synchronized
    internal fun armExpiry(handle: AutoCloseable) {
        if (closed) {
            handle.close()
        } else {
            expiryHandle?.close()
            expiryHandle = handle
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        expiryHandle?.close()
        expiryHandle = null
        channels.asReversed().forEach(PreparedChannel::close)
        secureSessionControl.close()
        protectionRuntime.close()
        directLease?.close()
        admissionReservation?.close()
    }

    @Synchronized
    fun isExpired(nowMonotonicMs: Long): Boolean = closed || nowMonotonicMs >= expiresAtMonotonicMs
}

sealed interface SessionSetupMessage {
    val header: SessionSetupHeader

    data class ClientSetupRequest(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val selectedChannels: Int,
        val preferences: SessionSetupPreferences,
    ) : SessionSetupMessage

    data class HostPathDirective(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val directive: PathDirective,
        val activeCandidate: NetworkPathKind,
        val standbyCandidate: NetworkPathKind?,
        val pathAttemptId: PathAttemptId?,
        val hostDirectProbePort: Int,
        val directTimeoutMs: Int,
    ) : SessionSetupMessage

    data class DirectPathProbe(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val pathAttemptId: PathAttemptId,
    ) : SessionSetupMessage

    data class DirectPathAck(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val pathAttemptId: PathAttemptId,
    ) : SessionSetupMessage

    data class PathFailure(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val pathAttemptId: PathAttemptId,
        val reason: PathFailureReason,
    ) : SessionSetupMessage

    data class ClientEndpointOffer(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val activePathKind: NetworkPathKind,
        val endpoints: List<ChannelEndpointOffer>,
    ) : SessionSetupMessage

    data class HostConfigurationProposal(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val clientEndpointOfferHash: ByteArray,
        val proposalGeneration: Int,
        val activePathKind: NetworkPathKind,
        val standbyPathKind: NetworkPathKind?,
        val activePathId: PathId,
        val standbyPathId: PathId?,
        val descriptors: List<ChannelDescriptor>,
        val configurations: List<SetupConfiguration>,
    ) : SessionSetupMessage

    data class ClientConfigurationAccept(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val clientEndpointOfferHash: ByteArray,
        val proposalHash: ByteArray,
    ) : SessionSetupMessage

    data class ClientConfigurationDecline(
        override val header: SessionSetupHeader,
        val proposalHash: ByteArray,
        val proposalGeneration: Int,
        val reason: SessionSetupError,
    ) : SessionSetupMessage

    data class HostCommit(
        override val header: SessionSetupHeader,
        val profileHash: ByteArray,
        val clientEndpointOfferHash: ByteArray,
        val proposalHash: ByteArray,
    ) : SessionSetupMessage

    data class Reject(
        override val header: SessionSetupHeader,
        val stage: SetupRejectStage,
        val reason: SetupRejectReason,
        val relatedHash: ByteArray,
    ) : SessionSetupMessage
}

data class DecodedSessionSetupPacket(
    val message: SessionSetupMessage,
    val bytes: ByteArray,
) {
    val hash: ByteArray get() = SessionSetupCodec.hash(bytes)
}
