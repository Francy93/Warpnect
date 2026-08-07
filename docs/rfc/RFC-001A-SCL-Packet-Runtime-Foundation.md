# RFC-001A - SCL Packet and Runtime Foundation

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Status: Implemented

Date: 2026-08-07

## Problem

SCL needed a production-grade packet foundation before any transport implementation could begin.

The previous skeleton represented the packet header as a packed C++ structure. That preserved the intended 21-byte shape, but it risked conflating runtime metadata with wire serialization.

## Decision

Define the SCL Version 1 wire header as explicit bytes, not as a transmitted C++ structure.

The wire contract remains exactly 21 bytes and uses big-endian byte order.

The runtime `PacketHeader` remains an aligned value type and must never be serialized by raw structure copy.

## Implementation

RFC-001A adds:

- Named Version 1 wire offsets and field sizes.
- Explicit payload type wire values.
- Packet validation.
- Endian-safe header encoding and decoding.
- Full packet encoding into caller-owned buffers.
- Non-owning packet views.
- Typed packet errors and result values.
- Local monotonic timestamp value helpers.
- Private portable byte-order helpers.
- Host-native CTest coverage for the packet foundation.

No UDP socket, transport engine, session negotiation, fragmentation, recovery, FEC, clock synchronization, codecs, audio, video, input injection, Shizuku behavior, or JNI packet API was added.

## Compatibility Impact

Architecture Version remains 1.0.

Protocol Version remains 1.

Native ABI Version remains 1.

Android package root remains `io.warpnect`.

Native namespace root remains `warpnect::scl`.

Native shared library target remains `scl_core`.

JNI method signatures remain unchanged.

## Wire Contract

| Offset | Size | Field | Wire type |
| ---: | ---: | --- | --- |
| 0 | 2 | `protocol_version` | unsigned 16-bit |
| 2 | 2 | `flags` | unsigned 16-bit |
| 4 | 4 | `sequence_number` | unsigned 32-bit |
| 8 | 8 | `timestamp_us` | unsigned 64-bit |
| 16 | 1 | `payload_type` | unsigned 8-bit |
| 17 | 2 | `slice_index` | unsigned 16-bit |
| 19 | 2 | `total_slices` | unsigned 16-bit |

## Test Coverage

Native packet tests cover:

- Protocol and wire-size constants.
- Payload type numeric values.
- Golden wire vector encoding.
- Golden wire vector decoding.
- Runtime encode/decode round trips.
- Truncated inputs from 0 through 20 bytes.
- Small output buffers with guard-byte preservation.
- Unsupported protocol versions.
- Reserved and unsupported payload types.
- Invalid slice counts and indexes.
- Empty and non-empty packet views.
- Non-owning payload view behavior.
- Full packet encoding.
- Deterministic property-style round trips.
- Unaligned encode/decode buffers.

## Deferred Work

Deferred to later RFCs:

- RFC-001B - SCL UDP Transport Engine.
- RFC-001C - Fragmentation and Reassembly.
- RFC-001D - Loss Detection, NACK and Recovery.
- RFC-001E - Reed-Solomon FEC.
- RFC-001F - Clock Synchronization and Network Telemetry.
- RFC-001G - Phase 1 Integration and Benchmarks.
