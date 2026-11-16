# RFC-006J P1 Explicit Protocol Observation And Drain

## 2026-10-29 Local Checkpoint Review (r1624)

**P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED**, scoped to the local software
checkpoint. Overall **P2_PARTIAL_BLOCKED**; **P2_SYNTHETIC_COMPOSITION_NOT_STARTED**.
The implementation receipt below is historical r1623 evidence, including its
then-uncommitted state. This section supersedes only that checkpoint state,
not historical negatives or qualification limits.

The user explicitly authorized final D1-D7 review, fresh mandatory gates and
exactly two conditional local commits in attachment51722829-1fcd-4e23-a14c-918f416c1090.
Its raw SHA256 is8DA002072AD13CC6FED798064CADD8D5A4B3D1976BE5AA157E343C403B5811CE.
The two approved input hashes in the historical Approval And Baseline table
were reverified without changing either preserved review document or accepted RFC.
Full request/input provenance is in r1624/approval-provenance.json.

Parent: `bd8a4edc5928715317eaed46cdd96b48da5fc9f5`.
Branch: `codex/phase6-5-pairing-protocol-observation-checkpoint`.
Worktree:
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-protocol-observation-checkpoint/Warpnect`.
The initially clean worktree received exactly four source/test and three
documentation files, byte-verified against the untouched r1623 candidate.
No source/test correction was made during this consolidation.

Source/test checkpoint: `5c951ecda3693981918ad8c128c02de76d679f9f`.
It contains exactly the four full paths listed in r1624/source-checkpoint.json:

1. `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingContract.kt`
2. `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinator.kt`
3. `app/src/test/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingProtocolObservationTest.kt`
4. `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCompositionContractTest.kt`

The direct documentation child contains only this report,CURRENT_HANDOFF.md and
docs/FutureRoadmap.md. Its full SHA, exact committed paths, independently hashed
LF blobs, direct ancestry and final clean-index/worktree verification belong in
`C:/wg-pairing-observation-checkpoint-r1624/checkpoint-committed.json` and
CheckpointFinalReport.md,not a self-referential commit hash in this document.
The checkpoint classification requires those successful post-commit receipts.

### D1-D7 Independent Source Review

C=DirectShellPairingContract.kt; P=DirectShellPairingCoordinator.kt;
T=DirectShellPairingProtocolObservationTest.kt, at the frozen source identities
in the historical Source Identity table. All anchors are source line numbers.

| Decision | Source anchor | Required invariant | Actual implementation | Matching tests | Evidence | Result |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | C86-96,108-120; P343-372,657-664 | Verification is not daemon trust | Separate enums; new AUTHENTICATED rejected; POSSIBLE retained; legacy projection unchanged | T21,40,46,51,139,170,385 | Frozen source,37 structural checks,41 fresh model tests | PASS |
| D2 | C90-96; P344,375 | Immutable exact-ticket metadata,not authority | Object identity,enum-only payload,redacted rendering; future worker must bind socket/endpoint/local identity | T32,181,352,400 | Unchanged R; no production backend registration | PASS |
| D3 | C181; P266-314 | Optional explicit entry,current admission,one commit,normal R | No legacy fallback; foreign/unconfirmed/resolved handles and mixed paths fail closed; recovery/current rechecked | T21,32,40,57,63,81,98,108,120,131,245,375 | Actual observer and unchanged commitControl | PASS |
| D4 | P545-568 | Resource D independent from semantic eligibility | False-resource/foreign-ticket receipts cannot release ownership; all-true bound D may still require reconciliation; known P preserved | T150,156,164,181,189,207,223,229,286 | Trusted-port/model assertions,not OS observation | PASS |
| D5 | P591-600; completed() | F needs matching verified/POSSIBLE plus existing P/S/cleanup/current/recovery/D | Matched accepted observation and prior predicates required; reconciliation blocks eligible success | T139,150-229,261,297,315,324,331 | Real P1 with modeled receipts; local workflow only | PASS |
| D6 | P270,343-372,519,560; request() | Monotonic knowledge,duplicates inert,late results isolated | Conflicts retain first P/D and fence the same attempt; retained and newer active attempt cannot coexist | T81,88,120,235,252,268,278,331,341,352,385 | Old-generation and post-F contradiction traces | PASS |
| D7 | P228-264,532-539; original timers/R | Legacy behavior,budgets and isolation | AUTHENTICATED guard retained; core request/submit/cancel/environment/close/R/receipt/timer regions match parent |96 coordinator,5 deadline,2 discriminators | Unchanged legacy tests; fresh source/compiled guards | PASS |

No material correctness/security defect or security-critical inconclusive model
contract was found. O01-O20 were re-reviewed against the unchanged41 methods and
the two preserved discriminators; the detailed case mapping below still applies.
O04 proves optional-interface admission/no fallback,not a production adapter.
Positive P/S and D flags in these tests remain modeled trusted-port facts.
P1 cannot manufacture OS resource release,cryptographic validation or persisted
Android trust from an enum. A false-D receipt does not become a successful new
protocol attestation; the future producer must retain its actual first protocol
evidence while resources remain unresolved and prove that behavior in C21/C22.
Source compatibility does not promise binary compatibility to old test APKs.

### Fresh Mandatory Gates

Receipts: `C:/wg-pairing-observation-checkpoint-r1624/`. PowerShell7,Gradle8.12,
Kotlin2.0.21,JDK17 test worker,compile/target35,minSdk26,NDK27.0.12077973;
offline,maxWorkers2,maxParallelForks1,forkEvery0. No diagnostic Java agent.

| Gate | Fresh result |
| --- | --- |
| Focused JVM |369/369,15 suites,0 failures/errors/skips; focused/ XML and execution metadata |
| Full Debug JVM |1553/1553,195 suites,0 failures/errors/skips; one declared unfiltered execution,35s,no retry |
| Reproduction |Two independent builds and selected AAR match BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0 |
| ktlintCheck,lintDebug,lintRelease |PASS;126/34 warnings,0 errors; no added semantic finding |
| Debug,AndroidTest,helper,unsigned Release |PASS;29m3s,166 tasks144 executed/22 up-to-date; cold build,not runtime performance evidence |
| Patched archive/class/DEX closure |619 PASS;43 unrelated classes unchanged,7 changed/2 additive pairing entries inherited; no new patch or duplicate definitions |
| Dependencies/legacy APIs/classpath |132 PASS; exact selected artifact,libadb3.4.0/BC1.84 unchanged,Release exclusion |
| Source-set/manifest |21 PASS; no new registration or SDK/provider change |
| P1 source/model |37 approved-delta plus34 inherited/scoped checks PASS |
| Observation compiled/DEX |18 PASS; both variants,legacy/observed overloads and test exclusion |
| Storage/Android adapter compiled |10+14 PASS;45 instrumentation methods compiled,0 executed |
| Publisher |36 source plus3 ELF ABI/import guards PASS |
| Native media isolation |6 compiled symbol guards PASS; native inputs unchanged,CTest NOT EXECUTED |
| Offline media |40/40 PASS,0 failed/skipped |
| Pre-commit admission |268 integrity assertions PASS,including exact candidate,source/XML/artifact hashes and preservation |

Focused suites: fixture protocol4,Android filesystem24,Keystore10,consumed
publication11,AEAD9,credential storage38,native publisher12,owned protocol30,
composition discriminators2,coordinator96,deadline5,protocol observation41,
storage integration48,transport33,process/lease6. No inherited method changed.
The increment over1510/193 is43 methods/two suites; over1512/194 it is41/one.

Both fresh forced-death A and explicit-release B returned
COMMITTED_CLEANUP_PENDING; the independent live published owner returned BUSY.
All approved assertions ran. This does not attribute or fix the r1615 refusal.
The historical initial r1623 crash BUSY,PowerShell5 wrapper negative and three
formatter corrections remain separately preserved,not reused as fresh gates.
Strict historical lint equality remains FAIL_RETAINED: exactly eight removed
GradleDependency advisories per variant predate this P1 delta. Only the verified
unchanged libadb archive path is normalized; no blanket suppression. The
180-diagnostic/11-file extra Release-unit compile limitation remains untested
and unresolved here; assembleRelease is not testReleaseUnitTest.

### Source And Artifact Identity

The four-file LF cohort is unchanged:
`DBEFC3EFA5AC2CD21D7E7E5C0789AB44804ADACD4881EAB1980E786DAB92120F`.
Algorithm: localeCompare-sort paths,then SHA256 of UTF8 rows
`path<TAB>uppercase-SHA256-of-LF-content<LF>`. Individual raw/LF hashes match the
historical table; committed blobs independently match the LF hashes. This is
not the older19-path manifest. Actual Debug107/Release96 package class hashes,
source snapshots and XML identities are recorded externally; no source edit
followed full acceptance. Only documentation was finalized afterward.

| Fresh output | SHA256 |
| --- | --- |
| Debug APK |14505040E467994776694651C4F2E4F30C9D1D60FED90EB333056FBC7BD49FFB|
| AndroidTest APK |F0F744CB26AF83C630C8A9DA52E3BCFF063594FF1C9B8D973E32C9E62A0ED943|
| Helper JAR |FC0EB052B307CE1D864C1AF6CA0192B50E7C597155EF266F087BDAB650B5AE8A|
| Unsigned Release APK |34BC424812EEDED972D714227FC3465250758A3E4F39E0AC1DBF5D9751A05A9B|
| Patched AAR |BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0|

AndroidTest and helper are byte-identical to r1623. All19 helper DEX entries
match the fresh Debug APK. Debug differs only in six native entries,Release in
three; DEX,resources and manifests are unchanged. Actual ELF section comparison
finds different .text/.rodata in libscl_core.so for all three ABIs/both variants,
alongside embedded old/new checkout paths and linker-layout differences.
Instruction identity or a sole-cause explanation is NOT claimed. The three
Debug microphone-clock libraries differ only in build-id,with actual matching
.text/.rodata hashes. Per-entry/per-section hashes and unchanged source/build
inputs,not assumed APK determinism,are the provenance evidence. Original AAR,
licenses,dependencies and native ABI remain as recorded below.

### Preservation And Next Boundary

27 total worktrees;26 protected others,including the original seven-path dirty
candidate. Branch,HEAD,index and dirty-file hashes remain unchanged; four
inherited large research artifacts retain the disclosed size/mtime-only check.
Master/upstream and Phase7 R2 hashes remain unchanged.927 sealed receipt files
from r1620-r1623 were rehashed; older cited negatives remain in preserved reports.
All eight observed test/child PIDs exited; no owned survivor or forced cleanup.
Final post-commit checks,evidence manifest and both full SHAs are external.
No APK,log,key,receipt,generated binary or forensic script is committed.

Retain P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED,
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED,
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED and
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED. No push/merge,device/emulator,ADB/USB/NSD,
real pairing,Host/Release activation or Root/Shizuku/media/Phase7 work occurred.
No new Android runtime,physical hardware,power-loss or end-to-end D qualification.

Next recommendation only: separately authorize C01-C26 localhost composition
from the documentation checkpoint,using real P1,exact ticket/socket/endpoint/
local-identity binding,existing encrypted host recovery storage with explicitly
modeled directory sync,the authentic owned protocol,normal R,truthful P/S and
observed joint transport/store/code drain. Stop on any new contract gap. No
composition is implemented here. Real adbd,Android blocked-write/suspend-connect,
production key lifecycle,actual storage/transport transaction,NSD identity,
OEM/API/device qualification and Release onboarding remain open. G1-G5 hardware
remains PARTIAL,media debt OPEN,Phase7 R2 PROPOSED,Phase6.5 incomplete.

## Historical Implementation Receipt (r1623)

2026-10-29. **P1_ATTESTATION_DRAIN_IMPLEMENTED_SOFTWARE_TESTED**.
Overall **P2_PARTIAL_BLOCKED**.
No synthetic end-to-end composition, Android runtime qualification, real pairing
or Release onboarding is supplied by this increment. Final acceptance below
must be read with actual receipts, not projected test totals.

## Approval And Baseline

The user's explicit "Ok, procedi come nel prompt che hai proposto" approves the
named attestation/drain implementation prompt and its D1-D7 policy delta after
the preceding review was presented for approval. This is not approval of P2
composition, production trust adoption, new transport or an accepted-RFC rewrite.

Frozen parent: `bd8a4edc5928715317eaed46cdd96b48da5fc9f5`; source checkpoint
ancestor: `9576fdca42b9c0b709ddd2af51464f3464324fd9`.
New branch: `codex/phase6-5-pairing-protocol-observation`.
New worktree:
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-protocol-observation/Warpnect`.
It was initially clean. No dirty branch merged/copied.25 other worktrees are
protected, including the preserved review/composition worktree.

Approved input documents remain unchanged in that preserved worktree:

| Input | Raw SHA256 |
| --- | --- |
| DirectShellPairingAttestationDrainContractReview.md | 0E50DF493F952CD8A77B3CA231BC10AD8FF38B7E9EF998D2EF5F6D67DB8567EB |
| DirectShellPairingAttestationDrainImplementationPrompt.md | 897F5169DBB58CCE3B6092589950B46F95A5C6B6E8CF249F9C3822567C71C8B4 |

The review remains a historical proposed document at its original path. This
report records subsequent human approval of its bounded D1-D7 decisions; it does
not silently change accepted RFC-006J or confer production-readiness status.

Read actual P1 source, storage/recovery, owned transport/protocol, patched context,
accepted RFC-006I/RFC-006J, reservation/Release architecture and checkpoint reports.
Parent evidence1510/193 JVM and326 focused is historical. The earlier diagnostic
continuation1512/194 and328 focused includes two added legacy-discriminator
methods; those are selectively preserved here, not counted twice.

## Exact Scope

Pairing package: `io/warpnect/platform/privileged/directshell/pairing/`.

1. main/DirectShellPairingContract.kt: additive safe protocol observation and
   optional observer, defaulted state/drain metadata.
2. main/DirectShellPairingCoordinator.kt: explicit-path validation/knowledge,
   semantic reconciliation versus physical D, and retained-attempt handling.
3. test/DirectShellPairingProtocolObservationTest.kt: new deterministic pure-P1
   tests with controlled ports. They model publication/drain, not TLS/filesystem.
4. testDebug/DirectShellPairingCompositionContractTest.kt: exact prior two-method
   diagnostic copy, SHA256 raw/LF
   `6B407AC72E08F6CC8CB8AB6742F31AB67F4CE77306BD1A7E48DDCE1BB2EBF3E7`.

All inherited tests stay unchanged. No storage/codec/schema/native/Keystore,
transport/libadb/BC, dependency/source-set/SDK, UI/provider/runtime/manifest,
Session/security/SCL/media change. No new worker, retry queue or lifecycle owner.
Changes remain unstaged/uncommitted; receipts are `C:/wg-pairing-observation-r1623/`.

## Approved Model And Implementation

- D1: protocol UNCONFIRMED/PEER_INFO_VERIFIED is independent of external effects.
  New explicit observations retain POSSIBLE; legacy AUTHENTICATED is not redefined
  or emitted as a new-path upgrade or a confirmed Android trust claim.
- D2: immutable observation binds the exact ticket object; P1 validates CPU-only
  metadata. Real cryptographic/identity/endpoint validation remains a trusted
  future worker obligation, not something an enum or digest grants by itself.
- D3: optional ObservedDirectShellPairingObserver explicitly accepts new prepared
  metadata. Existing legacy method remains. No implicit fallback, second consent
  token, R grant or new storage publication API.
- D4: valid current-bound receipt can attest actual resource D independently of
  semantic eligibility. Missing/regressed semantic knowledge fences F, not P.
  Wrong-ticket receipt or unproven resources cannot release current ownership.
- D5: matching verified/POSSIBLE prepared+drain and all existing P/S/cleanup/
  current/recovery/D conditions are required for local workflow completion.
  PAIRED_NEEDS_EXPLICIT_HOST_RETRY is not daemon-trust persistence or Host/Session
  authority. This increment's positive trace is explicitly modeled only.
- D6: verified/possible knowledge is monotonic; cancel/expiry cannot erase it or
  restore consent. Identical value duplicates are inert. Conflicts reconcile
  only the same attempt, including retained observed attempts after F.
- D7: legacy path and guard, original deadlines/single-flight, storage R/P/S,
  existing transport and every unrelated security/provider/media contract remain.

The existing legacy authenticated drain guard is retained. New prepared does
not set that flag; it records separate bounded metadata. A mixed legacy/observed
call is a semantic conflict, never a second committer dispatch. New receipt
duplicates use data equality with ticket reference identity. A bound observation
cannot be recreated from persisted consent or credential-file presence.

Physical D still requires all three operation-resource domains. A same-attempt
receipt with all D domains and incomplete semantic data retains known P and
verification/effect but blocks success with reconciliation. Foreign-ticket or
false-resource receipt remains CLEANUP_INCOMPLETE. Known resources are not
declared live solely because protocol evidence is missing. Conversely a timer
or a callback alone cannot establish physical closure.

R remains the original CPU-only reservation. P/S occur only in existing storage
outside this model; F requires its valid receipt and D. No filesystem, crypto,
socket, Android probing or join is introduced under the monitor. Consent120s,
original work60s and cleanup observation3s remain unchanged. Client and complete
Host planning remain outside the model; Release setup stays unavailable.

## Verification Receipts And Limits

Final-cohort gates are recorded below. Development receipts remain independent:
initial formatter rejected three overlong lines; no rule
was disabled. The first focused execution was already dispatched on that stable
source before the formatter's negative was reviewed; its identity is retained.
Any later execution must name its source delta and distinct receipt directory.

No new model test proves real cryptographic authentication, physical socket or
lease closure, Android fsync/Keystore, process crash or power-loss behavior.
Existing storage/protocol component regressions remain component-scoped. No
Android instrumentation, emulator, device, ADB server, USB/NSD or real daemon is
authorized. Compiling AndroidTest is not execution. Native inputs are unchanged;
only applicable compiled isolation guards, not new CTest/hardware claims.

Historical Windows failure remains P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED; r1615
negative and the approved successor test contract are preserved. Historical
Release-unit compile180 diagnostics/11 DEBUG-dependent tests and strict lint
differences are not fixed or replaced by assembleRelease. Earlier libadb
cancellation, bootstrap/packaging, USB enumeration and SDK-cache observations
remain separately scoped. No retry-until-PASS or suppressed assertion.

## Remaining Work

The next possible task is the later, separately scoped C01-C26 synthetic
composition once this amendment's actual verification is accepted. It is not
started here. Real adbd/OEM/API qualification, Android TLS write/connect/suspend
behavior, production AndroidKeyStore lifecycle, endpoint/NSD binding, actual
storage/transport composition and Release onboarding remain independent P2 gates.
G1-G5 hardware remains PARTIAL, historical media defects OPEN and Phase7 R2
PROPOSED. Phase6.5 is not complete. No commit,push,merge or device/trust mutation.

## Fresh JVM And Model Acceptance

The final frozen source passed **369/369 focused tests in15 suites** and one
declared **1553/1553 full Debug JVM execution in195 suites**, with0 failures,
0 errors and0 skipped methods in both. No filtering/exclusions or diagnostic
agent in the full test worker; maxWorkers2,maxParallelForks1,forkEvery0 and
JDK17 test worker remain. Full execution completed in34s,not retried.

Compared with the parent1510/193,there are43 additional methods/two suites:
41 new observed-path model tests plus the two exact prior legacy discriminators.
Against r1621's diagnostic cohort1512/194,the increment is41 methods/one suite.
Focused369 = inherited326 + copied2 + new41,not an addition of tests from an older
dirty P2 branch. The inherited96 coordinator and5 deadline methods remain intact.
Per-method XML,hashes and discovery/worker metadata are archived in full/ and
focused-final/ under r1623. They are model/component regressions,not C01-C26.

| Approved case | Fresh evidence and scope |
| --- | --- |
| O01 |96 legacy coordinator,5 deadline and2 unchanged discriminator methods PASS; legacy AUTHENTICATED guard still rejects conservative legacy drain |
| O02 | Explicit verified/POSSIBLE prepared records knowledge and dispatches once; no reservation/publication inferred |
| O03 | Foreign/cloned ticket,unconfirmed,NONE_POSSIBLE or AUTHENTICATED prepared observations rejected before commit |
| O04 | Actual P1 exposes optional observer; controlled legacy-only observer cannot satisfy the capability cast and receives0 legacy calls. This is interface admission modeling,not a production adapter |
| O05 | Same-value prepared/receipt copies with distinct observation allocations are inert |
| O06 | Mixed entry paths,conflicting observations and different staged handles fence without another commit |
| O07 | Positive P/S/D model produces only the local workflow phase with verified AND POSSIBLE; no real protocol/storage/Host authority is implied |
| O08 | Missing/regressed/contradictory semantic metadata preserves known publication and D while requiring reconciliation |
| O09 | Foreign ticket,each false resource flag and missing/late D retain occupied ownership; cleanup timer is not D |
| O10 | Unknown P differs from known P with unconfirmed S or incomplete redundant cleanup; knowledge stays separate |
| O11 | Cancel before prepared/R prevents reservation and retains staged material once |
| O12 | Cancel after R or P preserves late publication and forces reconciliation,not rollback |
| O13 | Partially consumed original deadline,expiry before prepared/R,late cleanup and timely-work/late-D cases pass with unchanged budgets |
| O14 | Network,permission,revision,backend,runtime and owner invalidation preserve fences before R/after P |
| O15 | Verified information during cancellation refines the old attempt only,never grants a commit |
| O16 | D before late store receipt remains unknown P until classified; existing contradictory-store regressions pass |
| O17 | Old callbacks cannot mutate a new clean generation; late semantic conflict fences only the retained observed attempt |
| O18 | State/observation/drain/work/ticket rendering excludes synthetic code and private owner/nonce; inherited exception-redaction tests pass |
| O19 | Passive construction has no factory/worker/commit call; no retry or authority created by inspection |
| O20 | Source/compiled/DEX/isolation results are recorded in the final gate section; no production composition is registered |

Two extra explicit safety cases within the41 methods cover already-resolved
staged authorization and inconsistent verified/NONE_POSSIBLE metadata. The former
cannot leave a live attempt waiting for an impossible commit; the latter cannot
later erase conservative possible exposure. These were addressed during source
review before the first focused execution,not claimed as observed test failures.

### Development Negatives Kept Separate

The first focused source also executed369/369 methods successfully,including
all41 new methods. Windows PowerShell5.1 nevertheless returned a wrapper error:
ErrorActionPreference=Stop converted an unchanged javac source8-obsolete stderr
warning into NativeCommandError. The daemon records BUILD SUCCESSFUL in7m17s;
the child exit code was not captured by that wrapper. Its XML/command/source/
daemon-completion and harness-negative receipts remain under focused/.

The corrected runner uses the established PowerShell7 environment and records
actual exit code0. The new focused-final identity follows exactly three declared
line-wrap-only source corrections after the formatter's negative; reverse hashes
verify that delta against the first source receipt,including CRLF/LF alternatives.
No warning was suppressed,assertion weakened or failing final cohort retried.

The first real abrupt-death Windows process case returned BUSY with all approved
safety assertions passing. Explicit-release B returned COMMITTED_CLEANUP_PENDING.
Focused-final and full A/B returned COMMITTED_CLEANUP_PENDING; the independent
live owner returned BUSY. None of these observations attributes the historical
refusal mechanism or claims immediate crash recovery. All12 observed test/child
PIDs exited; no owned survivor or forced cleanup was found. Gradle/Kotlin build
daemons are separate infrastructure,not pairing transport workers.

### Source Identity

Parent plus four frozen source paths identifies the tested input. Freeze cohort:
`DBEFC3EFA5AC2CD21D7E7E5C0789AB44804ADACD4881EAB1980E786DAB92120F`,
computed by the archived freeze-source.cjs over its ordered LF-content manifest.
It is not the parent19-path digest. Raw and LF hashes are recorded separately:

| File | Raw SHA256 | LF SHA256 |
| --- | --- | --- |
| DirectShellPairingContract.kt |7171C98D73F9F251FC374E6DA6459A395B454CEFDAA52196C86EAF68B295FA05|58617CC31B39382661CB904C43B0920A7F89C7B3E242B0CB3F5789D0EB5B09B7|
| DirectShellPairingCoordinator.kt |4B2FA99F3B77F0C7AE38FE14A307AE659B400C6A73985C75D17F2CAB9DB59C19|same|
| DirectShellPairingProtocolObservationTest.kt |22DC11BA3B3D7ED2A5AAA702E8CDC13CFFF9CF1F08282678272E0A1315A9723D|same|
| DirectShellPairingCompositionContractTest.kt |6B407AC72E08F6CC8CB8AB6742F31AB67F4CE77306BD1A7E48DDCE1BB2EBF3E7|same|

The37-check approved-model guard compares unchanged request/submit/cancel/
environment/close,R/receipt/current-admission/end and clock/timer regions against
the parent. Legacy prepared differs only by the explicitly approved mixed-path
fence. Legacy tests and17 other checkpoint source/build/tool paths retain their
committed contents. The model still imports no Android/network/filesystem/libadb
dependency and is unwired outside the two existing main source files.

## Final Software And Isolation Gates

| Fresh final-cohort gate | Result |
| --- | --- |
| Full/focused JVM |1553/195 and369/15 PASS,0 failures/errors/skips;41 new methods plus2 preserved discriminators |
| ktlintCheck / lintDebug / lintRelease |PASS;0 lint errors,126 Debug and34 Release warnings retained |
| Debug / AndroidTest / helper / unsigned Release assembly |PASS; software campaign27m31s,166 tasks; cold native compilation,not a latency benchmark |
| verifyOwnedPairingReproduction |PASS; two independent builds and selected artifact match the pinned patched AAR |
| Patched archive/class/DEX closure |619 checks PASS;43 unrelated classes unchanged,7 changed/2 additive pairing entries exactly as checkpoint; no new patch/duplicate Debug DEX definitions |
| Dependency/legacy API/classpath |132 checks PASS; libadb3.4.0 and BC1.84 unchanged,exact selected/tested artifact,no Release dependency promotion |
| Source-set/manifest/DEX boundaries |21 checks PASS; existing registration/SDK/provider boundaries preserved |
| P1/model |34 inherited/scoped guards plus37 approved-delta checks PASS; unchanged legacy core regions and tests |
| New observation compiled/DEX isolation |18 checks PASS in Debug and Release; both prepared overloads,redacted metadata,safe state and test exclusion |
| Storage/Android adapter compiled |10+14 checks PASS;45 Android methods compiled,0 executed |
| Publisher |36 source checks plus3 compiled ELF ABI/import guards PASS; no Android execution |
| Native media DEBUG/Release symbols |6 compiled guards PASS; native source/ABI inputs unchanged,no CTest executed |
| Offline media |40/40 replay PASS,0 failed/skipped |

Lint comparison retains the prior strict-comparison negatives. Against the older
transport reference134 Debug/42 Release warnings,the candidate has126/34 with
exactly eight removed GradleDependency advisories per variant and no added
semantic finding. Only the verified unchanged libadb trust-manager archive path
is normalized; no warning/source/dependency is suppressed or upgraded. These
removals predate this P1 delta. Do not claim strict historical warning-set
equality or closure of the unrelated Release-unit compile limitation.

The existing compiler warnings remain in software/gradle.log,including unchanged
PairingModel copy-visibility,native unused fields/parameters,Debug Opus optimization
notice and deprecated test-only hardware evidence API. No unrelated repair or
warning flag change was made. assembleRelease is not testReleaseUnitTest.

### Built Artifact Identities

Parent + four frozen source inputs -> recorded Gradle commands -> these outputs:

| Output | SHA256 |
| --- | --- |
| Debug APK |62D6745EE6C1E49552E63889B2E231946BB0A35CB95A418B364338543E44E5AE|
| AndroidTest APK |F0F744CB26AF83C630C8A9DA52E3BCFF063594FF1C9B8D973E32C9E62A0ED943|
| DirectShell helper JAR |FC0EB052B307CE1D864C1AF6CA0192B50E7C597155EF266F087BDAB650B5AE8A|
| Unsigned Release APK |49F32249A110412EE10001BCA3A1D41EBC854D4DE9897C07A7BE3576452C18E9|
| Selected/reproduced patched AAR |BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0|

Artifact copies and signing-verification receipts are under r1623/artifacts/ and
artifact-freeze.json. These APK hashes identify this build,not a promise of
byte-identical APK reproducibility. No artifact was manually modified to match
an older hash. The unchanged helper packaging task bundles all current Debug
DEX,not a stable subset: all19 helper entries are byte-identical to the current
Debug APK DEX. Its changed hash therefore includes the changed P1 bytecode and
normal archive identity. helper-apk-dex-provenance.json records per-entry hashes
and differences from the parent. No helper was launched or registered.

The unmodified original AAR remains SHA256
62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB,
upstream revision dcd931071b5e4b9ebc0ac598201755d5a8814b71. Its existing
Apache-2.0 source/provenance notices and controlled DEBUG-only extension remain.

The new fields are additive/source-compatible internal Kotlin metadata,not a
promise of binary compatibility with separately compiled older test APKs.
Target and AndroidTest were rebuilt together; no installation/mixed-fixture
runtime is authorized by these outputs. Public protocol and native ABI remain
unchanged. Android fsync/Keystore/adbd and actual joint composition D remain
unqualified by this model increment.

## Final Preservation And Handoff

Exactly four source/test paths and three documentation paths remain uncommitted:
the four source paths above plus this report,CURRENT_HANDOFF and FutureRoadmap.
Index empty,HEAD stillbd8a4edc5928715317eaed46cdd96b48da5fc9f5 on the isolated
protocol-observation branch. No automatic commit,push,merge,reset,stash or cleanup.
HEAD alone does not contain this new model; use its explicit source manifest.

preservation-start/final.json compare all25 other worktrees by HEAD,branch,index
and dirty path/content inventory. The inherited four large evidence files retain
their declared metadata-only comparison. Original research's496 dirty entries,
review/composition and all earlier checkpoints remain preserved. Phase7 R2 keeps
SHA256 0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6.
Historical receipts are rehashed,not overwritten. final-integrity.json contains
source/document/artifact identities,actual counts,guard receipts and final scope;
evidence-manifest.json seals the new receipts without self-reference.

Only this bounded P1 amendment is IMPLEMENTED/SOFTWARE TESTED. Its metadata
contract gate is resolved for the explicit path; C01-C26 composition is still
NOT IMPLEMENTED. Review/checkpoint this four-source/three-document candidate
before the next separately scoped synthetic composition task. No Android/device
access,real pairing,production credentials,Host/Session activation,Release
onboarding,P3-P5,Root/Shizuku/provider/media or Phase7 work was performed.

Final closeout: **1046 integrity assertions PASS**,25 protected worktrees
unchanged and576 sealed historical receipt files rehashed. The four source
files still match the pre-acceptance freeze exactly; copies are retained under
r1623/source/ for recovery,not staged. All12 observed fixture/test PIDs have
terminated with no owned survivor. Seven dirty paths and empty index are the
intentional final state. No source edit followed the full acceptance execution.
