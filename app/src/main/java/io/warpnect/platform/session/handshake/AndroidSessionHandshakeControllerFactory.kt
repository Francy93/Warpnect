package io.warpnect.platform.session.handshake

import io.warpnect.session.SessionManager
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.handshake.SessionHandshakeConfig
import io.warpnect.session.handshake.SessionHandshakeEventListener
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.pairing.PairingCryptoProvider
import io.warpnect.session.trust.TrustedPeerStore

/** Explicit cold-path factories; they never initiate pairing or a handshake automatically. */
object AndroidSessionHandshakeControllerFactory {
    fun createInitiator(
        localSigner: LocalDeviceIdentitySigner,
        trustedPeers: TrustedPeerStore,
        sessionManager: SessionManager,
        crypto: PairingCryptoProvider,
        config: SessionHandshakeConfig = SessionHandshakeConfig(),
        eventListener: SessionHandshakeEventListener? = null,
    ): AndroidSessionHandshakeController? = AndroidDatagramSessionHandshakeTransport.createEphemeral()?.let {
        AndroidSessionHandshakeController(
            localSigner,
            trustedPeers,
            sessionManager,
            it,
            crypto,
            config,
            null,
            eventListener,
        )
    }

    fun createResponderForAdvertisedDiscovery(
        discovery: LocalDiscoveryController,
        localSigner: LocalDeviceIdentitySigner,
        trustedPeers: TrustedPeerStore,
        sessionManager: SessionManager,
        crypto: PairingCryptoProvider,
        config: SessionHandshakeConfig = SessionHandshakeConfig(),
        eventListener: SessionHandshakeEventListener? = null,
    ): AndroidSessionHandshakeController? = discovery.borrowSessionHandshakeTransport()?.let {
        AndroidSessionHandshakeController(
            localSigner,
            trustedPeers,
            sessionManager,
            it,
            crypto,
            config,
            discovery,
            eventListener,
        )
    }
}
