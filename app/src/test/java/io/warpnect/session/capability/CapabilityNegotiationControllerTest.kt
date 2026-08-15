package io.warpnect.session.capability

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.AuthenticatedSessionAdmissionReservation
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityNegotiationControllerTest {
    @Test
    fun protectedOfferSelectionConfirmCompleteProducesOneProfilePerSession() {
        val fixture = Fixture()
        assertEquals(
            CapabilityNegotiationError.None,
            fixture.host.registerHost(fixture.hostBootstrap, fixture.hostTransport, HostCapabilityPolicy()),
        )
        assertEquals(
            CapabilityNegotiationError.None,
            fixture.client.beginClient(fixture.clientBootstrap, fixture.clientTransport, request()),
        )

        assertEquals(1, fixture.hostCompletions.size)
        assertEquals(1, fixture.clientCompletions.size)
        assertEquals(0, fixture.host.snapshot().activeNegotiations)
        assertEquals(0, fixture.client.snapshot().activeNegotiations)
        assertEquals(1, fixture.host.snapshot().completionCacheSize)
        assertEquals(1, fixture.reservation.renewCount)
        assertTrue(
            fixture.hostCompletions.single().profileHash.contentEquals(fixture.clientCompletions.single().profileHash),
        )
    }

    @Test
    fun duplicateClientConfirmUsesCompletionCacheWithoutSecondAdmission() {
        val fixture = Fixture()
        fixture.host.registerHost(fixture.hostBootstrap, fixture.hostTransport, HostCapabilityPolicy())
        fixture.client.beginClient(fixture.clientBootstrap, fixture.clientTransport, request())
        val confirm = fixture.clientTransport.sent.last { bytes ->
            (CapabilityNegotiationCodec.decode(bytes)?.message as? CapabilityNegotiationMessage.ClientConfirm) != null
        }
        val responsesBefore = fixture.hostTransport.sent.size
        fixture.host.receive(fixture.sessionId, confirm)
        assertEquals(1, fixture.reservation.renewCount)
        assertEquals(1, fixture.host.snapshot().completedNegotiations)
        assertTrue(fixture.hostTransport.sent.size > responsesBefore)
    }

    @Test
    fun changedSemanticRetryFailsClosedAndReleasesReservation() {
        val fixture = Fixture(hostToClientDelivery = false)
        fixture.host.registerHost(fixture.hostBootstrap, fixture.hostTransport, HostCapabilityPolicy())
        fixture.client.beginClient(fixture.clientBootstrap, fixture.clientTransport, request())
        val original = fixture.clientTransport.sent.single()
        val decoded = requireNotNull(
            CapabilityNegotiationCodec.decode(original),
        ).message as CapabilityNegotiationMessage.ClientOffer
        val changed = requireNotNull(
            CapabilityNegotiationCodec.encode(
                decoded.copy(
                    request = decoded.request.copy(requiredRecoveryFlags = CapabilityBits.RECOVERY_FEC),
                ),
            ),
        )
        fixture.host.receive(fixture.sessionId, changed)
        assertEquals(CapabilityNegotiationError.NegotiationConflict, fixture.host.snapshot().lastError)
        assertTrue(fixture.reservation.closed)
        assertEquals(0, fixture.host.snapshot().activeNegotiations)
    }

    @Test
    fun timeoutReleasesAuthenticatedAdmissionAndDoesNotCreateRunningSession() {
        val fixture = Fixture(hostToClientDelivery = false)
        fixture.host.registerHost(fixture.hostBootstrap, fixture.hostTransport, HostCapabilityPolicy())
        fixture.client.beginClient(fixture.clientBootstrap, fixture.clientTransport, request())
        fixture.clock.nowMs = CapabilityNegotiationProtocol.DEFAULT_TIMEOUT_MS
        fixture.host.advance()
        assertEquals(CapabilityNegotiationError.Timeout, fixture.host.snapshot().lastError)
        assertTrue(fixture.reservation.closed)
        assertEquals(0, fixture.hostCompletions.size)
    }

    @Test
    fun controllerNeverReceivesPlaintextUdpOnlyProtectedTransportPayloads() {
        val fixture = Fixture()
        fixture.host.registerHost(fixture.hostBootstrap, fixture.hostTransport, HostCapabilityPolicy())
        fixture.host.receive(
            fixture.sessionId,
            byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte()),
        )
        assertEquals(1, fixture.host.snapshot().malformedMessages)
        assertEquals(0, fixture.host.snapshot().activeNegotiations)
        assertFalse(fixture.hostTransport.sent.isNotEmpty())
    }

    private fun request() = CapabilityNegotiationCodecTest.request()

    private class Fixture(hostToClientDelivery: Boolean = true) {
        val sessionId = SessionId.requireValid(0u, 99u)
        val clock = TestClock()
        val reservation = TestReservation(sessionId)
        val hostTransport = TestTransport()
        val clientTransport = TestTransport()
        val hostCompletions = mutableListOf<NegotiatedSessionBootstrap>()
        val clientCompletions = mutableListOf<NegotiatedSessionBootstrap>()
        val hostBootstrap = bootstrap(SessionRole.Host, SessionRole.Client, reservation)
        val clientBootstrap = bootstrap(SessionRole.Client, SessionRole.Host, null)
        val host = CapabilityNegotiationController(
            collector = LocalCapabilityCollector { role -> CapabilityNegotiationCodecTest.snapshot(role) },
            clock = clock,
            idGenerator = CapabilityNegotiationIdGenerator { CapabilityNegotiationCodecTest.id() },
            onCompleted = hostCompletions::add,
        )
        val client = CapabilityNegotiationController(
            collector = LocalCapabilityCollector { role -> CapabilityNegotiationCodecTest.snapshot(role) },
            clock = clock,
            idGenerator = CapabilityNegotiationIdGenerator { CapabilityNegotiationCodecTest.id() },
            onCompleted = clientCompletions::add,
        )

        init {
            clientTransport.peer = hostTransport
            hostTransport.peer = clientTransport
            hostTransport.deliver = hostToClientDelivery
        }

        private fun bootstrap(
            localRole: SessionRole,
            remoteRole: SessionRole,
            admission: AuthenticatedSessionAdmissionReservation?,
        ) = SecureSessionCapabilityBootstrap(
            sessionId = sessionId,
            generation = SessionGeneration.Initial,
            localDeviceId = DeviceId.requireValid(0u, if (localRole == SessionRole.Host) 1u else 2u),
            remoteDeviceId = DeviceId.requireValid(0u, if (localRole == SessionRole.Host) 2u else 1u),
            localRole = localRole,
            remoteRole = remoteRole,
            endpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 49000),
            protection = TestRuntime(sessionId),
            admissionReservation = admission,
        )
    }

    private class TestTransport : SecureSessionControlTransport {
        override val maxPayloadBytes: Int = CapabilityNegotiationProtocol.MAX_PAYLOAD_BYTES
        val sent = mutableListOf<ByteArray>()
        var peer: TestTransport? = null
        var deliver: Boolean = true
        private var listener: ((ByteArray) -> Unit)? = null

        override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(payload: ByteArray): SecureSessionControlSendResult {
            sent += payload.copyOf()
            if (deliver) peer?.listener?.invoke(payload.copyOf())
            return SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())
        }

        override fun close() = Unit
    }

    private class TestClock(var nowMs: Long = 0L) : CapabilityNegotiationMonotonicClock {
        override fun nowMs(): Long = nowMs
    }

    private class TestReservation(
        override val sessionId: SessionId,
    ) : AuthenticatedSessionAdmissionReservation {
        override val peerDeviceId: DeviceId = DeviceId.requireValid(0u, 2u)
        override val expiresAtMonotonicMs: Long = Long.MAX_VALUE
        var renewCount = 0
        var closed = false
        override fun renew(lifetimeMs: Long): Boolean {
            renewCount += 1
            return !closed && lifetimeMs == CapabilityNegotiationProtocol.POST_NEGOTIATION_RESERVATION_MS
        }
        override fun close() {
            closed = true
        }
    }

    private class TestRuntime(override val sessionId: SessionId) : SessionProtectionRuntime {
        override val sessionControlContext: ProtectionContextIds = ProtectionContextIds(1, 2)
        override val maxInnerSclDatagramSize: Int = 1_156
        override fun createChannelContext(channelId: io.warpnect.session.ChannelId) =
            SessionProtectionContextResult(SessionProtectionError.None, ProtectionContextIds(3, 4))
        override fun destroyChannelContext(channelId: io.warpnect.session.ChannelId) = SessionProtectionError.None
        override fun protectSessionControl(sequenceNumber: Long, timestampUs: Long, payload: ByteArray) =
            SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())
        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ) = io.warpnect.session.control.SessionControlUnprotectResult(
            SessionProtectionError.None,
            protectedDatagram.copyOf(),
        )
        override fun snapshot() =
            SessionProtectionSnapshot(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SessionProtectionError.None)
        override fun close() = Unit
    }
}
