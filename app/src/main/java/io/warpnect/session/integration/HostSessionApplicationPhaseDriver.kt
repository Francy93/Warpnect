package io.warpnect.session.integration

import io.warpnect.session.SessionRole
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.capability.SecureSessionCapabilityBootstrap
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.pairing.PairingVerificationPrompt

/**
 * Adapts the existing responder controller chain to the normal Host application action. The Host
 * never initiates WNSH/WNCP/WNSN from this interface: it prepares advertising and receives them
 * through RFC-005B's single bootstrap router.
 */
class HostSessionApplicationPhaseDriver(
    private val host: ControllerBackedHostSessionPhaseDriver,
) : SecureSessionPhaseDriver {
    override fun startDiscovery(role: SessionRole): SecureSessionIntegrationError =
        if (role == SessionRole.Host) host.start() else SecureSessionIntegrationError.InvalidPresence

    override fun stopDiscovery() = host.stopReadiness()

    override fun beginConnection(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    override fun beginExplicitPairing(
        request: SecureSessionConnectRequest,
        listener: SecureSessionPhaseListener,
    ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

    override fun approvePairing(): SecureSessionIntegrationError = host.approvePairing()

    override fun pendingPairingVerificationPrompt(): PairingVerificationPrompt? = host.pendingPairingPrompt()

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

    /**
     * A coordinator cancellation concerns one incoming Session flight. The long-lived Host
     * responder and its RFC-005B advertisement remain available for the next bounded attempt;
     * application shutdown still calls [close].
     */
    override fun cancel() = Unit

    override fun refreshHostAvailability() = host.refreshAvailability()

    override fun close() = host.close()
}
