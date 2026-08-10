#include "udp_socket.h"

#include "internal/socket_platform.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr UdpStatus status(UdpError error, int native_error = 0) noexcept {
    return UdpStatus{.error = error, .native_error = native_error};
}

[[nodiscard]] constexpr bool destination_is_valid(const UdpEndpoint& endpoint) noexcept {
    return is_supported_ip_version(endpoint.address.version) && endpoint.port != 0 &&
           !endpoint.address.is_unspecified();
}

} // namespace

UdpSocket::~UdpSocket() noexcept {
    close();
}

UdpSocket::UdpSocket(UdpSocket&& other) noexcept
    : socket_handle_(other.socket_handle_), ip_version_(other.ip_version_), bound_(other.bound_) {
    other.socket_handle_ = kInvalidSocketHandle;
    other.ip_version_ = IpVersion::V4;
    other.bound_ = false;
}

UdpSocket& UdpSocket::operator=(UdpSocket&& other) noexcept {
    if (this != &other) {
        close();
        socket_handle_ = other.socket_handle_;
        ip_version_ = other.ip_version_;
        bound_ = other.bound_;
        other.socket_handle_ = kInvalidSocketHandle;
        other.ip_version_ = IpVersion::V4;
        other.bound_ = false;
    }

    return *this;
}

UdpStatus UdpSocket::open(IpVersion version) noexcept {
    if (is_open()) {
        return status(UdpError::AlreadyOpen);
    }

    if (!is_supported_ip_version(version)) {
        return status(UdpError::AddressFamilyUnsupported);
    }

    const internal::NativeSocketOpenResult opened = internal::open_udp_socket(version);
    if (!opened.ok()) {
        return opened.status;
    }

    socket_handle_ = opened.socket;
    ip_version_ = version;
    bound_ = false;
    return status(UdpError::None);
}

UdpStatus UdpSocket::bind(const UdpEndpoint& endpoint) noexcept {
    if (!is_open()) {
        return status(UdpError::NotOpen);
    }

    if (bound_) {
        return status(UdpError::AlreadyBound);
    }

    if (!is_supported_ip_version(endpoint.address.version)) {
        return status(UdpError::AddressFamilyUnsupported);
    }

    if (endpoint.address.version != ip_version_) {
        return status(UdpError::AddressFamilyMismatch);
    }

    const UdpStatus bind_status = internal::bind_udp_socket(socket_handle_, endpoint);
    if (!bind_status.ok()) {
        return bind_status;
    }

    bound_ = true;
    return status(UdpError::None);
}

UdpSendResult UdpSocket::send_to(std::span<const std::byte> datagram,
                                 const UdpEndpoint& destination) noexcept {
    if (!is_open()) {
        return UdpSendResult{.status = status(UdpError::NotOpen), .bytes_sent = 0};
    }

    if (datagram.size() > kUdpMaxDatagramPayloadSize) {
        return UdpSendResult{.status = status(UdpError::DatagramTooLarge), .bytes_sent = 0};
    }

    if (!is_supported_ip_version(destination.address.version)) {
        return UdpSendResult{.status = status(UdpError::AddressFamilyUnsupported), .bytes_sent = 0};
    }

    if (destination.address.version != ip_version_) {
        return UdpSendResult{.status = status(UdpError::AddressFamilyMismatch), .bytes_sent = 0};
    }

    if (destination.port == 0) {
        return UdpSendResult{.status = status(UdpError::InvalidPort), .bytes_sent = 0};
    }

    if (!destination_is_valid(destination)) {
        return UdpSendResult{.status = status(UdpError::InvalidAddress), .bytes_sent = 0};
    }

    return internal::send_udp_datagram(socket_handle_, datagram, destination);
}

UdpReceiveResult UdpSocket::receive_from(std::span<std::byte> destination) noexcept {
    if (!is_open()) {
        return UdpReceiveResult{
            .status = status(UdpError::NotOpen),
            .bytes_received = 0,
            .source = {},
        };
    }

    if (!bound_) {
        return UdpReceiveResult{
            .status = status(UdpError::NotBound),
            .bytes_received = 0,
            .source = {},
        };
    }

    return internal::receive_udp_datagram(socket_handle_, destination);
}

UdpReadinessResult UdpSocket::wait_readable(std::uint64_t timeout_us) noexcept {
    if (!is_open()) {
        return UdpReadinessResult{.status = status(UdpError::NotOpen), .readable = false};
    }

    if (!bound_) {
        return UdpReadinessResult{.status = status(UdpError::NotBound), .readable = false};
    }

    return internal::wait_udp_socket_readable(socket_handle_, timeout_us);
}

UdpEndpointResult UdpSocket::local_endpoint() const noexcept {
    if (!is_open()) {
        return UdpEndpointResult{.status = status(UdpError::NotOpen), .endpoint = {}};
    }

    if (!bound_) {
        return UdpEndpointResult{.status = status(UdpError::NotBound), .endpoint = {}};
    }

    return internal::query_local_endpoint(socket_handle_);
}

bool UdpSocket::is_open() const noexcept {
    return socket_handle_ != kInvalidSocketHandle;
}

void UdpSocket::close() noexcept {
    if (is_open()) {
        internal::close_socket(socket_handle_);
    }

    socket_handle_ = kInvalidSocketHandle;
    ip_version_ = IpVersion::V4;
    bound_ = false;
}

} // namespace warpnect::scl
