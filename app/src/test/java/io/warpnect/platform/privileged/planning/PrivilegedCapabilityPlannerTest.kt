package io.warpnect.platform.privileged.planning

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCapabilityPlannerTest {
    @Test fun `Root Video and Input still miss SystemAudio`() {
        val result =
            select(
                usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
            )
        assertEquals(
            listOf(PrivilegedCapability.SYSTEM_AUDIO),
            (result as PrivilegedCapabilityPlanResult.HostIncompatible).missingCapabilities,
        )
    }

    @Test fun `qualified complete Root matrix selects single Root without ranking change`() {
        assertPlan(
            select(
                *PrivilegedCapability.canonicalOrder.map {
                    usable(PrivilegedProvider.DIRECT_ROOT, it)
                }.toTypedArray(),
            ),
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test fun `Root media input and Shell Audio compose when only those cells exist`() {
        assertPlan(
            select(
                usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
            ),
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapabilityPlanShape.COMPOSITE,
        )
    }

    @Test fun `complete Shell wins over unnecessary Root Video Input composition`() {
        val cells = PrivilegedCapability.canonicalOrder.map {
            usable(PrivilegedProvider.DIRECT_SHELL, it)
        } + listOf(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
        )
        assertPlan(
            select(*cells.toTypedArray()),
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `Root Input alone cannot produce a partial Host`() {
        val result = select(usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT))
        assertTrue(result is PrivilegedCapabilityPlanResult.HostIncompatible)
    }

    @Test
    fun `Root Input completes otherwise incomplete DirectShell without Root media`() {
        assertPlan(
            select(
                usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            ),
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapabilityPlanShape.COMPOSITE,
        )
    }

    @Test
    fun `complete DirectShell avoids unnecessary Root Input composite`() {
        assertPlan(
            select(
                usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            ),
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `A DirectShell-only complete plan is selected`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `B complete Root wins when equivalently qualified`() {
        val cells = PrivilegedProvider.canonicalOrder.flatMap { provider ->
            PrivilegedCapability.canonicalOrder.map { capability -> usable(provider, capability) }
        }

        assertPlan(
            select(*cells.toTypedArray()),
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `C two-provider composite uses Root where equally qualified`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapabilityPlanShape.COMPOSITE,
        )
    }

    @Test
    fun `D Shizuku can synthetically rescue one capability`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.SHIZUKU,
            PrivilegedCapabilityPlanShape.COMPOSITE,
        )
    }

    @Test
    fun `E a unique three-provider assignment remains complete`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.SHIZUKU,
            PrivilegedCapabilityPlanShape.COMPOSITE,
        )
    }

    @Test
    fun `F no audio provider makes Host incompatible rather than partial`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        val incompatible = result as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertTrue(incompatible != null)
        assertEquals(listOf(PrivilegedCapability.SYSTEM_AUDIO), incompatible?.missingCapabilities)
    }

    @Test
    fun `F no video provider makes Host incompatible rather than partial`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        val incompatible = result as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertEquals(listOf(PrivilegedCapability.VIDEO), incompatible?.missingCapabilities)
    }

    @Test
    fun `F no input provider makes Host incompatible rather than partial`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
        )

        val incompatible = result as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertEquals(listOf(PrivilegedCapability.INPUT), incompatible?.missingCapabilities)
    }

    @Test
    fun `G fewer providers beats a nominally higher-priority composite`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `H stronger evidence beats nominal provider priority`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO, CapabilityQualificationState.VALIDATED),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `I provider priority breaks a true tie`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        assertPlan(
            result,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapabilityPlanShape.SINGLE,
        )
    }

    @Test
    fun `J shuffled matrix construction produces the same logical plan`() {
        val cells = completeCells(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )
        val expected = PrivilegedCapabilityPlanner.select(PrivilegedCapabilityMatrix.from(cells))

        repeat(20) { index ->
            val shuffled = cells.shuffled(Random(index))
            assertEquals(expected, PrivilegedCapabilityPlanner.select(PrivilegedCapabilityMatrix.from(shuffled)))
        }
    }

    @Test
    fun `K authorization required is not usable`() {
        val result = select(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            unavailable(
                PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.AUTHORIZATION_REQUIRED,
            ),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        val incompatible = result as? PrivilegedCapabilityPlanResult.HostIncompatible
        assertEquals(listOf(PrivilegedCapability.SYSTEM_AUDIO), incompatible?.missingCapabilities)
    }

    @Test
    fun `L not implemented Root cells never enter a plan`() {
        val root = PrivilegedCapability.canonicalOrder.map { capability ->
            unavailable(
                PrivilegedProvider.DIRECT_ROOT,
                capability,
                CapabilityQualificationState.NOT_IMPLEMENTED,
            )
        }
        val result = select(
            *root.toTypedArray(),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )

        val plan = requireComplete(result)
        assertFalse(plan.participatingProviders.contains(PrivilegedProvider.DIRECT_ROOT))
        assertEquals(PrivilegedProvider.DIRECT_SHELL, plan.videoProvider)
    }

    private fun select(vararg overrides: CapabilityQualification): PrivilegedCapabilityPlanResult =
        PrivilegedCapabilityPlanner.select(PrivilegedCapabilityMatrix.from(completeCells(*overrides)))

    private fun completeCells(vararg overrides: CapabilityQualification): List<CapabilityQualification> {
        val overrideByCoordinate = overrides.associateBy { it.provider to it.capability }
        return PrivilegedProvider.canonicalOrder.flatMap { provider ->
            PrivilegedCapability.canonicalOrder.map { capability ->
                overrideByCoordinate[provider to capability]
                    ?: unavailable(provider, capability, CapabilityQualificationState.NOT_AVAILABLE)
            }
        }
    }

    private fun usable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState = CapabilityQualificationState.QUALIFIED,
    ): CapabilityQualification = CapabilityQualification.usable(
        provider = provider,
        capability = capability,
        state = state,
        reason = "qualified_${provider.name}_${capability.name}",
        provenance = provenance(),
    )

    private fun unavailable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState,
    ): CapabilityQualification = CapabilityQualification.unavailable(
        provider = provider,
        capability = capability,
        state = state,
        reason = "unavailable_${provider.name}_${capability.name}",
        provenance = provenance(),
    )

    private fun provenance() = CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE)

    private fun requireComplete(result: PrivilegedCapabilityPlanResult): PrivilegedCapabilityPlan =
        (result as? PrivilegedCapabilityPlanResult.Complete)?.plan
            ?: error("expected_complete_plan")

    private fun assertPlan(
        result: PrivilegedCapabilityPlanResult,
        video: PrivilegedProvider,
        systemAudio: PrivilegedProvider,
        input: PrivilegedProvider,
        shape: PrivilegedCapabilityPlanShape,
    ) {
        val plan = requireComplete(result)
        assertEquals(video, plan.videoProvider)
        assertEquals(systemAudio, plan.systemAudioProvider)
        assertEquals(input, plan.inputProvider)
        assertEquals(shape, plan.shape)
    }
}
