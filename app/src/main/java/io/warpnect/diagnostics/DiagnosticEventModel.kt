@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.telemetry.ClockDomainId
import io.warpnect.telemetry.TelemetryComponent
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetryScopeKind
import io.warpnect.telemetry.TelemetrySourceId

/** Local diagnostic schema version. It is neither an SCL nor a network protocol version. */
const val DIAGNOSTIC_EVENT_MODEL_VERSION = 1
const val MAX_DIAGNOSTIC_EVENT_DESCRIPTORS = 256
const val MAX_DIAGNOSTIC_EVENT_FIELDS = 4
const val MAX_KOTLIN_DIAGNOSTIC_EVENTS = 1024
const val MAX_NATIVE_DIAGNOSTIC_EVENTS = 1024
const val DEFAULT_DIAGNOSTIC_SNAPSHOT_LIMIT = 256
const val MAX_DIAGNOSTIC_SNAPSHOT_LIMIT = 512
const val MAX_NATIVE_DIAGNOSTIC_EVENT_BYTES = 128 * 1024

@JvmInline
value class DiagnosticEventTypeId(
    val value: Int,
) {
    init {
        require(value in 1..0xffff) { "DiagnosticEventTypeId must be a non-zero u16" }
    }
}

enum class DiagnosticSeverity(
    val bridgeId: Int,
) {
    Debug(1),
    Info(2),
    Warning(3),
    Error(4),
    Critical(5),
    ;

    companion object {
        fun fromBridgeId(value: Int): DiagnosticSeverity? = entries.firstOrNull { it.bridgeId == value }
    }
}

enum class DiagnosticScalarKind {
    Unsigned,
    Signed,
    Boolean,
    Enum,
}

enum class DiagnosticPayloadFieldKey {
    Reason,
    FromState,
    ToState,
    PathId,
    TargetPathId,
    Attempt,
    RawCode,
    Count,
    DurationUs,
    OldGeneration,
    NewGeneration,
}

data class DiagnosticPayloadFieldDescriptor(
    val key: DiagnosticPayloadFieldKey,
    val kind: DiagnosticScalarKind,
)

data class DiagnosticEventDescriptor(
    val id: DiagnosticEventTypeId,
    val canonicalName: String,
    val defaultSeverity: DiagnosticSeverity,
    val allowedScopes: Set<TelemetryScopeKind>,
    val payload: List<DiagnosticPayloadFieldDescriptor>,
    val description: String,
) {
    init {
        require(canonicalName.matches(Regex("warpnect\\.event(\\.[a-z0-9_]+)+"))) {
            "Diagnostic canonical name is invalid: $canonicalName"
        }
        require(payload.size <= MAX_DIAGNOSTIC_EVENT_FIELDS) { "Diagnostic payload exceeds four fields" }
    }
}

/** Bounded normalized reasons. Raw platform error text is intentionally not retained. */
enum class DiagnosticReason(
    val code: ULong,
) {
    None(0u),
    UserRequested(1u),
    ApplicationStopping(2u),
    PolicyChange(3u),
    Timeout(4u),
    Capacity(5u),
    TrustMismatch(6u),
    AuthenticationFailure(7u),
    PlatformUnavailable(8u),
    NetworkLost(9u),
    ValidationFailure(10u),
    CodecFailure(11u),
    AudioFailure(12u),
    InputInjectionFailure(13u),
    RecoveryExpired(14u),
    SupersededGeneration(15u),
    FatalInternalError(16u),
}

/** Explicit local lifecycle values; these are not SessionLifecycle enum ordinals. */
enum class DiagnosticSessionState(
    val code: ULong,
) {
    Prepared(1u),
    Active(2u),
    Suspended(3u),
    Reconnecting(4u),
    Disconnecting(5u),
    Closed(6u),
    Failed(7u),
}

enum class DiagnosticScopeKind(
    val bridgeId: Int,
) {
    Process(1),
    Session(2),
    Path(3),
    Channel(4),
    Component(5),
    ;

    companion object {
        fun fromBridgeId(value: Int): DiagnosticScopeKind? = entries.firstOrNull { it.bridgeId == value }
    }
}

/** Immutable, compact retained scope data. It remains useful after a source or Session closes. */
data class DiagnosticScopeSnapshot(
    val kind: DiagnosticScopeKind,
    val sourceId: UInt = 0u,
    val sessionGeneration: UInt = 0u,
    val sessionIdHigh: ULong = 0u,
    val sessionIdLow: ULong = 0u,
    val pathId: UInt = 0u,
    val channelId: UInt = 0u,
    val pathKind: Int = 0,
    val channelKind: Int = 0,
    val channelDirection: Int = 0,
    val componentKind: Int = 0,
) {
    companion object {
        fun from(scope: TelemetryScope, sourceId: TelemetrySourceId? = null): DiagnosticScopeSnapshot = when (scope) {
            TelemetryScope.Process -> DiagnosticScopeSnapshot(DiagnosticScopeKind.Process, sourceId?.value ?: 0u)
            is TelemetryScope.Session -> DiagnosticScopeSnapshot(
                kind = DiagnosticScopeKind.Session,
                sourceId = sourceId?.value ?: 0u,
                sessionGeneration = scope.generation.value,
                sessionIdHigh = scope.sessionId.high,
                sessionIdLow = scope.sessionId.low,
            )
            is TelemetryScope.Path -> DiagnosticScopeSnapshot(
                kind = DiagnosticScopeKind.Path,
                sourceId = sourceId?.value ?: 0u,
                sessionGeneration = scope.generation.value,
                sessionIdHigh = scope.sessionId.high,
                sessionIdLow = scope.sessionId.low,
                pathId = scope.pathId.value,
                pathKind = scope.pathKind.diagnosticCode(),
            )
            is TelemetryScope.Channel -> DiagnosticScopeSnapshot(
                kind = DiagnosticScopeKind.Channel,
                sourceId = sourceId?.value ?: 0u,
                sessionGeneration = scope.generation.value,
                sessionIdHigh = scope.sessionId.high,
                sessionIdLow = scope.sessionId.low,
                channelId = scope.channelId.value,
                channelKind = scope.channelKind.diagnosticCode(),
                channelDirection = scope.direction.diagnosticCode(),
            )
            is TelemetryScope.Component -> DiagnosticScopeSnapshot(
                kind = DiagnosticScopeKind.Component,
                sourceId = sourceId?.value ?: 0u,
                componentKind = scope.component.diagnosticCode(),
            )
        }
    }
}

data class DiagnosticEventRecord(
    val sequence: ULong,
    val timestampNs: ULong,
    val clockDomain: ClockDomainId,
    val severity: DiagnosticSeverity,
    val scope: DiagnosticScopeSnapshot,
    val typeId: DiagnosticEventTypeId,
    val payload: ULongArray,
)

internal fun ClockDomainId.diagnosticBridgeId(): Int = when (this) {
    ClockDomainId.AndroidMonotonic -> 1
    ClockDomainId.AndroidBootTime -> 2
    ClockDomainId.AndroidUptime -> 3
    ClockDomainId.NativeSteady -> 4
}

internal fun diagnosticClockDomainFromBridgeId(value: Int): ClockDomainId? = when (value) {
    1 -> ClockDomainId.AndroidMonotonic
    2 -> ClockDomainId.AndroidBootTime
    3 -> ClockDomainId.AndroidUptime
    4 -> ClockDomainId.NativeSteady
    else -> null
}

object DiagnosticEventIds {
    val HistoryStarted = DiagnosticEventTypeId(0x0001)
    val ProviderFailed = DiagnosticEventTypeId(0x0002)
    val NativeBridgeMalformed = DiagnosticEventTypeId(0x0003)

    val SessionStateChanged = DiagnosticEventTypeId(0x0101)
    val SessionRunning = DiagnosticEventTypeId(0x0102)
    val SessionStartFailed = DiagnosticEventTypeId(0x0103)
    val SessionSuspended = DiagnosticEventTypeId(0x0104)
    val MigrationStarted = DiagnosticEventTypeId(0x0105)
    val MigrationSucceeded = DiagnosticEventTypeId(0x0106)
    val MigrationFailed = DiagnosticEventTypeId(0x0107)
    val ReconnectStarted = DiagnosticEventTypeId(0x0108)
    val ReconnectAttemptFailed = DiagnosticEventTypeId(0x0109)
    val ReconnectSucceeded = DiagnosticEventTypeId(0x010a)
    val ReconnectExpired = DiagnosticEventTypeId(0x010b)
    val ReconnectCancelled = DiagnosticEventTypeId(0x010c)
    val DisconnectLocal = DiagnosticEventTypeId(0x010d)
    val DisconnectRemote = DiagnosticEventTypeId(0x010e)
    val PairingStarted = DiagnosticEventTypeId(0x0120)
    val PairingSasReady = DiagnosticEventTypeId(0x0121)
    val PairingSucceeded = DiagnosticEventTypeId(0x0122)
    val PairingFailed = DiagnosticEventTypeId(0x0123)
    val HandshakeStarted = DiagnosticEventTypeId(0x0130)
    val HandshakeSucceeded = DiagnosticEventTypeId(0x0131)
    val HandshakeFailed = DiagnosticEventTypeId(0x0132)
    val CapabilityFailed = DiagnosticEventTypeId(0x0140)
    val SetupFailed = DiagnosticEventTypeId(0x0141)

    val PathPlatformLosing = DiagnosticEventTypeId(0x0201)
    val PathPlatformLost = DiagnosticEventTypeId(0x0202)
    val PathValidationFailed = DiagnosticEventTypeId(0x0203)
    val SocketRebindSucceeded = DiagnosticEventTypeId(0x0204)
    val SocketRebindFailed = DiagnosticEventTypeId(0x0205)

    val VideoEncoderStarted = DiagnosticEventTypeId(0x0301)
    val VideoEncoderFailed = DiagnosticEventTypeId(0x0302)
    val VideoDecoderStarted = DiagnosticEventTypeId(0x0303)
    val VideoDecoderFailed = DiagnosticEventTypeId(0x0304)
    val VideoRenderTargetUnavailable = DiagnosticEventTypeId(0x0305)

    val AudioCaptureStarted = DiagnosticEventTypeId(0x0401)
    val AudioCaptureFailed = DiagnosticEventTypeId(0x0402)
    val AudioPlaybackStarted = DiagnosticEventTypeId(0x0403)
    val AudioPlaybackFailed = DiagnosticEventTypeId(0x0404)
    val AudioEncoderFailed = DiagnosticEventTypeId(0x0405)
    val AudioDecoderFailed = DiagnosticEventTypeId(0x0406)

    val InputInjectionServiceAvailable = DiagnosticEventTypeId(0x0501)
    val InputInjectionServiceLost = DiagnosticEventTypeId(0x0502)
    val InputSafetyReset = DiagnosticEventTypeId(0x0503)
    val InputInjectionFailedFatal = DiagnosticEventTypeId(0x0504)

    val ClockSyncQualified = DiagnosticEventTypeId(0x0601)
    val ClockSyncUnqualified = DiagnosticEventTypeId(0x0602)

    val TrustKeyMismatch = DiagnosticEventTypeId(0x0701)
    val ProtectionRuntimeFailed = DiagnosticEventTypeId(0x0702)

    val PrivilegedServiceUnavailable = DiagnosticEventTypeId(0x0801)
    val PrivilegedServiceRestored = DiagnosticEventTypeId(0x0802)
}

object DiagnosticEventDescriptorCatalog {
    private val process = setOf(TelemetryScopeKind.Process)
    private val session = setOf(TelemetryScopeKind.Session)
    private val path = setOf(TelemetryScopeKind.Path)
    private val component = setOf(TelemetryScopeKind.Component)
    private val media = setOf(TelemetryScopeKind.Channel, TelemetryScopeKind.Component)
    private val sessionMedia =
        setOf(TelemetryScopeKind.Session, TelemetryScopeKind.Channel, TelemetryScopeKind.Component)
    private val reason = DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.Reason, DiagnosticScalarKind.Enum)
    private val rawCode =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.RawCode, DiagnosticScalarKind.Signed)
    private val fromState =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.FromState, DiagnosticScalarKind.Enum)
    private val toState = DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.ToState, DiagnosticScalarKind.Enum)
    private val pathId =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.PathId, DiagnosticScalarKind.Unsigned)
    private val targetPathId =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.TargetPathId, DiagnosticScalarKind.Unsigned)
    private val attempt =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.Attempt, DiagnosticScalarKind.Unsigned)
    private val oldGeneration =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.OldGeneration, DiagnosticScalarKind.Unsigned)
    private val newGeneration =
        DiagnosticPayloadFieldDescriptor(DiagnosticPayloadFieldKey.NewGeneration, DiagnosticScalarKind.Unsigned)

    val descriptors: List<DiagnosticEventDescriptor> = listOf(
        event(
            DiagnosticEventIds.HistoryStarted,
            "diagnostic.history_started",
            DiagnosticSeverity.Info,
            process,
            emptyList(),
            "Diagnostic history became available.",
        ),
        event(
            DiagnosticEventIds.ProviderFailed,
            "diagnostic.provider_failed",
            DiagnosticSeverity.Warning,
            process,
            listOf(reason),
            "A diagnostic provider failed on the cold path.",
        ),
        event(
            DiagnosticEventIds.NativeBridgeMalformed,
            "diagnostic.native_bridge_malformed",
            DiagnosticSeverity.Error,
            process,
            emptyList(),
            "A WNDE batch failed validation.",
        ),
        event(
            DiagnosticEventIds.SessionStateChanged,
            "session.state_changed",
            DiagnosticSeverity.Info,
            session,
            listOf(fromState, toState),
            "Accepted lifecycle transition.",
        ),
        event(
            DiagnosticEventIds.SessionRunning,
            "session.running",
            DiagnosticSeverity.Info,
            session,
            emptyList(),
            "All committed local pipelines are running.",
        ),
        event(
            DiagnosticEventIds.SessionStartFailed,
            "session.start_failed",
            DiagnosticSeverity.Error,
            session,
            listOf(reason, rawCode),
            "Session establishment failed.",
        ),
        event(
            DiagnosticEventIds.SessionSuspended,
            "session.suspended",
            DiagnosticSeverity.Warning,
            session,
            listOf(reason),
            "All usable paths were lost.",
        ),
        event(
            DiagnosticEventIds.MigrationStarted,
            "session.path_migration_started",
            DiagnosticSeverity.Info,
            session,
            listOf(pathId, targetPathId),
            "A same-generation migration transaction started.",
        ),
        event(
            DiagnosticEventIds.MigrationSucceeded,
            "session.path_migration_succeeded",
            DiagnosticSeverity.Info,
            session,
            listOf(pathId, targetPathId),
            "A same-generation migration committed.",
        ),
        event(
            DiagnosticEventIds.MigrationFailed,
            "session.path_migration_failed",
            DiagnosticSeverity.Warning,
            session,
            listOf(pathId, targetPathId, reason),
            "A migration transaction failed.",
        ),
        event(
            DiagnosticEventIds.ReconnectStarted,
            "session.reconnect_started",
            DiagnosticSeverity.Warning,
            session,
            listOf(oldGeneration, newGeneration),
            "Fresh-generation reconnect started.",
        ),
        event(
            DiagnosticEventIds.ReconnectAttemptFailed,
            "session.reconnect_attempt_failed",
            DiagnosticSeverity.Warning,
            session,
            listOf(attempt, reason),
            "A bounded reconnect attempt failed.",
        ),
        event(
            DiagnosticEventIds.ReconnectSucceeded,
            "session.reconnect_succeeded",
            DiagnosticSeverity.Info,
            session,
            listOf(oldGeneration, newGeneration),
            "Fresh generation became active.",
        ),
        event(
            DiagnosticEventIds.ReconnectExpired,
            "session.reconnect_expired",
            DiagnosticSeverity.Error,
            session,
            emptyList(),
            "Recovery lease expired.",
        ),
        event(
            DiagnosticEventIds.ReconnectCancelled,
            "session.reconnect_cancelled",
            DiagnosticSeverity.Info,
            session,
            listOf(reason),
            "Recovery was cancelled locally.",
        ),
        event(
            DiagnosticEventIds.DisconnectLocal,
            "session.disconnect_local",
            DiagnosticSeverity.Info,
            session,
            listOf(reason),
            "Local disconnect accepted.",
        ),
        event(
            DiagnosticEventIds.DisconnectRemote,
            "session.disconnect_remote",
            DiagnosticSeverity.Info,
            session,
            listOf(reason),
            "Authenticated remote disconnect accepted.",
        ),
        event(
            DiagnosticEventIds.PairingStarted,
            "pairing.started",
            DiagnosticSeverity.Info,
            component,
            emptyList(),
            "Explicit pairing began.",
        ),
        event(
            DiagnosticEventIds.PairingSasReady,
            "pairing.sas_ready",
            DiagnosticSeverity.Info,
            component,
            emptyList(),
            "SAS is ready for explicit local verification.",
        ),
        event(
            DiagnosticEventIds.PairingSucceeded,
            "pairing.succeeded",
            DiagnosticSeverity.Info,
            component,
            emptyList(),
            "Trusted-peer store accepted pairing.",
        ),
        event(
            DiagnosticEventIds.PairingFailed,
            "pairing.failed",
            DiagnosticSeverity.Warning,
            component,
            listOf(reason),
            "Pairing reached a terminal failure.",
        ),
        event(
            DiagnosticEventIds.HandshakeStarted,
            "handshake.started",
            DiagnosticSeverity.Info,
            component,
            emptyList(),
            "Authenticated handshake began.",
        ),
        event(
            DiagnosticEventIds.HandshakeSucceeded,
            "handshake.succeeded",
            DiagnosticSeverity.Info,
            component,
            emptyList(),
            "Authenticated handshake completed.",
        ),
        event(
            DiagnosticEventIds.HandshakeFailed,
            "handshake.failed",
            DiagnosticSeverity.Warning,
            component,
            listOf(reason),
            "Handshake reached a terminal failure.",
        ),
        event(
            DiagnosticEventIds.CapabilityFailed,
            "session.capability_negotiation_failed",
            DiagnosticSeverity.Warning,
            session,
            listOf(reason),
            "Capability negotiation failed.",
        ),
        event(
            DiagnosticEventIds.SetupFailed,
            "session.setup_failed",
            DiagnosticSeverity.Error,
            session,
            listOf(reason),
            "Session setup failed.",
        ),
        event(
            DiagnosticEventIds.PathPlatformLosing,
            "network.path_platform_losing",
            DiagnosticSeverity.Warning,
            path,
            listOf(pathId),
            "Local platform reported a path as losing.",
        ),
        event(
            DiagnosticEventIds.PathPlatformLost,
            "network.path_platform_lost",
            DiagnosticSeverity.Warning,
            path,
            listOf(pathId),
            "Local platform reported a path loss.",
        ),
        event(
            DiagnosticEventIds.PathValidationFailed,
            "network.path_validation_failed",
            DiagnosticSeverity.Warning,
            path,
            listOf(pathId, reason),
            "Authenticated candidate validation failed.",
        ),
        event(
            DiagnosticEventIds.SocketRebindSucceeded,
            "network.socket_rebind_succeeded",
            DiagnosticSeverity.Info,
            session,
            listOf(pathId, targetPathId),
            "Live socket binding was rebound.",
        ),
        event(
            DiagnosticEventIds.SocketRebindFailed,
            "network.socket_rebind_failed",
            DiagnosticSeverity.Error,
            session,
            listOf(pathId, targetPathId, reason),
            "Live socket binding could not be rebound.",
        ),
        event(
            DiagnosticEventIds.VideoEncoderStarted,
            "video.encoder_started",
            DiagnosticSeverity.Info,
            media,
            emptyList(),
            "Video encoder started.",
        ),
        event(
            DiagnosticEventIds.VideoEncoderFailed,
            "video.encoder_failed",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Video encoder failed.",
        ),
        event(
            DiagnosticEventIds.VideoDecoderStarted,
            "video.decoder_started",
            DiagnosticSeverity.Info,
            media,
            emptyList(),
            "Video decoder started.",
        ),
        event(
            DiagnosticEventIds.VideoDecoderFailed,
            "video.decoder_failed",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Video decoder failed.",
        ),
        event(
            DiagnosticEventIds.VideoRenderTargetUnavailable,
            "video.render_target_unavailable",
            DiagnosticSeverity.Warning,
            media,
            emptyList(),
            "Video render target was unavailable.",
        ),
        event(
            DiagnosticEventIds.AudioCaptureStarted,
            "audio.capture_started",
            DiagnosticSeverity.Info,
            media,
            emptyList(),
            "Audio capture started.",
        ),
        event(
            DiagnosticEventIds.AudioCaptureFailed,
            "audio.capture_failed",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Audio capture failed.",
        ),
        event(
            DiagnosticEventIds.AudioPlaybackStarted,
            "audio.playback_started",
            DiagnosticSeverity.Info,
            media,
            emptyList(),
            "Audio playback started.",
        ),
        event(
            DiagnosticEventIds.AudioPlaybackFailed,
            "audio.playback_failed",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Audio playback failed.",
        ),
        event(
            DiagnosticEventIds.AudioEncoderFailed,
            "audio.encoder_failed",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Audio encoder failed.",
        ),
        event(
            DiagnosticEventIds.AudioDecoderFailed,
            "audio.decoder_failed",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Audio decoder failed.",
        ),
        event(
            DiagnosticEventIds.InputInjectionServiceAvailable,
            "input.injection_service_available",
            DiagnosticSeverity.Info,
            media,
            emptyList(),
            "Input injection service became available.",
        ),
        event(
            DiagnosticEventIds.InputInjectionServiceLost,
            "input.injection_service_lost",
            DiagnosticSeverity.Warning,
            media,
            emptyList(),
            "Input injection service was lost.",
        ),
        event(
            DiagnosticEventIds.InputSafetyReset,
            "input.safety_reset",
            DiagnosticSeverity.Warning,
            sessionMedia,
            listOf(reason),
            "Input safety reset was applied.",
        ),
        event(
            DiagnosticEventIds.InputInjectionFailedFatal,
            "input.injection_failed_fatal",
            DiagnosticSeverity.Error,
            media,
            listOf(reason, rawCode),
            "Privileged input injection failed terminally.",
        ),
        event(
            DiagnosticEventIds.ClockSyncQualified,
            "clock.sync_qualified",
            DiagnosticSeverity.Info,
            session,
            emptyList(),
            "ClockSync qualification changed to qualified.",
        ),
        event(
            DiagnosticEventIds.ClockSyncUnqualified,
            "clock.sync_unqualified",
            DiagnosticSeverity.Warning,
            session,
            listOf(reason),
            "ClockSync qualification was lost.",
        ),
        event(
            DiagnosticEventIds.TrustKeyMismatch,
            "security.trust_key_mismatch",
            DiagnosticSeverity.Error,
            component,
            emptyList(),
            "Trusted peer identity key mismatched.",
        ),
        event(
            DiagnosticEventIds.ProtectionRuntimeFailed,
            "security.protection_runtime_failed",
            DiagnosticSeverity.Critical,
            session,
            listOf(reason, rawCode),
            "Protection runtime reached a terminal failure.",
        ),
        event(
            DiagnosticEventIds.PrivilegedServiceUnavailable,
            "platform.privileged_service_unavailable",
            DiagnosticSeverity.Error,
            component,
            listOf(reason),
            "A privileged service was unavailable.",
        ),
        event(
            DiagnosticEventIds.PrivilegedServiceRestored,
            "platform.privileged_service_restored",
            DiagnosticSeverity.Info,
            component,
            emptyList(),
            "A privileged service was restored.",
        ),
    )

    private val byId: Array<DiagnosticEventDescriptor?> = arrayOfNulls(0x1_0000)

    init {
        validate(descriptors)
        descriptors.forEach { byId[it.id.value] = it }
    }

    fun descriptorFor(id: DiagnosticEventTypeId): DiagnosticEventDescriptor? = byId[id.value]

    fun validate(candidate: List<DiagnosticEventDescriptor>) {
        require(candidate.size <= MAX_DIAGNOSTIC_EVENT_DESCRIPTORS)
        require(candidate.map { it.id }.toSet().size == candidate.size) { "Diagnostic EventTypeIds must be unique" }
        require(
            candidate.map { it.canonicalName }.toSet().size == candidate.size,
        ) { "Diagnostic event names must be unique" }
        require(candidate.all { it.payload.size <= MAX_DIAGNOSTIC_EVENT_FIELDS && it.allowedScopes.isNotEmpty() })
    }

    private fun event(
        id: DiagnosticEventTypeId,
        suffix: String,
        severity: DiagnosticSeverity,
        scopes: Set<TelemetryScopeKind>,
        payload: List<DiagnosticPayloadFieldDescriptor>,
        description: String,
    ) = DiagnosticEventDescriptor(id, "warpnect.event.$suffix", severity, scopes, payload, description)
}

private fun NetworkPathKind.diagnosticCode(): Int = when (this) {
    NetworkPathKind.Direct -> 1
    NetworkPathKind.Lan -> 2
}

private fun SessionChannelKind.diagnosticCode(): Int = when (this) {
    SessionChannelKind.Control -> 1
    SessionChannelKind.Video -> 2
    SessionChannelKind.SystemAudio -> 3
    SessionChannelKind.MicrophoneAudio -> 4
    SessionChannelKind.Input -> 5
    SessionChannelKind.Telemetry -> 6
}

private fun SessionChannelDirection.diagnosticCode(): Int = when (this) {
    SessionChannelDirection.HostToClient -> 1
    SessionChannelDirection.ClientToHost -> 2
    SessionChannelDirection.Bidirectional -> 3
}

private fun TelemetryComponent.diagnosticCode(): Int = when (this) {
    TelemetryComponent.VideoEncoder -> 1
    TelemetryComponent.VideoDecoder -> 2
    TelemetryComponent.SystemAudioEncoder -> 3
    TelemetryComponent.SystemAudioDecoder -> 4
    TelemetryComponent.MicrophoneAudioEncoder -> 5
    TelemetryComponent.MicrophoneAudioDecoder -> 6
    TelemetryComponent.AudioPlayback -> 7
    TelemetryComponent.AudioCapture -> 8
    TelemetryComponent.InputCapture -> 9
    TelemetryComponent.InputReceiver -> 10
    TelemetryComponent.InputInjection -> 11
    TelemetryComponent.SessionControl -> 12
    TelemetryComponent.Protection -> 13
    TelemetryComponent.Discovery -> 14
    TelemetryComponent.Pairing -> 15
    TelemetryComponent.Handshake -> 16
    TelemetryComponent.CapabilityNegotiation -> 17
    TelemetryComponent.SessionSetup -> 18
    TelemetryComponent.SessionLifecycle -> 19
}
