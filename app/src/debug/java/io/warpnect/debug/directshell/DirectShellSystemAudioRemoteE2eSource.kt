package io.warpnect.debug.directshell

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureControllerCore
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureResult
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.platform.audio.capture.DebugSystemAudioCaptureOverride
import io.warpnect.telemetry.AudioSenderTelemetry
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Explicitly armed, process-local DEBUG source for the DirectShell remote-E2E spike.
 *
 * It is not a production capability provider: the normal application installs it only through the
 * DEBUG broadcast receiver after an authenticated, pre-launched shell server is reachable.
 */
@TargetApi(Build.VERSION_CODES.S)
internal class DirectShellSystemAudioRemoteE2eSource private constructor(
    private val port: Int,
    private val generationId: Long,
    secret: ByteArray,
) : DebugSystemAudioCaptureOverride {
    private val secret = DirectShellProbeProtocol.requireSecret(secret)

    @Volatile
    private var cleared = false

    override fun createController(context: Context, telemetry: AudioSenderTelemetry?): AudioCaptureController {
        return DirectShellSystemAudioRemoteE2eCaptureController(
            port = port,
            generationId = generationId,
            secret = copySecret(),
            telemetry = telemetry,
        )
    }

    fun probe(): RuntimeProbe = probeServer(port, copySecret())

    fun describe(): String = "generation=$generationId port=$port cleared=$cleared"

    fun shutdown(): String {
        val shutdownSecret = copySecret()
        if (shutdownSecret.size != DirectShellProbeProtocol.SECRET_BYTES) return "secret_cleared"
        return try {
            DirectShellPcmRingProbeClient.connectLoopback(port, shutdownSecret).use { client ->
                "server_${client.call(DirectShellProbeProtocol.Command.SHUTDOWN).status.name.lowercase()}"
            }
        } catch (_: IOException) {
            "server_already_unreachable"
        } catch (_: DirectShellProbeProtocol.ProtocolException) {
            "server_protocol_unreachable"
        } finally {
            Arrays.fill(shutdownSecret, 0.toByte())
        }
    }

    fun clear() {
        if (!cleared) {
            Arrays.fill(secret, 0.toByte())
            cleared = true
        }
    }

    private fun copySecret(): ByteArray = if (cleared) ByteArray(0) else secret.clone()

    companion object {
        fun create(port: Int, generationId: Long, secret: ByteArray): DirectShellSystemAudioRemoteE2eSource? {
            if (port !in MIN_PORT..MAX_PORT || generationId <= 0L) return null
            return try {
                DirectShellSystemAudioRemoteE2eSource(port, generationId, secret)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        internal fun probeServer(port: Int, secret: ByteArray): RuntimeProbe {
            if (secret.size != DirectShellProbeProtocol.SECRET_BYTES) {
                return RuntimeProbe(false, AudioCaptureError.PrivilegedServiceUnavailable, "invalid_secret")
            }
            return try {
                DirectShellPcmRingProbeClient.connectLoopback(port, secret).use { client ->
                    val ping = client.call(DirectShellProbeProtocol.Command.PING)
                    if (ping.status != DirectShellProbeProtocol.Status.OK) {
                        return RuntimeProbe(
                            false,
                            AudioCaptureError.PrivilegedServiceUnavailable,
                            "ping_${ping.status.name}",
                        )
                    }
                    val runtime = client.call(DirectShellProbeProtocol.Command.GET_RUNTIME_INFO)
                    if (runtime.status != DirectShellProbeProtocol.Status.OK) {
                        return RuntimeProbe(
                            false,
                            AudioCaptureError.PrivilegedServiceUnavailable,
                            "runtime_${runtime.status.name}",
                        )
                    }
                    val facts = runtime.payload.lineSequence()
                        .mapNotNull { line ->
                            val separator = line.indexOf('=')
                            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                        }
                        .toMap()
                    val uid = facts["uid"]?.toIntOrNull()
                    if (uid != Process.SHELL_UID || facts["uid_kind"] != "SHELL") {
                        return RuntimeProbe(false, AudioCaptureError.PermissionDenied, "shell_identity_rejected")
                    }
                    RuntimeProbe(true, AudioCaptureError.None, "shell_uid=$uid api=${facts["api"] ?: "unknown"}")
                }
            } catch (exception: IOException) {
                RuntimeProbe(
                    false,
                    AudioCaptureError.PrivilegedServiceUnavailable,
                    "control_${exception.javaClass.simpleName}",
                )
            } catch (exception: DirectShellProbeProtocol.ProtocolException) {
                RuntimeProbe(
                    false,
                    AudioCaptureError.PrivilegedServiceUnavailable,
                    "protocol_${exception.javaClass.simpleName}",
                )
            } finally {
                Arrays.fill(secret, 0.toByte())
            }
        }

        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65_535
    }

    internal data class RuntimeProbe(
        val available: Boolean,
        val error: AudioCaptureError,
        val detail: String,
    )
}

/**
 * DEBUG-only capture adapter. It owns only DirectShell setup/control and the existing production
 * ring drain; the supplied sink is the normal Session Opus input sink.
 */
@TargetApi(Build.VERSION_CODES.S)
internal class DirectShellSystemAudioRemoteE2eCaptureController(
    private val port: Int,
    private val generationId: Long,
    secret: ByteArray,
    private val telemetry: AudioSenderTelemetry?,
) : AudioCaptureController {
    private val lock = Any()
    private val controlLock = Any()
    private val core = AudioCaptureControllerCore()
    private val secret = DirectShellProbeProtocol.requireSecret(secret)
    private val heartbeatRunning = AtomicBoolean(false)

    @Volatile
    private var closed = false

    private var sink: PcmAudioSink? = null
    private var ringArmed = false
    private var observedRingOverruns = 0
    private var heartbeatThread: Thread? = null

    override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
        val requestError = directShellRequestError(request)
        if (requestError != AudioCaptureError.None) return unavailable(requestError)
        val probe = DirectShellSystemAudioRemoteE2eSource.probeServer(port, copySecret())
        if (!probe.available) {
            log("DIRECT_SHELL_REMOTE_E2E_CAPABILITY_UNAVAILABLE generation=$generationId detail=${probe.detail}")
            return unavailable(probe.error)
        }
        return AudioCaptureCapabilities(
            source = AudioCaptureSource.SystemAudio,
            available = true,
            supportedSampleRatesHz = listOf(DirectShellRemoteE2eAudioContract.SAMPLE_RATE_HZ),
            selectedSampleRateHz = DirectShellRemoteE2eAudioContract.SAMPLE_RATE_HZ,
            channelCount = DirectShellRemoteE2eAudioContract.CHANNEL_COUNT,
            encoding = AudioPcmEncoding.Pcm16,
            privilegedBackendAvailable = true,
            requestedBufferSizeBytes = DirectShellRemoteE2eAudioContract.PAYLOAD_CAPACITY_BYTES,
            timestampSupport = AudioTimestampQuality.EstimatedFromReadCompletion,
            lastError = AudioCaptureError.None,
        )
    }

    override suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult =
        synchronized(lock) {
            if (closed) return@synchronized AudioCaptureResult(AudioCaptureError.Closed, core.snapshot())
            val requestError = directShellRequestError(request)
            if (requestError != AudioCaptureError.None) {
                core.fail(requestError)
                return@synchronized AudioCaptureResult(requestError, core.snapshot())
            }
            val beginError = core.beginPrepare(request)
            if (beginError != AudioCaptureError.None) {
                return@synchronized AudioCaptureResult(beginError, core.snapshot())
            }
            this.sink = sink
            val ringResult = DirectShellPcmRingProbeSession.armForDownstream(
                generationId = generationId,
                secret = copySecret(),
                downstreamSink = sink,
                callbacks = DirectShellPcmRingProbeSession.DrainCallbacks(
                    onError = ::onDrainError,
                    onRingState = ::onRingState,
                    onPcmAccepted = ::onPcmAccepted,
                ),
            )
            if (!ringResult.success) {
                return@synchronized prepareFailure(mapRingFailure(ringResult.payload))
            }
            ringArmed = true
            val bind = control(DirectShellProbeProtocol.Command.PCM_RING_BIND)
            if (!bind.success) {
                closeRemoteDescriptors()
                DirectShellPcmRingProbeSession.finish(generationId)
                ringArmed = false
                return@synchronized prepareFailure(mapControlFailure(bind.payload))
            }
            core.completePrepare(
                error = AudioCaptureError.None,
                format = directShellFormat(),
                ringCapacity = DirectShellRemoteE2eAudioContract.RING_SLOT_COUNT,
            )
            log(
                "DIRECT_SHELL_REMOTE_E2E_SOURCE_SELECTED generation=$generationId port=$port " +
                    "source=REMOTE_SUBMIX ring=SharedPcmAudioRingV1 shizuku_audio_producer=false",
            )
            AudioCaptureResult(AudioCaptureError.None, core.snapshot())
        }

    override suspend fun start(): AudioCaptureResult {
        synchronized(lock) {
            if (closed) return AudioCaptureResult(AudioCaptureError.Closed, core.snapshot())
            val beginError = core.beginStart()
            if (beginError != AudioCaptureError.None) {
                return AudioCaptureResult(beginError, core.snapshot())
            }
        }
        val drainStart = DirectShellPcmRingProbeSession.startDrain(generationId)
        if (!drainStart.success) return startFailure(mapRingFailure(drainStart.payload))
        val prepare = control(DirectShellProbeProtocol.Command.PCM_RING_AUDIO_PREPARE)
        if (!prepare.success) return startFailure(mapControlFailure(prepare.payload))
        val start = control(DirectShellProbeProtocol.Command.PCM_RING_AUDIO_START)
        if (!start.success) return startFailure(mapControlFailure(start.payload))
        synchronized(lock) {
            core.completeStart(AudioCaptureError.None)
        }
        startHeartbeat()
        log("DIRECT_SHELL_REMOTE_E2E_CAPTURE_RUNNING generation=$generationId")
        return AudioCaptureResult(AudioCaptureError.None, core.snapshot())
    }

    override suspend fun stop(): AudioCaptureResult {
        synchronized(lock) {
            if (closed) return AudioCaptureResult(AudioCaptureError.None, core.snapshot())
            val beginError = core.beginStop()
            if (beginError != AudioCaptureError.None) {
                return AudioCaptureResult(beginError, core.snapshot())
            }
        }
        stopHeartbeat()
        var error = AudioCaptureError.None
        if (ringArmed) {
            val stop = control(DirectShellProbeProtocol.Command.PCM_RING_AUDIO_STOP)
            if (!stop.success) error = mapControlFailure(stop.payload)
            val close = control(DirectShellProbeProtocol.Command.PCM_RING_CLOSE)
            if (!close.success && error == AudioCaptureError.None) error = mapControlFailure(close.payload)
            val finish = DirectShellPcmRingProbeSession.finish(generationId)
            if (!finish.success && error == AudioCaptureError.None) error = mapRingFailure(finish.payload)
            ringArmed = false
            val shutdown = control(DirectShellProbeProtocol.Command.SHUTDOWN)
            if (!shutdown.success && error == AudioCaptureError.None) error = mapControlFailure(shutdown.payload)
        }
        synchronized(lock) {
            sink = null
            return core.completeStop(error)
        }
    }

    override fun snapshot() = core.snapshot()

    override fun close() {
        val shouldStop = synchronized(lock) { !closed }
        if (shouldStop) {
            runBlocking { stop() }
        }
        synchronized(lock) {
            if (closed) return
            closed = true
            sink = null
            Arrays.fill(secret, 0.toByte())
            core.close()
        }
    }

    private fun startFailure(error: AudioCaptureError): AudioCaptureResult {
        synchronized(lock) {
            core.completeStart(error)
            return AudioCaptureResult(error, core.snapshot())
        }
    }

    private fun prepareFailure(error: AudioCaptureError): AudioCaptureResult {
        if (ringArmed) {
            closeRemoteDescriptors()
            DirectShellPcmRingProbeSession.finish(generationId)
            ringArmed = false
        }
        control(DirectShellProbeProtocol.Command.SHUTDOWN)
        sink = null
        core.completePrepare(error, null)
        return AudioCaptureResult(error, core.snapshot())
    }

    private fun onDrainError(error: AudioCaptureError) {
        synchronized(lock) {
            core.fail(error)
        }
        runCatching { sink?.onCaptureError(error) }
        log("DIRECT_SHELL_REMOTE_E2E_DRAIN_ERROR generation=$generationId error=${error.name}")
    }

    private fun onRingState(occupancy: Int, highWater: Int, overruns: Int) {
        synchronized(lock) {
            core.recordRingState(occupancy, highWater)
            val delta = (overruns - observedRingOverruns).coerceAtLeast(0)
            if (delta > 0) telemetry?.captureOverruns?.add(delta.toULong())
            observedRingOverruns = overruns
        }
    }

    private fun onPcmAccepted(
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ) {
        synchronized(lock) {
            core.recordChunk(
                sizeBytes,
                frameCount,
                firstFramePosition,
                captureTimeNs,
                timestampQuality,
            )
            telemetry?.capturedSamples?.add(frameCount.coerceAtLeast(0).toULong())
        }
    }

    private fun startHeartbeat() {
        if (!heartbeatRunning.compareAndSet(false, true)) return
        heartbeatThread = Thread(
            {
                while (heartbeatRunning.get()) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (!heartbeatRunning.get()) break
                    emitDiagnostics()
                }
            },
            "WarpnectDirectShellRemoteE2eHeartbeat",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun stopHeartbeat() {
        if (!heartbeatRunning.compareAndSet(true, false)) return
        val thread = heartbeatThread
        heartbeatThread = null
        thread?.interrupt()
        if (thread != null && thread != Thread.currentThread()) {
            runCatching { thread.join(HEARTBEAT_STOP_JOIN_MILLIS) }
        }
    }

    private fun emitDiagnostics() {
        val remote = control(DirectShellProbeProtocol.Command.GET_PCM_RING_DIAGNOSTICS)
        if (!remote.success) {
            onDrainError(AudioCaptureError.PrivilegedServiceDied)
            log(
                "DIRECT_SHELL_REMOTE_E2E_SERVER_DIAGNOSTICS_FAILED generation=$generationId " +
                    "detail=${safe(remote.payload)}",
            )
            return
        }
        val local = DirectShellPcmRingProbeSession.metrics(generationId)
        log(
            "DIRECT_SHELL_REMOTE_E2E_SERVER_DIAGNOSTICS generation=$generationId ${safe(remote.payload)}",
        )
        log(
            "DIRECT_SHELL_REMOTE_E2E_APP_RING_METRICS generation=$generationId ${safe(local.payload)}",
        )
    }

    private fun closeRemoteDescriptors() {
        control(DirectShellProbeProtocol.Command.PCM_RING_CLOSE)
    }

    private fun control(command: DirectShellProbeProtocol.Command): ControlResult = synchronized(controlLock) {
        try {
            DirectShellPcmRingProbeClient.connectLoopback(port, secret).use { client ->
                val response = client.call(command)
                ControlResult(
                    success = response.status == DirectShellProbeProtocol.Status.OK,
                    payload = response.payload,
                )
            }
        } catch (exception: IOException) {
            ControlResult(false, "control_${exception.javaClass.simpleName}")
        } catch (exception: DirectShellProbeProtocol.ProtocolException) {
            ControlResult(false, "protocol_${exception.javaClass.simpleName}")
        }
    }

    private fun copySecret(): ByteArray = secret.clone()

    private fun unavailable(error: AudioCaptureError) = AudioCaptureCapabilities(
        source = AudioCaptureSource.SystemAudio,
        available = false,
        privilegedBackendAvailable = false,
        lastError = error,
    )

    private fun directShellRequestError(request: AudioCaptureRequest): AudioCaptureError =
        DirectShellRemoteE2eAudioContract.validate(request, Build.VERSION.SDK_INT)

    private fun directShellFormat(): AudioCaptureFormat = DirectShellRemoteE2eAudioContract.format()

    private fun mapRingFailure(payload: String): AudioCaptureError = when {
        payload.contains("DRAIN_START") -> AudioCaptureError.NotificationChannelFailed
        payload.contains("RING_ARM") -> AudioCaptureError.SharedMemoryCreationFailed
        payload.contains("GENERATION") -> AudioCaptureError.SharedRingCorrupt
        else -> AudioCaptureError.Unknown
    }

    private fun mapControlFailure(payload: String): AudioCaptureError = when {
        payload.contains("AUDIORECORD_UNINITIALIZED") -> AudioCaptureError.AudioRecordUninitialized
        payload.contains("AUDIORECORD_CONSTRUCTION") -> AudioCaptureError.AudioRecordCreationFailed
        payload.contains("PERMISSION_DENIED") -> AudioCaptureError.PermissionDenied
        payload.contains("START_RECORDING") -> AudioCaptureError.AudioRecordStartFailed
        payload.contains("READ_FAILED") -> AudioCaptureError.AudioRecordReadFailed
        payload.contains("FD_HANDOFF") -> AudioCaptureError.PrivilegedServiceUnavailable
        payload.contains("RING_") || payload.contains("NOTIFY") || payload.contains("ACK_") ->
            AudioCaptureError.NotificationChannelFailed
        payload.startsWith("control_") || payload.startsWith("protocol_") ->
            AudioCaptureError.PrivilegedServiceUnavailable
        else -> AudioCaptureError.Unknown
    }

    private fun safe(value: String): String = value
        .replace('\n', ';')
        .replace('\r', '_')
        .replace('\u0000', '_')
        .take(MAX_LOG_PAYLOAD_CHARS)

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private data class ControlResult(
        val success: Boolean,
        val payload: String,
    )

    private companion object {
        const val TAG = "WarpnectDirectShellE2e"
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
        const val HEARTBEAT_STOP_JOIN_MILLIS = 3_500L
        const val MAX_LOG_PAYLOAD_CHARS = 2_800
    }
}
