package io.warpnect.debug.directshell

import android.content.Context
import android.net.InetAddresses
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.warpnect.platform.discovery.AndroidDiscoveryMulticastLock
import io.warpnect.session.discovery.DiscoveryError
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
        CONNECTION_SERVICE_PAIRING_REQUIRED,
        CONNECTION_SERVICE_IDENTITY_REQUIRED,
        CONNECTION_SERVICE_RESOLUTION_ALREADY_ACTIVE,
        DISCOVERY_START_FAILED,
        DISCOVERY_SECURITY_ERROR,
        DISCOVERY_MULTICAST_UNAVAILABLE,
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
        requiredHost: String? = null,
    ): Endpoint = discover(
        context = context,
        serviceType = SERVICE_TYPE_TLS_CONNECT,
        timeoutFailure = Failure.CONNECTION_SERVICE_NOT_DISCOVERED,
        timeoutMillis = CONNECTION_SERVICE_PUBLICATION_TIMEOUT_MILLIS,
        trustedServiceName = requireTrustedServiceName(trustedServiceName),
        excludedEndpoint = excludedEndpoint,
        requiredHost = requiredHost,
    )

    @Suppress("DEPRECATION") // The executor overload is unavailable on the API31/API33 targets.
    private fun discover(
        context: Context,
        serviceType: String,
        timeoutFailure: Failure,
        timeoutMillis: Long,
        trustedServiceName: String? = null,
        excludedEndpoint: Endpoint? = null,
        requiredHost: String? = null,
    ): Endpoint {
        val result = AtomicReference<Endpoint>()
        val terminalFailure = AtomicReference<Failure?>()
        val ready = CountDownLatch(1)
        val manager = context.applicationContext.getSystemService(NsdManager::class.java)
            ?: throw DiscoveryException(Failure.DISCOVERY_SECURITY_ERROR)
        val closed = AtomicBoolean(false)
        val resolver = SerialServiceResolver(
            manager = manager,
            result = result,
            ready = ready,
            terminalFailure = terminalFailure,
            trustedServiceName = trustedServiceName,
            excludedEndpoint = excludedEndpoint,
            expectedType = serviceType,
            requiredHost = requiredHost,
        )
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                terminalFailure.compareAndSet(null, Failure.DISCOVERY_START_FAILED)
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=start_failed type=$serviceType error=$errorCode")
                ready.countDown()
            }

            override fun onDiscoveryStarted(serviceType: String) {
                if (closed.get()) runCatching { manager.stopServiceDiscovery(this) }
                Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=started type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=found query=$serviceType")
                resolver.enqueue(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=lost query=$serviceType")
                resolver.invalidate(serviceInfo)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=stop_failed type=$serviceType error=$errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        val multicast = AndroidDiscoveryMulticastLock(context)
        val release = AutoCloseable {
            multicast.close()
            Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=multicast_scope_closed")
        }
        return withDiscoveryMulticast(release, multicast::acquireForActiveNsd) {
            Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=multicast_scope_ready")
            try {
                val started = System.nanoTime()
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                val exactQuery = trustedServiceName?.takeIf { requiredHost != null }?.let { guid ->
                    // Only a GUID from this pairing and the very same local address are eligible.
                    {
                        Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=exact_guid_query_requested")
                        resolver.enqueue(
                            NsdServiceInfo().apply {
                                serviceName = guid
                                this.serviceType = SERVICE_TYPE_TLS_CONNECT
                            },
                            exact = true,
                        )
                    }
                }
                if (!awaitDiscoveryWithinBudget(
                        started, timeoutMillis, System::nanoTime, { ready.await(it, TimeUnit.NANOSECONDS) }, exactQuery,
                    )
                ) {
                    Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=timeout query=$serviceType")
                    throw DiscoveryException(timeoutFailure)
                }
                terminalFailure.get()?.let { throw DiscoveryException(it) }
                requireDiscoveryResult(result.get(), timeoutFailure)
            } catch (_: SecurityException) {
                throw DiscoveryException(Failure.DISCOVERY_SECURITY_ERROR)
            } catch (_: IllegalArgumentException) {
                throw DiscoveryException(Failure.DISCOVERY_START_FAILED)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DiscoveryException(Failure.DISCOVERY_INTERRUPTED)
            } finally {
                // API31 has no public stopServiceResolution(); fence its late callbacks locally.
                closed.set(true)
                resolver.close()
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }
    }

    internal fun <T> withDiscoveryMulticast(
        lease: AutoCloseable,
        acquire: () -> DiscoveryError,
        discover: () -> T,
    ): T = lease.use {
        val admission = try {
            acquire()
        } catch (_: SecurityException) {
            DiscoveryError.LanPermissionDenied
        }
        when (admission) {
            DiscoveryError.None -> discover()
            DiscoveryError.LanPermissionDenied -> throw DiscoveryException(Failure.DISCOVERY_SECURITY_ERROR)
            else -> throw DiscoveryException(Failure.DISCOVERY_MULTICAST_UNAVAILABLE)
        }
    }

    internal fun awaitDiscoveryWithinBudget(
        startedNanos: Long,
        timeoutMillis: Long,
        nowNanos: () -> Long,
        await: (Long) -> Boolean,
        exactQuery: (() -> Unit)?,
    ): Boolean {
        fun remaining(millis: Long) = TimeUnit.MILLISECONDS.toNanos(millis) - (nowNanos() - startedNanos)
        val browseLimit = if (exactQuery == null) timeoutMillis else minOf(timeoutMillis, EXACT_BROWSE_HEAD_START_MILLIS)
        val browseBudget = remaining(browseLimit)
        if (browseBudget > 0 && await(browseBudget)) return true
        if (exactQuery == null || remaining(timeoutMillis) <= 0) return false
        exactQuery()
        val remainingBudget = remaining(timeoutMillis)
        return remainingBudget > 0 && await(remainingBudget)
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
        private val expectedType: String,
        private val requiredHost: String?,
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
            val exact: Boolean,
        ) {
            val serviceName: String get() = requireNotNull(serviceInfo.serviceName)
            val origin: String get() = if (exact) "exact_guid" else "browse"
        }

        fun enqueue(serviceInfo: NsdServiceInfo, exact: Boolean = false) {
            val next = synchronized(lock) {
                if (closed || result.get() != null || terminalFailure.get() != null) {
                    return@synchronized null
                }
                val serviceName = serviceInfo.serviceName.orEmpty()
                val rejection = discoveryIdentityRejection(expectedType, serviceInfo.serviceType, serviceName, trustedServiceName)
                if (rejection != null) {
                    Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=ignored reason=${rejection.name}")
                    return@synchronized null
                }
                val trustedMatch = trustedServiceName?.let { trustedConnectionServiceMatch(serviceName, it) }
                if (trustedMatch == TrustedConnectionServiceMatch.DNS_SD_CONFLICT_ALIAS) {
                    Log.i(
                        TAG,
                        "DIRECT_SHELL_SELF_ADB_DISCOVERY event=trusted_alias reason=dns_sd_conflict_suffix",
                    )
                }
                if (candidateGenerations.containsKey(serviceName)) {
                    Log.i(
                        TAG,
                        "DIRECT_SHELL_SELF_ADB_DISCOVERY event=ignored reason=duplicate",
                    )
                    return@synchronized null
                }
                val candidate = Candidate(serviceInfo, ++nextCandidateGeneration, exact)
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
                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=invalidated active=${active?.serviceName == serviceName}",
                )
            }
        }

        fun close() {
            val state = synchronized(lock) {
                if (closed) return
                closed = true
                val activeOrigin = active?.origin ?: "none"
                val pendingCount = pending.size
                pending.clear()
                candidateGenerations.clear()
                "active=$activeOrigin pending=$pendingCount"
            }
            Log.i(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolver_closed $state")
        }

        private fun resolve(candidate: Candidate) {
            Log.i(
                TAG,
                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_requested " +
                    "origin=${candidate.origin} generation=${candidate.generation}",
            )
            try {
                manager.resolveService(
                    candidate.serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(
                                TAG,
                                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_failed " +
                                    "error=$errorCode " +
                                    "failure=${resolveFailureName(errorCode)} origin=${candidate.origin} " +
                                    "generation=${candidate.generation}",
                            )
                            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                                fail(candidate, Failure.CONNECTION_SERVICE_RESOLUTION_ALREADY_ACTIVE)
                            }
                            complete(candidate)
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            if (!isCurrent(candidate)) {
                                Log.i(
                                    TAG,
                                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolved_ignored " +
                                        "generation=${candidate.generation} reason=stale_or_closed",
                                )
                                complete(candidate)
                                return
                            }
                            val name = resolvedServiceInfo.serviceName.orEmpty()
                            val identityRejection = discoveryIdentityRejection(
                                expectedType, resolvedServiceInfo.serviceType, name, trustedServiceName,
                            )
                            var rejection = identityRejection
                            val endpoint = if (identityRejection == null) {
                                selectLocalEndpoint(
                                    host = resolvedServiceInfo.host,
                                    port = resolvedServiceInfo.port,
                                    localAddresses = localAddresses(),
                                    serviceName = name,
                                    excludedEndpoint = excludedEndpoint,
                                    requiredHost = requiredHost,
                                    onRejected = { rejection = it },
                                )
                            } else {
                                null
                            }
                            Log.i(
                                TAG,
                                "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolved origin=${candidate.origin} " +
                                    "admitted=${endpoint != null} reason=${rejection?.name ?: "NONE"}",
                            )
                            if (endpoint != null) publish(candidate, endpoint)
                            complete(candidate)
                        }
                    },
                )
            } catch (_: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_rejected " +
                        "generation=${candidate.generation}",
                )
                complete(candidate)
            } catch (_: SecurityException) {
                fail(candidate, Failure.DISCOVERY_SECURITY_ERROR)
                Log.w(TAG, "DIRECT_SHELL_SELF_ADB_DISCOVERY event=resolve_security_error")
                complete(candidate)
            }
        }

        private fun isCurrent(candidate: Candidate): Boolean = synchronized(lock) {
            !closed && active == candidate && candidateGenerations[candidate.serviceName] == candidate.generation
        }

        private fun publish(candidate: Candidate, endpoint: Endpoint) = synchronized(lock) {
            if (isCurrent(candidate) && terminalFailure.get() == null && result.compareAndSet(null, endpoint)) {
                ready.countDown()
            }
        }

        private fun fail(candidate: Candidate, failure: Failure) = synchronized(lock) {
            if (isCurrent(candidate) && result.get() == null && terminalFailure.compareAndSet(null, failure)) {
                ready.countDown()
            }
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
        requiredHost: String? = null,
        onRejected: (DiscoveryRejection) -> Unit = {},
    ): Endpoint? {
        fun reject(reason: DiscoveryRejection): Endpoint? {
            onRejected(reason)
            return null
        }
        if (host == null) return reject(DiscoveryRejection.MISSING_ADDRESS)
        if (port !in MIN_PORT..MAX_PORT) return reject(DiscoveryRejection.INVALID_PORT)
        val hostKey = canonicalAddress(host)
        if (hostKey !in localAddresses.map(::canonicalAddress).toSet()) return reject(DiscoveryRejection.NONLOCAL_ADDRESS)
        val address = host.hostAddress ?: return reject(DiscoveryRejection.MISSING_ADDRESS)
        if (requiredHost != null && address != requiredHost) return reject(DiscoveryRejection.PAIRED_ADDRESS_MISMATCH)
        if (excludedEndpoint?.host == address && excludedEndpoint.port == port) return reject(DiscoveryRejection.EXCLUDED_ENDPOINT)
        return Endpoint(address, port, serviceName)
    }

    internal fun requireCurrentLocalSocketAddress(endpoint: Endpoint): InetSocketAddress {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IOException("adb_endpoint_wireless_debugging_unavailable")
        }
        val address = try {
            InetAddresses.parseNumericAddress(endpoint.host)
        } catch (_: IllegalArgumentException) {
            throw IOException("adb_endpoint_address_invalid")
        }
        if (selectLocalEndpoint(address, endpoint.port, localAddresses(), endpoint.serviceName) != endpoint) {
            throw IOException("adb_endpoint_no_longer_local")
        }
        return InetSocketAddress(address, endpoint.port)
    }

    internal fun matchesDiscoveryIdentity(
        expectedType: String,
        observedType: String?,
        serviceName: String,
        trustedServiceName: String?,
    ): Boolean = discoveryIdentityRejection(expectedType, observedType, serviceName, trustedServiceName) == null

    internal enum class DiscoveryRejection {
        TYPE_MISMATCH,
        MISSING_NAME,
        GUID_MISMATCH,
        MISSING_ADDRESS,
        INVALID_PORT,
        NONLOCAL_ADDRESS,
        PAIRED_ADDRESS_MISMATCH,
        EXCLUDED_ENDPOINT,
    }

    internal fun discoveryIdentityRejection(
        expectedType: String,
        observedType: String?,
        serviceName: String,
        trustedServiceName: String?,
    ): DiscoveryRejection? = when {
        observedType?.trim('.') != expectedType.trim('.') -> DiscoveryRejection.TYPE_MISMATCH
        serviceName.isEmpty() -> DiscoveryRejection.MISSING_NAME
        trustedServiceName != null && !matchesTrustedConnectionService(serviceName, trustedServiceName) ->
            DiscoveryRejection.GUID_MISMATCH
        else -> null
    }

    internal fun requireDiscoveryResult(endpoint: Endpoint?, missing: Failure): Endpoint =
        endpoint ?: throw DiscoveryException(missing)

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
            foundServiceName.startsWith(trusted) &&
                foundServiceName.removePrefix(trusted).matches(DNS_SD_CONFLICT_SUFFIX) -> {
                TrustedConnectionServiceMatch.DNS_SD_CONFLICT_ALIAS
            }
            else -> null
        }
    }

    internal fun matchesTrustedConnectionService(foundServiceName: String, trustedServiceName: String): Boolean =
        trustedConnectionServiceMatch(foundServiceName, trustedServiceName) != null

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
        val key = canonicalBytes.joinToString(separator = ".") { byte -> (byte.toInt() and 0xff).toString() }
        return if (address is Inet6Address && address.isLinkLocalAddress) "$key%${address.scopeId}" else key
    }

    // adbd starts TLS and publishes _adb-tls-connect asynchronously after Wireless Debugging is
    // enabled. This is a single bounded publication grace, not a retry loop.
    internal const val CONNECTION_SERVICE_PUBLICATION_TIMEOUT_MILLIS = 30_000L
    private const val EXACT_BROWSE_HEAD_START_MILLIS = 2_000L
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
