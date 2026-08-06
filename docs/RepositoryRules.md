# Repository Rules

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines permanent repository hygiene and development rules.

## Generated Files

Do not commit generated build outputs.

Forbidden under version control:

- `.gradle/`
- `.idea/`
- `.cxx/`
- `.externalNativeBuild/`
- `build/`
- `app/build/`
- `native/build/`
- `captures/`
- APK or AAB outputs.
- Local SDK files such as `local.properties`.

The Gradle wrapper is allowed and required for reproducible builds.

Gradle wrapper distribution checksums must remain pinned.

## Architecture Changes

Architecture Version 1.0 is frozen.

Changes to Warpnect/SCL separation, Android package root, native namespace root, native library name, or transport philosophy require an ADR.

## Protocol Changes

All protocol changes require an RFC.

Breaking packet layout or protocol semantic changes require a Protocol Version increment.

## Native Boundary

JNI remains bridge-only.

JNI must not contain networking, protocol logic, application state decisions, or UI behavior.

## SCL Independence

SCL remains platform independent.

SCL headers must not include Android, JNI, Compose, Activity, or Shizuku concepts.

## Warpnect State Ownership

Warpnect owns application state.

The orchestrator owns user-facing session state and role transitions.

SCL may expose protocol status and telemetry upward, but it must not own product state.

## Build Quality

New code must pass:

- Gradle build.
- Android lint.
- ktlint.
- Unit tests.
- Native header smoke build.

Warnings are warnings in RFC-000D. Future RFCs may introduce stricter gates.
