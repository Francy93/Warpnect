package io.warpnect.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.platform.capture.AndroidVideoCaptureController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the production privileged mirror can feed an app-owned hardware encoder without using
 * the production strict-CBR profile. It is capture-backend evidence only for codec-quirk devices.
 */
@RunWith(AndroidJUnit4::class)
class PrivilegedCaptureSafeEncoderInstrumentationTest {
    private var captureController: AndroidVideoCaptureController? = null
    private var codec: MediaCodec? = null
    private var captureStarted = false

    @After
    fun tearDown() {
        if (captureStarted) {
            runBlocking { captureController?.stop() }
        }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        captureController?.close()
    }

    @Test
    fun privilegedMirrorFeedsSafeVbrEncoderWhenAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = AndroidVideoCaptureController(context)
        captureController = capture
        val capabilities = runBlocking { capture.queryCapabilities() }
        assumeTrue(
            "Privileged capture unavailable: ${capabilities.privilegeState}/${capabilities.lastError}",
            capabilities.privilegeState == CapturePrivilegeState.Ready && capabilities.backendAvailable,
        )

        val format = safeVbrFormat()
        val codecInfo = selectSafeHardwareAvcEncoder(format)
        assumeTrue("No metadata-supported hardware AVC VBR encoder", codecInfo != null)

        val encoder = MediaCodec.createByCodecName(requireNotNull(codecInfo).name)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        println("Warpnect capture proof: codecOwnerUid=${Process.myUid()} codec=${codecInfo.name}")

        val start = runBlocking {
            capture.start(
                CaptureRequest(
                    sourceDisplayId = Display.DEFAULT_DISPLAY,
                    outputWidth = WIDTH,
                    outputHeight = HEIGHT,
                ),
                inputSurface,
            )
        }
        assertTrue("capture start failed: $start", start.isSuccess)
        captureStarted = true

        assertTrue("no encoded access unit from privileged mirror", waitForEncodedAccessUnit(encoder))
        assertTrue("capture stop failed", runBlocking { capture.stop() }.isSuccess)
        captureStarted = false
    }

    private fun safeVbrFormat(): MediaFormat = MediaFormat.createVideoFormat(MIME_AVC, WIDTH, HEIGHT).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
        )
    }

    private fun selectSafeHardwareAvcEncoder(format: MediaFormat): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            if (!info.isEncoder || MIME_AVC !in info.supportedTypes) return@firstOrNull false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !info.isHardwareAccelerated) {
                return@firstOrNull false
            }
            val capabilities = runCatching { info.getCapabilitiesForType(MIME_AVC) }.getOrNull()
                ?: return@firstOrNull false
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats &&
                capabilities.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                ) == true &&
                capabilities.isFormatSupported(format)
        }

    private fun waitForEncodedAccessUnit(encoder: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadlineMs = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index < 0) continue
            try {
                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !codecConfig) return true
            } finally {
                encoder.releaseOutputBuffer(index, false)
            }
        }
        return false
    }

    private companion object {
        const val MIME_AVC = "video/avc"
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FRAME_RATE = 30
        const val BITRATE_BPS = 1_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val ENCODE_TIMEOUT_MS = 5_000L
    }
}
