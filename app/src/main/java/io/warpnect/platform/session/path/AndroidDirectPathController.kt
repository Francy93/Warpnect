package io.warpnect.platform.session.path

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import io.warpnect.session.discovery.DiscoveryOpaqueRouteLocator
import io.warpnect.session.setup.DirectPathLease
import io.warpnect.session.setup.PathFailureReason
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Public-API-only Wi-Fi Direct adapter. `onConnectionChanged` is fed from the application's
 * existing P2P broadcast integration; Android ActionListener success is deliberately not treated
 * as a usable data path.
 */
class AndroidDirectPathController(
    context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val hostGroupManager: HostDirectGroupManager,
) : AutoCloseable {
    private val lock = Any()
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val pendingHost = mutableListOf<(AndroidDirectResult) -> Unit>()
    private val pendingClient = mutableListOf<(AndroidDirectResult) -> Unit>()
    private var clientGroupLeases = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) refreshConnectionState()
        }
    }

    init {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun ensureHostGroup(callback: (AndroidDirectResult) -> Unit) {
        if (closed.get()) {
            callback(AndroidDirectResult.Failure(PathFailureReason.DirectUnavailable))
            return
        }
        synchronized(lock) {
            if (pendingHost.size >= MAX_PENDING) {
                callback(AndroidDirectResult.Failure(PathFailureReason.DirectUnavailable))
                return
            }
            pendingHost += callback
        }
        hostGroupManager.ensureGroup { result ->
            when (result) {
                is AndroidDirectResult.HostGroupReady -> completeHost(result)
                is AndroidDirectResult.Failure -> completeHost(result)
                AndroidDirectResult.RequestAccepted -> refreshConnectionState()
                is AndroidDirectResult.ClientConnected -> Unit
            }
        }
    }

    /** Requests a join only. The caller must wait for [onConnectionChanged] before probing. */
    fun connectClient(
        peerLocator: DiscoveryOpaqueRouteLocator,
        peerAddress: String,
        callback: (AndroidDirectResult) -> Unit,
    ) {
        if (closed.get() || peerAddress.isBlank()) {
            callback(AndroidDirectResult.Failure(PathFailureReason.ConnectFailed))
            return
        }
        synchronized(lock) {
            if (pendingClient.size >= MAX_PENDING) {
                callback(AndroidDirectResult.Failure(PathFailureReason.DirectUnavailable))
                return
            }
            pendingClient += callback
        }
        val config = WifiP2pConfig().apply { deviceAddress = peerAddress }
        try {
            manager.connect(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        callback(AndroidDirectResult.RequestAccepted)
                        refreshConnectionState()
                    }

                    override fun onFailure(reason: Int) {
                        removePending(callback)
                        callback(AndroidDirectResult.Failure(PathFailureReason.ConnectFailed, reason))
                    }
                },
            )
        } catch (_: SecurityException) {
            removePending(callback)
            callback(AndroidDirectResult.Failure(PathFailureReason.PermissionRequired))
        } catch (_: RuntimeException) {
            removePending(callback)
            callback(AndroidDirectResult.Failure(PathFailureReason.ConnectFailed))
        }
    }

    /** Verifies the frozen Host-as-GO topology after Android has reported group formation. */
    fun onConnectionChanged(info: WifiP2pInfo?, group: WifiP2pGroup?) {
        if (closed.get() || info == null || group == null || !info.groupFormed) {
            return
        }
        if (info.isGroupOwner) {
            hostGroupManager.markReady(group)
            completeHost(AndroidDirectResult.HostGroupReady(group.`interface`))
            if (synchronized(lock) { pendingClient.isNotEmpty() }) {
                completeClient(AndroidDirectResult.Failure(PathFailureReason.UnexpectedGroupOwner))
            }
            return
        }
        val ownerAddress = info.groupOwnerAddress?.hostAddress
        if (ownerAddress.isNullOrBlank()) {
            completeClient(AndroidDirectResult.Failure(PathFailureReason.ConnectionTimeout))
        } else {
            completeClient(AndroidDirectResult.ClientConnected(group.`interface`, ownerAddress))
        }
    }

    fun acquireHostGroupLease(): DirectPathLease? = hostGroupManager.acquireLease()

    fun acquireClientGroupLease(): DirectPathLease? = synchronized(lock) {
        if (closed.get()) return@synchronized null
        clientGroupLeases += 1
        ClientGroupLease(this)
    }

    fun releaseHostGroupLease(lease: DirectPathLease) = lease.close()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already removed by platform teardown.
        }
        val (callbacks, removeClientGroup) = synchronized(lock) {
            (pendingHost + pendingClient).also {
                pendingHost.clear()
                pendingClient.clear()
            } to (clientGroupLeases > 0).also {
                clientGroupLeases = 0
            }
        }
        callbacks.forEach { it(AndroidDirectResult.Failure(PathFailureReason.DirectUnavailable)) }
        if (removeClientGroup) removeGroupBestEffort()
        hostGroupManager.close()
    }

    private fun refreshConnectionState() {
        if (closed.get()) return
        try {
            manager.requestConnectionInfo(channel) { info ->
                try {
                    manager.requestGroupInfo(channel) { group -> onConnectionChanged(info, group) }
                } catch (_: SecurityException) {
                    failPendingForPermission()
                }
            }
        } catch (_: SecurityException) {
            failPendingForPermission()
        }
    }

    private fun completeHost(result: AndroidDirectResult) {
        val callbacks = synchronized(lock) { pendingHost.toList().also { pendingHost.clear() } }
        callbacks.forEach { it(result) }
    }

    private fun completeClient(result: AndroidDirectResult) {
        val callbacks = synchronized(lock) { pendingClient.toList().also { pendingClient.clear() } }
        callbacks.forEach { it(result) }
    }

    private fun removePending(callback: (AndroidDirectResult) -> Unit) = synchronized(lock) {
        pendingHost.remove(callback)
        pendingClient.remove(callback)
    }

    private fun releaseClientGroupLease() = synchronized(lock) {
        if (clientGroupLeases == 0) return@synchronized
        clientGroupLeases -= 1
        if (clientGroupLeases == 0 && !closed.get()) {
            removeGroupBestEffort()
        }
    }

    private fun failPendingForPermission() {
        completeHost(AndroidDirectResult.Failure(PathFailureReason.PermissionRequired))
        completeClient(AndroidDirectResult.Failure(PathFailureReason.PermissionRequired))
    }

    private fun removeGroupBestEffort() {
        try {
            manager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                },
            )
        } catch (_: RuntimeException) {
            // Best-effort teardown; no other session lease remains in this controller.
        }
    }

    private class ClientGroupLease(private val owner: AndroidDirectPathController) : DirectPathLease {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) owner.releaseClientGroupLease()
        }
    }

    private companion object {
        const val MAX_PENDING = 8
    }
}

/** One Host-owned Wi-Fi Direct group is shared by all pending/prepared direct session paths. */
class HostDirectGroupManager(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
) : AutoCloseable {
    private val lock = Any()
    private var state = HostDirectGroupState.NoGroup
    private var group: WifiP2pGroup? = null
    private var leases = 0
    private var closed = false

    fun ensureGroup(callback: (AndroidDirectResult) -> Unit) = synchronized(lock) {
        if (closed) {
            callback(AndroidDirectResult.Failure(PathFailureReason.DirectUnavailable))
            return@synchronized
        }
        when (state) {
            HostDirectGroupState.Ready -> callback(AndroidDirectResult.HostGroupReady(group?.`interface`))
            HostDirectGroupState.Creating -> callback(AndroidDirectResult.RequestAccepted)
            else -> {
                state = HostDirectGroupState.Creating
                try {
                    manager.createGroup(
                        channel,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = callback(AndroidDirectResult.RequestAccepted)

                            override fun onFailure(reason: Int) = synchronized(lock) {
                                state = HostDirectGroupState.Failed
                                callback(AndroidDirectResult.Failure(PathFailureReason.GroupCreationFailed, reason))
                            }
                        },
                    )
                } catch (_: SecurityException) {
                    state = HostDirectGroupState.Failed
                    callback(AndroidDirectResult.Failure(PathFailureReason.PermissionRequired))
                } catch (_: RuntimeException) {
                    state = HostDirectGroupState.Failed
                    callback(AndroidDirectResult.Failure(PathFailureReason.GroupCreationFailed))
                }
            }
        }
    }

    fun markReady(group: WifiP2pGroup) = synchronized(lock) {
        if (closed) return@synchronized
        this.group = group
        state = HostDirectGroupState.Ready
    }

    fun acquireLease(): DirectPathLease? = synchronized(lock) {
        if (closed || state != HostDirectGroupState.Ready) return@synchronized null
        leases += 1
        HostGroupLease(this)
    }

    fun snapshot(): HostDirectGroupSnapshot = synchronized(lock) {
        HostDirectGroupSnapshot(state, leases, group?.`interface`)
    }

    private fun releaseLease() = synchronized(lock) {
        if (leases == 0) return@synchronized
        leases -= 1
        if (leases == 0 && state == HostDirectGroupState.Ready && !closed) removeGroupLocked()
    }

    private fun removeGroupLocked() {
        state = HostDirectGroupState.Removing
        try {
            manager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = synchronized(lock) {
                        if (closed) return@synchronized
                        group = null
                        state = HostDirectGroupState.NoGroup
                    }

                    override fun onFailure(reason: Int) = synchronized(lock) {
                        if (closed) return@synchronized
                        state = HostDirectGroupState.Failed
                    }
                },
            )
        } catch (_: RuntimeException) {
            if (!closed) state = HostDirectGroupState.Failed
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        if (state == HostDirectGroupState.Ready) removeGroupLocked()
        state = HostDirectGroupState.Closed
        group = null
        leases = 0
    }

    private class HostGroupLease(private val owner: HostDirectGroupManager) : DirectPathLease {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) owner.releaseLease()
        }
    }
}

enum class HostDirectGroupState {
    NoGroup,
    Creating,
    Ready,
    Failed,
    Removing,
    Closed,
}

data class HostDirectGroupSnapshot(
    val state: HostDirectGroupState,
    val leaseCount: Int,
    val interfaceName: String?,
)

sealed interface AndroidDirectResult {
    data object RequestAccepted : AndroidDirectResult
    data class HostGroupReady(val interfaceName: String?) : AndroidDirectResult
    data class ClientConnected(val interfaceName: String?, val groupOwnerAddress: String) : AndroidDirectResult
    data class Failure(val reason: PathFailureReason, val platformReason: Int? = null) : AndroidDirectResult
}
