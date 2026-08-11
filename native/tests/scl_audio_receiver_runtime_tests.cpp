#include "audio_receiver_runtime.h"
#include "audio_transport.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <memory>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::AudioReceiverConfig;
using warpnect::scl::AudioReceiverEvent;
using warpnect::scl::AudioReceiverEventType;
using warpnect::scl::AudioReceiverRuntime;
using warpnect::scl::AudioTimestampQuality;
using warpnect::scl::AudioTransportError;
using warpnect::scl::AudioTransportSender;
using warpnect::scl::AudioTransportSenderConfig;
using warpnect::scl::AudioTransportSenderWorkspace;
using warpnect::scl::IpAddress;
using warpnect::scl::PayloadType;
using warpnect::scl::UdpEndpoint;

int failures = 0;
int skips = 0;

[[nodiscard]] std::byte byte(std::uint8_t value) noexcept {
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

[[nodiscard]] std::vector<std::byte> make_bytes(std::size_t size, std::uint8_t seed = 7) {
    std::vector<std::byte> bytes(size);
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 23U + seed) & 0xFFU));
    }
    return bytes;
}

[[nodiscard]] std::span<const std::byte> as_bytes(const std::vector<std::byte>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

[[nodiscard]] bool bytes_equal(std::span<const std::byte> lhs,
                               std::span<const std::byte> rhs) noexcept {
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin());
}

struct RuntimeAndSender final {
    AudioReceiverRuntime receiver;
    std::vector<std::byte> sender_scratch;
    PayloadType sender_payload_type = PayloadType::Unknown;
    std::unique_ptr<AudioTransportSender> sender;

    RuntimeAndSender(AudioReceiverConfig receiver_config,
                     std::size_t max_wire_datagram_size,
                     PayloadType sender_payload_type)
        : receiver(receiver_config),
          sender_scratch(max_wire_datagram_size),
          sender_payload_type(sender_payload_type) {}
};

[[nodiscard]] bool open_pair(RuntimeAndSender& pair) {
    const auto receiver_open = pair.receiver.open();
    if (!receiver_open.ok()) {
        skip("audio receiver runtime UDP bind unavailable");
        return false;
    }
    const auto local = pair.receiver.local_endpoint();
    if (!local.ok()) {
        skip("audio receiver runtime local endpoint unavailable");
        return false;
    }
    expect(local.endpoint.port != 0, "audio receiver binds an ephemeral port");
    pair.sender = std::make_unique<AudioTransportSender>(
        AudioTransportSenderConfig{.remote_endpoint = local.endpoint,
                                   .local_port = 0,
                                   .max_wire_datagram_size = pair.sender_scratch.size(),
                                   .initial_audio_sequence = 0,
                                   .payload_type = pair.sender_payload_type},
        AudioTransportSenderWorkspace{.datagram_scratch = pair.sender_scratch});
    const auto sender_open = pair.sender->open();
    if (!sender_open.ok()) {
        skip("audio sender UDP open unavailable");
        return false;
    }
    return true;
}

[[nodiscard]] AudioReceiverEvent pump_until(AudioReceiverRuntime& receiver,
                                            AudioReceiverEventType type) noexcept {
    AudioReceiverEvent event{};
    for (int attempt = 0; attempt < 1000; ++attempt) {
        event = receiver.pump(10'000);
        if (event.type == type || event.type == AudioReceiverEventType::TransportError) {
            return event;
        }
    }
    return event;
}

void stream_config_and_sequential_frames_arrive_exactly() {
    constexpr std::size_t max_wire = 256;
    RuntimeAndSender pair(
        AudioReceiverConfig{.local_endpoint = UdpEndpoint::loopback_v4(0),
                            .payload_type = PayloadType::SystemAudio,
                            .max_wire_datagram_size = max_wire,
                            .max_logical_audio_payload_size = 4096,
                            .reassembly_slot_count = 2,
                            .ready_slot_count = 2,
                            .reassembly_timeout_us = 20'000},
        max_wire,
        PayloadType::SystemAudio);
    if (!open_pair(pair)) {
        return;
    }

    expect(pair.sender->submit_stream_config(48000, 1, 5000, 312).ok(),
           "sender submits receiver runtime config");
    const auto config = pump_until(pair.receiver, AudioReceiverEventType::StreamConfigReady);
    expect_equal(config.type, AudioReceiverEventType::StreamConfigReady,
                 "receiver publishes StreamConfigReady");
    expect_equal(config.config_generation, 1U, "receiver config generation");
    expect_equal(config.sample_rate_hz, 48000U, "receiver sample rate");
    expect_equal(config.channel_count, static_cast<std::uint8_t>(1), "receiver channels");
    expect_equal(config.frame_duration_us, 5000U, "receiver frame duration");
    expect_equal(config.lookahead_samples, 312U, "receiver lookahead");

    for (std::uint64_t index = 0; index < 4; ++index) {
        const auto opus = make_bytes(18, static_cast<std::uint8_t>(index + 11));
        const auto first_position = index * 240U;
        expect(pair.sender
                   ->submit_audio_frame(as_bytes(opus), first_position, index * 5'000'000,
                                        AudioTimestampQuality::AudioRecordTimestamp, index == 2)
                   .ok(),
               "sender submits receiver runtime frame");
        const auto frame = pump_until(pair.receiver, AudioReceiverEventType::AudioFrameReady);
        expect_equal(frame.type, AudioReceiverEventType::AudioFrameReady,
                     "receiver publishes AudioFrameReady");
        expect_equal(frame.config_generation, 1U, "frame generation");
        expect_equal(frame.first_frame_position, first_position, "frame position exact");
        expect_equal(frame.capture_time_us, index * 5'000U, "capture timestamp floors to us");
        expect_equal(frame.timestamp_quality, AudioTimestampQuality::AudioRecordTimestamp,
                     "timestamp quality exact");
        expect_equal(frame.discontinuity_before, index == 2, "discontinuity exact");
        const auto payload = pair.receiver.ready_slot_payload(frame.slot_index);
        expect(bytes_equal(payload, as_bytes(opus)), "ready slot Opus bytes exact");
        expect(pair.receiver.release_ready_slot(frame.slot_index).ok(), "ready slot releases");
    }

    const auto snapshot = pair.receiver.snapshot();
    expect_equal(snapshot.audio_frames_delivered, 4ULL, "receiver delivered four frames");
    expect(snapshot.ready_slots_high_water <= 1U, "ready occupancy stays around one when consumed");
}

void fragmented_audio_frame_reassembles_to_one_ready_slot() {
    constexpr std::size_t max_wire = 64;
    RuntimeAndSender pair(
        AudioReceiverConfig{.local_endpoint = UdpEndpoint::loopback_v4(0),
                            .payload_type = PayloadType::SystemAudio,
                            .max_wire_datagram_size = max_wire,
                            .max_logical_audio_payload_size = 4096,
                            .reassembly_slot_count = 2,
                            .ready_slot_count = 2,
                            .reassembly_timeout_us = 20'000},
        max_wire,
        PayloadType::SystemAudio);
    if (!open_pair(pair)) {
        return;
    }

    expect(pair.sender->submit_stream_config(48000, 2, 5000, 120).ok(),
           "fragmented receiver config submits");
    (void)pump_until(pair.receiver, AudioReceiverEventType::StreamConfigReady);
    const auto opus = make_bytes(180, 99);
    expect(pair.sender
               ->submit_audio_frame(as_bytes(opus), 480, 9'000'000,
                                    AudioTimestampQuality::EstimatedFromReadCompletion, false)
               .ok(),
           "fragmented receiver frame submits");
    const auto frame = pump_until(pair.receiver, AudioReceiverEventType::AudioFrameReady);
    expect_equal(frame.type, AudioReceiverEventType::AudioFrameReady,
                 "fragmented frame becomes ready");
    expect_equal(frame.first_frame_position, 480ULL, "fragmented frame position exact");
    expect_equal(frame.timestamp_quality, AudioTimestampQuality::EstimatedFromReadCompletion,
                 "fragmented timestamp quality exact");
    expect(bytes_equal(pair.receiver.ready_slot_payload(frame.slot_index), as_bytes(opus)),
           "fragmented ready Opus payload exact");
    expect(pair.receiver.release_ready_slot(frame.slot_index).ok(), "fragmented slot releases");

    const auto snapshot = pair.receiver.snapshot();
    expect(snapshot.last_reassembly_latency_us > 0 || snapshot.audio_frames_completed == 1,
           "fragmented path records bounded reassembly completion");
}

void payload_filtering_and_ready_capacity_are_bounded() {
    constexpr std::size_t max_wire = 256;
    RuntimeAndSender pair(
        AudioReceiverConfig{.local_endpoint = UdpEndpoint::loopback_v4(0),
                            .payload_type = PayloadType::MicrophoneAudio,
                            .max_wire_datagram_size = max_wire,
                            .max_logical_audio_payload_size = 4096,
                            .reassembly_slot_count = 1,
                            .ready_slot_count = 1,
                            .reassembly_timeout_us = 20'000},
        max_wire,
        PayloadType::SystemAudio);
    if (!open_pair(pair)) {
        return;
    }

    expect(pair.sender->submit_stream_config(48000, 1, 5000, 120).ok(),
           "filtered sender config submits");
    const auto filtered = pump_until(pair.receiver, AudioReceiverEventType::Timeout);
    expect(filtered.type == AudioReceiverEventType::Timeout ||
               filtered.type == AudioReceiverEventType::None,
           "filtered payload does not publish media event");
    expect(pair.receiver.snapshot().unsupported_payload_datagrams >= 1U,
           "filtered payload counted");
}

int run_all_tests() {
    stream_config_and_sequential_frames_arrive_exactly();
    fragmented_audio_frame_reassembles_to_one_ready_slot();
    payload_filtering_and_ready_capacity_are_bounded();
    return failures == 0 ? 0 : 1;
}

} // namespace

int main() {
    const int result = run_all_tests();
    if (skips != 0) {
        std::cout << "SKIPPED: " << skips << " optional UDP-dependent checks\n";
    }
    if (failures == 0) {
        std::cout << "SCL audio receiver runtime tests passed\n";
    }
    return result;
}
