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
