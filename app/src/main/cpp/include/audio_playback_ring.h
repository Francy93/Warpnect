#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <span>
#include <utility>
#include <vector>

#include "audio_opus_decoder.h"

namespace warpnect::audio {

inline constexpr std::uint32_t kPcmPlaybackRingVersion = 1;

enum class AudioPlaybackError : std::uint8_t {
    None = 0,
    InvalidConfiguration = 1,
    UnsupportedSampleRate = 2,
    UnsupportedChannelCount = 3,
    UnsupportedPcmFormat = 4,
    OboeDependencyUnavailable = 5,
    StreamOpenFailed = 6,
    StreamStartFailed = 7,
    StreamStopFailed = 8,
    StreamDisconnected = 9,
    RequestedFormatMismatch = 10,
    RequestedSampleRateMismatch = 11,
    RequestedChannelMismatch = 12,
    ExclusiveModeUnavailable = 13,
    LowLatencyModeUnavailable = 14,
    NonDirectBuffer = 15,
    InvalidBufferRange = 16,
    InvalidFrameCount = 17,
    ConfigGenerationMismatch = 18,
    PlaybackRingFull = 19,
    PlaybackNotPrimed = 20,
    PresentationTimestampUnavailable = 21,
    AlreadyPrepared = 22,
    AlreadyRunning = 23,
    NotPrepared = 24,
    NotRunning = 25,
    Closed = 26,
};

struct AudioSourcePlaybackAnchor final {
    bool valid = false;
    std::uint64_t sequence = 0;
    std::uint32_t config_generation = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    std::uint64_t source_frame_position = 0;
    std::uint64_t source_capture_time_us = 0;
    std::uint64_t output_frame_position = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
    DecodedAudioFrameKind frame_kind = DecodedAudioFrameKind::Normal;
};

struct PcmPlaybackRingConfig final {
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t config_generation = 1;
    std::uint32_t sample_rate_hz = 48000;
    std::uint8_t channel_count = 1;
    std::uint32_t frames_per_codec_frame = 240;
    std::uint32_t ring_capacity_codec_frames = 4;
};

struct DecodedPcmPlaybackMetadata final {
    std::uint32_t config_generation = 0;
    std::uint64_t first_frame_position = 0;
    std::uint64_t capture_time_us = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
    DecodedAudioFrameKind frame_kind = DecodedAudioFrameKind::Normal;
};

struct PcmPlaybackRingSubmitResult final {
    AudioPlaybackError error = AudioPlaybackError::None;
    std::uint32_t occupancy_frames = 0;
};

struct PcmPlaybackRingConsumeResult final {
    std::uint32_t frames_requested = 0;
    std::uint32_t pcm_frames_copied = 0;
    std::uint32_t silence_frames_inserted = 0;
    bool underrun = false;
    bool consumed_pcm = false;
    std::uint64_t first_consumed_frame_position = 0;
    std::uint64_t last_capture_time_us = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
    DecodedAudioFrameKind frame_kind = DecodedAudioFrameKind::Normal;
};

struct PcmPlaybackRingSnapshot final {
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t config_generation = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    std::uint32_t frames_per_codec_frame = 0;
    std::uint32_t ring_capacity_frames = 0;
    std::uint32_t ring_occupancy_frames = 0;
    std::uint32_t ring_high_water_mark = 0;
    std::uint64_t pcm_frames_submitted = 0;
    std::uint64_t pcm_frames_consumed = 0;
    std::uint64_t pcm_frames_rejected = 0;
    std::uint64_t underrun_callbacks = 0;
    std::uint64_t underrun_frames = 0;
    std::uint64_t silence_frames_inserted = 0;
    std::uint64_t normal_frames = 0;
    std::uint64_t plc_frames = 0;
    std::uint64_t discontinuity_frames = 0;
    std::uint64_t last_source_frame_position = 0;
    std::uint64_t last_capture_time_us = 0;
    std::uint64_t residence_samples = 0;
    std::uint64_t last_residence_ns = 0;
    std::uint64_t max_residence_ns = 0;
    AudioPlaybackError last_error = AudioPlaybackError::None;
    bool prepared = false;
    bool closed = false;
};

class PcmPlaybackRing final {
public:
    explicit PcmPlaybackRing(PcmPlaybackRingConfig config);

    PcmPlaybackRing(const PcmPlaybackRing&) = delete;
    PcmPlaybackRing& operator=(const PcmPlaybackRing&) = delete;

    [[nodiscard]] AudioPlaybackError prepare();
    void reset() noexcept;
    void close() noexcept;

    [[nodiscard]] PcmPlaybackRingSubmitResult submit(std::span<const std::byte> pcm,
                                                     std::uint32_t frame_count,
                                                     const DecodedPcmPlaybackMetadata& metadata,
                                                     std::uint64_t submit_time_ns = 0) noexcept;

    [[nodiscard]] PcmPlaybackRingConsumeResult consume(std::span<std::byte> output,
                                                       std::uint32_t requested_frames,
                                                       std::uint64_t consume_time_ns = 0,
                                                       std::uint64_t output_start_frame_position =
                                                           0) noexcept;

    [[nodiscard]] std::uint32_t occupancy_frames() const noexcept;
    // Callback/consumer only: the SPSC head stays owned until consume releases it.
    [[nodiscard]] std::uint64_t oldest_submit_time_ns_for_consumer() const noexcept;
    [[nodiscard]] std::uint32_t capacity_frames() const noexcept;
    [[nodiscard]] bool has_available_slot() const noexcept {
        return prepared_ && !closed_ &&
            queued_slots_.load(std::memory_order_acquire) < config_.ring_capacity_codec_frames;
    }
    [[nodiscard]] AudioSourcePlaybackAnchor latest_source_anchor() const noexcept;
    void invalidate_source_timeline() noexcept;
    [[nodiscard]] PcmPlaybackRingSnapshot snapshot() const noexcept;
    [[nodiscard]] static AudioPlaybackError validate_config(
        const PcmPlaybackRingConfig& config) noexcept;

private:
    struct Slot final {
        Slot() = default;
        Slot(Slot&& other) noexcept
            : pcm(std::move(other.pcm)), frame_count(other.frame_count.load(std::memory_order_relaxed)),
              metadata(other.metadata), submit_time_ns(other.submit_time_ns) {}
        std::vector<std::int16_t> pcm{};
        std::atomic<std::uint32_t> frame_count{0};
        DecodedPcmPlaybackMetadata metadata{};
        std::uint64_t submit_time_ns = 0;
    };

    [[nodiscard]] std::uint32_t queued_frames_relaxed() const noexcept;
    void publish_source_anchor(const Slot& slot,
                               std::uint32_t read_offset,
                               std::uint64_t output_frame_position) noexcept;
    void update_high_water(std::uint32_t occupancy_frames) noexcept;
    void record_error(AudioPlaybackError error) noexcept;

    PcmPlaybackRingConfig config_{};
    std::vector<Slot> slots_{};
    std::atomic<std::uint32_t> head_{0};
    std::atomic<std::uint32_t> tail_{0};
    std::atomic<std::uint32_t> queued_slots_{0};
    std::atomic<std::uint32_t> active_read_offset_frames_{0};
    std::atomic<std::uint64_t> anchor_sequence_{0};
    std::atomic<bool> anchor_valid_{false};
    std::atomic<std::uint32_t> anchor_config_generation_{0};
    std::atomic<std::uint32_t> anchor_sample_rate_hz_{0};
    std::atomic<std::uint8_t> anchor_channel_count_{0};
    std::atomic<std::uint64_t> anchor_source_frame_position_{0};
    std::atomic<std::uint64_t> anchor_source_capture_time_us_{0};
    std::atomic<std::uint64_t> anchor_output_frame_position_{0};
    std::atomic<std::uint8_t> anchor_timestamp_quality_{0};
    std::atomic<bool> anchor_discontinuity_before_{false};
    std::atomic<std::uint8_t> anchor_frame_kind_{0};

    // Producer, callback and numeric observer own separate fields; observation
    // must not add a mutex to the PCM data plane.
    struct AtomicSnapshot final {
        std::atomic<AudioCaptureSource> source{AudioCaptureSource::MicrophoneAudio};
        std::atomic<std::uint32_t> config_generation{0};
        std::atomic<std::uint32_t> sample_rate_hz{0};
        std::atomic<std::uint8_t> channel_count{0};
        std::atomic<std::uint32_t> frames_per_codec_frame{0};
        std::atomic<std::uint32_t> ring_capacity_frames{0};
        std::atomic<std::uint32_t> ring_occupancy_frames{0};
        std::atomic<std::uint32_t> ring_high_water_mark{0};
        std::atomic<std::uint64_t> pcm_frames_submitted{0};
        std::atomic<std::uint64_t> pcm_frames_consumed{0};
        std::atomic<std::uint64_t> pcm_frames_rejected{0};
        std::atomic<std::uint64_t> underrun_callbacks{0};
        std::atomic<std::uint64_t> underrun_frames{0};
        std::atomic<std::uint64_t> silence_frames_inserted{0};
        std::atomic<std::uint64_t> normal_frames{0};
        std::atomic<std::uint64_t> plc_frames{0};
        std::atomic<std::uint64_t> discontinuity_frames{0};
        std::atomic<std::uint64_t> last_source_frame_position{0};
        std::atomic<std::uint64_t> last_capture_time_us{0};
        std::atomic<std::uint64_t> residence_samples{0};
        std::atomic<std::uint64_t> last_residence_ns{0};
        std::atomic<std::uint64_t> max_residence_ns{0};
        std::atomic<AudioPlaybackError> last_error{AudioPlaybackError::None};
        std::atomic<bool> prepared{false};
        std::atomic<bool> closed{false};
    } snapshot_{};
    bool prepared_ = false;
    bool closed_ = false;
};

} // namespace warpnect::audio
