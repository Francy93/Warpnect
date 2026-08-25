# RFC-006F - Runtime Diagnostics UI

## Status

Implemented as a local, read-only Android presentation surface. It consumes Runtime Telemetry Model
V1 and Diagnostic Event Model V1 without changing a packet, Session, media, ClockSync, security,
recovery, or protocol decision.

## Architecture

```text
TelemetryHub + DiagnosticEventHub
        | one cold snapshot + one incremental event read
DiagnosticsUiController (screen scoped, background dispatcher)
        | immutable DiagnosticsUiState StateFlow
Compose DiagnosticsScreen (lifecycle-aware collection)
```

Composable rendering has no TelemetryHub or DiagnosticEventHub calls. The controller owns
presentation state only; the Android application composition retains the existing process-scoped
hubs across Activity recreation. Snapshot collection, WNTM/WNDE parsing, and UI projection run on
an existing background dispatcher. Compose receives only final immutable state on Main.

## Sampling Policy

- Default refresh interval: 1000 ms.
- Allowed intervals: 500, 1000, 2000, and 5000 ms.
- One controller accepts at most one refresh in flight; extra periodic/manual requests coalesce.
- Pause stops periodic telemetry and WNDE/event reads but retains the last state. Manual refresh
  remains available while paused.
- Lifecycle `ON_START` begins screen sampling and `ON_STOP` cancels the screen scheduler. Closing
  the surface closes the controller.

One refresh reads one `TelemetryHub.snapshot()` and one cursor-based
`DiagnosticEventHub.snapshotSince(...)`, then performs bounded projection. Tabs do not request
their own snapshots. There is no permanent sampler, thread, executor, queue, or process timer.

## Presentation

`DiagnosticsUiState` is immutable and contains no runtime source, hub, native handle, codec, Oboe
stream, or protection object. It exposes Overview, Media, Network, Latency, Events, and Raw
sections. Session selection uses SessionId plus SessionGeneration internally but displays a local
`Session N - Generation M` label instead of a full SessionId.

Overview includes sampling state, Complete/Partial health, snapshot age, source/session counts,
selected generation, available role/lifecycle, active path, ClockSync qualification, warning/error
event count, and history gaps. It remains usable with no active Session.

Media renders only actually-bound Video, SystemAudio, and reverse-Input metrics. Video distinguishes
decoded output, Surface releases, render-policy drops, and Android render notifications; a render
notification is not called a physically displayed frame. PCM Samples retain the RFC-006B sample
frame semantic, and no production MicrophoneAudio panel is fabricated.

Network renders represented paths, outer UDP outcomes/throughput, protection, FEC,
NACK/retransmission, reassembly, and recovery counters. New protected records remain distinct from
successful UDP sends. Platform availability/loss remains distinct from authenticated validation and
active state.

## Rates, Histograms, And Latency

Only current and previous telemetry snapshots are retained. A rate is available only when source ID
and MetricId match, the counter did not decrease/saturate, and the capture-time delta is positive.
Fresh-generation or recreated source replacement therefore breaks the rate series. Encoded media
bitrate uses encoded payload bytes; network throughput uses outer UDP bytes.

Histograms display cumulative sample count, average, min/max, p50, p95, and p99. Percentiles are
fixed-bucket upper bounds such as `<= 16 ms`; the infinity bucket is `> largest finite boundary`.
Empty histograms say `No samples`; invalid gauges say `Unavailable`; no raw samples, charts, or
metric time-series history exist.

Only bound RFC-006D values are displayed. ClockSync retains qualification, signed offset,
uncertainty, sample counters, and RTT where available. Oboe output latency is labeled an estimate.
Unsupported transport one-way, Video source-to-render, and Input source-to-injection measurements
are explicitly `Unavailable`, never zero or fabricated from incompatible timestamps.

## Event History

The controller reads RFC-006E cursors incrementally at the bounded 256-event/provider limit. The UI
keeps at most 512 application and 512 native presentation records, dropping only its own oldest view
record when full. It surfaces authoritative overwrite gaps.

Application and native events are separate lazy lists with stable `(provider, sequence)` keys and
provider-local sequence ordering. They are never sorted together by raw timestamp. Bounded filters
cover severity, provider, static category, and selected Session. Rows show only static descriptor
title, severity, provider sequence, scope summary, clock domain, and predefined scalar fields.

## Raw Metrics And Privacy

Raw inspection is a deterministic lazy list grouped by scope/source and ordered by MetricId. It shows
MetricId, canonical descriptor, kind, unit, formatted value/histogram summary, source ID, and
non-sensitive scope. It is read only.

The UI exposes no DeviceId, full SessionId, peer fingerprint, SAS, key, nonce, packet number,
remote/local address or port, payload/ciphertext, or semantic key/button/axis/pointer/touch content.
Controls are limited to close, section/session selection, pause/resume, refresh, interval choice,
and event filters. There are no migration, reconnect, tuning, export, clipboard, persistence, or
remote reporting controls.

## Cost And Version Audit

- Runtime producer-to-Compose pushes: 0.
- Per metric/event StateFlow: 0; one immutable state per refresh or UI intent.
- Per-row JNI: 0; existing bounded WNTM/WNDE batches remain the only native reads.
- UI delivery/history/chart/export queues: 0.
- Permanent diagnostics UI threads/timers: 0.
- Packet, Video, Audio, and Input payload copies caused by UI: 0.
- UI state: current/previous telemetry snapshots plus 512 events/provider, with no metric history.

Runtime Telemetry Model V1, WNTM V1, Diagnostic Event Model V1, WNDE V1, Architecture Version
1.0, SCL Protocol Version 1, and Native Bridge ABI Version 1 remain unchanged. RFC-006F adds no
MetricId, EventTypeId, native UI code, or network protocol.

## Tests And Device Status

JVM coverage includes rates/source continuity, bucket percentile formatting, invalid gauges,
refresh coalescing, screen cadence/stopping, pause/manual refresh, disabled telemetry, event cursors
and provider order, UI event capacity, Session/generation projection, and SessionId privacy.

The project has no existing Compose instrumentation test infrastructure, so RFC-006F uses focused
controller/projection JVM tests rather than adding a broad UI test stack. Final Android build,
native regression, ABI, and `adb` status are reported with completion. Device UI validation is only
claimed when an attached device is present.

## Limitations

RFC-006F intentionally adds no export, persistence, remote telemetry, runtime tuning, adaptive
control, charts/time-series history, completed trace history, or fabricated unsupported one-way
latency. RFC-006G owns reports and export.
