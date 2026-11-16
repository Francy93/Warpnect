#include "audio_causal_flight_recorder.h"
#include "audio_playback_capacity_wait.h"
#include "audio_playback_ring.h"
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <new>
#include <thread>

static std::atomic<bool> watch_allocations{false};
static std::atomic<std::size_t> observed_allocations{0};

void* operator new(std::size_t bytes) {
    if (watch_allocations.load(std::memory_order_relaxed))
        observed_allocations.fetch_add(1, std::memory_order_relaxed);
    if (auto* memory = std::malloc(bytes ? bytes : 1)) return memory;
    throw std::bad_alloc();
}
void operator delete(void* memory) noexcept { std::free(memory); }
void operator delete(void* memory, std::size_t) noexcept { std::free(memory); }
void* operator new[](std::size_t bytes) { return ::operator new(bytes); }
void operator delete[](void* memory) noexcept { ::operator delete(memory); }
void operator delete[](void* memory, std::size_t) noexcept { ::operator delete(memory); }

using namespace warpnect::audio::diagnostic;
static void check(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

static void observe_bounded_cost() {
    auto recorder = std::make_unique<AudioCausalFlightRecorder>();
    std::atomic<unsigned> sink{0};
    constexpr std::size_t samples = 4096;
    std::array<std::uint64_t, samples> elapsed{};
    for (unsigned mode = 0; mode < 3; ++mode) {
        auto* selected = mode == 0 ? nullptr : recorder.get();
        if (mode == 2) check(recorder->trigger(FlightTrigger::CallbackGap, 1), "cost trigger");
        watch_allocations.store(true);
        for (std::size_t n = 0; n < samples; ++n) {
            const auto begin = std::chrono::steady_clock::now();
            if (selected) sink.fetch_add(selected->record(FlightLane::Callback,
                {.begin=AudioCausalFlightRecorder::post_trigger_ns + n + 2,
                 .end=AudioCausalFlightRecorder::post_trigger_ns + n + 2}), std::memory_order_relaxed);
            else sink.fetch_add(0, std::memory_order_relaxed);
            const auto end = std::chrono::steady_clock::now();
            elapsed[n] = static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(end - begin).count());
        }
        watch_allocations.store(false);
        check(observed_allocations.load() == 0, "record/off/frozen paths allocate no ordinary C++ heap");
        std::sort(elapsed.begin(), elapsed.end());
        std::cout << "HOST_CORE_COST mode=" << mode << " samples=" << samples
                  << " p50_ns=" << elapsed[samples / 2] << " p95_ns=" << elapsed[samples * 95 / 100]
                  << " max_ns=" << elapsed.back() << " allocations=" << observed_allocations.load()
                  << " recorder_bytes=" << sizeof(AudioCausalFlightRecorder) << '\n';
    }
    check(sink.load() == samples, "only enabled unfrozen records accepted");
    check(recorder->stats_quiescent(FlightLane::Callback).frozen == samples, "frozen writes accounted");
}

int main() {
    observe_bounded_cost();
    auto recorder = std::make_unique<AudioCausalFlightRecorder>();
    const auto count = AudioCausalFlightRecorder::capacities[0] + 37;
    for (std::size_t i = 0; i < count; ++i)
        check(recorder->record(FlightLane::Wire, {.begin=i+1, .end=i+1, .position=i}), "bounded append");
    auto stats = recorder->stats_quiescent(FlightLane::Wire);
    check(stats.written == count && stats.overwritten == 37, "exact overwrite accounting");
    std::size_t seen = 0;
    recorder->visit_quiescent(FlightLane::Wire, [&](const FlightEvent& e) {
        check(e.position == 37 + seen++, "circular order");
    });
    check(seen == AudioCausalFlightRecorder::capacities[0], "fixed capacity");
    check(!recorder->record(FlightLane::Callback, {.begin=10, .end=9}), "clock regression rejected");
    check(!recorder->record(FlightLane::Callback, {}), "missing timestamp rejected");
    check(recorder->trigger(FlightTrigger::CallbackGap, 100'000), "first trigger retained");
    check(!recorder->trigger(FlightTrigger::Underrun, 100'001), "no overwrite of first reason");
    check(recorder->reason() == FlightTrigger::CallbackGap, "reason published with trigger");
    const auto edge = 100'000 + AudioCausalFlightRecorder::post_trigger_ns;
    check(recorder->record(FlightLane::Callback, {.begin=edge, .end=edge}), "inclusive post window");
    check(!recorder->record(FlightLane::Callback, {.begin=edge+1, .end=edge+1}), "hard freeze");
    check(recorder->stats_quiescent(FlightLane::Callback).frozen == 1, "frozen != overflow");
    check(recorder->stats_quiescent(FlightLane::Callback).invalid == 2, "invalid timestamp count");

    auto parallel = std::make_unique<AudioCausalFlightRecorder>();
    std::thread a([&] {
        for (unsigned i=1; i<=10000; ++i) parallel->record(FlightLane::Callback, {.begin=i, .end=i});
    });
    std::thread b([&] {
        for (unsigned i=1; i<=10000; ++i) parallel->record(FlightLane::Wire, {.begin=i, .end=i});
    });
    a.join(); b.join();
    check(parallel->stats_quiescent(FlightLane::Callback).written == 10000, "independent callback lane");
    check(parallel->stats_quiescent(FlightLane::Wire).written == 10000, "independent ingress lane");
    check(parallel->trigger_ns() == 0, "recording alone cannot fabricate failure");
    auto fresh = std::make_unique<AudioCausalFlightRecorder>();
    check(fresh->trigger_ns() == 0 && fresh->stats_quiescent(FlightLane::Wire).written == 0,
          "fresh lifetime has no previous generation data");

    auto counter_history = std::make_unique<AudioCausalFlightRecorder>();
    constexpr auto trigger_time = 10'000'000'000ULL;
    for (unsigned i=0; i<1600; ++i) {
        const auto time = trigger_time - 5'000'000'000ULL + i * 5'000'000ULL;
        counter_history->record(FlightLane::Observer,
            {.begin=time, .end=time, .counter=time>=trigger_time ? 1ULL : 0ULL, .kind=FlightKind::Xrun});
        if (time == trigger_time) counter_history->trigger(FlightTrigger::Xrun, time);
    }
    bool before_counter=false, first_change=false, post_edge=false;
    counter_history->visit_quiescent(FlightLane::Observer, [&](const FlightEvent& e) {
        before_counter |= e.begin == trigger_time-5'000'000 && e.counter == 0;
        first_change |= e.begin == trigger_time && e.counter == 1;
        post_edge |= e.begin == trigger_time+AudioCausalFlightRecorder::post_trigger_ns;
    });
    check(before_counter && first_change && post_edge,
          "200Hz SDK observations retain pre-counter, first change and full2s post-window");

    check(FlightClockBracket{100,1000,101}.valid(), "bounded clock bracket");
    check(!FlightClockBracket{101,1000,100}.valid(), "clock regression invalidates correlation");
    check(!FlightClockBracket{100,1000,100101}.valid(), "slow observer invalidates fine correlation");

    // A consumer pause does not imply rejection: actual demand and occupancy matter.
    using warpnect::audio::AudioPlaybackCapacityWait;
    AudioPlaybackCapacityWait admission(1'000'000'000, 96, 48000, 240, true, true);
    check(admission.next_wait_ns(4032,4080,240,1'000'000'000) > 0, "full ring waits");
    check(admission.next_wait_ns(4032,4080,240,1'007'999'999) > 0, "not expired early");
    check(admission.next_wait_ns(4032,4080,240,1'008'000'000) == 0, "unchanged8ms expiry");
    check(admission.next_wait_ns(3840,4080,240,1'008'000'000) == 0, "room admits even at deadline");
    using namespace warpnect::audio;
    PcmPlaybackRing ring({.source=AudioCaptureSource::SystemAudio, .config_generation=7,
        .sample_rate_hz=48000, .channel_count=2, .frames_per_codec_frame=240,
        .ring_capacity_codec_frames=17});
    check(ring.prepare() == AudioPlaybackError::None, "actual85ms ring prepares");
    std::array<std::byte,960> pcm{};
    for (unsigned n=0; n<17; ++n)
        check(ring.submit(pcm,240,{.config_generation=7,.first_frame_position=n*240ULL},
                          1'000'000'000).error == AudioPlaybackError::None, "fill exact ring");
    std::array<std::byte,192> output{};
    check(ring.consume(output,48,1'000'000'000).pcm_frames_copied == 48, "partial callback consume");
    check(ring.occupancy_frames() == 4032, "historical pressure occupancy");
    auto refused=ring.submit(pcm,240,{.config_generation=7,.first_frame_position=4080},1'008'000'000);
    check(refused.error == AudioPlaybackError::PlaybackRingFull &&
          ring.snapshot().pcm_frames_rejected == 240, "no callback progress causes real240-frame rejection");
    std::array<std::byte,768> progress{};
    check(ring.consume(progress,192,1'023'000'000).pcm_frames_copied == 192, "later consumer catches up");
    check(ring.submit(pcm,240,{.config_generation=7,.first_frame_position=4320},1'023'000'001).error ==
          AudioPlaybackError::None, "same demand after progress is admitted without larger buffer");
    std::cout << "PASS causal recorder: bounded lanes, first trigger, freeze, clocks, ownership and admission replay\n";
}
