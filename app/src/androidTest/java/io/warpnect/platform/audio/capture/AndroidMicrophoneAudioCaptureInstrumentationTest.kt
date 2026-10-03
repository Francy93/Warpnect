package io.warpnect.platform.audio.capture

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMicrophoneAudioCaptureInstrumentationTest {
    @Test
    fun physicalMicrophoneProducesPcmAndStopsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val frames = AtomicLong()
        val nonZeroSamples = AtomicLong()
        val sumSquares = AtomicLong()
        val peak = AtomicLong()
        val deliveryIntervals = LongArray(7)
        var previousDeliveryNs = 0L
        var maxDeliveryGapNs = 0L
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
                val nowNs = System.nanoTime()
                if (previousDeliveryNs != 0L) {
                    val gapNs = nowNs - previousDeliveryNs
                    maxDeliveryGapNs = maxOf(maxDeliveryGapNs, gapNs)
                    val bucket = when {
                        gapNs < 1_000_000 -> 0
                        gapNs < 3_000_000 -> 1
                        gapNs < 7_000_000 -> 2
                        gapNs < 10_000_000 -> 3
                        gapNs < 20_000_000 -> 4
                        gapNs < 40_000_000 -> 5
                        else -> 6
                    }
                    deliveryIntervals[bucket]++
                }
                previousDeliveryNs = nowNs
                val samples = buffer.duplicate().order(ByteOrder.nativeOrder())
                var localEnergy = 0L
                var localNonZero = 0L
                var localPeak = 0L
                for (index in 0 until sizeBytes / Short.SIZE_BYTES) {
                    val sample = samples.getShort(offset + index * Short.SIZE_BYTES).toLong()
                    if (sample != 0L) localNonZero++
                    localEnergy += sample * sample
                    localPeak = maxOf(localPeak, kotlin.math.abs(sample))
                }
                frames.addAndGet(frameCount.toLong())
                nonZeroSamples.addAndGet(localNonZero)
                sumSquares.addAndGet(localEnergy)
                peak.accumulateAndGet(localPeak, ::maxOf)
            }
        }
        val request = AudioCaptureRequest(
            source = AudioCaptureSource.MicrophoneAudio,
            preferredSampleRateHz = 48_000,
            channelCount = 1,
            targetChunkDurationUs = 5_000,
            requireStrictFormat = true,
        )
        val controller = AndroidMicrophoneAudioCaptureController(context)
        try {
            val capabilities = controller.queryCapabilities(request)
            assertTrue(capabilities.available)
            runBlocking {
                assertEquals(AudioCaptureError.None, controller.prepare(request, sink).error)
                assertEquals(AudioCaptureError.None, controller.start().error)
            }
            Thread.sleep(3_000)
            runBlocking { assertEquals(AudioCaptureError.None, controller.stop().error) }
            val capturedFrames = frames.get()
            val rms = sqrt(sumSquares.get().toDouble() / capturedFrames.coerceAtLeast(1L))
            Log.i(
                "WarpnectMicrophoneProbe",
                "MIC_LOCAL_PROBE unprocessed_supported=${capabilities.unprocessedMicrophoneSupported} " +
                    "frames=$capturedFrames non_zero=${nonZeroSamples.get()} " +
                    "rms=$rms peak=${peak.get()} max_delivery_gap_ns=$maxDeliveryGapNs " +
                    "delivery_bins_lt1_3_7_10_20_40_else_ms=${deliveryIntervals.joinToString(",")}",
            )
            assertTrue(capturedFrames > 48_000L)
            assertTrue(nonZeroSamples.get() > 0L)
            assertTrue(rms > 0.0)
        } finally {
            controller.close()
        }
    }
}
