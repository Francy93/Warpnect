# Future Roadmap

## R1713 RFC-006J Private Entry

[R1713](phase6_5/DirectShellR1713PrivateEntry.md): the private fixture
has an executable local keypad. The isolated unlocked RemoteInput reply
was delivered; the observed shade did not retain visible text, but
system-managed history was not qualified. Keep real pairing codes out
of RemoteInput pending a decision. Production onboarding, Android A06,
atomic Keystore writer and real inter-process role authorities remain
open. **P2_PARTIAL_BLOCKED**.

## R1712 RFC-006J Carrier And Role Boundaries

[R1712](phase6_5/DirectShellR1712CarrierAndRoleBoundaries.md): the
Samsung API31 lock-screen fixture did not deliver a reply. Do not
qualify retention or start production RemoteInput enrollment. Next
P3 work is explicit unlocked foreground entry in a private fixture;
physical Android backpressure and actual FD/Surface/privileged input
approval remain separate. **P2_PARTIAL_BLOCKED**.

## R1711 RFC-006J Control Qualification

[R1711](phase6_5/DirectShellR1711ControlQualification.md): standalone Control
v2 passed UID2000, artifact mapping, HMAC and teardown on A41/API30/31.
Review launch ownership before product integration, not another identical
standalone test. Approve the actual FD/Surface and input authority
boundaries, a real SystemUI gesture gate and a verifiable no-replace
provider before advancing the remaining roles. **P2_PARTIAL_BLOCKED**.

## R1710 RFC-006J Control And Private Setup

[R1710 evidence and decisions](phase6_5/DirectShellR1710ControlAndPrivateSetup.md):
both Samsung pairing dialogs show a numeric IPv4 and an ephemeral port
distinct from reconnect. The offline numeric adapter is not app wiring.
The distinct minimal Control DEX is isolated and not launched. Keep
D-P1-D-P4 strict, and review the launch/process witness and Input/PCM/Video
authority boundaries separately. The private setup fixture passed on the
two exact A41s, without production registration. **P2_PARTIAL_BLOCKED**.

## R1709 RFC-006J Network And Integration Gates

[R1709](phase6_5/DirectShellR1709NetworkAndIntegration.md): same-device
Network-bound synthetic TCP and cleanup passed on the exact A41/API30 and
API31. Next qualify Samsung numeric tuple visibility without pairing and
review the existing-only key capability option without relaxing no-replace.
Keep P3 Android delivery unregistered pending lifecycle qualification.
Extract the narrow P4 Control runtime before adapting the media/input
roles. P5 remains inactive. **P2_PARTIAL_BLOCKED**.

## R1708 RFC-006J Physical Owner And Prototypes

[R1708](phase6_5/DirectShellR1708Prototypes.md): AF25 PASS on the isolated
API30 A41. Review strict no-replace alias admission and API30/31 same-device
Network binding separately. P3 private delivery and P4 offline closure checks
are prepared but not registered; P5 remains inactive. **P2_PARTIAL_BLOCKED**.

## R1707 RFC-006J First-Enrollment And Integration Gates

[R1707 decision and validation record](phase6_5/DirectShellR1707FirstEnrollment.md):
D-P1-D-P4 approved, with a single-lease transaction and fail-closed restart
behavior. A compatible, non-replacing Android alias contract remains open.
AF25 still needs an isolated A41/API30 with 2 GiB free and no LOW/FULL state.
Review the API30/31 same-device Network admission, P3 private delivery
policy and P4 narrow artifact closure before any activation. P5 is inactive.
**P2_PARTIAL_BLOCKED**; emulator and R0b remain suspended.

## 2026-11-13 RFC-006J Isolated Android Owner

[Owner and dependency review](phase6_5/DirectShellAndroidPairingOwner.md):
the unregistered DEBUG owner implements the approved no-wakeup policy
and reuses the original P1 work deadline and physical drain. Keep
**P2_PARTIAL_BLOCKED**. Next: targeted platform evidence, approve the
first-enrollment transaction (D-P1-D-P4), review Network/NSD authority and
the distinct P3/P4 integration and P5 qualification gates.

## 2026-11-13 RFC-006J Physical API30 Evidence

[Physical Android gate](phase6_5/DirectShellPhysicalApi30Gates.md): positive
P1/P2 localhost composition, key absence, real no-replace collision, recovery,
cancellation and owned drain now have exact-handset evidence. Keep
**P2_PARTIAL_BLOCKED**. Remaining critical path: production identity and
recovery decisions D-P1-D-P4, Android owner/time and Network/NSD admission,
real-adbd composition, then separately reviewed production setup, runtime
and Release qualification. Emulator/R0b remain suspended.

## 2026-11-12 RFC-006J Debug Pairing on A41/API31

[r1702 hardware evidence and Debug consolidation](phase6_5/DirectShellDebugPairingR1702.md):
one real Host-UI pairing, NSD-bound TLS reconnect, and helper HMAC/shutdown
passed with verified cleanup. The normal Debug build excludes the diagnostic
A41 gate and temporary package. No Release or Android synthetic fixture
runtime qualification follows from r1702 itself. **P2_PARTIAL_BLOCKED**;
retain existing checkpoints and historical negatives. The subsequent physical
API30 gate is recorded above. Emulator/R0b suspended.

## 2026-11-03 RFC-006J API30 Fixture Independent Checkpoint Review (r1634)

[Review and fresh evidence](phase6_5/DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-independent-software-checkpoint-review-r1634).
Six-source admitted cohort unchanged;independent authority/Keystore/native/bridge/
drain/AF01-AF24 source review passed with disclosed runtime and negative-test limits.
Fresh JVM1606/1606 in198 suites,focused422/422 in18,replay40/40;build/lint/AAR and
isolation PASS. Corrected producer61 controls plus8 guards PASS.69 Android methods
compiled,zero executed;CTest not executed. Final admission7037 checks PASS. Source
checkpoint2981d6626f28a71f1671eafce10aa67ee2a90e5d;documentation SHA and final
two-commit verification are external in FinalCheckpointReport.md. The resulting
P2_ANDROID_API30_SYNTHETIC_FIXTURE_CHECKPOINT_VERIFIED label is software only,
conditional on that postverification,not Android qualification.
Retain P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED and prior P1/P2 checkpoints,
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED,all historical negatives and P2_PARTIAL_BLOCKED.
Next recommendation only:separately authorized disposable exact-API30 fixture with
identity/isolation/owned cleanup preflight,including inherited DEBUG initializer.
No device,adbd,production provisioning,Host/Session,Release,P3-P5 or Phase7 work here.

## 2026-11-03 RFC-006J API30 Fixture Software Admission (r1633)

[Admission and unchanged source](phase6_5/DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-guard-producer-correction-and-software-admission-r1633):
external PowerShell producer corrected with strict boolean/name checks,not cosmetic
null replacement.61 controls PASS,real8/8 compiled checks;original consumer unchanged
passes6232 checks in one attempt. Original malformed producer/receipt/negative kept.

No application/test/build/dependency/native/protocol edit. Source5092B52E...D606C4348
and all artifacts unchanged. REUSED verified r1632 software:1606/198 full,422/18
focused,40 replay,build/lint126/34,AAR/isolation;not fresh executions.69 Android
methods compiled,zero executed. No Git checkpoint,Android runtime or hardware
qualification. Three factual docs updated after admission;changes uncommitted.

Retain prior owner-drain/P1/P2 software checkpoints;P2_PARTIAL_BLOCKED,Phase6.5
incomplete.33 protected locations preserved with disclosed missing-Git/metadata-only
limits. Historical Windows/SDK/Release-unit/lint/media/hardware/R2 negatives remain.
Recommend only separately authorized disposable exactAPI30 runtime preflight and
fixture execution with explicit identity/UID/alias/root/transport/rollback scope.
No device/emulator/ADB/USB/NSD,real adbd,production D-P1-D-P4,Host/Session/Release,
Root/Shizuku,media/SCL,P3-P5 or Phase7 action. Production owner/clock/identity,
Network/NSD,real adbd/write/suspend-connect,API/OEM/device and Release remain open.

## 2026-11-03 RFC-006J API30 Fixture: Test-Only Boundary Corrected

[Correction and preserved history](phase6_5/DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-test-only-api-correction-and-software-acceptance-r1632):
whole DEBUG peer bridge now RestrictTo(TESTS),with no suppression or library patch.
Only annotation/import change from r1631;all five other candidate sources and
existing AF/C/H methods unchanged. New cohort5092B52E...D606C4348;old failed cohort
and127-warning XML remain preserved. No new formal checkpoint or completed
acceptance:one final admission stopped on malformed fixture-compiled.json.
External C:/wg-pairing-fixture-boundary-r1632-20261103/ preserves the first negative.

Fresh1606/198 full,422/18 focused JVM,40 replay PASS;69 Android methods/5 suites
compiled,zero executed. Builds/ktlint/lints/reproduction PASS;unchanged semantic
warning comparator126/34,no additions. Previously interrupted classpath/compiled/
DEX/helper/handoff/fixture-isolation gates completed. Nine explicit API-boundary
checks PASS. Separate eight-check compiled-fixture producer is not qualified:
two null labels and improperly grouped PowerShell conjunctions. No relaxed
consumer,producer correction or repeated admission. Application source unchanged
after the negative;this is an external verification defect,not an Android failure.
Prior P1/P2 checkpoints retained;overall P2_PARTIAL_BLOCKED,Phase6.5 incomplete.

All changes uncommitted;no Android/device/emulator/ADB/USB/real pairing,production
provisioning,Host/Session/Release activation or unrelated work. Historical Windows
failure UNATTRIBUTED,Release-unit/lint/SDK/hardware/media/R2 limitations unchanged.
Next recommendation only:separately authorize the minimal external producer
correction with negative controls and a separately identified receipt/admission.
Then,only with separate authorization,disposable exactAPI30 runtime preflight.
D-P1-D-P4,Android owner/clock,
Network/NSD,real adbd,write/suspend/connect,API/OEM/device and Release remain open.

## 2026-11-02 RFC-006J API30 Fixture: Compiled, Software Acceptance Blocked

[Report](phase6_5/DirectShellPairingAndroidApi30CompositionFixture.md): Gate A/B
source review PASS for an isolated instrumentation owner,not production lifecycle.
Existing P1/Android FS/test AES/native publisher/owned TLS-PAKE/drain are connected
without core/storage/protocol changes.69 Android methods compile,zero execute;
24 new AF scenarios. Final1606/198 full and422/18 focused JVM,40 replay and Gradle
build/ktlint/lint tasks PASS. Four host bridge methods added; prior C/H unchanged.

Final software aggregate STOPPED_FIRST_FAILURE on new DEBUG VisibleForTests
warning at DebugSyntheticPairingPeerAuth.java:20. No suppression or post-stop
source correction; remaining classpath/fixture-specific compiled/DEX checks not
run. Six source/build/test plus three docs uncommitted; no checkpoint earned.
Parent656fcef retained; protected observable Nov2 state preserved with preexisting
missing-Git metadata explicitly disclosed. AF15 timing correction and superseded
intermediate evidence are separate; no Android runtime/power-loss claim.

Recommend only a narrowly authorized test-only visibility review/correction,new
source freeze and software gates before any separately authorized exactAPI30
runtime fixture campaign. No production D-P1-D-P4,real adbd,Host/Session/Release,
Root/Shizuku,media,P3-P5 or Phase7 work. Prior owner-drain/synthetic/P1/libadb/Windows
software checkpoints retained; Windows failure UNATTRIBUTED. P2_PARTIAL_BLOCKED;
Phase6.5 and historical hardware/media/Release-unit/SDK/lint limitations remain.

## 2026-10-30 RFC-006J Owner Drain Local Checkpoint (r1630)

[Checkpoint evidence](phase6_5/DirectShellPairingDrainHandoff.md#2026-10-30-independent-checkpoint-resumption-r1630):
P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED, conditional on the final external
committed-blob/cleanliness proof. Source checkpoint
08c6f1c3034de1545286cdc802e3731eaac10d98 plus its direct three-document child
on codex/phase6-5-pairing-owner-drain-checkpoint from eabc12225f34e6a3c26dbce84fbfe6ddb36b3dff.
Full documentation SHA is external under
C:/wg-pairing-drain-checkpoint-resume-r1630/. Exactly two source/test paths and
three docs; no production correction, new feature, push or merge.

The explicitly approved external admission correction validates PASS,integer
count132 and an actual132-element checks array. All other predicates unchanged;
15 schema controls and the sole corrected admission4966 checks PASS. Original
r1629 failed script/exit1/negative remain preserved, not retroactively PASS.
Source cohort EDC7150F...9E88A79 unchanged;31 protected worktrees,371 sealed r1629
files and2455 earlier sealed files verified.8230 provenance/schema checks PASS.

Prior r1629 results REUSED after hash/command/XML/artifact verification,not rerun:
1602/197 full JVM,418/17 focused,40/40 media;0 failures/errors/skips; builds,lints,
AAR reproduction and source/compiled/DEX/manifest/native guards PASS.45 Android
methods compiled,0 executed; no CTest. Historical Windows UNATTRIBUTED,strict
lint/Release-unit,r1628/r1629 verifier negatives,CXX5304,USB/native-layout and
hardware/media limitations remain. No Android/runtime/power-loss qualification.

Overall P2_PARTIAL_BLOCKED; Phase6.5 remains open. Retain implementation and
earlier P1/synthetic/libadb/Windows software statuses. Recommend only a separately
authorized disposable exact-API30 synthetic fixture using current private FS,
Keystore test AES/native no-replace/test RSA/P1+owner drain, with clock/timer/
lifecycle gate first. No D-P1-D-P4 production provisioning,real adbd,NSD,normal
Host/Session registration,Release onboarding,Root/Shizuku,media,P3-P5 or Phase7.
No future increment or Android/emulator/device/ADB execution in this checkpoint.

## 2026-10-30 RFC-006J Owner Drain Handoff (r1628)

[Approved D-O1/D-O2 implementation](phase6_5/DirectShellPairingDrainHandoff.md):
P2_OWNER_DRAIN_HANDOFF_IMPLEMENTED_SOFTWARE_TESTED. Unregistered DEBUG wrapper
around the unchanged real synthetic delegate;one lazy same-owner observer is
independent infrastructure,not an attempt-owned worker falsely marked drained.
Original ticket/work/deadlines and observed R/P/S/D/F remain. One bounded close
observation,no retry/rearm;actual worker/store uncertainty retains the fence.
No new authority,normal registration,production key or existing core change.

Fresh18 new methods,418/17 focused,one1602/197 full JVM and40 media PASS;
required builds,ktlint/lints,AAR reproduction and source/compiled/DEX/dependency/
native guards PASS. A final lexical verifier initially confused `.scheduler`
with a schedule call; original negative retained,only verifier corrected with
positive/negative controls,53 final assertions PASS. No full-test rerun or
application-source correction. Historical Windows/lint/Release-unit negatives
and fresh SDK XML diagnostic are recorded,not suppressed or reattributed.

Parent eabc122/source3547a51c;new isolated owner-drain-handoff branch. Exactly
two new source/test files plus three docs remain uncommitted,index empty.
30 protected worktrees,2072 sealed receipts and Phase7 R2 preserved.
45 Android methods compiled,0 executed;no CTest or Android/device/adbd evidence.
Host directory sync is modeled; Release setup unavailable,minSdk26/API30+
Wireless Debugging policy unchanged. Overall P2_PARTIAL_BLOCKED;Phase6.5 open.

Next only: independent review and separately authorized local checkpoint of
this exact bounded candidate. Android owner/clock/timer/fixture wiring,
D-P1-D-P4 production provisioning/recovery,Network/NSD binding,real adbd/API/OEM
qualification and Release onboarding remain separate. No automatic P3-P5,
Root/Shizuku,Host/Session,media/SCL or Phase7 work.

## 2026-10-29 RFC-006J Synthetic Checkpoint Review (r1626)

[Independent review](phase6_5/DirectShellPairingSyntheticComposition.md#2026-10-29-independent-review-and-checkpoint-r1626)
PASS within host-localhost scope. Exact r1625 five-source candidate preserved;
no new implementation or accepted contract change. Fresh400/16 focused JVM,
1584/196 single full JVM and40/40 replay PASS; builds,lints,AAR reproduction,
actual DEX/classpath/manifest,P1/storage/native ABI guards PASS. Two local commits
form the gated checkpoint: source3547a51ca2cf75af7e4c1259f6d428f44185aaef plus its
direct three-document child. P2_SYNTHETIC_COMPOSITION_CHECKPOINT_VERIFIED requires
the final external blob/cleanliness/preservation receipt under
C:/wg-pairing-synthetic-checkpoint-r1626/.271 pre-commit admission checks PASS;
no push,merge or new source correction. Full documentation SHA recorded externally.
28 prior worktrees and1682 sealed receipts protected; original candidate untouched.

Recovery revalidation,original deadline,actual protocol evidence,normal R/P/S and
owner-observed physical D/F reviewed. Positive directory S modeled; no Android
automatic lifecycle dispatcher,production enrollment or Host/Session authority.
Next,only after checkpoint: a separately authorized Android composition-boundary
review/isolated fixture plan using existing storage/Keystore/native publisher and
observed P1 ports. No automatic real adbd,production alias,NSD or Release work.
Overall P2_PARTIAL_BLOCKED; Phase6.5 and historical media/hardware debts remain.
45 Android methods compiled,0 executed; no native CTest. Strict historical lint,
Release-unit compilation and unattributed Windows failure remain preserved.

## 2026-10-29 RFC-006J P2 Synthetic Composition

[C01-C26 implementation](phase6_5/DirectShellPairingSyntheticComposition.md):
P2_SYNTHETIC_COMPOSITION_IMPLEMENTED_SOFTWARE_TESTED on localhost/Windows.
Real P1/observed D1-D7,recovery admission,encrypted host storage,owned socket,
authentic pinned TLS/PAKE and existing R/P/S/D/F cooperate. Directory-sync
acknowledgement is modeled,not Android/power-loss proof. Actual owner-side
worker/socket/lease/channel drain is distinguished from semantic eligibility;
unknown P/S or unresolved resources cannot create success or new authority.

Fresh1584/196 full JVM,400/16 focused,31 composition methods,40 replay,required
build/lint/reproduction and source/compiled/DEX/manifest/native guards PASS.
45 Android methods compiled,0 executed; no CTest or new hardware qualification.
Only additive read-only recovery revalidation and original-budget worker wait
support the inert DEBUG adapter; P1,old methods,formats,libadb,providers,media,
SDK and Release availability remain unchanged. Five source/test plus three docs
are uncommitted at688f13bd on the isolated composition-integration branch.

27 other worktrees,1270 sealed receipts and Phase7 R2 preserved. Historical
Windows refusal remains UNATTRIBUTED; strict lint/Release-unit limitations and
G1-G5/media debt are not closed. Next: independent review and separately
authorized checkpoint,not automatic real pairing or Android/Release promotion.
Actual adbd,Android transport/key/transaction,NSD binding,OEM/API/device and
Release gates remain open. Overall P2_PARTIAL_BLOCKED; Phase6.5 incomplete.
No push/merge,device/ADB/USB/NSD,Host activation,Root/Shizuku,P3-P5 or Phase7 work.
Earlier entries retain historical scope,not current composition status.

## 2026-10-29 RFC-006J P1 Observation/Drain Checkpoint

[Final P1 review/checkpoint](phase6_5/DirectShellPairingProtocolObservation.md):
P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED,local software only. Source checkpoint
5c951ecda3693981918ad8c128c02de76d679f9f contains four reviewed paths; its direct
three-document child and clean/blob proofs are recorded externally under
C:/wg-pairing-observation-checkpoint-r1624/. D1-D7 review PASS; no source/test
correction,policy expansion or inherited-test change in consolidation.

Fresh1553/195 full JVM,369/15 focused,40 replay,builds/lint/reproduction and
source/compiled/DEX/manifest/native isolation PASS. No new lint finding; strict
historical warning differences and Release-unit compile limitation remain.
45 Android methods compiled,0 executed; no CTest or new Android/hardware claim.
Known P/S,attested physical D and semantic eligibility remain independent;
model tests do not prove actual joint socket/storage drain.26 other worktrees,
927 sealed receipts,master/upstream and Phase7 R2 preserved. Windows refusal
remains P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED,not a fixed OS mechanism.

P2_SYNTHETIC_COMPOSITION_NOT_STARTED. Recommend a separate C01-C26 localhost
increment from this checkpoint with exact identity binding,existing recovery
admission,explicitly modeled host directory sync,authentic owned protocol and
observed joint drain. Do not execute automatically. Overall P2_PARTIAL_BLOCKED;
real adbd/Android transport,key/transaction,NSD endpoint,OEM/API/device and
Release qualification remain open. G1-G5 hardware PARTIAL,media debts OPEN,
Phase6.5 incomplete. No push/merge,device,Host,Root/Shizuku or Phase7 work.
Earlier notes below retain historical scope,not current uncommitted status.

## 2026-10-29 RFC-006J P1 Attestation And Drain Amendment

[Bounded implementation](phase6_5/DirectShellPairingProtocolObservation.md):
approved D1-D7 implemented/software-tested as an optional exact-ticket observer
path,not a new coordinator or production backend. Verification and POSSIBLE
effects coexist without a trust upgrade; semantic reconciliation remains
separate from resource D and P/S. Legacy behavior and budgets are preserved.
Fresh1553/195 full,369/15 focused JVM,40 replay,builds/lint/reproduction and
applicable isolation guards PASS.45 Android methods compile,0 execute; no CTest
or new hardware qualification. Four source/test paths plus three docs are
uncommitted atbd8a4ed on the isolated protocol-observation branch.

Review/checkpoint the narrow P1 delta before a separate C01-C26 synthetic
composition. That end-to-end integration is NOT IMPLEMENTED; overall
P2_PARTIAL_BLOCKED remains,with real adbd/Android transport,key,endpoint/OEM and
Release gates open.25 other worktrees preserved. No Root/Shizuku,media,Phase7,
accepted-RFC or Phase6.5 completion change; older checkpoint notes remain history.

## 2026-10-28 Amended Patched libadb Software Checkpoint

[RFC-006J checkpoint receipt](phase6_5/DirectShellPairingPatchedLibadbIntegration.md#2026-10-28-amended-candidate-local-checkpoint):
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED, source9576fdca42b9c0b709ddd2af51464f3464324fd9
plus its direct seven-document child. Exactly19 source/build/test/tool paths,
no new production delta beyond the frozen implementation; approved Windows
crash-safety versus release-qualified recovery tests retained. Fresh1510 full,
326 focused JVM,40 replay, builds/lint/reproduction and applicable guards PASS.
45 Android methods compiled,0 executed.23 protected other worktrees preserved.

Retain P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED and
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED in Windows host scope.
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED remains: immediate post-crash availability
was superseded by approval, not root-caused. Overall P2_PARTIAL_BLOCKED.
Next is separately authorized synthetic joint P1/storage/transport composition,
NOT STARTED. Real adbd, Android transport/key/NSD/OEM qualification and Release
onboarding remain open. No device, Host, P3-P5 or Phase7 work. Phase7 R2 remains
PROPOSED; G1-G5 hardware PARTIAL and historical media debts OPEN. Phase6.5 is open.

## 2026-10-28 Windows Lease Test Contract Amendment

[RFC-006J P2 test-only successor](phase6_5/DirectShellWindowsLeaseTestContractImplementation.md):
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED. Explicitly approved
split: real forced-death safety accepts only authenticated committed recovery
or strictly unavailable BUSY after all retained-data/parent-cleanup checks;
separate explicit child lock/channel-release acknowledgement requires committed
recovery, never BUSY. No production storage or transport change.

Fresh211 focused and one full unfiltered/noninstrumented1510/1510 JVM run PASS,
193 suites (+3 methods),0 failures/errors/skips; ktlint/isolation/integrity PASS.
Historical Windows failure remains P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED. The
old immediate post-crash availability assertion is superseded, not reproduced
or explained.22 protected worktrees and frozen candidate/evidence preserved.

Review the uncommitted two-test/three-document delta before separately approved
checkpoint work. P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED is NOT YET EARNED;
synthetic P1/P2 composition NOT STARTED. No automatic pairing, devices, Release,
P3-P5 or Phase7 advancement. Overall P2_PARTIAL_BLOCKED, Phase7 R2 PROPOSED,
G1-G5 hardware PARTIAL, existing media debts OPEN; Phase6.5 is not complete.

## 2026-10-28 Owned-Socket Pairing Protocol Software Boundary

[RFC-006J P2 implementation](phase6_5/DirectShellPairingPatchedLibadbIntegration.md):
P2_LIBADB_PATCH_PACKAGING_VERIFIED and
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED for Windows/JDK21 localhost.
The explicit DEBUG patch retains libadb3.4.0 and unrelated ADB behavior; authentic
TLS/PAKE runs over the operation-owned socket with the original P1 deadline.
1507 full/323 focused JVM (+35),40 replay and applicable build/lint/isolation/
reproduction gates PASS.45 instrumentation methods compile, none execute here.
No Android transport/blocked TLS-write qualification or real adbd trust claim.

Overall P2_PARTIAL_BLOCKED. Review/checkpoint the17-source/six-document cohort,
then separately authorize synthetic joint P1/storage/transport composition with
pre-PAKE durability and physical drain, not real pairing or Release onboarding.
No new commit, device access, provider/Session/media change or P3-P5 work.
19 protected worktrees, G1-G5 hardware PARTIAL, existing media debts OPEN and
Phase7 R2 PROPOSED remain unchanged. Phase6.5 is not complete.

## 2026-10-28 Transport Frozen, Protocol Packaging Decision Required

[RFC-006J P2 protocol adapter review](phase6_5/DirectShellPairingProtocolAdapter.md):
P2_TRANSPORT_CANCELLATION_CHECKPOINT_VERIFIED at c5577c776300d99d2b69a00b99584295b0f06184
(source) / be6beb9453b09f44340233ffbd3eca2577ef1c97 (docs). Fresh1472 full/288 focused
JVM,40 replay and scoped isolation PASS; no Android transport qualification.
P2_ADB_PROTOCOL_ADAPTER_DESIGN_REVIEW_REQUIRED: a narrow owned-connected-socket
context extension needs explicitly reviewed DEBUG dependency/class packaging,
validated peer evidence and the unchanged authoritative P1 deadline. The current
AAR cannot accept that socket; a test JVM overlay is not APK integration.

No Task B source/build/dependency change. Next is that bounded design decision,
not a broad library fork, copied crypto, real pairing or Release activation.
Overall P2_PARTIAL_BLOCKED.18 protected worktrees, Phase7 R2 PROPOSED, G1-G5
hardware PARTIAL and historical media debts remain unchanged; Phase6.5 is open.

## 2026-10-28 Localhost Transport Lifecycle Increment

The [reassessed continuation](phase6_5/DirectShellPairingTransportCancellation.md)
records P2_TRANSPORT_CANCELLATION_IMPLEMENTED_SOFTWARE_TESTED for the inert DEBUG/
host-loopback component only:33 new tests,1472/288 JVM,40 replay and required
software/isolation gates PASS. The prior refusal was overly broad, not a product
or Android defect. Partial sources/negative receipts are preserved; five app/build
and four documentation paths stay uncommitted atffe1d1b. No backend wiring,
credential publication, device/ADB-server activity or Release onboarding.

Overall P2_PARTIAL_BLOCKED. Protocol adapter, exact P1 deadline handoff, real
endpoint/identity/Android transport qualification and credential lifecycle remain
open. Review/checkpoint the component before a narrowly scoped adapter review;
no silent library fork, real pairing or P3-P5. P1/storage/media/SDK/provider/RFC
contracts and17 protected worktrees remain unchanged. G1-G5 hardware PARTIAL,
historical media debts OPEN and Phase7 R2 PROPOSED remain separate. Phase6.5 is
not complete. Older sections below are retained historical receipts.

## 2026-10-28 Multiprocess Checkpoint, No Transport Acceptance

[Checkpoint/stop receipt](phase6_5/DirectShellPairingTransportCancellation.md):
5f1fe26 source/tests andffe1d1b documentation freeze the verified multiprocess
candidate; its worktree is clean. The isolated transport increment remains
incomplete/uncommitted, with zero new transport tests executed. It was stopped
at an assistance safety boundary, not a new architectural blocker or validated
failure mechanism. No backend, Release, P3-P5 or Phase7 advancement is claimed.
Use the clean checkpoint, not the incomplete branch, for the tested baseline.
17 protected worktrees and existing negative evidence are preserved. Overall
P2_PARTIAL_BLOCKED; G1-G5 hardware PARTIAL and historical media debts unchanged.

## 2026-10-28 Independent Android Storage Ownership

[RFC-006J multiprocess receipt](phase6_5/DirectShellPairingMultiprocessStorageValidation.md):
native publisher checkpointed locally at4378f06/5d262e4; separate DEBUG-only fixture
is IMPLEMENTED/SOFTWARE TESTED and VALIDATED on the exact API30 AOSP x86_64 image
only.45/45 actual methods cover independent same-UID processes plus all34 prior
storage/native/lifecycle cases;1439/255 JVM,40 replay, build/lint/isolation PASS.
P1, production storage/native/media and unavailable Release setup are unchanged.
Task B remains uncommitted;16 protected worktrees and Phase7 R2 preserved. Cleanup
and retained harness failures/USB preflight incident are explicit in the report.
Overall P2_PARTIAL_BLOCKED. Android ARM/OEM/power-loss and real transport/NSD/
identity/cancellation/key-lifecycle qualification remain open. Next bounded task
is review/authorized checkpoint of this fixture, not automatic backend/P3-P5.
G1-G5 hardware stays PARTIAL; historical media debts stay OPEN. Phase6.5 is not
complete; no Phase7 implementation or migration is authorized by these results.

## 2026-10-27 Native Credential Publication Component

[RFC-006J native publication/storage integration](phase6_5/DirectShellPairingNativePublicationIntegration.md)
is IMPLEMENTED/SOFTWARE TESTED and VALIDATED only on the named disposable
API30 AOSP x86_64 fixture:34/34 platform methods,1435/251 JVM and required gates
PASS. Public API30 renameat2 replaces Android's denied hard-link path without
changing encrypted v2 records, P1 policy or global minSdk26. Successful P consumes
pending; S, cleanup and physical D stay independent. No Release setup or real
backend is activated. Overall P2_PARTIAL_BLOCKED; cross-process Android/OEM/ARM/
power-loss and remaining transport/identity/key lifecycle gates are still open.
Next proposed bounded work is review/checkpoint then separately authorized
same-UID process-exclusion qualification. No P3-P5, media or Phase7 implementation.

## 2026-10-27 Android Pairing Storage Increment

[P2 Android storage/key adapter](phase6_5/DirectShellPairingAndroidStorageAdapter.md)
continues verified P1 checkpoints7f3e39f/856bca7 with a selective, uncommitted
storage source closure, not the old dirty research cohort. Final1383 full/199 focused
JVM receipts and40 offline tests are recorded there;26 new platform methods
compile, not run. API26 descriptor operations fail closed (public CLOEXEC needs27).
Public Android fsync/no-replace/exclusion and non-exportable key-handle code are
unwired; Android runtime qualification and explicit production key provisioning
remain pending. P2_PARTIAL_BLOCKED, Release unavailable, no P3-P5/device/Phase7
implementation. Final gates and next bounded qualification scope are in the
receipt. Historical sections below retain their original evidence scopes.

## 2026-10-27 Pure Pairing Reservation Model

The user-approved [P1 reservation/receipt amendment](phase6_5/DirectShellPairingReservationModel.md)
is IMPLEMENTED/SOFTWARE TESTED on isolated `codex/phase6-5-pairing-commit-reservation`, from
P1 checkpoint73c0b6e. It removes the publication closure, models explicit
uncertainty/retention and keeps physical drain independent. It activates no
backend, Release setup or runtime. Separate uncommitted P2 remains PARTIAL_BLOCKED;
actual namespace CAS/sync, pre-PAKE recovery persistence, Android adapters and real
I/O cancellation/trust binding still require their own implementation/gates.
Final96 focused/1280 full JVM,40 replay, build/lint/isolation gates PASS; no
hardware or Release qualification. No P3-P5, Root/Shizuku, media, provider
migration or Phase7 change is authorized.

## 2026-10-26 DirectShell Pairing Checkpoints And Release Plan

[DEBUG pairing UI](phase6_5/DirectShellHostPairingSetup.md) is locally
checkpointed atf7dd2cf with its software receipt at2a6528d. Its13 code/test/
resource paths match the1184-JVM verified cohort; hardware remains NOT EXECUTED.
[Current handoff](phase6_5/CURRENT_HANDOFF.md) and
[Release architecture](phase6_5/DirectShellReleasePairingArchitecture.md)
separate already implemented RFC-006I/J orchestration from missing production
enrollment AND Release DirectShell runtime/helper/gateway activation.

The pure unwired P1 pairing owner/contracts are now IMPLEMENTED on isolated
`codex/phase6-5-pairing-enrollment-owner`, not backend activation; see the
[P1 verification receipt](phase6_5/DirectShellPairingEnrollmentOwner.md).
The [authorized local P1 checkpoint](phase6_5/DirectShellPairingP1Checkpoint.md)
preserves that exact source cohort at1271765, with separate documentation.
Its [bounded prompt](phase6_5/DirectShellReleasePairingImplementationPrompt.md)
was executed for P1 only. Existing libadb pair cancellation/timeout and trust/
credential lifecycle require review before Release promotion. No DEBUG probe,
dependency, automatic Host enable, Root/Shizuku behavior or provider ranking is
promoted. G1-G5 hardware remains PARTIAL, historical media debts remain OPEN,
Video observer stays isolated and Phase7 R2/S0 state is unchanged. Phase6.5 is
not complete; no main-development merge or push occurred.

## 2026-10-25 G5 Software Receipt

The [G5 diagnostic receipt](phase6_5/DirectShellDiagnosticConsolidation.md)
records isolated local checkpoints99f0bfc/f8cf491 and integrated software gates:
1155 JVM,26/26 native Debug and Release, Android Debug/Release builds and lint.
New runtime observations are DEBUG-only, explicit opt-in and default OFF;
output24ms/fault/Phase7 tools are excluded. The selected G1-G5 branch is ready
for controlled source integration, not a merge, hardware qualification or release
promotion. Original dirty evidence and frozen G1-G4 remain intact. RFC-006I/J,
Phase7 R2/S0 state and the independent audio/Video/timestamp debts are unchanged.

## 2026-10-25 G1-G4 Consolidation Receipt

The [isolated functional baseline](phase6_5/DirectShellFunctionalConsolidation.md)
records local logical checkpoints and software verification, not a development
branch merge or fresh hardware/release qualification. RFC-006I static planning
already exists; no planner/provider/recovery work is begun. Outstanding audio,
Video, timestamp and full Android regression debt remains explicit. Phase7 R2
and its separate S0/prototype plan are preserved unchanged in the original dirty
worktree; no future optimization is imported into this source projection.

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This roadmap is architectural context. Phase 1 core networking and Phase 2 core video implementation are complete.

## Phase 0 - Architecture, Repository & Toolchain

Status: Complete.

Implemented, defined and frozen Warpnect's core architecture, the boundaries between Kotlin/Android and C++/SCL, repositories, toolchains, and invariants.

- RFC-000A - Architecture Audit & Stabilization [complete]
- RFC-000B - Architecture Documentation & Invariants [complete]
- RFC-000C - Final Architecture Hardening [complete]
- RFC-000D - Repository & Build System Finalization [complete]

## Phase 1 - SCL Core Networking Engine

Status: Complete.

Implemented the platform-independent C++20 networking core:

- RFC-001A - SCL Packet & Runtime Foundation [complete]
- RFC-001B - SCL UDP Transport Engine [complete]
- RFC-001C - Fragmentation & Reassembly [complete]
- RFC-001D - Loss Detection, NACK & Recovery [complete]
- RFC-001E - Reed–Solomon FEC [complete]
- RFC-001F - Clock Synchronization & Network Telemetry [complete]
- RFC-001G - Phase 1 Integration & Benchmarks [complete]

## Phase 2 - Video Pipeline

Status: Core implementation complete through RFC-002G. RFC-002H is implemented supplemental
Android capture compatibility architecture.

Implemented:

- RFC-002A - Android Privileged Video Capture Foundation [complete]
- RFC-002B - Android Hardware Video Encoder Pipeline [complete]
- RFC-002C - Encoded Video to SCL Transport Integration [complete]
- RFC-002D - Android Hardware Video Decoder Pipeline [complete]
- RFC-002E - Low-Latency Rendering Pipeline [complete]
- RFC-002F - End-to-End Video Streaming [complete]
- RFC-002G - Video Latency, Recovery and Performance Tuning [complete]
- RFC-002H - Android Privileged Screen Capture Backend & Privilege Boundary [implemented]

Phase 2 core history remains complete through RFC-002G. RFC-002H production capture has hardware
validation on legacy API 30/API 31 and modern API 36 paths. H1 real-device media validation is
complete. RFC-002B strict-CBR metadata qualification now uses a disposable same-UID app process,
which contains vendor codec failure without terminating Warpnect. Final same-UID qualification
passed on two API 36 S22 devices plus tested API 30 and API 31 A41 devices. The A41
`InputApiUnavailable` investigation established a narrow Phase 4 Android platform-adapter defect:
the previous resolver assumed `InputManagerGlobal`, which is absent on tested API 30, API 31, and API
33 hardware. The production resolver now retains the modern adapter and falls back through capability
qualification to the legacy `InputManager` adapter. Local production injection of key, touch, pointer,
and joystick events passed on API 30, API 31, and API 33 under the Shizuku shell UserService.
The SystemAudio startup investigation found a capability false positive rather than an Input defect.
`AudioService` authorizes AudioPolicy registration against the Shizuku UserService Binder caller (shell
UID 2000), which does not hold `MODIFY_AUDIO_ROUTING`; the prior package-attributed check advertised a
channel that could not start. Capability qualification now checks the UserService's own permission, so
SystemAudio is omitted before setup when that caller cannot register its AudioPolicy. Post-correction
API 30 and API 31 A41 Host-to-S7 Sessions authenticated, committed setup, and started media without
`SystemAudioStartFailed`. A new human touch on the S7 Client was correlated through the protected Session,
legacy InputManager injection, and a Warpnect-owned target on each A41 Host, closing the two A41 reverse-input
validation targets.
The physical API 36 modern-input regression passed locally on the S22: `ModernInputManagerGlobal` was
selected and key, touch, pointer, and joystick events were accepted and observed. A separate S22 SystemAudio
investigation found an audio-only API 36 restriction. After the Shizuku UserService clears the incoming
application Binder identity, `AudioPolicy` registration can proceed as shell UID 2000, but the first
production loopback `AudioRecord` is uninitialized because AudioFlinger rejects the incoherent
`uid=2000, package=io.warpnect` attribution. The capability preflight now performs that same bounded
prepare/start/stop path before WNCP and therefore truthfully omits SystemAudio on this runtime. It neither
falls back after commitment nor generalizes the result to Samsung or API 36 devices beyond the tested S22.
The S22 reverse-input E2E result remains uncounted; this SystemAudio condition is separate from the modern
Input resolver result.
The API 33 tablet was revalidated with the same current production SystemAudio path. Its prior
`AudioRecordCreationFailed` no longer reproduces after the generic Binder-identity correction: the real
preflight prepares, starts, reads PCM, and stops successfully. A normal-app 48 kHz stereo `USAGE_GAME` tone
produced 68,400 captured frames, 116,112 non-zero samples, peak 8,191, and RMS 5,340.6 through the privileged
loopback path, closing the tablet SystemAudio compatibility debt as `SYSTEM_AUDIO_SUPPORTED`.
The formerly named tablet “pre-auth” blocker was instead a diagnostic false negative: the tablet's global
log threshold suppressed `Log.d` breadcrumbs, and the harness also used the S9's non-authoritative legacy
render callback as a media-success gate. With debug-only breadcrumbs emitted at `INFO` and media success
defined by Host media start plus Client decoder output, five Tablet-to-S9 first-attempt Sessions passed via
normal semantic teardown. Authentication, WNCP, setup, capture, encoder, transport, Client decode, and
Surface output all completed; a supplementary screenshot showed the tablet Host display on the S9. No model,
OEM, or API-specific runtime behavior changed.

On 2026-09-09, a subsequent tablet SystemAudio remote-E2E attempt found a changed external precondition rather
than an audio-pipeline regression. Shizuku Manager 13.6.0.r1086.2650830c starts every tested Warpnect
UserService as shell but fails before Binder publication: the minimal debug Binder plus production capture,
audio, and input classes all die in `LoadedApk.makeApplication`. The tablet's MediaTek framework calls
`Application.getProcessName().equals(mPackageName)` in its OEM resource-preload guard, but
`ActivityThread.systemMain()` has no application process name in the Shizuku UserService bootstrap, producing
the recorded null-pointer exception. Shizuku 13.6 introduced the forced `makeApplication` path; the identical
APK and provider version publish all four Binders on the A41. This is
`SHIZUKU_13_6_MAKEAPPLICATION_TABLET_FRAMEWORK_INCOMPATIBILITY`, not an API33-wide, OEM-wide, or audio-format
claim. The provider removes an unstarted service after 30 seconds without a `ServiceConnection` terminal
callback. Warpnect therefore bounds that local bind by the provider's own deadline and truthfully reports
`PrivilegedServiceUnavailable` instead of blocking Host readiness forever. A controlled provider-only A/B held
the Tablet build, Warpnect APK, app data, and UserService arguments constant and changed only the signed official
Shizuku Manager APK. The prior official 13.5.4.r1049.0e53409 APK
(`A05832CE3716AFB1FCCCF46F348006D2A296CA777E1FF3D223797DC74D06B31F`) published the minimal,
capture, audio, and input Binders on the Tablet; the same four binds also passed on the A41 control. Under that
provider the production tablet SystemAudio controller again prepared, started, captured a normal-app 48 kHz
stereo tone, and stopped cleanly (70,080 frames, 115,632 non-zero samples, peak 8,191, RMS 5,265.3). This proves
`SHIZUKU_13_6_PROVIDER_REGRESSION_CONFIRMED_FOR_TABLET_FRAMEWORK`, while remaining deliberately narrower than
an API33-, OEM-, or all-13.6 claim. The older provider is diagnostic evidence, not a Warpnect version requirement:
13.6 remains the current official stable and no official fixed release was available during the experiment. A
subsequent clean Tablet-to-S9 run under 13.5.4 completed the technical remote SystemAudio chain. The normal
997 Hz PCM16/stereo/48 kHz `AudioTrack` tone ran for 60 seconds and wrote 2,875,396 source frames. On the Tablet,
the privileged recorder, Shared PCM Ring, encoder, payload producer, and UDP sender remained active; the 50-second
snapshot showed 69,998 payloads and 69,999 generated/sent datagrams, ring high-water mark 7, no capture drop/overrun, no `WouldBlock`, and no
transport or Session error. On the S9, the receiver decoded 71,437 audio frames by the 60-second snapshot and the
native Oboe playback consumer advanced its PCM Playback Ring from 13,499,760/13,499,760 written/consumed frames to
16,251,120/16,250,976, bounded by its 960-frame high-water mark, with zero xruns and no receiver or Session error.
ADB stayed connected for the full measurement. `TABLET_SYSTEM_AUDIO_REMOTE_E2E_PASS` is therefore closed as
technical E2E evidence; human audibility is pending. This adds no device allowlist, audio fallback, payload change,
or Session retry. Official Shizuku 13.6 was restored after the controlled run and remains incompatible with this
Tablet's UserService bootstrap until upstream supplies a compatible provider revision.

The independent S9/API29 and S7/API26 Host coverage pass is complete. With the actual Shizuku shell-UID
UserService, S9 lacks `MODIFY_AUDIO_ROUTING`, so its production SystemAudio preflight returns
`PermissionDenied` and omits the channel before WNCP. S7 has the same real privilege-provider bootstrap but a
separate framework-contract boundary: PCM Shared Ring V1 uses `android.os.SharedMemory`, introduced in API27.
The capability path now returns `UnsupportedPlatform` on an API below that requirement and the direct prepare
path returns the same typed result without loading the unavailable class. This is a generic API-contract guard,
not a device, OEM, or blind API success rule. Neither Host can truthfully select SystemAudio, so local PCM and
remote E2E are not applicable; both classifications are complete. The task added no audio fallback, profile
change, payload/ring amendment, or Session retry.

RFC-002I is implemented supplemental Client decoder qualification for legacy Android where framework
hardware classification is unavailable. It uses conservative static inspection plus a contained,
same-UID active decoder qualification only for unknown legacy candidates. It does not alter RFC-002D,
RFC-002B, or the completed H1 validation.

Client presentation investigation identified a local `SurfaceView` composition defect: on affected
Samsung Clients, decoded buffers were behind the opaque Compose window buffer. The production view now
uses `setZOrderOnTop(true)`. A real A41 API 31 Host-to-S7 API 26 Client Session is human-confirmed
visible after the fix. S9 API 29 and tablet API 33 both visibly present the same local MediaCodec
fixture. The S9 now also passes A41-hosted authentication, WNCP, setup, receiver startup, AVC
configuration, decoder startup, first access-unit decode, and Surface release. The user then physically
confirmed the A41 Host screen, Android Home, and ordinary Host applications visibly streamed on the S9.
`S9 PRODUCTION VIDEO PIPELINE VALIDATED` closes the former S9 media-start/presentation debt. The tablet's
separate remote-session investigation is now validated through the Host pipeline and S9 Client decoder/output.
A follow-up A41 API 31-to-S7
capture-scope trace selected
the physical logical display (`source_display_id=0`, `layer_stack=0`) and remained active while the Host
left Warpnect. Home, Settings, and the notification shade were each visible on the S7; the user confirmed
the Settings view directly. `HOST FULL-DISPLAY CAPTURE VALIDATED` therefore closes the app-window capture
concern. The then-open growing-latency observation is resolved below as a Video Resync Control V1
sender-ownership defect, not a capture or presentation regression.

Privileged helper lifecycle validation then found a real Android ownership defect rather than an expected
Shizuku retention policy: unbinding a non-daemon UserService disconnects it but does not terminate its
process. The capture, audio, and input helpers now implement the reserved UserService destroy transaction,
clean their local resource, and exit after their final gateway owner releases them; temporary SystemAudio
capability controllers also close in `finally`. Four A41 API 31 Host-to-S7 API 26 Client media Sessions,
including a Host Home/background/return interval, left no capture, audio, or input helper after normal
teardown. A bounded harness correction now reaches the genuine off-screen `Disconnect` control before
declaring it absent.

Cold capability/session-negotiation validation identified a readiness-ordering defect, not an undersized
protocol timeout. Previously, the Host synchronously collected its mandatory capability snapshot only after
receiving a Client WNCP offer, after the Client's negotiation window had started. Host readiness now completes
that exact snapshot before advertising discovery and reuses it when responding to WNCP. An A41 API 31 Host and
S7 API 26 Client passed their first deliberately cold exact-qualification Session through authentication,
setup, and media; a subsequent exact-cache-hit regression also passed. WNCP, qualification workloads and
thresholds, payloads, and security semantics were unchanged. The S9 API 29 control also completed WNCP,
setup, receiver startup, AVC configuration, decoder startup, first access-unit decode, Surface release, and
human-confirmed remote presentation. Its former `VideoPipelineStartFailed` lifecycle race is closed.

RFC-002B strict-CBR qualification now retains a `Supported` result across application-process restarts,
but only under a complete versioned exact key: algorithm, probe workload, target profile, codec identity,
exact AVC request, Build fingerprint, and media-runtime compatibility version. The A41/API 31 first
exact-key miss ran its two eligible strict-CBR probes and persisted their `Supported` results; later new
processes, force-stop/relaunch, and data-preserving reinstall reused those records without spawning
`:codecProbe`. Timeouts, probe death, and other transient failures remain process-local and the existing
probe-death quarantine is unchanged. This removes repeated qualification work without treating
compatibility as an install-lifetime or device-model judgment.

Session establishment and restart reliability is now validated for the A41/API 31 Host-to-S9/API 29
Client topology. The root cause was not discovery reachability or a timeout: after normal cancellation,
the application-scoped Client coordinator remained terminal `Closed` while the retained discovery model
could still make the UI look ready. A subsequent Connect was therefore rejected as busy before pairing.
The discovery-preserving Client attempt path also retained a `SessionProtectionController` that it had
already closed. Normal disconnect now returns the Client coordinator to reusable `Idle`, and a new
attempt obtains a fresh session-protection owner without reusing a cancelled runtime. Ten consecutive
normal first-attempt A41-to-S9 Session cycles passed through media and semantic teardown without process
kills, force-stops, reboot, Shizuku restart, or hidden retry. Client-only, Host-only, and dual app-process
restart cases each passed on their first attempt with preserved app data; the Host used its persistent
RFC-002B result and did not spawn `:codecProbe`. An A41-to-S7 technical regression also passed. This
preserves SAS, WNCP/WNSN, Session protection, and all frozen protocol/ABI contracts; it is a local
runtime ownership correction, not automatic retry behavior.

The growing-streaming-latency investigation then identified a freshness-correctness defect rather than a
throughput or quality limitation. When a Client detected a video discontinuity, it correctly entered
`WaitingForKeyFrame` and sent Video Resync Control V1. The Android production sender had not bound
`NativeVideoSenderControlRuntime`, so its Host never pumped that control channel or forwarded a real
keyframe request to the encoder. It kept sending frames the Client could not safely use, which made the
last visible image increasingly stale. The production binding now owns the existing V1 control runtime;
no profile, payload, FEC, decoder, quality, or frozen protocol/ABI semantics changed. Final 180-second
A41 API 31 Host-to-S9 API 29 and A41 API 31 Host-to-S7 API 26 runs with repeated harmless Host-display
updates kept reassembly/ready occupancy at 0-1, showed no timeout/full-window growth, continuously
released decoder output, and recovered every observed resync through a forwarded keyframe request.
`GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` closes the stale-frame accumulation debt. Absolute
end-to-end latency remains intentionally unmeasured without cross-device clock provenance; residual
fixed latency and general throughput work remain Phase 7 concerns.

## Phase 3 - Audio Pipeline

Status: Complete.

Implemented:

- RFC-003A - Android Low-Latency Audio Capture Foundation [complete]
- RFC-003B - Portable Ultra-Low-Latency Opus Encoder [complete]
- RFC-003C - SCL Audio Payload and Transport Integration [complete]
- RFC-003D - Portable Ultra-Low-Latency Opus Decoder [complete]
- RFC-003E - Android Ultra-Low-Latency Audio Playback [complete]
- RFC-003F - End-to-End Audio Streaming [complete]
- RFC-003G - Audio/Video Synchronization [complete]
- RFC-003H - Audio Latency, Recovery and Performance Tuning [complete]

## Phase 4 - Reverse Input

Status: Complete.

Implemented:

- RFC-004A - Portable Input Event Model and SCL Input Payload V1 [complete]
- RFC-004B - Android Input Capture Foundation [complete]
- RFC-004C - Reverse SCL Input Transport [complete]
- RFC-004D - Android Privileged Input Injection [complete]
- RFC-004E - Input Mapping, Coordinate and Device Semantics [complete]
- RFC-004F - End-to-End Reverse Input [complete]
- RFC-004G - Input Latency, State Convergence, Reliability and Performance Tuning [complete]

Phase 4 is implementation-complete. Real-device privileged injection, UHID availability, and
game-specific compatibility remain device-specific validation work.

## Phase 5 - Discovery and Secure Session Management

Status: implementation-complete. RFC-005I integrates the normal Android Host/Client composition,
the public-API LAN and Wi-Fi Direct backends, concrete Phase 2-4 prepared-transport adoption, and
live-transport migration ownership. Real-device validation remains separate from implementation
status.

- RFC-005A - Session Identity & Core Session Model [complete]
- RFC-005B - Local Network Discovery & Presence [complete]
- RFC-005C - Pairing & Trust Bootstrap [complete]
- RFC-005D - Authenticated Session Handshake [complete]
- RFC-005E - Session Keys, Packet Authentication & Anti-Replay [complete]
- RFC-005F - Capability, Role & Feature Negotiation [complete]
- RFC-005G - Endpoint, Channel & Stream Negotiation [complete]
- RFC-005H - Session Lifecycle, Disconnect & Reconnection [complete]
- RFC-005I - End-to-End Discovery & Secure Session Integration [complete]

## Phase 6 - Telemetry and Diagnostics

Status: implementation-complete. Real-device validation debt remains.

- RFC-006A - Unified Runtime Telemetry Model [complete]
- RFC-006B - Media Pipeline Metrics Integration [complete]
- RFC-006C - Network & Recovery Diagnostics [complete]
- RFC-006D - Latency Trace & Cross-Pipeline Correlation [complete]
- RFC-006E - Diagnostic Logging & Bounded Event History [complete]
- RFC-006F - Runtime Diagnostics UI [complete]
- RFC-006G - Session Reports & Benchmark Export [complete]
- RFC-006H - Diagnostics Integration & Validation [complete]

Phase 6 is implementation-complete. Host/JVM, Android build, ABI, and native validation are green;
real Android/hardware validation remains separately tracked because no device was attached.

## Phase 6.5 - Privileged Runtime Evolution

Current microphone evidence: [final global-input closure](phase6_5/DirectShellGlobalMicrophoneFinalClosure.md)
validates C782DD9D r2 S9/API29 ->Tablet/API33 ordinary MIC3/3 above121s under one
Enable, close/reopen steady quality deltas0, real997Hz, UID NONE, natural three-
preset routing and normal/helper-loss cleanup. Fixed40ms IPC age is retained,
independent of80/70ms DEBUG playout.1022 JVM/22+22 native gates pass. Older partial
continuations later in this roadmap remain historical. A41 routing permission
and S7 product context-adapter boundaries remain; valid forced priority,
release promotion and universal qualification are not implied. Next separate
task was SystemAudio causal jitter/playout quality analysis. The
[completed exact-pair investigation](phase6_5/SystemAudioQualityRootCause.md) preserves
the microphone milestone at596a534 and independent Input reset atb812d82;
SystemAudio FC7D151F3/3 consecutive70s A41/API31 ->S9/API29 tones pass with
rejection/underrun/xrun0. Fixed85/70ms reserve follows measured phase range;
latency cost and all failed experiments remain documented. Final1037 JVM/22+22
native gates pass. Next ONE task is MicrophoneAudio Android Compatibility
Expansion (A41/S7), not started here; release/universal qualification is not implied.
Same-provider recovery/root-assisted remediation/live migration remain deferred
NOT IMPLEMENTED; composite pre-Session planning is not runtime recovery.

Status: validated DirectShell modern Host baseline; static per-capability
planning, optional Shizuku provider integration, and RFC-006J runtime
state/background Host readiness plus Step 2 persistent evidence and prepared
plan reuse are implemented, JVM-tested, and
hardware-admission validated on the currently tested A41/API31 and
Tablet/API33 DirectShell Hosts.

The validated modern DirectShell path uses user-authorized Wireless Debugging,
app-owned local ADB, app_process as shell uid 2000, and purpose-specific
DirectShell Video, SystemAudio, and Input servers. It has hardware evidence on
A41/API31, Tablet/API33, and S22/API36, with S9/API29 as the established
Client. The final modern SystemAudio matrix includes audible S9 confirmation
for every Host. DirectShell remains a carefully validated migration path, not
an unconditional release default.

The next milestones are:

- RFC-006I - Privileged Capability Planning and Composite Runtime [accepted; static layer implemented]
- Privileged Capability Matrix [implemented; JVM-tested; modern DirectShell Host admission validated]
- Privileged Capability Planner with static complete-plan selection [implemented; JVM-tested; modern DirectShell Host admission validated]
- Composite Host Plan ownership and controlled hardware validation [complete: A41/API31 to S9/API29; DirectShell Video/SystemAudio + Shizuku Input]
- Shizuku Optional Per-Capability Provider Integration [complete for controlled DEBUG composition]
- RFC-006J - Privileged Runtime Orchestration and Host Readiness [Step 1 lifecycle-validated; Step 2 process-restart/cache-to-freeze validated on A41/API31 and Tablet/API33]
- Runtime State Model and Background Host Readiness Coordinator [implemented: passive volatile observation, single-flight admission, generation-safe cleanup, and late RFC-006I plan freeze]
- Persistent Capability Evidence and Prepared Plan Reuse [implemented, JVM-tested, hardware-validated: bounded app-private positive evidence, exact compatibility keys, selective invalidation, advisory cache reconstruction, and mandatory late revalidation]
- DirectRoot Bootstrap + Root Recovery Authority Foundation [DEBUG/laboratory implemented, 783-test gated and AOSP/API26 emulator-validated; production Root authorization not implemented]
- DirectRoot Input Capability [implemented; exact AOSP/API26 laboratory QUALIFIED, 3/3 final-artifact cycles plus 1/1 active disconnect reset; 807-test gated; not physical Root validation]
- DirectRoot Video Capability [implemented; exact AOSP/API26 laboratory QUALIFIED, 3/3 frozen-artifact decoded-content cycles plus two active-loss cases; 841-test gated; no physical Root claim]
- DirectRoot SystemAudio Capability [implemented; exact AOSP/API26 lab QUALIFIED, 3/3 frozen-artifact OFF/ON PCM/ring cycles and actual Root-only plan freeze; 904-test gated; normal Host admission was a separate boundary]
- Complete DirectRoot Host Admission [normal application Enable/Disable Host and publication validated in exact AOSP/API26 lab; eight consecutive passes, 8/9 counted attempts with ninth ADB observation anomaly preserved; restart 1/1, loss 2/2, 931-test gated; no production/physical Root claim]
- Physical Root Device Qualification [next evidence boundary; no physical Root or production root-manager claim follows from the AOSP lab]
- In-Session Privileged Capability Provider Migration RFC [planned; live migration not implemented]
- In-Session Privileged Capability Provider Migration implementation [planned]
- Final privileged-runtime acceptance [planned]

The normal Host invariant remains Video plus SystemAudio plus Input. If no
complete qualified provider plan exists, Host is unavailable while Client mode
remains available. Shizuku is preserved as a future optional compatibility
provider rather than being unconditionally deleted.

RFC-006J Steps 1 and 2 leave Root-assisted Remediation, DirectRoot, Same-provider
Recovery, and Live Provider Migration NOT IMPLEMENTED. Passive application
startup never launches DirectShell feature servers or Warpnect Shizuku
UserServices; user Host admission re-observes runtime state, performs the
existing bounded RFC-006I admission, and freezes a plan only before Host
availability and discovery.

The laboratory foundation slice separates Root authority from Root capability
provision. It exposed volatile authenticated UID0 authority with no capabilities
at its original checkpoint. Production
Root-manager consent, physical Root validation and actual remediation remain
deferred. The lab authority passed 8/8 formal cases plus 2/2 resource cycles;
this does not qualify a physical Root device or product Root manager. See
`phase6_5/DirectRootBootstrapFoundation.md` for exact lab evidence.

The subsequent Input-only slice supplies typed, generation-owned Root Input
through the existing Android resolver/factory/tracker. AOSP/API26 observed real
key/touch/pointer/joystick delivery, complete reset and connection-loss release;
the exact final APK passed 3/3 qualifications without retries. The earlier
0/1 reset-fixture attempt remains recorded, not hidden. Positive cache evidence
is Input-only and explicitly LABORATORY_EXTERNAL_ROOT scoped; helper liveness
stays volatile. At that Input checkpoint Root Video/SystemAudio remained
NOT_IMPLEMENTED, so Root-only Host admission was incomplete. Synthetic DirectShell media plus Root Input
COMPOSITE and preference for a complete DirectShell SINGLE plan are JVM-tested,
not a manufactured API26 hardware composite. See
`phase6_5/DirectRootInputQualification.md` for 807-test gates, exact environment,
resources, cleanup and the bounded A41 endpoint-unavailable regression attempt.

The subsequent Video slice reuses the privileged mirror/projection and normal
app MediaCodec pipeline. UID0 receives the real encoder Surface through a
provider/UID/PID/generation/HMAC-bound Binder lease, not TCP. Exact AOSP/API26
frozen-artifact qualification passed 3/3 with decoded white-circle/checkerboard
content and two active-loss cleanup cases. Root Video/Input cache is scoped to
LABORATORY_EXTERNAL_ROOT; runtime stays volatile and startup stays passive.
At that Video checkpoint Root-only Host was incomplete because SystemAudio was NOT_IMPLEMENTED.
See `phase6_5/DirectRootVideoQualification.md` for exact artifacts, 841-test gates,
graphics-FD sampling limits and the optional Tablet admission accounting (1/2,
second attempt failed at runtime preparation, not a Root capture boundary).

The subsequent SystemAudio slice preserves the existing PCM Ring V1/drain/Opus
contract and adds only a provider-independent API26 ashmem ownership adapter and
finite authenticated Root audio control/descriptor handoff. The final APK passed
3/3 same-generation Input, decoded Video A/B and machine-verified silent/997Hz
Audio qualifications, then actual SINGLE/DIRECT_ROOT preparation, late
revalidation and freeze. Three isolated audio resource cycles also passed with
stable app FD samples. The earlier2/3 combined campaign remains recorded: its
third attempt stopped before Audio because the DEBUG reset observer incorrectly
assumed cross-source delivery order. Only that observer was corrected.

All three cells are QUALIFIED for the exact LABORATORY_EXTERNAL_ROOT key. At
the SystemAudio checkpoint this did not validate ordinary product Host admission:
the product Root bootstrap adapter is absent and AOSP Video qualification uses
the explicit DEBUG software encoder, not a bypass of the hardware-codec policy.
Production authorization/Magisk, physical Root validation, actual remediation,
same-provider recovery and live migration remain deferred. See
`phase6_5/DirectRootSystemAudioQualification.md` for exact artifact, attempts,
904-test gates, loss/cleanup evidence and complete-plan versus Host boundaries.

The subsequent normal Host slice closes application admission, not production
Root acquisition. A DEBUG/lab adapter registers a genuinely authenticated UID0
authority and finite capability-resource factory with the existing readiness/
admission owner. The same UI/controller action as normal Enable Host performs
late PING/generation checks, real acquisition and unchanged RFC-006I freeze
before the normal Host controller publishes Host Available and LAN discovery.
Freeze alone cannot publish. The lab's real Surface software codec is explicit
and does not assert the hardware-encode flag or alter release codec policy.

Eight consecutive normal Host cycles passed; the requested ten-cycle campaign
stopped on attempt9 at an inconclusive ADB inspection precondition (8/9, no
hidden retry). Cache evidence stayed byte-identical, categorized app descriptors
stabilized after emulator graphics warmup, restart admission passed1/1 and two
controlled helper-loss tests converged to teardown or truthful pre-freeze failure
without provider migration. See `phase6_5/DirectRootHostAdmissionValidation.md`.
Physical DirectRoot Device Qualification is the next evidence boundary; no
Magisk, remediation, same-provider recovery or live migration is implemented.
Because no already-rooted physical device is currently available, that
qualification is externally blocked. The non-Root priority queue and its
current closure evidence are tracked in
`phase6_5/CurrentNonRootDebt.md`. The Tablet's historical 1/2 DirectShell
preparation result was followed on 2026-09-28 by an exact-final-APK 10/10
normal Enable/Disable Host campaign, two earlier diagnostic 10/10 campaigns,
and a separate plan audit; its historical first failing stage
remains inconclusive because the old application trace was not captured. See
`phase6_5/TabletDirectShellRuntimeReliability.md`. Do not infer a product fix
or re-open closed video/cold-qualification/helper-lifecycle work from that old
high-level error.

RFC-006J Step 2 extends that passive path with a versioned, non-secret,
provider/capability evidence cache. Exact compatible records may create an
advisory prepared plan in the background, but no cache entry can advertise a
Host or skip late volatile revalidation and the existing final plan freeze.
Selective compatibility mismatch removes only the affected coordinate;
transient runtime availability does not poison or erase durable evidence.
Real A41/Tablet process restarts each loaded three persisted `QUALIFIED` records
and prepared a DirectShell-only advisory plan with zero feature servers and
zero Warpnect Shizuku UserServices. User Host requests then performed late
revalidation, current acquisition, and the existing plan freeze before Host
Available. Cleanup passed on both; no new audible/Session compatibility claim
is made by this cache-lifecycle validation. The earlier A41 missing TLS-connect
endpoint attempt remains separately recorded in RFC-006J.

## Phase 7 - Optimization

The [Phase 6.5 SystemAudio routing campaign](phase6_5/SystemAudioRoutingSemantics.md)
observed Client-only playback during normal DirectShell Sessions and restored
Host speaker playback after Disconnect on Tablet/API33 (3/3 human-confirmed),
A41/API31 (2/2 human-confirmed, plus a third technical Session) and S22/API36
(3/3 human-confirmed after it became available). The current supported
built-in-speaker semantic for this tested modern tier is `REMOTE_ONLY`, not a
universal Android guarantee or proof that the historical standalone A41
local-audio observation was wrong. Supported Host+Client duplication and
untested output routes are not qualified. The separate post-Disconnect
Session/discovery anomaly was reproduced on A41/API31 and fixed at the Session
terminal notification/Host availability boundary. The
[lifecycle report](phase6_5/PostDisconnectHostLifecycleReliability.md) records
10/10 A41 -> S9 reconnects under one Host enable and a separate fragmented
DirectShell launch PID fix. A later Tablet/API33 -> S9 gate passed 5/5
post-Disconnect Sessions under one Host enable; S22 confirmation remains
pending. The later
[MicrophoneAudio qualification](phase6_5/MicrophoneAudioE2EQualification.md)
reused Phase 3 Client-microphone -> Host Opus/Oboe infrastructure in three
S9/API29 -> Tablet/API33 protected technical Sessions plus one final-APK
spot-check. It did not establish
human-audible Tablet output. A separate long-idle control found Host Available
while its DirectShell helpers had expired. The subsequent
[owner-liveness follow-up](phase6_5/DirectShellIdleLivenessAndAudioPlaybackQuality.md)
implemented authenticated generation-bound owner sockets and loss-driven
publication withdrawal. Physical A41/Tablet controls crossed the former idle
boundary without a Host toggle; this closes the bounded ordinary-idle defect,
not background persistence. That earlier MicrophoneAudio follow-up remained PARTIAL: steady-state
ring rejection/zero-fill and Tablet physical output need causal closure. A
known external speaker tone captured on A41 and decoded on Tablet proves that
portion of the protected path, not the Tablet speaker. Owner liveness is now
checkpointed locally at `715b215`, not re-opened for another lifetime redesign.
The clarified [MicrophoneAudio destination architecture](phase6_5/MicrophoneAudioVirtualInputArchitecture.md)
requires **Android Host virtual microphone input** for production. Oboe output
is only DEBUG validation, explicitly labelled and unavailable as a release
virtual-input offer. S22/API36 shell demonstrated UID-scoped Android recorder
injection; A41/API31 policy authorization was denied. Additional/selectable
input and local-mic coexistence, consent and protected Session handoff remain
unimplemented, not disguised as speaker playback or generalized to all Android.
DEBUG Media routing is technically SPEAKER on A41; Tablet route-after-fix and
human audibility remain unconfirmed. Three final S9 -> A41 technical/lifecycle
Sessions passed but all steady quality windows retained rejection/zero-fill.
The first additional irregular boundary is protected submit -> pre-decoder
receive; precise network/OS/receiver causality still needs a bounded investigation,
not a larger ring. The 2026-10-01
[cadence/first-class input follow-up](phase6_5/MicrophoneAudioCadenceAndVirtualInput.md)
now observes additional jitter already at Host kernel receive, with local
authentication/lock timing separated from ingress. Cause and playback quality
remain INCONCLUSIVE/PARTIAL; no ring inflation or guessed transport change.
S9 -> A41 DEBUG speaker audibility is operator-confirmed. Tablet/API33 shell
can register a dynamic submix, but an unmatched ordinary consumer cannot open
the enumerated input; matching-UID injection succeeds. A41 shell registration
remains permission-blocked. First-class Android input is blocked on the tested
DirectShell paths, not universally disproven and not solved by scoped injection.
Those earlier software gates were959 JVM/0/0/0, native Debug/Release22/22 each.
The later [portable microphone ordered gate](phase6_5/DirectShellMicrophoneInjectionQualification.md)
proves AudioRecord API read batching before encoding, and physically validates
local paced S9 delivery without another PCM queue (592 intervals all3-7ms).
The initial control-suspension failure and Tablet Wi-Fi/ADB loss remain preserved.
After operator restoration, three consecutive final-APK S9 -> Tablet Sessions
above60s completed:3/3 lifecycle PASS,0/3 quality PASS. Source pacing improves
encoder/socket cadence, but kernel gaps41.8-46.2ms and steady ring rejection/
zero-fill remained in that campaign; no ring/codec/socket-buffer change. The then-next boundary was
post-pacer socket-send-to-kernel cadence causality, not global injection or a
larger buffer. Those exact-source gates were964 JVM/0/0/0 and native
Debug/Release22/22 each; no new source change/rerun in the resumed hardware phase.
Partial work remains uncommitted.
The subsequent [autonomous cadence closure](phase6_5/MicrophoneAudioCadenceRootCause.md)
establishes two Warpnect boundaries: an insufficient20ms PCM ring/5ms reserve
for ordered arrivals with roughly49-64ms local phase range, and cold output
startup while the ring was already full. An80ms bounded ring/70ms reserve
plus warm output clock closes exact S9/API29 -> Tablet/API33 DEBUG playback:
3/3 consecutive Sessions69.902/69.748/69.898s under one Enable Host, with
rejection/underrun/xrun0. Nominal priming adds65ms; pacer, codec and socket
buffers are unchanged. Kernel gaps40.9-45.5ms remain, so no Wi-Fi bug or
absolute E2E latency is claimed. The rejected60/50ms campaign and earlier
failures remain preserved. SPEAKER route and physical acoustic capture ->
remote decode are proven, final human Tablet audibility is not. Normal
Disconnect/Waiting/rediscovery and final helpers/secrets0 pass. SystemAudio
technical control passes but retains its separately open playback pressure.
Final gates972 JVM/0/0/0, native Debug/Release22/22 each; source remains
uncommitted. Global policy was deliberately not started in this task.
The new product experiment is one portable Dynamic AudioPolicy recorder mix
matching MIC/VOICE_COMMUNICATION/CAMCORDER capture presets, without application
UID criteria. The earlier explicit-port/scoped results neither prove nor disprove
that hypothesis. The later [global integration](phase6_5/DirectShellGlobalMicrophoneInjection.md)
physically validates one DEBUG capture-preset backend on final CF5C311C:
S9->Tablet three fresh Sessions with60s ordinary MIC consumers and physical
997Hz signal, plus VOICE_COMMUNICATION/CAMCORDER. UID rules NONE; natural Android
selection and normal/helper-loss restoration pass. Release destination and
quality remain PARTIAL: startup/no-consumer IPC backpressure and MIC1 late DEBUG
underrun need causal closure; MIC2/3 DEBUG counters0 do not erase that result.
Forced priority is NOT TESTED; getter authorization does not prove setter or
crash-safe role rollback. A41 permission/S7 adapter boundaries are capability
results, not separate architectures. Gates990 JVM/0/0/0 and native22/22 each.
No device-specific path or framework/vendor/HAL patch. The
latest [consumer-transition continuation](phase6_5/MicrophoneConsumerTransitionQuality.md)
keeps global-input quality PARTIAL: Tablet returned; provisional40ms local IPC
expiration and bounded stale-head admission improve two complete108s ordinary
MIC Sessions, but the third fails during renewed platform radio/path loss.
Bluetooth SIGKILL, wlan0 disassociation and Android disabling Debug wireless are
observed; exact initiating cause remains INCONCLUSIVE. No primary substitution,
priority/portability promotion or recovery/migration. Full gates1004 JVM/161
suites and native22/22 each pass;80/70ms DEBUG playout remains unchanged.
Current [residual microphone quality evidence](phase6_5/MicrophoneReceiverSchedulingQuality.md):
peer link restored, bounded one-burst admission corrects whole-block DEBUG
rejection without ring growth. Final81F10D24 first cycle remains quality FAIL
on residual source/track starvation;2/3 NOT RUN.1017 JVM/22+22 native pass.
Portability/forced priority/release remain gated; SystemAudio quality remains
a separate future task, no recovery/migration implementation.

Latest [receiver scheduling continuation](phase6_5/MicrophoneReceiverSchedulingQuality.md)
keeps global microphone quality PARTIAL. A microphone-only audio-priority worker
reduces proven local receive starvation; one stable physical diagnostic does
not replace acceptance. Frozen43FB71D6 cycle1 loses peer/Tablet prerequisite,
2/3 NOT RUN.1016 JVM/22+22 native pass. No release promotion, forced priority,
provider recovery or new portability path. SystemAudio quality remains separate.

Latest [source-clock recovery continuation](phase6_5/MicrophoneSourceClockRecovery.md)
corrects a proven pacing-debt defect, but global-input quality remains PARTIAL:
the first counted new108.943s cycle fails steady pressure and teardown;2/3 NOT
RUN. Separate80s stable continuous-reader diagnostic is not close/reopen
acceptance. Later primary media suspension/radio loss disables Debug wireless;
peer ping/TCP subsequently recover automatically. No buffer/codec/socket change,
new provider/OEM path, priority role, recovery/migration or release promotion.
Finish this exact primary gate after manual prerequisite restoration; do not
start the separate SystemAudio quality task from a partial microphone result.
Current [2026-10-03 clock-continuity continuation](phase6_5/MicrophoneConsumerClockContinuity.md)
completes functional3/3 consecutive108s S9 ->Tablet ordinary MIC Sessions on
66A8B330. IPC rejection0 does not close quality: cycle3 has recurring steady
DEBUG and RemoteSubmix AudioTrack underruns. Output continuity and explicit-gap
re-priming reuse80/70ms; fixed40ms IPC freshness remains provisional. A subsequent
diagnostic loses the Tablet radio/Debug wireless prerequisite, and Host health
is withdrawn. A41/S7 actual AudioPolicy registration is permission blocked;
legacy S7 shell context is reachable. Tablet invalid-preset setter/clearer probe
proves permission authorization only, not valid priority or crash-safe rollback.
Gates1007 JVM/161 suites/native22/22 each, no milestone commit or release promotion.
Finish the same primary microphone gate before a separate SystemAudio quality
task. Latest SystemAudio control is technical/lifecycle PASS,69120/789/0 with
2118960 decoded frames, not an equal-window quality comparison.
The preceding historical [quality follow-up](phase6_5/DirectShellGlobalMicrophoneInjectionQuality.md)
remains PARTIAL: consumer-aware discard/idle silence,80ms local IPC age,
outside-lock recording-device lookup and initial warm-clock retention did not
close the first counted111.017s cycle after manual Wireless Debugging restoration.
Real997Hz natural MIC reception and normal physical restoration remain proven;
unexpected queue rejection3120frames/HWM16 prevents a quality token. Opening-flush
and smaller track-threshold experiments were reverted after failed quality tests.
A new local consumer-window admission guard is physically unvalidated; it adds
no buffer or delay and is not a pressure fix. A later bidirectional S9/Tablet
peer LAN failure was [reproduced outside Warpnect and cleared by one Tablet
rejoin](phase6_5/S9TabletPeerLinkRecovery.md); all bidirectional IPv4 TCP/UDP
controls now pass, without a product recovery change. The precise network cause
remains INCONCLUSIVE. Tablet subsequently became unavailable by operator
confirmation; current87EF556C primary acceptance is paused, not replaced by
S22/A41. No new microphone Session counted. Earlier evidence remains preserved.
Fixed80ms LOCAL IPC age is not the accepted80/70ms DEBUG playout reserve; no
adaptive benefit or whole-platform age bound is proven. A separate
failed-helper-replacement publication source fix needs physical confirmation.
Portability alternatives and priority set/clear are deferred until quality PASS.
No release promotion or new completion token. The
future `HOST_AND_REMOTE` user choice and Host display `KEEP_ON`,
`PRIVACY_BLANK`, or true `SCREEN_OFF` policy are not implemented;
screen-policy feasibility should be evaluated before or during RFC-005K.
That dedicated lifecycle/background work must also audit fixed-duration helper,
process and service assumptions for ownership-versus-expiry semantics, including
Host persistence, screen-off and power management. Discovery-specific lifetime
review stays with its appropriate future RFC. RFC-005J/005K are not started by
the bounded owner-channel change.

The [Phase 6.5 rendered-frame observability report](phase6_5/LegacyRenderedFrameObservability.md)
defines the measurement precondition: local decoder and Surface-handoff
durations are measurable, but `OnFrameRendered` is diagnostic only and
cross-device/photon latency is not established by app clocks or callback time.
Qualify external display instrumentation and clock provenance before freezing
physical-display latency baselines.

Optimize:

- CPU usage.
- Memory allocations.
- Zero-copy paths.
- Thread scheduling.
- Buffer management.
- Battery consumption.
- Thermal behavior.

## Phase 8 - Cross-Platform Expansion

Reuse the C++20 core to build:

- Windows client.
- Linux client.
- macOS client.

Only platform-specific capture, rendering, audio, and input layers should differ.

## Phase 9 - Production Readiness

Prepare the project for release:

- Configuration system.
- Logging.
- Error handling.
- Automated testing.
- CI/CD.
- Documentation.
