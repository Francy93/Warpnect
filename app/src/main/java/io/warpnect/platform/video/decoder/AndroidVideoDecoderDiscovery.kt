package io.warpnect.platform.video.decoder

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import io.warpnect.video.decoder.VideoDecoderCandidate
import io.warpnect.video.decoder.VideoDecoderCapabilities
import io.warpnect.video.decoder.VideoDecoderCodec
import io.warpnect.video.decoder.VideoDecoderCodecInfo
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderHardwareAcceleration
import io.warpnect.video.decoder.VideoDecoderSelector

interface VideoDecoderDiscovery {
    fun query(config: VideoDecoderConfig): VideoDecoderCapabilities
}

class AndroidVideoDecoderDiscovery : VideoDecoderDiscovery {
    override fun query(config: VideoDecoderConfig): VideoDecoderCapabilities {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .mapNotNull { info -> info.toCandidate(config) }
            .toList()
        return VideoDecoderSelector.select(config, candidates)
    }

    @SuppressLint("InlinedApi")
    private fun MediaCodecInfo.toCandidate(config: VideoDecoderConfig): VideoDecoderCandidate? {
        val supportsAvc = supportedTypes.any { it.equals(VideoDecoderCodec.Avc.mimeType, ignoreCase = true) }
        if (!supportsAvc) {
            return null
        }

        val capabilities = runCatching { getCapabilitiesForType(VideoDecoderCodec.Avc.mimeType) }.getOrNull()
            ?: return null
        val videoCapabilities = capabilities.videoCapabilities
        val widthRange = videoCapabilities.supportedWidths
        val heightRange = videoCapabilities.supportedHeights
        val lowLatencySupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        } else {
            null
        }

        return VideoDecoderCandidate(
            info = VideoDecoderCodecInfo(
                codecName = name,
                canonicalName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) canonicalName else null,
                hardwareAcceleration = hardwareAcceleration(),
                softwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isSoftwareOnly else null,
                vendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isVendor else null,
                alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isAlias else null,
                lowLatencyFeatureSupported = lowLatencySupported,
            ),
            supportsAvc = true,
            widthSupported = widthRange.containsValue(config.width),
            heightSupported = heightRange.containsValue(config.height),
            sizeSupported = runCatching {
                videoCapabilities.isSizeSupported(config.width, config.height)
            }.getOrDefault(false),
            sizeAndRateSupported = config.expectedFrameRate?.let { frameRate ->
                runCatching {
                    videoCapabilities.areSizeAndRateSupported(
                        config.width,
                        config.height,
                        frameRate.toDouble(),
                    )
                }.getOrDefault(false)
            } ?: true,
            lowLatencyFeatureSupported = lowLatencySupported,
            widthAlignment = videoCapabilities.widthAlignment,
            heightAlignment = videoCapabilities.heightAlignment,
            minWidth = widthRange.lower,
            maxWidth = widthRange.upper,
            minHeight = heightRange.lower,
            maxHeight = heightRange.upper,
        )
    }

    private fun MediaCodecInfo.hardwareAcceleration(): VideoDecoderHardwareAcceleration =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                isHardwareAccelerated -> VideoDecoderHardwareAcceleration.Hardware
                isSoftwareOnly -> VideoDecoderHardwareAcceleration.Software
                else -> VideoDecoderHardwareAcceleration.Unknown
            }
        } else {
            VideoDecoderHardwareAcceleration.Unknown
        }

    private fun Range<Int>.containsValue(value: Int): Boolean = contains(value)
}
