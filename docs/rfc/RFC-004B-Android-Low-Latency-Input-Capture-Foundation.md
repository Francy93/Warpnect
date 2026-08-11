# RFC-004B - Android Low-Latency Input Capture Foundation

Project: Warpnect

Status: Phase 4 Android input-capture foundation complete.

Architecture Version: 1.0 frozen. SCL Protocol Version: 1. Native Bridge ABI Version: 1. Input Payload Version: 1. Audio Payload Version: 1. Video Payload Version: 1. Video Resync Control Version: 1.

## Purpose

RFC-004B converts input events delivered to the Warpnect Android receiver UI into the portable RFC-004A model:

```text
Android KeyEvent / MotionEvent
        |
WarpnectInputCaptureView
        |
Android input adapters
        |
RFC-004A portable Kotlin input model
        |
InputEventSink
```

This RFC does not add reverse UDP transport, JNI input transport, SCL serialization in Kotlin, privileged injection, target-side coordinate mapping, gesture recognition, NACK/FEC, or reliability policy.

## Capture Architecture

`WarpnectInputCaptureView` is a transparent, focusable Android `View` for the remote-control surface. It receives raw:

- `KeyEvent`;
- touch `MotionEvent`;
- generic mouse / scroll / joystick `MotionEvent`;
- captured pointer `MotionEvent`;
- pointer-capture and window-focus callbacks.

The view forwards each callback synchronously to `AndroidInputCaptureController`. The controller normalizes the event and calls `InputEventSink.onInputEvent(eventTimeUs, event)` directly. There is no capture queue, worker thread, per-event coroutine, timer polling loop, VSYNC sampling loop, or extra main-thread hop.

Recognized captured events are consumed when `InputCaptureConfig.consumeCapturedEvents` is true. Unsupported events return to normal Android dispatch where possible. A recognized event remains consumed even when the sink rejects it so a transport failure does not accidentally trigger local UI behavior.

## Input Surface

`WarpnectInputCaptureView` is focusable and focusable-in-touch-mode while active. Touch/click interaction with the surface requests focus. The Compose wrapper `WarpnectInputSurface` hosts the view through `AndroidView` so it can be overlaid with the existing video surface without creating a rendering pipeline.

The input layer is transparent and does not draw media or process pixels. Absolute coordinate normalization depends only on the capture view's current width and height.

## Timestamp Contract

Source event time is preserved:

- `KeyEvent.eventTime * 1000` for key events;
- `MotionEvent.eventTimeNanos / 1000` on API levels that expose nanosecond event time;
- `MotionEvent.eventTime * 1000` on older supported API levels;
- matching historical MotionEvent timestamps for historical samples.

Callback receipt time is not substituted. The controller records bounded dispatch-delay diagnostics as `callbackUptimeUs - eventTimeUs`.

## Device Registry

`AndroidInputDeviceRegistry` maps:

```text
(androidDeviceId, InputDeviceKind) -> session-local deviceSlot
```

The Android runtime ID is local capture metadata only and never appears in the portable model. Slots are allocated from `0..65534`. Slot `65535` is reserved for RFC-004A `ResetState(AllDevices)`.

The registry is bounded by `InputCaptureConfig.maxTrackedLogicalDevices`, default 32 active logical devices. Slots are not reused for a different logical device during one active capture lifecycle. Device removal emits `ResetState(ThisDevice, DeviceDisconnected)` for affected logical slots before local state is discarded.

## Keyboard

Physical keyboard `ACTION_DOWN` and `ACTION_UP` events are mapped by `AndroidKeyboardHidMapper` to USB HID Keyboard/Keypad usage page `0x0007`.

The baseline mapping covers:

- A-Z and 0-9;
- Enter, Escape, Backspace, Tab, Space;
- punctuation keys;
- CapsLock;
- F1-F12;
- PrintScreen, ScrollLock, Pause;
- Insert/Home/PageUp/Delete/End/PageDown;
- arrows;
- NumLock and numpad digits/operators;
- left/right Control, Shift, Alt, and GUI/Meta.

`KeyEvent.repeatCount` is preserved for Down and forced to zero for Up. `AndroidKeyboardModifierTracker` maintains left/right modifier state from actual key transitions. Unknown keys increment diagnostics and are not mapped through Unicode or scan-code fallback.

## Touch

Touch capture uses raw `MotionEvent` actions:

```text
Down
Up
Move
Cancel
PointerDown
PointerUp
```

Each `InputTouchFrame` carries all current contacts from the Android observation. Pointer IDs must fit `0..31`; more than 32 contacts are rejected rather than truncated. View-local x/y coordinates are normalized to inclusive u16 `0..65535` using the capture view bounds.

`ACTION_MOVE` historical samples are processed before the current sample when `captureTouchHistory` is enabled. Historical frames use `TouchAction.Move`, action pointer `0xFF`, all current contacts, and each historical sample's own event time.

Tool type maps to Unknown, Finger, Stylus, Eraser, or Mouse. Pressure and size are normalized and marked valid only when useful motion-range information is available; otherwise they are zero and invalid. `ACTION_CANCEL` emits `TouchFrame(Cancel)` and reset logic clears local state where needed. No gesture recognition is introduced.

## Mouse and Pointer

Uncaptured mouse movement emits `PointerAbsolute` snapshots with normalized x/y coordinates and the portable button mask. Button-only press/release events are preserved as absolute pointer state snapshots.

`ACTION_SCROLL` reads `AXIS_HSCROLL` and `AXIS_VSCROLL` and converts them to signed Q8.8 logical scroll units, preserving fractional values and rejecting zero/zero no-ops.

The controller exposes `requestPointerCapture()` and `releasePointerCapture()`. Captured pointer events use `AXIS_RELATIVE_X` and `AXIS_RELATIVE_Y`, including every historical sample, because relative-axis history is incremental. Deltas are normalized by capture-surface width/height and encoded as Q16.16. No mouse acceleration, sensitivity, DPI scaling, or target/game mapping is applied.

Pointer-capture loss or manual release emits a mouse `ResetState` when a captured mouse slot exists.

## Gamepad

Game controllers are recognized from `SOURCE_GAMEPAD` and `SOURCE_JOYSTICK`.

Gamepad button `KeyEvent` values update a bounded per-slot state cache and immediately emit a complete `InputGamepadState`. Gamepad buttons are not encoded as keyboard `Key` payloads.

Common mappings include A/B/X/Y, shoulders, trigger buttons, Select/Back, Start, Guide/Mode, stick buttons, and D-pad key events. Unknown gamepad buttons increment diagnostics without inventing reserved bits.

Joystick `ACTION_MOVE` reads:

```text
AXIS_X / AXIS_Y
AXIS_Z / AXIS_RZ
AXIS_LTRIGGER / AXIS_RTRIGGER
AXIS_HAT_X / AXIS_HAT_Y
```

MotionRange min/max is used when available; otherwise conservative common ranges are used for synthetic/test events. Stick axes are normalized to `-32767..32767` and never generate reserved `-32768`. Triggers are normalized to `0..65535`. HAT axes and D-pad key state are kept separately and ORed in the emitted snapshot. No deadzone or response curve is applied.

Historical joystick samples are processed synchronously before the current sample when `captureGamepadHistory` is enabled. No historical queue remains after the Android event returns.

## Reset Behavior

ResetState is used instead of synthetic release storms:

- window focus loss emits `ResetState(AllDevices, FocusLost)`;
- `stop()` emits `ResetState(AllDevices, SessionStop)` before sink detach;
- Android device removal emits `ResetState(ThisDevice, DeviceDisconnected)` for affected logical slots;
- pointer-capture loss emits a mouse reset when needed;
- manual pointer-capture release emits `ResetState(ThisDevice, UserRequest)` for active captured mouse state.

After reset handling, local modifier, gamepad, mouse, and touch state is cleared.

## Buffering and Allocation

Production capture is event-driven and queue-free:

```text
capture queue = 0
worker queue = 0
timer polling = 0
```

Per-device state is bounded by `maxTrackedLogicalDevices`. Touch contacts are bounded by RFC-004A's 32-contact maximum. RFC-004A Kotlin model objects are small immutable values; RFC-004B does not add per-event `ByteArray`, large lists, maps, JSON, Bundle payloads, or media buffers. RFC-004G may later measure whether object pooling is worthwhile.

## Snapshot and Telemetry

`InputCaptureSnapshot` exposes state, tracked logical device count, highest assigned slot, captured event counters, historical counters, reset count, unsupported-key/button counters, invalid-pointer count, sink failures, registry-full count, pointer-capture requested/active flags, last event time, last callback delay, and last typed error.

Telemetry is counter/latest-value only. There is no in-memory event log.

## Tests

JVM tests cover:

- Android keycode to HID usage mapping;
- unknown key behavior;
- left/right modifier tracking;
- bounded registry allocation, capacity, removal, slot freshness, and lifecycle reset;
- gamepad button mapping;
- axis normalization for `-1..1` and `0..255` ranges;
- no-deadzone behavior;
- pointer button masks;
- normalized absolute coordinate conversion;
- relative Q16.16 conversion;
- scroll Q8.8 conversion;
- capture-config validation.

Android instrumentation includes synthetic `WarpnectInputCaptureView` dispatch coverage for keyboard, touch, mouse absolute motion, scroll, and gamepad button translation. Physical keyboard, mouse/pointer-capture, gamepad, and touch validation remain actual-device dependent.

## Protocol Audit

No changes are made to:

```text
PacketHeader
PayloadType
Input Payload V1
Audio Payload V1
Video Payload V1
VideoResyncRequest
NACK
FEC
ClockSync
```

Native Bridge ABI Version remains 1 because RFC-004B introduces no input JNI boundary.

## Device Status

Synthetic Android instrumentation can run on a connected device or emulator. Physical peripheral results must be reported only when actual hardware is available. No physical keyboard, mouse, gamepad, touch, or pointer-capture compatibility claim is made from host/JVM tests alone.

## Deferred Work

```text
RFC-004C - Reverse SCL Input Transport
RFC-004D - Android Privileged Input Injection
RFC-004E - Input Mapping, Coordinate and Device Semantics
RFC-004F - End-to-End Reverse Input
RFC-004G - Input Latency, Reliability and Performance Tuning
```

Do not begin them as part of RFC-004B.
