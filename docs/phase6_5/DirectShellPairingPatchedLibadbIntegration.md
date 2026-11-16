# DirectShell Patched libadb Integration

## 2026-10-28 Amended Candidate Local Checkpoint

This is the explicitly authorized consolidation successor, not a new feature
increment. **P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED** applies to the clean,
two-commit software checkpoint once its post-commit receipt verifies both trees.
Retain **P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED**,
**P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED** and overall
**P2_PARTIAL_BLOCKED**. Historical **P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED** is
not fixed, root-caused or converted into an environment-only finding.

### Git And Exact Source Closure

Branch `codex/phase6-5-pairing-patched-libadb-checkpoint`, worktree
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-patched-libadb-checkpoint/Warpnect`.
Parent `be6beb9453b09f44340233ffbd3eca2577ef1c97`.
Verified source/test commit: **`9576fdca42b9c0b709ddd2af51464f3464324fd9`**.
The documentation checkpoint is its direct child containing this section.
Its full SHA is recorded after creation in the external
`C:/wg-pairing-checkpoint-r1620/checkpoint-committed.json` and
`CheckpointFinalReport.md`, and in the final response. A commit cannot embed
its own full SHA without changing that SHA; no amend/third commit is used.

The original frozen worktree is untouched. Selective byte-verified reconstruction
uses the r1619 amended candidate, not the dirty research worktree. Scope is exactly
19 source/build/test/tool paths and seven documents,26 total against the parent.
All19 committed source blobs match the tested LF-canonical identities; raw
worktree hashes also match the frozen candidate, including CRLF differences.

| Identity | SHA256 and algorithm |
| --- | --- |
| Original17 | `1873B582984F7DCD6F2BB723E71FA4E4B35161A253CDDA2B19EE46D07105C334`; sorted `path + NUL + LF-content SHA256`, joined by LF, no terminal separator |
| Approved test2 | `4ABE9B94B614541E71C9EB9E67D2524FD841E4FFA84075DBE21D6A69806CB965`; sorted `path + TAB + LF-content SHA256 + LF` |
| Amended19 | `669EDB51625CAF4CBCFF67831819D0BBA9F765A6BFFF11B307F982CD9B8A2041`; same TAB/terminal-LF algorithm as test2 |

SHA256 digests above hash the UTF8 manifest text with uppercase content digests.
Individual raw/LF hashes and exact full paths are in `candidate-start.json`,
`gate-ready.json`, `documents-final.json` and the committed-blob receipt. The
source tree contains the original17 files plus only
`DirectShellStorageProcess.java` and `DirectShellStorageProcessTest.kt` under
`app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/`.
The first commit's complete19-path diff-tree is verified, with no docs, AAR/APK,
generated objects, logs, temporary scripts, credential files or diagnostic agent.
The second commit contains only the seven documents listed by the current task.

### Approved Lease Contract Review

The [amendment report](DirectShellWindowsLeaseTestContractImplementation.md)
is preserved below its successor entry. Review PASS: no material test-contract
defect and **zero new production delta** relative to the original patched-libadb
candidate. Parent-owned stale bookkeeping cannot be accepted as a safe BUSY:
the captured actual FileLock must be invalid, FileChannel closed, and exact
canonical-path registry entry absent before and after the single inspection.
Reflection is read-only and never clears ownership. Authenticated success
requires a distinct acquired inspector lease, actual key/record validation,
correct committed-cleanup classification and resource release.

BUSY requires no returned lease, no inspector key/record access, reconciliation,
zero mutating filesystem calls through object close, unchanged whole ciphertext,
retained anchor/pending/hard-link identities, unresolved staged authorization
and zero resolution callbacks. It creates no retry, cleanup, replacement, Host
or Session authority. All other statuses/exceptions fail. A stale parent entry
or unexpected storage mutation fails the test rather than entering this branch.

Test A retains actual forced death while the child owns the lock, observed exit,
original bounded waits and exactly one immediate inspection, with no sleeps,
retries or longer timeout. It deliberately no longer guarantees immediate OS
availability after a crash. Test B independently requires an explicit R command,
real child lock release AND channel close before LEASE_RELEASED, one shared stdout
reader, normal exit, then strictly COMMITTED_CLEANUP_PENDING. BUSY fails Test B.
Strict G1-G6 same-JVM/registry/real overlapping-channel/live-process/no-replacement/
uncertainty coverage remains. The overlapping channel uses the unchanged real
JVM exception path, not an injected exception; sanitized BUSY is not retrospective
proof of the historical mechanism.

Fresh focused and full runs both observed A=COMMITTED_CLEANUP_PENDING,
B=COMMITTED_CLEANUP_PENDING, live-published G6=BUSY. Both BUSY safety and strict
recovery execute; no post-crash BUSY occurred this time. Historical r1615 remains
1506 PASS/one FAIL out of1507. r1616 independent controls and r1617 instrumented
1507 PASS remain separate evidence. The platform-contract basis and explicit
approval supersede the old temporal expectation, not its unknown failure cause.

### Fresh Checkpoint Verification

Windows11 Pro64-bit/build22631; JDK21.0.2 Gradle launcher, Temurin17.0.14+7
normal Test workers, Gradle8.12/Kotlin2.0.21, SDK35/NDK27.0.12077973. Existing
maxWorkers2, one Test fork/forkEvery0; no diagnostic agent, filters in the full
run, device access, ADB server, USB enumeration, NSD or real daemon.

| Gate | Fresh result, not historical substitution |
| --- | --- |
| Full Debug JVM | **1510/1510 PASS**,193 suites,0 failures/errors/skips; one declared unfiltered/noninstrumented run,29s |
| Focused P1/P2 | **326/326 PASS**,13 suites,0 failures/errors/skips; same323 baseline plus three approved process methods |
| Focused per-suite counts | coordinator96, deadline5, storage38, storage integration48, consumed-publication11, native publisher12, process6, transport33, protocol30, Android FS host24, Keystore host10, AEAD9, fixture protocol4 |
| Offline Flight/output media replay | **40/40 PASS**,0 skipped; source/media behavior unchanged |
| Reproduction | Two fresh independent patch builds equal each other and actual selected AAR |
| Required builds/format/lint | ktlintCheck,lintDebug,lintRelease,assembleDebug,assembleDebugAndroidTest,packageDirectShellProbeDex,assembleRelease PASS; software command25m58s,166 tasks/144 executed/22 up-to-date |
| Lint interpretation | Debug126 warnings/0 errors, Release34/0; strict historical134/42 equality is not PASS; exactly eight removed dependency advisories and verified unchanged trust-manager archive paths,0 new semantic findings, no suppression |
| Library/archive/DEX closure | **619 checks PASS**; exactly43 unchanged/seven changed/two added context classes; Debug24266 and Release15589 actual DEX definitions individually unique |
| Actual module/classpath/legacy APIs | **132 checks PASS**; five selected patched AAR configurations, two normal AGP test archive views with identical selected class bytes, no original/host overlay; BC1.84 and legacy APIs preserved, no Release libadb/BC |
| Source-set/manifest/Release UI |21 checks PASS; Release setup still unavailable, no new component/registration |
| P1 exact delta/model |19 exact-delta and34 model checks PASS; original deadline and120s/60s/3s, R/P/S/D/F, one attempt and drain fences unchanged |
| Storage/adapter/publisher |10 compiled storage,14 adapter/discovery and36 source publisher checks PASS |
| Native compiled isolation |6 existing Debug/Release Flight guards across three ABIs plus three private publisher ELF/exports/import guards PASS; not CTest or Android execution |
| Android methods | Exactly45 compiled annotations freshly discovered (18 storage/8 lifecycle/8 native/11 multiprocess); **zero executed** |
| Pre-commit evidence admission |730 checks PASS, exact19+7 manifest, unchanged source, archived XML and original r1615 failure preserved |
| Source index/commit |68 checks PASS,19 exact paths and LF blobs; no implementation changed after the tests |

All application Java/DEX/resource/manifest payloads are identical to r1614 except
the explicitly inventoried rebuilt native payloads. Debug differs in six entries:
three SCL libraries and three microphone-clock libraries. Release differs only
in three SCL libraries. AndroidTest and helper are byte-identical. For all three
Debug microphone-clock libraries, only `.note.gnu.build-id` differs; `.text` and
`.rodata` are identical. SCL contains the new embedded build path, with layout/
relocation/text/rodata/build-id differences from the new worktree; it is NOT
asserted instruction- or binary-identical. Source/compiler inputs remain
unchanged and compiled guards pass. No deterministic whole-APK identity was
promised or forced. Full entry/section hashes are in `artifact-comparison.json`
and `native-elf-comparison.json`, not attributed vaguely to timestamps.

| Fresh frozen artifact | SHA256 |
| --- | --- |
| Debug APK | `3BAC4BA4C01AB62F1D681A953F3F8DE1AC50AEEE7DE2FEF691858D0E9D9D5C4E` |
| AndroidTest APK | `D2A6FD9A78422E5BEDC029E8CF4302F9A703FDE688FF15B7B0437CA050123B7C` |
| DirectShell helper JAR | `F9DD2B9BDA297C960E7F4EA9DAD71174EE3B032A53D796440F15578B75499B53` |
| Unsigned Release APK | `A12DB99CB8B9084CE275E1B84B75F45E1AC06EA3F583FEE50C28A22F6E0C69CE` |
| Selected/reproduced patched AAR | `BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0` |

Original AAR remains62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB;
original classes.jarE230CF15535F44551DDFFF6058EA99209AC4560F7C4210C1B2E563FCB4A8D8DE,
patched classes.jarE63D6B2771B204CA55EB7328F87472040C01A837FEB589C10D3769FC85577A33.
Pinned upstream revisiondcd931071b5e4b9ebc0ac598201755d5a8814b71 and exact source
patch/Apache-2.0 notices are unchanged. The original module and all43 unrelated
class bodies remain; no replacement library, upgrade or broad fork. Actual APK
signatures verify with unchanged public fingerprint
8AA8738920C8C0942537F7DD71ED291DF508B850EDB7E5A4D2A8DF49813CD969.
`artifact-freeze.json` binds the19-source cohort to these outputs; read-only
committed LF checks bind that cohort to the source commit.

### Hygiene, Preservation And Final Scope

Raw staged `git diff --check` reports16 trailing-space warnings **only** on
literal blank context prefixes in `connected-socket.patch`. These are required
unified-diff data, verified exactly against Git's generated source diff. All
staged code/build/license paths pass whitespace checks; no attributes, lint
rules, assertions or source bytes are changed to suppress the raw data warning.
`staged-raw-diff-check.log` and `source-index.json` retain the distinction.

Two new external-harness negatives are retained: an initial manifest classifier
counted the tool README as an eighth document (before copying anything), and a
PowerShell timestamp auto-conversion broke the first process-closeout parser.
Both were corrected only in external scripts, with unchanged original receipts,
source and actual test executions. All observed Test workers/children exited;
FileLock/channel/registry cleanup is asserted. JUnit owns temporary directories;
no broad cleanup or independent per-TEMP-directory deletion claim is made.

Historical lint comparisons, Release-unit compilation180 diagnostics/11 unchanged
DEBUG-dependent tests, earlier cancellation/packaging/bootstrap failures, USB
enumeration incident and SDK metadata-only drift remain preserved. Successful
assembleRelease does NOT repair or pass that extra Release-unit compile gate.
No full CTest, Android instrumentation, emulator or hardware test executed.

All23 protected other worktrees, original17+6 frozen candidate, original research
and diagnostic branches/evidence remain unchanged in their recorded HEAD/branch/
index/dirty-file inventories. Original research retains496 dirty entries with
the four inherited large-artifact metadata-only exceptions. Master/upstream and
Phase7 R2 hash remain unchanged. Exact post-commit receipts verify source/doc
direct ancestry,19/7 committed paths, LF blobs, empty index and clean checkpoint;
ignored build outputs remain intentionally available. No push, merge, reset,
stash, clean, device operation or production configuration change.

Receipts: `C:/wg-pairing-checkpoint-r1620/`, including source/doc manifests,
focused/full XML and logs, reproduction/classpath/closure, lint, signatures,
artifact/ELF hashes, static/native guards, pre/post preservation and committed
blob identities. Original r1614-r1619 evidence is not overwritten.

**IMPLEMENTED / SOFTWARE TESTED / LOCAL CHECKPOINT VERIFIED** only. No new Android
VALIDATED, hardware QUALIFIED or PRODUCTION-READY claim. Still open: real adbd
interop, Android TLS blocked-write cancellation, suspend-inclusive connect,
production key lifecycle, Network/NSD endpoint identity, combined storage/
transport transaction, real pairing-device/OEM/API qualification and Release
onboarding. Global minSdk26 and Wireless Debugging eligibility API30+ remain.

Smallest next increment: separately review/authorize a controlled synthetic
P1 + recovery-anchor storage + owned-protocol composition, explicitly labeling
modeled directory sync and requiring joint transport/storage/code drain. It is
**NOT STARTED** here. No real pairing, Host activation, P3-P5 or Phase7 work.
G1-G5 hardware remains PARTIAL; historical media debts remain OPEN. Phase6.5 is
not complete. Historical sections below retain their original scopes.

2026-10-28. **P2_LIBADB_PATCH_PACKAGING_VERIFIED** and
**P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED**, exact Windows/JDK21
localhost fixture only. Overall **P2_PARTIAL_BLOCKED**.
No real Android daemon, pairing, trust modification, device access or Host activation.

## Baseline And Authorization

Transport source checkpoint: `c5577c776300d99d2b69a00b99584295b0f06184`.
Parent/HEAD: `be6beb9453b09f44340233ffbd3eca2577ef1c97`.
Branch: `codex/phase6-5-pairing-libadb-owned-socket-integration`.
Worktree:
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-libadb-owned-socket-integration/Warpnect`.

The user's current explicit instruction approves the DEBUG class-closure
substitution that the [previous feasibility review](DirectShellPairingProtocolAdapter.md)
left pending. This is not an accepted RFC rewrite or general library-fork approval.
The previous review's five uncommitted documents were individually SHA256 checked
against `C:/wg-pairing-protocol-r1613/final-integrity.json`, then copied as
documentation evidence only; no dirty branch/source was merged. Provenance:
`C:/wg-pairing-libadb-r1614/review-document-provenance.json`.
Earlier libadb source/patch/proofs remain unchanged and were not imported as code.

The implementation remains uncommitted, index empty. External non-secret
receipts: `C:/wg-pairing-libadb-r1614/`. Native/media/provider/Session/security,
storage publication and all accepted RFC sources are unchanged. P1's amendment
is restricted to owner-originated immutable deadline metadata and optional dispatch.

## Source Closure

The exact 17-path raw/LF hash manifest is external `source-freeze.json`; LF cohort:
`1873B582984F7DCD6F2BB723E71FA4E4B35161A253CDDA2B19EE46D07105C334`.

| Path | Delta |
|---|---|
| `app/build.gradle.kts` | DEBUG artifact transform, compiler-only configuration, reproduction tasks |
| `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingContract.kt` | Additive owner work metadata and optional interface |
| `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinator.kt` | Original deadline optional dispatch only |
| `app/src/debug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingTransport.kt` | Distinct verified-peer completion; original lifecycle unchanged |
| `app/src/debug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellOwnedPairingProtocol.kt` | Unwired owned-socket adapter |
| `app/src/test/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingDeadlineHandoffTest.kt` | Five portable deadline tests |
| `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellOwnedPairingProtocolTest.kt` | Thirty host adapter/protocol methods |
| `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/OwnedPairingPeerFixture.kt` | Synthetic mutually credentialed TLS/PAKE peer |
| `app/src/testDebug/java/android/util/Log.java` | Host-only Android logging shim |
| `buildSrc/build.gradle.kts` | Build-only Java helper registration |
| `buildSrc/src/main/java/io/warpnect/buildlogic/OwnedPairingTransform.java` | Pinned transformation and deterministic reproduction |
| `tools/libadb-pairing/upstream/PairingConnectionCtx.java` | Immutable pinned source |
| `tools/libadb-pairing/patched/PairingConnectionCtx.java` | Reviewed context delta |
| `tools/libadb-pairing/connected-socket.patch` | Exact source diff |
| `tools/libadb-pairing/VerifyOwnedPairing.cjs` | Class/archive/DEX verifier |
| `tools/libadb-pairing/README.md` | Reproduction, provenance and limitations |
| `tools/libadb-pairing/LICENSE-Apache-2.0` | Complete selected license |

The six documentation paths are this report, CURRENT_HANDOFF, ProtocolAdapter,
TransportCancellation, ReleasePairingArchitecture and FutureRoadmap. No APK,
generated JAR, runtime receipt, key or test credential is part of Git source closure.

## Original Dependency And Reproducible Packaging

The dependency stays
`com.github.osservatorionessuno:libadb-android-bc:3.4.0`; no new ADB library,
version upgrade, local Maven replacement or general ADB implementation.
Pinned source revision: `dcd931071b5e4b9ebc0ac598201755d5a8814b71`.
Original `PairingConnectionCtx.java` was read from the immutable Git blob, not the
dirty old research clone. Its LF SHA256 is
`9E7B43ECCCC21B9DC75146BB7AEAEF9F788404EECD95943061512878E9A7D899`.

| Artifact | SHA256 |
|---|---|
| Original AAR | `62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB` |
| Patched AAR | `BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0` |
| Original classes.jar | `E230CF15535F44551DDFFF6058EA99209AC4560F7C4210C1B2E563FCB4A8D8DE` |
| Patched classes.jar | `E63D6B2771B204CA55EB7328F87472040C01A837FEB589C10D3769FC85577A33` |

`buildSrc/.../OwnedPairingTransform.java` is build-time code only. A custom
artifact attribute is requested by resolved DEBUG configurations, not by
Release. The original Maven component and its dependency metadata remain selected;
only the target AAR's bytes are transformed. Other AARs are registered as exact
input-file pass-throughs. The pinned original AAR SHA is checked before compilation.
No writes target the immutable Maven artifact; ordinary Gradle derived-transform
caching is used. No generated AAR/JAR is committed.

The original context closure is removed from `classes.jar`, then replaced by the
one reviewed Java source compiled against original library/transitive classes and
the project's Android SDK. No unrelated Java source is compiled into the artifact.
All non-context class bytes, AAR entry payloads outside classes.jar and module
dependencies are preserved. Fixed ZIP ordering and local 1980 timestamps avoid
build-time/path/timezone entry metadata. Compiler bytecode target is Java 8;
toolchain/classpath provenance is part of reproduction, not assumed cross-JDK identity.

`verifyOwnedPairingReproduction` builds in two independent directories and compares
both with the actual Gradle-selected AAR. It is not a test JVM classpath overlay.
The artifact verifier records every changed/added and all 43 unchanged class hashes.
Seven original context/nested class entries are recompiled; two are added:
`PairingConnectionCtx$AuthenticatedPeerInfo` and the compiler's enum-switch helper.
No entry outside that closure is changed or deleted. Recompilation/debug-metadata
differences within otherwise unchanged nested classes are explicit, not hidden.

Five actual DEBUG compile/runtime/unit/instrumentation-compile configurations
select the same patched AAR. Instrumentation runtime intentionally excludes the
tested-app library, which is supplied by the target APK. The actual host Test task
has two AGP archive views (runtime.jar and jars/classes.jar), as it also does for
unmodified Material3. Both contain exactly the same 52 selected library class
bodies; no original context or third overlay is accepted. An initially incorrect
single-JAR guard failed and is preserved. The corrected check verifies both whole
class views byte-for-byte; strict uniqueness remains required for final Debug DEX.

Reproduction and license instructions: [tools/libadb-pairing](../../tools/libadb-pairing/README.md).
The upstream file offers GPL-3.0-or-later OR Apache-2.0; the Apache-2.0 option is
selected for this derived source, retaining its SPDX/provenance and complete
license text. This does not relicense unrelated dependency files. A source diff,
immutable upstream copy and derived source are retained; no opaque binary patch.

## Connected-Socket And Protocol Delta

Original call graph remains available:

```text
legacy DEBUG controller -> manager.pair (manager monitor, caller thread)
 -> original constructors -> start() -> new Socket(host,port)
 -> original BC TLS1.3/exporter -> pinned PAKE -> framed peer-info
 -> original manager API completion
```

The new opt-in path is separate and unregistered:

```text
explicit P1 submission -> original absolute work context
 -> operation-owned transport -> one connected raw socket
 -> simulated/ future real durable-recovery admission
 -> worker-only borrowed identity -> patched startConnected(socket, endpoint, checkpoint)
 -> original BC TLS1.3 / adb-label NUL exporter
 -> original PairingAuthCtx / SPAKE2 / HKDF / AEAD
 -> original bounded ADB frames / encrypted RSA client peer-info
 -> validated peer-info evidence
 -> owner raw close -> worker protocol release / code wipe -> observed worker exit
```

`startConnected` verifies connected/not-closed/not-shutdown state and exact
pre-resolved remote endpoint. It does not create/connect a second socket, retry,
start a worker or set another timeout budget. Legacy constructors and public
methods remain; non-pairing manager/connection/shell classes are byte-identical.
The old experimental unconnected-socket/12s-stage patch was not reused.

The same original TLS client drives TLS1.3, exporter capture, RSA client credentials
and PAKE. CPU-only owner checkpoints are added before writes/reads and after
blocking stages, before further IO. Already-entered IO can race stop; no rollback
is inferred. Only the owning worker computes or destroys PAKE state. Stop closes
the raw socket, not the library context concurrently. `releaseConnected` releases
protocol/password resources after owner close; failed/unknown release cannot prove D.
The legacy path is not represented as newly cancellable. Raw peer-info logging is
removed in both paths; the original legacy wire/API behavior is retained.

## Authenticated Evidence And Its Limits

The [AOSP Android 11 PeerInfo definition](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-11.0.0_r1/adb/pairing_connection/include/adb/pairing/pairing_connection.h)
uses type 1 for the device GUID and a fixed 8192-byte record. The connected path
requires that type, successful AEAD, exact record length, and nonempty bounded
NUL-terminated GUID data. It does not invent a UUID format, 255-byte maximum,
ASCII/whitespace rule or mDNS identity. Tests accept opaque space-containing
labels and a 500-byte GUID while rejecting empty/unterminated fields.

An immutable result defensively copies opaque GUID data, the observed pairing TLS
certificate SHA256, local certificate SHA256 and admitted endpoint. It is bound
to the PAKE/exporter channel, not to a CA, mDNS advertisement, hardware identity
or proof that adbd persisted trust. The local certificate hash names the supplied
identity; it alone does not prove that a server requested or retained that certificate.
The synthetic peer separately requests, verifies and checks the actual TLS client
certificate and independently parses the Android RSA public-key encoding.
Both fixture roles use the test-owned synthetic identity; this does not establish
independent physical-device identities or production provisioning.

No raw key, certificate, pairing code, peer-info payload or arbitrary exception is
exposed in UI/receipt rendering. The connected API sanitizes ordinary exceptions;
the transport retains only closed lifecycle categories. Result rendering is redacted.
The adapter loads/borrows identity only on the IO worker. Caller-owned identity
material remains outside protocol drain; future storage/identity owner cleanup is
not implemented or falsely acknowledged. Owned code/password copies are wiped;
no claim of erasing every internal JVM/crypto allocation is made.

Completion is `PEER_INFO_VERIFIED`, not `AUTHENTICATED` Android trust or a staged
credential grant. `externalEffect` remains conservatively POSSIBLE. After sending
client peer-info, remote trust may already be possible even if reply/cancel fails.
The synthetic peer never mutates Android trust. No credential publication,
identity adoption, Host, provider, planner or Session authority follows.

## Authoritative P1 Deadline And Ownership

P1 still sets workDeadline at code submission, before backend construction.
An optional `DeadlineAwarePairingOperation` receives a read-only
`DirectShellPairingWorkContext`: the exact ticket reference, original absolute
deadline and the same injected suspend-inclusive clock/scheduler. Legacy operations
keep their original start dispatch; the optional interface's context-free entry
fails closed. No consent, reservation, revision, publication or finalization rule
is changed. A text-exact delta guard proves all prior state-machine code remains.

The new Protocol adapter requires a one-shot matching work binding and exact
endpoint. Missing/reused/foreign context is rejected. Its own checkpoints compare
the original cutoff as well as the transport stop signal. Neither adapter nor
TLS/PAKE stage derives now+60s. Factories/binding are CPU-only and inert; no file,
crypto, socket I/O or join is introduced under the coordinator monitor.

120s consent / 60s total work / 3s cleanup observation, one active attempt and
no queue remain. `System.nanoTime` is used only by the unchanged bounded external
join observation, not as an Android enrollment clock. A cancelled/expired lifecycle
can retain already-verified protocol knowledge. No actual worker exit/socket closure
is inferred from a callback or logical timeout. Future composition must still combine
transport D, storage D, identity/code-copy drain and P1 F; R/P/S are unchanged.

## Host Test Matrix

New suites: 30 DEBUG adapter/protocol methods and five portable P1 deadline methods.
All test peers use exact IPv4 loopback and ephemeral ports, synthetic identities
and a fixture-only code. IPv6 eligibility remains inherited unit coverage, not a
new IPv6 IO qualification. No daemon/ADB server/NSD/USB/device fixture is used.
A test-only no-op `android.util.Log` shim supplies the absent host framework method;
no library protocol class is shadowed. APK isolation excludes both host shims.

| Case | Executed scope |
|---|---|
| A01 | Inert construction/factory; missing work binding fails before identity/IO |
| A02/A03 | Actual patched context, same owned socket, exact result endpoint; one socket creation |
| A04/A05 | Actual pinned TLS handshake and PAKE receive stalled; raw close interrupts and actual workers drain |
| A06 | Blocked TLS-write cancellation remains UNVERIFIED; valid bounded pairing payloads do not provide a reliable native backpressure fixture here. Historical actual 8MiB raw-TCP write is not promoted to TLS/Android evidence |
| A07/A08 | Wrong code; type/version/size/tag/GUID and EOF rejection; no authenticated credential or Host effect |
| A09 | Synthetic full mutual-TLS/PAKE exchange; TLS client certificate and 2048-bit Android RSA encoding independently checked |
| A10/A15 | Barrier after verification / after client peer-info; cancellation cannot erase protocol knowledge or assert no remote effect |
| A11/A12 | Exact ticket/clock/scheduler and consumed deadline; expired dispatch, actual TLS/PAKE expiry, partial response and late result do not renew work |
| A13/A14 | Repeated stop, held protocol cleanup and actual P1 aware dispatch; cleanup observation does not prove drain or admit another attempt |
| A16 | Previous-owner context cannot dispatch a newer ticket; old receipt stays origin-bound; inherited P1 late-callback regressions retained |
| A17 | Released code rejected before socket/identity; original regression retained |
| A18 | Legacy API availability and exact built-artifact/DEX/Release guards, not just source compilation |
| A19 | Every actual socket/worker/code/protocol test cleanup asserts drain; identity borrowing remains separately owned |
| A20 | No storage/provider/Session/Host effect; direct socket-state rejection and no passive IO |

All race ordering uses barriers/injected clocks, not sleeps. Time-bounded awaits
are observation limits, not proof of cancellation. Recovery readiness in these
tests is explicitly simulated attestation; no production anchor or key is provisioned.
Real protocol computation and client credentials are exercised, but the peer is
not Android adbd and remote persistence/interoperability is not qualified.

## Software Verification

Reference 1472 JVM / 288 focused / 191 suites remains the frozen transport scope.
New complete JVM execution: **1507/1507 in 193 suites**, delta +35 tests/+2 suites,
zero failures/errors/skips. The initial focused run is 164/164 (30 adapter,
5 new deadline, 33 existing transport, 96 existing P1). Those 129 inherited tests
are unchanged, not weakened. Full/focused final XML identities are preserved externally.
Existing Windows child-process lease failures remain historical/unattributed;
absence in a new run does not resolve them. The extra historical Release unit-test
compile failure (180 diagnostics/11 unchanged DEBUG-dependent tests) is not rerun,
fixed, or represented by assembleRelease success.

Final full build, lint, focused/storage, closure and preservation results are
recorded in the final receipt below. Android instrumentation/emulator/device
tests and native CTest are NOT EXECUTED. Native inputs did not change; compiled
ABI/symbol guards do not imply CTest or Android transport qualification.

## Preserved Negative Evidence

The first packaging attempt incorrectly put the request attribute on the component
variant and failed resolution. The next exposed an offline compiler-only Kotlin
stdlib version not in cache. Artifact-level attributes preserve original variant
selection; the compiler-only configuration explicitly uses the app's existing
Kotlin 2.0.21. Neither fix changes the DEBUG runtime graph or weakens input pinning.
A further compile diagnosed an eagerly empty Android boot classpath; the transform
now declares the exact current SDK android.jar as an input. All negative logs remain.

The first fixture compilation attempted package-private AndroidPubkey access and
failed. It now independently parses the serialized RSA key; no production API
was exposed for tests. A Windows mapped-file failure prevented ktlintFormat from
rewriting the build script; source-format tasks were scoped separately and final
ktlintCheck still covers the scripts. An intermediate test compilation saw a newly
edited API before its class output and failed; the source cohort was then held
stable for compilation. The new nullability warning was corrected, not suppressed.

A first overly broad source guard rejected the single deferred socket factory.
The retained negative is followed by an exact one-factory assertion and proof of
no second socket/connect/worker/store/Host path. No application code was changed
to hide that guard failure. Original r1601/r1611/r1612/r1613 failed controls,
assistance-stop/reassessment, USB enumeration history and SDK timestamp-only
deviation remain unchanged with their original scopes.

The source-closure freeze also caught a real reproducibility defect: the helper's
initial package directory was named `build`, matching the repository's existing
`build/` ignore rule. It was moved to `io/warpnect/buildlogic` before the final
17-path freeze. No global ignore rule was weakened and no force-staging was used.
All applicable final gates are rerun after that packaging-source correction; the
earlier successful full run remains a separate receipt, not rewritten.

Strict historical lint equality comparisons retain FAIL: Debug134->126 and
Release42->34. Exactly eight old GradleDependency update advisories are absent
in the offline run; declarations/versions are not upgraded. Two existing Debug
TrustAllX509TrustManager warnings move from the original to transformed archive
path. Those non-pairing class bytes are unchanged. A separately recorded semantic
comparison permits only those verified path changes and the eight removed
advisories: zero new findings, zero errors, no warning suppression. It does not
declare the existing trust-manager warnings fixed or erase the strict negatives.

## Remaining Gates And Recommendation

Still OPEN: real adbd interoperability, Android blocked TLS writes/suspend-inclusive
connect behavior, ARM/OEM TLS provider behavior, endpoint/Network/NSD identity,
production key/recovery lifecycle, combined storage/transport transaction and
Release onboarding. Prior API30 emulator storage validation is not transport evidence.
No synthetic result is eligible for production credential adoption or Host activation.

Smallest follow-up: review/checkpoint this exact software cohort, then separately
authorize a synthetic end-to-end P1 + existing storage-anchor + owned-protocol
composition with a single operation owner and the same deadline. First prove
pre-PAKE durability admission and joint drain against controlled fixtures, without
real daemon trust, NSD or Release registration. Do not automatically execute it.

G1-G5 hardware remains PARTIAL, historical media defects stay OPEN, Phase7 R2 stays
PROPOSED. Global minSdk26 and API30+ Wireless Debugging eligibility are unchanged.
No P3-P5, Root/Shizuku, provider migration or Phase7 work; Phase6.5 is not complete.

## Final Receipt

The final 17-source closure matches LF cohort
`1873B582984F7DCD6F2BB723E71FA4E4B35161A253CDDA2B19EE46D07105C334`.
After the buildlogic package/source-closure correction, the entire requested
build command passed again in23m28s,173 tasks (172 executed/1 up-to-date).
The earlier successful command took31m15s/173 executed and remains a separate
receipt. No failing test was rerun until PASS: the actual test executions passed;
the source packaging fix, compile/static/harness negatives are preserved separately.

| Gate | Final evidence |
|---|---|
| Full Debug JVM |1507/1507,193 suites,0 failures/errors/skips; +35/+2 over parent |
| Focused P1/storage/transport |323/323,13 suites,0 failures/errors/skips; +35 over parent |
| New suites / unchanged suites |30 adapter +5 deadline; all96 old P1 and33 old transport tests unchanged |
| Offline media replay |40/40 PASS |
| ktlintCheck / lintDebug / lintRelease |PASS; Debug126/0 errors, Release34/0 errors; strict old equality FAIL retained, semantic comparison0 new findings |
| Debug / AndroidTest / helper / unsigned Release builds |PASS; exactly45 instrumentation methods discovered,0 executed |
| Source-set/DEX/manifest |21 checks PASS |
| P1 model / exact additive source/build delta |34 scoped model +19 exact delta checks PASS; no reservation/cancellation-policy change |
| Storage / Android adapter / publisher guards |10/14/36 PASS; contract-unchanged assertions amended only for the exact verified P1 handoff |
| Native symbol/ABI isolation |6 PASS, three Debug/three Release; no native input change or CTest claim |
| Original/patched archive and actual DEX closure |619 checks PASS; all43 unrelated classes byte-identical, seven changed/two added in context closure only |
| Actual variant/module/test classpath |132 checks PASS; five selected AAR views, both AGP JVM archives byte-identical to that artifact; no protocol overlay |
| Reproduction |Two independent builds equal each other AND the actual selected AAR |
| Android/platform/real adbd |NOT EXECUTED / NOT QUALIFIED |

Frozen copies: `C:/wg-pairing-libadb-r1614/artifacts/`.

| Build artifact | SHA256 |
|---|---|
| Debug APK | `9154F92D07DD5E5E70F3D3E5A7F3A099ECA9C13993515081F47EA86CC23553B0` |
| AndroidTest APK | `D2A6FD9A78422E5BEDC029E8CF4302F9A703FDE688FF15B7B0437CA050123B7C` |
| Helper JAR | `F9DD2B9BDA297C960E7F4EA9DAD71174EE3B032A53D796440F15578B75499B53` |
| Unsigned Release APK | `F16BD398496316DE3CD875D22F1BEBA3CC5F76B25B69BA040683DD30474DCC76` |

Debug and AndroidTest v2 signatures verify with the unchanged public signer
SHA256 `8AA8738920C8C0942537F7DD71ED291DF508B850EDB7E5A4D2A8DF49813CD969`.
No installation or Android execution is part of this provenance chain. APK/DEX
class uniqueness and legacy API availability are not inferred from compilation
alone. The full per-entry unchanged/changed/added hashes are in
`library-artifact-closure-final.json`; module and tested-class hashes in
`classpath-audit-final.json`; source/app artifact mapping in source/artifact-freeze.

All19 protected worktrees match the initial HEAD/branch/index/dirty-file hash/
recorded metadata inventory; original research remains496 dirty entries, with
the same four preexisting metadata-only large-artifact exceptions. master,
upstream and Phase7 R2 hash are unchanged. Final Git: HEADbe6beb9, index empty,
17 source/build/test/tool paths plus six documentation paths uncommitted. No
commit, reset, stash, clean, push, merge or broad dirty-source import.

Final `C:/wg-pairing-libadb-r1614/final-integrity.json`:294 checks PASS, including
all individual full/focused XML hashes and the exact source/documentation manifest.
Two final harness negatives are retained: treating required unified-diff blank
context prefixes as source trailing whitespace, and trimming the leading status
column before parsing Git porcelain. The fixes validate the patch against exact
Git-generated diff output and preserve the porcelain status columns. No source,
test assertion or frozen artifact was changed to satisfy either guard.

Receipts also retain individual XMLs/logs for full/focused initial and post-closure
runs, all static/symbol guards, both strict lint negatives and semantic comparison,
expected source/API failures and original source inventories. All test-owned
socket/worker cleanup assertions pass and the test JVM exits. No device, USB,
ADB-server, emulator, NSD or production credential operation was issued.

**IMPLEMENTED / SOFTWARE TESTED**, not Android VALIDATED, QUALIFIED or
PRODUCTION-READY. A06 blocked TLS-write behavior, real Android connect/suspend,
endpoint/key lifecycle and combined production transaction remain open. Overall
P2_PARTIAL_BLOCKED. Stop here; no real pairing, Release activation or P3-P5.
