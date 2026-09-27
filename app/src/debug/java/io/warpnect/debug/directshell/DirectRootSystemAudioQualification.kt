package io.warpnect.debug.directshell

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.debug.audio.SystemAudioE2eToneActivity
import io.warpnect.platform.audio.capture.DirectRootSystemAudioCapability
import io.warpnect.platform.audio.capture.DirectRootSystemAudioGateway
import io.warpnect.platform.audio.capture.RootAudioRequest
import io.warpnect.platform.audio.capture.RootPcmDescriptorLease
import io.warpnect.platform.audio.capture.SharedPcmAudioDrain
import io.warpnect.platform.audio.capture.shared.LegacyAshmemPcmMemory
import io.warpnect.platform.audio.capture.shared.PcmSignalEvidence
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.readiness.AndroidPersistentCapabilityEvidenceKeyFactory
import io.warpnect.platform.privileged.readiness.CachedCapabilityEvidenceMatrixBuilder
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRepository
import io.warpnect.platform.privileged.readiness.SharedPreferencesPersistentCapabilityEvidenceStore
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.json.JSONObject

/** Explicit bounded fixture; measurements occur after the production drain, not at the helper. */
internal object DirectRootSystemAudioQualification {
    private var active: Active? = null
    private var tone: SystemAudioE2eToneActivity.ValidationTone? = null

    fun run(context: Context, runtime: DirectRootRuntime) {
        var success = false
        var reason = "UNKNOWN"
        try {
            val current = start(runtime)
            Thread.sleep(300)
            val silence = current.metrics.window()
            Thread.sleep(1000)
            current.metrics.endWindow()
            tone = SystemAudioE2eToneActivity.ValidationTone(continuousPhase = true).apply { start() }
            Thread.sleep(300)
            val signal = current.metrics.window()
            Thread.sleep(1500)
            current.metrics.endWindow()
            check(signal.validTone(silence)) { "ZERO_OR_UNCORRELATED_PCM" }
            tone?.stop()
            tone = null
            current.metrics.delayOneRead = true
            Thread.sleep(150)
            val stopped = JSONObject(current.gateway.stop(current.request))
            check(stopped.getString("boundary") == "STOPPED") { "AUDIO_STOP_FAILED_$stopped" }
            Thread.sleep(100)
            val status = JSONObject(current.gateway.status(current.request))
            check(status.getLong("written") == current.metrics.frames) { "RING_DRAIN_INCOMPLETE" }
            check(current.metrics.errors == 0L && status.getLong("errors") == 0L) { "PCM_READ_OR_DRAIN_ERROR" }
            check(status.getInt("hwm") <= RootPcmDescriptorLease.SLOT_COUNT && status.getInt("occupancy") == 0)
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_audio_evidence generation=${current.request.generation} " +
                    "audio_generation=${current.request.audioGeneration} silence_samples=${silence.samples} " +
                    "silence_rms=${silence.rms} tone_samples=${signal.samples} nonzero=${signal.nonZero} " +
                    "rms=${signal.rms} peak=${signal.peak} " +
                    "tone_amplitude=${signal.toneAmplitude} clips=${signal.clips} " +
                    "app_frames=${current.metrics.frames} app_bytes=${current.metrics.bytes} " +
                    "wakeups=${current.metrics.wakeups} " +
                    "discontinuities=${current.metrics.discontinuities} producer=$status",
            )
            success = true
            reason = "root_remote_submix_real_ring_pcm_fixture_observed"
        } catch (failure: Exception) {
            reason = failure.message?.take(120) ?: failure.javaClass.simpleName
            Log.i("WarpnectDirectRoot", "direct_root_audio_qualification result=FAIL boundary=$reason")
        } finally {
            release()
            if (runtime.snapshot.ready) {
                val result = DirectRootSystemAudioCapability.record(runtime.snapshot, success, reason)
                val repository = repository(context)
                val cached = CachedCapabilityEvidenceMatrixBuilder.build(
                    repository.loadReusableEvidence().reusableRecords,
                )
                repository.reconcileAuthoritativeMatrix(
                    PrivilegedCapabilityMatrix.complete { provider, capability ->
                        if (provider == result.provider && capability == result.capability) {
                            result
                        } else {
                            cached.qualification(
                                provider,
                                capability,
                            )
                        }
                    },
                )
            }
        }
        if (success) {
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_audio_qualification result=PASS generation=${runtime.snapshot.generation}",
            )
        }
    }

    fun hold(runtime: DirectRootRuntime) {
        start(runtime)
        Log.i("WarpnectDirectRoot", "direct_root_audio_held result=PASS")
    }

    fun rejectBadRing(runtime: DirectRootRuntime) {
        release()
        val request =
            RootAudioRequest(
                requireNotNull(runtime.snapshot.generation),
                (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(2),
            )
        val memory = LegacyAshmemPcmMemory.create("warpnect-root-invalid-header", RootPcmDescriptorLease.REGION_BYTES)
        val notify = ParcelFileDescriptor.createPipe()
        val ack = ParcelFileDescriptor.createPipe()
        val gateway = DirectRootSystemAudioGateway(runtime)
        try {
            val mapped = memory.mapReadWrite()
            try {
                check(SharedPcmAudioRingLayout.initialize(mapped, 8, 960))
                mapped.putInt(0, 0)
            } finally {
                memory.unmap(mapped)
            }
            DirectRootPcmHandoff.stage(request, memory, notify[1], ack[0])
            val response = JSONObject(gateway.prepare(request))
            check(response.getString("boundary") == "RING_SETUP_FAILED" && response.getInt("record_state") == -1)
            Log.i("WarpnectDirectRoot", "direct_root_audio_invalid_header result=REJECTED record_constructed=false")
        } finally {
            gateway.close()
            DirectRootPcmHandoff.release(request)
            (notify + ack).forEach { runCatching { it.close() } }
            memory.close()
        }
    }

    private fun start(runtime: DirectRootRuntime): Active {
        release()
        check(runtime.snapshot.ready)
        val request =
            RootAudioRequest(
                requireNotNull(runtime.snapshot.generation),
                (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(2),
            )
        val memory = LegacyAshmemPcmMemory.create(
            "warpnect-root-pcm-${request.audioGeneration}",
            RootPcmDescriptorLease.REGION_BYTES,
        )
        var notify: Array<ParcelFileDescriptor>? = null
        var ack: Array<ParcelFileDescriptor>? = null
        try {
            val mapped = memory.mapReadWrite()
            check(
                SharedPcmAudioRingLayout.initialize(
                    mapped,
                    RootPcmDescriptorLease.SLOT_COUNT,
                    RootPcmDescriptorLease.SLOT_BYTES,
                ),
            )
            memory.unmap(mapped)
            memory.unmap(mapped)
            check(
                runCatching {
                    LegacyAshmemPcmMemory.receive(memory.duplicateDescriptor(), RootPcmDescriptorLease.REGION_BYTES + 1)
                }.isFailure,
            )
            val verifiedDuplicate = LegacyAshmemPcmMemory.receive(
                memory.duplicateDescriptor(),
                RootPcmDescriptorLease.REGION_BYTES,
            )
            val verifiedMapping = verifiedDuplicate.mapReadWrite()
            check(SharedPcmAudioRingLayout.validate(verifiedMapping))
            verifiedDuplicate.unmap(verifiedMapping)
            verifiedDuplicate.close()
            verifiedDuplicate.close()
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_ashmem_adapter duplicate=PASS size_mismatch=REJECTED unmap_close_idempotent=PASS",
            )
            notify = ParcelFileDescriptor.createPipe()
            ack = ParcelFileDescriptor.createPipe()
            val metrics = Metrics()
            val gateway = DirectRootSystemAudioGateway(runtime)
            val drain = SharedPcmAudioDrain(memory, notify[0], ack[1], metrics, metrics::error, { _, _, _ -> })
            val current = Active(request, memory, notify, ack, gateway, drain, metrics)
            active = current
            DirectRootPcmHandoff.stage(request, memory, notify[1], ack[0])
            val prepared = JSONObject(gateway.prepare(request))
            Log.i("WarpnectDirectRoot", "direct_root_audio_prepare $prepared")
            check(prepared.getString("boundary") == "PREPARED") { prepared.getString("boundary") }
            check(drain.start() == AudioCaptureError.None) { "DRAIN_START_FAILED" }
            val started = JSONObject(gateway.start(request))
            check(started.getString("boundary") == "RECORDING") { started.getString("boundary") }
            Log.i("WarpnectDirectRoot", "direct_root_audio_start $started")
            return current
        } catch (error: Exception) {
            release()
            notify?.forEach { runCatching { it.close() } }
            ack?.forEach { runCatching { it.close() } }
            memory.close()
            throw error
        }
    }

    fun release() {
        tone?.stop()
        tone = null
        val current = active
        active = null
        if (current != null) {
            runCatching { current.gateway.stop(current.request) }
            current.gateway.close()
            DirectRootPcmHandoff.release()
            runCatching { current.notify[1].close() }
            runCatching { current.ack[0].close() }
            current.drain.close()
            current.notify.forEach { runCatching { it.close() } }
            current.ack.forEach { runCatching { it.close() } }
            current.memory.close()
        }
        DirectRootPcmHandoff.release()
        Log.i("WarpnectDirectRoot", "direct_root_audio_app_cleanup active=false ring=false pipes=false")
    }

    fun status(context: Context, runtime: DirectRootRuntime?) {
        val records = repository(context).loadReusableEvidence().reusableRecords.filter {
            it.provider == PrivilegedProvider.DIRECT_ROOT
        }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_audio_status records=${records.map { it.capability }} " +
                "runtime_ready=${runtime?.snapshot?.ready == true} " +
                "active=${active != null} pcm_lease=${DirectRootPcmHandoff.active()}",
        )
    }

    private fun repository(context: Context) = PersistentCapabilityEvidenceRepository(
        SharedPreferencesPersistentCapabilityEvidenceStore(context),
        AndroidPersistentCapabilityEvidenceKeyFactory(shizukuRuntimeVersion = {
            null
        }),
        recordedAtEpochMs = System::currentTimeMillis,
    )

    private data class Active(
        val request: RootAudioRequest,
        val memory: LegacyAshmemPcmMemory,
        val notify: Array<ParcelFileDescriptor>,
        val ack: Array<ParcelFileDescriptor>,
        val gateway: DirectRootSystemAudioGateway,
        val drain: SharedPcmAudioDrain,
        val metrics: Metrics,
    )

    internal class Metrics : PcmAudioSink {
        private var evidence: PcmSignalEvidence? = null

        @Volatile var delayOneRead = false
        var frames = 0L
        var bytes = 0L
        var wakeups = 0L
        var errors = 0L
        var discontinuities = 0L
        private var expectedFrame: Long? = null

        @Synchronized fun window(): PcmSignalEvidence = PcmSignalEvidence().also { evidence = it }

        @Synchronized fun endWindow() {
            evidence = null
        }

        @Synchronized fun error(error: AudioCaptureError) {
            if (error != AudioCaptureError.None) errors++
        }
        override fun onFormatChanged(format: AudioCaptureFormat) = Unit
        override fun onCaptureError(error: AudioCaptureError) = error(error)

        @Synchronized override fun onPcmChunk(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
        ) {
            if (delayOneRead) {
                delayOneRead = false
                Thread.sleep(80)
            }
            if (expectedFrame != null && expectedFrame != firstFramePosition) discontinuities++
            expectedFrame = firstFramePosition + frameCount
            evidence?.accept(buffer, offset, sizeBytes, firstFramePosition)
            frames += frameCount
            bytes += sizeBytes
            wakeups++
        }
    }
}
