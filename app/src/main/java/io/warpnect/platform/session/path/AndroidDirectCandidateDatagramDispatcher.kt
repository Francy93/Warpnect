package io.warpnect.platform.session.path

import io.warpnect.platform.session.control.SecureSessionControlDatagramIo
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicBoolean

/** One readiness-driven reader shared by all bounded Direct candidate sockets. */
class AndroidDirectCandidateDatagramDispatcher(
    private val maxSockets: Int = DEFAULT_MAX_SOCKETS,
) : AutoCloseable {
    private val lock = Any()
    private val selector = Selector.open()
    private val entries = LinkedHashSet<AndroidDirectCandidateDatagramIo>()
    private val closed = AtomicBoolean(false)
    private val thread = Thread(::runLoop, "WarpnectDirectPath").apply {
        isDaemon = true
        start()
    }

    fun open(localAddress: String): AndroidDirectCandidateDatagramIo? {
        if (closed.get() || localAddress.isBlank()) return null
        return try {
            val address = InetAddress.getByName(localAddress)
            val channel = DatagramChannel.open().apply {
                configureBlocking(false)
                bind(InetSocketAddress(address, 0))
            }
            val local = channel.localAddress as? InetSocketAddress
            if (local == null || local.port !in 1..0xffff) {
                channel.close()
                return null
            }
            synchronized(lock) {
                if (closed.get() || entries.size >= maxSockets) {
                    channel.close()
                    return null
                }
                selector.wakeup()
                val io = AndroidDirectCandidateDatagramIo(this, channel, local)
                channel.register(selector, SelectionKey.OP_READ, io)
                entries += io
                io
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun unregister(io: AndroidDirectCandidateDatagramIo) = synchronized(lock) {
        if (!entries.remove(io)) return@synchronized
        selector.wakeup()
        io.closeChannel()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val closing = synchronized(lock) {
            selector.wakeup()
            entries.toList().also { entries.clear() }
        }
        closing.forEach(AndroidDirectCandidateDatagramIo::closeChannel)
        thread.join(2_000L)
        selector.close()
    }

    private fun runLoop() {
        while (!closed.get()) {
            try {
                selector.select()
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    val io = key.attachment() as? AndroidDirectCandidateDatagramIo ?: continue
                    if (!key.isValid || !key.isReadable) continue
                    for (ignored in 0 until MAX_DRAIN_PER_SELECT) {
                        if (!io.receiveOne()) break
                    }
                }
            } catch (_: Exception) {
                if (!closed.get()) Thread.yield()
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_SOCKETS = 16
        const val MAX_DRAIN_PER_SELECT = 8
    }
}

class AndroidDirectCandidateDatagramIo internal constructor(
    private val owner: AndroidDirectCandidateDatagramDispatcher,
    private val channel: DatagramChannel,
    localSocketAddress: InetSocketAddress,
) : SecureSessionControlDatagramIo, AutoCloseable {
    private val lock = Any()
    private val receiveBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM_BYTES)
    private val closed = AtomicBoolean(false)
    private var candidateListener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
    private var secureListener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
    private var secureContextId: Long? = null

    val localEndpoint: HandshakeTransportEndpoint = requireNotNull(
        HandshakeTransportEndpoint.from(localSocketAddress.address.address, localSocketAddress.port),
    )

    fun setCandidateListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) = synchronized(lock) {
        if (!closed.get()) candidateListener = listener
    }

    override fun setSecureControlListener(
        receiveContextId: Long,
        listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?,
    ) = synchronized(lock) {
        if (closed.get()) return@synchronized
        secureContextId = receiveContextId.takeIf { listener != null }
        secureListener = listener
        if (listener != null) candidateListener = null
    }

    override fun send(endpoint: HandshakeTransportEndpoint, bytes: ByteArray): Boolean {
        if (closed.get() || bytes.isEmpty() || bytes.size > MAX_DATAGRAM_BYTES) return false
        return try {
            val target = InetSocketAddress(InetAddress.getByAddress(endpoint.addressBytes()), endpoint.port)
            synchronized(channel) { channel.send(ByteBuffer.wrap(bytes), target) == bytes.size }
        } catch (_: Exception) {
            false
        }
    }

    internal fun receiveOne(): Boolean {
        if (closed.get()) return false
        return try {
            receiveBuffer.clear()
            val source = channel.receive(receiveBuffer) as? InetSocketAddress ?: return false
            val size = receiveBuffer.position()
            if (size !in 1..MAX_DATAGRAM_BYTES) return true
            receiveBuffer.flip()
            val bytes = ByteArray(size)
            receiveBuffer.get(bytes)
            val endpoint = HandshakeTransportEndpoint.from(source.address.address, source.port) ?: return true
            val listener = synchronized(lock) { candidateListener ?: secureListener }
            listener?.invoke(endpoint, bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) owner.unregister(this)
    }

    internal fun closeChannel() {
        closed.set(true)
        synchronized(lock) {
            candidateListener = null
            secureListener = null
            secureContextId = null
        }
        channel.close()
    }

    private companion object {
        const val MAX_DATAGRAM_BYTES = 1_200
    }
}
