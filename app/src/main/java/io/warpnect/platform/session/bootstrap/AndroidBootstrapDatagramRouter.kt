@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.platform.session.bootstrap

import io.warpnect.platform.session.control.SecureSessionControlDatagramIo
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeProtocol
import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.pairing.PairingBootstrapProtocol
import io.warpnect.session.pairing.PairingTransport
import io.warpnect.session.pairing.PairingTransportEndpoint
import io.warpnect.session.pairing.PairingTransportSendResult
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

/** One blocking reader for the advertised bootstrap socket; WNPB, WNSH and protected WNSD coexist. */
class AndroidBootstrapDatagramRouter(
    private val socket: DatagramSocket,
) : SecureSessionControlDatagramIo, AutoCloseable {
    private val lock = Any()
    private var pairingListener: ((PairingTransportEndpoint, ByteArray) -> Unit)? = null
    private var handshakeListener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
    private val secureControlListeners = LinkedHashMap<Long, (HandshakeTransportEndpoint, ByteArray) -> Unit>()
    private var reader: Thread? = null
    private var closed = false

    fun borrowPairingTransport(): PairingTransport = RoutedPairingTransport(this)
    fun borrowSessionHandshakeTransport(): SessionHandshakeTransport = RoutedSessionHandshakeTransport(this)

    fun setPairingListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) = synchronized(lock) {
        if (closed) return@synchronized
        pairingListener = listener
        startReaderLocked()
    }

    fun setHandshakeListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) = synchronized(lock) {
        if (closed) return@synchronized
        handshakeListener = listener
        startReaderLocked()
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
            startReaderLocked()
        }
    }

    fun send(host: String, port: Int, bytes: ByteArray): PairingTransportSendResult = try {
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
        PairingTransportSendResult.Sent
    } catch (
        _: IOException,
    ) {
        PairingTransportSendResult.Failed
    } catch (_: SecurityException) {
        PairingTransportSendResult.Failed
    }

    override fun send(endpoint: HandshakeTransportEndpoint, bytes: ByteArray): Boolean = try {
        socket.send(
            DatagramPacket(bytes, bytes.size, InetAddress.getByAddress(endpoint.addressBytes()), endpoint.port),
        )
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun startReaderLocked() {
        if (reader != null || closed) return
        reader = Thread(::readLoop, THREAD_NAME).apply {
            isDaemon = true
            start()
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
                if (packet.length < 4 || packet.length > SessionHandshakeProtocol.MAX_DATAGRAM_BYTES) continue
                val bytes = packet.data.copyOf(packet.length)
                val address = packet.address ?: continue
                val hostAddress = address.hostAddress ?: continue
                when {
                    bytes.copyOfRange(0, 4).contentEquals(PAIRING_MAGIC) -> synchronized(lock) {
                        pairingListener
                    }?.invoke(PairingTransportEndpoint(hostAddress, packet.port), bytes)
                    bytes.copyOfRange(
                        0,
                        4,
                    ).contentEquals(
                        SessionHandshakeProtocol.MAGIC,
                    ) -> HandshakeTransportEndpoint.from(
                        address.address,
                        packet.port,
                    )?.let { endpoint ->
                        synchronized(lock) {
                            handshakeListener
                        }?.invoke(endpoint, bytes)
                    }
                    bytes.copyOfRange(0, 4).contentEquals(SECURE_MAGIC) && packet.length >= SECURE_HEADER_BYTES -> {
                        val contextId = readU64(bytes, SECURE_CONTEXT_ID_OFFSET)
                        HandshakeTransportEndpoint.from(address.address, packet.port)?.let { endpoint ->
                            synchronized(lock) {
                                secureControlListeners[contextId]
                            }?.invoke(endpoint, bytes)
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) { reader = null }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pairingListener = null
            handshakeListener = null
            secureControlListeners.clear()
            socket.close()
        }
    }
    private companion object {
        const val THREAD_NAME = "WarpnectBootstrapUdp"
        const val SECURE_HEADER_BYTES = 28
        const val SECURE_CONTEXT_ID_OFFSET = 8
        val PAIRING_MAGIC: ByteArray =
            byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte())
        val SECURE_MAGIC: ByteArray =
            byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte())

        fun readU64(bytes: ByteArray, offset: Int): Long =
            (0 until 8).fold(0L) { result, index -> (result shl 8) or (bytes[offset + index].toLong() and 0xffL) }
    }
}

private class RoutedPairingTransport(private val router: AndroidBootstrapDatagramRouter) : PairingTransport {
    override fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) =
        router.setPairingListener(listener)
    override fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult =
        if (datagram.size > PairingBootstrapProtocol.MAX_DATAGRAM_BYTES) {
            PairingTransportSendResult.Failed
        } else {
            router.send(
                destination.host,
                destination.port,
                datagram,
            )
        }
    override fun close() {
        router.setPairingListener(null)
    }
}

private class RoutedSessionHandshakeTransport(
    private val router: AndroidBootstrapDatagramRouter,
) : SessionHandshakeTransport, SecureSessionControlDatagramIo {
    override fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) =
        router.setHandshakeListener(listener)
    override fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean =
        datagram.size <= SessionHandshakeProtocol.MAX_DATAGRAM_BYTES && router.send(endpoint, datagram)
    override fun close() {
        router.setHandshakeListener(null)
    }

    override fun setSecureControlListener(
        receiveContextId: Long,
        listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?,
    ) = router.setSecureControlListener(receiveContextId, listener)
}
