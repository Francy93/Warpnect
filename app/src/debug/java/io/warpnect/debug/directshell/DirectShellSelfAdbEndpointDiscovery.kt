package io.warpnect.debug.directshell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded DNS-SD discovery of the device's own Wireless Debugging services. */
internal object DirectShellSelfAdbEndpointDiscovery {
    data class Endpoint(
        val host: String,
        val port: Int,
    )

    enum class Failure {
        PAIRING_SERVICE_NOT_DISCOVERED,
        CONNECTION_SERVICE_NOT_DISCOVERED,
        DISCOVERY_START_FAILED,
        DISCOVERY_SECURITY_ERROR,
        DISCOVERY_INTERRUPTED,
    }

    class DiscoveryException(
        val failure: Failure,
    ) : IOException(failure.name)

    fun discoverPairing(context: Context): Endpoint = discover(
        context,
        SERVICE_TYPE_TLS_PAIRING,
        Failure.PAIRING_SERVICE_NOT_DISCOVERED,
        PAIRING_DISCOVERY_TIMEOUT_MILLIS,
    )

    fun discoverConnection(context: Context, excludedEndpoint: Endpoint? = null): Endpoint = discover(
        context,
        SERVICE_TYPE_TLS_CONNECT,
        Failure.CONNECTION_SERVICE_NOT_DISCOVERED,
        CONNECTION_SERVICE_PUBLICATION_TIMEOUT_MILLIS,
        excludedEndpoint,
    )

    @Suppress("DEPRECATION") // The executor overload is unavailable on the API31/API33 targets.
    private fun discover(
        context: Context,
        serviceType: String,
        timeoutFailure: Failure,
        timeoutMillis: Long,
        excludedEndpoint: Endpoint? = null,
    ): Endpoint {
        val result = AtomicReference<Endpoint>()
        val terminalFailure = AtomicReference<Failure?>()
        val ready = CountDownLatch(1)
        val manager = context.applicationContext.getSystemService(NsdManager::class.java)
            ?: throw DiscoveryException(Failure.DISCOVERY_SECURITY_ERROR)
        val discoveryStarted = AtomicBoolean(false)
        val resolver = SerialServiceResolver(manager, result, ready, terminalFailure, excludedEndpoint)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                terminalFailure.compareAndSet(null, Failure.DISCOVERY_START_FAILED)
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=start_failed type=$serviceType error=$errorCode")
                ready.countDown()
            }

            override fun onDiscoveryStarted(serviceType: String) {
                discoveryStarted.set(true)
                Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=started type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) = resolver.enqueue(serviceInfo)

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=stop_failed type=$serviceType error=$errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            if (!ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw DiscoveryException(timeoutFailure)
            }
            terminalFailure.get()?.let { throw DiscoveryException(it) }
        } catch (_: SecurityException) {
            throw DiscoveryException(Failure.DISCOVERY_SECURITY_ERROR)
        } catch (_: IllegalArgumentException) {
            throw DiscoveryException(Failure.DISCOVERY_START_FAILED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DiscoveryException(Failure.DISCOVERY_INTERRUPTED)
        } finally {
            if (discoveryStarted.get()) runCatching { manager.stopServiceDiscovery(listener) }
        }
        return result.get() ?: throw DiscoveryException(timeoutFailure)
    }

    /** Android's NSD resolver allows one outstanding request per client on the A41 framework. */
    @Suppress("DEPRECATION")
    private class SerialServiceResolver(
        private val manager: NsdManager,
        private val result: AtomicReference<Endpoint>,
        private val ready: CountDownLatch,
        private val terminalFailure: AtomicReference<Failure?>,
        private val excludedEndpoint: Endpoint?,
    ) {
        private val lock = Any()
        private val pending = ArrayDeque<NsdServiceInfo>()
        private val seenServiceNames = mutableSetOf<String>()
        private var resolving = false

        fun enqueue(serviceInfo: NsdServiceInfo) {
            val next = synchronized(lock) {
                val serviceName = serviceInfo.serviceName ?: return@synchronized null
                if (result.get() != null || terminalFailure.get() != null || !seenServiceNames.add(serviceName)) {
                    return@synchronized null
                }
                pending.addLast(serviceInfo)
                nextLocked()
            }
            if (next != null) resolve(next)
        }

        private fun resolve(serviceInfo: NsdServiceInfo) {
            try {
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(
                                TAG,
                                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_failed " +
                                    "service=${failedServiceInfo.serviceName} error=$errorCode",
                            )
                            complete()
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val resolvedEndpoint = resolvedServiceInfo.host?.hostAddress?.let { host ->
                                Endpoint(host, resolvedServiceInfo.port)
                            }
                            val endpoint = selectLocalEndpoint(
                                host = resolvedServiceInfo.host,
                                port = resolvedServiceInfo.port,
                                localAddresses = localAddresses(),
                                excludedEndpoint = excludedEndpoint,
                            )
                            Log.i(
                                TAG,
                                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolved " +
                                    "service=${resolvedServiceInfo.serviceName} " +
                                    "host=${resolvedServiceInfo.host?.hostAddress ?: "none"} " +
                                    "port=${resolvedServiceInfo.port} local=${endpoint != null} " +
                                    "excluded=${resolvedEndpoint == excludedEndpoint}",
                            )
                            if (endpoint != null && result.compareAndSet(null, endpoint)) {
                                ready.countDown()
                            }
                            complete()
                        }
                    },
                )
            } catch (error: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_rejected " +
                        "service=${serviceInfo.serviceName}",
                    error,
                )
                complete()
            } catch (error: SecurityException) {
                terminalFailure.compareAndSet(null, Failure.DISCOVERY_SECURITY_ERROR)
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_security_error", error)
                ready.countDown()
                complete()
            }
        }

        private fun complete() {
            val next = synchronized(lock) {
                resolving = false
                nextLocked()
            }
            if (next != null) resolve(next)
        }

        private fun nextLocked(): NsdServiceInfo? {
            if (resolving || result.get() != null || terminalFailure.get() != null) return null
            if (pending.isEmpty()) return null
            val next = pending.removeFirst()
            resolving = true
            return next
        }
    }

    /**
     * Only use a DNS-SD endpoint that resolves to an address owned by this device. The early
     * probe helper checked a local port through loopback, which rejects an adbd listener bound
     * specifically to the Wi-Fi interface on some devices.
     */
    internal fun selectLocalEndpoint(
        host: InetAddress?,
        port: Int,
        localAddresses: Iterable<InetAddress>,
        excludedEndpoint: Endpoint? = null,
    ): Endpoint? {
        if (host == null || port !in MIN_PORT..MAX_PORT) return null
        val hostKey = canonicalAddress(host)
        if (hostKey !in localAddresses.map(::canonicalAddress).toSet()) return null
        return Endpoint(host.hostAddress ?: return null, port).takeUnless { it == excludedEndpoint }
    }

    private fun localAddresses(): List<InetAddress> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            Collections.list(interfaces).flatMap { networkInterface ->
                Collections.list(networkInterface.inetAddresses)
            }
        } catch (error: Exception) {
            Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=local_address_query_failed", error)
            emptyList()
        }
    }

    private fun canonicalAddress(address: InetAddress): String {
        val bytes = address.address
        val canonicalBytes = if (
            bytes.size == IPV6_BYTES &&
            bytes.copyOfRange(0, IPV4_MAPPED_PREFIX_BYTES).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() &&
            bytes[11] == 0xff.toByte()
        ) {
            bytes.copyOfRange(IPV4_MAPPED_PREFIX_BYTES, IPV6_BYTES)
        } else {
            bytes
        }
        return canonicalBytes.joinToString(separator = ".") { byte -> (byte.toInt() and 0xff).toString() }
    }

    // adbd starts TLS and publishes _adb-tls-connect asynchronously after Wireless Debugging is
    // enabled. This is a single bounded publication grace, not a retry loop.
    internal const val CONNECTION_SERVICE_PUBLICATION_TIMEOUT_MILLIS = 30_000L
    internal const val PAIRING_DISCOVERY_TIMEOUT_MILLIS = 8_000L
    private const val SERVICE_TYPE_TLS_PAIRING = "_adb-tls-pairing._tcp"
    private const val SERVICE_TYPE_TLS_CONNECT = "_adb-tls-connect._tcp"
    private const val MIN_PORT = 1_024
    private const val MAX_PORT = 65_535
    private const val IPV6_BYTES = 16
    private const val IPV4_MAPPED_PREFIX_BYTES = 12
    private const val TAG = "WarpnectDirectShellBootstrap"
}
