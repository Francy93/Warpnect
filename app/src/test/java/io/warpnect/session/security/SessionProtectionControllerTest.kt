package io.warpnect.session.security

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.handshake.AuthenticatedSessionAdmissionReservation
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.AuthenticatedSessionRootSecret
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeAttemptId
import io.warpnect.session.identity.ImmutableBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProtectionControllerTest {
    @Test
    fun successfulCreationConsumesRootAndRetainsAdmissionForLaterNegotiation() {
        val reservation = TestReservation()
        val runtime = TestRuntime()
        val controller = SessionProtectionController(
            SessionProtectionRuntimeFactory { root, _, _ ->
                assertEquals(32, root.size)
                SessionProtectionCreationResult(SessionProtectionError.None, runtime)
            },
        )
        val bootstrap = bootstrap(reservation)

        val result = controller.createSessionProtection(bootstrap)

        assertTrue(result.isSuccess)
        assertTrue(bootstrap.rootSecret.isDestroyed)
        assertFalse(reservation.closed)
        assertEquals(1, controller.snapshotCount())
    }

    @Test
    fun failedNativeInitializationDestroysRootAndReleasesAdmission() {
        val reservation = TestReservation()
        val controller = SessionProtectionController(
            SessionProtectionRuntimeFactory { _, _, _ ->
                SessionProtectionCreationResult(SessionProtectionError.CryptoFailure)
            },
        )
        val bootstrap = bootstrap(reservation)

        val result = controller.createSessionProtection(bootstrap)

        assertEquals(SessionProtectionError.CryptoFailure, result.error)
        assertTrue(bootstrap.rootSecret.isDestroyed)
        assertTrue(reservation.closed)
        assertEquals(0, controller.snapshotCount())
    }

    @Test
    fun controllerBoundsNativeRuntimesWithoutStartingAnyMediaSession() {
        val runtime = TestRuntime()
        val controller = SessionProtectionController(
            factory = SessionProtectionRuntimeFactory { _, _, _ ->
                SessionProtectionCreationResult(SessionProtectionError.None, runtime)
            },
            config = SessionProtectionConfig(maxRuntimes = 1),
        )
        val first = bootstrap(TestReservation(), sessionLow = 1uL)
        val secondReservation = TestReservation()
        val second = bootstrap(secondReservation, sessionLow = 2uL)

        assertTrue(controller.createSessionProtection(first).isSuccess)
        assertEquals(SessionProtectionError.Busy, controller.createSessionProtection(second).error)
        assertTrue(second.rootSecret.isDestroyed)
        assertTrue(secondReservation.closed)
    }

    private fun bootstrap(reservation: TestReservation, sessionLow: ULong = 1uL): AuthenticatedSessionBootstrap {
        return AuthenticatedSessionBootstrap(
            sessionId = SessionId.requireValid(0uL, sessionLow),
            generation = SessionGeneration.Initial,
            localDeviceId = DeviceId.requireValid(0uL, 10uL),
            remoteDeviceId = DeviceId.requireValid(0uL, 20uL),
            localRole = SessionRole.Host,
            remoteRole = SessionRole.Client,
            attemptId = SessionHandshakeAttemptId.requireValid(0uL, 30uL),
            authenticatedTranscriptHash = ImmutableBytes.copyOf(ByteArray(32) { 1 }),
            endpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 4_000),
            rootSecret = AuthenticatedSessionRootSecret(ByteArray(32) { 2 }),
            admissionReservation = reservation,
        )
    }

    private class TestReservation : AuthenticatedSessionAdmissionReservation {
        override val sessionId: SessionId = SessionId.requireValid(0uL, 1uL)
        override val peerDeviceId: DeviceId = DeviceId.requireValid(0uL, 20uL)
        override val expiresAtMonotonicMs: Long = 10_000L
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class TestRuntime : SessionProtectionRuntime {
        override val sessionId: SessionId = SessionId.requireValid(0uL, 1uL)
        override val sessionControlContext: ProtectionContextIds = ProtectionContextIds(1L, 2L)
        override val maxInnerSclDatagramSize: Int = 1_156
        override fun createChannelContext(channelId: io.warpnect.session.ChannelId): SessionProtectionContextResult =
            SessionProtectionContextResult(SessionProtectionError.None, ProtectionContextIds(3L, 4L))
        override fun destroyChannelContext(channelId: io.warpnect.session.ChannelId): SessionProtectionError =
            SessionProtectionError.None
        override fun protectSessionControl(
            sequenceNumber: Long,
            timestampUs: Long,
            payload: ByteArray,
        ): io.warpnect.session.control.SecureSessionControlSendResult =
            io.warpnect.session.control.SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())
        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ): io.warpnect.session.control.SessionControlUnprotectResult =
            io.warpnect.session.control.SessionControlUnprotectResult(
                SessionProtectionError.None,
                protectedDatagram.copyOf(),
            )
        override fun snapshot(): SessionProtectionSnapshot = SessionProtectionSnapshot(
            1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SessionProtectionError.None,
        )
        override fun close() = Unit
    }
}
