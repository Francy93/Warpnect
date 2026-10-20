package io.warpnect.platform.audio.capture

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioCaptureState
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMicrophoneCompatibilityInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val request = AudioCaptureRequest(
        source = AudioCaptureSource.MicrophoneAudio,
        preferredSampleRateHz = 48_000,
        channelCount = 1,
        targetChunkDurationUs = 5_000,
        requireStrictFormat = true,
    )

    @Test
    fun repeatedCaptureStartsFreshAndStopsWithoutLatePcm() = runBlocking {
        val controller = AndroidMicrophoneAudioCaptureController(context)
        try {
            repeat(3) { cycle ->
                val frames = AtomicLong()
                val invalid = AtomicLong()
                val firstInvalid = AtomicReference<String?>()
                var nextPosition = 0L
                var previousTime = 0L
                var previousQuality = AudioTimestampQuality.Unavailable
                val sink = object : PcmAudioSink {
                    override fun onFormatChanged(format: AudioCaptureFormat) {
                        if (format.sampleRateHz != 48_000 || format.channelCount != 1 ||
                            format.bytesPerFrame != 2 || format.targetFramesPerChunk != 240
                        ) {
                            invalid.incrementAndGet()
                        }
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
                        // RFC-003A's hardware anchor can revise an earlier read-completion estimate.
                        if (previousQuality != AudioTimestampQuality.Unavailable &&
                            timestampQuality != previousQuality
                        ) {
                            Log.i(
                                "WarpnectMicrophoneProbe",
                                "MIC_TIMESTAMP_QUALITY_REVISION position=$firstFramePosition " +
                                    "delta_ns=${captureTimeNs - previousTime} " +
                                    "from=$previousQuality to=$timestampQuality",
                            )
                        }
                        if (frameCount !in 1..240 || sizeBytes != frameCount * 2 ||
                            firstFramePosition != nextPosition || captureTimeNs <= 0 ||
                            (timestampQuality == previousQuality && captureTimeNs < previousTime)
                        ) {
                            invalid.incrementAndGet()
                            val detail = "cycle=${cycle + 1} frames=$frameCount bytes=$sizeBytes " +
                                "position=$firstFramePosition expected=$nextPosition " +
                                "time=$captureTimeNs previous=$previousTime quality=$timestampQuality " +
                                "previous_quality=$previousQuality"
                            if (firstInvalid.compareAndSet(null, detail)) {
                                Log.w("WarpnectMicrophoneProbe", "MIC_COMPAT_INVALID $detail")
                            }
                        }
                        nextPosition = firstFramePosition + frameCount
                        previousTime = captureTimeNs
                        previousQuality = timestampQuality
                        frames.addAndGet(frameCount.toLong())
                    }
                }
                assertEquals(AudioCaptureError.None, controller.prepare(request, sink).error)
                val prepared = controller.snapshot()
                assertTrue(prepared.actualAudioRecordBufferFrames >= prepared.targetChunkFrames)
                assertEquals(AudioCaptureError.None, controller.start().error)
                Thread.sleep(1_200)
                assertEquals(AudioCaptureError.None, controller.stop().error)
                val stoppedFrames = frames.get()
                Thread.sleep(250)
                assertEquals("No delivery after stop", stoppedFrames, frames.get())
                assertTrue("Real frames must progress", stoppedFrames > 24_000)
                assertEquals("PCM shape and timeline: ${firstInvalid.get()}", 0L, invalid.get())
                assertEquals(AudioCaptureState.Stopped, controller.snapshot().state)
                assertNoCaptureWorker()
                Log.i(
                    "WarpnectMicrophoneProbe",
                    "MIC_COMPAT_REOPEN cycle=${cycle + 1} frames=$stoppedFrames " +
                        "buffer_frames=${prepared.actualAudioRecordBufferFrames} late_pcm=0 invalid=0",
                )
            }
        } finally {
            controller.close()
        }
        assertEquals(AudioCaptureState.Closed, controller.snapshot().state)
        assertNoCaptureWorker()
    }

    @Test
    fun sinkFailureIsReportedAndCaptureCanBeReleased() = runBlocking {
        val failed = CountDownLatch(1)
        val error = arrayOf(AudioCaptureError.None)
        val controller = AndroidMicrophoneAudioCaptureController(context)
        val sink = object : PcmAudioSink {
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
                throw IllegalStateException("Bounded sink failure fixture")
            }

            override fun onCaptureError(captureError: AudioCaptureError) {
                error[0] = captureError
                failed.countDown()
            }
        }
        try {
            assertEquals(AudioCaptureError.None, controller.prepare(request, sink).error)
            assertEquals(AudioCaptureError.None, controller.start().error)
            assertTrue("Real capture reaches sink", failed.await(3, TimeUnit.SECONDS))
            assertEquals(AudioCaptureError.SinkFailure, error[0])
            assertEquals(AudioCaptureError.None, controller.stop().error)
        } finally {
            controller.close()
        }
        assertNoCaptureWorker()
    }

    @Test
    fun missingConsentIsUnavailableWithoutStartingCapture() = runBlocking {
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) {
                    PackageManager.PERMISSION_DENIED
                } else {
                    super.checkSelfPermission(permission)
                }
        }
        val controller = AndroidMicrophoneAudioCaptureController(denied)
        try {
            val capability = controller.queryCapabilities(request)
            assertFalse(capability.available)
            assertEquals(AudioCaptureError.PermissionDenied, capability.lastError)
            assertEquals(
                AudioCaptureError.PermissionDenied,
                controller.prepare(
                    request,
                    object : PcmAudioSink {
                        override fun onFormatChanged(format: AudioCaptureFormat) = Unit

                        override fun onPcmChunk(
                            buffer: ByteBuffer,
                            offset: Int,
                            sizeBytes: Int,
                            frameCount: Int,
                            firstFramePosition: Long,
                            captureTimeNs: Long,
                            timestampQuality: AudioTimestampQuality,
                        ) = error("Denied capture must not deliver PCM")
                    },
                ).error,
            )
        } finally {
            controller.close()
        }
        assertNoCaptureWorker()
    }

    private fun assertNoCaptureWorker() {
        assertFalse(
            Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "WarpnectMicrophoneCapture" },
        )
    }
}
