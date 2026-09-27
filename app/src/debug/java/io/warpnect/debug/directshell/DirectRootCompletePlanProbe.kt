package io.warpnect.debug.directshell

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import io.warpnect.platform.audio.capture.AndroidSystemAudioCaptureController
import io.warpnect.platform.audio.capture.DirectRootSystemAudioCapability
import io.warpnect.platform.audio.encoder.NativeOpusAudioEncoderController
import io.warpnect.platform.audio.encoder.OpusPcmAudioSink
import io.warpnect.platform.capture.DirectRootVideoCapability
import io.warpnect.platform.input.injection.DirectRootInputCapability
import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanAdmission
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanAdmissionResult
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanLease
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanShape
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.planning.PrivilegedProviderAcquirer
import io.warpnect.platform.privileged.planning.PrivilegedProviderAcquisition
import io.warpnect.platform.privileged.readiness.AndroidPersistentCapabilityEvidenceKeyFactory
import io.warpnect.platform.privileged.readiness.CachedPreparedPlanBuilder
import io.warpnect.platform.privileged.readiness.LateRevalidatedCapabilityEvidence
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRepository
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.readiness.SharedPreferencesPersistentCapabilityEvidenceStore
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeIdentity
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking

/** Explicit lab admission only: actual three-cell evidence, actual planner and provider freeze. */
internal object DirectRootCompletePlanProbe {
    private var frozen: PrivilegedCapabilityPlanLease? = null

    fun run(context: Context, runtime: DirectRootRuntime) {
        release()
        check(runtime.checkLiveness()) { "root_admission_helper_absent" }
        val repository = PersistentCapabilityEvidenceRepository(
            SharedPreferencesPersistentCapabilityEvidenceStore(context),
            AndroidPersistentCapabilityEvidenceKeyFactory(shizukuRuntimeVersion = { null }),
            recordedAtEpochMs = System::currentTimeMillis,
        )
        val snapshot = runtime.snapshot
        val generation = requireNotNull(snapshot.generation)
        val volatile = PrivilegedRuntimeStateSnapshot(
            generation,
            SystemClock.elapsedRealtime(),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.READY,
                rootFoundation = snapshot,
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_SHELL,
                PrivilegedRuntimeAvailabilityState.UNSUPPORTED,
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.SHIZUKU,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
        )
        val passive = PrivilegedRuntimeSnapshot(
            PrivilegedRuntimeKind.DIRECT_ROOT,
            PrivilegedRuntimeState.READY,
            generation = generation,
            identity = PrivilegedRuntimeIdentity(
                snapshot.identity?.uid,
                snapshot.identity?.gid,
                snapshot.identity?.selinuxContext,
            ),
        )
        val cached = CachedPreparedPlanBuilder.build(
            volatile,
            passive,
            repository.loadReusableEvidence().reusableRecords,
            SystemClock.elapsedRealtime(),
        )
        check(cached.preparedPlan?.plan?.participatingProviders == listOf(PrivilegedProvider.DIRECT_ROOT)) {
            "root_complete_cached_plan_absent"
        }
        val live = PrivilegedCapabilityMatrix.complete { provider, capability ->
            if (provider == PrivilegedProvider.DIRECT_ROOT) {
                when (capability) {
                    PrivilegedCapability.VIDEO -> DirectRootVideoCapability.qualification(snapshot)
                    PrivilegedCapability.SYSTEM_AUDIO -> DirectRootSystemAudioCapability.qualification(snapshot)
                    PrivilegedCapability.INPUT -> DirectRootInputCapability.qualification(snapshot)
                }
            } else {
                CapabilityQualification.unavailable(
                    provider,
                    capability,
                    CapabilityQualificationState.NOT_AVAILABLE,
                    "lab_not_selected",
                    CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER),
                )
            }
        }
        check(
            live.entries().filter { it.provider == PrivilegedProvider.DIRECT_ROOT }.all {
                it.state.isUsable && it.provenance.runtimeGeneration == generation
            },
        ) { "root_three_live_capabilities_required" }
        val matrix = LateRevalidatedCapabilityEvidence.combine(live, cached.matrix)
        val result = PrivilegedCapabilityPlanAdmission().admit(
            matrix,
            object : PrivilegedProviderAcquirer {
                override fun acquire(provider: PrivilegedProvider): PrivilegedProviderAcquisition =
                    if (provider == PrivilegedProvider.DIRECT_ROOT && runtime.checkLiveness() &&
                        DirectRootVideoCapability.retainForHost() && DirectRootInputCapability.retainForHost() &&
                        DirectRootSystemAudioCapability.retainForHost()
                    ) {
                        PrivilegedProviderAcquisition.Retained
                    } else {
                        PrivilegedProviderAcquisition.Failed("root_late_revalidation_failed")
                    }
                override fun release(provider: PrivilegedProvider) {
                    DirectRootSystemAudioCapability.selectForHost(false)
                    DirectRootVideoCapability.selectForHost(false)
                    DirectRootInputCapability.selectForHost(false)
                }
            },
        ) as? PrivilegedCapabilityPlanAdmissionResult.Frozen ?: error("root_plan_not_frozen")
        frozen = result.lease
        check(
            result.plan.shape == PrivilegedCapabilityPlanShape.SINGLE &&
                result.plan.participatingProviders == listOf(PrivilegedProvider.DIRECT_ROOT),
        )
        check(
            DirectRootSystemAudioCapability.selectForHost(true) &&
                DirectRootVideoCapability.selectForHost(true) && DirectRootInputCapability.selectForHost(true),
        )
        val controller = AndroidSystemAudioCaptureController(context)
        val encoder = NativeOpusAudioEncoderController()
        try {
            val request = AudioCaptureRequest(AudioCaptureSource.SystemAudio)
            check(controller.queryCapabilities(request).available)
            val metrics = DirectRootSystemAudioQualification.Metrics()
            val output = EncodedCounts()
            check(
                encoder.prepare(AudioEncoderRequest(source = request.source, channelCount = 2), output).error ==
                    AudioEncoderError.None,
            )
            check(encoder.start().error == AudioEncoderError.None)
            val opus = OpusPcmAudioSink(encoder)
            val sink = object : PcmAudioSink by opus {
                override fun onPcmChunk(
                    buffer: ByteBuffer,
                    offset: Int,
                    sizeBytes: Int,
                    frameCount: Int,
                    firstFramePosition: Long,
                    captureTimeNs: Long,
                    timestampQuality: AudioTimestampQuality,
                ) {
                    metrics.onPcmChunk(
                        buffer,
                        offset,
                        sizeBytes,
                        frameCount,
                        firstFramePosition,
                        captureTimeNs,
                        timestampQuality,
                    )
                    opus.onPcmChunk(
                        buffer,
                        offset,
                        sizeBytes,
                        frameCount,
                        firstFramePosition,
                        captureTimeNs,
                        timestampQuality,
                    )
                }
            }
            check(runBlocking { controller.prepare(request, sink) }.error == AudioCaptureError.None)
            check(runBlocking { controller.start() }.error == AudioCaptureError.None)
            Thread.sleep(400)
            check(runBlocking { controller.stop() }.error == AudioCaptureError.None)
            check(runBlocking { controller.stop() }.error == AudioCaptureError.None)
            check(metrics.frames > 0 && metrics.errors == 0L)
            val encoded = encoder.snapshot()
            check(encoded.encodedFrames > 0 && output.frames == encoded.encodedFrames && output.bytes > 0)
            check(encoded.lastError == AudioEncoderError.None && output.errors == 0)
            check(encoder.stop().error == AudioEncoderError.None)
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_audio_existing_opus result=PASS pcm_frames=${encoded.pcmFramesReceived} " +
                    "encoded_frames=${encoded.encodedFrames} " +
                    "encoded_bytes=${encoded.encodedBytes} errors=${output.errors}",
            )
            Log.i("WarpnectDirectRoot", "direct_root_audio_existing_controller result=PASS frames=${metrics.frames}")
        } finally {
            controller.close()
            encoder.close()
        }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_complete_plan result=PASS prepared=SINGLE_DIRECT_ROOT " +
                "late_revalidated=true frozen=true generation=$generation " +
                "video=DIRECT_ROOT system_audio=DIRECT_ROOT input=DIRECT_ROOT " +
                "ordinary_host_ui=NOT_TESTED network_session=NOT_TESTED",
        )
    }

    fun release() {
        frozen?.close()
        frozen = null
    }

    private class EncodedCounts : EncodedAudioSink {
        var frames = 0L
        var bytes = 0L
        var errors = 0

        override fun onOutputFormatChanged(format: EncodedAudioFormat) = Unit

        override fun onEncodedFrame(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
            encodedFrameIndex: Long,
        ) {
            check(sizeBytes > 0 && offset >= 0 && offset.toLong() + sizeBytes <= buffer.capacity())
            frames++
            bytes += sizeBytes
        }

        override fun onEncoderError(error: AudioEncoderError) {
            if (error != AudioEncoderError.None) errors++
        }
    }
}
