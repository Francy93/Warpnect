#include "clock_sync.h"
#include "clock_sync_control.h"
#include "fec.h"
#include "fragmentation.h"
#include "loss_detector.h"
#include "packet_codec.h"
#include "recovery_control.h"
#include "telemetry.h"
#include "udp_socket.h"

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>

namespace {

using warpnect::scl::ClockExchangeTracker;
using warpnect::scl::ClockModelSnapshot;
using warpnect::scl::ClockSyncConfig;
using warpnect::scl::ClockSynchronizer;
using warpnect::scl::ClockSyncRequest;
using warpnect::scl::ClockSyncResponse;
using warpnect::scl::ClockSyncSample;
using warpnect::scl::ClockSyncState;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::FecBlockEncoder;
using warpnect::scl::FecEncoderWorkspace;
using warpnect::scl::FragmentationConfig;
using warpnect::scl::IpAddress;
using warpnect::scl::IpVersion;
using warpnect::scl::LossObservationKind;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::LossSlot;
using warpnect::scl::NackRequest;
using warpnect::scl::NetworkTelemetry;
using warpnect::scl::NetworkTelemetryStorage;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::PendingClockExchange;
using warpnect::scl::ReedSolomonConfig;
using warpnect::scl::ReedSolomonWorkspace;
using warpnect::scl::RollingSampleWindow;
using warpnect::scl::SessionControlType;
using warpnect::scl::TimingError;
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

void expect_near(double actual, double expected, double tolerance, std::string_view message) {
    if (std::fabs(actual - expected) > tolerance) {
        std::cerr << "FAIL: " << message << " actual=" << actual << " expected=" << expected
                  << '\n';
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

void fill_bytes(std::span<std::byte> bytes, std::uint8_t seed = 13) noexcept {
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 37U + seed) & 0xFFU));
    }
}

[[nodiscard]] constexpr PacketHeader packet_header(std::uint32_t sequence,
                                                   PayloadType payload_type) noexcept {
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

[[nodiscard]] ClockSyncSample model_sample(std::uint64_t local_midpoint, double rate, double offset,
                                           std::uint64_t rtt = 100) noexcept {
    const double remote = static_cast<double>(local_midpoint) * rate + offset;
    return ClockSyncSample{
        .t0_us = local_midpoint - 50,
        .t1_us = static_cast<std::uint64_t>(remote - 10.0),
        .t2_us = static_cast<std::uint64_t>(remote + 10.0),
        .t3_us = local_midpoint + 50,
        .rtt_us = rtt,
        .offset_us = offset,
        .local_midpoint_us = static_cast<double>(local_midpoint),
        .remote_midpoint_us = remote,
    };
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
    expect(bound.ok(), "IPv4 socket binds");
    if (!bound.ok()) {
        return false;
    }

    const auto local = socket.local_endpoint();
    expect(local.ok(), "IPv4 local endpoint available");
    if (!local.ok()) {
        return false;
    }

    expect(local.endpoint.address == IpAddress::loopback_v4(), "IPv4 endpoint is loopback");
    expect(local.endpoint.port != 0, "IPv4 endpoint has port");
    endpoint = local.endpoint;
    return true;
}

void test_clock_control_golden_vectors_and_malformed_inputs() {
    constexpr ClockSyncRequest request{
        .exchange_id = 0x01020304U,
        .t0_us = 0x0102030405060708ULL,
    };
    constexpr std::array<std::byte, warpnect::scl::kClockSyncRequestWireSize> request_expected{
        byte(0x03), byte(0x01), byte(0x00), byte(0x00), byte(0x01), byte(0x02),
        byte(0x03), byte(0x04), byte(0x01), byte(0x02), byte(0x03), byte(0x04),
        byte(0x05), byte(0x06), byte(0x07), byte(0x08),
    };
    std::array<std::byte, warpnect::scl::kClockSyncRequestWireSize> request_output{};
    expect(warpnect::scl::encode_clock_sync_request(request, request_output).ok(),
           "clock request encodes");
    expect(request_output == request_expected, "clock request golden vector");
    expect(warpnect::scl::decode_clock_sync_request(request_expected).request == request,
           "clock request golden decodes");

    constexpr ClockSyncResponse response{
        .exchange_id = 0x01020304U,
        .t0_us = 0x0102030405060708ULL,
        .t1_us = 0x1112131415161718ULL,
        .t2_us = 0x2122232425262728ULL,
    };
    constexpr std::array<std::byte, warpnect::scl::kClockSyncResponseWireSize> response_expected{
        byte(0x04), byte(0x01), byte(0x00), byte(0x00), byte(0x01), byte(0x02), byte(0x03),
        byte(0x04), byte(0x01), byte(0x02), byte(0x03), byte(0x04), byte(0x05), byte(0x06),
        byte(0x07), byte(0x08), byte(0x11), byte(0x12), byte(0x13), byte(0x14), byte(0x15),
        byte(0x16), byte(0x17), byte(0x18), byte(0x21), byte(0x22), byte(0x23), byte(0x24),
        byte(0x25), byte(0x26), byte(0x27), byte(0x28),
    };
    std::array<std::byte, warpnect::scl::kClockSyncResponseWireSize> response_output{};
    expect(warpnect::scl::encode_clock_sync_response(response, response_output).ok(),
           "clock response encodes");
    expect(response_output == response_expected, "clock response golden vector");
    expect(warpnect::scl::decode_clock_sync_response(response_expected).response == response,
           "clock response golden decodes");

    for (std::size_t size = 0; size < request_expected.size(); ++size) {
        expect_equal(warpnect::scl::decode_clock_sync_request(
                         std::span<const std::byte>(request_expected).first(size))
                         .error,
                     TimingError::InvalidControlPayload, "truncated clock request rejected");
    }
    std::array<std::byte, request_expected.size() + 1U> oversized_request{};
    expect_equal(warpnect::scl::decode_clock_sync_request(oversized_request).error,
                 TimingError::InvalidControlPayload, "oversized clock request rejected");

    for (std::size_t size = 0; size < response_expected.size(); ++size) {
        expect_equal(warpnect::scl::decode_clock_sync_response(
                         std::span<const std::byte>(response_expected).first(size))
                         .error,
                     TimingError::InvalidControlPayload, "truncated clock response rejected");
    }
    std::array<std::byte, response_expected.size() + 1U> oversized_response{};
    expect_equal(warpnect::scl::decode_clock_sync_response(oversized_response).error,
                 TimingError::InvalidControlPayload, "oversized clock response rejected");

    auto malformed = request_expected;
    malformed[0] = byte(0x01);
    expect_equal(warpnect::scl::decode_clock_sync_request(malformed).error,
                 TimingError::UnsupportedControlType, "wrong request control type rejected");
    malformed = request_expected;
    malformed[1] = byte(0x02);
    expect_equal(warpnect::scl::decode_clock_sync_request(malformed).error,
                 TimingError::UnsupportedControlVersion, "wrong request version rejected");
    malformed = request_expected;
    malformed[3] = byte(0x01);
    expect_equal(warpnect::scl::decode_clock_sync_request(malformed).error,
                 TimingError::ReservedFieldNonZero, "request reserved field rejected");

    auto malformed_response = response_expected;
    malformed_response[0] = byte(0x03);
    expect_equal(warpnect::scl::decode_clock_sync_response(malformed_response).error,
                 TimingError::UnsupportedControlType, "wrong response control type rejected");
    malformed_response = response_expected;
    malformed_response[1] = byte(0x02);
    expect_equal(warpnect::scl::decode_clock_sync_response(malformed_response).error,
                 TimingError::UnsupportedControlVersion, "wrong response version rejected");
    malformed_response = response_expected;
    malformed_response[2] = byte(0x01);
    expect_equal(warpnect::scl::decode_clock_sync_response(malformed_response).error,
                 TimingError::ReservedFieldNonZero, "response reserved field rejected");
    ClockSyncResponse invalid_order = response;
    invalid_order.t2_us = invalid_order.t1_us - 1U;
    expect_equal(warpnect::scl::encode_clock_sync_response(invalid_order, response_output).error,
                 TimingError::InvalidTimestampOrder, "response t2 before t1 rejected on encode");
}

void test_exchange_tracker() {
    std::array<PendingClockExchange, 2> storage{};
    ClockExchangeTracker tracker(storage);

    expect(tracker.register_request(1, 1000).ok(), "tracker registers first request");
    expect_equal(tracker.register_request(1, 1000).error, TimingError::DuplicateExchange,
                 "tracker rejects duplicate active exchange");
    expect(tracker.register_request(2, 2000).ok(), "tracker registers second request");
    expect_equal(tracker.register_request(3, 3000).error, TimingError::TrackerFull,
                 "tracker capacity exceeded is explicit");

    const ClockSyncResponse response_two{
        .exchange_id = 2, .t0_us = 2000, .t1_us = 2500, .t2_us = 2520};
    expect(tracker.complete_response(response_two, 2220).ok(),
           "tracker completes out-of-order response");
    expect_equal(tracker.active_count(), static_cast<std::size_t>(1),
                 "tracker consumes completed exchange");
    expect_equal(tracker.complete_response(response_two, 2220).error, TimingError::UnknownExchange,
                 "duplicate response after completion is unknown");

    const ClockSyncResponse wrong_t0{.exchange_id = 1, .t0_us = 999, .t1_us = 1500, .t2_us = 1520};
    expect_equal(tracker.complete_response(wrong_t0, 1220).error,
                 TimingError::ExchangeTimestampMismatch, "tracker rejects wrong echoed t0");
    const ClockSyncResponse response_one{
        .exchange_id = 1, .t0_us = 1000, .t1_us = 1500, .t2_us = 1520};
    expect(tracker.complete_response(response_one, 1220).ok(), "tracker completes first response");

    tracker.reset();
    expect_equal(tracker.active_count(), static_cast<std::size_t>(0), "tracker reset clears state");
    expect(tracker.register_request(0xFFFFFFFFU, 42).ok(), "tracker accepts wrapped exchange id");
    const ClockSyncResponse wrapped{
        .exchange_id = 0xFFFFFFFFU, .t0_us = 42, .t1_us = 100, .t2_us = 101};
    expect(tracker.complete_response(wrapped, 144).ok(), "tracker completes wrapped exchange id");
}

void test_sample_math() {
    const auto zero_offset = warpnect::scl::calculate_clock_sync_sample(
        ClockSyncResponse{.exchange_id = 1, .t0_us = 1000, .t1_us = 1100, .t2_us = 1120}, 1220);
    expect(zero_offset.ok(), "zero-offset sample calculates");
    expect_equal(zero_offset.sample.rtt_us, static_cast<std::uint64_t>(200), "zero-offset RTT");
    expect_near(zero_offset.sample.offset_us, 0.0, 0.001, "zero-offset estimate");

    const auto known_offset = warpnect::scl::calculate_clock_sync_sample(
        ClockSyncResponse{.exchange_id = 2, .t0_us = 1000, .t1_us = 1600, .t2_us = 1620}, 1220);
    expect(known_offset.ok(), "known-offset sample calculates");
    expect_equal(known_offset.sample.rtt_us, static_cast<std::uint64_t>(200), "known-offset RTT");
    expect_near(known_offset.sample.offset_us, 500.0, 0.001, "known +500us offset");

    const auto processing = warpnect::scl::calculate_clock_sync_sample(
        ClockSyncResponse{.exchange_id = 3, .t0_us = 1000, .t1_us = 1500, .t2_us = 2500}, 3000);
    expect(processing.ok(), "processing-removal sample calculates");
    expect_equal(processing.sample.rtt_us, static_cast<std::uint64_t>(1000),
                 "responder processing removed from RTT");

    expect_equal(
        warpnect::scl::calculate_clock_sync_sample(
            ClockSyncResponse{.exchange_id = 4, .t0_us = 1000, .t1_us = 1500, .t2_us = 2500}, 1200)
            .error,
        TimingError::InvalidSample, "remote processing larger than local elapsed rejected");

    const std::uint64_t near_max = std::numeric_limits<std::uint64_t>::max();
    const auto overflow_safe =
        warpnect::scl::calculate_clock_sync_sample(ClockSyncResponse{.exchange_id = 5,
                                                                     .t0_us = near_max - 100U,
                                                                     .t1_us = near_max - 50U,
                                                                     .t2_us = near_max - 40U},
                                                   near_max - 80U);
    expect(overflow_safe.ok(), "midpoint near UINT64_MAX does not overflow");
    expect_equal(overflow_safe.sample.rtt_us, static_cast<std::uint64_t>(10),
                 "near-max RTT calculation");
}

void test_clock_model_fit_conversion_and_quality() {
    std::array<ClockSyncSample, 8> samples{};
    ClockSynchronizer sync(ClockSyncConfig{.min_samples_for_model = 2,
                                           .max_accepted_rtt_us = 1000,
                                           .max_abs_drift_ppm = 1000.0,
                                           .stale_after_us = 1000},
                           samples);

    expect_equal(sync.remote_to_local(1500, 0).error, TimingError::ClockModelUnavailable,
                 "unsynchronized conversion fails");
    expect(sync.add_sample(model_sample(1000, 1.0, 500.0)).ok(), "first model sample accepted");
    expect_equal(sync.snapshot(1050).state, ClockSyncState::WarmingUp, "one sample warms up");
    expect_equal(sync.local_to_remote(1500, 1050).error, TimingError::ClockModelUnavailable,
                 "warming-up conversion fails");
    expect(sync.add_sample(model_sample(2000, 1.0, 500.0)).ok(), "second model sample accepted");

    const ClockModelSnapshot snapshot = sync.snapshot(2050);
    expect_equal(snapshot.state, ClockSyncState::Synchronized,
                 "constant-offset model synchronized");
    expect_near(snapshot.rate_ratio, 1.0, 0.000001, "constant-offset rate");
    expect_near(snapshot.drift_ppm, 0.0, 0.001, "constant-offset drift");
    expect_equal(sync.local_to_remote(3000, 2050).timestamp_us, static_cast<std::uint64_t>(3500),
                 "local-to-remote conversion");
    expect_equal(sync.remote_to_local(3500, 2050).timestamp_us, static_cast<std::uint64_t>(3000),
                 "remote-to-local conversion");
    const auto round_trip =
        sync.remote_to_local(sync.local_to_remote(4500, 2050).timestamp_us, 2050);
    expect_equal(round_trip.timestamp_us, static_cast<std::uint64_t>(4500),
                 "local remote local round trip");
    expect_equal(sync.snapshot(3100).state, ClockSyncState::Stale, "model becomes stale");
    expect_equal(sync.remote_to_local(3500, 3100).error, TimingError::ClockModelUnavailable,
                 "stale conversion fails");

    std::array<ClockSyncSample, 8> positive_samples{};
    ClockSynchronizer positive(ClockSyncConfig{.min_samples_for_model = 2,
                                               .max_accepted_rtt_us = 1000,
                                               .max_abs_drift_ppm = 1000.0,
                                               .stale_after_us = 10'000'000},
                               positive_samples);
    expect(positive.add_sample(model_sample(1'000'000, 1.00005, 500.0)).ok(),
           "positive drift sample one");
    expect(positive.add_sample(model_sample(2'000'000, 1.00005, 500.0)).ok(),
           "positive drift sample two");
    expect(positive.add_sample(model_sample(3'000'000, 1.00005, 500.0)).ok(),
           "positive drift sample three");
    expect_near(positive.snapshot(3'000'100).drift_ppm, 50.0, 0.1, "positive drift estimated");

    std::array<ClockSyncSample, 8> negative_samples{};
    ClockSynchronizer negative(ClockSyncConfig{.min_samples_for_model = 2,
                                               .max_accepted_rtt_us = 1000,
                                               .max_abs_drift_ppm = 1000.0,
                                               .stale_after_us = 10'000'000},
                               negative_samples);
    expect(negative.add_sample(model_sample(1'000'000, 0.999925, 500.0)).ok(),
           "negative drift sample one");
    expect(negative.add_sample(model_sample(2'000'000, 0.999925, 500.0)).ok(),
           "negative drift sample two");
    expect(negative.add_sample(model_sample(3'000'000, 0.999925, 500.0)).ok(),
           "negative drift sample three");
    expect_near(negative.snapshot(3'000'100).drift_ppm, -75.0, 0.1, "negative drift estimated");

    std::array<ClockSyncSample, 4> degraded_samples{};
    ClockSynchronizer degraded(ClockSyncConfig{.min_samples_for_model = 2,
                                               .max_accepted_rtt_us = 1000,
                                               .max_abs_drift_ppm = 10.0,
                                               .stale_after_us = 10'000'000},
                               degraded_samples);
    expect(degraded.add_sample(model_sample(1'000'000, 1.00005, 0.0)).ok(), "degraded sample one");
    expect(degraded.add_sample(model_sample(2'000'000, 1.00005, 0.0)).ok(), "degraded sample two");
    expect_equal(degraded.snapshot(2'000'100).state, ClockSyncState::Degraded,
                 "drift beyond configured limit is degraded");

    std::array<ClockSyncSample, 2> degenerate_samples{};
    ClockSynchronizer degenerate(ClockSyncConfig{.min_samples_for_model = 2,
                                                 .max_accepted_rtt_us = 1000,
                                                 .max_abs_drift_ppm = 1000.0,
                                                 .stale_after_us = 10'000'000},
                                 degenerate_samples);
    expect(degenerate.add_sample(model_sample(10'000, 1.0, 100.0)).ok(),
           "degenerate fit sample one");
    expect(degenerate.add_sample(model_sample(10'000, 1.0, 200.0)).ok(),
           "degenerate fit sample two");
    expect_equal(degenerate.snapshot(10'100).state, ClockSyncState::Degraded,
                 "degenerate local midpoint fit remains degraded");

    std::array<ClockSyncSample, 3> eviction_samples{};
    ClockSynchronizer eviction(ClockSyncConfig{.min_samples_for_model = 2,
                                               .max_accepted_rtt_us = 1000,
                                               .max_abs_drift_ppm = 1000.0,
                                               .stale_after_us = 10'000'000},
                               eviction_samples);
    expect(eviction.add_sample(model_sample(1000, 1.0, 100.0)).ok(), "eviction sample 1");
    expect(eviction.add_sample(model_sample(2000, 1.0, 100.0)).ok(), "eviction sample 2");
    expect(eviction.add_sample(model_sample(3000, 1.0, 100.0)).ok(), "eviction sample 3");
    expect(eviction.add_sample(model_sample(4000, 1.0, 100.0)).ok(), "eviction sample 4");
    expect_equal(eviction.sample_count(), static_cast<std::size_t>(3), "sample window bounded");
    expect_equal(eviction.local_to_remote(4500, 4050).timestamp_us,
                 static_cast<std::uint64_t>(4600), "evicted window still fits model");
}

void test_sample_rejection_and_one_way_delay() {
    std::array<ClockSyncSample, 4> samples{};
    ClockSynchronizer sync(ClockSyncConfig{.min_samples_for_model = 2,
                                           .max_accepted_rtt_us = 200,
                                           .max_abs_drift_ppm = 1000.0,
                                           .stale_after_us = 10'000},
                           samples);

    expect(sync.add_sample(model_sample(1000, 1.0, 500.0, 100)).ok(), "low RTT sample accepted");
    expect_equal(sync.add_sample(model_sample(2000, 1.0, 500.0, 500)).error,
                 TimingError::RttTooLarge, "high RTT sample rejected");
    expect_equal(sync.snapshot(1050).accepted_samples, static_cast<std::size_t>(1),
                 "rejected high RTT does not enter model");
    expect_equal(sync.snapshot(1050).rejected_samples, static_cast<std::size_t>(1),
                 "rejected sample counted");
    expect(sync.add_sample(model_sample(3000, 1.0, 500.0, 100)).ok(), "second accepted sample");

    const auto delay = sync.estimate_one_way_delay(2000, 1600, 3050);
    expect(delay.ok(), "one-way delay estimates under synchronized model");
    expect_equal(delay.delay_us, static_cast<std::uint64_t>(100), "one-way delay value");
    expect_equal(sync.estimate_one_way_delay(2000, 1400, 3050).error, TimingError::InvalidSample,
                 "negative one-way delay rejected");

    std::array<ClockSyncSample, 2> unsync_samples{};
    ClockSynchronizer unsync(ClockSyncConfig{}, unsync_samples);
    expect_equal(unsync.estimate_one_way_delay(1, 2, 3).error, TimingError::ClockModelUnavailable,
                 "one-way delay unavailable without model");
}

void test_rolling_statistics_and_telemetry() {
    std::array<std::uint64_t, 3> rolling_storage{};
    RollingSampleWindow window(rolling_storage);
    window.add(10);
    window.add(20);
    auto snapshot = window.snapshot();
    expect_equal(snapshot.sample_count, static_cast<std::size_t>(2), "rolling count partial");
    expect_equal(snapshot.latest, static_cast<std::uint64_t>(20), "rolling latest partial");
    expect_equal(snapshot.minimum, static_cast<std::uint64_t>(10), "rolling minimum partial");
    expect_equal(snapshot.maximum, static_cast<std::uint64_t>(20), "rolling maximum partial");
    expect_near(snapshot.mean, 15.0, 0.001, "rolling mean partial");
    expect_near(snapshot.jitter, 0.625, 0.001, "rolling jitter after two samples");
    window.add(50);
    window.add(5);
    snapshot = window.snapshot();
    expect_equal(snapshot.sample_count, static_cast<std::size_t>(3), "rolling count full");
    expect_equal(snapshot.latest, static_cast<std::uint64_t>(5), "rolling latest after overwrite");
    expect_equal(snapshot.minimum, static_cast<std::uint64_t>(5), "rolling min after overwrite");
    expect_equal(snapshot.maximum, static_cast<std::uint64_t>(50), "rolling max after overwrite");
    expect_near(snapshot.mean, 25.0, 0.001, "rolling mean after overwrite");

    std::array<std::uint64_t, 8> rtt_samples{};
    std::array<std::uint64_t, 8> one_way_samples{};
    NetworkTelemetry telemetry(NetworkTelemetryStorage{.rtt_samples = rtt_samples,
                                                       .one_way_delay_samples = one_way_samples});
    telemetry.record_datagram_sent(100);
    telemetry.record_datagram_received(80);
    telemetry.record_send_would_block();
    telemetry.record_receive_would_block();
    telemetry.record_datagram_truncated();
    telemetry.record_gap_detected(2);
    telemetry.record_late_packet();
    telemetry.record_duplicate_packet();
    telemetry.record_nack_generated(3);
    telemetry.record_nack_received();
    telemetry.record_retransmission_sent();
    telemetry.record_retransmission_received_or_observed();
    telemetry.record_fec_block_encoded(2);
    telemetry.record_fec_recovery_attempt(true, 1);
    telemetry.record_fec_recovery_attempt(false, 0);
    telemetry.record_clock_request_sent();
    telemetry.record_clock_response_received();
    telemetry.record_clock_sample_accepted(120);
    telemetry.record_clock_sample_accepted(180);
    telemetry.record_clock_sample_rejected();
    telemetry.record_one_way_delay(55);

    const ClockModelSnapshot clock{.state = ClockSyncState::Synchronized, .accepted_samples = 2};
    const auto telem = telemetry.snapshot(clock);
    expect_equal(telem.counters.datagrams_sent, static_cast<std::uint64_t>(1),
                 "telemetry datagrams sent");
    expect_equal(telem.counters.datagrams_received, static_cast<std::uint64_t>(1),
                 "telemetry datagrams received");
    expect_equal(telem.counters.bytes_sent, static_cast<std::uint64_t>(100),
                 "telemetry bytes sent");
    expect_equal(telem.counters.bytes_received, static_cast<std::uint64_t>(80),
                 "telemetry bytes received");
    expect_equal(telem.counters.sequence_gaps_detected, static_cast<std::uint64_t>(2),
                 "telemetry gap count");
    expect_equal(telem.counters.nack_messages_generated, static_cast<std::uint64_t>(1),
                 "telemetry NACK message count");
    expect_equal(telem.counters.nack_sequences_requested, static_cast<std::uint64_t>(3),
                 "telemetry NACK sequence count");
    expect_equal(telem.counters.fec_recovery_attempts, static_cast<std::uint64_t>(2),
                 "telemetry FEC attempts");
    expect_equal(telem.counters.fec_recovery_successes, static_cast<std::uint64_t>(1),
                 "telemetry FEC success count");
    expect_equal(telem.counters.fec_recovery_failures, static_cast<std::uint64_t>(1),
                 "telemetry FEC failure count");
    expect_equal(telem.counters.fec_data_shards_recovered, static_cast<std::uint64_t>(1),
                 "telemetry FEC recovered data count");
    expect_equal(telem.counters.clock_samples_accepted, static_cast<std::uint64_t>(2),
                 "telemetry clock samples accepted");
    expect_equal(telem.counters.clock_samples_rejected, static_cast<std::uint64_t>(1),
                 "telemetry clock samples rejected");
    expect_equal(telem.rtt.sample_count, static_cast<std::size_t>(2), "telemetry RTT samples");
    expect_equal(telem.one_way_delay.latest, static_cast<std::uint64_t>(55),
                 "telemetry one-way delay latest");
    expect_equal(telem.clock_model.state, ClockSyncState::Synchronized,
                 "telemetry snapshot embeds clock model");

    telemetry.record_datagram_sent(std::numeric_limits<std::uint64_t>::max());
    telemetry.record_datagram_sent(1);
    const auto saturated = telemetry.snapshot(clock);
    expect_equal(saturated.counters.bytes_sent, std::numeric_limits<std::uint64_t>::max(),
                 "telemetry counters saturate");
    expect(saturated.counters.counter_saturated, "telemetry saturation flag set");

    telemetry.reset();
    const auto reset = telemetry.snapshot(ClockModelSnapshot{});
    expect_equal(reset.counters.datagrams_sent, static_cast<std::uint64_t>(0),
                 "telemetry reset clears counters");
    expect_equal(reset.rtt.sample_count, static_cast<std::size_t>(0),
                 "telemetry reset clears RTT samples");
}

void test_loss_fec_telemetry_and_no_feedback() {
    std::array<std::uint64_t, 4> rtt_samples{};
    std::array<std::uint64_t, 4> one_way_samples{};
    NetworkTelemetry telemetry(NetworkTelemetryStorage{.rtt_samples = rtt_samples,
                                                       .one_way_delay_samples = one_way_samples});

    std::array<LossSlot, 16> slots{};
    warpnect::scl::LossDetector detector(
        LossRecoveryConfig{.reorder_delay_us = 0, .renack_interval_us = 10, .max_nack_attempts = 2},
        slots);
    expect(detector.observe(100, 0).ok(), "loss telemetry first packet");
    const auto gap = detector.observe(103, 1);
    expect_equal(gap.kind, LossObservationKind::GapDetected, "loss telemetry gap detected");
    telemetry.record_gap_detected(gap.missing_created);
    expect_equal(detector.observe(101, 2).kind, LossObservationKind::RecoveredMissing,
                 "loss telemetry late packet");
    telemetry.record_late_packet();
    expect_equal(detector.observe(103, 3).kind, LossObservationKind::Duplicate,
                 "loss telemetry duplicate packet");
    telemetry.record_duplicate_packet();
    std::array<NackRequest, 1> nacks{};
    const auto collected = detector.collect_due_nacks(20, PayloadType::Video, nacks);
    expect(collected.ok(), "loss telemetry NACK generated");
    telemetry.record_nack_generated(1);
    telemetry.record_retransmission_sent();
    telemetry.record_retransmission_received_or_observed();

    constexpr FecBlockConfig config{
        .rs = ReedSolomonConfig{.data_shards = 2, .parity_shards = 1},
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = 500,
        .max_wire_datagram_size = 96,
    };
    std::array<std::byte, 512> data_storage{};
    std::array<std::byte, 512> parity_storage{};
    std::array<std::byte, 512> matrix_storage{};
    std::array<std::byte, 32> scratch_storage{};
    FecBlockEncoder encoder(
        config, FecEncoderWorkspace{
                    .data_shard_storage = data_storage,
                    .parity_shard_storage = parity_storage,
                    .rs_workspace = ReedSolomonWorkspace{.matrix_storage = matrix_storage,
                                                         .scratch_storage = scratch_storage},
                });
    std::array<std::byte, 64> packet_a{};
    std::array<std::byte, 64> packet_b{};
    constexpr std::array<std::byte, 3> payload{byte(1), byte(2), byte(3)};
    const auto encoded_a =
        warpnect::scl::encode_packet(packet_header(500, PayloadType::Video), payload, packet_a);
    const auto encoded_b =
        warpnect::scl::encode_packet(packet_header(501, PayloadType::Video), payload, packet_b);
    expect(encoded_a.ok() && encoded_b.ok(), "no-feedback FEC source packets encode");
    const auto original_packet_a = packet_a;
    expect(encoder
               .accept_data_datagram(
                   std::span<const std::byte>(packet_a).first(encoded_a.bytes_written))
               .ok(),
           "no-feedback FEC accepts first");
    expect(encoder
               .accept_data_datagram(
                   std::span<const std::byte>(packet_b).first(encoded_b.bytes_written))
               .ok(),
           "no-feedback FEC accepts second");
    expect(encoder.encode().ok(), "no-feedback FEC parity encodes");
    telemetry.record_fec_block_encoded(1);
    telemetry.record_fec_recovery_attempt(true, 1);

    const auto snapshot = telemetry.snapshot(ClockModelSnapshot{});
    expect_equal(snapshot.counters.sequence_gaps_detected, static_cast<std::uint64_t>(2),
                 "loss telemetry gap counter");
    expect_equal(snapshot.counters.fec_blocks_encoded, static_cast<std::uint64_t>(1),
                 "FEC telemetry block counter");
    expect(packet_a == original_packet_a, "telemetry recording does not mutate packet bytes");
    expect_equal(detector.collect_due_nacks(21, PayloadType::Video, nacks).requests_written,
                 static_cast<std::size_t>(0), "telemetry did not alter NACK timing/state");
}

void test_udp_clock_sync_integration() {
    UdpSocket endpoint_a;
    UdpSocket endpoint_b;
    UdpEndpoint a_local{};
    UdpEndpoint b_local{};
    if (!open_bound_socket_v4(endpoint_a, a_local) || !open_bound_socket_v4(endpoint_b, b_local)) {
        return;
    }

    std::array<PendingClockExchange, 4> pending{};
    ClockExchangeTracker tracker(pending);
    std::array<ClockSyncSample, 4> sync_samples{};
    ClockSynchronizer synchronizer(ClockSyncConfig{.min_samples_for_model = 2,
                                                   .max_accepted_rtt_us = 10'000,
                                                   .max_abs_drift_ppm = 1000.0,
                                                   .stale_after_us = 1'000'000},
                                   sync_samples);
    std::array<std::uint64_t, 4> rtt_samples{};
    std::array<std::uint64_t, 4> one_way_samples{};
    NetworkTelemetry telemetry(NetworkTelemetryStorage{.rtt_samples = rtt_samples,
                                                       .one_way_delay_samples = one_way_samples});

    for (std::uint32_t exchange = 0; exchange < 3; ++exchange) {
        const std::uint64_t t0 = 10'000 + (exchange * 1'000);
        const std::uint64_t t1 = t0 + 600;
        const std::uint64_t t2 = t1 + 20;
        const std::uint64_t t3 = t0 + 220;

        expect(tracker.register_request(exchange, t0).ok(), "UDP clock tracker registers request");
        ClockSyncRequest request{.exchange_id = exchange, .t0_us = t0};
        std::array<std::byte, warpnect::scl::kClockSyncRequestWireSize> request_payload{};
        expect(warpnect::scl::encode_clock_sync_request(request, request_payload).ok(),
               "UDP clock request payload encodes");
        std::array<std::byte, 64> request_packet{};
        const auto request_encoded = warpnect::scl::encode_packet(
            packet_header(7000 + exchange, PayloadType::SessionControl), request_payload,
            request_packet);
        expect(request_encoded.ok(), "UDP clock request packet encodes");
        telemetry.record_clock_request_sent();
        telemetry.record_datagram_sent(request_encoded.bytes_written);
        expect(
            endpoint_a
                .send_to(
                    std::span<const std::byte>(request_packet).first(request_encoded.bytes_written),
                    b_local)
                .ok(),
            "UDP clock request sends");

        std::array<std::byte, 128> receive_buffer{};
        const auto received_request = receive_until_ready(endpoint_b, receive_buffer);
        expect(received_request.ok(), "UDP clock request receives");
        telemetry.record_datagram_received(received_request.bytes_received);
        const auto decoded_request_packet = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received_request.bytes_received));
        expect(decoded_request_packet.ok(), "UDP clock request packet decodes");
        const auto decoded_request =
            warpnect::scl::decode_clock_sync_request(decoded_request_packet.packet.payload);
        expect(decoded_request.ok(), "UDP clock request payload decodes");
        expect_equal(decoded_request.request, request, "UDP clock request matches");

        ClockSyncResponse response{.exchange_id = exchange, .t0_us = t0, .t1_us = t1, .t2_us = t2};
        std::array<std::byte, warpnect::scl::kClockSyncResponseWireSize> response_payload{};
        expect(warpnect::scl::encode_clock_sync_response(response, response_payload).ok(),
               "UDP clock response payload encodes");
        std::array<std::byte, 96> response_packet{};
        const auto response_encoded = warpnect::scl::encode_packet(
            packet_header(8000 + exchange, PayloadType::SessionControl), response_payload,
            response_packet);
        expect(response_encoded.ok(), "UDP clock response packet encodes");
        expect(endpoint_b
                   .send_to(std::span<const std::byte>(response_packet)
                                .first(response_encoded.bytes_written),
                            a_local)
                   .ok(),
               "UDP clock response sends");

        const auto received_response = receive_until_ready(endpoint_a, receive_buffer);
        expect(received_response.ok(), "UDP clock response receives");
        telemetry.record_clock_response_received();
        telemetry.record_datagram_received(received_response.bytes_received);
        const auto decoded_response_packet = warpnect::scl::decode_packet(
            std::span<const std::byte>(receive_buffer).first(received_response.bytes_received));
        expect(decoded_response_packet.ok(), "UDP clock response packet decodes");
        const auto decoded_response =
            warpnect::scl::decode_clock_sync_response(decoded_response_packet.packet.payload);
        expect(decoded_response.ok(), "UDP clock response payload decodes");
        const auto exchange_result = tracker.complete_response(decoded_response.response, t3);
        expect(exchange_result.ok(), "UDP clock exchange completes");
        telemetry.record_clock_sample_accepted(exchange_result.sample.rtt_us);
        expect(synchronizer.add_sample(exchange_result.sample).ok(),
               "UDP clock sample accepted by synchronizer");
    }

    const auto model = synchronizer.snapshot(13'000);
    expect_equal(model.state, ClockSyncState::Synchronized,
                 "multiple UDP clock exchanges produce model");
    const auto snapshot = telemetry.snapshot(model);
    expect_equal(snapshot.counters.clock_requests_sent, static_cast<std::uint64_t>(3),
                 "UDP clock telemetry request count");
    expect_equal(snapshot.counters.clock_responses_received, static_cast<std::uint64_t>(3),
                 "UDP clock telemetry response count");
    expect_equal(snapshot.counters.clock_samples_accepted, static_cast<std::uint64_t>(3),
                 "UDP clock telemetry accepted samples");
    expect(snapshot.rtt.sample_count == 3, "UDP clock telemetry RTT window");
}

} // namespace

int main() {
    static_assert(static_cast<std::uint8_t>(SessionControlType::ClockSyncRequest) == 3);
    static_assert(static_cast<std::uint8_t>(SessionControlType::ClockSyncResponse) == 4);

    test_clock_control_golden_vectors_and_malformed_inputs();
    test_exchange_tracker();
    test_sample_math();
    test_clock_model_fit_conversion_and_quality();
    test_sample_rejection_and_one_way_delay();
    test_rolling_statistics_and_telemetry();
    test_loss_fec_telemetry_and_no_feedback();
    test_udp_clock_sync_integration();

    if (failures != 0) {
        std::cerr << failures << " SCL timing/telemetry test failure(s)\n";
        return 1;
    }

    std::cout << "SCL timing and telemetry tests passed\n";
    return 0;
}
