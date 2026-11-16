#pragma once

#if defined(__ANDROID__) && !defined(NDEBUG)
#include "audio_causal_flight_recorder.h"
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sys/system_properties.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

namespace warpnect::audio::diagnostic {

class SystemAudioFlightDebug final {
public:
    static std::shared_ptr<SystemAudioFlightDebug> create(std::uint32_t generation,
                                                        std::uint64_t session_tag) noexcept {
        std::atomic<std::uint64_t> capability{0};
        if (!capability.is_lock_free()) return {};
        char property[PROP_VALUE_MAX]{};
        if (__system_property_get("debug.warpnect.system_flight", property) <= 0 ||
            std::strcmp(property, "1") != 0) return {};
        try {
            std::lock_guard guard(registry_mutex());
            if (!registry().expired()) { blocked() = true; return {}; }
            auto result = std::shared_ptr<SystemAudioFlightDebug>(
                new SystemAudioFlightDebug(generation, session_tag));
            registry() = result;
            blocked() = false;
            return result;
        } catch (...) { return {}; } // Diagnostic exhaustion cannot fail a Session.
    }
    static std::shared_ptr<SystemAudioFlightDebug> current() noexcept {
        try {
            std::lock_guard guard(registry_mutex());
            if (blocked()) return {};
            return registry().lock(); // Cold component creation only, never the callback.
        } catch (...) { return {}; }
    }
    static std::uint64_t now(clockid_t id = CLOCK_MONOTONIC) noexcept {
        timespec t{};
        if (clock_gettime(id, &t) != 0) return 0;
        return static_cast<std::uint64_t>(t.tv_sec) * 1'000'000'000ULL + t.tv_nsec;
    }
    static std::uint32_t tid() noexcept {
        // API26-targeted C++ TLS may allocate lazily on the first callback.
        return static_cast<std::uint32_t>(syscall(SYS_gettid));
    }
    void channel(std::uint32_t id) noexcept { channel_.store(id); }
    void active(std::uint64_t time) noexcept {
        std::uint64_t empty = 0;
        pcm_start_.compare_exchange_strong(empty, time);
        if (phase_.load(std::memory_order_relaxed) != 3) phase_.store(2, std::memory_order_relaxed);
    }
    void emit(FlightLane lane, FlightEvent e) noexcept {
        e.tid = tid();
        e.phase = phase_.load(std::memory_order_relaxed);
        recorder_.record(lane, e);
    }
    void trigger(FlightTrigger kind, std::uint64_t at) noexcept { recorder_.trigger(kind, at); }
    void lifecycle(std::uint32_t code, std::uint32_t generation) noexcept {
        if (code >= 2) phase_.store(3, std::memory_order_relaxed);
        const auto time = now();
        emit(FlightLane::Observer, {.begin=time, .end=time, .kind=FlightKind::Lifecycle,
             .generation=generation, .code=code});
    }
    void observe_trigger() noexcept {
        if (recorder_.trigger_ns() && !reported_.exchange(true))
            __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
                "SYSTEM_FLIGHT_TRIGGER mono=%llu reason=%u session_generation=%u",
                static_cast<unsigned long long>(recorder_.trigger_ns()),
                static_cast<unsigned>(recorder_.reason()), session_generation_);
    }
    bool active_phase() const noexcept { return phase_.load(std::memory_order_relaxed) == 2; }
    bool stopping() const noexcept { return phase_.load(std::memory_order_relaxed) == 3; }
    const AudioCausalFlightRecorder& quiescent_test_view() const noexcept { return recorder_; }
    ~SystemAudioFlightDebug() {
        // Last shared owner: receiver, decoder, producer and callback have all quiesced.
        const auto end_mono_before = now(), end_real = now(CLOCK_REALTIME), end_mono_after = now();
        const auto boot_before = now(), boot = now(CLOCK_BOOTTIME), boot_after = now();
        char path[192]{};
        std::snprintf(path, sizeof(path), "/data/data/io.warpnect/cache/system-flight-%d-%llu.csv",
                      getpid(), static_cast<unsigned long long>(created_));
        FILE* f = std::fopen(path, "w");
        if (f) {
            std::fprintf(f, "META,version=1,clock=MONOTONIC,session_generation=%u,session_tag=%llu,channel=%u,created=%llu,trigger=%llu,reason=%u,post_ns=%llu,bytes=%zu,fault=%u\n",
                session_generation_, static_cast<unsigned long long>(session_tag_), channel_.load(),
                static_cast<unsigned long long>(created_), static_cast<unsigned long long>(recorder_.trigger_ns()),
                static_cast<unsigned>(recorder_.reason()),
                static_cast<unsigned long long>(AudioCausalFlightRecorder::post_trigger_ns), sizeof(*this), 0U);
            std::fprintf(f, "CLOCK,start,%llu,%llu,%llu\nCLOCK,end,%llu,%llu,%llu\n",
                static_cast<unsigned long long>(clock_start_.mono_before),
                static_cast<unsigned long long>(clock_start_.realtime),
                static_cast<unsigned long long>(clock_start_.mono_after),
                static_cast<unsigned long long>(end_mono_before),
                static_cast<unsigned long long>(end_real), static_cast<unsigned long long>(end_mono_after));
            std::fprintf(f, "BOOT,start,%llu,%llu,%llu\nBOOT,end,%llu,%llu,%llu\n",
                static_cast<unsigned long long>(clock_boot_.mono_before),
                static_cast<unsigned long long>(clock_boot_.realtime),
                static_cast<unsigned long long>(clock_boot_.mono_after),
                static_cast<unsigned long long>(boot_before), static_cast<unsigned long long>(boot),
                static_cast<unsigned long long>(boot_after));
            std::fprintf(f, "lane,begin,end,position,aux,counter,kind,tid,generation,sequence,frames,before,after,code,phase\n");
            for (unsigned i = 0; i < 5; ++i) {
                const auto lane = static_cast<FlightLane>(i);
                const auto s = recorder_.stats_quiescent(lane);
                std::fprintf(f, "LANE,%u,capacity=%zu,written=%llu,overwritten=%llu,contention=%llu,frozen=%llu,invalid=%llu\n", i,
                    AudioCausalFlightRecorder::capacities[i],
                    static_cast<unsigned long long>(s.written), static_cast<unsigned long long>(s.overwritten),
                    static_cast<unsigned long long>(s.contention), static_cast<unsigned long long>(s.frozen),
                    static_cast<unsigned long long>(s.invalid));
                recorder_.visit_quiescent(lane, [&](const FlightEvent& e) {
                    std::fprintf(f, "%u,%llu,%llu,%llu,%llu,%llu,%u,%u,%u,%u,%u,%u,%u,%u,%u\n", i,
                        static_cast<unsigned long long>(e.begin), static_cast<unsigned long long>(e.end),
                        static_cast<unsigned long long>(e.position), static_cast<unsigned long long>(e.aux),
                        static_cast<unsigned long long>(e.counter), static_cast<unsigned>(e.kind), e.tid,
                        e.generation, e.sequence, e.frames, e.before, e.after, e.code, e.phase);
                });
            }
            std::fclose(f);
        }
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
                            "SYSTEM_FLIGHT_FINISH file=%s saved=%d", path, f != nullptr);
    }
private:
    SystemAudioFlightDebug(std::uint32_t generation, std::uint64_t tag)
        : session_generation_(generation), session_tag_(tag), created_(now()),
          clock_start_{now(), now(CLOCK_REALTIME), now()}, clock_boot_{now(), now(CLOCK_BOOTTIME), now()} {
    }
    static std::mutex& registry_mutex() { static std::mutex lock; return lock; }
    static bool& blocked() { static bool value = false; return value; }
    static std::weak_ptr<SystemAudioFlightDebug>& registry() {
        static std::weak_ptr<SystemAudioFlightDebug> current; return current;
    }
    const std::uint32_t session_generation_;
    const std::uint64_t session_tag_, created_;
    const FlightClockBracket clock_start_, clock_boot_;
    AudioCausalFlightRecorder recorder_{};
    std::atomic<std::uint32_t> channel_{0}, phase_{1};
    std::atomic<std::uint64_t> pcm_start_{0};
    std::atomic<bool> reported_{false};
};
static_assert(sizeof(SystemAudioFlightDebug) < 1024 * 1024);

} // namespace warpnect::audio::diagnostic
#endif
