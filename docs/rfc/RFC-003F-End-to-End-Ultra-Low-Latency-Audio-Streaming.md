# RFC-003F - End-to-End Ultra-Low-Latency Audio Streaming

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1.

RFC-003F composes the completed Phase 3 audio foundations into the first functional end-to-end audio streaming path.

```text
TX:
Audio capture
        -> Opus RestrictedLowDelay encoder
        -> SCL Audio Payload V1 sender
        -> UDP

RX:
UDP
        -> SCL Audio receiver runtime
        -> Audio Payload V1 parser/reassembly
        -> Opus decoder / explicit PLC
        -> Oboe playback ring
        -> Android audio output
```

## Transmitter

Each transmitter session owns one logical audio stream: `SystemAudio` or `MicrophoneAudio`.

Start order is deterministic:

```text
1. Open SCL audio transport.
2. Prepare Opus encoder with the transport sink.
3. Start Opus encoder.
4. Prepare PCM capture with the encoder sink.
5. Start capture.
```

Rollback closes every acquired downstream resource if a later step fails. Stop order is capture, encoder, then transport so borrowed PCM and encoded buffers never outlive their owners.

The hot path remains synchronous:

```text
capture callback
        -> Opus encode
        -> AudioFrame packetization
        -> non-blocking UDP send
```

`WouldBlock`, `UdpSendFailed`, and partial emission are counted as dropped encoded audio frames. They do not block the capture thread and do not create a retry queue.

## Receiver Runtime

The native `AudioReceiverRuntime` owns one non-blocking UDP socket for one payload type. It waits for readability on the `WarpnectAudioReceiver` context, parses SCL packet headers, filters the configured audio payload type and optional remote endpoint, uses RFC-001C reassembly for fragmented messages, parses Audio Payload V1, and publishes coarse events:

```text
StreamConfigReady
AudioFrameReady
TransportError
Stopped
```

The runtime does not decode Opus, play PCM, run Oboe, perform A/V sync, or implement network recovery policy.

Receive storage is bounded:

```text
reassembly slots: small fixed count
ready slots: small fixed count
logical audio payload capacity: fixed
```

Ready slots expose persistent direct-buffer storage for synchronous decoder input. Kotlin receives metadata and a slot index, not a per-frame encoded `ByteArray`. Slots are released in `finally` after decode so failures cannot leak ready capacity.

This storage is transport/reassembly ownership, not a network jitter buffer. Frames are consumed immediately once ready.

## Receiver Session

The receiver session waits for `StreamConfig` before preparing decode/playback. AudioFrames that arrive first are dropped and counted.

On the first valid StreamConfig:

```text
prepare decoder
start decoder
prepare playback
wait for first AudioFrame
```

After the first valid frame decodes, one decoded codec frame primes the RFC-003E playback ring and playback starts. RFC-003F does not require a multi-frame network prebuffer.

Configuration changes create a fresh decoder/playback pair. Old-generation PCM does not cross into the new playback stream.

## ImmediateFreshness Policy

RFC-003F has no timed network reorder window:

```text
network reorder wait = 0
```

The receiver uses `firstFramePosition` as the media timeline:

- Contiguous frames decode immediately.
- Duplicate and late frames are dropped.
- Small aligned gaps invoke explicit Opus PLC synchronously.
- Misaligned gaps trigger a freshness reset.
- Large gaps reset decoder/playback media state and resume from the current fresh frame.

The default `maxImmediatePlcFrames` is 2 codec frames. With 5 ms audio, that is at most 10 ms of immediate PLC generation. This is not a waiting delay.

`DiscontinuityBefore` remains source-capture metadata. It is propagated through decode/playback but does not itself imply network packet loss.

## Copy Boundaries

Transmitter:

```text
borrowed PCM/direct capture buffer
        -> Opus encoder
        -> borrowed Opus output
        -> fragment/datagram-sized SCL copy
```

Receiver:

```text
UDP/reassembly storage
        -> native ready slot direct view
        -> decoder borrowed input
        -> decoder PCM scratch
        -> one PCM ownership copy into playback ring
```

There is no per-frame encoded `ByteArray`, PCM `ByteArray`, second complete encoded staging copy, or second application PCM staging buffer.

## Threading

Active media contexts are:

```text
TX SystemAudio:
WarpnectSystemAudioCapture / WarpnectSystemAudioDrain
        -> Opus
        -> UDP

TX Microphone:
WarpnectMicrophoneCapture
        -> Opus
        -> UDP

RX:
WarpnectAudioReceiver
        -> SCL parse/reassembly
        -> decode/PLC
        -> playback-ring submit

Playback:
Oboe high-priority callback
```

RFC-003F adds no audio sender worker, encoder worker, decoder worker, playback worker queue, per-frame coroutine, or per-frame UI hop. The Oboe callback remains independent from UDP, SCL parsing, Opus decode, JNI, and Kotlin.

## Limitations

The receiver still needs a StreamConfig before frames can decode. Without session negotiation or an audio config request protocol, late join depends on receiver-first startup or explicit current-config resend by the caller.

RFC-003F does not implement A/V synchronization, adaptive jitter delay, timed reordering, audio NACK, SCL audio FEC, Opus in-band FEC, adaptive bitrate, congestion control, packet pacing, discovery, session negotiation, reconnect, resampling, time stretching, or stream mixing.

## Tests

Coverage added for RFC-003F includes:

- Native SCL audio receiver runtime UDP loopback.
- StreamConfig and AudioFrame event publication.
- Fragmented AudioFrame reassembly into one ready slot.
- Payload filtering and bounded ready/reassembly ownership.
- Transmitter start rollback and `WouldBlock` drop handling.
- Receiver config wait, first-frame prime, contiguous decode, small-gap PLC, late/duplicate drop, misaligned reset, large-gap reset, config change, playback-ring-full drop, and ready-slot release on decoder failure.

Device end-to-end tests are device-dependent. Without a connected Android device or emulator, Oboe runtime and microphone/system-audio full pipeline checks remain not run.

## Versioning

RFC-003F adds no wire change.

```text
Architecture Version: 1.0
SCL Protocol Version: 1
Native Bridge ABI Version: 1

PCM Shared Ring Version: 1
PCM Playback Ring Version: 1

Audio Payload Version: 1

Video Payload Version: 1
Video Resync Control Version: 1
```

PacketHeader, PayloadType, Audio Payload V1, Video Payload V1, VideoResyncRequest, NACK, FEC, and ClockSync wire layouts are unchanged.

## Next

RFC-003G owns Audio/Video Synchronization.

RFC-003H owns Audio Latency, Recovery, and Performance Tuning.
