# DirectShell Pairing Protocol Adapter Feasibility

## 2026-10-28 Verified Local Checkpoint Successor

The [amended checkpoint](DirectShellPairingPatchedLibadbIntegration.md#2026-10-28-amended-candidate-local-checkpoint)
consolidates the approved implementation and Windows test-contract amendment,
source9576fdca42b9c0b709ddd2af51464f3464324fd9 plus its direct documentation child.
P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED: fresh1510 full/326 focused JVM,40 replay,
required builds/lint, deterministic AAR/class closure and legacy/Release isolation
PASS. No new production source delta, protocol patch or dependency update.
This review's old status and implementation receipts below remain historical.
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED retains exact localhost scope;
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED remains.45 Android methods compile,0 execute;
no adbd/hardware/Release qualification. Overall P2_PARTIAL_BLOCKED. Synthetic
joint composition NOT STARTED and requires separate authorization; no P3-P5.

## 2026-10-28 Explicitly Approved Implementation Successor

The user subsequently approved exactly the DEBUG artifact/class substitution
gated by this review. The [implementation receipt](DirectShellPairingPatchedLibadbIntegration.md)
now records P2_LIBADB_PATCH_PACKAGING_VERIFIED and
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED, exact host-localhost scope.
libadb is retained; original API/transitives/non-pairing classes are preserved.
The actual selected AAR, authentic connected-socket protocol and original P1
deadline handoff pass1507 full/323 focused JVM,40 replay and required software
gates. No production storage/Host wiring, Android transport test or real trust
effect. Blocked TLS-write qualification remains open. Overall P2_PARTIAL_BLOCKED.
17 source and six documentation paths remain uncommitted on the isolated child;
the original review worktree remains unchanged. This successor does not rewrite
the historical feasibility decision or accepted RFCs below.

2026-10-28. **P2_TRANSPORT_CANCELLATION_CHECKPOINT_VERIFIED** (Task A).
**P2_ADB_PROTOCOL_ADAPTER_DESIGN_REVIEW_REQUIRED** (Task B).
Overall **P2_PARTIAL_BLOCKED**; Release onboarding unavailable.

No Task B application, test, dependency or build input was changed. This is the
explicit feasibility stop allowed by the task, not an implemented adapter or a
safety refusal. The bounded authorized localhost development scope remains
permitted. The earlier assistance stop and its superseding reassessment remain
unchanged in [the transport report](DirectShellPairingTransportCancellation.md).

## 1. Verified Checkpoints And Scope

Task A started from clean-source parent
`ffe1d1bc38ce88e450fb9fc6becb42a71ab4653b`, with exactly the previously reviewed
five application/build/test and four documentation changes. All raw hashes
matched the original receipt; Git index and committed blobs matched their
LF-normalized contents. CRLF in `app/build.gradle.kts` is not a source delta.
No artifact was regenerated to force a match.

| Local commit | Exact scope |
|---|---|
| `c5577c776300d99d2b69a00b99584295b0f06184` | `feat(directshell): add operation-owned cancellable pairing transport`; five paths below |
| `be6beb9453b09f44340233ffbd3eca2577ef1c97` | `docs(phase6_5): record pairing transport cancellation validation`; four documents below |

Source/build/test manifest and LF-canonical SHA256:

| Path | SHA256 |
|---|---|
| `app/build.gradle.kts` | `7FD32D768A588AA397F36D9A1A0EA695B804117B494E55988B55FFFCA5F8F83D` |
| `app/src/debug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingTransport.kt` | `9EB41DCCD947CF473A80038124E650364DE2A7E19582DF407031BE113C24C299` |
| `app/src/testDebug/java/io/github/muntashirakon/adb/PairingAuthFixture.java` | `70746724F654B0440930D1A3A8B461795ECB63DB31814CDB014BDA697F25D48A` |
| `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingTransportTest.kt` | `4682F1AD18B9A0695CFB791846CC37D5D1C875509B451DD99D84C28FF919EFF8` |
| `app/src/testDebug/java/io/warpnect/platform/privileged/directshell/pairing/PairingTransportFixture.kt` | `DDD398818588C3A77C1ABF8D2DA0FBD35DF6223B294552A306C2C54537764091` |

Five-path cohort: `9476B92D0D37F8E9DC9EC6FBAF52265273526C5C664F17C02183C089DE3CDC06`.
Documentation checkpoint contains exactly `docs/FutureRoadmap.md`,
`docs/phase6_5/CURRENT_HANDOFF.md`,
`docs/phase6_5/DirectShellReleasePairingArchitecture.md` and
`docs/phase6_5/DirectShellPairingTransportCancellation.md`.

Fresh verification before committing: 234 integrity/provenance checks; 26
source-index, 25 documentation-index and 32 committed-closure checks. The
transport worktree is clean, index empty, with exactly those two local commits.
The prior 251-check receipt was inspected and preserved, not relabeled as a
fresh execution of that older checker.

Task B branch: `codex/phase6-5-pairing-protocol-adapter`.
Worktree: `C:/Users/Francy/.codex/worktrees/phase6-5-pairing-protocol-adapter/Warpnect`.
Parent/HEAD: `be6beb9453b09f44340233ffbd3eca2577ef1c97`; initially clean.
Task B remains uncommitted and documentation-only. No old dirty P2 patch/source
was imported. External receipts: `C:/wg-pairing-protocol-r1613/`.

## 2. Pinned Dependency And Actual API

Fresh offline Gradle `dependencyInsight` resolves DEBUG runtime
`com.github.osservatorionessuno:libadb-android-bc:3.4.0`, unchanged.
Original cached AAR SHA256:
`62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB`.
Its `classes.jar` SHA256 is
`E230CF15535F44551DDFFF6058EA99209AC4560F7C4210C1B2E563FCB4A8D8DE`;
fresh ZIP comparison proves the API-probed JAR is exactly the AAR entry.
No external artifact resolution or dependency update was performed by that check.

Pinned public source revision: `dcd931071b5e4b9ebc0ac598201755d5a8814b71`.
The preserved upstream clone has an old modified context file. All original
source inspection used `git cat-file blob <pin>:<path>`, not that working file.
Original context: 437 lines, 17,797 bytes, SHA256
`9E7B43ECCCC21B9DC75146BB7AEAEF9F788404EECD95943061512878E9A7D899`.
Hashes for manager, PAKE and AndroidPubkey are in `library-api-audit.json`.

`PairingConnectionCtx` is final. Its public constructors accept host/port/code
bytes/key/certificate/device-name, not a socket, stream or transport factory.
`start()` is synchronous and returns void. `setupTlsConnection`, framing,
SPAKE2 exchange, peer-info exchange, TLS client and peer-info classes are private.
`PairingAuthCtx` is package-private; the test shim accesses real PAKE arithmetic
but cannot invoke those private context stages.

Fresh compile-only positive control: existing `start()` and `close()` compile.
Negative control: `start(ownedSocket)` and `getAuthenticatedPeerInfo()` fail with
the two expected missing-API diagnostics against the exact original JAR.
No probe is executed; no socket, identity, key, TLS session or peer is created.
This proves the proposed API is absent, not that patching is impossible.
Seventeen source/API checks and three package/provenance checks pass.

## 3. Actual Call Graph And Ownership

Numbers refer to the immutable upstream source captured in external
`original-*.java`, not to the old modified clone.

```text
Existing DEBUG DirectShellSelfBootstrapController.pair [app lines 88-93]
  -> ensureManager().pair(host, port, immutable String code)
  -> AbsAdbConnectionManager.pair [524-535], caller thread, manager mLock
     -> local PairingConnectionCtx, try-with-resources [527]
     -> start [98], SAME caller, no library worker created
        -> setupTlsConnection [135]
           -> local new Socket(host, port) [140], implicit blocking connect
           -> local BC TlsClientProtocol over that socket
           -> private AdbTlsClient, protocol.connect [151], TLS 1.3
           -> notifyHandshakeComplete [339]: export adb-label\0, 64 bytes
           -> only THEN assign mInputStream/mOutputStream [154-155]
           -> PairingAuthCtx.createAlice(code || exporter) [167]
        -> doExchangeMsgs [204]: framed client SPAKE2 write, peer header/read
        -> doExchangePeerInfo [228]: encrypted client identity write [241],
           peer header/ciphertext read, decrypt, fixed-size check
        -> private parsed peer-info logged [265]; no structured result retained
     -> context.close [270], streams and PAKE destroy
     -> manager boolean true, or exception
```

| Boundary | Resource owner / interrupt path | Knowledge limitation |
|---|---|---|
| TCP connect | Context-local socket; caller blocks in constructor | No injected connect timeout/owner-accessible close in original pairing path |
| TLS handshake | Local BC protocol/client; same socket/caller | Context stream fields still null; manager close does not reach this socket |
| SPAKE2 / peer-info read/write | Context stream fields and PairingAuthCtx, same caller | Stream close later becomes possible, but concurrent PAKE destruction is not a safe owner/drain protocol |
| Cancellation | Manager `close` [542] destroys its private key and closes ordinary `mAdbConnection` | It does not retain the local pairing context; neither a stop callback nor Future cancellation proves drain |
| Code | Legacy DEBUG immutable String -> mutable bytes held by context | `close` clears password bytes; String, combined temporary buffer and exporter do not provide the new operation's full zeroization accounting |
| Identity | Existing manager supplies local private key/certificate/RSA public key | TLS client signs using encoded private-key bytes; no production identity should be created/imported by an adapter |
| Completion | Void context start / manager boolean | No safe typed endpoint/GUID/certificate result for P2 storage |
| Drain | Caller return plus try-with-resources only | Current manager has no independent worker-termination receipt for P1 |

The separate new Warpnect component owns one raw socket, one IO worker, one
close worker and a timer. Its Protocol port receives that already-connected
socket; stop closes it. Calling the original context from this port would create
another socket, defeating that guarantee. No such wiring is present.

## 4. Protocol Semantics And Trust Boundary

Original TLS is BC low-level TLS 1.3, not an interchangeable ordinary JSSE
handshake. Exporter bytes are captured during handshake completion, before BC
discards the exporter secret. Server certificate acceptance alone is not endpoint
authentication: SPAKE2 binds the user code to this TLS exporter. Client TLS
credentials use the supplied local RSA key/certificate and RSA-PSS/SHA256 signer.
The package-private implementation provides SPAKE2, HKDF and pairing AES-GCM;
no new crypto algorithm is needed or authorized.

Wire framing is six bytes, version 1, message types SPAKE2=0 / PEER_INFO=1,
payload 1..16,384 bytes; decrypted peer-info is exactly 8,192 bytes. Client
peer-info contains the Android RSA public-key encoding. Original peer-info
parsing checks size but not the required semantic GUID type/content; both
`ADB_RSA_PUB_KEY` and `ADB_DEVICE_GUID` constants are 0 in this revision, and raw
peer-info is logged. An adapter cannot treat void return as the typed, validated
identity required by the storage contract. Correcting validation/log exposure
would be a separate reviewed part of the same context patch, not a new protocol.

Potential irreversible remote trust effect is NOT a successful client callback.
A real daemon may accept the client key after receiving encrypted client
peer-info, before its reply is consumed. Exact daemon persistence timing is not
measured here. Admission remains conservatively POSSIBLE; no missing return,
timeout, cancellation or later local storage failure proves remote rollback.
Retained recovery identity must precede future trust-affecting IO. PAKE success,
Android trust, local storage publication, Warpnect Session authentication and
COMPLETE Host acquisition remain different authorities.

Existing host fixtures use genuine BC TLS/exporter and pinned PAKE mathematics,
but synthetic peer-info and no production client-certificate request/identity.
Their FIXTURE_VERIFIED result is not an authentic context/adbd success. This
increment deliberately does not promote it or construct authenticated credentials.

## 5. Strategy Decision Before Implementation

| Strategy | Finding | Decision |
|---|---|---|
| A: existing socket/stream injection | Absent in source and cached bytecode; final class prevents subclassing | Cannot implement against unchanged API |
| B: reusable context stages | Private TLS/framing/peer-info; only package PAKE math reusable | Copying the remaining protocol or using reflection is not an acceptable transparent adapter |
| C: narrow context patch | Technically plausible, prior host overlay is relevant evidence | Select for explicit dependency/packaging review; do not integrate implicitly |

The earlier feasibility patch is one source file, **122 added / 19 removed
lines**, SHA256
`81C35CC486EC195023029A48D820012D4CC7F03ED51E8584433CF3F2685830D9`.
It is read-only evidence, not reviewed production code. Historical r1601 receipts
show a patched-context host overlay interrupted TLS/PAKE reads and completed a
synthetic authenticated exchange. The cached original remained blocked about
328ms after close and required independent peer cleanup. Those old executions
were NOT rerun or attributed to this increment.

That patch expects an **unconnected** externally supplied socket and introduces
12s stage limits. The new transport supplies an **already-connected** socket and
requires the original absolute P1 deadline; copying that patch unchanged is
incorrect. Its modified legacy constructors also need compatibility review.
No such changes were applied here.

### Packaging Is A Real Dependency Decision

The original AAR contains `PairingConnectionCtx.class` and six nested class files.
Adding a replacement with that FQCN to app DEBUG source would leave duplicate
definitions. A JVM classpath overlay can mask the original for host experiments
but is not Android DEX/source closure. Renaming/copying the 437-line protocol
would create a maintained copied implementation; the test shim alone does not
avoid this. Reflection cannot inject the local variable before blocking TLS.

Correct APK closure needs either an upstream artifact exposing reviewed APIs,
or an explicitly approved, reproducible DEBUG-only patched artifact/class-closure
substitution with original dependency/class manifests and license notices.
The prompt separately gates dependency replacement/copied protocol/full forks.
No approval for that artifact change is inferred from permission to evaluate a
narrow patch. There is no demonstrated need for a broad crypto/library rewrite;
the stop is the exact source-plus-packaging review, not technical impossibility.

Upstream files are dual licensed GPL-3.0-or-later OR Apache-2.0. Any future
derived patch/package must retain provenance, satisfy the selected license and
record maintenance/ABI risks; this is not a blanket licensing/security audit.
Keep original constructors and all non-pairing class bytes unchanged if that is
the approved route; add an opt-in API, not a behavior change to legacy DEBUG use.
Current Warpnect call sites only use the original manager path; leave them alone.

## 6. Minimum Proposed Delta (NOT IMPLEMENTED)

1. Approve packaging first: exact pinned artifact, paired context/nested-class
   closure, deterministic transformation or rebuilt artifact, DEBUG-only scope,
   hash/license/change manifest, no generic library update or Release promotion.
2. Add an opt-in connected-socket protocol entry retaining original TLS/exporter,
   PAKE and wire operations. Validate socket state/exact admitted endpoint; do not
   create/connect another socket. Preserve legacy constructors and their behavior.
3. Pass a CPU-only owner stop/deadline checkpoint before each new protocol
   read/write stage and after blocking returns. Already-entered IO may race stop;
   raw close remains the authoritative cancellation path. No arbitrary callbacks
   under a new library lock. One worker owns protocol computation and final
   destruction; cancellation must not concurrently destroy PAKE/key state.
4. Return immutable internal validated peer evidence: correct type, bounded
   strict GUID, authenticated endpoint/TLS certificate binding and local-identity
   association. No raw peer-info logging or exception propagation. Expose only
   safe summaries to UI/receipts. Synthetic fixture success remains fixture-only.
5. Adapt the existing Protocol port only after class closure is buildable; record
   owned socket continuity, release knowledge and worker termination separately.
   Do not publish credentials, activate a provider or enable Host.
6. Add the narrow owner deadline handoff below, then execute A01-A20 and the
   required full software/build/isolation matrix on that actual source cohort.

## 7. Authoritative P1 Deadline Handoff (PROPOSED)

Current P1 `DirectShellPairingCoordinator.submitCode` is synchronized. At lines
107-108 it calculates `after(WORK_MS)` and stores `Attempt.workDeadline`, before
`backend.create` and `operation.start(code)` [112,121]. The backend/operation
contracts [contract lines 167-176] expose neither deadline nor owner clock.
The stored deadline is private, not part of the ticket. Computing `now+60s`
inside a delayed backend would extend consent/work and is prohibited.

An additive internal, owner-created immutable work context is sufficient in
principle; no reservation/publication change is identified. Proposed shape:

```kotlin
// Proposed contract sketch, not application source or an approved API.
internal interface DeadlineAwarePairingOperation : DirectShellPairingOperation {
    fun start(code: DirectShellPairingCode, work: OwnerPairingWorkContext)
}
// OwnerPairingWorkContext: original ticket identity, exact absolute
// deadlineElapsedRealtimeMs, original suspend-inclusive clock/scheduler.
// Constructed only for this submission by the enrollment owner.
```

P1 would dispatch the optional new interface with its existing absolute value;
legacy fakes/operations retain existing `start(code)`. The new adapter's legacy
entry must fail closed, never fabricate a new budget. Context is metadata, not
consent, reservation or storage authority. Ticket/clock/deadline mismatches and
expired work fail before IO. All dispatch must remain CPU-only under the monitor;
protocol construction/identity validation/crypto/network run on the owned worker.

Check the same absolute cutoff before connect, TLS, each message stage, result
and finalization; scheduled expiry closes the same socket. Read inactivity
timeouts do not renew it. Use injected suspend-inclusive elapsed time, not
`System.nanoTime` for enrollment semantics. Existing 120s/60s/3s, no queue,
single attempt, revision-zero/no-replacement, R/P/S/D/F and cleanup fences stay
unchanged. `nanoTime` remains only the existing bounded host join observation.
Late verified protocol knowledge can coexist with expired/cancelled lifecycle;
neither success nor timeout proves drain or grants P1/storage authority.

Do not implement this otherwise-unused P1 amendment in isolation merely to make
the adapter task appear partially complete. It belongs to the dependency-closed
approved protocol integration and needs the existing 96 model regressions.

## 8. Acceptance Matrix For The Approved Follow-up

All **new adapter** A01-A20 executions are NOT EXECUTED here. Existing transport
tests were rerun against unchanged source; they do not qualify absent wiring.

| Adapter cases | Required discriminator beyond current transport evidence |
|---|---|
| A01/A20 | Construct/inspect actual adapter: no socket/worker/store/Host/provider/Session side effect |
| A02/A03 | Prove original patched protocol entry and same connected socket across TCP/TLS/PAKE; one socket factory, no hidden connect |
| A04/A05 | Actual patched-context TLS and PAKE-read stall, same raw close, observed worker exit; old overlay is historical only |
| A06 | Real bounded TLS/PAKE write backpressure if reproducible; otherwise explicit unverified boundary, not raw-TCP equivalence |
| A07/A08 | Actual context wrong code; frame/type/version/size/tag/EOF and GUID rejection without credential grant |
| A09 | Synthetic full exchange requesting client certificate and checking real Android RSA encoding; fixture-only result |
| A10/A15 | Barrier after client peer-info / before response; cancellation retains possible remote effect and any later verified knowledge |
| A11/A12 | Delayed backend, consumed/expired work, interstage clock advance, late completion: unchanged original P1 deadline |
| A13/A14 | Repeated stop, held close/protocol worker, 3s observation expiry: no false drain or second attempt |
| A16 | Old ticket completion cannot change newer owner; unresolved old resources retain fence |
| A17 | Preserve released-code fail-closed regression before any worker/socket |
| A18/A19 | APK/DEX/class-closure/Release guards, all worker/socket/TLS/PAKE/code resources drained |

Use barriers/injected elapsed clocks, not sleeps as ordering proof. Combine
transport D, storage D and code-copy D only in a later explicit composition.
An authenticated response is not storage P/S, coordinator F or physical D.
Native connect blackhole, Android TLS write cancellation and suspended-clock
behavior remain separate platform evidence, not inferred from host success.

## 9. Actual Verification And Artifact Provenance

| Gate | This increment |
|---|---|
| Full Debug JVM | FRESH 1472/1472, 191 suites, zero failures/errors/skips; delta 0 |
| Focused P1/storage/transport JVM | FRESH 288/288, 11 suites, including 96 P1 and 33 transport; delta 0 |
| Offline audio replay | FRESH 40/40; no media source change |
| Source-set / P1 model | FRESH 21/21 and 34/34 |
| Compiled storage / Android adapter guards | FRESH 10/10 and 14/14; discovery is compiled-only |
| Transport isolation / native symbol guards | FRESH 39/39 and 6/6 against unchanged built classes/three-ABI libraries |
| Dependency/API/package analysis | FRESH offline Gradle resolution, 17 source/API + 3 package checks; expected two-diagnostic negative compile retained |
| ktlintCheck / lintDebug / lintRelease | Prior same-source PASS retained; not rerun for documentation-only feasibility |
| Debug / AndroidTest / helper / Release builds | Prior frozen same-source PASS retained; no new APK generation required |
| New adapter tests | 0; no adapter implementation claimed |
| Android instrumentation / emulator / device / CTest | NOT EXECUTED |

Both fresh JVM tasks actually executed (`--rerun` on the test task), with 25
other tasks up-to-date each; not stale XML interpreted as new executions.
The known Windows child-process lease failure did not recur in the planned
focused/full runs; its cause remains unresolved. The historical extra Release
unit-test failure (180 diagnostics/11 unchanged DEBUG-dependent tests) remains
FAIL, not rerun, fixed or represented as assembleRelease success.

The four frozen artifacts were rehashed before checkpoint and match exactly:

| Artifact | SHA256 |
|---|---|
| Debug APK | `37BC4CF7D00A5A40F46A39B5047FFC88B93A90479FE18A6E1364AF20B31DECAD` |
| AndroidTest APK | `D2A6FD9A78422E5BEDC029E8CF4302F9A703FDE688FF15B7B0437CA050123B7C` |
| Helper JAR | `687FA1D1CF758C8A9F93E3935F39E3FA8E595CDE30B11F5F168FDF0AEE9A7CBF` |
| Unsigned Release APK | `AEC4BCA2D3A303FAF1A16B574810DBA6531CBFD4E9A266D15C50309A8C2491F0` |

Task A commits -> verified same LF source -> frozen prior APK/JVM/build receipts
and fresh JVM executions is the chain. There is no new adapter APK, Android
protocol test, installation or pairing Session. All earlier r1601/r1610/r1611/
r1612 failed controls, SDK timestamp-only deviation and safety-stop history are
preserved; no historical receipt is overwritten.

## 10. Preservation, Status And Next Decision

Task A preservation compares 17 protected worktrees before/after the authorized
checkpoint. Task B inventories 19 total, excluding only this new worktree:
18 protected, including the clean transport checkpoint. Original research has
496 dirty paths; four large artifacts retain the existing metadata-only scope.
HEAD/branch/index/dirty-file hashes/recorded metadata, master/upstream refs and
Phase7 R2 are compared. No reset, stash, clean, broad source import, push, merge,
Task B commit or historical evidence deletion is authorized or performed.

Task B changes only this report plus successor entries in FutureRoadmap,
CURRENT_HANDOFF, ReleasePairingArchitecture and TransportCancellation. Accepted
RFCs, P1/storage/native/media, SDKs/manifests/provider order and all five frozen
source/build paths are unchanged. Global minSdk26, API30+ DirectShell Wireless
Debugging eligibility and unavailable Release setup adapter remain intact.
G1-G5 hardware acceptance stays PARTIAL; historical audio/video debts OPEN.
No P3-P5/Phase7 work, production credentials, ADB server, USB enumeration, NSD,
emulator or Android device access. No new TLS/PAKE peer was run in Task B.

**Recommendation:** approve one bounded DEBUG-only dependency-packaging decision
for an additive connected-socket context extension, then implement that exact
class closure + authoritative P1 deadline handoff + actual protocol adapter and
A01-A20 tests in a separate increment. Require all other library class bytes/
dependencies unchanged, explicit license/hash provenance and Release exclusion.
Do not approve a general libadb fork or change the legacy manager by implication.
If dependency substitution is declined, leave the adapter unavailable and seek
the equivalent upstream API; do not use reflection or fixture crypto as a shortcut.

Task A: IMPLEMENTED / SOFTWARE TESTED / LOCALLY CHECKPOINTED.
Task B: DESIGNED at feasibility level, NOT IMPLEMENTED / SOFTWARE TESTED as an
adapter, NOT Android VALIDATED, QUALIFIED or PRODUCTION-READY.
Overall P2_PARTIAL_BLOCKED. Phase6.5 is not complete.

## Final Verification Receipt

Fresh `C:/wg-pairing-protocol-r1613/final-integrity.json`: **244 checks PASS**,
including exact committed/source/XML/artifact provenance and the five-document
Task B manifest. Task A's 17-worktree before/after comparison and Task B's
18-worktree start/final comparison both PASS in their recorded scope. Original
research remains 496 dirty paths; master/upstream and Phase7 R2 hashes match.
Final host-only process/port snapshot: no test JVM worker and no listener on
5037/5055/5666/5667. No Android query was needed or issued. Task B HEAD is still
be6beb9453b09f44340233ffbd3eca2577ef1c97, index empty, exactly five documentation
paths uncommitted; the separate transport checkpoint worktree remains clean.
