package io.warpnect.avsync

import kotlin.math.abs

class VideoTimestampDomainValidator(
    private val config: AvSyncConfig,
) {
    private val audioOffsets = ArrayDeque<OffsetSample>()
    private val videoOffsets = ArrayDeque<OffsetSample>()

    @Synchronized
    fun reset() {
        audioOffsets.clear()
        videoOffsets.clear()
    }

    @Synchronized
    fun addAudioObservation(captureTimeUs: Long, readyLocalUs: Long) {
        if (captureTimeUs < 0L || readyLocalUs < 0L) return
        pushBounded(audioOffsets, OffsetSample(mediaTimeUs = captureTimeUs, readyLocalUs = readyLocalUs))
    }

    @Synchronized
    fun addVideoObservation(presentationTimeUs: Long, readyLocalUs: Long) {
        if (presentationTimeUs < 0L || readyLocalUs < 0L) return
        pushBounded(videoOffsets, OffsetSample(mediaTimeUs = presentationTimeUs, readyLocalUs = readyLocalUs))
    }

    @Synchronized
    fun snapshot(nowUs: Long): VideoTimestampDomainSnapshot {
        if (audioOffsets.isEmpty() || videoOffsets.isEmpty()) {
            return VideoTimestampDomainSnapshot(quality = VideoTimestampDomainQuality.Unknown)
        }
        val videoSamples = videoOffsets.size
        if (videoSamples < config.minimumVideoCalibrationSamples) {
            return VideoTimestampDomainSnapshot(
                quality = VideoTimestampDomainQuality.Calibrating,
                videoCalibrationSamples = videoSamples,
                audioCalibrationSamples = audioOffsets.size,
                lastSampleAgeUs = sampleAgeUs(nowUs),
            )
        }

        val audioMedian = median(audioOffsets.map { it.offsetUs })
        val videoMedian = median(videoOffsets.map { it.offsetUs })
        val differences = videoOffsets.map { video -> video.offsetUs - nearestAudioOffset(video.readyLocalUs) }
        val pathDifference = videoMedian - audioMedian
        val rateError = videoRateErrorPpm()
        val quality = when {
            abs(pathDifference) > config.maxCrossMediaPathDifferenceUs -> VideoTimestampDomainQuality.Rejected
            rateError > config.maxTimestampRateErrorPpm -> VideoTimestampDomainQuality.Rejected
            sampleAgeUs(nowUs) > config.maxSyncModelAgeUs -> VideoTimestampDomainQuality.Stale
            else -> VideoTimestampDomainQuality.SenderMonotonicCompatible
        }
        return VideoTimestampDomainSnapshot(
            quality = quality,
            videoCalibrationSamples = videoSamples,
            audioCalibrationSamples = audioOffsets.size,
            medianAudioOffsetUs = audioMedian,
            medianVideoOffsetUs = videoMedian,
            pathOffsetDifferenceUs = pathDifference,
            minPathOffsetDifferenceUs = differences.minOrNull() ?: pathDifference,
            maxPathOffsetDifferenceUs = differences.maxOrNull() ?: pathDifference,
            timestampRateErrorPpm = rateError,
            lastSampleAgeUs = sampleAgeUs(nowUs),
        )
    }

    private fun pushBounded(target: ArrayDeque<OffsetSample>, sample: OffsetSample) {
        target.addLast(sample)
        while (target.size > AvSyncConfig.MAX_CALIBRATION_SAMPLES) {
            target.removeFirst()
        }
    }

    private fun nearestAudioOffset(readyLocalUs: Long): Long {
        var best = audioOffsets.first()
        var bestDistance = abs(best.readyLocalUs - readyLocalUs)
        for (sample in audioOffsets) {
            val distance = abs(sample.readyLocalUs - readyLocalUs)
            if (distance < bestDistance) {
                best = sample
                bestDistance = distance
            }
        }
        return best.offsetUs
    }

    private fun videoRateErrorPpm(): Long {
        val first = videoOffsets.firstOrNull() ?: return Long.MAX_VALUE
        val last = videoOffsets.lastOrNull() ?: return Long.MAX_VALUE
        val mediaDelta = last.mediaTimeUs - first.mediaTimeUs
        val localDelta = last.readyLocalUs - first.readyLocalUs
        if (mediaDelta <= 0L || localDelta <= 0L) return Long.MAX_VALUE
        return (abs(mediaDelta - localDelta) * PARTS_PER_MILLION) / localDelta
    }

    private fun sampleAgeUs(nowUs: Long): Long {
        val latest = maxOf(audioOffsets.lastOrNull()?.readyLocalUs ?: 0L, videoOffsets.lastOrNull()?.readyLocalUs ?: 0L)
        return if (nowUs >= latest) nowUs - latest else 0L
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private data class OffsetSample(
        val mediaTimeUs: Long,
        val readyLocalUs: Long,
    ) {
        val offsetUs: Long
            get() = readyLocalUs - mediaTimeUs
    }

    private companion object {
        const val PARTS_PER_MILLION = 1_000_000L
    }
}
