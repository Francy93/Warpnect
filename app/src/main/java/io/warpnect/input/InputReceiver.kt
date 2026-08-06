package io.warpnect.input

class InputReceiver {
    fun onTouch(event: TouchInputEvent): InputReceiveResult =
        InputReceiveResult.NotImplemented("Touch event transport is reserved for a later phase.")

    fun onKeyboard(event: KeyboardInputEvent): InputReceiveResult =
        InputReceiveResult.NotImplemented("Keyboard event transport is reserved for a later phase.")

    fun onMouse(event: MouseInputEvent): InputReceiveResult =
        InputReceiveResult.NotImplemented("Mouse event transport is reserved for a later phase.")

    fun onGamepad(event: GamepadInputEvent): InputReceiveResult =
        InputReceiveResult.NotImplemented("Gamepad event transport is reserved for a later phase.")
}

data class TouchInputEvent(
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val action: TouchAction,
)

enum class TouchAction {
    Down,
    Move,
    Up,
    Cancel,
}

data class KeyboardInputEvent(
    val keyCode: Int,
    val pressed: Boolean,
)

data class MouseInputEvent(
    val x: Float,
    val y: Float,
    val buttons: Int,
)

data class GamepadInputEvent(
    val controlCode: Int,
    val value: Float,
)

sealed interface InputReceiveResult {
    data object Accepted : InputReceiveResult

    data class NotImplemented(
        val reason: String,
    ) : InputReceiveResult
}

