#include "fec.h"
#include "fec_control.h"
#include "fragmentation.h"
#include "packet_codec.h"
#include "reassembly.h"
#include "recovery_control.h"
#include "reed_solomon.h"
#include "retransmission_cache.h"
#include "session_protection.h"
#include "udp_socket.h"
#include "video_packetizer.h"
#include "video_protocol.h"
#include "video_transport.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::CsdEntryCursor;
using warpnect::scl::CsdEntryView;
using warpnect::scl::DatagramSink;
using warpnect::scl::DatagramProtectionError;
using warpnect::scl::DatagramProtectionResult;
using warpnect::scl::DatagramProtector;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::FecBlockEncoder;
using warpnect::scl::FecEncoderWorkspace;
using warpnect::scl::FecError;
using warpnect::scl::FecParityView;
using warpnect::scl::FecRecoveryBlock;
using warpnect::scl::FecRecoveryWorkspace;
using warpnect::scl::FragmentationConfig;
using warpnect::scl::IpAddress;
using warpnect::scl::IpVersion;
using warpnect::scl::NackRequest;
using warpnect::scl::NackSequenceCursor;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::ReassemblySlot;
using warpnect::scl::ReassemblyWorkspace;
using warpnect::scl::RecoveryError;
using warpnect::scl::ReedSolomonConfig;
using warpnect::scl::ReedSolomonWorkspace;
using warpnect::scl::RetransmissionCache;
using warpnect::scl::RetransmissionCacheConfig;
using warpnect::scl::RetransmissionCacheWorkspace;
using warpnect::scl::RetransmissionEntry;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::UdpError;
using warpnect::scl::UdpReceiveResult;
using warpnect::scl::UdpSocket;
using warpnect::scl::VideoAccessUnitDecodeResult;
using warpnect::scl::VideoCodec;
using warpnect::scl::VideoError;
using warpnect::scl::VideoMessageHeader;
using warpnect::scl::VideoMessageHeaderWireBytes;
using warpnect::scl::VideoMessageType;
using warpnect::scl::VideoPacketizer;
using warpnect::scl::VideoPacketizerConfig;
using warpnect::scl::VideoSizeResult;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoStreamConfigDecodeResult;
using warpnect::scl::VideoTransportFecConfig;
using warpnect::scl::VideoTransportSender;
using warpnect::scl::VideoTransportSenderConfig;
using warpnect::scl::VideoTransportSenderWorkspace;
using warpnect::scl::security::ProtectionScope;
using warpnect::scl::security::SessionProtectionError;
using warpnect::scl::security::SessionProtectionLocalRole;
using warpnect::scl::security::SessionProtectionRuntime;

int failures = 0;
int skips = 0;

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

[[nodiscard]] std::array<std::byte, 32> protection_root() {
    std::array<std::byte, 32> value{};
    for (std::size_t index = 0; index < value.size(); ++index) {
        value[index] = byte(static_cast<std::uint8_t>(index + 1U));
    }
    return value;
}

[[nodiscard]] std::array<std::byte, 16> protection_session_id() {
    std::array<std::byte, 16> value{};
    for (std::size_t index = 0; index < value.size(); ++index) {
        value[index] = byte(static_cast<std::uint8_t>(0x30U + index));
    }
    return value;
}

[[nodiscard]] std::array<std::byte, 32> protection_transcript() {
    std::array<std::byte, 32> value{};
    for (std::size_t index = 0; index < value.size(); ++index) {
        value[index] = byte(static_cast<std::uint8_t>(0x70U + index));
    }
    return value;
}

class RuntimeDatagramProtector final : public DatagramProtector {
  public:
    RuntimeDatagramProtector(SessionProtectionRuntime& runtime, ProtectionScope scope) noexcept
        : runtime_(runtime), scope_(scope) {}

    [[nodiscard]] std::size_t secure_datagram_budget() const noexcept override {
        return runtime_.secure_datagram_budget();
    }

    [[nodiscard]] std::size_t inner_datagram_budget() const noexcept override {
        return runtime_.inner_datagram_budget();
    }

    [[nodiscard]] DatagramProtectionResult protect(
        std::span<const std::byte> inner,
        std::span<std::byte> output) noexcept override {
        const auto result = runtime_.protect(scope_, inner, output);
        return {.error = map(result.error), .bytes_written = result.bytes_written};
    }

    [[nodiscard]] DatagramProtectionResult unprotect(
        const UdpEndpoint& source,
        std::span<const std::byte> secure,
        std::span<std::byte> output,
        std::uint64_t now_us) noexcept override {
        const auto result = runtime_.unprotect(source, secure, output, now_us);
        return {.error = map(result.error), .bytes_written = result.bytes_written};
    }

  private:
    [[nodiscard]] static DatagramProtectionError map(SessionProtectionError error) noexcept {
        if (error == SessionProtectionError::None) return DatagramProtectionError::None;
        if (error == SessionProtectionError::DatagramTooLarge) {
            return DatagramProtectionError::DatagramTooLarge;
        }
        return DatagramProtectionError::Rejected;
    }

    SessionProtectionRuntime& runtime_;
    ProtectionScope scope_{};
};

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

void skip(std::string_view message) {
    std::cout << "SKIP: " << message << '\n';
    ++skips;
}

[[nodiscard]] bool bytes_equal(std::span<const std::byte> lhs,
                               std::span<const std::byte> rhs) noexcept {
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin());
}

[[nodiscard]] std::vector<std::byte> make_bytes(std::size_t size,
                                                std::uint8_t seed = 17) {
    std::vector<std::byte> bytes(size);
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 37U + seed) & 0xFFU));
    }
    return bytes;
}

[[nodiscard]] std::vector<std::byte> to_vector(std::span<const std::byte> bytes) {
    return std::vector<std::byte>(bytes.begin(), bytes.end());
}

[[nodiscard]] std::span<const std::byte> as_bytes(const std::vector<std::byte>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

template <std::size_t Size>
[[nodiscard]] std::span<const std::byte> as_bytes(const std::array<std::byte, Size>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

struct CapturedDatagram final {
    std::vector<std::byte> bytes{};
    PacketHeader header{};
};

class CollectingSink final : public DatagramSink {
  public:
    VideoError failure = VideoError::None;
    std::size_t fail_after = std::numeric_limits<std::size_t>::max();
    std::vector<CapturedDatagram> datagrams{};

    [[nodiscard]] VideoStatus send(std::span<const std::byte> datagram) noexcept override {
        if (datagrams.size() >= fail_after) {
            return VideoStatus{.error = failure};
        }

        const auto decoded = warpnect::scl::decode_packet(datagram);
        if (!decoded.ok()) {
            return VideoStatus{.error = VideoError::PacketEncodeFailed};
        }

        datagrams.push_back(
            CapturedDatagram{.bytes = to_vector(datagram), .header = decoded.packet.header});
        return VideoStatus{};
    }
};

struct ReassembledPayload final {
    std::vector<std::byte> payload{};
    std::uint64_t timestamp_us = 0;
};

[[nodiscard]] bool reassemble_datagrams(const std::vector<CapturedDatagram>& datagrams,
                                        std::size_t datagram_budget,
                                        ReassembledPayload& output) {
    if (datagrams.empty()) {
        expect(false, "reassembly requires at least one datagram");
        return false;
    }

    const auto first = warpnect::scl::decode_packet(as_bytes(datagrams.front().bytes));
    expect(first.ok(), "first datagram decodes for reassembly sizing");
    if (!first.ok()) {
        return false;
    }

    const auto max_payload =
        warpnect::scl::max_reassembled_payload_size(
            FragmentationConfig{.max_datagram_size = datagram_budget},
            first.packet.header.total_slices);
    const auto metadata =
        warpnect::scl::required_reassembly_metadata_size(first.packet.header.total_slices);
    expect(max_payload.ok(), "max reassembly payload size is available");
    expect(metadata.ok(), "reassembly metadata size is available");
    if (!max_payload.ok() || !metadata.ok()) {
        return false;
    }

    std::vector<std::byte> payload_storage(max_payload.size);
    std::vector<std::byte> bitmap(metadata.size);
    ReassemblySlot slot(
        FragmentationConfig{.max_datagram_size = datagram_budget},
        ReassemblyWorkspace{.payload_storage = payload_storage, .received_bitmap = bitmap});

    for (const CapturedDatagram& datagram : datagrams) {
        const auto decoded = warpnect::scl::decode_packet(as_bytes(datagram.bytes));
        expect(decoded.ok(), "fragment datagram decodes");
        if (!decoded.ok()) {
            return false;
        }
        const auto accepted = slot.accept(decoded.packet);
        expect(accepted.ok(), "fragment accepts into reassembly slot");
        if (!accepted.ok()) {
            return false;
        }
    }

    expect(slot.is_complete(), "reassembly completes");
    const auto result = slot.result();
    expect(result.ok(), "reassembly result is available");
    if (!result.ok()) {
        return false;
    }

    output.payload = to_vector(result.payload.payload);
    output.timestamp_us = result.payload.group.timestamp_us;
    return true;
}

[[nodiscard]] std::vector<std::byte> expected_access_unit_payload(
    std::uint32_t generation,
    std::uint32_t frame_id,
    bool keyframe,
    std::span<const std::byte> access_unit) {
    VideoMessageHeaderWireBytes header{};
    const auto encoded_header = warpnect::scl::encode_video_message_header(
        VideoMessageHeader{
            .video_version = warpnect::scl::kVideoPayloadVersion,
            .message_type = VideoMessageType::AccessUnit,
            .codec = VideoCodec::Avc,
            .flags = keyframe ? warpnect::scl::kVideoAccessUnitKeyFrameFlag : std::uint8_t{0},
            .config_generation = generation,
            .item_id = frame_id,
        },
        header);
    expect(encoded_header.ok(), "expected AU video header encodes");

    std::vector<std::byte> expected(header.begin(), header.end());
    expected.insert(expected.end(), access_unit.begin(), access_unit.end());
    return expected;
}

[[nodiscard]] FecBlockConfig fec_config(std::uint8_t data_shards,
                                        std::uint8_t parity_shards,
                                        std::uint32_t base_sequence,
                                        std::size_t max_wire_datagram_size) noexcept {
    return FecBlockConfig{
        .rs =
            ReedSolomonConfig{
                .data_shards = data_shards,
                .parity_shards = parity_shards,
            },
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = base_sequence,
        .max_wire_datagram_size = max_wire_datagram_size,
    };
}

struct FecEncoderStorage final {
    std::vector<std::byte> data{};
    std::vector<std::byte> parity{};
    std::vector<std::byte> matrix{};
    std::vector<std::byte> scratch{};

    explicit FecEncoderStorage(const FecBlockConfig& config) {
        data.resize(warpnect::scl::required_fec_encoder_data_storage_size(config).size);
        parity.resize(warpnect::scl::required_fec_encoder_parity_storage_size(config).size);
        matrix.resize(warpnect::scl::required_reed_solomon_matrix_storage_size(config.rs).size);
        scratch.resize(warpnect::scl::required_reed_solomon_scratch_storage_size(config.rs).size);
    }

    [[nodiscard]] FecEncoderWorkspace workspace() noexcept {
        return FecEncoderWorkspace{
            .data_shard_storage = data,
            .parity_shard_storage = parity,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }
};

struct FecRecoveryStorage final {
    std::vector<std::byte> shards{};
    std::vector<std::byte> present{};
    std::vector<std::byte> matrix{};
    std::vector<std::byte> scratch{};

    explicit FecRecoveryStorage(const FecBlockConfig& config) {
        shards.resize(warpnect::scl::required_fec_recovery_shard_storage_size(config).size);
        present.resize(warpnect::scl::required_fec_presence_storage_size(config.rs).size);
        matrix.resize(warpnect::scl::required_reed_solomon_matrix_storage_size(config.rs).size);
        scratch.resize(warpnect::scl::required_reed_solomon_scratch_storage_size(config.rs).size);
    }

    [[nodiscard]] FecRecoveryWorkspace workspace() noexcept {
        return FecRecoveryWorkspace{
            .shard_storage = shards,
            .present_bitmap = present,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }
};

[[nodiscard]] bool build_video_datagrams(std::size_t datagram_budget,
                                         std::uint32_t base_sequence,
                                         std::uint32_t generation,
                                         std::uint32_t frame_id,
                                         std::uint64_t pts_us,
                                         bool keyframe,
                                         std::span<const std::byte> access_unit,
                                         CollectingSink& sink) {
    std::vector<std::byte> scratch(datagram_budget);
    VideoPacketizer packetizer(scratch);
    const auto result = packetizer.emit_access_unit(
        VideoPacketizerConfig{.max_datagram_size = datagram_budget},
        base_sequence,
        pts_us,
        generation,
        frame_id,
        keyframe,
        access_unit,
        sink);
    expect(result.ok(), "video access unit packetizes");
    return result.ok();
}

void test_common_video_header_golden() {
    VideoMessageHeaderWireBytes output{};
    const auto status = warpnect::scl::encode_video_message_header(
        VideoMessageHeader{
            .video_version = 1,
            .message_type = VideoMessageType::AccessUnit,
            .codec = VideoCodec::Avc,
            .flags = warpnect::scl::kVideoAccessUnitKeyFrameFlag,
            .config_generation = 0x01020304U,
            .item_id = 0xA1B2C3D4U,
        },
        output);

    const VideoMessageHeaderWireBytes expected{
        byte(0x01), byte(0x02), byte(0x01), byte(0x01), byte(0x01), byte(0x02),
        byte(0x03), byte(0x04), byte(0xA1), byte(0xB2), byte(0xC3), byte(0xD4),
    };

    expect(status.ok(), "common video header encodes");
    expect(output == expected, "common video header matches explicit golden bytes");
}

void test_stream_config_golden_and_csd_round_trip() {
    const std::array<std::byte, 3> csd0{byte(0x67), byte(0x42), byte(0x00)};
    const std::array<std::byte, 2> csd1{byte(0x68), byte(0xCE)};
    const std::array<CsdEntryView, 2> entries{
        CsdEntryView{.bytes = as_bytes(csd0)},
        CsdEntryView{.bytes = as_bytes(csd1)},
    };
    std::array<std::byte, 64> output{};
    const auto encoded = warpnect::scl::encode_video_stream_config_payload(
        0x01020304U,
        0x0780U,
        0x0438U,
        entries,
        output);

    const std::vector<std::byte> expected{
        byte(0x01), byte(0x01), byte(0x01), byte(0x00), byte(0x01), byte(0x02),
        byte(0x03), byte(0x04), byte(0x00), byte(0x00), byte(0x00), byte(0x00),
        byte(0x07), byte(0x80), byte(0x04), byte(0x38), byte(0x02), byte(0x00),
        byte(0x00), byte(0x00), byte(0x00), byte(0x00), byte(0x00), byte(0x03),
        byte(0x67), byte(0x42), byte(0x00), byte(0x00), byte(0x00), byte(0x00),
        byte(0x02), byte(0x68), byte(0xCE),
    };

    expect(encoded.ok(), "stream config encodes");
    expect_equal(encoded.bytes_written, expected.size(), "stream config encoded size");
    expect(bytes_equal(std::span<const std::byte>(output).first(encoded.bytes_written),
                       as_bytes(expected)),
           "stream config bytes match explicit golden vector");

    const VideoStreamConfigDecodeResult decoded =
        warpnect::scl::decode_video_stream_config(
            std::span<const std::byte>(output).first(encoded.bytes_written));
    expect(decoded.ok(), "stream config decodes");
    expect_equal(decoded.config.config_generation, 0x01020304U, "config generation decodes");
    expect_equal(decoded.config.width, static_cast<std::uint16_t>(0x0780), "width decodes");
    expect_equal(decoded.config.height, static_cast<std::uint16_t>(0x0438), "height decodes");
    expect_equal(decoded.config.csd_count, static_cast<std::uint8_t>(2), "CSD count decodes");

    CsdEntryCursor cursor(decoded.config);
    const auto decoded0 = cursor.next();
    const auto decoded1 = cursor.next();
    expect(decoded0.ok(), "first CSD entry decodes");
    expect(decoded1.ok(), "second CSD entry decodes");
    expect(bytes_equal(decoded0.entry.bytes, as_bytes(csd0)), "first CSD is exact");
    expect(bytes_equal(decoded1.entry.bytes, as_bytes(csd1)), "second CSD is exact");
    expect(!cursor.has_next(), "CSD cursor exhausts exactly");
}

void test_access_unit_golden_and_small_round_trip() {
    const auto au = make_bytes(9, 21);
    CollectingSink sink;
    expect(build_video_datagrams(256, 77, 3, 0xA1B2C3D4U, 1234567, true, as_bytes(au), sink),
           "small AU packetization succeeds");
    expect_equal(sink.datagrams.size(), static_cast<std::size_t>(1), "small AU emits one datagram");

    const auto decoded = warpnect::scl::decode_packet(as_bytes(sink.datagrams[0].bytes));
    expect(decoded.ok(), "small AU SCL datagram decodes");
    expect_equal(decoded.packet.header.timestamp_us, static_cast<std::uint64_t>(1234567),
                 "PacketHeader timestamp preserves encoder PTS");
    expect_equal(decoded.packet.header.slice_index, static_cast<std::uint16_t>(0),
                 "single AU slice index");
    expect_equal(decoded.packet.header.total_slices, static_cast<std::uint16_t>(1),
                 "single AU total slices");

    const auto parsed = warpnect::scl::decode_video_access_unit(
        decoded.packet.payload,
        decoded.packet.header.timestamp_us);
    expect(parsed.ok(), "small AU video payload parses");
    expect_equal(parsed.access_unit.frame_id, 0xA1B2C3D4U, "frame ID decodes");
    expect(parsed.access_unit.is_key_frame, "keyframe flag decodes");
    expect(bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)), "AU bytes are exact");
}

void test_malformed_video_payloads() {
    const auto au = make_bytes(4, 9);
    const auto valid = expected_access_unit_payload(1, 7, true, as_bytes(au));
    auto mutate = [&](std::size_t offset, std::byte value) {
        std::vector<std::byte> copy = valid;
        copy[offset] = value;
        return copy;
    };

    expect_equal(warpnect::scl::decode_video_access_unit({}, 0).error,
                 VideoError::MalformedVideoPayload,
                 "truncated common header rejected");
    expect_equal(warpnect::scl::decode_video_access_unit(as_bytes(mutate(0, byte(2))), 0).error,
                 VideoError::UnsupportedVideoVersion,
                 "unsupported version rejected");
    expect_equal(warpnect::scl::decode_video_access_unit(as_bytes(mutate(1, byte(99))), 0).error,
                 VideoError::UnsupportedVideoMessageType,
                 "unknown message type rejected");
    expect_equal(warpnect::scl::decode_video_access_unit(as_bytes(mutate(2, byte(0))), 0).error,
                 VideoError::UnsupportedVideoCodec,
                 "unknown codec rejected");
    expect_equal(warpnect::scl::decode_video_access_unit(as_bytes(mutate(3, byte(2))), 0).error,
                 VideoError::InvalidVideoFlags,
                 "reserved AU flags rejected");
    auto generation_zero = valid;
    generation_zero[4] = byte(0);
    generation_zero[5] = byte(0);
    generation_zero[6] = byte(0);
    generation_zero[7] = byte(0);
    expect_equal(warpnect::scl::decode_video_access_unit(as_bytes(generation_zero), 0).error,
                 VideoError::InvalidConfigGeneration,
                 "zero generation rejected");
    expect_equal(
        warpnect::scl::decode_video_access_unit(
            as_bytes(std::vector<std::byte>(valid.begin(), valid.begin() +
                                                           warpnect::scl::kVideoMessageHeaderWireSize)),
            0)
            .error,
        VideoError::AccessUnitEmpty,
        "empty access unit rejected");

    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    std::array<std::byte, 32> config{};
    const auto encoded = warpnect::scl::encode_video_stream_config_payload(1, 16, 16, entries,
                                                                           config);
    expect(encoded.ok(), "valid config fixture encodes");
    std::vector<std::byte> valid_config(config.begin(), config.begin() + encoded.bytes_written);

    auto bad_item = valid_config;
    bad_item[11] = byte(1);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(bad_item)).error,
                 VideoError::InvalidFrameId,
                 "stream config item_id must be zero");
    auto zero_width = valid_config;
    zero_width[12] = byte(0);
    zero_width[13] = byte(0);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(zero_width)).error,
                 VideoError::InvalidDimensions,
                 "zero width rejected");
    auto zero_csd = valid_config;
    zero_csd[16] = byte(0);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(zero_csd)).error,
                 VideoError::InvalidCsdCount,
                 "zero CSD count rejected");
    auto too_many_csd = valid_config;
    too_many_csd[16] = byte(5);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(too_many_csd)).error,
                 VideoError::InvalidCsdCount,
                 "too many CSD entries rejected");
    auto nonzero_reserved = valid_config;
    nonzero_reserved[17] = byte(1);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(nonzero_reserved)).error,
                 VideoError::MalformedVideoPayload,
                 "nonzero stream config reserved bytes rejected");
    auto truncated_length = valid_config;
    truncated_length.resize(warpnect::scl::kVideoStreamConfigPrefixWireSize + 2U);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(truncated_length)).error,
                 VideoError::MalformedCsd,
                 "truncated CSD length rejected");
    auto oversized_csd = valid_config;
    oversized_csd[20] = byte(0);
    oversized_csd[21] = byte(0);
    oversized_csd[22] = byte(0);
    oversized_csd[23] = byte(9);
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(oversized_csd)).error,
                 VideoError::MalformedCsd,
                 "oversized CSD length rejected");
    auto trailing = valid_config;
    trailing.push_back(byte(0x55));
    expect_equal(warpnect::scl::decode_video_stream_config(as_bytes(trailing)).error,
                 VideoError::MalformedCsd,
                 "trailing malformed CSD structure rejected");
}

void test_segmented_fragmentation_crosses_header_boundary() {
    const std::size_t budget = warpnect::scl::kPacketHeaderWireSize + 7U;
    const auto au = make_bytes(13, 31);
    CollectingSink sink;
    expect(build_video_datagrams(budget, 100, 9, 3, 555, false, as_bytes(au), sink),
           "segmented AU packetizes across small datagram budget");
    expect(sink.datagrams.size() > 1, "segmented AU emits multiple datagrams");

    const auto first = warpnect::scl::decode_packet(as_bytes(sink.datagrams[0].bytes));
    const auto second = warpnect::scl::decode_packet(as_bytes(sink.datagrams[1].bytes));
    expect(first.ok() && second.ok(), "first two segmented fragments decode");
    expect_equal(first.packet.payload.size(), static_cast<std::size_t>(7),
                 "first fragment contains only header prefix");
    expect_equal(second.packet.payload.size(), static_cast<std::size_t>(7),
                 "second fragment crosses header tail into AU bytes");

    ReassembledPayload reassembled;
    expect(reassemble_datagrams(sink.datagrams, budget, reassembled), "segmented AU reassembles");
    const auto expected = expected_access_unit_payload(9, 3, false, as_bytes(au));
    expect(bytes_equal(as_bytes(reassembled.payload), as_bytes(expected)),
           "segmented payload reassembles as header plus exact AU bytes");
}

void test_large_access_units_round_trip() {
    constexpr std::size_t budget = 1200;
    const std::array<std::size_t, 4> sizes{4U * 1024U, 64U * 1024U, 256U * 1024U,
                                          1024U * 1024U};
    for (const std::size_t size : sizes) {
        const auto au = make_bytes(size, static_cast<std::uint8_t>(size & 0xFFU));
        CollectingSink sink;
        expect(build_video_datagrams(budget, 500, 5, 12, 999, false, as_bytes(au), sink),
               "large AU packetizes");

        ReassembledPayload reassembled;
        expect(reassemble_datagrams(sink.datagrams, budget, reassembled),
               "large AU reassembles");
        const auto parsed =
            warpnect::scl::decode_video_access_unit(as_bytes(reassembled.payload),
                                                    reassembled.timestamp_us);
        expect(parsed.ok(), "large AU parses after reassembly");
        expect(bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)),
               "large AU encoded bytes are exact after round trip");
    }
}

void test_sequence_frame_generation_keyframe_and_pts_semantics() {
    const std::size_t budget = warpnect::scl::kPacketHeaderWireSize + 12U;
    const auto au = make_bytes(28, 43);
    CollectingSink sink;
    expect(build_video_datagrams(budget, std::numeric_limits<std::uint32_t>::max() - 1U, 7,
                                 std::numeric_limits<std::uint32_t>::max(), 1234, true,
                                 as_bytes(au), sink),
           "wrap AU packetizes");
    expect(sink.datagrams.size() >= 3, "wrap AU creates enough fragments");
    expect_equal(sink.datagrams[0].header.sequence_number,
                 std::numeric_limits<std::uint32_t>::max() - 1U,
                 "first sequence before wrap");
    expect_equal(sink.datagrams[1].header.sequence_number,
                 std::numeric_limits<std::uint32_t>::max(),
                 "second sequence at wrap boundary");
    expect_equal(sink.datagrams[2].header.sequence_number, 0U, "third sequence wraps to zero");
    for (const CapturedDatagram& datagram : sink.datagrams) {
        expect_equal(datagram.header.timestamp_us, static_cast<std::uint64_t>(1234),
                     "all AU fragments preserve PTS");
    }

    ReassembledPayload reassembled;
    expect(reassemble_datagrams(sink.datagrams, budget, reassembled), "wrap AU reassembles");
    const VideoAccessUnitDecodeResult parsed =
        warpnect::scl::decode_video_access_unit(as_bytes(reassembled.payload),
                                                reassembled.timestamp_us);
    expect(parsed.ok(), "wrap AU parses");
    expect_equal(parsed.access_unit.frame_id, std::numeric_limits<std::uint32_t>::max(),
                 "frame ID is independent and may wrap");
    expect(parsed.access_unit.is_key_frame, "keyframe bit is preserved");
    expect_equal(warpnect::scl::next_video_config_generation(0), 1U,
                 "first config generation is 1");
    expect_equal(warpnect::scl::next_video_config_generation(1), 2U,
                 "second config generation is 2");
    expect_equal(warpnect::scl::next_video_config_generation(
                     std::numeric_limits<std::uint32_t>::max()),
                 1U,
                 "config generation wraps and skips zero");
}

[[nodiscard]] bool recover_one_with_fec(const std::vector<CapturedDatagram>& data,
                                        const FecBlockConfig& config,
                                        std::uint8_t dropped_index,
                                        std::vector<CapturedDatagram>& recovered_data) {
    FecEncoderStorage encoder_storage(config);
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    for (std::uint8_t i = 0; i < config.rs.data_shards; ++i) {
        const auto accepted = encoder.accept_data_datagram(as_bytes(data[i].bytes));
        expect(accepted.ok(), "FEC accepts video data datagram");
        if (!accepted.ok()) {
            return false;
        }
    }
    expect(encoder.encode().ok(), "FEC encodes video data block");
    const auto parity = encoder.parity_view(0);
    expect(parity.ok(), "FEC parity view is available");
    if (!parity.ok()) {
        return false;
    }

    FecRecoveryStorage recovery_storage(config);
    FecRecoveryBlock recovery(recovery_storage.workspace());
    for (std::uint8_t i = 0; i < config.rs.data_shards; ++i) {
        if (i == dropped_index) {
            continue;
        }
        const auto accepted = recovery.accept_data_datagram(config, as_bytes(data[i].bytes));
        expect(accepted.ok(), "FEC recovery accepts remaining video data");
        if (!accepted.ok()) {
            return false;
        }
    }
    expect(recovery.accept_parity(parity.parity).ok(), "FEC recovery accepts video parity");
    expect(recovery.recover().ok(), "FEC recovers missing video data");
    const auto recovered = recovery.datagram(dropped_index);
    expect(recovered.ok(), "recovered video datagram is available");
    if (!recovered.ok()) {
        return false;
    }

    recovered_data.clear();
    for (std::uint8_t i = 0; i < config.rs.data_shards; ++i) {
        if (i == dropped_index) {
            const auto decoded = warpnect::scl::decode_packet(recovered.datagram.datagram);
            recovered_data.push_back(
                CapturedDatagram{.bytes = to_vector(recovered.datagram.datagram),
                                 .header = decoded.packet.header});
        } else {
            recovered_data.push_back(data[i]);
        }
    }
    return true;
}

void test_video_fec_recovery() {
    constexpr std::size_t max_wire = 96;
    const VideoSizeResult protected_budget =
        warpnect::scl::video_fragment_datagram_budget(max_wire, true);
    expect(protected_budget.ok(), "video protected datagram budget is valid");
    const auto au = make_bytes(100, 55);
    CollectingSink sink;
    expect(build_video_datagrams(protected_budget.size, 1000, 2, 4, 9876, false, as_bytes(au),
                                 sink),
           "video AU packetizes for FEC");
    expect_equal(sink.datagrams.size(), static_cast<std::size_t>(4),
                 "FEC test AU emits exactly one 4-shard block");

    const FecBlockConfig config = fec_config(4, 1, 1000, max_wire);
    std::vector<CapturedDatagram> recovered;
    expect(recover_one_with_fec(sink.datagrams, config, 2, recovered),
           "FEC recovers one lost video shard");
    ReassembledPayload reassembled;
    expect(reassemble_datagrams(recovered, protected_budget.size, reassembled),
           "FEC-recovered AU reassembles");
    const auto parsed =
        warpnect::scl::decode_video_access_unit(as_bytes(reassembled.payload),
                                                reassembled.timestamp_us);
    expect(parsed.ok(), "FEC-recovered AU parses");
    expect(bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)),
           "FEC-recovered AU bytes are exact");
}

void test_stream_config_fec_recovery() {
    constexpr std::size_t max_wire = 96;
    const VideoSizeResult protected_budget =
        warpnect::scl::video_fragment_datagram_budget(max_wire, true);
    const auto csd = make_bytes(70, 61);
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    CollectingSink sink;
    std::vector<std::byte> scratch(protected_budget.size);
    VideoPacketizer packetizer(scratch);
    const auto emitted = packetizer.emit_stream_config(
        VideoPacketizerConfig{.max_datagram_size = protected_budget.size},
        2000,
        3,
        1280,
        720,
        entries,
        sink);
    expect(emitted.ok(), "stream config packetizes for FEC");
    expect_equal(sink.datagrams.size(), static_cast<std::size_t>(3),
                 "stream config emits one 3-shard block");

    const FecBlockConfig config = fec_config(3, 1, 2000, max_wire);
    std::vector<CapturedDatagram> recovered;
    expect(recover_one_with_fec(sink.datagrams, config, 1, recovered),
           "FEC recovers one lost stream config shard");
    ReassembledPayload reassembled;
    expect(reassemble_datagrams(recovered, protected_budget.size, reassembled),
           "FEC-recovered stream config reassembles");
    const auto parsed = warpnect::scl::decode_video_stream_config(as_bytes(reassembled.payload));
    expect(parsed.ok(), "FEC-recovered stream config parses");
    CsdEntryCursor cursor(parsed.config);
    const auto decoded_csd = cursor.next();
    expect(decoded_csd.ok(), "recovered stream config CSD entry parses");
    expect(bytes_equal(decoded_csd.entry.bytes, as_bytes(csd)),
           "recovered stream config CSD is exact");
}

void test_nack_fallback_after_fec_capacity_exceeded() {
    constexpr std::size_t max_wire = 96;
    const VideoSizeResult protected_budget =
        warpnect::scl::video_fragment_datagram_budget(max_wire, true);
    const auto au = make_bytes(100, 73);
    CollectingSink sink;
    expect(build_video_datagrams(protected_budget.size, 3000, 4, 8, 2222, true, as_bytes(au),
                                 sink),
           "NACK fallback AU packetizes");

    const FecBlockConfig config = fec_config(4, 1, 3000, max_wire);
    FecEncoderStorage encoder_storage(config);
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    RetransmissionCacheConfig cache_config{
        .slot_count = 8,
        .max_datagram_size = max_wire,
    };
    std::vector<std::byte> cache_storage(
        warpnect::scl::required_retransmission_datagram_storage_size(cache_config).size);
    std::vector<RetransmissionEntry> entries(cache_config.slot_count);
    RetransmissionCache cache(
        cache_config,
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = entries});
    for (const CapturedDatagram& datagram : sink.datagrams) {
        expect(cache.store(PayloadType::Video, datagram.header.sequence_number,
                           as_bytes(datagram.bytes))
                   .ok(),
               "video datagram is cached exactly");
        expect(encoder.accept_data_datagram(as_bytes(datagram.bytes)).ok(),
               "video datagram enters FEC encoder");
    }
    expect(encoder.encode().ok(), "NACK fallback FEC block encodes");
    const auto parity = encoder.parity_view(0);
    expect(parity.ok(), "NACK fallback parity exists");

    FecRecoveryStorage recovery_storage(config);
    FecRecoveryBlock recovery(recovery_storage.workspace());
    for (std::uint8_t i = 0; i < config.rs.data_shards; ++i) {
        if (i == 1 || i == 3) {
            continue;
        }
        expect(recovery.accept_data_datagram(config, as_bytes(sink.datagrams[i].bytes)).ok(),
               "NACK fallback accepts remaining data");
    }
    expect(recovery.accept_parity(parity.parity).ok(), "NACK fallback accepts parity");
    expect_equal(recovery.recover().error, FecError::InsufficientShards,
                 "loss exceeding parity is explicit");

    const NackRequest nack{
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = 3000,
        .missing_bitmap = (std::uint64_t{1} << 1U) | (std::uint64_t{1} << 3U),
    };
    NackSequenceCursor cursor(nack);
    std::vector<CapturedDatagram> completed;
    completed.push_back(sink.datagrams[0]);
    completed.push_back(sink.datagrams[2]);
    while (cursor.has_next()) {
        const auto sequence = cursor.next();
        expect(sequence.ok(), "NACK sequence cursor advances");
        const auto cached = cache.find(PayloadType::Video, sequence.sequence_number);
        expect(cached.ok(), "NACK sequence has exact cached datagram");
        const auto decoded = warpnect::scl::decode_packet(cached.datagram);
        expect(decoded.ok(), "cached retransmission decodes");
        completed.push_back(CapturedDatagram{.bytes = to_vector(cached.datagram),
                                             .header = decoded.packet.header});
    }
    std::sort(completed.begin(), completed.end(),
              [](const CapturedDatagram& lhs, const CapturedDatagram& rhs) {
                  return lhs.header.slice_index < rhs.header.slice_index;
              });

    ReassembledPayload reassembled;
    expect(reassemble_datagrams(completed, protected_budget.size, reassembled),
           "NACK-completed AU reassembles");
    const auto parsed =
        warpnect::scl::decode_video_access_unit(as_bytes(reassembled.payload),
                                                reassembled.timestamp_us);
    expect(parsed.ok(), "NACK-completed AU parses");
    expect(bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)),
           "NACK-completed AU bytes are exact");
}

[[nodiscard]] UdpReceiveResult receive_until_ready(UdpSocket& socket,
                                                   std::span<std::byte> buffer) noexcept {
    UdpReceiveResult result{};
    for (int attempt = 0; attempt < 10000; ++attempt) {
        result = socket.receive_from(buffer);
        if (result.status.error != UdpError::WouldBlock) {
            return result;
        }
    }
    return result;
}

[[nodiscard]] bool open_bound_receiver(UdpSocket& receiver, UdpEndpoint& endpoint) {
    const auto opened = receiver.open(IpVersion::V4);
    if (!opened.ok()) {
        skip("IPv4 UDP receiver socket unavailable");
        return false;
    }
    const auto bound = receiver.bind(UdpEndpoint::loopback_v4(0));
    if (!bound.ok()) {
        skip("IPv4 UDP receiver bind unavailable");
        return false;
    }
    const auto local = receiver.local_endpoint();
    expect(local.ok(), "UDP receiver local endpoint is available");
    if (!local.ok()) {
        return false;
    }
    endpoint = local.endpoint;
    expect(endpoint.address == IpAddress::loopback_v4(), "receiver is bound to loopback");
    expect(endpoint.port != 0, "receiver ephemeral port is nonzero");
    return true;
}

struct SenderStorage final {
    std::vector<std::byte> datagram_scratch{};
    std::vector<std::byte> protected_datagram_scratch{};
    std::vector<std::byte> cache_storage{};
    std::vector<RetransmissionEntry> cache_entries{};
    std::vector<std::byte> fec_data{};
    std::vector<std::byte> fec_parity{};
    std::vector<std::byte> fec_matrix{};
    std::vector<std::byte> fec_scratch{};
    std::vector<std::byte> fec_payload{};

    SenderStorage(std::size_t max_wire,
                  std::size_t cache_slots,
                  VideoTransportFecConfig fec,
                  std::size_t fec_datagram_budget = 0) {
        datagram_scratch.resize(max_wire);
        protected_datagram_scratch.resize(max_wire);
        RetransmissionCacheConfig cache_config{
            .slot_count = cache_slots,
            .max_datagram_size = max_wire,
        };
        cache_storage.resize(
            warpnect::scl::required_retransmission_datagram_storage_size(cache_config).size);
        cache_entries.resize(cache_slots);
        if (fec.enabled) {
            const std::size_t effective_fec_budget =
                fec_datagram_budget == 0 ? max_wire : fec_datagram_budget;
            const FecBlockConfig block = fec_config(fec.data_shards, fec.parity_shards, 0,
                                                   effective_fec_budget);
            fec_data.resize(warpnect::scl::required_fec_encoder_data_storage_size(block).size);
            fec_parity.resize(
                warpnect::scl::required_fec_encoder_parity_storage_size(block).size);
            fec_matrix.resize(
                warpnect::scl::required_reed_solomon_matrix_storage_size(block.rs).size);
            fec_scratch.resize(
                warpnect::scl::required_reed_solomon_scratch_storage_size(block.rs).size);
            const auto shard_size = warpnect::scl::fec_shard_size(block);
            fec_payload.resize(warpnect::scl::kFecParityHeaderWireSize + shard_size.size);
        }
    }

    [[nodiscard]] VideoTransportSenderWorkspace workspace() noexcept {
        return VideoTransportSenderWorkspace{
            .datagram_scratch = datagram_scratch,
            .retransmission_datagram_storage = cache_storage,
            .retransmission_entries = cache_entries,
            .fec_data_shard_storage = fec_data,
            .fec_parity_shard_storage = fec_parity,
            .fec_matrix_storage = fec_matrix,
            .fec_scratch_storage = fec_scratch,
            .fec_parity_payload_scratch = fec_payload,
            .protected_datagram_scratch = protected_datagram_scratch,
        };
    }
};

[[nodiscard]] bool receive_datagram(UdpSocket& receiver, CapturedDatagram& out) {
    std::array<std::byte, 2048> buffer{};
    const UdpReceiveResult received = receive_until_ready(receiver, buffer);
    expect(received.ok(), "UDP datagram receives");
    if (!received.ok()) {
        return false;
    }
    out.bytes = std::vector<std::byte>(buffer.begin(), buffer.begin() + received.bytes_received);
    const auto decoded = warpnect::scl::decode_packet(as_bytes(out.bytes));
    expect(decoded.ok(), "received UDP datagram decodes");
    if (!decoded.ok()) {
        return false;
    }
    out.header = decoded.packet.header;
    return true;
}

[[nodiscard]] bool receive_secure_datagram(UdpSocket& receiver,
                                           std::vector<std::byte>& wire,
                                           UdpEndpoint& source) {
    std::array<std::byte, 2048> buffer{};
    const UdpReceiveResult received = receive_until_ready(receiver, buffer);
    expect(received.ok(), "secure UDP datagram receives");
    if (!received.ok()) return false;
    wire.assign(buffer.begin(), buffer.begin() + received.bytes_received);
    source = received.source;
    return true;
}

[[nodiscard]] bool unprotect_captured(RuntimeDatagramProtector& protector,
                                      const UdpEndpoint& source,
                                      const std::vector<std::byte>& wire,
                                      CapturedDatagram& captured,
                                      std::uint64_t now_us) {
    std::array<std::byte, 2048> inner{};
    const auto unprotected = protector.unprotect(source, as_bytes(wire), inner, now_us);
    expect(unprotected.ok(), "WNSD video datagram authenticates before SCL parsing");
    if (!unprotected.ok()) return false;
    captured.bytes.assign(inner.begin(), inner.begin() + unprotected.bytes_written);
    const auto decoded = warpnect::scl::decode_packet(as_bytes(captured.bytes));
    expect(decoded.ok(), "authenticated inner video datagram decodes");
    if (!decoded.ok()) return false;
    captured.header = decoded.packet.header;
    return true;
}

void test_protected_video_nack_retransmits_exact_cached_wnsd() {
    constexpr std::size_t secure_budget = 1200;
    UdpSocket sender_socket;
    UdpSocket receiver_socket;
    if (!sender_socket.open(IpVersion::V4).ok() ||
        !receiver_socket.open(IpVersion::V4).ok() ||
        !sender_socket.bind(UdpEndpoint::loopback_v4(0)).ok() ||
        !receiver_socket.bind(UdpEndpoint::loopback_v4(0)).ok()) {
        skip("protected video NACK UDP sockets unavailable");
        return;
    }
    const auto sender_local = sender_socket.local_endpoint();
    const auto receiver_local = receiver_socket.local_endpoint();
    if (!sender_local.ok() || !receiver_local.ok()) {
        skip("protected video NACK UDP endpoints unavailable");
        return;
    }

    SessionProtectionRuntime sender_security({});
    SessionProtectionRuntime receiver_security({});
    const auto root = protection_root();
    const auto session = protection_session_id();
    const auto transcript = protection_transcript();
    expect(sender_security.initialize(root, session, 1, transcript,
                                      SessionProtectionLocalRole::Host).ok(),
           "protected video Host security initializes");
    expect(receiver_security.initialize(root, session, 1, transcript,
                                        SessionProtectionLocalRole::Client).ok(),
           "protected video Client security initializes");
    const ProtectionScope scope = ProtectionScope::channel(17);
    expect(sender_security.create_context(scope, receiver_local.endpoint).ok(),
           "protected video sender Channel context initializes");
    expect(receiver_security.create_context(scope, sender_local.endpoint).ok(),
           "protected video receiver Channel context initializes");
    RuntimeDatagramProtector sender_protector(sender_security, scope);
    RuntimeDatagramProtector receiver_protector(receiver_security, scope);

    SenderStorage storage(secure_budget, 16, VideoTransportFecConfig{});
    VideoTransportSender sender(
        VideoTransportSenderConfig{
            .remote_endpoint = receiver_local.endpoint,
            .local_port = sender_local.endpoint.port,
            .max_wire_datagram_size = secure_budget,
            .initial_video_sequence = 5000,
            .initial_control_sequence = 9000,
            .initial_frame_id = 41,
            .retransmission_cache_slots = 16,
            .fec = {},
            .protector = &sender_protector,
        },
        storage.workspace());
    sender.adopt_prebound_socket(std::move(sender_socket));
    expect(sender.open().ok(), "protected video sender opens prebound endpoint");

    const std::array<std::byte, 2> csd0{byte(0x67), byte(0x01)};
    const std::array<std::byte, 2> csd1{byte(0x68), byte(0x02)};
    const std::array<CsdEntryView, 2> csd_entries{
        CsdEntryView{.bytes = as_bytes(csd0)},
        CsdEntryView{.bytes = as_bytes(csd1)},
    };
    expect(sender.submit_stream_config(1280, 720, csd_entries).ok(),
           "protected Video stream config sends first");
    const std::size_t config_datagrams =
        static_cast<std::size_t>(sender.snapshot().video_datagrams_sent);
    for (std::size_t index = 0; index < config_datagrams; ++index) {
        std::vector<std::byte> wire;
        UdpEndpoint source{};
        if (!receive_secure_datagram(receiver_socket, wire, source)) return;
        CapturedDatagram ignored;
        if (!unprotect_captured(receiver_protector, source, wire, ignored, index + 1U)) return;
    }

    const auto au = make_bytes(2500, 101);
    expect(sender.submit_access_unit(as_bytes(au), 88'000, true).ok(),
           "protected video AU sends");
    const std::size_t count =
        static_cast<std::size_t>(sender.snapshot().video_datagrams_sent) - config_datagrams;
    expect(count > 2, "protected video AU fragments for NACK");
    std::vector<CapturedDatagram> completed;
    std::vector<std::byte> dropped_wire;
    std::uint32_t first_sequence = 0;
    for (std::size_t index = 0; index < count; ++index) {
        std::vector<std::byte> wire;
        UdpEndpoint source{};
        if (!receive_secure_datagram(receiver_socket, wire, source)) return;
        if (index == 1) {
            dropped_wire = wire;
            continue;
        }
        CapturedDatagram captured;
        if (!unprotect_captured(receiver_protector, source, wire, captured, index + 1U)) return;
        if (index == 0) first_sequence = captured.header.sequence_number;
        completed.push_back(std::move(captured));
    }
    expect(!dropped_wire.empty(), "one protected Video datagram is lost before AEAD receive");

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_payload{};
    expect(warpnect::scl::encode_nack(
               NackRequest{.target_payload_type = PayloadType::Video,
                           .base_sequence_number = first_sequence,
                           .missing_bitmap = std::uint64_t{1} << 1U},
               nack_payload).ok(),
           "protected NACK payload encodes");
    std::array<std::byte, 128> nack_inner{};
    const auto nack_packet = warpnect::scl::encode_packet(
        PacketHeader{.protocol_version = warpnect::scl::kSclProtocolVersion,
                     .flags = 0,
                     .sequence_number = 73,
                     .timestamp_us = 0,
                     .payload_type = PayloadType::SessionControl,
                     .slice_index = 0,
                     .total_slices = 1},
        nack_payload, nack_inner);
    expect(nack_packet.ok(), "protected NACK inner SCL datagram encodes");
    std::array<std::byte, secure_budget> nack_secure{};
    const auto protected_nack = receiver_protector.protect(
        std::span<const std::byte>(nack_inner).first(nack_packet.bytes_written), nack_secure);
    expect(protected_nack.ok(), "NACK is protected with Channel context");
    expect(receiver_socket.send_to(
               std::span<const std::byte>(nack_secure).first(protected_nack.bytes_written),
               sender_local.endpoint).ok(),
           "protected NACK reaches sender endpoint");
    std::array<std::byte, secure_budget> control_receive{};
    expect(sender.pump_control_datagram(control_receive, 100'000).ok(),
           "sender authenticates NACK before retransmission");

    std::vector<std::byte> retransmitted_wire;
    UdpEndpoint retransmitted_source{};
    if (!receive_secure_datagram(receiver_socket, retransmitted_wire, retransmitted_source)) return;
    expect(bytes_equal(as_bytes(retransmitted_wire), as_bytes(dropped_wire)),
           "NACK retransmission is exact cached WNSD bytes");
    CapturedDatagram retransmitted;
    expect(unprotect_captured(receiver_protector, retransmitted_source, retransmitted_wire,
                              retransmitted, 1000),
           "missing protected packet is accepted once");
    expect_equal(retransmitted.header.sequence_number, first_sequence + 1U,
                 "protected retransmission restores missing SCL sequence");
    completed.push_back(retransmitted);

    std::array<std::byte, 2048> replay_output{};
    const auto replay = receiver_protector.unprotect(
        retransmitted_source, as_bytes(retransmitted_wire), replay_output, 1001);
    expect_equal(replay.error, DatagramProtectionError::Rejected,
                 "second protected retransmission is rejected by anti-replay");
    std::sort(completed.begin(), completed.end(),
              [](const CapturedDatagram& lhs, const CapturedDatagram& rhs) {
                  return lhs.header.slice_index < rhs.header.slice_index;
              });
    ReassembledPayload reassembled;
    expect(reassemble_datagrams(completed, sender_protector.inner_datagram_budget(), reassembled),
           "protected NACK-completed AU reassembles");
    const auto parsed = warpnect::scl::decode_video_access_unit(
        as_bytes(reassembled.payload), reassembled.timestamp_us);
    expect(parsed.ok() && bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)),
           "protected NACK-completed Video AU is exact");
    expect_equal(sender.snapshot().retransmissions, 1ULL,
                 "protected sender counts one exact retransmission");
}

void test_adopted_protected_video_transport_rebind_preserves_security_state() {
    constexpr std::size_t secure_budget = 1200;
    UdpSocket old_sender_socket;
    UdpSocket old_receiver_socket;
    if (!old_sender_socket.open(IpVersion::V4).ok() || !old_receiver_socket.open(IpVersion::V4).ok() ||
        !old_sender_socket.bind(UdpEndpoint::loopback_v4(0)).ok() ||
        !old_receiver_socket.bind(UdpEndpoint::loopback_v4(0)).ok()) {
        skip("adopted protected Video transport UDP sockets unavailable");
        return;
    }
    const auto old_sender_endpoint = old_sender_socket.local_endpoint();
    const auto old_receiver_endpoint = old_receiver_socket.local_endpoint();
    if (!old_sender_endpoint.ok() || !old_receiver_endpoint.ok()) return;

    SessionProtectionRuntime sender_security({});
    SessionProtectionRuntime receiver_security({});
    const auto root = protection_root();
    const auto session = protection_session_id();
    const auto transcript = protection_transcript();
    expect(sender_security.initialize(root, session, 1, transcript, SessionProtectionLocalRole::Host).ok(),
           "adopted transport sender security initializes");
    expect(receiver_security.initialize(root, session, 1, transcript, SessionProtectionLocalRole::Client).ok(),
           "adopted transport receiver security initializes");
    constexpr ProtectionScope scope = ProtectionScope::channel(29);
    expect(sender_security.create_context(scope, old_receiver_endpoint.endpoint).ok(),
           "adopted transport sender Channel context initializes");
    expect(receiver_security.create_context(scope, old_sender_endpoint.endpoint).ok(),
           "adopted transport receiver Channel context initializes");
    RuntimeDatagramProtector sender_protector(sender_security, scope);
    RuntimeDatagramProtector receiver_protector(receiver_security, scope);

    SenderStorage storage(secure_budget, 8, VideoTransportFecConfig{});
    VideoTransportSender sender(
        VideoTransportSenderConfig{
            .remote_endpoint = old_receiver_endpoint.endpoint,
            .local_port = old_sender_endpoint.endpoint.port,
            .max_wire_datagram_size = secure_budget,
            .initial_video_sequence = 900,
            .initial_control_sequence = 901,
            .initial_frame_id = 902,
            .retransmission_cache_slots = 8,
            .fec = {},
            .protector = &sender_protector,
        },
        storage.workspace());
    sender.adopt_prebound_socket(std::move(old_sender_socket));
    expect(sender.open().ok(), "prepared Video sender adopts the running socket");

    const std::array<std::byte, 2> csd{byte(0x67), byte(0x01)};
    const std::array<CsdEntryView, 1> csd_entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(sender.submit_stream_config(640, 480, csd_entries).ok(),
           "adopted Video transport sends config before migration");
    std::vector<std::byte> config_wire;
    UdpEndpoint config_source{};
    if (!receive_secure_datagram(old_receiver_socket, config_wire, config_source)) return;
    CapturedDatagram config_inner;
    if (!unprotect_captured(receiver_protector, config_source, config_wire, config_inner, 1)) return;

    const std::array<std::byte, 3> before_au{byte(1), byte(2), byte(3)};
    expect(sender.submit_access_unit(as_bytes(before_au), 10'000, true).ok(),
           "adopted Video transport sends before migration");
    std::vector<std::byte> before_wire;
    UdpEndpoint before_source{};
    if (!receive_secure_datagram(old_receiver_socket, before_wire, before_source)) return;
    CapturedDatagram before_inner;
    if (!unprotect_captured(receiver_protector, before_source, before_wire, before_inner, 2)) return;
    const auto before_header = warpnect::scl::security::decode_secure_datagram_header(
        std::span<const std::byte>(before_wire.data(), warpnect::scl::security::kSecureDatagramHeaderSize));
    expect(before_header.ok(), "protected packet before live rebind has WNSD header");

    UdpSocket migrated_sender_socket;
    UdpSocket migrated_receiver_socket;
    if (!migrated_sender_socket.open(IpVersion::V4).ok() || !migrated_receiver_socket.open(IpVersion::V4).ok() ||
        !migrated_sender_socket.bind(UdpEndpoint::loopback_v4(0)).ok() ||
        !migrated_receiver_socket.bind(UdpEndpoint::loopback_v4(0)).ok()) {
        skip("migrated protected Video transport UDP sockets unavailable");
        return;
    }
    const auto migrated_sender_endpoint = migrated_sender_socket.local_endpoint();
    const auto migrated_receiver_endpoint = migrated_receiver_socket.local_endpoint();
    if (!migrated_sender_endpoint.ok() || !migrated_receiver_endpoint.ok()) return;
    expect(receiver_security.set_expected_remote_endpoint(scope, migrated_sender_endpoint.endpoint).ok(),
           "receiver Channel context accepts only the migrated sender endpoint");
    expect(sender.rebind_prebound_socket(std::move(migrated_sender_socket), migrated_receiver_endpoint.endpoint).ok(),
           "already-adopted Video transport rebinds in place");

    const std::array<std::byte, 3> after_au{byte(4), byte(5), byte(6)};
    expect(sender.submit_access_unit(as_bytes(after_au), 11'000, true).ok(),
           "same adopted Video transport sends after migration");
    std::vector<std::byte> after_wire;
    UdpEndpoint after_source{};
    if (!receive_secure_datagram(migrated_receiver_socket, after_wire, after_source)) return;
    const auto after_header = warpnect::scl::security::decode_secure_datagram_header(
        std::span<const std::byte>(after_wire.data(), warpnect::scl::security::kSecureDatagramHeaderSize));
    expect(after_header.ok(), "protected packet after live rebind has WNSD header");
    if (before_header.ok() && after_header.ok()) {
        expect_equal(after_header.header.protection_context_id, before_header.header.protection_context_id,
                     "adopted transport keeps its Channel ProtectionContextId");
        expect_equal(after_header.header.packet_number, before_header.header.packet_number + 1U,
                     "adopted transport continues the WNSD packet-number space");
    }
    CapturedDatagram after_inner;
    expect(unprotect_captured(receiver_protector, after_source, after_wire, after_inner, 3),
           "migrated endpoint decrypts with the original Channel context");
    std::array<std::byte, 2048> replay_output{};
    expect_equal(receiver_protector.unprotect(before_source, as_bytes(after_wire), replay_output, 4).error,
                 DatagramProtectionError::Rejected,
                 "old remote endpoint is rejected after committed live rebind");
    expect_equal(receiver_protector.unprotect(after_source, as_bytes(before_wire), replay_output, 5).error,
                 DatagramProtectionError::Rejected,
                 "replay state survives the adopted transport rebind");
}

void test_protected_video_fec_unprotects_before_recovery() {
    constexpr std::size_t secure_budget = 1200;
    UdpSocket sender_socket;
    UdpSocket receiver_socket;
    if (!sender_socket.open(IpVersion::V4).ok() ||
        !receiver_socket.open(IpVersion::V4).ok() ||
        !sender_socket.bind(UdpEndpoint::loopback_v4(0)).ok() ||
        !receiver_socket.bind(UdpEndpoint::loopback_v4(0)).ok()) {
        skip("protected video FEC UDP sockets unavailable");
        return;
    }
    const auto sender_local = sender_socket.local_endpoint();
    const auto receiver_local = receiver_socket.local_endpoint();
    if (!sender_local.ok() || !receiver_local.ok()) return;

    SessionProtectionRuntime sender_security({});
    SessionProtectionRuntime receiver_security({});
    const auto root = protection_root();
    const auto session = protection_session_id();
    const auto transcript = protection_transcript();
    expect(sender_security.initialize(root, session, 1, transcript,
                                      SessionProtectionLocalRole::Host).ok(),
           "protected FEC sender security initializes");
    expect(receiver_security.initialize(root, session, 1, transcript,
                                        SessionProtectionLocalRole::Client).ok(),
           "protected FEC receiver security initializes");
    const ProtectionScope scope = ProtectionScope::channel(18);
    expect(sender_security.create_context(scope, receiver_local.endpoint).ok(),
           "protected FEC sender context initializes");
    expect(receiver_security.create_context(scope, sender_local.endpoint).ok(),
           "protected FEC receiver context initializes");
    RuntimeDatagramProtector sender_protector(sender_security, scope);
    RuntimeDatagramProtector receiver_protector(receiver_security, scope);
    const VideoTransportFecConfig fec{.enabled = true, .data_shards = 2, .parity_shards = 1};
    SenderStorage storage(secure_budget, 16, fec, sender_protector.inner_datagram_budget());
    VideoTransportSender sender(
        VideoTransportSenderConfig{
            .remote_endpoint = receiver_local.endpoint,
            .local_port = sender_local.endpoint.port,
            .max_wire_datagram_size = secure_budget,
            .initial_video_sequence = 6000,
            .initial_control_sequence = 9500,
            .initial_frame_id = 51,
            .retransmission_cache_slots = 16,
            .fec = fec,
            .protector = &sender_protector,
        },
        storage.workspace());
    sender.adopt_prebound_socket(std::move(sender_socket));
    expect(sender.open().ok(), "protected FEC sender opens");

    const auto csd = make_bytes(1500, 107);
    const std::array<CsdEntryView, 1> csd_entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(sender.submit_stream_config(1280, 720, csd_entries).ok(),
           "protected FEC stream config sends complete block");
    const std::size_t config_data =
        static_cast<std::size_t>(sender.snapshot().video_datagrams_sent);
    const std::size_t config_parity =
        static_cast<std::size_t>(sender.snapshot().fec_parity_packets);
    expect_equal(config_data, static_cast<std::size_t>(2),
                 "protected FEC config emits a complete two-shard block");
    expect_equal(config_parity, static_cast<std::size_t>(1),
                 "protected FEC config flushes one parity record");
    for (std::size_t index = 0; index < config_data + config_parity; ++index) {
        std::vector<std::byte> wire;
        UdpEndpoint source{};
        if (!receive_secure_datagram(receiver_socket, wire, source)) return;
        CapturedDatagram ignored;
        if (!unprotect_captured(receiver_protector, source, wire, ignored, index + 1U)) return;
    }
    const std::uint64_t decrypted_before_au = receiver_security.snapshot().decrypted_packets;
    const auto au = make_bytes(1500, 109);
    expect(sender.submit_access_unit(as_bytes(au), 99'000, false).ok(),
           "protected FEC Video AU sends");
    expect_equal(sender.snapshot().video_datagrams_sent - config_data, 2ULL,
                 "protected FEC AU emits two data shards");
    expect_equal(sender.snapshot().fec_parity_packets - config_parity, 1ULL,
                 "protected FEC emits one parity datagram");

    std::vector<std::vector<std::byte>> wire;
    std::vector<UdpEndpoint> sources;
    for (int index = 0; index < 3; ++index) {
        wire.emplace_back();
        sources.emplace_back();
        if (!receive_secure_datagram(receiver_socket, wire.back(), sources.back())) return;
    }
    CapturedDatagram first_data;
    CapturedDatagram parity;
    if (!unprotect_captured(receiver_protector, sources[0], wire[0], first_data, 1) ||
        !unprotect_captured(receiver_protector, sources[2], wire[2], parity, 2)) {
        return;
    }
    const auto parity_packet = warpnect::scl::decode_packet(as_bytes(parity.bytes));
    expect(parity_packet.ok() && parity_packet.packet.header.payload_type == PayloadType::SessionControl,
           "FEC parity is authenticated before SessionControl parsing");
    const auto parity_view = warpnect::scl::decode_fec_parity_payload(parity_packet.packet.payload);
    expect(parity_view.ok(), "authenticated FEC parity payload decodes");

    const FecBlockConfig block = fec_config(2, 1, first_data.header.sequence_number,
                                            sender_protector.inner_datagram_budget());
    FecRecoveryStorage recovery_storage(block);
    FecRecoveryBlock recovery(recovery_storage.workspace());
    expect(recovery.accept_data_datagram(block, as_bytes(first_data.bytes)).ok(),
           "FEC accepts authenticated inner data shard");
    expect(recovery.accept_parity(parity_view.parity).ok(),
           "FEC accepts authenticated inner parity shard");
    expect(recovery.recover().ok(), "FEC recovers ciphertext-lost inner Video shard");
    const auto missing = recovery.datagram(1);
    expect(missing.ok(), "FEC exposes recovered inner Video datagram");
    const auto missing_packet = warpnect::scl::decode_packet(missing.datagram.datagram);
    expect(missing_packet.ok(), "FEC recovered inner SCL datagram decodes");
    std::vector<CapturedDatagram> completed{
        first_data,
        CapturedDatagram{.bytes = to_vector(missing.datagram.datagram),
                         .header = missing_packet.packet.header},
    };
    std::sort(completed.begin(), completed.end(),
              [](const CapturedDatagram& lhs, const CapturedDatagram& rhs) {
                  return lhs.header.slice_index < rhs.header.slice_index;
              });
    ReassembledPayload reassembled;
    const auto fragment_budget = warpnect::scl::video_fragment_datagram_budget(
        sender_protector.inner_datagram_budget(), true);
    expect(fragment_budget.ok() &&
               reassemble_datagrams(completed, fragment_budget.size, reassembled),
           "protected FEC recovered AU reassembles");
    const auto parsed = warpnect::scl::decode_video_access_unit(
        as_bytes(reassembled.payload), reassembled.timestamp_us);
    expect(parsed.ok() && bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)),
           "protected FEC recovered Video AU is exact");
    expect_equal(receiver_security.snapshot().decrypted_packets - decrypted_before_au, 2ULL,
                 "only received WNSD records decrypt before FEC recovery");
}

void test_video_transport_sender_udp_loopback_and_nack() {
    UdpSocket receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver(receiver, endpoint)) {
        return;
    }

    constexpr std::size_t max_wire = 256;
    SenderStorage storage(max_wire, 16, VideoTransportFecConfig{});
    VideoTransportSender sender(
        VideoTransportSenderConfig{
            .remote_endpoint = endpoint,
            .local_port = 0,
            .max_wire_datagram_size = max_wire,
            .initial_video_sequence = 4000,
            .initial_control_sequence = 9000,
            .initial_frame_id = std::numeric_limits<std::uint32_t>::max() - 1U,
            .retransmission_cache_slots = 16,
            .fec = {},
        },
        storage.workspace());
    expect(sender.open().ok(), "video transport sender opens");

    const std::array<std::byte, 2> csd0{byte(0x67), byte(0x01)};
    const std::array<std::byte, 2> csd1{byte(0x68), byte(0x02)};
    const std::array<CsdEntryView, 2> csd_entries{
        CsdEntryView{.bytes = as_bytes(csd0)},
        CsdEntryView{.bytes = as_bytes(csd1)},
    };
    expect(sender.submit_stream_config(640, 360, csd_entries).ok(),
           "sender submits stream config");
    CapturedDatagram config_datagram;
    if (receive_datagram(receiver, config_datagram)) {
        const auto decoded = warpnect::scl::decode_packet(as_bytes(config_datagram.bytes));
        const auto parsed = warpnect::scl::decode_video_stream_config(decoded.packet.payload);
        expect(parsed.ok(), "UDP stream config parses");
        expect_equal(parsed.config.config_generation, 1U, "UDP stream config generation is 1");
    }

    const auto au = make_bytes(512, 91);
    expect(sender.submit_access_unit(as_bytes(au), 7777, true).ok(),
           "sender submits large AU");
    const auto after_submit = sender.snapshot();
    const std::size_t au_datagrams =
        static_cast<std::size_t>(after_submit.video_datagrams_sent - 1U);
    expect(au_datagrams > 1, "UDP NACK test AU fragments");

    std::vector<CapturedDatagram> received;
    for (std::size_t i = 0; i < au_datagrams; ++i) {
        CapturedDatagram datagram;
        if (receive_datagram(receiver, datagram)) {
            received.push_back(std::move(datagram));
        }
    }
    expect_equal(received.size(), au_datagrams, "all initial AU datagrams receive");
    if (received.size() < 2) {
        return;
    }

    const std::uint32_t missing_sequence = received[1].header.sequence_number;
    std::vector<CapturedDatagram> completed;
    completed.push_back(received[0]);
    for (std::size_t i = 2; i < received.size(); ++i) {
        completed.push_back(received[i]);
    }

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_payload{};
    expect(
        warpnect::scl::encode_nack(
            NackRequest{
                .target_payload_type = PayloadType::Video,
                .base_sequence_number = received[0].header.sequence_number,
                .missing_bitmap = std::uint64_t{1} << 1U,
            },
            nack_payload)
            .ok(),
        "NACK payload encodes");
    std::array<std::byte, 128> nack_datagram{};
    const auto encoded_nack = warpnect::scl::encode_packet(
        PacketHeader{
            .protocol_version = warpnect::scl::kSclProtocolVersion,
            .flags = 0,
            .sequence_number = 123,
            .timestamp_us = 0,
            .payload_type = PayloadType::SessionControl,
            .slice_index = 0,
            .total_slices = 1,
        },
        nack_payload,
        nack_datagram);
    expect(encoded_nack.ok(), "NACK datagram encodes");
    expect(sender.handle_control_datagram(
                     std::span<const std::byte>(nack_datagram).first(encoded_nack.bytes_written))
               .ok(),
           "sender handles NACK control datagram");
    CapturedDatagram retransmitted;
    if (receive_datagram(receiver, retransmitted)) {
        expect_equal(retransmitted.header.sequence_number, missing_sequence,
                     "retransmitted datagram sequence matches missing");
        completed.push_back(std::move(retransmitted));
    }
    std::sort(completed.begin(), completed.end(),
              [](const CapturedDatagram& lhs, const CapturedDatagram& rhs) {
                  return lhs.header.slice_index < rhs.header.slice_index;
              });

    ReassembledPayload reassembled;
    expect(reassemble_datagrams(completed, max_wire, reassembled),
           "UDP NACK-completed AU reassembles");
    const auto parsed =
        warpnect::scl::decode_video_access_unit(as_bytes(reassembled.payload),
                                                reassembled.timestamp_us);
    expect(parsed.ok(), "UDP NACK-completed AU parses");
    expect_equal(parsed.access_unit.frame_id, std::numeric_limits<std::uint32_t>::max() - 1U,
                 "sender frame ID remains independent from packet sequence");
    expect(parsed.access_unit.is_key_frame, "UDP keyframe flag is preserved");
    expect(bytes_equal(parsed.access_unit.encoded_bytes, as_bytes(au)),
           "UDP loopback AU bytes are exact after NACK retransmission");
    expect_equal(sender.snapshot().retransmissions, 1ULL, "sender counts retransmission");
}

int run_all_tests() {
    test_common_video_header_golden();
    test_stream_config_golden_and_csd_round_trip();
    test_access_unit_golden_and_small_round_trip();
    test_malformed_video_payloads();
    test_segmented_fragmentation_crosses_header_boundary();
    test_large_access_units_round_trip();
    test_sequence_frame_generation_keyframe_and_pts_semantics();
    test_video_fec_recovery();
    test_stream_config_fec_recovery();
    test_nack_fallback_after_fec_capacity_exceeded();
    test_protected_video_fec_unprotects_before_recovery();
    test_protected_video_nack_retransmits_exact_cached_wnsd();
    test_adopted_protected_video_transport_rebind_preserves_security_state();
    test_video_transport_sender_udp_loopback_and_nack();
    return failures == 0 ? 0 : 1;
}

} // namespace

int main() {
    const int result = run_all_tests();
    if (skips != 0) {
        std::cout << "SKIPPED: " << skips << " optional UDP-dependent checks\n";
    }
    return result;
}
