package io.warpnect.platform.audio.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Standard Android microphone consumer. No Warpnect controller, PCM ring or Session access. */
@RunWith(AndroidJUnit4::class)
class StandardMicrophoneConsumerInstrumentationTest {
    @Test fun observeEnumeratedInputSelectionWithoutScopedUidInjection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = InstrumentationRegistry.getArguments().getString("preferredInputId")?.toIntOrNull()
        if (id == null) {
            org.junit.Assume.assumeTrue("Dedicated probe requires an explicit enumerated input ID", false)
            return
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING"),
        )
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).single { it.id == id }
        Log.i(TAG, "INPUT_SELECTION requested_id=$id type=${device.type} address=${inputAddress(device)}")
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    3840,
                ),
            ).build()
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, record.state)
            Log.i(TAG, "INPUT_SELECTION preferred_accepted=${record.setPreferredDevice(device)}")
            record.startRecording()
            val pcm = ShortArray(960)
            val deadline = SystemClock.elapsedRealtime() + 8000
            var samples = 0L
            var energy = 0.0
            var real = 0.0
            var imaginary = 0.0
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                if (read < 0) {
                    Log.i(TAG, "INPUT_SELECTION read_error=$read routed_id=${record.routedDevice?.id}")
                    break
                }
                for (i in 0 until read) {
                    val value = pcm[i].toDouble()
                    val phase = 2 * PI * 1193 * samples / 48_000
                    energy += value * value
                    real += value * cos(phase)
                    imaginary += value * sin(phase)
                    samples++
                }
                Thread.sleep(3)
            }
            val n = samples.coerceAtLeast(1).toDouble()
            Log.i(
                TAG,
                "INPUT_SELECTION samples=$samples rms=${sqrt(energy / n)} " +
                    "tone_amplitude=${2 * sqrt(real * real + imaginary * imaginary) / n} " +
                    "routed_id=${record.routedDevice?.id} routed_type=${record.routedDevice?.type} " +
                    "routed_address=${record.routedDevice?.let(::inputAddress)}",
            )
        } catch (failure: RuntimeException) {
            Log.i(TAG, "INPUT_SELECTION unavailable=${failure.javaClass.simpleName} state=${record.state}")
        } finally {
            runCatching { record.stop() }
            record.release()
            Log.i(TAG, "INPUT_SELECTION cleanup=RELEASED")
        }
    }

    @Test fun observeStandardAndroidInputWithoutClaimingRemoteQualification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        Log.i(
            TAG,
            "STANDARD_MIC consumer_uid=${android.os.Process.myUid()} " +
                "capture_output_permission=${context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT")} " +
                "modify_routing_permission=${context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")}",
        )
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
            Log.i(
                "WarpnectStandardMicConsumer",
                "input_id=${it.id} type=${it.type} address=${inputAddress(it)} product=${it.productName} " +
                    "sample_rates=${it.sampleRates.joinToString(",")} channels=${it.channelCounts.joinToString(",")} " +
                    "channel_masks=${it.channelMasks.joinToString(",")} encodings=${it.encodings.joinToString(",")}",
            )
        }
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioRecord.getMinBufferSize(
                        48_000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    3840,
                ),
            )
            .build()
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, record.state)
            record.startRecording()
            val pcm = ShortArray(960)
            val start = SystemClock.elapsedRealtime()
            var window = start
            var samples = 0L
            var energy = 0.0
            var peak = 0
            var real = 0.0
            var imaginary = 0.0
            var total = 0L
            while (SystemClock.elapsedRealtime() - start < 12_000) {
                val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                assertTrue(read >= 0)
                for (i in 0 until read) {
                    val value = pcm[i].toDouble()
                    val phase = 2 * PI * 1193 * samples / 48_000
                    energy += value * value
                    peak = maxOf(peak, kotlin.math.abs(pcm[i].toInt()))
                    real += value * cos(phase)
                    imaginary += value * sin(phase)
                    samples++
                }
                total += read
                if (SystemClock.elapsedRealtime() - window >= 1000) {
                    val n = samples.coerceAtLeast(1).toDouble()
                    Log.i(
                        "WarpnectStandardMicConsumer",
                        "STANDARD_MIC samples=$samples rms=${sqrt(energy / n)} peak=$peak " +
                            "tone_amplitude=${2 * sqrt(real * real + imaginary * imaginary) / n} " +
                            "route_type=${record.routedDevice?.type} route_id=${record.routedDevice?.id} " +
                            "route_address=${record.routedDevice?.let(::inputAddress)}",
                    )
                    samples = 0
                    energy = 0.0
                    peak = 0
                    real = 0.0
                    imaginary = 0.0
                    window = SystemClock.elapsedRealtime()
                }
                Thread.sleep(3)
            }
            assertTrue(total > 48_000)
        } finally {
            runCatching { record.stop() }
            record.release()
            Log.i("WarpnectStandardMicConsumer", "STANDARD_MIC cleanup=RELEASED")
        }
    }

    companion object {
        private const val TAG = "WarpnectStandardMicConsumer"
        private fun inputAddress(device: AudioDeviceInfo): String =
            if (Build.VERSION.SDK_INT >= 28) device.address else "NOT_PUBLIC_ON_API26"
    }
}
