# RFC-006I - Privileged Capability Planning and Composite Runtime

## Status

Accepted architecture. This RFC defines the next planning boundary only. It does
not implement a matrix, a planner, a composite Host, DirectRoot, provider
migration, or a protocol revision.

## Motivation

Warpnect has three required privileged Host capabilities:

    Video capture
    SystemAudio capture
    Input injection

The validated modern DirectShell path demonstrates that one provider can supply
all three capabilities on the tested API31, API33, and API36 devices. That is a
strong baseline, but it is not a sufficient final model for every Android
environment. A provider may be available for one capability and unavailable for
another. Historical evidence already shows this distinction:

* Tablet Shizuku 13.6 fails before UserService Binder publication, while
  DirectShell works for the tested modern capabilities.
* The historical S22 Shizuku SystemAudio path failed at an AudioRecord
  attribution boundary, while DirectShell SystemAudio captured real PCM.
* DirectRoot is a desired future provider but is not implemented.

Selecting one all-or-nothing runtime before qualifying individual
capabilities cannot represent those facts truthfully.

## Current Architecture

The current PrivilegedRuntime and PrivilegedRuntimeResolver form a
bootstrap-oriented seam. The automatic modern DirectShell registration acquires
the app-owned Wireless Debugging runtime before Host capability collection.
When that runtime is selected, a failed DirectShell acquisition is terminal for
that Host attempt; it does not silently start Shizuku.

The current production feature contracts remain separate:

    VideoSenderComponent
      -> privileged capture gateway
      -> normal-process MediaCodec and existing video transport

    SystemAudio capture controller
      -> existing PCM Shared Ring V1
      -> SharedPcmAudioDrain
      -> existing Opus and Audio Payload V1 path

    AndroidInputInjectionController
      -> existing resolver, event factory, and state tracker
      -> privileged injection gateway

This RFC preserves those contracts. It changes the future selection model, not
Video Payload V1, Audio Payload V1, Input Payload V1, PacketHeader, PayloadType,
SCL, WNCP, WNSN, WNSD, session protection, or the native ABI.

## Complete Host Invariant

Normal Warpnect Host mode is complete only when all required capabilities and
ordinary codec/session prerequisites have qualified:

    Video capture       REQUIRED
    SystemAudio         REQUIRED
    Input injection     REQUIRED
    Codec/session prerequisites REQUIRED

An assignment is invalid if any required capability is unavailable, insufficiently
qualified, unhealthy before admission, or cannot be owned safely for the Host
lifetime.

    no complete plan
      -> HOST_INCOMPATIBLE
      -> Host is unavailable
      -> Client mode remains available

Warpnect must not advertise, negotiate, or operate a deliberately partial
normal Host. A future limited product mode would require a separate product and
RFC decision.

## Provider Model

The future planning domain contains these providers:

| Provider | Current status | Intended role |
| --- | --- | --- |
| DIRECT_ROOT | Not implemented | Future highest-priority direct provider |
| DIRECT_SHELL | Validated on modern reference devices | App-owned non-root modern provider |
| SHIZUKU | Existing implementation retained | Optional compatibility provider |

DirectRoot remains represented truthfully as not implemented. This RFC neither
creates a root launcher nor implies root availability.

Shizuku is preserved, but its roadmap changes from unconditional deletion to
demotion as an optional capability provider. It is not started when a complete,
equivalently reliable DirectShell-only plan exists. It may later be selected
only through an explicit complete plan and per-capability qualification.

## Terms

PrivilegedCapabilityMatrix is the immutable result of qualifying each provider
for each required capability for one Host admission attempt.

PrivilegedCapabilityPlanner is the deterministic pure policy that chooses a
complete plan from a matrix.

PrivilegedCapabilityPlan is the frozen provider assignment for Video,
SystemAudio, Input, and the runtime instances required to own those assignments.

Composite is a property of a plan that uses more than one provider. HYBRID is
not a fourth runtime kind and must not appear as an execution provider.

For example, this is one composite plan:

    videoProvider       = DIRECT_SHELL
    systemAudioProvider = DIRECT_SHELL
    inputProvider       = SHIZUKU

## Per-Capability Qualification

Each matrix cell must carry a state, an exact non-sensitive reason where it is
not usable, relevant evidence, and version dimensions. The eventual Kotlin enum
names are intentionally deferred until the implementation is designed against
existing model conventions. The required semantic states include:

| Semantic state | Meaning |
| --- | --- |
| NOT_IMPLEMENTED | Provider or provider-capability adapter does not exist |
| NOT_AVAILABLE | Runtime cannot be acquired in the present environment |
| AUTHORIZATION_REQUIRED | User authorization is required before a meaningful probe |
| QUALIFICATION_FAILED | Real capability preflight failed with an exact reason |
| QUALIFIED | Current device preflight proved the capability is usable |
| VALIDATED | Qualifying hardware evidence and lifecycle evidence are recorded |

Capability qualification is stronger than a runtime process starting. It must
exercise the actual boundary needed by the capability:

* Video: privileged capture/mirror admission and usable Surface ownership.
* SystemAudio: capture prepare, AudioRecord start, and bounded real PCM
  evidence where appropriate.
* Input: actual privileged injection qualification and reset safety.

Historical evidence may inform a new admission attempt but must not be
misrepresented as a universal device, OEM, API, or provider rule.

## Capability Matrix

The matrix is conceptually:

| Provider | Video | SystemAudio | Input |
| --- | --- | --- | --- |
| DIRECT_ROOT | qualification cell | qualification cell | qualification cell |
| DIRECT_SHELL | qualification cell | qualification cell | qualification cell |
| SHIZUKU | qualification cell | qualification cell | qualification cell |

A cell contains more than PASS or FAIL. It records the qualification result,
reason, evidence level, relevant provider/runtime implementation versions, and
cache provenance. A plan may use a provider for one capability without implying
that the provider is qualified for the other two.

## Static Plan Selection

The first implementation is static: it completes before Host advertisement,
WNCP, and Session setup. The plan is not changed after it is frozen.

Candidate generation considers every assignment of qualified providers to the
three required capabilities. The planner then applies this deterministic order:

1. Reject incomplete plans and plans with an insufficiently qualified cell.
2. Prefer the fewest distinct active providers.
3. Prefer stronger demonstrated reliability and qualification evidence.
4. Among otherwise comparable plans, prefer provider use in this order:

       DIRECT_ROOT > DIRECT_SHELL > SHIZUKU

5. Prefer lower operational and lifecycle cost.
6. Apply a stable, documented final tie-break using the canonical capability
   order Video, SystemAudio, Input and the provider order above.

Reliability and evidence outrank nominal provider privilege. The policy must
not invent device allowlists, OEM allowlists, blind API branches, or arbitrary
numeric scores merely to produce a preferred answer.

The planner output is either one complete PrivilegedCapabilityPlan or a
HOST_INCOMPATIBLE result with the missing required capabilities and their
exact qualification reasons.

## Required Examples

### Example A - Complete DirectShell

| Provider | Video | Audio | Input |
| --- | --- | --- | --- |
| DIRECT_ROOT | N/A | N/A | N/A |
| DIRECT_SHELL | PASS | PASS | PASS |
| SHIZUKU | PASS | FAIL | PASS |

Result:

    DIRECT_SHELL_ONLY

DirectShell is complete and uses one provider. Shizuku must not be started.

### Example B - Two-Provider Composite

| Provider | Video | Audio | Input |
| --- | --- | --- | --- |
| DIRECT_ROOT | PASS | FAIL | PASS |
| DIRECT_SHELL | PASS | PASS | FAIL |
| SHIZUKU | PASS | FAIL | PASS |

Assuming equal qualification quality, the result is:

    Video       -> DIRECT_ROOT
    SystemAudio -> DIRECT_SHELL
    Input       -> DIRECT_ROOT

It is complete, uses two providers, and prefers DirectRoot over equivalent
alternatives for Video and Input.

### Example C - Shizuku Rescues One Capability

| Provider | Video | Audio | Input |
| --- | --- | --- | --- |
| DIRECT_ROOT | N/A | N/A | N/A |
| DIRECT_SHELL | PASS | PASS | FAIL |
| SHIZUKU | PASS | FAIL | PASS |

Result:

    Video       -> DIRECT_SHELL
    SystemAudio -> DIRECT_SHELL
    Input       -> SHIZUKU

This is a valid composite plan only because all required capabilities are
qualified. Shizuku is an explicit compatibility provider, not an implicit
fallback.

### Example D - Impossible Host

| Provider | Video | Audio | Input |
| --- | --- | --- | --- |
| DIRECT_ROOT | PASS | FAIL | PASS |
| DIRECT_SHELL | PASS | FAIL | PASS |
| SHIZUKU | PASS | FAIL | PASS |

Result:

    HOST_INCOMPATIBLE
    missing = SYSTEM_AUDIO

No partial Host is advertised.

## Publication, Freeze, and Failure Semantics

The admission order is:

    Host requested
      -> acquire and qualify candidates
      -> build capability matrix
      -> choose complete plan or HOST_INCOMPATIBLE
      -> acquire every provider required by the selected plan
      -> freeze plan
      -> publish truthful Host profile
      -> discovery and WNCP
      -> Session

If a provider cannot be acquired after selection, preflight fails, every
partially acquired provider is released, and Host remains unavailable. A plan
is frozen before discovery, WNCP, or Session. No provider may silently replace
another after negotiation, including a Shizuku substitution after a DirectShell
failure.

## Lifecycle and Ownership

The selected plan owns only the provider runtimes that it needs. For example:

    Host lifetime
      -> DIRECT_SHELL runtime
           -> Video
           -> SystemAudio
      -> SHIZUKU runtime
           -> Input

Host disable, failed preflight, Session teardown, and abnormal shutdown must
release all participating providers. A successful provider cannot keep another
provider alive accidentally. Existing feature-specific teardown remains
required: Input reset/release, audio capture/ring stop, and video mirror and
Surface release.

The implementation must make ownership inspectable by generation and must
prevent an old provider process, descriptor, secret, Surface, AudioRecord, or
input state from attaching to a later Host admission.

## Compatibility Cache

The current exact-key compatibility cache should not be generalized to a single
statement such as device X supports DirectShell. A future cache record must be
dimensioned at least by:

    existing compatibility key
      + provider
      + capability
      + relevant app/provider/runtime implementation versions

Invalidation must occur when the app capability implementation, provider
version, Android build/compatibility key, artifact identity, or meaningful
qualification inputs change. Cached evidence may reduce repeated work only
where the existing cache policy permits it; it cannot make an unqualified
capability appear ready.

## Telemetry and Diagnostics

The future implementation must produce bounded diagnostics such as:

    privileged_plan = SINGLE
    video_provider = DIRECT_SHELL
    system_audio_provider = DIRECT_SHELL
    input_provider = DIRECT_SHELL

or:

    privileged_plan = COMPOSITE
    video_provider = DIRECT_ROOT
    system_audio_provider = DIRECT_SHELL
    input_provider = DIRECT_ROOT

It must also retain qualification evidence, for example:

    directshell.video = VALIDATED
    directshell.audio = QUALIFIED
    shizuku.input = QUALIFIED
    root.audio = QUALIFICATION_FAILED_<reason>

Diagnostics must not log pairing codes, secrets, HMAC keys, endpoint tokens,
raw local ADB credentials, arbitrary provider command data, or user audio and
input content.

## Security Invariants

Composite planning must preserve every existing security boundary:

* authenticated local privileged control;
* fresh per-generation DirectShell secret handling;
* Session generation semantics and anti-replay;
* explicit user authorization for Wireless Debugging and Shizuku;
* no generic arbitrary shell command, file, Surface, or descriptor broker;
* truthful capability negotiation;
* no secret logging;
* no silent provider substitution after plan freeze.

The planner selects a known purpose-specific provider/capability adapter. It
does not create a capability for untrusted network peers to choose a provider,
run commands, or obtain an arbitrary privileged handle.

## Live Provider Migration Is Deferred

Live provider/capability migration is an approved architectural follow-up, but
is out of scope for this RFC's implementation. A dedicated future RFC,
In-Session Privileged Capability Provider Migration, must determine:

* Session generation interaction and migration admission;
* provider and capability runtime generations;
* security state, rollback, timeout, and atomic commit;
* health monitoring and replacement qualification;
* controlled Session teardown when no replacement exists.

The future capability-specific requirements are:

| Capability | Required future convergence |
| --- | --- |
| Input | Reset old provider state, release keys/touches/pointer buttons/joystick, establish replacement, converge state, then resume |
| SystemAudio | Quiesce old capture, terminate incomplete PCM epoch, establish a fresh capture epoch and encoder synchronization, then resume |
| Video | Release old mirror, establish replacement Surface relationship, force an IDR/keyframe, update StreamConfig only when required, and perform receiver resync |

A local concept such as CapabilityRuntimeGeneration may be sufficient for some
implementation ownership. It is distinct from Session generation in the
conceptual model. No new wire generation field may be added without the
dedicated migration RFC and evidence that a protocol amendment is necessary.
Architecture V1.0, SCL Protocol V1, and Native ABI V1 remain frozen.

## Non-Goals

This RFC does not:

* implement DirectRoot;
* activate Shizuku fallback or composite Host composition;
* change the current validated DirectShell selection;
* implement live migration;
* change protocol, payload, Session, or native ABI contracts;
* create a partial Host mode;
* add device, OEM, or blind API allowlists.

## Future Validation

The later implementation must add pure planner tests for:

* complete one-provider, two-provider, and three-provider plans;
* equivalent-plan tie breaking;
* reliability outranking nominal provider priority;
* no complete plan;
* deterministic output;
* per-provider/per-capability cache provenance.

Runtime integration tests must cover partial acquisition cleanup, multiple
provider lifetime, Host disable, truthful capability publication, no provider
leakage, and no Shizuku start when DirectShell is independently complete.

Hardware validation must eventually cover DirectShell-only modern devices,
intentionally qualified Shizuku compatibility cases, DirectRoot when it exists,
and at least one controlled composite plan.

## Acceptance Criteria for the Follow-On Implementation

The follow-on static planner is acceptable only when it:

1. selects a deterministic complete plan before Host advertisement;
2. makes Host unavailable if no complete plan exists;
3. keeps Client mode available;
4. does not silently change a frozen plan after negotiation;
5. preserves provider-specific qualification evidence and cache dimensions;
6. releases all partially acquired and selected providers correctly; and
7. leaves all frozen protocol and ABI contracts unchanged.
