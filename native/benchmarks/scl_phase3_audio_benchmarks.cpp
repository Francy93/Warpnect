#include "benchmark_runner.h"

#include "audio_opus_decoder.h"
#include "audio_opus_encoder.h"
#include "audio_packetizer.h"
#include "audio_receiver_runtime.h"
#include "audio_transport_result.h"

#include <algorithm>
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

using warpnect::audio::AudioBitrateMode;
using warpnect::audio::AudioCaptureSource;
using warpnect::audio::AudioCodec;
using warpnect::audio::AudioDecoderError;
using warpnect::audio::AudioEncoderError;
using warpnect::audio::AudioEncoderSubmitStatus;
using warpnect::audio::DecodedAudioFrameKind;
using warpnect::audio::EncodedAudioFrameMetadata;
using warpnect::audio::MissingAudioFrameMetadata;
using warpnect::audio::OpusAudioDecoder;
using warpnect::audio::OpusAudioDecoderConfig;
using warpnect::audio::OpusAudioEncoder;
using warpnect::audio::OpusAudioEncoderConfig;
using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::AudioDatagramSink;
using warpnect::scl::AudioFrameDurationCode;
using warpnect::scl::AudioPacketizer;
using warpnect::scl::AudioPacketizerConfig;
using warpnect::scl::AudioReceiverConfig;
using warpnect::scl::AudioReceiverRuntime;
using warpnect::scl::AudioTransportError;
using warpnect::scl::AudioTransportStatus;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::UdpEndpoint;

struct AudioProfile final {
    std::uint32_t frame_duration_us = 5'000;
    std::uint8_t channels = 1;
    std::uint32_t bitrate_bps = 64'000;
    AudioBitrateMode bitrate_mode = AudioBitrateMode::ConstantBitrate;
    std::uint8_t complexity = 5;
};

struct PacketFixture final {
    AudioProfile profile{};
    std::uint32_t samples_per_frame = 0;
    std::vector<std::int16_t> pcm{};
    std::vector<std::byte> packet{};
};

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

[[nodiscard]] std::string profile_name(const AudioProfile& profile) {
    std::ostringstream stream;
    stream << "48k_" << (profile.channels == 2 ? "stereo" : "mono") << '_'
           << profile.frame_duration_us << "us_" << profile.bitrate_bps / 1000 << "kbps_c"
           << static_cast<int>(profile.complexity) << '_'
           << (profile.bitrate_mode == AudioBitrateMode::ConstantBitrate ? "cbr" : "cvbr");
    return stream.str();
}

[[nodiscard]] std::string scenario_name(std::string_view prefix, const AudioProfile& profile) {
    std::ostringstream stream;
    stream << prefix << '_' << profile_name(profile);
    return stream.str();
}

[[nodiscard]] std::span<const std::byte> pcm_bytes(const std::vector<std::int16_t>& pcm) noexcept {
    return std::span<const std::byte>(reinterpret_cast<const std::byte*>(pcm.data()),
                                      pcm.size() * sizeof(std::int16_t));
}

[[nodiscard]] std::span<const std::byte> bytes(const std::vector<std::byte>& data) noexcept {
    return std::span<const std::byte>(data.data(), data.size());
}

[[nodiscard]] std::vector<std::int16_t> make_pcm(std::uint32_t samples_per_frame,
                                                 std::uint8_t channels,
                                                 std::uint32_t seed) {
    std::vector<std::int16_t> pcm(static_cast<std::size_t>(samples_per_frame) * channels);
    for (std::size_t i = 0; i < pcm.size(); ++i) {
        pcm[i] = static_cast<std::int16_t>(((i + seed) * 113U) & 0x3FFFU);
    }
    return pcm;
}

[[nodiscard]] OpusAudioEncoderConfig encoder_config(const AudioProfile& profile) noexcept {
    return OpusAudioEncoderConfig{
        .codec = AudioCodec::Opus,
        .source = profile.channels == 2 ? AudioCaptureSource::SystemAudio
                                        : AudioCaptureSource::MicrophoneAudio,
        .sample_rate_hz = 48'000,
        .channel_count = profile.channels,
        .frame_duration_us = profile.frame_duration_us,
        .bitrate_bps = profile.bitrate_bps,
        .bitrate_mode = profile.bitrate_mode,
        .complexity = profile.complexity,
    };
}

[[nodiscard]] OpusAudioDecoderConfig decoder_config(const AudioProfile& profile) noexcept {
    return OpusAudioDecoderConfig{
        .codec = AudioCodec::Opus,
        .source = profile.channels == 2 ? AudioCaptureSource::SystemAudio
                                        : AudioCaptureSource::MicrophoneAudio,
        .config_generation = 1,
        .sample_rate_hz = 48'000,
        .channel_count = profile.channels,
        .frame_duration_us = profile.frame_duration_us,
        .lookahead_samples = profile.frame_duration_us == 2'500 ? 120U : 312U,
    };
}

[[nodiscard]] PacketFixture make_fixture(const AudioProfile& profile, std::uint32_t seed) {
    const auto enc_config = encoder_config(profile);
    const std::uint32_t samples =
        OpusAudioEncoder::samples_per_frame(enc_config.sample_rate_hz, enc_config.frame_duration_us);
    PacketFixture fixture{.profile = profile,
                          .samples_per_frame = samples,
                          .pcm = make_pcm(samples, profile.channels, seed)};
    OpusAudioEncoder encoder(enc_config);
    if (encoder.prepare().error != AudioEncoderError::None ||
        encoder.start().error != AudioEncoderError::None) {
        return fixture;
    }
    const auto encoded = encoder.submit_pcm(
        pcm_bytes(fixture.pcm), 0, 0,
        warpnect::audio::AudioTimestampQuality::AudioRecordTimestamp);
    if (encoded.status != AudioEncoderSubmitStatus::EncodedFrameReady) {
        return fixture;
    }
    const auto output = encoder.output_buffer();
    fixture.packet.assign(output.begin(), output.begin() + encoded.packet_size);
    return fixture;
}

[[nodiscard]] AudioFrameDurationCode duration_code(std::uint32_t duration_us) noexcept {
    return warpnect::scl::frame_duration_code_from_us(duration_us);
}

class CountingAudioSink final : public AudioDatagramSink {
  public:
    std::uint64_t bytes_emitted = 0;
    std::uint64_t datagrams_emitted = 0;

    [[nodiscard]] AudioTransportStatus
    send_audio_datagram(std::span<const std::byte> datagram) noexcept override {
        bytes_emitted += datagram.size();
        ++datagrams_emitted;
        return AudioTransportStatus{};
    }
};

class CollectingAudioSink final : public AudioDatagramSink {
  public:
    std::vector<std::vector<std::byte>> datagrams{};

    [[nodiscard]] AudioTransportStatus
    send_audio_datagram(std::span<const std::byte> datagram) noexcept override {
        datagrams.emplace_back(datagram.begin(), datagram.end());
        return AudioTransportStatus{};
    }
};

[[nodiscard]] std::vector<std::vector<std::byte>>
packetize_frame_datagrams(const PacketFixture& fixture, std::size_t max_wire) {
    std::vector<std::byte> scratch(max_wire);
    AudioPacketizer packetizer(std::span<std::byte>(scratch.data(), scratch.size()));
    CollectingAudioSink sink;
    (void)packetizer.emit_audio_frame(
        AudioPacketizerConfig{.max_datagram_size = max_wire},
        PayloadType::SystemAudio,
        10,
        1'000,
        1,
        0,
        warpnect::scl::AudioTimestampQuality::AudioRecordTimestamp,
        false,
        bytes(fixture.packet),
        sink);
    return sink.datagrams;
}

[[nodiscard]] AudioReceiverConfig receiver_config(std::size_t max_wire) noexcept {
    return AudioReceiverConfig{
        .local_endpoint = UdpEndpoint::loopback_v4(0),
        .remote_endpoint = UdpEndpoint::loopback_v4(40'000),
        .restrict_remote_endpoint = false,
        .payload_type = PayloadType::SystemAudio,
        .max_wire_datagram_size = max_wire,
        .max_logical_audio_payload_size = 4'096,
        .reassembly_slot_count = 4,
        .ready_slot_count = 4,
        .reassembly_timeout_us = 20'000,
    };
}

struct RecoveryMetrics final {
    std::uint32_t wait_us = 0;
    std::uint32_t normal = 0;
    std::uint32_t plc = 0;
    std::uint32_t late = 0;
    std::uint32_t reordered_recovered = 0;
    std::uint32_t large_gap_resets = 0;
    std::uint64_t added_wait_us = 0;
};

struct Arrival final {
    std::uint32_t frame_index = 0;
    std::uint64_t arrival_us = 0;
};

[[nodiscard]] bool contains_arrival_before(const std::vector<Arrival>& arrivals,
                                           std::size_t start_index,
                                           std::uint32_t frame_index,
                                           std::uint64_t deadline_us,
                                           std::uint64_t& arrival_us) noexcept {
    for (std::size_t i = start_index; i < arrivals.size(); ++i) {
        if (arrivals[i].frame_index == frame_index && arrivals[i].arrival_us <= deadline_us) {
            arrival_us = arrivals[i].arrival_us;
            return true;
        }
    }
    return false;
}

[[nodiscard]] RecoveryMetrics simulate_recovery(std::vector<Arrival> arrivals,
                                                std::uint32_t reorder_wait_us,
                                                std::uint32_t max_plc_frames) {
    std::stable_sort(arrivals.begin(), arrivals.end(), [](const Arrival& lhs, const Arrival& rhs) {
        return lhs.arrival_us < rhs.arrival_us;
    });

    RecoveryMetrics metrics{.wait_us = reorder_wait_us};
    std::uint32_t expected = 0;
    for (std::size_t i = 0; i < arrivals.size(); ++i) {
        const Arrival& arrival = arrivals[i];
        if (arrival.frame_index < expected) {
            ++metrics.late;
            continue;
        }
        if (arrival.frame_index == expected) {
            ++metrics.normal;
            ++expected;
            continue;
        }

        const std::uint32_t missing = arrival.frame_index - expected;
        bool recovered_by_wait = false;
        if (reorder_wait_us > 0 && missing == 1) {
            std::uint64_t recovered_arrival = 0;
            if (contains_arrival_before(arrivals, i + 1, expected,
                                        arrival.arrival_us + reorder_wait_us, recovered_arrival)) {
                metrics.added_wait_us += recovered_arrival - arrival.arrival_us;
                ++metrics.normal;
                ++metrics.reordered_recovered;
                ++expected;
                recovered_by_wait = true;
            }
        }

        if (!recovered_by_wait) {
            if (missing > max_plc_frames) {
                ++metrics.large_gap_resets;
                expected = arrival.frame_index;
            } else {
                metrics.plc += missing;
                expected += missing;
            }
        }

        if (arrival.frame_index == expected) {
            ++metrics.normal;
            ++expected;
        }
    }
    return metrics;
}

void add_environment(BenchmarkRunner& runner, std::string_view build_type) {
    runner.add_metadata("phase", "3-audio");
    runner.add_metadata("rfc", "003H");
    runner.add_metadata("build_type", std::string(build_type));
    runner.add_metadata("os", warpnect::benchmarks::current_os_name());
    runner.add_metadata("compiler", warpnect::benchmarks::compiler_name());
    runner.add_metadata("architecture", warpnect::benchmarks::architecture_name());
    runner.add_metadata("logical_cpu_count", std::to_string(std::thread::hardware_concurrency()));
    runner.add_metadata("codec", "libopus 1.6.1 RestrictedLowDelay");
    runner.add_metadata("mode", runner.options().smoke ? "smoke" : "standard");
    runner.add_metadata("iterations", std::to_string(runner.options().iterations));
}

void add_baseline_values(BenchmarkRunner& runner) {
    runner.add_value("audio_baseline", "capture_target_chunk", "production", "5000", "us");
    runner.add_value("audio_baseline", "codec_frame_duration", "production", "5000", "us");
    runner.add_value("audio_baseline", "recovery_policy", "production", "ImmediateFreshness", "policy");
    runner.add_value("audio_baseline", "network_reorder_wait", "production", "0", "us");
    runner.add_value("audio_baseline", "max_immediate_plc_frames", "production", "2", "frames");
    runner.add_value("audio_baseline", "max_recoverable_audio_age", "production", "10000", "us");
    runner.add_value("audio_baseline", "audio_reassembly_slots", "production", "4", "slots");
    runner.add_value("audio_baseline", "audio_ready_slots", "production", "4", "slots");
    runner.add_value("audio_baseline", "max_logical_audio_payload", "production", "4096", "bytes");
    runner.add_value("audio_baseline", "playback_ring_capacity", "production", "4", "codec_frames");
    runner.add_value("audio_baseline", "playback_start_threshold", "production", "1", "codec_frames");
    runner.add_value("audio_baseline", "oboe_requested_buffer", "production", "2", "bursts");
    runner.add_value("audio_baseline", "av_sync_sample_interval", "production", "20000", "us");
    runner.add_value("audio_baseline", "av_sync_startup_capacity_derived_hold", "production",
                     "15000", "us",
                     "4 codec-frame ring minus 1-frame start threshold at 5 ms");
    runner.add_value("audio_baseline", "av_sync_video_schedule_ahead_max", "production", "20000", "us");
}

void add_wire_values(BenchmarkRunner& runner, const PacketFixture& fixture) {
    const double packets_per_second = 1'000'000.0 / fixture.profile.frame_duration_us;
    const auto datagrams = packetize_frame_datagrams(fixture, 1'200);
    std::size_t bytes_per_frame = 0;
    for (const auto& datagram : datagrams) {
        bytes_per_frame += datagram.size();
    }
    const std::size_t logical_header_bytes = 21U + 16U;
    const std::string scenario = profile_name(fixture.profile);
    runner.add_value("audio_wire", "packets_per_second", scenario,
                     std::to_string(static_cast<std::uint64_t>(packets_per_second)), "packets/s");
    runner.add_value("audio_wire", "representative_opus_packet", scenario,
                     std::to_string(fixture.packet.size()), "bytes");
    runner.add_value("audio_wire", "wire_bytes_per_audio_frame", scenario,
                     std::to_string(bytes_per_frame), "bytes");
    runner.add_value("audio_wire", "header_overhead_per_second", scenario,
                     std::to_string(static_cast<std::uint64_t>(logical_header_bytes * packets_per_second)),
                     "bytes/s", "21-byte SCL header plus 16-byte AudioFrame prefix");
    runner.add_value("audio_wire", "fragmentation_percentage", scenario,
                     datagrams.size() > 1 ? "100" : "0", "percent",
                     "representative generated Opus packet at 1200-byte datagram budget");
}

void run_codec_benchmarks(BenchmarkRunner& runner, const std::vector<PacketFixture>& fixtures) {
    const std::size_t iterations = runner.options().smoke ? 100U : runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 5U : 30U;
    for (const PacketFixture& fixture : fixtures) {
        if (fixture.packet.empty()) {
            runner.add_value("audio_codec", "fixture", profile_name(fixture.profile), "failed", "status");
            continue;
        }

        add_wire_values(runner, fixture);

        OpusAudioEncoder encoder(encoder_config(fixture.profile));
        if (encoder.prepare().error == AudioEncoderError::None &&
            encoder.start().error == AudioEncoderError::None) {
            std::uint64_t frame_position = 0;
            std::uint64_t capture_time_ns = 0;
            runner.run_latency(
                "audio_codec",
                "opus_encode",
                profile_name(fixture.profile),
                iterations,
                warmup,
                fixture.pcm.size() * sizeof(std::int16_t),
                [&]() -> std::uint64_t {
                    const auto result = encoder.submit_pcm(
                        pcm_bytes(fixture.pcm), frame_position, capture_time_ns,
                        warpnect::audio::AudioTimestampQuality::AudioRecordTimestamp);
                    frame_position += fixture.samples_per_frame;
                    capture_time_ns += static_cast<std::uint64_t>(fixture.profile.frame_duration_us) * 1'000ULL;
                    return result.status == AudioEncoderSubmitStatus::EncodedFrameReady
                               ? static_cast<std::uint64_t>(result.packet_size)
                               : 0ULL;
                });
        }

        OpusAudioDecoder decoder(decoder_config(fixture.profile));
        if (decoder.prepare().error == AudioDecoderError::None &&
            decoder.start().error == AudioDecoderError::None) {
            std::uint64_t frame_position = 0;
            std::uint64_t capture_time_us = 0;
            runner.run_latency(
                "audio_codec",
                "opus_decode",
                profile_name(fixture.profile),
                iterations,
                warmup,
                fixture.packet.size(),
                [&]() -> std::uint64_t {
                    const auto result = decoder.decode(
                        bytes(fixture.packet),
                        EncodedAudioFrameMetadata{
                            .config_generation = 1,
                            .first_frame_position = frame_position,
                            .capture_time_us = capture_time_us,
                            .timestamp_quality =
                                warpnect::audio::AudioTimestampQuality::AudioRecordTimestamp,
                            .discontinuity_before = false,
                        });
                    frame_position += fixture.samples_per_frame;
                    capture_time_us += fixture.profile.frame_duration_us;
                    return result.error == AudioDecoderError::None
                               ? static_cast<std::uint64_t>(result.pcm_size_bytes)
                               : 0ULL;
                });
        }

        OpusAudioDecoder plc_decoder(decoder_config(fixture.profile));
        if (plc_decoder.prepare().error == AudioDecoderError::None &&
            plc_decoder.start().error == AudioDecoderError::None) {
            std::uint64_t frame_position = 0;
            std::uint64_t capture_time_us = 0;
            runner.run_latency(
                "audio_recovery",
                "opus_plc",
                profile_name(fixture.profile),
                iterations,
                warmup,
                0,
                [&]() -> std::uint64_t {
                    const auto result = plc_decoder.conceal_missing_frame(
                        MissingAudioFrameMetadata{
                            .config_generation = 1,
                            .first_frame_position = frame_position,
                            .capture_time_us = capture_time_us,
                            .timestamp_quality =
                                warpnect::audio::AudioTimestampQuality::AudioRecordTimestamp,
                        });
                    frame_position += fixture.samples_per_frame;
                    capture_time_us += fixture.profile.frame_duration_us;
                    return result.error == AudioDecoderError::None &&
                                   result.frame_kind == DecodedAudioFrameKind::PacketLossConcealment
                               ? static_cast<std::uint64_t>(result.pcm_size_bytes)
                               : 0ULL;
                });
        }
    }
}

void run_packetizer_and_receiver_benchmarks(BenchmarkRunner& runner, const PacketFixture& fixture) {
    constexpr std::size_t kMaxWire = 1'200;
    const std::size_t iterations = runner.options().smoke ? 100U : runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 5U : 30U;
    std::vector<std::byte> scratch(kMaxWire);
    AudioPacketizer packetizer(std::span<std::byte>(scratch.data(), scratch.size()));
    std::uint32_t sequence = 1;
    runner.run_latency(
        "audio_transport",
        "audio_frame_packetize",
        profile_name(fixture.profile),
        iterations,
        warmup,
        fixture.packet.size(),
        [&]() -> std::uint64_t {
            CountingAudioSink sink;
            const auto result = packetizer.emit_audio_frame(
                AudioPacketizerConfig{.max_datagram_size = kMaxWire},
                PayloadType::SystemAudio,
                sequence,
                1'000,
                1,
                0,
                warpnect::scl::AudioTimestampQuality::AudioRecordTimestamp,
                false,
                bytes(fixture.packet),
                sink);
            sequence += result.datagrams_emitted == 0 ? 1U : result.datagrams_emitted;
            return result.ok() ? sink.bytes_emitted : 0ULL;
        });

    const auto datagrams = packetize_frame_datagrams(fixture, kMaxWire);
    AudioReceiverRuntime receiver(receiver_config(kMaxWire));
    std::uint64_t now_us = 1'000;
    runner.run_latency(
        "audio_transport",
        "receiver_accept_datagrams",
        profile_name(fixture.profile),
        iterations,
        warmup,
        fixture.packet.size(),
        [&]() -> std::uint64_t {
            std::uint64_t accepted = 0;
            for (const auto& datagram : datagrams) {
                const auto status = receiver.accept_datagram(
                    bytes(datagram), UdpEndpoint::loopback_v4(40'000), now_us);
                accepted += status.ok() ? datagram.size() : 0U;
                now_us += 1;
            }
            (void)receiver.release_ready_slot(0);
            return accepted;
        });
}

void add_recovery_rows(BenchmarkRunner& runner) {
    const std::vector<std::pair<std::string, std::vector<Arrival>>> scenarios{
        {"no_reorder", {{0, 0}, {1, 5'000}, {2, 10'000}, {3, 15'000}}},
        {"adjacent_swap_1ms", {{0, 0}, {2, 5'000}, {1, 6'000}, {3, 15'000}}},
        {"delayed_missing_250us", {{0, 0}, {2, 5'000}, {1, 5'250}, {3, 15'000}}},
        {"delayed_missing_2500us", {{0, 0}, {2, 5'000}, {1, 7'500}, {3, 15'000}}},
        {"delayed_missing_10000us", {{0, 0}, {2, 5'000}, {3, 15'000}, {1, 15'000}}},
        {"single_loss", {{0, 0}, {2, 10'000}, {3, 15'000}}},
        {"two_frame_burst_loss", {{0, 0}, {3, 15'000}, {4, 20'000}}},
        {"large_burst_loss", {{0, 0}, {9, 45'000}}},
        {"duplicate_packet", {{0, 0}, {1, 5'000}, {1, 5'100}, {2, 10'000}}},
    };
    for (std::uint32_t wait_us : {0U, 500U, 1'000U, 2'500U, 5'000U}) {
        for (const auto& [name, arrivals] : scenarios) {
            const RecoveryMetrics metrics = simulate_recovery(arrivals, wait_us, 2);
            const std::string scenario = name + "_wait_" + std::to_string(wait_us) + "us";
            runner.add_value("audio_recovery_policy", "normal_frames", scenario,
                             std::to_string(metrics.normal), "frames");
            runner.add_value("audio_recovery_policy", "plc_frames", scenario,
                             std::to_string(metrics.plc), "frames");
            runner.add_value("audio_recovery_policy", "late_drops", scenario,
                             std::to_string(metrics.late), "frames");
            runner.add_value("audio_recovery_policy", "reordered_recovered", scenario,
                             std::to_string(metrics.reordered_recovered), "frames");
            runner.add_value("audio_recovery_policy", "large_gap_resets", scenario,
                             std::to_string(metrics.large_gap_resets), "events");
            runner.add_value("audio_recovery_policy", "added_wait", scenario,
                             std::to_string(metrics.added_wait_us), "us");
        }
    }
    runner.add_value("audio_recovery_policy", "nack_default", "production", "disabled", "status",
                     "retransmission is not useful without playout wait evidence");
    runner.add_value("audio_recovery_policy", "scl_fec_default", "production", "disabled", "status",
                     "fixed parity timeliness remains device/network pending");
    runner.add_value("audio_recovery_policy", "opus_inband_fec_default", "production", "disabled", "status",
                     "RestrictedLowDelay remains selected; no codec mode switch for FEC");
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
    add_baseline_values(runner);

    const std::vector<AudioProfile> profiles{
        AudioProfile{.frame_duration_us = 2'500, .channels = 1, .bitrate_bps = 64'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 64'000},
        AudioProfile{.frame_duration_us = 10'000, .channels = 1, .bitrate_bps = 64'000},
        AudioProfile{.frame_duration_us = 20'000, .channels = 1, .bitrate_bps = 64'000},
        AudioProfile{.frame_duration_us = 2'500, .channels = 2, .bitrate_bps = 128'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 2, .bitrate_bps = 128'000},
        AudioProfile{.frame_duration_us = 10'000, .channels = 2, .bitrate_bps = 128'000},
        AudioProfile{.frame_duration_us = 20'000, .channels = 2, .bitrate_bps = 128'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 32'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 48'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 96'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 2, .bitrate_bps = 96'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 2, .bitrate_bps = 160'000},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 64'000,
                     .bitrate_mode = AudioBitrateMode::ConstrainedVariableBitrate},
        AudioProfile{.frame_duration_us = 5'000, .channels = 2, .bitrate_bps = 128'000,
                     .bitrate_mode = AudioBitrateMode::ConstrainedVariableBitrate},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 64'000,
                     .complexity = 8},
        AudioProfile{.frame_duration_us = 5'000, .channels = 1, .bitrate_bps = 64'000,
                     .complexity = 10},
    };
    std::vector<PacketFixture> fixtures;
    fixtures.reserve(profiles.size());
    for (std::size_t i = 0; i < profiles.size(); ++i) {
        fixtures.push_back(make_fixture(profiles[i], static_cast<std::uint32_t>(i + 1U)));
    }

    run_codec_benchmarks(runner, fixtures);
    if (!fixtures.empty() && !fixtures[1].packet.empty()) {
        run_packetizer_and_receiver_benchmarks(runner, fixtures[1]);
    }
    add_recovery_rows(runner);

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
