#pragma once

#if defined(__ANDROID__) && !defined(NDEBUG)
#include <array>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <android/log.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

namespace warpnect::audio::android {

class MicrophoneRingTimingDebug final {
public:
    struct Event {
        std::uint64_t begin, end, position, submitted, consumed;
        std::uint32_t frames, before, after, callbacks, priming;
        int result;
        bool discontinuity;
    };

    static std::unique_ptr<MicrophoneRingTimingDebug> create() noexcept {
        char property[PROP_VALUE_MAX]{};
        if (__system_property_get("debug.warpnect.mic_ring_timing", property) <= 0 ||
            std::strcmp(property, "1") != 0) return {};
        std::atomic<std::uint64_t> capability{0};
        if (!capability.is_lock_free()) return {};
        return std::unique_ptr<MicrophoneRingTimingDebug>(new (std::nothrow) MicrophoneRingTimingDebug);
    }
    ~MicrophoneRingTimingDebug() { finish(); }
    static std::uint64_t now() noexcept {
        timespec t{};
        clock_gettime(CLOCK_MONOTONIC, &t);
        return static_cast<std::uint64_t>(t.tv_sec) * 1000000000ULL + t.tv_nsec;
    }
    void submit(Event event, bool full) noexcept {
        if (full) {
            std::uint64_t unset = 0;
            first_full_.compare_exchange_strong(unset, event.end, std::memory_order_release);
        }
        if (retaining(event.begin)) submits_[submit_count_++ % submits_.size()] = event;
    }
    void callback(Event event) noexcept {
        if (retaining(event.begin)) callbacks_[callback_count_++ % callbacks_.size()] = event;
    }
    void finish() noexcept {
        if (finished_) return;
        finished_ = true;
        char path[192]{};
        std::snprintf(path, sizeof(path), "/data/data/io.warpnect/cache/mic-ring-%d-%llu.csv",
            getpid(), static_cast<unsigned long long>(created_));
        FILE* file = std::fopen(path, "w");
        if (file) {
            std::fprintf(file, "META,first_full=%llu,submits=%llu,callbacks=%llu,slots=1024,post_full_ns=250000000\n",
                static_cast<unsigned long long>(first_full_.load()),
                static_cast<unsigned long long>(submit_count_), static_cast<unsigned long long>(callback_count_));
            std::fprintf(file, "type,begin,end,position,submitted,consumed,frames,before,after,callbacks,priming,result,discontinuity\n");
            dump(file, 1, submits_, submit_count_);
            dump(file, 2, callbacks_, callback_count_);
            std::fclose(file);
        }
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
            "MIC_RING_TIMING_FINISH file=%s saved=%d", path, file != nullptr);
    }

private:
    bool retaining(std::uint64_t now_ns) const noexcept {
        const auto first = first_full_.load(std::memory_order_acquire);
        return first == 0 || now_ns <= first + 250000000ULL;
    }
    static void dump(FILE* file, int type, const std::array<Event, 1024>& rows, std::uint64_t count) {
        const auto start = count > rows.size() ? count - rows.size() : 0;
        for (auto i = start; i < count; ++i) {
            const auto& e = rows[i % rows.size()];
            std::fprintf(file, "%d,%llu,%llu,%llu,%llu,%llu,%u,%u,%u,%u,%u,%d,%d\n", type,
                static_cast<unsigned long long>(e.begin), static_cast<unsigned long long>(e.end),
                static_cast<unsigned long long>(e.position), static_cast<unsigned long long>(e.submitted),
                static_cast<unsigned long long>(e.consumed), e.frames, e.before, e.after,
                e.callbacks, e.priming, e.result, e.discontinuity);
        }
    }
    const std::uint64_t created_ = now();
    std::array<Event, 1024> submits_{}, callbacks_{};
    std::uint64_t submit_count_ = 0, callback_count_ = 0; // One writer per array; dump after stream/ingress stop.
    std::atomic<std::uint64_t> first_full_{0};
    bool finished_ = false;
};

} // namespace warpnect::audio::android
#endif
