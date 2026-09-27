# DirectRoot SystemAudio Qualification

## Baseline And Evidence Scope

Branch: `codex/directshell-production-runtime-integration`. Starting HEAD:
`ae431d9e26d21833faad2b21b77b2621f1ae04c9`. The previously validated Video
slice was reviewed and checkpointed locally as
`84f79ddac95b44db63681cf4d1d9f73cab2d8f62`
(`feat(privileged): add DirectRoot Video capability`, 33 files). Generated
AVC/PNG/logs, formatters, native build output and machine-local AVD data were
excluded. Master is `032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream remains
`9d0b4dce7ea04a82e81778de6296cc1096775d31`. No push, merge, reset or rebase.
The new SystemAudio slice is uncommitted for review.

All Root experiments target only `Warpnect_Root_API26`, `emulator-5556`:
Android 8.0.0 / API26, AOSP/default x86_64 r1, userdebug/test-keys, qemu=1,
SELinux **Enforcing**, fingerprint
`Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.
The ordinary app is UID10065; the actual helper observes UID/GID0/0,
`u:r:su:s0`, provenance `LABORATORY_EXTERNAL_ROOT`. `/system/xbin/su` remains
unusable by the ordinary app. minSdk remains26; API25 is untouched.

This proves neither Samsung S7/API26 or S9/API29 Root SystemAudio nor generic
Android8-10 support. Physical OEM audio HAL, root-manager consent, SELinux,
hardware codecs and production authorization require independent qualification.

## Existing Architecture And Reuse

```text
Warpnect-owned normal AudioTrack fixture, OFF / 997Hz ON
  -> Android playback / REMOTE_SUBMIX
  -> authenticated UID0 RootAudioBridge / AudioRecord
  -> existing SharedPcmAudioRingLayout V1
  -> actual notify pipe (helper writes / app reads)
  -> existing SharedPcmAudioDrain in ordinary app
  -> actual ACK pipe (app writes / helper reads)
  -> existing PcmAudioSink
  -> existing OpusPcmAudioSink / NativeOpusAudioEncoderController [bounded smoke]
```

The existing Shizuku UserService creates Java SharedMemory and both pipes,
owns AudioPolicy/AudioRecord and duplicates consumer descriptors to the app.
The validated DirectShell path instead uses app-created memory/pipes and a
purpose-specific framework descriptor bridge. Root retains that ownership
direction, ring header/slots/generations/frame alignment and signal records.
It does not create another PCM ring, audio format, encoder or network path.

The unchanged ring has a64-byte header,8 slots,64-byte slot metadata and
960-byte stereo PCM payload per slot:8256 bytes total,240 frames/5ms per chunk.
Notifications/ACKs are the existing8-byte slot-index/generation records. A
full ring consumes/discards the captured chunk rather than growing a queue;
frame positions preserve the resulting discontinuity. Timestamp quality is
EstimatedFromReadCompletion. ACKs confirm completed consumer reads; the existing
shared slot state remains authoritative. No additional polling synchronization.

New `PcmRingProducerCore` drives that original layout/signaling contract with a
bounded read callback and diagnostics. The old DirectShell/Shizuku producer
implementations were not replaced; their behavior and production API27 floor
remain unchanged. The common drain's memory ownership is narrowly generalized.

## API26 Shared Memory Boundary

The old production path genuinely depends on Java SharedMemory/API27. It did
not already contain an API26 native adapter. This slice adds provider-independent
`PcmMemoryRegion`, with the original `JavaSharedPcmMemory` wrapper and
`LegacyAshmemPcmMemory` for the exact legacy laboratory.

The legacy adapter creates bounded, non-purgeable MemoryFile ashmem, duplicates
its FileDescriptor into ParcelFileDescriptor, verifies actual ashmem size via
MemoryFile.getSize, and maps MAP_SHARED read/write through Android Os.mmap.
The API26 framework DirectByteBuffer constructor wraps this mapping; an atomic
unmapper prevents Cleaner/explicit double-munmap, and mapped slices are marked
inaccessible before unmap. No JNI/native source or transport was added.
Reflection here is an API26 compatibility boundary, not a cross-version hidden
API guarantee. [AOSP API26 MemoryFile](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/os/MemoryFile.java).

Pipe access-mode validation resolves the finite framework `Os.fcntlInt` method
reflectively: it exists on the observed API26 framework but became public in
the SDK at API30. This preserves the actual FIFO/direction checks without
suppressing lint's API-floor warning or introducing a generic hidden-API RPC.

Actual emulator checks cover creation, descriptor duplication, same-header
observation, wrong actual size rejection, writable cross-process mapping,
double-unmap/close and failure cleanup. Ring layout and notification generation
tests remain pure JVM tests; they are not treated as FD hardware evidence.

## Implementation And Finite Protocol

New main-side types:
`DirectRootSystemAudioCapability`, `DirectRootSystemAudioGateway`,
`RootAudioControl`, `RootAudioRequestCodec`, `RootAudioStatusError`, `RootPcmDescriptorLease`,
`PcmMemoryRegion`, `PcmRingProducerCore`, `PcmSignalEvidence`.
DEBUG types: `RootAudioBridge`, `DirectRootPcmHandoff`,
`DirectRootSystemAudioQualification`, `DirectRootAudioCaptureGateway`,
`DirectRootCompletePlanProbe`.

The runtime/helper identity becomes version4,
`warpnect-root-video-audio-input-v1`; outer authenticated framing is unchanged.
Finite Audio commands are PCM_RING_AUDIO_PREPARE, PCM_RING_AUDIO_START,
GET_PCM_RING_DIAGNOSTICS, PCM_RING_AUDIO_STOP and PCM_RING_CLOSE.
Each carries exactly24 bytes: magic WNRA, version1, positive Root generation
and positive Audio generation. Unsupported/malformed requests fail closed.
CAPABILITIES explicitly reports Video/Input/SystemAudio implemented, recovery
actions NONE. Implementation availability alone is not capability evidence.
The finite status classifier distinguishes unavailable API/format, construction,
initialization, start, read, descriptor/ring and stop failures. Unknown or malformed
responses fail closed; they are not all mislabeled as ring corruption.

The existing DEBUG provider adds only `open_root_pcm_descriptor_set_v1`.
It can issue only the currently staged memory/notify-write/ACK-read set, never
an arbitrary path/FD. A10-second single-use lease binds DIRECT_ROOT, UID0,
the authenticated helper PID, both generations, exact region size and a
domain-separated HMAC. The helper validates actual memory size/header and
FIFO descriptor direction. Old Shell methods remain strictly UID2000-only.

The foundation's32-byte generation secret, challenge, ordered requests,
replay/expiry checks, base.apk SHA-256 verification, owner EOF and bounded
lifetime remain. Secrets are consumed/deleted from the app-private temporary
handoff, never supplied in process arguments/logcat or stored in evidence.
Loopback TCP does not prove peer UID; the trusted laboratory launcher and
authenticated generation provide that boundary. The descriptor Binder call
does prove the framework caller UID/PID. No attestation claim is made.

## API26 AudioRecord And Attribution

Actual constructor: AudioRecord.Builder, REMOTE_SUBMIX source8, PCM16 format2,
48000Hz, CHANNEL_IN_STEREO12,2 channels. Min buffer observed5760 bytes;
configured11520 bytes (max of twice minimum and7680). Initialized state1,
recording state3; separate fresh session IDs per capture.

First exploration proved FD handoff/mapping but crashed in native AudioRecord
setup: bare app_process had a null current op-package. API26 forwards
ActivityThread.currentOpPackageName into native_setup, whose String16 path
dereferences it. That failure was retained, not reclassified as audio success.
[AOSP AudioRecord](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/media/java/android/media/AudioRecord.java),
[AOSP native setup](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/jni/android_media_AudioRecord.cpp).

The narrow correction initializes the real framework ActivityThread.systemMain
on the helper thread, then observes its framework-created Application/Context:
package=`android`, op-package=`android`, UID0. No manually fabricated Application,
Warpnect package attribution or blindly copied com.android.shell identity.
API26 has no modern AttributionSource. It neither grants UID1000 nor changes
SELinux. [AOSP ActivityThread](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/app/ActivityThread.java).

## Exploration Accounting

These runs are separate from the final frozen-artifact campaign:

1. APK `58f4d158ebce21b95e26b640499d6b2d3c08091298c4c9e688643fba33b19a0e`,
   helper2802, Root generation8319027585326391406:
   FAIL_AUDIORECORD_NATIVE_SETUP_NULL_OP_PACKAGE / native SIGSEGV. No PCM pass.
2. Corrected APK `6afcec30f890ec8d33ccbfd3c8cf79449407fde273c6b12812f45dd0e5b125e6`,
   helper3037, generation6503571419570938036: PASS. App silence96000 samples,
   RMS0; tone145440 samples,145422 nonzero, RMS2463.723, peak3485,
   997Hz amplitude3484.232. Captured168960/written-drained166560 frames,
   694 notify/ACK,10 deliberately induced bounded drops, HWM7, occupancy0.
3. APK `542f630e5e15f6af7561d0d9edf48c126bfc53346fd3848d55f49ee4aab66329`,
   helper3331, generation6636601623365570906:1/1 whole diagnostic cycle.
   Real same-generation Input, decoded Video A/B, Audio, cache-backed prepared
   Root-only plan, late revalidation, actual planner freeze, common audio
   controller and teardown passed. App RMS0 ->2463.711;158368 frames drained.
4. APK `838c566a0f5b256a0c43a3dade7007276769064b194ca250931bc4d9c13906b1`,
   helper4513, generation5704781100765855903:1/1 diagnostic cycle adding the
   unchanged downstream Opus smoke. App silence96000 samples/RMS0;
   tone143520 samples,143502 nonzero, RMS2463.728, peak3485,
   997Hz amplitude3484.245. Captured159744/written-drained157344 frames,
   notify/ACK656, drops10, HWM7, occupancy0. The existing common audio
   controller/Opus sink produced93 units/7440 bytes from22528 PCM frames,
   with0 encoder errors. This was before the final compatibility/status fix.

Diagnostic ktlint failures were style errors fixed before final gates. One
new cache test originally misclassified ZERO_PCM as a durable contradiction;
the existing policy was preserved. Transient capture failure blocks current
qualification/freeze without erasing stable cache; a known authoritative format
contradiction selectively invalidates only Audio. No hidden hardware retry.
The first final-gate invocation passed884 JVM tests and assembled both APKs,
but lint rejected two direct SDK calls to `Os.fcntlInt` as API30-only. That gate
failure is retained separately; the finite legacy resolution above and12 pure
status-classification tests were added before the second gate invocation.

The second gate run passed896 JVM tests and every requested gate. Its APK was
`40cc4edae8e3228119d128719e028c0c4ee858e217268e17255a3bd493e1b6b0`
(33624617 bytes). The first combined frozen-artifact campaign was **2/3 complete
cycles,2/2 SystemAudio attempts reached**. Cycle3 failed before Video/Audio at
`RESET_TARGET_NOT_OBSERVED` (helper6112, Root generation42010822051620141).
The target actually received all releases, but Android delivered TOUCH_CANCEL
before KEY_UP, contradicting the DEBUG fixture's assumed cross-source ordering.
The failure is retained in `root-audio-final-campaign.log` and
`root-audio-campaign1-cycle3-input.log`, not silently retried or blamed on Audio.

Only the DEBUG observer was corrected: ordinary event-prefix ordering remains
strict; reset requires every exact source-release event, with no assumed global
delivery order between different input sources. Eight pure tests cover both
observed release orders, missing/duplicate/extra releases, incorrect ordinary
order and disconnect release. Production injection/tracker/transport behavior
is unchanged. A new frozen-artifact campaign follows this bounded fixture fix.

## Security And Loss Evidence

The diagnostic artifact rejected wrong caller UID10065, wrong HMAC/provider,
stale Audio generation, replayed issuance, invalid magic/version/oversized
region metadata. A real corrupt V1 ring header was rejected with
record_constructed=false (helper3898, generation2547501929655646794).

Active recording/helper TERM: helper4048, generation4713715409496810125.
Runtime became HELPER_LOST; app observed runtime_ready=false, active=false,
pcm_lease=false with all three cached compatibility records retained. The
Root process death releases its AudioRecord/FDs; app monitor closes drain,
local pipe copies/mapping. No new helper or recovery attempt.

Active recording/app force-stop: helper4257, generation8267050381338266990.
Owner EOF at15:56:44.919 UTC; AudioRecord/memory/pipes/provider cleanup and
process_exit=0 at15:56:45.055. No indefinite capture. These are laboratory
loss tests, not physical OEM or hard-power-loss guarantees.

The exact final APK repeated these boundaries:

| Case | PID / Root generation | Observed result |
| --- | --- | --- |
| Corrupt V1 header | 8854 / 5953372391304560417 | REJECTED, record_constructed=false;1/1 |
| Active helper TERM, explicit state query | 9336 / 1203862000408642248 | HELPER_LOST; cache3 records, runtime_ready=false, active=false, pcm_lease=false;1/1 |
| Active owner app force-stop | 9582 / 4181420027050680401 | owner EOF19:08:18.152, Audio cleanup19:08:18.229, exit0 at19:08:18.236;1/1 |

Logs: `root-audio-final-invalid-ring.log`, `root-audio-final-helper-loss2.log`,
`root-audio-final-app-loss.log`. The TERM command guards the exact current
Warpnect helper generation/PID/UID; it never targets system or physical processes.
The app-loss Audio generation was3894230934763004522, session297, initialized1,
recording3. The measured84ms is EOF-to-exit, not an Android-wide timeout guarantee.

Two observer-only failures are also preserved: an initial timestamp-quoting
error stopped before any helper launch; a first final helper-loss observer
(PID9010/generation2883072875993839926) waited for an automatic state log that
the monitor does not emit. Cleanup was logged, but that observer timed out
(0/1 observation assertion; `root-audio-final-helper-loss.log`). The next bounded
run explicitly queried state and passed, rather than hiding the failed assertion.
No source or privileged behavior changed for these observer corrections.

Normal stop: stop AudioRecord, join producer, consume outstanding ACKs; app
continues draining, compares written/drained frames and final occupancy;
close helper AudioRecord/pipes/map/provider, release broker copies, close app
writer ends/drain/read ends/map/region, release capability selection, shutdown
Root helper. Original app mapping survives closure of helper copies. Double
stop/unmap/close is idempotent. New Root/Audio generations never reuse old leases.

## Final Campaign And Gates

Frozen final APK SHA-256:
`d3af77d3ed000b02bb6d0402cb15b4ffe161a428bc18bec176c5cfe79aaa43b6`;
33624617 bytes, versionCode1/versionName0.0.1, minSdk26 unchanged.
The corrected no-retry campaign passed **3/3**, including fresh same-generation
Input target delivery, offline decoded Video A/B, SystemAudio OFF/ON, actual
cached-plan preparation/late freeze and common audio controller/Opus smoke.
Evidence: `root-audio-final-campaign2.log` and
`root-audio-20260927-205721/cycle1.log` through `cycle3.log`.

| Cycle | Root PID / generation | Audio generation / session | Captured / written = drained frames | App bytes / wakeups | Tone samples / nonzero | RMS / 997Hz amplitude | Notify = ACK |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 6469 / 4252604514080451515 | 980528799761228473 / 161 | 160768 / 158368 | 633472 / 660 | 145440 / 145422 | 2463.755 / 3484.320 | 660 |
| 2 | 6948 / 1219541430662618643 | 2276049247209487521 / 185 | 159744 / 157344 | 629376 / 656 | 143520 / 143502 | 2463.728 / 3484.245 | 656 |
| 3 | 7422 / 9068024508613633892 | 4986243347704656313 / 209 | 158720 / 156320 | 625280 / 652 | 145440 / 145422 | 2463.721 / 3484.224 | 652 |

Every cycle: authenticated UID/GID0/0, Enforcing `u:r:su:s0`; initialized1 and
recording3 before reads;11520-byte buffer; observed framework package/op-package
android/android; silence96000 samples/RMS0; tone peak3485, clips0. The silence
window measures1s and tone approximately1.5s after settling. Metrics are from
normal-app PCM after the unchanged drain, not helper-local sample summaries.

The deliberate80ms consumer pause exercised bounded pressure:10 dropped chunks/
overruns, HWM7/8 and one corresponding frame-position discontinuity per cycle.
Captured minus written is2400 frames, exactly those10 chunks. Errors0; final
occupancy0; written equals drained; notify equals ACK. Ring wrap occurred many
times in8 slots. The V1 drain has no underrun counter: it blocks on notification,
so no fabricated underrun=0 metric is added. No buffering increase or tuning.

| Cycle | Existing controller PCM frames | Existing Opus units / bytes | Encoder errors | Actual plan freeze |
| --- | --- | --- | --- | --- |
| 1 | 22528 | 93 / 7440 | 0 | SINGLE / DIRECT_ROOT |
| 2 | 20480 | 85 / 6800 | 0 | SINGLE / DIRECT_ROOT |
| 3 | 22528 | 93 / 7440 | 0 | SINGLE / DIRECT_ROOT |

Video decoded center pixels were253/253/253 for fixtureA and0/0/254 for fixtureB
in every cycle. Input observed key/touch/pointer/joystick and all reset releases,
with active state0. All three current compatibility cells are **QUALIFIED for
this exact AOSP API26 laboratory**, not universally VALIDATED:
VIDEO=DIRECT_ROOT, SYSTEM_AUDIO=DIRECT_ROOT, INPUT=DIRECT_ROOT.

An additional isolated Audio resource campaign, with no Video/Input activity
fixtures or Opus smoke, passed **3/3** on the same exact final APK:

| Cycle | Root PID / generation | Audio generation | Captured / written = drained | App bytes | Notify = ACK | RMS / amplitude | Drops / HWM |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 7992 / 6588844090022286356 | 3220988300136712858 | 159744 / 156384 | 625536 | 652 | 2463.755 / 3484.320 | 14 / 7 |
| 2 | 8225 / 9190770712759678779 | 4667589618615435275 | 158720 / 156320 | 625280 | 652 | 2463.721 / 3484.224 | 10 / 7 |
| 3 | 8461 / 5822490525175610368 | 8112044831549624686 | 159744 / 157344 | 629376 | 656 | 2463.720 / 3484.223 | 10 / 7 |

Every isolated cycle still had silence96000 samples/RMS0, peak3485, clips0,
one bounded discontinuity, errors0 and occupancy0. Tone sample/nonzero counts
were145440/145422,145440/145422,143520/143502. The cold isolated cycle's14 drops
are retained, not tuned away. Evidence: `root-audio-resource-campaign.log` and
`root-audio-20260927-210033/cycle1.log` through `cycle3.log`.

Full fixture app FD samples were65/68/70 (PID6403); helper FD30-before/38-after
on each independent generation. This increase is not claimed as whole-app
leak-freedom or conclusively attributed to a particular graphics component.
The isolated audio campaign was stable: app FD60/60/60 (PID7821), helper
FD30-before/33-after in all three generations. API26 framework initialization
adds helper process resources; those disappear with each helper. The isolation
does not reproduce the full fixture's growth in the new Audio resource path.
PCM maps and leases were absent before shutdown, and helpers/listeners/private
bootstrap files were absent after every cycle. Indefinite or whole-graphics
soak leak-freedom remains unproven. No unrelated graphics refactor was made.

Final software run (`root-audio-final-gates3.log`): **904 JVM tests in145
suites,0 failures,0 errors,0 skipped**, up from841. All100 tasks executed in
7m10s. ktlintCheck, lintDebug, testDebugUnitTest with `--rerun-tasks`,
assembleDebug, assembleDebugAndroidTest and packageDirectShellProbeDex PASS.
Lint reports0 errors and121 warnings; existing Kotlin/deprecation warnings
remain, not concealed. `git diff --check` PASS.

The63 added cases cover finite Audio protocol/descriptor metadata and HMAC,
generation/order/replay, gateway state/release/loss, PCM fixture calculations,
original ring wrap/full/malformed reads/notify/ACK, scoped cache/prepared Root
plans, precise failure classification and the DEBUG cross-source reset observer.
Real API resolution, FD transfer, AudioRecord and target delivery are emulator
evidence, not mocked API evidence. Planner tests preserve Root-complete SINGLE,
missing Audio incompatible, Root Video/Input plus Shell Audio COMPOSITE and
unchanged preference among complete Root/Shell plans. A transient helper outage
preserves cache; durable contradictory format evidence invalidates only Audio.

PowerShell parsing PASS for RootLab, RootHelper, Test-RootVideo and Test-RootAudio
(4/4). RootHelper/Test-RootAudio reject a physical serial before any ADB call
(2/2). Native source is unchanged; native CTest is not rerun or claimed as a
new result. No native API26 adapter was necessary.

## Complete Plan Versus Product Host

The explicit laboratory probe uses actual current-generation observations for
all three cells and the real cached-plan builder, late evidence revalidation
and PrivilegedCapabilityPlanAdmission. It retains only the acquired Root runtime
and selects the three existing provider gateways after freeze. No synthetic
qualification, planner preference change or partial Host invariant.

Ordinary product Host UI/discovery/network Session is **NOT_TESTED** by that
probe. The product PrivilegedRuntimeResolver still has no production Root
bootstrap adapter; the AOSP Video qualification explicitly uses the existing
software encoder in DEBUG, whereas ordinary Host preflight requires a hardware
encoder. Neither invariant is bypassed to publish Host Available. Thus actual
Root-only planner/freeze evidence must not be promoted to complete product Host
admission or physical Root E2E. No remote Session/human audibility claim.

Cached records contain compatibility evidence for the exact fingerprint/ABI,
provider/backend/helper revision and LABORATORY_EXTERNAL_ROOT provenance.
They do not contain current Root liveness, AudioRecord, descriptors or secrets.
Warm process restart loads records but cannot make a missing helper READY.
With a matching live laboratory runtime the advisory builder can prepare
SINGLE/DIRECT_ROOT without starting AudioRecord, mirror or Input; freeze is late.

Audio routing: no physical local-audibility or duplication observation is
possible in the no-audio AVD. Routing remains UNKNOWN, not a product policy.
The fixture proves captured Android playback, not physical Client playback.

## Final Cleanup And Repository Inventory

Final explicit inspection found0 Warpnect Root helpers,0 PCM maps,0 private
request/secret files,0 listeners among20 observed Root control ports, and0
WarpnectCapture/qualification mirrors in SurfaceFlinger. App PID9698 had no
active Root Audio lease. Media resource-manager output contained only empty
historical PID entries/removal logs, not active codec client resources;
`media.codec` is not an observable service on this API26 image. All common
controller/encoder owners had closed. Indefinite global resource profiling is
not claimed. Bootstrap files/listeners/maps were separately checked every cycle.

Then `adb -s emulator-5556 unroot` restarted adbd as UID/GID2000/2000,
`u:r:shell:s0`; RootLab stopped the API26 AVD. API25/configuration/userdata were
not changed. No physical device was rooted, flashed, wiped, installed, or used
for a tone by this task. The optional non-Root physical admission regression
was NOT_TESTED; current A41/Tablet/S9/S7 connections do not themselves establish
a current-APK regression result. Automated common-path tests and gates pass.

The new slice changes41 source/test paths,9 documentation paths and2 tooling
paths, all uncommitted. DEBUG-only Root server/provider composition remains
explicit; normal Shizuku/DirectShell selection, Java SharedMemory/API27 behavior,
Input implementation, native source, protocol formats and planner ranking stay
unchanged. Common production changes are the narrow memory-ownership interface,
optional Root gateway selection, finite protocol/state and scoped evidence cells.

Main source under `app/src/main/java/io/warpnect/platform`:

```text
audio/capture/DirectRootSystemAudioCapability.kt [new]
audio/capture/DirectRootSystemAudioGateway.kt [new]
audio/capture/RootAudioControl.kt [new]
audio/capture/RootAudioStatusError.kt [new]
audio/capture/RootPcmDescriptorLease.kt [new]
audio/capture/shared/PcmMemoryRegion.kt [new]
audio/capture/shared/PcmRingProducerCore.kt [new]
audio/capture/shared/PcmSignalEvidence.kt [new]
audio/capture/AndroidSystemAudioCaptureController.kt
audio/capture/PrivilegedAudioCaptureGateway.kt
audio/capture/SharedPcmAudioDrain.kt
audio/capture/privileged/PrivilegedAudioCaptureBundles.kt
privileged/planning/AndroidHostPrivilegedCapabilityAdmission.kt
privileged/readiness/AndroidHostReadinessWork.kt
privileged/readiness/PersistentCapabilityEvidence.kt
privileged/runtime/DirectRootRuntime.kt
privileged/runtime/PrivilegedRuntime.kt [comment only]
```

DEBUG source under `app/src/debug/java/io/warpnect/debug`:

```text
directshell/DirectRootAudioCaptureGateway.kt [new]
directshell/DirectRootCompletePlanProbe.kt [new]
directshell/DirectRootPcmHandoff.kt [new]
directshell/DirectRootSystemAudioQualification.kt [new]
directshell/RootAudioBridge.kt [new]
directshell/DirectRootInputQualification.kt [observation only]
input/InputSessionTargetActivity.kt [observation only]
audio/SystemAudioE2eToneActivity.kt [optional continuous fixture phase]
directshell/DirectRootLabController.kt
directshell/DirectRootVideoQualification.kt [current Audio state telemetry]
directshell/DirectShellRuntimeAutoRegistrationProvider.kt
directshell/DirectShellVideoCaptureProbeContentProvider.kt
directshell/RootControlLease.java
directshell/RootVideoProviderClient.kt
directshell/WarpnectRootHelper.kt
```

Tests under `app/src/test/java/io/warpnect`:

```text
debug/input/InputResetObservationTest.kt [new]
platform/audio/capture/DirectRootSystemAudioGatewayTest.kt [new]
platform/audio/capture/RootAudioProtocolTest.kt [new]
platform/audio/capture/RootAudioStatusErrorTest.kt [new]
platform/audio/capture/shared/PcmRingProducerCoreTest.kt [new]
platform/audio/capture/shared/PcmSignalEvidenceTest.kt [new]
debug/directshell/RootControlLeaseTest.java
platform/privileged/planning/PrivilegedCapabilityPlannerTest.kt
platform/privileged/readiness/PersistentCapabilityEvidenceTest.kt
```

Tooling: `tools/root-lab/RootHelper.ps1` and new
`tools/root-lab/Test-RootAudio.ps1`. Documentation: this report,
`docs/phase6_5/{README,AndroidRootLab,DirectRootBootstrapFoundation,DirectRootInputQualification,DirectRootVideoQualification}.md`,
`docs/FutureRoadmap.md`,
`docs/rfc/RFC-006J-Privileged-Runtime-Orchestration-and-Host-Readiness.md`,
`docs/testing/AndroidCompatibilityMatrix.md` (AOSP section only).

Index is empty; ending HEAD remains Video checkpoint84f79dd, branch/master/
upstream unchanged. The pre-existing150 generated native entries, historical
untracked artifacts/screenshots/XML and line-ending-only InputCapture status
entry are preserved, not Audio changes. APKs/reports/logs/AVC/PNG/offline decoder
and temporary `root-audio-format.gradle` remain generated laboratory output.
Machine-local AVD/userdata/snapshots are not repository assets. No push/merge.

## Deferred And Security Boundaries

Production Root authorization / Magisk: NOT IMPLEMENTED.

Physical DirectRoot validation: NOT VALIDATED.

Root-assisted Runtime Remediation: NOT IMPLEMENTED.

Same-provider Recovery: NOT IMPLEMENTED.

Live Provider Migration: NOT IMPLEMENTED.

No arbitrary Root shell, generic Binder RPC, arbitrary FD transfer/object
deserialization, PCM over control TCP, audio-policy mutation, Magisk claim,
physical Root claim or remediation. No production dependency on desktop ADB.
No Audio Payload, Opus algorithm, SCL, Session, WNCP or native source change.

Reproduce with the existing guarded RootLab/RootHelper tooling and
`tools/root-lab/Test-RootAudio.ps1 -Serial emulator-5556 -Cycles 3`.
Supply `-FfmpegPath` to a local offline decoder observer to also qualify actual
Video A/B and Input on each generation and exercise the real complete plan.
No retries, implicit wipe, physical Root operation or downloaded observer is
performed by that script. Generated evidence remains under app/build/reports.

Decision: **DIRECTROOT_SYSTEMAUDIO_CAPABILITY_VALIDATED_IN_AOSP_LAB**.

The exact lab now has a complete qualified Root capability set and real frozen
Root-only plans, but not validated ordinary Root Host admission. The stronger
Host-admission token is deliberately not used. Next recommended task, not
implemented here: **Physical Root Device Qualification**, to establish the
OEM/root-manager/audio-HAL/codec/SELinux boundary before generalizing this lab
evidence or claiming production Root readiness.

## Normal Host Admission Follow-up

This validated SystemAudio slice was checkpointed locally as `022ba4d` before
the next task. `DirectRootHostAdmissionValidation.md` closes the previously
unvalidated normal application Enable Host boundary: eight consecutive normal
Enable/Disable Host passes on its exact APK,8/9 counted attempts with an
inconclusive ADB observation failure before the ninth acquisition, restart1/1
and controlled helper-loss cases2/2. No retry rewrites the original accounting.

Host admission uses the existing Root audio gateway, PCM Ring V1, controller
and drain; silent PCM progression verifies active ownership before freeze.
No tone or new signal qualification is credited to that admission campaign.
All three cached cells remain byte-identical, QUALIFIED for the exact lab;
liveness and capture stay volatile. After late authenticated acquisition,
SINGLE/DIRECT_ROOT freeze and normal controller start, Host Available and LAN
registration are observed. This now supports the bounded normal Host milestone,
not Magisk, physical Root, peer discovery reachability or remote Root E2E.
The follow-up passes931 JVM tests and all software gates; production Root
authorization, remediation, recovery and migration remain deferred.
