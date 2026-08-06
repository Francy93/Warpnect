# Warpnect

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect is an Android-first ultra-low-latency remote presence project powered by the State Coherence Layer (SCL).

Warpnect is the application layer. It owns Android lifecycle, Compose UI, application state, platform integration, and user-facing orchestration.

SCL is the protocol layer. It owns packet foundations, transport abstractions, timing, telemetry concepts, and the future cross-platform real-time core.

## Current Status

This repository is pre-implementation infrastructure.

Present:

- Android Kotlin application skeleton.
- Compose entry surface.
- Warpnect role state machine.
- Shizuku bridge stub.
- SCL C++20 header skeleton.
- JNI bridge skeleton.
- Formatting, lint, CI, and test infrastructure.
- Architecture Version 1.0 documentation.

Not implemented:

- UDP sockets.
- Packet serialization.
- Fragmentation or reassembly.
- FEC or NACK.
- Clock synchronization.
- Video, audio, discovery, telemetry, rendering, or input injection.

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

Native header smoke build:

```powershell
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native/build --config Debug
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
- [State Management](docs/StateManagement.md)
- [Thread Model](docs/ThreadModel.md)
- [Versioning](docs/Versioning.md)
- [Warpnect and SCL](docs/WarpnectAndSCL.md)

## Roadmap Summary

The next RFC is:

```text
RFC-001 - SCL Core Runtime & Packet Foundation
```

Future RFCs must preserve Architecture Version 1.0 unless an ADR explicitly changes it.
