package io.warpnect.shizuku

class ShizukuBridge {
    fun accessState(): PrivilegedAccessState = PrivilegedAccessState.Unknown

    fun requestBinding(): PrivilegedOperationResult = PrivilegedOperationResult.NotImplemented(
        reason = "Shizuku binding is reserved for a later phase.",
        recoveryPrompt = "Enable Wireless Debugging or Shizuku before privileged Warpnect features are added.",
    )

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

    data class NotImplemented(
        val reason: String,
        val recoveryPrompt: String,
    ) : PrivilegedOperationResult
}
