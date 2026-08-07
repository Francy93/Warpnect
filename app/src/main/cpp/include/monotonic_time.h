#ifndef WARPNECT_SCL_MONOTONIC_TIME_H_
#define WARPNECT_SCL_MONOTONIC_TIME_H_

#include <cstdint>

namespace warpnect::scl {

struct MonotonicTimestampUs final {
    std::uint64_t value = 0;

    constexpr bool operator==(const MonotonicTimestampUs&) const = default;
};

[[nodiscard]] MonotonicTimestampUs monotonic_time_now_us() noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_MONOTONIC_TIME_H_
