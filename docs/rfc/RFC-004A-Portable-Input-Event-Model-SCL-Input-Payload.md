# RFC-004A - Portable Input Event Model and SCL Input Payload V1

Project: Warpnect

Status: Phase 4 reverse input foundation complete.

Architecture Version: 1.0 frozen. SCL Protocol Version: 1. Native Bridge ABI Version: 1. PCM Shared Ring Version: 1. PCM Playback Ring Version: 1. Audio Payload Version: 1. Video Payload Version: 1. Video Resync Control Version: 1. Input Payload Version: 1.

## Purpose

RFC-004A begins Phase 4 by defining the portable reverse-input event model and deterministic SCL Input Payload Version 1 wire representation.

The implemented scope is intentionally limited to:

```text
portable input event semantics
Input Payload Version 1
native encode/decode/validation
platform-neutral Kotlin model
tests and documentation
```

Android input capture, UDP input transport orchestration, privileged injection, input mapping, reliability policy, and device/session negotiation remain deferred.

## Payload Type and Timestamp

Input Payload Version 1 is carried by the already frozen:

```text
PayloadType::Input = 4
```

The 21-byte SCL `PacketHeader` is unchanged. No new `PayloadType` is added.

When RFC-004C packetizes input messages, `PacketHeader.timestamp_us` carries source-device local monotonic event time in microseconds. Input Payload V1 does not duplicate that timestamp inside the logical payload and never uses wall-clock time.

## Common Header

Every Input Payload V1 message starts with exactly 8 bytes:

| Offset | Size | Field |
| -----: | ---: | ----- |
| 0 | 1 | `input_version` |
| 1 | 1 | `message_type` |
| 2 | 1 | `device_kind` |
| 3 | 1 | `flags` |
| 4 | 2 | `device_slot` |
| 6 | 2 | reserved |

All multi-byte fields are big-endian. Version 1 requires `flags == 0` and reserved bytes `6..7 == 0`.

`device_slot` is a session-local logical device identifier:

```text
0        = primary/default device slot
1..65534 = additional session-local slots
65535    = reserved, except ResetState AllDevices
```

It is not an Android `InputDevice.deviceId`, USB address, Bluetooth address, hardware serial, or global identity.

## Device Kinds

Input Device Kind values:

| Value | Kind |
| ----: | ---- |
| 0 | Unknown |
| 1 | Keyboard |
| 2 | Touchscreen |
| 3 | Mouse |
| 4 | Gamepad |
| 5 | Stylus |
| 6 | Touchpad |

These are portable Warpnect values and do not reuse Android source integers.

## Message Types

Input Message Type values:

| Value | Message |
| ----: | ------- |
| 0 | Unknown |
| 1 | Key |
| 2 | TouchFrame |
| 3 | PointerAbsolute |
| 4 | PointerRelative |
| 5 | Scroll |
| 6 | GamepadState |
| 7 | ResetState |

Unknown Version 1 message types return `UnsupportedInputMessageType`.

## Keyboard

`Key` is exactly 20 bytes:

| Offset | Size | Field |
| -----: | ---: | ----- |
| 0 | 8 | common header |
| 8 | 2 | `usage_page` |
| 10 | 2 | `usage_id` |
| 12 | 1 | `action` |
| 13 | 1 | reserved |
| 14 | 2 | `repeat_count` |
| 16 | 2 | `modifier_mask` |
| 18 | 2 | reserved |

Keys use USB HID Usage Page plus Usage ID semantics. The project documentation references USB HID Usage Tables 1.7 as the semantic baseline, but the protocol parser does not whitelist only currently mapped usages. Platform adapters decide whether a valid HID usage can be captured or injected locally.

Actions are `Down = 1` and `Up = 2`. Initial Down uses repeat count 0, repeated Down uses a positive repeat count, and Up requires repeat count 0.

The modifier mask uses bits 0..7 for left/right Control, Shift, Alt, and GUI. Bits 8..15 are reserved and rejected when non-zero. Modifier keys remain representable as ordinary HID key usages. No text, IME, clipboard, or paste message is defined.

## Touch

`TouchFrame` represents one multi-contact source observation. Its prefix is 12 bytes:

| Offset | Size | Field |
| -----: | ---: | ----- |
| 0 | 8 | common header |
| 8 | 1 | `action` |
| 9 | 1 | `action_pointer_id` |
| 10 | 1 | `pointer_count` |
| 11 | 1 | reserved |

Each contact is exactly 12 bytes:

| Relative Offset | Size | Field |
| --------------: | ---: | ----- |
| 0 | 1 | `pointer_id` |
| 1 | 1 | `tool_type` |
| 2 | 2 | `pointer_flags` |
| 4 | 2 | `x_normalized` |
| 6 | 2 | `y_normalized` |
| 8 | 2 | `pressure` |
| 10 | 2 | `size` |

The total TouchFrame size is:

```text
12 + pointer_count * 12
```

Version 1 supports up to 32 contacts. Pointer IDs are 0..31 and must be unique within a frame. Absolute coordinates are normalized u16 values, with x left-to-right and y top-to-bottom. Pressure and size are normalized u16 fields and are meaningful only when their validity bits are set; otherwise the fields must be zero.

Touch actions are Down, Up, Move, Cancel, PointerDown, and PointerUp. Transition actions must reference a contact present in the frame. Move and Cancel use action pointer `0xFF`. No gesture recognition is encoded on the wire.

## Pointer and Scroll

`PointerAbsolute` is exactly 20 bytes and carries normalized u16 x/y coordinates, a portable button-state mask, optional pressure, and reserved-zero fields. It is suitable for absolute mouse, stylus-like, or remote-desktop pointer observations. Pixel coordinates are not serialized.

`PointerRelative` is exactly 20 bytes and carries signed Q16.16 normalized surface deltas:

```text
65536  = +1.0 logical surface width/height
-65536 = -1.0 logical surface width/height
```

A zero-delta relative message is valid when button state changed.

`Scroll` is exactly 16 bytes and carries signed Q8.8 logical scroll units:

```text
256 = +1.0 logical scroll unit
```

Zero horizontal and vertical scroll is rejected as a no-op. Mouse acceleration, DPI scaling, sensitivity, and pixel mapping belong to later adapter/mapping RFCs.

## Gamepad

`GamepadState` is exactly 28 bytes and is a complete common-controller state snapshot:

| Offset | Size | Field |
| -----: | ---: | ----- |
| 0 | 8 | common header |
| 8 | 4 | `button_mask` |
| 12 | 2 | `left_x` |
| 14 | 2 | `left_y` |
| 16 | 2 | `right_x` |
| 18 | 2 | `right_y` |
| 20 | 2 | `left_trigger` |
| 22 | 2 | `right_trigger` |
| 24 | 4 | reserved |

The button mask covers A/B/X/Y, shoulders, trigger buttons, Select/Back, Start, Guide/Mode, stick buttons, and D-pad directions. Bits 17..31 are reserved.

Stick axes use signed i16 values:

```text
-32767 = -1.0
0      = center
32767  = +1.0
```

The value `-32768` is invalid/reserved. Trigger axes are normalized u16 values from released to fully pressed. No deadzone, sensitivity curve, or Android keycode mapping is part of the protocol.

## ResetState

`ResetState` is exactly 12 bytes:

| Offset | Size | Field |
| -----: | ---: | ----- |
| 0 | 8 | common header |
| 8 | 1 | `scope` |
| 9 | 1 | `reason` |
| 10 | 2 | reserved |

Scopes are `ThisDevice = 1` and `AllDevices = 2`. `ThisDevice` requires a valid non-reserved device slot. `AllDevices` requires `device_kind = Unknown` and `device_slot = 65535`, which is the only Version 1 use of the reserved slot value.

Reasons are diagnostic: SessionStop, DeviceDisconnected, FocusLost, ErrorRecovery, and UserRequest. All reasons perform the same protocol-level reset semantics.

## Delivery Classification

RFC-004A defines a local helper classification only:

```text
FreshState
CriticalTransition
Reset
```

Pointer, scroll, gamepad, and touch move observations are generally FreshState. Key transitions and touch down/up/cancel transitions are CriticalTransition. ResetState is Reset. This prepares RFC-004C and RFC-004G but does not implement retransmission, duplication, NACK, FEC, reliable queues, or transport policy.

## Serialization and Validation

Native encode/decode uses explicit big-endian field operations. It does not copy packed C++ structs onto the wire.

Parsing is strict:

- fixed-size messages require exact length;
- TouchFrame size must match its pointer count exactly;
- trailing bytes are rejected;
- reserved bytes and bits must be zero;
- unknown enum values are rejected except HID usage identifiers, which remain forward-preservable semantic values;
- pointer IDs must be unique;
- button masks, modifier masks, tool types, action pointers, gamepad axes, and reset sentinel rules are validated.

The parser is statically bounded. TouchFrame decoding uses a fixed 32-contact structure and does not allocate based on arbitrary wire data.

## Kotlin Model

The package `io.warpnect.input.model` defines platform-neutral input models and normalization helpers. It intentionally contains no Android framework classes and no independent Kotlin wire serializer. SCL binary serialization remains native so the protocol has a single implementation authority.

The Kotlin helpers provide deterministic validation and conversion for normalized u16 coordinates, signed gamepad axes, trigger u16 values, Q16.16 relative motion, and Q8.8 scroll units. NaN, infinity, and out-of-range values are rejected.

## Tests

Host-native tests cover:

- common header, key, touch, gamepad, and reset golden vectors;
- strict length validation and every truncation point;
- malformed common flags/reserved fields;
- invalid enum values;
- reserved modifier/button/pointer bits;
- touch duplicate IDs, maximum 32 contacts, action-pointer rules, and pressure/size validity;
- coordinate edge values;
- Q16.16 relative motion and Q8.8 scroll helpers through encoded values;
- gamepad full-state roundtrip and invalid `-32768` axes;
- reset AllDevices and ThisDevice sentinel validation;
- delivery classification.

JVM tests cover the platform-neutral Kotlin model validation, fixed-point helpers, device-slot rules, pointer/gamepad masks, touch rules, reset rules, and delivery classification. RFC-004A has no Android instrumentation dependency.

## Explicit Non-Goals

RFC-004A does not implement:

```text
Android input capture
InputManager injection
AccessibilityService
shell input
/dev/uinput
UDP input runtime
JNI input transport
input NACK/FEC/reliability policy
device descriptors
session negotiation
input mapping/deadzones/gesture recognition
```

## Protocol Audit

No changes are made to:

```text
PacketHeader
PayloadType values
Audio Payload V1
Video Payload V1
VideoResyncRequest
NACK
FEC
ClockSync
```

Input Payload Version 1 is the only new versioned payload contract, and it uses existing `PayloadType::Input`.

## Next Work

Phase 4 continues with:

```text
RFC-004B - Android Input Capture Foundation
RFC-004C - Reverse SCL Input Transport
RFC-004D - Android Privileged Input Injection
RFC-004E - Input Mapping, Coordinate and Device Semantics
RFC-004F - End-to-End Reverse Input
RFC-004G - Input Latency, Reliability and Performance Tuning
```

Do not begin them as part of RFC-004A.
