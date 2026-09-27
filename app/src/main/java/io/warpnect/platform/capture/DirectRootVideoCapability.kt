package io.warpnect.platform.capture

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

/** Passive explicit mirror/encoder/content qualification; never startup capture. */
internal object DirectRootVideoCapability {
    const val BACKEND_REVISION = "directroot-video-v1"

    @Volatile var bootstrapProvenance: RootBootstrapProvenance? = null
        private set

    @Volatile private var observed: CapabilityQualification? = null

    @Volatile private var owner: DirectRootRuntime? = null

    @Volatile private var selected: Long? = null

    fun registerLaboratoryImplementation() {
        bootstrapProvenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
    }

    fun bindRuntime(runtime: DirectRootRuntime?) {
        selected = null
        owner = runtime
    }

    fun restoreForHost(evidence: CapabilityQualification) {
        check(evidence.provider == PrivilegedProvider.DIRECT_ROOT && evidence.capability == PrivilegedCapability.VIDEO)
        check(
            evidence.state.isUsable &&
                evidence.provenance.source == CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE,
        )
        check(owner?.snapshot?.ready == true && evidence.provenance.runtimeGeneration == owner?.snapshot?.generation)
        check(evidence.provenance.providerVersion == bootstrapProvenance?.name)
        observed = evidence
    }

    fun retainForHost(): Boolean = owner?.snapshot?.let { it.ready && qualification(it).state.isUsable } == true

    fun selectForHost(enabled: Boolean): Boolean {
        selected = null
        if (!enabled) return true
        if (!retainForHost()) return false
        selected = owner?.snapshot?.generation
        return true
    }

    fun createSelectedGateway(onServiceDied: () -> Unit): PrivilegedCaptureGateway? {
        val generation = selected ?: return null
        val runtime = owner?.takeIf { it.snapshot.ready && it.snapshot.generation == generation }
            ?: error("root_video_selected_runtime_lost")
        return DirectRootVideoCaptureGateway(runtime, onServiceDied)
    }

    fun record(runtime: DirectRootSnapshot, contentObserved: Boolean): CapabilityQualification {
        require(runtime.ready && runtime.provenance == bootstrapProvenance)
        return CapabilityQualification(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.VIDEO,
            if (contentObserved) {
                CapabilityQualificationState.QUALIFIED
            } else {
                CapabilityQualificationState.QUALIFICATION_FAILED
            },
            if (contentObserved) "root_mirror_app_encoder_fixture_observed" else "root_video_content_not_observed",
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
                PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO,
                if (bootstrapProvenance == null) {
                    CapabilityQualificationState.NOT_IMPLEMENTED
                } else {
                    CapabilityQualificationState.DEFERRED
                },
                "root_video_explicit_content_qualification_required",
                CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER),
            )
}
