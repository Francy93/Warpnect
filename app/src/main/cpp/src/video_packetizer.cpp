#include "video_packetizer.h"

#include <algorithm>
#include <limits>

#include "datagram_limits.h"
#include "fragmentation.h"
#include "packet_codec.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr PacketizedVideoResult packetized_error(VideoError error,
                                                               std::uint16_t emitted = 0,
                                                               std::size_t bytes = 0) noexcept {
    return PacketizedVideoResult{.error = error, .datagrams_emitted = emitted, .bytes_emitted = bytes};
}

[[nodiscard]] constexpr bool checked_add(std::size_t lhs, std::size_t rhs,
                                         std::size_t& result) noexcept {
    if (lhs > std::numeric_limits<std::size_t>::max() - rhs) {
        return false;
    }
    result = lhs + rhs;
    return true;
}

[[nodiscard]] VideoError validate_packetizer_config(VideoPacketizerConfig config,
                                                    std::size_t scratch_size) noexcept {
    if (config.max_datagram_size < kPacketHeaderWireSize + 1U ||
        config.max_datagram_size > kUdpMaxDatagramPayloadSize ||
        scratch_size < config.max_datagram_size) {
        return VideoError::InvalidDatagramBudget;
    }
    return VideoError::None;
}

[[nodiscard]] VideoSizeResult logical_payload_size(std::span<const PayloadSegment> segments) noexcept {
    std::size_t total = 0;
    for (const PayloadSegment& segment : segments) {
        if (!checked_add(total, segment.bytes.size(), total)) {
            return VideoSizeResult{.error = VideoError::PayloadTooLarge};
        }
    }
    return VideoSizeResult{.size = total};
}

void copy_segmented_range(std::span<const PayloadSegment> segments, std::size_t logical_offset,
                          std::span<std::byte> output) noexcept {
    std::size_t segment_base = 0;
    std::size_t output_offset = 0;

    for (const PayloadSegment& segment : segments) {
        const std::size_t segment_end = segment_base + segment.bytes.size();
        if (logical_offset < segment_end && output_offset < output.size()) {
            const std::size_t inside = logical_offset > segment_base ? logical_offset - segment_base : 0;
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

} // namespace

VideoPacketizer::VideoPacketizer(std::span<std::byte> datagram_scratch) noexcept
    : datagram_scratch_(datagram_scratch) {}

PacketizedVideoResult
VideoPacketizer::emit_access_unit(VideoPacketizerConfig config, std::uint32_t base_sequence_number,
                                  std::uint64_t presentation_time_us,
                                  std::uint32_t config_generation, std::uint32_t frame_id,
                                  bool keyframe, std::span<const std::byte> access_unit,
                                  DatagramSink& sink) noexcept {
    if (config_generation == 0) {
        return packetized_error(VideoError::InvalidConfigGeneration);
    }
    if (access_unit.empty()) {
        return packetized_error(VideoError::AccessUnitEmpty);
    }

    VideoMessageHeaderWireBytes header{};
    const VideoStatus header_status = encode_video_message_header(
        VideoMessageHeader{
            .video_version = kVideoPayloadVersion,
            .message_type = VideoMessageType::AccessUnit,
            .codec = VideoCodec::Avc,
            .flags = keyframe ? kVideoAccessUnitKeyFrameFlag : std::uint8_t{0},
            .config_generation = config_generation,
            .item_id = frame_id,
        },
        std::span<std::byte>(header));
    if (!header_status.ok()) {
        return packetized_error(header_status.error);
    }

    std::array<PayloadSegment, 2> segments{
        PayloadSegment{std::span<const std::byte>(header)},
        PayloadSegment{access_unit},
    };

    return emit_segments(
        config,
        PacketHeader{
            .protocol_version = kSclProtocolVersion,
            .flags = 0,
            .sequence_number = base_sequence_number,
            .timestamp_us = presentation_time_us,
            .payload_type = PayloadType::Video,
            .slice_index = 0,
            .total_slices = 1,
        },
        std::span<const PayloadSegment>(segments), sink);
}

PacketizedVideoResult
VideoPacketizer::emit_stream_config(VideoPacketizerConfig config, std::uint32_t base_sequence_number,
                                    std::uint32_t config_generation, std::uint16_t width,
                                    std::uint16_t height,
                                    std::span<const CsdEntryView> csd_entries,
                                    DatagramSink& sink) noexcept {
    if (config_generation == 0) {
        return packetized_error(VideoError::InvalidConfigGeneration);
    }
    const VideoSizeResult size = video_stream_config_payload_size(width, height, csd_entries);
    if (!size.ok()) {
        return packetized_error(size.error);
    }

    const auto prefix = make_video_stream_config_prefix(
        config_generation, width, height, static_cast<std::uint8_t>(csd_entries.size()));
    std::array<std::array<std::byte, kVideoCsdLengthWireSize>, kMaxVideoCsdEntriesV1> lengths{};
    std::array<PayloadSegment, 1U + (kMaxVideoCsdEntriesV1 * 2U)> segments{};
    std::size_t segment_count = 0;
    segments[segment_count++] = PayloadSegment{std::span<const std::byte>(prefix)};

    for (std::size_t i = 0; i < csd_entries.size(); ++i) {
        const auto length = static_cast<std::uint32_t>(csd_entries[i].bytes.size());
        lengths[i][0] = static_cast<std::byte>((length >> 24U) & 0xFFU);
        lengths[i][1] = static_cast<std::byte>((length >> 16U) & 0xFFU);
        lengths[i][2] = static_cast<std::byte>((length >> 8U) & 0xFFU);
        lengths[i][3] = static_cast<std::byte>(length & 0xFFU);
        segments[segment_count++] = PayloadSegment{std::span<const std::byte>(lengths[i])};
        segments[segment_count++] = PayloadSegment{csd_entries[i].bytes};
    }

    return emit_segments(
        config,
        PacketHeader{
            .protocol_version = kSclProtocolVersion,
            .flags = 0,
            .sequence_number = base_sequence_number,
            .timestamp_us = 0,
            .payload_type = PayloadType::Video,
            .slice_index = 0,
            .total_slices = 1,
        },
        std::span<const PayloadSegment>(segments.data(), segment_count), sink);
}

PacketizedVideoResult VideoPacketizer::emit_segments(VideoPacketizerConfig config,
                                                     const PacketHeader& logical_header,
                                                     std::span<const PayloadSegment> segments,
                                                     DatagramSink& sink) noexcept {
    const VideoError config_error =
        validate_packetizer_config(config, datagram_scratch_.size());
    if (config_error != VideoError::None) {
        return packetized_error(config_error);
    }

    const VideoSizeResult payload_size = logical_payload_size(segments);
    if (!payload_size.ok()) {
        return packetized_error(payload_size.error);
    }

    const std::size_t capacity = config.max_datagram_size - kPacketHeaderWireSize;
    const std::size_t slices =
        payload_size.size == 0 ? 1U : (payload_size.size + capacity - 1U) / capacity;
    if (slices == 0 || slices > std::numeric_limits<std::uint16_t>::max()) {
        return packetized_error(VideoError::TooManyFragments);
    }

    std::uint16_t emitted = 0;
    std::size_t bytes_emitted = 0;
    for (std::size_t slice = 0; slice < slices; ++slice) {
        const std::size_t logical_offset = slice * capacity;
        const std::size_t remaining = payload_size.size - logical_offset;
        const std::size_t fragment_size = std::min(remaining, capacity);

        PacketHeader fragment_header = logical_header;
        fragment_header.sequence_number =
            fragment_sequence_number(logical_header.sequence_number, static_cast<std::uint16_t>(slice));
        fragment_header.slice_index = static_cast<std::uint16_t>(slice);
        fragment_header.total_slices = static_cast<std::uint16_t>(slices);

        const PacketStatus header_status =
            encode_packet_header(fragment_header, datagram_scratch_.first(kPacketHeaderWireSize));
        if (!header_status.ok()) {
            return packetized_error(VideoError::PacketEncodeFailed, emitted, bytes_emitted);
        }

        auto fragment_payload =
            datagram_scratch_.subspan(kPacketHeaderWireSize, fragment_size);
        copy_segmented_range(segments, logical_offset, fragment_payload);

        const auto datagram =
            std::span<const std::byte>(datagram_scratch_.data(), kPacketHeaderWireSize + fragment_size);
        const VideoStatus sent = sink.send(datagram);
        if (!sent.ok()) {
            return packetized_error(emitted == 0 ? sent.error : VideoError::PartialEmission,
                                    emitted, bytes_emitted);
        }
        ++emitted;
        bytes_emitted += datagram.size();
    }

    return PacketizedVideoResult{
        .datagrams_emitted = emitted,
        .bytes_emitted = bytes_emitted,
    };
}

} // namespace warpnect::scl
