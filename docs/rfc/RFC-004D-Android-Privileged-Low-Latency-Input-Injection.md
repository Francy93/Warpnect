# RFC-004D - Android Privileged Low-Latency Input Injection

## Status

Implemented. RFC-004D provides an Android target-side privileged injection foundation only. It does not decode SCL Input Payload V1, map portable input values, capture input, or add an input receiver/runtime.

## Path

```text
Android-ready key/touch/pointer/joystick event
        |
AndroidInputInjectionController
        |
synchronous AIDL Binder call
        |
Shizuku/Sui PrivilegedInputInjectionUserService
        |
cached InputManagerGlobal reflection
        |
InputManager ASYNC injection
```

The app-to-UserService Binder call is synchronous and intentionally not `oneway`. The production InputManager mode is asynchronous: `SubmittedAsync` means the service submitted the event; it does not prove application delivery. `WaitForResultDiagnostics` is optional and is never selected by the production default. `WAIT_FOR_FINISHED` is not used.

## Privilege And Capability Model

The user service runs under the identity supplied by Shizuku/Sui. Capabilities expose the service UID and root/shell/other classification, cached InputManager API resolution, async/wait mode support, target-UID support, display-targeting support, and per-event-kind availability. Platform and OEM behavior remains device-specific.

`InputManagerGlobal.getInstance`, injection overloads, the InputEvent display setter, and optional MotionEvent action-button setter are resolved once. Normal injection invokes only cached methods. If a target UID was requested and the three-argument injection overload is unavailable, preparation returns `TargetUidUnsupported`; it never broadens to untargeted injection. An explicit display ID is required for every event, and a missing display setter produces `DisplayTargetingUnsupported`.

## Android-Ready Events And Timing

Events carry Android key codes, target pixel coordinates, source, Android device ID, display ID, and an opaque bounded state slot. This is deliberately an Android-ready boundary. RFC-004E owns portable HID, normalized-coordinate, and gamepad mapping.

`sourceEventTimeUs` is retained in bounded diagnostics. KeyEvent and MotionEvent `eventTime` and `downTime` use local `SystemClock.uptimeMillis()` in the target process. The implementation never subtracts remote and local clock domains.

## Internal Binder Contract

`IPrivilegedInputInjectionService` is version 1 and exposes synchronous primitive methods for prepare/start/stop, key, complete touch frame, pointer, joystick, reset, capabilities, and snapshot. Touch uses six bounded primitive arrays, one entry per contact, with a strict maximum of 32 contacts. Hot injection calls do not use Bundles, callbacks, SharedMemory rings, queues, or per-event coroutines.

## Bounded State And Reset

The UserService serializes a caller's event through a short internal lock that covers validation, direct asynchronous submission, and bounded bookkeeping. Configuration defaults to 32 state slots and 64 pressed keys per slot. There is no history beyond the active pressed keys, latest touch state, pointer state, and non-neutral joystick state.

Accepted key down records local downTime; key up removes it only after accepted injection. Orphan ups still submit and do not allocate a slot. Touch begins with DOWN and continuation without active state is rejected. The latest accepted touch snapshot preserves original downTime for a single reset CANCEL.

`ResetState` repair is cold, deterministic, and bounded: key ups are ordered by key code; each touch stream receives exactly one CANCEL; active pointer button state receives a neutral CANCEL; non-neutral joystick state receives a neutral motion event. Failures retain their active state and yield `ResetPartial`, with `stateMayRemainInjected` surfaced. Service death likewise sets `stateMayRemainInjected`; there is no automatic rebind or invisible reset claim.

## Lifecycle

`prepare` validates the requested configuration, binds/validates the service through the gateway, resolves the cached privileged API, and creates tracker storage. `start` arms injection. `stop` best-effort resets all state by default and transitions to stopped. `close` performs the same best-effort stop then unbinds the UserService and clears controller references. Restart requires a new prepare/start cycle.

## Threading, Allocation, And Scope

There is no injection worker, HandlerThread, executor, timer, retry loop, pacing loop, coalescer, shell command, Accessibility fallback, virtual device, native/JNI input bridge, or SCL receiver added by this RFC. MotionEvent pointer arrays are bounded per Android touch/pointer/joystick event and every constructed MotionEvent is recycled in `finally`.

## Validation

JVM tests cover local event time versus source metadata, failed and orphan key transitions, touch sequencing and cancellation, pointer/joystick neutral reset, partial bounded capacities, invalid touch IDs, controller lifecycle, and target-UID preparation failure without an untargeted event call. Android instrumentation covers non-privileged KeyEvent construction and explicit display/mode handoff with a fake privileged API.

Shizuku/Sui binding, cross-app delivery, touch/mouse/gamepad delivery, target UID behavior, and multi-display behavior require an authorized device and are reported only when actually run.

## Versioning And Protocol

Architecture Version remains 1.0. SCL Protocol Version remains 1. Native Bridge ABI remains 1. Input Payload Version remains 1. RFC-004D changes no PacketHeader field, PayloadType value, Input Payload V1 byte, Audio/Video payload, NACK, FEC, ClockSync, or native protocol code.

## Deferred Work

- RFC-004E - Input Mapping, Coordinate and Device Semantics
- RFC-004F - End-to-End Reverse Input
- RFC-004G - Input Latency, Reliability and Performance Tuning
