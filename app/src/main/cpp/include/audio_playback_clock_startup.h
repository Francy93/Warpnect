#pragma once

#include <cstdint>

namespace warpnect::audio {

// STARTED and callback prefill do not prove that the endpoint consumes frames.
class AudioPlaybackClockStartup {
public:
    enum class State { Waiting, Ready, TimedOut };
    AudioPlaybackClockStartup(int64_t initial_read, uint64_t deadline_ns) noexcept
        : initial_read_(initial_read), deadline_ns_(deadline_ns) {}

    State observe(int64_t read, uint64_t now_ns) const noexcept {
        if (now_ns >= deadline_ns_) return State::TimedOut;
        return read > initial_read_ ? State::Ready : State::Waiting;
    }

private:
    int64_t initial_read_;
    uint64_t deadline_ns_;
};

} // namespace warpnect::audio
