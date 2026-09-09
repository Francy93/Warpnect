package io.warpnect.platform.audio.capture

/**
 * PCM Shared Ring V1 transfers the privileged capture through android.os.SharedMemory, added in
 * API 27. A SystemAudio capability must not advertise a path that cannot construct that ring.
 */
internal const val SYSTEM_AUDIO_SHARED_MEMORY_MIN_API = 27

internal fun supportsSystemAudioSharedMemory(apiLevel: Int): Boolean = apiLevel >= SYSTEM_AUDIO_SHARED_MEMORY_MIN_API
