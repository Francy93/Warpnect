#include "audio_opus_encoder.h"

#include "opus.h"

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
using warpnect::audio::AudioEncoderError;
using warpnect::audio::AudioEncoderSubmitStatus;
using warpnect::audio::AudioTimestampQuality;
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

[[nodiscard]] std::span<const std::byte> pcm_bytes(const std::vector<std::int16_t>& pcm,
                                                   std::size_t frame_offset,
                                                   std::size_t frame_count,
                                                   std::uint8_t channels) noexcept {
    const auto sample_offset = frame_offset * channels;
    const auto sample_count = frame_count * channels;
    return std::span<const std::byte>(
        reinterpret_cast<const std::byte*>(pcm.data() + sample_offset),
        sample_count * sizeof(std::int16_t));
}

[[nodiscard]] std::vector<std::int16_t> silence(std::size_t frames,
                                                std::uint8_t channels) {
    return std::vector<std::int16_t>(frames * channels, 0);
}

[[nodiscard]] std::vector<std::int16_t> tone(std::size_t frames,
                                             std::uint8_t channels,
                                             std::uint32_t sample_rate_hz) {
    std::vector<std::int16_t> pcm(frames * channels);
    constexpr double frequency = 440.0;
    constexpr double amplitude = 12000.0;
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

[[nodiscard]] OpusAudioEncoderConfig config(std::uint8_t channels = 1,
                                            std::uint32_t frame_duration_us = 5000,
                                            AudioCaptureSource source =
                                                AudioCaptureSource::MicrophoneAudio) noexcept {
    return OpusAudioEncoderConfig{
        .codec = AudioCodec::Opus,
        .source = source,
        .sample_rate_hz = 48000,
        .channel_count = channels,
        .frame_duration_us = frame_duration_us,
        .bitrate_bps = channels == 2 ? 128000U : 64000U,
        .bitrate_mode = AudioBitrateMode::ConstantBitrate,
        .complexity = 5,
    };
}

void decode_packet(const OpusAudioEncoder& encoder,
                   std::size_t packet_size,
                   std::uint8_t channels,
                   std::uint32_t samples_per_frame,
                   std::string_view label) {
    int opus_error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(48000, channels, &opus_error);
    expect(decoder != nullptr && opus_error == OPUS_OK, "decoder create");
    if (decoder == nullptr) {
        return;
    }
    std::vector<std::int16_t> decoded(static_cast<std::size_t>(samples_per_frame) * channels);
    const auto output = const_cast<OpusAudioEncoder&>(encoder).output_buffer();
    const int decoded_samples = opus_decode(
        decoder, reinterpret_cast<const unsigned char*>(output.data()),
        static_cast<opus_int32>(packet_size), decoded.data(), static_cast<int>(samples_per_frame),
        0);
    expect_equal(decoded_samples, static_cast<int>(samples_per_frame), label);
    opus_decoder_destroy(decoder);
}

void direct_mono_and_stereo_packets_are_valid() {
    for (const std::uint8_t channels : {std::uint8_t{1}, std::uint8_t{2}}) {
        OpusAudioEncoder encoder(config(channels));
        expect_equal(encoder.prepare().error, AudioEncoderError::None, "prepare");
        expect_equal(encoder.start().error, AudioEncoderError::None, "start");
        const auto pcm = silence(240, channels);
        const auto result = encoder.submit_pcm(
            pcm_bytes(pcm, 0, 240, channels), 1000, 55'000,
            AudioTimestampQuality::AudioRecordTimestamp);
        expect_equal(result.status, AudioEncoderSubmitStatus::EncodedFrameReady, "encoded");
        expect(result.direct_fast_path, "direct fast path");
        expect_equal(result.first_frame_position, 1000ULL, "frame position");
        expect_equal(result.capture_time_ns, 55'000ULL, "capture timestamp");
        expect_equal(result.encoded_frame_index, 0ULL, "encoded frame index");
        decode_packet(encoder, result.packet_size, channels, 240, "decoded samples");
    }
}

void supported_frame_durations_are_exact() {
    expect_equal(OpusAudioEncoder::samples_per_frame(48000, 2500), 120U, "2.5ms");
    expect_equal(OpusAudioEncoder::samples_per_frame(48000, 5000), 240U, "5ms");
    expect_equal(OpusAudioEncoder::samples_per_frame(48000, 10000), 480U, "10ms");
    expect_equal(OpusAudioEncoder::samples_per_frame(48000, 20000), 960U, "20ms");
}

void unsupported_format_is_rejected() {
    auto unsupported_rate = config();
    unsupported_rate.sample_rate_hz = 44100;
    expect_equal(OpusAudioEncoder::validate_config(unsupported_rate),
                 AudioEncoderError::UnsupportedSampleRate, "44.1k rejected");

    auto unsupported_channels = config();
    unsupported_channels.channel_count = 3;
    expect_equal(OpusAudioEncoder::validate_config(unsupported_channels),
                 AudioEncoderError::UnsupportedChannelCount, "3 channels rejected");
}

void partial_frame_assembly_uses_single_accumulator() {
    OpusAudioEncoder encoder(config());
    expect_equal(encoder.prepare().error, AudioEncoderError::None, "prepare");
    expect_equal(encoder.start().error, AudioEncoderError::None, "start");
    const auto pcm = tone(240, 1, 48000);
    const auto first = encoder.submit_pcm(
        pcm_bytes(pcm, 0, 120, 1), 0, 10'000, AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(first.status, AudioEncoderSubmitStatus::NeedMoreInput, "need more input");
    expect_equal(encoder.snapshot().partial_frame_samples, 120U, "partial samples");

    const auto second = encoder.submit_pcm(
        pcm_bytes(pcm, 120, 120, 1), 120, 12'500, AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(second.status, AudioEncoderSubmitStatus::EncodedFrameReady, "assembled output");
    expect(second.assembler_path, "assembler path");
    expect_equal(second.first_frame_position, 0ULL, "assembled first frame position");
    expect_equal(second.capture_time_ns, 10'000ULL, "assembled first timestamp retained");
}

void larger_input_splits_into_multiple_direct_frames() {
    OpusAudioEncoder encoder(config());
    expect_equal(encoder.prepare().error, AudioEncoderError::None, "prepare");
    expect_equal(encoder.start().error, AudioEncoderError::None, "start");
    const auto pcm = tone(480, 1, 48000);
    const auto first = encoder.submit_pcm(
        pcm_bytes(pcm, 0, 480, 1), 40, 1'000'000,
        AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(first.status, AudioEncoderSubmitStatus::EncodedFrameReady, "first frame");
    expect_equal(first.consumed_bytes, 480ULL, "first consumed bytes");

    const auto second = encoder.submit_pcm(
        pcm_bytes(pcm, 240, 240, 1), 280, 6'000'000,
        AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(second.status, AudioEncoderSubmitStatus::EncodedFrameReady, "second frame");
    expect_equal(second.encoded_frame_index, 1ULL, "second frame index");
}

void discontinuity_drops_partial_frame() {
    OpusAudioEncoder encoder(config());
    expect_equal(encoder.prepare().error, AudioEncoderError::None, "prepare");
    expect_equal(encoder.start().error, AudioEncoderError::None, "start");
    const auto pcm = silence(240, 1);
    const auto partial = encoder.submit_pcm(
        pcm_bytes(pcm, 0, 120, 1), 1000, 1'000, AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(partial.status, AudioEncoderSubmitStatus::NeedMoreInput, "partial");
    const auto gap = encoder.submit_pcm(
        pcm_bytes(pcm, 120, 120, 1), 2000, 3'000, AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(gap.status, AudioEncoderSubmitStatus::Discontinuity, "discontinuity");
    expect_equal(gap.expected_frame_position, 1120ULL, "expected position");
    expect_equal(gap.actual_frame_position, 2000ULL, "actual position");
    expect_equal(encoder.snapshot().partial_frame_samples, 0U, "partial dropped");
}

void tail_discard_and_restart_reset_timeline() {
    OpusAudioEncoder encoder(config());
    expect_equal(encoder.prepare().error, AudioEncoderError::None, "prepare");
    expect_equal(encoder.start().error, AudioEncoderError::None, "start");
    const auto pcm = silence(240, 1);
    const auto partial =
        encoder.submit_pcm(pcm_bytes(pcm, 0, 120, 1), 0, 0, AudioTimestampQuality::Unavailable);
    expect_equal(partial.status, AudioEncoderSubmitStatus::NeedMoreInput, "tail partial");
    const auto stopped = encoder.stop();
    expect_equal(stopped.tail_frames_dropped, 120ULL, "tail dropped");
    expect_equal(encoder.start().error, AudioEncoderError::None, "restart");
    const auto encoded = encoder.submit_pcm(
        pcm_bytes(pcm, 0, 240, 1), 5000, 9'000, AudioTimestampQuality::Unavailable);
    expect_equal(encoded.encoded_frame_index, 0ULL, "frame index reset");
}

void bitrate_update_is_explicit() {
    OpusAudioEncoder encoder(config(2));
    expect_equal(encoder.prepare().error, AudioEncoderError::None, "prepare");
    expect_equal(encoder.update_bitrate(96000).error, AudioEncoderError::None, "bitrate update");
    expect_equal(encoder.snapshot().bitrate_bps, 96000U, "snapshot bitrate");
    expect_equal(encoder.update_bitrate(100).error, AudioEncoderError::InvalidBitrate,
                 "invalid bitrate");
}

} // namespace

int main() {
    direct_mono_and_stereo_packets_are_valid();
    supported_frame_durations_are_exact();
    unsupported_format_is_rejected();
    partial_frame_assembly_uses_single_accumulator();
    larger_input_splits_into_multiple_direct_frames();
    discontinuity_drops_partial_frame();
    tail_discard_and_restart_reset_timeline();
    bitrate_update_is_explicit();

    if (failures != 0) {
        std::cerr << failures << " Opus audio encoder test failure(s)\n";
        return 1;
    }
    std::cout << "Opus audio encoder tests passed\n";
    return 0;
}
