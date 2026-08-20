package io.warpnect.session.integration

import io.warpnect.session.SessionBounds
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.lifecycle.DisconnectReason

/**
 * Bounded Host-side ownership for fully integrated runtimes. RFC-005D/005H capacity remains the
 * admission authority; this registry prevents a second process-local "current session" owner.
 */
class HostSessionRuntimeRegistry(
    private val maxRuntimes: Int = SessionBounds.HARD_MAX_SESSIONS,
) : AutoCloseable {
    private val lock = Any()
    private val runtimes = linkedMapOf<SessionRuntimeKey, RunningSessionRuntime>()
    private var closed = false

    init {
        require(maxRuntimes in 1..SessionBounds.HARD_MAX_SESSIONS)
    }

    fun register(runtime: RunningSessionRuntime): SecureSessionIntegrationError = synchronized(lock) {
        if (closed) return@synchronized SecureSessionIntegrationError.Closed
        val key = SessionRuntimeKey(runtime.sessionId, runtime.generation)
        if (key in runtimes || runtimes.keys.any { it.sessionId == key.sessionId }) {
            return@synchronized SecureSessionIntegrationError.Busy
        }
        if (runtimes.size >= maxRuntimes) return@synchronized SecureSessionIntegrationError.RegistryCapacityExceeded
        runtimes[key] = runtime
        SecureSessionIntegrationError.None
    }

    fun remove(sessionId: SessionId, generation: SessionGeneration): RunningSessionRuntime? = synchronized(lock) {
        runtimes.remove(SessionRuntimeKey(sessionId, generation))
    }

    fun runtime(sessionId: SessionId): RunningSessionRuntime? = synchronized(lock) {
        runtimes.entries.firstOrNull { it.key.sessionId == sessionId }?.value
    }

    fun snapshotCount(): Int = synchronized(lock) { runtimes.size }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            runtimes.values.toList().also { runtimes.clear() }
        }
        toClose.asReversed().forEach { it.close(DisconnectReason.HostClosing) }
    }
}

data class SessionRuntimeKey(
    val sessionId: SessionId,
    val generation: SessionGeneration,
)

/** One Running owner for a generation. It delegates path/recovery control entirely to RFC-005H. */
class RunningSessionRuntime(
    val lifecycle: ManagedLifecycleSession,
    val pipeline: SessionPipelineRuntime,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    val sessionId: SessionId get() = lifecycle.sessionId
    val generation: SessionGeneration get() = lifecycle.generation

    fun advance() {
        lifecycle.advance()
    }

    fun close(reason: DisconnectReason) {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        // RFC-005H invokes pipeline safety hooks while protected SessionControl is still usable.
        lifecycle.gracefulDisconnect(reason)
        pipeline.close()
        lifecycle.close()
    }

    /** RFC-005H already owns fresh-generation handoff; do not send an old-generation notice. */
    fun disposeForReconnect() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        pipeline.close()
        lifecycle.close()
    }

    override fun close() = close(DisconnectReason.ApplicationStopping)
}
