package io.warpnect.platform.capture

import android.content.Context
import android.view.Surface
import io.warpnect.capture.CaptureControllerCore
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureStartResult
import io.warpnect.capture.CaptureStopResult
import io.warpnect.capture.VideoCaptureController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidVideoCaptureController(
    context: Context,
    private val surfaceValidator: CaptureSurfaceValidator = AndroidCaptureSurfaceValidator,
) : VideoCaptureController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val core = CaptureControllerCore(CaptureClock::monotonicUs)
    private val monitor = DisplayConfigurationMonitor(context.applicationContext)
    private val gateway = ShizukuCaptureGateway(
        context = context.applicationContext,
        onServiceDied = {
            monitor.stop()
            core.onServiceDied()
        },
    )

    override suspend fun queryCapabilities() = gateway.queryCapabilities()

    override suspend fun requestPermission() = gateway.requestPermission()

    override suspend fun start(request: CaptureRequest, target: Surface): CaptureStartResult = lock.withLock {
        val validation = core.beginStart(request, surfaceValidator.isValid(target))
        if (validation != CaptureError.None) {
            return@withLock CaptureStartResult(validation, core.snapshot())
        }

        val startError = gateway.startCapture(request, target)
        val result = core.completeStart(startError, gateway.snapshot())
        if (result.isSuccess) {
            monitor.start(request.sourceDisplayId) { event ->
                scope.launch {
                    handleDisplayEvent(request, event)
                }
            }
        } else {
            monitor.stop()
            gateway.stopCapture()
        }
        result
    }

    override suspend fun stop(): CaptureStopResult = lock.withLock {
        core.beginStop()
        monitor.stop()
        val stopError = gateway.stopCapture()
        core.completeStop(stopError)
    }

    override fun snapshot(): CaptureSessionSnapshot = core.snapshot()

    override fun close() {
        monitor.stop()
        gateway.close()
    }

    private suspend fun handleDisplayEvent(request: CaptureRequest, event: DisplayConfigurationEvent) {
        lock.withLock {
            if (event == DisplayConfigurationEvent.Removed) {
                core.fail(CaptureError.DisplayRemoved)
                monitor.stop()
                gateway.stopCapture()
                return@withLock
            }
            if (core.beginReconfigure() == CaptureError.None) {
                val error = gateway.updateCapture(request)
                core.completeReconfigure(error, gateway.snapshot())
            }
        }
    }
}
