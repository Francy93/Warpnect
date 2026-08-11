#pragma once

#include <cstdint>
#include <memory>
#include <span>

#include "audio_playback_ring.h"

namespace oboe {
class AudioStream;
}

namespace warpnect::audio::android {

enum class AudioPlaybackSharingPolicy : std::uint8_t {
    RequireExclusive = 0,
    PreferExclusiveAllowShared = 1,
};

enum class AudioPlaybackModeCode : std::uint8_t {
    Unknown = 0,
    None = 1,
    PowerSaving = 2,
    LowLatency = 3,
};

enum class AudioPlaybackSharingModeCode : std::uint8_t {
    Unknown = 0,
    Exclusive = 1,
    Shared = 2,
};

enum class AudioPlaybackApiCode : std::uint8_t {
    Unknown = 0,
    OpenSLES = 1,
    AAudio = 2,
};

enum class AudioPlaybackFormatCode : std::uint8_t {
    Unknown = 0,
    I16 = 1,
};

struct OboeAudioPlaybackConfig final {
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t config_generation = 1;
    std::uint32_t sample_rate_hz = 48000;
    std::uint8_t channel_count = 1;
    std::uint32_t frame_duration_us = 5000;
    std::uint32_t frames_per_codec_frame = 240;
    std::uint32_t lookahead_samples = 0;
    std::uint32_t ring_capacity_codec_frames = 4;
    std::uint32_t start_threshold_codec_frames = 1;
    AudioPlaybackSharingPolicy sharing_policy =
        AudioPlaybackSharingPolicy::PreferExclusiveAllowShared;
    std::uint32_t requested_buffer_bursts = 2;
    bool require_low_latency_performance_mode = true;
};

struct AudioPlaybackPresentationTimestamp final {
    AudioPlaybackError error = AudioPlaybackError::None;
    bool valid = false;
    std::uint64_t frame_position = 0;
    std::uint64_t presentation_time_ns = 0;
    std::uint64_t latency_us = 0;
};

struct AudioSourcePresentationAnchor final {
    AudioPlaybackError error = AudioPlaybackError::None;
    bool valid = false;
    std::int64_t source_content_time_us = 0;
    std::uint64_t source_capture_time_us = 0;
    std::uint64_t source_frame_position = 0;
    std::uint64_t output_frame_position = 0;
    std::uint64_t local_presentation_time_ns = 0;
    std::uint64_t oboe_frame_position = 0;
    std::uint64_t oboe_presentation_time_ns = 0;
    std::uint64_t age_ns = 0;
    std::uint32_t config_generation = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint32_t lookahead_samples = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
    DecodedAudioFrameKind frame_kind = DecodedAudioFrameKind::Normal;
    std::uint64_t latency_us = 0;
};

struct OboeAudioPlaybackSnapshot final {
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t config_generation = 0;
    std::uint32_t requested_sample_rate_hz = 0;
    std::uint32_t actual_sample_rate_hz = 0;
    std::uint8_t requested_channel_count = 0;
    std::uint8_t actual_channel_count = 0;
    std::uint32_t frame_duration_us = 0;
    std::uint32_t frames_per_codec_frame = 0;
    std::uint32_t lookahead_samples = 0;
    AudioPlaybackModeCode requested_performance_mode = AudioPlaybackModeCode::LowLatency;
    AudioPlaybackModeCode actual_performance_mode = AudioPlaybackModeCode::Unknown;
    AudioPlaybackSharingModeCode requested_sharing_mode = AudioPlaybackSharingModeCode::Exclusive;
    AudioPlaybackSharingModeCode actual_sharing_mode = AudioPlaybackSharingModeCode::Unknown;
    AudioPlaybackApiCode audio_api = AudioPlaybackApiCode::Unknown;
    std::uint32_t requested_buffer_bursts = 0;
    std::uint32_t frames_per_burst = 0;
    std::uint32_t requested_buffer_frames = 0;
    std::uint32_t actual_buffer_frames = 0;
    std::uint32_t buffer_capacity_frames = 0;
    std::uint32_t hardware_sample_rate = 0;
    std::uint32_t hardware_channel_count = 0;
    std::uint32_t ring_capacity_frames = 0;
    std::uint32_t ring_occupancy_frames = 0;
    std::uint32_t ring_high_water_mark = 0;
    std::uint64_t pcm_frames_submitted = 0;
    std::uint64_t pcm_frames_consumed = 0;
    std::uint64_t pcm_frames_rejected = 0;
    std::uint64_t underrun_callbacks = 0;
    std::uint64_t underrun_frames = 0;
    std::uint64_t silence_frames_inserted = 0;
    std::int32_t xrun_count = 0;
    std::uint64_t normal_frames = 0;
    std::uint64_t plc_frames = 0;
    std::uint64_t discontinuity_frames = 0;
    std::uint64_t last_source_frame_position = 0;
    std::uint64_t last_capture_time_us = 0;
    std::uint64_t last_presentation_frame_position = 0;
    std::uint64_t last_presentation_time_ns = 0;
    bool presentation_timestamp_valid = false;
    AudioPlaybackError last_error = AudioPlaybackError::None;
    bool prepared = false;
    bool running = false;
    bool closed = false;
    bool exclusive_request_granted = false;
    AudioPlaybackFormatCode actual_format = AudioPlaybackFormatCode::Unknown;
    AudioPlaybackFormatCode hardware_format = AudioPlaybackFormatCode::Unknown;
    std::uint64_t ring_residence_samples = 0;
    std::uint64_t last_ring_residence_ns = 0;
    std::uint64_t max_ring_residence_ns = 0;
};

class OboeAudioPlayback final {
public:
    explicit OboeAudioPlayback(OboeAudioPlaybackConfig config);
    ~OboeAudioPlayback();

    OboeAudioPlayback(const OboeAudioPlayback&) = delete;
    OboeAudioPlayback& operator=(const OboeAudioPlayback&) = delete;

    [[nodiscard]] AudioPlaybackError prepare();
    [[nodiscard]] AudioPlaybackError start();
    [[nodiscard]] AudioPlaybackError stop();
    [[nodiscard]] AudioPlaybackError submit_pcm(std::span<const std::byte> pcm,
                                                std::uint32_t frame_count,
                                                const DecodedPcmPlaybackMetadata& metadata);
    [[nodiscard]] AudioPlaybackPresentationTimestamp query_presentation_timestamp();
    [[nodiscard]] AudioSourcePresentationAnchor query_source_presentation_anchor();
    [[nodiscard]] OboeAudioPlaybackSnapshot snapshot();
    void close() noexcept;

    [[nodiscard]] static AudioPlaybackError validate_config(
        const OboeAudioPlaybackConfig& config) noexcept;

private:
    class DataCallback;
    class ErrorCallback;

    [[nodiscard]] AudioPlaybackError open_stream();
    void refresh_stream_snapshot();
    void refresh_xrun_count();
    void record_error(AudioPlaybackError error) noexcept;
    [[nodiscard]] std::uint64_t monotonic_now_ns() const noexcept;
    [[nodiscard]] static std::uint64_t lookahead_duration_us(std::uint32_t lookahead_samples,
                                                             std::uint32_t sample_rate_hz) noexcept;

    OboeAudioPlaybackConfig config_{};
    std::shared_ptr<PcmPlaybackRing> ring_{};
    std::shared_ptr<DataCallback> data_callback_{};
    std::shared_ptr<ErrorCallback> error_callback_{};
    std::shared_ptr<oboe::AudioStream> stream_{};
    OboeAudioPlaybackSnapshot snapshot_{};
    bool prepared_ = false;
    bool running_ = false;
    bool closed_ = false;
};

} // namespace warpnect::audio::android
