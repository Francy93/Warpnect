package io.warpnect.platform.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectDnsSdServiceTypeTest {
    @Test
    fun acceptsOnlyWarpnectDnsSdServiceLabels() {
        assertTrue("_warpnect._udp".matchesWarpnectServiceType())
        assertTrue("Warpnect-abcdef._warpnect._udp".matchesWarpnectServiceType())
        assertTrue("Warpnect-abcdef._warpnect._udp.local.".matchesWarpnectServiceType())

        assertFalse("_other._udp".matchesWarpnectServiceType())
        assertFalse("_warpnect._udp.evil".matchesWarpnectServiceType())
        assertFalse("other_warpnect._udp".matchesWarpnectServiceType())
    }
}
