# Future Roadmap

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This roadmap is architectural context. Phase 1 core networking and Phase 2 video implementation are complete.

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

Status: Complete.

Implemented:

- RFC-002A - Android Privileged Video Capture Foundation [complete]
- RFC-002B - Android Hardware Video Encoder Pipeline [complete]
- RFC-002C - Encoded Video to SCL Transport Integration [complete]
- RFC-002D - Android Hardware Video Decoder Pipeline [complete]
- RFC-002E - Low-Latency Rendering Pipeline [complete]
- RFC-002F - End-to-End Video Streaming [complete]
- RFC-002G - Video Latency, Recovery and Performance Tuning [complete]

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

Status: in progress.

- RFC-006A - Unified Runtime Telemetry Model [complete]
- RFC-006B - Media Pipeline Metrics Integration [next]
- RFC-006C - Network & Recovery Diagnostics
- RFC-006D - Latency Trace & Cross-Pipeline Correlation
- RFC-006E - Diagnostic Logging & Bounded Event History
- RFC-006F - Runtime Diagnostics UI
- RFC-006G - Session Reports & Benchmark Export
- RFC-006H - Diagnostics Integration & Validation

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
