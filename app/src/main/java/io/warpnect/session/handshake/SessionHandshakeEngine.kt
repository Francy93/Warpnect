@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.handshake

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.pairing.PairingCryptoProvider
import io.warpnect.session.pairing.PairingCryptoResult
import io.warpnect.session.pairing.PairingEphemeralKeyPair
import io.warpnect.session.pairing.PairingSignature
import io.warpnect.session.trust.TrustedPeerStore

sealed interface SessionHandshakeEngineAction {
    data class Send(val datagram: ByteArray) : SessionHandshakeEngineAction
    data class Completed(val bootstrap: AuthenticatedSessionBootstrap) : SessionHandshakeEngineAction
}

data class SessionHandshakeEngineResult(
    val error: SessionHandshakeError = SessionHandshakeError.None,
    val actions: List<SessionHandshakeEngineAction> = emptyList(),
)

/** Pure, transport-free authenticated-session handshake state machine. */
class SessionHandshakeEngine private constructor(
    val side: HandshakeSide,
    val attemptId: SessionHandshakeAttemptId,
    private val endpoint: HandshakeTransportEndpoint,
    private val localSigner: LocalDeviceIdentitySigner,
    private val trustedPeers: TrustedPeerStore,
    private val crypto: PairingCryptoProvider,
    private val expectedPeer: ExpectedPeerConstraint,
    private val admission: SessionHandshakeAdmission?,
    private var localEphemeral: PairingEphemeralKeyPair,
    private val clientHello: SessionHandshakeMessage.ClientHello,
    private val logicalTranscript: MutableList<ByteArray>,
) : AutoCloseable {
    var state: SessionHandshakeState = if (side == HandshakeSide.Initiator) {
        SessionHandshakeState.WaitingForRetryOrServerHello
    } else {
        SessionHandshakeState.WaitingForServerAuth
    }
        private set

    private var serverHello: SessionHandshakeMessage.ServerHello? = null
    private var keys: SessionHandshakeDerivedKeys? = null
    private var remoteDeviceId: DeviceId? = null
    private var cachedClientHello: ByteArray? = null
    private var cachedServerHello: ByteArray? = null
    private var cachedServerAuth: ByteArray? = null
    private var cachedClientAuth: ByteArray? = null
    private var cachedServerComplete: ByteArray? = null

    fun initialOutbound(): ByteArray? = cachedClientHello?.copyOf() ?: cachedServerAuth?.copyOf()

    fun receive(source: HandshakeTransportEndpoint, datagram: ByteArray): SessionHandshakeEngineResult {
        if (state == SessionHandshakeState.Closed) return failed(SessionHandshakeError.Closed)
        if (source != endpoint) return fail(SessionHandshakeError.EndpointMismatch)
        val (packet, parseError) = SessionHandshakeCodec.decode(datagram)
        if (parseError != SessionHandshakeError.None || packet == null) return fail(parseError)
        if (packet.header.attemptId != attemptId) return failed(SessionHandshakeError.UnexpectedMessage)
        return when (side) {
            HandshakeSide.Initiator -> receiveInitiator(packet)
            HandshakeSide.Responder -> receiveResponder(packet)
        }
    }

    private fun receiveInitiator(packet: SessionHandshakePacket): SessionHandshakeEngineResult = when (packet.message) {
        is SessionHandshakeMessage.HelloRetry -> onHelloRetry(packet)
        is SessionHandshakeMessage.ServerHello -> onServerHello(packet)
        is SessionHandshakeMessage.ServerAuth -> onServerAuth(packet)
        is SessionHandshakeMessage.ServerComplete -> onServerComplete(packet)
        is SessionHandshakeMessage.Reject -> fail(SessionHandshakeError.TransportFailure, rejected = true)
        else -> failed(SessionHandshakeError.UnexpectedMessage)
    }

    private fun onHelloRetry(packet: SessionHandshakePacket): SessionHandshakeEngineResult {
        if (state == SessionHandshakeState.CookieClientHelloSent && packet.header.messageSequence == 1) {
            // UDP may deliver a second stateless retry after the cookie-bearing ClientHello. The
            // existing controller keeps that ClientHello as its bounded retransmission payload.
            return SessionHandshakeEngineResult()
        }
        if (state != SessionHandshakeState.WaitingForRetryOrServerHello || packet.header.messageSequence != 1) {
            return failed(
                SessionHandshakeError.UnexpectedSequence,
            )
        }
        val retry = packet.message as SessionHandshakeMessage.HelloRetry
        val retried = clientHello.copy(retryCookie = retry.cookie)
        val datagram = SessionHandshakeCodec.encode(attemptId, 2, retried) ?: return fail(SessionHandshakeError.MalformedDatagram)
        logicalTranscript += packet.datagram.copyOf()
        logicalTranscript += datagram.copyOf()
        cachedClientHello = datagram
        state = SessionHandshakeState.CookieClientHelloSent
        return SessionHandshakeEngineResult(actions = listOf(SessionHandshakeEngineAction.Send(datagram.copyOf())))
    }

    private fun onServerHello(packet: SessionHandshakePacket): SessionHandshakeEngineResult {
        if (
            state in setOf(SessionHandshakeState.WaitingForServerAuth, SessionHandshakeState.WaitingForServerComplete) &&
            packet.header.messageSequence == 3 &&
            cachedServerHello?.contentEquals(packet.datagram) == true
        ) {
            // UDP may redeliver ServerHello after the initiator has advanced. It is only safe to
            // ignore the byte-identical cached message; any different packet remains unexpected.
            return SessionHandshakeEngineResult()
        }
        if (state !in setOf(SessionHandshakeState.WaitingForRetryOrServerHello, SessionHandshakeState.CookieClientHelloSent) || packet.header.messageSequence != 3) {
            return failed(SessionHandshakeError.UnexpectedSequence)
        }
        val hello = packet.message as SessionHandshakeMessage.ServerHello
        if (!matchesClientHello(hello)) return fail(SessionHandshakeError.InvalidRole)
        val shared = localEphemeral.deriveSharedSecret(hello.ephemeralPublicKey)
        val secret = (shared as? PairingCryptoResult.Value)?.value ?: return fail(SessionHandshakeError.KeyAgreementFailure)
        val earlyHash = SessionHandshakeCanonical.transcriptHash(crypto, logicalTranscript + packet.datagram)
        keys = SessionHandshakeCanonical.deriveKeys(crypto, earlyHash, secret.toByteArray())
        secret.wipe()
        serverHello = hello
        logicalTranscript += packet.datagram.copyOf()
        cachedServerHello = packet.datagram.copyOf()
        state = SessionHandshakeState.WaitingForServerAuth
        return SessionHandshakeEngineResult()
    }

    private fun onServerAuth(packet: SessionHandshakePacket): SessionHandshakeEngineResult {
        if (
            state == SessionHandshakeState.WaitingForServerComplete &&
            packet.header.messageSequence == 4 &&
            cachedServerAuth?.contentEquals(packet.datagram) == true
        ) {
            // A retransmitted ServerAuth can cross the ClientAuth send on UDP. It must be the
            // exact authenticated record already in the transcript; do not emit ClientAuth again.
            return SessionHandshakeEngineResult()
        }
        if (state != SessionHandshakeState.WaitingForServerAuth || packet.header.messageSequence != 4) {
            return failed(
                SessionHandshakeError.UnexpectedSequence,
            )
        }
        val plaintext = decrypt(packet, serverToClient = true) ?: return fail(SessionHandshakeError.DecryptFailure)
        val record = SessionHandshakeCanonical.parseAuthRecord(plaintext) ?: return fail(SessionHandshakeError.MalformedDatagram)
        val tbs = SessionHandshakeCanonical.parseAuthTbs(record.tbs, SessionRole.Client) ?: return fail(SessionHandshakeError.InvalidRole)
        if (!matchesSession(tbs.sessionId, tbs.generation) || tbs.senderRole != SessionRole.Host) {
            return fail(
                SessionHandshakeError.InvalidRole,
            )
        }
        if (expectedPeer is ExpectedPeerConstraint.ExactTrustedPeer && expectedPeer.deviceId != tbs.deviceId) {
            return fail(
                SessionHandshakeError.UnexpectedTrustedPeer,
            )
        }
        val trusted = trustedPeers.findTrustedPeer(tbs.deviceId) ?: return fail(SessionHandshakeError.PeerNotTrusted)
        if (trusted.identityFingerprint != tbs.fingerprint) return fail(SessionHandshakeError.TrustedIdentityMismatch)
        val previous = SessionHandshakeCanonical.transcriptHash(crypto, logicalTranscript)
        if (!crypto.verifyIdentitySignature(
                trusted.identityPublicKey,
                SessionHandshakeCanonical.serverSignatureInput(previous, record.tbs),
                record.signature,
            )
        ) {
            return fail(SessionHandshakeError.SignatureFailure)
        }
        val finishedHash = SessionHandshakeCanonical.authFinishedHash(crypto, previous, record.tbs, record.signature)
        val expected = crypto.hmacSha256(requireKeys().serverFinishedKey.toByteArray(), finishedHash)
        if (!crypto.constantTimeEquals(expected, record.finished)) return fail(SessionHandshakeError.FinishedFailure)
        remoteDeviceId = tbs.deviceId
        logicalTranscript += packet.datagram.copyOf()
        cachedServerAuth = packet.datagram.copyOf()
        state = SessionHandshakeState.ServerAuthenticated
        return sendClientAuth()
    }

    private fun sendClientAuth(): SessionHandshakeEngineResult {
        val previous = SessionHandshakeCanonical.transcriptHash(crypto, logicalTranscript)
        val identity = localSigner.identity
        val tbs = SessionHandshakeCanonical.clientAuthTbs(
            identity.deviceId,
            identity.fingerprint,
            clientHello.sessionId,
            clientHello.generation,
        )
        val signature = localSigner.sign(SessionHandshakeCanonical.clientSignatureInput(previous, tbs)).toSignature()
            ?: return fail(SessionHandshakeError.SignatureFailure)
        val finished = crypto.hmacSha256(
            requireKeys().clientFinishedKey.toByteArray(),
            SessionHandshakeCanonical.authFinishedHash(crypto, previous, tbs, signature),
        )
        val datagram = encrypt(SessionHandshakeMessageType.ClientAuth, 5, SessionHandshakeCanonical.authRecord(tbs, signature, finished), serverToClient = false)
            ?: return fail(SessionHandshakeError.TransportFailure)
        logicalTranscript += datagram.copyOf()
        cachedClientAuth = datagram
        state = SessionHandshakeState.WaitingForServerComplete
        return SessionHandshakeEngineResult(actions = listOf(SessionHandshakeEngineAction.Send(datagram.copyOf())))
    }

    private fun onServerComplete(packet: SessionHandshakePacket): SessionHandshakeEngineResult {
        if (state != SessionHandshakeState.WaitingForServerComplete || packet.header.messageSequence != 6) {
            return failed(
                SessionHandshakeError.UnexpectedSequence,
            )
        }
        val plaintext = decrypt(packet, serverToClient = true) ?: return fail(SessionHandshakeError.DecryptFailure)
        val record = SessionHandshakeCanonical.parseCompleteRecord(plaintext) ?: return fail(SessionHandshakeError.CompletionFailure)
        val authenticatedHash = SessionHandshakeCanonical.transcriptHash(crypto, logicalTranscript)
        if (!crypto.constantTimeEquals(
                record.first,
                authenticatedHash,
            )
        ) {
            return fail(SessionHandshakeError.CompletionFailure)
        }
        val expected = crypto.hmacSha256(
            requireKeys().serverCompleteKey.toByteArray(),
            SessionHandshakeCanonical.completeMacInput(
                attemptId,
                clientHello.sessionId,
                clientHello.generation,
                authenticatedHash,
            ),
        )
        if (!crypto.constantTimeEquals(expected, record.second)) return fail(SessionHandshakeError.CompletionFailure)
        val bootstrap = bootstrap(authenticatedHash, null)
        state = SessionHandshakeState.Authenticated
        destroyEphemeralState()
        return SessionHandshakeEngineResult(actions = listOf(SessionHandshakeEngineAction.Completed(bootstrap)))
    }

    private fun receiveResponder(packet: SessionHandshakePacket): SessionHandshakeEngineResult = when (packet.message) {
        is SessionHandshakeMessage.ClientAuth -> onClientAuth(packet)
        is SessionHandshakeMessage.ClientHello -> if (packet.header.messageSequence == 2) {
            SessionHandshakeEngineResult(
                actions = listOfNotNull(
                    cachedServerHello,
                    cachedServerAuth,
                ).map { SessionHandshakeEngineAction.Send(it.copyOf()) },
            )
        } else {
            failed(SessionHandshakeError.UnexpectedSequence)
        }
        else -> failed(SessionHandshakeError.UnexpectedMessage)
    }

    private fun onClientAuth(packet: SessionHandshakePacket): SessionHandshakeEngineResult {
        if (state != SessionHandshakeState.WaitingForServerAuth || packet.header.messageSequence != 5) {
            return if (state == SessionHandshakeState.Authenticated && cachedServerComplete != null) {
                SessionHandshakeEngineResult(
                    actions = listOf(SessionHandshakeEngineAction.Send(cachedServerComplete!!.copyOf())),
                )
            } else {
                failed(SessionHandshakeError.UnexpectedSequence)
            }
        }
        val plaintext = decrypt(packet, serverToClient = false) ?: return fail(SessionHandshakeError.DecryptFailure)
        val record = SessionHandshakeCanonical.parseAuthRecord(plaintext) ?: return fail(SessionHandshakeError.MalformedDatagram)
        val tbs = SessionHandshakeCanonical.parseAuthTbs(record.tbs, SessionRole.Host) ?: return fail(SessionHandshakeError.InvalidRole)
        if (!matchesSession(tbs.sessionId, tbs.generation) || tbs.senderRole != SessionRole.Client) {
            return fail(
                SessionHandshakeError.InvalidRole,
            )
        }
        val trusted = trustedPeers.findTrustedPeer(tbs.deviceId) ?: return fail(SessionHandshakeError.PeerNotTrusted)
        if (trusted.identityFingerprint != tbs.fingerprint) return fail(SessionHandshakeError.TrustedIdentityMismatch)
        val previous = SessionHandshakeCanonical.transcriptHash(crypto, logicalTranscript)
        if (!crypto.verifyIdentitySignature(
                trusted.identityPublicKey,
                SessionHandshakeCanonical.clientSignatureInput(previous, record.tbs),
                record.signature,
            )
        ) {
            return fail(SessionHandshakeError.SignatureFailure)
        }
        val expected = crypto.hmacSha256(
            requireKeys().clientFinishedKey.toByteArray(),
            SessionHandshakeCanonical.authFinishedHash(crypto, previous, record.tbs, record.signature),
        )
        if (!crypto.constantTimeEquals(expected, record.finished)) return fail(SessionHandshakeError.FinishedFailure)
        val admissionResult = admission?.reserve(clientHello.sessionId, tbs.deviceId, clientHello.generation)
            ?: SessionHandshakeAdmissionResult(SessionHandshakeError.None)
        if (admissionResult.error != SessionHandshakeError.None) return fail(admissionResult.error)
        remoteDeviceId = tbs.deviceId
        logicalTranscript += packet.datagram.copyOf()
        cachedClientAuth = packet.datagram.copyOf()
        val authenticatedHash = SessionHandshakeCanonical.transcriptHash(crypto, logicalTranscript)
        val mac = crypto.hmacSha256(
            requireKeys().serverCompleteKey.toByteArray(),
            SessionHandshakeCanonical.completeMacInput(
                attemptId,
                clientHello.sessionId,
                clientHello.generation,
                authenticatedHash,
            ),
        )
        val datagram = encrypt(SessionHandshakeMessageType.ServerComplete, 6, SessionHandshakeCanonical.completeRecord(authenticatedHash, mac), serverToClient = true)
            ?: return fail(SessionHandshakeError.TransportFailure)
        cachedServerComplete = datagram
        val bootstrap = bootstrap(authenticatedHash, admissionResult.reservation)
        state = SessionHandshakeState.Authenticated
        destroyEphemeralState()
        return SessionHandshakeEngineResult(
            actions = listOf(
                SessionHandshakeEngineAction.Send(datagram.copyOf()),
                SessionHandshakeEngineAction.Completed(bootstrap),
            ),
        )
    }

    private fun encrypt(
        type: SessionHandshakeMessageType,
        sequence: Int,
        plaintext: ByteArray,
        serverToClient: Boolean,
    ): ByteArray? {
        val key = if (serverToClient) requireKeys().serverKey else requireKeys().clientKey
        val iv = if (serverToClient) requireKeys().serverIv else requireKeys().clientIv
        val header =
            SessionHandshakeHeader(
                type,
                0x0002,
                attemptId,
                sequence,
                plaintext.size + SessionHandshakeProtocol.GCM_TAG_BYTES,
            )
        val aad = SessionHandshakeCodec.encodeHeader(header)
        val encrypted = crypto.aes128GcmEncrypt(
            key.toByteArray(),
            SessionHandshakeCanonical.aeadNonce(iv.toByteArray(), sequence),
            aad,
            plaintext,
        )
        val bytes = (encrypted as? PairingCryptoResult.Value)?.value ?: return null
        val message = when (type) {
            SessionHandshakeMessageType.ServerAuth -> SessionHandshakeMessage.ServerAuth(ImmutableBytes.copyOf(bytes))
            SessionHandshakeMessageType.ClientAuth -> SessionHandshakeMessage.ClientAuth(ImmutableBytes.copyOf(bytes))
            SessionHandshakeMessageType.ServerComplete -> SessionHandshakeMessage.ServerComplete(
                ImmutableBytes.copyOf(bytes),
            )
            else -> return null
        }
        return SessionHandshakeCodec.encode(attemptId, sequence, message)
    }

    private fun decrypt(packet: SessionHandshakePacket, serverToClient: Boolean): ByteArray? {
        val cipher = when (val message = packet.message) {
            is SessionHandshakeMessage.ServerAuth -> message.encryptedRecord.toByteArray()
            is SessionHandshakeMessage.ClientAuth -> message.encryptedRecord.toByteArray()
            is SessionHandshakeMessage.ServerComplete -> message.encryptedRecord.toByteArray()
            else -> return null
        }
        val key = if (serverToClient) requireKeys().serverKey else requireKeys().clientKey
        val iv = if (serverToClient) requireKeys().serverIv else requireKeys().clientIv
        val result = crypto.aes128GcmDecrypt(
            key.toByteArray(),
            SessionHandshakeCanonical.aeadNonce(iv.toByteArray(), packet.header.messageSequence),
            SessionHandshakeCodec.encodeHeader(packet.header),
            cipher,
        )
        return (result as? PairingCryptoResult.Value)?.value
    }

    private fun matchesClientHello(hello: SessionHandshakeMessage.ServerHello): Boolean =
        hello.suite == 1 && hello.sessionId == clientHello.sessionId && hello.generation == clientHello.generation &&
            hello.initiatorRole == SessionRole.Client && hello.responderRole == SessionRole.Host

    private fun matchesSession(sessionId: SessionId, generation: SessionGeneration): Boolean =
        sessionId == clientHello.sessionId && generation == clientHello.generation
    private fun requireKeys(): SessionHandshakeDerivedKeys = checkNotNull(keys) { "Handshake keys unavailable" }

    private fun bootstrap(
        hash: ByteArray,
        reservation: AuthenticatedSessionAdmissionReservation?,
    ): AuthenticatedSessionBootstrap = AuthenticatedSessionBootstrap(
        sessionId = clientHello.sessionId,
        generation = clientHello.generation,
        localDeviceId = localSigner.identity.deviceId,
        remoteDeviceId = checkNotNull(remoteDeviceId),
        localRole = if (side == HandshakeSide.Initiator) SessionRole.Client else SessionRole.Host,
        remoteRole = if (side == HandshakeSide.Initiator) SessionRole.Host else SessionRole.Client,
        attemptId = attemptId,
        authenticatedTranscriptHash = ImmutableBytes.copyOf(hash),
        endpoint = endpoint,
        rootSecret = SessionHandshakeCanonical.deriveRoot(
            crypto,
            requireKeys().handshakeSecret.toByteArray(),
            hash,
        ),
        admissionReservation = reservation,
    )

    private fun destroyEphemeralState() {
        localEphemeral.close()
        keys?.destroy()
        keys = null
    }
    private fun fail(error: SessionHandshakeError, rejected: Boolean = false): SessionHandshakeEngineResult {
        destroyEphemeralState()
        state = if (rejected) SessionHandshakeState.Rejected else SessionHandshakeState.Failed
        return SessionHandshakeEngineResult(error)
    }
    private fun failed(error: SessionHandshakeError): SessionHandshakeEngineResult = SessionHandshakeEngineResult(error)
    override fun close() {
        if (state != SessionHandshakeState.Closed) {
            destroyEphemeralState()
            state = SessionHandshakeState.Closed
        }
    }

    companion object {
        data class Started(val engine: SessionHandshakeEngine?, val result: SessionHandshakeEngineResult)

        fun initiate(
            attemptId: SessionHandshakeAttemptId,
            endpoint: HandshakeTransportEndpoint,
            sessionId: SessionId,
            generation: SessionGeneration = SessionGeneration.Initial,
            targetPresence: DiscoveryPresenceBinding = DiscoveryPresenceBinding.None,
            localSigner: LocalDeviceIdentitySigner,
            trustedPeers: TrustedPeerStore,
            crypto: PairingCryptoProvider,
            expectedPeer: ExpectedPeerConstraint = ExpectedPeerConstraint.AnyTrustedPeer,
        ): Started {
            val ephemeral = (crypto.generateEphemeralKeyPair() as? PairingCryptoResult.Value)?.value
                ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.KeyAgreementFailure))
            val nonce = HandshakeNonce.from(crypto.randomBytes(SessionHandshakeProtocol.NONCE_BYTES)) ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.InvalidConfig))
            val hello = SessionHandshakeMessage.ClientHello(
                1,
                sessionId,
                generation,
                SessionRole.Client,
                SessionRole.Host,
                targetPresence,
                nonce,
                ephemeral.publicKey,
            )
            val datagram = SessionHandshakeCodec.encode(attemptId, 0, hello) ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.MalformedDatagram))
            val engine =
                SessionHandshakeEngine(
                    HandshakeSide.Initiator, attemptId, endpoint, localSigner, trustedPeers, crypto, expectedPeer, null, ephemeral, hello,
                    mutableListOf(
                        datagram.copyOf(),
                    ),
                )
            engine.cachedClientHello = datagram
            return Started(
                engine,
                SessionHandshakeEngineResult(actions = listOf(SessionHandshakeEngineAction.Send(datagram.copyOf()))),
            )
        }

        fun respond(
            endpoint: HandshakeTransportEndpoint,
            initialClientHello: SessionHandshakePacket,
            helloRetry: SessionHandshakePacket,
            cookieClientHello: SessionHandshakePacket,
            localSigner: LocalDeviceIdentitySigner,
            trustedPeers: TrustedPeerStore,
            crypto: PairingCryptoProvider,
            admission: SessionHandshakeAdmission,
        ): Started {
            val hello = cookieClientHello.message as? SessionHandshakeMessage.ClientHello
                ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.MalformedDatagram))
            if (hello.initiatorRole != SessionRole.Client || hello.responderRole != SessionRole.Host || hello.suite != 1) {
                return Started(
                    null,
                    SessionHandshakeEngineResult(SessionHandshakeError.InvalidRole),
                )
            }
            val ephemeral = (crypto.generateEphemeralKeyPair() as? PairingCryptoResult.Value)?.value
                ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.KeyAgreementFailure))
            val nonce = HandshakeNonce.from(crypto.randomBytes(SessionHandshakeProtocol.NONCE_BYTES)) ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.InvalidConfig))
            val serverHello = SessionHandshakeMessage.ServerHello(
                1,
                hello.sessionId,
                hello.generation,
                SessionRole.Client,
                SessionRole.Host,
                nonce,
                ephemeral.publicKey,
            )
            val serverHelloDatagram = SessionHandshakeCodec.encode(cookieClientHello.header.attemptId, 3, serverHello)
                ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.MalformedDatagram))
            val shared = ephemeral.deriveSharedSecret(hello.ephemeralPublicKey)
            val secret = (shared as? PairingCryptoResult.Value)?.value ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.KeyAgreementFailure))
            val transcript =
                mutableListOf(
                    initialClientHello.datagram.copyOf(),
                    helloRetry.datagram.copyOf(),
                    cookieClientHello.datagram.copyOf(),
                    serverHelloDatagram.copyOf(),
                )
            val keys = SessionHandshakeCanonical.deriveKeys(
                crypto,
                SessionHandshakeCanonical.transcriptHash(crypto, transcript),
                secret.toByteArray(),
            )
            secret.wipe()
            val engine =
                SessionHandshakeEngine(HandshakeSide.Responder, cookieClientHello.header.attemptId, endpoint, localSigner, trustedPeers, crypto, ExpectedPeerConstraint.AnyTrustedPeer, admission, ephemeral, hello, transcript)
            engine.serverHello = serverHello
            engine.cachedServerHello = serverHelloDatagram
            engine.keys = keys
            val previous = SessionHandshakeCanonical.transcriptHash(crypto, transcript)
            val identity = localSigner.identity
            val tbs = SessionHandshakeCanonical.serverAuthTbs(
                identity.deviceId,
                identity.fingerprint,
                hello.sessionId,
                hello.generation,
            )
            val signature = localSigner.sign(SessionHandshakeCanonical.serverSignatureInput(previous, tbs)).toSignature()
                ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.SignatureFailure))
            val finished = crypto.hmacSha256(
                keys.serverFinishedKey.toByteArray(),
                SessionHandshakeCanonical.authFinishedHash(crypto, previous, tbs, signature),
            )
            val authDatagram = engine.encrypt(SessionHandshakeMessageType.ServerAuth, 4, SessionHandshakeCanonical.authRecord(tbs, signature, finished), serverToClient = true)
                ?: return Started(null, SessionHandshakeEngineResult(SessionHandshakeError.TransportFailure))
            engine.logicalTranscript += authDatagram.copyOf()
            engine.cachedServerAuth = authDatagram
            engine.state = SessionHandshakeState.WaitingForServerAuth
            return Started(
                engine,
                SessionHandshakeEngineResult(
                    actions = listOf(
                        SessionHandshakeEngineAction.Send(serverHelloDatagram.copyOf()),
                        SessionHandshakeEngineAction.Send(authDatagram.copyOf()),
                    ),
                ),
            )
        }
    }
}

private fun io.warpnect.session.identity.IdentityKeyResult<ImmutableBytes>.toSignature(): PairingSignature? =
    (this as? io.warpnect.session.identity.IdentityKeyResult.Value)?.value?.toByteArray()?.let(
        PairingSignature::fromDer,
    )
