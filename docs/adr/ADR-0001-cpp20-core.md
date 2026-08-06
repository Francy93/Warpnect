# ADR-0001 - C++20 Core

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Status: Accepted

Date: 2026-08-06

## Decision

Use C++20 for the SCL core.

## Reason

C++20 is selected for:

- Portability across Android, Windows, Linux, and macOS.
- Performance control.
- Low-latency runtime behavior.
- Deterministic ownership and memory decisions.
- Direct access to platform-native integration points through thin adapters.

## Consequences

SCL code must avoid Android-specific assumptions in public protocol and transport headers.

Kotlin remains responsible for Android application behavior, UI, lifecycle, and orchestration.

Future desktop ports should reuse the C++20 SCL core.
