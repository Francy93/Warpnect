@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.platform.session.handshake

import io.warpnect.platform.session.control.SecureSessionControlDatagramIo
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeProtocol
import io.warpnect.session.handshake.SessionHandshakeTransport
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/** Dedicated initiator socket only. Advertised responder sockets use AndroidBootstrapDatagramRouter. */
class AndroidDatagramSessionHandshakeTransport private constructor(
    private val socket: DatagramSocket,
) : SessionHandshakeTransport, SecureSessionControlDatagramIo {
    private val lock = Any()
    private var listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
    private val secureControlListeners = LinkedHashMap<Long, (HandshakeTransportEndpoint, ByteArray) -> Unit>()
    private var reader: Thread? = null
    private var closed = false

    override fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) = synchronized(
        lock,
    ) {
        if (closed) return@synchronized
        this.listener = listener
        if (listener != null && reader == null) {
            reader = Thread(::readLoop, THREAD_NAME).apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean {
        if (datagram.size > SessionHandshakeProtocol.MAX_DATAGRAM_BYTES || closed) return false
        return try {
            socket.send(
                DatagramPacket(
                    datagram,
                    datagram.size,
                    InetAddress.getByAddress(endpoint.addressBytes()),
                    endpoint.port,
                ),
            )
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun setSecureControlListener(
        receiveContextId: Long,
        listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?,
    ) = synchronized(lock) {
        if (closed) return@synchronized
        if (listener == null) {
            secureControlListeners.remove(receiveContextId)
        } else {
            secureControlListeners[receiveContextId] = listener
            if (reader == null) {
                reader = Thread(::readLoop, THREAD_NAME).apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun readLoop() {
        try {
            while (!socket.isClosed) {
                val packet =
                    DatagramPacket(
                        ByteArray(SessionHandshakeProtocol.MAX_DATAGRAM_BYTES + 1),
                        SessionHandshakeProtocol.MAX_DATAGRAM_BYTES + 1,
                    )
                try {
                    socket.receive(packet)
                } catch (_: SocketException) {
                    return
                } catch (_: IOException) {
                    return
                }
                val endpoint = packet.address?.let { HandshakeTransportEndpoint.from(it.address, packet.port) } ?: continue
                val bytes = packet.data.copyOf(packet.length)
                if (bytes.size >= SECURE_HEADER_BYTES && bytes.copyOfRange(0, 4).contentEquals(SECURE_MAGIC)) {
                    synchronized(
                        lock,
                    ) { secureControlListeners[readU64(bytes, SECURE_CONTEXT_ID_OFFSET)] }?.invoke(endpoint, bytes)
                } else {
                    synchronized(lock) { listener }?.invoke(endpoint, bytes)
                }
            }
        } finally {
            synchronized(lock) { reader = null }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            listener = null
            secureControlListeners.clear()
            socket.close()
        }
    }

    companion object {
        private const val THREAD_NAME = "WarpnectSessionHandshakeUdp"
        private const val SECURE_HEADER_BYTES = 28
        private const val SECURE_CONTEXT_ID_OFFSET = 8
        private val SECURE_MAGIC =
            byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte())

        private fun readU64(bytes: ByteArray, offset: Int): Long = (0 until 8).fold(0L) { result, index ->
            (result shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }

        fun createEphemeral(): AndroidDatagramSessionHandshakeTransport? = try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = false
                bind(InetSocketAddress(0))
            }
            AndroidDatagramSessionHandshakeTransport(socket)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
