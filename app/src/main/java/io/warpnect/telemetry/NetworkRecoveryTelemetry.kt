package io.warpnect.telemetry

import io.warpnect.NativeBridge
import io.warpnect.session.security.SessionProtectionError

/**
 * Cold-path registration and pre-bound control-plane handles for RFC-005H lifecycle events.
 * These counters are observational and are never consulted by lifecycle decisions.
 */
class SessionLifecycleTelemetry private constructor(
    private val source: TelemetrySource,
) : AutoCloseable {
    val heartbeatSent = source.counter(TelemetryMetricIds.SessionHeartbeatSent)
    val heartbeatAckReceived = source.counter(TelemetryMetricIds.SessionHeartbeatAckReceived)
    val heartbeatMiss = source.counter(TelemetryMetricIds.SessionHeartbeatMiss)
    val suspended = source.counter(TelemetryMetricIds.SessionSuspended)
    val migrationStarted = source.counter(TelemetryMetricIds.SessionPathMigrationStarted)
    val migrationSucceeded = source.counter(TelemetryMetricIds.SessionPathMigrationSucceeded)
    val migrationFailed = source.counter(TelemetryMetricIds.SessionPathMigrationFailed)
    val reconnectAttempt = source.counter(TelemetryMetricIds.SessionReconnectAttempt)
    val reconnectSucceeded = source.counter(TelemetryMetricIds.SessionReconnectSucceeded)
    val reconnectAttemptFailed = source.counter(TelemetryMetricIds.SessionReconnectAttemptFailed)
    val reconnectExpired = source.counter(TelemetryMetricIds.SessionReconnectExpired)
    val reconnectCancelled = source.counter(TelemetryMetricIds.SessionReconnectCancelled)
    val disconnectLocal = source.counter(TelemetryMetricIds.SessionDisconnectLocal)
    val disconnectRemote = source.counter(TelemetryMetricIds.SessionDisconnectRemote)

    override fun close() = source.close()

    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope.Session): SessionLifecycleTelemetry =
            SessionLifecycleTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)

        private val ids = listOf(
            TelemetryMetricIds.SessionHeartbeatSent,
            TelemetryMetricIds.SessionHeartbeatAckReceived,
            TelemetryMetricIds.SessionHeartbeatMiss,
            TelemetryMetricIds.SessionSuspended,
            TelemetryMetricIds.SessionPathMigrationStarted,
            TelemetryMetricIds.SessionPathMigrationSucceeded,
            TelemetryMetricIds.SessionPathMigrationFailed,
            TelemetryMetricIds.SessionReconnectAttempt,
            TelemetryMetricIds.SessionReconnectSucceeded,
            TelemetryMetricIds.SessionReconnectAttemptFailed,
            TelemetryMetricIds.SessionReconnectExpired,
            TelemetryMetricIds.SessionReconnectCancelled,
            TelemetryMetricIds.SessionDisconnectLocal,
            TelemetryMetricIds.SessionDisconnectRemote,
        )
    }
}

/** Path-local platform facts; platform availability is never treated as peer authentication. */
class SessionPathTelemetry private constructor(
    private val source: TelemetrySource,
) : AutoCloseable {
    val active = source.gauge(TelemetryMetricIds.PathActive)
    val validated = source.gauge(TelemetryMetricIds.PathValidated)
    val platformAvailable = source.counter(TelemetryMetricIds.PathPlatformAvailable)
    val platformLosing = source.counter(TelemetryMetricIds.PathPlatformLosing)
    val platformLost = source.counter(TelemetryMetricIds.PathPlatformLost)
    val validationStarted = source.counter(TelemetryMetricIds.PathValidationStarted)
    val validationSucceeded = source.counter(TelemetryMetricIds.PathValidationSucceeded)
    val validationFailed = source.counter(TelemetryMetricIds.PathValidationFailed)

    override fun close() = source.close()

    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope.Path): SessionPathTelemetry =
            SessionPathTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)

        private val ids = listOf(
            TelemetryMetricIds.PathActive,
            TelemetryMetricIds.PathValidated,
            TelemetryMetricIds.PathPlatformAvailable,
            TelemetryMetricIds.PathPlatformLosing,
            TelemetryMetricIds.PathPlatformLost,
            TelemetryMetricIds.PathValidationStarted,
            TelemetryMetricIds.PathValidationSucceeded,
            TelemetryMetricIds.PathValidationFailed,
        )
    }
}

/**
 * SessionControl is a cold Kotlin path, but it uses the same local aggregate semantics as a
 * protected Channel. It deliberately owns no endpoint, packet number, or payload metadata.
 */
class SessionControlNetworkTelemetry private constructor(
    private val source: TelemetrySource,
) : AutoCloseable {
    private val udpDatagramSent = source.counter(TelemetryMetricIds.UdpDatagramSent)
    private val udpByteSent = source.counter(TelemetryMetricIds.UdpByteSent)
    private val udpDatagramReceived = source.counter(TelemetryMetricIds.UdpDatagramReceived)
    private val udpByteReceived = source.counter(TelemetryMetricIds.UdpByteReceived)
    private val udpSendError = source.counter(TelemetryMetricIds.UdpSendError)
    private val recordProduced = source.counter(TelemetryMetricIds.ProtectionRecordProduced)
    private val recordAccepted = source.counter(TelemetryMetricIds.ProtectionRecordAccepted)
    private val protectError = source.counter(TelemetryMetricIds.ProtectionProtectError)
    private val authenticationFailed = source.counter(TelemetryMetricIds.ProtectionAuthenticationFailed)
    private val replayDropped = source.counter(TelemetryMetricIds.ProtectionReplayDropped)
    private val unknownContext = source.counter(TelemetryMetricIds.ProtectionUnknownContext)
    private val endpointMismatch = source.counter(TelemetryMetricIds.ProtectionEndpointMismatch)
    private val epochRejected = source.counter(TelemetryMetricIds.ProtectionEpochRejected)
    private val malformed = source.counter(TelemetryMetricIds.ProtectionMalformed)

    fun recordProduced() = recordProduced.increment()
    fun udpSent(bytes: Int) {
        udpDatagramSent.increment()
        udpByteSent.add(bytes.toULong())
    }
    fun udpReceived(bytes: Int) {
        udpDatagramReceived.increment()
        udpByteReceived.add(bytes.toULong())
    }
    fun udpSendError() = udpSendError.increment()
    fun protectError() = protectError.increment()
    fun recordAccepted() = recordAccepted.increment()

    fun recordUnprotectError(error: SessionProtectionError) = when (error) {
        SessionProtectionError.None -> recordAccepted()
        SessionProtectionError.AuthFailure -> authenticationFailed.increment()
        SessionProtectionError.ReplayDuplicate,
        SessionProtectionError.ReplayTooOld,
        -> replayDropped.increment()
        SessionProtectionError.UnknownContext -> unknownContext.increment()
        SessionProtectionError.EndpointMismatch -> endpointMismatch.increment()
        SessionProtectionError.InvalidEpoch,
        SessionProtectionError.FutureEpoch,
        -> epochRejected.increment()
        SessionProtectionError.InvalidEnvelope,
        SessionProtectionError.UnsupportedProtectionVersion,
        SessionProtectionError.DatagramTooSmall,
        SessionProtectionError.DatagramTooLarge,
        -> malformed.increment()
        else -> protectError()
    }

    override fun close() = source.close()

    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope.Session): SessionControlNetworkTelemetry =
            SessionControlNetworkTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)

        private val ids = listOf(
            TelemetryMetricIds.UdpDatagramSent,
            TelemetryMetricIds.UdpByteSent,
            TelemetryMetricIds.UdpDatagramReceived,
            TelemetryMetricIds.UdpByteReceived,
            TelemetryMetricIds.UdpSendError,
            TelemetryMetricIds.ProtectionRecordProduced,
            TelemetryMetricIds.ProtectionRecordAccepted,
            TelemetryMetricIds.ProtectionProtectError,
            TelemetryMetricIds.ProtectionAuthenticationFailed,
            TelemetryMetricIds.ProtectionReplayDropped,
            TelemetryMetricIds.ProtectionUnknownContext,
            TelemetryMetricIds.ProtectionEndpointMismatch,
            TelemetryMetricIds.ProtectionEpochRejected,
            TelemetryMetricIds.ProtectionMalformed,
        )
    }
}

/**
 * One native WNTM source per protected Channel. Kotlin only owns its bounded source identity and
 * scope; packet updates remain native and reach Kotlin only in the existing snapshot batch.
 */
class NativeChannelNetworkTelemetry private constructor(
    private val source: TelemetrySource,
    val sourceId: TelemetrySourceId?,
) : AutoCloseable {
    override fun close() {
        sourceId?.let {
            NativeTelemetrySourceScopes.remove(it)
            runCatching { NativeBridge.runtimeTelemetryUnregisterSource(it.value.toLong()) }
        }
        source.close()
    }

    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope.Channel): NativeChannelNetworkTelemetry {
            val source = hub.registerSource(TelemetrySourceDefinition(scope, emptyList())).source
            val id = source.sourceId ?: return NativeChannelNetworkTelemetry(source, null)
            val registered = runCatching {
                NativeBridge.runtimeTelemetryRegisterSource(id.value.toLong(), metricIds, metricKinds)
            }.getOrDefault(false)
            return if (registered) {
                NativeTelemetrySourceScopes.put(id, scope)
                NativeChannelNetworkTelemetry(source, id)
            } else {
                source.close()
                NativeChannelNetworkTelemetry(DisabledNetworkNativeTelemetrySource, null)
            }
        }

        private val metricIds = shortArrayOf(
            0x0201, 0x0202, 0x0203, 0x0204, 0x0205, 0x0206, 0x0207, 0x0208, 0x0209,
            0x0221, 0x0222, 0x0223, 0x0224, 0x0225, 0x0226,
            0x0231, 0x0232, 0x0233, 0x0234,
            0x0241, 0x0242, 0x0243, 0x0244,
            0x0701, 0x0702, 0x0703, 0x0704, 0x0705, 0x0706, 0x0707, 0x0708, 0x0709,
        )
        private val metricKinds = ByteArray(metricIds.size) { TelemetryMetricKind.CounterU64.bridgeId.toByte() }
    }
}

private object DisabledNetworkNativeTelemetrySource : TelemetrySource {
    override val sourceId: TelemetrySourceId? = null
    override val enabled = false
    override fun counter(id: TelemetryMetricId) = DisabledTelemetryCounter
    override fun gauge(id: TelemetryMetricId) = DisabledTelemetryGauge
    override fun histogram(id: TelemetryMetricId) = DisabledTelemetryHistogram
    override fun close() = Unit
}
