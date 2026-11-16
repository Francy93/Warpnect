# RFC-006J Atomic Publication: Next Bounded Implementation Prompt

**PROPOSED / REQUIRES SEPARATE USER AUTHORIZATION / NOT EXECUTED.**
2026-10-27. Copy-ready next increment. This prompt authorizes nothing by itself.
Its scope, if explicitly approved, is a **test-only native feasibility harness**,
not production storage integration or real ADB pairing. It is intentionally the
smallest next implementation because untrusted_app execution is not yet proven.

## Mission

Prove or falsify the public Android API30 bionic operation
`renameat2(dirFd, sourceName, dirFd, destinationName, RENAME_NOREPLACE)` in the
ordinary app security domain on one disposable AOSP/API30 emulator.

Read `DirectShellPairingAtomicPublicationFeasibility.md` and the byte-preserved
`DirectShellPairingAndroidStorageValidation.md`. Do not assume their uncommitted
documents are in HEAD. Preserve the13 protected worktrees and all failure evidence.
Start a fresh isolated worktree from
`ce2466240784c21e1698f1b06faed466087a57a3`, with source checkpoint
`a7fefaa28681c0612ddfdc20e4cf0421849b312d`. Suggested branch:
`codex/phase6-5-pairing-native-publish-proof`. Selectively copy only reviewed
documentation needed for provenance, not a dirty source cohort. No automatic
commit/push/merge/reset/stash/clean.

Keep overall P2_PARTIAL_BLOCKED. The previous Android result is15 PASS/2 FAIL/
9 blocked, not a passing storage baseline. Do not repeat the26-case campaign
before replacing its hard-link prerequisite in a later separately approved task.

## Explicitly Permitted New Test Artifact

Add the minimum standalone C/C++ test source and Kotlin instrumentation bridge,
using the already installed NDK27.0.12077973 and public libc API30 declaration.
No external dependency, private reflection, hard-coded syscall number, libc
patch, generic syscall wrapper, executable helper or shell-command API.

Compile the test library for arm64-v8a/armeabi-v7a/x86_64 at API30. Package it
ONLY in the AndroidTest artifact. It must be absent from target Debug, helper
and Release artifacts. Do not edit the production CMake graph, SCL JNI/ABI or
put the library in main/debug jniLibs. Test-only packaging configuration may be
changed minimally if necessary, with an exact manifest and isolation evidence.
If the toolchain cannot isolate it without a production native/build change,
stop and report the precise additional authorization needed.

Keep application minSdk26/target35/compile35 unchanged. An API30 strong symbol
must never be added to a library loaded by min26 app paths. Load the proof library
only from an explicit API30+ test operation, not a constructor/application/static
initializer. Do not claim that packaging a symbol proves actual call permission.

The native function may accept only a borrowed, validated test-directory FD and
an enum selecting fixed candidate-a/candidate-b names; destination is fixed.
No arbitrary paths, file writes, payload returns, key access, thread creation or
logging in the native entry. The Java test owns and closes the descriptor; the
native function never closes it. Return only bounded primitive result/errno
metadata, copying errno immediately after the call. Preserve JNI exceptions and
process-death uncertainty without exposing arbitrary Throwable text.

## Isolated Runtime Preflight

Use only a newly created disposable fixture or the previous disposable API30
environment after proving it has no user/production data. Do not use physical
A41/S9/Tablet/other devices or protected Root AVDs. No root, su, Shizuku, adb root,
SELinux change, permission grant, target downgrade, real Wireless Debugging
pairing, trust mutation or privileged probe.

Use private AVD/user/key directories and a dedicated ADB server with no physical
USB selection or wireless auto-connect. Every command targets the verified
emulator serial explicitly; do not restart or repurpose the default ADB server.
Record fingerprint/API/ABI, kernel/mount information, image/emulator identity,
APK hashes/signatures and initial empty test namespace.

Crucially, record Process.myUid/native UID and the executing test process's own
SELinux context. It must be the target application's normal untrusted_app domain,
not UID2000/run-as_app or a shell/native executable masquerading as app evidence.
Inspect target Application/providers for lazy-only startup. Stop before testing
if a helper, Host acquisition or live Session could be started.

Use only a UUID-owned directory under app noBackupFilesDir, outside
directshell-pairing-v1, with fixed synthetic byte patterns. No AES key/certificate,
pairing code, real identity or production namespace is necessary. Sync complete
data and the candidate directory before each synthetic authorization boundary;
close all writer FDs and preserve digests/ownership. No raw payload logging.
Source symlinks, non-regular files, changed candidate data or foreign ownership
must fail caller prevalidation without entering P. A rename of an invalid source
is not an acceptable credential publication, even if the raw syscall succeeds.

## Bounded Proof Cases

Predefine at most8 cases,60s each/8min total; one emulator image and one native
primitive. Stop on a security/ownership violation, unexpected target mutation,
SIGSYS, unsupported required operation or unproven drain. Preserve failures;
never retry with a weaker call or altered platform policy.

1. Destination absent: complete candidate becomes the record, exact data digest
   unchanged, source absent, known success. Perform real directory fsync.
2. Existing regular destination: exact EEXIST/no replacement, old bytes/inode
   preserved, candidate retained. Include an existing symlink/foreign entry as
   subcases confined to the owned synthetic fixture.
3. Two pre-synchronized distinct candidates, two cooperating contenders released
   by an explicit barrier: exactly one winner and one EEXIST; winner digest in
   record, winner source consumed, loser source retained. No sleep-based ordering.
4. Bounded reader observation: it sees absent or one complete valid record,
   never a partial/invented winner. Verify final digest and source effects.
5. Controlled abort before calling the primitive: no record, candidate retained;
   no claim of testing the real P1 cancellation state machine.
6. After known P, before directory S: inject a test-only skipped/failing-sync
   observation, not a real filesystem sabotage. Record Published/Unconfirmed;
   preserve record and distinguish actual syscall success from injected S failure.
7. Reopen using a fresh reader: validate record-only, candidate-only and known
   uncertain combinations; no adoption, deletion, resumed consent or Host side
   effect. This is reopen inspection, not a physical crash/power-cut proof.
8. Real synchronization and resource drain: directory fsync must actually return,
   owned FD/worker counts reach zero and no operation remains. Close/ownership
   ambiguity blocks cleanup; it is not hidden by a logical timeout.

Record exact native return/errno where observed. ENOSYS, unsupported flags/FS,
seccomp or SELinux denial keep the mechanism unavailable, never invoke rename,
link, copy or marker fallback. EEXIST is a documented collision result; arbitrary
IO exceptions or absent receipts are Unknown, not proof of no publication.
If a process is killed by the platform, preserve that evidence and stop that path.
Do not broaden to another OS/backend to obtain PASS.

Two threads test kernel arbitration but do not qualify independent-process
writer-lease ownership. Where existing safe process-fixture infrastructure is
absent, explicitly leave that acceptance gap; do not add a privileged service,
shared UID or production manifest component to hide it.

## Frozen Contracts And Deferred Production Delta

Do NOT modify production credential filesystem/storage/codec/Keystore, P1,
Host setup, Release availability, runtime/provider registry/planner, Session,
SCL/native ABI, security, media parameters or Phase7. No new record schema.
There is no real pairing/backend integration and no second lifecycle owner.

The later possible production delta, not authorized by this prompt, would be:
one API30-gated private publisher + explicit consumed-source result + precise
cleanup/restart replays. Successful rename consumes identity.pending; blindly
running the old hard-link cleanup would be wrong. Do not implement that delta
even if this isolated proof passes. Markers/SQLite need separate schema approval.

## Verification And Cleanup

Freeze sources/test library/APKs and all ABI symbols/ELF dependencies. Build and
execute only the focused test-only proof. Confirm the exact new class/library
is in AndroidTest and absent from target/helper/Release, with unchanged production
source and artifacts. Run applicable test-source formatting/static checks and
git diff hygiene; a full media/native/JVM campaign is unnecessary unless its
inputs were changed (which would require stopping for scope review first).
Do not relabel historical1383 JVM or15/2/9 results as fresh execution.

Preserve the unrelated Release unit compilation FAIL. Do not fix it in this task.
No physical qualification, universal Android permission or power-loss claim.

Cleanup only verified owned synthetic files after all FDs/threads drain. Stop the
owned emulator/server, remove only its temporary keys, and verify protected
worktrees, default ADB identity/server and personal AVDs remain unchanged. Any
uncertain cleanup is reported, not covered by broad deletion or another reboot.

## Deliverable And Stop

Produce a focused proof receipt (or explicitly scoped successor section of the
feasibility report), with source/artifact hashes, actual app domain, each result,
native errors, collision/source effects, sync/drain facts and limits. Distinguish:

- NATIVE_NO_REPLACE_PRIMITIVE_PROVEN_ON_EXACT_FIXTURE, only when the relevant
  cases actually pass; not complete storage/P2/platform qualification.
- NATIVE_NO_REPLACE_PRIMITIVE_UNSUPPORTED_OR_BLOCKED, for denied/missing guarantees.
- NATIVE_NO_REPLACE_PRIMITIVE_UNVERIFIED, if no safe execution was possible.

Leave new work uncommitted for review. Keep overall P2_PARTIAL_BLOCKED and Release
unavailable. Recommend the next minimum integration decision only; do not execute
it. Stop after the bounded proof, cleanup and report, without transport/P3-P5.
