package io.warpnect.session.lifecycle

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.handshake.HandshakeTransportEndpoint

/** Non-identity route inputs for one bounded, sequential fresh-WNSH recovery attempt series. */
data class ReconnectRouteCandidate(
    val endpoint: HandshakeTransportEndpoint,
    val kind: NetworkPathKind,
    val source: ReconnectRouteSource,
)

enum class ReconnectRouteSource {
    LastValidatedStandby,
    LastActive,
    Discovery,
}

/**
 * Pure RFC-005H ordering. A route is only an address to attempt; the reconnect still requires
 * RFC-005D ExactTrustedPeer authentication before it can reclaim a recovery lease.
 */
class ReconnectRoutePlanner(
    private val pathPreference: PathPreferencePolicy,
    private val maximumCandidates: Int = 6,
) {
    init {
        require(maximumCandidates in 1..6) { "Reconnect candidates must remain bounded" }
    }

    fun plan(
        lastValidatedStandby: List<ReconnectRouteCandidate>,
        lastActive: List<ReconnectRouteCandidate>,
        discovery: List<ReconnectRouteCandidate>,
    ): List<ReconnectRouteCandidate> = (
        lastValidatedStandby.map { it.copy(source = ReconnectRouteSource.LastValidatedStandby) } +
            lastActive.map { it.copy(source = ReconnectRouteSource.LastActive) } +
            discovery.map { it.copy(source = ReconnectRouteSource.Discovery) }
        )
        .asSequence()
        .filter { it.kind.allowedBy(pathPreference) }
        .distinctBy { candidate ->
            listOf(
                candidate.kind.name,
                candidate.endpoint.addressBytes().joinToString(separator = ","),
                candidate.endpoint.port.toString(),
            ).joinToString(separator = ":")
        }
        .sortedWith(
            compareBy<ReconnectRouteCandidate> { it.source.priority() }
                .thenBy { it.kind.preferenceRank(pathPreference) },
        )
        .take(maximumCandidates)
        .toList()

    private fun NetworkPathKind.allowedBy(policy: PathPreferencePolicy): Boolean = when (policy) {
        PathPreferencePolicy.DirectOnly -> this == NetworkPathKind.Direct
        PathPreferencePolicy.LanOnly -> this == NetworkPathKind.Lan
        PathPreferencePolicy.PreferDirectThenLan,
        PathPreferencePolicy.PreferLan,
        -> true
    }

    private fun NetworkPathKind.preferenceRank(policy: PathPreferencePolicy): Int = when (policy) {
        PathPreferencePolicy.PreferDirectThenLan,
        PathPreferencePolicy.DirectOnly,
        -> if (this == NetworkPathKind.Direct) 0 else 1
        PathPreferencePolicy.PreferLan,
        PathPreferencePolicy.LanOnly,
        -> if (this == NetworkPathKind.Lan) 0 else 1
    }

    private fun ReconnectRouteSource.priority(): Int = when (this) {
        ReconnectRouteSource.LastValidatedStandby -> 0
        ReconnectRouteSource.LastActive -> 1
        ReconnectRouteSource.Discovery -> 2
    }
}
