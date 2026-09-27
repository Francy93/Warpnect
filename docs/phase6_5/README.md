# Phase 6.5 - Privileged Runtime Evolution

## Current Status

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
Host admission through the ordinary UI/network is not yet claimed.

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

## Report Roles

The individual probe reports remain historical evidence for their original
scope. This index is the current Phase 6.5 status and mini-roadmap:

* DirectShellBootstrapProbe.md - app_process shell identity and authenticated control.
* DirectShellSystemAudioProbe.md - standalone REMOTE_SUBMIX capture.
* DirectShellFdSharedMemoryProbe.md - framework-mediated FD-backed memory handoff.
* DirectShellPcmSharedRingIntegration.md - existing PCM ring and notify/ack reuse.
* DirectShellSystemAudioRemoteE2E.md - downstream audio transport spike.
* DirectShellInputInjectionProbe.md and DirectShellInputSessionIntegration.md - input feasibility and Host integration.
* DirectShellVideoCaptureIntegration.md - surface handoff and visible video.
* DirectShellProductionBootstrapFeasibility.md and DirectShellProductionRuntimeIntegration.md - app-owned Wireless Debugging acquisition and complete modern runtime.
* DirectShellA41NsdResolutionFix.md - trusted ADB DNS-SD endpoint resolution and controlled composite evidence.
* AndroidRootLab.md and DirectRootBootstrapFoundation.md - AOSP lab and authenticated laboratory Root authority.
* DirectRootInputQualification.md - Input-only Root provider, exact emulator target proof and scoped evidence.
* DirectRootVideoQualification.md - Root mirror/Surface provider, app-owned AVC and decoded-content AOSP lab proof.
* DirectRootSystemAudioQualification.md - API26 ashmem, Root REMOTE_SUBMIX, normal-app PCM signal proof and scoped complete-plan evidence.

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
  Ordinary product Root Host UI/network admission remains unvalidated.

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
