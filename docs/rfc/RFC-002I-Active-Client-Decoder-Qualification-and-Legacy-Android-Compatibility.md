# RFC-002I - Active Client Decoder Qualification & Legacy Android Compatibility

Project: Warpnect

Status: Draft. This document records evidence and proposes a production design. It does not change current Client eligibility, reopen H1, or modify RFC-002B.

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1.

## Context and Problem

RFC-002D requires a hardware AVC decoder for Client video. Android API 29 and later exposes framework hardware/software classification through `MediaCodecInfo`; API 26-28 does not. The current `AndroidVideoDecoderDiscovery` therefore assigns every candidate on those releases `Unknown`. `VideoDecoderSelector` returns `HardwareClassificationUnavailable` before it evaluates hardware, size, and rate selection.

That behavior is conservative, but the absence of framework metadata is not proof that a decoder is software-only or inadequate. Conversely, a vendor-style name and static format support do not prove real-time performance. Warpnect needs a device-agnostic way to admit an unknown legacy decoder only after it demonstrates the exact required Client workload.

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
- No production implementation in this RFC.
- No change to RFC-002B encoder semantics, capture, Shizuku, privileged Input, H1, MediaProjection, root, or Phase 7 latency work.
- No online hardware database as runtime authority.

## Current Eligibility Path

~~~text
Client eligibility
        -> AVC decoder discovery
        -> framework hardware classification
        -> VideoDecoderSelector
        -> local video capability
        -> WNCP
~~~

`AndroidVideoDecoderDiscovery` enumerates regular AVC decoders and checks dimensions plus `areSizeAndRateSupported(1280, 720, 60)`. `AndroidLocalCapabilityCollector` makes Client video ready only when decoder discovery succeeds. It does not use capture or Input injection to make that Client decision. Encoder discovery may appear in a Client diagnostic snapshot, but Host encoder availability is not the Client video-ready predicate. Shizuku and privileged capture are also outside the Client decoder decision.

## Legacy Classification Comparison

Warpnect currently treats all API 26-28 AVC candidates as `Unknown` and rejects when no framework-classified candidate exists. Media3 treats API 29 framework classification as authoritative where it is available, and uses codec-name heuristics on earlier Android versions to identify well-known software families. Its legacy result is an approximation, not a platform guarantee.

RFC-002I adopts only the conservative part of that model: a known software-family result may prevent a candidate from consuming an active qualification attempt. A non-software-family name is not a positive hardware classification and can advance only to the exact static checks followed by active qualification. This avoids both assumptions that `Unknown` means software and that a vendor-style name proves real-time offload.

Supporting Android and Media3 behavior is documented by the [Android MediaCodecInfo reference](https://developer.android.com/reference/android/media/MediaCodecInfo) and [Media3 MediaCodecUtil source](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecUtil.java).

## Hardware Evidence

Hardware observations apply only to the tested device/build combination. They are not model-family or Android-version guarantees.

| Device class | Android/API | Selected AVC decoder | Classification | Evidence |
| --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | Production decoder | Framework metadata available | Reference Host; H1 real rendered-frame validation complete |
| Samsung SM-G960F | Android 10 / API 29 | `OMX.Exynos.avc.dec` | `hardware=true`, `software=false`, `vendor=true` | S22 Host to S9 Client reached first real rendered frame |
| Samsung SM-G935F | Android 8.0 / API 26 | `OMX.Exynos.avc.dec` | Framework metadata unavailable | Current Client policy rejects before WNSN with `HardwareClassificationUnavailable` |
| Aocwei X700_EEA | Android 13 / API 33 | Production Client decoder | Framework metadata available | S22 Host to tablet Client reached first real rendered frame |

The physical S7 reports `OMX.Exynos.avc.dec`, `OMX.google.h264.decoder`, and `OMX.SEC.avc.sw.dec`. The Exynos candidate reports 32..3840 by 32..2160, 2x2 alignment, and 1280x720 at 60 fps support. The Google and SEC software candidates match known software-style naming families. That name evidence is only a conservative filter for a future active probe; it does not prove hardware identity.

### S7 Active Laboratory Evidence

A test-only deterministic fixture was decoded by `OMX.Exynos.avc.dec` with Surface output. It paced 180 AVC access units at 1280x720 and 60 fps over approximately three seconds. The decoder produced 180 outputs and 180 Surface-frame callbacks. First decoder output was approximately 75 ms, first Surface frame approximately 80 ms, and no codec error occurred.

The fixture encoder had an 8 Mbps target but generated approximately 0.56 Mbps of simple synthetic content. Therefore this is geometry, cadence, Surface-output, and basic pacing evidence only; it does not prove the V1 8 Mbps content envelope. The same simple fixture also ran through the Google software decoder, reinforcing that neither a codec name nor an idle-device microbenchmark may authorize a software fallback. The observed instrumentation-process CPU time is not direct hardware-offload evidence. Readable S7 codec configuration provides strong indirect support; no decoder-block power or clock transition was safely observable.

### Tablet Display and Role Evidence

The API 33 tablet rendered a real remote S22 stream in portrait and landscape. Both traces reached the production first-rendered-frame event. A bounded rotation during streaming did not fail the Session, but existing diagnostics did not force a render-Surface recreation or observe a post-recreation presentation event.

After the tablet received Shizuku permission, it entered Host lifecycle readiness. Tablet-to-S22 reverse-role validation remains inconclusive because the S22 could not resolve the tablet as a host after the clean test reset. This is a discovery-environment boundary, not decoder, capture, or renderer evidence. It must be repeated independently.

## Role Separation

| Role | Required for video eligibility | Not a Client video prerequisite |
| --- | --- | --- |
| Client | Security/session support, protected receive, qualified AVC decoder, render Surface | Local capture, local encoder, privileged Input injection, Shizuku |
| Host | Existing RFC-002B encoder policy, qualified capture backend, existing Session/feature requirements | Client decoder qualification alone |

A future UI may expose Client and Host availability separately. A device that qualifies as a Client but lacks Host prerequisites must not be described as wholly unsupported.

## Proposed Qualification Design

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

Before implementation, the algorithm must define versioned pass thresholds. They must require successful configure/start, valid Surface presentation, full-profile pacing, no fatal codec error, no unrecovered output stall, and a bounded minimum presentation ratio. Duration, minimum ratio, and maximum presentation gap must be calibrated against S22 and S9 baselines using an actual 8 Mbps fixture. The current S7 result does not set those thresholds.

### Containment and Failure Semantics

RFC-002B already provides a disposable same-UID `:codecProbe` process for encoder qualification. RFC-002I may reuse that process only if its IPC protocol keeps encoder and decoder operations, caches, timeouts, and diagnostics independent. Otherwise it must use an equally narrow normal-app-UID qualifier. It MUST NOT use Shizuku, shell, root, or an isolated UID.

Process death, Binder death, timeout, configuration failure, and insufficient performance become typed local results. They must not terminate the main application, block the UI, trigger hidden retries, or become an optimistic Client capability. There is no active-Session decoder switch.

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

Active results require a bounded persistent cache. Its key MUST include qualification algorithm and fixture version, Warpnect video-profile/runtime compatibility version, selected codec identity, Android build identity sufficient to invalidate firmware/codec changes, result type, and qualification time. A changed key invalidates the entry.

Process death or timeout also requires current-main-process quarantine so WNCP/capability collection cannot repeatedly launch destructive probes. Persistent negative results are scoped to that exact key, never a vendor or model blacklist.

## Security, Privacy, and UI

The qualifier is local-only. It must not transmit or persist video, use a screen capture, collect screen content, record media, expose device identity to peers, alter pairing, or alter Session keys/SAS. It may emit only bounded structural diagnostics and typed results.

The future UI should present Client and Host availability independently without raw codec exceptions or build identities.

## Testing and Hardware Validation

Implementation requires focused tests for modern positive classification, classified software rejection, legacy software-family rejection, active pass/fail/timeout/process-death containment, cache/quarantine, no Shizuku involvement, unchanged live decoder ownership, role separation, and no runtime silent decoder fallback.

Hardware validation must include the S22 reference, API 29 S9, API 26 S7, and another legacy/non-Samsung device where available. A future S7 production admission must demonstrate a real remote rendered frame using the same final APK. Tablet validation must include explicit post-Surface-recreation presentation evidence.

## Rejected Approaches

- Device-model allowlists, Galaxy-S7-specific exceptions, and API-26 unconditional allow.
- Treating every vendor-named codec as hardware or every unknown codec as software.
- Online hardware databases as runtime authority.
- Silent software fallback or profile downgrade.
- Blocking installation because a runtime role capability is unavailable.
- Reopening H1 or changing RFC-002B encoder semantics for a Client decoder problem.

## Protocol and ABI Impact

This design uses the existing local video-available result and WNCP capability representation. It changes no Architecture Version, SCL Protocol Version, Native Bridge ABI, PacketHeader, PayloadType, WNSH, WNSD, Video Payload V1, security framing, JNI contract, MetricId, or EventTypeId. Any need to change a frozen contract blocks implementation for architecture review.

## Open Questions

1. What exact profile/level, access-unit structure, and actual 8 Mbps envelope should the qualification fixture represent?
2. Which output-ratio and maximum-gap thresholds are validated by S22/S9 baselines without becoming a Phase 7 latency benchmark?
3. Can `:codecProbe` safely host independent decoder operations, or should decoder work use a sibling qualifier service?
4. Which renderer callback provides portable post-Surface-recreation presentation evidence?

## Proposed Implementation Sequence

1. **002I-A:** local typed qualification state model and preserved role mapping.
2. **002I-B:** conservative legacy inspection and exact decoder profile checks.
3. **002I-C:** crash-contained same-UID active decoder qualifier with full-envelope fixture.
4. **002I-D:** scoped persistent cache and current-process quarantine.
5. **002I-E:** role-aware Client eligibility and local UI semantics.
6. **002I-F:** focused and hardware validation, including real remote S7 rendering.

## Acceptance Criteria

RFC-002I may be accepted only after its full-envelope fixture, objective qualification thresholds, and same-UID containment boundary are defined from representative baseline evidence. It may be implemented only after focused tests and hardware validation demonstrate a real remote rendered frame on a qualified legacy Client, without a software fallback or quality reduction.
