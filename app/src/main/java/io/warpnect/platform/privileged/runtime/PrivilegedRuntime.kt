package io.warpnect.platform.privileged.runtime

/**
 * The finite privileged execution environments Warpnect may select for Host capabilities.
 *
 * DirectRoot is deliberately represented without an implementation. Keeping the kind visible
 * lets the resolver report the intended product order without pretending root is available.
 */
enum class PrivilegedRuntimeKind {
    DIRECT_ROOT,
    DIRECT_SHELL,
    SHIZUKU,
    NONE,
}

/** Runtime lifecycle state, independent from capability-specific Audio/Input/Video state. */
enum class PrivilegedRuntimeState {
    IDLE,
    ACQUIRING,
    READY,
    UNAVAILABLE,
    RELEASED,
}

/**
 * Fine-grained acquisition progress retained for telemetry and diagnostics.
 *
 * `PrivilegedRuntimeState` deliberately remains small because feature composition only needs to
 * distinguish readiness from terminal unavailability. This phase makes the Wireless Debugging
 * lifecycle truthful without making the UI depend on implementation details.
 */
enum class PrivilegedRuntimePhase {
    NOT_APPLICABLE,
    WIRELESS_DEBUGGING_DISABLED,
    UNPAIRED,
    DISCOVERING_ADB_ENDPOINT,
    CONNECTING_ADB,
    ADB_CONNECTED,
    LAUNCHING_RUNTIME,
    FEATURE_QUALIFICATION,
    RUNTIME_READY,
    FAILED,
    SHUTTING_DOWN,
    STOPPED,
}

/** Narrow, truthful reasons surfaced before Host capability negotiation starts. */
enum class PrivilegedRuntimeFailure {
    NONE,
    ROOT_NOT_IMPLEMENTED,
    DIRECTSHELL_NOT_ARMED,
    DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED,
    DIRECTSHELL_NOT_PAIRED,
    DIRECTSHELL_CONNECT_FAILED,
    DIRECTSHELL_ARTIFACT_INVALID,
    DIRECTSHELL_LAUNCH_FAILED,
    DIRECTSHELL_IDENTITY_INVALID,
    DIRECTSHELL_AUTHENTICATION_FAILED,
    DIRECTSHELL_FEATURE_QUALIFICATION_FAILED,
    DIRECTSHELL_RUNTIME_RELEASED,
    SHIZUKU_LEGACY_FALLBACK,
}

data class PrivilegedRuntimeIdentity(
    val uid: Int? = null,
    val gid: Int? = null,
    val selinuxContext: String? = null,
    val packageName: String? = null,
    val opPackageName: String? = null,
)

data class PrivilegedRuntimeSnapshot(
    val kind: PrivilegedRuntimeKind,
    val state: PrivilegedRuntimeState,
    val phase: PrivilegedRuntimePhase = PrivilegedRuntimePhase.NOT_APPLICABLE,
    val generation: Long? = null,
    val identity: PrivilegedRuntimeIdentity? = null,
    val failure: PrivilegedRuntimeFailure = PrivilegedRuntimeFailure.NONE,
    val detail: String = "",
) {
    val ready: Boolean
        get() = state == PrivilegedRuntimeState.READY && failure == PrivilegedRuntimeFailure.NONE

    companion object {
        fun shizukuFallback(): PrivilegedRuntimeSnapshot = PrivilegedRuntimeSnapshot(
            kind = PrivilegedRuntimeKind.SHIZUKU,
            state = PrivilegedRuntimeState.READY,
            phase = PrivilegedRuntimePhase.RUNTIME_READY,
            detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=DIRECTSHELL_NOT_ARMED",
        )

        fun none(failure: PrivilegedRuntimeFailure, detail: String): PrivilegedRuntimeSnapshot =
            PrivilegedRuntimeSnapshot(
                kind = PrivilegedRuntimeKind.NONE,
                state = PrivilegedRuntimeState.UNAVAILABLE,
                phase = PrivilegedRuntimePhase.FAILED,
                failure = failure,
                detail = detail,
            )
    }
}

/** Creates only a known Warpnect privileged runtime; it is never a generic shell launcher. */
fun interface PrivilegedRuntimeFactory {
    fun create(): PrivilegedRuntime
}

/** A purpose-specific privileged runtime, never a generic shell-command surface. */
interface PrivilegedRuntime : AutoCloseable {
    val kind: PrivilegedRuntimeKind

    fun acquireForHost(): PrivilegedRuntimeSnapshot

    fun releaseForHost()

    override fun close() = releaseForHost()
}

/**
 * Product-order resolver: DirectRoot (not implemented) -> DirectShell -> an explicitly retained
 * legacy Shizuku path during migration. A DirectShell acquisition failure is terminal and never
 * silently falls through to Shizuku.
 */
class PrivilegedRuntimeResolver(
    private val directShellRuntime: PrivilegedRuntime?,
    private val allowShizukuFallback: Boolean,
) {
    fun acquireForHost(): PrivilegedRuntimeSnapshot {
        val directShell = directShellRuntime
        if (directShell != null) return directShell.acquireForHost()
        if (allowShizukuFallback) return PrivilegedRuntimeSnapshot.shizukuFallback()
        return PrivilegedRuntimeSnapshot.none(
            PrivilegedRuntimeFailure.DIRECTSHELL_NOT_ARMED,
            "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=DIRECTSHELL_NOT_ARMED",
        )
    }
}

/**
 * Process-local composition registry.
 *
 * A build that registers an automatic DirectShell factory requires DirectShell for ordinary Host
 * acquisition and never falls through to Shizuku. Builds without that registration retain the
 * established Shizuku composition explicitly during the migration period.
 */
object PrivilegedRuntimeRegistry {
    private val lock = Any()
    private var directShellRuntime: PrivilegedRuntime? = null
    private var automaticDirectShellFactory: PrivilegedRuntimeFactory? = null
    private var directShellRequiredForHost = false
    private var latest = PrivilegedRuntimeSnapshot.shizukuFallback()

    /**
     * Registers a lazy DirectShell runtime for ordinary Host activation.
     *
     * Registration performs no ADB work and launches no shell process. The factory is invoked
     * only by [acquireForHost], after the user explicitly enables Host mode.
     */
    fun registerAutomaticDirectShell(factory: PrivilegedRuntimeFactory): Boolean = synchronized(lock) {
        if (automaticDirectShellFactory != null) return@synchronized false
        automaticDirectShellFactory = factory
        directShellRequiredForHost = true
        if (directShellRuntime == null) {
            latest = directShellIdleSnapshot("auto_registered_waiting_for_host")
        }
        true
    }

    fun installDirectShell(runtime: PrivilegedRuntime): Boolean = synchronized(lock) {
        if (runtime.kind != PrivilegedRuntimeKind.DIRECT_SHELL) return@synchronized false
        directShellRuntime?.takeIf { it !== runtime }?.releaseForHost()
        directShellRuntime = runtime
        directShellRequiredForHost = true
        latest = directShellIdleSnapshot("explicitly_registered_waiting_for_host")
        true
    }

    fun clearDirectShell(runtime: PrivilegedRuntime? = null) {
        val toRelease: PrivilegedRuntime? = synchronized(lock) {
            val current = directShellRuntime
            if (runtime != null && current !== runtime) return@synchronized null
            directShellRuntime = null
            latest = if (directShellRequiredForHost) {
                directShellIdleSnapshot("automatic_runtime_waiting_for_host")
            } else {
                PrivilegedRuntimeSnapshot.shizukuFallback()
            }
            current
        }
        toRelease?.releaseForHost()
    }

    fun acquireForHost(): PrivilegedRuntimeSnapshot {
        val selection = runtimeForHost()
        val result = selection.failure ?: PrivilegedRuntimeResolver(
            directShellRuntime = selection.runtime,
            allowShizukuFallback = !selection.directShellRequired,
        ).acquireForHost()
        synchronized(lock) { latest = result }
        return result
    }

    fun releaseForHost() {
        val runtime = synchronized(lock) { directShellRuntime }
        runtime?.releaseForHost()
        synchronized(lock) {
            latest = if (runtime == null) {
                if (directShellRequiredForHost) {
                    PrivilegedRuntimeSnapshot.none(
                        PrivilegedRuntimeFailure.DIRECTSHELL_RUNTIME_RELEASED,
                        "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=host_released",
                    ).copy(phase = PrivilegedRuntimePhase.STOPPED)
                } else {
                    PrivilegedRuntimeSnapshot.shizukuFallback()
                }
            } else {
                PrivilegedRuntimeSnapshot(
                    kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                    state = PrivilegedRuntimeState.RELEASED,
                    phase = PrivilegedRuntimePhase.STOPPED,
                    failure = PrivilegedRuntimeFailure.DIRECTSHELL_RUNTIME_RELEASED,
                    detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=released",
                )
            }
        }
    }

    fun snapshot(): PrivilegedRuntimeSnapshot = synchronized(lock) { latest }

    internal fun resetForTests() {
        val runtime = synchronized(lock) {
            val current = directShellRuntime
            directShellRuntime = null
            automaticDirectShellFactory = null
            directShellRequiredForHost = false
            latest = PrivilegedRuntimeSnapshot.shizukuFallback()
            current
        }
        runtime?.releaseForHost()
    }

    private data class RuntimeSelection(
        val runtime: PrivilegedRuntime?,
        val directShellRequired: Boolean,
        val failure: PrivilegedRuntimeSnapshot? = null,
    )

    private fun runtimeForHost(): RuntimeSelection {
        val existing = synchronized(lock) {
            RuntimeSelection(
                runtime = directShellRuntime,
                directShellRequired = directShellRequiredForHost,
            )
        }
        if (existing.runtime != null || !existing.directShellRequired) return existing

        val factory = synchronized(lock) { automaticDirectShellFactory }
            ?: return existing
        val created = runCatching { factory.create() }.getOrElse { error ->
            return RuntimeSelection(
                runtime = null,
                directShellRequired = true,
                failure = PrivilegedRuntimeSnapshot.none(
                    PrivilegedRuntimeFailure.DIRECTSHELL_LAUNCH_FAILED,
                    "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=factory_failed " +
                        "error=${error.javaClass.simpleName}",
                ),
            )
        }
        if (created.kind != PrivilegedRuntimeKind.DIRECT_SHELL) {
            runCatching { created.releaseForHost() }
            return RuntimeSelection(
                runtime = null,
                directShellRequired = true,
                failure = PrivilegedRuntimeSnapshot.none(
                    PrivilegedRuntimeFailure.DIRECTSHELL_LAUNCH_FAILED,
                    "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=factory_returned_${created.kind}",
                ),
            )
        }
        val installed = synchronized(lock) {
            directShellRuntime ?: created.also { directShellRuntime = it }
        }
        if (installed !== created) runCatching { created.releaseForHost() }
        return RuntimeSelection(installed, directShellRequired = true)
    }

    private fun directShellIdleSnapshot(detail: String) = PrivilegedRuntimeSnapshot(
        kind = PrivilegedRuntimeKind.DIRECT_SHELL,
        state = PrivilegedRuntimeState.IDLE,
        phase = PrivilegedRuntimePhase.NOT_APPLICABLE,
        failure = PrivilegedRuntimeFailure.NONE,
        detail = "direct_root=ROOT_NOT_IMPLEMENTED direct_shell=$detail",
    )
}
