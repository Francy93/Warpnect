package io.warpnect.platform.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDeliveryDebugProbeTest {
    @Test
    fun stereoSystemAudioUsesFramesInsteadOfInterleavedSamplesForToneFrequency() {
        val events = mutableListOf<String>()
        val probe = PcmDeliveryDebugProbe("SystemAudio_decoded_playback", events::add)
        val pcm = ByteBuffer.allocateDirect(192_000).order(ByteOrder.nativeOrder())
        repeat(48_000) { frame ->
            val value = (kotlin.math.sin(2 * Math.PI * 997 * frame / 48_000) * 2_000).toInt().toShort()
            pcm.putShort(frame * 4, value)
            pcm.putShort(frame * 4 + 2, value)
        }
        probe.observe(pcm, 0, pcm.capacity(), 48_000, 1L)
        probe.flush(2L)
        assertEquals(2_000.0, events.single().substringAfter("tone997=").substringBefore(' ').toDouble(), 1.0)
    }

    @Test
    fun correlatesKnownMonoToneNumericallyAndResetsEachWindow() {
        val events = mutableListOf<String>()
        val probe = PcmDeliveryDebugProbe("client_microphone_capture", events::add)
        val pcm = ByteBuffer.allocateDirect(96_000).order(ByteOrder.nativeOrder())
        repeat(48_000) { index ->
            pcm.putShort(index * 2, (kotlin.math.sin(2 * Math.PI * 997 * index / 48_000) * 1_000).toInt().toShort())
        }
        probe.observe(pcm, 0, pcm.capacity(), 48_000, 1L)
        probe.flush(2L)
        val amplitude = events.single().substringAfter("tone997=").substringBefore(' ').toDouble()
        assertEquals(1_000.0, amplitude, 1.0)
        val silence = ByteBuffer.allocateDirect(480)
        probe.observe(silence, 0, 480, 240, 3L)
        probe.flush(4L)
        assertTrue(events.last().contains("tone997=0.0"))
    }

    @Test
    fun observesBorrowedPcmWithoutChangingBufferPosition() {
        val events = mutableListOf<String>()
        val probe = PcmDeliveryDebugProbe("capture", events::add)
        val pcm = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        pcm.putShort(0, 100)
        pcm.putShort(2, -100)
        pcm.position(4)
        probe.observe(pcm, 0, 4, 2, 1L)
        probe.flush(2L)
        assertEquals(4, pcm.position())
        assertEquals(1, events.size)
        assertTrue(events.single().contains("frames=2 rms=100.0 peak=100"))
    }

    @Test
    fun separatesDeliveryBurstsFromLongLocalGapsAndBoundsOutput() {
        val events = mutableListOf<String>()
        val probe = PcmDeliveryDebugProbe("playback", events::add)
        val pcm = ByteBuffer.allocateDirect(2)
        probe.observe(pcm, 0, 2, 1, 1L)
        probe.observe(pcm, 0, 2, 1, 500_001L)
        probe.observe(pcm, 0, 2, 1, 20_500_001L)
        probe.flush(20_500_002L)
        assertTrue(events.single().contains("max_gap_ns=20000000 max_burst=2"))
        repeat(30) {
            probe.observe(pcm, 0, 2, 1, 30_000_000L + it)
            probe.flush(30_000_001L + it)
        }
        assertEquals(24, events.size)
    }
}
