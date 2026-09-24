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

The A41 pairing evidence is also retained: pairing succeeded, but the
Wireless Debugging connection service initially did not publish its
_adb-tls-connect endpoint. A controlled ordinary Wireless Debugging
off/on transition allowed normal adbd publication; Warpnect's bounded
connection-service discovery grace then supported repeated self-bootstrap
acquisition. This is recorded as a bootstrap-environment/adbd publication
condition, not an audio failure and not an A41-specific code branch.

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

## Roadmap

Completed:

* DirectShell bootstrap, authenticated control, and deterministic cleanup.
* DirectShell SystemAudio, Input, and Video qualification on the modern matrix.
* App-owned Wireless Debugging bootstrap on the qualified devices.
* Modern audible SystemAudio matrix to S9.
* Static `PrivilegedCapabilityMatrix` and deterministic complete-plan planner.
* Host preflight that freezes a complete Video, SystemAudio, and Input plan
  before Host availability or discovery.

Next architecture milestones:

1. Integrate Shizuku as an explicit optional per-capability provider and
   validate a controlled composite plan.
2. Implement and qualify DirectRoot separately.
3. Write the dedicated In-Session Privileged Capability Provider Migration RFC.
4. Implement and hardware-validate provider migration only after that RFC is
   accepted.
5. Perform final privileged-runtime acceptance across modern, compatibility,
   and future DirectRoot paths.

The static planner is JVM-validated and current-build Host-admission validated
on A41/API31 and Tablet/API33. Both selected the all-DirectShell `SINGLE` plan
before discovery; a silent Tablet-to-S9 Session retained it through media
startup and both Host runtimes cleaned up on disable. The current implementation
does not claim composite hardware validation or a new audible-audio result. An
S7/API26 control truthfully reports the DirectShell Android 11+ platform
requirement without a shell launch or Shizuku fallback; its Host-only run leaves
Client mode unblocked rather than asserting a separately executed Client probe.

No partial normal Host mode is planned. Video, SystemAudio, and Input remain
jointly required for Host availability.
