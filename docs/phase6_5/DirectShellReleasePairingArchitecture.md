# DirectShell Release Pairing Architecture

## 2026-11-12 Debug Evidence, Release Boundary Unchanged

[r1702](DirectShellDebugPairingR1702.md) demonstrates the existing Debug Host-UI
path through real adbd TLS/PAKE, authenticated-GUID discovery, reconnect,
helper HMAC and shutdown on one A41/API31. It does not exercise the pair-only
P1/P2 Android fixture or authorize using the Debug bootstrap as Release
enrollment. Production provisioning, Android owner/clock, Network/NSD binding,
blocked-write/suspend behavior, device qualification and Release onboarding
remain open. Overall **P2_PARTIAL_BLOCKED**.

## 2026-10-28 Development Checkpoint, Release Still Unavailable

The [amended local software checkpoint](DirectShellPairingPatchedLibadbIntegration.md#2026-10-28-amended-candidate-local-checkpoint)
earns P2_PATCHED_LIBADB_CHECKPOINT_VERIFIED with source
9576fdca42b9c0b709ddd2af51464f3464324fd9 and a direct seven-document child.
Fresh1510 full/326 focused JVM,40 replay, required builds/lint/reproduction,
actual DEX/module/legacy API and P1/storage/native guards PASS. Only the two
approved host test files change after the frozen patched-libadb implementation;
no production contract, endpoint, provider, storage or onboarding modification.

Release setup remains unavailable. libadb3.4.0/BC1.84 and its patch stay DEBUG-only;
main contains only the existing inert storage/model boundaries. Global minSdk26
and API30+ Wireless Debugging eligibility remain.45 Android methods freshly
compile,0 execute; no new Android/OEM/adbd, key-lifecycle or power-loss claim.
P2_WINDOWS_LEASE_FAILURE_UNATTRIBUTED remains. Overall P2_PARTIAL_BLOCKED;
synthetic P1/storage/transport composition NOT STARTED. Its bounded synthetic
authorization is the next review, not real pairing or P3-P5 activation. Earlier
architecture and accepted RFCs are not silently amended by this checkpoint.

2026-10-28 [approved implementation successor](DirectShellPairingPatchedLibadbIntegration.md):
P2_LIBADB_PATCH_PACKAGING_VERIFIED and
P2_ADB_PROTOCOL_ADAPTER_IMPLEMENTED_SOFTWARE_TESTED for the exact localhost
host fixture. A reproducible DEBUG-only context patch retains libadb3.4.0,
original APIs, transitive functionality and43 unrelated class bytes. The adapter
borrows the existing owned socket and original P1 work deadline; no second
coordinator, consent, storage publication or Host authority is introduced.
1507 full/323 focused JVM,40 replay and applicable build/static/package gates
PASS. Android transport, blocked TLS writes, real endpoint/identity/key lifecycle
and combined production storage transaction remain separate gates. Release setup
and backend registration remain unavailable; no library/BC promotion.19 protected
worktrees preserved; all changes uncommitted. Overall P2_PARTIAL_BLOCKED; this
does not amend accepted RFCs or authorize P3-P5. Earlier review states are historical.

2026-10-28 [protocol adapter feasibility successor](DirectShellPairingProtocolAdapter.md):
transport checkpoints c5577c776300d99d2b69a00b99584295b0f06184 (five source paths)
and be6beb9453b09f44340233ffbd3eca2577ef1c97 (four documents) verified; fresh1472/
288 JVM and40 replay PASS. P2_ADB_PROTOCOL_ADAPTER_DESIGN_REVIEW_REQUIRED: original
libadb3.4.0 does not expose owned socket/typed peer operations. A narrow context
patch is plausible, but APK class closure requires separately reviewed DEBUG-only
dependency packaging. No copied protocol, artifact substitution or new P1 API
was implemented. Authoritative original-deadline handoff is part of that proposed
delta, not permission to reset60s. Overall P2_PARTIAL_BLOCKED; no Release setup,
normal Host composition, Android transport qualification or P3-P5 advancement.
The original architecture below is retained; this is not an accepted RFC amendment.

2026-10-28 permitted-continuation successor: the prior assistance stop was
reassessed as overly broad, not an architecture defect. The
[transport receipt](DirectShellPairingTransportCancellation.md) records a standalone
DEBUG/host-loopback component IMPLEMENTED/SOFTWARE TESTED:33 transport,1472 full/
288 focused JVM,40 replay and required builds/lint/isolation PASS. It supplies
operation-owned raw-socket cancellation and truthful joint worker/protocol/code
drain, not a production libadb Protocol adapter or authenticated credential grant.
Five application/build paths are uncommitted; BCTLS1.84 exposure is testDebug-only.
No main/P1/storage/SDK/provider/Release or accepted RFC change. Android, native
stalled-connect/TLS-write qualification, exact owner deadline handoff and actual
protocol/endpoint/local-identity binding remain open. The unchanged manager is
not made cancellable by this component alone. Overall P2_PARTIAL_BLOCKED; Release
setup unavailable. No devices, trust changes, NSD, real pairing or P3-P5. Historical
sections below retain their original scope; they do not override this successor.

2026-10-28 checkpoint successor: multiprocess source/tests are locally committed
at5f1fe26546e969f455fe8297993c6fcef2d26533, documentation at
ffe1d1bc38ce88e450fb9fc6becb42a71ab4653b. The subsequent isolated transport attempt
is incomplete/unverified and was stopped at an assistance safety boundary, not
an architecture finding. [Stop receipt](DirectShellPairingTransportCancellation.md).
No new transport runtime evidence, backend integration or Release availability
follows. P1/storage-production/accepted RFC/media contracts remain unchanged;
overall P2_PARTIAL_BLOCKED. Use the clean checkpoint as the tested baseline.

Latest2026-10-28: [multiprocess storage qualification](DirectShellPairingMultiprocessStorageValidation.md)
continues local publisher checkpoints4378f06/5d262e4 on an isolated uncommitted
DEBUG/test branch.45/45 actual API30 AOSP x86_64 methods,1439/255 JVM,40 replay and
required software gates PASS. Two independent ordinary same-UID app processes
demonstrate the OS lease, retained-record/reconciliation and physical drain
contract; this is not a second production coordinator or general multiprocess
pairing support. P1, encrypted records, main storage/native implementation,
planner, media and Release composition are unchanged. The service is non-exported,
explicit-instrumentation-only and absent from Release; no real PAKE/NSD/Host
operation. Overall P2_PARTIAL_BLOCKED and Release setup unavailable. ARM/OEM/
power-loss, real transport cancellation/endpoint identity and production key
lifecycle remain separate gates. Review/checkpoint the fixture before any next
bounded implementation; no automatic P3-P5. Older statements below are historical.

Latest2026-10-27: [native publication integration](DirectShellPairingNativePublicationIntegration.md)
is implemented/software-tested with an exact API30 emulator transaction/lifecycle
validation,34/34 methods. A separate API30 library keeps min26 SCL dependencies
unchanged and loads only in the explicit storage worker. Release may package
dormant storage code but its setup adapter remains unavailable and no real
backend/runtime/helper is registered. P1 policy/crypto/namespace are unchanged;
1435/251 JVM and required gates PASS. Overall P2_PARTIAL_BLOCKED: independent-
process Android lease, OEM/ARM/power-loss, production key lifecycle, endpoint/
NSD/real PAKE/physical cancellation remain open. No P3-P5 authorization follows.

2026-10-27 storage successor: the [Android adapter](DirectShellPairingAndroidStorageAdapter.md)
is implemented/unwired with public app-private OS operations and existing-key-only
AndroidKeyStore AES256 handles, not exportable key bytes. P1 R/P/S/D/F contracts
and both checkpoints are unchanged. Final1383 full/199 focused host JVM evidence
is in that receipt; API26 operations fail closed (public CLOEXEC requires27);
26 platform methods compile but have NOT run. No supported Release adapter,
production key generation, NSD, real PAKE, credential adoption or runtime
registration is supplied. Android platform/durability/physical-drain qualification
remains pending and overall P2_PARTIAL_BLOCKED. No new RFC policy is accepted.

2026-10-26. Repository-grounded RFC-006J implementation proposal, not a new RFC
identifier and not authorization to activate a Release backend.

2026-10-27 scope note: the [user-approved pure P1 reservation/receipt amendment](DirectShellPairingReservationModel.md)
is IMPLEMENTED/SOFTWARE TESTED on a separate isolated branch, without a real
backend. The original proposal's publish/cancel cutoff below is historical P1;
the amended model uses R (one-shot reservation), a typed storage receipt and
independent physical drain. This is not permission to put filesystem work under
the owner monitor, adopt DEBUG identity or activate Release. Accepted RFC text
and the separate review/P2 worktrees remain unchanged.

## Decision And Status

The normal Host DEBUG pairing entry is **IMPLEMENTED / SOFTWARE TESTED** and
locally checkpointed. Release enrollment is **DESIGNED / PROPOSED**, not
implemented, hardware validated, qualified or production-ready.

Keep the current Release `HostPairingSetupAdapter` unavailable. Promoting the
DEBUG Activity or changing `debugImplementation` to `implementation` is not an
adequate production implementation. There are two independent missing bundles:

1. User-consented, cancellable, secret-safe pairing enrollment.
2. A Release DirectShell runtime/helper/feature-adapter composition capable of
   entering the existing qualification and COMPLETE-plan boundary.

Pairing alone cannot supply the second bundle. The pure application-owned P1
state machine and narrow backend/commit contracts are now IMPLEMENTED in the
isolated `codex/phase6-5-pairing-enrollment-owner` worktree, fromfaa9f0f. Only
test fakes implement the ports; there is no production wiring or real enrollment.
See the [P1 receipt and limits](DirectShellPairingEnrollmentOwner.md) for current
automated gates and provenance. Its [bounded prompt](DirectShellReleasePairingImplementationPrompt.md)
has been executed for P1 only, not for P2-P5 or Release activation.

## Verified Consolidation

| Checkpoint | Exact SHA | Scope |
| --- | --- | --- |
| Frozen G1-G5 | `656c3783ba3f120f2e8b911b2ec3c932ca1b5df3` | Independent baseline, not blanket hardware acceptance |
| DEBUG UI implementation/tests | `f7dd2cf131aa7952b67ee09e9594a3e2a4aefe9a` | Exact13 app paths from the previously verified15-path manifest |
| UI software receipt/index | `2a6528da80dc9f354055b021c07d4594e9654e64` | Remaining two documentation paths, with authorized consolidation provenance |

Branch: `codex/phase6-5-directshell-pairing-setup`. Both commits are local;
the tree was clean immediately after2a6528d, before this architecture-only delta.
No source changes were made while checkpointing. All15 initial raw hashes and
four artifacts matched the prior receipt; after committing, all13 app hashes
still match. Git's LF normalization is not a functional source delta.

Fresh focused JVM55/55:34 UI-owner/presentation/permission (18+11+5), plus21
existing discovery/bootstrap-contract/controller tests (11+8+2),0 failures/
errors/skips; fresh isolation21/21; diff hygiene PASS. Retained exact-source
evidence: full JVM1184/1184 in180 suites,29 additions over1155; offline40/40;
native guards6/6; Debug/AndroidTest/helper/Release builds, ktlint and lint PASS,
0 lint errors/134 unchanged warnings. No new full-suite/build/CTest run is
claimed: source and build configuration did not change. Filtered test XML is
not the full suite; both current filtered receipts and the prior full1184
receipt remain separately preserved, not added together as a new full run.

Receipts: `C:/wg-pairing-setup-r1598/` and current
`C:/wg-onboarding-r1599/`. Artifact B043FBA2 is a pre-commit build of656c378
plus the identical13-path cohort, not a fresh f7dd2cf build. See the
[full manifest, hashes and acceptance scope](DirectShellHostPairingSetup.md).
APK VCS metadata may differ when rebuilding a committed tree later.

Review inputs include accepted RFC-006I/J, actual source, and the preserved
`PostHardwareReconciliation.md`/`CURRENT_HANDOFF.md` at the separate reporting
worktree `C:/Users/Francy/.codex/worktrees/phase6-5-video-recovery/Warpnect`
(7ef9960 with its uncommitted reporting cohort). They were read, not imported.
The original research3253518, frozen20928f4/656c378, Video observer142e467,
Video reports7ef9960 and unchanged Phase7 R2 remain separate.

## Actual Execution And Source Boundaries

Paths below are relative to `app/src/`; `main/.../` expands to
`main/java/io/warpnect/`, with equivalent expansions for `debug/.../`,
`release/.../` and `test/.../`. Function names describe the actual
implementation, not hypothetical functionality inferred from a class name.

| Stage / source | Actual implementation | Production disposition |
| --- | --- | --- |
| `main/.../MainActivity.kt`, `ui/SecureSessionScreen.kt` | Explicit Enable Host, current ADB_PAIRING_REQUIRED action, saveable ActivityResult token | Reusable normal UI; Release action absent when adapter returns null |
| `main/.../platform/privileged/readiness/HostPairingSetupController.kt` | Single launch per current readiness generation; stale/duplicate returns ignored; no Host/credential API | Reusable launch guard, NOT an enrollment/network owner |
| `debug/.../platform/privileged/readiness/HostPairingSetupAdapter.kt` | API30+ and resolvable own Activity/developer-settings intent | DEBUG adapter only |
| `release/.../platform/privileged/readiness/HostPairingSetupAdapter.kt` | Always null, confirmed in bytecode and APK | Keep unavailable until all Release gates pass |
| `debug/.../debug/directshell/DirectShellSelfBootstrapProbeActivity.kt` | Settings button, permission gate, pairing notification, separate Input smoke button | Do not promote this developer/probe surface |
| `DirectShellSelfBootstrapNotification.kt` in that DEBUG package | RemoteInput, explicit FGS PendingIntent, mutable code action and immutable bootstrap retry | Reuse interaction pattern only; new consent-token binding and cleanup required |
| `DirectShellSelfBootstrapPairingService.kt` | New raw Thread per accepted start, START_NOT_STICKY, controller.close in finally | DEBUG behavior, not backend single-flight/cancellation proof |
| `DirectShellSelfBootstrapController.pairAndBootstrapDiscovered()` | Discover local pair endpoint -> TLS/PAKE -> persist authenticated peer GUID -> GUID/local-address TLS-connect -> shell identity -> verify artifact -> app_process -> HMAC/negative-auth probe -> shutdown | Mixed enrollment, runtime and diagnostics; extract pair-only subset, do not call this wholesale from Release |
| `DirectShellSelfAdbEndpointDiscovery.kt` | Local-address filter; serial resolver, candidate generations;8s pairing discovery/30s connect publication; trusted connect service name and DNS-SD conflict aliases | Valuable algorithms/tests, currently DEBUG-only; add bounded candidate storage, network ownership, cancellation and redacted output before extraction |
| `DirectShellSelfAdbKeyStore.kt` | Software RSA2048/X509 identity, encrypted private key/cert/GUID using AndroidKeyStore AES256-GCM; temporary arrays wiped | Already encrypted at rest, NOT plaintext persistence. Needs versioned atomic records, restore/partial-record handling and explicit migration policy |
| `DirectShellSelfAdbConnectionManager.kt` | Existing libadb authentication, API level,12s manager connection timeout, fail on unauthorized | Reuse library behind a private pairing-only wrapper after cancellation/trust review; not a new ADB stack |
| `DirectShellRuntimeAutoRegistrationProvider.kt` | DEBUG-only lazy registration; also registers unrelated Root lab adapters | Never move the initializer wholesale into Release |
| `DirectShellRuntime.kt` | DEBUG runtime owner, feature prewarming, leases/generations, owner-loss health publication; installs DEBUG overrides | Implemented and historically exercised in DEBUG; no equivalent Release factory/ownership bundle exists yet |
| `DirectShellSelfBootstrapContract.kt`, server/client/protocol classes | Fixed artifact/roles, HMAC, shell UID2000, scoped shutdown; also smoke/forensic paths | Reviewed runtime subset can be extracted later; diagnostic roles/negative probes remain excluded |
| `main/.../platform/privileged/runtime/PrivilegedRuntime.kt` | Registry/resolver and lazy runtime acquisition; registered DirectShell is required and does not fall through to Shizuku | Reuse existing provider ownership; no new fallback |
| `main/.../platform/privileged/readiness/AndroidHostReadinessWork.kt` | Passive classification, live acquisition on explicit Host request, qualification, advisory evidence | Reuse unchanged; pairing result is not usable capability evidence |
| `main/.../platform/privileged/planning/AndroidHostPrivilegedCapabilityAdmission.kt`, `PrivilegedCapabilityPlanner.kt` | Current per-capability probes, static complete-plan choice/freeze | Reuse unchanged; COMPLETE=Video+SystemAudio+Input, MicrophoneAudio optional |
| `main/.../platform/session/integration/AndroidSecureSessionComposition.kt` | `startHostFromUi()` -> readiness -> owner-generation checks -> `HostAdmissionPublicationGate.publish()` | Only this existing admission route may publish Host |

The capture/input/audio selectors are also relevant: `CaptureGatewaySelector.kt`,
`SystemAudioCaptureControllerSelector.kt`, and `InputInjectionControllerSelector.kt`
currently gate their DirectShell override registries with BuildConfig.DEBUG.
A Release pairing success cannot unlock those adapters. Refactoring these
provider entry points belongs to a separately verified runtime extraction, not
to the small pairing coordinator or a planner rewrite.

```text
CURRENT DEBUG:
explicit Enable Host -> background readiness's active request
 -> ADB_PAIRING_REQUIRED -> explicit setup Activity
 -> user Android settings + notification code submission
 -> pair/connect/bootstrap smoke/shutdown (DEBUG only)
 -> return to app [no success claim from ActivityResult]
 -> NEW explicit Enable Host
 -> Registry.acquireForHost -> DirectShell runtime/feature qualification
 -> current matrix -> COMPLETE plan/freeze -> owner recheck -> Host publication

CURRENT RELEASE:
setup adapter absent + automatic DirectShell registration absent
 -> no actionable DirectShell pairing entry, no claim of DirectShell readiness
 -> existing explicit provider/Client paths retain their current semantics

PROPOSED RELEASE:
explicit consent -> pair-only enrollment -> close all pairing resources
 -> authorization stored, Host still OFF
 -> NEW explicit Enable Host -> separately supported Release runtime bundle
 -> unchanged qualification/COMPLETE-plan/freeze/publication
```

No arrow connects pairing completion directly to `startHost`, a capability cell,
cache qualification or discovery publication. Wireless Debugging authorizes a
local ADB host key; Warpnect peer/SAS pairing authenticates a remote Session.
They have different stores, tokens, trust and purposes.

## Concrete Library Feasibility Findings

The actually cached dependency is
`com.github.osservatorionessuno:libadb-android-bc:3.4.0`, DEBUG-only, with
BC1.84 declared by the existing build. Cached AAR SHA256:
`62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB`.
Read-only javap inspection of that exact AAR is recorded under
`C:/wg-onboarding-r1599/libadb-*-bytecode.txt`. No library code was executed,
installed, updated or patched; no device was used.

1. `AbsAdbConnectionManager.pair(host,port,String)` holds `mLock`, constructs a
   local `PairingConnectionCtx`, calls synchronous `start()` and closes it after
   return. It does not forward the manager timeout into this pairing context.
2. `PairingConnectionCtx.setupTlsConnection()` creates `new Socket(host,port)`;
   the inspected method supplies no explicit connect/read timeout, network-bound
   socket factory or externally owned socket. TLS is established before its
   input/output streams become fields. Manager `close()` closes the ordinary
   `mAdbConnection`, not that local pairing context. Merely closing the manager,
   interrupting a coroutine or waiting12s is NOT a proven physical pair cancel.
3. Pairing returns a boolean through the manager; the inspected public context
   API has no structured peer-info result getter. PeerInfo is parsed internally
   and logged. The wrapper currently derives the retained connect service name
   from mDNS metadata. Production must audit the binding to authenticated peer
   information; a service name/GUID advertisement is not a cryptographic proof.
4. `SslUtils$2` has no-op trust callbacks and the pairing TLS authentication
   callback does not independently validate a conventional CA chain. Do not
   label this alone an exploit: ADB pairing uses PAKE plus a TLS exporter, and
   has different trust semantics from browser HTTPS. Conversely TLS encryption
   alone does not prove end-to-end daemon identity. Review the complete pairing/
   connect trust chain and local endpoint restrictions before Release reuse.
5. Existing wrapper `guarded()`/service reporting logs Throwable/detail strings;
   the library also logs handshake/peer information. Their code secrecy and
   transitive logging are not certified by the UI tests. No actual secret leak
   was induced or inferred from this static review.

The [maintainer README](https://github.com/osservatorionessuno/libadb-android-bc/blob/master/README.md)
explicitly describes the fork as unaudited; it is context, not proof that master
equals the cached3.4.0 artifact. Review the pinned dependency/transitive license
inventory and reproducible provenance before changing its source-set scope.
The [AOSP ADB Wi-Fi architecture](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md)
distinguishes pairing/connect services; its current HEAD is background
architecture, not evidence of this Samsung framework's exact implementation.

**Backend promotion gate:** obtain a reviewed same-library extension/upstream
change exposing owned socket/context cancellation before TLS, bounded I/O and
structured authenticated peer information, OR demonstrate an equivalent existing
public route with deterministic stalled-peer tests. No reflection, binary hook,
Thread.stop, protocol rewrite or guessed bounded timeout. No dependency bump or
patch is authorized here. If physical close cannot be made bounded, withhold
Release support rather than spawning a replacement worker around a stuck one.

## Proposed Minimal Ownership Boundary

Proposed paths, not files implemented by this task. `P1` through `P5` below are
local delivery slices, NOT new roadmap/RFC identifiers.

| Slice / exact proposed Kotlin path | Responsibility |
| --- | --- |
| P1 `main/.../platform/privileged/directshell/pairing/DirectShellPairingContract.kt` | Opaque consent ticket, states/errors, narrow backend, cancellable operation and staged-authorization commit contracts; no Android or library imports |
| P1 `main/.../platform/privileged/directshell/pairing/DirectShellPairingCoordinator.kt` | Application-owned enrollment state machine, one attempt/operation, deadlines, stale-result/commit gates; test-injected dependencies only |
| P1 `test/.../platform/privileged/directshell/pairing/DirectShellPairingCoordinatorTest.kt` | Deterministic fakes, virtual monotonic clock/scheduler, cancellation/commit/process-loss tests |
| P2 `main/.../platform/privileged/directshell/pairing/AndroidDirectShellPairingTransport.kt` | Private wrapper of reviewed libadb pair-only operation; no shell/openStream API exposed |
| P2 `main/.../platform/privileged/directshell/pairing/AndroidDirectShellAdbEndpointDiscovery.kt` | Extract current resolver policy/tests, bounded local-only candidates, per-socket network ownership and stale callback shutdown |
| P2 `main/.../platform/privileged/directshell/pairing/AndroidDirectShellCredentialStore.kt` | Atomic encrypted enrollment records and explicit legacy adoption; no capability evidence |
| P3 `main/.../platform/privileged/directshell/pairing/DirectShellPairingSetupActivity.kt` | Non-exported production setup surface, system-settings button, explicit consent/cancel, permission result UI |
| P3 `main/.../platform/privileged/directshell/pairing/DirectShellPairingNotification.kt` / `DirectShellPairingService.kt` | Narrow token-bound RemoteInput and short, user-initiated FGS hosting the SAME application coordinator, not another owner |
| P4 `main/.../platform/privileged/directshell/runtime/AndroidDirectShellRuntime.kt` / `DirectShellRuntimeBootstrap.kt` / `DirectShellPrivilegedServer.kt` | Planned owner, closed bootstrap and internal helper entry boundaries extracted from the DEBUG runtime; no changed wire/native ABI or new generic command API |
| P4 `release/.../platform/privileged/directshell/DirectShellRuntimeRegistration.kt` and existing `release/.../platform/privileged/readiness/HostPairingSetupAdapter.kt` | Activate only after the separately reviewed runtime/feature bundle exists; lazy registration only, no Root lab initializer |

Existing `HostPairingSetupController` remains a UI launch guard. It is not a
second enrollment state machine. The future enrollment coordinator is created
by the Application composition and injected into the private Activity/service;
neither owns independent retry logic. P1 does not wire this owner into the app.

The backend contract permits only prepare-pair, cancel/close, and staged
authorization commit/discard. No host/port/command string supplied through public
IPC, no generic `exec`, raw AdbConnection, smoke/probe role or arbitrary file API.
Code ownership uses a bounded secret holder, not a data class whose generated
toString/copy can reveal it. StateFlow/outcomes contain no code, key, certificate
payload or raw Throwable. The actual library still accepts immutable strings:
best-effort array wiping is not a claim that all JVM/IME copies are zeroized.

## State Machine And Linearization

```text
Unavailable / Idle
 -- explicit supported current Host-origin request --> AwaitingConsentAndCode
 -- explicit valid code + required user permissions --> DiscoveringLocalEndpoint
 -> Pairing -> CommittingAuthorization -> PairedNeedsExplicitHostRetry
 any active stage -- cancel/network invalidation/deadline --> Cancelling
 -> Cancelled / Expired / Failed
 cleanup cannot be verified within budget -> CleanupIncomplete [no new operation]
 process restart -> Idle, prior consent tickets/notification submissions invalid
```

Keep these identity domains separate:

- Origin readiness generation: checks current UI intent; never a Session token.
- Enrollment consent ticket: opaque request nonce plus per-process owner epoch;
  active only while explicit consent remains. A passive readiness refresh is not
  itself a new pairing authorization or permission to publish Host.
- Network epoch and credential revision: a result for old network/key material
  cannot commit or silently select another endpoint/key.
- Existing runtime/helper and Session generations: unchanged; enrollment never
  manufactures their identity, authorization or qualification.

Explicit setup cancel/back withdraws the enrollment ticket, cancels the mutable
code PendingIntent and closes the owned operation. A stale Host-generation
completion must not overwrite readiness; only an enrollment ticket still valid
under its own consent/network/credential checks can publish authorization.
No setup is permitted to replace credentials while an active Host/runtime lease
uses them; report Busy and leave current Session/Client state alone.

Backend success supplies an opaque staged authorization handle. Commit must be
coordinator-authorized and atomically guarded against canceled ticket/owner
epoch/network/credential revision. Define the publish/rename of the encrypted
record as the commit linearization point. Cancel ordered before that point wins;
cancel after it cannot pretend Android's already-completed pairing was undone.
The fake commit contract must test both orders before real disk integration.

Late PAKE completion after cancel may already have changed Android's trusted-host
list. Do not claim rollback of that system effect, revoke trust, or regenerate
the key silently. Retain encrypted staged identity as Unconfirmed if needed for
an explicit next action; it is not an accepted Host credential record. Already
committed credentials are not erased by canceling an unrelated UI notice.

Process death is fail-closed: no pairing code/transport is restored, no service
is sticky, no boot receiver, WorkManager enrollment replay or automatic retry.
Old notification actions expire even if Android later delivers them. A new app
process requires a fresh user setup action. UI recreation in the same process
reattaches to the owner; it must not resubmit the code or duplicate I/O.

## Bounds And Credential Policy

These are proposed acceptance bounds, not changes to existing production timing:

Use one injected suspend-inclusive monotonic domain (Android
`SystemClock.elapsedRealtime`, as in readiness) for consent/work expiry. Check
the absolute deadline on submit, callbacks and commit as well as timer delivery;
a delayed timer or device sleep must not extend consent. This changes no SCL,
wire/media timestamp, playback clock or existing liveness contract.

| Resource | Proposal / rationale |
| --- | --- |
| Active enrollment / worker / pending work | One each; no queue of new pairing attempts; duplicate submit is rejected, never a new Thread |
| Awaiting code |120s consent/notification expiry, no network or credential creation until explicit submission; a visible countdown permits fresh user restart |
| Local discovery candidates | Provisional cap8 bounded metadata candidates, local interfaces only, existing name<=255 and valid-port checks; qualify with adversarial NSD fixtures |
| Work after code submission |60s total deadline, based on the existing60s owner-acquisition ceiling; existing8s discovery is a stage ceiling, not license for nested unbounded retries |
| Pair I/O / cleanup | Proposed<=12s I/O stage and<=3s physical cleanup; NOT guaranteed by current libadb. Stalled-peer tests are a hard promotion gate |
| Cancel | Logical ticket withdrawal immediately; retain occupied backend slot until physical close is confirmed. CleanupIncomplete blocks new I/O instead of growing workers |
| Stored code | None: no preference, SavedState/Bundle, file, telemetry, clipboard harvesting or service replay. RemoteInput is ephemeral OS-mediated delivery, not a no-copy guarantee |

Pair enrollment does not automatically run the old30s TLS-connect discovery
and bootstrap smoke. The later explicit Host request owns connection/runtime
acquisition. No retry on auth rejection, invalid code, expiry, network change or
trust revocation. Current DEBUG's single ECONNREFUSED endpoint refresh is an
existing bounded acquisition behavior; this proposal does not enlarge it.

Reuse the existing RSA identity/AES-GCM-at-rest approach where the pinned TLS
library requires exportable software key material. Do not assert that the RSA
private key is non-exportable merely because its wrapping key is in
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).
Keystore hardware backing must be probed, not inferred from model/API. No
StrongBox requirement/model allowlist is proposed.

Store a versioned, authenticated record atomically under `noBackupFilesDir`,
with a distinct production alias/namespace and explicit schema/field binding.
Bind key/certificate consistency and the authenticated peer association; retain
no transient IP/port as permanent trust. Current debug preferences are already
encrypted, but partial records, missing wrapping alias and restored ciphertext
must be Corrupt/Unconfirmed, not automatic replacement enrollment. The app's
existing `allowBackup=true` is not permission to back up ADB identity material.
Specify credential exclusions for both backup rule generations without disabling
unrelated app backup or changing Warpnect peer/SAS identity storage.

Do not auto-import, clear or replace the DEBUG store/alias on a signed same-data
upgrade. A separate explicit adoption transaction can copy and validate the
existing identity, preserving originals/trust, after compatibility tests. A
new identity requires explicit user authorization, not a repair fallback.
Review the current asynchronous multi-field save and broad clear() before reuse.

## Android UI, Permissions And Service Policy

Pairing supports Android11/API30+ only; app minSdk26/Client availability remains
unchanged. Keep target/compile35 for this plan. Android's user controls remain
mandatory: Warpnect may open the supported developer-settings Activity, not
enable developer options/Wireless Debugging, write secure settings, bypass a
system dialog or promise a universal deep-link directly into its pairing panel.
The [ADB guide](https://developer.android.com/tools/adb#wireless) documents the
platform Wireless Debugging prerequisite, not a special privilege for Warpnect.

Prefer the existing notification code-entry pattern while Settings remains
foregrounded. Production UI has no smoke/Input/bootstrap controls. Start no
pairing I/O while only observing readiness or displaying instructions. A normal
notification can wait for code without a pairing worker/FGS; process loss expires
it and asks for a fresh setup from the normal app, still without a PC. If an
alternative code-entry UI is offered, first demonstrate that Android's pairing
panel remains usable; do not assume app switching/split-screen behavior.

On API33+, deny/no-grant/channel-disabled must make code entry unavailable or
offer a separately qualified foreground UI; do not start invisible pairing as a
fallback. POST_NOTIFICATIONS is needed for this notification journey, not an
Android prerequisite for every FGS. See
[notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

After an explicit code notification action, the private service may host the
bounded operation; Android recognizes notification interaction as a possible
[background FGS start exemption](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
not guaranteed success. Handle start denial/security errors without retry.
Proposed API34+ type is `shortService` for this bounded critical enrollment,
with onTimeout cleanup; API30-33 uses a normal bounded FGS. Its work budget is
well below the documented approximately3-minute
[shortService limit](https://developer.android.com/develop/background-work/services/fgs/service-types#short-service).
Do not call same-device ADB an external connected device merely to borrow that
type, or copy DEBUG dataSync by habit. Final service-type/distribution acceptance
must precede activation; no new FGS manifest is implemented here.

Proposed manifest deltas, only at the UI/service integration slice:

- Private non-exported setup Activity/service; narrow developer-settings query
  if resolver-based availability remains; base FGS permission already exists.
- Declare only the selected supported service type. No exported enrollment
  receiver/provider, boot restart, overlay, shell/root permission or generic IPC.
- Explicit service component and per-attempt unique data/request identity for
  RemoteInput PendingIntent; mutable only where RemoteInput requires it. All
  unrelated extras/host/port/script input are rejected. Cancel action immutable.
- Recheck notification/channel state at submit. Do not copy the DEBUG blanket
  nearby-device permission list as proof that ordinary NSD requires it.
- NSD/multicast/network permission handling follows actual selected API/target,
  with a scoped multicast lease where required and no process-wide network bind.
  [NsdManager documentation](https://developer.android.com/reference/android/net/nsd/NsdManager)
  describes API/extension differences. Existing SCL discovery permissions and
  network contracts are not to be redesigned for pairing.

Current public [local-network guidance](https://developer.android.com/privacy-and-security/local-network-permission)
distinguishes API36 opt-in protections and target37 enforcement. This repository
targets35: do not add target37 permissions or change SDKs as part of P1. Later
target/API changes need a fresh permission review and explicit hardware matrix.

## Security And Failure Review

| Condition | Required behavior / test |
| --- | --- |
| Recreate/duplicate tap/code submit | Reattach one owner; one backend begin/commit; never copy code into saved UI state |
| Missing Activity / permission/channel denial | Typed unavailable/denied outcome, no network/helper; Client remains available |
| Wrong/expired code | Typed failure, no auth record, clear code, explicit new attempt only; no automatic guessing |
| Trust revoked externally | Later Host authentication fails truthfully; keep Host unpublished/withdraw unhealthy ownership; no auto-repair/fallback |
| Network changes | Invalidate attempt/socket lease; no rebind to another network or arbitrary mDNS peer, no global bindProcessToNetwork |
| Malicious/stale NSD | Local-address/network and trusted identity checks, bounded candidate set, inert late callbacks; GUID/name is not proof of authority |
| Cancellation during TLS/PAKE | Close actual owned context/socket, not only a logical Future; block replacement work until cleanup proved |
| Late callback/commit | Request/owner/network/credential revision checked at publish point; stale callback cannot write trusted state or start Host |
| Process death / partial credential write | No auto-resume; atomic old-or-new record, Unconfirmed when ambiguous; preserve keys/trust, require fresh consent |
| Logging/telemetry/crash toString | Closed enums/stages/durations only; canary-test codes/key bytes/exception causes across wrapper AND dependency logs |
| Pairing success, incomplete capabilities | PairedNeedsExplicitHostRetry only. Normal probes/planner may still return HostIncompatible; no partial Host or compulsory microphone |
| Invalid helper artifact/UID/HMAC/generation | Runtime unavailable, cleanup owned resources, no discovery. Enrollment cannot grant support by using another provider |

Review conclusions are constraints, not a completed security audit. In particular
the current DEBUG code does not prove bounded pair cancellation, transactional
credential publication or safe duplicate service commands. This does not change
the accepted software-only UI checkpoint or infer a hardware regression.

## Incremental Implementation And Activation Gates

1. **P1, implemented/unwired:** pure coordinator/contracts plus deterministic
   fake backend/commit tests; [receipt](DirectShellPairingEnrollmentOwner.md).
   No libadb/Android service/credential store/manifest/source-set activation
   change. DEBUG UI and Release adapter remain byte-for-byte unchanged. This
   establishes model ordering, not real backend cancellation/credential safety.
2. **P2, separately approved:** resolve the pinned-library cancellation/identity/
   logging/ownership findings; extract NSD, key storage and private pairing-only
   transport with shared DEBUG consumers, not duplicated implementations. Prove
   stalled connect/TLS/PAKE close and atomic credential semantics offline. Review
   exact dependency/license inventory before any Release dependency promotion.
3. **P3, separately approved:** private user-facing setup Activity, token-bound
   notification/service/permissions integration. No smoke bootstrap. An enrollment
   preview must not advertise a working normal Host until the runtime bundle exists.
4. **P4, separately approved:** production DirectShell runtime/helper/gateway
   extraction and lazy registration. Reuse fixed role commands, installed-artifact
   verification, HMAC/UID2000/generation ownership/teardown; exclude DEBUG test
   servers, negative-auth smoke execution, lab Root registration and arbitrary
   command APIs. Preserve media implementations and all frozen wire/native ABI.
   Review full dependency closure rather than moving huge DEBUG files wholesale.
5. **P5, activation/qualification:** enable the Release setup adapter only when
   both implementation bundles exist and are approved. Static availability means
   implementation/platform presence, not fabricated capability qualification.
   Run signed Release/API hardware acceptance and regressions; then update status.

RFC decision: P1 is compatible with the existing RFC-006J readiness orchestration
as an unconnected implementation increment. Real credential enrollment, process
ownership and Release activation need an explicitly reviewed RFC-006J security/
lifecycle addendum before P2-P5 activation; this document proposes that addendum
but does not silently amend the accepted RFC. RFC-006I ranking, cache advisory
semantics, COMPLETE Host invariant and Session protocols need no redesign.

Rollback is source-set/product gating, not security weakening: leave/restore the
Release adapter to unavailable and do not register the Release runtime; keep
Client and any other explicitly configured provider semantics. Stop owned
enrollment, expire tokens/notifications and preserve credential records. No auto
unpair, key deletion, downgrade-data migration, retry, Root/Shizuku rescue or
device reboot. Regress the existing DEBUG checkpoint when shared code is extracted.

## Acceptance And Later Hardware Proof

Only after implementing and passing its gates may P1 be labelled
**IMPLEMENTED / SOFTWARE TESTED**, never an end-user Release capability claim.
Deterministic virtual-time tests must cover zero passive calls,
user/reason/adapter eligibility, duplicate starts/submits, permission denial,
invalid/expired code, deadlines, cancel before/after fake completion/commit,
stale owner/readiness/network/credential revisions, process re-creation, exactly
once close/discard, CleanupIncomplete blocking replacement, redacted canary
outcomes, and zero Host/planner/registry side effects. Do not use real sleeps or
new Android/network dependencies merely for these tests.

Run focused and full JVM with exact delta, ktlint/lint, Debug/AndroidTest/helper/
Release builds and positive/negative source-set guards after actual code changes.
Keep current21 isolation expectations until the specific dependency/adapter
activation is approved; later replace them with reviewed narrow production
allowances plus continued total DEBUG/lab exclusion, not disabled checks.
Offline audio replay/native gates follow affected paths; no media change or new
audio performance claim from a pairing unit suite.

Hardware is NOT authorized now. Later use a legitimately unpaired supported test
fixture, not revoked trust on restored A41/S9. Freeze source/APK/signers, preserve
data/keys/network/volumes and verify a restorable original main/test installation
before mutation. Test API31 and a real API33+ permission path, then actually
available API36+ according to capability evidence; API26/29 negative controls do
not become unsupported Clients. Do not infer universal API30+ qualification.

Required later signed-Release journey: no PC/developer-only Warpnect screen,
manual Android settings consent, code entry with Settings foreground, denial/
cancel/expiry/network/revocation/process-death cases, one current attempt, no
code/keys in logs, zero helpers on pair-only success, explicit later Enable Host,
current complete plan before publication, Client availability, teardown and
verified restoration. Test a meaningful enrollment/Host acquisition boundary,
not only an APK build or a notification appearing. Re-run exact scoped media/
input regressions only when their shared runtime adapters actually move.

## Remaining Phase 6.5 Debt

G1-G5 remains software ready but **TARGETED_HARDWARE_REGRESSION_PARTIAL**.
Historical SystemAudio RX gaps, AAudio wakeup, dispatch-to-decode stall,
MicrophoneAudio DEBUG admission and S9 timestamp regression, Video HOME/REOPEN
and bootstrap EADDRINUSE remain independent OPEN debts. Last Video first-full
run remains NOT_REPRODUCED; observer142e467 is software validated only and is
not imported. Exact historical quality/r835 qualification is not revoked or
transferred automatically to the consolidated APK.

Root/Shizuku qualification, provider migration/recovery, RFC-005J/005K and full
Phase6.5 exit remain separate. Phase7 R2 stays proposed and unchanged; no S0 or
adaptive audio implementation begins. No Android device, trust, volume, setting,
APK or network was accessed/modified for this consolidation/design task.
