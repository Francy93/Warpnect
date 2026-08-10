# SCL Protocol Design

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document records the SCL packet foundation, UDP transport boundary, Version 1 fragmentation semantics, Version 1 NACK recovery control payload, Version 1 Reed-Solomon FEC parity control payload, and Version 1 clock synchronization control payloads. It does not define discovery, encryption, codecs, audio, video, input injection, or session negotiation.

## Protocol Purpose

SCL exists to keep two endpoints coherent under strict latency pressure. It will eventually transport synchronized channels for:

- Video.
- System audio.
- Microphone audio.
- Input events.
- Telemetry.
- Handshake.
- Session control.

## Version 1 Wire Header

The SCL Version 1 wire header is exactly 21 bytes.

All multi-byte fields use network byte order, meaning big-endian.

| Offset | Size | Field | Wire type |
| ---: | ---: | --- | --- |
| 0 | 2 | `protocol_version` | unsigned 16-bit |
| 2 | 2 | `flags` | unsigned 16-bit |
| 4 | 4 | `sequence_number` | unsigned 32-bit |
| 8 | 8 | `timestamp_us` | unsigned 64-bit |
| 16 | 1 | `payload_type` | unsigned 8-bit |
| 17 | 2 | `slice_index` | unsigned 16-bit |
| 19 | 2 | `total_slices` | unsigned 16-bit |

The public constant is:

```cpp
kPacketHeaderWireSize == 21
```

Field offsets are exposed as named constants in `protocol.h`.

## Wire and Runtime Separation

The wire representation is a sequence of bytes. It must not depend on:

- C++ structure packing.
- Host endianness.
- Native alignment.
- `reinterpret_cast`.
- Direct transmission of a runtime structure.
- Raw copying of a runtime structure.

`PacketHeader` is the aligned runtime representation used after decoding and before encoding. It is not packed and is not serialized directly.

## Payload Types

Version 1 payload type values are frozen:

| Value | Payload type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Video` |
| 2 | `SystemAudio` |
| 3 | `MicrophoneAudio` |
| 4 | `Input` |
| 5 | `Telemetry` |
| 6 | `SessionControl` |
| 7 | `Handshake` |

`Unknown` is reserved and must not be emitted by encoders.

Decoders must distinguish reserved `Unknown` from unsupported numeric payload values.

`Handshake` is reserved for capability negotiation, endpoint compatibility, and protocol compatibility checks.

`SessionControl` is reserved for active session lifecycle and versioned control/recovery payloads after negotiation, including NACK and FEC parity subtypes.

## Flags

The `flags` field is 16 bits.

Version 1 assigns no feature semantics to individual flag bits. Packet encoding and decoding must preserve the full 16-bit value without rejecting unknown or unassigned bits.

Future RFCs may define flag semantics.

## Validation Rules

Version 1 validation rules:

- `protocol_version` must equal Protocol Version 1.
- `payload_type` must be a defined Version 1 value.
- `payload_type` must not be `Unknown`.
- `total_slices >= 1`.
- `slice_index < total_slices`.
- Every 32-bit sequence number is structurally valid.
- Every 64-bit timestamp value is structurally valid.
- All flag bits are preserved and are structurally valid.

Validation does not implement recovery, loss detection, retransmission, ordering policy, or timestamp comparison.

## Packet Views

Decoded packets use a non-owning `PacketView`.

The view contains:

- A decoded runtime `PacketHeader`.
- A `std::span<const std::byte>` payload view.

The payload span points into the caller-provided packet buffer, beginning at byte offset 21.

The caller must keep the source packet buffer alive while the view is used.

Parsing does not allocate and does not copy payload bytes.

## Timestamp Domain

`timestamp_us` represents microseconds in a monotonic timestamp domain.

A packet timestamp is meaningful only inside its documented clock domain unless a clock synchronization model has been established.

RFC-001F does not automatically interpret `PacketHeader::timestamp_us` as a UDP transport-send timestamp. One-way delay estimation is valid only when the caller explicitly supplies a remote timestamp whose semantics are suitable for delay measurement.

SCL must not use wall-clock time, Unix epoch time, local time, or calendar time for protocol latency decisions.

## Allocation and Complexity

Packet header encoding and decoding are allocation-free and constant time.

Packet parsing is allocation-free and constant time.

Full packet serialization writes the header and copies the caller-provided payload exactly once into the caller-owned output buffer.

No packet codec function owns payload memory.

## UDP Transport

RFC-001B establishes a packet-agnostic UDP datagram transport.

Transport APIs operate on caller-owned `std::span` buffers and platform-neutral endpoints. Public UDP headers must not expose `sockaddr`, Winsock, POSIX socket handles, Android framework types, JNI, or Kotlin concepts.

One successful `send_to()` call sends exactly one UDP datagram. One successful `receive_from()` call receives exactly one UDP datagram. The transport does not emulate stream semantics, concatenate datagrams, split datagrams, retry sends, hide loss, or guarantee ordering.

UDP transport is non-blocking. An empty receive queue returns `WouldBlock`; this is not an error condition and must not cause sleeping, spinning, hidden retries, or logging in production code.

Transport truncation is explicit. If an incoming datagram exceeds the caller-provided receive buffer, the result is `DatagramTruncated` and the datagram must not be passed upward as a complete SCL packet.

The structural UDP payload limit is:

```cpp
kUdpMaxDatagramPayloadSize == 65507
```

This is the IPv4 theoretical UDP payload maximum. It is not the SCL MTU and must not be treated as a recommended packet size.

SCL will normally use much smaller datagrams to avoid IP-layer fragmentation. Application-level fragmentation and selective NACK recovery are implemented above UDP. Packet sizing policy, path MTU behavior, pacing, FEC, and congestion behavior remain deferred to later RFCs.
SCL FEC also uses caller-selected datagram budgets and does not freeze a protocol MTU.

The UDP layer moves opaque datagrams. It must not inspect `PacketHeader`, validate protocol versions, interpret `PayloadType`, read sequence numbers, parse timestamps, or mutate packet bytes.

## Fragmentation And Reassembly

RFC-001C defines deterministic application-level fragmentation for logical SCL payloads that exceed a caller-selected datagram budget.

The Version 1 binary header is unchanged. Fragmentation uses the existing `sequence_number`, `slice_index`, and `total_slices` fields.

One algorithmic fragment is one on-wire SCL slice:

```text
fragment index == slice_index
fragment count == total_slices
```

The datagram budget is caller-selected:

```text
max_datagram_size = 21-byte SCL header + fragment payload
```

The fragment payload capacity is:

```text
max_fragment_payload = max_datagram_size - kPacketHeaderWireSize
```

No global SCL MTU is frozen by Protocol Version 1. Test budgets such as 64, 128, 256, 512, or 1200 bytes are examples only.

For a logical payload of size `P` and fragment payload capacity `C`, non-empty payloads use:

```text
total_slices = ceil(P / C)
```

An empty logical payload is represented as one fragment:

```text
slice_index = 0
total_slices = 1
payload size = 0
```

The Version 1 fragmentation sequence rule is:

```text
For slice i:

sequence_number = base_sequence_number + slice_index

Therefore:

base_sequence_number =
    sequence_number - slice_index

All arithmetic is modulo 2^32.
```

This means each transmitted UDP datagram has a unique packet-level sequence number, including slices from the same logical payload.

A runtime-only fragment group key is derived from:

- Protocol version.
- Base sequence number.
- Timestamp.
- Payload type.
- Flags.
- Total slice count.

This key is never serialized as an additional wire structure.

Fragmentation is zero-copy. `FragmentView` payload spans reference the caller-owned logical payload.

Reassembly copies fragment payload bytes once into caller-owned persistent storage. The reassembly slot owns no heap buffer and represents one fragment group at a time.

Reassembly supports out-of-order fragments, final-slice-first arrival, duplicate detection, conflicting duplicate rejection, and reset/reuse.

Reassembly does not implement loss detection, NACK, retransmission, timeout eviction, FEC, pacing, RTT, or clock synchronization. RFC-001D recovery primitives compose with reassembly through packet sequence numbers and decoded packet views.

## Video Payload Version 1

RFC-002C defines the Version 1 logical payload carried inside `PayloadType::Video`.

This payload version is independent from:

- SCL Protocol Version.
- Native Bridge ABI Version.
- NACK Control Payload Version.
- FEC Parity Control Version.
- Clock Sync Control Version.

Constants:

```cpp
kVideoPayloadVersion == 1
PayloadType::Video == 1
```

The 21-byte SCL `PacketHeader` is unchanged. RFC-002C does not add a new `PayloadType`.

### Video Message Types

| Value | Message type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `StreamConfig` |
| 2 | `AccessUnit` |

Only `StreamConfig` and `AccessUnit` are emitted by Version 1 encoders.

### Video Codec IDs

| Value | Codec |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Avc` |

RFC-002C implements AVC/H.264 only. Android `MediaCodecInfo.CodecProfileLevel` constants are not protocol codec IDs.

### Common Video Message Header

Every Version 1 video logical payload begins with this exact 12-byte header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `video_version` |
| 1 | 1 | `message_type` |
| 2 | 1 | `codec` |
| 3 | 1 | `flags` |
| 4 | 4 | `config_generation` |
| 8 | 4 | `item_id` |

Constants:

```cpp
kVideoMessageHeaderWireSize == 12
```

All multi-byte video payload fields use big-endian byte order.

### Configuration Generation

`config_generation` identifies the decoder configuration required by an access unit.

Rules:

```text
0 = invalid / no active configuration
first accepted configuration = 1
subsequent format change = previous + 1
UINT32_MAX + 1 wraps to 1, skipping 0
```

Every `AccessUnit` must reference a successfully emitted non-zero configuration generation.

### Item ID

For `StreamConfig`:

```text
item_id = 0
```

For `AccessUnit`:

```text
item_id = frame_id
```

The frame ID is a 32-bit wrapping access-unit counter. It is independent from SCL packet sequence numbers.

### StreamConfig

`StreamConfig.flags` must be zero in Version 1.

After the 12-byte common header, a `StreamConfig` contains:

| Offset | Size | Field |
| ---: | ---: | --- |
| 12 | 2 | `width` |
| 14 | 2 | `height` |
| 16 | 1 | `csd_count` |
| 17 | 3 | reserved, zero |

The fixed StreamConfig prefix is exactly 20 bytes.

After the prefix, exactly `csd_count` codec-specific-data entries follow. Each entry is:

```text
uint32 big-endian length
exact CSD bytes
```

AVC Version 1 accepts 1 through 4 CSD entries. CSD bytes are preserved exactly. SCL does not parse SPS/PPS, rewrite start codes, convert Annex B to AVCC, convert AVCC to Annex B, merge NAL units, or split NAL units.

### AccessUnit

An `AccessUnit` payload is:

```text
12-byte common video header
exact encoded access-unit bytes
```

There is no additional fixed access-unit metadata header.

For `AccessUnit.flags`:

```text
bit 0 = KeyFrame
bits 1..7 = reserved, zero
```

Reserved flag bits are rejected by the parser. SCL does not parse AVC NAL units to infer keyframes.

### Video Timestamps

For Version 1 `AccessUnit` SCL packets:

```text
PacketHeader::timestamp_us = MediaCodec BufferInfo.presentationTimeUs
```

All fragments of one access unit carry the same timestamp.

This timestamp is the encoder presentation timestamp supplied by RFC-002B. It is not automatically interpreted as UDP send time, network time, synchronized cross-device monotonic time, or wall-clock time. Later video orchestration and latency RFCs will define how media PTS relates to synchronized endpoint clocks.

Negative Kotlin `presentationTimeUs` values are rejected before unsigned native conversion.

### Video Fragmentation

The logical payload being fragmented is:

```text
StreamConfig:
20-byte StreamConfig prefix + length-prefixed CSD entries

AccessUnit:
12-byte VideoMessageHeader + encoded AU bytes
```

Fragmentation uses RFC-001C unchanged. Each fragment has its own unique SCL packet sequence number, and the fragment group base remains:

```text
base_sequence_number = sequence_number - slice_index modulo 2^32
```

When FEC is disabled:

```text
fragmentation datagram budget = max_wire_datagram_size
```

When FEC is enabled:

```text
fragmentation datagram budget =
max_wire_datagram_size - 21 - 16 - 2
```

No global MTU is frozen by RFC-002C.

### Segmented Packetization

RFC-002C packetizes access units as segmented logical payloads:

```text
segment 0 = 12-byte video header
segment 1 = borrowed MediaCodec encoded AU bytes
```

The native packetizer copies only the bytes intersecting each SCL fragment into the outgoing datagram scratch buffer. It does not allocate or require a complete temporary `12 + AU size` staging buffer.

### RFC-002F Runtime Composition

RFC-002F adds end-to-end sender/receiver runtime orchestration for Video Payload Version 1. It does not change the Video V1 wire layout, the 21-byte SCL `PacketHeader`, `PayloadType::Video`, NACK payloads, FEC parity payloads, or clock-sync payloads.

The receiver runtime uses existing SCL packet decode, loss detection, NACK generation, Reed-Solomon FEC recovery, RFC-001C reassembly, and Video V1 parsing. Completed AccessUnit payload bytes remain owned by bounded native receiver storage until JNI synchronously fills a MediaCodec-owned direct input buffer. No complete access-unit Kotlin payload representation is part of the protocol contract.

## Loss Detection, NACK, And Recovery

RFC-001D defines bounded packet-level loss detection and selective retransmission primitives for one caller-scoped recovery domain.

A recovery domain is an ordered sequence-number context managed by the caller. It may correspond to a payload type, direction, and session or stream context, but no recovery-domain identifier is added to the Version 1 packet header.

Do not feed NACK control packets into the same loss detector that tracks the data packets they request.

### Sequence Ordering

Loss detection uses 32-bit modular sequence arithmetic.

A sequence `a` is newer than sequence `b` when:

```text
a != b
and
(a - b) modulo 2^32 < 2^31
```

The exact half-range distance `0x80000000` is ambiguous and must not be silently ordered.

### Local Timing

NACK scheduling uses caller-supplied local monotonic timestamps:

- `reorder_delay_us`: how long a missing candidate waits before the first NACK.
- `renack_interval_us`: fixed interval between repeated NACK attempts.
- `max_nack_attempts`: maximum emitted NACK attempts per missing sequence.

The recovery layer owns no timers and creates no threads.

### NACK Wire Payload

NACK is a Version 1 `SessionControl` payload subtype. It does not add a new `PayloadType`.

The NACK payload is exactly 16 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 1 | `target_payload_type` |
| 3 | 1 | `reserved` |
| 4 | 4 | `base_sequence_number` |
| 8 | 8 | `missing_bitmap` |

Constants:

```cpp
kNackPayloadWireSize == 16
kNackControlVersion == 1
SessionControlType::Nack == 1
```

All multi-byte NACK payload fields use big-endian byte order.

### NACK Bitmap

Bit `i` in `missing_bitmap` requests:

```text
base_sequence_number + i modulo 2^32
```

The bitmap must be non-zero. `target_payload_type` must be a defined non-`Unknown` Version 1 payload type. The reserved byte must be zero.

### Retransmission

A retransmission reuses the exact original encoded datagram.

Retransmission must not regenerate or mutate:

- Protocol version.
- Flags.
- Sequence number.
- Timestamp.
- Payload type.
- Slice metadata.
- Payload bytes.

The retransmission cache is bounded, caller-owned, and deterministic. Lookup returns a non-owning span over cached bytes. Cache eviction uses oldest-slot ring replacement in RFC-001D.

## Reed-Solomon FEC

RFC-001E defines proactive Forward Error Correction using systematic Reed-Solomon erasure coding over `GF(256)` with primitive polynomial `0x11D`.

FEC protects complete encoded original SCL data datagrams:

```text
PacketHeader + payload
    -> encoded SCL datagram
    -> 2-byte original length prefix
    -> zero-padded systematic RS shard
```

The Reed-Solomon block contains:

```text
K data shards + M parity shards
```

Any `K` valid shards from the `K + M` block are sufficient to reconstruct the systematic data shards. This is erasure correction only: the receiver must know which shards are present or missing. RFC-001E does not detect silent byte corruption.

All data datagrams in one FEC block belong to one caller-defined recovery domain. At minimum, they share:

- Target payload type.
- Base sequence number.
- Data shard count.
- Parity shard count.
- Shard size.

Data shard `i` protects the original encoded SCL datagram whose sequence number is:

```text
base_sequence_number + i modulo 2^32
```

FEC does not add fields to the 21-byte SCL packet header and does not change Protocol Version 1.

### FEC Parity Control Payload

FEC parity is transported as a `SessionControl` payload subtype. It does not add a new `PayloadType`.

Constants:

```cpp
SessionControlType::FecParity == 2
kFecParityControlVersion == 1
kFecParityHeaderWireSize == 16
```

The FEC parity payload starts with a 16-byte metadata header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 1 | `target_payload_type` |
| 3 | 1 | `parity_index` |
| 4 | 4 | `base_sequence_number` |
| 8 | 1 | `data_shards` |
| 9 | 1 | `parity_shards` |
| 10 | 2 | `shard_size` |
| 12 | 4 | `reserved` |

Immediately after the header are exactly `shard_size` parity bytes.

All multi-byte FEC parity fields use big-endian byte order.

FEC parity validation rejects malformed payloads, unsupported control versions, `Unknown` or undefined target payload types, `SessionControl` as the target payload type, zero shard counts, `data_shards + parity_shards > 255`, out-of-range parity indexes, `shard_size < 2`, nonzero reserved bits, and payload length mismatches.

### FEC Datagram Budget

FEC budget calculation uses a caller-selected maximum complete wire datagram size:

```text
max protected datagram =
max wire datagram
- SCL header
- FEC parity header
- original length prefix
```

In constants:

```text
max_protected_datagram_size =
max_wire_datagram_size - 21 - 16 - 2
```

No global SCL MTU is frozen by RFC-001E.

When FEC is enabled, the caller must choose an RFC-001C fragmentation datagram budget no larger than `max_protected_datagram_size`, so parity packets fit the same selected wire budget.

### FEC And NACK

FEC is the first recovery opportunity. Selective NACK/retransmission remains the correctness fallback when fewer than `K` shards are available.

FEC primitives do not generate NACKs, retransmit parity, own UDP sockets, create threads, estimate RTT, adapt parity ratios, pace traffic, or perform congestion control.

## Clock Synchronization And Telemetry

RFC-001F defines local runtime clock synchronization and telemetry measurement.

SCL never modifies operating-system clocks. It maintains an internal affine model between endpoint monotonic clock domains:

```text
remote_time ~= reference_remote + rate_ratio * (local_time - reference_local)
```

Clock synchronization uses `PayloadType::SessionControl` and does not add a new `PayloadType`.

Constants:

```cpp
SessionControlType::ClockSyncRequest == 3
SessionControlType::ClockSyncResponse == 4
kClockSyncControlVersion == 1
kClockSyncRequestWireSize == 16
kClockSyncResponseWireSize == 32
```

The 16-byte request payload is:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 2 | `reserved` |
| 4 | 4 | `exchange_id` |
| 8 | 8 | `t0_us` |

The 32-byte response payload is:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 2 | `reserved` |
| 4 | 4 | `exchange_id` |
| 8 | 8 | `t0_us` |
| 16 | 8 | `t1_us` |
| 24 | 8 | `t2_us` |

All multi-byte fields use big-endian byte order. `t3_us` is recorded locally by the initiator and is not transmitted.

For a valid four-timestamp exchange:

```text
RTT = (t3 - t0) - (t2 - t1)

offset = ((t1 - t0) + (t2 - t3)) / 2
```

`offset > 0` means the remote clock is numerically ahead of the local clock for that exchange.

Accepted samples expose safe local and remote midpoints. Model fitting uses midpoint pairs and centered affine least-squares estimation to reduce precision loss from large monotonic timestamps.

Four-timestamp synchronization cannot independently determine clock offset and path asymmetry. The offset and one-way delay values are estimates, not exact truth.

Clock quality states are:

- `Unsynchronized`.
- `WarmingUp`.
- `Synchronized`.
- `Degraded`.
- `Stale`.

Remote-to-local and local-to-remote conversion require a `Synchronized` model. One-way delay estimation also requires explicitly valid timestamp semantics and a synchronized model.

Network telemetry is local runtime measurement only. RFC-001F defines no `PayloadType::Telemetry` wire schema.

Telemetry counters are saturating and preserve separate observations for transport, loss/reordering, NACK, retransmission, FEC, and clock synchronization. Telemetry also tracks bounded RTT and one-way-delay rolling windows and jitter:

```text
J(i) = J(i-1) + (abs(d(i) - d(i-1)) - J(i-1)) / 16
```

Telemetry is observational. It must not alter FEC ratios, NACK timing, congestion behavior, datagram sizing, pacing, bitrate, packet bytes, or transport behavior.

## Stability

Packet layout is part of the SCL protocol contract.

Breaking packet layout or protocol semantic changes require a Protocol Version change and an RFC.

Transport API changes that affect native callers require an RFC and may require Native ABI documentation if exposed through the bridge in a later phase.
