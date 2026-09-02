package io.warpnect.platform.session.integration

import android.content.Context
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.session.AudioReceiverSessionConfig
import io.warpnect.audio.session.AudioTransmitterSessionConfig
import io.warpnect.audio.session.DefaultAudioReceiverSessionController
import io.warpnect.audio.session.DefaultAudioTransmitterSessionController
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import io.warpnect.audio.transport.AudioTransportConfig
import io.warpnect.capture.CaptureRequest
import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.reliability.InputReliabilityConfig
import io.warpnect.input.session.ReverseInputReceiverSessionConfig
import io.warpnect.input.session.ReverseInputReceiverSessionController
import io.warpnect.input.session.ReverseInputSenderSessionConfig
import io.warpnect.input.session.ReverseInputSenderSessionController
import io.warpnect.input.transport.InputReceiverConfig
import io.warpnect.input.transport.InputTransportConfig
import io.warpnect.platform.audio.capture.AndroidMicrophoneAudioCaptureController
import io.warpnect.platform.audio.capture.AndroidSystemAudioCaptureController
import io.warpnect.platform.audio.decoder.NativeOpusAudioDecoderController
import io.warpnect.platform.audio.encoder.NativeOpusAudioEncoderController
import io.warpnect.platform.audio.playback.NativeOboeAudioPlaybackController
import io.warpnect.platform.audio.transport.NativeSclAudioReceiverController
import io.warpnect.platform.audio.transport.NativeSclAudioTransportController
import io.warpnect.platform.capture.AndroidVideoCaptureController
import io.warpnect.platform.input.capture.AndroidInputCaptureController
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import io.warpnect.platform.input.injection.AndroidInputInjectionController
import io.warpnect.platform.input.mapping.AndroidTargetDisplayGeometryProvider
import io.warpnect.platform.input.mapping.AndroidTargetInputDeviceResolver
import io.warpnect.platform.input.mapping.AndroidTargetInputMapper
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingConfig
import io.warpnect.platform.input.transport.NativeSclInputReceiverController
import io.warpnect.platform.input.transport.NativeSclInputTransportController
import io.warpnect.platform.video.decoder.AndroidMediaCodecVideoDecoder
import io.warpnect.platform.video.decoder.VideoDecoderDebugObserver
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.platform.video.encoder.VideoEncoderDiscovery
import io.warpnect.platform.video.encoder.VideoEncoderFrameDebugObserver
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.VideoRenderDebugObserver
import io.warpnect.platform.video.transport.NativeSclVideoReceiverController
import io.warpnect.platform.video.transport.NativeSclVideoTransportController
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.integration.SessionPipelineComponent
import io.warpnect.session.setup.AudioStreamMode
import io.warpnect.session.setup.InputStreamConfiguration
import io.warpnect.session.setup.PreparedChannel
import io.warpnect.session.setup.RecoveryConfiguration
import io.warpnect.session.setup.SetupConfiguration
import io.warpnect.session.setup.VideoStreamMode
import io.warpnect.telemetry.AudioReceiverTelemetry
import io.warpnect.telemetry.AudioSenderTelemetry
import io.warpnect.telemetry.InputReceiverTelemetry
import io.warpnect.telemetry.InputSenderTelemetry
import io.warpnect.telemetry.NativeAudioPlaybackTelemetry
import io.warpnect.telemetry.VideoDecoderTelemetry
import io.warpnect.telemetry.VideoEncoderTelemetry
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.render.VideoRenderTarget
import io.warpnect.video.render.VideoRenderTargetListener
import io.warpnect.video.render.VideoViewportGeometry
import io.warpnect.video.render.VideoViewportGeometryProvider
import io.warpnect.video.session.DefaultVideoReceiverSessionController
import io.warpnect.video.session.DefaultVideoTransmitterSessionController
import io.warpnect.video.session.VideoReceiverSessionConfig
import io.warpnect.video.session.VideoTransmitterSessionConfig
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportFecConfig

/**
 * Local Android resources only. Network addresses, ports, packet protection, and transport
 * handles are intentionally absent: RFC-005G owns and binds all of them before this factory runs.
 */
data class AndroidSessionPipelineResources(
    val bindClientVideoRenderer: (AndroidVideoRenderController) -> AutoCloseable? = { null },
    val clientInputSurface: () -> WarpnectInputCaptureView? = { null },
    val clientViewportGeometry: VideoViewportGeometryProvider = VideoViewportGeometryProvider {
        VideoViewportGeometry()
    },
    val hostDisplayId: Int = 0,
    val systemAudioTargetUid: Int? = null,
    val inputTargetUid: Int = -1,
    val inputTargetDisplayId: Int = 0,
    val telemetryFactory: ((PreparedChannel) -> SessionPipelineComponent?)? = null,
)

/**
 * Android production binding for RFC-005I. It composes the pre-existing Phase 2/3/4 controllers
 * around a native transport adopted from RFC-005G; it never invokes a legacy/manual UDP factory.
 */
class DefaultAndroidSessionPipelineBindings(
    context: Context,
    private val videoEncoderDiscovery: VideoEncoderDiscovery,
    private val resources: AndroidSessionPipelineResources = AndroidSessionPipelineResources(),
    private val videoEncoderFrameDebugObserver: VideoEncoderFrameDebugObserver = VideoEncoderFrameDebugObserver.None,
    private val videoDecoderDebugObserver: VideoDecoderDebugObserver = VideoDecoderDebugObserver.None,
    private val videoRenderDebugObserver: VideoRenderDebugObserver = VideoRenderDebugObserver.None,
) : AndroidSessionPipelineBindings {
    private val appContext = context.applicationContext

    override fun createVideoSender(
        channel: PreparedChannel,
        mode: VideoStreamMode,
        recovery: RecoveryConfiguration?,
        transport: NativeSclVideoTransportController,
        telemetry: VideoEncoderTelemetry,
    ): AndroidVideoSenderPipeline {
        val controller = DefaultVideoTransmitterSessionController(
            captureController = AndroidVideoCaptureController(appContext),
            encoderController = AndroidMediaCodecVideoEncoder(
                discovery = videoEncoderDiscovery,
                telemetry = telemetry,
                frameDebugObserver = videoEncoderFrameDebugObserver,
            ),
            transportController = transport,
        )
        return AndroidVideoSenderPipeline(
            controller = controller,
            config = VideoTransmitterSessionConfig(
                captureRequest = CaptureRequest(resources.hostDisplayId, mode.width, mode.height),
                encoderRequest = VideoEncoderRequest(
                    width = mode.width,
                    height = mode.height,
                    frameRate = mode.fps,
                    bitrateBps = mode.bitrateBps.toInt(),
                    iFrameIntervalSeconds = DEFAULT_VIDEO_I_FRAME_INTERVAL_SECONDS,
                ),
                transportConfig = channel.videoTransportConfig(recovery),
            ),
            telemetrySources = listOf(telemetry),
        )
    }

    override fun createVideoReceiver(
        channel: PreparedChannel,
        mode: VideoStreamMode,
        recovery: RecoveryConfiguration?,
        receiver: NativeSclVideoReceiverController,
        telemetry: VideoDecoderTelemetry,
    ): AndroidVideoReceiverPipeline? {
        lateinit var controller: DefaultVideoReceiverSessionController
        val renderer = AndroidVideoRenderController(
            targetListener = object : VideoRenderTargetListener {
                override fun onRenderTargetAvailable(target: VideoRenderTarget) {
                    controller.onRenderTargetAvailable(target)
                }

                override fun onRenderTargetChanged(target: VideoRenderTarget) {
                    controller.onRenderTargetChanged(target)
                }

                override fun onRenderTargetDestroyed(surfaceGeneration: Long) {
                    controller.onRenderTargetDestroyed(surfaceGeneration)
                }
            },
            debugObserver = videoRenderDebugObserver,
        )
        controller = DefaultVideoReceiverSessionController(
            receiverRuntimeController = receiver,
            decoderController = AndroidMediaCodecVideoDecoder(
                telemetry = telemetry,
                debugObserver = videoDecoderDebugObserver,
            ),
            renderController = renderer,
            telemetry = telemetry,
        )
        val rendererBinding = resources.bindClientVideoRenderer(renderer) ?: run {
            controller.close()
            return null
        }
        return AndroidVideoReceiverPipeline(
            controller = controller,
            config = VideoReceiverSessionConfig(
                receiverRuntimeConfig = channel.videoReceiverConfig(recovery),
            ),
            onPathMigrationCommitted = controller::requestContinuityResync,
            onClose = rendererBinding::close,
            telemetrySources = listOf(telemetry),
        )
    }

    override fun createAudioSender(
        channel: PreparedChannel,
        mode: AudioStreamMode,
        transport: NativeSclAudioTransportController,
        telemetry: AudioSenderTelemetry,
    ): AndroidAudioSenderPipeline {
        val source = channel.audioSource()
        val capture = when (source) {
            AudioCaptureSource.SystemAudio -> AndroidSystemAudioCaptureController(appContext, telemetry = telemetry)
            AudioCaptureSource.MicrophoneAudio -> AndroidMicrophoneAudioCaptureController(
                appContext,
                telemetry = telemetry,
            )
        }
        return AndroidAudioSenderPipeline(
            controller = DefaultAudioTransmitterSessionController(
                captureController = capture,
                encoderController = NativeOpusAudioEncoderController(telemetry),
                transportController = transport,
            ),
            telemetrySources = listOf(telemetry),
            config = AudioTransmitterSessionConfig(
                captureRequest = AudioCaptureRequest(
                    source = source,
                    preferredSampleRateHz = mode.sampleRateHz,
                    channelCount = mode.channelCount,
                    targetChunkDurationUs = mode.frameDurationUs.toLong(),
                    targetUid = if (source == AudioCaptureSource.SystemAudio) resources.systemAudioTargetUid else null,
                ),
                encoderRequest = AudioEncoderRequest(
                    source = source,
                    sampleRateHz = mode.sampleRateHz,
                    channelCount = mode.channelCount,
                    frameDurationUs = mode.frameDurationUs,
                    bitrateBps = mode.bitrateBps.toInt(),
                ),
                transportConfig = channel.audioTransportConfig(source),
            ),
        )
    }

    override fun createAudioReceiver(
        channel: PreparedChannel,
        mode: AudioStreamMode,
        receiver: NativeSclAudioReceiverController,
        telemetry: AudioReceiverTelemetry,
        playbackTelemetry: NativeAudioPlaybackTelemetry,
    ): AndroidAudioReceiverPipeline {
        val source = channel.audioSource()
        return AndroidAudioReceiverPipeline(
            controller = DefaultAudioReceiverSessionController(
                receiverRuntimeController = receiver,
                decoderControllerFactory = { NativeOpusAudioDecoderController(telemetry) },
                playbackControllerFactory = { NativeOboeAudioPlaybackController(playbackTelemetry) },
            ),
            telemetrySources = listOf(telemetry, playbackTelemetry),
            config = AudioReceiverSessionConfig(
                receiverRuntimeConfig = channel.audioReceiverConfig(source),
            ),
        )
    }

    override fun createInputSender(
        channel: PreparedChannel,
        config: SetupConfiguration.Input,
        transport: NativeSclInputTransportController,
        telemetry: InputSenderTelemetry,
    ): AndroidInputSenderPipeline? {
        val surface = resources.clientInputSurface() ?: return null
        val reliability = config.config.toReliabilityConfig()
        return AndroidInputSenderPipeline(
            controller = ReverseInputSenderSessionController(
                captureController = AndroidInputCaptureController(appContext, telemetry),
                geometryProvider = resources.clientViewportGeometry,
                transportController = transport,
                telemetry = telemetry,
            ),
            telemetrySources = listOf(telemetry),
            surface = surface,
            config = ReverseInputSenderSessionConfig(
                captureConfig = InputCaptureConfig(enabledKinds = config.config.inputKinds.toInputKinds()),
                transportConfig = channel.inputTransportConfig(),
                reliabilityConfig = reliability,
            ),
        )
    }

    override fun createInputReceiver(
        channel: PreparedChannel,
        config: SetupConfiguration.Input,
        receiver: NativeSclInputReceiverController,
        telemetry: InputReceiverTelemetry,
    ): AndroidInputReceiverPipeline {
        val injection = AndroidInputInjectionController(appContext)
        val mapper = AndroidTargetInputMapper(
            injectionController = injection,
            displayGeometryProvider = AndroidTargetDisplayGeometryProvider(appContext),
            deviceResolver = AndroidTargetInputDeviceResolver(appContext),
            config = AndroidTargetInputMappingConfig(
                targetDisplayId = resources.inputTargetDisplayId,
            ),
            telemetry = telemetry,
        )
        val controller = ReverseInputReceiverSessionController(receiver, mapper, injection, telemetry)
        return AndroidInputReceiverPipeline(
            controller = controller,
            config = ReverseInputReceiverSessionConfig(
                receiverConfig = channel.inputReceiverConfig(),
                injectionConfig = InputInjectionConfig(targetUid = resources.inputTargetUid),
                reliabilityConfig = config.config.toReliabilityConfig(),
            ),
            onInputSafetyReset = controller::requestEmergencyReset,
            telemetrySources = listOf(telemetry),
        )
    }

    override fun createTelemetry(channel: PreparedChannel): SessionPipelineComponent? =
        resources.telemetryFactory?.invoke(channel)

    private fun PreparedChannel.videoTransportConfig(recovery: RecoveryConfiguration?): VideoTransportConfig =
        VideoTransportConfig(
            remoteAddress = remoteAddress,
            remotePort = remotePort(),
            localPort = localLease.localPort,
            maxWireDatagramSize = descriptor.maxSecureDatagramBytes,
            retransmissionCacheSlots = recovery?.retransmissionCacheSlots?.coerceAtLeast(1) ?: 1,
            fec = recovery.toVideoFec(),
        )

    private fun PreparedChannel.videoReceiverConfig(recovery: RecoveryConfiguration?): VideoReceiverRuntimeConfig =
        VideoReceiverRuntimeConfig(
            localAddress = localLease.binding.localAddress,
            localPort = localLease.localPort,
            remoteAddress = remoteAddress,
            remotePort = remotePort(),
            restrictRemoteEndpoint = true,
            maxWireDatagramSize = descriptor.maxSecureDatagramBytes,
            maxLogicalPayloadSize = MAX_VIDEO_PAYLOAD_BYTES,
            reassemblySlotCount = VIDEO_REASSEMBLY_SLOTS,
            readySlotCount = VIDEO_READY_SLOTS,
            lossSlotCount = VIDEO_LOSS_SLOTS,
            maxNacksPerPump = VIDEO_MAX_NACKS_PER_PUMP,
            reorderDelayUs = VIDEO_REORDER_DELAY_US,
            renackIntervalUs = VIDEO_RENACK_INTERVAL_US,
            maxNackAttempts = VIDEO_MAX_NACK_ATTEMPTS,
            fec = recovery.toVideoFec(),
            reassemblyTimeoutUs = VIDEO_REASSEMBLY_TIMEOUT_US,
            maxFrameRecoveryAgeUs = VIDEO_RECOVERY_AGE_US,
        )

    private fun PreparedChannel.audioTransportConfig(source: AudioCaptureSource): AudioTransportConfig =
        AudioTransportConfig(
            source = source,
            remoteAddress = remoteAddress,
            remotePort = remotePort(),
            localPort = localLease.localPort,
            maxWireDatagramSize = descriptor.maxSecureDatagramBytes,
        )

    private fun PreparedChannel.audioReceiverConfig(source: AudioCaptureSource): AudioReceiverRuntimeConfig =
        AudioReceiverRuntimeConfig(
            source = source,
            localAddress = localLease.binding.localAddress,
            localPort = localLease.localPort,
            remoteAddress = remoteAddress,
            remotePort = remotePort(),
            restrictRemoteEndpoint = true,
            maxWireDatagramSize = descriptor.maxSecureDatagramBytes,
        )

    private fun PreparedChannel.inputTransportConfig(): InputTransportConfig = InputTransportConfig(
        remoteAddress = remoteAddress,
        remotePort = remotePort(),
        localPort = localLease.localPort,
        maxWireDatagramSize = descriptor.maxSecureDatagramBytes,
    )

    private fun PreparedChannel.inputReceiverConfig(): InputReceiverConfig = InputReceiverConfig(
        localAddress = localLease.binding.localAddress,
        localPort = localLease.localPort,
        expectedRemoteAddress = remoteAddress,
        expectedRemotePort = remotePort(),
        maxWireDatagramSize = descriptor.maxSecureDatagramBytes,
    )

    private fun PreparedChannel.remotePort(): Int = if (descriptor.hostLocalPort == localLease.localPort) {
        descriptor.clientLocalPort
    } else {
        descriptor.hostLocalPort
    }

    private fun PreparedChannel.audioSource(): AudioCaptureSource = when (descriptor.kind) {
        SessionChannelKind.SystemAudio -> AudioCaptureSource.SystemAudio
        SessionChannelKind.MicrophoneAudio -> AudioCaptureSource.MicrophoneAudio
        else -> error("Not an audio channel")
    }

    private fun RecoveryConfiguration?.toVideoFec(): VideoTransportFecConfig {
        if (this == null || recoveryFlags and CapabilityBits.RECOVERY_FEC == 0) {
            return VideoTransportFecConfig.Disabled
        }
        return VideoTransportFecConfig(true, fecDataShards, fecParityShards)
    }

    private fun InputStreamConfiguration.toReliabilityConfig(): InputReliabilityConfig = InputReliabilityConfig(
        criticalCopies = criticalCopies,
        resetCopies = resetCopies,
        recentTransportSequenceCapacity = transportDuplicateWindow,
        recentSemanticDuplicateCapacity = semanticIdentityCache,
        networkReorderWaitUs = networkReorderWaitUs,
    )

    private fun Int.toInputKinds(): Set<InputDeviceKind> = buildSet {
        if (this@toInputKinds and CapabilityBits.INPUT_KEYBOARD != 0) add(InputDeviceKind.Keyboard)
        if (this@toInputKinds and CapabilityBits.INPUT_TOUCHSCREEN != 0) add(InputDeviceKind.Touchscreen)
        if (this@toInputKinds and CapabilityBits.INPUT_MOUSE != 0) add(InputDeviceKind.Mouse)
        if (this@toInputKinds and CapabilityBits.INPUT_GAMEPAD != 0) add(InputDeviceKind.Gamepad)
        if (this@toInputKinds and CapabilityBits.INPUT_STYLUS != 0) add(InputDeviceKind.Stylus)
        if (this@toInputKinds and CapabilityBits.INPUT_TOUCHPAD != 0) add(InputDeviceKind.Touchpad)
    }

    private companion object {
        const val DEFAULT_VIDEO_I_FRAME_INTERVAL_SECONDS = 1f
        const val MAX_VIDEO_PAYLOAD_BYTES = 128 * 1024
        const val VIDEO_REASSEMBLY_SLOTS = 8
        const val VIDEO_READY_SLOTS = 8
        const val VIDEO_LOSS_SLOTS = 128
        const val VIDEO_MAX_NACKS_PER_PUMP = 8
        const val VIDEO_REORDER_DELAY_US = 2_000L
        const val VIDEO_RENACK_INTERVAL_US = 8_000L
        const val VIDEO_MAX_NACK_ATTEMPTS = 3
        const val VIDEO_REASSEMBLY_TIMEOUT_US = 50_000L
        const val VIDEO_RECOVERY_AGE_US = 50_000L
    }
}
