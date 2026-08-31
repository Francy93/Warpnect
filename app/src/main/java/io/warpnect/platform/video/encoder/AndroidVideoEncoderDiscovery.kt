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

internal class AndroidVideoEncoderDiscovery(
    private val cbrFallback: CbrCapabilityFallback = CbrCapabilityFallback(processCbrProbe),
    private val debugObserver: VideoEncoderCbrCapabilityDebugObserver = VideoEncoderCbrCapabilityDebugObserver.None,
) : VideoEncoderDiscovery {
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

        val candidate = VideoEncoderCandidate(
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
        return candidate.copy(
            bitrateModeSupported = resolveCbrSupport(candidate, request),
        )
    }

    private fun resolveCbrSupport(candidate: VideoEncoderCandidate, request: VideoEncoderRequest): Boolean {
        val metadataSupported = candidate.bitrateModeSupported
        if (!metadataSupported && candidate.isEligibleForCbrProbe()) {
            debugObserver.onActiveProbeStarted()
        }
        val decision = cbrFallback.resolve(
            metadataSupported = metadataSupported,
            allOtherRequirementsSupported = candidate.isEligibleForCbrProbe(),
            key = ExactVideoEncoderCapabilityKey.from(candidate.info.codecName, request),
        )
        debugObserver.onDecision(decision)
        return decision.supported
    }

    private fun VideoEncoderCandidate.isEligibleForCbrProbe() = supportsAvc &&
        info.hardwareAcceleration == VideoEncoderHardwareAcceleration.Hardware &&
        info.softwareOnly != true &&
        supportsSurfaceInput &&
        widthSupported &&
        heightSupported &&
        sizeSupported &&
        sizeAndRateSupported &&
        bitrateSupported

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

    private companion object {
        val processCbrProbe = CachedExactVideoEncoderCapabilityProbe(AndroidExactVideoEncoderCapabilityProbe())
    }
}

internal interface VideoEncoderCbrCapabilityDebugObserver {
    fun onDecision(decision: CbrCapabilityDecision)

    fun onActiveProbeStarted() = Unit

    companion object {
        val None = object : VideoEncoderCbrCapabilityDebugObserver {
            override fun onDecision(decision: CbrCapabilityDecision) = Unit
        }
    }
}
