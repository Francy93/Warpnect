package io.warpnect.platform.audio.capture.privileged

import io.warpnect.audio.capture.AudioCaptureError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPolicyCapabilityQualificationTest {
    @Test
    fun requiresTheAudioPolicyCallersRoutingPermissionWhenNoProjectionAuthorizationExists() {
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
    fun requiresTheRealAudioPolicyPathToBeStartableAfterStaticPrerequisitesPass() {
        val qualification = AudioPolicyCapabilityQualification(
            contextAvailable = true,
            hiddenApiAvailable = true,
            routingPermissionGranted = true,
        )

        assertEquals(
            AudioCaptureError.AudioRecordCreationFailed,
            qualification.requireStartability { AudioCaptureError.AudioRecordCreationFailed },
        )
    }

    @Test
    fun doesNotRunTheActivePathWhenStaticPrerequisitesAlreadyRejectIt() {
        val qualification = AudioPolicyCapabilityQualification(
            contextAvailable = true,
            hiddenApiAvailable = true,
            routingPermissionGranted = false,
        )
        var startabilityQueried = false

        val error = qualification.requireStartability {
            startabilityQueried = true
            AudioCaptureError.None
        }

        assertEquals(AudioCaptureError.PermissionDenied, error)
        assertFalse(startabilityQueried)
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
