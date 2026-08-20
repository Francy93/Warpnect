package io.warpnect.platform.session.channel

import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.platform.session.security.nativeProtectionHandle
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.setup.ChannelTransportPreparationRequest
import io.warpnect.session.setup.ChannelTransportPreparationResult
import io.warpnect.session.setup.PreparedChannelTransport
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SetupConfiguration

data class NativePreparedChannelConfig(
    val maxVideoPayloadBytes: Int = 128 * 1_024,
    val videoReassemblySlots: Int = 8,
    val videoReadySlots: Int = 8,
    val videoLossSlots: Int = 128,
    val maxVideoNacksPerPump: Int = 8,
    val videoReorderDelayUs: Long = 2_000L,
    val videoRenackIntervalUs: Long = 8_000L,
    val maxVideoNackAttempts: Int = 3,
    val videoReassemblyTimeoutUs: Long = 50_000L,
    val videoRecoveryAgeUs: Long = 50_000L,
    val videoResyncCooldownUs: Long = 250_000L,
    val videoClockSyncIntervalUs: Long = 1_000_000L,
    val videoClockSyncSamples: Int = 16,
    val maxAudioPayloadBytes: Int = 4_096,
    val audioReassemblySlots: Int = 4,
    val audioReadySlots: Int = 4,
    val audioReassemblyTimeoutUs: Long = 20_000L,
) {
    fun isValid(): Boolean = maxVideoPayloadBytes > 0 && videoReassemblySlots > 0 && videoReadySlots > 0 &&
        videoLossSlots > 0 && maxVideoNacksPerPump > 0 && videoReorderDelayUs >= 0L &&
        videoRenackIntervalUs >= 0L && maxVideoNackAttempts > 0 && videoReassemblyTimeoutUs >= 0L &&
        videoRecoveryAgeUs >= 0L && videoResyncCooldownUs >= 0L && videoClockSyncIntervalUs >= 0L &&
        videoClockSyncSamples >= 0 && maxAudioPayloadBytes > 0 && audioReassemblySlots > 0 &&
        audioReadySlots > 0 && audioReassemblyTimeoutUs >= 0L
}

/**
 * Builds stopped native transports on the socket already advertised by WNSN. Packet protection is
 * attached below SCL framing in native code; this preparation starts no capture or receive worker.
 */
class NativePreparedChannelTransportPreparer(
    private val config: NativePreparedChannelConfig = NativePreparedChannelConfig(),
) : io.warpnect.session.setup.ChannelTransportPreparer {
    override fun prepare(request: ChannelTransportPreparationRequest): ChannelTransportPreparationResult {
        if (!config.isValid()) return failure(SessionSetupError.InvalidConfig)
        val endpointHandle = (request.localEndpoint as? NativeChannelEndpointLeaseAccess)?.nativeEndpointHandle() ?: 0L
        val protectionHandle = nativeProtectionHandle(request.sessionProtectionRuntime)
        val channelMatches = request.protection.channelId == request.descriptor.channelId
        if (endpointHandle == 0L || protectionHandle == 0L || !channelMatches) {
            return failure(SessionSetupError.ProtectionContextFailed)
        }
        val localPort = request.localEndpoint.localPort
        val remotePort = request.descriptor.remotePortFor(request.localRole)
        val channelId = request.descriptor.channelId.value.toLong()
        val secureBudget = request.descriptor.maxSecureDatagramBytes
        val sender = request.descriptor.isSender(request.localRole)
        val recovery = request.configurations.filterIsInstance<SetupConfiguration.Recovery>()
            .singleOrNull()?.config
        val handle = when (request.descriptor.kind) {
            SessionChannelKind.Video -> prepareVideo(
                request,
                sender,
                localPort,
                remotePort,
                secureBudget,
                protectionHandle,
                channelId,
                endpointHandle,
                recovery,
            )
            SessionChannelKind.SystemAudio -> prepareAudio(
                request,
                sender,
                AudioCaptureSource.SystemAudio,
                localPort,
                remotePort,
                secureBudget,
                protectionHandle,
                channelId,
                endpointHandle,
            )
            SessionChannelKind.MicrophoneAudio -> prepareAudio(
                request,
                sender,
                AudioCaptureSource.MicrophoneAudio,
                localPort,
                remotePort,
                secureBudget,
                protectionHandle,
                channelId,
                endpointHandle,
            )
            SessionChannelKind.Input -> prepareInput(
                request,
                sender,
                localPort,
                remotePort,
                secureBudget,
                protectionHandle,
                channelId,
                endpointHandle,
            )
            SessionChannelKind.Telemetry -> NativePreparedTransportHandle(
                NativeBridge.preparedSecureChannelCreate(
                    request.remoteAddress,
                    remotePort,
                    localPort,
                    secureBudget,
                    protectionHandle,
                    channelId,
                    endpointHandle,
                ),
                NativePreparedTransportKind.Generic,
            )
            SessionChannelKind.Control -> return failure(SessionSetupError.ChannelPlanInvalid)
        }
        return if (handle.handle == 0L) {
            failure(SessionSetupError.TransportPreparationFailed)
        } else {
            // Native construction has consumed the pre-bound socket. Keep the lease only as
            // channel descriptor metadata; the stopped transport is now its sole socket owner.
            (request.localEndpoint as? NativeChannelEndpointLeaseAccess)?.markNativeEndpointAdopted(endpointHandle)
            ChannelTransportPreparationResult(SessionSetupError.None, handle)
        }
    }

    private fun prepareVideo(
        request: ChannelTransportPreparationRequest,
        sender: Boolean,
        localPort: Int,
        remotePort: Int,
        secureBudget: Int,
        protectionHandle: Long,
        channelId: Long,
        endpointHandle: Long,
        recovery: io.warpnect.session.setup.RecoveryConfiguration?,
    ): NativePreparedTransportHandle {
        val fecEnabled = ((recovery?.recoveryFlags ?: 0) and CapabilityBits.RECOVERY_FEC) != 0
        val retransmissionSlots = recovery?.retransmissionCacheSlots?.takeIf { it > 0 } ?: 1
        return if (sender) {
            NativePreparedTransportHandle(
                NativeBridge.videoTransportCreate(
                    request.remoteAddress,
                    remotePort,
                    localPort,
                    secureBudget,
                    0L,
                    0L,
                    0L,
                    retransmissionSlots,
                    fecEnabled,
                    recovery?.fecDataShards ?: 0,
                    recovery?.fecParityShards ?: 0,
                    config.videoResyncCooldownUs,
                    protectionHandle,
                    channelId,
                    endpointHandle,
                ),
                NativePreparedTransportKind.VideoSender,
            )
        } else {
            NativePreparedTransportHandle(
                NativeBridge.videoReceiverCreate(
                    request.localEndpoint.binding.localAddress,
                    localPort,
                    request.remoteAddress,
                    remotePort,
                    true,
                    secureBudget,
                    config.maxVideoPayloadBytes,
                    config.videoReassemblySlots,
                    config.videoReadySlots,
                    config.videoLossSlots,
                    config.maxVideoNacksPerPump,
                    config.videoReorderDelayUs,
                    config.videoRenackIntervalUs,
                    config.maxVideoNackAttempts,
                    0L,
                    fecEnabled,
                    recovery?.fecDataShards ?: 0,
                    recovery?.fecParityShards ?: 0,
                    config.videoReassemblyTimeoutUs,
                    config.videoRecoveryAgeUs,
                    config.videoResyncCooldownUs,
                    config.videoClockSyncIntervalUs,
                    config.videoClockSyncSamples,
                    protectionHandle,
                    channelId,
                    endpointHandle,
                ),
                NativePreparedTransportKind.VideoReceiver,
            )
        }
    }

    private fun prepareAudio(
        request: ChannelTransportPreparationRequest,
        sender: Boolean,
        source: AudioCaptureSource,
        localPort: Int,
        remotePort: Int,
        secureBudget: Int,
        protectionHandle: Long,
        channelId: Long,
        endpointHandle: Long,
    ): NativePreparedTransportHandle = if (sender) {
        NativePreparedTransportHandle(
            NativeBridge.audioTransportCreate(
                request.remoteAddress,
                remotePort,
                localPort,
                secureBudget,
                0L,
                source.ordinal,
                protectionHandle,
                channelId,
                endpointHandle,
            ),
            NativePreparedTransportKind.AudioSender,
        )
    } else {
        NativePreparedTransportHandle(
            NativeBridge.audioReceiverCreate(
                request.localEndpoint.binding.localAddress,
                localPort,
                request.remoteAddress,
                remotePort,
                true,
                secureBudget,
                config.maxAudioPayloadBytes,
                config.audioReassemblySlots,
                config.audioReadySlots,
                config.audioReassemblyTimeoutUs,
                source.ordinal,
                protectionHandle,
                channelId,
                endpointHandle,
            ),
            NativePreparedTransportKind.AudioReceiver,
        )
    }

    private fun prepareInput(
        request: ChannelTransportPreparationRequest,
        sender: Boolean,
        localPort: Int,
        remotePort: Int,
        secureBudget: Int,
        protectionHandle: Long,
        channelId: Long,
        endpointHandle: Long,
    ): NativePreparedTransportHandle = if (sender) {
        NativePreparedTransportHandle(
            NativeBridge.inputTransportCreate(
                request.remoteAddress,
                remotePort,
                localPort,
                secureBudget,
                0L,
                protectionHandle,
                channelId,
                endpointHandle,
            ),
            NativePreparedTransportKind.InputSender,
        )
    } else {
        NativePreparedTransportHandle(
            NativeBridge.inputReceiverCreate(
                request.localEndpoint.binding.localAddress,
                localPort,
                request.remoteAddress,
                remotePort,
                secureBudget,
                protectionHandle,
                channelId,
                endpointHandle,
            ),
            NativePreparedTransportKind.InputReceiver,
        )
    }

    private fun failure(error: SessionSetupError) = ChannelTransportPreparationResult(error)
}

/**
 * Platform-only transport identity used for the one-time 005G -> Phase 2/3/4 handle transfer.
 * It deliberately does not leak into portable session setup models.
 */
internal enum class NativePreparedTransportKind {
    VideoSender,
    VideoReceiver,
    AudioSender,
    AudioReceiver,
    InputSender,
    InputReceiver,
    Generic,
}

/**
 * Narrow ownership bridge for a stopped native transport prepared by RFC-005G.  A consumer must
 * take the handle exactly once; after that the receiving phase controller owns its destruction.
 */
internal interface NativePreparedChannelTransportAccess {
    fun takeNativeHandle(expectedKind: NativePreparedTransportKind): Long
}

internal class NativePreparedTransportHandle(
    var handle: Long,
    private val kind: NativePreparedTransportKind,
) : PreparedChannelTransport, NativePreparedChannelTransportAccess {
    private val lock = Any()

    override val protectedRequired: Boolean = true
    override val started: Boolean = false

    override fun takeNativeHandle(expectedKind: NativePreparedTransportKind): Long = synchronized(lock) {
        if (kind != expectedKind) return@synchronized 0L
        val transferred = handle
        handle = 0L
        transferred
    }

    override fun close() = synchronized(lock) {
        if (handle == 0L) return
        when (kind) {
            NativePreparedTransportKind.VideoSender -> NativeBridge.videoTransportDestroy(handle)
            NativePreparedTransportKind.VideoReceiver -> NativeBridge.videoReceiverDestroy(handle)
            NativePreparedTransportKind.AudioSender -> NativeBridge.audioTransportDestroy(handle)
            NativePreparedTransportKind.AudioReceiver -> NativeBridge.audioReceiverDestroy(handle)
            NativePreparedTransportKind.InputSender -> NativeBridge.inputTransportDestroy(handle)
            NativePreparedTransportKind.InputReceiver -> NativeBridge.inputReceiverDestroy(handle)
            NativePreparedTransportKind.Generic -> NativeBridge.preparedSecureChannelDestroy(handle)
        }
        handle = 0L
    }
}

/** Returns zero when a prepared transport is not the expected native secure channel handle. */
internal fun PreparedChannelTransport.takeNativePreparedHandle(expectedKind: NativePreparedTransportKind): Long =
    (this as? NativePreparedChannelTransportAccess)?.takeNativeHandle(expectedKind) ?: 0L

/** The live native transport consumes this already-bound RFC-005H replacement socket exactly once. */
internal fun io.warpnect.session.setup.ChannelEndpointLease.nativeEndpointHandleForLiveRebind(): Long =
    (this as? NativeChannelEndpointLeaseAccess)?.nativeEndpointHandle() ?: 0L

/** Called only after a successful JNI rebind has consumed this prepared socket. */
internal fun io.warpnect.session.setup.ChannelEndpointLease.markNativeEndpointAdopted(handle: Long) {
    (this as? NativeChannelEndpointLeaseAccess)?.markNativeEndpointAdopted(handle)
}

private fun io.warpnect.session.setup.ChannelDescriptor.remotePortFor(role: SessionRole): Int =
    if (role == SessionRole.Host) clientLocalPort else hostLocalPort

private fun io.warpnect.session.setup.ChannelDescriptor.isSender(role: SessionRole): Boolean = when (direction) {
    SessionChannelDirection.HostToClient -> role == SessionRole.Host
    SessionChannelDirection.ClientToHost -> role == SessionRole.Client
    SessionChannelDirection.Bidirectional -> false
}
