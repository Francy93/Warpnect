# RFC-004E - Input Mapping, Coordinate and Device Semantics

Status: Implemented.

RFC-004E adds the endpoint-local semantic mapping that deliberately sits between RFC-004B capture, RFC-004C transport, and RFC-004D privileged injection. It does not add an SCL message, JNI entry point, Binder method, receiver runtime, or reliability policy.

## Scope

The receiver-side path is synchronous:

```text
Android input dispatch
        -> RFC-004B portable model
        -> RemoteVideoViewportInputMapper
        -> SclInputEventSink
        -> RFC-004C immediate UDP send
```

The target-side path is also synchronous:

```text
portable Input Payload V1 model
        -> AndroidTargetInputMapper
        -> RFC-004D Android-ready events
        -> synchronous privileged Binder call
        -> InputManager asynchronous injection
```

Neither mapper owns a worker, coroutine, timer, network socket, event history, input queue, or media payload. RFC-004F remains responsible for composing the target-side receiver runtime with this mapper.

## Receiver Viewport Mapping

`VideoRenderGeometry` remains the single aspect-fit authority. `WarpnectVideoSurfaceView` publishes an immutable `VideoViewportGeometry` containing the input surface dimensions, visible content rectangle, video dimensions, surface generation, and local video-geometry generation. `VideoViewportGeometryStore` provides a lock-free metadata handoff to `RemoteVideoViewportInputMapper`; video bytes and pixels never cross this boundary.

For absolute touch and pointer input, Input V1's capture-surface normalized coordinate is transformed into remote-content normalized coordinates. With `N = 65535`, the exact integer model is:

```text
surfaceCoordinate = inputNormalized * surfaceSize
contentNormalized = round((surfaceCoordinate - contentOffset * N) / contentSize)
```

The content interval is inclusive at both visible edges, so content edges map exactly to `0` and `65535`. Conversion uses deterministic nearest-integer rounding and clamps only after a gesture has already begun inside content.

The production `OutsideVideoContentPolicy` is `RejectNewClampActive`:

- A new `TouchFrame.Down` in letterbox/pillarbox is suppressed; it cannot start a remote touch at a display edge.
- A new `PointerDown` added outside an active touch emits `ResetState(ThisDevice, ErrorRecovery)`, clears local mapping state, and suppresses the malformed partial frame.
- Active touch moves, pointer-up, up, and cancel contacts that leave content are clamped to the nearest content edge and forwarded, preserving release continuity.
- An absolute mouse observation outside content is suppressed while no button is active; a new outside press is also suppressed. An active drag/button sequence is clamped and forwarded.
- Key, scroll, gamepad, and portable reset events do not depend on viewport coordinates.

Relative pointer deltas use the existing Q16.16 representation and are rescaled without a position test:

```text
contentDelta = round(surfaceDelta * surfaceSize / contentSize)
```

Overflow is rejected explicitly. Scroll Q8.8 values are not spatial coordinates and are passed unchanged.

Before an event uses a changed surface generation, video-config generation, or content rectangle, the mapper emits a bounded per-slot `ResetState(ErrorRecovery)` for every active absolute touch/pointer state. The triggering coordinate continuation is then suppressed, so an old `MOVE` cannot be interpreted in the new coordinate system. If the reset cannot be forwarded, the new geometry is not used. Geometry that is unavailable or invalid causes a typed drop/rejection; input is never retained for future geometry.

## Target Display Mapping

`AndroidTargetDisplayGeometryProvider` uses Android logical display geometry for the configured `targetDisplayId`. Its production implementation isolates `Display.getRealSize()` to a cold-path cached provider and invalidates cached geometry via `DisplayManager` callbacks. It does not use Warpnect's Activity/window bounds or physical panel mode resolution.

Absolute Input V1 coordinates map to target logical-display pixels with deterministic nearest-integer rounding:

```text
xPx = round(xNormalized * (logicalWidthPx - 1) / 65535)
yPx = round(yNormalized * (logicalHeightPx - 1) / 65535)
```

For a one-pixel dimension the result is zero. Relative Q16.16 content deltas map to floating Android relative axes:

```text
relativeXPx = deltaXQ16_16 / 65536 * logicalWidthPx
relativeYPx = deltaYQ16_16 / 65536 * logicalHeightPx
```

Relative deltas are never clamped to screen bounds. Rotation is recorded in geometry diagnostics but is not applied a second time: the Phase 2 renderer presents the captured remote display in its current logical orientation, and RFC-004E has no independent image transform to invert.

If target geometry changes while a touch or pointer-button sequence is active, RFC-004D receives `ResetState(ThisSlot, ErrorRecovery)`. The triggering coordinate continuation is suppressed rather than remapped across coordinate systems. Keyboard and gamepad state are geometry-independent. A failed privileged reset leaves mapper state intact and returns `ResetInjectionFailure`.

## Keyboard and Device Resolution

`AndroidHidKeyboardMappingTable` is one canonical bidirectional table. RFC-004B uses its Android-keycode-to-HID direction; RFC-004E uses the inverse for HID Usage Page `0x0007`. The table covers the RFC-004B keyboard set: alphanumerics, punctuation, navigation, function keys, numpad, and left/right Ctrl, Shift, Alt, and Meta. Unsupported but syntactically valid HID usages return `UnsupportedHidUsage`; no Unicode, IME, scan-code, or text synthesis occurs.

Portable modifier bits produce Android's matching side-specific and aggregate meta-state bits. A portable key maps to exactly one `AndroidKeyInjectionEvent` with `SOURCE_KEYBOARD`, scan code zero, preserved repeat count, and no synthesized repeat timer.

Portable `deviceSlot` is passed directly to RFC-004D as `stateSlot`. Android device IDs are resolved only at the target via bounded policy:

| Device kind | Default resolution |
| --- | --- |
| Keyboard | `SyntheticDefault` (`deviceId = 0`) |
| Touchscreen, stylus, touchpad | `SyntheticDefault` (`deviceId = 0`) |
| Mouse | `SyntheticDefault` (`deviceId = 0`) |
| Gamepad | `SyntheticDefault` (`deviceId = 0`) for the InputManager backend |

`PreferSourceCompatible` and `RequireSourceCompatible` remain explicit target-local mapping options and use an `InputManager` resolver cache. Device add/remove/change callbacks invalidate that cache; no input event scans local devices. With the InputManager injection backend, a requested device ID is not a promise that InputDispatcher preserves a physical target `InputDevice` identity. No physical source-to-target pairing or Android device identity crosses Input Payload V1.

## Touch, Pointer, and Scroll

Touch contact order is preserved. `actionPointerId` is looked up in that order to obtain the Android `actionIndex`; pointer IDs are never assumed to equal indexes. Portable tool types map to Android unknown, finger, stylus, eraser, or mouse tool types. Valid pressure and size normalize to `0.0f..1.0f`; missing pressure defaults to `1.0f` for active contacts and `0.0f` for the released action contact, while missing size defaults to `1.0f`.

Pointer snapshots carry a complete portable button mask. RFC-004E compares it to the bounded previous state and emits a deterministic sequence in portable-button order: Primary, Secondary, Tertiary, Back, Forward.

```text
new releases -> ACTION_BUTTON_RELEASE
main action  -> DOWN / UP / MOVE / HOVER_MOVE
new presses  -> ACTION_BUTTON_PRESS
final absolute release -> HOVER_MOVE
```

Primary, secondary, and tertiary define the main down gesture. Back and Forward generate button transitions but do not create a drag state. Absolute mouse input uses `SOURCE_MOUSE`; relative input uses `SOURCE_MOUSE_RELATIVE` with `ACTION_MOVE`; scroll maps Q8.8 values to `AXIS_HSCROLL` and `AXIS_VSCROLL` in an `ACTION_SCROLL` event without changing tracked button state. One portable snapshot can expand into no more than five button transitions, one main event, and one post-up hover event.

## Gamepad Mapping

Gamepad snapshots are cached per bounded logical slot. A first snapshot compares with neutral state; an identical later snapshot is a successful no-op with no privileged call. Changed standard buttons become Android gamepad key transitions, and changed analog state becomes one `SOURCE_JOYSTICK` event carrying X/Y/Z/RZ, left/right triggers, and optional hat axes.

The default `DpadInjectionMode` is `HatAxes`. Up/down/left/right become `AXIS_HAT_Y` and `AXIS_HAT_X`; opposing directions produce zero on that axis and increment diagnostics. `KeyEvents` is an explicit alternative and emits D-pad key transitions with zero hat axes. The two modes are never combined. Gamepad event order is releases, joystick/hat state, then presses. No deadzone, sensitivity curve, response curve, repeat timer, or state-coalescing queue is introduced.

## Reset, Bounds, and Diagnostics

Portable `ResetState(ThisDevice)` maps to RFC-004D `ThisSlot` with the same slot. `AllDevices` maps to `AllSlots`. Mapper cache clearing occurs only after the privileged reset succeeds.

Both mappers use at most 32 fixed tracked slots by default. Receiver state contains only active touch IDs, pointer buttons, device kind, and geometry identity. Target state contains only touch-active, pointer-button, and latest gamepad snapshot state. There is no wire payload copy, `ByteArray`, JSON, `Bundle`, queue, worker, or timer. Mapping snapshot counters report mapping volume, suppression, clamping, geometry resets, production expansion, unsupported HID, gamepad no-ops, opposing D-pad states, and the latest typed error without per-event logging.

## Validation

JVM coverage includes shared HID round trips, target modifier conversion, viewport edge/letterbox mapping, outside-down suppression, active clamping and release continuity, multi-touch reset, relative scaling, geometry resets, logical target coordinates, touch action ID/index mapping, mouse action and button expansion, gamepad axes/hat/key mode/no-op behavior, and reset failure behavior.

An Android instrumentation test checks that the production target-display provider returns a usable primary logical display geometry. It is device/emulator dependent. Privileged key, touch, pointer, and gamepad delivery remain dependent on an attached device plus Shizuku/Sui availability and are not claimed by host tests.

## Version and Protocol Status

RFC-004E leaves Architecture Version `1.0`, SCL Protocol Version `1`, Native Bridge ABI Version `1`, Input Payload Version `1`, and Privileged Input Injection Service Version `1` unchanged. `PacketHeader`, `PayloadType`, Input/Audio/Video payload layouts, VideoResyncRequest, NACK, FEC, and ClockSync are unchanged.

## Deferred Work

- RFC-004F - End-to-End Reverse Input: continuous receive/session orchestration.
- RFC-004G - Input Latency, Reliability and Performance Tuning.
- Device-specific privileged injection behavior and commercial-game controller recognition remain runtime validation work; RFC-004E does not create a persistent virtual Android `InputDevice`.
