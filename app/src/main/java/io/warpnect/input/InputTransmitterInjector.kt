package io.warpnect.input

import io.warpnect.shizuku.ShizukuBridge

class InputTransmitterInjector(
    private val shizukuBridge: ShizukuBridge,
) {
    fun preparePrivilegedInputPath(): InputInjectionResult =
        InputInjectionResult.NotImplemented("Privileged input preparation is reserved for a later phase.")

    fun injectTouch(event: TouchInputEvent): InputInjectionResult =
        InputInjectionResult.NotImplemented("Touch injection is not implemented in Phase 0.")

    fun injectKeyboard(event: KeyboardInputEvent): InputInjectionResult =
        InputInjectionResult.NotImplemented("Keyboard injection is not implemented in Phase 0.")

    fun injectMouse(event: MouseInputEvent): InputInjectionResult =
        InputInjectionResult.NotImplemented("Mouse injection is not implemented in Phase 0.")

    fun injectGamepad(event: GamepadInputEvent): InputInjectionResult =
        InputInjectionResult.NotImplemented("Gamepad injection is not implemented in Phase 0.")

    fun privilegedAccessState() = shizukuBridge.accessState()
}

sealed interface InputInjectionResult {
    data object Accepted : InputInjectionResult

    data class NotImplemented(
        val reason: String,
    ) : InputInjectionResult
}
