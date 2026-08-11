#include "audio_packetizer.h"

#include <algorithm>
#include <array>
#include <limits>

#include "datagram_limits.h"
#include "fragmentation.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr PacketizedAudioResult packetized_error(
    AudioTransportError error,
    std::uint16_t emitted = 0,
    std::size_t bytes = 0) noexcept {
    return PacketizedAudioResult{.error = error, .datagrams_emitted = emitted,
                                 .bytes_emitted = bytes};
}

[[nodiscard]] constexpr bool checked_add(std::size_t lhs, std::size_t rhs,
                                         std::size_t& result) noexcept {
    if (lhs > std::numeric_limits<std::size_t>::max() - rhs) {
        return false;
    }
    result = lhs + rhs;
    return true;
}

[[nodiscard]] AudioTransportError validate_packetizer_config(AudioPacketizerConfig config,
                                                             std::size_t scratch_size) noexcept {
    if (config.max_datagram_size < kPacketHeaderWireSize + 1U ||
        config.max_datagram_size > kUdpMaxDatagramPayloadSize ||
        scratch_size < config.max_datagram_size) {
        return AudioTransportError::InvalidDatagramBudget;
    }
    return AudioTransportError::None;
}

[[nodiscard]] AudioTransportSizeResult
logical_payload_size(std::span<const AudioPayloadSegment> segments) noexcept {
    std::size_t total = 0;
    for (const AudioPayloadSegment& segment : segments) {
        if (!checked_add(total, segment.bytes.size(), total)) {
            return AudioTransportSizeResult{.error = AudioTransportError::PayloadTooLarge};
        }
    }
    return AudioTransportSizeResult{.size = total};
}

void copy_segmented_range(std::span<const AudioPayloadSegment> segments,
                          std::size_t logical_offset,
                          std::span<std::byte> output) noexcept {
    std::size_t segment_base = 0;
    std::size_t output_offset = 0;

    for (const AudioPayloadSegment& segment : segments) {
        const std::size_t segment_end = segment_base + segment.bytes.size();
        if (logical_offset < segment_end && output_offset < output.size()) {
            const std::size_t inside =
                logical_offset > segment_base ? logical_offset - segment_base : 0;
            const std::size_t available = segment.bytes.size() - inside;
            const std::size_t needed = output.size() - output_offset;
            const std::size_t to_copy = std::min(available, needed);
            std::copy(segment.bytes.begin() + static_cast<std::ptrdiff_t>(inside),
                      segment.bytes.begin() + static_cast<std::ptrdiff_t>(inside + to_copy),
                      output.begin() + static_cast<std::ptrdiff_t>(output_offset));
            output_offset += to_copy;
            logical_offset += to_copy;
        }
        segment_base = segment_end;
    }
}

[[nodiscard]] AudioTransportError map_packet_error(PacketError error) noexcept {
    return error == PacketError::None ? AudioTransportError::None
                                      : AudioTransportError::PacketEncodeFailed;
}

} // namespace

AudioPacketizer::AudioPacketizer(std::span<std::byte> datagram_scratch) noexcept
    : datagram_scratch_(datagram_scratch) {}

PacketizedAudioResult AudioPacketizer::emit_stream_config(
    AudioPacketizerConfig config,
    PayloadType payload_type,
    std::uint32_t base_sequence_number,
    std::uint32_t config_generation,
    std::uint32_t sample_rate_hz,
    std::uint8_t channel_count,
    AudioFrameDurationCode frame_duration,
    std::uint32_t lookahead_samples,
    AudioDatagramSink& sink) noexcept {
    if (!is_supported_audio_payload_type(payload_type)) {
        return packetized_error(AudioTransportError::UnsupportedAudioMessageType);
    }
    AudioStreamConfigWireBytes payload{};
    const AudioTransportEncodeResult encoded = encode_audio_stream_config_payload(
        config_generation, sample_rate_hz, channel_count, frame_duration, lookahead_samples,
        payload);
    if (!encoded.ok()) {
        return packetized_error(encoded.error);
    }

    const std::array<AudioPayloadSegment, 1> segments{
        AudioPayloadSegment{std::span<const std::byte>(payload)},
    };

    return emit_segments(
        config,
        PacketHeader{
            .protocol_version = kSclProtocolVersion,
            .flags = 0,
            .sequence_number = base_sequence_number,
            .timestamp_us = 0,
            .payload_type = payload_type,
            .slice_index = 0,
            .total_slices = 1,
        },
        std::span<const AudioPayloadSegment>(segments), sink);
}

PacketizedAudioResult AudioPacketizer::emit_audio_frame(
    AudioPacketizerConfig config,
    PayloadType payload_type,
    std::uint32_t base_sequence_number,
    std::uint64_t capture_time_us,
    std::uint32_t config_generation,
    std::uint64_t first_frame_position,
    AudioTimestampQuality timestamp_quality,
    bool discontinuity_before,
    std::span<const std::byte> encoded_packet,
    AudioDatagramSink& sink) noexcept {
    if (!is_supported_audio_payload_type(payload_type)) {
        return packetized_error(AudioTransportError::UnsupportedAudioMessageType);
    }
    if (config_generation == 0) {
        return packetized_error(AudioTransportError::InvalidConfigGeneration);
    }
    if (timestamp_quality == AudioTimestampQuality::Reserved) {
        return packetized_error(AudioTransportError::InvalidTimestampQuality);
    }
    if (encoded_packet.empty()) {
        return packetized_error(AudioTransportError::EncodedPacketEmpty);
    }

    const AudioFramePrefixWireBytes prefix = make_audio_frame_prefix(
        config_generation, first_frame_position, timestamp_quality, discontinuity_before);
    const std::array<AudioPayloadSegment, 2> segments{
        AudioPayloadSegment{std::span<const std::byte>(prefix)},
        AudioPayloadSegment{encoded_packet},
    };

    return emit_segments(
        config,
        PacketHeader{
            .protocol_version = kSclProtocolVersion,
            .flags = 0,
            .sequence_number = base_sequence_number,
            .timestamp_us = capture_time_us,
            .payload_type = payload_type,
            .slice_index = 0,
            .total_slices = 1,
        },
        std::span<const AudioPayloadSegment>(segments), sink);
}

PacketizedAudioResult AudioPacketizer::emit_segments(
    AudioPacketizerConfig config,
    const PacketHeader& logical_header,
    std::span<const AudioPayloadSegment> segments,
    AudioDatagramSink& sink) noexcept {
    const AudioTransportError config_error =
        validate_packetizer_config(config, datagram_scratch_.size());
    if (config_error != AudioTransportError::None) {
        return packetized_error(config_error);
    }

    const AudioTransportSizeResult payload_size = logical_payload_size(segments);
    if (!payload_size.ok()) {
        return packetized_error(payload_size.error);
    }

    const std::size_t capacity = config.max_datagram_size - kPacketHeaderWireSize;
    const std::size_t slices =
        payload_size.size == 0 ? 1U : (payload_size.size + capacity - 1U) / capacity;
    if (slices == 0 || slices > std::numeric_limits<std::uint16_t>::max()) {
        return packetized_error(AudioTransportError::TooManyFragments);
    }

    std::uint16_t emitted = 0;
    std::size_t bytes_emitted = 0;
    for (std::size_t slice = 0; slice < slices; ++slice) {
        const std::size_t logical_offset = slice * capacity;
        const std::size_t remaining = payload_size.size - logical_offset;
        const std::size_t fragment_size = std::min(remaining, capacity);

        PacketHeader fragment_header = logical_header;
        fragment_header.sequence_number =
            fragment_sequence_number(logical_header.sequence_number,
                                     static_cast<std::uint16_t>(slice));
        fragment_header.slice_index = static_cast<std::uint16_t>(slice);
        fragment_header.total_slices = static_cast<std::uint16_t>(slices);

        const PacketStatus header_status =
            encode_packet_header(fragment_header, datagram_scratch_.first(kPacketHeaderWireSize));
        const AudioTransportError packet_error = map_packet_error(header_status.error);
        if (packet_error != AudioTransportError::None) {
            return packetized_error(packet_error, emitted, bytes_emitted);
        }

        auto fragment_payload = datagram_scratch_.subspan(kPacketHeaderWireSize, fragment_size);
        copy_segmented_range(segments, logical_offset, fragment_payload);

        const auto datagram =
            std::span<const std::byte>(datagram_scratch_.data(),
                                       kPacketHeaderWireSize + fragment_size);
        const AudioTransportStatus sent = sink.send_audio_datagram(datagram);
        if (!sent.ok()) {
            return packetized_error(emitted == 0 ? sent.error
                                                 : AudioTransportError::PartialEmission,
                                    emitted, bytes_emitted);
        }
        ++emitted;
        bytes_emitted += datagram.size();
    }

    return PacketizedAudioResult{.datagrams_emitted = emitted,
                                 .bytes_emitted = bytes_emitted};
}

} // namespace warpnect::scl
