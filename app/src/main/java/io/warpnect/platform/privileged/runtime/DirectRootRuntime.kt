package io.warpnect.platform.privileged.runtime

import io.warpnect.platform.input.injection.RootInputControl
import java.util.concurrent.CopyOnWriteArrayList

enum class RootBootstrapProvenance {
    LABORATORY_EXTERNAL_ROOT,
    PRODUCTION_ROOT_MANAGER,
}

enum class DirectRootState {
    IMPLEMENTATION_UNAVAILABLE,
    PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED,
    LAB_BOOTSTRAP_AVAILABLE,
    BOOTSTRAPPING,
    HELPER_READY,
    AUTHORIZATION_REQUIRED,
    BOOTSTRAP_FAILED,
    HELPER_LOST,
    RELEASED,
}

data class RootHelperIdentity(
    val pid: Int,
    val uid: Int,
    val gid: Int,
    val selinuxContext: String,
    val api: Int,
    val generation: Long,
    val protocolVersion: Int,
    val implementation: String,
    val artifactSha256: String,
    val provenance: RootBootstrapProvenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT,
)

data class DirectRootSnapshot(
    val state: DirectRootState = DirectRootState.PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED,
    val generation: Long? = null,
    val provenance: RootBootstrapProvenance? = null,
    val identity: RootHelperIdentity? = null,
    val reason: String = "production_root_bootstrap_not_implemented",
) {
    val ready: Boolean
        get() = state == DirectRootState.HELPER_READY && identity?.uid == 0 && identity.gid == 0
}

/** Authenticated, finite control only. There is deliberately no command/file/launch operation. */
interface RootControlSession : AutoCloseable {
    val identity: RootHelperIdentity

    fun ping()

    fun shutdown()
}

/** Acquisition adapter; laboratory authorization is not production root-manager consent. */
interface RootRuntimeBootstrap {
    val provenance: RootBootstrapProvenance
    val generation: Long
    val expectedArtifactSha256: String

    fun acquire(): RootControlSession

    fun cancel()
}

/** Root authority owner, intentionally NOT a Host capability runtime or provider. */
class DirectRootRuntime(
    private val bootstrap: RootRuntimeBootstrap,
    private val publish: (DirectRootSnapshot) -> Unit = {},
) : AutoCloseable {
    private var session: RootControlSession? = null
    private var closed = false
    var snapshot = DirectRootSnapshot(
        state = DirectRootState.LAB_BOOTSTRAP_AVAILABLE,
        generation = bootstrap.generation,
        provenance = bootstrap.provenance,
        reason = "helper_absent",
    )
        private set

    @Synchronized
    fun acquireAuthority(): DirectRootSnapshot {
        check(!closed) { "root_runtime_closed" }
        if (snapshot.ready) return snapshot
        update(DirectRootState.BOOTSTRAPPING, "awaiting_authenticated_helper")
        var acquired: RootControlSession? = null
        try {
            acquired = bootstrap.acquire()
            val identity = acquired.identity
            require(identity.uid == 0 && identity.gid == 0 && identity.pid > 0) { "root_identity_invalid" }
            require(identity.generation == bootstrap.generation) { "root_generation_invalid" }
            require(identity.provenance == bootstrap.provenance) { "root_provenance_invalid" }
            require(identity.protocolVersion == PROTOCOL_VERSION) { "root_protocol_invalid" }
            require(identity.implementation == IMPLEMENTATION) { "root_implementation_invalid" }
            require(identity.artifactSha256 == bootstrap.expectedArtifactSha256) { "root_artifact_invalid" }
            acquired.ping()
            session = acquired
            snapshot = snapshot.copy(
                state = DirectRootState.HELPER_READY,
                identity = identity,
                reason = "authenticated_uid0",
            )
            publish(snapshot)
        } catch (error: Exception) {
            runCatching { acquired?.close() }
            bootstrap.cancel()
            update(DirectRootState.BOOTSTRAP_FAILED, error.javaClass.simpleName)
        }
        return snapshot
    }

    @Synchronized
    fun checkLiveness(): Boolean {
        if (!snapshot.ready) return false
        return try {
            session?.ping() ?: error("root_session_absent")
            true
        } catch (_: Exception) {
            runCatching { session?.close() }
            session = null
            bootstrap.cancel()
            update(DirectRootState.HELPER_LOST, "authenticated_connection_lost")
            false
        }
    }

    @Synchronized
    internal fun inputControl(generation: Long): RootInputControl? =
        (session as? RootInputControl).takeIf { !closed && snapshot.ready && snapshot.generation == generation }

    @Synchronized
    internal fun inputConnectionLost() {
        runCatching { session?.close() }
        session = null
        bootstrap.cancel()
        update(DirectRootState.HELPER_LOST, "authenticated_input_connection_lost")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            runCatching { session?.shutdown() }
        } finally {
            runCatching { session?.close() }
            session = null
            bootstrap.cancel()
            update(DirectRootState.RELEASED, "generation_released")
        }
    }

    private fun update(state: DirectRootState, reason: String) {
        snapshot = snapshot.copy(state = state, identity = null, reason = reason)
        publish(snapshot)
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        const val IMPLEMENTATION = "warpnect-root-input-v1"
    }
}

enum class RootRecoveryAction {
    RECOVER_DIRECT_SHELL,
    RECOVER_SHIZUKU,
    RECOVER_WARPNECT_HELPER,
}

data class RootRecoveryAuthoritySnapshot(
    val available: Boolean,
    val generation: Long?,
    val provenance: RootBootstrapProvenance?,
    /** No remediation operation exists in this slice. */
    val supportedActions: Set<RootRecoveryAction> = emptySet(),
)

fun interface RootRecoveryAuthority {
    fun snapshot(): RootRecoveryAuthoritySnapshot
}

/** Volatile observation only: no acquisition, no persistence, no RFC-006I provider registration. */
object DirectRootRuntimeRegistry : RootRecoveryAuthority {
    @Volatile
    private var latest = DirectRootSnapshot()
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    fun runtimeSnapshot(): DirectRootSnapshot = latest

    fun publish(snapshot: DirectRootSnapshot) {
        latest = snapshot
        observers.forEach { observer -> runCatching { observer() } }
    }

    fun observe(observer: () -> Unit): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    override fun snapshot() = RootRecoveryAuthoritySnapshot(latest.ready, latest.generation, latest.provenance)
}
