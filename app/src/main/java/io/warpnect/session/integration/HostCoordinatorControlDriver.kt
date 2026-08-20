package io.warpnect.session.integration

import io.warpnect.session.SessionRole
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.capability.SecureSessionCapabilityBootstrap
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap

/**
 * Lifecycle-facing adapter for a responder controller. Incoming Host work is driven by WNSH/WNCP
 * callbacks, not by Client-only connect calls; this keeps one coordinator API without inventing
 * a parallel host lifecycle implementation.
 */
class HostCoordinatorControlDriver(
    private val host: ControllerBackedHostSessionPhaseDriver,
) : SecureSessionPhaseDriver {
    override fun startDiscovery(role: SessionRole): SecureSessionIntegrationError =
        if (role == SessionRole.Host) host.start() else SecureSessionIntegrationError.InvalidPresence

    override fun stopDiscovery() = Unit

    override fun beginConnection(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    override fun beginExplicitPairing(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    override fun approvePairing(): SecureSessionIntegrationError = SecureSessionIntegrationError.PairingRequired

    override fun createSecureCapabilityBootstrap(bootstrap: AuthenticatedSessionBootstrap): SecurePhaseResult {
        bootstrap.rootSecret.close()
        bootstrap.admissionReservation?.close()
        return SecurePhaseResult(SecureSessionIntegrationError.InvalidPresence)
    }

    override fun beginCapabilities(
        bootstrap: SecureSessionCapabilityBootstrap,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    override fun beginSetup(
        bootstrap: NegotiatedSessionBootstrap,
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    override fun advance() = host.advance()

    override fun cancel() = Unit

    override fun refreshHostAvailability() = host.refreshAvailability()

    override fun close() = host.close()
}
