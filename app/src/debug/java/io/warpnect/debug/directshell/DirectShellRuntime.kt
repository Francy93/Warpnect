package io.warpnect.debug.directshell

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureResult
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePermissionResult
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionResult
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.audio.capture.DebugSystemAudioCaptureOverride
import io.warpnect.platform.audio.capture.DebugSystemAudioCaptureOverrideRegistry
import io.warpnect.platform.capture.DebugCaptureGatewayOverride
import io.warpnect.platform.capture.DebugCaptureGatewayOverrideRegistry
import io.warpnect.platform.capture.PrivilegedCaptureGateway
import io.warpnect.platform.input.injection.DebugInputInjectionControllerOverride
import io.warpnect.platform.input.injection.DebugInputInjectionControllerOverrideRegistry
import io.warpnect.platform.privileged.runtime.PrivilegedRuntime
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFailure
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeIdentity
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimePhase
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeRegistry
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import io.warpnect.telemetry.AudioSenderTelemetry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * DEBUG integration owner for the validated self-ADB DirectShell feature servers.
 *
 * The runtime owns authorization and feature-server provisioning. It prewarms one fresh server
 * per feature before Host discovery begins, so exact Session validation never pays for an ADB
 * launch on the setup critical path. A consumed server is replaced after Session teardown.
 */
internal class DirectShellRuntime(context: Context) : PrivilegedRuntime {
    override val kind: PrivilegedRuntimeKind = PrivilegedRuntimeKind.DIRECT_SHELL

    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val activeLeases = LinkedHashSet<FeatureLease<*>>()
    private val prewarmedServers = LinkedHashMap<
        DirectShellSelfBootstrapContract.ServerRole,
        DirectShellSelfBootstrapController.DirectShellLaunchedServer,
        >()
    private var runtimeSnapshot = idleSnapshot()
    private var runtimeGeneration: Long? = null
    private var armed = false

    private val audioOverride = object : DebugSystemAudioCaptureOverride {
        override fun createController(context: Context, telemetry: AudioSenderTelemetry?): AudioCaptureController =
            RuntimeAudioController(this@DirectShellRuntime, context, telemetry)
    }

    private val inputOverride = object : DebugInputInjectionControllerOverride {
        override fun createController(context: Context): InputInjectionController =
            RuntimeInputController(this@DirectShellRuntime, context)
    }

    private val videoOverride = object : DebugCaptureGatewayOverride {
        override fun createGateway(context: Context, onServiceDied: () -> Unit): PrivilegedCaptureGateway =
            RuntimeCaptureGateway(this@DirectShellRuntime, context, onServiceDied)
    }

    override fun acquireForHost(): PrivilegedRuntimeSnapshot {
        synchronized(lock) {
            if (runtimeSnapshot.ready) return runtimeSnapshot
            runtimeSnapshot = runtimeSnapshot.copy(
                state = PrivilegedRuntimeState.ACQUIRING,
                phase = PrivilegedRuntimePhase.DISCOVERING_ADB_ENDPOINT,
                failure = PrivilegedRuntimeFailure.NONE,
                detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=self_adb_acquiring",
            )
        }

        val launch = DirectShellSelfBootstrapController(applicationContext).use { controller ->
            controller.bootstrapPreviouslyAuthorizedHostRuntime()
        }
        if (!launch.successful) {
            val failure = launchFailure(launch.result.classification)
            return updateUnavailable(failure, launch.result.detail)
        }
        val servers = requireNotNull(launch.servers)
        synchronized(lock) {
            runtimeSnapshot = runtimeSnapshot.copy(
                phase = PrivilegedRuntimePhase.FEATURE_QUALIFICATION,
                detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=self_adb_feature_qualification",
            )
        }
        val candidates = linkedMapOf(
            DirectShellSelfBootstrapContract.ServerRole.PcmSharedRing to servers.audio,
            DirectShellSelfBootstrapContract.ServerRole.InputSession to servers.input,
            DirectShellSelfBootstrapContract.ServerRole.VideoCapture to servers.video,
        )
        val qualificationFailure = candidates.entries.firstNotNullOfOrNull { (role, server) ->
            qualifyServer(role, server)
        }
        if (qualificationFailure != null) {
            candidates.forEach { (role, server) -> closeFeatureServer(role, server) }
            return updateUnavailable(
                PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED,
                qualificationFailure,
            )
        }

        val installed = DebugSystemAudioCaptureOverrideRegistry.install(audioOverride) &&
            DebugInputInjectionControllerOverrideRegistry.install(inputOverride) &&
            DebugCaptureGatewayOverrideRegistry.install(videoOverride)
        if (!installed) {
            DebugSystemAudioCaptureOverrideRegistry.clear(audioOverride)
            DebugInputInjectionControllerOverrideRegistry.clear(inputOverride)
            DebugCaptureGatewayOverrideRegistry.clear(videoOverride)
            candidates.forEach { (role, server) -> closeFeatureServer(role, server) }
            return updateUnavailable(
                PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED,
                "debug_feature_override_install_rejected",
            )
        }

        val identity = parseIdentity(launch.result.detail)
        return synchronized(lock) {
            val generation = nextRuntimeGeneration()
            armed = true
            runtimeGeneration = generation
            prewarmedServers.putAll(candidates)
            runtimeSnapshot = PrivilegedRuntimeSnapshot(
                kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                state = PrivilegedRuntimeState.READY,
                phase = PrivilegedRuntimePhase.RUNTIME_READY,
                generation = generation,
                identity = identity,
                detail = "runtime_kind=DIRECT_SHELL runtime_generation=$generation " +
                    "bootstrap=self_adb uid=2000 package=com.android.shell " +
                    "audio_backend=DIRECT_SHELL input_backend=DIRECT_SHELL " +
                    "video_backend=DIRECT_SHELL shizuku_audio=false shizuku_input=false " +
                    "shizuku_video=false direct_root=ROOT_NOT_IMPLEMENTED " +
                    "direct_shell=self_adb_ready ${launch.result.detail}",
            )
            Log.i(TAG, "DIRECT_SHELL_RUNTIME_READY ${runtimeSnapshot.detail}")
            runtimeSnapshot
        }
    }

    override fun releaseForHost() {
        val released = synchronized(lock) {
            if (!armed && activeLeases.isEmpty() && prewarmedServers.isEmpty()) return
            runtimeSnapshot = runtimeSnapshot.copy(
                state = PrivilegedRuntimeState.ACQUIRING,
                phase = PrivilegedRuntimePhase.SHUTTING_DOWN,
                failure = PrivilegedRuntimeFailure.NONE,
                detail = "runtime_kind=DIRECT_SHELL runtime_generation=${runtimeGeneration ?: "none"} " +
                    "direct_shell=host_shutting_down",
            )
            armed = false
            val currentLeases = activeLeases.toList()
            activeLeases.clear()
            val warmServers = prewarmedServers.toMap()
            prewarmedServers.clear()
            currentLeases to warmServers
        }
        DebugSystemAudioCaptureOverrideRegistry.clear(audioOverride)
        DebugInputInjectionControllerOverrideRegistry.clear(inputOverride)
        DebugCaptureGatewayOverrideRegistry.clear(videoOverride)
        released.first.asReversed().forEach { lease -> runCatching { lease.close() } }
        released.second.forEach { (role, server) -> closeFeatureServer(role, server) }
        synchronized(lock) {
            runtimeSnapshot = PrivilegedRuntimeSnapshot(
                kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                state = PrivilegedRuntimeState.RELEASED,
                phase = PrivilegedRuntimePhase.STOPPED,
                generation = runtimeGeneration,
                failure = PrivilegedRuntimeFailure.DIRECTSHELL_RUNTIME_RELEASED,
                detail = "runtime_kind=DIRECT_SHELL runtime_generation=${runtimeGeneration ?: "none"} " +
                    "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=host_released",
            )
        }
        Log.i(
            TAG,
            "DIRECT_SHELL_RUNTIME_RELEASED leases=${released.first.size} warm=${released.second.size}",
        )
    }

    fun snapshot(): PrivilegedRuntimeSnapshot = synchronized(lock) { runtimeSnapshot }

    fun createAudioController(context: Context, telemetry: AudioSenderTelemetry?): AudioCaptureController =
        RuntimeAudioController(this, context, telemetry)

    fun createInputController(context: Context): InputInjectionController = RuntimeInputController(this, context)

    fun createCaptureGateway(context: Context, onServiceDied: () -> Unit): PrivilegedCaptureGateway =
        RuntimeCaptureGateway(this, context, onServiceDied)

    private fun acquireAudioLease(
        context: Context,
        telemetry: AudioSenderTelemetry?,
    ): FeatureLease<AudioCaptureController>? {
        val role = DirectShellSelfBootstrapContract.ServerRole.PcmSharedRing
        val launched = takeFeatureServer(role) ?: return null
        val source = DirectShellSystemAudioRemoteE2eSource.create(
            launched.launch.port,
            launched.launch.generation,
            launched.launch.secret.clone(),
        ) ?: return rejectFeature(role, launched, "audio_source_invalid")
        val probe = source.probe()
        if (!probe.available) {
            source.clear()
            return rejectFeature(role, launched, "audio_${probe.error.name}_${probe.detail}")
        }
        val delegate = source.createController(context.applicationContext, telemetry)
        val lease = FeatureLease(delegate) { consumed ->
            runCatching { delegate.close() }
            source.clear()
            releaseFeatureServer(role, launched, consumed)
        }
        return registerLease(lease)
    }

    private fun acquireInputLease(context: Context): FeatureLease<InputInjectionController>? {
        val role = DirectShellSelfBootstrapContract.ServerRole.InputSession
        val launched = takeFeatureServer(role) ?: return null
        val source = DirectShellInputSessionSource.create(
            launched.launch.port,
            launched.launch.generation,
            launched.launch.secret.clone(),
        ) ?: return rejectFeature(role, launched, "input_source_invalid")
        val probe = source.probe()
        if (!probe.available) {
            source.clear()
            return rejectFeature(role, launched, "input_${probe.error.name}_${probe.detail}")
        }
        val delegate = source.createController(context.applicationContext)
        val lease = FeatureLease(delegate) { consumed ->
            runCatching { delegate.close() }
            source.clear()
            releaseFeatureServer(role, launched, consumed)
        }
        return registerLease(lease)
    }

    private fun acquireVideoLease(
        context: Context,
        onServiceDied: () -> Unit,
    ): FeatureLease<PrivilegedCaptureGateway>? {
        val role = DirectShellSelfBootstrapContract.ServerRole.VideoCapture
        val launched = takeFeatureServer(role) ?: return null
        val source = DirectShellVideoCaptureSource.create(
            launched.launch.port,
            launched.launch.generation,
            launched.launch.secret.clone(),
        ) ?: return rejectFeature(role, launched, "video_source_invalid")
        val probe = source.probe()
        if (!probe.available) {
            source.clear()
            return rejectFeature(role, launched, "video_${probe.error.name}_${probe.detail}")
        }
        val delegate = source.createGateway(context.applicationContext, onServiceDied)
        val lease = FeatureLease(delegate) { consumed ->
            runCatching { delegate.close() }
            source.clear()
            releaseFeatureServer(role, launched, consumed)
        }
        return registerLease(lease)
    }

    private fun takeFeatureServer(
        role: DirectShellSelfBootstrapContract.ServerRole,
    ): DirectShellSelfBootstrapController.DirectShellLaunchedServer? {
        synchronized(lock) {
            if (!armed || !runtimeSnapshot.ready) {
                Log.w(TAG, "DIRECT_SHELL_RUNTIME_FEATURE_BLOCKED role=${role.name} runtime_not_ready")
                return null
            }
            prewarmedServers.remove(role)?.let { server ->
                Log.i(
                    TAG,
                    "DIRECT_SHELL_RUNTIME_FEATURE_PREWARMED role=${role.name} generation=${server.launch.generation}",
                )
                return server
            }
        }
        Log.i(TAG, "DIRECT_SHELL_RUNTIME_FEATURE_REPLACEMENT role=${role.name}")
        val launch = DirectShellSelfBootstrapController(applicationContext).use { controller ->
            controller.bootstrapPreviouslyAuthorizedFeatureServer(role)
        }
        if (!launch.successful) {
            updateUnavailable(launchFailure(launch.result.classification), launch.result.detail)
            Log.w(
                TAG,
                "DIRECT_SHELL_RUNTIME_FEATURE_FAILED role=${role.name} result=${launch.result.classification}",
            )
            return null
        }
        val server = requireNotNull(launch.server)
        val qualificationFailure = qualifyServer(role, server)
        if (qualificationFailure != null) {
            closeFeatureServer(role, server)
            updateUnavailable(PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED, qualificationFailure)
            Log.w(TAG, "DIRECT_SHELL_RUNTIME_FEATURE_REJECTED role=${role.name} $qualificationFailure")
            return null
        }
        return server
    }

    private fun releaseFeatureServer(
        role: DirectShellSelfBootstrapContract.ServerRole,
        server: DirectShellSelfBootstrapController.DirectShellLaunchedServer,
        consumed: Boolean,
    ) {
        val retained = synchronized(lock) {
            if (!consumed && armed && runtimeSnapshot.ready && !prewarmedServers.containsKey(role)) {
                prewarmedServers[role] = server
                true
            } else {
                false
            }
        }
        if (retained) {
            Log.i(
                TAG,
                "DIRECT_SHELL_RUNTIME_FEATURE_RETAINED role=${role.name} generation=${server.launch.generation}",
            )
            return
        }
        closeFeatureServer(role, server)
        val replace = synchronized(lock) { armed && runtimeSnapshot.ready }
        if (replace) prewarmReplacement(role)
    }

    private fun prewarmReplacement(role: DirectShellSelfBootstrapContract.ServerRole) {
        val launch = DirectShellSelfBootstrapController(applicationContext).use { controller ->
            controller.bootstrapPreviouslyAuthorizedFeatureServer(role)
        }
        if (!launch.successful) {
            updateUnavailable(launchFailure(launch.result.classification), launch.result.detail)
            Log.w(
                TAG,
                "DIRECT_SHELL_RUNTIME_FEATURE_REPLACEMENT_FAILED " +
                    "role=${role.name} result=${launch.result.classification}",
            )
            return
        }
        val server = requireNotNull(launch.server)
        val qualificationFailure = qualifyServer(role, server)
        val retained = qualificationFailure == null && synchronized(lock) {
            if (armed && runtimeSnapshot.ready && !prewarmedServers.containsKey(role)) {
                prewarmedServers[role] = server
                true
            } else {
                false
            }
        }
        if (retained) {
            Log.i(
                TAG,
                "DIRECT_SHELL_RUNTIME_FEATURE_REPLACED role=${role.name} generation=${server.launch.generation}",
            )
        } else {
            closeFeatureServer(role, server)
            if (qualificationFailure != null) {
                updateUnavailable(
                    PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED,
                    qualificationFailure,
                )
                Log.w(
                    TAG,
                    "DIRECT_SHELL_RUNTIME_FEATURE_REPLACEMENT_REJECTED " +
                        "role=${role.name} $qualificationFailure",
                )
            }
        }
    }

    private fun qualifyServer(
        role: DirectShellSelfBootstrapContract.ServerRole,
        server: DirectShellSelfBootstrapController.DirectShellLaunchedServer,
    ): String? {
        return when (role) {
            DirectShellSelfBootstrapContract.ServerRole.PcmSharedRing -> {
                val source = DirectShellSystemAudioRemoteE2eSource.create(
                    server.launch.port,
                    server.launch.generation,
                    server.launch.secret.clone(),
                ) ?: return "audio_source_invalid"
                try {
                    source.probe().takeUnless { it.available }
                        ?.let { "audio_${it.error.name}_${it.detail}" }
                } finally {
                    source.clear()
                }
            }
            DirectShellSelfBootstrapContract.ServerRole.InputSession -> {
                val source = DirectShellInputSessionSource.create(
                    server.launch.port,
                    server.launch.generation,
                    server.launch.secret.clone(),
                ) ?: return "input_source_invalid"
                try {
                    source.probe().takeUnless { it.available }
                        ?.let { "input_${it.error.name}_${it.detail}" }
                } finally {
                    source.clear()
                }
            }
            DirectShellSelfBootstrapContract.ServerRole.VideoCapture -> {
                val source = DirectShellVideoCaptureSource.create(
                    server.launch.port,
                    server.launch.generation,
                    server.launch.secret.clone(),
                ) ?: return "video_source_invalid"
                try {
                    source.probe().takeUnless { it.available }
                        ?.let { "video_${it.error.name}_${it.detail}" }
                } finally {
                    source.clear()
                }
            }
            else -> "unsupported_feature_role_${role.name}"
        }
    }

    private fun closeFeatureServer(
        role: DirectShellSelfBootstrapContract.ServerRole,
        server: DirectShellSelfBootstrapController.DirectShellLaunchedServer,
    ) {
        runCatching {
            when (role) {
                DirectShellSelfBootstrapContract.ServerRole.PcmSharedRing ->
                    DirectShellSystemAudioRemoteE2eSource.create(
                        server.launch.port,
                        server.launch.generation,
                        server.launch.secret.clone(),
                    )?.let { source ->
                        source.shutdown()
                        source.clear()
                    }
                DirectShellSelfBootstrapContract.ServerRole.InputSession ->
                    DirectShellInputSessionSource.create(
                        server.launch.port,
                        server.launch.generation,
                        server.launch.secret.clone(),
                    )?.let { source ->
                        source.shutdown()
                        source.clear()
                    }
                DirectShellSelfBootstrapContract.ServerRole.VideoCapture ->
                    DirectShellVideoCaptureSource.create(
                        server.launch.port,
                        server.launch.generation,
                        server.launch.secret.clone(),
                    )?.let { source ->
                        source.shutdown()
                        source.clear()
                    }
                else -> Unit
            }
        }
        server.launch.secret.fill(0)
    }

    private fun <T> rejectFeature(
        role: DirectShellSelfBootstrapContract.ServerRole,
        launched: DirectShellSelfBootstrapController.DirectShellLaunchedServer,
        detail: String,
    ): FeatureLease<T>? {
        closeFeatureServer(role, launched)
        updateUnavailable(PrivilegedRuntimeFailure.DIRECTSHELL_FEATURE_QUALIFICATION_FAILED, detail)
        Log.w(TAG, "DIRECT_SHELL_RUNTIME_FEATURE_REJECTED role=${role.name} $detail")
        return null
    }

    private fun <T> registerLease(lease: FeatureLease<T>): FeatureLease<T>? = synchronized(lock) {
        if (!armed || !runtimeSnapshot.ready) {
            runCatching { lease.close() }
            null
        } else {
            activeLeases += lease
            lease
        }
    }

    private fun releaseLease(lease: FeatureLease<*>) {
        synchronized(lock) { activeLeases.remove(lease) }
        lease.close()
    }

    private fun updateUnavailable(failure: PrivilegedRuntimeFailure, detail: String): PrivilegedRuntimeSnapshot {
        return synchronized(lock) {
            runtimeSnapshot = PrivilegedRuntimeSnapshot(
                kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                state = PrivilegedRuntimeState.UNAVAILABLE,
                phase = failure.phase(),
                generation = runtimeGeneration,
                failure = failure,
                detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=$detail",
            )
            runtimeSnapshot
        }
    }

    private fun idleSnapshot(): PrivilegedRuntimeSnapshot = PrivilegedRuntimeSnapshot(
        kind = PrivilegedRuntimeKind.DIRECT_SHELL,
        state = PrivilegedRuntimeState.IDLE,
        phase = PrivilegedRuntimePhase.NOT_APPLICABLE,
        detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=registered_waiting_for_host",
    )

    private fun launchFailure(classification: String): PrivilegedRuntimeFailure = when {
        classification.contains("WIRELESS_DEBUGGING_DISABLED") ->
            PrivilegedRuntimeFailure.DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED
        classification.contains("PAIRING_REQUIRED") || classification.contains("AUTHORIZATION_REJECTED") ->
            PrivilegedRuntimeFailure.DIRECTSHELL_NOT_PAIRED
        classification.contains("ARTIFACT") -> PrivilegedRuntimeFailure.DIRECTSHELL_ARTIFACT_INVALID
        classification.contains("APP_PROCESS") || classification.contains("LAUNCH") ->
            PrivilegedRuntimeFailure.DIRECTSHELL_LAUNCH_FAILED
        classification.contains("SHELL_UID") || classification.contains("IDENTITY") ->
            PrivilegedRuntimeFailure.DIRECTSHELL_IDENTITY_INVALID
        classification.contains("HMAC") || classification.contains("AUTH") ->
            PrivilegedRuntimeFailure.DIRECTSHELL_AUTHENTICATION_FAILED
        else -> PrivilegedRuntimeFailure.DIRECTSHELL_CONNECT_FAILED
    }

    private fun parseIdentity(detail: String): PrivilegedRuntimeIdentity? {
        if (!detail.contains("shell_uid=2000")) return null
        return PrivilegedRuntimeIdentity(uid = 2000, gid = 2000, packageName = "com.android.shell")
    }

    private fun PrivilegedRuntimeFailure.phase(): PrivilegedRuntimePhase = when (this) {
        PrivilegedRuntimeFailure.DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED ->
            PrivilegedRuntimePhase.WIRELESS_DEBUGGING_DISABLED
        PrivilegedRuntimeFailure.DIRECTSHELL_NOT_PAIRED -> PrivilegedRuntimePhase.UNPAIRED
        else -> PrivilegedRuntimePhase.FAILED
    }

    private class FeatureLease<T>(
        val delegate: T,
        private val releaseAction: (consumed: Boolean) -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val consumed = AtomicBoolean(false)

        fun markConsumed() {
            consumed.set(true)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) releaseAction(consumed.get())
        }
    }

    private class RuntimeAudioController(
        private val runtime: DirectShellRuntime,
        private val context: Context,
        private val telemetry: AudioSenderTelemetry?,
    ) : AudioCaptureController {
        private val lock = Any()
        private var launchAttempted = false
        private var lease: FeatureLease<AudioCaptureController>? = null

        override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities =
            delegate()?.queryCapabilities(request) ?: unavailableAudioCapabilities()

        override suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult {
            val controller = delegate() ?: return unavailableAudioResult()
            synchronized(lock) { lease?.markConsumed() }
            return controller.prepare(request, sink)
        }

        override suspend fun start(): AudioCaptureResult = currentDelegate()?.start() ?: unavailableAudioResult()

        override suspend fun stop(): AudioCaptureResult = currentDelegate()?.stop() ?: unavailableAudioResult()

        override fun snapshot(): AudioCaptureSnapshot = currentDelegate()?.snapshot() ?: AudioCaptureSnapshot(
            source = AudioCaptureSource.SystemAudio,
            lastError = AudioCaptureError.PrivilegedServiceUnavailable,
        )

        override fun close() {
            val current = synchronized(lock) { lease.also { lease = null } }
            if (current != null) runtime.releaseLease(current)
        }

        private fun delegate(): AudioCaptureController? = synchronized(lock) {
            lease?.delegate ?: if (launchAttempted) {
                null
            } else {
                launchAttempted = true
                runtime.acquireAudioLease(context, telemetry)?.also { lease = it }?.delegate
            }
        }

        private fun currentDelegate(): AudioCaptureController? = synchronized(lock) { lease?.delegate }
    }

    private class RuntimeInputController(
        private val runtime: DirectShellRuntime,
        private val context: Context,
    ) : InputInjectionController {
        private val lock = Any()
        private var launchAttempted = false
        private var lease: FeatureLease<InputInjectionController>? = null

        override suspend fun queryCapabilities(): InputInjectionCapabilities =
            delegate()?.queryCapabilities() ?: unavailableInputCapabilities()

        override suspend fun requestPermission(): InputInjectionPermissionResult =
            delegate()?.requestPermission() ?: InputInjectionPermissionResult(InputInjectionError.ServiceUnavailable)

        override suspend fun prepare(config: io.warpnect.input.injection.InputInjectionConfig): InputInjectionResult {
            val controller = delegate() ?: return unavailableInputResult()
            synchronized(lock) { lease?.markConsumed() }
            return controller.prepare(config)
        }

        override fun start(): InputInjectionResult = currentDelegate()?.start() ?: unavailableInputResult()

        override fun injectKey(event: AndroidKeyInjectionEvent): InputInjectionResult =
            currentDelegate()?.injectKey(event) ?: unavailableInputResult()

        override fun injectTouch(event: AndroidTouchInjectionEvent): InputInjectionResult =
            currentDelegate()?.injectTouch(event) ?: unavailableInputResult()

        override fun injectPointer(event: AndroidPointerInjectionEvent): InputInjectionResult =
            currentDelegate()?.injectPointer(event) ?: unavailableInputResult()

        override fun injectJoystick(event: AndroidJoystickInjectionEvent): InputInjectionResult =
            currentDelegate()?.injectJoystick(event) ?: unavailableInputResult()

        override fun resetState(
            scope: InputResetScope,
            stateSlot: Int,
            reason: InputResetReason,
        ): InputInjectionResult = currentDelegate()?.resetState(scope, stateSlot, reason) ?: unavailableInputResult()

        override fun stop(): InputInjectionResult = currentDelegate()?.stop() ?: unavailableInputResult()

        override fun snapshot(): InputInjectionSnapshot = currentDelegate()?.snapshot() ?: unavailableInputSnapshot()

        override fun close() {
            val current = synchronized(lock) { lease.also { lease = null } }
            if (current != null) runtime.releaseLease(current)
        }

        private fun delegate(): InputInjectionController? = synchronized(lock) {
            lease?.delegate ?: if (launchAttempted) {
                null
            } else {
                launchAttempted = true
                runtime.acquireInputLease(context)?.also { lease = it }?.delegate
            }
        }

        private fun currentDelegate(): InputInjectionController? = synchronized(lock) { lease?.delegate }
    }

    private class RuntimeCaptureGateway(
        private val runtime: DirectShellRuntime,
        private val context: Context,
        private val onServiceDied: () -> Unit,
    ) : PrivilegedCaptureGateway {
        private val lock = Any()
        private var launchAttempted = false
        private var lease: FeatureLease<PrivilegedCaptureGateway>? = null

        override suspend fun queryCapabilities(): CaptureCapabilities =
            delegate()?.queryCapabilities() ?: unavailableCaptureCapabilities()

        override suspend fun requestPermission(): CapturePermissionResult =
            delegate()?.requestPermission() ?: CapturePermissionResult(CaptureError.PrivilegedServiceUnavailable)

        override suspend fun startCapture(request: CaptureRequest, target: Surface): CaptureError {
            val gateway = delegate() ?: return CaptureError.PrivilegedServiceUnavailable
            synchronized(lock) { lease?.markConsumed() }
            return gateway.startCapture(request, target)
        }

        override suspend fun updateCapture(request: CaptureRequest): CaptureError =
            currentDelegate()?.updateCapture(request) ?: CaptureError.PrivilegedServiceUnavailable

        override suspend fun stopCapture(): CaptureError =
            currentDelegate()?.stopCapture() ?: CaptureError.PrivilegedServiceUnavailable

        override fun snapshot(): CaptureSessionSnapshot = currentDelegate()?.snapshot() ?: CaptureSessionSnapshot(
            lastError = CaptureError.PrivilegedServiceUnavailable,
        )

        override fun close() {
            val current = synchronized(lock) { lease.also { lease = null } }
            if (current != null) runtime.releaseLease(current)
        }

        private fun delegate(): PrivilegedCaptureGateway? = synchronized(lock) {
            lease?.delegate ?: if (launchAttempted) {
                null
            } else {
                launchAttempted = true
                runtime.acquireVideoLease(context, onServiceDied)?.also { lease = it }?.delegate
            }
        }

        private fun currentDelegate(): PrivilegedCaptureGateway? = synchronized(lock) { lease?.delegate }
    }

    private companion object {
        const val TAG = "WarpnectDirectShellRuntime"
        val runtimeGenerationCounter = AtomicLong(0L)

        fun nextRuntimeGeneration(): Long = runtimeGenerationCounter.incrementAndGet()

        fun unavailableAudioCapabilities() = AudioCaptureCapabilities(
            source = AudioCaptureSource.SystemAudio,
            available = false,
            lastError = AudioCaptureError.PrivilegedServiceUnavailable,
        )

        fun unavailableAudioResult() = AudioCaptureResult(
            AudioCaptureError.PrivilegedServiceUnavailable,
            AudioCaptureSnapshot(
                source = AudioCaptureSource.SystemAudio,
                lastError = AudioCaptureError.PrivilegedServiceUnavailable,
            ),
        )

        fun unavailableInputCapabilities() = InputInjectionCapabilities(
            lastError = InputInjectionError.ServiceUnavailable,
        )

        fun unavailableInputSnapshot() = InputInjectionSnapshot(
            lastError = InputInjectionError.ServiceUnavailable,
        )

        fun unavailableInputResult() = InputInjectionResult(
            InputInjectionServiceResult.ServiceUnavailable,
            unavailableInputSnapshot(),
        )

        fun unavailableCaptureCapabilities() = CaptureCapabilities(
            privilegeState = CapturePrivilegeState.BackendUnavailable,
            backend = CaptureBackend.None,
            backendAvailable = false,
            supportedSourceDisplays = emptyList(),
            supportsDynamicProjection = false,
            platformApiLevel = Build.VERSION.SDK_INT,
            lastError = CaptureError.PrivilegedServiceUnavailable,
        )
    }
}

/** Explicit DEBUG arm/disarm surface; it does not launch shell code until Host acquisition. */
internal object DirectShellRuntimeArm {
    private val lock = Any()
    private var active: DirectShellRuntime? = null

    fun arm(context: Context): PrivilegedRuntimeSnapshot = synchronized(lock) {
        active?.let { return it.snapshot() }
        val runtime = DirectShellRuntime(context.applicationContext)
        check(PrivilegedRuntimeRegistry.installDirectShell(runtime)) { "directshell_runtime_install_rejected" }
        active = runtime
        runtime.snapshot()
    }

    fun disarm() {
        val runtime = synchronized(lock) { active.also { active = null } }
        PrivilegedRuntimeRegistry.clearDirectShell(runtime)
    }

    fun snapshot(): PrivilegedRuntimeSnapshot? = synchronized(lock) { active?.snapshot() }
}
