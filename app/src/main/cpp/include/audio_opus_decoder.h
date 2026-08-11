#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "audio_opus_encoder.h"

namespace warpnect::audio {

enum class AudioDecoderError : std::uint8_t {
    None = 0,
    UnsupportedCodec = 1,
    UnsupportedSampleRate = 2,
    UnsupportedChannelCount = 3,
    UnsupportedFrameDuration = 4,
    InvalidConfigGeneration = 5,
    ReconfigurationRequired = 6,
    NonDirectBuffer = 7,
    InvalidBufferRange = 8,
    EncodedPacketEmpty = 9,
    EncodedPacketTooLarge = 10,
    MalformedOpusPacket = 11,
    UnexpectedPacketDuration = 12,
    UnexpectedDecodedFrameSize = 13,
    DecoderCreateFailed = 14,
    DecoderDecodeFailed = 15,
    DecoderControlFailed = 16,
    InvalidMissingFrameMetadata = 17,
    PacketLossConcealmentFailed = 18,
    OutputSinkFailure = 19,
    AlreadyPrepared = 20,
    AlreadyRunning = 21,
    NotPrepared = 22,
    NotRunning = 23,
    Closed = 24,
};

enum class DecodedAudioFrameKind : std::uint8_t {
    Normal = 0,
    PacketLossConcealment = 1,
};

struct OpusAudioDecoderConfig final {
    AudioCodec codec = AudioCodec::Opus;
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t config_generation = 1;
    std::uint32_t sample_rate_hz = 48000;
    std::uint8_t channel_count = 1;
    std::uint32_t frame_duration_us = 5000;
    std::uint32_t lookahead_samples = 0;
};

struct EncodedAudioFrameMetadata final {
    std::uint32_t config_generation = 0;
    std::uint64_t first_frame_position = 0;
    std::uint64_t capture_time_us = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
};

struct MissingAudioFrameMetadata final {
    std::uint32_t config_generation = 0;
    std::uint64_t first_frame_position = 0;
    std::uint64_t capture_time_us = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
};

struct AudioDecoderStatus final {
    AudioDecoderError error = AudioDecoderError::None;
    std::int32_t native_error = 0;
};

struct AudioDecoderDecodeResult final {
    AudioDecoderError error = AudioDecoderError::None;
    std::int32_t native_error = 0;
    DecodedAudioFrameKind frame_kind = DecodedAudioFrameKind::Normal;
    std::size_t pcm_size_bytes = 0;
    std::uint32_t frame_count = 0;
    std::uint64_t first_frame_position = 0;
    std::uint64_t capture_time_us = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
};

struct AudioDecoderSnapshot final {
    AudioCodec codec = AudioCodec::Opus;
    AudioCaptureSource source = AudioCaptureSource::MicrophoneAudio;
    std::uint32_t config_generation = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    std::uint32_t frame_duration_us = 0;
    std::uint32_t samples_per_frame = 0;
    std::uint32_t lookahead_samples = 0;
    std::uint64_t packets_submitted = 0;
    std::uint64_t encoded_bytes_submitted = 0;
    std::uint64_t frames_decoded = 0;
    std::uint64_t pcm_frames_decoded = 0;
    std::uint64_t pcm_bytes_decoded = 0;
    std::uint64_t plc_frames_generated = 0;
    std::uint64_t malformed_packets = 0;
    std::uint64_t duration_mismatches = 0;
    std::uint64_t decode_failures = 0;
    std::uint64_t sink_failures = 0;
    std::uint64_t last_frame_position = 0;
    std::uint64_t last_capture_time_us = 0;
    std::uint32_t last_decoded_samples = 0;
    std::int32_t last_native_error = 0;
    AudioDecoderError last_error = AudioDecoderError::None;
    bool prepared = false;
    bool running = false;
    bool closed = false;
};

class OpusAudioDecoder final {
public:
    explicit OpusAudioDecoder(OpusAudioDecoderConfig config);
    ~OpusAudioDecoder();

    OpusAudioDecoder(const OpusAudioDecoder&) = delete;
    OpusAudioDecoder& operator=(const OpusAudioDecoder&) = delete;

    [[nodiscard]] AudioDecoderStatus prepare();
    [[nodiscard]] AudioDecoderStatus start();
    [[nodiscard]] AudioDecoderStatus stop();
    [[nodiscard]] AudioDecoderDecodeResult
    decode(std::span<const std::byte> encoded_packet,
           const EncodedAudioFrameMetadata& metadata);
    [[nodiscard]] AudioDecoderDecodeResult
    conceal_missing_frame(const MissingAudioFrameMetadata& metadata);
    void close() noexcept;

    [[nodiscard]] std::span<std::byte> output_buffer() noexcept;
    [[nodiscard]] const OpusAudioDecoderConfig& config() const noexcept;
    [[nodiscard]] AudioDecoderSnapshot snapshot() const noexcept;

    [[nodiscard]] static AudioDecoderError validate_config(
        const OpusAudioDecoderConfig& config) noexcept;
    [[nodiscard]] static std::uint32_t samples_per_frame(std::uint32_t sample_rate_hz,
                                                         std::uint32_t frame_duration_us) noexcept;

private:
    [[nodiscard]] AudioDecoderDecodeResult
    decode_into_output(const unsigned char* data,
                       std::int32_t length,
                       const EncodedAudioFrameMetadata& metadata,
                       DecodedAudioFrameKind frame_kind);
    void reset_runtime() noexcept;
    void release_decoder() noexcept;
    void record_error(AudioDecoderError error, std::int32_t native_error = 0) noexcept;

    OpusAudioDecoderConfig config_{};
    void* decoder_ = nullptr;
    std::vector<std::int16_t> pcm_scratch_{};
    std::uint32_t samples_per_frame_ = 0;
    AudioDecoderSnapshot snapshot_{};
};

} // namespace warpnect::audio
