package io.warpnect.session.setup

import io.warpnect.session.control.SecureSessionControlTransport

/** Retains one existing bounded HostCommit reply across the Setup-to-lifecycle handoff. */
internal class CompletedSetupControlTransport(
    private val delegate: SecureSessionControlTransport,
    private val setupId: SessionSetupId,
    private val profileHash: ByteArray,
    private val endpointOfferHash: ByteArray,
    private val proposalHash: ByteArray,
    commitBytes: ByteArray,
    private val expiresAtMs: Long,
    private val clock: SessionSetupMonotonicClock,
) : SecureSessionControlTransport by delegate {
    private val lock = Any()
    private var commit: ByteArray? = commitBytes.copyOf()
    private var closed = false

    override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) {
        delegate.setPayloadListener(
            listener?.let { downstream ->
                { payload ->
                    val setupPayload = payload.size >= SessionSetupProtocol.MAGIC.size &&
                        SessionSetupProtocol.MAGIC.indices.all { payload[it] == SessionSetupProtocol.MAGIC[it] }
                    if (setupPayload) {
                        val reply = synchronized(lock) {
                            if (closed) return@synchronized null
                            if (clock.nowMs() >= expiresAtMs) commit = null
                            val cached = commit ?: return@synchronized null
                            val accept = SessionSetupCodec.decode(payload)?.message
                                as? SessionSetupMessage.ClientConfigurationAccept ?: return@synchronized null
                            if (accept.header.setupId != setupId ||
                                !accept.profileHash.contentEquals(profileHash) ||
                                !accept.clientEndpointOfferHash.contentEquals(endpointOfferHash) ||
                                !accept.proposalHash.contentEquals(proposalHash)
                            ) {
                                return@synchronized null
                            }
                            cached.copyOf()
                        }
                        reply?.let(delegate::send)
                    } else if (synchronized(lock) { !closed }) {
                        downstream(payload)
                    }
                }
            },
        )
    }

    override fun close() {
        val release = synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            commit = null
            true
        }
        if (release) delegate.close()
    }
}
