package io.warpnect.platform.video.transport

import io.warpnect.video.session.VideoSenderControlRuntime
import io.warpnect.video.session.VideoSenderControlRuntimeFactory
import io.warpnect.video.session.VideoSenderControlSnapshot
import io.warpnect.video.session.VideoSessionControlResult
import io.warpnect.video.session.VideoSessionError
import io.warpnect.video.session.VideoSessionErrorSource
import io.warpnect.video.session.VideoSessionFailure
import io.warpnect.video.transport.VideoTransportController
import io.warpnect.video.transport.VideoTransportError

class NativeVideoSenderControlRuntime(
    private val transportController: NativeSclVideoTransportController,
) : VideoSenderControlRuntime {
    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var localSnapshot = VideoSenderControlSnapshot()

    override fun start(timeoutUs: Long): VideoSessionControlResult {
        if (timeoutUs < 0L) {
            return failure(VideoTransportError.InvalidBufferRange)
        }
        if (running) {
            return VideoSessionControlResult.Success
        }
        running = true
        worker = Thread({
            while (running) {
                val result = transportController.pumpControl(timeoutUs)
                if (!result.isSuccess) {
                    localSnapshot = localSnapshot.copy(
                        running = false,
                        transportErrors = localSnapshot.transportErrors + 1,
                        lastError = result.error,
                    )
                    running = false
                } else {
                    localSnapshot = localSnapshot.copy(
                        running = true,
                        pumpIterations = localSnapshot.pumpIterations + 1,
                        lastError = VideoTransportError.None,
                    )
                }
            }
        }, THREAD_NAME).apply { start() }
        localSnapshot = localSnapshot.copy(running = true)
        return VideoSessionControlResult.Success
    }

    override fun stop(): VideoSessionControlResult {
        running = false
        val currentWorker = worker
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.join(STOP_JOIN_TIMEOUT_MS)
        }
        worker = null
        localSnapshot = localSnapshot.copy(running = false)
        return VideoSessionControlResult.Success
    }

    override fun snapshot(): VideoSenderControlSnapshot = localSnapshot.copy(running = running)

    private fun failure(error: VideoTransportError): VideoSessionControlResult = VideoSessionControlResult(
        error = VideoSessionError.TransportFailed,
        failure = VideoSessionFailure(
            source = VideoSessionErrorSource.Transport,
            error = VideoSessionError.TransportFailed,
            transportError = error,
        ),
    )

    companion object : VideoSenderControlRuntimeFactory {
        private const val THREAD_NAME = "WarpnectVideoSenderControl"
        private const val STOP_JOIN_TIMEOUT_MS = 250L

        override fun create(transportController: VideoTransportController): VideoSenderControlRuntime? =
            (transportController as? NativeSclVideoTransportController)?.let {
                NativeVideoSenderControlRuntime(it)
            }
    }
}
