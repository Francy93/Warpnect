package io.warpnect.platform.capture.privileged

import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifiedPrivilegedDisplayCaptureApiTest {
    @Test
    fun modernStrategyIsSelectedWhenBothStrategiesQualify() {
        val modern = FakeStrategy(CaptureBackend.DisplayManagerMirror, available = true)
        val legacy = FakeStrategy(CaptureBackend.SurfaceControlDisplay, available = true)
        val api = QualifiedPrivilegedDisplayCaptureApi(listOf(modern, legacy))

        val capabilities = api.queryCapabilities()

        assertEquals(CaptureBackend.DisplayManagerMirror, capabilities.backend)
        assertTrue(capabilities.backendAvailable)
        assertEquals(1, modern.queryCalls)
        assertEquals(1, legacy.queryCalls)
    }

    @Test
    fun legacyStrategyIsSelectedWhenModernIsUnavailable() {
        val modern = FakeStrategy(CaptureBackend.DisplayManagerMirror, available = false)
        val legacy = FakeStrategy(CaptureBackend.SurfaceControlDisplay, available = true)
        val api = QualifiedPrivilegedDisplayCaptureApi(listOf(modern, legacy))

        val capabilities = api.queryCapabilities()

        assertEquals(CaptureBackend.SurfaceControlDisplay, capabilities.backend)
        assertTrue(capabilities.backendAvailable)
    }

    @Test
    fun unavailableStrategiesProduceTypedBackendUnavailable() {
        val api = QualifiedPrivilegedDisplayCaptureApi(
            listOf(
                FakeStrategy(CaptureBackend.DisplayManagerMirror, available = false),
                FakeStrategy(CaptureBackend.SurfaceControlDisplay, available = false),
            ),
        )

        val capabilities = api.queryCapabilities()

        assertFalse(capabilities.backendAvailable)
        assertEquals(CaptureBackend.None, capabilities.backend)
        assertEquals(CaptureError.CaptureBackendUnavailable, capabilities.lastError)
    }

    @Test
    fun selectedStrategyIsCachedAndDoesNotSwitchAfterQualification() {
        val modern = FakeStrategy(CaptureBackend.DisplayManagerMirror, available = true)
        val legacy = FakeStrategy(CaptureBackend.SurfaceControlDisplay, available = false)
        val api = QualifiedPrivilegedDisplayCaptureApi(listOf(modern, legacy))

        assertEquals(CaptureBackend.DisplayManagerMirror, api.queryCapabilities().backend)
        modern.available = false
        legacy.available = true

        assertEquals(CaptureBackend.DisplayManagerMirror, api.queryCapabilities().backend)
        assertEquals(1, modern.queryCalls)
        assertEquals(1, legacy.queryCalls)
    }

    private class FakeStrategy(
        private val backend: CaptureBackend,
        var available: Boolean,
    ) : PrivilegedDisplayCaptureApi {
        var queryCalls = 0

        override fun queryCapabilities(): CaptureCapabilities {
            queryCalls += 1
            return CaptureCapabilities(
                privilegeState = if (available) {
                    CapturePrivilegeState.Ready
                } else {
                    CapturePrivilegeState.BackendUnavailable
                },
                backend = backend,
                backendAvailable = available,
                supportedSourceDisplays = emptyList(),
                supportsDynamicProjection = available,
                platformApiLevel = 35,
                lastError = if (available) CaptureError.None else CaptureError.HiddenApiUnavailable,
            )
        }

        override fun startCapture(request: CaptureRequest, targetSurface: Surface): CaptureError = CaptureError.None

        override fun updateCapture(request: CaptureRequest): CaptureError = CaptureError.None

        override fun stopCapture(): CaptureError = CaptureError.None

        override fun snapshot(): CaptureSessionSnapshot = CaptureSessionSnapshot(backend = backend)
    }
}
