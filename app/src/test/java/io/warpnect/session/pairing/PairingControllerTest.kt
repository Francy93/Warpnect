package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.trust.InMemoryTrustedPeerStorePersistence
import io.warpnect.session.trust.TrustedPeerStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingControllerTest {
    @Test
    fun explicitDualAcceptancePersistsTrustWithoutCreatingAnySession() {
        val clock = TestClock()
        val network = TestNetwork()
        val initiator = controller(1u, clock, network.a)
        val responder = controller(2u, clock, network.b)
        responder.openPairingWindow()

        assertTrue(initiator.beginPairing(network.b.endpoint, "Untrusted host alias").isSuccess)
        val attemptId = network.a.firstAttemptId()
        network.deliverAtoB()
        network.deliverBtoA()
        network.deliverAtoB()

        val initiatorPrompt = requireNotNull(initiator.verificationPrompt(attemptId))
        val responderPrompt = requireNotNull(responder.verificationPrompt(attemptId))
        assertEquals(initiatorPrompt.shortAuthenticationString, responderPrompt.shortAuthenticationString)
        assertTrue(initiator.acceptVerification(initiatorPrompt.attemptId).isSuccess)
        assertTrue(responder.acceptVerification(responderPrompt.attemptId).isSuccess)
        network.deliverAtoB()
        network.deliverBtoA()

        assertEquals(1, initiator.snapshot().trustedPeerCount)
        assertEquals(1, responder.snapshot().trustedPeerCount)
        assertEquals(1, initiator.snapshot().successfulPairings)
        assertEquals(1, responder.snapshot().successfulPairings)
    }

    @Test
    fun retriesAreByteIdenticalAndBoundedAtFour() {
        val clock = TestClock()
        val transport = TestTransport(PairingTransportEndpoint("initiator", 40001))
        val controller = controller(1u, clock, transport)
        controller.beginPairing(PairingTransportEndpoint("responder", 40002))
        val first = transport.sent.single().datagram.copyOf()

        listOf(249L, 250L, 750L, 1_750L, 3_750L).forEachIndexed { index, nowMs ->
            clock.now = nowMs
            controller.advance()
            assertEquals(index + 1, transport.sent.size)
        }
        transport.sent.drop(1).forEach { assertArrayEquals(first, it.datagram) }

        clock.now = 5_750L
        controller.advance()
        assertEquals(PairingError.PairingTransportTimeout, controller.snapshot().lastError)
        assertEquals(1, controller.snapshot().timeouts)
        assertTrue(
            transport.sent.last().datagram.let(
                PairingBootstrapCodec::decode,
            ).packet?.message is PairingBootstrapMessage.Abort,
        )
    }

    @Test
    fun pairingWindowAndSourceEndpointAreEnforced() {
        val clock = TestClock()
        val network = TestNetwork()
        val initiator = controller(1u, clock, network.a)
        val responder = controller(2u, clock, network.b)
        responder.openPairingWindow(100L)
        initiator.beginPairing(network.b.endpoint)
        val commit = network.a.takeNext().datagram

        clock.now = 100L
        network.b.deliver(network.a.endpoint, commit)
        assertTrue(
            network.b.sent.last().datagram.let(
                PairingBootstrapCodec::decode,
            ).packet?.message is PairingBootstrapMessage.Reject,
        )
        assertEquals(PairingError.PairingWindowExpired, responder.snapshot().lastError)
        network.b.takeNext()

        clock.now = 0L
        responder.openPairingWindow()
        network.b.deliver(network.a.endpoint, commit)
        val response = network.b.takeNext().datagram
        network.a.deliver(PairingTransportEndpoint("spoof", 49999), response)
        assertEquals(PairingError.EndpointMismatch, initiator.snapshot().lastError)
    }

    @Test
    fun userRejectionDoesNotPersistTrust() {
        val clock = TestClock()
        val network = TestNetwork()
        val initiator = controller(1u, clock, network.a)
        val responder = controller(2u, clock, network.b)
        responder.openPairingWindow()
        initiator.beginPairing(network.b.endpoint)
        val attemptId = network.a.firstAttemptId()
        network.deliverAtoB()
        network.deliverBtoA()
        network.deliverAtoB()

        val prompt = requireNotNull(initiator.verificationPrompt(attemptId))
        val rejected = initiator.rejectVerification(prompt.attemptId, mismatch = true)
        assertEquals(PairingError.VerificationMismatch, rejected.error)
        assertEquals(0, initiator.snapshot().trustedPeerCount)
        assertEquals(0, responder.snapshot().trustedPeerCount)
    }

    @Test
    fun rePairRepairsOneSidedFinalConfirmLossWithoutReplacingTrustedKey() {
        val clock = TestClock()
        val network = TestNetwork()
        val initiator = controller(1u, clock, network.a)
        val responder = controller(2u, clock, network.b)
        responder.openPairingWindow()

        initiator.beginPairing(network.b.endpoint)
        var attemptId = network.a.firstAttemptId()
        exchangeUntilPrompts(network)
        initiator.acceptVerification(attemptId)
        responder.acceptVerification(attemptId)
        network.deliverAtoB() // The responder becomes trusted.
        network.b.clearSent() // Drop its final Confirm to simulate an asymmetric completion.

        listOf(250L, 750L, 1_750L, 3_750L, 5_750L).forEach { nowMs ->
            clock.now = nowMs
            initiator.advance()
            responder.advance()
            network.a.clearSent()
            network.b.clearSent()
        }
        assertEquals(0, initiator.snapshot().trustedPeerCount)
        assertEquals(1, responder.snapshot().trustedPeerCount)

        initiator.beginPairing(network.b.endpoint)
        attemptId = network.a.firstAttemptId()
        exchangeUntilPrompts(network)
        initiator.acceptVerification(attemptId)
        responder.acceptVerification(attemptId)
        network.deliverAtoB()
        network.deliverBtoA()

        assertEquals(1, initiator.snapshot().trustedPeerCount)
        assertEquals(1, responder.snapshot().trustedPeerCount)
        assertEquals(1, responder.snapshot().alreadyTrusted)
        assertEquals(0, responder.snapshot().identityKeyMismatches)
    }

    private fun exchangeUntilPrompts(network: TestNetwork) {
        network.deliverAtoB()
        network.deliverBtoA()
        network.deliverAtoB()
    }

    private fun controller(deviceValue: ULong, clock: TestClock, transport: TestTransport): PairingController {
        val crypto = JcaPairingCryptoProvider()
        val signer = JcaSoftwareIdentitySigner.generate(DeviceId.requireValid(0u, deviceValue), crypto)
        return PairingController(
            localSigner = signer,
            trustedPeerStore = TrustedPeerStore(InMemoryTrustedPeerStorePersistence(), crypto::sha256),
            transport = transport,
            crypto = crypto,
            monotonicClock = clock,
            wallClock = PairingWallClock { 1_000L },
        )
    }

    private class TestClock(
        var now: Long = 0L,
    ) : PairingMonotonicClock {
        override fun nowMs(): Long = now
    }

    private class TestNetwork {
        val a = TestTransport(PairingTransportEndpoint("initiator", 40001))
        val b = TestTransport(PairingTransportEndpoint("responder", 40002))

        fun deliverAtoB() {
            val sent = a.takeNext()
            b.deliver(a.endpoint, sent.datagram)
        }

        fun deliverBtoA() {
            val sent = b.takeNext()
            a.deliver(b.endpoint, sent.datagram)
        }
    }

    private class TestTransport(
        val endpoint: PairingTransportEndpoint,
    ) : PairingTransport {
        private var listener: ((PairingTransportEndpoint, ByteArray) -> Unit)? = null
        val sent = ArrayList<SentDatagram>()

        override fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult {
            sent += SentDatagram(destination, datagram.copyOf())
            return PairingTransportSendResult.Sent
        }

        fun deliver(source: PairingTransportEndpoint, datagram: ByteArray) {
            listener?.invoke(source, datagram.copyOf())
        }

        fun takeNext(): SentDatagram = sent.removeAt(0)

        fun clearSent() {
            sent.clear()
        }

        fun firstAttemptId(): PairingAttemptId = requireNotNull(
            PairingBootstrapCodec.decode(sent.first().datagram).packet,
        ).attemptId

        override fun close() = Unit
    }

    private data class SentDatagram(
        val destination: PairingTransportEndpoint,
        val datagram: ByteArray,
    )
}
