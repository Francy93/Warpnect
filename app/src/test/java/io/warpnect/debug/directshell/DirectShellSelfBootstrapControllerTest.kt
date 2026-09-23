package io.warpnect.debug.directshell

import java.io.IOException
import java.net.ConnectException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellSelfBootstrapControllerTest {
    @Test
    fun recognizesAConnectionRefusalWrappedByTheAdbClient() {
        val error = IOException("transport failure", ConnectException("Connection refused"))

        assertTrue(hasConnectionRefusedCause(error))
    }

    @Test
    fun doesNotTreatAnUnrelatedIoFailureAsAStaleEndpoint() {
        val error = IOException("TLS authentication failed")

        assertFalse(hasConnectionRefusedCause(error))
    }
}
