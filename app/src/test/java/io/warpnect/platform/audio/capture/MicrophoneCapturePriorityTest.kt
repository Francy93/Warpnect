package io.warpnect.platform.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneCapturePriorityTest {
    @Test fun requestsUrgentAudioOnTheCallingWorkerOnly() {
        var requested: Int? = null
        var appliedThread: Thread? = null
        var applied = false
        val worker = Thread {
            applied = AndroidAudioThreadPriority.applyMicrophoneCapturePriority {
                requested = it
                appliedThread = Thread.currentThread()
            }
        }
        worker.start()
        worker.join()
        assertTrue(applied)
        assertEquals(-19, requested)
        assertEquals(worker, appliedThread)
    }

    @Test fun platformDenialIsNotReportedAsApplied() {
        assertFalse(
            AndroidAudioThreadPriority.applyMicrophoneCapturePriority { throw SecurityException("denied") },
        )
    }
}
