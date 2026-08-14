package io.warpnect.session.pairing

import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryRouteDescriptor
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.LocalDiscoveryController

/**
 * Explicit RFC-005B to RFC-005C bridge. Presence is only a current route hint: this class never
 * creates a Session, trusts discovery metadata, connects Wi-Fi Direct, or promotes a route to a
 * session PathId.
 */
class DiscoveryPairingBootstrap(
    private val discovery: LocalDiscoveryController,
    private val pairingController: PairingController,
) {
    fun pair(presenceId: DiscoveryPresenceId, remoteUntrustedAlias: String? = null): PairingControllerResult {
        val lan = discovery.resolveRoute(presenceId, DiscoveryRouteKind.Lan)
        val descriptor = lan.descriptor as? DiscoveryRouteDescriptor.Lan
        if (descriptor != null) {
            val address = descriptor.addressCandidates.firstOrNull()?.hostAddress
                ?: return unavailable(PairingError.DiscoveryRouteUnavailable)
            return pairingController.beginPairing(
                PairingTransportEndpoint(address, descriptor.port),
                remoteUntrustedAlias,
            )
        }
        val direct = discovery.resolveRoute(presenceId, DiscoveryRouteKind.Direct)
        return unavailable(
            if (direct.descriptor != null) {
                PairingError.PairingTransportUnavailable
            } else {
                PairingError.DiscoveryRouteUnavailable
            },
        )
    }

    /**
     * Returns a transport backed by the exact port RFC-005B advertised, if this local host is
     * currently advertising and the Android lease supports a pairing borrow. The caller owns the
     * returned PairingController but discovery retains socket ownership.
     */
    fun borrowAdvertisedResponderTransport(): PairingTransport? = discovery.borrowPairingTransport()

    private fun unavailable(error: PairingError): PairingControllerResult =
        PairingControllerResult(error, pairingController.snapshot())
}
