#include "benchmark_runner.h"

#include "packet_codec.h"
#include "session_protection.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string>
#include <thread>
#include <vector>

namespace {

using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::security::ProtectionScope;
using warpnect::scl::security::SessionProtectionConfig;
using warpnect::scl::security::SessionProtectionLocalRole;
using warpnect::scl::security::SessionProtectionRuntime;

[[nodiscard]] constexpr std::byte byte(const std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

[[nodiscard]] std::array<std::byte, 32> root_secret() {
    std::array<std::byte, 32> result{};
    for (std::size_t index = 0; index < result.size(); ++index) {
        result[index] = byte(static_cast<std::uint8_t>(index + 1U));
    }
    return result;
}

[[nodiscard]] std::array<std::byte, 16> session_id() {
    std::array<std::byte, 16> result{};
    for (std::size_t index = 0; index < result.size(); ++index) {
        result[index] = byte(static_cast<std::uint8_t>(0x80U + index));
    }
    return result;
}

[[nodiscard]] std::array<std::byte, 32> transcript_hash() {
    std::array<std::byte, 32> result{};
    for (std::size_t index = 0; index < result.size(); ++index) {
        result[index] = byte(static_cast<std::uint8_t>(0x40U + index));
    }
    return result;
}

[[nodiscard]] std::vector<std::byte> inner_datagram(const std::size_t payload_size) {
    std::vector<std::byte> payload(payload_size, byte(0x5A));
    const PacketHeader header{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0,
        .sequence_number = 1,
        .timestamp_us = 1,
        .payload_type = PayloadType::SessionControl,
        .slice_index = 0,
        .total_slices = 1,
    };
    std::vector<std::byte> result(warpnect::scl::kPacketHeaderWireSize + payload.size());
    if (!warpnect::scl::encode_packet(header, payload, result).ok()) {
        return {};
    }
    return result;
}

struct RuntimePair final {
    SessionProtectionRuntime client{SessionProtectionConfig{}};
    SessionProtectionRuntime host{SessionProtectionConfig{}};
    UdpEndpoint client_endpoint = UdpEndpoint::loopback_v4(31001);
    UdpEndpoint host_endpoint = UdpEndpoint::loopback_v4(31002);

    RuntimePair() {
        const auto root = root_secret();
        const auto sid = session_id();
        const auto transcript = transcript_hash();
        const auto scope = ProtectionScope::session_control();
        (void)client.initialize(root, sid, 1, transcript, SessionProtectionLocalRole::Client);
        (void)host.initialize(root, sid, 1, transcript, SessionProtectionLocalRole::Host);
        (void)client.create_context(scope, host_endpoint);
        (void)host.create_context(scope, client_endpoint);
    }
};

void add_metadata(BenchmarkRunner& runner, const std::string& build_type) {
    runner.add_metadata("phase", "5-discovery-secure-session-management");
    runner.add_metadata("rfc", "005E");
    runner.add_metadata("build_type", build_type);
    runner.add_metadata("os", warpnect::benchmarks::current_os_name());
    runner.add_metadata("compiler", warpnect::benchmarks::compiler_name());
    runner.add_metadata("architecture", warpnect::benchmarks::architecture_name());
    runner.add_metadata("logical_cpu_count", std::to_string(std::thread::hardware_concurrency()));
    runner.add_metadata("mode", runner.options().smoke ? "smoke" : "standard");
}

void run_case(BenchmarkRunner& runner, const std::size_t payload_size) {
    const std::vector<std::byte> inner = inner_datagram(payload_size);
    if (inner.empty()) {
        runner.add_value("session_protection", "fixture", std::to_string(payload_size), "failed", "status");
        return;
    }
    const std::size_t iterations = runner.options().smoke ? 100U : runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 10U : 50U;
    RuntimePair pair{};
    std::array<std::byte, 1200> secure{};
    std::array<std::byte, 1200> plain{};
    runner.add_value("session_protection", "inner_scl_bytes", std::to_string(payload_size),
                     std::to_string(inner.size()), "bytes");
    runner.add_value("session_protection", "wnsd_overhead", std::to_string(payload_size), "44", "bytes");

    runner.run_latency(
        "session_protection", "protect_and_unprotect", std::to_string(payload_size), iterations, warmup,
        inner.size() + warpnect::scl::security::kSecureDatagramOverhead, [&]() -> std::uint64_t {
            const auto protected_result =
                pair.client.protect(ProtectionScope::session_control(), inner, secure);
            if (!protected_result.ok()) {
                return 0;
            }
            const auto unprotected_result = pair.host.unprotect(
                pair.client_endpoint,
                std::span<const std::byte>(secure.data(), protected_result.bytes_written),
                plain,
                1);
            return unprotected_result.ok() ? static_cast<std::uint64_t>(unprotected_result.bytes_written) : 0;
        });
}

} // namespace

int main(int argc, char** argv) {
    const BenchmarkOptions options = warpnect::benchmarks::parse_options(argc, argv);
    BenchmarkRunner runner(options);
#if defined(NDEBUG)
    add_metadata(runner, "Release");
#else
    add_metadata(runner, "Debug");
#endif
    runner.add_value("session_protection", "suite", "v1", "AES-128-GCM/HKDF-SHA256", "algorithm");
    runner.add_value("session_protection", "replay_window", "default", "4096", "packet_numbers");
    runner.add_value("session_protection", "epoch_packet_limit", "default", "1048576", "packet_numbers");
    run_case(runner, 64);
    run_case(runner, 512);
    if (!runner.write_csv(std::cout) || !runner.write_csv_file(options.output_path)) {
        return 1;
    }
    runner.print_summary(std::cout);
    return 0;
}
