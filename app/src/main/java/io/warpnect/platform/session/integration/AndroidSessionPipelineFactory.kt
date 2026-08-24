package io.warpnect.platform.session.integration

import android.view.View
import io.warpnect.NativeBridge
import io.warpnect.audio.session.AudioReceiverSessionConfig
import io.warpnect.audio.session.AudioReceiverSessionController
import io.warpnect.audio.session.AudioTransmitterSessionConfig
import io.warpnect.audio.session.AudioTransmitterSessionController
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import io.warpnect.audio.transport.AudioTransportConfig
import io.warpnect.input.session.ReverseInputReceiverSessionConfig
import io.warpnect.input.session.ReverseInputReceiverSessionController
import io.warpnect.input.session.ReverseInputSenderSessionConfig
import io.warpnect.input.session.ReverseInputSenderSessionController
import io.warpnect.input.transport.InputReceiverConfig
import io.warpnect.input.transport.InputTransportConfig
import io.warpnect.platform.audio.transport.NativeSclAudioReceiverController
import io.warpnect.platform.audio.transport.NativeSclAudioTransportController
import io.warpnect.platform.input.transport.NativeSclInputReceiverController
import io.warpnect.platform.input.transport.NativeSclInputTransportController
import io.warpnect.platform.session.channel.NativePreparedTransportKind
import io.warpnect.platform.session.channel.takeNativePreparedHandle
import io.warpnect.platform.video.transport.NativeSclVideoReceiverController
import io.warpnect.platform.video.transport.NativeSclVideoTransportController
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionRole
import io.warpnect.session.integration.SecureSessionIntegrationError
import io.warpnect.session.integration.SessionPipelineComponent
import io.warpnect.session.integration.SessionPipelineComponentResult
import io.warpnect.session.integration.SessionPipelineFactory
import io.warpnect.session.integration.SessionPipelineFactoryResult
import io.warpnect.session.integration.SessionPipelineStartPhase
import io.warpnect.session.setup.AudioStreamMode
import io.warpnect.session.setup.PreparedChannel
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.RecoveryConfiguration
import io.warpnect.session.setup.SetupConfiguration
import io.warpnect.session.setup.VideoStreamMode
import io.warpnect.telemetry.AudioReceiverTelemetry
import io.warpnect.telemetry.AudioSenderTelemetry
import io.warpnect.telemetry.InputReceiverTelemetry
import io.warpnect.telemetry.InputSenderTelemetry
import io.warpnect.telemetry.NativeAudioPlaybackTelemetry
import io.warpnect.telemetry.NativeChannelNetworkTelemetry
import io.warpnect.telemetry.NativeClockSyncTelemetry
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.VideoDecoderTelemetry
import io.warpnect.telemetry.VideoEncoderTelemetry
import io.warpnect.video.session.VideoReceiverSessionConfig
import io.warpnect.video.session.VideoReceiverSessionController
import io.warpnect.video.session.VideoTransmitterSessionConfig
import io.warpnect.video.session.VideoTransmitterSessionController
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoTransportConfig
import kotlinx.coroutines.runBlocking

/**
 * Platform-local construction supplied by the Android application composition. These methods
 * receive an already-adopted native transport, never an IP address or port entered by a user.
 * Implementations create the existing Phase 2/3/4 controllers and provide only local resources
 * such as a render target, selected display, or privileged Android service binding.
 */
interface AndroidSessionPipelineBindings {
    fun createVideoSender(
        channel: PreparedChannel,
        mode: VideoStreamMode,
        recovery: RecoveryConfiguration?,
        transport: NativeSclVideoTransportController,
        telemetry: VideoEncoderTelemetry,
    ): AndroidVideoSenderPipeline?

    fun createVideoReceiver(
        channel: PreparedChannel,
        mode: VideoStreamMode,
        recovery: RecoveryConfiguration?,
        receiver: NativeSclVideoReceiverController,
        telemetry: VideoDecoderTelemetry,
    ): AndroidVideoReceiverPipeline?

    fun createAudioSender(
        channel: PreparedChannel,
        mode: AudioStreamMode,
        transport: NativeSclAudioTransportController,
        telemetry: AudioSenderTelemetry,
    ): AndroidAudioSenderPipeline?

    fun createAudioReceiver(
        channel: PreparedChannel,
        mode: AudioStreamMode,
        receiver: NativeSclAudioReceiverController,
        telemetry: AudioReceiverTelemetry,
        playbackTelemetry: NativeAudioPlaybackTelemetry,
    ): AndroidAudioReceiverPipeline?

    fun createInputSender(
        channel: PreparedChannel,
        config: SetupConfiguration.Input,
        transport: NativeSclInputTransportController,
        telemetry: InputSenderTelemetry,
    ): AndroidInputSenderPipeline?

    fun createInputReceiver(
        channel: PreparedChannel,
        config: SetupConfiguration.Input,
        receiver: NativeSclInputReceiverController,
        telemetry: InputReceiverTelemetry,
    ): AndroidInputReceiverPipeline?

    /** Phase 6 owns telemetry redesign; selected V1 telemetry still needs an existing bounded runtime. */
    fun createTelemetry(channel: PreparedChannel): SessionPipelineComponent?
}

data class AndroidVideoSenderPipeline(
    val controller: VideoTransmitterSessionController,
    val config: VideoTransmitterSessionConfig,
    val onPathMigrationCommitted: () -> Unit = {},
    val telemetrySources: List<AutoCloseable> = emptyList(),
)

data class AndroidVideoReceiverPipeline(
    val controller: VideoReceiverSessionController,
    val config: VideoReceiverSessionConfig,
    val onPathMigrationCommitted: () -> Unit = {},
    val telemetrySources: List<AutoCloseable> = emptyList(),
)

data class AndroidAudioSenderPipeline(
    val controller: AudioTransmitterSessionController,
    val config: AudioTransmitterSessionConfig,
    val telemetrySources: List<AutoCloseable> = emptyList(),
)

data class AndroidAudioReceiverPipeline(
    val controller: AudioReceiverSessionController,
    val config: AudioReceiverSessionConfig,
    val telemetrySources: List<AutoCloseable> = emptyList(),
)

data class AndroidInputSenderPipeline(
    val controller: ReverseInputSenderSessionController,
    val surface: View,
    val config: ReverseInputSenderSessionConfig,
    val onInputSafetyReset: () -> Unit = {},
    val telemetrySources: List<AutoCloseable> = emptyList(),
)

data class AndroidInputReceiverPipeline(
    val controller: ReverseInputReceiverSessionController,
    val config: ReverseInputReceiverSessionConfig,
    val onInputSafetyReset: () -> Unit = {},
    val telemetrySources: List<AutoCloseable> = emptyList(),
)

/**
 * Production RFC-005I factory. It is deliberately a small adapter: RFC-005G has already bound
 * the path socket and derived the Channel context, and the existing Phase 2/3/4 controllers own
 * codec, audio, capture, mapping, and injection behaviour.
 */
class AndroidSessionPipelineFactory(
    private val bindings: AndroidSessionPipelineBindings,
    private val telemetryHub: TelemetryHub = TelemetryHub.disabled(),
) : SessionPipelineFactory {
    override fun create(bootstrap: PreparedSessionBootstrap): SessionPipelineFactoryResult {
        if (bootstrap.isClosed()) return SessionPipelineFactoryResult(SecureSessionIntegrationError.Closed)
        val components = ArrayList<SessionPipelineComponent>(bootstrap.channels.size)
        bootstrap.channels.forEach { channel ->
            val component = createChannelComponent(bootstrap, channel)
            if (component == null) {
                components.asReversed().forEach(SessionPipelineComponent::close)
                return SessionPipelineFactoryResult(errorFor(channel.descriptor.kind))
            }
            components += component
        }
        return SessionPipelineFactoryResult(SecureSessionIntegrationError.None, components)
    }

    private fun createChannelComponent(
        bootstrap: PreparedSessionBootstrap,
        channel: PreparedChannel,
    ): SessionPipelineComponent? {
        val role = bootstrap.localRole
        if (!channel.transport.protectedRequired || channel.transport.started) return null
        return when (channel.descriptor.kind) {
            SessionChannelKind.Video -> createVideo(bootstrap, role, channel)
            SessionChannelKind.SystemAudio,
            SessionChannelKind.MicrophoneAudio,
            -> createAudio(bootstrap, role, channel)
            SessionChannelKind.Input -> createInput(bootstrap, role, channel)
            // RFC-005I has no concrete native Telemetry adopter. The production capability
            // policy disables it, and an accidental selection must fail rather than leave the
            // RFC-005G native handle unowned.
            SessionChannelKind.Telemetry -> null
            SessionChannelKind.Control -> null
        }
    }

    private fun createVideo(
        bootstrap: PreparedSessionBootstrap,
        role: SessionRole,
        channel: PreparedChannel,
    ): SessionPipelineComponent? {
        val mode = channel.configuration.filterIsInstance<SetupConfiguration.Video>()
            .singleOrNull()?.mode ?: return null
        val recovery = channel.configuration.filterIsInstance<SetupConfiguration.Recovery>().singleOrNull()?.config
        return if (channel.isSender(role)) {
            val transport = NativeSclVideoTransportController()
            val handle = channel.transport.takeNativePreparedHandle(NativePreparedTransportKind.VideoSender)
            val networkTelemetry =
                attachNetworkTelemetry(bootstrap, channel, handle, NativePreparedTransportKind.VideoSender)
            if (transport.adoptPreparedTransport(handle) != io.warpnect.video.transport.VideoTransportError.None
            ) {
                networkTelemetry?.close()
                transport.close()
                null
            } else {
                val telemetry = VideoEncoderTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
                val pipeline = bindings.createVideoSender(channel, mode, recovery, transport, telemetry)
                if (pipeline == null) {
                    telemetry.close()
                    networkTelemetry?.close()
                    transport.close()
                    null
                } else if (!pipeline.config.matches(channel, mode)) {
                    pipeline.controller.close()
                    telemetry.close()
                    networkTelemetry?.close()
                    null
                } else {
                    channel.adoptLiveTransport { lease, remoteAddress, remotePort ->
                        transport.rebindLiveTransport(lease, remoteAddress, remotePort)
                    }
                    VideoSenderComponent(pipeline, networkTelemetry)
                }
            }
        } else {
            val receiver = NativeSclVideoReceiverController()
            val handle = channel.transport.takeNativePreparedHandle(NativePreparedTransportKind.VideoReceiver)
            val networkTelemetry =
                attachNetworkTelemetry(bootstrap, channel, handle, NativePreparedTransportKind.VideoReceiver)
            if (receiver.adoptPreparedTransport(handle) != io.warpnect.video.transport.VideoTransportError.None
            ) {
                networkTelemetry?.close()
                receiver.close()
                null
            } else {
                val telemetry = VideoDecoderTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
                val clockSyncTelemetry = attachClockSyncTelemetry(bootstrap, channel, receiver)
                val pipeline = bindings.createVideoReceiver(channel, mode, recovery, receiver, telemetry)
                if (pipeline == null) {
                    clockSyncTelemetry?.close()
                    telemetry.close()
                    networkTelemetry?.close()
                    receiver.close()
                    null
                } else if (!pipeline.config.matches(channel)) {
                    pipeline.controller.close()
                    clockSyncTelemetry?.close()
                    telemetry.close()
                    networkTelemetry?.close()
                    null
                } else {
                    channel.adoptLiveTransport { lease, remoteAddress, remotePort ->
                        receiver.rebindLiveTransport(lease, remoteAddress, remotePort)
                    }
                    val telemetrySources = clockSyncTelemetry?.let { pipeline.telemetrySources + it }
                        ?: pipeline.telemetrySources
                    VideoReceiverComponent(pipeline.copy(telemetrySources = telemetrySources), networkTelemetry)
                }
            }
        }
    }

    private fun createAudio(
        bootstrap: PreparedSessionBootstrap,
        role: SessionRole,
        channel: PreparedChannel,
    ): SessionPipelineComponent? {
        val configuration = when (channel.descriptor.kind) {
            SessionChannelKind.SystemAudio -> channel.configuration.filterIsInstance<SetupConfiguration.SystemAudio>()
                .singleOrNull()?.mode
            SessionChannelKind.MicrophoneAudio ->
                channel.configuration
                    .filterIsInstance<SetupConfiguration.MicrophoneAudio>()
                    .singleOrNull()?.mode
            else -> null
        } ?: return null
        return if (channel.isSender(role)) {
            val transport = NativeSclAudioTransportController()
            val handle = channel.transport.takeNativePreparedHandle(NativePreparedTransportKind.AudioSender)
            val networkTelemetry =
                attachNetworkTelemetry(bootstrap, channel, handle, NativePreparedTransportKind.AudioSender)
            if (transport.adoptPreparedTransport(handle) != io.warpnect.audio.transport.AudioTransportError.None
            ) {
                networkTelemetry?.close()
                transport.close()
                null
            } else {
                val telemetry = AudioSenderTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
                val pipeline = bindings.createAudioSender(channel, configuration, transport, telemetry)
                if (pipeline == null) {
                    telemetry.close()
                    networkTelemetry?.close()
                    transport.close()
                    null
                } else if (!pipeline.config.matches(channel, configuration)) {
                    pipeline.controller.close()
                    telemetry.close()
                    networkTelemetry?.close()
                    null
                } else {
                    channel.adoptLiveTransport { lease, remoteAddress, remotePort ->
                        transport.rebindLiveTransport(lease, remoteAddress, remotePort)
                    }
                    AudioSenderComponent(channel.descriptor.kind, pipeline, networkTelemetry)
                }
            }
        } else {
            val receiver = NativeSclAudioReceiverController()
            val handle = channel.transport.takeNativePreparedHandle(NativePreparedTransportKind.AudioReceiver)
            val networkTelemetry =
                attachNetworkTelemetry(bootstrap, channel, handle, NativePreparedTransportKind.AudioReceiver)
            if (receiver.adoptPreparedTransport(handle) != io.warpnect.audio.transport.AudioTransportError.None
            ) {
                networkTelemetry?.close()
                receiver.close()
                null
            } else {
                val telemetry = AudioReceiverTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
                val playbackTelemetry = NativeAudioPlaybackTelemetry.register(
                    telemetryHub,
                    bootstrap.channelScope(channel),
                )
                val pipeline = bindings.createAudioReceiver(
                    channel,
                    configuration,
                    receiver,
                    telemetry,
                    playbackTelemetry,
                )
                if (pipeline == null) {
                    telemetry.close()
                    playbackTelemetry.close()
                    networkTelemetry?.close()
                    receiver.close()
                    null
                } else if (!pipeline.config.matches(channel, configuration)) {
                    pipeline.controller.close()
                    telemetry.close()
                    playbackTelemetry.close()
                    networkTelemetry?.close()
                    null
                } else {
                    channel.adoptLiveTransport { lease, remoteAddress, remotePort ->
                        receiver.rebindLiveTransport(lease, remoteAddress, remotePort)
                    }
                    AudioReceiverComponent(channel.descriptor.kind, pipeline, networkTelemetry)
                }
            }
        }
    }

    private fun createInput(
        bootstrap: PreparedSessionBootstrap,
        role: SessionRole,
        channel: PreparedChannel,
    ): SessionPipelineComponent? {
        val configuration = channel.configuration.filterIsInstance<SetupConfiguration.Input>()
            .singleOrNull() ?: return null
        return if (channel.isSender(role)) {
            val transport = NativeSclInputTransportController()
            val handle = channel.transport.takeNativePreparedHandle(NativePreparedTransportKind.InputSender)
            val networkTelemetry =
                attachNetworkTelemetry(bootstrap, channel, handle, NativePreparedTransportKind.InputSender)
            if (transport.adoptPreparedTransport(handle) != io.warpnect.input.transport.InputTransportError.None
            ) {
                networkTelemetry?.close()
                transport.close()
                null
            } else {
                val telemetry = InputSenderTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
                val pipeline = bindings.createInputSender(channel, configuration, transport, telemetry)
                if (pipeline == null) {
                    telemetry.close()
                    networkTelemetry?.close()
                    transport.close()
                    null
                } else if (!pipeline.config.matches(channel)) {
                    pipeline.controller.close()
                    telemetry.close()
                    networkTelemetry?.close()
                    null
                } else {
                    channel.adoptLiveTransport { lease, remoteAddress, remotePort ->
                        transport.rebindLiveTransport(lease, remoteAddress, remotePort)
                    }
                    InputSenderComponent(pipeline, networkTelemetry)
                }
            }
        } else {
            val receiver = NativeSclInputReceiverController()
            val handle = channel.transport.takeNativePreparedHandle(NativePreparedTransportKind.InputReceiver)
            val networkTelemetry =
                attachNetworkTelemetry(bootstrap, channel, handle, NativePreparedTransportKind.InputReceiver)
            if (receiver.adoptPreparedTransport(handle) != io.warpnect.input.transport.InputReceiverError.None
            ) {
                networkTelemetry?.close()
                receiver.close()
                null
            } else {
                val telemetry = InputReceiverTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
                val pipeline = bindings.createInputReceiver(channel, configuration, receiver, telemetry)
                if (pipeline == null) {
                    telemetry.close()
                    networkTelemetry?.close()
                    receiver.close()
                    null
                } else if (!pipeline.config.matches(channel)) {
                    pipeline.controller.close()
                    telemetry.close()
                    networkTelemetry?.close()
                    null
                } else {
                    channel.adoptLiveTransport { lease, remoteAddress, remotePort ->
                        receiver.rebindLiveTransport(lease, remoteAddress, remotePort)
                    }
                    InputReceiverComponent(pipeline, networkTelemetry)
                }
            }
        }
    }

    private fun errorFor(kind: SessionChannelKind): SecureSessionIntegrationError = when (kind) {
        SessionChannelKind.Video -> SecureSessionIntegrationError.VideoPipelineStartFailed
        SessionChannelKind.SystemAudio -> SecureSessionIntegrationError.SystemAudioStartFailed
        SessionChannelKind.MicrophoneAudio -> SecureSessionIntegrationError.MicrophoneStartFailed
        SessionChannelKind.Input -> SecureSessionIntegrationError.InputPipelineStartFailed
        SessionChannelKind.Telemetry -> SecureSessionIntegrationError.TelemetryStartFailed
        SessionChannelKind.Control -> SecureSessionIntegrationError.PipelinePlanInvalid
    }

    private fun attachNetworkTelemetry(
        bootstrap: PreparedSessionBootstrap,
        channel: PreparedChannel,
        handle: Long,
        kind: NativePreparedTransportKind,
    ): NativeChannelNetworkTelemetry? {
        if (handle == 0L) return null
        val telemetry = NativeChannelNetworkTelemetry.register(
            telemetryHub,
            bootstrap.channelScope(channel),
        )
        val sourceId = telemetry.sourceId
        if (
            sourceId != null &&
            NativeBridge.channelNetworkTelemetryAttach(
                handle,
                kind.networkTelemetryKind,
                sourceId.value.toLong(),
            )
        ) {
            return telemetry
        }
        telemetry.close()
        return null
    }

    private fun attachClockSyncTelemetry(
        bootstrap: PreparedSessionBootstrap,
        channel: PreparedChannel,
        receiver: NativeSclVideoReceiverController,
    ): NativeClockSyncTelemetry? {
        val telemetry = NativeClockSyncTelemetry.register(telemetryHub, bootstrap.channelScope(channel))
        val sourceId = telemetry.sourceId
        if (sourceId != null && receiver.attachClockSyncTelemetry(sourceId)) return telemetry
        telemetry.close()
        return null
    }
}

private fun PreparedSessionBootstrap.channelScope(channel: PreparedChannel): TelemetryScope.Channel =
    TelemetryScope.Channel(
        sessionId = sessionId,
        generation = generation,
        channelId = channel.descriptor.channelId,
        channelKind = channel.descriptor.kind,
        direction = channel.descriptor.direction,
    )

private class VideoSenderComponent(
    private val pipeline: AndroidVideoSenderPipeline,
    private val networkTelemetry: AutoCloseable?,
) : SessionPipelineComponent {
    override val name = "video-sender"
    override val phase = SessionPipelineStartPhase.PhysicalSource
    override val channelKinds = setOf(SessionChannelKind.Video)
    override fun start() = SessionPipelineComponentResult(
        if (runBlocking { pipeline.controller.start(pipeline.config) }.isSuccess) {
            SecureSessionIntegrationError.None
        } else {
            SecureSessionIntegrationError.VideoPipelineStartFailed
        },
    )
    override fun stop() {
        runBlocking { pipeline.controller.stop() }
    }
    override fun onPathMigrationCommitted() = pipeline.onPathMigrationCommitted()
    override fun close() {
        pipeline.controller.close()
        pipeline.telemetrySources.forEach(AutoCloseable::close)
        networkTelemetry?.close()
    }
}

private class VideoReceiverComponent(
    private val pipeline: AndroidVideoReceiverPipeline,
    private val networkTelemetry: AutoCloseable?,
) : SessionPipelineComponent {
    override val name = "video-receiver"
    override val phase = SessionPipelineStartPhase.InboundTransport
    override val channelKinds = setOf(SessionChannelKind.Video)
    override fun start() = SessionPipelineComponentResult(
        if (runBlocking { pipeline.controller.start(pipeline.config) }.isSuccess) {
            SecureSessionIntegrationError.None
        } else {
            SecureSessionIntegrationError.VideoPipelineStartFailed
        },
    )
    override fun stop() {
        runBlocking { pipeline.controller.stop() }
    }
    override fun onPathMigrationCommitted() = pipeline.onPathMigrationCommitted()
    override fun close() {
        pipeline.controller.close()
        pipeline.telemetrySources.forEach(AutoCloseable::close)
        networkTelemetry?.close()
    }
}

private class AudioSenderComponent(
    private val kind: SessionChannelKind,
    private val pipeline: AndroidAudioSenderPipeline,
    private val networkTelemetry: AutoCloseable?,
) : SessionPipelineComponent {
    override val name = "${kind.name.lowercase()}-sender"
    override val phase = SessionPipelineStartPhase.PhysicalSource
    override val channelKinds = setOf(kind)
    override fun start() = SessionPipelineComponentResult(
        if (runBlocking { pipeline.controller.start(pipeline.config) }.isSuccess) {
            SecureSessionIntegrationError.None
        } else {
            kind.startError()
        },
    )
    override fun stop() {
        runBlocking { pipeline.controller.stop() }
    }
    override fun close() {
        pipeline.controller.close()
        pipeline.telemetrySources.forEach(AutoCloseable::close)
        networkTelemetry?.close()
    }
}

private class AudioReceiverComponent(
    private val kind: SessionChannelKind,
    private val pipeline: AndroidAudioReceiverPipeline,
    private val networkTelemetry: AutoCloseable?,
) : SessionPipelineComponent {
    override val name = "${kind.name.lowercase()}-receiver"
    override val phase = SessionPipelineStartPhase.InboundTransport
    override val channelKinds = setOf(kind)
    override fun start() = SessionPipelineComponentResult(
        if (runBlocking { pipeline.controller.start(pipeline.config) }.isSuccess) {
            SecureSessionIntegrationError.None
        } else {
            kind.startError()
        },
    )
    override fun stop() {
        runBlocking { pipeline.controller.stop() }
    }
    override fun close() {
        pipeline.controller.close()
        pipeline.telemetrySources.forEach(AutoCloseable::close)
        networkTelemetry?.close()
    }
}

private class InputSenderComponent(
    private val pipeline: AndroidInputSenderPipeline,
    private val networkTelemetry: AutoCloseable?,
) : SessionPipelineComponent {
    override val name = "input-sender"
    override val phase = SessionPipelineStartPhase.PhysicalSource
    override val channelKinds = setOf(SessionChannelKind.Input)
    override fun start() = SessionPipelineComponentResult(
        if (pipeline.controller.start(pipeline.surface, pipeline.config).isSuccess) {
            SecureSessionIntegrationError.None
        } else {
            SecureSessionIntegrationError.InputPipelineStartFailed
        },
    )
    override fun stop() {
        pipeline.controller.stop()
    }
    override fun onInputSafetyReset() = pipeline.onInputSafetyReset()
    override fun close() {
        pipeline.controller.close()
        pipeline.telemetrySources.forEach(AutoCloseable::close)
        networkTelemetry?.close()
    }
}

private class InputReceiverComponent(
    private val pipeline: AndroidInputReceiverPipeline,
    private val networkTelemetry: AutoCloseable?,
) : SessionPipelineComponent {
    override val name = "input-receiver"
    override val phase = SessionPipelineStartPhase.InboundTransport
    override val channelKinds = setOf(SessionChannelKind.Input)
    override fun start() = SessionPipelineComponentResult(
        if (runBlocking { pipeline.controller.start(pipeline.config) }.isSuccess) {
            SecureSessionIntegrationError.None
        } else {
            SecureSessionIntegrationError.InputPipelineStartFailed
        },
    )
    override fun stop() {
        pipeline.controller.stop()
    }
    override fun onInputSafetyReset() = pipeline.onInputSafetyReset()
    override fun close() {
        pipeline.controller.close()
        pipeline.telemetrySources.forEach(AutoCloseable::close)
        networkTelemetry?.close()
    }
}

private fun PreparedChannel.isSender(role: SessionRole): Boolean = when (descriptor.direction) {
    SessionChannelDirection.HostToClient -> role == SessionRole.Host
    SessionChannelDirection.ClientToHost -> role == SessionRole.Client
    SessionChannelDirection.Bidirectional -> false
}

private fun VideoTransmitterSessionConfig.matches(channel: PreparedChannel, mode: VideoStreamMode): Boolean =
    encoderRequest.width == mode.width && encoderRequest.height == mode.height &&
        encoderRequest.frameRate == mode.fps && encoderRequest.bitrateBps.toLong() == mode.bitrateBps &&
        transportConfig.matches(channel)

private fun VideoReceiverSessionConfig.matches(channel: PreparedChannel): Boolean =
    receiverRuntimeConfig.matches(channel)

private fun AudioTransmitterSessionConfig.matches(channel: PreparedChannel, mode: AudioStreamMode): Boolean =
    encoderRequest.sampleRateHz == mode.sampleRateHz && encoderRequest.frameDurationUs == mode.frameDurationUs &&
        encoderRequest.channelCount == mode.channelCount && encoderRequest.bitrateBps.toLong() == mode.bitrateBps &&
        transportConfig.matches(channel)

private fun AudioReceiverSessionConfig.matches(channel: PreparedChannel, mode: AudioStreamMode): Boolean =
    receiverRuntimeConfig.matches(channel) && receiverRuntimeConfig.source == channel.audioSource() &&
        mode.sampleRateHz == 48_000

private fun ReverseInputSenderSessionConfig.matches(channel: PreparedChannel): Boolean =
    transportConfig.matches(channel)

private fun ReverseInputReceiverSessionConfig.matches(channel: PreparedChannel): Boolean =
    receiverConfig.matches(channel)

private fun VideoTransportConfig.matches(channel: PreparedChannel): Boolean =
    remoteAddress == channel.remoteAddress && remotePort == channel.remotePort() &&
        localPort == channel.localLease.localPort && maxWireDatagramSize == channel.descriptor.maxSecureDatagramBytes

private fun VideoReceiverRuntimeConfig.matches(channel: PreparedChannel): Boolean =
    localAddress == channel.localLease.binding.localAddress && localPort == channel.localLease.localPort &&
        remoteAddress == channel.remoteAddress && remotePort == channel.remotePort() && restrictRemoteEndpoint &&
        maxWireDatagramSize == channel.descriptor.maxSecureDatagramBytes

private fun AudioTransportConfig.matches(channel: PreparedChannel): Boolean =
    source == channel.audioSource() && remoteAddress == channel.remoteAddress && remotePort == channel.remotePort() &&
        localPort == channel.localLease.localPort && maxWireDatagramSize == channel.descriptor.maxSecureDatagramBytes

private fun AudioReceiverRuntimeConfig.matches(channel: PreparedChannel): Boolean =
    localAddress == channel.localLease.binding.localAddress && localPort == channel.localLease.localPort &&
        remoteAddress == channel.remoteAddress && remotePort == channel.remotePort() && restrictRemoteEndpoint &&
        maxWireDatagramSize == channel.descriptor.maxSecureDatagramBytes

private fun InputTransportConfig.matches(channel: PreparedChannel): Boolean =
    remoteAddress == channel.remoteAddress && remotePort == channel.remotePort() &&
        localPort == channel.localLease.localPort && maxWireDatagramSize == channel.descriptor.maxSecureDatagramBytes

private fun InputReceiverConfig.matches(channel: PreparedChannel): Boolean =
    localAddress == channel.localLease.binding.localAddress && localPort == channel.localLease.localPort &&
        expectedRemoteAddress == channel.remoteAddress && expectedRemotePort == channel.remotePort() &&
        maxWireDatagramSize == channel.descriptor.maxSecureDatagramBytes

private fun PreparedChannel.remotePort(): Int = if (descriptor.direction == SessionChannelDirection.HostToClient) {
    if (descriptor.hostLocalPort == localLease.localPort) descriptor.clientLocalPort else descriptor.hostLocalPort
} else if (descriptor.hostLocalPort == localLease.localPort) {
    descriptor.clientLocalPort
} else {
    descriptor.hostLocalPort
}

private fun PreparedChannel.audioSource(): io.warpnect.audio.capture.AudioCaptureSource = when (descriptor.kind) {
    SessionChannelKind.SystemAudio -> io.warpnect.audio.capture.AudioCaptureSource.SystemAudio
    SessionChannelKind.MicrophoneAudio -> io.warpnect.audio.capture.AudioCaptureSource.MicrophoneAudio
    else -> error("Not an audio channel")
}

private fun SessionChannelKind.startError(): SecureSessionIntegrationError = when (this) {
    SessionChannelKind.SystemAudio -> SecureSessionIntegrationError.SystemAudioStartFailed
    SessionChannelKind.MicrophoneAudio -> SecureSessionIntegrationError.MicrophoneStartFailed
    else -> SecureSessionIntegrationError.PipelineStartFailed
}
