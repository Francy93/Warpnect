# RFC-006E - Diagnostic Logging & Bounded Event History

## Status

Implemented. Diagnostic Event Model Version is `1 (LOCAL / NON-WIRE)`. Native Diagnostic Event
Bridge is `WNDE V1 (LOCAL / JNI ONLY)`. Architecture Version remains `1.0`, SCL Protocol Version
remains `1`, Native Bridge ABI Version remains `1`, and WNTM remains V1.

## Goals and Boundaries

RFC-006E adds a process-local structured history for significant lifecycle transitions and failures.
It is observational: it does not change Session state, packet acceptance, media behavior, input,
ClockSync, recovery, security, or route selection. It creates no network payload, remote reporting,
persistence, export, UI, worker, delivery queue, or timer.

The history intentionally excludes UDP datagrams, WNSD record outcomes, AEAD failures, replay
rejects, FEC/NACK/reassembly activity, video/audio frames, Oboe callbacks, normal input events, and
latency samples. Those remain aggregate Runtime Telemetry metrics.

## Event Model V1

`DiagnosticEventDescriptorCatalog` contains 52 static descriptors, below the V1 maximum of 256.
Every descriptor has an explicit non-zero u16 ID, canonical `warpnect.event.*` name, fixed default
severity, permitted typed scope kinds, payload schema, and description. IDs are never enum ordinals.

| Range | Descriptors |
|---|---|
| `0x0001-0x0003` | diagnostic framework: history started, provider failed, malformed WNDE |
| `0x0101-0x0141` | Session state/running/start, migration, reconnect, disconnect, pairing, handshake, capability/setup |
| `0x0201-0x0205` | platform path loss/validation and socket rebind outcomes |
| `0x0301-0x0305` | Video encoder/decoder start/failure and render-target absence |
| `0x0401-0x0406` | Audio capture/playback start/failure and codec failures |
| `0x0501-0x0504` | input injection-service and safety-reset transitions |
| `0x0601-0x0602` | ClockSync qualification transitions |
| `0x0701-0x0702` | trust mismatch and terminal protection-runtime failure |
| `0x0801-0x0802` | privileged-platform service unavailable/restored |

The complete V1 descriptor index is static:

```text
0x0001 diagnostic.history_started                    Info     -
0x0002 diagnostic.provider_failed                    Warning  Reason
0x0003 diagnostic.native_bridge_malformed            Error    -
0x0101 session.state_changed                          Info     FromState, ToState
0x0102 session.running                                Info     -
0x0103 session.start_failed                           Error    Reason, RawCode
0x0104 session.suspended                              Warning  Reason
0x0105 session.path_migration_started                 Info     PathId, TargetPathId
0x0106 session.path_migration_succeeded               Info     PathId, TargetPathId
0x0107 session.path_migration_failed                  Warning  PathId, TargetPathId, Reason
0x0108 session.reconnect_started                      Warning  OldGeneration, NewGeneration
0x0109 session.reconnect_attempt_failed               Warning  Attempt, Reason
0x010A session.reconnect_succeeded                    Info     OldGeneration, NewGeneration
0x010B session.reconnect_expired                      Error    -
0x010C session.reconnect_cancelled                    Info     Reason
0x010D session.disconnect_local                       Info     Reason
0x010E session.disconnect_remote                      Info     Reason
0x0120 pairing.started                                Info     -
0x0121 pairing.sas_ready                              Info     -
0x0122 pairing.succeeded                              Info     -
0x0123 pairing.failed                                 Warning  Reason
0x0130 handshake.started                              Info     -
0x0131 handshake.succeeded                            Info     -
0x0132 handshake.failed                               Warning  Reason
0x0140 session.capability_negotiation_failed          Warning  Reason
0x0141 session.setup_failed                           Error    Reason
0x0201 network.path_platform_losing                   Warning  PathId
0x0202 network.path_platform_lost                     Warning  PathId
0x0203 network.path_validation_failed                 Warning  PathId, Reason
0x0204 network.socket_rebind_succeeded                Info     PathId, TargetPathId
0x0205 network.socket_rebind_failed                   Error    PathId, TargetPathId, Reason
0x0301 video.encoder_started                          Info     -
0x0302 video.encoder_failed                           Error    Reason, RawCode
0x0303 video.decoder_started                          Info     -
0x0304 video.decoder_failed                           Error    Reason, RawCode
0x0305 video.render_target_unavailable                Warning  -
0x0401 audio.capture_started                          Info     -
0x0402 audio.capture_failed                           Error    Reason, RawCode
0x0403 audio.playback_started                         Info     -
0x0404 audio.playback_failed                          Error    Reason, RawCode
0x0405 audio.encoder_failed                           Error    Reason, RawCode
0x0406 audio.decoder_failed                           Error    Reason, RawCode
0x0501 input.injection_service_available              Info     -
0x0502 input.injection_service_lost                   Warning  -
0x0503 input.safety_reset                             Warning  Reason
0x0504 input.injection_failed_fatal                   Error    Reason, RawCode
0x0601 clock.sync_qualified                           Info     -
0x0602 clock.sync_unqualified                         Warning  Reason
0x0701 security.trust_key_mismatch                    Error    -
0x0702 security.protection_runtime_failed             Critical Reason, RawCode
0x0801 platform.privileged_service_unavailable        Error    Reason
0x0802 platform.privileged_service_restored           Info     -
```

V1 severities are `Debug`, `Info`, `Warning`, `Error`, and `Critical`. Each record has at most four
predefined 64-bit scalar fields. V1 allows unsigned, signed, boolean, and bounded-enum fields only.
The initial keys are Reason, FromState, ToState, PathId, TargetPathId, Attempt, RawCode, Count,
DurationUs, OldGeneration, and NewGeneration. No string, map, JSON, byte array, Throwable, SAS, or
vendor diagnostic string enters retained storage.

## Scope and Clocks

Each record embeds an immutable Process, Session, Path, Channel, or Component scope snapshot with
the original TelemetrySourceId when available. It contains bounded SessionId/generation, PathId/
kind, ChannelId/kind/direction, or ComponentKind fields as appropriate. The snapshot survives source
and Session closure, so old records stay understandable without a live registry lookup.

Kotlin event recording uses `ANDROID_BOOTTIME` via `SystemClock.elapsedRealtimeNanos()`. Native
events retain `NATIVE_STEADY`. The providers have independent monotonically increasing u64 sequence
spaces. DiagnosticEventHub returns separate Kotlin and native batches rather than falsely sorting
raw timestamps from different clock domains into one exact timeline.

## Fixed Histories and Cursors

Kotlin uses preallocated `AtomicLongArray` storage for 1024 records. Native uses a preallocated 1024
record ring. Both overwrite the oldest retained item when full; neither grows or waits for a reader.
Native writers use a non-waiting ring acquisition and may drop a rare observation while a cold
snapshot owns the ring rather than block the producer.

`snapshotSince(DiagnosticEventCursor, limit)` reads independent Kotlin/native cursor ranges. The
default is 256 events per provider and the hard request maximum is 512. Each batch reports retained
events, oldest/newest available sequence, next cursor, overwrite count, gap, and truncation. Reads
are non-destructive. A cursor before the oldest retained record reports a visible gap.

Completed events are retained only in these bounded rings. This is history, not a delivery queue:
there is no StateFlow, SharedFlow, LiveData stream, consumer backpressure, or event worker.

## WNDE V1

Native history is collected through one cold JNI call, `NativeBridge.diagnosticEventSnapshot`, per
native history read. WNDE is little-endian and bounded to 128 KiB.

Header, exactly 32 bytes:

```text
0   4   magic "WNDE"
4   2   bridge version = 1
6   2   header bytes = 32
8   8   native batch sequence u64
16  8   native source monotonic timestamp ns u64
24  4   record count u32
28  4   total bytes u32
```

Each record is exactly 96 bytes: u64 event sequence/timestamp, u8 clock domain/severity/scope/
field count, u16 type/flags, u32 source/generation, two u64 SessionId words, u32 path/channel IDs,
four bounded scope-kind bytes, zero reserved u32, and four u64 payload slots. Kotlin rejects bad
magic/version/length/count/record bounds, non-zero reserved values, invalid enums, unknown IDs,
severity/schema mismatches, and invalid typed enum payloads. Parse failure returns a provider status
without touching Session behavior, increments a self metric, and records one bounded framework event.

## Integration

`AndroidSecureSessionComposition` owns one process DiagnosticEventHub, created independently of
activity recreation and closed after the application/session graph. The hub reuses the process
TelemetryHub only for self metrics.

SessionLifecycleController emits accepted state/running, suspension, migration, reconnect, and
idempotent disconnect events at the existing RFC-005H transaction boundaries. Android path monitor
loss hints become Path-scope events and remain local hints, not peer liveness. Pairing emits started,
SAS-ready without digits, trust success/failure, and trust-key-mismatch without key material.
Handshake emits start, terminal success/failure, and trust mismatch only at existing attempt
boundaries. Pipeline component startup reports Video encoder/decoder, Audio capture/playback, and
Input injection-service state without entering a MediaCodec frame callback or Oboe callback.

Capability/setup, platform-restored, and native terminal-protection descriptors remain static but
are not emitted unless their present component has a truthful existing semantic boundary. RFC-006E
does not manufacture lifecycle state to populate a catalog.

## Logging Sink and Privacy

Structured history is primary. `AndroidLogcatDiagnosticSink` is an optional developer sink and is
disabled by default in production composition. When explicitly selected it formats only canonical
event name, severity, normalized reason, and permitted raw numeric code. It omits SessionId,
DeviceId, addresses, ports, fingerprints, SAS, packet numbers, payloads, and arbitrary exception
text. Sink failure only increments a self metric.

The model never retains packet/media payload, traffic or pairing secrets, ECDH private key, cookie
key, nonce, ciphertext, tag, remote address, device/fingerprint, typed input content, key/button/
axis value, or pointer/touch coordinate.

## Runtime Telemetry Self Metrics

RFC-006E adds these Process counters to Runtime Telemetry Model V1:

| ID | Name |
|---|---|
| `0x0008` | `warpnect.diagnostic.event.recorded` |
| `0x0009` | `warpnect.diagnostic.event.overwritten` |
| `0x000A` | `warpnect.diagnostic.snapshot.count` |
| `0x000B` | `warpnect.diagnostic.native_parse_failure` |
| `0x000C` | `warpnect.diagnostic.log_sink_failure` |

The Runtime Telemetry descriptor catalog is therefore `143 / 512`.

## Cost, Memory, and Validation

High-rate diagnostic writes are all zero by design: packet `0`, Video-frame `0`, Audio callback
`0`, Input event `0`, and latency sample `0`. WNDE emission performs no JNI. Native WNDE collection
is one cold JNI call; Kotlin ring writers use atomics and native rare writers use only the local
ring's non-waiting synchronization. No new payload copies, event queues, worker threads, or timers
are introduced.

Native retained storage is 1024 x 96 bytes = 98,304 bytes plus ring metadata. Kotlin uses twelve
preallocated primitive arrays of 1024 slots; cold reads materialize at most 256 records by default
or 512 by explicit bounded request. The WNDE direct buffer is 128 KiB.

RFC-006D's conservative combined source audit was 345 of 512 slots: 43 per Session across eight
Sessions plus one process source. RFC-006E adds one process-scoped DiagnosticEventHub self-metric
source and no event-only Session/Path/Channel sources, for 346 of 512 slots and 166 slots of
headroom. The normal production profile remains materially smaller; source rejection remains
observational and supplies disabled handles.

Tests cover catalog validity, Kotlin overwrite/gap/cursor behavior, disabled history, immutable scope
retention, WNDE golden parsing and malformed rejection, self-metric isolation, native WNDE byte
layout, native overwrite/gap behavior, and concurrent native writers. Host-only results validate
representation and bounded behavior; Android device Logcat/media/audio/input runtime capture remains
device validation work when no device is attached.

## Limitations

RFC-006E adds no diagnostics UI, persistence, file/session export, remote logging, full-text
exception history, per-packet event log, or completed latency-trace history. It does not activate
`PayloadType.Telemetry` and does not introduce adaptive runtime control.
