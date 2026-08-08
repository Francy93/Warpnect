package io.warpnect.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

class ShizukuBridge {
    fun accessState(): PrivilegedAccessState = try {
        if (!Shizuku.pingBinder()) {
            PrivilegedAccessState.Unavailable
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            PrivilegedAccessState.Available
        } else {
            PrivilegedAccessState.PermissionRequired
        }
    } catch (_: RuntimeException) {
        PrivilegedAccessState.Unavailable
    }

    fun requestBinding(): PrivilegedOperationResult = when (accessState()) {
        PrivilegedAccessState.Available -> PrivilegedOperationResult.Accepted
        PrivilegedAccessState.PermissionRequired -> {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
                .fold(
                    onSuccess = { PrivilegedOperationResult.PermissionRequestIssued },
                    onFailure = {
                        PrivilegedOperationResult.Unavailable(
                            reason = "Shizuku permission could not be requested.",
                            recoveryPrompt = "Start Shizuku and retry the permission request.",
                        )
                    },
                )
        }
        PrivilegedAccessState.Unavailable,
        PrivilegedAccessState.Unknown,
        -> PrivilegedOperationResult.Unavailable(
            reason = "Shizuku binder is unavailable.",
            recoveryPrompt = "Start Shizuku or Sui before using privileged Warpnect features.",
        )
    }

    fun prepareSessionService(): PrivilegedOperationResult = PrivilegedOperationResult.NotImplemented(
        reason = "Privileged session orchestration is reserved for a later phase.",
        recoveryPrompt = "Receiver mode can run without privileged actions during Phase 0.",
    )

    fun executeShellPrivilegedOperation(operation: PrivilegedShellOperation): PrivilegedOperationResult =
        PrivilegedOperationResult.NotImplemented(
            reason = "Shell-privileged execution is not implemented in Phase 0.",
            recoveryPrompt = "A later phase will provide a clean unavailable-permission path.",
        )

    fun injectPrivilegedInput(event: PrivilegedInputEvent): PrivilegedOperationResult =
        PrivilegedOperationResult.NotImplemented(
            reason = "Privileged input injection is not implemented in Phase 0.",
            recoveryPrompt = "Input injection will require Shizuku or an equivalent privileged backend later.",
        )

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 20_020
    }
}

enum class PrivilegedAccessState {
    Unknown,
    Available,
    PermissionRequired,
    Unavailable,
}

data class PrivilegedShellOperation(
    val name: String,
    val arguments: List<String> = emptyList(),
)

data class PrivilegedInputEvent(
    val channel: PrivilegedInputChannel,
    val description: String = "",
)

enum class PrivilegedInputChannel {
    Touch,
    Keyboard,
    Mouse,
    Gamepad,
}

sealed interface PrivilegedOperationResult {
    data object Accepted : PrivilegedOperationResult

    data object PermissionRequestIssued : PrivilegedOperationResult

    data class Unavailable(
        val reason: String,
        val recoveryPrompt: String,
    ) : PrivilegedOperationResult

    data class NotImplemented(
        val reason: String,
        val recoveryPrompt: String,
    ) : PrivilegedOperationResult
}
