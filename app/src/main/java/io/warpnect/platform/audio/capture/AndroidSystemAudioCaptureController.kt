package io.warpnect.platform.audio.capture

import android.content.Context
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureControllerCore
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureResult
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.PcmAudioSink

internal class AndroidSystemAudioCaptureController(
    context: Context,
    gatewayFactory: (
        onServiceDied: () -> Unit,
    ) -> PrivilegedAudioCaptureGateway = { onServiceDied ->
        ShizukuAudioCaptureGateway(context, onServiceDied)
    },
) : AudioCaptureController {
    private val lock = Any()
    private val core = AudioCaptureControllerCore()
    private val gateway = gatewayFactory(::onServiceDied)

    @Volatile
    private var closed = false

    private var sink: PcmAudioSink? = null
    private var drain: SharedPcmAudioDrain? = null

    override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
        if (request.source != AudioCaptureSource.SystemAudio) {
            return AudioCaptureCapabilities(
                source = request.source,
                available = false,
                lastError = AudioCaptureError.InvalidRequest,
            )
        }
        return kotlinx.coroutines.runBlocking {
            gateway.querySystemAudioCapabilities(request)
        }
    }

    override suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult =
        synchronized(lock) {
            if (closed) {
                return@synchronized AudioCaptureResult(AudioCaptureError.Closed, core.snapshot())
            }
            if (request.source != AudioCaptureSource.SystemAudio) {
                core.fail(AudioCaptureError.InvalidRequest)
                return@synchronized AudioCaptureResult(AudioCaptureError.InvalidRequest, core.snapshot())
            }
            val beginError = core.beginPrepare(request)
            if (beginError != AudioCaptureError.None) {
                return@synchronized AudioCaptureResult(beginError, core.snapshot())
            }
            val setup = kotlinx.coroutines.runBlocking {
                gateway.prepareSystemAudioCapture(request)
            }
            if (setup.error != AudioCaptureError.None ||
                setup.format == null ||
                setup.sharedMemory == null ||
                setup.notifyReadFd == null ||
                setup.ackWriteFd == null
            ) {
                return@synchronized prepareFailure(
                    if (setup.error == AudioCaptureError.None) {
                        AudioCaptureError.SharedRingCorrupt
                    } else {
                        setup.error
                    },
                )
            }
            return try {
                sink.onFormatChanged(setup.format)
                this.sink = sink
                drain = SharedPcmAudioDrain(
                    sharedMemory = setup.sharedMemory,
                    notifyReadFd = setup.notifyReadFd,
                    ackWriteFd = setup.ackWriteFd,
                    sink = sink,
                    onError = ::onDrainError,
                    onRingState = core::recordRingState,
                )
                core.completePrepare(
                    error = AudioCaptureError.None,
                    format = setup.format,
                    actualBufferFrames = setup.actualBufferSizeFrames,
                    ringCapacity = setup.ringCapacity,
                )
                AudioCaptureResult(AudioCaptureError.None, core.snapshot())
            } catch (_: RuntimeException) {
                prepareFailure(AudioCaptureError.SinkFailure)
            }
        }

    override suspend fun start(): AudioCaptureResult = synchronized(lock) {
        if (closed) {
            return@synchronized AudioCaptureResult(AudioCaptureError.Closed, core.snapshot())
        }
        val beginError = core.beginStart()
        if (beginError != AudioCaptureError.None) {
            return@synchronized AudioCaptureResult(beginError, core.snapshot())
        }
        val drainStart = drain?.start() ?: AudioCaptureError.NotPrepared
        if (drainStart != AudioCaptureError.None) {
            core.completeStart(drainStart)
            return@synchronized AudioCaptureResult(drainStart, core.snapshot())
        }
        val startError = kotlinx.coroutines.runBlocking {
            gateway.startSystemAudioCapture()
        }
        if (startError != AudioCaptureError.None) {
            drain?.close()
            drain = null
            core.completeStart(startError)
            return@synchronized AudioCaptureResult(startError, core.snapshot())
        }
        core.completeStart(AudioCaptureError.None)
        AudioCaptureResult(AudioCaptureError.None, core.snapshot())
    }

    override suspend fun stop(): AudioCaptureResult {
        synchronized(lock) {
            if (closed) {
                return AudioCaptureResult(AudioCaptureError.None, core.snapshot())
            }
            core.beginStop()
        }
        kotlinx.coroutines.runBlocking {
            gateway.stopSystemAudioCapture()
        }
        synchronized(lock) {
            drain?.close()
            drain = null
            sink = null
            return core.completeStop(AudioCaptureError.None)
        }
    }

    override fun snapshot() = core.snapshot()

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        kotlinx.coroutines.runBlocking {
            gateway.stopSystemAudioCapture()
        }
        synchronized(lock) {
            drain?.close()
            drain = null
            sink = null
            gateway.close()
            core.close()
        }
    }

    private fun prepareFailure(error: AudioCaptureError): AudioCaptureResult {
        kotlinx.coroutines.runBlocking {
            gateway.stopSystemAudioCapture()
        }
        drain?.close()
        drain = null
        core.completePrepare(error, null)
        return AudioCaptureResult(error, core.snapshot())
    }

    private fun onDrainError(error: AudioCaptureError) {
        synchronized(lock) {
            core.fail(error)
        }
        runCatching { sink?.onCaptureError(error) }
    }

    private fun onServiceDied() {
        onDrainError(AudioCaptureError.PrivilegedServiceDied)
        drain?.close()
        drain = null
    }
}
