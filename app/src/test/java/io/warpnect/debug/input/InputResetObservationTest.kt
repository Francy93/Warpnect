package io.warpnect.debug.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputResetObservationTest {
    private val prefix = listOf("KEY_DOWN", "TOUCH_DOWN")
    private val releases = setOf("JOYSTICK", "KEY_UP", "TOUCH_CANCEL")

    @Test
    fun keyReleaseBeforeTouchCancelIsObserved() {
        assertTrue(matchesResetObservation(prefix + listOf("JOYSTICK", "KEY_UP", "TOUCH_CANCEL"), prefix, releases))
    }

    @Test
    fun touchCancelBeforeKeyReleaseIsObserved() {
        assertTrue(matchesResetObservation(prefix + listOf("JOYSTICK", "TOUCH_CANCEL", "KEY_UP"), prefix, releases))
    }

    @Test
    fun missingReleaseIsRejected() {
        assertFalse(matchesResetObservation(prefix + listOf("JOYSTICK", "KEY_UP"), prefix, releases))
    }

    @Test
    fun duplicateCannotSubstituteForMissingRelease() {
        assertFalse(matchesResetObservation(prefix + listOf("JOYSTICK", "KEY_UP", "KEY_UP"), prefix, releases))
    }

    @Test
    fun ordinaryEventsMustKeepTheirOrder() {
        assertFalse(matchesResetObservation(prefix.reversed() + releases, prefix, releases))
    }

    @Test
    fun unexpectedEventCannotSubstituteForRelease() {
        assertFalse(matchesResetObservation(prefix + listOf("TOUCH_MOVE", "KEY_UP", "TOUCH_CANCEL"), prefix, releases))
    }

    @Test
    fun extraEventIsRejected() {
        assertFalse(matchesResetObservation(prefix + releases + "KEY_UP", prefix, releases))
    }

    @Test
    fun disconnectRequiresBothSourceReleases() {
        val disconnect = setOf("KEY_UP", "TOUCH_CANCEL")
        assertTrue(matchesResetObservation(prefix + disconnect.reversed(), prefix, disconnect))
        assertFalse(matchesResetObservation(prefix + "KEY_UP", prefix, disconnect))
    }
}
