#pragma once

#include <cstdint>

namespace warpnect::audio {

// Callback-thread state. Priming must not hold queued live PCM beyond its reserve.
class AudioPlaybackPrimingGate final {
public:
    explicit AudioPlaybackPrimingGate(std::uint32_t threshold_frames,
                                     std::uint32_t sample_rate_hz = 0) noexcept
        : threshold_frames_(threshold_frames),
          reserve_ns_(sample_rate_hz == 0 ? 0 :
              static_cast<std::uint64_t>(threshold_frames) * 1'000'000'000ULL / sample_rate_hz) {}

    [[nodiscard]] bool ready(std::uint32_t occupancy_frames, std::uint64_t now_ns = 0,
                             std::uint64_t oldest_submit_ns = 0,
                             std::uint32_t requested_frames = 1) noexcept {
        const bool reserve_elapsed = reserve_ns_ > 0 && oldest_submit_ns > 0 &&
            now_ns >= oldest_submit_ns && now_ns - oldest_submit_ns >= reserve_ns_ &&
            requested_frames > 0 && occupancy_frames >= requested_frames;
        primed_ = primed_ || occupancy_frames >= threshold_frames_ || reserve_elapsed;
        return primed_;
    }

    void reset() noexcept { primed_ = false; }

    void source_discontinuity() noexcept {
        if (threshold_frames_ > 0) primed_ = false;
    }

private:
    std::uint32_t threshold_frames_ = 0;
    const std::uint64_t reserve_ns_;
    bool primed_ = false;
};

} // namespace warpnect::audio
