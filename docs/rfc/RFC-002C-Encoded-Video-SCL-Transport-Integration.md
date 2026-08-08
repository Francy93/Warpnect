# RFC-002C - Encoded Video to SCL Transport Integration

Project: Warpnect

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

Video Payload Version: 1

Status: Implemented

## Purpose

RFC-002C connects RFC-002B encoded AVC output to the SCL networking foundation.

The transmitter-side media path is now:

```text
RFC-002A privileged capture
        |
MediaCodec input Surface
        |
RFC-002B hardware AVC encoder
        |
borrowed direct encoded ByteBuffer
        |
RFC-002C Kotlin transport sink
        |
NativeBridge JNI
        |
SCL video packetizer and sender
        |
fragmentation, UDP, retransmission cache, optional FEC
```

RFC-002C stops at encoded-video transport. It does not implement a decoder, renderer, receiver scheduler, adaptive bitrate, congestion control, pacing, discovery, or end-to-end streaming orchestration.

## Video Payload Version 1

`PayloadType::Video` remains wire value 1. RFC-002C defines an inner Version 1 video payload carried by that existing payload type.

No `PacketHeader` field changed. The SCL wire header remains exactly 21 bytes.

## Message Types And Codec IDs

Video message types:

| Value | Type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `StreamConfig` |
| 2 | `AccessUnit` |

Video codec IDs:

| Value | Codec |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Avc` |

RFC-002C implements AVC only.

## Common Header

Every Version 1 video logical payload begins with this 12-byte header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `video_version` |
| 1 | 1 | `message_type` |
| 2 | 1 | `codec` |
| 3 | 1 | `flags` |
| 4 | 4 | `config_generation` |
| 8 | 4 | `item_id` |

All multi-byte fields are big-endian. `kVideoMessageHeaderWireSize == 12`.

## StreamConfig

`StreamConfig.flags` is zero. `item_id` is zero.

After the common header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 12 | 2 | `width` |
| 14 | 2 | `height` |
| 16 | 1 | `csd_count` |
| 17 | 3 | reserved, zero |

The fixed prefix is 20 bytes. It is followed by exactly `csd_count` CSD entries:

```text
uint32 big-endian length
exact CSD bytes
```

AVC Version 1 accepts 1 through 4 CSD entries. The CSD bytes supplied by RFC-002B are transported exactly. RFC-002C does not parse SPS/PPS, normalize Annex B or AVCC, add or remove start codes, or split/merge NAL units.

## AccessUnit

An access unit is:

```text
12-byte common video header
exact encoded access-unit bytes
```

`item_id` is the 32-bit wrapping frame ID. The frame ID advances once per submitted access unit and is independent from SCL datagram sequence numbers.

AccessUnit flag bits:

```text
bit 0 = KeyFrame
bits 1..7 = reserved, zero
```

Reserved flags are rejected. RFC-002C does not parse AVC NAL units to detect keyframes.

## Configuration Generation

The sender starts with `current_config_generation = 0`.

Rules:

```text
0 = invalid / no active configuration
first accepted StreamConfig = 1
subsequent accepted StreamConfig = previous + 1
UINT32_MAX + 1 wraps to 1, skipping 0
```

Access units are rejected until a StreamConfig has been successfully emitted. If StreamConfig emission fails partway, the new generation is not made active.

## Timestamp Semantics

For Version 1 AccessUnit packets:

```text
PacketHeader::timestamp_us = MediaCodec BufferInfo.presentationTimeUs
```

Every fragment of one access unit carries the same timestamp.

This is a media presentation timestamp. It is not automatically UDP send time, network time, synchronized cross-device monotonic time, or wall-clock time. Later video pipeline RFCs will define how encoded PTS relates to synchronized endpoint clocks.

Negative Kotlin presentation timestamps are rejected before native unsigned conversion.

## Segmented Packetization

The native packetizer treats an access unit as a segmented logical payload:

```text
segment 0 = 12-byte video header
segment 1 = borrowed encoded AU bytes
```

For each fragment, only the bytes intersecting that fragment are copied into the outgoing datagram scratch buffer. RFC-002C does not allocate or require a complete `12 + AU size` native staging buffer.

StreamConfig is similarly segmented from the fixed prefix, CSD length fields, and CSD byte spans.

## Datagram Budget

The sender accepts a caller-provided `max_wire_datagram_size`. No global MTU is frozen.

Without FEC:

```text
fragmentation datagram budget = max_wire_datagram_size
fragment payload capacity = max_wire_datagram_size - 21
```

With FEC:

```text
max protected datagram = max_wire_datagram_size - 21 - 16 - 2
fragment payload capacity = max protected datagram - 21
```

The FEC budget formula is inherited from RFC-001E.

## Sender Architecture

`VideoTransportSender` composes existing native primitives:

- `VideoPacketizer`.
- `UdpSocket`.
- `RetransmissionCache`.
- Optional `FecBlockEncoder`.
- `NetworkTelemetry`.

The sender is caller-driven and creates no thread, timer, pacing loop, or hidden queue. UDP sends are non-blocking. `WouldBlock` and partial emission are reported explicitly.

Video data datagrams use one continuous `PayloadType::Video` sequence domain across StreamConfig and AccessUnit messages. FEC parity packets remain `PayloadType::SessionControl` and use the session-control sequence domain.

FEC blocks may protect consecutive Video datagrams across access-unit boundaries. RFC-002C does not require frame-aligned FEC blocks and does not freeze a K/M profile.

## NACK Compatibility

RFC-001D NACK is reused unchanged. A NACK targeting `PayloadType::Video` identifies missing SCL datagram sequence numbers.

The sender caches exact encoded Video datagrams. On a caller-supplied NACK control datagram or decoded request, it looks up the cached datagram and retransmits byte-identical bytes. RFC-002C does not add a NACK receive thread.

## Receive Parsing

Portable parsers operate on a complete reassembled logical Video payload:

- `decode_video_stream_config`.
- `decode_video_access_unit`.

AccessUnit parsing returns a non-owning view over encoded bytes. StreamConfig parsing returns a view and a bounded CSD cursor. Consumers may copy CSD later if they need persistent decoder ownership.

Reassembly remains RFC-001C. RFC-002C does not introduce a production multi-frame receive scheduler.

## Kotlin And JNI Contract

`SclEncodedVideoSink` implements RFC-002B `EncodedVideoSink`.

On output-format changes it forwards codec, dimensions, and CSD to native transport. CSD is cold-path configuration data and may be copied.

On access units it validates:

- direct `ByteBuffer`;
- `offset >= 0`;
- `size > 0`;
- `offset + size <= buffer.capacity()`;
- `presentationTimeUs >= 0`.

It does not mutate `ByteBuffer.position()` or `ByteBuffer.limit()`.

JNI uses:

```text
GetDirectBufferAddress
GetDirectBufferCapacity
```

for access-unit and control-datagram buffers. It does not use Java byte arrays for MediaCodec access-unit bytes. The native pointer is borrowed only for the synchronous call and is never retained.

`NativeBridge.kt` remains the sole Kotlin JNI entry point.

## Resource Ownership

| Resource | Owner | Created | Released |
| --- | --- | --- | --- |
| `MediaCodec` output buffer | Android `MediaCodec` | encoder callback | RFC-002B releases after sink returns |
| direct buffer pointer | JNI call | native submission entry | before JNI return |
| `VideoTransportSender` handle | `NativeSclVideoTransportController` | `open()` | `closeResult()` / `close()` |
| UDP socket | native sender | native sender open | native sender close/destroy |
| datagram scratch buffer | native handle | setup | destroy |
| retransmission cache storage | native handle | setup | destroy |
| FEC workspace | native handle when enabled | setup | destroy |
| CSD copies | JNI cold path | StreamConfig submission | after native submission returns |

## Allocation Semantics

Setup allocation:

- native handle;
- datagram scratch buffer;
- retransmission cache storage;
- optional FEC workspaces.

Cold-path allocation:

- Kotlin CSD array conversion;
- JNI CSD temporary copies on output-format changes.

Per-access-unit hot path:

- no mandatory Kotlin `ByteArray`;
- no complete native AU staging buffer;
- fragment bytes are copied only into outgoing SCL datagrams;
- retransmission cache stores exact encoded datagrams;
- optional FEC copies datagrams into caller-owned shard storage.

## Tests

Native host tests cover:

- common-header golden vector;
- StreamConfig golden vector;
- AccessUnit round trip;
- malformed payload rejection;
- CSD round trip;
- segmented header-plus-AU packetization;
- small and large AU fragmentation;
- packet sequence wrap;
- frame ID wrap;
- configuration generation wrap;
- keyframe flags;
- PTS preservation;
- FEC video recovery;
- FEC plus NACK fallback;
- StreamConfig recovery;
- UDP loopback sender/reassembly/NACK retransmission.

JVM tests cover:

- output-format to StreamConfig submission;
- AU before config failure;
- direct-buffer requirement;
- offset/size validation;
- negative PTS rejection;
- keyframe flag forwarding;
- PTS forwarding;
- transport error propagation;
- ByteBuffer position/limit preservation.

Android build verification compiles the native transport into all configured ABIs.

Device-only JNI/instrumentation execution depends on a connected Android device or emulator. No device results are fabricated.

## Known Limitations

- No decoder or renderer exists yet.
- No production receive scheduler exists yet.
- No pacing, congestion control, bitrate adaptation, adaptive FEC, automatic MTU selection, or keyframe recovery policy exists yet.
- No codec-config resend policy exists yet.
- UDP sends occur synchronously/back-to-back.
- Partial AU emission is visible to the caller but not retried by a hidden queue.
- FEC K/M is caller configuration, not a frozen Warpnect profile.

## Architecture Compliance

RFC-002C introduces no `PacketHeader` change, no new `PayloadType`, no AVC/NAL parser, no decoder, no renderer, no MediaProjection changes, no raw-frame pipeline changes, no software encoder fallback, no pacing, no congestion control, no adaptive bitrate, and no native networking worker thread.

Native Bridge ABI Version remains 1 because RFC-002C adds entry points without removing or changing existing ABI-1 calls.

## Deferred Work

- RFC-002D - Android Hardware Video Decoder Pipeline.
- RFC-002E - Low-Latency Rendering Pipeline.
- RFC-002F - End-to-End Video Streaming.
- RFC-002G - Video Latency, Recovery and Performance Tuning.
