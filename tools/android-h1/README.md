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

## Experimental Capture Spike

`capture-spike.ps1` exists only on the `experiment/android-capture-backends` branch. It enumerates
every attached physical device, binds a separate debug-only Shizuku UserService, and records
bounded metadata, Input-reflection, legacy SurfaceControl, and DisplayManager-mirroring probes.
`All` intentionally avoids instantiating a codec; use the separate encoder probes only after the
metadata result has made that bounded experiment appropriate. It creates no Warpnect Session,
sends no media, persists no frames, and writes only safe booleans/enums plus device metadata to
the ignored artifact directory.

The legacy lifecycle probe records the reflection boundary, temporary-display cleanup, and the
first configuration operation that fails. The DisplayManager resolution probe reports only
Surface-targeted `createVirtualDisplay` method shapes; it does not invoke alternate signatures.

```powershell
.\tools\android-h1\capture-spike.ps1 -Probe All
```

Use `WARPNECT_CAPTURE_DEVICES=<serial1>,<serial2>` or repeated `-Serial` values to constrain an
experiment. These are optional runtime overrides; the script never embeds device-specific serials.
