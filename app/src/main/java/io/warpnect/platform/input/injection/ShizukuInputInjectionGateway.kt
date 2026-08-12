package io.warpnect.platform.input.injection

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.input.injection.privileged.IPrivilegedInputInjectionService
import io.warpnect.platform.input.injection.privileged.PrivilegedInputInjectionContract
import io.warpnect.platform.input.injection.privileged.PrivilegedInputInjectionUserService
import io.warpnect.platform.input.injection.privileged.toInputInjectionCapabilities
import io.warpnect.platform.input.injection.privileged.toInputInjectionSnapshot
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

internal class ShizukuInputInjectionGateway(
    private val context: Context,
    private val onServiceDied: () -> Unit,
) : PrivilegedInputInjectionGateway {
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedInputInjectionUserService::class.java.name),
    )
        .daemon(false)
        .debuggable((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        .processNameSuffix("input-injection")
        .tag("input-injection")
        .version(PrivilegedInputInjectionContract.SERVICE_VERSION)
    private var remote: IPrivilegedInputInjectionService? = null
    private var boundConnection: ServiceConnection? = null
    private var lastSnapshot = InputInjectionSnapshot()
    private var lastBindError = InputInjectionError.UserServiceBindFailed

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        boundConnection = null
        lastSnapshot = lastSnapshot.copy(
            state = InputInjectionState.Error,
            stateMayRemainInjected = true,
            lastError = InputInjectionError.ServiceDied,
        )
        onServiceDied()
    }

    init {
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override suspend fun queryCapabilities(): InputInjectionCapabilities {
        val readiness = readiness()
        if (readiness != InputInjectionError.None) return unavailableCapabilities(readiness)
        val service = ensureService() ?: return unavailableCapabilities(lastBindError)
        return try {
            service.capabilities.toInputInjectionCapabilities()
        } catch (_: RemoteException) {
            unavailableCapabilities(onRemoteFailure())
        } catch (_: RuntimeException) {
            unavailableCapabilities(InputInjectionError.ServiceUnavailable)
        }
    }

    override suspend fun requestPermission(): InputInjectionPermissionResult = when (
        readiness(
            includePermission = false,
        )
    ) {
        InputInjectionError.None -> if (hasPermission()) {
            InputInjectionPermissionResult(InputInjectionError.None)
        } else {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
                .fold(
                    onSuccess = { InputInjectionPermissionResult(InputInjectionError.ShizukuPermissionRequired, true) },
                    onFailure = { InputInjectionPermissionResult(InputInjectionError.ShizukuUnavailable) },
                )
        }
        else -> InputInjectionPermissionResult(InputInjectionError.ShizukuUnavailable)
    }

    override suspend fun prepare(config: InputInjectionConfig): InputInjectionError {
        val ready = readiness()
        if (ready != InputInjectionError.None) return ready
        val service = ensureService() ?: return lastBindError
        return try {
            if (service.serviceVersion != PrivilegedInputInjectionContract.SERVICE_VERSION) {
                return InputInjectionError.ServiceUnavailable
            }
            val code = service.prepare(
                config.targetUid,
                config.injectionMode.toWireMode(),
                config.maxTrackedInjectionSlots,
                config.maxPressedKeysPerSlot,
            )
            lastSnapshot = service.snapshot.toInputInjectionSnapshot()
            resultError(code)
        } catch (_: RemoteException) {
            onRemoteFailure()
        } catch (_: RuntimeException) {
            InputInjectionError.RemoteFailure
        }
    }

    override fun start(): InputInjectionError = invoke { service -> service.startInjection() }

    override fun injectKey(event: AndroidKeyInjectionEvent): Int = invokeCode { service ->
        service.injectKey(
            event.sourceEventTimeUs, event.stateSlot, event.action, event.keyCode, event.repeatCount,
            event.metaState, event.scanCode, event.flags, event.source, event.androidDeviceId, event.displayId,
        )
    }

    override fun injectTouch(event: AndroidTouchInjectionEvent): Int = invokeCode { service ->
        val count = event.pointers.size
        service.injectTouch(
            event.sourceEventTimeUs, event.stateSlot, event.actionMasked, event.actionIndex, count,
            IntArray(count) { event.pointers[it].pointerId }, IntArray(count) { event.pointers[it].toolType },
            FloatArray(count) { event.pointers[it].xPx }, FloatArray(count) { event.pointers[it].yPx },
            FloatArray(count) { event.pointers[it].pressure }, FloatArray(count) { event.pointers[it].size },
            event.metaState, event.buttonState, event.source, event.androidDeviceId, event.displayId,
        )
    }

    override fun injectPointer(event: AndroidPointerInjectionEvent): Int = invokeCode { service ->
        service.injectPointer(
            event.sourceEventTimeUs, event.stateSlot, event.action, event.actionButton, event.xPx, event.yPx,
            event.relativeXPx, event.relativeYPx, event.horizontalScroll, event.verticalScroll, event.pressure,
            event.size, event.metaState, event.buttonState, event.source, event.androidDeviceId, event.displayId,
        )
    }

    override fun injectJoystick(event: AndroidJoystickInjectionEvent): Int = invokeCode { service ->
        service.injectJoystick(
            event.sourceEventTimeUs, event.stateSlot, event.leftX, event.leftY, event.rightX, event.rightY,
            event.leftTrigger, event.rightTrigger, event.hatX, event.hatY, event.metaState, event.source,
            event.androidDeviceId, event.displayId,
        )
    }

    override fun reset(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int = invokeCode { service ->
        service.resetState(scope.toWireScope(), stateSlot, reason.ordinal + 1)
    }

    override fun stop(resetAll: Boolean): Int = invokeCode { service -> service.stopInjection(resetAll) }

    override fun snapshot(): InputInjectionSnapshot {
        val service = remote ?: return lastSnapshot
        return try {
            service.snapshot.toInputInjectionSnapshot().also { lastSnapshot = it }
        } catch (_: RemoteException) {
            lastSnapshot = unavailableInputInjectionSnapshot(onRemoteFailure())
            lastSnapshot
        } catch (_: RuntimeException) {
            lastSnapshot = unavailableInputInjectionSnapshot(InputInjectionError.RemoteFailure)
            lastSnapshot
        }
    }

    override fun close() {
        runCatching { boundConnection?.let { Shizuku.unbindUserService(args, it, true) } }
        Shizuku.removeBinderDeadListener(binderDeadListener)
        remote = null
        boundConnection = null
    }

    private fun invoke(call: (IPrivilegedInputInjectionService) -> Int): InputInjectionError =
        resultError(invokeCode(call))

    private fun invokeCode(call: (IPrivilegedInputInjectionService) -> Int): Int {
        val service = remote ?: return InputInjectionError.ServiceUnavailable.code
        return try {
            call(service)
        } catch (_: RemoteException) {
            onRemoteFailure().code
        } catch (_: RuntimeException) {
            InputInjectionError.RemoteFailure.code
        }
    }

    private suspend fun ensureService(): IPrivilegedInputInjectionService? {
        remote?.let { return it }
        val readiness = readiness()
        if (readiness != InputInjectionError.None) {
            lastBindError = readiness
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val injectionService = IPrivilegedInputInjectionService.Stub.asInterface(service)
                    remote = injectionService
                    boundConnection = this
                    lastBindError = InputInjectionError.None
                    if (continuation.isActive) continuation.resume(injectionService)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    remote = null
                    boundConnection = null
                    onServiceDied()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { runCatching { Shizuku.unbindUserService(args, connection, false) } }
            try {
                Shizuku.bindUserService(args, connection)
            } catch (_: RuntimeException) {
                lastBindError = InputInjectionError.UserServiceBindFailed
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun readiness(includePermission: Boolean = true): InputInjectionError = try {
        if (!Shizuku.pingBinder()) {
            InputInjectionError.ShizukuUnavailable
        } else if (includePermission && !hasPermission()) {
            InputInjectionError.ShizukuPermissionRequired
        } else {
            InputInjectionError.None
        }
    } catch (_: RuntimeException) {
        InputInjectionError.ServiceUnavailable
    }

    private fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false
    }

    private fun onRemoteFailure(): InputInjectionError {
        remote = null
        lastSnapshot = lastSnapshot.copy(
            state = InputInjectionState.Error,
            stateMayRemainInjected = true,
            lastError = InputInjectionError.ServiceDied,
        )
        onServiceDied()
        return InputInjectionError.ServiceDied
    }

    private fun unavailableCapabilities(error: InputInjectionError): InputInjectionCapabilities =
        InputInjectionCapabilities(
            serviceAvailable = false,
            backend = InputInjectionBackend.None,
            lastError = error,
        )

    private fun resultError(code: Int): InputInjectionError =
        io.warpnect.input.injection.InputInjectionServiceResult.fromCode(code).error

    private fun io.warpnect.input.injection.InputInjectionMode.toWireMode(): Int = when (this) {
        io.warpnect.input.injection.InputInjectionMode.AsyncLowLatency ->
            PrivilegedInputInjectionContract.MODE_ASYNC
        io.warpnect.input.injection.InputInjectionMode.WaitForResultDiagnostics ->
            PrivilegedInputInjectionContract.MODE_WAIT_FOR_RESULT
    }

    private fun InputResetScope.toWireScope(): Int = when (this) {
        InputResetScope.ThisSlot -> PrivilegedInputInjectionContract.RESET_THIS_SLOT
        InputResetScope.AllSlots -> PrivilegedInputInjectionContract.RESET_ALL_SLOTS
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 20_024
    }
}
