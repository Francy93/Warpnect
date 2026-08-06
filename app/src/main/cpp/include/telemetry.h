#ifndef WARPNECT_SCL_TELEMETRY_H_
#define WARPNECT_SCL_TELEMETRY_H_

#include <cstdint>

namespace warpnect::scl {

struct StageTimingUs final {
    std::uint64_t started_us;
    std::uint64_t completed_us;
};

struct FrameTelemetry final {
    std::uint64_t capture_time_us;
    std::uint64_t encode_time_us;
    std::uint64_t network_time_us;
    std::uint64_t decode_time_us;
    std::uint64_t render_time_us;
    std::uint64_t input_round_trip_time_us;
};

[[nodiscard]] std::uint64_t media_pipeline_time_us(const FrameTelemetry& telemetry) noexcept;

}  // namespace warpnect::scl

#endif  // WARPNECT_SCL_TELEMETRY_H_
