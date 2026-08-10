package io.warpnect.audio.capture

import android.os.Build
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPcmAudioRingInstrumentationTest {
    @Test
    fun sharedMemoryRingPublishesAndReleasesSlot() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
        val totalBytes = SharedPcmAudioRingLayout.totalBytes(
            slotCount = 2,
            payloadCapacityBytes = 32,
        )
        val memory = SharedMemory.create("warpnect_test_pcm_ring", totalBytes)
        val mapped = memory.mapReadWrite().order(ByteOrder.nativeOrder())
        try {
            SharedPcmAudioRingLayout.initialize(mapped, slotCount = 2, payloadCapacityBytes = 32)
            val slot = SharedPcmAudioRingLayout.beginWrite(mapped)
            SharedPcmAudioRingLayout.payloadSlice(mapped, slot).put(0, 0x44)
            val generation = SharedPcmAudioRingLayout.slotGeneration(mapped, slot)

            SharedPcmAudioRingLayout.publish(
                memory = mapped,
                slot = slot,
                sequence = 1,
                validBytes = 1,
                frameCount = 1,
                firstFramePosition = 0,
                captureTimeNs = 1,
                timestampQualityCode = AudioTimestampQuality.AudioRecordTimestamp.ordinal,
                flags = 0,
                publishTimeNs = 2,
            )
            val view = requireNotNull(SharedPcmAudioRingLayout.beginRead(mapped, slot, generation))

            assertEquals(0x44.toByte(), view.payload.get(0))
            SharedPcmAudioRingLayout.completeRead(mapped, slot, generation)
            assertEquals(0, SharedPcmAudioRingLayout.occupancy(mapped))
        } finally {
            SharedMemory.unmap(mapped)
            memory.close()
        }
    }
}
