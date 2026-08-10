package io.warpnect.platform.audio.capture

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.platform.audio.capture.privileged.IPrivilegedAudioCaptureService
import io.warpnect.platform.audio.capture.privileged.PrivilegedAudioCaptureContract
import io.warpnect.platform.audio.capture.privileged.PrivilegedAudioCaptureUserService
import io.warpnect.platform.audio.capture.privileged.PrivilegedSystemAudioSetup
import io.warpnect.platform.audio.capture.privileged.privilegedSystemAudioSetupFromBundle
import io.warpnect.platform.audio.capture.privileged.toAudioCaptureCapabilities
import io.warpnect.platform.audio.capture.privileged.toAudioCaptureSnapshot
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

internal class ShizukuAudioCaptureGateway(
    private val context: Context,
    private val onServiceDied: () -> Unit,
) : PrivilegedAudioCaptureGateway {
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedAudioCaptureUserService::class.java.name),
    )
        .daemon(false)
        .debuggable((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        .processNameSuffix("audio")
        .tag("audio")
        .version(PrivilegedAudioCaptureContract.SERVICE_VERSION)

    private var remote: IPrivilegedAudioCaptureService? = null
    private var boundConnection: ServiceConnection? = null
    private var lastSnapshot = AudioCaptureSnapshot(source = AudioCaptureSource.SystemAudio)

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        boundConnection = null
        lastSnapshot = lastSnapshot.copy(lastError = AudioCaptureError.ShizukuBinderUnavailable)
        onServiceDied()
    }

    init {
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override suspend fun querySystemAudioCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
        val readiness = shizukuReadiness()
        if (readiness != AudioCaptureError.None) {
            return unavailableCapabilities(readiness)
        }
        val service = ensureService() ?: return unavailableCapabilities(
            AudioCaptureError.PrivilegedServiceUnavailable,
        )
        return try {
            service.querySystemAudioCapabilities().toAudioCaptureCapabilities()
        } catch (_: RemoteException) {
            remote = null
            unavailableCapabilities(AudioCaptureError.PrivilegedServiceDied)
        } catch (_: RuntimeException) {
            unavailableCapabilities(AudioCaptureError.PrivilegedServiceUnavailable)
        }
    }

    override suspend fun prepareSystemAudioCapture(request: AudioCaptureRequest): PrivilegedSystemAudioSetup {
        val readiness = shizukuReadiness()
        if (readiness != AudioCaptureError.None) {
            return failedSetup(readiness)
        }
        val service = ensureService() ?: return failedSetup(AudioCaptureError.PrivilegedServiceUnavailable)
        return try {
            val chunkFrames = io.warpnect.audio.capture.AudioChunkPlanner.targetFramesPerChunk(
                request.preferredSampleRateHz ?: AudioCaptureRequest.DEFAULT_SAMPLE_RATE_HZ,
                request.targetChunkDurationUs,
            )
            val bundle = service.prepareSystemAudioCapture(
                request.preferredSampleRateHz ?: 0,
                request.channelCount ?: 0,
                chunkFrames,
                request.targetChunkDurationUs,
                request.sharedRingSlotCount,
                request.targetUid ?: -1,
            )
            lastSnapshot = service.getSystemAudioState().toAudioCaptureSnapshot()
            privilegedSystemAudioSetupFromBundle(bundle)
        } catch (_: RemoteException) {
            remote = null
            failedSetup(AudioCaptureError.PrivilegedServiceDied)
        } catch (_: RuntimeException) {
            failedSetup(AudioCaptureError.PrivilegedServiceUnavailable)
        }
    }

    override suspend fun startSystemAudioCapture(): AudioCaptureError {
        val service = remote ?: return AudioCaptureError.PrivilegedServiceUnavailable
        return try {
            val error = AudioCaptureError.fromCode(service.startSystemAudioCapture())
            lastSnapshot = service.getSystemAudioState().toAudioCaptureSnapshot()
            error
        } catch (_: RemoteException) {
            remote = null
            AudioCaptureError.PrivilegedServiceDied
        } catch (_: RuntimeException) {
            AudioCaptureError.PrivilegedServiceUnavailable
        }
    }

    override suspend fun stopSystemAudioCapture(): AudioCaptureError {
        val service = remote ?: return AudioCaptureError.None
        return try {
            val error = AudioCaptureError.fromCode(service.stopSystemAudioCapture())
            lastSnapshot = service.getSystemAudioState().toAudioCaptureSnapshot()
            error
        } catch (_: RemoteException) {
            remote = null
            AudioCaptureError.PrivilegedServiceDied
        } catch (_: RuntimeException) {
            AudioCaptureError.PrivilegedServiceUnavailable
        }
    }

    override fun snapshot(): AudioCaptureSnapshot = lastSnapshot

    override fun close() {
        runCatching {
            boundConnection?.let {
                Shizuku.unbindUserService(args, it, true)
            }
        }
        Shizuku.removeBinderDeadListener(binderDeadListener)
        remote = null
        boundConnection = null
    }

    private suspend fun ensureService(): IPrivilegedAudioCaptureService? {
        remote?.let { return it }
        if (shizukuReadiness() != AudioCaptureError.None) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val audioService = IPrivilegedAudioCaptureService.Stub.asInterface(service)
                    remote = audioService
                    boundConnection = this
                    if (continuation.isActive) {
                        continuation.resume(audioService)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    remote = null
                    boundConnection = null
                    onServiceDied()
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching {
                    Shizuku.unbindUserService(args, connection, false)
                }
            }
            try {
                Shizuku.bindUserService(args, connection)
            } catch (_: RuntimeException) {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun shizukuReadiness(): AudioCaptureError {
        val binderState = shizukuBinderState()
        if (binderState != AudioCaptureError.None) {
            return binderState
        }
        return if (hasShizukuPermission()) {
            AudioCaptureError.None
        } else {
            AudioCaptureError.ShizukuPermissionRequired
        }
    }

    private fun shizukuBinderState(): AudioCaptureError = try {
        if (Shizuku.pingBinder()) {
            AudioCaptureError.None
        } else {
            AudioCaptureError.ShizukuUnavailable
        }
    } catch (_: RuntimeException) {
        AudioCaptureError.ShizukuUnavailable
    }

    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false
    }

    private fun unavailableCapabilities(error: AudioCaptureError): AudioCaptureCapabilities = AudioCaptureCapabilities(
        source = AudioCaptureSource.SystemAudio,
        available = false,
        privilegedBackendAvailable = false,
        lastError = error,
    )

    private fun failedSetup(error: AudioCaptureError): PrivilegedSystemAudioSetup = PrivilegedSystemAudioSetup(
        error = error,
        format = null,
        actualBufferSizeFrames = 0,
        ringCapacity = 0,
        sharedMemory = null,
        notifyReadFd = null,
        ackWriteFd = null,
    )
}
