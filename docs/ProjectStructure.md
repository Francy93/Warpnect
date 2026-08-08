# Project Structure

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines the current repository layout and intended responsibility boundaries.

## Root

```text
.
|-- .github/
|-- app/
|-- docs/
|-- gradle/
|-- native/
|-- build.gradle.kts
|-- gradlew
|-- gradlew.bat
|-- settings.gradle.kts
|-- gradle.properties
`-- .gitignore
```

Root Gradle files declare the Android application build. The root does not contain application logic.

The Gradle wrapper pins the build runtime to Gradle 8.12.

## Android App Module

```text
app/
|-- build.gradle.kts
|-- proguard-rules.pro
`-- src/main/
    |-- AndroidManifest.xml
    |-- aidl/
    |-- cpp/
    |-- java/io/warpnect/
    `-- res/
```

The app module is the only current Gradle module. Additional modules may be introduced later only when they reduce build or ownership complexity.

## Kotlin Source Tree

```text
app/src/main/java/io/warpnect/
|-- MainActivity.kt
|-- CoreOrchestrator.kt
|-- NativeBridge.kt
|-- audio/
|-- capture/
|-- codec/
|-- input/
|-- network/
|-- platform/capture/
|-- platform/video/encoder/
|-- shizuku/
|-- video/encoder/
`-- ui/
```

Responsibilities:

- `MainActivity.kt`: Android entry point.
- `CoreOrchestrator.kt`: Warpnect role state machine and app-level service ownership.
- `NativeBridge.kt`: Kotlin-side JNI boundary, not protocol logic.
- `capture/`: app-facing privileged video capture contracts, typed state/errors, request/result values, snapshots, and pure geometry helpers.
- `platform/capture/`: Android Shizuku/UserService capture integration, display configuration monitoring, and hidden framework adapter ownership.
- `video/encoder/`: app-facing hardware AVC encoder contracts, capability values, lifecycle state, snapshots, format plans, and controller-core logic.
- `platform/video/encoder/`: Android `MediaCodec` AVC encoder discovery, MediaFormat translation, output-format extraction, monotonic clock access, and Surface-input encoder controller.
- `ui/`: Compose UI.
- `shizuku/`: app-level Shizuku availability and permission helper.
- `network/`: future discovery/session bootstrap stubs, not UDP transport implementation.
- `codec/`: future video pipeline stubs.
- `audio/`: future audio pipeline stubs.
- `input/`: future reverse-input stubs.

## Android Capture Source Tree

```text
app/src/main/aidl/io/warpnect/platform/capture/privileged/
app/src/main/java/io/warpnect/capture/
app/src/main/java/io/warpnect/platform/capture/
app/src/main/java/io/warpnect/platform/capture/privileged/
```

Responsibilities:

- `IPrivilegedCaptureService.aidl`: Binder control plane for capabilities, start, update, stop, and state. It passes a setup-time `Surface`, never frame bytes.
- `VideoCaptureController.kt`: app-facing capture lifecycle abstraction.
- `CaptureRequest.kt`, `CaptureResult.kt`, `CaptureState.kt`, `CaptureError.kt`, `CaptureCapabilities.kt`, `CaptureSessionSnapshot.kt`: typed capture model.
- `CaptureGeometry.kt`: pure aspect-preserving projection helper.
- `AndroidVideoCaptureController.kt`: app-process lifecycle facade.
- `ShizukuCaptureGateway.kt`: Shizuku permission, UserService binding, remote service calls, and binder death mapping.
- `DisplayConfigurationMonitor.kt`: low-frequency display change/removal monitoring.
- `PrivilegedCaptureUserService.kt`: Shizuku UserService Binder endpoint.
- `SurfaceControlDisplayCaptureApi.kt`: isolated hidden Android display API reflection for continuous Surface capture.

Production capture is Android/Kotlin platform integration only. It is not part of the C++ SCL core and does not use JNI.

## Android Video Encoder Source Tree

```text
app/src/main/java/io/warpnect/video/encoder/
app/src/main/java/io/warpnect/platform/video/encoder/
```

Responsibilities:

- `VideoEncoderController.kt`: app-facing encoder lifecycle abstraction.
- `EncodedVideoSink.kt`: synchronous borrowed `ByteBuffer` output contract.
- `VideoEncoderRequest.kt`, `VideoCodec.kt`, `VideoEncoderResult.kt`, `VideoEncoderError.kt`, `VideoEncoderSnapshot.kt`: typed AVC encoder model.
- `VideoEncoderCandidate.kt`, `VideoEncoderCapabilities.kt`: hardware AVC capability and deterministic selection model.
- `VideoEncoderFormatPlan.kt`: pure low-latency AVC Surface-input MediaFormat planning.
- `VideoEncoderControllerCore.kt`: testable lifecycle, counters, output-format state, PTS ordering, and control precondition logic.
- `AndroidVideoEncoderDiscovery.kt`: Android `MediaCodecList`/`MediaCodecInfo` discovery and capability extraction.
- `AndroidVideoEncoderFormatFactory.kt`: thin Android `MediaFormat` translation for Surface input, CBR, realtime priority, latency, no-B-frame, and max-FPS hints.
- `AndroidMediaCodecVideoEncoder.kt`: asynchronous `MediaCodec` controller running on `WarpnectVideoEncoder`.
- `VideoEncoderOutputFormatExtractor.kt`: output format and CSD extraction.

Production encoding is Android/Kotlin platform integration only. It consumes raw frames through a `MediaCodec` input `Surface` and exposes encoded access units as borrowed codec buffers. It does not use JNI, SCL packetization, UDP, a decoder, or a renderer.

## Native Source Tree

```text
app/src/main/cpp/
|-- CMakeLists.txt
|-- jni_bridge.cpp
|-- include/
`-- src/
```

The native shared library target is `scl_core`.

Native responsibilities:

- `protocol.h`, `packet_codec.h`, `packet_result.h`: SCL packet constants, runtime structures, validation, encoding, and decoding.
- `udp_endpoint.h`, `udp_socket.h`, `udp_engine.h`, `udp_result.h`: platform-neutral UDP values and non-blocking socket abstraction.
- `fragmentation.h`, `fragment_result.h`, `reassembly.h`, `datagram_limits.h`: fragmentation planning and bounded reassembly.
- `sequence_number.h`, `loss_detector.h`, `recovery_control.h`, `recovery_result.h`, `retransmission_cache.h`: bounded loss detection, NACK payloads, and exact-datagram retransmission cache.
- `reed_solomon.h`, `fec.h`, `fec_control.h`, `fec_result.h`: systematic Reed-Solomon FEC and SessionControl parity payloads.
- `clock_sync.h`, `clock_sync_control.h`, `timing_result.h`: four-timestamp synchronization, affine clock model, timestamp conversion, and clock control payloads.
- `telemetry.h`: bounded counters, rolling statistics, jitter, and immutable snapshots.
- `native_bridge.h`, `jni_bridge.cpp`: JNI glue only.
- `src/internal/`: private byte-order, socket-platform, GF(256), and matrix helpers.

SCL C++ owns protocol, timing, telemetry, and transport primitives. It does not own Android display capture, Shizuku integration, Compose, or app lifecycle.

## Future Expansion Points

Future code should expand along existing responsibility boundaries:

- SCL transport implementation below `app/src/main/cpp/src`.
- SCL tests in host-native targets under `native/`.
- Android capture, codec, renderer, audio, and input adapters in Kotlin packages with clear ownership.
- Cross-platform SCL extraction into a standalone native module only when desktop work begins.

No future phase should collapse SCL protocol logic into Kotlin or Android lifecycle logic into C++.

## Test Layout

```text
app/src/test/
app/src/androidTest/
native/tests/
native/test_support/
native/benchmarks/
```

Current JVM tests include capture state-machine, validation, rollback, binder death, capability mapping, and geometry coverage.

Current JVM tests also include encoder candidate selection, format planning, lifecycle/core state, output metadata, keyframe metadata, bitrate/keyframe control preconditions, codec diagnostics, and drain-timeout coverage.

Current Android instrumentation tests include a privileged capture first-frame smoke test that skips explicitly when Shizuku/backend prerequisites are unavailable.

Current Android instrumentation tests also include a synthetic EGL Surface producer feeding `MediaCodec`, plus a Shizuku-gated RFC-002A capture-to-encoder integration test that skips explicitly when device prerequisites are unavailable.

Current native tests include header smoke coverage, packet foundation tests, UDP localhost transport tests, fragmentation/reassembly tests, loss/NACK/recovery tests, Reed-Solomon FEC tests, clock synchronization/network telemetry tests, and Phase 1 full-pipeline integration tests.

`native/test_support/` contains host-only deterministic support code, including the scripted network impairment simulator used by integration tests and benchmarks. It is not compiled into the Android `scl_core` target.

`native/benchmarks/` contains the host-only Phase 1 benchmark runner and the `scl_phase1_benchmarks` executable. Benchmarks are explicit/manual and are not part of Android production builds.

## CI Layout

```text
.github/workflows/android.yml
```

CI builds debug, runs ktlint, runs Android lint, runs unit tests, and compiles the native header smoke target.
