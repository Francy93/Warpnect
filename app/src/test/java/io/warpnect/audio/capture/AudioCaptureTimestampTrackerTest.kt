package io.warpnect.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCaptureTimestampTrackerTest {
    @Test
    fun fallbackEstimatesFirstFrameFromReadCompletion() {
        val tracker = AudioCaptureTimestampTracker(sampleRateHz = 48_000)

        val timing = tracker.recordChunk(frameCount = 240, readCompletionTimeNs = 10_000_000)

        assertEquals(0L, timing.firstFramePosition)
        assertEquals(5_000_000L, timing.captureTimeNs)
        assertEquals(AudioTimestampQuality.EstimatedFromReadCompletion, timing.timestampQuality)
    }

    @Test
    fun audioRecordAnchorProvidesFrameworkTimestampQuality() {
        val tracker = AudioCaptureTimestampTracker(sampleRateHz = 48_000)
        tracker.recordChunk(frameCount = 240, readCompletionTimeNs = 10_000_000)
        tracker.updateAnchor(AudioTimestampAnchor(framePosition = 240, nanoTime = 20_000_000))

        val timing = tracker.recordChunk(frameCount = 240, readCompletionTimeNs = 25_000_000)

        assertEquals(240L, timing.firstFramePosition)
        assertEquals(20_000_000L, timing.captureTimeNs)
        assertEquals(AudioTimestampQuality.AudioRecordTimestamp, timing.timestampQuality)
    }

    @Test
    fun resetStartsNewTimeline() {
        val tracker = AudioCaptureTimestampTracker(sampleRateHz = 44_100)
        tracker.recordChunk(frameCount = 221, readCompletionTimeNs = 10_000_000)

        tracker.reset()
        val timing = tracker.recordChunk(frameCount = 221, readCompletionTimeNs = 20_000_000)

        assertEquals(0L, timing.firstFramePosition)
        assertEquals(AudioTimestampQuality.EstimatedFromReadCompletion, timing.timestampQuality)
    }

    @Test
    fun convertsFramesWithIntegerArithmetic() {
        assertEquals(5_000_000L, AudioCaptureTimestampTracker.framesToNanos(240, 48_000))
        assertEquals(5_011_337L, AudioCaptureTimestampTracker.framesToNanos(221, 44_100))
    }
}
