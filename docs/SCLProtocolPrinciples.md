# SCL Protocol Principles

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines protocol principles that future SCL implementation RFCs must preserve.

It is not an implementation specification.

## Purpose

SCL exists to maintain coherent interactive state between endpoints under strict latency constraints.

SCL is designed for:

- Remote gaming.
- Interactive applications.
- Low-latency remote presence.
- Synchronized device experiences.

The goal is minimum perceived divergence between endpoints, not maximum throughput.

## State Coherence Over Delivery

Traditional streaming systems often optimize bandwidth, compression ratio, and reliability.

SCL prioritizes:

- Temporal correctness.
- Responsiveness.
- Synchronization.
- Useful delivery within a latency budget.

A late packet can be less valuable than a missing packet.

## UDP-First Direction

SCL uses a UDP-first transport architecture.

RFC-001B provides the first UDP transport primitive: non-blocking, packet-agnostic datagram I/O over IPv4 and IPv6 loopback-capable endpoints.

Reliability-related features belong above UDP inside SCL, including:

- Packet loss detection.
- NACK and selective recovery.
- FEC.
- Time-sensitive retransmission decisions.

RFC-001D implements bounded packet loss detection, compact NACK payloads, and exact-datagram retransmission cache primitives.

RFC-001E implements proactive systematic Reed-Solomon FEC over `GF(256)` with primitive polynomial `0x11D`. FEC recovers erased original encoded SCL datagrams when at least `K` shards are available from a `K + M` block. RTT adaptation, congestion control, pacing, adaptive FEC ratios, and recovery policy remain deferred.

RFC-001F implements clock-domain measurement and bounded telemetry. It measures RTT, jitter, clock offset, and drift; it does not adapt transport behavior.

UDP transport preserves native datagram semantics. It does not provide delivery, ordering, retransmission, fragmentation, pacing, or congestion control.

## Packet Contract

Every SCL packet header must expose:

- Protocol Version.
- Flags.
- Sequence number.
- Timestamp.
- Payload type.
- Slice index.
- Total slices.

Packet layout is versioned. Breaking layout changes require a Protocol Version increment.

The Version 1 wire header is exactly 21 bytes and is encoded in big-endian byte order.

The runtime `PacketHeader` is not the wire representation and must not be transmitted directly.

The UDP transport must not parse this packet contract. Packet codec and transport compose through byte spans only.

## Fragmentation Contract

Fragmentation uses the existing Version 1 slice metadata.

Each fragment is one SCL packet and one UDP datagram candidate.

Each fragment receives a unique sequence number:

```text
sequence_number = base_sequence_number + slice_index
```

Reassembly derives:

```text
base_sequence_number = sequence_number - slice_index
```

Both operations use unsigned 32-bit modular arithmetic.

No Version 1 fragmentation code may add a wire `message_id`, require slice 0 first, rely on arrival order, or assign the same sequence number to every slice.

Fragmentation budget selection is caller-owned and is not a frozen global MTU.

## Recovery Contract

Loss detection is scoped to a caller-owned recovery domain. A recovery domain is an ordered sequence context chosen by the caller; it is not globally inferred from the SCL packet header.

Sequence ordering uses unsigned 32-bit modular arithmetic. The half-range distance `0x80000000` is ambiguous and must not be ordered silently.

NACK is encoded as a `SessionControl` payload subtype with control type `1` and control payload version `1`. It uses a 16-byte payload containing a target payload type, base sequence number, and 64-bit missing bitmap.

A retransmission reuses the exact original encoded SCL datagram. Recovery code must not regenerate timestamps, flags, sequence numbers, slice metadata, or payload bytes.

Recovery primitives are caller-driven and threadless. They do not own UDP sockets, timers, queues, or background workers.

## FEC Contract

FEC protects complete encoded original SCL data datagrams, not only packet payload bytes.

Each systematic data shard stores:

```text
uint16 original datagram length
encoded SCL datagram bytes
zero padding
```

Parity is carried by `PayloadType::SessionControl` with `SessionControlType::FecParity = 2` and FEC parity control version `1`. This does not introduce a new `PayloadType` and does not modify the 21-byte packet header.

One FEC block protects consecutive data sequence numbers in one caller-defined recovery domain:

```text
data shard i -> base_sequence_number + i modulo 2^32
```

FEC is erasure-only. It can reconstruct missing shards when enough valid shards are present, but it does not detect arbitrary silent corruption.

FEC primitives are bounded, caller-driven, and UDP-independent. They do not generate NACKs, retransmit parity, create threads, own sockets, tune parity ratios, or implement congestion control.

## Encoded Video Payload Contract

Video uses existing `PayloadType::Video` and an inner Video Payload Version. RFC-002C defines Video Payload Version 1 without changing the SCL packet header or adding a payload type.

Version 1 video payloads carry either StreamConfig or AccessUnit messages. StreamConfig preserves codec-specific data exactly. AccessUnit preserves encoded AVC bytes, keyframe flag, frame ID, configuration generation, and the RFC-002B MediaCodec presentation timestamp carried in `PacketHeader::timestamp_us`.

SCL video packetization must not parse or normalize AVC NAL units. Video recovery must reuse existing SCL fragmentation, exact-datagram retransmission/NACK, and FEC primitives rather than introducing a second recovery protocol.

## Encoded Audio Payload Contract

System and microphone audio use existing `PayloadType::SystemAudio` and `PayloadType::MicrophoneAudio` with an inner Audio Payload Version. RFC-003C defines Audio Payload Version 1 without changing the SCL packet header or adding a payload type.

Version 1 audio payloads carry either StreamConfig or AudioFrame messages. StreamConfig carries Opus decoder-critical metadata: sample rate, channel count, codec frame duration, configuration generation, and encoder lookahead. AudioFrame carries one raw Opus packet plus the first PCM frame position, timestamp quality, discontinuity flag, and capture timestamp in `PacketHeader::timestamp_us`.

SCL audio packetization must not parse Opus internals, aggregate multiple Opus packets, maintain an encoded-audio queue, or enable audio recovery policy by default. Existing fragmentation remains the only Version 1 fragmentation model.

## Clock Synchronization Contract

Latency and ordering decisions must use monotonic clocks.

Cross-device timestamps must not be compared directly unless a synchronization model exists.

Clock synchronization uses `PayloadType::SessionControl` with `SessionControlType::ClockSyncRequest = 3`, `SessionControlType::ClockSyncResponse = 4`, and clock sync control version `1`.

Clock sync uses a four-timestamp exchange:

```text
t0 = initiator local send time
t1 = responder local receive time
t2 = responder local send time
t3 = initiator local receive time
```

RTT removes responder processing time:

```text
RTT = (t3 - t0) - (t2 - t1)
```

Offset is an estimate, not an exact truth:

```text
offset = ((t1 - t0) + (t2 - t3)) / 2
```

SCL must never modify operating-system clocks. It only maintains an internal affine model for timestamp conversion.

`PacketHeader::timestamp_us` is not automatically a UDP send timestamp. One-way-delay estimation requires explicit caller-provided timestamp semantics and a synchronized model.

## Telemetry Contract

Telemetry is local runtime measurement only. RFC-001F does not define a `PayloadType::Telemetry` wire schema.

Telemetry counters must be saturating and observational. Recording telemetry must not modify sequence tracking, FEC output, NACK timing, UDP data, packet bytes, pacing, congestion behavior, bitrate, or datagram sizing.

## Channels

SCL separates channel identity from application UX.

Future channels include video, system audio, microphone audio, input, telemetry, handshake, and session control.

Handshake is for capability and compatibility negotiation.

Session control is for active session lifecycle.

## Platform Independence

SCL must remain independent from Android, Compose, Activity lifecycle, Shizuku, and Kotlin concepts.

Platform-specific code belongs at the edge.

## Compatibility Domains

SCL has two independent compatibility domains:

- Protocol Version 1 for packet and protocol compatibility.
- Native ABI Version 1 for Kotlin/JNI/native bridge compatibility.

They must evolve independently.

## Final Principle

SCL is not a faster screen streaming protocol.

SCL is a synchronization layer designed to make distributed interactive state feel local.
