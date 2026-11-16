# Android API30 Synthetic Pairing Composition Fixture

## 2026-11-03 Independent Software Checkpoint Review (r1634)

The exact admitted r1633 candidate was independently reviewed in isolated branch
`codex/phase6-5-pairing-android-api30-fixture-checkpoint`, rooted at
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-android-api30-fixture-checkpoint/Warpnect`.
Parent is `656fcef657ae2aac08c8099ce5b0905378a092cd`; source ancestor remains
`08c6f1c3034de1545286cdc802e3731eaac10d98`. Exactly six source/build/test and
three documentation paths were selectively copied with raw and LF hash checks.
No application or test correction was made. All 4,936 inherited inputs remain
identical under the documented byte-preserving CRLF normalization, including the
invalid-UTF8 binary's actual Git-clean object check.

Fresh final admission passed7037 checks in one attempt. Source checkpoint
`2981d6626f28a71f1671eafce10aa67ee2a90e5d` contains exactly the six reviewed paths.
The documentation commit identity and final clean-tree verification are recorded
externally in `FinalCheckpointReport.md` and `postcommit-verification.json`.
Only that successful two-commit postverification awards
`P2_ANDROID_API30_SYNTHETIC_FIXTURE_CHECKPOINT_VERIFIED`.
This is a software-checkpoint label, never Android runtime validation. Overall
remains **P2_PARTIAL_BLOCKED**; Phase 6.5 is incomplete.

### Independent Review And Explicit Limits

External evidence: `C:/wg-pairing-api30-checkpoint-r1634-20261103/`.
`IndependentReview.md` contains the complete ownership graph, ten Keystore cases
and AF01-AF24 coverage matrix. `independent-review-provenance.json` binds the review
to the exact source and method inventory. Source/security/architecture/ownership
review passed for the controlled software-fixture scope, with these explicit limits:

- Missing/preexisting aliases fail closed; deletion ownership is recorded only
  after acknowledged generation. An ambiguously created alias is not adopted or
  deleted. Unknown test residue cannot be described as completely restored.
- Test AES256/GCM and exportable synthetic software RSA2048/X.509 are separate
  domains. No hardware-backed RSA or production D-P1-D-P4 provisioning is inferred.
- AF22/AF24 prove safe rejection, not unique failure-cause attribution: an earlier
  TLS failure could also satisfy their negative assertions. AF08 supplies the
  independent positive path; fresh host protocol/bridge tests supply other negatives.
- AF15 enables modeled missing drain acknowledgement only at Stage.PUBLISHED,
  after recovery admission. Independent store BUSY witnesses a retained real lease;
  that stage marker alone is not positive publication proof. AF08 requires actual P/S.
- The unchanged DEBUG startup provider still registers lazy existing factories and
  clears fixed private root-lab handoff files. It does not launch Host or connect ADB.
  Future disposable-target preflight must account for it; no new registration is added.

Actual P1 ticket/original WorkContext, D1-D7, D-O1/D-O2 and 120s/60s/3s policies
are unchanged. The worker uses real Android storage recovery before owned TCP,
actual pinned TLS/exporter/SPAKE2, exact verified-peer binding, observed prepared(),
one-shot R, native no-replace P and separate S. Real delegate awaitDrain and worker
termination checks deliver D; P1 alone decides F/reconciliation. No fake drain,
AUTHENTICATED trust claim, Host/Session authority or daemon-trust adoption is added.
Blocking crypto/filesystem/socket/native/join work remains outside the P1 monitor.

The whole DEBUG bridge retains `@RestrictTo(RestrictTo.Scope.TESTS)`, direct pinned
Bob/destroy calls and exact test caller closure. It is absent from Release and not
registered in normal pairing startup. This annotation is static API discipline,
not runtime access control; Android classloader behavior remains unexecuted.

### Fresh Software Evidence

One declared complete unfiltered/noninstrumented JVM acceptance followed source
freeze; no rerun, diagnostic agent or test exclusion. Focused and full executions
used configured JDK17, maxWorkers2, maxParallelForks1, forkEvery0.

| Gate | Fresh r1634 result |
| --- | --- |
| Focused JVM | 422/422,18 suites;0 failures/errors/skips |
| Full Debug JVM | 1606/1606,198 suites;0 failures/errors/skips |
| Offline media replay | 40/40;0 failures/cancellations/skips |
| ktlintCheck,lintDebug,lintRelease | PASS;126 Debug/34 Release,no new semantic findings |
| Debug/AndroidTest/helper/unsigned Release | PASS; exact task execution vs UP-TO-DATE in software-summary.json |
| Patched AAR | Two independent reproductions plus selected artifact match pinned hash |
| Library/classpath | 619 closure checks,43 unrelated class entries identical;132 classpath/API checks |
| Corrected PowerShell producer | 61 controls PASS,8/8 valid compiled guards;both false-right cases rejected |
| Other isolation | P1/storage/adapter/publisher/ABI/manifest/source-set/DEX/handoff/helper guards PASS |
| Android instrumentation | 69 methods/5 suites COMPILED,NOT EXECUTED |
| Native CTest | NOT EXECUTED;native inputs unchanged |

The four authentic bridge JVM methods passed in both cohorts. AF01-AF24 remain
source-reviewed/compiled only,not Android runtime PASS. Positive Android paths
select actual AndroidKeyStore,Os.fsync and JNI renameat2(RENAME_NOREPLACE),not a
modeled successful directory sync. Their runtime results and power-loss behavior
are not established by source or compilation. Named fault acknowledgements,
fixture clock/timer dispatch and controlled restart remain explicitly modeled.

Focused Windows forced-death and explicit-release inspections both returned
COMMITTED_CLEANUP_PENDING. Full forced-death returned BUSY with every approved
integrity/parent-registry/no-mutation/reconciliation assertion passing; strict
explicit-release recovery returned COMMITTED_CLEANUP_PENDING. Live-owner BUSY
remained strict in both cohorts. No lock-refusal mechanism was captured or inferred;
**P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED** remains unchanged.

### Source And Artifact Provenance

Six-source LF cohort remains
`5092B52E14542E7C864B8ED65EA6E25B88D07AF5381E5D0250E7364D606C4348`;
bridge raw/LF `565352DD2A8A4DC1EADF68064DC22B96FC73ADD59AE8CE07333E0C4FC05988FB`.
Algorithm is SHA256 of sorted UTF8 rows `path<TAB>uppercase-LF-SHA256<LF>`.
`source-final.json` records all individual raw/LF hashes; `source-versus-parent.diff`
records the exact six-file delta. Documentation identities are recorded separately.

| Artifact | Fresh SHA256 |
| --- | --- |
| Debug APK | 88F6C019FB39C9EB9742806113232CBF916AA5B133B7F519924B5FCD92D0B1A6 |
| AndroidTest APK | 4DFBB87E6E68EB3DE64D9A13ADC04C9438DC2452843FE85B1FFD4B64166BC524 |
| Helper JAR | FCC679E75DF70246CC722402FD68AB4AB9162A023A530B1EE47C31C8566F35FD |
| Unsigned Release APK | 5D58AF3B0FA5B057F19B6D7DF1BFB5A7FE47395D6408F1C7555E6F534BF3C28D |
| Selected/reproduced AAR | BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0 |

Compared with r1632,helper bytes are identical;AndroidTest uncompressed ZIP entries
are identical despite container hash difference. Debug has six changed native
entries and Release three; `artifact-comparison.json` identifies their exact hashes.
Different build-root/native output identities are not instruction-level equivalence
or timestamp-only proof. Whole-APK reproducibility is not claimed; the pinned AAR
is reproduced exactly. No post-commit binary reproduction is claimed.

### Preservation And Next Gate

34 other protected locations:28 registered worktrees and six known locations
without Git metadata. Of the latter,two directories exist and four are absent.
Initial Git/known-path inventory preceded transfer; a supplemental read-only full
walk covers25355 entries in the two existing metadata-less directories and is
compared again before commits. No missing metadata was repaired. Four inherited
large research artifacts retain their size/mtime-only exception. No full Git
registry continuity across October30-November2 recovery is asserted.

Original r1631 VisibleForTests,r1632 malformed producer,r1628 regex,r1629 schema,
r1615 lease negatives remain immutable. CXX5304 recurred as a nonfatal SDK metadata
warning. Historical strict lint differences,separate Release-unit compilation
limitation,TLS/write/bootstrap/USB/SDK observations,native layout limitations,
G1-G5/media debts and Phase7 R2 remain open; assembleRelease closes none of them.

Next recommendation only:separately authorize one disposable exact-API30 runtime
fixture after identity/fingerprint/ABI/kernel/UID/SELinux,APK/source/signature,
private test namespace/alias,localhost isolation,runner,cleanup/rollback and
stop-on-failure preflight. Account for inherited startup providers. Do not rely
only on ADB filtering or choose personal devices. No such execution occurred here.
Production provisioning,Android lifecycle/clock wiring,Network/NSD identity,real
adbd interoperability,blocked writes/suspend-connect,OEM qualification and Release
onboarding remain separate. All prior verified P1/P2 checkpoint classifications
are retained; no Phase6.5 completion or hardware/production-readiness claim.

## 2026-11-03 Guard Producer Correction And Software Admission (r1633)

**Android API30 synthetic composition fixture - implemented and software-tested;
instrumentation compiled,not executed.** This is software admission,not a verified
Git checkpoint,Android runtime validation,hardware qualification or production
readiness. Retain P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED and prior P1/P2
checkpoints. Overall **P2_PARTIAL_BLOCKED**;Phase6.5 remains incomplete.

### Authorization And Unchanged Candidate

The user explicitly authorized correction only of the external PowerShell producer,
independent false-condition controls,provenance/reuse verification and one admission
with the original consumer. Parent/HEAD656fcef657ae2aac08c8099ce5b0905378a092cd,
ancestor08c6f1c3034de1545286cdc802e3731eaac10d98,branch and managed -resume root
are unchanged. No application,test,build,dependency,compiler,native or protocol edit.
All six candidate source/build/test paths match frozen cohort
`5092B52E14542E7C864B8ED65EA6E25B88D07AF5381E5D0250E7364D606C4348`;
bridge raw/LF565352DD2A8A4DC1EADF68064DC22B96FC73ADD59AE8CE07333E0C4FC05988FB.
No test assertion or AF01-AF24 method changed. Only these three factual status/docs
were updated after admission. Nine total uncommitted paths,index empty,no commits.

External r1633 evidence:`C:/wg-pairing-guard-producer-r1633-20261103/`.
All446 sealed r1632 files and its nine-path candidate were verified before work.
The corrected producer and all evidence remain external,not tracked Git content.

### Confirmed Defect And Narrow Correction

Original PowerShell AST confirms exactly two affected expressions:And has an
ArrayLiteralAst on its right. `@($left -and $right,'label')` creates a one-element
boolean row;with left=true,the right array is truthy even if right=false. Thus
join/isAlive and Bob/destroy fail to enforce their second operand,and labels become
null. The other six original rows are regular boolean/name pairs. The historical
r1632 rejection was correct and remains unchanged;this was not just serialization.

New external `fixture-bytecode.corrected.ps1` preserves the eight exact semantic
checks,reads the same five owner classes and DEBUG bridge,and computes all ten
witness booleans independently before combination. Explicit assertion calls take
a grouped boolean and separate label,with strict boolean/name/uniqueness/count
validation. Missing compiled input or malformed output fails closed. No null-label
substitution,consumer relaxation,hardcoded success or application correction.
The only success receipt is emitted after all eight actual conditions/schema pass.
`DefinitionsOnly` is used solely to test the same pure assertion functions;it never
emits a genuine compiled receipt or invokes application code.

### Independent Controls And Genuine Compiled Result

**61 external controls PASS:3 expected accepts,58 expected rejects.** Both compound
predicates independently exercise TT accept,TF reject,FT reject,FF reject. Changing
only isAlive or only destroy to false causes the intended assertion to fail.
Controls also cover all six simple predicates,missing left/right witnesses,
nonboolean/null values,invalid structure,missing/empty/duplicate labels,wrong or
missing count,short/long/null check arrays,incorrect result/scope and missing
compiled file/text. These are external synthetic guard tests,not additional JVM
pairing tests or Android execution.

One genuine corrected producer execution exit0:**8 exact named checks,zero nulls**.
All ten actual compiled witnesses are present. Current bytecode disassemblies of
the five owner classes and target bridge match sealed r1632 instructions;class
hashes are recorded. Fourteen archived javap checksum records also match current
compiled class bytes. This proves only the specified compiled boundaries,not live
Android classloader,filesystem,Keystore,native syscall or physical D behavior.

| Identity | SHA256 |
|---|---|
| Original producer (unchanged) | `AED77D49BB1E4D88A5E27DC910B1ECFA324D9E63471D0BAB0B88D4E12FD45B89` |
| Corrected producer | `9197BB679B0B7CFDC2773D50C448A3E7521C87FA42F66CE7EAB9CD829EE88039` |
| Original malformed receipt (unchanged) | `B444D586A8E3FAE93B65A5667227CE82B2ED41D2E8C0704ABAB5310BCE93BD32` |
| Corrected genuine receipt | `09AD24B8CEC83BF299B94F0E9509C5687FEBEE5FED0D6C5702BCCBA99EA8F025` |
| Original and executed consumer | `D58D15F3E348FC6041D60D325C9A07AE4F9CB16EDF94F98120C3898B9F00ABE0` |
| Exact external producer diff | `93C4D7BE0F314791B5A71D5A07F3D2FBE53320A7C5C0D9A0A6E1485EEB50C3FB` |

### Software Reuse,Not New Executions

**REUSED r1632:**1606/1606 full JVM in198 suites,422/422 focused in18 suites,
40/40 replay,zero failures/errors/skips;required builds,ktlint,lints,AAR
reproduction and security/isolation receipts. No Gradle,test,replay or reproduction
campaign was repeated. 5651 provenance checks verified source/inherited inputs,
archived XML/commands/current artifacts,controls and class metadata. Reuse and
admission input ledgers identify each original receipt/hash and new guard input.

69 Android instrumentation methods in5 suites remain **compiled,zero executed**;
method identities and all AF scenarios are unchanged. Pinned selected/two
reproduced AARs stillBC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0.
All four APK/helper identities remain exactly the r1632 values below. No rebuild
or new Android/native qualification is claimed. Native CTest remains unexecuted.

Existing lint126 Debug/34 Release remains semantically verified with zero additions.
r1632 had not archived raw lint XML before its admission stop. Current full XML DOM
was checked against sealed r1631 XML with only the uniquely identified,approved
VisibleForTests finding removed from Debug;Release DOM is identical. No additional
normalization/filter or warning allowance. Current XML hashes are measured now,
not falsely called historical r1632 raw hashes. The original comparison/receipt
and successful software execution remain intact;no Lint tool ran here.

A new provenance reader initially failed to recognize javap's actual `/C:/...`
Windows path notation. Original helper/negative are retained. A separately named
reader decodes only that drive-prefix notation,four path controls pass,and all
hash comparisons/minimum metadata requirements remain unchanged. This is input
path resolution,not a change to the producer's predicates or original consumer.

### One Original-Consumer Admission

The original `admit.cjs` was copied byte-for-byte to a fresh isolated evidence
view,not edited.289 dependency-closed r1632 input files were copied/hash-verified.
Only the corrected guard and current preservation snapshots are new inputs.
The corrected receipt has its own top-level name and is copied byte-identically
to the consumer's required `fixture-compiled.json` path in that new view;the old
malformed file is never overwritten. No interception or consumer-code substitution.

One declared command:`node C:/wg-pairing-guard-producer-r1633-20261103/admission/admit.cjs`.
**Exit0,6232 checks PASS**,result PASS_CORRECTED_FIXTURE_SOFTWARE_ADMISSION.
Exact command,input hashes,per-check ledger and exit/log identities are in
admission-execution.json,admission-inputs.json and admission/admission.json.
Original r1631/r1632 negatives remain failed historical receipts,not retroactive
successes. No second attempt or opportunistic post-admission correction.

The admission covers unchanged P1/ticket/generation/120-60-3s policies,D1-D7,
D-O1/D-O2,R/P/S/D/F,owned socket/authentic pinned TLS-PAKE/verified-peer binding,
test-only key policy,native no-replace/separate P-S,owner drain/callback isolation,
DEBUG/Release dependencies and manifest/provider/Host/Session boundaries through
the verified inherited source and original guard receipts. Source/compiled claims
must not be mistaken for Android runtime execution.

### Preservation,Final State And Next Boundary

Fresh inventory:33 known protected locations,27 registered worktrees and six
historically unavailable-Git directories. No repair/attach/delete or October30
continuity claim. Observable HEAD/branch/index/dirty state,master/upstream and
Phase7 R2 preserved;four research artifacts keep the declared metadata-only
exception. Final post-document comparison and new documentation manifest are
external;source/artifacts remain frozen after admission. No commit,push or merge.

Retain Windows failure UNATTRIBUTED/r1615,r1628 regex/r1629 schema negatives,
r1631 Lint/r1632 producer negatives,strict lint differences,Release-unit limitation,
CXX5304,TLS/write/bootstrap/USB/SDK/native-layout findings,G1-G5/media debts and R2.
No production AES/RSA,D-P1-D-P4,device/emulator/ADB/USB/NSD,real adbd,Host/Session,
Release onboarding,Root/Shizuku,media/SCL,P3-P5 or Phase7 action occurred.

Recommendation only:separately authorize disposable exactAPI30 runtime preflight
and the existing synthetic fixture,first fixing identity/ABI/UID/security-domain,
test alias/root,transport isolation,APK/source freeze and cleanup/rollback scope.
No personal-device fallback or ineffective USB filter as sole isolation. Existing
controlled clock/owner fixture does not qualify production Android lifecycle.
Actual storage/native/Keystore/D,production identity/clock/owner,Network/NSD,real
adbd,blocked writes/suspend-connect,API/OEM/device and Release remain open.

## 2026-11-03 Test-Only API Correction And Software Acceptance (r1632)

**Test-only boundary corrected; individual software executions passed,but final
admission STOPPED_FIRST_ADMISSION_FAILURE. Instrumentation compiled,not executed.**
No completed acceptance or new formal checkpoint is earned. First negative:
`C:/wg-pairing-fixture-boundary-r1632-20261103/admission-first-negative.json`.
Retain P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED and all prior verified
P1/synthetic/libadb/Windows software checkpoints. Overall **P2_PARTIAL_BLOCKED**;
Phase6.5 remains incomplete. No Android runtime or hardware qualification.

### Exact Baseline And Scope

Same parent `656fcef657ae2aac08c8099ce5b0905378a092cd`, ancestor
`08c6f1c3034de1545286cdc802e3731eaac10d98`, branch and managed `-resume` worktree
below. Explicit user attachment57de428b authorizes this one test-API correction,
fresh software verification and previously interrupted isolation gates,not Android
execution. All362 sealed r1631 evidence files and its original nine-path candidate
were verified before editing. Exact source/doc snapshots are retained under the
new evidence directory's `before/`. The old failed cohort remains
`D839240967B17DE6C5A5B20CC9EBF0583E0342FD5B52161BF723DCB86DEF51DA`.

Only **one source file changes relative to r1631**:
`app/src/debug/java/io/github/muntashirakon/adb/DebugSyntheticPairingPeerAuth.java`.
Two added lines:import `androidx.annotation.RestrictTo` and class-level
`@RestrictTo(RestrictTo.Scope.TESTS)`. Removing exactly those lines reproduces the
old source. The other five candidate source/build/test files are byte-identical;
no test methods or compiler dependencies change. Total candidate remains six
source/build/test plus three documentation paths,all uncommitted,index empty.

### Root Cause And Valid Boundary

Pinned source revision `dcd931071b5e4b9ebc0ac598201755d5a8814b71` has a
package-private PairingAuthCtx and public static createBob(byte[]),annotated
VisibleForTesting with intended private visibility. Java package access was
possible,but the unrestricted DEBUG bridge was not a test context. The r1631
warning was genuine; DEBUG application source alone is not test source.

The whole new bridge is now explicitly restricted to tests. This is a supported
[AndroidX TESTS API contract](https://developer.android.com/reference/androidx/annotation/RestrictTo.Scope#TESTS),
not a suppression or moved invocation. It permits test source sets/explicitly
test-restricted code and constrains future ordinary callers. The original
[VisibleForTesting contract](https://developer.android.com/reference/androidx/annotation/VisibleForTesting)
is unchanged. Actual cached lint-checks31.7.3 was inspected:its isTestContext walks
enclosing declarations and recognizes the RestrictTo TESTS scope. The exact
original-to-corrected project Lint results below establish the real gate outcome.

This is static API discipline,not runtime access control or pairing authorization.
No reflection,SuppressLint,SuppressWarnings,rule filter or warning-comparison
change is used. The strict comparison script remains byte-identical SHA256
`BE2EDCFB4F2356ED8FC61705DFF65B142FB73806621596E14354C417956A2F63`.
All bridge consumers remain exactly the AndroidTestDebug peer and testDebug tests.
Compiled metadata retains TESTS; compiled peer still invokes the public target
bridge; nine explicit boundary checks PASS.

The bridge remains beside package-private PairingAuthCtx in the target DEBUG
APK/package. Moving it to AndroidTest was rejected because compilation would not
prove cross-classloader runtime-package access. No classloader arrangement or
runtime implementation changed; actual Android loader/provider behavior remains
unexecuted. Pinned PairingAuthCtx source SHA256
`E663D7663D8FAA0AFF1D4DE8A05E55A2C07F494DAC634918E2CB10B792CFD027`,
class SHA256 `9BC44D43D3A079D0A9994176732A46EA5DA864E7090702F70BD63724DE59D31F`
matches the unmodified class in the selected AAR. No libadb/BC patch or upgrade.

### Fresh Executions And Discovery

Final source cohort:
`5092B52E14542E7C864B8ED65EA6E25B88D07AF5381E5D0250E7364D606C4348`.
Bridge raw and LF SHA256:
`565352DD2A8A4DC1EADF68064DC22B96FC73ADD59AE8CE07333E0C4FC05988FB`.
`source-final.json` contains all six raw/LF identities; the five other rows retain
their r1631 identities. The cohort algorithm is the same sorted path/TAB/LF-hash
row SHA256 described in historical section15. No source edit after final freeze.

Fresh **1606/1606 JVM methods in198 suites**, **422/422 focused in18 suites**,
zero failure/error/skip. One declared unfiltered/noninstrumented final full run;
normal Gradle8.12/JDK17 worker,maxWorkers2,maxParallelForks1,forkEvery0,no diagnostic
agent. Four authentic bridge tests retained and freshly passed:Alice/Bob encrypted
roundtrip,wrong-code rejection,invalid-exporter rejection,released-context refusal.
Existing owned-protocol30,C/H31/18,P1 coordinator96/deadline5/observation41 and
storage/multiprocess cohorts remain unchanged. Per-suite focused counts are the
same as historical section12. Delta relative to r1631 is **zero methods/suites**.
The declaration's inherited `newHostMethods=4`/`expectedPriorMethods=1602` fields
are relative to parent656fcef,not four tests added by this annotation correction.

Fresh40/40 offline replay PASS. Android Kotlin/Java compilation and actual JUnit
annotation discovery PASS:18 storage+8 lifecycle+8 native+11 multiprocess+24 AF =
**69 methods/5 suites,zero executed**. Every method identity matches r1631;
AF01-AF24 remain compiled-only,not runtime PASS. Same-socket ownership,distinct
certificates and all actual Android calls remain in unchanged fixture/protocol
source; Android filesystem/native/Keystore/D/F behavior is not qualified here.

Fresh Windows evidence:focused forced-death scenario returned BUSY and passed
the approved fail-closed safety assertions; explicit-release returned
COMMITTED_CLEANUP_PENDING. Full execution returned COMMITTED_CLEANUP_PENDING for
both crash/release and BUSY for the live published owner. No retry,delay,assertion
change or causal attribution. Retain P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED.

### Builds, Lint And Completed Interrupted Gates

Fresh required Gradle tasks all PASS:verifyOwnedPairingReproduction,
testDebugUnitTest,ktlintCheck,lintDebug,lintRelease,assembleDebug,
assembleDebugAndroidTest,packageDirectShellProbeDex,assembleRelease.
Unchanged native/Release inputs may be UP-TO-DATE,not newly recompiled/qualified.
Native CTest not executed. No Android execution or device/ADB/USB enumeration.

The real new VisibleForTests finding is absent. Strict normalized semantic
comparison PASS:**126 Debug/34 Release**,zero added findings/errors; exactly the
historical eight removed GradleDependency advisories in each variant remain
separate from exact old warning-set equality. No new warning allowance. Original
r1631 Debug127/Release34 XML and STOPPED_FIRST_FAILURE receipt remain unchanged.

Fresh successful qualified guards:619 library/class/DEX closure (43 unrelated classes byte-
identical),132 legacy/classpath selections,21 source-set/manifest/APK,34 P1-model,
10 storage compiled,14 Android-adapter compiled,6 native symbols,36 publisher
source,3 publisher ELF,18 P1 observation compiled,8 composition compiled,
19 composition DEX,57 handoff source/compiled/DEX,
69 fixture source/compiled/dependency/DEX and9 explicit test-only API checks.
The eight-check fixture-compiled producer reported PASS but its receipt is
malformed and two conjunction checks are not fully enforced;not qualified.
Helper payload matches the complete current DEBUG APK DEX. No duplicate class,
DEBUG libadb/BC promotion into Release,test fixture in Release or normal backend
registration. These formerly interrupted guards ran now,not historically in r1631.

Selected and two independently rebuilt AARs match
`BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0`.
libadb3.4.0/BC1.84 and all seven component selections are unchanged. R/P/S/D/F,
D1-D7,D-O1/D-O2,original120/60/3s budgets,storage,codec,Keystore,native publisher,
P1,transport,protocol,provider/Host/Session/media/SCL/SDK policies are untouched.

### Fresh Artifacts And Preservation

| Artifact | SHA256 |
|---|---|
| Debug APK | `654FEE5FB7CECA978319885EE082D209028B72416F30F7FDD63605E0364553DA` |
| AndroidTest APK | `AE20657789A253F63BBC61E08C96FF2A09C69DF32AE58D2686B988AEAA161C8B` |
| DirectShell helper JAR | `FCC679E75DF70246CC722402FD68AB4AB9162A023A530B1EE47C31C8566F35FD` |
| Unsigned Release APK | `CB301866A90A8E6D259EAFE94CF2EDF684EA974FCC1CFC6AA165DE52242D4D0B` |

AndroidTest/Release hashes equal r1631 in this measured build;Debug/helper differ.
No general APK reproducibility or instruction-level equivalence is inferred.
Full/focused XML,commands,execution receipts,source diff/snapshots,artifact copies,
class inventories and final admission are separately sealed in the new directory.

33 known protected locations were freshly inventoried:27 registered worktrees and
six previously unavailable-Git directories. Preserve all observable current
HEAD/branch/index/dirty-file state,master/upstream and Phase7 R2;four original
research artifacts retain the declared size/mtime-only exception. No missing
metadata repair or unbroken October30 registry continuity claim. Final comparison
and final Git state are external;index remains empty,nine dirty paths,no commit.
Ten observed host JVM/child PIDs have no surviving test-owned process.

Diagnostic helpers are not hidden:initial external classpath lookup failed on
the absent lintClassPath configuration;one configuration probe lacked ANDROID_HOME;
CLI help's normal nonzero exit was misclassified. Actual AGP lintTool.classpath
was then inspected/exported. Two separate standalone CLI layouts failed to
reproduce the known old warning and remain **INCONCLUSIVE**,not acceptance proof
or passing controls. No assertions were relaxed. The supported source contract,
actual detector,real project Lint,unchanged strict comparator and exact consumers
are the correction evidence. These helper outcomes are not Android/product defects.

### First Final Admission Negative: External Producer Defect

The single declared aggregate exited1 at `actual successful guard/schema:
fixture-compiled.json`. Its result/count are PASS_COMPILED_FIXTURE_BOUNDARY/8,
but checks[5] and checks[7] are null. The aggregate correctly rejects these fields;
it must not be relaxed to accept them. No corrected verifier or rerun was made.

Read-only analysis identifies PowerShell precedence in fixture-bytecode.ps1:
`@($left -and $right,'label')` creates a one-element boolean row because the
right side includes the comma array. With left=true it remains true even when
right=false;index1 serializes null. The affected predicates are join+isAlive and
Bob+destroy. This is not merely missing display labels:the producer fails to
independently enforce each second operand. Bounded constant-expression controls
demonstrate the behavior without changing genuine evidence. Actual saved compiled
text contains all four witnesses,but that diagnostic reading does not replace a
corrected guard or admit this cohort. No application defect is inferred.

Immutable identities:aggregate `admit.cjs`
`D58D15F3E348FC6041D60D325C9A07AE4F9CB16EDF94F98120C3898B9F00ABE0`;
producer `fixture-bytecode.ps1`
`AED77D49BB1E4D88A5E27DC910B1ECFA324D9E63471D0BAB0B88D4E12FD45B89`;
original `fixture-compiled.json`
`B444D586A8E3FAE93B65A5667227CE82B2ED41D2E8C0704ABAB5310BCE93BD32`.
Original files,first negative and separate admission-negative-analysis.json remain
intact. Source stays5092B52E...D606C4348;no application edit or software rerun follows
the negative. Final provenance/closeout is not a successful admission receipt.

### Remaining Limits And Next Recommendation

Preserve the entire r1631 negative/history below,Windows failure/r1615,
r1628/r1629 verifier negatives,Release-unit180-diagnostic/11-file limitation,
CXX5304,prior TLS/write/bootstrap/USB/SDK/native-layout observations,G1-G5/media
debts and R2. No fresh CTest or Android fsync/power-loss/drain qualification.

Immediate recommendation only:separately authorize the corrected external producer
with explicitly grouped boolean/name pairs or explicit Check calls,including
false-right-operand controls. Preserve this producer/receipt and generate a
separately named compiled receipt against unchanged source/artifacts;then one
new admission with valid provenance. Do not substitute labels for missing checks
or weaken the consumer. No correction/retry is executed here.

Only after software admission and separate runtime authorization:disposable **exactAPI30** fixture
execution after environment/fingerprint/ABI/UID/alias/root/transport isolation,
APK/source freeze,cleanup and rollback preflight. An ineffective USB device filter
alone is insufficient isolation. No personal-device fallback or automatic run.
Production D-P1-D-P4,Android owner/elapsed-timer wiring,Network/NSD identity,real
adbd,Android blocked writes/suspend-connect,OEM/API/device qualification and Release
remain independent blockers. No Host/Session,Root/Shizuku,P3-P5 or Phase7 work.

## Historical r1631 Report: Preserved Stopped Cohort

2026-11-02, r1631. **Implemented and AndroidTest-compiled; final software
acceptance STOPPED_FIRST_FAILURE.** The no-new-lint-finding comparison failed.
This is not an Android runtime validation or a completed software acceptance.
**Android instrumentation: COMPILED, NOT EXECUTED.** Overall **P2_PARTIAL_BLOCKED**.
No commit, push, merge, Android/emulator/device/ADB/USB/NSD execution or real pairing.

## 1. Baseline And Authority

Parent/HEAD `656fcef657ae2aac08c8099ce5b0905378a092cd`, source parent
`08c6f1c3034de1545286cdc802e3731eaac10d98`, retains
P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED and the earlier synthetic/P1/libadb/
Windows test-contract checkpoints. The user attachment c44e1346 authorizes only
contract gates, this isolated fixture, compilation and host software verification.
It does not authorize production D-P1-D-P4 provisioning or Android execution.
Accepted RFC-006I/RFC-006J, D1-D7 and D-O1/D-O2 are unchanged.

Historical baseline, not this increment's execution:1602/197 full JVM,
418/17 focused,40 replay,45 Android methods compiled/zero executed in r1629/r1630.
Earlier component-level API30 runtime receipts do not validate this composition.

## 2. Worktree Recovery And Preservation

Branch `codex/phase6-5-pairing-android-api30-composition-fixture`, active managed
root `C:/Users/Francy/.codex/worktrees/phase6-5-pairing-android-api30-composition-fixture-resume/Warpnect`.
The October30 partial root without `-resume` was no longer Git-managed on Nov2:
its `.git` and matching common worktree entry were absent. No cause is attributed.
Attach failed without changing it. A clean managed replacement was created from
the exact parent and only five byte-verified partial source/test files copied;
no dirty branch merge, whole directory import or crash-log import occurred.

The current preservation baseline covers33 known protected locations:27 registered
worktrees plus six directories without Git metadata. Their observable contents,
available branch/HEAD/index/dirty state, master/upstream and Phase7 R2 are compared
before/after resumed work. Four large original-research artifacts retain the
predeclared size/mtime-only exception. Full October30-to-Nov2 Git-registry
continuity cannot be claimed. Missing metadata is not repaired. Details:
`C:/wg-pairing-android-api30-fixture-r1631-resume-20261102/recovery-initial.json`
and the final evidence directory's preservation receipts.

## 3. Gate A: Clock, Timer And Lifecycle

Source-level **PASS for an isolated instrumentation-owned fixture only**, recorded
before source edits in `C:/wg-pairing-android-api30-fixture-r1631/GateDecision.md`.
The existing contract supplies the exact ticket/WorkContext, injected monotonic
clock and cancellable non-inline scheduler. Request/submit retain120s consent,
the original absolute60s work budget and P1's private3s cleanup observation.
Handoff's separate once-only <=3s cutoff consumes dispatch delay; no equality
with P1's private cutoff is asserted. Existing source/state machines are unchanged.

The bounded controlled clock/scheduler is shared by P1/composition/transport/
handoff, with due callbacks outside its lock. Advancing it models elapsed time;
it does not qualify Android suspend, connect or timer delivery. Android
[elapsedRealtime](https://developer.android.com/reference/android/os/SystemClock)
includes sleep; [Handler scheduling](https://developer.android.com/reference/android/os/Handler#postAtTime(java.lang.Runnable,%20long))
uses uptime. No production Android scheduler, Activity or Service is introduced.
The instrumentation owner closes P1 and separately joins its observer outside
the P1 monitor/main looper. This is not production process-lifecycle proof.

## 4. Gate B: Storage And Identity

Source-level **PASS through existing ports**, not measured platform qualification.
The unchanged composition accepts a storage factory closure. The Android factory
uses an existing key capability and may touch framework directories; it runs only
in explicit setup/the operation worker, not passive construction. Its
RESOLVED_NOT_QUALIFIED result is preserved.

AES-256-GCM is an existing-only non-exportable AndroidKeyStore test handle.
The ADB identity remains synthetic software RSA2048 plus matching X.509 and
exportable PKCS8, as required by the existing codec/signer. Remote certificate
digest is obtained from actual verified protocol evidence, not fixture GUID or
mDNS. Warpnect Session identity remains a separate domain. No AndroidKeyStore
RSA substitution or old DEBUG identity import occurs.

## 5. Exact Source Scope

Six uncommitted source/build/test paths:

| Path | Reason |
|---|---|
| `app/build.gradle.kts` | Two lines expose the already selected bctls1.84 to `androidTestDebugCompileOnly`; no upgrade/runtime/Release dependency |
| `app/src/androidTestDebug/java/io/warpnect/platform/privileged/directshell/pairing/AndroidPairingStorageFixture.kt` | Reject observed alias collisions before generation, own cleanup only after success, extract synthetic RSA factory |
| `app/src/androidTestDebug/java/io/warpnect/platform/privileged/directshell/pairing/AndroidCompositionPeerFixture.kt` | Reviewed adaptation of the existing host peer with explicit distinct synthetic identities |
| `app/src/androidTestDebug/java/io/warpnect/platform/privileged/directshell/pairing/AndroidPairingCompositionBoundaryInstrumentationTest.kt` | Instrumentation-owned wiring and24 AF scenarios |
| `app/src/debug/java/io/github/muntashirakon/adb/DebugSyntheticPairingPeerAuth.java` | Small unregistered package-access bridge to authentic pinned Bob PAKE in the target classloader |
| `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/DebugSyntheticPairingPeerAuthTest.kt` | Four actual host tests of that bridge |

Existing main source, P1, C/H tests, storage/codec/Keystore, native publisher,
transport/protocol, patched library, manifests, providers, SDKs and media/SCL are
unchanged. The build delta follows the preserved October30 compiler failure:
AndroidTest could not resolve `org.bouncycastle.tls`; the correct compile-only
configuration was inspected before adding the exact existing1.84 dependency.
The DEBUG bridge is needed because the host testDebug shim is unavailable to
instrumentation, and package-private cross-APK/classloader access is unqualified.
It contains no network/storage/provisioning/authority operation. Android loader
behavior and the lint restriction below remain unresolved qualification gates.

## 6. Actual Execution Graph And Ownership

```text
explicit fixture setup + P1 request/code
  -> exact ticket + original WorkContext
  -> existing composition worker + identity borrow
  -> Android storage begin / lease / encrypted recovery / sync / authenticated reread
  -> existing recovery admission + live deadline
  -> one owned localhost socket + original pinned TLS1.3/exporter/SPAKE2/ADB frames
  -> exact verifiedPeer binding (ticket, endpoint, socket, local/remote certificate)
  -> storage prepare -> observed prepared(PEER_INFO_VERIFIED,POSSIBLE)
  -> one-shot P1 R -> real Android native P -> independent S receipt
  -> worker finally: transport/code/store/identity-borrow cleanup
  -> owner observer calls unchanged delegate awaitDrain, observes real termination
  -> delegate D -> exact P1 callback -> permitted F or reconciliation
```

P1 alone grants R/F. The operation worker owns blocking crypto/storage/protocol
composition; transport owns its socket and I/O/close workers. Storage owns the
FileLock/channel/registry lease. The owner observer is separate from attempt D.
The fixture listener/peer thread is separately closed/joined. Neither a callback
nor a worker's finally is accepted as its termination. The test never manually
calls operation.awaitDrain, constructs D, issues R or fabricates verifiedPeer.

## 7. Alias And Synthetic Identity Policy

Setup uses only a unique UUID-scoped private test root and
`warpnect.test.directshell.pairing.aes256.v1.*` alias. The production alias/root
and old DEBUG credentials are not provisioned/adopted. Existing alias observation
fails before generation; cleanup ownership is recorded only after success.
Unexpected/ambiguous generation is not silently adopted for deletion. This is
single-owner controlled fixture policy, not an atomic cross-process production
provisioner. Test setup requires exactAPI30 and normal app UID/untrusted_app domain.
Local and peer certificates are distinct. Synthetic software RSA is not claimed
hardware-backed; the AES handle must remain non-exportable at future runtime.

## 8. Android Filesystem, P And S

Positive paths select the unchanged Android filesystem and native JNI publisher,
not Windows hard links or modeled positive fsync. Actual source requests file,
directory and parent fsync; stable OS lease; encrypted authenticated recovery
reread; revision-zero admission; `renameat2(RENAME_NOREPLACE)` publication; separate
sync/cleanup knowledge. No overwrite/copy fallback exists. Global minSdk26,
compile/target35 and DirectShell Wireless Debugging API30+ policy remain unchanged.
The initial fixture is **exact API30**, not generic API30+ qualification.

These Android calls are compiled but unexecuted here. A future successful P
would not alone establish S, D, F or power-loss durability. Named negative ports
fail recovery sync or withhold acknowledgement after real native P/sync. They
do not substitute fake-positive platform behavior.

## 9. AF15 Correction And Cohort Separation

Source review found AF15 activated missing-drain knowledge before recovery,
causing the real Android path check to fail closed before opening any socket.
This was an **unexecuted fixture logic defect**, not an Android/P1 runtime failure.
It now selects the fault-capable port early but activates the fault only at the
existing PUBLISHED checkpoint after P/S, before worker cleanup. It checks actual
socket/worker exit and an independent store inspection returning BUSY, witnessing
the retained real lease. Only explicit fixture teardown resets its own fault;
it cannot invent D/F. Android runtime confirmation is still pending.

Superseded intermediate cohort `5A3CDAE400041028CEA2AED3FB45096ACA7C6FE2F94DBC4B92440720458BC2DB`
had passing host/build executions, not Android acceptance. All six source files,
four built artifacts and receipts were archived before editing. Corrected final
cohort `D839240967B17DE6C5A5B20CC9EBF0583E0342FD5B52161BF723DCB86DEF51DA`
has a distinct freeze and one declared complete JVM run. No failed full run was
repeated until PASS, and neither cohort is retroactively relabeled Android-tested.

## 10. Instrumentation Scenario Matrix

Every row is **COMPILED_NOT_EXECUTED**, not runtime PASS. Identifiers are the
actual `afNN...` methods in the compiled new suite; exact names/annotations are
in `compile-discovery.json` and javap receipts.

| ID | Scenario / required future witness |
|---|---|
| AF01 | Passive owner/readiness/request: no fixture, peer, socket or observer |
| AF02 | Existing-only missing AES reader: no key provisioning |
| AF03 | Repeated test alias generation rejected; old ciphertext still decrypts |
| AF04 | Authenticated encrypted recovery, actual file/directory/parent sync evidence before socket/pending; exact work/clock/scheduler |
| AF05 | Missing key blocks protocol/publication |
| AF06 | Independent same-process store owner excludes composition |
| AF07 | Existing whole ciphertext preserved by actual native no-replace collision |
| AF08 | Complete distinct-certificate synthetic Android R/P/S/automatic D/F and read-only restart reconciliation |
| AF09 | Cancel actual stalled TLS; same socket closes and POSSIBLE remains |
| AF10 | Cancel actual stalled PAKE; real resource drain |
| AF11 | Cancel after client peer-info: retain POSSIBLE, no rollback claim |
| AF12 | Real native rename with modeled lost P acknowledgement remains UNKNOWN |
| AF13 | Real P/sync with modeled lost S acknowledgement cannot finalize success |
| AF14 | Held real worker/recovery lease and exhausted observation retain D/admission fence |
| AF15 | Post-P/S modeled missing storage-drain acknowledgement retains actual lease despite network exit |
| AF16 | Simulated20s storage consumption leaves40s of the original60s work deadline |
| AF17 | Expiry before protocol retains recovery, no socket |
| AF18 | Expiry in TLS closes original socket without renewing budget |
| AF19 | Old close/cancel cannot affect a new ticket after pre-I/O cancellation |
| AF20 | Cloned ticket/120s consent expiry cannot dispatch |
| AF21 | Test key removed after recovery: ciphertext retained, protocol denied |
| AF22 | Authenticated channel with invalid peer-info cannot prepare |
| AF23 | Required recovery sync fault blocks protocol and retains NONE_POSSIBLE |
| AF24 | Wrong code produces no verified evidence/adoption/publication |

Restart here is a controlled reread, not process-kill or power-cut qualification.
AF06 is same-process independent ownership; inherited Android multiprocess tests
remain separately compiled, not newly executed. Controlled timing/fault knowledge
is modeled; intended Android syscall/Keystore/native/socket witnesses are real
calls but not observed runtime outcomes in this task.

## 11. Compilation And Discovery

Final Kotlin/Java AndroidTest compilation PASS. Actual annotations discovered:
storage18, lifecycle8, native8, multiprocess11, new composition24 = **69 methods
in5 suites;0 executed**. Inherited45 are unchanged. No AndroidTest APK install,
emulator, instrumentation runner, ADB server or USB enumeration was used.

## 12. Fresh Host JVM Results

Final frozen cohort: **1606/1606 in198 suites**, **422/422 focused in18 suites**,
zero failed/errored/skipped. Delta from parent:+4 methods/+1 suite in both;
the new bridge suite proves authentic Alice/Bob roundtrip, wrong-code rejection,
invalid exporter rejection and idempotent released-context refusal. It does not
test Android classloaders/Keystore. Full execution was unfiltered/noninstrumented,
normal Gradle8.12/JDK17 test workers, maxWorkers2/maxParallelForks1/forkEvery0.
Gradle launcher uses the established JDK21. No diagnostic lease agent was used.

Focused per-suite methods: fixture protocol4, Android FS24, Android Keystore10,
new bridge4, consumed-publication11, AEAD9, storage38, native publisher12,
owned protocol30, composition contract2, P1 coordinator96, deadline5, handoff18,
protocol observation41, storage integration48, synthetic composition31,
transport33, storage process6. Existing C/H/O tests/assertions are unchanged.
Fresh40/40 offline media replay PASS. Final lease receipts recorded strict release
and forced death COMMITTED_CLEANUP_PENDING, live published owner BUSY; the
historical Windows refusal mechanism remains UNATTRIBUTED.

## 13. Builds, Reproduction And Completed Guards

Final Gradle tasks PASS: verifyOwnedPairingReproduction, testDebugUnitTest,
ktlintCheck, lintDebug, lintRelease, assembleDebug, assembleDebugAndroidTest,
packageDirectShellProbeDex, assembleRelease. Some unchanged build/native tasks
were UP-TO-DATE; they are not claimed freshly recompiled. Two independent patched
AAR builds and selected artifact match the pinned hash below. No library patch
or BC upgrade occurred.

Completed fresh guards before stop:619 artifact/class/DEX closure checks with43
unrelated classes byte-identical,21 source-set/APK/manifest,34 P1-model,10 storage
compiled,14 Android-adapter compiled/discovery,6 compiled native symbols,
36 publisher source and3 publisher ELF ABI/import. P1 committed/working source
identity and inherited inputs were checked. Native inputs unchanged; CTest not run.

## 14. First Mandatory Failure And Unexecuted Gates

The aggregate stopped at `lint-semantic.ps1:24`: **New lint finding**.
`lintDebug` itself exits0, but comparison correctly detects a new warning:

```text
VisibleForTests: This method should only be accessed from tests or within private scope
app/src/debug/java/io/github/muntashirakon/adb/DebugSyntheticPairingPeerAuth.java:20
context = PairingAuthCtx.createBob(password);
```

Final Debug127 warnings versus historical134:8 previously explained removed
dependency advisories PLUS1 genuine new VisibleForTests warning. Parent had126.
Release34 versus42:only the same8 removed advisories, no new warning. No errors.
The existing archive-path normalization does not remove or explain this warning.
No suppression, dependency/source change or gate rerun was made after stop.
Read-only analysis and original XML are separately preserved. This is not an
Android/library cancellation defect and does not earn final software acceptance.

The subsequent132-classpath audit, compiled observation/composition/helper/handoff
checks and new fixture-specific compiled/DEX isolation aggregate were **NOT RUN**.
Classpath enumeration/logging and APK build happened, but are not those audits.
No complete DEBUG/Release fixture-isolation PASS is claimed. The prepared final
success verifier was not executed; stopped provenance closure is separate.

## 15. Source And Artifact Provenance

External final evidence: `C:/wg-pairing-android-api30-fixture-r1631-final-20261102/`.
`source-final.json` has individual raw/LF hashes and paths; cohort algorithm:
SHA256 of sorted UTF8 `path + TAB + LF_SHA256 + LF` rows (uppercase hex hashes).
4936 committed baseline paths are checked excluding allowed changes. Intentional
invalid-UTF8 mbedTLS fixture bytes are normalized byte-preservingly and additionally
match the exact Git-clean blob; three positive/negative controls passed. No
third-party source was edited. Documentation hashes/receipts are frozen separately.

| Source (basename; full paths in section5/manifest) | LF SHA256 |
|---|---|
| `build.gradle.kts` | `5A5D611C2A246D3414A220EE4049277F33028F8093F605A77F7AE0A7BA408A6E` |
| `AndroidCompositionPeerFixture.kt` | `F3F1E17295D0B16B55B809ACFFAD329182EC58900E8417B08E49317282512ECF` |
| `AndroidPairingCompositionBoundaryInstrumentationTest.kt` | `1159F73EC9C3D46C2ECD78DD07000E560432CBCB59EA0AD95BDF97EE39DF4320` |
| `AndroidPairingStorageFixture.kt` | `476AF02D0270F51D405092FC1037B58A6B2984A59BEF7DF168E640C947D02990` |
| `DebugSyntheticPairingPeerAuth.java` | `90098442E57B9A6BA3D9733C17F24B5D855436CFA61E63D9864D887708843C79` |
| `DebugSyntheticPairingPeerAuthTest.kt` | `EDD3DE9575C914FF20BC41F265A9CB9C5114FE6EE2B126C7009118E9D36FD8F7` |

| Final built artifact (not an accepted checkpoint) | SHA256 |
|---|---|
| Debug APK | `E0C3780923FF24A3089C0F6C0BCB066055B2D4FCA348EC071B2114D2AC384C33` |
| AndroidTest APK | `AE20657789A253F63BBC61E08C96FF2A09C69DF32AE58D2686B988AEAA161C8B` |
| DirectShell helper JAR | `E82E3E76CE0DD3B1CD2903C86B16B21DC5D5FFB71E1D6E6EC09BCE1E67D914E5` |
| Unsigned Release APK | `CB301866A90A8E6D259EAFE94CF2EDF684EA974FCC1CFC6AA165DE52242D4D0B` |
| Selected/reproduced patched AAR | `BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0` |

Artifacts are copied/hash-verified in artifact-freeze.json. No APK reproducibility
or whole-APK identity with prior checkpoints is assumed; native build roots and
tool outputs can differ. No instruction-level equivalence is inferred solely
from inherited source identity. Source was not edited after final acceptance run.

## 16. Receipt Separation And Negative History

Original October30 evidence remains `C:/wg-pairing-android-api30-fixture-r1631/`:
missing-BCTLS compiler FAIL retained. Resume/intermediate evidence remains
`C:/wg-pairing-android-api30-fixture-r1631-resume-20261102/`: clean recovery,
five-path provenance, buildSrc init-script failure/corrected separate configuration
probe, normalization-verifier negative/controls, AF15 source finding, superseded
six-source/artifact archive,1606/198 host and422/18 focused PASS,29m18s build PASS.
Those are not the corrected final cohort's acceptance. Old crash logs are
untouched, without attributed cause. A read-only receipt-summary shell syntax
error after the lint stop changed no source/evidence/gate and was not an admission.

Final cohort receipts include source freeze, declaration, full/focused XML hashes,
command/exit/execution metadata,69-method compiled discovery,40 replay,
reproduction, artifact copies, completed guards, pipeline STOPPED_FIRST_FAILURE,
original lint XML and separate read-only lint-negative analysis. The first negative
is not rewritten PASS. Earlier396 sealed r1629/r1630 receipts are rechecked.

Retain P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED/r1615 and approved successor contract,
r1628 regex/r1629 schema negatives, strict historical lint differences, separate
Release-unit180-diagnostic/11-file limitation, prior TLS-write/bootstrap/USB/SDK
observations, native-layout differences, G1-G5 partial hardware/media debts and R2.
Intermediate configure emitted CXX5304; final native configure was up-to-date,
not proof of SDK repair. No SDK/cache/flags were changed to suppress it.

## 17. Runtime And Production Limits

No new Android filesystem/fsync/Keystore/native publication/combined drain behavior
was executed. No hardware-backed AES, power-loss resilience, real adbd, endpoint
authentication, Android suspend/connect or blocked TLS-write claim is earned.
Host positive directory sync remains modeled in inherited C/H tests; that modeling
does not replace the actual Android port in AF source. Peer success is synthetic
PEER_INFO_VERIFIED with POSSIBLE effect, never persistent Android AUTHENTICATED
trust, Warpnect Session authentication, Host acquisition or credential adoption.

## 18. Separately Authorized Future API30 Plan

First resolve/review the new test-only-visibility warning without suppression or
pinned-library changes, then obtain a new verified source cohort/software gates.
Only after separate runtime authorization: identify one disposable exactAPI30
fixture by build fingerprint/ABI; verify normal app UID/SELinux domain, dedicated
test roots/aliases and no production records. Freeze target/test APK/signer/source
hashes and expected69 methods. Prove transport isolation; the historically
ineffective ADB device filter alone is insufficient. No personal-device fallback.
Authorize fixture-only installation, rollback, cleanup and operations explicitly.

Execute narrow preflight before full synthetic composition; capture actual
fsync/native errno, lease/FD release, AES properties, socket/worker/observer drain
and each executed method/outcome. Stop on unexpected authority or unsupported
primitive, retain uncertain state, never weaken an assertion or automatically
repair a key. Cleanup only owned synthetic aliases/root after physical drain.
Emulator evidence would not qualify OEM/physical devices or power-cut durability.
No such execution is performed or automatically scheduled here.

## 19. Remaining P2 Blockers

Software fixture acceptance is blocked by the new lint finding and unexecuted
remaining isolation gates. Independent later requirements remain: actual combined
Android storage/native/Keystore/drain execution; production D-P1-D-P4 RSA/AES
identity lifecycle; application owner/clock/timer wiring; Network/NSD endpoint
admission; actual adbd interoperability; Android write/suspend/connect behavior;
API/OEM/device qualification; Release onboarding. No P3-P5/Phase7 progression.

## 20. Smallest Next Increment

Recommendation only: separately authorize a narrow review/correction of the new
DEBUG synthetic bridge's test-only visibility declaration so its intended use
agrees with the pinned createBob testing contract, without suppressions or library
changes. Verify the real lint result and remaining isolation gates against a new
source freeze, preserving this negative. Do not automatically start Android runtime
testing, production provisioning or broaden the library patch.

## 21. Final Git And Closeout

HEAD remains the verified parent; index empty; six source/build/test plus this
report, CURRENT_HANDOFF and FutureRoadmap = nine uncommitted paths. No commit or
generated artifact is staged. Final source bytes match the freeze; documentation
is the only post-execution edit. All10 observed test JVM/child PIDs have no surviving
owned process. Protected observable state matches the Nov2 recovery baseline,
with the six unavailable-Git locations and four metadata-only exceptions disclosed.
Exact final proof is external `FinalReport.md` / `final-provenance.json`; neither
is an accepted all-gates-PASS receipt. Prior checkpoints and RFCs are untouched.

## 22. Classification

Retain P2_OWNER_DRAIN_HANDOFF_CHECKPOINT_VERIFIED,
P2_SYNTHETIC_COMPOSITION_CHECKPOINT_VERIFIED, P1_ATTESTATION_DRAIN_CHECKPOINT_VERIFIED,
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED,
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED,
P2_WINDOWS_LEASE_TEST_CONTRACT_IMPLEMENTED_SOFTWARE_TESTED and
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED. This increment is an implemented/compiled
fixture with **software acceptance blocked**, not Android VALIDATED, hardware
QUALIFIED or PRODUCTION READY. **P2_PARTIAL_BLOCKED; Phase6.5 incomplete.**
