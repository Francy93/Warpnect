#pragma once

#include <cstdint>

namespace warpnect::scl {

// Capture the critical-section end before unlock; reporting may be descheduled afterward.
struct ProtectionTimingProbe {
    std::int64_t begin_ns, cpu_begin_ns;
    std::int64_t acquired_ns = 0, cpu_acquired_ns = 0;
    std::int64_t released_ns = 0, cpu_released_ns = 0;

    void acquired(std::int64_t wall_ns, std::int64_t cpu_ns) noexcept {
        acquired_ns = wall_ns;
        cpu_acquired_ns = cpu_ns;
    }
    void releasing(std::int64_t wall_ns, std::int64_t cpu_ns) noexcept {
        released_ns = wall_ns;
        cpu_released_ns = cpu_ns;
    }
    [[nodiscard]] std::int64_t wait_ns() const noexcept { return acquired_ns - begin_ns; }
    [[nodiscard]] std::int64_t work_ns() const noexcept { return released_ns - acquired_ns; }
    [[nodiscard]] std::int64_t cpu_wait_ns() const noexcept { return cpu_acquired_ns - cpu_begin_ns; }
    [[nodiscard]] std::int64_t cpu_work_ns() const noexcept { return cpu_released_ns - cpu_acquired_ns; }
};

}  // namespace warpnect::scl
