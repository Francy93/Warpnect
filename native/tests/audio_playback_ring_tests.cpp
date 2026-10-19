#include "audio_playback_ring.h"
#include "audio_playback_priming.h"
#include "audio_playback_capacity_wait.h"

#include <algorithm>
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

// Ordered 5 ms packets delayed, then delivered together. No packet loss.
void bounded_network_jitter_requires_both_playout_reserve_and_burst_headroom() {
    const auto replay = [](std::uint32_t capacity, std::uint32_t prime, std::uint64_t gap_us) {
        PcmPlaybackRing ring(config(1, 240, capacity));
        expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare jitter replay");
        const std::vector<std::int16_t> pcm(240, 321);
        std::vector<std::int16_t> output(256);
        bool started = false;
        std::uint64_t next_callback_ticks = 0;
        // One tick is 1/3 microsecond: 256 frames is exactly 16000 ticks at 48 kHz.
        for (std::uint64_t packet = 0; packet < 4000; ++packet) {
            const auto generated_us = packet * 5000;
            const auto phase_us = generated_us % 250000;
            const auto delivered_us = phase_us >= 100000 && phase_us < 100000 + gap_us
                ? generated_us + 100000 + gap_us - phase_us : generated_us;
            const auto delivered_ticks = delivered_us * 3;
            while (started && next_callback_ticks < delivered_ticks) {
                static_cast<void>(ring.consume(mutable_bytes(output), 256));
                next_callback_ticks += 16000;
            }
            static_cast<void>(ring.submit(bytes(pcm), 240, metadata(packet * 240)));
            if (!started && ring.occupancy_frames() >= prime * 240) {
                started = true;
                next_callback_ticks = delivered_ticks;
            }
        }
        return ring.snapshot();
    };
    const auto original = replay(4, 1, 40000);
    expect(original.pcm_frames_rejected > 0, "20 ms ring loses valid burst PCM");
    expect(original.underrun_frames > 0, "20 ms ring exhausts during receive gap");
    const auto insufficient = replay(12, 10, 60000);
    expect(insufficient.underrun_frames > 0, "50 ms reserve cannot cover a 60 ms gap");
    const auto corrected = replay(16, 14, 60000);
    expect_equal(corrected.pcm_frames_rejected, 0ULL, "bounded ring accepts recovered burst");
    expect_equal(corrected.underrun_frames, 0ULL, "bounded reserve covers receive gap");
    expect(corrected.ring_high_water_mark <= 3840, "jitter reserve stays bounded");
    const auto unprimed = replay(16, 1, 60000);
    expect(unprimed.underrun_frames > 0, "capacity alone is not a playout reserve");
}

void warm_output_priming_does_not_consume_early_or_hide_later_underflow() {
    warpnect::audio::AudioPlaybackPrimingGate gate(2400);
    expect(!gate.ready(0), "empty warm clock is not PCM ready");
    expect(!gate.ready(2399), "partial prime remains silent without consuming PCM");
    expect(gate.ready(2400), "full reserve activates PCM");
    expect(gate.ready(0), "later empty ring is an underrun, not hidden re-priming");
    gate.reset();
    expect(!gate.ready(0), "new playback lifetime needs its own reserve");
}

void stereo_system_reserve_covers_measured_ordered_phase_range() {
    const auto replay = [](std::uint32_t capacity, std::uint32_t prime, std::uint64_t excursion_us = 40000) {
        PcmPlaybackRing ring(config(2, 240, capacity));
        expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare stereo jitter replay");
        const std::vector<std::int16_t> pcm(480, 321);
        std::vector<std::int16_t> output(192);
        bool started = false;
        std::uint64_t next_callback_us = 0;
        for (std::uint64_t packet = 0; packet < 4000; ++packet) {
            const auto generated_us = packet * 5000;
            const auto phase_us = generated_us % 250000;
            const auto delivered_us = phase_us >= 100000 && phase_us < 100000 + excursion_us
                ? generated_us + 100000 + excursion_us - phase_us : generated_us;
            while (started && next_callback_us < delivered_us) {
                static_cast<void>(ring.consume(mutable_bytes(output), 96));
                next_callback_us += 2000;
            }
            static_cast<void>(ring.submit(bytes(pcm), 240, metadata(packet * 240)));
            if (!started && ring.occupancy_frames() >= prime * 240) {
                started = true;
                next_callback_us = delivered_us;
            }
        }
        return ring.snapshot();
    };
    const auto baseline = replay(4, 1);
    expect(baseline.pcm_frames_rejected > 0, "old stereo ring rejects continuous ordered audio");
    expect(baseline.underrun_frames > 0, "old stereo reserve starves");
    expect(replay(8, 6).underrun_frames > 0, "30 ms cannot cover a measured 40 ms phase excursion");
    const auto candidate = replay(12, 10);
    expect_equal(candidate.pcm_frames_rejected, 0ULL, "60 ms capacity accepts restored stereo burst");
    expect_equal(candidate.underrun_frames, 0ULL, "50 ms reserve covers bounded phase excursion");
    expect(candidate.ring_high_water_mark <= 2880, "system reserve remains bounded");
    // Audio-priority hardware run: lossless ordered arrival phase range 63.38 ms.
    expect(replay(13, 10, 65000).underrun_frames > 0, "50 ms cannot cover measured 63.38 ms phase range");
    expect(replay(15, 12, 65000).underrun_frames > 0, "60 ms reserve remains insufficient");
    const auto measured_range = replay(17, 14, 65000);
    expect_equal(measured_range.pcm_frames_rejected, 0ULL, "three spare packets accept stereo burst");
    expect_equal(measured_range.underrun_frames, 0ULL, "70 ms reserve covers rounded measured range");
    expect(measured_range.ring_high_water_mark <= 4080, "85 ms remains a hard capacity bound");
}

void explicit_discontinuity_rearms_reserve_without_hiding_ordinary_underflow() {
    warpnect::audio::AudioPlaybackPrimingGate microphone(3360);
    expect(microphone.ready(3360), "microphone reserve starts playback");
    expect(microphone.ready(0), "ordinary starvation remains observable");
    microphone.source_discontinuity();
    expect(!microphone.ready(3359), "explicit freshness reset requires the same reserve");
    expect(microphone.ready(3360), "fresh reserve resumes the existing output clock");
    expect(microphone.ready(0), "later starvation is not silently reclassified as priming");
    warpnect::audio::AudioPlaybackPrimingGate system_audio(0);
    system_audio.source_discontinuity();
    expect(system_audio.ready(0), "zero-threshold system audio remains unchanged");
}

void almost_fitting_microphone_block_waits_for_one_callback_without_rejection() {
    PcmPlaybackRing ring(config(1, 240, 16));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare bounded admission");
    const std::vector<std::int16_t> pcm(240, 123);
    std::vector<std::int16_t> output(256);
    for (std::uint64_t block = 0; block < 16; ++block) {
        static_cast<void>(ring.submit(bytes(pcm), 240, metadata(block * 240)));
    }
    std::vector<std::int16_t> partial(224);
    static_cast<void>(ring.consume(mutable_bytes(partial), 224));
    expect_equal(ring.occupancy_frames(), 3616U, "reproduce partial head plus fifteen complete slots");
    const warpnect::audio::AudioPlaybackCapacityWait admission(1, 256, 48000);
    expect(admission.next_wait_ns(ring.occupancy_frames(), ring.capacity_frames(), 240, 1) > 0,
           "16-frame shortage waits, does not drop 240 frames");
    static_cast<void>(ring.consume(mutable_bytes(output), 256));
    expect_equal(admission.next_wait_ns(ring.occupancy_frames(), ring.capacity_frames(), 240, 250001),
                 0ULL, "one callback makes complete block fit");
    expect_equal(ring.submit(bytes(pcm), 240, metadata(3840)).error,
                 AudioPlaybackError::None, "valid PCM admitted after callback");
    expect_equal(ring.snapshot().pcm_frames_rejected, 0ULL, "no loss counter suppressed");
}

void capacity_wait_expires_and_never_delays_invalid_or_already_fitting_blocks() {
    const warpnect::audio::AudioPlaybackCapacityWait admission(100, 256, 48000);
    expect_equal(admission.next_wait_ns(3840, 3840, 240, 5'333'433), 0ULL,
                 "stalled callback cannot extend one-burst deadline");
    expect_equal(admission.next_wait_ns(3600, 3840, 240, 100), 0ULL, "fitting block has no delay");
    expect_equal(admission.next_wait_ns(3840, 3840, 3841, 100), 0ULL, "invalid block not retried");
    expect_equal(admission.next_wait_ns(3840, 3840, 240, 99), 0ULL, "backward clock fails bounded");
    const warpnect::audio::AudioPlaybackCapacityWait unavailable(100, 0, 48000);
    expect_equal(unavailable.next_wait_ns(3840, 3840, 240, 100), 0ULL, "unknown burst never waits");
}

void stereo_system_block_waits_only_for_its_output_burst() {
    PcmPlaybackRing ring(config(2, 240, 4));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare stereo admission");
    const std::vector<std::int16_t> pcm(480, 123);
    for (std::uint64_t block = 0; block < 4; ++block) {
        static_cast<void>(ring.submit(bytes(pcm), 240, metadata(block * 240)));
    }
    std::vector<std::int16_t> output(192);
    static_cast<void>(ring.consume(mutable_bytes(output), 96));
    static_cast<void>(ring.consume(mutable_bytes(output), 96));
    const warpnect::audio::AudioPlaybackCapacityWait admission(1, 96, 48000);
    expect_equal(ring.occupancy_frames(), 768U, "192 consumed frames leave a partial stereo slot");
    expect(admission.next_wait_ns(768, 960, 240, 1) > 0, "48-frame deficit waits for one burst");
    static_cast<void>(ring.consume(mutable_bytes(output), 96));
    expect_equal(ring.submit(bytes(pcm), 240, metadata(960)).error,
                 AudioPlaybackError::None, "one stereo callback admits the complete block");
    expect_equal(admission.next_wait_ns(960, 960, 240, 2'000'001), 0ULL,
                 "system admission cannot exceed the two-millisecond burst budget");
    expect_equal(ring.snapshot().pcm_frames_rejected, 0ULL, "valid stereo PCM is not discarded");
}

void stereo_partial_head_admission_needs_one_spare_packet_when_callback_is_late() {
    const std::vector<std::int16_t> pcm(480, 123);
    const auto replay = [&](std::uint32_t slots) {
        PcmPlaybackRing ring(config(2, 240, slots));
        expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare measured partial head");
        for (std::uint64_t block = 0; block < 12; ++block) {
            static_cast<void>(ring.submit(bytes(pcm), 240, metadata(block * 240)));
        }
        std::vector<std::int16_t> output(384);
        static_cast<void>(ring.consume(mutable_bytes(output), 192));
        expect_equal(ring.occupancy_frames(), 2688U, "physical rejection occupancy after partial callback");
        static_cast<void>(ring.submit(bytes(pcm), 240, metadata(2880)));
        return ring.snapshot();
    };
    expect_equal(replay(12).pcm_frames_rejected, 240ULL, "60 ms loses a valid complete packet before the next callback");
    const auto candidate = replay(13);
    expect_equal(candidate.pcm_frames_rejected, 0ULL, "one spare packet admits without extending the wait deadline");
    expect_equal(candidate.ring_occupancy_frames, 2928U, "only the actual 240 frame packet is added");
}

void complete_system_block_admission_has_a_bounded_multi_burst_deadline() {
    PcmPlaybackRing ring(config(2, 240, 13));
    expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare complete stereo block admission");
    const std::vector<std::int16_t> pcm(480, 123);
    for (std::uint64_t block = 0; block < 13; ++block) {
        static_cast<void>(ring.submit(bytes(pcm), 240, metadata(block * 240)));
    }
    const warpnect::audio::AudioPlaybackCapacityWait one_burst(1, 96, 48000);
    const warpnect::audio::AudioPlaybackCapacityWait block(1, 96, 48000, 240);
    expect_equal(one_burst.next_wait_ns(3120, 3120, 240, 2'000'001), 0ULL,
                 "one hardware burst is too short for a complete packet at a full ring");
    std::vector<std::int16_t> output(192);
    for (std::uint64_t callback = 1; callback <= 3; ++callback) {
        expect(block.next_wait_ns(ring.occupancy_frames(), 3120, 240, callback * 1'500'000) > 0,
               "whole block budget survives until enough frames are consumed");
        static_cast<void>(ring.consume(mutable_bytes(output), 96));
    }
    expect_equal(ring.submit(bytes(pcm), 240, metadata(3120)).error,
                 AudioPlaybackError::None, "three small callbacks release the complete packet");
    expect_equal(block.next_wait_ns(3120, 3120, 240, 6'000'001), 0ULL,
                 "stalled output cannot extend the rounded six millisecond deadline");
    expect_equal(ring.snapshot().pcm_frames_rejected, 0ULL, "no valid buffer discarded or counter hidden");
}

void recorded_system_callback_delay_has_one_burst_of_nonrenewable_margin() {
    const auto replay = [](bool margin) {
        PcmPlaybackRing ring(config(2, 240, 17));
        expect_equal(ring.prepare(), AudioPlaybackError::None, "prepare unchanged85ms stereo ring");
        const std::vector<std::int16_t> pcm(480, 123);
        for (std::uint64_t block = 0; block < 17; ++block) {
            static_cast<void>(ring.submit(bytes(pcm), 240, metadata(block * 240)));
        }
        std::vector<std::int16_t> output(192);
        static_cast<void>(ring.consume(mutable_bytes(output), 96));
        static_cast<void>(ring.consume(mutable_bytes(output), 96));
        expect_equal(ring.occupancy_frames(), 3888U, "r866 two callbacks leave48 more frames to free");
        const warpnect::audio::AudioPlaybackCapacityWait admission(1, 96, 48000, 240, true, margin);
        const auto remaining = admission.next_wait_ns(3888, 4080, 240, 6'044'078);
        expect_equal(remaining > 0, margin, "old deadline expires before recorded third callback");
        if (remaining > 0) {
            expect(admission.next_wait_ns(3888, 4080, 240, 6'923'578) > 0,
                   "third callback completes within one additional burst");
            static_cast<void>(ring.consume(mutable_bytes(output), 96));
        }
        static_cast<void>(ring.submit(bytes(pcm), 240, metadata(4080)));
        return ring.snapshot().pcm_frames_rejected;
    };
    expect_equal(replay(false), 240ULL, "measured nominal-only deadline loses a valid packet");
    expect_equal(replay(true), 0ULL, "one burst margin reuses existing ring credit without loss");
    const warpnect::audio::AudioPlaybackCapacityWait candidate(1, 96, 48000, 240, true, true);
    expect_equal(candidate.next_wait_ns(4080, 4080, 240, 8'000'001), 0ULL,
                 "stalled callback cannot renew the eight millisecond deadline");
    expect_equal(candidate.next_wait_ns(3840, 4080, 240, 2), 0ULL,
                 "fitting packets acquire no extra reserve or wait");
    const warpnect::audio::AudioPlaybackCapacityWait disabled(1, 96, 48000, 240, false, true);
    expect_equal(disabled.next_wait_ns(4080, 4080, 240, 2), 0ULL,
                 "callback margin never enables a disabled microphone wait");
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
    bounded_network_jitter_requires_both_playout_reserve_and_burst_headroom();
    warm_output_priming_does_not_consume_early_or_hide_later_underflow();
    stereo_system_reserve_covers_measured_ordered_phase_range();
    explicit_discontinuity_rearms_reserve_without_hiding_ordinary_underflow();
    almost_fitting_microphone_block_waits_for_one_callback_without_rejection();
    capacity_wait_expires_and_never_delays_invalid_or_already_fitting_blocks();
    stereo_system_block_waits_only_for_its_output_burst();
    stereo_partial_head_admission_needs_one_spare_packet_when_callback_is_late();
    complete_system_block_admission_has_a_bounded_multi_burst_deadline();
    recorded_system_callback_delay_has_one_burst_of_nonrenewable_margin();

    if (failures != 0) {
        std::cerr << failures << " audio playback ring test failure(s)\n";
        return 1;
    }
    std::cout << "Audio playback ring tests passed\n";
    return 0;
}
