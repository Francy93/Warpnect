# Phase 3 Audio Baseline

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native ABI Version 1.

RFC-003H establishes a reproducible Phase 3 audio benchmark baseline. Host-native results measure CPU/runtime costs only; they do not establish Android hardware output latency, acoustic latency, or physical A/V synchronization.

## Baseline Configuration

| Setting | Value |
| --- | ---: |
| Capture target chunk | 5 ms |
| Opus application | RestrictedLowDelay |
| Opus codec frame | 5 ms |
| AudioFrame mapping | 1 Opus packet = 1 AudioFrame |
| Network reorder wait | 0 us |
| Production recovery policy | ImmediateFreshness |
| maxImmediatePlcFrames | 2 |
| maxRecoverableAudioAgeUs | 10,000 us |
| Audio reassembly slots | 4 |
| Audio ready slots | 4 |
| Max logical audio payload | 4,096 bytes |
| Playback ring capacity | 4 codec frames |
| Playback startup threshold | 1 codec frame |
| Oboe requested buffer | 2 bursts |
| A/V sync sample interval | 20 ms |
| A/V startup capacity-derived maximum | 15 ms at 5 ms framing |
| Video sync schedule-ahead maximum | 20 ms |

Capacity is not target latency. Playback and receive capacities are safety bounds; actual occupancy and residence are the latency measurements.

## Benchmark Target

The host benchmark target is:

```powershell
cmake -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-release --config Release --target scl_phase3_audio_benchmarks
.\native\build-release\Release\scl_phase3_audio_benchmarks.exe --standard --output native\build-release\benchmarks\phase3-audio-standard.csv
```

Generated CSV output belongs under the build directory and is not committed.

## Host Environment

Measured during RFC-003H verification:

| Field | Value |
| --- | --- |
| OS | Windows |
| Compiler | MSVC 1939 |
| Architecture | x86_64 |
| Logical CPUs | 8 |
| Codec | pinned libopus 1.6.1 |
| Build | Release |
| Iterations | 1,000 |

## Codec Frame Duration

Representative 48 kHz CBR profiles:

| Profile | Encode p50/p95/p99 us | Decode p50/p95/p99 us | PLC p50/p95/p99 us | Packets/s | Wire bytes/frame | Fragmented |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| mono 2.5 ms 64 kbps | 46.8 / 95.4 / 188.5 | 17.0 / 18.6 / 25.1 | 0.7 / 0.8 / 1.0 | 400 | 57 | no |
| mono 5 ms 64 kbps | 63.2 / 104.6 / 287.2 | 28.5 / 61.7 / 243.8 | 1.1 / 1.3 / 1.6 | 200 | 77 | no |
| mono 10 ms 64 kbps | 99.9 / 154.3 / 257.2 | 48.8 / 91.3 / 134.6 | 2.1 / 2.5 / 2.8 | 100 | 117 | no |
| mono 20 ms 64 kbps | 164.9 / 246.9 / 738.6 | 78.9 / 95.2 / 175.3 | 4.2 / 14.9 / 16.0 | 50 | 197 | no |
| stereo 2.5 ms 128 kbps | 74.0 / 129.7 / 739.1 | 27.9 / 57.2 / 102.0 | 1.2 / 1.5 / 1.7 | 400 | 77 | no |
| stereo 5 ms 128 kbps | 102.3 / 192.9 / 1379.5 | 48.9 / 84.8 / 114.0 | 2.1 / 7.7 / 8.0 | 200 | 117 | no |
| stereo 10 ms 128 kbps | 162.9 / 324.1 / 1294.0 | 103.1 / 171.4 / 282.2 | 4.2 / 4.9 / 15.7 | 100 | 197 | no |
| stereo 20 ms 128 kbps | 302.8 / 520.4 / 2459.2 | 171.0 / 339.9 / 2233.5 | 8.2 / 26.4 / 28.0 | 50 | 357 | no |

Host CPU results do not justify changing the production default. RFC-003H retains 5 ms Opus because 2.5 ms doubles packet/JNI/receiver wake frequency, while 10 ms and 20 ms add media duration to every packet.

## Transport Runtime

For the representative 48 kHz mono 5 ms 64 kbps CBR profile:

| Stage | p50 / p95 / p99 us |
| --- | ---: |
| AudioFrame packetization | 0.3 / 0.3 / 0.4 |
| Receiver datagram accept/parse | 0.3 / 0.7 / 0.8 |

These values are host-local runtime costs. Android scheduling, socket, JNI, and playback behavior remain device-specific.

## Recovery Comparison

Deterministic synthetic-time recovery rows:

| Scenario | Wait | Normal | PLC | Late | Recovered by wait | Large resets | Added wait |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| adjacent swap 1 ms | 0 us | 3 | 1 | 1 | 0 | 0 | 0 us |
| adjacent swap 1 ms | 500 us | 3 | 1 | 1 | 0 | 0 | 0 us |
| adjacent swap 1 ms | 1,000 us | 4 | 0 | 1 | 1 | 0 | 1,000 us |
| missing delayed 2.5 ms | 0 us | 3 | 1 | 1 | 0 | 0 | 0 us |
| missing delayed 2.5 ms | 2,500 us | 4 | 0 | 1 | 1 | 0 | 2,500 us |
| large burst | 0 us | 2 | 0 | 0 | 0 | 1 | 0 us |

Tiny reorder windows can avoid PLC in selected synthetic reorder cases, but they permanently add possible receive wait. With no device/network evidence, production remains ImmediateFreshness with networkReorderWaitUs = 0.

## Selected Defaults

Production defaults after RFC-003H:

- Opus frame duration remains 5 ms.
- RestrictedLowDelay remains the Opus application.
- CBR remains the baseline packet-size mode.
- ImmediateFreshness remains the production recovery policy.
- networkReorderWaitUs remains 0.
- maxImmediatePlcFrames remains 2.
- Audio NACK remains disabled.
- SCL audio FEC remains disabled.
- Opus in-band FEC remains disabled and is not forced by changing codec application mode.
- Playback startup threshold remains 1 codec frame.
- Playback ring capacity remains 4 codec frames.
- Oboe requested buffer remains 2 bursts; dynamic buffer tuning remains device-pending.
- A/V sync sample interval remains 20 ms.
- Video schedule-ahead maximum remains 20 ms.

## Device Status

No Android hardware measurements are encoded in this document unless a connected device/emulator run is reported by verification.

When no device is connected:

```text
Oboe runtime tuning: NOT RUN
Microphone pipeline: NOT RUN
Privileged SystemAudio: NOT RUN
combined A/V: NOT RUN
two-device LAN: NOT RUN
```

Real-device latency, Oboe backend properties, thermal/power behavior, Bluetooth behavior, and physical A/V skew remain device-specific.
