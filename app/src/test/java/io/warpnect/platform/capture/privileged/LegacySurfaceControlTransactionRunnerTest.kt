package io.warpnect.platform.capture.privileged

import io.warpnect.capture.CaptureError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySurfaceControlTransactionRunnerTest {
    @Test
    fun configurationUsesOneTransactionInSurfaceProjectionLayerOrder() {
        val calls = mutableListOf<String>()

        LegacySurfaceControlTransactionRunner.configure(
            open = { calls += "open" },
            attachSurface = { calls += "surface" },
            setProjection = { calls += "projection" },
            setLayerStack = { calls += "layer" },
            close = { calls += "close" },
        )

        assertEquals(listOf("open", "surface", "projection", "layer", "close"), calls)
    }

    @Test
    fun transactionClosesWhenConfigurationFails() {
        val calls = mutableListOf<String>()

        runCatching {
            LegacySurfaceControlTransactionRunner.configure(
                open = { calls += "open" },
                attachSurface = { calls += "surface" },
                setProjection = { throw IllegalStateException("test") },
                setLayerStack = { calls += "layer" },
                close = { calls += "close" },
            )
        }

        assertEquals(listOf("open", "surface", "close"), calls)
    }

    @Test
    fun secureQualificationUsesInsecureModeOnlyWhenSecureModeFails() {
        val calls = mutableListOf<Boolean>()
        val qualification = LegacySecureModeSelector().select { secure ->
            calls += secure
            if (secure) CaptureError.CaptureCreationFailed else CaptureError.None
        }

        assertEquals(listOf(true, false), calls)
        assertEquals(false, qualification.secure)
        assertEquals(CaptureError.None, qualification.error)
    }

    @Test
    fun secureQualificationDoesNotProbeInsecureModeWhenSecureModeWorks() {
        val calls = mutableListOf<Boolean>()
        val qualification = LegacySecureModeSelector().select { secure ->
            calls += secure
            CaptureError.None
        }

        assertEquals(listOf(true), calls)
        assertTrue(qualification.secure == true)
        assertEquals(CaptureError.None, qualification.error)
    }
}
