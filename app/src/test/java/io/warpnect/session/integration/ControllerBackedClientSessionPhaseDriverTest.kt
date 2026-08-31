package io.warpnect.session.integration

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.CapabilityNegotiationCodec
import io.warpnect.session.capability.CapabilityNegotiationCodecTest
import io.warpnect.session.capability.CapabilityNegotiationController
import io.warpnect.session.capability.CapabilityNegotiationHeader
import io.warpnect.session.capability.CapabilityNegotiationMessage
import io.warpnect.session.capability.CapabilityNegotiationMessageType
import io.warpnect.session.capability.CapabilityNegotiationMonotonicClock
import io.warpnect.session.capability.CapabilityNegotiationRejectReason
import io.warpnect.session.capability.CapabilityNegotiationRejectStage
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.capability.LocalCapabilityCollector
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.discovery.DefaultLocalDiscoveryController
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoveryAvailability
import io.warpnect.session.discovery.DiscoveryBackendPolicy
import io.warpnect.session.discovery.DiscoveryConfig
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseResult
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.DiscoveryMode
import io.warpnect.session.discovery.DiscoveryMonotonicClock
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryPresenceStatus
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.AuthenticatedSessionRootSecret
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeAttemptId
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionController
import io.warpnect.session.security.SessionProtectionCreationResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionRuntimeFactory
import io.warpnect.session.security.SessionProtectionSnapshot
import io.warpnect.session.setup.SessionSetupPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerBackedClientSessionPhaseDriverTest {
    @Test
    fun terminalCapabilityRejectFailsTheClientAttemptExactlyOnce() {
        val transport = RejectingSecureControlTransport()
        val driver = driver(transport)
        val failures = mutableListOf<Pair<SecureSessionIntegrationStage, SecureSessionIntegrationError>>()
        val listener = object : SecureSessionPhaseListener {
            override fun onPairingRequired() = Unit

            override fun onPairingCompleted() = Unit

            override fun onAuthenticated(bootstrap: AuthenticatedSessionBootstrap) = Unit

            override fun onCapabilitiesNegotiated(
                bootstrap: io.warpnect.session.capability.NegotiatedSessionBootstrap,
            ) = Unit

            override fun onPrepared(bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap) = Unit

            override fun onFailed(stage: SecureSessionIntegrationStage, error: SecureSessionIntegrationError) {
                failures += stage to error
            }
        }

        val secure = requireNotNull(driver.createSecureCapabilityBootstrap(authenticatedBootstrap()).bootstrap)
        assertEquals(
            SecureSessionIntegrationError.None,
            driver.beginCapabilities(secure, request(), listener),
        )

        driver.advance()
        driver.advance()

        assertEquals(
            listOf(
                SecureSessionIntegrationStage.Capabilities to
                    SecureSessionIntegrationError.CapabilityNegotiationFailed,
            ),
            failures,
        )
        assertEquals(1, transport.rejectsDelivered)
        driver.close()
    }

    private fun driver(transport: RejectingSecureControlTransport): ControllerBackedClientSessionPhaseDriver =
        ControllerBackedClientSessionPhaseDriver(
            discovery = DefaultLocalDiscoveryController(
                config = DiscoveryConfig(
                    mode = DiscoveryMode.BrowseOnly,
                    backendPolicy = DiscoveryBackendPolicy.LanOnly,
                    offeredRole = SessionRole.Client,
                ),
                backends = emptyList(),
                contactEndpointLeaseFactory = {
                    DiscoveryContactEndpointLeaseResult(error = DiscoveryError.OperationRejected)
                },
                clock = DiscoveryMonotonicClock { 0L },
            ),
            handshakeTransportFactory = ClientSessionHandshakeTransportFactory { null },
            handshakeFactory = ClientSessionHandshakeControllerFactory { _, _ -> error("unused") },
            pairingTransportFactory = ClientPairingTransportFactory { null },
            pairingFactory = ClientPairingControllerFactory { _, _, _ -> error("unused") },
            protection = SessionProtectionController(
                SessionProtectionRuntimeFactory { _, bootstrap, _ ->
                    SessionProtectionCreationResult(
                        SessionProtectionError.None,
                        TestProtectionRuntime(bootstrap.sessionId),
                    )
                },
            ),
            secureControlFactory = SecureSessionControlTransportFactory { transport },
            capabilityFactory = ClientCapabilityNegotiationControllerFactory {
                CapabilityNegotiationController(
                    collector = LocalCapabilityCollector(CapabilityNegotiationCodecTest::snapshot),
                    clock = CapabilityNegotiationMonotonicClock { 0L },
                )
            },
            setupFactory = ClientSessionSetupControllerFactory { error("unused") },
            setupRuntimeFactory = ClientSessionSetupRuntimeFactory { _, _ -> null },
        )

    private fun authenticatedBootstrap(): AuthenticatedSessionBootstrap = AuthenticatedSessionBootstrap(
        sessionId = SessionId.requireValid(0u, 1u),
        generation = SessionGeneration.Initial,
        localDeviceId = DeviceId.requireValid(0u, 1u),
        remoteDeviceId = DeviceId.requireValid(0u, 2u),
        localRole = SessionRole.Client,
        remoteRole = SessionRole.Host,
        attemptId = SessionHandshakeAttemptId.requireValid(0uL, 1uL),
        authenticatedTranscriptHash = ImmutableBytes.copyOf(ByteArray(32)),
        endpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 49_000),
        rootSecret = AuthenticatedSessionRootSecret(ByteArray(32) { 1 }),
    )

    private fun request(): SecureSessionConnectRequest = SecureSessionConnectRequest(
        presence = DiscoveredPresence(
            DiscoveryPresenceId.requireValid(0uL, 1uL),
            null,
            SessionRole.Host,
            DiscoveryAvailability.Available,
            1,
            0L,
            0L,
            listOf(DiscoveryRouteKind.Lan),
            DiscoveryPresenceStatus.Usable,
        ),
        capabilityRequest = CapabilityRequest(
            CapabilityBits.CHANNEL_VIDEO,
            0,
            CapabilityBits.OPTIONAL_CHANNEL_MASK xor CapabilityBits.CHANNEL_VIDEO,
            0,
            0,
            MicrophoneRoutingSelection.NotApplicable,
            MicrophoneRoutingSelection.NotApplicable,
            0,
            0,
            io.warpnect.session.capability.FeatureRequirement.Disabled,
            io.warpnect.session.capability.FeatureRequirement.Disabled,
            0,
        ),
        setupPreferences = SessionSetupPreferences(),
    )

    private class RejectingSecureControlTransport : SecureSessionControlTransport {
        override val maxPayloadBytes: Int = 1_156
        var rejectsDelivered = 0
        private var listener: ((ByteArray) -> Unit)? = null

        override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(payload: ByteArray): SecureSessionControlSendResult {
            val offer = requireNotNull(CapabilityNegotiationCodec.decode(payload)).message
                as CapabilityNegotiationMessage.ClientOffer
            val reject = CapabilityNegotiationMessage.Reject(
                header = CapabilityNegotiationHeader(
                    CapabilityNegotiationMessageType.NegotiationReject,
                    offer.header.negotiationId,
                    0,
                ),
                stage = CapabilityNegotiationRejectStage.Offer,
                reason = CapabilityNegotiationRejectReason.Incompatible,
                relatedHash = CapabilityNegotiationCodec.hash(payload),
            )
            listener?.invoke(requireNotNull(CapabilityNegotiationCodec.encode(reject)))
            rejectsDelivered += 1
            return SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())
        }

        override fun close() = Unit
    }

    private class TestProtectionRuntime(
        override val sessionId: SessionId,
    ) : SessionProtectionRuntime {
        override val sessionControlContext = ProtectionContextIds(1, 2)
        override val maxInnerSclDatagramSize = 1_156

        override fun createChannelContext(channelId: io.warpnect.session.ChannelId) =
            SessionProtectionContextResult(SessionProtectionError.None, ProtectionContextIds(3, 4))

        override fun destroyChannelContext(channelId: io.warpnect.session.ChannelId) = SessionProtectionError.None

        override fun protectSessionControl(sequenceNumber: Long, timestampUs: Long, payload: ByteArray) =
            SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())

        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ) = SessionControlUnprotectResult(SessionProtectionError.None, protectedDatagram.copyOf())

        override fun snapshot() = SessionProtectionSnapshot(
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            SessionProtectionError.None,
        )

        override fun close() = Unit
    }
}
