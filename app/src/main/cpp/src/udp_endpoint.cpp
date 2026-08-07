#include "udp_endpoint.h"

#include "internal/socket_platform.h"

namespace warpnect::scl {

IpAddressParseResult parse_numeric_ip_address(std::string_view text) noexcept {
    return internal::parse_numeric_ip_address_platform(text);
}

} // namespace warpnect::scl
