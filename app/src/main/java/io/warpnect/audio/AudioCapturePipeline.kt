package io.warpnect.audio

class AudioCapturePipeline {
    fun configure(configuration: AudioCaptureConfiguration): AudioPipelineResult =
        AudioPipelineResult.NotImplemented("Audio capture is reserved for a later phase.")

    fun start(): AudioPipelineResult =
        AudioPipelineResult.NotImplemented("System and microphone audio capture are not implemented in Phase 0.")

    fun stop() {
        // Phase 0 owns no capture resources.
    }
}

data class AudioCaptureConfiguration(
    val sampleRateHz: Int,
    val channelCount: Int,
    val preferInternalAudio: Boolean,
)

sealed interface AudioPipelineResult {
    data object Ready : AudioPipelineResult

    data class NotImplemented(
        val reason: String,
    ) : AudioPipelineResult
}
