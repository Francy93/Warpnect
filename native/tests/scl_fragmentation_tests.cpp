#include "fragmentation.h"
#include "packet_codec.h"
#include "reassembly.h"
#include "udp_socket.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>

namespace {

using warpnect::scl::FragmentationConfig;
using warpnect::scl::FragmentCursor;
using warpnect::scl::FragmentError;
using warpnect::scl::FragmentGroupKey;
using warpnect::scl::FragmentView;
using warpnect::scl::IpAddress;
using warpnect::scl::IpVersion;
using warpnect::scl::PacketHeader;
using warpnect::scl::PacketView;
using warpnect::scl::PayloadType;
using warpnect::scl::ReassemblySlot;
using warpnect::scl::ReassemblyWorkspace;
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

[[nodiscard]] constexpr FragmentationConfig budget_for_capacity(std::size_t capacity) noexcept {
    return FragmentationConfig{.max_datagram_size =
                                   warpnect::scl::kPacketHeaderWireSize + capacity};
}

[[nodiscard]] constexpr PacketHeader
logical_header(std::uint32_t sequence = 1000,
               PayloadType payload_type = PayloadType::Telemetry) noexcept {
    return PacketHeader{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0x00A5,
        .sequence_number = sequence,
        .timestamp_us = 0x0102030405060708ULL,
        .payload_type = payload_type,
        .slice_index = 0,
        .total_slices = 1,
    };
}

[[nodiscard]] PacketView as_packet_view(const FragmentView& fragment) noexcept {
    return PacketView{.header = fragment.header, .payload = fragment.payload};
}

template <std::size_t N> void fill_payload(std::array<std::byte, N>& payload) noexcept {
    for (std::size_t i = 0; i < payload.size(); ++i) {
        payload[i] = byte(static_cast<std::uint8_t>((i * 37U + 11U) & 0xFFU));
    }
}

[[nodiscard]] bool payload_matches(std::span<const std::byte> actual,
                                   std::span<const std::byte> expected) noexcept {
    if (actual.size() != expected.size()) {
        return false;
    }

    for (std::size_t i = 0; i < actual.size(); ++i) {
        if (actual[i] != expected[i]) {
            return false;
        }
    }

    return true;
}

template <std::size_t FragmentCount, std::size_t PayloadSize>
bool collect_fragments(std::array<FragmentView, FragmentCount>& fragments,
                       FragmentationConfig config,
                       const std::array<std::byte, PayloadSize>& payload,
                       PacketHeader header = logical_header()) noexcept {
    const auto plan = warpnect::scl::plan_fragments(config, header, payload);
    expect(plan.ok(), "fragmentation plan succeeds");
    if (!plan.ok()) {
        return false;
    }

    expect_equal(plan.plan.total_slices, static_cast<std::uint16_t>(FragmentCount),
                 "fragment count matches expected storage");

    FragmentCursor cursor(plan.plan);
    for (std::size_t i = 0; i < FragmentCount; ++i) {
        const auto fragment = cursor.next();
        expect(fragment.ok(), "fragment cursor returns fragment");
        if (!fragment.ok()) {
            return false;
        }
        fragments[i] = fragment.fragment;
    }

    expect(!cursor.has_next(), "fragment cursor exhausted");
    expect_equal(cursor.next().error, FragmentError::NoMoreFragments,
                 "cursor reports no more fragments");
    return true;
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

[[nodiscard]] bool open_bound_receiver_v4(UdpSocket& receiver,
                                          UdpEndpoint& local_endpoint) noexcept {
    const auto open = receiver.open(IpVersion::V4);
    expect(open.ok(), "IPv4 receiver opens");
    if (!open.ok()) {
        return false;
    }

    const auto bind = receiver.bind(UdpEndpoint::loopback_v4(0));
    expect(bind.ok(), "IPv4 receiver binds");
    if (!bind.ok()) {
        return false;
    }

    const auto local = receiver.local_endpoint();
    expect(local.ok(), "IPv4 receiver local endpoint");
    if (!local.ok()) {
        return false;
    }

    expect(local.endpoint.address == IpAddress::loopback_v4(), "IPv4 receiver bound loopback");
    expect(local.endpoint.port != 0, "IPv4 receiver ephemeral port assigned");
    local_endpoint = local.endpoint;
    return true;
}

void test_fragment_counts() {
    constexpr std::size_t capacity = 5;
    constexpr FragmentationConfig config = budget_for_capacity(capacity);
    constexpr std::array<std::size_t, 7> payload_sizes{0, 1, 4, 5, 6, 10, 11};
    constexpr std::array<std::uint16_t, 7> expected_counts{1, 1, 1, 1, 2, 2, 3};

    std::array<std::byte, 11> payload{};
    fill_payload(payload);

    for (std::size_t i = 0; i < payload_sizes.size(); ++i) {
        const std::size_t payload_size = payload_sizes[i];
        const auto count = warpnect::scl::calculate_fragment_count(config, payload_size);
        expect(count.ok(), "fragment count calculation succeeds");
        expect_equal(count.fragment_payload_capacity, capacity, "fragment capacity");
        expect_equal(count.total_slices, expected_counts[i], "calculated total slices");

        const auto plan = warpnect::scl::plan_fragments(
            config, logical_header(), std::span<const std::byte>(payload).first(payload_size));
        expect(plan.ok(), "fragment plan for count case succeeds");

        FragmentCursor cursor(plan.plan);
        for (std::uint16_t slice = 0; slice < expected_counts[i]; ++slice) {
            const auto fragment = cursor.next();
            expect(fragment.ok(), "fragment produced for count case");
            expect_equal(fragment.fragment.header.slice_index, slice, "slice index");
            expect_equal(fragment.fragment.header.total_slices, expected_counts[i], "slice total");
            expect_equal(fragment.fragment.header.sequence_number,
                         static_cast<std::uint32_t>(logical_header().sequence_number + slice),
                         "slice sequence");

            const bool final_slice = slice == expected_counts[i] - 1U;
            const std::size_t expected_size =
                payload_size == 0 ? 0
                                  : (final_slice ? payload_size - (capacity * slice) : capacity);
            expect_equal(fragment.fragment.payload.size(), expected_size, "slice payload size");
        }
    }
}

void test_single_and_multi_slice_metadata() {
    constexpr FragmentationConfig config = budget_for_capacity(8);
    std::array<std::byte, 20> payload{};
    fill_payload(payload);

    const auto single_plan =
        warpnect::scl::plan_fragments(config, logical_header(77, PayloadType::Input),
                                      std::span<const std::byte>(payload).first(8));
    expect(single_plan.ok(), "single-slice plan succeeds");
    FragmentCursor single_cursor(single_plan.plan);
    const auto single = single_cursor.next();
    expect(single.ok(), "single-slice fragment produced");
    expect_equal(single.fragment.header.sequence_number, static_cast<std::uint32_t>(77),
                 "single-slice sequence");
    expect_equal(single.fragment.header.slice_index, static_cast<std::uint16_t>(0),
                 "single-slice index");
    expect_equal(single.fragment.header.total_slices, static_cast<std::uint16_t>(1),
                 "single-slice total");
    expect_equal(single.fragment.header.flags, static_cast<std::uint16_t>(0x00A5),
                 "single-slice flags preserved");
    expect_equal(single.fragment.header.payload_type, PayloadType::Input,
                 "single-slice type preserved");

    std::array<FragmentView, 3> fragments{};
    expect(collect_fragments(fragments, config, payload), "multi-slice fragments collected");

    std::size_t covered = 0;
    for (std::size_t i = 0; i < fragments.size(); ++i) {
        expect_equal(fragments[i].header.slice_index, static_cast<std::uint16_t>(i),
                     "multi-slice index");
        expect_equal(fragments[i].header.sequence_number,
                     static_cast<std::uint32_t>(logical_header().sequence_number + i),
                     "multi-slice sequence");
        expect(fragments[i].payload.data() == payload.data() + covered,
               "fragment view points into original payload");
        covered += fragments[i].payload.size();
    }
    expect_equal(covered, payload.size(), "multi-slice fragments cover full payload");
}

void test_sequence_wrap_and_encoding() {
    constexpr FragmentationConfig config = budget_for_capacity(1);
    constexpr PacketHeader header = logical_header(0xFFFFFFFEU, PayloadType::Video);
    constexpr std::array<std::byte, 4> payload{byte(1), byte(2), byte(3), byte(4)};
    std::array<FragmentView, 4> fragments{};
    expect(collect_fragments(fragments, config, payload, header), "wrap fragments collected");

    constexpr std::array<std::uint32_t, 4> expected_sequences{
        0xFFFFFFFEU,
        0xFFFFFFFFU,
        0x00000000U,
        0x00000001U,
    };

    for (std::size_t i = 0; i < fragments.size(); ++i) {
        expect_equal(fragments[i].header.sequence_number, expected_sequences[i],
                     "wrapped sequence");
        expect_equal(warpnect::scl::fragment_base_sequence_number(
                         fragments[i].header.sequence_number, fragments[i].header.slice_index),
                     header.sequence_number, "derived base sequence after wrap");

        std::array<std::byte, warpnect::scl::kPacketHeaderWireSize + 1> datagram{};
        const auto encoded =
            warpnect::scl::encode_packet(fragments[i].header, fragments[i].payload, datagram);
        expect(encoded.ok(), "wrapped fragment encodes");
        const auto decoded = warpnect::scl::decode_packet(
            std::span<const std::byte>(datagram).first(encoded.bytes_written));
        expect(decoded.ok(), "wrapped fragment decodes");
        expect(decoded.packet.header == fragments[i].header, "decoded wrapped header");
        expect(payload_matches(decoded.packet.payload, fragments[i].payload),
               "decoded wrapped payload");
    }
}

template <std::size_t FragmentCount, std::size_t PayloadSize, std::size_t OrderCount>
void reassemble_in_order(std::array<FragmentView, FragmentCount>& fragments,
                         const std::array<std::byte, PayloadSize>& payload,
                         const std::array<std::uint16_t, OrderCount>& order,
                         FragmentationConfig config, std::string_view label) {
    std::array<std::byte, 128> storage{};
    std::array<std::byte, 8> metadata{};
    ReassemblySlot slot(
        config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});

    expect_equal(slot.result().error, FragmentError::ReassemblyNotStarted, "result before start");

    for (std::size_t i = 0; i < order.size(); ++i) {
        const auto result = slot.accept(as_packet_view(fragments[order[i]]));
        expect(result.ok(), "reassembly accepts fragment");
        if (i + 1U < order.size()) {
            expect(!result.complete, "reassembly incomplete before all fragments");
            expect_equal(slot.result().error, FragmentError::ReassemblyIncomplete,
                         "incomplete result hidden");
        }
    }

    expect(slot.is_complete(), "reassembly complete");
    const auto complete = slot.result();
    expect(complete.ok(), "complete result available");
    expect(payload_matches(complete.payload.payload, payload), label);
    expect_equal(complete.payload.group,
                 FragmentGroupKey{
                     .protocol_version = fragments[0].header.protocol_version,
                     .base_sequence_number = fragments[0].header.sequence_number,
                     .timestamp_us = fragments[0].header.timestamp_us,
                     .payload_type = fragments[0].header.payload_type,
                     .flags = fragments[0].header.flags,
                     .total_slices = fragments[0].header.total_slices,
                 },
                 "group key matches logical payload");
}

void test_reassembly_orders() {
    constexpr FragmentationConfig config = budget_for_capacity(5);
    std::array<std::byte, 18> payload{};
    fill_payload(payload);
    std::array<FragmentView, 4> fragments{};
    expect(collect_fragments(fragments, config, payload), "reassembly fragments collected");

    reassemble_in_order(fragments, payload, std::array<std::uint16_t, 4>{0, 1, 2, 3}, config,
                        "in-order payload reconstructed");
    reassemble_in_order(fragments, payload, std::array<std::uint16_t, 4>{3, 2, 1, 0}, config,
                        "reverse-order payload reconstructed");
    reassemble_in_order(fragments, payload, std::array<std::uint16_t, 4>{3, 0, 2, 1}, config,
                        "arbitrary-order payload reconstructed");
}

void test_final_slice_first_with_exact_storage() {
    constexpr FragmentationConfig config = budget_for_capacity(4);
    std::array<std::byte, 10> payload{};
    fill_payload(payload);
    std::array<FragmentView, 3> fragments{};
    expect(collect_fragments(fragments, config, payload), "final-first fragments collected");

    std::array<std::byte, 10> exact_storage{};
    std::array<std::byte, 1> metadata{};
    ReassemblySlot slot(
        config, ReassemblyWorkspace{.payload_storage = exact_storage, .received_bitmap = metadata});

    const auto final_first = slot.accept(as_packet_view(fragments[2]));
    expect(final_first.ok(), "final slice accepted first");
    expect(!final_first.complete, "final slice first remains incomplete");
    expect_equal(slot.result().error, FragmentError::ReassemblyIncomplete,
                 "final-first result incomplete");

    expect(slot.accept(as_packet_view(fragments[0])).ok(), "first non-final accepted");
    const auto complete = slot.accept(as_packet_view(fragments[1]));
    expect(complete.ok(), "remaining fragment accepted");
    expect(complete.complete, "final-first assembly completes");
    expect(payload_matches(slot.result().payload.payload, payload),
           "final-first payload reconstructed");
}

void test_duplicate_and_conflicting_duplicate() {
    constexpr FragmentationConfig config = budget_for_capacity(4);
    std::array<std::byte, 9> payload{};
    fill_payload(payload);
    std::array<FragmentView, 3> fragments{};
    expect(collect_fragments(fragments, config, payload), "duplicate fragments collected");

    std::array<std::byte, 12> storage{};
    std::array<std::byte, 1> metadata{};
    ReassemblySlot slot(
        config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});

    expect(slot.accept(as_packet_view(fragments[0])).ok(), "first fragment accepted");
    const auto duplicate = slot.accept(as_packet_view(fragments[0]));
    expect_equal(duplicate.error, FragmentError::DuplicateFragment, "duplicate is reported");
    expect_equal(slot.received_fragment_count(), static_cast<std::uint16_t>(1),
                 "duplicate does not change count");

    std::array<std::byte, 4> modified{};
    for (std::size_t i = 0; i < modified.size(); ++i) {
        modified[i] = fragments[0].payload[i];
    }
    modified[1] = byte(0xEE);
    FragmentView conflict = fragments[0];
    conflict.payload = modified;
    const auto conflicting = slot.accept(as_packet_view(conflict));
    expect_equal(conflicting.error, FragmentError::ConflictingFragment,
                 "conflicting duplicate rejected");

    expect(slot.accept(as_packet_view(fragments[1])).ok(), "second fragment accepted");
    expect(slot.accept(as_packet_view(fragments[2])).ok(), "third fragment accepted");
    expect(payload_matches(slot.result().payload.payload, payload),
           "conflicting duplicate did not overwrite original");
}

void test_group_mismatches() {
    constexpr FragmentationConfig config = budget_for_capacity(4);
    std::array<std::byte, 8> payload{};
    fill_payload(payload);
    std::array<FragmentView, 2> fragments{};
    expect(collect_fragments(fragments, config, payload), "group mismatch fragments collected");

    constexpr std::array<int, 5> mismatch_cases{0, 1, 2, 3, 4};
    for (int mismatch : mismatch_cases) {
        std::array<std::byte, 8> storage{};
        std::array<std::byte, 1> metadata{};
        ReassemblySlot slot(
            config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});
        expect(slot.accept(as_packet_view(fragments[0])).ok(), "active group starts");

        FragmentView other = fragments[1];
        switch (mismatch) {
        case 0:
            other.header.sequence_number += 11U;
            break;
        case 1:
            other.header.timestamp_us += 1U;
            break;
        case 2:
            other.header.payload_type = PayloadType::Input;
            break;
        case 3:
            other.header.flags ^= 0x0001U;
            break;
        case 4:
            other.header.total_slices = 3;
            break;
        default:
            break;
        }

        expect_equal(slot.accept(as_packet_view(other)).error, FragmentError::FragmentGroupMismatch,
                     "mismatched group rejected");
        expect(slot.accept(as_packet_view(fragments[1])).ok(),
               "original group still completes after mismatch");
        expect(payload_matches(slot.result().payload.payload, payload),
               "mismatch did not corrupt active group");
    }
}

void test_invalid_slice_and_size_validation() {
    constexpr FragmentationConfig config = budget_for_capacity(4);
    std::array<std::byte, 5> payload{};
    fill_payload(payload);

    std::array<std::byte, 16> storage{};
    std::array<std::byte, 2> metadata{};

    PacketHeader invalid = logical_header();
    invalid.total_slices = 0;
    ReassemblySlot invalid_slot(
        config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});
    expect_equal(invalid_slot.accept(PacketView{.header = invalid, .payload = {}}).error,
                 FragmentError::InvalidFragment, "zero total_slices rejected");

    invalid = logical_header();
    invalid.slice_index = 1;
    invalid.total_slices = 1;
    expect_equal(invalid_slot.accept(PacketView{.header = invalid, .payload = {}}).error,
                 FragmentError::InvalidFragment, "slice equal total rejected");

    PacketHeader non_final = logical_header();
    non_final.slice_index = 0;
    non_final.total_slices = 2;
    expect_equal(invalid_slot
                     .accept(PacketView{.header = non_final,
                                        .payload = std::span<const std::byte>(payload).first(3)})
                     .error,
                 FragmentError::FragmentSizeMismatch, "short non-final rejected");

    PacketHeader final = logical_header();
    final.sequence_number += 1U;
    final.slice_index = 1;
    final.total_slices = 2;
    expect_equal(invalid_slot.accept(PacketView{.header = final, .payload = payload}).error,
                 FragmentError::FragmentSizeMismatch, "oversized final rejected");

    expect_equal(invalid_slot.accept(PacketView{.header = final, .payload = {}}).error,
                 FragmentError::FragmentSizeMismatch, "zero final in multi-slice rejected");

    std::array<std::byte, 6> exact_storage{};
    std::array<std::byte, 1> exact_metadata{};
    ReassemblySlot valid_final_slot(config, ReassemblyWorkspace{.payload_storage = exact_storage,
                                                                .received_bitmap = exact_metadata});
    expect(valid_final_slot
               .accept(PacketView{.header = final,
                                  .payload = std::span<const std::byte>(payload).first(2)})
               .ok(),
           "valid smaller final accepted");
    expect(valid_final_slot
               .accept(PacketView{.header = non_final,
                                  .payload = std::span<const std::byte>(payload).first(4)})
               .ok(),
           "matching non-final completes smaller-final assembly");
}

void test_workspace_validation_and_reset() {
    constexpr FragmentationConfig config = budget_for_capacity(4);
    std::array<std::byte, 9> payload{};
    fill_payload(payload);
    std::array<FragmentView, 3> fragments{};
    expect(collect_fragments(fragments, config, payload), "workspace fragments collected");

    std::array<std::byte, 11> guarded_storage{};
    guarded_storage.fill(byte(0x5A));
    std::array<std::byte, 1> metadata{};
    ReassemblySlot small_storage_slot(
        config,
        ReassemblyWorkspace{.payload_storage = std::span<std::byte>(guarded_storage).first(8),
                            .received_bitmap = metadata});
    expect_equal(small_storage_slot.accept(as_packet_view(fragments[0])).error,
                 FragmentError::ReassemblyStorageTooSmall, "small payload storage rejected");
    expect_equal(guarded_storage[8], byte(0x5A), "payload guard preserved");

    std::array<std::byte, 12> storage{};
    std::array<std::byte, 0> no_metadata{};
    ReassemblySlot small_metadata_slot(
        config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = no_metadata});
    expect_equal(small_metadata_slot.accept(as_packet_view(fragments[0])).error,
                 FragmentError::MetadataStorageTooSmall, "small metadata storage rejected");

    std::array<std::byte, 12> reusable_storage{};
    std::array<std::byte, 1> reusable_metadata{};
    ReassemblySlot slot(config, ReassemblyWorkspace{.payload_storage = reusable_storage,
                                                    .received_bitmap = reusable_metadata});
    for (const FragmentView& fragment : fragments) {
        expect(slot.accept(as_packet_view(fragment)).error == FragmentError::None,
               "first reusable assembly accepts fragment");
    }
    expect(slot.is_complete(), "first reusable assembly complete");
    slot.reset();
    expect(!slot.is_complete(), "reset clears completion");
    expect(!slot.is_started(), "reset clears started state");

    PacketHeader second_header = logical_header(9000, PayloadType::Input);
    std::array<FragmentView, 3> second_fragments{};
    expect(collect_fragments(second_fragments, config, payload, second_header),
           "second reusable fragments collected");
    for (const FragmentView& fragment : second_fragments) {
        expect(slot.accept(as_packet_view(fragment)).error == FragmentError::None,
               "second reusable assembly accepts fragment");
    }
    expect(slot.result().payload.group.payload_type == PayloadType::Input,
           "reset slot exposes second group");
}

void test_empty_payload_and_math_helpers() {
    constexpr FragmentationConfig empty_config =
        FragmentationConfig{.max_datagram_size = warpnect::scl::kPacketHeaderWireSize};
    constexpr std::array<std::byte, 0> empty_payload{};

    const auto empty_plan =
        warpnect::scl::plan_fragments(empty_config, logical_header(), empty_payload);
    expect(empty_plan.ok(), "empty payload can use header-only budget");
    FragmentCursor cursor(empty_plan.plan);
    const auto fragment = cursor.next();
    expect(fragment.ok(), "empty payload fragment produced");
    expect_equal(fragment.fragment.header.total_slices, static_cast<std::uint16_t>(1),
                 "empty payload has one slice");
    expect_equal(fragment.fragment.payload.size(), static_cast<std::size_t>(0),
                 "empty fragment payload size");

    std::array<std::byte, 0> storage{};
    std::array<std::byte, 1> metadata{};
    ReassemblySlot slot(
        empty_config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});
    const auto accepted = slot.accept(as_packet_view(fragment.fragment));
    expect(accepted.ok(), "empty fragment accepted");
    expect(accepted.complete, "empty payload completes");
    expect_equal(slot.result().payload.payload.size(), static_cast<std::size_t>(0),
                 "empty reassembled payload size");

    const auto max_count = warpnect::scl::calculate_fragment_count(budget_for_capacity(1), 65535);
    expect(max_count.ok(), "65535 one-byte fragments are representable");
    expect_equal(max_count.total_slices, std::numeric_limits<std::uint16_t>::max(),
                 "maximum total_slices calculated");
    expect_equal(warpnect::scl::fragment_base_sequence_number(
                     warpnect::scl::fragment_sequence_number(1234, 65534), 65534),
                 static_cast<std::uint32_t>(1234), "max slice index base derivation");

    expect_equal(warpnect::scl::calculate_fragment_count(budget_for_capacity(1), 65536).error,
                 FragmentError::TooManyFragments, "too many fragments rejected");
    expect_equal(
        warpnect::scl::calculate_fragment_count(
            FragmentationConfig{.max_datagram_size = warpnect::scl::kPacketHeaderWireSize - 1}, 1)
            .error,
        FragmentError::DatagramBudgetTooSmall, "too-small budget rejected");
    expect_equal(
        warpnect::scl::calculate_fragment_count(
            FragmentationConfig{.max_datagram_size = warpnect::scl::kUdpMaxDatagramPayloadSize + 1},
            1)
            .error,
        FragmentError::DatagramBudgetTooLarge, "too-large budget rejected");

    PacketHeader nested = logical_header();
    nested.total_slices = 2;
    expect_equal(
        warpnect::scl::plan_fragments(budget_for_capacity(4), nested, std::span<const std::byte>{})
            .error,
        FragmentError::AlreadyFragmented, "nested fragmentation rejected");
}

void test_udp_fragmentation_integration(bool send_out_of_order) {
    constexpr FragmentationConfig config = budget_for_capacity(43);
    std::array<std::byte, 130> payload{};
    fill_payload(payload);
    std::array<FragmentView, 4> fragments{};
    expect(collect_fragments(fragments, config, payload, logical_header(7000)),
           "UDP integration fragments collected");

    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "fragment UDP sender opens");

    std::array<std::array<std::byte, 64>, 4> datagrams{};
    std::array<std::size_t, 4> datagram_sizes{};
    for (std::size_t i = 0; i < fragments.size(); ++i) {
        const auto encoded =
            warpnect::scl::encode_packet(fragments[i].header, fragments[i].payload, datagrams[i]);
        expect(encoded.ok(), "fragment datagram encodes");
        datagram_sizes[i] = encoded.bytes_written;
    }

    const std::array<std::uint16_t, 4> order = send_out_of_order
                                                   ? std::array<std::uint16_t, 4>{2, 0, 3, 1}
                                                   : std::array<std::uint16_t, 4>{0, 1, 2, 3};

    for (std::uint16_t index : order) {
        const auto sent = sender.send_to(
            std::span<const std::byte>(datagrams[index]).first(datagram_sizes[index]), destination);
        expect(sent.ok(), "fragment datagram sends over UDP");
    }

    std::array<std::byte, 172> reassembly_storage{};
    std::array<std::byte, 1> reassembly_metadata{};
    ReassemblySlot slot(config, ReassemblyWorkspace{.payload_storage = reassembly_storage,
                                                    .received_bitmap = reassembly_metadata});

    std::array<std::byte, 64> receive_buffer{};
    for (std::size_t i = 0; i < fragments.size(); ++i) {
        const UdpReceiveResult received = receive_until_ready(receiver, receive_buffer);
        expect(received.ok(), "fragment datagram receives over UDP");

        const auto decoded = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received.bytes_received));
        expect(decoded.ok(), "UDP fragment datagram decodes");
        const auto accepted = slot.accept(decoded.packet);
        expect(accepted.ok(), "UDP decoded fragment accepted");
    }

    expect(slot.is_complete(), "UDP fragment assembly complete");
    expect(payload_matches(slot.result().payload.payload, payload),
           send_out_of_order ? "UDP out-of-order payload reconstructed"
                             : "UDP in-order payload reconstructed");
}

void test_deterministic_property_coverage() {
    std::array<std::byte, 1536> payload{};
    fill_payload(payload);

    constexpr std::array<std::size_t, 5> capacities{43, 107, 235, 491, 1179};

    for (std::size_t case_index = 0; case_index < 40; ++case_index) {
        const std::size_t payload_size = (case_index * 37U) % 1024U;
        const FragmentationConfig config =
            budget_for_capacity(capacities[case_index % capacities.size()]);
        PacketHeader header =
            logical_header(static_cast<std::uint32_t>(0xFFFF0000U + case_index),
                           case_index % 2U == 0 ? PayloadType::Telemetry : PayloadType::Video);
        header.flags = static_cast<std::uint16_t>(case_index * 17U);
        header.timestamp_us += case_index;

        const auto plan = warpnect::scl::plan_fragments(
            config, header, std::span<const std::byte>(payload).first(payload_size));
        expect(plan.ok(), "property plan succeeds");
        FragmentCursor cursor(plan.plan);

        std::array<std::array<std::byte, 1200>, 32> encoded{};
        std::array<std::size_t, 32> sizes{};
        std::uint16_t fragment_count = 0;
        while (cursor.has_next()) {
            const auto fragment = cursor.next();
            expect(fragment.ok(), "property fragment produced");
            expect(fragment_count < encoded.size(), "property encoded storage capacity");
            const auto packet = warpnect::scl::encode_packet(
                fragment.fragment.header, fragment.fragment.payload, encoded[fragment_count]);
            expect(packet.ok(), "property fragment encodes");
            sizes[fragment_count] = packet.bytes_written;
            ++fragment_count;
        }

        std::array<std::byte, 2048> storage{};
        std::array<std::byte, 8> metadata{};
        ReassemblySlot slot(
            config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});

        for (std::uint16_t step = 0; step < fragment_count; ++step) {
            const std::uint16_t index =
                static_cast<std::uint16_t>((step * 3U + 1U) % fragment_count);
            const auto decoded = warpnect::scl::decode_packet(
                std::span<const std::byte>(encoded[index]).first(sizes[index]));
            expect(decoded.ok(), "property fragment decodes");
            const auto accepted = slot.accept(decoded.packet);
            if (accepted.error == FragmentError::DuplicateFragment) {
                continue;
            }
            expect(accepted.ok(), "property fragment accepted");
        }

        for (std::uint16_t index = 0; index < fragment_count; ++index) {
            const auto decoded = warpnect::scl::decode_packet(
                std::span<const std::byte>(encoded[index]).first(sizes[index]));
            const auto accepted = slot.accept(decoded.packet);
            expect(accepted.ok() || accepted.error == FragmentError::DuplicateFragment,
                   "property completion accepts or duplicates");
        }

        expect(slot.is_complete(), "property assembly complete");
        expect(payload_matches(slot.result().payload.payload,
                               std::span<const std::byte>(payload).first(payload_size)),
               "property payload reconstructed");
    }
}

} // namespace

int main() {
    test_fragment_counts();
    test_single_and_multi_slice_metadata();
    test_sequence_wrap_and_encoding();
    test_reassembly_orders();
    test_final_slice_first_with_exact_storage();
    test_duplicate_and_conflicting_duplicate();
    test_group_mismatches();
    test_invalid_slice_and_size_validation();
    test_workspace_validation_and_reset();
    test_empty_payload_and_math_helpers();
    test_udp_fragmentation_integration(false);
    test_udp_fragmentation_integration(true);
    test_deterministic_property_coverage();

    if (failures != 0) {
        std::cerr << failures << " SCL fragmentation test failure(s)\n";
        return 1;
    }

    std::cout << "SCL fragmentation tests passed\n";
    return 0;
}
