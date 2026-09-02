package io.warpnect.platform.video.encoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun interface ExactVideoEncoderProbeServiceCaller {
    fun probe(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult
}

/** Converts a bounded disposable-process call into the existing exact-probe contract. */
internal class ServiceBackedExactVideoEncoderCapabilityProbe(
    private val caller: ExactVideoEncoderProbeServiceCaller,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : ExactVideoEncoderCapabilityProbe {
    override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision {
        val result = if (isMainThread()) {
            ExactVideoEncoderCapabilityProbeResult.MainThreadRejected
        } else {
            caller.probe(key)
        }
        return CbrCapabilityDecision(
            supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
            source = CbrCapabilityDecisionSource.ActiveProbe,
            probeResult = result,
        )
    }
}

/**
 * Calls an application-private Service declared in a normal secondary app process. The service has
 * the same application UID as Warpnect; it is intentionally neither Shizuku nor an isolated UID.
 */
internal class AndroidCodecProbeServiceCaller(
    context: Context,
    private val bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
) : ExactVideoEncoderProbeServiceCaller {
    private val appContext = context.applicationContext

    override fun probe(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult {
        val connection = ProbeServiceConnection()
        val intent = Intent(appContext, ExactVideoEncoderProbeService::class.java)
        val bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return ExactVideoEncoderCapabilityProbeResult.ProbeServiceUnavailable

        return try {
            if (!connection.await(bindTimeoutMs)) {
                return ExactVideoEncoderCapabilityProbeResult.ProbeTimedOut
            }
            val service = connection.service ?: return connection.failureResult()
            invokeBounded(service, key)
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun invokeBounded(
        service: IExactVideoEncoderProbeService,
        key: ExactVideoEncoderCapabilityKey,
    ): ExactVideoEncoderCapabilityProbeResult {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<ExactVideoEncoderCapabilityProbeResult> {
                ExactVideoEncoderCapabilityProbeResult.fromCode(
                    service.probe(
                        key.codecName,
                        key.mimeType,
                        key.width,
                        key.height,
                        key.frameRate,
                        key.bitrateBps,
                        key.bitrateMode,
                        key.iFrameIntervalBits,
                    ),
                )
            }
            try {
                future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                ExactVideoEncoderCapabilityProbeResult.ProbeTimedOut
            }
        } catch (_: TimeoutException) {
            ExactVideoEncoderCapabilityProbeResult.ProbeTimedOut
        } catch (_: DeadObjectException) {
            ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied
        } catch (_: RemoteException) {
            ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied
        } catch (error: ExecutionException) {
            when (error.cause) {
                is DeadObjectException -> ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied
                is RemoteException -> ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied
                else -> ExactVideoEncoderCapabilityProbeResult.ProbeServiceUnavailable
            }
        } catch (_: Exception) {
            ExactVideoEncoderCapabilityProbeResult.ProbeServiceUnavailable
        } finally {
            executor.shutdownNow()
        }
    }

    private class ProbeServiceConnection : ServiceConnection {
        private val connected = CountDownLatch(1)

        @Volatile var service: IExactVideoEncoderProbeService? = null

        @Volatile private var processDied = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IExactVideoEncoderProbeService.Stub.asInterface(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            processDied = true
            service = null
            connected.countDown()
        }

        override fun onBindingDied(name: ComponentName) {
            processDied = true
            service = null
            connected.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            connected.countDown()
        }

        fun await(timeoutMs: Long): Boolean = connected.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun failureResult(): ExactVideoEncoderCapabilityProbeResult = if (processDied) {
            ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied
        } else {
            ExactVideoEncoderCapabilityProbeResult.ProbeServiceUnavailable
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 3_000L
        const val CALL_TIMEOUT_MS = 5_000L
    }
}
