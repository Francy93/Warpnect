package io.warpnect.input.session

import android.view.View
import io.warpnect.input.capture.InputCaptureController
import io.warpnect.input.mapping.RemoteVideoViewportInputMapper
import io.warpnect.input.transport.InputTransportController
import io.warpnect.input.transport.SclInputEventSink
import io.warpnect.video.render.VideoViewportGeometryProvider

/** Caller-driven source composition: capture -> viewport mapping -> SCL input transport. */
class ReverseInputSenderSessionController(
    private val captureController: InputCaptureController,
    private val geometryProvider: VideoViewportGeometryProvider,
    private val transportController: InputTransportController,
) : AutoCloseable {
    private var mapper: RemoteVideoViewportInputMapper? = null
    private var reliabilitySink: SclInputEventSink? = null
    private var snapshot = ReverseInputSenderSessionSnapshot()
    private var closed = false

    fun start(surface: View, config: ReverseInputSenderSessionConfig): ReverseInputSenderSessionResult {
        if (closed) return result(ReverseInputSessionError.Closed)
        if (!config.isValid()) return fail(ReverseInputSessionError.InvalidConfiguration)
        if (snapshot.state == ReverseInputSessionState.Running) return result(ReverseInputSessionError.None)
        snapshot = snapshot.copy(
            state = ReverseInputSessionState.Starting,
            startAttempts = snapshot.startAttempts + 1L,
            lastError = ReverseInputSessionError.None,
            stopResetAttempted = false,
            stopResetSent = false,
            stopResetFailed = false,
        )

        val preparedTransport = transportController.prepare(config.transportConfig)
        if (!preparedTransport.isSuccess) return fail(ReverseInputSessionError.TransportPrepareFailed)
        val startedTransport = transportController.start()
        if (!startedTransport.isSuccess) {
            transportController.stop()
            return fail(ReverseInputSessionError.TransportStartFailed)
        }

        val activeReliabilitySink = SclInputEventSink(transportController, config.reliabilityConfig)
        val activeMapper = try {
            RemoteVideoViewportInputMapper(
                geometryProvider = geometryProvider,
                downstream = activeReliabilitySink,
                config = config.viewportMappingConfig,
            )
        } catch (_: IllegalArgumentException) {
            transportController.stop()
            return fail(ReverseInputSessionError.MapperPrepareFailed)
        }
        mapper = activeMapper
        reliabilitySink = activeReliabilitySink
        val preparedCapture = captureController.prepare(surface, config.captureConfig, activeMapper)
        if (!preparedCapture.isSuccess) {
            activeMapper.close()
            mapper = null
            reliabilitySink = null
            transportController.stop()
            return fail(ReverseInputSessionError.CapturePrepareFailed)
        }
        val startedCapture = captureController.start()
        if (!startedCapture.isSuccess) {
            captureController.stop()
            activeMapper.close()
            mapper = null
            reliabilitySink = null
            transportController.stop()
            return fail(ReverseInputSessionError.CaptureStartFailed)
        }
        snapshot = snapshot.copy(state = ReverseInputSessionState.Running, lastError = ReverseInputSessionError.None)
        return result(ReverseInputSessionError.None)
    }

    fun stop(): ReverseInputSenderSessionResult {
        if (closed) return result(ReverseInputSessionError.Closed)
        if (snapshot.state == ReverseInputSessionState.Stopped) return result(ReverseInputSessionError.None)
        snapshot = snapshot.copy(
            state = ReverseInputSessionState.Stopping,
            stopAttempts = snapshot.stopAttempts + 1L,
            stopResetAttempted = snapshot.state == ReverseInputSessionState.Running,
        )
        val before = transportController.snapshot()
        captureController.stop()
        val afterCaptureStop = transportController.snapshot()
        val resetSent = afterCaptureStop.resetsSent > before.resetsSent
        val resetFailed = afterCaptureStop.resetSendFailures > before.resetSendFailures
        val reliability = reliabilitySink?.snapshot() ?: snapshot.reliability
        transportController.stop()
        mapper?.close()
        mapper = null
        reliabilitySink = null
        snapshot = snapshot.copy(
            state = ReverseInputSessionState.Stopped,
            stopResetSent = resetSent,
            stopResetFailed = resetFailed,
            reliability = reliability,
            lastError = if (resetFailed) ReverseInputSessionError.ResetSendFailed else ReverseInputSessionError.None,
        )
        return result(snapshot.lastError)
    }

    fun snapshot(): ReverseInputSenderSessionSnapshot = snapshot.copy(
        capture = captureController.snapshot(),
        mapper = mapper?.snapshot(),
        reliability = reliabilitySink?.snapshot() ?: snapshot.reliability,
        transport = transportController.snapshot(),
    )

    override fun close() {
        if (closed) return
        stop()
        captureController.close()
        mapper?.close()
        mapper = null
        reliabilitySink = null
        transportController.close()
        closed = true
        snapshot = snapshot.copy(state = ReverseInputSessionState.Closed, lastError = ReverseInputSessionError.Closed)
    }

    private fun fail(error: ReverseInputSessionError): ReverseInputSenderSessionResult {
        snapshot = snapshot.copy(state = ReverseInputSessionState.Error, lastError = error)
        return result(error)
    }

    private fun result(error: ReverseInputSessionError): ReverseInputSenderSessionResult =
        ReverseInputSenderSessionResult(error, snapshot())
}
