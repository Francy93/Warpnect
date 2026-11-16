# DirectShell Pairing Storage Integration

Current2026-10-27 successor: [native no-replace integration](DirectShellPairingNativePublicationIntegration.md)
preserves the retained-source host provider while Android now returns explicit
consumed-source publication knowledge. Atomic P, S, cleanup and D remain
independent; generic missing results have unknown attempt/effect, never inferred
nonpublication.1435/251 JVM and34/34 API30 fixture methods pass. This is an
uncommitted component increment from frozen Android checkpoint ce246624, not a
merge of the old dirty P2 branch or full backend/Release qualification. Earlier
Windows/model/source counts below remain historical, not added to these results.

Successor,2026-10-27: [Android storage adapter](DirectShellPairingAndroidStorageAdapter.md)
records the selective isolated continuation from the two verified P1 checkpoints.
It adds an opaque AEAD key-handle port and public Android filesystem adapter;
it does not retroactively turn the Windows/model results below into Android
durability evidence. Prior seven-path candidate and receipts remain preserved.
The successor's final1383 full/199 focused JVM cohort passes, with26 compiled, unexecuted
instrumentation methods. Real Android platform validation remains pending,
overall P2_PARTIAL_BLOCKED and Release unavailable. The remaining sections are
the unaltered historical storage-only receipt, not the successor's source manifest.

2026-10-27. Storage-only P2 increment; no transport, Android adapter or Release
activation. Overall P2 remains **P2_PARTIAL_BLOCKED**. This report distinguishes
real host operations, modeled synchronization, Android portability and power loss.

## P1 Checkpoint And Approval

**P1_RESERVATION_CHECKPOINT_VERIFIED**:

- `7f3e39f036db0f833efe843ccb243b82210f8e20`: reservation source/tests, exactly3 paths.
- `856bca744cc688df22e35b1de6b1303100ea66da`: software receipt/navigation, exactly7 docs.

Both local commits were explicitly authorized by the user. The original ten raw
path hashes and four artifact hashes matched r1603 before staging. Git's
`core.autocrlf=true` normalizes committed text to LF; all three source/test blobs
match the separately recorded LF-canonical hashes. Fresh96 focused JVM,40 replay,
21 source-set,34 model and6 native guards pass. Full1280/build/lint are retained
identical-source evidence, not freshly rerun for the checkpoint. P1 is clean.
Receipts: `C:/wg-pairing-storage-r1604/p1-integrity.json`, `p1-focused.log` and
`p1-offline.log`; prior r1603 remains preserved.

The user's named prompt approval authorized the five bounded reservation policy
deltas recorded in the P1 receipt; this instruction separately authorizes real
storage with controlled fake authenticated evidence. The review/addendum remains
PROPOSED in its unchanged review worktree, not silently rewritten into accepted
RFC-006J. No broader onboarding/trust/adoption policy is accepted by this code.

## Source Closure And Provenance

Branch `codex/phase6-5-pairing-storage-integration`, isolated from856bca7 at
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-storage-integration/Warpnect`.
New P2 changes are uncommitted. No dirty P2 file or whole branch was imported.

Main package `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/`:

- `DirectShellCredentialCodec.kt`: bounded v2 recovery/authenticated envelopes,
  immutable evidence copy, local identity consistency and existing wrapping-key port.
- `DirectShellCredentialFileSystem.kt`: explicit blocking operations and stable
  OS lease, no waiting lock/retry or application registration.
- `DirectShellCredentialStorage.kt`: single operation storage owner, fixed
  namespace, prepare/reserved publication, independent receipts/drain and inspection.

Tests in the matching `app/src/testDebug/java/` package:

- `DirectShellCredentialStorageTest.kt` (also shared test-only crypto/sync fixture).
- `DirectShellPairingStorageIntegrationTest.kt` (real P1 owner, manually advanced worker).
- `DirectShellStorageProcess.java` and `DirectShellStorageProcessTest.kt` (child
  JVM exclusion/process-termination tests, not shipped in APKs).

Selective algorithm provenance: old dirty P2 storage raw SHA256
`F938E14F1B8D2BCE56F2C0687441F8E6D82E370E4F5B662A4CFE9E3160BC7ACB`, test
`119FEF4AB6123A607A111EF322445C2E666E3B0C77D4C78C0AB41A2E0FDEB579`.
Reuse is limited to RSA2048/self-signed X509/private-public consistency,
AES-GCM96-bit IV/128-bit tag/AAD, bounded reads, CREATE_NEW/file force and
no-replace hard-link algorithms plus test certificate generation. Its v1 schema,
object monitor, discard path and publication status are NOT carried forward.
Endpoint-policy source/tests and localhost patched-libadb proof remain untouched.

## Storage And Authorization Contract

Construction is inert. Explicit submitted operation work is the only permitted
caller of `begin`; passive P1 readiness/request never calls it. All crypto, file,
lease and synchronization operations are blocking worker-only methods. No real
backend/committer worker is added; the integration fixture advances the existing
operation outside the owner monitor and asserts no I/O there. Production wiring
and physical worker cancellation/drain still require later implementation.

Namespace under a future canonical app-private `noBackupFilesDir` base:
`directshell-pairing-v1`, stable `writer.lock`, at most three <=65536-byte
encrypted files: `identity.recovery`, `identity.pending`, `identity.record`.
No history directory, retry queue, alternate path, overwrite or copy fallback.
Read/inspection examines at most five directory entries before blocking an
unexpected namespace, and reads at most65537 bytes to detect overlong records.
The lock inode is never truncated, deleted or replaced by this engine.

Recovery v2 stores local RSA key/certificate plus authenticated transaction
binding, **no remote peer claim and no pairing code**. File-content force, parent
namespace sync and child namespace sync precede RECOVERY_READY. Missing existing
AES256 key, invalid identity, partial write or unsupported sync prevents that
pre-PAKE gate. Failed/cancelled/uncertain records are retained; close/inspection
never deletes them. Existing DEBUG v1 records are not imported or adopted.

After a future trusted protocol adapter supplies authenticated evidence bound
to the exact ticket/local certificate, prepare writes a complete immutable
candidate before R. This engine verifies association, NOT PAKE authenticity;
all such evidence in this increment is an explicit test fixture.

R remains the original CPU-only opaque P1 reservation. Publication must use its
exact prepared handle once, under the same storage lease, with initial revision0.
P is the actual create-if-absent directory operation, not an earlier exists()
check. A foreign/existing/corrupt record is never overwritten/adopted. A generic
exception after entering P reports Unknown, not false NotPublished. After known P,
failed directory sync gives Published/Unconfirmed and retains recovery material.
Only known P/S permits removal of redundant pending/anchor copies; cleanup failure
is independent. A typed receipt is not D. Close returns true only after the owned
lease/descriptors are released and no storage operation is active. No timer or
receipt can fabricate physical drain. The actual operation must still combine
transport/store/code-copy drain before F. No Host/runtime/Session effects follow.

Cancel before R forbids a grant. An unclaimed stopped reservation cannot start
publication. Once execution was claimed after R, stop/expiry cannot promise
rollback: publication may settle, but an invalidated attempt cannot automatically
become successful. Unknown P/S, surviving material and missing D block subsequent
enrollment. Existing120s/60s/3s budgets are unchanged; no filesystem-completion
deadline is invented, and no coordinator/UI monitor is held across storage I/O.

## Ownership And Restart

One nonblocking atomic in-instance execution gate plus a stable exclusive
`FileChannel.tryLock()` lease covers preparation through publication/cleanup.
A process-wide canonical-path registry denies a second same-JVM lock FD before
opening it; this also avoids POSIX closing-another-FD lock loss. Lease validity
and regular-file shape are checked; inode file-key equality is additionally
checked only when the provider supplies it. Windows NIO supplies no fileKey,
so null equality is NOT treated as proof of inode identity. Stable-inode behavior
depends on every cooperating writer never unlinking/replacing that lock file.
OS locks address cooperating independent processes only,
not root, compromised same-UID writers or hostile replacement of the namespace.
Failed descriptor cleanup retains a strong namespace fence, not a false D.

Restart inspection acquires an existing lock without creating it, decrypts and
validates only the bounded fixed records, then releases the inspection lease.
It never restores consent, ticket, reservation, operation or credentials into a
runtime. All nonempty/unknown states require explicit reconciliation:

| Surviving state | Classification / permitted action |
| --- | --- |
| No namespace / lock only | EMPTY; no consent implied |
| Valid recovery only | RECOVERY_RECONCILIATION; retain |
| Candidate, no committed entry | PREPARED_RECONCILIATION; retain, no automatic P |
| Valid committed entry only | COMMITTED_RECONCILIATION; not completed enrollment or Host authority |
| Committed plus redundant files | COMMITTED_CLEANUP_PENDING; no automatic unlink |
| Incomplete/tag-invalid/oversized/wrong-key/foreign records | CORRUPT; retain, block |
| Missing wrapping key | KEY_UNAVAILABLE; retain, never regenerate |
| Lease competitor | BUSY; no second worker/FD chain |
| Unknown filesystem operation or lock outcome | IO_UNCERTAIN / OWNERSHIP_UNRESOLVED; block |

Simulated boundaries include anchor partial write, anchor sync, candidate partial
write, prepared-before-R, link entry, successful P before receipt, S failure and
cleanup failure. Child-process termination after P is not a physical power cut.
PAKE/Android trust effects are not reversible by deleting a local record; this
slice does no PAKE or trust mutation at all.

## Primitive Feasibility And Portability Limit

Fresh Windows/JDK21 NIO probe on test-owned files: hard-link same-file true;
second link raises FileAlreadyExistsException; directory READ/force raises
AccessDeniedException. Default storage therefore returns UNSUPPORTED before
creating the recovery anchor. This is an actual unavailable guarantee, not PASS.
The tests' `ModeledDirectorySync` explicitly models successful sync ordering;
it does NOT prove Windows directory durability. All other principal file,
encryption, hard-link and lease operations in those fixtures are real.

Android minSdk26/compileSdk35 is unchanged. NIO
[createLink is API26 and optional](https://developer.android.com/reference/java/nio/file/Files#createLink(java.nio.file.Path,%20java.nio.file.Path));
[tryLock is nonblocking](https://developer.android.com/reference/java/nio/channels/FileChannel#tryLock(long,%20long,%20boolean)).
API availability alone does not qualify app-private filesystem/journal semantics.
[FileChannel.force](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))
is not an Android directory/power-loss test. A successful link is atomic entry
publication, not confirmed content+parent+directory durability across power loss.
Android Keystore/hardware backing, noBackup location ownership, directory sync,
cross-process locking and actual filesystem guarantees remain UNQUALIFIED.
No Android stub adapter advertises them. The current wrapping-key port deliberately
requires an existing exportable AES256 key; unqualified/nonexportable providers
are unavailable, not treated as a working Keystore integration.

## Negative Evidence And Software Tests

The first two formatting attempts stopped before tests on uncorrectable long
lines (three main then two fixture lines); receipts retain both failures.
The first actual focused run executed148 tests,46 failures: most stopped at
IO_UNCERTAIN before anchor readiness; a passive fixture also accessed an
uninitialized operation. A direct lease test isolated a ClassCastException:
mixed NIO enum option inference produced Object[] rather than OpenOption[].
The options now have an explicit type. Independent JDK17/21 probes also found
Windows fileKey unavailable; OS exclusion is actually tested, not inferred from
that optional field. The passive fixture now reports zero starts before create.
These were new prototype/fixture defects, not regressions assigned to older P2
or historical Android results. `focused-r3-xml`, `lease-negative-xml` and logs
preserve the failures. The next focused run passed149; it is superseded by the
expanded final cohort, not added to it. Two new nullable fixture compiler warnings
were corrected without suppression before final gates.

A final targeted test failed1/1 when key availability disappeared after R:
storage knew P had not begun but returned an unclaimed receipt P1 could not
accept. `prepublication-negative.log`, XML and exact before-fix storage source
are preserved. The storage now claims the exact validated in-memory candidate
once before execution, separately from entering P; known pre-link failures
can truthfully report NotPublished. This does not authorize a wrong ticket or
make R/P identical. After entering P, generic failures remain Unknown. The
modified-record test now explicitly distinguishes consuming an execution from
publishing anything; no old P1 test or security assertion was weakened.

The first full1341/184 cohort and26m39s integrated build passed, but final audit
then reproduced an idempotence defect1/1: calling the same storage publication
twice returned NotPublished after Published, manufacturing a contradictory
receipt for P1. The negative XML/log, all seven before-fix source files, initial
freeze and four build hashes are preserved (`receipt-cache-negative-*`,
`before-receipt-cache/`, `initial-build-manifest.json`). The store now retains
exactly one settled typed receipt bound to the exact reservation/candidate.
Repeated calls return that same knowledge without I/O; a claimed execution
without an outcome remains Unknown. Externally conflicting receipts still fail
closed in unchanged P1. No new authority, retry or persistent receipt is added.

Final full Debug JVM: **1342 tests /184 suites PASS**,0 failures/errors/skips,
**+62 over1280 P1**, NOT old P2's1264. New suites:38 storage,21 P1/storage
integration,3 independent-process tests. All181 inherited suites and their test
names/counts are unchanged (`jvm-delta-settled.json`); original96 coordinator tests PASS.
The focused158/4 suite cohort and full1342 cohort are distinct executions, not summed.
Authoritative copies: `jvm-focused-settled-*` and `jvm-full-settled-*`.
`source-freeze.json` is the final seven-path source, not the initial freeze.
No sleeps determine interleavings: CPU-only callbacks, manual timers, latches
and child-process handshakes order events; finite waits only bound a failed test.

The first counting invocation used Windows PowerShell5 and failed on hashtable
measurement. Its retry ran after the full suite had completed, so the early
`jvm-focused-final-*` label actually contains1341/184 full results, NOT a157
focused receipt. It remains preserved, not relabeled as focused evidence.
The unambiguous `jvm-full-final-*` copy records the initial1341 cohort; the
final settled receipts above supersede it. A source guard treated grep's no-match exit1
as a tool failure; it now checks untracked source via rg and validates0/1 explicitly.
No suppression or historical evidence replacement was used.

An early lint comparison before its XML existed is not a lint result. An ad-hoc
Release XML accessor also failed; its apparent PASS is invalid and not evidence.
The structured XML comparator with Stop-on-error, `compare-release.ps1`, is
authoritative. Neither harness error is an application defect or justification
for suppressing warnings. Final verified gate results follow.

## Final Automated Gates

All receipts below are under `C:/wg-pairing-storage-r1604/`.

| Gate | Final result / scope |
| --- | --- |
| Focused P1 + storage JVM |158/4 suites PASS,96 unchanged P1 +62 new; `storage-focused-settled.log`, `jvm-focused-settled-*`;1m20s |
| Full Debug JVM |1342/184 PASS,0 failures/errors/skips; `full-gates-settled.log`, `jvm-full-settled-*` |
| Inherited test-name/count equality |All181 P1 suites unchanged; +38 storage/+21 integration/+3 process; `jvm-delta-settled.json` |
| ktlintCheck |PASS, no suppression, final frozen source |
| lintDebug |0 errors/134 warnings; exact baseline multiset unchanged; `lint-debug-comparison.json` |
| lintRelease |0 errors/42 warnings; exact P1 Release issue keys unchanged; `lint-release-verified.json` |
| Debug, AndroidTest, helper, unsigned Release |PASS; final integrated run11m45s,165 tasks/38 executed; initial26m39s build is separate before-fix evidence |
| Existing source-set/DEX/manifest isolation |21/21 PASS, root/output adaptation only |
| P1 model isolation |34/34 PASS; parent and source assertion now require the frozen three-path P1 cohort unchanged |
| New storage/source integrity |44/44 PASS, including one bounded settled receipt; `storage-isolation.json` |
| New compiled storage isolation |10/10 PASS: DEBUG/Release candidate/evidence ownership, no fixture/modeled-sync shipping or privileged dependency; `storage-compiled-isolation.json` |
| Native symbol guards |6/6 PASS,3 Debug RT-TLS +3 Release Flight exclusion across three ABIs |
| Offline Flight/output smoke |40/40 PASS; `storage-offline-settled.log` |
| CTest / Android instrumentation / hardware |NOT EXECUTED; no native source delta; inherited26/26 CTest is historical, APK build is not device validation |
| Extra Release unit compilation |NOT rerun; prior180 diagnostics/11 unchanged tests remains FAIL, not repaired or relabeled |
| Preservation |Nine preexisting worktrees/535 dirty paths unchanged; P1 reservation checkpoint additionally clean at856bca7 |

Required build invocation, with JDK21 launcher/Kotlin toolchain17, Gradle8.12,
SDK35/min26, NDK27.0.12077973/CMake3.22.1, max-workers1/native parallelism2:

```text
gradlew.bat :app:ktlintCheck :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex :app:assembleRelease --max-workers=1
```

Existing compiler/deprecation/third-party native warnings remain visible. No
new Kotlin fixture warning survives the final run; no lint assertion is weakened.
The two source-set variants still use the same unavailable Release setup adapter.
The new standalone main classes ship but remain unwired; all host fixtures,
test subprocesses and `ModeledDirectorySync` are absent from the APKs. Existing
libadb AAR SHA256 remains
`62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB`.

## Final Artifact And Manifest Identity

Traceability is856bca7 + the seven final frozen source/test files -> the final
build -> four outputs below. No dirty old P2/research source is an input. HEAD
alone intentionally does not contain this uncommitted P2 increment.

| Output | SHA256 |
| --- | --- |
| Debug APK | `8B3E419FE08768DD3702D03CA1ADAB0CCA27BA8EDCDD56D22ABC709FBD627031` |
| AndroidTest APK | `0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F` |
| DirectShell DEX helper JAR | `DB4E40FB1D4E20BB9DA2F220B70A2CC25D829FA90495A130B08062AFA4C5758E` |
| Unsigned Release APK | `0A52592DE64A670EB07DAB77C379C400406CC74DB828F6150AB642F65074FD62` |

No output was installed. ABI closure remains arm64-v8a/armeabi-v7a/x86_64.
`final-manifest.json` records raw and LF-canonical identities/sizes for exactly14
paths: the seven application/test paths above plus these seven documents:

- `docs/FutureRoadmap.md`
- `docs/phase6_5/CURRENT_HANDOFF.md`
- `docs/phase6_5/CurrentNonRootDebt.md`
- `docs/phase6_5/DirectShellPairingReservationModel.md`
- `docs/phase6_5/DirectShellReleasePairingArchitecture.md`
- `docs/phase6_5/README.md`
- `docs/phase6_5/DirectShellPairingStorageIntegration.md` (new).

No tracked application/native/build/accepted-RFC path is modified relative to
856bca7. P2 index stays empty; all14 paths remain uncommitted on the isolated
branch. Only the two specifically authorized P1 commits were created. No push,
merge, reset, stash or cleanup of preserved worktrees occurred.

The preserved nine-tree inventory compares HEAD/branch/index and dirty path
content/size/mtime: original496 (492 hashes +4 metadata-only large artifacts),
old P2's17, review's5, Video's11 and hardware's6, plus clean frozen foundations.
The original P1 reservation branch is additionally clean at856bca7. Master032b592
and upstream9d0b4dc are unchanged. Phase7 R2 retains SHA256
`0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6`.
No Android device, trust, APK, network, volume, security or runtime state was
accessed or changed; no restoration is necessary or newly claimed.

## Scope And Remaining Gates

**Final classification: P2_STORAGE_PARTIAL_BLOCKED.** The component and its
bounded modeled P1 integration are IMPLEMENTED/SOFTWARE TESTED in the exact host
scope above. Real AES-GCM/file-content-force/no-replace NTFS operations and OS
exclusion are demonstrated. Real directory synchronization is NOT demonstrated:
the default Windows provider denies it before the recovery gate. Modeled sync
ordering and158/1342 passing tests cannot promote that guarantee. Therefore
P2_STORAGE_SOFTWARE_TESTED, Android HARDWARE VALIDATED, QUALIFIED and
PRODUCTION-READY are deliberately NOT claimed.

P2 overall remains PARTIAL_BLOCKED: real total-deadline connect/write/TLS/PAKE
cancellation/drain, authenticated endpoint/connection trust binding, Android NSD,
Keystore/platform storage qualification and composition are still missing.
Release adapter stays unavailable. No P3-P5, runtime registration, provider,
protocol/anti-replay/Session, media, buffering or Phase7 changes. No devices used.
The extra Release unit-test compile baseline FAIL180 diagnostics/11 unchanged
tests remains a separate limitation, not a fixed or passing gate.

**Smallest next bounded recommendation:** qualify an isolated Android private
storage boundary for directory synchronization and an existing nonexportable
AES256 Keystore wrapping key. Amend the narrow key-provider evidence contract
only with an explicit size/purpose/ownership check, not by dropping validation
or returning a fake key. Keep it unwired, require real app-private filesystem
and Keystore instrumentation under separate device authorization, and fail
Unsupported/Unknown without namespace/file atomicity or sync evidence. Acceptance
must repeat the same lease/no-replace/cancel/receipt/restart cases before any
pre-PAKE gate or Release availability can be enabled. This recommendation is NOT
executed; real transport and P3-P5 remain separate later gates. No further S9,
RF/media, provider or Phase7 investigation is opened. Phase6.5 remains incomplete.
