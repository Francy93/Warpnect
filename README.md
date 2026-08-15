# Warpnect

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect is an Android-first ultra-low-latency remote presence project powered by the State Coherence Layer (SCL).

Warpnect is the application layer. It owns Android lifecycle, Compose UI, application state, platform integration, and user-facing orchestration.

SCL is the protocol layer. It owns packet foundations, transport abstractions, timing, telemetry concepts, and the future cross-platform real-time core.

## Current Status

This repository contains the frozen architecture baseline, the complete SCL Phase 1 core networking foundation, the complete Phase 2 Android video pipeline through RFC-002G, the complete Phase 3 audio pipeline through RFC-003H, the complete Phase 4 reverse-input pipeline through RFC-004G, and the Phase 5 foundations through authenticated endpoint, channel, and exact stream preparation in RFC-005G.

Present:

- Android Kotlin application skeleton.
- Compose entry surface.
- Warpnect role state machine.
- Shizuku bridge and privileged capture UserService integration.
- SCL C++20 packet codec foundation.
- SCL UDP transport engine.
- SCL fragmentation and reassembly primitives.
- SCL loss detection, NACK, and retransmission cache primitives.
- SCL Reed-Solomon FEC primitives.
- SCL clock synchronization and bounded network telemetry primitives.
- SCL Phase 1 integration tests and reproducible host-native benchmarks.
- Privileged Android display capture foundation that writes to a caller-owned `Surface`.
- Android hardware AVC encoder foundation using `MediaCodec` Surface input and borrowed encoded output buffers.
- Version 1 SCL video payload contract for StreamConfig and AccessUnit messages.
- Native encoded-video packetization, UDP sending, retransmission/NACK handling, optional FEC, and receive-side video payload parsing.
- Kotlin transport sink that forwards borrowed direct `MediaCodec` output buffers through `NativeBridge`.
- Android hardware AVC decoder foundation using pull-based `MediaCodec` input slots and caller-owned `Surface` output.
- Low-latency Android `SurfaceView` rendering foundation with aspect-fit layout, Surface lifecycle tracking, render/drop/scheduled release policy, and frame-rate hinting.
- End-to-end Android video session orchestration from privileged capture/encode through SCL transport to bounded receive/recovery, decoder input fill, and SurfaceView rendering.
- Phase 2 latency/recovery tuning with bounded recovery deadlines, VideoResyncRequest control, live clock-sync/RTT telemetry hooks, optional loss-reactive bitrate policy, and host-native video benchmarks.
- Independent PCM audio capture foundations for privileged system/game playback and microphone audio.
- PCM Shared Ring Version 1 for privileged system-audio transfer through bounded SharedMemory and FD notification/ACK.
- Microphone `AudioRecord` capture using preallocated direct buffers and borrowed PCM sink callbacks.
- Portable libopus 1.6.1 Opus audio encoder foundation using RestrictedLowDelay operation, 5 ms baseline frames, borrowed direct PCM input, and borrowed encoded packet output.
- SCL Audio Payload Version 1 transport for both system and microphone streams, mapping each borrowed raw Opus packet to one SCL AudioFrame over non-blocking UDP.
- Portable libopus 1.6.1 Opus audio decoder foundation using borrowed direct encoded input, preallocated PCM16 output, explicit caller-driven PLC, and borrowed decoded PCM sink callbacks.
- Android low-latency native playback foundation using pinned Oboe 1.10.0, a fixed-capacity PCM handoff ring, one explicit PCM ownership copy, and a high-priority native output callback.
- Integrated end-to-end audio streaming sessions that compose capture, Opus encode/decode, SCL Audio Payload V1 UDP transport, bounded native audio receive storage, explicit small-gap PLC, and Oboe playback.
- A/V synchronization foundation above the completed video and audio pipelines. Audio hardware presentation acts as the receiver-side master clock; video can be scheduled against the measured audio source-to-output timeline when the video PTS domain qualifies as compatible with the sender audio clock.
- Phase 3 audio latency, recovery, and performance tuning backed by a reproducible UltraLowLatency profile, host-native Phase 3 audio benchmarks, explicit recovery-policy selection, bounded playback/A/V timing defaults, and documentation that separates host CPU/runtime costs from device-specific output latency.
- Input Payload Version 1 for portable low-level keyboard, touch, pointer, scroll, gamepad, and reset semantics using the existing `PayloadType::Input`.
- Platform-neutral Kotlin input models and native C++20 Input Payload V1 encode/decode/validation tests. Input wire semantics do not embed Android device IDs, Android keycodes, scan codes, source constants, display IDs, or pixel coordinates.
- Android low-latency input-capture foundation using a dedicated View-based raw input surface, synchronous `InputEventSink`, bounded session-local device-slot registry, hardware keyboard HID mapping, touch/pointer normalization, scroll, pointer capture, and common game-controller state snapshots.
- Reverse SCL input transport that submits each portable input observation immediately through `NativeBridge` as one non-blocking `PayloadType::Input` UDP datagram. It uses a single sequence domain, preserves source event timestamps, and keeps one reusable direct touch-contact scratch buffer per transport controller.
- Privileged Android target-side input injection through a synchronous Shizuku/Sui UserService Binder call and cached `InputManagerGlobal` reflection in the privileged process. Android-ready key, touch, pointer, and joystick events use explicit display targeting, asynchronous InputManager submission, bounded state, and explicit reset repair.
- Endpoint-local reverse-input mapping: receiver-side touch/absolute-pointer coordinates use the renderer's exact aspect-fit video-content rectangle before SCL transport, while target-side portable keyboard, touch, pointer, scroll, and gamepad semantics map synchronously into RFC-004D Android-ready events using target logical-display geometry and bounded target-local device resolution.
- Integrated end-to-end reverse input: Android keyboard, touch, mouse, and gamepad capture flows through viewport-aware Portable Input V1 mapping, one-datagram SCL UDP transport, continuous strict-endpoint target receive/parsing, target mapping, and the privileged Shizuku/Sui injection backend.
- Phase 4 input latency, reliability, and performance tuning. The `UltraLowLatencyConvergent` profile has zero network reorder wait, bounded immediate critical-transition/reset redundancy, transport and semantic duplicate suppression, per-semantic freshness guards, and synchronous stable-pointer-ID touch repair. It has no ACK, retransmission, NACK, FEC, retry queue, or jitter buffer.
- A cold privileged `/dev/uhid` capability probe for future hardware-like input compatibility research. It creates no virtual HID device and does not claim a production UHID backend.
- Formatting, lint, CI, and test infrastructure.
- Platform-neutral Phase 5 session identity and topology modeling. Devices, peers, live sessions, channels, paths, and logical peripherals have distinct bounded identities; a Host can own independent Client sessions without equating a peer to an endpoint.
- Android local discovery and ephemeral presence through LAN DNS-SD and Wi-Fi Direct DNS-SD. Each explicit advertising epoch uses one fresh random DiscoveryPresenceId across both backends, so LAN and Direct route observations can merge without broadcasting DeviceId or SessionId.
- A bounded untrusted-presence cache with strict Discovery Presence Schema Version 1 TXT parsing, route loss/expiry, availability visibility policy, contact-port reservation without a receive protocol, typed Android permission/capability planning, and one `WarpnectDiscovery` control context.
- Persistent local identity and explicit pairing/trust bootstrap. A non-zero DeviceId is bound to an Android Keystore P-256 signing key; explicit LAN pairing uses fresh ECDH, signed transcript commitment, a six-digit SAS, and two human confirmations before storing a bounded `DeviceId -> public key` trust binding.
- Mutually authenticated WNSH session handshakes. A trusted Host authenticates first inside encrypted handshake records, then both peers produce a fresh forward-secret root secret without creating a media session.
- Session Packet Protection V1: a portable native WNSD AES-128-GCM envelope with scoped directional keys, bounded anti-replay windows, monotonic key epochs, and cold JNI runtime/context ownership.
- Authenticated capability and feature negotiation through bounded WNCP Version 1 records carried only inside encrypted RFC-005E SessionControl datagrams. Frozen Client/Host capability snapshots, explicit Required/Preferred/Disabled requests, and Host policy produce one immutable profile without starting media, audio, input, or Direct resources.
- Authenticated RFC-005G Session Setup V1 over protected SessionControl: strict bounded WNSN state machines, explicit LAN/Direct path policy, public-API Android Wi-Fi Direct Group Owner reuse, authenticated Direct candidate probing, path-bound endpoint leases, deterministic PathId/ChannelId allocation, exact stream validation, and independent protected native channel transports. The result is a bounded `PreparedSessionBootstrap`; no media pipeline is started.
- Architecture Version 1.0 documentation.

Not implemented:

- Adaptive FEC, packet pacing, full congestion control, automatic MTU selection, or production Internet fairness.
- Device-specific real-audio latency figures, Oboe route measurements, Bluetooth/acoustic measurements, and physical A/V sync figures beyond the recorded benchmark/device runs.
- Running-session lifecycle, path health, automatic live failover, disconnect/reconnect/resume, full Phase 5 startup orchestration, telemetry UI, or an unbounded telemetry wire stream.

## Repository Layout

```text
.
├── app/
│   └── src/main/
│       ├── java/io/warpnect/
│       └── cpp/
├── docs/
│   └── adr/
├── native/
│   └── tests/
├── .github/workflows/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Build Requirements

Recommended baseline:

- Android Studio Ladybug 2024.2.1 or newer.
- JDK 17.
- Gradle 8.12 through the committed Gradle wrapper.
- Android Gradle Plugin 8.7.3.
- Kotlin 2.0.21.
- Android SDK platform 35.
- Android NDK 27.0.12077973.
- CMake 3.22.1.

See [BuildEnvironment.md](docs/BuildEnvironment.md) for details.

## Android SDK Setup

Create a local SDK configuration that is not committed:

```properties
sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

Alternatively set `ANDROID_HOME` or `ANDROID_SDK_ROOT`.

Install:

```text
platforms;android-35
build-tools;34.0.0
ndk;27.0.12077973
cmake;3.22.1
```

## Build Commands

Debug build:

```powershell
.\gradlew.bat :app:assembleDebug
```

Release build without signing/publishing:

```powershell
.\gradlew.bat :app:assembleRelease
```

Unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Android lint:

```powershell
.\gradlew.bat :app:lintDebug
```

Kotlin style:

```powershell
.\gradlew.bat :app:ktlintCheck
```

Native host build and tests:

```powershell
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native/build --config Debug
ctest --test-dir native/build -C Debug --output-on-failure
```

Native Release benchmarks:

```powershell
cmake -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-release --config Release
.\native\build-release\Release\scl_phase1_benchmarks.exe --standard --iterations 500 --output native\build-release\phase1-baseline.csv
.\native\build-release\Release\scl_phase2_video_benchmarks.exe --standard --output native\build-release\phase2-video-standard.csv
.\native\build-release\Release\opus_audio_encoder_benchmarks.exe --standard --output native\build-release\opus-audio-encoder-standard.csv
.\native\build-release\Release\opus_audio_decoder_benchmarks.exe --standard --output native\build-release\opus-audio-decoder-standard.csv
.\native\build-release\Release\scl_phase3_audio_benchmarks.exe --standard --output native\build-release\benchmarks\phase3-audio-standard.csv
.\native\build-release\Release\scl_phase4_input_benchmarks.exe --standard --output native\build-release\benchmarks\phase4-input-standard.csv
.\native\build-release\Release\scl_session_protection_benchmarks.exe --standard --output native\build-release\benchmarks\session-protection-standard.csv
```

## Native Build Overview

The Android native library target is:

```text
scl_core
```

Android loads it as:

```kotlin
System.loadLibrary("scl_core")
```

The produced Android shared library is `libscl_core.so`.

## Documentation Index

- [Architecture](docs/Architecture.md)
- [Architecture Invariants](docs/ArchitectureInvariants.md)
- [Architecture Version](docs/ArchitectureVersion.md)
- [Build Environment](docs/BuildEnvironment.md)
- [Coding Standards](docs/CodingStandards.md)
- [Decision Records](docs/DecisionRecords.md)
- [Future Roadmap](docs/FutureRoadmap.md)
- [Glossary](docs/Glossary.md)
- [Native Boundary](docs/NativeBoundary.md)
- [Project Structure](docs/ProjectStructure.md)
- [Protocol Design](docs/ProtocolDesign.md)
- [Repository Rules](docs/RepositoryRules.md)
- [RFC-001A Packet Foundation](docs/rfc/RFC-001A-SCL-Packet-Runtime-Foundation.md)
- [RFC-001B UDP Transport](docs/rfc/RFC-001B-SCL-UDP-Transport-Engine.md)
- [RFC-001C Fragmentation and Reassembly](docs/rfc/RFC-001C-SCL-Fragmentation-Reassembly.md)
- [RFC-001D Loss, NACK and Recovery](docs/rfc/RFC-001D-SCL-Loss-NACK-Recovery.md)
- [RFC-001E Reed-Solomon FEC](docs/rfc/RFC-001E-SCL-Reed-Solomon-FEC.md)
- [RFC-001F Clock Synchronization and Network Telemetry](docs/rfc/RFC-001F-SCL-Clock-Synchronization-Network-Telemetry.md)
- [RFC-001G Phase 1 Integration and Benchmarks](docs/rfc/RFC-001G-SCL-Phase1-Integration-Benchmarks.md)
- [RFC-002A Android Privileged Video Capture Foundation](docs/rfc/RFC-002A-Android-Privileged-Video-Capture-Foundation.md)
- [RFC-002B Android Hardware Video Encoder Pipeline](docs/rfc/RFC-002B-Android-Hardware-Video-Encoder-Pipeline.md)
- [RFC-002C Encoded Video to SCL Transport Integration](docs/rfc/RFC-002C-Encoded-Video-SCL-Transport-Integration.md)
- [RFC-002D Android Hardware Video Decoder Pipeline](docs/rfc/RFC-002D-Android-Hardware-Video-Decoder-Pipeline.md)
- [RFC-002E Android Low-Latency Rendering Pipeline](docs/rfc/RFC-002E-Android-Low-Latency-Rendering-Pipeline.md)
- [RFC-002F End-to-End Video Streaming](docs/rfc/RFC-002F-End-to-End-Video-Streaming.md)
- [RFC-002G Video Latency, Recovery and Performance Tuning](docs/rfc/RFC-002G-Video-Latency-Recovery-Performance-Tuning.md)
- [RFC-003A Android Low-Latency Audio Capture Foundation](docs/rfc/RFC-003A-Android-Low-Latency-Audio-Capture-Foundation.md)
- [RFC-003B Portable Ultra-Low-Latency Opus Audio Encoder Pipeline](docs/rfc/RFC-003B-Portable-Ultra-Low-Latency-Opus-Audio-Encoder-Pipeline.md)
- [RFC-003C SCL Audio Payload and Transport Integration](docs/rfc/RFC-003C-SCL-Audio-Payload-Transport-Integration.md)
- [RFC-003D Portable Ultra-Low-Latency Opus Audio Decoder Pipeline](docs/rfc/RFC-003D-Portable-Ultra-Low-Latency-Opus-Audio-Decoder-Pipeline.md)
- [RFC-003E Android Ultra-Low-Latency Audio Playback Pipeline](docs/rfc/RFC-003E-Android-Ultra-Low-Latency-Audio-Playback-Pipeline.md)
- [RFC-003F End-to-End Ultra-Low-Latency Audio Streaming](docs/rfc/RFC-003F-End-to-End-Ultra-Low-Latency-Audio-Streaming.md)
- [RFC-003G Ultra-Low-Latency Audio/Video Synchronization](docs/rfc/RFC-003G-Ultra-Low-Latency-Audio-Video-Synchronization.md)
- [RFC-003H Audio Latency, Recovery and Performance Tuning](docs/rfc/RFC-003H-Audio-Latency-Recovery-Performance-Tuning.md)
- [RFC-004A Portable Input Event Model and SCL Input Payload V1](docs/rfc/RFC-004A-Portable-Input-Event-Model-SCL-Input-Payload.md)
- [RFC-004B Android Low-Latency Input Capture Foundation](docs/rfc/RFC-004B-Android-Low-Latency-Input-Capture-Foundation.md)
- [RFC-004C Reverse SCL Input Transport](docs/rfc/RFC-004C-Reverse-SCL-Input-Transport.md)
- [RFC-004D Android Privileged Low-Latency Input Injection](docs/rfc/RFC-004D-Android-Privileged-Low-Latency-Input-Injection.md)
- [RFC-004E Input Mapping, Coordinate and Device Semantics](docs/rfc/RFC-004E-Input-Mapping-Coordinate-Device-Semantics.md)
- [RFC-004F End-to-End Ultra-Low-Latency Reverse Input](docs/rfc/RFC-004F-End-to-End-Ultra-Low-Latency-Reverse-Input.md)
- [RFC-004G Input Latency, State Convergence, Reliability and Performance Tuning](docs/rfc/RFC-004G-Input-Latency-State-Convergence-Reliability-Performance-Tuning.md)
- [RFC-005A Session Identity & Core Session Model](docs/rfc/RFC-005A-Session-Identity-Core-Session-Model.md)
- [RFC-005B Local Network Discovery & Presence](docs/rfc/RFC-005B-Local-Network-Discovery-Presence.md)
- [RFC-005C Pairing & Trust Bootstrap](docs/rfc/RFC-005C-Pairing-Trust-Bootstrap.md)
- [RFC-005D Authenticated Session Handshake](docs/rfc/RFC-005D-Authenticated-Session-Handshake.md)
- [RFC-005E Session Keys, Packet Authentication & Anti-Replay](docs/rfc/RFC-005E-Session-Keys-Packet-Authentication-Anti-Replay.md)
- [RFC-005F Capability, Role & Feature Negotiation](docs/rfc/RFC-005F-Capability-Role-Feature-Negotiation.md)
- [Phase 1 Baseline Benchmarks](docs/benchmarks/Phase1Baseline.md)
- [Phase 2 Video Baseline](docs/benchmarks/Phase2VideoBaseline.md)
- [Phase 3 Audio Baseline](docs/benchmarks/Phase3AudioBaseline.md)
- [Phase 4 Reverse Input Baseline](docs/benchmarks/Phase4ReverseInputBaseline.md)
- [SCL Protocol Principles](docs/SCLProtocolPrinciples.md)
- [State Management](docs/StateManagement.md)
- [Thread Model](docs/ThreadModel.md)
- [Versioning](docs/Versioning.md)
- [Warpnect and SCL](docs/WarpnectAndSCL.md)

## Roadmap Summary

Phase 1 networking is complete. Phase 2 video is complete: RFC-002A privileged Android capture, RFC-002B hardware AVC encoding, RFC-002C encoded AVC transport over SCL, RFC-002D hardware AVC decoding, RFC-002E low-latency Surface rendering, RFC-002F end-to-end video session orchestration, and RFC-002G latency/recovery/performance tuning are implemented.

Phase 3 audio is implementation-complete. RFC-003A implements PCM capture foundations for privileged system/game playback where supported and independent microphone capture. RFC-003B adds a portable RestrictedLowDelay Opus encoder foundation for short raw Opus packets. RFC-003C adds SCL Audio Payload Version 1 transport for both system and microphone streams. RFC-003D adds a portable queue-free Opus decoder foundation with borrowed PCM output. RFC-003E adds an Android low-latency native playback foundation based on Oboe and a bounded PCM handoff ring. RFC-003F integrates end-to-end audio streaming with receiver-first StreamConfig handling, immediate sample-position ordering, small-gap Opus PLC, and freshness resets for large gaps. RFC-003G adds a latency-bounded A/V synchronization foundation that uses audio hardware presentation as the master clock, qualifies video timestamp compatibility before using scheduled rendering, and falls back to immediate presentation when trustworthy timing is unavailable. RFC-003H adds Phase 3 performance configuration, reproducible host-native audio benchmarks, freshness-oriented recovery evaluation, and documented production defaults.

Phase 4 reverse input is implementation-complete through RFC-004G. RFC-004A defines Portable Input Payload Version 1, RFC-004B captures Android input, RFC-004C sends one immediate SCL UDP datagram per observation, RFC-004D injects Android-ready events through a Shizuku/Sui UserService, RFC-004E supplies endpoint-local viewport and target-display semantics, RFC-004F composes the end-to-end path, and RFC-004G adds no-wait bounded state convergence. Full-state input converges from newer accepted state, stale state cannot resurrect released controls, critical transitions use immediate duplicate submissions, and touch repair uses stable pointer IDs. Real-device privileged injection, observed InputManager device identity, UHID availability, and game compatibility remain device-specific and pending where not measured.

Phase 5 now includes RFC-005A through RFC-005F. The session core keeps device identity, peer identity, SessionId, network path, channel, and session-scoped logical peripheral identity separate. RFC-005B adds unauthenticated ephemeral LAN/Direct discovery, RFC-005C adds explicit human-verified DeviceId-to-P-256-key trust, RFC-005D creates an authenticated forward-secret bootstrap, and RFC-005E protects SessionControl. RFC-005F then freezes an authenticated capability profile over WNSD without opening a channel or starting a pipeline. Endpoint/channel establishment, Direct connection, failover, and reconnect remain later work.

Real-device performance figures remain device-specific and must not be generalized from host benchmarks. Future RFCs must preserve Architecture Version 1.0 unless an ADR explicitly changes it.
