# Video HOME/REOPEN Deterministic Replay

Successor: [bounded first-full observation](VideoReassemblyFirstFullDiagnostic.md).
The tests/docs below were subsequently checkpointed locally at
`f0c6d87bbc00d7eb7ffd73bfa960f7d4092070eb`; earlier no-commit/no-instrumentation
statements retain their historical task scope. No hardware root cause is promoted.

2026-10-25. Isolated investigation above frozen G1-G5
`656c3783ba3f120f2e8b911b2ec3c932ca1b5df3`, branch
`codex/phase6-5-video-home-reopen`. No production correction, device installation,
media campaign, provider change or source integration.

## Decision

**DETERMINISTIC_SIGNATURE_REPRODUCED / CAUSAL_ATTRIBUTION_INSUFFICIENT.**

The production native receiver reproduces slots8/ready0/complete7/incomplete1,
then ReassemblyWindowFull before expiry, with genuine protected datagrams and
unchanged anti-replay. The Session controller separately reproduces logical
Error after successful Surface recreation and permits progress after an explicit
discontinuity/keyframe. This establishes a sufficient mechanism, not the origin
of the incomplete group in r1460 or the frozen hardware run.

No first **incorrect** production transition has been linked to those hardware
episodes. RFC-002F explicitly reports capacity exhaustion; ordered publication
must not bypass an observed older incomplete group. RFC-002G expires incomplete
groups at the existing first-observed-fragment deadline. The reproducer does
not justify ignoring full-window errors, relaxing replay protection, or purging
all partial data at Surface recreation. No production fix or commit is made.
Video HOME/REOPEN attribution remains **PARTIAL**, not FIXED or HARDWARE_VALIDATED.

## Baseline And Evidence Provenance

Read-only inputs outside this source projection:

- `C:/Users/Francy/.codex/worktrees/phase6-5-hardware-regression/Warpnect/docs/phase6_5/FrozenG1G5HardwareRegression.md`
  and `HardwareRestorationAndFailureReconciliation.md`: frozen APK0297F044,
  actual failure chronology, restored-device receipts and historical matrix.
- `C:/wg-hardware-r1592/harness/video-input-off-02/`: S9/A41 logs, snapshots,
  screenshots and `first-capacity-failure.txt`; corresponding harness and run log
  remain in their original evidence directory.
- Research reports under `C:/Users/Francy/Desktop/Warpnect/docs/phase6_5/`:
  `SystemAudioOutputTimingContract.md` (r1460),
  `VideoReceiverBoundedReassemblyRecovery.md`, `VideoDecoderLifecycleRecovery.md`,
  `VideoSystemAudioRegressionRecovery.md` and later causal/scheduling reports.

These uncommitted reports are read in place, not imported into the frozen
source, overwritten, or treated as permission for a new hardware campaign.
The accepted contracts remain [RFC-002F](../rfc/RFC-002F-End-to-End-Video-Streaming.md)
and [RFC-002G](../rfc/RFC-002G-Video-Latency-Recovery-Performance-Tuning.md).
[G1-G4](DirectShellFunctionalConsolidation.md) and
[G5](DirectShellDiagnosticConsolidation.md) retain their source/gate scope.

Original research HEAD remains3253518, master032b592, upstream9d0b4dc.
Functional consolidation remains20928f4; frozen diagnostic consolidation remains
clean at656c378. The isolated investigation uses only that frozen source, not
the dirty research tree. No newer status/handoff file supersedes these receipts.

## Original Signature

S9 log MONOTONIC timestamps, one device/clock; millisecond log precision. Snapshot
fields based on elapsedRealtime/BOOTTIME and A41 clocks are not subtracted here.

| Client time(s) | First trustworthy observation |
| --- | --- |
|535491.699|Streaming, rendered1893, ready/slots0, full0, HWM1 |
|535496.467|Surface1 destroyed; receiver/Session retained |
|535496.675|WaitingForSurface, ready1/slots1, complete2182/delivered2179 |
|535498.873|Surface2 available |
|535498.917/.918|Decoder starts/prepares with unchanged codec generation1 |
|535498.946/.957|New codec input/output after reopen |
|535499.010|Actual rendered-frame callback on Surface2 |
|535500.359..360|Protected ReplayDuplicate, not failed authentication |
|535500.618|First logged full, sequence2651, ready0/complete7/incomplete1, active generation1, awaiting keyframe1, FEC0 |
|535501.675|Core Error/ReceiverFailed, full36, HWM8, delivered2345/rendered2340 |
|535506.675|Still Error/full36, but delivered2418/rendered2413 |

No timeout or stale-release increment is sampled around that first full. The
incomplete group's identity, fragment bitmap and first-observed time are absent.
r1460 has the same ordered-window signature after successful Surface2 setup;
that is not proof that its missing fragment has the same cause. Presentation
logs are capped, so the last printed callback is not the last actual render.
Host snapshots show continuing encode/send progress, not per-fragment arrival.
Neither a sustained producer stop nor permanently unusable accepted bytes is
established. G3's early configuration resend succeeds and is not reopened here.

## Source And Ownership Path

1. `NativeSclVideoReceiverController` owns the receive worker; its JNI handle
   mutex serializes native calls. `VideoReceiverRuntime::accept_datagram`
   authenticates/anti-replay-checks before decoding the inner SCL packet.
2. `process_video_packet` observes loss/FEC, rejects known inner duplicates
   before slot allocation, then uses the existing fixed eight reassembly slots.
   Each slot owns bytes/bitmap; the ready queue owns indices, not a second AU copy.
3. `has_older_unqueued_slot` / `publish_ordered_ready_access_units` prevent later
   complete groups from passing an observed older incomplete group. With ready0,
   pump may continue receiving. A ninth new group cannot allocate: explicit
   ReassemblyWindowFull, emitted as TransportError by `pump`.
4. `pump` expires incomplete slots using local monotonic time and the existing
   50,000us first-observed-fragment age. This is not media PTS age. Expiry is
   polled, not a per-slot timer interrupt; record the last expiry scan when
   interpreting a future age/deadline snapshot. Configured ready data still has
   priority over further socket reads, preserving the earlier backpressure fix.
5. Kotlin dispatch is synchronous to the Session listener. `onRuntimeError`
   sets core Error/ReceiverFailed; access-unit notifications alone do not clear
   it. Same-generation config is ignored. An explicit later discontinuity can
   enter WaitingForKeyFrame and a keyframe can restore Streaming. Error is not
   proof that every decoder/native operation permanently stopped: the receive
   worker calls the error listener without exiting its `while (running)` loop.
6. HOME stops/releases the decoder, not the authenticated receiver. A coroutine
   Mutex serializes prepare/start/stop; Surface identity rejects stale callbacks.
   Reopen activates the same codec configuration and keyframe gate. Codec-owned
   input buffers drain ready slots without a Kotlin AU payload queue.

Source: `app/src/main/cpp/src/video_receiver_runtime.cpp`,
`app/src/main/java/io/warpnect/platform/video/transport/NativeSclVideoReceiverController.kt`,
`app/src/main/java/io/warpnect/video/session/VideoReceiverSessionController.kt`
and `VideoReceiverSessionCore.kt`. These production files are unchanged.

## Deterministic Native Reproducer

[Test source](../../native/tests/scl_video_home_reopen_tests.cpp), registered only
in the host native CMake test project as `scl_video_home_reopen_tests`.

Real components: VideoPacketizer, VideoReceiverRuntime, LossDetector,
SessionProtectionRuntime, cryptographic protection, endpoint binding and a
loopback UDP allocation-failure event. Authentication contexts are initialized
with fixed test-only trusted root/transcript inputs; this does not run a live
pairing/Session handshake. Fixtures use synthetic encoded-byte payloads, not an
Android codec or captured user content. Every supplied media fragment is sealed
and checked by the production protection implementation. Most deliveries use
the actual `accept_datagram` entry; the ninth AU uses UDP and the real `pump`.

The test executable alone supplies the existing monotonic clock symbol in place
of the static archive's platform-clock object. Production clocks/ABI are not
patched. All injected ages use one deterministic microsecond clock; no sleeps,
device-clock subtraction or scheduler timing assertions. The fixture uses256B
wire budget,8192B maximum logical payload, eight slots/eight ready indices,
128 loss-history entries, FEC OFF and the existing50ms recovery deadline. This
tests ownership with small synthetic AUs, not real encoder-size/load equivalence.
The UDP capacity-event pump allows one100ms OS-readiness wait to avoid assuming
synchronous loopback delivery. That bound does not advance the injected clock,
change the50ms recovery budget, or retry a failed experimental Session.

After draining a warm keyframe, decoder fills pause and keyframe gating begins.
The first of two fragments of AU2 is received; seven later AUs3..9 complete in
a deliberately compressed burst. Reopen reactivates codec generation1. The
incomplete AU blocks even an already complete later keyframe. The fixture
asserts slots8/ready0, seven later completed groups and warm frame already drained.

| Case | Observed production behavior / assertion |
| --- | --- |
|Before expiry|Ninth new group produces TransportError/ReassemblyWindowFull, timeout0. A duplicate first fragment and exact replay of the ninth protected packet are correctly rejected without new ownership or refreshed authenticated liveness. Genuine unseen tail at49,999us publishes eight AUs; keyframe gating drops old nonkeys and delivers AU9; fresh keyframe11 then progresses, slots0. |
|Ordered publication|Separate fixture completes the tail at49,999us and lifts the gate explicitly: fill returns2,3,4,5,6,7,8,9 exactly once, then NoData/slots0. |
|Tail never arrives|Pump at50,000us expires/releases the one incomplete slot, increments timeout/stale release and emits RecoveryDeadlineExceeded/Discontinuity; seven completed slots remain. Fresh keyframe11 triggers publication, keyframe-gated drain and slots0. |
|Tail after expiry|At50,001us original incomplete slot expires. Previously unseen tail authenticates and recreates an older partial group; already accepted head remains ReplayDuplicate. A second expiry releases it; fresh keyframe restores progress. Timeout1/2 distinguishes this case from the sampled hardware timeout0 signature. |
|Duplicates / Session change|Replay of a consumed AU creates no ghost slot. Close releases old ownership. A new authenticated Session generation2 rejects old generation1 bytes as UnknownContext even when presented from the currently expected endpoint; codec generation1 remains valid within the new Session. Fresh protected keyframe progresses. |

Bounds are asserted on used/ready slots and HWM, all at most8. Security acceptance
precedes reassembly admission: a successfully authenticated datagram that later
finds no capacity is still consumed by anti-replay. Exact byte retransmission
must not roll that security state back. ReplayDuplicate near a full event does
not identify which unseen fragment is missing.

Expiry alone does not republish retained completed slots in this baseline; a
subsequent completed message does. The late-tail recreation and this publication
edge are characterized, **not approved as optimal recovery** or claimed as the
hardware cause. No permanent deadlock is demonstrated by these bounded cases.
A separate contract review would be required before changing either behavior.

## JVM Lifecycle Coverage And Limits

[VideoReceiverLifecycleTest](../../app/src/test/java/io/warpnect/video/session/VideoReceiverLifecycleTest.kt)
adds four cases to the existing five:

- Surface2 reaches Streaming before a capacity error; same config and AU events
  do not hide Error; explicit discontinuity/keyframe permits progress.
- Full-window Error while Surface is absent does not silently restart on reopen;
  explicit stop reaches Idle.
- A delayed Surface1 destruction cannot steal Surface2's decoder during recovery.
- Disconnect while reopen preparation is suspended prevents that obsolete
  preparation from starting after a capacity error.

These execute the real Session controller/core, coroutine serialization and
generation/Surface guards, with fake runtime/codec/Surface dependencies. Native
typed events and JVM error mapping are checked separately. There is no combined
JNI/MediaCodec/physical Surface test and no claim of visible-video validation.
The old lifecycle and native configured-ready/duplicate/G3 tests are retained.

## What Is Established And What Is Not

**Established:** a genuinely missing fragment plus a bounded burst before the
existing deadline is sufficient for the exact occupancy/full signature. No stale
expiry, duplicate ghost, broken generation or failed Surface preparation is
required. Completion before deadline drains correctly; expiry releases the
original incomplete slot, and a fresh keyframe permits bounded progress.

**Not established:** which AU/fragment first went missing or was recreated on
hardware; its age at the first full; whether the hardware hole was genuine,
too-old input, a reset/ordering interaction, or another defect. Seven synthetic
arrivals separated by1us deliberately model a burst, not observed runtime cadence
or proof of Wi-Fi loss. This does not reproduce the full causal hardware history.
There is no failing-before/passing-after product patch because no such defect has
been attributed. Full-window Error remains a real acceptance failure, not PASS.

The configured ready8 read-bypass defect is different from ready0 here. The
known inner-duplicate early return is still present. G3 config resend, historical
AlreadyPrepared fixes and independent EADDRINUSE/audio/timestamp debts are not
reclassified or silently closed.

## Required Next Discriminator (Proposal Only)

Prepare one explicitly opt-in bounded DEBUG snapshot at the **first**
ReassemblyWindowFull in a Session, before changing recovery:

- Current monotonic time, last expiry-scan time, effective deadline, authenticated
  Session identity/generation (non-secret identifier), active codec generation,
  Surface generation/lifecycle state and pending runtime event.
- For all at most8 slots: fragment-group identity/order key, first-seen age,
  first/last fragment sequence, expected/received fragment count, complete/queued
  flags, codec generation/frame ID/keyframe where already parsed, last release
  reason and existing occupancy/counter values.
- Fragment bitmap with a fixed256B-per-slot cap and explicit truncation/counts;
  never interpret a truncated bitmap as complete. A fixed64-record recent ingress
  metadata history should distinguish authenticated new/duplicate/TooOld input,
  allocation, expiry and release, without payload or key material.

Budget the whole observer at no more than8KiB per receiver, one frozen snapshot
per Session, fixed-size records, no new protocol/JNI ABI, no allocation or text
logging in the receive critical path, and deferred export. Determine any fields
already available before adding them. Benchmark capture cost and overflow/truncation
before a separately authorized device run. This is a design, not implemented
instrumentation or authorization for another installation/campaign.

Required discrimination: partial age below50ms with genuine missing bitmap vs
an age beyond a completed expiry scan; unseen late tail vs known duplicate;
current-generation group vs stale owner; explicit release vs retained completed
slots; native pressure transition vs unrelated Surface callback. Matching sender
fragment provenance is needed before attributing a genuine hole to delivery.
Only then add a failing deterministic case and a contract-grounded minimal patch.

Future hardware acceptance, after such a patch: same negotiated-channel preflight,
fresh startup then repeated HOME/REOPEN with new visible scenes, real touch/key
input, bounded occupancy/recovery, no unexplained receiver Error, correct generation
and Disconnect cleanup. Existing G3/security/lifecycle and relevant audio
regressions remain required. No hardware-validation token is issued now.

## DEBUG Microphone Selection Confound

`MainActivity.kt` initializes `debugMicrophoneRequested` with Compose
`remember { mutableStateOf(false) }`. The composition's DEBUG request is a
process-owned companion `AtomicBoolean`; only explicit setter callbacks update
it. A recreated false checkbox therefore need not clear an earlier true request.
The setup builder reads that process value, requesting MicrophoneAudio and not
SystemAudio when true. The old Video harness clicked only an already-checked
checkbox. Actual frozen `audio_runtime` records report MicrophoneAudio/Running.

This is a source-backed DEBUG state-ownership/harness mismatch, not proof that
microphone load caused Video failure. The Video failure remains real under the
actual supported channel combination. It is not a SystemAudio-only control.
For a future harness, explicitly exercise the setter transition to the intended
state (true then false for OFF if necessary), verify the actual protected setup's
negotiated channel/source on both peers, and stop before measurement on mismatch.
Do not infer configuration from checkbox appearance. No UI/MicrophoneAudio
behavior is changed in this task.

## Automated Verification

Raw receipts remain outside Git at `C:/wv-home-r1594`.

- Focused JVM lifecycle:9/9 PASS; full final JVM1159/1159 across178 suites,
  failures0/errors0/skipped0, including the four new tests.
- Final native CTest:27/27 Debug (7.09s),27/27 Release (3.70s), including five
  protected replay cases, existing receiver/transport/security and G3 resync
  tests. Test targets are counted, not individual assertions.
- ktlintCheck, lintDebug, assembleDebug, assembleDebugAndroidTest and
  packageDirectShellProbeDex PASS. Integrated Android run13m38s,100 tasks:
  68 executed/32 up-to-date. lintDebug has0 errors/134 warnings, the existing
  G5 count/disposition, with no suppression or security-debt promotion.
- clang-format dry-run with Werror and git diff --check PASS. Native builds
  retain the existing warning flags. Android emits existing toolchain/third-party
  warnings (SDK XML version, Opus debug optimization and Oboe unused parameters);
  passing gates do not mean warning-free or Release/device qualification.
- First native harness compile failure used a nonexistent socket-adoption method;
  corrected to the real `adopt_prebound_socket`. Failure and corrected build logs
  are preserved. Initial ktlint failure on four new long constructor lines is
  likewise retained; no production workaround or suppression.

Only test sources/native test registration and this report/index pointers are
candidate changes. No production source, native ABI, window, FEC, codec, audio
parameter, provider, Input, Session/security or Phase7 change.

Exact change manifest:

| File | Scope |
| --- | --- |
|`native/tests/scl_video_home_reopen_tests.cpp`|New protected native characterization executable with test-local clock |
|`native/CMakeLists.txt`|One executable/link declaration and CTest registration, no production build delta |
|`app/src/test/java/io/warpnect/video/session/VideoReceiverLifecycleTest.kt`|Four tests, typed keyframe helper and fake decoder counters; existing five tests retained |
|`docs/phase6_5/VideoHomeReopenDeterministicReplay.md`|This receipt and proposed missing discriminator |
|`docs/phase6_5/README.md`, `docs/phase6_5/CurrentNonRootDebt.md`, `docs/FutureRoadmap.md`|Short scoped pointers; historical text preserved |

Reproduction (VS2022/x64 host native tests, JDK21/Android SDK configured):

```powershell
cmake -S native -B <external-build-dir> -G "Visual Studio 17 2022" -A x64
cmake --build <external-build-dir> --config Debug --parallel 2
ctest --test-dir <external-build-dir> -C Debug --output-on-failure
cmake --build <external-build-dir> --config Release --parallel 2
ctest --test-dir <external-build-dir> -C Release --output-on-failure
.\gradlew.bat :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex --max-workers=2 --console=plain
```

Android configuration remains minSdk26, NDK27.0.12077973, arm64-v8a/armeabi-v7a/
x86_64. An APK/test APK build is not device validation. The new native clock
override is linked only into the host test executable, never the Android library.
There is no production delta requiring another Android Release/audio campaign;
those historical gates retain only their previous scope.

Fresh build identities, **not installed**:

| Artifact | SHA256 |
| --- | --- |
|Debug main APK|009C8180E1E02E1095206906C34D2953CF06BE885D93966FD7C17FD9BDC0D0F7 |
|Debug AndroidTest APK|0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F |
|DirectShell helper JAR|002DD42AF5954BDF71300A33CA25DA1631848CB48CDBBB247D9433913D23D3AC |

The rebuilt main APK hash is not the historical frozen hardware APK0297F044;
no hardware evidence is transferred to it. Production file diff against656c378
is empty, as are third-party/RFC changes. Build outputs are ignored artifacts,
not commit contents. Counts/logs/hashes are retained in
`C:/wv-home-r1594/jvm-final-counts.json`, `ctest-socket-bound-*.log`,
`native-characterization-socket-bound.log`, `android-gates-02.log` and
`build-artifacts-final.json`.

## Device And Worktree Preservation

Read-only recheck2026-10-25T21:14Z finds A41 SM-A415F/API31 and S9 SM-G960F/API29
over USB. Both still have the verified original installed identities:

| Package, both devices | SHA256 |
| --- | --- |
|io.warpnect|30C3A3C589285B8A911EB7E1A5A49DC13EBE039FAD6134430D432BDF020A78A3 |
|io.warpnect.test|C2E823AFDC74CCD5B1042960598BE289F81B56FBC7E7D50D219A7BB0677F82BF |

No owned helper process matched the read-only process check. No install, reboot,
Activity launch, data write/clear, trust/network/developer-setting change, tone,
volume write or instrumentation execution was performed. Prior detailed
restoration/signing/Ready/Host-disabled receipts remain at
`C:/wg-restoration-r1593`; this task does not claim a new UI-state or pairing test.
Current hash/process receipt: `C:/wv-home-r1594/restored-readonly.json`.

Preservation verifier:492 original file hashes, four intentionally metadata-only
private entries,496 paths, original index SHA and clean G1-G4 identity all match.
All six existing hardware-branch report hashes/sizes match the initial snapshot;
the frozen G1-G5 branch is clean at656c378. Phase7 R2 SHA remains
`0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6`.
No blanket staging, reset, stash, clean, merge, commit or push.

## Completion Scope

- Reproducer/lifecycle coverage: **IMPLEMENTED**, software **TESTED** as recorded.
- Historical failure mechanism attribution: **PARTIAL**.
- Production correction: **NOT IMPLEMENTED**, not justified by this evidence.
- New hardware validation/qualification: **NOT TESTED**.
- G1-G5 hardware acceptance and Phase6.5 closure: remain **PARTIAL**.

One next action: implement/test the bounded first-full slot-identity observation
above, then seek authorization for its single targeted hardware discriminator.
Do not begin another media/provider/Phase7 task or speculative recovery change.
