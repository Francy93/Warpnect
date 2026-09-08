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
The physical API 36 modern-input regression passed locally on the S22: `ModernInputManagerGlobal` was
selected and key, touch, pointer, and joystick events were accepted and observed. A separate S22 SystemAudio
investigation found an audio-only API 36 restriction. After the Shizuku UserService clears the incoming
application Binder identity, `AudioPolicy` registration can proceed as shell UID 2000, but the first
production loopback `AudioRecord` is uninitialized because AudioFlinger rejects the incoherent
`uid=2000, package=io.warpnect` attribution. The capability preflight now performs that same bounded
prepare/start/stop path before WNCP and therefore truthfully omits SystemAudio on this runtime. It neither
falls back after commitment nor generalizes the result to Samsung or API 36 devices beyond the tested S22.
The S22 reverse-input E2E result remains uncounted; this SystemAudio condition is separate from the modern
Input resolver result.
The API 33 tablet was revalidated with the same current production SystemAudio path. Its prior
`AudioRecordCreationFailed` no longer reproduces after the generic Binder-identity correction: the real
preflight prepares, starts, reads PCM, and stops successfully. A normal-app 48 kHz stereo `USAGE_GAME` tone
produced 75,120 captured frames with non-zero PCM energy through the privileged loopback path, closing the
tablet SystemAudio compatibility debt as `SYSTEM_AUDIO_SUPPORTED`. A single Tablet-to-S9 Session authenticated
but did not complete an independent Tablet Host/video checkpoint, so audio remote E2E remains unclaimed and
the tablet remote-session blocker stays separate.

RFC-002I is implemented supplemental Client decoder qualification for legacy Android where framework
hardware classification is unavailable. It uses conservative static inspection plus a contained,
same-UID active decoder qualification only for unknown legacy candidates. It does not alter RFC-002D,
RFC-002B, or the completed H1 validation.

Client presentation investigation identified a local `SurfaceView` composition defect: on affected
Samsung Clients, decoded buffers were behind the opaque Compose window buffer. The production view now
uses `setZOrderOnTop(true)`. A real A41 API 31 Host-to-S7 API 26 Client Session is human-confirmed
visible after the fix. S9 API 29 and tablet API 33 both visibly present the same local MediaCodec
fixture. The S9 now also passes A41-hosted authentication, WNCP, setup, receiver startup, AVC
configuration, decoder startup, first access-unit decode, and Surface release. The user then physically
confirmed the A41 Host screen, Android Home, and ordinary Host applications visibly streamed on the S9.
`S9 PRODUCTION VIDEO PIPELINE VALIDATED` closes the former S9 media-start/presentation debt. The tablet
remains separately blocked before video by authentication. A follow-up A41 API 31-to-S7
capture-scope trace selected
the physical logical display (`source_display_id=0`, `layer_stack=0`) and remained active while the Host
left Warpnect. Home, Settings, and the notification shade were each visible on the S7; the user confirmed
the Settings view directly. `HOST FULL-DISPLAY CAPTURE VALIDATED` therefore closes the app-window capture
concern. The then-open growing-latency observation is resolved below as a Video Resync Control V1
sender-ownership defect, not a capture or presentation regression.

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
thresholds, payloads, and security semantics were unchanged. The S9 API 29 control also completed WNCP,
setup, receiver startup, AVC configuration, decoder startup, first access-unit decode, Surface release, and
human-confirmed remote presentation. Its former `VideoPipelineStartFailed` lifecycle race is closed.

RFC-002B strict-CBR qualification now retains a `Supported` result across application-process restarts,
but only under a complete versioned exact key: algorithm, probe workload, target profile, codec identity,
exact AVC request, Build fingerprint, and media-runtime compatibility version. The A41/API 31 first
exact-key miss ran its two eligible strict-CBR probes and persisted their `Supported` results; later new
processes, force-stop/relaunch, and data-preserving reinstall reused those records without spawning
`:codecProbe`. Timeouts, probe death, and other transient failures remain process-local and the existing
probe-death quarantine is unchanged. This removes repeated qualification work without treating
compatibility as an install-lifetime or device-model judgment.

Session establishment and restart reliability is now validated for the A41/API 31 Host-to-S9/API 29
Client topology. The root cause was not discovery reachability or a timeout: after normal cancellation,
the application-scoped Client coordinator remained terminal `Closed` while the retained discovery model
could still make the UI look ready. A subsequent Connect was therefore rejected as busy before pairing.
The discovery-preserving Client attempt path also retained a `SessionProtectionController` that it had
already closed. Normal disconnect now returns the Client coordinator to reusable `Idle`, and a new
attempt obtains a fresh session-protection owner without reusing a cancelled runtime. Ten consecutive
normal first-attempt A41-to-S9 Session cycles passed through media and semantic teardown without process
kills, force-stops, reboot, Shizuku restart, or hidden retry. Client-only, Host-only, and dual app-process
restart cases each passed on their first attempt with preserved app data; the Host used its persistent
RFC-002B result and did not spawn `:codecProbe`. An A41-to-S7 technical regression also passed. This
preserves SAS, WNCP/WNSN, Session protection, and all frozen protocol/ABI contracts; it is a local
runtime ownership correction, not automatic retry behavior.

The growing-streaming-latency investigation then identified a freshness-correctness defect rather than a
throughput or quality limitation. When a Client detected a video discontinuity, it correctly entered
`WaitingForKeyFrame` and sent Video Resync Control V1. The Android production sender had not bound
`NativeVideoSenderControlRuntime`, so its Host never pumped that control channel or forwarded a real
keyframe request to the encoder. It kept sending frames the Client could not safely use, which made the
last visible image increasingly stale. The production binding now owns the existing V1 control runtime;
no profile, payload, FEC, decoder, quality, or frozen protocol/ABI semantics changed. Final 180-second
A41 API 31 Host-to-S9 API 29 and A41 API 31 Host-to-S7 API 26 runs with repeated harmless Host-display
updates kept reassembly/ready occupancy at 0-1, showed no timeout/full-window growth, continuously
released decoder output, and recovered every observed resync through a forwarded keyframe request.
`GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` closes the stale-frame accumulation debt. Absolute
end-to-end latency remains intentionally unmeasured without cross-device clock provenance; residual
fixed latency and general throughput work remain Phase 7 concerns.

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
