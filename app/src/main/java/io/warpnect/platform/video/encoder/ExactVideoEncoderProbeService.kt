package io.warpnect.platform.video.encoder

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Disposable normal-UID process boundary for cold MediaCodec capability qualification.
 *
 * The service owns neither production encoding nor media output. A vendor-native codec abort is
 * deliberately contained to this process and observed by the caller as a typed probe result.
 */
class ExactVideoEncoderProbeService : Service() {
    private val binder = object : IExactVideoEncoderProbeService.Stub() {
        override fun probe(
            codecName: String,
            mimeType: String,
            width: Int,
            height: Int,
            frameRate: Int,
            bitrateBps: Int,
            bitrateMode: String,
            iFrameIntervalBits: Int,
        ): Int = runExactProbe(
            ExactVideoEncoderCapabilityKey(
                codecName = codecName,
                mimeType = mimeType,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrateBps = bitrateBps,
                bitrateMode = bitrateMode,
                iFrameIntervalBits = iFrameIntervalBits,
            ),
        ).code
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
