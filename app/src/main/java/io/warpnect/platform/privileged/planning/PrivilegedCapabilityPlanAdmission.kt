package io.warpnect.platform.privileged.planning

/** A purpose-specific provider acquisition surface used only before a Host plan freezes. */
interface PrivilegedProviderAcquirer {
    fun acquire(provider: PrivilegedProvider): PrivilegedProviderAcquisition

    fun release(provider: PrivilegedProvider)
}

sealed interface PrivilegedProviderAcquisition {
    /** The admission attempt acquired a new provider ownership. */
    data object Acquired : PrivilegedProviderAcquisition

    /** A provider already qualified for this Host attempt is retained rather than reacquired. */
    data object Retained : PrivilegedProviderAcquisition

    data class Failed(
        val reason: String,
    ) : PrivilegedProviderAcquisition {
        init {
            require(reason.isNotBlank()) { "privileged_provider_acquisition_reason_missing" }
            require(reason.length <= CapabilityQualification.MAX_REASON_LENGTH) {
                "privileged_provider_acquisition_reason_too_long"
            }
        }
    }
}

data class PrivilegedCapabilityPlanAttempt(
    val plan: PrivilegedCapabilityPlan,
    val failedProvider: PrivilegedProvider? = null,
    val failureReason: String? = null,
)

/** Owns the providers selected by one frozen Host admission plan. */
class PrivilegedCapabilityPlanLease internal constructor(
    val plan: PrivilegedCapabilityPlan,
    private val acquirer: PrivilegedProviderAcquirer,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    override fun close() {
        val providers = synchronized(lock) {
            if (closed) return
            closed = true
            plan.participatingProviders.asReversed()
        }
        providers.forEach(acquirer::release)
    }
}

sealed interface PrivilegedCapabilityPlanAdmissionResult {
    val matrix: PrivilegedCapabilityMatrix
    val attempts: List<PrivilegedCapabilityPlanAttempt>

    data class Frozen(
        override val matrix: PrivilegedCapabilityMatrix,
        val plan: PrivilegedCapabilityPlan,
        val lease: PrivilegedCapabilityPlanLease,
        override val attempts: List<PrivilegedCapabilityPlanAttempt>,
    ) : PrivilegedCapabilityPlanAdmissionResult

    data class Incompatible(
        override val matrix: PrivilegedCapabilityMatrix,
        val result: PrivilegedCapabilityPlanResult.HostIncompatible,
        override val attempts: List<PrivilegedCapabilityPlanAttempt>,
    ) : PrivilegedCapabilityPlanAdmissionResult
}

/**
 * Performs finite pre-freeze acquisition and re-planning. Every failed provider acquisition
 * invalidates every coordinate for that provider in this Host attempt, so the selectable state
 * space strictly shrinks. It never retries an unchanged failed plan.
 */
class PrivilegedCapabilityPlanAdmission(
    private val planner: PrivilegedCapabilityPlanner = PrivilegedCapabilityPlanner,
) {
    fun admit(
        initialMatrix: PrivilegedCapabilityMatrix,
        acquirer: PrivilegedProviderAcquirer,
    ): PrivilegedCapabilityPlanAdmissionResult {
        var matrix = initialMatrix
        val attempts = mutableListOf<PrivilegedCapabilityPlanAttempt>()
        val attemptedPlans = mutableSetOf<List<PrivilegedProvider>>()

        while (true) {
            when (val selection = planner.select(matrix)) {
                is PrivilegedCapabilityPlanResult.HostIncompatible -> {
                    return PrivilegedCapabilityPlanAdmissionResult.Incompatible(matrix, selection, attempts)
                }

                is PrivilegedCapabilityPlanResult.Complete -> {
                    val plan = selection.plan
                    val planKey = PrivilegedCapability.canonicalOrder.map(plan::providerFor)
                    check(attemptedPlans.add(planKey)) { "privileged_capability_plan_retried_without_matrix_change" }

                    val ownedThisAttempt = mutableListOf<PrivilegedProvider>()
                    var failure: Pair<PrivilegedProvider, String>? = null
                    for (provider in plan.participatingProviders) {
                        when (val acquisition = acquirer.acquire(provider)) {
                            PrivilegedProviderAcquisition.Acquired,
                            PrivilegedProviderAcquisition.Retained,
                            -> ownedThisAttempt += provider

                            is PrivilegedProviderAcquisition.Failed -> {
                                failure = provider to acquisition.reason
                                break
                            }
                        }
                    }
                    if (failure == null) {
                        val lease = PrivilegedCapabilityPlanLease(plan, acquirer)
                        attempts += PrivilegedCapabilityPlanAttempt(plan)
                        return PrivilegedCapabilityPlanAdmissionResult.Frozen(matrix, plan, lease, attempts)
                    }

                    ownedThisAttempt.asReversed().forEach(acquirer::release)
                    val (failedProvider, failureReason) = requireNotNull(failure)
                    attempts += PrivilegedCapabilityPlanAttempt(plan, failedProvider, failureReason)
                    matrix = matrix.invalidateProvider(
                        provider = failedProvider,
                        reason = boundedReplanReason(failureReason),
                    )
                }
            }
        }
    }

    private fun boundedReplanReason(failureReason: String): String =
        ("provider_acquisition_failed_$failureReason").take(CapabilityQualification.MAX_REASON_LENGTH)
}
