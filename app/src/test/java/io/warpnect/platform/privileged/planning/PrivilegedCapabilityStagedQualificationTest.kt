package io.warpnect.platform.privileged.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCapabilityStagedQualificationTest {
    @Test
    fun `healthy DirectShell plan leaves optional provider unprobed`() {
        var calls = 0
        val result = PrivilegedCapabilityStagedQualification().completeWithOptionalProvider(
            initialMatrix = matrix(
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            ),
            optionalProvider = DeferredCapabilityQualifier {
                calls += 1
                error("optional_provider_must_not_be_qualified")
            },
        )

        assertTrue(result.initiallyComplete)
        assertTrue(result.qualifiedCapabilities.isEmpty())
        assertEquals(0, calls)
        val plan = requireComplete(PrivilegedCapabilityPlanner.select(result.matrix))
        assertEquals(PrivilegedProvider.DIRECT_SHELL, plan.videoProvider)
        assertEquals(PrivilegedProvider.DIRECT_SHELL, plan.systemAudioProvider)
        assertEquals(PrivilegedProvider.DIRECT_SHELL, plan.inputProvider)
    }

    @Test
    fun `only a missing DirectShell input capability invokes Shizuku input qualification`() {
        var requested: Set<PrivilegedCapability>? = null
        val result = PrivilegedCapabilityStagedQualification().completeWithOptionalProvider(
            initialMatrix = matrix(
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                unavailable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            ),
            optionalProvider = DeferredCapabilityQualifier { capabilities ->
                requested = capabilities
                listOf(usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT))
            },
        )

        assertFalse(result.initiallyComplete)
        assertEquals(setOf(PrivilegedCapability.INPUT), requested)
        assertEquals(setOf(PrivilegedCapability.INPUT), result.qualifiedCapabilities)
        val plan = requireComplete(PrivilegedCapabilityPlanner.select(result.matrix))
        assertEquals(PrivilegedProvider.DIRECT_SHELL, plan.videoProvider)
        assertEquals(PrivilegedProvider.DIRECT_SHELL, plan.systemAudioProvider)
        assertEquals(PrivilegedProvider.SHIZUKU, plan.inputProvider)
        assertEquals(PrivilegedCapabilityPlanShape.COMPOSITE, plan.shape)
    }

    @Test
    fun `missing SystemAudio remains Host incompatible when optional provider fails it`() {
        val result = PrivilegedCapabilityStagedQualification().completeWithOptionalProvider(
            initialMatrix = matrix(
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                unavailable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            ),
            optionalProvider = DeferredCapabilityQualifier {
                listOf(
                    unavailable(
                        PrivilegedProvider.SHIZUKU,
                        PrivilegedCapability.SYSTEM_AUDIO,
                        CapabilityQualificationState.QUALIFICATION_FAILED,
                    ),
                )
            },
        )

        val incompatible = PrivilegedCapabilityPlanner.select(result.matrix)
            as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertEquals(listOf(PrivilegedCapability.SYSTEM_AUDIO), incompatible?.missingCapabilities)
    }

    @Test
    fun `optional authorization requirement is never selected as a capability`() {
        val result = PrivilegedCapabilityStagedQualification().completeWithOptionalProvider(
            initialMatrix = matrix(
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                unavailable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            ),
            optionalProvider = DeferredCapabilityQualifier {
                listOf(
                    unavailable(
                        PrivilegedProvider.SHIZUKU,
                        PrivilegedCapability.INPUT,
                        CapabilityQualificationState.AUTHORIZATION_REQUIRED,
                    ),
                )
            },
        )

        val incompatible = PrivilegedCapabilityPlanner.select(result.matrix)
            as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertEquals(listOf(PrivilegedCapability.INPUT), incompatible?.missingCapabilities)
    }

    @Test
    fun `optional provider bootstrap unavailability remains Host incompatible`() {
        val result = PrivilegedCapabilityStagedQualification().completeWithOptionalProvider(
            initialMatrix = matrix(
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                unavailable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            ),
            optionalProvider = DeferredCapabilityQualifier {
                listOf(
                    unavailable(
                        PrivilegedProvider.SHIZUKU,
                        PrivilegedCapability.INPUT,
                        CapabilityQualificationState.NOT_AVAILABLE,
                    ),
                )
            },
        )

        val incompatible = PrivilegedCapabilityPlanner.select(result.matrix)
            as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertEquals(listOf(PrivilegedCapability.INPUT), incompatible?.missingCapabilities)
        assertEquals(
            CapabilityQualificationState.NOT_AVAILABLE,
            result.matrix.qualification(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT).state,
        )
    }

    private fun matrix(vararg overrides: CapabilityQualification): PrivilegedCapabilityMatrix {
        val overrideByCoordinate = overrides.associateBy { it.provider to it.capability }
        return PrivilegedCapabilityMatrix.complete { provider, capability ->
            overrideByCoordinate[provider to capability] ?: CapabilityQualification.unavailable(
                provider = provider,
                capability = capability,
                state = if (provider == PrivilegedProvider.SHIZUKU) {
                    CapabilityQualificationState.DEFERRED
                } else {
                    CapabilityQualificationState.NOT_AVAILABLE
                },
                reason = "not_qualified_${provider.name}_${capability.name}",
                provenance = provenance(),
            )
        }
    }

    private fun usable(provider: PrivilegedProvider, capability: PrivilegedCapability): CapabilityQualification {
        return CapabilityQualification.usable(
            provider = provider,
            capability = capability,
            state = CapabilityQualificationState.QUALIFIED,
            reason = "qualified_${provider.name}_${capability.name}",
            provenance = provenance(),
        )
    }

    private fun unavailable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState = CapabilityQualificationState.QUALIFICATION_FAILED,
    ): CapabilityQualification {
        return CapabilityQualification.unavailable(
            provider = provider,
            capability = capability,
            state = state,
            reason = "unavailable_${provider.name}_${capability.name}",
            provenance = provenance(),
        )
    }

    private fun provenance() = CapabilityQualificationProvenance(
        CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
    )

    private fun requireComplete(result: PrivilegedCapabilityPlanResult): PrivilegedCapabilityPlan =
        (result as? PrivilegedCapabilityPlanResult.Complete)?.plan ?: error("expected_complete_plan")
}
