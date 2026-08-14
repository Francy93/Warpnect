package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingEngineTest {
    private val crypto = JcaPairingCryptoProvider()

    @Test
    fun happyPathProducesSameSasAndOneCompletionPerPeer() {
        val flow = startFlow()
        val initiatorPrompt = prompt(flow.initiatorAfterResponse)
        val responderPrompt = prompt(flow.responderAfterReveal)
        assertEquals(initiatorPrompt.shortAuthenticationString, responderPrompt.shortAuthenticationString)
        assertEquals(6, initiatorPrompt.shortAuthenticationString.length)

        val initiatorConfirm = send(flow.initiator.acceptVerification())
        val responderConfirm = send(flow.responder.acceptVerification())
        val responderCompleted = flow.responder.receive(initiatorConfirm)
        val initiatorCompleted = flow.initiator.receive(responderConfirm)

        assertEquals(PairingEngineState.Paired, flow.initiator.state)
        assertEquals(PairingEngineState.Paired, flow.responder.state)
        val firstCompletion = completed(responderCompleted)
        val secondCompletion = completed(initiatorCompleted)
        assertEquals(firstCompletion.transcriptHash, secondCompletion.transcriptHash)
        assertEquals(flow.initiatorSigner.identity.deviceId, firstCompletion.remotePeer.deviceId)
        assertEquals(flow.responderSigner.identity.deviceId, secondCompletion.remotePeer.deviceId)
        val duplicateConfirm = flow.initiator.receive(responderConfirm)
        assertEquals(PairingError.None, duplicateConfirm.error)
        assertFalse(duplicateConfirm.actions.any { it is PairingEngineAction.Completed })
    }

    @Test
    fun tamperedResponseFailsSignatureBeforeSas() {
        val initiatorSigner = signer(1u)
        val responderSigner = signer(2u)
        val attempt = attempt(1u)
        val initial = PairingEngine.initiate(attempt, initiatorSigner, crypto)
        val initiator = requireNotNull(initial.engine)
        val responder = requireNotNull(PairingEngine.respond(attempt, responderSigner, crypto).engine)
        val response = send(responder.receive(send(initial.result)))
        val original = response.message as PairingBootstrapMessage.Response
        val tampered = PairingBootstrapPacket(
            attempt,
            original.copy(responderNonce = PairingNonce.requireBytes(ByteArray(32) { 9 })),
        )

        val result = initiator.receive(tampered)
        assertEquals(PairingError.SignatureInvalid, result.error)
        assertEquals(PairingEngineState.Failed, initiator.state)
        assertFalse(result.actions.any { it is PairingEngineAction.Prompt })
    }

    @Test
    fun revealThatDoesNotMatchCommitFailsBeforeSignatureOrSas() {
        val flow = startFlow(stopBeforeReveal = true)
        val reveal = send(flow.initiatorAfterResponse)
        val original = reveal.message as PairingBootstrapMessage.Reveal
        val tampered = PairingBootstrapPacket(
            reveal.attemptId,
            original.copy(
                material = original.material.copy(initiatorNonce = PairingNonce.requireBytes(ByteArray(32) { 11 })),
            ),
        )

        val result = flow.responder.receive(tampered)
        assertEquals(PairingError.CommitmentMismatch, result.error)
        assertEquals(PairingEngineState.Failed, flow.responder.state)
        assertFalse(result.actions.any { it is PairingEngineAction.Prompt })
    }

    @Test
    fun directionalConfirmKeysPreventReflection() {
        val flow = startFlow()
        val initiatorConfirm = send(flow.initiator.acceptVerification())

        val reflected = flow.initiator.receive(initiatorConfirm)
        assertEquals(PairingError.ConfirmationMacInvalid, reflected.error)
        assertEquals(PairingEngineState.Failed, flow.initiator.state)
    }

    @Test
    fun duplicateResponseResendsExactRevealWithoutSecondPrompt() {
        val flow = startFlow(stopBeforeReveal = true)
        val firstReveal = send(flow.initiatorAfterResponse)
        val response = flow.response
        val duplicate = flow.initiator.receive(response)

        assertEquals(PairingError.None, duplicate.error)
        assertEquals(firstReveal, send(duplicate))
        assertFalse(duplicate.actions.any { it is PairingEngineAction.Prompt })
    }

    @Test
    fun outOfOrderConfirmDoesNotChangeState() {
        val initiatorSigner = signer(1u)
        val attempt = attempt(1u)
        val initial = PairingEngine.initiate(attempt, initiatorSigner, crypto)
        val initiator = requireNotNull(initial.engine)
        val unexpected = initiator.receive(
            PairingBootstrapPacket(
                attempt,
                PairingBootstrapMessage.Confirm(hash(1), PairingConfirmationMac.requireSha256(ByteArray(32) { 2 })),
            ),
        )

        assertEquals(PairingError.UnexpectedMessage, unexpected.error)
        assertEquals(PairingEngineState.CommitSent, initiator.state)
    }

    @Test
    fun knownKeyChangeFailsClosedBeforePrompt() {
        val inspector = PairingPeerTrustInspector { _, _ -> PairingError.PeerIdentityKeyChanged }
        val initiatorSigner = signer(1u)
        val responderSigner = signer(2u)
        val attempt = attempt(1u)
        val started = PairingEngine.initiate(attempt, initiatorSigner, crypto, inspector)
        val initiator = requireNotNull(started.engine)
        val responder = requireNotNull(PairingEngine.respond(attempt, responderSigner, crypto).engine)
        val response = send(responder.receive(send(started.result)))

        val result = initiator.receive(response)
        assertEquals(PairingError.PeerIdentityKeyChanged, result.error)
        assertEquals(PairingEngineState.Failed, initiator.state)
        assertFalse(result.actions.any { it is PairingEngineAction.Prompt })
    }

    @Test
    fun selfPairingIsRejectedByDeviceId() {
        val sameId = device(1u)
        val initiatorSigner = JcaSoftwareIdentitySigner.generate(sameId, crypto)
        val responderSigner = JcaSoftwareIdentitySigner.generate(sameId, crypto)
        val attempt = attempt(3u)
        val started = PairingEngine.initiate(attempt, initiatorSigner, crypto)
        val initiator = requireNotNull(started.engine)
        val responder = requireNotNull(PairingEngine.respond(attempt, responderSigner, crypto).engine)
        val response = send(responder.receive(send(started.result)))

        assertEquals(PairingError.SelfPairing, initiator.receive(response).error)
    }

    @Test
    fun attemptIdChangesCommitmentAndTranscript() {
        val first = startFlow(attempt = attempt(1u))
        val second = startFlow(attempt = attempt(2u))
        assertNotEquals(
            (send(first.initialResult).message as PairingBootstrapMessage.Commit).commitHash,
            (send(second.initialResult).message as PairingBootstrapMessage.Commit).commitHash,
        )
        assertNotEquals(
            prompt(first.initiatorAfterResponse).shortAuthenticationString,
            prompt(second.initiatorAfterResponse).shortAuthenticationString,
        )
    }

    private fun startFlow(attempt: PairingAttemptId = attempt(1u), stopBeforeReveal: Boolean = false): PairingFlow {
        val initiatorSigner = signer(1u)
        val responderSigner = signer(2u)
        val started = PairingEngine.initiate(attempt, initiatorSigner, crypto)
        val initiator = requireNotNull(started.engine)
        val responder = requireNotNull(PairingEngine.respond(attempt, responderSigner, crypto).engine)
        val responseResult = responder.receive(send(started.result))
        val response = send(responseResult)
        val initiatorAfterResponse = initiator.receive(response)
        val responderAfterReveal = if (stopBeforeReveal) {
            PairingEngineResult(responder.state)
        } else {
            responder.receive(send(initiatorAfterResponse))
        }
        return PairingFlow(
            initiator,
            responder,
            initiatorSigner,
            responderSigner,
            started.result,
            response,
            initiatorAfterResponse,
            responderAfterReveal,
        )
    }

    private fun signer(value: ULong): JcaSoftwareIdentitySigner = JcaSoftwareIdentitySigner.generate(
        device(value),
        crypto,
    )

    private fun device(value: ULong): DeviceId = DeviceId.requireValid(0u, value)

    private fun attempt(value: ULong): PairingAttemptId = PairingAttemptId.requireValid(0u, value)

    private fun hash(value: Int): PairingHash = PairingHash.requireSha256(ByteArray(32) { value.toByte() })

    private fun send(result: PairingEngineResult): PairingBootstrapPacket =
        requireNotNull(result.actions.filterIsInstance<PairingEngineAction.Send>().singleOrNull())
            .packet

    private fun prompt(result: PairingEngineResult): PairingEngineAction.Prompt =
        requireNotNull(result.actions.filterIsInstance<PairingEngineAction.Prompt>().singleOrNull())

    private fun completed(result: PairingEngineResult): PairingEngineAction.Completed =
        requireNotNull(result.actions.filterIsInstance<PairingEngineAction.Completed>().singleOrNull())

    private data class PairingFlow(
        val initiator: PairingEngine,
        val responder: PairingEngine,
        val initiatorSigner: JcaSoftwareIdentitySigner,
        val responderSigner: JcaSoftwareIdentitySigner,
        val initialResult: PairingEngineResult,
        val response: PairingBootstrapPacket,
        val initiatorAfterResponse: PairingEngineResult,
        val responderAfterReveal: PairingEngineResult,
    )
}
