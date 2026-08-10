package io.warpnect.audio.capture

data class AudioCaptureRequest(
    val source: AudioCaptureSource,
    val preferredSampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val encoding: AudioPcmEncoding = AudioPcmEncoding.Pcm16,
    val targetChunkDurationUs: Long = DEFAULT_TARGET_CHUNK_DURATION_US,
    val sharedRingSlotCount: Int = DEFAULT_SHARED_RING_SLOT_COUNT,
    val targetUid: Int? = null,
    val requireStrictFormat: Boolean = true,
) {
    companion object {
        const val DEFAULT_TARGET_CHUNK_DURATION_US = 5_000L
        const val MIN_TARGET_CHUNK_DURATION_US = 2_500L
        const val MAX_TARGET_CHUNK_DURATION_US = 20_000L
        const val DEFAULT_SHARED_RING_SLOT_COUNT = 8
        const val MIN_SHARED_RING_SLOT_COUNT = 2
        const val MAX_SHARED_RING_SLOT_COUNT = 64
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
    }
}
