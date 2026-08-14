@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.platform.session.handshake

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
) : SessionHandshakeTransport {
    private val lock = Any()
    private var listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
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
                synchronized(lock) { listener }?.invoke(endpoint, packet.data.copyOf(packet.length))
            }
        } finally {
            synchronized(lock) { reader = null }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            listener = null
            socket.close()
        }
    }

    companion object {
        private const val THREAD_NAME = "WarpnectSessionHandshakeUdp"
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
