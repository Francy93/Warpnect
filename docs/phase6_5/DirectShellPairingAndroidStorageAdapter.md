# DirectShell Pairing Android Storage Adapter

Successor2026-10-27: [production native publication integration](DirectShellPairingNativePublicationIntegration.md)
adds an API30-only private no-replace publisher and consumed-source cleanup.
All26 existing plus8 additional methods now execute PASS on one isolated AOSP
API30 x86_64 fixture, with1435 full/251 focused JVM and required software gates.
The previous no-runtime receipt below retains its original scope; the old denied
link campaign is preserved separately. This is not universal Android/physical/
power-loss qualification. Independent-process Android lease, production key and
real transport/backend remain pending; overall P2_PARTIAL_BLOCKED, Release
unavailable. Keystore/AEAD/location factory and P1 code are unchanged.

2026-10-27. RFC-006J bounded storage/platform increment. No device or emulator
access, real pairing, NSD, application wiring, Release activation or key provisioning.
**P2_ANDROID_STORAGE_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED** within the explicit
API/failure boundaries below. Overall **P2_PARTIAL_BLOCKED**. Platform
validation remains **P2_ANDROID_STORAGE_PLATFORM_VALIDATION_PENDING**.

## Baseline And Provenance

Branch `codex/phase6-5-pairing-android-storage`, workspace
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-android-storage/Warpnect`.
HEAD remains P1 documentation checkpoint
`856bca744cc688df22e35b1de6b1303100ea66da`; P1 source/test checkpoint is
`7f3e39f036db0f833efe843ccb243b82210f8e20`. No new commit, staging, push or merge.

Only the seven application/test paths in the previous
[storage receipt](DirectShellPairingStorageIntegration.md) and that receipt were
transferred from the preserved, uncommitted storage worktree. All fourteen prior
manifest entries and four prior artifacts were hash-verified before transfer.
The seven initial copies were byte-identical; four old test/helper paths remain
LF-canonically identical after this increment. Three core storage paths are
explicitly amended below. No old endpoint/libadb proof, Video observer, mixed
research source or historical dirty documentation cohort was imported.

Exact raw/LF hashes and origins are in
`C:/wg-pairing-android-storage-r1605/transfer-provenance.json`; final source closure
is `source-freeze.json` in that receipt directory. The prior1342 JVM/158 focused
and Windows storage results belong to r1604, not fresh Android validation.
The accepted RFC and separate PROPOSED security/lifecycle review stay unchanged.

## Exact Application Manifest

All paths below are under `app/src/`, in package
`io/warpnect/platform/privileged/directshell/pairing/`.

| Source set | File | Delta/provenance |
| --- | --- | --- |
| main/java | DirectShellCredentialCodec.kt | Selective r1604 copy; AEAD port, same v2 bounded format/AAD, plaintext wipe on failure |
| main/java | DirectShellCredentialFileSystem.kt | Selective r1604 copy; narrow platform hooks, private lock attributes and drain knowledge |
| main/java | DirectShellCredentialStorage.kt | Selective r1604 copy; opaque AEAD use, platform status mapping, IO-drain fence, bounded scan |
| main/java | DirectShellCredentialAead.kt | New portable bounded JCA AES-GCM port; no key export in encryption/decryption |
| main/java | AndroidDirectShellCredentialFileSystem.kt | New public Android OS operations, evidence and fixed private paths |
| main/java | AndroidDirectShellCredentialKeyStore.kt | New existing-key-only AndroidKeyStore/KeyInfo adapter |
| main/java | AndroidDirectShellCredentialStorageAdapter.kt | New explicit worker-side location factory; not a provider registration |
| test/java | DirectShellCredentialAeadTest.kt | New9 host JCA tests |
| test/java | AndroidDirectShellCredentialKeyStoreTest.kt | New10 injected key-policy tests; not Android execution |
| testDebug/java | AndroidDirectShellCredentialFileSystemTest.kt | New22 host tests; Android ownership/directory sync modeled, API26 rejection |
| testDebug/java | DirectShellCredentialStorageTest.kt | Preserved38 r1604 tests/fixture |
| testDebug/java | DirectShellPairingStorageIntegrationTest.kt | Preserved21 real P1/controlled-worker tests |
| testDebug/java | DirectShellStorageProcess.java | Preserved child-JVM fixture, not shipped |
| testDebug/java | DirectShellStorageProcessTest.kt | Preserved3 real host process/lease tests |
| androidTestDebug/java | AndroidPairingStorageFixture.kt | New isolated app-private fixture and synthetic test-key provisioning only |
| androidTestDebug/java | AndroidPairingStorageInstrumentationTest.kt | New18 platform methods, compiled but NOT executed |
| androidTestDebug/java | AndroidPairingStorageLifecycleInstrumentationTest.kt | New8 P1/platform lifecycle methods, compiled but NOT executed |

No existing tracked application source, P1 coordinator/contract/test, Gradle,
manifest, SDK target, dependency, native or media file is changed. Documentation
navigation updates are limited to this report, the prior storage receipt,
CURRENT_HANDOFF, Release pairing architecture, FutureRoadmap, phase README and
CurrentNonRootDebt. Phase7 R2 is unchanged/proposed.

## Android Filesystem Contract

The explicit `forApplication` factory obtains application context, verifies UID,
rejects a symlink/non-directory final no-backup path, canonicalizes it and verifies
it is a direct child of the canonical application data directory. Resolution is
`RESOLVED_NOT_QUALIFIED`, never provider support. It does not query/create a key or
create the credential namespace. Android's `noBackupFilesDir` getter itself may
create its base directory; this is not identity creation or enrollment consent.

The only namespace is `directshell-pairing-v1`; only `writer.lock`,
`identity.recovery`, `identity.pending`, `identity.record` are admitted. The scan
stops at the fifth entry. Record size is at most65536 bytes; reads allocate at
most65537 bytes to detect overflow. No history or arbitrary user path exists.
Namespace0700, record/lock0600, owner UID, regular-file/directory type and size
are checked. `lstat`/`O_NOFOLLOW` reject symlinks. Open descriptors are checked
against entry device/inode and UID before data/sync work. No lock inode is deleted.

The stable NIO `FileChannel.tryLock` lease has no wait/poll/retry. An in-process
canonical-path registry handles same-JVM overlapping ownership; OS exclusion is
required between cooperating processes. POSIX attributes are supplied at Android
lock creation. Lock acquisition/crypto/IO never run under P1's monitor. This is
not protection against compromised or non-cooperating same-UID writers replacing
paths, deleting aliases or ignoring the lease. No such security claim is made.

Public `android.system.Os` operations provide exclusive file creation, bounded
read/write, data fsync, hard-link no-replace publication and redundant-file removal.
`EEXIST` preserves the prior record. Unsupported link has no copy/rename/overwrite
fallback. The public SDK lacks `O_DIRECTORY` and `Os.unlink`: directory opens use
`O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC`, followed by fstat/type/inode checks;
`Os.remove` is confined to known redundant regular recovery/pending files.
Most Os operations used are API21; `O_CLOEXEC` is public only from27. The adapter
therefore returns UNSUPPORTED before descriptor open on API26; no hidden numeric
flag, unsafe no-CLOEXEC open or later fcntl fallback is supplied. The JVM negative
test exercises that gate before Android calls. NIO/Path/POSIX attributes require26;
project minSdk26/compile35/target35 remain unchanged, but minimum *functional*
adapter candidate is27, still unqualified. API presence is not filesystem support.
[Android Os reference](https://developer.android.com/reference/android/system/Os),
[public constants](https://developer.android.com/reference/android/system/OsConstants).

## Synchronization And Drain

The required worker sequence is:

1. Synchronize the application data directory to persist a possibly newly created
   `no_backup` entry; create/validate the fixed child namespace.
2. Acquire the stable lease, reject surviving records, sync base and child
   namespace (including the new lock entry).
3. Create recovery exclusively, write encrypted contents, fsync file, close,
   fsync namespace, re-read/authenticate. Only then `RECOVERY_READY` opens the
   future pre-PAKE storage gate.
4. Create authenticated pending candidate exclusively, fsync file, close,
   fsync directory and verify immutable contents before R.
5. At P, hard-link complete pending data to record if absent; fsync directory at S.
6. Remove only known redundant pending/recovery after confirmed P/S; fsync the
   cleanup directory. Cleanup failure does not reverse P.

Data, parent-directory, namespace, no-replace and exclusion observations are
separate: NOT_CHECKED/OBSERVED/UNSUPPORTED/UNKNOWN/REJECTED, plus busy/drain
evidence. OBSERVED means that requested operation returned, not physical
power-loss qualification or proven universal Android support. Unsupported fsync
closes the pre-PAKE gate; generic IO uncertainty is not relabeled Unsupported.

One Android descriptor is tracked per serial storage operation. Every completed
normal path closes it. An ambiguous close retains ownership knowledge as
incomplete; it is never retried because the integer descriptor might be reused.
Storage refuses D and retains its lease while IO drain is unproven. No timeout
pretends this is cleanup. The OS worker may block; no bounded filesystem latency
or automatic retry is promised. P1's explicit blocked/reconciliation states apply.

## Non-Exportable Key Contract

The codec now consumes a narrow `seal/open` AEAD port. A compatibility default
adapts existing exportable AES256 JVM fixtures; unknown opaque software keys stay
unavailable. Android verifies an opaque AndroidKeyStore SecretKey via KeyInfo,
then gives that handle to Cipher. No `getEncoded()` occurs in Android key lookup
or in seal/open. The RSA ADB identity is still recoverable only inside the
encrypted record; the non-exportability guarantee concerns the AES wrapping key.

Production alias: `warpnect.directshell.pairing.production.aes256.v1`.
Lookup verifies exact alias, generated origin, AES256, exactly encrypt/decrypt,
GCM/NoPadding and no user-authentication dependency. Construction is inert;
inspection/lookup never provisions, deletes, imports, replaces or repairs a key.
Missing/policy-rejected/unknown key evidence is sanitized and blocks storage.
Expected alias absent never generates a replacement, even when ciphertext exists.
Production enrollment-time key provisioning remains a separate authorized future
task: this increment deliberately supplies no production generation entry point.

Encryption uses `Cipher.init(ENCRYPT_MODE, key)` without caller IV and requires
the provider's96-bit IV/128-bit tag. The unchanged52-byte record header is AAD;
decryption validates bounded ciphertext and authenticated plaintext. AndroidKeyStore
randomized-encryption keys reject caller-provided encryption IVs, so the former
exportable-key pattern cannot be reused. No software can guarantee random IVs
never collide; the intended guarantee is provider-generated fresh randomness,
not a persisted nonce counter. [Randomization policy](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setRandomizedEncryptionRequired(boolean)).

KeyInfo exposes no randomized-encryption-required getter. The adapter does not
pretend to read that flag; its encryption always uses provider-generated IVs,
and the future fixture explicitly provisions that restriction and tests rejection
of supplied IVs. Backing evidence is UNKNOWN below31 and independently records
securityLevel on31+; KeyStore membership is not a hardware-backed qualification.
No StrongBox requirement or device allowlist. [KeyInfo evidence](https://developer.android.com/reference/android/security/keystore/KeyInfo).

## Reservation, Restart And Failure Semantics

R/P/S/D/F remain distinct. P1 is unchanged; the exact opaque candidate/reservation
association and one-shot claim are authoritative. All publication, crypto, lease,
fsync and close work stays outside its monitor. Before R no P; after R cancellation
cannot promise rollback; after P retain the record. Unknown P/S requires
reconciliation, and a receipt alone never proves D. Settled receipts are cached
for exact reservation identity; retries never repeat filesystem publication.
Consent120s/work60s/cleanup observation3s remain unchanged. No independent worker
queue, coordinator, Host authority or new lifecycle state machine is introduced.

Inspection is read-only and bounded; it never creates a namespace, lock, key,
identity, consent or Host. Existing core status names remain authoritative:

| Artifact/evidence | Classification | Automatic action |
| --- | --- | --- |
| No namespace/records, valid key if needed | EMPTY | None; no consent restored |
| Recovery only | RECOVERY_RECONCILIATION | None |
| Prepared without committed record | PREPARED_RECONCILIATION | None |
| Record only | COMMITTED_RECONCILIATION | No automatic adoption/Host |
| Record plus redundant artifacts | COMMITTED_CLEANUP_PENDING | No restart deletion |
| Truncated/authentication failure | CORRUPT | Retain |
| Missing/unconfirmed key | KEY_UNAVAILABLE plus key evidence | Retain, no regeneration |
| Foreign UID/type/mode | FOREIGN_RECORD or bounded scan CORRUPT | Retain/reject |
| Competing writer | BUSY | No queue/retry |
| Unknown IO/publication | IO_UNCERTAIN / typed UNKNOWN receipt | Block reconciliation |
| Unproven close/lease drain | OWNERSHIP_UNRESOLVED | Keep occupied |
| Unsupported primitive | UNSUPPORTED | No weakened fallback |

Android daemon trust and local files remain non-atomic independent domains.
This task has no PAKE, no daemon effects and only controlled fake authentication
evidence. A surviving file is never proof of a completed pairing workflow.

## Software Evidence

Receipts: `C:/wg-pairing-android-storage-r1605/`. Toolchain unchanged: Gradle8.12,
JDK21 launcher/existing17 toolchain, SDK35, NDK27.0.12077973, CMake3.22.1;
arm64-v8a/armeabi-v7a/x86_64. No dependency/SDK upgrade.

| Gate | Current receipt/scope |
| --- | --- |
| Focused JVM |199/199,7 suites:96 P1 +62 prior storage +41 new |
| Full Debug JVM |1383/1383,187 suites; +41 over r1604's1342/184; all1342 prior test identities retained |
| Existing test preservation | All prior tests retained; four copied fixtures LF-identical |
| Offline audio replay |40/40, no media source changes |
| AndroidTest Kotlin |26 new methods compile; NOT executed |
| ktlint/full lint/build/isolation | PASS; exact closure below |
| Native CTest | NOT EXECUTED; no native source/build input change |
| Device/emulator/Android fsync/Keystore runtime | NOT EXECUTED |
| Power loss | NOT QUALIFIED |

Host AEAD uses real JCA, all prior real Windows file/link/OS-exclusion tests remain.
New adapter tests inject modeled UID/mode/Android open/directory sync; they do not
measure Android fsync. Windows directory synchronization remains Unsupported in
its actual provider negative test. The positive modeled sync does not establish
Android durability. Host process tests are not Android cross-process qualification.

Preserved failed development receipts: initial formatting, unavailable public
`O_DIRECTORY`/`Os.unlink` compiler diagnostics, later instrumentation formatting,
an incorrect test-only runtime-kind import, and a real NewApi lint error for
O_CLOEXEC at min26. The last required a fail-closed API gate, not suppression.
These were corrected, not
suppressed. One source-guard draft also matched a comment mentioning an unavailable
API; the guard was corrected to inspect code without comments, not to allow the
forbidden API. No application assertion/lint rule was weakened. The prior extra Release unit-test
compile failure (180 diagnostics/11 unchanged DEBUG-dependent tests, reproduced
on clean parent) remains a historical independent FAIL; not rerun or repaired,
and not confused with assembleRelease/lintRelease.

## Future Android Qualification Boundary

The26 DEBUG-only instrumentation methods use a UUID-owned directory under
target no-backup storage, ContextWrapper redirecting only that isolated fixture,
and dedicated synthetic test aliases. No production record, alias, existing DEBUG
identity, trust or pairing data is read or mutated. Main contains no key generator;
synthetic provisioning is explicit in positive tests, not setup/passive inspection.
Existing DEBUG-only BC is used for test certificates; no new shipped dependency.

Prepared platform cases cover canonical path/symlink/UID guards, real directory
and file fsync, actual no-replace collision, full encrypted restart, non-exportable
key use, wrong purposes/key/AAD/tag, missing key with retained ciphertext,
truncated/corrupt/interrupted anchor, post-P unknown/sync/cleanup failures and
descriptor drain. Eight lifecycle cases cover cancel around R/P, duplicate/late
receipt, timeout and no new operation before D. Injected failures are explicitly
labeled, not spontaneous Android observations. Unsupported required primitives
must fail qualification rather than be skipped as PASS.

The two-adapter Android lease test is same-process only. No existing safe
Android cross-process service infrastructure exists here; no manifest/service
was added just to simulate it. Android independent-process exclusion remains a
specific later acceptance gap. Physical power-cut durability is not proven by
instrumentation or process-termination replay.

Smallest next bounded task, requiring separate authorization: run these prepared
tests on an isolated authorized API27+ fixture (API26 is explicitly unsupported
by this adapter, not a positive qualification target), freezing APK/test hashes and API,
UID/filesystem/key evidence; test only synthetic namespaces/aliases, stop on the
first unsupported/unknown guarantee, verify resource drain and fixture-only
cleanup. Record backing without claiming it from membership. Add a narrowly
reviewed independent-process exclusion test only if fixture infrastructure permits.
Do not combine this with real pairing, a media campaign or Release onboarding.

Remaining P2: production key provisioning policy/qualification, actual Android
filesystem/exclusion, endpoint identity/NSD/network ownership, real transport and
physical cancellation/drain, authenticated evidence, reconciliation and complete
credential lifecycle. Release adapter stays unavailable; runtime/helper registration
is absent. No P3-P5 is started. P2 completion, HARDWARE VALIDATED, QUALIFIED and
PRODUCTION-READY are not claimed. G1-G5 hardware remains PARTIAL and all historic
media debts stay open in their original scope.

## Verification Closure

Fresh `software-settled.log`: **BUILD SUCCESSFUL**,26m8s,167 tasks (119 executed,
48 up-to-date). `focused-r8.log` separately passes on the identical source.
Executed tasks: ktlintCheck, testDebugUnitTest, lintDebug, lintRelease,
assembleDebug, assembleDebugAndroidTest, packageDirectShellProbeDex, assembleRelease.
No connected/install/emulator/instrumentation task was invoked.

| Isolation/verification | Fresh result |
| --- | --- |
| Existing source/bytecode/DEX/manifest boundaries |21/21 |
| P1 reservation/model boundaries |34/34 |
| Android/portable storage source integrity |82/82; expanded/re-scoped successor to old44, not an extra44 |
| Inherited compiled storage boundaries |10/10 |
| New adapter compiled/discovery boundaries |14/14 |
| Native compiled symbol guards |6/6 across three ABIs; not CTest |
| Debug lint |0 errors/134 warnings, exact baseline issue-key delta0 |
| Release lint |0 errors/42 warnings, exact baseline issue-key delta0 |
| Git diff/17 new source whitespace/frozen hashes |PASS |
| Protected worktrees |11/11 unchanged HEAD/index/status/content; four original large artifacts metadata-only |
| Phase7 R2 |unchanged SHA256 `0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6` |

Compiler/Gradle/native warnings from unchanged sources remain visible; no
suppression, buffer/priority change or unrelated warning repair was introduced.
The extra historical Release unit-test FAIL remains separate from passing
Release assembly/lint. Native CTest, Android runtime, filesystem power loss,
Keystore hardware backing and hardware acceptance were NOT executed/qualified.

Exact compiled instrumentation discovery:18 platform +8 lifecycle methods,
`instrumentation-discovery.json`, COMPILED_NOT_EXECUTED. JVM XML receipts
`jvm-focused-settled-results.json` and `jvm-full-settled-results.json` record
199/1383 tests, no failures/errors/skips. `jvm-preservation.json` compares all
1342 prior test identities, missing0/added41. Replay log records40/40.

Frozen application/test source cohort SHA256 (sorted path + LF-canonical hash):
`27CAD08A12DC2FA832A67528FB67317C14DF27BAF31CC7A1B9FDDB72F2966416`.
Full per-path raw/LF hashes and artifacts are in `final-manifest.json`.

| Artifact in this worktree | SHA256 |
| --- | --- |
| app/build/outputs/apk/debug/app-debug.apk | `6CE1E9EBB173EF54042FC1EE2EACCDC42AA83AC822498DA115422DCC155999DE` |
| app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | `568E617A79032A5A1C2CAD9DF3DC6D74E7F5FC679CCCD432A644D6EE60D23A49` |
| app/build/outputs/directshell-probe/warpnect-directshell-probe.jar | `9CDA0B48A097397878523B5037A23F7E04C90D8F058143CEBDA03F8524A3EFB9` |
| app/build/outputs/apk/release/app-release-unsigned.apk | `6C12B35DB579668441F268334D262823FEC32FF0A089898F9464B8CD71458CDF` |

Traceability is HEAD856bca7 + frozen17-path candidate -> these build outputs;
not HEAD alone, not installed artifacts, and not a claim of bit-reproducible ZIP
timestamps.17 application/test +7 documentation paths remain uncommitted; the
index is empty. P1 worktree stays clean, prior P2 storage14-path dirty cohort
and original research496-entry cohort remain unchanged. Master/upstream,
G1-G5, Video, review and earlier P2 are preserved. No secrets/private artifacts
were staged. No device state was read or changed, so no restoration was needed.

Decision: this adapter is IMPLEMENTED/SOFTWARE TESTED, not HARDWARE VALIDATED,
QUALIFIED or PRODUCTION-READY. API26 descriptor operations are explicitly
UNSUPPORTED; API27+ actual guarantees remain pending instrumentation. Proceed
only with a separately authorized synthetic-fixture platform qualification step;
do not start real pairing/P3-P5 or advertise Release availability.
