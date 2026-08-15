package io.warpnect.platform.session.security

import io.warpnect.NativeBridge
import io.warpnect.session.ChannelId
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionConfig
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionCreationResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionRuntimeFactory
import io.warpnect.session.security.SessionProtectionSnapshot
import io.warpnect.session.security.toCanonicalBytes
import io.warpnect.session.security.toNativeProtectionRole

object NativeSessionProtectionRuntimeFactory : SessionProtectionRuntimeFactory {
    override fun create(
        rootSecret: ByteArray,
        bootstrap: AuthenticatedSessionBootstrap,
        config: SessionProtectionConfig,
    ): SessionProtectionCreationResult {
        val values = NativeBridge.sessionProtectionCreate(
            rootSecret = rootSecret,
            sessionId = bootstrap.sessionId.toCanonicalBytes(),
            sessionGeneration = bootstrap.generation.value.toInt(),
            transcriptHash = bootstrap.authenticatedTranscriptHash.toByteArray(),
            localRole = bootstrap.localRole.toNativeProtectionRole(),
            maxSecureDatagramSize = config.maxSecureDatagramSize,
            replayWindowSize = config.replayWindowSize,
            maxContexts = config.maxContexts,
            maxPacketsPerEpoch = config.maxPacketsPerEpoch,
            previousEpochRetentionUs = config.previousEpochRetentionUs,
            maxProtectedRetransmissionAgeUs = config.maxProtectedRetransmissionAgeUs,
            expectedRemoteAddress = bootstrap.endpoint.addressBytes(),
            expectedRemotePort = bootstrap.endpoint.port,
        )
        if (values.size != 5) return SessionProtectionCreationResult(SessionProtectionError.CryptoFailure)
        val error = SessionProtectionError.fromNative(values[1].toInt())
        if (error != SessionProtectionError.None || values[0] == 0L) {
            val finalError = if (error == SessionProtectionError.None) {
                SessionProtectionError.CryptoFailure
            } else {
                error
            }
            return SessionProtectionCreationResult(finalError)
        }
        return SessionProtectionCreationResult(
            error = SessionProtectionError.None,
            runtime = NativeSessionProtectionRuntime(
                handle = values[0],
                bootstrap = bootstrap,
                sessionControlContext = ProtectionContextIds(values[2], values[3]),
                maxInnerSclDatagramSize = values[4].toInt(),
            ),
        )
    }
}

internal interface NativeSessionProtectionHandleAccess {
    fun nativeProtectionHandle(): Long
}

internal fun nativeProtectionHandle(runtime: SessionProtectionRuntime): Long =
    (runtime as? NativeSessionProtectionHandleAccess)?.nativeProtectionHandle() ?: 0L

private class NativeSessionProtectionRuntime(
    private var handle: Long,
    private val bootstrap: AuthenticatedSessionBootstrap,
    override val sessionControlContext: ProtectionContextIds,
    override val maxInnerSclDatagramSize: Int,
) : SessionProtectionRuntime, NativeSessionProtectionHandleAccess {
    private val lock = Any()
    override val sessionId get() = bootstrap.sessionId

    override fun nativeProtectionHandle(): Long = synchronized(lock) { handle }

    override fun createChannelContext(channelId: ChannelId): SessionProtectionContextResult = synchronized(lock) {
        if (handle == 0L) return@synchronized SessionProtectionContextResult(SessionProtectionError.Closed)
        val values = NativeBridge.sessionProtectionCreateContext(
            handle,
            1,
            channelId.value.toLong(),
        )
        if (values.size != 3) return@synchronized SessionProtectionContextResult(SessionProtectionError.CryptoFailure)
        val error = SessionProtectionError.fromNative(values[0].toInt())
        val contextIds = if (error == SessionProtectionError.None) {
            ProtectionContextIds(values[1], values[2])
        } else {
            null
        }
        SessionProtectionContextResult(error, contextIds)
    }

    override fun createChannelContext(
        channelId: ChannelId,
        expectedRemoteEndpoint: HandshakeTransportEndpoint,
    ): SessionProtectionContextResult = synchronized(lock) {
        if (handle == 0L) return@synchronized SessionProtectionContextResult(SessionProtectionError.Closed)
        decodeContextResult(
            NativeBridge.sessionProtectionCreateContext(
                handle,
                1,
                channelId.value.toLong(),
                expectedRemoteEndpoint.addressBytes(),
                expectedRemoteEndpoint.port,
            ),
        )
    }

    override fun destroyChannelContext(channelId: ChannelId): SessionProtectionError = synchronized(lock) {
        if (handle == 0L) {
            SessionProtectionError.Closed
        } else {
            SessionProtectionError.fromNative(
                NativeBridge.sessionProtectionDestroyContext(handle, 1, channelId.value.toLong()),
            )
        }
    }

    override fun protectSessionControl(
        sequenceNumber: Long,
        timestampUs: Long,
        payload: ByteArray,
    ): SecureSessionControlSendResult = synchronized(lock) {
        if (handle == 0L) return@synchronized SecureSessionControlSendResult(SessionProtectionError.Closed)
        decodeControlResult(
            NativeBridge.sessionProtectionProtectSessionControl(handle, sequenceNumber, timestampUs, payload),
        ).let { (error, bytes) -> SecureSessionControlSendResult(error, bytes) }
    }

    override fun unprotectSessionControl(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult = synchronized(lock) {
        if (handle == 0L) return@synchronized SessionControlUnprotectResult(SessionProtectionError.Closed)
        decodeControlResult(
            NativeBridge.sessionProtectionUnprotectSessionControl(
                handle,
                sourceEndpoint.addressBytes(),
                sourceEndpoint.port,
                protectedDatagram,
                nowUs,
            ),
        ).let { (error, bytes) -> SessionControlUnprotectResult(error, bytes) }
    }

    override fun unprotectCandidateSessionControl(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult = synchronized(lock) {
        if (handle == 0L) return@synchronized SessionControlUnprotectResult(SessionProtectionError.Closed)
        decodeControlResult(
            NativeBridge.sessionProtectionUnprotectCandidateSessionControl(
                handle,
                sourceEndpoint.addressBytes(),
                sourceEndpoint.port,
                protectedDatagram,
                nowUs,
            ),
        ).let { (error, bytes) -> SessionControlUnprotectResult(error, bytes) }
    }

    override fun rebindSessionControlEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError =
        synchronized(lock) {
            if (handle == 0L) {
                SessionProtectionError.Closed
            } else {
                SessionProtectionError.fromNative(
                    NativeBridge.sessionProtectionRebindSessionControl(
                        handle,
                        endpoint.addressBytes(),
                        endpoint.port,
                    ),
                )
            }
        }

    override fun snapshot(): SessionProtectionSnapshot = synchronized(lock) {
        if (handle == 0L) return@synchronized closedSnapshot()
        val values = NativeBridge.sessionProtectionSnapshot(handle)
        if (values.size != 13) return@synchronized closedSnapshot(SessionProtectionError.CryptoFailure)
        SessionProtectionSnapshot(
            activeContexts = values[0],
            protectedPackets = values[1],
            decryptedPackets = values[2],
            replayDrops = values[3],
            tooOldDrops = values[4],
            unknownContextDrops = values[5],
            endpointFilterDrops = values[6],
            authFailures = values[7],
            keyUpdatesSent = values[8],
            keyUpdatesAccepted = values[9],
            currentSendEpoch = values[10],
            currentReceiveEpoch = values[11],
            lastError = SessionProtectionError.fromNative(values[12].toInt()),
        )
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeBridge.sessionProtectionDestroy(handle)
            handle = 0L
        }
    }

    private fun closedSnapshot(
        error: SessionProtectionError = SessionProtectionError.Closed,
    ): SessionProtectionSnapshot {
        return SessionProtectionSnapshot(
            activeContexts = 0,
            protectedPackets = 0,
            decryptedPackets = 0,
            replayDrops = 0,
            tooOldDrops = 0,
            unknownContextDrops = 0,
            endpointFilterDrops = 0,
            authFailures = 0,
            keyUpdatesSent = 0,
            keyUpdatesAccepted = 0,
            currentSendEpoch = 0,
            currentReceiveEpoch = 0,
            lastError = error,
        )
    }

    private fun decodeControlResult(bytes: ByteArray?): Pair<SessionProtectionError, ByteArray?> {
        if (bytes == null || bytes.size < CONTROL_RESULT_PREFIX_BYTES) {
            return SessionProtectionError.CryptoFailure to null
        }
        val errorCode = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        val error = SessionProtectionError.fromNative(errorCode)
        return error to bytes.copyOfRange(CONTROL_RESULT_PREFIX_BYTES, bytes.size).takeIf {
            error == SessionProtectionError.None
        }
    }

    private fun decodeContextResult(values: LongArray): SessionProtectionContextResult {
        if (values.size != 3) return SessionProtectionContextResult(SessionProtectionError.CryptoFailure)
        val error = SessionProtectionError.fromNative(values[0].toInt())
        return SessionProtectionContextResult(
            error,
            if (error == SessionProtectionError.None) ProtectionContextIds(values[1], values[2]) else null,
        )
    }

    private companion object {
        const val CONTROL_RESULT_PREFIX_BYTES = 4
    }
}
