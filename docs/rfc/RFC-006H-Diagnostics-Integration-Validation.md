# RFC-006H - Diagnostics Integration & Validation

## Status

Phase 6 is implementation-complete. This RFC validates the integrated local diagnostics stack
without adding a MetricId, Diagnostic EventTypeId, wire field, WNTM/WNDE revision, report-schema
field, background service, or adaptive control.

## Ownership

```text
WarpnectApplication
  -> AndroidSecureSessionComposition
       -> TelemetryHub
       -> DiagnosticEventHub
       -> ReportExportController
       -> Session lifecycle, media, Input, protection and path runtimes

Activity / Compose
  -> DiagnosticsUiController
       -> bounded cold TelemetryHub and DiagnosticEventHub reads
       -> immutable UI state
```

`AndroidSecureSessionComposition.Factory` constructs the application-owned hubs and report/export
controller once. `DiagnosticsUiController` is screen-scoped and owns presentation state only;
activity recreation neither reconstructs the runtime hubs nor registers another native provider.

## Frozen Models

| Contract | Final value |
| --- | --- |
| Architecture | 1.0 |
| SCL protocol | 1 |
| Native Bridge ABI | 1 |
| Runtime Telemetry Model / WNTM | V1 / V1 |
| Diagnostic Event Model / WNDE | V1 / V1 |
| Diagnostic Report Schema | V1 |
| Runtime metric descriptors | 143 / 512 |
| Diagnostic event descriptors | 52 / 256 |

`PayloadType.Telemetry` remains inactive. PacketHeader V1 (21 bytes), media and Input payload
V1, ClockSync, NACK/FEC, WNPB, WNSH, WNSD, WNCP, WNSN, and WNSL are unchanged. WNDE remains an
in-process little-endian JNI batch, never a network message.

## Lifecycle and Generation Validation

Existing session integration tests plus this RFC's focused tests validate startup, Running,
same-generation migration, reconnect, close, event retention, and bounded report capture.

* Same-generation migration preserves the Session generation, Channel and protection runtime, and
  the pre-bound network/media/latency telemetry identities for surviving pipelines. Counters and
  histograms remain cumulative; event history records the migration boundary.
* Fresh-generation reconnect closes generation-N telemetry/correlation state and creates fresh
  generation-N+1 sources. Generation-N events remain readable in the bounded process history.
* UI rate calculation and benchmark counter/histogram deltas reject source replacement. The
  benchmark integration test reports `interrupted_by_generation_change` for N to N+1 and
  `interrupted_by_session_close` after close.
* Late callbacks are guarded by existing Session/Generation/Channel ownership; they cannot
  complete generation-N+1 latency state or mutate its telemetry/event scope.

## Failure Isolation

| Failure or bound | Validated behavior |
| --- | --- |
| Telemetry disabled or source capacity exhausted | Runtime continues with no-op diagnostic handles; registration self-metrics report rejection. |
| WNTM provider failure | Kotlin metrics remain available; snapshot/report are partial and UI can show native unavailable. |
| WNDE provider failure or malformed batch | Kotlin event history remains available; provider status is partial/failed without Session impact. |
| Kotlin/native event ring wrap | Oldest records overwrite; producer does not wait; cursor and report/UI gap metadata expose loss. |
| Correlation table full or expired | Telemetry sample is rejected/expired; media/Input runtime continues. |
| UI refresh failure or controller close | Last valid presentation can remain visible; hubs and Session are not closed. |
| Report preparation/write/picker failure | The report controller releases its cold export gate and baseline state; no Session behavior changes. |

The controller now has a narrow cold-path benchmark ownership lock and generation epoch. It makes
Start/Stop/Cancel deterministic, claims a stop once, releases the export gate if temp generation
fails, and prevents a late start completion from re-installing a cancelled baseline. It introduces
no hot-path lock, worker, queue, or telemetry schema change.

## High-Rate, Clock, and Privacy Audits

Packet protection rejects, replay/endpoint failures, FEC/NACK/reassembly, media frames, Oboe
callbacks, Input events, and latency samples remain aggregate metrics, not event-history records.
The integration packet-flood test performed 2,048 UDP/authentication updates while retaining only
the deliberately emitted lifecycle event. ClockSync events are transition-only.

Android BOOTTIME, Android MONOTONIC, native monotonic, and peer ClockSync mappings retain their
explicit domains. Kotlin and native events remain provider-separated in UI and JSON. The frozen
timestamp provenance does not support generic cross-device transport or source-to-render latency;
UI/report/benchmark projection leaves those values unavailable rather than rendering zero.

Reports retain role, generation, channel/path kinds, and bounded platform metadata, but exclude
raw SessionId/DeviceId, peer identity, endpoints, cryptographic material, packet identifiers, SAS,
and semantic Input content. Exact 64-bit values remain decimal strings. Fixed-clock report tests
produce byte-identical JSON and preserve `ULong.MAX_VALUE` exactly.

## Bounds and Cost

* The selected runtime allows one production channel per ChannelKind. A maximum receiver-side
  Video/SystemAudio/MicrophoneAudio/Input set uses 11 channel/component sources, plus two session
  sources and four Path sources: 17 per Session. At eight Sessions this is 136, plus the process
  framework source, or 137 active sources, below the 512 hard limit. The usual
  Video/SystemAudio/Input profile is 14 per Session.
* Kotlin event history is 1,024 preallocated records across 12 `AtomicLongArray` columns, roughly
  98,304 bytes of primitive slots plus atomic-array/object overhead. Native history is 1,024
  fixed 96-byte records, 98,304 bytes plus metadata; a WNDE batch is capped at 128 KiB.
* Each `LatencyCorrelationTable` is a 256-entry fixed open-addressing table with an eight-probe
  bound, two-second opportunistic expiry, five `LongArray` columns and one `BooleanArray`: 10,496
  bytes of primitive storage before JVM headers. Completed traces are immediately discarded.
* UI holds current and previous snapshots and at most 512 presentation events per provider; it
  holds no metric time series. Report capture retains only start/end snapshots, cursor windows,
  one prepared cache file capped at 8 MiB, and a bounded streaming buffer.

No Phase 6 runtime payload copies, per-packet/frame/audio/Input JNI calls, permanent diagnostics
threads, benchmark timers, completed-trace queues, report queues, persistence, or remote telemetry
were found. `TelemetryHub` registration synchronization and native WNTM/WNDE provider
synchronization are cold snapshot paths; direct runtime updates use pre-bound atomics. Oboe's data
callback remains restricted to its accepted pre-bound atomics and makes no timestamp/latency query,
JNI call, allocation, lock, event write, or snapshot request.

## Validation

* JVM: 482 tests, 0 failures, 0 errors (`testDebugUnitTest --rerun-tasks`).
* Android: `ktlintCheck`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` passed.
  Debug native libraries built for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
* Native: Debug CTest 22/22 and Release CTest 22/22 passed.
* Host Release benchmarks: counter increment p50 0.1 us, histogram record p50 0.5 us, native
  event write p50 0.2 us, and native 256-event snapshot p50 50.6 us. These are host observations,
  not Android performance guarantees.

## Hardware Status and Debt

No attached Android device was available for this validation pass. Real Compose lifecycle/rendering,
MediaCodec render timestamps, Oboe cold output estimate, privileged SystemAudio/Input, real LAN or
Wi-Fi Direct callbacks, two-device migration/reconnect, Storage Access Framework provider writes,
and streaming benchmark export remain real-device validation debt. This is not a Phase 6 software
implementation blocker.

`native/build-rfc006e` was confirmed as an ignored, untracked generated build directory inside the
workspace. The execution environment rejected its deletion; it remains non-source and must not be
committed.

## Final Result

Phase 6 diagnostics are bounded, observational, generation-safe, lifecycle-safe, and explicitly
truthful about partial snapshots, history gaps, source replacement, clock domains, and unavailable
measurements. There is no next phase work in this RFC.
