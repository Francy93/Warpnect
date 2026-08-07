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
│   ├── datagram_limits.h
│   ├── fragment_result.h
│   ├── fragmentation.h
│   ├── loss_detector.h
│   ├── monotonic_time.h
│   ├── native_bridge.h
│   ├── packet_codec.h
│   ├── packet_result.h
│   ├── protocol.h
│   ├── reassembly.h
│   ├── recovery_control.h
│   ├── recovery_result.h
│   ├── retransmission_cache.h
│   ├── sequence_number.h
│   ├── telemetry.h
│   ├── udp_endpoint.h
│   ├── udp_engine.h
│   ├── udp_result.h
│   └── udp_socket.h
└── src/
    ├── fragmentation.cpp
    ├── loss_detector.cpp
    ├── internal/
    │   ├── byte_order.h
    │   ├── socket_platform.h
    │   ├── socket_platform_posix.cpp
    │   └── socket_platform_windows.cpp
    ├── monotonic_time.cpp
    ├── packet_codec.cpp
    ├── reassembly.cpp
    ├── recovery_control.cpp
    ├── retransmission_cache.cpp
    ├── udp_endpoint.cpp
    ├── udp_socket.cpp
    └── native_stub.cpp
```

Responsibilities:

- `datagram_limits.h`: shared structural datagram size limits used by transport and fragmentation planning.
- `clock_sync.h`: bounded pending exchange tracking, clock sample calculation, affine clock model fitting, timestamp conversion, and one-way-delay estimation.
- `clock_sync_control.h`: Version 1 SessionControl clock sync request and response payload codec.
- `fec.h`: bounded FEC block encoder and recovery block APIs over encoded SCL datagrams.
- `fec_control.h`: Version 1 SessionControl FEC parity payload codec.
- `fec_result.h`: typed FEC and Reed-Solomon result values.
- `protocol.h`: SCL packet structures and protocol constants.
- `fragment_result.h`: typed fragmentation and reassembly result values.
- `fragmentation.h`: zero-copy fragmentation planning and cursor API.
- `loss_detector.h`: bounded caller-driven sequence gap tracking and NACK scheduling.
- `reassembly.h`: bounded caller-owned reassembly slot API.
- `recovery_control.h`: Version 1 SessionControl NACK payload codec and requested sequence cursor.
- `recovery_result.h`: typed recovery, sequence, NACK, and retransmission result values.
- `reed_solomon.h`: systematic Reed-Solomon codec API over caller-owned equal-size shards.
- `retransmission_cache.h`: bounded caller-owned exact-datagram retransmission cache.
- `sequence_number.h`: wrap-safe 32-bit sequence arithmetic helpers.
- `timing_result.h`: typed timing, clock synchronization, and telemetry result values.
- `packet_codec.h`: SCL packet validation, serialization, and decoding API.
- `packet_result.h`: typed packet result and error values.
- `monotonic_time.h`: local monotonic timestamp value helper.
- `telemetry.h`: SCL timing structures, saturating telemetry counters, rolling sample windows, jitter, and immutable snapshots.
- `udp_endpoint.h`: fixed-size platform-neutral IP address and UDP endpoint values.
- `udp_result.h`: typed UDP transport status and result values.
- `udp_socket.h`: move-only non-blocking UDP socket abstraction.
- `udp_engine.h`: compatibility umbrella for the public UDP transport headers.
- `native_bridge.h`: native bridge surface for Kotlin/JNI.
- `jni_bridge.cpp`: JNI glue only.
- `native_stub.cpp`: compileable Phase 0 native definitions.
- `src/internal/byte_order.h`: private endian-safe byte-order helpers.
- `src/internal/gf256.h`: private GF(256) arithmetic tables using primitive polynomial `0x11D`.
- `src/internal/gf256_matrix.h`: private bounded matrix helpers for Reed-Solomon coding and recovery.
- `src/internal/socket_platform.h`: private platform socket adapter boundary.
- `src/internal/socket_platform_posix.cpp`: POSIX/Android UDP backend.
- `src/internal/socket_platform_windows.cpp`: Windows host-native UDP backend for tests.
- `src/packet_codec.cpp`: SCL packet foundation implementation.
- `src/clock_sync.cpp`: SCL clock sample calculation, pending exchange tracking, affine model fitting, and timestamp conversion implementation.
- `src/clock_sync_control.cpp`: SCL clock sync request and response payload encoding and decoding.
- `src/fragmentation.cpp`: SCL fragmentation planning and cursor implementation.
- `src/reassembly.cpp`: SCL bounded reassembly implementation.
- `src/loss_detector.cpp`: SCL loss detection and NACK scheduling implementation.
- `src/recovery_control.cpp`: SCL NACK payload encoding, decoding, and sequence iteration.
- `src/reed_solomon.cpp`: systematic Reed-Solomon encoding and erasure recovery implementation.
- `src/fec_control.cpp`: FEC parity control payload encoding, decoding, and datagram budget helpers.
- `src/fec.cpp`: SCL FEC block assembly and recovery implementation.
- `src/retransmission_cache.cpp`: SCL bounded retransmission cache implementation.
- `src/monotonic_time.cpp`: local monotonic timestamp helper implementation.
- `src/telemetry.cpp`: SCL rolling statistics, telemetry counters, snapshots, and legacy media timing helper implementation.
- `src/udp_endpoint.cpp`: numeric address parsing dispatch.
- `src/udp_socket.cpp`: public UDP socket lifecycle and validation.

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

Current native tests include header smoke coverage, packet foundation tests, UDP localhost transport tests, fragmentation/reassembly tests, loss/NACK/recovery tests, Reed-Solomon FEC tests, and clock synchronization/network telemetry tests.

## CI Layout

```text
.github/workflows/android.yml
```

CI builds debug, runs ktlint, runs Android lint, runs unit tests, and compiles the native header smoke target.
