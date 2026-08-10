#ifndef WARPNECT_SCL_UDP_RESULT_H_
#define WARPNECT_SCL_UDP_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <string_view>

#include "datagram_limits.h"
#include "udp_endpoint.h"

namespace warpnect::scl {

enum class UdpError : std::uint8_t {
    None = 0,
    NotOpen,
    AlreadyOpen,
    NotBound,
    AlreadyBound,
    InvalidAddress,
    InvalidPort,
    AddressFamilyMismatch,
    AddressFamilyUnsupported,
    SocketCreationFailed,
    SocketOptionConfigurationFailed,
    NonBlockingConfigurationFailed,
    BindFailed,
    WouldBlock,
    DatagramTooLarge,
    DatagramTruncated,
    SendFailed,
    ReceiveFailed,
    LocalEndpointQueryFailed,
};

[[nodiscard]] constexpr std::string_view udp_error_name(UdpError error) noexcept {
    switch (error) {
    case UdpError::None:
        return "None";
    case UdpError::NotOpen:
        return "NotOpen";
    case UdpError::AlreadyOpen:
        return "AlreadyOpen";
    case UdpError::NotBound:
        return "NotBound";
    case UdpError::AlreadyBound:
        return "AlreadyBound";
    case UdpError::InvalidAddress:
        return "InvalidAddress";
    case UdpError::InvalidPort:
        return "InvalidPort";
    case UdpError::AddressFamilyMismatch:
        return "AddressFamilyMismatch";
    case UdpError::AddressFamilyUnsupported:
        return "AddressFamilyUnsupported";
    case UdpError::SocketCreationFailed:
        return "SocketCreationFailed";
    case UdpError::SocketOptionConfigurationFailed:
        return "SocketOptionConfigurationFailed";
    case UdpError::NonBlockingConfigurationFailed:
        return "NonBlockingConfigurationFailed";
    case UdpError::BindFailed:
        return "BindFailed";
    case UdpError::WouldBlock:
        return "WouldBlock";
    case UdpError::DatagramTooLarge:
        return "DatagramTooLarge";
    case UdpError::DatagramTruncated:
        return "DatagramTruncated";
    case UdpError::SendFailed:
        return "SendFailed";
    case UdpError::ReceiveFailed:
        return "ReceiveFailed";
    case UdpError::LocalEndpointQueryFailed:
        return "LocalEndpointQueryFailed";
    }

    return "UnknownUdpError";
}

struct [[nodiscard]] UdpStatus final {
    UdpError error = UdpError::None;
    int native_error = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == UdpError::None;
    }
};

struct [[nodiscard]] UdpSendResult final {
    UdpStatus status{};
    std::size_t bytes_sent = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return status.ok();
    }
};

struct [[nodiscard]] UdpReceiveResult final {
    UdpStatus status{};
    std::size_t bytes_received = 0;
    UdpEndpoint source{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return status.ok();
    }
};

struct [[nodiscard]] UdpReadinessResult final {
    UdpStatus status{};
    bool readable = false;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return status.ok();
    }
};

struct [[nodiscard]] UdpEndpointResult final {
    UdpStatus status{};
    UdpEndpoint endpoint{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return status.ok();
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_UDP_RESULT_H_
