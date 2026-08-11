#include "audio_playback_ring.h"

#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::audio::AudioCaptureSource;
using warpnect::audio::AudioPlaybackError;
using warpnect::audio::AudioTimestampQuality;
using warpnect::audio::DecodedAudioFrameKind;
using warpnect::audio::DecodedPcmPlaybackMetadata;
using warpnect::audio::PcmPlaybackRing;
using warpnect::audio::PcmPlaybackRingConfig;

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

[[nodiscard]] PcmPlaybackRingConfig config(std::uint8_t channels = 1,
                                           std::uint32_t frames_per_codec_frame = 4,
                                           std::uint32_t capacity = 3) noexcept {
    return PcmPlaybackRingConfig{
        .source = channels == 2 ? AudioCaptureSource::SystemAudio
                                : AudioCaptureSource::MicrophoneAudio,
        .config_generation = 7,
        .sample_rate_hz = 48000,
        .channel_count = channels,
        .frames_per_codec_frame = frames_per_codec_frame,
        .ring_capacity_codec_frames = capacity,
    };
}

[[nodiscard]] std::vector<std::int16_t> frame(std::uint32_t first,
                                              std::uint32_t frames,
                                              std::uint8_t channels) {
    std::vector<std::int16_t> pcm(static_cast<std::size_t>(frames) * channels);
    for (std::uint32_t frame_index = 0; frame_index < frames; ++frame_index) {
        for (std::uint8_t channel = 0; channel < channels; ++channel) {
            pcm[(static_cast<std::size_t>(frame_index) * channels) + channel] =
                static_cast<std::int16_t>((first + frame_index) * 10U + channel);
        }
    }
    return pcm;
}

[[nodiscard]] std::span<const std::byte> bytes(const std::vector<std::int16_t>& pcm) noexcept {
    return std::span<const std::byte>(reinterpret_cast<const std::byte*>(pcm.data()),
                                      pcm.size() * sizeof(std::int16_t));
}

[[nodiscard]] std::span<std::byte> mutable_bytes(std::vector<std::int16_t>& pcm) noexcept {
    return std::span<std::byte>(reinterpret_cast<std::byte*>(pcm.data()),
                                pcm.size() * sizeof(std::int16_t));
}

[[nodiscard]] DecodedPcmPlaybackMetadata metadata(
    std::uint64_t first_frame_position,
    bool discontinuity = false,
    DecodedAudioFrameKind kind = DecodedAudioFrameKind::Normal) noexcept {
    return DecodedPcmPlaybackMetadata{
        .config_generation = 7,
        .first_frame_position = first_frame_position,
        .capture_time_us = 1000 + first_frame_position,
        .timestamp_quality = AudioTimestampQuality::AudioRecordTimestamp,
        .discontinuity_before = discontinuity,
        .frame_kind = kind,
    };
}

void empty_ring_outputs_silence() {
    PcmPlaybackRing ring(config());
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare empty");
    std::vector<std::int16_t> output(4, 123);
    const auto consumed = ring.consume(mutable_bytes(output), 4);
    expect_equal(consumed.pcm_frames_copied, 0U, "empty copied");
    expect_equal(consumed.silence_frames_inserted, 4U, "empty silence");
    for (const auto sample : output) {
        expect_equal(sample, static_cast<std::int16_t>(0), "empty output silence");
    }
    expect_equal(ring.snapshot().underrun_callbacks, 1ULL, "empty underrun callback");
}

void single_slot_and_partial_consumption() {
    PcmPlaybackRing ring(config());
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare partial");
    const auto pcm = frame(0, 4, 1);
    expect_equal(ring.submit(bytes(pcm), 4, metadata(100), 10).error, AudioPlaybackError::None,
                 "submit partial");

    std::vector<std::int16_t> first(2);
    const auto first_result = ring.consume(mutable_bytes(first), 2, 20);
    expect_equal(first_result.pcm_frames_copied, 2U, "partial first copied");
    expect_equal(first_result.first_consumed_frame_position, 100ULL, "partial position");
    expect_equal(first[0], static_cast<std::int16_t>(0), "partial sample 0");
    expect_equal(first[1], static_cast<std::int16_t>(10), "partial sample 1");
    expect_equal(ring.occupancy_frames(), 2U, "partial occupancy");

    std::vector<std::int16_t> second(2);
    const auto second_result = ring.consume(mutable_bytes(second), 2, 30);
    expect_equal(second_result.pcm_frames_copied, 2U, "partial second copied");
    expect_equal(second_result.first_consumed_frame_position, 102ULL, "partial second position");
    expect_equal(second[0], static_cast<std::int16_t>(20), "partial sample 2");
    expect_equal(second[1], static_cast<std::int16_t>(30), "partial sample 3");
    expect_equal(ring.occupancy_frames(), 0U, "partial drained");
    expect_equal(ring.snapshot().residence_samples, 2ULL, "residence samples");
}

void callback_larger_than_one_slot_preserves_order() {
    PcmPlaybackRing ring(config(1, 4, 3));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare larger");
    const auto a = frame(0, 4, 1);
    const auto b = frame(4, 4, 1);
    expect_equal(ring.submit(bytes(a), 4, metadata(0)).error, AudioPlaybackError::None,
                 "submit a");
    expect_equal(ring.submit(bytes(b), 4, metadata(4)).error, AudioPlaybackError::None,
                 "submit b");

    std::vector<std::int16_t> output(10, 99);
    const auto consumed = ring.consume(mutable_bytes(output), 10);
    expect_equal(consumed.pcm_frames_copied, 8U, "larger copied");
    expect_equal(consumed.silence_frames_inserted, 2U, "larger silence");
    for (int i = 0; i < 8; ++i) {
        expect_equal(output[static_cast<std::size_t>(i)], static_cast<std::int16_t>(i * 10),
                     "larger order");
    }
    expect_equal(output[8], static_cast<std::int16_t>(0), "larger silence 8");
    expect_equal(output[9], static_cast<std::int16_t>(0), "larger silence 9");
}

void wrap_full_and_reset() {
    PcmPlaybackRing ring(config(1, 4, 2));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare wrap");
    const auto a = frame(0, 4, 1);
    const auto b = frame(4, 4, 1);
    const auto c = frame(8, 4, 1);
    expect_equal(ring.submit(bytes(a), 4, metadata(0)).error, AudioPlaybackError::None,
                 "wrap submit a");
    expect_equal(ring.submit(bytes(b), 4, metadata(4)).error, AudioPlaybackError::None,
                 "wrap submit b");
    expect_equal(ring.submit(bytes(c), 4, metadata(8)).error,
                 AudioPlaybackError::PlaybackRingFull, "wrap full");

    std::vector<std::int16_t> output(4);
    expect_equal(ring.consume(mutable_bytes(output), 4).pcm_frames_copied, 4U,
                 "wrap consume a");
    expect_equal(ring.submit(bytes(c), 4, metadata(8)).error, AudioPlaybackError::None,
                 "wrap submit c");

    std::vector<std::int16_t> rest(8);
    expect_equal(ring.consume(mutable_bytes(rest), 8).pcm_frames_copied, 8U,
                 "wrap consume rest");
    expect_equal(rest[0], static_cast<std::int16_t>(40), "wrap b first");
    expect_equal(rest[4], static_cast<std::int16_t>(80), "wrap c first");
    expect_equal(ring.snapshot().pcm_frames_rejected, 4ULL, "wrap rejected frames");

    ring.reset();
    expect_equal(ring.occupancy_frames(), 0U, "reset occupancy");
    expect_equal(ring.snapshot().pcm_frames_submitted, 0ULL, "reset counters");
}

void stereo_and_metadata_counters() {
    PcmPlaybackRing ring(config(2, 3, 2));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare stereo");
    const auto pcm = frame(20, 3, 2);
    expect_equal(ring.submit(bytes(pcm), 3,
                             metadata(200, true,
                                      DecodedAudioFrameKind::PacketLossConcealment))
                     .error,
                 AudioPlaybackError::None, "stereo submit");

    std::vector<std::int16_t> output(6);
    const auto consumed = ring.consume(mutable_bytes(output), 3);
    expect_equal(consumed.pcm_frames_copied, 3U, "stereo copied");
    expect(consumed.discontinuity_before, "stereo discontinuity metadata");
    expect_equal(consumed.frame_kind, DecodedAudioFrameKind::PacketLossConcealment,
                 "stereo plc kind");
    expect_equal(output[0], static_cast<std::int16_t>(200), "stereo left");
    expect_equal(output[1], static_cast<std::int16_t>(201), "stereo right");
    expect_equal(ring.snapshot().plc_frames, 1ULL, "plc counter");
    expect_equal(ring.snapshot().discontinuity_frames, 1ULL, "discontinuity counter");
}

void source_anchor_tracks_slot_start_and_invalidates_on_underrun() {
    PcmPlaybackRing ring(config(1, 4, 3));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare anchor");
    const auto a = frame(0, 4, 1);
    const auto b = frame(4, 4, 1);
    expect_equal(ring.submit(bytes(a), 4, metadata(100), 10).error, AudioPlaybackError::None,
                 "anchor submit a");

    std::vector<std::int16_t> first(2);
    static_cast<void>(ring.consume(mutable_bytes(first), 2, 20, 1'000));
    auto anchor = ring.latest_source_anchor();
    expect(anchor.valid, "anchor valid after first source sample");
    expect_equal(anchor.source_frame_position, 100ULL, "anchor source position");
    expect_equal(anchor.source_capture_time_us, 1100ULL, "anchor capture time");
    expect_equal(anchor.output_frame_position, 1'000ULL, "anchor output position");

    std::vector<std::int16_t> second(2);
    static_cast<void>(ring.consume(mutable_bytes(second), 2, 30, 1'002));
    anchor = ring.latest_source_anchor();
    expect(anchor.valid, "anchor remains valid through partial tail");
    expect_equal(anchor.source_frame_position, 100ULL, "partial tail keeps original slot anchor");

    expect_equal(ring.submit(bytes(b), 4,
                             metadata(200, false,
                                      DecodedAudioFrameKind::PacketLossConcealment))
                     .error,
                 AudioPlaybackError::None, "anchor submit b");
    std::vector<std::int16_t> larger(4);
    static_cast<void>(ring.consume(mutable_bytes(larger), 4, 40, 1'004));
    anchor = ring.latest_source_anchor();
    expect(anchor.valid, "plc anchor valid");
    expect_equal(anchor.source_frame_position, 200ULL, "plc anchor source position");
    expect_equal(anchor.output_frame_position, 1'004ULL, "plc anchor output position");
    expect_equal(anchor.frame_kind, DecodedAudioFrameKind::PacketLossConcealment,
                 "plc anchor kind");

    std::vector<std::int16_t> empty(1);
    static_cast<void>(ring.consume(mutable_bytes(empty), 1, 50, 1'010));
    expect(!ring.latest_source_anchor().valid, "underrun invalidates source timeline");
}

void invalid_configuration_and_metadata_are_rejected() {
    auto invalid = config(3);
    expect_equal(PcmPlaybackRing::validate_config(invalid),
                 AudioPlaybackError::UnsupportedChannelCount, "invalid channels");

    PcmPlaybackRing ring(config());
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare invalid metadata");
    const auto pcm = frame(0, 4, 1);
    auto wrong_generation = metadata(0);
    wrong_generation.config_generation = 8;
    expect_equal(ring.submit(bytes(pcm), 4, wrong_generation).error,
                 AudioPlaybackError::ConfigGenerationMismatch, "generation mismatch");
    expect_equal(ring.submit(bytes(pcm), 5, metadata(0)).error,
                 AudioPlaybackError::InvalidFrameCount, "invalid frame count");
}

} // namespace

int main() {
    empty_ring_outputs_silence();
    single_slot_and_partial_consumption();
    callback_larger_than_one_slot_preserves_order();
    wrap_full_and_reset();
    stereo_and_metadata_counters();
    source_anchor_tracks_slot_start_and_invalidates_on_underrun();
    invalid_configuration_and_metadata_are_rejected();

    if (failures != 0) {
        std::cerr << failures << " audio playback ring test failure(s)\n";
        return 1;
    }
    std::cout << "Audio playback ring tests passed\n";
    return 0;
}
