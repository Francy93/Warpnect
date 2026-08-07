# Future Roadmap

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This roadmap is architectural context. Phase 1 core networking is complete; Phase 2 is the next implementation phase.

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

## Phase 2 - Video Streaming Pipeline

Status: Next.

Implement:

- Screen capture.
- Hardware MediaCodec encoder.
- Hardware decoder.
- Zero-copy rendering.
- Adaptive bitrate.
- Low-latency video pipeline.

## Phase 3 - Audio Pipeline

Implement:

- System audio streaming.
- Microphone return channel.
- Opus encoder and decoder.
- Synchronization with video.
- Low-latency playback and render pipeline.

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
