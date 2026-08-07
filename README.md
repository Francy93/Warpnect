# Warpnect

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect is an Android-first ultra-low-latency remote presence project powered by the State Coherence Layer (SCL).

Warpnect is the application layer. It owns Android lifecycle, Compose UI, application state, platform integration, and user-facing orchestration.

SCL is the protocol layer. It owns packet foundations, transport abstractions, timing, telemetry concepts, and the future cross-platform real-time core.

## Current Status

This repository contains the frozen architecture baseline plus the first SCL Phase 1 foundations.

Present:

- Android Kotlin application skeleton.
- Compose entry surface.
- Warpnect role state machine.
- Shizuku bridge stub.
- SCL C++20 packet codec foundation.
- SCL UDP transport engine.
- SCL fragmentation and reassembly primitives.
- SCL loss detection, NACK, and retransmission cache primitives.
- SCL Reed-Solomon FEC primitives.
- SCL clock synchronization and bounded network telemetry primitives.
- JNI bridge skeleton.
- Formatting, lint, CI, and test infrastructure.
- Architecture Version 1.0 documentation.

Not implemented:

- Adaptive RTT-driven transport policy, pacing, congestion control, or bitrate control.
- Video, audio, discovery, telemetry UI/wire streaming, rendering, or input injection.

## Repository Layout

```text
.
├── app/
│   └── src/main/
│       ├── java/io/warpnect/
│       └── cpp/
├── docs/
│   └── adr/
├── native/
│   └── tests/
├── .github/workflows/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Build Requirements

Recommended baseline:

- Android Studio Ladybug 2024.2.1 or newer.
- JDK 17.
- Gradle 8.12 through the committed Gradle wrapper.
- Android Gradle Plugin 8.7.3.
- Kotlin 2.0.21.
- Android SDK platform 35.
- Android NDK 27.0.12077973.
- CMake 3.22.1.

See [BuildEnvironment.md](docs/BuildEnvironment.md) for details.

## Android SDK Setup

Create a local SDK configuration that is not committed:

```properties
sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

Alternatively set `ANDROID_HOME` or `ANDROID_SDK_ROOT`.

Install:

```text
platforms;android-35
build-tools;34.0.0
ndk;27.0.12077973
cmake;3.22.1
```

## Build Commands

Debug build:

```powershell
.\gradlew.bat :app:assembleDebug
```

Release build without signing/publishing:

```powershell
.\gradlew.bat :app:assembleRelease
```

Unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Android lint:

```powershell
.\gradlew.bat :app:lintDebug
```

Kotlin style:

```powershell
.\gradlew.bat :app:ktlintCheck
```

Native host build and tests:

```powershell
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native/build --config Debug
ctest --test-dir native/build -C Debug --output-on-failure
```

## Native Build Overview

The Android native library target is:

```text
scl_core
```

Android loads it as:

```kotlin
System.loadLibrary("scl_core")
```

The produced Android shared library is `libscl_core.so`.

## Documentation Index

- [Architecture](docs/Architecture.md)
- [Architecture Invariants](docs/ArchitectureInvariants.md)
- [Architecture Version](docs/ArchitectureVersion.md)
- [Build Environment](docs/BuildEnvironment.md)
- [Coding Standards](docs/CodingStandards.md)
- [Decision Records](docs/DecisionRecords.md)
- [Future Roadmap](docs/FutureRoadmap.md)
- [Glossary](docs/Glossary.md)
- [Native Boundary](docs/NativeBoundary.md)
- [Project Structure](docs/ProjectStructure.md)
- [Protocol Design](docs/ProtocolDesign.md)
- [Repository Rules](docs/RepositoryRules.md)
- [RFC-001A Packet Foundation](docs/rfc/RFC-001A-SCL-Packet-Runtime-Foundation.md)
- [RFC-001B UDP Transport](docs/rfc/RFC-001B-SCL-UDP-Transport-Engine.md)
- [RFC-001C Fragmentation and Reassembly](docs/rfc/RFC-001C-SCL-Fragmentation-Reassembly.md)
- [RFC-001D Loss, NACK and Recovery](docs/rfc/RFC-001D-SCL-Loss-NACK-Recovery.md)
- [RFC-001E Reed-Solomon FEC](docs/rfc/RFC-001E-SCL-Reed-Solomon-FEC.md)
- [RFC-001F Clock Synchronization and Network Telemetry](docs/rfc/RFC-001F-SCL-Clock-Synchronization-Network-Telemetry.md)
- [SCL Protocol Principles](docs/SCLProtocolPrinciples.md)
- [State Management](docs/StateManagement.md)
- [Thread Model](docs/ThreadModel.md)
- [Versioning](docs/Versioning.md)
- [Warpnect and SCL](docs/WarpnectAndSCL.md)

## Roadmap Summary

The next RFC is:

```text
RFC-001G - Phase 1 Integration & Benchmarks
```

Future RFCs must preserve Architecture Version 1.0 unless an ADR explicitly changes it.
