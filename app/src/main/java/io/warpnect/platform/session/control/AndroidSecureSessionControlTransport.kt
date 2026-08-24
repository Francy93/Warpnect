package io.warpnect.platform.session.control

import android.os.SystemClock
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlProtectionRuntime
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.telemetry.SessionControlNetworkTelemetry

/** Datagram I/O ownership is shared with the bootstrap router; this class never reads a socket itself. */
interface SecureSessionControlDatagramIo {
    fun setSecureControlListener(receiveContextId: Long, listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?)

    fun send(endpoint: HandshakeTransportEndpoint, bytes: ByteArray): Boolean
}

/** Cold-path WNCP transport: each send builds one SCL SessionControl packet and one fresh WNSD record. */
class AndroidSecureSessionControlTransport(
    datagramIo: SecureSessionControlDatagramIo,
    private val runtime: SessionControlProtectionRuntime,
    private val receiveContextId: Long,
    remoteEndpoint: HandshakeTransportEndpoint,
    private val telemetry: SessionControlNetworkTelemetry? = null,
) : SecureSessionControlTransport {
    private val lock = Any()
    private var datagramIo = datagramIo
    private var sequenceNumber = 0L
    private var listener: ((ByteArray) -> Unit)? = null
    private var remoteEndpoint = remoteEndpoint
    private var closed = false

    override val maxPayloadBytes: Int
        get() = runtime.maxPayloadBytes

    override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) = synchronized(lock) {
        if (closed) return@synchronized
        this.listener = listener
        datagramIo.setSecureControlListener(
            receiveContextId,
            if (listener == null) {
                null
            } else {
                { endpoint, datagram -> onProtectedDatagram(endpoint, datagram) }
            },
        )
    }

    override fun send(payload: ByteArray): SecureSessionControlSendResult = synchronized(lock) {
        if (closed) return@synchronized SecureSessionControlSendResult(SessionProtectionError.Closed)
        if (payload.size > maxPayloadBytes) {
            return@synchronized SecureSessionControlSendResult(
                SessionProtectionError.DatagramTooLarge,
            )
        }
        val protected = runtime.protectSessionControl(
            sequenceNumber,
            SystemClock.elapsedRealtimeNanos() / 1_000L,
            payload,
        )
        if (!protected.isSuccess) {
            telemetry?.protectError()
            return@synchronized protected
        }
        telemetry?.recordProduced()
        sequenceNumber += 1
        val datagram = requireNotNull(protected.protectedDatagram)
        if (!datagramIo.send(remoteEndpoint, datagram)) {
            telemetry?.udpSendError()
            return@synchronized SecureSessionControlSendResult(SessionProtectionError.CryptoFailure)
        }
        telemetry?.udpSent(datagram.size)
        protected
    }

    override fun protectCandidate(payload: ByteArray): SecureSessionControlSendResult = synchronized(lock) {
        if (closed) return@synchronized SecureSessionControlSendResult(SessionProtectionError.Closed)
        if (payload.size > maxPayloadBytes) {
            return@synchronized SecureSessionControlSendResult(SessionProtectionError.DatagramTooLarge)
        }
        val protected = runtime.protectSessionControl(
            sequenceNumber,
            SystemClock.elapsedRealtimeNanos() / 1_000L,
            payload,
        )
        if (protected.isSuccess) {
            telemetry?.recordProduced()
            sequenceNumber += 1
        } else {
            telemetry?.protectError()
        }
        protected
    }

    override fun unprotectCandidate(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ) = synchronized(lock) {
        if (closed) {
            io.warpnect.session.control.SessionControlUnprotectResult(SessionProtectionError.Closed)
        } else {
            runtime.unprotectCandidateSessionControl(sourceEndpoint, protectedDatagram, nowUs)
        }
    }

    override fun rebindRemoteEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError = synchronized(
        lock,
    ) {
        if (closed) return@synchronized SessionProtectionError.Closed
        val result = runtime.rebindSessionControlEndpoint(endpoint)
        if (result == SessionProtectionError.None) remoteEndpoint = endpoint
        result
    }

    fun rebindPath(
        newDatagramIo: SecureSessionControlDatagramIo,
        endpoint: HandshakeTransportEndpoint,
    ): SessionProtectionError = synchronized(lock) {
        if (closed) return@synchronized SessionProtectionError.Closed
        val result = runtime.rebindSessionControlEndpoint(endpoint)
        if (result != SessionProtectionError.None) return@synchronized result
        datagramIo.setSecureControlListener(receiveContextId, null)
        datagramIo = newDatagramIo
        remoteEndpoint = endpoint
        listener?.let {
            datagramIo.setSecureControlListener(receiveContextId) { source, datagram ->
                onProtectedDatagram(source, datagram)
            }
        }
        result
    }

    private fun onProtectedDatagram(endpoint: HandshakeTransportEndpoint, datagram: ByteArray) {
        telemetry?.udpReceived(datagram.size)
        val delivered = synchronized(lock) {
            if (closed) return
            runtime.unprotectSessionControl(endpoint, datagram, SystemClock.elapsedRealtimeNanos() / 1_000L)
        }
        telemetry?.recordUnprotectError(delivered.error)
        if (delivered.isSuccess) synchronized(lock) { listener }?.invoke(requireNotNull(delivered.payload))
    }

    override fun close() {
        val shouldCloseTelemetry = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                listener = null
                datagramIo.setSecureControlListener(receiveContextId, null)
                true
            }
        }
        if (shouldCloseTelemetry) telemetry?.close()
    }
}
