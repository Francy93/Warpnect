package io.warpnect.platform.video.encoder

import android.content.Context

internal fun safeCodecProbeDiscovery(context: Context): VideoEncoderDiscovery = AndroidVideoEncoderDiscovery(
    CbrCapabilityFallback(
        CachedExactVideoEncoderCapabilityProbe(
            ServiceBackedExactVideoEncoderCapabilityProbe(
                AndroidCodecProbeServiceCaller(context),
            ),
        ),
    ),
)
