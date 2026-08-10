package io.warpnect.platform.video.transport

import io.warpnect.video.session.VideoKeyFrameRequestHandler
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
    private val keyFrameRequestHandler: VideoKeyFrameRequestHandler,
) : VideoSenderControlRuntime {
    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var localSnapshot = VideoSenderControlSnapshot()
    private var observedKeyFrameRequests = 0L

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
                    observeKeyFrameRequests()
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

    private fun observeKeyFrameRequests() {
        val transportSnapshot = transportController.snapshot()
        val pending = transportSnapshot.keyFrameRequestsReceived - observedKeyFrameRequests
        if (pending <= 0L) {
            return
        }
        observedKeyFrameRequests = transportSnapshot.keyFrameRequestsReceived
        val result = keyFrameRequestHandler.onKeyFrameRequested()
        localSnapshot = if (result.isSuccess) {
            localSnapshot.copy(
                keyFrameRequestsObserved = localSnapshot.keyFrameRequestsObserved + pending,
                keyFrameRequestsForwarded = localSnapshot.keyFrameRequestsForwarded + 1,
            )
        } else {
            localSnapshot.copy(
                keyFrameRequestsObserved = localSnapshot.keyFrameRequestsObserved + pending,
                keyFrameRequestFailures = localSnapshot.keyFrameRequestFailures + 1,
                lastSessionFailure = result.failure,
            )
        }
    }

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

        override fun create(
            transportController: VideoTransportController,
            keyFrameRequestHandler: VideoKeyFrameRequestHandler,
        ): VideoSenderControlRuntime? = (transportController as? NativeSclVideoTransportController)?.let {
            NativeVideoSenderControlRuntime(it, keyFrameRequestHandler)
        }
    }
}
