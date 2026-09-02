package io.warpnect.platform.audio.capture.privileged

import io.warpnect.audio.capture.AudioCaptureError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPolicyCapabilityQualificationTest {
    @Test
    fun requiresRoutingPermissionWhenNoProjectionAuthorizationExists() {
        val qualification = AudioPolicyCapabilityQualification(
            contextAvailable = true,
            hiddenApiAvailable = true,
            routingPermissionGranted = false,
        )

        assertFalse(qualification.isAvailable)
        assertEquals(AudioCaptureError.PermissionDenied, qualification.error)
    }

    @Test
    fun acceptsOnlyACompleteAuthorizedAudioPolicyPath() {
        val qualification = AudioPolicyCapabilityQualification(
            contextAvailable = true,
            hiddenApiAvailable = true,
            routingPermissionGranted = true,
        )

        assertTrue(qualification.isAvailable)
        assertEquals(AudioCaptureError.None, qualification.error)
    }

    @Test
    fun distinguishesServiceAndHiddenApiUnavailabilityBeforePermission() {
        val noContext = AudioPolicyCapabilityQualification(
            contextAvailable = false,
            hiddenApiAvailable = true,
            routingPermissionGranted = true,
        )
        val noApi = AudioPolicyCapabilityQualification(
            contextAvailable = true,
            hiddenApiAvailable = false,
            routingPermissionGranted = true,
        )

        assertEquals(AudioCaptureError.PrivilegedServiceUnavailable, noContext.error)
        assertEquals(AudioCaptureError.AudioPolicyUnavailable, noApi.error)
    }
}
