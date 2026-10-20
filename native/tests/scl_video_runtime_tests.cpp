#include "packet_codec.h"
#include "monotonic_time.h"
#include "video_packetizer.h"
#include "video_protocol.h"
#include "video_receiver_runtime.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using warpnect::scl::CsdEntryView;
using warpnect::scl::DatagramSink;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::PacketHeader;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::VideoError;
using warpnect::scl::VideoMessageType;
using warpnect::scl::VideoPacketizer;
using warpnect::scl::VideoPacketizerConfig;
using warpnect::scl::VideoReceiverConfig;
using warpnect::scl::VideoReceiverEventType;
using warpnect::scl::VideoReceiverRuntime;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoTransportFecConfig;

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

[[nodiscard]] bool bytes_equal(std::span<const std::byte> lhs,
                               std::span<const std::byte> rhs) noexcept {
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin());
}

[[nodiscard]] std::vector<std::byte> make_bytes(std::size_t size,
                                                std::uint8_t seed = 11) {
    std::vector<std::byte> bytes(size);
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 29U + seed) & 0xFFU));
    }
    return bytes;
}

[[nodiscard]] std::span<const std::byte> as_bytes(const std::vector<std::byte>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

template <std::size_t Size>
[[nodiscard]] std::span<const std::byte> as_bytes(const std::array<std::byte, Size>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

[[nodiscard]] std::vector<std::byte> to_vector(std::span<const std::byte> bytes) {
    return std::vector<std::byte>(bytes.begin(), bytes.end());
}

struct CapturedDatagram final {
    std::vector<std::byte> bytes{};
    PacketHeader header{};
};

class CollectingSink final : public DatagramSink {
  public:
    std::vector<CapturedDatagram> datagrams{};

    [[nodiscard]] VideoStatus send(std::span<const std::byte> datagram) noexcept override {
        const auto decoded = warpnect::scl::decode_packet(datagram);
        if (!decoded.ok()) {
            return VideoStatus{.error = VideoError::PacketEncodeFailed};
        }
        datagrams.push_back(CapturedDatagram{
            .bytes = to_vector(datagram),
            .header = decoded.packet.header,
        });
        return VideoStatus{};
    }
};

[[nodiscard]] VideoReceiverConfig receiver_config(std::size_t max_wire = 128,
                                                  std::size_t slots = 8,
                                                  std::size_t ready = 8) noexcept {
    return VideoReceiverConfig{
        .local_endpoint = UdpEndpoint::loopback_v4(0),
        .remote_endpoint = UdpEndpoint::loopback_v4(49000),
        .restrict_remote_endpoint = false,
        .max_wire_datagram_size = max_wire,
        .max_logical_payload_size = 8192,
        .reassembly_slot_count = slots,
        .ready_slot_count = ready,
        .loss =
            LossRecoveryConfig{
                .reorder_delay_us = 25,
                .renack_interval_us = 50,
                .max_nack_attempts = 2,
            },
        .loss_slot_count = 64,
        .max_nacks_per_pump = 8,
        .initial_control_sequence = 7000,
        .fec = VideoTransportFecConfig{},
        .reassembly_timeout_us = 50,
    };
}

[[nodiscard]] bool feed(VideoReceiverRuntime& runtime,
                        const std::vector<CapturedDatagram>& datagrams,
                        std::uint64_t now_us = 1000) {
    const UdpEndpoint source = UdpEndpoint::loopback_v4(49000);
    for (const CapturedDatagram& datagram : datagrams) {
        const VideoStatus status = runtime.accept_datagram(as_bytes(datagram.bytes), source, now_us);
        expect(status.ok(), "receiver accepts datagram");
        if (!status.ok()) {
            return false;
        }
    }
    return true;
}

[[nodiscard]] std::vector<CapturedDatagram>
packetize_stream_config(std::size_t max_wire, std::uint32_t sequence,
                        std::uint32_t generation,
                        std::span<const CsdEntryView> csd_entries) {
    std::vector<std::byte> scratch(max_wire);
    VideoPacketizer packetizer(scratch);
    CollectingSink sink;
    const auto emitted = packetizer.emit_stream_config(
        VideoPacketizerConfig{.max_datagram_size = max_wire},
        sequence,
        generation,
        1280,
        720,
        csd_entries,
        sink);
    expect(emitted.ok(), "stream config packetizes");
    return sink.datagrams;
}

[[nodiscard]] std::vector<CapturedDatagram>
packetize_access_unit(std::size_t max_wire, std::uint32_t sequence,
                      std::uint32_t generation, std::uint32_t frame_id,
                      std::uint64_t pts_us, bool keyframe,
                      std::span<const std::byte> au) {
    std::vector<std::byte> scratch(max_wire);
    VideoPacketizer packetizer(scratch);
    CollectingSink sink;
    const auto emitted = packetizer.emit_access_unit(
        VideoPacketizerConfig{.max_datagram_size = max_wire},
        sequence,
        pts_us,
        generation,
        frame_id,
        keyframe,
        au,
        sink);
    expect(emitted.ok(), "access unit packetizes");
    return sink.datagrams;
}

void test_stream_config_event_and_exact_csd() {
    const std::array<std::byte, 3> csd0{byte(0x67), byte(0x42), byte(0x00)};
    const std::array<std::byte, 2> csd1{byte(0x68), byte(0xCE)};
    const std::array<CsdEntryView, 2> entries{
        CsdEntryView{.bytes = as_bytes(csd0)},
        CsdEntryView{.bytes = as_bytes(csd1)},
    };
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "runtime opens");
    const auto datagrams = packetize_stream_config(128, 10, 1, entries);
    expect(feed(runtime, datagrams), "stream config datagrams feed");

    const auto event = runtime.pump(0);
    expect_equal(event.type, VideoReceiverEventType::StreamConfigReady,
                 "stream config event publishes");
    expect_equal(event.config_generation, 1U, "stream config generation publishes");
    expect_equal(event.width, static_cast<std::uint16_t>(1280), "stream config width publishes");
    expect_equal(event.height, static_cast<std::uint16_t>(720), "stream config height publishes");

    const auto latest = runtime.latest_stream_config();
    expect_equal(latest.config_generation, 1U, "latest config generation stores");
    expect_equal(latest.csd_count, static_cast<std::uint8_t>(2), "latest CSD count stores");
    expect(bytes_equal(runtime.latest_csd_entry(0), as_bytes(csd0)), "first CSD is exact");
    expect(bytes_equal(runtime.latest_csd_entry(1), as_bytes(csd1)), "second CSD is exact");
}

void test_keyframe_gate_and_direct_decoder_fill() {
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "runtime opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 20, 1, entries)), "config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "config generation activates");

    const auto non_key = make_bytes(24, 3);
    const auto key = make_bytes(25, 9);
    expect(feed(runtime, packetize_access_unit(128, 30, 1, 1, 100, false, as_bytes(non_key))),
           "non-key AU feeds");
    expect(feed(runtime, packetize_access_unit(128, 31, 1, 2, 200, true, as_bytes(key))),
           "key AU feeds");

    std::array<std::byte, 64> output{};
    const auto filled = runtime.fill_decoder_input(output);
    expect(filled.error == VideoError::None, "decoder fill succeeds at first keyframe");
    expect(filled.has_access_unit, "decoder fill returns AU");
    expect_equal(filled.frame_id, 2U, "non-key before first keyframe is skipped");
    expect_equal(filled.presentation_time_us, 200ULL, "PTS is preserved");
    expect(filled.keyframe, "keyframe metadata is preserved");
    expect(bytes_equal(std::span<const std::byte>(output).first(filled.size), as_bytes(key)),
           "filled decoder bytes are exact");
    expect_equal(runtime.snapshot().non_keyframes_dropped_awaiting_keyframe, 1ULL,
                 "non-key drop is counted");
}

void test_frame_ordering_waits_for_older_frame() {
    VideoReceiverRuntime runtime(receiver_config(96));
    expect(runtime.open().ok(), "runtime opens");
    const std::array<std::byte, 2> csd{byte(7), byte(8)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(96, 100, 1, entries)), "config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "config activates");

    const auto frame1 = make_bytes(140, 1);
    const auto frame2 = make_bytes(140, 2);
    const auto frame2_datagrams = packetize_access_unit(96, 220, 1, 2, 2000, true, as_bytes(frame2));
    const auto frame1_datagrams = packetize_access_unit(96, 200, 1, 1, 1000, true, as_bytes(frame1));
    expect(!frame1_datagrams.empty(), "older frame fixture emits datagrams");
    expect(feed(runtime, std::vector<CapturedDatagram>{frame1_datagrams.front()}),
           "older frame begins incomplete");
    expect(feed(runtime, frame2_datagrams), "later frame feeds first");
    expect_equal(runtime.snapshot().ready_access_units, static_cast<std::size_t>(0),
                 "later frame is not ready before older frame");
    expect(feed(runtime, frame1_datagrams), "older frame feeds second");

    std::vector<std::byte> output(256);
    auto filled = runtime.fill_decoder_input(output);
    expect(filled.error == VideoError::None, "first ordered fill succeeds");
    expect_equal(filled.frame_id, 1U, "older frame fills first");
    expect(bytes_equal(std::span<const std::byte>(output).first(filled.size), as_bytes(frame1)),
           "older frame bytes fill exactly");

    filled = runtime.fill_decoder_input(output);
    expect(filled.error == VideoError::None, "second ordered fill succeeds");
    expect_equal(filled.frame_id, 2U, "later frame fills second");
    expect(bytes_equal(std::span<const std::byte>(output).first(filled.size), as_bytes(frame2)),
           "later frame bytes fill exactly");
}

void test_reassembly_timeout_marks_discontinuity() {
    VideoReceiverRuntime runtime(receiver_config(96));
    expect(runtime.open().ok(), "runtime opens for timeout pump");
    const auto au = make_bytes(200, 5);
    auto fragments = packetize_access_unit(96, 300, 1, 4, 444, true, as_bytes(au));
    expect(fragments.size() > 1, "timeout fixture fragments");
    fragments.resize(1);
    expect(feed(runtime, fragments, 1000), "partial frame feeds");

    const auto event = runtime.pump(0);
    expect_equal(event.type, VideoReceiverEventType::Discontinuity,
                 "timeout publishes discontinuity");
    expect_equal(event.error, VideoError::RecoveryDeadlineExceeded,
                 "freshness deadline error is preserved");
    expect_equal(runtime.snapshot().reassembly_timeouts, 1ULL, "timeout is counted");
    expect_equal(runtime.snapshot().stale_frames_released, 1ULL, "stale release is counted");
}

void test_ready_frame_does_not_starve_late_stream_config() {
    warpnect::scl::UdpSocket receiver_socket;
    expect(receiver_socket.open(warpnect::scl::IpVersion::V4).ok(), "receiver socket opens");
    expect(receiver_socket.bind(UdpEndpoint::loopback_v4(0)).ok(), "receiver socket binds");
    const auto receiver_endpoint = receiver_socket.local_endpoint();
    expect(receiver_endpoint.ok(), "receiver endpoint is available");
    if (!receiver_endpoint.ok()) return;

    auto config = receiver_config();
    config.local_endpoint = receiver_endpoint.endpoint;
    config.clock_sync_interval_us = 0;
    VideoReceiverRuntime runtime(config);
    runtime.adopt_prebound_socket(std::move(receiver_socket));
    expect(runtime.open().ok(), "prebound runtime opens");
    const auto frame = make_bytes(24, 19);
    expect(feed(runtime, packetize_access_unit(128, 11, 1, 1, 100, true, as_bytes(frame)),
                warpnect::scl::monotonic_time_now_us().value), "frame precedes its config");
    expect_equal(runtime.pump(0).type, VideoReceiverEventType::AccessUnitReady,
                 "pending frame is observable without consuming it");

    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    warpnect::scl::UdpSocket sender_socket;
    expect(sender_socket.open(warpnect::scl::IpVersion::V4).ok(), "config sender opens");
    const auto configs = packetize_stream_config(128, 12, 1, entries);
    for (const auto& datagram : configs) {
        expect(sender_socket.send_to(as_bytes(datagram.bytes), receiver_endpoint.endpoint).ok(),
               "late config enters actual socket");
    }
    bool config_received = false;
    for (int attempt = 0; attempt < 8; ++attempt) {
        if (runtime.pump(1'000).type == VideoReceiverEventType::StreamConfigReady) {
            config_received = true;
            break;
        }
    }
    expect(config_received, "unconsumed frame must not starve socket config delivery");
    expect_equal(runtime.snapshot().stream_configs_received, 1ULL, "late config is received");
    if (!config_received) return;
    expect(runtime.activate_config_generation(1).ok(), "late config can activate");
    std::array<std::byte, 64> output{};
    const auto filled = runtime.fill_decoder_input(output);
    expect(filled.has_access_unit, "original queued frame remains available");
    expect(bytes_equal(std::span<const std::byte>(output).first(filled.size), as_bytes(frame)),
           "queued frame is not altered or consumed by the socket pump");
}

void test_full_unconfigured_window_admits_fragmented_config_without_growth() {
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "unconfigured full-window runtime opens");
    const auto frame = make_bytes(24, 19);
    for (std::uint32_t index = 0; index < 8; ++index) {
        expect(feed(runtime, packetize_access_unit(128, 100 + index, 1, index + 1,
                                                  1000 + index, true, as_bytes(frame))),
               "unconfigured frame fills existing window");
    }
    expect_equal(runtime.snapshot().ready_access_units, static_cast<std::size_t>(8),
                 "existing eight-slot window is full");
    const auto csd = make_bytes(100, 7);
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    const auto config = packetize_stream_config(128, 108, 1, entries);
    expect(config.size() > 1, "late config fixture is fragmented");
    if (!feed(runtime, config)) return;
    expect_equal(runtime.pump(0).type, VideoReceiverEventType::StreamConfigReady,
                 "full unconfigured frames do not exclude late config");
    expect_equal(runtime.snapshot().reassembly_slots_high_water, static_cast<std::size_t>(8),
                 "config admission does not grow the window");
    expect_equal(runtime.snapshot().stale_frames_released, 1ULL,
                 "one oldest unconfigured frame is explicitly released");
    expect(runtime.activate_config_generation(1).ok(), "late config can activate");
    std::array<std::byte, 64> output{};
    const auto filled = runtime.fill_decoder_input(output);
    expect(filled.has_access_unit, "remaining current-generation frames can decode");
    expect_equal(filled.frame_id, 2U, "only the oldest pending frame was released");
    expect(bytes_equal(std::span<const std::byte>(output).first(filled.size), as_bytes(frame)),
           "remaining frame contents are unchanged");
}

void test_active_config_full_window_keeps_existing_backpressure() {
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "active full-window runtime opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 99, 1, entries)), "active config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "active config activates");
    const auto frame = make_bytes(24, 19);
    for (std::uint32_t index = 0; index < 8; ++index) {
        expect(feed(runtime, packetize_access_unit(128, 100 + index, 1, index + 1,
                                                  1000 + index, true, as_bytes(frame))),
               "active frames fill existing window");
    }
    const auto extra = packetize_access_unit(128, 108, 1, 9, 1009, true, as_bytes(frame));
    expect_equal(runtime.accept_datagram(as_bytes(extra.front().bytes),
                                        UdpEndpoint::loopback_v4(49000), 1000).error,
                 VideoError::ReassemblyWindowFull, "active stream retains bounded backpressure");
    expect_equal(runtime.snapshot().stale_frames_released, 0ULL,
                 "startup exception does not drop an active frame");
}

void test_active_ready_window_does_not_read_past_decoder_backpressure() {
    warpnect::scl::UdpSocket receiver_socket;
    expect(receiver_socket.open(warpnect::scl::IpVersion::V4).ok(), "active receiver opens");
    expect(receiver_socket.bind(UdpEndpoint::loopback_v4(0)).ok(), "active receiver binds");
    const auto endpoint = receiver_socket.local_endpoint();
    expect(endpoint.ok(), "active receiver endpoint exists");
    if (!endpoint.ok()) return;
    auto config = receiver_config();
    config.local_endpoint = endpoint.endpoint;
    config.clock_sync_interval_us = 0;
    VideoReceiverRuntime runtime(config);
    runtime.adopt_prebound_socket(std::move(receiver_socket));
    expect(runtime.open().ok(), "active prebound runtime opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 99, 1, entries)), "config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "decoder generation activates");
    const auto frame = make_bytes(24, 19);
    for (std::uint32_t i = 0; i < 8; ++i) {
        expect(feed(runtime, packetize_access_unit(128, 100 + i, 1, i + 1,
                                                  1000 + i, true, as_bytes(frame))),
               "complete frames fill the bounded ready window");
    }
    warpnect::scl::UdpSocket sender;
    expect(sender.open(warpnect::scl::IpVersion::V4).ok(), "next frame sender opens");
    const auto extra = packetize_access_unit(128, 108, 1, 9, 1009, true, as_bytes(frame));
    for (const auto& datagram : extra) {
        expect(sender.send_to(as_bytes(datagram.bytes), endpoint.endpoint).ok(),
               "next active frame enters actual UDP socket");
    }
    const auto before = runtime.snapshot();
    for (int i = 0; i < 16; ++i) {
        const auto event = runtime.pump(1'000);
        expect_equal(event.type, VideoReceiverEventType::AccessUnitReady,
                     "decoder backpressure is not a fatal receive error");
        expect_equal(event.frame_id, 1U, "oldest ready frame retains ownership");
    }
    expect_equal(runtime.snapshot().datagrams_received, before.datagrams_received,
                 "active pump does not consume the next packet before decoder admission");
    expect_equal(runtime.snapshot().reassembly_window_full, 0ULL,
                 "readiness cannot manufacture a full-window failure");
    std::array<std::byte, 64> output{};
    for (std::uint32_t i = 1; i <= 8; ++i) {
        const auto filled = runtime.fill_decoder_input(output);
        expect(filled.has_access_unit, "existing decoder frames remain available");
        expect_equal(filled.frame_id, i, "queued frame ordering is unchanged");
    }
    const auto next = runtime.pump(10'000);
    expect_equal(next.type, VideoReceiverEventType::AccessUnitReady,
                 "socket progress resumes after the decoder drains");
    const auto filled = runtime.fill_decoder_input(output);
    expect(filled.has_access_unit, "post-pressure decoder fill progresses");
    expect_equal(filled.frame_id, 9U, "next frame is delivered exactly once");
    expect(bytes_equal(std::span<const std::byte>(output).first(filled.size), as_bytes(frame)),
           "post-pressure frame bytes remain intact");
    expect_equal(runtime.snapshot().reassembly_slots_high_water, static_cast<std::size_t>(8),
                 "no reassembly window growth");
    expect_equal(runtime.snapshot().ready_access_units, static_cast<std::size_t>(0),
                 "decoder releases all ready ownership");
}

void test_duplicate_access_unit_is_not_recreated_after_decoder_delivery() {
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "duplicate replay runtime opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 99, 1, entries)), "duplicate config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "duplicate config activates");
    const auto frame = make_bytes(24, 17);
    const auto original = packetize_access_unit(128, 100, 1, 1, 1000, true, as_bytes(frame));
    expect(feed(runtime, original), "original unit feeds");
    std::array<std::byte, 64> output{};
    expect(runtime.fill_decoder_input(output).has_access_unit, "original unit is delivered");
    expect(feed(runtime, original), "redundant authenticated inner datagram is harmless");
    expect_equal(runtime.fill_decoder_input(output).has_access_unit, false,
                 "an already delivered unit must not be recreated");
    expect_equal(runtime.snapshot().access_units_delivered, 1ULL,
                 "decoder delivery is exactly once for this sequence");
}

void test_duplicate_retransmission_cannot_exhaust_a_full_active_window() {
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "full-window duplicate runtime opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 99, 1, entries)), "full-window config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "full-window config activates");
    const auto frame = make_bytes(24, 23);
    const auto old = packetize_access_unit(128, 100, 1, 1, 1000, true, as_bytes(frame));
    expect(feed(runtime, old), "old unit feeds");
    std::array<std::byte, 64> output{};
    expect(runtime.fill_decoder_input(output).has_access_unit, "old unit is consumed");
    for (std::uint32_t i = 1; i <= 8; ++i) {
        expect(feed(runtime, packetize_access_unit(128, 100 + i, 1, 1 + i,
                                                  1000 + i, true, as_bytes(frame))),
               "fresh units fill the existing bounded window");
    }
    expect_equal(runtime.accept_datagram(as_bytes(old.front().bytes),
                                        UdpEndpoint::loopback_v4(49000), 1009).error,
                 VideoError::None, "old retransmission must not allocate a ninth slot");
    expect_equal(runtime.snapshot().reassembly_window_full, 0ULL,
                 "duplicate input cannot manufacture capacity exhaustion");
    for (std::uint32_t i = 2; i <= 9; ++i) {
        const auto filled = runtime.fill_decoder_input(output);
        expect(filled.has_access_unit, "fresh queued unit remains owned");
        expect_equal(filled.frame_id, i, "duplicate does not reorder fresh units");
    }
    expect_equal(runtime.snapshot().reassembly_slots_high_water, static_cast<std::size_t>(8),
                 "no slot capacity increase");
}

void test_late_duplicate_fragment_cannot_create_an_ordering_barrier() {
    VideoReceiverRuntime runtime(receiver_config());
    expect(runtime.open().ok(), "fragment replay runtime opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 99, 1, entries)), "fragment config feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "fragment config activates");
    const auto large_frame = make_bytes(256, 29);
    const auto old = packetize_access_unit(128, 100, 1, 1, 1000, true, as_bytes(large_frame));
    expect(old.size() > 1, "the old access unit genuinely spans multiple datagrams");
    expect(feed(runtime, old), "the complete original access unit feeds");
    std::array<std::byte, 512> output{};
    expect(runtime.fill_decoder_input(output).has_access_unit, "original decoder delivery releases its slot");
    expect(runtime.accept_datagram(as_bytes(old.front().bytes),
                                   UdpEndpoint::loopback_v4(49000), 1001).ok(),
           "one late redundant fragment is harmless");
    const auto fresh = make_bytes(24, 31);
    const auto next_sequence = 100U + static_cast<std::uint32_t>(old.size());
    for (std::uint32_t i = 0; i < 7; ++i) {
        expect(feed(runtime, packetize_access_unit(128, next_sequence + i, 1, 2 + i,
                                                  1002 + i, true, as_bytes(fresh))),
               "seven newer complete units feed");
    }
    const auto before_next = runtime.snapshot();
    std::cout << "DUPLICATE_FRAGMENT_REPLAY ready=" << before_next.ready_access_units
              << " slots=" << before_next.reassembly_slots_used << '\n';
    expect_equal(before_next.ready_access_units, static_cast<std::size_t>(7),
                 "a consumed fragment cannot block newer complete frames");
    expect_equal(before_next.reassembly_slots_used, static_cast<std::size_t>(7),
                 "no ghost incomplete slot remains after an old fragment");
    const auto extra = packetize_access_unit(128, next_sequence + 7, 1, 9, 1009, true, as_bytes(fresh));
    expect(runtime.accept_datagram(as_bytes(extra.front().bytes),
                                   UdpEndpoint::loopback_v4(49000), 1009).ok(),
           "eighth fresh access unit still fits the original window");
    expect_equal(runtime.snapshot().reassembly_window_full, 0ULL,
                 "an old fragment cannot manufacture the physical pressure fingerprint");
    expect_equal(runtime.snapshot().reassembly_slots_high_water, static_cast<std::size_t>(8),
                 "the fixed eight-slot contract is unchanged");
}

void test_loss_window_overflow_rebases_without_permanent_resync() {
    auto config = receiver_config(128);
    config.clock_sync_interval_us = 0;
    VideoReceiverRuntime runtime(config);
    expect(runtime.open().ok(), "loss-window replay opens");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 0, 1, entries)), "loss replay config");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "loss replay generation activates");
    const auto frame = make_bytes(24, 13);
    std::array<std::byte, 64> output{};
    expect(feed(runtime, packetize_access_unit(128, 1, 1, 1, 1000, true, as_bytes(frame))),
           "initial keyframe arrives");
    (void)runtime.pump(0);
    expect(runtime.fill_decoder_input(output).has_access_unit, "initial keyframe drains");

    // A Surface pause/socket loss may exceed the bounded NACK window, not the Session.
    expect(feed(runtime, packetize_access_unit(128, 200, 1, 2, 2000, true, as_bytes(frame))),
           "post-gap keyframe arrives");
    expect_equal(runtime.pump(0).type, VideoReceiverEventType::Discontinuity,
                 "one gap requests decoder resynchronization");
    expect(runtime.fill_decoder_input(output).has_access_unit, "post-gap keyframe drains");
    const auto discontinuities = runtime.snapshot().discontinuities;
    for (std::uint32_t sequence = 201; sequence < 205; ++sequence) {
        expect(feed(runtime, packetize_access_unit(128, sequence, 1, sequence - 198,
                                                   2000 + sequence, false, as_bytes(frame))),
               "continuous post-gap delta arrives");
        expect_equal(runtime.pump(0).type, VideoReceiverEventType::AccessUnitReady,
                     "continuous data must not request the same resync forever");
        expect(runtime.fill_decoder_input(output).has_access_unit,
               "delta frames remain decodable after the recovered keyframe");
    }
    expect_equal(runtime.snapshot().discontinuities, discontinuities,
                 "loss-window overflow is rebased exactly once");
    expect_equal(runtime.snapshot().active_config_generation, 1U,
                 "loss recovery does not replace stream generation");
    expect_equal(runtime.snapshot().reassembly_window_full, 0ULL,
                 "loss recovery does not grow or exhaust the reassembly window");
}

void test_rejected_clock_diagnostics_do_not_fail_media_delivery() {
    warpnect::scl::UdpSocket peer;
    expect(peer.open(warpnect::scl::IpVersion::V4).ok(), "clock peer opens");
    expect(peer.bind(UdpEndpoint::loopback_v4(0)).ok(), "clock peer binds");
    const auto endpoint = peer.local_endpoint();
    expect(endpoint.ok(), "clock peer endpoint resolves");
    auto config = receiver_config(128);
    config.remote_endpoint = endpoint.endpoint;
    config.clock_sync_interval_us = 1;
    VideoReceiverRuntime runtime(config);
    expect(runtime.open().ok(), "clock rejection replay opens");
    const auto request = [&]() {
        (void)runtime.pump(0);
        expect(peer.wait_readable(10'000).readable, "actual clock request arrives");
        std::array<std::byte, 128> wire{};
        const auto received = peer.receive_from(wire);
        expect(received.ok(), "clock request receives");
        const auto packet = warpnect::scl::decode_packet(
            std::span<const std::byte>(wire).first(received.bytes_received));
        expect(packet.ok(), "clock request packet decodes");
        const auto decoded = warpnect::scl::decode_clock_sync_request(packet.packet.payload);
        expect(decoded.ok(), "clock request payload decodes");
        return decoded.request;
    };
    std::uint32_t control_sequence = 0;
    const auto respond = [&](const warpnect::scl::ClockSyncResponse& response,
                             std::uint64_t now_us, bool malformed = false) {
        std::array<std::byte, warpnect::scl::kClockSyncResponseWireSize> payload{};
        expect(warpnect::scl::encode_clock_sync_response(response, payload).ok(),
               "clock response encodes");
        if (malformed) payload[1] = byte(0x7F);
        std::array<std::byte, 128> wire{};
        const auto encoded = warpnect::scl::encode_packet(
            PacketHeader{.sequence_number = control_sequence++,
                         .payload_type = warpnect::scl::PayloadType::SessionControl},
            payload, wire);
        expect(encoded.ok(), "clock response packet encodes");
        return runtime.accept_datagram(
            std::span<const std::byte>(wire).first(encoded.bytes_written), endpoint.endpoint, now_us);
    };
    const auto first = request();
    auto response = warpnect::scl::ClockSyncResponse{
        .exchange_id = first.exchange_id, .t0_us = first.t0_us,
        .t1_us = first.t0_us + 10, .t2_us = first.t0_us + 20};
    auto unknown = response;
    unknown.exchange_id += 100;
    expect(respond(unknown, first.t0_us + 100).ok(),
           "uncorrelated clock sample is dropped without failing media");
    expect(respond(response, first.t0_us + 100, true).ok(),
           "malformed clock sample remains diagnostic-only");
    expect(respond(response, first.t0_us + config.clock_sync.max_accepted_rtt_us + 1000).ok(),
           "late clock sample is rejected without a fatal transport event");
    expect_equal(runtime.snapshot().clock_sync_responses_received, 0ULL,
                 "rejected samples are not accepted clock evidence");
    const auto second = request();
    response = {.exchange_id = second.exchange_id, .t0_us = second.t0_us,
                .t1_us = second.t0_us + 10, .t2_us = second.t0_us + 20};
    expect(respond(response, second.t0_us + 100).ok(), "fresh clock sample still accepts");
    expect_equal(runtime.snapshot().clock_sync_responses_received, 1ULL,
                 "only fresh correlated sample updates the model");
    const std::array<std::byte, 2> csd{byte(1), byte(2)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(feed(runtime, packetize_stream_config(128, 0, 1, entries)), "media config still feeds");
    (void)runtime.pump(0);
    expect(runtime.activate_config_generation(1).ok(), "media generation still activates");
    const auto frame = make_bytes(24, 3);
    expect(feed(runtime, packetize_access_unit(128, 1, 1, 1, 1000, true, as_bytes(frame))),
           "media keyframe still feeds");
    std::array<std::byte, 64> output{};
    expect(runtime.fill_decoder_input(output).has_access_unit, "media progresses after diagnostic rejection");
}

int run_all_tests() {
    test_stream_config_event_and_exact_csd();
    test_keyframe_gate_and_direct_decoder_fill();
    test_frame_ordering_waits_for_older_frame();
    test_reassembly_timeout_marks_discontinuity();
    test_ready_frame_does_not_starve_late_stream_config();
    test_full_unconfigured_window_admits_fragmented_config_without_growth();
    test_active_config_full_window_keeps_existing_backpressure();
    test_active_ready_window_does_not_read_past_decoder_backpressure();
    test_duplicate_access_unit_is_not_recreated_after_decoder_delivery();
    test_duplicate_retransmission_cannot_exhaust_a_full_active_window();
    test_late_duplicate_fragment_cannot_create_an_ordering_barrier();
    test_loss_window_overflow_rebases_without_permanent_resync();
    test_rejected_clock_diagnostics_do_not_fail_media_delivery();
    return failures == 0 ? 0 : 1;
}

} // namespace

int main() {
    return run_all_tests();
}
