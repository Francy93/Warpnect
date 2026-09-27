package io.warpnect.platform.audio.capture

import io.warpnect.platform.privileged.runtime.DirectRootRuntime

/** Finite generation-bound operations; data remains in the app-owned PCM ring. */
internal class DirectRootSystemAudioGateway(private val runtime: DirectRootRuntime) : AutoCloseable {
    private val generation = requireNotNull(runtime.snapshot.generation)
    private var closed = false
    private var capture: RootAudioRequest? = null

    private fun control(request: RootAudioRequest): RootAudioControl {
        check(!closed && request.generation == generation && runtime.snapshot.ready) { "ROOT_AUDIO_RUNTIME_STALE" }
        return runtime.audioControl(generation) ?: error("ROOT_AUDIO_CONTROL_UNAVAILABLE")
    }

    fun prepare(request: RootAudioRequest): String {
        check(capture == null) { "ROOT_AUDIO_ALREADY_PREPARED" }
        val result = invoke(request) { it.prepareAudio(request) }
        capture = request
        return result
    }

    fun start(request: RootAudioRequest): String = active(request) { it.startAudio(request) }

    fun status(request: RootAudioRequest): String = active(request) { it.audioStatus(request) }

    fun stop(request: RootAudioRequest): String = active(request) { it.stopAudio(request) }

    private fun active(request: RootAudioRequest, action: (RootAudioControl) -> String): String {
        check(capture == request) { "ROOT_AUDIO_GENERATION_MISMATCH" }
        return invoke(request, action)
    }

    private fun invoke(request: RootAudioRequest, action: (RootAudioControl) -> String): String {
        val peer = control(request)
        try {
            return action(peer)
        } catch (error: Exception) {
            runtime.audioConnectionLost()
            throw error
        }
    }

    override fun close() {
        if (closed) return
        capture?.let { request -> runCatching { invoke(request) { it.closeAudio(request) } } }
        capture = null
        closed = true
    }
}
