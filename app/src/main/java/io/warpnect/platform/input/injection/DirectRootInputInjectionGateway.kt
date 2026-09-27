package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.privileged.runtime.DirectRootRuntime

/** Finite typed operations, not arbitrary Android-object deserialization or a Root shell RPC. */
internal interface RootInputControl {
    fun capabilities(): InputInjectionCapabilities
    fun prepareInput(config: InputInjectionConfig): Int
    fun startInput(): Int
    fun key(event: AndroidKeyInjectionEvent): Int
    fun touch(event: AndroidTouchInjectionEvent): Int
    fun pointer(event: AndroidPointerInjectionEvent): Int
    fun joystick(event: AndroidJoystickInjectionEvent): Int
    fun resetInput(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int
    fun stopInput(): Int
    fun inputSnapshot(): InputInjectionSnapshot
}

/** Owns Input only. Releasing this gateway never releases somebody else's runtime generation. */
internal class DirectRootInputInjectionGateway(
    private val runtime: DirectRootRuntime,
) : PrivilegedInputInjectionGateway {
    private val generation = requireNotNull(runtime.snapshot.generation)
    private var closed = false

    @Synchronized
    private fun <T> call(unavailable: (InputInjectionError) -> T, operation: (RootInputControl) -> T): T {
        if (closed) return unavailable(InputInjectionError.Closed)
        val control = runtime.inputControl(generation) ?: return unavailable(InputInjectionError.ServiceDied)
        return try {
            operation(control)
        } catch (_: Exception) {
            runtime.inputConnectionLost()
            unavailable(InputInjectionError.ServiceDied)
        }
    }

    override suspend fun queryCapabilities(): InputInjectionCapabilities =
        call({ InputInjectionCapabilities(lastError = it) }, RootInputControl::capabilities)

    override suspend fun requestPermission() = InputInjectionPermissionResult(
        if (runtime.inputControl(generation) != null && !closed) {
            InputInjectionError.None
        } else {
            InputInjectionError.ServiceUnavailable
        },
    )

    override suspend fun prepare(config: InputInjectionConfig): InputInjectionError =
        if (config.validate() != InputInjectionError.None) {
            config.validate()
        } else {
            InputInjectionServiceResult.fromCode(code { it.prepareInput(config) }).error
        }

    override fun start(): InputInjectionError = InputInjectionServiceResult.fromCode(code { it.startInput() }).error
    override fun injectKey(event: AndroidKeyInjectionEvent): Int = code { it.key(event) }
    override fun injectTouch(event: AndroidTouchInjectionEvent): Int = code { it.touch(event) }
    override fun injectPointer(event: AndroidPointerInjectionEvent): Int = code { it.pointer(event) }
    override fun injectJoystick(event: AndroidJoystickInjectionEvent): Int = code { it.joystick(event) }
    override fun reset(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int =
        code { it.resetInput(scope, stateSlot, reason) }

    // Root always releases all tracked state; a caller cannot opt out of release safety.
    override fun stop(resetAll: Boolean): Int = code { it.stopInput() }
    override fun snapshot(): InputInjectionSnapshot = call(::unavailableInputInjectionSnapshot) { it.inputSnapshot() }

    @Synchronized
    override fun close() {
        if (closed) return
        code { it.stopInput() }
        closed = true
    }

    private fun code(operation: (RootInputControl) -> Int): Int = call({ it.code }, operation)
}
