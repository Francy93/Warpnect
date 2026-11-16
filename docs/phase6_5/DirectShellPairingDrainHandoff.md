# DirectShell Owner-Side Automatic Drain Handoff

## 2026-10-30 Independent Checkpoint Resumption (r1630)

**P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED**, subject to the final committed-blob,
cleanliness and preservation receipt described below. Retain
**P2_OWNER_DRAIN_HANDOFF_IMPLEMENTED_SOFTWARE_TESTED** and overall
**P2_PARTIAL_BLOCKED**. Phase6.5 is incomplete. This is a local software checkpoint,
not Android runtime validation, hardware qualification or production readiness.

Verified parent: `eabc12225f34e6a3c26dbce84fbfe6ddb36b3dff`.
Source/test checkpoint: `08c6f1c3034de1545286cdc802e3731eaac10d98`.
Branch: `codex/phase6-5-pairing-owner-drain-checkpoint`.
Worktree:
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-owner-drain-checkpoint/Warpnect`.
Exactly two source/test paths in that commit and three documents in its direct
child. The documentation SHA and final post-commit proof are recorded externally
in `C:/wg-pairing-drain-checkpoint-resume-r1630/CheckpointFinalReport.md` and
`checkpoint-final.json`; no self-referential commit hash or amend is required.
No push or merge.

### Review And Evidence Reuse

The independent r1629 source/security/lifecycle review found no material defect
or security-critical inconclusive contract. Its original accepted snapshot,
current review and factual H09 addendum remain unchanged. D-O1/D-O2, exact-ticket
callbacks, clear-before-forward reentrancy, real delegate joint D and separately
observed infrastructure termination were reviewed. No source/test correction.
H09 exercises the exact cutoff at runtime; strictly-past/near-MAX cases are source
proof, not additional executed tests. Shutdown-before-close/never-started handles
can retain inert fail-closed ownership; automatic recovery is not claimed.

The explicitly authorized resumption verifies and REUSES r1629 executions:

| Prior r1629 gate | Verified prior result; NOT newly executed in r1630 |
| --- | --- |
| Full JVM | 1602/1602 methods,197 suites,0 failures/errors/skips; one unfiltered noninstrumented run |
| Focused JVM | 418/418 methods,17 suites,0 failures/errors/skips; includes18 new H01-H16 methods |
| Offline media replay | 40/40 PASS |
| Builds/format/lint | Debug,AndroidTest,helper,Release,ktlint and both lints PASS; no new semantic lint findings |
| Pinned AAR | Selected artifact and two independent reproductions PASS |
| Isolation | Source/DEX/manifest/P1/storage/native ABI and publisher guards PASS |
| Android/native execution | 45 instrumentation methods compiled,0 executed; native CTest not run |

All371 sealed r1629 files,2455 earlier sealed files, archived XML and current
full XML, source freeze, execution commands/exits, live/frozen artifacts and
selected/reproduced AAR identities were verified unchanged. The resumption
preflight passed8230 assertions. No JVM, replay, Gradle or reproduction rerun.
All31 other worktrees remain protected, including the unchanged implementation
candidate. Four inherited large research artifacts retain disclosed size/mtime-
only checks; source files have hash checks. Phase7 R2 remains unchanged.

### Admission Schema Correction And Preserved Negative

r1629 stopped with exit1 at the external aggregate, not an application gate:
`read('classpath-audit.json').checks === 132` compared an array to a number.
The unchanged producer emits `result: "PASS"`, integer `count: 132` and an array
of132 successful labels, appended only after each assertion passes.

The separately identified `admit-schema-corrected.cjs` changes only that predicate:

```javascript
classpathAudit.result === "PASS" &&
classpathAudit.count === 132 &&
Array.isArray(classpathAudit.checks) &&
classpathAudit.checks.length === classpathAudit.count
```

| External verifier | SHA256 |
| --- | --- |
| Original failed admit.cjs | 19A4AD0A4A078A09723DABC2B5EAD784735E138A925AB90FE081D349CAF7943F |
| Separate corrected verifier | 345B383403E8087F06F7BC1F248354082F09A54C5AE336F7379031E505337594 |

Exact diff, original negative/hash/exit1 and unchanged producer/receipt identities
are preserved.15 schema controls PASS: one genuine positive and14 malformed or
negative inputs, including wrong/missing result/count, noninteger/string count,
nonarray/short/long/missing checks and malformed objects. Genuine evidence was
not changed. The corrected aggregate ran ONCE: exit0,4966 assertions PASS. No
other admission assertion or expected count changed. The old failed aggregate
remains failed; it is not retroactively relabeled PASS.

### Frozen Source And Artifacts

Two-source LF cohort:
`EDC7150FF0EA69BB838D6E45F7064FB896CE3421CA4EE04C6AC47C2C39E88A79`.
Individual raw/LF identities remain CEC6EB42...25A4FDF and658C658A...790C49C,
fully recorded in the external source manifest. Inherited five-source cohort
`2E1EFC4800A1E162061CC120C4D3DF685271A2FFAF02075EC54AD2683069ED06`
and all inherited application/build inputs remain unchanged. No existing P1,
storage, transport, protocol, libadb, native, SDK, provider or media source delta.

| Reused r1629 artifact | SHA256 |
| --- | --- |
| Debug APK | EBE7CDA1DECC6E896369C66E2B3F8B4A428B6C59A2E6CE6AD7DBC5F743F7CD6D |
| AndroidTest APK | F0F744CB26AF83C630C8A9DA52E3BCFF063594FF1C9B8D973E32C9E62A0ED943 |
| Helper JAR | CD4E3AC9C94372AF32F1DD9BE907E02DFE5838D8A2D733634DF7CFE9E7F91D32 |
| Unsigned Release APK | 406EC924863B68C78D1F22A9701B8EBE7EE8749FFF5B0710DF2A035CF137D94C |
| Selected/reproduced patched AAR | BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0 |

Artifacts were built before Git consolidation from eabc122 plus the exact frozen
candidate. No post-commit rebuild or whole-APK determinism claim. Relative to
r1628, DEX inventories are identical; archive differences are confined to native
entries and recorded separately. Native input hashes/ABI guards pass; instruction-
level equivalence is not claimed. DEBUG remains inert/unregistered, Release
adapter unavailable, application minSdk26 and Wireless Debugging API30+ unchanged.

### Residual Limits And Next Recommendation Only

Retain P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED and original r1615, approved Windows
successor contract, r1628 verifier false positive, r1629 admission negative,
strict historical lint differences, separate Release-unit180/11 limitation,
CXX5304 SDK warning, transport/bootstrap/USB/SDK/native-layout observations,
G1-G5 partial hardware and media debts. No warning suppression or infrastructure
repair. H15 uses modeled positive directory S and a same-certificate fixture;
inherited C08 covers distinct certificates. Neither proves Android daemon trust.

Next is only a separately authorized disposable exact-API30 synthetic fixture
increment using existing private FS, synthetic Keystore AES alias, native
no-replace publisher, synthetic RSA, P1 observed path and owner drain. Inspect
existing clock/timer/lifecycle contracts first; stop at any missing contract.
No such increment, production D-P1-D-P4 provisioning, real adbd/NSD, device or
emulator access, Host/Session, Release onboarding, Root/Shizuku, media or Phase7
work occurred. Android storage/native composition, key/owner/endpoint integration,
blocked-write/suspend-connect, API/OEM qualification and Release remain open.

## Historical Implementation Receipt (r1628, Unchanged)

2026-10-30, r1628. **P2_OWNER_DRAIN_HANDOFF_IMPLEMENTED_SOFTWARE_TESTED**.
DEBUG-only, unregistered owner infrastructure; host software scope, not Android
runtime validation. Overall **P2_PARTIAL_BLOCKED**; Phase6.5 incomplete.

## 1. Authorization And Provenance

Explicit human approval of D-O1/D-O2 is attachment
`b0803204-b0e3-4d96-bae1-bbc2ff5a38bf/Testo incollato.txt`, SHA256
`BD56168563E14AFE1B1450EE911503FC111CBC5BA3F0D757151C096201A3A769`.
It authorizes only this DEBUG lifecycle foundation, not production decisions
D-P1-D-P4, Android platform wiring, real pairing or a commit.

The reviewed inputs remain unchanged in the separate protected
`phase6-5-pairing-android-composition-boundary-review/Warpnect` worktree:

| Input | Raw SHA256 |
| --- | --- |
| DirectShellPairingAndroidCompositionBoundaryReview.md | 2AA59C911A34852FD435A45D6F8520044E2ED7EA1A8E940BEEE94E7D826B9B1D |
| DirectShellPairingAndroidCompositionBoundaryImplementationPrompt.md | C3522E66B0FAEDE181406511B5B6899A2A8C8525F5C49315061A07E2C31D6929 |

Those historical draft documents were not edited or silently relabeled approved.
The new attachment supplies the limited acceptance provenance. Accepted RFC-006I,
RFC-006J, D1-D7 and all existing R/P/S/D/F implementations remain unchanged.

Parent/HEAD: `eabc12225f34e6a3c26dbce84fbfe6ddb36b3dff`.
Source checkpoint: `3547a51ca2cf75af7e4c1259f6d428f44185aaef`.
Branch: `codex/phase6-5-pairing-owner-drain-handoff`.
Worktree:
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-owner-drain-handoff/Warpnect`.
Initially clean; 31 worktrees total, 30 preceding worktrees protected.
No dirty branch merge or source transfer. External receipts:
`C:/wg-pairing-drain-handoff-r1628/`.

## 2. Exact Implementation Scope

Two NEW application/test paths only:

1. `app/src/debug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingDrainHandoff.kt`
2. `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingDrainHandoffTest.kt`

No existing application, test, dependency, build, manifest, SDK, native, libadb,
storage, coordinator, media, provider or accepted RFC input changed. At source
freeze, all 4933 inherited tracked files were raw-hash identical. Later changes
are limited to this report, CURRENT_HANDOFF.md and docs/FutureRoadmap.md.

The decorator implements the existing Backend/Committer and returns the existing
DeadlineAware operation shape. It wraps the concrete synthetic delegate, not a
copy of its state machine. No new protocol, generic executor, lifecycle authority,
registration, service, permission or feature gateway was added.

Source anchors in the new handoff: create:43,start:72,close:95,observe:113,
startObserver:124,observeLoop:139,stopObserver:155,awaitObserverTermination:165.

## 3. Actual Execution And Ownership

```text
explicit request/code -> real P1 -> exact ticket/original WorkContext
 -> DEBUG handoff -> unchanged synthetic operation worker
    -> existing encrypted recovery/admission -> owned socket/TLS/PAKE
    -> actual bound verifiedPeer -> candidate -> observed prepared -> R/P/S
    -> existing socket/protocol/code/identity/storage cleanup -> worker exits
 P1.close callback handoff -> one metadata slot -> owner observer infrastructure
    -> exact delegate.awaitDrain(remaining), outside P1/decorator locks
    -> delegate proves real resource D and creates its existing receipt
    -> exact handoff slot CAS -> unchanged P1 callback -> F/reconciliation
 owner shutdown -> separate stop signal and actual observer-thread join
```

| Owner | Resources and authority |
| --- | --- |
| Existing P1 | Consent, ticket/generation, original deadlines, R and semantic F |
| Existing synthetic operation | Storage/identity/code, protocol and composition worker; unchanged physical cleanup |
| Existing transport/protocol | One actual socket, I/O/close workers, pinned TLS/PAKE and conservative external-effect evidence |
| New same-owner infrastructure | Exactly one lazy observer thread, semaphore and at most one pending close observation; no enrollment or commit authority |
| Test fixture | Listener/peer worker, temporary namespace/key fixtures and barriers; separate from P1 D |

The observer remains alive after valid operation D. This is the approved D-O1
infrastructure lifetime, not an attempt worker falsely reported terminated.
The actual delegate joins every attempt-owned worker before its D. Its old D
semantics, resource domains and receipt construction have not changed.

## 4. Ports And Callback Ordering

`create` requires ObservedDirectShellPairingObserver and creates no infrastructure
or I/O. `start` validates exact ticket, clock/scheduler objects, live original
deadline and unreleased code, then dispatches infrastructure and forwards the
same WorkContext/code to the unchanged delegate. No replacement budget/token.
The underlying P1 still serializes start/cancel and is the sole trusted caller.
This is not a new security boundary against hostile code inside the process.

`commit` checks the current exact ticket and forwards the existing staged handle
and control unchanged. It performs no R, storage publication or crypto itself.
`cancel` forwards the existing stop request. Duplicate close is inert.

`close` installs one real delegate callback, captures one observation cutoff and
publishes one pending operation. The delegate alone constructs/emits D. The
decorator never creates DirectShellPairingDrainReceipt or decides its booleans.
Its callback clears only its exact operation slot, once, before forwarding to P1.
An old observer finally only marks the old observation complete; it cannot clear
a new operation. Reentrant new-generation dispatch is exercised by H11.

No gate is held across awaitDrain, delegate callbacks, commit, cancel or joins.
The observer factory is a trusted bounded CPU-only port returning a fresh,
unstarted Thread; the default creates that thread only on explicit operation
dispatch. Like existing operation start, thread ownership is published before
Thread.start. Constructors/readiness/request do not dispatch it. No filesystem,
Keystore, crypto, network or blocking join is added under P1.

## 5. D-O2 Window And Incomplete Drain

The cutoff is captured once at the original close handoff, using the injected
enrollment clock, with an upper bound of 3000ms. Dispatch delay consumes it.
The observer passes only the clamped remaining wait to the actual delegate.
With no remaining time, awaitDrain(0) performs only its existing non-waiting
physical check; it does not grant a fresh wait or infer release from expiry.
A later genuine resource witness can refine the same P1 attempt, not renew consent.

This cutoff is not P1's private cleanup deadline, an approximation claimed equal
to it, a work deadline, or publication authority. Original 120000/60000/3000ms
P1 policy remains byte-for-byte unchanged. Storage/protocol consume the original
work budget. There is no replacement timer, polling, retry or automatic rearm.

False/uncertain observation retains both P1 and decorator operation fences.
Subsequent resource termination alone cannot trigger a second observation.
No receipt, success or readmission is manufactured. Explicit late reconciliation
remains outside this increment. A physically drained attempt with unknown P/S or
conflicting knowledge can still be semantically blocked by unchanged P1.

## 6. Independent Observer Shutdown

stopObserver sets a one-way stop flag, wakes infrastructure and forwards stop to
the current delegate. It neither emits D nor silently abandons an owned thread.
awaitObserverTermination is a separately bounded, outside-UI/P1 physical join;
self-join is rejected. A false result retains the tracked thread reference and
unavailable owner state. No Thread.stop, interrupt-as-drain or replacement worker.

The owner must request P1 closure before shutting down its observer when it
wants the pending close observation delivered. Shutdown before a future close
does not resurrect infrastructure: the operation remains fenced if D cannot be
observed. Shutdown itself is never proof that an attempt or store drained.
No Application.onTerminate/service lifecycle guarantee is assumed; the component
is unregistered and no Android lifecycle adapter is implemented here.

## 7. H01-H16 Acceptance

All tests invoke the real concrete delegate. No new test constructs a D receipt
or manually invokes operation.awaitDrain. Test-only reflection obtains actual
worker/lease/channel references for bounded physical observation. Fixture-only
release after an intentionally incomplete observation does not deliver a late D
or implement a production reconciliation policy.

| ID | Actual evidence | Result |
| --- | --- | --- |
| H01 | Constructor, state reads and P1 request leave observer/store/socket absent | PASS |
| H02 | Legacy observer/context-less/wrong-context start rejected; exact original WorkContext reaches delegate | PASS |
| H03 | Two legally clean pre-I/O failed attempts reuse one live infrastructure thread; two actual D callbacks | PASS |
| H04 | Stop during infrastructure creation prevents delegate worker/storage/socket creation; actual absent-resource D | PASS |
| H05 | Real worker exits before delayed observer dispatch; automatic existing delegate observation completes D | PASS |
| H06 | Two concurrent barrier-released cancellations; both thread results checked, one D/observer, closed actual socket | PASS |
| H07 | Actual operation worker held after socket/store closure; zero-budget check cannot attest termination or readmit | PASS |
| H08 | Network worker exits but actual FileLock/channel lease remains held; competing store BUSY, D/readmission blocked | PASS |
| H09 | Dispatch held past original cutoff; observation completes without fresh wait, no D/rearm after later worker exit | PASS |
| H10 | Repeated cancel/close cannot replace callback, renew cutoff or create another observer/receipt | PASS |
| H11 | Actual D callback reentrantly starts a new generation before old observer finally; old cancel/close cannot clear new slot | PASS |
| H12 | Real host publication with injected UNKNOWN receipt stays unknown despite D; deliberate late conflicting metadata remains reconciliation | PASS |
| H13 | Operation D true while observer thread is physically alive; separate shutdown and real join prove termination | PASS |
| H14 | Dispatch exception retains occupied owner, no I/O/D; separate paused-infrastructure case proves stop request is not termination | PASS, two methods |
| H15 | Real P1 + encrypted host recovery + one owned socket + actual TLS1.3/SPAKE2 + valid observed R/P/S + automatic physical D/F | PASS |
| H16 | Actual stalled TLS and PAKE receives interrupted through owned socket; worker exits, resources drain, POSSIBLE/reconciliation retained | PASS, two methods |

18 methods cover the 16 identifiers. H07-H09 retain incomplete ownership after
their sole observation even when fixture cleanup later releases actual resources.
H08 injects the existing resourcesDrained=false port to retain a real OS lease;
this is not an actual blocked Android-native FD experiment. H12 UNKNOWN is an
explicit injected receipt after real publication, not a physical crash claim.

## 8. Actual Versus Modeled Evidence

Real: host files, AES-GCM, authenticated reread, RSA/certificate consistency,
FileLock/channel exclusion, hard-link no-replace publication, socket/TLS1.3,
exporter/SPAKE2/framing, actual verifiedPeer, worker and observer joins, code and
identity-borrow release. All peers are synthetic localhost fixtures, never adbd.

Modeled: positive directory synchronization, injected clock/scheduler, explicit
UNKNOWN-publication and held-storage-drain fault ports. StorageFixture supplies
synthetic test-owned RSA/AES; no production alias, identity adoption or key import.
H15 uses the fixture certificate at both local and peer ends; the inherited C08
regression separately covers distinct certificates. No independent remote-identity
or Android trust persistence claim follows from that synthetic success.

PEER_INFO_VERIFIED stays separate from POSSIBLE external effect, Android daemon
trust, Warpnect Session/SAS authentication and COMPLETE Host qualification.
Local PAIRED_NEEDS_EXPLICIT_HOST_RETRY is not real paired-device qualification.
There is no Host/Session, provider, NSD or production storage registration.

## 9. Fresh JVM And Replay Evidence

| Gate | Fresh result |
| --- | --- |
| development01 | 17/17 PASS; first new-file cohort |
| development02 | 18/18 PASS; strengthened test harness and added shutdown witness |
| Final focused | 418/418,17 suites,0 failures/errors/skips; includes all 400 inherited focused methods and 18 new methods |
| Final full | 1602/1602,197 suites,0 failures/errors/skips; one declared unfiltered noninstrumented execution,35s |
| Offline media | 40/40 PASS,0 failures/skips |
| Patched AAR reproduction | Two independent builds plus selected artifact match BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0 |

Reference checkpoint:1584 methods/196 suites,400 focused/16. Delta is exactly
18 methods/one suite. All inherited C01-C26 methods,96 P1 coordinator,41
observation,33 transport and30 owned-protocol tests remain unchanged.
Execution metadata records Gradle8.12,offline,maxWorkers2 and the existing bounded
JVM worker configuration; no diagnostic agent, filter or exclusion in the full run.
Final declaration/source freeze and XML/log identities are retained externally.

Fresh Windows process tests: A_FORCED_DEATH and B_ACKNOWLEDGED_RELEASE both
COMMITTED_CLEANUP_PENDING; G6_LIVE_PUBLISHED_OWNER BUSY,all six methods PASS.
This does not identify or fix the original r1615 refusal mechanism.

## 10. Fresh Build And Isolation Gates

| Gate | Actual final-cohort result |
| --- | --- |
| ktlintCheck,lintDebug,lintRelease | PASS;126/34 warnings,0 errors; no new semantic finding |
| assembleDebug,assembleDebugAndroidTest,packageDirectShellProbeDex,assembleRelease | PASS;28m53s,166 tasks144 executed/22 up-to-date; not runtime-performance evidence |
| Source-set/bytecode/manifest |21 checks PASS; no normal backend registration or Release setup activation |
| P1 model |34 checks PASS; exact unchanged P1 source/budgets,only the named inert DEBUG handoff added to the reference allowlist |
| Storage / Android adapter compiled |10+14 checks PASS;45 Android methods compiled,0 executed |
| Native publisher |36 source assertions plus3 private ELF ABI/import guards PASS |
| Native media symbols |6 compiled Debug/Release guards PASS; native sources unchanged; no CTest |
| Patched library closure / dependency classpath |619+132 checks PASS;43 unrelated library class entries unchanged,legacy API retained,actual selected artifact,Release exclusion |
| Observation / existing composition compiled |18+8 checks PASS;31 existing composition JUnit annotations unchanged |
| Existing composition DEX |19 checks PASS; global class uniqueness and fixture exclusion |
| Helper |Exactly19 current Debug APK DEX entries,matching byte-for-byte; no helper execution |
| New handoff |53 source/compiled/DEX/discovery assertions PASS in the corrected verifier; first negative retained below |
| Fixture processes |No observed test JVM/child survived; actual lease/channel/registry closure assertions retained |
| Final preservation |30 other worktrees and2072 sealed historical receipt files unchanged |

The lint comparison retains the historical strict negative: against the older
transport baseline,126/34 versus134/42 reflect exactly eight previously removed
GradleDependency advisories per variant and verified unchanged-libadb cache-path
normalization,not new changes in this task. Current counts match r1626; no new
semantic warning or suppression. The unrelated Release-unit compilation issue
is not fixed or represented as executed/PASS.

**Preserved first verifier negative:** post-software.ps1 reached its final new
guard,which incorrectly treated `.schedule` inside `work.scheduler` as a prohibited
method invocation. handoff-isolation.log and verify-handoff-first.cjs are retained.
Only the external verifier was corrected to require invocation syntax; six
positive and one negative regex controls were added. The separate
handoff-isolation02.log/53-assertion receipt completes that tail. The first
aggregate was not retroactively relabeled PASS. No application code,test assertion,
full JVM run,build or preceding guard was changed/repeated to force success.
GuardFirstNegative.md and guard-correction.json record exact provenance.

Fresh configureCMakeDebug emitted SDK parser diagnostic CXX5304 (XML through3,
metadata4). The same marker was not found in r1626's software log; do not claim
strict infrastructure-warning equality or an established cause. No SDK/NDK,
cache,metadata or flag workaround was applied. Existing Kotlin/Java/native/Opus
warnings remain visible; no media/optimization change. All required builds
completed despite those diagnostics. See InfrastructureObservations.md.

## 11. Frozen Source Identity

| Path basename | Raw and LF-canonical SHA256 |
| --- | --- |
| DirectShellPairingDrainHandoff.kt | CEC6EB423B67725C45E12A740B7EDA779BF45F69D3E917DEF3F368DB225A4FDF |
| DirectShellPairingDrainHandoffTest.kt | 658C658A40906B37CF404C76BB3A7DB9AF1B134765CF984484BCE0F71790C49C |

Two-new-path manifest (sorted path TAB uppercase LF-SHA newline,UTF8,SHA256):
`EDC7150FF0EA69BB838D6E45F7064FB896CE3421CA4EE04C6AC47C2C39E88A79`.
Inherited five-source LF cohort remains
`2E1EFC4800A1E162061CC120C4D3DF685271A2FFAF02075EC54AD2683069ED06`.
Source/artifact/document manifests are distinct; no previous digest is reused to
identify this new code. No source edit is allowed after final acceptance freeze.

Fresh artifact identities,verified copied outputs in r1628/artifacts/:

| Artifact | SHA256 |
| --- | --- |
| Debug APK | 4B669D16CE5C129C50358B2007D284012A427D6DE81DABB9D73D8AE2F044311C |
| AndroidTest APK | F0F744CB26AF83C630C8A9DA52E3BCFF063594FF1C9B8D973E32C9E62A0ED943 |
| DirectShell helper JAR | CD4E3AC9C94372AF32F1DD9BE907E02DFE5838D8A2D733634DF7CFE9E7F91D32 |
| Unsigned Release APK | FC744E7B6ECBE6547E4E47A63968C1F7B93197DE278DD6036A71F86D5F773E1B |
| Selected/reproduced patched AAR | BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0 |

AndroidTest matches r1626; Debug/helper/Release hashes differ and are not presented
as deterministic equivalents. Both Debug APKs have19 DEX entries. Class-definition
comparison:24284 ->24292,including nine new handoff/generated descriptors plus
one renamed existing-composition synthetic lambda; two old composition-generated
lambda/backport descriptors are absent. All such changes are confined to that
DEBUG pairing namespace. Their generated naming/sharing is consistent with D8
output changes,but instruction/sole-cause equivalence is not independently proven.
No old public component class disappeared. Release retains exactly15592 class
definitions in2 DEX entries,with no added/removed descriptor. New handoff absent.
Source preservation and ABI/isolation are verified,not native instruction or
whole-APK bit identity. Exact descriptor deltas are in dex-set-comparison.json.

## 12. Preserved Negatives And Limitations

Retain P2_SYNTHETIC_COMPOSITION_CHECKPOINT_VERIFIED,
P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED,P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED,
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED and
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED.
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED, r1615 negative and approved successor
contract remain unchanged. Retain historical strict lint differences and the
additional Release-unit compilation issue (180 diagnostics,11 unchanged files).
assembleRelease is not that independent gate. Retain earlier TLS/write/package/
bootstrap,USB enumeration,SDK metadata,Compose stability/native binary-layout
observations,G1-G5 partial hardware acceptance,media debt and Phase7 R2.

External formatting preflight failures are retained in DevelopmentNotes.md:
nested-build init-script guard, omitted SDK environment, and standalone CLI
source-classifier JAR selection. They are tool setup errors, not application
test failures. Same-version cached formatter binary selection changed no app
dependency/configuration/cache. Only the two new files were formatted. No failed
final JVM acceptance run was retried; development test reruns followed the recorded
test harness changes. No warning or assertion was suppressed.

## 13. Platform And Future Scope

Global minSdk26,compile/target35 and DirectShell Wireless Debugging API30+ remain.
Release setup stays unavailable. Android Keystore/storage/native publisher,
protocol/BC versions,SDK/dependencies and existing runtime behavior are unchanged.
No device/emulator/ADB/USB/NSD,production key,adbd or privileged-helper operation.

45 Android instrumentation methods compiled in this build;zero executed.
Earlier exact API30 component
runtime evidence stays historical,not evidence of combined Android composition.
No native production input changed; no CTest or physical power-loss claim.

Still open: real adbd interoperability,Android blocked-write/suspend/connect,
production Keystore/RSA lifecycle,D-P1-D-P4 provisioning/recovery,actual combined
Android storage/native transaction,Network/NSD binding,API/OEM/device qualification
and Release onboarding. No P3-P5,Root/Shizuku,media/SCL or Phase7 work occurred.

Smallest next step after verified software gates: independent review of this exact
uncommitted two-source/three-document candidate and separately authorized local
checkpoint. Do not automatically start Android fixture wiring or real pairing.
Future Android fixture execution requires separate environment authorization.

## 14. Final Git And Preservation

Exactly two new source/test paths and three documentation paths remain
uncommitted;index empty,HEAD still eabc12225f34e6a3c26dbce84fbfe6ddb36b3dff.
No commits/push/merge/reset/stash/clean. Source freeze remains unchanged after
all tests/builds/guards. Before/after preservation manifests match for all30
preceding worktrees (HEAD,branch,index,dirty-file identities),master/upstream and
Phase7 R2. The four previously disclosed large research artifacts retain
size/mtime-only checks,not source exceptions.2072 sealed r1620-r1627 receipt
files rehashed at both start and finish; historical negatives were not overwritten.

Current source and document manifests,all gate/artifact receipt hashes and final
Git scope checks are recorded in r1628/final-integrity.json and FinalReport.md.
The final evidence manifest seals this task independently. No historical receipt
or old cohort digest is reused as fresh validation. These remain reviewable local
changes,not a Git checkpoint,Android validation or production readiness.
