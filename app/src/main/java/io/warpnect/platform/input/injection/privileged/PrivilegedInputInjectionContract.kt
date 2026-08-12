package io.warpnect.platform.input.injection.privileged

import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetScope

internal object PrivilegedInputInjectionContract {
    const val SERVICE_VERSION = 1
    const val MODE_ASYNC = 0
    const val MODE_WAIT_FOR_RESULT = 1
    const val RESET_THIS_SLOT = 1
    const val RESET_ALL_SLOTS = 2

    fun modeFromWire(value: Int): InputInjectionMode? = when (value) {
        MODE_ASYNC -> InputInjectionMode.AsyncLowLatency
        MODE_WAIT_FOR_RESULT -> InputInjectionMode.WaitForResultDiagnostics
        else -> null
    }

    fun resetScopeFromWire(value: Int): InputResetScope? = when (value) {
        RESET_THIS_SLOT -> InputResetScope.ThisSlot
        RESET_ALL_SLOTS -> InputResetScope.AllSlots
        else -> null
    }

    fun stateCode(state: InputInjectionState): Int = state.ordinal

    fun stateFromCode(value: Int): InputInjectionState = InputInjectionState.entries.getOrElse(value) {
        InputInjectionState.Error
    }
}
