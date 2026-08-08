package io.warpnect.platform.video.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderCandidate
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderCodecInfo
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSelector
import io.warpnect.video.encoder.VideoProfileLevel

interface VideoEncoderDiscovery {
    fun query(request: VideoEncoderRequest): VideoEncoderCapabilities
}

class AndroidVideoEncoderDiscovery : VideoEncoderDiscovery {
    override fun query(request: VideoEncoderRequest): VideoEncoderCapabilities {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .mapNotNull { info -> info.toCandidate(request) }
            .toList()
        return VideoEncoderSelector.select(request, candidates)
    }

    private fun MediaCodecInfo.toCandidate(request: VideoEncoderRequest): VideoEncoderCandidate? {
        val supportsAvc = supportedTypes.any { it.equals(VideoCodec.Avc.mimeType, ignoreCase = true) }
        if (!supportsAvc) {
            return null
        }

        val capabilities = runCatching { getCapabilitiesForType(VideoCodec.Avc.mimeType) }.getOrNull()
            ?: return null
        val videoCapabilities = capabilities.videoCapabilities
        val encoderCapabilities = capabilities.encoderCapabilities
        val bitrateRange = videoCapabilities.bitrateRange
        val widthRange = videoCapabilities.supportedWidths
        val heightRange = videoCapabilities.supportedHeights

        return VideoEncoderCandidate(
            info = VideoEncoderCodecInfo(
                codecName = name,
                canonicalName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) canonicalName else null,
                hardwareAcceleration = hardwareAcceleration(),
                softwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isSoftwareOnly else null,
                vendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isVendor else null,
                alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isAlias else null,
            ),
            supportsAvc = true,
            supportsSurfaceInput = capabilities.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            ),
            widthSupported = widthRange.containsValue(request.width),
            heightSupported = heightRange.containsValue(request.height),
            sizeSupported = runCatching {
                videoCapabilities.isSizeSupported(request.width, request.height)
            }.getOrDefault(false),
            sizeAndRateSupported = runCatching {
                videoCapabilities.areSizeAndRateSupported(
                    request.width,
                    request.height,
                    request.frameRate.toDouble(),
                )
            }.getOrDefault(false),
            bitrateSupported = bitrateRange.containsValue(request.bitrateBps),
            bitrateModeSupported = encoderCapabilities.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            ),
            widthAlignment = videoCapabilities.widthAlignment,
            heightAlignment = videoCapabilities.heightAlignment,
            minWidth = widthRange.lower,
            maxWidth = widthRange.upper,
            minHeight = heightRange.lower,
            maxHeight = heightRange.upper,
            minBitrateBps = bitrateRange.lower,
            maxBitrateBps = bitrateRange.upper,
            supportedProfiles = capabilities.profileLevels.map {
                VideoProfileLevel(profile = it.profile, level = it.level)
            },
        )
    }

    private fun MediaCodecInfo.hardwareAcceleration(): VideoEncoderHardwareAcceleration =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                isHardwareAccelerated -> VideoEncoderHardwareAcceleration.Hardware
                isSoftwareOnly -> VideoEncoderHardwareAcceleration.Software
                else -> VideoEncoderHardwareAcceleration.Unknown
            }
        } else {
            VideoEncoderHardwareAcceleration.Unknown
        }

    private fun Range<Int>.containsValue(value: Int): Boolean = contains(value)
}
