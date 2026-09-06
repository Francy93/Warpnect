package io.warpnect.debug.audio

import android.app.Activity
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

/** Debug-only structural probe of the production SystemAudio controller and Shizuku UserService. */
class SystemAudioProductionValidationActivity : Activity() {
    private val started = AtomicBoolean(false)
    private val chunkCount = AtomicLong(0)
    private val frameCount = AtomicLong(0)

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
            val prepared = runBlocking { controller.prepare(request, StructuralSink()) }
            logResult("PREPARE", prepared.error)
            logSnapshot("PREPARE_SNAPSHOT", prepared.snapshot)
            if (!prepared.isSuccess) return

            val startedResult = runBlocking { controller.start() }
            logResult("START", startedResult.error)
            logSnapshot("START_SNAPSHOT", startedResult.snapshot)
            if (!startedResult.isSuccess) return

            Thread.sleep(500L)
            val running = controller.snapshot()
            logSnapshot("RUNNING_SNAPSHOT", running)
            Log.i(TAG, "SYSTEM_AUDIO_STRUCTURAL_DATA chunks=${chunkCount.get()} frames=${frameCount.get()}")
        } catch (exception: RuntimeException) {
            Log.i(TAG, "SYSTEM_AUDIO_UNEXPECTED_FAILURE type=${exception.javaClass.simpleName}")
        } finally {
            val stopped = runCatching { runBlocking { controller.stop() } }.getOrNull()
            if (stopped != null) {
                logResult("STOP", stopped.error)
                logSnapshot("STOP_SNAPSHOT", stopped.snapshot)
            }
            controller.close()
            runOnUiThread(::finish)
        }
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
        }

        override fun onCaptureError(error: AudioCaptureError) {
            Log.i(TAG, "SYSTEM_AUDIO_SINK_ERROR error=$error")
        }
    }

    private companion object {
        const val TAG = "WarpnectSystemAudio"
    }
}
