# Thread Model

## RFC-004F Reverse Input

```text
Source Android UI/input dispatch
  -> capture -> viewport mapper -> JNI/send

Target WarpnectInputReceiver
  -> native readiness wait -> bridge decode -> target mapper -> synchronous Binder injection

Privileged Shizuku UserService Binder context
  -> InputManager ASYNC
```

There is no input sender, mapping, injection, retry, or reorder worker.

## RFC-005A Session Core

~~~text
Any caller
  -> SessionManager synchronized bounded mutation
  -> immutable SessionSnapshot / SessionManagerSnapshot
~~~

RFC-005A owns no discovery worker, handshake worker, network-path worker, coroutine actor, or
session loop. Later Phase 5 RFCs must introduce execution contexts explicitly rather than turning
the session model into an implicit background runtime.

## RFC-005B Local Discovery

```text
WarpnectDiscovery HandlerThread
  -> NSD callbacks
  -> Wi-Fi P2P DNS-SD callbacks
  -> presence-cache mutation
  -> stale-route expiry
  -> advertisement availability updates
```

`AndroidLocalDiscoveryController` owns one explicit low-frequency control context. It has no media
worker, input worker, native UDP receive loop, discovery busy-poll loop, pairing worker, or
handshake worker. Its delayed expiry task runs on the same control thread; it is not a separate
presence-cleanup worker.

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

RFC-002F adds end-to-end session runtime contexts. `WarpnectVideoSenderControl` waits for incoming sender-side SessionControl/NACK datagrams and dispatches them to the native video sender's exact retransmission path. `WarpnectVideoReceiver` owns receiver UDP reads, FEC/NACK/reassembly processing, Video Payload V1 parsing, and coarse runtime events. Neither context processes Android raw frames or decoded pixels.

The receiver decoder input source runs on `WarpnectVideoDecoder`. It never waits for UDP; when no native ready AU exists, it returns `NoData`. When an AU is ready, JNI synchronously fills the codec-owned direct input `ByteBuffer` from bounded native receiver storage.

RFC-002F does not add a rendering worker thread, decoded-frame jitter buffer, unbounded Kotlin event channel, or UDP operation on the UI thread.

RFC-002G adds no metrics thread. Performance counters, recovery age checks, queue high-water marks, and timing fields are recorded inside the existing sender, receiver, decoder, and renderer execution contexts.

The receiver runtime uses `WarpnectVideoReceiver` for recovery deadline expiry, VideoResyncRequest emission, low-frequency RFC-001F clock-sync request emission, and ClockSyncResponse processing. These operations remain bounded and are driven by the receiver event pump.

The sender control context uses `WarpnectVideoSenderControl` for incoming NACK, VideoResyncRequest, and ClockSyncRequest traffic. A valid resync request may notify Kotlin session orchestration to call the existing encoder keyframe request API on `WarpnectVideoEncoder`; it does not move encoded media payloads through the control thread.

RFC-002G keeps render decisions on `WarpnectVideoDecoder` and does not introduce a per-frame UI hop, per-frame coroutine launch, dedicated renderer worker loop, or polling metrics loop.

RFC-003A adds three audio capture contexts. `WarpnectSystemAudioCapture` runs in the privileged Shizuku/Sui process and owns system-audio `AudioRecord.read`, timestamp updates, shared-ring publication, and overrun accounting. `WarpnectSystemAudioDrain` runs in the ordinary app process and owns notification FD waits, borrowed PCM sink callbacks, and ACK records. `WarpnectMicrophoneCapture` runs in the ordinary app process and owns microphone `AudioRecord.read`, timestamp updates, and borrowed PCM sink callbacks.

Audio capture does not run on the UI thread, does not perform PCM per-chunk Binder calls, and does not launch a coroutine or task per PCM chunk.

RFC-003B adds no encoder worker thread. Opus encoding runs synchronously on the caller-serialized PCM sink context:

```text
SystemAudio:
WarpnectSystemAudioDrain
        -> Opus encoder
        -> EncodedAudioSink

Microphone:
WarpnectMicrophoneCapture
        -> Opus encoder
        -> EncodedAudioSink
```

The audio encoder does not introduce a PCM queue, encoder work queue, encoded-packet queue, per-frame coroutine, or per-frame main-thread hop.

RFC-003C adds no audio transport worker thread. Audio transport submission is synchronous on the existing encoded-audio callback path:

```text
SystemAudio:
WarpnectSystemAudioDrain
        -> Opus encoder
        -> SCL audio send

Microphone:
WarpnectMicrophoneCapture
        -> Opus encoder
        -> SCL audio send
```

The native audio sender uses non-blocking UDP and returns `WouldBlock` or `UdpSendFailed` immediately. It does not wait for writability, launch a coroutine per packet, create an encoded-audio queue, or hop through the UI thread.

RFC-003D adds no audio decoder worker thread. Opus decoding runs synchronously on whichever future receive context invokes the decoder:

```text
future audio receive context
        -> Opus decoder
        -> DecodedPcmAudioSink
```

The decoder does not create an encoded input queue, decoded PCM queue, playback queue, playback jitter buffer, per-frame coroutine, or per-frame UI hop.

RFC-003E adds no playback worker queue. PCM submission runs synchronously on the future receive/decoder context that invokes `DecodedPcmAudioSink`, then copies once into the native playback ring:

```text
future audio receive/decoder context
        -> DecodedPcmAudioSink
        -> NativeBridge
        -> native playback ring
```

The consumer is Oboe's high-priority native data callback:

```text
Oboe audio callback
        -> consume native playback ring
        -> fill output buffer
```

The callback does not invoke JNI, Kotlin/Java, a per-frame coroutine, UI-thread work, networking, codec decode, logging, sleeping, allocation, timestamp queries, or blocking locks. Underrun fills the remaining output with PCM16 silence and updates counters.

RFC-003F adds one persistent receiver context per active audio receiver stream:

```text
WarpnectAudioReceiver
        -> UDP readability wait
        -> SCL packet parse/reassembly
        -> Audio Payload V1 parse
        -> sample-position ordering
        -> Opus decode / explicit PLC
        -> playback-ring submit
```

Transmitter media remains synchronous:

```text
SystemAudio:
WarpnectSystemAudioCapture / WarpnectSystemAudioDrain
        -> Opus
        -> SCL UDP

Microphone:
WarpnectMicrophoneCapture
        -> Opus
        -> SCL UDP
```

RFC-003F adds no audio sender worker, encoder worker, decoder worker, playback worker queue, per-frame coroutine launch, per-frame UI hop, or per-frame thread creation. The receiver context may wait in native socket readiness; it must not busy-poll. The Oboe callback remains isolated from UDP, SCL parsing, decoder work, JNI, and Kotlin.

RFC-003G adds one optional low-frequency synchronization context for active A/V sessions:

```text
WarpnectAvSync
        -> query audio presentation anchor
        -> update timestamp-domain validator
        -> publish immutable A/V model
```

`WarpnectAvSync` is not a media worker queue. It does not decode, render, copy PCM, copy video, read UDP payloads, run network I/O, or perform per-frame media scheduling. It sleeps between configured sampling intervals and exits on controller stop/close.

The Oboe callback remains real-time native only. RFC-003G adds fixed metadata anchor publication to the existing callback path, but no JNI, Kotlin/Java, timestamp query, network operation, codec work, allocation, logging, or blocking lock.

Video render decisions remain on `WarpnectVideoDecoder`. `AvSynchronizedVideoRenderPolicy` reads the latest immutable model and returns immediately. It must not query Oboe, wait for the sync worker, call JNI, perform network I/O, allocate media payloads, or hop to the UI thread per frame.

RFC-003H adds no worker thread for PLC, reorder buffering, codec adaptation, Oboe buffer adaptation, or benchmark telemetry. The new performance profile is a cold-path configuration/snapshot view. Host-native benchmarks run only when explicitly invoked from the native build tree.

If a future device-measured playback buffer tuner is enabled, it must run outside the Oboe data callback, remain bounded, and report every buffer-size change. RFC-003H does not enable such a tuner by default.

RFC-004A adds no input capture thread, transport thread, injection thread, JNI callback, or worker queue. Input Payload V1 encode/decode/validation is caller-driven protocol work and has no Android runtime dependency.

RFC-004B adds no input worker thread. Android input capture runs on the normal Android UI/input dispatch context:

```text
Android UI/input dispatch
        -> WarpnectInputCaptureView
        -> raw KeyEvent / MotionEvent mapping
        -> portable Input model
        -> InputEventSink
```

The capture path does not launch a per-event coroutine, post a per-event handler hop, poll input devices on a timer, sample input at VSYNC, perform JNI, perform UDP input transport, or invoke privileged target-side injection. `InputDeviceListener` callbacks use the application looper for device lifecycle notifications only.

## Future Thread Responsibilities

Future phases should isolate real-time responsibilities into explicit execution domains:

- UI thread: Compose rendering and user interaction.
- Orchestration thread or scope: session lifecycle and role transitions.
- Network thread: `WarpnectVideoReceiver` owns current receiver UDP receive/recovery; packet pacing, if ever adopted, remains future work.
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

RFC-004C extends the RFC-004B caller-driven path without creating another execution context:

```text
Android UI/input dispatch
        -> RFC-004B mapping
        -> SclInputEventSink
        -> JNI
        -> native non-blocking UDP send
```

There is no input transport worker, retry worker, pacing timer, per-event coroutine, handler post, or receiver runtime in RFC-004C. The sender is caller-serialized; its steady-state send path does not take a blocking mutex.

RFC-004D adds no app-side injection worker. A caller makes one synchronous Binder transaction to the Shizuku/Sui UserService, where a short bounded state lock protects validation, direct InputManager asynchronous submission, and state updates:

```text
caller context
        -> synchronous Binder
        -> PrivilegedInputInjectionUserService Binder context
        -> InputManager ASYNC submission
```

The UserService contains no UI, handler loop, executor, retry queue, timer, or network work. It never calls JNI or SCL transport from the injection path. Caller serialization is required; service locking is a bounded correctness guard only.

RFC-004E adds no mapping worker. Receiver-side viewport mapping executes synchronously in the existing Android input-dispatch call before `SclInputEventSink`:

```text
Android UI/input dispatch
        -> RFC-004B capture mapper
        -> RemoteVideoViewportInputMapper
        -> RFC-004C sender
```

`WarpnectInputReceiver` calls `InputStateConvergenceController`, `AndroidTargetInputMapper`, and RFC-004D injection synchronously. Geometry and device listener callbacks only invalidate cold-path metadata caches; none of these components polls, sleeps, posts per-event work, or owns a queue.

RFC-004G keeps this model. `InputStateConvergenceController` runs synchronously on
`WarpnectInputReceiver` between the bridge decoder and `AndroidTargetInputMapper`. Immediate
redundant source submissions run on the existing Android input-dispatch caller. There is no
reliability worker, duplicate worker, touch-repair worker, retry timer, reorder timeout, or input
coalescing queue.

## RFC-005C Pairing Control

RFC-005C uses one low-frequency `WarpnectPairing` HandlerThread for explicit pairing only:

```text
WarpnectPairing
        -> cold-path UDP datagram receive
        -> protocol state and crypto
        -> bounded retry and timeout callbacks
        -> pairing-window lifecycle
        -> immutable snapshot publication
```

It does not process media frames, audio frames, reverse-input events, discovery callbacks, or UI
permission requests. It has no per-message coroutine, busy poll, unbounded retry, or media/input
queue. Pairing begins only after an explicit user action or explicit responder pairing window.
