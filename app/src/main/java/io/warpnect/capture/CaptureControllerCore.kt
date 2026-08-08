package io.warpnect.capture

internal class CaptureControllerCore(
    private val clock: () -> Long,
) {
    private var currentSnapshot = CaptureSessionSnapshot()

    fun snapshot(): CaptureSessionSnapshot = currentSnapshot

    fun beginStart(request: CaptureRequest, targetSurfaceIsValid: Boolean): CaptureError {
        val validation = CaptureValidation.validate(request, targetSurfaceIsValid)
        if (validation != CaptureError.None) {
            currentSnapshot = currentSnapshot.copy(
                state = CaptureState.Error,
                lastError = validation,
            )
            return validation
        }
        if (currentSnapshot.state == CaptureState.Running ||
            currentSnapshot.state == CaptureState.Starting ||
            currentSnapshot.state == CaptureState.Reconfiguring
        ) {
            currentSnapshot = currentSnapshot.copy(lastError = CaptureError.AlreadyRunning)
            return CaptureError.AlreadyRunning
        }

        currentSnapshot = CaptureSessionSnapshot(
            state = CaptureState.Starting,
            sourceDisplayId = request.sourceDisplayId,
            targetWidth = request.outputWidth,
            targetHeight = request.outputHeight,
            lastError = CaptureError.None,
        )
        return CaptureError.None
    }

    fun completeStart(error: CaptureError, backendSnapshot: CaptureSessionSnapshot?): CaptureStartResult {
        currentSnapshot = if (error == CaptureError.None) {
            (backendSnapshot ?: currentSnapshot).copy(
                state = CaptureState.Running,
                startedAtMonotonicUs = backendSnapshot?.startedAtMonotonicUs ?: clock(),
                lastError = CaptureError.None,
            )
        } else {
            currentSnapshot.copy(
                state = CaptureState.Error,
                lastError = error,
            )
        }
        return CaptureStartResult(error, currentSnapshot)
    }

    fun beginReconfigure(): CaptureError {
        if (currentSnapshot.state != CaptureState.Running) {
            return CaptureError.NotRunning
        }
        currentSnapshot = currentSnapshot.copy(state = CaptureState.Reconfiguring)
        return CaptureError.None
    }

    fun completeReconfigure(error: CaptureError, backendSnapshot: CaptureSessionSnapshot?) {
        currentSnapshot = if (error == CaptureError.None) {
            (backendSnapshot ?: currentSnapshot).copy(
                state = CaptureState.Running,
                lastError = CaptureError.None,
            )
        } else {
            currentSnapshot.copy(
                state = CaptureState.Error,
                lastError = error,
            )
        }
    }

    fun beginStop(): CaptureError {
        if (currentSnapshot.state == CaptureState.Stopped) {
            return CaptureError.None
        }
        currentSnapshot = currentSnapshot.copy(state = CaptureState.Stopping)
        return CaptureError.None
    }

    fun completeStop(error: CaptureError): CaptureStopResult {
        currentSnapshot = if (error == CaptureError.None || error == CaptureError.NotRunning) {
            CaptureSessionSnapshot(state = CaptureState.Stopped)
        } else {
            currentSnapshot.copy(
                state = CaptureState.Error,
                lastError = error,
            )
        }
        return CaptureStopResult(
            error = if (error == CaptureError.NotRunning) CaptureError.None else error,
            snapshot = currentSnapshot,
        )
    }

    fun onServiceDied() {
        fail(CaptureError.PrivilegedServiceDied)
    }

    fun fail(error: CaptureError) {
        currentSnapshot = currentSnapshot.copy(
            state = if (currentSnapshot.state == CaptureState.Stopped) {
                CaptureState.Stopped
            } else {
                CaptureState.Error
            },
            lastError = error,
        )
    }
}
