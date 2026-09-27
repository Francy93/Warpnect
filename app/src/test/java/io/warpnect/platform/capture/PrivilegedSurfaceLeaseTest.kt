package io.warpnect.platform.capture

import io.warpnect.capture.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedSurfaceLeaseTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun lease(provider: SurfaceLeaseProvider = SurfaceLeaseProvider.DIRECT_ROOT) =
        PrivilegedSurfaceLease(provider, 10, 20, 30, 640, 360, 100, key)
    private fun accept(
        lease: PrivilegedSurfaceLease,
        uid: Int = 0,
        pid: Int = 30,
        provider: SurfaceLeaseProvider = SurfaceLeaseProvider.DIRECT_ROOT,
        generation: Long = 10,
        surface: Long = 20,
        proof: ByteArray = PrivilegedSurfaceLease.proof(key, provider, generation, surface, 640, 360),
        now: Long = 1,
    ) = lease.accept(uid, pid, provider, generation, surface, 640, 360, proof, now)

    @Test fun `Root UID0 owner consumes exact lease once`() {
        val lease = lease()
        assertTrue(accept(lease))
        assertFalse(accept(lease))
    }

    @Test fun `Shell UID2000 owner consumes Shell lease`() = assertTrue(
        accept(lease(SurfaceLeaseProvider.DIRECT_SHELL), uid = 2000, provider = SurfaceLeaseProvider.DIRECT_SHELL),
    )

    @Test fun `Root cannot claim Shell lease`() = assertFalse(
        accept(lease(SurfaceLeaseProvider.DIRECT_SHELL), provider = SurfaceLeaseProvider.DIRECT_SHELL),
    )

    @Test fun `Shell cannot claim Root lease`() = assertFalse(accept(lease(), uid = 2000))

    @Test fun `provider separation survives a valid wrong-provider proof`() = assertFalse(
        accept(lease(), provider = SurfaceLeaseProvider.DIRECT_SHELL),
    )

    @Test fun `wrong Binder PID rejected`() = assertFalse(accept(lease(), pid = 31))

    @Test fun `stale runtime generation rejected`() = assertFalse(accept(lease(), generation = 9))

    @Test fun `stale Surface generation rejected`() = assertFalse(accept(lease(), surface = 19))

    @Test fun `invalid HMAC rejected without consuming lease`() {
        val lease = lease()
        assertFalse(accept(lease, proof = ByteArray(32)))
        assertTrue(accept(lease))
    }

    @Test fun `expired lease rejected`() = assertFalse(accept(lease(), now = 100))

    @Test fun `closed lease rejected`() {
        val lease = lease()
        lease.close()
        assertFalse(accept(lease))
    }

    @Test fun `bounded typed Video request round trips`() {
        val request = RootVideoRequest(10, 20, CaptureRequest(0, 640, 360))
        assertEquals(request, RootVideoRequestCodec.decode(RootVideoRequestCodec.encode(request)))
    }

    @Test fun `wrong version truncated and oversized Video requests fail closed`() {
        val bytes = RootVideoRequestCodec.encode(RootVideoRequest(10, 20, CaptureRequest(0, 640, 360)))
        assertTrue(runCatching { RootVideoRequestCodec.decode(bytes.copyOf(39)) }.isFailure)
        assertTrue(runCatching { RootVideoRequestCodec.decode(bytes.copyOf(4096)) }.isFailure)
        bytes[7] = 2
        assertTrue(runCatching { RootVideoRequestCodec.decode(bytes) }.isFailure)
    }

    @Test fun `default display scope and dimensions fail closed`() {
        assertTrue(
            runCatching {
                RootVideoRequestCodec.encode(RootVideoRequest(10, 20, CaptureRequest(1, 640, 360)))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                RootVideoRequestCodec.encode(RootVideoRequest(10, 20, CaptureRequest(0, 8192, 360)))
            }.isFailure,
        )
    }
}
