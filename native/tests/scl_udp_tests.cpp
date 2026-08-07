#include "packet_codec.h"
#include "protocol.h"
#include "udp_endpoint.h"
#include "udp_result.h"
#include "udp_socket.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>

namespace {

using warpnect::scl::IpAddress;
using warpnect::scl::IpAddressParseError;
using warpnect::scl::IpVersion;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::UdpError;
using warpnect::scl::UdpReceiveResult;
using warpnect::scl::UdpSendResult;
using warpnect::scl::UdpSocket;

int failures = 0;
int skips = 0;

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

void expect(bool condition, std::string_view message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

template <typename T>
void expect_equal(const T& actual, const T& expected, std::string_view message) {
    if (!(actual == expected)) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

void skip(std::string_view message) {
    std::cout << "SKIP: " << message << '\n';
    ++skips;
}

[[nodiscard]] constexpr bool payload_matches(std::span<const std::byte> actual,
                                             std::span<const std::byte> expected) noexcept {
    if (actual.size() != expected.size()) {
        return false;
    }

    for (std::size_t i = 0; i < actual.size(); ++i) {
        if (actual[i] != expected[i]) {
            return false;
        }
    }

    return true;
}

[[nodiscard]] UdpReceiveResult receive_until_ready(UdpSocket& socket,
                                                   std::span<std::byte> buffer) noexcept {
    UdpReceiveResult result{};
    for (int attempt = 0; attempt < 10000; ++attempt) {
        result = socket.receive_from(buffer);
        if (result.status.error != UdpError::WouldBlock) {
            return result;
        }
    }

    return result;
}

[[nodiscard]] bool open_bound_receiver_v4(UdpSocket& receiver,
                                          UdpEndpoint& local_endpoint) noexcept {
    const auto open = receiver.open(IpVersion::V4);
    expect(open.ok(), "IPv4 receiver opens");
    if (!open.ok()) {
        return false;
    }

    const auto bind = receiver.bind(UdpEndpoint::loopback_v4(0));
    expect(bind.ok(), "IPv4 receiver binds loopback ephemeral port");
    if (!bind.ok()) {
        return false;
    }

    const auto local = receiver.local_endpoint();
    expect(local.ok(), "IPv4 receiver local endpoint is available");
    if (!local.ok()) {
        return false;
    }

    expect(local.endpoint.address == IpAddress::loopback_v4(), "IPv4 local address is loopback");
    expect(local.endpoint.port != 0, "IPv4 local endpoint has ephemeral port");
    local_endpoint = local.endpoint;
    return true;
}

[[nodiscard]] bool send_and_receive_v4(std::span<const std::byte> payload) noexcept {
    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return false;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "IPv4 sender opens");

    const UdpSendResult sent = sender.send_to(payload, destination);
    expect(sent.ok(), "IPv4 datagram sends");
    expect_equal(sent.bytes_sent, payload.size(), "IPv4 datagram send byte count");

    std::array<std::byte, 1500> receive_buffer{};
    UdpReceiveResult received =
        receive_until_ready(receiver, std::span<std::byte>(receive_buffer).first(payload.size()));

    expect(received.ok(), "IPv4 datagram receives");
    expect_equal(received.bytes_received, payload.size(), "IPv4 datagram receive byte count");
    expect(
        payload_matches(std::span<const std::byte>(receive_buffer).first(payload.size()), payload),
        "IPv4 datagram payload matches");
    expect(received.source.address == IpAddress::loopback_v4(), "IPv4 source address is loopback");
    expect(received.source.port != 0, "IPv4 source port is non-zero");

    return sent.ok() && received.ok();
}

void test_numeric_address_parsing() {
    const auto loopback_v4 = warpnect::scl::parse_numeric_ip_address("127.0.0.1");
    expect(loopback_v4.ok(), "parse IPv4 loopback");
    expect(loopback_v4.address == IpAddress::loopback_v4(), "parsed IPv4 loopback value");

    const auto lan_v4 = warpnect::scl::parse_numeric_ip_address("192.168.1.20");
    expect(lan_v4.ok(), "parse IPv4 numeric address");
    expect_equal(lan_v4.address.bytes[0], static_cast<std::uint8_t>(192), "parsed IPv4 byte 0");
    expect_equal(lan_v4.address.bytes[1], static_cast<std::uint8_t>(168), "parsed IPv4 byte 1");
    expect_equal(lan_v4.address.bytes[2], static_cast<std::uint8_t>(1), "parsed IPv4 byte 2");
    expect_equal(lan_v4.address.bytes[3], static_cast<std::uint8_t>(20), "parsed IPv4 byte 3");

    const auto loopback_v6 = warpnect::scl::parse_numeric_ip_address("::1");
    expect(loopback_v6.ok(), "parse IPv6 loopback");
    expect(loopback_v6.address == IpAddress::loopback_v6(), "parsed IPv6 loopback value");

    const auto doc_v6 = warpnect::scl::parse_numeric_ip_address("2001:db8::1");
    expect(doc_v6.ok(), "parse IPv6 documentation address");
    expect_equal(doc_v6.address.version, IpVersion::V6, "parsed IPv6 version");

    const auto hostname = warpnect::scl::parse_numeric_ip_address("localhost");
    expect_equal(hostname.error, IpAddressParseError::InvalidAddress,
                 "hostname is not resolved by numeric parser");
}

void test_socket_lifecycle() {
    UdpSocket socket;
    expect(!socket.is_open(), "default socket is closed");
    expect_equal(socket.receive_from(std::span<std::byte>{}).status.error, UdpError::NotOpen,
                 "receive before open fails");
    expect_equal(socket.local_endpoint().status.error, UdpError::NotOpen,
                 "local endpoint before open fails");

    expect(socket.open(IpVersion::V4).ok(), "socket opens");
    expect(socket.is_open(), "socket reports open");
    expect_equal(socket.open(IpVersion::V4).error, UdpError::AlreadyOpen,
                 "opening twice is rejected");
    expect_equal(socket.receive_from(std::span<std::byte>{}).status.error, UdpError::NotBound,
                 "receive before bind fails");
    expect_equal(socket.local_endpoint().status.error, UdpError::NotBound,
                 "local endpoint before bind fails");

    socket.close();
    expect(!socket.is_open(), "socket closes");
    socket.close();
    expect(!socket.is_open(), "closing twice is safe");

    UdpEndpoint released_endpoint{};
    {
        UdpSocket scoped;
        expect(scoped.open(IpVersion::V4).ok(), "scoped socket opens");
        expect(scoped.bind(UdpEndpoint::loopback_v4(0)).ok(), "scoped socket binds");
        const auto local = scoped.local_endpoint();
        expect(local.ok(), "scoped socket local endpoint available");
        released_endpoint = local.endpoint;
    }

    UdpSocket rebound;
    expect(rebound.open(IpVersion::V4).ok(), "rebound socket opens");
    expect(rebound.bind(released_endpoint).ok(), "destructor released UDP port");

    UdpSocket movable;
    expect(movable.open(IpVersion::V4).ok(), "movable socket opens");
    UdpSocket moved_constructed(std::move(movable));
    expect(moved_constructed.is_open(), "move constructor transfers open socket");
    expect(!movable.is_open(), "move constructor leaves source closed");
    expect_equal(
        movable.send_to(std::span<const std::byte>{}, UdpEndpoint::loopback_v4(9)).status.error,
        UdpError::NotOpen, "moved-from socket behaves as closed");

    UdpSocket move_assigned_source;
    expect(move_assigned_source.open(IpVersion::V4).ok(), "move source opens");
    UdpSocket move_assigned_target;
    move_assigned_target = std::move(move_assigned_source);
    expect(move_assigned_target.is_open(), "move assignment transfers open socket");
    expect(!move_assigned_source.is_open(), "move assignment leaves source closed");
}

void test_non_blocking_receive() {
    UdpSocket receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver_v4(receiver, endpoint)) {
        return;
    }

    std::array<std::byte, 64> buffer{};
    const UdpReceiveResult received = receiver.receive_from(buffer);
    expect_equal(received.status.error, UdpError::WouldBlock,
                 "empty receive queue returns WouldBlock");
}

void test_ipv4_loopback_and_binary_safety() {
    constexpr std::array<std::byte, 8> payload{
        byte(0x00), byte(0xFF), byte(0x10), byte(0x00),
        byte(0x7F), byte(0x80), byte(0x42), byte(0x24),
    };

    expect(send_and_receive_v4(payload), "IPv4 binary datagram round trip succeeds");
}

void test_datagram_boundaries() {
    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "boundary sender opens");

    constexpr std::array<std::byte, 3> first{byte(0x41), byte(0x41), byte(0x41)};
    constexpr std::array<std::byte, 5> second{byte(0x42), byte(0x42), byte(0x42), byte(0x42),
                                              byte(0x42)};

    expect(sender.send_to(first, destination).ok(), "first datagram sends");
    expect(sender.send_to(second, destination).ok(), "second datagram sends");

    std::array<std::byte, 16> buffer{};
    const UdpReceiveResult first_receive = receive_until_ready(receiver, buffer);
    expect(first_receive.ok(), "first datagram receives");
    expect_equal(first_receive.bytes_received, first.size(), "first datagram size preserved");
    expect(payload_matches(std::span<const std::byte>(buffer).first(first.size()), first),
           "first datagram payload preserved");

    buffer.fill(byte(0));
    const UdpReceiveResult second_receive = receive_until_ready(receiver, buffer);
    expect(second_receive.ok(), "second datagram receives");
    expect_equal(second_receive.bytes_received, second.size(), "second datagram size preserved");
    expect(payload_matches(std::span<const std::byte>(buffer).first(second.size()), second),
           "second datagram payload preserved");
}

void test_zero_length_datagram() {
    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "zero-length sender opens");
    const UdpSendResult sent = sender.send_to(std::span<const std::byte>{}, destination);
    expect(sent.ok(), "zero-length datagram sends");
    expect_equal(sent.bytes_sent, static_cast<std::size_t>(0), "zero-length send byte count");

    std::array<std::byte, 1> buffer{};
    const UdpReceiveResult received = receive_until_ready(receiver, buffer);
    expect(received.ok(), "zero-length datagram receives");
    expect_equal(received.bytes_received, static_cast<std::size_t>(0),
                 "zero-length receive byte count");
}

void test_truncation_detection() {
    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "truncation sender opens");
    constexpr std::array<std::byte, 16> large{
        byte(0), byte(1), byte(2),  byte(3),  byte(4),  byte(5),  byte(6),  byte(7),
        byte(8), byte(9), byte(10), byte(11), byte(12), byte(13), byte(14), byte(15),
    };
    expect(sender.send_to(large, destination).ok(), "large datagram sends");

    std::array<std::byte, 4> small{};
    const UdpReceiveResult received = receive_until_ready(receiver, small);
    expect_equal(received.status.error, UdpError::DatagramTruncated,
                 "oversized incoming datagram is reported as truncated");
}

void test_oversize_send_rejection() {
    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "oversize sender opens");

    std::array<std::byte, warpnect::scl::kUdpMaxDatagramPayloadSize + 1> oversize{};
    const UdpSendResult sent = sender.send_to(oversize, UdpEndpoint::loopback_v4(9));
    expect_equal(sent.status.error, UdpError::DatagramTooLarge,
                 "structurally oversize datagram rejected before OS send");
}

void test_endpoint_validation() {
    UdpSocket ipv4;
    expect(ipv4.open(IpVersion::V4).ok(), "IPv4 validation socket opens");
    std::array<std::byte, 1> payload{byte(0x01)};

    expect_equal(ipv4.send_to(payload, UdpEndpoint::loopback_v6(9)).status.error,
                 UdpError::AddressFamilyMismatch, "IPv4 socket rejects IPv6 destination");
    expect_equal(ipv4.send_to(payload, UdpEndpoint::loopback_v4(0)).status.error,
                 UdpError::InvalidPort, "destination port zero rejected");
    expect_equal(ipv4.send_to(payload, UdpEndpoint::any_v4(9)).status.error,
                 UdpError::InvalidAddress, "unspecified outbound address rejected");

    UdpSocket ipv6;
    const auto open_v6 = ipv6.open(IpVersion::V6);
    if (!open_v6.ok()) {
        skip("IPv6 validation socket unavailable");
        return;
    }

    expect_equal(ipv6.send_to(payload, UdpEndpoint::loopback_v4(9)).status.error,
                 UdpError::AddressFamilyMismatch, "IPv6 socket rejects IPv4 destination");
}

void test_ephemeral_port() {
    UdpSocket receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver_v4(receiver, endpoint)) {
        return;
    }

    expect_equal(endpoint.address.version, IpVersion::V4, "ephemeral endpoint is IPv4");
    expect(endpoint.port != 0, "ephemeral endpoint has non-zero port");
}

void test_ipv6_loopback_if_available() {
    UdpSocket receiver;
    const auto receiver_open = receiver.open(IpVersion::V6);
    if (!receiver_open.ok()) {
        skip("IPv6 receiver socket unavailable");
        return;
    }

    const auto receiver_bind = receiver.bind(UdpEndpoint::loopback_v6(0));
    if (!receiver_bind.ok()) {
        skip("IPv6 loopback bind unavailable");
        return;
    }

    const auto local = receiver.local_endpoint();
    expect(local.ok(), "IPv6 local endpoint available");
    if (!local.ok()) {
        return;
    }

    expect(local.endpoint.port != 0, "IPv6 ephemeral port assigned");

    UdpSocket sender;
    const auto sender_open = sender.open(IpVersion::V6);
    if (!sender_open.ok()) {
        skip("IPv6 sender socket unavailable");
        return;
    }

    constexpr std::array<std::byte, 4> payload{byte(0xDE), byte(0xAD), byte(0xBE), byte(0xEF)};
    const UdpSendResult sent = sender.send_to(payload, local.endpoint);
    expect(sent.ok(), "IPv6 datagram sends");
    if (!sent.ok()) {
        return;
    }

    std::array<std::byte, 16> buffer{};
    const UdpReceiveResult received = receive_until_ready(receiver, buffer);
    expect(received.ok(), "IPv6 datagram receives");
    expect_equal(received.bytes_received, payload.size(), "IPv6 datagram size preserved");
    expect(payload_matches(std::span<const std::byte>(buffer).first(payload.size()), payload),
           "IPv6 payload preserved");
    expect_equal(received.source.address.version, IpVersion::V6, "IPv6 source family reported");
    expect(received.source.port != 0, "IPv6 source port reported");
}

void test_would_block_stress() {
    UdpSocket receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver_v4(receiver, endpoint)) {
        return;
    }

    std::array<std::byte, 64> buffer{};
    for (int i = 0; i < 32; ++i) {
        expect_equal(receiver.receive_from(buffer).status.error, UdpError::WouldBlock,
                     "repeated empty receive returns WouldBlock");
    }
}

void test_moderate_datagram_sizes() {
    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "moderate sender opens");

    std::array<std::byte, 1200> payload{};
    for (std::size_t i = 0; i < payload.size(); ++i) {
        payload[i] = byte(static_cast<std::uint8_t>((i * 31U) & 0xFFU));
    }

    constexpr std::array<std::size_t, 7> sizes{1, 21, 64, 256, 512, 1024, 1200};
    std::array<std::byte, 1200> buffer{};

    for (std::size_t size : sizes) {
        const auto outgoing = std::span<const std::byte>(payload).first(size);
        const UdpSendResult sent = sender.send_to(outgoing, destination);
        expect(sent.ok(), "moderate datagram sends");
        expect_equal(sent.bytes_sent, size, "moderate datagram sent byte count");

        buffer.fill(byte(0));
        const UdpReceiveResult received = receive_until_ready(receiver, buffer);
        expect(received.ok(), "moderate datagram receives");
        expect_equal(received.bytes_received, size, "moderate datagram received byte count");
        expect(payload_matches(std::span<const std::byte>(buffer).first(size), outgoing),
               "moderate datagram payload preserved");
    }
}

void test_packet_over_udp_integration() {
    UdpSocket receiver;
    UdpEndpoint destination{};
    if (!open_bound_receiver_v4(receiver, destination)) {
        return;
    }

    UdpSocket sender;
    expect(sender.open(IpVersion::V4).ok(), "packet-over-UDP sender opens");

    constexpr std::array<std::byte, 5> payload{byte(0x53), byte(0x43), byte(0x4C), byte(0x01),
                                               byte(0x99)};
    constexpr PacketHeader header{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0xBEEF,
        .sequence_number = 0x01020304,
        .timestamp_us = 0x0102030405060708ULL,
        .payload_type = PayloadType::Telemetry,
        .slice_index = 0,
        .total_slices = 1,
    };

    std::array<std::byte, warpnect::scl::kPacketHeaderWireSize + payload.size()> packet{};
    const auto encoded = warpnect::scl::encode_packet(header, payload, packet);
    expect(encoded.ok(), "SCL packet encodes before UDP send");

    const UdpSendResult sent = sender.send_to(
        std::span<const std::byte>(packet).first(encoded.bytes_written), destination);
    expect(sent.ok(), "encoded SCL packet sends over UDP");

    std::array<std::byte, packet.size()> receive_buffer{};
    const UdpReceiveResult received = receive_until_ready(receiver, receive_buffer);
    expect(received.ok(), "encoded SCL packet receives over UDP");
    expect_equal(received.bytes_received, packet.size(), "received SCL packet byte count");

    const auto decoded = warpnect::scl::decode_packet(
        std::span<const std::byte>(receive_buffer).first(received.bytes_received));
    expect(decoded.ok(), "received SCL packet decodes");
    expect(decoded.packet.header == header, "received SCL header matches");
    expect(payload_matches(decoded.packet.payload, payload), "received SCL payload matches");
}

} // namespace

int main() {
    test_numeric_address_parsing();
    test_socket_lifecycle();
    test_non_blocking_receive();
    test_ipv4_loopback_and_binary_safety();
    test_datagram_boundaries();
    test_zero_length_datagram();
    test_truncation_detection();
    test_oversize_send_rejection();
    test_endpoint_validation();
    test_ephemeral_port();
    test_ipv6_loopback_if_available();
    test_would_block_stress();
    test_moderate_datagram_sizes();
    test_packet_over_udp_integration();

    if (failures != 0) {
        std::cerr << failures << " SCL UDP test failure(s)\n";
        return 1;
    }

    std::cout << "SCL UDP tests passed";
    if (skips != 0) {
        std::cout << " with " << skips << " environment skip(s)";
    }
    std::cout << '\n';
    return 0;
}
