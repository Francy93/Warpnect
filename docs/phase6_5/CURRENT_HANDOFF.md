# Phase 6.5 Current Handoff

## R1713 Private Code Entry And Local Handle Qualification

[R1713 report](DirectShellR1713PrivateEntry.md): the isolated foreground
keypad passed lifecycle and synthetic delivery on both A41s. It binds
one explicit P1 ticket and keeps the code out of RemoteInput, saved state
and IME. Samsung dialog continuity and split-screen remain unverified.
The distinct unlocked API31 SystemUI test observed
one real submission with no text visible afterwards in the observed
shade; system history and retention are not qualified. See the Samsung
interaction outcome in the report before relying on a foreground flow.
Local Android PCM/Surface handle duplication and release are not
inter-process or production media qualification. **P2_PARTIAL_BLOCKED**;
no emulator, R0b or Release activation.

## R1712 Samsung Carrier And Independent Role Work

[R1712 evidence](DirectShellR1712CarrierAndRoleBoundaries.md): on the
exact A41/API31, the isolated notice was seen unlocked, hidden while
locked, and absent after unlock in both trials. No reply was sent;
retention and platform authentication are unqualified. Prefer explicit
foreground entry for secrets. The unregistered foreground lifecycle,
Wi-Fi resampling, owned TLS stress and typed role drain progress do
not enable production pairing or media. **P2_PARTIAL_BLOCKED**;
emulator/R0b remain suspended.

## R1711 Physical Control, Role Contracts And Private Integration

[R1711 evidence](DirectShellR1711ControlQualification.md): the unchanged
Control v2 DEX passed on the exact A41/API30 and API31, including UID2000,
independent mapping/listener evidence, authenticated frames and actual
teardown. The first API30 binary-pipe failure remains recorded. Input,
PCM and video have typed software boundaries, not production gateways.
The private lock policy and Network observer are unregistered. No writer,
real pairing, Host/Session or Release activation. **P2_PARTIAL_BLOCKED**;
emulator and R0b remain suspended.

## R1710 Numeric Route, Private Setup And Control

[R1710 evidence](DirectShellR1710ControlAndPrivateSetup.md) records the
two Samsung numeric observations, the offline pairing-only route,
isolated P3 delivery, blocked channel and stale reply after process
death on A41/API30 and API31, and the standalone Control artifact. Production
Keystore no-replace remains unresolved. No privileged helper launch,
production pairing, Host/Session or Release activation. **P2_PARTIAL_BLOCKED**;
emulator and R0b remain suspended.

## R1709 Physical Network And Integration Review

[R1709 evidence](DirectShellR1709NetworkAndIntegration.md): the isolated
Network-bound TCP fixture passed once on each A41/API30 and API31, with
verified cleanup. No physical link loss, Samsung numeric tuple or new
pairing was observed. P3 Android components are unregistered; actual Debug
helper closure is not eligible for a P4 DEX. Strict no-replace is unchanged.
Overall **P2_PARTIAL_BLOCKED**; emulator and R0b remain suspended.

## R1708 Physical Owner And Independent Prototypes

[R1708 report](DirectShellR1708Prototypes.md): the sealed r1706 AF25 passed
once on isolated A41/API30, with observed resource drain and cleanup. The
writer intent, numeric route and private setup experiments remain unregistered;
the P4 review JAR pipeline does not package an active helper. No public
AndroidKeyStore no-replace writer is qualified. Overall **P2_PARTIAL_BLOCKED**;
no new real adbd, Host, Session or Release wiring. Earlier LOW stop is preserved.

## R1707 First-Enrollment Transaction And AF25 Stop

[Accepted decisions, implementation and remaining gates](DirectShellR1707FirstEnrollment.md).
D-P1-D-P4 are accepted; the host-testable transaction owns one writer lease.
No Android key writer is declared safe on the evidence available. AF25
stopped before installation on the A41/API30 because `/data` was LOW and
below 2 GiB. Existing fixture results are undisturbed. P3 has an unregistered
ticket carrier and P4 a role/artifact inventory, not production wiring.
Overall **P2_PARTIAL_BLOCKED**. Emulator and R0b stay suspended.

## 2026-11-13 Isolated Android DirectShell Owner

See [owner, elapsed deadlines and next decisions](DirectShellAndroidPairingOwner.md).
R1706 composes the existing synthetic operation and drain handoff with one
unregistered DEBUG Android owner. Resume rechecks elapsed deadlines without
waking the phone; worker guards close the gap before starting new connect
or storage effects. This does not restore consent, qualify real suspend, or
activate production provisioning, Network/NSD, Host, Session or Release.
Overall **P2_PARTIAL_BLOCKED**; emulator/R0b remain suspended.

## 2026-11-13 Physical DirectShell Gates

See [runtime evidence and dependency matrix](DirectShellPhysicalApi30Gates.md).
r1704 AF02/AF08 passed on an isolated physical A41/API30. r1705 adds native
collision, authenticated recovery, stalled TLS/PAKE, cancellation, lease and
drain, and Android component evidence: 69/69 distinct methods across two
artifact cohorts, with the first AF12 failure retained. The separate API31
probe confirms the existing-only AES reader and platform backing on a fresh
test alias, with cleanup verified. Historical compile-only statements below
describe their original checkpoints. None qualifies production pairing or
Release. Keep **P2_PARTIAL_BLOCKED**, with emulator/R0b suspended.

## 2026-11-12 DirectShell Debug Pairing Consolidation

[Hardware receipt and remaining gates](DirectShellDebugPairingR1702.md):
r1702 completed one Debug Host-UI pairing on Galaxy A41/Android 12, with
pre-code NSD resolution, authenticated GUID, real adbd TLS reconnect,
helper HMAC and authenticated shutdown. No Host/Session started. The temporary
APK/association were removed; Debug wireless was restored to Off. The ordinary
Debug changes preserve the path without the A41-specific diagnostic gate.
Hardware evidence applies to r1702, not to the new APK bytes or Release.

At the r1703 consolidation Android synthetic P1/P2 had only compile/software
evidence; see the later runtime record above. At the time the proposed gate was an
exact-API30 physical A41 for the existing AF02 preflight and one AF08; no
production keys, real pairing, Release activation or emulator/R0b resumption.

## 2026-11-03 API30 Fixture Independent Checkpoint Review (r1634)

[Current independent review](DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-independent-software-checkpoint-review-r1634).
Exact six-source5092B52E...06C4348 candidate unchanged from r1633;isolated branch
codex/phase6-5-pairing-android-api30-fixture-checkpoint,parent656fcef6...092cd.
Fresh422/422 focused(18 suites),1606/1606 full(198 suites),40/40 replay;zero method
failures/errors/skips. Build/lint/reproduction/isolation PASS;126/34 lint,no new
semantic findings. Corrected producer61 controls and8 compiled checks PASS.
69 Android methods COMPILED,NOT EXECUTED;CTest NOT EXECUTED. CXX5304 warning retained.
External:C:/wg-pairing-api30-checkpoint-r1634-20261103/IndependentReview.md and
software-summary.json. Final admission7037 checks PASS,one attempt. Source commit:
2981d6626f28a71f1671eafce10aa67ee2a90e5d. Documentation commit identity and clean-tree
postverification are recorded in the external FinalCheckpointReport.md;award
P2_ANDROID_API30_SYNTHETIC_FIXTURE_CHECKPOINT_VERIFIED only with that successful
two-commit postverification. No Android runtime qualification.
Full forced-death BUSY satisfied approved safety contract;strict explicit release
succeeded. P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED remains.34 protected locations,
six with unavailable Git metadata(two existing/four absent),four metadata-only
large research exceptions;no retrospective Git continuity claim.
Next gate requires separate exact-API30 disposable-fixture authorization and must
account for the inherited lazy DEBUG initializer. No devices/ADB/real pairing or
production provisioning authorized here. Overall P2_PARTIAL_BLOCKED;Phase6.5 incomplete.

## 2026-11-03 External Guard Producer Correction And Admission (r1633)

[Current admission and preserved history](DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-guard-producer-correction-and-software-admission-r1633).
Android API30 synthetic composition fixture - implemented/software-tested;
instrumentation COMPILED,NOT EXECUTED. No new Git checkpoint or runtime claim.
Original consumer D58D15F3...F00ABE0 executed once unchanged:6232 checks PASS,exit0.
External:C:/wg-pairing-guard-producer-r1633-20261103/admission/admission.json.

Only external producer changed:explicit typed assertions enforce all eight original
checks,including both join/isAlive and Bob/destroy operands.61 independent controls
PASS (3 accept/58 reject),one real compiled execution8/8 valid names/no nulls.
Producer9197BB67...9EE88039,receipt09AD24B8...9EA8F025;original producer AED77D49,
malformed B444D586 and first admission negative remain immutable. A new input view
supplies the corrected receipt without replacing old evidence or changing consumer.

REUSED r1632,not new runs:1606/198 full JVM,422/18 focused,40 replay,required
builds/ktlint/lints126/34,AAR reproduction,isolation.5651 reuse-provenance checks;
14 recorded class checksums match,all four artifacts unchanged.69 Android methods/
5 suites remain compiled,0 executed;no new Gradle/JVM/replay/AAR run or CTest.
The new reader's `/C:/` path-format negative and bounded correction are preserved;
no hash threshold/predicate relaxation. Raw lint XML reuse limits disclosed in report.

All six source/build/test paths still5092B52E...D606C4348;bridge565352DD unchanged.
Parent656fcef,branch and -resume worktree unchanged. Three factual docs updated only
after admission;nine total dirty paths,index empty,no commits.33 protected known
locations (27 registered,six unavailable-Git roots) preserved with original
metadata-only exceptions;no repair or old registry continuity claim.

Retain all prior checkpoints,Windows failure UNATTRIBUTED and every historical
negative/Release-unit/SDK/lint/hardware/media/R2 limitation. Overall P2_PARTIAL_BLOCKED;
Phase6.5 incomplete. Next only after separate authorization:disposable exactAPI30
runtime preflight/fixture campaign. No device/emulator/ADB/USB/NSD,real adbd,
production provisioning,Host/Session/Release,Root/Shizuku,media,P3-P5 or Phase7 work.

## 2026-11-03 API30 Fixture Test-Only Boundary Correction (r1632)

[Current correction and preserved negative](DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-test-only-api-correction-and-software-acceptance-r1632).
Test-only correction and individual software runs passed;final acceptance BLOCKED.
Instrumentation COMPILED,NOT EXECUTED. One final admission exited1:
C:/wg-pairing-fixture-boundary-r1632-20261103/admission-first-negative.json.
No new formal checkpoint. Parent656fcef,branch and managed -resume root unchanged.

Only r1631 source delta:import and whole-class RestrictTo(TESTS) on the DEBUG Bob
bridge. A supported test-only API restriction,not suppression or moved call.
Package/classloader and authentic libadb behavior unchanged;all consumers are the
existing AndroidTest/testDebug fixtures. Five other candidate sources/build/tests
unchanged. Six source/build/test + three docs uncommitted,index empty,no commit.
New source cohort5092B52E...D606C4348;old D8392409...6DEF51DA negative preserved.

Fresh1606/198 full,422/18 focused JVM,40/40 replay PASS,0 failures/errors/skips;
zero test-count delta from r1631.69 Android methods in5 suites compiled,0 executed,
all identities unchanged. Required builds/ktlint/lints,AAR reproduction PASS.
Unchanged strict semantic lint comparator passes126 Debug/34 Release,no additions;
original127-warning r1631 failure/XML retained. Formerly interrupted132 classpath,
18 observation,8 composition compiled,19 composition DEX,57 handoff,helper and
69 fixture-isolation checks now PASS;9 explicit test-only API checks PASS.
The separate eight-check fixture-compiled producer is NOT qualified:checks[5]/[7]
are null and its PowerShell -and/comma rows fail to enforce the right operands.
The aggregate correctly rejected the receipt. No producer/consumer correction or
gate rerun after failure. Read-only controls/diagnosis retained;no application
defect inferred and no completed software admission claimed.
Pinned AAR BC687AD0...20DB3D0,libadb3.4.0/BC1.84 unchanged. Native guards ran,
CTest did not. Three standalone diagnostic setup failures and two ineffective
CLI control layouts are disclosed separately,not passing acceptance evidence.

Focused forced-death BUSY satisfied approved safety assertions;strict release and
full crash/release inspected COMMITTED_CLEANUP_PENDING. No retries or attribution:
Windows historical failure remains UNATTRIBUTED.33 protected locations observed
(27 registered,six historical missing-Git roots),metadata-only exceptions disclosed.
No repairs,device/emulator/ADB/USB/NSD,real adbd,production keys,Host/Session,
Release onboarding,Root/Shizuku,media,P3-P5 or Phase7 changes. Prior checkpoints and
all historical negatives retained. Immediate next recommendation:separately
authorized external producer correction/negative controls,separate receipt and one
new admission,not relaxed schema. Only later separately authorized disposable
exactAPI30 runtime preflight/campaign. P2_PARTIAL_BLOCKED;Phase6.5 incomplete.

## 2026-11-02 API30 Composition Fixture: Compile-Only, Acceptance Stopped (r1631)

[Source, gates and first-negative report](DirectShellPairingAndroidApi30CompositionFixture.md).
Parent656fcef657ae2aac08c8099ce5b0905378a092cd unchanged. Gate A/B source review
supports an instrumentation-owned controlled-clock fixture through existing ports;
no new P1/storage/lifecycle authority or production D-P1-D-P4 provisioning.
Six source/build/test plus three documentation paths remain uncommitted,index empty.
Active managed root is phase6-5-pairing-android-api30-composition-fixture-resume/Warpnect.
Original partial root is preserved without its missing Git metadata;33 known
protected locations match the Nov2 recovery baseline (27 registered,six unavailable
Git roots; no claim of complete October30 registry continuity).

Real Android filesystem/test AES/native publisher and existing P1/owned protocol/
drain are wired in the fixture,not executed.69 Android methods compiled,0 executed
(24 new AF methods). Four new bridge host tests: final1606/198 full,422/18 focused,
40/40 replay PASS,0 failures/errors/skips. Build/ktlint/lint Gradle tasks and pinned
AAR reproduction PASS. Final cohort D8392409...6DEF51DA; no source edit after its
one declared complete JVM execution. Superseded intermediate cohort and AF15
source-inferred pre-admission injection defect remain archived separately.

Mandatory lint comparison FAIL: new DEBUG VisibleForTests at
DebugSyntheticPairingPeerAuth.java:20,PairingAuthCtx.createBob(password).
Debug127 warnings (parent126),Release34; historical eight removed dependency
advisories remain separate. No suppression, source correction or acceptance rerun
after stop. Subsequent classpath/new fixture compiled/DEX aggregates NOT EXECUTED.
Do not claim all-gates software acceptance or Android validation. External receipts:
C:/wg-pairing-android-api30-fixture-r1631-final-20261102/.

Prior owner-drain/synthetic/P1/libadb/Windows checkpoints retained. No new checkpoint,
commit,push,merge,CTest,Android/emulator/device/ADB/USB/NSD,Host/Session,Release,
real adbd or Phase7 work. Historical Windows failure remains UNATTRIBUTED; retain
CXX5304,Release-unit/lint negatives,hardware/media debts and R2. Next recommendation
only: separately reviewed test-only visibility correction and new software freeze,
then separately authorized exactAPI30 runtime campaign. Overall P2_PARTIAL_BLOCKED.

## 2026-10-30 Owner Drain Checkpoint Resumption (r1630)

[Checkpoint and preserved history](DirectShellPairingDrainHandoff.md#2026-10-30-independent-checkpoint-resumption-r1630).
P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED applies with the final external
committed-blob/cleanliness receipt. Source commit
08c6f1c3034de1545286cdc802e3731eaac10d98 from
eabc12225f34e6a3c26dbce84fbfe6ddb36b3dff contains exactly the two reviewed new
source/test paths. Its direct child contains only this handoff, the drain report
and FutureRoadmap. Full documentation SHA and final proof are external:
C:/wg-pairing-drain-checkpoint-resume-r1630/CheckpointFinalReport.md.

Explicitly authorized external-verifier correction only: old aggregate compared
checks-array===132; corrected version validates PASS,count===132,array type and
array.length===count. Producer/evidence unchanged.15 schema controls PASS;
one corrected admission exit0,4966 checks PASS. Original admit.cjs, exit1,
AdmissionFirstNegative.txt and r1629 stopped receipt remain unchanged. The old
failure is not relabeled PASS. No application/test/build or contract correction.

REUSED r1629 executions, not new runs:1602/197 full,418/17 focused,40/40 replay,
0 failures/errors/skips; builds/ktlint/lints,AAR reproduction and existing
source/DEX/manifest/storage/native guards PASS.8230 provenance/schema assertions
verified source/XML/commands/artifacts intact. LF cohort EDC7150F...9E88A79;
all31 other worktrees,371 sealed r1629 files and2455 earlier sealed files
preserved. No full/focused/replay/Gradle/reproduction rerun; no push or merge.

45 Android methods remain compiled,0 executed; CTest not run. DEBUG remains
unregistered and Release setup unavailable. Host-positive directory sync modeled;
real Android storage/native/Keystore/clock/owner/endpoint qualification remains.
H09 exact-cutoff runtime case versus source-proven strictly-past/MAX cases is
clarified externally; owner shutdown can retain fail-closed inert ownership.
Retain r1628 verifier negative,r1629 schema negative,CXX5304,Windows failure
UNATTRIBUTED,strict lint/Release-unit limitations,all hardware/media debts and R2.

Retain prior P1/synthetic/libadb/Windows software checkpoints and
P2_OWNER_DRAIN_HANDOFF_IMPLEMENTED_SOFTWARE_TESTED. Overall P2_PARTIAL_BLOCKED;
Phase6.5 incomplete. Next recommendation only: separately authorized exact-API30
synthetic fixture with existing FS/test AES/native publisher/test RSA/P1+drain,
after clock/timer/lifecycle review. No production D-P1-D-P4, real adbd, Android/
emulator/device/ADB, Host/Session, Release, P3-P5 or Phase7 execution now.

## 2026-10-30 Owner-Side Drain Handoff (r1628)

[Implementation/evidence](DirectShellPairingDrainHandoff.md):
P2_OWNER_DRAIN_HANDOFF_IMPLEMENTED_SOFTWARE_TESTED,host-only DEBUG foundation.
User attachment b0803204 explicitly accepted D-O1/D-O2; the earlier r1627 review
and draft remain untouched in their protected worktree. No D-P1-D-P4 approval.
New branch codex/phase6-5-pairing-owner-drain-handoff from
eabc12225f34e6a3c26dbce84fbfe6ddb36b3dff; source parent3547a51c.

One lazy,tracked observer belongs to the same P1 owner,not attempt D. It calls
the real delegate.awaitDrain outside P1/locks and forwards only the delegate's
actual receipt. Exact code/work/ticket and original budgets unchanged. One
close-time <=3s window; delay consumes it,no retry/rearm. Held worker/lease
preserves ownership; independent observer shutdown uses a real join. No core,
storage,transport,libadb,manifest,SDK,provider,media or Release source change.
Two new files and three docs uncommitted,index empty,no commit/push/merge.

Fresh18 H01-H16 methods;418/17 focused;one1602/197 unfiltered noninstrumented
full JVM;40/40 media PASS,0 failures/errors/skips. Builds/ktlint/lints,AAR
reproduction,619 closure/132 classpath,existing source/compiled/DEX/native guards
PASS. New53-check guard PASS after a documented verifier-only correction:
`.schedule` falsely matched `.scheduler`; first negative retained,separate
corrected receipt,no repeated full JVM or source/test weakening. Debug/Release
lint126/34,no new semantic warning; strict historical differences retained.

Real P1/encrypted host storage/owned socket/authentic TLS-PAKE/R/P/S/automatic
physical D/F exercised. Positive directory S modeled,not Android durability.
45 Android methods compiled,0 executed;no CTest,emulator/device/ADB/USB/NSD,
Keystore/native execution,Host/Session activation or production registration.
Fresh SDK XML diagnostic CXX5304 preserved without toolchain workaround.
30 other worktrees and2072 sealed receipts preserved;Phase7 R2 unchanged.
Evidence C:/wg-pairing-drain-handoff-r1628/.

Overall P2_PARTIAL_BLOCKED; retain verified synthetic/P1/libadb checkpoints and
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED. Next: independent review of this exact
two-source/three-document candidate and separately authorized local checkpoint.
No automatic Android fixture/owner/network/key wiring,real adbd,Release,P3-P5
or Phase7. Phase6.5 incomplete; G1-G5/media debt unchanged.

## 2026-10-29 Synthetic Checkpoint Review (r1626)

[Current review](DirectShellPairingSyntheticComposition.md#2026-10-29-independent-review-and-checkpoint-r1626):
source/security review PASS for exact five-source cohort2E1EFC48...3069ED06.
Isolated codex/phase6-5-pairing-p2-synthetic-checkpoint from688f13bd; no source/test
correction. Fresh400/16 focused,1584/196 single unfiltered noninstrumented full
JVM and40/40 replay PASS. AAR reproduced twice plus selected identity verified.
Required builds,lints,reproduction,619 class-closure/132 classpath,source/DEX/
manifest/P1/storage/native ABI guards all PASS.45 Android methods compile,0
execute;no CTest. Source checkpoint3547a51ca2cf75af7e4c1259f6d428f44185aaef
contains exactly five paths; its direct three-document child and clean/blob/
preservation proofs are recorded externally after creation.271 admission checks
PASS. P2_SYNTHETIC_COMPOSITION_CHECKPOINT_VERIFIED applies only with the final
checkpoint-final.json receipt; no amend,self-referential SHA,push or merge.
Evidence C:/wg-pairing-synthetic-checkpoint-r1626/.

28 other worktrees protected;1682 sealed prior receipts rehashed. Real owned
worker/socket/lease/channel D and exact-ticket protocol binding reviewed; positive
directory S remains modeled and Android automatic D dispatch absent. No new
daemon trust or Host/Session authority. Overall P2_PARTIAL_BLOCKED; Phase6.5
incomplete. Historical Windows refusal stays UNATTRIBUTED. Next only after a
verified checkpoint: separately authorized narrow Android boundary/fixture review,
not real pairing,production key provisioning,Release or P3-P5 implementation.
One Debug class differs only by verified constant8 Compose stability initialization;
all96 Release pairing classes match r1625. Native instruction identity is not
claimed from unchanged sources; actual ZIP/ELF differences and ABI checks recorded.
Strict historical lint and Release-unit compilation limitations remain unchanged.

## 2026-10-29 P2 Synthetic Composition (r1625)

[Current implementation/evidence](DirectShellPairingSyntheticComposition.md):
P2_SYNTHETIC_COMPOSITION_IMPLEMENTED_SOFTWARE_TESTED,localhost/Windows scope.
Parent/HEAD688f13bd028265c2c75109c2e6949be85daf2bd5; new isolated branch
codex/phase6-5-pairing-p2-synthetic-composition-integration. Five source/test
paths and three docs remain uncommitted,index empty,no new commits/push/merge.
Final source cohort2E1EFC4800A1E162061CC120C4D3DF685271A2FFAF02075EC54AD2683069ED06.

Real P1,D1-D7 observed prepared,encrypted recovery,actual pinned TLS/PAKE,
exact ticket/work/socket/endpoint/certificate binding,normal R and real host
no-replace P are exercised together. Positive directory S is explicitly modeled.
One operation worker,existing transport workers and actual lease/channel/code
release are observed before D by an explicit outside-monitor owner join; this
is not automatic Android drain dispatch. F retains verified/POSSIBLE,never
confirmed daemon trust or Host/Session authority. Unknown P/S or live worker/
lease stays fenced. New recoveryAdmission is read-only; awaitWork consumes the
original remaining budget. All original P1/storage/transport methods unchanged.

Fresh1584/196 full,400/16 focused,31 new composition methods coveringC01-C26 plus
five targeted discriminators,and40/40 offline replay PASS,zero failures/errors/
skips. One unfiltered noninstrumented full run only. Builds,ktlint/lints,AAR
reproduction,619 closure,132 classpath,8+19 new compiled/DEX and applicable
P1/storage/publisher/native guards PASS.126/34 lint warnings,no new finding;
strict historical differences/Release-unit compile limitation remain open.
45 Android methods compiled,0 executed; no CTest or new Android/hardware claim.

27 other worktrees and1270 sealed receipts preserved,Phase7 R2 unchanged.
Retain P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED,
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED,
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED,
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED and
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED. No historical runtime failure is fixed
or reattributed by these synthetic tests. Earlier sections retain historical scope.

Next recommendation only: independent review and separately authorized local
checkpoint of this exact uncommitted candidate. No automatic real adbd/device,
production key/transaction,NSD endpoint,OEM/API or Release onboarding step.
Overall P2_PARTIAL_BLOCKED; G1-G5 hardware PARTIAL,media debts OPEN,Phase6.5
incomplete. No Root/Shizuku,Host/Session activation,P3-P5 or Phase7 work.

## 2026-10-29 P1 Observation/Drain Local Checkpoint

[Reviewed checkpoint](DirectShellPairingProtocolObservation.md):
P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED,local software scope only. Source
commit5c951ecda3693981918ad8c128c02de76d679f9f has exactly four paths at the
unchanged DBEFC3EF...92120F LF cohort; the direct documentation child has exactly
three. Full documentation SHA,blob/ancestry checks and clean final state are in
C:/wg-pairing-observation-checkpoint-r1624/checkpoint-committed.json and
CheckpointFinalReport.md; no self-referential SHA or amend. Parentbd8a4ed;
branch codex/phase6-5-pairing-protocol-observation-checkpoint.

D1-D7 fresh source review PASS; O01-O20 coverage re-reviewed. No source/test fix
or accepted-RFC rewrite. Exact-ticket,verified/POSSIBLE,physical D versus
semantic eligibility,retained-attempt isolation and legacy AUTHENTICATED behavior
remain distinct. P1 trusts modeled resource ports here,not observed OS drain.
No cryptographic/daemon-trust authority arises from an enum or local F result.
Original120s/60s/3s,one attempt,R/P/S/D/F and no retry remain.

Fresh369/15 focused,one1553/195 full JVM and40 offline replay PASS,0 failures/
errors/skips. Required builds,ktlint/lints,reproduction,619 closure,132 API/
classpath,18 observation and applicable P1/storage/native guards PASS.126/34
lint warnings,no added semantic finding; historical strict negatives retained.
45 Android methods compiled,0 executed; no CTest,emulator/device/ADB/USB/NSD.
Native .text differences are explicitly recorded,not inferred identical from
source. AndroidTest/helper match r1623; APK differences are native entries only.

26 protected other worktrees and927 sealed receipts preserved; the original
dirty P1 candidate remains untouched. Both fresh crash/release cases returned
committed-cleanup pending; live owner BUSY remains fail-closed. Historical
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED,Release-unit compile limitation and earlier
wrapper/formatter/library/USB/SDK negatives remain,not fixed or relabeled.

P2_SYNTHETIC_COMPOSITION_NOT_STARTED. Next is separately authorized C01-C26 from
this checkpoint with actual joint resource observation and explicitly modeled
host directory sync,not automatic execution. Overall P2_PARTIAL_BLOCKED;
adbd/Android transport/key/storage/endpoint/OEM/Release gates remain open.
G1-G5 hardware PARTIAL,media debt OPEN,Phase7 R2 unchanged,Phase6.5 incomplete.
No push,merge,Host/Release activation or unrelated source change. Older sections
below retain historical scope and are superseded only for checkpoint state.

## 2026-10-29 P1 Protocol Observation And Independent Drain

[Implementation report](DirectShellPairingProtocolObservation.md):
P1_ATTESTATION_DRAIN_IMPLEMENTED_SOFTWARE_TESTED after explicit approval of
the reviewed D1-D7 amendment and named implementation prompt. Exact isolated
parentbd8a4edc5928715317eaed46cdd96b48da5fc9f5,branch
codex/phase6-5-pairing-protocol-observation. Four source/test paths and three
documents remain uncommitted/index empty; no new checkpoint commit or merge.

Optional exact-ticket protocol observations preserve PEER_INFO_VERIFIED and
POSSIBLE independently. Legacy prepared/AUTHENTICATED drain remains unchanged;
mixed paths fail closed. Known physical D plus semantic uncertainty reconciles
without inventing unknown P or eligible F. Foreign/unproven D remains fenced.
Original120s/60s/3s budgets,R/P/S/D/F,single owner and generation rules remain.

Fresh1553/1553 JVM in195 suites,369/369 focused in15 suites,40/40 replay,
builds/lint/reproduction and applicable source/compiled/DEX/manifest/native guards
PASS.41 new model methods plus2 exact diagnostic methods,all1510 inherited
methods unchanged.45 Android methods compiled,0 executed; CTest not run because
native inputs unchanged. Logs retain the initial formatter negative and
PowerShell5 wrapper error separately from its369 passing XML methods. Initial
crash test BUSY is approved fail-closed evidence,not historical root-cause proof.

25 other worktrees,earlier reviews/evidence/checkpoints and Phase7 R2 preserved.
No storage/transport/libadb/provider/Session/media/Release activation changes.
Overall P2_PARTIAL_BLOCKED. Actual C01-C26 composition NOT IMPLEMENTED; Android
transport/key/storage composition,adbd/endpoint/OEM/Release gates remain open.
Next: review/checkpoint this bounded P1 amendment,then a separately authorized
synthetic composition increment. Do not repeat completed RFC/provider work,
real pairing or hardware tests automatically. Historical notes below are retained.

## 2026-10-28 Patched libadb And Lease Contract Checkpoint

[Checkpoint report](DirectShellPairingPatchedLibadbIntegration.md#2026-10-28-amended-candidate-local-checkpoint):
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED for the isolated Windows software checkpoint.
Source/test commit9576fdca42b9c0b709ddd2af51464f3464324fd9 contains exactly19 paths;
the direct documentation child contains exactly seven. Its full SHA and clean
post-commit blob checks are in C:/wg-pairing-checkpoint-r1620/checkpoint-committed.json
and CheckpointFinalReport.md; no self-referential commit hash/amend is required.
Branch codex/phase6-5-pairing-patched-libadb-checkpoint, parentbe6beb9453b09f44340233ffbd3eca2577ef1c97.
Combined19-source LF cohort669EDB51625CAF4CBCFF67831819D0BBA9F765A6BFFF11B307F982CD9B8A2041.

Fresh1510/1510 full JVM/193 suites,326/326 focused/13 suites,40 replay PASS,
zero test failures/errors/skips. One unfiltered noninstrumented full run only.
Required Debug/AndroidTest/helper/Release builds, ktlint/lint, AAR reproduction,
619 class/DEX and132 classpath/API guards PASS. P1/storage/native checks PASS;
45 Android methods freshly compiled,0 executed. Approved semantic lint comparison
finds0 new findings; strict historical warning equality is not called PASS.
Only approved host tests differ from the frozen production implementation.

Forced death and explicit release both returned committed-cleanup pending;
live-owner BUSY was strictly fail-closed. P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED
remains, not a fixed OS issue. P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED
and P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED retain host scope.
23 other protected worktrees, original frozen/research/diagnostic evidence and
Phase7 R2 preserved. No push/merge/device/ADB/USB/NSD/Host/Release activation.

Synthetic P1/storage/transport composition remains NOT STARTED. The next smallest
task is its separate bounded authorization/review, not automatic execution or
real daemon pairing. Overall P2_PARTIAL_BLOCKED, Release unavailable, Android
transport/OEM qualification pending, G1-G5 hardware PARTIAL and media debts OPEN.
No Phase6.5 completion. Older sections below are historical, superseded only for
the local checkpoint gate, not their failure-attribution or qualification scope.

## 2026-10-28 Approved Windows Lease Test Contract Successor

[Current test-only receipt](DirectShellWindowsLeaseTestContractImplementation.md):
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED on the Windows host.
The user explicitly approved separating forced-death safety from strict
release-qualified recovery. New source delta is only the existing process test
and its Java child; production storage, P1, patched libadb and media are unchanged.

Fresh211 focused tests PASS; one noninstrumented unfiltered full Debug JVM run:
1510/1510 PASS,193 suites,0 failures/errors/skips (+3 methods). Ktlint, compiled
test-only/source-set isolation and1019 integrity checks PASS. Forced death and
acknowledged release both returned COMMITTED_CLEANUP_PENDING; a genuine live
published owner returned fail-closed BUSY. No post-crash BUSY was observed.
Directory sync is modeled; no new Android or power-loss qualification.

P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED remains. The old immediate post-crash
availability assertion is superseded by explicit approval, NOT root-caused or
fixed; historical r1615 FAIL/r1616/r1617/r1618 evidence remains preserved.
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED retains its previous scope.
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED is NOT YET EARNED; synthetic composition
NOT STARTED. No checkpoint or composition was resumed here.

Branch codex/phase6-5-pairing-windows-lease-test-contract, parentbe6beb9453b09f44340233ffbd3eca2577ef1c97.
Frozen17+6 source preserved; amended19-source cohort and seven documents remain
uncommitted/index empty.22 other worktrees, original research and Phase7 R2
preserved. No device/ADB-server/USB/NSD operation or Release activation.
Next is review of this test-contract delta; any later checkpoint must bind its
new source manifest and separately verify outstanding gates. Overall
P2_PARTIAL_BLOCKED, Release unavailable, G1-G5 hardware PARTIAL, media debts OPEN,
Phase6.5 incomplete. Older sections below retain historical scope, not authority
to skip the failed checkpoint or resume composition.

## 2026-10-28 Controlled Patched libadb Software Increment

[Current implementation receipt](DirectShellPairingPatchedLibadbIntegration.md):
P2_LIBADB_PATCH_PACKAGING_VERIFIED and
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED, Windows/JDK21 localhost only.
The user explicitly approved the bounded DEBUG class substitution. libadb3.4.0,
its module/transitives and43 unrelated class bytes remain; one context closure
is patched. The actual selected AAR equals two independent reproductions; Debug
DEX definitions are unique, Release contains neither libadb/BC nor the adapter.

P1 adds only immutable original-deadline metadata and optional dispatch. No new
60s budget, consent/publication authority, coordinator or Host registration.
1507 full JVM/193 suites,323 focused/13 suites (35 new methods),40 replay and all
required builds/ktlint/lint PASS. Strict historical lint equality negatives remain
documented: eight absent dependency advisories and two moved existing warnings;
semantic comparison finds0 new issues.45 Android methods compile,0 execute.
Real blocked TLS writes and all Android transport/platform qualification remain
unverified; synthetic protocol completion is not real Android daemon trust.

Branch codex/phase6-5-pairing-libadb-owned-socket-integration, HEAD
be6beb9453b09f44340233ffbd3eca2577ef1c97. Exact17-source LF cohort
1873B582984F7DCD6F2BB723E71FA4E4B35161A253CDDA2B19EE46D07105C334,
plus six documents, uncommitted/index empty.19 other worktrees and Phase7 R2
preserved; no device/server/USB/NSD/pairing/Host operation. The next bounded task
is review/checkpoint, then separately approved synthetic joint storage/transport
composition, not automatic real pairing or P3-P5. Overall P2_PARTIAL_BLOCKED;
Release unavailable, G1-G5 hardware PARTIAL, media debts OPEN, Phase6.5 not complete.

## 2026-10-28 Transport Checkpoint And Protocol Adapter Gate

[Current protocol feasibility report](DirectShellPairingProtocolAdapter.md):
P2_TRANSPORT_CANCELLATION_CHECKPOINT_VERIFIED. Source/tests are locally committed
at c5577c776300d99d2b69a00b99584295b0f06184, documentation at
be6beb9453b09f44340233ffbd3eca2577ef1c97; transport worktree clean. Fresh full
1472/1472 JVM, focused288/288 (33 transport/96 P1),40 replay and scoped compiled
isolation PASS; prior build/lint receipts retain identical-source historical scope.

P2_ADB_PROTOCOL_ADAPTER_DESIGN_REVIEW_REQUIRED on isolated branch
codex/phase6-5-pairing-protocol-adapter. No application code changed. Original
pinned context has no socket injection or typed peer result; private protocol
stages cannot be transparently reused. A narrow additive source patch is feasible
in principle, but real APK closure needs an explicitly reviewed DEBUG-only
artifact/class substitution, not a host overlay or copied protocol. New17 API
and3 package checks include a preserved expected missing-API compile negative.
An additive owner deadline handoff is proposed, not implemented; no fresh60s
budget may be created. Do not treat old synthetic PAKE success as adbd validation.

Next decision is the exact dependency-packaging approval in that report, not
real pairing, automatic Host activation or P3-P5. Overall P2_PARTIAL_BLOCKED,
Release setup unavailable, Android transport unqualified.18 protected worktrees,
original research, historical failures and Phase7 R2 preserved. Historical
sections below are superseded only within their exact scope, not erased.

## 2026-10-28 Permitted Transport Continuation

The user's explicit reassessment reopened the bounded localhost task. The prior
assistance refusal was overly broad, not an architecture/Android defect. Its
report and four partial files were preserved before edits; historical sections
below do not supersede this [current receipt](DirectShellPairingTransportCancellation.md).

P2_TRANSPORT_CANCELLATION_IMPLEMENTED_SOFTWARE_TESTED applies only to the inert
DEBUG/host-loopback operation-owned component. Fresh33 transport tests,1472 full
JVM/191 suites,288 focused/11 suites,40 replay, required Debug/AndroidTest/helper/
Release builds, ktlint/lint and isolation PASS. Tests prove actual owned-socket
close at TLS/PAKE-read/raw-write boundaries, independent physical drain and
fail-closed outcomes. Stalled connect is injected, not native-connect qualified.
The synthetic TLS/PAKE fixture grants no Android trust or credential authority.

Source parent remainsffe1d1bc38ce88e450fb9fc6becb42a71ab4653b. Five app/build plus
four documentation paths remain uncommitted/unstaged on the isolated transport
branch. The only build delta exposes existing BCTLS1.84 to testDebug compilation.
P1/main/storage/native/media/RFC/SDK/provider/Release behavior are unchanged.
The manager is not replaced or wired to the new component. A future adapter
still needs an authoritative P1 deadline handoff, qualified protocol/identity
binding and Android transport/clock validation; no new60s budget is permitted.

17 protected worktrees preserved. No ADB server/device/NSD/USB/pairing activity
or APK installation. Host metadata FAIL is retained for identical-content SDK
cache mtime only. Existing negatives and preexisting Release-unit compile FAIL
remain historical, not resolved. Overall P2_PARTIAL_BLOCKED, Release unavailable,
G1-G5 hardware PARTIAL, media debts OPEN, Phase7 R2 unchanged. Next is review/
authorized checkpoint and a separately scoped protocol adapter review, not
automatic real pairing or P3-P5. Phase6.5 is not complete.

## 2026-10-28 Multiprocess Checkpoint And Transport Stop

The [new receipt](DirectShellPairingTransportCancellation.md) verifies exactly
two authorized local commits:5f1fe26546e969f455fe8297993c6fcef2d26533 (six DEBUG/test
paths) andffe1d1bc38ce88e450fb9fc6becb42a71ab4653b (five documents).
P2_MULTIPROCESS_STORAGE_CHECKPOINT_VERIFIED; that worktree is clean. Its prior
1439/255 JVM,45 Android and40 replay results are historical, not fresh executions.

Current isolated transport branch remains atffe1d1b with four incomplete,
unverified source/test files, uncommitted and unstaged. Task B was stopped at an
assistance safety boundary, NOT a demonstrated architectural defect. No new
transport tests executed or Android/Release compatibility demonstrated. See the
receipt for the exact preparation failures and stopped-build scope. Do not use
the partial branch as a validated baseline or wire it into Host.

17 protected worktrees, original research and Phase7 R2 preserved. No ADB server,
device access, USB enumeration or pairing in this task. Overall P2_PARTIAL_BLOCKED;
Release setup unavailable. Continue only from the clean checkpoint under an
independent review; no implementation follow-up is automatically authorized.
Historical sections below retain their original scopes and negative evidence.

## 2026-10-28 Multiprocess Storage Qualification Successor

Task A is P2_NATIVE_PUBLICATION_CHECKPOINT_VERIFIED: local source
4378f06fc8bf7c91ee30a670aadc4a023cf9d7f6 and documentation
5d262e49ff678b6f1d12002edb625f6a4dc166f2; publisher worktree clean. The user-approved
helper SHA transcription correction changed only the expected reference, not the
artifact; original negative evidence is preserved.

Current isolated branch codex/phase6-5-pairing-multiprocess-storage-validation at
5d262e4 contains six DEBUG/test and five documentation paths, uncommitted and
unstaged. [Exact receipt](DirectShellPairingMultiprocessStorageValidation.md):
P2_ANDROID_MULTIPROCESS_STORAGE_VALIDATED_ON_EXACT_FIXTURE,45/45 actual API30 AOSP
x86_64 methods (11 new,34 existing),1439 full/255 focused JVM,40 replay and required
build/lint/isolation gates PASS. Same UID10132/domain, independent PIDs, stable
lock identity, one concurrent winner, retained records/reconciliation and real
drain/process-death behavior verified. P1/main/native/media remain unchanged.

Fixture corrections and their FAILs are retained. Initial private ADB preflight
enumerated USB transports despite its one-device filter; no phone-targeted
command/install/pairing followed. The server was stopped and restarted with USB,
emulator auto-scan and mDNS disabled; only the owned emulator was then accepted.
No claim of literal zero physical transport contact is made. Final45 directories,
37 aliases, packages/processes/emulator/server/vendor keys cleaned;16 protected
worktrees preserved. Strict host timestamp FAIL is limited to identical-content
SDK .knownPackages cache mtime, not a configuration/key change.

Overall P2_PARTIAL_BLOCKED, Release unavailable. ARM/OEM/power-loss, real transport/
NSD/endpoint binding/physical cancellation and production key lifecycle remain
open. Next smallest task is review and separately authorized checkpoint of this
fixture, not automatic real pairing or P3-P5. G1-G5 hardware stays PARTIAL; media
debts and Phase7 R2 remain unchanged. Historical sections below retain their own
source/evidence scopes and do not override this successor.

## 2026-10-27 Native Credential Publication Integration

Current isolated branch `codex/phase6-5-pairing-native-publication-integration`,
unchanged HEAD ce246624,14 source/test/build paths uncommitted. The
[integration receipt](DirectShellPairingNativePublicationIntegration.md) records
P2_NATIVE_PUBLICATION_IMPLEMENTED_SOFTWARE_TESTED and
P2_NATIVE_PUBLICATION_ANDROID_FIXTURE_VALIDATED:1435 full/251 focused JVM,
40 replay, required build/lint/isolation gates,34/34 actual API30 AOSP x86_64
storage/lifecycle methods PASS. Native no-replace publication consumes pending;
P/S/D/F remain separate, attempt knowledge true/false/unknown, no Android link
fallback. Same normal UID/untrusted_app domain; encrypted records and existing
P1 reservations/receipts tested, no real PAKE or Host activation.
The original15 PASS/2 FAIL/9 blocked receipt remains historical; both failed
method identities now pass on a new frozen APK, not retroactively on the old one.
Physical/OEM/ARM runtime, independent-process Android lease and power-loss
qualification remain pending. Fixture directories/31 test aliases/packages/
emulator/server/vendor keys cleaned;15 protected worktrees preserved.
Overall P2_PARTIAL_BLOCKED; Release setup unavailable. No transport/NSD/key
provisioning/P3-P5, media, Root/Shizuku or Phase7 implementation. Next requires
review/checkpoint and a separately approved same-UID process-exclusion fixture,
not automatic real pairing. Historical sections below retain their own scopes.

## 2026-10-27 Android Storage Successor

Current isolated branch `codex/phase6-5-pairing-android-storage`, HEAD
`856bca744cc688df22e35b1de6b1303100ea66da`; P1 source checkpoint7f3e39f is unchanged.
The [Android adapter receipt](DirectShellPairingAndroidStorageAdapter.md) supersedes
older next-task wording below, not historical acceptance. Seven reviewed P2
storage/test paths were selectively copied from preserved r1604; no whole dirty
branch was integrated. Android public-OS private storage, directory fsync and
read-only non-exportable AES256 key-handle adapters are implemented but unwired.
Fresh199 focused/1383 full JVM and40 replay tests pass; final build/isolation
closure is in that receipt.26 new instrumentation methods compile, NOT executed.
Classification P2_ANDROID_STORAGE_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED;
P2_ANDROID_STORAGE_PLATFORM_VALIDATION_PENDING. Required Debug/AndroidTest/helper/
Release builds, ktlint and both lint gates pass with no new lint warnings.
API26 descriptor operations fail closed (public CLOEXEC flag requires27).
Android fsync/Keystore/exclusion runtime validation and production key provisioning
remain pending. Overall P2_PARTIAL_BLOCKED; Release setup stays unavailable.
No device, emulator, real PAKE, NSD, trust change, P3-P5, commit, push or merge.
Next bounded task is separately authorized synthetic-fixture platform qualification,
not real pairing or Release onboarding. All historical media/Phase7 scopes remain.

2026-10-26. Navigation/status for this isolated source branch, not an RFC amendment.

## 2026-10-27 Reservation Model Increment

Current branch `codex/phase6-5-pairing-commit-reservation`, isolated from verified
P1 documentation checkpoint `73c0b6e49aad4c55df405f96f9f8dbef31a7370d` and source
checkpoint `1271765cb3e1eebcf72908142663864abece8796`.
The named implementation prompt was explicitly authorized by the user. Only
the pure unwired P1 contract/coordinator and tests are amended; final verification is
recorded in [the current model receipt](DirectShellPairingReservationModel.md).
The read-only review/addendum stays unchanged in its separate review worktree.
No automatic commit, backend integration, Release activation or device operation.
Model only: IMPLEMENTED/SOFTWARE TESTED. Final96 focused and1280 full JVM tests
PASS, +47 over frozen P1;40 replay,21 existing isolation,34 model guards,6 native
guards, required builds, ktlint/lint and preservation PASS. No hardware result.

P2 remains PARTIAL_BLOCKED in its own preserved dirty worktree. Its1264 JVM and
library/storage results are NOT this branch's baseline: this branch starts from
P1's1233/49 cohort. Actual storage/NSD/Keystore/physical cancellation qualification
is pending. The preexisting extra Release-unit compile FAIL (180 diagnostics in
11 unchanged tests, reproduced on clean parent) stays FAIL, not a new regression.
The unavailable Release adapter and existing DEBUG setup/planner/media remain
unchanged. G1-G5 hardware stays PARTIAL; historical media debts and Phase7 R2 stay
open/proposed in their exact scopes. No P3-P5 or follow-on is executed.

The remaining sections retain the parent P1 handoff as historical provenance;
their branch/next-task wording does not supersede this increment.

## Current Source And Local Checkpoints

- Current branch `codex/phase6-5-pairing-enrollment-owner`, isolated from reviewed
  parent `faa9f0f695c56ad4fb8d0f729bae0cb1d623c80c`. P1 source/test checkpoint:
  `1271765cb3e1eebcf72908142663864abece8796`; separate documentation checkpoint
  contains this handoff and [verification receipt](DirectShellPairingP1Checkpoint.md).
  No push or merge. Parent branch
  `codex/phase6-5-directshell-pairing-setup` remains clean/preserved.
- `f7dd2cf131aa7952b67ee09e9594a3e2a4aefe9a`: exact13-path DEBUG Host pairing
  setup implementation/tests. No native/media/planner/protocol change.
- `2a6528da80dc9f354055b021c07d4594e9654e64`: software acceptance report/index.
- [Manifest, artifact identities and scope](DirectShellHostPairingSetup.md).
  Full1184 JVM,40 offline,21 isolation,6 native guards/build/lint receipts retained;
  fresh consolidation55 focused JVM tests (34 UI +21 existing pairing contracts)
  and21 isolation pass. No hardware test.
- Prior artifacts were built from656c378 plus this identical source cohort,
  not post-commit rebuilds. Freeze fresh artifacts before future hardware work.

## P1 Enrollment Owner And Remaining Architecture

Read [Release pairing architecture](DirectShellReleasePairingArchitecture.md).
The Release plan remains DESIGNED/PROPOSED, not accepted Release capability.
The [bounded prompt](DirectShellReleasePairingImplementationPrompt.md) was
executed for the pure unwired P1 only. [P1 receipt](DirectShellPairingEnrollmentOwner.md)
records the two internal model/contract files, one deterministic test file,
consent120s/work60s/cleanup3s, publication/cancel ordering and physical-drain
backpressure. Full1233 JVM (49 additions),49 focused and40 offline replay tests
pass; Debug/AndroidTest/helper/Release builds, ktlint/lint,21 existing isolation,
17 P1 guards and6 native symbol guards pass. Debug lint0 errors/134 unchanged
warnings. P1 only is IMPLEMENTED/SOFTWARE TESTED, not hardware validated; no
device access. A three-test checked-exception negative replay and minimal
correction in the new model are preserved, not a baseline hardware regression.
No Android/network/backend/store implementation, UI wiring, dependency/manifest
change, runtime registration or Release setup action is supplied. P2-P5 are not
started; real cancellation/transaction/identity gates require separate approval.

Why not promote the DEBUG Activity: pairing service concurrency/cancellation is
not qualified; cached libadb3.4.0 pair does not receive the manager timeout or
expose its handshake socket through manager.close. Credentials are already
AES-GCM encrypted but need atomic/restore/migration review. A Release DirectShell
runtime/helper/gateway registration bundle is ALSO missing; pairing alone does
not make a COMPLETE Host. Keep Release setup adapter unavailable, no new deps.

Static RFC-006I matrix/planner/complete-plan freeze, optional Shizuku composition
and RFC-006J Steps1/2/cache already exist. Do not restart those RFCs. Production
enrollment needs the proposed RFC-006J security/lifecycle addendum reviewed before
real backend/Release activation; no new RFC identifier, automatic Host start,
provider recovery/migration or Root/Shizuku dependency is introduced.

## Preserved Acceptance And Debt

G1_G5_SOFTWARE_BASELINE_READY_FOR_INTEGRATION is source scope only;
G1_G5_TARGETED_HARDWARE_REGRESSION_PARTIAL remains unchanged. Audio delivery,
AAudio wakeup, dispatch-to-decode, microphone DEBUG admission/timestamp, Video
HOME/REOPEN and EADDRINUSE remain OPEN. Video's bounded successor remains
VIDEO_FIRST_FULL_NOT_REPRODUCED; observer142e467 is not imported/trigger-validated.
Exact historical microphone/r835 and SystemAudio quality campaigns remain
their own scopes, not fresh qualification of this checkpoint.

A41/S9 restoration was VERIFIED in preserved receipts; this task did not access
devices or reverify live state. Do not redo restoration, revoke trust or install
without separate authorization. Future pairing acceptance needs a legitimately
unpaired safe fixture and a verified rollback plan.

Original research3253518 (85 tracked/411 untracked), frozen20928f4/656c378 and
Video/report7ef9960 worktrees are preserved separately. Detailed historical
`PostHardwareReconciliation.md` and its matrices remain in
`C:/Users/Francy/.codex/worktrees/phase6-5-video-recovery/Warpnect`; not copied or
merged here. UI/design and P1 checkpoints are local only.
No master/research
integration or push is authorized. Phase7 R2 is unchanged/proposed, S0 not begun;
audio85/70ms, admission8ms, output4ms and microphone80/70ms/freshness40ms stay fixed.
Root/Shizuku qualification and Phase6.5 exit remain separate outstanding work.
