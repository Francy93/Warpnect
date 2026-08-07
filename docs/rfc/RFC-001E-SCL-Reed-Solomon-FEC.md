# RFC-001E - SCL Reed-Solomon FEC

Status: Implemented

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

NACK Control Payload Version: 1

FEC Parity Control Version: 1

## Problem

RFC-001D recovery requires at least one round trip after packet loss is detected. For latency-sensitive streams, some losses should be recoverable proactively when bounded parity is available.

## Decision

SCL implements systematic Reed-Solomon erasure coding over `GF(256)` using primitive polynomial `0x11D`.

One FEC block protects:

```text
K data shards + M parity shards
```

Any `K` valid shards from the block can reconstruct the systematic data shards. The model is erasure-only; arbitrary silent corruption detection is out of scope.

## Implementation

The low-level Reed-Solomon codec operates only on equal-size caller-owned shards. It has no packet, UDP, Android, JNI, or Kotlin dependency.

At the SCL layer, FEC protects complete encoded original SCL data datagrams. Each systematic data shard stores:

```text
uint16 original datagram length
encoded SCL datagram bytes
zero padding
```

FEC parity is carried as `PayloadType::SessionControl` with:

```text
SessionControlType::FecParity = 2
FEC Parity Control Version = 1
```

The parity payload starts with a 16-byte big-endian metadata header followed by exactly `shard_size` parity bytes.

## Compatibility Impact

The 21-byte SCL PacketHeader is unchanged.

No new `PayloadType` was introduced.

Architecture Version remains 1.0.

SCL Protocol Version remains 1.

Native Bridge ABI Version remains 1.

## Test Coverage

Native tests cover GF arithmetic, primitive table behavior, matrix inversion, systematic matrix construction, golden RS parity vectors, erasure recovery combinations, insufficient-shard failures, FEC parity golden headers, malformed parity payloads, FEC block validation, duplicate/conflicting shards, sequence wraparound, LossDetector integration, fragmentation plus FEC, UDP FEC success, UDP NACK fallback, and deterministic property-style recovery.

## Deferred Work

Deferred to later RFCs:

- Adaptive FEC ratios.
- Loss-rate estimation.
- RTT-based recovery decisions.
- Congestion control.
- Pacing.
- FEC negotiation.
- Security/integrity validation.
- SIMD or platform-specific acceleration.
- JNI/Kotlin exposure.

Next:

```text
RFC-001F - Clock Synchronization & Network Telemetry
```
