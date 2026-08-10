#include "internal/socket_platform.h"

#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <array>
#include <limits>

#include <WS2tcpip.h>
#include <WinSock2.h>

namespace warpnect::scl::internal {
namespace {

[[nodiscard]] constexpr UdpStatus status(UdpError error, int native_error = 0) noexcept {
    return UdpStatus{.error = error, .native_error = native_error};
}

[[nodiscard]] constexpr int address_family_for(IpVersion version) noexcept {
    switch (version) {
    case IpVersion::V4:
        return AF_INET;
    case IpVersion::V6:
        return AF_INET6;
    }

    return AF_UNSPEC;
}

[[nodiscard]] SOCKET native_socket(NativeSocketHandle socket) noexcept {
    return static_cast<SOCKET>(socket);
}

struct WinsockRuntime final {
    UdpStatus startup_status{};

    WinsockRuntime() noexcept {
        WSADATA data{};
        const int result = ::WSAStartup(MAKEWORD(2, 2), &data);
        startup_status =
            result == 0 ? status(UdpError::None) : status(UdpError::SocketCreationFailed, result);
    }

    ~WinsockRuntime() noexcept {
        if (startup_status.ok()) {
            ::WSACleanup();
        }
    }

    WinsockRuntime(const WinsockRuntime&) = delete;
    WinsockRuntime& operator=(const WinsockRuntime&) = delete;
};

[[nodiscard]] UdpStatus ensure_winsock() noexcept {
    static WinsockRuntime runtime;
    return runtime.startup_status;
}

[[nodiscard]] bool copy_to_null_terminated(std::string_view text,
                                           std::array<char, 46>& output) noexcept {
    if (text.empty() || text.size() >= output.size()) {
        return false;
    }

    for (std::size_t i = 0; i < text.size(); ++i) {
        output[i] = text[i];
    }
    output[text.size()] = '\0';
    return true;
}

[[nodiscard]] UdpEndpointResult endpoint_from_sockaddr(const sockaddr_storage& storage,
                                                       int length) noexcept {
    if (storage.ss_family == AF_INET && length >= static_cast<int>(sizeof(sockaddr_in))) {
        const auto* address = reinterpret_cast<const sockaddr_in*>(&storage);
        UdpEndpoint endpoint = UdpEndpoint::any_v4(ntohs(address->sin_port));
        const auto* source = reinterpret_cast<const std::uint8_t*>(&address->sin_addr.S_un.S_addr);
        for (std::size_t i = 0; i < 4; ++i) {
            endpoint.address.bytes[i] = source[i];
        }
        return UdpEndpointResult{.status = status(UdpError::None), .endpoint = endpoint};
    }

    if (storage.ss_family == AF_INET6 && length >= static_cast<int>(sizeof(sockaddr_in6))) {
        const auto* address = reinterpret_cast<const sockaddr_in6*>(&storage);
        UdpEndpoint endpoint = UdpEndpoint::any_v6(ntohs(address->sin6_port));
        for (std::size_t i = 0; i < 16; ++i) {
            endpoint.address.bytes[i] = address->sin6_addr.u.Byte[i];
        }
        return UdpEndpointResult{.status = status(UdpError::None), .endpoint = endpoint};
    }

    return UdpEndpointResult{.status = status(UdpError::AddressFamilyUnsupported), .endpoint = {}};
}

[[nodiscard]] bool endpoint_to_sockaddr(const UdpEndpoint& endpoint, sockaddr_storage& storage,
                                        int& length) noexcept {
    storage = {};
    length = 0;

    if (endpoint.address.version == IpVersion::V4) {
        auto* address = reinterpret_cast<sockaddr_in*>(&storage);
        address->sin_family = AF_INET;
        address->sin_port = htons(endpoint.port);
        auto* destination = reinterpret_cast<std::uint8_t*>(&address->sin_addr.S_un.S_addr);
        for (std::size_t i = 0; i < 4; ++i) {
            destination[i] = endpoint.address.bytes[i];
        }
        length = sizeof(sockaddr_in);
        return true;
    }

    if (endpoint.address.version == IpVersion::V6) {
        auto* address = reinterpret_cast<sockaddr_in6*>(&storage);
        address->sin6_family = AF_INET6;
        address->sin6_port = htons(endpoint.port);
        for (std::size_t i = 0; i < 16; ++i) {
            address->sin6_addr.u.Byte[i] = endpoint.address.bytes[i];
        }
        length = sizeof(sockaddr_in6);
        return true;
    }

    return false;
}

} // namespace

NativeSocketOpenResult open_udp_socket(IpVersion version) noexcept {
    const UdpStatus winsock_status = ensure_winsock();
    if (!winsock_status.ok()) {
        return NativeSocketOpenResult{.status = winsock_status,
                                      .socket = kInvalidNativeSocketHandle};
    }

    const int family = address_family_for(version);
    if (family == AF_UNSPEC) {
        return NativeSocketOpenResult{
            .status = status(UdpError::AddressFamilyUnsupported),
            .socket = kInvalidNativeSocketHandle,
        };
    }

    const SOCKET socket = ::socket(family, SOCK_DGRAM, IPPROTO_UDP);
    if (socket == INVALID_SOCKET) {
        return NativeSocketOpenResult{
            .status = status(UdpError::SocketCreationFailed, ::WSAGetLastError()),
            .socket = kInvalidNativeSocketHandle,
        };
    }

    if (version == IpVersion::V6) {
        DWORD ipv6_only = 1;
        if (::setsockopt(socket, IPPROTO_IPV6, IPV6_V6ONLY,
                         reinterpret_cast<const char*>(&ipv6_only), sizeof(ipv6_only)) != 0) {
            const int native_error = ::WSAGetLastError();
            ::closesocket(socket);
            return NativeSocketOpenResult{
                .status = status(UdpError::SocketOptionConfigurationFailed, native_error),
                .socket = kInvalidNativeSocketHandle,
            };
        }
    }

    u_long non_blocking = 1;
    if (::ioctlsocket(socket, FIONBIO, &non_blocking) != 0) {
        const int native_error = ::WSAGetLastError();
        ::closesocket(socket);
        return NativeSocketOpenResult{
            .status = status(UdpError::NonBlockingConfigurationFailed, native_error),
            .socket = kInvalidNativeSocketHandle,
        };
    }

    return NativeSocketOpenResult{
        .status = status(UdpError::None),
        .socket = static_cast<NativeSocketHandle>(socket),
    };
}

void close_socket(NativeSocketHandle socket) noexcept {
    if (socket != kInvalidNativeSocketHandle) {
        ::closesocket(native_socket(socket));
    }
}

UdpStatus bind_udp_socket(NativeSocketHandle socket, const UdpEndpoint& endpoint) noexcept {
    sockaddr_storage storage{};
    int length = 0;
    if (!endpoint_to_sockaddr(endpoint, storage, length)) {
        return status(UdpError::AddressFamilyUnsupported);
    }

    if (::bind(native_socket(socket), reinterpret_cast<const sockaddr*>(&storage), length) != 0) {
        return status(UdpError::BindFailed, ::WSAGetLastError());
    }

    return status(UdpError::None);
}

UdpSendResult send_udp_datagram(NativeSocketHandle socket, std::span<const std::byte> datagram,
                                const UdpEndpoint& destination) noexcept {
    sockaddr_storage storage{};
    int length = 0;
    if (!endpoint_to_sockaddr(destination, storage, length)) {
        return UdpSendResult{.status = status(UdpError::AddressFamilyUnsupported), .bytes_sent = 0};
    }

    const char empty_byte = 0;
    const auto* data =
        datagram.empty() ? &empty_byte : reinterpret_cast<const char*>(datagram.data());

    const int sent = ::sendto(native_socket(socket), data, static_cast<int>(datagram.size()), 0,
                              reinterpret_cast<const sockaddr*>(&storage), length);
    if (sent == SOCKET_ERROR) {
        const int native_error = ::WSAGetLastError();
        if (native_error == WSAEWOULDBLOCK) {
            return UdpSendResult{.status = status(UdpError::WouldBlock, native_error),
                                 .bytes_sent = 0};
        }

        return UdpSendResult{.status = status(UdpError::SendFailed, native_error), .bytes_sent = 0};
    }

    const auto bytes_sent = static_cast<std::size_t>(sent);
    if (bytes_sent != datagram.size()) {
        return UdpSendResult{.status = status(UdpError::SendFailed), .bytes_sent = bytes_sent};
    }

    return UdpSendResult{.status = status(UdpError::None), .bytes_sent = bytes_sent};
}

UdpReceiveResult receive_udp_datagram(NativeSocketHandle socket,
                                      std::span<std::byte> destination) noexcept {
    if (destination.size() > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        return UdpReceiveResult{
            .status = status(UdpError::ReceiveFailed),
            .bytes_received = 0,
            .source = {},
        };
    }

    sockaddr_storage source_storage{};
    int source_length = sizeof(source_storage);
    char empty_byte = 0;
    auto* data = destination.empty() ? &empty_byte : reinterpret_cast<char*>(destination.data());

    const int received =
        ::recvfrom(native_socket(socket), data, static_cast<int>(destination.size()), 0,
                   reinterpret_cast<sockaddr*>(&source_storage), &source_length);

    if (received == SOCKET_ERROR) {
        const int native_error = ::WSAGetLastError();
        if (native_error == WSAEWOULDBLOCK) {
            return UdpReceiveResult{
                .status = status(UdpError::WouldBlock, native_error),
                .bytes_received = 0,
                .source = {},
            };
        }

        if (native_error == WSAEMSGSIZE) {
            const UdpEndpointResult source = endpoint_from_sockaddr(source_storage, source_length);
            return UdpReceiveResult{
                .status = status(UdpError::DatagramTruncated, native_error),
                .bytes_received = 0,
                .source = source.ok() ? source.endpoint : UdpEndpoint{},
            };
        }

        return UdpReceiveResult{
            .status = status(UdpError::ReceiveFailed, native_error),
            .bytes_received = 0,
            .source = {},
        };
    }

    const UdpEndpointResult source = endpoint_from_sockaddr(source_storage, source_length);
    if (!source.ok()) {
        return UdpReceiveResult{
            .status = status(UdpError::ReceiveFailed, source.status.native_error),
            .bytes_received = 0,
            .source = {},
        };
    }

    return UdpReceiveResult{
        .status = status(UdpError::None),
        .bytes_received = static_cast<std::size_t>(received),
        .source = source.endpoint,
    };
}

UdpReadinessResult wait_udp_socket_readable(NativeSocketHandle socket,
                                            std::uint64_t timeout_us) noexcept {
    fd_set read_set;
    FD_ZERO(&read_set);
    FD_SET(native_socket(socket), &read_set);

    timeval timeout{
        .tv_sec = static_cast<long>(timeout_us / 1'000'000ULL),
        .tv_usec = static_cast<long>(timeout_us % 1'000'000ULL),
    };

    const int result = ::select(0, &read_set, nullptr, nullptr, &timeout);
    if (result == SOCKET_ERROR) {
        return UdpReadinessResult{
            .status = status(UdpError::ReceiveFailed, ::WSAGetLastError()),
            .readable = false,
        };
    }

    return UdpReadinessResult{
        .status = status(UdpError::None),
        .readable = result > 0 && FD_ISSET(native_socket(socket), &read_set),
    };
}

UdpEndpointResult query_local_endpoint(NativeSocketHandle socket) noexcept {
    sockaddr_storage storage{};
    int length = sizeof(storage);
    if (::getsockname(native_socket(socket), reinterpret_cast<sockaddr*>(&storage), &length) != 0) {
        return UdpEndpointResult{
            .status = status(UdpError::LocalEndpointQueryFailed, ::WSAGetLastError()),
            .endpoint = {},
        };
    }

    UdpEndpointResult endpoint = endpoint_from_sockaddr(storage, length);
    if (!endpoint.ok()) {
        return UdpEndpointResult{.status = status(UdpError::LocalEndpointQueryFailed),
                                 .endpoint = {}};
    }

    return endpoint;
}

IpAddressParseResult parse_numeric_ip_address_platform(std::string_view text) noexcept {
    std::array<char, 46> text_buffer{};
    if (!copy_to_null_terminated(text, text_buffer)) {
        return IpAddressParseResult{.error = IpAddressParseError::InvalidAddress, .address = {}};
    }

    IN_ADDR ipv4{};
    if (::InetPtonA(AF_INET, text_buffer.data(), &ipv4) == 1) {
        IpAddress address = IpAddress::any_v4();
        const auto* source = reinterpret_cast<const std::uint8_t*>(&ipv4.S_un.S_addr);
        for (std::size_t i = 0; i < 4; ++i) {
            address.bytes[i] = source[i];
        }
        return IpAddressParseResult{.error = IpAddressParseError::None, .address = address};
    }

    IN6_ADDR ipv6{};
    if (::InetPtonA(AF_INET6, text_buffer.data(), &ipv6) == 1) {
        IpAddress address = IpAddress::any_v6();
        for (std::size_t i = 0; i < 16; ++i) {
            address.bytes[i] = ipv6.u.Byte[i];
        }
        return IpAddressParseResult{.error = IpAddressParseError::None, .address = address};
    }

    return IpAddressParseResult{.error = IpAddressParseError::InvalidAddress, .address = {}};
}

} // namespace warpnect::scl::internal
