#include "input_receiver_runtime.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

#include "input_transport.h"

namespace {

using warpnect::scl::InputDatagramSink;
using warpnect::scl::InputDeviceKind;
using warpnect::scl::InputKeyAction;
using warpnect::scl::InputKeyEvent;
using warpnect::scl::InputMessageHeader;
using warpnect::scl::InputMessageType;
using warpnect::scl::InputReceiverConfig;
using warpnect::scl::InputReceiverEventType;
using warpnect::scl::InputReceiverRuntime;
using warpnect::scl::InputTransportError;
using warpnect::scl::InputTransportSender;
using warpnect::scl::InputTransportSenderConfig;
using warpnect::scl::InputTransportSenderWorkspace;
using warpnect::scl::InputTransportStatus;
using warpnect::scl::IpVersion;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::UdpSocket;

int failures = 0;

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

template <typename T>
[[nodiscard]] T read_native(const std::array<std::byte, 1024>& bytes, std::size_t offset) {
    T value{};
    std::memcpy(&value, bytes.data() + offset, sizeof(value));
    return value;
}

[[nodiscard]] InputMessageHeader header(std::uint16_t slot) noexcept {
    return InputMessageHeader{
        .input_version = 1,
        .message_type = InputMessageType::Key,
        .device_kind = InputDeviceKind::Keyboard,
        .flags = 0,
        .device_slot = slot,
    };
}

[[nodiscard]] std::uint16_t allocate_loopback_port() {
    UdpSocket socket;
    expect(socket.open(IpVersion::V4).ok(), "probe socket opens");
    expect(socket.bind(UdpEndpoint::any_v4(0)).ok(), "probe socket binds");
    const auto endpoint = socket.local_endpoint();
    expect(endpoint.ok(), "probe endpoint available");
    return endpoint.ok() ? endpoint.endpoint.port : 0;
}

class RecordingSink final : public InputDatagramSink {
  public:
    std::vector<std::vector<std::byte>> datagrams{};

    [[nodiscard]] InputTransportStatus
    send_input_datagram(std::span<const std::byte> datagram) noexcept override {
        datagrams.emplace_back(datagram.begin(), datagram.end());
        return InputTransportStatus{};
    }
};

void test_udp_loopback_bridge_and_endpoint_filter() {
    const std::uint16_t receiver_port = allocate_loopback_port();
    const std::uint16_t sender_port = allocate_loopback_port();
    if (receiver_port == 0 || sender_port == 0) return;

    InputReceiverRuntime receiver(InputReceiverConfig{
        .local_endpoint = UdpEndpoint::any_v4(receiver_port),
        .expected_remote_endpoint = UdpEndpoint::loopback_v4(sender_port),
    });
    expect(receiver.open() == warpnect::scl::InputReceiverError::None, "receiver opens");

    std::array<std::byte, 417> sender_scratch{};
    InputTransportSender sender(
        InputTransportSenderConfig{
            .remote_endpoint = UdpEndpoint::loopback_v4(receiver_port),
            .local_port = sender_port,
            .max_wire_datagram_size = 417,
            .initial_input_sequence = 90,
        },
        InputTransportSenderWorkspace{.datagram_scratch = sender_scratch});
    expect(sender.open().ok(), "sender opens on configured source port");
    const InputKeyEvent key{
        .header = header(12),
        .usage_page = 0x0007,
        .usage_id = 0x0028,
        .action = InputKeyAction::Down,
        .repeat_count = 3,
        .modifier_mask = 0x0081,
    };
    expect(sender.submit_key(987654, key).ok(), "loopback key sends");
    const auto event = receiver.pump(500000);
    expect_equal(event.type, InputReceiverEventType::EventReady, "receiver returns parsed event");
    expect_equal(event.sequence_number, 90U, "sequence preserves exactly");
    expect_equal(event.source_event_time_us, 987654ULL, "source timestamp preserves exactly");
    expect_equal(event.key.usage_id, static_cast<std::uint16_t>(0x0028), "key decodes");

    std::array<std::byte, 1024> bridge{};
    expect(receiver.write_bridge(bridge) == warpnect::scl::InputReceiverError::None,
           "bridge writes persistent scalar record");
    expect_equal(read_native<std::int32_t>(bridge, 0), static_cast<std::int32_t>(InputMessageType::Key),
                 "bridge message type");
    expect_equal(read_native<std::int32_t>(bridge, 8), 12, "bridge device slot");
    expect_equal(read_native<std::int64_t>(bridge, 16), static_cast<std::int64_t>(90),
                 "bridge sequence");
    expect_equal(read_native<std::int64_t>(bridge, 24), static_cast<std::int64_t>(987654),
                 "bridge timestamp");
    expect_equal(read_native<std::int32_t>(bridge, 32), 7, "bridge key usage page");
    expect_equal(read_native<std::int32_t>(bridge, 36), 0x28, "bridge key usage id");

    const auto parsed = warpnect::scl::InputDatagramParser::parse(
        std::span<const std::byte>(sender_scratch.data(), 41));
    expect(parsed.ok(), "sender scratch still contains complete datagram");
    if (parsed.ok()) {
        const auto unexpected = receiver.accept_datagram(
            std::span<const std::byte>(sender_scratch.data(), 41),
            UdpEndpoint::loopback_v4(static_cast<std::uint16_t>(sender_port + 1)));
        expect_equal(unexpected.type, InputReceiverEventType::UnexpectedEndpointDropped,
                     "unexpected endpoint drops before parse");
    }
    expect_equal(receiver.snapshot().unexpected_endpoint_drops, 1ULL,
                 "unexpected endpoint is counted");
}

void test_malformed_oversize_interrupt_and_sequence_diagnostics() {
    const UdpEndpoint expected = UdpEndpoint::loopback_v4(15001);
    InputReceiverRuntime receiver(InputReceiverConfig{
        .local_endpoint = UdpEndpoint::any_v4(15002),
        .expected_remote_endpoint = expected,
    });
    expect(receiver.open() == warpnect::scl::InputReceiverError::None, "diagnostic receiver opens");
    const std::array<std::byte, 1> malformed{std::byte{0}};
    expect_equal(receiver.accept_datagram(malformed, expected).type,
                 InputReceiverEventType::MalformedDatagramDropped, "malformed datagram drops");
    std::array<std::byte, 418> oversize{};
    expect_equal(receiver.accept_datagram(oversize, expected).type,
                 InputReceiverEventType::OversizeDatagramDropped, "oversize datagram drops");

    std::array<std::byte, 417> scratch{};
    RecordingSink sink;
    InputTransportSender sender(
        InputTransportSenderConfig{
            .remote_endpoint = expected,
            .max_wire_datagram_size = 417,
        },
        InputTransportSenderWorkspace{.datagram_scratch = scratch}, sink);
    expect(sender.open().ok(), "recording sender opens");
    const InputKeyEvent key{
        .header = header(1),
        .usage_page = 7,
        .usage_id = 4,
        .action = InputKeyAction::Down,
    };
    expect(sender.submit_key(1, key).ok(), "sequence 0 sends");
    expect(sender.submit_key(2, key).ok(), "sequence 1 sends");
    expect(sender.submit_key(3, key).ok(), "sequence 2 sends");
    expect_equal(receiver.accept_datagram(sink.datagrams[0], expected).type,
                 InputReceiverEventType::EventReady, "first sequence accepts");
    expect_equal(receiver.accept_datagram(sink.datagrams[2], expected).type,
                 InputReceiverEventType::EventReady, "future sequence accepts without waiting");
    expect_equal(receiver.accept_datagram(sink.datagrams[1], expected).type,
                 InputReceiverEventType::EventReady, "late sequence still delivers in arrival order");
    expect_equal(receiver.accept_datagram(sink.datagrams[2], expected).type,
                 InputReceiverEventType::EventReady, "same sequence still delivers in arrival order");
    const auto snapshot = receiver.snapshot();
    expect_equal(snapshot.sequence_first, 1ULL, "first sequence counter");
    expect_equal(snapshot.sequence_gap_events, 1ULL, "gap diagnostics counter");
    expect_equal(snapshot.sequence_gap_count, 1ULL, "gap count");
    expect_equal(snapshot.sequence_out_of_order, 1ULL, "out of order diagnostics counter");
    expect_equal(snapshot.sequence_same, 1ULL, "same diagnostics counter");

    receiver.interrupt();
    expect_equal(receiver.pump(0).type, InputReceiverEventType::Interrupted,
                 "interrupt wakes runtime without a polling loop");
}

} // namespace

int main() {
    test_udp_loopback_bridge_and_endpoint_filter();
    test_malformed_oversize_interrupt_and_sequence_diagnostics();
    if (failures != 0) {
        std::cerr << failures << " input receiver runtime test failure(s)\n";
        return 1;
    }
    std::cout << "Input receiver runtime tests passed\n";
    return 0;
}
