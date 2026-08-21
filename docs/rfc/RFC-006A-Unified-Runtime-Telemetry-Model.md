# RFC-006A - Unified Runtime Telemetry Model

## Status

Complete. Runtime Telemetry Model Version is `1` and is local/non-wire. Architecture Version,
SCL Protocol Version, and Native Bridge ABI Version remain `1.0`, `1`, and `1` respectively.

## Purpose and Scope

RFC-006A supplies a bounded observational telemetry foundation shared by Kotlin control/platform
code and portable native C++20 code. It establishes descriptor identity, scopes, atomics, source
lifetimes, immutable on-demand snapshots, and one batched native snapshot bridge. It does not yet
instrument all Video, Audio, Input, network/recovery, ClockSync, or end-to-end latency signals.

Telemetry never affects codecs, capability selection, buffering, path choice, migration, reconnect,
FEC, NACK, input policy, or render policy. Telemetry failure, disablement, and capacity exhaustion
cannot fail a Session.

## Descriptor Catalog

`TelemetryDescriptorCatalog` is static and validates a maximum of 512 descriptors. Canonical names
are lower-case ASCII `warpnect.*` namespaces. IDs are explicit non-zero u16 values, never enum
ordinals. Ranges are reserved as follows:

```text
0x0001-0x00ff telemetry framework
0x0100-0x01ff session/lifecycle
0x0200-0x02ff network/transport/recovery
0x0300-0x03ff video
0x0400-0x04ff audio
0x0500-0x05ff input
0x0600-0x06ff clock/synchronization/latency
0x0700-0x07ff security/protection
0x0800-0x08ff platform/runtime
```

The current framework descriptors are:

```text
0x0001 warpnect.telemetry.source.active                 GaugeI64 Count
0x0002 warpnect.telemetry.source.registration_rejected  CounterU64 Count
0x0003 warpnect.telemetry.snapshot.count                CounterU64 Count
0x0004 warpnect.telemetry.snapshot.partial              CounterU64 Count
0x0005 warpnect.telemetry.snapshot.provider_failure     CounterU64 Count
0x0006 warpnect.telemetry.update.overflow               CounterU64 Count
0x0007 warpnect.telemetry.snapshot.duration             HistogramU64 Microseconds
```

No runtime descriptor creation or runtime-generated metric name exists.

## Model, Scopes, and Privacy

The only Metric Kinds are `CounterU64`, `GaugeI64`, and `HistogramU64`. Counters saturate instead
of intentionally wrapping. Gauges carry separate validity, so `-1` can be a valid value. Histograms
retain count, sum, min, max, and fixed bucket counts; a descriptor contains at most 16 increasing
finite boundaries and therefore at most 17 buckets.

Scopes are typed `Process`, `Session`, `Path`, `Channel`, and `Component`. Session scopes include
the bounded SessionId/generation pair; Path and Channel scopes add only bounded architectural IDs
and enums. Components use a fixed enum. There are no string labels for DeviceId, IP/port, SSID,
fingerprint, exception text, sequence numbers, thread ID, or surface identity.

Telemetry stores no keys, secrets, pairing/SAS values, payload bytes, typed input, key identity,
touch history, or persistent peer identity by default.

## Sources and Snapshots

A process-local `TelemetryHub` is owned by the Android secure-session composition through
`CoreOrchestrator`, so it survives activity recreation and closes after runtime sources. It supports
at most 512 active registered sources, 32 instruments per source, 8 histograms per source, 32
snapshot providers, and 16,384 records per snapshot. A capacity rejection returns a no-op source;
it never affects the owning Session.

Updates retain direct primitive handles. They take no hub mutex, make no registry/map/name lookup,
allocate nothing after construction, and never call JNI. Registration, unregistration, and snapshot
collection are cold-path operations. Closing a source is idempotent and removes it from later
snapshots without invalidating a runtime-held primitive block.

Snapshots are immutable, on-demand, non-destructive, and weakly consistent. Each record is an
atomic observation, but independent records need not reflect one instant. Snapshot sequence values
increase monotonically; Kotlin composition timestamps use `SystemClock.elapsedRealtimeNanos()`.
Native provider timestamps use their own steady-clock domain, so raw Android/native absolute times
are never subtracted.

## Native Bridge V1

Native sources use `RuntimeTelemetryCounterU64`, `RuntimeTelemetryGaugeI64`,
`RuntimeTelemetryHistogramU64`, and `RuntimeTelemetryRegistry`. `NativeTelemetrySnapshotProvider`
uses one reusable direct buffer of at most 256 KiB and makes one JNI transition per provider
collection, not one per metric. A too-small buffer returns required size without writing a malformed
partial structure.

The local-only little-endian WNTM bridge has a 32-byte header:

```text
0 magic WNTM (4)       4 bridge version u16       6 header bytes u16
8 native sequence u64 16 source monotonic ns u64 24 record count u32
28 total bytes u32
```

Every record begins with its 16-byte `(source id u32, metric id u16, kind u8, flags u8, payload
bytes u16, reserved u16, reserved2 u32)` header. Counter payload is one u64. Gauge payload is a
valid byte, seven zero bytes, then i64. Histogram payload is count/sum/min/max, finite boundary
count, seven zero bytes, then one u64 per bucket including infinity. The Kotlin parser rejects bad
magic/version/size, reserved bytes, payload sizes, unsupported kinds, invalid bucket count, duplicate
records, record overruns, and trailing bytes. WNTM never leaves the process and is not a Warpnect
network protocol.

## Thread, Queue, Lock, and Copy Audit

RFC-006A adds zero permanent threads, executors, handler threads, periodic samplers, update queues,
snapshot-history queues, export queues, or network queues. Hot update global lock, JNI, and registry
lookup are all absent. Registration and snapshot take only a cold-path registry lock. Hot updates
perform zero copies; snapshot creation may make bounded immutable Kotlin copies. No media frame,
encoded access unit, PCM buffer, packet, or input event is copied by the telemetry layer.

## Testing and Limits

JVM coverage validates descriptor uniqueness/name/range rules, counter saturation, gauge validity,
histogram boundaries and concurrent totals, typed scope/generation separation, source close and
capacity behavior, snapshot sequencing/non-destructiveness, partial providers, and WNTM golden and
malformed vectors. Native coverage validates primitives, concurrent histogram totals, source
lifetime, exact WNTM fields, and `BufferTooSmall` no-partial-write behavior. The host benchmark
covers counter increment, gauge set, histogram record, and bounded snapshot collection; it reports
host measurements only and makes no Android latency claim.

## Non-Goals

RFC-006A adds no comprehensive media metrics, network/recovery diagnostics, latency trace model,
event history/logging, diagnostics UI, report/export format, remote telemetry protocol, or adaptive
decision loop. Those remain RFC-006B through RFC-006H.
