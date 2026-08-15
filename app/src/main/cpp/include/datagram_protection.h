#ifndef WARPNECT_SCL_DATAGRAM_PROTECTION_H_
#define WARPNECT_SCL_DATAGRAM_PROTECTION_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "udp_endpoint.h"

namespace warpnect::scl {

enum class DatagramProtectionError : std::uint8_t {
    None = 0,
    Rejected,
    Failed,
    DatagramTooLarge,
};

struct DatagramProtectionResult final {
    DatagramProtectionError error = DatagramProtectionError::None;
    std::size_t bytes_written = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == DatagramProtectionError::None;
    }
};

/** Native data-path seam; implementations keep key material opaque to media transports. */
class DatagramProtector {
  public:
    virtual ~DatagramProtector() = default;

    [[nodiscard]] virtual std::size_t secure_datagram_budget() const noexcept = 0;
    [[nodiscard]] virtual std::size_t inner_datagram_budget() const noexcept = 0;
    [[nodiscard]] virtual DatagramProtectionResult protect(
        std::span<const std::byte> inner_datagram,
        std::span<std::byte> output) noexcept = 0;
    [[nodiscard]] virtual DatagramProtectionResult unprotect(
        const UdpEndpoint& source,
        std::span<const std::byte> secure_datagram,
        std::span<std::byte> output,
        std::uint64_t now_us) noexcept = 0;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_DATAGRAM_PROTECTION_H_
