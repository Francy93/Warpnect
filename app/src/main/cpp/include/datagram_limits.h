#ifndef WARPNECT_SCL_DATAGRAM_LIMITS_H_
#define WARPNECT_SCL_DATAGRAM_LIMITS_H_

#include <cstddef>

namespace warpnect::scl {

inline constexpr std::size_t kUdpMaxIpv4PayloadSize = 65507;
inline constexpr std::size_t kUdpMaxDatagramPayloadSize = kUdpMaxIpv4PayloadSize;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_DATAGRAM_LIMITS_H_
