package io.warpnect.platform.audio.capture

import java.nio.ByteBuffer

internal data class RootAudioRequest(val generation: Long, val audioGeneration: Long)

internal object RootAudioRequestCodec {
    const val MAGIC = 0x574e5241
    const val VERSION = 1
    const val SIZE = 24

    fun encode(request: RootAudioRequest): ByteArray {
        require(request.generation > 0 && request.audioGeneration > 0)
        return ByteBuffer.allocate(SIZE).putInt(MAGIC).putInt(VERSION)
            .putLong(request.generation).putLong(request.audioGeneration).array()
    }

    fun decode(bytes: ByteArray): RootAudioRequest {
        require(bytes.size == SIZE)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == MAGIC && buffer.int == VERSION)
        return RootAudioRequest(buffer.long, buffer.long).also {
            require(it.generation > 0 && it.audioGeneration > 0)
        }
    }
}

internal interface RootAudioControl {
    fun prepareAudio(request: RootAudioRequest): String

    fun startAudio(request: RootAudioRequest): String

    fun audioStatus(request: RootAudioRequest): String

    fun stopAudio(request: RootAudioRequest): String

    fun closeAudio(request: RootAudioRequest): String
}
