package io.warpnect.debug.directshell

import android.net.nsd.NsdManager
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            serviceName = "adb-R58N52B32JJ-1jXjic",
        )

        requireNotNull(endpoint)
        assertEquals("192.168.1.36", endpoint.host)
        assertEquals(35_395, endpoint.port)
        assertEquals("adb-R58N52B32JJ-1jXjic", endpoint.serviceName)
    }

    @Test
    fun rejectsResolvedEndpointOwnedByAnotherDevice() {
        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.37"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
            serviceName = "adb-foreign-service",
        )

        assertNull(endpoint)
    }

    @Test
    fun acceptsIpv4MappedIpv6ForLocalIpv4Listener() {
        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("::ffff:192.168.1.36"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
            serviceName = "adb-R58N52B32JJ-1jXjic",
        )

        requireNotNull(endpoint)
        assertEquals(35_395, endpoint.port)
    }

    @Test
    fun rejectsAStaleEndpointExplicitlyExcludedAfterConnectRefusal() {
        val stale = DirectShellSelfAdbEndpointDiscovery.Endpoint(
            "192.168.1.36",
            35_395,
            "adb-R58N52B32JJ-1jXjic",
        )

        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.36"),
            port = 35_395,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
            serviceName = "adb-R58N52B32JJ-1jXjic",
            excludedEndpoint = stale,
        )

        assertNull(endpoint)
    }

    @Test
    fun acceptsAReplacementEndpointAfterExcludingStaleEndpoint() {
        val stale = DirectShellSelfAdbEndpointDiscovery.Endpoint(
            "192.168.1.36",
            35_395,
            "adb-R58N52B32JJ-1jXjic",
        )

        val endpoint = DirectShellSelfAdbEndpointDiscovery.selectLocalEndpoint(
            host = InetAddress.getByName("192.168.1.36"),
            port = 37_125,
            localAddresses = listOf(InetAddress.getByName("192.168.1.36")),
            serviceName = "adb-R58N52B32JJ-1jXjic",
            excludedEndpoint = stale,
        )

        requireNotNull(endpoint)
        assertEquals(37_125, endpoint.port)
    }

    @Test
    fun retainsOnlyANonblankBoundedTrustedAdbServiceName() {
        assertEquals(
            "adb-R58N52B32JJ-1jXjic",
            DirectShellSelfAdbEndpointDiscovery.requireTrustedServiceName("adb-R58N52B32JJ-1jXjic"),
        )
    }

    @Test
    fun onlyResolvesTheServiceBoundToTheUserApprovedPairing() {
        val trusted = "adb-R58N52B32JJ-1jXjic"

        assertTrue(
            DirectShellSelfAdbEndpointDiscovery.matchesTrustedConnectionService(
                foundServiceName = trusted,
                trustedServiceName = trusted,
            ),
        )
        assertFalse(
            DirectShellSelfAdbEndpointDiscovery.matchesTrustedConnectionService(
                foundServiceName = "adb-Z1382023HGM1895372-1laj6c",
                trustedServiceName = trusted,
            ),
        )
    }

    @Test
    fun acceptsOnlyTheCanonicalDnsSdConflictAliasOfTheTrustedService() {
        val trusted = "adb-Z1382023HGM1895372-1laj6c"

        assertEquals(
            DirectShellSelfAdbEndpointDiscovery.TrustedConnectionServiceMatch.DNS_SD_CONFLICT_ALIAS,
            DirectShellSelfAdbEndpointDiscovery.trustedConnectionServiceMatch(
                foundServiceName = "$trusted (2)",
                trustedServiceName = trusted,
            ),
        )
        assertNull(
            DirectShellSelfAdbEndpointDiscovery.trustedConnectionServiceMatch(
                foundServiceName = "$trusted (02)",
                trustedServiceName = trusted,
            ),
        )
        assertNull(
            DirectShellSelfAdbEndpointDiscovery.trustedConnectionServiceMatch(
                foundServiceName = "$trusted (0)",
                trustedServiceName = trusted,
            ),
        )
        assertNull(
            DirectShellSelfAdbEndpointDiscovery.trustedConnectionServiceMatch(
                foundServiceName = "adb-foreign-service (2)",
                trustedServiceName = trusted,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWhitespaceInTrustedAdbServiceName() {
        DirectShellSelfAdbEndpointDiscovery.requireTrustedServiceName("adb-local\nservice")
    }

    @Test
    fun exposesTheFrameworkMeaningOfResolveErrorThree() {
        assertEquals(
            "FAILURE_ALREADY_ACTIVE",
            DirectShellSelfAdbEndpointDiscovery.resolveFailureName(NsdManager.FAILURE_ALREADY_ACTIVE),
        )
    }
}
