package io.warpnect.platform.audio.capture.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSignalEvidenceTest {
    private fun samples(frequency: Double): ByteBuffer =
        ByteBuffer.allocate(48_000 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(48_000) { frame ->
                val value = (8192 * sin(2 * Math.PI * frequency * frame / 48_000)).toInt().toShort()
                putShort(value)
                putShort(value)
            }
        }

    @Test fun `silence is not positive capture evidence`() {
        val silence = PcmSignalEvidence()
        val pcm = samples(0.0)
        silence.accept(pcm, 0, pcm.capacity(), 0)
        assertEquals(0.0, silence.rms, 0.0)
        assertFalse(silence.validTone(silence))
    }

    @Test fun `997 Hz has expected energy and correlation`() {
        val signal = PcmSignalEvidence()
        val pcm = samples(997.0)
        signal.accept(pcm, 0, pcm.capacity(), 0)
        assertTrue(signal.validTone(PcmSignalEvidence()))
        assertEquals(8192.0, signal.toneAmplitude, 2.0)
        assertEquals(5792.0, signal.rms, 4.0)
    }

    @Test fun `different tone is not correlated`() {
        val signal = PcmSignalEvidence()
        val pcm = samples(1100.0)
        signal.accept(pcm, 0, pcm.capacity(), 0)
        assertFalse(signal.validTone(PcmSignalEvidence()))
    }

    @Test fun `short samples cannot qualify`() {
        val signal = PcmSignalEvidence()
        val pcm = samples(997.0)
        signal.accept(pcm, 0, 400, 0)
        assertFalse(signal.validTone(PcmSignalEvidence()))
    }

    @Test fun `malformed PCM bounds fail closed`() {
        assertTrue(runCatching { PcmSignalEvidence().accept(ByteBuffer.allocate(4), 0, 8, 0) }.isFailure)
    }

    @Test fun `unaligned PCM rejected`() {
        assertTrue(runCatching { PcmSignalEvidence().accept(ByteBuffer.allocate(4), 0, 3, 0) }.isFailure)
    }
}
