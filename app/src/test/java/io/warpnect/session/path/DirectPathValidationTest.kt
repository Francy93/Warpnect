package io.warpnect.session.path

import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.setup.PathAttemptId
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SessionSetupHeader
import io.warpnect.session.setup.SessionSetupId
import io.warpnect.session.setup.SessionSetupMessage
import io.warpnect.session.setup.SessionSetupMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPathValidationTest {
    @Test
    fun authenticatedProbeBindsOnlyOneCandidateEndpoint() {
        val fixture = Fixture()

        val accepted = fixture.window.acceptProbe(fixture.probe(), fixture.client, 99)
        val changedEndpoint = fixture.window.acceptProbe(fixture.probe(), fixture.otherClient, 99)

        assertEquals(fixture.client, (accepted as DirectPathValidationResult.Accepted).endpoint)
        assertEquals(
            SessionSetupError.EndpointMismatch,
            (changedEndpoint as DirectPathValidationResult.Failure).error,
        )
    }

    @Test
    fun setupAttemptAndProfileAreAllBound() {
        val fixture = Fixture()
        val wrongSetup = fixture.probe().copy(
            header = SessionSetupHeader(
                SessionSetupMessageType.DirectPathProbe,
                SessionSetupId.requireValid(8u),
                0,
            ),
        )
        val wrongAttempt = fixture.probe().copy(pathAttemptId = PathAttemptId.requireValid(10u))
        val wrongProfile = fixture.probe().copy(profileHash = ByteArray(32) { 3 })

        listOf(wrongSetup, wrongAttempt, wrongProfile).forEach { probe ->
            val result = fixture.window.acceptProbe(probe, fixture.client, 10)
            assertEquals(
                SessionSetupError.DirectProbeAuthenticationFailed,
                (result as DirectPathValidationResult.Failure).error,
            )
        }
    }

    @Test
    fun ackRequiresExactHostEndpointAndWindowLifetime() {
        val fixture = Fixture()
        val wrongSource = fixture.window.expectedAck(fixture.ack(), fixture.client, fixture.host, 10)
        val valid = fixture.window.expectedAck(fixture.ack(), fixture.host, fixture.host, 99)
        val expired = fixture.window.expectedAck(fixture.ack(), fixture.host, fixture.host, 100)

        assertEquals(
            SessionSetupError.DirectProbeAuthenticationFailed,
            (wrongSource as DirectPathValidationResult.Failure).error,
        )
        assertTrue(valid is DirectPathValidationResult.Accepted)
        assertEquals(
            SessionSetupError.DirectValidationTimeout,
            (expired as DirectPathValidationResult.Failure).error,
        )
    }

    @Test
    fun constructorFreezesProfileHashAndCloseRejectsLateProbe() {
        val profileHash = ByteArray(32) { it.toByte() }
        val fixture = Fixture(profileHash)
        profileHash.fill(0)
        assertTrue(
            fixture.window.acceptProbe(
                fixture.probe(),
                fixture.client,
                10,
            ) is DirectPathValidationResult.Accepted,
        )

        fixture.window.close()
        val closed = fixture.window.acceptProbe(fixture.probe(), fixture.client, 10)
        assertEquals(SessionSetupError.DirectValidationTimeout, (closed as DirectPathValidationResult.Failure).error)
    }

    private class Fixture(profile: ByteArray = ByteArray(32) { it.toByte() }) {
        private val expectedProfile = ByteArray(32) { it.toByte() }
        private val setupId = SessionSetupId.requireValid(7u)
        private val attemptId = PathAttemptId.requireValid(9u)
        val client = endpoint(49_001)
        val otherClient = endpoint(49_002)
        val host = endpoint(49_000)
        val window = DirectPathValidationWindow(setupId.value, profile, attemptId, 100)

        fun probe() = SessionSetupMessage.DirectPathProbe(
            SessionSetupHeader(SessionSetupMessageType.DirectPathProbe, setupId, 0),
            expectedProfile.copyOf(),
            attemptId,
        )

        fun ack() = SessionSetupMessage.DirectPathAck(
            SessionSetupHeader(SessionSetupMessageType.DirectPathAck, setupId, 0),
            expectedProfile.copyOf(),
            attemptId,
        )

        private fun endpoint(port: Int) =
            HandshakeTransportEndpoint.requireValid(byteArrayOf(192.toByte(), 168.toByte(), 49, 2), port)
    }
}
