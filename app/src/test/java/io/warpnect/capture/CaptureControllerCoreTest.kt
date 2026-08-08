package io.warpnect.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureControllerCoreTest {
    private val request = CaptureRequest(
        sourceDisplayId = 0,
        outputWidth = 1280,
        outputHeight = 720,
    )

    @Test
    fun validationRejectsInvalidDimensionsBeforeBackendCall() {
        val core = CaptureControllerCore { 1000L }

        val error = core.beginStart(request.copy(outputWidth = 0), targetSurfaceIsValid = true)

        assertEquals(CaptureError.InvalidCaptureDimensions, error)
        assertEquals(CaptureState.Error, core.snapshot().state)
    }

    @Test
    fun validationRejectsInvalidSurfaceBeforeBackendCall() {
        val core = CaptureControllerCore { 1000L }

        val error = core.beginStart(request, targetSurfaceIsValid = false)

        assertEquals(CaptureError.InvalidTargetSurface, error)
        assertEquals(CaptureState.Error, core.snapshot().state)
    }

    @Test
    fun startRunningStopStoppedLifecycleIsExplicit() {
        val core = CaptureControllerCore { 1000L }

        assertEquals(CaptureError.None, core.beginStart(request, targetSurfaceIsValid = true))
        val start = core.completeStart(
            error = CaptureError.None,
            backendSnapshot = CaptureSessionSnapshot(
                state = CaptureState.Running,
                backend = CaptureBackend.SurfaceControlDisplay,
                sourceDisplayId = 0,
                sourceWidth = 1920,
                sourceHeight = 1080,
                sourceRotation = 0,
                targetWidth = 1280,
                targetHeight = 720,
                startedAtMonotonicUs = 1000L,
            ),
        )

        assertTrue(start.isSuccess)
        assertEquals(CaptureState.Running, start.snapshot.state)
        assertEquals(CaptureError.None, core.beginStop())
        val stopped = core.completeStop(CaptureError.None)
        assertTrue(stopped.isSuccess)
        assertEquals(CaptureState.Stopped, stopped.snapshot.state)
    }

    @Test
    fun duplicateStartDoesNotCreateSecondSession() {
        val core = CaptureControllerCore { 1000L }
        core.beginStart(request, targetSurfaceIsValid = true)
        core.completeStart(CaptureError.None, CaptureSessionSnapshot(state = CaptureState.Running))

        val duplicate = core.beginStart(request, targetSurfaceIsValid = true)

        assertEquals(CaptureError.AlreadyRunning, duplicate)
        assertEquals(CaptureState.Running, core.snapshot().state)
    }

    @Test
    fun startFailureLeavesExplicitErrorForRollbackPath() {
        val core = CaptureControllerCore { 1000L }

        core.beginStart(request, targetSurfaceIsValid = true)
        val result = core.completeStart(CaptureError.SourceDisplayNotFound, null)

        assertEquals(CaptureError.SourceDisplayNotFound, result.error)
        assertEquals(CaptureState.Error, result.snapshot.state)
    }

    @Test
    fun stopWhileStoppedIsNoOpSuccess() {
        val core = CaptureControllerCore { 1000L }

        assertEquals(CaptureError.None, core.beginStop())
        val result = core.completeStop(CaptureError.None)

        assertTrue(result.isSuccess)
        assertEquals(CaptureState.Stopped, result.snapshot.state)
    }

    @Test
    fun restartAfterStopReusesStateCleanly() {
        val core = CaptureControllerCore { 1000L }

        core.beginStart(request, targetSurfaceIsValid = true)
        core.completeStart(CaptureError.None, CaptureSessionSnapshot(state = CaptureState.Running))
        core.beginStop()
        core.completeStop(CaptureError.None)
        val restart = core.beginStart(request.copy(sourceDisplayId = 1), targetSurfaceIsValid = true)

        assertEquals(CaptureError.None, restart)
        assertEquals(1, core.snapshot().sourceDisplayId)
        assertEquals(CaptureState.Starting, core.snapshot().state)
    }

    @Test
    fun reconfigurationFailureIsTyped() {
        val core = CaptureControllerCore { 1000L }
        core.beginStart(request, targetSurfaceIsValid = true)
        core.completeStart(CaptureError.None, CaptureSessionSnapshot(state = CaptureState.Running))

        assertEquals(CaptureError.None, core.beginReconfigure())
        core.completeReconfigure(CaptureError.DisplayRemoved, null)

        assertEquals(CaptureState.Error, core.snapshot().state)
        assertEquals(CaptureError.DisplayRemoved, core.snapshot().lastError)
    }

    @Test
    fun binderDeathLeavesErrorState() {
        val core = CaptureControllerCore { 1000L }
        core.beginStart(request, targetSurfaceIsValid = true)
        core.completeStart(CaptureError.None, CaptureSessionSnapshot(state = CaptureState.Running))

        core.onServiceDied()

        assertEquals(CaptureState.Error, core.snapshot().state)
        assertEquals(CaptureError.PrivilegedServiceDied, core.snapshot().lastError)
    }

    @Test
    fun hiddenApiFailureMapsToBackendUnavailableState() {
        val capabilities = CaptureCapabilities(
            privilegeState = CapturePrivilegeState.BackendUnavailable,
            backend = CaptureBackend.None,
            backendAvailable = false,
            supportedSourceDisplays = emptyList(),
            supportsDynamicProjection = false,
            platformApiLevel = 35,
            lastError = CaptureError.HiddenApiUnavailable,
        )

        assertEquals(CapturePrivilegeState.BackendUnavailable, capabilities.privilegeState)
        assertEquals(CaptureError.HiddenApiUnavailable, capabilities.lastError)
    }
}
