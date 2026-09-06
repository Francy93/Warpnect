# Android H1 Device Harness

`run.ps1` is development-only tooling for H1 real-device validation. It dynamically selects two
physical ADB devices in state `device`, builds one debug APK, installs that exact artifact on both,
and saves bounded, sanitized scenario evidence under the ignored `artifacts/` directory.

## Device Selection

By default the harness ignores emulators and selects the first two physical device serials in
deterministic lexical order. A developer can override either role without hard-coding a serial in
the script:

```powershell
$env:WARPNECT_DEVICE_A = "<host serial>"
$env:WARPNECT_DEVICE_B = "<client serial>"
.\tools\android-h1\run.ps1 -Scenario PairAccept
```

It exits with `TWO_REAL_DEVICES_REQUIRED` when two physical devices are unavailable. It never
uninstalls or clears Warpnect by default; `-CleanState` is an explicit destructive test mode only.
`PairAcceptCleanState` is the separately named clean-state pairing setup; recovery scenarios must
not use it.

## Automation and Evidence

The harness uses UI hierarchy text first. When a control has no direct accessibility action, it
taps the bounds obtained from that same hierarchy; it contains no hard-coded device coordinates.
It recognizes either spaced or contiguous six-digit SAS presentation only in memory, refuses to
automate confirmation unless both peers expose one unambiguous matching value, and stores only
`sas_equal`, never the code itself. Logs and XML are filtered/redacted; screenshots redact the SAS
bounds before saving. Artifacts include the exact APK hash and selected device metadata.

For example:

```powershell
.\tools\android-h1\run.ps1 -Scenario PairAccept
.\tools\android-h1\run.ps1 -Scenario ClientReject
.\tools\android-h1\run.ps1 -Scenario RoleReversal
```

The device must already be awake and unlocked. The harness reports
`DEVICE_LOCKED_OR_NOT_FOREGROUND` rather than attempting to bypass device security.

## Human Reverse-Input Validation

`InputSessionHold` checks media-start acceptance on both peers plus real Client decode, without
requiring a timely legacy rendered-frame callback. `session_start_failed` overrides readiness even
if a video frame was decoded before the failure. A scenario PASS means readiness only, never
reverse-input E2E validation.

```powershell
.\tools\android-h1\run.ps1 -Scenario InputSessionHold -SkipBuild -SkipInstall `
    -HoldMediaAfterFirstDecodeSeconds 120 -LeaveSessionRunning
.\tools\android-h1\test-media-outcome.ps1
```

`-LeaveSessionRunning` explicitly skips final semantic teardown, including after failure, so the
operator can inspect the state or prepare an owned target. The operator must subsequently stop or
disconnect the intended pair. The normal default still tears down the scenario. The decode hold is
bounded to 120 seconds; neither option generates a reverse-input event. The human touch and the
complete protected delivery into the Host-owned target require separate evidence.

## Lifecycle Repetition

`-ReuseRunningApps` keeps already foreground Warpnect instances running between scenario
invocations. It is intended for lifecycle repetition only: it must not be combined with
`-CleanState`, and it preserves the normal scenario teardown. This lets a test observe resource
release across repeated Sessions without the harness force-stopping the application between every
cycle.
