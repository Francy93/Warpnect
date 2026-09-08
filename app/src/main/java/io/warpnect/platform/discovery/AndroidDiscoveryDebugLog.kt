package io.warpnect.platform.discovery

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.warpnect.platform.session.integration.VideoPipelineStartDebugEvent
import io.warpnect.platform.session.integration.VideoPipelineStartDebugEventKind
import io.warpnect.platform.video.decoder.VideoDecoderDebugEvent
import io.warpnect.platform.video.decoder.VideoDecoderPresentationObservation
import io.warpnect.platform.video.encoder.CbrCapabilityDecision
import io.warpnect.platform.video.encoder.CbrCapabilityDecisionSource
import io.warpnect.platform.video.transport.VideoTransportDebugEvent
import io.warpnect.session.capability.CapabilityNegotiationDebugEvent
import io.warpnect.session.capability.CapabilityNegotiationDebugEventKind
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.handshake.SessionHandshakeDebugEvent
import io.warpnect.session.handshake.SessionHandshakeDebugEventKind
import io.warpnect.session.handshake.SessionHandshakeError
import io.warpnect.session.integration.SessionStartupDebugEvent
import io.warpnect.session.integration.SessionStartupDebugEventKind
import io.warpnect.session.lifecycle.SessionLifecycleDebugEvent
import io.warpnect.session.lifecycle.SessionLifecycleDebugEventKind
import io.warpnect.session.pairing.PairingDebugEvent
import io.warpnect.session.pairing.PairingDebugEventKind
import io.warpnect.session.setup.SessionSetupDebugEvent
import io.warpnect.session.setup.SessionSetupDebugEventKind
import io.warpnect.video.session.VideoReceiverSessionSnapshot
import io.warpnect.video.session.VideoSessionError
import io.warpnect.video.session.VideoTransmitterSessionSnapshot

/** Debug-build-only, control-plane observability for physical discovery validation. */
internal class AndroidDiscoveryDebugLog(context: Context) {
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    internal val isEnabled: Boolean
        get() = enabled

    fun event(backend: DiscoveryRouteKind, event: String, error: DiscoveryError? = null, rawCode: Int? = null) {
        if (!enabled) return
        val message = buildString {
            append("backend=")
            append(backend.name.uppercase())
            append(" event=")
            append(event)
            error?.takeUnless { it == DiscoveryError.None }?.let {
                append(" reason=")
                append(it.name)
            }
            rawCode?.let {
                append(" code=")
                append(it)
            }
        }
        Log.d(TAG, message)
    }

    fun routeObserved(backend: DiscoveryRouteKind) = event(backend, "route_observed")

    fun presenceAccepted(count: Int) {
        if (enabled) Log.d(TAG, "event=presence_accepted count=$count")
    }

    fun snapshotPublishRequested(count: Int) {
        if (enabled) Log.d(TAG, "event=snapshot_publish_requested host_count=$count")
    }

    fun snapshotPublished(count: Int) {
        if (enabled) Log.d(TAG, "event=snapshot_published host_count=$count")
    }

    fun uiStateReceived(count: Int) {
        if (enabled) Log.d(TAG, "event=ui_state_received host_count=$count")
    }

    fun chooserVisible(count: Int) {
        if (enabled) Log.d(TAG, "event=chooser_visible host_count=$count")
    }

    fun chooserModel(count: Int) {
        if (enabled) Log.d(TAG, "event=chooser_model host_count=$count")
    }

    fun hostRowsCreated(count: Int) {
        if (enabled) Log.d(TAG, "event=host_rows_created count=$count")
    }

    fun hostRowComposed() {
        if (enabled) Log.d(TAG, "event=host_row_composed")
    }

    fun hostLifecycleStarted() {
        if (enabled) Log.d(TAG, "event=host_lifecycle_started")
    }

    fun hostLifecycleStopped() {
        if (enabled) Log.d(TAG, "event=host_lifecycle_stopped")
    }

    fun hostRegistrationActive() {
        if (enabled) Log.d(TAG, "event=host_registration_active")
    }

    fun hostRegistrationLost() {
        if (enabled) Log.d(TAG, "event=host_registration_lost")
    }

    fun p2pChannelDisconnected() {
        if (enabled) Log.d(TAG, "event=p2p_channel_disconnected")
    }

    fun handshakeStarted() {
        if (enabled) Log.d(TAG, "event=handshake_started")
    }

    fun handshakeFailed(error: SessionHandshakeError) {
        if (enabled) Log.d(TAG, "event=handshake_failed reason=${error.name}")
    }

    fun handshake(event: SessionHandshakeDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            event.messageType?.let {
                append(" action=")
                append(it.name)
            }
            event.messageSequence?.let {
                append(" sequence=")
                append(it)
            }
            event.error?.let {
                append(" reason=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    fun presenceCount(count: Int) {
        if (enabled) Log.d(TAG, "event=presence_count count=$count")
    }

    fun pairing(event: PairingDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            event.messageType?.let {
                append(" action=")
                append(it.name)
            }
            event.error?.let {
                append(" reason=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    fun sessionStartup(event: SessionStartupDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            event.error?.let {
                append(" reason=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    fun sessionLifecycle(event: SessionLifecycleDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            event.error?.let {
                append(" reason=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    fun videoPipelineStart(event: VideoPipelineStartDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            event.result?.failure?.takeUnless { it.error == VideoSessionError.None }?.let { failure ->
                append(" source=")
                append(failure.source.name)
                append(" reason=")
                append(failure.error.name)
                val detail = listOfNotNull(
                    failure.captureError,
                    failure.encoderError,
                    failure.transportError,
                    failure.decoderError,
                )
                    .singleOrNull()
                detail?.let {
                    append(" detail=")
                    append(it)
                }
            }
        }
        Log.d(TAG, message)
    }

    fun firstVideoFrameEncoded() {
        if (!enabled) return
        Log.d(TAG, "event=first_frame_encoded")
    }

    fun videoTransport(event: VideoTransportDebugEvent) {
        if (!enabled) return
        Log.d(TAG, "event=${event.logName}")
    }

    fun videoDecoder(event: VideoDecoderDebugEvent) {
        if (!enabled) return
        Log.d(TAG, "event=${event.logName}")
    }

    fun videoDecoderPresentation(observation: VideoDecoderPresentationObservation) {
        if (!enabled) return
        val message = buildString {
            append("event=decoder_presentation stage=")
            append(observation.stage.name)
            append(" local_monotonic_ns=")
            append(observation.localMonotonicNs)
            append(" pts_us=")
            append(observation.presentationTimeUs)
            observation.renderedToSurface?.let {
                append(" render_to_surface=")
                append(it)
            }
            observation.scheduledRenderTimestampNs?.let {
                append(" scheduled_render_ns=")
                append(it)
                append(" scheduled_minus_now_ns=")
                append(it - observation.localMonotonicNs)
            }
            observation.codecCallbackNanoTime?.let {
                append(" codec_callback_ns=")
                append(it)
            }
        }
        Log.d(TAG, message)
    }

    fun videoAccessUnitReady(presentationTimeUs: Long, keyframe: Boolean, localMonotonicNs: Long) {
        if (!enabled) return
        Log.d(
            TAG,
            "event=client_remote_access_unit_ready local_monotonic_ns=$localMonotonicNs " +
                "pts_us=$presentationTimeUs keyframe=$keyframe",
        )
    }

    /** Low-cadence local pipeline counters for backlog investigations; no media or peer data. */
    fun videoPipelineSenderRuntime(snapshot: VideoTransmitterSessionSnapshot) {
        if (!enabled) return
        val encoder = snapshot.encoder
        val transport = snapshot.transport
        Log.d(
            TAG,
            "event=video_runtime role=host local_monotonic_ms=${SystemClock.elapsedRealtime()} " +
                "session_state=${snapshot.state} capture_state=${snapshot.capture?.state} " +
                "encoder_au=${encoder?.accessUnitsEncoded} " +
                "encoder_keyframes=${encoder?.keyFramesEncoded} " +
                "encoder_bytes=${encoder?.encodedBytes} " +
                "encoder_pts_us=${encoder?.lastPresentationTimeUs} encoder_errors=${encoder?.runtimeErrors} " +
                "transport_au=${transport?.accessUnitsSubmitted} " +
                "transport_keyframes=${transport?.keyframesSubmitted} " +
                "transport_generated=${transport?.videoDatagramsGenerated} " +
                "transport_sent=${transport?.videoDatagramsSent} transport_bytes=${transport?.videoBytesSent} " +
                "transport_retransmissions=${transport?.retransmissions} " +
                "transport_failures=${transport?.accessUnitsFailed} " +
                "transport_resync_received=${transport?.resyncRequestsReceived} " +
                "transport_config_resends=${transport?.streamConfigResends} " +
                "transport_keyframe_requests=${transport?.keyFrameRequestsReceived} " +
                "control_running=${snapshot.control?.running} " +
                "control_pumps=${snapshot.control?.pumpIterations} " +
                "control_transport_errors=${snapshot.control?.transportErrors} " +
                "control_keyframes_forwarded=${snapshot.control?.keyFrameRequestsForwarded} " +
                "control_keyframe_failures=${snapshot.control?.keyFrameRequestFailures} " +
                "control_error=${snapshot.control?.lastError} " +
                "control_session_error=${snapshot.control?.lastSessionFailure?.error} " +
                "transport_pts_us=${transport?.lastPresentationTimeUs} transport_error=${transport?.lastError}",
        )
    }

    /** Low-cadence local receiver, decoder, and render counters for backlog investigations. */
    fun videoPipelineReceiverRuntime(snapshot: VideoReceiverSessionSnapshot) {
        if (!enabled) return
        val receiver = snapshot.receiver
        val decoder = snapshot.decoder
        val renderer = snapshot.renderer
        Log.d(
            TAG,
            "event=video_runtime role=client local_monotonic_ms=${SystemClock.elapsedRealtime()} " +
                "session_state=${snapshot.state} receiver_datagrams=${receiver?.datagramsReceived} " +
                "receiver_video_datagrams=${receiver?.videoDatagramsReceived} " +
                "receiver_completed=${receiver?.accessUnitsCompleted} " +
                "receiver_delivered=${receiver?.accessUnitsDelivered} " +
                "receiver_slots=${receiver?.reassemblySlotsUsed} " +
                "receiver_slots_hwm=${receiver?.reassemblySlotsHighWater} " +
                "receiver_ready=${receiver?.readyAccessUnits} " +
                "receiver_ready_hwm=${receiver?.readyAccessUnitsHighWater} " +
                "receiver_timeouts=${receiver?.reassemblyTimeouts} " +
                "receiver_reassembly_full=${receiver?.reassemblyWindowFull} " +
                "receiver_ready_full=${receiver?.readyWindowFull} " +
                "receiver_stale_released=${receiver?.staleFramesReleased} " +
                "receiver_nacks=${receiver?.nacksSent} " +
                "receiver_resync=${receiver?.resyncRequestsSent} " +
                "receiver_frame_id=${receiver?.lastFrameId} " +
                "receiver_pts_us=${receiver?.lastPresentationTimeUs} " +
                "receiver_ready_wait_us=${receiver?.lastReadyWaitUs} " +
                "decoder_queued=${decoder?.accessUnitsQueued} " +
                "decoder_bytes=${decoder?.encodedBytesQueued} " +
                "decoder_backpressure=${decoder?.inputBackpressureEvents} " +
                "decoder_output=${decoder?.decodedOutputBuffers} " +
                "decoder_released=${decoder?.framesRenderedRequested} " +
                "decoder_dropped=${decoder?.framesDropped} " +
                "decoder_input_pts_us=${decoder?.lastInputPtsUs} " +
                "decoder_output_pts_us=${decoder?.lastOutputPtsUs} " +
                "render_now=${renderer?.renderNowDecisions} " +
                "render_scheduled=${renderer?.scheduledRenderDecisions} " +
                "render_dropped=${renderer?.dropDecisions} render_pts_us=${renderer?.lastFramePtsUs}",
        )
    }

    fun clientRenderTargetAvailable(surfaceGeneration: Long) {
        if (!enabled) return
        Log.d(TAG, "event=client_render_target_available generation=$surfaceGeneration")
    }

    fun clientRenderTargetDestroyed(surfaceGeneration: Long) {
        if (!enabled) return
        Log.d(TAG, "event=client_render_target_destroyed generation=$surfaceGeneration")
    }

    fun clientDecoderPreparedForRenderTarget(surfaceGeneration: Long) {
        if (!enabled) return
        Log.d(TAG, "event=client_decoder_prepared_for_render_target generation=$surfaceGeneration")
    }

    fun clientRemoteFrameRendered(surfaceGeneration: Long) {
        if (!enabled) return
        Log.d(TAG, "event=client_remote_frame_rendered generation=$surfaceGeneration")
    }

    fun clientRenderSurfaceAttached(rendererAvailable: Boolean) {
        if (!enabled) return
        Log.d(TAG, "event=client_render_surface_attached renderer_available=$rendererAvailable")
    }

    fun clientRenderControllerAttached(surfaceWasValid: Boolean) {
        if (!enabled) return
        Log.d(TAG, "event=client_render_controller_attached surface_valid=$surfaceWasValid")
    }

    fun sessionSetupRuntimeCreated() {
        if (enabled) Log.d(TAG, "event=setup_runtime_created")
    }

    fun routeLocalAddressResolutionStarted() {
        if (enabled) Log.d(TAG, "event=route_local_address_resolution_started")
    }

    fun routeLocalAddressResolutionSucceeded(addressFamily: String) {
        if (enabled) {
            Log.d(
                TAG,
                "event=route_local_address_resolution_succeeded " +
                    "address_resolved=true wildcard=false address_family=$addressFamily",
            )
        }
    }

    fun routeLocalAddressResolutionFailed(reason: String, addressFamily: String? = null) {
        if (!enabled) return
        val message = buildString {
            append("event=route_local_address_resolution_failed address_resolved=false reason=")
            append(reason)
            addressFamily?.let {
                append(" address_family=")
                append(it)
            }
        }
        Log.d(TAG, message)
    }

    fun sessionSetup(event: SessionSetupDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            event.error?.let {
                append(" reason=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    fun capability(event: CapabilityNegotiationDebugEvent) {
        if (!enabled) return
        val message = buildString {
            append("event=")
            append(event.kind.logName)
            append(" local_monotonic_ms=")
            append(SystemClock.elapsedRealtime())
            event.error?.let {
                append(" reason=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    /** Bounded DEBUG-only timing for local capability collection; values are device-local only. */
    fun capabilityCollection(role: String, component: String, phase: String, durationMs: Long? = null) {
        if (!enabled) return
        val message = buildString {
            append("event=capability_collection")
            append(" local_monotonic_ms=")
            append(SystemClock.elapsedRealtime())
            append(" role=")
            append(role)
            append(" component=")
            append(component)
            append(" phase=")
            append(phase)
            durationMs?.let {
                append(" duration_ms=")
                append(it)
            }
        }
        Log.d(TAG, message)
    }

    fun localCapabilityProbe(
        role: String,
        videoAvailable: Boolean,
        videoError: String,
        inputAvailable: Boolean,
        inputError: String,
    ) {
        if (enabled) {
            Log.d(
                TAG,
                "event=capability_local_probe role=$role video_available=$videoAvailable video_reason=$videoError " +
                    "input_available=$inputAvailable input_reason=$inputError",
            )
        }
    }

    fun encoderCbrCapability(decision: CbrCapabilityDecision) {
        if (!enabled) return
        val event = when (decision.source) {
            CbrCapabilityDecisionSource.Metadata -> "encoder_cbr_metadata_supported"
            CbrCapabilityDecisionSource.NotEligible -> return
            CbrCapabilityDecisionSource.ActiveProbe -> {
                if (decision.supported) "encoder_cbr_active_probe_succeeded" else "encoder_cbr_active_probe_failed"
            }
            CbrCapabilityDecisionSource.CurrentProcessProbeCache -> "encoder_cbr_current_process_cache_hit"
            CbrCapabilityDecisionSource.PersistentProbeCache -> "encoder_cbr_persistent_cache_hit"
            CbrCapabilityDecisionSource.CurrentProcessQuarantine -> "encoder_cbr_current_process_quarantined"
        }
        val message = buildString {
            append("event=")
            append(event)
            decision.probeResult?.let {
                append(" result=")
                append(it.name)
            }
        }
        Log.d(TAG, message)
    }

    fun encoderCbrActiveProbeStarted() {
        if (enabled) Log.d(TAG, "event=encoder_cbr_active_probe_started")
    }

    fun decoderQualificationProbeStarted() {
        if (enabled) {
            Log.d(
                TAG,
                "event=decoder_qualification_probe_started main_pid=${Process.myPid()} main_uid=${Process.myUid()}",
            )
        }
    }

    fun decoderQualification(decision: io.warpnect.platform.video.decoder.LegacyDecoderQualificationDecision) {
        if (!enabled) return
        val event = when (decision.source) {
            io.warpnect.platform.video.decoder.LegacyDecoderQualificationSource.ActiveProbe ->
                "decoder_qualification_probe_completed"
            io.warpnect.platform.video.decoder.LegacyDecoderQualificationSource.PersistentCache ->
                "decoder_qualification_cache_hit"
            io.warpnect.platform.video.decoder.LegacyDecoderQualificationSource.CurrentProcessQuarantine ->
                "decoder_qualification_quarantined"
        }
        Log.d(TAG, "event=$event result=${decision.result.name} outcome=${decision.outcome.name}")
    }

    private companion object {
        const val TAG = "WarpnectDiscovery"
    }
}

private val PairingDebugEventKind.logName: String
    get() = when (this) {
        PairingDebugEventKind.AttemptStarted -> "pairing_attempt_started"
        PairingDebugEventKind.SasReady -> "pairing_sas_ready"
        PairingDebugEventKind.LocalConfirm -> "pairing_local_confirm"
        PairingDebugEventKind.RemoteConfirmReceived -> "pairing_remote_confirm_received"
        PairingDebugEventKind.LocalReject -> "pairing_local_reject"
        PairingDebugEventKind.RemoteRejectReceived -> "pairing_remote_reject_received"
        PairingDebugEventKind.ActionSent -> "pairing_action_sent"
        PairingDebugEventKind.ActionReceived -> "pairing_action_received"
        PairingDebugEventKind.Succeeded -> "pairing_succeeded"
        PairingDebugEventKind.Failed -> "pairing_failed"
        PairingDebugEventKind.ResetStarted -> "pairing_reset_started"
        PairingDebugEventKind.ResetComplete -> "pairing_reset_complete"
    }

private val SessionHandshakeDebugEventKind.logName: String
    get() = when (this) {
        SessionHandshakeDebugEventKind.Started -> "handshake_attempt_started"
        SessionHandshakeDebugEventKind.ActionSent -> "handshake_action_sent"
        SessionHandshakeDebugEventKind.ActionReceived -> "handshake_action_received"
        SessionHandshakeDebugEventKind.PairingTrustBoundaryReset -> "handshake_pairing_trust_boundary_reset"
        SessionHandshakeDebugEventKind.Authenticated -> "handshake_authenticated"
        SessionHandshakeDebugEventKind.Failed -> "handshake_failed"
    }

private val SessionStartupDebugEventKind.logName: String
    get() = when (this) {
        SessionStartupDebugEventKind.Authenticated -> "session_authenticated"
        SessionStartupDebugEventKind.SecureControlReady -> "secure_control_ready"
        SessionStartupDebugEventKind.CapabilityNegotiationStarted -> "capability_negotiation_started"
        SessionStartupDebugEventKind.CapabilityNegotiated -> "capability_negotiated"
        SessionStartupDebugEventKind.SessionSetupStarted -> "session_setup_started"
        SessionStartupDebugEventKind.SessionPrepared -> "session_prepared"
        SessionStartupDebugEventKind.VideoChannelReady -> "video_channel_ready"
        SessionStartupDebugEventKind.MediaStartRequested -> "media_start_requested"
        SessionStartupDebugEventKind.MediaStartAccepted -> "media_start_accepted"
        SessionStartupDebugEventKind.RuntimeRunning -> "session_runtime_running"
        SessionStartupDebugEventKind.Failed -> "session_start_failed"
    }

private val SessionLifecycleDebugEventKind.logName: String
    get() = when (this) {
        SessionLifecycleDebugEventKind.StartRequested -> "lifecycle_start_requested"
        SessionLifecycleDebugEventKind.StartRejectedClosed -> "lifecycle_start_rejected_closed"
        SessionLifecycleDebugEventKind.StartRejectedMissingCapacity -> "lifecycle_start_rejected_missing_capacity"
        SessionLifecycleDebugEventKind.StartRejectedBootstrapTransfer -> "lifecycle_start_rejected_bootstrap_transfer"
        SessionLifecycleDebugEventKind.StartRejectedCapacityPromotion -> "lifecycle_start_rejected_capacity_promotion"
        SessionLifecycleDebugEventKind.StartSucceeded -> "lifecycle_start_succeeded"
        SessionLifecycleDebugEventKind.FirstHeartbeatSent -> "lifecycle_first_heartbeat_sent"
        SessionLifecycleDebugEventKind.FirstActiveControlPayloadReceived ->
            "lifecycle_first_active_control_payload_received"
        SessionLifecycleDebugEventKind.Suspended -> "lifecycle_suspended"
        SessionLifecycleDebugEventKind.ReconnectRequested -> "lifecycle_reconnect_requested"
    }

private val VideoPipelineStartDebugEventKind.logName: String
    get() = when (this) {
        VideoPipelineStartDebugEventKind.SenderStartRequested -> "video_sender_start_requested"
        VideoPipelineStartDebugEventKind.SenderStartSucceeded -> "video_sender_start_succeeded"
        VideoPipelineStartDebugEventKind.SenderStartFailed -> "video_sender_start_failed"
        VideoPipelineStartDebugEventKind.ReceiverStartRequested -> "video_receiver_start_requested"
        VideoPipelineStartDebugEventKind.ReceiverStartSucceeded -> "video_receiver_start_succeeded"
        VideoPipelineStartDebugEventKind.ReceiverStartFailed -> "video_receiver_start_failed"
    }

private val VideoTransportDebugEvent.logName: String
    get() = when (this) {
        VideoTransportDebugEvent.FirstVideoDatagramSent -> "first_video_datagram_sent"
        VideoTransportDebugEvent.FirstVideoDatagramReceived -> "first_video_datagram_received"
        VideoTransportDebugEvent.FirstStreamConfigAvailable -> "client_video_stream_config_available"
        VideoTransportDebugEvent.FirstVideoAccessUnitReceived -> "first_video_access_unit_received"
    }

private val VideoDecoderDebugEvent.logName: String
    get() = when (this) {
        VideoDecoderDebugEvent.DecoderStarted -> "decoder_started"
        VideoDecoderDebugEvent.FirstAccessUnitSubmitted -> "first_video_access_unit_submitted_to_decoder"
        VideoDecoderDebugEvent.FirstOutputAvailable -> "first_frame_decoded"
        VideoDecoderDebugEvent.FirstFrameRendered -> "first_frame_rendered"
    }

private val SessionSetupDebugEventKind.logName: String
    get() = when (this) {
        SessionSetupDebugEventKind.OfferBuildStarted -> "setup_offer_build_started"
        SessionSetupDebugEventKind.OfferBuilt -> "setup_offer_built"
        SessionSetupDebugEventKind.OfferSent -> "setup_offer_sent"
        SessionSetupDebugEventKind.OfferReceived -> "setup_offer_received"
        SessionSetupDebugEventKind.PathSelectionBuilt -> "setup_selection_build_started"
        SessionSetupDebugEventKind.PathSelectionSent -> "setup_selection_sent"
        SessionSetupDebugEventKind.PathSelectionReceived -> "setup_selection_received"
        SessionSetupDebugEventKind.ChannelPlanValidationStarted -> "channel_plan_validation_started"
        SessionSetupDebugEventKind.ChannelPlanValidationSucceeded -> "channel_plan_validation_succeeded"
        SessionSetupDebugEventKind.ChannelPlanValidationFailed -> "channel_plan_validation_failed"
        SessionSetupDebugEventKind.SocketPathBindingStarted -> "socket_path_binding_started"
        SessionSetupDebugEventKind.SocketPathBindingSucceeded -> "socket_path_binding_succeeded"
        SessionSetupDebugEventKind.Committed -> "setup_committed"
        SessionSetupDebugEventKind.Failed -> "setup_failed"
    }

private val CapabilityNegotiationDebugEventKind.logName: String
    get() = when (this) {
        CapabilityNegotiationDebugEventKind.ClientOfferSent -> "capability_client_offer_sent"
        CapabilityNegotiationDebugEventKind.ClientOfferReceived -> "capability_client_offer_received"
        CapabilityNegotiationDebugEventKind.HostSelectionSent -> "capability_host_selection_sent"
        CapabilityNegotiationDebugEventKind.HostSelectionReceived -> "capability_host_selection_received"
        CapabilityNegotiationDebugEventKind.ClientConfirmSent -> "capability_client_confirm_sent"
        CapabilityNegotiationDebugEventKind.ClientConfirmReceived -> "capability_client_confirm_received"
        CapabilityNegotiationDebugEventKind.HostCompleteSent -> "capability_host_complete_sent"
        CapabilityNegotiationDebugEventKind.HostCompleteReceived -> "capability_host_complete_received"
        CapabilityNegotiationDebugEventKind.Completed -> "capability_completed"
        CapabilityNegotiationDebugEventKind.Failed -> "capability_failed"
    }
