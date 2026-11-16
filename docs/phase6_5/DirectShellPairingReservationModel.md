# DirectShell Pairing Reservation And Receipt Model

2026-10-27. **IMPLEMENTED / SOFTWARE TESTED: pure, unwired P1 reservation/receipt
model only.** No P2/backend/Release completion or hardware claim.

## Authorization And Provenance

The user's explicit instruction, "OK, procedi col prossimo prompt
DirectShellPairingDurableCommitImplementationPrompt", authorizes execution of
the named prompt and its five reviewed contract deltas for this bounded model
increment. It is not permission to activate onboarding or implement real P2.

The review/addendum/prompt remain preserved, unchanged, in
`C:/Users/Francy/.codex/worktrees/phase6-5-durable-commit-review/Warpnect`.
Accepted RFC-006J text is not silently edited. This implementation records the
approved reservation cutoff, possible late local settlement, pre-PAKE recovery
requirement, explicit uncertainty/drain fences and first-store no-replacement
assumption. Storage/crypto/OS enforcement remains future P2 work.

New branch: `codex/phase6-5-pairing-commit-reservation`.
Worktree: `C:/Users/Francy/.codex/worktrees/phase6-5-pairing-reservation/Warpnect`.
Reviewed source parent: `73c0b6e49aad4c55df405f96f9f8dbef31a7370d`, including P1
source/test checkpoint `1271765cb3e1eebcf72908142663864abece8796`.
Dirty P2, research, Video and review files are read-only evidence, not source
inputs. P2 remains PARTIAL_BLOCKED. All changes here remain uncommitted.

## Verification

Final results, exact manifest/artifact identities, amended expectations, negative
replays and preservation comparison are recorded below.
No source from P2 or devices is used. No physical audio/network/Keystore or
filesystem crash/power-loss validation is supplied by deterministic model tests.

## Source And Scope

The only application paths are:

- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingContract.kt`
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinator.kt`
- `app/src/test/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinatorTest.kt`

No P2 endpoint/storage source, libadb source extension, runtime registration,
DEBUG setup UI/service, Release adapter, manifest, dependency, native/media,
planner/cache or accepted RFC path is changed. The coordinator is still unwired.
Receipts are external at `C:/wg-pairing-reservation-r1603/`; no APK/log/private
evidence is staged or added to Git.

## Contract Delta

`DirectShellPairingCommitPermit.publish(() -> Boolean)` is removed. The committer
receives `DirectShellPairingCommitControl`: CPU-only `reserve(prepared)` and
`completed(reservation,receipt)`. A prepared object is metadata, not authority;
R validates its exact live ticket/authorization, expected revision, current
environment snapshot and elapsed deadline. Only the original prepared object
can claim its opaque one-shot reservation. Real candidate/digest/lease ownership
and revision CAS remain P2 adapter obligations, not JVM model proofs.

Publication, durability, redundant-record cleanup and physical drain are separate
closed metadata domains. `cancel`/`cancelHostRequest` return typed local-publication
dispositions, not a rollback Boolean. Stop before R prohibits a grant. Stop after
R acknowledges no rollback guarantee; an already-claimed operation can report a
late Published/Unknown result. No new consent, endpoint or second worker follows.

The token has bounded AVAILABLE/CLAIMED/ABSTAINED execution state. Stop/drain
atomically close only a still-unclaimed opportunity. They cannot abort a claimed
filesystem action or claim rollback after R. A withdrawn, never-claimed token can
report NotPublished; this still does not undo possible Android trust. Duplicate
claims/foreign prepared objects fail, including claims after observed stop.

A Published receipt names the expected next credential revision, bound to its
reservation/ticket. Wrong/missing revision and inconsistent metadata fail closed.
Finalization accepts the original cached revision or the matching owned commit
transition, not an unrelated revision. This is a model binding, NOT a real store
CAS or permission to replace an existing identity. The first physical adapter
remains revision0/no-replacement; a fake0->1 trace exercises that distinction.
An ambiguous invalidation observed before a matching receipt stays conservative
and requires reconciliation; the future adapter must qualify notification/
snapshot ordering rather than clearing that fence automatically.

`close(drained)` has a typed joint-drain receipt covering transport, storage and
code-copy release, external trust effect and retained material. Missing callbacks
do not prove no Android-side effect. `pairingStarted(recovery)` is a CPU-only
checkpoint acknowledgment before trust-affecting I/O: only declared retained
recovery permits progress. This is a fake-backed port obligation, not a claim that
the core has written an encrypted anchor. No disk/Keystore implementation exists.

Staged resolution now names `RETAIN_UNCONFIRMED` or `RECORD_PUBLISHED`; neither
is a delete/unpair command. Failure/cancel after possible PAKE effect requires
reconciliation. Missing publication knowledge stays COMMIT_OUTCOME_UNKNOWN;
invalid/conflicting receipts fail closed. A known positive receipt before the
work deadline can finish work, but success still needs truthful joint drain.
Store receipt, resource drain and user-visible success cannot substitute for
one another.

Final drain samples the current CPU-only environment even without an invalidation
notification. A permission/backend/network/foreign-revision/runtime change after
a positive receipt prevents eligible success. Already-completed work does not
become late merely because drain finishes in its existing3s cleanup phase.

One active attempt and a bounded retained metadata fence prevent replacement
work around unknown or surviving credentials. A fake persistent namespace gate
is injected as `environment.reconciliationRequired` for new-process tests; this
does not implement cross-process locking or storage inspection. No stale ticket,
notification, worker, consent or reservation is restored. Client operation and
normal Host admission remain outside this pure owner.

## Boundaries Preserved

Awaiting code120s, total work60s after submission, cleanup observation3s. All time
uses the injected suspend-inclusive clock; stage progress never resets work.
Cleanup timeout is not evidence of physical closure. Ordinary port failures use
safe enums; fatal VM errors are not swallowed. State/receipt rendering contains
no code, key, certificate, raw exception, endpoint or ticket nonce.

Pairing completion remains PAIRED_NEEDS_EXPLICIT_HOST_RETRY, never Host enable,
runtime lease, capability evidence, planner freeze or Session authentication.
COMPLETE Host still requires Video+SystemAudio+Input; MicrophoneAudio remains
optional. Existing provider ownership/Client/DEBUG setup and unavailable Release
action are unchanged. SystemAudio85/70ms, admission8ms/output4ms and the validated
microphone path are untouched. No Phase7 implementation or media timing claim.

## Deterministic Evidence And Amended Expectations

The negative test `unresolved publication effect is not a definite failure`
ran against old P1's original production sources and failed as expected:
expected COMMIT_OUTCOME_UNKNOWN, actual CANCELLING. Fake publication had already
recorded a possible effect before raising an ordinary Exception. Preserved:
`before-unknown.log` / `before-unknown.xml`. This is a model contradiction, not
a filesystem or hardware failure. Its amended fake returns a typed Unknown
receipt instead of assuming an exception means NotPublished.

The existing49 tests retain their pre-R readiness, consent, input-wiping,
single-flight, timer/version, reentrancy, cleanup, ordinary-exception and stale
ticket intent. Post-R behavior intentionally changes: cancel after a stored
receipt but before final drain requires reconciliation, not rollback; cancel
after authenticated staging can retain identity and prevent another enrollment;
failed/unknown publication cannot erase possible daemon trust. The old test
named "failed publication discards staged handle" is renamed to state retention.
These are the explicitly approved policy deltas, not suppressed assertions.

All fake store operations are controlled by explicit test steps at prepared,
R/claim, receipt, stop/deadline and drain. They do no I/O and use no sleeps.
Store callbacks can be synchronous/reentrant or delayed; no worker is created
by the coordinator. Actual filesystem atomicity, key encryption, power failure,
daemon trust, physical socket cancellation and Android scheduling are NOT tested
by these fake interleavings.

Intermediate failures are preserved separately: two uncorrectable overlong lines
stopped formatting before test execution; one fake owner declaration needed an
explicit type to avoid Kotlin recursive inference; the initial90-test run exposed
one overly optimistic pre-drain external-effect display plus two legacy expected
outcomes requiring the approved retention semantics. The display is corrected
to Possible before invoking start; fake typed drain may later prove NonePossible.
No lint rule or assertion was disabled. Final audit/gates below apply to the
subsequent frozen final candidate, not this intermediate cohort.

## Final-Audit Negative Replays

After the first92-focused/1276-full JVM cohort and Android build, two new targeted
tests failed2/2 against that source: unnotified invalidation before final drain
still produced eligible success, and a reservation not yet claimed remained
claimable after stop. Receipts: `before-finalization-claim-fixes.log`/`.xml`;
exact source copies are in `before-finalization-sources/`. A receipt-copy path
typo was corrected before rerunning anything; the original XML was preserved.

Minimal fixes are current-snapshot validation at final drain and atomic
abstention of unclaimed execution. Already-claimed completion remains unknown or
stored-needs-reconciliation after stop, never rolled back. Matching owned store
revision is explicit so this validation cannot misclassify the modeled own0->1
commit as a foreign invalidation. Wrong receipt revisions are rejected.

The first build remains historical, not substituted for the final source cohort:
`initial-build-manifest.json`, `initial-artifacts/`, `source-freeze-initial.json`,
`jvm-full-results.json` and `android-builds.log` (27m58s, fresh native ABI builds).
The final artifact hashes/gates use separate receipts and a new source freeze.
The pre-PAKE stale-callback/new-clean-attempt test also preserves the original
generation-isolation intent while authenticated/retained identity now correctly
blocks another enrollment. No existing assertion was disabled to obtain PASS.

## Review Acceptance Coverage

| Review cases | Pure model evidence | Boundary not proven here |
| --- | --- | --- |
| R01/R03/R06/R09/R13/R20 | Pre-R cancel/expiry/revision checks, duplicate/old tickets and callback fences | Actual store/OS authority |
| R02/R04/R05/R07/R08/R14/R15/R19 | Stop/deadline/invalidation/disposal after R; claimed versus safely abstained; late receipt cannot restore consent | Cancellation of in-flight filesystem/socket I/O |
| R11/R12 | Identical receipt inert, conflicting/invalid receipt fails closed before/after drain | Truthfulness of a real backend receipt |
| R16/R17/R18 | Receipt/drain ordering, completed work then cleanup, timeout/late drain, slot fencing | Android scheduler or physical descriptor drain |
| O01-O09 | Zero passive calls, one fake operation, no queue/overwrite/Host hooks, secret/fatal-error handling, possible trust without prepared callback | Release UI, privileged runtime and Android daemon |
| P02/P03/P05-P11/P13, modeled portions | Interrupted/failed preparation, NotPublished/Unknown/Published-unsynced/cleanup-failed receipts, retained restart fence, recovery prerequisite, own/foreign revision distinction | Real file/Keystore encryption, CAS, process/power failure |
| P01/P04/P12/P14 and physical portions above | No physical claim; corresponding P2 gates remain open | Atomic old-or-new publication, cross-process lease, link/sync support, power-loss durability |

Final focused cohort is96 tests,47 more than original P1's49. Full final JVM is
1280 tests in181 suites,0 failures/errors/skips, +47 over P1's1233. All other180
suite counts remain unchanged. P2's separate1264/183 cohort is not this branch's
baseline and its two suites are not imported.

Final application source SHA256 (before final integrated gates):

| Basename | SHA256 |
| --- | --- |
| DirectShellPairingContract.kt | `2F5910C1C23CFE8C6DB9FD90C29FE9B56F143011B403D909D84A83B32F2C7FFB` |
| DirectShellPairingCoordinator.kt | `8FB2E2A9CFCE3B7CD1273E00300C93E5B4B1F947242DEAE3F57D97A7F53C5CE2` |
| DirectShellPairingCoordinatorTest.kt | `936BF0696A7261B08E7606070C4FBDD919D466D1484E70179D69172412306BCD` |

## Final Automated Gates

| Gate | Exact result / receipt under r1603 |
| --- | --- |
| Focused final JVM |96/96 PASS,0 failures/errors/skips; `focused-final.log`, `jvm-focused-final-results.json` and XML copy |
| Full final Debug JVM |1280/181 suites PASS,0 failures/errors/skips; `full-jvm-final.log`, `jvm-full-final-results.json` and XML copy |
| Count/provenance comparison |+47 over P1, all180 unrelated suite counts unchanged; `jvm-delta-full-final.json` |
| ktlintCheck |PASS, no suppression; final source frozen after formatting |
| lintDebug |0 errors/134 warnings; exact issue multiset unchanged against frozen baseline; `lint-debug-comparison.json` |
| lintRelease |0 errors/42 warnings, retained in XML/report; no claim of a fresh historical Release equivalence run |
| Debug/AndroidTest/helper/Release builds |PASS; `android-builds-final.log`,11m24s; initial fresh native build27m58s retained separately |
| Existing source/bytecode/DEX/manifest/resource isolation |21/21 PASS, same assertions; only checkout/output paths adapted; `source-set-verification.json` |
| Pure-model isolation |34/34 PASS:17 inherited/scoped checks +17 new; `model-isolation.json` |
| Compiled native guards |6/6 PASS:3 Debug RT-TLS and3 Release Flight exclusion, three ABIs |
| Offline Flight/output replay |40/40 PASS; `offline-final.log` |
| CTest Debug/Release |NOT rerun: no native/source/build-input delta. Inherited26/26 each is historical, not a new result |
| Android instrumentation / hardware |NOT EXECUTED; AndroidTest APK build is not device validation |
| Extra Release-unit compilation |NOT rerun; known baseline FAIL180 diagnostics/11 unchanged DEBUG-dependent common tests, clean-parent reproduction retained at r1601 |
| Diff/source/preservation |Exact3 app paths +7 docs; no other source/build/RFC delta; index empty; protected worktrees compare unchanged |

The inherited owner guard's old no-tracked-app-delta assertion is narrowly
adapted to require exactly the three approved application paths, not removed.
Other inherited owner checks remain; added checks cover R/receipt separation,
typed retention/stop/drain, unchanged deadlines, one-shot/abstained execution,
final environment/revision validation, private primary token construction and
DEX exclusion of the old permit/unit fixture. The existing21 source-set checks
are not weakened or expanded to admit privileged dependencies.

Compiled native guards inspect current Android outputs for arm64-v8a,
armeabi-v7a and x86_64; they are not CTest or Android callback qualification.
Required toolchain stays JDK21.0.2 launcher/Kotlin toolchain17, Gradle8.12,
SDK35/min26, NDK27.0.12077973/CMake3.22.1. Builds used max-workers1 and native
parallelism2. Existing deprecation/Kotlin/third-party/native warnings are retained.

## Final Artifact Identity

Traceability: verified parent73c0b6e + the exact three frozen application hashes
above -> final build -> hashes below. HEAD alone does not contain this uncommitted
model. No input from dirty P2/research is needed to build it. AndroidTest source
is unchanged; its unchanged hash is a build identity, not a device result.

| Output | SHA256 |
| --- | --- |
| Debug APK | `9C244840CBE193BBBC88CF2018623CB60909A3F66B155A0725DCC6423AF5E382` |
| AndroidTest APK | `0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F` |
| DirectShell DEX helper JAR | `2AA2EA0383F84DB0A9875C2BF9BCF3875E0B57003390DB814F55BD6D69A762F6` |
| Unsigned Release APK | `AF3DBA57BFC7EC357F3D92F2355678053C296954B35295AA8C08C15BD0FF8959` |

No artifact was installed or executed on Android. Release setup remains unavailable;
the standalone model's compiled presence does not supply an adapter/backend or
runtime/helper bundle. The libadb feasibility patch is absent from app outputs.

## Exact Documentation And Git Manifest

In addition to the three application paths listed earlier, the delta is exactly:

- `docs/FutureRoadmap.md`
- `docs/phase6_5/CURRENT_HANDOFF.md`
- `docs/phase6_5/CurrentNonRootDebt.md`
- `docs/phase6_5/README.md`
- `docs/phase6_5/DirectShellPairingEnrollmentOwner.md`
- `docs/phase6_5/DirectShellReleasePairingArchitecture.md`
- `docs/phase6_5/DirectShellPairingReservationModel.md` (this new receipt).

`final-manifest.json` records all10 path sizes/raw SHA256 plus the four artifacts.
`source-freeze.json` is the final three-file build input; earlier snapshots use
explicit initial/before-fix names. All delta remains uncommitted/unstaged at
HEAD73c0b6e on the isolated branch. No automatic commit, push, merge, reset,
stash, clean or deletion of preserved evidence occurred.

`preservation-start.json` and `preservation-final.json` compare nine protected
worktrees by HEAD/branch/index and dirty content/size/mtime.535 dirty paths are
preserved: original496 (492 content-hashed plus4 metadata-only large artifacts),
P2's17, review's5, Video's11 and hardware's6; frozen P1/setup/G1-G4/G5 remain
clean. Master032b592/upstream9d0b4dc remain unchanged. Phase7 R2 retains SHA256
`0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6`.
No device state/restoration/trust was accessed or freshly reverified.

## Reproduction And Remaining Gates

From the isolated source cohort/toolchain above:

```text
gradlew.bat :app:ktlintCheck :app:testDebugUnitTest --tests *DirectShellPairingCoordinatorTest
gradlew.bat :app:ktlintCheck :app:testDebugUnitTest
gradlew.bat :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex :app:assembleRelease
node --test tools/hardware/SystemAudioFlight.test.cjs tools/hardware/SystemAudioOutputContract.test.cjs
```

Keep P2 PARTIAL_BLOCKED. Required next real-backend gates are pre-PAKE encrypted
recovery/schema, immutable authenticated candidate ownership, actual namespace
lease/revision CAS/sync receipts and restart policy, real bounded connect/write/
TLS/PAKE cancellation/drain and total deadline, trust binding and Android NSD/
Keystore qualification. These are not supplied by the pure model's fake receipt
or matching revision. No P3-P5, production migration/adoption, Release UI/runtime,
automatic remediation or provider recovery is begun.

Only this model is IMPLEMENTED/SOFTWARE TESTED. Physical durability, Android
trust/resource cleanup, hardware validation, broad qualification and production
readiness remain unclaimed. G1-G5 hardware stays PARTIAL; independent audio,
microphone timestamp and Video debts remain OPEN. Phase6.5 is not complete.

## Authorized Reservation Checkpoint

2026-10-27 follow-up: **P1_RESERVATION_CHECKPOINT_VERIFIED**. The user's P1
checkpoint/P2 storage instruction authorizes two local logical commits. Source
checkpoint: `7f3e39f036db0f833efe843ccb243b82210f8e20`, exactly the three reviewed
application/test paths. The separate documentation commit contains the seven
documentation paths already listed, including this appended checkpoint receipt.
Earlier uncommitted statements describe the original delivery, not this checkpoint.

Before staging, fresh integrity verification matched all ten original raw path
hashes and all four artifact hashes from r1603. `core.autocrlf=true`: Git stores
normalized LF blobs; r1604 records both raw and canonical LF SHA256. No semantic
source change was introduced by staging. Fresh focused JVM96/96 (0 failures,
errors or skips), offline40/40, isolation21/21, model34/34, native guards6/6,
diff hygiene and nine-worktree preservation PASS. The focused rebuild took2m48s.
Full1280 JVM/build/lint results above remain retained identical-source evidence,
not newly executed full gates. No new lint warning is introduced by checkpointing.

Receipts: `C:/wg-pairing-storage-r1604/p1-integrity.json`, `p1-focused.log`,
`p1-offline.log`; guarded source-set/native evidence remains under r1603.
Only source, tests and documentation are committed, not generated/private files.
No push, merge or device access. P2 storage work must start in a separate worktree;
this does not complete P2 or make Release enrollment available.
