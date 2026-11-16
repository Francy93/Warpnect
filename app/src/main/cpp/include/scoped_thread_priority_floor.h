#pragma once

namespace warpnect::scl {
// Scheduling only: the caller's original priority is restored after its lock scope.
class ScopedThreadPriorityFloor final {
public:
    using Read = bool (*)(int&) noexcept;
    using Set = int (*)(int) noexcept;
    using Failure = void (*)(int, int) noexcept;
    ScopedThreadPriorityFloor(bool enabled, int floor, Read read, Set set, Failure failure) noexcept
        : set_(set), failure_(failure) {
        if (!enabled || !read(original_) || original_ <= floor) return;
        const int result = set_(floor);
        changed_ = result == 0;
        if (result != 0 && failure_ != nullptr) failure_(floor, result);
    }
    ~ScopedThreadPriorityFloor() {
        if (!changed_) return;
        const int result = set_(original_);
        if (result != 0 && failure_ != nullptr) failure_(original_, result);
    }
    ScopedThreadPriorityFloor(const ScopedThreadPriorityFloor&) = delete;
    ScopedThreadPriorityFloor& operator=(const ScopedThreadPriorityFloor&) = delete;
private:
    Set set_;
    Failure failure_;
    int original_ = 0;
    bool changed_ = false;
};
}
