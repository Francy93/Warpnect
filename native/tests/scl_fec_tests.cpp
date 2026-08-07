#include "fec.h"
#include "fec_control.h"
#include "fragmentation.h"
#include "loss_detector.h"
#include "packet_codec.h"
#include "reassembly.h"
#include "recovery_control.h"
#include "retransmission_cache.h"
#include "udp_socket.h"

#include "internal/gf256.h"
#include "internal/gf256_matrix.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <utility>

namespace {

using warpnect::scl::FecAcceptResult;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::FecBlockEncoder;
using warpnect::scl::FecEncoderWorkspace;
using warpnect::scl::FecError;
using warpnect::scl::FecParityHeader;
using warpnect::scl::FecParityView;
using warpnect::scl::FecRecoveryBlock;
using warpnect::scl::FecRecoveryWorkspace;
using warpnect::scl::FragmentationConfig;
using warpnect::scl::FragmentCursor;
using warpnect::scl::FragmentView;
using warpnect::scl::IpAddress;
using warpnect::scl::IpVersion;
using warpnect::scl::LossObservationKind;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::LossSlot;
using warpnect::scl::LossSlotState;
using warpnect::scl::MutableShardView;
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
using warpnect::scl::ShardView;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::UdpError;
using warpnect::scl::UdpReceiveResult;
using warpnect::scl::UdpSocket;

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

[[nodiscard]] bool bytes_equal(std::span<const std::byte> left,
                               std::span<const std::byte> right) noexcept {
    if (left.size() != right.size()) {
        return false;
    }

    for (std::size_t i = 0; i < left.size(); ++i) {
        if (left[i] != right[i]) {
            return false;
        }
    }

    return true;
}

void fill_bytes(std::span<std::byte> bytes, std::uint8_t seed = 17) noexcept {
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 31U + seed) & 0xFFU));
    }
}

[[nodiscard]] constexpr PacketHeader data_header(std::uint32_t sequence,
                                                 PayloadType payload_type = PayloadType::Video,
                                                 std::uint64_t timestamp_us = 0x0102030405060708ULL,
                                                 std::uint16_t flags = 0x00A5U) noexcept {
    return PacketHeader{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = flags,
        .sequence_number = sequence,
        .timestamp_us = timestamp_us,
        .payload_type = payload_type,
        .slice_index = 0,
        .total_slices = 1,
    };
}

struct RsStorage final {
    std::array<std::byte, 8192> matrix{};
    std::array<std::byte, 255> scratch{};

    [[nodiscard]] ReedSolomonWorkspace workspace() noexcept {
        return ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch};
    }
};

struct FecEncoderStorage final {
    std::array<std::byte, 8192> data{};
    std::array<std::byte, 8192> parity{};
    std::array<std::byte, 8192> matrix{};
    std::array<std::byte, 255> scratch{};

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
    std::array<std::byte, 16384> shards{};
    std::array<std::byte, 32> presence{};
    std::array<std::byte, 8192> matrix{};
    std::array<std::byte, 255> scratch{};

    [[nodiscard]] FecRecoveryWorkspace workspace() noexcept {
        return FecRecoveryWorkspace{
            .shard_storage = shards,
            .present_bitmap = presence,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }
};

struct PacketSet final {
    std::array<std::array<std::byte, 128>, 8> datagrams{};
    std::array<std::size_t, 8> sizes{};
    std::size_t count = 0;
};

[[nodiscard]] std::span<const std::byte> packet_at(const PacketSet& packets,
                                                   std::size_t index) noexcept {
    return std::span<const std::byte>(packets.datagrams[index]).first(packets.sizes[index]);
}

[[nodiscard]] bool encode_test_packet(PacketSet& packets, std::size_t index, std::uint32_t sequence,
                                      PayloadType payload_type, std::size_t payload_size,
                                      std::uint8_t seed = 7) noexcept {
    std::array<std::byte, 48> payload{};
    fill_bytes(std::span<std::byte>(payload).first(payload_size), seed);
    const PacketHeader header =
        data_header(sequence, payload_type, 0x100000000ULL + sequence,
                    static_cast<std::uint16_t>(0x0100U + static_cast<std::uint16_t>(index)));
    const auto encoded = warpnect::scl::encode_packet(
        header, std::span<const std::byte>(payload).first(payload_size), packets.datagrams[index]);
    expect(encoded.ok(), "test SCL data packet encodes");
    if (!encoded.ok()) {
        return false;
    }

    packets.sizes[index] = encoded.bytes_written;
    packets.count = std::max(packets.count, index + 1U);
    return true;
}

[[nodiscard]] bool build_packet_set(PacketSet& packets, std::uint8_t count,
                                    std::uint32_t base_sequence,
                                    PayloadType payload_type) noexcept {
    constexpr std::array<std::size_t, 8> payload_sizes{3, 7, 0, 11, 19, 5, 13, 17};
    for (std::uint8_t i = 0; i < count; ++i) {
        if (!encode_test_packet(packets, i, base_sequence + i, payload_type, payload_sizes[i],
                                static_cast<std::uint8_t>(9U + i))) {
            return false;
        }
    }

    return true;
}

[[nodiscard]] constexpr FecBlockConfig
fec_config(std::uint8_t data_shards, std::uint8_t parity_shards, std::uint32_t base_sequence = 100,
           PayloadType payload_type = PayloadType::Video, std::size_t budget = 96) noexcept {
    return FecBlockConfig{
        .rs = ReedSolomonConfig{.data_shards = data_shards, .parity_shards = parity_shards},
        .target_payload_type = payload_type,
        .base_sequence_number = base_sequence,
        .max_wire_datagram_size = budget,
    };
}

[[nodiscard]] bool encode_fec_block(const FecBlockConfig& config, const PacketSet& packets,
                                    FecBlockEncoder& encoder) noexcept {
    for (std::uint8_t i = 0; i < config.rs.data_shards; ++i) {
        const FecAcceptResult accepted = encoder.accept_data_datagram(packet_at(packets, i));
        expect(accepted.ok(), "FEC encoder accepts source datagram");
        expect_equal(accepted.shard_index, i, "FEC encoder source shard index");
        if (!accepted.ok()) {
            return false;
        }
    }

    expect(encoder.encode().ok(), "FEC encoder produces parity");
    return encoder.is_encoded();
}

[[nodiscard]] UdpReceiveResult receive_until_ready(UdpSocket& socket,
                                                   std::span<std::byte> buffer) noexcept {
    UdpReceiveResult result{};
    for (int attempt = 0; attempt < 20000; ++attempt) {
        result = socket.receive_from(buffer);
        if (result.status.error != UdpError::WouldBlock) {
            return result;
        }
    }

    return result;
}

[[nodiscard]] bool open_bound_socket_v4(UdpSocket& socket, UdpEndpoint& endpoint) noexcept {
    expect(socket.open(IpVersion::V4).ok(), "IPv4 socket opens");
    if (!socket.is_open()) {
        return false;
    }

    const auto bound = socket.bind(UdpEndpoint::loopback_v4(0));
    expect(bound.ok(), "IPv4 socket binds loopback");
    if (!bound.ok()) {
        return false;
    }

    const auto local = socket.local_endpoint();
    expect(local.ok(), "IPv4 socket local endpoint");
    if (!local.ok()) {
        return false;
    }

    expect(local.endpoint.address == IpAddress::loopback_v4(), "IPv4 local endpoint is loopback");
    expect(local.endpoint.port != 0, "IPv4 local endpoint gets ephemeral port");
    endpoint = local.endpoint;
    return true;
}

void test_gf_arithmetic_and_tables() {
    using warpnect::scl::internal::gf256::add;
    using warpnect::scl::internal::gf256::divide;
    using warpnect::scl::internal::gf256::inverse;
    using warpnect::scl::internal::gf256::kPrimitivePolynomial;
    using warpnect::scl::internal::gf256::kTables;
    using warpnect::scl::internal::gf256::multiply;
    using warpnect::scl::internal::gf256::power;
    using warpnect::scl::internal::gf256::subtract;

    expect_equal(kPrimitivePolynomial, static_cast<std::uint16_t>(0x11D),
                 "GF(256) primitive polynomial is 0x11D");
    expect_equal(kTables.exp[0], static_cast<std::uint8_t>(1), "exp table starts at one");
    expect_equal(kTables.exp[255], static_cast<std::uint8_t>(1), "exp table repeats after cycle");

    std::array<bool, 256> seen{};
    for (std::uint16_t i = 0; i < 255; ++i) {
        const auto value = kTables.exp[i];
        expect(value != 0, "GF non-zero cycle contains non-zero values");
        expect(!seen[value], "GF non-zero cycle has no duplicate before repeat");
        seen[value] = true;
    }

    for (std::uint16_t i = 0; i < 256; ++i) {
        const auto value = static_cast<std::uint8_t>(i);
        expect_equal(add(value, value), static_cast<std::uint8_t>(0), "a + a = 0");
        expect_equal(subtract(value, value), static_cast<std::uint8_t>(0), "a - a = 0");
        expect_equal(multiply(value, 0), static_cast<std::uint8_t>(0), "a * 0 = 0");
        expect_equal(multiply(value, 1), value, "a * 1 = a");
        expect_equal(power(value, 0), static_cast<std::uint8_t>(1), "a^0 = 1");
        expect_equal(power(value, 1), value, "a^1 = a");
        if (value != 0) {
            expect_equal(multiply(value, inverse(value)), static_cast<std::uint8_t>(1),
                         "a * inverse(a) = 1");
            expect_equal(divide(multiply(value, 7), 7), value, "division round trip");
        }
    }
}

void test_matrix_and_systematic_rows() {
    {
        std::array<std::byte, 4> matrix{byte(1), byte(0), byte(1), byte(1)};
        const std::array<std::byte, 4> original = matrix;
        std::array<std::byte, 4> inverse{};
        expect(warpnect::scl::internal::invert_square_matrix(matrix, inverse, 2).ok(),
               "small matrix inverts");
        std::array<std::byte, 4> product{};
        warpnect::scl::internal::multiply_matrices(original, 2, 2, inverse, 2, product);
        expect_equal(product, std::array<std::byte, 4>{byte(1), byte(0), byte(0), byte(1)},
                     "matrix multiplied by inverse is identity");
    }

    {
        std::array<std::byte, 4> singular{byte(1), byte(2), byte(1), byte(2)};
        std::array<std::byte, 4> inverse{};
        expect_equal(warpnect::scl::internal::invert_square_matrix(singular, inverse, 2).error,
                     FecError::SingularMatrix, "singular matrix is reported");
    }

    constexpr std::array<ReedSolomonConfig, 3> configs{
        ReedSolomonConfig{.data_shards = 2, .parity_shards = 1},
        ReedSolomonConfig{.data_shards = 4, .parity_shards = 2},
        ReedSolomonConfig{.data_shards = 8, .parity_shards = 3},
    };
    for (const ReedSolomonConfig config : configs) {
        RsStorage storage{};
        std::array<std::byte, 2048> matrix{};
        expect(warpnect::scl::build_systematic_matrix(config, storage.workspace(), matrix).ok(),
               "systematic matrix builds");
        for (std::uint8_t row = 0; row < config.data_shards; ++row) {
            for (std::uint8_t column = 0; column < config.data_shards; ++column) {
                const auto expected = byte(row == column ? 1U : 0U);
                expect_equal(
                    matrix[warpnect::scl::internal::matrix_index(row, column, config.data_shards)],
                    expected, "systematic row is identity");
            }
        }
    }
}

void test_reed_solomon_encode_and_recover() {
    {
        constexpr ReedSolomonConfig config{.data_shards = 2, .parity_shards = 1};
        std::array<std::byte, 4> first{byte(1), byte(2), byte(3), byte(4)};
        std::array<std::byte, 4> second{byte(5), byte(6), byte(7), byte(8)};
        std::array<std::byte, 4> parity{};
        std::array<ShardView, 2> data{ShardView{first}, ShardView{second}};
        std::array<MutableShardView, 1> parity_views{MutableShardView{parity}};
        RsStorage storage{};
        expect(warpnect::scl::encode_parity(config, data, parity_views, storage.workspace()).ok(),
               "golden RS parity encodes");
        expect_equal(parity, std::array<std::byte, 4>{byte(9), byte(10), byte(11), byte(28)},
                     "golden RS parity vector");
        expect_equal(first, std::array<std::byte, 4>{byte(1), byte(2), byte(3), byte(4)},
                     "systematic source data unchanged");
    }

    constexpr ReedSolomonConfig config{.data_shards = 4, .parity_shards = 2};
    std::array<std::array<std::byte, 8>, 4> original{};
    for (std::size_t i = 0; i < original.size(); ++i) {
        fill_bytes(original[i], static_cast<std::uint8_t>(11U + i));
    }

    std::array<std::array<std::byte, 8>, 2> parity{};
    std::array<ShardView, 4> data_views{};
    std::array<MutableShardView, 2> parity_views{};
    for (std::size_t i = 0; i < original.size(); ++i) {
        data_views[i] = ShardView{original[i]};
    }
    for (std::size_t i = 0; i < parity.size(); ++i) {
        parity_views[i] = MutableShardView{parity[i]};
    }
    RsStorage encode_storage{};
    expect(
        warpnect::scl::encode_parity(config, data_views, parity_views, encode_storage.workspace())
            .ok(),
        "K=4 M=2 parity encodes");

    const auto recover_case = [&](std::array<bool, 6> present) {
        std::array<std::array<std::byte, 8>, 6> shards{};
        for (std::size_t i = 0; i < 4; ++i) {
            shards[i] = original[i];
        }
        for (std::size_t i = 0; i < 2; ++i) {
            shards[4 + i] = parity[i];
        }

        std::array<MutableShardView, 6> shard_views{};
        for (std::size_t i = 0; i < shards.size(); ++i) {
            shard_views[i] = MutableShardView{shards[i]};
        }

        RsStorage recover_storage{};
        const auto result = warpnect::scl::recover_data_shards(config, shard_views, present,
                                                               recover_storage.workspace());
        return std::pair{result, shards};
    };

    {
        auto [result, shards] = recover_case({true, false, true, true, true, true});
        expect(result.ok(), "single missing data shard recovers");
        expect_equal(shards[1], original[1], "single missing shard matches original");
    }
    {
        auto [result, shards] = recover_case({false, true, true, false, true, true});
        expect(result.ok(), "two missing data shards recover");
        expect_equal(shards[0], original[0], "first recovered shard matches original");
        expect_equal(shards[3], original[3], "second recovered shard matches original");
    }
    {
        auto [result, shards] = recover_case({true, true, true, true, false, false});
        expect_equal(result.error, FecError::NoRecoveryNeeded,
                     "parity-only loss needs no recovery");
        expect_equal(shards[2], original[2], "data remains intact when only parity is missing");
    }
    {
        auto [result, shards] = recover_case({false, false, false, true, true, true});
        (void)shards;
        expect_equal(result.error, FecError::InsufficientShards, "too many erasures are reported");
    }

    for (std::uint8_t missing_a = 0; missing_a < 6; ++missing_a) {
        for (std::uint8_t missing_b = missing_a; missing_b < 6; ++missing_b) {
            std::array<bool, 6> present{true, true, true, true, true, true};
            present[missing_a] = false;
            present[missing_b] = false;
            auto [result, shards] = recover_case(present);
            if (present[0] && present[1] && present[2] && present[3]) {
                expect_equal(result.error, FecError::NoRecoveryNeeded,
                             "data-present erasure combination needs no recovery");
            } else {
                expect(result.ok(), "recoverable erasure combination succeeds");
                for (std::size_t i = 0; i < 4; ++i) {
                    expect_equal(shards[i], original[i], "recovered data combination matches");
                }
            }
        }
    }
}

void test_fec_control_payload_and_budget() {
    constexpr FecParityHeader header{
        .target_payload_type = PayloadType::Telemetry,
        .parity_index = 1,
        .base_sequence_number = 0x01020304U,
        .data_shards = 4,
        .parity_shards = 2,
        .shard_size = 0x0039,
    };
    std::array<std::byte, header.shard_size> parity{};
    fill_bytes(parity, 0xA0);
    std::array<std::byte, warpnect::scl::kFecParityHeaderWireSize + header.shard_size> output{};

    expect(warpnect::scl::encode_fec_parity_payload(FecParityView{header, parity}, output).ok(),
           "FEC parity payload encodes");
    constexpr std::array<std::byte, warpnect::scl::kFecParityHeaderWireSize> expected_header{
        byte(0x02), byte(0x01), byte(0x05), byte(0x01), byte(0x01), byte(0x02),
        byte(0x03), byte(0x04), byte(0x04), byte(0x02), byte(0x00), byte(0x39),
        byte(0x00), byte(0x00), byte(0x00), byte(0x00),
    };
    expect(bytes_equal(std::span<const std::byte>(output).first(expected_header.size()),
                       expected_header),
           "FEC parity metadata golden header matches");

    const auto decoded = warpnect::scl::decode_fec_parity_payload(output);
    expect(decoded.ok(), "FEC parity payload decodes");
    expect(decoded.parity.header == header, "decoded FEC parity header matches");
    expect(bytes_equal(decoded.parity.parity, parity), "decoded FEC parity bytes match");

    for (std::size_t size = 0; size < warpnect::scl::kFecParityHeaderWireSize; ++size) {
        expect_equal(
            warpnect::scl::decode_fec_parity_payload(std::span<const std::byte>(output).first(size))
                .error,
            FecError::InvalidParityPayload, "truncated FEC parity payload rejected");
    }

    auto malformed = output;
    malformed[0] = byte(0x01);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::UnsupportedControlType, "wrong FEC control type rejected");
    malformed = output;
    malformed[1] = byte(0x02);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::UnsupportedControlVersion, "wrong FEC version rejected");
    malformed = output;
    malformed[2] = byte(0x00);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidParityPayload, "Unknown FEC target rejected");
    malformed = output;
    malformed[2] = byte(0x06);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidParityPayload, "SessionControl FEC target rejected");
    malformed = output;
    malformed[2] = byte(0xFF);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidParityPayload, "undefined FEC target rejected");
    malformed = output;
    malformed[8] = byte(0x00);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidConfiguration, "zero data shards rejected");
    malformed = output;
    malformed[9] = byte(0x00);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidConfiguration, "zero parity shards rejected");
    malformed = output;
    malformed[8] = byte(200);
    malformed[9] = byte(100);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error, FecError::TooManyShards,
                 "K+M over 255 rejected");
    malformed = output;
    malformed[3] = byte(2);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidParityIndex, "parity index outside range rejected");
    malformed = output;
    malformed[10] = byte(0);
    malformed[11] = byte(1);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidShardSize, "too-small FEC shard size rejected");
    malformed = output;
    malformed[15] = byte(1);
    expect_equal(warpnect::scl::decode_fec_parity_payload(malformed).error,
                 FecError::InvalidParityPayload, "nonzero FEC reserved field rejected");
    expect_equal(warpnect::scl::decode_fec_parity_payload(
                     std::span<const std::byte>(output).first(output.size() - 1U))
                     .error,
                 FecError::InvalidParityPayload, "FEC parity payload size mismatch rejected");

    const auto protected_size = warpnect::scl::fec_max_protected_datagram_size(96);
    expect(protected_size.ok(), "FEC budget calculation succeeds");
    expect_equal(protected_size.size, static_cast<std::size_t>(57),
                 "FEC max protected datagram formula");
    expect_equal(21U + 16U + (protected_size.size + 2U), static_cast<std::size_t>(96),
                 "FEC parity packet fits selected budget");
    expect_equal(warpnect::scl::fec_max_protected_datagram_size(38).error,
                 FecError::DatagramBudgetTooSmall, "too-small FEC budget rejected");
    expect_equal(warpnect::scl::fec_max_protected_datagram_size(
                     warpnect::scl::kUdpMaxDatagramPayloadSize + 1U)
                     .error,
                 FecError::DatagramBudgetTooLarge, "too-large FEC budget rejected");
}

void test_fec_block_encoder_validation() {
    const FecBlockConfig config = fec_config(4, 2);
    PacketSet packets{};
    expect(build_packet_set(packets, config.rs.data_shards, config.base_sequence_number,
                            config.target_payload_type),
           "FEC encoder packets build");
    FecEncoderStorage storage{};
    FecBlockEncoder encoder(config, storage.workspace());

    expect(encoder.accept_data_datagram(packet_at(packets, 0)).ok(),
           "FEC encoder accepts first datagram");
    expect_equal(encoder.accept_data_datagram(packet_at(packets, 0)).error,
                 FecError::DuplicateShard, "identical data duplicate is idempotent");

    PacketSet conflict{};
    expect(encode_test_packet(conflict, 0, config.base_sequence_number, config.target_payload_type,
                              4, 0xEE),
           "conflicting data packet builds");
    expect_equal(encoder.accept_data_datagram(packet_at(conflict, 0)).error,
                 FecError::ConflictingShard, "conflicting data duplicate rejected");

    for (std::uint8_t i = 1; i < config.rs.data_shards; ++i) {
        expect(encoder.accept_data_datagram(packet_at(packets, i)).ok(),
               "FEC encoder accepts remaining datagram");
    }

    const auto first_shard = encoder.systematic_shard(0);
    expect_equal(std::to_integer<std::uint8_t>(first_shard[0]), static_cast<std::uint8_t>(0),
                 "systematic shard length prefix high byte");
    expect_equal(std::to_integer<std::uint8_t>(first_shard[1]),
                 static_cast<std::uint8_t>(packet_at(packets, 0).size()),
                 "systematic shard length prefix low byte");
    expect(bytes_equal(first_shard.subspan(2, packet_at(packets, 0).size()), packet_at(packets, 0)),
           "systematic shard stores exact datagram");
    const auto padding = first_shard.subspan(2 + packet_at(packets, 0).size());
    expect(std::all_of(padding.begin(), padding.end(),
                       [](std::byte value) { return value == std::byte{0}; }),
           "systematic shard padding is zero");

    expect(encoder.encode().ok(), "FEC encoder encodes parity");
    for (std::uint8_t i = 0; i < config.rs.parity_shards; ++i) {
        const auto parity = encoder.parity_view(i);
        expect(parity.ok(), "FEC parity view available");
        expect_equal(parity.parity.header.target_payload_type, config.target_payload_type,
                     "parity target payload type");
        expect_equal(parity.parity.header.base_sequence_number, config.base_sequence_number,
                     "parity protected base sequence");
        expect_equal(parity.parity.header.parity_index, i, "parity index");
        expect_equal(parity.parity.parity.size(), warpnect::scl::fec_shard_size(config).size,
                     "parity shard size");
    }

    const FecBlockConfig three_data = fec_config(3, 1);
    PacketSet wrong_sequence{};
    expect(encode_test_packet(wrong_sequence, 0, 100, PayloadType::Video, 3),
           "wrong-sequence packet 100 builds");
    expect(encode_test_packet(wrong_sequence, 1, 101, PayloadType::Video, 3),
           "wrong-sequence packet 101 builds");
    expect(encode_test_packet(wrong_sequence, 2, 103, PayloadType::Video, 3),
           "wrong-sequence packet 103 builds");
    FecEncoderStorage wrong_sequence_storage{};
    FecBlockEncoder wrong_sequence_encoder(three_data, wrong_sequence_storage.workspace());
    expect(wrong_sequence_encoder.accept_data_datagram(packet_at(wrong_sequence, 0)).ok(),
           "wrong-sequence encoder accepts first");
    expect(wrong_sequence_encoder.accept_data_datagram(packet_at(wrong_sequence, 1)).ok(),
           "wrong-sequence encoder accepts second");
    expect_equal(wrong_sequence_encoder.accept_data_datagram(packet_at(wrong_sequence, 2)).error,
                 FecError::SequenceMismatch, "non-consecutive FEC datagram rejected");

    PacketSet wrong_type{};
    expect(encode_test_packet(wrong_type, 0, 100, PayloadType::Telemetry, 3),
           "wrong payload type packet builds");
    FecEncoderStorage wrong_type_storage{};
    FecBlockEncoder wrong_type_encoder(three_data, wrong_type_storage.workspace());
    expect_equal(wrong_type_encoder.accept_data_datagram(packet_at(wrong_type, 0)).error,
                 FecError::PayloadTypeMismatch, "mixed FEC payload type rejected");
}

void test_fec_recovery_block_and_wraparound() {
    const FecBlockConfig config = fec_config(4, 2);
    PacketSet packets{};
    expect(build_packet_set(packets, config.rs.data_shards, config.base_sequence_number,
                            config.target_payload_type),
           "FEC recovery packets build");
    FecEncoderStorage encoder_storage{};
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    if (!encode_fec_block(config, packets, encoder)) {
        return;
    }

    FecRecoveryStorage recovery_storage{};
    FecRecoveryBlock recovery(recovery_storage.workspace());
    const auto parity_one = encoder.parity_view(1);
    expect(parity_one.ok(), "parity one available");
    expect(recovery.accept_parity(parity_one.parity).ok(), "parity can initialize recovery block");
    expect_equal(recovery.accept_parity(parity_one.parity).error, FecError::DuplicateShard,
                 "identical parity duplicate is idempotent");

    std::array<std::byte, 128> changed_parity_storage{};
    std::copy(parity_one.parity.parity.begin(), parity_one.parity.parity.end(),
              changed_parity_storage.begin());
    changed_parity_storage[0] = byte(static_cast<std::uint8_t>(
        std::to_integer<std::uint8_t>(changed_parity_storage[0]) ^ 0xFFU));
    expect_equal(recovery
                     .accept_parity(FecParityView{parity_one.parity.header,
                                                  std::span<const std::byte>(changed_parity_storage)
                                                      .first(parity_one.parity.parity.size())})
                     .error,
                 FecError::ConflictingShard, "conflicting parity duplicate rejected");

    expect(recovery.accept_data_datagram(config, packet_at(packets, 3)).ok(),
           "recovery accepts data shard 3");
    expect_equal(recovery.accept_data_datagram(config, packet_at(packets, 3)).error,
                 FecError::DuplicateShard, "identical data duplicate is idempotent");

    PacketSet data_conflict{};
    expect(encode_test_packet(data_conflict, 0, config.base_sequence_number + 3U,
                              config.target_payload_type, 5, 0x55),
           "conflicting recovery data builds");
    expect_equal(recovery.accept_data_datagram(config, packet_at(data_conflict, 0)).error,
                 FecError::ConflictingShard, "conflicting data duplicate rejected");

    expect(recovery.accept_data_datagram(config, packet_at(packets, 0)).ok(),
           "recovery accepts data shard 0");
    expect(recovery.accept_parity(encoder.parity_view(0).parity).ok(),
           "recovery accepts parity shard 0");
    expect(recovery.accept_data_datagram(config, packet_at(packets, 2)).ok(),
           "recovery accepts data shard 2");
    expect_equal(recovery.datagram(1).error, FecError::InsufficientShards,
                 "missing recovered datagram hidden before recovery");
    expect(recovery.recover().ok(), "FEC recovery reconstructs missing data");
    const auto recovered = recovery.datagram(1);
    expect(recovered.ok(), "recovered datagram view available");
    expect_equal(recovered.datagram.sequence_number, config.base_sequence_number + 1U,
                 "recovered sequence number");
    expect(bytes_equal(recovered.datagram.datagram, packet_at(packets, 1)),
           "recovered datagram byte-identical to original");

    const FecBlockConfig wrapped = fec_config(4, 2, 0xFFFFFFFEU, PayloadType::Input);
    PacketSet wrapped_packets{};
    expect(build_packet_set(wrapped_packets, wrapped.rs.data_shards, wrapped.base_sequence_number,
                            wrapped.target_payload_type),
           "wrapped FEC packets build");
    FecEncoderStorage wrapped_encoder_storage{};
    FecBlockEncoder wrapped_encoder(wrapped, wrapped_encoder_storage.workspace());
    if (!encode_fec_block(wrapped, wrapped_packets, wrapped_encoder)) {
        return;
    }
    FecRecoveryStorage wrapped_recovery_storage{};
    FecRecoveryBlock wrapped_recovery(wrapped_recovery_storage.workspace());
    expect(wrapped_recovery.accept_data_datagram(wrapped, packet_at(wrapped_packets, 0)).ok(),
           "wrapped recovery accepts first");
    expect(wrapped_recovery.accept_data_datagram(wrapped, packet_at(wrapped_packets, 1)).ok(),
           "wrapped recovery accepts second");
    expect(wrapped_recovery.accept_data_datagram(wrapped, packet_at(wrapped_packets, 3)).ok(),
           "wrapped recovery accepts wrapped fourth");
    expect(wrapped_recovery.accept_parity(wrapped_encoder.parity_view(0).parity).ok(),
           "wrapped recovery accepts parity");
    expect(wrapped_recovery.recover().ok(), "wrapped sequence FEC recovers");
    expect(
        bytes_equal(wrapped_recovery.datagram(2).datagram.datagram, packet_at(wrapped_packets, 2)),
        "wrapped recovered datagram matches sequence zero shard");
}

void test_loss_detector_fec_before_nack() {
    const FecBlockConfig config = fec_config(4, 2);
    PacketSet packets{};
    expect(build_packet_set(packets, config.rs.data_shards, config.base_sequence_number,
                            config.target_payload_type),
           "loss/FEC packets build");
    FecEncoderStorage encoder_storage{};
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    if (!encode_fec_block(config, packets, encoder)) {
        return;
    }

    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                            .renack_interval_us = 20,
                                                            .max_nack_attempts = 2},
                                         slots);
    FecRecoveryStorage recovery_storage{};
    FecRecoveryBlock recovery(recovery_storage.workspace());

    for (std::size_t index : std::array<std::size_t, 3>{0, 2, 3}) {
        const auto decoded = warpnect::scl::decode_packet(packet_at(packets, index));
        expect(decoded.ok(), "loss/FEC decodes delivered data");
        expect(
            detector
                .observe(decoded.packet.header.sequence_number, static_cast<std::uint64_t>(index))
                .ok(),
            "loss/FEC observes delivered sequence");
        expect(recovery.accept_data_datagram(config, packet_at(packets, index)).ok(),
               "loss/FEC recovery accepts delivered data");
    }
    expect_equal(detector.sequence_status(config.base_sequence_number + 1U).state,
                 LossSlotState::Missing, "loss detector sees missing sequence");

    expect(recovery.accept_parity(encoder.parity_view(0).parity).ok(),
           "loss/FEC accepts parity before NACK delay");
    expect(recovery.recover().ok(), "loss/FEC reconstructs missing datagram");
    const auto recovered = recovery.datagram(1);
    expect(recovered.ok(), "loss/FEC recovered datagram available");
    const auto decoded_recovered = warpnect::scl::decode_packet(recovered.datagram.datagram);
    expect(decoded_recovered.ok(), "loss/FEC recovered datagram decodes");
    expect_equal(detector.observe(decoded_recovered.packet.header.sequence_number, 5).kind,
                 LossObservationKind::RecoveredMissing, "FEC recovery clears missing state");

    std::array<NackRequest, 1> nacks{};
    expect_equal(detector.collect_due_nacks(20, config.target_payload_type, nacks).requests_written,
                 static_cast<std::size_t>(0), "FEC before delay prevents NACK");
}

void test_fragmentation_fec_and_nack_fallback() {
    const auto protected_budget = warpnect::scl::fec_max_protected_datagram_size(96);
    expect(protected_budget.ok(), "fragmentation FEC protected budget available");
    const FragmentationConfig fragmentation_config{.max_datagram_size = protected_budget.size};

    std::array<std::byte, 130> logical_payload{};
    fill_bytes(logical_payload, 0x23);
    const auto plan = warpnect::scl::plan_fragments(
        fragmentation_config, data_header(2000, PayloadType::Telemetry), logical_payload);
    expect(plan.ok(), "fragmentation/FEC plan succeeds");
    expect_equal(plan.plan.total_slices, static_cast<std::uint16_t>(4),
                 "fragmentation/FEC produces four data datagrams");

    PacketSet fragments{};
    FragmentCursor cursor(plan.plan);
    while (cursor.has_next()) {
        const auto fragment = cursor.next();
        expect(fragment.ok(), "fragmentation/FEC fragment produced");
        const auto encoded =
            warpnect::scl::encode_packet(fragment.fragment.header, fragment.fragment.payload,
                                         fragments.datagrams[fragments.count]);
        expect(encoded.ok(), "fragmentation/FEC datagram encodes");
        fragments.sizes[fragments.count] = encoded.bytes_written;
        ++fragments.count;
    }

    const FecBlockConfig config = fec_config(4, 1, 2000, PayloadType::Telemetry, 96);
    FecEncoderStorage encoder_storage{};
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    if (!encode_fec_block(config, fragments, encoder)) {
        return;
    }

    FecRecoveryStorage recovery_storage{};
    FecRecoveryBlock recovery(recovery_storage.workspace());
    std::array<std::byte, 160> reassembly_storage{};
    std::array<std::byte, 1> reassembly_bitmap{};
    ReassemblySlot reassembly(fragmentation_config,
                              ReassemblyWorkspace{.payload_storage = reassembly_storage,
                                                  .received_bitmap = reassembly_bitmap});

    for (std::size_t index : std::array<std::size_t, 3>{0, 1, 3}) {
        expect(recovery.accept_data_datagram(config, packet_at(fragments, index)).ok(),
               "fragmentation/FEC accepts delivered data");
        const auto decoded = warpnect::scl::decode_packet(packet_at(fragments, index));
        expect(decoded.ok(), "fragmentation/FEC decodes delivered fragment");
        expect(reassembly.accept(decoded.packet).ok(),
               "fragmentation/FEC reassembly accepts delivered fragment");
    }

    expect(recovery.accept_parity(encoder.parity_view(0).parity).ok(),
           "fragmentation/FEC accepts parity");
    expect(recovery.recover().ok(), "fragmentation/FEC reconstructs dropped fragment");
    const auto recovered = recovery.datagram(2);
    expect(recovered.ok(), "fragmentation/FEC recovered fragment available");
    expect(bytes_equal(recovered.datagram.datagram, packet_at(fragments, 2)),
           "fragmentation/FEC recovered fragment is byte-identical");
    const auto decoded_recovered = warpnect::scl::decode_packet(recovered.datagram.datagram);
    expect(decoded_recovered.ok(), "fragmentation/FEC recovered fragment decodes");
    expect(reassembly.accept(decoded_recovered.packet).ok(),
           "fragmentation/FEC accepts recovered fragment into reassembly");
    expect(bytes_equal(reassembly.result().payload.payload, logical_payload),
           "fragmentation/FEC reassembled payload matches original");

    FecRecoveryStorage fallback_recovery_storage{};
    FecRecoveryBlock fallback_recovery(fallback_recovery_storage.workspace());
    std::array<LossSlot, 16> loss_slots{};
    warpnect::scl::LossDetector detector(
        LossRecoveryConfig{.reorder_delay_us = 0, .renack_interval_us = 10, .max_nack_attempts = 2},
        loss_slots);
    std::array<std::byte, 8 * 96> cache_storage{};
    std::array<RetransmissionEntry, 8> cache_entries{};
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 8, .max_datagram_size = 96},
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = cache_entries});
    for (std::size_t i = 0; i < fragments.count; ++i) {
        expect(cache
                   .store(config.target_payload_type,
                          config.base_sequence_number + static_cast<std::uint32_t>(i),
                          packet_at(fragments, i))
                   .ok(),
               "fallback cache stores original fragment");
    }

    for (std::size_t index : std::array<std::size_t, 2>{0, 3}) {
        expect(fallback_recovery.accept_data_datagram(config, packet_at(fragments, index)).ok(),
               "fallback FEC accepts delivered data");
        const auto decoded = warpnect::scl::decode_packet(packet_at(fragments, index));
        expect(decoded.ok(), "fallback decodes delivered fragment");
        expect(
            detector
                .observe(decoded.packet.header.sequence_number, static_cast<std::uint64_t>(index))
                .ok(),
            "fallback observes delivered fragment");
    }
    expect(fallback_recovery.accept_parity(encoder.parity_view(0).parity).ok(),
           "fallback accepts available parity");
    expect_equal(fallback_recovery.recover().error, FecError::InsufficientShards,
                 "FEC fails cleanly when parity capacity is exceeded");

    std::array<NackRequest, 1> nacks{};
    const auto collected = detector.collect_due_nacks(1, config.target_payload_type, nacks);
    expect(collected.ok(), "fallback NACK collection succeeds");
    expect_equal(collected.requests_written, static_cast<std::size_t>(1),
                 "fallback emits compact NACK for missing fragments");

    std::array<std::byte, 160> fallback_reassembly_storage{};
    std::array<std::byte, 1> fallback_reassembly_bitmap{};
    ReassemblySlot fallback_reassembly(
        fragmentation_config, ReassemblyWorkspace{.payload_storage = fallback_reassembly_storage,
                                                  .received_bitmap = fallback_reassembly_bitmap});
    for (std::size_t index : std::array<std::size_t, 2>{0, 3}) {
        const auto decoded = warpnect::scl::decode_packet(packet_at(fragments, index));
        expect(fallback_reassembly.accept(decoded.packet).ok(),
               "fallback reassembly accepts delivered fragment");
    }

    NackSequenceCursor nack_cursor(nacks[0]);
    while (nack_cursor.has_next()) {
        const auto requested = nack_cursor.next();
        expect(requested.ok(), "fallback NACK cursor yields sequence");
        const auto cached = cache.find(config.target_payload_type, requested.sequence_number);
        expect(cached.ok(), "fallback cache resolves requested fragment");
        const auto decoded = warpnect::scl::decode_packet(cached.datagram);
        expect(decoded.ok(), "fallback cached fragment decodes");
        expect(detector.observe(decoded.packet.header.sequence_number, 2).ok(),
               "fallback recovered sequence observed");
        expect(fallback_reassembly.accept(decoded.packet).ok(),
               "fallback reassembly accepts retransmitted fragment");
    }
    expect(bytes_equal(fallback_reassembly.result().payload.payload, logical_payload),
           "fallback retransmission completes reassembly");
}

[[nodiscard]] bool encode_parity_packet(const FecParityView& parity, std::uint32_t control_sequence,
                                        std::span<std::byte> output,
                                        std::size_t& bytes_written) noexcept {
    std::array<std::byte, 128> parity_payload{};
    const auto payload_status = warpnect::scl::encode_fec_parity_payload(parity, parity_payload);
    expect(payload_status.ok(), "UDP FEC parity payload encodes");
    if (!payload_status.ok()) {
        return false;
    }

    const auto payload_size = warpnect::scl::fec_parity_payload_size(parity.header);
    expect(payload_size.ok(), "UDP FEC parity payload size available");
    if (!payload_size.ok()) {
        return false;
    }

    const PacketHeader header =
        data_header(control_sequence, PayloadType::SessionControl, 0xABCDEFU, 0);
    const auto packet = warpnect::scl::encode_packet(
        header, std::span<const std::byte>(parity_payload).first(payload_size.size), output);
    expect(packet.ok(), "UDP FEC parity SCL packet encodes");
    if (!packet.ok()) {
        return false;
    }

    bytes_written = packet.bytes_written;
    return true;
}

void test_udp_fec_success() {
    UdpSocket sender;
    UdpSocket receiver;
    UdpEndpoint sender_endpoint{};
    UdpEndpoint receiver_endpoint{};
    if (!open_bound_socket_v4(sender, sender_endpoint) ||
        !open_bound_socket_v4(receiver, receiver_endpoint)) {
        return;
    }

    const FecBlockConfig config = fec_config(4, 2, 3000, PayloadType::Input, 96);
    PacketSet packets{};
    expect(build_packet_set(packets, config.rs.data_shards, config.base_sequence_number,
                            config.target_payload_type),
           "UDP FEC source packets build");
    FecEncoderStorage encoder_storage{};
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    if (!encode_fec_block(config, packets, encoder)) {
        return;
    }

    std::array<std::byte, 128> parity_packet{};
    std::size_t parity_packet_size = 0;
    expect(encode_parity_packet(encoder.parity_view(0).parity, 9000, parity_packet,
                                parity_packet_size),
           "UDP FEC parity packet builds");

    for (std::size_t index : std::array<std::size_t, 3>{0, 1, 3}) {
        expect(sender.send_to(packet_at(packets, index), receiver_endpoint).ok(),
               "UDP FEC sends delivered data");
    }
    expect(sender
               .send_to(std::span<const std::byte>(parity_packet).first(parity_packet_size),
                        receiver_endpoint)
               .ok(),
           "UDP FEC sends parity");

    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                            .renack_interval_us = 20,
                                                            .max_nack_attempts = 2},
                                         slots);
    FecRecoveryStorage recovery_storage{};
    FecRecoveryBlock recovery(recovery_storage.workspace());
    std::array<std::byte, 128> receive_buffer{};

    for (std::size_t i = 0; i < 4; ++i) {
        const auto received = receive_until_ready(receiver, receive_buffer);
        expect(received.ok(), "UDP FEC receiver gets datagram");
        const auto packet = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received.bytes_received));
        expect(packet.ok(), "UDP FEC received datagram decodes");
        if (packet.packet.header.payload_type == PayloadType::SessionControl) {
            const auto parity = warpnect::scl::decode_fec_parity_payload(packet.packet.payload);
            expect(parity.ok(), "UDP FEC parity payload decodes");
            expect(recovery.accept_parity(parity.parity).ok(), "UDP FEC recovery accepts parity");
        } else {
            expect(detector.observe(packet.packet.header.sequence_number, i).ok(),
                   "UDP FEC observes data sequence");
            expect(recovery
                       .accept_data_datagram(config, std::span<const std::byte>(receive_buffer)
                                                         .first(received.bytes_received))
                       .ok(),
                   "UDP FEC recovery accepts data");
        }
    }

    expect_equal(detector.sequence_status(config.base_sequence_number + 2U).state,
                 LossSlotState::Missing, "UDP FEC missing data is tracked");
    expect(recovery.recover().ok(), "UDP FEC reconstructs missing data");
    const auto recovered = recovery.datagram(2);
    expect(recovered.ok(), "UDP FEC recovered datagram available");
    expect(bytes_equal(recovered.datagram.datagram, packet_at(packets, 2)),
           "UDP FEC recovered datagram is exact original");
    const auto decoded_recovered = warpnect::scl::decode_packet(recovered.datagram.datagram);
    expect(decoded_recovered.ok(), "UDP FEC recovered packet decodes");
    expect_equal(detector.observe(decoded_recovered.packet.header.sequence_number, 5).kind,
                 LossObservationKind::RecoveredMissing, "UDP FEC recovery clears missing state");
    std::array<NackRequest, 1> nacks{};
    expect_equal(detector.collect_due_nacks(20, config.target_payload_type, nacks).requests_written,
                 static_cast<std::size_t>(0), "UDP FEC success requires no NACK");
}

void test_udp_nack_fallback_after_fec_failure() {
    UdpSocket sender;
    UdpSocket receiver;
    UdpEndpoint sender_endpoint{};
    UdpEndpoint receiver_endpoint{};
    if (!open_bound_socket_v4(sender, sender_endpoint) ||
        !open_bound_socket_v4(receiver, receiver_endpoint)) {
        return;
    }

    const FecBlockConfig config = fec_config(4, 1, 4000, PayloadType::Telemetry, 96);
    PacketSet packets{};
    expect(build_packet_set(packets, config.rs.data_shards, config.base_sequence_number,
                            config.target_payload_type),
           "UDP fallback source packets build");
    FecEncoderStorage encoder_storage{};
    FecBlockEncoder encoder(config, encoder_storage.workspace());
    if (!encode_fec_block(config, packets, encoder)) {
        return;
    }

    std::array<std::byte, 8 * 96> cache_storage{};
    std::array<RetransmissionEntry, 8> cache_entries{};
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 8, .max_datagram_size = 96},
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = cache_entries});
    for (std::size_t i = 0; i < config.rs.data_shards; ++i) {
        expect(cache
                   .store(config.target_payload_type,
                          config.base_sequence_number + static_cast<std::uint32_t>(i),
                          packet_at(packets, i))
                   .ok(),
               "UDP fallback caches original datagram");
    }

    std::array<std::byte, 128> parity_packet{};
    std::size_t parity_packet_size = 0;
    expect(encode_parity_packet(encoder.parity_view(0).parity, 9100, parity_packet,
                                parity_packet_size),
           "UDP fallback parity packet builds");

    for (std::size_t index : std::array<std::size_t, 2>{0, 3}) {
        expect(sender.send_to(packet_at(packets, index), receiver_endpoint).ok(),
               "UDP fallback sends delivered data");
    }
    expect(sender
               .send_to(std::span<const std::byte>(parity_packet).first(parity_packet_size),
                        receiver_endpoint)
               .ok(),
           "UDP fallback sends insufficient parity");

    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(
        LossRecoveryConfig{.reorder_delay_us = 0, .renack_interval_us = 10, .max_nack_attempts = 2},
        slots);
    FecRecoveryStorage recovery_storage{};
    FecRecoveryBlock recovery(recovery_storage.workspace());
    std::array<std::byte, 128> receive_buffer{};

    for (std::size_t i = 0; i < 3; ++i) {
        const auto received = receive_until_ready(receiver, receive_buffer);
        expect(received.ok(), "UDP fallback receiver gets datagram");
        const auto packet = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received.bytes_received));
        expect(packet.ok(), "UDP fallback received datagram decodes");
        if (packet.packet.header.payload_type == PayloadType::SessionControl) {
            const auto parity = warpnect::scl::decode_fec_parity_payload(packet.packet.payload);
            expect(parity.ok(), "UDP fallback parity payload decodes");
            expect(recovery.accept_parity(parity.parity).ok(),
                   "UDP fallback recovery accepts parity");
        } else {
            expect(detector.observe(packet.packet.header.sequence_number, i).ok(),
                   "UDP fallback observes data sequence");
            expect(recovery
                       .accept_data_datagram(config, std::span<const std::byte>(receive_buffer)
                                                         .first(received.bytes_received))
                       .ok(),
                   "UDP fallback recovery accepts data");
        }
    }

    expect_equal(recovery.recover().error, FecError::InsufficientShards,
                 "UDP fallback FEC reports insufficient shards");

    std::array<NackRequest, 1> requests{};
    const auto collected = detector.collect_due_nacks(1, config.target_payload_type, requests);
    expect(collected.ok(), "UDP fallback NACK generated");
    expect_equal(collected.requests_written, static_cast<std::size_t>(1),
                 "UDP fallback emits one compact NACK");

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_payload{};
    expect(warpnect::scl::encode_nack(requests[0], nack_payload).ok(),
           "UDP fallback NACK payload encodes");
    std::array<std::byte, 96> nack_packet{};
    const auto encoded_nack = warpnect::scl::encode_packet(
        data_header(9200, PayloadType::SessionControl, 0x1010, 0), nack_payload, nack_packet);
    expect(encoded_nack.ok(), "UDP fallback NACK SCL packet encodes");
    expect(receiver
               .send_to(std::span<const std::byte>(nack_packet).first(encoded_nack.bytes_written),
                        sender_endpoint)
               .ok(),
           "UDP fallback sends reverse NACK");

    std::array<std::byte, 96> sender_receive_buffer{};
    const auto received_nack = receive_until_ready(sender, sender_receive_buffer);
    expect(received_nack.ok(), "UDP fallback sender receives NACK");
    const auto nack_packet_view = warpnect::scl::decode_packet(
        std::span<const std::byte>(sender_receive_buffer).first(received_nack.bytes_received));
    expect(nack_packet_view.ok(), "UDP fallback sender decodes NACK packet");
    const auto decoded_nack = warpnect::scl::decode_nack(nack_packet_view.packet.payload);
    expect(decoded_nack.ok(), "UDP fallback sender decodes NACK payload");

    NackSequenceCursor requested(decoded_nack.request);
    while (requested.has_next()) {
        const auto sequence = requested.next();
        expect(sequence.ok(), "UDP fallback requested sequence produced");
        const auto cached =
            cache.find(decoded_nack.request.target_payload_type, sequence.sequence_number);
        expect(cached.ok(), "UDP fallback cache resolves requested datagram");
        expect(sender.send_to(cached.datagram, receiver_endpoint).ok(),
               "UDP fallback sends cached retransmission");
    }

    for (std::size_t i = 0; i < 2; ++i) {
        const auto received = receive_until_ready(receiver, receive_buffer);
        expect(received.ok(), "UDP fallback receiver gets retransmission");
        const auto packet = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received.bytes_received));
        expect(packet.ok(), "UDP fallback retransmission decodes");
        const auto observation = detector.observe(packet.packet.header.sequence_number, 2);
        expect_equal(observation.kind, LossObservationKind::RecoveredMissing,
                     "UDP fallback retransmission clears missing state");
    }
    expect(!detector.has_missing(), "UDP fallback missing state fully cleared");

    std::array<NackRequest, 1> post_recovery{};
    expect_equal(
        detector.collect_due_nacks(20, config.target_payload_type, post_recovery).requests_written,
        static_cast<std::size_t>(0), "UDP fallback has no NACK after retransmission");
}

void test_deterministic_property_style_fec() {
    for (std::uint32_t scenario = 0; scenario < 18U; ++scenario) {
        const std::uint8_t data_count = static_cast<std::uint8_t>(3U + (scenario % 3U));
        const std::uint8_t parity_count = static_cast<std::uint8_t>(1U + (scenario % 2U));
        const std::uint32_t base = 0xFFFFFFF0U + scenario * 11U;
        const PayloadType type = scenario % 2U == 0 ? PayloadType::Video : PayloadType::Input;
        const FecBlockConfig config = fec_config(data_count, parity_count, base, type, 96);
        PacketSet packets{};
        expect(build_packet_set(packets, data_count, base, type), "property FEC packets build");
        FecEncoderStorage encoder_storage{};
        FecBlockEncoder encoder(config, encoder_storage.workspace());
        if (!encode_fec_block(config, packets, encoder)) {
            return;
        }

        FecRecoveryStorage recovery_storage{};
        FecRecoveryBlock recovery(recovery_storage.workspace());
        const std::uint8_t missing_index =
            static_cast<std::uint8_t>((scenario * 2U + 1U) % data_count);
        for (std::uint8_t i = 0; i < data_count; ++i) {
            if (i == missing_index) {
                continue;
            }
            expect(recovery.accept_data_datagram(config, packet_at(packets, i)).ok(),
                   "property FEC accepts present data");
        }
        expect(recovery.accept_parity(encoder.parity_view(0).parity).ok(),
               "property FEC accepts parity");
        expect(recovery.recover().ok(), "property FEC recovers missing data");
        expect(bytes_equal(recovery.datagram(missing_index).datagram.datagram,
                           packet_at(packets, missing_index)),
               "property FEC recovered datagram matches original");
    }
}

} // namespace

int main() {
    test_gf_arithmetic_and_tables();
    test_matrix_and_systematic_rows();
    test_reed_solomon_encode_and_recover();
    test_fec_control_payload_and_budget();
    test_fec_block_encoder_validation();
    test_fec_recovery_block_and_wraparound();
    test_loss_detector_fec_before_nack();
    test_fragmentation_fec_and_nack_fallback();
    test_udp_fec_success();
    test_udp_nack_fallback_after_fec_failure();
    test_deterministic_property_style_fec();

    if (failures != 0) {
        std::cerr << failures << " SCL FEC test failure(s)\n";
        return 1;
    }

    std::cout << "SCL FEC tests passed\n";
    return 0;
}
