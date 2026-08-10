#include "benchmark_runner.h"

#include "packet_codec.h"
#include "video_packetizer.h"
#include "video_protocol.h"
#include "video_receiver_runtime.h"
#include "video_resync_control.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace {

using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::CsdEntryView;
using warpnect::scl::DatagramSink;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::PacketHeader;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::VideoError;
using warpnect::scl::VideoPacketizer;
using warpnect::scl::VideoPacketizerConfig;
using warpnect::scl::VideoReceiverConfig;
using warpnect::scl::VideoReceiverRuntime;
using warpnect::scl::VideoResyncReason;
using warpnect::scl::VideoResyncRequest;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoTransportFecConfig;

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

void fill_bytes(std::span<std::byte> bytes, std::uint8_t seed = 37) noexcept {
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 31U + seed) & 0xFFU));
    }
}

template <std::size_t Size>
[[nodiscard]] std::span<const std::byte> as_bytes(const std::array<std::byte, Size>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

[[nodiscard]] std::span<const std::byte> as_bytes(const std::vector<std::byte>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

[[nodiscard]] std::string scenario(std::string_view name, std::size_t value,
                                   std::string_view unit) {
    std::ostringstream stream;
    stream << name << "=" << value << unit;
    return stream.str();
}

struct CapturedDatagram final {
    std::vector<std::byte> bytes{};
    PacketHeader header{};
};

class CountingSink final : public DatagramSink {
  public:
    std::uint64_t bytes = 0;
    std::uint64_t datagrams = 0;

    [[nodiscard]] VideoStatus send(std::span<const std::byte> datagram) noexcept override {
        bytes += datagram.size();
        ++datagrams;
        return VideoStatus{};
    }
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
            .bytes = std::vector<std::byte>(datagram.begin(), datagram.end()),
            .header = decoded.packet.header,
        });
        return VideoStatus{};
    }
};

[[nodiscard]] std::vector<CapturedDatagram>
packetize_access_unit(std::size_t max_wire,
                      std::uint32_t sequence,
                      std::uint32_t frame_id,
                      std::span<const std::byte> access_unit) {
    std::vector<std::byte> scratch(max_wire);
    VideoPacketizer packetizer(scratch);
    CollectingSink sink;
    (void)packetizer.emit_access_unit(
        VideoPacketizerConfig{.max_datagram_size = max_wire},
        sequence,
        10'000 + frame_id,
        1,
        frame_id,
        true,
        access_unit,
        sink);
    return sink.datagrams;
}

[[nodiscard]] VideoReceiverConfig receiver_config(std::size_t max_wire) noexcept {
    return VideoReceiverConfig{
        .local_endpoint = UdpEndpoint::loopback_v4(0),
        .remote_endpoint = UdpEndpoint::loopback_v4(49000),
        .restrict_remote_endpoint = false,
        .max_wire_datagram_size = max_wire,
        .max_logical_payload_size = 128U * 1024U,
        .reassembly_slot_count = 8,
        .ready_slot_count = 8,
        .loss =
            LossRecoveryConfig{
                .reorder_delay_us = 2'000,
                .renack_interval_us = 8'000,
                .max_nack_attempts = 3,
            },
        .loss_slot_count = 128,
        .max_nacks_per_pump = 8,
        .initial_control_sequence = 70'000,
        .fec = VideoTransportFecConfig{},
        .reassembly_timeout_us = 50'000,
        .max_frame_recovery_age_us = 50'000,
        .resync_request_cooldown_us = 250'000,
        .clock_sync_interval_us = 0,
    };
}

void add_environment(BenchmarkRunner& runner, std::string_view build_type) {
    runner.add_metadata("os", warpnect::benchmarks::current_os_name());
    runner.add_metadata("architecture", warpnect::benchmarks::architecture_name());
    runner.add_metadata("compiler", warpnect::benchmarks::compiler_name());
    runner.add_metadata("build_type", std::string(build_type));
    runner.add_metadata("__cplusplus", std::to_string(__cplusplus));
#ifdef _MSC_VER
    runner.add_metadata("_MSVC_LANG", std::to_string(_MSVC_LANG));
#endif
    runner.add_metadata("logical_cpu_count", std::to_string(std::thread::hardware_concurrency()));
    runner.add_metadata("mode", runner.options().smoke ? "smoke" : "standard");
    runner.add_metadata("iterations", std::to_string(runner.options().iterations));
}

void video_packetization_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 5 : 50;
    for (std::size_t payload_size : {512U, 4096U, 64U * 1024U}) {
        std::vector<std::byte> payload(payload_size);
        fill_bytes(payload);
        std::vector<std::byte> scratch(1200);
        VideoPacketizer packetizer(scratch);
        std::uint32_t sequence = 10'000;
        std::uint32_t frame = 1;
        runner.run_latency(
            "video_packetization",
            "access_unit_to_scl_datagrams",
            scenario("payload", payload_size, "B"),
            iterations,
            warmup,
            payload_size,
            [&]() {
                CountingSink sink;
                const auto emitted = packetizer.emit_access_unit(
                    VideoPacketizerConfig{.max_datagram_size = 1200},
                    sequence,
                    20'000 + frame,
                    1,
                    frame,
                    frame == 1,
                    payload,
                    sink);
                sequence += emitted.datagrams_emitted == 0 ? 1U : emitted.datagrams_emitted;
                ++frame;
                return sink.bytes ^ sink.datagrams;
            });
    }
}

void receiver_runtime_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = std::max<std::size_t>(10, runner.options().iterations / 10U);
    const std::size_t warmup = runner.options().smoke ? 1 : 5;
    for (std::size_t payload_size : {4096U, 64U * 1024U}) {
        std::vector<std::byte> payload(payload_size);
        fill_bytes(payload, 91);
        std::uint32_t sequence = 30'000;
        std::uint32_t frame = 1;
        runner.run_latency(
            "video_receiver_runtime",
            "reassemble_order_and_fill",
            scenario("payload", payload_size, "B"),
            iterations,
            warmup,
            payload_size,
            [&]() {
                auto datagrams = packetize_access_unit(1200, sequence, frame, payload);
                sequence += static_cast<std::uint32_t>(datagrams.size() + 1U);
                VideoReceiverRuntime runtime(receiver_config(1200));
                (void)runtime.open();
                (void)runtime.activate_config_generation(1);
                const UdpEndpoint source = UdpEndpoint::loopback_v4(49000);
                std::uint64_t now_us = 1000;
                for (const CapturedDatagram& datagram : datagrams) {
                    (void)runtime.accept_datagram(datagram.bytes, source, now_us++);
                }
                std::vector<std::byte> output(payload.size() + 16U);
                const auto filled = runtime.fill_decoder_input(output);
                ++frame;
                return filled.has_access_unit ? filled.size : 0U;
            });
    }
}

void recovery_deadline_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = std::max<std::size_t>(10, runner.options().iterations / 20U);
    const std::size_t warmup = runner.options().smoke ? 1 : 5;
    std::vector<std::byte> payload(32U * 1024U);
    fill_bytes(payload, 123);
    std::uint32_t sequence = 80'000;
    runner.run_latency(
        "video_recovery",
        "deadline_expiry",
        "payload=32KiB,maxFrameRecoveryAge=50ms",
        iterations,
        warmup,
        payload.size(),
        [&]() {
            auto datagrams = packetize_access_unit(512, sequence, 1, payload);
            sequence += static_cast<std::uint32_t>(datagrams.size() + 1U);
            VideoReceiverRuntime runtime(receiver_config(512));
            (void)runtime.open();
            const UdpEndpoint source = UdpEndpoint::loopback_v4(49000);
            if (!datagrams.empty()) {
                (void)runtime.accept_datagram(datagrams.front().bytes, source, 1000);
            }
            const auto before = runtime.snapshot().stale_frames_released;
            (void)runtime.pump(0);
            const auto after = runtime.snapshot().stale_frames_released;
            return after >= before ? after - before : 0U;
        });
}

void control_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 5 : 50;
    std::array<std::byte, warpnect::scl::kVideoResyncRequestWireSize> payload{};
    runner.run_latency("video_control", "video_resync_encode_decode", "wire=8B", iterations,
                       warmup, payload.size(), [&]() {
                           (void)warpnect::scl::encode_video_resync_request(
                               VideoResyncRequest{
                                   .reason = VideoResyncReason::Discontinuity,
                                   .receiver_config_generation = 7,
                               },
                               payload);
                           const auto decoded = warpnect::scl::decode_video_resync_request(payload);
                           return decoded.ok() ? decoded.request.receiver_config_generation : 0U;
                       });
}

void resource_reports(BenchmarkRunner& runner) {
    runner.add_value("video_config", "max_frame_recovery_age", "ultra_low_latency", "50000",
                     "us", "provisional Phase 2 default");
    runner.add_value("video_config", "reorder_delay", "ultra_low_latency", "2000", "us",
                     "fixed policy baseline");
    runner.add_value("video_config", "renack_interval", "ultra_low_latency", "8000", "us",
                     "fixed policy baseline");
    runner.add_value("video_config", "resync_cooldown", "ultra_low_latency", "250000", "us",
                     "request storm guard");
    runner.add_value("video_config", "clock_sync_interval", "ultra_low_latency", "1000000",
                     "us", "low-frequency SessionControl exchange");
    runner.add_value("allocation_audit", "receiver_ready_slots", "after_setup", "0",
                     "payload copies", "bounded slot indices only");
    runner.add_value("allocation_audit", "decoder_fill", "after_setup", "1", "payload copy",
                     "native ready AU into MediaCodec-owned input buffer");
    runner.add_value("pacing", "production_sender", "Phase2", "disabled", "decision",
                     "no pacing queue added by RFC-002G");
    runner.add_value("adaptive_fec", "production_sender", "Phase2", "disabled", "decision",
                     "fixed FEC profiles measured; no variable K/M policy enabled");
}

} // namespace

int main(int argc, char** argv) {
    BenchmarkOptions options = warpnect::benchmarks::parse_options(argc, argv);
#if defined(NDEBUG)
    constexpr std::string_view build_type = "Release";
#else
    constexpr std::string_view build_type = "Debug";
#endif
    BenchmarkRunner runner(options);
    add_environment(runner, build_type);
    video_packetization_benchmarks(runner);
    receiver_runtime_benchmarks(runner);
    recovery_deadline_benchmarks(runner);
    control_benchmarks(runner);
    resource_reports(runner);

    if (!runner.write_csv_file(options.output_path)) {
        std::cerr << "Failed to write benchmark CSV: " << options.output_path << '\n';
        return 1;
    }
    if (options.output_path.empty()) {
        (void)runner.write_csv(std::cout);
    }
    runner.print_summary(std::cout);
    return 0;
}
