#include "native_bridge.h"

namespace warpnect::scl {
namespace bridge {

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

void native_core_shutdown() noexcept {}

} // namespace bridge
} // namespace warpnect::scl
