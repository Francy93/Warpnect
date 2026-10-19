# Video Geometry Thread Ownership - 2026-10-20

## Proven Defect

During the S7/API26 microphone-Client to A41/API31 Complete-Host compatibility
attempts, the Client process terminates with `CalledFromWrongThreadException`.
The first product boundary is `AndroidVideoRenderController.setVideoGeometry`:
decoder recreation runs on a DefaultDispatcher worker and directly calls
`SurfaceView.requestLayout()`. The Activity/View hierarchy belongs to the main
thread. This is separate from microphone transport arrival gaps and Android
AudioPolicy permission denial; fixing it is not evidence that either is closed.

Preserved physical failures: ignored `r1206-s7-a41-ap-only-24ghz` and
`r1211-s7-a41-single-hop-local-ap`, including their Client crash logs. The
same old source also fails both new focused tests on the real S7 in
`app/build/reports/r1212-render-pre-fix-s7.txt`.

## Minimal Correction

Keep geometry state in the existing thread-safe core. Dispatch only the View
layout request using that View's `post`. Check that the same View is still
attached when the action executes, so delayed work cannot touch a detached
render target. No API/model branch, codec, audio buffer, transport, Session
generation, security, provider or Complete-Host contract changes.

Two focused instrumentation regressions:

- `decoderWorkerGeometryRequestsLayoutOnTheViewThread`: an actual attached
  Activity/SurfaceView, worker callback, layout owner-thread verification and
  unchanged published dimensions.
- `queuedGeometryDoesNotTouchADetachedView`: bounded delayed dispatch followed
  by detach; queued work must not request layout on the former owner.

## Validation Scope

Frozen main APK SHA256:
`521C8E5C85E8E2F590D4EFC5FB41FDEF08EE3552E7D9CA71BF755540978066F5`.
On A41/API31 and S7/API26 the full five-case render class passes, alongside
ten real capture/JNI/transport component cases, 15/15 per device. Evidence:
`r1217-final-components-a41.txt`, `r1218-final-components-s7.txt`.

The first S7 component invocation (`r1217`) was accidentally started while its
test APK installation was still running and was terminated during package
replacement. Preserve it as a harness coordination failure, not a clean run.
The explicit replacement attempt waits for installation and verifies the
frozen main APK before executing the complete class set. It is not a hidden
audio campaign retry.

`r1213-post-video-fix-gates.txt`: ktlint, Android lint, all 1148 JVM cases in
176 suites (zero failures/errors/skips), debug APK, test APK and DirectShell
probe dex pass. Current native Debug and Release CTest both pass 25/25; native
working source is not changed by this UI correction. This physically validates
the View-thread defect correction, not microphone E2E quality or broad Android
compatibility. The inherited microphone transition work remains uncommitted.
