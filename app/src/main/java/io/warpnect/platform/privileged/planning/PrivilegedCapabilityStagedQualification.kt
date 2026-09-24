package io.warpnect.platform.privileged.planning

import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot

/**
 * A bounded, optional provider probe used only after the current matrix cannot produce a
 * complete Host plan. The probe receives exactly the currently missing capabilities.
 */
internal fun interface DeferredCapabilityQualifier {
    fun qualify(capabilities: Set<PrivilegedCapability>): List<CapabilityQualification>
}

/** Optional provider lifecycle and selected-capability snapshot contribution. */
internal interface OptionalPrivilegedCapabilityProvider : DeferredCapabilityQualifier {
    fun acquire(): PrivilegedProviderAcquisition

    fun release()

    fun snapshotForPlan(
        plan: PrivilegedCapabilityPlan,
        current: AndroidCapabilityProbeSnapshot,
    ): AndroidCapabilityProbeSnapshot
}

internal data class StagedPrivilegedCapabilityQualificationResult(
    val matrix: PrivilegedCapabilityMatrix,
    val initiallyComplete: Boolean,
    val qualifiedCapabilities: Set<PrivilegedCapability>,
)

/**
 * Keeps lazy qualification outside the pure planner. A deferred cell is never selectable; it is
 * replaced only when a complete plan is impossible with the already-acquired provider set.
 */
internal class PrivilegedCapabilityStagedQualification(
    private val planner: PrivilegedCapabilityPlanner = PrivilegedCapabilityPlanner,
) {
    fun completeWithOptionalProvider(
        initialMatrix: PrivilegedCapabilityMatrix,
        optionalProvider: DeferredCapabilityQualifier,
    ): StagedPrivilegedCapabilityQualificationResult {
        val initial = planner.select(initialMatrix)
        if (initial is PrivilegedCapabilityPlanResult.Complete) {
            return StagedPrivilegedCapabilityQualificationResult(
                matrix = initialMatrix,
                initiallyComplete = true,
                qualifiedCapabilities = emptySet(),
            )
        }

        val missing = (initial as PrivilegedCapabilityPlanResult.HostIncompatible)
            .missingCapabilities
            .toCollection(linkedSetOf())
        val updated = initialMatrix.withQualifications(optionalProvider.qualify(missing))
        return StagedPrivilegedCapabilityQualificationResult(
            matrix = updated,
            initiallyComplete = false,
            qualifiedCapabilities = missing,
        )
    }
}
