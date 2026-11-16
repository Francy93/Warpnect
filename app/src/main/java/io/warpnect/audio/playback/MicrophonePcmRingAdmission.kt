package io.warpnect.audio.playback

import java.nio.ByteBuffer

/** Single decoded-PCM producer; no SDK/executor handoff, bounded native slot-credit recheck only. */
interface MicrophonePcmRingAdmission {
    fun admitMicrophonePcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult
}
