package io.warpnect.session.integration

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionBehaviorPolicy
import io.warpnect.session.SessionManager
import io.warpnect.session.SessionManagerConfig
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.CapabilityNegotiationController
import io.warpnect.session.capability.CapabilityNegotiationMonotonicClock
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.capability.FeatureRequirement
import io.warpnect.session.capability.HostCapabilityPolicy
import io.warpnect.session.capability.LocalCapabilityCollector
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.discovery.DefaultLocalDiscoveryController
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoveryAddressCandidate
import io.warpnect.session.discovery.DiscoveryAdvertisement
import io.warpnect.session.discovery.DiscoveryAvailability
import io.warpnect.session.discovery.DiscoveryBackend
import io.warpnect.session.discovery.DiscoveryBackendAdvertisingRequest
import io.warpnect.session.discovery.DiscoveryBackendCommandResult
import io.warpnect.session.discovery.DiscoveryBackendObserver
import io.warpnect.session.discovery.DiscoveryBackendPolicy
import io.warpnect.session.discovery.DiscoveryConfig
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseResult
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.DiscoveryMode
import io.warpnect.session.discovery.DiscoveryMonotonicClock
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryRouteDescriptor
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.DiscoveryRouteObservation
import io.warpnect.session.discovery.HostAvailabilityProvider
import io.warpnect.session.discovery.PairingBootstrapContactEndpointLease
import io.warpnect.session.discovery.SessionHandshakeBootstrapContactEndpointLease
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeController
import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.pairing.JcaSoftwareIdentitySigner
import io.warpnect.session.pairing.PairingBootstrapCodec
import io.warpnect.session.pairing.PairingBootstrapMessage
import io.warpnect.session.pairing.PairingBootstrapPacket
import io.warpnect.session.pairing.PairingController
import io.warpnect.session.pairing.PairingRejectReason
import io.warpnect.session.pairing.PairingTransport
import io.warpnect.session.pairing.PairingTransportEndpoint
import io.warpnect.session.pairing.PairingTransportSendResult
import io.warpnect.session.security.SessionProtectionController
import io.warpnect.session.security.SessionProtectionCreationResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntimeFactory
import io.warpnect.session.setup.HostSessionSetupPolicy
import io.warpnect.session.setup.SessionSetupController
import io.warpnect.session.setup.SessionSetupMonotonicClock
import io.warpnect.session.setup.SessionSetupPreferences
import io.warpnect.session.trust.InMemoryTrustedPeerStorePersistence
import io.warpnect.session.trust.TrustedPeerStore
import io.warpnect.ui.hostChooserRows
import io.warpnect.ui.secureSessionScreenUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDiscoveryStartupTest {
    @Test
    fun freshClientDiscoveryPreparesThenStartsBrowsing() {
        val backend = RecordingBackend()
        val driver = ControllerBackedClientSessionPhaseDriver(
            discovery = DefaultLocalDiscoveryController(
                config = DiscoveryConfig(
                    mode = DiscoveryMode.BrowseOnly,
                    backendPolicy = DiscoveryBackendPolicy.LanOnly,
                    offeredRole = SessionRole.Client,
                ),
                backends = listOf(backend),
                contactEndpointLeaseFactory = {
                    DiscoveryContactEndpointLeaseResult(RecordingContactLease())
                },
                clock = DiscoveryMonotonicClock { 0L },
            ),
            handshakeTransportFactory = ClientSessionHandshakeTransportFactory {
                error("Handshake transport is unused by discovery startup")
            },
            handshakeFactory = ClientSessionHandshakeControllerFactory { _, _ ->
                error("Handshake controller is unused by discovery startup")
            },
            pairingTransportFactory = ClientPairingTransportFactory {
                error("Pairing transport is unused by discovery startup")
            },
            pairingFactory = ClientPairingControllerFactory { _, _, _ ->
                error("Pairing controller is unused by discovery startup")
            },
            protection = SessionProtectionController(
                SessionProtectionRuntimeFactory { _, _, _ ->
                    SessionProtectionCreationResult(SessionProtectionError.CryptoFailure)
                },
            ),
            secureControlFactory = SecureSessionControlTransportFactory {
                error("Secure control is unused by discovery startup")
            },
            capabilityFactory = ClientCapabilityNegotiationControllerFactory {
                error("Capability negotiation is unused by discovery startup")
            },
            setupFactory = ClientSessionSetupControllerFactory {
                error("Session setup is unused by discovery startup")
            },
            setupRuntimeFactory = ClientSessionSetupRuntimeFactory { _, _ ->
                error("Session runtime is unused by discovery startup")
            },
        )

        assertEquals(SecureSessionIntegrationError.None, driver.startDiscovery(SessionRole.Client))
        assertEquals(1, backend.browsingStarts)
        assertTrue(backend.browsing)

        driver.stopDiscovery()
        assertFalse(backend.browsing)

        assertEquals(SecureSessionIntegrationError.None, driver.startDiscovery(SessionRole.Client))
        assertEquals(2, backend.browsingStarts)
        assertTrue(backend.browsing)

        driver.close()
    }

    @Test
    fun freshApplicationHostStartAdvertisesBeforeBorrowingPairingTransportAndStartsNoMedia() {
        val lease = RecordingContactLease()
        val backend = RecordingBackend()
        val pipelineFactory = CountingUnusedPipelineFactory()
        val application = application(discovery(backend, lease), pipelineFactory) { discovery ->
            discovery.borrowPairingTransport()?.let { transport ->
                RecordingResponder(transport).also { responder -> lease.borrowedResponder = responder }
            }
        }

        val result = application.startHost()

        assertEquals(SecureSessionIntegrationError.None, result.error)
        assertEquals(SessionRole.Host, application.snapshot.value.activeRole)
        assertEquals(SecureSessionCoordinatorState.Discovering, application.snapshot.value.active?.state)
        assertEquals(1, backend.advertisementStarts)
        assertEquals(1, lease.pairingBorrows)
        assertNotNull(lease.borrowedResponder)
        assertSame(lease.pairingTransport, lease.borrowedResponder?.transport)
        assertEquals(0, pipelineFactory.createCalls)

        application.close()
    }

    @Test
    fun unavailableAdvertisedContactEndpointReturnsTypedHostDiscoveryFailureWithoutStartingMedia() {
        val backend = RecordingBackend()
        val pipelineFactory = CountingUnusedPipelineFactory()
        val discovery = DefaultLocalDiscoveryController(
            config = hostDiscoveryConfig(),
            backends = listOf(backend),
            contactEndpointLeaseFactory = {
                DiscoveryContactEndpointLeaseResult(error = DiscoveryError.ContactEndpointUnavailable)
            },
            clock = DiscoveryMonotonicClock { 0L },
        )
        val application = application(discovery, pipelineFactory) { null }

        val result = application.startHost()

        assertEquals(SecureSessionIntegrationError.DiscoveryStartFailed, result.error)
        assertEquals(SecureSessionIntegrationError.DiscoveryStartFailed, application.snapshot.value.active?.lastError)
        assertEquals(0, pipelineFactory.createCalls)
        assertFalse(backend.advertising)

        application.close()
    }

    @Test
    fun oneHostApplicationStartPublishesOneAdvertisementWhenAvailabilityIsKnown() {
        val lease = RecordingContactLease()
        val backend = RecordingBackend()
        val discovery = DefaultLocalDiscoveryController(
            config = hostDiscoveryConfig(),
            backends = listOf(backend),
            contactEndpointLeaseFactory = { DiscoveryContactEndpointLeaseResult(lease) },
            clock = DiscoveryMonotonicClock { 0L },
            availabilityProvider = HostAvailabilityProvider { DiscoveryAvailability.Available },
        )
        val application = application(discovery, CountingUnusedPipelineFactory()) { current ->
            current.borrowPairingTransport()?.let(::RecordingResponder)
        }

        assertEquals(SecureSessionIntegrationError.None, application.startHost().error)
        assertEquals(1, backend.advertisementStarts)

        application.close()
    }

    @Test
    fun hostLifecycleCallbacksFollowActualReadinessStartAndStop() {
        val lifecycleEvents = mutableListOf<String>()
        val lease = RecordingContactLease()
        val application = application(
            discovery(RecordingBackend(), lease),
            CountingUnusedPipelineFactory(),
            pairingResponderFactory = { current ->
                current.borrowPairingTransport()?.let(::RecordingResponder)
            },
            onHostReadinessStarted = { lifecycleEvents += "started" },
            onHostReadinessStopped = { lifecycleEvents += "stopped" },
        )

        assertEquals(SecureSessionIntegrationError.None, application.startHost().error)
        application.stopHost()

        assertEquals(listOf("started", "stopped"), lifecycleEvents)
        application.close()
    }

    @Test
    fun asynchronousRouteObservationAfterFindHostsPublishesApplicationAndChooserSnapshots() {
        val backend = RecordingBackend()
        val discovery = DefaultLocalDiscoveryController(
            config = DiscoveryConfig(
                mode = DiscoveryMode.BrowseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
                offeredRole = SessionRole.Client,
            ),
            backends = listOf(backend),
            contactEndpointLeaseFactory = { DiscoveryContactEndpointLeaseResult(RecordingContactLease()) },
            clock = DiscoveryMonotonicClock { 0L },
        )
        val driver = clientDriver(discovery)
        val coordinator = SecureSessionCoordinator(
            localRole = SessionRole.Client,
            phaseDriver = driver,
            pipelineFactory = CountingUnusedPipelineFactory(),
            lifecycleFactory = UnusedLifecycleFactory,
        )
        val publishedHostCounts = mutableListOf<Int>()
        val application = SecureSessionApplicationController(
            client = coordinator,
            host = coordinator,
            requestFactory = { null },
            onClientDiscoverySnapshotPublished = publishedHostCounts::add,
        )

        assertEquals(SecureSessionIntegrationError.None, application.startClientDiscovery().error)
        assertEquals(0, discovery.snapshot().candidateCount)
        assertEquals(0, application.snapshot.value.discoveredHostCount)
        assertEquals(0, secureSessionScreenUiModel(application.snapshot.value).hostCount)

        backend.observeHostLanRoute()

        assertEquals(1, discovery.snapshot().candidateCount)
        assertEquals(1, coordinator.snapshot.value.discovery?.candidateCount)
        assertEquals(1, application.snapshot.value.active?.discovery?.candidateCount)
        assertEquals(1, application.snapshot.value.discoveredHostCount)
        assertEquals(listOf(1), publishedHostCounts)
        val chooserModel = secureSessionScreenUiModel(application.snapshot.value)
        assertEquals(1, chooserModel.hostCount)
        assertEquals(1, hostChooserRows(chooserModel.hosts).size)
        assertTrue(chooserModel.chooserVisible)
        application.close()
    }

    @Test
    fun remotePairingRejectionClearsTheClientAttemptAndPublishesTheExistingFailurePath() {
        val backend = RecordingBackend()
        val discovery = DefaultLocalDiscoveryController(
            config = DiscoveryConfig(
                mode = DiscoveryMode.BrowseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
                offeredRole = SessionRole.Client,
            ),
            backends = listOf(backend),
            contactEndpointLeaseFactory = { DiscoveryContactEndpointLeaseResult(RecordingContactLease()) },
            clock = DiscoveryMonotonicClock { 0L },
        )
        val transport = RecordingPairingTransport()
        val crypto = JcaPairingCryptoProvider()
        val signer = JcaSoftwareIdentitySigner.generate(DeviceId.requireValid(0u, 7u), crypto)
        val driver = ControllerBackedClientSessionPhaseDriver(
            discovery = discovery,
            handshakeTransportFactory = ClientSessionHandshakeTransportFactory {
                error("Handshake transport is unused by pairing rejection")
            },
            handshakeFactory = ClientSessionHandshakeControllerFactory { _, _ ->
                error("Handshake controller is unused by pairing rejection")
            },
            pairingTransportFactory = ClientPairingTransportFactory { transport },
            pairingFactory = ClientPairingControllerFactory { pairingTransport, prompt, completed ->
                PairingController(
                    localSigner = signer,
                    trustedPeerStore = TrustedPeerStore(InMemoryTrustedPeerStorePersistence(), crypto::sha256),
                    transport = pairingTransport,
                    crypto = crypto,
                    eventListener = prompt,
                    completedListener = completed,
                )
            },
            protection = SessionProtectionController(
                SessionProtectionRuntimeFactory { _, _, _ ->
                    SessionProtectionCreationResult(SessionProtectionError.CryptoFailure)
                },
            ),
            secureControlFactory = SecureSessionControlTransportFactory {
                error("Secure control is unused by pairing rejection")
            },
            capabilityFactory = ClientCapabilityNegotiationControllerFactory {
                error("Capability negotiation is unused by pairing rejection")
            },
            setupFactory = ClientSessionSetupControllerFactory {
                error("Session setup is unused by pairing rejection")
            },
            setupRuntimeFactory = ClientSessionSetupRuntimeFactory { _, _ ->
                error("Session runtime is unused by pairing rejection")
            },
        )
        val failures = mutableListOf<Pair<SecureSessionIntegrationStage, SecureSessionIntegrationError>>()
        val listener = object : SecureSessionPhaseListener {
            override fun onPairingRequired() = Unit
            override fun onPairingCompleted() = Unit
            override fun onAuthenticated(bootstrap: io.warpnect.session.handshake.AuthenticatedSessionBootstrap) = Unit
            override fun onCapabilitiesNegotiated(
                bootstrap: io.warpnect.session.capability.NegotiatedSessionBootstrap,
            ) = Unit
            override fun onPrepared(bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap) = Unit
            override fun onFailed(stage: SecureSessionIntegrationStage, error: SecureSessionIntegrationError) {
                failures += stage to error
            }
        }

        assertEquals(SecureSessionIntegrationError.None, driver.startDiscovery(SessionRole.Client))
        backend.observeHostLanRoute()
        assertEquals(SecureSessionIntegrationError.None, driver.beginExplicitPairing(clientRequest(), listener))

        val attemptId = transport.firstAttemptId()
        transport.deliver(
            PairingTransportEndpoint("192.0.2.1", 45_000),
            PairingBootstrapCodec.encode(
                PairingBootstrapPacket(attemptId, PairingBootstrapMessage.Reject(PairingRejectReason.UserRejected)),
            ),
        )
        driver.advance()

        assertEquals(
            listOf(SecureSessionIntegrationStage.Pairing to SecureSessionIntegrationError.PairingFailed),
            failures,
        )
        assertEquals(SecureSessionIntegrationError.PairingRequired, driver.rejectPairing())
        driver.close()
    }

    private fun clientRequest() = SecureSessionConnectRequest(
        DiscoveredPresence(
            DiscoveryPresenceId.requireValid(0uL, 2uL),
            null,
            SessionRole.Host,
            DiscoveryAvailability.Available,
            1,
            0,
            0,
            listOf(DiscoveryRouteKind.Lan),
            io.warpnect.session.discovery.DiscoveryPresenceStatus.Usable,
        ),
        CapabilityRequest(
            CapabilityBits.CHANNEL_VIDEO,
            0,
            CapabilityBits.OPTIONAL_CHANNEL_MASK xor CapabilityBits.CHANNEL_VIDEO,
            0,
            0,
            MicrophoneRoutingSelection.NotApplicable,
            MicrophoneRoutingSelection.NotApplicable,
            0,
            0,
            FeatureRequirement.Disabled,
            FeatureRequirement.Disabled,
            0,
        ),
        SessionSetupPreferences(),
    )

    private fun clientDriver(discovery: DefaultLocalDiscoveryController): ControllerBackedClientSessionPhaseDriver =
        ControllerBackedClientSessionPhaseDriver(
            discovery = discovery,
            handshakeTransportFactory = ClientSessionHandshakeTransportFactory {
                error("Handshake transport is unused by discovery startup")
            },
            handshakeFactory = ClientSessionHandshakeControllerFactory { _, _ ->
                error("Handshake controller is unused by discovery startup")
            },
            pairingTransportFactory = ClientPairingTransportFactory {
                error("Pairing transport is unused by discovery startup")
            },
            pairingFactory = ClientPairingControllerFactory { _, _, _ ->
                error("Pairing controller is unused by discovery startup")
            },
            protection = SessionProtectionController(
                SessionProtectionRuntimeFactory { _, _, _ ->
                    SessionProtectionCreationResult(SessionProtectionError.CryptoFailure)
                },
            ),
            secureControlFactory = SecureSessionControlTransportFactory {
                error("Secure control is unused by discovery startup")
            },
            capabilityFactory = ClientCapabilityNegotiationControllerFactory {
                error("Capability negotiation is unused by discovery startup")
            },
            setupFactory = ClientSessionSetupControllerFactory {
                error("Session setup is unused by discovery startup")
            },
            setupRuntimeFactory = ClientSessionSetupRuntimeFactory { _, _ ->
                error("Session runtime is unused by discovery startup")
            },
        )

    private fun application(
        discovery: DefaultLocalDiscoveryController,
        pipelineFactory: CountingUnusedPipelineFactory,
        onHostReadinessStarted: () -> Unit = {},
        onHostReadinessStopped: () -> Unit = {},
        pairingResponderFactory: (DefaultLocalDiscoveryController) -> HostPairingResponder? = { null },
    ): SecureSessionApplicationController {
        val crypto = JcaPairingCryptoProvider()
        val signer = JcaSoftwareIdentitySigner.generate(DeviceId.requireValid(0u, 1u), crypto)
        val trustedPeers = TrustedPeerStore(InMemoryTrustedPeerStorePersistence(), crypto::sha256)
        val sessionManager = SessionManager(
            SessionManagerConfig(
                localDeviceId = signer.identity.deviceId,
                initialPolicy = SessionBehaviorPolicy(maxConcurrentClients = 8),
            ),
        )
        val hostDriver = ControllerBackedHostSessionPhaseDriver(
            discovery = discovery,
            handshakeFactory = HostSessionHandshakeControllerFactory { listener ->
                SessionHandshakeController(
                    transport = requireNotNull(discovery.borrowSessionHandshakeTransport()),
                    localSigner = signer,
                    trustedPeers = trustedPeers,
                    sessionManager = sessionManager,
                    crypto = crypto,
                    eventListener = listener,
                )
            },
            protection = SessionProtectionController(
                SessionProtectionRuntimeFactory { _, _, _ ->
                    SessionProtectionCreationResult(SessionProtectionError.CryptoFailure)
                },
            ),
            secureControlFactory = HostSecureSessionControlTransportFactory { null },
            capabilityFactory = HostCapabilityNegotiationControllerFactory { completed ->
                CapabilityNegotiationController(
                    collector = LocalCapabilityCollector {
                        error(
                            "No capability negotiation should start during Host readiness",
                        )
                    },
                    clock = CapabilityNegotiationMonotonicClock { 0L },
                    onCompleted = completed,
                )
            },
            setupFactory = HostSessionSetupControllerFactory { completed ->
                SessionSetupController(
                    clock = SessionSetupMonotonicClock { 0L },
                    onCompleted = completed,
                )
            },
            setupRuntimeFactory = HostSessionSetupRuntimeFactory { null },
            capabilityPolicy = HostCapabilityPolicy(),
            setupPolicy = HostSessionSetupPolicy(SessionSetupPreferences()),
            onPrepared = {},
            pairingResponderFactory = HostPairingResponderFactory { pairingResponderFactory(discovery) },
            onHostReadinessStarted = onHostReadinessStarted,
            onHostReadinessStopped = onHostReadinessStopped,
        )
        val coordinator = SecureSessionCoordinator(
            localRole = SessionRole.Host,
            phaseDriver = HostSessionApplicationPhaseDriver(hostDriver),
            pipelineFactory = pipelineFactory,
            lifecycleFactory = UnusedLifecycleFactory,
        )
        return SecureSessionApplicationController(
            client = coordinator,
            host = coordinator,
            requestFactory = { null },
        )
    }

    private fun discovery(backend: RecordingBackend, lease: RecordingContactLease): DefaultLocalDiscoveryController =
        DefaultLocalDiscoveryController(
            config = hostDiscoveryConfig(),
            backends = listOf(backend),
            contactEndpointLeaseFactory = { DiscoveryContactEndpointLeaseResult(lease) },
            clock = DiscoveryMonotonicClock { 0L },
        )

    private fun hostDiscoveryConfig(): DiscoveryConfig = DiscoveryConfig(
        mode = DiscoveryMode.AdvertiseOnly,
        backendPolicy = DiscoveryBackendPolicy.LanOnly,
    )

    private class CountingUnusedPipelineFactory : SessionPipelineFactory {
        var createCalls = 0

        override fun create(
            bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap,
        ): SessionPipelineFactoryResult {
            createCalls += 1
            return SessionPipelineFactoryResult(SecureSessionIntegrationError.Failed)
        }
    }

    private object UnusedLifecycleFactory : SessionLifecycleSessionFactory {
        override fun create(
            bootstrap: io.warpnect.session.setup.PreparedSessionBootstrap,
            pipeline: SessionPipelineRuntime,
            listener: SessionLifecycleRuntimeListener,
        ): SessionLifecycleFactoryResult = SessionLifecycleFactoryResult(SecureSessionIntegrationError.Failed)
    }

    private class RecordingBackend : DiscoveryBackend {
        override val kind = DiscoveryRouteKind.Lan
        var advertisementStarts = 0
        var advertising = false
        var browsingStarts = 0
        var browsing = false
        private var observer: DiscoveryBackendObserver? = null
        private var browsingGeneration: Long? = null

        override fun prepare(observer: DiscoveryBackendObserver): DiscoveryBackendCommandResult =
            DiscoveryBackendCommandResult.Accepted.also { this.observer = observer }

        override fun startAdvertising(request: DiscoveryBackendAdvertisingRequest): DiscoveryBackendCommandResult {
            advertisementStarts += 1
            advertising = true
            return DiscoveryBackendCommandResult.Accepted
        }

        override fun stopAdvertising(controllerGeneration: Long, advertisementGeneration: Long?) {
            advertising = false
        }

        override fun startBrowsing(controllerGeneration: Long): DiscoveryBackendCommandResult {
            browsingStarts += 1
            browsing = true
            browsingGeneration = controllerGeneration
            return DiscoveryBackendCommandResult.Accepted
        }
        override fun stopBrowsing(controllerGeneration: Long) {
            browsing = false
            browsingGeneration = null
        }

        fun observeHostLanRoute() {
            observer?.onRouteObserved(
                requireNotNull(browsingGeneration),
                DiscoveryRouteObservation(
                    backendRouteKey = "test-route",
                    kind = DiscoveryRouteKind.Lan,
                    advertisement = DiscoveryAdvertisement(
                        presenceId = DiscoveryPresenceId.requireValid(0uL, 2uL),
                        offeredRole = SessionRole.Host,
                        availability = DiscoveryAvailability.Available,
                    ),
                    descriptor = DiscoveryRouteDescriptor.Lan(
                        addressCandidates = listOf(DiscoveryAddressCandidate("192.0.2.1")),
                        port = 45_000,
                    ),
                ),
            )
        }

        override fun close() = Unit
    }

    private class RecordingContactLease :
        PairingBootstrapContactEndpointLease,
        SessionHandshakeBootstrapContactEndpointLease {
        val pairingTransport = RecordingPairingTransport()
        private val handshakeTransport = RecordingHandshakeTransport()
        var pairingBorrows = 0
        var borrowedResponder: RecordingResponder? = null

        override val port: Int = 45_000

        override fun borrowPairingTransport(): PairingTransport {
            pairingBorrows += 1
            return pairingTransport
        }

        override fun borrowSessionHandshakeTransport(): SessionHandshakeTransport = handshakeTransport
        override fun close() = Unit
    }

    private class RecordingResponder(
        val transport: PairingTransport,
    ) : HostPairingResponder {
        var started = false

        override fun start(): SecureSessionIntegrationError {
            started = true
            return SecureSessionIntegrationError.None
        }

        override fun approvePairing(): SecureSessionIntegrationError = SecureSessionIntegrationError.PairingRequired
        override fun pendingPrompt() = null
        override fun close() = Unit
    }

    private class RecordingPairingTransport : PairingTransport {
        private var listener: ((PairingTransportEndpoint, ByteArray) -> Unit)? = null
        private val sent = mutableListOf<ByteArray>()

        override fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult {
            sent += datagram.copyOf()
            return PairingTransportSendResult.Sent
        }

        fun firstAttemptId() = requireNotNull(PairingBootstrapCodec.decode(sent.first()).packet).attemptId

        fun deliver(endpoint: PairingTransportEndpoint, datagram: ByteArray) {
            listener?.invoke(endpoint, datagram.copyOf())
        }

        override fun close() = Unit
    }

    private class RecordingHandshakeTransport : SessionHandshakeTransport {
        override fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) = Unit
        override fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean = true
        override fun close() = Unit
    }
}
