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
| Samsung SM-G960F | Android 10 / API 29 | A41 API 31 Host-to-Client completed authentication, WNCP, setup, receiver startup, AVC configuration, decoder startup, first access-unit decode, and output release to the current Surface. The user then physically confirmed visible remote video, including the Warpnect Host screen, Android Home, and ordinary Host applications. | Same `SurfaceView` visibly presented the immutable AVC fixture through `OMX.Exynos.avc.dec`. | `S9_PRODUCTION_VIDEO_PIPELINE_VALIDATED` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | Tablet Host-to-S9 Client reached authentication, WNCP, setup, capture, encoder output, protected video transport, Client decode, and output release in five consecutive first-attempt Sessions. A supplementary screenshot showed the current Tablet Host display in the S9 Client surface. | Same `SurfaceView` visibly presented the immutable AVC fixture through `c2.mtk.avc.decoder`. | `TABLET_REMOTE_SESSION_VALIDATED` |

The local fixture is a bounded debug-only composition control and is not substituted for a protected
remote Session result. The tablet screenshot supplements the protected-session milestones and Client
output-release evidence; the S7 remote result above additionally includes explicit human confirmation.

## Host Full-Display Capture Scope

On A41 Android 12/API 31, the legacy privileged capture backend selected the physical logical display
with `source_display_id=0` and `layer_stack=0`, mirrored it into the app-owned encoder Surface, and
remained active after the Warpnect Activity left foreground. In one protected A41 Host-to-S7 Client
Session, the Client visibly followed the Host through Warpnect, Android Home, Settings, and the
notification shade. The user directly confirmed the Settings presentation on the S7.

| Host | Client | Capture backend | Verified display states | Current classification |
| --- | --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 | Samsung SM-G935F, Android 8.0 / API 26 | `SurfaceControlDisplayCaptureApi`, legacy privileged display mirror | Warpnect, Home, Settings, notification shade | `HOST_FULL_DISPLAY_CAPTURE_VALIDATED` |

This is display-scope evidence for the tested device/runtime, not a claim that secure or protected Android
content is capturable. The former growing-latency and tablet remote-session observations are recorded below
with their resolved boundaries; they do not alter this capture-scope evidence.

## Privileged UserService Lifecycle and Cleanup

The A41/API 31 Host uses three non-daemon, shell-UID Shizuku UserServices: `capture`, `audio`, and
`input-injection`. They are `EXPECTED_BOUNDED`: a Session or cold capability query owns each connection,
and no helper is retained after its final owner releases it. The main `io.warpnect` process and the
external Shizuku server are independently `EXPECTED_PERSISTENT` while their respective application/runtime
is alive. The same-UID `codecProbe` is an independent bounded qualification process, not a Shizuku helper.

The prior accumulation was a production ownership defect. Shizuku unbind disconnects a UserService but does
not itself terminate its process, so each Warpnect helper now exposes the reserved UserService destroy
transaction, stops/resets its local resource, and exits after the gateway's final unbind. The SystemAudio
capability query also now closes its temporary controller in `finally`. The final validation APK was
`2AA9C9C0AC0B8A05BC286905846087CA873880868D96A08A0566DDD99B0FABEB`, 28,953,663 bytes, with ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`.

| Host / Client | Exercise | Helpers after normal teardown | Result |
| --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G935F, Android 8.0 / API 26 | Four protected media Sessions, including one Host Home/background/return interval | capture=0, audio=0, input=0 | `PRIVILEGED_USERSERVICE_CLEANUP_VALIDATED` |
| Same pair | Session/UI/authentication attempts aborting before privileged acquisition | capture=0, audio=0, input=0 | No partial-acquisition retention observed |

One repeated harness batch initially left the Client UI in `Streaming` because its real `Disconnect` control
was below the visible `ScrollView` viewport and was not tapped. The harness now performs one bounded viewport
search before declaring that action absent; this is a harness cleanup correction, not a change to Session or
SAS semantics. A post-`Disconnect` Client coordinator is terminal by current design, so a same-process
reconnect attempt is not used as a cold-capability result; it did not acquire privileged helpers or cause
count growth.

## Cold Capability Preparation and Session Negotiation

The prior cold-start failure was a local readiness ordering defect. The Host collected its required capability
snapshot synchronously only after receiving the Client WNCP offer, while the Client WNCP window had already
started. On a cold A41, strict-CBR qualification and privileged capability checks could therefore consume the
remote peer's negotiation budget. `PreparedHostCapabilityCollector` now completes the required Host snapshot
on the existing session-control scheduler before Host discovery advertisement. The responder reuses that exact
prepared snapshot when it later receives the Client offer. No WNCP timeout, qualification threshold, codec
profile, cache key, or wire state changed.

The final validation APK was
`0A78B3F3D61FAAB5F0788A0A17163B029F267652D195C682FCAD6FB222DF3D94`, 28,953,707 bytes, with ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`.

| Host / Client | Qualification state | Negotiation evidence | Result |
| --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G935F, Android 8.0 / API 26 | Host strict-CBR cache miss; Client exact RFC-002I decoder cache miss | Host capability preflight completed before advertisement (4,613 ms total; video component 1,014 ms). Client active decoder qualification completed before its offer (6,602 ms). After the offer, Host selection took 13 ms in the Host local clock and arrived after 38 ms in the Client local clock. | First cold Session authenticated, completed setup, and started media without a retry: `COLD_CAPABILITY_NEGOTIATION_VALIDATED` |
| Same pair | Host and Client exact qualification cache hits; Host app process retained and Client app process restarted | Host selection took 2 ms after the offer in the Host local clock; Client received it after 11 ms in its local clock. | Warm cache regression passed; legacy rendered-frame callback timing remains a separate observability concern. |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G960F, Android 10 / API 29 | Host readiness preflight; Client framework decoder classification | WNCP, setup, decoder startup, first access-unit decode, and Surface release completed. The user physically confirmed the A41 Host screen, Home, and ordinary Host applications on the S9. | `S9_PRODUCTION_VIDEO_PIPELINE_VALIDATED` |

The debug-only cold control invalidates only the exact RFC-002I decoder-qualification cache key; it does not
clear pairing, trust, session security, or unrelated capability state. The primary cold/cold validation did
not reboot either device, restart Shizuku, clear app data, or hide a warm retry.

## Persistent Exact Encoder Qualification Cache

RFC-002B active strict-CBR encoder qualification now persists only a typed `Supported` result in
app-private storage. The persisted record is addressed by a SHA-256 of the complete compatibility
key: qualification algorithm version, probe workload version, target profile version, codec name,
MIME, width, height, frame rate, bitrate, bitrate mode, I-frame interval, Build fingerprint, and
media-runtime compatibility version. An application version is intentionally not part of that key;
an explicit qualification/profile version changes when qualification semantics change.

`Unsupported`, timeouts, probe-process death, service failures, and other transient probe failures
remain current-process results only. The existing current-process probe-death quarantine is unchanged.
Malformed, missing, or incompatible persisted records are exact-key misses and run normal qualification;
they are never interpreted as support.

| A41 API 31 action | Exact-key cache result | Active `:codecProbe` work | Encoder capability collection |
| --- | --- | --- | --- |
| First normal Host enable with no record | miss | 2 eligible encoder probes, both `Supported` | 988 ms |
| New process, normal Host enable | 2 persistent hits | 0 probes | 100 ms |
| Second new process, normal Host enable | 2 persistent hits | 0 probes | 108 ms |
| `am force-stop` then normal relaunch | 2 persistent hits | 0 probes | 107 ms |
| `adb install -r` preserving data then normal Host enable | 2 persistent hits | 0 probes | 122 ms |

The final APK for this validation was SHA-256
`CBB4B4ED0589C02A4789192D35584FFC501D31301EE7223893C461B13BCFFCB5`, 28,953,707 bytes,
with ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64`. A normal A41 API 31 Host-to-S9 API 29
Session after a persistent hit completed authentication, WNCP, setup, decoder startup, first access
unit, and first decoded frame. After normal teardown, no `capture`, `audio`, or `input-injection`
Shizuku UserService remained.

## Session Establishment and Restart Reliability

The user-observed A41 API 31 Host-to-S9 API 29 first-attempt connection defect was reproduced with
the prior validated APK without clearing data, restarting Shizuku, rebooting, or force-stopping either
application. Two independent normal UI attempts discovered and accepted the Host presence, but did not
start pairing or authentication. The Client UI retained a Host row and displayed `Ready`, even though
the underlying Client coordinator had already reached its terminal `Closed` state.

The first failed boundary was `CLIENT_COORDINATOR_TERMINAL_STATE_REUSED`. Before the correction,
`SecureSessionCoordinator.disconnect()` left the application-scoped Client coordinator `Closed` after
normal attempt cancellation. Discovery could continue to refresh the old UI model, but a later
`SecureSessionApplicationController.connect()` correctly rejected the terminal coordinator as busy.
The UI did not surface that rejection. A related ownership defect was also found:
`ControllerBackedClientSessionPhaseDriver.closeAttempt(keepDiscovery = true)` closed its
`SessionProtectionController` but retained that terminal controller for a later authenticated attempt.
This is classified as `RETRY_REUSES_CANCELLED_RUNTIME_OWNER`.

The production correction returns the Client coordinator to reusable `Idle` on normal disconnect while
still invalidating the completed attempt token, and gives a discovery-preserving subsequent attempt a
fresh `SessionProtectionController`. Per-attempt state remains new; discovery, SAS, authentication,
WNCP, WNSN, payload formats, and transport semantics are unchanged. This is not an automatic retry.

The final reliability candidate was SHA-256
`6A69E3F04F265D138A50D917D941967D8B13B55BC8BED79B8292DA27D77614A7`, 28,953,707 bytes,
with ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64`, built from `79078df`. The same artifact was
installed on all devices in the validation rows below.

| A41 API 31 Host / Client scenario | First-attempt result | Evidence |
| --- | --- | --- |
| S9 API 29, ten consecutive normal enable/discover/connect/disconnect cycles | 10 / 10 PASS | Each cycle authenticated, completed capabilities, setup, video-channel readiness, media start, first encoded and received video datagrams, decoder start, first access-unit submission, and first decoded frame. Each ended through the product Disconnect/Host-disable path before the next cycle. |
| S9 API 29, Client process restart only | PASS | App data and Host process were preserved; one normal Connect reached media on its first attempt. |
| S9 API 29, Host process restart only | PASS | App data and Client process were preserved; the Host restored the RFC-002B persistent exact-key result and one normal Connect reached media on its first attempt. |
| S9 API 29, both application processes restarted | PASS | App data and Shizuku were preserved; one normal Connect reached media on its first attempt. The Host log recorded `encoder_cbr_persistent_cache_hit` and no active `:codecProbe`. |
| S7 API 26 regression control | PASS | Authentication, capabilities, setup, media start, decoder start, and first decoded frame passed. The legacy `OnFrameRendered` callback remains a separate observability signal and is not used as a Session-success gate. |

After final semantic teardown, A41, S9, and S7 each reported zero `capture`, `audio`,
`input-injection`, `codecProbe`, and `decoderProbe` processes. The lifecycle cleanup invariant remains
intact. `SESSION_ESTABLISHMENT_RESTART_RELIABILITY_VALIDATED` applies to the tested A41 API 31-to-S9
API 29 topology. The earlier tablet “pre-authentication blocker” label was superseded by the later
Tablet Host-to-S9 investigation; its validated result is recorded below. The later growing-streaming-latency
investigation is recorded below.

## Growing Streaming Latency / Backpressure

The user-reported A41 API 31 Host-to-S9 API 29 progressively stale stream was reproduced with a
changing Host display. The first failed boundary was
`VIDEO_RESYNC_CONTROL_NOT_PUMPED_BY_PRODUCTION_SENDER`, not an accumulating encoder, transport,
reassembly, decoder, or Surface queue. After a receiver discontinuity, the Client correctly entered
`WaitingForKeyFrame` and emitted Video Resync Control V1 requests. The Host continued encoding and
sending, but the Android production binding left `DefaultVideoTransmitterSessionController` on its
default no-op sender-control runtime. It therefore never pumped the already-negotiated control channel
or forwarded the request to `MediaCodec.requestKeyFrame()`. The last visible frame consequently became
progressively stale while fresh non-key frames could not be safely delivered.

The production binding now installs `NativeVideoSenderControlRuntime`. It pumps the existing V1 control
channel and asks the active encoder for an IDR on a real resync request. No video profile, frame rate,
bitrate, payload, FEC, transport, or decoder-selection behavior changed. A focused unit test covers
sender-control start and final stop with the video pipeline. Bounded debug-only five-second snapshots
are retained for future local diagnostics; they record counts and local PTS only, never frame content,
peer addresses, or cross-device latency.

| Stage | Queue / buffer | Bound / policy | Final sustained evidence |
| --- | --- | --- | --- |
| Encoder output to packetizer | Synchronous encoder sink | No retained application AU queue | Host encoded and submitted 2,148 (S9) / 2,151 (S7) AUs without failed submission. |
| Native video reassembly | Reassembly slots | 8 slots, 50 ms expiry | Final occupancy 0; high-water 1; no timeout or full-window event. |
| Native ready AUs | Ready slots | 8 slots, ordered bounded delivery | Final occupancy 0; high-water 1; no full-window event. |
| Decoder to surface | MediaCodec output with immediate release | `releaseOutputBuffer(index, true)` | Decoder output/release remained within 2-5 AUs of input and continued through teardown. |

The final debug APK was SHA-256
`B9E834D546BD673F4A1E333B45CB303D808C260696CC6C28A7206EBC64BEB1E7`, 29,235,744 bytes,
with ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64` (`minSdk 26`, `targetSdk 35`). Both runs used the
same artifact, one normal authenticated Session, and harmless Host display pulses every two seconds;
they did not use a restart, reconnect, quality reduction, or frame-dropping workaround.

| Host / Client | Duration | Control recovery | Queue / freshness result | Classification |
| --- | --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G960F, Android 10 / API 29 | 180 s; 61 Host pulses | 12 resync requests received, 11 keyframe requests forwarded, zero control errors | Client remained `Streaming` after startup. Reassembly and ready high-water were 1; final decoder queued/output/released were 2,142 / 2,139 / 2,139. The local input-output PTS delta was 50 ms at the final sample and did not grow monotonically. | `GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G935F, Android 8.0 / API 26 | 180 s; 62 Host pulses | 16 resync requests received, 14 keyframe requests forwarded, zero control errors | Client remained `Streaming` after startup. Reassembly and ready high-water were 1; final decoder queued/output/released were 2,166 / 2,164 / 2,164. The final local input-output PTS delta was 33 ms. | `GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` |

This proves the tested stream does not retain stale work unboundedly after recovery. It does not claim a
cross-device absolute latency value because Warpnect has no accepted clock-synchronization provenance.
The historical delayed/batched legacy `OnFrameRendered` callback remains an observability limitation,
not a freshness or Session-success gate. `GROWING_STREAMING_LATENCY` is closed as a correctness debt;
residual fixed latency and broader throughput tuning remain Phase 7 concerns.

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
| Samsung SM-S901B | Android 16 / API 36 | `ModernInputManagerGlobal`; `input_available=true` | Final APK selected the modern backend; key, touch, pointer, and joystick returned `SubmittedAsync` and were observed by the Warpnect target | SystemAudio is now omitted before WNCP because the real privileged loopback `AudioRecord` is uninitialized; no reverse-input event counted | `MODERN_PATH_PHYSICAL_LOCAL_REGRESSION_PASS`; SystemAudio truthfully unavailable on this runtime |

The API 30 and API 31 A41 targets are now validated through real reverse-input Sessions. The S7 Client
was the human-input source in both runs; no Host-side touch, ADB input, UI automation, or local injection
was used as E2E evidence. The physical API 36 modern-backend regression is now locally validated on the
S22 with the final APK. The S22 SystemAudio preflight now rejects the actual unstartable privileged
`AudioRecord` before WNCP instead of reaching `SystemAudioStartFailed`; no S22 reverse-input event is
counted as E2E evidence.

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

The API 33 tablet originally reached `createAudioRecordSink` and returned
`AudioRecordCreationFailed`. Revalidation after the generic UserService Binder-identity correction
now passes the exact production preflight (`prepare` -> `start` -> `stop`) and publishes SystemAudio
truthfully. A bounded debug-only verification used the same production controller with a normal-app
PCM16/stereo/48 kHz `USAGE_GAME` AudioTrack tone: 68,400 frames were captured, with 116,112 non-zero
samples, peak 8,191, and RMS 5,340.6. This is `TABLET_API33_SYSTEM_AUDIO_SUPPORTED`; it changed no
payload, mix, fallback, or model-specific behavior. The later Tablet-to-S9 campaign completed five
first-attempt Host/video Sessions without restart. It started the current-run privileged audio helper, but
did not play a deterministic source during those remote Sessions; SystemAudio transport/playback E2E remains
unproven rather than being inferred from local capture or Session success.

On 2026-09-09, after the tablet's Shizuku Manager was updated to 13.6.0.r1086.2650830c, a direct
production-controller run exposed a provider bootstrap failure before the privileged Binder was delivered. A
bounded isolator repeated the same failure for a minimal Binder and the production capture, audio, and input
UserServices. On this tablet's MediaTek `LoadedApk`, `makeApplication` calls
`Application.getProcessName().equals(mPackageName)` in an OEM resource-preload guard. The Shizuku
`ActivityThread.systemMain()` bootstrap has no application process name, so that forced 13.6 application path
throws before the UserService class loader, constructor, AudioPolicy, or AudioRecord is reached. The same APK
and Shizuku 13.6 publish all four Binders on the A41. The precise result is
`SHIZUKU_13_6_MAKEAPPLICATION_TABLET_FRAMEWORK_INCOMPATIBILITY`; it does not generalize to API33, an OEM, a
payload, or an audio format. The manager removes an unstarted service after its 30-second startup deadline
without delivering a `ServiceConnection` callback. Warpnect bounds that local bind by the same deadline and
reports `PrivilegedServiceUnavailable` before negotiation rather than wedging Host readiness. A controlled A/B
kept the Tablet Android build, Warpnect APK/app data, and UserService arguments fixed and changed only the official
signed Shizuku provider. With 13.5.4.r1049.0e53409 (APK SHA-256
`A05832CE3716AFB1FCCCF46F348006D2A296CA777E1FF3D223797DC74D06B31F`), all four Tablet probes (minimal,
capture, audio, input) published their Binders; the same minimal/capture/audio/input matrix also passed on the A41
control. The production tablet SystemAudio controller then prepared, started, and captured the normal Android
48 kHz stereo tone (70,080 frames, 115,632 non-zero samples, peak 8,191, RMS 5,265.3) before clean stop. The
exact differential is `SHIZUKU_13_6_PROVIDER_REGRESSION_CONFIRMED_FOR_TABLET_FRAMEWORK`, not a broad Android,
OEM, or Warpnect packaging claim. The old provider remains diagnostic only: Shizuku 13.6 was still the current
official stable and no official fixed revision was available during this run. A later Tablet-to-S9 session under
13.5.4 started SystemAudio and advanced Host PCM/Opus/payload/UDP counters during a 60-second normal `AudioTrack`
tone, but the S9 disconnected from ADB before Client receiver, playback-ring, and Android playback counters could
be collected. `TABLET_SYSTEM_AUDIO_REMOTE_E2E_OPEN` remains the truthful state.

### API 36 SystemAudio Startability Qualification

The S22/API 36 had a different false-positive boundary. Its Shizuku audio UserService runs as shell UID
2000 and reports the routing permission, so the earlier static capability check published SystemAudio. The
baseline production Session consequently committed SystemAudio and then exposed only the generic
`SystemAudioStartFailed`. With the incoming application Binder identity cleared, AudioPolicy registration
passes, but the same production `createAudioRecordSink` returns an uninitialized AudioRecord: AudioFlinger
rejects `uid=2000, package=io.warpnect` attribution. This is recorded as
`S22_AUDIO_RECORD_INITIALIZATION_FAILED` / `ANDROID_API36_AUDIORECORD_ATTRIBUTION_RESTRICTION`, not as a
Samsung-specific rule.

SystemAudio capability now performs the exact bounded production prepare/start/stop sequence after static
prerequisites pass. On the tested S22 that sequence returns `AudioRecordUninitialized`, so SystemAudio is
unavailable and omitted before WNCP. The implementation does not change the audio payload, add a microphone
substitute, or allow a committed SystemAudio channel to continue after startup failure. The same
Binder-identity correction allows the tested API 33 tablet to complete the production capture path; that is
a different outcome from the API 36 attribution restriction, not a general API-level or OEM rule.

The validation APK built from production commit `9e04f81d90949afc970f4480f657743e51281b85` was SHA-256
`4C14A9854AD10DDF39C53DF877C85D56CC2A25C67AF8C1B4673A4FA3BCACA0C2`, 29,235,744 bytes, with ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. Its installed `base.apk` digest was verified on the tested S22,
S9, and A41 without clearing application data.

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
