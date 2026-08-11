package io.warpnect.audio.transport

interface AudioTransportController : EncodedAudioTransportBackend, AutoCloseable {
    fun open(config: AudioTransportConfig): AudioTransportOpenResult

    fun resendCurrentConfig(): AudioTransportSubmitResult

    fun snapshot(): AudioTransportSnapshot

    fun closeResult(): AudioTransportCloseResult

    override fun close() {
        closeResult()
    }
}
