# Coding Standards

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

These standards apply to Warpnect Kotlin code, SCL C++ code, JNI glue, and documentation.

## General

Prefer explicit structure over premature abstraction.

Keep files small and responsibility-focused.

Do not hide unimplemented behavior behind fake success.

Use stable names that match the Warpnect/SCL split.

## Kotlin

Use package root:

```kotlin
package io.warpnect
```

Kotlin owns:

- Android lifecycle.
- Compose UI.
- App orchestration.
- Platform bridges.
- User-facing state.

Kotlin must not own:

- UDP transport implementation.
- Packet serialization.
- FEC or packet recovery.
- Protocol hot paths.

Style rules:

- Use immutable public state where possible.
- Expose state through `StateFlow` or another explicit observable model.
- Keep Compose functions stateless where practical.
- Send user actions to the orchestrator.
- Return explicit result types from stubs and platform bridges.

## C++20

Use namespace root:

```cpp
namespace warpnect::scl
```

C++ owns:

- Protocol structures.
- Transport interfaces and future implementations.
- Telemetry timing structures.
- Synchronization primitives.

Style rules:

- Use fixed-width integer types for protocol-facing data.
- Prefer `noexcept` on hot-path and status-returning interfaces where practical.
- Avoid exceptions in packet and transport hot paths.
- Avoid heap allocations in steady-state hot paths.
- Avoid platform headers in public SCL headers.
- Use `std::span` for borrowed packet buffers.
- Use explicit status/result types across interfaces.

## JNI

JNI files must be narrow and boring.

JNI may convert values and call C++ bridge functions. It must not contain networking, business logic, or protocol implementation.

JNI-exposed functions must match the final package identity `io.warpnect`.

## Naming

Use Warpnect for application-level names.

Use SCL for protocol-level names.

Do not introduce a third conceptual product name.

The Android native shared library target is `scl_core`, producing `libscl_core.so`.

## Error Handling

Prefer explicit result types over nullable values for recoverable failures.

Future protocol and transport failures should expose enough detail for telemetry and diagnostics without coupling low-level code to UI.

## File Organization

Application code belongs under `app/src/main/java/io/warpnect`.

Native SCL headers belong under `app/src/main/cpp/include`.

Native SCL source belongs under `app/src/main/cpp/src`.

JNI glue belongs at the native boundary and should stay isolated from transport source files.

## Documentation

Architecture changes must update the relevant document in `docs`.

Changes that violate or modify `ArchitectureInvariants.md` require an explicit architecture decision.
