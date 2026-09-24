package io.warpnect.platform.privileged.readiness

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.warpnect.platform.privileged.planning.AndroidHostPrivilegedCapabilityAdmission
import io.warpnect.platform.privileged.planning.AndroidHostPrivilegedCapabilityAdmissionResult
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanResult
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFailure
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeRegistry
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import io.warpnect.shizuku.PrivilegedAccessState
import io.warpnect.shizuku.ShizukuBridge

/** Passive inputs used to classify DirectShell without launching an ADB connection or shell server. */
internal data class DirectShellRuntimeEnvironment(
    val apiLevel: Int,
    val wirelessDebuggingExplicitlyDisabled: Boolean?,
)

/** Converts current runtime observations into the RFC-006J volatile-state model. */
internal object PrivilegedRuntimeStateMapper {
    fun snapshot(
        generation: Long,
        observedAtMonotonicMs: Long,
        directShellEnvironment: DirectShellRuntimeEnvironment,
        runtime: PrivilegedRuntimeSnapshot,
        shizukuAccess: PrivilegedAccessState,
    ): PrivilegedRuntimeStateSnapshot = PrivilegedRuntimeStateSnapshot(
        generation = generation,
        observedAtMonotonicMs = observedAtMonotonicMs,
        directRoot = PrivilegedRuntimeAvailability(
            kind = PrivilegedRuntimeKind.DIRECT_ROOT,
            state = PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            reason = null,
            detail = "direct_root_not_implemented",
        ),
        directShell = directShellAvailability(directShellEnvironment, runtime),
        shizuku = shizukuAvailability(shizukuAccess),
    )

    private fun directShellAvailability(
        environment: DirectShellRuntimeEnvironment,
        runtime: PrivilegedRuntimeSnapshot,
    ): PrivilegedRuntimeAvailability {
        if (runtime.kind != PrivilegedRuntimeKind.DIRECT_SHELL) {
            return availability(
                PrivilegedRuntimeAvailabilityState.UNKNOWN,
                null,
                "directshell_runtime_not_registered",
            )
        }
        if (environment.apiLevel < MIN_DIRECT_SHELL_API) {
            return availability(
                PrivilegedRuntimeAvailabilityState.UNSUPPORTED,
                PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
                "wireless_debugging_requires_api_$MIN_DIRECT_SHELL_API",
            )
        }
        if (environment.wirelessDebuggingExplicitlyDisabled == true) {
            return availability(
                PrivilegedRuntimeAvailabilityState.DISABLED_RECOVERABLE,
                PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
                "wireless_debugging_explicitly_disabled",
            )
        }
        return when (runtime.state) {
            PrivilegedRuntimeState.READY -> availability(
                PrivilegedRuntimeAvailabilityState.READY,
                null,
                runtime.detail,
            )

            PrivilegedRuntimeState.ACQUIRING -> availability(
                PrivilegedRuntimeAvailabilityState.RECOVERY_IN_PROGRESS,
                null,
                runtime.detail,
            )

            PrivilegedRuntimeState.UNAVAILABLE -> unavailableAvailability(runtime.failure, runtime.detail)
            PrivilegedRuntimeState.IDLE,
            PrivilegedRuntimeState.RELEASED,
            -> availability(
                PrivilegedRuntimeAvailabilityState.UNKNOWN,
                null,
                runtime.detail.ifBlank { "directshell_not_acquired" },
            )
        }
    }

    private fun unavailableAvailability(
        failure: PrivilegedRuntimeFailure,
        detail: String,
    ): PrivilegedRuntimeAvailability = when (failure) {
        PrivilegedRuntimeFailure.DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED -> availability(
            PrivilegedRuntimeAvailabilityState.DISABLED_RECOVERABLE,
            PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_NOT_PAIRED -> availability(
            PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
            PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_AUTHENTICATION_FAILED -> availability(
            PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
            PrivilegedRuntimeFailureReason.ADB_AUTH_FAILED,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_CONNECT_FAILED -> availability(
            PrivilegedRuntimeAvailabilityState.TEMPORARILY_UNAVAILABLE,
            PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_UNSUPPORTED_PLATFORM -> availability(
            PrivilegedRuntimeAvailabilityState.UNSUPPORTED,
            PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_ARTIFACT_INVALID,
        PrivilegedRuntimeFailure.DIRECTSHELL_LAUNCH_FAILED,
        PrivilegedRuntimeFailure.DIRECTSHELL_IDENTITY_INVALID,
        PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED,
        -> availability(
            PrivilegedRuntimeAvailabilityState.ENABLED_BUT_UNUSABLE,
            PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_RUNTIME_RELEASED -> availability(
            PrivilegedRuntimeAvailabilityState.TEMPORARILY_UNAVAILABLE,
            PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            detail,
        )

        PrivilegedRuntimeFailure.DIRECTSHELL_NOT_ARMED,
        PrivilegedRuntimeFailure.ROOT_NOT_IMPLEMENTED,
        PrivilegedRuntimeFailure.SHIZUKU_LEGACY_FALLBACK,
        PrivilegedRuntimeFailure.NONE,
        -> availability(
            PrivilegedRuntimeAvailabilityState.UNKNOWN,
            null,
            detail.ifBlank { "directshell_unavailable_without_specific_reason" },
        )
    }

    private fun shizukuAvailability(access: PrivilegedAccessState): PrivilegedRuntimeAvailability = when (access) {
        PrivilegedAccessState.Available -> availability(
            PrivilegedRuntimeAvailabilityState.READY,
            null,
            "shizuku_binder_ready",
            kind = PrivilegedRuntimeKind.SHIZUKU,
        )

        PrivilegedAccessState.PermissionRequired -> availability(
            PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
            PrivilegedRuntimeFailureReason.SHIZUKU_AUTHORIZATION_REQUIRED,
            "shizuku_permission_required",
            kind = PrivilegedRuntimeKind.SHIZUKU,
        )

        PrivilegedAccessState.Unavailable -> availability(
            PrivilegedRuntimeAvailabilityState.TEMPORARILY_UNAVAILABLE,
            PrivilegedRuntimeFailureReason.SHIZUKU_SERVER_STOPPED,
            "shizuku_binder_unavailable",
            kind = PrivilegedRuntimeKind.SHIZUKU,
        )

        PrivilegedAccessState.Unknown -> availability(
            PrivilegedRuntimeAvailabilityState.UNKNOWN,
            null,
            "shizuku_state_unknown",
            kind = PrivilegedRuntimeKind.SHIZUKU,
        )
    }

    private fun availability(
        state: PrivilegedRuntimeAvailabilityState,
        reason: PrivilegedRuntimeFailureReason?,
        detail: String,
        kind: PrivilegedRuntimeKind = PrivilegedRuntimeKind.DIRECT_SHELL,
    ): PrivilegedRuntimeAvailability = PrivilegedRuntimeAvailability(
        kind = kind,
        state = state,
        reason = reason,
        detail = detail.replace('\n', ' ').replace('\r', ' ').take(PrivilegedRuntimeAvailability.MAX_DETAIL_LENGTH),
    )

    private const val MIN_DIRECT_SHELL_API = 30
}

/** Android adapter for the first RFC-006J slice. It never launches a provider during observation. */
internal class AndroidHostReadinessWork(
    context: Context,
    private val admission: AndroidHostPrivilegedCapabilityAdmission,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val runtimeSnapshot: () -> PrivilegedRuntimeSnapshot = PrivilegedRuntimeRegistry::snapshot,
    private val acquireRuntime: () -> PrivilegedRuntimeSnapshot = PrivilegedRuntimeRegistry::acquireForHost,
    private val shizukuAccess: () -> PrivilegedAccessState = { ShizukuBridge().accessState() },
    private val directShellEnvironment: () -> DirectShellRuntimeEnvironment = {
        DirectShellRuntimeEnvironment(
            apiLevel = Build.VERSION.SDK_INT,
            wirelessDebuggingExplicitlyDisabled = wirelessDebuggingExplicitlyDisabled(context.applicationContext),
        )
    },
) : HostReadinessWork {
    override fun observeVolatileRuntime(generation: Long): PrivilegedRuntimeStateSnapshot = observe(
        generation = generation,
        runtime = runtimeSnapshot(),
    )

    override fun prepareForHost(generation: Long, runtimeState: PrivilegedRuntimeStateSnapshot): HostReadinessState {
        val acquired = acquireRuntime()
        val currentRuntimeState = observe(generation, acquired)
        if (!acquired.ready) return unavailableRuntimeState(generation, currentRuntimeState, acquired)
        return try {
            when (val admissionResult = admission.prepare(acquired)) {
                is AndroidHostPrivilegedCapabilityAdmissionResult.Admitted -> HostReadinessState.Ready(
                    PreparedPrivilegedCapabilityPlan(
                        generation = generation,
                        runtimeState = currentRuntimeState,
                        acquiredRuntime = acquired,
                        matrix = admissionResult.matrix,
                        plan = admissionResult.plan,
                        evidenceSource = HostReadinessEvidenceSource.CURRENT_HOST_ADMISSION,
                        commitState = HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION,
                        preparedAtMonotonicMs = elapsedRealtimeMs(),
                    ),
                )

                is AndroidHostPrivilegedCapabilityAdmissionResult.Unavailable -> unavailablePlanState(
                    generation,
                    currentRuntimeState,
                    admissionResult.incompatible,
                )
            }
        } catch (_: RuntimeException) {
            admission.release()
            if (acquired.kind == PrivilegedRuntimeKind.DIRECT_SHELL) {
                PrivilegedRuntimeRegistry.releaseForHost()
            }
            HostReadinessState.Failed(
                generation,
                currentRuntimeState,
                PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            )
        }
    }

    override fun releasePreparedResources() {
        admission.release()
    }

    private fun observe(generation: Long, runtime: PrivilegedRuntimeSnapshot): PrivilegedRuntimeStateSnapshot =
        PrivilegedRuntimeStateMapper.snapshot(
            generation = generation,
            observedAtMonotonicMs = elapsedRealtimeMs(),
            directShellEnvironment = directShellEnvironment(),
            runtime = runtime,
            shizukuAccess = shizukuAccess(),
        )

    private fun unavailableRuntimeState(
        generation: Long,
        runtimeState: PrivilegedRuntimeStateSnapshot,
        runtime: PrivilegedRuntimeSnapshot,
    ): HostReadinessState {
        val availability = when (runtime.kind) {
            PrivilegedRuntimeKind.DIRECT_SHELL -> runtimeState.directShell
            PrivilegedRuntimeKind.SHIZUKU -> runtimeState.shizuku
            PrivilegedRuntimeKind.DIRECT_ROOT -> runtimeState.directRoot
            PrivilegedRuntimeKind.NONE -> runtimeState.directShell
        }
        return when (availability.state) {
            PrivilegedRuntimeAvailabilityState.DISABLED_RECOVERABLE -> HostReadinessState.UserActionRequired(
                generation,
                runtimeState,
                availability.reason ?: PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
            )

            PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED -> HostReadinessState.AuthorizationRequired(
                generation,
                runtimeState,
                availability.reason ?: PrivilegedRuntimeFailureReason.ADB_AUTH_FAILED,
            )

            PrivilegedRuntimeAvailabilityState.UNSUPPORTED,
            PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            -> HostReadinessState.Unsupported(
                generation,
                runtimeState,
                availability.reason ?: PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
            )

            else -> HostReadinessState.Failed(
                generation,
                runtimeState,
                availability.reason ?: runtime.failure.toReadinessReason(),
            )
        }
    }

    private fun unavailablePlanState(
        generation: Long,
        runtimeState: PrivilegedRuntimeStateSnapshot,
        incompatible: PrivilegedCapabilityPlanResult.HostIncompatible,
    ): HostReadinessState {
        val states = incompatible.diagnostics.map { it.state }.toSet()
        return when {
            CapabilityQualificationState.AUTHORIZATION_REQUIRED in states -> HostReadinessState.AuthorizationRequired(
                generation,
                runtimeState,
                runtimeState.shizuku.reason ?: PrivilegedRuntimeFailureReason.SHIZUKU_AUTHORIZATION_REQUIRED,
            )

            states.all { it == CapabilityQualificationState.NOT_IMPLEMENTED } -> HostReadinessState.Unsupported(
                generation,
                runtimeState,
                PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
            )

            else -> HostReadinessState.Failed(
                generation,
                runtimeState,
                PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            )
        }
    }

    private fun PrivilegedRuntimeFailure.toReadinessReason(): PrivilegedRuntimeFailureReason = when (this) {
        PrivilegedRuntimeFailure.DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED ->
            PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED
        PrivilegedRuntimeFailure.DIRECTSHELL_NOT_PAIRED -> PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED
        PrivilegedRuntimeFailure.DIRECTSHELL_CONNECT_FAILED ->
            PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE
        PrivilegedRuntimeFailure.DIRECTSHELL_AUTHENTICATION_FAILED -> PrivilegedRuntimeFailureReason.ADB_AUTH_FAILED
        PrivilegedRuntimeFailure.DIRECTSHELL_UNSUPPORTED_PLATFORM -> PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED
        PrivilegedRuntimeFailure.NONE,
        PrivilegedRuntimeFailure.ROOT_NOT_IMPLEMENTED,
        PrivilegedRuntimeFailure.DIRECTSHELL_NOT_ARMED,
        PrivilegedRuntimeFailure.DIRECTSHELL_ARTIFACT_INVALID,
        PrivilegedRuntimeFailure.DIRECTSHELL_LAUNCH_FAILED,
        PrivilegedRuntimeFailure.DIRECTSHELL_IDENTITY_INVALID,
        PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED,
        PrivilegedRuntimeFailure.DIRECTSHELL_RUNTIME_RELEASED,
        PrivilegedRuntimeFailure.SHIZUKU_LEGACY_FALLBACK,
        -> PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED
    }

    private companion object {
        const val ADB_WIFI_ENABLED_SETTING = "adb_wifi_enabled"

        /** A missing or unreadable setting is unknown, not disabled; bounded acquisition decides. */
        fun wirelessDebuggingExplicitlyDisabled(context: Context): Boolean? = runCatching {
            Settings.Global.getString(context.contentResolver, ADB_WIFI_ENABLED_SETTING) == "0"
        }.getOrNull()
    }
}
