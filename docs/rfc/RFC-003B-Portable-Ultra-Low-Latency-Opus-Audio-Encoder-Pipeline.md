# RFC-003B - Portable Ultra-Low-Latency Opus Audio Encoder Pipeline

Project: Warpnect

Status: Implemented.

Architecture Version: 1.0. SCL Protocol Version: 1. Native Bridge ABI Version: 1. PCM Shared Ring Version: 1. Video Payload Version: 1. Video Resync Control Version: 1. SCL Audio Payload Version: not defined.

## Purpose

RFC-003B adds Warpnect's first encoded-audio primitive:

```text
PCM16 borrowed direct buffer
        |
JNI direct-buffer bridge
        |
portable libopus encoder
        |
borrowed raw Opus packet
        |
EncodedAudioSink
```

It does not add SCL audio transport, UDP audio, an audio decoder, remote playback, an Opus container, resampling, channel conversion, or A/V synchronization.

## Dependency

Warpnect vendors the official upstream libopus 1.6.1 source distribution under `third_party/opus-1.6.1`.

The downloaded source archive provenance used for integration was:

```text
https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz
SHA-256: 6FFCB593207BE92584DF15B32466ED64BBEC99109F007C82205F0194572411A1
```

The upstream `COPYING` file is preserved. Warpnect does not link against Android private platform Opus libraries, hidden OEM codec internals, Android `MediaCodec` Opus, or AAC fallback.

## Native Module Boundary

The portable encoder is implemented as `warpnect::audio::OpusAudioEncoder`. It is deliberately outside `warpnect::scl`.

The Android shared library still packages the code in `scl_core` for app loading, but the codec module is a separate responsibility from SCL packet, transport, recovery, telemetry, and wire-format code.

Host tests and Android JNI link the same portable encoder source and the same pinned libopus target.

## Codec Configuration

RFC-003B implements Opus only.

Baseline configuration:

- Opus application: `OPUS_APPLICATION_RESTRICTED_LOWDELAY`.
- Default frame duration: `5 ms`.
- Supported frame durations: `2.5 ms`, `5 ms`, `10 ms`, `20 ms`.
- Supported sample rates: `8000`, `12000`, `16000`, `24000`, `48000 Hz`.
- Default/preferred sample rate: `48000 Hz`.
- Channel counts: mono and stereo only.
- SystemAudio signal hint: `OPUS_SIGNAL_MUSIC`.
- MicrophoneAudio signal hint: `OPUS_SIGNAL_VOICE`.
- DTX: disabled.
- Opus in-band FEC: disabled.
- Packet-loss expectation: `0`.
- Baseline bitrate mode: constant bitrate.
- Provisional bitrates: `128000 bps` for system stereo, `64000 bps` for microphone mono.
- Complexity default: `5`, validated in libopus' `0..10` range.

`44100 Hz` input is rejected as `UnsupportedSampleRate`; Warpnect does not resample in RFC-003B.

## Frame Assembly

RFC-003A capture chunks and Opus frames are separate boundaries. The encoder therefore owns a bounded `AudioFrameAssembler` concept inside `OpusAudioEncoder`.

The assembler can retain at most one incomplete Opus frame. It never becomes a PCM queue.

When the assembler is empty and the borrowed PCM range contains a complete aligned Opus frame, the encoder calls `opus_encode()` directly on the borrowed PCM pointer.

When capture chunks are smaller than an Opus frame, only the missing PCM is copied into the fixed accumulator. If a larger PCM callback contains multiple complete frames, Kotlin synchronously loops native submission over the borrowed range and each packet is delivered before output scratch reuse.

On PCM discontinuity, detected from `firstFramePosition` versus `expectedNextFramePosition`, the partial accumulator is discarded and the discontinuity is reported. Warpnect does not concatenate non-contiguous samples and does not synthesize filler PCM.

## Timestamp Contract

Every encoded Opus packet carries metadata for the first PCM sample encoded in that packet:

- `firstFramePosition`.
- `captureTimeNs`.
- `timestampQuality`.
- local `encodedFrameIndex`.

For frames assembled across multiple PCM callbacks, the timestamp remains the first stored sample's timestamp. For multiple frames from one callback, later frame timestamps are derived from the frame offset and sample rate using integer arithmetic.

At prepare time, libopus lookahead is queried and exposed as `lookaheadSamples`. RFC-003B records lookahead for future A/V synchronization but does not compensate or drop samples.

## JNI Boundary

All Kotlin native declarations remain in `NativeBridge.kt`.

The hot path requires a direct PCM `ByteBuffer`. JNI uses:

```text
GetDirectBufferAddress
GetDirectBufferCapacity
```

No PCM hot path uses Java byte arrays. Native code borrows the PCM pointer only for the synchronous submit call and never retains it.

The native encoder owns one preallocated encoded packet scratch buffer. Kotlin receives a persistent direct output buffer view and `EncodedAudioSink` borrows that view only during `onEncodedFrame()`.

JNI returns compact primitive metadata arrays for submit/stop/snapshot operations. It does not implement PCM framing, Opus policy, timestamp continuity, packetization, or SCL transport.

## Lifecycle

`AudioEncoderController` exposes:

```text
queryCapabilities
prepare
start
updateBitrate
stop
snapshot
close
```

Prepare validates the request, creates libopus state, configures RestrictedLowDelay, allocates the frame accumulator and output scratch, and publishes the output format. Start resets codec state, partial-frame state, timestamps, counters, and encoded frame index. Stop discards an incomplete tail frame and does not zero-pad or emit synthetic audio. Close releases native state and is idempotent.

System and microphone audio use independent encoder instances. RFC-003B does not multiplex sources through one Opus state.

## Threading

Encoding is synchronous on the caller's RFC-003A PCM sink context:

```text
WarpnectSystemAudioDrain -> Opus encoder -> EncodedAudioSink
WarpnectMicrophoneCapture -> Opus encoder -> EncodedAudioSink
```

RFC-003B introduces no encoder worker thread, no PCM work queue, and no encoded-packet queue.

## Telemetry

Snapshots expose:

- codec/source/sample rate/channel count/frame duration/samples per frame.
- bitrate, bitrate mode, complexity, and lookahead.
- PCM chunks/frames received.
- encoded frames/bytes.
- direct fast-path frames.
- assembler frames and partial samples.
- discontinuities, skipped PCM, and tail frames dropped.
- last frame positions, capture timestamp, native Opus error, and typed error.

All telemetry is local. No SCL telemetry wire format changes were made.

## Tests

Native tests cover:

- 48 kHz mono/stereo 5 ms encoding.
- 2.5/5/10/20 ms sample calculations.
- 44.1 kHz rejection.
- mono/stereo support and unsupported channel rejection.
- bitrate controls.
- direct aligned frames.
- partial frame assembly.
- multiple frames in one input.
- PCM discontinuity.
- tail discard and restart.
- timestamp/frame-position preservation.
- test-only libopus decoding of packets.

JVM tests cover validation and controller behavior through a fake native backend: prepare/start, PCM forwarding, NeedMoreInput, multiple frames, discontinuity, direct-buffer rejection, bitrate update, sink failure, stop, restart, and close-related state.

Instrumentation includes a JNI smoke test that does not require audio hardware: direct PCM is accepted, non-direct PCM is rejected, and a borrowed direct Opus output packet is produced when a device/emulator is attached.

## Benchmark

The host Release benchmark executable is:

```text
opus_audio_encoder_benchmarks
```

Measured on the current Windows x86_64 host with MSVC 1939, Release build:

```text
48 kHz mono, 5 ms, 64 kbps:
  p50 66.4 us, p95 107.8 us, p99 137.2 us

48 kHz stereo, 5 ms, 128 kbps:
  p50 103.0 us, p95 151.9 us, p99 301.0 us
```

These are host-only measurements. They are not Android device latency claims.

## Device Status

No connected Android device/emulator was available during implementation verification.

```text
Microphone -> Opus:
NOT RUN - no connected device

SystemAudio -> Opus:
NOT RUN - no connected privileged device
```

## Architecture Audit

Confirmed:

- No production Android `MediaCodec` audio encoder path.
- No AAC fallback.
- No resampler.
- No channel conversion.
- No SCL audio payload version.
- No UDP audio transport.
- No audio decoder.
- No audio playback.
- No A/V synchronization.
- No PCM queue.
- No encoder worker queue.
- No encoded packet queue.
- No per-frame PCM `ByteArray`.
- No per-frame encoded packet `ByteArray`.
- No Opus container, Ogg, WebM, MP4, or `OpusHead` wire serialization.
- No SCL namespace pollution with codec logic.

## Deferred Work

```text
RFC-003C - SCL Audio Payload & Transport Integration
RFC-003D - Audio Decoder Pipeline
RFC-003E - Low-Latency Audio Playback Pipeline
RFC-003F - End-to-End Audio Streaming
RFC-003G - Audio/Video Synchronization
RFC-003H - Audio Latency, Recovery & Performance Tuning
```
