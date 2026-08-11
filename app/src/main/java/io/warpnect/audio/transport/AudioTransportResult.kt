package io.warpnect.audio.transport

data class AudioTransportOpenResult(
    val error: AudioTransportError,
    val snapshot: AudioTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AudioTransportError.None
}

data class AudioTransportSubmitResult(
    val error: AudioTransportError,
    val snapshot: AudioTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AudioTransportError.None
}

data class AudioTransportCloseResult(
    val error: AudioTransportError,
    val snapshot: AudioTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AudioTransportError.None
}
