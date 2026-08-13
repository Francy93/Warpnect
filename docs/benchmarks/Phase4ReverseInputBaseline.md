# Phase 4 Reverse Input Baseline

## Scope

RFC-004G compares the RFC-004F `ArrivalOrderBestEffort` baseline with the Phase 4
production `UltraLowLatencyConvergent` profile. This document records reproducible
host transport measurements and deterministic state-convergence coverage. It does not
claim Android hardware, Binder, InputDispatcher, game-processing, physical-input, or
cross-device one-way latency.

## Baseline And Final Profile

| Setting | RFC-004F baseline | RFC-004G production |
| --- | --- | --- |
| Policy | `ArrivalOrderBestEffort` | `UltraLowLatencyConvergent` |
| Network reorder wait | 0 us | 0 us |
| Critical copies | 1 | 2 immediate submissions |
| Reset copies | 1 | 3 immediate submissions |
| Fresh snapshots | 1 | 1 |
| Incremental deltas | 1 | 1 |
| Transport duplicate filter | disabled | 64 sequences |
| Semantic duplicate cache | disabled | 32 critical/reset identities |
| Per-slot capacity | n/a | 32 slots |
| Per-slot key capacity | n/a | 64 HID keys |
| Touch repair | disabled | enabled, at most 64 synchronous repair events |
| NACK / FEC / ACK / retransmission queue | disabled | disabled |

All copies are immediate, independent `InputTransportController.submit()` calls. Each
uses a new SCL sequence number and preserves source timestamp, portable event content,
and device slot. There is no retry timer, ACK wait, reorder buffer, or payload cache.

The source sink and target receive controller also expose fixed logarithmic microsecond
histograms (count, min, mean, p50, p90, p95, p99, max) for synchronous sender submission,
target convergence-and-dispatch, and target mapping-and-injection work. Recording uses atomic
fixed buckets only; it does not retain event payloads or emit a per-event trace. Existing capture
diagnostics retain source callback delay, while native receiver/transport snapshots retain bounded
send/receive counters and sequence observations. No cross-device stage or one-way value is derived
without a qualified clock mapping.

## Deterministic Reliability Results

JVM scenarios cover zero-loss, duplicated transport sequence, semantic redundant copies
with distinct sequences, sequence wrap, key release arriving before a delayed key down,
older gamepad/absolute snapshots, stale relative-button metadata, reset watermarks, and
lost/missing touch transitions. Copy-survival coverage runs every nonempty subset for one,
two, and three immediate critical/reset copies. Touch coverage includes lost PointerDown,
PointerUp, terminal Up with pointer-ID reuse, stale post-release frames, and a four-contact
repair. The convergent controller injects one semantic event for received critical copies,
drops stale replaceable snapshots, preserves unique relative motion and scroll deltas, and
repairs touch state synchronously from stable pointer IDs.

| Deterministic scenario | RFC-004F baseline | RFC-004G result |
| --- | --- | --- |
| Reordered KeyUp before KeyDown | Arrival order can leave the later KeyDown applied | Older KeyDown is dropped by its per-key watermark |
| Same-sequence relative duplicate | Both deltas are forwarded | The second datagram is suppressed before mapping |
| Redundant Key / Reset copies | Each received copy is forwarded | Every nonempty 1/2/3-copy delivery subset injects one semantic event; newest duplicate advances its watermark |
| Same timestamp, later identical event | No distinction is available | Only adjacent immediate-copy sequence values deduplicate; later legitimate observations remain deliverable |
| Lost touch transition followed by newer complete state | No target reconciliation | Stable pointer-ID repair rebuilds/releases synchronously, bounded to 64 events or one local reset |

The resulting production trade-off is explicit:

| Change | Benefit | Cost |
| --- | --- | --- |
| Two critical copies | Reduces loss of key, touch transition, and button state changes | One extra UDP datagram for each critical transition |
| Three reset copies | Improves chance that a rare reset arrives | Two extra UDP datagrams for each reset |
| Total immediate send failure | Keeps the next identical pointer/gamepad observation critical | No retry timer or retained payload |
| Sequence/state convergence | Stops late state from resurrecting released controls | Fixed bounded state metadata |
| Touch reconciliation | Recovers from a newer complete touch snapshot after a lost transition | A bounded synchronous local repair burst or one local reset |

No-loss behavior remains immediate: a fresh event is either forwarded once, or an
intentional redundant critical copy is suppressed before target mapping/injection.

## Native Host Benchmark

Environment: Windows x86_64, MSVC 19.39 (Visual Studio 17.9.8), Release, 1,000 iterations per
measurement.
The benchmark uses fixed Input Payload V1 objects, packetizes into the existing 417-byte
scratch storage, strictly parses the packet, and calls native `InputReceiverRuntime`
`accept_datagram()`. It does not call a UDP socket, JNI, Binder, InputManager, or Android
hardware in the timed operation.

| Scenario | Packetize p50/p95/p99 us | Parse p50/p95/p99 us | Receiver accept p50/p95/p99 us |
| --- | ---: | ---: | ---: |
| Key | 0.3 / 0.3 / 0.3 | 0.3 / 0.4 / 0.6 | 0.7 / 1.0 / 1.1 |
| Touch 1 | 0.3 / 0.4 / 0.5 | 0.6 / 0.9 / 1.0 | 1.2 / 1.6 / 1.9 |
| Touch 4 | 0.4 / 0.5 / 0.7 | 0.7 / 0.8 / 1.1 | 1.4 / 1.8 / 2.0 |
| Touch 32 | 1.2 / 1.7 / 2.1 | 1.1 / 1.8 / 2.2 | 2.2 / 4.1 / 4.4 |
| Pointer absolute | 0.3 / 0.4 / 0.5 | 0.4 / 0.5 / 0.6 | 0.8 / 1.0 / 1.2 |
| Pointer relative | 0.3 / 0.4 / 0.5 | 0.4 / 0.4 / 0.5 | 0.8 / 1.0 / 1.2 |
| Scroll | 0.3 / 0.4 / 0.5 | 0.4 / 0.4 / 0.6 | 0.8 / 1.0 / 1.3 |
| Gamepad state | 0.3 / 0.4 / 0.5 | 0.4 / 0.5 / 0.6 | 0.8 / 1.0 / 1.2 |
| ResetState | 0.3 / 0.4 / 0.5 | 0.3 / 0.5 / 0.6 | 0.7 / 0.9 / 1.1 |

Generated CSV is intentionally written only under ignored `native/build` or
`native/build-release` directories. Measurements are implementation-cost diagnostics,
not end-to-end input latency claims.

## Device Status

At RFC-004G execution time no connected Android device or emulator was used for a
runtime measurement. Therefore the following are `NOT RUN`: InputManager/Binder latency,
observed injected device ID, UHID `/dev/uhid` probe, physical keyboard/touch/mouse/gamepad
delivery, real game compatibility, same-device loopback, and two-device one-way latency.

## Final Decision

`UltraLowLatencyConvergent` is selected with `criticalCopies = 2`, `resetCopies = 3`,
`networkReorderWaitUs = 0`, sequence duplicate capacity 64, semantic duplicate capacity
32, and touch repair enabled. Input NACK, SCL FEC, generic retransmission, ACKs, and an
input jitter buffer remain disabled because they would require waiting or future work that
does not improve the zero-wait baseline.
