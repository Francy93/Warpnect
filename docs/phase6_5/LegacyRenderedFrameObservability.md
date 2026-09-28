# Legacy Rendered-Frame Observability (2026-09-28)

## Scope and decision

This is a measurement qualification, not a video-correctness campaign. Physical
remote video correctness was previously validated on S7 and S9. This run used
S7/API26 and S9/API29 as legacy Clients, A41/API31 as a local decoder control,
and Tablet/API33 as the non-Root DirectShell Host for the S9 remote Session.
S22/API36 was temporarily unavailable and was not tested. No Root, audible
fixture, wire change, or decoder scheduling change was used.

| Target | Android | Build fingerprint | Role / decoder |
| --- | --- | --- | --- |
| Samsung SM-G935F (S7) | 8.0.0 / API26 | `samsung/hero2ltexx/hero2lte:8.0.0/R16NW/G935FXXU8EVH2:user/release-keys` | Local Client fixture, `OMX.Exynos.avc.dec` |
| Samsung SM-G960F (S9) | 10 / API29 | `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys` | Remote Client and local fixture, `OMX.Exynos.avc.dec` |
| Samsung SM-A415F (A41) | 12 / API31 | `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys` | Local Client control, `c2.mtk.avc.decoder` |
| Aocwei X700_EEA (Tablet) | 13 / API33 | `Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys` | DirectShell Host for S9 |

`MediaCodec.OnFrameRenderedListener` is **not an authoritative presentation
clock**. Android documents that this informational callback can be delayed,
batched, or absent for some rendered frames on pre-Android-14 releases.
Further, on the tested A41 `c2.mtk.avc.decoder`, its supplied `nanoTime` was
exactly `presentationTimeUs * 1000` for the first 64 observations, not the
app's local `System.nanoTime()` domain. The actual local clock provenance of
the codec-provided value is therefore unqualified. See the Android
[listener reference](https://developer.android.com/reference/android/media/MediaCodec.OnFrameRenderedListener)
and [MediaCodec release contract](https://developer.android.com/reference/android/media/MediaCodec).

Classification: **CLASSIFIED_PHASE7_MEASUREMENT_DEBT**. Decoder output and
successful release to Surface remain trustworthy local pipeline milestones.
Neither proves a SurfaceFlinger latch, a physical display presentation, or a
human-visible photon. True physical-display end-to-end latency still needs a
separately qualified display instrument and a cross-device clock/identity
methodology.

## Current path and frame identity

Host encoder PTS -> Video Payload V1 -> protected SCL AU (`frameId`, PTS) ->
native Client receiver -> MediaCodec input PTS -> output `BufferInfo` PTS ->
`releaseOutputBuffer(index, true)` -> Surface/BufferQueue -> SurfaceFlinger ->
display. `frameId` is not forwarded into MediaCodec. Matching the app's
decoder-input, decoder-output, Surface-release, and callback observations uses
PTS, and is valid only while that decoder preserves exact PTS identity. The
codec may transform or reorder PTS; no wire field was added to conceal that
limitation. Native receiver AU-ready can be reported repeatedly for the same
frame while a consumer drains it; bounded DEBUG logging now counts each
`configGeneration`/`frameId` only once.

| Boundary | Source and domain | Meaning | Production trust |
| --- | --- | --- | --- |
| Host encoder PTS | Host Android BOOTTIME, us | Media identity carried across wire | Correlation key only; not subtractable from Client clock |
| Client AU-ready | Local `System.nanoTime()` in bounded DEBUG log | Reassembled AU available, not individual packet arrival | Local diagnostic |
| Decoder input/output | Client `System.nanoTime()` | Queue submission / output buffer available | Local pipeline interval |
| Surface handoff | Client `System.nanoTime()` after successful release call | Buffer submitted toward Surface | Local handoff; **not presented** |
| Codec callback receipt | Client `System.nanoTime()` | Informational callback delivered to app | Diagnostic only; variable, biased by missing/expired callbacks |
| Callback-supplied `nanoTime` | Codec/vendor-dependent; A41 returned PTS ns | Raw codec value | Unknown clock domain; never subtract from local time for latency |
| SurfaceFlinger layer stats | ADB shell `dumpsys SurfaceFlinger --latency`, ns | Per-layer desired/actual/ready diagnostics where exposed | Laboratory only; vendor semantics and permission vary |
| Physical display photons | No current app API | Actual visible output | Not software-observable here |

`SystemClock.elapsedRealtimeNanos()` (BOOTTIME), `System.nanoTime()`
(MONOTONIC), Android uptime, native steady time, wall-clock logcat stamps, and
remote Host PTS are separate domains. Earlier DEBUG AU-ready and decoder-stage
observations mixed BOOTTIME and MONOTONIC; both now use local MONOTONIC.
`video_runtime.local_monotonic_ms` remains BOOTTIME and must not be subtracted
from the DEBUG `local_monotonic_ns` values. RFC-006D cross-device source-to-
decoder/render descriptors remain unbound: native ClockSync does not map the
Host Android source clock to the Client Android decoder clock.

## Physical observations

All local fixture runs used the existing immutable 1280x720 AVC fixture on the
normal `WarpnectVideoSurfaceView`, with 64 bounded PTS observations per stage.
The screenshot proves that the fixture image was visible, not its millisecond
presentation time. Release-to-receipt intervals are local MONOTONIC durations;
callback-supplied timestamps were deliberately excluded from those values.

| Client / run | Decoder | First 64 released / callbacks matched | Release -> callback receipt median / p95 / max | Other evidence |
| --- | --- | --- | --- | --- |
| S7 API26, local fixture | `OMX.Exynos.avc.dec` | 64 / 64 | 87.7 / 91.1 / 92.2 ms | Visible screenshot; 578 queued, 577 released, 572 callbacks at intentional stop; final 5 were still outstanding, not proved lost |
| S9 API29, local fixture | `OMX.Exynos.avc.dec` | 64 / 64 | 85.1 / 86.3 / 93.3 ms | Visible screenshot; 533 queued, 532 released, 519 callbacks at intentional stop; 13 outstanding, not proved lost |
| A41 API31, local fixture | `c2.mtk.avc.decoder` | 64 / 64 | Callback received near release; median 0.55 ms | Visible screenshot; 610 released and 610 callbacks at stop; codec `nanoTime` equaled PTS ns, so its apparent clock was invalid for local latency |
| Tablet API33 -> S9 API29, protected Session | `OMX.Exynos.avc.dec` | 64 / 64 in bounded DEBUG PTS sample | 281 / 57,450 / 70,705 ms | S9 screenshot shows real Tablet Host UI; decoder release advanced. Several sparse-content callbacks arrived tens of seconds after release; the image screenshot does not establish which individual frame was visible before its callback. |

The exact final debug APK was also installed on S9 for a silent local-fixture
smoke test after the telemetry change. It selected `OMX.Exynos.avc.dec`,
reported 558 inputs, 555 Surface releases and 543 callbacks at intentional
stop, and logged no fixture failure. The 12 release/callback difference at
stop is outstanding work, not proof of permanently lost callbacks. This final
APK was not used for a new remote Session because the Tablet was locked.

The S9 remote sample included an apparent callback-delivery lag (local receipt
minus codec-supplied value) of up to 70.4 seconds and several callbacks close
together after widely separated PTS. Three adjacent callback pairs arrived
less than 10 ms apart despite PTS separations greater than 20 ms, including
one 3.85-second separation. It supports **delayed and sometimes
batched callback delivery**, not a 70-second physical-display delay. The
continuously changing local fixture on the same decoder was prompt, so an
S9/Samsung model-wide callback blacklist would be unjustified. In the remote
sample, 64 decoder inputs, outputs, releases, and callbacks were PTS-matched.
The bounded sample proves no permanent callback loss among those 64; it does
not prove completeness across all 1,729 later released frames.

The S7 remote Session was not repeated in this task: a new Tablet/S7 peer trust
exchange required explicit SAS confirmation and timed out without approval.
S7 local fixture and prior human-confirmed A41 -> S7 visible Session evidence
remain separate. The A41 Host control hit `Wireless debugging endpoint is
unavailable` before Session; this is a bootstrap precondition, not decoder
evidence. A later S9 `InvalidPresence` was also outside the decoder boundary.
S22 was unavailable; prior S22 acceptance is unchanged.

| Anomaly outside the callback result | First trustworthy boundary | Classification | Action |
| --- | --- | --- | --- |
| A41 Host request | `Wireless debugging endpoint is unavailable`, before DirectShell acquisition | ENVIRONMENTAL_PRECONDITION | Used Tablet as the already validated Host; did not attribute this to Video |
| S7 -> Tablet first connect | SAS was generated, then `UserConfirmationTimeout` before trust | ENVIRONMENTAL_PRECONDITION | Preserved failed attempt; no automatic approval or hidden pairing retry |
| Later S9 connect after the first valid Session | `InvalidPresence`; Tablet subsequently showed lockscreen and no S7 discovery row | INCONCLUSIVE | No decoder failure inferred; no speculative product fix |
| S7 local fixture exit | Intentional `Thread.interrupt()` during pacing was logged as decoder failure | TEST_HARNESS_DEFECT | DEBUG fixture now treats cancellation as normal; S9 final fixture run showed no such failure |

## SurfaceFlinger diagnostic boundary

On both legacy Clients, the shell-visible SurfaceView layer exposed a refresh
period and 128 frame-stat rows. The first column matched `PTS * 1000` for 63
fixture frames per device; the second was a local monotonic-sized actual-present
value. Matching the row by PTS to app logs gave median release -> layer
actual-present of 62.2 ms (S7) and 45.3 ms (S9). Median callback receipt was
26.5 ms (S7) and 22.6 ms (S9) **after** that layer mark. The AOSP
[FrameTracker implementation](https://android.googlesource.com/platform/frameworks/native/+/cb31cabec0411d3647be742d9be0605a4dcf6622/services/surfaceflinger/FrameTracker.cpp)
labels these fields desired present, actual present, and frame ready; the
[BufferLayer implementation](https://android.googlesource.com/platform/frameworks/native/+/3a2607a5047fe873e939b6e945f2d54ea89b05bd/services/surfaceflinger/BufferLayer.cpp)
may derive actual-present from a present fence or display refresh. This is
**ADB-only diagnostic correlation** on the tested builds, not an app-facing
BufferQueue acquisition API, a universal vendor guarantee, or a photon sensor.

## Production policy and remaining qualification risk

The Client UI now marks `Streaming` after the first successful Surface handoff,
not after an arbitrarily delayed codec callback. This means active decoder
delivery, **not proven visible presentation**. DEBUG names distinguish
`first_output_released_for_surface` from `first_codec_render_callback`, and
stale callbacks from a stopped/old codec cannot mutate the new generation.
Metric ID `0x0632` now measures **local release -> codec callback receipt**,
under a diagnostic name. It does not use the codec-supplied `nanoTime` and is
not a latency or callback-completeness claim. The two-second correlation
expiry makes very delayed callbacks unmatched rather than falsely zero or
presented. Closed/open-addressed PTS slots still find later colliding keys.

Normal Session admission, video resync, stall/recovery and disconnect do not
depend on `OnFrameRendered`. The separate exact-key legacy decoder qualification
probe still counts callback throughput on a continuously paced offscreen
fixture; that is **not** a Session presentation timestamp. It passed on the
tested S7 previously (358/360 or more) and the current local fixture was
prompt. A future codec that decodes correctly but withholds callbacks in that
qualification probe could still be falsely rejected; that scenario is not
established by this run and must be investigated with exact probe evidence,
not by removing a safety threshold speculatively.

## Phase 7 measurement contract

| Metric | Start -> end | Clock | Trust | Scope | External instrument? |
| --- | --- | --- | --- | --- | --- |
| Client AU-ready -> decoder enqueue | DEBUG AU-ready -> input queued, same PTS | Client MONOTONIC | Local diagnostic | Exact PTS, same generation | No |
| Decoder work | Input queued -> output available | Client MONOTONIC | Local pipeline | Exact PTS | No |
| App handoff | Output available -> successful Surface release | Client MONOTONIC | Local pipeline | Exact PTS | No |
| Callback delivery | Surface release -> callback receipt | Client MONOTONIC | Weak diagnostic; missing/late samples biased | Exact PTS if callback arrives before expiry | No |
| Layer presentation | Surface release -> `SurfaceFlinger --latency` row | Client MONOTONIC if build verified | ADB-only diagnostic | Tested layer/PTS mapping only | Shell access |
| Physical display / E2E photons | Host visual transition -> Client light output | No accepted shared clock today | Not established in software | Future external campaign | Yes |

Do not subtract Host PTS from Client MONOTONIC, call a Surface release
"presented", or call a codec callback a physical-display timestamp.
