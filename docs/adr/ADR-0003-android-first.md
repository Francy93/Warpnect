# ADR-0003 - Android First

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Status: Accepted

Date: 2026-08-06

## Decision

Build the first implementation as Android-first.

## Reason

Android is selected as the first target because:

- It is the initial user-facing platform.
- It provides native media APIs needed for later video and audio phases.
- It has privileged input requirements that shape the platform bridge early.
- It allows rapid validation of receiver and transmitter roles on real devices.

## Consequences

The repository starts as an Android app.

Android-specific code remains in Kotlin and platform packages.

The SCL C++20 core remains portable so future Windows, Linux, and macOS clients can reuse it.
