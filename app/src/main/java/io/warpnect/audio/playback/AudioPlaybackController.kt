package io.warpnect.audio.playback

import java.nio.ByteBuffer

interface AudioPlaybackController : AutoCloseable {
    fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult

    fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult

    fun start(): AudioPlaybackResult

    fun stop(): AudioPlaybackResult

    fun snapshot(): AudioPlaybackSnapshot

    fun queryPresentationTimestamp(): AudioPresentationTimestampResult

    override fun close()
}
