# RFC-002F - End-to-End Video Streaming

Project: Warpnect

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

Video Payload Version: 1

Status: Implemented

## Purpose

RFC-002F connects the completed Phase 2 foundations into explicit transmitter and receiver session orchestration.

Transmitter path:

```text
RFC-002A privileged capture
        |
MediaCodec encoder Surface
        |
RFC-002B hardware AVC encoder
        |
RFC-002C SCL video sender
        |
UDP
```

Receiver path:

```text
UDP
        |
native SCL receiver runtime
        |
bounded reassembly / FEC / NACK
        |
complete Video Payload V1 AccessUnit
        |
codec-owned direct input ByteBuffer
        |
RFC-002D hardware AVC decoder
        |
RFC-002E SurfaceView renderer
```

RFC-002F composes the existing components. It does not change the 21-byte SCL `PacketHeader`, `PayloadType::Video`, Video Payload Version 1, NACK, FEC, or clock-sync wire payloads.

## Transmitter Lifecycle

`DefaultVideoTransmitterSessionController` starts components in deterministic order:

1. Validate the session configuration.
2. Open RFC-002C SCL video transport.
3. Prepare RFC-002B encoder with `SclEncodedVideoSink`.
4. Start the encoder.
5. Start the bounded sender control runtime for incoming SessionControl/NACK datagrams when the native transport supports it.
6. Start RFC-002A capture into the encoder input Surface.
7. Enter `Streaming`.

On partial-start failure, already-acquired resources roll back in reverse ownership order. Capture is stopped before encoder teardown, and transport is closed if it was opened.

Stop order is:

```text
capture stop
encoder drain/stop
sender control stop
transport close
```

The transmitter does not retain encoded access units. Borrowed `MediaCodec` output still flows synchronously through `SclEncodedVideoSink` into native SCL packetization.

## Sender Control Runtime

`NativeVideoSenderControlRuntime` runs on `WarpnectVideoSenderControl`. It waits on the native sender socket for incoming SessionControl datagrams and passes them to RFC-002C control handling. Valid Video NACK requests look up the RFC-001D exact-datagram retransmission cache and send byte-identical retransmissions.

The control runtime does not process raw video frames and does not retain access units. It uses bounded native receive scratch storage.

## Receiver Runtime

`VideoReceiverRuntime` is a portable C++ receiver runtime composed from existing Phase 1/RFC-002C primitives:

- non-blocking `UdpSocket` receive;
- packet decode;
- `LossDetector`;
- RFC-001D NACK encode/send;
- optional RFC-001E FEC recovery;
- bounded multi-message `ReassemblySlot` window;
- Video Payload V1 parser;
- bounded ready-slot ring;
- local telemetry snapshot.

All receive capacities are explicit in `VideoReceiverConfig`, including:

- `max_wire_datagram_size`;
- `max_logical_payload_size`;
- `reassembly_slot_count`;
- `ready_slot_count`;
- `loss_slot_count`;
- `max_nacks_per_pump`;
- `reassembly_timeout_us`;
- optional FEC K/M.

There is no unbounded reassembly map and no unbounded access-unit queue.

## Receive Window

Each reassembly slot owns fixed setup-allocated payload and bitmap storage. Completed AccessUnit bytes remain in that slot until the decoder input source successfully copies them into a codec-owned input buffer.

The ready queue contains only slot indices and small metadata. It does not duplicate access-unit payload bytes.

Capacity exhaustion is explicit:

- `ReassemblyWindowFull`;
- `ReadyWindowFull`;
- `ReassemblyTimeout`;
- `Discontinuity`.

Timeouts release stale incomplete slots and move the receiver back to keyframe-gated recovery.

## Native To Decoder Boundary

`NativeSclVideoReceiverController.inputSource` implements RFC-002D `VideoDecoderInputSource`.

When `WarpnectVideoDecoder` provides a codec-owned input `ByteBuffer`, the input source validates:

- direct buffer requirement;
- native direct-buffer address;
- direct-buffer capacity;
- destination capacity.

JNI uses `GetDirectBufferAddress` and `GetDirectBufferCapacity`. Native code copies the ready AU bytes directly from receiver slot storage into the codec-owned destination buffer and returns only metadata:

```text
size
presentationTimeUs
configGeneration
frameId
keyFrame
```

Kotlin never owns a complete encoded AU payload on the receiver hot path. CSD remains cold-path configuration data and may be represented as small `ByteArray` values for decoder setup.

## Receiver Session Orchestration

`DefaultVideoReceiverSessionController` coordinates:

- native receiver runtime;
- latest StreamConfig event;
- RFC-002E render target Surface;
- RFC-002D decoder lifecycle;
- render policy sink;
- keyframe gating;
- config-generation changes;
- Surface destruction/recreation.

The decoder is prepared only when both a valid StreamConfig and a valid render Surface exist.

If Surface arrives first, the session waits for config. If config arrives first, the session waits for Surface. Once both exist, it prepares the decoder, activates the matching native config generation, starts decoding, and waits for the first keyframe.

## StreamConfig

StreamConfig events originate in native SCL parsing. Kotlin receives decoded metadata only:

```text
codec
width
height
configGeneration
CSD entries
```

The decoder is configured with exact CSD bytes from RFC-002C. RFC-002F does not parse SPS/PPS, normalize NAL units, or change Video Payload V1.

On generation change, the receiver stops the old decoder, updates geometry, prepares the decoder with the new exact CSD, activates the new native generation, and waits for a keyframe.

## Keyframe Gating

The receiver enters `WaitingForKeyFrame` after:

- initial decoder preparation;
- config-generation change;
- Surface/decoder recreation;
- transport discontinuity.

Completed non-key AUs are dropped while awaiting a keyframe. The receiver resumes delivery at the next AU whose Video Payload V1 keyframe bit is set. RFC-002F does not parse AVC NAL units to infer keyframes and does not introduce a remote keyframe-request protocol.

## Ordering

Native receive uses RFC-001C fragment group identity and bounded slot ordering. A completed later message is not delivered before an observed older incomplete group. Frame IDs remain independent from SCL packet sequences and are preserved into decoder metadata.

Frame-ID wrap semantics remain modulo 2^32. Fine-grained ordering and recovery policy tuning remains RFC-002G work.

## Recovery

FEC recovery composes RFC-001E parity handling. Recovered datagrams pass through the same packet decode, loss observation, reassembly, and Video parsing path as ordinary UDP arrivals.

NACK generation composes RFC-001D `LossDetector` and NACK payloads. Sender retransmission uses the exact encoded datagram cache and does not regenerate access units.

No video-specific recovery protocol was introduced.

## Surface Lifecycle

RFC-002E owns the SurfaceView and publishes Surface generation events. RFC-002F stops/releases the decoder when the Surface is destroyed, keeps receiver transport lifecycle explicit, and waits for a new Surface. On Surface recreation, the latest config is reused, a new decoder is prepared, and the session waits for a natural keyframe.

## Thread Model

Runtime contexts:

- UI/main: SurfaceHolder lifecycle and app role actions.
- `WarpnectVideoEncoder`: encoder callbacks and synchronous encoded AU submission.
- `WarpnectVideoSenderControl`: incoming SessionControl/NACK receive for the sender.
- `WarpnectVideoReceiver`: UDP receive, FEC/NACK/reassembly runtime events.
- `WarpnectVideoDecoder`: decoder input/output callbacks and render decisions.

No UDP work runs on the UI thread. Decoder input fill never waits on UDP; it returns `NoData` when no ready AU exists.

## Telemetry

Transmitter snapshots include capture, encoder, transport, sender control, remote endpoint, and last session error.

Receiver snapshots include native receiver counters, active config generation, video dimensions, decoder state, renderer state, delivered frame count, keyframe-gate drops, discontinuities, last PTS, last frame ID, and last session error.

Telemetry is local only. RFC-002F does not change the telemetry wire protocol.

## Tests

Native tests add `scl_video_runtime_tests` for:

- StreamConfig event and exact CSD storage;
- keyframe-gated direct decoder fill;
- ordered multi-message delivery with an observed older incomplete group;
- reassembly timeout and discontinuity.

Existing RFC-002C native tests continue to cover:

- Video wire golden vectors;
- malformed payloads;
- CSD round trip;
- segmented packetization;
- small/large AU;
- sequence/frame/config generation wrap;
- PTS/keyframes;
- FEC recovery;
- NACK fallback;
- UDP loopback sender/retransmission.

JVM tests add:

- receiver prerequisite/keyframe/surface state transitions;
- transmitter rollback for transport and encoder-start preparation failures.

Android instrumentation adds a loopback JNI/runtime test:

```text
SclEncodedVideoSink
        |
native SCL sender
        |
127.0.0.1 UDP
        |
native SCL receiver
        |
direct decoder-input ByteBuffer fill
```

Android instrumentation also adds a generic device-gated end-to-end test:

```text
SyntheticEglSurfaceProducer
        |
RFC-002B MediaCodec encoder
        |
RFC-002C SCL sender over 127.0.0.1 UDP
        |
RFC-002F native receiver runtime
        |
RFC-002D MediaCodec decoder
        |
RFC-002E SurfaceView renderer
```

Existing RFC-002B/RFC-002D/RFC-002E instrumentation continues to cover synthetic MediaCodec encoder, decoder, and SurfaceView rendering components when a suitable device/emulator exists.

## Limitations

RFC-002F intentionally does not implement:

- automatic late-join StreamConfig resend;
- remote keyframe-request protocol;
- adaptive bitrate;
- adaptive FEC;
- packet pacing;
- congestion control;
- automatic MTU discovery;
- discovery, DNS, NSD, mDNS, pairing, authentication, or encryption;
- reconnect strategy;
- audio or input streaming.

The baseline assumes the receiver is running before transmitter output begins so the initial StreamConfig can be received.

## Architecture Compliance

Unchanged:

- Architecture Version: 1.0
- SCL Protocol Version: 1
- Native Bridge ABI Version: 1
- NACK Control Payload Version: 1
- FEC Parity Control Version: 1
- Clock Sync Control Version: 1
- Video Payload Version: 1

No `PacketHeader` change. No new `PayloadType`. No Video Payload V1 change.
