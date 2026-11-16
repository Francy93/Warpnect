# DirectShell Android Multiprocess Storage Validation

2026-10-27/28. RFC-006J storage-ownership-only increment.
**P2_NATIVE_PUBLICATION_CHECKPOINT_VERIFIED** and
**P2_ANDROID_MULTIPROCESS_STORAGE_VALIDATED_ON_EXACT_FIXTURE**.
Overall **P2_PARTIAL_BLOCKED**; Release setup unavailable. No real PAKE, NSD,
production key provisioning, Host/runtime activation or P3-P5. No physical-device
test or phone-targeted command; the rejected initial USB transport enumeration
is disclosed below, not claimed to be complete device non-access.

## Task A Checkpoint

**P2_NATIVE_PUBLICATION_CHECKPOINT_VERIFIED**. Source commit
`4378f06fc8bf7c91ee30a670aadc4a023cf9d7f6`, documentation commit
`5d262e49ff678b6f1d12002edb625f6a4dc166f2`. Exactly14 source/test/build and10
documentation paths; normalized Git blobs match the reviewed LF cohort
`DC378B3F471987401673C966167425C5FA2A7EBBFAEB07B92C73798B5F529C26`.
Checkpoint worktree verified clean; no binaries, logs, keys or temporary tools
committed. Fresh37 pre-checkpoint integrity checks; earlier1435 full/251 focused
JVM,34/34 Android and40 replay results remain historical, not new executions.

The checkpoint prompt contained a helper digest transcription error
(`...61875E5B...`). The user explicitly confirmed the unchanged actual SHA256
`80D3D37976BE902B6F6A45EB200760D5129C37ADC61875A5BC18900FAA48D4A7`.
The preceding negative gate remains in the chat and external `original-negative.json`.
Only that expected reference was corrected. The helper was not edited/regenerated;
Debug/Test/Release digests and all other expected integrity values were unchanged.

## Task B Provenance And Scope

Isolated branch `codex/phase6-5-pairing-multiprocess-storage-validation`, from
`5d262e49ff678b6f1d12002edb625f6a4dc166f2`, at
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-multiprocess-storage-validation/Warpnect`.
External tools/receipts: `C:/wg-pairing-multiprocess-r1610/`.
The16 predecessor worktrees are protected, including the now-clean publisher.
The original research worktree remains496 dirty paths; its four large artifacts
retain the existing metadata-only inventory scope. No dirty branch import/merge.

Six application/test delta paths:

- `app/src/debug/AndroidManifest.xml`: one non-exported ordinary same-UID service
  in process `:pairing_storage_fixture`; no new permission or startup hook.
- `app/src/debug/java/io/warpnect/debug/storage/PairingStorageFixtureProtocol.kt`:
  finite numeric operations, canonical UUID token only, ten-second observation
  bounds; no arbitrary path, command, secret or credential-payload IPC.
- Same directory `PairingStorageFixtureService.kt`: Binder UID/process guard,
  actual storage lease and read-only inspection, one explicitly owned barrier
  worker, normal drain or explicit own-process death. No publication entry.
- `app/src/testDebug/java/io/warpnect/debug/storage/PairingStorageFixtureProtocolTest.kt`:
  four tests: canonical token, finite operations, Android process-name grammar
  and compatibility with the existing synthetic-key ownership policy.
- `app/src/androidTestDebug/java/io/warpnect/platform/privileged/directshell/pairing/AndroidPairingStorageFixture.kt`:
  opt-in UUID namespace/alias for the process fixture, exact owned cleanup;
  default existing storage tests preserve their scope.
- Same directory `AndroidMultiprocessStorageInstrumentationTest.kt`:11 methods
  covering M01-M12, with M12 cleanup assertions after every method.

Production storage, native publisher, P1/common96 tests, codec/AEAD, Keystore
reader, main/Release manifest, SDK/Gradle/dependencies, provider/planner and media
are unchanged. minSdk26 and DirectShell Wireless Debugging API30+ remain distinct.
The service does not evaluate the lazy Application composition or register a
provider. Existing default-process DEBUG providers are not moved into its process.

## Ownership Reconstruction

Storage has one operation-local atomic execution fence and one stable writer.lock
lease. DirectShellCredentialLease uses canonical Path registry placeholders to
avoid same-process overlapping opens, then one FileChannel.tryLock. Independent
processes have separate registries/channels; exclusion between them must come
from the OS lock. Cooperating writers never unlink/recreate writer.lock.

An acquired OS lock is not enrollment consent, R, P, S or F. Existing records are
inspected under a lease and block replacement, even after a prior process dies.
R remains CPU-only, P one no-replace publication, S independent sync evidence,
D joint resource drain, F P1 finalization. No fixture code changes120/60/3s P1
budgets or performs filesystem/crypto/lock work under its monitor.

The DEBUG service cannot begin an enrollment, publish, provision a key, delete
artifacts, execute commands or access Host. It derives only a fixed owned test
root from the UUID. Process A uses the real storage engine and synthetic keys
when encrypted artifacts are necessary; B uses the same existing test alias for
read-only authenticated classification. No credential/private-key bytes cross
Binder. This qualifies cooperating storage ownership, not arbitrary hostile
same-UID writers or general multiprocess enrollment coordination.

## Acceptance Mapping And Budget

| Case | Executed method / required observation |
|---|---|
| M01 | m01IndependentIdentityAndM12BoundedCleanup: actual equal UID/domain, distinct PIDs, canonical namespace/device/inode |
| M02 | m02OwnerAExcludesBWithoutWriterOrPublication: A valid lease, B Busy from acquisition and storage inspection, file set unchanged |
| M03 | m03OwnerBExcludesAInReverseDirection: B lease, A acquisition/inspection Busy |
| M04 | m04ConcurrentAcquireHasExactlyOneWinner: two acknowledged readers, one pipe release, one tryLock each, exactly one winner |
| M05 | m05CleanDrainAndReacquisitionKeepStableInode: actual A close, B acquire/drain, A reacquire without inode replacement |
| M06 | m06CommittedRecordBlocksReplacementAcrossProcesses: real encrypted JNI publication, B committed classification, unchanged bytes, subsequent begin blocked |
| M07 | m07IncompleteEnrollmentRequiresReadOnlyReconciliation: real recovery/pending, B read-only PREPARED_RECONCILIATION |
| M08 | m08ProcessDeathReleasesOsLockButNotArtifacts: verified B self-exit while locked, Binder death plus independent kernel lock grant/release barrier, one production A reacquire, retained recovery blocks begin |
| M09 | m09LostPublicationReceiptCannotAuthorizeReplacement: real P plus injected lost acknowledgement, Unknown receipt retained, B classifies committed/recovery without adopting |
| M10 | m10HeldDescriptorPreventsFalseDrainAndSecondOwner: actual P/S and extra FD, P1 receipt plus3s cleanup expiry cannot permit new attempt or release OS lease; B Busy until explicit FD/store close and D |
| M11 | m11FixtureMessagesNeverRestoreOrCreateP1Consent: passive owner unchanged; explicit consent expires at120s, B messages do not revive it, stale code rejected, zero backend/committer calls |
| M12 | After every case: no owned B worker/FD/lease (or verified dead B), stable lock inode, then main store drain, owned aliases/files cleanup |

One fresh API30 AOSP x86_64 image, one private ADB server, no alternative API/OEM
fallback.45 planned methods:11 new plus all34 reference storage/native/lifecycle
methods because the shared fixture changed.90s per case,15min total runtime;
stop at a trustworthy failure. Same-source failure is not retried for a PASS.
Synthetic fault corrections, if needed, retain negative receipts and require a
new final source/artifact freeze and all affected gates, on the same fixture only.

## Verification Results

Final six-path LF cohort after the two fixture-only corrections:
`DDE300599EA969C20F1385A85B7F8DA8FFC46EEB50406B85CA6D62B44919C931`.
Raw/per-file LF hashes are in external source-freeze.json. Earlier1437/1438 JVM
cohorts and their receipts remain preserved; they are not the final APK source.

Fresh gates against the final cohort:

| Gate | Result and scope |
|---|---|
| Full Debug JVM |1439/1439,190 suites,0 failures/errors/skips; +4 tests/+1 suite over1435/189 |
| Focused JVM |255/255 in10 suites, including unchanged96 P1; +4 over251 |
| Offline flight/output replay |40/40,0 failures/skips, final alias-cohort run |
| ktlintCheck / lintDebug / lintRelease |PASS; Debug134 warnings/0 errors, Release42 warnings,0 issue-key delta in both |
| assembleDebug / assembleDebugAndroidTest |PASS;45 exact JUnit methods discovered from compiled classes |
| packageDirectShellProbeDex / assembleRelease |PASS; dormant publisher retained, Release setup unavailable |
| Existing source-set / P1 model guards |21/21 and34/34 |
| Compiled storage / Android adapter guards |10/10 and14/14 |
| Publisher source / new fixture isolation |36/36 and22/22 |
| Native symbol guards / artifact audit |6/6 and105/105 |
| Git diff hygiene / final source freeze |PASS; no staged paths |
| Android instrumentation |45/45 executed,0 failures/skips/blocked; details below |

Final combined Gradle command used --max-workers=2 and completed successfully
in5m25s (170 actionable tasks,31 executed). Source and APK freeze preceded the
final runtime run; hashes were checked before each method. Native Debug and
RelWithDebInfo build/ABI inspection covers all three configured ABIs; native
inputs are unchanged in Task B. CTest NOT EXECUTED/not required for this delta.
No new native runtime qualification is inferred for ARM.

The extra Release unit-test compile failure remains a separate historical FAIL:
180 diagnostics across11 unchanged DEBUG-dependent tests, reproduced on the clean
parent. It was not repaired, rerun or called a passing gate. assembleRelease and
lintRelease PASS do not imply that additional unit-test source set compiles.

Negative evidence retained separately: initial full JVM1437 executed,1 FAIL in
the unchanged DirectShellStorageProcessTest process-death case (expected
COMMITTED_CLEANUP_PENDING, actual BUSY after Windows child waitFor). Its source
and all production inputs are byte-equivalent to the checkpoint. A single
focused unchanged3/3 comparison and subsequent complete1437/1437 run pass;
the initial failure remains an intermittent, unattributed Windows process/lock
observation, not a demonstrated resolved defect. No assertion, acquisition retry,
production code or host process test was changed to obtain that result. Initial
combined build was cancelled after the JVM failure while other analysis tasks
were still active; its log is not a successful final build receipt.

Other harness-only negatives: absent SDK environment on the first new-worktree
command; explicit environment-only SDK resolution; a post-checkpoint git-show
Windows long filename probe (cat-file verifies blobs without changing Git config);
Windows PowerShell5 dictionary aggregation (unchanged parser run under pwsh7);
compact-versus-pretty JSON comparison corrected by structured parsing; new
checkout CRLF normalized against both original proof text and committed Git
blobs; one new test's non-autoformatable long line split without suppression.
No source/content mismatch or weakened product assertion was hidden by those
tool corrections. Source freeze verification uses exact decoded JSON plus raw
and LF hashes. Earlier receipts/tools remain preserved.

## Preflight And Platform Fixture Corrections

The first private-server preflight rejected four physical USB transports plus
the owned emulator. The actual daemon PID/arguments included the requested
one-device filter; that filter was not effective on this observed Windows adb
37.0.1 run. No APK installation or instrumentation occurred at that point. No
phone-specific shell/package/pairing/trust/settings command was sent. The server
did enumerate/automatically attach USB transports; that incident is retained,
not relabelled as complete physical-device non-access. Phone state was not
re-queried to manufacture a restoration claim. The owned emulator/server were
stopped, vendor keys removed and the empty default-server state preserved.

Exactly one host-harness isolation repair used process-local ADB_USB=0,
ADB_EMU=0 and ADB_MDNS=0. These disable USB initialization and automatic emulator
scanning in [ADB's primary source](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/main.cpp).
No driver, security policy, global setting, phone configuration or personal AVD
was changed. Empty inventory was observed before starting the same owned AVD;
the original strict preflight then accepted exactly emulator-5666, API30,
Enforcing, empty package namespace. `preflight-first-failure.json`, first-process/
cleanup receipts and the original chat output preserve the negative observation.

The first frozen Debug APK was then rejected by API30 PackageManager before app
startup: INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION, ParsedService cannot cast
to String, stack in ComponentParseUtils.buildProcessName. No Warpnect package
was installed. This is a new fixture manifest defect, not a storage/lease failure.
The example suffix with hyphens is invalid under
[Android11 PackageParser.validateName](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android11-release/core/java/android/content/pm/PackageParser.java).
The [component parser](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android11-release/core/java/android/content/pm/parsing/component/ComponentParseUtils.java)
routes the compound-name validation result through getResult without first
checking the error path, consistent with the captured cast exception. This is
source/log correlation, not runtime instrumentation of the parser internals.

The smallest correction changes only the DEBUG suffix and its matching protocol
constant to pairing_storage_fixture, with a third host protocol regression test.
It does not export the component, change UID, weaken any assertion or change the
production storage/P1 contract. Before-fix source, APKs, hashes and install log
are preserved under `before-process-name-fix/` and `install-parse-logcat.log`.
The corrected candidate rebuilt/revalidated before installation. The same AVD
and image remained in use, not a different environment selected for a PASS.

The first method on that install then failed in fixture construction, before
key provisioning, file creation or the second process: credential_key_owner_invalid.
The new synthetic alias prefix was outside the existing Android key-reader
allowlist. Its1 FAIL/44 blocked receipt, source and APKs are preserved under
before-alias-fix/. This is a fixture ownership-policy mismatch, not an observed
filesystem lease failure. The minimal correction uses the already approved
warpnect.test.directshell.pairing.aes256.v1.UUID namespace, still distinct from
the production alias. A fourth host protocol test constructs the real key reader
with a fake inert backend, proving acceptance without any read/provision call.
The production allowlist, key reader and credential policy were not widened.
The failed fixture packages were removed after verifying no owned artifacts or
processes existed. The new DDE30059 cohort was fully gated/frozen and installed
on the same emulator. Its45/45 result does not rewrite the earlier M01 failure.

M08 additionally uses an auxiliary test-only FileChannel.lock observer to wait
for actual kernel grant/release after B self-termination. Its finite latch/join
and interruption bound are distinct from Binder death. Only after that observer
has released/closed its channel does the production lease perform one tryLock.
There is no acquisition retry loop, production blocking-lock fallback or wait
under the P1 monitor. Zombie/PID-directory disappearance is not used as a false
proxy for physical descriptor drain or power-loss durability.

## Exact Android Execution

One owned AVD: Warpnect_Multiprocess_API30_r1610. Android11/API30, x86_64,
fingerprint Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys,
security patch2021-08-05. Kernel5.4.86-android11-2-00006-gae78026f427c-ab7595864;
/data is ext4 on /dev/block/dm-5; SELinux Enforcing. No adb root/su or policy change.
Private server5055, explicit emulator-5666, USB/scanner/mDNS disabled in that
server's process environment. Shell UID2000 controls instrumentation only; it is
not either storage actor. Actual A/B UID10132 and domain
u:r:untrusted_app:s0:c132,c256,c512,c768 were asserted in every multiprocess case.
Both resolve the identical canonical UUID namespace and lock device/inode;
independent FileChannels/process registries are used. Every method checks inode
stability internally; the observed131086 happens to be reused between deleted
test directories and is not a claim that one inode spans independent lifecycles.

Frozen runtime artifacts (not the predecessor Task A APKs):

| Artifact | SHA256 |
|---|---|
| Debug APK |4C2F65BB0F59D3024CF3E5AA5714203EA68AC0AD8CE13C00409FBA4314CF6ABC |
| AndroidTest APK |680E87D60008A4B948482CBD2AC814104AE033B3BFC492447EEFA7F4E6AFAEA0 |
| Unsigned Release APK |D7161B32A3FD1D55CB6CC99699B597F6A0770EDA850F6921924769BFCC813471 |
| New-worktree helper build |600A60E5A0E1456BF5C5935376217DEF010BA9E4C0BFCAC1DAADBF672573083E |

Installed target/test hashes match the first two rows. Both APKs verify with v2
signature and signer SHA256
8AA8738920C8C0942537F7DD71ED291DF508B850EDB7E5A4D2A8DF49813CD969.
The Task A helper remains byte-identical at80D3D379...48D4A7; Task B's independently
built helper is not an alteration of that artifact or a hash-correction rebuild.
Packaged publisher libraries remain byte-identical to Task A for all three ABIs.

Final run45/45,0 failures/skips/blocked,135.986s driver wall time, under the
predeclared15min/90s-per-case limit. These durations include instrumentation/ADB
startup overhead and are not lock-acquisition or audio-latency benchmarks.

| Case | A PID | B PID | Driver ms | Actual result |
|---|---:|---:|---:|---|
| M01 |2348 |2371 |6186 |PASS identity and no owned B FD |
| M02 |2410 |2432 |3500 |PASS B Busy; A remains valid, file set unchanged |
| M03 |2471 |2493 |3574 |PASS A Busy while B owns |
| M04 |2532 |2554 |3566 |PASS pipe barrier; A won, B Busy; one attempt each |
| M05 |2595 |2617 |3518 |PASS actual close/reacquire, unchanged inode |
| M06 |4029 |4051 |3755 |PASS real encrypted P/S; committed record unchanged and replacement blocked |
| M07 |4091 |4113 |3517 |PASS retained prepared artifacts; read-only reconciliation |
| M08 (last) |4343 |4364 |3392 |PASS B self-death, kernel release, A one-shot reacquire; recovery retained/blocked |
| M09 |4154 |4176 |3701 |PASS real P plus modeled lost acknowledgement stays Unknown; no replacement |
| M10 |4218 |4240 |3372 |PASS real extra FD holds D/lease; P1 cleanup timeout blocks new work until physical close |
| M11 |4282 |4304 |3138 |PASS expired P1 consent unchanged, no backend/commit call from fixture IPC |
| M12 |All above |All above |Within each |PASS zero owned FD/worker/lease or verified dead B, then owned file/key cleanup |

Also freshly executed: AndroidPairingStorageInstrumentationTest18/18;
AndroidPairingStorageLifecycleInstrumentationTest8/8;
AndroidNativePublicationInstrumentationTest8/8. Exact method names, status
streams, durations, source freeze and installed provenance are retained in
artifact-freeze.json, instrumentation-plan/results.json and45 per-method logs.
No same-source failing method was retried for a PASS. Three successive fixture
cohorts (invalid manifest, invalid synthetic alias, corrected candidate) keep
separate negative provenance; no storage production source/assertion was changed.

Actual operations include app-private canonical containment/symlink checks,
data/directory fsync, OS leases, native no-replace publication, existing-record
protection and AndroidKeyStore AES256-GCM/non-exportable synthetic key use.
Lost acknowledgement and unresolved-close/drain inputs are explicitly injected
models around real operations. P1 timer/authentication driving is deterministic
fake backend evidence, not PAKE or daemon trust. No test measures power loss,
hardware-backed key security or hostile non-cooperating same-UID writers.

## Cleanup, Preservation And Final Git

All45 method cleanups independently confirm zero owned kernel FDs before deleting
only their UUID directory;37 explicitly provisioned synthetic aliases were
deleted and absence verified. Final target no_backup is empty; production
namespace/alias were never created. Idle bound fixture processes were force-stopped
only after successful drain, then target and test packages removed. No owned
Warpnect/helper process remains. Owned emulator/server stopped, ports5055/5666/
5667 closed, temporary private vendor keys removed. Default ADB5037 was absent
initially and remains absent. No phone re-query or restoration was attempted.

Final preservation:16 protected worktrees unchanged in branch/HEAD/index/dirty
contents/recorded metadata; research remains496 paths, publisher clean at5d262e4.
master/upstream refs and Phase7 R2 hash remain unchanged. Strict host metadata
guard retains a FAIL: only the generated16-byte global SDK .knownPackages cache
mtime changed during build, with identical SHA256 and content. All other seven
tracked host key/AVD/SDK configuration records are unchanged. No rebaseline or
timestamp restoration; host-delta-inspection.json records the exact difference.
This metadata deviation is not an Android storage PASS/FAIL or key/config change.

Final branch remains codex/phase6-5-pairing-multiprocess-storage-validation at
5d262e49ff678b6f1d12002edb625f6a4dc166f2, with six application/test and five
documentation paths uncommitted, nothing staged. No push, merge, reset, clean,
stash or broad import. Task A's two authorized checkpoints are the only commits.
Relevant canonical successor notices are documentation only; prior failures and
accepted RFC text remain unchanged. G1-G5 hardware is still PARTIAL; historical
audio/Video/timestamp defects remain OPEN and Phase7 R2 stays PROPOSED.

External receipts reside in C:/wg-pairing-multiprocess-r1610, including
checkpoint-verification/receipt.json; source/artifact-freeze.json; final Gradle
alias-fix-software-gates.log; jvm-full/focused-alias-fix-results.json and XML;
offline-final-alias.log; source/model/storage/adapter/publisher/fixture/native/
artifact isolation; installed/signature files; instrumentation-plan/results and
per-method logs; fixture-cleanup/runtime-cleanup.json; preservation-start/final;
host-state-start/inspection and host-delta-inspection; final integrity receipt.
Before-process-name-fix and before-alias-fix directories retain failed candidates.

**IMPLEMENTED / SOFTWARE TESTED:** bounded DEBUG-only fixture and regressions.
**VALIDATED:** cooperating same-UID independent-process storage contract only on
this exact API30 AOSP x86_64 fixture. **Not QUALIFIED / PRODUCTION-READY:** Android
ARM/OEM/power-loss, full P2 backend, general multiprocess enrollment or Release.

## Limits And Next Bounded Step

Process death can establish kernel lock release and retained artifacts, not
successful cancellation, Android trust rollback or physical power-loss durability.
Injected lost acknowledgement/drain predicates are models around real operations,
not spontaneous OS faults. P1 active lifecycle timers/authentication are controlled
tests, not real ADB transport. ARM is compilation-only unless separately executed.
OEM/API/storage, physical power-loss, real endpoint binding/NSD/transport cancellation,
production key lifecycle/adoption and Release onboarding remain unqualified.

Review/checkpoint the completed fixture separately before any next implementation.
No automatic Task B commit, physical qualification, real ADB integration or P3-P5.
