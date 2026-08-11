package io.warpnect.avsync

import io.warpnect.video.decoder.DecodedVideoFrame
import io.warpnect.video.render.VideoRenderDecision
import io.warpnect.video.render.VideoRenderPolicy
import java.util.concurrent.atomic.AtomicReference

fun interface VideoFrameTimingObserver {
    fun onDecodedVideoFrame(presentationTimeUs: Long, readyLocalUs: Long)
}

data class AvSynchronizedVideoRenderPolicySnapshot(
    val videoFramesScheduled: Long = 0,
    val videoFramesRenderedImmediately: Long = 0,
    val videoScheduleClamped: Long = 0,
    val latestVideoLateUs: Long = 0,
    val minVideoLateUs: Long = 0,
    val maxVideoLateUs: Long = 0,
    val latestVideoScheduleAheadUs: Long = 0,
    val currentEstimatedAvSkewUs: Long = 0,
)

class AvSynchronizedVideoRenderPolicy(
    private val configProvider: () -> AvSyncConfig,
    private val modelProvider: () -> AvSyncModel,
    private val timingObserver: VideoFrameTimingObserver? = null,
) : VideoRenderPolicy {
    private val snapshotRef = AtomicReference(AvSynchronizedVideoRenderPolicySnapshot())

    override fun decide(frame: DecodedVideoFrame, nowNs: Long): VideoRenderDecision {
        timingObserver?.onDecodedVideoFrame(frame.presentationTimeUs, nowNs / NANOS_PER_MICRO)
        val config = configProvider()
        val model = modelProvider()
        if (!config.enabled ||
            model.state != AvSyncState.Synchronized ||
            model.videoTimestampDomainQuality != VideoTimestampDomainQuality.SenderMonotonicCompatible ||
            !model.audioPresentationAnchorValid ||
            isModelStale(model, config, nowNs)
        ) {
            recordImmediate(lateUs = 0)
            return VideoRenderDecision.RenderImmediately
        }

        val mediaDeltaUs = frame.presentationTimeUs - model.audioSourceTimeUs
        val mediaDeltaNs = AudioPresentationMath.microsToNanos(mediaDeltaUs) ?: return overflowFallback()
        val manualOffsetNs =
            AudioPresentationMath.microsToNanos(config.manualAvOffsetUs) ?: return overflowFallback()
        val targetWithoutOffset =
            model.audioPresentationTimeNs.saturatingAdd(mediaDeltaNs) ?: return overflowFallback()
        val targetNs = targetWithoutOffset.saturatingAdd(manualOffsetNs) ?: return overflowFallback()

        if (targetNs <= nowNs) {
            val lateUs = (nowNs - targetNs) / NANOS_PER_MICRO
            recordImmediate(lateUs = lateUs)
            return VideoRenderDecision.RenderImmediately
        }

        val aheadUs = (targetNs - nowNs) / NANOS_PER_MICRO
        val maxAheadNs =
            AudioPresentationMath.microsToNanos(config.maxVideoSyncScheduleAheadUs)
                ?: return overflowFallback()
        return if (targetNs - nowNs > maxAheadNs) {
            val clampedTarget = nowNs.saturatingAdd(maxAheadNs) ?: return overflowFallback()
            recordScheduled(aheadUs = config.maxVideoSyncScheduleAheadUs, clamped = true)
            VideoRenderDecision.RenderAtLocalTime(clampedTarget)
        } else {
            recordScheduled(aheadUs = aheadUs, clamped = false)
            VideoRenderDecision.RenderAtLocalTime(targetNs)
        }
    }

    fun snapshot(): AvSynchronizedVideoRenderPolicySnapshot = snapshotRef.get()

    fun reset() {
        snapshotRef.set(AvSynchronizedVideoRenderPolicySnapshot())
    }

    private fun isModelStale(model: AvSyncModel, config: AvSyncConfig, nowNs: Long): Boolean {
        if (model.modelUpdatedAtNs <= 0L || nowNs < model.modelUpdatedAtNs) return true
        return ((nowNs - model.modelUpdatedAtNs) / NANOS_PER_MICRO) > config.maxSyncModelAgeUs
    }

    private fun overflowFallback(): VideoRenderDecision {
        recordImmediate(lateUs = 0)
        return VideoRenderDecision.RenderImmediately
    }

    private fun recordScheduled(aheadUs: Long, clamped: Boolean) {
        snapshotRef.updateAndGet {
            it.copy(
                videoFramesScheduled = it.videoFramesScheduled + 1,
                videoScheduleClamped = it.videoScheduleClamped + if (clamped) 1 else 0,
                latestVideoScheduleAheadUs = aheadUs,
                currentEstimatedAvSkewUs = 0,
            )
        }
    }

    private fun recordImmediate(lateUs: Long) {
        snapshotRef.updateAndGet {
            val positiveLate = lateUs.coerceAtLeast(0L)
            val newMin = if (it.videoFramesRenderedImmediately == 0L) {
                positiveLate
            } else {
                minOf(it.minVideoLateUs, positiveLate)
            }
            it.copy(
                videoFramesRenderedImmediately = it.videoFramesRenderedImmediately + 1,
                latestVideoLateUs = positiveLate,
                minVideoLateUs = newMin,
                maxVideoLateUs = maxOf(it.maxVideoLateUs, positiveLate),
                currentEstimatedAvSkewUs = positiveLate,
            )
        }
    }

    private fun Long.saturatingAdd(other: Long): Long? {
        if (other > 0L && this > Long.MAX_VALUE - other) return null
        if (other < 0L && this < Long.MIN_VALUE - other) return null
        return this + other
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}
