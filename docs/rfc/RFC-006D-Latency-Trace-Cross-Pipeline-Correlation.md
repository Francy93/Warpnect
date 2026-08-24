# RFC-006D - Latency Trace and Cross-Pipeline Correlation

## Status

Implementation complete. RFC-006D extends Runtime Telemetry Model V1 locally. Current host, JVM,
and Android build verification is complete; real-device latency validation remains explicitly not run.
It
does not activate `PayloadType.Telemetry` or change PacketHeader V1, Video Payload V1, Audio Payload
V1, Input Payload V1, ClockSync, WNSD, WNCP, WNSN, or WNSL.

## Timestamp Provenance Audit

| Value | Origin and unit | Clock domain | Transport survival | Correlation use |
|---|---|---|---|---|
| Video `PacketHeader.timestamp_us` | encoder presentation time, microseconds | Android BOOTTIME at source | yes | receiver input PTS identity, not generic send time |
| Audio `PacketHeader.timestamp_us` | existing capture time, microseconds | capture/native source domain | yes | no peer mapping to remote native clock |
| Input `PacketHeader.timestamp_us` | Android source event time, microseconds | Android uptime | yes | no peer uptime-to-native mapping |
| MediaCodec input/output PTS | access-unit presentation time, microseconds | media identity | receiver-local | exact local decoder key while unchanged |
| `OnFrameRenderedListener.nanoTime` | Android render observation, nanoseconds | Android MONOTONIC | local | matched to Surface release by PTS |
| `System.nanoTime()` | local duration timestamp, nanoseconds | Android MONOTONIC | no | local Video and Opus durations |
| `elapsedRealtimeNanos()` | capture/source helper time, nanoseconds | Android BOOTTIME | no implicit native mapping | never mixed with MONOTONIC |
| `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` | PCM timestamp, nanoseconds | Android MONOTONIC | source metadata only | no cross-device sample without peer bridge |
| Oboe presentation timestamp | `getTimestamp(CLOCK_MONOTONIC)`, nanoseconds | Android MONOTONIC | local | cold output estimate only |
| Android input event/fallback time | event uptime, microseconds | Android uptime | Input V1 preserves time | local capture-to-sender only |
| RFC-001F ClockSync | native `steady_clock` model, microseconds | native steady per peer | ClockSync control only | quality, reference offset, and RTT |
| native SCL monotonic time | `std::chrono::steady_clock`, microseconds | native steady | internal/control | never assumed equal to Kotlin clocks |

Android MONOTONIC, BOOTTIME, uptime, and native `steady_clock` are explicit distinct domains.
RFC-006D adds no local JNI clock bridge because every implemented duration is calculated in one
known domain. The current wire model has no peer Android-source-domain to native-clock bridge.

## Wire Sufficiency Result

`RFC-006D CORRELATION BLOCKER`: generic authenticated transport one-way latency, Video
source-to-decoder/render, Audio source-to-decoder, and Input source-to-receiver/injection cannot be
computed faithfully from frozen data. `PacketHeader.timestamp_us` has type-specific meaning, and
RFC-001F maps native steady clocks rather than each peer Android source clock. The smallest
hypothetical change would be an authenticated typed source timestamp plus source-clock mapping or a
stable cross-device trace identity. RFC-006D deliberately adds neither.

Affected descriptors remain static catalog entries but are unbound where provenance is absent. No
cross-device result is guessed or zero-filled.

## Descriptor Catalog

The catalog grows from 101 to 138 descriptors. RFC-006D adds these 37 local IDs:

```text
0601 clock.sync.sample.accepted             0602 clock.sync.sample.rejected
0603 clock.sync.qualified                   0604 clock.sync.offset
0605 clock.sync.uncertainty                 0606 clock.sync.round_trip
0607 clock.sync.mapping_rejected
0620 latency.transport.one_way              0621 latency.transport.sampled
0622 latency.transport.clock_unqualified    0623 latency.transport.invalid
0630 video.decoder_input_to_output           0631 video.decoder_output_to_release
0632 video.release_to_render                 0633 video.source_to_decoder_input
0634 video.source_to_render                  0635 video.correlation_unmatched
0636 video.correlation_expired
0640 audio.source_to_decoder_input           0641 audio.decoder_input_to_output
0642 audio.output_to_playback_callback       0643 audio.playback_output_estimate
0644 audio.playback_output_estimate_failed   0645 audio.correlation_unmatched
0646 audio.correlation_expired
0650 input.capture_to_sender                 0651 input.source_to_receiver
0652 input.receiver_to_injection             0653 input.source_to_injection
0654 input.correlation_rejected
0660 latency.trace.started                   0661 latency.trace.completed
0662 latency.trace.expired                   0663 latency.trace.capacity_rejected
0664 latency.trace.unmatched                 0665 latency.trace.clock_unqualified
0666 latency.trace.invalid_duration
```

ClockSync RTT uses fixed microsecond boundaries `100, 250, 500, 1000, 2000, 5000, 10000, 20000,
50000, 100000, 250000, 500000`. Local stage histograms use the V1 local boundaries. Cross-device
boundaries remain reserved until a future provenance audit passes.

## Implemented Measurements

Video records decoder-input to output, output to Surface release, and release to the supplied
MediaCodec render `nanoTime`. Correlation is by PTS, never FIFO order. Missing render callbacks
expire; they are not treated as drops. A codec PTS transformation leaves local output/release/render
measurements valid only where the output PTS remains an exact local key.

Audio records sampled one-in-eight synchronous native Opus decoder input-to-output work. The Oboe
output estimate comes only from existing cold `queryPresentationTimestamp`, which requests
`CLOCK_MONOTONIC` presentation and platform estimated output latency. The data callback never
queries a timestamp or latency. The frozen playback ring has no exact decoded-frame-to-callback key,
so output-to-playback-callback remains unsupported.

Input records sampled one-in-four local capture-to-sender duration after the existing sink accepts
the event. It records no event content, and intentional Input V1 redundant transport copies do not
create a second source measurement.

The adopted native Video receiver publishes ClockSync accepted/rejected counters, qualified state,
model reference offset, and the existing RTT through one native WNTM source. RFC-001F currently has
no uncertainty estimator, so the uncertainty gauge is deliberately unbound.

## Correlation Ownership and Bounds

The Video decoder owns one `LatencyCorrelationTable`: 256 power-of-two entries, maximum probe 8,
and opportunistic two-second expiry. Five preallocated `LongArray` fields consume 10,240 bytes of
primitive payload storage and the occupancy array adds 256 bytes, excluding JVM headers. Audio and
Input use immediate local stage duration and allocate no correlation table. There is no completed
trace history, trace queue, timer, or global trace lock.

Sources register during pipeline construction. Same-generation path rebind preserves Video, Audio,
Input, and native ClockSync source identities because their pipeline and adopted native transport
survive. Generation N+1 closes old sources and discards in-flight state before creating new sources.
Late old callbacks cannot mutate N+1 source state.

RFC-006B uses at most four local media sources per Session; RFC-006C uses at most 38; RFC-006D adds
one native ClockSync source for a local Video receiver. The conservative maximum is 43 per Session,
344 across eight Sessions, plus one process source: 345/512, with 167 remaining slots.

## Runtime, Privacy, and Threading

Video callbacks execute one bounded table operation and at most one histogram update. Sampled Input
and Opus use existing timestamps and one histogram update. ClockSync updates pre-bound native
counter/gauge/histogram handles on its existing control response path. None allocates media data,
copies payloads, looks up the registry, takes a global telemetry lock, crosses JNI per event, starts
a thread, schedules a timer, logs an event, or changes runtime control decisions.

Transient PTS/source-time keys never enter snapshots. No metric contains payload data, IP/port,
DeviceId, peer fingerprint, key material, packet number, key/button/axis value, character, or
pointer/touch coordinate. ClockSync offset and RTT are observational; qualification does not change
security, path choice, A/V sync, codec, audio, or recovery behavior.

## Verification Scope

JVM coverage verifies clock-domain rejection, bounded expiry, out-of-order PTS matching, supplied
render-time use, and deterministic Audio/Input sampling. Native coverage verifies the pre-bound
ClockSync counter/gauge/histogram source. Full Gradle, ABI, native Debug/Release, and adb results
are recorded only after final verification. Android render timing, Oboe estimate, and two-device
ClockSync accuracy remain hardware-runtime validation work.
