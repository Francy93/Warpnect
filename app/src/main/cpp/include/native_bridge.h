#ifndef WARPNECT_SCL_NATIVE_BRIDGE_H_
#define WARPNECT_SCL_NATIVE_BRIDGE_H_

#include <cstdint>

#include "protocol.h"

namespace warpnect::scl::bridge {

inline constexpr std::uint32_t kNativeBridgeAbiVersion = 1;

enum class NativeCoreState : std::uint8_t {
    Uninitialized,
    Initialized,
    Shutdown,
};

struct NativeCoreInfo final {
    const char* protocol_name;
    std::uint16_t protocol_version;
    std::uint32_t protocol_abi_version;
};

[[nodiscard]] NativeCoreInfo native_core_info() noexcept;
[[nodiscard]] NativeCoreState native_core_state() noexcept;
void native_core_shutdown() noexcept;

}  // namespace warpnect::scl::bridge

#endif  // WARPNECT_SCL_NATIVE_BRIDGE_H_
