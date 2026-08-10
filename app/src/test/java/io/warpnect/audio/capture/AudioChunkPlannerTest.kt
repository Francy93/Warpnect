package io.warpnect.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChunkPlannerTest {
    @Test
    fun computesFiveMillisecondChunksForCommonSampleRates() {
        assertEquals(240, AudioChunkPlanner.targetFramesPerChunk(48_000, 5_000))
        assertEquals(221, AudioChunkPlanner.targetFramesPerChunk(44_100, 5_000))
    }

    @Test
    fun computesFrameAlignedChunkBytes() {
        assertEquals(960, AudioChunkPlanner.chunkBytes(240, 4))
        assertEquals(442, AudioChunkPlanner.chunkBytes(221, 2))
    }

    @Test
    fun rejectsOverflowedChunkBytes() {
        assertEquals(0, AudioChunkPlanner.chunkBytes(Int.MAX_VALUE, 4))
    }
}
