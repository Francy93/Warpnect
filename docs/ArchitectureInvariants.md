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

Audio capture MUST produce bounded-lifetime PCM views. Continuous audio MUST NOT be materialized as per-chunk `ByteArray` payloads in production capture paths.

Privileged system-audio PCM MUST NOT traverse Binder payloads. Binder is restricted to setup/control, SharedMemory descriptor transfer, FD transfer, status, and cold-path errors.

System-audio capture SHOULD preserve local playback when the privileged Android AudioPolicy backend supports loopback-and-render operation. Warpnect MUST NOT intentionally redirect game/media audio away from local output as the default.

Audio capture MUST prioritize freshness over accumulating PCM backlog. When bounded PCM capacity is exhausted, capture loss must be visible rather than hidden behind an unbounded queue.

Audio capture timestamps MUST use a monotonic clock domain suitable for later A/V synchronization. Wall-clock time MUST NOT drive audio media timing.

Audio codec framing MAY retain at most one incomplete PCM codec frame. RFC-003B MUST NOT maintain a PCM playback queue, PCM work queue, encoded-packet queue, or multi-frame audio buffer.

Aligned PCM input MAY be encoded directly from its borrowed direct buffer. Partial capture/code-frame mismatch MUST use only a single fixed accumulator sized for one Opus frame.

Encoded Opus packets MUST be borrowed bounded-lifetime views from preallocated native output storage. They MUST NOT be materialized as per-frame Kotlin `ByteArray` values.

Audio codec implementation MUST remain separate from SCL transport/protocol responsibilities. Codec logic belongs under `warpnect::audio`, not `warpnect::scl`.

PCM discontinuities MUST NOT be concealed by concatenating non-contiguous capture samples. Partial codec-frame state must be discarded when capture frame positions reveal a gap.

One encoded Opus packet MUST map to one SCL `AudioFrame`. Warpnect MUST NOT batch multiple Opus packets into one audio transport message to reduce packet-header overhead.

Audio transport MUST NOT introduce an encoded-frame sender queue, audio sender worker queue, or hidden retry backlog. `SclEncodedAudioSink` submits each borrowed Opus packet synchronously to native SCL transport.

Audio capture timestamps MUST be serialized through `PacketHeader::timestamp_us` in monotonic microseconds. The transport boundary performs the explicit `captureTimeNs / 1000` conversion.

Audio sample position MUST remain independent from SCL packet sequence numbers. `first_frame_position` is the audio timeline; packet sequence numbers are transport ordering/recovery metadata.

Opus bytes MUST remain opaque to SCL transport. Audio Payload V1 must not parse, repacketize, merge, split at codec level, or rewrite Opus packets.

Audio decoding MUST be synchronous and queue-free. RFC-003D MUST NOT maintain an encoded packet queue, decoded PCM queue, playback queue, decoder worker queue, or playback jitter buffer.

Decoded PCM MUST be exposed through bounded-lifetime preallocated storage. It MUST NOT be materialized as per-frame Kotlin `ByteArray`, native full-frame staging buffers, `FloatArray`, or `List<Short>` values in production.

Audio source discontinuity metadata MUST be preserved through decode, but it MUST NOT implicitly reset Opus codec state.

Opus packet-loss concealment MUST be an explicit caller-driven decoder mechanism. The decoder MUST NOT infer network loss, inspect SCL sequence gaps, or invoke PLC automatically.

Encoder lookahead MUST remain visible for later timeline alignment and MUST NOT be blindly applied as pre-skip whenever a decoder instance is created.

Android audio playback MUST use a high-priority native callback path. The production output callback MUST NOT require a Java/Kotlin callback for each hardware audio burst.

RFC-003E MAY contain a strictly bounded PCM handoff ring to bridge decoder-production timing and the Android hardware audio clock. This ring is not a network jitter buffer and MUST NOT intentionally accumulate multiple codec frames before playback.

Decoded PCM may cross the decoder/playback ownership boundary through one bounded copy into preallocated native ring storage. It MUST NOT be copied through a Kotlin PCM `ByteArray`, `ShortArray`, unbounded queue, or second application PCM staging buffer.

Playback underrun MUST produce explicit PCM16 silence and counters rather than blocking the real-time audio callback.

Audio presentation timestamps are receiver-local playback diagnostics. They MUST remain distinct from sender capture timestamps and be preserved separately for later A/V synchronization.

End-to-end audio orchestration MUST compose RFC-003A through RFC-003E without duplicating their subsystem responsibilities inside a monolithic controller.

Audio receive storage MUST be strictly bounded transport/reassembly ownership. It is not an intentional network playback jitter buffer and MUST NOT grow with stream duration.

RFC-003F MUST NOT introduce a timed audio reordering wait. The baseline receiver favors immediate freshness and caller-driven Opus PLC for small sample-position gaps.

Late audio frames MUST be discarded rather than inserted behind the active playback timeline.

Large audio gaps MUST be handled by freshness-oriented media reset rather than unbounded PLC generation, retransmission waiting, or preservation of stale playback-ring PCM.

The Oboe real-time callback MUST remain independent from network receive, SCL parsing, codec decoding, JNI, and Kotlin execution.

A/V synchronization MUST NOT assume that two timestamps share a clock domain merely because they use the same unit.

Audio presentation is the receiver-side synchronization master once a valid Oboe source-to-presentation timeline exists.

Video synchronization MUST use the existing scheduled `RenderAt` mechanism and MUST NOT introduce a decoded-video presentation queue.

Opus codec lookahead MUST participate in source-to-presentation timeline mapping. The value comes from the audio StreamConfig and MUST NOT be hard-coded.

A/V synchronization MAY use only the bounded slack already available in the playback ring for startup alignment. It MUST NOT create a continuous audio jitter buffer or automatically increase playback-ring capacity.

If synchronization would require exceeding configured latency bounds, Warpnect MUST degrade synchronization quality rather than growing media latency without bound.

The A/V synchronization layer MUST process timing metadata only. It MUST NOT copy Opus, PCM, AVC, decoded video pixels, or other media payloads.

Audio recovery MUST be constrained by media freshness. A packet or parity block recovered after the useful playout deadline is not considered a latency-path benefit.

The Phase 3 production audio profile MUST NOT add a continuous network jitter buffer unless measurements explicitly justify a bounded policy. The current baseline remains zero timed reorder wait with explicit small-gap PLC.

Playback-ring capacity is a safety and ownership bound. Actual ring occupancy and residence determine application buffering latency; allocated capacity MUST NOT be reported as intentional playback delay.

Audio performance tuning may trade reliability against latency only through explicit, measurable, bounded policy. Experimental reorder, retransmission, FEC, codec, and buffer settings MUST remain visible and must not silently become defaults.

Host-native benchmarks may establish codec/runtime CPU costs and deterministic policy behavior, but they MUST NOT be used as proof of Android hardware output latency, acoustic latency, Bluetooth latency, or physical A/V synchronization.

Android input device IDs, Android keycodes, scan codes, screen pixels, display IDs, and Android framework source constants MUST remain platform-adapter details. They MUST NOT become SCL Input Payload wire semantics.

Absolute input coordinates MUST be normalized to a logical Warpnect remote-control surface so receiver and target display resolution are decoupled.

Gamepad input MUST be represented as a bounded complete common-controller state snapshot rather than a sequence of independent Android axis deltas or platform keycodes.

Input Payload V1 MUST contain an explicit ResetState mechanism so later session and transport layers can prevent stuck remote keys, touches, mouse buttons, and gamepad state without an unbounded recovery history.

Input Payload V1 MUST NOT batch unrelated temporal input events, introduce input-specific fragmentation, add an input reliability protocol, or create an input queue in RFC-004A.

Android input capture MUST remain event-driven. It MUST NOT introduce an input worker queue, timer-based polling loop, per-event coroutine, VSYNC sampling loop, or temporal batching layer.

Android runtime input-device IDs are local capture identifiers only. They MUST NOT become SCL Input Payload wire identity.

MotionEvent source timestamps MUST be preserved. Capture callbacks MUST NOT replace them with callback receipt time as the event timestamp.

Relative pointer axes MUST consume historical samples supplied by Android because incremental relative motion is not accumulated into the current sample.

RFC-004A ResetState MUST be emitted on capture lifecycle boundaries that can otherwise leave remote state logically pressed, including focus loss, session stop, device removal, pointer-capture loss, and local recovery resets where applicable.

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
