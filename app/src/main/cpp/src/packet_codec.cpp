#include "packet_codec.h"

#include <cstring>
#include <limits>

#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr PacketStatus status(PacketError error) noexcept {
    return PacketStatus{.error = error};
}

[[nodiscard]] constexpr PacketError decode_payload_type(std::uint8_t wire_value,
                                                        PayloadType& payload_type) noexcept {
    switch (wire_value) {
    case static_cast<std::uint8_t>(PayloadType::Unknown):
        return PacketError::ReservedPayloadType;
    case static_cast<std::uint8_t>(PayloadType::Video):
        payload_type = PayloadType::Video;
        return PacketError::None;
    case static_cast<std::uint8_t>(PayloadType::SystemAudio):
        payload_type = PayloadType::SystemAudio;
        return PacketError::None;
    case static_cast<std::uint8_t>(PayloadType::MicrophoneAudio):
        payload_type = PayloadType::MicrophoneAudio;
        return PacketError::None;
    case static_cast<std::uint8_t>(PayloadType::Input):
        payload_type = PayloadType::Input;
        return PacketError::None;
    case static_cast<std::uint8_t>(PayloadType::Telemetry):
        payload_type = PayloadType::Telemetry;
        return PacketError::None;
    case static_cast<std::uint8_t>(PayloadType::SessionControl):
        payload_type = PayloadType::SessionControl;
        return PacketError::None;
    case static_cast<std::uint8_t>(PayloadType::Handshake):
        payload_type = PayloadType::Handshake;
        return PacketError::None;
    default:
        return PacketError::UnsupportedPayloadType;
    }
}

} // namespace

PacketStatus validate_packet_header(const PacketHeader& header) noexcept {
    if (header.protocol_version != kSclProtocolVersion) {
        return status(PacketError::UnsupportedProtocolVersion);
    }

    if (!payload_type_is_defined(header.payload_type)) {
        return status(PacketError::UnsupportedPayloadType);
    }

    if (header.payload_type == PayloadType::Unknown) {
        return status(PacketError::ReservedPayloadType);
    }

    if (header.total_slices == 0) {
        return status(PacketError::InvalidSliceCount);
    }

    if (header.slice_index >= header.total_slices) {
        return status(PacketError::InvalidSliceIndex);
    }

    return status(PacketError::None);
}

PacketStatus encode_packet_header(const PacketHeader& header,
                                  std::span<std::byte> output) noexcept {
    if (output.size() < kPacketHeaderWireSize) {
        return status(PacketError::OutputBufferTooSmall);
    }

    const PacketStatus validation = validate_packet_header(header);
    if (!validation.ok()) {
        return validation;
    }

    const bool wrote_header =
        internal::write_u16_be(header.protocol_version, output,
                               kPacketHeaderProtocolVersionOffset) &&
        internal::write_u16_be(header.flags, output, kPacketHeaderFlagsOffset) &&
        internal::write_u32_be(header.sequence_number, output, kPacketHeaderSequenceNumberOffset) &&
        internal::write_u64_be(header.timestamp_us, output, kPacketHeaderTimestampUsOffset) &&
        internal::has_bytes(output.size(), kPacketHeaderPayloadTypeOffset,
                            kPacketHeaderPayloadTypeSize) &&
        internal::write_u16_be(header.slice_index, output, kPacketHeaderSliceIndexOffset) &&
        internal::write_u16_be(header.total_slices, output, kPacketHeaderTotalSlicesOffset);

    if (!wrote_header) {
        return status(PacketError::OutputBufferTooSmall);
    }

    output[kPacketHeaderPayloadTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.payload_type));
    return status(PacketError::None);
}

PacketEncodeResult encoded_packet_size(std::size_t payload_size) noexcept {
    if (payload_size > (std::numeric_limits<std::size_t>::max() - kPacketHeaderWireSize)) {
        return PacketEncodeResult{.error = PacketError::SizeOverflow};
    }

    return PacketEncodeResult{.bytes_written = kPacketHeaderWireSize + payload_size};
}

PacketEncodeResult encode_packet(const PacketHeader& header, std::span<const std::byte> payload,
                                 std::span<std::byte> output) noexcept {
    const PacketStatus validation = validate_packet_header(header);
    if (!validation.ok()) {
        return PacketEncodeResult{.error = validation.error};
    }

    const PacketEncodeResult size_result = encoded_packet_size(payload.size());
    if (!size_result.ok()) {
        return size_result;
    }

    const std::size_t required_size = size_result.bytes_written;
    if (output.size() < required_size) {
        return PacketEncodeResult{.error = PacketError::OutputBufferTooSmall};
    }

    const PacketStatus header_status =
        encode_packet_header(header, output.first(kPacketHeaderWireSize));
    if (!header_status.ok()) {
        return PacketEncodeResult{.error = header_status.error};
    }

    if (!payload.empty()) {
        std::memmove(output.data() + kPacketHeaderWireSize, payload.data(), payload.size());
    }

    return PacketEncodeResult{.bytes_written = required_size};
}

PacketDecodeResult decode_packet_header(std::span<const std::byte> input) noexcept {
    if (input.size() < kPacketHeaderWireSize) {
        return PacketDecodeResult{.error = PacketError::BufferTooSmall};
    }

    PacketHeader header{};

    const bool read_header =
        internal::read_u16_be(input, kPacketHeaderProtocolVersionOffset, header.protocol_version) &&
        internal::read_u16_be(input, kPacketHeaderFlagsOffset, header.flags) &&
        internal::read_u32_be(input, kPacketHeaderSequenceNumberOffset, header.sequence_number) &&
        internal::read_u64_be(input, kPacketHeaderTimestampUsOffset, header.timestamp_us) &&
        internal::read_u16_be(input, kPacketHeaderSliceIndexOffset, header.slice_index) &&
        internal::read_u16_be(input, kPacketHeaderTotalSlicesOffset, header.total_slices);

    if (!read_header) {
        return PacketDecodeResult{.error = PacketError::BufferTooSmall};
    }

    const PacketError payload_decode = decode_payload_type(
        static_cast<std::uint8_t>(input[kPacketHeaderPayloadTypeOffset]), header.payload_type);
    if (payload_decode != PacketError::None) {
        return PacketDecodeResult{.error = payload_decode};
    }

    const PacketStatus validation = validate_packet_header(header);
    if (!validation.ok()) {
        return PacketDecodeResult{.error = validation.error};
    }

    return PacketDecodeResult{.header = header};
}

PacketViewResult decode_packet(std::span<const std::byte> packet) noexcept {
    PacketDecodeResult decoded_header = decode_packet_header(packet);
    if (!decoded_header.ok()) {
        return PacketViewResult{.error = decoded_header.error};
    }

    return PacketViewResult{
        .packet =
            PacketView{
                .header = decoded_header.header,
                .payload = packet.subspan(kPacketHeaderWireSize),
            },
    };
}

} // namespace warpnect::scl
