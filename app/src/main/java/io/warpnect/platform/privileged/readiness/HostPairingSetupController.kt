package io.warpnect.platform.privileged.readiness

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HostPairingSetupPhase { OPEN, RETURNED, UNAVAILABLE, PERMISSION_DENIED }

internal data class HostPairingSetupAttempt(val generation: Long, val phase: HostPairingSetupPhase)

internal enum class HostPairingSetupLaunchResult { STARTED, UNAVAILABLE, PERMISSION_DENIED }

/** Returns only a current, explicitly requested DirectShell authorization generation. */
internal fun hostPairingSetupGeneration(state: HostReadinessUiState): Long? {
    if (!state.hostActionVisible) return null
    val generation: Long
    val runtime: PrivilegedRuntimeStateSnapshot
    val reason: PrivilegedRuntimeFailureReason
    when (val readiness = state.readiness) {
        is HostReadinessState.AuthorizationRequired -> {
            generation = readiness.generation
            runtime = readiness.runtimeState
            reason = readiness.reason
        }
        is HostReadinessState.UserActionRequired -> {
            generation = readiness.generation
            runtime = readiness.runtimeState
            reason = readiness.reason
        }
        else -> return null
    }
    return generation.takeIf {
        it == runtime.generation && reason == PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED &&
            runtime.directShell.state == PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED &&
            runtime.directShell.reason == PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED
    }
}

/** Application-owned launch guard. Retains neither Activity nor launcher across rotation. */
internal class HostPairingSetupController(private val currentReadiness: () -> HostReadinessUiState) : AutoCloseable {
    private val mutableAttempt = MutableStateFlow<HostPairingSetupAttempt?>(null)
    val attempt: StateFlow<HostPairingSetupAttempt?> = mutableAttempt.asStateFlow()
    private var lastAttemptedGeneration: Long? = null
    private var pendingGeneration: Long? = null
    private var pendingToken: String? = null
    private var closed = false

    @Synchronized
    fun request(
        expectedGeneration: Long,
        adapterAvailable: Boolean,
        launch: (String) -> HostPairingSetupLaunchResult,
    ): Boolean {
        if (closed || !adapterAvailable || pendingGeneration != null ||
            lastAttemptedGeneration == expectedGeneration ||
            hostPairingSetupGeneration(currentReadiness()) != expectedGeneration
        ) {
            return false
        }
        lastAttemptedGeneration = expectedGeneration
        pendingGeneration = expectedGeneration
        val token = UUID.randomUUID().toString()
        pendingToken = token
        mutableAttempt.value = HostPairingSetupAttempt(expectedGeneration, HostPairingSetupPhase.OPEN)
        when (launch(token)) {
            HostPairingSetupLaunchResult.STARTED -> Unit
            HostPairingSetupLaunchResult.UNAVAILABLE -> finishLaunch(
                expectedGeneration,
                HostPairingSetupPhase.UNAVAILABLE,
            )
            HostPairingSetupLaunchResult.PERMISSION_DENIED -> finishLaunch(
                expectedGeneration,
                HostPairingSetupPhase.PERMISSION_DENIED,
            )
        }
        return true
    }

    /** Activity return (including cancellation) is not evidence of pairing or Host readiness. */
    @Synchronized
    fun returned(token: String): Boolean {
        val generation = pendingGeneration ?: return false
        if (closed || pendingToken != token) return false
        pendingGeneration = null
        pendingToken = null
        if (hostPairingSetupGeneration(currentReadiness()) != generation) {
            mutableAttempt.value = null
            return false
        }
        mutableAttempt.value = HostPairingSetupAttempt(generation, HostPairingSetupPhase.RETURNED)
        return true
    }

    private fun finishLaunch(generation: Long, phase: HostPairingSetupPhase) {
        pendingGeneration = null
        pendingToken = null
        mutableAttempt.value = HostPairingSetupAttempt(generation, phase)
    }

    @Synchronized
    override fun close() {
        closed = true
        pendingGeneration = null
        pendingToken = null
        mutableAttempt.value = null
    }
}
