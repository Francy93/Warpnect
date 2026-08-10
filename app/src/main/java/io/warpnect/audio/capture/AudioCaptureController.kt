package io.warpnect.audio.capture

interface AudioCaptureController : AutoCloseable {
    fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities

    suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult

    suspend fun start(): AudioCaptureResult

    suspend fun stop(): AudioCaptureResult

    fun snapshot(): AudioCaptureSnapshot

    override fun close()
}
