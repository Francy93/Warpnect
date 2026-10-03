#pragma once

#include <cstdint>

namespace warpnect::audio {

// Callback-thread state. An explicit source discontinuity rearms the existing reserve.
class AudioPlaybackPrimingGate final {
public:
    explicit AudioPlaybackPrimingGate(std::uint32_t threshold_frames) noexcept
        : threshold_frames_(threshold_frames) {}

    [[nodiscard]] bool ready(std::uint32_t occupancy_frames) noexcept {
        primed_ = primed_ || occupancy_frames >= threshold_frames_;
        return primed_;
    }

    void reset() noexcept { primed_ = false; }

    void source_discontinuity() noexcept {
        if (threshold_frames_ > 0) primed_ = false;
    }

private:
    std::uint32_t threshold_frames_ = 0;
    bool primed_ = false;
};

} // namespace warpnect::audio
