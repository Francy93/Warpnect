package io.warpnect.video.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReceiverStreamConfigAdmissionTest {
    @Test
    fun admitsTheFirstConfigurationAndRejectsItsRetransmission() {
        val admission = VideoReceiverStreamConfigAdmission()

        assertTrue(admission.admits(7L))
        assertFalse(admission.admits(7L))
    }

    @Test
    fun admitsANewConfigurationGeneration() {
        val admission = VideoReceiverStreamConfigAdmission()

        assertTrue(admission.admits(7L))
        assertTrue(admission.admits(8L))
    }
}
