<!-- NATIVE_PUBLICATION_SUCCESSOR_START -->
**2026-10-27 successor:** [native integration](DirectShellPairingNativePublicationIntegration.md)
has1435 full/251 focused JVM and34/34 actual API30 AOSP x86_64 storage/lifecycle
methods PASS on new frozen APKs, using production no-replace rename and explicit
source consumption. P2_NATIVE_PUBLICATION_ANDROID_FIXTURE_VALIDATED is exact-
fixture only. Overall P2_PARTIAL_BLOCKED; Release unavailable; independent-process
Android, physical/OEM/ARM and power-loss qualification remain pending. The entire
original report below is historical evidence:15 PASS/2 FAIL/9 blocked is preserved,
not changed into a passing old candidate. No real pairing or physical device.
<!-- NATIVE_PUBLICATION_SUCCESSOR_END -->

# DirectShell Pairing Android Storage Validation

2026-10-27. RFC-006J storage checkpoint, disposable platform execution and
version-policy audit. No application/test/native source change in this task.

**P2_ANDROID_STORAGE_CHECKPOINT_VERIFIED**.
**P2_ANDROID_STORAGE_PLATFORM_PARTIAL**, NOT EMULATOR_VALIDATED.
Overall **P2_PARTIAL_BLOCKED**; Release enrollment remains unavailable.

## Checkpoints And Source Provenance

The authorized two local commits on `codex/phase6-5-pairing-android-storage` are:

| Commit | Exact SHA | Scope |
| --- | --- | --- |
| feat(directshell): add Android credential storage and Keystore adapters | `a7fefaa28681c0612ddfdc20e4cf0421849b312d` | Exactly17 reviewed application/test paths |
| docs(phase6_5): record Android storage software verification | `ce2466240784c21e1698f1b06faed466087a57a3` | Exactly7 reviewed documentation paths; PLATFORM_VALIDATION_PENDING preserved |

Their parent is P1 docs `856bca744cc688df22e35b1de6b1303100ea66da`, with P1
source/tests `7f3e39f036db0f833efe843ccb243b82210f8e20` unchanged. All24 original
raw/LF hashes and all4 artifact hashes matched r1605 before staging. Exact path
staging and cached diff checks excluded APKs, keys, logs and generated material.
Committed blobs match LF-canonical hashes under `core.autocrlf=true`. No automatic
source modification accompanied normalization. The checkpoint worktree is clean.

Application/test cohort SHA256:
`27CAD08A12DC2FA832A67528FB67317C14DF27BAF31CC7A1B9FDDB72F2966416`.

The isolated validation branch is
`codex/phase6-5-pairing-storage-platform-validation`, HEADce24662, workspace
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-storage-platform-validation/Warpnect`.
Only documentation is changed here, uncommitted. No merge/push, extra checkpoint,
old dirty source import, Phase7 R2 modification or accepted RFC rewrite.
Receipts: `C:/wg-pairing-storage-platform-r1606/`.
The managed checkout initially used detached HEADce24662 for unchanged-source
execution; the named validation branch was then created at that same commit for
the documentation handoff. No tested source or ancestor was changed by this.

## Frozen Test Inputs

The existing verified outputs were copied, not rebuilt, and both installed APK
hashes were checked on the emulator. Traceability is the r1605 frozen source
cohort -> identical committed source -> these APKs -> emulator5660 -> named test
receipts. This is not a claim of post-commit ZIP timestamp reproducibility.

| Artifact | SHA256 |
| --- | --- |
| Target Debug APK | `6CE1E9EBB173EF54042FC1EE2EACCDC42AA83AC822498DA115422DCC155999DE` |
| AndroidTest APK | `568E617A79032A5A1C2CAD9DF3DC6D74E7F5FC679CCCD432A644D6EE60D23A49` |
| Retained, unused helper JAR | `9CDA0B48A097397878523B5037A23F7E04C90D8F058143CEBDA03F8524A3EFB9` |
| Retained, uninstalled unsigned Release APK | `6C12B35DB579668441F268334D262823FEC32FF0A089898F9464B8CD71458CDF` |

No helper JAR, privileged server or Release app was run. No runtime permissions
were granted with install flags. No physical device/ADB trust state was accessed.
Both frozen APK signatures verify locally with the same certificate SHA256
`8AA8738920C8C0942537F7DD71ED291DF508B850EDB7E5A4D2A8DF49813CD969`.

## Disposable Environment

Existing AVDs were only protected API25/26 Root laboratories: not used. A fresh
API30 default AOSP image was obtained from the official SDK repository into a
private SDK under the receipt directory. Existing accepted SDK license files were
copied; no new license acceptance, global SDK update or emulator setting change.
SDK package metadata identifies revision11 (the bundled source.properties says10;
the measured fingerprint below is authoritative for execution). Emulator37.2.12,
build16428233, WHPX;2 virtual cores/2048MiB, headless/no audio/no snapshots.
The global SDK emulator37.1.11 and original AVDs remain unchanged.

| Evidence | Actual result |
| --- | --- |
| AVD | Warpnect_Storage_API30_r1606, newly created, initially no Warpnect packages |
| Target | emulator-5660, qemu=1, x86_64, ranchu |
| Android |11 / API30 |
| Fingerprint | `Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys` |
| Shell identity | UID2000, no adb root/su/SELinux change |
| Application UID |10130, untrusted_app SELinux domain during tests |
| Data mount | `/dev/block/dm-5 /data ext4 rw,seclabel,nosuid,nodev,noatime,resgid=1065,errors=panic` |
| SELinux |Enforcing |
| Application/private base | `/data/user/0/io.warpnect`, UID10130,0700; no_backup UID10130,0771 as provided by Android |

An independent loopback ADB server used5049, an impossible USB serial filter and
disabled mDNS auto-connect, with its own temporary host key. Every target command
specified emulator-5660; qemu/API/fingerprint were checked before installation and
tests. No Wireless Debugging setting/pairing, USB-assisted production setup, root,
Shizuku, normal Activity/Host or Session was used. Default ADB server PID7924 on5037
was not restarted or selected. This is emulator control transport, not a legacy
DirectShell onboarding fallback.

Preflight source inspection found existing DEBUG startup registration to be lazy:
the provider registers factories and cleans only its own absent fresh-lab files.
Application composition remains lazy; the tests never access it. Runtime logs
showed `registered=true launch_on_host_enable_only=true`, no helper launch.
The normal Android `am instrument` shell uses app_process internally; this is
not a Warpnect privileged helper. System root daemons on the userdebug image are
not app root authorization. No tested Warpnect process gained UID0.

## Bounded Execution And First Failure

Plan:26 compiled methods, one invocation each,60s per method/6min campaign budget.
The three ordered preflights passed: passive inspection, path/symlink boundary,
actual directory/file synchronization. Then the first after-P cancellation case
failed: expected PUBLISHED, observed UNKNOWN. The sequence stopped immediately.

The preserved kernel/audit log identifies the first trustworthy boundary:

```text
avc: denied { link } ... comm="roidJUnitRunner" name="identity.pending"
scontext=u:r:untrusted_app:s0:c130,c256,c512,c768
tcontext=u:object_r:app_data_file:s0:c130,c256,c512,c768
tclass=file permissive=0 app=io.warpnect
```

Source path: storage claims R -> validates encrypted candidate -> Android
`linkNoReplace` -> `Os.link(pending,record)` -> SELinux denial -> sanitized
platform UNKNOWN -> storage UNKNOWN/unconfirmed receipt. The numeric errno is
NOT exposed by this adapter and was NOT measured; do not invent EACCES/EPERM.
The closed failure category and exact denied permission are known. No successful
P, S-for-publication, redundant cleanup or after-link cancellation callback is
demonstrated by this FAIL.

One distinct existing atomic-publication test, without P1/cancellation, was used
as the component discriminator. It also failed PUBLISHED versus UNKNOWN. This
is not a hidden retry of the failed lifecycle case. No affected positive
publication path was exercised further. Twelve independent nonpublication cases
then passed; nine publication-dependent cases were explicitly blocked, not skipped
or counted PASS. Assertions, SDK target, SELinux, buffer sizes and storage method
were not changed. No second backend/device campaign was started.

Conclusion: the selected Android hard-link publication primitive is unavailable
to this exact untrusted_app fixture under its enforced policy. This is a concrete
platform-policy incompatibility, not evidence of a P1 cancel ordering defect.
It is not a claim about every Android/OEM policy or a new media/runtime regression.
The existing implementation fails closed; it is NOT an Android-qualified store.
This is newly observed first-runtime evidence, not a regression against an earlier
Android storage PASS: no such baseline existed.

## Exact Method Results

P = PASS; F = FAIL; B = NOT_EXECUTED_BLOCKED_PUBLICATION, not a runner skip.
Class names share package `io.warpnect.platform.privileged.directshell.pairing`.

### AndroidPairingStorageInstrumentationTest

| Method | Result |
| --- | --- |
| factoryAndInspectionNeverProvisionKeyOrIdentity |P |
| realDirectorySynchronizationAndFileSynchronizationAreDistinct |P |
| canonicalPrivateBoundaryRejectsOutsideAndSymlinkArtifacts |P |
| realAtomicPublicationPreservesWholeEncryptedRecordAndNoReplacement |F |
| existingCommittedEntryWinsNoReplaceRace |B |
| twoAdaptersShareStableLeaseSameProcessNotCrossProcessQualification |P |
| realKeystoreNonExportableKeyEncryptsAndDecryptsBoundedRecord |P |
| keyRandomizationRequiresProviderIVNotCallerIV |P |
| invalidTagAndAADNeverYieldAuthenticatedPlaintext |P |
| wrongOwnedKeyCannotDecryptPreviousRecord |P |
| unqualifiedKeyPurposesRemainUnavailable |P |
| missingKeyAfterRecordCreationPreservesCiphertextWithoutRepair |P |
| truncatedAndCorruptRecordRemainBlockedAtRestart |P |
| injectedPostPublicationUncertaintyPreservesRealCommittedEntry |B |
| injectedCleanupFailureDoesNotUndoPublication |B |
| interruptedAnchorWriteRemainsBlockedWithoutRecoveryPermission |P |
| publicationWithoutDirectorySyncCannotBecomeDurableSuccess |B |
| injectedAmbiguousCloseCannotClaimDrainAndIsNeverRetried |P |

### AndroidPairingStorageLifecycleInstrumentationTest

| Method | Result |
| --- | --- |
| cancelBeforeReservationRetainsRecoveryButNeverPublishes |P |
| cancelAfterReservationAbstainsUnclaimedPublication |P |
| cancelInsideClaimedPublicationCannotPromiseRollback |B |
| cancelAfterPublicationPreservesCommittedRecord |F |
| duplicateReceiptHasOnePublicationAndNoHostSideEffect |B |
| timeoutDuringStorageDoesNotReleaseUndrainedOperation |B |
| lateReceiptAfterDisposalCannotReviveConsent |B |
| receiptWithoutDrainNeverAllowsSecondOperation |B |

Totals:17 unique methods executed,15 PASS/2 FAIL/0 skips;9 blocked. Lifecycle
alone:2 PASS/1 FAIL/5 blocked, not8/8. Receipts are `instrumentation-results.json`,
`atomic-discriminator.log`, `independent-results.json`, `platform-summary.json`
and per-method logs under `instrumentation/`. No case was silently retried.

## What The Platform Evidence Establishes

| Property | Evidence and limit |
| --- | --- |
| Canonical/UID/mode boundary |Real app-private fixture, real lstat/fstat/mode checks, outside path and symlink rejected; real code reached RECOVERY_READY |
| Stable lease |Real Android FileChannel.tryLock acquisition, cooperating same-process exclusion and release/reinspection PASS; independent Android-process contention NOT tested |
| Recovery/preparation |Real exclusive encrypted file creation, data fsync, parent app-directory/no_backup/child sync and authenticated re-read completed; no modeled successful fsync in this platform path |
| Power loss |NOT tested; fsync return is not physical power-cut qualification |
| Atomic publication/no replacement |BLOCKED at SELinux link permission; no successful directory-entry P or post-P synchronization; Windows/JVM results do not transfer |
| Incomplete/restart |Interrupted anchor, corrupt/truncated pending and missing-key blocking PASS; fixtures retained data until explicit test-owned teardown, never automatic pairing/Host |
| Drain |Normal owned store close/lease release completed; ambiguous-close fault is injected, retains unknown D and is not retried |
| Redundant post-P cleanup |NOT qualified because P could not succeed |

Real Keystore tests used only dedicated synthetic aliases, never the production
alias or DEBUG identities. AndroidKeyStore lookup/KeyInfo checks accepted AES256,
generated exact alias, encrypt+decrypt/GCM/NoPadding policy. `SecretKey.encoded`
returned null. Provider-generated96-bit IVs/128-bit tags, distinct observed IVs,
AAD authentication, bad tags/wrong key rejection and missing-key no-regeneration
passed. Caller-provided encryption IV was rejected for the synthetic randomized
key. No plaintext credentials were written by the store. This proves functional
cryptographic behavior on this emulator, not hardware-backed storage.

The adapter reports backing UNKNOWN below31; no securityLevel value was obtained
on API30, and no hardware level is inferred from provider membership or the
emulator's software Keymaster service. Exact per-Cipher provider names were not
emitted. The tests verify the AndroidKeyStore handle path and policy, not an
independent attestation. No production key provisioning is supplied.

R/P/S/D/F remain independent. The two passing cancellation cases exercise no-P
outcomes before R and after R while the claim is still unconsumed. They do not
prove cancellation during successful P. The attempted after-P test never reached
its prerequisite P; it must not be reinterpreted as a passing unknown-outcome
test. IO stayed on the explicit test worker path; the pure P1 monitor/contract
was not modified. The submitted characters are fixed synthetic model input,
not a generated Android pairing code or real TLS/PAKE exchange. No real Host,
runtime acquisition, planner freeze, peer authentication or Session was created.

## DirectShell Version Policy

**Self-contained user-facing Wireless Debugging enrollment requires API30+.**
API29 and below are **UNSUPPORTED_PLATFORM_VERSION** for that path. This is a
product-policy label, mapped to existing private readiness
`UNSUPPORTED`/`PLATFORM_UNSUPPORTED` and runtime
`DIRECTSHELL_UNSUPPORTED_PLATFORM`; no wire enum or accepted RFC is renamed.

Android's documented modern phone pairing flow begins with Android11/API30;
the older USB-assisted TCP workflow is a different path, not an automatic fallback.
[Official ADB platform distinction](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

| Android | API | User-facing DirectShell Wireless Pairing |
| --- | ---: | --- |
|8.0 |26 |Unsupported |
|8.1 |27 |Unsupported |
|9 |28 |Unsupported |
|10 |29 |Unsupported |
|11 |30 |Eligible, pending qualification |
|12-12L |31-32 |Eligible, pending qualification |
|13-15 |33-35 |Eligible, pending qualification |
|16 |36 |Eligible, pending qualification |

Eligibility is the OS feature prerequisite, not proof that the current store,
Release adapter or complete DirectShell Host is available. This exact AOSP30
publication FAIL remains a blocking qualification result despite version
eligibility. Other/OEM/API versions remain untested here. This phone/tablet
policy does not expand qualification to TV/Wear variants.

The code already enforces30: `AndroidHostReadinessWork.kt` derives separate
DirectShell availability using MIN_DIRECT_SHELL_API=30; DEBUG
`HostPairingSetupAdapter.createIntent` returns null below30;
`DirectShellSelfBootstrapController.requireModernWirelessDebugging` checks30.
Release createIntent remains null on every API. UI action mapping requires
current ADB_PAIRING_REQUIRED plus an available adapter, so unsupported OS states
do not display a nonfunctional pairing-code action. No duplicate feature boolean
or runtime gate change was needed.

Global minSdk26, target/compile35 remain unchanged. The storage adapter's separate
API26 CLOEXEC failure stays fail-closed and is NOT a production Wireless Pairing
acceptance gap (that path begins at30). Static provider selection/COMPLETE Host =
Video+SystemAudio+Input and optional microphone remain intact. Other providers
retain independent eligibility/qualification; Client is not globally disabled.
No root/Shizuku/USB/legacy-TCP substitution was added. Previously validated DEBUG
or laboratory paths on older OS releases retain their exact historical scope;
they are not promoted to self-contained onboarding and were not retested here.

## Software Gates And Preservation

Fresh TaskA:199/199 focused JVM;40/40 replay;82 source,21 source-set/DEX/manifest
and34 P1 model guards. Exact manifest/artifact/index checks passed before each
commit and committed LF blobs were verified after. Fresh compatibility audit:
66/66 JVM across planner21, background readiness16, setup controller18,
presentation11. No failures/errors/skips. A requested extra class-name pattern
matched no suite; it adds no tests or claim to the recorded four suites.

The r1605 full1383 JVM, Debug/AndroidTest/helper/Release builds, ktlint, lint
(134 Debug/42 Release unchanged warnings), compiled storage10/adapter14/native6
are retained identical-source evidence, not freshly rerun after these docs-only
updates. No application/test/native/Gradle input changed, so a full rebuild or
CTest campaign was unnecessary. The historical additional Release unit compile
FAIL (180 diagnostics/11 unchanged DEBUG-dependent tests) remains FAIL, not repaired
or folded into successful Release assembly. No media/RFC-006I/Session/security change.

Historical G1-G5 hardware PARTIAL and all media debt remain open; Phase7 R2 is
unchanged/proposed. Twelve protected worktrees, including the new clean Android
storage checkpoint, preserve HEAD/index/status/dirty contents (the original four
large artifacts use metadata-only checks as before). Master/upstream unchanged.
Receipt `preservation-b-final.json` is compared with the pre-execution inventory.

## Cleanup And Harness Findings

Every executed case reached its fixture After cleanup; the two FAILs contain no
additional cleanup failure. Known synthetic alias deletion calls completed;
the missing-key case independently verified deletion/no regeneration. A global
post-test Keystore alias enumeration was NOT performed, so no separate alias-count
attestation is claimed. Actual app no_backup listing was empty after all17 cases:
no fixture directories, records or production namespace. Both newly installed
owned packages were uninstalled with Success, then absence verified. No existing
app data/trust/signer installation was replaced.

The owned emulator was stopped; ports5660/5661 closed. Only private ADB server5049
was stopped; the original5037 PID7924 remained. Its host key/configuration and
protected AVD/SDK settings match the preflight hashes. The fixture's temporary
ADB private/public key files were removed only after both owned processes stopped.
Private SDK/image/AVD are retained offline for evidence; no active fixture helper
or key-based access surface remains. Receipts: `post-tests-state.json`,
`cleanup-packages.json`, `runtime-cleanup.json`, `host-state-final.json`.
No physical A41/S9/Tablet/other device operation or restoration was needed.

Preserved harness findings: remote-host auto-start restriction and hostname-form
listener rejection while creating the private ADB server (no physical connection);
the emulator's private SDK needed a copy of platform-tools; avdmanager emitted an
optional devices.xml warning but created the private configuration; an initial
runner filter and combined shell metadata query were too broad/incorrect and
were superseded by exact runner validation and standalone UID/path queries.
These did not alter assertions or app state and are not storage failures.

## Decision And Next Bounded Task

Do not continue real P2 PAKE/NSD or P3-P5. Recovery-file fsync success is necessary
but not sufficient: before future trust-affecting I/O, a usable committed-record
publication strategy must also be established under the normal app domain.

Next: one RFC-006J-scoped design/feasibility review of **Android-allowed atomic
no-replace publication of a complete encrypted record**, replacing no implementation
yet. Compare supported public primitives and, only as a separately proposed option,
a narrow native no-replace primitive with API/SELinux/runtime evidence. Specify
revision-zero CAS, crash outcomes, S/D uncertainty and a safe pre-PAKE capability
gate. No ordinary overwriting rename, copy fallback, target-SDK downgrade, root,
SELinux relaxation or second enrollment owner is acceptable. Directory sync and
Keystore evidence should be reused, not rediscovered in another broad campaign.

Any chosen alternative needs explicit review, negative/collision/crash/ownership
tests and a separately frozen synthetic-fixture run before real pairing. Nothing
here authorizes a new JNI/native ABI, platform workaround or transport integration.
Stop this task with the partial result and preserved failures, not a mitigation
or universal Android qualification. No further commits, push or merge.
