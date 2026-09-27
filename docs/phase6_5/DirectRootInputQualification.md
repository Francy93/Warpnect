# DirectRoot Input Qualification

## Scope And Repository Baseline

Branch: `codex/directshell-production-runtime-integration`. Starting foundation
checkpoint: `1766fa1`; the previously validated foundation was checkpointed
locally as `e762a1a` (`feat(privileged): add authenticated DirectRoot runtime
foundation`) before this Input implementation. Master remains `032b592`.
The new Input slice is uncommitted for review. No push, merge, rebase or master
fast-forward is part of this task.

This slice implements only DirectRoot Input. Production Root authorization,
Root Video, Root SystemAudio, actual recovery actions, same-provider recovery
and live migration remain NOT IMPLEMENTED. The laboratory harness obtains Root;
the normal app does not gain Root through the AOSP `su` executable.

## Existing Input Architecture And Reuse

```text
Client Input capture -> Input Payload V1 -> protected SCL -> Host decode/mapping
  -> AndroidInputInjectionController -> PrivilegedInputInjectionGateway
    -> ShizukuInputInjectionGateway -> privileged UserService          [existing]
    -> DirectShell gateway -> authenticated shell control              [existing]
    -> DirectRootInputInjectionGateway -> authenticated Root control   [new]
      -> existing DirectShellInputSessionCodec / typed WNIS messages
      -> existing PrivilegedInputInjectionUserService, invoked locally
      -> ReflectivePrivilegedInputManagerApi / existing resolver
      -> AndroidInjectedEventFactory / InputInjectionStateTracker
      -> Android InputManager -> Warpnect-owned DEBUG target
```

The local service invocation in the Root helper is not a Shizuku bootstrap or
Shizuku Binder call. Root and shell reuse the same framework resolver, factory,
canonical event model, tracker and release semantics. No third injector, new
network payload, arbitrary Parcelable deserialization or generic shell RPC is
introduced. The existing network Input Payload V1 and Session contracts are
unchanged. This task qualifies the local Root boundary, not remote Root Input.

## Implementation And Protocol

Main-side additions: `DirectRootInputInjectionGateway`, finite `RootInputControl`,
passive `DirectRootInputCapability`, and `RootInputRequestBounds`. The gateway
owns Input operations, not the Root runtime itself. It binds one runtime
generation, rejects closed/stale/lost owners and never retries an event on a
different provider. Connection failure moves the runtime out of READY.

The existing DEBUG bridge now takes backend/UID-kind parameters, retaining its
DirectShell defaults. `RootLocalControl` implements the typed Input interface;
`WarpnectRootHelper` dispatches only its finite allowlist. Added Root operations
reuse the existing `INPUT_SESSION_CAPABILITIES`, `PREPARE`, `START`, `KEY`, `TOUCH`,
`POINTER`, `JOYSTICK`, `RESET`, `STOP` and `SNAPSHOT` commands. No EXEC, file,
property, settings or arbitrary Android-object operation exists.

Root helper identity protocol is version 2 / `warpnect-root-input-v1`. The reused
outer WNDP framing remains version 1 and the reused internal WNIS Input codec
remains version 1. These DEBUG/local identifiers are not SCL or Input Payload V1
revisions. The exact capability response is:

```text
video=NOT_IMPLEMENTED;system_audio=NOT_IMPLEMENTED;input=IMPLEMENTED;remediation=NONE
```

Implementation is not qualification: UID0, a CAPABILITIES response or a framework
return value alone cannot produce positive Root Input evidence. The explicit
DEBUG qualification must observe the target's ordered events and reset result.

### File Inventory

New main files under `app/src/main/java/io/warpnect/platform/input/injection/`:

* `DirectRootInputInjectionGateway.kt`: finite Input transport interface and generation-owned gateway.
* `DirectRootInputCapability.kt`: passive observations and explicitly selected controller composition.
* `RootInputRequestBounds.kt`: additional RPC bounds, not a replacement event validator.

Main files changed:

* `input/injection/InputInjectionTypes.kt`: append internal DirectRoot backend enum without shifting existing ordinals.
* `platform/input/injection/InputInjectionControllerSelector.kt`: honor an explicitly frozen Root Input choice without fallback.
* `platform/input/injection/PrivilegedInputManagerApi.kt`: method-driven default-display-only legacy correction.
* `platform/privileged/runtime/DirectRootRuntime.kt`: typed Input control access/loss and Root helper identity revision.
* `platform/privileged/planning/AndroidHostPrivilegedCapabilityAdmission.kt`: Root Input cell, retain/release and frozen selection.
* `platform/privileged/readiness/PersistentCapabilityEvidence.kt`: Input-only provenance-scoped Root keys and volatile revalidation.
* `platform/privileged/readiness/AndroidHostReadinessWork.kt`: passive current Root Input state, no background injection.

DEBUG files under `app/src/debug/java/io/warpnect/`:

* New `debug/directshell/DirectRootInputQualification.kt`: owned-target qualification, held-input/disconnect proof and positive evidence.
* `debug/directshell/DirectRootLabController.kt`: typed client, explicit lab actions, stale gateway and cache inspection.
* `debug/directshell/DirectShellInputSessionBridge.kt`: provider parameters and Root-only RPC bounds; DirectShell defaults preserved.
* `debug/directshell/DirectShellRuntimeAutoRegistrationProvider.kt`: metadata registration only.
* `debug/directshell/RootControlLease.java`: finite Input allowlist and generation/version/order bounds.
* `debug/directshell/WarpnectRootHelper.kt`: authenticated Input dispatch and shared tracker cleanup.
* `debug/input/InputSessionTargetActivity.kt`: bounded observation ordering for the existing owned target.

Tests: new `DirectRootInputInjectionGatewayTest.kt`; changed
`RootControlLeaseTest.java`, `InputManagerBackendSelectorTest.kt`,
`PrivilegedCapabilityPlannerTest.kt`, `PersistentCapabilityEvidenceTest.kt`, and
`DirectRootRuntimeTest.kt`. The original shared tracker/factory/controller and
Shizuku gateway are not rewritten.

Tooling: `RootHelper.ps1` and new `Test-RootInput.ps1`. Documentation: this report,
`DirectRootBootstrapFoundation.md`, `AndroidRootLab.md`, `README.md`, RFC-006J,
`docs/FutureRoadmap.md` and `docs/testing/AndroidCompatibilityMatrix.md` (separate
emulator/lab section). No native source, Android SDK floor, network
payload, SCL, WNCP/WNSN or ABI changed. The unrelated line-ending-only
`AndroidInputCaptureController.kt` worktree entry is preserved, not part of this slice.

## API26 Framework Finding

FACT: the tested API26 framework resolves legacy
`android.hardware.input.InputManager.getInstance()` and
`injectInputEvent(InputEvent, int)`. `InputManagerGlobal` is absent.

FACT: AOSP Android 8.0 `InputEvent` does not expose `setDisplayId`. The earlier
resolver treated that method as mandatory and rejected the otherwise usable
legacy backend. Primary sources: [AOSP InputEvent](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/view/InputEvent.java)
and [AOSP InputManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/hardware/input/InputManager.java).

Correction: method absence permits only display 0. A nonzero display still fails
closed as `DisplayTargetingUnsupported`; security/reflection errors are not
silently ignored. This is framework-driven, not an API/model/OEM allowlist.
Existing modern display assignment remains unchanged. The lab target uses the
default display and wait-for-result diagnostics, not the optional target-UID
overload absent from this framework.

INFERENCE: this narrow correction can serve other frameworks with the same
actual API surface. It does not prove nondefault-display or physical API26 Root
compatibility. No external source code was copied.

## Authentication, Validation And Ownership

The foundation's 32-byte per-generation secret, HMAC-SHA-256 envelope, fresh
challenge, strictly increasing request IDs, installed-base.apk SHA-256 and
helper-side UID/GID checks are retained. Secrets stay in an owner-only temporary
app-private file until UID0 consumes/deletes it; no secret is in process
arguments, logcat or persistent compatibility evidence.

Input messages bind WNIS magic/version/generation and bounded payload length
(16..4096 bytes) before dispatch. The canonical codec/tracker validates event
shape, actions and ordering; supplemental Root RPC checks bound timestamps,
coordinates, key codes, logical slot IDs, device/display IDs and analog values.
Logical slot IDs range through 65535; this is distinct from the existing bound
of 32 simultaneously tracked slots. Nonfinite coordinates/axes are rejected.

Transport remains IPv4 loopback only, not a LAN service or emulator host port.
It does not provide Binder caller UID/Unix peer credentials. App identity is
possession of private generation material, not an OS-authenticated socket UID.
Helper trust includes the explicitly trusted laboratory launch chain and exact
artifact verification; it is not remote attestation against malicious Root.

On normal close, invalidation or authenticated owner-connection loss, the
existing tracker releases keys, cancels touches, releases pointer state and
neutralizes joystick state before helper exit. Root `stop` always resets all
state even if an app caller requests otherwise. A stale gateway cannot submit
to the next generation. SIGKILL cannot run release code: after hard helper loss
the unavailable snapshot truthfully retains `stateMayRemainInjected=true`.
No automatic recovery or speculative reinjection is added to mask that limit.

## Capability Matrix, Planning And Persistence

An observed local qualification records `QUALIFIED`, scoped to this laboratory,
not a universal `VALIDATED` capability. Root Video and SystemAudio remain
`NOT_IMPLEMENTED`. The unchanged Complete Host invariant requires all three.
Root-only Input cannot make Host available.

JVM cases cover an incomplete Root-only plan, synthetic DirectShell Video/audio
plus Root Input COMPOSITE, and preference for a complete DirectShell SINGLE plan
over an unnecessary composite. Ranking/provider-count rules are unchanged.

Positive Root Input cache records use exact API, fingerprint hash, app version,
helper/backend revisions and `LABORATORY_EXTERNAL_ROOT` provenance. Future
`PRODUCTION_ROOT_MANAGER` has a different key and cannot reuse lab evidence.
Root media and unidentified-provenance records are rejected. A transient helper
outage retains compatibility evidence; an authoritative contradiction invalidates
only the affected coordinate. Runtime liveness/secret/adbd state are not cached.

Passive readiness may load Root Input evidence without injection. It requires
current matching-provenance runtime availability before using that coordinate
for an advisory plan and still cannot bypass late admission/plan freeze.
Ordinary physical devices remain production-bootstrap NOT_IMPLEMENTED; no
imaginary Magisk prompt or partial Host is introduced.

## Laboratory Validation

The final exact DEBUG APK passed **3/3 consecutive acquisition/qualification
cycles, with no retry**, on 2026-09-27. A separate active-key/touch connection-loss
test passed **1/1**. These are emulator observations, not physical Root support.

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, 33,542,697 bytes,
versionCode 1 / versionName 0.0.1, packaged ABIs arm64-v8a, armeabi-v7a and
x86_64. SHA-256:

```text
713A775E7350ECADB77D9C368C90DAB4B0845044CF60E1C09DAC54F7E82F33E1
```

The source baseline is foundation checkpoint `e762a1a` plus this uncommitted
Input slice. This is not a claim that the APK was built solely from that commit.
Installed base.apk and helper-side SHA-256 matched the exact local artifact.

The qualifying target is `Warpnect_Root_API26`: AOSP/default x86_64, Android
8.0.0/API26, userdebug/test-keys, SELinux Enforcing, qemu=1. Fingerprint:
`Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.
API25 is not used and minSdk remains 26.

### Final Qualification And Resources

All four final helpers observed UID/GID **0/0**, SELinux **u:r:su:s0**, API26,
protocol identity version 2, implementation `warpnect-root-input-v1`, the exact
APK hash above and `LABORATORY_EXTERNAL_ROOT`. The normal app remained UID10065.
The selected backend was legacy InputManager; modern class resolution was
unavailable and legacy resolution succeeded. Display 0 and `WaitForResultDiagnostics`
were used. The optional target-UID overload was unavailable (`targetUid=-1`).

| Cycle | Generation | Helper PID | Loopback port | Helper FDs before/after | App FDs | Result |
| --- | --- | ---: | ---: | --- | ---: | --- |
| 1 | 6363686304193552917 | 2817 | 39924 | 30 / 30 | 65 | PASS; then generation invalidation |
| 2 | 8730733411105616456 | 3006 | 38017 | 30 / 30 | 65 | PASS; then authenticated shutdown |
| 3 | 7947787047783621251 | 3199 | 41836 | 30 / 30 | 65 | PASS; then explicit helper-loss test |
| Active disconnect | 1030147773740980311 | 3417 | 38226 | Not sampled | Not sampled | PASS; target observed release on EOF |

App PID2717 performed the three qualifications. In every cycle the owned target
observed KEY_A DOWN/UP, TOUCH DOWN/MOVE/UP at the target center (540,924), pointer
hover, joystick (0.25,-0.25), then held-key/touch reset and neutral joystick.
Key/touch/joystick order was exact; Android-generated pointer enter/move/exit
callbacks were observed separately, not miscounted as lost key/touch events.

Each final qualification snapshot reported:

* Key submissions 3/3, touch 4/4, pointer 1/1, joystick 1/1; all failure counts 0.
* One reset request, one complete reset, zero partial reset.
* One synthetic KEY_UP, TOUCH_CANCEL and neutral joystick; no pointer button was held.
* Tracked slots, pressed keys, touch streams, pointer buttons and joystick states all 0.
* Permission/service failures 0, `lastError=None`, `stateMayRemainInjected=false`.

These are privileged event counters, not separate human interactions. The actual
path was app typed Root requests -> authenticated helper -> reused backend ->
Android -> owned target. No `adb shell input` performed any credited injection.

### Authentication, Generations, Loss And Persistence

Cycle 1 sent a typed KEY request with a wrong HMAC before acquisition. The helper
rejected it as `ProtocolException`, `owner=false`; the negative caller neither
injected an event nor took ownership. The subsequent valid HELLO/IDENTITY,
CAPABILITIES and PING handshake succeeded. Invalid-generation, replay, version,
malformed/oversized request and non-Root identity rejection are covered by the
retained foundation tests plus the Input extension tests; they are not all new
physical negative runs in this slice.

Cycle 2 used the retained generation-1 gateway while generation 2 was active:
`direct_root_input_stale_gateway result=REJECTED code=22`. It did not send an event
to the new helper. Each generation used fresh secret material. Cycle 3 terminated
only verified helper PID3199 after qualification had already reset all state.
The app published `HELPER_LOST`, `runtime_ready=false`, `lastError=ServiceDied`;
the Root Input cache record remained. No automatic recovery ran.

The separate active disconnect held KEY_A and one touch in generation
1030147773740980311, then closed the authenticated owner connection. The target
received KEY_UP at 09:31:07.169 and TOUCH_CANCEL at 09:31:07.171 (emulator UTC).
The helper released its endpoint and exited at 09:31:07.175. This proves release
on connection loss, not release after an uncatchable hard kill.

After helper shutdown and app process restart, app PID3556 logged at
09:31:15.384: `root_records=1 runtime_ready=false
state=PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED input_snapshot=null`. Positive Input
compatibility survived; helper liveness did not. No background Input event or
helper launch occurred. Authoritative contradiction/selective invalidation and
LAB-versus-production cache isolation are JVM-tested, not induced hardware mutations.

Final checks found **0 helpers, 0 Root probe listeners, 0 private bootstrap
files**. The remaining loopback listener was laboratory adbd port 5037, not the
Root helper. `adb unroot` returned adbd to UID/GID2000 / u:r:shell:s0; SELinux
remained Enforcing. RootLab stopped the AVD. API25 configuration was untouched.

### Attempt Accounting

Exploratory failures are preserved separately, not counted as successful cycles:

| Attempt | First boundary | Observation |
| --- | --- | --- |
| 1 | HARNESS_DELAY / helper idle expiry before acquisition | No Input API test occurred |
| 2 | API_UNAVAILABLE / legacy method resolution | Mandatory setDisplayId rejected API26 |
| 3 | TARGET_NOT_OBSERVED_OR_ORDER | Actual key/touch/pointer/joystick receipt; extra Android hover callback broke the overly strict mouse fixture |

The first counted campaign attempt on APK
`32CA81B830274BF1DD21C271D108E9ABC1676BB7E18BE6DB55E76F3748742567`
failed 0/1 at `RESET_TARGET_NOT_OBSERVED`. Its actual target log contained all
releases: neutral joystick, KEY_UP, TOUCH_CANCEL. The fixture had required a
different cross-slot order. `InputInjectionStateTracker.reset(AllSlots)` walks
allocated array positions; the surviving joystick occupies an earlier position
than the subsequently held key/touch after inactive slots were reclaimed.
Only the DEBUG expected sequence was corrected; the production tracker and its
release policy were not changed. This failed attempt remains separate from the
subsequent fresh-artifact campaign and is not silently retried.

The final fixture keeps key/touch/joystick order exact and independently requires
pointer observation, allowing only Android-generated extra pointer callbacks.
No lost key/touch release or qualification retry is treated as PASS.

Thus the final exact-artifact qualification is **3/3**; across the earlier
counted reset-fixture APK and the final APK it is **3/4**. The three exploratory
failures above remain separate. The active-disconnect case is an additional
**1/1**, not a fourth ordinary qualification cycle. Evidence is preserved under
`artifacts/directroot-input/`: `final-campaign-fixed.log`, `final-cleanup.log`,
the earlier boundary/reset logs and failed-attempt APK. These are laboratory
artifacts, not checkpoint assets.

### Physical Regression

The same exact final APK was installed on A41/API31 without clearing data.
Passive readiness loaded three existing records and prepared advisory
`SINGLE / DIRECT_SHELL`; Root stayed `PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED`,
not selected. The one live Enable Host attempt failed **0/1** at
`WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE` after 90.147 seconds
(11:36:38.601 -> 11:38:08.748, device-local time). It never reached Input or
feature-server qualification. The source of the missing endpoint was not
reinvestigated or repaired in this task; no new A41 Host Available result is claimed.

Dismiss restored Ready with the truthful Host-unavailable reason, and Warpnect
privileged processes were 0. The installed Shizuku server was left alone; no
Shizuku feature fallback or Root command ran. The earlier foundation's successful
A41 admission remains historical evidence, not this attempt's result. No other
physical device was used for this Input regression and no tone was played.
Evidence: `artifacts/directroot-input/a41-regression.log`.

## Reproduction And Tooling

Build/install the exact DEBUG APK without changing minSdk. Root commands must
target the explicit, verified emulator only:

```powershell
.\tools\root-lab\RootLab.ps1 -Action Start -Api 26 -Port 5556
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$serial = 'emulator-5556' # Verify AVD/API/qemu; do not identify by port alone.
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
New-Item -ItemType Directory -Force artifacts\directroot-input | Out-Null
Start-Transcript -Path artifacts\directroot-input\qualification.log
try { .\tools\root-lab\Test-RootInput.ps1 -Serial $serial }
finally { Stop-Transcript }
.\tools\root-lab\RootHelper.ps1 -Action Stop -Serial $serial
& $adb -s $serial unroot
.\tools\root-lab\RootLab.ps1 -Action Stop -Api 26 -Port 5556
```

`RootHelper.ps1` adds only finite lab actions for qualification/status/stale
gateway/held-input/connection drop. Every action checks emulator serial, AVD,
API26, qemu and installed-package hash. `Test-RootInput.ps1` additionally requires
Enforcing SELinux, runs three fresh qualifications without retries, a separate
held-key/touch disconnect-release case and process-restart cache inspection.
ADB observes/launches the lab helper but `adb shell input` is not used for
qualification. There is no physical Root operation, broad process kill or wipe.

## Software Gates And Remaining Work

The frozen-source final build passed ktlintCheck, lintDebug, testDebugUnitTest
with `--rerun-tasks`, assembleDebug, assembleDebugAndroidTest and
packageDirectShellProbeDex: 100 executed tasks, 16m56s. Exact JVM total:
**807 tests in 137 suites, 0 failures, 0 errors, 0 skipped** (baseline 783).
The 24 additional cases cover generation-owned gateway use/release/loss and
Root request bounds (9), authenticated Input protocol cases (4), default-display
method guard (1), exact Root Input planner cases (3), and provenance-scoped
cache/invalidation cases (7). Existing resolver, factory and tracker tests are
retained. Mocked transport tests are not hardware qualification evidence.

`git diff --check` and the RootLab/RootHelper/Test-RootHelper/Test-RootInput
PowerShell parser and physical-target rejection checks passed. The final build
log is `app/build/reports/root-lab/root-input-reset-final-gates.log`.
Native source is unchanged, so no new native CTest run is claimed. Existing
generated native artifacts and historical laboratory screenshots/logs remain
outside commits. The ignored targeted formatters are temporary lab helpers,
not project assets.

Foundation checkpoint `e762a1a` is local only. This Input source/test/tooling/doc
slice remains uncommitted; master and upstream refs are unchanged. No push,
merge or rebase occurred. Pre-existing generated native output, historical
laboratory artifacts and the line-ending-only input-capture entry are preserved.
Machine-local AVD/userdata/snapshots are not repository changes.

Production Root authorization / Magisk: NOT IMPLEMENTED

DirectRoot Video: NOT IMPLEMENTED

DirectRoot SystemAudio: NOT IMPLEMENTED

Root-assisted Runtime Remediation: NOT IMPLEMENTED

Same-provider Recovery: NOT IMPLEMENTED

Live Provider Migration: NOT IMPLEMENTED

Physical Root and production root-manager behavior remain unvalidated. This
emulator cannot qualify OEM SELinux, vendor/kernel/codec/audio-HAL or Magisk
consent/lifecycle behavior. A subsequent capability task must qualify its actual
boundary independently rather than infer it from Input or UID0.

Decision: **DIRECTROOT_INPUT_CAPABILITY_VALIDATED_IN_AOSP_LAB**.

Next recommended task, not implemented here: **DirectRoot Video Capability**,
beginning with a separately scoped AOSP/API26 feasibility/qualification boundary.
Emulator video cannot by itself establish physical SurfaceFlinger or codec support.

## Subsequent Video Slice

This Input report remains historical evidence for its original exact APK.
Its source/tests/tooling/docs were reviewed and checkpointed locally as
`ae431d9` before the Video task. New Video work is uncommitted for review.
`DirectRootVideoQualification.md` records3/3 decoded-content API26 Video cycles
on helper identity version3 / `warpnect-root-video-input-v1`, with the same
typed Input backend and authentication primitives retained. One explicit
Input qualification on that frozen Video APK passed1/1 with owned-target
key/touch/pointer/joystick receipt, ordered reset and active state0.

Root Video and Input now have QUALIFIED evidence only for the exact AOSP lab.
Both cache records survived process restart; Root liveness did not. SystemAudio
is still NOT_IMPLEMENTED, so no Root-only Complete Host is admitted. The new
Video report supersedes only the earlier deferred Video status/next-step
recommendation; production authorization, remediation and physical Root
remain deferred. The next independently scoped capability is SystemAudio.
