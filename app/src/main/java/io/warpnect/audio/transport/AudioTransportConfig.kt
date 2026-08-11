package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioCaptureSource

data class AudioTransportConfig(
    val source: AudioCaptureSource,
    val remoteAddress: String,
    val remotePort: Int,
    val maxWireDatagramSize: Int,
    val localPort: Int = 0,
    val initialAudioSequence: Long = 0,
)
