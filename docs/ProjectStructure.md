# Project Structure

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines the current repository layout and intended responsibility boundaries.

## Root

```text
.
├── .github/
├── app/
├── docs/
├── gradle/
├── native/
├── build.gradle.kts
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
├── gradle.properties
└── .gitignore
```

Root Gradle files declare the Android application build. The root does not contain application logic.

The Gradle wrapper pins the build runtime to Gradle 8.12.

## Android App Module

```text
app/
├── build.gradle.kts
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── cpp/
    ├── java/io/warpnect/
    └── res/
```

The app module is the only current Gradle module. Additional modules may be introduced later only when they reduce build or ownership complexity.

## Kotlin Source Tree

```text
app/src/main/java/io/warpnect/
├── MainActivity.kt
├── CoreOrchestrator.kt
├── NativeBridge.kt
├── audio/
├── codec/
├── input/
├── network/
├── shizuku/
└── ui/
```

Responsibilities:

- `MainActivity.kt`: Android entry point.
- `CoreOrchestrator.kt`: Warpnect role state machine.
- `NativeBridge.kt`: Kotlin-side JNI boundary, not protocol logic.
- `ui/`: Compose UI.
- `shizuku/`: future privileged access bridge.
- `network/`: future discovery/session bootstrap stubs, not UDP transport implementation.
- `codec/`: future video pipeline stubs.
- `audio/`: future audio pipeline stubs.
- `input/`: future reverse-input stubs.

## Native Source Tree

```text
app/src/main/cpp/
├── CMakeLists.txt
├── jni_bridge.cpp
├── include/
│   ├── native_bridge.h
│   ├── protocol.h
│   ├── telemetry.h
│   └── udp_engine.h
└── src/
    └── native_stub.cpp
```

Responsibilities:

- `protocol.h`: SCL packet structures and protocol constants.
- `telemetry.h`: SCL timing structures and helper declarations.
- `udp_engine.h`: platform-independent UDP engine interface.
- `native_bridge.h`: native bridge surface for Kotlin/JNI.
- `jni_bridge.cpp`: JNI glue only.
- `native_stub.cpp`: compileable Phase 0 native definitions.

The native shared library target is `scl_core`.

## Future Expansion Points

Future code should expand along existing responsibility boundaries:

- SCL transport implementation below `app/src/main/cpp/src`.
- SCL tests in a separate native test target when introduced.
- Android platform adapters in Kotlin packages with clear ownership.
- Cross-platform SCL extraction into a standalone native module only when desktop work begins.

No future phase should collapse SCL protocol logic into Kotlin or Android lifecycle logic into C++.

## Test Layout

```text
app/src/test/
app/src/androidTest/
native/tests/
```

The current tests are infrastructure smoke tests only.

## CI Layout

```text
.github/workflows/android.yml
```

CI builds debug, runs ktlint, runs Android lint, runs unit tests, and compiles the native header smoke target.
