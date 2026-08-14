package io.warpnect.session

enum class SessionError {
    None,
    InvalidDeviceId,
    InvalidSessionId,
    InvalidSessionGeneration,
    InvalidParticipantIndex,
    DuplicateSessionId,
    SessionCapacityExceeded,
    DuplicatePeerSessionNotAllowed,
    DuplicateParticipantIndex,
    InvalidRoleCombination,
    InvalidChannel,
    DuplicateChannelId,
    ChannelCapacityExceeded,
    InvalidChannelStateTransition,
    InvalidPath,
    DuplicatePathId,
    PathCapacityExceeded,
    MultipleActivePaths,
    InvalidPathStateTransition,
    InvalidPeripheral,
    DuplicatePeripheral,
    PeripheralCapacityExceeded,
    InvalidPeripheralPresence,
    InvalidPolicy,
    InvalidManagerConfiguration,
    InvalidStateTransition,
    SessionNotFound,
    Closed,
}

enum class SessionRole {
    Host,
    Client,
}

data class SessionParticipant(
    val deviceId: DeviceId,
    val role: SessionRole,
)

enum class SessionState {
    Created,
    Establishing,
    Ready,
    Running,
    Suspended,
    Stopping,
    Stopped,
    Failed,
    Closed,
}

enum class SessionChannelKind {
    Control,
    Video,
    SystemAudio,
    MicrophoneAudio,
    Input,
    Telemetry,
}

enum class SessionChannelDirection {
    HostToClient,
    ClientToHost,
    Bidirectional,
}

enum class SessionChannelState {
    Absent,
    Configured,
    Starting,
    Active,
    Stopping,
    Stopped,
    Failed,
}

data class SessionChannel(
    val channelId: ChannelId,
    val kind: SessionChannelKind,
    val direction: SessionChannelDirection = kind.defaultDirection(),
    val state: SessionChannelState = SessionChannelState.Configured,
)

enum class NetworkPathKind {
    Direct,
    Lan,
}

enum class NetworkPathState {
    Candidate,
    Validated,
    Standby,
    Active,
    Degraded,
    Failed,
    Closed,
}

data class NetworkPath(
    val pathId: PathId,
    val kind: NetworkPathKind,
    val state: NetworkPathState = NetworkPathState.Candidate,
)

enum class PeripheralKind {
    Keyboard,
    Mouse,
    Touchscreen,
    Gamepad,
    Stylus,
    Touchpad,
    Microphone,
}

enum class SourcePeripheralPresence {
    Absent,
    Present,
}

enum class TargetPeripheralExposureState {
    NotExposed,
    Active,
    RetainedInactive,
}

/**
 * Session-scoped logical peripheral identity. Equal device slots from separate sessions are
 * intentionally different identities; the kind keeps different peripheral families distinct too.
 */
@ConsistentCopyVisibility
data class LogicalPeripheralId private constructor(
    val sessionId: SessionId,
    val kind: PeripheralKind,
    val deviceSlot: Int,
) {
    companion object {
        fun from(sessionId: SessionId, kind: PeripheralKind, deviceSlot: Int): LogicalPeripheralId? =
            if (deviceSlot in 0..SessionBounds.MAX_LOGICAL_DEVICE_SLOT) {
                LogicalPeripheralId(sessionId, kind, deviceSlot)
            } else {
                null
            }

        fun requireValid(sessionId: SessionId, kind: PeripheralKind, deviceSlot: Int): LogicalPeripheralId =
            requireNotNull(from(sessionId, kind, deviceSlot)) {
                "Logical peripheral deviceSlot must be within the session-local range"
            }
    }
}

data class SessionPeripheral(
    val id: LogicalPeripheralId,
    val sourcePresence: SourcePeripheralPresence = SourcePeripheralPresence.Absent,
    val targetExposure: TargetPeripheralExposureState = TargetPeripheralExposureState.NotExposed,
    val lastPresenceChangeAtMonotonicUs: Long = 0L,
)

/**
 * A session is always exactly one local participant and one remote participant in RFC-005A.
 * Multi-client host topology is represented by multiple independent sessions.
 */
data class SessionCreateRequest(
    val sessionId: SessionId,
    val generation: SessionGeneration = SessionGeneration.Initial,
    val remotePeer: PeerReference,
    val localRole: SessionRole,
    val remoteRole: SessionRole,
    val participantIndex: ParticipantIndex? = null,
    val policy: SessionBehaviorPolicy? = null,
)

data class SessionChannelSnapshot(
    val channelId: ChannelId,
    val kind: SessionChannelKind,
    val direction: SessionChannelDirection,
    val state: SessionChannelState,
)

data class NetworkPathSnapshot(
    val pathId: PathId,
    val kind: NetworkPathKind,
    val state: NetworkPathState,
)

data class SessionPeripheralSnapshot(
    val id: LogicalPeripheralId,
    val sourcePresence: SourcePeripheralPresence,
    val targetExposure: TargetPeripheralExposureState,
    val lastPresenceChangeAtMonotonicUs: Long,
)

data class PeripheralKindCount(
    val kind: PeripheralKind,
    val total: Int,
    val sourcePresent: Int,
    val targetActive: Int,
    val retainedInactive: Int,
)

data class SessionPeripheralCounts(
    val total: Int,
    val sourcePresent: Int,
    val targetActive: Int,
    val retainedInactive: Int,
    val byKind: List<PeripheralKindCount>,
)

data class SessionSnapshot(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val localParticipant: SessionParticipant,
    val remoteParticipant: SessionParticipant,
    val participantIndex: ParticipantIndex?,
    val state: SessionState,
    val channels: List<SessionChannelSnapshot>,
    val paths: List<NetworkPathSnapshot>,
    val activePathId: PathId?,
    val logicalPeripherals: List<SessionPeripheralSnapshot>,
    val peripheralCounts: SessionPeripheralCounts,
    val policy: SessionBehaviorPolicy,
    val createdAtMonotonicUs: Long,
    val lastStateChangeAtMonotonicUs: Long,
    val lastError: SessionError,
) {
    val localDeviceId: DeviceId
        get() = localParticipant.deviceId

    val remoteDeviceId: DeviceId
        get() = remoteParticipant.deviceId

    val localRole: SessionRole
        get() = localParticipant.role

    val remoteRole: SessionRole
        get() = remoteParticipant.role
}

data class SessionStateCount(
    val state: SessionState,
    val count: Int,
)

data class SessionManagerSnapshot(
    val closed: Boolean,
    val activeSessionCount: Int,
    val registeredSessionCount: Int,
    val maxConcurrentClients: Int,
    val maxSessions: Int,
    val hostSessions: Int,
    val clientSessions: Int,
    val sessionsByState: List<SessionStateCount>,
    val activePaths: Int,
    val standbyPaths: Int,
    val logicalPeripheralCounts: SessionPeripheralCounts,
    val policy: SessionBehaviorPolicy,
    val lastError: SessionError,
    val sessions: List<SessionSnapshot>,
)

data class SessionOperationResult(
    val error: SessionError,
    val session: SessionSnapshot? = null,
    val manager: SessionManagerSnapshot? = null,
) {
    val isSuccess: Boolean
        get() = error == SessionError.None
}

fun SessionChannelKind.defaultDirection(): SessionChannelDirection = when (this) {
    SessionChannelKind.Video,
    SessionChannelKind.SystemAudio,
    -> SessionChannelDirection.HostToClient
    SessionChannelKind.MicrophoneAudio,
    SessionChannelKind.Input,
    -> SessionChannelDirection.ClientToHost
    SessionChannelKind.Control,
    SessionChannelKind.Telemetry,
    -> SessionChannelDirection.Bidirectional
}

internal fun SessionState.isLive(): Boolean = when (this) {
    SessionState.Stopped,
    SessionState.Failed,
    SessionState.Closed,
    -> false
    else -> true
}

internal fun SessionState.canTransitionTo(next: SessionState): Boolean {
    if (this == next) return true
    return when (this) {
        SessionState.Created -> next in setOf(
            SessionState.Establishing,
            SessionState.Stopping,
            SessionState.Stopped,
            SessionState.Failed,
            SessionState.Closed,
        )
        SessionState.Establishing -> next in setOf(
            SessionState.Ready,
            SessionState.Stopping,
            SessionState.Failed,
            SessionState.Closed,
        )
        SessionState.Ready -> next in setOf(
            SessionState.Running,
            SessionState.Stopping,
            SessionState.Failed,
            SessionState.Closed,
        )
        SessionState.Running -> next in setOf(
            SessionState.Suspended,
            SessionState.Stopping,
            SessionState.Failed,
            SessionState.Closed,
        )
        SessionState.Suspended -> next in setOf(
            SessionState.Running,
            SessionState.Stopping,
            SessionState.Failed,
            SessionState.Closed,
        )
        SessionState.Stopping -> next in setOf(
            SessionState.Stopped,
            SessionState.Failed,
            SessionState.Closed,
        )
        SessionState.Stopped -> next == SessionState.Closed
        SessionState.Failed -> next == SessionState.Closed
        SessionState.Closed -> false
    }
}

internal fun SessionChannelState.canTransitionTo(next: SessionChannelState): Boolean {
    if (this == next) return true
    return when (this) {
        SessionChannelState.Absent -> next == SessionChannelState.Configured
        SessionChannelState.Configured -> next in setOf(
            SessionChannelState.Starting,
            SessionChannelState.Stopped,
            SessionChannelState.Failed,
        )
        SessionChannelState.Starting -> next in setOf(
            SessionChannelState.Active,
            SessionChannelState.Stopping,
            SessionChannelState.Failed,
        )
        SessionChannelState.Active -> next in setOf(
            SessionChannelState.Stopping,
            SessionChannelState.Failed,
        )
        SessionChannelState.Stopping -> next in setOf(
            SessionChannelState.Stopped,
            SessionChannelState.Failed,
        )
        SessionChannelState.Stopped -> next == SessionChannelState.Configured
        SessionChannelState.Failed -> next == SessionChannelState.Stopped
    }
}

internal fun NetworkPathState.canTransitionTo(next: NetworkPathState): Boolean {
    if (this == next) return true
    return when (this) {
        NetworkPathState.Candidate -> next in setOf(
            NetworkPathState.Validated,
            NetworkPathState.Standby,
            NetworkPathState.Active,
            NetworkPathState.Failed,
            NetworkPathState.Closed,
        )
        NetworkPathState.Validated -> next in setOf(
            NetworkPathState.Standby,
            NetworkPathState.Active,
            NetworkPathState.Degraded,
            NetworkPathState.Failed,
            NetworkPathState.Closed,
        )
        NetworkPathState.Standby -> next in setOf(
            NetworkPathState.Validated,
            NetworkPathState.Active,
            NetworkPathState.Degraded,
            NetworkPathState.Failed,
            NetworkPathState.Closed,
        )
        NetworkPathState.Active -> next in setOf(
            NetworkPathState.Standby,
            NetworkPathState.Degraded,
            NetworkPathState.Failed,
            NetworkPathState.Closed,
        )
        NetworkPathState.Degraded -> next in setOf(
            NetworkPathState.Active,
            NetworkPathState.Standby,
            NetworkPathState.Failed,
            NetworkPathState.Closed,
        )
        NetworkPathState.Failed -> next == NetworkPathState.Closed
        NetworkPathState.Closed -> false
    }
}
