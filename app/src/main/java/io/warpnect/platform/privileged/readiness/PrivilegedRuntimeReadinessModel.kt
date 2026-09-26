package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlan
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot

/**
 * Current provider availability, deliberately separate from Video/SystemAudio/Input qualification.
 */
internal enum class PrivilegedRuntimeAvailabilityState {
    READY,
    DISABLED_RECOVERABLE,
    ENABLED_BUT_UNUSABLE,
    AUTHORIZATION_REQUIRED,
    TEMPORARILY_UNAVAILABLE,
    RECOVERY_IN_PROGRESS,
    RECOVERY_FAILED,
    UNSUPPORTED,
    NOT_IMPLEMENTED,
    UNKNOWN,
}

/** Machine-readable, non-sensitive cause for a volatile runtime observation. */
internal enum class PrivilegedRuntimeFailureReason {
    WIRELESS_DEBUGGING_DISABLED,
    WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE,
    ADB_PAIRING_REQUIRED,
    ADB_AUTH_FAILED,
    SHIZUKU_SERVER_STOPPED,
    SHIZUKU_AUTHORIZATION_REQUIRED,
    PLATFORM_UNSUPPORTED,
    PROVIDER_BOOTSTRAP_FAILED,
    UNKNOWN,
}

/** One provider's current runtime observation. It is never capability evidence by itself. */
internal data class PrivilegedRuntimeAvailability(
    val kind: PrivilegedRuntimeKind,
    val state: PrivilegedRuntimeAvailabilityState,
    val reason: PrivilegedRuntimeFailureReason? = null,
    val detail: String = "",
    /** A registered but intentionally idle runtime may be acquired only after an explicit Host request. */
    val candidateForHostAcquisition: Boolean = false,
) {
    init {
        require(detail.length <= MAX_DETAIL_LENGTH) { "runtime_readiness_detail_too_long" }
        require(detail.none { it == '\n' || it == '\r' }) { "runtime_readiness_detail_multiline" }
    }

    companion object {
        const val MAX_DETAIL_LENGTH = 160
    }
}

/**
 * Immutable volatile observation for the finite provider set. The generation belongs to the
 * coordinator that requested the observation; it is not a provider capability generation.
 */
internal data class PrivilegedRuntimeStateSnapshot(
    val generation: Long,
    val observedAtMonotonicMs: Long,
    val directRoot: PrivilegedRuntimeAvailability,
    val directShell: PrivilegedRuntimeAvailability,
    val shizuku: PrivilegedRuntimeAvailability,
) {
    init {
        require(generation > 0L) { "runtime_readiness_generation_invalid" }
        require(observedAtMonotonicMs >= 0L) { "runtime_readiness_timestamp_invalid" }
        require(directRoot.kind == PrivilegedRuntimeKind.DIRECT_ROOT) { "runtime_readiness_root_kind_invalid" }
        require(directShell.kind == PrivilegedRuntimeKind.DIRECT_SHELL) { "runtime_readiness_shell_kind_invalid" }
        require(shizuku.kind == PrivilegedRuntimeKind.SHIZUKU) { "runtime_readiness_shizuku_kind_invalid" }
    }

    fun availability(kind: PrivilegedRuntimeKind): PrivilegedRuntimeAvailability = when (kind) {
        PrivilegedRuntimeKind.DIRECT_ROOT -> directRoot
        PrivilegedRuntimeKind.DIRECT_SHELL -> directShell
        PrivilegedRuntimeKind.SHIZUKU -> shizuku
        PrivilegedRuntimeKind.NONE -> error("runtime_readiness_none_is_not_a_provider")
    }
}

/** The concrete phase currently known to the UI. There is intentionally no synthetic percentage. */
internal enum class HostReadinessPhase {
    CHECKING_DEVICE,
    CHECKING_PRIVILEGED_RUNTIME,
    PREPARING_HOST,
}

/** Origin of advisory prepared-plan evidence. It never changes RFC-006I planner ordering. */
internal enum class HostReadinessEvidenceSource {
    NONE,
    EXISTING_COMPATIBILITY_CACHE,
    CURRENT_HOST_ADMISSION,
}

/**
 * A plan visible before or during Host activation. Only [FROZEN_AT_HOST_ADMISSION] has passed
 * the existing RFC-006I provider acquisition boundary and may be used to publish a Host.
 */
internal enum class HostReadinessPlanCommitState {
    ADVISORY,
    FROZEN_AT_HOST_ADMISSION,
}

/**
 * Generation-scoped plan/evidence bundle. A prepared plan is deliberately not a WNCP or Session
 * contract until the existing Host admission has frozen it.
 */
internal data class PreparedPrivilegedCapabilityPlan(
    val generation: Long,
    val runtimeState: PrivilegedRuntimeStateSnapshot,
    /** May be IDLE for a cache-derived advisory plan; never implies that a provider is running. */
    val runtimeSnapshot: PrivilegedRuntimeSnapshot,
    val matrix: PrivilegedCapabilityMatrix,
    val plan: PrivilegedCapabilityPlan,
    val evidenceSource: HostReadinessEvidenceSource,
    /** Stable hashed keys used by advisory evidence. Empty only for live Host-admission plans. */
    val evidenceCompatibilityKeys: List<String> = emptyList(),
    val commitState: HostReadinessPlanCommitState,
    val preparedAtMonotonicMs: Long,
) {
    init {
        require(generation == runtimeState.generation) { "prepared_plan_runtime_generation_mismatch" }
        require(evidenceCompatibilityKeys.size == evidenceCompatibilityKeys.distinct().size) {
            "prepared_plan_evidence_key_duplicate"
        }
        require(preparedAtMonotonicMs >= 0L) { "prepared_plan_timestamp_invalid" }
    }
}

/** One passive background result. A non-null prepared plan is advisory until Host admission freezes it. */
internal data class BackgroundHostReadinessObservation(
    val runtimeState: PrivilegedRuntimeStateSnapshot,
    val preparedPlan: PreparedPrivilegedCapabilityPlan? = null,
    val evidenceSource: HostReadinessEvidenceSource = HostReadinessEvidenceSource.NONE,
) {
    init {
        require(preparedPlan == null || preparedPlan.generation == runtimeState.generation) {
            "background_readiness_prepared_generation_mismatch"
        }
        require(preparedPlan == null || preparedPlan.commitState == HostReadinessPlanCommitState.ADVISORY) {
            "background_readiness_plan_must_be_advisory"
        }
    }
}

/**
 * User-facing Host readiness state. It is separate from the per-provider runtime snapshot and
 * separate again from the final RFC-006I frozen plan.
 */
internal sealed interface HostReadinessState {
    data object NotStarted : HostReadinessState

    data class Checking(
        val generation: Long,
        val phase: HostReadinessPhase,
    ) : HostReadinessState

    data class Ready(
        val prepared: PreparedPrivilegedCapabilityPlan,
    ) : HostReadinessState

    data class AuthorizationRequired(
        val generation: Long,
        val runtimeState: PrivilegedRuntimeStateSnapshot,
        val reason: PrivilegedRuntimeFailureReason,
    ) : HostReadinessState

    data class UserActionRequired(
        val generation: Long,
        val runtimeState: PrivilegedRuntimeStateSnapshot,
        val reason: PrivilegedRuntimeFailureReason,
    ) : HostReadinessState

    data class Unsupported(
        val generation: Long,
        val runtimeState: PrivilegedRuntimeStateSnapshot,
        val reason: PrivilegedRuntimeFailureReason,
    ) : HostReadinessState

    data class Failed(
        val generation: Long,
        val runtimeState: PrivilegedRuntimeStateSnapshot?,
        val reason: PrivilegedRuntimeFailureReason,
    ) : HostReadinessState

    data class Stale(
        val generation: Long,
        val runtimeState: PrivilegedRuntimeStateSnapshot,
        val reason: PrivilegedRuntimeFailureReason? = null,
        val evidenceSource: HostReadinessEvidenceSource = HostReadinessEvidenceSource.NONE,
    ) : HostReadinessState
}

internal val HostReadinessState.generationOrNull: Long?
    get() = when (this) {
        HostReadinessState.NotStarted -> null
        is HostReadinessState.Checking -> generation
        is HostReadinessState.Ready -> prepared.generation
        is HostReadinessState.AuthorizationRequired -> generation
        is HostReadinessState.UserActionRequired -> generation
        is HostReadinessState.Unsupported -> generation
        is HostReadinessState.Failed -> generation
        is HostReadinessState.Stale -> generation
    }

/** UI visibility is intentionally independent from the durable readiness state. */
internal data class HostReadinessUiState(
    val readiness: HostReadinessState = HostReadinessState.NotStarted,
    val hostActionVisible: Boolean = false,
)
