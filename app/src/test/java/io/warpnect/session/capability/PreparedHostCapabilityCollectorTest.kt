package io.warpnect.session.capability

import io.warpnect.session.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PreparedHostCapabilityCollectorTest {
    @Test
    fun preparedHostSnapshotIsReusedUntilHostReadinessStops() {
        var hostCollections = 0
        val collector = PreparedHostCapabilityCollector(
            LocalCapabilityCollector { role ->
                if (role == SessionRole.Host) hostCollections += 1
                CapabilityNegotiationCodecTest.snapshot(role)
            },
        )

        val prepared = collector.prepareHost()
        val negotiated = collector.collect(SessionRole.Host)

        assertSame(prepared, negotiated)
        assertEquals(1, hostCollections)

        collector.clearPreparedHost()
        collector.collect(SessionRole.Host)

        assertEquals(2, hostCollections)
    }

    @Test
    fun clientCollectionIsNeverSatisfiedByHostPreparation() {
        val roles = mutableListOf<SessionRole>()
        val collector = PreparedHostCapabilityCollector(
            LocalCapabilityCollector { role ->
                roles += role
                CapabilityNegotiationCodecTest.snapshot(role)
            },
        )

        collector.prepareHost()
        collector.collect(SessionRole.Client)

        assertEquals(listOf(SessionRole.Host, SessionRole.Client), roles)
    }
}
