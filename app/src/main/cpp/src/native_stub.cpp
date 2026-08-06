#include "native_bridge.h"
#include "telemetry.h"

namespace warpnect::scl {

std::uint64_t media_pipeline_time_us(const FrameTelemetry& telemetry) noexcept {
    return telemetry.capture_time_us +
           telemetry.encode_time_us +
           telemetry.network_time_us +
           telemetry.decode_time_us +
           telemetry.render_time_us;
}

}  // namespace warpnect::scl

namespace warpnect::scl::bridge {

NativeCoreInfo native_core_info() noexcept {
    return NativeCoreInfo{
        .protocol_name = "State Coherence Layer",
        .protocol_version = kSclProtocolVersion,
        .protocol_abi_version = kNativeBridgeAbiVersion,
    };
}

NativeCoreState native_core_state() noexcept {
    return NativeCoreState::Initialized;
}

void native_core_shutdown() noexcept {
}

}  // namespace warpnect::scl::bridge
