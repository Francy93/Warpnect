package io.warpnect.debug.privilege

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.TextView
import io.warpnect.platform.audio.capture.privileged.PrivilegedAudioCaptureContract
import io.warpnect.platform.audio.capture.privileged.PrivilegedAudioCaptureUserService
import io.warpnect.platform.capture.privileged.PrivilegedCaptureContract
import io.warpnect.platform.capture.privileged.PrivilegedCaptureUserService
import io.warpnect.platform.input.injection.privileged.PrivilegedInputInjectionContract
import io.warpnect.platform.input.injection.privileged.PrivilegedInputInjectionUserService
import rikka.shizuku.Shizuku

/**
 * DEBUG-only bootstrap isolator. It verifies Binder publication only and never calls capture,
 * audio, or input operations in the remote service.
 */
class UserServiceBootstrapProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var nextProbeIndex = 0
    private var activeProbe: ActiveProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Warpnect UserService bootstrap diagnostics" })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            startNextProbe()
        }
    }

    override fun onDestroy() {
        activeProbe?.let { unbind(it, remove = false) }
        activeProbe = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startNextProbe() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false) ||
            runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "USER_SERVICE_BOOTSTRAP_MAIN_NOT_READY")
            finish()
            return
        }

        val descriptor = DESCRIPTORS.getOrNull(nextProbeIndex++)
        if (descriptor == null) {
            Log.i(TAG, "USER_SERVICE_BOOTSTRAP_COMPLETE")
            finish()
            return
        }

        val args = Shizuku.UserServiceArgs(ComponentName(packageName, descriptor.className))
            .daemon(false)
            .debuggable((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .processNameSuffix(descriptor.processSuffix)
            .tag(descriptor.tag)
            .version(descriptor.version)
        lateinit var connection: ServiceConnection
        val timeout = Runnable {
            val probe = activeProbe ?: return@Runnable
            if (probe.descriptor != descriptor) return@Runnable
            Log.i(TAG, "USER_SERVICE_BOOTSTRAP_RESULT service=${descriptor.name} result=TIMEOUT")
            unbind(probe, remove = false)
            activeProbe = null
            handler.postDelayed(::startNextProbe, BETWEEN_PROBES_DELAY_MS)
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val probe = activeProbe ?: return
                if (probe.descriptor != descriptor) return
                Log.i(
                    TAG,
                    "USER_SERVICE_BOOTSTRAP_RESULT service=${descriptor.name} result=CONNECTED " +
                        "main_pid=${Process.myPid()} binder=${service.javaClass.name}",
                )
                unbind(probe, remove = true)
                activeProbe = null
                handler.postDelayed(::startNextProbe, BETWEEN_PROBES_DELAY_MS)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val probe = activeProbe ?: return
                if (probe.descriptor != descriptor) return
                Log.i(TAG, "USER_SERVICE_BOOTSTRAP_RESULT service=${descriptor.name} result=DISCONNECTED")
                unbind(probe, remove = false)
                activeProbe = null
                handler.postDelayed(::startNextProbe, BETWEEN_PROBES_DELAY_MS)
            }
        }
        val probe = ActiveProbe(descriptor, args, connection, timeout)
        activeProbe = probe
        Log.i(TAG, "USER_SERVICE_BOOTSTRAP_BEGIN service=${descriptor.name}")
        try {
            Shizuku.bindUserService(args, connection)
            handler.postDelayed(timeout, USER_SERVICE_START_TIMEOUT_MS)
        } catch (_: RuntimeException) {
            Log.i(TAG, "USER_SERVICE_BOOTSTRAP_RESULT service=${descriptor.name} result=BIND_EXCEPTION")
            unbind(probe, remove = false)
            activeProbe = null
            handler.postDelayed(::startNextProbe, BETWEEN_PROBES_DELAY_MS)
        }
    }

    private fun unbind(probe: ActiveProbe, remove: Boolean) {
        handler.removeCallbacks(probe.timeout)
        runCatching { Shizuku.unbindUserService(probe.args, probe.connection, remove) }
    }

    private data class ActiveProbe(
        val descriptor: ProbeDescriptor,
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection,
        val timeout: Runnable,
    )

    private data class ProbeDescriptor(
        val name: String,
        val className: String,
        val processSuffix: String,
        val tag: String,
        val version: Int,
    )

    private companion object {
        const val TAG = "WarpnectBootstrapProbe"
        const val USER_SERVICE_START_TIMEOUT_MS = 31_000L
        const val BETWEEN_PROBES_DELAY_MS = 250L

        val DESCRIPTORS = listOf(
            ProbeDescriptor(
                name = "minimal",
                className = UserServiceBootstrapProbe::class.java.name,
                processSuffix = "bootstrap-probe",
                tag = "bootstrap-probe",
                version = 1,
            ),
            ProbeDescriptor(
                name = "capture",
                className = PrivilegedCaptureUserService::class.java.name,
                processSuffix = "capture",
                tag = "capture",
                version = PrivilegedCaptureContract.SERVICE_VERSION,
            ),
            ProbeDescriptor(
                name = "audio",
                className = PrivilegedAudioCaptureUserService::class.java.name,
                processSuffix = "audio",
                tag = "audio",
                version = PrivilegedAudioCaptureContract.SERVICE_VERSION,
            ),
            ProbeDescriptor(
                name = "input",
                className = PrivilegedInputInjectionUserService::class.java.name,
                processSuffix = "input-injection",
                tag = "input-injection",
                version = PrivilegedInputInjectionContract.SERVICE_VERSION,
            ),
        )
    }
}
