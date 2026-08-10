# RFC-003A - Android Low-Latency Audio Capture Foundation

Project: Warpnect

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

PCM Shared Ring Version: 1

Status: Implemented

## Purpose

RFC-003A begins Phase 3 by adding Android PCM audio capture foundations for two independent sources:

```text
SystemAudio
MicrophoneAudio
```

The RFC captures uncompressed signed PCM 16-bit audio only. It does not add an audio encoder, SCL audio payload version, UDP audio transport, audio playback, or A/V synchronization.

## System Audio Architecture

The intended privileged system-audio path is:

```text
Android game/media playback
        |
privileged AudioPolicy loopback + render
        |
AudioRecord
        |
bounded SharedMemory PCM ring
        |
borrowed slot payload ByteBuffer
        |
PcmAudioSink
```

The privileged backend lives under `io.warpnect.platform.audio.capture.privileged` and reuses the Shizuku/Sui UserService pattern established for privileged display capture. Hidden/SystemApi interaction is isolated in `ReflectivePrivilegedAudioPolicyCaptureApi`. Reflection is cold-path setup/teardown only.

The backend attempts AudioPolicy `LOOP_BACK | RENDER` behavior with usage rules for `USAGE_GAME`, `USAGE_MEDIA`, and `USAGE_UNKNOWN`. Warpnect does not intentionally redirect local playback away from the source device and does not use MediaProjection or `AudioPlaybackCaptureConfiguration` as a fallback.

Binder is setup/control only. It transfers the SharedMemory handle, notification FD, ACK FD, format metadata, and typed errors. PCM chunk payloads never travel through AIDL/Binder.

## Microphone Architecture

The microphone path runs in the ordinary app process:

```text
Android microphone
        |
AudioRecord
        |
preallocated direct ByteBuffer
        |
borrowed PCM chunk
        |
PcmAudioSink
```

`AndroidMicrophoneAudioCaptureController` owns one source and one lifecycle. It prefers `MediaRecorder.AudioSource.UNPROCESSED` when Android reports support, otherwise it uses `VOICE_RECOGNITION`. RFC-003A does not automatically enable acoustic echo cancellation, noise suppression, or automatic gain control.

Oboe/AAudio are deferred. RFC-003H must benchmark AudioRecord microphone latency against Oboe/AAudio on real devices before replacing the platform backend.

## PCM Contract

`PcmAudioSink` receives:

- format changes;
- borrowed PCM chunks;
- typed capture errors.

Chunk buffers are valid only for the synchronous `onPcmChunk` callback. The sink must not retain them.

Initial format:

```text
signed PCM 16-bit
native Android byte order
interleaved channels
```

No application resampling or channel conversion is performed.

Default source preferences:

| Source | Channels | Preferred rate | Encoding |
| --- | ---: | ---: | --- |
| SystemAudio | 2 | device/default, typically 48000 Hz | PCM16 |
| MicrophoneAudio | 1 | device/default, typically 48000 Hz | PCM16 |

The actual configured sample rate and channel count are reported.

## Chunking

Capture uses an explicit `targetChunkDurationUs`, default `5000 us`. Valid bounds are `2500 us..20000 us`.

`targetFramesPerChunk` is derived from actual sample rate with overflow-safe integer arithmetic. This is a capture granularity target, not an SCL packet duration and not a protocol rule.

## PCM Shared Ring

Internal Android-only version:

```text
PCM Shared Ring Version = 1
```

The shared ring has a fixed setup-time size:

```text
global header
+ slotCount * (slot metadata + PCM payload capacity)
```

Each slot contains fixed metadata:

- state;
- generation;
- sequence;
- valid byte count;
- frame count;
- first frame position;
- capture time in monotonic nanoseconds;
- timestamp quality;
- flags;
- publish time.

Slot lifecycle:

```text
FREE -> WRITING -> FILLED -> READING -> FREE
```

The baseline slot count is 8. This is safety capacity for cross-process scheduling jitter, not an intentional `8 * 5 ms` playback buffer. The app-side drain consumes slots immediately and calls the sink synchronously.

If the ring is full, the privileged producer records an overrun, drains AudioRecord into a preallocated discard buffer, and continues. Fresh audio is preferred over growing latency.

## Notification And ACK

The privileged service creates fixed FD-based notification and acknowledgement channels at setup.

Notification and ACK records are tiny fixed-size messages containing:

```text
slot index
slot generation
```

PCM remains in SharedMemory. There is no AIDL callback per chunk and no 1 ms shared-memory polling loop.

## Timestamp Contract

All capture timing uses a monotonic clock compatible with `System.nanoTime()`.

Where possible, capture uses:

```text
AudioRecord.getTimestamp(AudioTimestamp, TIMEBASE_MONOTONIC)
```

Each chunk reports:

- `firstFramePosition`;
- `captureTimeNs` for the first PCM frame;
- `AudioTimestampQuality`.

If a framework timestamp is not yet available, the fallback estimate is:

```text
read completion monotonic time - chunk duration
```

The fallback is explicitly marked `EstimatedFromReadCompletion`. Restart resets frame counters and timestamp anchors.

## Lifecycle

Controllers expose:

```text
Stopped
Preparing
Prepared
Starting
Running
Stopping
Error
Closed
```

Prepare is transactional: partial resources are released on failure. Stop prevents further PCM callbacks, stops AudioRecord, terminates capture/drain threads, releases AudioPolicy, unmaps SharedMemory, closes FDs, and clears timestamp state.

## Threading

RFC-003A adds:

- `WarpnectSystemAudioCapture`: privileged process AudioRecord read and slot publish.
- `WarpnectSystemAudioDrain`: ordinary app process notification wait, sink callback, ACK.
- `WarpnectMicrophoneCapture`: ordinary app process AudioRecord read and sink callback.

No audio capture, PCM sink callback, or SharedMemory drain runs on the UI thread. No per-chunk coroutine/task is launched.

## Telemetry

Snapshots report:

- sample rate, channel count, bytes per frame, chunk frames;
- actual AudioRecord buffer frames;
- chunks, frames, and bytes captured;
- timestamp successes/fallbacks;
- sink failures;
- ring capacity, occupancy, high-water mark, overruns;
- dropped chunks/frames;
- last frame position and capture time;
- last typed error.

Telemetry is local only and does not change SCL telemetry wire formats.

## Tests

JVM tests cover:

- chunk-frame and byte calculation;
- 44.1 kHz and 48 kHz timestamp arithmetic;
- AudioRecord timestamp anchor and fallback behavior;
- restart/reset timestamp state;
- controller lifecycle, duplicate start, sink failure, and counters;
- shared ring sizing, slot lifecycle, generation, wrap-safe reuse, full-ring overrun, and fixed signal records.

Android instrumentation includes a non-privileged SharedMemory ring lifecycle test. Hardware system-audio and microphone capture tests are device-gated.

## Device Status

When no connected Android device/emulator is present:

```text
Privileged system audio: NOT RUN
Microphone hardware capture: NOT RUN
```

No Shizuku/Sui AudioPolicy capture success, local playback preservation, microphone hardware runtime, or signal quality result is claimed without a real device.

## Deferred Work

- RFC-003B - Low-Latency Audio Encoder Pipeline.
- RFC-003C - SCL Audio Payload and Transport Integration.
- RFC-003D - Android Audio Decoder Pipeline.
- RFC-003E - Low-Latency Audio Playback Pipeline.
- RFC-003F - End-to-End Audio Streaming.
- RFC-003G - Audio/Video Synchronization.
- RFC-003H - Audio Latency, Recovery and Performance Tuning.
