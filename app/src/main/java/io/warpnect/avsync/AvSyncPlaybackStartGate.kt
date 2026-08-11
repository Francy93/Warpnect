package io.warpnect.avsync

import io.warpnect.audio.session.AudioPlaybackStartGate
import io.warpnect.audio.session.AudioPlaybackStartGateDecision
import io.warpnect.audio.session.AudioReceiverSessionSnapshot
import java.util.concurrent.atomic.AtomicReference

data class AvSyncStartupGateSnapshot(
    val startupHoldUs: Long = 0,
    val startupHoldCapacityLimitUs: Long = 0,
    val startupHoldExpired: Boolean = false,
    val videoReady: Boolean = false,
    val released: Boolean = false,
)

class AvSyncPlaybackStartGate(
    private val configProvider: () -> AvSyncConfig,
    private val clock: AvSyncClock = SystemAvSyncClock,
) : AudioPlaybackStartGate {
    private val snapshotRef = AtomicReference(AvSyncStartupGateSnapshot())

    @Volatile
    private var primedAtNs: Long = 0L

    @Volatile
    private var videoReady: Boolean = false

    override fun evaluate(snapshot: AudioReceiverSessionSnapshot, nowNs: Long): AudioPlaybackStartGateDecision {
        val config = configProvider()
        if (!config.enabled) return release(nowNs, expired = false)
        if (primedAtNs == 0L) {
            primedAtNs = nowNs
        }
        val capacityLimitUs = capacityDerivedMaxHoldUs(snapshot)
        val configuredLimitUs = if (config.maxStartupAudioSyncHoldUs == AvSyncConfig.AUTO_STARTUP_HOLD_US) {
            capacityLimitUs
        } else {
            minOf(config.maxStartupAudioSyncHoldUs, capacityLimitUs)
        }
        val elapsedUs = ((nowNs - primedAtNs).coerceAtLeast(0L)) / NANOS_PER_MICRO
        val shouldReleaseForCapacity = shouldReleaseBeforeRingFull(snapshot)
        val expired = elapsedUs >= configuredLimitUs
        val shouldStart = videoReady || expired || shouldReleaseForCapacity
        snapshotRef.set(
            AvSyncStartupGateSnapshot(
                startupHoldUs = elapsedUs,
                startupHoldCapacityLimitUs = capacityLimitUs,
                startupHoldExpired = expired,
                videoReady = videoReady,
                released = shouldStart,
            ),
        )
        return if (shouldStart) AudioPlaybackStartGateDecision.Start else AudioPlaybackStartGateDecision.Hold
    }

    override fun onPlaybackStarted() {
        snapshotRef.updateAndGet { it.copy(released = true) }
    }

    override fun onPlaybackReset() {
        primedAtNs = 0L
        videoReady = false
        snapshotRef.set(AvSyncStartupGateSnapshot())
    }

    fun notifyVideoReady() {
        videoReady = true
        val nowNs = clock.nowNs()
        val elapsedUs = if (primedAtNs > 0L && nowNs >= primedAtNs) {
            (nowNs - primedAtNs) / NANOS_PER_MICRO
        } else {
            0L
        }
        snapshotRef.updateAndGet {
            it.copy(
                startupHoldUs = elapsedUs,
                videoReady = true,
            )
        }
    }

    fun snapshot(): AvSyncStartupGateSnapshot = snapshotRef.get()

    private fun capacityDerivedMaxHoldUs(snapshot: AudioReceiverSessionSnapshot): Long {
        val playback = snapshot.playback
        val framesPerCodecFrame = snapshot.samplesPerFrame.takeIf { it > 0 }
            ?: playback?.framesPerCodecFrame
            ?: return 0L
        val capacityFrames = playback?.ringCapacityFrames ?: return 0L
        val startThresholdFrames = framesPerCodecFrame * snapshot.playbackStartThresholdCodecFrames.coerceAtLeast(1)
        val extraFrames = (capacityFrames - startThresholdFrames).coerceAtLeast(0)
        return (extraFrames.toLong() * snapshot.frameDurationUs.toLong()) / framesPerCodecFrame.toLong()
    }

    private fun shouldReleaseBeforeRingFull(snapshot: AudioReceiverSessionSnapshot): Boolean {
        val playback = snapshot.playback ?: return false
        val framesPerCodecFrame = snapshot.samplesPerFrame.takeIf { it > 0 }
            ?: playback.framesPerCodecFrame
        if (framesPerCodecFrame <= 0 || playback.ringCapacityFrames <= 0) return false
        return playback.ringOccupancyFrames + framesPerCodecFrame >= playback.ringCapacityFrames
    }

    private fun release(nowNs: Long, expired: Boolean): AudioPlaybackStartGateDecision {
        val elapsedUs = if (primedAtNs > 0L && nowNs >= primedAtNs) {
            (nowNs - primedAtNs) / NANOS_PER_MICRO
        } else {
            0L
        }
        snapshotRef.set(
            AvSyncStartupGateSnapshot(
                startupHoldUs = elapsedUs,
                startupHoldExpired = expired,
                videoReady = videoReady,
                released = true,
            ),
        )
        return AudioPlaybackStartGateDecision.Start
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}
