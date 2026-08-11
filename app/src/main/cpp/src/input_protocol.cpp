#include "input_protocol.h"

#include <algorithm>

#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr InputProtocolStatus status(InputProtocolError error) noexcept {
    return InputProtocolStatus{.error = error};
}

[[nodiscard]] constexpr InputEncodeResult encode_error(InputProtocolError error) noexcept {
    return InputEncodeResult{.error = error};
}

[[nodiscard]] constexpr InputHeaderDecodeResult header_error(InputProtocolError error) noexcept {
    return InputHeaderDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputKeyDecodeResult key_error(InputProtocolError error) noexcept {
    return InputKeyDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputTouchFrameDecodeResult
touch_error(InputProtocolError error) noexcept {
    return InputTouchFrameDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputPointerAbsoluteDecodeResult
absolute_error(InputProtocolError error) noexcept {
    return InputPointerAbsoluteDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputPointerRelativeDecodeResult
relative_error(InputProtocolError error) noexcept {
    return InputPointerRelativeDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputScrollDecodeResult scroll_error(InputProtocolError error) noexcept {
    return InputScrollDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputGamepadStateDecodeResult
gamepad_error(InputProtocolError error) noexcept {
    return InputGamepadStateDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputResetStateDecodeResult reset_error(InputProtocolError error) noexcept {
    return InputResetStateDecodeResult{.error = error};
}

[[nodiscard]] constexpr InputMessageType decode_message_type(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputMessageType::Key):
        return InputMessageType::Key;
    case static_cast<std::uint8_t>(InputMessageType::TouchFrame):
        return InputMessageType::TouchFrame;
    case static_cast<std::uint8_t>(InputMessageType::PointerAbsolute):
        return InputMessageType::PointerAbsolute;
    case static_cast<std::uint8_t>(InputMessageType::PointerRelative):
        return InputMessageType::PointerRelative;
    case static_cast<std::uint8_t>(InputMessageType::Scroll):
        return InputMessageType::Scroll;
    case static_cast<std::uint8_t>(InputMessageType::GamepadState):
        return InputMessageType::GamepadState;
    case static_cast<std::uint8_t>(InputMessageType::ResetState):
        return InputMessageType::ResetState;
    default:
        return static_cast<InputMessageType>(static_cast<std::uint8_t>(value));
    }
}

[[nodiscard]] constexpr InputDeviceKind decode_device_kind(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputDeviceKind::Keyboard):
        return InputDeviceKind::Keyboard;
    case static_cast<std::uint8_t>(InputDeviceKind::Touchscreen):
        return InputDeviceKind::Touchscreen;
    case static_cast<std::uint8_t>(InputDeviceKind::Mouse):
        return InputDeviceKind::Mouse;
    case static_cast<std::uint8_t>(InputDeviceKind::Gamepad):
        return InputDeviceKind::Gamepad;
    case static_cast<std::uint8_t>(InputDeviceKind::Stylus):
        return InputDeviceKind::Stylus;
    case static_cast<std::uint8_t>(InputDeviceKind::Touchpad):
        return InputDeviceKind::Touchpad;
    case static_cast<std::uint8_t>(InputDeviceKind::Unknown):
        return InputDeviceKind::Unknown;
    default:
        return static_cast<InputDeviceKind>(static_cast<std::uint8_t>(value));
    }
}

[[nodiscard]] constexpr InputKeyAction decode_key_action(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputKeyAction::Down):
        return InputKeyAction::Down;
    case static_cast<std::uint8_t>(InputKeyAction::Up):
        return InputKeyAction::Up;
    default:
        return static_cast<InputKeyAction>(static_cast<std::uint8_t>(value));
    }
}

[[nodiscard]] constexpr InputTouchAction decode_touch_action(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputTouchAction::Down):
        return InputTouchAction::Down;
    case static_cast<std::uint8_t>(InputTouchAction::Up):
        return InputTouchAction::Up;
    case static_cast<std::uint8_t>(InputTouchAction::Move):
        return InputTouchAction::Move;
    case static_cast<std::uint8_t>(InputTouchAction::Cancel):
        return InputTouchAction::Cancel;
    case static_cast<std::uint8_t>(InputTouchAction::PointerDown):
        return InputTouchAction::PointerDown;
    case static_cast<std::uint8_t>(InputTouchAction::PointerUp):
        return InputTouchAction::PointerUp;
    default:
        return static_cast<InputTouchAction>(static_cast<std::uint8_t>(value));
    }
}

[[nodiscard]] constexpr InputTouchToolType decode_tool_type(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputTouchToolType::Finger):
        return InputTouchToolType::Finger;
    case static_cast<std::uint8_t>(InputTouchToolType::Stylus):
        return InputTouchToolType::Stylus;
    case static_cast<std::uint8_t>(InputTouchToolType::Eraser):
        return InputTouchToolType::Eraser;
    case static_cast<std::uint8_t>(InputTouchToolType::Mouse):
        return InputTouchToolType::Mouse;
    case static_cast<std::uint8_t>(InputTouchToolType::Unknown):
        return InputTouchToolType::Unknown;
    default:
        return static_cast<InputTouchToolType>(static_cast<std::uint8_t>(value));
    }
}

[[nodiscard]] constexpr InputResetScope decode_reset_scope(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputResetScope::ThisDevice):
        return InputResetScope::ThisDevice;
    case static_cast<std::uint8_t>(InputResetScope::AllDevices):
        return InputResetScope::AllDevices;
    default:
        return static_cast<InputResetScope>(static_cast<std::uint8_t>(value));
    }
}

[[nodiscard]] constexpr InputResetReason decode_reset_reason(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(InputResetReason::Unknown):
        return InputResetReason::Unknown;
    case static_cast<std::uint8_t>(InputResetReason::SessionStop):
        return InputResetReason::SessionStop;
    case static_cast<std::uint8_t>(InputResetReason::DeviceDisconnected):
        return InputResetReason::DeviceDisconnected;
    case static_cast<std::uint8_t>(InputResetReason::FocusLost):
        return InputResetReason::FocusLost;
    case static_cast<std::uint8_t>(InputResetReason::ErrorRecovery):
        return InputResetReason::ErrorRecovery;
    case static_cast<std::uint8_t>(InputResetReason::UserRequest):
        return InputResetReason::UserRequest;
    default:
        return InputResetReason::Unknown;
    }
}

[[nodiscard]] constexpr bool is_defined_reset_reason(std::uint8_t value) noexcept {
    return value <= static_cast<std::uint8_t>(InputResetReason::UserRequest);
}

[[nodiscard]] constexpr bool uses_transition_pointer(InputTouchAction action) noexcept {
    return action == InputTouchAction::Down || action == InputTouchAction::Up ||
           action == InputTouchAction::PointerDown || action == InputTouchAction::PointerUp;
}

[[nodiscard]] constexpr bool is_touch_device_kind(InputDeviceKind kind) noexcept {
    return kind == InputDeviceKind::Touchscreen || kind == InputDeviceKind::Touchpad ||
           kind == InputDeviceKind::Stylus;
}

[[nodiscard]] constexpr bool is_absolute_pointer_device_kind(InputDeviceKind kind) noexcept {
    return kind == InputDeviceKind::Mouse || kind == InputDeviceKind::Stylus ||
           kind == InputDeviceKind::Touchpad;
}

[[nodiscard]] constexpr bool is_relative_pointer_device_kind(InputDeviceKind kind) noexcept {
    return kind == InputDeviceKind::Mouse || kind == InputDeviceKind::Touchpad;
}

[[nodiscard]] InputProtocolError exact_size(std::size_t actual,
                                            std::size_t expected) noexcept {
    if (actual == expected) {
        return InputProtocolError::None;
    }
    return actual < expected ? InputProtocolError::MalformedPayload
                             : InputProtocolError::TrailingBytes;
}

[[nodiscard]] bool read_i16_be(std::span<const std::byte> input, std::size_t offset,
                               std::int16_t& value) noexcept {
    std::uint16_t unsigned_value = 0;
    if (!internal::read_u16_be(input, offset, unsigned_value)) {
        return false;
    }
    value = static_cast<std::int16_t>(unsigned_value);
    return true;
}

[[nodiscard]] bool read_i32_be(std::span<const std::byte> input, std::size_t offset,
                               std::int32_t& value) noexcept {
    std::uint32_t unsigned_value = 0;
    if (!internal::read_u32_be(input, offset, unsigned_value)) {
        return false;
    }
    value = static_cast<std::int32_t>(unsigned_value);
    return true;
}

[[nodiscard]] bool write_i16_be(std::int16_t value, std::span<std::byte> output,
                                std::size_t offset) noexcept {
    return internal::write_u16_be(static_cast<std::uint16_t>(value), output, offset);
}

[[nodiscard]] bool write_i32_be(std::int32_t value, std::span<std::byte> output,
                                std::size_t offset) noexcept {
    return internal::write_u32_be(static_cast<std::uint32_t>(value), output, offset);
}

[[nodiscard]] InputProtocolError decode_common_header(std::span<const std::byte> payload,
                                                      InputMessageHeader& header) noexcept {
    if (payload.size() < kInputMessageHeaderWireSize) {
        return InputProtocolError::MalformedPayload;
    }

    header.input_version = static_cast<std::uint8_t>(payload[kInputMessageVersionOffset]);
    header.message_type = decode_message_type(payload[kInputMessageTypeOffset]);
    header.device_kind = decode_device_kind(payload[kInputDeviceKindOffset]);
    header.flags = static_cast<std::uint8_t>(payload[kInputCommonFlagsOffset]);
    if (!internal::read_u16_be(payload, kInputDeviceSlotOffset, header.device_slot)) {
        return InputProtocolError::MalformedPayload;
    }
    if (payload[kInputCommonReservedOffset] != std::byte{0} ||
        payload[kInputCommonReservedOffset + 1] != std::byte{0}) {
        return InputProtocolError::ReservedFieldNonZero;
    }
    return validate_input_message_header(header).error;
}

void write_common_header_unchecked(const InputMessageHeader& header,
                                   std::span<std::byte> output) noexcept {
    output[kInputMessageVersionOffset] = static_cast<std::byte>(header.input_version);
    output[kInputMessageTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.message_type));
    output[kInputDeviceKindOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.device_kind));
    output[kInputCommonFlagsOffset] = static_cast<std::byte>(header.flags);
    (void)internal::write_u16_be(header.device_slot, output, kInputDeviceSlotOffset);
    output[kInputCommonReservedOffset] = std::byte{0};
    output[kInputCommonReservedOffset + 1] = std::byte{0};
}

[[nodiscard]] InputProtocolError write_checked_header(const InputMessageHeader& header,
                                                      std::span<std::byte> output) noexcept {
    if (output.size() < kInputMessageHeaderWireSize) {
        return InputProtocolError::MalformedPayload;
    }
    const InputProtocolStatus validation = validate_input_message_header(header);
    if (!validation.ok()) {
        return validation.error;
    }
    write_common_header_unchecked(header, output);
    return InputProtocolError::None;
}

[[nodiscard]] bool action_pointer_present(const InputTouchFrame& frame) noexcept {
    for (std::uint8_t i = 0; i < frame.pointer_count; ++i) {
        if (frame.contacts[i].pointer_id == frame.action_pointer_id) {
            return true;
        }
    }
    return false;
}

[[nodiscard]] InputProtocolError validate_touch_contact(
    const InputTouchContact& contact) noexcept {
    if (contact.pointer_id >= kInputMaxTouchContacts) {
        return InputProtocolError::InvalidActionPointer;
    }
    if (!is_input_touch_tool_type_defined(contact.tool_type)) {
        return InputProtocolError::InvalidToolType;
    }
    if ((contact.pointer_flags & ~kInputTouchPointerFlagsAllowed) != 0) {
        return InputProtocolError::InvalidPointerFlags;
    }
    if ((contact.pointer_flags & kInputTouchPressureValidFlag) == 0 && contact.pressure != 0) {
        return InputProtocolError::InvalidPointerFlags;
    }
    if ((contact.pointer_flags & kInputTouchSizeValidFlag) == 0 && contact.size != 0) {
        return InputProtocolError::InvalidPointerFlags;
    }
    return InputProtocolError::None;
}

void encode_touch_contact(const InputTouchContact& contact, std::span<std::byte> output,
                          std::size_t offset) noexcept {
    output[offset] = static_cast<std::byte>(contact.pointer_id);
    output[offset + 1] =
        static_cast<std::byte>(static_cast<std::uint8_t>(contact.tool_type));
    (void)internal::write_u16_be(contact.pointer_flags, output, offset + 2);
    (void)internal::write_u16_be(contact.x_normalized, output, offset + 4);
    (void)internal::write_u16_be(contact.y_normalized, output, offset + 6);
    (void)internal::write_u16_be(contact.pressure, output, offset + 8);
    (void)internal::write_u16_be(contact.size, output, offset + 10);
}

[[nodiscard]] bool decode_touch_contact(std::span<const std::byte> payload, std::size_t offset,
                                        InputTouchContact& contact) noexcept {
    contact.pointer_id = static_cast<std::uint8_t>(payload[offset]);
    contact.tool_type = decode_tool_type(payload[offset + 1]);
    return internal::read_u16_be(payload, offset + 2, contact.pointer_flags) &&
           internal::read_u16_be(payload, offset + 4, contact.x_normalized) &&
           internal::read_u16_be(payload, offset + 6, contact.y_normalized) &&
           internal::read_u16_be(payload, offset + 8, contact.pressure) &&
           internal::read_u16_be(payload, offset + 10, contact.size);
}

} // namespace

std::string_view input_protocol_error_name(InputProtocolError error) noexcept {
    switch (error) {
    case InputProtocolError::None:
        return "None";
    case InputProtocolError::UnsupportedInputVersion:
        return "UnsupportedInputVersion";
    case InputProtocolError::UnsupportedInputMessageType:
        return "UnsupportedInputMessageType";
    case InputProtocolError::UnsupportedInputDeviceKind:
        return "UnsupportedInputDeviceKind";
    case InputProtocolError::InvalidCommonFlags:
        return "InvalidCommonFlags";
    case InputProtocolError::ReservedFieldNonZero:
        return "ReservedFieldNonZero";
    case InputProtocolError::InvalidDeviceSlot:
        return "InvalidDeviceSlot";
    case InputProtocolError::InvalidKeyAction:
        return "InvalidKeyAction";
    case InputProtocolError::InvalidModifierMask:
        return "InvalidModifierMask";
    case InputProtocolError::InvalidTouchAction:
        return "InvalidTouchAction";
    case InputProtocolError::InvalidPointerCount:
        return "InvalidPointerCount";
    case InputProtocolError::DuplicatePointerId:
        return "DuplicatePointerId";
    case InputProtocolError::InvalidActionPointer:
        return "InvalidActionPointer";
    case InputProtocolError::InvalidToolType:
        return "InvalidToolType";
    case InputProtocolError::InvalidPointerFlags:
        return "InvalidPointerFlags";
    case InputProtocolError::InvalidPointerButtonMask:
        return "InvalidPointerButtonMask";
    case InputProtocolError::InvalidGamepadButtonMask:
        return "InvalidGamepadButtonMask";
    case InputProtocolError::InvalidGamepadAxis:
        return "InvalidGamepadAxis";
    case InputProtocolError::InvalidResetScope:
        return "InvalidResetScope";
    case InputProtocolError::InvalidResetReason:
        return "InvalidResetReason";
    case InputProtocolError::MalformedPayload:
        return "MalformedPayload";
    case InputProtocolError::TrailingBytes:
        return "TrailingBytes";
    case InputProtocolError::ArithmeticOverflow:
        return "ArithmeticOverflow";
    }
    return "UnknownInputProtocolError";
}

InputDeliveryClass input_delivery_class(const InputMessageHeader& header,
                                        InputTouchAction touch_action,
                                        InputKeyAction key_action) noexcept {
    switch (header.message_type) {
    case InputMessageType::Key:
        (void)key_action;
        return InputDeliveryClass::CriticalTransition;
    case InputMessageType::TouchFrame:
        return uses_transition_pointer(touch_action) || touch_action == InputTouchAction::Cancel
                   ? InputDeliveryClass::CriticalTransition
                   : InputDeliveryClass::FreshState;
    case InputMessageType::PointerAbsolute:
    case InputMessageType::PointerRelative:
    case InputMessageType::Scroll:
    case InputMessageType::GamepadState:
        return InputDeliveryClass::FreshState;
    case InputMessageType::ResetState:
        return InputDeliveryClass::Reset;
    case InputMessageType::Unknown:
        return InputDeliveryClass::FreshState;
    }
    return InputDeliveryClass::FreshState;
}

InputProtocolStatus validate_input_message_header(const InputMessageHeader& header) noexcept {
    if (header.input_version != kInputPayloadVersion) {
        return status(InputProtocolError::UnsupportedInputVersion);
    }
    if (!is_input_message_type_defined(header.message_type) ||
        header.message_type == InputMessageType::Unknown) {
        return status(InputProtocolError::UnsupportedInputMessageType);
    }
    if (!is_input_device_kind_defined(header.device_kind)) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if (header.flags != 0) {
        return status(InputProtocolError::InvalidCommonFlags);
    }
    if (header.message_type != InputMessageType::ResetState) {
        if (header.device_kind == InputDeviceKind::Unknown) {
            return status(InputProtocolError::UnsupportedInputDeviceKind);
        }
        if (!is_valid_input_device_slot(header.device_slot)) {
            return status(InputProtocolError::InvalidDeviceSlot);
        }
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_key(const InputKeyEvent& event) noexcept {
    InputProtocolStatus common = validate_input_message_header(event.header);
    if (!common.ok()) {
        return common;
    }
    if (event.header.message_type != InputMessageType::Key ||
        event.header.device_kind != InputDeviceKind::Keyboard) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if (event.action != InputKeyAction::Down && event.action != InputKeyAction::Up) {
        return status(InputProtocolError::InvalidKeyAction);
    }
    if (event.action == InputKeyAction::Up && event.repeat_count != 0) {
        return status(InputProtocolError::InvalidKeyAction);
    }
    if ((event.modifier_mask & ~kInputModifierMaskAllowed) != 0) {
        return status(InputProtocolError::InvalidModifierMask);
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_touch_frame(const InputTouchFrame& frame) noexcept {
    InputProtocolStatus common = validate_input_message_header(frame.header);
    if (!common.ok()) {
        return common;
    }
    if (frame.header.message_type != InputMessageType::TouchFrame ||
        !is_touch_device_kind(frame.header.device_kind)) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if (frame.action == InputTouchAction::Unknown) {
        return status(InputProtocolError::InvalidTouchAction);
    }
    if (frame.pointer_count > kInputMaxTouchContacts ||
        (frame.pointer_count == 0 && frame.action != InputTouchAction::Cancel)) {
        return status(InputProtocolError::InvalidPointerCount);
    }
    std::array<bool, kInputMaxTouchContacts> seen{};
    for (std::uint8_t i = 0; i < frame.pointer_count; ++i) {
        const InputTouchContact& contact = frame.contacts[i];
        const InputProtocolError contact_error = validate_touch_contact(contact);
        if (contact_error != InputProtocolError::None) {
            return status(contact_error);
        }
        if (seen[contact.pointer_id]) {
            return status(InputProtocolError::DuplicatePointerId);
        }
        seen[contact.pointer_id] = true;
    }
    if (uses_transition_pointer(frame.action)) {
        if (frame.action_pointer_id == kInputNoActionPointerId ||
            !action_pointer_present(frame)) {
            return status(InputProtocolError::InvalidActionPointer);
        }
    } else if (frame.action_pointer_id != kInputNoActionPointerId) {
        return status(InputProtocolError::InvalidActionPointer);
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_pointer_absolute(
    const InputPointerAbsolute& event) noexcept {
    InputProtocolStatus common = validate_input_message_header(event.header);
    if (!common.ok()) {
        return common;
    }
    if (event.header.message_type != InputMessageType::PointerAbsolute ||
        !is_absolute_pointer_device_kind(event.header.device_kind)) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if ((event.button_mask & ~kInputPointerButtonMaskAllowed) != 0) {
        return status(InputProtocolError::InvalidPointerButtonMask);
    }
    if ((event.pointer_flags & ~kInputPointerAbsoluteFlagsAllowed) != 0) {
        return status(InputProtocolError::InvalidPointerFlags);
    }
    if ((event.pointer_flags & kInputPointerAbsolutePressureValidFlag) == 0 &&
        event.pressure != 0) {
        return status(InputProtocolError::InvalidPointerFlags);
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_pointer_relative(
    const InputPointerRelative& event) noexcept {
    InputProtocolStatus common = validate_input_message_header(event.header);
    if (!common.ok()) {
        return common;
    }
    if (event.header.message_type != InputMessageType::PointerRelative ||
        !is_relative_pointer_device_kind(event.header.device_kind)) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if ((event.button_mask & ~kInputPointerButtonMaskAllowed) != 0) {
        return status(InputProtocolError::InvalidPointerButtonMask);
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_scroll(const InputScroll& event) noexcept {
    InputProtocolStatus common = validate_input_message_header(event.header);
    if (!common.ok()) {
        return common;
    }
    if (event.header.message_type != InputMessageType::Scroll ||
        !is_relative_pointer_device_kind(event.header.device_kind)) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if ((event.button_mask & ~kInputPointerButtonMaskAllowed) != 0) {
        return status(InputProtocolError::InvalidPointerButtonMask);
    }
    if (event.horizontal_q8_8 == 0 && event.vertical_q8_8 == 0) {
        return status(InputProtocolError::MalformedPayload);
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_gamepad_state(const InputGamepadState& state) noexcept {
    InputProtocolStatus common = validate_input_message_header(state.header);
    if (!common.ok()) {
        return common;
    }
    if (state.header.message_type != InputMessageType::GamepadState ||
        state.header.device_kind != InputDeviceKind::Gamepad) {
        return status(InputProtocolError::UnsupportedInputDeviceKind);
    }
    if ((state.button_mask & ~kInputGamepadButtonMaskAllowed) != 0) {
        return status(InputProtocolError::InvalidGamepadButtonMask);
    }
    if (!is_valid_gamepad_axis(state.left_x) || !is_valid_gamepad_axis(state.left_y) ||
        !is_valid_gamepad_axis(state.right_x) || !is_valid_gamepad_axis(state.right_y)) {
        return status(InputProtocolError::InvalidGamepadAxis);
    }
    return status(InputProtocolError::None);
}

InputProtocolStatus validate_input_reset_state(const InputResetState& reset) noexcept {
    InputProtocolStatus common = validate_input_message_header(reset.header);
    if (!common.ok()) {
        return common;
    }
    if (reset.header.message_type != InputMessageType::ResetState) {
        return status(InputProtocolError::UnsupportedInputMessageType);
    }
    if (reset.scope != InputResetScope::ThisDevice &&
        reset.scope != InputResetScope::AllDevices) {
        return status(InputProtocolError::InvalidResetScope);
    }
    if (!is_defined_reset_reason(static_cast<std::uint8_t>(reset.reason))) {
        return status(InputProtocolError::InvalidResetReason);
    }
    if (reset.scope == InputResetScope::AllDevices) {
        if (reset.header.device_kind != InputDeviceKind::Unknown ||
            reset.header.device_slot != kInputReservedDeviceSlot) {
            return status(InputProtocolError::InvalidDeviceSlot);
        }
    } else if (reset.header.device_kind == InputDeviceKind::Unknown ||
               !is_valid_input_device_slot(reset.header.device_slot)) {
        return status(InputProtocolError::InvalidDeviceSlot);
    }
    return status(InputProtocolError::None);
}

InputEncodeResult encode_input_message_header(const InputMessageHeader& header,
                                              std::span<std::byte> output) noexcept {
    const InputProtocolError error = write_checked_header(header, output);
    return error == InputProtocolError::None
               ? InputEncodeResult{.bytes_written = kInputMessageHeaderWireSize}
               : encode_error(error);
}

InputEncodeResult encode_input_key(const InputKeyEvent& event,
                                   std::span<std::byte> output) noexcept {
    if (output.size() < kInputKeyWireSize) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_key(event);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(event.header, output);
    (void)internal::write_u16_be(event.usage_page, output, kInputKeyUsagePageOffset);
    (void)internal::write_u16_be(event.usage_id, output, kInputKeyUsageIdOffset);
    output[kInputKeyActionOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(event.action));
    output[kInputKeyReserved0Offset] = std::byte{0};
    (void)internal::write_u16_be(event.repeat_count, output, kInputKeyRepeatCountOffset);
    (void)internal::write_u16_be(event.modifier_mask, output, kInputKeyModifierMaskOffset);
    output[kInputKeyReserved1Offset] = std::byte{0};
    output[kInputKeyReserved1Offset + 1] = std::byte{0};
    return InputEncodeResult{.bytes_written = kInputKeyWireSize};
}

InputEncodeResult encode_input_touch_frame(const InputTouchFrame& frame,
                                           std::span<std::byte> output) noexcept {
    const std::size_t expected = input_touch_frame_wire_size(frame.pointer_count);
    if (expected < kInputTouchFramePrefixWireSize) {
        return encode_error(InputProtocolError::ArithmeticOverflow);
    }
    if (output.size() < expected) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_touch_frame(frame);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(frame.header, output);
    output[kInputTouchActionOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(frame.action));
    output[kInputTouchActionPointerIdOffset] = static_cast<std::byte>(frame.action_pointer_id);
    output[kInputTouchPointerCountOffset] = static_cast<std::byte>(frame.pointer_count);
    output[kInputTouchReservedOffset] = std::byte{0};
    for (std::uint8_t i = 0; i < frame.pointer_count; ++i) {
        encode_touch_contact(frame.contacts[i], output,
                             kInputTouchFramePrefixWireSize +
                                 static_cast<std::size_t>(i) * kInputTouchContactWireSize);
    }
    return InputEncodeResult{.bytes_written = expected};
}

InputEncodeResult encode_input_pointer_absolute(const InputPointerAbsolute& event,
                                                std::span<std::byte> output) noexcept {
    if (output.size() < kInputPointerAbsoluteWireSize) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_pointer_absolute(event);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(event.header, output);
    (void)internal::write_u16_be(event.x_normalized, output, kInputPointerAbsoluteXOffset);
    (void)internal::write_u16_be(event.y_normalized, output, kInputPointerAbsoluteYOffset);
    (void)internal::write_u16_be(event.button_mask, output,
                                 kInputPointerAbsoluteButtonMaskOffset);
    (void)internal::write_u16_be(event.pointer_flags, output,
                                 kInputPointerAbsoluteFlagsOffset);
    (void)internal::write_u16_be(event.pressure, output, kInputPointerAbsolutePressureOffset);
    output[kInputPointerAbsoluteReservedOffset] = std::byte{0};
    output[kInputPointerAbsoluteReservedOffset + 1] = std::byte{0};
    return InputEncodeResult{.bytes_written = kInputPointerAbsoluteWireSize};
}

InputEncodeResult encode_input_pointer_relative(const InputPointerRelative& event,
                                                std::span<std::byte> output) noexcept {
    if (output.size() < kInputPointerRelativeWireSize) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_pointer_relative(event);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(event.header, output);
    (void)write_i32_be(event.delta_x_q16_16, output, kInputPointerRelativeDxOffset);
    (void)write_i32_be(event.delta_y_q16_16, output, kInputPointerRelativeDyOffset);
    (void)internal::write_u16_be(event.button_mask, output,
                                 kInputPointerRelativeButtonMaskOffset);
    output[kInputPointerRelativeReservedOffset] = std::byte{0};
    output[kInputPointerRelativeReservedOffset + 1] = std::byte{0};
    return InputEncodeResult{.bytes_written = kInputPointerRelativeWireSize};
}

InputEncodeResult encode_input_scroll(const InputScroll& event,
                                      std::span<std::byte> output) noexcept {
    if (output.size() < kInputScrollWireSize) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_scroll(event);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(event.header, output);
    (void)write_i16_be(event.horizontal_q8_8, output, kInputScrollHorizontalOffset);
    (void)write_i16_be(event.vertical_q8_8, output, kInputScrollVerticalOffset);
    (void)internal::write_u16_be(event.button_mask, output, kInputScrollButtonMaskOffset);
    output[kInputScrollReservedOffset] = std::byte{0};
    output[kInputScrollReservedOffset + 1] = std::byte{0};
    return InputEncodeResult{.bytes_written = kInputScrollWireSize};
}

InputEncodeResult encode_input_gamepad_state(const InputGamepadState& state,
                                             std::span<std::byte> output) noexcept {
    if (output.size() < kInputGamepadStateWireSize) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_gamepad_state(state);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(state.header, output);
    (void)internal::write_u32_be(state.button_mask, output, kInputGamepadButtonMaskOffset);
    (void)write_i16_be(state.left_x, output, kInputGamepadLeftXOffset);
    (void)write_i16_be(state.left_y, output, kInputGamepadLeftYOffset);
    (void)write_i16_be(state.right_x, output, kInputGamepadRightXOffset);
    (void)write_i16_be(state.right_y, output, kInputGamepadRightYOffset);
    (void)internal::write_u16_be(state.left_trigger, output, kInputGamepadLeftTriggerOffset);
    (void)internal::write_u16_be(state.right_trigger, output, kInputGamepadRightTriggerOffset);
    output[kInputGamepadReservedOffset] = std::byte{0};
    output[kInputGamepadReservedOffset + 1] = std::byte{0};
    output[kInputGamepadReservedOffset + 2] = std::byte{0};
    output[kInputGamepadReservedOffset + 3] = std::byte{0};
    return InputEncodeResult{.bytes_written = kInputGamepadStateWireSize};
}

InputEncodeResult encode_input_reset_state(const InputResetState& reset,
                                           std::span<std::byte> output) noexcept {
    if (output.size() < kInputResetStateWireSize) {
        return encode_error(InputProtocolError::MalformedPayload);
    }
    const InputProtocolStatus validation = validate_input_reset_state(reset);
    if (!validation.ok()) {
        return encode_error(validation.error);
    }
    write_common_header_unchecked(reset.header, output);
    output[kInputResetScopeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(reset.scope));
    output[kInputResetReasonOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(reset.reason));
    output[kInputResetReservedOffset] = std::byte{0};
    output[kInputResetReservedOffset + 1] = std::byte{0};
    return InputEncodeResult{.bytes_written = kInputResetStateWireSize};
}

InputHeaderDecodeResult decode_input_message_header(
    std::span<const std::byte> payload) noexcept {
    InputMessageHeader header{};
    const InputProtocolError error = decode_common_header(payload, header);
    if (error != InputProtocolError::None) {
        return header_error(error);
    }
    return InputHeaderDecodeResult{.header = header};
}

InputKeyDecodeResult decode_input_key(std::span<const std::byte> payload) noexcept {
    const InputProtocolError size_error = exact_size(payload.size(), kInputKeyWireSize);
    if (size_error != InputProtocolError::None) {
        return key_error(size_error);
    }
    InputKeyEvent event{};
    InputProtocolError error = decode_common_header(payload, event.header);
    if (error != InputProtocolError::None) {
        return key_error(error);
    }
    if (payload[kInputKeyReserved0Offset] != std::byte{0} ||
        payload[kInputKeyReserved1Offset] != std::byte{0} ||
        payload[kInputKeyReserved1Offset + 1] != std::byte{0}) {
        return key_error(InputProtocolError::ReservedFieldNonZero);
    }
    if (!internal::read_u16_be(payload, kInputKeyUsagePageOffset, event.usage_page) ||
        !internal::read_u16_be(payload, kInputKeyUsageIdOffset, event.usage_id) ||
        !internal::read_u16_be(payload, kInputKeyRepeatCountOffset, event.repeat_count) ||
        !internal::read_u16_be(payload, kInputKeyModifierMaskOffset, event.modifier_mask)) {
        return key_error(InputProtocolError::MalformedPayload);
    }
    event.action = decode_key_action(payload[kInputKeyActionOffset]);
    error = validate_input_key(event).error;
    return error == InputProtocolError::None ? InputKeyDecodeResult{.event = event}
                                             : key_error(error);
}

InputTouchFrameDecodeResult decode_input_touch_frame(
    std::span<const std::byte> payload) noexcept {
    if (payload.size() < kInputTouchFramePrefixWireSize) {
        return touch_error(InputProtocolError::MalformedPayload);
    }
    InputTouchFrame frame{};
    InputProtocolError error = decode_common_header(payload, frame.header);
    if (error != InputProtocolError::None) {
        return touch_error(error);
    }
    frame.action = decode_touch_action(payload[kInputTouchActionOffset]);
    frame.action_pointer_id = static_cast<std::uint8_t>(payload[kInputTouchActionPointerIdOffset]);
    frame.pointer_count = static_cast<std::uint8_t>(payload[kInputTouchPointerCountOffset]);
    if (payload[kInputTouchReservedOffset] != std::byte{0}) {
        return touch_error(InputProtocolError::ReservedFieldNonZero);
    }
    if (frame.pointer_count > kInputMaxTouchContacts) {
        return touch_error(InputProtocolError::InvalidPointerCount);
    }
    const std::size_t expected = input_touch_frame_wire_size(frame.pointer_count);
    const InputProtocolError size_error = exact_size(payload.size(), expected);
    if (size_error != InputProtocolError::None) {
        return touch_error(size_error);
    }
    for (std::uint8_t i = 0; i < frame.pointer_count; ++i) {
        if (!decode_touch_contact(payload,
                                  kInputTouchFramePrefixWireSize +
                                      static_cast<std::size_t>(i) * kInputTouchContactWireSize,
                                  frame.contacts[i])) {
            return touch_error(InputProtocolError::MalformedPayload);
        }
    }
    error = validate_input_touch_frame(frame).error;
    return error == InputProtocolError::None ? InputTouchFrameDecodeResult{.frame = frame}
                                             : touch_error(error);
}

InputPointerAbsoluteDecodeResult decode_input_pointer_absolute(
    std::span<const std::byte> payload) noexcept {
    const InputProtocolError size_error =
        exact_size(payload.size(), kInputPointerAbsoluteWireSize);
    if (size_error != InputProtocolError::None) {
        return absolute_error(size_error);
    }
    InputPointerAbsolute event{};
    InputProtocolError error = decode_common_header(payload, event.header);
    if (error != InputProtocolError::None) {
        return absolute_error(error);
    }
    if (payload[kInputPointerAbsoluteReservedOffset] != std::byte{0} ||
        payload[kInputPointerAbsoluteReservedOffset + 1] != std::byte{0}) {
        return absolute_error(InputProtocolError::ReservedFieldNonZero);
    }
    if (!internal::read_u16_be(payload, kInputPointerAbsoluteXOffset, event.x_normalized) ||
        !internal::read_u16_be(payload, kInputPointerAbsoluteYOffset, event.y_normalized) ||
        !internal::read_u16_be(payload, kInputPointerAbsoluteButtonMaskOffset,
                               event.button_mask) ||
        !internal::read_u16_be(payload, kInputPointerAbsoluteFlagsOffset,
                               event.pointer_flags) ||
        !internal::read_u16_be(payload, kInputPointerAbsolutePressureOffset,
                               event.pressure)) {
        return absolute_error(InputProtocolError::MalformedPayload);
    }
    error = validate_input_pointer_absolute(event).error;
    return error == InputProtocolError::None
               ? InputPointerAbsoluteDecodeResult{.event = event}
               : absolute_error(error);
}

InputPointerRelativeDecodeResult decode_input_pointer_relative(
    std::span<const std::byte> payload) noexcept {
    const InputProtocolError size_error =
        exact_size(payload.size(), kInputPointerRelativeWireSize);
    if (size_error != InputProtocolError::None) {
        return relative_error(size_error);
    }
    InputPointerRelative event{};
    InputProtocolError error = decode_common_header(payload, event.header);
    if (error != InputProtocolError::None) {
        return relative_error(error);
    }
    if (payload[kInputPointerRelativeReservedOffset] != std::byte{0} ||
        payload[kInputPointerRelativeReservedOffset + 1] != std::byte{0}) {
        return relative_error(InputProtocolError::ReservedFieldNonZero);
    }
    if (!read_i32_be(payload, kInputPointerRelativeDxOffset, event.delta_x_q16_16) ||
        !read_i32_be(payload, kInputPointerRelativeDyOffset, event.delta_y_q16_16) ||
        !internal::read_u16_be(payload, kInputPointerRelativeButtonMaskOffset,
                               event.button_mask)) {
        return relative_error(InputProtocolError::MalformedPayload);
    }
    error = validate_input_pointer_relative(event).error;
    return error == InputProtocolError::None
               ? InputPointerRelativeDecodeResult{.event = event}
               : relative_error(error);
}

InputScrollDecodeResult decode_input_scroll(std::span<const std::byte> payload) noexcept {
    const InputProtocolError size_error = exact_size(payload.size(), kInputScrollWireSize);
    if (size_error != InputProtocolError::None) {
        return scroll_error(size_error);
    }
    InputScroll event{};
    InputProtocolError error = decode_common_header(payload, event.header);
    if (error != InputProtocolError::None) {
        return scroll_error(error);
    }
    if (payload[kInputScrollReservedOffset] != std::byte{0} ||
        payload[kInputScrollReservedOffset + 1] != std::byte{0}) {
        return scroll_error(InputProtocolError::ReservedFieldNonZero);
    }
    if (!read_i16_be(payload, kInputScrollHorizontalOffset, event.horizontal_q8_8) ||
        !read_i16_be(payload, kInputScrollVerticalOffset, event.vertical_q8_8) ||
        !internal::read_u16_be(payload, kInputScrollButtonMaskOffset, event.button_mask)) {
        return scroll_error(InputProtocolError::MalformedPayload);
    }
    error = validate_input_scroll(event).error;
    return error == InputProtocolError::None ? InputScrollDecodeResult{.event = event}
                                             : scroll_error(error);
}

InputGamepadStateDecodeResult decode_input_gamepad_state(
    std::span<const std::byte> payload) noexcept {
    const InputProtocolError size_error =
        exact_size(payload.size(), kInputGamepadStateWireSize);
    if (size_error != InputProtocolError::None) {
        return gamepad_error(size_error);
    }
    InputGamepadState state{};
    InputProtocolError error = decode_common_header(payload, state.header);
    if (error != InputProtocolError::None) {
        return gamepad_error(error);
    }
    for (std::size_t i = 0; i < 4; ++i) {
        if (payload[kInputGamepadReservedOffset + i] != std::byte{0}) {
            return gamepad_error(InputProtocolError::ReservedFieldNonZero);
        }
    }
    if (!internal::read_u32_be(payload, kInputGamepadButtonMaskOffset, state.button_mask) ||
        !read_i16_be(payload, kInputGamepadLeftXOffset, state.left_x) ||
        !read_i16_be(payload, kInputGamepadLeftYOffset, state.left_y) ||
        !read_i16_be(payload, kInputGamepadRightXOffset, state.right_x) ||
        !read_i16_be(payload, kInputGamepadRightYOffset, state.right_y) ||
        !internal::read_u16_be(payload, kInputGamepadLeftTriggerOffset,
                               state.left_trigger) ||
        !internal::read_u16_be(payload, kInputGamepadRightTriggerOffset,
                               state.right_trigger)) {
        return gamepad_error(InputProtocolError::MalformedPayload);
    }
    error = validate_input_gamepad_state(state).error;
    return error == InputProtocolError::None
               ? InputGamepadStateDecodeResult{.state = state}
               : gamepad_error(error);
}

InputResetStateDecodeResult decode_input_reset_state(
    std::span<const std::byte> payload) noexcept {
    const InputProtocolError size_error = exact_size(payload.size(), kInputResetStateWireSize);
    if (size_error != InputProtocolError::None) {
        return reset_error(size_error);
    }
    InputResetState reset{};
    InputProtocolError error = decode_common_header(payload, reset.header);
    if (error != InputProtocolError::None) {
        return reset_error(error);
    }
    if (payload[kInputResetReservedOffset] != std::byte{0} ||
        payload[kInputResetReservedOffset + 1] != std::byte{0}) {
        return reset_error(InputProtocolError::ReservedFieldNonZero);
    }
    reset.scope = decode_reset_scope(payload[kInputResetScopeOffset]);
    const auto reason_wire = static_cast<std::uint8_t>(payload[kInputResetReasonOffset]);
    if (!is_defined_reset_reason(reason_wire)) {
        return reset_error(InputProtocolError::InvalidResetReason);
    }
    reset.reason = decode_reset_reason(payload[kInputResetReasonOffset]);
    error = validate_input_reset_state(reset).error;
    return error == InputProtocolError::None ? InputResetStateDecodeResult{.reset = reset}
                                             : reset_error(error);
}

} // namespace warpnect::scl
