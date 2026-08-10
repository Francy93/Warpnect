# Architecture Invariants

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines permanent architectural laws for Warpnect and SCL. These rules override local convenience.

## Core Independence

The SCL core MUST remain platform independent.

Android APIs MUST NOT leak into SCL headers, transport code, packet code, telemetry code, or synchronization code.

SCL code MUST be suitable for future reuse on Windows, Linux, and macOS.

## Layer Isolation

Warpnect UI MUST NOT access transport directly.

Networking MUST NOT depend on UI.

Kotlin MAY orchestrate session state and user intent. Kotlin MUST NOT implement UDP transport, packet serialization, fragmentation, FEC, or protocol recovery.

C++ MAY implement SCL protocol, timing, telemetry, and transport. C++ MUST NOT implement Android app lifecycle, UI state, or user experience policy.

UDP transport MUST remain packet-agnostic. It moves opaque datagrams and MUST NOT inspect SCL packet headers, payload types, sequence numbers, or timestamps.

Fragmentation and reassembly MUST remain independent from UDP sockets. They operate on packet views, fragment views, and caller-owned buffers only.

Loss detection, NACK generation, and retransmission caching MUST remain independent from UDP sockets. They operate on sequence numbers, NACK payloads, and cached datagram byte spans only.

FEC MUST remain independent from UDP sockets and from NACK policy. It operates on caller-provided encoded datagram spans, caller-owned shard storage, and explicit SessionControl parity payloads only.

Clock synchronization MUST remain independent from UDP sockets. It operates on explicit request/response payloads, caller-supplied monotonic timestamps, pending-exchange storage, and bounded sample storage only.

Telemetry MUST remain observational. It may record SCL events and expose snapshots, but it MUST NOT change transport, loss recovery, FEC, pacing, congestion, bitrate, datagram sizing, or application behavior.

Android video capture MUST remain outside the SCL C++ core. The Android application/platform layer owns capture orchestration, privileged service binding, display APIs, and lifecycle.

Android video capture uses a caller-owned `android.view.Surface` as the media data-plane boundary. Capture may borrow the Surface for a running session, but it MUST NOT own or release it.

Warpnect MUST NOT silently fall back from privileged display capture to MediaProjection. If privileged capture is unavailable, the app must report an explicit typed state.

Video frame pixels MUST NOT traverse Binder, JNI, Kotlin callbacks, or application-owned CPU buffers in the production capture path. Binder is a control plane; `Surface` is the video data plane.

Raw video MUST travel from Android capture to the hardware encoder through a `Surface` path without mandatory application CPU pixel readback.

MediaCodec encoded output buffers are borrowed and consumed synchronously. RFC-002B MUST NOT maintain an unbounded encoded-frame queue or require a mandatory per-access-unit `ByteArray` copy.

Warpnect MUST NOT silently replace a verified hardware encoding requirement with a software encoder. If hardware AVC encoding cannot be confirmed, the transmitter path must report an explicit typed unavailable state.

Encoded video MUST cross Kotlin/JNI as a borrowed direct `ByteBuffer`. RFC-002C MUST NOT require a complete access-unit `ByteArray` copy before native packetization.

The logical Video payload is versioned independently inside `PayloadType::Video`. Adding or changing video payload layout must not change the 21-byte SCL `PacketHeader` or add a new payload type without a protocol RFC.

SCL video packetization MUST NOT parse, normalize, merge, split, or otherwise rewrite AVC NAL units. RFC-002C transports framework-provided CSD and encoded access-unit bytes exactly.

Video recovery MUST compose the existing SCL fragmentation, retransmission/NACK, and Reed-Solomon FEC primitives. RFC-002C MUST NOT introduce a parallel video-specific recovery protocol.

RFC-002D decodes compressed AVC access units directly into a caller-owned `Surface`. Decoded raw pixels MUST NOT traverse an application CPU pipeline, `ImageReader`, `Bitmap`, raw output `ByteBuffer`, or YUV/RGBA conversion path in production.

Decoder input is driven by MediaCodec-owned input slots. RFC-002D MUST NOT maintain a hidden encoded-access-unit payload queue; it may retain only bounded MediaCodec input indices.

Every decoded access unit MUST match the decoder session's active codec configuration generation. A generation mismatch must surface an explicit reconfiguration-required state rather than decoding against stale CSD.

Video rendering policy is outside RFC-002D. The decoder may expose render/drop/scheduled-release mechanisms, but frame pacing, jitter-buffer scheduling, late-frame policy, and renderer ownership belong to later RFCs.

Decoded video MUST be presented through a `SurfaceView`/`Surface` pipeline without mandatory decoded-pixel CPU readback. RFC-002E MUST NOT use a production `ImageReader`, `Bitmap`, raw YUV/RGBA buffer, `TextureView` fallback, OpenGL layer, Vulkan layer, or PixelCopy path to present decoded frames.

RFC-002E MUST NOT maintain a decoded-frame queue, presentation queue, or jitter buffer. The renderer supplies an immediate render/drop/scheduled decision and MediaCodec output indices must be released promptly.

Remote media presentation timestamps MUST NOT be directly interpreted as receiver-local render deadlines. Scheduled rendering requires an explicit receiver-local monotonic nanosecond timestamp.

Default rendering favors immediate presentation. It MUST NOT add an intentional application frame delay, per-frame main-thread hop, or dedicated renderer worker loop.

End-to-end video orchestration MUST compose RFC-002A through RFC-002E rather than duplicating their subsystem responsibilities inside a monolithic controller.

Native receiver buffering MUST be strictly bounded and represents transport/reassembly ownership, not a hidden decoder payload queue.

A complete reassembled encoded AU MUST be copied directly from native receiver storage into a MediaCodec-owned decoder input buffer without an intermediate full-access-unit Kotlin `ByteArray`.

Default end-to-end rendering MUST NOT introduce an application jitter buffer.

Warpnect video recovery MUST be freshness-bounded. Incomplete stale video frames are abandoned rather than recovered indefinitely, and stale predictive frames MUST NOT block receiver progress.

Completed in-order encoded access units MUST be delivered toward the decoder promptly when a codec input slot is available. Warpnect MUST NOT add an intentional complete-AU playback buffer.

Video resynchronization requests MAY trigger current StreamConfig resend and a hardware encoder sync-frame request, but they MUST NOT require retaining media access units outside the existing exact-datagram retransmission cache.

Performance telemetry MUST be bounded. Counters, high-water marks, fixed histograms, and fixed sample rings are allowed; unbounded per-frame diagnostic history is not.

## JNI Rules

JNI is only a bridge.

JNI MUST NOT contain:

- Business logic.
- Networking logic.
- Packet serialization logic.
- Session policy.
- UI logic.

JNI MAY contain:

- Type conversion.
- Native call dispatch.
- Explicit error/status mapping.
- Direct `ByteBuffer` validation and address/capacity lookup for synchronous native calls.

## Naming Rules

Use Warpnect for application concepts.

Use SCL for protocol and synchronization concepts.

The Android package root MUST be:

```text
io.warpnect
```

The native namespace root SHOULD remain:

```cpp
warpnect::scl
```

Temporary sample package names are forbidden.

The native shared library target MUST be:

```text
scl_core
```

## Dependency Direction

Allowed direction:

```text
UI -> Orchestrator -> Platform bridge -> JNI -> SCL
```

Forbidden direction:

```text
SCL -> JNI -> Android lifecycle -> UI
```

SCL may report status and telemetry upward. It must not make application decisions.

## Performance Rules

Future hot paths MUST avoid:

- Unnecessary heap allocations.
- Unnecessary copies.
- Blocking operations.
- Lock contention.
- String parsing or other string processing.
- UI thread access.

Initialization paths may use richer structures when they do not affect steady-state latency.

UDP `send_to`, UDP `receive_from`, and runtime endpoint conversion MUST avoid heap allocation, hidden retries, internal queues, and blocking calls.

Fragment generation MUST avoid heap allocation and payload copies. Reassembly MAY copy fragment bytes once into caller-owned storage and MUST NOT allocate storage internally.

Loss observation, NACK collection, NACK encode/decode, recovery sequence iteration, cache lookup, and retransmission resolution MUST avoid heap allocation and blocking operations. Cache insertion MAY copy one encoded datagram into caller-owned bounded storage.

GF(256) arithmetic, Reed-Solomon parity generation, FEC parity encode/decode, FEC data/parity acceptance, and FEC reconstruction MUST avoid heap allocation and blocking operations. FEC MAY copy encoded datagrams once into caller-owned shard storage.

Clock sync control encode/decode, pending-exchange lookup, sample calculation, clock-model fitting, timestamp conversion, telemetry event recording, telemetry snapshots, and rolling-statistics insertion MUST avoid heap allocation and blocking operations.

## Timing Rules

All latency measurements MUST use monotonic clocks.

Timestamp domains MUST be documented.

Cross-device timestamps MUST NOT be compared directly unless a synchronization model exists.

Clock synchronization converts timestamp domains; it MUST never modify endpoint operating-system clocks.

Wall-clock time MUST NOT drive SCL protocol latency, recovery, or clock synchronization decisions.

`PacketHeader::timestamp_us` MUST NOT be treated as an implicit UDP send timestamp unless the caller explicitly documents that semantic meaning for a measurement.

Telemetry MUST distinguish capture, encode, network, decode, render, and input round-trip stages.

## Protocol Stability

Packet structures MUST be versioned.

Breaking packet layout changes require explicit protocol version changes.

Breaking native bridge changes require explicit ABI version changes and documentation.

Packet headers MUST use fixed-width integer types.

Wire formats MUST be defined by explicit byte layout, field offsets, and byte order.

Runtime structures MUST NOT be used as the serialization mechanism.

## Architecture Change Rules

Changes to Warpnect/SCL separation, package root, namespace root, native library name, or UDP-first transport philosophy require an ADR.

## Ownership Rules

Session state has one owner: the Warpnect orchestrator.

Transport state has one owner: the SCL transport engine.

Telemetry samples must have clear ownership or immutable handoff.

Transport instances are not internally threaded. Concurrent access to one transport object requires explicit external coordination.

Loss detectors and retransmission caches are not internally threaded. Each recovery object has one owning execution context; concurrent use requires external coordination.

FEC encoders and recovery blocks are not internally threaded. Each FEC object has one owning execution context; concurrent use requires external coordination.

Clock synchronizers, pending exchange trackers, rolling statistics windows, and telemetry collectors are not internally threaded. Each object has one owning execution context; concurrent use requires external coordination.

Android capture controllers may use Android control-plane callbacks for display lifecycle and Binder state, but MUST NOT introduce a per-frame application thread, screenshot polling loop, or CPU pixel-copy loop for production capture.

## Failure Rules

Stubs MUST be honest. If a feature is unavailable, it must return an explicit not-implemented or unavailable result.

Privileged failures MUST have a clean path toward user recovery.

Silent fallback behavior is forbidden in low-latency paths unless it is explicitly documented and measured.

## Phase Discipline

Do not implement future phases inside architecture or cleanup work.

RFC-001 may implement SCL networking. Codec, audio, input, discovery, diagnostics, optimization, desktop, and production work remain separate phases.
