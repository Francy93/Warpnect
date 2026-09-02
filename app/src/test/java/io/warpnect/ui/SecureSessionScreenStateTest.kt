package io.warpnect.ui

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.capability.FeatureRequirement
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.capability.SecureSessionCapabilityBootstrap
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoveryAvailability
import io.warpnect.session.discovery.DiscoveryDisplayAlias
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryPresenceStatus
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.identity.IdentityFingerprint
import io.warpnect.session.integration.SecurePhaseResult
import io.warpnect.session.integration.SecureSessionApplicationController
import io.warpnect.session.integration.SecureSessionConnectRequest
import io.warpnect.session.integration.SecureSessionCoordinator
import io.warpnect.session.integration.SecureSessionCoordinatorState
import io.warpnect.session.integration.SecureSessionIntegrationError
import io.warpnect.session.integration.SecureSessionPhaseDriver
import io.warpnect.session.integration.SecureSessionPhaseListener
import io.warpnect.session.integration.SessionControlDispatcher
import io.warpnect.session.integration.SessionLifecycleFactoryResult
import io.warpnect.session.integration.SessionLifecycleSessionFactory
import io.warpnect.session.integration.SessionPipelineFactory
import io.warpnect.session.integration.SessionPipelineFactoryResult
import io.warpnect.session.lifecycle.RecoverableSessionRecord
import io.warpnect.session.pairing.PairingAttemptId
import io.warpnect.session.pairing.PairingEngineState
import io.warpnect.session.pairing.PairingTransport
import io.warpnect.session.pairing.PairingTransportEndpoint
import io.warpnect.session.pairing.PairingTransportSendResult
import io.warpnect.session.pairing.PairingVerificationPrompt
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionSetupPreferences
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSessionScreenStateTest {
    @Test
    fun rendererIsAbsentForReadyAndSearchingAndAppearsOnlyForAnActualReceiverPipeline() {
        assertFalse(shouldComposeClientVideoSurface(null, clientVideoRendererBound = false))
        assertFalse(shouldComposeClientVideoSurface(SessionRole.Client, clientVideoRendererBound = false))
        assertTrue(shouldComposeClientVideoSurface(SessionRole.Client, clientVideoRendererBound = true))
        assertFalse(shouldComposeClientVideoSurface(SessionRole.Host, clientVideoRendererBound = true))
    }

    @Test
    fun clientDiscoveryCancelUsesTheApplicationPathStopsBrowsingAndReturnsToReady() {
        val clientDriver = DiscoveryOnlyPhaseDriver()
        val application = SecureSessionApplicationController(
            client = coordinator(SessionRole.Client, clientDriver),
            host = coordinator(SessionRole.Host),
            requestFactory = { null },
        )

        assertEquals(SecureSessionIntegrationError.None, application.startClientDiscovery().error)
        assertEquals(SessionRole.Client, application.snapshot.value.activeRole)
        assertEquals(SecureSessionCoordinatorState.Discovering, application.snapshot.value.active?.state)
        assertTrue(application.discoveredHosts().isEmpty())
        assertFalse(
            shouldComposeClientVideoSurface(application.snapshot.value.activeRole, clientVideoRendererBound = false),
        )

        assertTrue(
            shouldCancelClientDiscovery(
                application.snapshot.value.activeRole,
                application.snapshot.value.active?.state,
            ),
        )
        assertEquals(
            SecureSessionIntegrationError.None,
            application.cancelClientDiscovery().error,
        )

        assertNull(application.snapshot.value.activeRole)
        assertEquals(SecureSessionCoordinatorState.Idle, application.snapshot.value.client.state)
        assertEquals(1, clientDriver.stopDiscoveryCalls)
        assertFalse(
            shouldCancelClientDiscovery(
                application.snapshot.value.activeRole,
                application.snapshot.value.active?.state,
            ),
        )

        assertEquals(
            SecureSessionIntegrationError.None,
            application.startClientDiscovery().error,
        )
        assertEquals(2, clientDriver.startDiscoveryCalls)
        application.close()
    }

    @Test
    fun discoveryPresentationDistinguishesHostWaitingFromClientSearching() {
        assertEquals(
            "Waiting for clients",
            sessionStatusText(
                SessionRole.Host,
                SecureSessionCoordinatorState.Discovering,
                SecureSessionIntegrationError.None,
            ),
        )
        assertEquals(
            "Searching for hosts",
            sessionStatusText(
                SessionRole.Client,
                SecureSessionCoordinatorState.Discovering,
                SecureSessionIntegrationError.None,
            ),
        )
        assertEquals(
            "Discovery unavailable",
            sessionStatusText(
                SessionRole.Client,
                SecureSessionCoordinatorState.Failed,
                SecureSessionIntegrationError.DiscoveryStartFailed,
            ),
        )
    }

    @Test
    fun clientShowsStreamingOnlyAfterTheRendererPresentsAFrame() {
        assertEquals(
            "Running",
            sessionStatusText(
                activeRole = SessionRole.Client,
                state = SecureSessionCoordinatorState.Running,
                error = SecureSessionIntegrationError.None,
                clientVideoStreaming = false,
            ),
        )
        assertEquals(
            "Streaming",
            sessionStatusText(
                activeRole = SessionRole.Client,
                state = SecureSessionCoordinatorState.Running,
                error = SecureSessionIntegrationError.None,
                clientVideoStreaming = true,
            ),
        )
        assertEquals(
            "Running",
            sessionStatusText(
                activeRole = SessionRole.Host,
                state = SecureSessionCoordinatorState.Running,
                error = SecureSessionIntegrationError.None,
                clientVideoStreaming = true,
            ),
        )
    }

    @Test
    fun connectFromUiContextRunsHandshakeTransportOnSerializedControlThreadOnceAndPublishesConnecting() {
        val transport = ThreadRecordingHandshakeTransport()
        val dispatcher = QueuedControlDispatcher()
        val clientDriver = DiscoveryOnlyPhaseDriver(transport)
        val application = SecureSessionApplicationController(
            client = coordinator(SessionRole.Client, clientDriver),
            host = coordinator(SessionRole.Host),
            requestFactory = ::connectRequest,
            controlDispatcher = dispatcher,
        )
        val uiThread = Thread.currentThread()

        assertEquals(SecureSessionIntegrationError.None, application.startClientDiscovery().error)
        assertEquals(SecureSessionIntegrationError.None, application.connect(discoveredHost()).error)
        assertEquals(SecureSessionIntegrationError.Busy, application.connect(discoveredHost()).error)
        assertEquals(1, dispatcher.pendingCount)
        assertEquals(0, transport.sendCount)

        dispatcher.runAllOnControlThread()

        assertEquals(1, clientDriver.connectionStarts)
        assertEquals(1, transport.sendCount)
        assertFalse(transport.sendThread.get() === uiThread)
        assertEquals(
            SecureSessionCoordinatorState.Connecting,
            application.snapshot.value.active?.state,
        )
        application.close()
    }

    @Test
    fun pairingStartFromUiContextRunsPairingTransportOnSerializedControlThreadOnlyOnce() {
        val dispatcher = QueuedControlDispatcher()
        val pairingTransport = ThreadRecordingPairingTransport()
        val clientDriver = DiscoveryOnlyPhaseDriver(
            pairingTransport = pairingTransport,
            pairingRequiredOnConnection = true,
        )
        val application = SecureSessionApplicationController(
            client = coordinator(SessionRole.Client, clientDriver),
            host = coordinator(SessionRole.Host),
            requestFactory = ::connectRequest,
            controlDispatcher = dispatcher,
        )
        val uiThread = Thread.currentThread()

        assertEquals(SecureSessionIntegrationError.None, application.startClientDiscovery().error)
        assertEquals(SecureSessionIntegrationError.None, application.connect(discoveredHost()).error)
        dispatcher.runAllOnControlThread()
        assertEquals(SecureSessionCoordinatorState.PairingRequired, application.snapshot.value.active?.state)

        assertEquals(SecureSessionIntegrationError.None, application.beginExplicitPairing().error)
        assertEquals(SecureSessionIntegrationError.Busy, application.beginExplicitPairing().error)
        assertEquals(1, dispatcher.pendingCount)
        assertEquals(0, pairingTransport.sendCount)

        dispatcher.runAllOnControlThread()

        assertEquals(1, clientDriver.pairingStarts)
        assertEquals(1, pairingTransport.sendCount)
        assertFalse(pairingTransport.sendThread.get() === uiThread)
        assertEquals(SecureSessionCoordinatorState.Pairing, application.snapshot.value.active?.state)
        assertEquals("123 456", application.snapshot.value.active?.pairingVerificationPrompt?.shortAuthenticationString)
        application.close()
    }

    @Test
    fun connectAutomaticallyStartsRequiredSecurePairingAndKeepsSasDecisionExplicit() {
        val dispatcher = QueuedControlDispatcher()
        val pairingTransport = ThreadRecordingPairingTransport()
        val clientDriver = DiscoveryOnlyPhaseDriver(
            pairingTransport = pairingTransport,
            pairingRequiredOnConnection = true,
        )
        val application = SecureSessionApplicationController(
            client = coordinator(SessionRole.Client, clientDriver),
            host = coordinator(SessionRole.Host),
            requestFactory = ::connectRequest,
            controlDispatcher = dispatcher,
        )
        val uiThread = Thread.currentThread()

        assertEquals(SecureSessionIntegrationError.None, application.startClientDiscovery().error)
        assertEquals(SecureSessionIntegrationError.None, application.connect(discoveredHost()).error)
        dispatcher.runAllOnControlThread()
        assertEquals(SecureSessionCoordinatorState.PairingRequired, application.snapshot.value.active?.state)
        assertEquals(0, clientDriver.pairingStarts)

        dispatcher.runOnControlThread(application::advance)

        assertEquals(1, clientDriver.pairingStarts)
        assertEquals(1, pairingTransport.sendCount)
        assertFalse(pairingTransport.sendThread.get() === uiThread)
        assertEquals(SecureSessionCoordinatorState.Pairing, application.snapshot.value.active?.state)
        assertEquals("123 456", application.snapshot.value.active?.pairingVerificationPrompt?.shortAuthenticationString)
        assertEquals(0, clientDriver.pairingApprovals)
        assertEquals(0, clientDriver.inputControllerCount)

        assertEquals(SecureSessionIntegrationError.None, application.approvePairing().error)
        assertEquals(SecureSessionIntegrationError.Busy, application.approvePairing().error)
        assertEquals(0, clientDriver.pairingApprovals)
        dispatcher.runAllOnControlThread()

        assertEquals(1, clientDriver.pairingApprovals)
        assertEquals(2, pairingTransport.sendCount)
        assertFalse(pairingTransport.sendThread.get() === uiThread)
        application.close()
    }

    @Test
    fun securePeerVerificationRejectIsAnExplicitSerializedPairingDecision() {
        val dispatcher = QueuedControlDispatcher()
        val pairingTransport = ThreadRecordingPairingTransport()
        val clientDriver = DiscoveryOnlyPhaseDriver(
            pairingTransport = pairingTransport,
            pairingRequiredOnConnection = true,
        )
        val application = SecureSessionApplicationController(
            client = coordinator(SessionRole.Client, clientDriver),
            host = coordinator(SessionRole.Host),
            requestFactory = ::connectRequest,
            controlDispatcher = dispatcher,
        )
        val uiThread = Thread.currentThread()

        assertEquals(SecureSessionIntegrationError.None, application.startClientDiscovery().error)
        assertEquals(SecureSessionIntegrationError.None, application.connect(discoveredHost()).error)
        dispatcher.runAllOnControlThread()
        dispatcher.runOnControlThread(application::advance)
        assertEquals(SecureSessionCoordinatorState.Pairing, application.snapshot.value.active?.state)

        assertEquals(SecureSessionIntegrationError.None, application.rejectPairing().error)
        assertEquals(SecureSessionIntegrationError.Busy, application.rejectPairing().error)
        assertEquals(0, clientDriver.pairingRejections)
        dispatcher.runAllOnControlThread()

        assertEquals(1, clientDriver.pairingRejections)
        assertEquals(2, pairingTransport.sendCount)
        assertFalse(pairingTransport.sendThread.get() === uiThread)
        assertEquals(SecureSessionCoordinatorState.Failed, application.snapshot.value.active?.state)
        application.close()
    }

    private fun coordinator(
        role: SessionRole,
        driver: DiscoveryOnlyPhaseDriver = DiscoveryOnlyPhaseDriver(),
    ): SecureSessionCoordinator = SecureSessionCoordinator(
        localRole = role,
        phaseDriver = driver,
        pipelineFactory = UnusedPipelineFactory,
        lifecycleFactory = UnusedLifecycleFactory,
    )

    private class DiscoveryOnlyPhaseDriver(
        private val handshakeTransport: SessionHandshakeTransport? = null,
        private val pairingTransport: PairingTransport? = null,
        private val pairingRequiredOnConnection: Boolean = false,
    ) : SecureSessionPhaseDriver {
        var startDiscoveryCalls = 0
        var stopDiscoveryCalls = 0
        var connectionStarts = 0
        var pairingStarts = 0
        var pairingApprovals = 0
        var pairingRejections = 0
        val inputControllerCount = 0

        override fun startDiscovery(role: SessionRole): SecureSessionIntegrationError =
            SecureSessionIntegrationError.None.also { startDiscoveryCalls += 1 }
        override fun stopDiscovery() {
            stopDiscoveryCalls += 1
        }
        override fun beginConnection(
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError {
            connectionStarts += 1
            handshakeTransport?.send(
                HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 45_000),
                byteArrayOf(1),
            )
            if (pairingRequiredOnConnection) listener.onPairingRequired()
            return SecureSessionIntegrationError.None
        }

        override fun beginExplicitPairing(
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError {
            pairingStarts += 1
            pairingTransport?.send(
                PairingTransportEndpoint("127.0.0.1", 45_001),
                byteArrayOf(2),
            )
            listener.onPairingVerificationPrompt(testPairingPrompt())
            return SecureSessionIntegrationError.None
        }

        override fun approvePairing(): SecureSessionIntegrationError {
            pairingApprovals += 1
            pairingTransport?.send(
                PairingTransportEndpoint("127.0.0.1", 45_001),
                byteArrayOf(3),
            )
            return SecureSessionIntegrationError.None
        }

        override fun rejectPairing(): SecureSessionIntegrationError {
            pairingRejections += 1
            pairingTransport?.send(
                PairingTransportEndpoint("127.0.0.1", 45_001),
                byteArrayOf(4),
            )
            return SecureSessionIntegrationError.PairingFailed
        }

        override fun createSecureCapabilityBootstrap(bootstrap: AuthenticatedSessionBootstrap): SecurePhaseResult =
            SecurePhaseResult(SecureSessionIntegrationError.InvalidPresence)

        override fun beginCapabilities(
            bootstrap: SecureSessionCapabilityBootstrap,
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

        override fun beginSetup(
            bootstrap: NegotiatedSessionBootstrap,
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

        override fun beginReconnect(
            record: RecoverableSessionRecord,
            nextGeneration: io.warpnect.session.SessionGeneration,
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError = SecureSessionIntegrationError.InvalidPresence

        override fun cancel() = Unit
        override fun refreshHostAvailability() = Unit
        override fun close() = Unit
    }

    private object UnusedPipelineFactory : SessionPipelineFactory {
        override fun create(bootstrap: PreparedSessionBootstrap): SessionPipelineFactoryResult =
            SessionPipelineFactoryResult(SecureSessionIntegrationError.Failed)
    }

    private object UnusedLifecycleFactory : SessionLifecycleSessionFactory {
        override fun create(
            bootstrap: PreparedSessionBootstrap,
            pipeline: io.warpnect.session.integration.SessionPipelineRuntime,
            listener: io.warpnect.session.integration.SessionLifecycleRuntimeListener,
        ): SessionLifecycleFactoryResult = SessionLifecycleFactoryResult(SecureSessionIntegrationError.Failed)
    }

    private fun discoveredHost() = DiscoveredPresence(
        presenceId = DiscoveryPresenceId.requireValid(0uL, 1uL),
        displayAlias = DiscoveryDisplayAlias.requireValid("Host"),
        offeredRole = SessionRole.Host,
        availability = DiscoveryAvailability.Available,
        discoverySchemaVersion = 1,
        firstSeenMonotonicMs = 0L,
        lastSeenMonotonicMs = 0L,
        availablePathKinds = listOf(DiscoveryRouteKind.Lan),
        status = DiscoveryPresenceStatus.Usable,
    )

    private fun connectRequest(presence: DiscoveredPresence) = SecureSessionConnectRequest(
        presence = presence,
        capabilityRequest = CapabilityRequest(
            requiredChannels = CapabilityBits.CHANNEL_VIDEO,
            preferredChannels = 0,
            disabledChannels = CapabilityBits.OPTIONAL_CHANNEL_MASK xor CapabilityBits.CHANNEL_VIDEO,
            requiredInputKinds = 0,
            preferredInputKinds = 0,
            microphonePolicyPrimary = MicrophoneRoutingSelection.NotApplicable,
            microphonePolicyFallback = MicrophoneRoutingSelection.NotApplicable,
            stablePresenceRequiredKinds = 0,
            stablePresencePreferredKinds = 0,
            videoLowLatencyRequirement = FeatureRequirement.Disabled,
            distinctGamepadIdentityRequirement = FeatureRequirement.Disabled,
            requiredRecoveryFlags = 0,
        ),
        setupPreferences = SessionSetupPreferences(),
    )

    private class QueuedControlDispatcher : SessionControlDispatcher {
        private val pending = ArrayDeque<() -> Unit>()

        val pendingCount: Int get() = pending.size

        override fun dispatch(action: () -> Unit): Boolean {
            pending.addLast(action)
            return true
        }

        fun runAllOnControlThread() {
            runOnControlThread {
                while (pending.isNotEmpty()) pending.removeFirst().invoke()
            }
        }

        fun runOnControlThread(action: () -> Unit) {
            val worker = Thread(action)
            worker.start()
            worker.join()
        }
    }

    private class ThreadRecordingHandshakeTransport : SessionHandshakeTransport {
        var sendCount = 0
        val sendThread = AtomicReference<Thread?>()

        override fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) = Unit

        override fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean {
            sendCount += 1
            sendThread.set(Thread.currentThread())
            return true
        }

        override fun close() = Unit
    }

    private class ThreadRecordingPairingTransport : PairingTransport {
        var sendCount = 0
        val sendThread = AtomicReference<Thread?>()

        override fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) = Unit

        override fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult {
            sendCount += 1
            sendThread.set(Thread.currentThread())
            return PairingTransportSendResult.Sent
        }

        override fun close() = Unit
    }
}

private fun testPairingPrompt() = PairingVerificationPrompt(
    attemptId = PairingAttemptId.requireValid(0uL, 1uL),
    remoteUntrustedAlias = null,
    remoteDeviceId = DeviceId.requireValid(0uL, 2uL),
    remoteIdentityFingerprint = IdentityFingerprint.requireSha256(ByteArray(32) { 1 }),
    shortAuthenticationString = "123 456",
    state = PairingEngineState.AwaitingUserConfirmation,
    expiresAtMonotonicMs = 1_000L,
)
