#ifndef WARPNECT_SCL_AUDIO_PROTOCOL_H_
#define WARPNECT_SCL_AUDIO_PROTOCOL_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "audio_transport_result.h"
#include "protocol.h"

namespace warpnect::scl {

inline constexpr std::uint8_t kAudioPayloadVersion = 1;

inline constexpr std::size_t kAudioMessageHeaderWireSize = 8;
inline constexpr std::size_t kAudioMessageVersionOffset = 0;
inline constexpr std::size_t kAudioMessageTypeOffset = 1;
inline constexpr std::size_t kAudioMessageCodecOffset = 2;
inline constexpr std::size_t kAudioMessageFlagsOffset = 3;
inline constexpr std::size_t kAudioMessageConfigGenerationOffset = 4;

inline constexpr std::size_t kAudioStreamConfigWireSize = 20;
inline constexpr std::size_t kAudioStreamConfigSampleRateOffset = 8;
inline constexpr std::size_t kAudioStreamConfigChannelCountOffset = 12;
inline constexpr std::size_t kAudioStreamConfigFrameDurationOffset = 13;
inline constexpr std::size_t kAudioStreamConfigReservedOffset = 14;
inline constexpr std::size_t kAudioStreamConfigLookaheadOffset = 16;

inline constexpr std::size_t kAudioFramePrefixWireSize = 16;
inline constexpr std::size_t kAudioFrameFirstFramePositionOffset = 8;

inline constexpr std::uint8_t kAudioFrameDiscontinuityBeforeFlag = 0x01;
inline constexpr std::uint8_t kAudioFrameTimestampQualityMask = 0x06;
inline constexpr std::uint8_t kAudioFrameTimestampQualityShift = 1;
inline constexpr std::uint8_t kAudioFrameReservedFlagsMask = 0xF8;

enum class AudioMessageType : std::uint8_t {
    Unknown = 0,
    StreamConfig = 1,
    AudioFrame = 2,
};

enum class AudioCodec : std::uint8_t {
    Unknown = 0,
    Opus = 1,
};

enum class AudioFrameDurationCode : std::uint8_t {
    Unknown = 0,
    Ms2_5 = 1,
    Ms5 = 2,
    Ms10 = 3,
    Ms20 = 4,
};

enum class AudioTimestampQuality : std::uint8_t {
    Unavailable = 0,
    AudioRecordTimestamp = 1,
    EstimatedFromReadCompletion = 2,
    Reserved = 3,
};

struct AudioMessageHeader final {
    std::uint8_t audio_version = kAudioPayloadVersion;
    AudioMessageType message_type = AudioMessageType::Unknown;
    AudioCodec codec = AudioCodec::Unknown;
    std::uint8_t flags = 0;
    std::uint32_t config_generation = 0;

    constexpr bool operator==(const AudioMessageHeader&) const = default;
};

struct AudioStreamConfigView final {
    AudioCodec codec = AudioCodec::Unknown;
    std::uint32_t config_generation = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    AudioFrameDurationCode frame_duration = AudioFrameDurationCode::Unknown;
    std::uint32_t frame_duration_us = 0;
    std::uint32_t lookahead_samples = 0;
};

struct [[nodiscard]] AudioStreamConfigDecodeResult final {
    AudioTransportError error = AudioTransportError::None;
    AudioStreamConfigView config{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == AudioTransportError::None;
    }
};

struct AudioFrameView final {
    AudioCodec codec = AudioCodec::Unknown;
    std::uint32_t config_generation = 0;
    std::uint64_t first_frame_position = 0;
    bool discontinuity_before = false;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    std::uint64_t capture_time_us = 0;
    std::span<const std::byte> encoded_packet{};
};

struct [[nodiscard]] AudioFrameDecodeResult final {
    AudioTransportError error = AudioTransportError::None;
    AudioFrameView frame{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == AudioTransportError::None;
    }
};

using AudioMessageHeaderWireBytes = std::array<std::byte, kAudioMessageHeaderWireSize>;
using AudioStreamConfigWireBytes = std::array<std::byte, kAudioStreamConfigWireSize>;
using AudioFramePrefixWireBytes = std::array<std::byte, kAudioFramePrefixWireSize>;

[[nodiscard]] bool is_supported_audio_payload_type(PayloadType payload_type) noexcept;
[[nodiscard]] AudioFrameDurationCode frame_duration_code_from_us(std::uint32_t duration_us) noexcept;
[[nodiscard]] std::uint32_t frame_duration_us(AudioFrameDurationCode code) noexcept;
[[nodiscard]] AudioTimestampQuality decode_audio_timestamp_quality(std::uint8_t wire) noexcept;
[[nodiscard]] std::uint8_t encode_audio_frame_flags(AudioTimestampQuality quality,
                                                    bool discontinuity_before) noexcept;
[[nodiscard]] AudioTransportStatus
validate_audio_message_header(const AudioMessageHeader& header) noexcept;
[[nodiscard]] AudioTransportStatus encode_audio_message_header(const AudioMessageHeader& header,
                                                               std::span<std::byte> output) noexcept;
[[nodiscard]] AudioStreamConfigWireBytes
make_audio_stream_config_payload(std::uint32_t config_generation, std::uint32_t sample_rate_hz,
                                 std::uint8_t channel_count,
                                 AudioFrameDurationCode frame_duration,
                                 std::uint32_t lookahead_samples) noexcept;
[[nodiscard]] AudioTransportEncodeResult encode_audio_stream_config_payload(
    std::uint32_t config_generation, std::uint32_t sample_rate_hz,
    std::uint8_t channel_count, AudioFrameDurationCode frame_duration,
    std::uint32_t lookahead_samples, std::span<std::byte> output) noexcept;
[[nodiscard]] AudioFramePrefixWireBytes
make_audio_frame_prefix(std::uint32_t config_generation, std::uint64_t first_frame_position,
                        AudioTimestampQuality timestamp_quality,
                        bool discontinuity_before) noexcept;
[[nodiscard]] AudioStreamConfigDecodeResult
decode_audio_stream_config(std::span<const std::byte> payload) noexcept;
[[nodiscard]] AudioFrameDecodeResult decode_audio_frame(std::span<const std::byte> payload,
                                                        std::uint64_t capture_time_us) noexcept;
[[nodiscard]] std::uint32_t next_audio_config_generation(std::uint32_t current) noexcept;

static_assert(kAudioMessageHeaderWireSize == 8);
static_assert(kAudioStreamConfigWireSize == 20);
static_assert(kAudioFramePrefixWireSize == 16);
static_assert(static_cast<std::uint8_t>(AudioMessageType::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(AudioMessageType::StreamConfig) == 1);
static_assert(static_cast<std::uint8_t>(AudioMessageType::AudioFrame) == 2);
static_assert(static_cast<std::uint8_t>(AudioCodec::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(AudioCodec::Opus) == 1);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_AUDIO_PROTOCOL_H_
