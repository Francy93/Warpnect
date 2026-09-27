package io.warpnect.platform.audio.capture

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

/** Real normal-process OFF/ON ring evidence only; registration/readiness never starts capture. */
internal object DirectRootSystemAudioCapability {
    const val BACKEND_REVISION = "directroot-remote-submix-pcm-v1"

    @Volatile var bootstrapProvenance: RootBootstrapProvenance? = null
        private set

    @Volatile private var observed: CapabilityQualification? = null

    @Volatile private var owner: DirectRootRuntime? = null

    @Volatile private var selected: Long? = null

    @Volatile private var factory: ((DirectRootRuntime, () -> Unit) -> PrivilegedAudioCaptureGateway)? = null

    fun registerLaboratoryImplementation(
        create: ((DirectRootRuntime, () -> Unit) -> PrivilegedAudioCaptureGateway)? = null,
    ) {
        bootstrapProvenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
        factory = create
    }

    fun bindRuntime(runtime: DirectRootRuntime?) {
        selected = null
        owner = runtime
    }

    fun restoreForHost(evidence: CapabilityQualification) {
        check(
            evidence.provider == PrivilegedProvider.DIRECT_ROOT &&
                evidence.capability == PrivilegedCapability.SYSTEM_AUDIO,
        )
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
        if (!retainForHost() || factory == null) return false
        selected = owner?.snapshot?.generation
        return true
    }

    fun createSelectedGateway(onServiceDied: () -> Unit): PrivilegedAudioCaptureGateway? {
        val generation = selected ?: return null
        val runtime = owner?.takeIf { it.snapshot.ready && it.snapshot.generation == generation }
            ?: error("root_audio_selected_runtime_lost")
        return requireNotNull(factory).invoke(runtime, onServiceDied)
    }

    fun record(runtime: DirectRootSnapshot, realRingPcm: Boolean, reason: String): CapabilityQualification {
        require(runtime.ready && runtime.provenance == bootstrapProvenance)
        return CapabilityQualification(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.SYSTEM_AUDIO,
            if (realRingPcm) {
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
                PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO,
                if (bootstrapProvenance == null) {
                    CapabilityQualificationState.NOT_IMPLEMENTED
                } else {
                    CapabilityQualificationState.DEFERRED
                },
                "root_audio_explicit_ring_signal_qualification_required",
                CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER),
            )
}
