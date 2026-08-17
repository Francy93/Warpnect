package io.warpnect.session.lifecycle

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectRoutePlannerTest {
    @Test
    fun preservesBoundedStandbyActiveThenDiscoveryPriority() {
        val planner = ReconnectRoutePlanner(PathPreferencePolicy.PreferDirectThenLan)

        val routes = planner.plan(
            lastValidatedStandby = listOf(route(NetworkPathKind.Lan, 10)),
            lastActive = listOf(route(NetworkPathKind.Direct, 11)),
            discovery = listOf(route(NetworkPathKind.Direct, 12)),
        )

        assertEquals(
            listOf(NetworkPathKind.Lan, NetworkPathKind.Direct, NetworkPathKind.Direct),
            routes.map(ReconnectRouteCandidate::kind),
        )
        assertEquals(
            listOf(
                ReconnectRouteSource.LastValidatedStandby,
                ReconnectRouteSource.LastActive,
                ReconnectRouteSource.Discovery,
            ),
            routes.map(ReconnectRouteCandidate::source),
        )
    }

    @Test
    fun directOnlyFiltersLanAndDeduplicatesRoutes() {
        val planner = ReconnectRoutePlanner(PathPreferencePolicy.DirectOnly)
        val direct = route(NetworkPathKind.Direct, 20)

        val routes = planner.plan(
            lastValidatedStandby = listOf(route(NetworkPathKind.Lan, 19)),
            lastActive = listOf(direct),
            discovery = listOf(direct),
        )

        assertEquals(1, routes.size)
        assertEquals(NetworkPathKind.Direct, routes.single().kind)
        assertEquals(ReconnectRouteSource.LastActive, routes.single().source)
    }

    private fun route(kind: NetworkPathKind, lastAddressByte: Int): ReconnectRouteCandidate = ReconnectRouteCandidate(
        requireNotNull(
            HandshakeTransportEndpoint.from(
                byteArrayOf(192.toByte(), 168.toByte(), 1, lastAddressByte.toByte()),
                45_000,
            ),
        ),
        kind,
        ReconnectRouteSource.Discovery,
    )
}
