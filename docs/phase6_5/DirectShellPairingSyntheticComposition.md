# DirectShell Pairing Synthetic Composition

## 2026-10-29 Independent Review And Checkpoint (r1626)

**P2_SYNTHETIC_COMPOSITION_CHECKPOINT_VERIFIED** is earned only by the final
two-commit/blob/preservation receipt `checkpoint-final.json`,not the earlier
r1625 PASS alone. Fresh mandatory gates and independent review PASS. This is an
independent review/consolidation of the exact r1625 implementation,not a new
feature or permission to execute Android pairing. Overall **P2_PARTIAL_BLOCKED**.
The r1625 implementation record below retains its historical scope.

### Provenance And Change Control

Verified parent `688f13bd028265c2c75109c2e6949be85daf2bd5`,including source
ancestor `5c951ecda3693981918ad8c128c02de76d679f9f`. New branch
`codex/phase6-5-pairing-p2-synthetic-checkpoint`,root
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-p2-synthetic-checkpoint/Warpnect`.
Exactly five source/test and three documentation paths were selectively copied
with raw/LF hashes from r1625; no dirty branch merge. The frozen source cohort
remains `2E1EFC4800A1E162061CC120C4D3DF685271A2FFAF02075EC54AD2683069ED06`.
No application/test correction,accepted RFC change or new protocol patch.
29 worktrees total;28 others protected,including the uncommitted r1625 candidate.
Receipts: `C:/wg-pairing-synthetic-checkpoint-r1626/`.

Verified source/test commit: **`3547a51ca2cf75af7e4c1259f6d428f44185aaef`**,
exactly the five reviewed paths. Its direct documentation child contains exactly
the three reviewed documents. The full documentation SHA is recorded after
creation in external `checkpoint-committed.json`,`checkpoint-final.json` and
`CheckpointFinalReport.md`,avoiding a self-referential commit/amend.271 fresh
pre-commit admission checks PASS. Final receipts additionally verify committed
LF blobs against the tested cohort,index empty,worktree clean,no generated
artifact tracked and all28 protected worktrees unchanged. No push or merge.

### Independent Security Review

PASS within the controlled host-localhost scope,no material defect identified.
Full source-anchored execution/ownership map,critical race analysis and case
coverage review are in `IndependentSecurityReview.md` and
`review-acceptance.json` in the receipt directory.

| Boundary | Actual reviewed source and conclusion |
| --- | --- |
| Authorization | Unchanged real P1; exact ticket/work/clock/scheduler,one active attempt;120s consent,60s original work,3s cleanup observation |
| Recovery | storage.recoveryAdmission:133 checks exact successful begin ticket,valid exclusive lease,existing key and unchanged authenticated anchor; no mutation,repair,key creation or detached authority |
| Pre-TLS | composition:162,179 revalidates real storage; transport checks active again after validation; the owned composition worker cannot close storage concurrently with that I/O worker |
| Deadline | transport.awaitWork:238 consumes original remaining budget; interruption/expiry never proves worker death or renews authority |
| Binding | composition.bindEvidence:290; actual same-instance verifiedPeer,exact work/ticket,captured owned socket/admitted+actual endpoint,local recovery certificate and actual remote digest |
| R/P/S | composition:217,220,222 uses only observed prepared,existing exact one-shot R and real storage P/S outside P1; no legacy AUTHENTICATED projection or replacement |
| D | composition.awaitDrain:260 externally observes actual thread death,transport receipt,storage/code/borrow release; no self-attested worker exit,duplicate delivery or newer-owner fence clearing |
| F | Unchanged P1 matching verified/POSSIBLE observation plus valid R/P,SYNC_CONFIRMED,cleanup,currentness,recovery and D; unknown P/S remains fenced despite physical drain |

The ownership guarantees concern cooperating writers and trusted in-process
ports,not a compromised same-UID process. Revalidation is not an atomic OS
transaction spanning arbitrary external lease destruction and TLS. The actual
owned execution graph excludes that competing closer. bindEvidence is trusted
internal glue,not a public unforgeable socket capability. No GUID,address,
fixture digest or certificate pin alone grants authority.

All31 C01-C26 methods and five targeted discriminators were independently read,
including stimulus/assertions and real versus modeled evidence. Fresh400/16
focused and1584/196 complete JVM executions PASS,zero failures/errors/skips.
The single full run was declared after source freeze,unfiltered and without an
agent; JDK17 test worker,maxWorkers2,maxParallelForks1,forkEvery0. Exact XML,
method/suite counts and C01-C26 mapping are archived,not historical substitutes.

Physical D is explicit owner-side host observation,not an automatic Android
dispatcher. FileLock invalidity,FileChannel closure and exact-path registry
absence are independently asserted; held worker/lease tests block false F.
Fixture peer/child lifetimes are separate from P1 D. All10 observed test/child
PIDs exited. Positive directory synchronization remains modeled; real host
files,AES-GCM,file force,OS leases and no-replace links are not Android/power-loss
qualification. No JVM heap erasure or fixture key destruction is claimed.

### Fresh Artifact And Regression Verification

Fresh40/40 offline media tests and the two independent patched AAR reproductions
plus actual selected BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0
PASS. Debug,AndroidTest,helper,unsigned Release,ktlint,lintDebug/lintRelease PASS;
software gate30m22s,166 tasks144 executed22 up-to-date. Fresh classpath audit8s.

| Fresh gate | Result |
| --- | --- |
| Pinned library closure |619 assertions PASS;43 unrelated entries unchanged; legacy API and exact approved class closure retained |
| Classpath/API/module audit |132 PASS;5 selections; no host protocol overlay or Release libadb/BC promotion |
| Source/inherited methods |46 PASS; exact P1 and every original storage/transport method unchanged |
| Source-set/manifest |21 PASS; no component,SDK,provider or normal backend registration change |
| P1 model/observation |34 model and18 compiled guards PASS; legacy semantics and observed D1-D7 preserved |
| Storage/Android adapter |10 storage and14 adapter compiled guards PASS |
| Composition |8 compiled and19 actual DEX guards PASS; fixtures excluded,definitions unique |
| Native publisher/media |36 publisher-source,3 private ELF ABI/import and6 media/native symbol guards PASS |
| Helper |All19 DEX entries exactly match the current Debug APK |
| Android test discovery |45 methods compiled,0 executed; no emulator/device/instrumentation |
| Native CTest |NOT EXECUTED; native source/build inputs unchanged |

Lint126 Debug/34 Release findings,0 errors and0 new semantic findings. The
historical strict warning equality remains FAIL_RETAINED: exactly8 removed
GradleDependency advisories per variant,plus only proven unchanged-libadb archive
path normalization. No suppression or dependency upgrade. The separate historical
Release-unit180-diagnostic/11-file compile limitation was not executed or fixed.

113 Debug and96 Release pairing classes were enumerated.112/113 Debug and96/96
Release classes match r1625 bytes. The one Debug IdentityBorrow difference is
limited to Compose's stability initializer: load the identical producer's
constant8 versus inline8. Actual javap shows unchanged operational methods/API
and the same value; compiler root cause is not independently reproduced.
All113 current Debug pairing classes match the actual full-test runtime classpath
archive. This is not a global byte-reproducibility claim. Compared to immutable
r1625 outputs,Debug/helper classes13.dex differs;AndroidTest is byte-identical.
Debug additionally differs in6 native entries and Release only in3 libscl_core.so
entries. Actual libscl_core .text/.rodata differ in all3 ABIs/both variants,with
workspace/layout evidence; that is not proven instruction equivalence or proof
that paths explain every difference. The3 Debug microphone-clock libraries have
identical .text/.rodata and differ only in build-id. Fresh ABI/symbol guards pass;
no new security/ABI regression identified. Exact entry/section manifests retained.

| Fresh artifact | SHA256 |
| --- | --- |
| Debug APK |A1E733361EA9FA1291AE3E9AF1A698EA1A8B30E7A31E11A60FFB8BEFC49E6ECB|
| AndroidTest APK |F0F744CB26AF83C630C8A9DA52E3BCFF063594FF1C9B8D973E32C9E62A0ED943|
| DirectShell helper JAR |47D1174E63C74BFDC01CECA64DBA477EEB0FBBBAA8FBD4608D81DD4BDA53F952|
| Unsigned Release APK |732D2AA0194B859F04F28AACF5B18280ACA28ADE5F48B92E203E1E7FA1DF0D84|
| Selected patched AAR |BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0|

Debug/Test signatures verified; all individual raw/LF hashes,compiled-class/test
classpath identities,XML hashes and source-to-artifact mapping are external.
APK bit equality is not required by this build contract; differences are not
hidden behind the unchanged source-cohort digest.

### Preservation And Remaining Gates

1682 sealed r1620-r1625 receipt files were freshly rehashed unchanged. Protected
worktree HEAD/branch/index/dirty sources and Phase7 R2 are compared before/after;
four inherited large research artifacts retain disclosed size/mtime-only checks.
The r1615 Windows failure remains **P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED**;
the approved successor test contract does not root-cause its runtime mechanism.
Strict historical lint differences,Release-unit compile limitation,old TLS/
packaging/USB/SDK observations and G1-G5/media debts remain open as recorded.

Retain P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED,
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED,P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED
and P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED. No new Android
runtime/OEM/adbd,production identity,durable native transaction,automatic drain,
endpoint/Network/NSD,blocked TLS-write or suspend-connect qualification. No
Release backend,Host/Session authority,P3-P5 or Phase7 activation.

Next recommendation,not execution: separately authorize a narrow Android
composition-boundary review/fixture plan around the existing
AndroidDirectShellCredentialStorageAdapter.forApplication,read-only Keystore
adapter,native publisher and owner-side D handoff. Identify minimum synthetic
identity/provisioning and automatic-drain ports before proposing implementation;
do not silently provision the production alias or connect to adbd.

## Historical r1625 Implementation Record

2026-10-29. **P2_SYNTHETIC_COMPOSITION_IMPLEMENTED_SOFTWARE_TESTED**.
Overall **P2_PARTIAL_BLOCKED**. This is localhost qualification,not Android
enrollment,production trust,Host activation or Release onboarding.

## Baseline And Historical Boundary

Parent/HEAD: `688f13bd028265c2c75109c2e6949be85daf2bd5`, containing source
checkpoint `5c951ecda3693981918ad8c128c02de76d679f9f` and the direct P1
documentation child. Initially clean branch
`codex/phase6-5-pairing-p2-synthetic-composition-integration` at
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-p2-synthetic-composition-integration/Warpnect`.
No dirty branch was merged/copied.28 total worktrees;27 others protected.
Receipts: `C:/wg-pairing-composition-r1625/`.

The earlier correctly design-blocked r1621 report remains unchanged in
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-p2-synthetic-composition/Warpnect/docs/phase6_5/DirectShellPairingSyntheticComposition.md`,
raw SHA256 `4DF6FAD5F99036529FDD7E213C9EFD3240950E3AF90D288B0F260772490009A1`.
That report is historical,not this implementation's source or acceptance.
The approved D1-D7 review is preserved alongside it. The later P1 checkpoint
and current explicit authorization permit the observed path; accepted RFC-006I/J
are unchanged. Retain all prior checkpoint statuses and
**P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED**. Phase6.5 is incomplete.

Read actual contract/coordinator/work context,storage/codec/AEAD/filesystem,
Android adapter,native publisher,transport,protocol,patched context and peer
fixtures; checkpoint,observation,storage,multiprocess,reservation,Release,
roadmap/handoff and accepted RFC documents. Previous1553/195 JVM,369 focused,
40 replay and45 Android compile-only methods are historical input evidence,
not this increment's result. No PROJECT_STATUS/VALIDATION_STATUS exists in
this parent; dated reports,handoff,roadmap and Phase6.5 README carry status.

## Exact Implementation Delta

Pairing package is `io/warpnect/platform/privileged/directshell/pairing/`.

| Full source/test path | Delta |
| --- | --- |
| app/src/debug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellSyntheticPairingComposition.kt | New inert,unregistered localhost composition; one owned worker and explicit owner-side D observation |
| app/src/debug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingTransport.kt | Additive awaitWork consumes the original remaining deadline; every old method unchanged |
| app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellCredentialStorage.kt | Additive read-only recoveryAdmission(ticket); all original methods unchanged |
| app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/OwnedPairingPeerFixture.kt | Add optional server/client identity parameters; original defaults/assertions/protocol unchanged |
| app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingSyntheticCompositionTest.kt |31 new methods covering26 cases plus five concrete binding/recovery/publication discriminators |

No P1 source/test,record format,crypto implementation,native publisher,libadb
patch,dependency/SDK,manifest,planner/provider,Session,SCL or media/input change.
The query and wait are narrowly necessary additive internal integration APIs,
not new authority or persistence semantics. Removing their exact bounded blocks
reproduces the parent storage/transport blobs. Reversing only the fixture identity
parameters reproduces the original fixture.46 fresh source assertions verify
this and exact inherited P1/crypto/test identities. Source remains uncommitted.

## Execution And Ownership Contract

```text
explicit P1 request/code submission
 -> exact ticket + original owner WorkContext
 -> one operation worker,not a second coordinator
 -> real storage.begin: revision0,stable OS lease,encrypted recovery
 -> required file force + modeled directory sync + authenticated reread
 -> read-only recoveryAdmission: exact ticket,live lease,key,unchanged anchor
 -> P1 pairingStarted(RETAINED_UNCONFIRMED),current eligibility
 -> one owned TCP socket,pre-TLS recoveryAdmission revalidation
 -> authentic pinned TLS1.3/exporter/SPAKE2/encrypted peer-info
 -> bind exact work/ticket/socket/endpoint/local cert and real remote cert digest
 -> real storage.prepare; only now create encrypted identity.pending
 -> ObservedDirectShellPairingObserver.prepared(staged,verified/POSSIBLE)
 -> bounded committer command on this same operation worker
 -> existing one-shot R -> real host no-replace P -> independent S/cleanup receipt
 -> worker closes storage/code and releases its identity borrow
 -> external owner observes actual worker termination and all resource domains D
 -> P1 F or truthful uncertainty/reconciliation,no Host/Session authority
```

P1 create/start/commit/cancel/close remain CPU-only. Commit saves one bounded
command,not a detached task or retry queue. Filesystem,crypto,socket/TLS/PAKE,
native boundaries and joins run outside P1's monitor. Fixture assertions check
actual Thread.holdsLock(owner)==false at blocking ports and stages.

| Boundary | Authoritative owner and observed fact |
| --- | --- |
| Consent/ticket/work | Real P1; exact ticket reference,original120s consent/60s work/3s cleanup observation |
| Recovery | Existing storage begin plus read-only revalidation; no Boolean substitutes for actual lease/anchor admission |
| Transport/protocol | Existing transport owns one Socket and I/O/close workers; pinned context borrows the same connected socket |
| Protocol evidence | Actual same-instance immutable verifiedPeer; no manufactured success callback,GUID/mDNS trust or daemon persistence claim |
| Candidate | Existing storage prepare after exact binding; staged handle remains opaque,not credential authority |
| R | Existing P1 control.reserve on the exact candidate.prepared; only one claim,no renewed consent |
| P/S | Existing storage.publish on same worker; real no-replace hard link,typed sync/cleanup knowledge,no overwrite/copy fallback |
| D | Explicit owner-side awaitDrain joins the real composition worker,checks actual transport receipt and storage/borrow/code closure; no callback self-attests thread termination |
| F | Unchanged P1 predicate; accepted matching PEER_INFO_VERIFIED/POSSIBLE plus valid P/S/current/recovery/cleanup/D |

The synthetic adapter is deliberately owner-observed,not an automatic Android
dispatcher. Without that explicit outside-monitor observation,no D callback is
delivered and P1 stays fenced. The external test caller is not another
operation-owned async worker or enrollment owner. No poll/retry cleanup queue
exists. Unknown resources cannot be released by a timeout or callback alone.

The pre-TLS query was required because a past RECOVERY_READY alone would not
explicitly check a subsequently lost lease/changed anchor. It only reads existing
key/lease/authenticated-anchor state through existing exclusive serialization.
Failure forbids protocol entry; it never creates,repairs,deletes or adopts files.
The owned I/O worker performs the transport's revalidation while the composition
worker waits; prepare/publication start only after that I/O worker has terminated.

## Evidence And Resource Scope

The real protocol result is bound outside P1's monitor to the originating work
object and exact ticket,actual socket remote endpoint,admitted endpoint and
the same synthetic local certificate used in recovery. Local SHA256 comparison
and remote digest length are checked; the remote digest comes from the actual
verified TLS/PAKE evidence,not fixture bytes,GUID or discovery metadata. Negative
tests use that real immutable result with foreign work/ticket/socket/endpoint/
identity and require rejection. A second controlled peer certificate differs
from the local recovery certificate; the authenticated encrypted record contains
its actual verified digest. No production key or DEBUG identity is imported.

Host filesystem operations,AES256-GCM/authenticated decoding,file force,OS locks,
atomic hard links and original storage/recovery state machine are real. Positive
directory-sync acknowledgement is explicitly modeled through the existing
test-only ModeledDirectorySync port. Negative sync acknowledgements fail closed.
No Windows directory fsync,Android native publication/Keystore,crash/power-loss
durability or real adbd interoperability is inferred from these tests.

Local identity is the existing synthetic fixture's per-JVM RSA identity; the
distinct server identity uses a fixed test seed. Interleavings/expectations are
deterministic; encryption IVs and the existing local fixture key generation are
not promised bit-identical across JVMs. A borrow is released by dropping the
operation's references after crypto workers end,not destroying the fixture's
long-lived key or proving JVM heap erasure. No hardware key handle exists here.

D checks actual Thread termination,Socket closure,protocol release and code-copy
release from the existing transport; the operation worker must itself be dead.
Real captured FileLock invalidity,FileChannel closure and exact-path registry
absence are asserted after normal cleanup. C22 deliberately retains a real live
lease when the filesystem port reports incomplete drain; closing the network
cannot release P1. Its eventual test-owned cleanup is not fabricated D or
automatic reconciliation. Pairing-code copies use the actual bounded wiping
wrapper and transport/library cleanup,never public state or exception payloads.

The peer's listener/socket/worker belong to the fixture,not P1 D. It separately
closes and joins them. The C24 child and reader are explicitly bounded/observed;
final OS handle reclamation also has the separately observed test-worker process
termination witness,not an inferred P1 resource receipt. No per-pipe handle-count
or physical power-cut qualification is claimed. JUnit owns/deletes temporary
namespaces; no broad filesystem cleanup or real credential access occurs.

## Deadline,Cancellation And Knowledge

Storage and all protocol stages consume the same original work deadline.
awaitWork only joins within its remaining interval; it does not start another60s
budget. Existing transport cleanup observation and P1 cleanup timer remain
separate,not renewed authority. No filesystem/crypto/join happens under P1.

Before R,stop/expiry prevents later authorization. After R/P,actual publication
knowledge is retained without rollback promises. Actual late verified protocol
information may refine the same attempt's recovery but never restore consent
or R. Effect stays POSSIBLE after protocol entry; no new AUTHENTICATED is emitted.
Old same-owner callbacks cannot alter a newer exact-ticket attempt. Identical
drain delivery is once-only; old callbacks cannot clear a newer backend handle.

C25's PAIRED_NEEDS_EXPLICIT_HOST_RETRY is only the existing synthetic local
workflow result. It confirms neither persistent Android trust nor production
identity adoption,Host qualification,Session authentication or automatic retry.
Known P with unconfirmed S and unknown P with physical D both block eligible F.

## C01-C26 Final JVM Matrix

All rows below are PASS in the final frozen31-method suite; their scope is the
assertions and exact XML archived under full/ and focused-final/,not Android.

| Case | Actual exercised observation |
| --- | --- |
| C01 | Construction/readiness/request cause no storage/socket/worker/authority action |
| C02 | Real P1 explicit dispatch; missing observed extension,foreign ticket and wrong work clock rejected,no fallback |
| C03 | Real encrypted anchor decode and synchronization call ordering before protocol; two extra methods lose the actual lease or change actual anchor before entry and block all sockets |
| C04 | Required sync failure prevents protocol and record publication |
| C05 | Unacknowledged recovery persistence retains actual encrypted anchor,reconciliation and readmission fence |
| C06 | Actual same-JVM namespace lease prevents another composition from entering protocol |
| C07 | Nonzero revision rejected; existing committed ciphertext preserved without replacement |
| C08 | Real owned socket and pinned TLS/PAKE; actual distinct local/remote certificates and stored remote digest |
| C09 | Wrong code fails authentic exchange; no pending/record or verified metadata |
| C10 | Actual malformed peer-info rejected; changed recovery blocks candidate; extra binding method rejects foreign inputs using real same-instance peer result |
| C11 | Cancel before admission creates no files/socket/publication |
| C12 | Cancel during real anchor write preserves recovery and blocks protocol |
| C13 | Real stalled TLS read interrupted by actual socket close; actual worker/resource drain |
| C14 | Real stalled PAKE read interrupted; same-socket ownership and drain retained |
| C15 | Cancel after client peer-info reaches peer retains POSSIBLE,no rollback assumption |
| C16 | Storage consumes50000ms of original controlled deadline; protocol uses the remaining original budget |
| C17 | Original deadline expires before protocol; no socket/candidate/publication |
| C18 | Deadline expiry during real PAKE stops I/O,retains effect/recovery and observes actual drain |
| C19 | Late real verified result after cancel refines knowledge,no R/publication |
| C20 | Same coordinator/new exact ticket,old cancel/duplicate D cannot affect new operation or publication |
| C21 | Actual held composition worker prevents D/F/readmission,even after P |
| C22 | Actual live storage lease blocks D despite closed socket and dead worker; cleanup deadline does not free it. Two extra methods distinguish known P/unconfirmed S and unknown P with real published bytes/physical D |
| C23 | Controlled object restart reads retained authenticated pending state without consent/adoption/mutation; not process-kill/power-loss evidence |
| C24 | Real independent live JVM holds writer.lock; new composition cannot enter protocol; child/reader termination observed |
| C25 | Real P1,storage,owned socket,pinned protocol,binding,observed prepared,R/P/S,actual joint D and permitted local F; one socket/publication/borrow release; timers0 |
| C26 | No Host/Session/provider/Root/Shizuku/NSD interfaces or production namespace used; state redaction and fixture scope asserted |

Final discovery: **1584/1584 JVM in196 suites**,one declared unfiltered,
noninstrumented full execution in32s; **400/400 focused in16 suites**,0 failures,
0 errors,0 skipped in both. Delta from1553/195 is31 methods/one suite; focused
369+31=400. All original tests remain,including96 coordinator,5 deadline,41
observation,33 transport,30 protocol and Windows contract methods. No diagnostic
agent,filter or disabled pairing case in full. maxWorkers2,maxParallelForks1,
forkEvery0,Gradle8.12/Kotlin2.0.21/JDK17 worker/offline configuration unchanged.

## Development And Historical Negatives

Development01 passed26 methods. Development02 passed29 after added binding/P/S
discriminators and explicit lock/channel/registry assertions. The first focused
four-source cohort passed398,then was superseded before full acceptance by the
five-source recovery-revalidation cohort400. All snapshots/XML remain distinct.
No failed full acceptance was retried. No source edit followed final full.

Pre-freeze source review corrected the NEW adapter's worker-reference dispatch
window and moved resource-free handle release before D delivery. The controlled
clock now actually releases cancelled closures; C25 checks timers0. These are
new-adapter/fixture review corrections,not historical P1/Windows defect fixes.
One nullable ProtectionDomain warning in development01 was corrected explicitly.
Formatter01 and04 negatives are retained; only remaining long lines were wrapped,
no rule disabled. Formatter05 passed on the final input.

External harness preflight negatives are not application failures: an initial
new regex counted currentThread() as a Thread constructor; the original tool
and negative are retained and the classifier corrected. A parser-generator
CommonJS local-name syntax error occurred before APK inspection and was fixed
without any source/test change. Full JVM acceptance was not rerun or relabeled.

Retain r1615's1506 PASS/one FAIL and P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED; the
approved successor contract does not root-cause Windows lock timing. Fresh
forced-death/release cases returned COMMITTED_CLEANUP_PENDING and live-owner
inspection BUSY. Earlier cancellation/packaging/bootstrap,USB enumeration,
SDK metadata,strict lint differences,r1623 wrapper/formatter negatives and the
independent180-diagnostic/11-file Release-unit compilation limitation remain.
assembleRelease is not testReleaseUnitTest; that extra test gate is not run/fixed.

## Final Software,Artifact And Preservation Receipts

All mandatory software gates PASS on the final five-source freeze. Native build
inputs are unchanged; only compiled ABI/symbol checks were executed,no CTest.

| Fresh final-cohort gate | Result |
| --- | --- |
| Full/focused JVM |1584/196 and400/16 PASS;31 new methods,zero failures/errors/skips; one unfiltered noninstrumented full execution |
| ktlintCheck/lintDebug/lintRelease |PASS;126 Debug/34 Release warnings,zero errors/no added semantic finding |
| Debug/AndroidTest/helper/unsigned Release |PASS;26m32s,166 tasks144 executed/22 up-to-date; not a performance benchmark |
| verifyOwnedPairingReproduction |PASS; two independent builds and selected AAR match the pinned hash |
| Library archive/class/DEX closure |619 PASS;43 unrelated classes byte-identical,7 changed/2 additive pairing entries inherited,no new library patch |
| Classpath/dependency/legacy APIs |132 PASS; actual selected AAR class bodies,libadb3.4.0/BC1.84,Release exclusion |
| P1 source/core and compatibility |46 source/inheritance assertions plus34 existing model guards PASS; P1 and all original storage/transport methods unchanged |
| P1 observed metadata/DEX |18 compiled guards PASS |
| Composition bytecode/DEX |8+19 PASS; actual thread joins/query/R-P path compiled,one Debug definition,no Release adapter or host-test definitions |
| Source-set/manifest |21 PASS; no runtime registration or SDK/provider change |
| Storage/Android adapter compiled |10+14 PASS;45 Android methods compiled,zero executed |
| Publisher/native |36 source,3 publisher ELF ABI/import and6 media DEBUG/Release symbol guards PASS |
| Offline media |40/40 PASS,zero failed/skipped; no live media/hardware quality claim |
| Final gate admission |265 checks PASS on source/XML/artifact identities and required fresh results |

The old strict warning-set comparison remains FAIL_RETAINED. The scoped semantic
comparison permits only the proven unchanged libadb archive-path normalization
and eight historically removed GradleDependency advisories per variant; none
were added/suppressed by this increment. Compiler/native/SDK warnings remain
in software/gradle.log. Release-unit compilation is not newly tested or fixed.

Final LF-canonical source cohort:
`2E1EFC4800A1E162061CC120C4D3DF685271A2FFAF02075EC54AD2683069ED06`.
Algorithm: codepoint-sort the five relative paths,then SHA256 of UTF8 rows
`path<TAB>uppercase-SHA256-of-LF-content<LF>`. Raw/LF identities are separate;
this does not reuse the P1 four-file or earlier19-file cohort digest.

| File | Raw SHA256 | LF SHA256 |
| --- | --- | --- |
| DirectShellPairingTransport.kt |E898CE31E0D356797EC857088166CFB912C9D7AEDF6C750E74059982184C215F|99DDEA4CB28306CBB16952773099CB9F9A62071EA9EB8610929696D48054A2F5|
| DirectShellSyntheticPairingComposition.kt |3578AB24CD88D9A52EAF78333A94EE9383F490C4CEE5A42298944908AD959AC0|same|
| DirectShellCredentialStorage.kt |6ADC465E18DECA2CF5FF0B1BDA132A541F5F69A8E35A5448A015EB146890EEDD|D046132D5DFBF7A3A89F3ED69BC41B4514228934DCF1333DA1EBFAA004DACC35|
| DirectShellPairingSyntheticCompositionTest.kt |BDD2FD5C069242AAE783690F5BB48820D1379F4E8A8D6CF5AA151030A8865CAA|same|
| OwnedPairingPeerFixture.kt |2387D925335D7F14C30693FE2DBD383E958E624F81F0A40BE871C1F9944E84A5|380CCE4F0D339417BC054F6841A8766463BCA1FD1E30D102CC43FA155F2ABAEE|

| Fresh output | SHA256 |
| --- | --- |
| Debug APK |E578A071FE7149D1F8623177060076EBC51DB74F26CB8B75FEE1F8B09BC261D5|
| AndroidTest APK |F0F744CB26AF83C630C8A9DA52E3BCFF063594FF1C9B8D973E32C9E62A0ED943|
| Helper JAR |C746C406BA2A18CF56296D5EEDED8E830AD452744B194EB9333B8BF733897B8A|
| Unsigned Release APK |BD02FF121EB1466277CB9905C23606DA6905C8B5322EA6543447CFF994605F06|
| Selected/reproduced patched AAR |BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0|

Parent688f13bd plus five source identities -> recorded commands -> frozen
artifacts/signature receipts.113 Debug/96 Release package class hashes and
compiled test discovery are recorded externally. AndroidTest is byte-identical
to r1624. Debug/helper change classes13.dex; Release changes classes2.dex and
baseline.prof for the additive inert query. Manifest/resources remain unchanged.
All19 helper DEX entries match the actual fresh Debug APK.

Rebuilt native entries also differ: libscl_core.so .text/.rodata and link-layout
sections differ for all three ABIs/both variants,with old/new checkout paths
actually present. No instruction identity or exclusive cause is inferred from
unchanged source. Three Debug microphone-clock entries differ only in build-id,
with actual identical .text/.rodata sections. Per-entry/per-section hashes are
in artifact-comparison.json/native-elf-comparison.json; no artifact was edited
to match an older APK hash. The original AAR remains
62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB,
upstream dcd931071b5e4b9ebc0ac598201755d5a8814b71; license/provenance unchanged.

Final state: exactly five source/test and three documentation paths uncommitted,
index empty,HEAD still688f13bd; zero commits/push/merge. Full manifests and final
read-only integrity receipt are in r1625/final-integrity.json and
CompositionFinalReport.md.27 protected worktrees retain branch/HEAD/index/dirty
content identities; four inherited large research artifacts use the disclosed
size/mtime-only check. Master/upstream and Phase7 R2 are unchanged.1270 sealed
recent historical files were rehashed; earlier cited evidence was not rewritten.
All16 observed worker/child PIDs terminated,with no forced cleanup or survivor.
The fixture-only namespaces are owned by JUnit; no production data/key/trust
access or protected-worktree cleanup occurred.

## Remaining Platform Gates

No Android instrumentation,emulator,device,ADB server,USB enumeration,NSD or real
daemon pairing is authorized/executed. AndroidTest compilation is not execution.
Native inputs unchanged; no new CTest claim. Global minSdk26 and API30+ integrated
Wireless Debugging policy,Release adapter unavailable,remain unchanged.

After software acceptance the smallest next increment is independent review and
checkpoint consolidation of this uncommitted five-source candidate,not automatic
Android enrollment. Real adbd,Android blocked TLS-write/suspend-connect behavior,
production AndroidKeyStore lifecycle,real storage/native publication composition,
endpoint/Network/NSD binding,OEM/API/device qualification and Release onboarding
remain open. G1-G5 hardware remains PARTIAL,media debt OPEN,Phase7 R2 PROPOSED and
unchanged. No P3-P5,Root/Shizuku migration or Phase6.5 closure.
