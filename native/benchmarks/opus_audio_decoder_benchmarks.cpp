#include "benchmark_runner.h"

#include "audio_opus_decoder.h"
#include "audio_opus_encoder.h"

#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::audio::AudioBitrateMode;
using warpnect::audio::AudioCaptureSource;
using warpnect::audio::AudioCodec;
using warpnect::audio::AudioDecoderError;
using warpnect::audio::AudioEncoderError;
using warpnect::audio::AudioEncoderSubmitStatus;
using warpnect::audio::AudioTimestampQuality;
using warpnect::audio::EncodedAudioFrameMetadata;
using warpnect::audio::OpusAudioDecoder;
using warpnect::audio::OpusAudioDecoderConfig;
using warpnect::audio::OpusAudioEncoder;
using warpnect::audio::OpusAudioEncoderConfig;
using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;

[[nodiscard]] std::vector<std::int16_t> pcm_frame(std::uint32_t samples_per_frame,
                                                  std::uint8_t channels) {
    std::vector<std::int16_t> pcm(static_cast<std::size_t>(samples_per_frame) * channels);
    for (std::size_t i = 0; i < pcm.size(); ++i) {
        pcm[i] = static_cast<std::int16_t>((i * 131U) & 0x3FFFU);
    }
    return pcm;
}

[[nodiscard]] std::span<const std::byte> pcm_bytes(const std::vector<std::int16_t>& pcm) noexcept {
    return std::span<const std::byte>(reinterpret_cast<const std::byte*>(pcm.data()),
                                      pcm.size() * sizeof(std::int16_t));
}

[[nodiscard]] OpusAudioEncoderConfig encoder_config(std::uint8_t channels) noexcept {
    return OpusAudioEncoderConfig{
        .codec = AudioCodec::Opus,
        .source = channels == 2 ? AudioCaptureSource::SystemAudio
                                : AudioCaptureSource::MicrophoneAudio,
        .sample_rate_hz = 48000,
        .channel_count = channels,
        .frame_duration_us = 5000,
        .bitrate_bps = channels == 2 ? 128000U : 64000U,
        .bitrate_mode = AudioBitrateMode::ConstantBitrate,
        .complexity = 5,
    };
}

[[nodiscard]] OpusAudioDecoderConfig decoder_config(std::uint8_t channels) noexcept {
    return OpusAudioDecoderConfig{
        .codec = AudioCodec::Opus,
        .source = channels == 2 ? AudioCaptureSource::SystemAudio
                                : AudioCaptureSource::MicrophoneAudio,
        .config_generation = 1,
        .sample_rate_hz = 48000,
        .channel_count = channels,
        .frame_duration_us = 5000,
        .lookahead_samples = 120,
    };
}

[[nodiscard]] std::vector<std::byte> encoded_packet(std::uint8_t channels) {
    auto config = encoder_config(channels);
    OpusAudioEncoder encoder(config);
    if (encoder.prepare().error != AudioEncoderError::None ||
        encoder.start().error != AudioEncoderError::None) {
        return {};
    }
    const auto samples_per_frame =
        OpusAudioEncoder::samples_per_frame(config.sample_rate_hz, config.frame_duration_us);
    const auto pcm = pcm_frame(samples_per_frame, channels);
    const auto encoded = encoder.submit_pcm(pcm_bytes(pcm), 0, 0,
                                            AudioTimestampQuality::AudioRecordTimestamp);
    if (encoded.status != AudioEncoderSubmitStatus::EncodedFrameReady) {
        return {};
    }
    const auto output = encoder.output_buffer();
    return std::vector<std::byte>(output.begin(), output.begin() + encoded.packet_size);
}

void add_environment(BenchmarkRunner& runner, std::string_view build_type) {
    runner.add_metadata("phase", "3-audio");
    runner.add_metadata("rfc", "003D");
    runner.add_metadata("build_type", std::string(build_type));
    runner.add_metadata("os", warpnect::benchmarks::current_os_name());
    runner.add_metadata("compiler", warpnect::benchmarks::compiler_name());
    runner.add_metadata("architecture", warpnect::benchmarks::architecture_name());
    runner.add_metadata("codec", "libopus 1.6.1 decoder");
}

void opus_decode_benchmark(BenchmarkRunner& runner, std::uint8_t channels) {
    const auto packet = encoded_packet(channels);
    if (packet.empty()) {
        runner.add_value("audio", "opus_decode_5ms", channels == 2 ? "stereo" : "mono",
                         "packet_generation_failed", "status");
        return;
    }

    OpusAudioDecoder decoder(decoder_config(channels));
    if (decoder.prepare().error != AudioDecoderError::None ||
        decoder.start().error != AudioDecoderError::None) {
        runner.add_value("audio", "opus_decode_5ms", channels == 2 ? "stereo" : "mono",
                         "prepare_failed", "status");
        return;
    }

    std::uint64_t frame_position = 0;
    std::uint64_t capture_time_us = 0;
    const auto scenario = channels == 2 ? "48k_stereo_5ms" : "48k_mono_5ms";
    runner.run_latency(
        "audio", "opus_decode_5ms", scenario,
        runner.options().smoke ? 100U : runner.options().iterations, 20,
        packet.size(),
        [&decoder, &packet, &frame_position, &capture_time_us]() -> std::uint64_t {
            const auto result = decoder.decode(
                packet,
                EncodedAudioFrameMetadata{
                    .config_generation = 1,
                    .first_frame_position = frame_position,
                    .capture_time_us = capture_time_us,
                    .timestamp_quality = AudioTimestampQuality::AudioRecordTimestamp,
                    .discontinuity_before = false,
                });
            frame_position += 240;
            capture_time_us += 5'000;
            return result.error == AudioDecoderError::None
                ? static_cast<std::uint64_t>(result.pcm_size_bytes)
                : 0ULL;
        });
    runner.add_value("audio", "realtime_budget", scenario, "5000", "us",
                     "media interval for 5 ms Opus frames; host codec CPU only");
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
    opus_decode_benchmark(runner, 1);
    opus_decode_benchmark(runner, 2);

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
