package io.warpnect.platform.audio.capture.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedBinderIdentityTest {
    @Test
    fun runsTheOperationAfterClearingAndBeforeRestoringTheAidlCallerIdentity() {
        val identity = RecordingIdentity()

        val result = withUserServiceIdentity(identity) {
            assertEquals(listOf("clear"), identity.events)
            "prepared"
        }

        assertEquals("prepared", result)
        assertEquals(listOf("clear", "restore:42"), identity.events)
    }

    @Test
    fun restoresTheAidlCallerIdentityWhenThePrivilegedOperationFails() {
        val identity = RecordingIdentity()

        val failure = runCatching {
            withUserServiceIdentity(identity) {
                error("expected")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("clear", "restore:42"), identity.events)
    }

    private class RecordingIdentity : PrivilegedBinderIdentity {
        val events = mutableListOf<String>()

        override fun clearCallingIdentity(): Long {
            events += "clear"
            return 42L
        }

        override fun restoreCallingIdentity(token: Long) {
            events += "restore:$token"
        }
    }
}
