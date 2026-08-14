package io.warpnect.platform.discovery

import io.warpnect.platform.session.pairing.AndroidDatagramPairingTransport
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseFactory
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseResult
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.PairingBootstrapContactEndpointLease
import io.warpnect.session.pairing.PairingTransport
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Reserves a real bootstrap UDP port without starting a receive loop or defining a contact protocol. */
class AndroidDiscoveryContactEndpointLeaseFactory : DiscoveryContactEndpointLeaseFactory {
    override fun acquire(): DiscoveryContactEndpointLeaseResult = try {
        val socket = DatagramSocket(null).apply {
            reuseAddress = false
            bind(InetSocketAddress(0))
        }
        DiscoveryContactEndpointLeaseResult(AndroidDiscoveryContactEndpointLease(socket))
    } catch (_: SecurityException) {
        DiscoveryContactEndpointLeaseResult(error = DiscoveryError.LanPermissionDenied)
    } catch (_: Exception) {
        DiscoveryContactEndpointLeaseResult(error = DiscoveryError.ContactEndpointUnavailable)
    }
}

private class AndroidDiscoveryContactEndpointLease(
    private val socket: DatagramSocket,
) : PairingBootstrapContactEndpointLease {
    private var closed = false
    private var borrowed = false

    override val port: Int
        get() = socket.localPort

    override fun borrowPairingTransport(): PairingTransport? = synchronized(this) {
        if (closed || borrowed) return@synchronized null
        borrowed = true
        AndroidDatagramPairingTransport.borrow(socket) {
            synchronized(this) { borrowed = false }
        }
    }

    override fun close() = synchronized(this) {
        closed = true
        socket.close()
    }
}
