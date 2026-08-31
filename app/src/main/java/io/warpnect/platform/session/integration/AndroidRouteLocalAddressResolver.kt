package io.warpnect.platform.session.integration

import io.warpnect.session.handshake.HandshakeTransportEndpoint
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

/** Resolves the concrete LAN source address selected by the kernel for a control-path endpoint. */
internal class AndroidRouteLocalAddressResolver(
    private val channelFactory: RouteAddressChannelFactory = RouteAddressChannelFactory {
        DatagramRouteAddressChannel(DatagramChannel.open())
    },
) {
    fun resolve(endpoint: HandshakeTransportEndpoint): String? =
        (resolveDetailed(endpoint) as? RouteLocalAddressResolution.Resolved)?.address

    fun resolveDetailed(endpoint: HandshakeTransportEndpoint): RouteLocalAddressResolution {
        val channel = try {
            channelFactory.open()
        } catch (_: Throwable) {
            return RouteLocalAddressResolution.ChannelOpenFailed
        }
        return try {
            channel.connect(InetAddress.getByAddress(endpoint.addressBytes()), endpoint.port)
            val address = channel.localAddress() ?: return RouteLocalAddressResolution.LocalAddressUnavailable
            when (address) {
                "0.0.0.0" -> RouteLocalAddressResolution.Wildcard(RouteAddressFamily.Ipv4)
                "::" -> RouteLocalAddressResolution.Wildcard(RouteAddressFamily.Ipv6)
                else -> RouteLocalAddressResolution.Resolved(address, address.toRouteAddressFamily())
            }
        } catch (_: Throwable) {
            RouteLocalAddressResolution.ConnectFailed
        } finally {
            runCatching { channel.close() }
        }
    }
}

/** Safe route-selection outcome; it deliberately never carries a remote endpoint. */
internal sealed interface RouteLocalAddressResolution {
    data class Resolved(val address: String, val family: RouteAddressFamily) : RouteLocalAddressResolution

    data class Wildcard(val family: RouteAddressFamily) : RouteLocalAddressResolution

    data object ChannelOpenFailed : RouteLocalAddressResolution

    data object ConnectFailed : RouteLocalAddressResolution

    data object LocalAddressUnavailable : RouteLocalAddressResolution
}

internal enum class RouteAddressFamily {
    Ipv4,
    Ipv6,
}

private fun String.toRouteAddressFamily(): RouteAddressFamily =
    if (contains(':')) RouteAddressFamily.Ipv6 else RouteAddressFamily.Ipv4

internal fun interface RouteAddressChannelFactory {
    fun open(): RouteAddressChannel
}

internal interface RouteAddressChannel : AutoCloseable {
    fun connect(address: InetAddress, port: Int)
    fun localAddress(): String?
}

private class DatagramRouteAddressChannel(
    private val channel: DatagramChannel,
) : RouteAddressChannel {
    override fun connect(address: InetAddress, port: Int) {
        channel.connect(InetSocketAddress(address, port))
    }

    override fun localAddress(): String? = (channel.localAddress as? InetSocketAddress)?.address?.hostAddress

    override fun close() {
        channel.close()
    }
}
