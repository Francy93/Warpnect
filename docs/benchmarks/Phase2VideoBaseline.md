# Phase 2 Video Baseline

Status: RFC-002G baseline and tuning report.

## Environment

Measured on the local host native Release build:

| Field | Value |
| --- | --- |
| OS | Windows |
| Architecture | x86_64 |
| Compiler | MSVC 1939 |
| C++ standard | 202002 |
| Logical CPUs | 8 |
| Build type | Release |
| Benchmark executable | `scl_phase2_video_benchmarks` |

Commands:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build native/build-release --config Release
.\native\build-release\Release\scl_phase2_video_benchmarks.exe --smoke --output native\build-release\phase2-video-smoke.csv
.\native\build-release\Release\scl_phase2_video_benchmarks.exe --standard --output native\build-release\phase2-video-standard.csv
```

The CSV files are generated build artifacts and are not checked in.

## Configured Phase 2 Low-Latency Values

| Setting | Value | Notes |
| --- | ---: | --- |
| max frame recovery age | 50000 us | stale incomplete frames are abandoned |
| reorder delay | 2000 us | fixed deterministic baseline |
| re-NACK interval | 8000 us | fixed deterministic baseline |
| resync request cooldown | 250000 us | request storm guard |
| clock sync interval | 1000000 us | low-frequency SessionControl exchange |
| adaptive FEC | disabled | fixed profiles remain explicit |
| sender pacing | disabled | no pacing queue added |

## Standard Benchmark Results

| Category | Scenario | n | p50 us | p95 us | p99 us | Mean us |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| video_packetization | 512 B AU | 1000 | 0.3 | 0.3 | 0.4 | 0.2878 |
| video_packetization | 4096 B AU | 1000 | 0.8 | 0.9 | 1.0 | 0.8123 |
| video_packetization | 65536 B AU | 1000 | 9.5 | 14.3 | 17.1 | 12.6412 |
| video_receiver_runtime | reassemble/order/fill 4096 B AU | 100 | 2115.0 | 4224.9 | 6238.0 | 2432.54 |
| video_receiver_runtime | reassemble/order/fill 65536 B AU | 100 | 2001.3 | 3830.2 | 4989.5 | 2369.29 |
| video_recovery | stale deadline expiry, 32 KiB AU | 50 | 2730.6 | 4262.1 | 13543.6 | 2926.94 |
| video_control | VideoResyncRequest encode/decode | 1000 | 0.1 | 0.1 | 0.1 | 0.0813 |

## Before and After

| Area | Before RFC-002G | After RFC-002G | Tradeoff |
| --- | --- | --- | --- |
| stale incomplete video | timeout existed, but recovery age was not a named tuning contract | explicit `maxFrameRecoveryAgeUs` controls freshness and telemetry records stale releases | late perfect frames may be dropped to keep interactive freshness |
| resynchronization | receiver waited for natural keyframes and receiver-first startup was assumed | receiver can send VideoResyncRequest for config/keyframe/discontinuity/surface events | adds a small SessionControl subtype and rate-limiting policy |
| clock telemetry | RFC-001F primitives existed outside the active video runtime | video runtime can exchange ClockSync packets and expose RTT/quality | low-frequency control traffic |
| performance evidence | Phase 2 had functional tests but no dedicated video benchmark target | repeatable native benchmark target records packetization, receive, recovery, and control timings | host numbers are not Android device latency guarantees |
| adaptive FEC | not implemented | deliberately still not implemented | avoids variable-profile complexity until device data justifies it |
| pacing | not implemented | deliberately still disabled | avoids a datagram queue and added sender delay |

## Queue and Copy Audit

The receiver buffers only bounded transport/reassembly state:

- fixed reassembly slots owning fragment-reassembled payload bytes;
- bounded ready-slot indices and small metadata;
- bounded FEC/loss tracking structures;
- bounded telemetry counters and timing fields.

The decoder path still copies payload bytes exactly once after reassembly:

```text
native ready AU -> MediaCodec-owned direct input ByteBuffer
```

There is no complete encoded AU Kotlin `ByteArray`, decoder payload queue, decoded-frame queue, or playback jitter buffer.

## Measurement Limitations

Measured:

- host-native packetization, receiver-runtime, recovery-deadline, and resync-control costs;
- configured low-latency defaults;
- native tests and benchmarks on the local Windows workstation.

Configured:

- recovery timing defaults;
- resync cooldown/startup/keyframe request delays;
- fixed bitrate default;
- adaptive FEC and pacing disabled decisions.

Inferred:

- host benchmarks indicate the native control/packetization costs are small relative to Android codec and display work, but they do not prove device latency.

Device-pending:

- generic Android loopback latency;
- privileged capture-to-render latency;
- hardware encoder/decoder runtime behavior;
- SurfaceView physical presentation;
- two-device LAN timing and RTT.
