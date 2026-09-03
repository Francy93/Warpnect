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

## RFC-002I Decoder Investigation

This is pre-implementation laboratory evidence, not a production Client eligibility change. On the
tested Android 8.0 / API 26 Samsung SM-G935F, framework decoder hardware classification is
unavailable, so current production Client capability remains unavailable with
`HardwareClassificationUnavailable` before WNSN.

The selected `OMX.Exynos.avc.dec` reports 1280x720 at 60 fps support and completed a test-only
180-frame Surface-output AVC decode at that geometry and cadence. The simple fixture was
configured with an 8 Mbps target but encoded at approximately 0.56 Mbps, so it does not establish
the complete V1 bitrate envelope or change the production device classification. Draft RFC-002I
defines the proposed full-envelope active qualification design.

On the API 33 tablet, production S22-to-tablet traces rendered a real remote frame in portrait and
landscape. A bounded rotation during streaming did not fail the Session, but existing observations
did not force Surface destruction/recreation or establish a separate post-recreation presentation
event. After a Shizuku permission update the tablet entered Host lifecycle readiness, but the
tablet-to-S22 reverse-role trace was inconclusive because discovery could not resolve the tablet
after the clean test reset. That result is independent of Client video.
