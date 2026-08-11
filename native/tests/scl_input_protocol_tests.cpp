#include "input_protocol.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::InputDeliveryClass;
using warpnect::scl::InputDeviceKind;
using warpnect::scl::InputEncodeResult;
using warpnect::scl::InputGamepadState;
using warpnect::scl::InputGamepadStateWireBytes;
using warpnect::scl::InputKeyAction;
using warpnect::scl::InputKeyEvent;
using warpnect::scl::InputKeyWireBytes;
using warpnect::scl::InputMessageHeader;
using warpnect::scl::InputMessageHeaderWireBytes;
using warpnect::scl::InputMessageType;
using warpnect::scl::InputPointerAbsolute;
using warpnect::scl::InputPointerAbsoluteWireBytes;
using warpnect::scl::InputPointerRelative;
using warpnect::scl::InputPointerRelativeWireBytes;
using warpnect::scl::InputProtocolError;
using warpnect::scl::InputResetReason;
using warpnect::scl::InputResetScope;
using warpnect::scl::InputResetState;
using warpnect::scl::InputResetStateWireBytes;
using warpnect::scl::InputScroll;
using warpnect::scl::InputScrollWireBytes;
using warpnect::scl::InputTouchAction;
using warpnect::scl::InputTouchContact;
using warpnect::scl::InputTouchFrame;
using warpnect::scl::InputTouchToolType;

int failures = 0;

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

[[nodiscard]] std::span<const std::byte> bytes(const std::vector<std::byte>& data) noexcept {
    return std::span<const std::byte>(data.data(), data.size());
}

template <std::size_t Size>
[[nodiscard]] std::span<const std::byte> bytes(
    const std::array<std::byte, Size>& data) noexcept {
    return std::span<const std::byte>(data.data(), data.size());
}

template <typename Decode>
void expect_truncation_matrix(std::span<const std::byte> payload, Decode decode,
                              std::string_view label) {
    for (std::size_t size = 0; size < payload.size(); ++size) {
        const auto result = decode(payload.first(size));
        expect_equal(result.error, InputProtocolError::MalformedPayload, label);
    }
    std::vector<std::byte> with_trailing(payload.begin(), payload.end());
    with_trailing.push_back(byte(0xAA));
    const auto trailing = decode(bytes(with_trailing));
    expect_equal(trailing.error, InputProtocolError::TrailingBytes, label);
}

[[nodiscard]] InputMessageHeader header(InputMessageType message_type,
                                        InputDeviceKind device_kind,
                                        std::uint16_t slot) noexcept {
    return InputMessageHeader{
        .input_version = 1,
        .message_type = message_type,
        .device_kind = device_kind,
        .flags = 0,
        .device_slot = slot,
    };
}

void test_common_header_golden() {
    InputMessageHeaderWireBytes output{};
    const auto encoded = warpnect::scl::encode_input_message_header(
        header(InputMessageType::GamepadState, InputDeviceKind::Gamepad, 0x1234), output);

    const InputMessageHeaderWireBytes expected{
        byte(0x01), byte(0x06), byte(0x04), byte(0x00),
        byte(0x12), byte(0x34), byte(0x00), byte(0x00),
    };
    expect(encoded.ok(), "input common header encodes");
    expect(output == expected, "input common header golden bytes");

    auto decoded = warpnect::scl::decode_input_message_header(output);
    expect(decoded.ok(), "input common header decodes");
    expect_equal(decoded.header.message_type, InputMessageType::GamepadState,
                 "input common message type");
    expect_equal(decoded.header.device_kind, InputDeviceKind::Gamepad,
                 "input common device kind");
    expect_equal(decoded.header.device_slot, static_cast<std::uint16_t>(0x1234),
                 "input common slot");

    output[3] = byte(0x01);
    expect_equal(warpnect::scl::decode_input_message_header(output).error,
                 InputProtocolError::InvalidCommonFlags,
                 "nonzero input common flags reject");
    output[3] = byte(0x00);
    output[7] = byte(0x01);
    expect_equal(warpnect::scl::decode_input_message_header(output).error,
                 InputProtocolError::ReservedFieldNonZero,
                 "nonzero input common reserved reject");
}

[[nodiscard]] InputKeyWireBytes make_key_payload() {
    InputKeyWireBytes output{};
    const InputKeyEvent event{
        .header = header(InputMessageType::Key, InputDeviceKind::Keyboard, 2),
        .usage_page = 0x0007,
        .usage_id = 0x0004,
        .action = InputKeyAction::Down,
        .repeat_count = 0,
        .modifier_mask = 0x0021,
    };
    const auto encoded = warpnect::scl::encode_input_key(event, output);
    expect(encoded.ok(), "key encodes");
    return output;
}

void test_key_golden_and_malformed() {
    const auto output = make_key_payload();
    const InputKeyWireBytes expected{
        byte(0x01), byte(0x01), byte(0x01), byte(0x00), byte(0x00),
        byte(0x02), byte(0x00), byte(0x00), byte(0x00), byte(0x07),
        byte(0x00), byte(0x04), byte(0x01), byte(0x00), byte(0x00),
        byte(0x00), byte(0x00), byte(0x21), byte(0x00), byte(0x00),
    };
    expect(output == expected, "key golden bytes");
    const auto decoded = warpnect::scl::decode_input_key(output);
    expect(decoded.ok(), "key decodes");
    expect_equal(decoded.event.usage_page, static_cast<std::uint16_t>(0x0007),
                 "key usage page");
    expect_equal(decoded.event.usage_id, static_cast<std::uint16_t>(0x0004),
                 "key usage id");
    expect_equal(decoded.event.action, InputKeyAction::Down, "key action");
    expect_equal(decoded.event.modifier_mask, static_cast<std::uint16_t>(0x0021),
                 "key modifier mask");

    auto bad = output;
    bad[12] = byte(0x09);
    expect_equal(warpnect::scl::decode_input_key(bad).error,
                 InputProtocolError::InvalidKeyAction, "invalid key action");
    bad = output;
    bad[12] = byte(0x02);
    bad[15] = byte(0x01);
    expect_equal(warpnect::scl::decode_input_key(bad).error,
                 InputProtocolError::InvalidKeyAction, "key up repeat rejects");
    bad = output;
    bad[16] = byte(0x01);
    expect_equal(warpnect::scl::decode_input_key(bad).error,
                 InputProtocolError::InvalidModifierMask, "reserved modifier bits reject");
    bad = output;
    bad[18] = byte(0x01);
    expect_equal(warpnect::scl::decode_input_key(bad).error,
                 InputProtocolError::ReservedFieldNonZero, "key reserved rejects");
    expect_truncation_matrix(bytes(output), warpnect::scl::decode_input_key, "key length");
}

[[nodiscard]] InputTouchFrame make_touch_frame() {
    InputTouchFrame frame{};
    frame.header = header(InputMessageType::TouchFrame, InputDeviceKind::Touchscreen, 3);
    frame.action = InputTouchAction::PointerDown;
    frame.action_pointer_id = 7;
    frame.pointer_count = 2;
    frame.contacts[0] = InputTouchContact{
        .pointer_id = 7,
        .tool_type = InputTouchToolType::Finger,
        .pointer_flags = 0x0003,
        .x_normalized = 0,
        .y_normalized = 0xFFFF,
        .pressure = 0x4000,
        .size = 0x2000,
    };
    frame.contacts[1] = InputTouchContact{
        .pointer_id = 3,
        .tool_type = InputTouchToolType::Stylus,
        .pointer_flags = 0x0001,
        .x_normalized = 0x8000,
        .y_normalized = 0x7FFF,
        .pressure = 0x1234,
        .size = 0,
    };
    return frame;
}

void test_touch_golden_max_and_validation() {
    std::array<std::byte, 36> output{};
    const auto encoded = warpnect::scl::encode_input_touch_frame(make_touch_frame(), output);
    const std::array<std::byte, 36> expected{
        byte(0x01), byte(0x02), byte(0x02), byte(0x00), byte(0x00),
        byte(0x03), byte(0x00), byte(0x00), byte(0x05), byte(0x07),
        byte(0x02), byte(0x00), byte(0x07), byte(0x01), byte(0x00),
        byte(0x03), byte(0x00), byte(0x00), byte(0xFF), byte(0xFF),
        byte(0x40), byte(0x00), byte(0x20), byte(0x00), byte(0x03),
        byte(0x02), byte(0x00), byte(0x01), byte(0x80), byte(0x00),
        byte(0x7F), byte(0xFF), byte(0x12), byte(0x34), byte(0x00),
        byte(0x00),
    };
    expect(encoded.ok(), "touch frame encodes");
    expect_equal(encoded.bytes_written, expected.size(), "touch encoded size");
    expect(output == expected, "touch frame golden bytes");
    const auto decoded = warpnect::scl::decode_input_touch_frame(output);
    expect(decoded.ok(), "touch frame decodes");
    expect_equal(decoded.frame.pointer_count, static_cast<std::uint8_t>(2),
                 "touch pointer count");
    expect_equal(decoded.frame.contacts[0], make_touch_frame().contacts[0],
                 "touch first contact");
    expect_equal(decoded.frame.contacts[1], make_touch_frame().contacts[1],
                 "touch second contact");

    InputTouchFrame max_frame{};
    max_frame.header = header(InputMessageType::TouchFrame, InputDeviceKind::Touchpad, 9);
    max_frame.action = InputTouchAction::Move;
    max_frame.action_pointer_id = warpnect::scl::kInputNoActionPointerId;
    max_frame.pointer_count = 32;
    for (std::uint8_t i = 0; i < max_frame.pointer_count; ++i) {
        max_frame.contacts[i] = InputTouchContact{
            .pointer_id = i,
            .tool_type = InputTouchToolType::Finger,
            .x_normalized = static_cast<std::uint16_t>(i * 1000U),
            .y_normalized = static_cast<std::uint16_t>(0xFFFFU - i),
        };
    }
    std::array<std::byte, 396> max_payload{};
    const auto max_encoded = warpnect::scl::encode_input_touch_frame(max_frame, max_payload);
    expect(max_encoded.ok(), "32-contact touch frame encodes");
    expect_equal(max_encoded.bytes_written, static_cast<std::size_t>(396),
                 "32-contact touch size");
    expect(warpnect::scl::decode_input_touch_frame(max_payload).ok(),
           "32-contact touch frame decodes");

    auto bad = make_touch_frame();
    bad.contacts[1].pointer_id = bad.contacts[0].pointer_id;
    expect_equal(warpnect::scl::validate_input_touch_frame(bad).error,
                 InputProtocolError::DuplicatePointerId, "duplicate touch id rejects");
    bad = make_touch_frame();
    bad.action_pointer_id = 8;
    expect_equal(warpnect::scl::validate_input_touch_frame(bad).error,
                 InputProtocolError::InvalidActionPointer,
                 "transition pointer must be present");
    bad = make_touch_frame();
    bad.action = InputTouchAction::Move;
    bad.action_pointer_id = 7;
    expect_equal(warpnect::scl::validate_input_touch_frame(bad).error,
                 InputProtocolError::InvalidActionPointer,
                 "move action pointer must be sentinel");
    bad = make_touch_frame();
    bad.contacts[0].pointer_flags = 0;
    bad.contacts[0].pressure = 1;
    expect_equal(warpnect::scl::validate_input_touch_frame(bad).error,
                 InputProtocolError::InvalidPointerFlags,
                 "pressure requires validity flag");
    expect_truncation_matrix(bytes(output), warpnect::scl::decode_input_touch_frame,
                             "touch length");
}

void test_pointer_and_scroll_messages() {
    InputPointerAbsoluteWireBytes absolute_bytes{};
    const InputPointerAbsolute absolute{
        .header = header(InputMessageType::PointerAbsolute, InputDeviceKind::Stylus, 4),
        .x_normalized = 0,
        .y_normalized = 0xFFFF,
        .button_mask = 0x0005,
        .pointer_flags = 0x0001,
        .pressure = 0x8000,
    };
    expect(warpnect::scl::encode_input_pointer_absolute(absolute, absolute_bytes).ok(),
           "pointer absolute encodes");
    const auto decoded_absolute = warpnect::scl::decode_input_pointer_absolute(absolute_bytes);
    expect(decoded_absolute.ok(), "pointer absolute decodes");
    expect_equal(decoded_absolute.event.x_normalized, static_cast<std::uint16_t>(0),
                 "absolute x edge");
    expect_equal(decoded_absolute.event.y_normalized, static_cast<std::uint16_t>(0xFFFF),
                 "absolute y edge");
    auto absolute_bad = absolute;
    absolute_bad.button_mask = 0x0020;
    expect_equal(warpnect::scl::validate_input_pointer_absolute(absolute_bad).error,
                 InputProtocolError::InvalidPointerButtonMask,
                 "absolute reserved button rejects");

    InputPointerRelativeWireBytes relative_bytes{};
    const InputPointerRelative relative{
        .header = header(InputMessageType::PointerRelative, InputDeviceKind::Mouse, 5),
        .delta_x_q16_16 = 65536,
        .delta_y_q16_16 = -65536,
        .button_mask = 0x0010,
    };
    expect(warpnect::scl::encode_input_pointer_relative(relative, relative_bytes).ok(),
           "pointer relative encodes");
    const auto decoded_relative = warpnect::scl::decode_input_pointer_relative(relative_bytes);
    expect(decoded_relative.ok(), "pointer relative decodes");
    expect_equal(decoded_relative.event.delta_x_q16_16, 65536, "relative +1");
    expect_equal(decoded_relative.event.delta_y_q16_16, -65536, "relative -1");

    InputScrollWireBytes scroll_bytes{};
    const InputScroll scroll{
        .header = header(InputMessageType::Scroll, InputDeviceKind::Touchpad, 6),
        .horizontal_q8_8 = 128,
        .vertical_q8_8 = -256,
        .button_mask = 0x0001,
    };
    expect(warpnect::scl::encode_input_scroll(scroll, scroll_bytes).ok(),
           "scroll encodes");
    const auto decoded_scroll = warpnect::scl::decode_input_scroll(scroll_bytes);
    expect(decoded_scroll.ok(), "scroll decodes");
    expect_equal(decoded_scroll.event.horizontal_q8_8, static_cast<std::int16_t>(128),
                 "fractional scroll");
    expect_equal(decoded_scroll.event.vertical_q8_8, static_cast<std::int16_t>(-256),
                 "negative scroll");
    auto zero_scroll = scroll;
    zero_scroll.horizontal_q8_8 = 0;
    zero_scroll.vertical_q8_8 = 0;
    expect_equal(warpnect::scl::validate_input_scroll(zero_scroll).error,
                 InputProtocolError::MalformedPayload, "zero scroll rejects");
}

void test_gamepad_golden_and_state_roundtrip() {
    const InputGamepadState state{
        .header = header(InputMessageType::GamepadState, InputDeviceKind::Gamepad, 0x1234),
        .button_mask = 0x00010009,
        .left_x = -32767,
        .left_y = 0,
        .right_x = 32767,
        .right_y = 1234,
        .left_trigger = 0x1111,
        .right_trigger = 0xEEEE,
    };
    InputGamepadStateWireBytes output{};
    expect(warpnect::scl::encode_input_gamepad_state(state, output).ok(),
           "gamepad encodes");
    const InputGamepadStateWireBytes expected{
        byte(0x01), byte(0x06), byte(0x04), byte(0x00), byte(0x12),
        byte(0x34), byte(0x00), byte(0x00), byte(0x00), byte(0x01),
        byte(0x00), byte(0x09), byte(0x80), byte(0x01), byte(0x00),
        byte(0x00), byte(0x7F), byte(0xFF), byte(0x04), byte(0xD2),
        byte(0x11), byte(0x11), byte(0xEE), byte(0xEE), byte(0x00),
        byte(0x00), byte(0x00), byte(0x00),
    };
    expect(output == expected, "gamepad golden bytes");
    const auto decoded = warpnect::scl::decode_input_gamepad_state(output);
    expect(decoded.ok(), "gamepad decodes");
    expect_equal(decoded.state.left_x, static_cast<std::int16_t>(-32767),
                 "gamepad left x");
    expect_equal(decoded.state.right_x, static_cast<std::int16_t>(32767),
                 "gamepad right x");

    auto bad = state;
    bad.left_x = static_cast<std::int16_t>(-32768);
    expect_equal(warpnect::scl::validate_input_gamepad_state(bad).error,
                 InputProtocolError::InvalidGamepadAxis, "reserved gamepad axis rejects");
    bad = state;
    bad.button_mask = 0x00020000;
    expect_equal(warpnect::scl::validate_input_gamepad_state(bad).error,
                 InputProtocolError::InvalidGamepadButtonMask,
                 "reserved gamepad button rejects");

    for (std::uint16_t slot = 0; slot < 3; ++slot) {
        auto snapshot = state;
        snapshot.header.device_slot = slot;
        snapshot.left_trigger = static_cast<std::uint16_t>(slot * 1000U);
        InputGamepadStateWireBytes bytes_out{};
        expect(warpnect::scl::encode_input_gamepad_state(snapshot, bytes_out).ok(),
               "gamepad full-state snapshot encodes independently");
        expect(warpnect::scl::decode_input_gamepad_state(bytes_out).ok(),
               "gamepad full-state snapshot decodes independently");
    }
}

void test_reset_golden_and_validation() {
    const InputResetState this_device{
        .header = header(InputMessageType::ResetState, InputDeviceKind::Mouse, 4),
        .scope = InputResetScope::ThisDevice,
        .reason = InputResetReason::DeviceDisconnected,
    };
    InputResetStateWireBytes output{};
    expect(warpnect::scl::encode_input_reset_state(this_device, output).ok(),
           "this-device reset encodes");
    const InputResetStateWireBytes expected_this{
        byte(0x01), byte(0x07), byte(0x03), byte(0x00), byte(0x00), byte(0x04),
        byte(0x00), byte(0x00), byte(0x01), byte(0x02), byte(0x00), byte(0x00),
    };
    expect(output == expected_this, "this-device reset golden bytes");

    const InputResetState all_devices{
        .header = header(InputMessageType::ResetState, InputDeviceKind::Unknown, 0xFFFF),
        .scope = InputResetScope::AllDevices,
        .reason = InputResetReason::SessionStop,
    };
    expect(warpnect::scl::encode_input_reset_state(all_devices, output).ok(),
           "all-devices reset encodes");
    const InputResetStateWireBytes expected_all{
        byte(0x01), byte(0x07), byte(0x00), byte(0x00), byte(0xFF), byte(0xFF),
        byte(0x00), byte(0x00), byte(0x02), byte(0x01), byte(0x00), byte(0x00),
    };
    expect(output == expected_all, "all-devices reset golden bytes");
    expect(warpnect::scl::decode_input_reset_state(output).ok(),
           "all-devices reset decodes");

    auto bad = all_devices;
    bad.header.device_slot = 0;
    expect_equal(warpnect::scl::validate_input_reset_state(bad).error,
                 InputProtocolError::InvalidDeviceSlot,
                 "all-devices reset requires sentinel slot");
    bad = this_device;
    bad.header.device_slot = 0xFFFF;
    expect_equal(warpnect::scl::validate_input_reset_state(bad).error,
                 InputProtocolError::InvalidDeviceSlot,
                 "this-device reset rejects sentinel slot");
    bad = all_devices;
    bad.header.device_kind = InputDeviceKind::Keyboard;
    expect_equal(warpnect::scl::validate_input_reset_state(bad).error,
                 InputProtocolError::InvalidDeviceSlot,
                 "all-devices reset requires unknown device kind");
}

void test_message_device_compatibility_and_delivery_class() {
    InputKeyEvent key{};
    key.header = header(InputMessageType::Key, InputDeviceKind::Gamepad, 1);
    key.action = InputKeyAction::Down;
    expect_equal(warpnect::scl::validate_input_key(key).error,
                 InputProtocolError::UnsupportedInputDeviceKind,
                 "gamepad buttons are not key messages");

    InputPointerRelative relative{};
    relative.header =
        header(InputMessageType::PointerRelative, InputDeviceKind::Touchscreen, 1);
    expect_equal(warpnect::scl::validate_input_pointer_relative(relative).error,
                 InputProtocolError::UnsupportedInputDeviceKind,
                 "relative pointer strict device matrix");

    expect_equal(
        warpnect::scl::input_delivery_class(header(InputMessageType::GamepadState,
                                                  InputDeviceKind::Gamepad, 0)),
        InputDeliveryClass::FreshState,
        "gamepad delivery is fresh-state");
    expect_equal(
        warpnect::scl::input_delivery_class(header(InputMessageType::TouchFrame,
                                                  InputDeviceKind::Touchscreen, 0),
                                           InputTouchAction::PointerUp),
        InputDeliveryClass::CriticalTransition,
        "touch transition delivery is critical");
    expect_equal(
        warpnect::scl::input_delivery_class(header(InputMessageType::ResetState,
                                                  InputDeviceKind::Unknown, 0xFFFF)),
        InputDeliveryClass::Reset,
        "reset delivery class");
}

} // namespace

int main() {
    test_common_header_golden();
    test_key_golden_and_malformed();
    test_touch_golden_max_and_validation();
    test_pointer_and_scroll_messages();
    test_gamepad_golden_and_state_roundtrip();
    test_reset_golden_and_validation();
    test_message_device_compatibility_and_delivery_class();

    if (failures != 0) {
        std::cerr << failures << " input protocol test failure(s)\n";
        return 1;
    }
    std::cout << "Input protocol tests passed\n";
    return 0;
}
