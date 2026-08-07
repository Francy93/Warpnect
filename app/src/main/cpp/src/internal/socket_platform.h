#ifndef WARPNECT_SCL_INTERNAL_SOCKET_PLATFORM_H_
#define WARPNECT_SCL_INTERNAL_SOCKET_PLATFORM_H_

#include <cstdint>
#include <limits>
#include <span>
#include <string_view>

#include "udp_endpoint.h"
#include "udp_result.h"

namespace warpnect::scl::internal {

using NativeSocketHandle = std::uintptr_t;

inline constexpr NativeSocketHandle kInvalidNativeSocketHandle =
    std::numeric_limits<NativeSocketHandle>::max();

struct [[nodiscard]] NativeSocketOpenResult final {
    UdpStatus status{};
    NativeSocketHandle socket = kInvalidNativeSocketHandle;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return status.ok();
    }
};

[[nodiscard]] NativeSocketOpenResult open_udp_socket(IpVersion version) noexcept;
void close_socket(NativeSocketHandle socket) noexcept;

[[nodiscard]] UdpStatus bind_udp_socket(NativeSocketHandle socket,
                                        const UdpEndpoint& endpoint) noexcept;

[[nodiscard]] UdpSendResult send_udp_datagram(NativeSocketHandle socket,
                                              std::span<const std::byte> datagram,
                                              const UdpEndpoint& destination) noexcept;

[[nodiscard]] UdpReceiveResult receive_udp_datagram(NativeSocketHandle socket,
                                                    std::span<std::byte> destination) noexcept;

[[nodiscard]] UdpEndpointResult query_local_endpoint(NativeSocketHandle socket) noexcept;

[[nodiscard]] IpAddressParseResult
parse_numeric_ip_address_platform(std::string_view text) noexcept;

} // namespace warpnect::scl::internal

#endif // WARPNECT_SCL_INTERNAL_SOCKET_PLATFORM_H_
