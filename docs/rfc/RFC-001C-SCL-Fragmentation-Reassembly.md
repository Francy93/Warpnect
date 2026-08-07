# RFC-001C - SCL Fragmentation and Reassembly

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

## Problem

RFC-001A defined the SCL Version 1 packet header and codec.

RFC-001B defined non-blocking UDP datagram transport.

SCL still needed deterministic application-level fragmentation and reassembly for logical payloads that exceed a caller-selected datagram budget.

## Decision

Implement zero-copy fragmentation and bounded reassembly primitives in portable C++20.

Fragmentation and reassembly remain independent from UDP sockets. Integration happens through callers and tests:

```text
FragmentCursor -> encode_packet -> UdpSocket
UdpSocket -> decode_packet -> ReassemblySlot
```

## Wire Compatibility

RFC-001C does not change the Version 1 SCL wire header.

No `message_id`, `frame_id`, `fragment_id`, `group_id`, or payload size field is added.

Architecture Version remains 1.0.

Protocol Version remains 1.

Native Bridge ABI Version remains 1.

## Fragmentation Model

The caller supplies:

```text
max_datagram_size
```

This represents the complete SCL datagram budget:

```text
21-byte SCL header + fragment payload
```

The fragment payload capacity is:

```text
max_datagram_size - kPacketHeaderWireSize
```

No universal SCL MTU is frozen by this RFC.

For non-empty payloads:

```text
total_slices = ceil(payload_size / fragment_payload_capacity)
```

For empty payloads:

```text
slice_index = 0
total_slices = 1
payload size = 0
```

Fragmenting an already fragmented packet is rejected.

## Sequence Number Contract

Every transmitted fragment has a unique packet sequence number:

```text
sequence_number = base_sequence_number + slice_index
```

Reassembly derives:

```text
base_sequence_number = sequence_number - slice_index
```

All arithmetic is unsigned 32-bit modular arithmetic.

This preserves packet-level loss visibility for future recovery.

## Fragment Group Identity

The runtime-only group key contains:

- Protocol version.
- Base sequence number.
- Timestamp.
- Payload type.
- Flags.
- Total slice count.

The key is never serialized.

## Reassembly Model

`ReassemblySlot` represents one fragment group at a time.

The caller provides:

- Persistent payload storage.
- Fragment-received bitmap storage.

The slot allocates no storage internally.

Fragments may arrive out of order. The final fragment may arrive first. Completion occurs when all slices are present and the exact final payload size is known.

Duplicate fragments are informational and do not corrupt state.

Conflicting duplicates are rejected and the first accepted fragment remains authoritative.

Different fragment groups are rejected without resetting the active slot.

`reset()` clears logical state and keeps the caller-owned workspace attached for reuse.

## Deferred Work

Deferred to later RFCs:

- Loss detection.
- NACK and recovery.
- Retransmission.
- Reed-Solomon FEC.
- Timeout eviction.
- Clock synchronization.
- Network telemetry.
- Phase 1 integration and benchmarks.
