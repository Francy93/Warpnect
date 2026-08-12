package io.warpnect.platform.input.injection

import android.content.Context
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionResult
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope

/** App-side lifecycle facade. Injection calls are synchronous and caller-serialized. */
class AndroidInputInjectionController private constructor(
    private val gatewayFactory: ((() -> Unit) -> PrivilegedInputInjectionGateway),
) : InputInjectionController {
    constructor(context: Context) : this(
        { onServiceDied -> ShizukuInputInjectionGateway(context.applicationContext, onServiceDied) },
    )

    private var config: InputInjectionConfig? = null
    private var localSnapshot = InputInjectionSnapshot()
    private val gateway: PrivilegedInputInjectionGateway by lazy { gatewayFactory(::onGatewayServiceDied) }

    override suspend fun queryCapabilities(): InputInjectionCapabilities = gateway.queryCapabilities()

    override suspend fun requestPermission(): InputInjectionPermissionResult = gateway.requestPermission()

    override suspend fun prepare(config: InputInjectionConfig): InputInjectionResult {
        if (localSnapshot.state == InputInjectionState.Closed) return result(InputInjectionServiceResult.Closed)
        val validation = config.validate()
        if (validation != InputInjectionError.None) return result(serviceResult(validation))
        localSnapshot = localSnapshot.copy(state = InputInjectionState.Preparing, lastError = InputInjectionError.None)
        val error = gateway.prepare(config)
        if (error != InputInjectionError.None) {
            localSnapshot = localSnapshot.copy(state = InputInjectionState.Error, lastError = error)
            return result(serviceResult(error))
        }
        this.config = config
        localSnapshot = gateway.snapshot().copy(
            state = InputInjectionState.Prepared,
            lastError = InputInjectionError.None,
        )
        return result(InputInjectionServiceResult.Prepared)
    }

    override fun start(): InputInjectionResult = when (localSnapshot.state) {
        InputInjectionState.Closed -> result(InputInjectionServiceResult.Closed)
        InputInjectionState.Running -> result(InputInjectionServiceResult.AlreadyRunning)
        InputInjectionState.Prepared -> {
            val error = gateway.start()
            if (error == InputInjectionError.None) {
                localSnapshot = localSnapshot.copy(state = InputInjectionState.Running, lastError = error)
                result(InputInjectionServiceResult.SubmittedAsync)
            } else {
                localSnapshot = localSnapshot.copy(state = InputInjectionState.Error, lastError = error)
                result(serviceResult(error))
            }
        }
        else -> result(InputInjectionServiceResult.NotPrepared)
    }

    override fun injectKey(event: AndroidKeyInjectionEvent): InputInjectionResult = inject {
        gateway.injectKey(event)
    }

    override fun injectTouch(event: AndroidTouchInjectionEvent): InputInjectionResult = inject {
        gateway.injectTouch(event)
    }

    override fun injectPointer(event: AndroidPointerInjectionEvent): InputInjectionResult = inject {
        gateway.injectPointer(event)
    }

    override fun injectJoystick(event: AndroidJoystickInjectionEvent): InputInjectionResult = inject {
        gateway.injectJoystick(event)
    }

    override fun resetState(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): InputInjectionResult =
        inject {
            gateway.reset(scope, stateSlot, reason)
        }

    override fun stop(): InputInjectionResult {
        if (localSnapshot.state == InputInjectionState.Closed) return result(InputInjectionServiceResult.Closed)
        if (localSnapshot.state == InputInjectionState.Stopped) return result(InputInjectionServiceResult.ResetComplete)
        if (localSnapshot.state != InputInjectionState.Running && localSnapshot.state != InputInjectionState.Prepared) {
            return result(InputInjectionServiceResult.NotRunning)
        }
        localSnapshot = localSnapshot.copy(state = InputInjectionState.Stopping)
        val code = gateway.stop(config?.resetAllOnStop == true)
        val serviceResult = InputInjectionServiceResult.fromCode(code)
        localSnapshot = when (serviceResult.error) {
            InputInjectionError.None -> gateway.snapshot().copy(state = InputInjectionState.Stopped)
            else -> localSnapshot.copy(
                state = InputInjectionState.Error,
                stateMayRemainInjected = true,
                lastError = serviceResult.error,
            )
        }
        return result(serviceResult)
    }

    override fun snapshot(): InputInjectionSnapshot {
        if (localSnapshot.state == InputInjectionState.Closed) return localSnapshot
        return gateway.snapshot().let { remote ->
            localSnapshot = remote.copy(
                state = if (remote.state == InputInjectionState.Error ||
                    remote.lastError == InputInjectionError.ServiceDied
                ) {
                    InputInjectionState.Error
                } else if (localSnapshot.state == InputInjectionState.Running) {
                    InputInjectionState.Running
                } else {
                    remote.state
                },
                stateMayRemainInjected = remote.stateMayRemainInjected ||
                    remote.lastError == InputInjectionError.ServiceDied,
            )
            localSnapshot
        }
    }

    override fun close() {
        if (localSnapshot.state == InputInjectionState.Closed) return
        if (localSnapshot.state == InputInjectionState.Running || localSnapshot.state == InputInjectionState.Prepared) {
            stop()
        }
        gateway.close()
        localSnapshot = localSnapshot.copy(state = InputInjectionState.Closed)
        config = null
    }

    private fun inject(call: () -> Int): InputInjectionResult {
        if (localSnapshot.state == InputInjectionState.Closed) return result(InputInjectionServiceResult.Closed)
        if (localSnapshot.state != InputInjectionState.Running) return result(InputInjectionServiceResult.NotRunning)
        val serviceResult = InputInjectionServiceResult.fromCode(call())
        if (serviceResult.error != InputInjectionError.None) {
            localSnapshot = localSnapshot.copy(
                state = if (serviceResult.error == InputInjectionError.ServiceDied) {
                    InputInjectionState.Error
                } else {
                    localSnapshot.state
                },
                stateMayRemainInjected = localSnapshot.stateMayRemainInjected ||
                    serviceResult.error == InputInjectionError.ServiceDied,
                lastError = serviceResult.error,
            )
        }
        return result(serviceResult)
    }

    private fun result(serviceResult: InputInjectionServiceResult): InputInjectionResult =
        InputInjectionResult(serviceResult, localSnapshot)

    private fun serviceResult(error: InputInjectionError): InputInjectionServiceResult =
        InputInjectionServiceResult.entries.firstOrNull { it.error == error }
            ?: InputInjectionServiceResult.UnknownFailure

    private fun onGatewayServiceDied() {
        if (localSnapshot.state != InputInjectionState.Closed) {
            localSnapshot = localSnapshot.copy(
                state = InputInjectionState.Error,
                stateMayRemainInjected = true,
                lastError = InputInjectionError.ServiceDied,
            )
        }
    }

    internal companion object {
        fun forTesting(gateway: PrivilegedInputInjectionGateway): AndroidInputInjectionController =
            AndroidInputInjectionController { gateway }
    }
}
