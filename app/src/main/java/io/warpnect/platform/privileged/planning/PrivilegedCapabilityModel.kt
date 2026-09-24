package io.warpnect.platform.privileged.planning

import java.util.Collections

/** The complete, finite privileged capability domain required by a normal Host. */
enum class PrivilegedCapability {
    VIDEO,
    SYSTEM_AUDIO,
    INPUT,
    ;

    companion object {
        val canonicalOrder: List<PrivilegedCapability> = entries
    }
}

/** A concrete privileged execution provider. Composite is a plan shape, never a provider. */
enum class PrivilegedProvider {
    DIRECT_ROOT,
    DIRECT_SHELL,
    SHIZUKU,
    ;

    companion object {
        /** Also the deterministic provider-priority order after evidence has been compared. */
        val canonicalOrder: List<PrivilegedProvider> = entries
    }
}

/**
 * Qualification semantics for one provider/capability coordinate.
 *
 * Only [QUALIFIED] and [VALIDATED] are usable by a frozen Host plan. A successfully started
 * process is intentionally not represented as a usable state by itself.
 */
enum class CapabilityQualificationState {
    NOT_IMPLEMENTED,
    NOT_AVAILABLE,
    AUTHORIZATION_REQUIRED,
    QUALIFICATION_FAILED,
    QUALIFIED,
    VALIDATED,
    ;

    val isUsable: Boolean
        get() = this == QUALIFIED || this == VALIDATED

    val evidence: CapabilityEvidence
        get() = when (this) {
            QUALIFIED -> CapabilityEvidence.QUALIFIED
            VALIDATED -> CapabilityEvidence.VALIDATED
            else -> CapabilityEvidence.NONE
        }
}

/** A deliberately small, explicit evidence ordering. It is not a numeric reliability score. */
enum class CapabilityEvidence {
    NONE,
    QUALIFIED,
    VALIDATED,
}

/** Identifies where a bounded qualification observation came from. */
enum class CapabilityQualificationSource {
    CURRENT_ATTEMPT_PROBE,
    CURRENT_ATTEMPT_CODEC_PREREQUISITE,
    EXISTING_COMPATIBILITY_CACHE,
    PROVIDER_ADAPTER,
    PRE_FREEZE_ACQUISITION,
}

/**
 * Diagnostics-only provenance. Cache dimensions remain explicit so a later cache migration does
 * not collapse provider/capability evidence into a coarse device-level support claim.
 */
data class CapabilityQualificationProvenance(
    val source: CapabilityQualificationSource,
    val runtimeGeneration: Long? = null,
    val compatibilityKey: String? = null,
    val providerVersion: String? = null,
    val implementationVersion: String? = null,
)

/** One immutable provider/capability qualification observation. */
data class CapabilityQualification(
    val provider: PrivilegedProvider,
    val capability: PrivilegedCapability,
    val state: CapabilityQualificationState,
    val reason: String,
    val provenance: CapabilityQualificationProvenance,
) {
    val evidence: CapabilityEvidence
        get() = state.evidence

    init {
        require(reason.isNotBlank()) { "capability_qualification_reason_missing" }
        require(reason.length <= MAX_REASON_LENGTH) { "capability_qualification_reason_too_long" }
        require(reason.none { it == '\n' || it == '\r' }) { "capability_qualification_reason_multiline" }
    }

    companion object {
        const val MAX_REASON_LENGTH = 160

        fun unavailable(
            provider: PrivilegedProvider,
            capability: PrivilegedCapability,
            state: CapabilityQualificationState,
            reason: String,
            provenance: CapabilityQualificationProvenance,
        ): CapabilityQualification {
            require(!state.isUsable) { "usable_state_requires_usable_factory" }
            return CapabilityQualification(provider, capability, state, reason, provenance)
        }

        fun usable(
            provider: PrivilegedProvider,
            capability: PrivilegedCapability,
            state: CapabilityQualificationState,
            reason: String,
            provenance: CapabilityQualificationProvenance,
        ): CapabilityQualification {
            require(state.isUsable) { "unusable_state_requires_unavailable_factory" }
            return CapabilityQualification(provider, capability, state, reason, provenance)
        }
    }
}

/**
 * Immutable, complete Cartesian product of every provider and required capability.
 *
 * Storage follows enum order, rather than construction/map order, so snapshots and planner input
 * are stable even when callers build their source collection in a different order.
 */
class PrivilegedCapabilityMatrix private constructor(
    cells: List<CapabilityQualification>,
) {
    private val orderedCells: List<CapabilityQualification> = Collections.unmodifiableList(cells.toList())

    init {
        require(orderedCells.size == coordinateCount) { "privileged_capability_matrix_incomplete" }
        canonicalCoordinates().zip(orderedCells).forEach { (coordinate, cell) ->
            require(cell.provider == coordinate.provider && cell.capability == coordinate.capability) {
                "privileged_capability_matrix_coordinate_mismatch"
            }
        }
    }

    fun qualification(provider: PrivilegedProvider, capability: PrivilegedCapability): CapabilityQualification =
        orderedCells[indexOf(provider, capability)]

    fun entries(): List<CapabilityQualification> = orderedCells

    /** Returns a fresh matrix after one runtime-wide pre-freeze provider acquisition failure. */
    fun invalidateProvider(provider: PrivilegedProvider, reason: String): PrivilegedCapabilityMatrix = from(
        orderedCells.map { cell ->
            if (cell.provider != provider) {
                cell
            } else {
                CapabilityQualification.unavailable(
                    provider = cell.provider,
                    capability = cell.capability,
                    state = CapabilityQualificationState.NOT_AVAILABLE,
                    reason = reason,
                    provenance = cell.provenance.copy(
                        source = CapabilityQualificationSource.PRE_FREEZE_ACQUISITION,
                    ),
                )
            }
        },
    )

    override fun equals(other: Any?): Boolean =
        other is PrivilegedCapabilityMatrix && orderedCells == other.orderedCells

    override fun hashCode(): Int = orderedCells.hashCode()

    override fun toString(): String = "PrivilegedCapabilityMatrix(cells=$orderedCells)"

    companion object {
        private data class Coordinate(
            val provider: PrivilegedProvider,
            val capability: PrivilegedCapability,
        )

        private val coordinates: List<Coordinate> = canonicalCoordinates()
        private val coordinateCount = coordinates.size

        fun from(cells: Iterable<CapabilityQualification>): PrivilegedCapabilityMatrix {
            val supplied = cells.toList()
            require(supplied.size == coordinateCount) { "privileged_capability_matrix_cell_count_invalid" }
            val indexed = supplied.associateBy { cell -> cell.provider to cell.capability }
            require(indexed.size == coordinateCount) { "privileged_capability_matrix_duplicate_coordinate" }
            val ordered = coordinates.map { coordinate ->
                requireNotNull(indexed[coordinate.provider to coordinate.capability]) {
                    "privileged_capability_matrix_coordinate_missing"
                }
            }
            return PrivilegedCapabilityMatrix(ordered)
        }

        fun complete(
            cellFor: (PrivilegedProvider, PrivilegedCapability) -> CapabilityQualification,
        ): PrivilegedCapabilityMatrix = from(
            coordinates.map { coordinate -> cellFor(coordinate.provider, coordinate.capability) },
        )

        private fun canonicalCoordinates(): List<Coordinate> = PrivilegedProvider.canonicalOrder.flatMap { provider ->
            PrivilegedCapability.canonicalOrder.map { capability -> Coordinate(provider, capability) }
        }

        private fun indexOf(provider: PrivilegedProvider, capability: PrivilegedCapability): Int =
            provider.ordinal * PrivilegedCapability.canonicalOrder.size + capability.ordinal
    }
}

enum class PrivilegedCapabilityPlanShape {
    SINGLE,
    COMPOSITE,
}

data class PrivilegedCapabilityAssignment(
    val capability: PrivilegedCapability,
    val provider: PrivilegedProvider,
)

/** A complete, static provider assignment ready to freeze before Host advertisement. */
data class PrivilegedCapabilityPlan(
    val videoProvider: PrivilegedProvider,
    val systemAudioProvider: PrivilegedProvider,
    val inputProvider: PrivilegedProvider,
    val selectedQualifications: List<CapabilityQualification>,
) {
    init {
        require(selectedQualifications.size == PrivilegedCapability.canonicalOrder.size) {
            "privileged_capability_plan_incomplete"
        }
        assignments().zip(selectedQualifications).forEach { (assignment, qualification) ->
            require(
                qualification.provider == assignment.provider &&
                    qualification.capability == assignment.capability &&
                    qualification.state.isUsable,
            ) { "privileged_capability_plan_qualification_invalid" }
        }
    }

    val shape: PrivilegedCapabilityPlanShape
        get() = if (participatingProviders.size == 1) {
            PrivilegedCapabilityPlanShape.SINGLE
        } else {
            PrivilegedCapabilityPlanShape.COMPOSITE
        }

    val participatingProviders: List<PrivilegedProvider>
        get() = PrivilegedProvider.canonicalOrder.filter { provider ->
            assignments().any { it.provider == provider }
        }

    val weakestEvidence: CapabilityEvidence
        get() = selectedQualifications.minOf { qualification -> qualification.evidence }

    val strongerEvidenceCellCount: Int
        get() = selectedQualifications.count { qualification ->
            qualification.evidence == CapabilityEvidence.VALIDATED
        }

    fun providerFor(capability: PrivilegedCapability): PrivilegedProvider = when (capability) {
        PrivilegedCapability.VIDEO -> videoProvider
        PrivilegedCapability.SYSTEM_AUDIO -> systemAudioProvider
        PrivilegedCapability.INPUT -> inputProvider
    }

    fun assignments(): List<PrivilegedCapabilityAssignment> = listOf(
        PrivilegedCapabilityAssignment(PrivilegedCapability.VIDEO, videoProvider),
        PrivilegedCapabilityAssignment(PrivilegedCapability.SYSTEM_AUDIO, systemAudioProvider),
        PrivilegedCapabilityAssignment(PrivilegedCapability.INPUT, inputProvider),
    )
}

sealed interface PrivilegedCapabilityPlanResult {
    data class Complete(
        val plan: PrivilegedCapabilityPlan,
    ) : PrivilegedCapabilityPlanResult

    data class HostIncompatible(
        val missingCapabilities: List<PrivilegedCapability>,
        val diagnostics: List<CapabilityQualification>,
    ) : PrivilegedCapabilityPlanResult {
        init {
            require(missingCapabilities.isNotEmpty()) { "host_incompatible_missing_capability_empty" }
        }
    }
}
