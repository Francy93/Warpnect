package io.warpnect.session.capability

import io.warpnect.session.SessionRole

/**
 * Retains one exact Host snapshot for the active Host-readiness lifetime. Preparing it before
 * discovery is advertised keeps cold platform qualification outside a peer's WNCP deadline.
 */
class PreparedHostCapabilityCollector(
    private val delegate: LocalCapabilityCollector,
) : LocalCapabilityCollector {
    private val lock = Any()
    private var preparedHost: LocalCapabilitySnapshot? = null

    fun prepareHost(): LocalCapabilitySnapshot = synchronized(lock) {
        preparedHost ?: delegate.collect(SessionRole.Host).also { preparedHost = it }
    }

    fun clearPreparedHost() = synchronized(lock) {
        preparedHost = null
    }

    override fun collect(role: SessionRole): LocalCapabilitySnapshot = when (role) {
        SessionRole.Host -> prepareHost()
        SessionRole.Client -> delegate.collect(role)
    }
}
