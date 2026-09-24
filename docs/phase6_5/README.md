# Phase 6.5 - Privileged Runtime Evolution

## Current Status

The validated modern DirectShell Host baseline is:

    DIRECTSHELL_PRODUCTION_RUNTIME_INTEGRATION_VALIDATED

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

Next architecture milestones:

1. RFC-006J - Privileged Runtime Orchestration and Host Readiness [accepted
   architecture; implementation planned].
2. Implement the Runtime State Model and Background Host Readiness Coordinator
   without DirectRoot remediation or live provider migration.
3. Implement and qualify DirectRoot separately.
4. Write the dedicated In-Session Privileged Capability Provider Migration RFC.
5. Implement and hardware-validate provider migration only after that RFC is
   accepted.
6. Perform final privileged-runtime acceptance across modern, compatibility,
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

No partial normal Host mode is planned. Video, SystemAudio, and Input remain
jointly required for Host availability.
