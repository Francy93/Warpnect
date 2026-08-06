#ifndef WARPNECT_SCL_UDP_ENGINE_H_
#define WARPNECT_SCL_UDP_ENGINE_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace warpnect::scl {

enum class AddressFamily : std::uint8_t {
    Ipv4,
    Ipv6,
};

struct Endpoint final {
    AddressFamily address_family = AddressFamily::Ipv4;
    std::array<std::uint8_t, 16> address{};
    std::uint16_t port = 0;
};

enum class UdpEngineStatus : std::uint8_t {
    Ok,
    NotInitialized,
    InvalidEndpoint,
    NotImplemented,
    Shutdown,
};

struct ReceiveResult final {
    UdpEngineStatus status;
    std::size_t bytes_received;
    Endpoint remote_endpoint;
};

class UdpEngine {
public:
    virtual ~UdpEngine() = default;

    virtual UdpEngineStatus initialize_sender(const Endpoint& remote_endpoint) noexcept = 0;
    virtual UdpEngineStatus initialize_receiver(std::uint16_t local_port) noexcept = 0;
    virtual UdpEngineStatus send_packet(std::span<const std::byte> packet) noexcept = 0;
    virtual ReceiveResult receive_packet(std::span<std::byte> buffer) noexcept = 0;
    virtual void shutdown() noexcept = 0;
};

}  // namespace warpnect::scl

#endif  // WARPNECT_SCL_UDP_ENGINE_H_
