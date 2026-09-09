package io.warpnect.debug.audio

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.platform.audio.capture.AndroidSystemAudioCaptureController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking

/** Debug-only structural probe of the production SystemAudio controller and Shizuku UserService. */
class SystemAudioProductionValidationActivity : Activity() {
    private val started = AtomicBoolean(false)
    private val chunkCount = AtomicLong(0)
    private val frameCount = AtomicLong(0)
    private val nonZeroSampleCount = AtomicLong(0)
    private val sampleSquareSum = AtomicLong(0)
    private val sampleCount = AtomicLong(0)
    private val peakSample = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Warpnect production system-audio validation" })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && started.compareAndSet(false, true)) {
            Thread(::exerciseProductionController, "WarpnectSystemAudioDebug").start()
        }
    }

    private fun exerciseProductionController() {
        val controller = AndroidSystemAudioCaptureController(applicationContext)
        var tone: ValidationTone? = null
        val request = AudioCaptureRequest(
            source = AudioCaptureSource.SystemAudio,
            preferredSampleRateHz = 48_000,
            channelCount = 2,
            targetChunkDurationUs = 5_000L,
        )
        try {
            val capabilities = controller.queryCapabilities(request)
            Log.i(
                TAG,
                "SYSTEM_AUDIO_CAPABILITIES available=${capabilities.available} " +
                    "backend=${capabilities.privilegedBackendAvailable} " +
                    "rate=${capabilities.selectedSampleRateHz} channels=${capabilities.channelCount} " +
                    "encoding=${capabilities.encoding} timestamp=${capabilities.timestampSupport} " +
                    "error=${capabilities.lastError}",
            )
            if (!capabilities.available) return
            val prepared = runBlocking { controller.prepare(request, StructuralSink()) }
            logResult("PREPARE", prepared.error)
            logSnapshot("PREPARE_SNAPSHOT", prepared.snapshot)
            if (!prepared.isSuccess) return

            val startedResult = runBlocking { controller.start() }
            logResult("START", startedResult.error)
            logSnapshot("START_SNAPSHOT", startedResult.snapshot)
            if (!startedResult.isSuccess) return

            tone = startValidationTone()
            Thread.sleep(TONE_CAPTURE_DURATION_MS)
            val running = controller.snapshot()
            logSnapshot("RUNNING_SNAPSHOT", running)
            logCapturedAudio()
        } catch (exception: RuntimeException) {
            Log.i(
                TAG,
                "SYSTEM_AUDIO_UNEXPECTED_FAILURE type=${exception.javaClass.simpleName} " +
                    "message=${exception.message ?: "none"}",
            )
        } finally {
            tone?.stop()
            val stopped = runCatching { runBlocking { controller.stop() } }.getOrNull()
            if (stopped != null) {
                logResult("STOP", stopped.error)
                logSnapshot("STOP_SNAPSHOT", stopped.snapshot)
            }
            controller.close()
            runOnUiThread(::finish)
        }
    }

    private fun startValidationTone(): ValidationTone {
        val samples = validationTonePcm()
        val bufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferBytes <= 0) {
            throw IllegalStateException("validation AudioTrack minimum buffer=$bufferBytes")
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("validation AudioTrack is not initialized")
        }
        track.play()
        val writing = AtomicBoolean(true)
        val writer = Thread(
            {
                while (writing.get()) {
                    val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) return@Thread
                }
            },
            "WarpnectSystemAudioTone",
        ).apply { start() }
        Log.i(
            TAG,
            "SYSTEM_AUDIO_TONE_STARTED rate=$SAMPLE_RATE_HZ frames=$TONE_FRAME_COUNT bufferBytes=$bufferBytes",
        )
        return ValidationTone(track, writing, writer)
    }

    private fun validationTonePcm(): ByteArray {
        val samples = ByteBuffer.allocate(TONE_FRAME_COUNT * CHANNEL_COUNT * PCM_16_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until TONE_FRAME_COUNT) {
            val value = (sin(2.0 * PI * TONE_FREQUENCY_HZ * frame / SAMPLE_RATE_HZ) * TONE_AMPLITUDE)
                .toInt()
                .toShort()
            repeat(CHANNEL_COUNT) {
                samples.putShort(value)
            }
        }
        return samples.array()
    }

    private fun logCapturedAudio() {
        val capturedSamples = sampleCount.get()
        val rms = if (capturedSamples == 0L) {
            0.0
        } else {
            kotlin.math.sqrt(sampleSquareSum.get().toDouble() / capturedSamples)
        }
        Log.i(
            TAG,
            "SYSTEM_AUDIO_TONE_CAPTURE chunks=${chunkCount.get()} frames=${frameCount.get()} " +
                "samples=$capturedSamples nonZero=${nonZeroSampleCount.get()} peak=${peakSample.get()} " +
                "rms=${"%.1f".format(java.util.Locale.ROOT, rms)}",
        )
    }

    private fun logResult(stage: String, error: AudioCaptureError) {
        Log.i(TAG, "SYSTEM_AUDIO_$stage result=$error")
    }

    private fun logSnapshot(stage: String, snapshot: io.warpnect.audio.capture.AudioCaptureSnapshot) {
        Log.i(
            TAG,
            "SYSTEM_AUDIO_$stage state=${snapshot.state} error=${snapshot.lastError} " +
                "rate=${snapshot.sampleRateHz} channels=${snapshot.channelCount} " +
                "bufferFrames=${snapshot.actualAudioRecordBufferFrames} chunks=${snapshot.chunksCaptured} " +
                "frames=${snapshot.framesCaptured}",
        )
    }

    private inner class StructuralSink : PcmAudioSink {
        override fun onFormatChanged(format: AudioCaptureFormat) = Unit

        override fun onPcmChunk(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
        ) {
            chunkCount.incrementAndGet()
            this@SystemAudioProductionValidationActivity.frameCount.addAndGet(frameCount.toLong())
            val samples = buffer.duplicate().order(ByteOrder.nativeOrder())
            samples.position(offset)
            samples.limit(offset + sizeBytes)
            while (samples.remaining() >= PCM_16_BYTES) {
                val value = samples.short.toInt()
                val magnitude = if (value == Short.MIN_VALUE.toInt()) {
                    Short.MAX_VALUE.toInt() + 1
                } else {
                    kotlin.math.abs(value)
                }
                this@SystemAudioProductionValidationActivity.sampleCount.incrementAndGet()
                this@SystemAudioProductionValidationActivity.sampleSquareSum.addAndGet(value.toLong() * value)
                if (value != 0) {
                    this@SystemAudioProductionValidationActivity.nonZeroSampleCount.incrementAndGet()
                }
                this@SystemAudioProductionValidationActivity.peakSample.accumulateAndGet(magnitude, ::maxOf)
            }
        }

        override fun onCaptureError(error: AudioCaptureError) {
            Log.i(TAG, "SYSTEM_AUDIO_SINK_ERROR error=$error")
        }
    }

    private data class ValidationTone(
        val track: AudioTrack,
        val writing: AtomicBoolean,
        val writer: Thread,
    ) {
        fun stop() {
            writing.set(false)
            runCatching { track.pause() }
            runCatching { writer.join(TONE_WRITER_JOIN_TIMEOUT_MS) }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val TAG = "WarpnectSystemAudio"
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT = 2
        const val PCM_16_BYTES = 2
        const val TONE_FREQUENCY_HZ = 997.0
        const val TONE_AMPLITUDE = 8_192.0
        const val TONE_FRAME_COUNT = SAMPLE_RATE_HZ / 20
        const val TONE_CAPTURE_DURATION_MS = 1_500L
        const val TONE_WRITER_JOIN_TIMEOUT_MS = 500L
    }
}
