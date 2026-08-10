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
|-- platform/video/decoder/
|-- platform/video/render/
|-- platform/video/transport/
|-- shizuku/
|-- video/session/
|-- video/decoder/
|-- video/encoder/
|-- video/render/
|-- video/transport/
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
- `video/decoder/`: app-facing hardware AVC decoder contracts, pull-based input source, output Surface policy values, capability values, lifecycle state, snapshots, format plans, and controller-core logic.
- `platform/video/decoder/`: Android `MediaCodec` AVC decoder discovery, MediaFormat translation, output-format extraction, monotonic clock access, and Surface-output decoder controller.
- `video/render/`: app-facing low-latency renderer contracts, aspect-fit geometry, Surface target values, render policy, frame-rate hint planning, lifecycle state, snapshots, and controller-core logic.
- `platform/video/render/`: Android `SurfaceView` render target, SurfaceHolder lifecycle controller, and Surface frame-rate hint application.
- `video/transport/`: app-facing encoded-video transport contracts, typed errors/results, snapshots, and `SclEncodedVideoSink`.
- `platform/video/transport/`: native-backed SCL video transport/receiver controllers, sender control runtime, and opaque native handle ownership.
- `video/session/`: RFC-002F transmitter and receiver session orchestration, lifecycle snapshots, rollback/error mapping, and receiver prerequisite/keyframe state core.
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

## Android Video Transport Source Tree

```text
app/src/main/java/io/warpnect/video/transport/
app/src/main/java/io/warpnect/platform/video/transport/
```

Responsibilities:

- `VideoTransportConfig.kt`: explicit numeric endpoint, datagram budget, sequence, retransmission-cache, and optional FEC configuration.
- `VideoTransportError.kt`, `VideoTransportResult.kt`, `VideoTransportState.kt`, `VideoTransportSnapshot.kt`: typed local transport model.
- `EncodedVideoTransportBackend.kt`: synchronous boundary used by the encoded sink.
- `SclEncodedVideoSink.kt`: RFC-002B `EncodedVideoSink` implementation that forwards output formats and borrowed direct `ByteBuffer` access units to SCL transport.
- `NativeSclVideoTransportController.kt`: platform controller that creates/destroys the native sender handle through `NativeBridge.kt`.

Production transport does not copy a complete access unit into a Kotlin `ByteArray`, does not serialize SCL video wire format in Kotlin, and does not create an unbounded video queue.

## Android Video Decoder Source Tree

```text
app/src/main/java/io/warpnect/video/decoder/
app/src/main/java/io/warpnect/platform/video/decoder/
```

Responsibilities:

- `VideoDecoderController.kt` / `VideoDecoderResult.kt`: app-facing decoder lifecycle abstraction and typed result values.
- `VideoDecoderConfig.kt`: AVC decoder configuration, active configuration generation, exact CSD entries, and optional max input size.
- `VideoDecoderInput.kt`: pull-based source contract invoked with MediaCodec-owned input buffers.
- `DecodedVideoOutput.kt`: decoded frame metadata and immediate render/drop/scheduled-release decisions. It never carries raw pixels.
- `VideoDecoderCandidate.kt`, `VideoDecoderCapabilities.kt`: hardware AVC capability and deterministic selection model.
- `VideoDecoderFormatPlan.kt`: pure decoder MediaFormat planning for MIME, dimensions, CSD, optional max input size, and optional low-latency request.
- `VideoDecoderControllerCore.kt`, `AvailableInputSlotTracker.kt`: testable lifecycle, telemetry, generation validation, output counters, and bounded input-index retention.
- `AndroidVideoDecoderDiscovery.kt`: Android `MediaCodecList`/`MediaCodecInfo` decoder discovery and hardware/low-latency capability extraction.
- `AndroidVideoDecoderFormatFactory.kt`: thin Android `MediaFormat` translation for exact CSD, optional max input size, and `KEY_LOW_LATENCY` when supported.
- `AndroidMediaCodecVideoDecoder.kt`: asynchronous `MediaCodec` controller running on `WarpnectVideoDecoder`.
- `VideoDecoderOutputFormatExtractor.kt`: output format diagnostics.

Production decoding is Android/Kotlin platform integration only. It consumes complete compressed AVC access units through MediaCodec-owned input buffers and emits decoded frames to a caller-owned `Surface`. It does not parse SCL packets, perform FEC/NACK, use JNI, keep an encoded payload queue, read raw output pixels, or implement renderer policy.

## Android Video Render Source Tree

```text
app/src/main/java/io/warpnect/video/render/
app/src/main/java/io/warpnect/platform/video/render/
app/src/main/java/io/warpnect/ui/WarpnectVideoSurface.kt
```

Responsibilities:

- `VideoRenderController.kt`: app-facing render lifecycle abstraction and RFC-002D `DecodedVideoSink` exposure.
- `VideoRenderGeometry.kt`: pure aspect-fit rectangle calculation.
- `VideoRenderPolicy.kt`: immediate, drop, and receiver-local scheduled render decisions.
- `VideoFrameRateHint.kt`: pure preferred-frame-rate validation and planning.
- `VideoRenderTarget.kt`: borrowed Surface target and listener events.
- `VideoRenderSnapshot.kt`, `VideoRenderError.kt`, `VideoRenderState.kt`, `VideoRenderResult.kt`: typed renderer model.
- `VideoRenderControllerCore.kt`: testable Surface generation, state, telemetry, geometry, and decision accounting.
- `AndroidVideoRenderController.kt`: Android SurfaceHolder lifecycle and `DecodedVideoSink` adapter.
- `WarpnectVideoSurfaceView.kt`: production `SurfaceView` render target with aspect-aware measurement.
- `AndroidFrameRateHintApplier.kt`: public Android `Surface.setFrameRate` API usage with non-disruptive strategy where available.
- `WarpnectVideoSurface.kt`: minimal Compose `AndroidView` adapter.

Production rendering presents decoded MediaCodec output through a `SurfaceView` Surface. It does not use TextureView fallback, ImageReader, Bitmap/YUV conversion, OpenGL/Vulkan, decoded-frame queues, SCL parsing, or JNI.

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
- `video_protocol.h`, `video_result.h`: Version 1 video payload constants, StreamConfig/AccessUnit parsing, CSD cursor, and typed video transport errors.
- `video_packetizer.h`: segmented video logical-payload packetizer for header-plus-borrowed-AU sources.
- `video_transport.h`: caller-driven SCL video sender composed from packetization, UDP, retransmission cache, optional FEC, and telemetry.
- `video_receiver_runtime.h`: bounded SCL video receiver runtime composed from UDP receive, FEC/NACK, multi-message reassembly, Video Payload V1 parsing, ready-slot ownership, and telemetry.
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

Current JVM tests also include encoded-video transport sink validation for output-format submission, direct-buffer requirements, buffer ranges, keyframe metadata, PTS forwarding, transport error propagation, and ByteBuffer position/limit preservation.

Current JVM tests also include decoder candidate selection, format planning, CSD validation, lifecycle/core state, config-generation checks, input-size checks, PTS preservation, output-action accounting, codec diagnostics, drain-timeout handling, bounded input-index retention, and output-release mapping.

Current JVM tests also include renderer aspect-fit geometry, Surface generation/state, close behavior, immediate/scheduled render policy, frame-rate validation/planning, and render-decision telemetry.

Current JVM tests also include RFC-002F receiver session prerequisite/keyframe/surface state transitions and transmitter partial-start rollback coverage.

Current Android instrumentation tests include a privileged capture first-frame smoke test that skips explicitly when Shizuku/backend prerequisites are unavailable.

Current Android instrumentation tests also include a synthetic EGL Surface producer feeding `MediaCodec`, plus a Shizuku-gated RFC-002A capture-to-encoder integration test that skips explicitly when device prerequisites are unavailable.

Current Android instrumentation tests also include a synthetic RFC-002B encoder-to-RFC-002D decoder round trip that renders to a test `SurfaceTexture` Surface and skips explicitly when hardware codec prerequisites are unavailable.

Current Android instrumentation tests also include RFC-002E `SurfaceView` lifecycle coverage that publishes a valid Surface target and observes Surface destruction when a device/emulator is available.

Current Android instrumentation tests also include RFC-002F SCL loopback coverage: one sender/receiver boundary test submits StreamConfig and an encoded AU over 127.0.0.1 UDP, then fills a direct decoder-style input buffer from native receiver storage; another generic device-gated test composes synthetic encoder output, SCL loopback transport, native receiver runtime, hardware decoder, and SurfaceView renderer.

Current native tests include header smoke coverage, packet foundation tests, UDP localhost transport tests, fragmentation/reassembly tests, loss/NACK/recovery tests, Reed-Solomon FEC tests, clock synchronization/network telemetry tests, Phase 1 full-pipeline integration tests, RFC-002C video protocol/transport tests, and RFC-002F video receiver runtime tests.

`native/test_support/` contains host-only deterministic support code, including the scripted network impairment simulator used by integration tests and benchmarks. It is not compiled into the Android `scl_core` target.

`native/benchmarks/` contains the host-only Phase 1 benchmark runner and the `scl_phase1_benchmarks` executable. Benchmarks are explicit/manual and are not part of Android production builds.

## CI Layout

```text
.github/workflows/android.yml
```

CI builds debug, runs ktlint, runs Android lint, runs unit tests, and compiles the native header smoke target.
