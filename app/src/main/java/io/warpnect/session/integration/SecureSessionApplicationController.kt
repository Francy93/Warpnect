package io.warpnect.session.integration

import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.lifecycle.DisconnectReason
import io.warpnect.session.pairing.PairingAttemptId
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
    private val onClientDiscoverySnapshotPublished: (Int) -> Unit = {},
    private val controlDispatcher: SessionControlDispatcher = SessionControlDispatcher { action ->
        action()
        true
    },
) : AutoCloseable {
    private val lock = Any()
    private var activeRole: SessionRole? = null
    private var closed = false
    private var connectQueued = false
    private var pairingStartQueued = false
    private var pairingDecisionAttemptId: PairingAttemptId? = null
    private var disconnectQueued = false
    private var hostStopQueued = false
    private val _snapshot = MutableStateFlow(snapshotLocked())
    val snapshot: StateFlow<SecureSessionApplicationSnapshot> = _snapshot.asStateFlow()

    init {
        client.setDiscoverySnapshotPublishedListener(::publishClientDiscoverySnapshot)
    }

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

    /**
     * Queues the full Client handshake start on the serialized Session control context. The
     * Android production graph supplies its existing non-Main control scheduler; the inline
     * default preserves deterministic portable/unit-test composition only.
     */
    fun connect(presence: DiscoveredPresence): SessionIntegrationResult {
        val submission = synchronized(lock) {
            if (closed) return@synchronized ConnectSubmission.Closed
            if (activeRole != SessionRole.Client || connectQueued ||
                client.snapshot.value.state !in setOf(
                    SecureSessionCoordinatorState.Idle,
                    SecureSessionCoordinatorState.Discovering,
                )
            ) {
                return@synchronized ConnectSubmission.Busy
            }
            requestFactory.create(presence)?.let { request ->
                connectQueued = true
                ConnectSubmission.Ready(request)
            } ?: ConnectSubmission.InvalidPresence
        }
        val connectionRequest = when (submission) {
            ConnectSubmission.Closed -> return closedResult(client)
            ConnectSubmission.Busy -> return busyResult(client)
            ConnectSubmission.InvalidPresence -> return invalidPresenceResult(client)
            is ConnectSubmission.Ready -> submission.request
        }
        if (!controlDispatcher.dispatch { connectOnControl(connectionRequest) }) {
            return synchronized(lock) {
                connectQueued = false
                if (closed) closedResult(client) else busyResult(client)
            }
        }
        return synchronized(lock) {
            SessionIntegrationResult(SecureSessionIntegrationError.None, client.snapshot.value)
        }
    }

    /**
     * Queues RFC-005C start after an explicit selected-peer connection. Android production always
     * runs it on WarpnectSessionControl because the first pairing action can synchronously send.
     */
    fun beginExplicitPairing(): SessionIntegrationResult {
        val submission = synchronized(lock) {
            when {
                closed -> ControlSubmission.Closed
                activeRole != SessionRole.Client || pairingStartQueued ||
                    client.snapshot.value.state != SecureSessionCoordinatorState.PairingRequired -> {
                    ControlSubmission.Busy
                }
                else -> {
                    pairingStartQueued = true
                    ControlSubmission.Ready
                }
            }
        }
        return submitControl(submission, ::beginPairingOnControl) { pairingStartQueued = false }
    }

    /** Sends the user-approved RFC-005C SAS result on the serialized Session control owner. */
    fun approvePairing(): SessionIntegrationResult = submitPairingDecision(approved = true)

    /** Sends the user-rejected RFC-005C SAS result on the serialized Session control owner. */
    fun rejectPairing(): SessionIntegrationResult = submitPairingDecision(approved = false)

    fun stopHost(): SessionIntegrationResult {
        val submission = synchronized(lock) {
            when {
                closed -> ControlSubmission.Closed
                activeRole != SessionRole.Host || hostStopQueued -> ControlSubmission.Busy
                else -> {
                    hostStopQueued = true
                    ControlSubmission.Ready
                }
            }
        }
        return submitControl(submission, ::stopHostOnControl) { hostStopQueued = false }
    }

    /** User-visible browsing cancellation owns the real RFC-005B stop, then returns to Ready. */
    fun cancelClientDiscovery() = synchronized(lock) {
        if (closed) return@synchronized closedResult(client)
        if (activeRole != SessionRole.Client) return@synchronized busyResult(client)
        client.cancelDiscovery().also { result ->
            if (result.error == SecureSessionIntegrationError.None) activeRole = null
            publishLocked()
        }
    }

    /** A runtime close can emit final Session control, so never perform it directly from Compose. */
    fun disconnect(): SessionIntegrationResult {
        val submission = synchronized(lock) {
            when {
                closed -> ControlSubmission.Closed
                activeRole == null || disconnectQueued -> ControlSubmission.Busy
                else -> {
                    disconnectQueued = true
                    ControlSubmission.Ready
                }
            }
        }
        return submitControl(submission, ::disconnectOnControl) { disconnectQueued = false }
    }

    /** Called from the existing control owner, never from an audio, codec, or input callback. */
    fun advance() = synchronized(lock) {
        if (!closed && activeRole != null) {
            when (activeRole) {
                SessionRole.Client -> client.advance()
                SessionRole.Host -> host.advance()
                null -> Unit
            }
            if (activeRole == SessionRole.Client &&
                client.snapshot.value.state == SecureSessionCoordinatorState.PairingRequired &&
                !pairingStartQueued
            ) {
                pairingStartQueued = true
                beginPairingOnControlLocked()
            } else {
                publishLocked()
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        client.setDiscoverySnapshotPublishedListener(null)
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
        val snapshot = snapshotLocked()
        if (pairingDecisionAttemptId != snapshot.active?.pairingVerificationPrompt?.attemptId) {
            pairingDecisionAttemptId = null
        }
        _snapshot.value = snapshot
    }

    /** Runs only on the serialized Session control owner in the Android production composition. */
    private fun connectOnControl(request: SecureSessionConnectRequest) {
        synchronized(lock) {
            try {
                if (!closed && activeRole == SessionRole.Client) {
                    client.connect(request).also { publishLocked() }
                }
            } finally {
                connectQueued = false
            }
        }
    }

    private fun beginPairingOnControl() = synchronized(lock) {
        beginPairingOnControlLocked()
    }

    private fun beginPairingOnControlLocked() {
        try {
            if (!closed && activeRole == SessionRole.Client &&
                client.snapshot.value.state == SecureSessionCoordinatorState.PairingRequired
            ) {
                client.beginExplicitPairing().also { publishLocked() }
            }
        } finally {
            pairingStartQueued = false
        }
    }

    private fun submitPairingDecision(approved: Boolean): SessionIntegrationResult {
        val submission = synchronized(lock) {
            val prompt = activePairingPromptLocked()
            when {
                closed -> PairingDecisionSubmission.Closed
                prompt == null || pairingDecisionAttemptId != null -> PairingDecisionSubmission.Busy
                else -> {
                    pairingDecisionAttemptId = prompt.attemptId
                    PairingDecisionSubmission.Ready(prompt.attemptId)
                }
            }
        }
        return when (submission) {
            PairingDecisionSubmission.Closed -> closedResult(client)
            PairingDecisionSubmission.Busy -> busyResult(client)
            is PairingDecisionSubmission.Ready -> {
                if (!controlDispatcher.dispatch {
                        completePairingDecisionOnControl(submission.attemptId, approved)
                    }
                ) {
                    synchronized(lock) {
                        if (pairingDecisionAttemptId == submission.attemptId) pairingDecisionAttemptId = null
                        if (closed) closedResult(client) else busyResult(client)
                    }
                } else {
                    synchronized(lock) {
                        SessionIntegrationResult(SecureSessionIntegrationError.None, client.snapshot.value)
                    }
                }
            }
        }
    }

    private fun completePairingDecisionOnControl(attemptId: PairingAttemptId, approved: Boolean) = synchronized(lock) {
        val prompt = activePairingPromptLocked()
        if (!closed && prompt?.attemptId == attemptId) {
            val coordinator = when (activeRole) {
                SessionRole.Client -> client
                SessionRole.Host -> host
                null -> null
            }
            if (coordinator != null) {
                if (approved) coordinator.approvePairing() else coordinator.rejectPairing()
                publishLocked()
            }
        }
    }

    private fun stopHostOnControl() = synchronized(lock) {
        try {
            if (!closed && activeRole == SessionRole.Host) {
                host.stopHostReadiness()
                activeRole = null
                publishLocked()
            }
        } finally {
            hostStopQueued = false
        }
    }

    private fun disconnectOnControl() = synchronized(lock) {
        try {
            val coordinator = when (activeRole) {
                SessionRole.Client -> client
                SessionRole.Host -> host
                null -> null
            }
            if (!closed && coordinator != null) {
                coordinator.disconnect(DisconnectReason.UserRequested)
                activeRole = null
                publishLocked()
            }
        } finally {
            disconnectQueued = false
        }
    }

    private fun submitControl(
        submission: ControlSubmission,
        action: () -> Unit,
        clearRejectedSubmission: () -> Unit,
    ): SessionIntegrationResult = when (submission) {
        ControlSubmission.Closed -> closedResult(client)
        ControlSubmission.Busy -> busyResult(client)
        ControlSubmission.Ready -> {
            if (!controlDispatcher.dispatch(action)) {
                synchronized(lock) {
                    clearRejectedSubmission()
                    if (closed) closedResult(client) else busyResult(client)
                }
            } else {
                synchronized(lock) {
                    SessionIntegrationResult(SecureSessionIntegrationError.None, client.snapshot.value)
                }
            }
        }
    }

    private fun activePairingPromptLocked() = when (activeRole) {
        SessionRole.Client -> client.snapshot.value.pairingVerificationPrompt
        SessionRole.Host -> host.snapshot.value.pairingVerificationPrompt
        null -> null
    }

    private fun publishClientDiscoverySnapshot(hostCount: Int) {
        val publishedCount = synchronized(lock) {
            if (closed || activeRole != SessionRole.Client) {
                null
            } else {
                publishLocked()
                _snapshot.value.active?.discovery?.candidateCount ?: hostCount
            }
        }
        publishedCount?.let(onClientDiscoverySnapshotPublished)
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

    private sealed interface ConnectSubmission {
        data class Ready(val request: SecureSessionConnectRequest) : ConnectSubmission
        data object Closed : ConnectSubmission
        data object Busy : ConnectSubmission
        data object InvalidPresence : ConnectSubmission
    }

    private enum class ControlSubmission {
        Ready,
        Closed,
        Busy,
    }

    private sealed interface PairingDecisionSubmission {
        data class Ready(val attemptId: PairingAttemptId) : PairingDecisionSubmission
        data object Closed : PairingDecisionSubmission
        data object Busy : PairingDecisionSubmission
    }
}

fun interface SecureSessionConnectRequestFactory {
    /** Returns null when current local user policy cannot legitimately connect to this presence. */
    fun create(presence: DiscoveredPresence): SecureSessionConnectRequest?
}

/** Serializes cold Session control work without introducing a per-intent worker. */
fun interface SessionControlDispatcher {
    /** Returns false only when the application-scoped Session control owner has stopped. */
    fun dispatch(action: () -> Unit): Boolean
}

data class SecureSessionApplicationSnapshot(
    val activeRole: SessionRole?,
    val client: SecureSessionCoordinatorSnapshot,
    val host: SecureSessionCoordinatorSnapshot,
    val discoveredHostCount: Int,
    val closed: Boolean,
    val active: SecureSessionCoordinatorSnapshot?,
)
