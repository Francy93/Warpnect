# Warpnect Architecture

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect is an ultra-low-latency remote presence application. Its architecture is split into two permanent conceptual layers:

- Warpnect: the user-facing application and ecosystem.
- SCL: the State Coherence Layer protocol and synchronization core.

This split is architectural, not cosmetic. Warpnect owns product behavior, Android lifecycle, UI, orchestration, and user-facing state. SCL owns protocol-level transport concepts, packet layout, timing, telemetry, and future cross-platform synchronization.

RFC-000C freezes Architecture Version 1.0 as the baseline before SCL core networking implementation.

## Current Repository Shape

The current repository is an Android application with one app module:

- `app/src/main/java/io/warpnect`: Android and Warpnect application code.
- `app/src/main/cpp`: native C++20 SCL skeleton and JNI bridge.
- `docs`: architecture documentation and permanent invariants.

Phase 0 and RFC-000A do not implement streaming, networking, audio, codec, discovery, privileged operations, or input injection.

## Warpnect Layer

The Warpnect layer is written in Kotlin. It owns:

- Android activity lifecycle.
- Compose UI.
- Role and application state.
- High-level orchestration.
- Shizuku availability and future permission prompts.
- User-facing session flow.

Warpnect may request work from SCL through a narrow native bridge later. It must not know UDP packet layout, serialization details, congestion behavior, FEC, or low-level timing internals.

## SCL Layer

SCL is written in C++20. It owns:

- Packet structures.
- Transport interfaces.
- Telemetry timing structures.
- Future UDP engine behavior.
- Future synchronization and recovery primitives.

SCL must remain portable across Android, Windows, Linux, and macOS. Android APIs must not appear in SCL headers or transport logic. Platform-specific binding belongs at the edge.

## Android Role

Android currently provides:

- App entry point through `MainActivity`.
- Compose surface through `MainScreen`.
- App state through `CoreOrchestrator`.
- Stubs for Shizuku, discovery, video, audio, and input modules.

Android code coordinates features. It does not implement the protocol core.

## C++ Role

C++ currently provides:

- Stable SCL packet type declarations.
- Stable SCL telemetry structures.
- A UDP engine interface.
- A native bridge metadata surface.
- A JNI source file that exposes bridge functions only.
- A native shared library target named `scl_core`.

C++ does not own Warpnect UI state, Android permissions, Android lifecycle, or product flows.

## Future Desktop Architecture

Future desktop clients should reuse SCL C++20 code directly. Platform-specific desktop layers should provide capture, render, audio device, input, and socket integration without changing SCL protocol semantics.

The desired shape is:

- Shared SCL core: packet, transport, telemetry, synchronization.
- Android platform layer: MediaCodec, Shizuku, Android input, Android audio.
- Windows platform layer: Windows capture, input, socket, render integration.
- Linux platform layer: PipeWire, input, socket, render integration.
- macOS platform layer: ScreenCaptureKit, input, socket, render integration.

SCL must not assume which platform is hosting it.
