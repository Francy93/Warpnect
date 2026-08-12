#include "input_transport.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::InputDatagramParser;
using warpnect::scl::InputDatagramSink;
using warpnect::scl::InputDeliveryClass;
using warpnect::scl::InputDeviceKind;
using warpnect::scl::InputGamepadState;
using warpnect::scl::InputKeyAction;
using warpnect::scl::InputKeyEvent;
using warpnect::scl::InputMessageHeader;
using warpnect::scl::InputMessageType;
using warpnect::scl::InputPointerAbsolute;
using warpnect::scl::InputPointerRelative;
using warpnect::scl::InputResetReason;
using warpnect::scl::InputResetScope;
using warpnect::scl::InputResetState;
using warpnect::scl::InputScroll;
using warpnect::scl::InputTouchAction;
using warpnect::scl::InputTouchContact;
using warpnect::scl::InputTouchFrame;
using warpnect::scl::InputTouchToolType;
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

[[nodiscard]] InputMessageHeader header(InputMessageType type,
                                        InputDeviceKind kind,
                                        std::uint16_t slot) noexcept {
    return InputMessageHeader{
        .input_version = 1,
        .message_type = type,
        .device_kind = kind,
        .flags = 0,
        .device_slot = slot,
    };
}

class RecordingSink final : public InputDatagramSink {
  public:
    InputTransportError next_error = InputTransportError::None;
    std::vector<std::vector<std::byte>> datagrams{};

    [[nodiscard]] InputTransportStatus
    send_input_datagram(std::span<const std::byte> datagram) noexcept override {
        datagrams.emplace_back(datagram.begin(), datagram.end());
        const InputTransportError error = next_error;
        next_error = InputTransportError::None;
        return InputTransportStatus{.error = error};
    }
};

[[nodiscard]] InputTransportSender make_sender(std::array<std::byte, 417>& scratch,
                                                RecordingSink& sink,
                                                std::uint32_t sequence = 0) {
    return InputTransportSender(
        InputTransportSenderConfig{
            .remote_endpoint = UdpEndpoint::loopback_v4(14000),
            .local_port = 0,
            .max_wire_datagram_size = 417,
            .initial_input_sequence = sequence,
        },
        InputTransportSenderWorkspace{.datagram_scratch = scratch}, sink);
}

void test_all_message_types_packetize_and_parse() {
    std::array<std::byte, 417> scratch{};
    RecordingSink sink;
    auto sender = make_sender(scratch, sink, 41);
    expect(sender.open().ok(), "test sender opens");

    InputTouchFrame touch{};
    touch.header = header(InputMessageType::TouchFrame, InputDeviceKind::Touchscreen, 2);
    touch.action = InputTouchAction::Move;
    touch.action_pointer_id = 0xFF;
    touch.pointer_count = 1;
    touch.contacts[0] = InputTouchContact{
        .pointer_id = 3,
        .tool_type = InputTouchToolType::Finger,
        .pointer_flags = 0x0003,
        .x_normalized = 123,
        .y_normalized = 456,
        .pressure = 789,
        .size = 987,
    };

    expect(sender.submit_key(101, InputKeyEvent{
                                        .header = header(InputMessageType::Key,
                                                         InputDeviceKind::Keyboard, 1),
                                        .usage_page = 0x0007,
                                        .usage_id = 0x0004,
                                        .action = InputKeyAction::Down,
                                        .repeat_count = 0,
                                        .modifier_mask = 0x0002,
                                    }).ok(),
           "key sends");
    expect(sender.submit_touch_frame(102, touch).ok(), "touch sends");
    expect(sender.submit_pointer_absolute(103, InputPointerAbsolute{
                                                     .header = header(InputMessageType::PointerAbsolute,
                                                                      InputDeviceKind::Mouse, 3),
                                                     .x_normalized = 100,
                                                     .y_normalized = 200,
                                                     .button_mask = 1,
                                                 }).ok(),
           "absolute pointer sends");
    expect(sender.submit_pointer_relative(104, InputPointerRelative{
                                                     .header = header(InputMessageType::PointerRelative,
                                                                      InputDeviceKind::Mouse, 3),
                                                     .delta_x_q16_16 = 65536,
                                                     .delta_y_q16_16 = -65536,
                                                     .button_mask = 1,
                                                 }).ok(),
           "relative pointer sends");
    expect(sender.submit_scroll(105, InputScroll{
                                           .header = header(InputMessageType::Scroll,
                                                            InputDeviceKind::Mouse, 3),
                                           .horizontal_q8_8 = 128,
                                           .vertical_q8_8 = -256,
                                           .button_mask = 1,
                                       }).ok(),
           "scroll sends");
    expect(sender.submit_gamepad_state(106, InputGamepadState{
                                                  .header = header(InputMessageType::GamepadState,
                                                                   InputDeviceKind::Gamepad, 4),
                                                  .button_mask = 0x00010001,
                                                  .left_x = -32767,
                                                  .left_y = 32767,
                                                  .right_x = -100,
                                                  .right_y = 200,
                                                  .left_trigger = 1000,
                                                  .right_trigger = 65000,
                                              }).ok(),
           "gamepad sends");
    expect(sender.submit_reset_state(107, InputResetState{
                                               .header = header(InputMessageType::ResetState,
                                                                InputDeviceKind::Unknown, 0xFFFF),
                                               .scope = InputResetScope::AllDevices,
                                               .reason = InputResetReason::SessionStop,
                                           }).ok(),
           "reset sends");

    expect_equal(sink.datagrams.size(), static_cast<std::size_t>(7), "one datagram per event");
    constexpr std::array<std::size_t, 7> expected_datagram_sizes{
        41, 45, 41, 41, 37, 49, 33,
    };
    for (std::size_t index = 0; index < sink.datagrams.size(); ++index) {
        expect_equal(sink.datagrams[index].size(), expected_datagram_sizes[index],
                     "fixed input datagram size");
        const auto parsed = InputDatagramParser::parse(sink.datagrams[index]);
        expect(parsed.ok(), "input datagram parses");
        if (parsed.ok()) {
            expect_equal(parsed.header.sequence_number, static_cast<std::uint32_t>(41 + index),
                         "single sequence domain increments");
            expect_equal(parsed.header.timestamp_us, static_cast<std::uint64_t>(101 + index),
                         "event timestamp preserves exactly");
            expect_equal(parsed.header.slice_index, static_cast<std::uint16_t>(0),
                         "input slice index is zero");
            expect_equal(parsed.header.total_slices, static_cast<std::uint16_t>(1),
                         "input total slices is one");
        }
    }
    const auto snapshot = sender.snapshot();
    expect_equal(snapshot.events_submitted, static_cast<std::uint64_t>(7), "submission count");
    expect_equal(snapshot.datagrams_sent, static_cast<std::uint64_t>(7), "send count");
    expect_equal(snapshot.fresh_state_submitted, static_cast<std::uint64_t>(5), "fresh count");
    expect_equal(snapshot.critical_transitions_submitted, static_cast<std::uint64_t>(1),
                 "critical count");
    expect_equal(snapshot.resets_submitted, static_cast<std::uint64_t>(1), "reset count");
}

void test_max_touch_budget_and_fragment_rejection() {
    std::array<std::byte, 417> scratch{};
    RecordingSink sink;
    auto sender = make_sender(scratch, sink);
    expect(sender.open().ok(), "sender opens with exact input budget");

    InputTouchFrame touch{};
    touch.header = header(InputMessageType::TouchFrame, InputDeviceKind::Touchpad, 5);
    touch.action = InputTouchAction::Move;
    touch.action_pointer_id = 0xFF;
    touch.pointer_count = 32;
    for (std::uint8_t i = 0; i < touch.pointer_count; ++i) {
        touch.contacts[i] = InputTouchContact{
            .pointer_id = i,
            .tool_type = InputTouchToolType::Finger,
            .x_normalized = static_cast<std::uint16_t>(i * 1000U),
            .y_normalized = static_cast<std::uint16_t>(0xFFFFU - i),
        };
    }
    expect(sender.submit_touch_frame(777, touch).ok(), "maximum touch sends");
    expect_equal(sink.datagrams.front().size(), static_cast<std::size_t>(417),
                 "maximum touch datagram is 417 bytes");
    expect(InputDatagramParser::parse(sink.datagrams.front()).ok(), "maximum touch parses");

    auto fragmented = sink.datagrams.front();
    fragmented[19] = std::byte{0};
    fragmented[20] = std::byte{2};
    expect_equal(InputDatagramParser::parse(fragmented).error,
                 InputTransportError::FragmentedInputUnsupported,
                 "fragmented input rejects");
    expect_equal(warpnect::scl::input_datagram_budget(416).error,
                 InputTransportError::InvalidDatagramBudget, "416-byte budget rejects");
    expect(warpnect::scl::input_datagram_budget(417).ok(), "417-byte budget accepts");
    expect(warpnect::scl::input_datagram_budget(900).ok(), "larger budget accepts");
}

void test_parser_rejects_non_input_and_malformed_payloads() {
    std::array<std::byte, 417> scratch{};
    RecordingSink sink;
    auto sender = make_sender(scratch, sink);
    expect(sender.open().ok(), "parser test sender opens");
    const InputKeyEvent key{
        .header = header(InputMessageType::Key, InputDeviceKind::Keyboard, 1),
        .usage_page = 0x0007,
        .usage_id = 0x0004,
        .action = InputKeyAction::Down,
    };
    expect(sender.submit_key(42, key).ok(), "parser test key sends");
    const auto& valid = sink.datagrams.front();

    auto wrong_payload_type = valid;
    wrong_payload_type[16] = std::byte{5};
    expect_equal(InputDatagramParser::parse(wrong_payload_type).error,
                 InputTransportError::UnexpectedPayloadType,
                 "non-input payload type rejects");

    auto unsupported_version = valid;
    unsupported_version[0] = std::byte{0};
    unsupported_version[1] = std::byte{2};
    expect_equal(InputDatagramParser::parse(unsupported_version).error,
                 InputTransportError::UnsupportedProtocolVersion,
                 "unsupported SCL version rejects");

    auto malformed_payload = valid;
    malformed_payload[warpnect::scl::kPacketHeaderWireSize] = std::byte{2};
    expect_equal(InputDatagramParser::parse(malformed_payload).error,
                 InputTransportError::MalformedInputPayload,
                 "malformed input payload rejects");

    auto trailing_payload = valid;
    trailing_payload.push_back(std::byte{0});
    expect_equal(InputDatagramParser::parse(trailing_payload).error,
                 InputTransportError::MalformedInputPayload,
                 "trailing input bytes reject");
}

void test_failed_send_consumes_sequence_without_queue() {
    std::array<std::byte, 417> scratch{};
    RecordingSink sink;
    auto sender = make_sender(scratch, sink, 0xFFFFFFFEU);
    expect(sender.open().ok(), "failure test sender opens");
    sink.next_error = InputTransportError::WouldBlock;
    const InputKeyEvent key{
        .header = header(InputMessageType::Key, InputDeviceKind::Keyboard, 0),
        .usage_page = 7,
        .usage_id = 4,
        .action = InputKeyAction::Up,
    };
    expect_equal(sender.submit_key(9, key).error, InputTransportError::WouldBlock,
                 "would block surfaces immediately");
    expect(sender.submit_key(10, key).ok(), "next input attempts independently");
    const auto first = InputDatagramParser::parse(sink.datagrams[0]);
    const auto second = InputDatagramParser::parse(sink.datagrams[1]);
    expect_equal(first.header.sequence_number, 0xFFFFFFFEU, "failed attempt sequence is visible");
    expect_equal(second.header.sequence_number, 0xFFFFFFFFU, "sequence does not roll back");
    expect_equal(sender.snapshot().next_input_sequence, 0U, "input sequence wraps after send attempts");
    expect_equal(sender.snapshot().critical_transitions_dropped, static_cast<std::uint64_t>(1),
                 "critical failure is counted distinctly");

    sink.next_error = InputTransportError::UdpSendFailed;
    const InputResetState reset{
        .header = header(InputMessageType::ResetState, InputDeviceKind::Unknown, 0xFFFF),
        .scope = InputResetScope::AllDevices,
        .reason = InputResetReason::FocusLost,
    };
    expect_equal(sender.submit_reset_state(11, reset).error, InputTransportError::UdpSendFailed,
                 "reset failure surfaces");
    expect_equal(sender.snapshot().reset_send_failures, static_cast<std::uint64_t>(1),
                 "reset failures are distinct");
}

void test_udp_loopback() {
    UdpSocket receiver;
    expect(receiver.open(IpVersion::V4).ok(), "loopback receiver opens");
    expect(receiver.bind(UdpEndpoint::any_v4(0)).ok(), "loopback receiver binds");
    const auto endpoint = receiver.local_endpoint();
    expect(endpoint.ok(), "loopback local endpoint available");
    if (!endpoint.ok()) {
        return;
    }

    std::array<std::byte, 417> sender_scratch{};
    InputTransportSender sender(
        InputTransportSenderConfig{
            .remote_endpoint = UdpEndpoint::loopback_v4(endpoint.endpoint.port),
            .max_wire_datagram_size = 417,
            .initial_input_sequence = 77,
        },
        InputTransportSenderWorkspace{.datagram_scratch = sender_scratch});
    expect(sender.open().ok(), "udp sender opens");
    const InputKeyEvent key{
        .header = header(InputMessageType::Key, InputDeviceKind::Keyboard, 8),
        .usage_page = 0x0007,
        .usage_id = 0x0028,
        .action = InputKeyAction::Down,
        .modifier_mask = 0x0001,
    };
    expect(sender.submit_key(0x0102030405060708ULL, key).ok(), "udp key sends");
    const auto ready = receiver.wait_readable(500000);
    expect(ready.ok() && ready.readable, "udp loopback becomes readable");
    std::array<std::byte, 417> receive_scratch{};
    const auto received = receiver.receive_from(receive_scratch);
    expect(received.ok(), "udp loopback receives");
    if (received.ok()) {
        const auto parsed = InputDatagramParser::parse(
            std::span<const std::byte>(receive_scratch.data(), received.bytes_received));
        expect(parsed.ok(), "udp loopback parser validates input");
        if (parsed.ok()) {
            expect_equal(parsed.header.sequence_number, 77U, "udp loopback sequence");
            expect_equal(parsed.header.timestamp_us, 0x0102030405060708ULL,
                         "udp loopback source timestamp");
            expect_equal(parsed.input_header.device_slot, static_cast<std::uint16_t>(8),
                         "udp loopback portable slot");
        }
    }

    InputTouchFrame maximum_touch{};
    maximum_touch.header = header(InputMessageType::TouchFrame, InputDeviceKind::Touchscreen, 8);
    maximum_touch.action = InputTouchAction::Move;
    maximum_touch.action_pointer_id = 0xFF;
    maximum_touch.pointer_count = 32;
    for (std::uint8_t index = 0; index < maximum_touch.pointer_count; ++index) {
        maximum_touch.contacts[index] = InputTouchContact{
            .pointer_id = index,
            .tool_type = InputTouchToolType::Finger,
        };
    }
    expect(sender.submit_touch_frame(0x1020304050607080ULL, maximum_touch).ok(),
           "maximum touch sends through UDP");
    const auto maximum_ready = receiver.wait_readable(500000);
    expect(maximum_ready.ok() && maximum_ready.readable, "maximum touch UDP is readable");
    const auto maximum_received = receiver.receive_from(receive_scratch);
    expect_equal(maximum_received.bytes_received, static_cast<std::size_t>(417),
                 "maximum touch UDP has full wire size");
    if (maximum_received.ok()) {
        expect(InputDatagramParser::parse(
                   std::span<const std::byte>(receive_scratch.data(), maximum_received.bytes_received))
                   .ok(),
               "maximum touch UDP parser validates input");
    }
    const auto sender_snapshot = sender.snapshot();
    expect(sender_snapshot.has_local_endpoint, "udp sender exposes local endpoint");
    expect(sender_snapshot.local_endpoint_port != 0, "udp sender bound local port is nonzero");
}

} // namespace

int main() {
    test_all_message_types_packetize_and_parse();
    test_max_touch_budget_and_fragment_rejection();
    test_parser_rejects_non_input_and_malformed_payloads();
    test_failed_send_consumes_sequence_without_queue();
    test_udp_loopback();

    if (failures != 0) {
        std::cerr << failures << " input transport test failure(s)\n";
        return 1;
    }
    std::cout << "Input transport tests passed\n";
    return 0;
}
