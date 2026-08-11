#ifndef WARPNECT_SCL_INPUT_PROTOCOL_H_
#define WARPNECT_SCL_INPUT_PROTOCOL_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace warpnect::scl {

inline constexpr std::uint8_t kInputPayloadVersion = 1;

inline constexpr std::size_t kInputMessageHeaderWireSize = 8;
inline constexpr std::size_t kInputMessageVersionOffset = 0;
inline constexpr std::size_t kInputMessageTypeOffset = 1;
inline constexpr std::size_t kInputDeviceKindOffset = 2;
inline constexpr std::size_t kInputCommonFlagsOffset = 3;
inline constexpr std::size_t kInputDeviceSlotOffset = 4;
inline constexpr std::size_t kInputCommonReservedOffset = 6;

inline constexpr std::size_t kInputKeyWireSize = 20;
inline constexpr std::size_t kInputKeyUsagePageOffset = 8;
inline constexpr std::size_t kInputKeyUsageIdOffset = 10;
inline constexpr std::size_t kInputKeyActionOffset = 12;
inline constexpr std::size_t kInputKeyReserved0Offset = 13;
inline constexpr std::size_t kInputKeyRepeatCountOffset = 14;
inline constexpr std::size_t kInputKeyModifierMaskOffset = 16;
inline constexpr std::size_t kInputKeyReserved1Offset = 18;

inline constexpr std::size_t kInputTouchFramePrefixWireSize = 12;
inline constexpr std::size_t kInputTouchActionOffset = 8;
inline constexpr std::size_t kInputTouchActionPointerIdOffset = 9;
inline constexpr std::size_t kInputTouchPointerCountOffset = 10;
inline constexpr std::size_t kInputTouchReservedOffset = 11;
inline constexpr std::size_t kInputTouchContactWireSize = 12;
inline constexpr std::size_t kInputMaxTouchContacts = 32;
inline constexpr std::uint8_t kInputNoActionPointerId = 0xFF;

inline constexpr std::size_t kInputPointerAbsoluteWireSize = 20;
inline constexpr std::size_t kInputPointerAbsoluteXOffset = 8;
inline constexpr std::size_t kInputPointerAbsoluteYOffset = 10;
inline constexpr std::size_t kInputPointerAbsoluteButtonMaskOffset = 12;
inline constexpr std::size_t kInputPointerAbsoluteFlagsOffset = 14;
inline constexpr std::size_t kInputPointerAbsolutePressureOffset = 16;
inline constexpr std::size_t kInputPointerAbsoluteReservedOffset = 18;

inline constexpr std::size_t kInputPointerRelativeWireSize = 20;
inline constexpr std::size_t kInputPointerRelativeDxOffset = 8;
inline constexpr std::size_t kInputPointerRelativeDyOffset = 12;
inline constexpr std::size_t kInputPointerRelativeButtonMaskOffset = 16;
inline constexpr std::size_t kInputPointerRelativeReservedOffset = 18;

inline constexpr std::size_t kInputScrollWireSize = 16;
inline constexpr std::size_t kInputScrollHorizontalOffset = 8;
inline constexpr std::size_t kInputScrollVerticalOffset = 10;
inline constexpr std::size_t kInputScrollButtonMaskOffset = 12;
inline constexpr std::size_t kInputScrollReservedOffset = 14;

inline constexpr std::size_t kInputGamepadStateWireSize = 28;
inline constexpr std::size_t kInputGamepadButtonMaskOffset = 8;
inline constexpr std::size_t kInputGamepadLeftXOffset = 12;
inline constexpr std::size_t kInputGamepadLeftYOffset = 14;
inline constexpr std::size_t kInputGamepadRightXOffset = 16;
inline constexpr std::size_t kInputGamepadRightYOffset = 18;
inline constexpr std::size_t kInputGamepadLeftTriggerOffset = 20;
inline constexpr std::size_t kInputGamepadRightTriggerOffset = 22;
inline constexpr std::size_t kInputGamepadReservedOffset = 24;

inline constexpr std::size_t kInputResetStateWireSize = 12;
inline constexpr std::size_t kInputResetScopeOffset = 8;
inline constexpr std::size_t kInputResetReasonOffset = 9;
inline constexpr std::size_t kInputResetReservedOffset = 10;

inline constexpr std::uint16_t kInputPrimaryDeviceSlot = 0;
inline constexpr std::uint16_t kInputReservedDeviceSlot = 0xFFFF;

inline constexpr std::uint16_t kInputModifierMaskAllowed = 0x00FF;
inline constexpr std::uint16_t kInputPointerButtonMaskAllowed = 0x001F;
inline constexpr std::uint16_t kInputTouchPointerFlagsAllowed = 0x0003;
inline constexpr std::uint16_t kInputTouchPressureValidFlag = 0x0001;
inline constexpr std::uint16_t kInputTouchSizeValidFlag = 0x0002;
inline constexpr std::uint16_t kInputPointerAbsoluteFlagsAllowed = 0x0001;
inline constexpr std::uint16_t kInputPointerAbsolutePressureValidFlag = 0x0001;
inline constexpr std::uint32_t kInputGamepadButtonMaskAllowed = 0x0001FFFFU;

enum class InputProtocolError : std::uint8_t {
    None = 0,
    UnsupportedInputVersion,
    UnsupportedInputMessageType,
    UnsupportedInputDeviceKind,
    InvalidCommonFlags,
    ReservedFieldNonZero,
    InvalidDeviceSlot,
    InvalidKeyAction,
    InvalidModifierMask,
    InvalidTouchAction,
    InvalidPointerCount,
    DuplicatePointerId,
    InvalidActionPointer,
    InvalidToolType,
    InvalidPointerFlags,
    InvalidPointerButtonMask,
    InvalidGamepadButtonMask,
    InvalidGamepadAxis,
    InvalidResetScope,
    InvalidResetReason,
    MalformedPayload,
    TrailingBytes,
    ArithmeticOverflow,
};

enum class InputDeviceKind : std::uint8_t {
    Unknown = 0,
    Keyboard = 1,
    Touchscreen = 2,
    Mouse = 3,
    Gamepad = 4,
    Stylus = 5,
    Touchpad = 6,
};

enum class InputMessageType : std::uint8_t {
    Unknown = 0,
    Key = 1,
    TouchFrame = 2,
    PointerAbsolute = 3,
    PointerRelative = 4,
    Scroll = 5,
    GamepadState = 6,
    ResetState = 7,
};

enum class InputKeyAction : std::uint8_t {
    Unknown = 0,
    Down = 1,
    Up = 2,
};

enum class InputTouchAction : std::uint8_t {
    Unknown = 0,
    Down = 1,
    Up = 2,
    Move = 3,
    Cancel = 4,
    PointerDown = 5,
    PointerUp = 6,
};

enum class InputTouchToolType : std::uint8_t {
    Unknown = 0,
    Finger = 1,
    Stylus = 2,
    Eraser = 3,
    Mouse = 4,
};

enum class InputResetScope : std::uint8_t {
    Unknown = 0,
    ThisDevice = 1,
    AllDevices = 2,
};

enum class InputResetReason : std::uint8_t {
    Unknown = 0,
    SessionStop = 1,
    DeviceDisconnected = 2,
    FocusLost = 3,
    ErrorRecovery = 4,
    UserRequest = 5,
};

enum class InputDeliveryClass : std::uint8_t {
    FreshState = 1,
    CriticalTransition = 2,
    Reset = 3,
};

struct InputMessageHeader final {
    std::uint8_t input_version = kInputPayloadVersion;
    InputMessageType message_type = InputMessageType::Unknown;
    InputDeviceKind device_kind = InputDeviceKind::Unknown;
    std::uint8_t flags = 0;
    std::uint16_t device_slot = kInputPrimaryDeviceSlot;

    constexpr bool operator==(const InputMessageHeader&) const = default;
};

struct InputKeyEvent final {
    InputMessageHeader header{};
    std::uint16_t usage_page = 0;
    std::uint16_t usage_id = 0;
    InputKeyAction action = InputKeyAction::Unknown;
    std::uint16_t repeat_count = 0;
    std::uint16_t modifier_mask = 0;
};

struct InputTouchContact final {
    std::uint8_t pointer_id = 0;
    InputTouchToolType tool_type = InputTouchToolType::Unknown;
    std::uint16_t pointer_flags = 0;
    std::uint16_t x_normalized = 0;
    std::uint16_t y_normalized = 0;
    std::uint16_t pressure = 0;
    std::uint16_t size = 0;

    constexpr bool operator==(const InputTouchContact&) const = default;
};

struct InputTouchFrame final {
    InputMessageHeader header{};
    InputTouchAction action = InputTouchAction::Unknown;
    std::uint8_t action_pointer_id = kInputNoActionPointerId;
    std::uint8_t pointer_count = 0;
    std::array<InputTouchContact, kInputMaxTouchContacts> contacts{};
};

struct InputPointerAbsolute final {
    InputMessageHeader header{};
    std::uint16_t x_normalized = 0;
    std::uint16_t y_normalized = 0;
    std::uint16_t button_mask = 0;
    std::uint16_t pointer_flags = 0;
    std::uint16_t pressure = 0;
};

struct InputPointerRelative final {
    InputMessageHeader header{};
    std::int32_t delta_x_q16_16 = 0;
    std::int32_t delta_y_q16_16 = 0;
    std::uint16_t button_mask = 0;
};

struct InputScroll final {
    InputMessageHeader header{};
    std::int16_t horizontal_q8_8 = 0;
    std::int16_t vertical_q8_8 = 0;
    std::uint16_t button_mask = 0;
};

struct InputGamepadState final {
    InputMessageHeader header{};
    std::uint32_t button_mask = 0;
    std::int16_t left_x = 0;
    std::int16_t left_y = 0;
    std::int16_t right_x = 0;
    std::int16_t right_y = 0;
    std::uint16_t left_trigger = 0;
    std::uint16_t right_trigger = 0;
};

struct InputResetState final {
    InputMessageHeader header{};
    InputResetScope scope = InputResetScope::Unknown;
    InputResetReason reason = InputResetReason::Unknown;
};

struct [[nodiscard]] InputProtocolStatus final {
    InputProtocolError error = InputProtocolError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputEncodeResult final {
    InputProtocolError error = InputProtocolError::None;
    std::size_t bytes_written = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputHeaderDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputMessageHeader header{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputKeyDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputKeyEvent event{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputTouchFrameDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputTouchFrame frame{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputPointerAbsoluteDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputPointerAbsolute event{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputPointerRelativeDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputPointerRelative event{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputScrollDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputScroll event{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputGamepadStateDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputGamepadState state{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

struct [[nodiscard]] InputResetStateDecodeResult final {
    InputProtocolError error = InputProtocolError::None;
    InputResetState reset{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputProtocolError::None;
    }
};

using InputMessageHeaderWireBytes = std::array<std::byte, kInputMessageHeaderWireSize>;
using InputKeyWireBytes = std::array<std::byte, kInputKeyWireSize>;
using InputPointerAbsoluteWireBytes = std::array<std::byte, kInputPointerAbsoluteWireSize>;
using InputPointerRelativeWireBytes = std::array<std::byte, kInputPointerRelativeWireSize>;
using InputScrollWireBytes = std::array<std::byte, kInputScrollWireSize>;
using InputGamepadStateWireBytes = std::array<std::byte, kInputGamepadStateWireSize>;
using InputResetStateWireBytes = std::array<std::byte, kInputResetStateWireSize>;

[[nodiscard]] constexpr std::size_t input_touch_frame_wire_size(
    std::uint8_t pointer_count) noexcept {
    return kInputTouchFramePrefixWireSize +
           static_cast<std::size_t>(pointer_count) * kInputTouchContactWireSize;
}

[[nodiscard]] constexpr bool is_valid_input_device_slot(std::uint16_t slot) noexcept {
    return slot != kInputReservedDeviceSlot;
}

[[nodiscard]] constexpr bool is_input_message_type_defined(InputMessageType type) noexcept;
[[nodiscard]] constexpr bool is_input_device_kind_defined(InputDeviceKind kind) noexcept;
[[nodiscard]] constexpr bool is_input_touch_tool_type_defined(InputTouchToolType type) noexcept;
[[nodiscard]] constexpr bool is_valid_gamepad_axis(std::int16_t value) noexcept;
[[nodiscard]] InputDeliveryClass input_delivery_class(const InputMessageHeader& header,
                                                      InputTouchAction touch_action =
                                                          InputTouchAction::Unknown,
                                                      InputKeyAction key_action =
                                                          InputKeyAction::Unknown) noexcept;
[[nodiscard]] std::string_view input_protocol_error_name(InputProtocolError error) noexcept;

[[nodiscard]] InputProtocolStatus validate_input_message_header(
    const InputMessageHeader& header) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_key(const InputKeyEvent& event) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_touch_frame(const InputTouchFrame& frame) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_pointer_absolute(
    const InputPointerAbsolute& event) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_pointer_relative(
    const InputPointerRelative& event) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_scroll(const InputScroll& event) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_gamepad_state(
    const InputGamepadState& state) noexcept;
[[nodiscard]] InputProtocolStatus validate_input_reset_state(
    const InputResetState& reset) noexcept;

[[nodiscard]] InputEncodeResult encode_input_message_header(
    const InputMessageHeader& header, std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_key(const InputKeyEvent& event,
                                                 std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_touch_frame(const InputTouchFrame& frame,
                                                         std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_pointer_absolute(
    const InputPointerAbsolute& event, std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_pointer_relative(
    const InputPointerRelative& event, std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_scroll(const InputScroll& event,
                                                    std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_gamepad_state(const InputGamepadState& state,
                                                           std::span<std::byte> output) noexcept;
[[nodiscard]] InputEncodeResult encode_input_reset_state(const InputResetState& reset,
                                                         std::span<std::byte> output) noexcept;

[[nodiscard]] InputHeaderDecodeResult decode_input_message_header(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputKeyDecodeResult decode_input_key(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputTouchFrameDecodeResult decode_input_touch_frame(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputPointerAbsoluteDecodeResult decode_input_pointer_absolute(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputPointerRelativeDecodeResult decode_input_pointer_relative(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputScrollDecodeResult decode_input_scroll(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputGamepadStateDecodeResult decode_input_gamepad_state(
    std::span<const std::byte> payload) noexcept;
[[nodiscard]] InputResetStateDecodeResult decode_input_reset_state(
    std::span<const std::byte> payload) noexcept;

constexpr bool is_input_message_type_defined(InputMessageType type) noexcept {
    switch (type) {
    case InputMessageType::Unknown:
    case InputMessageType::Key:
    case InputMessageType::TouchFrame:
    case InputMessageType::PointerAbsolute:
    case InputMessageType::PointerRelative:
    case InputMessageType::Scroll:
    case InputMessageType::GamepadState:
    case InputMessageType::ResetState:
        return true;
    }
    return false;
}

constexpr bool is_input_device_kind_defined(InputDeviceKind kind) noexcept {
    switch (kind) {
    case InputDeviceKind::Unknown:
    case InputDeviceKind::Keyboard:
    case InputDeviceKind::Touchscreen:
    case InputDeviceKind::Mouse:
    case InputDeviceKind::Gamepad:
    case InputDeviceKind::Stylus:
    case InputDeviceKind::Touchpad:
        return true;
    }
    return false;
}

constexpr bool is_input_touch_tool_type_defined(InputTouchToolType type) noexcept {
    switch (type) {
    case InputTouchToolType::Unknown:
    case InputTouchToolType::Finger:
    case InputTouchToolType::Stylus:
    case InputTouchToolType::Eraser:
    case InputTouchToolType::Mouse:
        return true;
    }
    return false;
}

constexpr bool is_valid_gamepad_axis(std::int16_t value) noexcept {
    return value != static_cast<std::int16_t>(-32768);
}

static_assert(kInputPayloadVersion == 1);
static_assert(kInputMessageHeaderWireSize == 8);
static_assert(kInputKeyWireSize == 20);
static_assert(kInputTouchFramePrefixWireSize == 12);
static_assert(kInputTouchContactWireSize == 12);
static_assert(kInputPointerAbsoluteWireSize == 20);
static_assert(kInputPointerRelativeWireSize == 20);
static_assert(kInputScrollWireSize == 16);
static_assert(kInputGamepadStateWireSize == 28);
static_assert(kInputResetStateWireSize == 12);
static_assert(static_cast<std::uint8_t>(InputDeviceKind::Keyboard) == 1);
static_assert(static_cast<std::uint8_t>(InputDeviceKind::Touchscreen) == 2);
static_assert(static_cast<std::uint8_t>(InputDeviceKind::Mouse) == 3);
static_assert(static_cast<std::uint8_t>(InputDeviceKind::Gamepad) == 4);
static_assert(static_cast<std::uint8_t>(InputDeviceKind::Stylus) == 5);
static_assert(static_cast<std::uint8_t>(InputDeviceKind::Touchpad) == 6);
static_assert(static_cast<std::uint8_t>(InputMessageType::Key) == 1);
static_assert(static_cast<std::uint8_t>(InputMessageType::TouchFrame) == 2);
static_assert(static_cast<std::uint8_t>(InputMessageType::PointerAbsolute) == 3);
static_assert(static_cast<std::uint8_t>(InputMessageType::PointerRelative) == 4);
static_assert(static_cast<std::uint8_t>(InputMessageType::Scroll) == 5);
static_assert(static_cast<std::uint8_t>(InputMessageType::GamepadState) == 6);
static_assert(static_cast<std::uint8_t>(InputMessageType::ResetState) == 7);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_INPUT_PROTOCOL_H_
