# RFC-003C - SCL Audio Payload and Transport Integration

Project: Warpnect  
Protocol/Core: State Coherence Layer - SCL  
Phase: 3 - Audio Pipeline  
Architecture Version: 1.0 - Frozen  
SCL Protocol Version: 1  
Native Bridge ABI Version: 1  
PCM Shared Ring Version: 1  
Video Payload Version: 1  
Video Resync Control Version: 1  
Audio Payload Version: 1

## Summary

RFC-003C connects RFC-003B raw Opus output to SCL transport without adding decoding, playback, audio session orchestration, A/V synchronization, pacing, congestion control, or audio recovery policy.

The production send path is:

```text
borrowed libopus output DirectByteBuffer
        |
        v
Kotlin EncodedAudioSink
        |
        v
NativeBridge JNI
        |
        v
Audio Payload V1 packetization
        |
        v
non-blocking UDP
```

One Opus packet maps to one logical SCL `AudioFrame`. Warpnect does not batch Opus packets to reduce packet overhead.

## Source Mapping

Audio uses existing SCL payload types:

```text
PayloadType::SystemAudio      = 2
PayloadType::MicrophoneAudio  = 3
```

Both carry Audio Payload Version 1. Payload type identifies the logical audio source, so source identity is not duplicated inside the audio payload.

SystemAudio and MicrophoneAudio sender instances maintain independent:

- SCL sequence-number domains;
- configuration generations;
- PCM frame-position timelines;
- local snapshots.

## Wire Contract

Audio Payload Version:

```text
1
```

Message types:

```text
Unknown      = 0
StreamConfig = 1
AudioFrame   = 2
```

Codec IDs:

```text
Unknown = 0
Opus    = 1
```

Every Audio Payload V1 message begins with an exact 8-byte common header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `audio_version` |
| 1 | 1 | `message_type` |
| 2 | 1 | `codec` |
| 3 | 1 | `flags` |
| 4 | 4 | `config_generation` |

All multi-byte fields are big-endian.

## StreamConfig

`StreamConfig` is exactly 20 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common audio header |
| 8 | 4 | `sample_rate_hz` |
| 12 | 1 | `channel_count` |
| 13 | 1 | `frame_duration_code` |
| 14 | 2 | reserved, zero |
| 16 | 4 | `lookahead_samples` |

Version 1 validates Opus-native rates only:

```text
8000, 12000, 16000, 24000, 48000 Hz
```

Version 1 channels:

```text
1 = mono
2 = stereo
```

Frame duration codes:

```text
Unknown = 0
Ms2_5   = 1
Ms5     = 2
Ms10    = 3
Ms20    = 4
```

The `lookahead_samples` value is copied from the RFC-003B encoder format. Bitrate, complexity, signal hint, DTX, and in-band FEC are not serialized because they are not decoder-critical StreamConfig fields.

## AudioFrame

`AudioFrame` has a fixed 16-byte prefix followed by one raw Opus packet:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common audio header |
| 8 | 8 | `first_frame_position` |
| 16 | ... | raw Opus packet |

Flags:

```text
bit 0     = DiscontinuityBefore
bits 1..2 = TimestampQuality
bits 3..7 = reserved, zero
```

Timestamp quality:

```text
00 = Unavailable
01 = AudioRecordTimestamp
10 = EstimatedFromReadCompletion
11 = Reserved
```

Reserved quality and reserved flags are rejected.

## Timestamp Contract

RFC-003B passes `captureTimeNs` for the first PCM sample in the encoded Opus packet.

RFC-003C serializes:

```text
PacketHeader.timestamp_us = floor(captureTimeNs / 1000)
```

The local nanosecond timestamp remains local metadata before transport. SCL Version 1 packet timestamps are microseconds. Negative capture timestamps are rejected before unsigned native conversion.

`first_frame_position` is preserved exactly as an unsigned 64-bit audio sample position per channel. It is independent from SCL packet sequence numbers and is not derived from network order.

## Discontinuity

`SclEncodedAudioSink` retains one local `pendingDiscontinuity` boolean when RFC-003B reports `onAudioDiscontinuity()`.

The next successfully submitted AudioFrame carries `DiscontinuityBefore`, then the pending flag is cleared. If submission fails, the pending flag remains so a retry/new frame can still mark the first post-gap packet.

No artificial PCM is generated to fill the gap.

## Config Generation

Each sender starts at generation 0. The first successfully emitted StreamConfig activates generation 1. Subsequent config changes increment the generation and wrap `UINT32_MAX -> 1`.

Frames before a successful StreamConfig return `AudioConfigRequired`. A failed config emission does not activate a new generation.

## Packetization And Copy Path

AudioFrame packetization is segmented:

```text
segment 0 = 16-byte AudioFrame prefix
segment 1 = borrowed raw Opus packet bytes
```

The native packetizer copies only each fragment-sized intersection into the SCL datagram scratch buffer. It does not allocate or build a complete `16 + Opus packet` staging buffer.

Expected hot path copy:

```text
libopus output scratch
        |
        | borrowed direct view through Kotlin/JNI
        v
fragment-sized SCL datagram copy
```

There is no full encoded packet copy in Kotlin and no per-frame encoded `ByteArray`.

## Fragmentation

Typical 5 ms Opus packets usually fit in one datagram:

```text
slice_index = 0
total_slices = 1
```

Version 1 does not require this. Oversized synthetic or future Opus packets use RFC-001C fragmentation unchanged. The same logical AudioFrame is fragmented; multiple Opus packets are not aggregated.

## Transport

`AudioTransportSender` is a caller-driven native sender with:

- one source payload type;
- one non-blocking UDP socket;
- one datagram scratch buffer;
- one config generation counter;
- one audio sequence-number domain;
- local counters/snapshot only.

`NativeSclAudioTransportController` owns the opaque native handle. `SclEncodedAudioSink` implements RFC-003B `EncodedAudioSink` and submits:

- `onOutputFormatChanged()` -> StreamConfig;
- `onEncodedFrame()` -> one AudioFrame;
- `onAudioDiscontinuity()` -> pending next-frame flag.

There is no sender worker thread, encoded-audio queue, retry queue, pacing queue, or JNI media-payload retention.

## Backpressure

UDP sends are non-blocking. `WouldBlock`, `UdpSendFailed`, and `PartialEmission` surface immediately. RFC-003C does not wait for writability or accumulate encoded audio to retry later.

## Receive Parsing

Portable receive-side parsing exists for complete reassembled logical payloads:

- `decode_audio_stream_config()`;
- `decode_audio_frame()`;
- common-header validation;
- timestamp-quality decoding;
- exact Opus payload view exposure.

The parser treats Opus bytes as opaque and does not inspect TOC, bandwidth, frame internals, or packet structure.

RFC-003C does not implement a continuous audio receiver runtime, decoder, jitter buffer, playout buffer, playback timing policy, or loss concealment.

## Telemetry

Local snapshots expose:

- config generation;
- source;
- sample rate, channels, frame duration, lookahead;
- frames submitted;
- fragmented frames;
- datagrams generated/sent;
- bytes sent;
- discontinuity frames;
- WouldBlock/send failure counts;
- last frame position;
- last capture timestamp in microseconds;
- last error.

No telemetry wire format changes were made.

## Tests

Native tests cover:

- common-header golden bytes;
- StreamConfig golden bytes;
- AudioFrame golden/round-trip;
- malformed payloads;
- timestamp-quality validation;
- SystemAudio/MicrophoneAudio payload identity;
- frame position preservation;
- one-datagram behavior;
- fragmentation/reassembly;
- no batching;
- WouldBlock/failure propagation;
- UDP loopback for both payload types;
- sequence wrap;
- config generation increments/wrap;
- Opus encoder output -> SCL packetizer byte exactness.

JVM tests cover:

- `SclEncodedAudioSink` format submission;
- frame-before-config rejection;
- direct-buffer forwarding without position/limit mutation;
- non-direct/range/timestamp validation;
- invalid format rejection;
- pending discontinuity semantics;
- transport error propagation.

Android instrumentation includes JNI direct-buffer audio transport smoke coverage. It requires a connected device/emulator to run.

## Device Status

No device-specific capture -> encoder -> transport result is claimed by this RFC unless instrumentation is actually run on a connected device.

## Static Audit

Confirmed by implementation shape:

- no audio decoder;
- no playback;
- no audio jitter buffer;
- no A/V sync;
- no audio NACK/FEC policy enabled by default;
- no pacing;
- no congestion control;
- no automatic bitrate adaptation;
- no Opus parsing in SCL;
- no SCL PacketHeader change;
- no new PayloadType;
- no complete encoded-packet Kotlin `ByteArray`;
- no encoded-audio sender queue;
- no audio transport worker thread.

## Deferred Work

- RFC-003D - Audio Decoder Pipeline.
- RFC-003E - Low-Latency Audio Playback Pipeline.
- RFC-003F - End-to-End Audio Streaming.
- RFC-003G - Audio/Video Synchronization.
- RFC-003H - Audio Latency, Recovery and Performance Tuning.
