@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.platform.session.integration

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.warpnect.CoreOrchestrator
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.diagnostics.DiagnosticEventHub
import io.warpnect.diagnostics.NativeDiagnosticEventSnapshotProvider
import io.warpnect.diagnostics.SessionLifecycleDiagnosticEvents
import io.warpnect.diagnostics.report.DiagnosticReportBuilder
import io.warpnect.diagnostics.report.HubDiagnosticReportReader
import io.warpnect.diagnostics.report.ReportExportController
import io.warpnect.platform.audio.capture.AndroidMicrophoneAudioCaptureController
import io.warpnect.platform.audio.capture.AndroidSystemAudioCaptureController
import io.warpnect.platform.audio.encoder.NativeOpusAudioEncoderController
import io.warpnect.platform.diagnostics.AndroidDiagnosticEventClock
import io.warpnect.platform.diagnostics.AndroidReportSupport
import io.warpnect.platform.discovery.AndroidDiscoveryDebugLog
import io.warpnect.platform.discovery.AndroidLocalDiscoveryController
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import io.warpnect.platform.input.injection.AndroidInputInjectionController
import io.warpnect.platform.session.capability.AndroidCapabilityProbe
import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot
import io.warpnect.platform.session.capability.AndroidLocalCapabilityCollector
import io.warpnect.platform.session.channel.AndroidChannelEndpointAllocator
import io.warpnect.platform.session.channel.NativePreparedChannelTransportPreparer
import io.warpnect.platform.session.control.AndroidSecureSessionControlTransport
import io.warpnect.platform.session.control.SecureSessionControlDatagramIo
import io.warpnect.platform.session.handshake.AndroidDatagramSessionHandshakeTransport
import io.warpnect.platform.session.identity.AndroidSessionIdentityFactory
import io.warpnect.platform.session.lifecycle.AndroidLifecycleCandidateDatagramIo
import io.warpnect.platform.session.lifecycle.AndroidNetworkPathMonitor
import io.warpnect.platform.session.lifecycle.PreparedSessionMigrationAdapter
import io.warpnect.platform.session.pairing.AndroidDatagramPairingTransport
import io.warpnect.platform.session.pairing.AndroidPairingController
import io.warpnect.platform.session.pairing.AndroidPairingControllerFactory
import io.warpnect.platform.session.path.AndroidDirectPathBackend
import io.warpnect.platform.session.path.AndroidSessionControlPathRebinder
import io.warpnect.platform.session.path.DirectPeerAddressResolver
import io.warpnect.platform.session.security.NativeSessionProtectionRuntimeFactory
import io.warpnect.platform.session.setup.AndroidExactStreamConfigurationValidator
import io.warpnect.platform.telemetry.AndroidTelemetryClock
import io.warpnect.platform.video.decoder.AndroidVideoDecoderDiscovery
import io.warpnect.platform.video.encoder.AndroidVideoEncoderDiscovery
import io.warpnect.platform.video.encoder.VideoEncoderCbrCapabilityDebugObserver
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionBehaviorPolicy
import io.warpnect.session.SessionManager
import io.warpnect.session.SessionManagerConfig
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.CapabilityNegotiationController
import io.warpnect.session.capability.CapabilityNegotiationDebugObserver
import io.warpnect.session.capability.CapabilityNegotiationMonotonicClock
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.capability.FeatureRequirement
import io.warpnect.session.capability.HostCapabilityPolicy
import io.warpnect.session.capability.LocalCapabilityAvailability
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.discovery.DiscoveryBackendPolicy
import io.warpnect.session.discovery.DiscoveryConfig
import io.warpnect.session.discovery.DiscoveryMode
import io.warpnect.session.discovery.DiscoveryOpaqueRouteLocator
import io.warpnect.session.discovery.DiscoveryRouteDescriptor
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.SessionManagerHostAvailabilityProvider
import io.warpnect.session.handshake.CurrentDiscoveryPresenceProvider
import io.warpnect.session.handshake.SessionHandshakeController
import io.warpnect.session.handshake.SessionHandshakeDebugObserver
import io.warpnect.session.identity.LocalDeviceIdentityResult
import io.warpnect.session.integration.ClientCapabilityNegotiationControllerFactory
import io.warpnect.session.integration.ClientPairingControllerFactory
import io.warpnect.session.integration.ClientPairingTransportFactory
import io.warpnect.session.integration.ClientSessionHandshakeControllerFactory
import io.warpnect.session.integration.ClientSessionHandshakeTransportFactory
import io.warpnect.session.integration.ClientSessionSetupControllerFactory
import io.warpnect.session.integration.ClientSessionSetupRuntimeFactory
import io.warpnect.session.integration.ControllerBackedClientSessionPhaseDriver
import io.warpnect.session.integration.ControllerBackedHostSessionPhaseDriver
import io.warpnect.session.integration.ControllerManagedLifecycleSessionFactory
import io.warpnect.session.integration.HostCapabilityNegotiationControllerFactory
import io.warpnect.session.integration.HostPairingResponder
import io.warpnect.session.integration.HostPairingResponderFactory
import io.warpnect.session.integration.HostSecureSessionControlTransportFactory
import io.warpnect.session.integration.HostSessionApplicationPhaseDriver
import io.warpnect.session.integration.HostSessionHandshakeControllerFactory
import io.warpnect.session.integration.HostSessionRuntimeRegistry
import io.warpnect.session.integration.HostSessionSetupControllerFactory
import io.warpnect.session.integration.HostSessionSetupRuntimeFactory
import io.warpnect.session.integration.SecureSessionApplicationController
import io.warpnect.session.integration.SecureSessionConnectRequest
import io.warpnect.session.integration.SecureSessionConnectRequestFactory
import io.warpnect.session.integration.SecureSessionCoordinator
import io.warpnect.session.integration.SessionLifecycleControllerProvider
import io.warpnect.session.integration.SessionPipelineRuntime
import io.warpnect.session.integration.SessionStartupDebugObserver
import io.warpnect.session.lifecycle.LifecyclePathBinding
import io.warpnect.session.lifecycle.SessionContinuityParticipant
import io.warpnect.session.lifecycle.SessionLifecycleController
import io.warpnect.session.lifecycle.SessionLifecycleDebugObserver
import io.warpnect.session.lifecycle.SessionLifecycleError
import io.warpnect.session.lifecycle.SessionLifecyclePathProvider
import io.warpnect.session.lifecycle.SessionLifecycleReconnectDelegate
import io.warpnect.session.lifecycle.SessionManagerLifecycleCapacityOwner
import io.warpnect.session.lifecycle.SessionRecoveryIntent
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.pairing.PairingCompletedListener
import io.warpnect.session.pairing.PairingController
import io.warpnect.session.pairing.PairingDebugObserver
import io.warpnect.session.pairing.PairingError
import io.warpnect.session.pairing.PairingEventListener
import io.warpnect.session.pairing.PairingVerificationPrompt
import io.warpnect.session.security.SessionProtectionController
import io.warpnect.session.setup.AudioStreamMode
import io.warpnect.session.setup.AudioStreamPreference
import io.warpnect.session.setup.HostSessionSetupPolicy
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionSetupController
import io.warpnect.session.setup.SessionSetupDebugObserver
import io.warpnect.session.setup.SessionSetupPreferences
import io.warpnect.session.setup.SessionSetupRuntime
import io.warpnect.session.setup.SetupPathCandidate
import io.warpnect.session.setup.VideoPreferencePolicy
import io.warpnect.session.setup.VideoStreamMode
import io.warpnect.session.setup.VideoStreamPreference
import io.warpnect.session.trust.TrustedPeerStore
import io.warpnect.telemetry.ClockDomainId
import io.warpnect.telemetry.NativeTelemetrySnapshotProvider
import io.warpnect.telemetry.NativeTelemetrySourceScopeResolver
import io.warpnect.telemetry.NativeTelemetrySourceScopes
import io.warpnect.telemetry.SessionControlNetworkTelemetry
import io.warpnect.telemetry.SessionLifecycleTelemetry
import io.warpnect.telemetry.SessionPathTelemetry
import io.warpnect.telemetry.TelemetryComponent
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.encoder.VideoEncoderRequest
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * In-process Android production graph for RFC-005I. It owns the normal discovery/session flow;
 * Compose receives only the application controller and ephemeral render/input view attachment.
 */
class AndroidSecureSessionComposition private constructor(
    val coreOrchestrator: CoreOrchestrator,
    val applicationController: SecureSessionApplicationController,
    val uiResources: AndroidSessionUiResources,
    val telemetryHub: TelemetryHub,
    val diagnosticEventHub: DiagnosticEventHub,
    val reportExportController: ReportExportController,
    private val hostRegistry: HostSessionRuntimeRegistry,
    private val controlScheduler: AndroidSessionControlScheduler,
    private val directPathBackend: AndroidDirectPathBackend?,
) : AutoCloseable {
    override fun close() {
        reportExportController.close()
        controlScheduler.close()
        applicationController.close()
        hostRegistry.close()
        directPathBackend?.close()
        coreOrchestrator.shutdown()
        diagnosticEventHub.close()
    }

    companion object {
        fun create(context: Context): AndroidSecureSessionComposition? = runCatching {
            Factory(context.applicationContext).create()
        }.getOrNull()
    }

    private class Factory(
        private val context: Context,
    ) {
        private val identityRepository = AndroidSessionIdentityFactory.createLocalDeviceIdentityRepository(context)
        private val trustedPeers: TrustedPeerStore = AndroidSessionIdentityFactory.createTrustedPeerStore(context)
        private val signer = identityRepository.loadOrCreate().let { result ->
            (result as? LocalDeviceIdentityResult.Ready)?.let { identityRepository.signer() }
        } ?: error("Warpnect local identity is unavailable")
        private val sessionManager = SessionManager(
            SessionManagerConfig(
                localDeviceId = signer.identity.deviceId,
                initialPolicy = SessionBehaviorPolicy(maxConcurrentClients = 1),
            ),
        )
        private val crypto = JcaPairingCryptoProvider()
        private val routeLocalAddressResolver = AndroidRouteLocalAddressResolver()
        private val uiResources = AndroidSessionUiResources()
        private val hostRegistry = HostSessionRuntimeRegistry()
        private val discoveryDebugLog = AndroidDiscoveryDebugLog(context)
        private val clientDiscovery = AndroidLocalDiscoveryController(
            context,
            DiscoveryConfig(
                mode = DiscoveryMode.BrowseOnly,
                backendPolicy = DiscoveryBackendPolicy.DirectAndLan,
                offeredRole = SessionRole.Client,
            ),
        )
        private val hostDiscovery = AndroidLocalDiscoveryController(
            context,
            DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseOnly,
                backendPolicy = DiscoveryBackendPolicy.DirectAndLan,
                offeredRole = SessionRole.Host,
            ),
            availabilityProvider = SessionManagerHostAvailabilityProvider(sessionManager),
        )
        private val directRouteState = DirectClientRouteState(clientDiscovery)
        private val directPathBackend = AndroidDirectPathBackend.create(context)
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        private val networkCallbackHandler = Handler(Looper.getMainLooper())
        private val clientIo = AtomicReference<SecureSessionControlDatagramIo?>()
        private val hostIo = AtomicReference<SecureSessionControlDatagramIo?>()
        private val telemetryHub = runCatching { TelemetryHub(AndroidTelemetryClock) }
            .getOrElse { TelemetryHub.disabled() }
        private val diagnosticEventHub = runCatching {
            DiagnosticEventHub(
                clock = AndroidDiagnosticEventClock,
                clockDomain = ClockDomainId.AndroidBootTime,
                telemetryHub = telemetryHub,
                nativeProvider = NativeDiagnosticEventSnapshotProvider(),
            )
        }.getOrElse {
            // Diagnostics are observational; retain a local-only fallback if native collection is unavailable.
            DiagnosticEventHub(clock = AndroidDiagnosticEventClock, clockDomain = ClockDomainId.AndroidBootTime)
        }
        private val reportExportController = ReportExportController(
            DiagnosticReportBuilder(
                HubDiagnosticReportReader(telemetryHub, diagnosticEventHub),
                AndroidReportSupport.environment(context),
            ),
            context.cacheDir,
        )
        private val pipelineFactory = AndroidSessionPipelineFactory(
            DefaultAndroidSessionPipelineBindings(
                context,
                AndroidSessionPipelineResources(
                    bindClientVideoRenderer = uiResources::bindClientVideoRenderer,
                    clientInputSurface = uiResources::inputCaptureSurface,
                ),
            ),
            telemetryHub,
            diagnosticEventHub,
            VideoPipelineStartDebugObserver(discoveryDebugLog::videoPipelineStart),
        )

        fun create(): AndroidSecureSessionComposition {
            // Telemetry is observational: an unavailable native collector never blocks Sessions.
            if (telemetryHub.enabled) {
                runCatching {
                    telemetryHub.registerProvider(
                        NativeTelemetrySnapshotProvider(
                            NativeTelemetrySourceScopeResolver { sourceId ->
                                NativeTelemetrySourceScopes.scopeFor(sourceId.value)
                            },
                        ),
                    )
                }
            }
            lateinit var client: SecureSessionCoordinator
            lateinit var host: SecureSessionCoordinator
            val applicationReference = AtomicReference<SecureSessionApplicationController?>()
            val controlScheduler = AndroidSessionControlScheduler {
                applicationReference.get()?.advance()
            }

            val clientDriver = ControllerBackedClientSessionPhaseDriver(
                discovery = clientDiscovery,
                handshakeTransportFactory = ClientSessionHandshakeTransportFactory {
                    AndroidDatagramSessionHandshakeTransport.createEphemeral()?.also {
                        clientIo.set(it)
                    }
                },
                handshakeFactory = ClientSessionHandshakeControllerFactory { transport, listener ->
                    SessionHandshakeController(
                        transport = transport,
                        localSigner = signer,
                        trustedPeers = trustedPeers,
                        sessionManager = sessionManager,
                        crypto = crypto,
                        presenceProvider = CurrentDiscoveryPresenceProvider { null },
                        eventListener = listener,
                        diagnosticEvents = diagnosticEventHub.writer(
                            TelemetryScope.Component(TelemetryComponent.Handshake),
                        ),
                        debugObserver = SessionHandshakeDebugObserver(discoveryDebugLog::handshake),
                    )
                },
                pairingTransportFactory = ClientPairingTransportFactory {
                    AndroidDatagramPairingTransport.createEphemeral()
                },
                pairingFactory = ClientPairingControllerFactory { transport, prompt, completed ->
                    PairingController(
                        localSigner = signer,
                        trustedPeerStore = trustedPeers,
                        transport = transport,
                        eventListener = prompt,
                        completedListener = completed,
                        diagnosticEvents = diagnosticEventHub.writer(
                            TelemetryScope.Component(TelemetryComponent.Pairing),
                        ),
                        debugObserver = PairingDebugObserver(discoveryDebugLog::pairing),
                    )
                },
                protection = SessionProtectionController(NativeSessionProtectionRuntimeFactory),
                secureControlFactory = clientSecureControlFactory(),
                capabilityFactory = ClientCapabilityNegotiationControllerFactory { completed ->
                    CapabilityNegotiationController(
                        capabilityCollector(),
                        monotonicCapabilityClock(),
                        onCompleted = completed,
                        debugObserver = CapabilityNegotiationDebugObserver(discoveryDebugLog::capability),
                    )
                },
                setupFactory = ClientSessionSetupControllerFactory { completed ->
                    SessionSetupController(
                        monotonicSetupClock(),
                        onCompleted = completed,
                        debugObserver = SessionSetupDebugObserver(discoveryDebugLog::sessionSetup),
                    )
                },
                setupRuntimeFactory = ClientSessionSetupRuntimeFactory { bootstrap, request ->
                    sessionSetupRuntime(bootstrap, directRouteState.routeTokenFor(request))
                },
                onConnectionRequest = directRouteState::select,
                onHandshakeStarted = discoveryDebugLog::handshakeStarted,
                onHandshakeFailed = discoveryDebugLog::handshakeFailed,
            )

            val lifecycleFactory = ControllerManagedLifecycleSessionFactory(
                SessionLifecycleControllerProvider { bootstrap, pipeline, listener ->
                    lifecycleController(bootstrap, pipeline, listener) { record, generation ->
                        if (bootstrap.localRole == SessionRole.Client) {
                            client.beginReconnect(record, generation)
                        } else {
                            host.awaitResponderReconnect()
                        }
                    }
                },
            )
            client = SecureSessionCoordinator(
                localRole = SessionRole.Client,
                phaseDriver = clientDriver,
                pipelineFactory = pipelineFactory,
                lifecycleFactory = lifecycleFactory,
                debugObserver = SessionStartupDebugObserver(discoveryDebugLog::sessionStartup),
            )

            lateinit var hostDriver: ControllerBackedHostSessionPhaseDriver
            hostDriver = ControllerBackedHostSessionPhaseDriver(
                discovery = hostDiscovery,
                handshakeFactory = HostSessionHandshakeControllerFactory { listener ->
                    val transport = hostDiscovery.borrowSessionHandshakeTransport() ?: return@HostSessionHandshakeControllerFactory null
                    val datagramIo = transport as? SecureSessionControlDatagramIo
                        ?: return@HostSessionHandshakeControllerFactory null
                    hostIo.set(datagramIo)
                    SessionHandshakeController(
                        transport = transport,
                        localSigner = signer,
                        trustedPeers = trustedPeers,
                        sessionManager = sessionManager,
                        crypto = crypto,
                        presenceProvider = CurrentDiscoveryPresenceProvider {
                            hostDiscovery.currentAdvertisingPresenceId()
                        },
                        eventListener = listener,
                        diagnosticEvents = diagnosticEventHub.writer(TelemetryScope.Component(TelemetryComponent.Handshake)),
                        debugObserver = SessionHandshakeDebugObserver(discoveryDebugLog::handshake),
                    )
                },
                protection = SessionProtectionController(NativeSessionProtectionRuntimeFactory),
                secureControlFactory = hostSecureControlFactory(),
                capabilityFactory = HostCapabilityNegotiationControllerFactory { completed ->
                    CapabilityNegotiationController(
                        capabilityCollector(),
                        monotonicCapabilityClock(),
                        onCompleted = completed,
                        debugObserver = CapabilityNegotiationDebugObserver(discoveryDebugLog::capability),
                    )
                },
                setupFactory = HostSessionSetupControllerFactory { completed ->
                    SessionSetupController(
                        monotonicSetupClock(),
                        onCompleted = completed,
                        debugObserver = SessionSetupDebugObserver(discoveryDebugLog::sessionSetup),
                    )
                },
                setupRuntimeFactory = HostSessionSetupRuntimeFactory { bootstrap ->
                    sessionSetupRuntime(bootstrap, null)
                },
                capabilityPolicy = productionHostCapabilityPolicy(),
                setupPolicy = HostSessionSetupPolicy(productionSetupPreferences()),
                onPrepared = { bootstrap -> host.acceptPreparedHostSession(bootstrap) },
                pairingResponderFactory = HostPairingResponderFactory {
                    AndroidHostPairingResponder.create(
                        hostDiscovery,
                        signer,
                        trustedPeers,
                        diagnosticEventHub.writer(TelemetryScope.Component(TelemetryComponent.Pairing)),
                        PairingDebugObserver(discoveryDebugLog::pairing),
                    )
                },
                onHostReadinessStarted = discoveryDebugLog::hostLifecycleStarted,
                onHostReadinessStopped = discoveryDebugLog::hostLifecycleStopped,
            )
            host = SecureSessionCoordinator(
                localRole = SessionRole.Host,
                phaseDriver = HostSessionApplicationPhaseDriver(hostDriver),
                pipelineFactory = pipelineFactory,
                lifecycleFactory = lifecycleFactory,
                hostRegistry = hostRegistry,
                debugObserver = SessionStartupDebugObserver(discoveryDebugLog::sessionStartup),
            )

            val application = SecureSessionApplicationController(
                client = client,
                host = host,
                requestFactory = SecureSessionConnectRequestFactory { presence ->
                    SecureSessionConnectRequest(
                        presence = presence,
                        capabilityRequest = productionCapabilityRequest(),
                        setupPreferences = productionSetupPreferences(),
                    )
                },
                onClientDiscoverySnapshotPublished = discoveryDebugLog::snapshotPublished,
                controlDispatcher = controlScheduler,
            )
            applicationReference.set(application)
            val orchestrator = CoreOrchestrator(
                localDiscoveryController = clientDiscovery,
                localDeviceIdentityRepository = identityRepository,
                trustedPeerStore = trustedPeers,
                sessionManager = sessionManager,
                secureSessionCoordinator = client,
                secureSessionApplicationController = application,
                telemetryHub = telemetryHub,
            )
            return AndroidSecureSessionComposition(
                orchestrator,
                application,
                uiResources,
                telemetryHub,
                diagnosticEventHub,
                reportExportController,
                hostRegistry,
                controlScheduler,
                directPathBackend,
            )
        }

        private fun clientSecureControlFactory(): io.warpnect.session.integration.SecureSessionControlTransportFactory =
            io.warpnect.session.integration.SecureSessionControlTransportFactory { bootstrap ->
                clientIo.get()?.let { datagram ->
                    AndroidSecureSessionControlTransport(
                        datagram,
                        bootstrap.protection,
                        bootstrap.protection.sessionControlContext.receiveContextId,
                        bootstrap.endpoint,
                        SessionControlNetworkTelemetry.register(
                            telemetryHub,
                            TelemetryScope.Session(bootstrap.sessionId, bootstrap.generation),
                        ),
                    )
                }
            }

        private fun hostSecureControlFactory(): HostSecureSessionControlTransportFactory =
            HostSecureSessionControlTransportFactory { bootstrap ->
                hostIo.get()?.let { datagram ->
                    AndroidSecureSessionControlTransport(
                        datagram,
                        bootstrap.protection,
                        bootstrap.protection.sessionControlContext.receiveContextId,
                        bootstrap.endpoint,
                        SessionControlNetworkTelemetry.register(
                            telemetryHub,
                            TelemetryScope.Session(bootstrap.sessionId, bootstrap.generation),
                        ),
                    )
                }
            }

        private fun capabilityCollector() = AndroidLocalCapabilityCollector(
            AndroidCapabilityProbe { role -> capabilitySnapshot(role) },
        )

        private fun capabilitySnapshot(role: SessionRole): AndroidCapabilityProbeSnapshot {
            val directImplemented = directPathBackend?.isImplemented() == true
            val directAvailable = directPathBackend?.isPlatformAvailable() == true && when (role) {
                SessionRole.Host -> true
                SessionRole.Client -> directRouteState.isUsable()
            }
            val videoMode = VideoStreamMode(1280, 720, 60, 8_000_000L)
            val encoder = AndroidVideoEncoderDiscovery(
                debugObserver = object : VideoEncoderCbrCapabilityDebugObserver {
                    override fun onDecision(decision: io.warpnect.platform.video.encoder.CbrCapabilityDecision) {
                        discoveryDebugLog.encoderCbrCapability(decision)
                    }

                    override fun onActiveProbeStarted() {
                        discoveryDebugLog.encoderCbrActiveProbeStarted()
                    }
                },
            ).query(
                VideoEncoderRequest(
                    width = videoMode.width,
                    height = videoMode.height,
                    frameRate = videoMode.fps,
                    bitrateBps = videoMode.bitrateBps.toInt(),
                    iFrameIntervalSeconds = 1f,
                ),
            )
            val decoder = AndroidVideoDecoderDiscovery().query(
                VideoDecoderConfig(
                    width = videoMode.width,
                    height = videoMode.height,
                    expectedFrameRate = videoMode.fps,
                    configGeneration = 1,
                    codecSpecificData = listOf(byteArrayOf(1)),
                ),
            )
            val system = AndroidSystemAudioCaptureController(context).queryCapabilities(
                AudioCaptureRequest(
                    source = AudioCaptureSource.SystemAudio,
                    preferredSampleRateHz = 48_000,
                    channelCount = 2,
                    targetChunkDurationUs = 5_000L,
                ),
            )
            val microphone = AndroidMicrophoneAudioCaptureController(context).queryCapabilities(
                AudioCaptureRequest(
                    source = AudioCaptureSource.MicrophoneAudio,
                    preferredSampleRateHz = 48_000,
                    channelCount = 1,
                    targetChunkDurationUs = 5_000L,
                ),
            )
            val audioEncoder = NativeOpusAudioEncoderController().queryCapabilities(
                AudioEncoderRequest(
                    source = AudioCaptureSource.SystemAudio,
                    sampleRateHz = 48_000,
                    channelCount = 2,
                    frameDurationUs = 5_000,
                    bitrateBps = 128_000,
                ),
            )
            val injection = if (role == SessionRole.Host) {
                AndroidInputInjectionController(context).let { controller ->
                    try {
                        runBlocking { controller.queryCapabilities() }
                    } finally {
                        controller.close()
                    }
                }
            } else {
                null
            }
            val snapshot = AndroidCapabilityProbeSnapshot(
                lanSecurePathAvailable = true,
                directPathBackendImplemented = directImplemented,
                directPathAvailable = directAvailable,
                standbyPathSupported = directImplemented,
                videoEncoder = encoder,
                videoDecoder = decoder,
                systemAudioCapture = system,
                microphoneCapture = microphone,
                audioEncoder = audioEncoder,
                opusDecodeAvailable = true,
                lowLatencyPlaybackAvailable = true,
                inputInjection = injection,
                implementedCaptureKinds = CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE or
                    CapabilityBits.INPUT_TOUCHSCREEN or CapabilityBits.INPUT_TOUCHPAD or CapabilityBits.INPUT_STYLUS,
                separateMicrophonePerPeerAvailable = false,
            )
            val local = snapshot.toLocalSnapshot(role, capturedAtMonotonicNs = 0L)
            val videoAvailable = local.localAvailability["video"] == LocalCapabilityAvailability.Available
            discoveryDebugLog.localCapabilityProbe(
                role = role.name,
                videoAvailable = videoAvailable,
                videoError = encoder.error.name,
                inputAvailable = local.input.injectionKinds != 0,
                inputError = injection?.lastError?.name ?: "NotApplicable",
            )
            return snapshot
        }

        private fun sessionSetupRuntime(
            bootstrap: io.warpnect.session.capability.NegotiatedSessionBootstrap,
            directRouteToken: String?,
        ): SessionSetupRuntime? {
            discoveryDebugLog.routeLocalAddressResolutionStarted()
            val remoteAddress = requireNotNull(
                InetAddress.getByAddress(bootstrap.endpoint.addressBytes()).hostAddress,
            )
            val routeResolution = routeLocalAddressResolver.resolveDetailed(bootstrap.endpoint)
            val localAddress = when (routeResolution) {
                is RouteLocalAddressResolution.Resolved -> {
                    discoveryDebugLog.routeLocalAddressResolutionSucceeded(routeResolution.family.name)
                    routeResolution.address
                }
                is RouteLocalAddressResolution.Wildcard -> {
                    discoveryDebugLog.routeLocalAddressResolutionFailed("WildcardAddress", routeResolution.family.name)
                    return null
                }
                RouteLocalAddressResolution.ChannelOpenFailed -> {
                    discoveryDebugLog.routeLocalAddressResolutionFailed("ChannelOpenFailed")
                    return null
                }
                RouteLocalAddressResolution.ConnectFailed -> {
                    discoveryDebugLog.routeLocalAddressResolutionFailed("ConnectFailed")
                    return null
                }
                RouteLocalAddressResolution.LocalAddressUnavailable -> {
                    discoveryDebugLog.routeLocalAddressResolutionFailed("LocalAddressUnavailable")
                    return null
                }
            }
            discoveryDebugLog.sessionSetupRuntimeCreated()
            val directCoordinator = directPathBackend?.createCoordinator(
                bootstrap.secureSessionControl,
                DirectPeerAddressResolver(clientDiscovery::directPeerAddress),
            )
            val directControl = bootstrap.secureSessionControl as? AndroidSecureSessionControlTransport
            return SessionSetupRuntime(
                lanCandidate = SetupPathCandidate(
                    pathId = PathId.requireValid(1u),
                    kind = io.warpnect.session.NetworkPathKind.Lan,
                    binding = PathSocketBinding(
                        PathId.requireValid(1u),
                        io.warpnect.session.NetworkPathKind.Lan,
                        localAddress,
                    ),
                    remoteAddress = remoteAddress,
                    controlEndpoint = bootstrap.endpoint,
                ),
                endpointAllocator = AndroidChannelEndpointAllocator(),
                transportPreparer = NativePreparedChannelTransportPreparer(),
                exactValidator = exactValidator(),
                directCoordinator = directCoordinator,
                directRouteToken = directRouteToken,
                controlPathRebinder = directControl?.let(::AndroidSessionControlPathRebinder),
            )
        }

        private fun exactValidator() = AndroidExactStreamConfigurationValidator(
            systemAudioCapture = { request -> AndroidSystemAudioCaptureController(context).queryCapabilities(request) },
            microphoneCapture = { request ->
                AndroidMicrophoneAudioCaptureController(
                    context,
                ).queryCapabilities(request)
            },
            inputCaptureAvailable = { config ->
                config.inputKinds and (CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE) != 0
            },
            inputInjectionAvailable = { config ->
                val controller = AndroidInputInjectionController(context)
                try {
                    val capabilities = runBlocking { controller.queryCapabilities() }
                    capabilities.serviceAvailable &&
                        (config.inputKinds and capabilityBits(capabilities)) == config.inputKinds
                } finally {
                    controller.close()
                }
            },
        )

        private fun lifecycleController(
            bootstrap: PreparedSessionBootstrap,
            pipeline: SessionPipelineRuntime,
            listener: io.warpnect.session.integration.SessionLifecycleRuntimeListener,
            reconnect: (
                io.warpnect.session.lifecycle.RecoverableSessionRecord,
                io.warpnect.session.SessionGeneration,
            ) -> Unit,
        ): SessionLifecycleController {
            val pathBindings = buildMap {
                (listOf(bootstrap.activePath) + listOfNotNull(bootstrap.standbyPath)).forEach { path ->
                    val endpoint = bootstrap.pathControlEndpoints[path.pathId] ?: when (path.pathId) {
                        bootstrap.activePath.pathId -> bootstrap.initialControlEndpoint
                        else -> null
                    }
                    if (endpoint != null) put(path.pathId, LifecyclePathBinding(path, endpoint))
                }
            }
            val control = bootstrap.secureSessionControl as? AndroidSecureSessionControlTransport
            val candidateIo = if (control != null && directPathBackend != null) {
                AndroidLifecycleCandidateDatagramIo(directPathBackend.candidateDispatcher, control)
            } else {
                null
            }
            val migrationAdapter = candidateIo?.let { candidate ->
                PreparedSessionMigrationAdapter(
                    bootstrap = bootstrap,
                    endpointAllocator = AndroidChannelEndpointAllocator(),
                    transportPreparer = NativePreparedChannelTransportPreparer(),
                    candidateIo = candidate,
                    commitControlPath = candidate::commitActivePath,
                )
            } ?: UnavailableMigrationAdapter()
            val controllerReference = AtomicReference<SessionLifecycleController?>()
            var directGroupObserver: AutoCloseable? = null
            var networkPathMonitor: AndroidNetworkPathMonitor? = null
            candidateIo?.setReceiver { source, datagram, nowUs ->
                controllerReference.get()?.receiveCandidate(source, datagram, nowUs)
            }
            val controller = SessionLifecycleController(
                bootstrap = bootstrap,
                pathProvider = object : SessionLifecyclePathProvider {
                    override fun bindingFor(pathId: PathId): LifecyclePathBinding? = pathBindings[pathId]
                },
                migrationAdapter = migrationAdapter,
                recoveryDelegate = SessionLifecycleReconnectDelegate { record, nextGeneration ->
                    reconnect(record, nextGeneration)
                },
                recoveryIntent = SessionRecoveryIntent(
                    PathPreferencePolicy.PreferDirectThenLan,
                    SecondaryPathPolicy.KeepValidatedStandby,
                    productionCapabilityRequest(),
                    productionSetupPreferences(),
                ),
                capacityOwner = if (bootstrap.localRole == SessionRole.Host) {
                    SessionManagerLifecycleCapacityOwner(sessionManager)
                } else {
                    null
                },
                continuityParticipants = listOf(
                    pipeline,
                    object : SessionContinuityParticipant {
                        override fun onSessionClosing() {
                            candidateIo?.close()
                            directGroupObserver?.close()
                            networkPathMonitor?.close()
                        }

                        override fun onSessionReconnected() {
                            directGroupObserver?.close()
                            networkPathMonitor?.close()
                        }
                    },
                    object : SessionContinuityParticipant {
                        override fun onSessionSuspended() = listener.onRecovering()
                    },
                ),
                telemetry = SessionLifecycleTelemetry.register(
                    telemetryHub,
                    TelemetryScope.Session(bootstrap.sessionId, bootstrap.generation),
                ),
                diagnosticEvents = SessionLifecycleDiagnosticEvents.register(
                    diagnosticEventHub,
                    TelemetryScope.Session(bootstrap.sessionId, bootstrap.generation),
                ),
                pathTelemetry = buildMap {
                    (listOf(bootstrap.activePath) + listOfNotNull(bootstrap.standbyPath)).forEach { path ->
                        put(
                            path.pathId,
                            SessionPathTelemetry.register(
                                telemetryHub,
                                TelemetryScope.Path(
                                    bootstrap.sessionId,
                                    bootstrap.generation,
                                    path.pathId,
                                    path.kind,
                                ),
                            ),
                        )
                    }
                },
                pathDiagnosticScopes = buildMap {
                    (listOf(bootstrap.activePath) + listOfNotNull(bootstrap.standbyPath)).forEach { path ->
                        put(
                            path.pathId,
                            TelemetryScope.Path(
                                bootstrap.sessionId,
                                bootstrap.generation,
                                path.pathId,
                                path.kind,
                            ),
                        )
                    }
                },
                debugObserver = SessionLifecycleDebugObserver(discoveryDebugLog::sessionLifecycle),
                clock = AndroidSessionMonotonicClock(),
            )
            controllerReference.set(controller)
            val directPathIds = (listOf(bootstrap.activePath) + listOfNotNull(bootstrap.standbyPath))
                .filter { it.kind == io.warpnect.session.NetworkPathKind.Direct }
                .map { it.pathId }
            if (directPathIds.isNotEmpty()) {
                directGroupObserver = directPathBackend?.observeGroupState { formed ->
                    directPathIds.forEach { pathId ->
                        if (formed) {
                            controller.onPlatformPathAvailable(pathId)
                        } else {
                            controller.onPlatformPathLoss(pathId, hard = true)
                        }
                    }
                }
            }
            val lanPathIds = (listOf(bootstrap.activePath) + listOfNotNull(bootstrap.standbyPath))
                .filter { it.kind == io.warpnect.session.NetworkPathKind.Lan }
                .mapNotNull { path -> networkForLocalAddress(path.localAddress)?.let { path.pathId to it } }
            if (lanPathIds.isNotEmpty()) {
                networkPathMonitor = connectivityManager?.let { manager ->
                    AndroidNetworkPathMonitor(
                        connectivityManager = manager,
                        callbackHandler = networkCallbackHandler,
                        dispatch = controller::onPlatformPathLoss,
                        onAvailable = controller::onPlatformPathAvailable,
                    ).also { monitor -> lanPathIds.forEach { (pathId, network) -> monitor.register(pathId, network) } }
                }
            }
            return controller
        }

        /** Resolves a selected native socket address on the control path, never in a callback. */
        private fun networkForLocalAddress(localAddress: String): Network? = runCatching {
            val manager = connectivityManager ?: return@runCatching null
            manager.allNetworks.firstOrNull { network ->
                manager.getLinkProperties(network)?.linkAddresses?.any { linkAddress ->
                    linkAddress.address.hostAddress?.substringBefore('%') == localAddress
                } == true
            }
        }.getOrNull()

        private fun productionCapabilityRequest(): CapabilityRequest = CapabilityRequest(
            requiredChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
            preferredChannels = CapabilityBits.CHANNEL_SYSTEM_AUDIO,
            disabledChannels = CapabilityBits.CHANNEL_MICROPHONE_AUDIO or CapabilityBits.CHANNEL_TELEMETRY,
            requiredInputKinds = CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE,
            preferredInputKinds = 0,
            microphonePolicyPrimary = MicrophoneRoutingSelection.NotApplicable,
            microphonePolicyFallback = MicrophoneRoutingSelection.NotApplicable,
            stablePresenceRequiredKinds = 0,
            stablePresencePreferredKinds = 0,
            videoLowLatencyRequirement = FeatureRequirement.Preferred,
            distinctGamepadIdentityRequirement = FeatureRequirement.Disabled,
            requiredRecoveryFlags = CapabilityBits.RECOVERY_NACK or CapabilityBits.RECOVERY_VIDEO_RESYNC,
        )

        private fun productionHostCapabilityPolicy(): HostCapabilityPolicy = HostCapabilityPolicy(
            allowedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_SYSTEM_AUDIO or
                CapabilityBits.CHANNEL_INPUT,
            mandatoryChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
            // Availability still comes from WNCP snapshots; this policy merely permits the
            // production RFC-005G backend when Android currently makes it usable.
            allowedPathKinds = CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT,
            allowedRecoveryFlags = CapabilityBits.RECOVERY_NACK or CapabilityBits.RECOVERY_VIDEO_RESYNC,
            allowedInputKinds = CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE,
            allowedMicrophoneRoutingMask = 0,
            allowDistinctGamepadIdentity = false,
            allowedStablePresenceKinds = 0,
        )

        private fun productionSetupPreferences(): SessionSetupPreferences = SessionSetupPreferences(
            pathPreference = PathPreferencePolicy.PreferDirectThenLan,
            secondaryPathPolicy = SecondaryPathPolicy.KeepValidatedStandby,
            video = VideoStreamPreference(
                VideoPreferencePolicy.Exact,
                listOf(
                    VideoStreamMode(
                        width = 1280,
                        height = 720,
                        fps = 60,
                        bitrateBps = 8_000_000L,
                        flags = CapabilityBits.VIDEO_KEYFRAME_REQUEST or CapabilityBits.VIDEO_RESYNC,
                    ),
                ),
            ),
            systemAudio = AudioStreamPreference(
                listOf(
                    AudioStreamMode(
                        sampleRateHz = 48_000,
                        frameDurationUs = 5_000,
                        channelCount = 2,
                        bitrateBps = 128_000,
                    ),
                ),
            ),
            input = io.warpnect.session.setup.InputStreamConfiguration(
                inputKinds = CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE,
                stablePresenceKinds = 0,
                featureFlags = CapabilityBits.INPUT_STATE_CONVERGENCE,
            ),
        )

        private fun capabilityBits(capabilities: io.warpnect.input.injection.InputInjectionCapabilities): Int =
            (if (capabilities.keyInjectionSupported) CapabilityBits.INPUT_KEYBOARD else 0) or
                (if (capabilities.pointerInjectionSupported) CapabilityBits.INPUT_MOUSE else 0) or
                (if (capabilities.touchInjectionSupported) CapabilityBits.INPUT_TOUCHSCREEN else 0) or
                (if (capabilities.joystickInjectionSupported) CapabilityBits.INPUT_GAMEPAD else 0)

        private fun monotonicCapabilityClock() = CapabilityNegotiationMonotonicClock {
            SystemClock.elapsedRealtime()
        }

        private fun monotonicSetupClock() = AndroidSessionMonotonicClock()
    }
}

/** Ephemeral Activity-owned view references; no session keying material is ever retained here. */
class AndroidSessionUiResources {
    private val render = AtomicReference<WarpnectVideoSurfaceView?>()
    private val input = AtomicReference<WarpnectInputCaptureView?>()
    private val rendererLock = Any()
    private var renderer: AndroidVideoRenderController? = null
    private val _clientVideoRendererBound = MutableStateFlow(false)
    val clientVideoRendererBound: StateFlow<Boolean> = _clientVideoRendererBound.asStateFlow()

    fun attachClientRenderSurface(renderSurface: WarpnectVideoSurfaceView) {
        render.set(renderSurface)
        synchronized(rendererLock) { renderer }?.let(renderSurface::attachController)
    }

    fun clearClientRenderSurface(renderSurface: WarpnectVideoSurfaceView) {
        synchronized(rendererLock) { renderer }?.detach(renderSurface)
        render.compareAndSet(renderSurface, null)
    }

    fun attachClientInputSurface(inputSurface: WarpnectInputCaptureView) {
        input.set(inputSurface)
    }

    fun clearClientInputSurface(inputSurface: WarpnectInputCaptureView) {
        input.compareAndSet(inputSurface, null)
    }

    /**
     * Establishes the single real receiver-pipeline ownership that is allowed to request a
     * Compose SurfaceView. It never touches a View because pipeline construction is control-path
     * work rather than a UI callback.
     */
    fun bindClientVideoRenderer(value: AndroidVideoRenderController): AutoCloseable? = synchronized(rendererLock) {
        if (renderer != null) {
            return@synchronized null
        }
        renderer = value
        _clientVideoRendererBound.value = true
        AutoCloseable {
            synchronized(rendererLock) {
                if (renderer === value) {
                    renderer = null
                    _clientVideoRendererBound.value = false
                }
            }
        }
    }

    fun inputCaptureSurface(): WarpnectInputCaptureView? = input.get()
}

/** Keeps only the selected ephemeral RFC-005B Direct locator for the current Client attempt. */
private class DirectClientRouteState(
    private val discovery: AndroidLocalDiscoveryController,
) {
    private val locator = AtomicReference<DiscoveryOpaqueRouteLocator?>()

    fun select(request: SecureSessionConnectRequest) {
        val descriptor = discovery.resolveRoute(
            request.presence.presenceId,
            DiscoveryRouteKind.Direct,
        ).descriptor as? DiscoveryRouteDescriptor.Direct
        val directLocator = descriptor?.peerLocator
        locator.set(directLocator?.takeIf { discovery.directPeerAddress(it) != null })
    }

    fun routeTokenFor(request: SecureSessionConnectRequest): String? {
        select(request)
        return locator.get()?.value
    }

    fun isUsable(): Boolean = locator.get()?.let { discovery.directPeerAddress(it) != null } == true
}

private class UnavailableMigrationAdapter : io.warpnect.session.lifecycle.SessionLifecycleMigrationAdapter {
    override fun armCandidateWindow(
        binding: LifecyclePathBinding,
        migrationId: io.warpnect.session.lifecycle.PathMigrationId,
        timeoutMs: Long,
    ): Boolean = false

    override fun disarmCandidateWindow(migrationId: io.warpnect.session.lifecycle.PathMigrationId) = Unit

    override fun sendCandidate(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean = false

    override fun prepareChannels(
        binding: LifecyclePathBinding,
        channels: List<io.warpnect.session.SessionChannelKind>,
    ): io.warpnect.session.lifecycle.ChannelMigrationPreparation? = null

    override fun commit(
        binding: LifecyclePathBinding,
        preparation: io.warpnect.session.lifecycle.ChannelMigrationPreparation,
        remoteEntries: List<io.warpnect.session.lifecycle.PathMigrationEntry>,
    ): SessionLifecycleError = SessionLifecycleError.NoStandbyPath
}

/** Host readiness explicitly opens a bounded pairing window on the shared RFC-005B contact port. */
private class AndroidHostPairingResponder private constructor(
    private val controller: AndroidPairingController,
    private val prompt: AtomicReference<PairingVerificationPrompt?>,
    private val pairingCompletedListener: AtomicReference<(() -> Unit)?>,
) : HostPairingResponder {
    override fun start(): io.warpnect.session.integration.SecureSessionIntegrationError =
        controller.openPairingWindow().error.toHostPairingIntegrationError()

    override fun approvePairing(): io.warpnect.session.integration.SecureSessionIntegrationError {
        val current = prompt.get() ?: return io.warpnect.session.integration.SecureSessionIntegrationError.PairingRequired
        return controller.acceptVerification(current.attemptId).error.toHostPairingIntegrationError()
    }

    override fun rejectPairing(): io.warpnect.session.integration.SecureSessionIntegrationError {
        val current = prompt.get() ?: return io.warpnect.session.integration.SecureSessionIntegrationError.PairingRequired
        return controller.rejectVerification(current.attemptId).error.toHostPairingDecisionIntegrationError()
    }

    override fun pendingPrompt(): PairingVerificationPrompt? {
        val current = prompt.get() ?: return null
        if (controller.snapshot().state == io.warpnect.session.pairing.PairingControllerState.AwaitingUserConfirmation) {
            return current
        }
        prompt.compareAndSet(current, null)
        return null
    }

    override fun setPairingCompletedListener(listener: () -> Unit) {
        pairingCompletedListener.set(listener)
    }

    override fun close() = controller.close()

    companion object {
        fun create(
            discovery: AndroidLocalDiscoveryController,
            signer: io.warpnect.session.identity.LocalDeviceIdentitySigner,
            trustedPeers: TrustedPeerStore,
            diagnosticEvents: io.warpnect.diagnostics.DiagnosticEventWriter,
            debugObserver: PairingDebugObserver,
        ): AndroidHostPairingResponder? {
            val prompt = AtomicReference<PairingVerificationPrompt?>()
            val pairingCompletedListener = AtomicReference<(() -> Unit)?>()
            val controller = AndroidPairingControllerFactory.createResponderForAdvertisedDiscovery(
                discovery,
                signer,
                trustedPeers,
                eventListener = PairingEventListener { prompt.set(it) },
                completedListener = PairingCompletedListener {
                    prompt.set(null)
                    pairingCompletedListener.get()?.invoke()
                },
                diagnosticEvents = diagnosticEvents,
                debugObserver = debugObserver,
            ) ?: return null
            return AndroidHostPairingResponder(controller, prompt, pairingCompletedListener)
        }
    }
}

private fun PairingError.toHostPairingIntegrationError(): io.warpnect.session.integration.SecureSessionIntegrationError =
    when (this) {
        PairingError.None -> io.warpnect.session.integration.SecureSessionIntegrationError.None
        PairingError.Closed -> io.warpnect.session.integration.SecureSessionIntegrationError.Closed
        else -> io.warpnect.session.integration.SecureSessionIntegrationError.PairingFailed
    }

/** A deliberate local SAS rejection ends only that inbound attempt; Host discovery remains ready. */
private fun PairingError.toHostPairingDecisionIntegrationError(): io.warpnect.session.integration.SecureSessionIntegrationError =
    when (this) {
        PairingError.UserRejected,
        PairingError.VerificationMismatch,
        -> io.warpnect.session.integration.SecureSessionIntegrationError.None
        else -> toHostPairingIntegrationError()
    }
