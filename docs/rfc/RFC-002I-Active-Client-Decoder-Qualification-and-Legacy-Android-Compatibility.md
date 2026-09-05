# RFC-002I - Active Client Decoder Qualification & Legacy Android Compatibility

Project: Warpnect

Status: Implemented. Production integration preserves the calibrated qualification semantics without changing current protocol contracts, reopening H1, or modifying RFC-002B.

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1.

## Context and Problem

RFC-002D requires a hardware AVC decoder for Client video. Android API 29 and later exposes framework hardware/software classification through `MediaCodecInfo`; API 26-28 does not. Before RFC-002I, `AndroidVideoDecoderDiscovery` assigned every API 26-28 candidate `Unknown`, and `VideoDecoderSelector` returned `HardwareClassificationUnavailable` before Client video could be advertised.

The absence of framework metadata is not proof that a decoder is software-only or inadequate. Conversely, a vendor-style name and static format support do not prove real-time performance. RFC-002I implements a device-agnostic way to admit an unknown legacy decoder only after it demonstrates the exact required Client workload.

## Goals

- Preserve the V1 Client profile: AVC/H.264, 1280x720, 60 fps, Surface output, and the existing 8 Mbps Host stream envelope.
- Keep explicitly classified software-only decoders unavailable by default.
- Permit an unknown legacy candidate to establish eligibility through bounded active qualification.
- Contain codec crashes, timeouts, and Binder death away from the main Warpnect process.
- Keep Client and Host eligibility separate and truthful.
- Reuse existing WNCP video availability rather than change a wire contract.

## Non-Goals

- No device allowlist, Galaxy-S7 exception, or API-26 unconditional allow.
- No silent software fallback, 60-to-30 fps downgrade, or reduced resolution.
- No adaptive profile, performance-policy, or Host-compatibility expansion beyond the accepted Client qualification design.
- No change to RFC-002B encoder semantics, capture, Shizuku, privileged Input, H1, MediaProjection, root, or Phase 7 latency work.
- No online hardware database as runtime authority.

## Current Eligibility Path

~~~text
Client eligibility
        -> AVC decoder discovery and exact static checks
        -> framework classification when available
        -> legacy software-family exclusion when unavailable
        -> bounded active qualification for an unknown legacy candidate
        -> local video capability
        -> existing WNCP video availability
~~~

`AndroidVideoDecoderDiscovery` enumerates regular AVC decoders and checks dimensions plus `areSizeAndRateSupported(1280, 720, 60)`. `AndroidLocalCapabilityCollector` makes Client video ready only when decoder discovery succeeds. It does not use capture or Input injection to make that Client decision. Encoder discovery may appear in a Client diagnostic snapshot, but Host encoder availability is not the Client video-ready predicate. Shizuku and privileged capture are also outside the Client decoder decision.

## Legacy Classification Comparison

Before RFC-002I, Warpnect rejected API 26-28 AVC candidates when framework classification was unavailable. Media3 treats API 29 framework classification as authoritative where it is available, and uses codec-name heuristics on earlier Android versions to identify well-known software families. Its legacy result is an approximation, not a platform guarantee.

RFC-002I adopts only the conservative part of that model: a known software-family result may prevent a candidate from consuming an active qualification attempt. A non-software-family name is not a positive hardware classification and can advance only to the exact static checks followed by active qualification. This avoids both assumptions that `Unknown` means software and that a vendor-style name proves real-time offload.

Supporting Android and Media3 behavior is documented by the [Android MediaCodecInfo reference](https://developer.android.com/reference/android/media/MediaCodecInfo) and [Media3 MediaCodecUtil source](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecUtil.java).

## Hardware Evidence

Hardware observations apply only to the tested device/build combination. They are not model-family or Android-version guarantees.

| Device class | Android/API | Selected AVC decoder | Classification | Evidence |
| --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | `c2.exynos.h264.decoder` | `hardware=true`, `software=false`, `vendor=true` | Reference Host; H1 real rendered-frame validation complete |
| Samsung SM-G960F | Android 10 / API 29 | `OMX.Exynos.avc.dec` | `hardware=true`, `software=false`, `vendor=true` | S22 Host to S9 Client reached first real rendered frame |
| Samsung SM-G935F | Android 8.0 / API 26 | `OMX.Exynos.avc.dec` | Framework metadata unavailable | Final same-UID active qualification PASS; S22 Host to S7 Client reached first real rendered frame |
| Aocwei X700_EEA | Android 13 / API 33 | `c2.mtk.avc.decoder` | Framework metadata available | S22 Host to tablet Client reached first real rendered frame and a post-recreation remote frame on a new Surface generation |

The physical S7 reports `OMX.Exynos.avc.dec`, `OMX.google.h264.decoder`, and `OMX.SEC.avc.sw.dec`. The Exynos candidate reports 32..3840 by 32..2160, 2x2 alignment, and 1280x720 at 60 fps support. The Google and SEC software candidates match known software-style naming families. That name evidence is only a conservative filter before active qualification; it does not prove hardware identity.

### Full-Envelope Decoder Calibration

The earlier 180-frame fixture remains preliminary geometry and pacing evidence only. Although its
encoder was configured for 8 Mbps, it produced approximately 0.56 Mbps. It is not a V1
qualification fixture.

Calibration used the byte-identical `rfc002i-avc-720p60-full-v2` fixture generated once on the
API 36 S22 using the current production `AndroidVideoEncoderFormatFactory` request. Its
deterministic screen-like grid, moving diagnostic panel, and controlled transitions prevent CBR
from collapsing to a solid-color stream without treating unconstrained random noise as
representative content. It uses no capture, network, or pixel readback. The fixture is a tracked,
read-only production asset
`app/src/main/assets/video/rfc002i-avc-720p60-full-v2.fixture`; the qualifier verifies its digest
before decoding it.

| Property | Measured value |
| --- | --- |
| SHA-256 | `554F1E7AD82F5DFDE40BF4D276F8F76C0170D41908C3B8554C652F55B17D86E0` |
| Duration / access units | 6,000 ms / 360 |
| Geometry / cadence | 1280x720 / 60 fps |
| Encoder request | AVC Surface input, strict CBR target 8,000,000 bps, one-second I-frame interval |
| Observed AVC SPS | profile IDC 100 (High), level IDC 32 (Level 3.2) |
| Actual payload | 6,450,442 bytes, 8,600,417 bps average |
| GOP / keyframes | six keyframes, maximum 60 frames per GOP |
| AU distribution | 100,796-byte largest AU; 20,875-byte p95 AU |

The production factory does not freeze `KEY_PROFILE` or `KEY_LEVEL` as a cross-device wire
contract. High@3.2 is evidence for this versioned reference fixture, not a new SCL, WNSN, or
peer-negotiated profile requirement. A material encoder-format or fixture change must increment
the fixture/qualification version.

The same bytes were paced to Surface decoders three times on each hardware candidate:

| Decoder | Outputs | Surface callbacks | First output | First Surface callback | Maximum output gap | Maximum Surface gap | EOS wall time |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S22 `c2.exynos.h264.decoder` | 360/360 each run | 348, 347, 346 | 56, 54, 53 ms | 84, 65, 60 ms | 77, 87, 80 ms | 86, 93, 87 ms | 6,009, 6,012, 6,011 ms |
| S9 `OMX.Exynos.avc.dec` | 360/360 each run | 359, 356, 358 | 52, 52, 51 ms | 55, 55, 55 ms | 33, 31, 34 ms | 38, 37, 41 ms | 6,011, 6,009, 6,010 ms |
| S7 `OMX.Exynos.avc.dec` | 360/360 each run | 360, 359, 360 | 74, 69, 86 ms | 81, 73, 93 ms | 34, 31, 29 ms | 35, 33, 29 ms | 6,013, 6,011, 6,012 ms |

All runs configured and started successfully, reached EOS, and reported no fatal codec error. The
S7 had one 19 ms input wait in the first run and no unrecovered input starvation. The S22 callback
counts show why qualification cannot demand one SurfaceTexture callback per submitted AU even for a
known-good production decoder.

One diagnostic-only S7 run selected `OMX.google.h264.decoder`. It also completed 360 outputs and
360 Surface callbacks, with first output/presentation at 137/164 ms and maximum output/Surface gaps
of 50/49 ms. This reinforces the software rule: an idle-device fixture result does not authorize an
explicitly known software-family decoder as a production fallback. Instrumentation-process CPU time
is not reliable hardware-offload evidence; no decoder-block power or clock transition was exposed
safely on the S7.

### Tablet Display and Role Evidence

The API 33 tablet rendered a real remote S22 stream in portrait and landscape. Both traces reached the production first-rendered-frame event. A bounded rotation during streaming did not fail the Session. The tablet now also has Shizuku Input permission, but that is unrelated to Client decoder qualification and does not change this evidence.

`AndroidVideoRenderController` increments a local `surfaceGeneration`, stops the decoder on target
destruction, awaits a keyframe, and re-prepares it for the new target. RFC-002I-F added a local-only
observer that correlates `destroyed generation N -> available generation N+1 -> decoder prepared ->
remote frame rendered`. On the final build the tablet produced that sequence from generation 1 to
generation 2 after a real background/foreground Surface lifecycle transition. This changed neither
wire data nor renderer ownership.

The tablet's newly granted Shizuku Input permission is not Client-decoder evidence. A later bounded tablet-Host attempt still reported `ShizukuPermissionRequired` during local Host capability collection, before media startup. Tablet-to-S22 reverse-role validation therefore remains inconclusive and outside this RFC: the result is an Input/privilege-provider environment boundary, not decoder, capture, or renderer evidence.

## Role Separation

| Role | Required for video eligibility | Not a Client video prerequisite |
| --- | --- | --- |
| Client | Security/session support, protected receive, qualified AVC decoder, render Surface | Local capture, local encoder, privileged Input injection, Shizuku |
| Host | Existing RFC-002B encoder policy, qualified capture backend, existing Session/feature requirements | Client decoder qualification alone |

The local UI exposes Client and Host availability separately. A device that qualifies as a Client but lacks Host prerequisites is not described as wholly unsupported.

## Implemented Qualification Design

### Modern Android

On API 29 and later, a positive framework hardware classification remains authoritative. The candidate must still pass exact static requirements: decoder role, AVC MIME, Surface output, dimensions, alignment, 1280x720, 60 fps, and the current V1 stream configuration. A classified software-only decoder remains unavailable. A framework-unknown candidate follows the legacy path rather than being admitted implicitly.

### Legacy Android

On releases without framework classification, Warpnect first performs the same exact static format inspection. A legacy name heuristic may exclude well-known software families from active probing. It MUST NOT prove that an unfamiliar or vendor-style candidate is hardware accelerated. Any remaining unknown candidate needs a successful active qualification before it can supply Client video.

### Active Qualification

The active decoder qualifier is cold-path work, not part of a Session or a media hot path. It MUST:

1. Use the selected decoder and exact V1 AVC profile.
2. Use a deterministic, versioned fixture whose actual encoded envelope covers the V1 profile. A configured bitrate target alone is insufficient.
3. Configure and start the decoder with a Surface output, pacing access units at 60 fps.
4. Record bounded local counters: accepted inputs, released outputs, presentation evidence, first output, stalls, fatal errors, and elapsed time.
5. Avoid pixel readback, Bitmaps, screen capture, Session traffic, networking, and persistent media output.
6. Run off Main and outside the live decoder owner.

The production fixture is a versioned, read-only application asset derived from the calibrated
reference envelope. It is decoded only by the qualifier; it is not persisted as output and is not
sent to a peer. This version requires 360 access units paced at 60 fps over six seconds, and records
the fixture digest before use.

The following pass criteria are calibration-derived, not a latency benchmark:

| Requirement | Threshold | Calibration basis |
| --- | --- | --- |
| Codec lifecycle | configure and start succeed; zero fatal codec errors; EOS observed | every S22/S9/S7 Exynos reference run |
| Accepted and decoded AUs | 360 submitted and 360 decoder outputs | every reference run |
| Surface presentation | at least 342 of 360 callbacks (95%) | the lowest known-good S22 result was 346/360; the threshold permits four additional coalesced callbacks, not a quality downgrade |
| Output / Surface continuity | neither maximum gap exceeds 125 ms | worst reference Surface gap was 93 ms; the 32 ms margin is approximately two 60 fps periods |
| Completion | EOS within 6,500 ms of paced input start | worst reference completion was 6,013 ms; the remaining 487 ms is bounded drain/scheduler margin, while the gap rules detect sustained starvation |
| Input starvation | no input wait reaches the existing one-second bounded acquisition limit | the S7's one 19 ms wait recovered; a bounded wait is diagnostic until it becomes unrecovered starvation |

First-output and first-presentation timing remain diagnostics. They vary with cold codec startup and
are not used as a Phase-7-style latency threshold.

### Containment and Failure Semantics

RFC-002I selects a dedicated normal-app-UID `:decoderProbe` Service process rather than extending
RFC-002B's `:codecProbe`. The existing service, AIDL contract, request key, timeout, and quarantine
are explicitly encoder/CBR-specific. A sibling process keeps decoder fixture lifetime, Binder
operation, timeout, cache, quarantine, and diagnostics independently typed, so an encoder probe
cannot poison an unrelated decoder qualification state. Both probe processes remain application-
private, non-isolated, same-UID processes. Neither uses Shizuku, shell, root, capture, a Session,
network, or production media ownership.

The decoder probe has a bounded 6,500 ms execution deadline plus a bounded bind allowance. Its
Binder result is exactly one local state:

| Result | Local capability mapping |
| --- | --- |
| `PASS` | qualified candidate may supply Client video |
| `FAIL` (`NORMAL_REJECTION`, configure/start failure, or insufficient performance) | Client video unavailable for this candidate/environment |
| `INCONCLUSIVE` (timeout, Binder death, process death, or service unavailable) | Client video unavailable; no optimistic retry |

All terminal outcomes leave the main process alive and responsive. Timeout/process/Binder death also
sets current-main-process quarantine for the exact key. There is no hidden retry loop, no live
Session decoder switch, and no software fallback.

## Qualification State Machine

~~~text
discover AVC candidates
        -> exact static format checks
        -> framework classification when available
        -> hardware classified: select
        -> software classified or known software family: unavailable
        -> unknown non-software-family: active qualification
                -> pass: qualified/select
                -> normal failure: unavailable
                -> timeout/process death: inconclusive/unavailable
        -> existing local Client video availability
        -> existing WNCP video-available state
~~~

Detailed qualification reasons remain local diagnostics and future local UI state. They do not require a peer-visible diagnosis or a new WNCP field.

## Cache and Invalidation

Active results use a bounded persistent cache keyed by: qualification algorithm version, fixture
identifier and SHA-256, Warpnect V1 video-profile compatibility version, selected codec component
identity, Android `Build.FINGERPRINT`, and a media-runtime compatibility version. The key contains
no model allowlist, peer identity, screen data, or network data. Any relevant encoder/fixture,
decoder, firmware, or algorithm change produces a new key.

`PASS`, `FAIL`, and `INCONCLUSIVE` are cached only for their exact key. Timeout, Binder death, and
process death additionally quarantine that key for the rest of the current main-process lifetime.
A restart may reuse the persistent exact-key result; an environment-key change is the explicit way
to requalify. Persistent negative or inconclusive results never form a vendor/model blacklist.

## Security, Privacy, and UI

The qualifier is local-only. It must not transmit or persist video, use a screen capture, collect screen content, record media, expose device identity to peers, alter pairing, or alter Session keys/SAS. It may emit only bounded structural diagnostics and typed results.

The local UI presents Client and Host availability independently without raw codec exceptions or build identities.

## Testing and Hardware Validation

Focused tests cover modern positive classification, classified software rejection, legacy software-family rejection, active pass/fail/timeout/process-death containment, cache/quarantine, no Shizuku involvement, unchanged live decoder ownership, role separation, and no runtime silent decoder fallback.

Hardware validation used the final APK `E49F01173420238D4C0B49236A45EE539D98CF12B7A245C9E374AF3E3231B31C` from implementation commit `3dce8cca8e9d9717386524d0d2a727485834635d`.

- On the API 26 S7, a cold probe ran in a distinct same-UID `:decoderProbe` process and passed with 360 inputs, 360 outputs, 358 Surface presentations, 12 ms maximum input wait, 35 ms maximum output gap, 37 ms maximum Surface gap, and EOS in 6,016 ms. The main app remained alive. A same-main-process query and a post-restart query both used the exact-key PASS cache without another probe process.
- The same S7 then completed the production S22 Host to S7 Client path through protected receive, decoder output, and `FIRST_REAL_RENDERED_FRAME_ON_CLIENT`. It used no software fallback or profile reduction.
- The API 29 S9 completed the same final-APK S22 Host Client trace through real remote rendering. Its framework hardware path did not launch `:decoderProbe`.
- The API 36 S22 physical codec inventory completed against the V1 Client profile without launching `:decoderProbe`; it retains framework hardware classification.
- The API 33 tablet completed real remote rendering and then, after an actual Surface lifecycle transition, recorded generation 1 destroyed, generation 2 available and decoder-prepared, followed by a remote access unit, decode output, and remote frame presentation on generation 2.

## Rejected Approaches

- Device-model allowlists, Galaxy-S7-specific exceptions, and API-26 unconditional allow.
- Treating every vendor-named codec as hardware or every unknown codec as software.
- Online hardware databases as runtime authority.
- Silent software fallback or profile downgrade.
- Blocking installation because a runtime role capability is unavailable.
- Reopening H1 or changing RFC-002B encoder semantics for a Client decoder problem.

## Protocol and ABI Impact

This design uses the existing local video-available result and WNCP capability representation. It changes no Architecture Version, SCL Protocol Version, Native Bridge ABI, PacketHeader, PayloadType, WNSH, WNSD, Video Payload V1, security framing, JNI contract, MetricId, or EventTypeId. Any need to change a frozen contract blocks implementation for architecture review.

## Resolved Design Questions

- The version-2 reference fixture, its actual AVC High@3.2 envelope, hash, GOP, and AU distribution
  are defined above. A material fixture or encoder-format change requires an incremented
  fixture/qualification version.
- Presentation-ratio, continuity, lifecycle, and completion thresholds are defined above from S22
  and S9 references. They are qualification guards, not one-way or end-to-end latency claims.
- Decoder qualification uses the dedicated same-UID `:decoderProbe` process.
- Post-Surface-recreation proof is a local surface-generation correlation ending in a new decoder
  frame-rendered callback. RFC-002I-F implemented and hardware-validated that observer without a
  protocol change.

## Implemented Sequence

1. **002I-A:** local typed qualification state model and preserved role mapping.
2. **002I-B:** conservative legacy inspection and exact decoder profile checks.
3. **002I-C:** crash-contained same-UID `:decoderProbe` active decoder qualifier with full-envelope fixture.
4. **002I-D:** scoped persistent cache and current-process quarantine.
5. **002I-E:** role-aware Client eligibility and local UI semantics.
6. **002I-F:** focused and hardware validation, including real remote S7 rendering.

## Completion Criteria

The full-envelope fixture, objective qualification thresholds, and same-UID containment boundary were calibrated before acceptance. Focused tests and final exact-artifact hardware validation then demonstrated a real remote rendered frame on the qualified legacy Client, no software fallback, no quality reduction, modern-path preservation, and post-Surface-recreation presentation. RFC-002I is implemented for the tested device/build environments.
