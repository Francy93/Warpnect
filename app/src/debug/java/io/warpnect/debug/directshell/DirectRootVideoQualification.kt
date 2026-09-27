package io.warpnect.debug.directshell

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.util.Log
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.platform.capture.DirectRootVideoCapability
import io.warpnect.platform.capture.DirectRootVideoCaptureGateway
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.readiness.AndroidPersistentCapabilityEvidenceKeyFactory
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRepository
import io.warpnect.platform.privileged.readiness.SharedPreferencesPersistentCapabilityEvidenceStore
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.encoder.VideoEncoderRequest
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking

/** Explicit DEBUG diagnostics. AVC stays app-local; offline decoded fixture proof precedes cache admission. */
internal object DirectRootVideoQualification {
    private var active: Active? = null
    private var pendingGeneration: Long? = null
    private val request =
        VideoEncoderRequest(
            width = 640,
            height = 360,
            frameRate = 30,
            bitrateBps = 1_000_000,
            iFrameIntervalSeconds = 1f,
        )

    fun run(context: Context, runtime: DirectRootRuntime) {
        pendingGeneration = null
        try {
            val running = start(context, runtime)
            for (state in listOf("a", "b")) {
                fixture(context, state)
                Thread.sleep(500)
                running.metrics.begin(File(context.filesDir, "root-video/state_$state.h264"))
                check(runBlocking { running.encoder.requestKeyFrame() }.isSuccess) { "KEYFRAME_REQUEST_FAILED" }
                Thread.sleep(1800)
                running.metrics.end(state)
            }
            check(running.metrics.errors == 0L) { "ENCODER_ERROR" }
            check(runBlocking { running.gateway.stopCapture() } == CaptureError.None) { "MIRROR_STOP_FAILED" }
            check(runBlocking { running.gateway.stopCapture() } == CaptureError.None) { "DOUBLE_STOP_FAILED" }
            pendingGeneration = runtime.snapshot.generation
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_video_qualification result=AWAITING_DECODED_CONTENT generation=$pendingGeneration",
            )
        } catch (failure: Exception) {
            Log.i("WarpnectDirectRoot", "direct_root_video_qualification result=FAIL boundary=${failure.message}")
        } finally {
            release()
            fixture(context, "a", false)
        }
    }

    /** DUMP-gated lab observer has decoded both bounded AVC windows and checked the known fixture pixels. */
    fun confirmContent(context: Context, runtime: DirectRootRuntime) {
        check(
            runtime.snapshot.ready && pendingGeneration == runtime.snapshot.generation,
        ) { "root_video_observation_stale" }
        val result = DirectRootVideoCapability.record(runtime.snapshot, true)
        val repository = repository(context)
        val cached = repository.loadReusableEvidence().reusableRecords
        val matrix = io.warpnect.platform.privileged.readiness.CachedCapabilityEvidenceMatrixBuilder.build(cached)
        val updated = PrivilegedCapabilityMatrix.complete { provider, capability ->
            if (provider == result.provider && capability == result.capability) {
                result
            } else {
                matrix.qualification(
                    provider,
                    capability,
                )
            }
        }
        repository.reconcileAuthoritativeMatrix(updated)
        pendingGeneration = null
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_video_qualification result=PASS generation=${runtime.snapshot.generation} " +
                "content=OFFLINE_DECODED_FIXTURE_AB " +
                "audio=${io.warpnect.platform.audio.capture.DirectRootSystemAudioCapability.qualification().state}",
        )
    }

    fun hold(context: Context, runtime: DirectRootRuntime) {
        start(context, runtime)
        Thread.sleep(1000)
        Log.i("WarpnectDirectRoot", "direct_root_video_held result=PASS generation=${runtime.snapshot.generation}")
    }

    private fun start(context: Context, runtime: DirectRootRuntime): Active {
        check(runtime.snapshot.ready)
        release()
        fixture(context, "a")
        val metrics = Metrics()
        val encoder = AndroidMediaCodecVideoEncoder(LaboratoryRootVideoEncoderDiscovery())
        val gateway = DirectRootVideoCaptureGateway(runtime, onServiceDied = { encoder.close() })
        val prepared = Active(encoder, gateway, metrics)
        active = prepared
        runBlocking {
            val capabilities = gateway.queryCapabilities()
            Log.i("WarpnectDirectRoot", "direct_root_video_backend $capabilities")
            check(
                capabilities.backendAvailable && capabilities.lastError == CaptureError.None,
            ) { "MIRROR_BACKEND_UNAVAILABLE" }
            val configured = encoder.prepare(request, metrics)
            check(
                configured.isSuccess && configured.inputSurface?.isValid == true,
            ) { "ENCODER_PREPARE_${configured.error}" }
            check(encoder.start().isSuccess) { "ENCODER_START_FAILED" }
            check(
                gateway.startCapture(
                    CaptureRequest(0, request.width, request.height), configured.inputSurface!!,
                ) == CaptureError.None,
            ) {
                "MIRROR_START_${gateway.snapshot()}"
            }
            Log.i("WarpnectDirectRoot", "direct_root_video_capture_snapshot ${gateway.snapshot()}")
        }
        return prepared
    }

    private fun fixture(context: Context, state: String, animate: Boolean = true) {
        context.startActivity(
            Intent(context, DirectShellVideoCaptureProbeActivity::class.java).putExtra("state", state)
                .putExtra("animate_marker", animate)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    fun status(context: Context, runtime: DirectRootRuntime?) {
        if (runtime?.snapshot?.ready != true) release()
        val records = repository(
            context,
        ).loadReusableEvidence().reusableRecords.filter { it.provider.name == "DIRECT_ROOT" }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_video_status records=${records.map { it.capability }} " +
                "runtime_ready=${runtime?.snapshot?.ready == true} active=${active != null} " +
                "surface_lease=${DirectRootSurfaceHandoff.active()}",
        )
    }

    fun release() {
        val old = active
        active = null
        if (old != null) {
            runBlocking {
                old.gateway.stopCapture()
                old.encoder.stop()
            }
            old.gateway.close()
            old.encoder.close()
            old.metrics.close()
        }
        DirectRootSurfaceHandoff.release()
    }

    private fun repository(context: Context) = PersistentCapabilityEvidenceRepository(
        SharedPreferencesPersistentCapabilityEvidenceStore(context),
        AndroidPersistentCapabilityEvidenceKeyFactory(shizukuRuntimeVersion = { null }),
        System::currentTimeMillis,
    )

    private data class Active(
        val encoder: AndroidMediaCodecVideoEncoder,
        val gateway: DirectRootVideoCaptureGateway,
        val metrics: Metrics,
    )

    private class Metrics : EncodedVideoSink, AutoCloseable {
        private var csd = emptyList<ByteArray>()
        private var file: FileOutputStream? = null
        private var waitingForIdr = true
        private var units = 0
        private var keyframes = 0
        private var bytes = 0L
        private var firstPts = 0L
        private var lastPts = 0L

        @Volatile var errors = 0L
            private set

        @Synchronized override fun onOutputFormatChanged(format: VideoEncoderOutputFormat) {
            csd = format.codecSpecificData.map { it.clone() }
            Log.i("WarpnectDirectRoot", "direct_root_video_codec_config csd=${csd.map { it.size }} format=$format")
        }

        @Synchronized fun begin(path: File) {
            path.parentFile?.mkdirs()
            file = FileOutputStream(path)
            waitingForIdr = true
            units = 0
            keyframes = 0
            bytes = 0
        }

        @Synchronized
        override fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            val output = file ?: return
            val idr = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (waitingForIdr) {
                if (!idr || csd.isEmpty()) return
                csd.forEach { output.write(it) }
                waitingForIdr = false
                firstPts = presentationTimeUs
            }
            if (bytes + size > 8 * 1024 * 1024) {
                errors++
                return
            }
            val copy = ByteArray(size)
            buffer.duplicate().apply {
                position(offset)
                limit(offset + size)
            }.get(copy)
            output.write(copy)
            units++
            bytes += size
            if (idr) keyframes++
            lastPts = presentationTimeUs
        }

        @Synchronized fun end(state: String) {
            close()
            check(units > 0 && keyframes > 0 && bytes > 0 && lastPts > firstPts) { "AVC_WINDOW_EMPTY_$state" }
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_video_window state=$state au=$units bytes=$bytes idr=$keyframes " +
                    "first_pts=$firstPts last_pts=$lastPts errors=$errors",
            )
        }

        override fun onEncoderError(error: VideoEncoderError) {
            errors++
            Log.i("WarpnectDirectRoot", "direct_root_video_encoder_error error=$error")
        }

        @Synchronized override fun close() {
            file?.close()
            file = null
        }
    }
}
