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

#include "simulated_network.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <new>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::ClockExchangeTracker;
using warpnect::scl::ClockSyncConfig;
using warpnect::scl::ClockSynchronizer;
using warpnect::scl::ClockSyncRequest;
using warpnect::scl::ClockSyncResponse;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::FecBlockEncoder;
using warpnect::scl::FecEncoderWorkspace;
using warpnect::scl::FecError;
using warpnect::scl::FecParityView;
using warpnect::scl::FecRecoveryBlock;
using warpnect::scl::FecRecoveryWorkspace;
using warpnect::scl::FragmentationConfig;
using warpnect::scl::FragmentCursor;
using warpnect::scl::FragmentError;
using warpnect::scl::LossDetector;
using warpnect::scl::LossObservationKind;
using warpnect::scl::LossRecoveryConfig;
using warpnect::scl::LossSlot;
using warpnect::scl::NackRequest;
using warpnect::scl::NackSequenceCursor;
using warpnect::scl::NetworkTelemetry;
using warpnect::scl::NetworkTelemetryStorage;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::PendingClockExchange;
using warpnect::scl::ReassemblySlot;
using warpnect::scl::ReassemblyWorkspace;
using warpnect::scl::RecoveryError;
using warpnect::scl::ReedSolomonConfig;
using warpnect::scl::ReedSolomonWorkspace;
using warpnect::scl::RetransmissionCache;
using warpnect::scl::RetransmissionCacheConfig;
using warpnect::scl::RetransmissionCacheWorkspace;
using warpnect::scl::RetransmissionEntry;
using warpnect::scl::TimingError;
using warpnect::test_support::ScriptedNetwork;
using warpnect::test_support::SimulatedSendOptions;

int failures = 0;
bool count_allocations = false;
std::size_t allocation_count = 0;

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

void fill_payload(std::span<std::byte> bytes, std::uint8_t seed = 19) noexcept {
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 41U + seed) & 0xFFU));
    }
}

[[nodiscard]] constexpr PacketHeader logical_header(std::uint32_t base_sequence,
                                                    PayloadType payload_type,
                                                    std::uint64_t timestamp_us) noexcept {
    return PacketHeader{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0x00A5,
        .sequence_number = base_sequence,
        .timestamp_us = timestamp_us,
        .payload_type = payload_type,
        .slice_index = 0,
        .total_slices = 1,
    };
}

struct DatagramRecord final {
    std::vector<std::byte> bytes{};
    std::uint32_t sequence = 0;
    std::uint16_t slice_index = 0;
    bool is_data = true;
};

struct Phase1Scenario final {
    std::string_view name{};
    std::size_t payload_size = 2000;
    std::size_t wire_budget = 512;
    std::uint32_t base_sequence = 1000;
    std::uint8_t parity_shards = 2;
    bool send_parity = true;
    bool use_fec_recovery = true;
    bool use_nack_fallback = false;
    bool evict_cache = false;
    std::vector<std::uint16_t> dropped_data{};
    std::vector<std::uint16_t> duplicated_data{};
    std::vector<std::uint64_t> data_delays_us{};
};

struct Phase1Result final {
    bool completed = false;
    bool explicit_unrecoverable = false;
    std::size_t data_datagrams = 0;
    std::size_t fec_recovered = 0;
    std::size_t nacks = 0;
    std::size_t nack_sequences = 0;
    std::size_t retransmissions = 0;
    std::size_t duplicates_seen = 0;
    std::size_t late_seen = 0;
    std::size_t gaps_created = 0;
    NetworkTelemetry telemetry{NetworkTelemetryStorage{}};
};

[[nodiscard]] bool contains_index(const std::vector<std::uint16_t>& values,
                                  std::uint16_t needle) noexcept {
    return std::find(values.begin(), values.end(), needle) != values.end();
}

[[nodiscard]] std::uint64_t data_delay_for(const Phase1Scenario& scenario,
                                           std::uint16_t index) noexcept {
    if (index < scenario.data_delays_us.size()) {
        return scenario.data_delays_us[index];
    }
    return 0;
}

[[nodiscard]] bool append_encoded_fragment(const warpnect::scl::FragmentView& fragment,
                                           std::vector<DatagramRecord>& records) {
    const auto size = warpnect::scl::encoded_packet_size(fragment.payload.size());
    if (!size.ok()) {
        return false;
    }

    DatagramRecord record{};
    record.bytes.resize(size.bytes_written);
    const auto encoded = warpnect::scl::encode_packet(fragment.header, fragment.payload,
                                                      std::span<std::byte>(record.bytes));
    if (!encoded.ok()) {
        return false;
    }

    record.sequence = fragment.header.sequence_number;
    record.slice_index = fragment.header.slice_index;
    records.push_back(std::move(record));
    return true;
}

[[nodiscard]] bool build_data_datagrams(const Phase1Scenario& scenario,
                                        std::vector<std::byte>& logical_payload,
                                        std::vector<DatagramRecord>& records,
                                        FragmentationConfig& fragmentation_config) {
    const auto protected_budget =
        warpnect::scl::fec_max_protected_datagram_size(scenario.wire_budget);
    if (!protected_budget.ok()) {
        return false;
    }

    fragmentation_config = FragmentationConfig{.max_datagram_size = protected_budget.size};
    logical_payload.resize(scenario.payload_size);
    fill_payload(logical_payload, static_cast<std::uint8_t>(scenario.base_sequence & 0xFFU));

    const auto plan = warpnect::scl::plan_fragments(
        fragmentation_config,
        logical_header(scenario.base_sequence, PayloadType::Video,
                       0x0102030405060708ULL + scenario.base_sequence),
        logical_payload);
    if (!plan.ok()) {
        return false;
    }

    FragmentCursor cursor(plan.plan);
    while (cursor.has_next()) {
        const auto fragment = cursor.next();
        if (!fragment.ok() || !append_encoded_fragment(fragment.fragment, records)) {
            return false;
        }
    }

    return !records.empty();
}

[[nodiscard]] FecBlockConfig fec_config_for(const Phase1Scenario& scenario,
                                            std::size_t data_count) noexcept {
    return FecBlockConfig{
        .rs =
            ReedSolomonConfig{
                .data_shards = static_cast<std::uint8_t>(data_count),
                .parity_shards = scenario.parity_shards,
            },
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = scenario.base_sequence,
        .max_wire_datagram_size = scenario.wire_budget,
    };
}

struct FecStorage final {
    std::vector<std::byte> encoder_data{};
    std::vector<std::byte> encoder_parity{};
    std::vector<std::byte> recovery_shards{};
    std::vector<std::byte> recovery_presence{};
    std::vector<std::byte> matrix{};
    std::vector<std::byte> scratch{};

    [[nodiscard]] bool resize(const FecBlockConfig& config) {
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

    [[nodiscard]] FecEncoderWorkspace encoder_workspace() noexcept {
        return FecEncoderWorkspace{
            .data_shard_storage = encoder_data,
            .parity_shard_storage = encoder_parity,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }

    [[nodiscard]] FecRecoveryWorkspace recovery_workspace() noexcept {
        return FecRecoveryWorkspace{
            .shard_storage = recovery_shards,
            .present_bitmap = recovery_presence,
            .rs_workspace =
                ReedSolomonWorkspace{.matrix_storage = matrix, .scratch_storage = scratch},
        };
    }
};

[[nodiscard]] bool append_fec_parity_packets(const Phase1Scenario& scenario,
                                             FecBlockEncoder& encoder,
                                             std::vector<DatagramRecord>& records) {
    for (std::uint8_t i = 0; i < scenario.parity_shards; ++i) {
        const auto view = encoder.parity_view(i);
        if (!view.ok()) {
            return false;
        }

        const auto payload_size = warpnect::scl::fec_parity_payload_size(view.parity.header);
        if (!payload_size.ok()) {
            return false;
        }

        std::vector<std::byte> control_payload(payload_size.size);
        if (!warpnect::scl::encode_fec_parity_payload(view.parity, control_payload).ok()) {
            return false;
        }

        PacketHeader control_header{
            .protocol_version = warpnect::scl::kSclProtocolVersion,
            .flags = 0,
            .sequence_number = 0x70000000U + i,
            .timestamp_us = 0x0203040506070809ULL,
            .payload_type = PayloadType::SessionControl,
            .slice_index = 0,
            .total_slices = 1,
        };

        const auto encoded_size = warpnect::scl::encoded_packet_size(control_payload.size());
        if (!encoded_size.ok()) {
            return false;
        }

        DatagramRecord record{};
        record.bytes.resize(encoded_size.bytes_written);
        if (!warpnect::scl::encode_packet(control_header, control_payload, record.bytes).ok()) {
            return false;
        }
        record.sequence = control_header.sequence_number;
        record.is_data = false;
        records.push_back(std::move(record));
    }

    return true;
}

void record_observation(NetworkTelemetry& telemetry,
                        const warpnect::scl::LossObservationResult& obs,
                        Phase1Result& result) noexcept {
    if (obs.kind == LossObservationKind::GapDetected) {
        telemetry.record_gap_detected(obs.missing_created);
        result.gaps_created += obs.missing_created;
    } else if (obs.kind == LossObservationKind::RecoveredMissing) {
        telemetry.record_late_packet();
        ++result.late_seen;
    } else if (obs.kind == LossObservationKind::Duplicate) {
        telemetry.record_duplicate_packet();
        ++result.duplicates_seen;
    }
}

[[nodiscard]] bool payload_completed(const ReassemblySlot& reassembly,
                                     std::span<const std::byte> logical_payload) {
    if (!reassembly.is_complete()) {
        return false;
    }

    const auto result = reassembly.result();
    return result.ok() && bytes_equal(result.payload.payload, logical_payload);
}

void process_received_datagram(std::span<const std::byte> datagram,
                               const FecBlockConfig& fec_config, FecRecoveryBlock& fec_recovery,
                               LossDetector& detector, ReassemblySlot& reassembly,
                               NetworkTelemetry& telemetry, Phase1Result& result,
                               std::uint64_t now_us, bool retransmitted = false) {
    telemetry.record_datagram_received(datagram.size());
    const auto packet = warpnect::scl::decode_packet(datagram);
    expect(packet.ok(), "phase1 received datagram decodes");
    if (!packet.ok()) {
        return;
    }

    if (packet.packet.header.payload_type == PayloadType::SessionControl) {
        const auto parity = warpnect::scl::decode_fec_parity_payload(packet.packet.payload);
        expect(parity.ok(), "phase1 FEC parity payload decodes");
        if (parity.ok()) {
            const auto accepted = fec_recovery.accept_parity(parity.parity);
            expect(accepted.ok(), "phase1 FEC recovery accepts parity");
        }
        return;
    }

    const auto observed = detector.observe(packet.packet.header.sequence_number, now_us);
    expect(observed.ok(), "phase1 loss detector observes data");
    record_observation(telemetry, observed, result);

    const auto accepted_data = fec_recovery.accept_data_datagram(fec_config, datagram);
    expect(accepted_data.ok() || accepted_data.error == FecError::DuplicateShard,
           "phase1 FEC recovery accepts data or duplicate");

    const auto accepted_fragment = reassembly.accept(packet.packet);
    expect(accepted_fragment.ok() || accepted_fragment.error == FragmentError::DuplicateFragment,
           "phase1 reassembly accepts data or duplicate");

    if (retransmitted) {
        telemetry.record_retransmission_received_or_observed();
    }
}

[[nodiscard]] Phase1Result run_phase1_pipeline(const Phase1Scenario& scenario) {
    std::array<std::uint64_t, 16> rtt_samples{};
    std::array<std::uint64_t, 16> one_way_samples{};
    Phase1Result result{.telemetry = NetworkTelemetry(NetworkTelemetryStorage{
                            .rtt_samples = rtt_samples,
                            .one_way_delay_samples = one_way_samples,
                        })};

    std::vector<std::byte> logical_payload{};
    std::vector<DatagramRecord> data_records{};
    FragmentationConfig fragmentation_config{};
    expect(build_data_datagrams(scenario, logical_payload, data_records, fragmentation_config),
           "phase1 data datagrams build");
    result.data_datagrams = data_records.size();

    const FecBlockConfig fec_config = fec_config_for(scenario, data_records.size());
    FecStorage fec_storage{};
    expect(fec_storage.resize(fec_config), "phase1 FEC storage sizes");

    FecBlockEncoder fec_encoder(fec_config, fec_storage.encoder_workspace());
    for (const DatagramRecord& record : data_records) {
        expect(fec_encoder.accept_data_datagram(record.bytes).ok(), "phase1 FEC encoder accepts");
    }
    expect(fec_encoder.encode().ok(), "phase1 FEC encoder encodes parity");
    result.telemetry.record_fec_block_encoded(scenario.parity_shards);

    std::vector<DatagramRecord> all_records = data_records;
    if (scenario.send_parity) {
        expect(append_fec_parity_packets(scenario, fec_encoder, all_records),
               "phase1 FEC parity packets build");
    }

    const std::size_t cache_slots = scenario.evict_cache ? 1U : data_records.size() + 4U;
    std::vector<std::byte> cache_storage(cache_slots * fragmentation_config.max_datagram_size);
    std::vector<RetransmissionEntry> cache_entries(cache_slots);
    RetransmissionCache cache(
        RetransmissionCacheConfig{.slot_count = cache_slots,
                                  .max_datagram_size = fragmentation_config.max_datagram_size},
        RetransmissionCacheWorkspace{.datagram_storage = cache_storage, .entries = cache_entries});
    for (const DatagramRecord& record : data_records) {
        expect(cache.store(PayloadType::Video, record.sequence, record.bytes).ok(),
               "phase1 cache stores original datagram");
    }

    const auto metadata_size = warpnect::scl::required_reassembly_metadata_size(
        static_cast<std::uint16_t>(data_records.size()));
    expect(metadata_size.ok(), "phase1 reassembly metadata size");
    const auto max_reassembly_size = warpnect::scl::max_reassembled_payload_size(
        fragmentation_config, static_cast<std::uint16_t>(data_records.size()));
    expect(max_reassembly_size.ok(), "phase1 reassembly payload storage size");
    std::vector<std::byte> reassembly_storage(max_reassembly_size.size);
    std::vector<std::byte> reassembly_metadata(metadata_size.size);
    ReassemblySlot reassembly(fragmentation_config,
                              ReassemblyWorkspace{.payload_storage = reassembly_storage,
                                                  .received_bitmap = reassembly_metadata});
    FecRecoveryBlock fec_recovery(fec_storage.recovery_workspace());
    std::array<LossSlot, 128> loss_slots{};
    LossDetector detector(LossRecoveryConfig{.reorder_delay_us = 100,
                                             .renack_interval_us = 200,
                                             .max_nack_attempts = 3},
                          loss_slots);

    ScriptedNetwork network{};
    for (const DatagramRecord& record : all_records) {
        SimulatedSendOptions options{};
        if (record.is_data) {
            options.drop = contains_index(scenario.dropped_data, record.slice_index);
            options.copies = contains_index(scenario.duplicated_data, record.slice_index) ? 2 : 1;
            options.delay_us = data_delay_for(scenario, record.slice_index);
        }
        network.submit(record.bytes, options);
        if (!options.drop) {
            result.telemetry.record_datagram_sent(record.bytes.size());
        }
    }

    network.advance_to(1000);
    for (const auto& delivered : network.drain_due()) {
        process_received_datagram(delivered.data, fec_config, fec_recovery, detector, reassembly,
                                  result.telemetry, result, delivered.delivery_time_us);
    }

    if (scenario.use_fec_recovery && !payload_completed(reassembly, logical_payload)) {
        const auto recovery = fec_recovery.recover();
        if (recovery.ok()) {
            result.telemetry.record_fec_recovery_attempt(true, scenario.dropped_data.size());
            for (std::uint16_t index : scenario.dropped_data) {
                const auto recovered = fec_recovery.datagram(static_cast<std::uint8_t>(index));
                if (recovered.ok()) {
                    expect(bytes_equal(recovered.datagram.datagram, data_records[index].bytes),
                           "phase1 FEC recovered exact datagram");
                    ++result.fec_recovered;
                    process_received_datagram(recovered.datagram.datagram, fec_config, fec_recovery,
                                              detector, reassembly, result.telemetry, result, 1100);
                }
            }
        } else {
            result.telemetry.record_fec_recovery_attempt(false, 0);
        }
    }

    if (scenario.use_nack_fallback && !payload_completed(reassembly, logical_payload)) {
        std::array<NackRequest, 8> requests{};
        const auto collected = detector.collect_due_nacks(1200, PayloadType::Video, requests);
        if (collected.ok() || collected.error == RecoveryError::OutputBufferTooSmall) {
            result.nacks += collected.requests_written;
            for (std::size_t i = 0; i < collected.requests_written; ++i) {
                std::array<std::byte, warpnect::scl::kNackPayloadWireSize> encoded_nack{};
                expect(warpnect::scl::encode_nack(requests[i], encoded_nack).ok(),
                       "phase1 NACK encodes");
                const auto decoded_nack = warpnect::scl::decode_nack(encoded_nack);
                expect(decoded_nack.ok(), "phase1 NACK decodes");
                NackSequenceCursor cursor(decoded_nack.request);
                std::size_t requested_sequences = 0;
                while (cursor.has_next()) {
                    const auto sequence = cursor.next();
                    if (!sequence.ok()) {
                        continue;
                    }
                    ++requested_sequences;
                    ++result.nack_sequences;
                    const auto cached = cache.find(PayloadType::Video, sequence.sequence_number);
                    if (!cached.ok()) {
                        result.explicit_unrecoverable = true;
                        continue;
                    }
                    result.telemetry.record_retransmission_sent();
                    ++result.retransmissions;
                    process_received_datagram(cached.datagram, fec_config, fec_recovery, detector,
                                              reassembly, result.telemetry, result, 1300, true);
                }
                result.telemetry.record_nack_generated(requested_sequences);
            }
        }
    }

    result.completed = payload_completed(reassembly, logical_payload);
    return result;
}

void test_simulated_network_impairments() {
    ScriptedNetwork network{};
    std::array<std::byte, 2> a{byte(0xA0), byte(0xA1)};
    std::array<std::byte, 2> b{byte(0xB0), byte(0xB1)};
    std::array<std::byte, 2> c{byte(0xC0), byte(0xC1)};

    network.submit(a, SimulatedSendOptions{.drop = true});
    network.submit(b, SimulatedSendOptions{.copies = 2, .delay_us = 20});
    network.submit(c, SimulatedSendOptions{.copies = 1, .delay_us = 10});
    network.advance_to(15);
    auto due = network.drain_due();
    expect_equal(due.size(), static_cast<std::size_t>(1), "simulator reorders by delivery time");
    expect(bytes_equal(due[0].data, c), "simulator delivers earlier delayed datagram first");
    network.advance_to(25);
    due = network.drain_due();
    expect_equal(due.size(), static_cast<std::size_t>(2), "simulator duplicates datagrams");
    expect(bytes_equal(due[0].data, b) && bytes_equal(due[1].data, b),
           "simulator duplicate bytes preserved");

    network.reset();
    for (std::uint8_t i = 0; i < 8; ++i) {
        std::array<std::byte, 1> value{byte(i)};
        network.submit(value, SimulatedSendOptions{.drop = static_cast<bool>((i % 3U) == 0U)});
    }
    network.advance_to(1);
    due = network.drain_due();
    expect_equal(due.size(), static_cast<std::size_t>(5), "periodic scripted loss works");
}

void test_phase1_pipeline_scenarios() {
    const auto zero_loss = run_phase1_pipeline(Phase1Scenario{.name = "zero_loss"});
    expect(zero_loss.completed, "zero-loss full pipeline completes");
    expect_equal(zero_loss.fec_recovered, static_cast<std::size_t>(0),
                 "zero loss needs no FEC recovery");
    expect_equal(zero_loss.nacks, static_cast<std::size_t>(0), "zero loss emits no NACK");
    expect_equal(zero_loss.retransmissions, static_cast<std::size_t>(0),
                 "zero loss retransmits nothing");

    Phase1Scenario reordered{.name = "reordered"};
    reordered.data_delays_us = {0, 40, 10, 20, 30};
    const auto reorder = run_phase1_pipeline(reordered);
    expect(reorder.completed, "out-of-order full pipeline completes");
    expect(reorder.gaps_created >= 1, "reordering temporarily creates a gap");
    expect(reorder.late_seen >= 1, "reordering records a late packet");
    expect_equal(reorder.nacks, static_cast<std::size_t>(0), "reordering avoids early NACK");

    Phase1Scenario duplicated{.name = "duplicate"};
    duplicated.duplicated_data = {1};
    const auto duplicate = run_phase1_pipeline(duplicated);
    expect(duplicate.completed, "duplicate full pipeline completes");
    expect(duplicate.duplicates_seen >= 1, "duplicate packet observed");

    Phase1Scenario fec_loss{.name = "fec_recovered"};
    fec_loss.dropped_data = {2};
    const auto fec = run_phase1_pipeline(fec_loss);
    expect(fec.completed, "FEC-recoverable full pipeline completes");
    expect_equal(fec.fec_recovered, static_cast<std::size_t>(1), "one datagram recovered by FEC");
    expect_equal(fec.nacks, static_cast<std::size_t>(0), "FEC recovery needs no NACK");

    Phase1Scenario nack_loss{.name = "nack_recovered"};
    nack_loss.send_parity = false;
    nack_loss.use_fec_recovery = false;
    nack_loss.use_nack_fallback = true;
    nack_loss.dropped_data = {2};
    const auto nack = run_phase1_pipeline(nack_loss);
    expect(nack.completed, "NACK-recovered full pipeline completes");
    expect(nack.nacks >= 1, "NACK recovery emits request");
    expect_equal(nack.retransmissions, static_cast<std::size_t>(1),
                 "NACK recovery retransmits one datagram");

    Phase1Scenario fallback{.name = "fec_nack_fallback", .parity_shards = 1};
    fallback.dropped_data = {2, 3};
    fallback.use_nack_fallback = true;
    const auto recovered_by_nack = run_phase1_pipeline(fallback);
    expect(recovered_by_nack.completed, "FEC-exceeded NACK fallback completes");
    expect_equal(recovered_by_nack.fec_recovered, static_cast<std::size_t>(0),
                 "FEC over capacity does not fabricate recovery");
    expect_equal(recovered_by_nack.retransmissions, static_cast<std::size_t>(2),
                 "fallback retransmits missing datagrams");

    Phase1Scenario wrap{.name = "sequence_wrap", .base_sequence = 0xFFFFFFFCU};
    wrap.dropped_data = {2};
    const auto wrapped = run_phase1_pipeline(wrap);
    expect(wrapped.completed, "sequence-wrap full pipeline completes");
    expect_equal(wrapped.fec_recovered, static_cast<std::size_t>(1),
                 "sequence-wrap FEC recovery succeeds");

    Phase1Scenario unrecoverable{.name = "unrecoverable", .parity_shards = 1};
    unrecoverable.dropped_data = {2, 3};
    unrecoverable.use_nack_fallback = true;
    unrecoverable.evict_cache = true;
    const auto failed = run_phase1_pipeline(unrecoverable);
    expect(!failed.completed, "unrecoverable pipeline does not claim success");
    expect(failed.explicit_unrecoverable, "unrecoverable pipeline reports missing cache entry");
}

void test_workspace_reuse_and_stress() {
    for (std::uint32_t i = 0; i < 80; ++i) {
        Phase1Scenario scenario{.name = "stress",
                                .payload_size = 1200 + (i % 7U) * 73U,
                                .base_sequence = 10'000U + (i * 16U)};
        if ((i % 4U) == 1U) {
            scenario.dropped_data = {2};
        } else if ((i % 4U) == 2U) {
            scenario.duplicated_data = {1};
        } else if ((i % 4U) == 3U) {
            scenario.data_delays_us = {0, 30, 10, 20, 25};
        }
        const auto result = run_phase1_pipeline(scenario);
        expect(result.completed, "stress/reuse recoverable payload completes");
    }
}

void test_clock_sync_control_exchange_and_telemetry() {
    std::array<PendingClockExchange, 4> pending{};
    ClockExchangeTracker tracker(pending);
    std::array<warpnect::scl::ClockSyncSample, 8> samples{};
    ClockSynchronizer synchronizer(ClockSyncConfig{.min_samples_for_model = 2,
                                                   .max_accepted_rtt_us = 1000,
                                                   .max_abs_drift_ppm = 1000.0,
                                                   .stale_after_us = 10'000},
                                   samples);
    std::array<std::uint64_t, 8> rtt{};
    std::array<std::uint64_t, 8> one_way{};
    NetworkTelemetry telemetry(
        NetworkTelemetryStorage{.rtt_samples = rtt, .one_way_delay_samples = one_way});

    for (std::uint32_t i = 0; i < 3; ++i) {
        const ClockSyncRequest request{.exchange_id = i, .t0_us = 1000 + (i * 1000)};
        std::array<std::byte, warpnect::scl::kClockSyncRequestWireSize> request_wire{};
        expect(tracker.register_request(request.exchange_id, request.t0_us).ok(),
               "clock exchange registers");
        expect(warpnect::scl::encode_clock_sync_request(request, request_wire).ok(),
               "clock request encodes");
        telemetry.record_clock_request_sent();
        const auto decoded_request = warpnect::scl::decode_clock_sync_request(request_wire);
        expect(decoded_request.ok(), "clock request decodes");

        const ClockSyncResponse response{
            .exchange_id = decoded_request.request.exchange_id,
            .t0_us = decoded_request.request.t0_us,
            .t1_us = decoded_request.request.t0_us + 600,
            .t2_us = decoded_request.request.t0_us + 620,
        };
        std::array<std::byte, warpnect::scl::kClockSyncResponseWireSize> response_wire{};
        expect(warpnect::scl::encode_clock_sync_response(response, response_wire).ok(),
               "clock response encodes");
        telemetry.record_clock_response_received();
        const auto decoded_response = warpnect::scl::decode_clock_sync_response(response_wire);
        expect(decoded_response.ok(), "clock response decodes");

        const auto completed =
            tracker.complete_response(decoded_response.response, request.t0_us + 220);
        expect(completed.ok(), "clock exchange completes");
        const auto update = synchronizer.add_sample(completed.sample);
        expect(update.ok(), "clock sample accepted into synchronizer");
        telemetry.record_clock_sample_accepted(completed.sample.rtt_us);
    }

    const auto snapshot = synchronizer.snapshot(3300);
    expect_equal(snapshot.state, warpnect::scl::ClockSyncState::Synchronized,
                 "clock sync integration reaches synchronized state");
    const auto telemetry_snapshot = telemetry.snapshot(snapshot);
    expect_equal(telemetry_snapshot.counters.clock_requests_sent, static_cast<std::uint64_t>(3),
                 "clock telemetry request count");
    expect_equal(telemetry_snapshot.counters.clock_responses_received,
                 static_cast<std::uint64_t>(3), "clock telemetry response count");
    expect_equal(telemetry_snapshot.counters.clock_samples_accepted, static_cast<std::uint64_t>(3),
                 "clock telemetry accepted sample count");
    expect_equal(telemetry_snapshot.rtt.sample_count, static_cast<std::size_t>(3),
                 "clock telemetry RTT window");
}

void test_zero_allocation_hot_paths() {
    std::array<std::byte, 128> packet_buffer{};
    std::array<std::byte, 16> payload{};
    fill_payload(payload);
    const PacketHeader header = logical_header(500, PayloadType::Input, 1234);
    expect(warpnect::scl::encode_packet(header, payload, packet_buffer).ok(),
           "allocation audit packet setup");
    const auto packet_size = warpnect::scl::encoded_packet_size(payload.size()).bytes_written;

    std::array<std::byte, 64> fragment_payload{};
    fill_payload(fragment_payload);
    const FragmentationConfig fragment_config{.max_datagram_size = 48};
    const auto plan = warpnect::scl::plan_fragments(fragment_config, header, fragment_payload);
    expect(plan.ok(), "allocation audit fragment plan setup");
    FragmentCursor cursor(plan.plan);

    std::array<std::byte, 64> reassembly_storage{};
    std::array<std::byte, 1> reassembly_metadata{};
    ReassemblySlot reassembly(fragment_config,
                              ReassemblyWorkspace{.payload_storage = reassembly_storage,
                                                  .received_bitmap = reassembly_metadata});

    std::array<LossSlot, 16> loss_slots{};
    LossDetector detector(
        LossRecoveryConfig{.reorder_delay_us = 0, .renack_interval_us = 10, .max_nack_attempts = 2},
        loss_slots);
    expect(detector.observe(500, 0).ok(), "allocation audit loss setup");
    expect(detector.observe(502, 0).ok(), "allocation audit gap setup");

    std::array<NackRequest, 1> nacks{};
    std::array<std::byte, warpnect::scl::kNackPayloadWireSize> nack_wire{};

    std::array<std::uint64_t, 4> rtt{};
    std::array<std::uint64_t, 4> one_way{};
    NetworkTelemetry telemetry(
        NetworkTelemetryStorage{.rtt_samples = rtt, .one_way_delay_samples = one_way});

    const auto decoded_packet =
        warpnect::scl::decode_packet(std::span<const std::byte>(packet_buffer).first(packet_size));
    expect(decoded_packet.ok(), "allocation audit decoded packet setup");

    allocation_count = 0;
    count_allocations = true;

    std::array<std::byte, 128> output{};
    (void)warpnect::scl::encode_packet(header, payload, output);
    (void)warpnect::scl::decode_packet(
        std::span<const std::byte>(packet_buffer).first(packet_size));
    const auto fragment = cursor.next();
    if (fragment.ok()) {
        (void)reassembly.accept(decoded_packet.packet);
    }
    (void)detector.collect_due_nacks(0, PayloadType::Input, nacks);
    (void)warpnect::scl::encode_nack(NackRequest{.target_payload_type = PayloadType::Input,
                                                 .base_sequence_number = 501,
                                                 .missing_bitmap = 1},
                                     nack_wire);
    (void)warpnect::scl::decode_nack(nack_wire);
    telemetry.record_datagram_sent(32);
    (void)telemetry.snapshot(warpnect::scl::ClockModelSnapshot{});

    count_allocations = false;
    expect_equal(allocation_count, static_cast<std::size_t>(0),
                 "selected C++ hot paths allocate zero heap memory after setup");
}

} // namespace

void* operator new(std::size_t size) {
    if (count_allocations) {
        ++allocation_count;
    }
    if (void* pointer = std::malloc(size)) {
        return pointer;
    }
    throw std::bad_alloc{};
}

void* operator new[](std::size_t size) {
    if (count_allocations) {
        ++allocation_count;
    }
    if (void* pointer = std::malloc(size)) {
        return pointer;
    }
    throw std::bad_alloc{};
}

void operator delete(void* pointer) noexcept {
    std::free(pointer);
}

void operator delete[](void* pointer) noexcept {
    std::free(pointer);
}

void operator delete(void* pointer, std::size_t) noexcept {
    std::free(pointer);
}

void operator delete[](void* pointer, std::size_t) noexcept {
    std::free(pointer);
}

int main() {
    test_simulated_network_impairments();
    test_phase1_pipeline_scenarios();
    test_workspace_reuse_and_stress();
    test_clock_sync_control_exchange_and_telemetry();
    test_zero_allocation_hot_paths();

    if (failures != 0) {
        std::cerr << failures << " SCL Phase 1 integration test failure(s)\n";
        return 1;
    }

    std::cout << "SCL Phase 1 integration tests passed\n";
    return 0;
}
