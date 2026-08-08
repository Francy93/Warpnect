# RFC-002A - Android Privileged Video Capture Foundation

Status: Implemented.

Architecture Version: 1.0. SCL Protocol Version: 1. Native Bridge ABI Version: 1.

## Purpose

RFC-002A begins Phase 2 by adding an Android-side display-capture foundation for the future transmitter video path.

The production media boundary is:

```text
Android display compositor
        |
        v
privileged capture backend
        |
        v
caller-owned Surface
```

RFC-002A stops at the caller-owned `android.view.Surface`. It does not create a video encoder, decode video, packetize video over SCL, or add a JNI capture API.

## Surface Data Plane

The app-facing contract is `VideoCaptureController.start(request, targetSurface)`.

The capture subsystem borrows the caller's `Surface` for the active session. It never owns or releases that `Surface`; the caller must keep it valid until capture is stopped.

The production data path does not return video frames to Kotlin, JNI, Binder callbacks, byte arrays, bitmaps, `ImageReader`, or application CPU buffers. Binder is used only as a control plane for capabilities, start, update, stop, state, and typed errors.

Test-only sinks may use `SurfaceTexture` or similar Android objects to prove that a frame reaches a `Surface`, but that is not the production streaming architecture.

## Privilege Architecture

Warpnect uses the existing Shizuku/Sui model:

```text
Warpnect app process
        |
        | Shizuku Binder
        v
PrivilegedCaptureUserService
        |
        v
PrivilegedDisplayCaptureApi
        |
        v
hidden Android display APIs
        |
        v
caller-owned Surface
```

Responsibilities:

- `io.warpnect.capture`: public capture request, result, state, error, capability, snapshot, and geometry types.
- `io.warpnect.platform.capture.AndroidVideoCaptureController`: ordinary app-process lifecycle controller.
- `io.warpnect.platform.capture.ShizukuCaptureGateway`: Shizuku permission, UserService binding, binder death, and remote state mapping.
- `io.warpnect.platform.capture.privileged.PrivilegedCaptureUserService`: Binder endpoint running as the Shizuku UserService.
- `io.warpnect.platform.capture.privileged.SurfaceControlDisplayCaptureApi`: isolated hidden API adapter.

Sui/root-backed Shizuku is compatible with the same UserService path. RFC-002A does not add a separate `su` or root-process launcher.

## Binder Contract

`IPrivilegedCaptureService` exposes:

```text
queryCapabilities() -> Bundle
startCapture(display, width, height, followRotation, Surface) -> error code
updateCapture(display, width, height, followRotation) -> error code
stopCapture() -> error code
getState() -> Bundle
```

The AIDL interface passes one `Surface` during setup. It does not carry frame payloads, `ByteArray`, `Bitmap`, `Image`, `HardwareBuffer`, or per-frame callbacks.

## Backend

The implemented backend is `SurfaceControlDisplay`.

The privileged adapter resolves these Android framework members by reflection on the cold path:

- `android.view.SurfaceControl.createDisplay(String, boolean)`
- `android.view.SurfaceControl.destroyDisplay(IBinder)`
- `android.view.SurfaceControl.setDisplaySurface(IBinder, Surface)`
- `android.view.SurfaceControl.setDisplayProjection(IBinder, int, Rect, Rect)`
- `android.view.SurfaceControl.setDisplayLayerStack(IBinder, int)`
- `android.hardware.display.DisplayManagerGlobal.getInstance()`
- `android.hardware.display.DisplayManagerGlobal.getDisplayInfo(int)`
- `android.view.DisplayInfo.logicalWidth`
- `android.view.DisplayInfo.logicalHeight`
- `android.view.DisplayInfo.rotation`
- optional `android.view.DisplayInfo.refreshRate`
- optional `android.view.DisplayInfo.layerStack`

Reflection is isolated in `SurfaceControlDisplayCaptureApi`. Missing classes, methods, or fields map to `HiddenApiUnavailable` or `CaptureBackendUnavailable`; they do not crash the app. Security failures map to `CapturePermissionDenied`.

No vendor-specific behavior is hard-coded. Runtime probing determines whether the backend is available on the current Android build.

## No MediaProjection Fallback

Warpnect does not use `MediaProjectionManager`, `MediaProjection`, or `createScreenCaptureIntent()` as a production fallback.

If Shizuku is unavailable, permission is missing, the UserService cannot bind, or the hidden display APIs are unavailable, the controller reports typed capability and lifecycle errors instead of silently switching capture architecture.

## Capability States

The capture capability model distinguishes:

- `ShizukuUnavailable`
- `ShizukuBinderUnavailable`
- `ShizukuPermissionRequired`
- `ShizukuPermissionDenied`
- `PrivilegedServiceUnavailable`
- `PrivilegedServiceDied`
- `HiddenApiUnavailable`
- `CaptureBackendUnavailable`
- `Ready`

The app can request Shizuku permission explicitly through `VideoCaptureController.requestPermission()`. The controller does not repeatedly prompt on its own.

## Capture Lifecycle

The app-process controller uses a small state machine:

```text
Stopped -> Starting -> Running
Running -> Reconfiguring -> Running
Running -> Stopping -> Stopped
Starting/Reconfiguring/Running -> Error
Error -> Stopping/Stopped
```

`start()` validates dimensions, target surface validity, duplicate starts, Shizuku readiness, service availability, and backend result. `stop()` is idempotent and safe while already stopped.

If startup fails after the privileged backend has acquired resources, the service rolls back through `stopCapture()`. Binder death moves local state out of `Running` and clears stale service references.

## Resource Ownership

| Resource | Owner | Created | Released |
| --- | --- | --- | --- |
| Shizuku binding | app gateway | first service use | controller close / disconnect |
| UserService capture object | Shizuku UserService | bind | unbind / service destroy |
| SurfaceControl display token | privileged backend | startCapture | stopCapture / rollback / destroy |
| target Surface | caller | outside capture | caller, after capture stops |
| display listener | app controller | successful start | stop / display removal / close |

The privileged service never releases the caller-owned target `Surface`.

## Geometry and Rotation

`CaptureGeometry.computeProjection()` is pure Kotlin and covered by JVM tests.

Inputs:

- source logical width and height;
- source rotation;
- target width and height.

The projection preserves aspect ratio by fitting the rotated source inside the target and applying deterministic letterboxing when needed. It supports rotations `0`, `90`, `180`, and `270` degrees through Android rotation constants `0..3`.

`DisplayConfigurationMonitor` listens for display changes/removal and asks the gateway to update projection. There is no high-frequency polling loop and no per-frame application thread.

## Security Behavior

RFC-002A does not attempt to bypass Android secure-content or protected-content policy. If Android redacts secure or DRM-protected surfaces, Warpnect preserves that platform behavior.

## Tests

JVM tests cover:

- capture request validation;
- state transitions;
- duplicate start;
- idempotent stop;
- restart;
- start rollback;
- reconfiguration failure;
- binder death mapping;
- hidden API unavailable capability mapping;
- projection geometry across portrait, landscape, rotation, square, and aspect-mismatch cases.

Instrumentation support includes a privilege-gated first-frame test using a test-only `SurfaceTexture` target. It skips explicitly when Shizuku permission or the privileged backend is unavailable.

## Manual Device Verification

A supported-device manual verification should record:

- manufacturer and model;
- Android version and API level;
- Shizuku or Sui mode;
- selected backend;
- default-display geometry;
- first-frame observed;
- first-frame latency;
- rotation behavior;
- restart behavior.

No unsupported or unavailable device result should be reported as a fake pass.

## Known Limitations

- Hidden display APIs are version-sensitive and may be blocked or changed by Android releases or vendor builds.
- The implemented backend uses runtime probing rather than a fixed Android-version promise.
- RFC-002A does not include a MediaCodec encoder, video transport, decoder, renderer, adaptive policy, or production telemetry UI.
- Test-only `SurfaceTexture` frame observation is not a MediaCodec performance claim.

## Architecture Compliance

RFC-002A introduces no SCL protocol changes, no packet header changes, no new payload type, no native bridge ABI changes, no JNI capture API, and no native SCL capture logic.

Deferred work:

- RFC-002B - Android Hardware Video Encoder Pipeline.
- RFC-002C - Encoded Video to SCL Transport Integration.
- RFC-002D - Hardware Video Decoder Pipeline.
- RFC-002E - Low-Latency Rendering Pipeline.
- RFC-002F - End-to-End Video Streaming.
- RFC-002G - Video Latency, Recovery and Performance Tuning.
