package io.warpnect.platform.discovery

import io.warpnect.session.discovery.DiscoveryContactEndpointLease
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseFactory
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseResult
import io.warpnect.session.discovery.DiscoveryError
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
) : DiscoveryContactEndpointLease {
    override val port: Int
        get() = socket.localPort

    override fun close() {
        socket.close()
    }
}
