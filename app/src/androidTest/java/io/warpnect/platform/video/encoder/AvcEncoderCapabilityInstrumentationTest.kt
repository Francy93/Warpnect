package io.warpnect.platform.video.encoder

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.video.encoder.VideoEncoderRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit hardware audit for the production AVC request. It creates no Session, captures no
 * display content, and only starts an encoder long enough to validate a suspicious configuration.
 */
@RunWith(AndroidJUnit4::class)
class AvcEncoderCapabilityInstrumentationTest {
    @Test
    fun activeProbeRefusesToInstantiateMediaCodecOnAndroidMain() {
        var factoryCalls = 0
        val probe = AndroidExactVideoEncoderCapabilityProbe(
            codecFactory = ExactFormatEncoderProbeCodecFactory {
                factoryCalls += 1
                error("must not run on Android Main")
            },
        )
        lateinit var decision: CbrCapabilityDecision

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            decision = probe.probe(ExactVideoEncoderCapabilityKey.from("test.codec", productionRequest()))
        }

        assertEquals(ExactVideoEncoderCapabilityProbeResult.MainThreadRejected, decision.probeResult)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun logsEveryAvcEncoderAndProbesMetadataDisagreement() {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(AVC, ignoreCase = true) } }
            .sortedBy { it.name }
            .forEach(::audit)
    }

    @SuppressLint("InlinedApi")
    private fun audit(info: MediaCodecInfo) {
        val capabilities = info.getCapabilitiesForType(AVC)
        val video = capabilities.videoCapabilities
        val encoder = capabilities.encoderCapabilities
        val surfaceInput = capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val sizeAndRate = runCatching { video.areSizeAndRateSupported(WIDTH, HEIGHT, FRAME_RATE.toDouble()) }
            .getOrDefault(false)
        val bitrate = video.bitrateRange.contains(BITRATE_BPS)
        val cq = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
        val vbr = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        val cbr = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val cbrFd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
        } else {
            false
        }
        val baseFormat = productionFormat(bitrateMode = null)
        val cbrFormat = productionFormat(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val cbrFdFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            productionFormat(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
        } else {
            null
        }
        val profiles = capabilities.profileLevels.joinToString(",") { "${it.profile}:${it.level}" }
        val common = listOf(
            "codec=${info.name}",
            "canonical=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.canonicalName else "na"}",
            "alias=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isAlias else false}",
            "vendor=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isVendor else false}",
            "hardware=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else false}",
            "software=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly else false}",
            "surface=$surfaceInput",
            "size_rate_720p60=$sizeAndRate",
            "bitrate_8m=$bitrate",
            "bitrate_range=${video.bitrateRange.lower}-${video.bitrateRange.upper}",
            "modes=cq:$cq,vbr:$vbr,cbr:$cbr,cbr_fd:$cbrFd",
            "format_base=${isFormatSupported(capabilities, baseFormat)}",
            "format_cbr=${isFormatSupported(capabilities, cbrFormat)}",
            "format_cbr_fd=${cbrFdFormat?.let { isFormatSupported(capabilities, it) } ?: "na"}",
            "profiles=$profiles",
        ).joinToString(" ")
        Log.i(TAG, "event=avc_encoder $common")

        if (!cbr && surfaceInput && sizeAndRate && bitrate && isHardware(info)) {
            val result = configureAndStart(info.name, cbrFormat)
            Log.i(
                TAG,
                "event=avc_cbr_active_probe codec=${info.name} configure=${result.configureAccepted} " +
                    "input_surface=${result.inputSurfaceCreated} start=${result.startAccepted} error=${result.error}",
            )
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            info.isHardwareAccelerated &&
            !info.isSoftwareOnly
    }

    private fun isFormatSupported(capabilities: MediaCodecInfo.CodecCapabilities, format: MediaFormat): Boolean {
        return runCatching { capabilities.isFormatSupported(format) }.getOrDefault(false)
    }

    private fun productionFormat(bitrateMode: Int?): MediaFormat {
        val format = AndroidVideoEncoderFormatFactory.create(productionRequest())
        if (bitrateMode == null) {
            format.removeKey(MediaFormat.KEY_BITRATE_MODE)
        } else {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        }
        return format
    }

    private fun productionRequest() = VideoEncoderRequest(
        width = WIDTH,
        height = HEIGHT,
        frameRate = FRAME_RATE,
        bitrateBps = BITRATE_BPS,
        iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
    )

    private fun configureAndStart(codecName: String, format: MediaFormat): ActiveProbeResult {
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var configured = false
        var inputSurface = false
        var started = false
        var error = "None"
        try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            configured = true
            surface = codec.createInputSurface()
            inputSurface = true
            codec.start()
            started = true
        } catch (throwable: Throwable) {
            error = throwable.javaClass.simpleName
        } finally {
            runCatching { codec?.stop() }
            runCatching { surface?.release() }
            runCatching { codec?.release() }
        }
        return ActiveProbeResult(configured, inputSurface, started, error)
    }

    private data class ActiveProbeResult(
        val configureAccepted: Boolean,
        val inputSurfaceCreated: Boolean,
        val startAccepted: Boolean,
        val error: String,
    )

    private companion object {
        const val TAG = "WarpnectAvcAudit"
        const val AVC = "video/avc"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val BITRATE_BPS = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1f
    }
}
