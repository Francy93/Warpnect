package io.warpnect.session.integration

import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.lifecycle.DisconnectReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small application-facing owner for the normal Host/Client actions. It has no endpoint fields:
 * a Client connection is always constructed from an RFC-005B [DiscoveredPresence]. Protocol work
 * remains in the two existing [SecureSessionCoordinator] instances.
 */
class SecureSessionApplicationController(
    private val client: SecureSessionCoordinator,
    private val host: SecureSessionCoordinator,
    private val requestFactory: SecureSessionConnectRequestFactory,
) : AutoCloseable {
    private val lock = Any()
    private var activeRole: SessionRole? = null
    private var closed = false
    private val _snapshot = MutableStateFlow(snapshotLocked())
    val snapshot: StateFlow<SecureSessionApplicationSnapshot> = _snapshot.asStateFlow()

    fun startClientDiscovery(): SessionIntegrationResult = synchronized(lock) {
        if (closed) return@synchronized closedResult(client)
        if (activeRole != null && activeRole != SessionRole.Client) return@synchronized busyResult(client)
        activeRole = SessionRole.Client
        client.startDiscovery().also { publishLocked() }
    }

    fun startHost(): SessionIntegrationResult = synchronized(lock) {
        if (closed) return@synchronized closedResult(host)
        if (activeRole != null && activeRole != SessionRole.Host) return@synchronized busyResult(host)
        activeRole = SessionRole.Host
        host.startDiscovery().also { publishLocked() }
    }

    fun discoveredHosts(): List<DiscoveredPresence> = synchronized(lock) {
        if (closed || activeRole != SessionRole.Client) emptyList() else client.discoveredPresences()
    }

    fun connect(presence: DiscoveredPresence): SessionIntegrationResult = synchronized(lock) {
        if (closed) return@synchronized closedResult(client)
        if (activeRole != SessionRole.Client) return@synchronized busyResult(client)
        val request = requestFactory.create(presence) ?: return@synchronized invalidPresenceResult(client)
        client.connect(request).also { publishLocked() }
    }

    fun beginExplicitPairing(): SessionIntegrationResult = synchronized(lock) {
        client.beginExplicitPairing().also { publishLocked() }
    }

    fun approvePairing(): SessionIntegrationResult = synchronized(lock) {
        when (activeRole) {
            SessionRole.Client -> client.approvePairing()
            SessionRole.Host -> host.approvePairing()
            null -> SessionIntegrationResult(SecureSessionIntegrationError.Busy, client.snapshot.value)
        }.also { publishLocked() }
    }

    fun stopHost() = synchronized(lock) {
        if (closed) return@synchronized closedResult(host)
        if (activeRole != SessionRole.Host) return@synchronized busyResult(host)
        host.stopHostReadiness().also {
            activeRole = null
            publishLocked()
        }
    }

    fun disconnect() = synchronized(lock) {
        val coordinator = when (activeRole) {
            SessionRole.Client -> client
            SessionRole.Host -> host
            null -> return@synchronized
        }
        coordinator.disconnect(DisconnectReason.UserRequested)
        activeRole = null
        publishLocked()
    }

    /** Called from the existing control owner, never from an audio, codec, or input callback. */
    fun advance() = synchronized(lock) {
        if (!closed && activeRole != null) {
            when (activeRole) {
                SessionRole.Client -> client.advance()
                SessionRole.Host -> host.advance()
                null -> Unit
            }
            publishLocked()
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        client.close()
        host.close()
        activeRole = null
        publishLocked()
    }

    private fun snapshotLocked(): SecureSessionApplicationSnapshot {
        val role = activeRole
        val coordinator = when (role) {
            SessionRole.Client -> client
            SessionRole.Host -> host
            null -> null
        }
        return SecureSessionApplicationSnapshot(
            activeRole = role,
            client = client.snapshot.value,
            host = host.snapshot.value,
            discoveredHostCount = if (role == SessionRole.Client && !closed) client.discoveredPresences().size else 0,
            closed = closed,
            active = coordinator?.snapshot?.value,
        )
    }

    private fun publishLocked() {
        _snapshot.value = snapshotLocked()
    }

    private fun closedResult(coordinator: SecureSessionCoordinator) = SessionIntegrationResult(
        SecureSessionIntegrationError.Closed,
        coordinator.snapshot.value,
    )

    private fun busyResult(coordinator: SecureSessionCoordinator) = SessionIntegrationResult(
        SecureSessionIntegrationError.Busy,
        coordinator.snapshot.value,
    )

    private fun invalidPresenceResult(coordinator: SecureSessionCoordinator) = SessionIntegrationResult(
        SecureSessionIntegrationError.InvalidPresence,
        coordinator.snapshot.value,
    )
}

fun interface SecureSessionConnectRequestFactory {
    /** Returns null when current local user policy cannot legitimately connect to this presence. */
    fun create(presence: DiscoveredPresence): SecureSessionConnectRequest?
}

data class SecureSessionApplicationSnapshot(
    val activeRole: SessionRole?,
    val client: SecureSessionCoordinatorSnapshot,
    val host: SecureSessionCoordinatorSnapshot,
    val discoveredHostCount: Int,
    val closed: Boolean,
    val active: SecureSessionCoordinatorSnapshot?,
)
