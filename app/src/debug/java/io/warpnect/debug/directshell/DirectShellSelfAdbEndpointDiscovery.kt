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
        val serviceName: String,
    )

    enum class Failure {
        PAIRING_SERVICE_NOT_DISCOVERED,
        CONNECTION_SERVICE_NOT_DISCOVERED,
        CONNECTION_SERVICE_IDENTITY_REQUIRED,
        CONNECTION_SERVICE_RESOLUTION_ALREADY_ACTIVE,
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

    fun discoverConnection(
        context: Context,
        trustedServiceName: String,
        excludedEndpoint: Endpoint? = null,
    ): Endpoint = discover(
        context = context,
        serviceType = SERVICE_TYPE_TLS_CONNECT,
        timeoutFailure = Failure.CONNECTION_SERVICE_NOT_DISCOVERED,
        timeoutMillis = CONNECTION_SERVICE_PUBLICATION_TIMEOUT_MILLIS,
        trustedServiceName = requireTrustedServiceName(trustedServiceName),
        excludedEndpoint = excludedEndpoint,
    )

    @Suppress("DEPRECATION") // The executor overload is unavailable on the API31/API33 targets.
    private fun discover(
        context: Context,
        serviceType: String,
        timeoutFailure: Failure,
        timeoutMillis: Long,
        trustedServiceName: String? = null,
        excludedEndpoint: Endpoint? = null,
    ): Endpoint {
        val result = AtomicReference<Endpoint>()
        val terminalFailure = AtomicReference<Failure?>()
        val ready = CountDownLatch(1)
        val manager = context.applicationContext.getSystemService(NsdManager::class.java)
            ?: throw DiscoveryException(Failure.DISCOVERY_SECURITY_ERROR)
        val discoveryStarted = AtomicBoolean(false)
        val resolver = SerialServiceResolver(
            manager = manager,
            result = result,
            ready = ready,
            terminalFailure = terminalFailure,
            trustedServiceName = trustedServiceName,
            excludedEndpoint = excludedEndpoint,
        )
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

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=found ${serviceInfoSummary(serviceInfo)}")
                resolver.enqueue(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=lost ${serviceInfoSummary(serviceInfo)}")
                resolver.invalidate(serviceInfo)
            }

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
            // API31 has no public stopServiceResolution(). Closing prevents a late callback from
            // starting another queued resolve after this bounded caller has already completed.
            resolver.close()
            if (discoveryStarted.get()) runCatching { manager.stopServiceDiscovery(listener) }
        }
        return result.get() ?: throw DiscoveryException(timeoutFailure)
    }

    /** Android's API31 NSD client permits one outstanding resolve operation at a time. */
    @Suppress("DEPRECATION")
    private class SerialServiceResolver(
        private val manager: NsdManager,
        private val result: AtomicReference<Endpoint>,
        private val ready: CountDownLatch,
        private val terminalFailure: AtomicReference<Failure?>,
        private val trustedServiceName: String?,
        private val excludedEndpoint: Endpoint?,
    ) {
        private val lock = Any()
        private val pending = ArrayDeque<Candidate>()
        private val candidateGenerations = mutableMapOf<String, Long>()
        private var active: Candidate? = null
        private var nextCandidateGeneration = 0L
        private var closed = false

        private data class Candidate(
            val serviceInfo: NsdServiceInfo,
            val generation: Long,
        ) {
            val serviceName: String get() = requireNotNull(serviceInfo.serviceName)
        }

        fun enqueue(serviceInfo: NsdServiceInfo) {
            val next = synchronized(lock) {
                val serviceName = serviceInfo.serviceName ?: return@synchronized null
                if (closed || result.get() != null || terminalFailure.get() != null) {
                    return@synchronized null
                }
                val trustedMatch = trustedServiceName?.let { trustedConnectionServiceMatch(serviceName, it) }
                if (trustedServiceName != null && trustedMatch == null) {
                    Log.i(
                        TAG,
                        "DIRECT_SHELL_SELF_ADB_DISCOVERY event=ignored service=$serviceName " +
                            "reason=not_trusted_adb_service",
                    )
                    return@synchronized null
                }
                if (trustedMatch == TrustedConnectionServiceMatch.DNS_SD_CONFLICT_ALIAS) {
                    Log.i(
                        TAG,
                        "DIRECT_SHELL_SELF_ADB_DISCOVERY event=trusted_alias service=$serviceName " +
                            "reason=dns_sd_conflict_suffix",
                    )
                }
                if (candidateGenerations.containsKey(serviceName)) {
                    Log.i(
                        TAG,
                        "DIRECT_SHELL_SELF_ADB_DISCOVERY event=ignored service=$serviceName reason=duplicate",
                    )
                    return@synchronized null
                }
                val candidate = Candidate(serviceInfo, ++nextCandidateGeneration)
                candidateGenerations[serviceName] = candidate.generation
                pending.addLast(candidate)
                nextLocked()
            }
            if (next != null) resolve(next)
        }

        fun invalidate(serviceInfo: NsdServiceInfo) {
            val serviceName = serviceInfo.serviceName ?: return
            synchronized(lock) {
                if (closed) return
                candidateGenerations.remove(serviceName)
                pending.removeAll { it.serviceName == serviceName }
                Log.i(
                    TAG,
                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=invalidated service=$serviceName " +
                        "active=${active?.serviceName == serviceName}",
                )
            }
        }

        fun close() {
            val state = synchronized(lock) {
                if (closed) return
                closed = true
                val activeService = active?.serviceName ?: "none"
                val pendingCount = pending.size
                pending.clear()
                candidateGenerations.clear()
                "active=$activeService pending=$pendingCount"
            }
            Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolver_closed $state")
        }

        private fun resolve(candidate: Candidate) {
            Log.i(
                TAG,
                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_requested " +
                    "service=${candidate.serviceName} generation=${candidate.generation}",
            )
            try {
                manager.resolveService(
                    candidate.serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(
                                TAG,
                                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_failed " +
                                    "service=${failedServiceInfo.serviceName} error=$errorCode " +
                                    "failure=${resolveFailureName(errorCode)} generation=${candidate.generation}",
                            )
                            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                                terminalFailure.compareAndSet(
                                    null,
                                    Failure.CONNECTION_SERVICE_RESOLUTION_ALREADY_ACTIVE,
                                )
                                ready.countDown()
                            }
                            complete(candidate)
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            if (!isCurrent(candidate)) {
                                Log.i(
                                    TAG,
                                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolved_ignored " +
                                        "service=${resolvedServiceInfo.serviceName} " +
                                        "generation=${candidate.generation} reason=stale_or_closed",
                                )
                                complete(candidate)
                                return
                            }
                            val resolvedEndpoint = resolvedServiceInfo.host?.hostAddress?.let { host ->
                                Endpoint(
                                    host = host,
                                    port = resolvedServiceInfo.port,
                                    serviceName = resolvedServiceInfo.serviceName.orEmpty(),
                                )
                            }
                            val endpoint = selectLocalEndpoint(
                                host = resolvedServiceInfo.host,
                                port = resolvedServiceInfo.port,
                                localAddresses = localAddresses(),
                                serviceName = resolvedServiceInfo.serviceName.orEmpty(),
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
                            complete(candidate)
                        }
                    },
                )
            } catch (error: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_rejected " +
                        "service=${candidate.serviceName} generation=${candidate.generation}",
                    error,
                )
                complete(candidate)
            } catch (error: SecurityException) {
                terminalFailure.compareAndSet(null, Failure.DISCOVERY_SECURITY_ERROR)
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_security_error", error)
                ready.countDown()
                complete(candidate)
            }
        }

        private fun isCurrent(candidate: Candidate): Boolean = synchronized(lock) {
            !closed && active == candidate && candidateGenerations[candidate.serviceName] == candidate.generation
        }

        private fun complete(candidate: Candidate) {
            val next = synchronized(lock) {
                if (active != candidate) return@synchronized null
                active = null
                if (closed) return@synchronized null
                nextLocked()
            }
            if (next != null) resolve(next)
        }

        private fun nextLocked(): Candidate? {
            if (closed || active != null || result.get() != null || terminalFailure.get() != null) return null
            if (pending.isEmpty()) return null
            val next = pending.removeFirst()
            active = next
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
        serviceName: String,
        excludedEndpoint: Endpoint? = null,
    ): Endpoint? {
        if (host == null || port !in MIN_PORT..MAX_PORT) return null
        val hostKey = canonicalAddress(host)
        if (hostKey !in localAddresses.map(::canonicalAddress).toSet()) return null
        return Endpoint(host.hostAddress ?: return null, port, serviceName)
            .takeUnless { candidate ->
                excludedEndpoint?.let { excluded ->
                    candidate.host == excluded.host && candidate.port == excluded.port
                } == true
            }
    }

    internal fun requireTrustedServiceName(serviceName: String): String {
        require(
            serviceName.isNotEmpty() &&
                serviceName.length <= MAX_SERVICE_NAME_LENGTH &&
                serviceName.none { it.isWhitespace() || it.isISOControl() },
        ) { "invalid_trusted_adb_service_name" }
        return serviceName
    }

    internal enum class TrustedConnectionServiceMatch {
        EXACT,
        DNS_SD_CONFLICT_ALIAS,
    }

    internal fun trustedConnectionServiceMatch(
        foundServiceName: String,
        trustedServiceName: String,
    ): TrustedConnectionServiceMatch? {
        val trusted = requireTrustedServiceName(trustedServiceName)
        return when {
            foundServiceName == trusted -> TrustedConnectionServiceMatch.EXACT
            foundServiceName.removePrefix(trusted).matches(DNS_SD_CONFLICT_SUFFIX) -> {
                TrustedConnectionServiceMatch.DNS_SD_CONFLICT_ALIAS
            }
            else -> null
        }
    }

    internal fun matchesTrustedConnectionService(foundServiceName: String, trustedServiceName: String): Boolean =
        trustedConnectionServiceMatch(foundServiceName, trustedServiceName) != null

    private fun serviceInfoSummary(serviceInfo: NsdServiceInfo): String {
        return "service=${serviceInfo.serviceName ?: "none"} type=${serviceInfo.serviceType ?: "none"} " +
            "host=${serviceInfo.host?.hostAddress ?: "none"} port=${serviceInfo.port} " +
            "network=unavailable_in_discovery_callback attributes=${serviceInfo.attributes.size}"
    }

    internal fun resolveFailureName(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE"
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
        else -> "UNKNOWN_$errorCode"
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
    private const val MAX_SERVICE_NAME_LENGTH = 255
    private val DNS_SD_CONFLICT_SUFFIX = Regex(""" \([1-9][0-9]*\)""")
    private const val IPV6_BYTES = 16
    private const val IPV4_MAPPED_PREFIX_BYTES = 12
    private const val TAG = "WarpnectDirectShellBootstrap"
}
