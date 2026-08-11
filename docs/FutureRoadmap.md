# Future Roadmap

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This roadmap is architectural context. Phase 1 core networking and Phase 2 video implementation are complete.

## Phase 1 - SCL Core Networking Engine

Status: Complete.

Implemented the platform-independent C++20 networking core:

- UDP transport.
- Binary packet protocol.
- Packet serialization.
- Frame fragmentation.
- Telemetry system.
- Reed-Solomon FEC.
- NACK and packet recovery.
- Clock synchronization.
- Deterministic integration testing.
- Reproducible host-native benchmarks.

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

Status: In progress.

Implemented:

- RFC-003A - Android Low-Latency Audio Capture Foundation [complete]
- RFC-003B - Portable Ultra-Low-Latency Opus Encoder [complete]
- RFC-003C - SCL Audio Payload and Transport Integration [complete]

Next:

- RFC-003D - Android Audio Decoder Pipeline [next]

Planned:

- RFC-003E - Low-Latency Audio Playback Pipeline.
- RFC-003F - End-to-End Audio Streaming.
- RFC-003G - Audio/Video Synchronization.
- RFC-003H - Audio Latency, Recovery and Performance Tuning.

## Phase 4 - Reverse Input

Implement:

- Touch.
- Keyboard.
- Mouse.
- Gamepad.
- Shizuku integration.
- `/dev/uinput` virtual devices.
- Bidirectional input protocol.

## Phase 5 - Discovery and Session Management

Implement:

- Wi-Fi Direct.
- mDNS/NSD.
- Automatic peer discovery.
- Session negotiation.
- Connection lifecycle.
- Authentication primitives.

## Phase 6 - Telemetry and Diagnostics

Implement:

- Real-time latency overlay.
- Per-stage timing.
- Network statistics.
- Packet loss graphs.
- Performance logging.
- Benchmark tools.

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
