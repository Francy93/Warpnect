# RFC-003D - Portable Ultra-Low-Latency Opus Audio Decoder Pipeline

Status: Complete.

## Summary

RFC-003D adds Warpnect's portable Opus decoder foundation. It consumes one already-parsed, borrowed raw Opus packet at a time and synchronously produces one borrowed PCM16 frame for `DecodedPcmAudioSink`.

It does not implement continuous network receive orchestration, `AudioTrack` playback, playback buffering, jitter buffering, A/V synchronization, or automatic packet-loss policy.

## Architecture

```text
borrowed Opus DirectByteBuffer
        |
        v
NativeBridge JNI
        |
        v
portable warpnect::audio OpusAudioDecoder
        |
        v
libopus 1.6.1 opus_decode()
        |
        v
preallocated PCM16 scratch
        |
        v
borrowed DecodedPcmAudioSink
```

The decoder lives in `warpnect::audio`, not `warpnect::scl`. SCL remains responsible for packet protocol, transport, recovery, and timing metadata. The decoder receives typed codec metadata rather than parsing SCL `PacketHeader` or Audio Payload V1 bytes directly.

## Dependency

RFC-003D reuses the pinned vendored `libopus 1.6.1` dependency introduced by RFC-003B. No duplicate Opus dependency, Android platform codec, MediaCodec audio decoder, AAC fallback, or third-party source patch was added.

## Configuration

Decoder configuration comes from RFC-003C StreamConfig values:

- `codec = Opus`
- `source = SystemAudio | MicrophoneAudio`
- non-zero `configGeneration`
- sample rates `8000`, `12000`, `16000`, `24000`, or `48000`
- channel count `1` or `2`
- frame durations `2500`, `5000`, `10000`, or `20000` us
- `lookaheadSamples`

`44100` Hz is rejected. RFC-003D does not resample or remix channels.

## Normal Decode

One RFC-003C AudioFrame maps to one normal `opus_decode()` operation. Before decoding, native code validates the packet duration with libopus packet-duration helpers and requires it to match the configured `samplesPerFrame`.

Successful decode requires:

- valid direct encoded input range
- active config generation match
- non-empty encoded packet
- decoded samples per channel equal configured `samplesPerFrame`

Malformed packets, duration mismatches, generation mismatches, and libopus failures return typed errors.

## PCM Output

Output is signed PCM16, interleaved, at the configured sample rate and channel count. The native decoder owns one setup-allocated PCM scratch buffer sized to exactly one configured frame:

```text
samplesPerFrame * channelCount * sizeof(int16)
```

Kotlin receives a persistent direct buffer view of this native scratch. The sink borrows it only during `onPcmFrame()`. The sink must not retain it.

## Copy Audit

The production decode path is:

```text
borrowed encoded DirectByteBuffer
        |
        v
libopus decoder reads input synchronously
        |
        v
preallocated native PCM scratch
        |
        v
borrowed PCM DirectByteBuffer
```

There is no per-frame encoded `ByteArray`, PCM `ByteArray`, float PCM intermediate, `List<Short>`, second PCM staging buffer, encoded input queue, decoded PCM queue, or playback jitter buffer.

## PLC

RFC-003D exposes explicit caller-driven Opus packet-loss concealment:

```text
concealMissingFrame(metadata)
```

The call uses libopus PLC (`data = NULL`, `len = 0`) for exactly the configured frame duration. PLC output is marked `PacketLossConcealment`, increments local counters, and carries caller-supplied frame position and capture-time metadata. PLC sets `discontinuityBefore = true`.

The decoder does not inspect SCL sequence gaps, UDP loss, NACK, FEC, frame positions, or arrival order to decide PLC. RFC-003F/003H own that policy.

## In-Band FEC

No automatic Opus in-band FEC policy is implemented in RFC-003D. A future RFC may expose a separate caller-driven `decode_fec` path if measured useful, but RFC-003B currently disables Opus in-band FEC and RFC-003D does not invoke it.

## Timing Metadata

The decoder preserves:

- `firstFramePosition`
- `captureTimeUs`
- `timestampQuality`
- `discontinuityBefore`
- `lookaheadSamples`
- `configGeneration`

It does not substitute wall-clock time, compare audio with video timestamps, map cross-device clocks, or perform A/V synchronization.

## Lookahead

`lookaheadSamples` is exposed in `DecodedAudioFormat` and snapshots for later timeline alignment. RFC-003D does not blindly discard lookahead samples when a decoder is created because decoder creation is not necessarily the remote encoder stream start.

## Discontinuity

`DiscontinuityBefore` is source PCM timeline metadata. The decoder propagates it to `DecodedPcmAudioSink` and does not automatically reset libopus state because a source discontinuity does not prove the encoder state was reset.

## Lifecycle

`NativeOpusAudioDecoderController` owns one native decoder handle and one output direct buffer view.

```text
Stopped -> Preparing -> Prepared -> Running -> Prepared/Stopped -> Closed
```

`prepare()` validates config, creates the native decoder, allocates the output scratch, and publishes `DecodedAudioFormat`. `start()` resets runtime counters and codec state. `stop()` stops accepting packets. `close()` is idempotent and destroys the native handle.

## Threading

RFC-003D introduces no decoder worker thread. Decode runs synchronously on the caller's future receive context. It does not launch per-frame coroutines, hop to the UI thread, or create a PCM/output queue.

## Errors

Typed errors include unsupported codec/rate/channel/duration, invalid generation, reconfiguration required, non-direct buffer, invalid range, empty/too-large packet, malformed packet, unexpected duration, unexpected decoded size, decode/control failures, invalid PLC metadata, PLC failure, sink failure, lifecycle errors, and closed state.

Native libopus error codes are preserved in snapshots/results where useful.

## Telemetry

Snapshots expose bounded local counters:

- packets and encoded bytes submitted
- frames, PCM frames, and PCM bytes decoded
- PLC frames generated
- malformed packets
- duration mismatches
- decode failures
- sink failures
- last frame position, capture time, decoded sample count
- last native error and typed error

No telemetry wire format changes were made.

## Tests

Native tests cover encoder-to-decoder operation, mono/stereo, `2.5/5/10/20 ms` frame durations, supported sample rates, `44100` rejection, malformed packets, wrong-duration packets, generation mismatch, metadata preservation, discontinuity propagation, explicit PLC, no automatic PLC, restart/reset, and output scratch reuse by structure.

JVM tests cover fake-backend controller prepare/start/decode, config mismatch, non-direct input, invalid ranges, sink failure, PLC success/failure, stop/restart, and idempotent close.

Android instrumentation includes a no-audio-hardware JNI test where synthetic PCM is encoded by RFC-003B and immediately decoded by RFC-003D through borrowed direct buffers.

## Benchmark

`opus_audio_decoder_benchmarks` measures host codec CPU cost for `48 kHz mono 5 ms` and `48 kHz stereo 5 ms`. Input Opus packets are generated outside the timed decode loop. These numbers are not Android playback latency or end-to-end audio latency.

## Limitations

- No network audio receive runtime.
- No audio playback.
- No playback or jitter buffer.
- No A/V synchronization.
- No automatic PLC, NACK, FEC, retransmission, or in-band FEC policy.
- No resampling, channel remixing, gain, effects, or audio processing.

## Versioning

RFC-003D makes no wire-format changes.

```text
Architecture Version: 1.0
SCL Protocol Version: 1
Native Bridge ABI Version: 1
PCM Shared Ring Version: 1
Video Payload Version: 1
Video Resync Control Version: 1
Audio Payload Version: 1
```

## Deferred Work

Next:

```text
RFC-003E - Android Low-Latency Audio Playback Pipeline
```

Still deferred:

```text
RFC-003F - End-to-End Audio Streaming
RFC-003G - Audio/Video Synchronization
RFC-003H - Audio Latency, Recovery & Performance Tuning
```
