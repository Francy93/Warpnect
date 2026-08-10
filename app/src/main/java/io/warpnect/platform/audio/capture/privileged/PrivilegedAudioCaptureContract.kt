package io.warpnect.platform.audio.capture.privileged

internal object PrivilegedAudioCaptureContract {
    const val SERVICE_VERSION = 1

    const val KEY_ERROR = "error"
    const val KEY_SOURCE = "source"
    const val KEY_AVAILABLE = "available"
    const val KEY_SAMPLE_RATE_HZ = "sample_rate_hz"
    const val KEY_CHANNEL_COUNT = "channel_count"
    const val KEY_ENCODING = "encoding"
    const val KEY_PRIVILEGED_BACKEND_AVAILABLE = "privileged_backend_available"
    const val KEY_UNPROCESSED_MICROPHONE_SUPPORTED = "unprocessed_microphone_supported"
    const val KEY_REQUESTED_BUFFER_SIZE_BYTES = "requested_buffer_size_bytes"
    const val KEY_ACTUAL_BUFFER_SIZE_FRAMES = "actual_buffer_size_frames"
    const val KEY_TIMESTAMP_QUALITY = "timestamp_quality"

    const val KEY_STATE = "state"
    const val KEY_BYTES_PER_FRAME = "bytes_per_frame"
    const val KEY_TARGET_CHUNK_FRAMES = "target_chunk_frames"
    const val KEY_TARGET_CHUNK_DURATION_US = "target_chunk_duration_us"
    const val KEY_CHUNKS_CAPTURED = "chunks_captured"
    const val KEY_FRAMES_CAPTURED = "frames_captured"
    const val KEY_BYTES_CAPTURED = "bytes_captured"
    const val KEY_TIMESTAMP_SUCCESSES = "timestamp_successes"
    const val KEY_TIMESTAMP_FALLBACKS = "timestamp_fallbacks"
    const val KEY_SINK_FAILURES = "sink_failures"
    const val KEY_RING_CAPACITY = "ring_capacity"
    const val KEY_RING_OCCUPANCY = "ring_occupancy"
    const val KEY_RING_HIGH_WATER = "ring_high_water"
    const val KEY_RING_OVERRUNS = "ring_overruns"
    const val KEY_CHUNKS_DROPPED = "chunks_dropped"
    const val KEY_FRAMES_DROPPED = "frames_dropped"
    const val KEY_LAST_FRAME_POSITION = "last_frame_position"
    const val KEY_LAST_CAPTURE_TIME_NS = "last_capture_time_ns"

    const val KEY_SHARED_MEMORY = "shared_memory"
    const val KEY_NOTIFY_READ_FD = "notify_read_fd"
    const val KEY_ACK_WRITE_FD = "ack_write_fd"
}
