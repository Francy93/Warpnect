@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.platform.privileged.planning

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.platform.audio.capture.AndroidSystemAudioCaptureController
import io.warpnect.platform.capture.ShizukuCaptureGateway
import io.warpnect.platform.input.injection.AndroidInputInjectionController
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimePhase
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku

/**
 * Lazily exposes the established Shizuku feature gateways to the staged provider matrix. This
 * adapter owns only transient qualification objects. The chosen Session feature controller owns
 * its normal UserService binding and closes it with its existing lifecycle.
 */
internal class ShizukuOptionalCapabilityProvider(
    context: Context,
    private val baseSnapshot: AndroidCapabilityProbeSnapshot,
) : OptionalPrivilegedCapabilityProvider {
    private val applicationContext = context.applicationContext
    private val qualified = linkedMapOf<PrivilegedCapability, CapabilityQualification>()
    private val observedSnapshots = linkedMapOf<PrivilegedCapability, AndroidCapabilityProbeSnapshot>()
    private var acquired = false

    override fun qualify(capabilities: Set<PrivilegedCapability>): List<CapabilityQualification> {
        if (capabilities.isEmpty()) return emptyList()
        when (val readiness = readiness()) {
            ShizukuReadiness.Ready -> Unit
            is ShizukuReadiness.Unavailable -> {
                return capabilities.map { capability ->
                    unavailable(capability, readiness.state, readiness.reason)
                }
            }
        }
        return capabilities.map { capability ->
            val observation = when (capability) {
                PrivilegedCapability.VIDEO -> qualifyVideo()
                PrivilegedCapability.SYSTEM_AUDIO -> qualifySystemAudio()
                PrivilegedCapability.INPUT -> qualifyInput()
            }
            qualified[capability] = observation
            observation
        }
    }

    override fun acquire(): PrivilegedProviderAcquisition {
        val ready = readiness()
        if (ready is ShizukuReadiness.Unavailable) {
            return PrivilegedProviderAcquisition.Failed(ready.reason)
        }
        if (qualified.values.none { it.state.isUsable }) {
            return PrivilegedProviderAcquisition.Failed("shizuku_no_qualified_selected_capability")
        }
        acquired = true
        Log.i(TAG, "SHIZUKU_OPTIONAL_PROVIDER_ACQUIRED qualified=${qualified.keys.joinToString()}")
        return PrivilegedProviderAcquisition.Acquired
    }

    override fun release() {
        if (!acquired) return
        acquired = false
        Log.i(TAG, "SHIZUKU_OPTIONAL_PROVIDER_RELEASED")
    }

    override fun snapshotForPlan(
        plan: PrivilegedCapabilityPlan,
        current: AndroidCapabilityProbeSnapshot,
    ): AndroidCapabilityProbeSnapshot {
        var selected = current
        plan.assignments()
            .filter { it.provider == PrivilegedProvider.SHIZUKU }
            .forEach { assignment ->
                val observed = observedSnapshots[assignment.capability] ?: return@forEach
                selected = when (assignment.capability) {
                    PrivilegedCapability.VIDEO -> selected.copy(privilegedVideoCapture = observed.privilegedVideoCapture)
                    PrivilegedCapability.SYSTEM_AUDIO -> selected.copy(systemAudioCapture = observed.systemAudioCapture)
                    PrivilegedCapability.INPUT -> selected.copy(inputInjection = observed.inputInjection)
                }
            }
        return selected
    }

    private fun qualifyVideo(): CapabilityQualification {
        val gateway = ShizukuCaptureGateway(applicationContext) {}
        return try {
            val capture = runBlocking { gateway.queryCapabilities() }
            baseSnapshot.copy(privilegedVideoCapture = capture).also {
                observedSnapshots[PrivilegedCapability.VIDEO] = it
            }.let { fromSnapshot(PrivilegedCapability.VIDEO, it) }
        } catch (error: RuntimeException) {
            unavailable(
                PrivilegedCapability.VIDEO,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "shizuku_video_${error.javaClass.simpleName}",
            )
        } finally {
            gateway.close()
        }
    }

    private fun qualifySystemAudio(): CapabilityQualification {
        val request = systemAudioRequest()
        val controller = AndroidSystemAudioCaptureController(applicationContext)
        return try {
            val capabilities = controller.queryCapabilities(request)
            if (!capabilities.available || !capabilities.privilegedBackendAvailable) {
                return observeAndQualify(PrivilegedCapability.SYSTEM_AUDIO, capabilities = capabilities)
            }
            val prepared = runBlocking { controller.prepare(request, DiscardPcmSink) }
            if (prepared.error != AudioCaptureError.None) {
                return unavailable(
                    PrivilegedCapability.SYSTEM_AUDIO,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "shizuku_audio_prepare_${prepared.error.name}",
                )
            }
            val started = runBlocking { controller.start() }
            if (started.error != AudioCaptureError.None) {
                return unavailable(
                    PrivilegedCapability.SYSTEM_AUDIO,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "shizuku_audio_start_${started.error.name}",
                )
            }
            val stopped = runBlocking { controller.stop() }
            if (stopped.error != AudioCaptureError.None) {
                return unavailable(
                    PrivilegedCapability.SYSTEM_AUDIO,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "shizuku_audio_stop_${stopped.error.name}",
                )
            }
            observeAndQualify(PrivilegedCapability.SYSTEM_AUDIO, capabilities = capabilities)
        } catch (error: RuntimeException) {
            unavailable(
                PrivilegedCapability.SYSTEM_AUDIO,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "shizuku_audio_${error.javaClass.simpleName}",
            )
        } finally {
            runCatching { controller.close() }
        }
    }

    private fun qualifyInput(): CapabilityQualification {
        val controller = AndroidInputInjectionController(applicationContext)
        return try {
            val capabilities = runBlocking { controller.queryCapabilities() }
            if (!capabilities.serviceAvailable || !capabilities.keyInjectionSupported || !capabilities.pointerInjectionSupported) {
                return observeAndQualify(PrivilegedCapability.INPUT, capabilities = capabilities)
            }
            val prepared = runBlocking { controller.prepare(InputInjectionConfig()) }
            if (prepared.serviceResult.error != InputInjectionError.None) {
                return unavailable(
                    PrivilegedCapability.INPUT,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "shizuku_input_prepare_${prepared.serviceResult.error.name}",
                )
            }
            val started = controller.start()
            if (started.serviceResult.error != InputInjectionError.None) {
                return unavailable(
                    PrivilegedCapability.INPUT,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "shizuku_input_start_${started.serviceResult.error.name}",
                )
            }
            val stopped = controller.stop()
            if (stopped.serviceResult.error != InputInjectionError.None) {
                return unavailable(
                    PrivilegedCapability.INPUT,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "shizuku_input_stop_${stopped.serviceResult.error.name}",
                )
            }
            observeAndQualify(PrivilegedCapability.INPUT, capabilities = capabilities)
        } catch (error: RuntimeException) {
            unavailable(
                PrivilegedCapability.INPUT,
                CapabilityQualificationState.QUALIFICATION_FAILED,
                "shizuku_input_${error.javaClass.simpleName}",
            )
        } finally {
            runCatching { controller.close() }
        }
    }

    private fun fromSnapshot(
        capability: PrivilegedCapability,
        snapshot: AndroidCapabilityProbeSnapshot,
    ): CapabilityQualification = AndroidPrivilegedCapabilityMatrixAdapter.build(
        runtime = shizukuRuntimeSnapshot,
        snapshot = snapshot,
    ).qualification(PrivilegedProvider.SHIZUKU, capability).copy(
        provenance = providerProvenance,
    )

    private fun observeAndQualify(
        capability: PrivilegedCapability,
        capabilities: io.warpnect.audio.capture.AudioCaptureCapabilities,
    ): CapabilityQualification = baseSnapshot.copy(systemAudioCapture = capabilities).also {
        observedSnapshots[capability] = it
    }.let { fromSnapshot(capability, it) }

    private fun observeAndQualify(
        capability: PrivilegedCapability,
        capabilities: io.warpnect.input.injection.InputInjectionCapabilities,
    ): CapabilityQualification = baseSnapshot.copy(inputInjection = capabilities).also {
        observedSnapshots[capability] = it
    }.let { fromSnapshot(capability, it) }

    private fun unavailable(
        capability: PrivilegedCapability,
        state: CapabilityQualificationState,
        reason: String,
    ): CapabilityQualification = CapabilityQualification.unavailable(
        provider = PrivilegedProvider.SHIZUKU,
        capability = capability,
        state = state,
        reason = reason.take(CapabilityQualification.MAX_REASON_LENGTH),
        provenance = providerProvenance,
    )

    private fun readiness(): ShizukuReadiness = try {
        when {
            !Shizuku.pingBinder() -> ShizukuReadiness.Unavailable(
                CapabilityQualificationState.NOT_AVAILABLE,
                "shizuku_binder_unavailable",
            )
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuReadiness.Unavailable(
                CapabilityQualificationState.AUTHORIZATION_REQUIRED,
                "shizuku_permission_required",
            )
            else -> ShizukuReadiness.Ready
        }
    } catch (_: RuntimeException) {
        ShizukuReadiness.Unavailable(CapabilityQualificationState.NOT_AVAILABLE, "shizuku_binder_unavailable")
    }

    private sealed interface ShizukuReadiness {
        data object Ready : ShizukuReadiness

        data class Unavailable(
            val state: CapabilityQualificationState,
            val reason: String,
        ) : ShizukuReadiness
    }

    private companion object {
        const val TAG = "WarpnectPrivilegedPlan"
        val providerProvenance = CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER)
        val shizukuRuntimeSnapshot = PrivilegedRuntimeSnapshot(
            kind = PrivilegedRuntimeKind.SHIZUKU,
            state = PrivilegedRuntimeState.READY,
            phase = PrivilegedRuntimePhase.RUNTIME_READY,
        )

        fun systemAudioRequest() = AudioCaptureRequest(
            source = AudioCaptureSource.SystemAudio,
            preferredSampleRateHz = 48_000,
            channelCount = 2,
            targetChunkDurationUs = 5_000L,
        )
    }

    private object DiscardPcmSink : PcmAudioSink {
        override fun onFormatChanged(format: AudioCaptureFormat) = Unit

        override fun onPcmChunk(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
        ) = Unit
    }
}
