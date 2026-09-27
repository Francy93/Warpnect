package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.DirectRootSnapshot
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance

/** Compatibility may outlive a helper; active ownership must use the current authenticated generation. */
internal object RootHostEvidence {
    fun hasCompleteEvidence(
        evidence: List<PersistentCapabilityEvidenceRecord>,
        provenance: RootBootstrapProvenance?,
    ): Boolean {
        if (provenance == null) return false
        val matrix = CachedCapabilityEvidenceMatrixBuilder.build(evidence)
        return PrivilegedCapability.canonicalOrder.all { capability ->
            val cell = matrix.qualification(PrivilegedProvider.DIRECT_ROOT, capability)
            cell.state.isUsable && cell.provenance.providerVersion == provenance.name
        }
    }

    fun restore(
        runtime: DirectRootSnapshot,
        evidence: List<PersistentCapabilityEvidenceRecord>,
    ): List<CapabilityQualification> {
        check(runtime.ready && runtime.generation != null) { "root_host_runtime_unavailable" }
        val matrix = CachedCapabilityEvidenceMatrixBuilder.build(evidence)
        return PrivilegedCapability.canonicalOrder.map { capability ->
            val cell = matrix.qualification(PrivilegedProvider.DIRECT_ROOT, capability)
            check(cell.state.isUsable && cell.provenance.providerVersion == runtime.provenance?.name) {
                "root_host_cached_${capability.name.lowercase()}_evidence_missing"
            }
            cell.copy(
                reason = "root_cached_evidence_current_authority",
                provenance = cell.provenance.copy(
                    source = CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE,
                    runtimeGeneration = runtime.generation,
                ),
            )
        }
    }
}
