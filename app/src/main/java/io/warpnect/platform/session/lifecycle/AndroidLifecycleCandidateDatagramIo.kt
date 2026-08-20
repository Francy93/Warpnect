package io.warpnect.platform.session.lifecycle

import io.warpnect.platform.session.control.AndroidSecureSessionControlTransport
import io.warpnect.platform.session.path.AndroidDirectCandidateDatagramDispatcher
import io.warpnect.platform.session.path.AndroidDirectCandidateDatagramIo
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.lifecycle.LifecyclePathBinding
import io.warpnect.session.lifecycle.PathMigrationId
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded candidate-socket owner for RFC-005H. It uses the existing RFC-005G dispatcher for both
 * LAN and Direct local-address bindings, then transfers exactly one validated socket into the
 * existing protected SessionControl transport at migration commit.
 */
class AndroidLifecycleCandidateDatagramIo(
    private val dispatcher: AndroidDirectCandidateDatagramDispatcher,
    private val secureControl: AndroidSecureSessionControlTransport,
) : LifecycleCandidateDatagramIo, AutoCloseable {
    private val lock = Any()
    private val receiver = AtomicReference<((HandshakeTransportEndpoint, ByteArray, Long) -> Unit)?>()
    private val candidates = LinkedHashMap<PathMigrationId, Candidate>()
    private var activeCandidate: Candidate? = null
    private var closed = false

    fun setReceiver(listener: (HandshakeTransportEndpoint, ByteArray, Long) -> Unit) {
        receiver.set(listener)
    }

    override fun arm(binding: LifecyclePathBinding, migrationId: PathMigrationId, timeoutMs: Long): Boolean =
        synchronized(
            lock,
        ) {
            if (closed || !binding.isValid() || timeoutMs <= 0L || candidates.containsKey(migrationId)) {
                return@synchronized false
            }
            val io = dispatcher.open(binding.plan.localAddress) ?: return@synchronized false
            val candidate = Candidate(migrationId, binding, io)
            io.setCandidateListener { source, datagram ->
                receiver.get()?.invoke(source, datagram, android.os.SystemClock.elapsedRealtimeNanos() / 1_000L)
            }
            candidates[migrationId] = candidate
            true
        }

    override fun disarm(migrationId: PathMigrationId) {
        val closing = synchronized(lock) {
            val candidate = candidates.remove(migrationId) ?: return
            candidate.takeUnless { it === activeCandidate }
        }
        closing?.io?.close()
    }

    override fun send(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean {
        val candidate = synchronized(lock) {
            candidates.values.singleOrNull { it.binding == binding && it !== activeCandidate }
        } ?: return false
        return candidate.io.send(binding.remoteControlEndpoint, protectedDatagram)
    }

    /**
     * Moves the already authenticated SessionControl transport to the armed candidate socket.
     * The old binding remains usable until `rebindPath` has atomically installed this one.
     */
    fun commitActivePath(binding: LifecyclePathBinding): Boolean {
        val candidate = synchronized(lock) {
            candidates.values.singleOrNull { it.binding == binding && it !== activeCandidate }
        } ?: return false
        if (secureControl.rebindPath(candidate.io, binding.remoteControlEndpoint) !=
            io.warpnect.session.security.SessionProtectionError.None
        ) {
            return false
        }
        synchronized(lock) {
            if (closed || candidates.remove(candidate.migrationId) !== candidate) return false
            activeCandidate = candidate
        }
        return true
    }

    override fun close() {
        val closing = synchronized(lock) {
            if (closed) return
            closed = true
            (candidates.values + listOfNotNull(activeCandidate)).distinct().also {
                candidates.clear()
                activeCandidate = null
            }
        }
        closing.forEach { it.io.close() }
        receiver.set(null)
    }

    private data class Candidate(
        val migrationId: PathMigrationId,
        val binding: LifecyclePathBinding,
        val io: AndroidDirectCandidateDatagramIo,
    )
}
