# SCL Protocol Design

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document records the intended SCL protocol direction. It does not implement the protocol.

## Protocol Purpose

SCL exists to keep two endpoints coherent under strict latency pressure. It will eventually transport synchronized channels for:

- Video.
- System audio.
- Microphone audio.
- Input events.
- Telemetry.
- Handshake.
- Session control.

## Current Packet Header

The current C++ skeleton defines a packed `PacketHeader` with:

- `protocol_version`
- `flags`
- `sequence_number`
- `timestamp_us`
- `payload_type`
- `slice_index`
- `total_slices`

The frozen Phase 0.9 header uses fixed-width fields and remains packed. Serialization, parsing, fragmentation, and validation are reserved for RFC-001 and later.

## Payload Types

Current payload categories are:

- Unknown
- Video
- System audio
- Microphone audio
- Input
- Telemetry
- Handshake
- Session control

These categories are identifiers only. They do not imply any implemented channel behavior yet.

`Handshake` is reserved for capability negotiation, endpoint compatibility, and protocol compatibility checks.

`SessionControl` is reserved for active session lifecycle messages after negotiation.

## Synchronization

Future synchronization must be designed around:

- Monotonic timestamps.
- Explicit sequence numbers.
- Loss detection.
- Frame or state ordering.
- Clock-domain documentation.

## Telemetry

SCL telemetry will track timing across capture, encode, network, decode, render, and input round-trip stages.

Telemetry must support diagnostics without introducing meaningful hot-path overhead.

## Transport

The future transport engine will be UDP-first. Packet send and receive behavior belongs in C++20 SCL code, not Kotlin.

Android-specific socket or permission setup must remain outside the reusable protocol core unless isolated behind a platform adapter.

## Stability

Packet layout is part of the protocol contract. Breaking layout changes require a protocol version change and migration notes.
