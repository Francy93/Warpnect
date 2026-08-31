package io.warpnect.session.integration

import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityNegotiationController
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.capability.SecureSessionCapabilityBootstrap
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.discovery.DiscoveryControllerState
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.DiscoveryPresenceStatus
import io.warpnect.session.discovery.DiscoveryRouteDescriptor
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.DiscoveryPresenceBinding
import io.warpnect.session.handshake.ExpectedPeerConstraint
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeController
import io.warpnect.session.handshake.SessionHandshakeError
import io.warpnect.session.handshake.SessionHandshakeEventListener
import io.warpnect.session.handshake.SessionHandshakeIntent
import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.lifecycle.RecoverableSessionRecord
import io.warpnect.session.pairing.PairingCompletedListener
import io.warpnect.session.pairing.PairingController
import io.warpnect.session.pairing.PairingError
import io.warpnect.session.pairing.PairingEventListener
import io.warpnect.session.pairing.PairingTransport
import io.warpnect.session.pairing.PairingTransportEndpoint
import io.warpnect.session.security.SessionProtectionController
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.setup.SessionSetupController
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SessionSetupRuntime
import io.warpnect.session.setup.retainOnlySelectedChannels
import java.net.InetAddress
import java.security.SecureRandom

/** Per-client-attempt random SessionId allocation. It is unrelated to a peer address or trust. */
fun interface SecureSessionIdGenerator {
    fun next(): SessionId
}

object RandomSecureSessionIdGenerator : SecureSessionIdGenerator {
    private val random = SecureRandom()

    override fun next(): SessionId {
        while (true) {
            SessionId.fromParts(random.nextLong().toULong(), random.nextLong().toULong())?.let { return it }
        }
    }
}

fun interface ClientSessionHandshakeControllerFactory {
    fun create(
        transport: SessionHandshakeTransport,
        listener: SessionHandshakeEventListener,
    ): SessionHandshakeController
}

/** Client bootstrap uses a dedicated ephemeral socket; it never borrows the Host discovery port. */
fun interface ClientSessionHandshakeTransportFactory {
    fun create(): SessionHandshakeTransport?
}

fun interface ClientPairingControllerFactory {
    fun create(
        transport: PairingTransport,
        promptListener: PairingEventListener,
        completedListener: PairingCompletedListener,
    ): PairingController
}

/** Pairing uses an explicit client bootstrap transport rather than a Session/media endpoint. */
fun interface ClientPairingTransportFactory {
    fun create(): PairingTransport?
}

fun interface ClientCapabilityNegotiationControllerFactory {
    fun create(onCompleted: (NegotiatedSessionBootstrap) -> Unit): CapabilityNegotiationController
}

fun interface ClientSessionSetupControllerFactory {
    fun create(onCompleted: (io.warpnect.session.setup.PreparedSessionBootstrap) -> Unit): SessionSetupController
}

/** Android owns the WNSD datagram router; this factory never permits an unprotected control path. */
fun interface SecureSessionControlTransportFactory {
    fun create(bootstrap: SecureSessionCapabilityBootstrap): SecureSessionControlTransport?
}

/** Builds path-bound endpoint allocators and native protected transport preparation for RFC-005G. */
fun interface ClientSessionSetupRuntimeFactory {
    fun create(bootstrap: NegotiatedSessionBootstrap, request: SecureSessionConnectRequest): SessionSetupRuntime?
}

/**
 * Production-oriented RFC-005I client adapter. It resolves only an ephemeral RFC-005B LAN route
 * for pairing/WNSH bootstrap, then delegates WNCP and WNSN to their existing bounded controllers.
 * Direct discovery is intentionally not treated as a Direct session path here; RFC-005G owns that
 * authenticated promotion after secure SessionControl exists.
 */
class ControllerBackedClientSessionPhaseDriver(
    private val discovery: LocalDiscoveryController,
    private val handshakeTransportFactory: ClientSessionHandshakeTransportFactory,
    private val handshakeFactory: ClientSessionHandshakeControllerFactory,
    private val pairingTransportFactory: ClientPairingTransportFactory,
    private val pairingFactory: ClientPairingControllerFactory,
    private var protection: SessionProtectionController,
    private val secureControlFactory: SecureSessionControlTransportFactory,
    private val capabilityFactory: ClientCapabilityNegotiationControllerFactory,
    private val setupFactory: ClientSessionSetupControllerFactory,
    private val setupRuntimeFactory: ClientSessionSetupRuntimeFactory,
    private val sessionIdGenerator: SecureSessionIdGenerator = RandomSecureSessionIdGenerator,
    /** Cold-path composition hook retaining only the selected ephemeral Direct route. */
    private val onConnectionRequest: (SecureSessionConnectRequest) -> Unit = {},
    private val onHandshakeStarted: () -> Unit = {},
    private val onHandshakeFailed: (SessionHandshakeError) -> Unit = {},
) : SecureSessionPhaseDriver {
    private val lock = Any()
    private var listener: SecureSessionPhaseListener? = null
    private var handshake: SessionHandshakeController? = null
    private var pairing: PairingController? = null
    private var capability: CapabilityNegotiationController? = null
    private var setup: SessionSetupController? = null
    private var secureControl: SecureSessionControlTransport? = null
    private var pendingPairingPrompt: io.warpnect.session.pairing.PairingVerificationPrompt? = null
    private var closed = false

    override fun startDiscovery(role: SessionRole): SecureSessionIntegrationError {
        if (role != SessionRole.Client) return SecureSessionIntegrationError.InvalidPresence
        return synchronized(lock) {
            if (closed) return@synchronized SecureSessionIntegrationError.Closed
            val preparationError = discovery.prepare().error.toIntegrationError()
            if (preparationError != SecureSessionIntegrationError.None) {
                preparationError
            } else {
                discovery.startBrowsing().error.toIntegrationError()
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(lock) {
            if (!closed) discovery.stop()
        }
    }

    override fun discoveredPresences() = discovery.discoveredPresences()

    override fun discoverySnapshot() = discovery.snapshot()

    override fun discoveryFailure(): SecureSessionIntegrationError = discovery.snapshot()
        .takeIf { it.state == DiscoveryControllerState.Error }
        ?.lastError
        ?.toIntegrationError()
        ?: SecureSessionIntegrationError.None

    override fun setDiscoveryUpdateListener(listener: (() -> Unit)?) {
        discovery.setDiscoveryUpdateListener(listener)
    }

    override fun beginConnection(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError {
        onConnectionRequest(request)
        val endpoint = synchronized(lock) {
            if (closed) return@synchronized null
            if (handshake != null) return@synchronized null
            this.listener = listener
            bootstrapEndpoint(request)
        } ?: return currentErrorOr(SecureSessionIntegrationError.InvalidPresence)

        val transport = handshakeTransportFactory.create()
            ?: return SecureSessionIntegrationError.AuthenticationFailed
        val controller = handshakeFactory.create(transport, handshakeListener())
        synchronized(lock) {
            if (closed || handshake != null) {
                controller.close()
                return if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy
            }
            handshake = controller
        }
        onHandshakeStarted()
        val result = controller.startInitiator(
            endpoint,
            sessionIdGenerator.next(),
            targetPresence = DiscoveryPresenceBinding.fromPresenceId(request.presence.presenceId),
            expectedPeer = request.expectedPeer,
        )
        return result.error.toIntegrationError()
    }

    override fun beginExplicitPairing(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError {
        onConnectionRequest(request)
        val endpoint = synchronized(lock) {
            if (closed || pairing != null) return@synchronized null
            this.listener = listener
            pairingEndpoint(request)
        } ?: return currentErrorOr(SecureSessionIntegrationError.InvalidPresence)
        val transport = pairingTransportFactory.create() ?: return SecureSessionIntegrationError.PairingFailed
        val controller = pairingFactory.create(
            transport,
            PairingEventListener { prompt ->
                synchronized(lock) { pendingPairingPrompt = prompt }
                this.listener?.onPairingVerificationPrompt(prompt)
            },
            PairingCompletedListener {
                releasePairingAttempt()?.close()
                this.listener?.onPairingCompleted()
            },
        )
        synchronized(lock) {
            if (closed || pairing != null) {
                controller.close()
                return if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy
            }
            pairing = controller
        }
        return controller.beginPairing(endpoint, request.presence.displayAlias?.value).error.toIntegrationError()
    }

    override fun approvePairing(): SecureSessionIntegrationError {
        val pair = synchronized(lock) { pairing }
            ?: return currentErrorOr(SecureSessionIntegrationError.PairingRequired)
        val prompt = synchronized(lock) { pendingPairingPrompt }
            ?: return SecureSessionIntegrationError.PairingRequired
        return pair.acceptVerification(prompt.attemptId).error.toIntegrationError()
    }

    override fun rejectPairing(): SecureSessionIntegrationError {
        val pair = synchronized(lock) { pairing }
            ?: return currentErrorOr(SecureSessionIntegrationError.PairingRequired)
        val prompt = synchronized(lock) { pendingPairingPrompt }
            ?: return SecureSessionIntegrationError.PairingRequired
        return pair.rejectVerification(prompt.attemptId).error.toIntegrationError()
    }

    override fun createSecureCapabilityBootstrap(bootstrap: AuthenticatedSessionBootstrap): SecurePhaseResult {
        if (closed) {
            bootstrap.rootSecret.close()
            bootstrap.admissionReservation?.close()
            return SecurePhaseResult(SecureSessionIntegrationError.Closed)
        }
        val runtime = protection.createSessionProtection(bootstrap)
        if (!runtime.isSuccess) return SecurePhaseResult(runtime.error.toIntegrationError())
        val secure = SecureSessionCapabilityBootstrap(
            bootstrap.sessionId,
            bootstrap.generation,
            bootstrap.localDeviceId,
            bootstrap.remoteDeviceId,
            bootstrap.localRole,
            bootstrap.remoteRole,
            bootstrap.endpoint,
            requireNotNull(runtime.runtime),
            bootstrap.admissionReservation,
        )
        val transport = secureControlFactory.create(secure)
        if (transport == null) {
            secure.protection.close()
            secure.admissionReservation?.close()
            return SecurePhaseResult(SecureSessionIntegrationError.SecureSessionFailed)
        }
        synchronized(lock) { secureControl = transport }
        return SecurePhaseResult(SecureSessionIntegrationError.None, secure)
    }

    override fun beginCapabilities(
        bootstrap: SecureSessionCapabilityBootstrap,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError {
        val transport = synchronized(lock) {
            if (closed || capability != null) return@synchronized null
            this.listener = listener
            secureControl
        } ?: return currentErrorOr(SecureSessionIntegrationError.SecureSessionFailed)
        val controller = capabilityFactory.create { completed -> this.listener?.onCapabilitiesNegotiated(completed) }
        synchronized(lock) { capability = controller }
        return controller.beginClient(bootstrap, transport, request.capabilityRequest).toIntegrationError()
    }

    override fun beginSetup(
        bootstrap: NegotiatedSessionBootstrap,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError {
        if (closed) return SecureSessionIntegrationError.Closed
        val runtime = setupRuntimeFactory.create(bootstrap, request)
            ?: return SecureSessionIntegrationError.SessionSetupFailed
        val preferences = request.setupPreferences.retainOnlySelectedChannels(bootstrap.profile)
        val controller = setupFactory.create { prepared -> this.listener?.onPrepared(prepared) }
        synchronized(lock) {
            if (closed || setup != null) {
                controller.close()
                return if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy
            }
            this.listener = listener
            setup = controller
        }
        return controller.beginClient(bootstrap, runtime, preferences).toIntegrationError()
    }

    override fun beginReconnect(
        record: RecoverableSessionRecord,
        nextGeneration: SessionGeneration,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError {
        if (record.localRole != SessionRole.Client || record.remoteRole != SessionRole.Host) {
            return SecureSessionIntegrationError.AuthenticationFailed
        }
        closeGenerationForReconnect()
        onConnectionRequest(request)
        val endpoint = synchronized(lock) {
            if (closed || handshake != null) return@synchronized null
            this.listener = listener
            bootstrapEndpoint(request)
        } ?: return currentErrorOr(SecureSessionIntegrationError.InvalidPresence)
        val transport = handshakeTransportFactory.create()
            ?: return SecureSessionIntegrationError.AuthenticationFailed
        val controller = handshakeFactory.create(transport, handshakeListener())
        synchronized(lock) {
            if (closed || handshake != null) {
                controller.close()
                return if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy
            }
            handshake = controller
        }
        onHandshakeStarted()
        return controller.startInitiator(
            endpoint = endpoint,
            intent = SessionHandshakeIntent.ReconnectSession(
                record.sessionId,
                nextGeneration,
                record.expectedPeerDeviceId,
            ),
            targetPresence = DiscoveryPresenceBinding.fromPresenceId(request.presence.presenceId),
            expectedPeer = ExpectedPeerConstraint.ExactTrustedPeer(record.expectedPeerDeviceId),
        ).error.toIntegrationError()
    }

    /** Called by the existing serialized Phase 5 control scheduler; it creates no worker or queue. */
    override fun advance() {
        val pair = synchronized(lock) { pairing }
        pair?.let { controller ->
            val snapshot = controller.advance().snapshot
            snapshot.lastError.takeUnless { it == PairingError.None }?.let { error ->
                if (releasePairingAttempt() === controller) {
                    controller.close()
                    synchronized(lock) { listener }?.onFailed(
                        SecureSessionIntegrationStage.Pairing,
                        error.toIntegrationError(),
                    )
                }
            }
        }
        synchronized(lock) { handshake }?.advance()
        val negotiated = synchronized(lock) { capability }
        negotiated?.let { controller ->
            controller.advance()
            controller.snapshot().lastError.takeUnless {
                it == io.warpnect.session.capability.CapabilityNegotiationError.None
            }?.let { error ->
                if (releaseCapabilityAttempt() === controller) {
                    controller.close()
                    synchronized(lock) { listener }?.onFailed(
                        SecureSessionIntegrationStage.Capabilities,
                        error.toIntegrationError(),
                    )
                }
            }
        }
        synchronized(lock) { setup }?.advance()
    }

    override fun cancel() = closeAttempt(keepDiscovery = true)

    override fun refreshHostAvailability() = Unit

    override fun close() {
        closeAttempt(keepDiscovery = false)
        synchronized(lock) { closed = true }
    }

    private fun closeAttempt(keepDiscovery: Boolean) {
        val owned = synchronized(lock) {
            val result = listOfNotNull(setup, capability, pairing, handshake, secureControl, protection)
            setup = null
            capability = null
            pairing = null
            handshake = null
            secureControl = null
            pendingPairingPrompt = null
            result
        }
        owned.forEach(AutoCloseable::close)
        if (!keepDiscovery) discovery.close()
    }

    /** Generation N is already cryptographically terminal when RFC-005H requests this path. */
    private fun closeGenerationForReconnect() {
        val owned = synchronized(lock) {
            val previousProtection = protection
            // close() is terminal, so generation N + 1 gets a new controller and fresh root.
            protection = previousProtection.freshGenerationController()
            val result = listOfNotNull(setup, capability, handshake, secureControl, previousProtection)
            setup = null
            capability = null
            handshake = null
            secureControl = null
            result
        }
        owned.forEach(AutoCloseable::close)
    }

    private fun handshakeListener(): SessionHandshakeEventListener = object : SessionHandshakeEventListener {
        override fun onAuthenticated(bootstrap: AuthenticatedSessionBootstrap) {
            listener?.onAuthenticated(bootstrap)
        }

        override fun onFailed(error: SessionHandshakeError) {
            onHandshakeFailed(error)
            releaseHandshakeAttempt()?.close()
            if (error == SessionHandshakeError.PeerNotTrusted) {
                listener?.onPairingRequired()
            } else {
                listener?.onFailed(SecureSessionIntegrationStage.Authentication, error.toIntegrationError())
            }
        }
    }

    private fun bootstrapEndpoint(request: SecureSessionConnectRequest): HandshakeTransportEndpoint? {
        if (request.presence.offeredRole != SessionRole.Host ||
            request.presence.status != DiscoveryPresenceStatus.Usable
        ) {
            return null
        }
        val route = discovery.resolveRoute(request.presence.presenceId, DiscoveryRouteKind.Lan)
        val lan = route.descriptor as? DiscoveryRouteDescriptor.Lan ?: return null
        val address = lan.addressCandidates.firstOrNull()?.hostAddress ?: return null
        return runCatching {
            HandshakeTransportEndpoint.from(
                InetAddress.getByName(address).address,
                lan.port,
            )
        }.getOrNull()
    }

    private fun pairingEndpoint(request: SecureSessionConnectRequest): PairingTransportEndpoint? {
        if (request.presence.offeredRole != SessionRole.Host ||
            request.presence.status != DiscoveryPresenceStatus.Usable
        ) {
            return null
        }
        val route = discovery.resolveRoute(request.presence.presenceId, DiscoveryRouteKind.Lan)
        val lan = route.descriptor as? DiscoveryRouteDescriptor.Lan ?: return null
        return lan.addressCandidates.firstOrNull()?.hostAddress?.let { address ->
            runCatching { PairingTransportEndpoint(address, lan.port) }.getOrNull()
        }
    }

    private fun releaseHandshakeAttempt(): SessionHandshakeController? = synchronized(lock) {
        val result = handshake
        handshake = null
        result
    }

    private fun releasePairingAttempt(): PairingController? = synchronized(lock) {
        val result = pairing
        pairing = null
        pendingPairingPrompt = null
        result
    }

    private fun releaseCapabilityAttempt(): CapabilityNegotiationController? = synchronized(lock) {
        val result = capability
        capability = null
        result
    }

    private fun currentErrorOr(fallback: SecureSessionIntegrationError): SecureSessionIntegrationError =
        synchronized(lock) {
            if (closed) SecureSessionIntegrationError.Closed else fallback
        }
}

private fun DiscoveryError.toIntegrationError(): SecureSessionIntegrationError = when (this) {
    DiscoveryError.None -> SecureSessionIntegrationError.None
    DiscoveryError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.DiscoveryStartFailed
}

private fun PairingError.toIntegrationError(): SecureSessionIntegrationError = when (this) {
    PairingError.None -> SecureSessionIntegrationError.None
    PairingError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.PairingFailed
}

private fun SessionHandshakeError.toIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionHandshakeError.None -> SecureSessionIntegrationError.None
    SessionHandshakeError.PeerNotTrusted -> SecureSessionIntegrationError.PairingRequired
    SessionHandshakeError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.AuthenticationFailed
}

private fun SessionProtectionError.toIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionProtectionError.None -> SecureSessionIntegrationError.None
    SessionProtectionError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.SecureSessionFailed
}

private fun io.warpnect.session.capability.CapabilityNegotiationError.toIntegrationError():
    SecureSessionIntegrationError =
    when (this) {
        io.warpnect.session.capability.CapabilityNegotiationError.None -> SecureSessionIntegrationError.None
        io.warpnect.session.capability.CapabilityNegotiationError.Closed -> SecureSessionIntegrationError.Closed
        else -> SecureSessionIntegrationError.CapabilityNegotiationFailed
    }

private fun SessionSetupError.toIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionSetupError.None -> SecureSessionIntegrationError.None
    SessionSetupError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.SessionSetupFailed
}
