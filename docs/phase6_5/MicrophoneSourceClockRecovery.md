# Microphone source-clock recovery continuation

> Current status is superseded by [final exact-path quality closure](DirectShellGlobalMicrophoneFinalClosure.md): primary3/3 PASS with the existing bounded pacer retained. Failed source-clock campaigns below remain historical; no universal source-stall or network closure is inferred.

Superseded current status: [receiver scheduling continuation](MicrophoneReceiverSchedulingQuality.md)
preserves every campaign here. Microphone-only worker priority has promising
diagnostic evidence, but final43FB71D6 acceptance attempts lose peer/Tablet
prerequisite before quality closure.1016 JVM and22/22 native each pass; quality
PARTIAL, no new token/commit or recovery implementation.

Date: 2026-10-03. Continuation of `MicrophoneConsumerClockContinuity.md`.

## Reconciled baseline

Branch `codex/directshell-production-runtime-integration`, starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`, master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`, upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`; staging empty. The inherited large
uncommitted source/test/doc/diagnostic worktree and generated evidence are
preserved. No checkpoint at task start, reset, clean, stash, merge, rebase or push.

Tablet Wireless Debugging was restored manually by the user. Tablet
`Z1382023HGM1895372`/API33 at 192.168.1.57 and S9 `23eaec0f12047ece`/API29 at
192.168.1.4 again pass 3/3 direct IPv4 pings in each direction. No new Wi-Fi,
router, security or application network workaround was applied.

The global-injection functional token remains valid. Quality is still PARTIAL
until a fresh final campaign succeeds. Earlier 66A8B330 campaigns, intermediate
failures, permission/priority probes and radio prerequisite loss remain in
`MicrophoneConsumerClockContinuity.md`; this continuation does not erase them.

## First additional causal boundary

Preserved S9 sender logs for the third 66A8B330 campaign show nominal source phase
moving permanently, on the S9's own clock:

| Sender window | Encoder-input phase | Protected-send phase | Sequence |
| --- | --- | --- | --- |
| 0-2 | approximately 0-5 ms | approximately 0-5 ms | continuous |
| 4 | 10.035-46.962 ms | 10.088-47.019 ms | continuous, 994 units |
| 5-10 | approximately 44-47 ms persists | approximately 44-47 ms persists | continuous |
| 11 | max 79.937 ms | max 80.271 ms | continuous, 994 units |

The corresponding Tablet kernel-socket enqueue timestamps show phase moving
from about -3.5..40 ms to about 39.5..75 ms, then max96.011 ms. This correlates
unit identity and *local phase changes*, not timestamps subtracted across devices.
No missing/reordered/duplicate units in those steady windows. Authentication and
receiver listener costs do not explain a permanent shift already present before
send. These kernel timestamps are not RF departure/arrival timestamps.

The old `MicrophoneChunkPacer.deadlineNs` rebased its next deadline to `now` whenever
lateness exceeded one packet period. It never repaid scheduling debt. Several
short delays therefore consumed tens of milliseconds of the already established
playout reserve, even after the interval histogram returned to approximately
5 ms. DEBUG occupancy fell from about3300 to1200-1800 frames, ordinary underflow
continued, and the independent RemoteSubmix AudioTrack also began underrunning.
AAudio xrun0 is compatible with an on-time callback that has insufficient PCM.

This is a PREEXISTING_PRODUCT_DEFECT in source pacing, not proof that all earlier
Wi-Fi jitter was caused by Warpnect. Genuine network jitter remains supported by
the existing 80/70 ms playout contract. Large cold-start socket queues and initial
sequence gaps remain separately visible in the diagnostics.

## Discriminating controls and correction

`global-clock-oct03-r1` on unchanged66A8B330: one 77.628s Session with an85s ordinary
MIC consumer. Source phase stayed about5-11ms instead of permanently accumulating.
IPC admission rejection0, eligible age max38.788385ms, no-consumer483600 frames,
stale12232, pre-window240, enqueued4167840/written3671768. DEBUG18000 rejected,
123 underruns, xrun1 and RemoteSubmix57 underruns occurred in the cold early
window, then counters were flat. This is a diagnostic control, not acceptance.

A deterministic repeated15ms scheduling-debt test fails with the old pacer
(4 cases,1 failure). It passes with the correction. The first candidate test
run had one obsolete assertion expecting a deadline in the past (6ms at
now6.2ms); that TEST_HARNESS_DEFECT is preserved. Corrected suite6/6 passes.

The new pacer preserves the nominal source timeline for recoverable delay, with
actual deliveries at least half a packet period apart: 2.5ms for240frames/48kHz.
It records the actual delivery time, including an overslept wait. When lateness
exceeds the *existing* AudioRecord capacity, it starts a fresh timeline instead
of trying to replay indefinitely unavailable backlog. S9's actual capacity is
2880frames/60ms; this is not an added buffer or device-model condition.

Four-chunk HAL batch smoothing still spaces ordinary deliveries at5ms. Short
scheduling debt is recovered gradually, not by a tight immediate batch. No
Opus, packet duration, PCM ring, socket buffer, Session or protection change.
There is no extra stored PCM, network queue or fixed latency reserve in this
correction. Existing source smoothing can still redistribute a four-chunk HAL
batch over up to15ms. Long capture outages beyond recoverable backlog are not
claimed universally lossless.

First physical corrected candidate:
APK `E44389F7EC69972604AD772169B58214CE39A370CDEC1AA0DB24712C625123EE`.
One diagnostic Session: Streaming07:52:49.265409UTC,
Disconnect requested07:54:06.869467UTC (77.604s), Ready/Waiting07:54:17.236047UTC.
Source send phase returned to near zero in every observed5s window, max6.836060ms
over12 windows; max send gap12.748308ms, continuous units, duplicates/reordering0.
No permanent44/80ms source shift. DEBUG15840 rejected/91 underruns were flat
through the continuous steady interval (119 total includes subsequent teardown).
RemoteSubmix17 underruns appeared within the first3.2s after consumer open and
then stayed flat. IPC enqueued4290480, written3727016, absent554400, stale9064,
pre-window0, rejected0, HWM14, eligible age max38.808923ms. Consumer type25/2170
RMS max767.056, 997Hz component1083.231; after Disconnect type15/11. No new human
audibility claim. Cold startup/activation artifacts remain explicit, not zeroed.

## Bounded observation changes

The next APK adds only bounded DEBUG dequeue-age histogram observation (one
sample per dequeued original IPC chunk, local monotonic residence), not another
audio policy change. Bin upper boundaries are1,3,5,10,20,30,40,80ms; the final
overflow bin is unbounded diagnostically. Actual PCM freshness is still hard
limited to40ms at write eligibility. Histogram includes discarded dequeued
chunks, and is not capture-to-consumer latency or a retry count.

The first full gate attempt stopped on one DEBUG log line exceeding120 columns;
the formatting correction does not alter dataflow. Full gates and fresh
consumer absent/open/close/reopen 3-Session acceptance will be reported below.

The current DEBUG-only cadence/timing probes now cover at most24 local5s windows,
instead of12, because the next failure first appears after60s. They retain the
existing process-wide log budget; release factories still return no probe.
Tests verify both timing and kernel-cadence observation stop at the same bound.
No native receiver, JNI, scheduling priority or transport behavior is changed.

## Counted final candidate: FAIL, counting stopped

Frozen APK:
`D28F613685269B397A3955FA1AF2F21A9F56033EFACC9F15B2DBF06AB057838B`.
Identical installed APK hashes were verified on S9 and Tablet. Normal Enable
Host at10:16:32.621738UTC, plan SINGLE/DIRECT_SHELL. One counted Session only:

| Cycle | Generation | Streaming UTC | Disconnect requested UTC | Duration | Result |
| --- | --- | --- | --- | --- | --- |
| 1 | 6040254014668604111 | 10:17:04.445537 | 10:18:53.388677 | 108.943s | FAIL: recurring steady quality loss; teardown replacement also fails |
| 2 | not attempted | - | - | - | NOT RUN after cycle1 failure |
| 3 | not attempted | - | - | - | NOT RUN after cycle1 failure |

Ordinary MIC opened10:17:12.622128, closed10:18:28.305112 and reopened
10:18:34.487805. The consumer has a separate unprivileged test-APK UID,
sourceMIC, no preferred device, no Warpnect ring/transport callback and no
UID policy criterion. RemoteSubmix type25 was selected naturally; after
policy release ordinary input returned to built-in type15/id11.

| Measured steady interval | DEBUG rejected delta | DEBUG underrun delta | xrun delta | RemoteSubmix track underrun delta | IPC rejected delta | IPC stale delta |
| --- | --- | --- | --- | --- | --- | --- |
| First continuous reader,60s sampled | 9360 | 69 | 0 | 37 | 720 | 34712 |
| Reopened reader,10s sampled | 35760 | 502 | 0 | 241 | 11040 | 73440 |

These intervals exclude the first5s after each consumer opens. They are sampled
counter windows, not a guarantee that every transition event is classified.
Recurring shared DEBUG starvation and independent AudioTrack starvation prevent
quality PASS. Counters were not suppressed, redefined or reset to hide this.

IPC release: enqueued5297760frames, written4105232, dropped-no-consumer995760,
stale196048 (admission-expired70560 is a subset), pre-consumer-window720;
unexpected admission rejection14400 is outside the accepted/enqueued count.
Accepted-frame balance is0. HWM16chunks, sampled first steady occupancy0..5;
eligible-write age max38.869231ms, dequeue age max64.097846ms. Coarse histogram
bins contain7375/904/412/1107/7867/3134/770/211/0 original chunks; p50<20ms,
p95<30ms,p99<40ms. This distribution includes subsequently discarded chunks,
not AudioTrack completion or acoustic end-to-end latency.

Ordinary consumer RMS reached751.265943,997Hz component1061.936909 in the first
reader; reopened reader735.995957/739.925129. OFF/ON/OFF remains numerically
visible. The probe measures the known997Hz component, not a general dominant-
frequency search. It proves live remote acoustic signal, not lossless quality
or human audibility on this APK.

The corrected S9 source phase remains bounded in the observed first12windows,
usually0..5ms, isolated max11.615ms; max send gap17.317193ms and no observed
missing/duplicate/reordered units. This refutes source-clock drift as a complete
explanation of the residual pressure. The worst new pressure begins beyond the
old60s probe limit. Some authenticated IPC calls take27/42ms and one79.671ms,
while sampled helper feed execution is at most about12ms. The remaining request-
dispatch/scheduling/network/decoder cause is not conclusively localized.
Concurrent GC elapsed time is not a stop-the-world pause; sampled pauses mostly
remain below2ms. Thread runqueue statistics are cumulative, not per-call delay.
No speculative priority/socket/ring change is made from these observations.

After Disconnect a fresh PCM helper owner attaches, but Input bootstrap's HMAC
stage times out after6007ms. At Tablet local440273.726, Host publication is
withdrawn with DIRECTSHELL_OWNER_LOST and migration=false. Host does not remain
falsely Available; however, normal Waiting/rediscovery acceptance fails. The
first trustworthy teardown boundary is Input authenticated bootstrap, not the
microphone policy. Its exact initiating cause is INCONCLUSIVE; no larger timeout
or hidden bootstrap retry is introduced.

## Diagnostic comparison, not replacement acceptance

APK with extended bounded observation:
`388C3A75FEFC1AED6412347A364F89488420E4A662A6CFA483BA3D3A75CE07E8`.
The only additional APK behavior is DEBUG probe coverage; pacing/IPC/policy
semantics remain unchanged. A separate117.758s diagnostic Session starts
10:29:34.316307UTC, Disconnect10:31:32.074763, Ready/Waiting10:31:42.481682;
generation5030375526639534349. Requested consumer125s is truthfully clamped by
the ordinary fixture to90s; actual measured capture90.037s,89 remote samples.

An80s sampled continuous steady window has DEBUG rejected/underrun/xrun delta
0/0/0, RemoteSubmix track underrun delta0, IPC rejection/stale/window deltas0.
Sampled queue0..3. Release enqueued5749920/written4305736/no-consumer1432320/
stale11624(admission2640 subset)/pre-window240/rejected0/HWM13. Eligible age
max38.615923ms/dequeue44.386770ms; histogram14946/3553/1918/3329/81/41/74/5/0,
p50<1ms,p95/p99<10ms. Track total32 underruns are confined to activation here,
not a universally accepted startup allowance. RMS718.084759, tone1000.427012,
natural input25 followed by physical15/11. Later kernel-cadence windows12..23
have sequence-continuous max gaps26..38.799ms without permanent source drift.

This useful nearest PASS control is NOT cycle2 of the failed campaign. It does
not include the required close/reopen gate and does not earn3/3 quality closure.

## Next consumer-window diagnostic and external prerequisite loss

Under the same continuously enabled diagnostic Host, a further attempt starts
10:36:30.148142UTC, reaches UI Streaming10:36:47.137416 and requests ordinary
consumer10:36:55.272669. Generation1033858071131274300/policyap86. The harness
eventually fails Streaming lost; there is no acceptance retry or substitute pair.

At Tablet local441337.906 Session lifecycle suspends, after only685decoded
units/164640frames. Source telemetry on S9 reports lifecycle suspension and
InvalidPresence on its own clock. Tablet input later reports ReceiverFailure.
At local441343.492 wpa_supplicant logs reason5 locally-generated disconnection
from BSSID6c:99:61:16:23:65; Bluetooth service death is logged at441343.508,
and441343.786 AdbDebuggingManager explicitly disables adbwifi because the
network disconnected. Typed WirelessDebuggingDisabledException then fails
helper preparation and Host publication is withdrawn.

The earlier media suspension is NOT explained merely by citing the later Wi-Fi
disconnect. TDLS peer setup, extensive audio-device callbacks/background work
and radio service loss are observations, not proven initiating causes. No
Warpnect TDLS operation exists in the searched source. Initial peer-network
loss is externally measurable: Tablet has no IPv4 route and ping says Network
is unreachable; S9-to-Tablet also fails. Classify the proven lost network/ADB
prerequisite ENVIRONMENTAL_PRECONDITION and the initiating early stall
INCONCLUSIVE. Do not count starvation after packet supply ceases as evidence of
a new pacing cause or quality PASS.

Tablet later re-associates automatically, same SSID/BSSID and192.168.1.57/24.
Direct IPv4 ping3/3 succeeds each way; harmless one-shot TCP marker delivery
succeeds in each direction on ports43491/43492. The first TCP probe expired
before its client was launched (10s tool-return interval): TEST_HARNESS_DEFECT,
then an explicit30s bounded probe verifies the link. No router/security change,
manual Wi-Fi toggle, product recovery or peer substitution. Wireless Debugging
remains0 after Android's automatic disablement; normal manual restoration is
requested, not forced with privileged settings.

## Current PCM, portability and recovery decisions

Retain one Session-owned global recorder/RemoteSubmix policy, capture-preset
matchingMIC/VOICE_COMMUNICATION/CAMCORDER, UID RULES=NONE. No-consumer real PCM
and pre-open-window PCM are discarded; maintenance silence keeps the submix
selectable. The consumer temporal guard and provisional fixed40ms authenticated
IPC freshness remain unchanged. There is no new adaptive policy: NOT NEEDED
is not established, and meaningful fixed/adaptive A/B remains quality-gated.
No additional capture/IPC/playout buffer or fixed delay is introduced by this
source-clock correction; it only repays recoverable scheduling debt. The
80ms/70ms DEBUG ring/reserve and potentially up-to15ms HAL batch smoothing
remain separate from IPC freshness. Existing AudioTrack4104frame/85.5ms queue
and AudioFlinger reported latency are additional observed platform buffering,
not an80/70ms reserve alias or proof of measured end-to-end latency.

A41's actual AudioService registration requires missing MODIFY_AUDIO_ROUTING;
S7's legacy shell context reaches the mechanism but hits the same permission.
Product API<31 adapter remains unimplemented. No new portability path is added
before quality closure. Tablet invalid-preset priority authorization evidence
remains PARTIAL: no valid forced preference or abnormal rollback is tested.
Natural selection remains the product experiment; no priority role is written.

Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
remain NOT IMPLEMENTED. Existing composite planning prepares a Complete Host
before a Session; this task neither changes a frozen plan nor implements future
post-failure recovery. Health invalidation/withdrawal is not recovery. Required
Host still Video+SystemAudio+Input; optional microphone failure does not make it
incomplete. No Root, flashing, framework/vendor/HAL/SELinux changes, hidden
Shizuku fallback, unauthenticated PCM or generic shell facility.

No new SystemAudio/Video/Input physical regression is claimed while the primary
prerequisite is lost. The preceding66A8B330 bounded SystemAudio result remains
technical/lifecycle PASS only (rejected20160,underrun217,xrun0), separate quality
debt. Neither its ring nor routing REMOTE_ONLY is changed. No release promotion.

## Tests, evidence and final classification

This continuation modifies the microphone capture controller and pacer, plus
bounded DEBUG queue-age/cadence observation. Focused pacer suite6cases includes
repeated recoverable clock debt, actual late delivery/minimum spacing, capture-
capacity-based reset, HAL batch smoothing, frame units and fresh Session state.
Two new probe-bound tests cover24window termination and timing/kernel agreement.
Native production source is not newly edited in this continuation; inherited
native changes remain preserved and tested. Generated harnesses/metrics/logs
under app/build/reports remain excluded from commit; no raw speech is recorded.

D28 candidate gates PASS:1010 JVM/161suites,0 failures/errors/skips;
native Debug22/22 and Release22/22. Latest probe-test continuation gates are
recorded after completion below. Initial DEBUG-line formatting and later new
test formatting gate failures remain in separate reports, corrected without
behavior changes. No checkpoint: global quality and final lifecycle do not
reach the required coherent validated boundary. Ending HEAD remains715b215.

Preserve DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED and the historical
MICROPHONE_AUDIO_CADENCE_AND_PLAYBACK_QUALITY_FIXED_AND_VALIDATED DEBUG baseline.
Current global quality: PARTIAL. Source-clock correction: IMPLEMENTED/TESTED,
physically improved, NOT sufficient to close the integration debt. Priority:
authorization-only PARTIAL, valid routing/cleanup NOT TESTED. No QUALIFIED or
PRODUCTION_READY claim.

Exactly one next task: continue the same S9-to-Tablet global microphone quality
diagnosis/three-cycle consumer-window acceptance after manual prerequisite
restoration. Do not begin SystemAudio redesign, portability promotion or forced
priority before this gate closes.
