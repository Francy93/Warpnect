#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>

namespace warpnect::audio {

// One authenticated ingress producer and one native audio callback consumer.
// This replaces the Java destination queue; it is not an additional jitter buffer.
class MicrophoneDestinationQueue {
public:
    static constexpr int kSlots = 8;
    static constexpr int kMaxBlock = 960;
    static constexpr int kCapacity = 1920;
    static constexpr int64_t kFreshNs = 40000000;
    explicit MicrophoneDestinationQueue(int64_t generation) : generation_(generation) {}
    static void erase(int16_t* pcm, int frames) noexcept {
        volatile int16_t* target = pcm;
        for (int i = 0; i < frames; ++i) target[i] = 0;
    }

    void window(int64_t since) noexcept { consumer_since_.store(since, std::memory_order_release); }

    bool offer(int64_t generation, const int16_t* pcm, int frames, int64_t enqueued, int64_t now,
            int64_t first_frame = -1) noexcept {
        if (generation != generation_ || frames <= 0 || frames > kMaxBlock || enqueued <= 0) return false;
        const int reason = reject(enqueued, now);
        if (reason != 0) { complete(producer_, 0, frames, reason); return true; }
        const auto tail = tail_.load(std::memory_order_relaxed);
        const int retained = retained_.load(std::memory_order_acquire);
        if (tail - head_.load(std::memory_order_acquire) >= kSlots || retained + frames > kCapacity) {
            complete(producer_, 0, frames, 5);
            return true;
        }
        auto& slot = slots_[tail % kSlots];
        std::copy_n(pcm, frames, slot.pcm.begin());
        slot.frames = frames;
        slot.offset = 0;
        slot.delivered = 0;
        slot.dropped.fill(0);
        slot.enqueued = enqueued;
        slot.first_frame = first_frame;
        const int count = retained_.fetch_add(frames, std::memory_order_acq_rel) + frames;
        int previous = high_water_.load();
        while (count > previous && !high_water_.compare_exchange_weak(previous, count)) {}
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    // Never allocates, locks, invokes JNI/Binder or waits for ACK/result egress.
    void render(int16_t* output, int frames, int64_t now, int64_t (*clock)() = nullptr) noexcept {
        std::fill_n(output, frames, int16_t{0});
        render_oldest_ = 0;
        render_accepted_ = 0;
        render_first_ = render_last_ = -1;
        int position = 0;
        struct Pending { uint64_t head; int position, count, reason; bool finished; int64_t first_frame; };
        std::array<Pending, kSlots> pending{};
        int pending_count = 0;
        auto cursor = head_.load(std::memory_order_relaxed);
        for (int visited = 0; visited < kSlots && position < frames; ++visited) {
            if (cursor == tail_.load(std::memory_order_acquire)) break;
            auto& slot = slots_[cursor % kSlots];
            const int reason = reject(slot.enqueued, now);
            if (reason != 0) {
                pending[pending_count++] = {cursor++, position, 0, reason, true, -1};
                continue;
            }
            const int count = std::min(frames - position, slot.frames - slot.offset);
            const auto first_frame = slot.first_frame < 0 ? -1 : slot.first_frame + slot.offset;
            std::copy_n(slot.pcm.data() + slot.offset, count, output + position);
            erase(slot.pcm.data() + slot.offset, count);
            slot.offset += count;
            const bool finished = slot.offset == slot.frames;
            pending[pending_count++] = {cursor, position, count, 0, finished, first_frame};
            position += count;
            attempts_.fetch_add(1);
            if (finished) ++cursor;
        }
        // Do not publish delivery or release slots before the final freshness/window check.
        const auto ended = clock == nullptr ? now : clock();
        for (int i = 0; i < pending_count; ++i) {
            const auto& entry = pending[i];
            auto& slot = slots_[entry.head % kSlots];
            const int reason = entry.reason == 0 ? reject(slot.enqueued, ended) : entry.reason;
            if (entry.count != 0) {
                if (reason != 0) {
                    erase(output + entry.position, entry.count);
                    slot.dropped[reason - 2] += entry.count;
                    if (reason == 3) post_copy_stale_.fetch_add(entry.count);
                } else {
                    slot.delivered += entry.count;
                    render_accepted_ += entry.count;
                    if (render_first_ < 0) render_first_ = entry.first_frame;
                    render_last_ = entry.first_frame < 0 ? -1 : entry.first_frame + entry.count;
                    if (render_oldest_ == 0 || slot.enqueued < render_oldest_) render_oldest_ = slot.enqueued;
                    const auto age = ended - slot.enqueued;
                    if (age > max_age_.load()) max_age_.store(age);
                }
            }
            if (entry.finished) {
                if (entry.reason != 0) slot.dropped[entry.reason - 2] += slot.frames - slot.offset;
                consumer_.sequence.fetch_add(1);
                consumer_.values[1].fetch_add(slot.delivered);
                for (int j = 0; j < 3; ++j) consumer_.values[j + 2].fetch_add(slot.dropped[j]);
                consumer_.values[0].fetch_add(1);
                consumer_.sequence.fetch_add(1);
                release(slot, entry.head);
            }
        }
        const int missing = frames - render_accepted_;
        if (consumer_since_.load(std::memory_order_acquire) > 0) active_silence_.fetch_add(missing);
        else idle_silence_.fetch_add(missing);
    }

    bool try_totals(std::array<int64_t, 9>& result) const noexcept {
        std::array<int64_t, 9> consumed{};
        if (!snapshot(producer_, result) || !snapshot(consumer_, consumed)) return false;
        for (int i = 0; i < 9; ++i) result[i] += consumed[i];
        result[6] = attempts_.load();
        return true;
    }
    std::array<int64_t, 9> totals() const noexcept {
        std::array<int64_t, 9> result{};
        try_totals(result);
        return result;
    }
    int retained() const noexcept { return retained_.load(); }
    int high_water() const noexcept { return high_water_.load(); }
    int64_t capacity_dropped() const noexcept { return producer_.values[5].load(); }
    int64_t pending_first() const noexcept { return pending_first_; }
    int64_t pending_enqueued() const noexcept { return pending_enqueued_; }
    int64_t max_age() const noexcept { return max_age_.load(); }
    int64_t active_silence() const noexcept { return active_silence_.load(); }
    int64_t idle_silence() const noexcept { return idle_silence_.load(); }
    int64_t post_copy_stale() const noexcept { return post_copy_stale_.load(); }
    // Callback-owned values used for honest post-copy age diagnostics, not admission credits.
    int64_t render_oldest() const noexcept { return render_oldest_; }
    int render_accepted() const noexcept { return render_accepted_; }
    int64_t render_first() const noexcept { return render_first_; }
    int64_t render_last() const noexcept { return render_last_; }
    // Public nonblocking output retains the original slot until the SDK accepts its remainder.
    // No second PCM queue, age renewal or credit from control/ACK is involved.
    int transfer(int maximum, int64_t now, int (*write)(void*, const int16_t*, int), void* sink,
            int64_t (*clock)() = nullptr) noexcept {
        render_oldest_ = 0; render_accepted_ = 0; render_first_ = render_last_ = -1;
        pending_first_ = -1; pending_enqueued_ = 0;
        for (int visited = 0; visited < kSlots; ++visited) {
            const auto head = head_.load(std::memory_order_relaxed);
            if (head == tail_.load(std::memory_order_acquire)) return 0;
            auto& slot = slots_[head % kSlots];
            const int reason = reject(slot.enqueued, now);
            if (reason != 0) {
                slot.dropped[reason - 2] += slot.frames - slot.offset;
                finish(slot, head);
                continue;
            }
            const int count = std::min(maximum, slot.frames - slot.offset);
            if (count <= 0) return 0;
            attempts_.fetch_add(1);
            pending_first_ = slot.first_frame < 0 ? -1 : slot.first_frame + slot.offset;
            pending_enqueued_ = slot.enqueued;
            const int written = write(sink, slot.pcm.data() + slot.offset, count);
            if (written <= 0) return written;
            if (written > count) return -1;
            render_first_ = slot.first_frame < 0 ? -1 : slot.first_frame + slot.offset;
            render_last_ = render_first_ < 0 ? -1 : render_first_ + written;
            render_oldest_ = slot.enqueued;
            render_accepted_ = written;
            const auto age = (clock == nullptr ? now : clock()) - slot.enqueued;
            if (age > max_age_.load()) max_age_.store(age);
            erase(slot.pcm.data() + slot.offset, written);
            slot.offset += written;
            slot.delivered += written;
            if (slot.offset == slot.frames) finish(slot, head);
            return written;
        }
        return 0;
    }
    void silence(int frames, bool active) noexcept {
        if (active) active_silence_.fetch_add(frames);
        else idle_silence_.fetch_add(frames);
    }
    // Called only after ingress and callback have both stopped.
    void clear() noexcept {
        for (auto& slot : slots_) erase(slot.pcm.data(), kMaxBlock);
        head_.store(tail_.load());
        retained_.store(0);
        consumer_since_.store(0);
    }

private:
    struct Slot {
        std::array<int16_t, kMaxBlock> pcm{};
        int frames = 0, offset = 0, delivered = 0;
        std::array<int, 3> dropped{};
        int64_t enqueued = 0, first_frame = -1;
    };
    int reject(int64_t enqueued, int64_t now) const noexcept {
        if (now < enqueued || now - enqueued > kFreshNs) return 3;
        const int64_t since = consumer_since_.load(std::memory_order_acquire);
        if (since == 0) return 2;
        if (enqueued < since) return 4;
        return 0;
    }
    struct Counters { std::atomic<uint64_t> sequence{0}; std::array<std::atomic<int64_t>, 9> values{}; };
    static void complete(Counters& counters, int accepted, int dropped, int reason) noexcept {
        counters.sequence.fetch_add(1);
        counters.values[1].fetch_add(accepted);
        if (dropped != 0) counters.values[reason].fetch_add(dropped);
        counters.values[0].fetch_add(1);
        counters.sequence.fetch_add(1);
    }
    // Snapshot is bounded even if a counters writer is descheduled. Caller keeps last stable totals.
    static bool snapshot(const Counters& counters, std::array<int64_t, 9>& result) noexcept {
        for (int attempt = 0; attempt < 8; ++attempt) {
            const auto before = counters.sequence.load();
            if ((before & 1U) != 0) continue;
            for (int i = 0; i < 9; ++i) result[i] = counters.values[i].load();
            if (counters.sequence.load() == before) return true;
        }
        return false;
    }
    void release(Slot& slot, uint64_t head) noexcept {
        erase(slot.pcm.data(), kMaxBlock);
        retained_.fetch_sub(slot.frames, std::memory_order_acq_rel);
        head_.store(head + 1, std::memory_order_release);
    }
    void finish(Slot& slot, uint64_t head) noexcept {
        consumer_.sequence.fetch_add(1);
        consumer_.values[1].fetch_add(slot.delivered);
        for (int j = 0; j < 3; ++j) consumer_.values[j + 2].fetch_add(slot.dropped[j]);
        consumer_.values[0].fetch_add(1);
        consumer_.sequence.fetch_add(1);
        release(slot, head);
    }
    const int64_t generation_;
    std::array<Slot, kSlots> slots_{};
    std::atomic<uint64_t> head_{0}, tail_{0};
    std::atomic<int> retained_{0}, high_water_{0};
    std::atomic<int64_t> consumer_since_{0}, max_age_{0}, active_silence_{0}, idle_silence_{0};
    Counters producer_, consumer_;
    std::atomic<int64_t> attempts_{0}, post_copy_stale_{0};
    int64_t render_oldest_ = 0;
    int render_accepted_ = 0;
    int64_t render_first_ = -1, render_last_ = -1;
    int64_t pending_first_ = -1, pending_enqueued_ = 0;
};
}
