# RFC-002E - Android Low-Latency Rendering Pipeline

Project: Warpnect

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

Video Payload Version: 1

Status: Implemented

## Purpose

RFC-002E adds the Android rendering foundation for decoded video:

```text
MediaCodec hardware decoder
        |
decoded buffers
        |
MediaCodec output Surface
        |
SurfaceView
        |
Android compositor/display
```

It owns the render `SurfaceView`/`Surface` lifecycle and render policy. It does not implement SCL receive orchestration, a jitter buffer, a final receiver session, or end-to-end streaming.

## SurfaceView Architecture

`WarpnectVideoSurfaceView` is the production render target. It owns the Android `Surface` through `SurfaceHolder`; RFC-002D borrows that Surface when configuring the decoder.

RFC-002E does not use `TextureView`, `ImageReader`, PixelCopy, Bitmap, OpenGL, Vulkan, or CPU pixel readback in production. Decoded frames remain in the Android codec/compositor path.

## Surface Lifecycle

`AndroidVideoRenderController` implements `SurfaceHolder.Callback` and handles:

- `surfaceCreated`;
- `surfaceChanged`;
- `surfaceDestroyed`;
- `close`.

Every new created Surface receives a monotonically increasing local `surfaceGeneration`. Destroyed Surfaces are invalidated immediately, and callers stop receiving that Surface as a target. A recreated Surface receives a new generation. Surface generation is local application state and is not placed on the SCL wire.

RFC-002E reports target events through `VideoRenderTargetListener`:

- `onRenderTargetAvailable`;
- `onRenderTargetChanged`;
- `onRenderTargetDestroyed`.

The renderer does not stop/reprepare the decoder by itself. RFC-002F will own end-to-end receiver orchestration.

## Ownership

| Resource | Owner |
| --- | --- |
| `SurfaceView` | RFC-002E render UI |
| `SurfaceHolder` | Android / SurfaceView |
| `Surface` | SurfaceView |
| `MediaCodec` decoder | RFC-002D |
| decoded output buffer | MediaCodec |
| rendering policy | RFC-002E |

RFC-002E never releases the SurfaceView-owned `Surface` manually.

## Aspect-Fit Geometry

`VideoRenderGeometry.aspectFit()` computes an aspect-preserving centered rectangle:

```text
scale = min(containerWidth / sourceWidth, containerHeight / sourceHeight)
```

The result letterboxes or pillarboxes with an opaque black surrounding area. It does not stretch or crop by default. `WarpnectVideoSurfaceView.onMeasure()` uses this geometry from the current video width and height.

Receiver-device rotation is handled as a layout/viewport size change. It is not confused with remote encoded video rotation.

## Compose Integration

`WarpnectVideoSurface()` is a minimal Compose adapter built with `AndroidView`. It hosts `WarpnectVideoSurfaceView` inside a black `Box` and keeps decoder/render lifecycle logic outside the composable.

No full receiver UI, controls panel, settings, connection orchestration, or stats overlay was added.

## Render Policy

`AndroidVideoRenderController.decodedVideoSink` is the RFC-002D `DecodedVideoSink` adapter.

The default policy is `ImmediateLowLatencyVideoRenderPolicy`, which returns `RenderNow` for valid frames while a Surface is active. It introduces no intentional application frame buffering.

Supported decisions:

- `RenderImmediately` -> RFC-002D `DecodedVideoOutputAction.RenderNow`;
- `Drop` -> RFC-002D `DecodedVideoOutputAction.Drop`;
- `RenderAtLocalTime(timestampNs)` -> RFC-002D `DecodedVideoOutputAction.RenderAt(timestampNs)`.

If no valid Surface is available, the sink drops the frame promptly. If a policy throws or produces a negative scheduled timestamp, the frame is dropped and a typed renderer error is recorded.

## Timestamp Contract

RFC-002C/RFC-002D `presentationTimeUs` remains a remote media timestamp. RFC-002E does not treat it as a receiver-local render deadline and does not guess a clock mapping.

Scheduled rendering requires an explicit receiver-local monotonic nanosecond timestamp suitable for Android `releaseOutputBuffer(index, timestampNs)`.

## Frame-Rate Hinting

`setPreferredFrameRate(frameRateHz)` accepts `null` or a positive finite `Float`. `null` clears the Android Surface hint with frame rate `0` when a valid Surface exists.

On supported API levels, `AndroidFrameRateHintApplier` calls public `Surface.setFrameRate` APIs. API 31+ uses `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`; older supported API levels use the two-argument call. The compatibility mode is fixed-source video. RFC-002E does not force non-seamless switching, a maximum refresh mode, `preferredDisplayModeId`, or a refresh-rate polling loop.

The hint is diagnostic/system-advisory. It does not throttle the decoder or prove that the physical display switched.

## Threading

SurfaceHolder lifecycle callbacks run on the View/window thread and remain lightweight.

Render decisions run synchronously on the RFC-002D `WarpnectVideoDecoder` callback thread through `DecodedVideoSink`. The decision path reads renderer state and policy directly. It does not hop to the UI thread, launch a coroutine per frame, create a render worker thread, or maintain a frame queue.

## Telemetry

`VideoRenderSnapshot` tracks:

- state and Surface availability;
- Surface generation and size;
- video geometry;
- preferred frame-rate hint and whether the hint was applied;
- frames received;
- immediate, scheduled, and drop decisions;
- last frame PTS and scheduled local timestamp;
- Surface create/destroy counts;
- frame-rate hint failures;
- render-policy failures;
- last typed renderer error.

Render decisions are separate from physical frame-rendered observations. RFC-002D owns `OnFrameRenderedListener` diagnostics.

## Tests

JVM tests cover:

- aspect-fit geometry;
- Surface generation and state transitions;
- close behavior;
- video geometry validation;
- immediate policy;
- scheduled policy with future and past local deadlines;
- null-deadline drop behavior;
- frame-rate validation and planning.

Instrumentation includes a SurfaceView lifecycle test using a real Activity host. It verifies target availability, valid Surface publication, generation 1, frame-rate preference acceptance, Surface destruction, and destroyed state when a device/emulator is available.

## Device Status

Device instrumentation requires a connected Android device or emulator. If no device is connected, instrumentation execution is reported as not run.

## Limitations

RFC-002E does not implement:

- SCL receive orchestration;
- decoder rebuild/rebind on Surface recreation;
- jitter buffer;
- decoded-frame queue;
- renderer worker loop;
- TextureView fallback;
- OpenGL/Vulkan rendering;
- pixel readback or screenshots;
- end-to-end video streaming.

## Architecture Compliance

No native code, JNI APIs, SCL wire format, `PacketHeader`, `PayloadType`, or Video Payload Version changes were made.

