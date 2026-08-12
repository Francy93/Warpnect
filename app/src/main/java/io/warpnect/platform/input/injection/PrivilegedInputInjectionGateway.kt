package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope

internal interface PrivilegedInputInjectionGateway : AutoCloseable {
    suspend fun queryCapabilities(): InputInjectionCapabilities

    suspend fun requestPermission(): InputInjectionPermissionResult

    suspend fun prepare(config: InputInjectionConfig): InputInjectionError

    fun start(): InputInjectionError

    fun injectKey(event: AndroidKeyInjectionEvent): Int

    fun injectTouch(event: AndroidTouchInjectionEvent): Int

    fun injectPointer(event: AndroidPointerInjectionEvent): Int

    fun injectJoystick(event: AndroidJoystickInjectionEvent): Int

    fun reset(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int

    fun stop(resetAll: Boolean): Int

    fun snapshot(): InputInjectionSnapshot

    override fun close()
}

internal fun unavailableInputInjectionSnapshot(error: InputInjectionError): InputInjectionSnapshot =
    InputInjectionSnapshot(
        state = if (error == InputInjectionError.Closed) InputInjectionState.Closed else InputInjectionState.Error,
        lastError = error,
        stateMayRemainInjected = error == InputInjectionError.ServiceDied,
    )
