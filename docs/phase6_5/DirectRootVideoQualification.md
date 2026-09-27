# DirectRoot Video Qualification

## Baseline And Scope

Branch: `codex/directshell-production-runtime-integration`. Task starting HEAD:
`e762a1a`. The previously validated Input slice was reviewed and checkpointed
locally as `ae431d9` (`feat(privileged): add DirectRoot Input capability`),
32 source/test/tooling/documentation files. Master remains `032b592`; upstream
remains `9d0b4dc`. No push, merge, master fast-forward, reset or rebase.
The new Video slice is uncommitted for review.

Only DIRECT_ROOT x VIDEO is added. Root Input is retained. Root SystemAudio,
production Root-manager/Magisk authorization, actual recovery actions,
same-provider recovery and live migration remain NOT IMPLEMENTED. minSdk stays
26. API25 and physical Root are not qualification targets.

## Existing Architecture And Reuse

```text
Session -> VideoSenderComponent -> AndroidVideoCaptureController
  -> CaptureGatewaySelector -> selected PrivilegedCaptureGateway
    -> Shizuku UserService                         [existing]
    -> DirectShell authenticated control/Surface   [existing]
    -> DirectRoot authenticated control/Surface    [new]
      -> existing QualifiedPrivilegedDisplayCaptureApi
        -> DisplayManagerMirror or SurfaceControlDisplay
        -> existing projection planner / encoder input Surface
  -> app-owned AndroidMediaCodecVideoEncoder
  -> existing AVC / Video Payload / SCL / Session pipeline
```

The provider-specific boundary changes execution/transport, not capture or
encoding algorithms. The existing mirror implementations, logical-display
projection, rotation correction, display selection and cleanup are reused
without native or media-pipeline source changes. Shizuku supplies bootstrap,
privileged UID and Binder delivery in its existing path; those are not required
by the shared mirror backend itself.

Root controls only the mirror. MediaCodec, the original input Surface, codec
configuration and encoded AUs belong to the normal app. Root never receives
encoded video. TCP carries fixed commands/diagnostics, not Surface or frames.
The isolated qualification uses the existing app encoder with an explicit
DEBUG AOSP software-codec discovery boundary; it is not a second encoder.

## Implementation Inventory

New main files under `app/src/main/java/io/warpnect/platform/capture/`:

* `DirectRootVideoCaptureGateway.kt`: runtime/generation-bound mirror gateway,
  idempotent stop, ownership-preserving close and runtime-loss observation.
* `RootVideoControl.kt`: finite typed adapter and bounded 40-byte request codec.
* `PrivilegedSurfaceLease.kt`: pure provider/UID/PID/generation/HMAC lease.
* `DirectRootVideoCapability.kt`: passive exact-generation qualification and
  explicitly frozen provider selection; no background capture.

Changed main files: `CaptureGatewaySelector.kt`, `DirectRootRuntime.kt`,
`AndroidHostPrivilegedCapabilityAdmission.kt`, `AndroidHostReadinessWork.kt`, and
`PersistentCapabilityEvidence.kt`. Root Video selection is explicit and fail
closed. The ordinary DirectShell/Shizuku composition and planner ranking are
unchanged. No protocol, native ABI, Session, encoder or decoder behavior change.

New DEBUG files under `app/src/debug/java/io/warpnect/debug/directshell/`:
`DirectRootSurfaceHandoff.kt`, `RootVideoProviderClient.kt`, `RootVideoBridge.kt`,
and `DirectRootVideoQualification.kt`. Changed DEBUG files:
`DirectRootLabController.kt`, `WarpnectRootHelper.kt`, `RootControlLease.java`,
`DirectShellRuntimeAutoRegistrationProvider.kt`,
`DirectShellVideoCaptureProbeContentProvider.kt`, and the existing visual fixture
`DirectShellVideoCaptureProbeActivity.kt` (opt-in moving marker only).

Tests: new `PrivilegedSurfaceLeaseTest.kt` and
`DirectRootVideoCaptureGatewayTest.kt`; extended `RootControlLeaseTest.java`,
`PrivilegedCapabilityPlannerTest.kt`, `PersistentCapabilityEvidenceTest.kt`.
Tooling: extended `RootHelper.ps1`; new `Test-RootVideo.ps1`.
Generated formatters, FFmpeg observer, AVC/PNG/logs and native build output are
laboratory artifacts, not project dependencies or checkpoint assets.

## Control And Surface Security

Root identity protocol is version **3**, implementation
`warpnect-root-video-input-v1`. Existing outer WNDP framing and canonical Input
codec remain unchanged. Finite Video commands are VIDEO_QUALIFY, VIDEO_START,
VIDEO_UPDATE, VIDEO_STOP and GET_VIDEO_DIAGNOSTICS. They bind the runtime and
Surface generations, display 0, bounded dimensions 1..4096 and version/magic.
No generic command, arbitrary reflection, Binder transaction or object RPC.

The foundation's 32-byte secret, HMAC-SHA-256 challenge, ordered requests,
single authenticated owner, APK SHA-256 verification, idle/absolute expiry and
EOF cleanup are retained. Secret bytes are consumed from the app-private
temporary bootstrap file by UID0 and deleted. They are absent from process
arguments, logcat and persistent evidence. The trusted external laboratory
launch chain is not production root-manager consent or remote attestation.
Loopback control does not establish peer UID; generation authentication does.

Surface delivery uses real Android Binder/Parcelable semantics through the
existing DEBUG ContentProvider. The new finite method is
`open_root_encoder_surface_v1`; the existing Shell v1 method remains strictly
UID2000-only. Root leases require DIRECT_ROOT, UID0, the exact authenticated
helper PID, runtime generation, fresh Surface generation, geometry, HMAC and
an unexpired single issuance (10-second handoff deadline). The app borrows the
original Surface into the lease; only its encoder releases that original.
The helper receives/releases its own parceled reference.

Pure tests cover Shell UID2000 acceptance, Root UID0 acceptance, both cross-UID
rejections, wrong provider/PID, stale runtime/Surface generations, wrong HMAC,
consumed/expired/closed lease and malformed/oversized metadata. The hardware
probe additionally rejects old runtime/Surface generations, wrong HMAC,
wrong provider, invalid magic and a second issuance before creating the mirror.
No Surface object/token or authentication proof is logged.

## API26 Framework And Encoder Findings

FACT: the shared resolver selects **SurfaceControlDisplay** in the tested UID0
API26 process. It resolves `android.view.SurfaceControl` methods:
`createDisplay(String, boolean)`, `destroyDisplay(IBinder)`,
`setDisplaySurface(IBinder, Surface)`,
`setDisplayProjection(IBinder, int, Rect, Rect)`,
`setDisplayLayerStack(IBinder, int)`, `openTransaction()` and `closeTransaction()`.
Display information comes from `DisplayManagerGlobal.getInstance()` and
`getDisplayInfo(int)`. Secure-mode qualification creates/destroys its existing
tiny disposable target; it is not an ImageReader frame-transport pipeline.

FACT: API26 Binder acquisition exposes
`IActivityManager.getContentProviderExternal(String, int, IBinder)`,
`removeContentProviderExternal(String, IBinder)` and
`IContentProvider.call(String, String, String, Bundle)`. The Root adapter resolves
these signatures, passes no fabricated package attribution (`callingPkg=null`),
and the provider checks real Binder UID/PID plus the authenticated lease.
Primary sources: [AOSP IActivityManager](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/IActivityManager.aidl)
and [AOSP IContentProvider](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/content/IContentProvider.java).
No external implementation was copied. Missing signatures fail closed;
modern Root Binder signatures are not qualified by this task.

FACT: AOSP `OMX.google.h264.encoder` advertises Surface AVC but not CBR in codec
metadata. The existing `AndroidExactVideoEncoderCapabilityProbe` successfully
configured/started the exact CBR Surface format. The DEBUG lab selector uses
that real active probe, not a false metadata assertion. Hardware acceleration
remains Unknown; the normal production hardware-selection policy is not relaxed.
The configuration is AVC, Surface, 640x360, 30fps, 1,000,000bps CBR, 1s IDR interval.

Geometry: default logical display 1080x1920 portrait, rotation 0, layer stack 0;
encoder 640x360 landscape. The unchanged aspect-preserving projection yields
approximately 202x360 content centered between black side bars. Those bars are
expected, not blank capture. No API/model/OEM allowlist or new geometry formula.

INFERENCE: the same shared mirror contract can serve Root without changing the
downstream architecture. UNKNOWN: physical Root SurfaceFlinger/codecs/OEM policy,
nondefault displays and production Magisk authorization. This lab does not
establish any of those properties.

## Real Content Verification And Attempt Accounting

The app opens the existing owned visual fixture, animating a bounded magenta
marker to keep display changes observable. State A contains a white circle;
state B contains a blue/yellow checkerboard. It records two bounded app-private
AVC windows, each beginning at an observed IDR with codec configuration, for
1.8 seconds after a 0.5-second fixture settle. At most 8MiB per window, no
continuous recording and no Root/control-channel video copying.

An offline FFmpeg observer decodes one frame from each app-owned AVC file.
The harness verifies 640x360 dimensions, the known white A center and colored
B center, then submits the DUMP-gated confirmation. Only after decoded-content
proof does Root Video become QUALIFIED and persist. Encoder counters alone
leave the result AWAITING_DECODED_CONTENT. FFmpeg is a generated lab-only tool,
not a Warpnect dependency, alternative encoder or capture path.

Exploratory attempt 1 failed **0/1**, before Surface handoff, at
LAB_AVC_SURFACE_CBR_CODEC_UNAVAILABLE. Its DEBUG discovery incorrectly relied
solely on codec CBR metadata and threw on the encoder thread, leaving the
diagnostic caller waiting. The failed artifact/log is retained. Only the DEBUG
selector was corrected: real existing exact-format probe plus a typed unsupported
result on failure. No production codec policy was weakened.

Exploratory attempt 2 passed **1/1** on APK SHA-256
`A28330E94BA2586546B84577EF2081C23A3EB7E237F0D3B7CFE216FA5B158B74`:
helper PID2803, UID/GID0, u:r:su:s0, generation7113047610126569122,
Surface generation5860435875156506120. A: 41 AUs / 17,940 bytes / 1 IDR;
B: 44 AUs / 34,128 bytes / 1 IDR; errors0, increasing timestamps. Offline decoded
centers were A=(253,253,253), B=(0,0,254); visual inspection confirmed the whole
circle/checkerboard and expected letterboxing. Helper FDs 30->35 after framework
initialization; app FDs67. Cache restored VIDEO+INPUT after process restart with
runtime/capture/Surface lease absent. This is diagnostic evidence, not the final
frozen-artifact three-cycle campaign or a hidden retry.

### Final Frozen-Artifact Campaign

On 2026-09-27 the frozen-source exact APK passed **3/3 fresh qualifications,
without retry**. APK: `app/build/outputs/apk/debug/app-debug.apk`, 33,575,465 bytes,
versionCode1 / versionName0.0.1; packaged arm64-v8a, armeabi-v7a, x86_64.
SHA-256:

```text
BB3B4D5B890F5869CF4788B44AF63161A0DD034EB3FB066EBABFC0AF507346FC
```

Source is local Input checkpoint `ae431d9` plus this uncommitted Video slice,
not a claim that the APK corresponds solely to that commit. Installed base.apk
and each helper-side hash matched. Target: Warpnect_Root_API26, Android8.0.0,
API26, AOSP/default x86_64 r1, userdebug/test-keys, qemu1, SELinux Enforcing.
Fingerprint:
`Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.
All helpers reported UID/GID0/0, u:r:su:s0, protocol3 and
LABORATORY_EXTERNAL_ROOT from inside Warpnect-owned code. App PID2679 remained
unprivileged. This is emulator evidence, not physical Android8 Root support.

| Cycle | Root generation | Surface generation | Helper PID / port | A AUs / bytes | B AUs / bytes | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 3120782965642290279 | 7221890676348400973 | 2788 / 43987 | 37 / 17444 | 40 / 31642 | PASS |
| 2 | 6756672014271132832 | 8787789606765612105 | 3176 / 41063 | 44 / 17842 | 45 / 33270 | PASS |
| 3 | 1054938904663070196 | 7587197230880327174 | 3551 / 45894 | 37 / 16986 | 38 / 32612 | PASS |

Each window observed one IDR, CSD sizes19/10, increasing PTS and encoder errors0.
PTS ranges (microseconds): cycle1 A59099382..60868049,
B61459860..63199849; cycle2 A79513370..81283307,
B81847171..83581658; cycle3 A98027891..99790485,
B100362136..102111329. Every decoded A center=(253,253,253),
B=(0,0,254); both complete images showed the expected fixture and letterboxing.
Each cycle rejected stale runtime/Surface, wrong HMAC/provider/magic and consumed
lease, stopped the mirror, released Surface/provider and shut down its helper.
Cycle1 additionally requalified the retained Input path on the protocol3 APK:
owned target receipt, ordering/reset PASS, active state0. This is 1/1 Input
regression on this artifact, not three new Input qualifications.

After shutdown and app process restart, persisted records were VIDEO+INPUT,
`runtime_ready=false active=false surface_lease=false`; no startup capture.
Evidence: `app/build/reports/root-lab/root-video-final-campaign.log` and
`root-video-20260927-154953/` (AVC, decoded PNG and per-cycle logs).

### Loss, Resources And Residual Limits

Two additional active-capture cases passed **2/2** on the same frozen APK:

* Helper loss: generation2958875316687885616, PID4405, explicit TERM of only
  the verified helper. Heartbeat published HELPER_LOST; encoder thread ended;
  `runtime_ready=false active=false surface_lease=false`. Mirror/helper/listener/
  bootstrap files0, no remediation. Codec release is also confirmed by the
  emulator's ResourceManager event records.
* App loss: generation6831847893931951831, PID4757. App force-stop closed the
  owner connection; helper observed EOF at14:00:52.340 UTC, released mirror at
  14:00:52.528 and exited by14:00:52.565. Restart loaded VIDEO+INPUT without
  runtime/capture/lease. Mirror/helper/listener/bootstrap files0.

An earlier loss setup was interrupted before deliberate helper termination;
it is not included in those two PASS cases. Pre-launch PowerShell alias/AVD
newline setup errors likewise performed no credited qualification. Evidence
is retained rather than counted as hidden successful retries.

Final campaign helper FD counts were **30->35** in each fresh process after
framework initialization. App samples during the three live authorities were
67,68,69. Investigation inspected descriptor targets and a separate three-cycle
stop/shutdown campaign in app PID4890: quiescent counts64,64,66, containing
5,5,6 emulator goldfish pipes and4,4,5 ashmem FDs. Active helper-loss capture had
71 app FDs and returned to64; codec eventfd/eventpoll and Root control socket
were released. The remaining variation is in emulator graphics allocations,
not retained Root listeners, Surface leases or encoder threads. A safe emulator
memory-trim observation did not eliminate all remaining graphics descriptors.
**No claim of bit-identical FD counts or indefinite soak leak-freedom is made**;
longer graphics-resource profiling remains a laboratory limitation. These short
samples do not establish unbounded growth or a Root protocol ownership leak.

Final SurfaceFlinger had no WarpnectCapture display; ResourceManager retained
historical PID entries but no active codec clients/resources. Root helpers,
control listeners, handoff leases, bootstrap files and unexpected encoder
threads were0. The only remaining loopback listener was adbd5037, not Root
control. `adb unroot` restored UID/GID2000 / u:r:shell:s0; Enforcing remained
unchanged, and RootLab stopped the AVD. API25/machine AVD configuration untouched.
Evidence: `root-video-loss-final.log`, `root-video-resource-campaign.log`,
descriptor snapshots and `root-video-resource-manager-after.txt` in the generated
Root-lab report directory. Those are not checkpoint files.

### Optional Physical DirectShell Control

The exact APK was installed without clearing data on connected Tablet/API33.
The first normal Enable Host reached **Host Available / Waiting for clients**;
semantic Disable Host returned Ready and left0 Warpnect privileged helpers.
A second observation attempt failed before complete runtime preparation:
**FAIL_PRIVILEGED_RUNTIME_PREPARATION**, first visible boundary. Its transient
Input shell helper was cleaned up and Dismiss returned Ready. Accounting is
**1/2 Host admission attempts**, not2/2 and not a Root Video failure.

The unchanged cache contains three QUALIFIED DIRECT_SHELL coordinates and no
Root records. In the second attempt the observed feature process was shell
`warpnect-inputsession-*`; no Root helper or Shizuku UserService appeared.
App telemetry was not available from this physical device's logcat collection,
so the first run's exact frozen plan/provider selection is **not independently
telemetry-confirmed**. Host UI success is established; a new complete physical
media/Session regression is not claimed. No audio tone, Root command, settings
change, rooting, flashing or wipe was performed on any physical device.

## Matrix, Planning, Persistence And Passive Readiness

The intended qualified lab matrix is Root Video=QUALIFIED, Input=QUALIFIED,
SystemAudio=NOT_IMPLEMENTED. Root-only Complete Host is still rejected, missing
SYSTEM_AUDIO. No partial Host, WNCP publication or planner ranking change.
Tests explicitly cover Root Video+Input incomplete; synthetic complete Root
SINGLE; Root Video+Input plus Shell audio COMPOSITE; and complete Shell SINGLE
preference over an unnecessary composite. Synthetic Root audio is a fixture,
never implementation/capability evidence.

Persistent Video/Input keys bind exact API, fingerprint hash, app version,
helper/backend revisions and LABORATORY_EXTERNAL_ROOT provenance. Root audio
evidence remains rejected. Lab records cannot satisfy future physical/production
Root-manager keys. A transient helper loss retains compatibility evidence;
authoritative Video contradiction invalidates only Video, preserving Input.
Liveness, secrets, PIDs, adbd Root state and active mirrors are never persisted.
Passive startup loads evidence but starts no encoder, mirror, Surface lease or
Root helper. Current matching runtime and late plan admission remain mandatory.

## Ownership And Loss

Normal stop destroys the mirror, releases Root's Surface reference and external
provider hold, clears the app's Surface lease, stops/releases the app encoder,
then releases the shared Root runtime when its owner ends. Stop is idempotent;
gateway close does not prematurely close a runtime also used by Input.
Runtime loss invalidates the gateway, reports service death once, and tears down
the encoder through the existing owner callback/explicit lab cleanup. No retry,
remediation or provider migration. App death closes the authenticated connection;
helper cleanup releases the mirror before process exit, bounded also by idle and
absolute generation expiry. New Root and Surface generations cannot reuse leases.

## Reproduction

```powershell
.\tools\root-lab\RootLab.ps1 -Action Start -Api 26 -Port 5556
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$serial = 'emulator-5556' # Scripts verify actual AVD/API/qemu/package hash.
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
.\tools\root-lab\Test-RootVideo.ps1 -Serial $serial -FfmpegPath <local-ffmpeg> -Cycles 3
.\tools\root-lab\RootHelper.ps1 -Action Stop -Serial $serial
& $adb -s $serial unroot
.\tools\root-lab\RootLab.ps1 -Action Stop -Api 26 -Port 5556
```

All Root tooling rejects physical serials before ADB, verifies the named API26
AOSP AVD, qemu, installed base.apk hash, bounded launch/control and Enforcing
SELinux in the campaign. It never wipes data, modifies system/vendor/settings,
changes SELinux or kills unrelated processes. The normal app cannot use AOSP su;
LABORATORY_EXTERNAL_ROOT is distinct from future product acquisition.

## Deferred Boundaries

Production Root authorization / Magisk: NOT IMPLEMENTED.

DirectRoot SystemAudio: NOT IMPLEMENTED.

Root-assisted Runtime Remediation: NOT IMPLEMENTED.

Same-provider Recovery: NOT IMPLEMENTED.

Live Provider Migration: NOT IMPLEMENTED.

No arbitrary Root shell, arbitrary Binder RPC, generic reflection, serialized
Surface over TCP, encoded video over Root control, physical Root claim or
automatic remediation. Future physical Root qualification must independently
test root-manager consent, OEM SELinux, kernel/vendor and hardware codecs.

Next recommended task after successful final qualification:
**DirectRoot SystemAudio Capability**, not implemented here.

## Software Gates And Repository Result

Frozen-source gate run: ktlintCheck, lintDebug, testDebugUnitTest with
`--rerun-tasks`, assembleDebug, assembleDebugAndroidTest and
packageDirectShellProbeDex all PASS; 100/100 tasks executed,10m50s.
**841 JVM tests in139 suites,0 failures,0 errors,0 skipped** (Input baseline807).
The34 new cases cover Surface lease/request codec14, Root Video gateway/loss10,
finite authenticated Video protocol4, exact planner cases4 and selective
Video/Input cache cases2. They do not substitute for emulator content proof.
`git diff --check` and five Root-lab PowerShell parser checks PASS; RootHelper
and Video campaign reject a physical serial before invoking ADB. Native source
unchanged, so native CTest is not rerun; prior22/22 Debug/Release stays historical.
Build log: `app/build/reports/root-lab/root-video-final-gates.log`.

Updated documentation: this report, RFC-006J, Phase6.5 README, FutureRoadmap,
foundation/Input reports (append-only follow-up), and the emulator-only section
of AndroidCompatibilityMatrix. Historical qualification reports remain scoped
to their original artifacts.

New source/tests/tooling/docs remain uncommitted; index empty, local HEAD
`ae431d9`, master/upstream unchanged. Pre-existing150 generated native entries,
historical artifacts/screenshots/XML and the unrelated line-ending-only
AndroidInputCaptureController entry are preserved, not part of the Video slice.
Generated APKs/reports/formatting helpers/offline observer, app-local diagnostic
AVC and machine-local AVD/userdata/snapshots are not checkpoint assets.

Decision: **DIRECTROOT_VIDEO_CAPABILITY_VALIDATED_IN_AOSP_LAB**.
Capability evidence is conservatively QUALIFIED for the exact lab, not universal
VALIDATED/physical or production Root support. Root-only Host remains incomplete
because SystemAudio is NOT_IMPLEMENTED.

## SystemAudio Follow-up

This historical Video slice was checkpointed locally as `84f79dd`. The later
`DirectRootSystemAudioQualification.md` supersedes only the missing Audio cell
for its exact API26 AOSP laboratory. Helper version4 changes the compatibility
key, so the new campaign requalifies Input and decoded Video content on every
fresh Audio helper generation; old version3 evidence is not silently reused.
The original software-encoder DEBUG boundary, production hardware-codec policy,
no physical Root claim and production Root-authorization deferral remain.

The final Audio artifact requalified decoded Video3/3 on those fresh version4
generations: fixtureA center253/253/253, fixtureB0/0/254, app-owned640x360 AVC.
After actual current-generation Input and Audio also qualified, the real
prepared-plan builder and late admission freeze returned SINGLE/DIRECT_ROOT3/3.
That scoped plan proof is not ordinary product Host Available/discovery/network
admission. The full visual fixture's app FD samples65/68/70 remain a recorded
resource-observability limitation; the isolated Audio samples60/60/60 do not
establish indefinite graphics leak-freedom. No Video implementation or hardware-
codec policy was changed to force ordinary Host readiness.
