package io.warpnect.platform.session.integration

import io.warpnect.session.handshake.HandshakeTransportEndpoint
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRouteLocalAddressResolverTest {
    private val endpoint = requireNotNull(
        HandshakeTransportEndpoint.from(InetAddress.getByName("192.0.2.10").address, 4242),
    )

    @Test
    fun usesTheConcreteAddressReportedAfterRouteConnection() {
        val channel = RecordingChannel(localAddress = "192.0.2.20")

        val result = AndroidRouteLocalAddressResolver { channel }.resolve(endpoint)

        assertEquals("192.0.2.20", result)
        assertEquals(4242, channel.port)
        assertTrue(channel.closed)
    }

    @Test
    fun wildcardAddressRemainsUnavailable() {
        val channel = RecordingChannel(localAddress = "0.0.0.0")

        val result = AndroidRouteLocalAddressResolver { channel }.resolve(endpoint)

        assertNull(result)
        assertTrue(channel.closed)
    }

    @Test
    fun connectionFailureReturnsUnavailableAndReleasesChannel() {
        val channel = RecordingChannel(failConnect = true)

        val result = AndroidRouteLocalAddressResolver { channel }.resolve(endpoint)

        assertNull(result)
        assertTrue(channel.closed)
    }

    @Test
    fun channelOpenFailureReturnsUnavailable() {
        val result = AndroidRouteLocalAddressResolver(
            RouteAddressChannelFactory { throw IllegalStateException("test") },
        ).resolve(endpoint)

        assertNull(result)
    }

    private class RecordingChannel(
        private val localAddress: String? = null,
        private val failConnect: Boolean = false,
    ) : RouteAddressChannel {
        var port = 0
        var closed = false

        override fun connect(address: InetAddress, port: Int) {
            if (failConnect) throw IllegalStateException("test")
            this.port = port
        }

        override fun localAddress(): String? = localAddress

        override fun close() {
            closed = true
        }
    }
}
