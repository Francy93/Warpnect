package io.warpnect.platform.capture

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class SurfaceLeaseProvider(val uid: Int) {
    DIRECT_SHELL(2000),
    DIRECT_ROOT(0),
}

/** One Surface, one authenticated runtime owner, no reusable registry token. */
internal class PrivilegedSurfaceLease(
    val provider: SurfaceLeaseProvider,
    val generation: Long,
    val surfaceGeneration: Long,
    val pid: Int,
    val width: Int,
    val height: Int,
    private val expiresAtMs: Long,
    secret: ByteArray,
) : AutoCloseable {
    private val key = secret.clone()
    private var consumed = false
    private var closed = false

    init {
        require(generation > 0 && surfaceGeneration > 0 && pid > 0 && secret.size == 32)
        require(width in 1..4096 && height in 1..4096 && expiresAtMs > 0)
    }

    @Synchronized
    fun accept(
        callerUid: Int,
        callerPid: Int,
        requestedProvider: SurfaceLeaseProvider,
        requestedGeneration: Long,
        requestedSurface: Long,
        requestedWidth: Int,
        requestedHeight: Int,
        proof: ByteArray?,
        nowMs: Long,
    ): Boolean {
        if (closed || consumed || nowMs >= expiresAtMs || callerUid != provider.uid || callerPid != pid ||
            requestedProvider != provider || requestedGeneration != generation ||
            requestedSurface != surfaceGeneration ||
            requestedWidth != width || requestedHeight != height || proof?.size != 32
        ) {
            return false
        }
        val expected = proof(key, provider, generation, surfaceGeneration, width, height)
        val accepted = MessageDigest.isEqual(expected, proof)
        expected.fill(0)
        if (accepted) consumed = true
        return accepted
    }

    @Synchronized override fun close() {
        closed = true
        key.fill(0)
    }

    companion object {
        const val MAGIC = 0x574e5652
        const val VERSION = 1

        fun proof(
            secret: ByteArray,
            provider: SurfaceLeaseProvider,
            generation: Long,
            surface: Long,
            width: Int,
            height: Int,
        ): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(
                ByteBuffer.allocate(40).putInt(MAGIC).putInt(VERSION).putInt(provider.uid)
                    .putLong(generation).putLong(surface).putInt(width).putInt(height).putInt(0).array(),
            )
        }
    }
}
