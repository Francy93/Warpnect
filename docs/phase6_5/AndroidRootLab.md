# Android Root Laboratory

## Status And Scope

    ANDROID_ROOT_LAB_ENVIRONMENT_QUALIFIED

Qualified on 2026-09-26 using two official AOSP/default x86_64 emulator images.
This is environment qualification, not DirectRoot or physical-device validation.

* DirectRoot: **NOT IMPLEMENTED**.
* Root-assisted Remediation: **NOT IMPLEMENTED**.
* Root providers, recovery helpers and production Root authorization: not implemented.
* Warpnect's production platform floor remains **minSdk 26**.
* No physical device was rooted, flashed, wiped, installed on or otherwise modified.
* No SELinux mode, system/vendor partition, boot image or framework was modified.

## Repository Baseline

Branch: `codex/directshell-production-runtime-integration`.
Starting HEAD: `569155a`; unchanged master: `032b592`.
Upstream branch was `9d0b4dc`, six commits behind the starting local branch.

RFC-006J Step 2 was reviewed and checkpointed locally first:

    5967608 feat(privileged): persist capability evidence and prepare Host plans

The checkpoint contains 14 source/test/documentation files, 2,000 insertions and
87 deletions. It preserves the already accepted
`PERSISTENT_CAPABILITY_EVIDENCE_AND_PREPARED_PLAN_VALIDATED` milestone.

Excluded and preserved: 150 modified generated files in `native/build-rfc006e`,
pre-existing laboratory screenshots/XML/logs, and a semantic-empty line-ending
change in `AndroidInputCaptureController.kt`. The initially empty Git index was
blocked by an empty lock dated 2026-09-24; no Git process was active, so only that
stale lock was removed. No reset, clean, rebase, merge or push was performed.

`artifacts/rfc006j/step2-format.gradle` is a temporary ktlint init-script allowlist,
not a required project asset. It remains outside the checkpoint.

This task changes only this report, the Phase 6.5 index and `tools/root-lab/RootLab.ps1`.
These environment-only changes remain uncommitted for review.

## Toolchain

| Component | Observed version/configuration |
| --- | --- |
| Android Studio | 2026.1.3, build `AI-261.26222.65.2613.15948027` |
| SDK | Windows default `%LOCALAPPDATA%\Android\Sdk`, also referenced by local.properties |
| ANDROID_HOME / ANDROID_SDK_ROOT | Both unset in the task process |
| platform-tools / adb | `37.0.1-15733141`; ADB protocol tool version `1.0.41` |
| emulator | `37.1.11.0`, build `15917651` |
| sdkmanager / avdmanager | SDK Command-line Tools `22.0`, `cmdline-tools\latest\bin` |
| Gradle | `8.12` |
| AGP / Kotlin | `8.7.3` / `2.0.21` |
| Project compileSdk / targetSdk / minSdk | `35` / `35` / **`26`** |
| Gradle/build JDK | Eclipse Adoptium `17.0.14+7`, Java toolchain 17 |
| SDK-manager JDK | Eclipse Temurin `21.0.2+13` |
| Studio bundled JBR | `25.0.2`, build `25.0.2+-15348964-b329.117` |
| Windows / acceleration | Windows 11 build `22631`; WHPX usable, HypervisorPresent true |

`emulator -accel-check` returned code 0 and
`WHPX(10.0.22631) is installed and usable`. Both launches also reported the
Windows Hypervisor Platform accelerator operational. No Windows virtualization
setting was changed. The host has approximately 8 GiB RAM; run these AVDs one at
a time. `vmcompute` and `vmms` were running.

Before this task there were **no AVDs and no system images**. Existing SDK packages
included platform-tools, emulator, command-line tools, platforms 35/37.0,
build-tools 34.0.0/36.0.0, CMake 3.22.1/4.1.2 and NDK 27.0.12077973/30.0.15729638.
They were not upgraded. SDK 25/26 platform packages are not needed to run these
images or build this compileSdk35 APK, so they were not installed.

### Packages Added

Exactly two packages, discovered in the official SDK repository before selection:

* `system-images;android-25;default;x86_64`, revision **1**.
* `system-images;android-26;default;x86_64`, revision **1**.

Both are AOSP/default, not Google APIs or Google Play images. No Studio update,
new build-tools, root manager or third-party Android image was installed.

## AVD Configuration

| Setting | Both laboratory AVDs |
| --- | --- |
| Names | `Warpnect_Root_API25`, `Warpnect_Root_API26` |
| Profile | Nexus 5 generic SDK profile, no Samsung customization |
| ABI | x86_64; guest abilist `x86_64,x86` |
| RAM / cores | 1,536 MiB / 2 |
| Display | 1080 x 1920 portrait, density 480 |
| Renderer | SwiftShader; WHPX CPU acceleration |
| Data partition | 6 GiB virtual capacity, compact standalone QCOW2 backing |
| SD card | Profile-generated 512 MB setting |
| Play Store / account | Disabled / none |
| Launch | Headless, no audio, no automatic snapshot load/save |
| Snapshots | Explicit `CLEAN_BOOT` and `ROOT_AVAILABLE` |

The dedicated AVD data area is configured through the per-AVD `.ini` absolute
path. Use `WARPNECT_ROOT_LAB_HOME` for reproduction on a suitable disk; no
machine-specific absolute user path is required in source or scripts.
On the qualified machine this area is on drive D:, separate from the SDK.

### Storage Findings

The first two API25 startup attempts failed before any guest boot:

1. Generated 10 GiB userdata requested 12,288 MB free space.
2. Reducing config to 2 GiB was insufficient: this emulator raised the effective
   minimum to 6 GiB and required 7,372.80 MB free space.

These are host-storage setup failures, not Root failures. The emulator source
applies the 6 GiB minimum to API24+ and a 1.2 safety multiplier for initial
userdata creation. See the [AOSP emulator startup implementation](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android-qemu2-glue/main.cpp).

Only the newly created lab AVDs were moved to the dedicated disk. Each initialized
userdata chain was compacted **offline** with the SDK's `qemu-img convert -O qcow2`,
then compared to the original with `qemu-img compare`: **Images are identical**.
Only after successful comparison were the redundant generated raw/overlay files
replaced by the standalone image. No guest sectors or application data were
changed. Initial compact sizes were 134,217,728 bytes (API25) and 195,952,640 bytes
(API26), both retaining 6,442,450,944 virtual bytes.

`-qcow2-for-userdata` is supplied for subsequent starts, but on this installed
emulator/image combination initial creation still produced a large raw base.
Do not assume that flag alone avoids initial storage requirements. Budget at
least 7.2 GiB free in the AVD data volume for initialization, plus SDK and snapshot
space. API25 snapshots were temporarily staged on another local volume during
API26 initialization and then restored, without deleting them.

## Observed Guest Identity

| Property | API25 | API26 |
| --- | --- | --- |
| AVD -> serial | `Warpnect_Root_API25` -> `emulator-5554` | `Warpnect_Root_API26` -> `emulator-5556` |
| Android release / API | `7.1.1` / `25` | `8.0.0` / `26` |
| Fingerprint | `Android/sdk_phone_x86_64/generic_x86_64:7.1.1/NYC/4931657:userdebug/test-keys` | `Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys` |
| Build type / tags | `userdebug` / `test-keys` | `userdebug` / `test-keys` |
| ro.kernel.qemu / ro.debuggable / ro.secure | `1` / `1` / `1` | `1` / `1` / `1` |
| Kernel | `3.10.0+` | `3.18.91+` |
| Natural SELinux state | **Enforcing** | **Enforcing** |
| Initial shell UID/GID / context | `2000/2000`, `u:r:shell:s0` | `2000/2000`, `u:r:shell:s0` |
| After adb root | `0/0`, `u:r:su:s0`, whoami=root | `0/0`, `u:r:su:s0`, whoami=root |
| service.adb.root after root | `1` | `1` |

Root shells retain the listed shell supplementary groups; their authoritative
effective identity is UID/GID0. Harmless `id`, `whoami`, `/proc/version`, getprop
and root-directory listing commands succeeded. No filesystem remediation was
performed. API25 `ps -AZ` is not supported (`bad pid '-AZ'`); `ps -Z` succeeded.
API26 `ps -AZ` succeeded. Its observed adbd PID changed from 1370/shell to
3170/root when root was requested, confirming a daemon restart.

## Root Lifecycle

| Test | API25 | API26 |
| --- | --- | --- |
| adb root / wait-for-device / id | PASS, restarting adbd as root, UID0 | PASS, restarting adbd as root, UID0 |
| adb unroot / wait / id | PASS, UID2000; service.adb.root=0 | PASS, UID2000; service.adb.root=0 |
| root -> normal guest reboot | PASS, returns UID2000; root property empty | PASS, returns UID2000; root property empty |
| root -> emulator shutdown -> cold boot | PASS, returns UID2000; root property empty | PASS, returns UID2000; root property empty |
| save/load CLEAN_BOOT | PASS, returns UID2000 | PASS, returns UID2000 |
| save/load ROOT_AVAILABLE | PASS, returns UID0; root property=1 | PASS, returns UID0; root property=1 |
| SELinux after lifecycle operations | Enforcing | Enforcing |

Each target completed one normal reboot and one full emulator-process cold restart
after running root. Changing boot IDs distinguish those boots from snapshot
restoration. Snapshots restore the saved daemon state; they do **not** prove Root
persists through a real reboot. A fresh cold boot needs a new laboratory `adb root`.
Both AVDs were unrooted and stopped at task end; no qemu/emulator process remains.

### API26 First-Boot Platform Warning

The first initialized API26 boot had one automatic `system_server` restart,
before Warpnect was installed. The first failing boundary was
`AlarmManagerService.onStart -> setTimeZoneImpl -> sendBroadcastAsUser`:

    IllegalStateException: Cannot broadcast before boot completed

The later NetworkPolicyManager NPE was consequential, not the first failure.
The platform recovered without an intervention. The subsequent normal reboot,
cold boot and final snapshot-restored observation had an empty crash buffer.

FACT: the restart and successful later boots were observed.
INFERENCE: this is a first-initialization platform/timezone issue, not a Warpnect
failure. UNKNOWN: recurrence after a future wipe or under another emulator
release. No framework, timezone, SELinux or persistent Android setting was
patched to suppress it. Keep the preserved first-boot log when investigating a
future wipe; do not describe the initial boot as crash-free.

## Warpnect Installation

The unchanged `:app:assembleDebug` build passed. APK:

* Source checkpoint: `5967608`.
* SHA-256: `A96B720589ACB25DEF4D4A3734B5EAA9968E2581A1CE3119D887EDC5AF4A4049`.
* Size: **33,784,804 bytes**; unchanged timestamp UTC `2026-09-26T15:44:59.0817562Z`.
* ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
* VersionCode **1**, VersionName **0.0.1**, package `io.warpnect`.

### API25: Expected Floor Rejection

Normal `adb install -r`, with adbd unrooted, returned exit 1:

    INSTALL_FAILED_OLDER_SDK
    Requires newer sdk version #26 (current version is #25)

`pm path io.warpnect` was empty. No bypass, manifest modification, special APK or
minSdk change was used. API25 is **not** Warpnect-supported by this result.

### API26: Real APK Coexistence

Normal installation succeeded and the installed base.apk SHA-256 matched exactly.
Observed package path:

    /data/app/io.warpnect-OBrDbYRgTSLUy8Gh3ABLww==/base.apk

The randomized install suffix is evidence, not a reusable hardcoded path. Query
`pm path io.warpnect` on each generation. primaryCpuAbi was `x86_64`.

`am start -W -n io.warpnect/.MainActivity` returned `Status: ok` on the initial
boot, subsequent reboot and cold boot. Screenshots were inspected, not inferred
from decoder counters. The UI rendered **Ready**, **Client: Not checked** and
**Host unavailable: privileged runtime is unsupported on this platform**.
The final PID 2645 remained alive through snapshot tests and teardown observation.
No Warpnect crash or ANR was observed. Host/session/media compatibility was not
tested or claimed.

## su And Future DirectRoot Boundary

Both images contain `/system/xbin/su`, owned by root:shell with mode **04750**
(`-rwsr-x---`), not a user-consent root manager. Binary sizes: API25 10,344 bytes,
API26 11,056 bytes. This does not grant ordinary apps execute access.

On API26, `run-as io.warpnect id` demonstrated the normal debug-app UID10065 in
`u:r:untrusted_app:s0:c512,c768`. The harmless `run-as io.warpnect /system/xbin/su 0 id`
attempt returned **Permission denied**, exit 1. This is not a full product Root
bootstrap test and no app Root acquisition was implemented.

PROPOSAL for future laboratory work: an explicitly harness-owned UID0 helper,
started via verified emulator-only `adb root`, with purpose-specific authenticated
IPC. This would exercise Root runtime code while keeping app privilege acquisition
marked laboratory-only. Alternatively a shell/harness invocation of the image's
restricted su can be investigated separately. Neither is implemented here.

Production Root bootstrap must separately use an actual root manager such as
Magisk, with physical-device authorization. It must not depend on emulator ADB,
grant a generic command terminal, or treat adb-root availability as app authority.

## Reproduction

Use the installed SDK; keep JAVA_HOME suitable for the SDK tools (JDK21 used in
this qualification) and the build's Java17 toolchain. Example PowerShell setup:

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$manager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdManager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
& $manager --list_installed
& $manager 'system-images;android-25;default;x86_64' 'system-images;android-26;default;x86_64'
```

AVDs are already created on this laboratory machine. For a new machine, first
select a disk with sufficient free space and set `WARPNECT_ROOT_LAB_HOME`:

```powershell
foreach ($api in 25,26) {
    $name = "Warpnect_Root_API$api"
    $path = Join-Path $env:WARPNECT_ROOT_LAB_HOME "$name.avd"
    'no' | & $avdManager create avd -n $name -k "system-images;android-$api;default;x86_64" -d 'Nexus 5' -p $path
}
```

Do not use `--force` to overwrite an existing AVD. Set each AVD's config.ini to
`hw.ramSize=1536`, `hw.cpu.ncore=2`, `disk.dataPartition.size=6442450944`,
`PlayStore.enabled=no` and `userdata.useQcow2=yes`. The old packages emit missing
image-local devices.xml warnings; creation still succeeded with the SDK Nexus 5
profile. Do not modify the downloaded image to hide these warnings.

From the repository root, start **one target at a time**:

```powershell
.\tools\root-lab\RootLab.ps1 -Action Start -Api 25 -SdkPath $sdk
.\tools\root-lab\RootLab.ps1 -Action Inspect -Api 25 -SdkPath $sdk
# Stop API25 before starting API26 on this modest-RAM machine.
.\tools\root-lab\RootLab.ps1 -Action Stop -Api 25 -SdkPath $sdk
.\tools\root-lab\RootLab.ps1 -Action Start -Api 26 -SdkPath $sdk
.\tools\root-lab\RootLab.ps1 -Action Inspect -Api 26 -SdkPath $sdk
```

Before any root/unroot/reboot or snapshot command, run Inspect to verify the
whitelisted AVD name, ro.kernel.qemu=1, matching API and `emulator-` serial. Never
substitute a physical serial or run an untargeted `adb shell`/root/reboot command.

```powershell
# API26 example; API25 uses emulator-5554.
$serial = 'emulator-5556'
& $adb -s $serial root
& $adb -s $serial wait-for-device
& $adb -s $serial shell id
& $adb -s $serial shell whoami
& $adb -s $serial shell getprop service.adb.root
& $adb -s $serial shell getenforce
& $adb -s $serial shell id -Z
& $adb -s $serial unroot
& $adb -s $serial wait-for-device
& $adb -s $serial shell id
```

The script bounds individual ADB calls to 15 seconds, boot observation to 300
seconds (configurable up to 600) and shutdown observation to 30 seconds. It
rejects absent AVDs/SDK tools, invalid ports and mismatched target identities.
Start-Process is hidden, logs are generated under `app/build/reports/root-lab`,
and no automatic root, wipe, broad process kill or physical targeting occurs.

### Cold Boot, Snapshots And Wipe

Start always supplies `-no-snapshot-load -no-snapshot-save`: it performs a true
cold boot while retaining explicit snapshot support. Stop then Start is the
normal reset; no userdata is cleared.

```powershell
# Verify with Inspect first; use the matching emulator serial throughout.
& $adb -s $serial emu avd snapshot list
& $adb -s $serial emu avd snapshot load CLEAN_BOOT
& $adb -s $serial wait-for-device
& $adb -s $serial shell id
# ROOT_AVAILABLE is the separately tested saved UID0 state.
& $adb -s $serial emu avd snapshot load ROOT_AVAILABLE
& $adb -s $serial wait-for-device
& $adb -s $serial shell id
.\tools\root-lab\RootLab.ps1 -Action Stop -Api 26 -SdkPath $sdk
```

To create fresh snapshots, use `emu avd snapshot save CLEAN_BOOT` in a verified
non-root clean state, then `adb root`, verify UID0 and save `ROOT_AVAILABLE`.
Snapshot restore rolls back guest data/state; never use it during a running
Warpnect Session. API26's qualified snapshots include the installed, idle APK;
they are not an uninstalled factory image. Cold boot remains the primary method.

A wipe was **not performed** during qualification. If a future explicit lab reset
requires it, first verify/stop that dedicated AVD, preserve wanted lab evidence,
and use Device Manager's **Wipe Data** on that AVD, or explicitly:

```powershell
# DESTRUCTIVE TO THIS DEDICATED AVD ONLY. Requires a deliberate reset decision.
# Confirm it is stopped and named Warpnect_Root_API26, not another device.
Start-Process -FilePath $emulator -WindowStyle Hidden -ArgumentList @(
    '-avd','Warpnect_Root_API26','-port','5556','-wipe-data',
    '-no-window','-no-audio','-no-snapshot-load','-no-snapshot-save',
    '-memory','1536','-cores','2','-gpu','swiftshader')
```

Allow the initial storage requirement again, reinstall the unchanged APK, and
recheck the first-boot warning. A wipe is not a shortcut to production support.

## Qualification Matrix

| Target | API | Image | Root-capable adbd / UID0 | SELinux | Warpnect install | Role |
| --- | --- | --- | --- | --- | --- | --- |
| Warpnect_Root_API25 | 25 | AOSP/default x86_64 r1 | PASS / PASS | Enforcing | Expected INSTALL_FAILED_OLDER_SDK | Legacy Root/platform lab only |
| Warpnect_Root_API26 | 26 | AOSP/default x86_64 r1 | PASS / PASS | Enforcing | PASS, MainActivity visible/no app crash | Future DirectRoot development lab |

Classification: **AOSP EMULATOR LAB QUALIFIED**, not physical DEVICE VALIDATED.
This result does not add API25 to Warpnect's compatibility matrix.

## Validation And Evidence

* `git diff --check`: PASS.
* `:app:assembleDebug`: PASS, unchanged exact APK (44 tasks, 6 executed).
* RootLab script parsing, invalid-port and missing-SDK rejection: PASS.
* Actual Start/Inspect/Stop and snapshot operations: hardware/emulator validated.
* Existing Step 2 baseline retained: 754 JVM tests across 134 suites, zero
  failures/errors/skips; ktlint, lint, debug APK, androidTest APK and probe packaging PASS.
* No application/native source was changed for the lab. No new full JVM/native
  campaign was necessary; no new CTest result is claimed.

Generated evidence stays outside commits in `artifacts/root-lab` and
`app/build/reports/root-lab`: SDK package lists, build log, identity/root/reboot
observations, snapshot proofs, API26 first-boot log, installed-hash evidence,
initial and final UI screenshots. Historical lab evidence is preserved.

## Emulator / Physical Boundary

The lab cannot establish Magisk authorization, OEM SELinux policy, Samsung
kernel/vendor behavior, physical SurfaceFlinger behavior, hardware codecs, audio
HAL behavior, thermals or actual root-manager lifecycle. A rooted physical device
will still be required for future DirectRoot product qualification. None was
selected, rooted or modified in this task.

Next recommended task, **not implemented here**:

    DirectRoot Bootstrap + Root Recovery Authority Foundation

### Foundation Follow-up

The environment above is historical qualification evidence, checkpointed as
`1766fa1`. `DirectRootBootstrapFoundation.md` records the subsequent DEBUG/lab
runtime slice and its separate validation. `tools/root-lab/RootHelper.ps1` allows
only the verified API26 emulator to launch the fixed Warpnect UID0 helper from
the matching installed APK, consuming a private per-generation secret file.
It does not change these AVD configurations, minSdk, snapshots or SELinux.
The app still cannot invoke the AOSP su, and production root-manager acquisition,
Root capabilities and actual remediation remain NOT IMPLEMENTED.

The subsequent foundation is **IMPLEMENTED / TESTED / EMULATOR-VALIDATED** on
API26: 8/8 formal cases and 2/2 separate resource cycles passed; actual helper
UID/GID0 and Enforcing `u:r:su:s0` were observed. Final helper processes,
listeners and private handoff files were zero; adbd returned to UID2000 and
RootLab stopped API26. API25 was not modified or rebooted in that slice.
This does not change the environment-versus-production acquisition distinction.

### Input Follow-up

`DirectRootInputQualification.md` records the first capability on the unchanged
API26 lab: authenticated helper UID/GID0, Enforcing u:r:su:s0, legacy InputManager,
real owned-target key/touch/pointer/joystick receipt and reset. The final exact
APK passed 3/3 fresh-generation qualifications and a separate 1/1 active
connection-loss release test. Root Input is QUALIFIED only for that exact lab
compatibility key; Root Video/SystemAudio remain NOT_IMPLEMENTED.

## Subsequent Root SystemAudio Investigation

The environment is unchanged by `DirectRootSystemAudioQualification.md`:
minSdk26, API26 AOSP/default x86_64, natural Enforcing policy and explicit
LABORATORY_EXTERNAL_ROOT bootstrap. Only `emulator-5556` / Warpnect_Root_API26
receives Root operations; API25 and connected physical devices are untouched.
The new `tools/root-lab/Test-RootAudio.ps1` rejects physical serials and verifies
AVD/qemu/API/policy before invoking the hash-verifying RootHelper harness.
It creates no emulator-specific production logic or root-manager authorization.

After building the exact debug APK, start the AVD and install without clearing
app data, then run:

```powershell
./tools/root-lab/RootLab.ps1 -Action Start -Api 26 -Port 5556
& "$env:ANDROID_SDK_ROOT/platform-tools/adb.exe" -s emulator-5556 install -r ./app/build/outputs/apk/debug/app-debug.apk
./tools/root-lab/Test-RootAudio.ps1 -Serial emulator-5556 -Cycles 3
# Optional same-generation Video/Input/complete-plan proof:
./tools/root-lab/Test-RootAudio.ps1 -Serial emulator-5556 -Cycles 3 -FfmpegPath <local-decoder.exe>
# After all helpers are stopped and the emulator identity has been checked:
& "$env:ANDROID_SDK_ROOT/platform-tools/adb.exe" -s emulator-5556 unroot
./tools/root-lab/RootLab.ps1 -Action Stop -Api 26 -Port 5556
```

The optional decoder observes saved AVC; it is not a screen capture source.
Reports, logs, decoded frames and temporary formatters remain generated evidence,
not tracked tooling dependencies. Ordinary-app su access remains denied.
The final SystemAudio report records3/3 same-generation Input/Video/Audio
qualifications and actual Root-only planner freezes, plus3/3 isolated Audio
resource cycles,904 JVM tests and exact failure accounting. A real complete
capability set in this AOSP laboratory is not physical Root, Magisk consent or
ordinary product Host admission through UI/discovery/network.
Final helpers/listeners/private bootstrap files were 0, adbd was unrooted to
UID2000, and RootLab stopped API26. Neither AVD configuration nor minSdk26 was
changed; API25 was not booted by the Input slice. No production root-manager,
physical Root or Complete Host support is inferred from these observations.

## References

* [Android emulator command-line lifecycle](https://developer.android.com/studio/run/emulator-commandline).
* [Android hardware acceleration](https://developer.android.com/studio/run/emulator-acceleration).
* [avdmanager](https://developer.android.com/tools/avdmanager).

The qualification decision rests on the observed emulator results, not these
references alone.
