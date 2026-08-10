#include "packet_codec.h"
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
    expect_equal(event.error, VideoError::ReassemblyTimeout, "timeout error is preserved");
    expect_equal(runtime.snapshot().reassembly_timeouts, 1ULL, "timeout is counted");
}

int run_all_tests() {
    test_stream_config_event_and_exact_csd();
    test_keyframe_gate_and_direct_decoder_fill();
    test_frame_ordering_waits_for_older_frame();
    test_reassembly_timeout_marks_discontinuity();
    return failures == 0 ? 0 : 1;
}

} // namespace

int main() {
    return run_all_tests();
}
