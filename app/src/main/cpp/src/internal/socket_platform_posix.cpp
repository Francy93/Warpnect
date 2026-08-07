#include "internal/socket_platform.h"

#include <array>
#include <cerrno>
#include <cstring>
#include <limits>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

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
                                                       socklen_t length) noexcept {
    if (storage.ss_family == AF_INET && length >= static_cast<socklen_t>(sizeof(sockaddr_in))) {
        const auto* address = reinterpret_cast<const sockaddr_in*>(&storage);
        UdpEndpoint endpoint = UdpEndpoint::any_v4(ntohs(address->sin_port));
        std::memcpy(endpoint.address.bytes.data(), &address->sin_addr.s_addr, 4);
        return UdpEndpointResult{.status = status(UdpError::None), .endpoint = endpoint};
    }

    if (storage.ss_family == AF_INET6 && length >= static_cast<socklen_t>(sizeof(sockaddr_in6))) {
        const auto* address = reinterpret_cast<const sockaddr_in6*>(&storage);
        UdpEndpoint endpoint = UdpEndpoint::any_v6(ntohs(address->sin6_port));
        std::memcpy(endpoint.address.bytes.data(), address->sin6_addr.s6_addr, 16);
        return UdpEndpointResult{.status = status(UdpError::None), .endpoint = endpoint};
    }

    return UdpEndpointResult{.status = status(UdpError::AddressFamilyUnsupported), .endpoint = {}};
}

[[nodiscard]] bool endpoint_to_sockaddr(const UdpEndpoint& endpoint, sockaddr_storage& storage,
                                        socklen_t& length) noexcept {
    std::memset(&storage, 0, sizeof(storage));
    length = 0;

    if (endpoint.address.version == IpVersion::V4) {
        auto* address = reinterpret_cast<sockaddr_in*>(&storage);
        address->sin_family = AF_INET;
        address->sin_port = htons(endpoint.port);
        std::memcpy(&address->sin_addr.s_addr, endpoint.address.bytes.data(), 4);
        length = sizeof(sockaddr_in);
        return true;
    }

    if (endpoint.address.version == IpVersion::V6) {
        auto* address = reinterpret_cast<sockaddr_in6*>(&storage);
        address->sin6_family = AF_INET6;
        address->sin6_port = htons(endpoint.port);
        std::memcpy(address->sin6_addr.s6_addr, endpoint.address.bytes.data(), 16);
        length = sizeof(sockaddr_in6);
        return true;
    }

    return false;
}

[[nodiscard]] constexpr bool is_would_block(int error) noexcept {
    return error == EAGAIN || error == EWOULDBLOCK;
}

} // namespace

NativeSocketOpenResult open_udp_socket(IpVersion version) noexcept {
    const int family = address_family_for(version);
    if (family == AF_UNSPEC) {
        return NativeSocketOpenResult{
            .status = status(UdpError::AddressFamilyUnsupported),
            .socket = kInvalidNativeSocketHandle,
        };
    }

    const int socket = ::socket(family, SOCK_DGRAM, IPPROTO_UDP);
    if (socket < 0) {
        return NativeSocketOpenResult{
            .status = status(UdpError::SocketCreationFailed, errno),
            .socket = kInvalidNativeSocketHandle,
        };
    }

    if (version == IpVersion::V6) {
        constexpr int ipv6_only = 1;
        if (::setsockopt(socket, IPPROTO_IPV6, IPV6_V6ONLY, &ipv6_only, sizeof(ipv6_only)) != 0) {
            const int native_error = errno;
            ::close(socket);
            return NativeSocketOpenResult{
                .status = status(UdpError::SocketOptionConfigurationFailed, native_error),
                .socket = kInvalidNativeSocketHandle,
            };
        }
    }

    const int existing_flags = ::fcntl(socket, F_GETFL, 0);
    if (existing_flags < 0) {
        const int native_error = errno;
        ::close(socket);
        return NativeSocketOpenResult{
            .status = status(UdpError::NonBlockingConfigurationFailed, native_error),
            .socket = kInvalidNativeSocketHandle,
        };
    }

    if (::fcntl(socket, F_SETFL, existing_flags | O_NONBLOCK) != 0) {
        const int native_error = errno;
        ::close(socket);
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
        ::close(static_cast<int>(socket));
    }
}

UdpStatus bind_udp_socket(NativeSocketHandle socket, const UdpEndpoint& endpoint) noexcept {
    sockaddr_storage storage{};
    socklen_t length = 0;
    if (!endpoint_to_sockaddr(endpoint, storage, length)) {
        return status(UdpError::AddressFamilyUnsupported);
    }

    if (::bind(static_cast<int>(socket), reinterpret_cast<const sockaddr*>(&storage), length) !=
        0) {
        return status(UdpError::BindFailed, errno);
    }

    return status(UdpError::None);
}

UdpSendResult send_udp_datagram(NativeSocketHandle socket, std::span<const std::byte> datagram,
                                const UdpEndpoint& destination) noexcept {
    sockaddr_storage storage{};
    socklen_t length = 0;
    if (!endpoint_to_sockaddr(destination, storage, length)) {
        return UdpSendResult{.status = status(UdpError::AddressFamilyUnsupported), .bytes_sent = 0};
    }

    const std::byte empty_byte{};
    const void* data = datagram.empty() ? &empty_byte : datagram.data();
    const auto sent = ::sendto(static_cast<int>(socket), data, datagram.size(), 0,
                               reinterpret_cast<const sockaddr*>(&storage), length);

    if (sent < 0) {
        const int native_error = errno;
        if (is_would_block(native_error)) {
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
    sockaddr_storage source_storage{};
    std::byte empty_byte{};
    iovec iov{
        .iov_base = destination.empty() ? static_cast<void*>(&empty_byte)
                                        : static_cast<void*>(destination.data()),
        .iov_len = destination.size(),
    };

    msghdr message{};
    message.msg_name = &source_storage;
    message.msg_namelen = sizeof(source_storage);
    message.msg_iov = &iov;
    message.msg_iovlen = 1;

    const auto received = ::recvmsg(static_cast<int>(socket), &message, MSG_TRUNC);
    if (received < 0) {
        const int native_error = errno;
        if (is_would_block(native_error)) {
            return UdpReceiveResult{
                .status = status(UdpError::WouldBlock, native_error),
                .bytes_received = 0,
                .source = {},
            };
        }

        return UdpReceiveResult{
            .status = status(UdpError::ReceiveFailed, native_error),
            .bytes_received = 0,
            .source = {},
        };
    }

    const UdpEndpointResult source = endpoint_from_sockaddr(source_storage, message.msg_namelen);
    if (!source.ok()) {
        return UdpReceiveResult{
            .status = status(UdpError::ReceiveFailed, source.status.native_error),
            .bytes_received = 0,
            .source = {},
        };
    }

    const auto bytes_received = static_cast<std::size_t>(received);
    if ((message.msg_flags & MSG_TRUNC) != 0 || bytes_received > destination.size()) {
        return UdpReceiveResult{
            .status = status(UdpError::DatagramTruncated),
            .bytes_received = 0,
            .source = source.endpoint,
        };
    }

    return UdpReceiveResult{
        .status = status(UdpError::None),
        .bytes_received = bytes_received,
        .source = source.endpoint,
    };
}

UdpEndpointResult query_local_endpoint(NativeSocketHandle socket) noexcept {
    sockaddr_storage storage{};
    socklen_t length = sizeof(storage);
    if (::getsockname(static_cast<int>(socket), reinterpret_cast<sockaddr*>(&storage), &length) !=
        0) {
        return UdpEndpointResult{.status = status(UdpError::LocalEndpointQueryFailed, errno),
                                 .endpoint = {}};
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

    in_addr ipv4{};
    if (::inet_pton(AF_INET, text_buffer.data(), &ipv4) == 1) {
        IpAddress address = IpAddress::any_v4();
        std::memcpy(address.bytes.data(), &ipv4.s_addr, 4);
        return IpAddressParseResult{.error = IpAddressParseError::None, .address = address};
    }

    in6_addr ipv6{};
    if (::inet_pton(AF_INET6, text_buffer.data(), &ipv6) == 1) {
        IpAddress address = IpAddress::any_v6();
        std::memcpy(address.bytes.data(), ipv6.s6_addr, 16);
        return IpAddressParseResult{.error = IpAddressParseError::None, .address = address};
    }

    return IpAddressParseResult{.error = IpAddressParseError::InvalidAddress, .address = {}};
}

} // namespace warpnect::scl::internal
