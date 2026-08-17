@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.lifecycle

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.setup.ChannelDescriptor
import io.warpnect.session.setup.SessionSetupPreferences
import java.security.SecureRandom

/** RFC-005H lifecycle control records. They are accepted only after RFC-005E SessionControl authentication. */
object SessionLifecycleProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 20
    const val MAX_PAYLOAD_BYTES = 512
    const val HASH_BYTES = 32
    const val CHALLENGE_BYTES = 16
    const val MAX_CHANNEL_MIGRATION_ENTRIES = 5
    const val MIGRATION_COMPLETION_CACHE_CAPACITY = 4
    const val MIGRATION_COMPLETION_RETENTION_MS = 5_000L
    val MAGIC: ByteArray = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte())
}

@JvmInline
value class LifecycleMessageId(val value: ULong) {
    val isValid: Boolean get() = value != 0uL

    companion object {
        fun from(value: ULong): LifecycleMessageId? = value.takeIf { it != 0uL }?.let(::LifecycleMessageId)
        fun requireValid(value: ULong): LifecycleMessageId =
            requireNotNull(from(value)) { "LifecycleMessageId cannot be zero" }
    }
}

@JvmInline
value class HeartbeatId(val value: ULong) {
    val isValid: Boolean get() = value != 0uL

    companion object {
        fun from(value: ULong): HeartbeatId? = value.takeIf { it != 0uL }?.let(::HeartbeatId)
        fun requireValid(value: ULong): HeartbeatId = requireNotNull(from(value)) { "HeartbeatId cannot be zero" }
    }
}

@JvmInline
value class PathMigrationId(val value: ULong) {
    val isValid: Boolean get() = value != 0uL

    companion object {
        fun from(value: ULong): PathMigrationId? = value.takeIf { it != 0uL }?.let(::PathMigrationId)
        fun requireValid(value: ULong): PathMigrationId =
            requireNotNull(from(value)) { "PathMigrationId cannot be zero" }
    }
}

fun interface LifecycleMessageIdGenerator {
    fun next(): LifecycleMessageId
}

fun interface HeartbeatIdGenerator {
    fun next(): HeartbeatId
}

fun interface PathMigrationIdGenerator {
    fun next(): PathMigrationId
}

private val lifecycleRandom = SecureRandom()

object SecureLifecycleMessageIdGenerator : LifecycleMessageIdGenerator {
    override fun next(): LifecycleMessageId = nonZeroU64 { LifecycleMessageId.from(it) }
}

object SecureHeartbeatIdGenerator : HeartbeatIdGenerator {
    override fun next(): HeartbeatId = nonZeroU64 { HeartbeatId.from(it) }
}

object SecurePathMigrationIdGenerator : PathMigrationIdGenerator {
    override fun next(): PathMigrationId = nonZeroU64 { PathMigrationId.from(it) }
}

private fun <T> nonZeroU64(factory: (ULong) -> T?): T {
    while (true) factory(lifecycleRandom.nextLong().toULong())?.let { return it }
}

enum class SessionLifecycleMessageType(val wireId: Int) {
    Heartbeat(1),
    HeartbeatAck(2),
    PathChallenge(3),
    PathResponse(4),
    PathMigrationPrepare(5),
    PathMigrationReady(6),
    PathMigrationCommit(7),
    PathMigrationAck(8),
    DisconnectNotice(9),
    DisconnectAck(10),
    ;

    companion object {
        fun fromWireId(value: Int): SessionLifecycleMessageType? = entries.firstOrNull { it.wireId == value }
    }
}

enum class SessionLifecycleState {
    Prepared,
    Establishing,
    Active,
    Degraded,
    MigratingPath,
    Suspended,
    Reconnecting,
    Disconnecting,
    Closed,
    Failed,
}

enum class LifecyclePathHealth {
    Active,
    Suspect,
    LocallyLost,
    ValidatedStandby,
    Lost,
}

enum class LifecycleTransportGate {
    ActiveTransport,
    Migrating,
    Suspended,
    Closed,
}

enum class SessionRecoveryPolicy {
    Disabled,
    FastReconnect,
}

enum class DisconnectReason(val wireId: Int) {
    UserRequested(1),
    ApplicationStopping(2),
    HostClosing(3),
    PolicyChange(4),
    FatalError(5),
    SupersededGeneration(6),
    ;

    companion object {
        fun fromWireId(value: Int): DisconnectReason? = entries.firstOrNull { it.wireId == value }
    }
}

enum class SessionLifecycleError {
    None,
    InvalidState,
    InvalidConfig,
    SessionGenerationExhausted,
    PathSuspect,
    PathLost,
    NoStandbyPath,
    PathValidationFailed,
    PathMigrationTimeout,
    PathMigrationConflict,
    MigrationEndpointAllocationFailed,
    MigrationCommitFailed,
    TransportRebindFailed,
    DisconnectTimeout,
    RecoveryDisabled,
    RecoveryExpired,
    RecoveryCancelled,
    ReconnectRouteUnavailable,
    ReconnectHandshakeFailed,
    ReconnectPeerMismatch,
    ReconnectGenerationMismatch,
    RecoveryLeaseConflict,
    CapabilityRenegotiationFailed,
    SessionSetupFailed,
    SecureControlFailure,
    MalformedMessage,
    UnexpectedMessage,
    Closed,
}

data class LifecycleHealthConfig(
    val idleProbeAfterMs: Long = 500L,
    val heartbeatResponseTimeoutMs: Long = 300L,
    val maxConsecutiveHeartbeatMisses: Int = 2,
    val pathCandidateValidationTimeoutMs: Long = 1_000L,
    val pathMigrationTimeoutMs: Long = 1_500L,
    val gracefulDisconnectBudgetMs: Long = 250L,
    val recoveryWindowMs: Long = 15_000L,
    val maxReconnectAttempts: Int = 6,
    val reconnectBackoffMs: List<Long> = listOf(0L, 250L, 500L, 1_000L, 2_000L, 4_000L),
) {
    fun isValid(): Boolean = idleProbeAfterMs in 250L..5_000L && heartbeatResponseTimeoutMs in 100L..2_000L &&
        maxConsecutiveHeartbeatMisses in 1..5 && pathCandidateValidationTimeoutMs in 250L..5_000L &&
        pathMigrationTimeoutMs in 250L..5_000L && gracefulDisconnectBudgetMs in 1L..2_000L &&
        recoveryWindowMs in 2_000L..60_000L && maxReconnectAttempts in 1..6 &&
        reconnectBackoffMs.size >= maxReconnectAttempts && reconnectBackoffMs.take(maxReconnectAttempts).all {
            it in 0L..10_000L
        }
}

data class SessionLifecycleHeader(
    val messageType: SessionLifecycleMessageType,
    val lifecycleMessageId: LifecycleMessageId,
    val bodyLength: Int,
)

data class PathMigrationEntry(
    val channelId: io.warpnect.session.ChannelId,
    val localPort: Int,
) {
    fun isValid(): Boolean = channelId.value != 0u && localPort in 1..0xffff
}

sealed interface SessionLifecycleMessage {
    val header: SessionLifecycleHeader

    data class Heartbeat(
        override val header: SessionLifecycleHeader,
        val heartbeatId: HeartbeatId,
        val activePathId: PathId,
    ) : SessionLifecycleMessage

    data class HeartbeatAck(
        override val header: SessionLifecycleHeader,
        val heartbeatId: HeartbeatId,
        val activePathId: PathId,
    ) : SessionLifecycleMessage

    data class PathChallenge(
        override val header: SessionLifecycleHeader,
        val migrationId: PathMigrationId,
        val targetPathId: PathId,
        val targetPathKind: NetworkPathKind,
        val challenge: ByteArray,
    ) : SessionLifecycleMessage

    data class PathResponse(
        override val header: SessionLifecycleHeader,
        val migrationId: PathMigrationId,
        val targetPathId: PathId,
        val targetPathKind: NetworkPathKind,
        val challenge: ByteArray,
    ) : SessionLifecycleMessage

    data class PathMigrationPrepare(
        override val header: SessionLifecycleHeader,
        val migrationId: PathMigrationId,
        val targetPathId: PathId,
        val preparedConfigurationHash: ByteArray,
        val entries: List<PathMigrationEntry>,
    ) : SessionLifecycleMessage

    data class PathMigrationReady(
        override val header: SessionLifecycleHeader,
        val migrationId: PathMigrationId,
        val targetPathId: PathId,
        val preparedConfigurationHash: ByteArray,
        val entries: List<PathMigrationEntry>,
    ) : SessionLifecycleMessage

    data class PathMigrationCommit(
        override val header: SessionLifecycleHeader,
        val migrationId: PathMigrationId,
        val targetPathId: PathId,
        val migrationPlanHash: ByteArray,
    ) : SessionLifecycleMessage

    data class PathMigrationAck(
        override val header: SessionLifecycleHeader,
        val migrationId: PathMigrationId,
        val targetPathId: PathId,
        val migrationPlanHash: ByteArray,
    ) : SessionLifecycleMessage

    data class DisconnectNotice(
        override val header: SessionLifecycleHeader,
        val reason: DisconnectReason,
        val sessionGeneration: SessionGeneration,
        val activePathId: PathId,
    ) : SessionLifecycleMessage

    data class DisconnectAck(
        override val header: SessionLifecycleHeader,
        val reason: DisconnectReason,
        val sessionGeneration: SessionGeneration,
        val activePathId: PathId,
    ) : SessionLifecycleMessage
}

data class DecodedSessionLifecyclePacket(
    val message: SessionLifecycleMessage,
    val bytes: ByteArray,
) {
    val hash: ByteArray get() = SessionLifecycleCodec.hash(bytes)
}

data class LifecycleChannelPlan(
    val descriptor: ChannelDescriptor,
    val kind: SessionChannelKind = descriptor.kind,
)

data class SessionLifecycleSnapshot(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val state: SessionLifecycleState,
    val transportGate: LifecycleTransportGate,
    val activePathId: PathId?,
    val activePathKind: NetworkPathKind?,
    val standbyPathId: PathId?,
    val standbyPathKind: NetworkPathKind?,
    val activePathHealth: LifecyclePathHealth?,
    val standbyPathHealth: LifecyclePathHealth?,
    val lastAuthenticatedReceiveAgeMs: Long?,
    val pendingHeartbeatId: HeartbeatId?,
    val consecutiveHeartbeatMisses: Int,
    val currentMigrationId: PathMigrationId?,
    val recoveryDeadlineRemainingMs: Long?,
    val reconnectAttemptCount: Int,
    val nextReconnectAttemptRemainingMs: Long?,
    val lastError: SessionLifecycleError,
)

/** Explicit non-secret user intent carried into a fresh reconnect generation. */
data class SessionRecoveryIntent(
    val pathPreference: PathPreferencePolicy,
    val secondaryPathPolicy: SecondaryPathPolicy,
    val capabilityRequest: CapabilityRequest,
    val setupPreferences: SessionSetupPreferences,
)

internal fun SessionLifecycleState.isTerminal(): Boolean =
    this == SessionLifecycleState.Closed || this == SessionLifecycleState.Failed
