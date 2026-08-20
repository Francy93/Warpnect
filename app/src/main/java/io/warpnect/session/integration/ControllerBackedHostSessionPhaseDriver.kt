package io.warpnect.session.integration

import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityNegotiationController
import io.warpnect.session.capability.CapabilityNegotiationError
import io.warpnect.session.capability.HostCapabilityPolicy
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.capability.SecureSessionCapabilityBootstrap
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.SessionHandshakeController
import io.warpnect.session.handshake.SessionHandshakeError
import io.warpnect.session.handshake.SessionHandshakeEventListener
import io.warpnect.session.pairing.PairingVerificationPrompt
import io.warpnect.session.security.SessionProtectionController
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.setup.HostSessionSetupPolicy
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionSetupController
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SessionSetupRuntime

interface HostPairingResponder : AutoCloseable {
    fun start(): SecureSessionIntegrationError
    fun approvePairing(): SecureSessionIntegrationError
    fun pendingPrompt(): PairingVerificationPrompt?
}

fun interface HostPairingResponderFactory {
    /** Called only after RFC-005B has advertised its shared bootstrap endpoint. */
    fun create(): HostPairingResponder?
}

fun interface HostSessionHandshakeControllerFactory {
    /** The responder is already attached to the single RFC-005B bootstrap router on return. */
    fun create(listener: SessionHandshakeEventListener): SessionHandshakeController?
}

fun interface HostSecureSessionControlTransportFactory {
    fun create(bootstrap: SecureSessionCapabilityBootstrap): SecureSessionControlTransport?
}

fun interface HostCapabilityNegotiationControllerFactory {
    fun create(onCompleted: (NegotiatedSessionBootstrap) -> Unit): CapabilityNegotiationController
}

fun interface HostSessionSetupControllerFactory {
    fun create(onCompleted: (PreparedSessionBootstrap) -> Unit): SessionSetupController
}

fun interface HostSessionSetupRuntimeFactory {
    fun create(bootstrap: NegotiatedSessionBootstrap): SessionSetupRuntime?
}

/**
 * Responder-side counterpart to [ControllerBackedClientSessionPhaseDriver]. It contains no
 * protocol parser: WNSH, WNCP and WNSN remain the existing controllers. A completed bootstrap is
 * handed to the RFC-005I coordinator, which owns the shared lifecycle/pipeline Running path.
 */
class ControllerBackedHostSessionPhaseDriver(
    private val discovery: LocalDiscoveryController,
    private val handshakeFactory: HostSessionHandshakeControllerFactory,
    private val protection: SessionProtectionController,
    private val secureControlFactory: HostSecureSessionControlTransportFactory,
    private val capabilityFactory: HostCapabilityNegotiationControllerFactory,
    private val setupFactory: HostSessionSetupControllerFactory,
    private val setupRuntimeFactory: HostSessionSetupRuntimeFactory,
    private val capabilityPolicy: HostCapabilityPolicy,
    private val setupPolicy: HostSessionSetupPolicy,
    private val onPrepared: (PreparedSessionBootstrap) -> Unit,
    private val pairingResponderFactory: HostPairingResponderFactory? = null,
    private val onFailure: (SecureSessionIntegrationStage, SecureSessionIntegrationError) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val sessions = LinkedHashMap<SessionId, HostSessionFlight>()
    private var handshake: SessionHandshakeController? = null
    private var pairingResponder: HostPairingResponder? = null
    private var closed = false

    /** Starts Host control prerequisites only: discovery/advertising and the existing WNSH responder. */
    fun start(): SecureSessionIntegrationError {
        if (synchronized(lock) { closed }) return SecureSessionIntegrationError.Closed
        if (synchronized(lock) { handshake != null }) return SecureSessionIntegrationError.None

        // RFC-005B owns the advertised bootstrap socket. Prepare/advertise it before borrowing
        // its one WNSH reader; otherwise a real Android responder can never attach.
        val discoveryError = discovery.prepare().error.toHostIntegrationError()
            .takeUnless { it != SecureSessionIntegrationError.None }
            ?: discovery.startAdvertising().error.toHostIntegrationError()
        if (discoveryError != SecureSessionIntegrationError.None) {
            close()
            return discoveryError
        }
        val responder = pairingResponderFactory?.create()
        if (pairingResponderFactory != null && responder == null) {
            close()
            return SecureSessionIntegrationError.PairingFailed
        }
        if (responder != null && responder.start() != SecureSessionIntegrationError.None) {
            responder.close()
            close()
            return SecureSessionIntegrationError.PairingFailed
        }
        val controller = synchronized(lock) {
            if (closed || handshake != null) {
                null
            } else {
                handshakeFactory.create(handshakeListener())
            }
        } ?: run {
            responder?.close()
            close()
            return SecureSessionIntegrationError.AuthenticationFailed
        }
        synchronized(lock) {
            if (closed || handshake != null) {
                controller.close()
                responder?.close()
                return if (closed) SecureSessionIntegrationError.Closed else SecureSessionIntegrationError.Busy
            }
            handshake = controller
            pairingResponder = responder
        }
        refreshAvailability()
        return SecureSessionIntegrationError.None
    }

    /** Existing controller timers only; no per-session polling thread or setup queue is created. */
    fun advance() {
        val current = synchronized(lock) { sessions.values.toList() }
        synchronized(lock) { handshake }?.advance()
        current.forEach { flight ->
            flight.capability.advance()
            flight.setup?.advance()
        }
    }

    fun refreshAvailability() {
        if (!closed) discovery.refreshAvailability()
    }

    fun approvePairing(): SecureSessionIntegrationError =
        synchronized(lock) { pairingResponder }?.approvePairing() ?: SecureSessionIntegrationError.PairingRequired

    fun pendingPairingPrompt(): PairingVerificationPrompt? = synchronized(lock) { pairingResponder }?.pendingPrompt()

    fun closeSession(sessionId: SessionId) {
        val flight = synchronized(lock) { sessions.remove(sessionId) } ?: return
        flight.close()
        refreshAvailability()
    }

    /** Stops Host readiness without terminally closing the application-scoped responder. */
    fun stopReadiness() {
        val owned = synchronized(lock) {
            if (closed) return
            val result = sessions.values.toList() + listOfNotNull(handshake, pairingResponder)
            sessions.clear()
            handshake = null
            pairingResponder = null
            result
        }
        owned.asReversed().forEach(AutoCloseable::close)
        discovery.stopAdvertising()
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            val result = sessions.values.toList() + listOfNotNull(handshake, pairingResponder)
            sessions.clear()
            handshake = null
            pairingResponder = null
            result
        }
        owned.asReversed().forEach(AutoCloseable::close)
        protection.close()
        discovery.close()
    }

    private fun handshakeListener(): SessionHandshakeEventListener = object : SessionHandshakeEventListener {
        override fun onAuthenticated(bootstrap: AuthenticatedSessionBootstrap) {
            acceptAuthenticated(bootstrap)
        }

        override fun onFailed(error: SessionHandshakeError) {
            onFailure(SecureSessionIntegrationStage.Authentication, error.toHostIntegrationError())
        }
    }

    private fun acceptAuthenticated(bootstrap: AuthenticatedSessionBootstrap) {
        val secure = createSecureBootstrap(bootstrap) ?: return
        val transport = secureControlFactory.create(secure)
        if (transport == null) {
            secure.protection.close()
            secure.admissionReservation?.close()
            onFailure(SecureSessionIntegrationStage.Security, SecureSessionIntegrationError.SecureSessionFailed)
            return
        }
        val capability = capabilityFactory.create { negotiated -> onCapabilitiesNegotiated(negotiated) }
        val registration = capability.registerHost(secure, transport, capabilityPolicy)
        if (registration != CapabilityNegotiationError.None) {
            capability.close()
            transport.close()
            secure.protection.close()
            secure.admissionReservation?.close()
            onFailure(SecureSessionIntegrationStage.Capabilities, registration.toHostIntegrationError())
            return
        }
        synchronized(lock) {
            if (closed || sessions.containsKey(secure.sessionId)) {
                capability.close()
                transport.close()
                secure.protection.close()
                secure.admissionReservation?.close()
                return
            }
            sessions[secure.sessionId] = HostSessionFlight(secure, transport, capability)
        }
        refreshAvailability()
    }

    private fun onCapabilitiesNegotiated(bootstrap: NegotiatedSessionBootstrap) {
        val runtime = setupRuntimeFactory.create(bootstrap)
        if (runtime == null) {
            failFlight(
                bootstrap.sessionId,
                SecureSessionIntegrationStage.Setup,
                SecureSessionIntegrationError.SessionSetupFailed,
            )
            return
        }
        val setup = setupFactory.create { prepared ->
            synchronized(lock) { sessions.remove(prepared.sessionId) }?.detachForLifecycle()
            onPrepared(prepared)
            refreshAvailability()
        }
        val registration = setup.registerHost(bootstrap, runtime, setupPolicy)
        if (registration != SessionSetupError.None) {
            setup.close()
            failFlight(bootstrap.sessionId, SecureSessionIntegrationStage.Setup, registration.toHostIntegrationError())
            return
        }
        val replaced = synchronized(lock) {
            val flight = sessions[bootstrap.sessionId] ?: return@synchronized false
            flight.setup = setup
            true
        }
        if (!replaced) setup.close()
    }

    private fun createSecureBootstrap(bootstrap: AuthenticatedSessionBootstrap): SecureSessionCapabilityBootstrap? {
        if (bootstrap.localRole != SessionRole.Host || bootstrap.remoteRole != SessionRole.Client) {
            bootstrap.rootSecret.close()
            bootstrap.admissionReservation?.close()
            onFailure(SecureSessionIntegrationStage.Authentication, SecureSessionIntegrationError.AuthenticationFailed)
            return null
        }
        val created = protection.createSessionProtection(bootstrap)
        if (!created.isSuccess) {
            onFailure(SecureSessionIntegrationStage.Security, created.error.toHostIntegrationError())
            return null
        }
        return SecureSessionCapabilityBootstrap(
            bootstrap.sessionId,
            bootstrap.generation,
            bootstrap.localDeviceId,
            bootstrap.remoteDeviceId,
            bootstrap.localRole,
            bootstrap.remoteRole,
            bootstrap.endpoint,
            requireNotNull(created.runtime),
            bootstrap.admissionReservation,
        )
    }

    private fun failFlight(
        sessionId: SessionId,
        stage: SecureSessionIntegrationStage,
        error: SecureSessionIntegrationError,
    ) {
        synchronized(lock) { sessions.remove(sessionId) }?.close()
        onFailure(stage, error)
        refreshAvailability()
    }

    private class HostSessionFlight(
        val secure: SecureSessionCapabilityBootstrap,
        val transport: SecureSessionControlTransport,
        val capability: CapabilityNegotiationController,
        var setup: SessionSetupController? = null,
    ) : AutoCloseable {
        fun detachForLifecycle() {
            // The prepared bootstrap owns the secure control/protection resources. The completed
            // WNSN controller is no longer needed once it has transferred that bootstrap.
            setup?.close()
            setup = null
            capability.close()
        }

        override fun close() {
            setup?.close()
            capability.close()
            transport.close()
            secure.protection.close()
            secure.admissionReservation?.close()
        }
    }
}

private fun io.warpnect.session.discovery.DiscoveryError.toHostIntegrationError(): SecureSessionIntegrationError =
    when (this) {
        io.warpnect.session.discovery.DiscoveryError.None -> SecureSessionIntegrationError.None
        io.warpnect.session.discovery.DiscoveryError.Closed -> SecureSessionIntegrationError.Closed
        else -> SecureSessionIntegrationError.InvalidPresence
    }

private fun SessionHandshakeError.toHostIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionHandshakeError.None -> SecureSessionIntegrationError.None
    SessionHandshakeError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.AuthenticationFailed
}

private fun SessionProtectionError.toHostIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionProtectionError.None -> SecureSessionIntegrationError.None
    SessionProtectionError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.SecureSessionFailed
}

private fun CapabilityNegotiationError.toHostIntegrationError(): SecureSessionIntegrationError = when (this) {
    CapabilityNegotiationError.None -> SecureSessionIntegrationError.None
    CapabilityNegotiationError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.CapabilityNegotiationFailed
}

private fun SessionSetupError.toHostIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionSetupError.None -> SecureSessionIntegrationError.None
    SessionSetupError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.SessionSetupFailed
}
