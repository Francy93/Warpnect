package io.warpnect.platform.audio.capture

import kotlin.coroutines.resume
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedUserServiceStartAwaiterTest {
    @Test
    fun returnsConnectedServiceBeforeStartupDeadline() = runTest {
        val result = awaitPrivilegedUserServiceStart<String>(30_000) { continuation ->
            continuation.resume("service")
        }

        assertEquals("service", result)
    }

    @Test
    fun returnsUnavailableAndCancelsBindingWhenServiceNeverConnects() = runTest {
        var cancellationCount = 0

        val result = awaitPrivilegedUserServiceStart<String>(30_000) { continuation ->
            continuation.invokeOnCancellation {
                cancellationCount++
            }
        }

        assertNull(result)
        assertEquals(1, cancellationCount)
    }
}
