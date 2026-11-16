# Windows Multiprocess Lease Test Contract Implementation

## 2026-10-28 Verified Consolidation Successor

The [checkpoint report](DirectShellPairingPatchedLibadbIntegration.md#2026-10-28-amended-candidate-local-checkpoint)
records P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED, source
9576fdca42b9c0b709ddd2af51464f3464324fd9 and its direct documentation child.
The two-file amendment and combined19-source hashes below remain unchanged;
review confirms no parent stale-lease or mutation bypass in the BUSY branch.
Fresh one full noninstrumented1510/193-suite run and broader326/13-suite focused
cohort PASS with0 failures/errors/skips. Both A and B returned committed-cleanup
pending; the live-owner G6 case strictly returned BUSY.40 replay, builds/lint,
artifact/isolation and ownership/security guards PASS.23 protected other worktrees
remain unchanged. This supersedes only the unearned checkpoint status below.

P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED retained. The original
failure remains P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED; old immediate post-crash
availability coverage is intentionally superseded, not proven or fixed. No
production storage change, fresh Android/power-loss qualification or device test.
The original r1619 branch/evidence remains uncommitted/preserved; the new isolated
checkpoint has exactly two commits. Synthetic composition NOT STARTED, overall
P2_PARTIAL_BLOCKED. Original r1619 historical receipt follows unchanged.

2026-10-28. Test-contract amendment explicitly approved by the user in the
"P2 Windows Multiprocess Lease Test Contract Amendment" task. This approval
covers crash safety versus release-qualified recovery only. It does not approve
a production correction, historical root-cause finding, patched-libadb
checkpoint or synthetic end-to-end composition.

Result: **P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED**.
Windows host test scope only; **P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED** remains.

## Provenance And Scope

- Branch: `codex/phase6-5-pairing-windows-lease-test-contract`.
- Worktree: `C:/Users/Francy/.codex/worktrees/phase6-5-pairing-windows-lease-test-contract/Warpnect`.
- Parent: `be6beb9453b09f44340233ffbd3eca2577ef1c97`; no new commit or index entry.
- Original frozen candidate: worktree `phase6-5-pairing-libadb-owned-socket-integration/Warpnect`,
  same parent, 17 application/build/test/tool paths and six documents.
- Original 17-path LF cohort:
  `1873B582984F7DCD6F2BB723E71FA4E4B35161A253CDDA2B19EE46D07105C334`.
- Selective reconstruction is recorded in `C:/wg-pairing-lease-contract-r1619/snapshot.json`:
  all 23 copied paths have original raw and LF-canonical hashes. No diagnostic
  agent, diagnostic source set or r1616/r1617 build wiring was copied.

New source delta: **two existing host test files only**, under
`app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/`:

1. `DirectShellStorageProcess.java`.
2. `DirectShellStorageProcessTest.kt`.

New documentation delta: this report and top-of-file successor entries in
`CURRENT_HANDOFF.md` and `docs/FutureRoadmap.md`. Earlier report bodies remain.
The copied production/build changes visible against clean HEAD belong to the
preserved frozen candidate, not this amendment. New production-code delta is
zero relative to that candidate; all its original 17 source hashes must match.
Accepted RFC-006I/RFC-006J, Phase 7 R2 and historical reports are not amended.

## Historical Evidence And Platform Basis

| Receipt | Historical result | Meaning retained |
| --- | --- | --- |
| r1615, `C:/wg-pairing-composition-r1615` | 1507 tests, 1506 PASS, one FAIL, 193 suites | Forced child exit followed by one inspection produced BUSY, not expected COMMITTED_CLEANUP_PENDING; refusal mechanism absent |
| r1616, `C:/wg-pairing-lease-r1616` | Registry refusal, genuine JVM overlap and independent live-process OS refusal demonstrated; five forced-death trials PASS | Independent mechanisms, not attribution of r1615 |
| r1617, `C:/wg-pairing-gradle-lease-r1617` | Instrumented full 1507 PASS; eight health tests PASS; 748 integrity checks | That worker's registry was free and first/only OS lock acquisition succeeded; instrumentation is not checkpoint acceptance |
| r1618 disposition review | Documentation-only proposed successor contract | Subsequently explicitly approved by the current user task, not retroactively approved in that review |

Original reviews remain in the preserved diagnostic worktrees:
`DirectShellWindowsLeaseFailureInvestigation.md`,
`DirectShellGradleWorkerLeaseDiagnostic.md`,
`DirectShellWindowsLeaseGateDispositionReview.md` and
`DirectShellWindowsLeaseTestContractPrompt.md`. External XML, receipts and
sealed manifests are preserved separately, never rewritten by this run.

Microsoft states that automatic removal of file locks after process termination
may take time. Confirmed exit is therefore not a guarantee of immediate lock
availability. This supports removing that temporal requirement, **not** a claim
that it explains r1615. See [LockFileEx remarks](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex#remarks).
The source review of OpenJDK17.0.14+7 in the disposition report remains historical:
`waitFor` observes process termination, not successful file-lock acquisition.

The amendment intentionally reduces post-crash temporal/liveness discrimination.
It is not equivalent to the old immediate-availability assertion. The historical
classification remains **P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED**, even if every
successor execution returns the committed status.

## Test A: Abrupt Death Safety

The original method name is retained:
`process death after atomic directory publication leaves whole encrypted record and no consent`.

Parent prepares a real encrypted recovery anchor and authenticated pending
record using the existing synthetic fixture. It captures its actual lease,
FileLock and FileChannel, then explicitly closes its storage owner. Read-only,
test-only reflection asserts that the actual lock is invalid, channel closed
and exact canonical-path registry entry absent. Registry inspection reads one
known map key only; it never removes/clears entries or releases another owner.

Child uses the original real `FileChannel.tryLock` and `Files.createLink` path.
Immediately before PUBLISHED_BEFORE_RECEIPT it asserts its lock is valid and
channel open. Parent verifies the marker, live child and expected whole
ciphertext before `destroyForcibly`; existing ten-second process-exit bounds
are retained. Actual termination is asserted. No clean-exit substitution,
sleep, lock probe, retry, second inspection or expanded timeout is added.

After termination, before the **single** inspection, all fixed artifact names
are checked with a bounded five-entry scan, regular-file/no-follow checks,
exact original recovery/pending bytes, exact committed ciphertext and
`Files.isSameFile(record, pending)`. The retained source hard link supplies a
real identity check; optional Windows fileKey values alone are not identity
proof. Parent lock/channel/registry release is checked again.

Only these mutually exclusive branches pass:

- COMMITTED_CLEANUP_PENDING: actual distinct inspector lease acquired, wrapping
  key and encrypted records read, authenticated production decoder/role/binding
  checks succeed, acquired lock/channel then close and registry becomes empty.
  Reconciliation is required. No authority or automatic cleanup follows.
- BUSY: no returned lease, no wrapping-key access or encrypted-record read by
  the inspector, exact-path registry absent, reconciliation required. It remains
  an unavailable result, **not authenticated committed state**. Artifact bytes
  and links remain unchanged; no persistent mutation, identity adoption,
  consent resolution, enrollment retry, credential replacement or authority.

Every other status/exception fails. The same staged-authorization object remains
unresolved with zero resolution callbacks; storage presence never grants P1
publication, Host or Session authority. The audited filesystem proves exactly
one create=false lease attempt and zero mutating calls during inspection and
its subsequent resource close. Assertions recheck retained artifacts after
close as well. Storage close here closes owned resources only, not credentials.

## Test B: Strict Recovery After Acknowledged Release

New method:
`explicit child lock and channel release requires strict committed cleanup recovery`.

A new JUnit TemporaryFolder provides an independent namespace with the same
encrypted preparation. Child mode `publish-release` publishes atomically and
reports PUBLISHED_BEFORE_RECEIPT while its lease remains held. Parent verifies
the exact ciphertext and sends one `R` byte. Child exits its try-with-resources
scope, releasing FileLock and closing FileChannel, asserts both are actually
released, and only then emits LEASE_RELEASED. Parent uses **one shared stdout
BufferedReader**, with sequential bounded read tasks, so acknowledgements cannot
be consumed by competing buffered readers. It requires normal exit code zero
and observed termination within the original bound.

One fresh inspection must then return **COMMITTED_CLEANUP_PENDING** with actual
ownership, authenticated records and reconciliation required. BUSY fails.
The same ciphertext, hard-link, parent ownership, unresolved authorization and
no-mutation assertions apply. This strict recovery scenario supplements Test A;
it does not replace forced death with graceful termination.

## Strict Ownership Gates

Existing production storage/namespace/publication tests remain unchanged.

| Gate | Strict coverage retained or added |
| --- | --- |
| G1 same-JVM exclusion | Existing `same JVM competing writer cannot reopen or close stable inode`; original owner can still publish |
| G2 registry cleanup | Existing explicit close/reopen cases plus actual lock-invalid/channel-closed/exact-path registry-absent assertions before every new child and after inspector drain |
| G3 real JVM overlap | New `real same JVM channel overlap is refused without registry ownership`: actual independent FileChannel/FileLock held, registry empty, one real competing acquisition returns BUSY, no key use/mutation; raw owner remains valid until explicitly closed |
| G4 live independent owner | Existing two process exclusion tests retained; new live-published-owner case proves a real child holding the published namespace causes BUSY |
| G5 atomic no replacement | Existing `real hard link publication is whole no replace and separate from cleanup`, `existing record wins actual storage CAS and is never replaced`, and committed-identity enrollment exclusion |
| G6 uncertainty/no authority | New `live published owner proves busy inspection has no authentication or persistent mutation`, existing recovery/key/corruption/ownership tests and P1 storage receipt/drain tests |

The G3 test exercises the unchanged real overlapping-channel path, not an
injected exception. Public BUSY intentionally does not expose its underlying
exception; no assertion retrospectively identifies r1615's refusal branch.
The new live-owner BUSY case deterministically exercises the safety branch even
if Test A happens to reacquire immediately. It is **not** delayed-release
evidence. New Test B, G3 and live-published G6 add three test methods; the
original three process methods remain, now six in that class.

## Source Identities

Hashes below describe the final source cohort, not old artifacts or evidence.
`source-freeze.json` records per-file raw and canonical bytes. CRLF is converted
to LF only for digest comparison, not by rewriting frozen files.

| Test file | Raw SHA256 | LF SHA256 |
| --- | --- | --- |
| DirectShellStorageProcess.java | 7608F5FBAC012F5E9F8BA520DD285FAA06D77915AD64E6042978DE812426DEB8 | E59239912B297DE908FFA726B15833083ECB5AE1D5C3F6BC1909F13E5F253198 |
| DirectShellStorageProcessTest.kt | 9A3A7C6F5ED7DF5C9683E6CBC900C50444F933A5B737EE7145E3C73AB35BB8C4 | 9A3A7C6F5ED7DF5C9683E6CBC900C50444F933A5B737EE7145E3C73AB35BB8C4 |

Two-path test-delta LF manifest:
`4ABE9B94B614541E71C9EB9E67D2524FD841E4FFA84075DBE21D6A69806CB965`.
Combined frozen17 plus amended2 source manifest:
`669EDB51625CAF4CBCFF67831819D0BBA9F765A6BFFF11B307F982CD9B8A2041`.
Algorithm for these two new manifests: SHA256 of UTF8 concatenated sorted
repository-relative `path + TAB + uppercase SHA256(LF bytes) + LF`.
The original 1873B582 cohort retains its own original receipt/algorithm and is
not reused as the amended candidate identity.

## Fresh Verification

Actual host: Windows 11 Pro64-bit, version10.0.22631/build22631. Normal Gradle8.12
Test worker uses Temurin OpenJDK17.0.14+7 (not the JDK21 Gradle launcher), one
fork, forkEvery0, maxWorkers2. `host-platform.json`, `host-jvm-version.txt` and
each `execution.json` retain this provenance. No OS version allowlist is added.

Fresh results, independently archived from actual normal Gradle XML:

| Gate | Actual result |
| --- | --- |
| Focused tests, six suites | **211/211 PASS**, zero failures/errors/skips; storage38, coordinator96, storage integration48, consumed-publication11, native-publisher host12, process6 |
| Single complete noninstrumented Debug JVM | **1510/1510 PASS**, 193 suites, zero failures/errors/skips; +3 tests, same suite count versus 1507/193 baseline |
| Kotlin and Java test compilation | PASS on final source; first compilation negative retained below |
| ktlintCheck | PASS; absent-source Gradle task SKIPPED/NO-SOURCE entries are not skipped JVM test methods |
| Source-set isolation | PASS, main/debug/release/androidTest/androidTestDebug contain no host testDebug roots |
| Compiled test-only isolation | PASS, two application compile/runtime JARs contain no process-test or lease-agent classes; eight amended test/fixture class files present only in unit-test output |
| Test worker isolation | Zero Java agents and diagnostic dependencies; full task has no include/exclude filters |
| Source/evidence integrity | **1019 checks PASS**, including unchanged frozen17, original r1615 XML193, sealed r1617 evidence and final focused/full XML |
| Protected worktrees | **22/22 preserved** within the recorded inventory; refs and Phase7 R2 unchanged |
| Git diff hygiene/index | PASS; index empty, no new commit |

The full command was declared once and executed once, through
`:app:testDebugUnitTest --rerun --offline --max-workers=2 --console=plain`,
with only the metadata init script and `-PleaseContractPhase=full`. No test
filter or diagnostic Java agent was present. Gradle reported BUILD SUCCESSFUL
in 36s. There was no full-suite failure or rerun. Initial directory preparation
found the already-created empty evidence directory and did not launch Gradle;
the subsequent invocation was the sole full JVM execution.

| Scenario | Focused execution | Full execution |
| --- | --- | --- |
| A_FORCED_DEATH, one post-exit inspection | COMMITTED_CLEANUP_PENDING | COMMITTED_CLEANUP_PENDING |
| B_ACKNOWLEDGED_RELEASE, one strict inspection | COMMITTED_CLEANUP_PENDING | COMMITTED_CLEANUP_PENDING |
| G6_LIVE_PUBLISHED_OWNER, one live-owner inspection | BUSY, fail-closed | BUSY, fail-closed |

All three report reconciliation required and zero worker agents. The final
full worker PID was14368; observed child PIDs were24512,13204,21588. The focused
worker PID was2248; observed children15984,15472,27852. Read-only process
closeout found none live. Other child-process cases independently assert
termination in their bounded finally paths. Real lock/channel/registry cleanup
is asserted by the test methods. JUnit owns TemporaryFolder cleanup; no broad
TEMP scan or independently verified per-directory deletion claim is made.
The actual post-crash BUSY branch did not occur in these executions. Its safety
checks ran against a genuine live published owner, not a fabricated delayed
Windows release or a historical-mechanism reproduction.

The first focused command failed at Kotlin test compilation before any test
worker ran: direct JVM management/ProcessHandle APIs were unavailable through
the Android compile stubs. The test-only metadata now uses host reflection;
no production classpath/API change. Original log/exit and before-fix source
freeze are retained in `focused/` and `source-freeze-compile-failed.json`.
Initial formatter/style negatives and their correction are also retained.
The corrected focused run is `focused-final/`; the one predeclared full run is
`full/`. Neither imports the r1616/r1617 diagnostic agent.

The external init script records ordinary Test task metadata and rejects
diagnostic agents or filtered full execution. It does not instrument classes,
replace the test runner, alter production locks or change the test classpath.
Its full execution receipt uses CREATE_NEW to reject accidental repetition.
Normal Gradle daemon build instrumentation is not a diagnostic test-worker agent.

## Guarantees And Non-Claims

Filesystem records, AES-GCM validation, OS locks, forced process exit, hard-link
no-replace publication and explicit FD close are real Windows/JVM operations.
Directory synchronization is **modeled by the pre-existing test fixture**;
the default provider's unsupported-directory-sync negative test remains strict.
These are not Android adapter, physical crash or power-cut durability results.

P1 R/P/S/D/F, consent/work/cleanup budgets, one-operation fencing, revision zero,
Keystore, native publication, transport, patched libadb, SDKs and dependencies
are unchanged. COMPLETE Host remains Video + SystemAudio + Input; microphone
optional. No Host/Session/provider/UI authority is added. Existing passive,
security and drain tests retain their separate software scope. No Host was
started as evidence of this test-only task.

No APK/Release rebuild, lintDebug/lintRelease, offline audio replay, CTest,
Android instrumentation or hardware campaign is represented as fresh here.
Those inputs are unchanged and earlier receipts retain their exact scope.
The independently reproduced Release-unit compile limitation (180 diagnostics
in 11 unchanged DEBUG-dependent tests), previous strict lint comparisons and
historical Windows negative remain preserved, not relabeled PASS or fixed.
No device command, ADB server, USB enumeration, NSD or pairing was used.

## Preservation And Handoff

Final `preservation-final.json` matches `preservation-start.json`: 22 other
protected worktrees, their HEAD/branch/index and dirty-file inventories preserved.
The original research inventory still has496 dirty entries; the historical
large-evidence metadata-only inventory exceptions retain that explicit scope.
Master remains032b59276a0e01e4674e807a8ffb5b5b86e9150e; tracked upstream remains
9d0b4dce7ea04a82e81778de6296cc1096775d31. Phase7 R2 SHA256 remains
0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6.

`final-integrity.json` verifies1019 conditions and the exact26-path dirty
manifest: frozen23 plus two amended tests and this new report (the two canonical
status documents are already among the frozen23 and receive only successor
entries). The frozen worktree's original23 paths are still byte-identical.
The new worktree has19 source/build/test/tool paths and seven documents, all
uncommitted/unstaged. Historical bodies in the two status files remain intact.
No reset, stash, clean, commit, merge or push was performed.

Evidence is external at `C:/wg-pairing-lease-contract-r1619/`:

- `snapshot.json`, `source-freeze.json` and original before-fix source freeze;
- `focused/` compile negative, formatter negatives and corrected formatter log;
- `focused-final/xml/`, `results-corrected.json`, execution/log/exit receipts;
- `full/xml/`, `results.json`, execution/log/exit receipts;
- source-set and compiled-isolation receipts, `resource-closeout.json`;
- preservation start/final and `final-integrity.json` with exact path hashes.

The initial focused metadata summary used PowerShell XML element conversion
incorrectly for stdout; the original archive is unchanged, and
`results-corrected.json` was reparsed from those same XML files, **not rerun**.
All wrappers/forensic scripts remain external, not application dependencies.

Next smallest increment is review of this approved test-contract implementation
and its fresh evidence. A separate authorized checkpoint task must explicitly
bind the **amended** 19-source cohort and outstanding source/artifact gates;
this task neither creates nor earns that checkpoint. Do not resume synthetic
composition or real pairing automatically.

Retain P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED and
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED in its previous scope.
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED is **NOT YET EARNED**. Synthetic P1/P2
end-to-end composition is **NOT STARTED**. Overall **P2_PARTIAL_BLOCKED**;
Release unavailable, G1-G5 hardware PARTIAL, historical media debts OPEN and
Phase 6.5 incomplete.
