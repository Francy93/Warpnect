package io.warpnect.platform.privileged.planning

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCapabilityPlanAdmissionTest {
    @Test
    fun `selected DirectShell acquisition failure replans to complete Shizuku alternative`() {
        val matrix = matrix(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO, CapabilityQualificationState.VALIDATED),
            usable(
                PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.VALIDATED,
            ),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT, CapabilityQualificationState.VALIDATED),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )
        val acquirer = FakeAcquirer(
            PrivilegedProvider.DIRECT_SHELL to listOf(failed("directshell_connect_failed")),
            PrivilegedProvider.SHIZUKU to listOf(PrivilegedProviderAcquisition.Acquired),
        )

        val frozen = requireFrozen(PrivilegedCapabilityPlanAdmission().admit(matrix, acquirer))

        assertEquals(PrivilegedProvider.SHIZUKU, frozen.plan.videoProvider)
        assertEquals(2, frozen.attempts.size)
        assertEquals(PrivilegedProvider.DIRECT_SHELL, frozen.attempts.first().failedProvider)
        assertEquals(1, acquirer.acquireCount(PrivilegedProvider.DIRECT_SHELL))
        frozen.lease.close()
        assertEquals(listOf(PrivilegedProvider.SHIZUKU), acquirer.releases)
    }

    @Test
    fun `partially acquired composite cleans ownership then replans`() {
        val matrix = matrix(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO, CapabilityQualificationState.VALIDATED),
            usable(
                PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.VALIDATED,
            ),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT, CapabilityQualificationState.VALIDATED),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
        )
        val acquirer = FakeAcquirer(
            PrivilegedProvider.DIRECT_SHELL to listOf(
                PrivilegedProviderAcquisition.Acquired,
                PrivilegedProviderAcquisition.Acquired,
            ),
            PrivilegedProvider.SHIZUKU to listOf(failed("shizuku_bind_failed")),
            PrivilegedProvider.DIRECT_ROOT to listOf(PrivilegedProviderAcquisition.Acquired),
        )

        val frozen = requireFrozen(PrivilegedCapabilityPlanAdmission().admit(matrix, acquirer))

        assertEquals(PrivilegedProvider.DIRECT_ROOT, frozen.plan.inputProvider)
        assertEquals(listOf(PrivilegedProvider.DIRECT_SHELL), acquirer.releases)
        assertEquals(2, frozen.attempts.size)
        frozen.lease.close()
        assertEquals(
            listOf(
                PrivilegedProvider.DIRECT_SHELL,
                PrivilegedProvider.DIRECT_SHELL,
                PrivilegedProvider.DIRECT_ROOT,
            ),
            acquirer.releases,
        )
    }

    @Test
    fun `no alternative after acquisition failure becomes Host incompatible`() {
        val matrix = matrix(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
        )
        val acquirer = FakeAcquirer(
            PrivilegedProvider.DIRECT_SHELL to listOf(failed("launch_failed")),
        )

        val result = PrivilegedCapabilityPlanAdmission().admit(matrix, acquirer)

        val incompatible = result as? PrivilegedCapabilityPlanAdmissionResult.Incompatible
        assertTrue(incompatible != null)
        assertEquals(1, incompatible?.attempts?.size)
        assertEquals(
            PrivilegedProvider.DIRECT_SHELL,
            incompatible?.attempts?.single()?.failedProvider,
        )
    }

    @Test
    fun `failed plan is not retried without matrix change`() {
        val matrix = matrix(
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO, CapabilityQualificationState.VALIDATED),
            usable(
                PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.VALIDATED,
            ),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT, CapabilityQualificationState.VALIDATED),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )
        val acquirer = FakeAcquirer(
            PrivilegedProvider.DIRECT_SHELL to listOf(failed("connection_failed")),
            PrivilegedProvider.SHIZUKU to listOf(PrivilegedProviderAcquisition.Acquired),
        )

        val frozen = requireFrozen(PrivilegedCapabilityPlanAdmission().admit(matrix, acquirer))

        assertEquals(2, frozen.attempts.map { it.plan.assignments() }.distinct().size)
        assertEquals(1, acquirer.acquireCount(PrivilegedProvider.DIRECT_SHELL))
        frozen.lease.close()
    }

    @Test
    fun `provider failures terminate after the finite provider domain is exhausted`() {
        val matrix = matrix(
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO, CapabilityQualificationState.VALIDATED),
            usable(
                PrivilegedProvider.DIRECT_ROOT,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.VALIDATED,
            ),
            usable(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT, CapabilityQualificationState.VALIDATED),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.VIDEO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.SYSTEM_AUDIO),
            usable(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
        )
        val acquirer = FakeAcquirer(
            PrivilegedProvider.DIRECT_ROOT to listOf(failed("root_failed")),
            PrivilegedProvider.DIRECT_SHELL to listOf(failed("shell_failed")),
            PrivilegedProvider.SHIZUKU to listOf(failed("shizuku_failed")),
        )

        val result = PrivilegedCapabilityPlanAdmission().admit(matrix, acquirer)

        val incompatible = result as? PrivilegedCapabilityPlanAdmissionResult.Incompatible
        assertTrue(incompatible != null)
        assertEquals(PrivilegedProvider.canonicalOrder.size, incompatible?.attempts?.size)
        assertEquals(0, acquirer.releases.size)
        assertFalse(result is PrivilegedCapabilityPlanAdmissionResult.Frozen)
    }

    private fun requireFrozen(
        result: PrivilegedCapabilityPlanAdmissionResult,
    ): PrivilegedCapabilityPlanAdmissionResult.Frozen =
        result as? PrivilegedCapabilityPlanAdmissionResult.Frozen ?: error("expected_frozen_plan")

    private fun matrix(vararg overrides: CapabilityQualification): PrivilegedCapabilityMatrix {
        val overrideByCoordinate = overrides.associateBy { it.provider to it.capability }
        return PrivilegedCapabilityMatrix.complete { provider, capability ->
            overrideByCoordinate[provider to capability]
                ?: unavailable(provider, capability, CapabilityQualificationState.NOT_AVAILABLE)
        }
    }

    private fun usable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState = CapabilityQualificationState.QUALIFIED,
    ): CapabilityQualification = CapabilityQualification.usable(
        provider,
        capability,
        state,
        "qualified_${provider.name}_${capability.name}",
        provenance(),
    )

    private fun unavailable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState,
    ): CapabilityQualification = CapabilityQualification.unavailable(
        provider,
        capability,
        state,
        "unavailable_${provider.name}_${capability.name}",
        provenance(),
    )

    private fun failed(reason: String) = PrivilegedProviderAcquisition.Failed(reason)

    private fun provenance() = CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE)

    private class FakeAcquirer(
        vararg scripted: Pair<PrivilegedProvider, List<PrivilegedProviderAcquisition>>,
    ) : PrivilegedProviderAcquirer {
        private val responses = scripted.associate { (provider, values) -> provider to ArrayDeque(values) }
        private val acquisitions = mutableListOf<PrivilegedProvider>()
        val releases = mutableListOf<PrivilegedProvider>()

        override fun acquire(provider: PrivilegedProvider): PrivilegedProviderAcquisition {
            acquisitions += provider
            val responsesForProvider = responses[provider]
            return if (responsesForProvider.isNullOrEmpty()) {
                PrivilegedProviderAcquisition.Acquired
            } else {
                responsesForProvider.removeFirst()
            }
        }

        override fun release(provider: PrivilegedProvider) {
            releases += provider
        }

        fun acquireCount(provider: PrivilegedProvider): Int = acquisitions.count { it == provider }
    }
}
