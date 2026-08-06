package io.warpnect.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkDiscoveryManager {
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    fun prepareWifiDirectDiscovery(): DiscoveryPreparation =
        DiscoveryPreparation.NotImplemented("Wi-Fi Direct peer discovery is reserved for a later phase.")

    fun prepareNsdDiscovery(): DiscoveryPreparation =
        DiscoveryPreparation.NotImplemented("mDNS/NSD fallback discovery is reserved for a later phase.")

    fun startDiscovery(preferredBackend: DiscoveryBackend = DiscoveryBackend.WifiDirect) {
        _state.value = DiscoveryState.Scanning(preferredBackend = preferredBackend)
    }

    fun stopDiscovery() {
        _state.value = DiscoveryState.Idle
    }
}

enum class DiscoveryBackend {
    WifiDirect,
    Nsd,
}

data class WarpnectPeer(
    val id: String,
    val displayName: String,
    val endpointHint: String? = null,
)

sealed interface DiscoveryState {
    data object Idle : DiscoveryState

    data class Scanning(
        val preferredBackend: DiscoveryBackend,
        val visiblePeers: List<WarpnectPeer> = emptyList(),
    ) : DiscoveryState

    data class Unavailable(
        val reason: String,
    ) : DiscoveryState
}

sealed interface DiscoveryPreparation {
    data object Ready : DiscoveryPreparation

    data class NotImplemented(
        val reason: String,
    ) : DiscoveryPreparation
}
