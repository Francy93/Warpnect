package io.warpnect.platform.session.pairing

import io.warpnect.session.pairing.PairingBootstrapProtocol
import io.warpnect.session.pairing.PairingTransport
import io.warpnect.session.pairing.PairingTransportEndpoint
import io.warpnect.session.pairing.PairingTransportSendResult
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Low-frequency LAN bootstrap transport. It owns a receive thread only while a PairingController
 * has an active pairing window or attempt. Borrowed discovery sockets remain owned by discovery.
 */
class AndroidDatagramPairingTransport private constructor(
    private val socket: DatagramSocket,
    private val ownsSocket: Boolean,
    private val onReleased: (() -> Unit)?,
) : PairingTransport {
    private val lock = Any()
    private var listener: ((PairingTransportEndpoint, ByteArray) -> Unit)? = null
    private var receiveThread: Thread? = null
    private var closed = false

    init {
        socket.soTimeout = RECEIVE_TIMEOUT_MS
    }

    override fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) = synchronized(lock) {
        if (closed) return@synchronized
        this.listener = listener
        if (listener != null) startReaderLocked()
    }

    override fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult {
        if (datagram.size > PairingBootstrapProtocol.MAX_DATAGRAM_BYTES || closed) {
            return PairingTransportSendResult.Failed
        }
        return try {
            val address = InetAddress.getByName(destination.host)
            socket.send(DatagramPacket(datagram, datagram.size, address, destination.port))
            PairingTransportSendResult.Sent
        } catch (_: SocketTimeoutException) {
            PairingTransportSendResult.WouldBlock
        } catch (_: SecurityException) {
            PairingTransportSendResult.Failed
        } catch (_: IOException) {
            PairingTransportSendResult.Failed
        }
    }

    override fun close() {
        val releaseImmediately = synchronized(lock) {
            if (closed) return
            closed = true
            listener = null
            if (ownsSocket) socket.close()
            receiveThread == null
        }
        if (releaseImmediately) onReleased?.invoke()
    }

    private fun startReaderLocked() {
        if (receiveThread != null || closed) return
        receiveThread = Thread(::receiveLoop, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        try {
            while (true) {
                val callback = synchronized(lock) {
                    if (closed) null else listener
                } ?: return
                val buffer = ByteArray(PairingBootstrapProtocol.MAX_DATAGRAM_BYTES + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    return
                } catch (_: IOException) {
                    return
                }
                val address = packet.address ?: continue
                callback(PairingTransportEndpoint(address.hostAddress, packet.port), packet.data.copyOf(packet.length))
            }
        } finally {
            val restart = synchronized(lock) {
                receiveThread = null
                !closed && !socket.isClosed && listener != null
            }
            if (restart) {
                synchronized(
                    lock,
                ) { startReaderLocked() }
            } else if (closed || socket.isClosed) onReleased?.invoke()
        }
    }

    companion object {
        private const val RECEIVE_TIMEOUT_MS: Int = 250
        private const val THREAD_NAME = "WarpnectPairingUdp"

        fun createEphemeral(): AndroidDatagramPairingTransport? = try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = false
                bind(InetSocketAddress(0))
            }
            AndroidDatagramPairingTransport(socket, ownsSocket = true, onReleased = null)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        fun borrow(socket: DatagramSocket, onReleased: () -> Unit): AndroidDatagramPairingTransport =
            AndroidDatagramPairingTransport(
                socket,
                ownsSocket = false,
                onReleased = onReleased,
            )
    }
}
