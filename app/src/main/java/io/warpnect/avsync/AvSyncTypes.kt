package io.warpnect.avsync

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality

enum class AvSyncState {
    Disabled,
    WaitingForAudio,
    WaitingForVideoTimestampDomain,
    Calibrating,
    Synchronized,
    Degraded,
    Unsupported,
    Closed,
}

enum class VideoTimestampDomainQuality {
    Unknown,
    Calibrating,
    SenderMonotonicCompatible,
    Rejected,
    Stale,
}

enum class AudioPresentationModelQuality {
    Unavailable,
    WarmingUp,
    Valid,
    Stale,
    Discontinuous,
}

enum class AvSyncError {
    None,
    InvalidConfiguration,
    AudioPresentationUnavailable,
    AudioPresentationStale,
    VideoTimestampDomainRejected,
    VideoTimestampDomainUnverified,
    SyncModelInvalid,
    SyncModelStale,
    StartupGateFailure,
    ArithmeticOverflow,
    InvalidTimestamp,
    Closed,
}

data class AvSyncConfig(
    val enabled: Boolean = true,
    val audioMasterSource: AudioCaptureSource = AudioCaptureSource.SystemAudio,
    val minimumVideoCalibrationSamples: Int = 8,
    val maxCrossMediaPathDifferenceUs: Long = 250_000L,
    val maxTimestampRateErrorPpm: Long = 50_000L,
    val syncSampleIntervalUs: Long = 20_000L,
    val maxSyncModelAgeUs: Long = 100_000L,
    val maxStartupAudioSyncHoldUs: Long = AUTO_STARTUP_HOLD_US,
    val maxVideoSyncScheduleAheadUs: Long = 20_000L,
    val manualAvOffsetUs: Long = 0L,
    val allowEstimatedAudioTimestamp: Boolean = false,
) {
    fun validate(): AvSyncError {
        if (minimumVideoCalibrationSamples <= 0 || minimumVideoCalibrationSamples > MAX_CALIBRATION_SAMPLES) {
            return AvSyncError.InvalidConfiguration
        }
        if (maxCrossMediaPathDifferenceUs <= 0L ||
            maxTimestampRateErrorPpm <= 0L ||
            syncSampleIntervalUs <= 0L ||
            maxSyncModelAgeUs <= 0L ||
            maxVideoSyncScheduleAheadUs < 0L ||
            maxStartupAudioSyncHoldUs < AUTO_STARTUP_HOLD_US
        ) {
            return AvSyncError.InvalidConfiguration
        }
        return AvSyncError.None
    }

    companion object {
        const val AUTO_STARTUP_HOLD_US = -1L
        const val MAX_CALIBRATION_SAMPLES = 64
    }
}

data class AvSyncModel(
    val state: AvSyncState = AvSyncState.Disabled,
    val audioPresentationQuality: AudioPresentationModelQuality = AudioPresentationModelQuality.Unavailable,
    val audioSourceTimeUs: Long = 0,
    val audioPresentationTimeNs: Long = 0,
    val audioTimestampQuality: AudioTimestampQuality = AudioTimestampQuality.Unavailable,
    val audioPresentationAnchorValid: Boolean = false,
    val videoTimestampDomainQuality: VideoTimestampDomainQuality = VideoTimestampDomainQuality.Unknown,
    val generation: Long = 0,
    val manualAvOffsetUs: Long = 0,
    val modelUpdatedAtNs: Long = 0,
)

data class VideoTimestampDomainSnapshot(
    val quality: VideoTimestampDomainQuality = VideoTimestampDomainQuality.Unknown,
    val videoCalibrationSamples: Int = 0,
    val audioCalibrationSamples: Int = 0,
    val medianAudioOffsetUs: Long = 0,
    val medianVideoOffsetUs: Long = 0,
    val pathOffsetDifferenceUs: Long = 0,
    val minPathOffsetDifferenceUs: Long = 0,
    val maxPathOffsetDifferenceUs: Long = 0,
    val timestampRateErrorPpm: Long = 0,
    val lastSampleAgeUs: Long = 0,
)

data class AvSyncSnapshot(
    val state: AvSyncState = AvSyncState.Disabled,
    val audioMasterSource: AudioCaptureSource = AudioCaptureSource.SystemAudio,
    val audioTimestampQuality: AudioTimestampQuality = AudioTimestampQuality.Unavailable,
    val audioPresentationQuality: AudioPresentationModelQuality = AudioPresentationModelQuality.Unavailable,
    val audioPresentationAnchorValid: Boolean = false,
    val audioAnchorAgeUs: Long = 0,
    val videoTimestampDomainQuality: VideoTimestampDomainQuality = VideoTimestampDomainQuality.Unknown,
    val videoCalibrationSamples: Int = 0,
    val startupHoldUs: Long = 0,
    val startupHoldCapacityLimitUs: Long = 0,
    val startupHoldExpired: Boolean = false,
    val currentEstimatedAvSkewUs: Long = 0,
    val videoFramesScheduled: Long = 0,
    val videoFramesRenderedImmediately: Long = 0,
    val videoScheduleClamped: Long = 0,
    val latestVideoLateUs: Long = 0,
    val minVideoLateUs: Long = 0,
    val maxVideoLateUs: Long = 0,
    val latestVideoScheduleAheadUs: Long = 0,
    val syncAcquisitions: Long = 0,
    val syncLosses: Long = 0,
    val audioUnderrunInvalidations: Long = 0,
    val audioResetInvalidations: Long = 0,
    val videoResetEvents: Long = 0,
    val manualAvOffsetUs: Long = 0,
    val modelAgeUs: Long = 0,
    val lastError: AvSyncError = AvSyncError.None,
)

data class AvSyncResult(
    val error: AvSyncError,
    val snapshot: AvSyncSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AvSyncError.None
}

fun interface AvSyncClock {
    fun nowNs(): Long
}

object SystemAvSyncClock : AvSyncClock {
    override fun nowNs(): Long = System.nanoTime()
}
