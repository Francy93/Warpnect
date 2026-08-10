#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace warpnect::audio {

enum class AudioCodec : std::uint8_t {
    Unknown = 0,
    Opus = 1,
};

enum class AudioCaptureSource : std::uint8_t {
    SystemAudio = 0,
    MicrophoneAudio = 1,
};

enum class AudioBitrateMode : std::uint8_t {
    ConstantBitrate = 0,
    ConstrainedVariableBitrate = 1,
};

enum class AudioTimestampQuality : std::uint8_t {
    AudioRecordTimestamp = 0,
    EstimatedFromReadCompletion = 1,
    Unavailable = 2,
};

enum class AudioEncoderError : std::uint8_t {
    None = 0,
    UnsupportedCodec = 1,
    UnsupportedSampleRate = 2,
    UnsupportedChannelCount = 3,
    UnsupportedFrameDuration = 4,
    InvalidBitrate = 5,
    InvalidComplexity = 6,
    InvalidPcmRange = 7,
    NonDirectPcmBuffer = 8,
    DependencyUnavailable = 9,
    EncoderCreateFailed = 10,
    EncoderConfigureFailed = 11,
    EncoderEncodeFailed = 12,
    EncoderControlFailed = 13,
    PcmDiscontinuity = 14,
    OutputSinkFailure = 15,
    AlreadyPrepared = 16,
    AlreadyRunning = 17,
    NotPrepared = 18,
    NotRunning = 19,
    Closed = 20,
};

enum class AudioEncoderSubmitStatus : std::uint8_t {
    NeedMoreInput = 0,
    EncodedFrameReady = 1,
    Discontinuity = 2,
    Failure = 3,
};

struct OpusAudioEncoderConfig final {
    AudioCodec codec = AudioCodec::Opus;
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t sample_rate_hz = 48000;
    std::uint8_t channel_count = 1;
    std::uint32_t frame_duration_us = 5000;
    std::uint32_t bitrate_bps = 64000;
    AudioBitrateMode bitrate_mode = AudioBitrateMode::ConstantBitrate;
    std::uint8_t complexity = 5;
};

struct AudioEncoderStatus final {
    AudioEncoderError error = AudioEncoderError::None;
    std::int32_t native_error = 0;
};

struct AudioEncoderSubmitResult final {
    AudioEncoderError error = AudioEncoderError::None;
    AudioEncoderSubmitStatus status = AudioEncoderSubmitStatus::NeedMoreInput;
    std::int32_t native_error = 0;
    std::size_t consumed_bytes = 0;
    std::size_t packet_size = 0;
    std::uint64_t first_frame_position = 0;
    std::uint64_t capture_time_ns = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    std::uint64_t encoded_frame_index = 0;
    std::uint64_t expected_frame_position = 0;
    std::uint64_t actual_frame_position = 0;
    bool direct_fast_path = false;
    bool assembler_path = false;
};

struct AudioEncoderStopResult final {
    AudioEncoderError error = AudioEncoderError::None;
    std::uint64_t tail_frames_dropped = 0;
};

struct AudioEncoderSnapshot final {
    AudioCodec codec = AudioCodec::Opus;
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    std::uint32_t frame_duration_us = 0;
    std::uint32_t samples_per_frame = 0;
    std::uint32_t bitrate_bps = 0;
    AudioBitrateMode bitrate_mode = AudioBitrateMode::ConstantBitrate;
    std::uint8_t complexity = 0;
    std::uint32_t lookahead_samples = 0;
    std::uint64_t pcm_chunks_received = 0;
    std::uint64_t pcm_frames_received = 0;
    std::uint64_t encoded_frames = 0;
    std::uint64_t encoded_bytes = 0;
    std::uint64_t direct_fast_path_frames = 0;
    std::uint64_t assembler_frames = 0;
    std::uint32_t partial_frame_samples = 0;
    std::uint64_t pcm_discontinuities = 0;
    std::uint64_t pcm_frames_skipped = 0;
    std::uint64_t tail_frames_dropped = 0;
    std::uint64_t last_input_frame_position = 0;
    std::uint64_t last_encoded_frame_position = 0;
    std::uint64_t last_capture_time_ns = 0;
    std::int32_t last_native_error = 0;
    AudioEncoderError last_error = AudioEncoderError::None;
    bool prepared = false;
    bool running = false;
    bool closed = false;
};

class OpusAudioEncoder final {
public:
    static constexpr std::size_t kMaxOpusPacketBytes = 1275;

    explicit OpusAudioEncoder(OpusAudioEncoderConfig config);
    ~OpusAudioEncoder();

    OpusAudioEncoder(const OpusAudioEncoder&) = delete;
    OpusAudioEncoder& operator=(const OpusAudioEncoder&) = delete;

    [[nodiscard]] AudioEncoderStatus prepare();
    [[nodiscard]] AudioEncoderStatus start();
    [[nodiscard]] AudioEncoderStopResult stop();
    [[nodiscard]] AudioEncoderStatus update_bitrate(std::uint32_t bitrate_bps);
    [[nodiscard]] AudioEncoderSubmitResult submit_pcm(std::span<const std::byte> pcm,
                                                       std::uint64_t first_frame_position,
                                                       std::uint64_t capture_time_ns,
                                                       AudioTimestampQuality timestamp_quality);
    void close() noexcept;

    [[nodiscard]] std::span<std::byte> output_buffer() noexcept;
    [[nodiscard]] const OpusAudioEncoderConfig& config() const noexcept;
    [[nodiscard]] AudioEncoderSnapshot snapshot() const noexcept;

    [[nodiscard]] static AudioEncoderError validate_config(
        const OpusAudioEncoderConfig& config) noexcept;
    [[nodiscard]] static std::uint32_t samples_per_frame(std::uint32_t sample_rate_hz,
                                                         std::uint32_t frame_duration_us) noexcept;

private:
    [[nodiscard]] AudioEncoderStatus configure_encoder();
    [[nodiscard]] AudioEncoderSubmitResult encode_direct(const std::byte* pcm,
                                                         std::uint64_t first_frame_position,
                                                         std::uint64_t capture_time_ns,
                                                         AudioTimestampQuality timestamp_quality);
    [[nodiscard]] AudioEncoderSubmitResult encode_accumulator();
    void reset_runtime() noexcept;
    void record_error(AudioEncoderError error, std::int32_t native_error = 0) noexcept;

    OpusAudioEncoderConfig config_{};
    void* encoder_ = nullptr;
    std::vector<std::int16_t> accumulator_{};
    std::vector<std::byte> packet_scratch_{};
    std::uint32_t samples_per_frame_ = 0;
    std::uint32_t accumulator_frames_ = 0;
    std::uint64_t accumulator_first_frame_position_ = 0;
    std::uint64_t accumulator_capture_time_ns_ = 0;
    AudioTimestampQuality accumulator_timestamp_quality_ = AudioTimestampQuality::Unavailable;
    bool has_expected_frame_position_ = false;
    std::uint64_t expected_frame_position_ = 0;
    std::uint64_t next_encoded_frame_index_ = 0;
    AudioEncoderSnapshot snapshot_{};
};

} // namespace warpnect::audio
