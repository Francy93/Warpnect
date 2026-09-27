# DirectRoot Normal Host Admission - AOSP API26

Date: 2026-09-27. Scope: the real application Enable/Disable Host boundary,
not a new capability, production Root authorization or a remote Session.

## Baseline And Checkpoint

Branch: `codex/directshell-production-runtime-integration`.
Starting HEAD: `84f79ddac95b44db63681cf4d1d9f73cab2d8f62`.
Previous validated SystemAudio checkpoint: `022ba4d`,
`feat(privileged): add DirectRoot SystemAudio capability` (52 source/test/tool/doc paths).
Master remains `032b59276a0e01e4674e807a8ffb5b5b86e9150e`;
upstream remains `9d0b4dce7ea04a82e81778de6296cc1096775d31`.
No push, merge, rebase, reset, clean or master fast-forward.

Initially the index was empty. The SystemAudio slice was checkpointed without
the 150 pre-existing generated native entries, historical untracked lab
artifacts/screenshots/XML or the line-ending-only InputCapture status entry.
Those remain preserved. A verified stale, empty Git index lock was removed
before staging; no checkout content was removed. New Host work is uncommitted.

## Previous Gap

`DirectRootCompletePlanProbe` had genuinely qualified capabilities, retained
the runtime, used the real planner and frozen a Root-only plan. It did not
invoke `SecureSessionApplicationController.startHost()`, publish discovery or
own the normal UI transition. The normal readiness adapter still acquired
DirectShell, whose Wireless Debugging branch is unsupported on API26.
The product codec collector also deliberately required hardware encoding,
whereas the accepted AOSP fixture uses an explicit DEBUG Surface codec.

Freeze authorizes admission; it cannot by itself publish Host.

## Application Path

```text
WarpnectApp Enable Host
  -> AndroidSecureSessionComposition.startHostFromUi()
  -> BackgroundHostReadinessCoordinator (single-flight)
  -> AndroidHostReadinessWork: reusable evidence + late Root PING
  -> AndroidHostPrivilegedCapabilityAdmission
  -> DirectRootHostOwnership: current authenticated authority/resource factory
  -> existing Video/Audio/Input controllers acquire resources
  -> unchanged RFC-006I acquisition, bounded pre-freeze replan and freeze
  -> PreparedHostCapabilityCollector: current owned capability snapshot
  -> HostAdmissionPublicationGate
  -> normal SecureSessionApplicationController.startHost()
  -> Host driver / discovery / pairing responder
  -> Host Available / Waiting for clients
```

Counted automation calls the same composition action as the UI through a
DUMP-protected DEBUG receiver. It never calls the qualification probe or
`planner.freeze()` directly. A frozen readiness state now leaves the Host UI
NotChecked until the normal application Host start succeeds.

The DEBUG `LaboratoryDirectRootHostResources` factory is invoked only for an
explicit Host request. It owns the existing Video capture controller and
app-owned MediaCodec Surface encoder, the existing SystemAudio controller and
PCM Ring V1/drain, and the existing Input controller. It requires actual AVC
configuration/AU and drained PCM progression before admission. It emits no
Input event and plays no fixture tone.

`LaboratoryRootVideoEncoderDiscovery` is extracted from the accepted Video
qualifier. Its finite tested profile is AVC 640x360, 30fps, 1Mbps, Surface input.
The DEBUG resource adapter explicitly records real Surface encoder acceptance;
it does NOT assert hardware acceleration. Ordinary hardware-only discovery is
unchanged, and the VIDEO_HARDWARE_ENCODE capability flag remains unset here.
No wire format, planner ranking or native source changes.

## Laboratory And Artifact

Only `Warpnect_Root_API26`, `emulator-5556`, received Root operations.
Android 8.0.0 / API26, AOSP/default x86_64 r1, userdebug/test-keys.
Fingerprint:
`Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.
Natural SELinux Enforcing. Helper UID/GID 0/0, `u:r:su:s0`; ordinary app UID10065.
Example helper identity: PID3323, generation3350376855842177985, protocol4,
implementation `warpnect-root-video-audio-input-v1`.

Final installed APK SHA-256:
`5ccd6dfe064b8c785c65b3c6d77d17e7db76147c044baa78a7db012f67c3a70a`.
Size: 33,641,001 bytes. No APK/source change during the counted campaign.
The later unit-test assertion fix does not change this APK.

The external, emulator-only RootHelper harness verifies AVD/qemu/API/package
and exact installed base.apk hash before launching the existing UID0 helper.
The app authenticates its current RootControlSession and verifies UID/GID,
generation, implementation, protocol, provenance and APK hash. This is
LABORATORY_EXTERNAL_ROOT, never production Root-manager authorization.
No production source depends on desktop ADB, PowerShell, AVD names or serials.

## Passive Readiness And Evidence

App startup loaded exactly three compatible Root records, with runtime absent,
no helper, no mirror/encoder, no AudioRecord/ring and no active Input gateway.
Before the explicit lab boundary is established, production Root bootstrap is
NOT_IMPLEMENTED and no Root prepared plan is claimed. After authenticated
laboratory authority acquisition, background readiness prepared ADVISORY
SINGLE/DIRECT_ROOT from those records without starting capability resources.
Enable Host joined readiness and late-revalidated actual helper liveness.

`RootHostEvidence` rebinds active owner generation only after compatible
cached cells and current authority match. It neither rewrites evidence nor
substitutes cached qualification for a live runtime. Resource acquisition and
provider retention recheck the current generation before freeze.

All selected active owners are the same Root runtime generation. The telemetry
fields `video_generation`, `audio_generation`, `input_generation` refer to
their runtime owner; Surface and PCM-ring sub-generations remain separately
fresh and authenticated by the existing bridges.

Every genuine frozen plan had:

```text
plan_type = SINGLE
Video = DIRECT_ROOT
SystemAudio = DIRECT_ROOT
Input = DIRECT_ROOT
commit = FROZEN_AT_HOST_ADMISSION
```

Persistent evidence file SHA-256 before/after every successful cycle, restart
and controlled outage remained:
`1e36dae67f546bfeb4509c844946fffc3b0157e0f8680be0d950d42921f890f6`.
Root cells remain QUALIFIED for the exact lab key; no liveness is persisted.
DirectShell and Shizuku were not selected or started for these admissions.

## Exact Reliability Campaign

Requested maximum10; runner stopped at the first failure. Result **8/9**,
with **8 consecutive normal Enable/Disable Host PASS** on the exact APK.
Attempt10 was NOT_ATTEMPTED. No hidden retry or relabeling of attempt9.

| Attempt | Root generation / PID | Host publication | Drained frames / AUs at release | App FD before / prepared / active / after | Helper active FD | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 3350376855842177985 / 3323 | AVAILABLE | 918864 / 14 | 59 / 60 / 79 / 60 | 43 | PASS |
| 2 | 6586289355959973622 / 3725 | AVAILABLE | 504832 / 13 | 60 / 61 / 81 / 62 | 43 | PASS |
| 3 | 8207232042824334662 / 4100 | AVAILABLE | 522320 / 13 | 62 / 63 / 81 / 62 | 43 | PASS |
| 4 | 8597229382648119814 / 4468 | AVAILABLE | 439296 / 13 | 62 / 63 / 81 / 62 | 43 | PASS |
| 5 | 5239358168975520571 / 4840 | AVAILABLE | 369488 / 13 | 62 / 63 / 81 / 62 | 43 | PASS |
| 6 | 4270227596362706621 / 5207 | AVAILABLE | 454416 / 13 | 62 / 63 / 81 / 62 | 43 | PASS |
| 7 | 7537328339519473247 / 5576 | AVAILABLE | 637920 / 14 | 62 / 63 / 81 / 62 | 43 | PASS |
| 8 | 1780044301081685823 / 5945 | AVAILABLE | 454416 / 13 | 62 / 63 / 81 / 62 | 43 | PASS |
| 9 | No helper or resource acquisition | Not reached | Not reached | Not sampled | Not sampled | FAIL observational precondition |

All successful releases logged `errors=0 cleanup_errors=0`, inactive Input,
AudioRecord/ring/pipe/provider cleanup and mirror/Surface/provider cleanup;
the runner verified helper/listener/bootstrap files gone and Host Idle with
advertising=false. Each acquisition had AVC config, >=1 AU and 18,240-22,320
initial drained PCM frames. No fixture tone, raw content logging or remote
Session was used. These are ownership/progression checks, not new Audio A/B
qualification or audible E2E claims.

Example causal interval, cycle1 (device timestamps):
20:59:36.885 resources ACQUIRED; 20:59:37.487 real readiness FROZEN;
20:59:38.490 normal HOST_PUBLICATION AVAILABLE; LAN registration active;
20:59:48.159 actual application Host/Discovering, advertising=true,
LAN Running, readiness Ready, UI Available;
21:00:04.972 resources RELEASED; 21:00:05.076 host lifecycle stopped;
21:00:05.174 helper shutdown/endpoint released.

The captured final-APK screenshot visibly shows Host: Available, Waiting for
clients and Disable Host. LAN/mDNS registration succeeded through the normal
backend. Direct discovery is unsupported in this AVD; UI correctly says one
backend is unavailable. Physical-peer visibility and a remote Session were
NOT_TESTED; local registration is not proof of multicast reachability to a
physical device.

## Resource Classification

App FD totals stabilized at62 for cycles2-8. Initial retained additions were
only emulator graphics `goldfish_pipe` descriptors: 3 initially, 4 after cycle1,
6 after cycle2, then6 through cycle8. A socket inode was unchanged even when
its descriptor number moved. Quiescent categories were stable: 2 pipe ends,
4 ashmem FDs,7 sockets. Active categories were8 pipe ends,11 ashmem FDs,9 sockets;
these returned to baseline after each disable. The prepared Root control socket
added one FD and was released. Every active helper sampled43 FDs and exited.
The saved maps for every cycle1-8 show exactly two app mappings of
`/dev/ashmem/warpnect-root-audio` while active and zero after disable.

No monotonic Warpnect control/PCM/notify/ACK FD growth was observed in this
bounded campaign. The earlier65/68/70 fixture samples are not retroactively
proven leak-free: this normal Host campaign supplies categorized, stabilized
evidence, not an indefinite graphics-soak guarantee. Graphics initialization
and framework CodecLooper retention are distinct from an active Warpnect codec.

Normal release stops/resets/closes Input, stops/drains/closes Audio, stops the
mirror, releases its borrowed Surface lease, stops/releases the app-owned
MediaCodec/Surface and releases Root authority. Encoder stop is awaited;
existing asynchronous encoder-thread close follows. No broad process kill.

## Restart And Controlled Loss

Warm app restart: three records reloaded; Root runtime_ready=false, generation
absent, Input snapshot null, helper/files0, no capability started. After explicit
lab authority acquisition the independent normal admission passed **1/1**:
generation5268458080196351830, PID6516, ADVISORY at21:10:03.119,
FROZEN at21:10:08.052, normal publication AVAILABLE at21:10:08.125,
normal release312320 PCM frames/13 AUs/errors0 at21:10:14.098.

Final controlled lifetime campaign **2/2**:

* Frozen-helper loss: generation5029214978804793533/PID7284.
  AVAILABLE21:15:10.057; after only that UID0 helper was terminated,
  WITHDRAWN ROOT_HELPER_UNAVAILABLE migration=false21:15:13.280;
  resources RELEASED21:15:13.797, cleanup_errors0, one expected loss error;
  discovery stopped21:15:13.806; actual application Idle/advertising=false.
  Readiness converged to Stale/NotChecked, not an Available Host. The loss
  reason is recorded in lifecycle telemetry; no sticky error banner is claimed.
* Pre-freeze loss: generation2825193991131467047/PID7633.
  Actual runtime HELPER_LOST21:15:27.305; normal Enable Host failed
  ROOT_HELPER_UNAVAILABLE21:15:28.718. No media acquisition/publication or
  alternative provider; UI explicitly showed the Root connection-loss reason.

Neither outage deleted stable evidence. No post-freeze migration/recovery.
Synthetic tests prove unchanged bounded pre-freeze replan can use a genuine
complete alternative; none exists in this API26 lab.

Final observation: helpers/listeners/bootstrap files0; SurfaceFlinger contains
only Built-in Screen and no WarpnectCapture display; AudioFlinger has no Record
client; no Warpnect encoder/drain/helper thread remained. A framework
CodecLooper and ordinary rendering/discovery threads remain, not claimed as
active capture. Input state was0; Surface/PCM leases were cleared by their
owners. API25/minSdk26/AVD configuration are unchanged; no physical Root action.
The final normal application snapshot is Idle, advertising=false, LAN Stopped,
readiness Stale, UI NotChecked. After verifying AVD/qemu/API and exact installed
APK again, `adb -s emulator-5556 unroot` restarted adbd; its actual identity was
UID/GID2000/2000, `u:r:shell:s0`. Guarded RootLab Stop then shut down API26.

## Anomalies And Escalation

* Formal attempt9: first boundary was emulator identity inspection BEFORE
  Prepare/Root launch/Enable Host. ADB returned stdout26 with a nonzero status;
  the old harness omitted the numeric child status. Runner exited1, but that
  must not be confused with a proven ADB exit1. Nearest PASS is cycle8 with
  the same APK/API/clean resources. One bounded read-only reproduction returned
  26/exit0. Classification INCONCLUSIVE observational precondition, not evidence
  of a Root capability/product regression. No product workaround or retry.
  Runner now logs exact command/child exit/stdout/stderr. Follow-up debt:
  characterize recurrence if this SDK/emulator observation anomaly returns.
* Exploratory loss observer: timestamp became only09-27 through command quoting,
  so logcat -T rejected it. Independent product telemetry proves the Host had
  reached AVAILABLE (generation443066198509804527/PID6909). Normal disable
  released it. Classification TEST_HARNESS_DEFECT; only the observer changed.
* New lifetime guard initially rejected doubled CR in `emu avd name` before any
  Root operation. Same verified AVD/API/qemu; classification TEST_HARNESS_DEFECT.
  Trim normalization now matches existing RootHelper safety checks, without
  weakening identity validation. The new separate2/2 campaign is the loss proof.
* Development compile/style failures and a new Set-versus-List unit assertion
  are preserved in build reports. Planner had selected the expected DirectShell
  alternative; only the assertion changed. No planner policy or product behavior
  was changed to satisfy the fixture. Final tests pass on all931 cases.

## Software Gates

`git diff --check`, ktlintCheck, lintDebug, testDebugUnitTest --rerun-tasks,
assembleDebug, assembleDebugAndroidTest and packageDirectShellProbeDex PASS.
Final JVM: **931 tests,0 failures,0 errors,0 skipped**, including27 new cases.
Lint:0 errors/121 warnings. Compilation retains existing Kotlin copy-visibility
and deprecation warnings. Native source unchanged; CTest not required/rerun.
RootHelper and both new harnesses pass PowerShell parsing and physical-serial
rejection. A previous failed build client was explicitly canceled after its
recorded assertion failure to avoid overlapping memory-heavy lint work; the
final rerun passed (12m28s). No APK change or hidden hardware retry resulted.
The final packaging-only verification also passed in19s (74 tasks,6 executed,
68 up-to-date); APK hash/size remained identical. It retains the toolchain's
SDK XML-version and Gradle-deprecation warnings, not new gate errors.

New tests cover compatible Root cache/generation/provenance, missing Audio,
UID2000 refusal, freeze without publication, normal start success/failure,
single-flight, Root late loss, immutable frozen ownership, disable/restart,
late provider liveness/resource disposal, pure bounded Root replan and the
explicit lab codec without a false hardware flag. Existing Shell/Shizuku
tests remain green; no physical DirectShell regression is claimed here.

## Changed Files And Reproduction

New main components: `DirectRootHostOwnership`, `RootHostEvidence`,
`HostAdmissionPublicationGate`. Changed main adapters: the three Root capability
registries, `AndroidHostReadinessWork`, `BackgroundHostReadinessCoordinator`,
`PrivilegedRuntimeReadinessModel`, `AndroidHostPrivilegedCapabilityAdmission`,
`AndroidLocalCapabilityCollector`, `AndroidSecureSessionComposition`.
DEBUG: new `DirectRootHostResources.kt`, lab controller public Host actions and
extracted Video codec discovery. Tests: new RootHostAdmissionIntegrationTest,
DirectRootHostOwnershipTest, plus planner-admission and capability-collector tests.
Tooling: RootHelper's finite EnableHost/DisableHost/HostStatus actions and new
Test-RootHostAdmission.ps1 / Test-RootHostLifetime.ps1. Documentation: this report,
RFC-006J, Phase6.5 README, roadmap, Root lab and prior four Root reports, and the
separate AOSP compatibility section. Historical reports retain prior outcomes.

```powershell
./tools/root-lab/RootLab.ps1 -Action Start -Api 26 -Port 5556
# Install the exact local APK without clearing data, explicitly targeting emulator-5556.
./tools/root-lab/Test-RootHostAdmission.ps1 -Serial emulator-5556 -Cycles 5
./tools/root-lab/Test-RootHostLifetime.ps1 -Serial emulator-5556
# After helpers/resources are stopped, verify AVD/qemu/API before adb -s ... unroot.
./tools/root-lab/RootLab.ps1 -Action Stop -Api 26 -Port 5556
```

Fresh labs must first obtain genuine Video/Audio/Input qualification records;
these scripts do not seed synthetic evidence. Generated JSON/logs/fd/maps/PNG,
temporary formatters and machine-local AVD files remain outside checkpoints.

## Security And Deferred Work

No arbitrary Root command API, generic Binder/FD/object broker, PCM/video over
control TCP, production ADB dependency, false hardware codec flag, physical
Root/Magisk claim, remediation or post-freeze provider migration.
Existing HMAC/challenge/request order/fresh secrets/lease/identity/security
bridges remain unchanged. Secrets are neither logged nor passed in argv.

Production Root authorization / Magisk: NOT IMPLEMENTED.
Physical DirectRoot validation: NOT VALIDATED.
Root-assisted Runtime Remediation: NOT IMPLEMENTED.
Same-provider Recovery: NOT IMPLEMENTED.
Live Provider Migration: NOT IMPLEMENTED.

The actual normal application boundary is validated repeatedly in the exact
AOSP lab, with genuine compatible ownership of all three required capabilities.
It is not production-ready Root or a physical Root Session claim. Minimum five
consecutive admission cycles is exceeded; requested maximum10 was not completed
because the ninth observational failure remains recorded.

Next recommended task, not implemented: **Physical DirectRoot Device Qualification**.

**SINGLE_DIRECT_ROOT_HOST_ADMISSION_VALIDATED_IN_AOSP_LAB**
