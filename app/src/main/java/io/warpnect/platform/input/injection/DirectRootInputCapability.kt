package io.warpnect.platform.input.injection

import io.warpnect.input.injection.InputInjectionController
import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.DirectRootRuntimeRegistry
import io.warpnect.platform.privileged.runtime.DirectRootSnapshot
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance

/** Passive compatibility observations only. Reading this registry never injects an event. */
internal object DirectRootInputCapability {
    const val BACKEND_REVISION = "directroot-input-v1"

    @Volatile var bootstrapProvenance: RootBootstrapProvenance? = null
        private set

    @Volatile private var observed: CapabilityQualification? = null

    @Volatile private var runtimeOwner: DirectRootRuntime? = null

    @Volatile private var selectedGeneration: Long? = null

    fun bindRuntime(runtime: DirectRootRuntime?) {
        selectedGeneration = null
        runtimeOwner = runtime
    }

    fun retainForHost(): Boolean = runtimeOwner?.snapshot?.let { it.ready && qualification(it).state.isUsable } == true

    fun selectForHost(selected: Boolean): Boolean {
        if (!selected) {
            selectedGeneration = null
            return true
        }
        if (!retainForHost()) return false
        selectedGeneration = runtimeOwner?.snapshot?.generation
        return true
    }

    fun createSelectedController(): InputInjectionController? {
        val generation = selectedGeneration ?: return null
        val owner = runtimeOwner?.takeIf { it.snapshot.ready && it.snapshot.generation == generation }
            ?: error("root_input_selected_runtime_lost")
        return AndroidInputInjectionController.fromGatewayFactory { DirectRootInputInjectionGateway(owner) }
    }

    fun registerLaboratoryImplementation() {
        bootstrapProvenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
    }

    fun record(runtime: DirectRootSnapshot, targetObserved: Boolean, reason: String): CapabilityQualification {
        require(runtime.ready && runtime.provenance == bootstrapProvenance)
        return CapabilityQualification(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.INPUT,
            if (targetObserved) {
                CapabilityQualificationState.QUALIFIED
            } else {
                CapabilityQualificationState.QUALIFICATION_FAILED
            },
            reason,
            CapabilityQualificationProvenance(
                CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
                runtimeGeneration = runtime.generation,
                providerVersion = runtime.provenance?.name,
                implementationVersion = BACKEND_REVISION,
            ),
        ).also { observed = it }
    }

    fun qualification(
        runtime: DirectRootSnapshot = DirectRootRuntimeRegistry.runtimeSnapshot(),
    ): CapabilityQualification =
        observed?.takeIf { runtime.ready && it.provenance.runtimeGeneration == runtime.generation }
            ?: CapabilityQualification.unavailable(
                PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT,
                if (bootstrapProvenance == null) {
                    CapabilityQualificationState.NOT_AVAILABLE
                } else {
                    CapabilityQualificationState.DEFERRED
                },
                if (runtime.ready) "root_input_target_not_observed" else "root_input_runtime_unavailable",
                CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER),
            )
}
