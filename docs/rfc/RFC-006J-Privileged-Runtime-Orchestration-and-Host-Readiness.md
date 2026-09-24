# RFC-006J - Privileged Runtime Orchestration and Host Readiness

## Status

Accepted architecture. Step 1 is implemented, JVM-tested, and hardware
validated on A41/API31 and Tablet/API33.

The implemented slice supplies volatile runtime-state observation,
asynchronous background Host readiness, single-flight Host preparation,
generation-safe invalidation, structured readiness UI state, and late reuse of
the existing RFC-006I admission/freeze boundary. It does not implement
DirectRoot, root-assisted remediation, same-provider recovery, or live provider
migration.

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
* DirectRoot is `NOT_IMPLEMENTED`.
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

**UNKNOWN:** DirectRoot behavior, which runtime failures are safely recoverable
on each Android environment, and the protocol/lifecycle requirements for live
provider migration have not been implemented or hardware-qualified.

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
| DIRECT_ROOT | Structurally modeled only | `NOT_IMPLEMENTED` |
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
Binder access state. DirectRoot is reported as `NOT_IMPLEMENTED`. It does not
connect local ADB, launch `app_process`, start a DirectShell feature server, or
start a Warpnect Shizuku UserService while Host is not requested.

Existing lower-level compatibility evidence remains owned by the existing
admission path and is reused there when its cache dimensions remain valid. Step
1 deliberately does not create a new global persisted complete-plan cache. A
passive observation without a trustworthy complete plan therefore publishes
`STALE`, not a fabricated `READY` result.

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

Step 1 preserves this ownership boundary. It does not introduce a second
compatibility cache: `AndroidHostPrivilegedCapabilityAdmission` retains the
existing evidence/cache behavior when active Host admission needs it. The new
snapshot is volatile-only and `HostReadinessEvidenceSource` records whether a
visible plan came from existing compatible evidence or current Host admission.

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

Only affected coordinates should become `STALE`; this must not force broad
continuous polling or discard unrelated qualification evidence.

## Prepared Plan and Frozen Plan

A prepared plan may be computed in the background from a current matrix. It is
advisory and carries the readiness generation and evidence provenance. It may
be invalidated at any time before Host admission.

In Step 1, no new globally persisted complete-plan cache exists. Passive startup
therefore produces a `STALE` observation unless a future implementation can
prove a current complete plan from valid evidence. The active Host request is
the late admission boundary and publishes `PreparedPrivilegedCapabilityPlan`
with `CURRENT_HOST_ADMISSION` and `FROZEN_AT_HOST_ADMISSION` only after the
existing RFC-006I admission succeeds.

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

## Root Has Two Future Roles

Root as a capability provider and Root as a recovery authority are independent:

```text
Root capability provider:
  DirectRoot directly supplies Video, SystemAudio, or Input.

Root recovery authority:
  a tightly scoped recovery operation restores another runtime, such as an
  eligible DirectShell prerequisite; that other runtime may remain the selected
  provider.
```

DirectRoot is `NOT_IMPLEMENTED`. This RFC neither creates a root command path
nor grants Root a generic remediation authority.

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

## Step 1 Verification

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
resource release, and UI presentation semantics.

On Tablet/API33 and A41/API31, a passive app launch completed without a
DirectShell launch or Shizuku UserService. User Host admission selected a
single DirectShell plan, deferred Shizuku, reached Host Available, and released
all three DirectShell feature servers on Host disable. Tablet re-acquisition
also produced one readiness generation and one runtime acquisition under a
double Host action. No deliberate audio tone or Session was required for this
lifecycle validation.

## Non-Goals

This RFC does not implement:

* DirectRoot or root command execution;
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
Root-assisted Remediation: NOT IMPLEMENTED
DirectRoot: NOT IMPLEMENTED (`NOT_IMPLEMENTED` in the capability model)
Same-provider Recovery: NOT IMPLEMENTED
Live Provider Migration: NOT IMPLEMENTED
```

## Next Implementation Step

DirectRoot Bootstrap + Root Recovery Authority Foundation.

That work must not be inferred from Step 1: it will require an explicit root
authorization and command boundary, a separate capability provider contract,
and a separately reviewed remediation authority. Same-provider recovery and
live provider migration remain out of scope.
