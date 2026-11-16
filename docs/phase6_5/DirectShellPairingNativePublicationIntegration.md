# DirectShell Native Atomic Publication Integration

2026-10-28 successor: this exact source is locally checkpointed at4378f06fc8bf7c91ee30a670aadc4a023cf9d7f6,
with documentation5d262e49ff678b6f1d12002edb625f6a4dc166f2 and a clean publisher
worktree. The [separate multiprocess receipt](DirectShellPairingMultiprocessStorageValidation.md)
adds45/45 actual API30 x86_64 methods (11 new plus34 rerun),1439/255 JVM and
required gates; P1 and production storage/native source remain unchanged.
It qualifies only the named cooperating-process fixture. Its DEBUG-only/test
delta remains uncommitted; no Release activation, ARM/OEM or power-loss claim.
Overall P2_PARTIAL_BLOCKED. The historical receipt below, including its failures,
APKs and then-current Git/next-task statements, is not retroactively rewritten.

2026-10-27. RFC-006J storage-only increment, explicitly authorized by the user's
attached production-publication integration prompt.
**P2_NATIVE_PUBLICATION_IMPLEMENTED_SOFTWARE_TESTED** and
**P2_NATIVE_PUBLICATION_ANDROID_FIXTURE_VALIDATED** for the exact fixture below. Overall
**P2_PARTIAL_BLOCKED**; Release onboarding remains unavailable. No real PAKE,
NSD, production key provisioning, physical-device access or P3-P5 work.

## Baseline And Provenance

### Authorized Checkpoint Successor

The user confirmed that the checkpoint prompt's helper SHA256 contained one
transcription error (`...61875E5B...`). The unchanged helper and original receipt
both have `80D3D37976BE902B6F6A45EB200760D5129C37ADC61875A5BC18900FAA48D4A7`.
The original negative gate is retained in the chat and external r1610
`original-negative.json`; it was not converted into a historical PASS. No helper
was edited or regenerated. Fresh checkpoint integrity confirms the same14-path
LF cohort,24-path manifest and all four frozen artifacts. Earlier build/JVM/Android
results below remain historical execution receipts, not newly rerun tests.
Source checkpoint: `4378f06fc8bf7c91ee30a670aadc4a023cf9d7f6`.
The separately authorized multiprocess qualification starts only after the
documentation checkpoint and clean-tree verification, in a new isolated worktree.
This successor does not qualify the full P2 backend or activate Release setup.

Isolated branch `codex/phase6-5-pairing-native-publication-integration`, worktree
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-native-publication-integration/Warpnect`.
HEAD remains Android storage documentation checkpoint
`ce2466240784c21e1698f1b06faed466087a57a3`, source checkpoint
`a7fefaa28681c0612ddfdc20e4cf0421849b312d`. P1 reservation checkpoints
`7f3e39f036db0f833efe843ccb243b82210f8e20` /
`856bca744cc688df22e35b1de6b1303100ea66da` remain unchanged ancestors.
No dirty branch was merged or cherry-picked. No new commit or staging.

The [native proof](DirectShellPairingNativePublicationProof.md), its
[feasibility review](DirectShellPairingAtomicPublicationFeasibility.md) and
[bounded predecessor prompt](DirectShellPairingAtomicPublicationImplementationPrompt.md)
are byte-identical documentation copies from the preserved proof worktree.
The proof's C raw SHA256 is
`F668AD0D78FDA567FE6B0CDF148AFE3D5C12AFE8DC4C794E65A09F62DFCA23DD`.
Only the reviewed public primitive/borrowed-FD algorithm is reused, not its
synthetic JNI interface, test-only library, dirty source cohort or runtime fixture.
Production uses a distinct name, fixed credential filenames, additional private
mode/source validation and typed operation knowledge.

The old8/8 primitive proof and old15 PASS/2 FAIL/9 blocked storage campaign retain
their own APK/source provenance. They are not this increment's execution counts.
The old Android report's failure history is preserved under a successor notice,
not rewritten as a passing old candidate. Accepted RFC-006I/J and the still-DRAFT
durable-commit addendum remain unchanged; the approved P1 policy is not amended.

## Exact Source Manifest

Final14-path delta cohort, LF-canonical SHA256:
`DC378B3F471987401673C966167425C5FA2A7EBBFAEB07B92C73798B5F529C26`.
Full raw and normalized hashes are in external `source-freeze.json`.
Git CRLF/LF conversion warnings are accounted for, not hidden or treated as
functional source changes.

Production/build paths:

- `app/build.gradle.kts`: three explicit API30 compiler tasks and generated JNI
  packaging dependencies, separate from the existing min26 CMake/SCL graph.
- `app/src/main/native-publication/directshell_publication.c`: one fixed-name JNI
  publisher; no generic filesystem/syscall interface.
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellNativeCredentialPublisher.kt`:
  explicit guarded load, borrowed-FD duplication, typed result and drain fence.
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/AndroidDirectShellCredentialFileSystem.kt`:
  production publisher dispatch, observation and physical-resource integration.
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellCredentialFileSystem.kt`:
  typed publication/source outcomes; existing host provider retains source.
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellCredentialStorage.kt`:
  result handling and conditional redundant-pending cleanup only.

Host tests, same package under `app/src/testDebug/java/`:

- `AndroidDirectShellCredentialFileSystemTest.kt`: model adaptation and two close/
  native-duplicate ownership fence cases.
- `DirectShellPairingStorageIntegrationTest.kt`: existing real P1/storage tests
  exercised with both retained/consumed source models, plus three ordering/race cases.
- `DirectShellNativeCredentialPublisherTest.kt`:12 gate, load, metadata, failure,
  duplicate ownership and no-queue tests; no Android JNI on the host.
- `DirectShellConsumedPublicationStorageTest.kt`:11 encrypted real-temp-file
  storage/source-consumption, sync, receipt and restart tests with modeled rename.

Android tests, same package under `app/src/androidTestDebug/java/`:

- `AndroidPairingStorageFixture.kt`: strict API30/normal-UID/domain fixture,
  kernel-FD drain observation and verified synthetic-key cleanup.
- `AndroidPairingStorageInstrumentationTest.kt`:18 existing methods; only the
  injected publication hook is moved from link to the new typed publisher.
- `AndroidPairingStorageLifecycleInstrumentationTest.kt`:8 existing methods;
  before/after publication hooks now surround actual production JNI.
- `AndroidNativePublicationInstrumentationTest.kt`:8 additional focused cases.

P1 contract/coordinator/common96-test suite, codec/AEAD v2, Keystore reader,
Android location factory, provider/UI/manifest/dependencies, SCL/CMake/native ABI,
media and Phase7 R2 are unchanged. Generated binaries/logs/keys are not source
changes and are not staged.

## Production Boundary

`libwarpnect_directshell_publication.so` is compiled with the installed
NDK27.0.12077973 for arm64-v8a/armeabi-v7a/x86_64 at API30, with O2,
`-Wall -Wextra -Werror`, no undefined symbols and16KiB maximum page alignment.
It uses the public bionic `renameat2@LIBC_R`, never a hard-coded syscall number,
private Android API, reflection, LD_PRELOAD, shell helper or external dependency.
It is not linked into min26 `libscl_core.so` and does not change SCL registration.

The sole JNI entry accepts one borrowed directory integer descriptor. It verifies
directory UID/private mode and source no-follow regular type, UID, private mode,
single link and1..65536-byte size. Only fixed `identity.pending` and
`identity.record` names are used. One RENAME_NOREPLACE call follows. Errno is
captured immediately. No payload/key/certificate, arbitrary path, descriptor
close, allocation, callback, logging, thread, retry or fallback in native code.
These checks do not claim to defeat compromised same-UID writers; the existing
stable cooperative writer lease and authenticated immutable preparation remain
necessary.

The Kotlin publisher constructor is inert. Explicit `publish` checks API30 before
library load or descriptor duplication. AndroidCredentialOs additionally checks
the actual platform, so a negative injected test cannot raise real eligibility.
The public ParcelFileDescriptor duplicate is operation-owned and closed once;
the original descriptor remains owned by Android filesystem withDescriptor.
Unknown close keeps a strong reference/fence, never retries a possibly reused FD,
and prevents subsequent I/O or lease release. A known P cannot be erased by a
later close exception. No independent lifecycle coordinator or worker queue exists.
Compose adds a return-only `$stable` static block to the entry class; compiled
inspection verifies it performs no load or I/O, rather than assuming no generated
class initializer exists.

Global minSdk26, target/compile35 remain unchanged. Only the integrated Wireless
Debugging path is API30+. API26-29 never loads this library through the explicit
publisher gate; other providers and Client retain independent eligibility.
Release may package dormant implementation, but has no supported setup adapter,
real backend or new runtime/helper registration. No legacy pairing fallback.

## Knowledge And Transaction Ordering

```text
explicit submitted operation -> stable lease
  -> encrypted recovery contents + parent/child namespace sync
  -> authenticated immutable pending candidate + sync
  -> CPU-only R -> one execution claim + digest/AEAD/ticket/revision revalidation
  -> P: fixed-name atomic no-replace rename
  -> S: publication-directory fsync
  -> remove independently redundant recovery (+ pending only if source retained)
  -> cleanup-directory fsync -> typed P1 receipt
  -> joint physical D -> existing P1 finalization F
```

No I/O/crypto/load/lock wait/join executes under the P1 monitor. Existing120s
consent/60s work/3s cleanup observation deadlines, one attempt/one reservation,
no queue, cancellation cutoff and COMPLETE Host requirement are unchanged.
Known after-R publication is not rolled back by cancellation. Late/inconsistent
receipts or missing D cannot create success or another enrollment.

| Boundary result | Atomic attempt/publication knowledge | Source knowledge | Next action |
| --- | --- | --- | --- |
| Native success | Attempted, Published | Consumed | Observe S separately; never delete/recreate pending |
| Portable host success | Attempted, Published | Retained | Existing host cleanup semantics preserved |
| EEXIST | Attempted, NotPublished; existing entry wins | Retained | No replace/retry/adoption; retain staged evidence |
| Load/API unavailable | Not attempted, NotPublished | Presence not inferred | Preserve records; no weaker publisher |
| Unsupported syscall/permission result | Attempted, known NotPublished | Presence not inferred | Keep feature unavailable, no fallback |
| Pre-call source/FD rejection | Not attempted, NotPublished | Presence not inferred | Block/reconcile, no repair |
| Native returned unknown-effect errno | Attempt confirmed, effect Unknown | Unknown | Preserve uncertainty, no retry |
| Missing/contradictory entry/result/ack | Attempt Unknown, publication Unknown | Unknown | Cache uncertainty, retain evidence, no retry |

S is carried independently in the existing P1 receipt, not inferred from native
return or cumulative filesystem evidence. D comes from descriptor/lease/worker
ownership, not a receipt or logical timeout. Filesystem observations are cumulative
safe metadata, not authority or proof that a pre-P sync also synchronized P.
Unknown result inspection never manufactures an earlier Published receipt.

The native private metadata codes distinguish success, EEXIST, unsupported,
pre-call rejection and unknown. Contradictory/unrecognized tuples decode Unknown;
only bounded errno metadata is observable, never raw exception/path detail.
Attempt knowledge is explicitly true/false/unknown; a lost result cannot be
misreported as a syscall known to have executed. The conservative outer exception
fence treats unknown attempt as possibly published, never as NotPublished.
The old Android hard-link method is explicitly unavailable, with no Os.link,
ordinary rename, ATOMIC_MOVE, copy, marker or database fallback.

## Restart And Trust Scope

The four-name namespace and encrypted v2 record format do not change. Inspection
remains bounded and read-only: recovery-only -> RECOVERY_RECONCILIATION;
pending(+recovery) -> PREPARED_RECONCILIATION; record-only ->
COMMITTED_RECONCILIATION; record+pending/recovery -> COMMITTED_CLEANUP_PENDING;
corrupt/missing-key/foreign/busy/unknown ownership remains blocked.
No consent/PAKE/publication/adoption/deletion/Host resumes after process death.

After known consumed-source P and failed S, record and recovery survive. After
confirmed S but failed redundant cleanup, P/S remain known with cleanup incomplete.
Record-only after reopen does not prove a completed enrollment, earlier fsync or
Android-side trust. No real trust effect occurs in these tests: authenticated
pairing evidence and backend/drain scheduling are controlled test inputs, not a
successful ADB TLS/PAKE protocol or endpoint identity claim.

## Verification

External receipt root: `C:/wg-pairing-native-integration-r1609/`.
Final source is DC378B3F above, not the earlier E904146F intermediate cohort.
The latter's source snapshot, manifests, failing new-test expectations and build
logs remain preserved. After refining attempt knowledge to true/false/unknown,
all affected gates were rerun on the final source; no intermediate APK was tested.

| Fresh software gate | Result |
| --- | --- |
| Full Debug JVM |1435/1435,189 suites,0 fail/error/skip; +52 over1383 |
| Focused P1/storage/publisher JVM after final build |251/251,9 suites,0 fail/error/skip; +52 over199 |
| Unchanged P1 coordinator suite within both runs |96/96 |
| Offline flight/output replay |40/40 |
| ktlintCheck, lintDebug, lintRelease |PASS;0 lint errors;134 Debug/42 Release warnings, zero baseline delta |
| assembleDebug / assembleDebugAndroidTest / helper / assembleRelease |PASS on final source |
| Existing source-set/DEX/manifest guards |21/21 |
| Existing scoped P1 model guards |34/34 |
| Compiled storage / Android adapter guards |10/10 and14/14; discovery receipts are not execution receipts |
| New publisher source guards |36/36 |
| New artifact/ELF/DEX/JUnit-discovery guards |104/104 |
| Existing compiled native Debug/Release guards |6/6 |
| New private native library |All3 ABIs compiled and ELF inspected; only x86_64 executed |
| Source freeze / diff / protected worktrees |PASS;15 protected worktrees, scope below |

Focused distribution: coordinator96, credential storage38, P1/storage integration48,
Android filesystem24, Android Keystore10, AEAD9, host process3, consumed-source
storage11, native publisher12. The48 integration executions are24 methods over
retained/consumed models, not48 independent new methods. Delta52 is27 extra
integration executions,2 filesystem cases and23 new storage/publisher cases.
No historical P2 test total was added to the current baseline.

All six unchanged SCL ABI/variant libraries were built within this task and
checked for no new publisher/renameat2 dependency. Host CTest was NOT EXECUTED:
its source/build inputs are unchanged; the new Android-only library is outside
that CMake graph. No native CTest or physical-hardware result is implied.

The independent Release-unit compile failure (180 diagnostics/11 unchanged
DEBUG-dependent common test files) is preserved, not rerun, repaired or labeled
PASS. lintRelease and assembleRelease are different passing gates. Existing
Opus/Oboe compiler warnings remain; the API30-only test intentionally reads legacy
KeyInfo.isInsideSecureHardware, producing one disclosed deprecation warning.
No assertions/warnings were suppressed. Initial new contradictory-receipt tests
expected RECONCILIATION_REQUIRED; the already-approved P1 correctly retains
COMMIT_OUTCOME_UNKNOWN. Those new expectations were corrected to the stricter
existing contract with no P1 source change. Formatter/check ordering, a whitespace-
sensitive build-graph guard, a too-early Release-APK audit and Compose's empty
static block caused preserved harness-only failures; all final semantic guards pass.

## Frozen Artifact Identities

| Artifact | SHA256 |
| --- | --- |
| Tested Debug target | `DFFEF39D083ED35B95513B419EB710E540A8547A5CAD7D455A4EBCEE354727D4` |
| Tested AndroidTest | `E83D36EF4D79DC73E7DF13542A6E5A410757E004309FF1238D5409C7AFC91081` |
| Helper JAR | `80D3D37976BE902B6F6A45EB200760D5129C37ADC61875A5BC18900FAA48D4A7` |
| Unsigned Release | `4172F4F3786FDFF8966339DEEDBF4A4F6EFF1D4494B352B706F4C096CA93E935` |
| Standalone publisher arm64-v8a | `BC78875E56E682E5FE3156B13B5BD3415C66BA8FC5450324415593C24A7B7751` |
| Standalone publisher armeabi-v7a | `154B8C370B3D73B2D135C6B5E22854F04017B130497F41E7C536015C8E9B43AA` |
| Standalone publisher x86_64 | `83DE7451EB12E133A3A0739332FB465DD4F48BA7DA726A0C112442A0918A0409` |

Full raw/LF source hashes, APK-packaged stripped-library hashes, ELF notes/imports,
compiled method discovery and copied immutable artifacts: `artifact-freeze.json`,
`source-freeze.json`, `publisher-elf-*.txt`, `artifacts/`. Target/test signer SHA256:
`8AA8738920C8C0942537F7DD71ED291DF508B850EDB7E5A4D2A8DF49813CD969`.
Both signatures verified; installed APK hashes matched the frozen files before
execution. No source from any other dirty worktree was used by the build.

Debug/Release include the separate private production library, not the synthetic
proof library. Helper remains DEX-only; the proof bridge is absent everywhere.
Release excludes DEBUG/libadb/external Bouncy Castle classes and all test fixtures,
and its setup adapter still returns null. Existing min26 SCL ELF dependencies
contain neither the publisher nor its API30 strong symbol. Library load failure,
unknown tuple or unsupported primitive never selects a weaker implementation.

## Exact Android Runtime

One newly created disposable AVD `Warpnect_Integrated_API30_r1609`, API30/AOSP11,
x86_64, emulator37.2.12 build16428233, no previous Warpnect/personal data.
Fingerprint:
`Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys`.
Kernel5.4.86-android11-2-00006-gae78026f427c-ab7595864, /data ext4 on dm-5,
SELinux Enforcing. Test process UID10130 equals target package UID; each fixture
records its own `u:r:untrusted_app:s0:c130,c256,c512,c768` context. Native code
validates getuid/fstat in that same process; there is no native executable/helper.
Shell UID2000 metadata is not substituted for app-domain execution evidence.

Control: private ADB5053, explicit serial emulator-5664, impossible physical USB
selection filter, no mDNS/autoconnect, separate AVD/user/vendor-key directories.
Default ADB5037/PID7924 and personal AVDs/keys remained unchanged. No root, su,
SELinux/permission/target downgrade, real Wireless Debugging pairing or physical
device command. Only owned emulator install/instrumentation/cleanup occurred.

Each fixture uses a UUID-owned app-private directory outside production
directshell-pairing-v1 and dedicated synthetic Keystore aliases. Startup source
and logcat show lazy existing DEBUG factory registration, not Host acquisition,
new production registration, live Session or privileged helper launch. Original
diagnostic defaults/media parameters remain unchanged. No tone or audio playback.

## Android Results Per Method

Budget fixed before execution:34 methods,60s each,720s total, one image, no retry
or fallback. **34 executed /34 PASS /0 FAIL /0 skip /0 blocked,91.519s total**
excluding boot/install. S=AndroidPairingStorageInstrumentationTest;
L=AndroidPairingStorageLifecycleInstrumentationTest;
N=AndroidNativePublicationInstrumentationTest. Durations include runner overhead,
not native-call latency or audio callback measurements.

| # | Class | Exact method | Result | Wall ms |
| --- | --- | --- | --- | ---: |
|1|S|factoryAndInspectionNeverProvisionKeyOrIdentity|PASS|7548|
|2|S|realDirectorySynchronizationAndFileSynchronizationAreDistinct|PASS|2980|
|3|S|realKeystoreNonExportableKeyEncryptsAndDecryptsBoundedRecord|PASS|2654|
|4|N|api29GateLeavesProductionLibraryUnloaded|PASS|2415|
|5|N|actualProductionPublisherConsumesPendingWithNativeSuccess|PASS|2380|
|6|S|canonicalPrivateBoundaryRejectsOutsideAndSymlinkArtifacts|PASS|2134|
|7|S|realAtomicPublicationPreservesWholeEncryptedRecordAndNoReplacement|PASS|2390|
|8|S|existingCommittedEntryWinsNoReplaceRace|PASS|2377|
|9|S|twoAdaptersShareStableLeaseSameProcessNotCrossProcessQualification|PASS|2440|
|10|S|keyRandomizationRequiresProviderIVNotCallerIV|PASS|2160|
|11|S|invalidTagAndAADNeverYieldAuthenticatedPlaintext|PASS|2140|
|12|S|wrongOwnedKeyCannotDecryptPreviousRecord|PASS|2183|
|13|S|unqualifiedKeyPurposesRemainUnavailable|PASS|2128|
|14|S|missingKeyAfterRecordCreationPreservesCiphertextWithoutRepair|PASS|2404|
|15|S|truncatedAndCorruptRecordRemainBlockedAtRestart|PASS|2291|
|16|S|injectedPostPublicationUncertaintyPreservesRealCommittedEntry|PASS|2389|
|17|S|injectedCleanupFailureDoesNotUndoPublication|PASS|2387|
|18|S|interruptedAnchorWriteRemainsBlockedWithoutRecoveryPermission|PASS|2457|
|19|S|publicationWithoutDirectorySyncCannotBecomeDurableSuccess|PASS|2376|
|20|S|injectedAmbiguousCloseCannotClaimDrainAndIsNeverRetried|PASS|2125|
|21|L|cancelBeforeReservationRetainsRecoveryButNeverPublishes|PASS|2368|
|22|L|cancelAfterReservationAbstainsUnclaimedPublication|PASS|2356|
|23|L|cancelInsideClaimedPublicationCannotPromiseRollback|PASS|3146|
|24|L|cancelAfterPublicationPreservesCommittedRecord|PASS|2602|
|25|L|duplicateReceiptHasOnePublicationAndNoHostSideEffect|PASS|2429|
|26|L|timeoutDuringStorageDoesNotReleaseUndrainedOperation|PASS|2331|
|27|L|lateReceiptAfterDisposalCannotReviveConsent|PASS|2299|
|28|L|receiptWithoutDrainNeverAllowsSecondOperation|PASS|2547|
|29|N|existingSymlinkRecordIsNeverReplacedByAuthorizedPublication|PASS|2330|
|30|N|keyEvidenceUsesSyntheticNonExportableAes256Only|PASS|2133|
|31|N|successfulRenameWithLostAcknowledgementRetainsUnknownP|PASS|2534|
|32|N|sourceValidationRejectsSymlinkBeforeRename|PASS|2580|
|33|N|boundedReaderSeesOnlyAbsentOrCompleteEncryptedRecord|PASS|4010|
|34|N|boundedRestartCombinationsAreReadOnlyAndNeverAdopted|PASS|5193|

The two original failed method identities (#7 and#24) now pass on the new APK;
all original nine blocked methods actually executed. This supersedes the old
publication boundary for this new fixture/build, not the old failed receipts.

Real observations: encrypted pending -> complete byte-identical committed record,
source consumed, native errno0; regular/symlink target collision errno17 EEXIST,
old entry preserved; file/parent/child/publication-directory fsync success; stable
same-process lease; cached receipt, actual post-P cancellation, resource drain.
The256 reader observations never saw partial ciphertext. Eight explicit synthetic
restart combinations are read-only classifications, not crash/power-loss proofs.
The negative API29 gate runs on actual API30 with an injected lower bound and
checks the library stays unmapped; actual API26-29 execution was NOT performed.

Injected lost acknowledgement, sync/cleanup failure and ambiguous close tests
deliberately model uncertainty after real operations. They are not spontaneous
filesystem errors or evidence that the platform actually failed fsync/close.
P1 timers/worker scheduling/authenticated protocol are controlled test inputs,
not real transport cancellation or Android scheduler timing qualification.

Keystore: AES256-GCM, non-exportable key handle, randomized provider IV/AAD/tag,
wrong/missing key and purpose rejection all observed. API30 legacy KeyInfo reports
inside_secure_hardware=false; production reader's backing evidence remains UNKNOWN
on API30. This is functional software-backed evidence, not hardware protection.
No production alias/key, plaintext identity/code, Android trust or DEBUG identity
was provisioned/imported/adopted.

## Cleanup And Preservation

All34 UUID directories removed only after store/lease/worker drain and actual
procfs verification of zero fixture FDs.31 explicitly created synthetic aliases
were deleted and absence verified. Final no_backup empty, target/test packages
uninstalled, no owned Warpnect/helper process. Owned emulator/server stopped,
ports5053/5664/5665 closed, temporary vendor keys removed; default ADB5037/PID7924
unchanged. No physical device restoration was necessary or attempted.

Initial/final preservation compares15 other worktrees, branch/HEAD/index, dirty
content hashes and metadata; original research remains496 entries, proof9,
prior validation8, earlier P2/review/Video/G1-G5 unchanged. Four large original
research artifacts retain the pre-existing metadata-only scope, not a new full
payload hash. Personal ADB identities/RootLab AVD configs and global SDK metadata
contents, master/upstream refs and Phase7 R2 hash are unchanged. The strict host
timestamp guard retains a FAIL: only the16-byte generated SDK `.knownPackages`
cache timestamp changed during the build window; size/content/SHA256 are identical.
The before/after delta is in host-delta-inspection.json. It was not silently
ignored, rebaselined or timestamp-restored. This is not a key/AVD/configuration
content change or an Android storage test failure. No reset/stash/clean,
push, merge, broad integration or automatic commit.

Receipts: source/artifact freeze JSON, installed-provenance, signatures,
instrumentation-plan/results and34 method logs; final-cohort-gates.log;
jvm-full-final-cohort-results.json and jvm-full-final-cohort-xml/,
jvm-focused-post-build-results.json and jvm-focused-post-build-xml/,
offline.log, isolation/native/lint comparisons, fixture/runtime-cleanup JSON,
final integrity/preservation files. Private fixture ADB keys were removed.

Final Git remains HEAD ce246624 on the isolated branch, with14 source/test/build
and10 documentation paths changed/untracked for review, nothing staged. The
three predecessor proof/review documents are unchanged byte copies; this receipt
and successor notices do not silently edit accepted RFCs or erase failed evidence.

**IMPLEMENTED / SOFTWARE TESTED:** production publisher/storage delta.
**VALIDATED:** actual encrypted transaction/P1 lifecycle on the named API30
emulator only. **Not QUALIFIED / PRODUCTION-READY:** full Android/OEM storage,
complete P2 backend or Release onboarding. Phase6.5 is not complete.

## Remaining Scope And Next Minimum Step

Independent-process Android writer-lease qualification remains distinct from the
real host child-JVM tests and same-process Android adapter tests. ARM runtime,
OEM/API coverage and physical power-loss guarantees remain unqualified. Successful
fsync returns establish OS synchronization evidence on the named fixture only.
Public descriptor-close behavior is additionally checked against actual fixture
FD drain, not asserted universally for all OEM implementations.

Real ADB TLS/PAKE, endpoint/NSD identity, physical transport cancellation, production
key provisioning/ownership, credential reconciliation/adoption and Release
runtime/onboarding remain separate mandatory P2/later gates. No P3-P5 is started.
G1-G5 hardware remains PARTIAL and all historical media debts remain open.

Next proposed bounded task: review/checkpoint this candidate, then define and
separately authorize an isolated same-UID multiprocess Android lease fixture,
with no production manifest/runtime addition or real pairing. Do not infer that
qualification from two threads or Windows child processes. No follow-up is run
automatically; all changes here remain uncommitted for review.
