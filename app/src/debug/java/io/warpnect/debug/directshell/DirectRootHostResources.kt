package io.warpnect.debug.directshell

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.SystemClock
import android.util.Log
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.platform.audio.capture.AndroidSystemAudioCaptureController
import io.warpnect.platform.audio.capture.DirectRootSystemAudioCapability
import io.warpnect.platform.audio.encoder.NativeOpusAudioEncoderController
import io.warpnect.platform.capture.AndroidVideoCaptureController
import io.warpnect.platform.capture.DirectRootVideoCapability
import io.warpnect.platform.input.injection.DirectRootInputCapability
import io.warpnect.platform.input.injection.InputInjectionControllerSelector
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.readiness.CachedCapabilityEvidenceMatrixBuilder
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRecord
import io.warpnect.platform.privileged.readiness.RootHostEvidence
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot
import io.warpnect.platform.video.encoder.AndroidExactVideoEncoderCapabilityProbe
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.platform.video.encoder.ExactVideoEncoderCapabilityKey
import io.warpnect.platform.video.encoder.VideoEncoderDiscovery
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderCodecInfo
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSupport
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

/** Explicit lab resource adapter invoked by normal Host admission, never by background observation. */
internal class LaboratoryDirectRootHostResources private constructor(
    context: Context,
    private val runtime: DirectRootRuntime,
    evidence: List<PersistentCapabilityEvidenceRecord>,
) : io.warpnect.platform.privileged.runtime.DirectRootHostResources {
    override val generation = requireNotNull(runtime.snapshot.generation)
    private val video = AndroidVideoCaptureController(context)
    private val discovery = LaboratoryRootVideoEncoderDiscovery()
    private val encoder = AndroidMediaCodecVideoEncoder(discovery)
    private val audio = AndroidSystemAudioCaptureController(context)
    private val input = InputInjectionControllerSelector.create(context)
    private val output = Counts()
    private var closed = false
    private var cleanupErrors = 0
    override lateinit var snapshot: AndroidCapabilityProbeSnapshot
        private set
    override val matrix: PrivilegedCapabilityMatrix = CachedCapabilityEvidenceMatrixBuilder.build(evidence)
        .withQualifications(RootHostEvidence.restore(runtime.snapshot, evidence))

    private fun acquire() = runBlocking {
        try {
            check(runtime.snapshot.provenance == RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT)
            val videoRequest =
                VideoEncoderRequest(
                    width = 640,
                    height = 360,
                    frameRate = 30,
                    bitrateBps = 1_000_000,
                    iFrameIntervalSeconds = 1f,
                )
            val captureCapabilities = video.queryCapabilities()
            check(captureCapabilities.backendAvailable && captureCapabilities.lastError == CaptureError.None)
            val encoding = discovery.query(videoRequest)
            check(encoding.isSupported) { "root_host_encoder_unavailable" }
            val configured = encoder.prepare(videoRequest, output)
            check(
                configured.isSuccess && configured.inputSurface?.isValid == true,
            ) { "root_host_encoder_prepare_failed" }
            check(encoder.start().isSuccess)
            check(video.start(CaptureRequest(0, 640, 360), requireNotNull(configured.inputSurface)).isSuccess)
            val audioRequest = AudioCaptureRequest(AudioCaptureSource.SystemAudio)
            val audioCapabilities = audio.queryCapabilities(audioRequest)
            check(audioCapabilities.available)
            check(audio.prepare(audioRequest, output).isSuccess)
            check(audio.start().isSuccess)
            val inputCapabilities = input.queryCapabilities()
            check(inputCapabilities.serviceAvailable)
            check(input.prepare(InputInjectionConfig()).isSuccess)
            check(input.start().isSuccess)
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while ((output.pcmFrames.get() == 0L || output.accessUnits.get() == 0L || !output.config) &&
                SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(20)
            check(
                output.pcmFrames.get() > 0 && output.accessUnits.get() > 0 &&
                    output.config && output.errors.get() == 0L,
            ) {
                "root_host_owned_resources_not_progressing"
            }
            val opus = NativeOpusAudioEncoderController().use {
                it.queryCapabilities(AudioEncoderRequest(source = AudioCaptureSource.SystemAudio, channelCount = 2))
            }
            snapshot = AndroidCapabilityProbeSnapshot(
                lanSecurePathAvailable = true,
                privilegedVideoCapture = captureCapabilities,
                videoEncoder = encoding,
                systemAudioCapture = audioCapabilities,
                audioEncoder = opus,
                inputInjection = inputCapabilities,
                laboratorySurfaceEncoderQualified = true,
            )
            check(runtime.checkLiveness())
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_host_resources state=ACQUIRED generation=$generation " +
                    "video_generation=$generation audio_generation=$generation input_generation=$generation " +
                    "avc_config=${output.config} access_units=${output.accessUnits.get()} " +
                    "pcm_frames=${output.pcmFrames.get()} input_active=${input.snapshot().activePressedKeys} " +
                    "lab_surface_encoder=true hardware_encode_claim=false",
            )
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runBlocking {
            releaseStep { input.stop() }
            releaseStep { input.close() }
            releaseStep { audio.stop() }
            releaseStep { audio.close() }
            releaseStep { video.stop() }
            releaseStep { video.close() }
            releaseStep { encoder.stop() }
            releaseStep { encoder.close() }
        }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_host_resources state=RELEASED generation=$generation " +
                "pcm_frames=${output.pcmFrames.get()} access_units=${output.accessUnits.get()} " +
                "errors=${output.errors.get()} cleanup_errors=$cleanupErrors",
        )
    }

    private suspend fun releaseStep(operation: suspend () -> Unit) {
        runCatching { operation() }.onFailure {
            cleanupErrors++
            Log.w("WarpnectDirectRoot", "direct_root_host_release_failed boundary=${it.javaClass.simpleName}")
        }
    }

    companion object {
        fun acquire(
            context: Context,
            runtime: DirectRootRuntime,
            evidence: List<PersistentCapabilityEvidenceRecord>,
        ): LaboratoryDirectRootHostResources {
            val restored = RootHostEvidence.restore(runtime.snapshot, evidence).associateBy { it.capability }
            DirectRootVideoCapability.restoreForHost(restored.getValue(PrivilegedCapability.VIDEO))
            DirectRootSystemAudioCapability.restoreForHost(restored.getValue(PrivilegedCapability.SYSTEM_AUDIO))
            DirectRootInputCapability.restoreForHost(restored.getValue(PrivilegedCapability.INPUT))
            check(
                DirectRootVideoCapability.selectForHost(true) && DirectRootSystemAudioCapability.selectForHost(true) &&
                    DirectRootInputCapability.selectForHost(true),
            )
            return LaboratoryDirectRootHostResources(context, runtime, evidence).also { it.acquire() }
        }
    }

    private class Counts : EncodedVideoSink, PcmAudioSink {
        val pcmFrames = AtomicLong()
        val accessUnits = AtomicLong()
        val errors = AtomicLong()

        @Volatile var config = false
        override fun onOutputFormatChanged(format: VideoEncoderOutputFormat) {
            config = format.hasCodecSpecificData()
        }
        override fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if (size > 0) accessUnits.incrementAndGet()
        }
        override fun onEncoderError(error: VideoEncoderError) {
            errors.incrementAndGet()
        }
        override fun onFormatChanged(format: AudioCaptureFormat) = Unit
        override fun onCaptureError(error: AudioCaptureError) {
            errors.incrementAndGet()
        }
        override fun onPcmChunk(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
        ) {
            pcmFrames.addAndGet(frameCount.toLong())
        }
    }
}

/** Same finite lab codec discovery used by qualification; no product hardware policy override. */
internal class LaboratoryRootVideoEncoderDiscovery : VideoEncoderDiscovery {
    override fun query(value: VideoEncoderRequest): VideoEncoderCapabilities {
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.contains("video/avc") && runCatching {
                val caps = info.getCapabilitiesForType("video/avc")
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    caps.videoCapabilities.areSizeAndRateSupported(
                        value.width, value.height, value.frameRate.toDouble(),
                    ) &&
                    caps.videoCapabilities.bitrateRange.contains(value.bitrateBps) &&
                    (
                        caps.encoderCapabilities.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        ) || AndroidExactVideoEncoderCapabilityProbe().probe(
                            ExactVideoEncoderCapabilityKey.from(info.name, value),
                        ).supported
                        )
            }.getOrDefault(false)
        } ?: return VideoEncoderCapabilities(value, null, null, error = VideoEncoderError.UnsupportedBitrateMode)
        val caps = codec.getCapabilitiesForType("video/avc").videoCapabilities
        return VideoEncoderCapabilities(
            value,
            VideoEncoderCodecInfo(codec.name, null, VideoEncoderHardwareAcceleration.Unknown, null, null, null),
            VideoEncoderSupport(
                true, true, true, true, true, true, true, caps.widthAlignment, caps.heightAlignment,
                caps.supportedWidths.lower, caps.supportedWidths.upper,
                caps.supportedHeights.lower, caps.supportedHeights.upper,
                caps.bitrateRange.lower, caps.bitrateRange.upper,
            ),
        )
    }
}
