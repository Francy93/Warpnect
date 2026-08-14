package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityFingerprint
import io.warpnect.session.identity.IdentityKeyResult
import io.warpnect.session.identity.IdentityPublicKey
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.trust.TrustStoreError

fun interface PairingPeerTrustInspector {
    fun inspect(deviceId: DeviceId, publicKey: IdentityPublicKey): PairingError
}

object AcceptAnyUntrustedPeer : PairingPeerTrustInspector {
    override fun inspect(deviceId: DeviceId, publicKey: IdentityPublicKey): PairingError = PairingError.None
}

data class PairingPeerIdentity(
    val deviceId: DeviceId,
    val publicKey: IdentityPublicKey,
    val fingerprint: IdentityFingerprint,
)

sealed interface PairingEngineAction {
    data class Send(
        val packet: PairingBootstrapPacket,
    ) : PairingEngineAction

    data class Prompt(
        val remotePeer: PairingPeerIdentity,
        val shortAuthenticationString: String,
    ) : PairingEngineAction

    data class Completed(
        val remotePeer: PairingPeerIdentity,
        val transcriptHash: PairingHash,
    ) : PairingEngineAction
}

data class PairingEngineResult(
    val state: PairingEngineState,
    val error: PairingError = PairingError.None,
    val actions: List<PairingEngineAction> = emptyList(),
)

data class PairingEngineStartResult(
    val engine: PairingEngine? = null,
    val result: PairingEngineResult,
)

/**
 * One pure, bounded Pairing Bootstrap V1 attempt. It does not own a socket, worker, timer, UI, or
 * trust database. A controller supplies those cold-path concerns and persists trust only after the
 * final Completed action.
 */
class PairingEngine private constructor(
    val role: PairingRole,
    val attemptId: PairingAttemptId,
    private val localSigner: LocalDeviceIdentitySigner,
    private val crypto: PairingCryptoProvider,
    private val trustInspector: PairingPeerTrustInspector,
    private var ephemeralKeyPair: PairingEphemeralKeyPair,
    private val localNonce: PairingNonce,
    private val initiatorMaterial: InitiatorRevealMaterial?,
    private val remoteUntrustedAlias: String?,
) : AutoCloseable {
    var state: PairingEngineState = PairingEngineState.Idle
        private set
    var lastError: PairingError = PairingError.None
        private set

    private var commit: PairingBootstrapMessage.Commit? = null
    private var response: PairingBootstrapMessage.Response? = null
    private var reveal: PairingBootstrapMessage.Reveal? = null
    private var transcriptHash: PairingHash? = null
    private var derivedKeys: PairingDerivedKeys? = null
    private var remotePeer: PairingPeerIdentity? = null
    private var localAccepted = false
    private var peerConfirmed = false
    private var completionEmitted = false

    fun start(): PairingEngineResult {
        if (role != PairingRole.Initiator || state != PairingEngineState.Idle) return unexpected()
        val material = requireNotNull(initiatorMaterial)
        val newCommit = PairingBootstrapMessage.Commit(
            PairingCryptoSuite.P256Sha256,
            PairingCanonical.commitment(crypto, material),
        )
        commit = newCommit
        state = PairingEngineState.CommitSent
        return succeeded(PairingEngineAction.Send(PairingBootstrapPacket(attemptId, newCommit)))
    }

    fun receive(packet: PairingBootstrapPacket): PairingEngineResult {
        if (packet.attemptId != attemptId) return failed(PairingError.InvalidPacket)
        return when (val message = packet.message) {
            is PairingBootstrapMessage.Commit -> receiveCommit(message)
            is PairingBootstrapMessage.Response -> receiveResponse(message)
            is PairingBootstrapMessage.Reveal -> receiveReveal(message)
            is PairingBootstrapMessage.Confirm -> receiveConfirm(message)
            is PairingBootstrapMessage.Reject -> terminal(PairingEngineState.Rejected, PairingError.RejectedByPeer)
            is PairingBootstrapMessage.Abort -> terminal(PairingEngineState.Failed, PairingError.AbortedByPeer)
        }
    }

    fun acceptVerification(): PairingEngineResult {
        if (state !in setOf(PairingEngineState.AwaitingUserConfirmation, PairingEngineState.PeerConfirmed)) {
            return unexpected()
        }
        val remote = remotePeer ?: return failWithAbort(PairingError.InvalidPeerIdentity)
        val hash = transcriptHash ?: return failWithAbort(PairingError.InvalidPacket)
        val keys = derivedKeys ?: return failWithAbort(PairingError.InvalidPacket)
        localAccepted = true
        val context = PairingCanonical.confirmationContext(
            attemptId = attemptId,
            transcriptHash = hash,
            initiatorDeviceId = initiatorDeviceId(),
            responderDeviceId = responderDeviceId(),
            senderRole = role,
        )
        val key = if (role == PairingRole.Initiator) keys.initiatorConfirmKey else keys.responderConfirmKey
        val confirm = PairingBootstrapMessage.Confirm(
            transcriptHash = hash,
            confirmationMac = PairingConfirmationMac.requireSha256(crypto.hmacSha256(key.toByteArray(), context)),
        )
        state = if (peerConfirmed) PairingEngineState.PeerConfirmed else PairingEngineState.LocalConfirmed
        val actions =
            mutableListOf<PairingEngineAction>(PairingEngineAction.Send(PairingBootstrapPacket(attemptId, confirm)))
        completeIfMutuallyConfirmed(actions)
        return succeeded(*actions.toTypedArray())
    }

    fun rejectVerification(mismatch: Boolean = false): PairingEngineResult {
        if (state !in setOf(PairingEngineState.AwaitingUserConfirmation, PairingEngineState.PeerConfirmed)) {
            return unexpected()
        }
        val reason = if (mismatch) PairingRejectReason.VerificationMismatch else PairingRejectReason.UserRejected
        return terminal(
            PairingEngineState.Rejected,
            if (mismatch) PairingError.VerificationMismatch else PairingError.UserRejected,
            PairingEngineAction.Send(PairingBootstrapPacket(attemptId, PairingBootstrapMessage.Reject(reason))),
        )
    }

    fun timeout(userConfirmation: Boolean): PairingEngineResult = terminal(
        PairingEngineState.TimedOut,
        if (userConfirmation) PairingError.UserConfirmationTimeout else PairingError.PairingTransportTimeout,
        PairingEngineAction.Send(
            PairingBootstrapPacket(attemptId, PairingBootstrapMessage.Abort(PairingAbortReason.Timeout)),
        ),
    )

    override fun close() {
        if (!state.isTerminal()) {
            terminal(PairingEngineState.Closed, PairingError.Closed)
        }
    }

    private fun receiveCommit(message: PairingBootstrapMessage.Commit): PairingEngineResult {
        if (role != PairingRole.Responder) return unexpected()
        if (state == PairingEngineState.ResponseSent && message == commit) {
            return requireNotNull(
                response,
            ).let { succeeded(PairingEngineAction.Send(PairingBootstrapPacket(attemptId, it))) }
        }
        if (state != PairingEngineState.Idle && state != PairingEngineState.CommitReceived) return unexpected()
        if (message.suite != PairingCryptoSuite.P256Sha256) return failWithAbort(PairingError.InvalidPacket)
        commit = message
        state = PairingEngineState.CommitReceived
        val unsignedResponse = PairingBootstrapMessage.Response(
            suite = PairingCryptoSuite.P256Sha256,
            responderDeviceId = localSigner.identity.deviceId,
            responderIdentityPublicKey = localSigner.identity.publicKey,
            responderEphemeralPublicKey = ephemeralKeyPair.publicKey,
            responderNonce = localNonce,
            commitHash = message.commitHash,
            signature = PairingSignature.requireDer(byteArrayOf(0)),
        )
        val signature = localSigner.sign(PairingCanonical.responseSignatureInput(attemptId, unsignedResponse))
            .asSignatureOrNull() ?: return failWithAbort(PairingError.InvalidPacket)
        val signedResponse = unsignedResponse.copy(signature = signature)
        response = signedResponse
        state = PairingEngineState.ResponseSent
        return succeeded(PairingEngineAction.Send(PairingBootstrapPacket(attemptId, signedResponse)))
    }

    private fun receiveResponse(message: PairingBootstrapMessage.Response): PairingEngineResult {
        if (role != PairingRole.Initiator) return unexpected()
        if (state in setOf(
                PairingEngineState.RevealSent,
                PairingEngineState.AwaitingUserConfirmation,
                PairingEngineState.LocalConfirmed,
                PairingEngineState.PeerConfirmed,
            ) && message == response
        ) {
            return reveal?.let {
                succeeded(PairingEngineAction.Send(PairingBootstrapPacket(attemptId, it)))
            } ?: succeeded()
        }
        if (state != PairingEngineState.CommitSent) return unexpected()
        val localCommit = commit ?: return failWithAbort(PairingError.InvalidPacket)
        if (message.suite != PairingCryptoSuite.P256Sha256 ||
            !crypto.constantTimeEquals(message.commitHash.bytes(), localCommit.commitHash.bytes())
        ) {
            return failWithAbort(PairingError.CommitmentMismatch)
        }
        val peerError = validatePeerIdentity(
            message.responderDeviceId,
            message.responderIdentityPublicKey,
            message.responderEphemeralPublicKey,
        )
        if (peerError != PairingError.None) return failWithAbort(peerError)
        if (!crypto.verifyIdentitySignature(
                message.responderIdentityPublicKey,
                PairingCanonical.responseSignatureInput(attemptId, message),
                message.signature,
            )
        ) {
            return failWithAbort(PairingError.SignatureInvalid)
        }
        val trustError = trustInspector.inspect(message.responderDeviceId, message.responderIdentityPublicKey)
        if (trustError != PairingError.None) return failForTrustPolicy(trustError)
        response = message
        val material = requireNotNull(initiatorMaterial)
        val signature = localSigner.sign(
            PairingCanonical.revealSignatureInput(attemptId, localCommit.commitHash, message, material, crypto),
        ).asSignatureOrNull() ?: return failWithAbort(PairingError.InvalidPacket)
        val signedReveal = PairingBootstrapMessage.Reveal(material, signature)
        reveal = signedReveal
        val derive = derivePeerState(
            remoteDeviceId = message.responderDeviceId,
            remoteIdentityKey = message.responderIdentityPublicKey,
            remoteEphemeralKey = message.responderEphemeralPublicKey,
            transcriptCommit = localCommit,
            transcriptResponse = message,
            transcriptReveal = signedReveal,
        )
        if (derive.error != PairingError.None) return derive
        state = PairingEngineState.RevealSent
        val prompt = promptAction() ?: return failWithAbort(PairingError.InvalidPeerIdentity)
        state = PairingEngineState.AwaitingUserConfirmation
        return succeeded(PairingEngineAction.Send(PairingBootstrapPacket(attemptId, signedReveal)), prompt)
    }

    private fun receiveReveal(message: PairingBootstrapMessage.Reveal): PairingEngineResult {
        if (role != PairingRole.Responder) return unexpected()
        if (state in setOf(
                PairingEngineState.AwaitingUserConfirmation,
                PairingEngineState.LocalConfirmed,
                PairingEngineState.PeerConfirmed,
            ) && message == reveal
        ) {
            return succeeded()
        }
        if (state != PairingEngineState.ResponseSent) return unexpected()
        val localCommit = commit ?: return failWithAbort(PairingError.InvalidPacket)
        val localResponse = response ?: return failWithAbort(PairingError.InvalidPacket)
        val material = message.material
        if (material.pairingAttemptId != attemptId || material.suite != PairingCryptoSuite.P256Sha256) {
            return failWithAbort(PairingError.InvalidPacket)
        }
        val expectedCommit = PairingCanonical.commitment(crypto, material)
        if (!crypto.constantTimeEquals(expectedCommit.bytes(), localCommit.commitHash.bytes())) {
            return failWithAbort(PairingError.CommitmentMismatch)
        }
        val peerError = validatePeerIdentity(
            material.initiatorDeviceId,
            material.initiatorIdentityPublicKey,
            material.initiatorEphemeralPublicKey,
        )
        if (peerError != PairingError.None) return failWithAbort(peerError)
        if (!crypto.verifyIdentitySignature(
                material.initiatorIdentityPublicKey,
                PairingCanonical.revealSignatureInput(
                    attemptId,
                    localCommit.commitHash,
                    localResponse,
                    material,
                    crypto,
                ),
                message.signature,
            )
        ) {
            return failWithAbort(PairingError.SignatureInvalid)
        }
        val trustError = trustInspector.inspect(material.initiatorDeviceId, material.initiatorIdentityPublicKey)
        if (trustError != PairingError.None) return failForTrustPolicy(trustError)
        reveal = message
        val derive = derivePeerState(
            remoteDeviceId = material.initiatorDeviceId,
            remoteIdentityKey = material.initiatorIdentityPublicKey,
            remoteEphemeralKey = material.initiatorEphemeralPublicKey,
            transcriptCommit = localCommit,
            transcriptResponse = localResponse,
            transcriptReveal = message,
        )
        if (derive.error != PairingError.None) return derive
        state = PairingEngineState.AwaitingUserConfirmation
        val prompt = promptAction() ?: return failWithAbort(PairingError.InvalidPeerIdentity)
        return succeeded(prompt)
    }

    private fun receiveConfirm(message: PairingBootstrapMessage.Confirm): PairingEngineResult {
        if (state == PairingEngineState.Paired) return succeeded()
        if (state !in setOf(
                PairingEngineState.AwaitingUserConfirmation,
                PairingEngineState.LocalConfirmed,
                PairingEngineState.PeerConfirmed,
            )
        ) {
            return unexpected()
        }
        val hash = transcriptHash ?: return failWithAbort(PairingError.InvalidPacket)
        if (!crypto.constantTimeEquals(message.transcriptHash.bytes(), hash.bytes())) {
            return failWithAbort(PairingError.ConfirmationMacInvalid)
        }
        val keys = derivedKeys ?: return failWithAbort(PairingError.InvalidPacket)
        val senderRole = if (role == PairingRole.Initiator) PairingRole.Responder else PairingRole.Initiator
        val key = if (senderRole == PairingRole.Initiator) keys.initiatorConfirmKey else keys.responderConfirmKey
        val expectedMac = crypto.hmacSha256(
            key.toByteArray(),
            PairingCanonical.confirmationContext(
                attemptId,
                hash,
                initiatorDeviceId(),
                responderDeviceId(),
                senderRole,
            ),
        )
        if (!crypto.constantTimeEquals(message.confirmationMac.bytes(), expectedMac)) {
            expectedMac.fill(0)
            return failWithAbort(PairingError.ConfirmationMacInvalid)
        }
        expectedMac.fill(0)
        peerConfirmed = true
        if (!localAccepted) {
            state = PairingEngineState.PeerConfirmed
            return succeeded()
        }
        val actions = mutableListOf<PairingEngineAction>()
        completeIfMutuallyConfirmed(actions)
        return succeeded(*actions.toTypedArray())
    }

    private fun derivePeerState(
        remoteDeviceId: DeviceId,
        remoteIdentityKey: IdentityPublicKey,
        remoteEphemeralKey: EphemeralPublicKey,
        transcriptCommit: PairingBootstrapMessage.Commit,
        transcriptResponse: PairingBootstrapMessage.Response,
        transcriptReveal: PairingBootstrapMessage.Reveal,
    ): PairingEngineResult {
        val sharedSecret = when (val result = ephemeralKeyPair.deriveSharedSecret(remoteEphemeralKey)) {
            is PairingCryptoResult.Value -> result.value
            is PairingCryptoResult.Failed -> return failWithAbort(PairingError.InvalidPeerIdentity)
        }
        val newTranscriptHash = PairingCanonical.transcriptHash(
            crypto,
            attemptId,
            transcriptCommit,
            transcriptResponse,
            transcriptReveal,
        )
        val keys = try {
            PairingCanonical.deriveKeys(crypto, newTranscriptHash, sharedSecret)
        } finally {
            sharedSecret.wipe()
            ephemeralKeyPair.close()
        }
        remotePeer = PairingPeerIdentity(
            remoteDeviceId,
            remoteIdentityKey,
            IdentityFingerprint.requireSha256(crypto.sha256(remoteIdentityKey.encodedSpki())),
        )
        transcriptHash = newTranscriptHash
        derivedKeys = keys
        return succeeded()
    }

    private fun validatePeerIdentity(
        deviceId: DeviceId,
        identityKey: IdentityPublicKey,
        ephemeralKey: EphemeralPublicKey,
    ): PairingError {
        if (deviceId == localSigner.identity.deviceId) return PairingError.SelfPairing
        val fingerprint = IdentityFingerprint.fromSha256(crypto.sha256(identityKey.encodedSpki()))
            ?: return PairingError.InvalidPeerIdentity
        if (fingerprint == localSigner.identity.fingerprint) return PairingError.SelfPairing
        if (!crypto.isValidP256PublicKey(identityKey.encodedSpki()) ||
            !crypto.isValidP256PublicKey(ephemeralKey.encodedSpki())
        ) {
            return PairingError.InvalidPeerIdentity
        }
        return PairingError.None
    }

    private fun promptAction(): PairingEngineAction.Prompt? {
        val peer = remotePeer ?: return null
        val keys = derivedKeys ?: return null
        val sas = PairingCanonical.sas(crypto, keys.sasMaterial) ?: return null
        return PairingEngineAction.Prompt(peer, sas)
    }

    private fun completeIfMutuallyConfirmed(actions: MutableList<PairingEngineAction>) {
        if (!localAccepted || !peerConfirmed || completionEmitted) return
        val peer = remotePeer ?: return
        val hash = transcriptHash ?: return
        state = PairingEngineState.Paired
        completionEmitted = true
        actions += PairingEngineAction.Completed(peer, hash)
        destroySecrets()
    }

    private fun initiatorDeviceId(): DeviceId = when (role) {
        PairingRole.Initiator -> localSigner.identity.deviceId
        PairingRole.Responder -> requireNotNull(remotePeer).deviceId
    }

    private fun responderDeviceId(): DeviceId = when (role) {
        PairingRole.Initiator -> requireNotNull(remotePeer).deviceId
        PairingRole.Responder -> localSigner.identity.deviceId
    }

    private fun failWithAbort(error: PairingError): PairingEngineResult = terminal(
        PairingEngineState.Failed,
        error,
        PairingEngineAction.Send(
            PairingBootstrapPacket(attemptId, PairingBootstrapMessage.Abort(PairingAbortReason.ProtocolFailure)),
        ),
    )

    /**
     * Trust policy is evaluated only after a valid peer signature. This prevents an unsigned
     * network packet from probing the contents of the local trusted-peer store.
     */
    private fun failForTrustPolicy(error: PairingError): PairingEngineResult = when (error) {
        PairingError.AlreadyTrusted -> failWithReject(error, PairingRejectReason.AlreadyTrusted)
        PairingError.PeerIdentityKeyChanged,
        PairingError.IdentityBindingConflict,
        -> failWithReject(error, PairingRejectReason.IdentityConflict)
        PairingError.TrustStoreCapacityExceeded -> failWithReject(error, PairingRejectReason.Busy)
        else -> failWithAbort(error)
    }

    private fun failWithReject(error: PairingError, reason: PairingRejectReason): PairingEngineResult = terminal(
        PairingEngineState.Failed,
        error,
        PairingEngineAction.Send(PairingBootstrapPacket(attemptId, PairingBootstrapMessage.Reject(reason))),
    )

    private fun terminal(
        terminalState: PairingEngineState,
        error: PairingError,
        vararg actions: PairingEngineAction,
    ): PairingEngineResult {
        state = terminalState
        lastError = error
        destroySecrets()
        return PairingEngineResult(state, error, actions.toList())
    }

    private fun failed(error: PairingError): PairingEngineResult {
        lastError = error
        return PairingEngineResult(state, error)
    }

    private fun unexpected(): PairingEngineResult = failed(PairingError.UnexpectedMessage)

    private fun succeeded(vararg actions: PairingEngineAction): PairingEngineResult =
        PairingEngineResult(state, PairingError.None, actions.toList())

    private fun destroySecrets() {
        ephemeralKeyPair.close()
        derivedKeys?.destroy()
        derivedKeys = null
    }

    companion object {
        fun initiate(
            attemptId: PairingAttemptId,
            localSigner: LocalDeviceIdentitySigner,
            crypto: PairingCryptoProvider,
            trustInspector: PairingPeerTrustInspector = AcceptAnyUntrustedPeer,
            remoteUntrustedAlias: String? = null,
        ): PairingEngineStartResult {
            val ephemeral = when (val result = crypto.generateEphemeralKeyPair()) {
                is PairingCryptoResult.Value -> result.value
                is PairingCryptoResult.Failed -> {
                    return PairingEngineStartResult(
                        result = PairingEngineResult(PairingEngineState.Failed, PairingError.InvalidPacket),
                    )
                }
            }
            val nonce = PairingNonce.fromBytes(crypto.randomBytes(PairingBootstrapProtocol.NONCE_BYTES))
                ?: run {
                    ephemeral.close()
                    return PairingEngineStartResult(
                        result = PairingEngineResult(PairingEngineState.Failed, PairingError.InvalidPacket),
                    )
                }
            val material = InitiatorRevealMaterial(
                pairingAttemptId = attemptId,
                initiatorDeviceId = localSigner.identity.deviceId,
                suite = PairingCryptoSuite.P256Sha256,
                initiatorIdentityPublicKey = localSigner.identity.publicKey,
                initiatorEphemeralPublicKey = ephemeral.publicKey,
                initiatorNonce = nonce,
            )
            val engine = PairingEngine(
                PairingRole.Initiator,
                attemptId,
                localSigner,
                crypto,
                trustInspector,
                ephemeral,
                nonce,
                material,
                remoteUntrustedAlias,
            )
            return PairingEngineStartResult(engine, engine.start())
        }

        fun respond(
            attemptId: PairingAttemptId,
            localSigner: LocalDeviceIdentitySigner,
            crypto: PairingCryptoProvider,
            trustInspector: PairingPeerTrustInspector = AcceptAnyUntrustedPeer,
            remoteUntrustedAlias: String? = null,
        ): PairingEngineStartResult {
            val ephemeral = when (val result = crypto.generateEphemeralKeyPair()) {
                is PairingCryptoResult.Value -> result.value
                is PairingCryptoResult.Failed -> {
                    return PairingEngineStartResult(
                        result = PairingEngineResult(PairingEngineState.Failed, PairingError.InvalidPacket),
                    )
                }
            }
            val nonce = PairingNonce.fromBytes(crypto.randomBytes(PairingBootstrapProtocol.NONCE_BYTES))
                ?: run {
                    ephemeral.close()
                    return PairingEngineStartResult(
                        result = PairingEngineResult(PairingEngineState.Failed, PairingError.InvalidPacket),
                    )
                }
            val engine = PairingEngine(
                PairingRole.Responder,
                attemptId,
                localSigner,
                crypto,
                trustInspector,
                ephemeral,
                nonce,
                initiatorMaterial = null,
                remoteUntrustedAlias = remoteUntrustedAlias,
            )
            return PairingEngineStartResult(engine, PairingEngineResult(PairingEngineState.Idle))
        }

        fun trustError(error: TrustStoreError): PairingError = when (error) {
            TrustStoreError.None -> PairingError.None
            TrustStoreError.AlreadyTrusted -> PairingError.AlreadyTrusted
            TrustStoreError.PeerIdentityKeyChanged -> PairingError.PeerIdentityKeyChanged
            TrustStoreError.IdentityBindingConflict -> PairingError.IdentityBindingConflict
            TrustStoreError.TrustStoreCapacityExceeded -> PairingError.TrustStoreCapacityExceeded
            TrustStoreError.TrustStoreCorrupt,
            TrustStoreError.PersistenceFailure,
            TrustStoreError.InvalidPeerRecord,
            -> PairingError.TrustPersistenceFailure
        }
    }
}

private fun IdentityKeyResult<io.warpnect.session.identity.ImmutableBytes>.asSignatureOrNull(): PairingSignature? =
    when (this) {
        is IdentityKeyResult.Value -> PairingSignature.fromDer(value.toByteArray())
        is IdentityKeyResult.Failed -> null
    }

internal fun PairingEngineState.isTerminal(): Boolean = this in setOf(
    PairingEngineState.Paired,
    PairingEngineState.Rejected,
    PairingEngineState.TimedOut,
    PairingEngineState.Failed,
    PairingEngineState.Closed,
)
