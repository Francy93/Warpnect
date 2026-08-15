package io.warpnect.session.control

import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.SessionProtectionError

/**
 * A narrow post-handshake transport for authenticated application control. Implementations must
 * encapsulate a SessionControl SCL packet in RFC-005E WNSD; raw WNCP is never a UDP payload.
 */
interface SecureSessionControlTransport : AutoCloseable {
    val maxPayloadBytes: Int

    fun setPayloadListener(listener: ((ByteArray) -> Unit)?)
    fun send(payload: ByteArray): SecureSessionControlSendResult
    fun protectCandidate(payload: ByteArray): SecureSessionControlSendResult =
        SecureSessionControlSendResult(SessionProtectionError.InvalidConfig)
    fun unprotectCandidate(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult = SessionControlUnprotectResult(SessionProtectionError.InvalidConfig)
    fun rebindRemoteEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError =
        SessionProtectionError.InvalidConfig
    override fun close()
}

data class SecureSessionControlSendResult(
    val error: SessionProtectionError,
    val protectedDatagram: ByteArray? = null,
) {
    val isSuccess: Boolean get() = error == SessionProtectionError.None && protectedDatagram != null
}

/** Cold-path native bridge used only by secure control, never video/audio/input packet hot paths. */
interface SessionControlProtectionRuntime : AutoCloseable {
    val maxPayloadBytes: Int

    fun protectSessionControl(
        sequenceNumber: Long,
        timestampUs: Long,
        payload: ByteArray,
    ): SecureSessionControlSendResult

    fun unprotectSessionControl(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult

    fun unprotectCandidateSessionControl(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult = unprotectSessionControl(sourceEndpoint, protectedDatagram, nowUs)

    fun rebindSessionControlEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError =
        SessionProtectionError.InvalidConfig

    override fun close()
}

data class SessionControlUnprotectResult(
    val error: SessionProtectionError,
    val payload: ByteArray? = null,
) {
    val isSuccess: Boolean get() = error == SessionProtectionError.None && payload != null
}
