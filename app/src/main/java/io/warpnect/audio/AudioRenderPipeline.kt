package io.warpnect.audio

class AudioRenderPipeline {
    fun configure(configuration: AudioRenderConfiguration): AudioPipelineResult =
        AudioPipelineResult.NotImplemented("Low-latency audio rendering is reserved for a later phase.")

    fun start(): AudioPipelineResult =
        AudioPipelineResult.NotImplemented("Audio playback is not implemented in Phase 0.")

    fun stop() {
        // Phase 0 owns no render resources.
    }
}

data class AudioRenderConfiguration(
    val sampleRateHz: Int,
    val channelCount: Int,
    val preferAaudio: Boolean,
)

