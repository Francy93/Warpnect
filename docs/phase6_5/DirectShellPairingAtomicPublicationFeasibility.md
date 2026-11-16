# Android Atomic No-Replace Publication Feasibility

2026-10-27. RFC-006J architecture review only; no production, test or native
implementation changes. **P2_ATOMIC_PUBLICATION_FEASIBILITY_UNRESOLVED**:
the comparison and a coherent conditional strategy are complete, but the
security-critical untrusted_app execution proof is still missing. This is not
P2 storage/platform validation. Overall **P2_PARTIAL_BLOCKED**; Release unavailable.

## Baseline And Preservation

Isolated branch `codex/phase6-5-pairing-atomic-publication-review`, worktree
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-atomic-publication-review/Warpnect`.
HEAD is Android storage docs checkpoint
`ce2466240784c21e1698f1b06faed466087a57a3`; source/test checkpoint
`a7fefaa28681c0612ddfdc20e4cf0421849b312d`. P1 reservation checkpoints
`7f3e39f036db0f833efe843ccb243b82210f8e20` /
`856bca744cc688df22e35b1de6b1303100ea66da` remain ancestors and unchanged.
Application/test cohort stays
`27CAD08A12DC2FA832A67528FB67317C14DF27BAF31CC7A1B9FDDB72F2966416`.

The latest [platform report](DirectShellPairingAndroidStorageValidation.md) was
uncommitted in the previous validation worktree, not present in HEAD. Its only
transfer here is a byte-identical report copy, raw SHA256
`7B0775BFE048D04AEFC52A5DF77265340431370FC793147859B66EE9A79B942A`.
The previous eight dirty documentation paths, failure receipts and source are
unchanged. No mixed source or other validation edits were imported.

Read inputs: current codec/filesystem/Android adapters/storage, instrumentation,
P1 coordinator/contract/model, accepted RFC-006I/J, current roadmap/handoff,
and the preserved durable-commit review/DRAFT addendum in its separate worktree.
The old review's CPU-only publication callback is historical, superseded by the
approved five-delta reservation model. Accepted RFC text and the still-PROPOSED
addendum were not silently rewritten. No second enrollment owner is proposed.

Receipts: `C:/wg-pairing-atomic-publication-r1607/`. Initial inventory covers13
protected worktrees: original research, G1-G5, P1, P2, Android checkpoint,
validation, durable review, Video and hardware. No commit, push, merge, reset,
stash, clean, Android/device access or emulator launch in this task.

## Reconstructed Failure

Inherited evidence, NOT rerun: disposable AOSP11/API30 x86_64, fingerprint
`Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys`,
app UID10130, untrusted_app, SELinux Enforcing, app-private ext4 /data on dm-5.
26 planned methods:15 PASS/2 FAIL/9 blocked,17 actually executed. Keystore,
recovery file/directory sync and same-process exclusion passed. There was no P.

The actual path is:

```text
begin -> lease + encrypted recovery + sync
prepare -> complete authenticated pending candidate + sync
P1 R -> exact candidate one-shot reservation
Storage.publish -> reservation.claim -> read/hash/decrypt/binding checks
publicationAttempted = true
io.linkNoReplace(record,pending) -> Android Os.link(pending,record)
SELinux denied { link } -> sanitized UNKNOWN -> UNKNOWN/unconfirmed receipt
store close/drain -> reconciliation, never false Published
```

`DirectShellCredentialStorage.publishClaimed` distinguishes EEXIST and explicit
Unsupported from ordinary unknown exceptions. Android's `link` wrapper maps only
EEXIST specially; the denied operation reaches its generic UNKNOWN category.
No numeric errno was captured. Do not relabel this historical failure EACCES,
EPERM or known NotPublished. An independent no-coordinator publication case also
failed, separating this boundary from P1 cancellation. The after-link callback
never established an after-P cancellation test.

The kernel receipt is `C:/wg-pairing-storage-platform-r1606/failure-logcat.log`:
`avc: denied { link } ... name="identity.pending" ... tclass=file permissive=0`.
AOSP11's policy independently forbids file hard links by untrusted apps, consistent
with that observation. This documents AOSP11 policy, not empirical coverage of
all Android/OEM versions. [AOSP app neverallows](https://android.googlesource.com/platform/system/sepolicy/+/android-11.0.0_r1/private/app_neverallows.te).
Calling linkat or wrapping the same hard-link operation in native code is not a
solution. No privilege, target-SDK downgrade or SELinux bypass is acceptable.

## Invariant Floor

- A complete, authenticated encrypted candidate is immutable and synchronized
  before R. Its exact ticket/revision/owner and digest remain bound to the grant.
- P is an atomic comparison/publication at the store, not an earlier existence
  check. Every existing/foreign/corrupt/uncertain entry blocks replacement.
- Revision0 ->1 only; no overwrite, truncate, delete/retry, fallback copy or
  automatic adoption. Competing publishers cannot both win.
- R is CPU-only; P/S/D are worker-owned IO/knowledge boundaries. F waits for the
  existing coordinator's final environment checks and joint physical drain.
- Stop before R prohibits P; stop after R cannot promise rollback of a claimed
  operation. Unknown effect/sync and missing D retain the occupied namespace.
- No raw key/code/payload in receipts/logs. Existing AEAD/AAD/format, four-name
  namespace, app-private ownership and stable OS writer lease are preserved
  unless a schema change is separately approved.
- Existing120s consent/60s work/3s cleanup observation budgets remain; IO may
  outlive a logical deadline, never under the coordinator/UI monitor.
- No Host, provider/planner, runtime/helper, Session or media authority follows
  from a file, marker, library load or feasibility result.

The stable lease excludes cooperating writers, not compromised same-UID code.
No primitive stops a malicious same-UID actor deleting or modifying data after P.
No write alias may remain after preparation. Atomic no-replace still protects an
existing destination at the operation itself; it is not whole-filesystem integrity.

## Candidate Matrix: Semantics

PROVEN means a scoped observed fact (historical runtime or current local static
inspection, explicitly identified). DOCUMENTED is authoritative semantics/source,
not execution. UNVERIFIED is a missing platform observation. REJECTED is failure
to satisfy the unchanged contract, not a claim that an API is generally unusable.

| Candidate | API/access | Atomicity and target collision | Contenders | Assessment |
| --- | --- | --- | --- | --- |
| A: Files.move(ATOMIC_MOVE) |Public Java API26+ |Atomic move where supported; target replacement is provider-dependent; no portable NO_REPLACE option |A replacement-capable rename can let later callers replace the first result |REJECTED as the current P primitive |
| B: Os.rename / renameat |Public Java rename API21; public native rename family |Same-filesystem rename has atomic visibility, but can replace target |Both calls may succeed successively, last writer replaces |REJECTED by itself |
| C: renameat2(RENAME_NOREPLACE) |Public NDK/bionic API30; no public Java binding in compileSDK35 |Documented atomic no-replace, EEXIST at collision; requires kernel/filesystem support |One successful creation at the destination, others fail without replacement |Preferred conditional design; Android runtime UNVERIFIED |
| D: exclusive empty commit marker + immutable candidate |Public Os.open(O_CREAT|O_EXCL), no native addition needed |Marker creation is exclusive; complete immutable candidate remains separate |One marker winner, but an empty marker cannot choose between different candidate identities |PROPOSED schema alternative, not a drop-in replacement |
| E1: AtomicFile / preferences |Public framework APIs |Replace/update/recovery contracts, not a no-replace revision CAS |Needs additional locking; does not supply required CAS |REJECTED for this contract |
| E2: encrypted SQLite row with UNIQUE revision |Public SQLite API; platform native engine |Transactional row commit/uniqueness can supply old-or-new visibility |Database locking/unique key arbitrate writers |Documented alternative, disproportionate schema/ownership delta; not selected |

The Java contract permits replacement with ATOMIC_MOVE and ignores other copy
options. Android11's UnixCopyFile atomic branch directly calls rename; the
non-atomic branch checks target then calls rename, leaving the check/use issue.
An exclusive cooperating-writer lease can serialize such a protocol, but it does
not turn rename into the currently required no-replace store operation. Accepting
that weaker/additional assumption would need explicit contract review, including
independent-process exclusion. No such reinterpretation is approved here.
[Java move contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)),
[Android11 provider source](https://android.googlesource.com/platform/libcore/+/android-11.0.0_r1/ojluni/src/main/java/sun/nio/fs/UnixCopyFile.java).
The public Android rename entry has no no-replace flag.
[Android Os API](https://developer.android.com/reference/android/system/Os#rename(java.lang.String,java.lang.String)).

AtomicFile is an update helper with its own recovery/backup behavior, not a
credential-revision store; its synchronization is not a substitute for writer
ownership. [Android AtomicFile contract](https://developer.android.com/reference/android/util/AtomicFile).
SQLite could store only ciphertext, but adds a database/journal lifecycle and
changes the fixed namespace, readers, durability evidence and drain ownership.
It is not inherently insecure; it is larger than a qualified rename primitive.
[SQLite atomic commit](https://www.sqlite.org/atomiccommit.html),
[constraint conflict policies](https://www.sqlite.org/lang_conflict.html).
Direct CREATE_NEW followed by writing credentials, ordinary copy, hard-link
variants and directory tricks that can replace an existing empty destination are
not substitutes for atomic publication of the already complete record.

## Candidate Matrix: Persistence And Scope

| Candidate | Source/lifetime after success | S/crash handling | Recovery/schema | Native/scope cost |
| --- | --- | --- | --- | --- |
| A/B |Source normally disappears |Pre-sync contents; sync changed namespace; sync failure not rollback |Current envelope retained, but collision guarantee insufficient |No extra Java dependency; unacceptable unchanged semantics |
| C |Pending name consumed, same complete inode becomes record |Pre-sync pending+namespace; same-directory rename P; directory S; retain recovery on uncertain S |Existing encrypted v2 record and restart classifier largely reusable; cleanup must account for consumed pending |One private, API30-gated platform primitive plus scoped cleanup delta; runtime proof first |
| D |Pending becomes permanent backing data; marker must remain |Data+namespace before R; empty marker P, marker/parent sync for S; missing/uncertain combination blocks |Marker meaning, permanent pending role, reader/versioning and old artifacts require explicit schema approval |Public-only but greater semantic delta than C |
| E1 |Update/backups managed by helper |Helper recovery can mutate artifacts |Conflicts with explicit retention/reconciliation |Rejected, do not wrap another lifecycle owner |
| E2 |Ciphertext is canonical database row |Qualified transaction/journal/sync settings needed |New persistent schema/files/limits and connection ownership |No new Gradle dependency necessarily, but major adapter/test delta |

All viable options still need sync of newly created parent/child namespace, real
file data synchronization and truthful descriptor/lease drain. Atomic visibility
does not prove physical power-loss durability. Cross-filesystem fallback is
forbidden. SQLite or markers do not eliminate these responsibilities.

## Native Candidate: Evidence And Limits

Current local observations, NOT Android execution:

- Installed NDK27.0.12077973 stdio.h declares renameat2 with INTRODUCED_IN(30)
  and RENAME_NOREPLACE; no hidden constant or hand-coded syscall number is needed.
- Six ELF stub inspections: armv7/arm64/x86_64 API30 libc exports
  `renameat2@@LIBC_R`; the corresponding API29 stubs do not. This proves local
  link-target availability only. `toolchain-evidence.json` records paths/hashes.
- javap on the project's SDK35 public Os class exposes rename, not renameat2.
  Reflection into libcore is neither necessary nor permitted.
- Android11 bionic declares the wrapper for all relevant architectures and exports
  it in LIBC_R. [Bionic syscall list](https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/libc/SYSCALLS.TXT),
  [API symbol map](https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/libc/libc.map.txt).

Linux documents RENAME_NOREPLACE and filesystem dependence, including ext4 support
from3.15; Android API number alone does not establish a vendor kernel or filesystem
implementation. Return-success consumes the old name; documented EEXIST collision
preserves the existing target. No actual call/errno was observed in this task.
[rename family semantics](https://man7.org/linux/man-pages/man2/rename.2.html).

The AOSP11 app seccomp build uses the bionic syscall list (which contains renameat2)
minus blacklists plus allowlists. Neither fetched blacklist removes renameat2.
The generator and build inputs therefore document intended AOSP app availability,
not the actual installed filter on the previous image or an OEM device.
[App filter build](https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/libc/Android.bp),
[generator](https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/libc/tools/genseccomp.py).
Generated per-ABI policy files are build outputs: attempted source URLs returned
404, not evidence that a syscall is disallowed. No filter was bypassed or changed.

AOSP11 app_data_file create permissions include rename; its separate neverallow
forbids link. This is favorable source evidence, not a runtime renameat2 permission
test. Exact directory add/remove-name/file-rename permissions, mount support,
seccomp behavior and OEM deviations remain qualification gates.
[App policy](https://android.googlesource.com/platform/system/sepolicy/+/android-11.0.0_r1/public/app.te),
[permission macros](https://android.googlesource.com/platform/system/sepolicy/+/android-11.0.0_r1/public/global_macros).

Do not strongly link an API30 symbol into the existing min26 SCL library and rely
on a late Java if-check: loading that library could fail on older OS releases.
The future proof should be a distinct test-only API30 library. Any later product
library would require explicit guarded loading, all-ABI checks and a min26 load
regression, with global minSdk26 unchanged. Public Wireless Pairing remains30+;
that does not authorize breaking other providers or Client on26-29.

## Marker Alternative: Why Not Select It Now

A zero-length CREATE_EXCL marker can be a complete marker at creation, provided
exactly one immutable authenticated candidate already exists and is the only
possible backing identity. It must contain no later-written bytes needed to make
it valid. Otherwise creation exposes an incomplete decision. Publishing arbitrary
marker contents after opening the file merely recreates the original problem.

A marker cannot by itself bind which of two different candidates won. A viable
design must retain the unique candidate/lease, validate its ticket/digest/AAD,
forbid replacement, define marker ownership and versioning, and block every
ambiguous old/new artifact combination. A new namespace cannot silently bypass
old uncertain credentials. Pending could no longer be deleted as redundant after
P. Existing readers expect identity.record to be encrypted v2 data, not an empty
marker: interpreting it differently requires approved schema/contract changes.
No marker prototype or format change is introduced. Public O_EXCL file creation
was already exercised in the prior recovery path; that is not proof of this
proposed multi-artifact transaction.

## Cancellation, Crash And Receipt Review

The conditional C operation reuses the same owner/lease and encrypted candidate:
recovery sync -> pending sync -> CPU-only R/one-shot claim -> renameat2 P ->
directory S -> typed receipt -> descriptor/transport/code-copy D -> P1 F.

| Boundary/event | Required interpretation; no new behavior is implemented |
| --- | --- |
| Stop before R |No reservation/P; retain any recovery effects, never resume consent |
| Stop after R before claim |Existing abstain ordering remains authoritative |
| Stop racing a claimed syscall |No rollback promise; one P attempt, bounded ownership retained |
| Existing target / documented EEXIST |Known no-replace loser; leave both target and its identity unchanged; retain prepared/recovery material for reconciliation |
| Native returns success |P known, source consumed; not yet S or D |
| P succeeds, directory fsync fails |Published/Unconfirmed, retain recovery, no destructive rollback |
| Exception/worker death after call entry, result unknown |Unknown P/S, no replacement attempt; do not guess from generic IO error |
| Crash before P |Recovery/pending may remain; restart inspection blocks, no automatic grant |
| Crash immediately after P |Record may exist, pending may be absent; authenticate/read only, no automatic adoption |
| Crash before S/receipt |Actual persistence/outcome may be uncertain; reconciliation, not consent restoration |
| Cleanup fails after P/S |Preserve committed record; report independent cleanup knowledge |
| D incomplete |Slot stays occupied regardless of receipt/timeout |
| Late/duplicate receipt |Existing P1 exact reservation/idempotency/finalization applies; cannot overwrite a newer outcome |

Known native errors need an explicit proof-based mapping. EEXIST has the documented
collision meaning; unsupported syscall/flags/filesystem or permission denial must
close eligibility, without a weaker fallback. Arbitrary failures (especially IO
or lost acknowledgement) cannot all be declared NotPublished. Capture errno
immediately, before JNI allocation/other calls, with no sensitive exception text.
The proof must distinguish returned error from process death/SIGSYS/no receipt.

Current cleanup unconditionally removes pending after successful link. A successful
rename consumes it: reusing that cleanup unchanged would falsely report cleanup
failure. A future narrow publication result must explicitly identify source
consumed/retained, or configure a fixed strategy with equally precise cleanup.
Do not hide rename behind the misleading linkNoReplace name. Existing restart
inspection already accepts record with/without pending, but needs deterministic
replays at the consumed-source boundaries. No new coordinator or protocol is needed.

For completeness, the unselected schema alternatives must not escape these
boundaries either (all below are design analysis, NOT execution):

| Event | Empty-marker proposal D | SQLite proposal E2 |
| --- | --- | --- |
| Stop before R |No marker creation; retain prewritten identity |No commit operation; retain staged ciphertext/recovery |
| Stop after R / during P |One marker attempt only; no removal promise |One transaction outcome; cancellation is not proof of rollback |
| Existing committed target |O_EXCL loses without replacement; unknown old entries block |Conflict must abort/ignore, never REPLACE; existing/corrupt store blocks enrollment |
| P known, S unknown |Marker may be visible; retain both marker/candidate, require reconciliation |Transaction may have committed despite lost outcome; preserve DB/journal, require reconciliation |
| Crash before P |Candidate/anchor only; no consent resume |Prepared ciphertext or incomplete DB setup; no transaction resume |
| Crash after P before receipt |Marker plus authenticated candidate needs a new-schema reader |Committed row versus recovery journal needs qualified DB recovery; file presence is not workflow completion |
| Cleanup/drain failure |Candidate is backing data, not redundant; marker FD/lease must drain |Never manually delete journal/WAL to force success; connection/lease drain must be proven |

SQLite must store only the pre-encrypted candidate, use a non-replacing unique
revision constraint and qualified transaction/synchronization configuration.
Its API defaults, connection pool waits, journal/WAL bounds and automatic recovery
would need their own mapping to S/D and fail-closed enrollment inspection. These
additional obligations, and D's changed record meaning, are why neither is silently
substituted for C. A/B remain rejected primitives under the unchanged contract;
their cancellation handling cannot repair a missing no-replace guarantee.

## Proof Gate And Next Implementation

No optional emulator proof was executed. A meaningful test of C requires a new
native test artifact/JNI entry point; no such harness already exists. The current
task requires stopping before that addition. A UID2000 shell test, host rename
test, libc symbol inspection or Java rename test cannot substitute for executing
RENAME_NOREPLACE in untrusted_app. We did not run rejected overwrite primitives
or another marker/codec/framework campaign merely to obtain a passing result.

Preferred **conditional** strategy is C: public bionic renameat2, fixed same-dir
names, RENAME_NOREPLACE, existing lease/AES/v2/recovery/receipts, no fallback.
This is a recommendation, not implemented or qualified. The critical missing
proof is ordinary app-domain execution, including collision/complete-data/source
consumption and real directory sync on the exact fixture. Therefore the completion
classification remains UNRESOLVED, rather than treating an API symbol as Android
support. No production implementation is proposed for automatic execution.

The [next bounded prompt](DirectShellPairingAtomicPublicationImplementationPrompt.md)
is deliberately **test-only native proof first**, requiring separate user approval.
It adds no production publisher. After a successful proof, a later separately
approved integration could add the one platform primitive, a narrow source-effect
result/cleanup delta and targeted tests; P1, codec, namespace and Session remain.
If native execution is denied/unsupported, return the exact limit and stop. Marker
or database schema changes require a distinct explicit RFC-006J decision, not a
fallback inside the same writer.

## Acceptance And Verification

The future proof matrix covers absent/existing target (including symlink/foreign
entry), complete immutable data, two distinct candidates/one winner, pre-P abort,
post-P/pre-S fault, reopen inspection, directory fsync and actual FD drain.
Use explicit barriers, no sleeps as ordering evidence. Prove old bytes untouched,
winner source consumed/loser source retained and no reader seeing partial valid
data. Same-process contenders are not independent-process writer-lease qualification.
Process restart/kill is not power-cut evidence. Stop on unexpected mutation,
unknown path/UID ownership, bootstrap, SIGSYS or unproven drain. No root/SELinux,
target downgrade, generic syscall API, real credentials or physical-device fallback.

Fresh current verification is documentation/source integrity only: six local NDK
stub inspections, public SDK method audit, fetched primary-source content/hashes,
diff/whitespace/link checks and protected-worktree comparison. No CTest/JVM suite/build,
instrumentation or Android runtime test was executed. Prior1383 JVM,199 focused,
40 replay and15/2/9 platform results stay historical with their original scope.
The preexisting extra Release unit compile FAIL remains separate and unrepaired.

Final changes: this report, next prompt, byte-identical platform-report copy and
handoff/roadmap navigation only. Index empty, all uncommitted. Thirteen other
worktrees, Phase7 R2, old reports/receipts and APKs are preserved; the original
four large artifacts retain the established metadata-only inventory policy.
No production eligibility, pairing, security, SCL, media or provider behavior changed.

Closure receipt:57 static/provenance/manifest/link checks PASS, plus the13-worktree
before/after comparison and diff hygiene PASS. `final-review-manifest.json` records
the exact five documentation paths/hashes, unchanged17-path source cohort and
four previous artifact identities. `toolchain-evidence.json` is local ELF/header/
SDK inspection, not a newly compiled native probe. Public-source archives and
their hashes are under the external `sources/` receipt directory, not in Git.
