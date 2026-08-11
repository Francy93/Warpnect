# RFC-003H - Audio Latency, Recovery and Performance Tuning

Project: Warpnect

Status: Phase 3 implementation complete.

Architecture Version: 1.0 frozen. SCL Protocol Version: 1. Native Bridge ABI Version: 1. PCM Shared Ring Version: 1. PCM Playback Ring Version: 1. Audio Payload Version: 1. Video Payload Version: 1. Video Resync Control Version: 1.

## Purpose

RFC-003H completes Phase 3 by adding a reproducible performance profile, host-native Phase 3 audio benchmarks, deterministic recovery-policy measurements, and documentation of the selected production defaults.

The implementation does not replace RFC-003A through RFC-003G. It measures and configures the existing architecture:

```text
PCM capture
 -> RestrictedLowDelay Opus
 -> SCL Audio Payload V1
 -> bounded audio receive runtime
 -> Opus decode / explicit PLC
 -> bounded PCM playback ring
 -> Oboe
```

When video is active, A/V synchronization remains audio-master and metadata-only.

## Performance Configuration

`AudioPerformanceConfig` records the Phase 3 UltraLowLatency baseline in one validated view without duplicating every subsystem type. Its snapshot exposes units for:

- codec frame duration;
- capture chunk target;
- encoder bitrate, bitrate mode, and complexity;
- recovery policy, reorder wait, PLC limit, and recoverable audio age;
- audio reassembly/ready capacity;
- playback ring capacity, startup threshold, and requested Oboe bursts;
- A/V sample interval, timestamp calibration bounds, startup hold, and video schedule-ahead bound.

The default profile maps onto existing receiver-runtime, receiver-session, and A/V synchronization config objects.

## Baseline

Current production baseline:

```text
capture target chunk = 5 ms
Opus frame = 5 ms
Opus application = RestrictedLowDelay
network reorder wait = 0 us
recovery policy = ImmediateFreshness
maxImmediatePlcFrames = 2
maxRecoverableAudioAgeUs = 10,000 us
audio reassembly slots = 4
audio ready slots = 4
max logical audio payload = 4096 bytes
playback ring = 4 codec frames
playback startup threshold = 1 codec frame
Oboe requested buffer = 2 bursts
A/V sync sample interval = 20 ms
A/V startup derived hold = 15 ms with current 5 ms / 4-slot / 1-threshold defaults
video schedule ahead maximum = 20 ms
```

## Benchmarking

The host-native target `scl_phase3_audio_benchmarks` reports CSV rows for:

- codec frame-duration matrix: 2.5, 5, 10, and 20 ms;
- mono and stereo 48 kHz profiles;
- representative bitrate, CBR/CVBR, and complexity points;
- Opus encode, decode, and explicit PLC CPU distributions;
- AudioFrame packetization and receiver parse/ready processing;
- wire bytes, packet rate, header overhead, and fragmentation;
- deterministic ImmediateFreshness and TinyReorderWindow recovery scenarios;
- default NACK, SCL FEC, and Opus in-band FEC status.

CSV output is generated under `native/build-release/benchmarks/` and is not committed.

## Tuning Decisions

No production default was changed from host-only evidence.

5 ms Opus remains selected. The host benchmark confirms codec work is below the media interval, but 2.5 ms doubles packet rate and hot-path call frequency, while 10 ms and 20 ms directly add media duration. Device playback evidence is required before changing the default.

ImmediateFreshness remains selected:

```text
networkReorderWaitUs = 0
maxImmediatePlcFrames = 2
```

TinyReorderWindow is validated as an explicit non-default policy and measured synthetically at 500, 1000, 2500, and 5000 us. It can recover selected reordered frames, but it adds intentional receive wait and therefore stays experimental.

Audio NACK remains disabled. With zero reorder wait, retransmissions normally arrive after the receiver has already advanced by PLC or reset. Enabling it would require measured RTT and a deliberate playout hold.

SCL audio FEC remains disabled. Existing FEC semantics can protect datagrams without a wire change, but block/parity formation must recover before the audio freshness deadline. That remains device/network pending.

Opus in-band FEC remains disabled. RFC-003H does not change the production Opus application away from RestrictedLowDelay merely to force FEC behavior.

Playback remains fixed-buffer by default:

```text
playback ring capacity = 4 codec frames
startup threshold = 1 codec frame
requested Oboe buffer = 2 bursts
dynamic Oboe buffer tuning = deferred/device-pending
```

## Recovery Model

ImmediateFreshness behavior is still:

```text
contiguous frame -> decode immediately
small aligned gap -> generate bounded explicit PLC synchronously
late or duplicate frame -> drop
large gap -> freshness reset
```

Recovery is constrained by media freshness. A recovered packet that misses the useful playout deadline is not a benefit.

## A/V Synchronization

RFC-003H retains RFC-003G defaults:

```text
syncSampleIntervalUs = 20,000
maxVideoSyncScheduleAheadUs = 20,000
startup hold = bounded by existing playback-ring slack
manual offset = 0
```

No audio resampling, time stretching, decoded-video queue, new PCM queue, or new A/V wire protocol is introduced. Synchronization remains degraded/immediate when trustworthy timing is unavailable.

## Audits

No new media payload copies are introduced by RFC-003H.

The production copy path remains:

```text
TX:
capture PCM
 -> Opus
 -> datagram-sized transport copy

RX:
UDP/reassembly storage
 -> borrowed Opus decoder input
 -> native PCM scratch
 -> one playback ownership copy
 -> Oboe
```

No new media queue is introduced. Existing bounded storage remains:

- capture SharedMemory ring;
- encoder one-frame accumulator;
- SCL datagram scratch;
- audio receiver reassembly slots;
- audio ready slots;
- decoder PCM scratch;
- playback SPSC ring;
- bounded telemetry/statistics samples.

The Oboe callback remains real-time safe: no JNI, Kotlin/Java, network, codec decode, logging, allocation, sleep, blocking synchronization, or timestamp query.

## Wire Compatibility

RFC-003H adds no wire changes and no new payload type. These remain unchanged:

```text
PacketHeader
PayloadType
Audio Payload V1
Video Payload V1
VideoResyncRequest
NACK
FEC
ClockSync
```

## Device Limitations

Host-native benchmarks cannot establish Android hardware output latency, Bluetooth/acoustic latency, or physical speaker/display synchronization.

When no device is connected, these categories remain pending:

```text
Oboe runtime tuning
Microphone pipeline
Privileged SystemAudio
combined A/V
two-device LAN
```

## Phase Status

Phase 3 - Audio Pipeline is implementation-complete.

Next phase:

```text
Phase 4 - Reverse Input
```
