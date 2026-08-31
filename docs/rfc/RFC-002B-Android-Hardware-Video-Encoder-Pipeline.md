# RFC-002B - Android Hardware Video Encoder Pipeline

Project: Warpnect

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1.

## Purpose

RFC-002B adds the Android hardware video encoder foundation for the transmitter path. It starts at the Surface boundary established by RFC-002A and ends at encoded access units:

```text
RFC-002A privileged capture
        |
        v
MediaCodec input Surface
        |
        v
hardware AVC encoder
        |
        v
borrowed encoded output ByteBuffer
        |
        v
EncodedVideoSink
```

RFC-002B does not define encoded-video transport, packetization, FEC policy, decoder, renderer, audio, adaptive bitrate, or JNI video APIs.

## AVC Baseline

The implemented codec is H.264/AVC:

```text
MIME: video/avc
```

Only AVC is implemented in this RFC. Future RFCs may add HEVC or AV1 after the end-to-end low-latency pipeline exists.

## Codec Discovery

`AndroidVideoEncoderDiscovery` queries `MediaCodecList(REGULAR_CODECS)` and inspects `MediaCodecInfo` candidates. A candidate must be an encoder, support `video/avc`, expose `COLOR_FormatSurface`, support the requested size/rate, support the requested bitrate range, and support CBR.

Hardware classification is explicit. On API levels with reliable framework metadata, Warpnect uses `isHardwareAccelerated`, `isSoftwareOnly`, `isVendor`, `isAlias`, and `canonicalName`. Unknown classification is reported as `HardwareClassificationUnavailable` rather than silently selecting a codec. Verified software-only encoders are rejected for the transmitter hardware path.

Candidate selection is deterministic. After filtering, Warpnect prefers non-alias codecs and sorts by canonical name and codec name.

### CBR Metadata Compatibility

Strict CBR remains the required Android encoder policy. Real vendor hardware can report
`EncoderCapabilities.isBitrateModeSupported(CBR)` and `CodecCapabilities.isFormatSupported()`
as unsupported even though the exact production CBR `MediaFormat` successfully completes
`configure()`, input-Surface creation, and `start()`.

When every other mandatory hardware AVC predicate passes and CBR metadata alone rejects a
candidate, Warpnect may perform one bounded, cold exact-format probe using
`AndroidVideoEncoderFormatFactory`. The probe runs outside Main, starts no capture or Session,
produces and persists no media, releases its codec and Surface immediately, and caches the
safe result per exact request for the process lifetime. A failed probe leaves the candidate
unavailable. This compatibility check never relaxes CBR to VBR or CBR_FD and introduces no
Video Payload V1, SCL, Session-wire, or negotiated bitrate-mode semantic change.

## Encoder Request

`VideoEncoderRequest` includes:

- codec, currently `VideoCodec.Avc`;
- width;
- height;
- frame rate;
- bitrate in bits per second;
- I-frame interval in seconds;
- bitrate mode, currently CBR.

Malformed requests are rejected before MediaCodec configuration. RFC-002B does not silently resize, clamp bitrate, change FPS, or switch bitrate mode.

## MediaFormat Configuration

`AndroidVideoEncoderFormatFactory` creates an Android `MediaFormat` from the pure `VideoEncoderFormatPlan`.

Configured fields:

| Field | RFC-002B handling |
| --- | --- |
| `KEY_MIME` | `video/avc` through `createVideoFormat` |
| `KEY_WIDTH` / `KEY_HEIGHT` | caller-requested dimensions |
| `KEY_COLOR_FORMAT` | `MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface` |
| `KEY_BIT_RATE` | caller-requested bitrate |
| `KEY_BITRATE_MODE` | `BITRATE_MODE_CBR` |
| `KEY_FRAME_RATE` | caller-requested frame rate |
| `KEY_I_FRAME_INTERVAL` | caller-requested interval |
| `KEY_PRIORITY` | `0` when supported |
| `KEY_LATENCY` | `1` frame on API levels that expose the encoder latency hint |
| `KEY_MAX_B_FRAMES` | `0` where supported |
| `KEY_MAX_FPS_TO_ENCODER` | requested frame rate where supported |

`MediaFormat.KEY_LOW_LATENCY` is not used for encoding. That key is decoder-oriented and remains deferred to decoder work.

## Surface Input Architecture

The encoder owns the `Surface` returned by `MediaCodec.createInputSurface()`. The Surface becomes available after `prepare()` succeeds and remains valid until encoder teardown. RFC-002A capture may borrow the Surface as a target, but RFC-002A must not release it.

The production path does not use `ImageReader`, `Bitmap`, raw YUV/RGBA CPU conversion, or video input `queueInputBuffer()`.

## MediaCodec Lifecycle

`AndroidMediaCodecVideoEncoder` implements:

```text
Stopped -> Preparing -> Prepared -> Starting -> Running -> Draining -> Stopped
```

`prepare()` selects a codec, creates `MediaCodec`, installs asynchronous callbacks, configures the encoder, and creates the input Surface. The prepare path is transactional: on failure, acquired codec resources are released.

`start()` starts the prepared codec. Duplicate prepare/start calls fail deterministically. `stop()` is idempotent while stopped.

For Surface input, graceful stop calls `MediaCodec.signalEndOfInputStream()` and waits for an output buffer marked `BUFFER_FLAG_END_OF_STREAM`. A bounded drain timeout prevents a broken codec from blocking Warpnect indefinitely. Cleanup releases the MediaCodec-owned input Surface and codec resources.

## Threading

MediaCodec lifecycle and callbacks run on a dedicated `HandlerThread` named:

```text
WarpnectVideoEncoder
```

The thread owns codec configuration, start/stop, output-format callbacks, encoded output callbacks, EOS/drain, and cleanup. It has no networking or SCL packet responsibility.

## Encoded Output Contract

`EncodedVideoSink` receives:

```kotlin
onOutputFormatChanged(format)
onAccessUnit(buffer, offset, size, presentationTimeUs, flags)
onEncoderError(error)
```

The access-unit `ByteBuffer` is codec-owned borrowed storage. The sink may synchronously read only the supplied range during `onAccessUnit()`. It must not retain the buffer after the callback returns. The encoder releases the output buffer with `releaseOutputBuffer(index, false)` after sink handling, including failure paths.

RFC-002B does not create a mandatory per-access-unit `ByteArray`, does not retain output buffers, and does not maintain an unbounded encoded-frame queue.

## Output Format and CSD

On output-format changes, Warpnect extracts:

- MIME;
- width and height;
- frame rate when present;
- bitrate when present;
- profile and level when present;
- output reorder depth when present;
- reported encoder latency when present;
- codec-specific data buffers such as `csd-0` and `csd-1`.

CSD is copied into owned immutable `ByteArray` values during the cold-path format-change event. RFC-002B does not normalize AVC between Annex B and AVCC, does not insert start codes, and does not parse NAL units beyond framework-provided CSD extraction.

## Low-Latency and Reorder Semantics

Where supported, RFC-002B requests zero B-frames using `KEY_MAX_B_FRAMES = 0`. When the output format reports `KEY_OUTPUT_REORDER_DEPTH`, Warpnect records it. A non-zero reorder depth is reported explicitly as `UnexpectedOutputReordering`; RFC-002B does not reorder access units internally.

Presentation timestamps are preserved exactly from `MediaCodec.BufferInfo.presentationTimeUs`. Diagnostic PTS ordering is tracked, and regressions are surfaced as unexpected output reordering.

## Dynamic Controls

`requestKeyFrame()` uses `MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME` through `MediaCodec.setParameters()`. The result means the request was accepted by MediaCodec, not that the next access unit is guaranteed to be a keyframe.

`updateBitrate()` validates the requested bitrate against known capability bounds and uses `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE` through `setParameters()`.

Neither control is called automatically from SCL loss, RTT, FEC, jitter, bandwidth, or telemetry. Transport-driven adaptation is deferred.

## Error Mapping

RFC-002B uses typed encoder errors. Codec creation, configuration, input-surface creation, start, EOS signaling, drain timeout, stop, release, runtime codec errors, output-buffer absence, output-format invalidity, sink failure, and unsupported capability conditions are reported distinctly where the underlying operation can be identified.

`CodecSpecificDataInvalid` remains reserved for a future stricter CSD requirement. RFC-002B stores framework-provided CSD when present but does not require a particular AVC CSD layout before transport has defined the bitstream contract. `ReconfigurationRequired` remains reserved for future in-place resolution or format reconfiguration attempts; RFC-002B uses stop/release/prepare for resolution changes.

## Resource Ownership

| Resource | Owner | Created | Released |
| --- | --- | --- | --- |
| `MediaCodec` | encoder controller | `prepare()` | `stop()` / `close()` |
| encoder input `Surface` | encoder controller | `prepare()` | encoder teardown |
| borrowed output `ByteBuffer` | MediaCodec | output callback | immediately after sink returns |
| CSD copy | output format value | output format change | normal Kotlin object lifetime |
| `WarpnectVideoEncoder` thread | encoder controller | controller construction | `close()` |
| RFC-002A capture target Surface use | RFC-002A borrows encoder Surface | capture start | capture stop |

## Tests

JVM tests cover:

- deterministic hardware candidate selection;
- software-only rejection;
- unknown hardware classification rejection;
- Surface input requirement;
- unsupported dimensions, frame rate, bitrate, and CBR;
- MediaFormat plan values and API-gated hint omission;
- `KEY_LOW_LATENCY` absence from the pure plan;
- lifecycle state transitions;
- duplicate prepare/start;
- idempotent stop;
- output format, CSD metadata, and reorder-depth handling;
- codec-config and keyframe counters;
- PTS regression detection;
- keyframe/bitrate control preconditions;
- codec exception diagnostic storage;
- drain timeout.

Instrumentation tests cover:

- synthetic EGL frames into the MediaCodec input Surface;
- hardware AVC query with explicit skip when unavailable;
- output format and encoded access-unit observation;
- keyframe request;
- bitrate update;
- PTS progression;
- EOS/stop;
- RFC-002A privileged capture into the encoder input Surface when Shizuku/Sui and backend prerequisites are available.

## Device Verification Status

Instrumentation tests are device-dependent. If no Android device or emulator is connected, generic hardware AVC verification and RFC-002A-to-RFC-002B privileged capture verification remain pending and must not be reported as passed.

An emulator that exposes only software AVC encoding is insufficient to claim hardware-path verification.

## Architecture Compliance

RFC-002B introduces no SCL wire-format changes, no packet header changes, no new payload type, no native bridge ABI change, no JNI video transport, no UDP video sending, no decoder, and no renderer.

## Deferred Work

- RFC-002C - Encoded Video to SCL Transport Integration.
- RFC-002D - Android Hardware Video Decoder Pipeline.
- RFC-002E - Low-Latency Rendering Pipeline.
- RFC-002F - End-to-End Video Streaming.
- RFC-002G - Video Latency, Recovery and Performance Tuning.
