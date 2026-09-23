package io.warpnect.platform.discovery

import io.warpnect.session.discovery.DiscoveryDisplayAlias
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDiscoveryHostAliasTest {
    @Test
    fun ownerConfiguredDeviceNameIsPreferredForHostPresentation() {
        val alias = AndroidDiscoveryHostAlias.fromCandidates(
            deviceName = "Francy's S22",
            model = "SM-S901B",
        )

        assertEquals("Francy's S22", alias.value)
    }

    @Test
    fun invalidDeviceNameFallsBackToBuildModel() {
        val alias = AndroidDiscoveryHostAlias.fromCandidates(
            deviceName = "bad\u0000name",
            model = "SM-A415F",
        )

        assertEquals("SM-A415F", alias.value)
    }

    @Test
    fun missingDeviceAndModelNamesUseTheGenericHostAlias() {
        val alias = AndroidDiscoveryHostAlias.fromCandidates(deviceName = null, model = " ")

        assertEquals(DiscoveryDisplayAlias.DEFAULT_HOST, alias)
    }
}
