package io.warpnect.platform.session.capability

import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.LocalCapabilityAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalCapabilityCollectorTest {
    @Test
    fun directDiscoveryDoesNotAdvertiseAPathWithoutProductionBackend() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            directPathBackendImplemented = false,
            directPathAvailable = true,
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertEquals(CapabilityBits.PATH_LAN, snapshot.paths.implementedPathKinds)
        assertEquals(CapabilityBits.PATH_LAN, snapshot.paths.availablePathKinds)
        assertEquals(LocalCapabilityAvailability.Unsupported, snapshot.localAvailability["directPath"])
    }

    @Test
    fun implementedDirectBackendCanRemainRuntimeUnavailable() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            directPathBackendImplemented = true,
            directPathAvailable = false,
            standbyPathSupported = true,
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertTrue(snapshot.paths.implementedPathKinds and CapabilityBits.PATH_DIRECT != 0)
        assertEquals(0, snapshot.paths.availablePathKinds and CapabilityBits.PATH_DIRECT)
        assertEquals(LocalCapabilityAvailability.SupportedButUnavailable, snapshot.localAvailability["directPath"])
    }

    @Test
    fun availableDirectBackendAdvertisesBoundedStandbyModel() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            directPathBackendImplemented = true,
            directPathAvailable = true,
            standbyPathSupported = true,
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertEquals(
            CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT,
            snapshot.paths.availablePathKinds,
        )
        assertEquals(2, snapshot.paths.maxPaths)
        assertEquals(CapabilityBits.PATH_STANDBY_SUPPORTED, snapshot.paths.pathFlags)
        assertEquals(LocalCapabilityAvailability.Available, snapshot.localAvailability["directPath"])
    }
}
