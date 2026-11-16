#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace warpnect::audio::diagnostic {

enum class FlightLane : std::uint32_t { Wire, Codec, Admission, Callback, Observer, Count };
enum class FlightKind : std::uint32_t {
    Receive = 1, Authenticated, Decode, Admission, Callback, Xrun, Lifecycle,
    ReceiveFault, DecodeFault, ConsumerFault, Encode, Send, Dispatch, DecoderCreated, Readiness,
    ReadyBuffer, ReceiverSnapshot,
};
enum class FlightTrigger : std::uint32_t {
    None, Rejected, Underrun, Xrun, ReceiveGap, SocketAge, CallbackGap, DecoderReset,
};

// begin/end are local CLOCK_MONOTONIC. aux is event-specific, never implicitly subtracted.
struct FlightEvent final {
    std::uint64_t begin = 0, end = 0, position = 0, aux = 0, counter = 0;
    FlightKind kind{};
    std::uint32_t tid = 0, generation = 0, sequence = UINT32_MAX;
    std::uint32_t frames = 0, before = 0, after = 0, code = 0, phase = 0;
};
static_assert(sizeof(FlightEvent) == 80);

class AudioCausalFlightRecorder final {
public:
    static constexpr std::uint64_t post_trigger_ns = 2'000'000'000ULL;
    // The existing SDK snapshot runs per PCM unit, not just per log window.
    // Retain its first counter change through the complete 2s post-trigger window.
    static constexpr std::array<std::size_t, 5> capacities{4096, 2048, 2048, 3072, 1280};
    static constexpr std::array<std::size_t, 5> offsets{0, 4096, 6144, 8192, 11264};
    static constexpr std::size_t record_capacity = 12544;

    struct LaneStats final {
        std::uint64_t written = 0, overwritten = 0, contention = 0, frozen = 0, invalid = 0;
    };

    bool record(FlightLane lane, const FlightEvent& event) noexcept {
        const auto i = static_cast<std::size_t>(lane);
        if (i >= capacities.size()) return false;
        auto& state = lanes_[i];
        // Never spin or wait for another observer, including during callback execution.
        if (state.writer.test_and_set(std::memory_order_acquire)) {
            state.contention.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        const auto trigger = trigger_ns_.load(std::memory_order_acquire);
        if (event.begin == 0 || event.end < event.begin) ++state.invalid;
        else if (trigger != 0 && event.end > trigger && event.end - trigger > post_trigger_ns)
            ++state.frozen;
        else {
            rows_[offsets[i] + state.written % capacities[i]] = event;
            ++state.written;
            state.writer.clear(std::memory_order_release);
            return true;
        }
        state.writer.clear(std::memory_order_release);
        return false;
    }

    bool trigger(FlightTrigger reason, std::uint64_t now) noexcept {
        if (reason == FlightTrigger::None || now == 0) return false;
        if (trigger_claim_.test_and_set(std::memory_order_acquire)) return false;
        reason_.store(reason, std::memory_order_relaxed);
        trigger_ns_.store(now, std::memory_order_release);
        return true;
    }
    std::uint64_t trigger_ns() const noexcept { return trigger_ns_.load(std::memory_order_acquire); }
    FlightTrigger reason() const noexcept { return reason_.load(std::memory_order_acquire); }

    // Only after ALL component owners have stopped; no concurrent export or reset API.
    LaneStats stats_quiescent(FlightLane lane) const noexcept {
        const auto i = static_cast<std::size_t>(lane);
        const auto& s = lanes_[i];
        return {s.written, s.written > capacities[i] ? s.written - capacities[i] : 0,
                s.contention.load(), s.frozen, s.invalid};
    }
    template<class Visitor> void visit_quiescent(FlightLane lane, Visitor visitor) const {
        const auto i = static_cast<std::size_t>(lane);
        const auto written = lanes_[i].written;
        const auto first = written > capacities[i] ? written - capacities[i] : 0;
        for (auto n = first; n < written; ++n) visitor(rows_[offsets[i] + n % capacities[i]]);
    }

private:
    struct Lane final {
        std::atomic_flag writer = ATOMIC_FLAG_INIT;
        std::atomic<std::uint64_t> contention{0};
        std::uint64_t written = 0, frozen = 0, invalid = 0;
    };
    std::array<FlightEvent, record_capacity> rows_{};
    std::array<Lane, 5> lanes_{};
    std::atomic_flag trigger_claim_ = ATOMIC_FLAG_INIT;
    std::atomic<std::uint64_t> trigger_ns_{0};
    std::atomic<FlightTrigger> reason_{FlightTrigger::None};
};
static_assert(sizeof(AudioCausalFlightRecorder) < 1024 * 1024);

// Clock brackets fail closed across clock steps or an excessively slow observer.
struct FlightClockBracket final {
    std::uint64_t mono_before, realtime, mono_after;
    bool valid() const noexcept { return mono_before > 0 && realtime > 0 &&
        mono_after >= mono_before && mono_after - mono_before <= 100'000; }
};

} // namespace warpnect::audio::diagnostic
