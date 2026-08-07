#include "monotonic_time.h"

#include <chrono>

namespace warpnect::scl {

MonotonicTimestampUs monotonic_time_now_us() noexcept {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const auto value = std::chrono::duration_cast<std::chrono::microseconds>(now).count();
    return MonotonicTimestampUs{.value = value < 0 ? 0U : static_cast<std::uint64_t>(value)};
}

} // namespace warpnect::scl
