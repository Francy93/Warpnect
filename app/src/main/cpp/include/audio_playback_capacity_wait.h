#pragma once

#include <algorithm>
#include <cstdint>

namespace warpnect::audio {

// Producer-only admission. System blocks may need several hardware bursts to fit.
class AudioPlaybackCapacityWait final {
public:
    AudioPlaybackCapacityWait(std::uint64_t started_ns, std::uint32_t burst_frames,
                              std::uint32_t sample_rate_hz,
                              std::uint32_t required_block_frames = 0) noexcept
        : started_ns_(started_ns), budget_ns_(sample_rate_hz == 0 || burst_frames == 0 ? 0 :
              std::max<std::uint64_t>(1, (static_cast<std::uint64_t>(required_block_frames) +
                  burst_frames - 1) / burst_frames) * burst_frames * 1'000'000'000ULL / sample_rate_hz) {}

    [[nodiscard]] std::uint64_t next_wait_ns(std::uint32_t occupancy, std::uint32_t capacity,
                                            std::uint32_t requested, std::uint64_t now_ns) const noexcept {
        if (requested == 0 || requested > capacity || occupancy <= capacity - requested ||
            now_ns < started_ns_ || now_ns - started_ns_ >= budget_ns_) return 0;
        return std::min<std::uint64_t>(250'000, budget_ns_ - (now_ns - started_ns_));
    }

private:
    std::uint64_t started_ns_;
    std::uint64_t budget_ns_;
};

} // namespace warpnect::audio
