# Thread Model

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines the intended future concurrency model. It does not require or implement any threads during the current phase.

## Current Phase

The current skeleton has no production threading model. Compose runs on the Android UI thread, and `CoreOrchestrator` exposes simple state transitions.

## Future Thread Responsibilities

Future phases should isolate real-time responsibilities into explicit execution domains:

- UI thread: Compose rendering and user interaction.
- Orchestration thread or scope: session lifecycle and role transitions.
- Network thread: UDP receive/send, packet scheduling, loss recovery.
- Encoder thread: video capture handoff and MediaCodec encode coordination.
- Decoder thread: decode and render handoff.
- Audio capture thread: microphone/system audio capture.
- Audio render thread: playback scheduling.
- Input thread: reverse input receive and injection coordination.
- Telemetry thread or collector: aggregation and diagnostic publishing.

## Thread Ownership

Each thread or execution domain must have one owner. Shared mutable state must be minimized and explicit.

The orchestrator may coordinate thread startup and shutdown, but it must not run hot-path packet, codec, or audio loops.

## Hot-Path Rules

Future hot paths should avoid:

- Heap allocation during steady-state frame or packet processing.
- Blocking calls.
- Lock contention.
- UI thread calls.
- Android lifecycle assumptions.

## Cross-Thread Communication

Future cross-thread communication should use bounded queues, explicit ownership transfer, or lock-free structures only when justified by measurement.

Backpressure must be visible to the orchestrator and telemetry layer.

## Timing

Timing data must be captured from a monotonic clock. Cross-thread timestamps must use the same timing domain unless a documented synchronization transform exists.
