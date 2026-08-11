#include "audio_protocol.h"

#include <algorithm>

#include "internal/byte_order.h"
#include "protocol.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr AudioTransportStatus status(AudioTransportError error) noexcept {
    return AudioTransportStatus{.error = error};
}

[[nodiscard]] constexpr AudioTransportEncodeResult
encode_error(AudioTransportError error) noexcept {
    return AudioTransportEncodeResult{.error = error};
}

[[nodiscard]] constexpr AudioStreamConfigDecodeResult
config_error(AudioTransportError error) noexcept {
    return AudioStreamConfigDecodeResult{.error = error};
}

[[nodiscard]] constexpr AudioFrameDecodeResult frame_error(AudioTransportError error) noexcept {
    return AudioFrameDecodeResult{.error = error};
}

[[nodiscard]] constexpr bool is_supported_sample_rate(std::uint32_t sample_rate_hz) noexcept {
    return sample_rate_hz == 8000U || sample_rate_hz == 12000U ||
           sample_rate_hz == 16000U || sample_rate_hz == 24000U ||
           sample_rate_hz == 48000U;
}

[[nodiscard]] constexpr bool is_supported_channel_count(std::uint8_t channel_count) noexcept {
    return channel_count == 1U || channel_count == 2U;
}

[[nodiscard]] constexpr bool is_supported_codec(AudioCodec codec) noexcept {
    return codec == AudioCodec::Opus;
}

[[nodiscard]] constexpr AudioMessageType decode_message_type(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(AudioMessageType::StreamConfig):
        return AudioMessageType::StreamConfig;
    case static_cast<std::uint8_t>(AudioMessageType::AudioFrame):
        return AudioMessageType::AudioFrame;
    default:
        return AudioMessageType::Unknown;
    }
}

[[nodiscard]] constexpr AudioCodec decode_codec(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(AudioCodec::Opus):
        return AudioCodec::Opus;
    default:
        return AudioCodec::Unknown;
    }
}

[[nodiscard]] constexpr AudioFrameDurationCode decode_frame_duration(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(AudioFrameDurationCode::Ms2_5):
        return AudioFrameDurationCode::Ms2_5;
    case static_cast<std::uint8_t>(AudioFrameDurationCode::Ms5):
        return AudioFrameDurationCode::Ms5;
    case static_cast<std::uint8_t>(AudioFrameDurationCode::Ms10):
        return AudioFrameDurationCode::Ms10;
    case static_cast<std::uint8_t>(AudioFrameDurationCode::Ms20):
        return AudioFrameDurationCode::Ms20;
    default:
        return AudioFrameDurationCode::Unknown;
    }
}

[[nodiscard]] AudioTransportError decode_common_header(std::span<const std::byte> payload,
                                                       AudioMessageHeader& header) noexcept {
    if (payload.size() < kAudioMessageHeaderWireSize) {
        return AudioTransportError::MalformedAudioPayload;
    }

    header.audio_version = static_cast<std::uint8_t>(payload[kAudioMessageVersionOffset]);
    header.message_type = decode_message_type(payload[kAudioMessageTypeOffset]);
    header.codec = decode_codec(payload[kAudioMessageCodecOffset]);
    header.flags = static_cast<std::uint8_t>(payload[kAudioMessageFlagsOffset]);
    if (!internal::read_u32_be(payload, kAudioMessageConfigGenerationOffset,
                               header.config_generation)) {
        return AudioTransportError::MalformedAudioPayload;
    }

    return validate_audio_message_header(header).error;
}

} // namespace

bool is_supported_audio_payload_type(PayloadType payload_type) noexcept {
    return payload_type == PayloadType::SystemAudio ||
           payload_type == PayloadType::MicrophoneAudio;
}

AudioFrameDurationCode frame_duration_code_from_us(std::uint32_t duration_us) noexcept {
    switch (duration_us) {
    case 2500U:
        return AudioFrameDurationCode::Ms2_5;
    case 5000U:
        return AudioFrameDurationCode::Ms5;
    case 10000U:
        return AudioFrameDurationCode::Ms10;
    case 20000U:
        return AudioFrameDurationCode::Ms20;
    default:
        return AudioFrameDurationCode::Unknown;
    }
}

std::uint32_t frame_duration_us(AudioFrameDurationCode code) noexcept {
    switch (code) {
    case AudioFrameDurationCode::Ms2_5:
        return 2500U;
    case AudioFrameDurationCode::Ms5:
        return 5000U;
    case AudioFrameDurationCode::Ms10:
        return 10000U;
    case AudioFrameDurationCode::Ms20:
        return 20000U;
    case AudioFrameDurationCode::Unknown:
        return 0U;
    }
    return 0U;
}

AudioTimestampQuality decode_audio_timestamp_quality(std::uint8_t wire) noexcept {
    switch (wire) {
    case static_cast<std::uint8_t>(AudioTimestampQuality::Unavailable):
        return AudioTimestampQuality::Unavailable;
    case static_cast<std::uint8_t>(AudioTimestampQuality::AudioRecordTimestamp):
        return AudioTimestampQuality::AudioRecordTimestamp;
    case static_cast<std::uint8_t>(AudioTimestampQuality::EstimatedFromReadCompletion):
        return AudioTimestampQuality::EstimatedFromReadCompletion;
    default:
        return AudioTimestampQuality::Reserved;
    }
}

std::uint8_t encode_audio_frame_flags(AudioTimestampQuality quality,
                                      bool discontinuity_before) noexcept {
    const auto quality_bits =
        static_cast<std::uint8_t>(static_cast<std::uint8_t>(quality)
                                  << kAudioFrameTimestampQualityShift);
    return static_cast<std::uint8_t>(
        quality_bits |
        (discontinuity_before ? kAudioFrameDiscontinuityBeforeFlag : std::uint8_t{0}));
}

AudioTransportStatus validate_audio_message_header(const AudioMessageHeader& header) noexcept {
    if (header.audio_version != kAudioPayloadVersion) {
        return status(AudioTransportError::UnsupportedAudioVersion);
    }
    if (header.message_type == AudioMessageType::Unknown) {
        return status(AudioTransportError::UnsupportedAudioMessageType);
    }
    if (!is_supported_codec(header.codec)) {
        return status(AudioTransportError::UnsupportedAudioCodec);
    }
    if (header.config_generation == 0) {
        return status(AudioTransportError::InvalidConfigGeneration);
    }
    if (header.message_type == AudioMessageType::StreamConfig && header.flags != 0) {
        return status(AudioTransportError::InvalidAudioFlags);
    }
    if (header.message_type == AudioMessageType::AudioFrame) {
        if ((header.flags & kAudioFrameReservedFlagsMask) != 0) {
            return status(AudioTransportError::InvalidAudioFlags);
        }
        const auto quality = decode_audio_timestamp_quality(
            static_cast<std::uint8_t>((header.flags & kAudioFrameTimestampQualityMask) >>
                                      kAudioFrameTimestampQualityShift));
        if (quality == AudioTimestampQuality::Reserved) {
            return status(AudioTransportError::InvalidTimestampQuality);
        }
    }
    return status(AudioTransportError::None);
}

AudioTransportStatus encode_audio_message_header(const AudioMessageHeader& header,
                                                 std::span<std::byte> output) noexcept {
    if (output.size() < kAudioMessageHeaderWireSize) {
        return status(AudioTransportError::MalformedAudioPayload);
    }
    const AudioTransportStatus validation = validate_audio_message_header(header);
    if (!validation.ok()) {
        return validation;
    }

    output[kAudioMessageVersionOffset] = static_cast<std::byte>(header.audio_version);
    output[kAudioMessageTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.message_type));
    output[kAudioMessageCodecOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.codec));
    output[kAudioMessageFlagsOffset] = static_cast<std::byte>(header.flags);
    const bool wrote =
        internal::write_u32_be(header.config_generation, output,
                               kAudioMessageConfigGenerationOffset);
    return status(wrote ? AudioTransportError::None
                        : AudioTransportError::MalformedAudioPayload);
}

AudioStreamConfigWireBytes
make_audio_stream_config_payload(std::uint32_t config_generation,
                                 std::uint32_t sample_rate_hz,
                                 std::uint8_t channel_count,
                                 AudioFrameDurationCode frame_duration,
                                 std::uint32_t lookahead_samples) noexcept {
    AudioStreamConfigWireBytes payload{};
    (void)encode_audio_message_header(
        AudioMessageHeader{
            .audio_version = kAudioPayloadVersion,
            .message_type = AudioMessageType::StreamConfig,
            .codec = AudioCodec::Opus,
            .flags = 0,
            .config_generation = config_generation,
        },
        payload);
    (void)internal::write_u32_be(sample_rate_hz, payload, kAudioStreamConfigSampleRateOffset);
    payload[kAudioStreamConfigChannelCountOffset] = static_cast<std::byte>(channel_count);
    payload[kAudioStreamConfigFrameDurationOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(frame_duration));
    payload[kAudioStreamConfigReservedOffset] = std::byte{0};
    payload[kAudioStreamConfigReservedOffset + 1] = std::byte{0};
    (void)internal::write_u32_be(lookahead_samples, payload, kAudioStreamConfigLookaheadOffset);
    return payload;
}

AudioTransportEncodeResult encode_audio_stream_config_payload(
    std::uint32_t config_generation,
    std::uint32_t sample_rate_hz,
    std::uint8_t channel_count,
    AudioFrameDurationCode frame_duration,
    std::uint32_t lookahead_samples,
    std::span<std::byte> output) noexcept {
    if (output.size() < kAudioStreamConfigWireSize) {
        return encode_error(AudioTransportError::MalformedAudioPayload);
    }
    if (config_generation == 0) {
        return encode_error(AudioTransportError::InvalidConfigGeneration);
    }
    if (!is_supported_sample_rate(sample_rate_hz)) {
        return encode_error(AudioTransportError::InvalidSampleRate);
    }
    if (!is_supported_channel_count(channel_count)) {
        return encode_error(AudioTransportError::InvalidChannelCount);
    }
    if (frame_duration == AudioFrameDurationCode::Unknown) {
        return encode_error(AudioTransportError::InvalidFrameDuration);
    }
    const auto payload = make_audio_stream_config_payload(config_generation, sample_rate_hz,
                                                         channel_count, frame_duration,
                                                         lookahead_samples);
    const auto decoded = decode_audio_stream_config(std::span<const std::byte>(payload));
    if (!decoded.ok()) {
        return encode_error(decoded.error);
    }
    std::copy(payload.begin(), payload.end(), output.begin());
    return AudioTransportEncodeResult{.bytes_written = kAudioStreamConfigWireSize};
}

AudioFramePrefixWireBytes make_audio_frame_prefix(std::uint32_t config_generation,
                                                  std::uint64_t first_frame_position,
                                                  AudioTimestampQuality timestamp_quality,
                                                  bool discontinuity_before) noexcept {
    AudioFramePrefixWireBytes prefix{};
    const std::uint8_t flags = encode_audio_frame_flags(timestamp_quality, discontinuity_before);
    (void)encode_audio_message_header(
        AudioMessageHeader{
            .audio_version = kAudioPayloadVersion,
            .message_type = AudioMessageType::AudioFrame,
            .codec = AudioCodec::Opus,
            .flags = flags,
            .config_generation = config_generation,
        },
        prefix);
    (void)internal::write_u64_be(first_frame_position, prefix,
                                 kAudioFrameFirstFramePositionOffset);
    return prefix;
}

AudioStreamConfigDecodeResult
decode_audio_stream_config(std::span<const std::byte> payload) noexcept {
    if (payload.size() != kAudioStreamConfigWireSize) {
        return config_error(AudioTransportError::MalformedAudioPayload);
    }
    AudioMessageHeader header{};
    const AudioTransportError common = decode_common_header(payload, header);
    if (common != AudioTransportError::None) {
        return config_error(common);
    }
    if (header.message_type != AudioMessageType::StreamConfig) {
        return config_error(AudioTransportError::UnsupportedAudioMessageType);
    }

    std::uint32_t sample_rate_hz = 0;
    std::uint32_t lookahead_samples = 0;
    const bool read =
        internal::read_u32_be(payload, kAudioStreamConfigSampleRateOffset, sample_rate_hz) &&
        internal::read_u32_be(payload, kAudioStreamConfigLookaheadOffset, lookahead_samples);
    if (!read) {
        return config_error(AudioTransportError::MalformedAudioPayload);
    }
    if (!is_supported_sample_rate(sample_rate_hz)) {
        return config_error(AudioTransportError::InvalidSampleRate);
    }
    const auto channel_count =
        static_cast<std::uint8_t>(payload[kAudioStreamConfigChannelCountOffset]);
    if (!is_supported_channel_count(channel_count)) {
        return config_error(AudioTransportError::InvalidChannelCount);
    }
    const auto duration = decode_frame_duration(payload[kAudioStreamConfigFrameDurationOffset]);
    if (duration == AudioFrameDurationCode::Unknown) {
        return config_error(AudioTransportError::InvalidFrameDuration);
    }
    if (payload[kAudioStreamConfigReservedOffset] != std::byte{0} ||
        payload[kAudioStreamConfigReservedOffset + 1] != std::byte{0}) {
        return config_error(AudioTransportError::MalformedAudioPayload);
    }

    return AudioStreamConfigDecodeResult{
        .config =
            AudioStreamConfigView{
                .codec = header.codec,
                .config_generation = header.config_generation,
                .sample_rate_hz = sample_rate_hz,
                .channel_count = channel_count,
                .frame_duration = duration,
                .frame_duration_us = frame_duration_us(duration),
                .lookahead_samples = lookahead_samples,
            },
    };
}

AudioFrameDecodeResult decode_audio_frame(std::span<const std::byte> payload,
                                          std::uint64_t capture_time_us) noexcept {
    if (payload.size() < kAudioFramePrefixWireSize) {
        return frame_error(AudioTransportError::MalformedAudioPayload);
    }
    AudioMessageHeader header{};
    const AudioTransportError common = decode_common_header(payload, header);
    if (common != AudioTransportError::None) {
        return frame_error(common);
    }
    if (header.message_type != AudioMessageType::AudioFrame) {
        return frame_error(AudioTransportError::UnsupportedAudioMessageType);
    }

    std::uint64_t first_frame_position = 0;
    if (!internal::read_u64_be(payload, kAudioFrameFirstFramePositionOffset,
                               first_frame_position)) {
        return frame_error(AudioTransportError::MalformedAudioPayload);
    }
    auto encoded = payload.subspan(kAudioFramePrefixWireSize);
    if (encoded.empty()) {
        return frame_error(AudioTransportError::EncodedPacketEmpty);
    }
    const auto quality = decode_audio_timestamp_quality(
        static_cast<std::uint8_t>((header.flags & kAudioFrameTimestampQualityMask) >>
                                  kAudioFrameTimestampQualityShift));
    if (quality == AudioTimestampQuality::Reserved) {
        return frame_error(AudioTransportError::InvalidTimestampQuality);
    }

    return AudioFrameDecodeResult{
        .frame =
            AudioFrameView{
                .codec = header.codec,
                .config_generation = header.config_generation,
                .first_frame_position = first_frame_position,
                .discontinuity_before =
                    (header.flags & kAudioFrameDiscontinuityBeforeFlag) != 0,
                .timestamp_quality = quality,
                .capture_time_us = capture_time_us,
                .encoded_packet = encoded,
            },
    };
}

std::uint32_t next_audio_config_generation(std::uint32_t current) noexcept {
    const std::uint32_t next = current + 1U;
    return next == 0 ? 1U : next;
}

} // namespace warpnect::scl
