package io.warpnect.debug.directshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class DirectShellAdbPeerBindingTest {
    private val type = "_adb-tls-connect._tcp"
    private val guid = "oem-opaque-guid"
    private val discovery = DirectShellSelfAdbEndpointDiscovery

    @Test
    fun authenticatedGuidIsIndependentOfPairingInstanceAndSerial() {
        val wire = guid.encodeToByteArray()
        assertEquals(guid, decodeDeviceGuid(wire))
        assertTrue(wire.all { it == 0.toByte() })
        assertTrue(discovery.matchesDiscoveryIdentity(type, ".$type", guid, guid))
        assertTrue(discovery.matchesDiscoveryIdentity(type, "$type.", "$guid (2)", guid))
        assertFalse(discovery.matchesDiscoveryIdentity(type, type, "pairing-advert", guid))
        assertFalse(discovery.matchesDiscoveryIdentity(type, type, " (2)", guid))
        assertFalse(discovery.matchesDiscoveryIdentity(type, "_adb._tcp", guid, guid))
    }

    @Test
    fun malformedPeerGuidNeverBecomesALocator() {
        val bytes = byteArrayOf(0xc0.toByte(), 0x80.toByte())
        assertThrows(IOException::class.java) { decodeDeviceGuid(bytes) }
        assertTrue(bytes.all { it == 0.toByte() })
        assertThrows(IOException::class.java) { decodeDeviceGuid(byteArrayOf()) }
    }

    @Test
    fun exactGuidDoesNotAuthorizeAnotherAddressDuringImmediateReconnect() {
        val paired = InetAddress.getByName("192.0.2.1")
        val other = InetAddress.getByName("192.0.2.2")
        val addresses = listOf(paired, other)
        assertNull(discovery.selectLocalEndpoint(other, 33_801, addresses, guid, requiredHost = paired.hostAddress))
        assertNotNull(discovery.selectLocalEndpoint(paired, 33_801, addresses, guid, requiredHost = paired.hostAddress))
    }

    @Test
    fun onlyUnrelatedAdvertisementsProduceTheSameFailureAsAnEmptyBrowse() {
        val missing = DirectShellSelfAdbEndpointDiscovery.Failure.CONNECTION_SERVICE_NOT_DISCOVERED
        val host = InetAddress.getByName("192.0.2.1")
        val untrusted = listOf("pairing-advert", "adb-serial-only", "other-guid (2)")
        for (names in listOf(emptyList(), untrusted)) {
            val candidate = names.filter { discovery.matchesDiscoveryIdentity(type, type, it, guid) }
                .firstNotNullOfOrNull { discovery.selectLocalEndpoint(host, 33_801, listOf(host), it) }
            val failure = assertThrows(DirectShellSelfAdbEndpointDiscovery.DiscoveryException::class.java) {
                discovery.requireDiscoveryResult(candidate, missing)
            }
            assertEquals(missing, failure.failure)
        }
    }
}
