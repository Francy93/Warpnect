# ADR-0002 - SCL Over UDP

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Status: Accepted

Date: 2026-08-06

## Decision

Use a UDP-first transport architecture for SCL.

## Reason

UDP is selected for:

- Latency control.
- Application-level recovery.
- Custom synchronization.
- Explicit packet pacing.
- Future support for FEC, NACK, and protocol-specific congestion decisions.

## Consequences

SCL owns packet ordering, loss detection, recovery policy, and timing behavior.

Kotlin must not implement UDP transport or packet recovery logic.

Session bootstrap and discovery may be orchestrated by Warpnect, but transport behavior belongs to SCL.
