package io.warpnect.debug.directshell

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectShellSelfAdbEndpointDiscoveryTest {
    @Test
    fun keepsConnectionServicePublicationGraceSeparateFromPairingDiscovery() {
        assertEquals(8_000L, DirectShellSelfAdbEndpointDiscovery.PAIRING_DISCOVERY_TIMEOUT_MILLIS)
        assertEquals(30_000L, DirectShellSelfAdbEndpointDiscovery.CONNECTION_SERVICE_PUBLICATION_TIMEOUT_MILLIS)
    }

    @Test
    fun selectsResolvedEndpointOwnedByDevice() {
        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.36"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
        )

        requireNotNull(endpoint)
        assertEquals("192.168.1.36", endpoint.host)
        assertEquals(35_395, endpoint.port)
    }

    @Test
    fun rejectsResolvedEndpointOwnedByAnotherDevice() {
        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.37"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
        )

        assertNull(endpoint)
    }

    @Test
    fun acceptsIpv4MappedIpv6ForLocalIpv4Listener() {
        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("::ffff:192.168.1.36"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
        )

        requireNotNull(endpoint)
        assertEquals(35_395, endpoint.port)
    }

    @Test
    fun rejectsAStaleEndpointExplicitlyExcludedAfterConnectRefusal() {
        val stale = DirectShellSelfAdbEndpointDiscovery.Endpoint("192.168.1.36", 35_395)

        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.36"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
            excludedEndpoint = stale,
        )

        assertNull(endpoint)
    }

    @Test
    fun acceptsAReplacementEndpointAfterExcludingStaleEndpoint() {
        val stale = DirectShellSelfAdbEndpointDiscovery.Endpoint("192.168.1.36", 35_395)

        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.36"),
            port = 37_125,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
            excludedEndpoint = stale,
        )

        requireNotNull(endpoint)
        assertEquals(37_125, endpoint.port)
    }
}
