package io.warpnect.platform.privileged.planning

import android.util.Log
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeCapability
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFailure
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeRegistry
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot
import io.warpnect.platform.session.capability.AndroidLocalCapabilityCollector
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.PreparedHostCapabilityCollector
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration

private typealias OptionalProviderFactory =
    (AndroidCapabilityProbeSnapshot) -> OptionalPrivilegedCapabilityProvider

private fun defaultStagedQualification(): PrivilegedCapabilityStagedQualification =
    PrivilegedCapabilityStagedQualification()

/** Result of platform preflight before Host discovery is allowed to become visible. */
internal sealed interface AndroidHostPrivilegedCapabilityAdmissionResult {
    data class Admitted(
        val matrix: PrivilegedCapabilityMatrix,
        val plan: PrivilegedCapabilityPlan,
    ) : AndroidHostPrivilegedCapabilityAdmissionResult

    data class Unavailable(
        val matrix: PrivilegedCapabilityMatrix,
        val incompatible: PrivilegedCapabilityPlanResult.HostIncompatible,
    ) : AndroidHostPrivilegedCapabilityAdmissionResult
}

/**
 * Adapts the existing cold Android subsystem probes to RFC-006I's immutable matrix. It does not
 * add a new provider implementation: a DirectShell attempt qualifies only DirectShell, while a
 * legacy Shizuku attempt retains the already selected legacy composition without creating a new
 * fallback path.
 */
internal class AndroidHostPrivilegedCapabilityAdmission(
    private val androidCollector: AndroidLocalCapabilityCollector,
    private val preparedCollector: PreparedHostCapabilityCollector,
    private val optionalProviderFactory: OptionalProviderFactory,
    private val plannerAdmission: PrivilegedCapabilityPlanAdmission = PrivilegedCapabilityPlanAdmission(),
    private val stagedQualification: PrivilegedCapabilityStagedQualification = defaultStagedQualification(),
) : AutoCloseable {
    private val lock = Any()
    private var activeLease: PrivilegedCapabilityPlanLease? = null
    private var activeResult: AndroidHostPrivilegedCapabilityAdmissionResult.Admitted? = null

    fun prepare(runtime: PrivilegedRuntimeSnapshot): AndroidHostPrivilegedCapabilityAdmissionResult {
        synchronized(lock) { activeResult }?.let { return it }

        val snapshot = androidCollector.probeSnapshot(SessionRole.Host)
        val suppressed = if (runtime.kind == PrivilegedRuntimeKind.DIRECT_SHELL) {
            DebugDirectShellCapabilitySuppression.consume()
        } else {
            null
        }
        suppressed?.let {
            Log.w(TAG, "DIRECT_SHELL_CAPABILITY_DEBUG_SUPPRESSED capability=${it.name} one_shot=true")
        }
        val initialMatrix = AndroidPrivilegedCapabilityMatrixAdapter.build(
            runtime = runtime,
            snapshot = snapshot,
            directShellSuppressed = suppressed,
        )
        logMatrixBuilt(runtime, initialMatrix)

        val optionalProvider = if (
            runtime.kind == PrivilegedRuntimeKind.DIRECT_SHELL && runtime.ready
        ) {
            optionalProviderFactory(snapshot)
        } else {
            null
        }
        val initialSelection = PrivilegedCapabilityPlanner.select(initialMatrix)
        val staged = optionalProvider?.let { provider ->
            stagedQualification.completeWithOptionalProvider(initialMatrix, provider)
        } ?: StagedPrivilegedCapabilityQualificationResult(
            matrix = initialMatrix,
            initiallyComplete = initialSelection is PrivilegedCapabilityPlanResult.Complete,
            qualifiedCapabilities = emptySet(),
        )
        if (!staged.initiallyComplete) {
            Log.i(
                TAG,
                "privileged_shizuku_staged_qualification requested=${staged.qualifiedCapabilities.joinToString()}" +
                    " shizuku_started=${optionalProvider != null}",
            )
            logMatrixBuilt(runtime, staged.matrix)
        } else {
            Log.i(TAG, "privileged_shizuku_deferred reason=directshell_complete shizuku_started=false")
        }
        return freezeOrReport(runtime, snapshot, staged.matrix, optionalProvider)
    }

    /** Builds a truthful incompatible matrix when runtime acquisition itself failed. */
    fun unavailableFromRuntime(
        runtime: PrivilegedRuntimeSnapshot,
    ): AndroidHostPrivilegedCapabilityAdmissionResult.Unavailable {
        DebugDirectShellCapabilitySuppression.consume()?.let { capability ->
            Log.i(
                TAG,
                "directshell_debug_capability_suppression_discarded capability=${capability.name} " +
                    "reason=runtime_unavailable",
            )
        }
        val matrix = AndroidPrivilegedCapabilityMatrixAdapter.build(runtime, snapshot = null)
        logMatrixBuilt(runtime, matrix)
        val result = PrivilegedCapabilityPlanner.select(matrix) as? PrivilegedCapabilityPlanResult.HostIncompatible
            ?: error("unavailable_runtime_must_not_produce_complete_plan")
        logPlanUnavailable(result)
        releaseUnfrozenRuntime(runtime)
        return AndroidHostPrivilegedCapabilityAdmissionResult.Unavailable(matrix, result)
    }

    fun release() {
        val lease = synchronized(lock) {
            activeResult = null
            activeLease.also { activeLease = null }
        }
        preparedCollector.clearPreparedHost()
        lease?.close()
    }

    override fun close() = release()

    private fun freezeOrReport(
        runtime: PrivilegedRuntimeSnapshot,
        snapshot: AndroidCapabilityProbeSnapshot,
        matrix: PrivilegedCapabilityMatrix,
        optionalProvider: OptionalPrivilegedCapabilityProvider?,
    ): AndroidHostPrivilegedCapabilityAdmissionResult {
        val admission = plannerAdmission.admit(matrix, CurrentAttemptProviderAcquirer(runtime, optionalProvider))
        return when (admission) {
            is PrivilegedCapabilityPlanAdmissionResult.Frozen -> {
                if (!applyFrozenFeatureSelection(runtime, admission.plan)) {
                    admission.lease.close()
                    val failedMatrix = admission.matrix.invalidateProvider(
                        PrivilegedProvider.DIRECT_SHELL,
                        "directshell_feature_selection_failed",
                    )
                    val selection = PrivilegedCapabilityPlanner.select(failedMatrix)
                    val incompatible = selection as? PrivilegedCapabilityPlanResult.HostIncompatible
                        ?: error("feature_selection_failure_requires_replanning")
                    logPlanUnavailable(incompatible)
                    releaseUnfrozenRuntime(runtime)
                    return AndroidHostPrivilegedCapabilityAdmissionResult.Unavailable(failedMatrix, incompatible)
                }
                val admitted = AndroidHostPrivilegedCapabilityAdmissionResult.Admitted(
                    matrix = admission.matrix,
                    plan = admission.plan,
                )
                val replacedLease = synchronized(lock) {
                    val previous = activeLease
                    activeLease = admission.lease
                    activeResult = admitted
                    previous
                }
                replacedLease?.close()
                preparedCollector.prepareHost {
                    androidCollector.collect(
                        SessionRole.Host,
                        optionalProvider?.snapshotForPlan(admission.plan, snapshot) ?: snapshot,
                    )
                }
                logPlanSelected(admission.plan)
                admitted
            }

            is PrivilegedCapabilityPlanAdmissionResult.Incompatible -> {
                logReplanFailures(admission.attempts)
                logPlanUnavailable(admission.result)
                releaseUnfrozenRuntime(runtime)
                AndroidHostPrivilegedCapabilityAdmissionResult.Unavailable(
                    matrix = admission.matrix,
                    incompatible = admission.result,
                )
            }
        }
    }

    private fun releaseUnfrozenRuntime(runtime: PrivilegedRuntimeSnapshot) {
        if (runtime.kind == PrivilegedRuntimeKind.DIRECT_SHELL) {
            PrivilegedRuntimeRegistry.releaseForHost()
        }
    }

    private fun logMatrixBuilt(runtime: PrivilegedRuntimeSnapshot, matrix: PrivilegedCapabilityMatrix) {
        val summary = matrix.entries().joinToString(separator = ",") { cell ->
            "${cell.provider.name}.${cell.capability.name}=${cell.state.name}"
        }
        Log.i(
            TAG,
            "privileged_matrix_built runtime=${runtime.kind.name} generation=${runtime.generation ?: "none"} $summary",
        )
    }

    private fun logPlanSelected(plan: PrivilegedCapabilityPlan) {
        val shizukuSelected = PrivilegedProvider.SHIZUKU in plan.participatingProviders
        Log.i(
            TAG,
            "privileged_plan_selected privileged_plan_type=${plan.shape.name} " +
                "video_provider=${plan.videoProvider.name} " +
                "system_audio_provider=${plan.systemAudioProvider.name} " +
                "input_provider=${plan.inputProvider.name} shizuku_started=$shizukuSelected",
        )
    }

    private fun logPlanUnavailable(result: PrivilegedCapabilityPlanResult.HostIncompatible) {
        val missing = result.missingCapabilities.joinToString(separator = ",") { it.name }
        val reason = result.diagnostics.firstOrNull()?.reason ?: "no_qualified_provider"
        Log.w(TAG, "privileged_plan_unavailable missing_capability=$missing reason=$reason")
    }

    private fun logReplanFailures(attempts: List<PrivilegedCapabilityPlanAttempt>) {
        attempts.filter { it.failedProvider != null }.forEach { attempt ->
            Log.w(
                TAG,
                "privileged_plan_acquisition_failed failed_provider=${attempt.failedProvider?.name} " +
                    "failed_capability_or_runtime=${attempt.failureReason}",
            )
            Log.i(TAG, "privileged_plan_replan")
        }
    }

    /**
     * DirectShell has already been acquired and qualified before this preflight, so it is retained
     * rather than launched a second time. Shizuku enters only after staged qualification has found
     * it necessary for a missing capability.
     */
    private class CurrentAttemptProviderAcquirer(
        private val runtime: PrivilegedRuntimeSnapshot,
        private val optionalProvider: OptionalPrivilegedCapabilityProvider?,
    ) : PrivilegedProviderAcquirer {
        override fun acquire(provider: PrivilegedProvider): PrivilegedProviderAcquisition = when (provider) {
            PrivilegedProvider.DIRECT_SHELL -> retain(
                expectedKind = PrivilegedRuntimeKind.DIRECT_SHELL,
                reason = "directshell_runtime_not_retained",
            )

            PrivilegedProvider.SHIZUKU -> if (runtime.kind == PrivilegedRuntimeKind.SHIZUKU) {
                retain(
                    expectedKind = PrivilegedRuntimeKind.SHIZUKU,
                    reason = "shizuku_not_retained",
                )
            } else {
                optionalProvider?.acquire()
                    ?: PrivilegedProviderAcquisition.Failed("shizuku_not_staged_for_current_attempt")
            }

            PrivilegedProvider.DIRECT_ROOT -> PrivilegedProviderAcquisition.Failed("root_not_implemented")
        }

        override fun release(provider: PrivilegedProvider) {
            when (provider) {
                PrivilegedProvider.DIRECT_SHELL -> PrivilegedRuntimeRegistry.releaseForHost()
                PrivilegedProvider.SHIZUKU -> optionalProvider?.release()
                PrivilegedProvider.DIRECT_ROOT -> Unit
            }
        }

        private fun retain(expectedKind: PrivilegedRuntimeKind, reason: String): PrivilegedProviderAcquisition =
            if (runtime.ready && runtime.kind == expectedKind) {
                PrivilegedProviderAcquisition.Retained
            } else {
                PrivilegedProviderAcquisition.Failed(reason)
            }
    }

    private fun applyFrozenFeatureSelection(
        runtime: PrivilegedRuntimeSnapshot,
        plan: PrivilegedCapabilityPlan,
    ): Boolean {
        if (runtime.kind != PrivilegedRuntimeKind.DIRECT_SHELL) return true
        val selected = mapOf(
            PrivilegedRuntimeCapability.VIDEO to plan.videoProvider.runtimeKind(),
            PrivilegedRuntimeCapability.SYSTEM_AUDIO to plan.systemAudioProvider.runtimeKind(),
            PrivilegedRuntimeCapability.INPUT to plan.inputProvider.runtimeKind(),
        )
        return PrivilegedRuntimeRegistry.applyHostFeatureSelection(selected)
    }

    private fun PrivilegedProvider.runtimeKind(): PrivilegedRuntimeKind = when (this) {
        PrivilegedProvider.DIRECT_ROOT -> PrivilegedRuntimeKind.DIRECT_ROOT
        PrivilegedProvider.DIRECT_SHELL -> PrivilegedRuntimeKind.DIRECT_SHELL
        PrivilegedProvider.SHIZUKU -> PrivilegedRuntimeKind.SHIZUKU
    }

    private companion object {
        const val TAG = "WarpnectPrivilegedPlan"
    }
}

/** Converts existing actual subsystem observations into all nine explicit matrix coordinates. */
internal object AndroidPrivilegedCapabilityMatrixAdapter {
    fun build(
        runtime: PrivilegedRuntimeSnapshot,
        snapshot: AndroidCapabilityProbeSnapshot?,
        directShellSuppressed: PrivilegedCapability? = null,
    ): PrivilegedCapabilityMatrix = PrivilegedCapabilityMatrix.complete { provider, capability ->
        when (provider) {
            PrivilegedProvider.DIRECT_ROOT -> unavailable(
                provider,
                capability,
                CapabilityQualificationState.NOT_IMPLEMENTED,
                "direct_root_not_implemented",
                runtime,
            )

            PrivilegedProvider.DIRECT_SHELL -> directShellQualification(
                runtime,
                snapshot,
                capability,
                directShellSuppressed,
            )
            PrivilegedProvider.SHIZUKU -> shizukuQualification(runtime, snapshot, capability)
        }
    }

    private fun directShellQualification(
        runtime: PrivilegedRuntimeSnapshot,
        snapshot: AndroidCapabilityProbeSnapshot?,
        capability: PrivilegedCapability,
        directShellSuppressed: PrivilegedCapability?,
    ): CapabilityQualification {
        if (directShellSuppressed == capability) {
            return unavailable(
                PrivilegedProvider.DIRECT_SHELL,
                capability,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "debug_directshell_${capability.name.lowercase()}_suppressed",
                runtime,
            )
        }
        if (runtime.kind != PrivilegedRuntimeKind.DIRECT_SHELL || !runtime.ready) {
            return unavailable(
                PrivilegedProvider.DIRECT_SHELL,
                capability,
                unavailableState(runtime.failure),
                "directshell_${runtime.failure.name.lowercase()}",
                runtime,
            )
        }
        return qualificationFromSnapshot(PrivilegedProvider.DIRECT_SHELL, capability, snapshot, runtime)
    }

    private fun shizukuQualification(
        runtime: PrivilegedRuntimeSnapshot,
        snapshot: AndroidCapabilityProbeSnapshot?,
        capability: PrivilegedCapability,
    ): CapabilityQualification {
        if (runtime.kind == PrivilegedRuntimeKind.SHIZUKU && runtime.ready) {
            return qualificationFromSnapshot(PrivilegedProvider.SHIZUKU, capability, snapshot, runtime)
        }
        return unavailable(
            PrivilegedProvider.SHIZUKU,
            capability,
            CapabilityQualificationState.DEFERRED,
            "shizuku_deferred_current_attempt",
            runtime,
        )
    }

    private fun qualificationFromSnapshot(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        snapshot: AndroidCapabilityProbeSnapshot?,
        runtime: PrivilegedRuntimeSnapshot,
    ): CapabilityQualification {
        if (snapshot == null) {
            return unavailable(
                provider,
                capability,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "capability_probe_not_collected",
                runtime,
            )
        }
        return when (capability) {
            PrivilegedCapability.VIDEO -> videoQualification(provider, snapshot, runtime)
            PrivilegedCapability.SYSTEM_AUDIO -> systemAudioQualification(provider, snapshot, runtime)
            PrivilegedCapability.INPUT -> inputQualification(provider, snapshot, runtime)
        }
    }

    private fun videoQualification(
        provider: PrivilegedProvider,
        snapshot: AndroidCapabilityProbeSnapshot,
        runtime: PrivilegedRuntimeSnapshot,
    ): CapabilityQualification {
        val capture = snapshot.privilegedVideoCapture
        val captureReady = capture?.backendAvailable == true &&
            capture.privilegeState == CapturePrivilegeState.Ready &&
            capture.lastError == CaptureError.None
        if (!captureReady) {
            return unavailable(
                provider,
                PrivilegedCapability.VIDEO,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "video_capture_${capture?.lastError?.name ?: "NOT_PROBED"}",
                runtime,
            )
        }
        val encoder = snapshot.videoEncoder
        val encoderReady = encoder?.isSupported == true &&
            encoder.selectedCodec?.hardwareAcceleration == VideoEncoderHardwareAcceleration.Hardware &&
            encoder.support?.surfaceInputSupported == true
        return if (encoderReady) {
            usable(provider, PrivilegedCapability.VIDEO, "video_capture_and_encoder_qualified", runtime)
        } else {
            unavailable(
                provider,
                PrivilegedCapability.VIDEO,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "video_encoder_${encoder?.error?.name ?: "NOT_PROBED"}",
                runtime,
            )
        }
    }

    private fun systemAudioQualification(
        provider: PrivilegedProvider,
        snapshot: AndroidCapabilityProbeSnapshot,
        runtime: PrivilegedRuntimeSnapshot,
    ): CapabilityQualification {
        val capture = snapshot.systemAudioCapture
        val captureReady = capture?.available == true && capture.privilegedBackendAvailable
        if (!captureReady) {
            return unavailable(
                provider,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "system_audio_${capture?.lastError?.name ?: "NOT_PROBED"}",
                runtime,
            )
        }
        val encoder = snapshot.audioEncoder
        val encoderReady = encoder?.available == true &&
            encoder.support.codecSupported &&
            encoder.support.sampleRateSupported &&
            encoder.support.channelCountSupported &&
            encoder.support.frameDurationSupported
        return if (encoderReady) {
            usable(provider, PrivilegedCapability.SYSTEM_AUDIO, "system_audio_and_opus_qualified", runtime)
        } else {
            unavailable(
                provider,
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "opus_encoder_${encoder?.error?.name ?: "NOT_PROBED"}",
                runtime,
            )
        }
    }

    private fun inputQualification(
        provider: PrivilegedProvider,
        snapshot: AndroidCapabilityProbeSnapshot,
        runtime: PrivilegedRuntimeSnapshot,
    ): CapabilityQualification {
        val input = snapshot.inputInjection
        val available = input?.serviceAvailable == true &&
            input.backend != InputInjectionBackend.None &&
            input.keyInjectionSupported &&
            input.pointerInjectionSupported
        return if (available) {
            usable(provider, PrivilegedCapability.INPUT, "input_keyboard_and_pointer_qualified", runtime)
        } else {
            unavailable(
                provider,
                PrivilegedCapability.INPUT,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "input_${input?.lastError?.name ?: "NOT_PROBED"}",
                runtime,
            )
        }
    }

    private fun usable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        reason: String,
        runtime: PrivilegedRuntimeSnapshot,
    ): CapabilityQualification = CapabilityQualification.usable(
        provider = provider,
        capability = capability,
        state = CapabilityQualificationState.QUALIFIED,
        reason = reason,
        provenance = provenance(runtime),
    )

    private fun unavailable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState,
        reason: String,
        runtime: PrivilegedRuntimeSnapshot,
    ): CapabilityQualification = CapabilityQualification.unavailable(
        provider = provider,
        capability = capability,
        state = state,
        reason = reason,
        provenance = provenance(runtime),
    )

    private fun unavailableState(failure: PrivilegedRuntimeFailure): CapabilityQualificationState = when (failure) {
        PrivilegedRuntimeFailure.DIRECTSHELL_NOT_PAIRED -> CapabilityQualificationState.AUTHORIZATION_REQUIRED
        else -> CapabilityQualificationState.NOT_AVAILABLE
    }

    private fun provenance(runtime: PrivilegedRuntimeSnapshot): CapabilityQualificationProvenance =
        CapabilityQualificationProvenance(
            source = CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
            runtimeGeneration = runtime.generation,
        )
}
