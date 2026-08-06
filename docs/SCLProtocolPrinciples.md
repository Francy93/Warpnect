# SCL Protocol Principles

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines protocol principles that future SCL implementation RFCs must preserve.

It is not an implementation specification.

## Purpose

SCL exists to maintain coherent interactive state between endpoints under strict latency constraints.

SCL is designed for:

- Remote gaming.
- Interactive applications.
- Low-latency remote presence.
- Synchronized device experiences.

The goal is minimum perceived divergence between endpoints, not maximum throughput.

## State Coherence Over Delivery

Traditional streaming systems often optimize bandwidth, compression ratio, and reliability.

SCL prioritizes:

- Temporal correctness.
- Responsiveness.
- Synchronization.
- Useful delivery within a latency budget.

A late packet can be less valuable than a missing packet.

## UDP-First Direction

SCL uses a UDP-first transport architecture.

Future reliability features belong above UDP inside SCL, including:

- Packet loss detection.
- FEC.
- NACK and selective recovery.
- Time-sensitive retransmission decisions.

None of those features are implemented in the current phase.

## Packet Contract

Every SCL packet header must expose:

- Protocol Version.
- Flags.
- Sequence number.
- Timestamp.
- Payload type.
- Slice index.
- Total slices.

Packet layout is versioned. Breaking layout changes require a Protocol Version increment.

## Timing

Latency and ordering decisions must use monotonic clocks.

Cross-device timestamps must not be compared directly unless a synchronization model exists.

## Channels

SCL separates channel identity from application UX.

Future channels include video, system audio, microphone audio, input, telemetry, handshake, and session control.

Handshake is for capability and compatibility negotiation.

Session control is for active session lifecycle.

## Platform Independence

SCL must remain independent from Android, Compose, Activity lifecycle, Shizuku, and Kotlin concepts.

Platform-specific code belongs at the edge.

## Compatibility Domains

SCL has two independent compatibility domains:

- Protocol Version 1 for packet and protocol compatibility.
- Native ABI Version 1 for Kotlin/JNI/native bridge compatibility.

They must evolve independently.

## Final Principle

SCL is not a faster screen streaming protocol.

SCL is a synchronization layer designed to make distributed interactive state feel local.
