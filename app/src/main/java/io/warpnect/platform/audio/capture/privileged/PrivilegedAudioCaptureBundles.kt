package io.warpnect.platform.audio.capture.privileged

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioCaptureState
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampQuality

internal data class PrivilegedSystemAudioSetup(
    val error: AudioCaptureError,
    val format: AudioCaptureFormat?,
    val actualBufferSizeFrames: Int,
    val ringCapacity: Int,
    val sharedMemory: SharedMemory?,
    val notifyReadFd: ParcelFileDescriptor?,
    val ackWriteFd: ParcelFileDescriptor?,
)

internal fun AudioCaptureCapabilities.toBundle(): Bundle = Bundle().apply {
    putInt(PrivilegedAudioCaptureContract.KEY_ERROR, lastError.code)
    putString(PrivilegedAudioCaptureContract.KEY_SOURCE, source.name)
    putBoolean(PrivilegedAudioCaptureContract.KEY_AVAILABLE, available)
    putInt(PrivilegedAudioCaptureContract.KEY_SAMPLE_RATE_HZ, selectedSampleRateHz ?: 0)
    putInt(PrivilegedAudioCaptureContract.KEY_CHANNEL_COUNT, channelCount ?: 0)
    putString(PrivilegedAudioCaptureContract.KEY_ENCODING, encoding.name)
    putBoolean(PrivilegedAudioCaptureContract.KEY_PRIVILEGED_BACKEND_AVAILABLE, privilegedBackendAvailable)
    putBoolean(
        PrivilegedAudioCaptureContract.KEY_UNPROCESSED_MICROPHONE_SUPPORTED,
        unprocessedMicrophoneSupported,
    )
    putInt(PrivilegedAudioCaptureContract.KEY_REQUESTED_BUFFER_SIZE_BYTES, requestedBufferSizeBytes)
    putInt(PrivilegedAudioCaptureContract.KEY_ACTUAL_BUFFER_SIZE_FRAMES, actualBufferSizeFrames)
    putString(PrivilegedAudioCaptureContract.KEY_TIMESTAMP_QUALITY, timestampSupport.name)
}

internal fun Bundle.toAudioCaptureCapabilities(): AudioCaptureCapabilities {
    val source = getString(PrivilegedAudioCaptureContract.KEY_SOURCE)
        ?.let { runCatching { AudioCaptureSource.valueOf(it) }.getOrNull() }
        ?: AudioCaptureSource.SystemAudio
    return AudioCaptureCapabilities(
        source = source,
        available = getBoolean(PrivilegedAudioCaptureContract.KEY_AVAILABLE, false),
        selectedSampleRateHz = getInt(PrivilegedAudioCaptureContract.KEY_SAMPLE_RATE_HZ).takeIf { it > 0 },
        channelCount = getInt(PrivilegedAudioCaptureContract.KEY_CHANNEL_COUNT).takeIf { it > 0 },
        encoding = getString(PrivilegedAudioCaptureContract.KEY_ENCODING)
            ?.let { runCatching { AudioPcmEncoding.valueOf(it) }.getOrNull() }
            ?: AudioPcmEncoding.Pcm16,
        privilegedBackendAvailable = getBoolean(
            PrivilegedAudioCaptureContract.KEY_PRIVILEGED_BACKEND_AVAILABLE,
            false,
        ),
        unprocessedMicrophoneSupported = getBoolean(
            PrivilegedAudioCaptureContract.KEY_UNPROCESSED_MICROPHONE_SUPPORTED,
            false,
        ),
        requestedBufferSizeBytes = getInt(PrivilegedAudioCaptureContract.KEY_REQUESTED_BUFFER_SIZE_BYTES),
        actualBufferSizeFrames = getInt(PrivilegedAudioCaptureContract.KEY_ACTUAL_BUFFER_SIZE_FRAMES),
        timestampSupport = getString(PrivilegedAudioCaptureContract.KEY_TIMESTAMP_QUALITY)
            ?.let { runCatching { AudioTimestampQuality.valueOf(it) }.getOrNull() }
            ?: AudioTimestampQuality.Unavailable,
        lastError = AudioCaptureError.fromCode(getInt(PrivilegedAudioCaptureContract.KEY_ERROR)),
    )
}

internal fun AudioCaptureSnapshot.toBundle(): Bundle = Bundle().apply {
    putInt(PrivilegedAudioCaptureContract.KEY_ERROR, lastError.code)
    putString(PrivilegedAudioCaptureContract.KEY_STATE, state.name)
    source?.let { putString(PrivilegedAudioCaptureContract.KEY_SOURCE, it.name) }
    putInt(PrivilegedAudioCaptureContract.KEY_SAMPLE_RATE_HZ, sampleRateHz)
    putInt(PrivilegedAudioCaptureContract.KEY_CHANNEL_COUNT, channelCount)
    putInt(PrivilegedAudioCaptureContract.KEY_BYTES_PER_FRAME, bytesPerFrame)
    putInt(PrivilegedAudioCaptureContract.KEY_TARGET_CHUNK_FRAMES, targetChunkFrames)
    putInt(
        PrivilegedAudioCaptureContract.KEY_ACTUAL_BUFFER_SIZE_FRAMES,
        actualAudioRecordBufferFrames,
    )
    putLong(PrivilegedAudioCaptureContract.KEY_CHUNKS_CAPTURED, chunksCaptured)
    putLong(PrivilegedAudioCaptureContract.KEY_FRAMES_CAPTURED, framesCaptured)
    putLong(PrivilegedAudioCaptureContract.KEY_BYTES_CAPTURED, bytesCaptured)
    putLong(PrivilegedAudioCaptureContract.KEY_TIMESTAMP_SUCCESSES, timestampSuccesses)
    putLong(PrivilegedAudioCaptureContract.KEY_TIMESTAMP_FALLBACKS, timestampFallbacks)
    putLong(PrivilegedAudioCaptureContract.KEY_SINK_FAILURES, sinkFailures)
    putInt(PrivilegedAudioCaptureContract.KEY_RING_CAPACITY, ringCapacity)
    putInt(PrivilegedAudioCaptureContract.KEY_RING_OCCUPANCY, ringOccupancy)
    putInt(PrivilegedAudioCaptureContract.KEY_RING_HIGH_WATER, ringHighWaterMark)
    putLong(PrivilegedAudioCaptureContract.KEY_RING_OVERRUNS, ringOverruns)
    putLong(PrivilegedAudioCaptureContract.KEY_CHUNKS_DROPPED, chunksDropped)
    putLong(PrivilegedAudioCaptureContract.KEY_FRAMES_DROPPED, framesDropped)
    putLong(PrivilegedAudioCaptureContract.KEY_LAST_FRAME_POSITION, lastFramePosition)
    putLong(PrivilegedAudioCaptureContract.KEY_LAST_CAPTURE_TIME_NS, lastCaptureTimeNs)
}

internal fun Bundle.toAudioCaptureSnapshot(): AudioCaptureSnapshot = AudioCaptureSnapshot(
    state = getString(PrivilegedAudioCaptureContract.KEY_STATE)
        ?.let { runCatching { AudioCaptureState.valueOf(it) }.getOrNull() }
        ?: AudioCaptureState.Stopped,
    source = getString(PrivilegedAudioCaptureContract.KEY_SOURCE)
        ?.let { runCatching { AudioCaptureSource.valueOf(it) }.getOrNull() },
    sampleRateHz = getInt(PrivilegedAudioCaptureContract.KEY_SAMPLE_RATE_HZ),
    channelCount = getInt(PrivilegedAudioCaptureContract.KEY_CHANNEL_COUNT),
    bytesPerFrame = getInt(PrivilegedAudioCaptureContract.KEY_BYTES_PER_FRAME),
    targetChunkFrames = getInt(PrivilegedAudioCaptureContract.KEY_TARGET_CHUNK_FRAMES),
    actualAudioRecordBufferFrames = getInt(
        PrivilegedAudioCaptureContract.KEY_ACTUAL_BUFFER_SIZE_FRAMES,
    ),
    chunksCaptured = getLong(PrivilegedAudioCaptureContract.KEY_CHUNKS_CAPTURED),
    framesCaptured = getLong(PrivilegedAudioCaptureContract.KEY_FRAMES_CAPTURED),
    bytesCaptured = getLong(PrivilegedAudioCaptureContract.KEY_BYTES_CAPTURED),
    timestampSuccesses = getLong(PrivilegedAudioCaptureContract.KEY_TIMESTAMP_SUCCESSES),
    timestampFallbacks = getLong(PrivilegedAudioCaptureContract.KEY_TIMESTAMP_FALLBACKS),
    sinkFailures = getLong(PrivilegedAudioCaptureContract.KEY_SINK_FAILURES),
    ringCapacity = getInt(PrivilegedAudioCaptureContract.KEY_RING_CAPACITY),
    ringOccupancy = getInt(PrivilegedAudioCaptureContract.KEY_RING_OCCUPANCY),
    ringHighWaterMark = getInt(PrivilegedAudioCaptureContract.KEY_RING_HIGH_WATER),
    ringOverruns = getLong(PrivilegedAudioCaptureContract.KEY_RING_OVERRUNS),
    chunksDropped = getLong(PrivilegedAudioCaptureContract.KEY_CHUNKS_DROPPED),
    framesDropped = getLong(PrivilegedAudioCaptureContract.KEY_FRAMES_DROPPED),
    lastFramePosition = getLong(PrivilegedAudioCaptureContract.KEY_LAST_FRAME_POSITION),
    lastCaptureTimeNs = getLong(PrivilegedAudioCaptureContract.KEY_LAST_CAPTURE_TIME_NS),
    lastError = AudioCaptureError.fromCode(getInt(PrivilegedAudioCaptureContract.KEY_ERROR)),
)

internal fun privilegedSystemAudioSetupFromBundle(bundle: Bundle): PrivilegedSystemAudioSetup {
    val format = bundle.toAudioCaptureFormat()
    return PrivilegedSystemAudioSetup(
        error = AudioCaptureError.fromCode(bundle.getInt(PrivilegedAudioCaptureContract.KEY_ERROR)),
        format = format,
        actualBufferSizeFrames = bundle.getInt(
            PrivilegedAudioCaptureContract.KEY_ACTUAL_BUFFER_SIZE_FRAMES,
        ),
        ringCapacity = bundle.getInt(PrivilegedAudioCaptureContract.KEY_RING_CAPACITY),
        sharedMemory = bundle.getParcelableCompat(PrivilegedAudioCaptureContract.KEY_SHARED_MEMORY),
        notifyReadFd = bundle.getParcelableCompat(PrivilegedAudioCaptureContract.KEY_NOTIFY_READ_FD),
        ackWriteFd = bundle.getParcelableCompat(PrivilegedAudioCaptureContract.KEY_ACK_WRITE_FD),
    )
}

internal fun Bundle.putFormat(format: AudioCaptureFormat) {
    putString(PrivilegedAudioCaptureContract.KEY_SOURCE, format.source.name)
    putInt(PrivilegedAudioCaptureContract.KEY_SAMPLE_RATE_HZ, format.sampleRateHz)
    putInt(PrivilegedAudioCaptureContract.KEY_CHANNEL_COUNT, format.channelCount)
    putString(PrivilegedAudioCaptureContract.KEY_ENCODING, format.encoding.name)
    putInt(PrivilegedAudioCaptureContract.KEY_BYTES_PER_FRAME, format.bytesPerFrame)
    putInt(PrivilegedAudioCaptureContract.KEY_TARGET_CHUNK_FRAMES, format.targetFramesPerChunk)
    putLong(PrivilegedAudioCaptureContract.KEY_TARGET_CHUNK_DURATION_US, format.targetChunkDurationUs)
}

private fun Bundle.toAudioCaptureFormat(): AudioCaptureFormat? {
    val sampleRate = getInt(PrivilegedAudioCaptureContract.KEY_SAMPLE_RATE_HZ)
    val channelCount = getInt(PrivilegedAudioCaptureContract.KEY_CHANNEL_COUNT)
    val bytesPerFrame = getInt(PrivilegedAudioCaptureContract.KEY_BYTES_PER_FRAME)
    val chunkFrames = getInt(PrivilegedAudioCaptureContract.KEY_TARGET_CHUNK_FRAMES)
    if (sampleRate <= 0 || channelCount <= 0 || bytesPerFrame <= 0 || chunkFrames <= 0) {
        return null
    }
    return AudioCaptureFormat(
        source = getString(PrivilegedAudioCaptureContract.KEY_SOURCE)
            ?.let { runCatching { AudioCaptureSource.valueOf(it) }.getOrNull() }
            ?: AudioCaptureSource.SystemAudio,
        sampleRateHz = sampleRate,
        channelCount = channelCount,
        encoding = getString(PrivilegedAudioCaptureContract.KEY_ENCODING)
            ?.let { runCatching { AudioPcmEncoding.valueOf(it) }.getOrNull() }
            ?: AudioPcmEncoding.Pcm16,
        bytesPerFrame = bytesPerFrame,
        targetFramesPerChunk = chunkFrames,
        targetChunkDurationUs = getLong(PrivilegedAudioCaptureContract.KEY_TARGET_CHUNK_DURATION_US),
    )
}

@Suppress("DEPRECATION")
private inline fun <reified T> Bundle.getParcelableCompat(key: String): T? = getParcelable(key)
