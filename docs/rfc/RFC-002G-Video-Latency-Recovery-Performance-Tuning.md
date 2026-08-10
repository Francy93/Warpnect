# RFC-002G - Video Latency, Recovery and Performance Tuning

Project: Warpnect

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

Video Payload Version: 1

Video Resync Control Version: 1

Status: Implemented

## Purpose

RFC-002G closes Phase 2 by adding bounded performance telemetry, freshness-bounded recovery, runtime video resynchronization, clock-sync integration, and repeatable native Phase 2 benchmarks to the RFC-002F end-to-end video pipeline.

The implementation preserves the existing architecture:

```text
Transmitter:
capture -> hardware AVC encoder -> SCL video sender -> UDP

Receiver:
UDP -> FEC/NACK/reassembly -> ordered AU -> hardware AVC decoder -> SurfaceView
```

No raw-frame CPU path, software codec fallback, full-AU Kotlin payload copy, decoder payload queue, decoded-frame queue, playback jitter buffer, AVC parser, Video Payload change, PacketHeader change, or new PayloadType is introduced.

## Measurement Baseline

The native Phase 2 benchmark target is:

```text
scl_phase2_video_benchmarks
```

It records packetization time, receiver runtime reassembly/fill cost, recovery-deadline behavior, resync control encode/decode cost, configured recovery values, and allocation/policy audit rows. CSV output is written under `native/build-release`, which is build output and is not source-controlled.

The local Release baseline on this workstation was:

| Scenario | Iterations | p50 | p95 | p99 | Notes |
| --- | ---: | ---: | ---: | ---: | --- |
| packetize 512 B AU | 1000 | 0.3 us | 0.3 us | 0.4 us | segmented AU -> SCL datagrams |
| packetize 4096 B AU | 1000 | 0.8 us | 0.9 us | 1.0 us | segmented AU -> SCL datagrams |
| packetize 65536 B AU | 1000 | 9.5 us | 14.3 us | 17.1 us | segmented AU -> SCL datagrams |
| receiver reassemble/fill 4096 B AU | 100 | 2115.0 us | 4224.9 us | 6238.0 us | production receiver runtime path |
| receiver reassemble/fill 65536 B AU | 100 | 2001.3 us | 3830.2 us | 4989.5 us | production receiver runtime path |
| stale recovery expiry, 32 KiB | 50 | 2730.6 us | 4262.1 us | 13543.6 us | explicit 50 ms freshness deadline state |
| VideoResyncRequest encode/decode | 1000 | 0.1 us | 0.1 us | 0.1 us | 8-byte SessionControl payload |

These are host-native measurements, not Android device latency guarantees. Android loopback, privileged capture, SurfaceView physical presentation, and two-device LAN figures remain device-pending when no connected device/emulator is available.

## Tuning Configuration

`VideoPerformanceConfig` groups Phase 2 tuning values in one validated configuration:

- `maxFrameRecoveryAgeUs`
- `resyncRequestCooldownUs`
- `startupConfigRequestDelayUs`
- `keyFrameWaitRequestDelayUs`
- `clockSyncIntervalUs`
- `diagnosticSampleCapacity`
- `bitrate`

The provided `UltraLowLatency` preset is only a named group of inspectable values. It does not hide behavior.

The provisional defaults are:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `maxFrameRecoveryAgeUs` | 50000 us | maximum recovery age for incomplete video frames |
| `resyncRequestCooldownUs` | 250000 us | receiver request storm guard |
| `startupConfigRequestDelayUs` | 250000 us | delay before requesting missing StreamConfig |
| `keyFrameWaitRequestDelayUs` | 250000 us | delay before requesting keyframe while gated |
| `clockSyncIntervalUs` | 1000000 us | low-frequency RFC-001F control exchange |
| `diagnosticSampleCapacity` | 256 | bounded diagnostic capacity |

The recovery age origin is the first observed fragment for a logical video message. Once `now_us - first_fragment_us >= maxFrameRecoveryAgeUs`, the incomplete message is stale, its slot is released, stale-frame telemetry is incremented, and the receiver enters keyframe-gated discontinuity recovery where appropriate.

## VideoResyncRequest V1

RFC-002G adds one SessionControl subtype:

```cpp
SessionControlType::VideoResyncRequest == 5
kVideoResyncControlVersion == 1
kVideoResyncRequestWireSize == 8
```

Wire format:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type = VideoResyncRequest` |
| 1 | 1 | `control_version = 1` |
| 2 | 1 | `reason` |
| 3 | 1 | reserved, zero |
| 4 | 4 | `receiver_config_generation` |

All multi-byte fields use big-endian byte order.

Reasons:

| Value | Reason |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `NeedConfiguration` |
| 2 | `NeedKeyFrame` |
| 3 | `Discontinuity` |
| 4 | `DecoderRestart` |
| 5 | `SurfaceRecreated` |
| 6 | `ReceiverOverflow` |

Unsupported versions, unknown reason values, nonzero reserved bytes, wrong subtype, and wrong payload size are rejected.

`receiver_config_generation == 0` means the receiver has no usable configuration. A nonzero value reports the receiver's currently known generation and is not proof that it matches the sender's latest generation.

## Resync Lifecycle

The receiver sends a rate-limited VideoResyncRequest when:

- it remains waiting for StreamConfig past the startup delay;
- it remains waiting for a keyframe past the keyframe wait delay;
- unrecoverable loss causes a discontinuity;
- decoder or Surface recreation requires a fresh random-access point.

The sender response is:

1. Decode and validate the request.
2. Suppress duplicates inside the configured cooldown.
3. Resend the current StreamConfig if one is cached.
4. Surface a keyframe-request event to Kotlin session orchestration.
5. Use the existing RFC-002B hardware encoder keyframe mechanism.

No encoded media access units are retained for resync. There is no Version 1 ACK for VideoResyncRequest.

## Clock Sync

The live video runtime composes the existing RFC-001F ClockSync Request/Response payloads. The receiver may send low-frequency requests over SessionControl, and the sender control path answers them with the current monotonic time. Receiver snapshots expose:

- clock-sync request/response counters;
- latest and best RTT;
- clock quality state.

Clock sync remains bounded and does not create another clock wire protocol. Remote media PTS is still not interpreted as a receiver-local render deadline.

## Recovery Timing

RFC-002G keeps a deterministic fixed recovery policy for tests and debugging. The Phase 2 defaults are:

```text
reorder_delay_us = 2000
renack_interval_us = 8000
max_nack_attempts = 3
maxFrameRecoveryAgeUs = 50000
```

Repeated NACKs cannot continue beyond the freshness deadline or attempt limit. Once a frame is stale, receiver resources are released and recovery moves toward the next keyframe.

Adaptive recovery timing was not enabled in production because the fixed bounded policy is measurable and deterministic, and RFC-002G should not add adaptive behavior without broader device/network evidence.

## FEC, NACK, Bitrate and Pacing

Existing RFC-001E FEC and RFC-001D NACK remain the recovery mechanisms. RFC-002G does not change FEC, NACK, Video Payload V1, or PacketHeader wire formats.

Fixed FEC profiles remain configured explicitly by the caller. Adaptive FEC was deliberately not enabled because variable profile switching was not justified by the current bounded receiver architecture and host-only measurements.

Sender pacing remains disabled. The benchmark evidence did not justify adding a bounded pacing queue to the interactive LAN baseline, and such a queue would add sender delay that must pay for itself.

`LossReactiveBitrateController` is available as optional pure policy. It is bounded, rate-limited, hysteresis-based, and constrained by explicit min/initial/max bitrate values. The default remains fixed bitrate. This controller is not a complete congestion-control algorithm and does not claim Internet fairness.

## Telemetry

Snapshots now expose bounded freshness/recovery and queue occupancy fields, including:

- stale frames released;
- resync requests sent/received/suppressed;
- clock-sync request/response counters;
- latest/best RTT and clock quality;
- reassembly and ready-slot high-water marks;
- reassembly latency;
- ready-wait latency.

Telemetry is metadata and counters only. No complete encoded media payload crosses Kotlin for diagnostics, and telemetry storage is bounded.

## Tests

Coverage added or extended:

- VideoResyncRequest golden wire vector and malformed payloads.
- Receiver resync request emission and sender resync handling.
- StreamConfig resend and keyframe-request telemetry.
- Freshness deadline expiry and stale slot release.
- Production receiver runtime FEC/NACK/reassembly regressions remain green.
- Performance configuration validation.
- Resync cooldown/session orchestration behavior.
- Optional loss-reactive bitrate policy bounds, hysteresis, rate limiting, decrease, and gradual increase.
- Phase 2 native benchmark smoke and standard modes.

Existing RFC-002F tests continue to cover zero loss, reordering, FEC recovery, NACK fallback, FEC plus NACK, discontinuity, frame ordering, frame wrap, generation change, capacity behavior, and direct decoder input fill.

## Device Status

No Android device or emulator was connected during RFC-002G verification. Instrumentation execution was therefore:

```text
NOT RUN - no connected device/emulator
```

Device-pending validation remains:

- privileged real capture;
- hardware encoder runtime;
- hardware decoder runtime;
- SurfaceView physical presentation;
- Android loopback latency baseline;
- two-device LAN baseline.

Host-native performance results do not replace device measurements.

## External End-To-End Measurement

Software timestamps are not proof of photons-on-glass latency. A future manual optical measurement should place the source and receiver displays in one high-speed-camera frame, trigger a rapid visual transition on the source, and count camera frames until the receiver display changes. Cross-device software timestamps may be used only when clock-sync quality is sufficient and the reported metric clearly states its clock-domain assumptions.

## Known Limitations

- No automatic MTU discovery.
- No production reconnect loop.
- No discovery/session negotiation.
- No authentication or encryption.
- No complete congestion controller.
- No adaptive FEC enabled by default.
- No datagram pacing enabled by default.
- No resolution or FPS adaptation.
- No optical end-to-end latency measurement without external equipment.

## Phase Status

Phase 2 - Video Pipeline is complete.

Next phase:

```text
Phase 3 - Audio Pipeline
```
