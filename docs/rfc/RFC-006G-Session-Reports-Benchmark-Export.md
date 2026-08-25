# RFC-006G - Session Reports & Benchmark Export

## Status

Implemented. Diagnostic Report Schema V1 is a local, explicit UTF-8 JSON export format. It changes
no SCL, WNSD, WNTM, WNDE, Runtime Telemetry, or Diagnostic Event wire/schema version.

## Report Schema

Every deterministic report uses `schema = warpnect.diagnostics.report`, `schema_version = 1`, and
one of `diagnostics_snapshot` or `benchmark_window`. Its top-level fields are `schema`,
`schema_version`, `report_type`, `generated_at_utc`, `runtime`, `platform`, `scope`, `capture`,
`telemetry`, `events`, `benchmark`, and `limitations`.

Telemetry records are ordered by scope, source ID, and metric ID. Application and native event
records are retained in separate sequence-ordered provider sections; their clock domains are
serialized and they are never falsely globally timestamp-sorted. Counters, gauges, event sequences,
timestamps, histogram values, durations, and deltas use decimal strings whenever exact 64-bit
preservation matters. Histograms export cumulative count, sum, optional min/max, boundaries, and
bucket counts. Invalid gauges remain invalid rather than becoming zero.

## Privacy and Bounds

The selected Session is serialized only as local alias `session = 1` with generation and safe role
metadata. The real SessionId is filtering-only. Reports exclude DeviceId, peer identity, addresses,
ports, SSID/BSSID/MAC, SAS, keys, nonces, packet numbers, replay state, ciphertext, and semantic
Input data. App version, SDK, ABI, manufacturer, and model are allowed metadata; serials,
fingerprints, advertising IDs, and account data are not.

Reports have at most 4096 telemetry records, 1024 events per provider, and 8 MiB of UTF-8 JSON.
The writer streams to one app-cache temporary file through a bounded output stream; oversized reports
fail rather than silently dropping arbitrary records. The file is removed after success, cancellation,
failure, replacement, or controller close.

## Snapshot and Benchmark Semantics

A DiagnosticsSnapshot always makes a fresh TelemetryHub snapshot and bounded DiagnosticEventHub read;
it never serializes diagnostics UI state. A BenchmarkWindow requires a selected Session/generation.
Start takes one telemetry baseline and records the two provider cursors. While active it takes no
intermediate snapshots, event reads, timer ticks, worker jobs, or samples. Stop takes one final
snapshot and reads only events retained since those cursors. Cancellation discards the baseline.

Counter and histogram window deltas require the same source ID, metric ID, and structural scope.
Fresh source or generation replacement marks the series unavailable. A generation transition is
explicitly `interrupted_by_generation_change`; disappearance is `interrupted_by_session_close`.
Gauge reports contain only start and end values. Compatible histograms expose count, sum, bucket,
average, and fixed-bucket percentile upper-bound deltas, while `window_min` and `window_max` are
always unavailable because two cumulative snapshots cannot reconstruct them truthfully.

## Android Export

The diagnostics UI explicitly prepares a report, then uses `CreateDocument(application/json)` with a
sanitized `warpnect-diagnostics-*` or `warpnect-benchmark-*` filename. Activity/Compose only launches
the picker and returns its URI. Snapshotting, WNDE collection, projection, temporary-file creation,
and ContentResolver `wt` (then narrow `w` fallback) copy occur on the existing IO dispatcher. There
is no broad storage permission, hard-coded destination, share sheet, upload, persistence, or native
report runtime.

## Validation

Focused JVM tests cover 64-bit JSON text, selected-Session filtering, deterministic output, one
baseline plus one end benchmark capture, source replacement, exact counter deltas/rates, and the
histogram window-min/max limitation. Full Gradle, native, ABI, and device validation are reported
from the final current-worktree run; no device result is inferred from host tests.

## Limitations

RFC-006G adds no automatic export, report archive, cloud upload, share sheet, continuous benchmark
sampling, time-series history, synthetic workload, remote telemetry, runtime tuning, or fabricated
unsupported cross-device latency. RFC-006H remains the next phase.
