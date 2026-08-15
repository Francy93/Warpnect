package io.warpnect.session.path

import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.setup.PathAttemptId
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SessionSetupMessage

/**
 * Bounded semantic validation after RFC-005E has authenticated a WNSD SessionControl record on a
 * dedicated Direct candidate socket. It deliberately cannot accept arbitrary session-control data
 * as an endpoint rebind request.
 */
class DirectPathValidationWindow(
    private val setupId: ULong,
    profileHash: ByteArray,
    private val attemptId: PathAttemptId,
    private val expiresAtMonotonicMs: Long,
) {
    private val expectedProfileHash = profileHash.copyOf()
    private var acceptedEndpoint: HandshakeTransportEndpoint? = null
    private var closed = false

    fun acceptProbe(
        message: SessionSetupMessage.DirectPathProbe,
        source: HandshakeTransportEndpoint,
        nowMonotonicMs: Long,
    ): DirectPathValidationResult {
        if (closed || nowMonotonicMs >= expiresAtMonotonicMs) {
            return DirectPathValidationResult.Failure(
                SessionSetupError.DirectValidationTimeout,
            )
        }
        if (message.header.setupId.value != setupId || message.pathAttemptId != attemptId ||
            !message.profileHash.contentEquals(expectedProfileHash)
        ) {
            return DirectPathValidationResult.Failure(SessionSetupError.DirectProbeAuthenticationFailed)
        }
        val prior = acceptedEndpoint
        if (prior != null && prior != source) {
            return DirectPathValidationResult.Failure(
                SessionSetupError.EndpointMismatch,
            )
        }
        acceptedEndpoint = source
        return DirectPathValidationResult.Accepted(source)
    }

    fun expectedAck(
        message: SessionSetupMessage.DirectPathAck,
        source: HandshakeTransportEndpoint,
        expectedHost: HandshakeTransportEndpoint,
        nowMonotonicMs: Long,
    ): DirectPathValidationResult {
        if (closed || nowMonotonicMs >= expiresAtMonotonicMs) {
            return DirectPathValidationResult.Failure(
                SessionSetupError.DirectValidationTimeout,
            )
        }
        if (source != expectedHost || message.header.setupId.value != setupId || message.pathAttemptId != attemptId ||
            !message.profileHash.contentEquals(expectedProfileHash)
        ) {
            return DirectPathValidationResult.Failure(SessionSetupError.DirectProbeAuthenticationFailed)
        }
        return DirectPathValidationResult.Accepted(source)
    }

    fun close() {
        closed = true
        acceptedEndpoint = null
    }
}

sealed interface DirectPathValidationResult {
    data class Accepted(val endpoint: HandshakeTransportEndpoint) : DirectPathValidationResult
    data class Failure(val error: SessionSetupError) : DirectPathValidationResult
}
