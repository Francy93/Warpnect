# Android Compatibility Matrix

This matrix records bounded physical-device observations. It is not a guarantee for every device with the same Android version, SoC, or model family.

## Batch 1: Modern Host and Legacy Clients

Test artifact: debug APK built from `99b83049671f43a026e8f446f005c944b996368c`, SHA-256 `768550E2DA5784218AF0DCE71F212ACB0F468283B637A0352D44C8DC11CF4E60`, ABI set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`.

| Device class | Android/API | ABI | Tested role | Install / launch | Client video result | Input | Capture | Encoder / CBR diagnostic | Classification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | arm64-v8a | Reference Host | PASS | Host authenticated, negotiated, captured, encoded, and sent real video for each Client trace | Available for Host | DisplayManager strategy previously production-validated | Strict-CBR production qualification previously PASS | Reference modern Host |
| Samsung SM-G960F | Android 10 / API 29 | arm64-v8a | Client | PASS | S22 Host to Client reached decoder start, remote decode, and first real rendered frame | Not required for Client; reported `NotApplicable` | Not tested | Safe exact-format probe reported supported; not a Host qualification | `CLIENT_SUPPORTED` |
| Samsung SM-G935F | Android 8.0 / API 26 | arm64-v8a | Client | PASS | Secure authentication passed, then local Client video capability was unavailable before WNSN: `HardwareClassificationUnavailable` | Not required for Client; reported `NotApplicable` | Not tested | Not required for Client; no Host qualification performed | `CLIENT_UNSUPPORTED_HARDWARE_DECODER_CLASSIFICATION_UNAVAILABLE` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | arm64-v8a | Client | PASS | S22 Host to Client reached protected receive, decoder start, remote decode, and first real rendered frame | Not required for Client; reported `NotApplicable` | Not tested | Safe exact-format probe reported supported; not a Host qualification | `CLIENT_SUPPORTED` |

The tablet was landscape during the trace. Its 16:9 render surface occupied the available height, so the Compose status label was not exposed to the UI-automation tree. The production decoder nevertheless emitted the first-rendered-frame event. This is recorded as a layout-observability limitation, not as a media failure or a latency measurement.

All remote-frame results above used the production Session, protected UDP, decoder, and render surface. No screenshots, pixel readback, local loopback frames, or captured media were used as proof.

## RFC-002I Production Validation

Test artifact: debug APK from `3dce8cca8e9d9717386524d0d2a727485834635d`, SHA-256
`E49F01173420238D4C0B49236A45EE539D98CF12B7A245C9E374AF3E3231B31C`, 28,887,903 bytes,
ABI set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`. The only tracked
media fixture is the RFC-002I qualification asset; no capture output, device log, screenshot, or
device identifier is recorded here.

| Device class | Android/API | Tested role | Decoder qualification | Remote render | Surface recreation | Classification |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | Reference Host / modern Client check | Framework hardware path; no `:decoderProbe` | Reference Host sent real production video in each trace | Not required | `REFERENCE_MODERN_DEVICE` |
| Samsung SM-G960F | Android 10 / API 29 | Client | Framework hardware path; no `:decoderProbe` | S22 Host to Client reached `FIRST_REAL_RENDERED_FRAME_ON_CLIENT` | Not required | `CLIENT_SUPPORTED` |
| Samsung SM-G935F | Android 8.0 / API 26 | Client | `OMX.Exynos.avc.dec` passed same-UID full-envelope active qualification; exact-key cache reused in-process and after restart | S22 Host to Client reached `FIRST_REAL_RENDERED_FRAME_ON_CLIENT` | Not required | `CLIENT_SUPPORTED_ACTIVE_QUALIFICATION` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | Client | Framework hardware path | S22 Host to Client reached `FIRST_REAL_RENDERED_FRAME_ON_CLIENT` | Real generation 1 destruction, generation 2 preparation, and remote frame presentation on generation 2 | `CLIENT_SUPPORTED` |

These are device/build observations, not support claims for all API 26, Exynos, MediaTek, or tablet
devices. The S7 was admitted because its own exact static and active qualification passed; the known
software-family `OMX.google.h264.decoder` remains unavailable as a production fallback.

## Client Visual Presentation Investigation

Debug APK from `2ffddbe`, SHA-256
`F695FD9AD8E6E636DD5DCCC0B0FB45D5F96C6F9C93D3269A810DB4D41EB410ED`, 28,953,663 bytes,
ABI set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`.

The Client decoder released valid frames to the active Surface while the default `SurfaceView` media
layer remained behind the opaque Compose window buffer on the affected Samsung UI composition. The
production view now calls `setZOrderOnTop(true)`. This is a local Android presentation change; it
does not alter video payloads, decoder qualification, capture, or Session/protocol semantics.

| Device class | Android/API | Production remote result | Local composition control | Current classification |
| --- | --- | --- | --- | --- |
| Samsung SM-G935F | Android 8.0 / API 26 | A41 API 31 Host reached authenticated, committed media setup, decoder output, immediate release, and an active SurfaceFlinger buffer. A human then clearly saw the current A41 screen in the Client video surface. | Same `SurfaceView` and decoder path visibly presented the immutable AVC fixture. | `VISIBLE_REMOTE_VIDEO_PASS` |
| Samsung SM-G960F | Android 10 / API 29 | A41 API 31 Host Session stopped at `CapabilityNegotiationFailed` before video startup. No remote-presentation conclusion is drawn. | Same `SurfaceView` visibly presented the immutable AVC fixture through `OMX.Exynos.avc.dec`. | `REMOTE_PRESENTATION_INCONCLUSIVE_CAPABILITY_NEGOTIATION` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | A41 API 31 Host Session did not authenticate. No remote-presentation conclusion is drawn. | Same `SurfaceView` visibly presented the immutable AVC fixture through `c2.mtk.avc.decoder`. | `REMOTE_PRESENTATION_INCONCLUSIVE_AUTHENTICATION` |

The local fixture is a bounded debug-only composition control and is not substituted for a protected
remote Session result. Android screenshots were used only for layout/composition inspection; the S7
remote result above includes explicit human visual confirmation.

## Privileged Input Compatibility Investigation

Test-only investigation artifact: debug APK on branch `investigate/a41-privileged-input` from base
`174b7047cd2725fd587601179991b624548c3b37` plus debug-only diagnostics, SHA-256
`BABD7CC8C57020440065C6C3E2D9053F407B2E677EB95A30298E28031956A7C2`, 28,983,833 bytes, ABI
set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`. The diagnostics bind a
normal Shizuku UserService and inject only F1, touch, pointer-hover, and joystick-motion events into
a foreground Warpnect-owned debug Activity. They do not exercise a Session, peer, or user application.

| Device class | Android/API | Current production resolver | Legacy `InputManager` candidate | Local injection evidence | Investigation state |
| --- | --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | `InputManagerGlobal` resolved; current resolver available | Also resolved | Key, touch, pointer, and joystick accepted and observed | `INPUT_SUPPORTED_CURRENT_PATH` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | `InputManagerGlobal` class unavailable; current resolver reports `InputApiUnavailable` | Two- and three-argument injection overloads resolved | Key, touch, pointer, and joystick accepted and observed | `API_PATH_DISCOVERED`, `LOCAL_INJECTION_PROVEN` |
| Samsung SM-A415F | Android 12 / API 31 | `InputManagerGlobal` class unavailable; current resolver reports `InputApiUnavailable` | Two-argument injection overload resolved; target-UID overload unavailable | Key, touch, pointer, and joystick accepted and observed | `API_PATH_DISCOVERED`, `LOCAL_INJECTION_PROVEN` |
| Samsung SM-A415F | Android 11 / API 30 | `InputManagerGlobal` class unavailable; current resolver reports `InputApiUnavailable` | Two-argument injection overload resolved; target-UID overload unavailable | Key, touch, pointer, and joystick accepted and observed | `API_PATH_DISCOVERED`, `LOCAL_INJECTION_PROVEN` |

Every tested UserService ran under shell UID 2000 after a successful Warpnect-side Shizuku permission
check. The framework classes resolved from the boot class loader; this is an API-surface compatibility
finding, not a Shizuku permission, Binder-service, SELinux, or device-model finding.

## Privileged Input Legacy Android Implementation (A41 E2E Validated)

The production resolver now selects `InputManagerGlobal` when its complete modern API is available and
otherwise qualifies the legacy `InputManager.getInstance().injectInputEvent(InputEvent, int)` adapter.
The resolver remains capability-driven, caches its selected adapter for the UserService lifetime, and
keeps explicit target-UID injection unavailable on the legacy two-argument API. The final validation APK
was built from `b609f4af14fbcd9fd4293e3ea67788f5819ad04f`, SHA-256
`7E48686B4A5D6542286FB2B081B441993254331F28DB275433524CD6C645C1DB`, 28,937,243 bytes, with
ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64` (`minSdk 26`, `targetSdk 35`).

| Device class | Android/API | Production backend result | Local production validation | End-to-end reverse input | Current status |
| --- | --- | --- | --- | --- | --- |
| Samsung SM-A415F | Android 11 / API 30 | `LegacyInputManager`; `input_available=true` | Key, touch, pointer, and joystick accepted and observed in a Warpnect-owned target; reconfirmed after helper reset | Human touch on S7 Client captured, sent as Input Payload V1, received by Host, forwarded to legacy injection, accepted by Android, and observed by the Host target; SystemAudio unadvertised/unselected | `PRODUCTION_PRIVILEGED_INPUT_SUPPORTED_LEGACY_BACKEND`; `REAL_REVERSE_INPUT_E2E_VALIDATED` |
| Samsung SM-A415F | Android 12 / API 31 | `LegacyInputManager`; `input_available=true` | Key, touch, pointer, and joystick accepted and observed in a Warpnect-owned target; reconfirmed after helper reset | Human touch on S7 Client captured, sent as Input Payload V1, received by Host, forwarded to legacy injection, accepted by Android, and observed by the Host target; SystemAudio unadvertised/unselected | `PRODUCTION_PRIVILEGED_INPUT_SUPPORTED_LEGACY_BACKEND`; `REAL_REVERSE_INPUT_E2E_VALIDATED` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | `LegacyInputManager`; `input_available=true` | Key, touch, pointer, and joystick accepted and observed in a Warpnect-owned target | Not run | `LOCAL_PRODUCTION_LEGACY_INPUT_PASS` |
| Samsung SM-G935F | Android 8.0 / API 26 | Not resolved because Shizuku was not running | Not run | Not run | `API26_LEGACY_INPUT_INCONCLUSIVE_SHIZUKU_UNAVAILABLE` |
| Samsung SM-S901B | Android 16 / API 36 | `ModernInputManagerGlobal`; `input_available=true` | Final APK selected the modern backend; key, touch, pointer, and joystick returned `SubmittedAsync` and were observed by the Warpnect target | Real S22 Host Session reached setup but stopped at `SystemAudioStartFailed` before media/Input; no reverse-input event counted | `MODERN_PATH_PHYSICAL_LOCAL_REGRESSION_PASS`; Session E2E blocked by SystemAudio |

The API 30 and API 31 A41 targets are now validated through real reverse-input Sessions. The S7 Client
was the human-input source in both runs; no Host-side touch, ADB input, UI automation, or local injection
was used as E2E evidence. The physical API 36 modern-backend regression is now locally validated on the
S22 with the final APK. A separate S22 Host Session attempt remains blocked before media/Input by
`SystemAudioStartFailed` and is not counted as reverse-input evidence.

The final API 30 and API 31 traces reached `setup_committed`, media startup, Input Payload V1 capture and
transport, Host payload receipt, `LegacyInputManager` `SubmittedAsync` injection, and
`INPUT_SESSION_MAIN_TARGET_TOUCH_OBSERVED` on the A41 Host. The runner's media-readiness result remains
separate from this human-event correlation and was not used as a substitute for it.

The 2026-09-05 completion pass reused the exact APK above, including verification of installed
`base.apk` digests on all four attached devices. A bounded cleanup of residual Warpnect-owned shell
helpers left both Shizuku servers running; both A41 local production targets then observed key=1,
touch=1, pointer=2, joystick=1, with `SubmittedAsync` results and helper UID 2000.

### SystemAudio Startup Correction

The SystemAudio production capability probe previously checked `MODIFY_AUDIO_ROUTING` against the
package attribution of the current context. On both tested A41s, the actual AudioPolicy registration
was made by the Shizuku UserService as shell UID 2000 and AudioService rejected it before AudioRecord
creation. The production capability check now uses the UserService's own permission (`checkSelfPermission`),
which conservatively reports SystemAudio unavailable when registration would be denied. It does not
change Audio payload, channel, Session, or Input semantics; a committed SystemAudio channel still fails
the Session if its source later cannot start.

Debug APK built from `fa466f0b510e782ad80a33b666e0eba579e1bf30`, SHA-256
`9EE0A01950C7EA30AB8B44635E88762C875AEFAC473448AC74613343D6ACB4EF`, 28,920,859 bytes, was installed
on both A41s. Each A41 Host-to-S7 Client Session then reached authentication, committed setup, video
channel readiness, and media start without `SystemAudioStartFailed`. This removes
`A41_INPUT_E2E_BLOCKED_BY_SYSTEM_AUDIO_STARTUP`; it does not prove remote reverse input. The next Input
proof must correlate a human Client touch with Input Payload receipt, legacy adapter injection, and an
event observed by the Warpnect-owned Host target.

The API 33 tablet control did not reproduce the A41 permission boundary: final capability publication
remained available and AudioPolicy preparation reached `createAudioRecordSink`, which then returned
`AudioRecordCreationFailed`. That independent source-start condition was not negotiated into a tablet
Session and is outside the A41 Input compatibility correction.

Earlier startup retries included one clean-state pairing attempt on the API 30 Host and S7 Client;
pairing was re-established normally and the API 30 recording permission was restored. The final
post-helper-reset comparisons did not clear application data. Test applications were stopped after
the bounded campaign; Shizuku servers were not restarted.

An initial `InputSessionHold` harness result incorrectly treated early Client decode as readiness
after Host startup had failed. That result is invalid as Input readiness or E2E evidence. The harness
now requires both media-start acceptances, preserves the terminal startup reason, and explicitly
labels readiness as NOT proving reverse-input E2E. Thirteen deterministic outcome assertions cover
this distinction. Existing 576 JVM tests (zero failures/errors/skips), lint/build gates and unchanged
native Debug/Release CTest 22/22 remain carried evidence for the unchanged application artifact.

## RFC-002I Decoder Investigation (Historical)

This is laboratory evidence retained from before RFC-002I implementation. At that time, the tested
Android 8.0 / API 26 Samsung SM-G935F had framework decoder hardware classification unavailable,
so the then-current Client capability was unavailable with `HardwareClassificationUnavailable`
before WNSN.

The selected `OMX.Exynos.avc.dec` reports 1280x720 at 60 fps support. Follow-up RFC-002I
calibration replayed a byte-identical six-second 1280x720/60 AVC fixture with an actual 8.60 Mbps
average envelope on the S22, S9, and S7 Exynos decoders. Each S7 Exynos run produced all 360
decoder outputs and 359-360 Surface callbacks without a codec error. This is pre-implementation
qualification evidence only at that time. The final production results are recorded in the RFC-002I
Production Validation section above.

On the API 33 tablet, production S22-to-tablet traces rendered a real remote frame in portrait and
landscape. A bounded rotation during streaming did not fail the Session, but these earlier
observations did not force Surface destruction/recreation or establish a separate post-recreation
presentation event. A later tablet-Host attempt still reported `ShizukuPermissionRequired` during local Host
capability collection despite a Shizuku permission update; that Input/privilege-provider boundary
is separate from Client decoder qualification and leaves the reverse-role trace inconclusive.
