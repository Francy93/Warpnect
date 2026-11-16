# DirectShell Functional Consolidation G1-G4

Date:2026-10-25. This report concerns an isolated functional projection, not a
merge into the development worktree or a new device campaign. The final decision
below follows completed post-commit integrated software verification.

## Repository And Preservation

Original branch: codex/directshell-production-runtime-integration.
Original HEAD:3253518088efcbfebc5cd8b60f2ff9d606e0a149.
master:032b59276a0e01e4674e807a8ffb5b5b86e9150e.
Recorded upstream:9d0b4dce7ea04a82e81778de6296cc1096775d31;31 ahead/0 behind,
without fetch, push or branch rewrite. Original index was empty,85 tracked files
modified and411 expanded untracked paths,496 paths in all.

Dedicated branch: codex/phase6-5-functional-consolidation.
Dedicated worktree: C:/Users/Francy/.codex/worktrees/phase6-5-functional-consolidation/Warpnect.
Original worktree: C:/Users/Francy/Desktop/Warpnect.
The original index hash is
32F56FBB16793979C701251D46D5EA87665AD6B8C669143F41B6F3083614CE5B.
Final preservation check: same branch/HEAD/master/upstream, empty index,85/411
statuses, zero path/status differences.492 original files match SHA256; four
privacy/large-file inventory entries match size/UTC metadata (not a new claim
of initial content hashes for those four). The real index hash above is intact.
Phase7 R2 SHA256 remains
0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6.

An immutable source/test/doc snapshot and exact patch, raw SHA256/Git-blob,
base/result-commit and command receipts are outside both source worktrees at
C:/wg14-artifacts-r1590.203 initial source/test/doc files were copied after
identity checks for review; this was not an automatic staging list. Historical
ignored evidence, private files, reports, generated packages and RF diagnostics
were not deleted or staged. The Phase7 R2 and its planning stay unchanged in
the original worktree; they are not imported into this functional branch.

## Checkpoints

| Group | Commit | Exact checkpoint scope | Software result |
| --- | --- | --- | --- |
| G1 |ad1d5f922b586ee0c2b05ccfaed1fc2c26517a4e|3 frozen files: JNI BOOTTIME receive, protected receive instrumentation, focused report |COMMITTED |
| G2 |f533117b57243336c8542d8de39c231a131f15e2|19 reviewed source/test/doc files; authenticated reply retention and Session/closing ownership |COMMITTED |
| G3 |1aec9fb14df0fd42ef937121e2cd977eb0bd5193|Video sender .h/.cpp, real-UDP resync tests, focused report |COMMITTED |
| G4 |1cce23043fca45fa0303e4e58a55b646e7eab7c4|68 reviewed files:45 functional/retained helper dependencies,20 tests,2 CMake files,1 scope report |COMMITTED |

Each commit's exact manifest is also independently available with
`git show --name-status <commit>` and `git show --binary --full-index <commit>`.
The external G1-G4 manifest/patch mirrors are verification artifacts, not build
dependencies. No file remaining only in the dirty worktree is a compile input.
The four functional commits change93 unique paths versus3253518:59 functional
source/retained DEBUG dependencies,28 test files,2 build files and4 focused
checkpoint reports. A subsequent five-file report/index receipt changes docs
only; it is not G5 and does not change the verified production source.
The three actual Android compile databases contain340 unique source files,
all tracked in this worktree, zero external/snapshot source and zero untracked
compile input. Native CMAKE_HOME_DIRECTORY also names this worktree. Normal
SDK/NDK, Gradle dependency/cache and generated build outputs are toolchain inputs,
not hidden source dependencies on the development checkout.

Focused reports: [G1 clock](DirectShellProtectedReceiveClockCheckpoint.md),
[G2 ownership](DirectShellControlOwnershipCheckpoint.md),
[G3 configuration](DirectShellVideoConfigurationCheckpoint.md),
[G4 continuity](DirectShellMicrophoneContinuityCheckpoint.md). G1's frozen report
keeps its historical approval/stop wording; the later G1-G4 user authorization
governs this run. No frozen G1 report contents were rewritten.

G1 uses the frozen patch SHA256
0C34D335436413ACB464E44091B8FABB27CD338BF9030B5310A4C1B61B2CB3EB.
Its raw and Git content identities were checked before staging. Existing frozen
source-exact gates (1043 JVM,22+22 CTest,57 Android adapter and33 non-Android
checks plus Android gates) were reused, not represented as a new device run.
The57/33 executables were rerun after G1 commit. The G4 JNI projection preserves
G1's clock/unprotect block identically and preserves the instrumentation source.
Replay rejection still does not refresh authenticated liveness. Media pacing,
wire time, cryptography, anti-replay and non-Android clock behavior are unchanged.

G2 full rerun:1055/1055 JVM,166 suites, ktlint, lint, DEBUG/AndroidTest APK and
helper DEX PASS. Post-commit targeted control/security/lifecycle/input/audio
suite:70/70 PASS. Native production source is unchanged by G2. Its generic
closing hook compiles independently; G4 supplies actual microphone ingress
revocation. This integration edge was documented rather than importing G4 early.

G3 clean native configure/build: Debug22/22 and Release22/22 PASS; Android APK
build PASS. Post-commit Video/protection target suite4/4 PASS. Twenty early
requests coalesce into one same-codec-generation resend; teardown clears pending
ownership. Receiver_config_generation is an advisory resync hint, not a new
Session credential. Existing malformed/protected-generation admission remains.
Already committed receiver/View/sender fixes are reused, not duplicated.

G4 is one coherent integration checkpoint with internal A/B/C ownership:
compatibility/policy authority; app-owned destination/control/lifetime; native
admission/clock/priority/priming. Generic API26/package and API31/attribution
adapters never grant authority. The selected DirectShell source uses ordinary
app-owned public NDK output for PCM and authenticated helper control for policy
identity/windows. No Root/Shizuku fallback or planner change is present.

Microphone queue is bounded at1920 frames/40ms, separately from80/70ms DEBUG
playout. Missing-ingress replacement/maintenance silence is explicitly counted,
not reported as original source. No-consumer, stale, pre-window and generation
ownership remain distinct. Existing scoped priority/refcounts and Kotlin/JNI
lifetime leases accompany direct admission and independent numeric SDK sampling.
The200us callback-credit recheck and age-bounded70ms priming are retained without
a larger buffer or freshness budget. See the focused G4 report for exact scope.

The old authenticated helper-feed commands and their feed/result/destination/
totals/native-clock classes remain legacy compile dependencies. Their bounded
microphone numeric accounting is explicit DEBUG helper code, not a selected
factory path or another provider. Optional protection/ring trace wrappers,
Flight Recorder, output/backend selectors, fault injection, RF tools and
synthetic app-output probes are excluded. No hidden G5 dependency is required.

## Integrated Gates

Final post-G4 Gradle rerun: PASS,100/100 tasks executed,12m50s, exit0 on
1cce23043fca45fa0303e4e58a55b646e7eab7c4. All required Android gates were run
together with --rerun-tasks, not inferred from the dirty-cohort audit.

| Integrated gate | Final result |
| --- | --- |
| git diff --check, including complete3253518..G4 diff |PASS |
| :app:ktlintCheck |PASS |
| :app:lintDebug |PASS,0 errors /132 warnings |
| :app:testDebugUnitTest |1152/1152 PASS,177 suites,0 failures/errors/skips |
| :app:assembleDebug |PASS,arm64-v8a/armeabi-v7a/x86_64 |
| :app:assembleDebugAndroidTest |PASS,build only |
| :app:packageDirectShellProbeDex |PASS |
| Native configure/build and CTest Debug /Release |PASS,24/24 each |
| G2-G4 raw-file/Git-blob manifests |91 file instances match committed content |
| Android native source dependency check |340 tracked sources,3 databases,0 external/untracked source inputs |

Post-commit native CMake configure/build and CTest PASS: Debug24/24 (3.46s),
Release24/24 (3.04s), zero failures. Both runs use the committed G4 source and
retain the complete security/control/Video/audio/input/telemetry suites.

G4 pre-commit full JVM1152/1152 in177 suites, zero failures/errors/skips, ktlint
PASS; native Debug24/24 and Release24/24 PASS. Queue/SDK-clock replay88 checks,
scoped-priority replay11 checks PASS. The post-commit full rerun above independently
confirms the final source cohort. AndroidTest is built only, never installed or
executed here. No connected-instrumentation or human-audibility result is claimed.

| Built artifact (not installed) | SHA256 |
| --- | --- |
| Debug APK,34170977 bytes |F15CED783B24D4ED2910DF0F6935EEE1FB21BE34A2A153AC48CB822849307FAB |
| AndroidTest APK,1237796 bytes |B29F9F454FD677863532D35CDF5C61E29A1E58734DED5135CAADAE4E68304675 |
| Helper DEX JAR,13285082 bytes |1B6AD373A8B3C01090D51CBDB38A12C878117AB324B37750BC87D16AD9607F06 |

Reproduction uses only the checked-out source and normal pinned toolchain:

```text
git diff --check
gradlew.bat --no-daemon --max-workers=2 :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex --rerun-tasks --console=plain
cmake -S native -B <fresh-build-directory>
cmake --build <fresh-build-directory> --config Debug --parallel 2
ctest --test-dir <fresh-build-directory> -C Debug --output-on-failure
cmake --build <fresh-build-directory> --config Release --parallel 2
ctest --test-dir <fresh-build-directory> -C Release --output-on-failure
```

No failure was bypassed, expected assertion weakened, new suppression added or
product parameter changed to obtain consolidation gates. Lint PASS is not a
warning-free static-security/release signoff. Any retained warnings are reported
explicitly:132 warnings include39 PrivateApi and13 DiscouragedPrivateApi plus
existing DEBUG exported-surface/dependency/dynamic-loading findings. Their
disposition is separate release work, not silently waived by zero lint errors.

## Three-Cohort Comparison

| Cohort | JVM | Native Debug/Release | Interpretation |
| --- | --- | --- | --- |
| A:3253518 committed source, G1 source-exact baseline |1043 /166 suites |22/22 each |Baseline functionality; not selected newer microphone source |
| B:initial dirty source, preserved audit |1157 /178 suites |27/27 each |Includes G5 diagnostics and an old host-only Phase7 simulator |
| C:isolated G1-G4 |1152 /177 suites,final full rerun |24/24 each |Functional projection; not an APK-equivalence or hardware claim |

The five JVM exclusions are exactly two AudioPathTrace observer tests, one
AudioSessionController slow-snapshot diagnostic, one capture timing sink-cost
accounting test and one lifecycle suspension-reason diagnostic. Native excludes
protection_timing_probe_tests, audio_causal_flight_recorder_tests and
audio_resilience_simulator_tests. Later output-experiment assertions in the shared
ring test are excluded; all selected G4 ring/priming/admission cases are retained.
No functional test is removed just to improve totals or hide failure.

## Historical Versus New Hardware Evidence

No ADB/device install, tone, Session, network/volume change or hardware campaign
is part of this consolidation. Historical validated milestones remain intact.
DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED and
DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_QUALITY_VALIDATED keep their historical
frozen-cohort scope. SYSTEM_AUDIO_A41_TO_S9_AP_ONLY_QUALITY_VALIDATED remains
that exact reference-network result, not a universal quality qualification.
r835 MIC on frozen667B2 exercised strict S9/API29 -> Tablet/API33 OPEN/REOPEN
for three Sessions under one Enable Host, active0/0/0 and real997Hz/natural
RemoteSubmix/restoration. It included intentional720 replacement-silence frames
per generation and DEBUG teardown underruns15/15/11. Those are not erased.

This projection removes observations and combines source independently of that
APK. Current isolated hardware validation is PENDING. Minimum reconciliation:
one frozen integrated APK, same S9/Tablet ordinary MIC OPEN/REOPEN first-frame
guard and original three-Session criterion, content/silence accounting, normal
Disconnect/helper-loss cleanup and bounded SystemAudio/Video/Input regressions.
New-path VOICE_COMMUNICATION/CAMCORDER transitions, release-default availability
and broader Android qualification remain separate acceptance work. API31 A41
and API26 S7 MODIFY_AUDIO_ROUTING denials remain permission-blocked evidence;
legacy adapter/ordinary capture tests do not establish global input or an
incomplete normal Host. The app-owned writer itself is currently API31-gated.

## Remaining Phase6.5 Work

- RFC-006I static planning/admission is already accepted and implemented in
  pre-existing history. This task does not begin or duplicate it.
- Optional Shizuku composition/qualification remains scoped to its existing
  matrix. It is not an internal dependency of DirectShell.
- Existing DirectRoot laboratory implementation/history is not physical device
  qualification or release availability. No new provider work is performed.
- Full integrated Android hardware regression and preset/API/release-default
  qualification remain pending. Complete Host stays Video+SystemAudio+Input;
  MicrophoneAudio stays optional. No partial normal Host is introduced.
- SystemAudio's exact-pair/AP-only historical quality remains historical.
  Independent pre-kernel-RX gaps, late AAudio activation and the unassigned
  dispatch-to-decode episode remain open; no broad quality closure follows.
- Video HOME/REOPEN reassembly and pre-Session EADDRINUSE remain open. G3 is not
  promoted as their solution. Same-quality S9 microphone timestamp rollback is
  separate from the already committed estimate-to-hardware diagnostic correction.
- Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
  are not implemented here; static composite planning is not runtime recovery.
- Phase7 R2 stays proposed/documentation-only. S0 is not started, no simulator
  is promoted and no adaptive code/default/output24ms configuration is imported.
- Compatibility/Phase6.5 exit/release-static-review debt is not closed by these
  software gates. Existing warning/private-API/DEBUG-surface findings still need
  explicit disposition before production promotion.

## Decision

**G1_G4_SOFTWARE_CONSOLIDATED.** All four functional groups are COMMITTED,
dependency-complete and SOFTWARE TESTED on the separate branch. The final
report/index receipt is a docs-only commit after these gates; it leaves the tested
source at1cce230 unchanged. It is identifiable as the branch HEAD receipt commit.
The working index/status and original preservation are checked after that commit.
IMPLEMENTED and SOFTWARE TESTED are scoped separately from historical HARDWARE
VALIDATED. The isolated hardware reconciliation remains PENDING; no new
QUALIFIED or PRODUCTION-READY claim follows.
The branch is intended for a separately authorized integration/reconciliation
step, not an automatic merge. No push, merge, G5 work or next RFC is performed.
