# RFC-006C - Network & Recovery Diagnostics

## Status

Complete. RFC-006C extends Runtime Telemetry Model V1 with local, bounded diagnostics for the
existing transport, protection, path, and lifecycle runtime. It defines no wire protocol and does
not activate `PayloadType.Telemetry`.

## Descriptor Catalog

The catalog has 101 of 512 available descriptors. RFC-006C adds the following 54 stable IDs.

| IDs | Names | Scope |
| --- | --- | --- |
| `0101`-`010E` | `warpnect.session.heartbeat.sent`, `.ack_received`, `.miss`, `.suspended`, `.path_migration.started`, `.succeeded`, `.failed`, `.reconnect.attempt`, `.succeeded`, `.attempt_failed`, `.expired`, `.cancelled`, `.disconnect.local`, `.disconnect.remote` | Session |
| `0201`-`0209` | `warpnect.network.udp.datagram.sent`, `.byte.sent`, `.datagram.received`, `.byte.received`, `.send.would_block`, `.send.error`, `.receive.error`, `.path_unavailable.drop`, `.socket.rebind` | Channel or Session |
| `0221`-`0226` | `warpnect.network.fec.data_shard.emitted`, `.parity_shard.emitted`, `.recovery.attempt`, `.shard.recovered`, `.recovery.completed`, `.recovery.failed` | Channel |
| `0231`-`0234` | `warpnect.network.nack.generated`, `.received`, `.retransmission.sent`, `.retransmission.cache_miss` | Channel |
| `0241`-`0244` | `warpnect.network.reassembly.fragment.accepted`, `.completed`, `.timeout`, `.evicted` | Channel |
| `0251`-`0258` | `warpnect.network.path.active`, `.validated`, `.platform_available`, `.platform_losing`, `.platform_lost`, `.validation.started`, `.succeeded`, `.failed` | Path |
| `0701`-`0709` | `warpnect.security.protection.record.produced`, `.record.accepted`, `.protect_error`, `.authentication_failed`, `.replay_dropped`, `.unknown_context`, `.endpoint_mismatch`, `.epoch_rejected`, `.malformed` | Channel or Session |

All metrics are `CounterU64` except `network.path.active` and `network.path.validated`, which are
explicit-validity `GaugeI64` values using `Boolean` units. UDP byte metrics describe actual outer
UDP datagrams, including WNSD overhead. They never describe media payload bytes.

## Source Ownership

One `RuntimeNetworkTelemetry` source is attached to each prepared native Video, Audio, or Input
Channel handle while it is stopped. It resolves its bounded metric pointers once; packet updates use
those pointers directly. The same source survives a same-generation endpoint rebind because the
same native transport survives. `SessionControlNetworkTelemetry` is a separate Kotlin Session scope
source for the existing low-frequency secure control transport. `SessionLifecycleTelemetry` owns
heartbeat, migration, reconnect, and disconnect counters. One `SessionPathTelemetry` source is
created for each represented path.

The current production profile has at most four RFC-006B media/input sources for a Client, three
for a Host, three Channel network sources, one SessionControl source, one lifecycle source, and two
path sources. The largest current profile is therefore 11 sources per Session and 89 including the
process source across the frozen eight-Session hard bound. The generic RFC-006C structure remains
bounded at 38 sources per Session for 32 represented Channels and four paths; source exhaustion is
still observational and returns disabled handles rather than failing a Session.

## Semantics

Native transport telemetry records successful socket sends only after the complete datagram is sent.
`WouldBlock` is distinct from `send.error`. A fresh WNSD record increments
`protection.record.produced` after protection succeeds, even if the later UDP send would block. An
exact cached NACK retransmission increments retransmission and successful UDP-send counters without
creating a new protection record or packet number.

Receive counts occur after the socket accepts an actual datagram and before endpoint filtering,
WNSD authentication, replay rejection, or inner SCL parsing. Protection outcome counters map to the
existing typed `SessionProtectionError` branches: authentication, replay, unknown context, endpoint,
epoch, and structural malformed records remain distinct. Rejected data never advances authenticated
liveness and no metric contains an address, port, packet number, nonce, key, ciphertext, or payload.

FEC, NACK, and reassembly counters are updated at their existing semantic operations. They do not
add Reed-Solomon buffers, retransmission records, cache retention, or reassembly lifetime. Audio and
Input continue to have no FEC path. No RTT, jitter, arrival timing, ClockSync quality, or latency
metric is defined here.

## Paths and Lifecycle

Android availability, losing, and lost callbacks are local hints. `onLosing` is advisory and
correctness never requires it before `onLost`; platform availability does not set the authenticated
path validation gauge. RFC-005H `PathChallenge`/`PathResponse` remains the authority for validation.

Migration is counted once per transaction, never per Channel. A committed Direct-to-LAN or
LAN-to-Direct migration preserves the Channel source ID, ChannelId, WNSD context, packet-number
space, replay state, media telemetry sources, and cumulative counters. Successful live socket
rebinds increment `network.socket.rebind` once per Channel. A fresh reconnect closes old sources and
creates new SessionGeneration-scoped sources from fresh WNSH/WNSD/WNCP/WNSN state.

## Cost, Threading, and Privacy

The normal native send/receive/protection/FEC/NACK/reassembly paths add only pre-bound relaxed
atomic increments or additions. They perform zero telemetry JNI transitions, allocations, string or
registry lookups, global TelemetryHub locks, queues, logging, packet copies, or thread dispatches.
Android callbacks and lifecycle events use pre-bound Kotlin atomics on their existing control path.
RFC-006C creates no telemetry worker, executor, Handler, snapshot history, or remote queue.

Telemetry snapshots remain explicit, weakly consistent, non-destructive cold-path observations.
Native records continue to use the unchanged local WNTM V1 batch: 32-byte little-endian header,
16-byte record header, and a maximum 256 KiB direct buffer. It is not a network protocol.

## Verification

Focused JVM tests cover the 101-descriptor catalog and independent lifecycle, path, and secure-control
source semantics. Existing RFC-005H Direct-to-LAN migration coverage now asserts lifecycle/path
counters while preserving the original runtime identity. The native runtime telemetry test creates
one pre-bound 32-metric Channel source and verifies fresh protection, WouldBlock, retransmission,
accepted/replayed record, and rebind counters remain independent.

The final build matrix records the actual command results in the RFC completion report. No Android
device is required for this local observability foundation; Android LAN/Wi-Fi Direct runtime callback
validation remains device-specific when no device is attached.

## Protocol and Version Audit

Runtime Telemetry Model remains V1 and Native Telemetry Snapshot Bridge remains WNTM V1. Architecture
Version remains 1.0, SCL Protocol Version remains 1, and Native Bridge ABI Version remains 1. PacketHeader
V1 remains 21 bytes. RFC-006C changes none of WNPB, WNSH, WNSD, WNCP, WNSN, WNSL, FEC, NACK, ClockSync,
Video Payload V1, Audio Payload V1, or Input Payload V1.

## Deferred Work

RFC-006C intentionally does not add RTT/jitter, ClockSync quality, cross-pipeline latency tracing,
event history/logging, diagnostics UI, export, remote telemetry, or adaptive control. RFC-006D owns
latency trace and cross-pipeline correlation.
