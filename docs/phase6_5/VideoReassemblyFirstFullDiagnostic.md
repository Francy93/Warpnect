# Video Reassembly First-Full Diagnostic

2026-10-25. Isolated branch `codex/phase6-5-video-home-reopen`.
Software instrumentation only; no ADB operation, installation, audio tone,
physical campaign, production recovery fix or integration into another branch.

**VIDEO_FIRST_FULL_DIAGNOSTIC_SOFTWARE_VALIDATED**, for the exact software gates
and compile-time boundaries below. Hardware cause attribution remains PARTIAL.

## Scope And Checkpoints

Frozen G1-G5: `656c3783ba3f120f2e8b911b2ec3c932ca1b5df3`.
Test/documentation checkpoint:
**`f0c6d87bbc00d7eb7ffd73bfa960f7d4092070eb`**,
`test(video): characterize protected HOME-reopen window pressure`.
Its seven files match the previous verified manifest byte for byte before
staging;1159 JVM,27/27 native each and integrated Android gate receipts are
preserved at `C:/wv-home-r1594`. A focused protected replay and diff check were
repeated before this expressly authorized local checkpoint. No production code
was included. The previous report's no-commit statements describe that earlier
task, not a denial of this subsequent checkpoint.

Diagnostic source/test/guard checkpoint:
**`142e467c0616ccbb3153bda9ea020bc4c345c62f`**,
`chore(video): add bounded opt-in first-full diagnostics`, seven dependency-
complete files, parentf0c6d87. This report/index closure is a separate local
documentation checkpoint above it; no uncommitted source is a build dependency.
Raw receipts remain outside Git at
`C:/wv-first-full-r1595`; no generated/private evidence is commit content.

Historical inputs remain read-only in their original worktrees:
`FrozenG1G5HardwareRegression.md`, `HardwareRestorationAndFailureReconciliation.md`
on the hardware-report branch, r1460 and successor reports in the research tree,
and [the deterministic replay](VideoHomeReopenDeterministicReplay.md) here.
No hardware failure is reclassified as fixed by a software observer.

## Why This Recorder

The existing Android first-eight `event=video_window_full` logs give aggregate
complete/incomplete/ready counts but not slot identities, ages or bitmaps.
G5's generic diagnostic event buffer uses a mutex and approximately128KiB,
and its audio flight store is approximately1MiB with different ownership.
Neither belongs in this bounded receiver path. The implementation reuses G5's
explicit property opt-in, compile-time exclusion, private-cache deferred-export
pattern and existing isolation guard, not either unsuitable hot-path store.
No general tracing framework, protocol field or diagnostic RPC is added.

## Source Manifest

| Path | Change |
| --- | --- |
|`app/src/main/cpp/include/video_first_full_diagnostic.h`|DEBUG-only fixed metadata/layout, Session claim, capture and deferred text serialization |
|`app/src/main/cpp/include/video_receiver_runtime.h`|DEBUG-only non-owning pointer and attachment/capture helpers |
|`app/src/main/cpp/src/video_receiver_runtime.cpp`|Observations at existing allocation, fragment, completion, release, expiry and first-full boundaries |
|`app/src/main/cpp/src/internal/video_first_full_android_debug.h`|Android DEBUG property gate and deferred exclusive-create export |
|`app/src/main/cpp/jni_bridge.cpp`|Cold Session/receiver ownership, existing protector result observation, detach/export during destruction |
|`native/tests/scl_video_home_reopen_tests.cpp`|Protected recorder tests, equivalence, bounds, allocation controls and host cost replays |
|`tools/hardware/VerifyAudioDiagnosticIsolation.ps1`|Optional `-IncludeVideoFirstFull`, receiver source/header checks and new marker exclusion |
|This report, Phase6.5 README/debt, FutureRoadmap and replay successor pointer|Scope, verification and future hardware discriminator |

No Kotlin, Android manifest, dependency, Android CMake linkage, public native/JNI
ABI version, wire format, crypto implementation or accepted RFC changes.
[RFC-002F](../rfc/RFC-002F-End-to-End-Video-Streaming.md) and
[RFC-002G](../rfc/RFC-002G-Video-Latency-Recovery-Performance-Tuning.md) remain
the allocation/error/recovery contracts. The release helper macro expands to
the same original `reset_slot(slot)` calls, not a new recovery path.

## Enablement, Ownership And Export

- Default **OFF**. Only exact Android property
  `debug.warpnect.video_first_full=1` at cold receiver creation requests a recorder.
  No settings are written by the product or this task. Other/missing values do
  not enable it; Release contains no reader or implementation.
- Requires an existing authenticated protection owner and at most8 reassembly
  and8 ready slots. Larger configurations are not scanned or resized; the
  observation fails closed without changing the receiver result.
- The existing Session protection-state mutex guards a one-time cold claim.
  One receiver handle owns the record allocation; its runtime/protector borrow
  pointers. A second receiver in the same Session cannot rearm it, even if the
  first closed before triggering or allocation failed. A new Session owns a
  new claim and generation. This deliberately favors boundedness over coverage.
- All runtime/protector/decoder observations are serialized by the existing
  receiver handle mutex. No additional receive lock, thread, atomic wait, I/O,
  text formatting or heap allocation is introduced in these hooks.
- The first allocation failure appends its metadata and freezes the recorder
  before the existing full counter/error handling. Eight real slot/bitmap
  views are copied once. Later failures, genuine completion, expiry, rebind,
  close and rejected input cannot change the frozen bytes.
- `nativeVideoReceiverDestroy` uses the existing close/lifetime contract:
  worker and codec users must be quiescent, runtime closes under its handle
  mutex, both borrowers detach, the mutex is released, then export runs.
  There is no destructor export, polling RPC or file write during capture.
- One private path per Session/receiver owner:
  `/data/data/io.warpnect/cache/video-first-full-<pid>-<tag>-<generation>.txt`.
  Exclusive `wx` creation prevents overwriting previous evidence. Missing full
  means no export. Failure to open/write is reported only at deferred finish,
  without retry or media-state mutation. A process crash or missing destroy
  can lose an in-memory snapshot; durability is not guaranteed by this design.

The new observer does not remove the pre-existing bounded DEBUG rejection/full
logs; those remain baseline overhead. It adds no synchronous receive logging.
Export uses libc file I/O only after quiescence; libc/OS file bookkeeping is not
the persistent recorder budget and is not a real-time operation. Future manual
campaigns must also bound file retention; this is not an unlimited-session disk
quota manager.

## Layout And Bounds

| Stored state | Bytes |
| --- | ---: |
|Snapshot header|88 |
|Eight slot records,328 each including256-byte bitmap|2624 |
|64 ingress records,48 each|3072 |
|Recorder counters/current envelope/frozen state and padding|24 |
|**Recorder total**|**5808** |

`static_assert(sizeof(VideoFirstFullDiagnostic)+128 <=8192)` reserves room for
borrower/owner pointers and cold Session claim/tag/generation bookkeeping.
Arrays are zeroed during one cold `new(nothrow)`, never resized. Disabled
receivers have only DEBUG pointer/cold bookkeeping, not allocated arrays.
No instrumentation-owned C++ allocation occurs during record/capture; calibrated
allocation tests cover appends and the actual protected first-full path.
The guard does not claim total application RSS, allocator internals or every
pre-existing crypto allocation is zero.

All scans are bounded by8 slots; bitmap copy is at most256 bytes per slot.
History overwrites oldest records after64, with total/overwritten counts retained.
Each bitmap separately reports required/captured bytes and a truncation bit.
For example3000 fragments require375 bytes; only256 are captured and truncation
is explicit. A truncated bitmap is never evidence that all fragments are known.
Text export loops over at most8 slots/64 events and is tested below32KiB.

Header: non-secret Session tag/generation/channel, local admission timestamp,
last performed expiry-scan timestamp, effective recovery deadline, active/latest
codec generations, configured slot/ready counts, awaiting-keyframe/pending event,
timeout/discontinuity/full counters. The full count includes the impending first
increment; capturing does not itself increment any product counter.

Slot: exact protocol/group flags/type/base sequence/remote timestamp order key,
local first observation and age, expected/received counts, first-arrival and
last-accepted fragment sequences, bitmap, complete/queued/parsed/keyframe flags,
already parsed frame/config identity, allocation serial, previous release time
and reason. Incomplete payloads are **not parsed again** merely to manufacture
frame IDs. A zero field without its parsed/present validity bit is unavailable.

State bits: present1, complete2, queued4, parsed identity8, keyframe16,
bitmap truncated32, invalid/backward local-age reference64.
Release reasons: invalid payload1, configuration2, generation3, keyframe gate4,
delivered5, unconfigured eviction6, expired7, close8.

History kinds: authenticated envelope1, ReplayDuplicate2, ReplayTooOld3, other
protection rejection4, offered inner fragment5, known inner duplicate6, inner
TooOld7, selected allocation8, completion9, expiration10, release11, allocation
failure12. A selected slot precedes actual fragment acceptance; the frozen
bitmap/count/present fields are the authority on actual ownership.

Envelope packet number/epoch correlate successful protection with inner input.
Rejected envelope identifiers are explicitly **unverified** (event flag4), not
new authenticated data or a known AU. There is no decrypt retry or inferred
plaintext identity. FEC-recovered input uses flag2/no inherited packet counter;
new authenticated inner input uses flag1. Protection result observation does
not alter replay state, error mapping or authenticated liveness.

## Clock And Observability Limits

All stored local times are SCL **MONOTONIC microseconds**. Ingress/first-full
timestamps are the existing `accept_datagram(now_us)` admission reference,
sampled before decrypt, not a new end-of-auth or exact end-of-snapshot timestamp.
Release observations sample the same monotonic clock only when recording is
enabled and not frozen. First-fragment age is computed from receiver-local
observation, never remote media PTS. The remote group timestamp is an order key,
not a local render deadline.

`last_expiry_scan_us=0` means no recorded scan, not proof of a timer defect.
Pump checks pending ready events before scanning; receive wait/processing can
also separate a scan from a later admission. The snapshot records those inputs
rather than pretending expiry is an interrupt.

Android authenticated liveness still uses BOOTTIME in the existing protector;
it is not converted, subtracted or refreshed by the observer. No cross-device
clock subtraction, RF timestamp, sender provenance or scheduling cause is
invented from this metadata.

Unavailable without new coupling: Kotlin Surface generation/lifecycle state
(export explicitly says UNAVAILABLE), unparsed partial-AU frame/config identity,
rejected inner payload identity, and overwritten ingress history. Existing
Surface/log/lifecycle evidence must be retained beside the native snapshot.
These limitations do not justify another JNI/public diagnostic interface.

## Software Verification

- Original protected cases remain: pre-expiry hole/full/replay/completion,
  never-arriving fragment/expiry/keyframe, ordered2..9 drain, late-tail recreation,
  consumed duplicate suppression and actual Session-generation rejection.
- New DEBUG groups verify exact first-full identities/bitmap/age/header;
  rejected replay does not refresh liveness; a second full remains an error
  while frozen bytes are unchanged through completion/close.
- ON before-deadline completion preserves ordered drain without a snapshot.
  ON late-tail recreation records prior Expired release, allocation reuse,
  bitmap02 (missing head), timeout1 and last expiry scan, unlike timeout0.
- Resealing the same inner datagram with a fresh valid protection counter
  exercises known inner-duplicate suppression, not only outer replay rejection.
  Advancing the real protection window exercises actual ReplayTooOld.
- New authenticated generations have distinct recorder ownership. Cold claim
  cannot be reused within a Session. Greater-than-eight windows fail closed.
  Bitmap/history truncation, FEC provenance flags, bounded export and a positive
  allocation-counter calibration are tested.
- OFF/ON compares the same product errors, timeout, occupancy, keyframe/drop,
  delivery and protection results. Existing JVM tests exercise lifecycle/core
  and Surface races; no Kotlin lifecycle behavior changes.

Native test count stays27: cases extend the existing protected replay executable,
not add a target. DEBUG runs the recorder groups; Release runs the five functional
groups with recorder types/code excluded. JVM stays1159/178 suites with0 failures,
errors or skips; all26 prerequisite tasks were explicitly rerun (3m08s).
G5 flight/output replays remain40/40. Final native27/27 both configurations pass.

The optional G5 isolation guard compares JNI, all three audio TUs, receiver TU
and receiver header against656c378 after `NDEBUG` preprocessing. Its whitespace-
normalized source identities match and new markers are absent. Including the
header as its own comparison prevents hiding a layout delta behind current
includes. ABI-wide symbol/string and Android build results are recorded below.
There is no assertion that DEBUG OFF is instruction-identical:
it adds nullable checks/helper calls, but no recorder clocks/storage/I/O.

## Overhead Boundary

Host replay modes cover OFF clock control, ON appends, frozen ON, one8-slot
capture, and actual protected admission plus decoder fill with OFF/ON/frozen/OFF
controls. Frame generation/protection setup and cold zeroing are outside timed
windows. Allocation tests are calibrated for C++ `new`, not libc/OS internals.

Initial loaded-host measurements and their repeat are retained, not discarded.
The disabled RX control itself varies appreciably; do not subtract percentiles
as a causal estimate or call a quantized0ns result free. Direct live hooks and
capture do have measurable cost. One final post-build bounded run gives the
following values (microseconds, MSVC DEBUG, QPC clock):

| Host measurement | p50 | p95 | observed maximum |
| --- | ---: | ---: | ---: |
|Append, ON without trigger|0.1|0.1|0.3 |
|OFF clock/control branch|0|0.1|0.1 |
|Frozen ON append check|0|0.1|0.2 |
|First capture,8 slots/2KiB bitmap cap|3.3|3.4|23.7 |
|All live hooks for one synthetic single-fragment AU|1.4|1.8|4.2 |
|Actual protected RX+fill, OFF initial|11.8|13.3|47.8 |
|Actual protected RX+fill, live ON|13.3|13.8|48.5 |
|Actual protected RX+fill, frozen ON|12.1|13.4|48.9 |
|Actual protected RX+fill, OFF final control|11.9|13.2|44.3 |

Append/control/capture use10000 observations each; combined hooks/RX use1000.
The stable final OFF medians bracket the live ON increase at roughly1.4-1.5us
for this tiny host replay. This is measurable overhead, not zero and not an
Android per-packet bound. Earlier concurrent-build OFF medians15.1/23.9us show
why the loaded-run subtraction was inconclusive. No production timing policy
is changed to make these measurements pass. There is no hard real-time claim
from an observed maximum. These are host costs, **not**
Android receive latency, callback latency, WCET, or enabled-observer qualification.
No device timing or xrun improvement is claimed. Any enabled hardware pressure
must be compared against OFF and investigated before causal attribution.

## Future Hardware Discriminator (Not Authorized Here)

1. Freeze the diagnostic commit, APK/helper hashes, ABI/default selectors and
   known source manifest. Inventory/restorable original app/test APKs, data/trust,
   properties, volume and network; require safe Package Manager state. Do not
   repeat stalled installs or modify pairing/developer/network settings silently.
2. Use the historical supported A41 Host -> S9 Client path. Preserve the current
   LAN rather than beginning an RF investigation. No new tone is needed for this
   Video defect. Record actual negotiated Video/audio/Input channels on both
   peers before measurement; an apparently false checkbox is not proof.
3. Predeclare at most one short OFF control (30s) and one ON Session (90s,
   maximum three HOME/REOPEN cycles, one Host enable). Restore original property
   values between control/cleanup as needed. No counted retry around failure.
   Budget artifact retention in advance; at most one frozen snapshot per Session.
4. Keep existing receiver/Surface/configuration/decoder logs and actual visible
   scenes with frame/input progression. At the first trustworthy full/error,
   stop stimulus, retain logs, then perform controlled Disconnect/close so the
   one frozen snapshot can export. Confirm its saved status, Session/generation,
   monotonic time, counters and actual slot identities before interpreting it.
5. Required discriminator: genuine missing bitmap and age below deadline vs
   expired/recreated group; previous release/scan vs retained ownership; known
   inner/outer duplicate vs unseen fragment; current vs obsolete generation.
   A rejected envelope alone cannot supply the missing AU or a network cause.
6. Compare observer OFF/ON receive/decode/CPU and visual timing with available
   telemetry. Investigate measurable disturbance; do not dismiss it as DEBUG.
   Capture must remain one-shot/bounded and product errors unchanged. No new
   unexpected state, allocation, helper, publication or teardown leak is allowed.
7. If no trigger within budget, report NOT_REPRODUCED, not fixed. Otherwise report
   the first boundary and only then propose a contract-grounded failing replay/fix.
8. Disable probe, close owned helpers, restore original app/test APKs without
   clearing data, verify installed hashes, restore properties/volume/Session
   state and verify Ready/Host disabled/trust preservation. On a pending install,
   stop mutations and retain state; no automatic reboot or maintenance escalation.

The software observer is not an authorization for those installations or tests.
No production recovery correction, larger window, changed deadline, FEC policy,
keyframe behavior, audio parameter or anti-replay concession is proposed.

## Preservation And Completion

This task does not access phones: the prior verified A41/S9 restoration receipt
remains its own evidence, not a new device-state assertion. Original research,
G1-G4/G1-G5 branches, hardware evidence and Phase7 R2 are preservation targets.
No push/merge, Root/Shizuku/provider work, Phase7 S0 or audio optimization.

Final gate/artifact and preservation receipts follow. Instrumentation is
IMPLEMENTED and SOFTWARE TESTED/VALIDATED in that scope, not newly
HARDWARE_VALIDATED, QUALIFIED or PRODUCTION-READY. Historical Video root-cause
attribution and G1-G5 hardware acceptance remain PARTIAL.

## Final Gate Receipt

| Gate | Result |
| --- | --- |
|Full JVM, explicit rerun|1159/1159,178 suites,0 failed/error/skipped;3m08s |
|Video lifecycle JVM|Existing9/9 included, Surface/core behavior unchanged |
|CTest Debug / Release|27/27 each; unchanged target count, expanded DEBUG cases |
|G3/native receiver/security regressions|PASS in both native configurations |
|Flight/output replay|40/40 PASS |
|ktlintCheck / lintDebug|PASS;0 lint errors,134 existing warnings, no suppression |
|Debug APK / AndroidTest / helper|PASS; no installation |
|Unsigned Release APK / native3 ABI build|PASS; not device or release-policy qualification |
|Integrated Android gate run|10m22s,150 tasks:67 executed/83 up-to-date |
|NDEBUG source/header isolation|6 files per ABI,18 comparisons PASS against656c378 |
|Compiled symbol/string isolation|Each DEBUG ABI45 matching symbols/2 required marker strings; each Release ABI0/0 |
|JNI surface|All101 existing entrypoint names unchanged |
|clang-format new headers/test, diff check|PASS |

Counts: frozen G1-G5 had1155 JVM/26 CTest targets; f0c6d87 added four JVM tests
and one protected executable, reaching1159/27. This delta adds six DEBUG groups
inside that executable (11 total groups,5 functional in Release), not another
CTest target or JVM class. The Release executable has no recorder code to enable.
Compiler/Gradle/SDK and pre-existing third-party/unused-field warnings remain
visible in logs. No assertion or warning was suppressed.

Native `NDEBUG` preprocessing is whitespace-normalized equivalence, not a claim
that independently linked APKs have identical bytes. LLVM nm/strings checks use
the actual unstripped `libscl_core.so` for arm64-v8a,armeabi-v7a,x86_64 in Debug
and RelWithDebInfo/Release. Both symbol absence and marker absence have DEBUG
positive controls. Receipts: `isolation-*.log`, `symbol-isolation.json`, full
symbol/string files, `jvm-counts.json`, `ctest-final-02-*.log`, `g5-replays.log`,
`observer-cost-post-build.log`, `android-gates.log` and post-commit build receipt.

Reproduce with JDK21, pinned NDK27.0.12077973/SDK, VS2022 x64 native project:

```powershell
cmake -S native -B <external-native-build> -G "Visual Studio 17 2022" -A x64
cmake --build <external-native-build> --config Debug --parallel 2
ctest --test-dir <external-native-build> -C Debug --output-on-failure
cmake --build <external-native-build> --config Release --parallel 2
ctest --test-dir <external-native-build> -C Release --output-on-failure
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --max-workers=2
.\gradlew.bat :app:ktlintCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex :app:assembleRelease --max-workers=2
.\tools\hardware\VerifyAudioDiagnosticIsolation.ps1 -CompileCommands <ABI-compile_commands.json> -OutputDirectory <external-output> -BaseRef 656c378 -IncludeVideoFirstFull
node --test tools/hardware/SystemAudioFlight.test.cjs tools/hardware/SystemAudioOutputContract.test.cjs
```

No source from the dirty research tree is a build dependency. Preservation
checks pass for492 file hashes/four metadata-only private entries,496 original
paths/index, clean G1-G4/G1-G5 identities and all six hardware-report hashes.
Phase7 R2 SHA remains
`0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6`.
No historical failure/evidence was overwritten. Source checkpoint142e467 and
the subsequent report-only checkpoint remain isolated and unpushed.

## Frozen Build Identities

Source142e467 was followed by a successful artifact refresh (11s,131 tasks,
13 executed/118 up-to-date); source/test/guard diff against that commit is empty.
All artifacts below are **NOT INSTALLED** and are independently identified,
not assigned the old0297F044 hardware result:

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
|Debug APK|37320936|59D91E62B0D43617D34B1240236377260D1CBDB4F5F6E42D780F316BB71B38E2 |
|AndroidTest APK|1237860|0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F |
|Helper DEX JAR|13287435|002DD42AF5954BDF71300A33CA25DA1631848CB48CDBBB247D9433913D23D3AC |
|Unsigned Release APK|22467163|0DD94F515794EE7766E8702FA84FFBF3D3D16C001BDD104C03C68E7D97D3EF49 |

Receipts: `C:/wv-first-full-r1595/artifacts-final.json`,
`post-source-commit-build.log`, source manifest and final Git receipt. The Debug
APK is the proposed future hardware candidate with property OFF by default;
changing that property for a separately authorized observation does not change
its binary identity. Exact preflight/rollback remain mandatory before use.

Completion is software only: **VIDEO_FIRST_FULL_DIAGNOSTIC_SOFTWARE_VALIDATED**.
No hardware root cause, recovery correction, Device qualification, Release
promotion or Phase6.5 exit follows. The one next task is the separately authorized
bounded A41 -> S9 first-full discriminator described above.
