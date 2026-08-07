#include "fragmentation.h"
#include "loss_detector.h"
#include "packet_codec.h"
#include "reassembly.h"
#include "recovery_control.h"
#include "retransmission_cache.h"
#include "sequence_number.h"
#include "udp_socket.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>

namespace {

using warpnect::scl::FragmentationConfig;
using warpnect::scl::FragmentCursor;
using warpnect::scl::FragmentError;
using warpnect::scl::IpAddress;
using warpnect::scl::IpVersion;
using warpnect::scl::LossObservationKind;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::LossSlot;
using warpnect::scl::LossSlotState;
using warpnect::scl::NackRequest;
using warpnect::scl::NackSequenceCursor;
using warpnect::scl::PacketHeader;
using warpnect::scl::PacketView;
using warpnect::scl::PayloadType;
using warpnect::scl::ReassemblySlot;
using warpnect::scl::ReassemblyWorkspace;
using warpnect::scl::RecoveryError;
using warpnect::scl::RetransmissionCache;
using warpnect::scl::RetransmissionCacheConfig;
using warpnect::scl::RetransmissionCacheWorkspace;
using warpnect::scl::RetransmissionEntry;
using warpnect::scl::SequenceOrder;
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

void fill_payload(std::span<std::byte> payload, std::uint8_t seed = 11) noexcept {
    for (std::size_t i = 0; i < payload.size(); ++i) {
        payload[i] = byte(static_cast<std::uint8_t>((i * 29U + seed) & 0xFFU));
    }
}

[[nodiscard]] constexpr PacketHeader data_header(std::uint32_t sequence,
                                                 PayloadType payload_type = PayloadType::Video,
                                                 std::uint64_t timestamp_us = 0x1122334455667788ULL,
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

[[nodiscard]] bool open_bound_socket_v4(UdpSocket& socket, UdpEndpoint& local_endpoint) noexcept {
    const auto open = socket.open(IpVersion::V4);
    expect(open.ok(), "IPv4 socket opens");
    if (!open.ok()) {
        return false;
    }

    const auto bind = socket.bind(UdpEndpoint::loopback_v4(0));
    expect(bind.ok(), "IPv4 socket binds loopback ephemeral port");
    if (!bind.ok()) {
        return false;
    }

    const auto local = socket.local_endpoint();
    expect(local.ok(), "IPv4 socket local endpoint available");
    if (!local.ok()) {
        return false;
    }

    expect(local.endpoint.address == IpAddress::loopback_v4(), "local endpoint is loopback");
    expect(local.endpoint.port != 0, "local endpoint has ephemeral port");
    local_endpoint = local.endpoint;
    return true;
}

void test_sequence_arithmetic() {
    expect(warpnect::scl::sequence_equal(100, 100), "sequence equality");
    expect_equal(warpnect::scl::compare_sequence_numbers(1, 0).order, SequenceOrder::Newer,
                 "1 is newer than 0");
    expect_equal(warpnect::scl::compare_sequence_numbers(0, 1).order, SequenceOrder::Older,
                 "0 is older than 1");
    expect(warpnect::scl::sequence_newer(101, 100).value, "101 newer than 100");
    expect(warpnect::scl::sequence_older(100, 101).value, "100 older than 101");
    expect_equal(warpnect::scl::compare_sequence_numbers(0, 0xFFFFFFFFU).order,
                 SequenceOrder::Newer, "0 is newer than UINT32_MAX across wrap");
    expect_equal(warpnect::scl::compare_sequence_numbers(1, 0xFFFFFFFEU).order,
                 SequenceOrder::Newer, "1 is newer than UINT32_MAX - 1 across wrap");
    expect_equal(warpnect::scl::forward_sequence_distance(0xFFFFFFFFU, 0).distance,
                 static_cast<std::uint32_t>(1), "forward distance wraps by one");
    expect_equal(warpnect::scl::forward_sequence_distance(0xFFFFFFFEU, 1).distance,
                 static_cast<std::uint32_t>(3), "forward distance wraps by three");
    expect_equal(warpnect::scl::compare_sequence_numbers(0x80000000U, 0).error,
                 RecoveryError::AmbiguousSequenceDistance, "half-range comparison is ambiguous");
    expect_equal(warpnect::scl::forward_sequence_distance(0, 0x80000000U).error,
                 RecoveryError::AmbiguousSequenceDistance, "half-range distance is ambiguous");
}

void test_in_order_tracking() {
    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                            .renack_interval_us = 20,
                                                            .max_nack_attempts = 2},
                                         slots);

    expect_equal(detector.observe(100, 0).kind, LossObservationKind::FirstPacket,
                 "first packet establishes baseline");
    expect_equal(detector.observe(101, 1).kind, LossObservationKind::InOrder, "101 is in order");
    expect_equal(detector.observe(102, 2).kind, LossObservationKind::InOrder, "102 is in order");
    expect_equal(detector.observe(103, 3).kind, LossObservationKind::InOrder, "103 is in order");
    expect(!detector.has_missing(), "in-order stream has no missing packets");

    std::array<NackRequest, 1> output{};
    const auto nacks = detector.collect_due_nacks(100, PayloadType::Video, output);
    expect(nacks.ok(), "collect on in-order detector succeeds");
    expect_equal(nacks.requests_written, static_cast<std::size_t>(0),
                 "in-order stream emits no NACK");
    expect_equal(detector.frontier_sequence(), static_cast<std::uint32_t>(103),
                 "frontier advances to 103");
}

void test_single_gap_and_reordering_delay() {
    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                            .renack_interval_us = 20,
                                                            .max_nack_attempts = 2},
                                         slots);

    expect_equal(detector.observe(100, 0).kind, LossObservationKind::FirstPacket,
                 "single gap baseline");
    const auto gap = detector.observe(102, 100);
    expect_equal(gap.kind, LossObservationKind::GapDetected, "102 creates a gap");
    expect_equal(gap.missing_created, static_cast<std::size_t>(1), "one sequence is missing");
    expect_equal(detector.sequence_status(101).state, LossSlotState::Missing, "101 marked missing");

    std::array<NackRequest, 1> output{};
    expect_equal(detector.collect_due_nacks(109, PayloadType::Video, output).requests_written,
                 static_cast<std::size_t>(0), "gap is not NACKed before reorder delay");

    const auto due = detector.collect_due_nacks(110, PayloadType::Video, output);
    expect(due.ok(), "single due NACK collection succeeds");
    expect_equal(due.requests_written, static_cast<std::size_t>(1), "one NACK generated");
    expect_equal(output[0].target_payload_type, PayloadType::Video, "NACK target type");
    expect_equal(output[0].base_sequence_number, static_cast<std::uint32_t>(101),
                 "NACK base sequence is missing packet");
    expect_equal(output[0].missing_bitmap, static_cast<std::uint64_t>(1),
                 "single missing packet bitmap");
}

void test_multiple_gap_and_compact_nack() {
    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(
        LossRecoveryConfig{.reorder_delay_us = 0, .renack_interval_us = 20, .max_nack_attempts = 2},
        slots);

    expect(detector.observe(100, 0).ok(), "multi gap first packet");
    const auto gap = detector.observe(105, 1);
    expect_equal(gap.kind, LossObservationKind::GapDetected, "105 creates multiple gap");
    expect_equal(gap.missing_created, static_cast<std::size_t>(4), "four missing sequences");

    std::array<NackRequest, 1> output{};
    const auto nacks = detector.collect_due_nacks(1, PayloadType::Video, output);
    expect(nacks.ok(), "multi gap NACK collection succeeds");
    expect_equal(nacks.requests_written, static_cast<std::size_t>(1),
                 "compact multi-gap NACK generated");
    expect_equal(output[0].base_sequence_number, static_cast<std::uint32_t>(101),
                 "multi-gap NACK base");
    expect_equal(output[0].missing_bitmap, static_cast<std::uint64_t>(0x0F),
                 "multi-gap NACK bitmap packs 101..104");
}

void test_reordered_and_late_recovery() {
    {
        std::array<LossSlot, 16> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                                .renack_interval_us = 20,
                                                                .max_nack_attempts = 2},
                                             slots);

        expect(detector.observe(100, 0).ok(), "reorder first packet");
        expect_equal(detector.observe(102, 5).kind, LossObservationKind::GapDetected,
                     "reorder gap created");
        expect_equal(detector.observe(101, 8).kind, LossObservationKind::RecoveredMissing,
                     "reordered packet clears missing state before NACK");

        std::array<NackRequest, 1> output{};
        expect_equal(detector.collect_due_nacks(20, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(0), "reordered packet suppresses NACK");
    }

    {
        std::array<LossSlot, 16> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                                .renack_interval_us = 20,
                                                                .max_nack_attempts = 2},
                                             slots);

        expect(detector.observe(100, 0).ok(), "late first packet");
        expect(detector.observe(102, 5).ok(), "late gap created");

        std::array<NackRequest, 1> output{};
        expect_equal(detector.collect_due_nacks(15, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(1), "late gap emits first NACK");
        expect_equal(detector.observe(101, 16).kind, LossObservationKind::RecoveredMissing,
                     "late packet clears missing state after NACK");
        expect_equal(detector.collect_due_nacks(40, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(0), "late recovery prevents re-NACK");
    }
}

void test_duplicate_wrap_and_capacity() {
    {
        std::array<LossSlot, 16> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                                .renack_interval_us = 20,
                                                                .max_nack_attempts = 2},
                                             slots);

        expect(detector.observe(100, 0).ok(), "duplicate first");
        expect_equal(detector.observe(101, 1).kind, LossObservationKind::InOrder,
                     "duplicate setup");
        expect_equal(detector.observe(101, 2).kind, LossObservationKind::Duplicate,
                     "duplicate is classified");
        expect_equal(detector.missing_count(), static_cast<std::size_t>(0),
                     "duplicate does not create gaps");
    }

    {
        std::array<LossSlot, 16> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                                .renack_interval_us = 20,
                                                                .max_nack_attempts = 2},
                                             slots);

        expect(detector.observe(0xFFFFFFFEU, 0).ok(), "wrap first");
        expect_equal(detector.observe(0xFFFFFFFFU, 1).kind, LossObservationKind::InOrder,
                     "wrap max in order");
        expect_equal(detector.observe(1, 2).kind, LossObservationKind::GapDetected,
                     "wrap gap detects missing zero");
        expect_equal(detector.sequence_status(0).state, LossSlotState::Missing,
                     "zero is missing across wrap");
        expect_equal(detector.observe(0, 3).kind, LossObservationKind::RecoveredMissing,
                     "zero recovers across wrap");
    }

    {
        std::array<LossSlot, 3> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                                .renack_interval_us = 20,
                                                                .max_nack_attempts = 2},
                                             slots);

        expect(detector.observe(100, 0).ok(), "capacity first");
        expect_equal(detector.observe(105, 1).error, RecoveryError::WindowCapacityExceeded,
                     "forward gap beyond capacity is explicit");
        expect_equal(detector.frontier_sequence(), static_cast<std::uint32_t>(100),
                     "capacity failure does not rebase frontier");
    }
}

void test_renack_timing_and_max_attempts() {
    {
        std::array<LossSlot, 16> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                                .renack_interval_us = 20,
                                                                .max_nack_attempts = 3},
                                             slots);

        expect(detector.observe(100, 0).ok(), "renack first");
        expect(detector.observe(102, 100).ok(), "renack gap");

        std::array<NackRequest, 1> output{};
        expect_equal(detector.collect_due_nacks(109, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(0), "before reorder delay no request");
        expect_equal(detector.collect_due_nacks(110, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(1), "at reorder delay first request");
        expect_equal(detector.collect_due_nacks(129, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(0), "before re-NACK interval no request");
        expect_equal(detector.collect_due_nacks(130, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(1), "after re-NACK interval second request");
        expect_equal(detector.sequence_status(101).nack_attempts, static_cast<std::uint16_t>(2),
                     "NACK attempts updated only for emitted requests");
    }

    {
        std::array<LossSlot, 16> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                                .renack_interval_us = 1,
                                                                .max_nack_attempts = 2},
                                             slots);

        expect(detector.observe(100, 0).ok(), "max attempts first");
        expect(detector.observe(102, 0).ok(), "max attempts gap");

        std::array<NackRequest, 1> output{};
        expect_equal(detector.collect_due_nacks(0, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(1), "first max-attempt NACK");
        expect_equal(detector.collect_due_nacks(1, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(1), "second max-attempt NACK");
        expect_equal(detector.collect_due_nacks(2, PayloadType::Video, output).requests_written,
                     static_cast<std::size_t>(0), "max attempts suppress further NACKs");
        expect_equal(detector.sequence_status(101).nack_attempts, static_cast<std::uint16_t>(2),
                     "exact max attempts recorded");
    }
}

void test_nack_golden_vectors_and_invalid_input() {
    constexpr NackRequest request{
        .target_payload_type = PayloadType::Telemetry,
        .base_sequence_number = 0x01020304U,
        .missing_bitmap = 0x0102030405060708ULL,
    };

    constexpr std::array<std::byte, warpnect::scl::kNackPayloadWireSize> expected{
        byte(0x01), byte(0x01), byte(0x05), byte(0x00), byte(0x01), byte(0x02),
        byte(0x03), byte(0x04), byte(0x01), byte(0x02), byte(0x03), byte(0x04),
        byte(0x05), byte(0x06), byte(0x07), byte(0x08),
    };

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> output{};
    expect(warpnect::scl::encode_nack(request, output).ok(), "golden NACK encodes");
    expect(output == expected, "golden NACK bytes match explicit vector");

    const auto decoded = warpnect::scl::decode_nack(expected);
    expect(decoded.ok(), "golden NACK decodes");
    expect(decoded.request == request, "decoded NACK request matches runtime value");

    for (std::size_t size = 0; size < warpnect::scl::kNackPayloadWireSize; ++size) {
        expect_equal(
            warpnect::scl::decode_nack(std::span<const std::byte>(expected).first(size)).error,
            RecoveryError::InvalidNackPayload, "truncated NACK rejected");
    }

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize + 1> oversized{};
    expect_equal(warpnect::scl::decode_nack(oversized).error, RecoveryError::InvalidNackPayload,
                 "oversized NACK rejected");

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> malformed = expected;
    malformed[0] = byte(0x02);
    expect_equal(warpnect::scl::decode_nack(malformed).error, RecoveryError::UnsupportedControlType,
                 "unknown control type rejected");
    malformed = expected;
    malformed[1] = byte(0x02);
    expect_equal(warpnect::scl::decode_nack(malformed).error,
                 RecoveryError::UnsupportedControlVersion, "unsupported control version rejected");
    malformed = expected;
    malformed[2] = byte(0x00);
    expect_equal(warpnect::scl::decode_nack(malformed).error,
                 RecoveryError::InvalidTargetPayloadType, "Unknown target payload type rejected");
    malformed = expected;
    malformed[2] = byte(0xFF);
    expect_equal(warpnect::scl::decode_nack(malformed).error,
                 RecoveryError::InvalidTargetPayloadType, "undefined target payload type rejected");
    malformed = expected;
    malformed[3] = byte(0x01);
    expect_equal(warpnect::scl::decode_nack(malformed).error, RecoveryError::InvalidNackPayload,
                 "reserved byte must be zero");
    malformed = expected;
    for (std::size_t i = 8; i < malformed.size(); ++i) {
        malformed[i] = byte(0x00);
    }
    expect_equal(warpnect::scl::decode_nack(malformed).error, RecoveryError::EmptyNackBitmap,
                 "empty NACK bitmap rejected");

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize - 1> small_output{};
    expect_equal(warpnect::scl::encode_nack(request, small_output).error,
                 RecoveryError::OutputBufferTooSmall, "small encode output rejected");
}

void test_nack_bitmap_wrap_and_packing() {
    NackSequenceCursor cursor(NackRequest{
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = 0xFFFFFFFEU,
        .missing_bitmap = 0x0FULL,
    });

    expect(cursor.has_next(), "wrap bitmap cursor starts non-empty");
    expect_equal(cursor.next().sequence_number, static_cast<std::uint32_t>(0xFFFFFFFEU),
                 "cursor yields base near wrap");
    expect_equal(cursor.next().sequence_number, static_cast<std::uint32_t>(0xFFFFFFFFU),
                 "cursor yields max sequence");
    expect_equal(cursor.next().sequence_number, static_cast<std::uint32_t>(0),
                 "cursor wraps to zero");
    expect_equal(cursor.next().sequence_number, static_cast<std::uint32_t>(1),
                 "cursor wraps to one");
    expect_equal(cursor.next().error, RecoveryError::NoMoreSequences, "cursor reports exhaustion");

    std::array<LossSlot, 32> slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                            .renack_interval_us = 1000,
                                                            .max_nack_attempts = 2},
                                         slots);
    expect(detector.observe(99, 0).ok(), "packing baseline");
    expect(detector.observe(102, 0).ok(), "packing creates 100,101 missing");
    expect(detector.observe(106, 0).ok(), "packing creates 103,104,105 missing");
    expect_equal(detector.observe(103, 0).kind, LossObservationKind::RecoveredMissing,
                 "packing recovers 103");
    expect_equal(detector.observe(104, 0).kind, LossObservationKind::RecoveredMissing,
                 "packing recovers 104");

    std::array<NackRequest, 1> packed{};
    const auto nacks = detector.collect_due_nacks(0, PayloadType::Video, packed);
    expect(nacks.ok(), "sparse packing collection succeeds");
    expect_equal(nacks.requests_written, static_cast<std::size_t>(1), "sparse misses fit one NACK");
    expect_equal(packed[0].base_sequence_number, static_cast<std::uint32_t>(100),
                 "sparse NACK base");
    expect_equal(packed[0].missing_bitmap,
                 (std::uint64_t{1} << 0U) | (std::uint64_t{1} << 1U) | (std::uint64_t{1} << 5U),
                 "sparse NACK packs bits 0,1,5");
}

void test_multiple_nack_packing_and_output_capacity() {
    std::array<LossSlot, 128> slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                            .renack_interval_us = 1000,
                                                            .max_nack_attempts = 2},
                                         slots);

    expect(detector.observe(99, 0).ok(), "multi NACK baseline");
    expect(detector.observe(166, 0).ok(), "multi NACK gap spans more than 64 positions");

    std::array<NackRequest, 2> output{};
    const auto nacks = detector.collect_due_nacks(0, PayloadType::Video, output);
    expect(nacks.ok(), "multi NACK collection succeeds");
    expect_equal(nacks.requests_written, static_cast<std::size_t>(2), "two NACK requests emitted");
    expect_equal(output[0].base_sequence_number, static_cast<std::uint32_t>(100),
                 "first NACK base");
    expect_equal(output[0].missing_bitmap, ~std::uint64_t{0},
                 "first NACK covers first 64 missing packets");
    expect_equal(output[1].base_sequence_number, static_cast<std::uint32_t>(164),
                 "second NACK base");
    expect_equal(output[1].missing_bitmap, static_cast<std::uint64_t>(0x03),
                 "second NACK covers 164 and 165");

    std::array<LossSlot, 128> capacity_slots{};
    warpnect::scl::LossDetector capacity_detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                                     .renack_interval_us = 1000,
                                                                     .max_nack_attempts = 2},
                                                  capacity_slots);
    expect(capacity_detector.observe(99, 0).ok(), "capacity baseline");
    expect(capacity_detector.observe(166, 0).ok(), "capacity gap");

    std::array<NackRequest, 1> one_output{};
    const auto partial = capacity_detector.collect_due_nacks(0, PayloadType::Video, one_output);
    expect_equal(partial.error, RecoveryError::OutputBufferTooSmall,
                 "insufficient NACK output is explicit");
    expect_equal(partial.requests_written, static_cast<std::size_t>(1),
                 "only emitted NACK is counted");
    expect(partial.output_exhausted, "output exhausted flag set");
    expect_equal(capacity_detector.sequence_status(100).nack_attempts,
                 static_cast<std::uint16_t>(1), "emitted sequence attempt incremented");
    expect_equal(capacity_detector.sequence_status(164).nack_attempts,
                 static_cast<std::uint16_t>(0), "omitted sequence attempt unchanged");

    std::array<NackRequest, 2> remaining{};
    const auto remaining_result =
        capacity_detector.collect_due_nacks(1, PayloadType::Video, remaining);
    expect(remaining_result.ok(), "omitted sequences remain eligible");
    expect_equal(remaining_result.requests_written, static_cast<std::size_t>(1),
                 "remaining omitted range emits later");
    expect_equal(remaining[0].base_sequence_number, static_cast<std::uint32_t>(164),
                 "remaining NACK starts at omitted range");
}

void test_retransmission_cache_basics() {
    std::array<std::byte, 3 * 32> storage{};
    std::array<RetransmissionEntry, 3> entries{};
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 3, .max_datagram_size = 32},
        RetransmissionCacheWorkspace{.datagram_storage = storage, .entries = entries});

    constexpr std::array<std::byte, 4> a{byte(0x00), byte(0xFF), byte(0x41), byte(0x42)};
    constexpr std::array<std::byte, 3> b{byte(0x10), byte(0x00), byte(0x20)};
    constexpr std::array<std::byte, 5> c{byte(0x99), byte(0x88), byte(0x77), byte(0x66),
                                         byte(0x55)};

    expect(cache.store(PayloadType::Video, 100, a).ok(), "cache stores sequence 100");
    expect(cache.store(PayloadType::Video, 101, b).ok(), "cache stores sequence 101");
    expect(cache.store(PayloadType::Video, 102, c).ok(), "cache stores sequence 102");
    expect(bytes_equal(cache.find(PayloadType::Video, 100).datagram, a),
           "cache lookup preserves binary datagram A");
    expect(bytes_equal(cache.find(PayloadType::Video, 101).datagram, b),
           "cache lookup preserves binary datagram B");
    expect(bytes_equal(cache.find(PayloadType::Video, 102).datagram, c),
           "cache lookup preserves binary datagram C");

    expect(cache.store(PayloadType::Video, 100, a).ok(),
           "identical duplicate cache store is idempotent");
    constexpr std::array<std::byte, 4> conflicting{byte(0x00), byte(0xFF), byte(0x41), byte(0x99)};
    expect_equal(cache.store(PayloadType::Video, 100, conflicting).error,
                 RecoveryError::ConflictingCachedDatagram, "conflicting cache store rejected");
    expect(bytes_equal(cache.find(PayloadType::Video, 100).datagram, a),
           "conflicting cache store preserves original bytes");
}

void test_cache_eviction_and_bounds() {
    {
        std::array<std::byte, 2 * 16> storage{};
        std::array<RetransmissionEntry, 2> entries{};
        RetransmissionCache cache(
            RetransmissionCacheConfig{.slot_count = 2, .max_datagram_size = 16},
            RetransmissionCacheWorkspace{.datagram_storage = storage, .entries = entries});

        constexpr std::array<std::byte, 1> a{byte(0xA0)};
        constexpr std::array<std::byte, 1> b{byte(0xB0)};
        constexpr std::array<std::byte, 1> c{byte(0xC0)};
        expect(cache.store(PayloadType::Video, 100, a).ok(), "eviction stores A");
        expect(cache.store(PayloadType::Video, 101, b).ok(), "eviction stores B");
        expect(cache.store(PayloadType::Video, 102, c).ok(), "eviction stores C");
        expect_equal(cache.find(PayloadType::Video, 100).error, RecoveryError::NotCached,
                     "oldest cache entry evicted");
        expect(bytes_equal(cache.find(PayloadType::Video, 101).datagram, b),
               "middle cache entry remains");
        expect(bytes_equal(cache.find(PayloadType::Video, 102).datagram, c),
               "new cache entry remains");
    }

    {
        std::array<std::byte, 64> storage{};
        storage[63] = byte(0xEE);
        std::array<RetransmissionEntry, 2> entries{};
        RetransmissionCache cache(
            RetransmissionCacheConfig{.slot_count = 2, .max_datagram_size = 32},
            RetransmissionCacheWorkspace{
                .datagram_storage = std::span<std::byte>(storage).first(63), .entries = entries});

        constexpr std::array<std::byte, 1> datagram{byte(0x42)};
        expect_equal(cache.store(PayloadType::Video, 1, datagram).error,
                     RecoveryError::CacheStorageTooSmall, "insufficient cache storage rejected");
        expect_equal(storage[63], byte(0xEE), "cache storage guard byte preserved");
    }

    {
        std::array<std::byte, 4> storage{};
        std::array<RetransmissionEntry, 1> entries{};
        RetransmissionCache cache(
            RetransmissionCacheConfig{.slot_count = 1, .max_datagram_size = 4},
            RetransmissionCacheWorkspace{.datagram_storage = storage, .entries = entries});

        constexpr std::array<std::byte, 5> too_large{byte(1), byte(2), byte(3), byte(4), byte(5)};
        expect_equal(cache.store(PayloadType::Video, 1, too_large).error,
                     RecoveryError::CacheDatagramTooLarge, "oversized cache datagram rejected");
    }
}

void test_retransmission_identity() {
    const PacketHeader header = data_header(777, PayloadType::Telemetry, 123456, 0xBEEF);
    constexpr std::array<std::byte, 6> payload{byte(0x00), byte(0xFF), byte(0x10),
                                               byte(0x20), byte(0x30), byte(0x40)};
    std::array<std::byte, 64> datagram{};
    const auto encoded = warpnect::scl::encode_packet(header, payload, datagram);
    expect(encoded.ok(), "identity packet encodes");

    std::array<std::byte, 2 * 64> storage{};
    std::array<RetransmissionEntry, 2> entries{};
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 2, .max_datagram_size = 64},
        RetransmissionCacheWorkspace{.datagram_storage = storage, .entries = entries});
    const std::span<const std::byte> original =
        std::span<const std::byte>(datagram).first(encoded.bytes_written);
    expect(cache.store(header.payload_type, header.sequence_number, original).ok(),
           "identity packet stores in cache");
    const auto lookup = cache.find(header.payload_type, header.sequence_number);
    expect(lookup.ok(), "identity packet lookup succeeds");
    expect(bytes_equal(lookup.datagram, original),
           "cached retransmission datagram is byte-identical to original");
}

struct FragmentFixture final {
    static constexpr std::size_t kBudget = 64;
    static constexpr std::size_t kMaxFragments = 8;

    std::array<std::byte, 150> payload{};
    std::array<std::byte, kMaxFragments * kBudget> datagrams{};
    std::array<std::size_t, kMaxFragments> sizes{};
    std::size_t fragment_count = 0;
};

[[nodiscard]] bool build_fragment_fixture(FragmentFixture& fixture,
                                          RetransmissionCache& cache) noexcept {
    fill_payload(fixture.payload);

    const auto plan = warpnect::scl::plan_fragments(
        FragmentationConfig{.max_datagram_size = FragmentFixture::kBudget},
        data_header(100, PayloadType::Video, 0x0102030405060708ULL, 0x00AA), fixture.payload);
    expect(plan.ok(), "fragment recovery plan succeeds");
    if (!plan.ok()) {
        return false;
    }

    FragmentCursor cursor(plan.plan);
    while (cursor.has_next()) {
        const auto fragment = cursor.next();
        expect(fragment.ok(), "fragment generated for fixture");
        if (!fragment.ok() || fixture.fragment_count >= FragmentFixture::kMaxFragments) {
            return false;
        }

        std::span<std::byte> slot = std::span<std::byte>(fixture.datagrams)
                                        .subspan(fixture.fragment_count * FragmentFixture::kBudget,
                                                 FragmentFixture::kBudget);
        const auto encoded =
            warpnect::scl::encode_packet(fragment.fragment.header, fragment.fragment.payload, slot);
        expect(encoded.ok(), "fragment datagram encodes");
        if (!encoded.ok()) {
            return false;
        }

        fixture.sizes[fixture.fragment_count] = encoded.bytes_written;
        const std::span<const std::byte> datagram = slot.first(encoded.bytes_written);
        expect(cache
                   .store(fragment.fragment.header.payload_type,
                          fragment.fragment.header.sequence_number, datagram)
                   .ok(),
               "fragment datagram cached");
        ++fixture.fragment_count;
    }

    expect_equal(fixture.fragment_count, static_cast<std::size_t>(4),
                 "fixture creates four fragments");
    return fixture.fragment_count == 4;
}

[[nodiscard]] std::span<const std::byte> fixture_datagram(const FragmentFixture& fixture,
                                                          std::size_t index) noexcept {
    return std::span<const std::byte>(fixture.datagrams)
        .subspan(index * FragmentFixture::kBudget, fixture.sizes[index]);
}

void test_fragment_recovery_without_udp() {
    std::array<std::byte, 8 * FragmentFixture::kBudget> cache_storage{};
    std::array<RetransmissionEntry, 8> cache_entries{};
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 8, .max_datagram_size = FragmentFixture::kBudget},
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = cache_entries});

    FragmentFixture fixture{};
    if (!build_fragment_fixture(fixture, cache)) {
        return;
    }

    std::array<LossSlot, 16> loss_slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                            .renack_interval_us = 20,
                                                            .max_nack_attempts = 2},
                                         loss_slots);
    std::array<std::byte, 200> reassembly_storage{};
    std::array<std::byte, 1> bitmap{};
    ReassemblySlot reassembly(
        FragmentationConfig{.max_datagram_size = FragmentFixture::kBudget},
        ReassemblyWorkspace{.payload_storage = reassembly_storage, .received_bitmap = bitmap});

    constexpr std::array<std::size_t, 3> delivered{0, 1, 3};
    for (std::size_t index : delivered) {
        const auto decoded = warpnect::scl::decode_packet(fixture_datagram(fixture, index));
        expect(decoded.ok(), "fragment recovery decode delivered datagram");
        expect(
            detector
                .observe(decoded.packet.header.sequence_number, static_cast<std::uint64_t>(index))
                .ok(),
            "fragment recovery observes delivered sequence");
        expect(reassembly.accept(decoded.packet).ok(),
               "fragment recovery accepts delivered fragment");
    }

    expect_equal(detector.sequence_status(102).state, LossSlotState::Missing,
                 "missing fragment sequence 102 detected");

    std::array<NackRequest, 1> requests{};
    const auto nacks = detector.collect_due_nacks(20, PayloadType::Video, requests);
    expect(nacks.ok(), "fragment recovery NACK generated");
    expect_equal(nacks.requests_written, static_cast<std::size_t>(1),
                 "fragment recovery emits one NACK");
    expect_equal(requests[0].base_sequence_number, static_cast<std::uint32_t>(102),
                 "fragment recovery NACK requests sequence 102");

    NackSequenceCursor cursor(requests[0]);
    const auto requested = cursor.next();
    expect(requested.ok(), "fragment recovery cursor yields requested sequence");
    const auto cached = cache.find(PayloadType::Video, requested.sequence_number);
    expect(cached.ok(), "fragment recovery cache resolves missing sequence");
    expect(bytes_equal(cached.datagram, fixture_datagram(fixture, 2)),
           "fragment recovery cache returns original missing datagram");

    const auto recovered = warpnect::scl::decode_packet(cached.datagram);
    expect(recovered.ok(), "fragment recovery decodes cached datagram");
    expect_equal(detector.observe(recovered.packet.header.sequence_number, 21).kind,
                 LossObservationKind::RecoveredMissing, "recovered fragment clears missing state");
    expect(reassembly.accept(recovered.packet).ok(), "recovered fragment accepted");
    const auto result = reassembly.result();
    expect(result.ok(), "reassembly completes after recovered fragment");
    expect(bytes_equal(result.payload.payload, fixture.payload),
           "recovered reassembly payload matches original");

    expect_equal(detector.observe(recovered.packet.header.sequence_number, 22).kind,
                 LossObservationKind::Duplicate, "duplicate retransmission detected by loss layer");
    expect_equal(reassembly.accept(recovered.packet).error, FragmentError::DuplicateFragment,
                 "duplicate retransmission is harmless to reassembly");
}

void test_full_udp_recovery_integration() {
    UdpSocket sender;
    UdpSocket receiver;
    UdpEndpoint sender_endpoint{};
    UdpEndpoint receiver_endpoint{};
    if (!open_bound_socket_v4(sender, sender_endpoint) ||
        !open_bound_socket_v4(receiver, receiver_endpoint)) {
        return;
    }

    std::array<std::byte, 8 * FragmentFixture::kBudget> cache_storage{};
    std::array<RetransmissionEntry, 8> cache_entries{};
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 8, .max_datagram_size = FragmentFixture::kBudget},
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = cache_entries});

    FragmentFixture fixture{};
    if (!build_fragment_fixture(fixture, cache)) {
        return;
    }

    constexpr std::size_t dropped_index = 2;
    for (std::size_t index = 0; index < fixture.fragment_count; ++index) {
        if (index == dropped_index) {
            continue;
        }

        const auto sent = sender.send_to(fixture_datagram(fixture, index), receiver_endpoint);
        expect(sent.ok(), "UDP integration sends selected fragment");
    }

    std::array<LossSlot, 16> loss_slots{};
    warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 10,
                                                            .renack_interval_us = 20,
                                                            .max_nack_attempts = 2},
                                         loss_slots);
    std::array<std::byte, 200> reassembly_storage{};
    std::array<std::byte, 1> bitmap{};
    ReassemblySlot reassembly(
        FragmentationConfig{.max_datagram_size = FragmentFixture::kBudget},
        ReassemblyWorkspace{.payload_storage = reassembly_storage, .received_bitmap = bitmap});

    std::array<std::byte, FragmentFixture::kBudget> receive_buffer{};
    for (std::size_t i = 0; i < fixture.fragment_count - 1U; ++i) {
        const auto received = receive_until_ready(receiver, receive_buffer);
        expect(received.ok(), "UDP integration receiver gets fragment");
        const auto decoded = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received.bytes_received));
        expect(decoded.ok(), "UDP integration decodes fragment");
        expect(
            detector.observe(decoded.packet.header.sequence_number, static_cast<std::uint64_t>(i))
                .ok(),
            "UDP integration observes fragment sequence");
        expect(reassembly.accept(decoded.packet).ok(), "UDP integration accepts fragment");
    }

    expect_equal(detector.sequence_status(102).state, LossSlotState::Missing,
                 "UDP integration detects dropped sequence");

    std::array<NackRequest, 1> requests{};
    const auto collected = detector.collect_due_nacks(20, PayloadType::Video, requests);
    expect(collected.ok(), "UDP integration collects NACK");
    expect_equal(collected.requests_written, static_cast<std::size_t>(1),
                 "UDP integration emits one NACK");

    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_payload{};
    expect(warpnect::scl::encode_nack(requests[0], nack_payload).ok(),
           "UDP integration encodes NACK payload");
    std::array<std::byte, 64> nack_packet{};
    const PacketHeader nack_header = data_header(9000, PayloadType::SessionControl, 20, 0);
    const auto encoded_nack = warpnect::scl::encode_packet(nack_header, nack_payload, nack_packet);
    expect(encoded_nack.ok(), "UDP integration wraps NACK in SessionControl packet");
    expect(receiver
               .send_to(std::span<const std::byte>(nack_packet).first(encoded_nack.bytes_written),
                        sender_endpoint)
               .ok(),
           "UDP integration sends reverse NACK");

    std::array<std::byte, 128> sender_receive_buffer{};
    const auto received_nack = receive_until_ready(sender, sender_receive_buffer);
    expect(received_nack.ok(), "UDP integration sender receives NACK");
    const auto nack_packet_view = warpnect::scl::decode_packet(
        std::span<const std::byte>(sender_receive_buffer).first(received_nack.bytes_received));
    expect(nack_packet_view.ok(), "UDP integration decodes NACK packet");
    expect_equal(nack_packet_view.packet.header.payload_type, PayloadType::SessionControl,
                 "NACK packet uses SessionControl payload type");
    const auto decoded_nack = warpnect::scl::decode_nack(nack_packet_view.packet.payload);
    expect(decoded_nack.ok(), "UDP integration decodes NACK payload");

    NackSequenceCursor requested_sequences(decoded_nack.request);
    const auto requested = requested_sequences.next();
    expect(requested.ok(), "UDP integration NACK cursor yields missing sequence");
    const auto cached =
        cache.find(decoded_nack.request.target_payload_type, requested.sequence_number);
    expect(cached.ok(), "UDP integration cache resolves missing datagram");
    expect(bytes_equal(cached.datagram, fixture_datagram(fixture, dropped_index)),
           "UDP integration cached datagram is original dropped datagram");
    expect(sender.send_to(cached.datagram, receiver_endpoint).ok(),
           "UDP integration retransmits cached datagram");

    const auto recovered_datagram = receive_until_ready(receiver, receive_buffer);
    expect(recovered_datagram.ok(), "UDP integration receiver gets retransmission");
    const auto recovered_packet = warpnect::scl::decode_packet(
        std::span<const std::byte>(receive_buffer).first(recovered_datagram.bytes_received));
    expect(recovered_packet.ok(), "UDP integration decodes retransmission");
    expect_equal(detector.observe(recovered_packet.packet.header.sequence_number, 21).kind,
                 LossObservationKind::RecoveredMissing,
                 "UDP integration retransmission clears missing state");
    expect(reassembly.accept(recovered_packet.packet).ok(),
           "UDP integration reassembly accepts recovered fragment");

    const auto result = reassembly.result();
    expect(result.ok(), "UDP integration reassembly complete");
    expect(bytes_equal(result.payload.payload, fixture.payload),
           "UDP integration payload reconstructed exactly");
}

void test_not_cached_and_property_style_recovery() {
    {
        std::array<std::byte, 2 * 32> storage{};
        std::array<RetransmissionEntry, 2> entries{};
        RetransmissionCache cache(
            RetransmissionCacheConfig{.slot_count = 2, .max_datagram_size = 32},
            RetransmissionCacheWorkspace{.datagram_storage = storage, .entries = entries});

        constexpr std::array<std::byte, 2> a{byte(0xA0), byte(0xA1)};
        constexpr std::array<std::byte, 2> b{byte(0xB0), byte(0xB1)};
        constexpr std::array<std::byte, 2> c{byte(0xC0), byte(0xC1)};
        expect(cache.store(PayloadType::Video, 100, a).ok(), "not-cached store A");
        expect(cache.store(PayloadType::Video, 101, b).ok(), "not-cached store B");
        expect(cache.store(PayloadType::Video, 102, c).ok(), "not-cached store C evicts A");
        expect_equal(cache.find(PayloadType::Video, 100).error, RecoveryError::NotCached,
                     "evicted NACK request resolves to NotCached");
    }

    for (std::uint32_t scenario = 0; scenario < 12U; ++scenario) {
        std::array<LossSlot, 128> slots{};
        warpnect::scl::LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 5,
                                                                .renack_interval_us = 7,
                                                                .max_nack_attempts = 2},
                                             slots);
        std::array<std::byte, 32 * 32> storage{};
        std::array<RetransmissionEntry, 32> entries{};
        RetransmissionCache cache(
            RetransmissionCacheConfig{.slot_count = 32, .max_datagram_size = 32},
            RetransmissionCacheWorkspace{.datagram_storage = storage, .entries = entries});

        const std::uint32_t base = 0xFFFFFFF0U + scenario * 17U;
        const std::uint32_t missing_offset = 2U + (scenario % 5U);
        std::array<std::array<std::byte, 32>, 8> datagrams{};
        std::array<std::size_t, 8> sizes{};

        for (std::uint32_t offset = 0; offset < 8U; ++offset) {
            const PacketHeader header =
                data_header(base + offset, PayloadType::Telemetry, 1000 + offset, 0x0101U);
            const std::array<std::byte, 3> payload{
                byte(static_cast<std::uint8_t>(scenario)),
                byte(static_cast<std::uint8_t>(offset)),
                byte(static_cast<std::uint8_t>(scenario + offset)),
            };
            const auto encoded = warpnect::scl::encode_packet(header, payload, datagrams[offset]);
            expect(encoded.ok(), "property packet encodes");
            sizes[offset] = encoded.bytes_written;
            expect(
                cache
                    .store(
                        PayloadType::Telemetry, header.sequence_number,
                        std::span<const std::byte>(datagrams[offset]).first(encoded.bytes_written))
                    .ok(),
                "property datagram caches");
        }

        for (std::uint32_t offset = 0; offset < 8U; ++offset) {
            if (offset == missing_offset) {
                continue;
            }

            const auto decoded = warpnect::scl::decode_packet(
                std::span<const std::byte>(datagrams[offset]).first(sizes[offset]));
            expect(decoded.ok(), "property packet decodes");
            expect(detector.observe(decoded.packet.header.sequence_number, offset).ok(),
                   "property sequence observed");
        }

        std::array<NackRequest, 2> requests{};
        const auto collected = detector.collect_due_nacks(100, PayloadType::Telemetry, requests);
        expect(collected.ok(), "property NACK collection succeeds");
        expect(collected.requests_written >= 1U, "property emits at least one NACK");

        std::array<std::byte, warpnect::scl::kNackPayloadWireSize> encoded_request{};
        expect(warpnect::scl::encode_nack(requests[0], encoded_request).ok(),
               "property NACK encodes");
        const auto decoded_request = warpnect::scl::decode_nack(encoded_request);
        expect(decoded_request.ok(), "property NACK decodes");

        NackSequenceCursor cursor(decoded_request.request);
        bool recovered_missing = false;
        while (cursor.has_next()) {
            const auto requested = cursor.next();
            expect(requested.ok(), "property NACK cursor yields sequence");
            const auto cached =
                cache.find(decoded_request.request.target_payload_type, requested.sequence_number);
            expect(cached.ok(), "property cache lookup succeeds");
            const auto recovered = warpnect::scl::decode_packet(cached.datagram);
            expect(recovered.ok(), "property recovered datagram decodes");
            const auto observation = detector.observe(recovered.packet.header.sequence_number, 200);
            if (recovered.packet.header.sequence_number == base + missing_offset) {
                expect_equal(observation.kind, LossObservationKind::RecoveredMissing,
                             "property missing packet recovers");
                recovered_missing = true;
            }
        }

        expect(recovered_missing, "property scenario recovered selected missing packet");
    }
}

} // namespace

int main() {
    test_sequence_arithmetic();
    test_in_order_tracking();
    test_single_gap_and_reordering_delay();
    test_multiple_gap_and_compact_nack();
    test_reordered_and_late_recovery();
    test_duplicate_wrap_and_capacity();
    test_renack_timing_and_max_attempts();
    test_nack_golden_vectors_and_invalid_input();
    test_nack_bitmap_wrap_and_packing();
    test_multiple_nack_packing_and_output_capacity();
    test_retransmission_cache_basics();
    test_cache_eviction_and_bounds();
    test_retransmission_identity();
    test_fragment_recovery_without_udp();
    test_full_udp_recovery_integration();
    test_not_cached_and_property_style_recovery();

    if (failures != 0) {
        std::cerr << failures << " failure(s)\n";
        return 1;
    }

    return 0;
}
