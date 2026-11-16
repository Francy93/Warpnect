#pragma once
#include <algorithm>
#include <cstdint>

namespace warpnect::audio {
// Estimates only the existing SDK buffer's draining clock, never PCM freshness or a jitter reserve.
// A nonblocking zero/partial result is the authoritative SDK capacity observation.
class MicrophoneSdkWriteClock {
public:
    MicrophoneSdkWriteClock(int capacity, int rate) : capacity_(capacity), rate_(rate) {}
    void start(int primed, int64_t now) noexcept {
        ahead_ = std::clamp(primed, 0, capacity_); last_ = now; fraction_ = 0;
        observed_read_ = observed_written_ = -1;
    }
    int ahead(int64_t now) noexcept {
        if (now <= last_) return ahead_;
        const auto elapsed = now - last_; last_ = now;
        if (elapsed >= 1000000000LL) { ahead_ = 0; fraction_ = 0; return 0; }
        const auto scaled = elapsed * rate_ + fraction_;
        ahead_ = static_cast<int>(std::max<int64_t>(0, ahead_ - scaled / 1000000000LL));
        fraction_ = scaled % 1000000000LL;
        return ahead_;
    }
    void written(int requested, int accepted, int64_t now) noexcept {
        ahead(now);
        if (accepted < 0 || accepted > requested) return;
        ahead_ = std::min(capacity_, ahead_ + accepted);
        if (accepted < requested) ahead_ = std::max(ahead_, std::max(0, capacity_ - requested));
    }
    void bound_from_read_position(int64_t written, int64_t read, int64_t sampled,
            int64_t now, int64_t maximum_age) noexcept {
        if (written < 0 || read < 0 || sampled <= 0 || now < sampled || now - sampled > maximum_age) return;
        if (read < observed_read_ || written < observed_written_) return;
        const bool progressed = read != observed_read_;
        observed_read_ = read;
        observed_written_ = written;
        ahead(now);
        // Repeated getters of one coarse endpoint position are not new output progress.
        // Accepted writes already add their own credit; do not renew its draining deadline.
        if (!progressed) return;
        const auto drained = ((now - sampled) * rate_ + 999999999LL) / 1000000000LL;
        const auto observed = std::clamp<int64_t>(written - read - drained, 0, capacity_);
        // Reconcile the silence deadline with actual endpoint progress, not only faster drain.
        // This never grants PCM admission credits or changes the allocated SDK buffer.
        ahead_ = static_cast<int>(observed);
        fraction_ = 0;
    }
    bool maintenance_due(bool active, int packet, int idle_burst, int64_t now) noexcept {
        const auto queued = ahead(now);
        if (capacity_ - queued < packet) return false;
        // Missing ingress is not a deadline while the existing SDK audio still
        // covers output. Real PCM is attempted independently of this estimate.
        return active ? queued == 0 : queued <= idle_burst;
    }
    bool idle_maintenance_due(bool consumer_active, int packet, int idle_burst, int64_t now) noexcept {
        // A push writer has no callback demand. A coarse read count cannot justify
        // committing zeros ahead of not-yet-arrived live PCM while a consumer reads.
        return !consumer_active && maintenance_due(false, packet, idle_burst, now);
    }
    bool active_maintenance_due(bool consumer_active, int packet, int64_t now) noexcept {
        return consumer_active && observed_read_ >= 0 && packet > 0 &&
            maintenance_due(true, packet, 0, now);
    }
private:
    int capacity_, rate_, ahead_ = 0;
    int64_t last_ = 0, fraction_ = 0;
    int64_t observed_read_ = -1, observed_written_ = -1;
};
}
