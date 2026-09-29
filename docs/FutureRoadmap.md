# Future Roadmap

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This roadmap is architectural context. Phase 1 core networking and Phase 2 core video implementation are complete.

## Phase 0 - Architecture, Repository & Toolchain

Status: Complete.

Implemented, defined and frozen Warpnect's core architecture, the boundaries between Kotlin/Android and C++/SCL, repositories, toolchains, and invariants.

- RFC-000A - Architecture Audit & Stabilization [complete]
- RFC-000B - Architecture Documentation & Invariants [complete]
- RFC-000C - Final Architecture Hardening [complete]
- RFC-000D - Repository & Build System Finalization [complete]

## Phase 1 - SCL Core Networking Engine

Status: Complete.

Implemented the platform-independent C++20 networking core:

- RFC-001A - SCL Packet & Runtime Foundation [complete]
- RFC-001B - SCL UDP Transport Engine [complete]
- RFC-001C - Fragmentation & Reassembly [complete]
- RFC-001D - Loss Detection, NACK & Recovery [complete]
- RFC-001E - Reed–Solomon FEC [complete]
- RFC-001F - Clock Synchronization & Network Telemetry [complete]
- RFC-001G - Phase 1 Integration & Benchmarks [complete]

## Phase 2 - Video Pipeline

Status: Core implementation complete through RFC-002G. RFC-002H is implemented supplemental
Android capture compatibility architecture.

Implemented:

- RFC-002A - Android Privileged Video Capture Foundation [complete]
- RFC-002B - Android Hardware Video Encoder Pipeline [complete]
- RFC-002C - Encoded Video to SCL Transport Integration [complete]
- RFC-002D - Android Hardware Video Decoder Pipeline [complete]
- RFC-002E - Low-Latency Rendering Pipeline [complete]
- RFC-002F - End-to-End Video Streaming [complete]
- RFC-002G - Video Latency, Recovery and Performance Tuning [complete]
- RFC-002H - Android Privileged Screen Capture Backend & Privilege Boundary [implemented]

Phase 2 core history remains complete through RFC-002G. RFC-002H production capture has hardware
validation on legacy API 30/API 31 and modern API 36 paths. H1 real-device media validation is
complete. RFC-002B strict-CBR metadata qualification now uses a disposable same-UID app process,
which contains vendor codec failure without terminating Warpnect. Final same-UID qualification
passed on two API 36 S22 devices plus tested API 30 and API 31 A41 devices. The A41
`InputApiUnavailable` investigation established a narrow Phase 4 Android platform-adapter defect:
the previous resolver assumed `InputManagerGlobal`, which is absent on tested API 30, API 31, and API
33 hardware. The production resolver now retains the modern adapter and falls back through capability
qualification to the legacy `InputManager` adapter. Local production injection of key, touch, pointer,
and joystick events passed on API 30, API 31, and API 33 under the Shizuku shell UserService.
The SystemAudio startup investigation found a capability false positive rather than an Input defect.
`AudioService` authorizes AudioPolicy registration against the Shizuku UserService Binder caller (shell
UID 2000), which does not hold `MODIFY_AUDIO_ROUTING`; the prior package-attributed check advertised a
channel that could not start. Capability qualification now checks the UserService's own permission, so
SystemAudio is omitted before setup when that caller cannot register its AudioPolicy. Post-correction
API 30 and API 31 A41 Host-to-S7 Sessions authenticated, committed setup, and started media without
`SystemAudioStartFailed`. A new human touch on the S7 Client was correlated through the protected Session,
legacy InputManager injection, and a Warpnect-owned target on each A41 Host, closing the two A41 reverse-input
validation targets.
The physical API 36 modern-input regression passed locally on the S22: `ModernInputManagerGlobal` was
selected and key, touch, pointer, and joystick events were accepted and observed. A separate S22 SystemAudio
investigation found an audio-only API 36 restriction. After the Shizuku UserService clears the incoming
application Binder identity, `AudioPolicy` registration can proceed as shell UID 2000, but the first
production loopback `AudioRecord` is uninitialized because AudioFlinger rejects the incoherent
`uid=2000, package=io.warpnect` attribution. The capability preflight now performs that same bounded
prepare/start/stop path before WNCP and therefore truthfully omits SystemAudio on this runtime. It neither
falls back after commitment nor generalizes the result to Samsung or API 36 devices beyond the tested S22.
The S22 reverse-input E2E result remains uncounted; this SystemAudio condition is separate from the modern
Input resolver result.
The API 33 tablet was revalidated with the same current production SystemAudio path. Its prior
`AudioRecordCreationFailed` no longer reproduces after the generic Binder-identity correction: the real
preflight prepares, starts, reads PCM, and stops successfully. A normal-app 48 kHz stereo `USAGE_GAME` tone
produced 68,400 captured frames, 116,112 non-zero samples, peak 8,191, and RMS 5,340.6 through the privileged
loopback path, closing the tablet SystemAudio compatibility debt as `SYSTEM_AUDIO_SUPPORTED`.
The formerly named tablet “pre-auth” blocker was instead a diagnostic false negative: the tablet's global
log threshold suppressed `Log.d` breadcrumbs, and the harness also used the S9's non-authoritative legacy
render callback as a media-success gate. With debug-only breadcrumbs emitted at `INFO` and media success
defined by Host media start plus Client decoder output, five Tablet-to-S9 first-attempt Sessions passed via
normal semantic teardown. Authentication, WNCP, setup, capture, encoder, transport, Client decode, and
Surface output all completed; a supplementary screenshot showed the tablet Host display on the S9. No model,
OEM, or API-specific runtime behavior changed.

On 2026-09-09, a subsequent tablet SystemAudio remote-E2E attempt found a changed external precondition rather
than an audio-pipeline regression. Shizuku Manager 13.6.0.r1086.2650830c starts every tested Warpnect
UserService as shell but fails before Binder publication: the minimal debug Binder plus production capture,
audio, and input classes all die in `LoadedApk.makeApplication`. The tablet's MediaTek framework calls
`Application.getProcessName().equals(mPackageName)` in its OEM resource-preload guard, but
`ActivityThread.systemMain()` has no application process name in the Shizuku UserService bootstrap, producing
the recorded null-pointer exception. Shizuku 13.6 introduced the forced `makeApplication` path; the identical
APK and provider version publish all four Binders on the A41. This is
`SHIZUKU_13_6_MAKEAPPLICATION_TABLET_FRAMEWORK_INCOMPATIBILITY`, not an API33-wide, OEM-wide, or audio-format
claim. The provider removes an unstarted service after 30 seconds without a `ServiceConnection` terminal
callback. Warpnect therefore bounds that local bind by the provider's own deadline and truthfully reports
`PrivilegedServiceUnavailable` instead of blocking Host readiness forever. A controlled provider-only A/B held
the Tablet build, Warpnect APK, app data, and UserService arguments constant and changed only the signed official
Shizuku Manager APK. The prior official 13.5.4.r1049.0e53409 APK
(`A05832CE3716AFB1FCCCF46F348006D2A296CA777E1FF3D223797DC74D06B31F`) published the minimal,
capture, audio, and input Binders on the Tablet; the same four binds also passed on the A41 control. Under that
provider the production tablet SystemAudio controller again prepared, started, captured a normal-app 48 kHz
stereo tone, and stopped cleanly (70,080 frames, 115,632 non-zero samples, peak 8,191, RMS 5,265.3). This proves
`SHIZUKU_13_6_PROVIDER_REGRESSION_CONFIRMED_FOR_TABLET_FRAMEWORK`, while remaining deliberately narrower than
an API33-, OEM-, or all-13.6 claim. The older provider is diagnostic evidence, not a Warpnect version requirement:
13.6 remains the current official stable and no official fixed release was available during the experiment. A
subsequent clean Tablet-to-S9 run under 13.5.4 completed the technical remote SystemAudio chain. The normal
997 Hz PCM16/stereo/48 kHz `AudioTrack` tone ran for 60 seconds and wrote 2,875,396 source frames. On the Tablet,
the privileged recorder, Shared PCM Ring, encoder, payload producer, and UDP sender remained active; the 50-second
snapshot showed 69,998 payloads and 69,999 generated/sent datagrams, ring high-water mark 7, no capture drop/overrun, no `WouldBlock`, and no
transport or Session error. On the S9, the receiver decoded 71,437 audio frames by the 60-second snapshot and the
native Oboe playback consumer advanced its PCM Playback Ring from 13,499,760/13,499,760 written/consumed frames to
16,251,120/16,250,976, bounded by its 960-frame high-water mark, with zero xruns and no receiver or Session error.
ADB stayed connected for the full measurement. `TABLET_SYSTEM_AUDIO_REMOTE_E2E_PASS` is therefore closed as
technical E2E evidence; human audibility is pending. This adds no device allowlist, audio fallback, payload change,
or Session retry. Official Shizuku 13.6 was restored after the controlled run and remains incompatible with this
Tablet's UserService bootstrap until upstream supplies a compatible provider revision.

The independent S9/API29 and S7/API26 Host coverage pass is complete. With the actual Shizuku shell-UID
UserService, S9 lacks `MODIFY_AUDIO_ROUTING`, so its production SystemAudio preflight returns
`PermissionDenied` and omits the channel before WNCP. S7 has the same real privilege-provider bootstrap but a
separate framework-contract boundary: PCM Shared Ring V1 uses `android.os.SharedMemory`, introduced in API27.
The capability path now returns `UnsupportedPlatform` on an API below that requirement and the direct prepare
path returns the same typed result without loading the unavailable class. This is a generic API-contract guard,
not a device, OEM, or blind API success rule. Neither Host can truthfully select SystemAudio, so local PCM and
remote E2E are not applicable; both classifications are complete. The task added no audio fallback, profile
change, payload/ring amendment, or Session retry.

RFC-002I is implemented supplemental Client decoder qualification for legacy Android where framework
hardware classification is unavailable. It uses conservative static inspection plus a contained,
same-UID active decoder qualification only for unknown legacy candidates. It does not alter RFC-002D,
RFC-002B, or the completed H1 validation.

Client presentation investigation identified a local `SurfaceView` composition defect: on affected
Samsung Clients, decoded buffers were behind the opaque Compose window buffer. The production view now
uses `setZOrderOnTop(true)`. A real A41 API 31 Host-to-S7 API 26 Client Session is human-confirmed
visible after the fix. S9 API 29 and tablet API 33 both visibly present the same local MediaCodec
fixture. The S9 now also passes A41-hosted authentication, WNCP, setup, receiver startup, AVC
configuration, decoder startup, first access-unit decode, and Surface release. The user then physically
confirmed the A41 Host screen, Android Home, and ordinary Host applications visibly streamed on the S9.
`S9 PRODUCTION VIDEO PIPELINE VALIDATED` closes the former S9 media-start/presentation debt. The tablet's
separate remote-session investigation is now validated through the Host pipeline and S9 Client decoder/output.
A follow-up A41 API 31-to-S7
capture-scope trace selected
the physical logical display (`source_display_id=0`, `layer_stack=0`) and remained active while the Host
left Warpnect. Home, Settings, and the notification shade were each visible on the S7; the user confirmed
the Settings view directly. `HOST FULL-DISPLAY CAPTURE VALIDATED` therefore closes the app-window capture
concern. The then-open growing-latency observation is resolved below as a Video Resync Control V1
sender-ownership defect, not a capture or presentation regression.

Privileged helper lifecycle validation then found a real Android ownership defect rather than an expected
Shizuku retention policy: unbinding a non-daemon UserService disconnects it but does not terminate its
process. The capture, audio, and input helpers now implement the reserved UserService destroy transaction,
clean their local resource, and exit after their final gateway owner releases them; temporary SystemAudio
capability controllers also close in `finally`. Four A41 API 31 Host-to-S7 API 26 Client media Sessions,
including a Host Home/background/return interval, left no capture, audio, or input helper after normal
teardown. A bounded harness correction now reaches the genuine off-screen `Disconnect` control before
declaring it absent.

Cold capability/session-negotiation validation identified a readiness-ordering defect, not an undersized
protocol timeout. Previously, the Host synchronously collected its mandatory capability snapshot only after
receiving a Client WNCP offer, after the Client's negotiation window had started. Host readiness now completes
that exact snapshot before advertising discovery and reuses it when responding to WNCP. An A41 API 31 Host and
S7 API 26 Client passed their first deliberately cold exact-qualification Session through authentication,
setup, and media; a subsequent exact-cache-hit regression also passed. WNCP, qualification workloads and
thresholds, payloads, and security semantics were unchanged. The S9 API 29 control also completed WNCP,
setup, receiver startup, AVC configuration, decoder startup, first access-unit decode, Surface release, and
human-confirmed remote presentation. Its former `VideoPipelineStartFailed` lifecycle race is closed.

RFC-002B strict-CBR qualification now retains a `Supported` result across application-process restarts,
but only under a complete versioned exact key: algorithm, probe workload, target profile, codec identity,
exact AVC request, Build fingerprint, and media-runtime compatibility version. The A41/API 31 first
exact-key miss ran its two eligible strict-CBR probes and persisted their `Supported` results; later new
processes, force-stop/relaunch, and data-preserving reinstall reused those records without spawning
`:codecProbe`. Timeouts, probe death, and other transient failures remain process-local and the existing
probe-death quarantine is unchanged. This removes repeated qualification work without treating
compatibility as an install-lifetime or device-model judgment.

Session establishment and restart reliability is now validated for the A41/API 31 Host-to-S9/API 29
Client topology. The root cause was not discovery reachability or a timeout: after normal cancellation,
the application-scoped Client coordinator remained terminal `Closed` while the retained discovery model
could still make the UI look ready. A subsequent Connect was therefore rejected as busy before pairing.
The discovery-preserving Client attempt path also retained a `SessionProtectionController` that it had
already closed. Normal disconnect now returns the Client coordinator to reusable `Idle`, and a new
attempt obtains a fresh session-protection owner without reusing a cancelled runtime. Ten consecutive
normal first-attempt A41-to-S9 Session cycles passed through media and semantic teardown without process
kills, force-stops, reboot, Shizuku restart, or hidden retry. Client-only, Host-only, and dual app-process
restart cases each passed on their first attempt with preserved app data; the Host used its persistent
RFC-002B result and did not spawn `:codecProbe`. An A41-to-S7 technical regression also passed. This
preserves SAS, WNCP/WNSN, Session protection, and all frozen protocol/ABI contracts; it is a local
runtime ownership correction, not automatic retry behavior.

The growing-streaming-latency investigation then identified a freshness-correctness defect rather than a
throughput or quality limitation. When a Client detected a video discontinuity, it correctly entered
`WaitingForKeyFrame` and sent Video Resync Control V1. The Android production sender had not bound
`NativeVideoSenderControlRuntime`, so its Host never pumped that control channel or forwarded a real
keyframe request to the encoder. It kept sending frames the Client could not safely use, which made the
last visible image increasingly stale. The production binding now owns the existing V1 control runtime;
no profile, payload, FEC, decoder, quality, or frozen protocol/ABI semantics changed. Final 180-second
A41 API 31 Host-to-S9 API 29 and A41 API 31 Host-to-S7 API 26 runs with repeated harmless Host-display
updates kept reassembly/ready occupancy at 0-1, showed no timeout/full-window growth, continuously
released decoder output, and recovered every observed resync through a forwarded keyframe request.
`GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` closes the stale-frame accumulation debt. Absolute
end-to-end latency remains intentionally unmeasured without cross-device clock provenance; residual
fixed latency and general throughput work remain Phase 7 concerns.

## Phase 3 - Audio Pipeline

Status: Complete.

Implemented:

- RFC-003A - Android Low-Latency Audio Capture Foundation [complete]
- RFC-003B - Portable Ultra-Low-Latency Opus Encoder [complete]
- RFC-003C - SCL Audio Payload and Transport Integration [complete]
- RFC-003D - Portable Ultra-Low-Latency Opus Decoder [complete]
- RFC-003E - Android Ultra-Low-Latency Audio Playback [complete]
- RFC-003F - End-to-End Audio Streaming [complete]
- RFC-003G - Audio/Video Synchronization [complete]
- RFC-003H - Audio Latency, Recovery and Performance Tuning [complete]

## Phase 4 - Reverse Input

Status: Complete.

Implemented:

- RFC-004A - Portable Input Event Model and SCL Input Payload V1 [complete]
- RFC-004B - Android Input Capture Foundation [complete]
- RFC-004C - Reverse SCL Input Transport [complete]
- RFC-004D - Android Privileged Input Injection [complete]
- RFC-004E - Input Mapping, Coordinate and Device Semantics [complete]
- RFC-004F - End-to-End Reverse Input [complete]
- RFC-004G - Input Latency, State Convergence, Reliability and Performance Tuning [complete]

Phase 4 is implementation-complete. Real-device privileged injection, UHID availability, and
game-specific compatibility remain device-specific validation work.

## Phase 5 - Discovery and Secure Session Management

Status: implementation-complete. RFC-005I integrates the normal Android Host/Client composition,
the public-API LAN and Wi-Fi Direct backends, concrete Phase 2-4 prepared-transport adoption, and
live-transport migration ownership. Real-device validation remains separate from implementation
status.

- RFC-005A - Session Identity & Core Session Model [complete]
- RFC-005B - Local Network Discovery & Presence [complete]
- RFC-005C - Pairing & Trust Bootstrap [complete]
- RFC-005D - Authenticated Session Handshake [complete]
- RFC-005E - Session Keys, Packet Authentication & Anti-Replay [complete]
- RFC-005F - Capability, Role & Feature Negotiation [complete]
- RFC-005G - Endpoint, Channel & Stream Negotiation [complete]
- RFC-005H - Session Lifecycle, Disconnect & Reconnection [complete]
- RFC-005I - End-to-End Discovery & Secure Session Integration [complete]

## Phase 6 - Telemetry and Diagnostics

Status: implementation-complete. Real-device validation debt remains.

- RFC-006A - Unified Runtime Telemetry Model [complete]
- RFC-006B - Media Pipeline Metrics Integration [complete]
- RFC-006C - Network & Recovery Diagnostics [complete]
- RFC-006D - Latency Trace & Cross-Pipeline Correlation [complete]
- RFC-006E - Diagnostic Logging & Bounded Event History [complete]
- RFC-006F - Runtime Diagnostics UI [complete]
- RFC-006G - Session Reports & Benchmark Export [complete]
- RFC-006H - Diagnostics Integration & Validation [complete]

Phase 6 is implementation-complete. Host/JVM, Android build, ABI, and native validation are green;
real Android/hardware validation remains separately tracked because no device was attached.

## Phase 6.5 - Privileged Runtime Evolution

Status: validated DirectShell modern Host baseline; static per-capability
planning, optional Shizuku provider integration, and RFC-006J runtime
state/background Host readiness plus Step 2 persistent evidence and prepared
plan reuse are implemented, JVM-tested, and
hardware-admission validated on the currently tested A41/API31 and
Tablet/API33 DirectShell Hosts.

The validated modern DirectShell path uses user-authorized Wireless Debugging,
app-owned local ADB, app_process as shell uid 2000, and purpose-specific
DirectShell Video, SystemAudio, and Input servers. It has hardware evidence on
A41/API31, Tablet/API33, and S22/API36, with S9/API29 as the established
Client. The final modern SystemAudio matrix includes audible S9 confirmation
for every Host. DirectShell remains a carefully validated migration path, not
an unconditional release default.

The next milestones are:

- RFC-006I - Privileged Capability Planning and Composite Runtime [accepted; static layer implemented]
- Privileged Capability Matrix [implemented; JVM-tested; modern DirectShell Host admission validated]
- Privileged Capability Planner with static complete-plan selection [implemented; JVM-tested; modern DirectShell Host admission validated]
- Composite Host Plan ownership and controlled hardware validation [complete: A41/API31 to S9/API29; DirectShell Video/SystemAudio + Shizuku Input]
- Shizuku Optional Per-Capability Provider Integration [complete for controlled DEBUG composition]
- RFC-006J - Privileged Runtime Orchestration and Host Readiness [Step 1 lifecycle-validated; Step 2 process-restart/cache-to-freeze validated on A41/API31 and Tablet/API33]
- Runtime State Model and Background Host Readiness Coordinator [implemented: passive volatile observation, single-flight admission, generation-safe cleanup, and late RFC-006I plan freeze]
- Persistent Capability Evidence and Prepared Plan Reuse [implemented, JVM-tested, hardware-validated: bounded app-private positive evidence, exact compatibility keys, selective invalidation, advisory cache reconstruction, and mandatory late revalidation]
- DirectRoot Bootstrap + Root Recovery Authority Foundation [DEBUG/laboratory implemented, 783-test gated and AOSP/API26 emulator-validated; production Root authorization not implemented]
- DirectRoot Input Capability [implemented; exact AOSP/API26 laboratory QUALIFIED, 3/3 final-artifact cycles plus 1/1 active disconnect reset; 807-test gated; not physical Root validation]
- DirectRoot Video Capability [implemented; exact AOSP/API26 laboratory QUALIFIED, 3/3 frozen-artifact decoded-content cycles plus two active-loss cases; 841-test gated; no physical Root claim]
- DirectRoot SystemAudio Capability [implemented; exact AOSP/API26 lab QUALIFIED, 3/3 frozen-artifact OFF/ON PCM/ring cycles and actual Root-only plan freeze; 904-test gated; normal Host admission was a separate boundary]
- Complete DirectRoot Host Admission [normal application Enable/Disable Host and publication validated in exact AOSP/API26 lab; eight consecutive passes, 8/9 counted attempts with ninth ADB observation anomaly preserved; restart 1/1, loss 2/2, 931-test gated; no production/physical Root claim]
- Physical Root Device Qualification [next evidence boundary; no physical Root or production root-manager claim follows from the AOSP lab]
- In-Session Privileged Capability Provider Migration RFC [planned; live migration not implemented]
- In-Session Privileged Capability Provider Migration implementation [planned]
- Final privileged-runtime acceptance [planned]

The normal Host invariant remains Video plus SystemAudio plus Input. If no
complete qualified provider plan exists, Host is unavailable while Client mode
remains available. Shizuku is preserved as a future optional compatibility
provider rather than being unconditionally deleted.

RFC-006J Steps 1 and 2 leave Root-assisted Remediation, DirectRoot, Same-provider
Recovery, and Live Provider Migration NOT IMPLEMENTED. Passive application
startup never launches DirectShell feature servers or Warpnect Shizuku
UserServices; user Host admission re-observes runtime state, performs the
existing bounded RFC-006I admission, and freezes a plan only before Host
availability and discovery.

The laboratory foundation slice separates Root authority from Root capability
provision. It exposed volatile authenticated UID0 authority with no capabilities
at its original checkpoint. Production
Root-manager consent, physical Root validation and actual remediation remain
deferred. The lab authority passed 8/8 formal cases plus 2/2 resource cycles;
this does not qualify a physical Root device or product Root manager. See
`phase6_5/DirectRootBootstrapFoundation.md` for exact lab evidence.

The subsequent Input-only slice supplies typed, generation-owned Root Input
through the existing Android resolver/factory/tracker. AOSP/API26 observed real
key/touch/pointer/joystick delivery, complete reset and connection-loss release;
the exact final APK passed 3/3 qualifications without retries. The earlier
0/1 reset-fixture attempt remains recorded, not hidden. Positive cache evidence
is Input-only and explicitly LABORATORY_EXTERNAL_ROOT scoped; helper liveness
stays volatile. At that Input checkpoint Root Video/SystemAudio remained
NOT_IMPLEMENTED, so Root-only Host admission was incomplete. Synthetic DirectShell media plus Root Input
COMPOSITE and preference for a complete DirectShell SINGLE plan are JVM-tested,
not a manufactured API26 hardware composite. See
`phase6_5/DirectRootInputQualification.md` for 807-test gates, exact environment,
resources, cleanup and the bounded A41 endpoint-unavailable regression attempt.

The subsequent Video slice reuses the privileged mirror/projection and normal
app MediaCodec pipeline. UID0 receives the real encoder Surface through a
provider/UID/PID/generation/HMAC-bound Binder lease, not TCP. Exact AOSP/API26
frozen-artifact qualification passed 3/3 with decoded white-circle/checkerboard
content and two active-loss cleanup cases. Root Video/Input cache is scoped to
LABORATORY_EXTERNAL_ROOT; runtime stays volatile and startup stays passive.
At that Video checkpoint Root-only Host was incomplete because SystemAudio was NOT_IMPLEMENTED.
See `phase6_5/DirectRootVideoQualification.md` for exact artifacts, 841-test gates,
graphics-FD sampling limits and the optional Tablet admission accounting (1/2,
second attempt failed at runtime preparation, not a Root capture boundary).

The subsequent SystemAudio slice preserves the existing PCM Ring V1/drain/Opus
contract and adds only a provider-independent API26 ashmem ownership adapter and
finite authenticated Root audio control/descriptor handoff. The final APK passed
3/3 same-generation Input, decoded Video A/B and machine-verified silent/997Hz
Audio qualifications, then actual SINGLE/DIRECT_ROOT preparation, late
revalidation and freeze. Three isolated audio resource cycles also passed with
stable app FD samples. The earlier2/3 combined campaign remains recorded: its
third attempt stopped before Audio because the DEBUG reset observer incorrectly
assumed cross-source delivery order. Only that observer was corrected.

All three cells are QUALIFIED for the exact LABORATORY_EXTERNAL_ROOT key. At
the SystemAudio checkpoint this did not validate ordinary product Host admission:
the product Root bootstrap adapter is absent and AOSP Video qualification uses
the explicit DEBUG software encoder, not a bypass of the hardware-codec policy.
Production authorization/Magisk, physical Root validation, actual remediation,
same-provider recovery and live migration remain deferred. See
`phase6_5/DirectRootSystemAudioQualification.md` for exact artifact, attempts,
904-test gates, loss/cleanup evidence and complete-plan versus Host boundaries.

The subsequent normal Host slice closes application admission, not production
Root acquisition. A DEBUG/lab adapter registers a genuinely authenticated UID0
authority and finite capability-resource factory with the existing readiness/
admission owner. The same UI/controller action as normal Enable Host performs
late PING/generation checks, real acquisition and unchanged RFC-006I freeze
before the normal Host controller publishes Host Available and LAN discovery.
Freeze alone cannot publish. The lab's real Surface software codec is explicit
and does not assert the hardware-encode flag or alter release codec policy.

Eight consecutive normal Host cycles passed; the requested ten-cycle campaign
stopped on attempt9 at an inconclusive ADB inspection precondition (8/9, no
hidden retry). Cache evidence stayed byte-identical, categorized app descriptors
stabilized after emulator graphics warmup, restart admission passed1/1 and two
controlled helper-loss tests converged to teardown or truthful pre-freeze failure
without provider migration. See `phase6_5/DirectRootHostAdmissionValidation.md`.
Physical DirectRoot Device Qualification is the next evidence boundary; no
Magisk, remediation, same-provider recovery or live migration is implemented.
Because no already-rooted physical device is currently available, that
qualification is externally blocked. The non-Root priority queue and its
current closure evidence are tracked in
`phase6_5/CurrentNonRootDebt.md`. The Tablet's historical 1/2 DirectShell
preparation result was followed on 2026-09-28 by an exact-final-APK 10/10
normal Enable/Disable Host campaign, two earlier diagnostic 10/10 campaigns,
and a separate plan audit; its historical first failing stage
remains inconclusive because the old application trace was not captured. See
`phase6_5/TabletDirectShellRuntimeReliability.md`. Do not infer a product fix
or re-open closed video/cold-qualification/helper-lifecycle work from that old
high-level error.

RFC-006J Step 2 extends that passive path with a versioned, non-secret,
provider/capability evidence cache. Exact compatible records may create an
advisory prepared plan in the background, but no cache entry can advertise a
Host or skip late volatile revalidation and the existing final plan freeze.
Selective compatibility mismatch removes only the affected coordinate;
transient runtime availability does not poison or erase durable evidence.
Real A41/Tablet process restarts each loaded three persisted `QUALIFIED` records
and prepared a DirectShell-only advisory plan with zero feature servers and
zero Warpnect Shizuku UserServices. User Host requests then performed late
revalidation, current acquisition, and the existing plan freeze before Host
Available. Cleanup passed on both; no new audible/Session compatibility claim
is made by this cache-lifecycle validation. The earlier A41 missing TLS-connect
endpoint attempt remains separately recorded in RFC-006J.

## Phase 7 - Optimization

The [Phase 6.5 SystemAudio routing campaign](phase6_5/SystemAudioRoutingSemantics.md)
observed Client-only playback during normal DirectShell Sessions and restored
Host speaker playback after Disconnect on Tablet/API33 (3/3 human-confirmed),
A41/API31 (2/2 human-confirmed, plus a third technical Session) and S22/API36
(3/3 human-confirmed after it became available). The current supported
built-in-speaker semantic for this tested modern tier is `REMOTE_ONLY`, not a
universal Android guarantee or proof that the historical standalone A41
local-audio observation was wrong. Supported Host+Client duplication and
untested output routes are not qualified. The separate post-Disconnect
Session/discovery anomaly was reproduced on A41/API31 and fixed at the Session
terminal notification/Host availability boundary. The
[lifecycle report](phase6_5/PostDisconnectHostLifecycleReliability.md) records
10/10 A41 -> S9 reconnects under one Host enable and a separate fragmented
DirectShell launch PID fix. A later Tablet/API33 -> S9 gate passed 5/5
post-Disconnect Sessions under one Host enable; S22 confirmation remains
pending. The
future `HOST_AND_REMOTE` user choice and Host display `KEEP_ON`,
`PRIVACY_BLANK`, or true `SCREEN_OFF` policy are not implemented;
screen-policy feasibility should be evaluated before or during RFC-005K.

The [Phase 6.5 rendered-frame observability report](phase6_5/LegacyRenderedFrameObservability.md)
defines the measurement precondition: local decoder and Surface-handoff
durations are measurable, but `OnFrameRendered` is diagnostic only and
cross-device/photon latency is not established by app clocks or callback time.
Qualify external display instrumentation and clock provenance before freezing
physical-display latency baselines.

Optimize:

- CPU usage.
- Memory allocations.
- Zero-copy paths.
- Thread scheduling.
- Buffer management.
- Battery consumption.
- Thermal behavior.

## Phase 8 - Cross-Platform Expansion

Reuse the C++20 core to build:

- Windows client.
- Linux client.
- macOS client.

Only platform-specific capture, rendering, audio, and input layers should differ.

## Phase 9 - Production Readiness

Prepare the project for release:

- Configuration system.
- Logging.
- Error handling.
- Automated testing.
- CI/CD.
- Documentation.
