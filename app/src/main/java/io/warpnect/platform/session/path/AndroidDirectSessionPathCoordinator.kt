package io.warpnect.platform.session.path

import android.os.SystemClock
import io.warpnect.platform.session.control.AndroidSecureSessionControlTransport
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.discovery.DiscoveryOpaqueRouteLocator
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.path.DirectPathValidationResult
import io.warpnect.session.path.DirectPathValidationWindow
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.setup.DirectPathClientRequest
import io.warpnect.session.setup.DirectPathCoordinator
import io.warpnect.session.setup.DirectPathHostRequest
import io.warpnect.session.setup.DirectPathLease
import io.warpnect.session.setup.DirectPathPreparationEvent
import io.warpnect.session.setup.PathFailureReason
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.SessionControlPathRebinder
import io.warpnect.session.setup.SessionSetupCodec
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SessionSetupHeader
import io.warpnect.session.setup.SessionSetupId
import io.warpnect.session.setup.SessionSetupMessage
import io.warpnect.session.setup.SessionSetupMessageType
import io.warpnect.session.setup.SessionSetupProtocol
import io.warpnect.session.setup.SetupPathCandidate
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

fun interface DirectPeerAddressResolver {
    fun resolve(route: DiscoveryOpaqueRouteLocator): String?
}

/**
 * Android P2P establishment plus authenticated WNSD candidate probing. All sockets use the actual
 * address of WifiP2pGroup.interface; no process-wide network binding or interface-name guess is used.
 */
class AndroidDirectSessionPathCoordinator(
    private val controller: AndroidDirectPathController,
    private val dispatcher: AndroidDirectCandidateDatagramDispatcher,
    private val secureControl: SecureSessionControlTransport,
    private val peerAddressResolver: DirectPeerAddressResolver,
    private val scheduler: ScheduledExecutorService,
    private val maxAttempts: Int = 8,
) : DirectPathCoordinator {
    private val lock = Any()
    private val attempts = LinkedHashMap<SessionSetupId, Attempt>()
    private var closed = false

    override fun prepareHost(request: DirectPathHostRequest, listener: (DirectPathPreparationEvent) -> Unit) {
        synchronized(lock) {
            if (!admit(request.setupId)) {
                listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
                return
            }
            attempts[request.setupId] = Attempt.Host(request, listener).also { attempt ->
                attempt.timeout = scheduler.schedule(
                    { fail(request.setupId, PathFailureReason.GroupCreationFailed) },
                    SessionSetupProtocol.DIRECT_SETUP_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
        controller.ensureHostGroup { result -> onHostGroup(request.setupId, result) }
    }

    override fun connectClient(request: DirectPathClientRequest, listener: (DirectPathPreparationEvent) -> Unit) {
        val route = DiscoveryOpaqueRouteLocator(request.routeToken)
        val peerAddress = peerAddressResolver.resolve(route)
        if (peerAddress.isNullOrBlank()) {
            listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
            return
        }
        synchronized(lock) {
            if (!admit(request.setupId)) {
                listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
                return
            }
            attempts[request.setupId] = Attempt.Client(request, listener).also { attempt ->
                attempt.timeout = scheduler.schedule(
                    { fail(request.setupId, PathFailureReason.ConnectionTimeout) },
                    SessionSetupProtocol.DIRECT_SETUP_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
        controller.connectClient(route, peerAddress) { result -> onClientConnection(request.setupId, result) }
    }

    override fun cancel(setupId: SessionSetupId) {
        synchronized(lock) { attempts.remove(setupId) }?.close()
    }

    override fun close() {
        val closing = synchronized(lock) {
            if (closed) return
            closed = true
            attempts.values.toList().also { attempts.clear() }
        }
        closing.forEach(Attempt::close)
    }

    private fun onHostGroup(setupId: SessionSetupId, result: AndroidDirectResult) {
        if (result == AndroidDirectResult.RequestAccepted) return
        val attempt = synchronized(lock) { attempts[setupId] as? Attempt.Host } ?: return
        when (result) {
            is AndroidDirectResult.HostGroupReady -> {
                val localAddress = directLocalAddress(result.interfaceName)
                val io = localAddress?.let(dispatcher::open)
                val groupLease = controller.acquireHostGroupLease()
                if (io == null || groupLease == null) {
                    io?.close()
                    groupLease?.close()
                    fail(setupId, PathFailureReason.GroupCreationFailed)
                    return
                }
                val window = DirectPathValidationWindow(
                    attempt.request.setupId.value,
                    attempt.request.profileHash,
                    attempt.request.pathAttemptId,
                    nowMs() + attempt.request.validationTimeoutMs,
                )
                attempt.io = io
                attempt.pathLease = CompositeDirectPathLease(io, groupLease)
                attempt.window = window
                io.setCandidateListener { source, datagram -> onHostCandidate(setupId, source, datagram) }
                attempt.timeout?.cancel(false)
                attempt.timeout = scheduler.schedule(
                    { fail(setupId, PathFailureReason.ProbeTimeout) },
                    attempt.request.validationTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
                attempt.listener(DirectPathPreparationEvent.HostReady(io.localEndpoint.port))
            }
            is AndroidDirectResult.Failure -> fail(setupId, result.reason)
            else -> Unit
        }
    }

    private fun onClientConnection(setupId: SessionSetupId, result: AndroidDirectResult) {
        if (result == AndroidDirectResult.RequestAccepted) return
        val attempt = synchronized(lock) { attempts[setupId] as? Attempt.Client } ?: return
        when (result) {
            is AndroidDirectResult.ClientConnected -> {
                val localAddress = directLocalAddress(result.interfaceName)
                val io = localAddress?.let(dispatcher::open)
                val groupLease = controller.acquireClientGroupLease()
                val host = endpoint(result.groupOwnerAddress, attempt.request.hostProbePort)
                if (io == null || groupLease == null || host == null) {
                    io?.close()
                    groupLease?.close()
                    fail(setupId, PathFailureReason.ConnectFailed)
                    return
                }
                attempt.io = io
                attempt.pathLease = CompositeDirectPathLease(io, groupLease)
                attempt.expectedHost = host
                attempt.window = DirectPathValidationWindow(
                    attempt.request.setupId.value,
                    attempt.request.profileHash,
                    attempt.request.pathAttemptId,
                    nowMs() + attempt.request.validationTimeoutMs,
                )
                io.setCandidateListener { source, datagram -> onClientCandidate(setupId, source, datagram) }
                sendClientProbe(attempt, 0)
            }
            is AndroidDirectResult.Failure -> fail(setupId, result.reason)
            is AndroidDirectResult.HostGroupReady -> fail(setupId, PathFailureReason.UnexpectedGroupOwner)
            AndroidDirectResult.RequestAccepted -> Unit
        }
    }

    private fun onHostCandidate(setupId: SessionSetupId, source: HandshakeTransportEndpoint, datagram: ByteArray) {
        val attempt = synchronized(lock) { attempts[setupId] as? Attempt.Host } ?: return
        val unprotected = secureControl.unprotectCandidate(source, datagram, nowUs())
        val probe = unprotected.payload?.let(SessionSetupCodec::decode)?.message as? SessionSetupMessage.DirectPathProbe
            ?: return
        val accepted = attempt.window?.acceptProbe(probe, source, nowMs())
        if (accepted !is DirectPathValidationResult.Accepted) return
        val ack = SessionSetupMessage.DirectPathAck(
            SessionSetupHeader(SessionSetupMessageType.DirectPathAck, attempt.request.setupId, 0),
            attempt.request.profileHash.copyOf(),
            attempt.request.pathAttemptId,
        )
        val encoded = SessionSetupCodec.encode(ack) ?: return fail(setupId, PathFailureReason.ProbeAuthenticationFailed)
        val protected = secureControl.protectCandidate(encoded)
        if (!protected.isSuccess || attempt.io?.send(source, requireNotNull(protected.protectedDatagram)) != true) {
            fail(setupId, PathFailureReason.ProbeAuthenticationFailed)
            return
        }
        complete(
            setupId,
            SetupPathCandidate(
                attempt.request.targetPathId,
                NetworkPathKind.Direct,
                PathSocketBinding(
                    attempt.request.targetPathId,
                    NetworkPathKind.Direct,
                    addressString(requireNotNull(attempt.io).localEndpoint),
                ),
                addressString(source),
                source,
            ),
        )
    }

    private fun sendClientProbe(attempt: Attempt.Client, retryIndex: Int) {
        if (retryIndex >= PROBE_RETRY_DELAYS_MS.size) {
            fail(attempt.request.setupId, PathFailureReason.ProbeTimeout)
            return
        }
        val probe = SessionSetupMessage.DirectPathProbe(
            SessionSetupHeader(SessionSetupMessageType.DirectPathProbe, attempt.request.setupId, 0),
            attempt.request.profileHash.copyOf(),
            attempt.request.pathAttemptId,
        )
        val encoded = SessionSetupCodec.encode(probe)
            ?: return fail(attempt.request.setupId, PathFailureReason.ProbeAuthenticationFailed)
        val protected = secureControl.protectCandidate(encoded)
        val sent = protected.protectedDatagram?.let { bytes ->
            attempt.io?.send(requireNotNull(attempt.expectedHost), bytes)
        } == true
        if (!protected.isSuccess || !sent) {
            fail(attempt.request.setupId, PathFailureReason.ProbeAuthenticationFailed)
            return
        }
        attempt.timeout?.cancel(false)
        attempt.timeout = scheduler.schedule(
            {
                synchronized(lock) {
                    attempts[attempt.request.setupId] as? Attempt.Client
                }?.let { sendClientProbe(it, retryIndex + 1) }
            },
            PROBE_RETRY_DELAYS_MS[retryIndex],
            TimeUnit.MILLISECONDS,
        )
    }

    private fun onClientCandidate(setupId: SessionSetupId, source: HandshakeTransportEndpoint, datagram: ByteArray) {
        val attempt = synchronized(lock) { attempts[setupId] as? Attempt.Client } ?: return
        val unprotected = secureControl.unprotectCandidate(source, datagram, nowUs())
        val ack = unprotected.payload?.let(SessionSetupCodec::decode)?.message as? SessionSetupMessage.DirectPathAck
            ?: return
        val accepted = attempt.window?.expectedAck(ack, source, requireNotNull(attempt.expectedHost), nowMs())
        if (accepted !is DirectPathValidationResult.Accepted) return
        complete(
            setupId,
            SetupPathCandidate(
                attempt.request.targetPathId,
                NetworkPathKind.Direct,
                PathSocketBinding(
                    attempt.request.targetPathId,
                    NetworkPathKind.Direct,
                    addressString(requireNotNull(attempt.io).localEndpoint),
                ),
                addressString(source),
                source,
            ),
        )
    }

    private fun complete(setupId: SessionSetupId, candidate: SetupPathCandidate) {
        val attempt = synchronized(lock) { attempts.remove(setupId) } ?: return
        attempt.timeout?.cancel(false)
        attempt.window?.close()
        attempt.io?.setCandidateListener(null)
        val lease = attempt.detachLease() ?: return attempt.close()
        attempt.listener(DirectPathPreparationEvent.Validated(candidate, lease))
    }

    private fun fail(setupId: SessionSetupId, reason: PathFailureReason) {
        val attempt = synchronized(lock) { attempts.remove(setupId) } ?: return
        attempt.close()
        attempt.listener(DirectPathPreparationEvent.Failure(reason))
    }

    private fun admit(setupId: SessionSetupId): Boolean =
        !closed && attempts.size < maxAttempts && !attempts.containsKey(setupId)

    private fun directLocalAddress(interfaceName: String?): String? {
        if (interfaceName.isNullOrBlank()) return null
        return try {
            val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
            val addresses = Collections.list(networkInterface.inetAddresses)
                .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
            (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
                ?.substringBefore('%')
        } catch (_: Exception) {
            null
        }
    }

    private fun endpoint(address: String, port: Int): HandshakeTransportEndpoint? = try {
        HandshakeTransportEndpoint.from(InetAddress.getByName(address).address, port)
    } catch (_: Exception) {
        null
    }

    private fun addressString(endpoint: HandshakeTransportEndpoint): String =
        requireNotNull(InetAddress.getByAddress(endpoint.addressBytes()).hostAddress).substringBefore('%')

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

    private sealed class Attempt(
        open val listener: (DirectPathPreparationEvent) -> Unit,
    ) {
        var io: AndroidDirectCandidateDatagramIo? = null
        var pathLease: DirectPathLease? = null
        var window: DirectPathValidationWindow? = null
        var timeout: ScheduledFuture<*>? = null

        fun detachLease(): DirectPathLease? = pathLease.also { pathLease = null }

        fun close() {
            timeout?.cancel(false)
            timeout = null
            window?.close()
            window = null
            pathLease?.close()
            pathLease = null
            io = null
        }

        data class Host(
            val request: DirectPathHostRequest,
            override val listener: (DirectPathPreparationEvent) -> Unit,
        ) : Attempt(listener)

        data class Client(
            val request: DirectPathClientRequest,
            override val listener: (DirectPathPreparationEvent) -> Unit,
            var expectedHost: HandshakeTransportEndpoint? = null,
        ) : Attempt(listener)
    }

    private companion object {
        val PROBE_RETRY_DELAYS_MS = longArrayOf(100L, 250L, 500L, 1_000L, 2_000L)
    }
}

private class CompositeDirectPathLease(
    val datagramIo: AndroidDirectCandidateDatagramIo,
    private val groupLease: DirectPathLease,
) : DirectPathLease {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        datagramIo.close()
        groupLease.close()
    }
}

class AndroidSessionControlPathRebinder(
    private val transport: AndroidSecureSessionControlTransport,
) : SessionControlPathRebinder {
    override fun rebind(candidate: SetupPathCandidate, lease: DirectPathLease): SessionSetupError {
        val direct = lease as? CompositeDirectPathLease ?: return SessionSetupError.PathBindingFailed
        val endpoint = candidate.controlEndpoint ?: return SessionSetupError.EndpointMismatch
        return when (transport.rebindPath(direct.datagramIo, endpoint)) {
            SessionProtectionError.None -> SessionSetupError.None
            SessionProtectionError.EndpointMismatch -> SessionSetupError.EndpointMismatch
            SessionProtectionError.Closed -> SessionSetupError.Closed
            else -> SessionSetupError.DirectProbeAuthenticationFailed
        }
    }
}
