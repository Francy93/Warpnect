package io.warpnect.session.setup

import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionRole
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime

/** Allocates a stopped UDP endpoint. Implementations must bind before returning the advertised port. */
fun interface ChannelEndpointAllocator {
    fun allocate(binding: PathSocketBinding, channelKind: SessionChannelKind): ChannelEndpointAllocationResult
}

fun interface ChannelTransportPreparer {
    fun prepare(request: ChannelTransportPreparationRequest): ChannelTransportPreparationResult
}

data class ChannelTransportPreparationRequest(
    val localRole: SessionRole,
    val descriptor: ChannelDescriptor,
    val localEndpoint: ChannelEndpointLease,
    val remoteAddress: String,
    val configurations: List<SetupConfiguration>,
    val sessionProtectionRuntime: SessionProtectionRuntime,
    val protection: PreparedChannelProtection,
)

data class ChannelTransportPreparationResult(
    val error: SessionSetupError,
    val transport: PreparedChannelTransport? = null,
) {
    val isSuccess: Boolean get() = error == SessionSetupError.None && transport != null
}

data class ChannelEndpointAllocationResult(
    val error: SessionSetupError,
    val lease: ChannelEndpointLease? = null,
) {
    val isSuccess: Boolean get() = error == SessionSetupError.None && lease != null
}

/** Keeps RFC-005E Channel scopes opaque and releases exactly the scope it created. */
class SessionChannelProtectionLease private constructor(
    private val runtime: SessionProtectionRuntime,
    override val channelId: ChannelId,
    override val contextIds: ProtectionContextIds,
) : PreparedChannelProtection {
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runtime.destroyChannelContext(channelId)
        }
    }

    companion object {
        fun create(
            runtime: SessionProtectionRuntime,
            channelId: ChannelId,
            expectedRemoteEndpoint: HandshakeTransportEndpoint,
        ): ChannelProtectionAllocationResult {
            val result = runtime.createChannelContext(channelId, expectedRemoteEndpoint)
            return if (result.isSuccess) {
                ChannelProtectionAllocationResult(
                    SessionSetupError.None,
                    SessionChannelProtectionLease(runtime, channelId, requireNotNull(result.contextIds)),
                )
            } else {
                ChannelProtectionAllocationResult(result.error.toSetupError())
            }
        }
    }
}

data class ChannelProtectionAllocationResult(
    val error: SessionSetupError,
    val protection: PreparedChannelProtection? = null,
) {
    val isSuccess: Boolean get() = error == SessionSetupError.None && protection != null
}

private fun SessionProtectionError.toSetupError(): SessionSetupError = when (this) {
    SessionProtectionError.ContextCapacityExceeded,
    SessionProtectionError.ContextIdCollision,
    -> SessionSetupError.ProtectionContextFailed
    SessionProtectionError.Closed -> SessionSetupError.Closed
    else -> SessionSetupError.ProtectionContextFailed
}
