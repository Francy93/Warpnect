# Thread Model

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines Warpnect's concurrency model and the phase-specific execution domains that currently exist.

## Current Phase

Compose runs on the Android UI thread, `CoreOrchestrator` exposes simple state transitions, SCL UDP/video transport is non-blocking but not internally threaded, and the Android codec foundations use dedicated media threads for MediaCodec work.

`UdpSocket` performs no background work. Its owner decides when to call `send_to()` and `receive_from()`.

Loss detection, NACK generation, and retransmission cache lookup also perform no background work. Their owner supplies local monotonic time and decides when to observe sequences, collect due NACKs, and retransmit cached datagrams.

FEC encoding and recovery perform no background work. Their owner supplies caller-owned shard storage, decides when to generate parity, decides when enough shards are available to attempt recovery, and decides when to fall back to NACK.

Clock synchronization and telemetry perform no background work. Their owner records local monotonic timestamps, registers and completes exchanges, adds accepted samples, records telemetry events, and asks for snapshots using caller-supplied `now_us`.

Android privileged video capture uses Android/Shizuku Binder and display-configuration callbacks for control-plane lifecycle only. The production capture data path writes to a caller-owned `Surface`; Warpnect does not run a per-frame application thread, screenshot loop, Binder frame callback, JNI frame transfer, or CPU pixel-copy loop.

Android hardware encoding uses a dedicated `WarpnectVideoEncoder` `HandlerThread` for `MediaCodec` lifecycle and asynchronous output callbacks. The encoded sink callback runs on that media execution context and must return promptly so the codec output buffer can be released.

RFC-002C adds no native networking worker thread. `SclEncodedVideoSink` may synchronously invoke native transport from the `WarpnectVideoEncoder` callback context. Native submission must remain bounded and use non-blocking socket calls because holding a borrowed codec output buffer for excessive time can stall the encoder. RFC-002C does not add pacing, polling, sleep-based draining, or an unbounded video queue.

Android hardware decoding uses a dedicated `WarpnectVideoDecoder` `HandlerThread` for `MediaCodec` lifecycle, input callbacks, output callbacks, output-format changes, frame-rendered diagnostics, EOS/drain, and codec cleanup. The decoder input source is invoked on this thread only when a codec-owned input buffer is available. It must return promptly and must not block on networking, sleep, or disk I/O. RFC-002D retains only bounded MediaCodec input indices when the source reports `NoData`; it does not retain encoded payloads.

RFC-002E SurfaceHolder lifecycle callbacks run on the Android View/window thread and must remain lightweight. Render decisions run synchronously on the `WarpnectVideoDecoder` callback thread through the renderer's `DecodedVideoSink`. The default path does not hop to the main thread per frame, launch a coroutine per frame, or create a dedicated renderer worker thread.

## Future Thread Responsibilities

Future phases should isolate real-time responsibilities into explicit execution domains:

- UI thread: Compose rendering and user interaction.
- Orchestration thread or scope: session lifecycle and role transitions.
- Network thread: future UDP receive/send scheduling, packet pacing if ever adopted, and session-owned loss recovery.
- Encoder thread: `WarpnectVideoEncoder` owns MediaCodec configuration, start/stop, output format changes, encoded output callbacks, EOS/drain, and codec cleanup.
- Decoder thread: `WarpnectVideoDecoder` owns decoder MediaCodec state and invokes renderer decisions for output-Surface release, but not networking or session orchestration.
- Surface lifecycle: Android View/window thread owns SurfaceHolder callbacks and render-target publication.
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
