# RFC-006J - Privileged Runtime Orchestration and Host Readiness

## Status

Accepted architecture. Step 1 is implemented, JVM-tested, and hardware
validated on A41/API31 and Tablet/API33. Step 2 persistent capability evidence
and advisory prepared-plan reuse are implemented, JVM-tested, and process-restart
hardware validated on the same two devices. Cached evidence remains advisory;
current runtime acquisition and late qualification still precede Host publication.
The subsequent DirectRoot authority foundation is DEBUG/laboratory implemented,
JVM-tested and AOSP/API26 emulator-validated. Its Input-only follow-up is now
implemented and QUALIFIED for the exact AOSP/API26 lab. Production Root
authorization, Root Video and Root SystemAudio remain NOT_IMPLEMENTED.

The implemented slice supplies volatile runtime-state observation,
asynchronous background Host readiness, single-flight Host preparation,
generation-safe invalidation, structured readiness UI state, versioned
persistent capability evidence, selective invalidation, cached background
matrix reconstruction, and late reuse of the existing RFC-006I admission/freeze
boundary, plus volatile observation of the laboratory Root authority and scoped
Root Input evidence. It does not implement Root media providers, production Root
authorization, root-assisted remediation, same-provider recovery, or live provider migration.

## Purpose

RFC-006I already defines a truthful per-capability matrix, deterministic
complete-plan selection, bounded pre-freeze replanning, and the frozen plan
required before Host discovery. This RFC defines how Warpnect should obtain the
runtime state and prepared evidence that RFC-006I will later consume without
blocking normal application startup or weakening any authorization boundary.

The intended long-term sequence is:

```text
runtime discovery
  -> runtime state classification
  -> background Host readiness
  -> optional bounded remediation
  -> staged capability qualification
  -> PrivilegedCapabilityMatrix
  -> PrivilegedCapabilityPlanner (RFC-006I)
  -> prepared plan
  -> Host admission and late revalidation
  -> frozen plan
  -> discovery, WNCP, and Session
  -> same-provider recovery or controlled teardown
  -> future provider migration only under a separate RFC
```

## Current Facts

* RFC-006I's static planner and complete-plan admission already exist. A frozen
  plan is required before Host publication, discovery, WNCP, or Session setup.
* DirectShell has hardware evidence for Video, SystemAudio, and Input on the
  modern API31, API33, and API36 reference devices.
* Shizuku can be an explicitly selected optional capability provider in the
  controlled DEBUG composition. It is not a post-freeze fallback.
* DirectRoot has a validated DEBUG/laboratory UID0 runtime authority foundation.
  Its Input capability is separately QUALIFIED on the exact AOSP/API26 lab;
  Video/SystemAudio and production bootstrap remain `NOT_IMPLEMENTED`.
  Root alone cannot supply a Complete Host plan.
* The A41 NSD repair proves that runtime state can change independently of a
  capability implementation. A valid pairing and local daemon were present;
  the prior failure was client-side endpoint selection.
* Existing capability evidence is useful but cannot by itself prove that a
  volatile runtime, authorization, network endpoint, or provider process is
  usable now.

## Evidence Boundary

**FACT:** RFC-006I freeze semantics, DirectShell modern capability evidence,
the controlled Shizuku composite, and the A41 NSD lifecycle repair are current
repository and hardware evidence.

**INFERENCE:** Separating durable qualification evidence from volatile runtime
state permits cheaper, more truthful preparation than requalifying every
provider after every application launch.

**UNKNOWN:** Production/physical DirectRoot behavior, capability support, which
runtime failures are safely recoverable on each Android environment, and the
protocol/lifecycle requirements for live provider migration remain unqualified.
The separate foundation report establishes laboratory authority; the subsequent
Input report establishes only exact-lab Input qualification, not Root media or
physical/root-manager behavior.

**FACT:** Step 1 now implements passive discovery, volatile state publication,
and single-flight readiness ownership. Its active Host path delegates to the
existing RFC-006I admission and frozen-plan boundary.

**PROPOSAL:** Remediation, same-provider recovery, and live provider migration
remain future work. They are not current product behavior.

## Terms

| Term | Meaning |
| --- | --- |
| Persistent evidence | Versioned past qualification and validation evidence that may be reused only while its cache key remains valid. |
| Volatile runtime state | Current availability, authorization, reachability, health, and endpoint state that can change without an app update. |
| Runtime discovery | Cheap, bounded observation of provider state. It does not itself grant privilege or claim capability qualification. |
| Readiness generation | A monotonic ownership token for one background preparation attempt. Older results may never overwrite newer state. |
| Prepared plan | An advisory complete plan built from current evidence and state before Host activation. It is not a Session contract. |
| Frozen plan | The RFC-006I provider assignment acquired and committed during Host admission. It is the Host and Session contract. |
| Recovery authority | A future component allowed to attempt a narrowly defined repair of another runtime. It is distinct from the final capability provider. |

## Architectural Layers

The layers must remain separate:

```text
PersistentCompatibilityEvidenceStore
        +
VolatileRuntimeStateSnapshot
        -> PrivilegedRuntimeDiscovery
        -> BackgroundHostReadinessCoordinator
        -> staged qualification
        -> PrivilegedCapabilityMatrix
        -> PrivilegedCapabilityPlanner
        -> PreparedPrivilegedCapabilityPlan
        -> Host admission / late revalidation
        -> Frozen PrivilegedCapabilityPlan
        -> Session health and future recovery boundary
```

`PrivilegedRuntimeResolver` remains the acquisition seam. Step 1 adds a
coordinator above it: passive observation never acquires a provider, while a
user Host request re-observes volatile assumptions and delegates to the
existing RFC-006I admission, matrix, planner, ownership, and freeze rules.

## Runtime Discovery

Step 1 implements bounded passive observation of each candidate without
needlessly starting every provider. It reports separate dimensions where they
are meaningful:

```text
installed
implemented
platform-supported
enabled
paired / authorized
running
reachable
healthy-enough-to-probe
```

Discovery must not infer capability success merely from any one of those
dimensions. For example, a connected DirectShell ADB endpoint does not prove
that Video, SystemAudio, or Input will qualify.

The candidate domains are:

| Provider | Discovery role now | Capability role now |
| --- | --- | --- |
| DIRECT_ROOT | Volatile authority observation; DEBUG/lab UID0 bootstrap, production bootstrap absent | Input implemented and exact-lab QUALIFIED; Video/SystemAudio `NOT_IMPLEMENTED` |
| DIRECT_SHELL | Wireless Debugging, pairing, local ADB, artifact, and authenticated-runtime state | Modern direct provider when truly qualified |
| SHIZUKU | Installation, Binder, authorization, and provider health | Optional per-capability compatibility provider |

Discovery must use bounded structured outcomes. Candidate semantic reasons
include:

```text
ROOT_NOT_AVAILABLE
ROOT_NOT_IMPLEMENTED
WIRELESS_DEBUGGING_DISABLED
WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE
ADB_PAIRING_REQUIRED
ADB_AUTH_FAILED
DIRECTSHELL_ARTIFACT_INVALID
DIRECTSHELL_BOOTSTRAP_FAILED
SHIZUKU_NOT_INSTALLED
SHIZUKU_SERVER_STOPPED
SHIZUKU_AUTHORIZATION_REQUIRED
SHIZUKU_PROVIDER_UNHEALTHY
PLATFORM_UNSUPPORTED
```

Reason values are machine-readable, bounded, and non-sensitive. UI copy must
be derived outside the core state model.

## Runtime State Model

The internal state model distinguishes provider reality from user-facing
readiness. Step 1 provides `PrivilegedRuntimeAvailabilityState`,
`PrivilegedRuntimeFailureReason`, `PrivilegedRuntimeAvailability`, and immutable
`PrivilegedRuntimeStateSnapshot` types. A snapshot contains per-provider state,
reason, generation, observation time, and non-sensitive detail.

Suggested internal states are:

| State | Meaning |
| --- | --- |
| NOT_STARTED | No readiness generation has begun. |
| CHECKING | Discovery or safe qualification is running. |
| READY | A current prepared complete plan is available, subject to late revalidation. |
| AUTHORIZATION_REQUIRED | A real provider may work after explicit user consent. |
| USER_ACTION_REQUIRED | A user-controlled prerequisite must be changed, for example Wireless Debugging enabled or pairing completed. |
| RECOVERING | A permitted, bounded remediation attempt is in progress. |
| UNSUPPORTED | Platform or implementation contract makes the requested path unavailable. |
| FAILED | A bounded operation failed for a reason that is neither a known user action nor unsupported state. |
| STALE | Cached or observed evidence must be recomputed before it can support readiness. |

`READY` means a prepared result exists. It never means that Host discovery is
already published or that a Session may use an unfrozen provider assignment.

## Background Host Readiness

Normal application startup makes the UI usable immediately. Step 1 starts one
asynchronous, passive readiness generation after session composition is ready;
it never sits on the main startup critical path:

```text
app start
  -> UI usable immediately
  -> one asynchronous readiness generation
       -> observe cheap volatile runtime state
       -> classify obvious unsupported or user-action states
       -> publish stale evidence when a complete current plan is not already available
```

The Android implementation passively observes API level, explicit Wireless
Debugging disablement, the existing DirectShell runtime snapshot, and Shizuku
Binder access state, plus the separate volatile Root authority snapshot.
Production Root bootstrap remains absent; an explicitly acquired lab helper can
be observed READY without making Root a Host provider. Observation does not
connect local ADB, launch `app_process`, start a DirectShell feature server, or
start a Warpnect Shizuku UserService while Host is not requested.

Step 2 adds an application-private, bounded persistent capability-evidence
store. It stores only usable `QUALIFIED` or `VALIDATED` provider/capability
observations with their original live provenance, explicit schema version,
recorded-at metadata, and an exact compatibility key. It stores no ADB keys,
pairing codes, HMAC secrets, session material, raw input, or media.

The key covers provider, capability, Android API, a SHA-256 Android-build
identity digest, Warpnect version code, provider implementation revision,
provider runtime version where relevant, and capability backend revision.
DirectShell keying deliberately excludes transient Wireless Debugging state.
Shizuku keying combines the public read-only server API/UID with installed manager
version code and update identity. The API number alone is not the manager release:
the official [Shizuku API source](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java)
exposes it as a server protocol value. Restricted library-only patch APIs are not
used. A manager upgrade therefore invalidates
only Shizuku records, even when the server API number is unchanged. An unavailable
or unidentifiable provider is not reused. This is not a claim that an installed
manager version proves the binary identity of an already-running server.
DirectRoot now has an Input-only positive evidence domain with explicit bootstrap
provenance in the provider-runtime key dimension. LABORATORY_EXTERNAL_ROOT and
future PRODUCTION_ROOT_MANAGER cannot share evidence. Root media/unknown-provenance
records are rejected. Helper liveness, laboratory adbd state and secrets are
never persisted; current matching-provenance authority is separately revalidated.

The cache schema is explicitly versioned. A mismatched schema or corrupt payload
is dropped safely; an unknown bounded record is ignored without blocking
startup. Storage is a single app-private SharedPreferences payload committed as
a bounded whole-matrix replacement. A shared transaction lock serializes complete
read/modify/write operations across repository instances. Preferences are opened
lazily on the non-Main readiness worker; malformed preference types and storage
exceptions fail closed. Failed persistence cannot turn a successful Host admission
into provider failure.

Existing codec stores (`SharedPreferencesExactVideoEncoderQualificationStore`
and `SharedPreferencesLegacyDecoderQualificationStore`) remain the authorities
for their detailed codec keys. `PreparedHostCapabilityCollector` remains the
in-memory owner of the WNCP-facing Host snapshot. None previously persisted the
complete privileged provider/capability matrix; this bounded metadata store fills
that gap without replacing their domain-specific qualification databases.

When the user requests Host, the same generation is upgraded to the existing
acquisition/admission path. That late path performs bounded qualification,
selects the RFC-006I plan, and publishes `READY` only with
`FROZEN_AT_HOST_ADMISSION`. It must release resources if the generation becomes
stale or Host startup is abandoned.

## Single-Flight and Generation Ownership

There is exactly one active readiness generation for a given relevant runtime
identity and invalidation epoch. The UI and `Enable Host` action observe that
same operation:

```text
BackgroundHostReadinessCoordinator
        -> readiness generation G
             -> UI observer
             -> Enable Host observer
```

Pressing `Enable Host` while `CHECKING` attaches to G; it must not initiate a
second concurrent provider startup or qualification. Cancellation, network
change, app shutdown, or a later invalidation creates a newer generation. A
completion may publish state only if its generation remains current. Provider
resources created by an obsolete generation must be released by their owner and
must never attach to a later prepared or frozen plan.

## Persistent Evidence and Invalidation

Persistent capability evidence and volatile runtime observations are distinct.
Capability evidence must remain keyed at least by the existing compatibility
key plus provider, capability, Warpnect/provider implementation version, Android
build, and artifact identity where applicable. Volatile state must carry an
observation time and must not be promoted into a timeless compatibility claim.

Step 2 implements `PersistentCapabilityEvidenceRecord` and
`PersistentCapabilityEvidenceKey` under `platform.privileged.readiness`.
`CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE` is used only when
the immutable matrix is reconstructed; the stored record retains its original
live provenance. Cached evidence is therefore visible in diagnostics without
being mislabeled as a fresh qualification.

At background startup the coordinator loads and validates evidence on its
non-Main dispatcher, then combines reusable records with a fresh volatile
runtime snapshot. It never calls DirectShell acquisition, starts a feature
server, starts capture, binds a Warpnect Shizuku UserService, or starts media.
Only an acquired DirectShell runtime, or an idle/released registered runtime with
Wireless Debugging explicitly enabled, or a currently ready selected Shizuku
runtime can support an advisory plan. An idle acquisition candidate is not proof
of ADB reachability or authorization: these are checked at late admission.
Wireless Debugging disabled,
authorization required, unsupported, unusable, or temporarily unavailable
runtime states override cache reuse. They do not erase durable evidence.

Relevant invalidation events include:

```text
Wi-Fi connectivity or BSSID change
local address or Wireless Debugging state change
ADB pairing, authorization, endpoint, or runtime-process loss
Shizuku Binder/server death or authorization change
Root authorization change
Android build or app/provider implementation upgrade
runtime health failure
explicit Host disable or provider cleanup
```

Known compatibility-key mismatch removes only the affected provider/capability
coordinate. A temporarily unknown Shizuku version retains historical evidence
only if all other known key dimensions match, and withholds it from reuse.
Negative results are not persisted. Existing positive evidence is invalidated
only for explicit contract contradictions: unsupported Android/format, absent
hidden capture API or AudioPolicy API, absent injection API, or unsupported display
targeting. Generic `QUALIFICATION_FAILED`, provider crashes, unavailable services,
authorization failure, resource exhaustion and one-shot DEBUG suppression do not
erase it. A new cheap `QUALIFIED` observation cannot downgrade matching `VALIDATED`
history. No arbitrary age expiration is applied.

A read-only Wireless Debugging content observer refreshes advisory generations
when that setting changes. It does not migrate or invalidate a frozen active Host
contract. All Host requests still obtain a fresh snapshot before acquisition.

Only affected coordinates should become `STALE`; this must not force broad
continuous polling or discard unrelated qualification evidence.

## Prepared Plan and Frozen Plan

A prepared plan may be computed in the background from a current matrix. It is
advisory and carries the readiness generation and evidence provenance. It may
be invalidated at any time before Host admission.

Step 2 can reconstruct a complete matrix from valid cached records and run the
unchanged RFC-006I planner before the user requests Host. A complete result is
published only as `PreparedPrivilegedCapabilityPlan(ADVISORY)` with the
readiness generation, volatile snapshot, cached matrix, selected compatibility
key hashes, and planner output. It cannot publish Host availability or reuse a
stale runtime server. A cold or partial cache remains `STALE`.

The active Host request is still the sole late admission boundary. It refreshes
volatile runtime state, acquires the chosen provider resources, runs bounded
qualification as needed, lets RFC-006I perform its pre-freeze replan behavior,
and publishes `CURRENT_HOST_ADMISSION` with `FROZEN_AT_HOST_ADMISSION` only
after success. Invalidating an advisory plan releases no provider resources;
invalidating a frozen plan uses the existing admission release path.

`LateRevalidatedCapabilityEvidence` merges matching durable evidence only with
currently usable feature and codec observations, retaining cached provenance
and binding it to the current runtime generation. A current failed observation
always wins. Missing coordinates retain live provenance. Diagnostics therefore
report `CACHED` for background plans and `MIXED` for late revalidated cached plans.
The current DirectShell lifecycle requires fresh feature-server identity checks
and detailed controller/codec queries for each new acquired generation; these
bounded checks are intentionally not bypassed by a coarse compatibility record.
They do not start capture or replay an audio/video qualification soak. Within a
Host admission the existing prepared collector prevents a second full collection.
No second RFC-006I freeze or provider-acquisition path is introduced.

At `Enable Host`, the required sequence is:

```text
reuse or await one readiness generation
  -> revalidate volatile assumptions
  -> acquire or retain only selected provider resources
  -> bounded RFC-006I replan if pre-freeze acquisition changed reality
  -> freeze the complete plan
  -> publish Host
  -> discovery
  -> WNCP
  -> Session
```

This preserves the rule: precompute the decision early; commit to the decision
late. No prepared plan can advertise Host capability, reserve a Session, or
silently become a frozen plan after its state is stale.

## Planner Relationship

RFC-006I remains the sole complete-plan policy. The orchestration layer must
preserve its ordering:

1. complete Host and valid qualification threshold;
2. minimum provider count;
3. strongest demonstrated real-world evidence and reliability;
4. material performance difference where evidence establishes one;
5. provider preference among otherwise comparable plans;
6. operational cost where it has a concrete measured meaning; and
7. deterministic canonical tie-break.

The conceptual provider preference remains `DIRECT_ROOT > DIRECT_SHELL >
SHIZUKU`, but it never overrides completeness, evidence, reliability, or the
provider-count rule. No device, OEM, or blind API allowlist is introduced.

## Root Has Two Independent Roles

Root as a capability provider and Root as a recovery authority are independent:

```text
Root capability provider:
  DirectRoot directly supplies Video, SystemAudio, or Input.

Root recovery authority:
  a tightly scoped recovery operation restores another runtime, such as an
  eligible DirectShell prerequisite; that other runtime may remain the selected
  provider.
```

The laboratory authority foundation is implemented independently of those
future recovery operations. The Input-only capability follow-up is implemented
and exact-lab QUALIFIED; Root media providers and actual remediation remain
`NOT_IMPLEMENTED`. There is no generic Root command or remediation path.

## Remediation Policy

A future `PrivilegedRuntimeRemediationCoordinator` may take a runtime snapshot
and explicitly available recovery authorities, then return updated bounded
evidence. It must be separate from qualification and plan selection.

Automatic remediation may be considered only for an explicitly recoverable,
non-user-disabled state with a known safe action, bounded retry semantics,
post-action verification, and cleanup. It must not be attempted for
`UNSUPPORTED`, `NOT_IMPLEMENTED`, ambiguous intent, or a security authorization
boundary.

The future policy must never mean:

```text
user disables Wireless Debugging
  -> Warpnect silently enables it again

Root exists
  -> Warpnect exposes arbitrary root command execution
```

When user intent cannot be distinguished from transient failure, the policy
must conservatively report `USER_ACTION_REQUIRED` rather than override it.
Root-assisted remediation is therefore designed here but not implemented.

## User Experience Model

The UI exposes a small, truthful readiness model:

```text
CHECKING
READY
USER_ACTION_REQUIRED
UNSUPPORTED
FAILED
```

Internal `NOT_STARTED`, `STALE`, `AUTHORIZATION_REQUIRED`, and `RECOVERING`
states may map to one of those views with an exact structured reason. If a user
presses `Enable Host` while checking, a small modal, sheet, or popup observes
the existing operation. It may identify truthful completed or active steps, but
must not fabricate percentage progress or claim a feature is qualified before
its real probe completes.

Successful readiness advances to ordinary Host admission. Meaningful failure
or user action must remain visible until dismissed or resolved. Client mode
remains available whenever Host is not.

Step 1 presents the preparation surface only after a user requests Host. It
maps `CHECKING` to a non-dismissible preparation state, briefly presents
`READY`, and leaves authorization, user-action, unsupported, and failed results
visible until dismissed. Dismissing the surface changes presentation only; it
does not erase the underlying runtime state.

## Session Health and Future Recovery

The plan remains frozen for a Session. This RFC introduces no live substitution
or new wire state. A future recovery hierarchy is only conceptual:

```text
capability health failure
  -> bounded same-provider recovery
  -> future provider migration if separately admitted
  -> controlled Session teardown when no safe replacement exists
```

Same-provider recovery must first reset and release capability-specific state:
Input releases contacts, keys, buttons, and joystick state; SystemAudio ends the
old capture epoch; Video releases the old mirror/Surface relationship and
requires a fresh keyframe/resynchronization path as needed. Provider migration,
Session generation interaction, rollback, atomic commit, and protocol changes
remain deferred to the dedicated migration RFC. Frozen SCL, payload, Session,
and native ABI contracts are unchanged.

## Security and Privacy

The orchestration design preserves:

* explicit Wireless Debugging, Shizuku, and future Root authorization;
* DirectShell per-generation secrets, HMAC, anti-replay, and artifact identity;
* purpose-specific privileged operations rather than arbitrary shell, file,
  descriptor, Surface, or root brokers;
* no logging of pairing codes, secrets, endpoint tokens, credentials, raw audio,
  or user input content;
* capability truthfulness before Host publication;
* no network peer control over provider selection, remediation, or privilege;
* complete Host-only admission and clean capability-specific teardown.

## Telemetry

Step 1 emits a bounded, non-sensitive `HOST_READINESS_STATE` diagnostic with
state and generation. Future diagnostics may add names such as:

```text
host_readiness_generation
host_readiness_state
runtime_discovery_result
runtime_state_transition
runtime_remediation_attempt
runtime_remediation_result
capability_evidence_source
prepared_plan_selected
prepared_plan_invalidated
host_plan_frozen
```

They must remain generation-scoped and non-sensitive. They do not imply a new
network protocol or diagnostic persistence policy.

## Step 1 and Step 2 Verification

The implemented slice demonstrates:

1. application UI startup stays non-blocking;
2. one shared readiness operation serves both background observation and
   `Enable Host`;
3. stale generations cannot publish over newer invalidations;
4. volatile observation is kept distinct from the existing compatibility
   evidence/cache ownership;
5. Host is never advertised before late revalidation and RFC-006I plan freeze;
6. `USER_ACTION_REQUIRED`, unsupported, and failed states are distinguishable;
7. no background operation leaks feature servers, capture, audio, input state,
   secrets, or provider helpers; and
8. existing Session behavior and all frozen protocols remain unchanged.

The JVM suite covers asynchronous startup, single flight, Host joining an
in-flight observation, stale-generation suppression, disabled Wireless
Debugging, API26 DirectShell unsupported state, DirectRoot not implemented,
healthy DirectShell without Shizuku admission, staged Shizuku qualification,
resource release, and UI presentation semantics. Step 2 additionally covers
empty/valid/corrupt/schema-mismatched storage, unknown records, exact reuse,
Android/build/backend/provider-version invalidation, retained unavailable
Shizuku evidence, transient-failure non-persistence, authoritative
contradiction, cached DirectShell-only and synthetic composite plans, advisory
plan invalidation, and mandatory late admission after an advisory result.

On Tablet/API33 and A41/API31, a passive app launch completed without a
DirectShell launch or Shizuku UserService. User Host admission selected a
single DirectShell plan, deferred Shizuku, reached Host Available, and released
all three DirectShell feature servers on Host disable. Tablet re-acquisition
also produced one readiness generation and one runtime acquisition under a
double Host action. No deliberate audio tone or Session was required for this
lifecycle validation.

### Step 2 Process-Restart Evidence - 2026-09-26

The previous validated Step 1 slice was checkpointed locally as `569155a`
(`feat(privileged): add background Host readiness orchestration`). Step 2
source, tests, and documentation remain uncommitted for review. The branch is
`codex/directshell-production-runtime-integration`; master remains `032b592`.
No merge, push, data clearing, reboot, or desktop privileged launch occurred.

Exact installed debug APK on both devices:

```text
SHA-256: A96B720589ACB25DEF4D4A3734B5EAA9968E2581A1CE3119D887EDC5AF4A4049
Size: 33,784,804 bytes
Build timestamp UTC: 2026-09-26T15:44:59.0817562Z
ABIs: arm64-v8a, armeabi-v7a, x86_64
Source: 569155a plus the uncommitted Step 2 implementation
```

Installed `base.apk` SHA-256 matched this artifact on each device. Installation
completed before qualification; no installer overlapped the counted runs.

| Device | Android | Build fingerprint |
| --- | --- | --- |
| Samsung A41 / SM-A415F | 12 / API31 | `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys` |
| Tablet / X700_EEA | 13 / API33 | `Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys` |

| Step 2 boundary | A41/API31 | Tablet/API33 |
| --- | --- | --- |
| Successful normal Host admission populates evidence | PASS: three DirectShell `QUALIFIED` records | PASS: three DirectShell `QUALIFIED` records |
| Semantic Disable Host before restart | PASS: feature servers and wrappers exited | PASS: feature servers and wrappers exited |
| Real app-process restart, no data clear | PASS: PID 5211 -> 6463 | PASS: PID 7232 -> 7538 |
| Persistent cache after restart | `VALID hit=3 retained=3 invalidated=0` | `VALID hit=3 retained=3 invalidated=0` |
| Background plan | `SINGLE / DIRECT_SHELL`, `CACHED`, `ADVISORY` | `SINGLE / DIRECT_SHELL`, `CACHED`, `ADVISORY` |
| Feature processes before Enable Host | DirectShell 0; Warpnect Shizuku UserServices 0 | DirectShell 0; Warpnect Shizuku UserServices 0 |
| UI before Enable Host | Ready; Host Not checked; no preparation dialog | Ready; Host Not checked; no preparation dialog |
| Enable Host after restart | `MIXED reused=3 late_revalidated=true` | `MIXED reused=3 late_revalidated=true` |
| Final plan and UI | `SINGLE / DIRECT_SHELL`, `FROZEN_AT_HOST_ADMISSION`; Host Available | `SINGLE / DIRECT_SHELL`, `FROZEN_AT_HOST_ADMISSION`; Host Available |
| Final Disable Host | PASS: no remaining DirectShell feature server or wrapper | PASS: no remaining DirectShell feature server or wrapper |

These are real qualifications, not imported/seeded cache records. The records
remain `QUALIFIED`, not promoted to `VALIDATED` merely because the persistence
mechanism passed its hardware test. The prior A41 Shizuku manager/server remained
present, but no Warpnect Shizuku UserService was launched or used for admission.
Both final plans logged `shizuku_started=false`.

Attempt accounting is explicit. A41 evidence population passed 1/2 attempts:
the first attempt had no published `_adb-tls-connect` endpoint and failed
boundedly with `WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE` after 90.100 seconds.
Desktop discovery also returned no service, no feature server started, and no
Shizuku fallback ran. A normal Settings OFF/ON restored the daemon's publication;
the subsequent population was a new recorded attempt, not a hidden retry.
A41 warm admission then passed 1/1, giving 2/3 total Host admission attempts.
Tablet population and warm admission each passed 1/1, giving 2/2 total.
The required process-restart/cache-to-freeze proof passed 1/1 on each device.

The Tablet initially had Wireless Debugging disabled. Passive cold startup
reported `USER_ACTION_REQUIRED / WIRELESS_DEBUGGING_DISABLED`, not a fabricated
plan. Enabling it through normal Settings reused the existing Warpnect pairing.
No pairing code, credential, or media content was persisted in the evidence file.

After the A41 warm admission and clean disable, a verified Wireless Debugging
OFF transition at 18:09:11.214 invalidated advisory readiness. At 18:09:11.269
the cache still had three valid records, and at 18:09:11.290 readiness became
`USER_ACTION_REQUIRED / WIRELESS_DEBUGGING_DISABLED`. Re-enabling restored an
advisory cached plan without acquiring a feature server. Wireless Debugging was
left enabled on both devices; both Hosts were disabled at completion.

Observed logcat times are device-local CEST (`Europe/Zurich`, UTC+02:00), not a
cross-device synchronized performance benchmark:

| Observation | A41/API31 | Tablet/API33 |
| --- | --- | --- |
| Warm background readiness starts | 18:01:25.448 | 18:10:52.032 |
| Persistent evidence loaded | 18:01:25.479 | 18:10:52.107 |
| Advisory plan selected | 18:01:25.496 | 18:10:52.153 |
| Advisory READY | 18:01:25.497 | 18:10:52.155 |
| User Host request | 18:02:18.085 | 18:11:47.873 |
| Late evidence revalidation | 18:02:18.115 | 18:11:47.921 |
| Evidence reused in current matrix | 18:02:25.853 | 18:11:58.302 |
| Frozen READY, preceding Host publication | 18:02:25.869 | 18:11:58.329 |

Background readiness took 49 ms and 123 ms in these individual samples. Warm
Host request-to-freeze took 7.784 s and 10.456 s, including the current feature
server generations and mandatory bounded controller/codec checks. Android
`am start -W` reported cold UI launch TotalTime of 1,808 ms and 3,638 ms; there
was no separate instrumented absolute UI-first-frame timestamp. Host Available
was verified through the actual UI hierarchy after freeze. No broad latency
claim is made from these two observations.

Local evidence is under `artifacts/rfc006j/step2-*`: cache-only preference
snapshots, filtered readiness/plan logs, UI XMLs, process inventories, and the
exact APK. Laboratory artifacts are not part of the Step 1 checkpoint. The
hardware run required no Session, test tone, remote input, or media recording.
It does not repeat or extend the historical audible/video compatibility matrix.

Software gates passed: `git diff --check`, `:app:ktlintCheck`, `:app:lintDebug`,
`:app:testDebugUnitTest` (including a `--rerun-tasks` run), `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`, and `:app:packageDirectShellProbeDex`. Exact JVM
total: 754 tests, 0 failures, 0 errors, 0 skipped, in 134 suites. The first lint
run rejected a restricted Shizuku patch-version API; the final implementation
uses public API/UID plus installed-manager metadata and final lint passed.
Native production source was unchanged, so native CTest was not rerun.

```text
PERSISTENT_CAPABILITY_EVIDENCE_AND_PREPARED_PLAN_VALIDATED
```

## Non-Goals

This RFC does not implement:

* DirectRoot media providers, production Root authorization or arbitrary root command execution;
* automatic Wireless Debugging or Shizuku startup/recovery;
* root-assisted remediation;
* same-provider runtime recovery;
* live provider/capability migration;
* a partial normal Host;
* any SCL, payload, WNCP, WNSN, Session, or native ABI revision.

## Implementation Status

```text
Runtime State Model: IMPLEMENTED / TESTED / VALIDATED (A41/API31, Tablet/API33)
Background Host Readiness: IMPLEMENTED / TESTED / VALIDATED (A41/API31, Tablet/API33)
Persistent Capability Evidence: IMPLEMENTED / TESTED / VALIDATED (A41/API31, Tablet/API33)
Selective Invalidation: IMPLEMENTED / TESTED / VALIDATED (volatile change on A41; exact-key policies JVM-tested)
Prepared Plan Reuse: IMPLEMENTED / TESTED / VALIDATED (A41/API31, Tablet/API33)
Root-assisted Remediation: NOT IMPLEMENTED
DirectRoot Runtime Foundation: IMPLEMENTED / TESTED / EMULATOR-VALIDATED (AOSP/API26)
Production Root Authorization: NOT IMPLEMENTED
DirectRoot Input: IMPLEMENTED / TESTED / QUALIFIED (exact AOSP/API26 LABORATORY_EXTERNAL_ROOT; no physical claim)
DirectRoot Video: IMPLEMENTED / TESTED / QUALIFIED (exact AOSP/API26 LABORATORY_EXTERNAL_ROOT; app-owned encoder, real decoded content)
DirectRoot SystemAudio: NOT IMPLEMENTED (`NOT_IMPLEMENTED` in the capability model)
Root Recovery Authority availability: IMPLEMENTED / TESTED / EMULATOR-VALIDATED; no executable remediation actions
Physical Root Validation: NOT VALIDATED
Same-provider Recovery: NOT IMPLEMENTED
Live Provider Migration: NOT IMPLEMENTED
```

## Next Implementation Step

DirectRoot SystemAudio Capability, after independently qualified laboratory Input and Video slices.

That work must not infer capability support from UID0 authority: it requires
separate capability implementation/qualification. Production acquisition still
requires explicit root-manager authorization; actual remediation requires a
separately reviewed finite action policy. Same-provider recovery and live
provider migration remain out of scope.

## DirectRoot Authority Foundation

`DirectRootRuntime` owns a `RootRuntimeBootstrap` adapter and an authenticated,
finite `RootControlSession`. `DirectRootRuntimeRegistry` exposes only a volatile
snapshot. The DEBUG adapter's provenance is `LABORATORY_EXTERNAL_ROOT`; future
`PRODUCTION_ROOT_MANAGER` acquisition must require the root manager's explicit
authorization and is NOT IMPLEMENTED. ADB root is not app Root authorization.

States distinguish production bootstrap absence, lab/helper absence, acquisition,
authenticated UID0 readiness, failure, loss and release. `RootRecoveryAuthority`
observes that authenticated authority separately from capability provision; its
supported action set is empty. No settings/property/permission/service mutation
or generic Root command is enabled.

At the foundation checkpoint, the readiness mapper retained the typed observation
and refreshed passive background state when it changed. A READY Root authority cannot make
Host ready: RFC-006I cells and acquisition then marked all Root capabilities
NOT_IMPLEMENTED. No Root-helper liveness, lab adbd state or secret is persisted
as capability evidence. Physical devices retain the truthful production-bootstrap
NOT_IMPLEMENTED state without a fabricated authorization prompt.

The helper reuses the existing HMAC envelope and bounded IPv4 loopback control.
Fresh generation, connection challenge, ordered requests, APK integrity and
UID0 checks bind acquisition. One authenticated owner, connection-loss shutdown,
idle limit and absolute laboratory expiry bound lifetime. No peer-UID claim is
made for TCP. See `../phase6_5/DirectRootBootstrapFoundation.md` for the launch trust
model, emulator evidence and deferred production/physical boundaries.

API26 validation: eight formal cases passed 8/8 without retry and two separate
resource cycles passed 2/2; six authenticated UID0 acquisitions on the exact APK.
Root authority READY retained all three capability cells as NOT_IMPLEMENTED.
App death, stale secret, wrong generation, duplicate owner, helper loss, idle
expiry and UID2000 rejection were exercised; final helpers/listeners/bootstrap
files were zero. A41/API31 regression retained SINGLE / DIRECT_SHELL and Host
Available with Shizuku deferred; Tablet was not connected. The final JVM suite
contains 783 tests with zero failures/errors/skips. Exact identities, artifact
hash and attempt accounting are in the focused foundation report.

## DirectRoot Input Qualification Follow-up

The validated foundation was locally checkpointed as `e762a1a` before Input work.
`DirectRootInputInjectionGateway` now exposes only the existing typed Input
operations through the authenticated Root session, bound to its runtime
generation. The helper reuses the shell path's canonical codec, local privileged
service implementation, reflective resolver, event factory and state tracker;
no Shizuku bootstrap/Binder call or third injector is introduced. Root helper
identity version 2 / `warpnect-root-input-v1` distinguishes the new implementation.
The reused WNDP/WNIS local framing and Input Payload V1 remain unchanged.

The helper explicitly reports INPUT implemented and Root Video/SystemAudio
NOT_IMPLEMENTED, with remediation NONE. A launch/UID0 assertion or API return
value cannot create positive capability evidence: explicit owned-target receipt
and reset qualification are required. Additional finite RPC bounds complement
the canonical action/order validation; no generic commands or arbitrary Android
object deserialization exist. Wrong/stale generation, replay, malformed/oversized
frames and authentication errors fail closed. Loss moves volatile runtime out of
READY; no retry onto another helper/provider or automatic Root recovery occurs.

The actual API26 framework resolves legacy InputManager but lacks InputEvent's
display setter. Only genuine method absence permits display 0; nondefault display
remains rejected and security/reflection failures do not trigger that allowance.
This is framework-driven, not an API/device allowlist. The existing modern path
and production tracker/reset policy are preserved.

Final exact APK SHA-256:
`713A775E7350ECADB77D9C368C90DAB4B0845044CF60E1C09DAC54F7E82F33E1`.
On the unchanged Enforcing AOSP/API26 x86_64 lab, **3/3** fresh generations
observed real key/touch/pointer/joystick receipt, ordered key/touch flow and complete
reset to zero active state. Helpers observed UID/GID0 and u:r:su:s0; helper FDs
remained 30 before/after and app FDs 65 across those three cycles. A separate
**1/1** active-key/touch connection-loss case observed KEY_UP/TOUCH_CANCEL before
helper exit. A wrong-HMAC typed Input request and an old gateway against a new
generation were rejected. Quiescent helper termination produced HELPER_LOST;
compatibility evidence survived and no remediation occurred.

An earlier counted artifact failed **0/1** due to an incorrect DEBUG expected
cross-slot reset order despite actual receipt of all releases; only that fixture
was corrected. Three exploratory boundary failures are also preserved. The
final 3/3 campaign contains no retries. Exact identities, target observations,
timestamps and attempt accounting are in `../phase6_5/DirectRootInputQualification.md`.

Process restart loaded one scoped Root Input record with runtime unavailable,
not falsely READY. Passive readiness emits no Input; Root Input alone still
cannot produce a Complete Host plan. Exact JVM cases retain Root-only incomplete,
synthetic DirectShell Video/SystemAudio plus Root Input COMPOSITE, and complete
DirectShell SINGLE preference without changing ranking. Root evidence is
QUALIFIED for the tested lab, not general VALIDATED/physical support.

Software gates passed with **807 tests, 0 failures/errors/skips**, ktlint, lint,
debug/androidTest builds and probe packaging, plus diff and PowerShell safety
checks. Native source was unchanged. Final helpers/listeners/private bootstrap
files were zero; adbd returned to UID2000 and the AVD stopped. A41's same-build
non-Root control prepared advisory SINGLE / DIRECT_SHELL, but live admission
failed **0/1** at WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE before feature checks;
no new successful physical admission is claimed. Root remained unselected,
no fallback/helper remained, and the UI returned to Ready after dismissal.

Hard SIGKILL cannot execute synthetic releases; that residual is surfaced with
stateMayRemainInjected rather than concealed. Production Root authorization,
Root Video/SystemAudio, actual remediation, same-provider recovery and live
migration remain NOT_IMPLEMENTED. No physical Root qualification is claimed.

```text
DIRECTROOT_INPUT_CAPABILITY_VALIDATED_IN_AOSP_LAB
```

## DirectRoot Video Qualification Follow-up

Input was locally checkpointed as `ae431d9`. The new Video work is uncommitted.
`DirectRootVideoCaptureGateway` is bound to authenticated UID0 authority and its
generation. Finite typed VIDEO_QUALIFY/START/UPDATE/STOP/DIAGNOSTICS control uses
the foundation HMAC envelope and ordered requests. Helper identity is version3 /
`warpnect-root-video-input-v1`; network Video/Input/Audio, SCL and Session formats
are unchanged. SystemAudio is explicitly NOT_IMPLEMENTED; recovery actions NONE.

The original app encoder Surface crosses Binder via a single-use DEBUG provider
lease bound to provider kind, UID0, helper PID, Root/Surface generations, geometry,
HMAC and expiry. Shell's existing method remains UID2000-only. The helper reuses
QualifiedPrivilegedDisplayCaptureApi and the existing projection planner; it owns
only mirror control and its parceled Surface reference. MediaCodec and AVC stay
in the normal app, never on the Root TCP control channel.

The exact Enforcing AOSP/API26 x86_64 APK passed 3/3 fresh Root/Surface generations
with app-owned AVC output and offline decoded fixture A/B observation. Negative
stale generations, wrong HMAC/provider/magic and consumed lease were rejected.
One current-artifact Input regression also observed ordered target events/reset.
Two separate active-capture loss cases passed: helper TERM invalidated liveness
and tore down encoder/mirror; app force-stop caused owner EOF and helper cleanup.
Persisted VIDEO+INPUT records survived restart, but runtime/capture/lease did not.

Evidence is QUALIFIED only for the exact laboratory key and provenance; Root
SystemAudio remains rejected from persistence. Background startup creates no
encoder, mirror, lease or helper. Root-only planning remains HOST_INCOMPATIBLE,
missing SYSTEM_AUDIO. Exact synthetic complete-Root/composite/complete-Shell
preference cases pass without planner ranking changes. No partial Host exists.

All gates passed:841 JVM tests,0 failures/errors/skips, ktlint, lint, debug and
androidTest builds, probe packaging, diff and PowerShell physical-target guards.
No native source changed. Final Root helpers/listeners/leases/bootstrap files,
unexpected encoder threads and active codec resources were0; adbd returned to
UID2000 and the AVD stopped. App graphics FD counts varied modestly, so no
indefinite-soak leak-freedom claim is made. An optional Tablet non-Root control
reached Host Available once; a second attempt failed at runtime preparation.
No complete new physical media regression is claimed from those UI observations.

See `../phase6_5/DirectRootVideoQualification.md` for exact APK/hash, generations,
AVC metrics/images, failed diagnostic preservation and resource limitations.
Production Root authorization, physical Root, SystemAudio, remediation,
same-provider recovery and live migration remain deferred.

```text
DIRECTROOT_VIDEO_CAPABILITY_VALIDATED_IN_AOSP_LAB
```
