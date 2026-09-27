package io.warpnect.platform.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAudioProtocolTest {
    private val request = RootAudioRequest(10, 20)
    private val key = ByteArray(32) { it.toByte() }
    private fun lease() = RootPcmDescriptorLease(request, 100, key, 50)
    private fun accepted(
        owner: RootPcmDescriptorLease,
        uid: Int = 0,
        pid: Int = 100,
        provider: String = "DIRECT_ROOT",
        value: RootAudioRequest = request,
        proof: ByteArray = RootPcmDescriptorLease.proof(key, value),
        now: Long = 1,
    ) = owner.accept(uid, pid, provider, RootAudioRequestCodec.encode(value), proof, now)

    @Test fun `request round trips`() {
        assertEquals(request, RootAudioRequestCodec.decode(RootAudioRequestCodec.encode(request)))
    }

    @Test fun `wrong magic is rejected`() {
        val bytes = RootAudioRequestCodec.encode(request)
        bytes[0] = 0
        assertTrue(runCatching { RootAudioRequestCodec.decode(bytes) }.isFailure)
    }

    @Test fun `wrong version is rejected`() {
        val bytes = RootAudioRequestCodec.encode(request)
        bytes[7] = 2
        assertTrue(runCatching { RootAudioRequestCodec.decode(bytes) }.isFailure)
    }

    @Test fun `undersize is rejected`() {
        assertTrue(runCatching { RootAudioRequestCodec.decode(ByteArray(23)) }.isFailure)
    }

    @Test fun `oversize is rejected`() {
        assertTrue(runCatching { RootAudioRequestCodec.decode(ByteArray(4096)) }.isFailure)
    }

    @Test fun `invalid generation is rejected`() {
        assertTrue(runCatching { RootAudioRequestCodec.encode(request.copy(generation = 0)) }.isFailure)
    }

    @Test fun `valid Root owner accepted`() {
        assertTrue(accepted(lease()))
    }

    @Test fun `shell UID cannot receive Root descriptors`() {
        assertFalse(accepted(lease(), uid = 2000))
    }

    @Test fun `wrong PID rejected`() {
        assertFalse(accepted(lease(), pid = 101))
    }

    @Test fun `wrong provider rejected`() {
        assertFalse(accepted(lease(), provider = "DIRECT_SHELL"))
    }

    @Test fun `old runtime generation rejected`() {
        assertFalse(accepted(lease(), value = request.copy(generation = 9)))
    }

    @Test fun `old audio generation rejected`() {
        assertFalse(accepted(lease(), value = request.copy(audioGeneration = 19)))
    }

    @Test fun `invalid HMAC rejected`() {
        assertFalse(accepted(lease(), proof = ByteArray(32)))
    }

    @Test fun `expired lease rejected`() {
        assertFalse(accepted(lease(), now = 50))
    }

    @Test fun `consumed lease rejected`() {
        val owner = lease()
        assertTrue(accepted(owner))
        owner.issued()
        assertFalse(accepted(owner))
    }

    @Test fun `closed lease rejected idempotently`() {
        val owner = lease()
        owner.close()
        owner.close()
        assertFalse(accepted(owner))
    }
}
