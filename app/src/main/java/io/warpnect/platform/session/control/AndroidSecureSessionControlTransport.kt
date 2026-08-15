package io.warpnect.platform.session.control

import android.os.SystemClock
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlProtectionRuntime
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.SessionProtectionError

/** Datagram I/O ownership is shared with the bootstrap router; this class never reads a socket itself. */
interface SecureSessionControlDatagramIo {
    fun setSecureControlListener(receiveContextId: Long, listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?)

    fun send(endpoint: HandshakeTransportEndpoint, bytes: ByteArray): Boolean
}

/** Cold-path WNCP transport: each send builds one SCL SessionControl packet and one fresh WNSD record. */
class AndroidSecureSessionControlTransport(
    private val datagramIo: SecureSessionControlDatagramIo,
    private val runtime: SessionControlProtectionRuntime,
    private val receiveContextId: Long,
    private val remoteEndpoint: HandshakeTransportEndpoint,
) : SecureSessionControlTransport {
    private val lock = Any()
    private var sequenceNumber = 0L
    private var listener: ((ByteArray) -> Unit)? = null
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
        if (!protected.isSuccess) return@synchronized protected
        sequenceNumber += 1
        if (!datagramIo.send(remoteEndpoint, requireNotNull(protected.protectedDatagram))) {
            return@synchronized SecureSessionControlSendResult(SessionProtectionError.CryptoFailure)
        }
        protected
    }

    private fun onProtectedDatagram(endpoint: HandshakeTransportEndpoint, datagram: ByteArray) {
        val delivered = synchronized(lock) {
            if (closed) return
            runtime.unprotectSessionControl(endpoint, datagram, SystemClock.elapsedRealtimeNanos() / 1_000L)
        }
        if (delivered.isSuccess) synchronized(lock) { listener }?.invoke(requireNotNull(delivered.payload))
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        listener = null
        datagramIo.setSecureControlListener(receiveContextId, null)
    }
}
