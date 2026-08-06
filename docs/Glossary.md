# Glossary

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This glossary defines official project terminology. Definitions apply inside Warpnect only.

## Warpnect

The user-facing application and product ecosystem. Warpnect includes Android lifecycle, Compose UI, application state, platform interaction, and user-facing orchestration.

## SCL

State Coherence Layer. The protocol and synchronization layer responsible for packet foundations, transport abstractions, timing, telemetry concepts, and future cross-platform state coherence.

## Endpoint

One participant in a Warpnect connection. An endpoint may eventually act as receiver, transmitter, or another negotiated role.

## Session

A coordinated relationship between endpoints with lifecycle, capabilities, role state, transport state, and user-facing status.

## Channel

A logical SCL data category, such as video, system audio, microphone audio, input, telemetry, handshake, or session control.

## Stream

A time-ordered flow of related data inside a channel. Future video frames and audio samples are streams.

## Packet

The smallest SCL transport unit described by a packet header and payload.

## Frame

A coherent media or state unit that may later be split across packets. A video frame is the primary future example.

## State

The condition of an application or protocol component at a point in time. Warpnect owns application state; SCL owns protocol-level state.

## Synchronization

The process of keeping endpoints aligned in timing, ordering, and shared interactive state.

## Telemetry

Measured timing and diagnostic data used to understand latency, loss, pacing, and runtime behavior.

## Latency

Elapsed time between a cause and its observable effect in a Warpnect interaction or media path.

## Round Trip Time

Elapsed time for a signal to travel from one endpoint to another and back using documented monotonic timing domains.

## Transport

The SCL mechanism that sends and receives packets between endpoints. Warpnect is UDP-first, but transport behavior is not implemented in the current phase.

## Protocol Version

The SCL compatibility version for packet layout and protocol semantics. Current value: Protocol Version 1.

## Native ABI Version

The compatibility version for the Kotlin/JNI/native bridge boundary. Current value: Native ABI Version 1.

## Architecture Version

The compatibility version for repository structure, architecture boundaries, and naming decisions. Current value: Architecture Version 1.0.
