# Future Roadmap

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This roadmap is architectural context. Phase 1 core networking and Phase 2 core video implementation are complete.

## Phase 0 - Architecture, Repository & Toolchain

Status: Complete.

Implemented, defined and frozen Warpnect's core architecture, the boundaries between Kotlin/Android and C++/SCL, repositories, toolchains, and invariants.

- RFC-000A - Architecture Audit & Stabilization [complete]
- RFC-000B - Architecture Documentation & Invariants [complete]
- RFC-000C - Final Architecture Hardening [complete]
- RFC-000D - Repository & Build System Finalization [complete]

## Phase 1 - SCL Core Networking Engine

Status: Complete.

Implemented the platform-independent C++20 networking core:

- RFC-001A - SCL Packet & Runtime Foundation [complete]
- RFC-001B - SCL UDP Transport Engine [complete]
- RFC-001C - Fragmentation & Reassembly [complete]
- RFC-001D - Loss Detection, NACK & Recovery [complete]
- RFC-001E - Reed–Solomon FEC [complete]
- RFC-001F - Clock Synchronization & Network Telemetry [complete]
- RFC-001G - Phase 1 Integration & Benchmarks [complete]

## Phase 2 - Video Pipeline

Status: Core implementation complete through RFC-002G. RFC-002H is implemented supplemental
Android capture compatibility architecture.

Implemented:

- RFC-002A - Android Privileged Video Capture Foundation [complete]
- RFC-002B - Android Hardware Video Encoder Pipeline [complete]
- RFC-002C - Encoded Video to SCL Transport Integration [complete]
- RFC-002D - Android Hardware Video Decoder Pipeline [complete]
- RFC-002E - Low-Latency Rendering Pipeline [complete]
- RFC-002F - End-to-End Video Streaming [complete]
- RFC-002G - Video Latency, Recovery and Performance Tuning [complete]
- RFC-002H - Android Privileged Screen Capture Backend & Privilege Boundary [implemented]

Phase 2 core history remains complete through RFC-002G. RFC-002H production capture has hardware
validation on legacy API 30/API 31 and modern API 36 paths. H1 real-device media validation is
complete. RFC-002B strict-CBR metadata qualification now uses a disposable same-UID app process,
which contains vendor codec failure without terminating Warpnect. Final same-UID qualification
passed on two API 36 S22 devices plus tested API 30 and API 31 A41 devices. The A41
`InputApiUnavailable` investigation established a narrow Phase 4 Android platform-adapter defect:
the previous resolver assumed `InputManagerGlobal`, which is absent on tested API 30, API 31, and API
33 hardware. The production resolver now retains the modern adapter and falls back through capability
qualification to the legacy `InputManager` adapter. Local production injection of key, touch, pointer,
and joystick events passed on API 30, API 31, and API 33 under the Shizuku shell UserService.
The SystemAudio startup investigation found a capability false positive rather than an Input defect.
`AudioService` authorizes AudioPolicy registration against the Shizuku UserService Binder caller (shell
UID 2000), which does not hold `MODIFY_AUDIO_ROUTING`; the prior package-attributed check advertised a
channel that could not start. Capability qualification now checks the UserService's own permission, so
SystemAudio is omitted before setup when that caller cannot register its AudioPolicy. Post-correction
API 30 and API 31 A41 Host-to-S7 Sessions authenticated, committed setup, and started media without
`SystemAudioStartFailed`. A new human touch on the S7 Client was correlated through the protected Session,
legacy InputManager injection, and a Warpnect-owned target on each A41 Host, closing the two A41 reverse-input
validation targets.
The final APK's physical API 36 modern-input regression passed locally on the S22: `ModernInputManagerGlobal`
was selected and key, touch, pointer, and joystick events were accepted and observed. A separate S22 Host
Session attempt stopped at `SystemAudioStartFailed` before media/Input, so no S22 reverse-input E2E event
was counted; that audio condition remains separate from the resolver result.

RFC-002I is implemented supplemental Client decoder qualification for legacy Android where framework
hardware classification is unavailable. It uses conservative static inspection plus a contained,
same-UID active decoder qualification only for unknown legacy candidates. It does not alter RFC-002D,
RFC-002B, or the completed H1 validation.

Client presentation investigation identified a local `SurfaceView` composition defect: on affected
Samsung Clients, decoded buffers were behind the opaque Compose window buffer. The production view now
uses `setZOrderOnTop(true)`. A real A41 API 31 Host-to-S7 API 26 Client Session is human-confirmed
visible after the fix. S9 API 29 and tablet API 33 both visibly present the same local MediaCodec
fixture, but their current A41-hosted remote traces remain separately blocked before video by capability
negotiation and authentication respectively. A follow-up A41 API 31-to-S7 capture-scope trace selected
the physical logical display (`source_display_id=0`, `layer_stack=0`) and remained active while the Host
left Warpnect. Home, Settings, and the notification shade were each visible on the S7; the user confirmed
the Settings view directly. `HOST FULL-DISPLAY CAPTURE VALIDATED` therefore closes the app-window capture
concern. Growing streaming latency remains a separate observation requiring focused evidence before any
design change.

Privileged helper lifecycle validation then found a real Android ownership defect rather than an expected
Shizuku retention policy: unbinding a non-daemon UserService disconnects it but does not terminate its
process. The capture, audio, and input helpers now implement the reserved UserService destroy transaction,
clean their local resource, and exit after their final gateway owner releases them; temporary SystemAudio
capability controllers also close in `finally`. Four A41 API 31 Host-to-S7 API 26 Client media Sessions,
including a Host Home/background/return interval, left no capture, audio, or input helper after normal
teardown. A bounded harness correction now reaches the genuine off-screen `Disconnect` control before
declaring it absent.

Cold capability/session-negotiation validation identified a readiness-ordering defect, not an undersized
protocol timeout. Previously, the Host synchronously collected its mandatory capability snapshot only after
receiving a Client WNCP offer, after the Client's negotiation window had started. Host readiness now completes
that exact snapshot before advertising discovery and reuses it when responding to WNCP. An A41 API 31 Host and
S7 API 26 Client passed their first deliberately cold exact-qualification Session through authentication,
setup, and media; a subsequent exact-cache-hit regression also passed. WNCP, qualification workloads and
thresholds, payloads, and security semantics were unchanged. The S9 API 29 control now completes WNCP and
setup but independently fails at Client `VideoPipelineStartFailed`; it remains a separate media-start debt.

## Phase 3 - Audio Pipeline

Status: Complete.

Implemented:

- RFC-003A - Android Low-Latency Audio Capture Foundation [complete]
- RFC-003B - Portable Ultra-Low-Latency Opus Encoder [complete]
- RFC-003C - SCL Audio Payload and Transport Integration [complete]
- RFC-003D - Portable Ultra-Low-Latency Opus Decoder [complete]
- RFC-003E - Android Ultra-Low-Latency Audio Playback [complete]
- RFC-003F - End-to-End Audio Streaming [complete]
- RFC-003G - Audio/Video Synchronization [complete]
- RFC-003H - Audio Latency, Recovery and Performance Tuning [complete]

## Phase 4 - Reverse Input

Status: Complete.

Implemented:

- RFC-004A - Portable Input Event Model and SCL Input Payload V1 [complete]
- RFC-004B - Android Input Capture Foundation [complete]
- RFC-004C - Reverse SCL Input Transport [complete]
- RFC-004D - Android Privileged Input Injection [complete]
- RFC-004E - Input Mapping, Coordinate and Device Semantics [complete]
- RFC-004F - End-to-End Reverse Input [complete]
- RFC-004G - Input Latency, State Convergence, Reliability and Performance Tuning [complete]

Phase 4 is implementation-complete. Real-device privileged injection, UHID availability, and
game-specific compatibility remain device-specific validation work.

## Phase 5 - Discovery and Secure Session Management

Status: implementation-complete. RFC-005I integrates the normal Android Host/Client composition,
the public-API LAN and Wi-Fi Direct backends, concrete Phase 2-4 prepared-transport adoption, and
live-transport migration ownership. Real-device validation remains separate from implementation
status.

- RFC-005A - Session Identity & Core Session Model [complete]
- RFC-005B - Local Network Discovery & Presence [complete]
- RFC-005C - Pairing & Trust Bootstrap [complete]
- RFC-005D - Authenticated Session Handshake [complete]
- RFC-005E - Session Keys, Packet Authentication & Anti-Replay [complete]
- RFC-005F - Capability, Role & Feature Negotiation [complete]
- RFC-005G - Endpoint, Channel & Stream Negotiation [complete]
- RFC-005H - Session Lifecycle, Disconnect & Reconnection [complete]
- RFC-005I - End-to-End Discovery & Secure Session Integration [complete]

## Phase 6 - Telemetry and Diagnostics

Status: implementation-complete. Real-device validation debt remains.

- RFC-006A - Unified Runtime Telemetry Model [complete]
- RFC-006B - Media Pipeline Metrics Integration [complete]
- RFC-006C - Network & Recovery Diagnostics [complete]
- RFC-006D - Latency Trace & Cross-Pipeline Correlation [complete]
- RFC-006E - Diagnostic Logging & Bounded Event History [complete]
- RFC-006F - Runtime Diagnostics UI [complete]
- RFC-006G - Session Reports & Benchmark Export [complete]
- RFC-006H - Diagnostics Integration & Validation [complete]

Phase 6 is implementation-complete. Host/JVM, Android build, ABI, and native validation are green;
real Android/hardware validation remains separately tracked because no device was attached.

## Phase 7 - Optimization

Optimize:

- CPU usage.
- Memory allocations.
- Zero-copy paths.
- Thread scheduling.
- Buffer management.
- Battery consumption.
- Thermal behavior.

## Phase 8 - Cross-Platform Expansion

Reuse the C++20 core to build:

- Windows client.
- Linux client.
- macOS client.

Only platform-specific capture, rendering, audio, and input layers should differ.

## Phase 9 - Production Readiness

Prepare the project for release:

- Configuration system.
- Logging.
- Error handling.
- Automated testing.
- CI/CD.
- Documentation.
