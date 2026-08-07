# Thread Model

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines the intended future concurrency model. It does not require or implement any threads during the current phase.

## Current Phase

The current code has no production threading model. Compose runs on the Android UI thread, `CoreOrchestrator` exposes simple state transitions, and the SCL UDP transport is non-blocking but not internally threaded.

`UdpSocket` performs no background work. Its owner decides when to call `send_to()` and `receive_from()`.

Loss detection, NACK generation, and retransmission cache lookup also perform no background work. Their owner supplies local monotonic time and decides when to observe sequences, collect due NACKs, and retransmit cached datagrams.

FEC encoding and recovery perform no background work. Their owner supplies caller-owned shard storage, decides when to generate parity, decides when enough shards are available to attempt recovery, and decides when to fall back to NACK.

Clock synchronization and telemetry perform no background work. Their owner records local monotonic timestamps, registers and completes exchanges, adds accepted samples, records telemetry events, and asks for snapshots using caller-supplied `now_us`.

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

Concurrent calls on the same UDP transport object require external coordination. The transport does not promise arbitrary shared access.

Concurrent calls on the same loss detector or retransmission cache also require external coordination.

Concurrent calls on the same FEC encoder or FEC recovery block require external coordination.

Concurrent calls on the same clock synchronizer, pending exchange tracker, rolling statistics window, or telemetry collector require external coordination.

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

SCL clock synchronization converts timestamp domains inside SCL only. It must not adjust the operating-system clock or spawn a background synchronization loop.
