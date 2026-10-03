# MicrophoneAudio cadence and bounded playout reserve

Latest [global injection final closure](DirectShellGlobalMicrophoneFinalClosure.md)
preserves this report's accepted DEBUG cadence token/80ms capacity/70ms reserve
and validates a later integration APK3/3 on the same primary pair. Separate
receiver-worker scheduling, one-burst atomic admission and maintenance-silence
supply defects are classified there; codec/packet/socket buffers unchanged.
Fixed40ms IPC age is NOT the playout reserve.1022 JVM/22+22 native gates pass;
no universal source/network/latency or forced-priority/release claim. Earlier
partial continuations below are preserved as exact historical evidence.

Latest [receiver scheduling continuation](MicrophoneReceiverSchedulingQuality.md)
adds a separate proven local drain-scheduling boundary after source pacing.
Microphone-only audio priority improves that diagnostic without changing this
historically validated80/70ms reserve. Final43FB71D6 quality acceptance is still
PARTIAL: first cycle loses peer/Tablet prerequisite;2/3 NOT RUN. No historical
cadence token revoked and no new global quality token earned.

Latest [source-clock recovery](MicrophoneSourceClockRecovery.md) preserves the
accepted historical DEBUG token and80/70ms buffer contract. A later integration
defect is proven: pacer rebasing after short scheduling delays permanently moves
the source timeline. The minimum correction repays recoverable debt using the
existing AudioRecord capacity and bounded delivery spacing, no new PCM buffer.
It improves local phase but does NOT close all global-input quality: one counted
new cycle fails steady pressure; an80s stable diagnostic is not3/3 acceptance.

Current scope note: [2026-10-03 integration follow-up](MicrophoneConsumerClockContinuity.md)
retains this report's accepted frozen-APK cadence token and80/70ms contract.
It corrects unnecessary healthy-output reconstruction and rearms that same
reserve only on explicit decoder freshness discontinuity. Ordinary starvation
remains visible: cycle3 of a later three-cycle global-input campaign has steady
DEBUG underrun delta13 and AudioTrack underflows. That candidate is quality
PARTIAL, not a retroactive revocation or extension of this historical milestone.
No new ring/codec/socket/pacer values or adaptive age algorithm.

Scope note: the later [global-input consumer-transition follow-up](MicrophoneConsumerTransitionQuality.md)
retains this report's validated80/70ms DEBUG playout contract and token. Its
provisional40ms authenticated IPC freshness ceiling is a separate mechanism;
two complete ordinary MIC Sessions and a third platform-interrupted attempt
do not earn global-input quality closure or revoke this historical milestone.

Date: 2026-10-01. Investigation of S9/API29 physical microphone -> protected
Session -> Tablet/API33 **DEBUG playback**, not production virtual input.
This report preserves earlier failures rather than replacing them with retries.
Final campaign and gates are recorded below only after completion.

Subsequent [global input integration](DirectShellGlobalMicrophoneInjection.md)
preserves this accepted cadence result and the80/70ms contract. New policy
creation/warm-start integration failures are separately recorded there, not
retrospectively converted into failures of this frozen APK. The integration
candidate moves policy/control work off audio ingress; no UDP/codec/ring change.

The later final CF5C311C injection APK proves global ordinary MIC/preset input,
not a fresh all-zero DEBUG playout campaign. Its first MIC cycle has startup
decoder resets/rejection and a late10-underrun/400-zero-fill-frame snapshot;
cycles2/3 have DEBUG rejection/underrun/xrun0. Input IPC also rejects frames at
startup and after the consumer stops. Exact generations/counters and the fixed
Session-owner reset regression are in the current report. This is separate
integration quality debt; the earlier frozen80/70ms cadence token below remains
accepted for its exact APK/path and is not generalized to the new destination.

## Repository boundary

Branch `codex/directshell-production-runtime-integration`; starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`. Earlier checkpoints:
`809b7ddd48e9db192a86de17b59abdfd607916fd` (mic technical E2E) and
`92347d0ba7c581457ae37aea4d83d6c57b37bc7b` (Session lifecycle).
Master `032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, ahead19/behind0.
Starting staging0, unstaged189, untracked67: 32 source/debug paths,9 test
paths,9 docs,159 generated native outputs,47 unrelated paths. Existing empty
`.git/index.lock`, screenshots/XML, attachments and native build output are
preserved. No current partial AudioPolicy work was checkpointed automatically.

## Exact hardware and format

| Role | Device / serial | Build fingerprint |
| --- | --- | --- |
| Physical Client mic | S9 SM-G960F, Android10/API29, `23eaec0f12047ece` | `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys` |
| Primary DEBUG receiver | Aocwei X700_EEA, Android13/API33, `Z1382023HGM1895372` | `Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys` |
| Available control/acoustic source | A41 SM-A415F, Android12/API31, `R58N52B32JJ` | `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys` |

S7 is connected but was not required; S22 is not a dependency. All ADB actions
use explicit serials. No Root, unlock, flash, wipe or system change.
Format remains48kHz mono PCM16,240 frames/240 samples/480 bytes per5ms unit,
Opus64kbit/s. Client AudioRecord built-in input uses the existing
VOICE_RECOGNITION fallback, with actual2880-frame/60ms capture buffer.
Codec, packet duration, bitrate, socket buffers and protected wire contracts
are unchanged by the reserve experiments.

## Original quantitative failure

The exact previous paced APK was
`E080A820000DCB33DFE8487B24FF349EC06D14808CF904C1B9A0873A98ABAECF`.
Its three normal Sessions under one Host enable had lifecycle3/3 PASS,
quality0/3 PASS. These failures remain authoritative historical evidence.

| Cycle | Sampled Streaming | Rejected total / steady delta | Underrun total / steady delta | Xrun | Kernel max gap | Socket max steady gap |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
|1|69.739s|52800 /35280|278 /204|0|43.808ms|14.596ms|
|2|69.891s|51120 /35760|282 /205|0|41.752ms|14.521ms|
|3|69.904s|44400 /33840|252 /195|0|46.173ms|14.398ms|

Before the pacer, encoder-input3-7ms intervals were370/12007 (3.08%);
afterwards98.6-98.9%. Blocking AudioRecord reads supplied roughly four5ms
chunks per20ms batch. The pacer redistributes already available chunks; it
does not guarantee regular network arrivals. Its additional redistribution
can reach approximately15ms. It is retained, not declared universally optimal.

## Observability and first trustworthy boundary

`AudioCadenceDebugProbe`, capture probes and native socket diagnostics correlate
frame position/unit sequence while using each endpoint's own local clock.
The new nominal-phase diagnostic subtracts local elapsed time from nominal
sample progression within one stream. It is **RELATIVE_ONLY**, not Client-to-
Host delay. No independent device clocks are subtracted.

The native receive probe obtains `SCM_TIMESTAMPNS` via `SO_TIMESTAMPNS` and
`recvmsg`: **software kernel ingress, CLOCK_REALTIME**, not hardware/RF arrival
or merely `recvfrom` return. Host read/authentication uses local MONOTONIC;
similar gaps there rule out a pure wall-clock-step explanation. See the
[Linux timestamping contract](https://cdn.kernel.org/doc/html/latest/networking/timestamping.html).
Native pre-authentication already sees burstiness, so JNI/Opus cannot be its
sole origin. The readable-socket loop does not intentionally wait20ms to drain.
Socket-send completion is not radio departure; firmware, RF, driver and exact
network delay remain unobserved. Do not claim a uniquely identified Wi-Fi bug.

The proven Warpnect failure boundary is the decoded-PCM ring admission and
consumption contract: a20ms ring with5ms initial reserve cannot absorb the
observed ordered arrivals' roughly49ms local phase range. During a gap it
zero-fills; when delayed packets arrive together it rejects valid buffers.
Xrun stays0 because the real-time callback still supplies its requested
samples on time, including those replacement zeros. Xrun is not PCM continuity.

Counter ownership is explicit: `PcmPlaybackRing::submit` in
`audio_playback_ring.cpp` increments `pcm_frames_rejected` by PCM frames for
invalid configuration/generation or unavailable capacity, including
`PlaybackRingFull`. In these quality failures bounded ring-full events
identify capacity rejection of valid decoded PCM, not a guessed counter
meaning. `PcmPlaybackRing::consume` counts one `underrun_callback` and the
missing PCM frames when a callback cannot obtain its full request; the
remainder is silence. `OboeAudioPlayback::DataCallback::onAudioReady` calls
consume after priming. The separate Oboe xrun counter concerns hardware
deadline/stream behavior, not those application replacement samples.

## Hypotheses and discriminating experiments

Each row is a fresh explicit Session, not a hidden retry. All completed normal
Disconnect -> Ready/Waiting; quality is evaluated separately.

| Experiment / hypothesis | Controlled change | Observation | Decision |
| --- | --- | --- | --- |
| Host radio sleep dominates | Baseline20s -> Tablet LOW_LATENCY20s -> release25s, same old ring | Actual low-latency active time +21.671s; rejected61920, steady45840; underrun328, steady244; xrun0. Kernel max52.763ms versus socket11.442ms. | Host lock alone rejected as solution; no product lock retained. |
| Client radio sleep dominates | Same ABA on S9 only | Actual active time +21.864s; rejected54960, steady36240; underrun279, steady192; xrun0. Kernel max56.177ms versus socket12.345ms. | Client lock alone rejected; does not rule out all radio effects. |
| Existing budget is too small | Diagnostics only, unchanged20ms ring | Kernel local phase -3.408..44.947ms, span48.355ms; pre-decoder -3.694..45.123ms, span48.817ms. Rejected45360, steady32400; underrun217, steady167. | Budget mismatch accepted; not an RF-cause claim. |
| A bounded reserve can absorb ordered bursts | Same pacer/codec/socket,60ms capacity and50ms threshold | Steady rejection/underrun0; total rejection2160 occurred at startup; HWM2848/2880. | Primary cause supported, startup separately unresolved. |
| Reserve makes source pacing unnecessary | Remove only current capture pacing | Startup sequence gap4 and pipeline reset; later steady rejected240 and2 underruns. Early output consumed512frames then made no observed consumption progress for about47ms. | Candidate quality FAIL; removal reverted. Exact cause of startup missing units remains INCONCLUSIVE. |
| Output clock must be warm before admitting PCM reserve | Start optional Mic output at configuration; callback holds PCM until threshold | One candidate ~70s: rejected0, steady underrun0; only3 startup callbacks/48 zero-fill frames, xrun0. | Promising secondary correction; requires final three-Session validation. |

Wi-Fi experiment activation was checked through system cumulative active time,
not just `WifiLock.isHeld`; supported conditions and device caveats are in
[Android WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager).
At this stage no paired simultaneous radio experiment had been performed;
the later combined experiment is recorded below. No universal radio innocence
claim follows from either negative single-endpoint experiment.

The phase-only run has one missing unit in the first bounded sequence window;
later windows are continuous. The unpaced candidate has four startup missing
units and counter resets. Both are preserved as **INCONCLUSIVE** first observed
native-delivery gaps; neither is rewritten as a PASS by a later run.

## Primary and secondary correction

`MicrophonePlayoutReserve` changes only the default Android MicrophoneAudio
receiver binding. The first candidate derived, at5ms, capacity12 units/
2880frames/60ms and initial reserve10/2400frames/50ms. The final measured
budget is16 units/3840frames/80ms and14/3360frames/70ms, as explained below.
SystemAudio config is returned unchanged. There is no extra packet queue,
network reordering algorithm, packet drop policy or unbounded jitter buffer.
The existing `PcmPlaybackRing` remains the only decoded-PCM reserve.

`AudioReceiverSessionConfig.playbackWarmStart` allows the optional microphone
receiver to start the Oboe clock immediately after preparing its stream, before
PCM. An explicit playback gate is never bypassed. The callback-thread
`AudioPlaybackPrimingGate` supplies intentional startup silence without
consuming PCM until the reserve is present. Those callbacks/frames have separate
`priming` counters. Once primed, the gate is sticky: a later empty ring remains
an actual counted underrun, never silently re-primed.

This fixes the second boundary: postponing cold Oboe startup until the reserve
was already full allowed incoming valid PCM to fill all slots while Android
started the output clock. Warm startup separates hardware readiness from PCM
readiness without increasing the buffer again. Exact OEM startup cause is not
asserted from an app snapshot; observed cold-start consumption plateau and
the isolated warm-start experiment support this correction.

Real-time callback work remains bounded atomic/ring operations, sample copy
or zero-fill; no locks, sleeps, I/O or logging. See the
[Oboe callback contract](https://github.com/google/oboe/blob/main/include/oboe/AudioStreamCallback.h).
DEBUG logging is bounded and observational/non-fatal. Local last-underrun
callback time and native submit/consume residence share CLOCK_MONOTONIC.
Appended JNI snapshot fields do not change a network wire format.

## Buffering and latency contract

| Item | Before | Final | Cost / limitation |
| --- | --- | --- | --- |
| Physical AudioRecord buffer |2880frames/60ms|unchanged|Capacity is not an absolute capture-latency measurement. |
| Source pacer |5ms deadlines|unchanged|Up to about15ms redistribution for four available chunks. |
| Decoded PCM capacity |960frames/20ms/1920B|3840frames/80ms/7680B|+60ms capacity, bounded, no second queue. |
| Initial PCM threshold |240frames/5ms|3360frames/70ms|+65ms nominal reserve relative to former threshold. |
| Codec / socket / hardware bursts |Existing values|unchanged|No bitrate, duration, socket-size or Oboe buffer inflation. |

Residence and occupancy are reported from the final campaign, not inferred
from capacity. This is a deliberately measured tradeoff for the tested path,
not a zero-latency claim or proof that70ms is optimal on every network. True
acoustic E2E latency remains unmeasured without a common/external clock.

## Final acceptance campaign

### Rejected 60ms/50ms candidate campaign

APK `5324683FC2DFA5C5EFDA5D6094917670005D8746EE979932D0F092F89FDC4C6C`
passed all Gradle gates,972 JVM/0/0/0, and was installed on both endpoints.
One normal Host enable at15:53:29.167Z, no hidden reconnect or toggles:

| Cycle | Connect / Streaming UTC | Sampled window | Rejected total / steady delta | Underrun total / steady delta | Result |
| --- | --- | ---: | ---: | ---: | --- |
|1|15:53:59.252 /15:54:17.960|69.748s|480 /480|5 /5|Quality FAIL; normal Ready/Waiting15:55:37.552Z. |
|2|15:55:37.553 /15:55:52.198|69.752s|5040 /4800|25 /22|Quality FAIL; normal Ready/Waiting15:57:12.401Z. |
|3|15:57:12.401 /15:57:26.757|Extended beyond intended65s|0 before teardown|5 before teardown|Quality FAIL; fixture could not find offscreen Disconnect. Normal scroll/tap then restored Ready/Waiting. |

Cycle1 first steady loss is precisely bracketed at local291328.422s: a59.862ms
kernel ingress gap /60.151ms pre-decoder gap, continuous sequence in that
window, then816 zero-fill frames and480 valid rejected frames. Local arrival
phase -3.746..59.415ms spans63.161ms, exceeding the candidate50ms reserve.
Classification: **PREEXISTING_PRODUCT_DEFECT**, inadequately bounded arrival-
tolerance contract, not a changed security/codec defect.

Cycle2 additionally has153.890ms software ingress gap /155.361ms pre-decoder
gap. Source socket max13.534ms, native receive sequence windows have6 missing
units total (including startup); the long-gap window itself remains continuous.
Exact RF/driver/kernel cause is **INCONCLUSIVE**. It is not evidence that a
154ms playback buffer would be an acceptable product correction.

Cycle3's control failure is **TEST_HARNESS_DEFECT**: Streaming remained active,
Disconnect existed below the viewport, one normal scroll exposed it. No app
behavior was changed for the fixture. Last pre-teardown sample at291686.234s
has rejection0, underrun5/592 zero-fill frames; first local video-stop signal
at291690.998s precedes the sample with68/16576. The extra63 callbacks/15984
frames are bracketed around teardown, not relabelled steady-state loss. No
sampled long-run clock-rate defect is proven from a stopping counter window.

### Combined radio-control experiment

Fresh Session, same APK/ring and continuously enabled Host: baseline20s ->
both endpoints LOW_LATENCY20s -> release25s. Actual cumulative active time
increased23.311s on Tablet and22.260s on S9. Rejection0, underrun5 occurred
in the first2s only, steady deltas0; xrun0, HWM2640/2880. Kernel maxima remain
roughly34.5-37.1ms in the enabled windows and27.5-44.2ms after release. This
does not causally establish a required radio-mode fix; no mode is retained.
Normal Disconnect/Waiting at16:04:11.626Z. File prefix
`mic-autonomous-both-radio`; this is diagnostic, not replacement for failed
counted candidate cycles.

### Revised bounded-budget candidate

Measured signed phase span of about64ms plus one256-frame callback quantum
(5.333ms) requires approximately70ms reserve. Capacity80ms adds two5ms slots
of partial-slot/burst headroom. `MicrophonePlayoutReserve` now derives16/14
codec frames at5ms:3840frames/7680B capacity,3360frames initial reserve.
Relative to the original20ms/5ms configuration, capacity adds60ms and nominal
priming adds65ms. Pacer and hardware output buffers remain unchanged.
No150ms buffer is introduced. The earlier60ms/50ms correction and its failed
campaign remain historical, not described as final validated behavior.

### Frozen final APK and normal product boundary

SHA256 `4B4B25989EF79D7C2C7FD5E69DFFA515B973F5CC1DC2A2FD1E8EE35538CB071D`.
Installed base.apk hashes were verified equal on S9 and Tablet. Full gates
PASS before final acceptance was classified. One normal Enable Host at
16:18:13.099Z selected `SINGLE / DIRECT_SHELL`, Video/SystemAudio/Input all
DIRECT_SHELL, `shizuku_started=false`. No manual runtime arm, Wi-Fi toggle,
radio mode, Host toggle, app restart or hidden Connect retry between cycles.

| Cycle | Connect UTC | Streaming UTC | Ready/Waiting UTC | Sampled Streaming | Lifecycle | Quality |
| --- | --- | --- | --- | ---: | --- | --- |
|1|16:19:00.156|16:19:18.217|16:20:37.426|69.902s|PASS|PASS|
|2|16:20:37.428|16:20:51.726|16:22:10.768|69.748s|PASS|PASS|
|3|16:22:10.768|16:22:24.954|16:23:43.878|69.898s|PASS|PASS|

Each includes an explicit65s hold after the observed Streaming UI; sampled
runtime duration also contains UI-observation overhead, not audio latency.
Host remained continuously enabled and discoverable for the next Session.

| Cycle | Captured PCM | Encoded units | Protected sent incl config | Received incl config | Decoded units | Decoded PCM / accepted | Rejected | Underrun / zero-fill | Xrun |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
|1|3357600|13990|13991|13990|13988|3357120 /3357120|0|0 /0|0|
|2|3355680|13983|13984|13965|13963|3351360 /3351360|0|0 /0|0|
|3|3358080|13992|13993|13984|13982|3355920 /3355920|0|0 /0|0|

Endpoint totals are asynchronous; their differences are NOT a packet-loss
calculation. Each unit is one negotiated240-frame5ms mono block; CBR Opus is
nominal40bytes per block. Sender generates one media datagram per encoded unit
plus initial configuration, rather than a four-unit SCL aggregation. Exact
RF departure/variable on-air overhead are not measured.

### Startup, steady state, teardown and occupancy

| Cycle | Local Host steady interval | Rejected start -> end / delta | Underrun start -> end / delta | Zero-fill delta | Xrun delta | Counter resets |
| --- | --- | --- | --- | ---: | ---: | ---: |
|1|292827.737..292882.735s (54.998s)|0 ->0 /0|0 ->0 /0|0|0|0|
|2|292921.480..292976.480s (55.000s)|0 ->0 /0|0 ->0 /0|0|0|0|
|3|293014.490..293069.492s (55.002s)|0 ->0 /0|0 ->0 /0|0|0|0|

Startup rejection/underrun/zero-fill are also0 in all final samples. Intentional
pre-PCM warm priming is recorded separately:8/32/15 callbacks,2048/8192/3840
output frames. These are not lost decoded PCM, not steady underruns, and their
frame duration is not measured physical E2E latency. No final ring-full or
underrun diagnostic event occurred. Sampling ends before teardown; no claim
of a full terminal counter snapshot is made merely from the last Streaming
row. All three AAudio close calls returned0 and tracks were removed.

| Cycle | Sampled occupancy min/max | HWM / capacity | Max local submit-to-consume residence |
| --- | --- | --- | ---: |
|1|1680 /3264frames (35.0 /68.0ms)|3568 /3840frames|74.041ms|
|2|1520 /3376frames (31.667 /70.333ms)|3664 /3840frames|76.054ms|
|3|960 /3328frames (20.0 /69.333ms)|3600 /3840frames|74.873ms|

Min/max are sparse snapshots, not a continuous occupancy trace. HWM is the
native lifetime HWM. No second queue exists; all buffering remains bounded.
Oboe route is AAudio Shared LowLatency,48kHz mono,256-frame burst, actual
512-frame buffer (10.667ms),2048-frame hardware capacity, unchanged. AudioFlinger
shows the active Warpnect track on `AUDIO_DEVICE_OUT_SPEAKER` (0x2), not earpiece.

### Final local cadence and sequence integrity

| Boundary / metric | Cycle1 | Cycle2 | Cycle3 |
| --- | --- | --- | --- |
| Capture read-return3-7ms / max |71.44% /11.691ms|80.98% /13.029ms|65.19% /12.056ms|
| Paced encoder input3-7ms / max |99.39% /12.693ms|98.75% /12.870ms|98.88% /11.262ms|
| Encoder output3-7ms / max |90.51% /11.308ms|86.42% /12.951ms|80.35% /11.010ms|
| Steady socket completion3-7ms / max |88.18% /11.295ms|81.44% /12.956ms|74.72% /11.804ms|
| Kernel software ingress3-7ms / max |81.04% /40.848ms|74.61% /41.790ms|71.39% /45.482ms|
| Pre-decoder max gap |41.143ms|42.070ms|45.973ms|
| Kernel local phase min..max | -4.889..34.611ms | -4.543..35.084ms | -3.301..43.486ms |
| Kernel missing units, all12 windows |0|1 in startup window0|1 in startup window0|
| Later missing / duplicate / reorder |0 /0 /0|0 /0 /0|0 /0 /0|

The two single startup gaps remain **INCONCLUSIVE** as to physical departure/
loss. Each receiver decodes one additional240-frame concealment block relative
to its decoded-unit counter; there is no recurring gap, rejected PCM or
playback zero-fill. Do not equate concealment with perfect original signal or
claim zero network loss globally. Socket observer does not expose duplicate/
reorder counters: absence remains UNKNOWN, not manufactured0. Source-position
probes show no gap/reorder/duplicate. Bounded timing windows cover about60s,
not every packet of the full Session.

Post-fix network gaps still resemble the failed paced baseline's41.8-46.2ms,
yet playback loss is0. This and the deterministic old/new ring experiment
support a causal buffering-contract correction, not a claim that Wi-Fi jitter
was eliminated. The earlier154ms episode remains beyond the80ms budget and
not explained at driver/RF level. No low-latency radio lock is product behavior.

### Physical signal and human boundary

During cycle1, S9's normal AudioTrack played997Hz for5s at music7/15. Its own
**physical built-in microphone** captured it through VOICE_RECOGNITION, not
REMOTE_SUBMIX or a digital injection. AudioTrack fixture has no access to
Warpnect capture/ring/transport. Client RMS approximately45 ->850.615 mixed
transition ->3513.414 ->40.917; decoded Tablet RMS45.713 ->842.326 ->3516.389
->41.137. Client peak6368, remote6845, below PCM16 clipping. This establishes
OFF/ON/OFF acoustic capture -> protected encode/send -> remote decode.
Numeric energy only, no spectral precision or audio recording.

Tablet speaker track consumption/route is technically proven; **human remote
audibility NOT_CONFIRMED** for this final APK. No response was received to the
optional listening question. Historical A41 human confirmation is not upgraded
to current Tablet proof. The earlier A41-source5s tone in the rejected candidate
did not produce a distinguishable S9 energy increase; device proximity/emission
was not independently established, so that acoustic attempt is **INCONCLUSIVE**.
Both A41/S9 temporary music adjustments were restored to their observed15/15.

### SystemAudio, Video/Input and resource regression

After the three mic Sessions, the checkbox was unchecked and one normal
Tablet -> S9 SystemAudio control ran under the same enabled Host:
16:25:34.202Z Connect,16:25:51.461Z Streaming,16:26:28.932Z Ready/Waiting,
requested20s hold. Host capture959040frames, encoder3996 units/319680bytes,
sent3997 datagrams; capture ring occupancy0/HWM7, overruns/drops0,
WouldBlock/send failure0. S9 received4612, decoded4611 units/1106640 PCMframes,
accepted1055040/rejected51600, underrun518/46560 zero-fill, xrun0. These
asynchronously sampled counters are not an end-to-end subtraction.

SystemAudio **technical/lifecycle PASS**, not zero-loss quality PASS or new
human REMOTE_ONLY proof. Its unchanged20ms ring/5ms threshold/zero priming
retain earlier pressure (closest earlier controls rejected50880/under436 and
71760/under804). This is preserved **INCONCLUSIVE quality debt**, not hidden
as a new PASS or asserted to be a new Mic-local regression. No routing policy
was changed; accepted REMOTE_ONLY evidence remains separate.

Video capture/encoder/control and DirectShell Input gateway run without reported
provider failures; normal release is observed. No new human visible-video or
touch-injection qualification is claimed. Shared SCL/network behavior was not
changed by this correction; automated Input/Video regressions remain in the
full suite. Previous owner-bound liveness is preserved, not redesigned.

Every mic cycle logs `MIC_CAPTURE_STOPPED` and `MIC_CAPTURE_CLOSED`; each
Tablet mic AAudio stream close returns0. Input release and Video mirror/encoder
release precede Host Waiting; warm Host ownership intentionally survives.
Final normal Disable Host16:28:45.349Z: Tablet Ready / Host Not checked,
DirectShell helpers0, `/data/local/tmp/warpnect-directshell-secret-*.bin`0,
app files contain `profileInstalled` only. Publication stopped, AudioFlinger
contains track-removal history rather than a current active Warpnect sink.
Owned logcat collectors22720/37940 were stopped by verified PID/command; no
broad process kill. No global FD leak-freedom claim. All radio probes released.

## Software and anomaly preservation

New focused coverage: duration-derived reserve, unchanged SystemAudio, explicit
larger config, invalid duration, local relative phase, warm Mic start, explicit
gate preservation; native ordered5ms/40ms-gap-and-burst simulation on the real
ring, and sticky initial priming that cannot hide later underflow.
Native Debug/Release CTest each22/22 passed on the final source (6.22s/3.50s).

The first full Gradle run executed972 JVM tests with3 failures: DEBUG calls to
`android.util.Log.i` threw in the local JVM's Android stubs. Diagnostics were
made non-fatal, not counter-suppressed; that failed run remains preserved and a
fresh full gate run passed. Earlier compilation/ktlint/test-fixture fixes
remain in generated logs; none are hidden hardware retries.

Anomaly classification and closest useful comparison:

| Failed boundary | Classification | Comparison / disposition |
| --- | --- | --- |
| Original paced quality, radio-only and phase-only runs: ring rejection/underflow | PREEXISTING_PRODUCT_DEFECT | Source pacing passes locally;60/50 reserve removes steady loss in an exploratory run, demonstrating that a good source histogram was insufficient. Old20/5 contract replaced; every failed run retained. |
| First60/50 run: ring fills before cold output consumes reliably | PREEXISTING_PRODUCT_DEFECT | Warm-clock candidate has rejection0; startup/output readiness separated from PCM reserve. |
| Unpaced candidate: startup sequence holes/reset and later playback loss | INCONCLUSIVE | Closest paced60/50 run had steady deltas0; exact missing-unit cause not established. Pacer removal reverted, no claim that removal is valid. |
| Counted60/50 cycle1: continuous roughly60ms gap then zero-fill/rejection | PREEXISTING_PRODUCT_DEFECT | Warm-clock exploratory pass versus measured63.161ms arrival phase span;50ms reserve insufficient. New80/70 budget then passes3/3. |
| Counted60/50 cycle2: ring losses under insufficient budget | PREEXISTING_PRODUCT_DEFECT | Same generation's prior cycle also fails; measured longer stall is separately unresolved below, not hidden by a retry. |
| Same cycle2:153.890ms software ingress gap, some bounded sequence holes | INCONCLUSIVE | Source socket max13.534ms; final receiver still has40-45ms gaps with no playout loss. No RF departure evidence, no150ms queue or invented driver fix. |
| Counted cycle3: Disconnect offscreen | TEST_HARNESS_DEFECT | Prior two normal Disconnects passed. One scroll exposes existing control; final harness scrolls only if needed, never retries Connect. Teardown counters are separately bracketed. |
| A41 acoustic-source attempt: no distinguishable S9 energy increase | INCONCLUSIVE | Final S9 self-speaker acoustic OFF/ON/OFF control gives clear capture/decoded energy. No prior failed acoustic attempt is relabelled audible. |
| First full JVM gate:3 Android Log stub failures | TEST_HARNESS_DEFECT | Existing controller tests reject the diagnostic side effect; non-fatal bounded logger restores972/0/0/0 without suppressing playback counters. |
| Unchanged SystemAudio quality pressure | INCONCLUSIVE | Earlier controls already have similar pressure; technical/lifecycle PASS remains distinct from quality. Follow-up debt, no opportunistic SystemAudio buffering change. |

Generated evidence is excluded from commits: `app/build/reports/` prefixes
`mic-autonomous-radio-host`, `mic-autonomous-radio-client`,
`mic-autonomous-phase-baseline`, `mic-autonomous-reserve-candidate`,
`mic-autonomous-unpaced-candidate`, `mic-autonomous-warm-candidate`, final gates
and native logs. No PCM or speech recording is persisted. Generated PowerShell
analysis/normal-UI harnesses are local diagnostics, not product code.

Final gates: `git diff --check`, `:app:ktlintCheck`, `:app:lintDebug`,
`:app:testDebugUnitTest --rerun-tasks` (**972/0 failures/0 errors/0 skipped**),
`:app:assembleDebug`, `:app:assembleDebugAndroidTest`,
`:app:packageDirectShellProbeDex` all PASS. Full Gradle run10m6s/100 executed
tasks; no source changes after the frozen APK. Subsequent edits are docs and
generated analysis only. Debug/Release CTest22/22 each, no failed native tests.

Eight added JVM scenarios:5 `MicrophonePlayoutReserveTest` cases, relative
nominal-phase safety in `AudioCadenceDebugProbeTest`,2 warm-start/explicit-gate
cases in `AudioSessionControllerTest`. Existing playback validation, ring-full
error, stop/restart, generation and native mono/stereo tests remain. Two added
native cases use real ring admission/consumption and sticky priming; no fake
physical-audibility tests. The replay now also proves the60/50 candidate fails
a60ms pause and80/70 succeeds without hiding later underflow.

Exact added tests:

| Suite | Scenario |
| --- | --- |
| `MicrophonePlayoutReserveTest` | `fiveMillisecondMonoFramesHaveBoundedReserveWithoutChangingTransport` |
| Same | `reserveIsDurationBasedRatherThanAssumingFiveMillisecondPackets` |
| Same | `explicitLargerCapacityAndPrimeAreNotSilentlyReduced` |
| Same | `systemAudioConfigurationIsUnchanged` |
| Same | `invalidPacketDurationCannotCreateUnboundedReserve` |
| `AudioCadenceDebugProbeTest` | `relativeNominalPhaseSeparatesArrivalDelayFromSequenceLoss` |
| `AudioSessionControllerTest` | `optionalMicrophoneClockWarmsBeforePcmAndStopsWithItsSession` |
| Same | `explicitPlaybackGateDoesNotAllowWarmStartToBypassIt` |
| Native `audio_playback_ring_tests` | `bounded_network_jitter_requires_both_playout_reserve_and_burst_headroom` |
| Same | `warm_output_priming_does_not_consume_early_or_hide_later_underflow` |

Final numeric artifacts: `mic-autonomous-reserve80-final-<serial>.log/.err`,
`*-setup.txt`, `*-sessions.txt`, `*-analysis.json`, `*-systemaudio.txt`,
`*-audioflinger-during.txt`, `*-audioflinger-after.txt`, plus
`mic-autonomous-reserve80-{final-gates,debug-ctest,release-ctest}.txt` under
ignored `app/build/reports/`. Earlier prefixes remain untouched.

## Security, product scope and remaining boundary

Pairing/trust, Session generation/authentication/anti-replay, MicrophoneAudio
negotiation, Audio Payload V1 and SCL contracts remain unchanged. Capture still
requires RECORD_AUDIO and explicit DEBUG Session opt-in. No unauthenticated PCM
path, generic privileged API, provider migration or automatic recovery.
Complete Host remains Video/SystemAudio/Input. DirectShell owner-bound lifetime
at715b215 is not redesigned. Debug speaker playback is not virtual microphone.

Capture-preset-wide Dynamic AudioPolicy/RemoteSubmix injection is **NOT STARTED
IN THIS TASK**, even if cadence passes. Existing UID-scoped experiments remain
historical and are not relabelled global injection. HOST_AND_REMOTE SystemAudio,
Host display policy, RFC-005J/K, physical DirectRoot, Bluetooth/USB/headset
inputs, broad third-party compatibility and Phase7 optimization remain deferred.

## Final classification and checkpoint discipline

**FIXED_AND_VALIDATED** for recurring Warpnect-caused MicrophoneAudio DEBUG
playout rejection/underrun on the exact S9/API29 -> Tablet/API33 final path.
Not universal Android/network qualification, not physical-speaker human proof,
not a production microphone destination or Phase7 latency baseline.

**MICROPHONE_AUDIO_CADENCE_AND_PLAYBACK_QUALITY_FIXED_AND_VALIDATED**.

Ending HEAD remains `715b2151af2c8ecac165729178266b24e5cae91b`; no new commit.
The optional checkpoint was not taken: the wider inherited microphone worktree
still contains partial AudioPolicy prototypes and a pre-existing Git lock; all
validated changes are left for review without touching that state. No staged
files, push, merge, rebase, reset or clean. Source/debug changes are separate
from tests,8 updated current docs, generated ignored evidence and inherited
159 native-output/47 unrelated paths. Exact final status is recorded in the
task close-out, not inferred from the starting count.

Final status: staged0, unstaged196, untracked71, total267 paths. Classification:
39 source/debug,12 tests,10 docs,159 generated native-output,47 unrelated.
These are whole-worktree counts, not all changes introduced by this task.
The existing empty Git lock retains its previous modification time. Final
`git diff --check` passes after doc edits; local PowerShell preparation,
normal-UI campaign and analysis scripts each parse with0 errors. No owned
ADB/logcat collector remains running.

Authoritative docs updated in this task:
`docs/phase6_5/MicrophoneAudioCadenceRootCause.md` (new),
`docs/phase6_5/DirectShellMicrophoneInjectionQualification.md`,
`docs/phase6_5/MicrophoneAudioCadenceAndVirtualInput.md`,
`docs/phase6_5/MicrophoneAudioE2EQualification.md`,
`docs/phase6_5/CurrentNonRootDebt.md`, `docs/phase6_5/README.md`,
`docs/FutureRoadmap.md`, `docs/testing/AndroidCompatibilityMatrix.md`.
Other inherited doc/source changes and partial policy probes remain untouched.

Remaining real risks: longer-duration clock skew/rare network stalls beyond
the bounded reserve; unexplained single startup sequence gaps; final Tablet
human/intelligible output; other device/output routes; unchanged SystemAudio
quality pressure. The added65ms nominal reserve cost needs explicit review,
not concealment behind smoother local histograms.

One recommended next task: **portable DirectShell global MicrophoneAudio
injection via Dynamic AudioPolicy + RemoteSubmix + capture-preset matching**,
no UID rules and runtime capability probing, after review of this cadence
tradeoff. It is not started automatically. Full background/screen-off/power/
fixed-duration timeout audit remains RFC-005K or the appropriate future RFC;
owner lifetime715b215 is not reopened.
