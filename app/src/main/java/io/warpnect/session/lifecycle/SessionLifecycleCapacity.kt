package io.warpnect.session.lifecycle

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionManager

/** Keeps exactly one Host capacity slot across active lifecycle, recovery and its next handshake. */
interface SessionLifecycleCapacityOwner : AutoCloseable {
    fun promote(sessionId: SessionId, peerDeviceId: DeviceId, generation: SessionGeneration): Boolean
    fun beginRecovery(
        sessionId: SessionId,
        peerDeviceId: DeviceId,
        generation: SessionGeneration,
        recoveryWindowMs: Long,
    ): Boolean
    fun handoffToFreshGeneration()
    override fun close()
}

/** SessionManager-backed capacity ownership; it stores no traffic key or recovery secret. */
class SessionManagerLifecycleCapacityOwner(
    private val manager: SessionManager,
) : SessionLifecycleCapacityOwner {
    private enum class State { Authenticated, Lifecycle, Recovery, HandedOff, Closed }
    private var state = State.Authenticated
    private var sessionId: SessionId? = null

    override fun promote(sessionId: SessionId, peerDeviceId: DeviceId, generation: SessionGeneration): Boolean =
        synchronized(
            this,
        ) {
            if (state != State.Authenticated) return@synchronized false
            val result = manager.promoteAuthenticatedAdmissionToLifecycle(sessionId, peerDeviceId, generation)
            if (!result.isSuccess) return@synchronized false
            this.sessionId = sessionId
            state = State.Lifecycle
            true
        }

    override fun beginRecovery(
        sessionId: SessionId,
        peerDeviceId: DeviceId,
        generation: SessionGeneration,
        recoveryWindowMs: Long,
    ): Boolean = synchronized(this) {
        if (state != State.Lifecycle || this.sessionId != sessionId) return@synchronized false
        val result = manager.beginLifecycleRecovery(sessionId, peerDeviceId, generation, recoveryWindowMs * 1_000L)
        if (!result.isSuccess) return@synchronized false
        state = State.Recovery
        true
    }

    override fun handoffToFreshGeneration() = synchronized(this) {
        if (state == State.Recovery) state = State.HandedOff
    }

    override fun close() = synchronized(this) {
        if (state == State.Closed || state == State.HandedOff) return@synchronized
        sessionId?.let { id ->
            when (state) {
                State.Lifecycle -> manager.releaseLifecycleAdmission(id)
                State.Recovery -> manager.releaseRecoveryAdmission(id)
                State.Authenticated -> manager.releaseAuthenticatedAdmission(id)
                State.HandedOff, State.Closed -> Unit
            }
        }
        state = State.Closed
    }
}
