# Implementation Prompt: RFC-006J Pairing Enrollment Owner (P1)

Executed for P1 only on isolated `codex/phase6-5-pairing-enrollment-owner`, from
faa9f0f, after explicit user authorization. [Receipt](DirectShellPairingEnrollmentOwner.md).
The original bounded task is preserved below, not a request to repeat it or
start P2-P5. P1 is a local delivery slice, not a new RFC identifier.

---

Continue on a new isolated branch/worktree from the latest reviewed
`codex/phase6-5-directshell-pairing-setup` source and documentation, not by
restarting from656c378 or copying the original research tree. Verify HEAD and
clean status. Preserve source checkpointf7dd2cf, receipt2a6528d, all G1-G5/Video
branches, evidence and Phase7 R2. Read:

- `docs/phase6_5/CURRENT_HANDOFF.md`
- `docs/phase6_5/DirectShellHostPairingSetup.md`
- `docs/phase6_5/DirectShellReleasePairingArchitecture.md`
- Accepted RFC-006I/J and the actual readiness/launch-guard source and tests.

Implement ONLY the smallest pure, application-owned, cancellable enrollment
coordinator and its narrow fake-backed contracts. This is groundwork for secure
Release onboarding, NOT activation of a Release backend or an end-user feature.

## Scope

Use the proposed paths, adapting names only for a demonstrated repository fit:

- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingContract.kt`
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinator.kt`
- `app/src/test/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinatorTest.kt`

Reuse existing structured readiness eligibility; do not duplicate the
`hostPairingSetupGeneration` policy. Keep the current HostPairingSetupController
as the Activity launch guard. The new owner models enrollment I/O/consent and
commit lifetime; it must not be another launcher or a general workflow framework.
Do not instantiate/wire it in normal composition during P1.

Define only internal typed contracts for a prepare-pair backend, cancellable/
drainable operation and staged-authorization commit/discard. Test fakes live in
test, not in a production fallback. No real network, NSD, library, credential
store, file or privileged operation may implement those contracts in P1.

Model the reviewed transitions: unavailable/idle, waiting for explicit consent/
code, discovery/pairing, authorization commit, paired-needs-explicit-Host-retry,
canceling, canceled/expired/failed and cleanup-incomplete. Use an injected
monotonic clock/scheduler and owner-epoch/nonce source so replays are deterministic.
Follow existing Kotlin/StateFlow conventions; no unnecessary dependency.

## Mandatory Invariants

1. Constructor, observation, cache restore and waiting for code invoke no backend,
   credential commit, helper or Host action. An explicit current user request,
   available backend capability and correct readiness reason precede enrollment.
2. At most one attempt/operation. Duplicate request/submit cannot queue work or
   create another worker. Backend begin follows valid explicit code submission.
3. Keep origin readiness generation, enrollment ticket/process epoch, network
   epoch and credential revision separate from runtime/Session generation.
4. Bound consent expiry and operation/cleanup deadlines as design acceptance
   values, not edits to existing ADB/audio timing. Logical timeout is NOT proof
   that actual I/O closed. Retain the occupied slot until fake physical drain
   confirms; CleanupIncomplete rejects replacement work.
5. Check current ticket/epochs/revision at fake authorization commit. Test cancel
   before and after the defined atomic commit point. Stale callbacks cannot
   publish trusted state or overwrite a newer attempt.
6. Process restart expires prior consent; no automatic resume/retry. Rotation
   reattaches to the same owner and does not resubmit code. Host-origin cancellation
   withdraws consent; passive readiness refresh is not a new authorization.
7. Secret input has bounded lifetime, redacted toString and best-effort wiping.
   No code/private key/credential payload in StateFlow, Bundle-like snapshots,
   logging, exceptions or outcomes. Do not claim immutable string copies can
   always be zeroized. Use opaque fake staged handles, not secret material.
8. Paired outcome NEVER calls startHost, Registry.acquireForHost, publication,
   planner/cache qualification or another provider. Existing explicit Enable Host
   and COMPLETE qualification/freeze are unchanged.
9. Client operation and existing DEBUG setup remain unchanged. Current Release
   adapter stays null; libadb/BC and DEBUG/lab classes stay excluded from Release.

## Deterministic Tests

Add focused tests for passive zero calls, reason/adapter/generation mapping,
duplicate request and code submit, invalid/expired code, denied permissions,
success/failure, timeout and cancel at each modeled stage, deferred close,
cleanup-incomplete backpressure, late duplicate results, old owner/process token,
network/key revision changes, fake commit/cancel order, exactly-once disposal
and code canaries absent from status/error rendering. No sleeps or real devices.
Use suspend-inclusive monotonic deadlines and recheck expiry at submit/result/
commit; a late timer event must not extend consent. Do not change media clocks.

Keep tests honest about the difference between fake physical drain and actual
libadb cancellation. The inspected3.4.0 pair call has no forwarded manager timeout
or externally owned handshake socket; solving that is a separate P2 gate.
Do not write a second ADB/SPAKE2/TLS implementation to bypass it.

## Exclusions And Gates

No Android Activity/service/notification/manifest changes, no library or Gradle
dependency promotion, no credential migration, no Release setup action, no runtime
registration, no Root/Shizuku/provider changes, no generic shell APIs. No source
or parameter changes to media/SCL/Session/authentication/anti-replay,85/70ms ring,
8ms admission,4ms output, microphone freshness, recovery or Phase7 R2/S0.

Run focused and full JVM (exact count/delta), ktlintCheck, lintDebug,
assembleDebug, assembleDebugAndroidTest, packageDirectShellProbeDex and Release
build/source-set checks. Preserve current21 isolation expectations; do not weaken
them because a future backend is planned. Offline/native gates follow actually
affected paths; C++/JNI changes are outside this scope. Run diff hygiene and
preservation checks. Do not label software builds hardware validation.

No device access, installs, unpairing, Wireless Debugging changes, Root, UAC or
hardware campaign. No push/merge/reset/stash/clean, broad cherry-pick or automatic
commit. Do not implement P2-P5 or execute a further follow-up automatically.

Update the architecture/handoff with exact implementation, tests, bounded state,
limits and remaining real-backend acceptance. Report IMPLEMENTED/SOFTWARE TESTED
for the pure owner only; Release onboarding remains unavailable and hardware
validation NOT EXECUTED. Stop after this bounded increment and its report.
