#ifndef WARPNECT_SCL_UDP_ENDPOINT_H_
#define WARPNECT_SCL_UDP_ENDPOINT_H_

#include <array>
#include <cstdint>
#include <string_view>

namespace warpnect::scl {

enum class IpVersion : std::uint8_t {
    V4 = 4,
    V6 = 6,
};

[[nodiscard]] constexpr bool is_supported_ip_version(IpVersion version) noexcept {
    return version == IpVersion::V4 || version == IpVersion::V6;
}

struct IpAddress final {
    IpVersion version = IpVersion::V4;
    std::array<std::uint8_t, 16> bytes{};

    [[nodiscard]] static constexpr IpAddress any_v4() noexcept {
        return IpAddress{.version = IpVersion::V4, .bytes = {}};
    }

    [[nodiscard]] static constexpr IpAddress loopback_v4() noexcept {
        IpAddress address = any_v4();
        address.bytes[0] = 127;
        address.bytes[3] = 1;
        return address;
    }

    [[nodiscard]] static constexpr IpAddress any_v6() noexcept {
        return IpAddress{.version = IpVersion::V6, .bytes = {}};
    }

    [[nodiscard]] static constexpr IpAddress loopback_v6() noexcept {
        IpAddress address = any_v6();
        address.bytes[15] = 1;
        return address;
    }

    [[nodiscard]] constexpr bool is_unspecified() const noexcept {
        if (version == IpVersion::V4) {
            return bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0;
        }

        if (version == IpVersion::V6) {
            for (std::uint8_t value : bytes) {
                if (value != 0) {
                    return false;
                }
            }
            return true;
        }

        return true;
    }

    constexpr bool operator==(const IpAddress&) const = default;
};

enum class IpAddressParseError : std::uint8_t {
    None = 0,
    InvalidAddress,
    AddressFamilyUnsupported,
};

[[nodiscard]] constexpr std::string_view
ip_address_parse_error_name(IpAddressParseError error) noexcept {
    switch (error) {
    case IpAddressParseError::None:
        return "None";
    case IpAddressParseError::InvalidAddress:
        return "InvalidAddress";
    case IpAddressParseError::AddressFamilyUnsupported:
        return "AddressFamilyUnsupported";
    }

    return "UnknownIpAddressParseError";
}

struct [[nodiscard]] IpAddressParseResult final {
    IpAddressParseError error = IpAddressParseError::None;
    IpAddress address{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == IpAddressParseError::None;
    }
};

[[nodiscard]] IpAddressParseResult parse_numeric_ip_address(std::string_view text) noexcept;

struct UdpEndpoint final {
    IpAddress address{};
    std::uint16_t port = 0;

    [[nodiscard]] static constexpr UdpEndpoint any_v4(std::uint16_t port) noexcept {
        return UdpEndpoint{.address = IpAddress::any_v4(), .port = port};
    }

    [[nodiscard]] static constexpr UdpEndpoint loopback_v4(std::uint16_t port) noexcept {
        return UdpEndpoint{.address = IpAddress::loopback_v4(), .port = port};
    }

    [[nodiscard]] static constexpr UdpEndpoint any_v6(std::uint16_t port) noexcept {
        return UdpEndpoint{.address = IpAddress::any_v6(), .port = port};
    }

    [[nodiscard]] static constexpr UdpEndpoint loopback_v6(std::uint16_t port) noexcept {
        return UdpEndpoint{.address = IpAddress::loopback_v6(), .port = port};
    }

    constexpr bool operator==(const UdpEndpoint&) const = default;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_UDP_ENDPOINT_H_
