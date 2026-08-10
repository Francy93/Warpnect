#ifndef WARPNECT_SCL_UDP_SOCKET_H_
#define WARPNECT_SCL_UDP_SOCKET_H_

#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>

#include "udp_endpoint.h"
#include "udp_result.h"

namespace warpnect::scl {

class UdpSocket final {
  public:
    UdpSocket() noexcept = default;
    ~UdpSocket() noexcept;

    UdpSocket(const UdpSocket&) = delete;
    UdpSocket& operator=(const UdpSocket&) = delete;

    UdpSocket(UdpSocket&& other) noexcept;
    UdpSocket& operator=(UdpSocket&& other) noexcept;

    [[nodiscard]] UdpStatus open(IpVersion version) noexcept;
    [[nodiscard]] UdpStatus bind(const UdpEndpoint& endpoint) noexcept;

    [[nodiscard]] UdpSendResult send_to(std::span<const std::byte> datagram,
                                        const UdpEndpoint& destination) noexcept;

    [[nodiscard]] UdpReceiveResult receive_from(std::span<std::byte> destination) noexcept;

    [[nodiscard]] UdpReadinessResult wait_readable(std::uint64_t timeout_us) noexcept;

    [[nodiscard]] UdpEndpointResult local_endpoint() const noexcept;
    [[nodiscard]] bool is_open() const noexcept;

    void close() noexcept;

  private:
    inline static constexpr std::uintptr_t kInvalidSocketHandle =
        std::numeric_limits<std::uintptr_t>::max();

    std::uintptr_t socket_handle_ = kInvalidSocketHandle;
    IpVersion ip_version_ = IpVersion::V4;
    bool bound_ = false;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_UDP_SOCKET_H_
