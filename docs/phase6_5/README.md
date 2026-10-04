# Phase 6.5 - Privileged Runtime Evolution

## Current Status

The [SystemAudio quality closure](SystemAudioQualityRootCause.md) is
HARDWARE_VALIDATED on exact A41/API31 ->S9/API29:FC7D151F,3/3 consecutive
70s tone Sessions, rejection/underrun/xrun0 including closing samples. Fixed
85/70ms SystemAudio reserve follows measured phase range and smaller failed
replays; its latency cost is explicit. Earlier counted FAILs remain visible.
Validated microphone source/tests/docs are checkpointed locally at596a534;
the independent all-device Input reset bridge fix is checkpointed atb812d82.
No recovery/migration, microphone compatibility expansion or release promotion
is included in the SystemAudio task. Final1037 JVM/22+22 native gates pass.
Next ONE task: MicrophoneAudio Android Compatibility Expansion (A41/S7).

Current [global microphone final closure](DirectShellGlobalMicrophoneFinalClosure.md)
is **VALIDATED ON THE EXACT PRIMARY PATH**: C782DD9D r2 S9/API29 ->Tablet/API33,
3/3 above121s under one Enable, ordinary MIC close/reopen, sampled steady
IPC/track/DEBUG rejected/underrun/xrun deltas0. Physical997Hz, natural MIC/VOICE/
CAMCORDER routing, UID NONE, restoration and helper-loss publication withdrawal.
1022 JVM/22+22 native gates pass. Fixed40ms IPC,80/70ms DEBUG remain separate;
no release/universal qualification/priority or portable-extension promotion.
Earlier current-status notes below are historical. The SystemAudio continuation
is linked above; this microphone campaign remains its own historical evidence.

Current [residual quality continuation](MicrophoneReceiverSchedulingQuality.md)
is PARTIAL. Peer link restored; bounded one-burst admission corrects240-frame
loss when only16 more frames need freeing. Final81F10D24 first cycle still
FAIL on residual starvation;2/3 NOT RUN.1017 JVM/22+22 native gates pass.
No quality/priority/release token or checkpoint; historical evidence retained.

Latest [receiver scheduling continuation](MicrophoneReceiverSchedulingQuality.md)
is **PARTIAL**: a microphone-only receive-worker correction reduces measured
local starvation and has one stable diagnostic. Final43FB71D6 first counted
cycle FAIL after peer/media loss and Tablet Wi-Fi/Debug wireless loss;2/3 NOT RUN.
Host publication withdraws correctly.1016 JVM/22+22 native gates pass. Functional
global token retained; no quality/priority/release token or checkpoint.

Latest [source-clock recovery continuation](MicrophoneSourceClockRecovery.md)
is **PARTIAL**. The additional pacer debt defect is corrected and tested without
buffer/codec/socket changes. First counted D28F6136 consumer-window cycle FAIL;
cycles2/3 NOT RUN. A separate80s steady continuous-reader diagnostic passes
numeric deltas but does not replace required close/reopen acceptance. A later
attempt loses the Tablet radio/Debug wireless prerequisite; peer ping/TCP return
without product changes, manual Debug wireless remains required. Functional
global token retained, no quality/priority/release/checkpoint promotion. This
note supersedes the historical status immediately below.

Latest [2026-10-03 clock-continuity campaign](MicrophoneConsumerClockContinuity.md)
is **PARTIAL**. Final66A8B330 completes three108s S9 ->Tablet functional MIC
Sessions under one Enable Host: no-UID ordinary input, live997Hz, physical
restoration, Ready/Waiting/rediscovery. IPC rejection0/0/0 is not quality closure:
cycle3 has recurring steady DEBUG and RemoteSubmix AudioTrack underruns. Healthy
output continuity/explicit-discontinuity re-priming reuse unchanged80/70ms.
Subsequent diagnostic fails with renewed platform radio loss, Android Debug
wireless disablement and truthful Host withdrawal. A41/S7 registration permission
barriers are refined; priority authorization-only, no valid preference applied.
All gates1007 JVM/161 suites and native22/22 each pass. No quality token, commit,
release promotion or recovery/migration. Finish this residual microphone boundary
before a separate SystemAudio quality task.

### Previous Continuation (Historical)

Latest [consumer-transition continuation](MicrophoneConsumerTransitionQuality.md)
is **PARTIAL**. Tablet returned; initial peer controls pass. The exact guard
candidate and bounded experiments remain visible. Provisional40ms IPC expiry
with stale-head admission improves two complete108s S9 ->Tablet ordinary MIC
Sessions; a third fails during platform path/radio loss, Bluetooth SIGKILL and
automatic Wireless Debugging disablement. No3/3 quality token or substitution.
80/70ms DEBUG playout remains frozen. Failed replacement withdraws Host health;
no recovery/migration or priority implementation. Full gates1004 JVM/161 suites,
native Debug/Release22/22; first native linker failure preserved. Final bounded
SystemAudio control is technical/lifecycle PASS, not quality closure. No commit.

### Previous Continuation (Historical)

Latest [global-input quality follow-up](DirectShellGlobalMicrophoneInjectionQuality.md)
is **PARTIAL**. Manually restored Wireless Debugging allowed the unchanged
candidate's first counted S9 -> Tablet cycle (111.017s), which failed quality;
acceptance stopped, with real997Hz signal and normal restoration preserved.
Opening-flush and smaller track-threshold experiments did not close pressure
and were reverted. A [network-only continuation](S9TabletPeerLinkRecovery.md)
restored bidirectional S9/Tablet IPv4 ping/TCP/UDP by one Tablet Wi-Fi rejoin,
with no media code change; exact network root cause remains INCONCLUSIVE.
Android switched Debug wireless off during rejoin, then Tablet USB disappeared.
The operator confirmed Tablet unavailable/replaced by S22 and authorized a pause.
No new microphone Session was counted or another Host substituted; resume the
current87EF556C candidate on Tablet return. The completed unchanged-source
three-task APK build passed; a new full-gate rerun was interrupted before tests.
A local consumer-window guard has focused tests, not physical qualification.
The separately corrected failed-replacement publication branch is also pending
physical validation. The original functional input and frozen
80/70ms DEBUG cadence tokens below remain accepted for their exact APKs.
Portability alternatives and forced priority remain deferred until quality passes.
The pre-window-guard follow-up gates passed996 JVM/160 suites/0 failures/errors/skips,
native Debug/Release22/22 each; the newest guard's final gates are recorded in
the focused report:998 JVM/160 suites/0 failures/errors/skips, all six final
Gradle tasks PASS. Earlier memory/style failures are preserved, not silent retries.
The latest A41 SystemAudio control passes technical lifecycle
only (partial late acoustic window; rejected30720/underrun373/xrun0 remains).
Normal Host disable left Tablet/A41 helpers0/secrets0. No new checkpoint or
quality token; all failed APKs and build attempts remain evidence.

Latest [portable global microphone integration](DirectShellGlobalMicrophoneInjection.md)
is **DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED** on the exact final
CF5C311C DEBUG S9/API29 -> Tablet/API33 path:3/3 fresh Sessions,60s independent
ordinary MIC consumers, real acoustic OFF/ON/OFF signal; VOICE_COMMUNICATION
and CAMCORDER also pass. UID rules NONE, no preferred-device call, built-in mic
coexists, normal Disconnect and helper loss restore physical capture. A41 is
permission-blocked; S7 stops at the current context adapter. Release/product
quality remains PARTIAL: MIC1 late DEBUG underruns and IPC startup/no-consumer
pressure are preserved, not hidden by MIC2/3 zero DEBUG counters. Forced priority
is NOT TESTED (read-only API/getter investigation done). No checkpoint.
Final gates990 JVM/0/0/0, native Debug/Release22/22 each. The accepted microphone
DEBUG playout80/70ms baseline below is unchanged and not generalized to input.

The latest 2026-10-01 [autonomous microphone cadence closure](MicrophoneAudioCadenceRootCause.md)
is **FIXED_AND_VALIDATED on S9/API29 -> Tablet/API33 DEBUG playback**: three
consecutive Sessions69.902/69.748/69.898s under one Enable Host, with rejection,
underrun and xrun0. An80ms PCM ring/70ms reserve absorbs measured ordered
arrival variation; warm output startup prevents cold-start admission loss.
Nominal priming adds65ms; the pacer, codec and socket buffers are unchanged.
Kernel gaps40.9-45.5ms still occur, so this is not a Wi-Fi-cause or zero-latency
claim. Acoustic OFF/ON/OFF capture -> remote decode and SPEAKER route are proven;
human Tablet output remains unconfirmed. Disconnect/Waiting/rediscovery3/3 and
final helpers/secrets0. SystemAudio technical control passes but retains its
separate known playback pressure. Final gates **972 JVM/0/0/0**, native
Debug/Release **22/22 each**. No new checkpoint; prior failures remain below.
Global capture-preset injection was not started in that cadence task; the later
integration above supersedes only its next-task/status statement.

The earlier 2026-10-01 [portable microphone ordered gate](DirectShellMicrophoneInjectionQualification.md)
identifies the first source grouping at blocking AudioRecord reads: four5ms
chunks per approximately20ms batch. A no-extra-queue pacing candidate produces
592/592 local S9 delivery intervals in3-7ms (maximum5.37ms), with clean stop.
This is not E2E quality closure. The initial protected Session suspension and
subsequent Tablet Wi-Fi/ADB loss remain preserved. After the operator restored
Wireless Debugging, three consecutive final-APK S9 -> Tablet Sessions above60s
completed under one Enable Host: **3/3 lifecycle PASS, 0/3 quality PASS**.
Steady rejection35280/35760/33840 and underrun callbacks204/205/195 remain;
xrun0 and unchanged20ms ring do not make this a quality PASS. Encoder input
cadence improves, but kernel arrival still has41.8-46.2ms gaps. Global
capture-preset injection and optional priority were NOT
TESTED/IMPLEMENTED because that gate has not passed. The AOSP recorder-mix
hypothesis is distinct from earlier UID-scoped/explicit-port selection results.
Final software: **964 JVM /0/0/0**, native Debug/Release **22/22 each**. Partial
source/tests/docs stay uncommitted; owner liveness at715b215 is not redesigned.

The 2026-10-01 [cadence and first-class input follow-up](MicrophoneAudioCadenceAndVirtualInput.md)
supersedes the earlier microphone investigation status below. Tablet is available;
bounded local kernel/socket/authentication/decoder diagnostics narrow additional
burstiness to protected submit -> Host kernel receipt, but do not prove its cause.
Quality is PARTIAL; the 20 ms ring is unchanged. S9 -> A41 DEBUG audibility is now
operator-confirmed. Android enumerates a Tablet dynamic submix, but an unmatched
ordinary consumer cannot open it; matching-UID injection works. This is scoped
AudioPolicy feasibility, not first-class product input. A41 shell registration
remains permission-blocked. No new production virtual microphone or checkpoint.
Final software gates: **959 JVM tests**, no failures/errors/skips, native Debug
and Release **22/22 each**. Exact APK, counted final Sessions and cleanup are in
the focused report; technical/lifecycle PASS is separate from quality acceptance.

The current non-Root debt source of truth is [CurrentNonRootDebt.md](CurrentNonRootDebt.md).
The [post-Disconnect lifecycle report](PostDisconnectHostLifecycleReliability.md)
reproduces the stale Host `Running`/lost rediscovery path on A41, fixes terminal
Session-to-Host notification and a fragmented DirectShell launch PID response,
and records 10/10 A41 -> S9 reconnects without a Host toggle. A later
Tablet/API33 -> S9 regression passed 5/5 under one Host enable with the same
APK; S22 post-Disconnect confirmation remains untested.
The separate [MicrophoneAudio E2E qualification](MicrophoneAudioE2EQualification.md)
used the existing Client-microphone-to-Host-speaker pipeline after that Tablet
gate. S9 -> Tablet passed three normal protected technical Sessions plus one
separate full-gate-APK spot-check, a local
physical microphone probe, permission-denial control, and restart/abnormal-loss
controls. Human audibility at the Tablet was not confirmed. A long-idle
DirectShell Host liveness defect was also exposed: the UI remained Available
after feature helpers expired; see the report and current debt index.
The 2026-09-30 [owner-liveness and playback-quality follow-up](DirectShellIdleLivenessAndAudioPlaybackQuality.md)
replaces post-owner idle expiry with authenticated generation-bound ownership.
A41 crossed almost 18 hours and Tablet approximately 18 minutes with the same
helpers before successful first post-idle Sessions; unexpected loss withdraws
publication. Full background policy remains deferred. MicrophoneAudio quality
is still PARTIAL: S9 listening controls reported a silent Tablet, and the A41
comparison showed additional playback resets. A physical S9 speaker fixture
was captured by the A41 mic and reached Tablet decoded PCM, but that is not
proof of Tablet speaker emission. The actual sink routed to EARPIECE.
That slice's software gates passed with 950 JVM tests; the report distinguishes
the physical APKs from the later final diagnostic rebuild. Owner-bound liveness
is now locally checkpointed at `715b215`, with a bounded A41 normal-Session
regression before the checkpoint; the long idle evidence was not unnecessarily
repeated.
The [virtual-input / DEBUG playback report](MicrophoneAudioVirtualInputArchitecture.md)
clarifies the product destination: Client microphone -> **Android Host virtual
microphone input**, not Host speaker. The latter is an explicitly labelled DEBUG
diagnostic sink; release cannot offer it as virtual input. A41/API31 shell policy
authorization fails, while S22/API36 successfully injected a fixed signal into
an ordinary AudioRecord consumer through a UID-scoped policy. This does not prove
an additional/selectable input for arbitrary Host apps or the protected remote-mic
product path. DEBUG media usage now routes to the A41 speaker, but Tablet is
disconnected and human output is NOT CONFIRMED. Final S9 -> A41 passed three
normal technical/lifecycle Sessions, with quality FAIL in every steady window.
Ring-full rejection and zero-fill remain; no buffer inflation or speculative
pacing fix. Final gates pass with **954 JVM tests** and native Debug/Release
CTest **22/22 each**. New investigation/routing work remains uncommitted.
Normal DirectShell SystemAudio routing is scoped in
[SystemAudioRoutingSemantics.md](SystemAudioRoutingSemantics.md): Tablet, A41
and S22 normal Host Sessions routed the test tone to S9 only and restored
Host speaker playback after Disconnect. This validates `REMOTE_ONLY` for the
tested modern DirectShell built-in-speaker tier, not untested output routes.
Legacy rendered-frame callback timing and its Phase 7 measurement boundary are
classified in [LegacyRenderedFrameObservability.md](LegacyRenderedFrameObservability.md):
S7/S9 local decoder fixtures were prompt, an S9 remote Session showed callbacks
delayed by up to 70.7 s after Surface handoff, and A41 returned an unqualified
codec `nanoTime`. None is treated as a physical-display timestamp.
The physical Tablet DirectShell preparation follow-up is
[TabletDirectShellRuntimeReliability.md](TabletDirectShellRuntimeReliability.md):
the exact final APK passed 10/10 normal Host admissions on 2026-09-28; two
earlier diagnostic campaigns also passed 10/10 each, while the
earlier 1/2 failure's precise cause remains inconclusive because its app log
was not captured. This result does not add a media Session claim.

The validated modern DirectShell Host baseline is:

    DIRECTSHELL_PRODUCTION_RUNTIME_INTEGRATION_VALIDATED

RFC-006J Step 1 is also complete for its bounded scope:

    BACKGROUND_HOST_READINESS_AND_RUNTIME_STATE_VALIDATED

It adds a volatile runtime-state model, passive asynchronous Host-readiness
observation, single-flight Host preparation, generation-safe invalidation, and
structured Host-preparation UI. It does not make DirectRoot, remediation,
same-provider recovery, or live provider migration available.

RFC-006J Step 2 is implemented, JVM-tested, and process-restart hardware validated
on A41/API31 and Tablet/API33:

    PERSISTENT_CAPABILITY_EVIDENCE_AND_PREPARED_PLAN_VALIDATED

The app-private cache retains only versioned positive provider/capability
evidence. It uses exact provider/capability compatibility keys, rebuilds an
advisory RFC-006I matrix off the UI thread, and never turns cached evidence into
Host publication. `Enable Host` still performs late runtime revalidation and
the existing RFC-006I plan freeze. Transient Wireless Debugging, endpoint, and
Shizuku availability changes affect current readiness without deleting durable
compatibility evidence.

The subsequent DEBUG/laboratory Root authority foundation is now AOSP/API26
emulator-validated:

    DIRECTROOT_LAB_BOOTSTRAP_AND_RECOVERY_AUTHORITY_FOUNDATION_VALIDATED

That foundation owns authenticated UID0 control and generations, not Host capabilities.
At that checkpoint all Root capabilities remained NOT_IMPLEMENTED.
See `DirectRootBootstrapFoundation.md` for the exact
783-test gates, 8/8 qualification cases, resource cycles and physical boundary.

The subsequent Input-only slice is now AOSP/API26 emulator-qualified:

    DIRECTROOT_INPUT_CAPABILITY_VALIDATED_IN_AOSP_LAB

`DirectRootInputQualification.md` records 3/3 fresh-generation qualifications,
real owned-target key/touch/pointer/joystick receipt, reset, 1/1 active connection-loss
release, provenance-scoped cache reuse and 807 passing JVM tests. The earlier
counted fixture failure remains recorded separately (0/1). Root Input evidence
is `QUALIFIED` for this exact laboratory, not general physical support.
At that Input checkpoint, production Root authorization, Root Video/SystemAudio
and remediation remained NOT_IMPLEMENTED; Input alone was not a Complete Host.

The subsequent Video slice is now AOSP/API26 emulator-qualified:

    DIRECTROOT_VIDEO_CAPABILITY_VALIDATED_IN_AOSP_LAB

`DirectRootVideoQualification.md` records 3/3 frozen-artifact qualifications,
authenticated UID0 Binder Surface handoff, reused mirror/projection and app-owned
encoder, offline decoded fixture A/B proof, two active-loss cases and 841 passing
JVM tests. Input checkpoint `ae431d9` and Video checkpoint `84f79dd` are local.
Root Video/Input evidence remains QUALIFIED for the exact LABORATORY_EXTERNAL_ROOT
compatibility key. Root SystemAudio and production authorization/remediation are
NOT_IMPLEMENTED at that Video checkpoint; Root-only Host was incomplete.

The subsequent SystemAudio slice is described separately in
`DirectRootSystemAudioQualification.md`. It adds a bounded API26 ashmem adapter,
the existing PCM Ring V1/notify/ACK/drain contract, authenticated UID0
REMOTE_SUBMIX and a machine-verified silence/997Hz fixture. Its explicit lab
probe requalifies all three capabilities on the same helper generation before
using the real prepared-plan builder, late revalidation and planner freeze.
Product Root authorization/Host UI and physical Root are separate boundaries;
no cached evidence or lab-only freeze publishes an ordinary Host capability.

    DIRECTROOT_SYSTEMAUDIO_CAPABILITY_VALIDATED_IN_AOSP_LAB

The final APK passed3/3 fresh-generation, three-capability qualifications and
actual SINGLE/DIRECT_ROOT plan freezes, plus3/3 isolated audio resource cycles.
All904 JVM tests and requested software gates pass. The first combined campaign
remains recorded as2/3, stopped before Audio by a DEBUG cross-source reset-order
assumption; production Input behavior was not changed to hide it. Complete Root
Host admission through the ordinary UI/network was not claimed at that checkpoint.

The subsequent normal application admission slice now closes that boundary:

    SINGLE_DIRECT_ROOT_HOST_ADMISSION_VALIDATED_IN_AOSP_LAB

`DirectRootHostAdmissionValidation.md` records eight consecutive normal
Enable/Disable Host passes on the exact API26 APK (8/9 counted attempts; the
ninth stopped at an inconclusive ADB observation precondition), a separate
restart admission 1/1, and controlled pre-/post-freeze helper-loss tests 2/2.
The real controller reaches Host Available and registers normal LAN discovery
only after authenticated same-generation acquisition and freeze. Background
readiness stays passive; cached evidence is unchanged by admission/outage.
All 931 JVM tests and requested gates pass. No remote Root Session, physical
peer visibility, production Root authorization or physical Root is claimed.

This status is supported by the Phase 6.5 reports and the current
DirectShell integration checkpoint. It is a validated engineering baseline,
not a declaration that DirectShell is the release default or that Shizuku can
be deleted today.

The automatic modern path demonstrated on the reference hardware is:

    normal Warpnect UI
      -> Enable Host
      -> PrivilegedRuntimeResolver
      -> DirectShellRuntime
      -> user-authorized Wireless Debugging and app-owned local ADB
      -> app_process as shell uid 2000
      -> DirectShell Video, SystemAudio, and Input
      -> Host Available
      -> real Session

For counted DirectShell runs, desktop ADB did not launch app_process and
Shizuku was not the Video, SystemAudio, or Input provider.

## Modern Hardware Evidence

| Host | API | DirectShell Video to S9 | DirectShell Input | DirectShell SystemAudio to S9 |
| --- | ---: | --- | --- | --- |
| Samsung A41 | 31 | Visible E2E PASS | PASS | AUDIBLE_CONFIRMED |
| Tablet X700_EEA | 33 | 3/3 visible E2E PASS | PASS | AUDIBLE_CONFIRMED |
| Samsung S22 | 36 | 3/3 visible E2E PASS | PASS | 3/3 technical PASS; AUDIBLE_CONFIRMED |

The S9/API29 is the established Client for this matrix. The S7/API26 remains a
separate legacy compatibility target and has not been made a DirectShell Host
by this work.

The Tablet result is important because its documented Shizuku 13.6
UserService bootstrap incompatibility remained present while DirectShell Video,
SystemAudio, and Input ran through app_process.

The A41 pairing evidence is also retained. The observed TLS-connect endpoint
was real, but the original client accepted foreign `_adb-tls-connect` records.
On API31 one foreign resolve starved the sole active NSD resolve slot and left
late work behind the bounded caller. The repair binds TLS-connect discovery to
the paired ADB service identity, accepts only its standard DNS-SD conflict
alias, serializes generation-owned resolution, and prevents late callbacks from
starting queued work. See `DirectShellA41NsdResolutionFix.md`. This is a
portable client lifecycle correction, not an A41/Samsung branch or an audio
failure.

## Root Laboratory Environment

`AndroidRootLab.md` records the separately qualified AOSP API25/API26 emulator
laboratory, including adb-root UID0, natural Enforcing SELinux, cold-boot and
snapshot behavior. API25 still rejects the normal minSdk26 APK as expected;
API26 installs and renders the unchanged app. This is environment evidence,
not physical-device support or an implemented DirectRoot capability backend.
The follow-up `DirectRootBootstrapFoundation.md` adds a DEBUG/laboratory UID0
authority foundation, locally checkpointed as `e762a1a`. The subsequent
`DirectRootInputQualification.md` adds the first capability, Input, through that
authenticated helper. `DirectRootVideoQualification.md` now adds mirror control
and authenticated Surface handoff to the existing app encoder. The subsequent
`DirectRootSystemAudioQualification.md` adds API26 ashmem, REMOTE_SUBMIX and
normal-app PCM/ring/Opus proof. All three capabilities are now QUALIFIED for
the exact AOSP lab. Production Root authorization and Root-assisted Remediation
remain **NOT IMPLEMENTED**. ADB-root acquisition
still requires the explicit laboratory harness, not app-accessible AOSP `su`.
`DirectRootHostAdmissionValidation.md` now validates the ordinary Enable Host
controller and publication using that explicitly authenticated lab authority.
It does not replace the missing production Root bootstrap adapter.

## Report Roles

The individual probe reports remain historical evidence for their original
scope. This index is the current Phase 6.5 status and mini-roadmap:

* DirectShellBootstrapProbe.md - app_process shell identity and authenticated control.
* DirectShellSystemAudioProbe.md - standalone REMOTE_SUBMIX capture.
* DirectShellFdSharedMemoryProbe.md - framework-mediated FD-backed memory handoff.
* DirectShellPcmSharedRingIntegration.md - existing PCM ring and notify/ack reuse.
* DirectShellSystemAudioRemoteE2E.md - downstream audio transport spike.
* SystemAudioRoutingSemantics.md - normal DirectShell Session Host/Client speaker routing and bounded physical observations.
* PostDisconnectHostLifecycleReliability.md - terminal Host ownership, rediscovery and same-Host reconnect reliability.
* DirectShellInputInjectionProbe.md and DirectShellInputSessionIntegration.md - input feasibility and Host integration.
* DirectShellVideoCaptureIntegration.md - surface handoff and visible video.
* DirectShellProductionBootstrapFeasibility.md and DirectShellProductionRuntimeIntegration.md - app-owned Wireless Debugging acquisition and complete modern runtime.
* DirectShellA41NsdResolutionFix.md - trusted ADB DNS-SD endpoint resolution and controlled composite evidence.
* AndroidRootLab.md and DirectRootBootstrapFoundation.md - AOSP lab and authenticated laboratory Root authority.
* DirectRootInputQualification.md - Input-only Root provider, exact emulator target proof and scoped evidence.
* DirectRootVideoQualification.md - Root mirror/Surface provider, app-owned AVC and decoded-content AOSP lab proof.
* DirectRootSystemAudioQualification.md - API26 ashmem, Root REMOTE_SUBMIX, normal-app PCM signal proof and scoped complete-plan evidence.
* DirectRootHostAdmissionValidation.md - normal Enable/Disable Host, Root-only acquisition/freeze/publication, restart, controlled loss and categorized FD evidence in AOSP/API26.
* TabletDirectShellRuntimeReliability.md - physical Tablet repeated DirectShell Host admission, stage diagnostics and historical-failure classification.
* CurrentNonRootDebt.md - superseded/closed versus genuinely open Phase 6.5 non-Root concerns.

## Roadmap

Completed:

* DirectShell bootstrap, authenticated control, and deterministic cleanup.
* DirectShell SystemAudio, Input, and Video qualification on the modern matrix.
* App-owned Wireless Debugging bootstrap on the qualified devices.
* Modern audible SystemAudio matrix to S9.
* Static `PrivilegedCapabilityMatrix` and deterministic complete-plan planner.
* Host preflight that freezes a complete Video, SystemAudio, and Input plan
  before Host availability or discovery.
* Optional Shizuku per-capability qualification and controlled A41-to-S9
  composite-plan validation: DirectShell Video/SystemAudio plus Shizuku Input.
* RFC-006J Step 1 runtime-state observation and background Host readiness:
  passive app startup, single-flight Host admission, structured readiness UI,
  generation-safe cleanup, and late RFC-006I plan freeze.
* RFC-006J Step 2 persistent capability evidence and advisory prepared-plan
  reconstruction: bounded app-private storage, schema/key validation,
  selective invalidation, and cached DirectShell/Shizuku planning without
  background feature-server startup. Real A41/Tablet process restarts loaded
  three persisted qualifications, prepared an advisory DirectShell-only plan,
  then performed current runtime acquisition and late plan freeze on demand.
* DirectRoot DEBUG/laboratory bootstrap and separate recovery-authority
  availability: authenticated UID0 control, generation/lifecycle qualification
  on AOSP/API26, no remediation action.
* DirectRoot Input implementation and AOSP/API26 qualification: typed existing
  Input codec/backend, generation ownership, target-observed ordering/reset,
  lab-provenance cache and unchanged complete-plan policy.
* DirectRoot Video implementation and AOSP/API26 qualification: existing mirror/
  geometry/encoder contract, provider-isolated Binder lease, real A/B decoded
  content, generation teardown and volatile liveness.
* DirectRoot SystemAudio implementation and AOSP/API26 qualification: original
  PCM Ring V1, real descriptor/notify/ACK/drain path, silence/997Hz proof,
  three-cell same-generation qualification and actual Root-only late plan freeze.
* Complete normal Root Host admission in AOSP/API26: real application Enable
  Host, passive prepared evidence, authenticated current ownership, ordinary
  Host Available/LAN publication, disable, restart and loss teardown. The
  bounded campaign observed no monotonic Warpnect-owned FD growth; physical
  multicast reachability and remote Root Sessions remain untested.

Next architecture milestones:

1. Physical Root Device Qualification, before generalizing the AOSP Root capability evidence.
2. Write the dedicated In-Session Privileged Capability Provider Migration RFC.
3. Implement and hardware-validate provider migration only after that RFC is
   accepted.
4. Perform final privileged-runtime acceptance across modern, compatibility,
   and future DirectRoot paths.

The static planner is JVM-validated and current-build Host-admission validated
on A41/API31 and Tablet/API33. Both selected the all-DirectShell `SINGLE` plan
before discovery; a silent Tablet-to-S9 Session retained it through media
startup and both Host runtimes cleaned up on disable. A controlled A41-to-S9
composite Session additionally validated DirectShell Video/SystemAudio with a
real Shizuku Input provider and human S9-to-A41 touch injection. No new audible
audio result is claimed. An S7/API26 control truthfully reports the DirectShell Android 11+ platform
requirement without a shell launch or Shizuku fallback; its Host-only run leaves
Client mode unblocked rather than asserting a separately executed Client probe.

The RFC-006J Step 1 hardware lifecycle proof is deliberately narrower than a
media Session: passive launch on A41/API31 and Tablet/API33 left the UI usable,
did not launch a DirectShell server or Shizuku UserService, and settled with
volatile `STALE` evidence when no complete current plan was cached. A user Host
request then performed the existing late admission, froze the all-DirectShell
`SINGLE` plan, reached Host Available, and released the runtime on disable.
Tablet double Host actions produced one readiness generation and one runtime
acquisition. No deliberate audio tone was used.

RFC-006J Step 2 passed its process-restart/cache-to-freeze proof 1/1 on A41/API31
and 1/1 on Tablet/API33. Background startup loaded three `QUALIFIED` records,
prepared `SINGLE / DIRECT_SHELL` as advisory only, and left DirectShell feature
servers and Warpnect Shizuku UserServices at zero. The UI did not advertise Host
until `Enable Host` acquired current runtimes, reused three records with late
revalidation, and froze the existing RFC-006I plan. Semantic disable released
all feature servers. A41 Wireless Debugging OFF invalidated current readiness
without erasing the three records. The earlier A41 endpoint-unavailable attempt
remains a recorded failure, not a hidden retry. No audio tone was required.
The exact APK, attempt accounting, timing, 754-test gates, and laboratory
evidence are recorded in RFC-006J's Step 2 verification section.

Deferred by RFC-006J Steps 1 and 2: Root-assisted Remediation, DirectRoot,
Same-provider Recovery, and Live Provider Migration are all NOT IMPLEMENTED.
The subsequent foundation slice superseded only the laboratory authority part
of that historical statement. The Input, Video and SystemAudio slices now
supersede those three capability implementations/qualifications in the exact
AOSP lab; production authorization and remediation remain deferred.

No partial normal Host mode is planned. Video, SystemAudio, and Input remain
jointly required for Host availability.
