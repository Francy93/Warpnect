package io.warpnect.platform.session.channel

import io.warpnect.NativeBridge
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.setup.ChannelEndpointAllocationResult
import io.warpnect.session.setup.ChannelEndpointAllocator
import io.warpnect.session.setup.ChannelEndpointLease
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.SessionSetupError

/**
 * Reserves the advertised endpoint in native code so the same socket can be atomically adopted by
 * the prepared native media transport. No receive loop or media worker is started here.
 */
class AndroidChannelEndpointAllocator : ChannelEndpointAllocator {
    override fun allocate(
        binding: PathSocketBinding,
        channelKind: SessionChannelKind,
    ): ChannelEndpointAllocationResult {
        if (!binding.isValid() || channelKind == SessionChannelKind.Control) {
            return ChannelEndpointAllocationResult(SessionSetupError.InvalidConfig)
        }
        val values = NativeBridge.preparedUdpEndpointCreate(binding.localAddress)
        return if (values.size != RESULT_VALUES || values[0] == 0L || values[1] != 0L || values[2] !in 1L..0xffffL) {
            values.firstOrNull()?.takeIf { it != 0L }?.let(NativeBridge::preparedUdpEndpointDestroy)
            ChannelEndpointAllocationResult(SessionSetupError.EndpointAllocationFailed)
        } else {
            ChannelEndpointAllocationResult(
                SessionSetupError.None,
                AndroidChannelEndpointLease(values[0], values[2].toInt(), binding, channelKind),
            )
        }
    }

    private companion object {
        const val RESULT_VALUES = 3
    }
}

internal interface NativeChannelEndpointLeaseAccess {
    fun nativeEndpointHandle(): Long

    /**
     * The native transport has consumed the socket from this endpoint holder. The lease remains
     * as descriptor metadata, but it must no longer attempt to destroy a socket it does not own.
     */
    fun markNativeEndpointAdopted(handle: Long)
}

private class AndroidChannelEndpointLease(
    private var handle: Long,
    override val localPort: Int,
    override val binding: PathSocketBinding,
    override val channelKind: SessionChannelKind,
) : ChannelEndpointLease, NativeChannelEndpointLeaseAccess {
    private val lock = Any()

    override fun nativeEndpointHandle(): Long = synchronized(lock) { handle }

    override fun markNativeEndpointAdopted(handle: Long) = synchronized(lock) {
        if (this.handle == handle) this.handle = 0L
    }

    override fun close() = synchronized(lock) {
        if (handle == 0L) return
        NativeBridge.preparedUdpEndpointDestroy(handle)
        handle = 0L
    }
}
