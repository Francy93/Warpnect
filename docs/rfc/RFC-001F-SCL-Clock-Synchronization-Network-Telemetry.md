# RFC-001F - SCL Clock Synchronization & Network Telemetry

Status: Implemented

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

NACK Control Payload Version: 1

FEC Parity Control Version: 1

Clock Sync Control Version: 1

## Problem

SCL needs local measurement primitives before it can make later transport-quality decisions. Endpoints have independent monotonic clocks, so raw timestamps from separate devices cannot be compared directly.

## Decision

RFC-001F adds a bounded, caller-driven clock synchronization and telemetry foundation.

SCL never modifies operating-system clocks. It estimates a mathematical relationship between endpoint monotonic clock domains and exposes conversion only when the model is synchronized.

Telemetry is observational only. It records transport, recovery, FEC, and clock events, but it does not change NACK timing, FEC ratio, pacing, congestion behavior, datagram size, packet bytes, or application quality.

## Clock Control Payloads

Clock sync uses `PayloadType::SessionControl` and explicit control subtypes:

```text
SessionControlType::ClockSyncRequest = 3
SessionControlType::ClockSyncResponse = 4
Clock Sync Control Version = 1
```

The request payload is exactly 16 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 2 | `reserved` |
| 4 | 4 | `exchange_id` |
| 8 | 8 | `t0_us` |

The response payload is exactly 32 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 2 | `reserved` |
| 4 | 4 | `exchange_id` |
| 8 | 8 | `t0_us` |
| 16 | 8 | `t1_us` |
| 24 | 8 | `t2_us` |

All multi-byte fields use big-endian byte order. `t3_us` is recorded locally by the initiator when the response is received.

## Synchronization Model

The four timestamps are:

```text
t0 = initiator local send time
t1 = responder local receive time
t2 = responder local send time
t3 = initiator local receive time
```

For a valid sample:

```text
RTT = (t3 - t0) - (t2 - t1)

offset = ((t1 - t0) + (t2 - t3)) / 2
```

The offset is an estimate under the symmetric-path assumption. Four-timestamp exchange cannot independently determine clock offset and path asymmetry.

Accepted midpoint pairs feed a bounded affine model:

```text
remote_time ~= reference_remote + rate_ratio * (local_time - reference_local)
```

The model exposes drift in parts per million:

```text
drift_ppm = (rate_ratio - 1.0) * 1,000,000
```

Quality states are `Unsynchronized`, `WarmingUp`, `Synchronized`, `Degraded`, and `Stale`.

## Telemetry

Telemetry uses fixed counters and caller-owned rolling sample windows. Counters saturate instead of wrapping.

Counters distinguish datagrams, byte counts, would-block events, truncation, sequence gaps, late packets, duplicates, NACK messages, NACK-requested sequences, retransmissions, FEC events, and clock synchronization events.

RTT and one-way-delay windows expose count, latest, minimum, maximum, mean, and jitter. Jitter uses:

```text
J(i) = J(i-1) + (abs(d(i) - d(i-1)) - J(i-1)) / 16
```

One-way delay is available only when a synchronized clock model exists and the caller explicitly provides a remote timestamp whose semantics are suitable for delay measurement. `PacketHeader::timestamp_us` is not automatically interpreted as a transport-send timestamp.

## Compatibility Impact

The 21-byte SCL `PacketHeader` is unchanged.

No new `PayloadType` was introduced.

No JNI or Kotlin timing/telemetry API was introduced.

Architecture Version remains 1.0.

SCL Protocol Version remains 1.

Native Bridge ABI Version remains 1.

## Test Coverage

Native tests cover clock request/response golden vectors, malformed payloads, exchange tracking, out-of-order completion, known offset samples, processing-time RTT removal, invalid timing, midpoint overflow safety, sample rejection, affine model fitting, positive and negative drift, drift limits, stale state, timestamp conversion, one-way-delay gating, rolling statistics, jitter, counter saturation, loss/FEC telemetry recording, no-feedback behavior, and UDP loopback clock synchronization.

## Deferred Work

Deferred to later RFCs:

- Adaptive FEC ratios.
- Adaptive NACK timing.
- Congestion control.
- Pacing.
- Bitrate control.
- Adaptive MTU or Path MTU Discovery.
- Telemetry wire streaming or UI.
- JNI/Kotlin timing and telemetry exposure.

Next:

```text
RFC-001G - Phase 1 Integration & Benchmarks
```
