package io.warpnect.audio.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneAudioDestinationTest {
    @Test fun releaseDoesNotOfferPlaybackAsVirtualInput() {
        val destination = MicrophoneAudioDestination.forBuild(debug = false)
        assertEquals(MicrophoneAudioDestination.HostVirtualInputNotImplemented, destination)
        assertFalse(destination.receiverAvailable)
    }

    @Test fun debugOffersOnlyAnExplicitDiagnosticDestination() {
        val destination = MicrophoneAudioDestination.forBuild(debug = true)
        assertEquals(MicrophoneAudioDestination.DebugPlayback, destination)
        assertTrue(destination.receiverAvailable)
    }
}
