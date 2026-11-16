# G4 - Optional Microphone Compatibility And Destination Continuity

This is a functional projection of preserved, previously implemented source on
3253518, after G1/G2/G3. It is not a new microphone architecture, a new provider,
a Phase7 audio policy, or a claim that every Android preset/API is qualified.

## Integration Boundary

One coherent G4 commit contains three internal ownership groups:

- G4A: MicrophonePolicyApi, legacy/package versus attributed shell context,
  capture-preset-only rule audit, real MODIFY_AUDIO_ROUTING check, policy and
  registration identity. Adapter availability never grants authority.
- G4B: AppOwnedRemoteMicrophoneSource, authenticated APP_PREPARE/APP_STATE
  control, public NDK AppOwnedMicrophoneClock, exact owned output, natural
  input drain and consumer window/close epoch, source revocation and cleanup.
  DirectShellMicrophoneInputSession implements G2's closing hook. Policy stays
  privileged; fresh PCM does not traverse the helper control/result socket.
- G4C: bounded destination queue/SDK write clock, native microphone admission,
  atomic ring observations, callback-credit recheck, age-bounded priming,
  SDK-clock startup, Kotlin/JNI native lifetime and independent DEBUG speaker
  observation, microphone worker priority and scoped protection priority floor.

The selected factory, helper policy, native clock and lifetime interfaces form
one integration cohort. They are kept together to avoid transitional commits
selecting an incomplete destination. Some adapter/primitive hunks could be
ordered separately; this is not a claim that no other valid split exists.
G1 liveness and G2 closing are real integration dependencies; G3 preserves the
Video configuration prerequisite. No planner/provider contract is changed.

## PCM And Clock Contract

Microphone DEBUG playout remains 80ms physical capacity /70ms initial reserve.
This is separate from the destination's 40ms freshness /1920-frame retention
bound at 48kHz mono PCM16. Its eight slots hold at most 960 frames each and do
not authorize more than 1920 retained frames in aggregate. No larger jitter
buffer, codec, packet duration or socket-buffer change is introduced.

No-consumer and pre-window real PCM is dropped, not replayed after OPEN/REOPEN.
Generation, window, enqueue age and post-copy freshness checks guard delivery.
The app-owned pump progresses independently of ACK/results and policy sampling.
Real PCM has priority over silence. With no consumer, maintenance silence keeps
the destination selectable. With an active consumer, bounded missing-ingress
silence is permitted only against observed SDK progress/deadline and is counted
separately. It does not fabricate original-source delivery or heal absent voice.
SDK estimates never grant PCM admission credits; zero/partial nonblocking writes
remain authoritative. Clock rollback/stale snapshots cannot renew drain credit.

The DEBUG speaker is a distinct destination. Its bounded existing handoff does
not backpressure Android-input admission; its drops/underruns are not relabelled
as input success. Native lifetime leases prevent destruction during ingress or
independent SDK observation. Close revokes ingress before joining and releasing
the Session's clock, drain and policy.

SystemAudio remains 85/70ms with its existing block-rounded 8ms admission and
default two-burst output (observed 4ms on S9), without the 24ms selector. Shared
atomic ring observations change synchronization, not PCM/wire formats or reserve.

## Retained And Excluded Dependencies

The old authenticated helper-feed commands remain compiled, but the selected
runtime factory does not choose DirectShellRemoteMicrophoneSource. Their feed,
destination, result, totals and native-clock classes are explicit legacy compile
dependencies, not fallback providers or additional hardware-validation claims.
Their bounded microphone numeric timing/terminal accounting remains in these
DEBUG helper classes. App-owned clock SDK sampling supplies functional drain
observations; its bounded numeric terminal journal contains no persisted PCM.

Excluded from the shared JNI/Oboe projection: optional protection/ring journals,
ATrace sections, SystemAudio Flight Recorder, socket timestamp diagnostics,
fault injection, backend/output selectors and standalone output probes. No
synthetic app-output activity/library is added. The ordinary existing consumer
test is retained without importing its later timing-probe additions. RF tools,
logs, screenshots, private state and Phase7 simulators/planning are not commits.

## Evidence Scope

Historical global-injection validation tokens retain their original scope.
The later r835 frozen APK prefix667B2 exercised S9/API29 -> Tablet/API33 MIC
OPEN65.096/65.150/65.123s and REOPEN25.133/25.129/25.092s, three Sessions under
one Enable Host, including first consumer frames. Active input and DEBUG counters
were0/0/0; ordinary AudioRecord naturally selected RemoteSubmix, physical997Hz
was measured and physical input/cleanup restored. UID RULES remained NONE.
Intentional720 replacement-silence frames per generation and DEBUG teardown
underruns15/15/11 were retained; SDK xrun0 is not a claim of zero missing source.

That APK included optional observations omitted here. It is historical evidence,
not a binary-equivalence assertion or a fresh physical test of this projection.
New-path VOICE_COMMUNICATION/CAMCORDER OPEN/REOPEN acceptance, human listening,
release-default availability and broad Android qualification remain PENDING.
The earlier preset successes are not revoked or transferred to this new cohort.

A41/API31 and S7/API26 DirectShell global injection remain permission-blocked
at MODIFY_AUDIO_ROUTING in preserved physical evidence. The API26 adapter exists;
ordinary physical capture is distinct from global injection and a Complete Host.
No Root/Shizuku rescue, model allowlist, UID rule or forced preferred input is
introduced. Normal Complete Host remains Video + SystemAudio + Input; this
microphone destination remains optional and DEBUG-provider-scoped.

Software gates and exact source/test manifest are recorded in the integrated
consolidation report. Tests exercise legacy/modern rule shapes and denial-safe
identity, OPEN/REOPEN/window/generation isolation, no-consumer/stale/partial
delivery, bounded missing-ingress replacement, SDK progression and rollback,
native admission/priming/handoff, callback safety and handle ownership/close.
An APK build is not device execution. No device installation is authorized here.

Pre-commit isolated gates: ktlint PASS, full JVM1152/1152 in177 suites with zero
failures/errors/skips; CTest Debug24/24 (3.97s) and Release24/24 (4.13s).
Direct queue/SDK-clock replay88 checks and scoped-priority replay11 checks PASS.
The five omitted JVM cases versus dirty1157 concern only excluded observation
hooks (path trace, slow snapshot/sink accounting and suspension diagnostics).
Three excluded native targets are protection timing, causal flight recording
and the host-only Phase7 simulation. No selected functional test is removed.
Android lint, DEBUG APK, AndroidTest APK and helper DEX PASS (83 tasks,6m55s),
including the three configured native ABIs. No instrumentation is run on devices.

Minimum later physical reconciliation: one frozen integrated APK on S9/API29
and Tablet/API33, strict ordinary MIC OPEN/REOPEN from first frames and original
three-Session criterion, with separate original/missing/replaced accounting,
routing restoration and helper-loss cleanup; bounded SystemAudio/Video/Input
regressions. Additional presets need their own current-path transition evidence.
Do not substitute a permission-blocked Host for Tablet acceptance.

Independent SystemAudio wakeup/kernel-RX/dispatch debts, Video HOME/REOPEN and
EADDRINUSE, and same-quality S9 microphone timestamp rollback remain OPEN.
No recovery/remediation/migration implementation is added.
