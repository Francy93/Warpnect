package io.warpnect.debug.audio

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * DEBUG-only normal Android playback source for SystemAudio transport validation.
 * It writes to AudioTrack only and never accesses Warpnect capture, PCM-ring, or transport APIs.
 */
class SystemAudioE2eToneActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private var tone: ValidationTone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Warpnect SystemAudio E2E tone" })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && started.compareAndSet(false, true)) startTone()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tone?.stop()
        tone = null
        super.onDestroy()
    }

    private fun startTone() {
        val durationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, DEFAULT_DURATION_SECONDS)
            .coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        val startedTone = runCatching { ValidationTone().apply { start() } }.getOrElse { exception ->
            Log.i(
                TAG,
                "SYSTEM_AUDIO_E2E_TONE_FAILED type=${exception.javaClass.simpleName} " +
                    "message=${exception.message ?: "none"}",
            )
            finish()
            return
        }
        tone = startedTone
        Log.i(
            TAG,
            "SYSTEM_AUDIO_E2E_TONE_STARTED rate=$SAMPLE_RATE_HZ channels=$CHANNEL_COUNT " +
                "frequency_hz=$FREQUENCY_HZ amplitude=$AMPLITUDE duration_s=$durationSeconds",
        )
        handler.postDelayed(
            {
                startedTone.stop()
                Log.i(TAG, "SYSTEM_AUDIO_E2E_TONE_STOPPED frames_written=${startedTone.framesWritten.get()}")
                finish()
            },
            durationSeconds * 1_000L,
        )
    }

    private class ValidationTone {
        val framesWritten = AtomicLong(0)
        private val writing = AtomicBoolean(false)
        private lateinit var track: AudioTrack
        private lateinit var writer: Thread

        fun start() {
            val bufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(bufferBytes > 0) { "AudioTrack minimum buffer=$bufferBytes" }
            track = AudioTrack.Builder()
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
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack is not initialized" }
            val samples = samples()
            writing.set(true)
            track.play()
            writer = Thread(
                {
                    while (writing.get()) {
                        val samplesWritten = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        if (samplesWritten <= 0) return@Thread
                        framesWritten.addAndGet((samplesWritten / CHANNEL_COUNT).toLong())
                    }
                },
                "WarpnectSystemAudioE2eTone",
            ).apply { start() }
        }

        fun stop() {
            if (!writing.getAndSet(false)) return
            runCatching { track.pause() }
            runCatching { writer.join(TONE_WRITER_JOIN_TIMEOUT_MS) }
            runCatching { track.flush() }
            runCatching { track.release() }
        }

        private fun samples(): ShortArray = ShortArray(TONE_FRAME_COUNT * CHANNEL_COUNT).also { output ->
            for (frame in 0 until TONE_FRAME_COUNT) {
                val sample = (sin(2.0 * PI * FREQUENCY_HZ * frame / SAMPLE_RATE_HZ) * AMPLITUDE)
                    .toInt()
                    .toShort()
                val offset = frame * CHANNEL_COUNT
                output[offset] = sample
                output[offset + 1] = sample
            }
        }
    }

    private companion object {
        const val TAG = "WarpnectSystemAudio"
        const val EXTRA_DURATION_SECONDS = "durationSeconds"
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT = 2
        const val FREQUENCY_HZ = 997.0
        const val AMPLITUDE = 8_192.0
        const val TONE_FRAME_COUNT = SAMPLE_RATE_HZ / 20
        const val DEFAULT_DURATION_SECONDS = 60
        const val MIN_DURATION_SECONDS = 5
        const val MAX_DURATION_SECONDS = 120
        const val TONE_WRITER_JOIN_TIMEOUT_MS = 500L
    }
}
