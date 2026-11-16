#pragma once

#include <cstdint>

namespace warpnect::audio {

// Give existing callback credit a bounded publication window, never a full-burst wait.
class AudioPlaybackCallbackHandoff final {
public:
    static constexpr std::uint64_t kMaximumNs = 200'000;

    AudioPlaybackCallbackHandoff(std::uint64_t begin_ns,
                                 std::uint64_t previous_callback_ns) noexcept
        : begin_ns_(begin_ns), eligible_(begin_ns > 0 && previous_callback_ns > 0 &&
                                        previous_callback_ns <= begin_ns) {}

    bool may_recheck(std::uint64_t now_ns) const noexcept {
        return eligible_ && now_ns >= begin_ns_ && now_ns - begin_ns_ < kMaximumNs;
    }

private:
    const std::uint64_t begin_ns_;
    bool eligible_ = false;
};

} // namespace warpnect::audio
