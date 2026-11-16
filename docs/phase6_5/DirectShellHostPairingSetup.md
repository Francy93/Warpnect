# DirectShell Pairing Setup From Normal Host UI

2026-10-26. RFC-006J Step1 bounded UI follow-up. This is not a new provider,
recovery policy, Release onboarding qualification or Phase 6.5 exit.

## Provenance And Scope

Base: frozen G1-G5 `656c3783ba3f120f2e8b911b2ec3c932ca1b5df3`.
Isolated branch: `codex/phase6-5-directshell-pairing-setup`, worktree
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-setup/Warpnect`.
The original software-only implementation did not authorize a commit. A later
explicit consolidation authorization checkpointed the identical13 source/test/
resource inputs locally as `f7dd2cf131aa7952b67ee09e9594a3e2a4aefe9a`.
The original15-path receipt remains preserved; this documentation update changes
only its commit/status provenance, not the verified implementation or APKs.

Reviewed inputs are the reporting worktree's `PostHardwareReconciliation.md`,
`CURRENT_HANDOFF.md` and `DirectShellPairingSetupFollowUp.md`, plus accepted
RFC-006J/RFC-006I and the actual frozen source. Reporting worktree:
`C:/Users/Francy/.codex/worktrees/phase6-5-video-recovery/Warpnect`.
The report branch and its uncommitted evidence are not source inputs to builds.
Video observer `142e467` and tests `f0c6d87` are not imported.

## Behavior And Ownership

Only an explicit Enable Host request can make the existing readiness dialog
visible. Its new `Pair Wireless debugging` command is offered only when:

1. Readiness is AuthorizationRequired/UserActionRequired for ADB_PAIRING_REQUIRED.
2. Snapshot and readiness generations agree and DirectShell itself reports
   AUTHORIZATION_REQUIRED/ADB_PAIRING_REQUIRED.
3. The source-set adapter resolves both the setup Activity and developer settings
   on API30+, and no setup attempt for this generation or pending older launch
   exists.

The application-owned `HostPairingSetupController` rechecks current readiness at
the tap, consumes one launch per generation and retains no Activity/launcher.
The separate per-launch UUID correlation token is saveable across Activity
recreation and cannot match a previous process's reused readiness number. It is
UI correlation only, not a Session/authentication token. Double taps cannot
launch twice. Any outstanding saved ActivityResult token blocks another launch;
a new generation cannot overlap that result. A stale completion clears only its
own pending launch, never readiness,
capability evidence, a newer launch, Host publication or Client state.

Back/cancel and all Activity result codes are treated as **setup returned**,
not proof of pairing success. There is no automatic retry or Enable Host.
The user dismisses the dialog and explicitly requests Enable Host again;
the existing late qualification, COMPLETE plan and freeze remain mandatory.
Missing Activity, denied launch and an unavailable ActivityResult lifecycle are
reported without automatic retries. Dismiss remains available, including when
setup is unavailable, so Client use is not gated by Host pairing.

The existing DEBUG setup screen opens developer settings only on a button tap
and uses the existing notification RemoteInput code flow. Entering that screen
may request its existing notification/nearby-device permissions; it does not
pair or start a privileged helper. Submitting a code/retry in the notification
explicitly runs the pre-existing bounded pair/connect/HMAC bootstrap/shutdown
probe. This coupling is unchanged and does not publish Host. No second ADB
implementation, new notification/service/command or trust replacement is added.

The permission gate remembers that a permission request was issued, including
saved-state restoration. Denial does not re-request immediately; a retry requires
an explicit setup-screen action. Missing/denied settings navigation is handled
inside that existing Activity without changing developer settings.

## DEBUG And Release

**DEBUG-only actionable setup UX.** The adapter under `src/debug` references the
existing `DirectShellSelfBootstrapProbeActivity`. Its sibling under `src/release`
returns null unconditionally. Main references only the source-set adapter and
pure launch guard, not DEBUG classes, libadb, notification service or probes.
Gradle dependencies and main/Release manifests are unchanged. The DEBUG manifest
adds only a narrow developer-settings intent visibility query so adapter
resolution does not rely on broad package visibility. No component or permission
is added.

Passive composition may resolve availability and create the launch guard, but
cannot launch an Activity, post a pairing notification, toggle Wireless Debugging,
pair, replace trust, start a helper or request Host preparation. The guard owns
only a nullable attempt, pending generation and last attempted generation; no
history, background worker, I/O or unbounded queue is added.

A genuinely supported Release entry remains a separate production onboarding
gap: it needs a qualified Release adapter/backend and explicit lifecycle,
credential storage, permission, notification and user-consent acceptance. This
task neither moves the DEBUG stack into main nor grants that authorization.

## Exact Source Manifest

Modified:

- `app/src/debug/AndroidManifest.xml`: developer-settings intent visibility query
  for the DEBUG adapter's availability check only.
- `app/src/main/java/io/warpnect/MainActivity.kt`: availability, explicit launch,
  exception handling, saveable result token and UI wiring.
- `app/src/main/java/io/warpnect/ui/SecureSessionScreen.kt`: pure reason/action
  mapping, result notices, explicit command and unchanged dismissal/Client path.
- `app/src/main/java/io/warpnect/platform/session/integration/AndroidSecureSessionComposition.kt`:
  application-scope launch guard and close only.
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellSelfBootstrapProbeActivity.kt`:
  permission-request restoration/denial handling and safe settings navigation.
- `app/src/test/java/io/warpnect/ui/HostReadinessPresentationTest.kt`: six action
  availability/visibility/attempt-generation regressions, preserving five tests.

Added:

- `app/src/debug/res/values/strings.xml`: three messages for the existing Italian
  DEBUG screen, excluded from Release; no new SetTextI18n warning or suppression.
- `app/src/main/java/io/warpnect/platform/privileged/readiness/HostPairingSetupController.kt`:
  pure generation eligibility and launch/result state, no Android dependency.
- `app/src/debug/java/io/warpnect/platform/privileged/readiness/HostPairingSetupAdapter.kt`:
  existing DEBUG Activity adapter with API/resolution checks.
- `app/src/release/java/io/warpnect/platform/privileged/readiness/HostPairingSetupAdapter.kt`:
  unavailable Release adapter.
- `app/src/debug/java/io/warpnect/debug/directshell/PairingSetupPermissionAction.kt`:
  bounded permission-request gate, no Android calls.
- `app/src/test/java/io/warpnect/platform/privileged/readiness/HostPairingSetupControllerTest.kt`:
  eighteen eligibility, single-flight, cancellation, failure and stale-result tests.
- `app/src/testDebug/java/io/warpnect/debug/directshell/PairingSetupPermissionActionTest.kt`:
  five first request, denial, rotation-state, grant and explicit-retry tests.

Documentation: this report and its Phase 6.5 README index entry only.

## Verification

Verification receipts, artifact/source identities, preserved failed gate logs and
preservation inventories are kept outside the repository at
`C:/wg-pairing-setup-r1598/`. Source-set checks inspect compiled Release
bytecode, APK DEX entries and merged manifests, not just BuildConfig branches.

| Check | Observed Result |
| --- | --- |
| Focused controller/presentation/permission JVM tests | 34/34 PASS:18+11+5; includes five pre-existing presentation tests |
| Full `:app:testDebugUnitTest` | 1184/1184 PASS,180 suites,0 failures/errors/skips; G1-G5 baseline1155 plus29 new |
| `:app:ktlintCheck` | PASS |
| `:app:assembleDebug` | PASS,arm64-v8a/armeabi-v7a/x86_64 |
| `:app:assembleDebugAndroidTest` | PASS build only; no instrumentation executed |
| `:app:packageDirectShellProbeDex` | PASS build only; no helper launched |
| `:app:assembleRelease` | PASS,unsigned APK; no Release setup backend |
| `:app:lintDebug` | PASS,0 errors/134 inherited warnings; exact normalized warning multiset matches frozen G5,0 delta |
| Offline Flight Recorder/output-contract replays | 40/40 PASS |
| Source/bytecode/DEX/manifest/resource isolation | 21/21 PASS |
| Actual compiled native symbol guards | Debug3/3 no flight emulated TLS; Release3/3 diagnostics absent |
| Host CTest | Not rerun: no native source change; inherited G5 Debug26/26 and Release26/26 are not a new result |

Build uses Gradle8.12/JDK21.0.2 with the existing JVM17 toolchain, compile/target35,
minSdk26, NDK27.0.12077973 and CMake3.22.1. All six Android CMake caches point to
this isolated worktree's `app/src/main/cpp`, not the research tree. Release native
build type is the existing RelWithDebInfo/NDEBUG configuration. No build-tool or
dependency version is changed. Reproduce from the isolated checkout with:

```powershell
./gradlew.bat :app:ktlintCheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex :app:assembleRelease :app:lintDebug --max-workers=1 --console=plain
node --test tools/hardware/SystemAudioFlight.test.cjs tools/hardware/SystemAudioOutputContract.test.cjs
git diff --check
```

Final artifacts under `app/build/outputs`, SHA256:

| Artifact | SHA256 |
| --- | --- |
| `apk/debug/app-debug.apk` | `B043FBA2EA98DB9FA76513A27A43A01634812E6A779482E96AD529A21D94F058` |
| `apk/androidTest/debug/app-debug-androidTest.apk` | `0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F` |
| `directshell-probe/warpnect-directshell-probe.jar` | `AE3EE5B0E7CE2FAE001358395E1001A562CAE12EBE9FA28D530077C568F51145` |
| `apk/release/app-release-unsigned.apk` | `6F24F316EE46D4513C3CBE2BE38F56BF95B62D4B7457F17D100411A9D281E948` |

Failed intermediate style invocations (120-column lines), a unit-test compile
against the previous permission-gate revision, and the intentionally cancelled
build during stale-result review are preserved outside Git. They are not PASS
receipts. The final source freeze includes the separate launch token and saved
pending-token guard. No assertions or existing tests were removed to pass gates.
An intermediate successful full build had139 lint warnings, five new SetTextI18n
findings in the three added DEBUG messages. Those messages were moved to DEBUG
resources, not suppressed; pre-resource APK/helper copies, source freeze and gate
receipts remain under `pre-resources-artifacts/` and `*-before-debug-resources.*`.
Compiler/SDK/native baseline warnings remain visible in logs; they were not
suppressed or used to justify unrelated edits.

Accepted final integrated receipt: `resource-gates.log`, exit0,6m29s,
160 tasks (38 executed/122 up-to-date); earlier complete three-ABI build is in
`final-gates.log`, exit0,25m34s. The resource-only follow-up reuses unchanged
native and Release outputs; it is not represented as another clean rebuild.
`jvm-full-results.json`, `lint-final-comparison.json`,
`source-set-verification.json` and `candidate-final.json` give machine-readable
counts, reviewed warning equivalence, source isolation and artifact identities.
The first lint summary parser's empty location fields were corrected using XML
location nodes; `lint-result-corrected.json` preserves the intermediate139
findings and the final comparison uses the complete fields, not that bad summary.

No native, codec, Session, planner, media, transport, cryptography or recovery
source is changed. SystemAudio85/70ms, admission8ms and output4ms remain fixed;
MicrophoneAudio80/70ms and40ms freshness remain fixed. No24ms/default selector,
Video observer, Phase7 R2/S0 or provider integration is added.

## Final Software Classification And Preservation

**IMPLEMENTED / SOFTWARE TESTED. HARDWARE VALIDATION NOT RUN.** This is the
DEBUG normal-Host setup entry only, not Release onboarding, universal Android
qualification or production readiness. The final source cohort is13 app source/
resource/test paths plus two documentation paths. At the original implementation
close all15 were unstaged/untracked at656c378, with no commit/push/merge/reset/
stash/integration/device operation. The subsequent authorized local source/test
checkpoint is recorded above; it does not upgrade hardware acceptance.

`candidate-final.json` and `candidate-files/` record the exact15-path manifest,
per-file SHA256 and restorable source projection, including untracked files.
The13 build-input paths match the final pre-build source freeze. Preservation
checks compare the original496-path cohort (492 hashes/four metadata records),
its empty index and3253518 HEAD, clean functional20928f4/frozen656c378, both
report worktrees'17 dirty files/indexes and unchanged master/upstream refs.
Phase7 R2 remains exactly SHA256
`0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6`.
Device-restoration receipts remain historical verified evidence; no fresh device
verification or pairing-state claim is made from this software task.

## Remaining Hardware Acceptance

No device access or installation is authorized or performed here. Automated
state-machine tests model recreation/results; they do not certify Android task
stack, permission UI, notification delivery or physical pairing on a device.

A separately authorized acceptance should use a legitimately unpaired test
profile/device, preserving existing restored trust. Freeze candidate hashes and
save original APKs/data-state evidence first. Verify normal Enable Host ->
ADB_PAIRING_REQUIRED -> explicit setup exactly once, double tap/recreation,
settings/notification code entry, permission denial, cancellation, return and
fresh explicit Enable Host. Confirm no publication before normal COMPLETE
Video+SystemAudio+Input qualification/freeze, Client availability, helper
shutdown and data/trust preservation. Do not infer MIC qualification or audio/
Video defect closure from this UX test. Restore APKs and device state afterward.

G1-G5 hardware acceptance remains PARTIAL; historical audio, microphone timestamp,
Video HOME/REOPEN and EADDRINUSE debt remain OPEN. Latest independent Video
first-full campaign remains NOT_REPRODUCED, not a recovery correction. Phase6.5
is not complete and Phase7 R2 remains proposed and unchanged.

## Authorized Local Consolidation (2026-10-26)

All15 raw file hashes and four artifact hashes matched
`C:/wg-pairing-setup-r1598/candidate-final.json` before any consolidation edit.
No unexplained path or content delta existed; the initial index was empty.
Only the13 `app/` paths in that manifest were explicitly staged and committed
as `f7dd2cf` (`feat(directshell): expose explicit debug pairing setup from Host readiness`).
The remaining two paths are this acceptance report and the README index, recorded
in a separate documentation checkpoint. No generated/private evidence is staged.

Fresh consolidation verification: focused JVM34/34 (18+11+5), zero failures/
errors/skips, with only `testDebugUnitTest` rerun; isolation21/21 and diff hygiene
PASS. All13 raw build-input hashes still match the previous software receipt.
Consequently full1184 JVM,40 offline replays, six native guards and Android/lint
build gates above are retained exact-source evidence, not relabelled reruns.
No source/build configuration changed, so the expensive complete suites were
not repeated for committing identical source and updating documentation.

The retained B043FBA2 APK was built from656c378 plus the13-path cohort before
the source commit. Its source is equivalent to f7dd2cf, but it is not claimed
to be a post-commit rebuild: APK VCS/build metadata can differ on a later build.
Freeze new commit/artifact identities before any separately authorized hardware
acceptance. New receipts are in `C:/wg-onboarding-r1599/`.

Review limits remain explicit: rotation tests model the application owner and
saved permission-request state, not real Android recreation; missing-Activity/
permission launch outcomes are fake-driven controller tests plus source checks.
They do not certify the existing DEBUG pairing service's actual cancellation,
duplicate RemoteInput concurrency, trust/TLS internals or process-death behavior.
Those are production-design review gates, not silently qualified by this commit.
