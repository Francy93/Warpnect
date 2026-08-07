#include "packet_codec.h"
#include "protocol.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>

namespace {

using warpnect::scl::PacketDecodeResult;
using warpnect::scl::PacketEncodeResult;
using warpnect::scl::PacketError;
using warpnect::scl::PacketHeader;
using warpnect::scl::PacketHeaderWireBytes;
using warpnect::scl::PacketStatus;
using warpnect::scl::PayloadType;

int failures = 0;

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

void expect(bool condition, std::string_view message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

template <typename T>
void expect_equal(const T& actual, const T& expected, std::string_view message) {
    if (!(actual == expected)) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

[[nodiscard]] constexpr PacketHeader
valid_header(PayloadType payload_type = PayloadType::Video) noexcept {
    return PacketHeader{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0x0000U,
        .sequence_number = 1U,
        .timestamp_us = 2U,
        .payload_type = payload_type,
        .slice_index = 0U,
        .total_slices = 1U,
    };
}

[[nodiscard]] constexpr PacketHeader golden_header() noexcept {
    return PacketHeader{
        .protocol_version = 1U,
        .flags = 0xA5C3U,
        .sequence_number = 0x01020304U,
        .timestamp_us = 0x0102030405060708ULL,
        .payload_type = PayloadType::Telemetry,
        .slice_index = 0x0002U,
        .total_slices = 0x0003U,
    };
}

[[nodiscard]] constexpr PacketHeaderWireBytes golden_wire() noexcept {
    return PacketHeaderWireBytes{
        byte(0x00), byte(0x01), byte(0xA5), byte(0xC3), byte(0x01), byte(0x02), byte(0x03),
        byte(0x04), byte(0x01), byte(0x02), byte(0x03), byte(0x04), byte(0x05), byte(0x06),
        byte(0x07), byte(0x08), byte(0x05), byte(0x00), byte(0x02), byte(0x00), byte(0x03),
    };
}

void test_constants_and_enums() {
    expect_equal(warpnect::scl::kSclProtocolVersion, static_cast<std::uint16_t>(1),
                 "protocol version is 1");
    expect_equal(warpnect::scl::kPacketHeaderWireSize, static_cast<std::size_t>(21),
                 "wire header size is 21");

    expect_equal(warpnect::scl::kPacketHeaderProtocolVersionOffset, static_cast<std::size_t>(0),
                 "protocol_version offset");
    expect_equal(warpnect::scl::kPacketHeaderFlagsOffset, static_cast<std::size_t>(2),
                 "flags offset");
    expect_equal(warpnect::scl::kPacketHeaderSequenceNumberOffset, static_cast<std::size_t>(4),
                 "sequence_number offset");
    expect_equal(warpnect::scl::kPacketHeaderTimestampUsOffset, static_cast<std::size_t>(8),
                 "timestamp_us offset");
    expect_equal(warpnect::scl::kPacketHeaderPayloadTypeOffset, static_cast<std::size_t>(16),
                 "payload_type offset");
    expect_equal(warpnect::scl::kPacketHeaderSliceIndexOffset, static_cast<std::size_t>(17),
                 "slice_index offset");
    expect_equal(warpnect::scl::kPacketHeaderTotalSlicesOffset, static_cast<std::size_t>(19),
                 "total_slices offset");

    expect_equal(static_cast<std::uint8_t>(PayloadType::Unknown), static_cast<std::uint8_t>(0),
                 "Unknown wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::Video), static_cast<std::uint8_t>(1),
                 "Video wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::SystemAudio), static_cast<std::uint8_t>(2),
                 "SystemAudio wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::MicrophoneAudio),
                 static_cast<std::uint8_t>(3), "MicrophoneAudio wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::Input), static_cast<std::uint8_t>(4),
                 "Input wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::Telemetry), static_cast<std::uint8_t>(5),
                 "Telemetry wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::SessionControl),
                 static_cast<std::uint8_t>(6), "SessionControl wire value");
    expect_equal(static_cast<std::uint8_t>(PayloadType::Handshake), static_cast<std::uint8_t>(7),
                 "Handshake wire value");

    expect_equal(sizeof(std::uint16_t), static_cast<std::size_t>(2), "uint16_t width");
    expect_equal(sizeof(std::uint32_t), static_cast<std::size_t>(4), "uint32_t width");
    expect_equal(sizeof(std::uint64_t), static_cast<std::size_t>(8), "uint64_t width");
}

void test_golden_wire_encode() {
    PacketHeaderWireBytes output{};

    const PacketStatus status = warpnect::scl::encode_packet_header(golden_header(), output);

    expect(status.ok(), "golden header encodes");
    expect(output == golden_wire(), "golden header bytes match explicit vector");
}

void test_golden_wire_decode() {
    const PacketDecodeResult decoded = warpnect::scl::decode_packet_header(golden_wire());

    expect(decoded.ok(), "golden header decodes");
    expect(decoded.header == golden_header(), "golden decoded runtime header matches");
}

void test_round_trips() {
    constexpr std::array<PacketHeader, 9> headers{
        PacketHeader{.protocol_version = 1,
                     .flags = 0,
                     .sequence_number = 0,
                     .timestamp_us = 0,
                     .payload_type = PayloadType::Video,
                     .slice_index = 0,
                     .total_slices = 1},
        PacketHeader{.protocol_version = 1,
                     .flags = 0xFFFFU,
                     .sequence_number = std::numeric_limits<std::uint32_t>::max(),
                     .timestamp_us = std::numeric_limits<std::uint64_t>::max(),
                     .payload_type = PayloadType::SystemAudio,
                     .slice_index = 0,
                     .total_slices = 1},
        PacketHeader{.protocol_version = 1,
                     .flags = 0x1234U,
                     .sequence_number = 42,
                     .timestamp_us = 123456789,
                     .payload_type = PayloadType::MicrophoneAudio,
                     .slice_index = 1,
                     .total_slices = 4},
        PacketHeader{.protocol_version = 1,
                     .flags = 0x8000U,
                     .sequence_number = 987654,
                     .timestamp_us = 5555,
                     .payload_type = PayloadType::Input,
                     .slice_index = 2,
                     .total_slices = 3},
        valid_header(PayloadType::Telemetry),
        valid_header(PayloadType::SessionControl),
        valid_header(PayloadType::Handshake),
        golden_header(),
        PacketHeader{.protocol_version = 1,
                     .flags = 0x00FFU,
                     .sequence_number = 0xFFFFFFFFU,
                     .timestamp_us = 0,
                     .payload_type = PayloadType::Video,
                     .slice_index = 0x00FEU,
                     .total_slices = 0x00FFU},
    };

    for (const PacketHeader& header : headers) {
        PacketHeaderWireBytes output{};
        const PacketStatus encode_status = warpnect::scl::encode_packet_header(header, output);
        expect(encode_status.ok(), "round-trip header encodes");

        const PacketDecodeResult decoded = warpnect::scl::decode_packet_header(output);
        expect(decoded.ok(), "round-trip header decodes");
        expect(decoded.header == header, "round-trip decoded header equals input");
    }
}

void test_truncated_input() {
    const PacketHeaderWireBytes input = golden_wire();

    for (std::size_t size = 0; size < warpnect::scl::kPacketHeaderWireSize; ++size) {
        const PacketDecodeResult decoded =
            warpnect::scl::decode_packet_header(std::span<const std::byte>(input).first(size));
        expect_equal(decoded.error, PacketError::BufferTooSmall, "truncated header is rejected");
    }
}

void test_small_output_buffers() {
    constexpr std::byte guard = byte(0xA7);

    for (std::size_t size = 0; size < warpnect::scl::kPacketHeaderWireSize; ++size) {
        PacketHeaderWireBytes output{};
        output.fill(guard);

        const PacketStatus status = warpnect::scl::encode_packet_header(
            valid_header(), std::span<std::byte>(output).first(size));

        expect_equal(status.error, PacketError::OutputBufferTooSmall,
                     "small header output is rejected");
        for (std::byte value : output) {
            expect_equal(value, guard, "small header output guard preserved");
        }
    }
}

void test_protocol_version_validation() {
    PacketHeader header = valid_header();
    header.protocol_version = 2;
    expect_equal(warpnect::scl::validate_packet_header(header).error,
                 PacketError::UnsupportedProtocolVersion, "invalid runtime version rejected");

    PacketHeaderWireBytes wire = golden_wire();
    wire[1] = byte(0x02);
    expect_equal(warpnect::scl::decode_packet_header(wire).error,
                 PacketError::UnsupportedProtocolVersion, "invalid wire version rejected");
}

void test_payload_validation() {
    constexpr std::array<PayloadType, 7> valid_types{
        PayloadType::Video,     PayloadType::SystemAudio, PayloadType::MicrophoneAudio,
        PayloadType::Input,     PayloadType::Telemetry,   PayloadType::SessionControl,
        PayloadType::Handshake,
    };

    for (PayloadType payload_type : valid_types) {
        expect(warpnect::scl::validate_packet_header(valid_header(payload_type)).ok(),
               "valid payload type accepted");
    }

    expect_equal(warpnect::scl::validate_packet_header(valid_header(PayloadType::Unknown)).error,
                 PacketError::ReservedPayloadType, "reserved Unknown payload rejected");

    PacketHeader invalid_payload = valid_header(static_cast<PayloadType>(0xFE));
    expect_equal(warpnect::scl::validate_packet_header(invalid_payload).error,
                 PacketError::UnsupportedPayloadType, "undefined runtime payload rejected");

    PacketHeaderWireBytes reserved_wire = golden_wire();
    reserved_wire[warpnect::scl::kPacketHeaderPayloadTypeOffset] = byte(0x00);
    expect_equal(warpnect::scl::decode_packet_header(reserved_wire).error,
                 PacketError::ReservedPayloadType, "reserved wire payload rejected");

    PacketHeaderWireBytes unsupported_wire = golden_wire();
    unsupported_wire[warpnect::scl::kPacketHeaderPayloadTypeOffset] = byte(0xFE);
    expect_equal(warpnect::scl::decode_packet_header(unsupported_wire).error,
                 PacketError::UnsupportedPayloadType, "unsupported wire payload rejected");
}

void test_slice_validation() {
    PacketHeader header = valid_header();

    header.total_slices = 0;
    expect_equal(warpnect::scl::validate_packet_header(header).error,
                 PacketError::InvalidSliceCount, "zero total_slices rejected");

    header = valid_header();
    header.slice_index = 1;
    header.total_slices = 1;
    expect_equal(warpnect::scl::validate_packet_header(header).error,
                 PacketError::InvalidSliceIndex, "slice_index equal total_slices rejected");

    header = valid_header();
    header.slice_index = 2;
    header.total_slices = 1;
    expect_equal(warpnect::scl::validate_packet_header(header).error,
                 PacketError::InvalidSliceIndex, "slice_index greater than total_slices rejected");

    expect(warpnect::scl::validate_packet_header(valid_header()).ok(),
           "single-slice metadata accepted");

    header = valid_header();
    header.slice_index = 2;
    header.total_slices = 3;
    expect(warpnect::scl::validate_packet_header(header).ok(), "multi-slice metadata accepted");
}

void test_packet_view() {
    constexpr std::array<std::byte, 3> payload{byte(0x10), byte(0x20), byte(0x30)};
    std::array<std::byte, warpnect::scl::kPacketHeaderWireSize + payload.size()> packet{};

    const PacketEncodeResult encoded =
        warpnect::scl::encode_packet(valid_header(PayloadType::Input), payload, packet);
    expect(encoded.ok(), "packet view source encodes");

    const auto view = warpnect::scl::decode_packet(packet);
    expect(view.ok(), "packet view decodes");
    expect_equal(view.packet.payload.size(), payload.size(), "packet view payload size");
    expect(view.packet.payload.data() == (packet.data() + warpnect::scl::kPacketHeaderWireSize),
           "packet view payload starts after header");
    expect_equal(view.packet.payload[1], byte(0x20), "packet view observes initial payload");

    packet[warpnect::scl::kPacketHeaderWireSize + 1] = byte(0x44);
    expect_equal(view.packet.payload[1], byte(0x44), "packet view is non-owning");

    PacketHeaderWireBytes header_only{};
    expect(warpnect::scl::encode_packet_header(valid_header(), header_only).ok(),
           "header-only packet source encodes");
    const auto empty_payload_view = warpnect::scl::decode_packet(header_only);
    expect(empty_payload_view.ok(), "empty payload packet decodes");
    expect_equal(empty_payload_view.packet.payload.size(), static_cast<std::size_t>(0),
                 "empty payload view size");
}

void test_full_packet_encoding() {
    constexpr std::array<std::byte, 4> payload{byte(0xAA), byte(0xBB), byte(0xCC), byte(0xDD)};
    std::array<std::byte, warpnect::scl::kPacketHeaderWireSize + payload.size()> output{};

    const PacketEncodeResult encoded =
        warpnect::scl::encode_packet(golden_header(), payload, output);
    expect(encoded.ok(), "full packet encodes");
    expect_equal(encoded.bytes_written, output.size(), "full packet byte count");

    for (std::size_t i = 0; i < warpnect::scl::kPacketHeaderWireSize; ++i) {
        expect_equal(output[i], golden_wire()[i], "full packet header byte");
    }

    for (std::size_t i = 0; i < payload.size(); ++i) {
        expect_equal(output[warpnect::scl::kPacketHeaderWireSize + i], payload[i],
                     "full packet payload byte");
    }

    PacketHeaderWireBytes header_only{};
    expect(warpnect::scl::encode_packet(golden_header(), std::span<const std::byte>{}, header_only)
               .ok(),
           "empty payload full packet encodes");

    std::array<std::byte, warpnect::scl::kPacketHeaderWireSize + payload.size() - 1> small{};
    small.fill(byte(0x5A));
    const PacketEncodeResult small_result =
        warpnect::scl::encode_packet(golden_header(), payload, small);
    expect_equal(small_result.error, PacketError::OutputBufferTooSmall,
                 "insufficient full packet output rejected");
    for (std::byte value : small) {
        expect_equal(value, byte(0x5A), "insufficient full packet output guard preserved");
    }

    const PacketEncodeResult overflow =
        warpnect::scl::encoded_packet_size(std::numeric_limits<std::size_t>::max());
    expect_equal(overflow.error, PacketError::SizeOverflow, "packet size overflow rejected");
}

void test_deterministic_property_round_trips() {
    constexpr std::array<PayloadType, 7> valid_types{
        PayloadType::Video,     PayloadType::SystemAudio, PayloadType::MicrophoneAudio,
        PayloadType::Input,     PayloadType::Telemetry,   PayloadType::SessionControl,
        PayloadType::Handshake,
    };

    for (std::uint32_t i = 0; i < 2048U; ++i) {
        PacketHeader header{
            .protocol_version = 1,
            .flags = static_cast<std::uint16_t>((i * 37U) & 0xFFFFU),
            .sequence_number = static_cast<std::uint32_t>(0x9E3779B9U * (i + 1U)),
            .timestamp_us = (static_cast<std::uint64_t>(i) << 32U) | (0xA5A5A5A5ULL ^ i),
            .payload_type = valid_types[i % valid_types.size()],
            .slice_index = static_cast<std::uint16_t>(i % 7U),
            .total_slices = static_cast<std::uint16_t>((i % 7U) + 1U),
        };
        if (header.slice_index >= header.total_slices) {
            header.slice_index = static_cast<std::uint16_t>(header.total_slices - 1U);
        }

        PacketHeaderWireBytes wire{};
        const PacketStatus encoded = warpnect::scl::encode_packet_header(header, wire);
        expect(encoded.ok(), "property header encodes");

        const PacketDecodeResult decoded = warpnect::scl::decode_packet_header(wire);
        expect(decoded.ok(), "property header decodes");
        expect(decoded.header == header, "property round-trip preserves header");
    }
}

void test_unaligned_buffers() {
    constexpr std::array<std::byte, 2> payload{byte(0xBA), byte(0xBE)};
    std::array<std::byte, warpnect::scl::kPacketHeaderWireSize + payload.size() + 2> storage{};

    std::span<std::byte> unaligned_output(storage.data() + 1,
                                          warpnect::scl::kPacketHeaderWireSize + payload.size());
    const PacketEncodeResult encoded =
        warpnect::scl::encode_packet(golden_header(), payload, unaligned_output);
    expect(encoded.ok(), "unaligned packet encodes");

    std::span<const std::byte> unaligned_input(storage.data() + 1, encoded.bytes_written);
    const auto decoded = warpnect::scl::decode_packet(unaligned_input);
    expect(decoded.ok(), "unaligned packet decodes");
    expect(decoded.packet.header == golden_header(), "unaligned decoded header matches");
    expect_equal(decoded.packet.payload.size(), payload.size(), "unaligned decoded payload size");
    expect_equal(decoded.packet.payload[0], payload[0], "unaligned payload byte 0");
    expect_equal(decoded.packet.payload[1], payload[1], "unaligned payload byte 1");
}

} // namespace

int main() {
    test_constants_and_enums();
    test_golden_wire_encode();
    test_golden_wire_decode();
    test_round_trips();
    test_truncated_input();
    test_small_output_buffers();
    test_protocol_version_validation();
    test_payload_validation();
    test_slice_validation();
    test_packet_view();
    test_full_packet_encoding();
    test_deterministic_property_round_trips();
    test_unaligned_buffers();

    if (failures != 0) {
        std::cerr << failures << " SCL packet test failure(s)\n";
        return 1;
    }

    std::cout << "SCL packet tests passed\n";
    return 0;
}
