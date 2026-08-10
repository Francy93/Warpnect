#include "packet_codec.h"
#include "video_receiver_runtime.h"
#include "video_resync_control.h"
#include "video_transport.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::CsdEntryView;
using warpnect::scl::IpVersion;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::UdpError;
using warpnect::scl::UdpReceiveResult;
using warpnect::scl::UdpSocket;
using warpnect::scl::VideoError;
using warpnect::scl::VideoReceiverConfig;
using warpnect::scl::VideoReceiverRuntime;
using warpnect::scl::VideoResyncReason;
using warpnect::scl::VideoResyncRequest;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoTransportFecConfig;
using warpnect::scl::VideoTransportSender;
using warpnect::scl::VideoTransportSenderConfig;
using warpnect::scl::VideoTransportSenderWorkspace;

int failures = 0;
int skips = 0;

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

void skip(std::string_view message) {
    std::cout << "SKIP: " << message << '\n';
    ++skips;
}

template <std::size_t Size>
[[nodiscard]] std::span<const std::byte> as_bytes(const std::array<std::byte, Size>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
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
    if (!receiver.open(IpVersion::V4).ok() ||
        !receiver.bind(UdpEndpoint::loopback_v4(0)).ok()) {
        skip("IPv4 loopback socket unavailable");
        return false;
    }
    const auto local = receiver.local_endpoint();
    expect(local.ok(), "loopback endpoint is available");
    if (!local.ok()) {
        return false;
    }
    endpoint = local.endpoint;
    return true;
}

struct SenderStorage final {
    std::vector<std::byte> datagram_scratch{};
    std::vector<std::byte> cache_storage{};
    std::vector<warpnect::scl::RetransmissionEntry> cache_entries{};

    explicit SenderStorage(std::size_t max_wire, std::size_t cache_slots)
        : datagram_scratch(max_wire),
          cache_storage(max_wire * cache_slots),
          cache_entries(cache_slots) {}

    [[nodiscard]] VideoTransportSenderWorkspace workspace() noexcept {
        return VideoTransportSenderWorkspace{
            .datagram_scratch = datagram_scratch,
            .retransmission_datagram_storage = cache_storage,
            .retransmission_entries = cache_entries,
        };
    }
};

void test_video_resync_golden_and_malformed() {
    std::array<std::byte, warpnect::scl::kVideoResyncRequestWireSize> output{};
    const VideoStatus encoded = warpnect::scl::encode_video_resync_request(
        VideoResyncRequest{
            .reason = VideoResyncReason::Discontinuity,
            .receiver_config_generation = 0x01020304U,
        },
        output);
    const std::array<std::byte, warpnect::scl::kVideoResyncRequestWireSize> expected{
        byte(0x05), byte(0x01), byte(0x03), byte(0x00),
        byte(0x01), byte(0x02), byte(0x03), byte(0x04),
    };
    expect(encoded.ok(), "VideoResyncRequest encodes");
    expect(output == expected, "VideoResyncRequest bytes match golden vector");

    const auto decoded = warpnect::scl::decode_video_resync_request(output);
    expect(decoded.ok(), "VideoResyncRequest decodes");
    expect_equal(decoded.request.reason, VideoResyncReason::Discontinuity,
                 "resync reason decodes");
    expect_equal(decoded.request.receiver_config_generation, 0x01020304U,
                 "receiver generation decodes");

    auto bad_version = output;
    bad_version[1] = byte(0x02);
    expect_equal(warpnect::scl::decode_video_resync_request(bad_version).error,
                 VideoError::UnsupportedVideoVersion,
                 "unsupported resync version rejects");

    auto bad_reason = output;
    bad_reason[2] = byte(0x7F);
    expect_equal(warpnect::scl::decode_video_resync_request(bad_reason).error,
                 VideoError::ResyncRequestMalformed,
                 "unknown resync reason rejects");

    auto bad_reserved = output;
    bad_reserved[3] = byte(0x01);
    expect_equal(warpnect::scl::decode_video_resync_request(bad_reserved).error,
                 VideoError::ResyncRequestMalformed,
                 "reserved byte rejects");
}

void test_receiver_emits_video_resync_request() {
    UdpSocket control_receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver(control_receiver, endpoint)) {
        return;
    }

    VideoReceiverRuntime runtime(
        VideoReceiverConfig{
            .local_endpoint = UdpEndpoint::loopback_v4(0),
            .remote_endpoint = endpoint,
            .restrict_remote_endpoint = false,
            .max_wire_datagram_size = 256,
            .max_logical_payload_size = 4096,
            .reassembly_slot_count = 4,
            .ready_slot_count = 4,
            .loss = warpnect::scl::LossRecoveryConfig{
                .reorder_delay_us = 25,
                .renack_interval_us = 50,
                .max_nack_attempts = 2,
            },
            .loss_slot_count = 32,
            .max_nacks_per_pump = 4,
            .initial_control_sequence = 77,
            .fec = VideoTransportFecConfig{},
            .reassembly_timeout_us = 50,
            .max_frame_recovery_age_us = 50,
            .clock_sync_interval_us = 0,
        });
    expect(runtime.open().ok(), "receiver runtime opens");
    expect(runtime.request_video_resync(VideoResyncReason::NeedKeyFrame, 5, 1000).ok(),
           "receiver sends resync request");

    std::array<std::byte, 512> receive_buffer{};
    const UdpReceiveResult received = receive_until_ready(control_receiver, receive_buffer);
    expect(received.ok(), "resync UDP datagram receives");
    if (!received.ok()) {
        return;
    }
    const auto packet = warpnect::scl::decode_packet(
        std::span<const std::byte>(receive_buffer).first(received.bytes_received));
    expect(packet.ok(), "resync packet decodes");
    expect_equal(packet.packet.header.payload_type, PayloadType::SessionControl,
                 "resync uses SessionControl payload type");
    const auto resync = warpnect::scl::decode_video_resync_request(packet.packet.payload);
    expect(resync.ok(), "resync payload decodes");
    expect_equal(resync.request.reason, VideoResyncReason::NeedKeyFrame,
                 "receiver reason is preserved");
    expect_equal(resync.request.receiver_config_generation, 5U,
                 "receiver generation is preserved");
    expect_equal(runtime.snapshot().resync_requests_sent, 1ULL,
                 "receiver counts sent resync");
}

void test_sender_handles_resync_with_config_resend_and_keyframe_event() {
    UdpSocket receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver(receiver, endpoint)) {
        return;
    }

    SenderStorage storage(256, 16);
    VideoTransportSender sender(
        VideoTransportSenderConfig{
            .remote_endpoint = endpoint,
            .local_port = 0,
            .max_wire_datagram_size = 256,
            .initial_video_sequence = 1000,
            .initial_control_sequence = 2000,
            .initial_frame_id = 1,
            .retransmission_cache_slots = 16,
            .fec = VideoTransportFecConfig{},
            .resync_request_cooldown_us = 0,
        },
        storage.workspace());
    expect(sender.open().ok(), "sender opens");

    const std::array<std::byte, 2> csd{byte(0x67), byte(0x42)};
    const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = as_bytes(csd)}};
    expect(sender.submit_stream_config(640, 360, entries).ok(), "sender submits initial config");

    std::array<std::byte, 512> receive_buffer{};
    (void)receive_until_ready(receiver, receive_buffer);

    std::array<std::byte, warpnect::scl::kVideoResyncRequestWireSize> payload{};
    expect(warpnect::scl::encode_video_resync_request(
               VideoResyncRequest{
                   .reason = VideoResyncReason::Discontinuity,
                   .receiver_config_generation = 1,
               },
               payload)
               .ok(),
           "test resync payload encodes");
    std::array<std::byte, 128> datagram{};
    const auto encoded = warpnect::scl::encode_packet(
        PacketHeader{
            .protocol_version = warpnect::scl::kSclProtocolVersion,
            .flags = 0,
            .sequence_number = 44,
            .timestamp_us = 0,
            .payload_type = PayloadType::SessionControl,
            .slice_index = 0,
            .total_slices = 1,
        },
        payload,
        datagram);
    expect(encoded.ok(), "test resync datagram encodes");
    expect(sender.handle_control_datagram(
                     std::span<const std::byte>(datagram).first(encoded.bytes_written))
               .ok(),
           "sender handles resync");

    const UdpReceiveResult resent = receive_until_ready(receiver, receive_buffer);
    expect(resent.ok(), "resent StreamConfig receives");
    if (!resent.ok()) {
        return;
    }
    const auto packet = warpnect::scl::decode_packet(
        std::span<const std::byte>(receive_buffer).first(resent.bytes_received));
    expect(packet.ok(), "resent StreamConfig packet decodes");
    const auto config = warpnect::scl::decode_video_stream_config(packet.packet.payload);
    expect(config.ok(), "resent StreamConfig payload decodes");
    expect_equal(config.config.config_generation, 1U, "resend keeps same config generation");
    expect_equal(sender.snapshot().stream_config_resends, 1ULL,
                 "sender counts StreamConfig resend");
    expect_equal(sender.snapshot().keyframe_requests_received, 1ULL,
                 "sender exposes keyframe request");
}

int run_all_tests() {
    test_video_resync_golden_and_malformed();
    test_receiver_emits_video_resync_request();
    test_sender_handles_resync_with_config_resend_and_keyframe_event();
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
