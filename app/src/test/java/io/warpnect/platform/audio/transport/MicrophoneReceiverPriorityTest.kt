package io.warpnect.platform.audio.transport

import io.warpnect.audio.capture.AudioCaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneReceiverPriorityTest {
    @Test
    fun microphoneCanSelectUrgentSchedulingWithoutCallingSystemPolicy() {
        var urgentCalls = 0
        assertTrue(
            applyAudioReceiverPriority(
                AudioCaptureSource.MicrophoneAudio,
                { error("system_policy_must_not_run") },
                {
                    urgentCalls++
                    true
                },
            ),
        )
        assertEquals(1, urgentCalls)
    }

    @Test
    fun systemAudioCannotAcquireMicrophoneUrgentScheduling() {
        var regularCalls = 0
        assertTrue(
            applyAudioReceiverPriority(
                AudioCaptureSource.SystemAudio,
                {
                    regularCalls++
                    true
                },
                { error("microphone_policy_must_not_run") },
            ),
        )
        assertEquals(1, regularCalls)
    }

    @Test
    fun microphonePriorityIsAppliedOnTheCallingWorkerOnly() {
        var appliedThread: Thread? = null
        var applied = false
        val worker = Thread {
            applied = applyAudioReceiverPriority(AudioCaptureSource.MicrophoneAudio) {
                appliedThread = Thread.currentThread()
                true
            }
        }
        worker.start()
        worker.join()
        assertTrue(applied)
        assertEquals(worker, appliedThread)
    }

    @Test
    fun systemAudioUsesTheSameAudioWorkerPriorityWithoutChangingOtherThreads() {
        var calls = 0
        assertTrue(
            applyAudioReceiverPriority(AudioCaptureSource.SystemAudio) {
                calls++
                true
            },
        )
        assertEquals(1, calls)
    }

    @Test
    fun missingSourceDoesNotAcquireMicrophoneScheduling() {
        assertFalse(applyAudioReceiverPriority(null) { error("unexpected_priority_request") })
    }

    @Test
    fun unavailablePriorityRemainsAReportedFailureInsteadOfFalseSuccess() {
        assertFalse(applyAudioReceiverPriority(AudioCaptureSource.MicrophoneAudio) { false })
    }
}
