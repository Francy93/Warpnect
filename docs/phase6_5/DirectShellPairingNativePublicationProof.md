# DirectShell Native No-Replace Publication Proof

2026-10-27. **NATIVE_NO_REPLACE_PRIMITIVE_PROVEN_ON_EXACT_FIXTURE**.
This is the separately user-authorized test-only successor to the
[feasibility review](DirectShellPairingAtomicPublicationFeasibility.md), not
production storage integration. Overall **P2_PARTIAL_BLOCKED**. Release onboarding
remains unavailable. No real pairing, physical-device access or production fix.

## Baseline And Scope

Isolated branch `codex/phase6-5-pairing-native-publish-proof`, worktree
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-native-publish-proof/Warpnect`.
HEAD remains `ce2466240784c21e1698f1b06faed466087a57a3`, Android storage source
checkpoint `a7fefaa28681c0612ddfdc20e4cf0421849b312d`. No new commits or staging.
P1 reservation checkpoints7f3e39f/856bca7 remain unchanged ancestors.

The user explicitly authorized the existing
[bounded prompt](DirectShellPairingAtomicPublicationImplementationPrompt.md).
That prompt, the feasibility review and the historical Android validation report
were copied byte-identically from the preserved review worktree for provenance.
Their original classifications describe their original executions; this receipt
adds evidence, does not rewrite them. The old26-case storage campaign remains
15 PASS/2 FAIL/9 blocked, NOT rerun and NOT retroactively passing.

The frozen17-path application/test cohort remains
`27CAD08A12DC2FA832A67528FB67317C14DF27BAF31CC7A1B9FDDB72F2966416`.
Production credential storage still calls `Os.link`; its denied Android publication
has NOT been replaced or qualified by this primitive experiment.

## Exact New Source Manifest

Only these three test/tool source files are new:

| Path | SHA256 (raw file) |
| --- | --- |
| `app/src/androidTestDebug/native/atomic_publish_proof.c` | `F668AD0D78FDA567FE6B0CDF148AFE3D5C12AFE8DC4C794E65A09F62DFCA23DD` |
| `app/src/androidTestDebug/java/io/warpnect/platform/privileged/directshell/pairing/NativeNoReplaceInstrumentationTest.kt` | `79596C8D4BCD76CE2E4D0439706A8F736FA320DA390F8850CA9AF6582EDE4A1D` |
| `tools/android-publish-proof/build-test-native.ps1` | `A175BDB29DD80A303B5AE9E85568C4E2EE41E432CFDDB7298124B7CDD7BF76B7` |

The Kotlin bridge loads the aligned library entry from the AndroidTest APK only
inside explicit API30+ test execution. There is no static initializer, app setup
call, new manifest component, Gradle dependency or production CMake/SCL ABI edit.
Generated `.so` files live only in ignored `src/androidTestDebug/jniLibs`; the
source/build script, not generated binaries, is the reproducible source artifact.

The one native entry borrows an app-owned validated directory FD, accepts only
candidate enum0/1, and calls the public bionic API30 `renameat2` with fixed
`candidate-a`/`candidate-b` and `record` names and `RENAME_NOREPLACE`. It rechecks
directory type/UID, captures errno immediately, and returns only packed native
UID/status/errno. No payload, arbitrary path, command, syscall number, allocation,
lock, file write, logging, thread creation or descriptor close in the native entry.
Java owns all descriptors and worker drain.

Caller preparation creates complete synthetic4096-byte files, synchronizes file
contents and directory entries, closes writer FDs and binds inode/UID/type/size/
link-count/digest before the operation. Symlink, nonregular and changed source
cases fail prevalidation without native entry. This is controlled immutable-file
test evidence, not protection against compromised same-UID code racing a writer.
Actual foreign-UID rejection is not exercised; no ownership/security bypass was
used to manufacture such an entry.

## Frozen Artifacts And Isolation

| Artifact | SHA256 |
| --- | --- |
| Unchanged target Debug APK | `6CE1E9EBB173EF54042FC1EE2EACCDC42AA83AC822498DA115422DCC155999DE` |
| New proof AndroidTest APK | `97A99634DB7C8B2F36124FBADB9D627BA1123F1BCC99B035529D78080C2BB03A` |
| Unchanged helper JAR | `9CDA0B48A097397878523B5037A23F7E04C90D8F058143CEBDA03F8524A3EFB9` |
| Unchanged unsigned Release APK | `6C12B35DB579668441F268334D262823FEC32FF0A089898F9464B8CD71458CDF` |

Target/test signatures were verified; both signer SHA256 values are
`8AA8738920C8C0942537F7DD71ED291DF508B850EDB7E5A4D2A8DF49813CD969`.
Installed target/test APK digests matched their frozen host artifacts before tests.
The target was reused from the verified checkpoint, not newly assembled from
mixed source. The new test APK was built in this worktree against identical main
sources.52 artifact/source/DEX isolation checks PASS: new library and classes
present in AndroidTest, absent from frozen target/helper/Release.

NDK27.0.12077973 compiled all three ABIs at API30 with `-Wall -Wextra -Werror`.
Each ELF exports exactly one JNI operation; `renameat2@LIBC_R` is imported from
public libc, not a private reflection/direct-syscall bridge. NEEDED entries are
`libc.so` and `libdl.so`; no libadb, crypto or C++ runtime dependency is added.

| ABI | Standalone library SHA256 | APK-packaged/stripped SHA256 |
| --- | --- | --- |
| arm64-v8a | `7459646AD6AD8E23C02FF8218B49D93B41B334D8154D6F277849D02E8088F916` | `312C08C726B14EEE8311DEA64AD65786B605CB999A4D61B648AAD2FF94D318F5` |
| armeabi-v7a | `476279E3A742C500A3B1774BABEC606EFCDA5C1D958A917B20F1858E9C78618E` | `DDBB1175145C3CA299E907D76E0190BCD8BEFE78B94C44A49068F6C68E498915` |
| x86_64 | `15FD32D362E6B74DF9408CEDFBBE78077F13B76288F9A3923C9E5D1AD2FC26CA` | `8A2664793DB8736E4725712B411F30DA6A6FB1E55EC42C1585625196EBCA8C02` |

Only x86_64 executed. ARM compilation/packaging is not runtime qualification.
App minSdk26/target35/compile35 are unchanged. No API30 strong reference enters
an existing min26 library or production loader. DirectShell integrated Wireless
Debugging eligibility remains API30+, not global application eligibility.

## Exact Disposable Runtime

- New dedicated AVD `Warpnect_Publish_API30_r1608`, Android11/API30, x86_64,
  emulator37.2.12 build16428233, no previous Warpnect or personal data.
- Fingerprint:
  `Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys`.
- Kernel `5.4.86-android11-2-00006-gae78026f427c-ab7595864`; /data ext4 on dm-5,
  SELinux Enforcing. Userdebug image is NOT an observation of elevated app rights.
- Every executing test reports Java UID10130 and
  `u:r:untrusted_app:s0:c130,c256,c512,c768` from its own `/proc/self/attr/current`.
  All nine native calls independently returned native UID10130. This is NOT a
  shell/run-as executable proof.
- Private serial `emulator-5662`, ADB port5051 with impossible physical USB
  selection filter, mDNS/autoconnect disabled, private user/AVD/vendor-key paths.
  Default ADB at5037/PID7924 and personal keys/AVDs were untouched.
- Each test uses one UUID-owned directory under target noBackupFilesDir, outside
  `directshell-pairing-v1`. That production namespace remained absent. No key,
  certificate, identity, code, PAKE, NSD or network fixture was used by playback/
  enrollment code. Only ordinary emulator control/install/instrumentation occurred.

Before execution, Application lazy composition and the DEBUG registration
provider were inspected. Existing providers instantiate and log lazy factories;
they do not acquire Host or launch helpers. Logcat shows the expected
`launch_on_host_enable_only=true`, not a bootstrap or live Session. Existing shell
property-read AVCs from ordinary Android command startup are retained in logcat;
they are not publication denials or evidence of a privileged Warpnect helper.

## Bounded Runtime Results

Budget fixed before execution:8 cases,60s/case,480s total, one image, one primitive,
no fallback. **8/8 PASS,0 FAIL,0 skipped;24.082s campaign wall time** excluding
fixture boot/install. Exactly one execution of each case, no spontaneous reruns.

| Method | Actual result | Wall ms |
| --- | --- | ---: |
| case01AbsentDestination | ret0/errno0; complete record, source consumed; actual post-P directory fsync returned |7475|
| case02CollisionAndCallerValidation | Regular and symlink targets each ret-1/errno17 EEXIST; original inode/content preserved, source retained; invalid source type/data rejected before native call |2431|
| case03TwoContenders | Barrier-released threads: one ret0, one EEXIST; winner complete, winning source consumed, loser retained; directory fsync returned |2422|
| case04BoundedReader |256 reads:16 absent,240 complete; final record complete; no partial/invalid observation |2337|
| case05ControlledAbort | No native call; no record, candidate retained; synthetic pre-call abort only |2177|
| case06PublishedUnconfirmedSync | Real P succeeded; post-P sync deliberately omitted at observation; Published/Unconfirmed record retained, then actual sync for fixture completion |2192|
| case07ReadOnlyReopen | Fresh-reader classification: candidate-only, record+candidate/reconcile, record-only/no-adoption; no automatic action |2826|
| case08SyncAndDrain | Actual directory fsync; owned descriptors/workers zero; procfs verifies no remaining fixture FD |2210|

Nine native operations total:6 success/errno0,3 collision/errno17. No ENOSYS,
unsupported flag, publication denial, replacement, alternate backend or fallback.
The reader result is finite sampled evidence, not an exhaustive concurrency proof.
Regular synthetic target content also models an unrecognized existing record;
it is not a real foreign user's data. Symlink collision never follows/replaces it.

File-data sync, child directory sync, parent entry sync, no-replace publication,
collision/source effects and final namespace cleanup returned successfully on
this exact app-private ext4 fixture. No actual injected filesystem corruption or
failing fsync was used. Case06 is explicitly modeled missing S evidence.

## Knowledge Boundaries And Remaining Gates

- This proves an available public no-replace primitive in one ordinary API30 app
  domain. It does NOT qualify the current encrypted store's full transaction.
- No real P1 reservation/cancel/receipt was invoked. Case05 is not P1 cancellation;
  case06 is not a change to its after-R semantics. R/P/S/D/F remain unchanged.
- Rename consumes the source. Future production cleanup MUST distinguish consumed
  pending from hard-link-retained pending; blindly keeping old cleanup is wrong.
- Thread contenders test kernel arbitration, NOT independent-process writer-lease
  qualification. No new service/shared UID/privileged process was introduced.
- Reopen inspection is NOT process-kill or physical power-cut qualification.
  Successful file/directory fsync returns are not proof against every power loss,
  storage controller or OEM filesystem. Physical/OEM and other API levels pending.
- Synthetic data needs no Keystore; its earlier scoped evidence is preserved, not
  rerun or promoted here. Credential authentication, store revision binding,
  noncooperating writer attacks and full recovery remain separate gates.
- No performance/real-time latency qualification was attempted. Small bounded
  native code and host compiler results are not Android callback measurements.

## Software Gates And Reproduction

Fresh: three-ABI native compile/ELF inspection, test-only Kotlin/Java/Dex/APK build,
AndroidTestDebug ktlint check, APK signatures,52 artifact/source isolation checks,
eight actual instrumented cases and diff/preservation checks PASS.
Final integrity receipt:74 checks PASS; exact manifest is three new test/tool
source paths and six documentation paths, with nothing staged.

Preserved harness errors: first formatter found one long line; corrected without
suppression. First Kotlin build found public `OsConstants.O_DIRECTORY` absent;
test adapter now uses public RDONLY/NONBLOCK/CLOEXEC/NOFOLLOW plus fstat type/UID/
inode checks, matching the established public-API approach. These were build-only
errors, no native publication had run. Initial unnecessary production-native task
dependencies were interrupted; final build excludes `mergeDebugNativeLibs` and
does not alter production inputs or the frozen APK. Receipts retain all logs.

From this worktree with existing JDK/SDK/NDK:

```powershell
./tools/android-publish-proof/build-test-native.ps1
./gradlew.bat :app:ktlintAndroidTestDebugSourceSetCheck :app:compileDebugAndroidTestKotlin :app:packageDebugAndroidTest -x :app:mergeDebugNativeLibs --console=plain
```

Then install only on a newly verified disposable fixture, freeze source/APK/signers,
and invoke the exact eight methods individually with the explicit private server
and serial. Example for the first case (never default ADB/physical devices):

```powershell
adb -P 5051 -s emulator-5662 shell am instrument -w -r -e class io.warpnect.platform.privileged.directshell.pairing.NativeNoReplaceInstrumentationTest#case01AbsentDestination io.warpnect.test/androidx.test.runner.AndroidJUnitRunner
```

Full JVM1383, focused199 and40 replay are historical receipts, not fresh gates.
Full lint/JVM/CTest/media campaigns were not repeated: none of their production
inputs changed. The unrelated Release-unit compile180-diagnostic/11-file failure
is unchanged and not reclassified PASS. No main Release or helper rebuild claim.

## Cleanup, Preservation And Final State

All eight cases closed owned descriptors and joined workers before deletion;
procfs found zero FDs pointing into each fixture directory. Each UUID directory
was removed only after those checks. Final `no_backup` empty, target/test fixture
packages uninstalled, no Warpnect/helper process remained. No synthetic Keystore
alias was created. Owned emulator/private server stopped; temporary fixture ADB
keys removed. Ports5051/5662/5663 closed; default ADB5037/PID7924 unchanged.

Start/final inventory compares14 protected worktrees, branch/HEAD/index, dirty-file
content hashes and metadata. Four pre-existing large original-research artifacts
retain the earlier metadata-only preservation scope, not a new full payload hash.
Original research496 entries, prior review5, platform validation8 and all other
protected states unchanged. Personal ADB identities/RootLab configs/global SDK
metadata unchanged. Master/upstream refs and Phase7 R2 hash unchanged. No commit,
push, merge, reset, stash, clean, trust change or physical-device operation.

Receipts are `C:/wg-pairing-native-publish-r1608/`: `preflight.json`,
`frozen-artifacts.json`, `installed-provenance.json`, eight method logs,
`runtime-results.json`, `final-logcat.log`, `fixture-cleanup.json`,
`runtime-cleanup.json`, build/signature/ELF logs, final integrity/preservation files.
Test/control scripts there contain no credentials; temporary private key removed.

**IMPLEMENTED / SOFTWARE TESTED:** test-only harness. **VALIDATED:** named
primitive/cases on exact emulator fixture only. **Not QUALIFIED or
PRODUCTION-READY:** encrypted Android store, full P2 or Release onboarding.

## Next Minimum Decision (Not Executed)

Separately authorize a reviewed API30-gated private production publisher delta:
no-replace rename plus an explicit source-consumed result and exact pending-file
cleanup/restart knowledge, bound to the existing opaque one-shot reservation.
Require actual store/P1 receipt races, revision/no-overwrite tests, sync/unknown/
cleanup failures, process-exclusion evidence and repeated isolated Android storage
instrumentation before promotion. Do not integrate this raw test bridge directly,
activate Release, add PAKE/NSD or P3-P5, or weaken old Unsupported/Unknown states.
This task stops at the primitive proof and leaves all new work uncommitted.
