package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioCaptureSource

data class AudioTransportSnapshot(
    val state: AudioTransportState = AudioTransportState.Stopped,
    val source: AudioCaptureSource? = null,
    val configGeneration: Long = 0,
    val nextAudioSequence: Long = 0,
    val configsSubmitted: Long = 0,
    val framesSubmitted: Long = 0,
    val framesFragmented: Long = 0,
    val datagramsGenerated: Long = 0,
    val datagramsSent: Long = 0,
    val bytesSent: Long = 0,
    val discontinuityFrames: Long = 0,
    val wouldBlockCount: Long = 0,
    val sendFailures: Long = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val frameDurationUs: Int = 0,
    val lookaheadSamples: Int = 0,
    val lastFramePosition: Long = 0,
    val lastCaptureTimeUs: Long = 0,
    val lastError: AudioTransportError = AudioTransportError.None,
) {
    companion object {
        private const val NATIVE_SNAPSHOT_VALUES = 21

        fun fromNative(values: LongArray): AudioTransportSnapshot {
            if (values.size < NATIVE_SNAPSHOT_VALUES) {
                return AudioTransportSnapshot(
                    state = AudioTransportState.Error,
                    lastError = AudioTransportError.InvalidHandle,
                )
            }
            val error = AudioTransportError.fromNativeCode(values[18].toInt())
            val opened = values[19] != 0L
            val closed = values[20] != 0L
            val state = when {
                closed -> AudioTransportState.Closed
                error != AudioTransportError.None -> AudioTransportState.Error
                opened -> AudioTransportState.Ready
                else -> AudioTransportState.Stopped
            }
            return AudioTransportSnapshot(
                state = state,
                source = sourceFromPayloadType(values[0].toInt()),
                configGeneration = values[1],
                nextAudioSequence = values[2],
                configsSubmitted = values[3],
                framesSubmitted = values[4],
                framesFragmented = values[5],
                datagramsGenerated = values[6],
                datagramsSent = values[7],
                bytesSent = values[8],
                discontinuityFrames = values[9],
                wouldBlockCount = values[10],
                sendFailures = values[11],
                sampleRateHz = values[12].toInt(),
                channelCount = values[13].toInt(),
                frameDurationUs = values[14].toInt(),
                lookaheadSamples = values[15].toInt(),
                lastFramePosition = values[16],
                lastCaptureTimeUs = values[17],
                lastError = error,
            )
        }

        private fun sourceFromPayloadType(payloadType: Int): AudioCaptureSource? = when (payloadType) {
            2 -> AudioCaptureSource.SystemAudio
            3 -> AudioCaptureSource.MicrophoneAudio
            else -> null
        }
    }
}
