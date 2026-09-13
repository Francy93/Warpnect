package io.warpnect.debug.directshell

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.platform.audio.capture.SharedPcmAudioDrain
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * DEBUG-only normal-process owner for one real production PCM-ring generation.
 *
 * The ring layout and drain are production classes. This owner only replaces the Shizuku setup
 * transport for the spike: it gives the real shell producer the two complementary pipe ends via
 * the narrowly authenticated provider endpoint.
 */
@TargetApi(Build.VERSION_CODES.S)
internal object DirectShellPcmRingProbeSession {
    private const val TAG = "WarpnectDirectShellRing"
    private const val SAMPLE_RATE_HZ = 48_000
    private const val CHANNEL_COUNT = 2
    private const val BYTES_PER_FRAME = CHANNEL_COUNT * Short.SIZE_BYTES
    private const val TARGET_CHUNK_FRAMES = 240
    private const val TARGET_CHUNK_DURATION_US = 5_000L
    private const val SLOT_COUNT = 8
    private const val PAYLOAD_CAPACITY_BYTES = TARGET_CHUNK_FRAMES * BYTES_PER_FRAME

    private var active: ActiveGeneration? = null

    @JvmStatic
    @Synchronized
    fun arm(generationId: Long, secret: ByteArray): Result {
        if (generationId <= 0L) return Result.failure("INVALID_GENERATION")
        val safeSecret = try {
            DirectShellProbeProtocol.requireSecret(secret)
        } catch (_: IllegalArgumentException) {
            return Result.failure("INVALID_SECRET")
        }
        releaseLocked("replaced")
        val fdBeforeArm = fdCount()

        var sharedMemory: SharedMemory? = null
        var initialMapping: ByteBuffer? = null
        var notifyPipe: Array<ParcelFileDescriptor>? = null
        var ackPipe: Array<ParcelFileDescriptor>? = null
        var drain: SharedPcmAudioDrain? = null
        try {
            val regionBytes = SharedPcmAudioRingLayout.totalBytes(SLOT_COUNT, PAYLOAD_CAPACITY_BYTES)
            check(regionBytes > 0) { "invalid_ring_size" }
            sharedMemory = SharedMemory.create("warpnect_directshell_pcm_ring_$generationId", regionBytes)
            initialMapping = sharedMemory.mapReadWrite().order(ByteOrder.nativeOrder())
            check(SharedPcmAudioRingLayout.initialize(initialMapping, SLOT_COUNT, PAYLOAD_CAPACITY_BYTES)) {
                "ring_initialize_failed"
            }
            SharedMemory.unmap(initialMapping)
            initialMapping = null

            notifyPipe = ParcelFileDescriptor.createPipe()
            ackPipe = ParcelFileDescriptor.createPipe()
            val metrics = ConsumerMetrics(generationId)
            metrics.onFormatChanged(productionFormat())
            drain = SharedPcmAudioDrain(
                sharedMemory = sharedMemory,
                notifyReadFd = notifyPipe[0],
                ackWriteFd = ackPipe[1],
                sink = metrics,
                onError = { error ->
                    metrics.recordDrainError(error)
                    Unit
                },
                onRingState = { occupancy, highWater, overruns ->
                    metrics.recordRingState(occupancy, highWater, overruns)
                    Unit
                },
                onPcmAccepted = { _, _, _, _, _ ->
                    metrics.recordNotificationWakeup()
                    Unit
                },
            )
            val drainError = drain.start()
            if (drainError != AudioCaptureError.None) {
                drain.close()
                closePipe(notifyPipe)
                closePipe(ackPipe)
                Arrays.fill(safeSecret, 0.toByte())
                return Result.failure("DRAIN_START_${drainError.name}")
            }
            active = ActiveGeneration(
                generationId = generationId,
                secret = safeSecret,
                sharedMemory = sharedMemory,
                notifyWriteFd = notifyPipe[1],
                ackReadFd = ackPipe[0],
                drain = drain,
                metrics = metrics,
                regionBytes = regionBytes,
                fdBeforeArm = fdBeforeArm,
            )
            log("APP_RING_ARM generation=$generationId ${payloadLocked("ARMED")}")
            return Result.success(payloadLocked("ARMED"))
        } catch (exception: Exception) {
            runCatching { drain?.close() }
            closePipe(notifyPipe)
            closePipe(ackPipe)
            initialMapping?.let { mapping -> runCatching { SharedMemory.unmap(mapping) } }
            sharedMemory?.let { memory -> runCatching { memory.close() } }
            Arrays.fill(safeSecret, 0.toByte())
            return Result.failure("RING_ARM_FAILED_${exception.javaClass.simpleName}")
        }
    }

    @JvmStatic
    @Synchronized
    fun issueDescriptorSet(
        callerUid: Int,
        callerPid: Int,
        magic: Int,
        version: Int,
        generationId: Long,
        regionBytes: Int,
        slotCount: Int,
        payloadCapacityBytes: Int,
        proof: ByteArray?,
    ): DescriptorReply {
        val current = active
        val result = when {
            callerUid != Process.SHELL_UID -> DirectShellPcmRingProbeBinderProtocol.Result.CALLER_UID_REJECTED
            current == null -> DirectShellPcmRingProbeBinderProtocol.Result.NOT_ARMED
            else -> DirectShellPcmRingProbeBinderProtocol.validate(
                current.generationId,
                current.descriptorIssued,
                current.regionBytes,
                SLOT_COUNT,
                PAYLOAD_CAPACITY_BYTES,
                magic,
                version,
                generationId,
                regionBytes,
                slotCount,
                payloadCapacityBytes,
                proof,
                current.secret,
            )
        }
        if (result != DirectShellPcmRingProbeBinderProtocol.Result.OK || current == null) {
            log(
                "APP_RING_BRIDGE_REJECT result=${result.name} caller_uid=$callerUid " +
                    "caller_pid=$callerPid generation=$generationId",
            )
            return DescriptorReply.failure(result, "descriptor_set_rejected")
        }
        current.descriptorIssued = true
        log(
            "APP_RING_DESCRIPTOR_ISSUED generation=${current.generationId} caller_uid=$callerUid " +
                "caller_pid=$callerPid ${payloadLocked("DESCRIPTOR_ISSUED")}",
        )
        return DescriptorReply.success(
            current.sharedMemory,
            current.notifyWriteFd,
            current.ackReadFd,
            "descriptor_set_issued",
        )
    }

    @JvmStatic
    @Synchronized
    fun resetMetrics(generationId: Long): Result {
        val current = currentGeneration(generationId) ?: return Result.failure("GENERATION_NOT_ACTIVE")
        current.metrics.reset()
        log("APP_RING_METRICS_RESET generation=$generationId")
        return Result.success(payloadLocked("METRICS_RESET"))
    }

    @JvmStatic
    @Synchronized
    fun metrics(generationId: Long): Result {
        if (currentGeneration(generationId) == null) return Result.failure("GENERATION_NOT_ACTIVE")
        return Result.success(payloadLocked("METRICS"))
    }

    @JvmStatic
    @Synchronized
    fun finish(generationId: Long): Result {
        val current = currentGeneration(generationId) ?: return Result.failure("GENERATION_NOT_ACTIVE")
        val fdBeforeRelease = fdCount()
        releaseLocked("finished")
        val payload = "generation=$generationId fd_before_release=$fdBeforeRelease fd_after_release=${fdCount()}"
        log("APP_RING_FINISH $payload")
        return Result.success(payload)
    }

    @JvmStatic
    @Synchronized
    fun verifyNormalCallerRejected(context: Context, generationId: Long): Result {
        val current = currentGeneration(generationId) ?: return Result.failure("GENERATION_NOT_ACTIVE")
        val proof = DirectShellPcmRingProbeBinderProtocol.createProof(
            current.secret,
            generationId,
            DirectShellPcmRingProbeBinderProtocol.MAGIC,
            DirectShellPcmRingProbeBinderProtocol.VERSION,
            current.regionBytes,
            SLOT_COUNT,
            PAYLOAD_CAPACITY_BYTES,
        )
        try {
            val request = android.os.Bundle().apply {
                putInt(DirectShellPcmRingProbeBinderProtocol.KEY_MAGIC, DirectShellPcmRingProbeBinderProtocol.MAGIC)
                putInt(DirectShellPcmRingProbeBinderProtocol.KEY_VERSION, DirectShellPcmRingProbeBinderProtocol.VERSION)
                putLong(DirectShellPcmRingProbeBinderProtocol.KEY_GENERATION_ID, generationId)
                putInt(DirectShellPcmRingProbeBinderProtocol.KEY_REGION_BYTES, current.regionBytes)
                putInt(DirectShellPcmRingProbeBinderProtocol.KEY_SLOT_COUNT, SLOT_COUNT)
                putInt(
                    DirectShellPcmRingProbeBinderProtocol.KEY_PAYLOAD_CAPACITY_BYTES,
                    PAYLOAD_CAPACITY_BYTES,
                )
                putByteArray(DirectShellPcmRingProbeBinderProtocol.KEY_PROOF, proof)
            }
            val response = context.contentResolver.call(
                Uri.parse("content://${DirectShellPcmRingProbeBinderProtocol.PROVIDER_AUTHORITY}"),
                DirectShellPcmRingProbeBinderProtocol.PROVIDER_METHOD_OPEN_PCM_RING,
                null,
                request,
            ) ?: return Result.failure("NULL_RESPONSE")
            val result = DirectShellPcmRingProbeBinderProtocol.Result.fromCode(
                response.getInt(
                    DirectShellPcmRingProbeBinderProtocol.KEY_RESULT_CODE,
                    DirectShellPcmRingProbeBinderProtocol.Result.INTERNAL_ERROR.code,
                ),
            )
            return if (result == DirectShellPcmRingProbeBinderProtocol.Result.CALLER_UID_REJECTED) {
                Result.success(result.name)
            } else {
                Result.failure(result.name)
            }
        } catch (exception: RuntimeException) {
            return Result.failure("CALL_FAILED_${exception.javaClass.simpleName}")
        } finally {
            Arrays.fill(proof, 0.toByte())
        }
    }

    @SuppressLint("NewApi")
    private fun releaseLocked(reason: String) {
        val current = active ?: return
        active = null
        runCatching { current.drain.close() }
        runCatching { current.notifyWriteFd.close() }
        runCatching { current.ackReadFd.close() }
        Arrays.fill(current.secret, 0.toByte())
        log("APP_RING_RELEASE reason=$reason generation=${current.generationId}")
    }

    private fun currentGeneration(generationId: Long): ActiveGeneration? =
        active?.takeIf { it.generationId == generationId }

    private fun payloadLocked(event: String): String {
        val current = active ?: return "event=$event active_generation=none"
        return "event=$event generation=${current.generationId} descriptor_issued=${current.descriptorIssued} " +
            "app_fd_before_arm=${current.fdBeforeArm} app_fd_now=${fdCount()} " +
            "region_bytes=${current.regionBytes} slot_count=$SLOT_COUNT " +
            "payload_capacity_bytes=$PAYLOAD_CAPACITY_BYTES ${current.metrics.payload()}"
    }

    private fun productionFormat() = AudioCaptureFormat(
        source = AudioCaptureSource.SystemAudio,
        sampleRateHz = SAMPLE_RATE_HZ,
        channelCount = CHANNEL_COUNT,
        encoding = AudioPcmEncoding.Pcm16,
        bytesPerFrame = BYTES_PER_FRAME,
        targetFramesPerChunk = TARGET_CHUNK_FRAMES,
        targetChunkDurationUs = TARGET_CHUNK_DURATION_US,
    )

    private fun closePipe(pipe: Array<ParcelFileDescriptor>?) {
        pipe?.forEach { descriptor -> runCatching { descriptor.close() } }
    }

    private fun fdCount(): Int = File("/proc/self/fd").list()?.size ?: -1

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    internal data class Result(
        val success: Boolean,
        val payload: String,
    ) {
        companion object {
            fun success(payload: String) = Result(true, payload)
            fun failure(payload: String) = Result(false, payload)
        }
    }

    internal data class DescriptorReply(
        val result: DirectShellPcmRingProbeBinderProtocol.Result,
        val sharedMemory: SharedMemory?,
        val notifyWriteFd: ParcelFileDescriptor?,
        val ackReadFd: ParcelFileDescriptor?,
        val detail: String,
    ) {
        companion object {
            fun success(
                sharedMemory: SharedMemory,
                notifyWriteFd: ParcelFileDescriptor,
                ackReadFd: ParcelFileDescriptor,
                detail: String,
            ) = DescriptorReply(
                DirectShellPcmRingProbeBinderProtocol.Result.OK,
                sharedMemory,
                notifyWriteFd,
                ackReadFd,
                detail,
            )

            fun failure(result: DirectShellPcmRingProbeBinderProtocol.Result, detail: String) =
                DescriptorReply(result, null, null, null, detail)
        }
    }

    private class ActiveGeneration(
        val generationId: Long,
        val secret: ByteArray,
        val sharedMemory: SharedMemory,
        val notifyWriteFd: ParcelFileDescriptor,
        val ackReadFd: ParcelFileDescriptor,
        val drain: SharedPcmAudioDrain,
        val metrics: ConsumerMetrics,
        val regionBytes: Int,
        val fdBeforeArm: Int,
        var descriptorIssued: Boolean = false,
    )

    private class ConsumerMetrics(
        private val generationId: Long,
    ) : PcmAudioSink {
        private var chunks = 0L
        private var bytes = 0L
        private var frames = 0L
        private var samples = 0L
        private var nonZeroSamples = 0L
        private var peak = 0
        private var sampleSquares = 0.0
        private var acceptedNotifications = 0L
        private var ringOccupancy = 0
        private var ringHighWater = 0
        private var ringOverruns = 0
        private var drainErrors = 0L
        private var firstReadLogged = false

        @Synchronized
        override fun onFormatChanged(format: AudioCaptureFormat) = Unit

        @Synchronized
        override fun onPcmChunk(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
        ) {
            val bounded = sizeBytes.coerceAtLeast(0).coerceAtMost(buffer.capacity() - offset)
            if (bounded <= 0) return
            val copy = ByteArray(bounded)
            val source = buffer.duplicate()
            source.position(offset)
            source.limit(offset + bounded)
            source.get(copy)
            val completeBytes = bounded - (bounded % BYTES_PER_FRAME)
            for (position in 0 until completeBytes step Short.SIZE_BYTES) {
                val sample = (
                    (copy[position].toInt() and 0xff) or
                        ((copy[position + 1].toInt() and 0xff) shl 8)
                    ).toShort().toInt()
                val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() + 1 else abs(sample)
                samples++
                sampleSquares += sample.toDouble() * sample.toDouble()
                if (sample != 0) nonZeroSamples++
                peak = maxOf(peak, magnitude)
            }
            chunks++
            bytes += completeBytes
            frames += frameCount.toLong()
            if (!firstReadLogged) {
                firstReadLogged = true
                log(
                    "APP_WAKE_APP_READ generation=$generationId bytes=$completeBytes frames=$frameCount " +
                        "timestamp_quality=${timestampQuality.name}",
                )
            }
        }

        @Synchronized
        override fun onCaptureError(error: AudioCaptureError) {
            drainErrors++
            log("APP_DRAIN_ERROR generation=$generationId error=${error.name}")
        }

        @Synchronized
        fun recordNotificationWakeup() {
            acceptedNotifications++
        }

        @Synchronized
        fun recordRingState(occupancy: Int, highWater: Int, overruns: Int) {
            ringOccupancy = occupancy
            ringHighWater = highWater
            ringOverruns = overruns
        }

        @Synchronized
        fun recordDrainError(error: AudioCaptureError) {
            drainErrors++
            log("APP_DRAIN_ERROR generation=$generationId error=${error.name}")
        }

        @Synchronized
        fun reset() {
            chunks = 0L
            bytes = 0L
            frames = 0L
            samples = 0L
            nonZeroSamples = 0L
            peak = 0
            sampleSquares = 0.0
            acceptedNotifications = 0L
        }

        @Synchronized
        fun payload(): String {
            val rms = if (samples == 0L) 0.0 else sqrt(sampleSquares / samples)
            return "consumer_chunks=$chunks consumer_bytes=$bytes consumer_frames=$frames " +
                "consumer_samples=$samples consumer_non_zero_samples=$nonZeroSamples " +
                "consumer_peak=$peak consumer_rms=${"%.3f".format(java.util.Locale.ROOT, rms)} " +
                "consumer_real_pcm=${frames > 0L && nonZeroSamples > 0L && rms > 1.0} " +
                "consumer_notification_wakeups=$acceptedNotifications ring_occupancy=$ringOccupancy " +
                "ring_high_water=$ringHighWater ring_overruns=$ringOverruns drain_errors=$drainErrors " +
                "sequence_discontinuities=unavailable_existing_drain_callback"
        }
    }
}
