#include "benchmark_runner.h"

#include "clock_sync.h"
#include "clock_sync_control.h"
#include "fec.h"
#include "fec_control.h"
#include "fragmentation.h"
#include "loss_detector.h"
#include "packet_codec.h"
#include "reassembly.h"
#include "recovery_control.h"
#include "retransmission_cache.h"
#include "telemetry.h"
#include "udp_socket.h"

#include "simulated_network.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <span>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::ClockSyncConfig;
using warpnect::scl::ClockSynchronizer;
using warpnect::scl::ClockSyncResponse;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::FecBlockEncoder;
using warpnect::scl::FecEncoderWorkspace;
using warpnect::scl::FecRecoveryBlock;
using warpnect::scl::FecRecoveryWorkspace;
using warpnect::scl::FragmentationConfig;
using warpnect::scl::FragmentCursor;
using warpnect::scl::IpVersion;
using warpnect::scl::LossDetector;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::LossSlot;
using warpnect::scl::MutableShardView;
using warpnect::scl::NackRequest;
using warpnect::scl::NackSequenceCursor;
using warpnect::scl::NetworkTelemetry;
using warpnect::scl::NetworkTelemetryStorage;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::ReassemblySlot;
using warpnect::scl::ReassemblyWorkspace;
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
using warpnect::test_support::ScriptedNetwork;

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

void fill_bytes(std::span<std::byte> bytes, std::uint8_t seed = 31) noexcept {
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 29U + seed) & 0xFFU));
    }
}

[[nodiscard]] constexpr PacketHeader packet_header(std::uint32_t sequence, PayloadType payload_type,
                                                   std::size_t index = 0) noexcept {
    return PacketHeader{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = static_cast<std::uint16_t>(0x0100U + static_cast<std::uint16_t>(index)),
        .sequence_number = sequence,
        .timestamp_us = 0x0102030405060708ULL + sequence,
        .payload_type = payload_type,
        .slice_index = 0,
        .total_slices = 1,
    };
}

[[nodiscard]] std::string size_text(std::size_t value) {
    return std::to_string(value);
}

[[nodiscard]] std::string scenario_text(std::string_view name, std::size_t value,
                                        std::string_view unit) {
    std::ostringstream stream;
    stream << name << "=" << value << unit;
    return stream.str();
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

[[nodiscard]] bool open_bound_v4(UdpSocket& socket, UdpEndpoint& endpoint) noexcept {
    if (!socket.open(IpVersion::V4).ok() || !socket.bind(UdpEndpoint::loopback_v4(0)).ok()) {
        return false;
    }
    const auto local = socket.local_endpoint();
    if (!local.ok()) {
        return false;
    }
    endpoint = local.endpoint;
    return true;
}

[[nodiscard]] bool open_bound_v6(UdpSocket& socket, UdpEndpoint& endpoint) noexcept {
    if (!socket.open(IpVersion::V6).ok() || !socket.bind(UdpEndpoint::loopback_v6(0)).ok()) {
        return false;
    }
    const auto local = socket.local_endpoint();
    if (!local.ok()) {
        return false;
    }
    endpoint = local.endpoint;
    return true;
}

struct FecStorage final {
    std::vector<std::byte> encoder_data{};
    std::vector<std::byte> encoder_parity{};
    std::vector<std::byte> recovery_shards{};
    std::vector<std::byte> recovery_presence{};
    std::vector<std::byte> matrix{};
    std::vector<std::byte> scratch{};

    bool resize(const FecBlockConfig& config) {
        const auto data = warpnect::scl::required_fec_encoder_data_storage_size(config);
        const auto parity = warpnect::scl::required_fec_encoder_parity_storage_size(config);
        const auto recovery = warpnect::scl::required_fec_recovery_shard_storage_size(config);
        const auto presence = warpnect::scl::required_fec_presence_storage_size(config.rs);
        const auto matrix_size =
            warpnect::scl::required_reed_solomon_matrix_storage_size(config.rs);
        const auto scratch_size =
            warpnect::scl::required_reed_solomon_scratch_storage_size(config.rs);
        if (!data.ok() || !parity.ok() || !recovery.ok() || !presence.ok() || !matrix_size.ok() ||
            !scratch_size.ok()) {
            return false;
        }

        encoder_data.assign(data.size, std::byte{0});
        encoder_parity.assign(parity.size, std::byte{0});
        recovery_shards.assign(recovery.size, std::byte{0});
        recovery_presence.assign(presence.size, std::byte{0});
        matrix.assign(matrix_size.size, std::byte{0});
        scratch.assign(scratch_size.size, std::byte{0});
        return true;
    }

    FecEncoderWorkspace encoder_workspace() noexcept {
        return FecEncoderWorkspace{
            .data_shard_storage = encoder_data,
            .parity_shard_storage = encoder_parity,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }

    FecRecoveryWorkspace recovery_workspace() noexcept {
        return FecRecoveryWorkspace{
            .shard_storage = recovery_shards,
            .present_bitmap = recovery_presence,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }
};

[[nodiscard]] std::vector<std::vector<std::byte>>
build_encoded_datagrams(std::uint8_t count, std::uint32_t base_sequence, PayloadType payload_type,
                        std::size_t payload_size) {
    std::vector<std::vector<std::byte>> datagrams{};
    datagrams.reserve(count);
    for (std::uint8_t i = 0; i < count; ++i) {
        std::vector<std::byte> payload(payload_size + (i % 5U));
        fill_bytes(payload, static_cast<std::uint8_t>(17U + i));
        const auto encoded_size = warpnect::scl::encoded_packet_size(payload.size());
        std::vector<std::byte> datagram(encoded_size.bytes_written);
        (void)warpnect::scl::encode_packet(packet_header(base_sequence + i, payload_type, i),
                                           payload, datagram);
        datagrams.push_back(std::move(datagram));
    }
    return datagrams;
}

[[nodiscard]] FecBlockConfig fec_config(std::uint8_t data, std::uint8_t parity,
                                        std::size_t wire_budget = 1400,
                                        std::uint32_t base_sequence = 10'000) noexcept {
    return FecBlockConfig{
        .rs = ReedSolomonConfig{.data_shards = data, .parity_shards = parity},
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = base_sequence,
        .max_wire_datagram_size = wire_budget,
    };
}

[[nodiscard]] std::vector<std::vector<std::byte>>
build_fragmented_datagrams(std::span<const std::byte> logical_payload, FragmentationConfig config,
                           std::uint32_t base_sequence) {
    std::vector<std::vector<std::byte>> datagrams{};
    const auto plan = warpnect::scl::plan_fragments(
        config, packet_header(base_sequence, PayloadType::Video), logical_payload);
    if (!plan.ok()) {
        return datagrams;
    }

    datagrams.reserve(plan.plan.total_slices);
    FragmentCursor cursor(plan.plan);
    while (cursor.has_next()) {
        const auto fragment = cursor.next();
        if (!fragment.ok()) {
            break;
        }
        const auto encoded_size =
            warpnect::scl::encoded_packet_size(fragment.fragment.payload.size());
        std::vector<std::byte> datagram(encoded_size.bytes_written);
        (void)warpnect::scl::encode_packet(fragment.fragment.header, fragment.fragment.payload,
                                           datagram);
        datagrams.push_back(std::move(datagram));
    }
    return datagrams;
}

[[nodiscard]] std::uint64_t
reassemble_datagrams(const std::vector<std::vector<std::byte>>& datagrams,
                     FragmentationConfig config, std::size_t payload_size, bool reverse_order) {
    std::vector<warpnect::scl::PacketView> views{};
    views.reserve(datagrams.size());
    for (const auto& datagram : datagrams) {
        const auto decoded = warpnect::scl::decode_packet(datagram);
        if (decoded.ok()) {
            views.push_back(decoded.packet);
        }
    }

    const auto metadata_size =
        warpnect::scl::required_reassembly_metadata_size(static_cast<std::uint16_t>(views.size()));
    const auto storage_size = warpnect::scl::max_reassembled_payload_size(
        config, static_cast<std::uint16_t>(views.size()));
    std::vector<std::byte> storage(storage_size.size);
    std::vector<std::byte> metadata(metadata_size.size);
    ReassemblySlot slot(
        config, ReassemblyWorkspace{.payload_storage = storage, .received_bitmap = metadata});
    if (reverse_order) {
        for (auto cursor = views.rbegin(); cursor != views.rend(); ++cursor) {
            (void)slot.accept(*cursor);
        }
    } else {
        for (const auto& view : views) {
            (void)slot.accept(view);
        }
    }

    const auto result = slot.result();
    return result.ok() ? static_cast<std::uint64_t>(result.payload.payload.size() + payload_size)
                       : 0U;
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

void packet_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 10 : 100;
    std::array<std::byte, 2048> output{};
    const PacketHeader header = packet_header(42, PayloadType::Video);
    runner.run_latency("packet", "encode_packet_header", "header_only", iterations, warmup, 21,
                       [&]() {
                           (void)warpnect::scl::encode_packet_header(header, output);
                           return output[0] == byte(0) ? 1U : 2U;
                       });

    std::array<std::byte, warpnect::scl::kPacketHeaderWireSize> header_wire{};
    (void)warpnect::scl::encode_packet_header(header, header_wire);
    runner.run_latency("packet", "decode_packet_header", "header_only", iterations, warmup, 21,
                       [&]() {
                           const auto decoded = warpnect::scl::decode_packet_header(header_wire);
                           return decoded.ok() ? decoded.header.sequence_number : 0U;
                       });

    for (std::size_t size : {0U, 64U, 256U, 512U, 1024U, 1200U}) {
        std::vector<std::byte> payload(size);
        fill_bytes(payload);
        const auto encoded_size = warpnect::scl::encoded_packet_size(size);
        std::vector<std::byte> datagram(encoded_size.bytes_written);
        const std::string scenario = scenario_text("payload", size, "B");
        runner.run_latency("packet", "encode_packet", scenario, iterations, warmup, size, [&]() {
            const auto encoded = warpnect::scl::encode_packet(header, payload, datagram);
            return encoded.bytes_written;
        });
        (void)warpnect::scl::encode_packet(header, payload, datagram);
        runner.run_latency("packet", "decode_packet", scenario, iterations, warmup, datagram.size(),
                           [&]() {
                               const auto decoded = warpnect::scl::decode_packet(datagram);
                               return decoded.ok() ? decoded.packet.payload.size() : 0U;
                           });
    }
}

void fragmentation_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 10 : 100;
    const FragmentationConfig config{.max_datagram_size = 1200};
    for (std::size_t size : {1024U, 16U * 1024U, 64U * 1024U, 256U * 1024U, 1024U * 1024U}) {
        std::vector<std::byte> payload(size);
        fill_bytes(payload);
        const PacketHeader header = packet_header(1000, PayloadType::Video);
        const std::string scenario = scenario_text("payload", size, "B");
        runner.run_latency(
            "fragmentation", "plan_fragments", scenario, iterations, warmup, size, [&]() {
                const auto plan = warpnect::scl::plan_fragments(config, header, payload);
                return plan.ok() ? plan.plan.total_slices : 0U;
            });
        const auto plan = warpnect::scl::plan_fragments(config, header, payload);
        runner.run_latency("fragmentation", "cursor_iteration", scenario, iterations, warmup, size,
                           [&]() {
                               FragmentCursor cursor(plan.plan);
                               std::uint64_t total = 0;
                               while (cursor.has_next()) {
                                   const auto fragment = cursor.next();
                                   total += fragment.ok() ? fragment.fragment.payload.size() : 0U;
                               }
                               return total;
                           });
    }
}

void reassembly_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = std::max<std::size_t>(20, runner.options().iterations / 10U);
    const std::size_t warmup = runner.options().smoke ? 3 : 10;
    const FragmentationConfig config{.max_datagram_size = 1200};
    std::vector<std::byte> payload(64U * 1024U);
    fill_bytes(payload);
    const auto datagrams = build_fragmented_datagrams(payload, config, 20'000);
    runner.run_latency("reassembly", "accept_all", "order=in_order,payload=64KiB", iterations,
                       warmup, payload.size(), [&]() {
                           return reassemble_datagrams(datagrams, config, payload.size(), false);
                       });
    runner.run_latency("reassembly", "accept_all", "order=reverse,payload=64KiB", iterations,
                       warmup, payload.size(), [&]() {
                           return reassemble_datagrams(datagrams, config, payload.size(), true);
                       });
}

void udp_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations =
        runner.options().smoke ? 50 : std::min<std::size_t>(500, runner.options().iterations);
    const std::size_t warmup = runner.options().smoke ? 5 : 20;
    for (std::size_t size : {64U, 512U, 1200U}) {
        UdpSocket sender{};
        UdpSocket receiver{};
        UdpEndpoint sender_endpoint{};
        UdpEndpoint receiver_endpoint{};
        if (!open_bound_v4(sender, sender_endpoint) ||
            !open_bound_v4(receiver, receiver_endpoint)) {
            runner.add_value("udp", "loopback_ipv4", scenario_text("payload", size, "B"), "skip",
                             "status", "IPv4 loopback unavailable");
            continue;
        }

        std::vector<std::byte> datagram(size);
        std::vector<std::byte> receive_buffer(size + 16U);
        fill_bytes(datagram);
        const std::string scenario = scenario_text("payload", size, "B");
        runner.run_latency("udp", "ipv4_send_receive", scenario, iterations, warmup, size, [&]() {
            const auto sent = sender.send_to(datagram, receiver_endpoint);
            if (!sent.ok()) {
                return std::size_t{0};
            }
            const auto received = receive_until_ready(receiver, receive_buffer);
            return received.ok() ? received.bytes_received : 0U;
        });

        runner.run_latency("udp", "ipv4_ping_pong", scenario, iterations, warmup, size * 2U, [&]() {
            const auto sent = sender.send_to(datagram, receiver_endpoint);
            if (!sent.ok()) {
                return std::size_t{0};
            }
            const auto received = receive_until_ready(receiver, receive_buffer);
            if (!received.ok()) {
                return std::size_t{0};
            }
            const auto returned = receiver.send_to(
                std::span<const std::byte>(receive_buffer).first(received.bytes_received),
                sender_endpoint);
            if (!returned.ok()) {
                return std::size_t{0};
            }
            const auto final_receive = receive_until_ready(sender, receive_buffer);
            return final_receive.ok() ? final_receive.bytes_received : 0U;
        });
    }

    UdpSocket v6_sender{};
    UdpSocket v6_receiver{};
    UdpEndpoint v6_sender_endpoint{};
    UdpEndpoint v6_receiver_endpoint{};
    if (open_bound_v6(v6_sender, v6_sender_endpoint) &&
        open_bound_v6(v6_receiver, v6_receiver_endpoint)) {
        std::array<std::byte, 128> datagram{};
        std::array<std::byte, 256> receive_buffer{};
        fill_bytes(datagram);
        runner.run_latency(
            "udp", "ipv6_send_receive", "payload=128B", iterations, warmup, datagram.size(), [&]() {
                const auto sent = v6_sender.send_to(datagram, v6_receiver_endpoint);
                if (!sent.ok()) {
                    return std::size_t{0};
                }
                const auto received = receive_until_ready(v6_receiver, receive_buffer);
                return received.ok() ? received.bytes_received : 0U;
            });
    } else {
        runner.add_value("udp", "ipv6_send_receive", "payload=128B", "skip", "status",
                         "IPv6 loopback unavailable");
    }
}

void recovery_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 10 : 100;
    std::array<LossSlot, 128> slots{};
    runner.run_latency("recovery", "loss_observe_in_order", "window=128", iterations, warmup, 0,
                       [&]() {
                           LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                                    .renack_interval_us = 100,
                                                                    .max_nack_attempts = 2},
                                                 slots);
                           return detector.observe(100, 0).ok() && detector.observe(101, 1).ok()
                                      ? detector.frontier_sequence()
                                      : 0U;
                       });
    runner.run_latency(
        "recovery", "loss_gap_and_collect_nack", "gap=4", iterations, warmup, 0, [&]() {
            LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 0,
                                                     .renack_interval_us = 100,
                                                     .max_nack_attempts = 2},
                                  slots);
            (void)detector.observe(100, 0);
            (void)detector.observe(105, 0);
            std::array<NackRequest, 1> requests{};
            const auto nacks = detector.collect_due_nacks(0, PayloadType::Video, requests);
            return nacks.requests_written + detector.missing_count();
        });

    const NackRequest request{.target_payload_type = PayloadType::Video,
                              .base_sequence_number = 100,
                              .missing_bitmap = 0x25};
    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_wire{};
    runner.run_latency(
        "recovery", "encode_nack", "bitmap=0x25", iterations, warmup, nack_wire.size(),
        [&]() { return warpnect::scl::encode_nack(request, nack_wire).ok() ? 1U : 0U; });
    (void)warpnect::scl::encode_nack(request, nack_wire);
    runner.run_latency("recovery", "decode_nack", "bitmap=0x25", iterations, warmup,
                       nack_wire.size(), [&]() {
                           const auto decoded = warpnect::scl::decode_nack(nack_wire);
                           return decoded.ok() ? decoded.request.missing_bitmap : 0U;
                       });
    runner.run_latency("recovery", "nack_bitmap_iteration", "bits=3", iterations, warmup, 0, [&]() {
        NackSequenceCursor cursor(request);
        std::uint64_t sum = 0;
        while (cursor.has_next()) {
            const auto sequence = cursor.next();
            sum += sequence.ok() ? sequence.sequence_number : 0U;
        }
        return sum;
    });

    std::vector<std::byte> cache_storage(64U * 1200U);
    std::vector<RetransmissionEntry> entries(64);
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = 64, .max_datagram_size = 1200},
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = entries});
    auto datagrams = build_encoded_datagrams(32, 1000, PayloadType::Video, 128);
    for (std::size_t i = 0; i < datagrams.size(); ++i) {
        (void)cache.store(PayloadType::Video, 1000U + static_cast<std::uint32_t>(i), datagrams[i]);
    }
    runner.run_latency("recovery", "retransmission_cache_lookup", "slots=64", iterations, warmup,
                       128, [&]() {
                           const auto found = cache.find(PayloadType::Video, 1016);
                           return found.ok() ? found.datagram.size() : 0U;
                       });
    runner.run_latency("recovery", "retransmission_cache_store", "slots=64", iterations, warmup,
                       128, [&]() {
                           cache.reset();
                           const auto stored = cache.store(PayloadType::Video, 2000, datagrams[0]);
                           return stored.ok() ? datagrams[0].size() : 0U;
                       });
}

void fec_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = std::max<std::size_t>(20, runner.options().iterations / 10U);
    const std::size_t warmup = runner.options().smoke ? 3 : 10;
    struct Scenario final {
        std::uint8_t k;
        std::uint8_t m;
    };
    for (Scenario scenario :
         {Scenario{4, 1}, Scenario{4, 2}, Scenario{8, 2}, Scenario{10, 2}, Scenario{16, 4}}) {
        for (std::size_t payload_size : {256U, 512U, 1024U, 1200U}) {
            const auto config = fec_config(scenario.k, scenario.m, 1400);
            FecStorage storage{};
            if (!storage.resize(config)) {
                continue;
            }
            auto datagrams = build_encoded_datagrams(scenario.k, config.base_sequence_number,
                                                     config.target_payload_type,
                                                     payload_size);
            const std::string label = "K=" + std::to_string(scenario.k) +
                                      ",M=" + std::to_string(scenario.m) +
                                      ",shard~=" + std::to_string(payload_size) + "B";
            runner.add_value(
                "fec", "network_overhead_ratio", label,
                std::to_string(static_cast<double>(scenario.m) / static_cast<double>(scenario.k)),
                "M/K", "benchmark scenario only");
            runner.run_latency("fec", "encode_parity", label, iterations, warmup,
                               payload_size * scenario.k, [&]() {
                                   FecBlockEncoder encoder(config, storage.encoder_workspace());
                                   for (const auto& datagram : datagrams) {
                                       (void)encoder.accept_data_datagram(datagram);
                                   }
                                   const auto encoded = encoder.encode();
                                   return encoded.ok() ? encoder.accepted_data_shards() : 0U;
                               });

            FecBlockEncoder prepared(config, storage.encoder_workspace());
            for (const auto& datagram : datagrams) {
                (void)prepared.accept_data_datagram(datagram);
            }
            (void)prepared.encode();
            runner.run_latency(
                "fec", "recover_single_data_shard", label, iterations, warmup, payload_size, [&]() {
                    FecRecoveryBlock recovery(storage.recovery_workspace());
                    for (std::uint8_t i = 1; i < scenario.k; ++i) {
                        (void)recovery.accept_data_datagram(config, datagrams[i]);
                    }
                    const auto parity = prepared.parity_view(0);
                    (void)recovery.accept_parity(parity.parity);
                    const auto recovered = recovery.recover();
                    return recovered.ok() ? recovery.datagram(0).datagram.datagram.size() : 0U;
                });
        }
    }
}

void clock_and_telemetry_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 10 : 100;
    const ClockSyncResponse response{.exchange_id = 1, .t0_us = 1000, .t1_us = 1600, .t2_us = 1620};
    runner.run_latency("clock", "calculate_sample", "offset=500us", iterations, warmup, 0, [&]() {
        const auto sample = warpnect::scl::calculate_clock_sync_sample(response, 1220);
        return sample.ok() ? sample.sample.rtt_us : 0U;
    });

    std::array<warpnect::scl::ClockSyncSample, 8> samples{};
    ClockSynchronizer sync(ClockSyncConfig{.min_samples_for_model = 2,
                                           .max_accepted_rtt_us = 1000,
                                           .max_abs_drift_ppm = 1000.0,
                                           .stale_after_us = 10'000'000},
                           samples);
    runner.run_latency("clock", "add_sample_and_fit", "samples=3", iterations, warmup, 0, [&]() {
        sync.reset();
        for (std::uint64_t i = 0; i < 3; ++i) {
            const ClockSyncResponse item{.exchange_id = static_cast<std::uint32_t>(i),
                                         .t0_us = 1000 + i * 1000,
                                         .t1_us = 1600 + i * 1000,
                                         .t2_us = 1620 + i * 1000};
            const auto sample = warpnect::scl::calculate_clock_sync_sample(item, 1220 + i * 1000);
            (void)sync.add_sample(sample.sample);
        }
        return static_cast<std::uint64_t>(sync.snapshot(4000).accepted_samples);
    });
    const auto first = warpnect::scl::calculate_clock_sync_sample(response, 1220);
    const ClockSyncResponse second{.exchange_id = 2, .t0_us = 2000, .t1_us = 2600, .t2_us = 2620};
    const auto second_sample = warpnect::scl::calculate_clock_sync_sample(second, 2220);
    sync.reset();
    (void)sync.add_sample(first.sample);
    (void)sync.add_sample(second_sample.sample);
    runner.run_latency("clock", "remote_to_local", "synchronized", iterations, warmup, 0,
                       [&]() { return sync.remote_to_local(3600, 2300).timestamp_us; });
    runner.run_latency("clock", "local_to_remote", "synchronized", iterations, warmup, 0,
                       [&]() { return sync.local_to_remote(3000, 2300).timestamp_us; });

    std::array<std::uint64_t, 32> rtt{};
    std::array<std::uint64_t, 32> one_way{};
    NetworkTelemetry telemetry(
        NetworkTelemetryStorage{.rtt_samples = rtt, .one_way_delay_samples = one_way});
    runner.run_latency("telemetry", "record_counter", "datagram_sent", iterations, warmup, 0,
                       [&]() {
                           telemetry.record_datagram_sent(1200);
                           return telemetry.snapshot(sync.snapshot(2300)).counters.datagrams_sent;
                       });
    runner.run_latency("telemetry", "rolling_stat_insert", "rtt", iterations, warmup, 0, [&]() {
        telemetry.record_clock_sample_accepted(100);
        return telemetry.snapshot(sync.snapshot(2300)).rtt.latest;
    });
    runner.run_latency(
        "telemetry", "snapshot", "counters_and_windows", iterations, warmup, 0,
        [&]() { return telemetry.snapshot(sync.snapshot(2300)).counters.bytes_sent; });
}

[[nodiscard]] std::uint64_t run_in_memory_pipeline_once(std::size_t payload_size,
                                                        std::size_t wire_budget,
                                                        bool drop_one_for_fec) {
    const auto protected_budget = warpnect::scl::fec_max_protected_datagram_size(wire_budget);
    if (!protected_budget.ok()) {
        return 0;
    }
    const FragmentationConfig fragmentation{.max_datagram_size = protected_budget.size};
    std::vector<std::byte> payload(payload_size);
    fill_bytes(payload);
    auto datagrams = build_fragmented_datagrams(payload, fragmentation, 50'000);
    if (datagrams.empty() || datagrams.size() > 32U) {
        return 0;
    }
    const auto config =
        fec_config(static_cast<std::uint8_t>(datagrams.size()), 2, wire_budget, 50'000);
    FecStorage fec_storage{};
    if (!fec_storage.resize(config)) {
        return 0;
    }
    FecBlockEncoder encoder(config, fec_storage.encoder_workspace());
    for (const auto& datagram : datagrams) {
        (void)encoder.accept_data_datagram(datagram);
    }
    (void)encoder.encode();

    const auto metadata_size = warpnect::scl::required_reassembly_metadata_size(
        static_cast<std::uint16_t>(datagrams.size()));
    const auto storage_size = warpnect::scl::max_reassembled_payload_size(
        fragmentation, static_cast<std::uint16_t>(datagrams.size()));
    std::vector<std::byte> reassembly_payload(storage_size.size);
    std::vector<std::byte> reassembly_metadata(metadata_size.size);
    ReassemblySlot reassembly(fragmentation,
                              ReassemblyWorkspace{.payload_storage = reassembly_payload,
                                                  .received_bitmap = reassembly_metadata});
    FecRecoveryBlock recovery(fec_storage.recovery_workspace());
    for (std::size_t i = 0; i < datagrams.size(); ++i) {
        if (drop_one_for_fec && i == 2U) {
            continue;
        }
        const auto decoded = warpnect::scl::decode_packet(datagrams[i]);
        (void)recovery.accept_data_datagram(config, datagrams[i]);
        if (decoded.ok()) {
            (void)reassembly.accept(decoded.packet);
        }
    }
    if (drop_one_for_fec) {
        const auto parity = encoder.parity_view(0);
        (void)recovery.accept_parity(parity.parity);
        (void)recovery.recover();
        const auto recovered = recovery.datagram(2);
        if (recovered.ok()) {
            const auto decoded = warpnect::scl::decode_packet(recovered.datagram.datagram);
            if (decoded.ok()) {
                (void)reassembly.accept(decoded.packet);
            }
        }
    }
    const auto result = reassembly.result();
    return result.ok() ? result.payload.payload.size() : 0U;
}

void end_to_end_benchmarks(BenchmarkRunner& runner) {
    const std::size_t iterations = std::max<std::size_t>(10, runner.options().iterations / 20U);
    const std::size_t warmup = runner.options().smoke ? 1 : 3;
    runner.run_latency("pipeline", "in_memory_zero_loss", "payload=64KiB,budget=1200", iterations,
                       warmup, 64U * 1024U,
                       [&]() { return run_in_memory_pipeline_once(64U * 1024U, 1200, false); });
    runner.run_latency("pipeline", "fec_recovery_latency", "payload=64KiB,budget=1200", iterations,
                       warmup, 64U * 1024U,
                       [&]() { return run_in_memory_pipeline_once(64U * 1024U, 1200, true); });

    UdpSocket sender{};
    UdpSocket receiver{};
    UdpEndpoint receiver_endpoint{};
    UdpEndpoint sender_endpoint{};
    if (open_bound_v4(sender, sender_endpoint) && open_bound_v4(receiver, receiver_endpoint)) {
        const FragmentationConfig config{.max_datagram_size = 512};
        std::vector<std::byte> payload(4096);
        fill_bytes(payload);
        const auto datagrams = build_fragmented_datagrams(payload, config, 80'000);
        std::vector<std::byte> receive_buffer(1024);
        runner.run_latency(
            "pipeline", "udp_loopback_zero_loss", "payload=4KiB,budget=512", iterations, warmup,
            payload.size(), [&]() {
                const auto metadata_size = warpnect::scl::required_reassembly_metadata_size(
                    static_cast<std::uint16_t>(datagrams.size()));
                const auto storage_size = warpnect::scl::max_reassembled_payload_size(
                    config, static_cast<std::uint16_t>(datagrams.size()));
                std::vector<std::byte> reassembly_payload(storage_size.size);
                std::vector<std::byte> reassembly_metadata(metadata_size.size);
                ReassemblySlot reassembly(
                    config, ReassemblyWorkspace{.payload_storage = reassembly_payload,
                                                .received_bitmap = reassembly_metadata});
                for (const auto& datagram : datagrams) {
                    (void)sender.send_to(datagram, receiver_endpoint);
                }
                std::uint64_t received_bytes = 0;
                for (std::size_t i = 0; i < datagrams.size(); ++i) {
                    const auto received = receive_until_ready(receiver, receive_buffer);
                    if (received.ok()) {
                        received_bytes += received.bytes_received;
                        const auto decoded =
                            warpnect::scl::decode_packet(std::span<const std::byte>(receive_buffer)
                                                             .first(received.bytes_received));
                        if (decoded.ok()) {
                            (void)reassembly.accept(decoded.packet);
                        }
                    }
                }
                return received_bytes + (reassembly.is_complete() ? payload.size() : 0U);
            });

        auto data = build_encoded_datagrams(4, 90'000, PayloadType::Video, 128);
        std::vector<std::byte> cache_storage(8U * 512U);
        std::vector<RetransmissionEntry> entries(8);
        RetransmissionCache cache(
            RetransmissionCacheConfig{.slot_count = 8, .max_datagram_size = 512},
            RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = entries});
        (void)cache.store(PayloadType::Video, 90'002, data[2]);
        NackRequest request{.target_payload_type = PayloadType::Video,
                            .base_sequence_number = 90'002,
                            .missing_bitmap = 1};
        std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_payload{};
        (void)warpnect::scl::encode_nack(request, nack_payload);
        const auto nack_packet_size = warpnect::scl::encoded_packet_size(nack_payload.size());
        std::vector<std::byte> nack_packet(nack_packet_size.bytes_written);
        (void)warpnect::scl::encode_packet(packet_header(123, PayloadType::SessionControl),
                                           nack_payload, nack_packet);
        runner.run_latency(
            "pipeline", "nack_recovery_loopback", "single_missing_datagram", iterations, warmup,
            data[2].size(), [&]() {
                (void)receiver.send_to(nack_packet, sender_endpoint);
                auto received = receive_until_ready(sender, receive_buffer);
                if (!received.ok()) {
                    return std::size_t{0};
                }
                const auto decoded_packet = warpnect::scl::decode_packet(
                    std::span<const std::byte>(receive_buffer).first(received.bytes_received));
                if (!decoded_packet.ok()) {
                    return std::size_t{0};
                }
                const auto decoded_nack = warpnect::scl::decode_nack(decoded_packet.packet.payload);
                if (!decoded_nack.ok()) {
                    return std::size_t{0};
                }
                const auto found = cache.find(PayloadType::Video, 90'002);
                if (!found.ok()) {
                    return std::size_t{0};
                }
                (void)sender.send_to(found.datagram, receiver_endpoint);
                received = receive_until_ready(receiver, receive_buffer);
                return received.ok() ? received.bytes_received : 0U;
            });
    } else {
        runner.add_value("pipeline", "udp_loopback_zero_loss", "payload=4KiB,budget=512", "skip",
                         "status", "IPv4 loopback unavailable");
    }
}

void resource_reports(BenchmarkRunner& runner) {
    runner.add_value("memory", "sizeof", "PacketHeader", size_text(sizeof(PacketHeader)), "bytes");
    runner.add_value("memory", "sizeof", "UdpEndpoint", size_text(sizeof(UdpEndpoint)), "bytes");
    runner.add_value("memory", "sizeof", "UdpSocket", size_text(sizeof(UdpSocket)), "bytes");
    runner.add_value("memory", "sizeof", "FragmentCursor", size_text(sizeof(FragmentCursor)),
                     "bytes");
    runner.add_value("memory", "sizeof", "ReassemblySlot", size_text(sizeof(ReassemblySlot)),
                     "bytes");
    runner.add_value("memory", "sizeof", "LossDetector", size_text(sizeof(LossDetector)), "bytes");
    runner.add_value("memory", "sizeof", "NackRequest", size_text(sizeof(NackRequest)), "bytes");
    runner.add_value("memory", "sizeof", "RetransmissionCache",
                     size_text(sizeof(RetransmissionCache)), "bytes");
    runner.add_value("memory", "sizeof", "FecBlockEncoder", size_text(sizeof(FecBlockEncoder)),
                     "bytes");
    runner.add_value("memory", "sizeof", "FecRecoveryBlock", size_text(sizeof(FecRecoveryBlock)),
                     "bytes");
    runner.add_value("memory", "sizeof", "ClockSynchronizer", size_text(sizeof(ClockSynchronizer)),
                     "bytes");
    runner.add_value("memory", "sizeof", "NetworkTelemetry", size_text(sizeof(NetworkTelemetry)),
                     "bytes");

    for (std::size_t budget : {512U, 768U, 1024U, 1200U, 1400U}) {
        const auto fec_budget = warpnect::scl::fec_max_protected_datagram_size(budget);
        const std::string scenario = scenario_text("wire_budget", budget, "B");
        runner.add_value("budget", "fragment_payload_capacity", scenario,
                         size_text(budget - warpnect::scl::kPacketHeaderWireSize), "bytes",
                         "without FEC parity compatibility");
        runner.add_value("budget", "fec_max_protected_datagram", scenario,
                         fec_budget.ok() ? size_text(fec_budget.size) : "invalid", "bytes",
                         "budget - 21 - 16 - 2");
        if (fec_budget.ok()) {
            const auto fragments = warpnect::scl::calculate_fragment_count(
                FragmentationConfig{.max_datagram_size = fec_budget.size}, 64U * 1024U);
            runner.add_value("budget", "fragment_count_for_64KiB", scenario,
                             fragments.ok() ? size_text(fragments.total_slices) : "invalid",
                             "fragments", "benchmark scenario only");
        }
    }

    runner.add_value("overhead", "scl_packet_header", "v1", "21", "bytes");
    runner.add_value("overhead", "fec_parity_header", "v1", "16", "bytes");
    runner.add_value("overhead", "fec_original_length_prefix", "v1", "2", "bytes");
    runner.add_value("overhead", "nack_payload", "v1", "16", "bytes");

    const auto config = fec_config(8, 2, 1200);
    runner.add_value("workspace", "loss_detector", "slots=128", size_text(sizeof(LossSlot) * 128U),
                     "bytes", "caller-provided slots");
    runner.add_value("workspace", "retransmission_cache", "slots=64,max=1200",
                     size_text(64U * 1200U + sizeof(RetransmissionEntry) * 64U), "bytes",
                     "caller-provided datagrams plus entries");
    runner.add_value("workspace", "fec_encoder_data", "K=8,M=2,budget=1200",
                     size_text(warpnect::scl::required_fec_encoder_data_storage_size(config).size),
                     "bytes");
    runner.add_value(
        "workspace", "fec_encoder_parity", "K=8,M=2,budget=1200",
        size_text(warpnect::scl::required_fec_encoder_parity_storage_size(config).size), "bytes");
    runner.add_value(
        "workspace", "fec_recovery", "K=8,M=2,budget=1200",
        size_text(warpnect::scl::required_fec_recovery_shard_storage_size(config).size), "bytes");
    runner.add_value("workspace", "clock_sync_samples", "samples=16",
                     size_text(sizeof(warpnect::scl::ClockSyncSample) * 16U), "bytes");
    runner.add_value("workspace", "telemetry_windows", "rtt=64,one_way=64",
                     size_text(sizeof(std::uint64_t) * 128U), "bytes");

    runner.add_value("allocation_audit", "packet_encode", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "packet_decode", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "fragment_iteration", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "reassembly_accept", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "loss_observe", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "nack_codec", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "cache_lookup", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "fec_encode_recover", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "clock_conversion", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "telemetry_recording", "after_setup", "0", "allocations");
    runner.add_value("allocation_audit", "udp_send_receive", "static_code_audit", "0",
                     "scl_core allocations", "OS/library internals outside C++ heap audit");
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
    packet_benchmarks(runner);
    fragmentation_benchmarks(runner);
    reassembly_benchmarks(runner);
    udp_benchmarks(runner);
    recovery_benchmarks(runner);
    fec_benchmarks(runner);
    clock_and_telemetry_benchmarks(runner);
    end_to_end_benchmarks(runner);
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
