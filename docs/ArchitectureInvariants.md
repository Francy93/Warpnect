# Architecture Invariants

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines permanent architectural laws for Warpnect and SCL. These rules override local convenience.

## Core Independence

The SCL core MUST remain platform independent.

Android APIs MUST NOT leak into SCL headers, transport code, packet code, telemetry code, or synchronization code.

SCL code MUST be suitable for future reuse on Windows, Linux, and macOS.

## Layer Isolation

Warpnect UI MUST NOT access transport directly.

Networking MUST NOT depend on UI.

Kotlin MAY orchestrate session state and user intent. Kotlin MUST NOT implement UDP transport, packet serialization, fragmentation, FEC, or protocol recovery.

C++ MAY implement SCL protocol, timing, telemetry, and transport. C++ MUST NOT implement Android app lifecycle, UI state, or user experience policy.

## JNI Rules

JNI is only a bridge.

JNI MUST NOT contain:

- Business logic.
- Networking logic.
- Packet serialization logic.
- Session policy.
- UI logic.

JNI MAY contain:

- Type conversion.
- Native call dispatch.
- Explicit error/status mapping.

## Naming Rules

Use Warpnect for application concepts.

Use SCL for protocol and synchronization concepts.

The Android package root MUST be:

```text
io.warpnect
```

The native namespace root SHOULD remain:

```cpp
warpnect::scl
```

Temporary sample package names are forbidden.

The native shared library target MUST be:

```text
scl_core
```

## Dependency Direction

Allowed direction:

```text
UI -> Orchestrator -> Platform bridge -> JNI -> SCL
```

Forbidden direction:

```text
SCL -> JNI -> Android lifecycle -> UI
```

SCL may report status and telemetry upward. It must not make application decisions.

## Performance Rules

Future hot paths MUST avoid:

- Unnecessary heap allocations.
- Unnecessary copies.
- Blocking operations.
- Lock contention.
- String parsing or other string processing.
- UI thread access.

Initialization paths may use richer structures when they do not affect steady-state latency.

## Timing Rules

All latency measurements MUST use monotonic clocks.

Timestamp domains MUST be documented.

Cross-device timestamps MUST NOT be compared directly unless a synchronization model exists.

Telemetry MUST distinguish capture, encode, network, decode, render, and input round-trip stages.

## Protocol Stability

Packet structures MUST be versioned.

Breaking packet layout changes require explicit protocol version changes.

Breaking native bridge changes require explicit ABI version changes and documentation.

Packet headers MUST use fixed-width integer types.

Wire-format structures MUST avoid platform-dependent layout unless explicitly packed and statically checked.

## Architecture Change Rules

Changes to Warpnect/SCL separation, package root, namespace root, native library name, or UDP-first transport philosophy require an ADR.

## Ownership Rules

Session state has one owner: the Warpnect orchestrator.

Transport state has one owner: the SCL transport engine.

Telemetry samples must have clear ownership or immutable handoff.

## Failure Rules

Stubs MUST be honest. If a feature is unavailable, it must return an explicit not-implemented or unavailable result.

Privileged failures MUST have a clean path toward user recovery.

Silent fallback behavior is forbidden in low-latency paths unless it is explicitly documented and measured.

## Phase Discipline

Do not implement future phases inside architecture or cleanup work.

RFC-001 may implement SCL networking. Codec, audio, input, discovery, diagnostics, optimization, desktop, and production work remain separate phases.
