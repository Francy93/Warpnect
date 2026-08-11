# RFC-003E - Android Ultra-Low-Latency Audio Playback Pipeline

Status: Complete.

## Summary

RFC-003E adds Warpnect's Android PCM playback foundation. It accepts borrowed PCM16 output from RFC-003D, copies it once into bounded native playback-owned storage, and lets an Oboe output callback consume that storage on the Android audio clock.

It does not implement continuous SCL audio receive, network jitter policy, automatic PLC/FEC/NACK, A/V synchronization, playback routing UI, or audio/video session orchestration.

## Dependency

RFC-003E vendors pinned upstream Oboe `1.10.0` under `third_party/oboe-1.10.0`. The vendored tree preserves the upstream Apache license files and is linked only into Android native builds.

Oboe types are isolated from `warpnect::scl` and from the portable Opus codec interfaces. The Android backend lives under `warpnect::audio::android`; the portable handoff ring remains testable without Android audio APIs.

## Playback Architecture

```text
borrowed RFC-003D PCM scratch
        |
        v
Kotlin DecodedPcmAudioSink
        |
        v
NativeBridge JNI
        |
        v
one copy into native SPSC playback ring
        |
        v
Oboe high-priority output callback
        |
        v
Android audio output
```

The one PCM copy is required because RFC-003D decoded PCM is borrowed only for the sink callback, while Oboe consumes output asynchronously according to the hardware clock. RFC-003E does not copy through a Kotlin `ByteArray`, `ShortArray`, Kotlin queue, or second application PCM buffer.

## Stream Configuration

The playback controller accepts PCM16 mono/stereo at Opus-native rates:

```text
8000
12000
16000
24000
48000 Hz
```

Frame durations are `2.5`, `5`, `10`, or `20 ms`; the baseline remains `5 ms`. The actual Oboe stream sample rate, channel count, format, performance mode, sharing mode, audio API, burst size, buffer size, hardware sample rate, and hardware channel count are recorded in snapshots.

Warpnect requests:

- `PerformanceMode::LowLatency`
- `SharingMode::Exclusive`
- `AudioFormat::I16`
- no Oboe sample-rate conversion
- no Oboe channel conversion
- no Oboe format conversion

The default sharing policy is `PreferExclusiveAllowShared`, so shared low-latency output is allowed when exclusive mode is unavailable. The actual sharing mode is always exposed.

## Playback Ring

The native `PcmPlaybackRing` is fixed-capacity single-producer/single-consumer storage. The baseline capacity is four codec frames and the startup threshold is one codec frame.

Capacity is scheduling safety, not target latency:

```text
capacity = maximum queued PCM storage
occupancy = actual queued PCM frames
latency follows occupancy, not allocated capacity
```

The ring stores fixed metadata with each slot:

- config generation
- first source frame position
- capture time in microseconds
- timestamp quality
- source discontinuity flag
- decoded frame kind
- valid frame count
- submission timestamp for bounded residence diagnostics

The consumer supports partial slot reads. If an Oboe callback asks for less than one codec frame, only `readFrameOffset` is retained. If it asks for more than one slot, the callback consumes the current tail and then later slots without concatenating into a temporary buffer.

## Buffering Audit

Intentional network jitter-buffer frames: `0`.

Intentional startup prefill: one codec frame by default.

Encoded input queued by RFC-003E: `0`.

Decoded PCM queued outside the playback ring: `0`.

The playback handoff ring exists only to bridge decoder-production timing and the Android hardware callback. It is not a network jitter buffer and does not intentionally wait for several decoded frames before playback.

## Copy Audit

Production copy path:

```text
RFC-003D PCM scratch
        |
borrowed direct buffer
        |
one native ring ownership copy
        |
Oboe output callback buffer
```

There is no per-frame PCM `ByteArray`, `ShortArray`, temporary Kotlin `ByteBuffer`, unbounded PCM queue, or second application PCM staging buffer.

## Oboe Callback

The Oboe data callback performs only bounded native work:

- consume ring samples
- copy PCM16 into Oboe's output buffer
- fill silence on underrun
- update atomic counters
- return `Continue`

It performs no JNI, Java/Kotlin callback, blocking lock, allocation, logging, sleep, network I/O, file I/O, timestamp query, codec decode, or playback scheduling policy.

## Buffer Tuning

After stream open, RFC-003E records:

- `framesPerBurst`
- `bufferCapacityFrames`
- requested buffer frames
- actual buffer frames returned by `setBufferSizeInFrames`
- requested buffer bursts

The baseline requested runtime buffer is `2 * framesPerBurst`. RFC-003E records XRun count where supported but does not automatically grow the buffer on XRun; RFC-003H owns tuning.

## Underrun And Overrun

If the callback needs more PCM than the ring contains, it writes all available PCM and fills the remainder with PCM16 silence. Counters expose underrun callbacks, underrun frames, and silence frames inserted.

If the producer submits PCM when the ring is full, submission fails immediately with `PlaybackRingFull`. RFC-003E does not block the producer, discard already queued PCM, allocate a new slot, or grow a backlog.

## Presentation Timing

`queryPresentationTimestamp()` performs a cold-path Oboe timestamp query outside the data callback. Snapshots expose receiver-local output frame position, monotonic presentation time, timestamp validity, and Oboe latency when available.

Sender capture time and receiver playback presentation time are separate domains. RFC-003E stores the ingredients for later RFC-003G work but performs no A/V mapping.

## Lifecycle

`NativeOboeAudioPlaybackController` owns one native playback handle:

```text
Stopped -> Preparing -> Prepared -> Running -> Prepared/Stopped -> Closed
```

`prepare()` validates configuration, allocates the ring, opens Oboe, queries actual stream properties, and applies the requested buffer size. `submitPcm()` may prime the ring while prepared. `start()` requires the configured threshold; the default is one codec frame. `stop()` stops output and clears ring ownership. `close()` is idempotent.

## Threading

Producer:

```text
future audio receive/decoder context
        -> DecodedPcmAudioSink
        -> NativeBridge
        -> native ring submit
```

Consumer:

```text
Oboe native data callback
        -> ring consume
        -> output buffer
```

RFC-003E introduces no playback worker queue, no per-frame coroutine, no UI-thread PCM work, and no JNI from the Oboe callback.

## Errors

Typed playback errors include invalid configuration, unsupported sample rate/channel/format, Oboe dependency/open/start/stop/disconnect failures, requested format/rate/channel mismatches, exclusive or low-latency mode unavailability, non-direct buffers, invalid ranges, invalid frame counts, config generation mismatch, ring full, not primed, presentation timestamp unavailable, lifecycle errors, and closed state.

## Snapshot And Telemetry

Snapshots expose bounded local diagnostics:

- requested and actual stream format properties
- Oboe performance mode, sharing mode, and API
- buffer burst/capacity/actual size
- ring capacity, occupancy, and high-water mark
- submitted, consumed, and rejected PCM frames
- underrun and silence counters
- XRun count
- normal, PLC, and discontinuity frame counts
- last source frame position and capture time
- presentation timestamp fields
- ring residence sample count, latest residence, and maximum residence
- last typed error

No telemetry wire format changes were made.

## Tests

Native host tests cover empty ring underrun/silence, single-slot writes, wrap, full-ring failure, reset/restart, partial slot consumption, callback sizes smaller/larger than codec frames, mono/stereo ordering, metadata progression, PLC/discontinuity counters, and capacity staying fixed.

JVM tests cover fake-backend controller prepare/start/stop/restart, one-frame priming, direct-buffer validation, invalid ranges, config mismatch, ring-full surfacing, presentation timestamp mapping, idempotent close, and `OboeDecodedPcmAudioSink` forwarding.

Android instrumentation compiles a direct PCM playback lifecycle smoke test and a short synthetic PCM start/stop test. Runtime execution requires a connected Android device or emulator.

## Device Status

No connected Android device or emulator was available during RFC-003E verification, so Oboe runtime instrumentation and encoder-to-decoder-to-playback device execution were not run.

## Limitations

- No continuous network audio receive runtime.
- No network jitter algorithm.
- No automatic NACK/FEC/PLC policy.
- No sample-rate conversion or channel mixing.
- No audio effects.
- No `AudioTrack`, `MediaPlayer`, or `SoundPool` production fallback.
- No audio route picker.
- No A/V synchronization.

## Versioning

RFC-003E makes no SCL wire-format change.

```text
Architecture Version: 1.0
SCL Protocol Version: 1
Native Bridge ABI Version: 1
PCM Shared Ring Version: 1
Audio Payload Version: 1
Video Payload Version: 1
Video Resync Control Version: 1
PCM Playback Ring Version: 1
```

`PCM Playback Ring Version` is an internal native data-structure version, not an SCL protocol version.

## Deferred Work

Next:

```text
RFC-003F - End-to-End Audio Streaming
```

Still deferred:

```text
RFC-003G - Audio/Video Synchronization
RFC-003H - Audio Latency, Recovery & Performance Tuning
```
