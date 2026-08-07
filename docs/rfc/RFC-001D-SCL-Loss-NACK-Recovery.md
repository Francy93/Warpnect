# RFC-001D - SCL Loss Detection, NACK And Recovery

Status: Complete

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

NACK Control Payload Version: 1

## Problem

RFC-001C gave every transmitted SCL datagram a unique sequence number, including fragments from the same logical payload. SCL needed a bounded way to detect sequence gaps, tolerate temporary packet reordering, request missing datagrams, and resolve those requests from recent transmit history.

## Decision

Implement recovery as portable C++20 primitives:

- Wrap-safe 32-bit sequence arithmetic.
- A caller-owned `LossDetector` for one recovery domain.
- A 16-byte Version 1 NACK `SessionControl` payload.
- A caller-owned retransmission cache that stores exact encoded datagrams.

Recovery primitives do not own UDP sockets, timers, threads, queues, JNI APIs, or Kotlin logic.

## Recovery Domains

A recovery domain is an ordered sequence-number context supplied by the caller. A domain may correspond to a payload type, direction, and session or stream context, but no domain identifier is added to the SCL packet header.

NACK control packets must not be fed into the same loss detector that tracks the data packets they request.

## Sequence Arithmetic

Ordering uses unsigned 32-bit modular arithmetic.

A sequence `a` is newer than `b` when:

```text
a != b
and
(a - b) modulo 2^32 < 2^31
```

The half-range distance `0x80000000` is ambiguous and returns a typed error.

## Loss Detector

`LossDetector` observes incoming sequence numbers with caller-supplied local monotonic time.

It distinguishes:

- First packet.
- In-order packet.
- Forward gap.
- Late packet that fills a missing slot.
- Duplicate packet.
- Too-old packet outside retained history.
- Ambiguous or over-capacity sequence movement.

Missing packets become NACK-eligible only after `reorder_delay_us`.

Repeated NACKs use `renack_interval_us`.

`max_nack_attempts` bounds automatic retry requests.

No internal timers are created.

## NACK Wire Payload

The NACK payload is carried in an SCL packet whose `PayloadType` is `SessionControl`.

The payload is exactly 16 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 1 | `target_payload_type` |
| 3 | 1 | `reserved` |
| 4 | 4 | `base_sequence_number` |
| 8 | 8 | `missing_bitmap` |

Values:

```text
control_type = 1
control_version = 1
```

All multi-byte fields are big-endian.

Bit `i` in `missing_bitmap` requests:

```text
base_sequence_number + i modulo 2^32
```

Malformed NACK payloads are rejected deterministically.

## Retransmission Cache

`RetransmissionCache` stores encoded SCL datagrams in caller-owned bounded storage.

Each entry records:

- Payload type.
- Sequence number.
- Encoded datagram length.
- Encoded datagram bytes.

Insertion copies the encoded datagram once into caller-owned storage.

Lookup returns a non-owning span over cached bytes.

Eviction uses deterministic oldest-slot ring replacement.

Duplicate stores of identical bytes are idempotent. Conflicting stores for the same payload type and sequence are rejected.

## Retransmission Semantics

A retransmission is the exact original encoded datagram.

It preserves:

- Protocol version.
- Flags.
- Sequence number.
- Timestamp.
- Payload type.
- Slice metadata.
- Payload bytes.

No retransmission flag is introduced in RFC-001D.

## Fragmentation Compatibility

Fragmented and non-fragmented packets use the same recovery path.

For a fragmented payload:

```text
slice 0 -> sequence 100
slice 1 -> sequence 101
slice 2 -> sequence 102
slice 3 -> sequence 103
```

A NACK for sequence `102` resolves to the original encoded slice 2 datagram. The reassembly slot then accepts the recovered packet normally.

## Test Coverage

Native tests cover:

- Sequence arithmetic and wraparound.
- Half-range ambiguity.
- In-order tracking.
- Gap creation.
- Reordered and late recovery.
- Duplicate detection.
- Window capacity failure.
- Re-NACK timing.
- Maximum NACK attempts.
- NACK golden vectors.
- Malformed NACK payloads.
- Bitmap iteration and wraparound.
- NACK packing and output capacity behavior.
- Retransmission cache identity, eviction, binary safety, and conflict detection.
- Fragment recovery using cached datagrams.
- Full bidirectional UDP loopback NACK and retransmission integration.
- Deterministic property-style recovery.

## Compatibility Impact

The 21-byte SCL packet header is unchanged.

No `PayloadType` is added.

SCL Protocol Version remains 1.

Native Bridge ABI Version remains 1.

No JNI or Kotlin APIs are introduced.

## Deferred Work

- RFC-001E - Reed-Solomon FEC.
- RFC-001F - Clock Synchronization and Network Telemetry.
- RFC-001G - Phase 1 Integration and Benchmarks.
