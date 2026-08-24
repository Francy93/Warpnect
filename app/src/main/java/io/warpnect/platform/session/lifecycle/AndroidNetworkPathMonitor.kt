package io.warpnect.platform.session.lifecycle

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import io.warpnect.session.PathId

/**
 * Bounded per-SessionPath Android callback adapter. It forwards ordered callback facts to the
 * serialized lifecycle control context and never treats an Android event as peer authentication.
 */
class AndroidNetworkPathMonitor(
    private val connectivityManager: ConnectivityManager,
    private val callbackHandler: Handler,
    private val dispatch: (PathId, hardLoss: Boolean) -> Unit,
    private val onAvailable: (PathId) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val registrations = LinkedHashMap<PathId, Registration>()
    private var closed = false

    fun register(pathId: PathId, network: Network): Boolean = synchronized(lock) {
        if (closed || registrations.containsKey(pathId) || registrations.size >= 4) return@synchronized false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (available == network) onAvailable(pathId)
            }

            override fun onLost(lost: Network) {
                if (lost == network) dispatch(pathId, true)
            }

            override fun onLosing(losing: Network, maxMsToLive: Int) {
                if (losing == network) dispatch(pathId, false)
            }

            override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
                if (changed == network && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    dispatch(pathId, false)
                }
            }
        }
        return@synchronized try {
            connectivityManager.registerNetworkCallback(
                android.net.NetworkRequest.Builder().build(),
                callback,
                callbackHandler,
            )
            registrations[pathId] = Registration(network, callback)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun unregister(pathId: PathId) = synchronized(lock) {
        registrations.remove(pathId)?.let { registration ->
            try {
                connectivityManager.unregisterNetworkCallback(registration.callback)
            } catch (_: IllegalArgumentException) {
                // Android may already have released a callback during process teardown.
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        registrations.keys.toList().forEach(::unregister)
        closed = true
    }

    private data class Registration(
        val network: Network,
        val callback: ConnectivityManager.NetworkCallback,
    )
}

/** Narrow bridge for RFC-005G Direct group callbacks; group loss is only a local lifecycle hint. */
class AndroidDirectGroupPathMonitor(
    private val dispatch: (PathId, hardLoss: Boolean) -> Unit,
) {
    fun onGroupConnectionChanged(pathId: PathId, groupFormed: Boolean) {
        if (!groupFormed) dispatch(pathId, true)
    }
}
