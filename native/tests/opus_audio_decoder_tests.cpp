#include "audio_opus_decoder.h"
#include "audio_opus_encoder.h"

#include <cmath>
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
using warpnect::audio::DecodedAudioFrameKind;
using warpnect::audio::EncodedAudioFrameMetadata;
using warpnect::audio::MissingAudioFrameMetadata;
using warpnect::audio::OpusAudioDecoder;
using warpnect::audio::OpusAudioDecoderConfig;
using warpnect::audio::OpusAudioEncoder;
using warpnect::audio::OpusAudioEncoderConfig;

int failures = 0;

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

[[nodiscard]] std::vector<std::int16_t> tone(std::size_t frames,
                                             std::uint8_t channels,
                                             std::uint32_t sample_rate_hz) {
    std::vector<std::int16_t> pcm(frames * channels);
    constexpr double frequency = 440.0;
    constexpr double amplitude = 10000.0;
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const auto value = static_cast<std::int16_t>(
            std::sin((static_cast<double>(frame) * frequency * 2.0 * 3.14159265358979323846) /
                     sample_rate_hz) *
            amplitude);
        for (std::uint8_t channel = 0; channel < channels; ++channel) {
            pcm[(frame * channels) + channel] = value;
        }
    }
    return pcm;
}

[[nodiscard]] std::span<const std::byte> pcm_bytes(const std::vector<std::int16_t>& pcm) noexcept {
    return std::span<const std::byte>(reinterpret_cast<const std::byte*>(pcm.data()),
                                      pcm.size() * sizeof(std::int16_t));
}

[[nodiscard]] OpusAudioEncoderConfig encoder_config(
    std::uint32_t sample_rate_hz,
    std::uint8_t channels,
    std::uint32_t frame_duration_us) noexcept {
    return OpusAudioEncoderConfig{
        .codec = AudioCodec::Opus,
        .source = channels == 2 ? AudioCaptureSource::SystemAudio
                                : AudioCaptureSource::MicrophoneAudio,
        .sample_rate_hz = sample_rate_hz,
        .channel_count = channels,
        .frame_duration_us = frame_duration_us,
        .bitrate_bps = channels == 2 ? 128000U : 64000U,
        .bitrate_mode = AudioBitrateMode::ConstantBitrate,
        .complexity = 5,
    };
}

[[nodiscard]] OpusAudioDecoderConfig decoder_config(
    std::uint32_t sample_rate_hz = 48000,
    std::uint8_t channels = 1,
    std::uint32_t frame_duration_us = 5000,
    std::uint32_t config_generation = 1) noexcept {
    return OpusAudioDecoderConfig{
        .codec = AudioCodec::Opus,
        .source = channels == 2 ? AudioCaptureSource::SystemAudio
                                : AudioCaptureSource::MicrophoneAudio,
        .config_generation = config_generation,
        .sample_rate_hz = sample_rate_hz,
        .channel_count = channels,
        .frame_duration_us = frame_duration_us,
        .lookahead_samples = 120,
    };
}

[[nodiscard]] std::vector<std::byte> encode_one_packet(std::uint32_t sample_rate_hz,
                                                       std::uint8_t channels,
                                                       std::uint32_t frame_duration_us,
                                                       std::uint64_t frame_position = 0) {
    const auto config = encoder_config(sample_rate_hz, channels, frame_duration_us);
    OpusAudioEncoder encoder(config);
    expect_equal(encoder.prepare().error, AudioEncoderError::None, "encoder prepare");
    expect_equal(encoder.start().error, AudioEncoderError::None, "encoder start");
    const auto samples =
        OpusAudioEncoder::samples_per_frame(sample_rate_hz, frame_duration_us);
    const auto pcm = tone(samples, channels, sample_rate_hz);
    const auto encoded = encoder.submit_pcm(pcm_bytes(pcm), frame_position, 99'000,
                                            AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(encoded.status, AudioEncoderSubmitStatus::EncodedFrameReady, "encoded packet");
    const auto output = encoder.output_buffer();
    return std::vector<std::byte>(output.begin(), output.begin() + encoded.packet_size);
}

void mono_and_stereo_decode_48k_5ms() {
    for (const std::uint8_t channels : {std::uint8_t{1}, std::uint8_t{2}}) {
        const auto packet = encode_one_packet(48000, channels, 5000);
        OpusAudioDecoder decoder(decoder_config(48000, channels, 5000));
        expect_equal(decoder.prepare().error, AudioDecoderError::None, "decoder prepare");
        expect_equal(decoder.start().error, AudioDecoderError::None, "decoder start");
        const auto result = decoder.decode(
            packet,
            EncodedAudioFrameMetadata{
                .config_generation = 1,
                .first_frame_position = 1000,
                .capture_time_us = 44,
                .timestamp_quality = AudioTimestampQuality::EstimatedFromReadCompletion,
                .discontinuity_before = false,
            });
        expect_equal(result.error, AudioDecoderError::None, "decode result");
        expect_equal(result.frame_count, 240U, "decoded samples per channel");
        expect_equal(result.pcm_size_bytes,
                     static_cast<std::size_t>(240U * channels * sizeof(std::int16_t)),
                     "decoded bytes");
        expect_equal(decoder.snapshot().frames_decoded, 1ULL, "frames decoded");
    }
}

void frame_duration_matrix_decodes() {
    for (const std::uint32_t duration : {2500U, 5000U, 10000U, 20000U}) {
        const auto packet = encode_one_packet(48000, 1, duration);
        OpusAudioDecoder decoder(decoder_config(48000, 1, duration));
        expect_equal(decoder.prepare().error, AudioDecoderError::None, "duration prepare");
        expect_equal(decoder.start().error, AudioDecoderError::None, "duration start");
        const auto result = decoder.decode(
            packet,
            EncodedAudioFrameMetadata{.config_generation = 1});
        expect_equal(result.error, AudioDecoderError::None, "duration decode");
        expect_equal(result.frame_count, OpusAudioDecoder::samples_per_frame(48000, duration),
                     "duration sample count");
    }
}

void supported_sample_rates_decode_and_44100_rejects() {
    for (const std::uint32_t rate : {8000U, 12000U, 16000U, 24000U, 48000U}) {
        const auto packet = encode_one_packet(rate, 1, 5000);
        OpusAudioDecoder decoder(decoder_config(rate, 1, 5000));
        expect_equal(decoder.prepare().error, AudioDecoderError::None, "rate prepare");
        expect_equal(decoder.start().error, AudioDecoderError::None, "rate start");
        const auto result = decoder.decode(
            packet,
            EncodedAudioFrameMetadata{.config_generation = 1});
        expect_equal(result.error, AudioDecoderError::None, "rate decode");
    }

    auto invalid = decoder_config(44100, 1, 5000);
    expect_equal(OpusAudioDecoder::validate_config(invalid),
                 AudioDecoderError::UnsupportedSampleRate, "44.1k rejected");
}

void malformed_and_wrong_duration_packets_fail_deterministically() {
    OpusAudioDecoder decoder(decoder_config(48000, 1, 5000));
    expect_equal(decoder.prepare().error, AudioDecoderError::None, "prepare malformed");
    expect_equal(decoder.start().error, AudioDecoderError::None, "start malformed");

    const std::vector<std::byte> malformed{std::byte{0xFF}, std::byte{0xFF}};
    const auto malformed_result = decoder.decode(
        malformed,
        EncodedAudioFrameMetadata{.config_generation = 1});
    expect(malformed_result.error == AudioDecoderError::MalformedOpusPacket ||
               malformed_result.error == AudioDecoderError::UnexpectedPacketDuration,
           "malformed packet rejected");

    const auto packet_10ms = encode_one_packet(48000, 1, 10000);
    const auto wrong_duration = decoder.decode(
        packet_10ms,
        EncodedAudioFrameMetadata{.config_generation = 1});
    expect_equal(wrong_duration.error, AudioDecoderError::UnexpectedPacketDuration,
                 "wrong duration rejected");
}

void metadata_and_generation_are_preserved() {
    const auto packet = encode_one_packet(48000, 1, 5000);
    OpusAudioDecoder decoder(decoder_config(48000, 1, 5000, 7));
    expect_equal(decoder.prepare().error, AudioDecoderError::None, "metadata prepare");
    expect_equal(decoder.start().error, AudioDecoderError::None, "metadata start");

    const auto mismatch = decoder.decode(
        packet,
        EncodedAudioFrameMetadata{.config_generation = 8});
    expect_equal(mismatch.error, AudioDecoderError::ReconfigurationRequired,
                 "generation mismatch");

    const auto result = decoder.decode(
        packet,
        EncodedAudioFrameMetadata{
            .config_generation = 7,
            .first_frame_position = 12345,
            .capture_time_us = 67890,
            .timestamp_quality = AudioTimestampQuality::AudioRecordTimestamp,
            .discontinuity_before = true,
        });
    expect_equal(result.error, AudioDecoderError::None, "metadata decode");
    expect_equal(result.first_frame_position, 12345ULL, "first frame position preserved");
    expect_equal(result.capture_time_us, 67890ULL, "capture time preserved");
    expect_equal(result.timestamp_quality, AudioTimestampQuality::AudioRecordTimestamp,
                 "quality preserved");
    expect(result.discontinuity_before, "discontinuity propagated");
    expect_equal(decoder.snapshot().last_frame_position, 12345ULL, "snapshot position");
}

void explicit_plc_generates_one_frame_and_decoder_remains_usable() {
    const auto packet_a = encode_one_packet(48000, 1, 5000, 0);
    const auto packet_b = encode_one_packet(48000, 1, 5000, 480);
    OpusAudioDecoder decoder(decoder_config(48000, 1, 5000));
    expect_equal(decoder.prepare().error, AudioDecoderError::None, "plc prepare");
    expect_equal(decoder.start().error, AudioDecoderError::None, "plc start");

    const auto first = decoder.decode(
        packet_a,
        EncodedAudioFrameMetadata{.config_generation = 1, .first_frame_position = 0});
    expect_equal(first.error, AudioDecoderError::None, "first decode");
    const auto plc = decoder.conceal_missing_frame(
        MissingAudioFrameMetadata{
            .config_generation = 1,
            .first_frame_position = 240,
            .capture_time_us = 5000,
            .timestamp_quality = AudioTimestampQuality::Unavailable,
        });
    expect_equal(plc.error, AudioDecoderError::None, "plc result");
    expect_equal(plc.frame_kind, DecodedAudioFrameKind::PacketLossConcealment, "plc kind");
    expect_equal(plc.frame_count, 240U, "plc frame count");
    expect(plc.discontinuity_before, "plc discontinuity flag");

    const auto later = decoder.decode(
        packet_b,
        EncodedAudioFrameMetadata{.config_generation = 1, .first_frame_position = 480});
    expect_equal(later.error, AudioDecoderError::None, "decode after plc");
    expect_equal(decoder.snapshot().plc_frames_generated, 1ULL, "plc counter");
}

void no_automatic_plc_and_restart_resets_counters() {
    const auto packet_a = encode_one_packet(48000, 1, 5000, 0);
    const auto packet_c = encode_one_packet(48000, 1, 5000, 480);
    OpusAudioDecoder decoder(decoder_config(48000, 1, 5000));
    expect_equal(decoder.prepare().error, AudioDecoderError::None, "no plc prepare");
    expect_equal(decoder.start().error, AudioDecoderError::None, "no plc start");
    expect_equal(decoder.decode(packet_a, EncodedAudioFrameMetadata{.config_generation = 1}).error,
                 AudioDecoderError::None, "decode a");
    expect_equal(decoder.decode(packet_c, EncodedAudioFrameMetadata{.config_generation = 1}).error,
                 AudioDecoderError::None, "decode c");
    expect_equal(decoder.snapshot().plc_frames_generated, 0ULL, "no hidden plc");

    expect_equal(decoder.stop().error, AudioDecoderError::None, "stop");
    expect_equal(decoder.start().error, AudioDecoderError::None, "restart");
    expect_equal(decoder.snapshot().frames_decoded, 0ULL, "restart resets counters");
}

} // namespace

int main() {
    mono_and_stereo_decode_48k_5ms();
    frame_duration_matrix_decodes();
    supported_sample_rates_decode_and_44100_rejects();
    malformed_and_wrong_duration_packets_fail_deterministically();
    metadata_and_generation_are_preserved();
    explicit_plc_generates_one_frame_and_decoder_remains_usable();
    no_automatic_plc_and_restart_resets_counters();

    if (failures != 0) {
        std::cerr << failures << " Opus audio decoder test failure(s)\n";
        return 1;
    }
    std::cout << "Opus audio decoder tests passed\n";
    return 0;
}
