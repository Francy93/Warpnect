package io.warpnect.platform.session.pairing

import io.warpnect.diagnostics.DiagnosticEventWriter
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.pairing.PairingCompletedListener
import io.warpnect.session.pairing.PairingConfig
import io.warpnect.session.pairing.PairingEventListener
import io.warpnect.session.trust.TrustedPeerStore

/** Explicit cold-path factories; neither creates a Session nor starts pairing automatically. */
object AndroidPairingControllerFactory {
    fun createInitiator(
        localSigner: LocalDeviceIdentitySigner,
        trustedPeerStore: TrustedPeerStore,
        config: PairingConfig = PairingConfig(),
        eventListener: PairingEventListener? = null,
        completedListener: PairingCompletedListener? = null,
        diagnosticEvents: DiagnosticEventWriter? = null,
    ): AndroidPairingController? = AndroidDatagramPairingTransport.createEphemeral()?.let { transport ->
        AndroidPairingController(
            localSigner,
            trustedPeerStore,
            transport,
            config,
            eventListener,
            completedListener,
            diagnosticEvents,
        )
    }

    /** Uses the exact live RFC-005B contact port; discovery retains socket ownership. */
    fun createResponderForAdvertisedDiscovery(
        discovery: LocalDiscoveryController,
        localSigner: LocalDeviceIdentitySigner,
        trustedPeerStore: TrustedPeerStore,
        config: PairingConfig = PairingConfig(),
        eventListener: PairingEventListener? = null,
        completedListener: PairingCompletedListener? = null,
        diagnosticEvents: DiagnosticEventWriter? = null,
    ): AndroidPairingController? = discovery.borrowPairingTransport()?.let { transport ->
        AndroidPairingController(
            localSigner,
            trustedPeerStore,
            transport,
            config,
            eventListener,
            completedListener,
            diagnosticEvents,
        )
    }
}
