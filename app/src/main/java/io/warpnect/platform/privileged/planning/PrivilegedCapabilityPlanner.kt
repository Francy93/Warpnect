package io.warpnect.platform.privileged.planning

/**
 * Pure static plan selection. It has no Android, runtime, ADB, Shizuku, or global-state dependency.
 */
object PrivilegedCapabilityPlanner {
    fun select(matrix: PrivilegedCapabilityMatrix): PrivilegedCapabilityPlanResult {
        val providersByCapability = PrivilegedCapability.canonicalOrder.associateWith { capability ->
            PrivilegedProvider.canonicalOrder.filter { provider ->
                matrix.qualification(provider, capability).state.isUsable
            }
        }
        val missing = PrivilegedCapability.canonicalOrder.filter { capability ->
            providersByCapability.getValue(capability).isEmpty()
        }
        if (missing.isNotEmpty()) {
            return PrivilegedCapabilityPlanResult.HostIncompatible(
                missingCapabilities = missing,
                diagnostics = missing.flatMap { capability ->
                    PrivilegedProvider.canonicalOrder.map { provider -> matrix.qualification(provider, capability) }
                },
            )
        }

        val candidates = buildList {
            providersByCapability.getValue(PrivilegedCapability.VIDEO).forEach { video ->
                providersByCapability.getValue(PrivilegedCapability.SYSTEM_AUDIO).forEach { systemAudio ->
                    providersByCapability.getValue(PrivilegedCapability.INPUT).forEach { input ->
                        add(
                            PrivilegedCapabilityPlan(
                                videoProvider = video,
                                systemAudioProvider = systemAudio,
                                inputProvider = input,
                                selectedQualifications = listOf(
                                    matrix.qualification(video, PrivilegedCapability.VIDEO),
                                    matrix.qualification(systemAudio, PrivilegedCapability.SYSTEM_AUDIO),
                                    matrix.qualification(input, PrivilegedCapability.INPUT),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        val selected = candidates.minWithOrNull(::comparePlans)
            ?: error("complete_provider_choices_must_produce_a_plan")
        return PrivilegedCapabilityPlanResult.Complete(selected)
    }

    /**
     * RFC-006I ordering:
     * 1. fewest active providers;
     * 2. strongest weakest evidence, then most VALIDATED cells;
     * 3. provider priority in Video/SystemAudio/Input order;
     * 4. no concrete lifecycle-cost distinction exists yet;
     * 5. the same canonical assignment vector is the stable final tie-break.
     */
    private fun comparePlans(left: PrivilegedCapabilityPlan, right: PrivilegedCapabilityPlan): Int {
        compareValues(left.participatingProviders.size, right.participatingProviders.size).takeIf { it != 0 }
            ?.let { return it }
        compareValues(right.weakestEvidence, left.weakestEvidence).takeIf { it != 0 }
            ?.let { return it }
        compareValues(right.strongerEvidenceCellCount, left.strongerEvidenceCellCount).takeIf { it != 0 }
            ?.let { return it }

        PrivilegedCapability.canonicalOrder.forEach { capability ->
            compareValues(left.providerFor(capability), right.providerFor(capability)).takeIf { it != 0 }
                ?.let { return it }
        }
        return 0
    }
}
