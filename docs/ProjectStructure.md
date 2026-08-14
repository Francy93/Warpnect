# Project Structure

## RFC-004F Reverse Input

```text
input/session/
  ReverseInputSenderSessionController
  ReverseInputReceiverSessionController
  ReverseInputSessionTypes

input/transport/
  InputReceiverRuntime
  InputReceiverBridgeDecoder

platform/input/transport/
  NativeSclInputReceiverController

native/
  input_receiver_runtime.h/.cpp
  scl_input_receiver_runtime_tests
```

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
|-- audio/decoder/
|-- audio/encoder/
|-- audio/performance/
|-- audio/playback/
|-- avsync/
|-- capture/
|-- codec/
|-- input/
|-- input/capture/
|-- input/model/
|-- input/performance/
|-- input/reliability/
|-- network/
|-- platform/capture/
|-- platform/audio/capture/
|-- platform/audio/decoder/
|-- platform/audio/encoder/
|-- platform/audio/playback/
|-- platform/audio/transport/
|-- platform/input/capture/
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
|-- ui/
`-- ui/input/
```

Responsibilities:

- `MainActivity.kt`: Android entry point.
- `CoreOrchestrator.kt`: Warpnect role state machine and app-level service ownership.
- `NativeBridge.kt`: Kotlin-side JNI boundary, not protocol logic.
- `capture/`: app-facing privileged video capture contracts, typed state/errors, request/result values, snapshots, and pure geometry helpers.
- `platform/capture/`: Android Shizuku/UserService capture integration, display configuration monitoring, and hidden framework adapter ownership.
- `audio/capture/`: app-facing PCM audio capture contracts, source model, format, synchronous borrowed sink, typed errors/results, lifecycle snapshots, chunk sizing, timestamp tracking, and controller-core logic.
- `audio/decoder/`: app-facing Opus audio decoder contracts, decode metadata, decoded PCM sink, format/snapshot/error/state values, and validation.
- `audio/encoder/`: app-facing Opus audio encoder contracts, request/format/snapshot/error/state values, borrowed encoded sink, capability validation, and timing helpers.
- `audio/performance/`: RFC-003H Phase 3 audio performance profile, recovery-policy selection, freshness bounds, playback/A/V tuning values, validation, and snapshot projection into existing subsystem configs.
- `audio/playback/`: app-facing low-latency PCM playback contracts, decoded PCM submission metadata, sharing policy, snapshots, presentation timestamp results, typed errors, and validation.
- `audio/session/`: RFC-003F transmitter and receiver audio session orchestration, start/stop rollback, StreamConfig handling, sample-position ordering, PLC gap policy, freshness reset policy, and composed snapshots.
- `audio/transport/`: app-facing SCL Audio Payload V1 transport contracts, typed errors/results, snapshots, and `SclEncodedAudioSink`.
- `avsync/`: RFC-003G metadata-only audio/video synchronization, audio-master presentation model sampling, video timestamp-domain qualification, bounded startup gate, synchronized video render policy, snapshots, and typed sync errors.
- `platform/audio/capture/`: Android `AudioRecord` microphone capture, system-audio Shizuku gateway, app-side SharedMemory drain, audio thread priority helper, and shared PCM ring layout.
- `platform/audio/capture/privileged/`: Shizuku/Sui UserService for privileged system-audio capture, isolated AudioPolicy reflection adapter, AIDL control contract, and Bundle conversion.
- `platform/audio/decoder/`: native-backed Opus decoder controller and fakeable JNI backend seam.
- `platform/audio/encoder/`: native-backed Opus encoder controller, fakeable JNI backend seam, and `PcmAudioSink` adapter.
- `platform/audio/playback/`: native-backed Oboe playback controller, fakeable JNI backend seam, and `DecodedPcmAudioSink` adapter.
- `platform/audio/transport/`: native-backed SCL audio sender and receiver-runtime controllers, source-specific opaque native handle ownership, and `WarpnectAudioReceiver` event pump ownership.
- `video/encoder/`: app-facing hardware AVC encoder contracts, capability values, lifecycle state, snapshots, format plans, and controller-core logic.
- `platform/video/encoder/`: Android `MediaCodec` AVC encoder discovery, MediaFormat translation, output-format extraction, monotonic clock access, and Surface-input encoder controller.
- `video/decoder/`: app-facing hardware AVC decoder contracts, pull-based input source, output Surface policy values, capability values, lifecycle state, snapshots, format plans, and controller-core logic.
- `platform/video/decoder/`: Android `MediaCodec` AVC decoder discovery, MediaFormat translation, output-format extraction, monotonic clock access, and Surface-output decoder controller.
- `video/render/`: app-facing low-latency renderer contracts, aspect-fit geometry, Surface target values, render policy, frame-rate hint planning, lifecycle state, snapshots, and controller-core logic.
- `platform/video/render/`: Android `SurfaceView` render target, SurfaceHolder lifecycle controller, and Surface frame-rate hint application.
- `video/transport/`: app-facing encoded-video transport contracts, typed errors/results, snapshots, and `SclEncodedVideoSink`.
- `platform/video/transport/`: native-backed SCL video transport/receiver controllers, sender control runtime, and opaque native handle ownership.
- `video/session/`: RFC-002F/RFC-002G transmitter and receiver session orchestration, lifecycle snapshots, rollback/error mapping, receiver prerequisite/keyframe state core, performance tuning configuration, resync orchestration, and optional bounded loss-reactive bitrate policy.
- `ui/`: Compose UI.
- `shizuku/`: app-level Shizuku availability and permission helper.
- `network/`: future discovery/session bootstrap stubs, not UDP transport implementation.
- `codec/`: future video pipeline stubs.
- `audio/`: future audio pipeline stubs.
- `input/`: future reverse-input stubs and Phase 4 input-facing contracts.
- `input/capture/`: RFC-004B app-facing input capture contracts, synchronous sink/result types, capture configuration, lifecycle state, typed errors, capabilities, and bounded snapshots.
- `input/model/`: RFC-004A platform-neutral input event model, device kind/message/action enums, validation, delivery classification, and normalized/fixed-point conversion helpers. It contains no Android framework classes and no wire serializer.
- `platform/input/capture/`: RFC-004B Android View-based input capture, raw `KeyEvent`/`MotionEvent` mapping, event-time conversion, bounded device-slot registry, keyboard HID mapper, gamepad state cache, pointer/touch normalization, pointer capture, and `InputDeviceListener` lifecycle handling.
- `ui/input/`: Compose `AndroidView` wrapper for placing the transparent input capture View over a remote-control surface.

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

## Android Audio Capture Source Tree

```text
app/src/main/aidl/io/warpnect/platform/audio/capture/privileged/
app/src/main/java/io/warpnect/audio/capture/
app/src/main/java/io/warpnect/platform/audio/capture/
app/src/main/java/io/warpnect/platform/audio/capture/privileged/
app/src/main/java/io/warpnect/platform/audio/capture/shared/
```

Responsibilities:

- `AudioCaptureController.kt`: app-facing source-specific audio capture lifecycle abstraction.
- `PcmAudioSink.kt`: synchronous borrowed PCM chunk contract.
- `AudioCaptureRequest.kt`, `AudioCaptureFormat.kt`, `AudioCaptureSnapshot.kt`, `AudioCaptureError.kt`: typed PCM capture model.
- `AudioChunkPlanner.kt`: pure target-chunk frame and byte calculation.
- `AudioCaptureTimestampTracker.kt`: monotonic first-frame timestamp tracking and fallback estimation.
- `AudioCaptureControllerCore.kt`: testable lifecycle and counter state.
- `AndroidMicrophoneAudioCaptureController.kt`: ordinary-process `AudioRecord` microphone capture using preallocated direct buffers.
- `AndroidSystemAudioCaptureController.kt`: ordinary-process system-audio controller and app-side SharedMemory drain ownership.
- `SharedPcmAudioRingLayout.kt`: internal PCM Shared Ring Version 1 layout, slot metadata, fixed slot lifecycle, and payload slicing.
- `IPrivilegedAudioCaptureService.aidl`: setup/control-only privileged audio AIDL contract.
- `PrivilegedAudioCaptureUserService.kt`: privileged process system-audio producer loop.
- `ReflectivePrivilegedAudioPolicyCaptureApi.kt`: isolated hidden AudioPolicy loopback+render adapter.

Production audio capture emits PCM only. It does not encode audio, define an SCL audio payload, perform UDP audio transport, play remote audio, calculate A/V sync, move PCM through Binder payloads, allocate per-chunk PCM `ByteArray` values, or maintain an unbounded PCM queue.

## Android Audio Encoder Source Tree

```text
app/src/main/java/io/warpnect/audio/encoder/
app/src/main/java/io/warpnect/platform/audio/encoder/
```

Responsibilities:

- `AudioEncoderController.kt`: app-facing encoded-audio lifecycle abstraction.
- `EncodedAudioSink.kt`: synchronous borrowed raw-Opus packet output contract.
- `AudioEncoderRequest.kt`, `EncodedAudioFormat.kt`, `AudioEncoderSnapshot.kt`, `AudioEncoderError.kt`: typed Opus encoder model.
- `AudioEncoderValidation.kt`: pure Opus-native sample-rate, mono/stereo, low-latency frame-duration, bitrate, and complexity validation.
- `NativeOpusAudioEncoderController.kt`: synchronous Kotlin controller around the native Opus backend.
- `OpusPcmAudioSink.kt`: RFC-003A `PcmAudioSink` adapter that forwards borrowed PCM to the prepared encoder.
- `OpusAudioEncoderBackend.kt`: fakeable backend seam and NativeBridge-backed implementation.

Production audio encoding uses pinned libopus 1.6.1 through portable `warpnect::audio` C++ code. It does not use Android `MediaCodec`, AAC fallback, resampling, channel conversion, SCL audio packetization, UDP, decoding, playback, or an encoder worker queue.

## Android Audio Decoder Source Tree

```text
app/src/main/java/io/warpnect/audio/decoder/
app/src/main/java/io/warpnect/platform/audio/decoder/
```

Responsibilities:

- `AudioDecoderController.kt`: app-facing Opus decoder lifecycle abstraction.
- `DecodedPcmAudioSink.kt`: synchronous borrowed PCM output contract.
- `AudioDecoderConfig.kt`, `DecodedAudioFormat.kt`, `EncodedAudioFrameMetadata.kt`, `MissingAudioFrameMetadata.kt`: typed codec-layer input/output metadata.
- `AudioDecoderSnapshot.kt`, `AudioDecoderError.kt`, `AudioDecoderState.kt`, `AudioDecoderResult.kt`: typed local decoder model.
- `AudioDecoderValidation.kt`: pure Opus-native sample-rate, mono/stereo, low-latency frame-duration, generation, and output-size validation.
- `NativeOpusAudioDecoderController.kt`: synchronous Kotlin controller around the native Opus decoder backend.
- `OpusAudioDecoderBackend.kt`: fakeable backend seam and NativeBridge-backed implementation.

Production audio decoding uses pinned libopus 1.6.1 through portable `warpnect::audio` C++ code. It does not use Android `MediaCodec`, AAC fallback, resampling, channel remixing, SCL parsing, UDP receive orchestration, `AudioTrack`, playback buffering, A/V sync, automatic PLC policy, or a decoder worker queue.

## Android Audio Playback Source Tree

```text
app/src/main/java/io/warpnect/audio/playback/
app/src/main/java/io/warpnect/platform/audio/playback/
```

Responsibilities:

- `AudioPlaybackController.kt`: app-facing PCM playback lifecycle abstraction.
- `AudioPlaybackConfig.kt`, `DecodedPcmMetadata.kt`: source, generation, PCM format, ring capacity, startup threshold, sharing policy, and decoded-frame metadata.
- `AudioPlaybackSnapshot.kt`, `AudioPlaybackError.kt`, `AudioPlaybackState.kt`, `AudioPlaybackResult.kt`: typed local playback model.
- `AudioPresentationTimestampResult.kt`: receiver-local playback timestamp query result.
- `AudioPlaybackValidation.kt`: pure sample-rate, channel, frame-duration, ring-capacity, threshold, and buffer-burst validation.
- `NativeOboeAudioPlaybackController.kt`: synchronous Kotlin controller around the native Oboe playback backend.
- `OboeDecodedPcmAudioSink.kt`: RFC-003D `DecodedPcmAudioSink` adapter that forwards borrowed decoded PCM into playback.
- `OboeAudioPlaybackBackend.kt`: fakeable backend seam and NativeBridge-backed implementation.

Production audio playback uses pinned Oboe 1.10.0 through Android-specific native C++ code. It accepts decoded PCM16 direct buffers, copies once into a bounded native SPSC handoff ring, and lets the Oboe data callback consume that ring. It does not use `AudioTrack`, `MediaPlayer`, `SoundPool`, sample-rate conversion, channel mixing, effects, network receive orchestration, jitter buffering, a playback worker queue, per-frame Kotlin allocation, or JNI from the Oboe callback.

## Android Audio Transport Source Tree

```text
app/src/main/java/io/warpnect/audio/transport/
app/src/main/java/io/warpnect/platform/audio/transport/
```

Responsibilities:

- `AudioTransportConfig.kt`: explicit source, numeric remote endpoint, datagram budget, local port, and initial audio sequence configuration.
- `AudioTransportError.kt`, `AudioTransportResult.kt`, `AudioTransportState.kt`, `AudioTransportSnapshot.kt`: typed local transport model.
- `EncodedAudioTransportBackend.kt`: synchronous boundary used by the encoded audio sink.
- `SclEncodedAudioSink.kt`: RFC-003B `EncodedAudioSink` implementation that submits StreamConfig on format changes and forwards each borrowed direct Opus packet as one SCL AudioFrame.
- `NativeSclAudioTransportController.kt`: platform controller that creates/destroys the native audio sender handle through `NativeBridge.kt`.

Production audio transport does not copy complete encoded Opus packets into Kotlin `ByteArray` values, does not serialize audio wire format in Kotlin, does not batch multiple Opus packets, and does not create an encoded-audio queue or worker thread.

## Audio/Video Synchronization Source Tree

```text
app/src/main/java/io/warpnect/avsync/
```

Responsibilities:

- `AudioVideoSyncController.kt`: optional coordination layer above existing audio and video sessions, low-frequency presentation-anchor sampling, sync model publication, invalidation/reacquisition hooks, and render-policy ownership.
- `AvSyncTypes.kt`: configuration, state, quality, error, model, snapshot, and clock abstractions.
- `VideoTimestampDomainValidator.kt`: bounded audio/video path-offset and rate-compatibility statistics.
- `AvSynchronizedVideoRenderPolicy.kt`: existing `VideoRenderPolicy` implementation that maps qualified video PTS onto the audio presentation timeline.
- `AvSyncPlaybackStartGate.kt`: optional startup-only audio gate that uses existing playback-ring slack and releases before ring overflow.
- `AudioPresentationMath.kt`: integer/rational lookahead and output-frame interpolation helpers.

Production A/V synchronization processes timing metadata only. It does not own media payloads, copy encoded or decoded media, create a network jitter buffer, create a decoded-video queue, resample/time-stretch audio, or introduce a new wire protocol.

## Portable Input Model Source Tree

```text
app/src/main/java/io/warpnect/input/model/
```

Responsibilities:

- `InputModel.kt`: Input Payload V1 semantic enums, session-local device slot validation, keyboard HID usage model, multi-touch contacts, absolute/relative pointer values, scroll values, complete gamepad snapshots, ResetState model, delivery classification, and integer/fixed-point normalization helpers.

The Kotlin model is platform-neutral. It does not depend on Android `InputDevice`, `KeyEvent`, `MotionEvent`, display IDs, pixels, capture callbacks, input injection APIs, JNI, UDP, or SCL wire serialization.

## Android Input Capture Source Tree

```text
app/src/main/java/io/warpnect/input/capture/
app/src/main/java/io/warpnect/platform/input/capture/
app/src/main/java/io/warpnect/ui/input/
```

Responsibilities:

- `InputCaptureTypes.kt`: app-facing capture controller contract, `InputEventSink`, sink result, config, capability, state, error, and snapshot types.
- `WarpnectInputCaptureView.kt`: transparent focusable Android `View` that receives raw key, touch, generic motion, captured-pointer, pointer-capture, and window-focus callbacks.
- `AndroidInputCaptureController.kt`: deterministic lifecycle, synchronous event dispatch, bounded registry ownership, reset behavior, pointer-capture control, device listener ownership, and capture telemetry counters.
- `AndroidInputCaptureMappers.kt`: Android event timestamp conversion, Android keycode to HID usage mapping, left/right modifier tracking, session-local device-slot registry, gamepad button/axis normalization, touch tool/pressure/size mapping, pointer button masks, scroll Q8.8 conversion, and relative Q16.16 conversion.
- `WarpnectInputSurface.kt`: Compose wrapper for hosting the capture View through `AndroidView`.

Production input capture is Android/Kotlin platform integration only. It does not use JNI, SCL serialization, UDP, input reliability policy, AccessibilityService, target-side injection, target coordinate mapping, gesture recognition, per-event coroutines, worker queues, or timer polling.

## Audio Performance Source Tree

```text
app/src/main/java/io/warpnect/audio/performance/
```

Responsibilities:

- `AudioPerformanceConfig.kt`: RFC-003H UltraLowLatency profile, ImmediateFreshness/TinyReorderWindow policy selection, max recoverable audio age, playback and A/V tuning values, validation, and immutable snapshot fields.

Production performance configuration composes existing RFC-003A through RFC-003G settings. It does not create a new media runtime, payload queue, JNI path, codec wrapper, playback backend, or protocol version.

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
- `video_resync_control.h`: Version 1 VideoResyncRequest SessionControl wire codec.
- `video_receiver_runtime.h`: bounded SCL video receiver runtime composed from UDP receive, FEC/NACK, multi-message reassembly, Video Payload V1 parsing, ready-slot ownership, recovery freshness deadlines, VideoResyncRequest emission, clock-sync integration, and telemetry.
- `audio_opus_encoder.h`: portable libopus 1.6.1 RestrictedLowDelay encoder wrapper in `warpnect::audio`, with one Opus state, one incomplete PCM frame accumulator, and one encoded packet scratch buffer.
- `audio_opus_decoder.h`: portable libopus 1.6.1 decoder wrapper in `warpnect::audio`, with one Opus state, typed packet-duration validation, one preallocated PCM scratch buffer, and explicit caller-driven PLC.
- `audio_protocol.h`, `audio_transport_result.h`: Audio Payload Version 1 constants, StreamConfig/AudioFrame parsing, timestamp-quality flags, and typed audio transport errors.
- `input_protocol.h` / `input_protocol.cpp`: Input Payload Version 1 constants, semantic enums, common header, Key, TouchFrame, PointerAbsolute, PointerRelative, Scroll, GamepadState, ResetState, delivery class helper, typed errors, and exact-size encode/decode/validation.
- `audio_packetizer.h`: segmented Audio Payload V1 packetizer for header-plus-borrowed-Opus sources.
- `audio_receiver_runtime.h`: bounded Audio Payload V1 UDP receiver runtime, source filtering, RFC-001C reassembly ownership, ready-slot ownership, and coarse audio receive events.
- `audio_transport.h`: caller-driven SCL audio sender composed from packetization and non-blocking UDP.
- `audio_playback_ring.h`: portable fixed-capacity PCM playback handoff ring with partial-slot consumption, underrun silence, overrun rejection, metadata counters, and bounded residence diagnostics.
- `audio_oboe_playback.h`: Android-only Oboe playback backend using a native callback and the portable playback ring.
- `native_bridge.h`, `jni_bridge.cpp`: JNI glue only.
- `src/internal/`: private byte-order, socket-platform, GF(256), and matrix helpers.

SCL C++ owns protocol, timing, telemetry, and transport primitives, including Audio Payload V1. Portable audio codec and playback-ring code uses `warpnect::audio` and is linked beside SCL for JNI packaging, but codec/playback logic does not pollute SCL transport/protocol responsibilities. Android Oboe playback code is isolated to Android native builds. Native code does not own Android display capture, Shizuku integration, Compose, or app lifecycle.

## Future Expansion Points

Future code should expand along existing responsibility boundaries:

- SCL transport implementation below `app/src/main/cpp/src`.
- SCL tests in host-native targets under `native/`.
- Android capture, codec, renderer, audio, and input adapters in Kotlin packages with clear ownership.
- Cross-platform SCL extraction into a standalone native module only when desktop work begins.

No future phase should collapse SCL protocol logic into Kotlin or Android lifecycle logic into C++.

## RFC-005A Session Core

The platform-neutral session model is located in:

~~~text
app/src/main/java/io/warpnect/session/
app/src/test/java/io/warpnect/session/
~~~

It contains typed identity values, policy values, session/channel/path/peripheral models, the
bounded synchronized SessionManager, immutable snapshots, and JVM model tests. It has no Android,
transport, media, JNI, or discovery dependency.

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

Current JVM tests also include RFC-002F/RFC-002G receiver session prerequisite/keyframe/surface state transitions, transmitter partial-start rollback coverage, performance configuration validation, resync cooldown behavior, and optional loss-reactive bitrate policy coverage.

Current JVM tests also include RFC-003A audio chunk sizing, monotonic timestamp tracking, controller lifecycle/counter behavior, fixed SharedMemory ring layout, slot publication/release, overrun behavior, and fixed notification record coverage.

Current JVM tests also include RFC-003B Opus encoder request validation, frame-duration/sample calculations, fake-backend controller lifecycle, synchronous multiple-frame forwarding, discontinuity reporting, direct-buffer rejection, bitrate update, sink failure, stop/restart, and tail discard coverage.

Current JVM tests also include RFC-003C encoded-audio transport sink validation for StreamConfig submission, one-frame forwarding, direct-buffer requirements, timestamp/frame-position validation, discontinuity flag semantics, transport error propagation, and ByteBuffer position/limit preservation.

Current JVM tests also include RFC-003D Opus decoder fake-backend controller lifecycle, synchronous decode forwarding, config mismatch handling, direct-buffer rejection, invalid range rejection, sink failure, explicit PLC success/failure, stop/restart, and idempotent close coverage.

Current JVM tests also include RFC-003E Oboe playback fake-backend controller lifecycle, configuration validation, direct-buffer and range validation, config mismatch rejection, one-frame priming/start behavior, ring-full surfacing, presentation timestamp query mapping, stop/restart, idempotent close, and decoded PCM sink adapter forwarding.

Current JVM tests also include RFC-003F audio transmitter rollback/backpressure handling and receiver orchestration coverage for config wait, first-frame prime, contiguous decode, small-gap PLC, late/duplicate drop, misaligned reset, large-gap reset, config change, playback-ring-full drop, and ready-slot release on decoder failure.

Current JVM tests also include RFC-003G A/V synchronization coverage for lookahead math, output-frame interpolation, synthetic timestamp-domain qualification, different-epoch/rate rejection, stale fallback, synchronized video `RenderAt` decisions, past/excessive-future fallback and clamping, manual offset, startup gate limits, early/timeout release, and sync reacquisition hooks.

Current JVM tests also include RFC-003H audio performance configuration coverage for default profile values, TinyReorderWindow validation, recoverable-age bounds, subsystem-capacity validation, and projection into receiver-session and A/V synchronization configs.

Current JVM tests also include RFC-004A portable input model coverage for normalization/fixed-point conversion, device-slot validation, HID key events, modifier masks, touch contact/action-pointer rules, pointer button masks, scroll no-op rejection, gamepad axis/button validation, ResetState scope rules, and delivery classification.

Current JVM tests also include RFC-004B Android input mapper coverage for Android keycode to HID usage mapping, unknown key behavior, left/right modifier tracking, bounded device registry slot freshness, gamepad button mapping, nonstandard axis-range normalization, no-deadzone behavior, pointer button masks, normalized coordinate conversion, relative Q16.16 conversion, scroll Q8.8 conversion, and capture-config validation.

Current Android instrumentation tests include a privileged capture first-frame smoke test that skips explicitly when Shizuku/backend prerequisites are unavailable.

Current Android instrumentation tests also include a synthetic EGL Surface producer feeding `MediaCodec`, plus a Shizuku-gated RFC-002A capture-to-encoder integration test that skips explicitly when device prerequisites are unavailable.

Current Android instrumentation tests also include a synthetic RFC-002B encoder-to-RFC-002D decoder round trip that renders to a test `SurfaceTexture` Surface and skips explicitly when hardware codec prerequisites are unavailable.

Current Android instrumentation tests also include RFC-002E `SurfaceView` lifecycle coverage that publishes a valid Surface target and observes Surface destruction when a device/emulator is available.

Current Android instrumentation tests also include RFC-002F SCL loopback coverage: one sender/receiver boundary test submits StreamConfig and an encoded AU over 127.0.0.1 UDP, then fills a direct decoder-style input buffer from native receiver storage; another generic device-gated test composes synthetic encoder output, SCL loopback transport, native receiver runtime, hardware decoder, and SurfaceView renderer.

Current Android instrumentation tests also include RFC-003A SharedMemory PCM ring lifecycle coverage. Privileged system-audio and microphone hardware capture remain device/prerequisite-gated.

Current Android instrumentation tests also include RFC-003B JNI direct-buffer Opus encoder smoke coverage that does not require audio hardware. Microphone-to-Opus and system-audio-to-Opus tests remain device/prerequisite-gated.

Current Android instrumentation tests also include RFC-003C JNI direct-buffer audio transport smoke coverage that does not require audio hardware. Capture-to-encoder-to-transport device tests remain device/prerequisite-gated.

Current Android instrumentation tests also include RFC-003D JNI encoder-to-decoder smoke coverage using synthetic PCM and borrowed direct buffers. It does not require audio hardware or playback.

Current Android instrumentation tests also include RFC-003E Oboe playback lifecycle and direct PCM submission smoke coverage for connected devices/emulators. It uses short deterministic PCM and does not require network audio.

RFC-003G Android synthetic A/V, privileged real-capture timestamp-domain qualification, and two-device A/V validation are device/prerequisite-gated and must report actual device status only.

Current Android instrumentation tests also include RFC-004B synthetic View dispatch coverage for keyboard, touch, mouse absolute, scroll, and gamepad button translation when a device/emulator is available. Physical keyboard, mouse/pointer-capture, gamepad, and touch hardware validation remains actual-device dependent.

Current native tests include header smoke coverage, packet foundation tests, UDP localhost transport tests, fragmentation/reassembly tests, loss/NACK/recovery tests, Reed-Solomon FEC tests, clock synchronization/network telemetry tests, Phase 1 full-pipeline integration tests, RFC-002C video protocol/transport tests, RFC-002F video receiver runtime tests, RFC-002G VideoResyncRequest/recovery deadline tests, RFC-003B portable Opus encoder tests, RFC-003C Audio Payload V1/transport tests, RFC-003D portable Opus decoder tests, RFC-003E portable playback ring tests, RFC-003F audio receiver runtime loopback/reassembly tests, and RFC-003G playback source-anchor metadata tests.

Current native tests also include RFC-004A Input Payload V1 golden-vector, malformed-payload, strict-length, reserved-field, pointer/touch/gamepad/reset validation, and delivery-class tests.

`native/test_support/` contains host-only deterministic support code, including the scripted network impairment simulator used by integration tests and benchmarks. It is not compiled into the Android `scl_core` target.

`native/benchmarks/` contains host-only benchmark runners and the `scl_phase1_benchmarks`, `scl_phase2_video_benchmarks`, `opus_audio_encoder_benchmarks`, and `opus_audio_decoder_benchmarks` executables. Benchmarks are explicit/manual and are not part of Android production builds.

RFC-003H adds the `scl_phase3_audio_benchmarks` executable under `native/benchmarks/`. It measures codec frame-duration, bitrate, CBR/CVBR, complexity, packetization, receiver-runtime, PLC, and deterministic recovery-policy scenarios. Generated CSV belongs under native build directories and is not committed.

RFC-004C adds `input/transport` for `InputTransportController`, configuration, result/state/error, snapshot, and `SclInputEventSink`; and `platform/input/transport` for `NativeSclInputTransportController` and its controller-owned direct touch scratch. Native `input_packetizer.*` and `input_transport.*` provide direct single-datagram encoding, non-blocking sender ownership, and strict datagram parsing. Host-native input transport tests and JVM sink/scratch tests cover those boundaries.

Android instrumentation includes RFC-004C native input sender primitive/touch-scratch submission and synthetic capture-to-`SclInputEventSink` transport coverage. It requires a connected device or emulator; no physical input device is required. Native tests include direct Input packetization, one-datagram limits, sequence progression/wrap after failure, per-class drop telemetry, strict parser checks, and UDP localhost loopback coverage.

RFC-004D adds `input/injection` for Android-ready injection contracts and `platform/input/injection` for `AndroidInputInjectionController`, the Shizuku gateway, cached privileged InputManager API wrapper, Android event factory, and bounded state tracker. Its internal AIDL contract and `PrivilegedInputInjectionUserService` live under `platform/input/injection/privileged`. JVM tests cover bounded state and lifecycle; Android instrumentation covers event construction without asserting privileged delivery.

RFC-004E adds `input/mapping` for queue-free receiver viewport mapping and `platform/input/mapping` for the shared Android/HID keyboard table, target logical-display provider, target-local input-device resolver, and Android target mapper. `video/render/VideoViewportGeometry` provides the immutable renderer-to-input metadata handoff. JVM tests cover source geometry and target semantic mapping; Android instrumentation covers target logical display geometry when a device/emulator is present.

RFC-004G adds `input/reliability` for `InputReliabilityConfig`, source classification,
`InputSequenceMath`, fixed recent-sequence/semantic caches, and `InputStateConvergenceController`
with stable-pointer-ID touch repair. `input/performance` supplies bounded local timing histograms.
`native/benchmarks/scl_phase4_input_benchmarks.cpp` provides host-only packetization/parse/receiver
measurements. The existing privileged injection capability bundle reports a cold `/dev/uhid` probe
result; no UHID backend is present.

## CI Layout

```text
.github/workflows/android.yml
```

CI builds debug, runs ktlint, runs Android lint, runs unit tests, and compiles the native header smoke target.
