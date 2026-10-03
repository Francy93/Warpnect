# Global Microphone Final Closure - 2026-10-03

## Current Acceptance Boundary

The functional milestone `DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED`
is retained. Frozen C782DD9D r2 passes **3/3** consecutive primary Sessions under
one Enable Host, including ordinary MIC close/reopen and physical restoration.
`DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_QUALITY_VALIDATED` applies to this exact
S9/API29 -> Tablet/API33 non-root DEBUG integration path and acceptance windows.
It is not universal qualification, release promotion or forced-priority validation.
Earlier failures below are not overwritten or retroactively passed.

## Repository And Exact Hardware

Branch `codex/directshell-production-runtime-integration`; starting/ending HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`; master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, ahead19/behind0.
Checkpoints92347d0/809b7dd/715b215 verified. Staging remains empty; the large
inherited microphone/runtime/native-generated worktree and empty index.lock
remain preserved. No reset, clean, discard, stash, rebase, merge or push.

| Device | Exact serial | Android/API | Exact fingerprint | Role |
| --- | --- | --- | --- | --- |
| X700_EEA | Z1382023HGM1895372 | 13/33 | Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys | Primary DirectShell Host/input consumer |
| SM-G960F | 23eaec0f12047ece | 10/29 | samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys | Physical microphone Client |
| SM-A415F | R58N52B32JJ | 12/31 | samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys | Capability control, not substitute acceptance |
| SM-G935F | 9886734e4937395657 | 8.0.0/26 | samsung/hero2ltexx/hero2lte:8.0.0/R16NW/G935FXXU8EVH2:user/release-keys | Context/permission probe |

S22 is not used. Exact serials are used for every action. No Root command,
security weakening, framework/HAL/system/vendor/property modification or
silent Shizuku fallback. Release microphone availability is not promoted.

## Peer Link

[Preserved network investigation](S9TabletPeerLinkRecovery.md) proves a
pair-specific non-Warpnect TCP/UDP failure, not just ICMP filtering. Router and
A41 controls passed. The bounded Tablet Wi-Fi reassociation restored the same
SSID FibreBox_X6-162367, BSSID6c:99:61:16:23:65/5320MHz, IPv4
192.168.1.57/24 and private MAC5e:42:d8:d0:6d:ad; S9 is192.168.1.4/24.
Bidirectional ping3/3 and independent TCP/UDP controls subsequently pass.
No router/AP/security setting or product Wi-Fi-recovery code changed.
TDLS handshakes precede some later failures, but a one-shot TDLS teardown did
not prevent another handshake/failure. Initiating driver/AP/firmware mechanism
remains INCONCLUSIVE, not a proven Warpnect media bug. Android disabled Debug
wireless on radio loss; the user restored it manually. Current admission
verifies1. Earlier failed campaigns retain their environmental classifications.

Initial controls: S9 and Tablet gateway192.168.1.1 each3/3, peer each0/3;
A41 .36/24 reaches both3/3. All usewlan0/same BSSID. Correct direct routes and
expected neighbour MACs, no observed duplicate-IP, active VPN or tether upstream.
Independent port48671 TCP connections fail both directions; S9->Tablet UDP
arrives but reverse echo fails, Tablet->S9 UDP fails; A41 controls pass. First
proven boundary is Tablet-to-S9 delivery, not just ICMP. Private MACs unchanged;
AP isolation/VLAN/TDLS/driver cause not proved. IPv4/IPv6 later controls pass;
IPv6 is not a justified substitute for the failed primary IPv4 transport.

## Architecture And Ownership

Client `AndroidMicrophoneAudioCaptureController` uses the existing public
microphone source preference UNPROCESSED, otherwise VOICE_RECOGNITION, with
RECORD_AUDIO authorization and built-in microphone routing. S9 uses the latter
on this build. Format48kHz/mono/PCM16,240 frames/480 bytes per5ms Opus unit,
64kbit/s. `MicrophoneChunkPacer` -> `NativeOpusAudioEncoderController` -> audio
sender/Audio Payload V1 -> authenticated protected SCL Session/UDP ->
`NativeSclAudioReceiverController` -> `AudioReceiverSessionController` -> native
Opus decode -> Session-owned optional destination. Debug Oboe and Android input
are independent destinations; decoder reconstruction does not own the policy.

`DirectShellMicrophoneInputSession` / `AsyncRemoteMicrophoneDestination`
acquire outside media ingress. `DirectShellRemoteMicrophoneSource` has one
audio-priority IPC worker, authenticated purpose-specific PCM feature channel,
owner generation and16 FIFO slots. `DirectShellRemoteMicrophonePolicy` runs
behind the existing shell UID2000 helper, not a generic daemon/PCM endpoint.

Actual framework APIs: AudioMixingRule.Builder.addRule with
RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET and AudioAttributes.setCapturePreset;
recorder-mix criteria MIC1/VOICE_COMMUNICATION7/CAMCORDER5; LOOP_BACK routing;
AudioManager.registerAudioPolicy. AudioTrack uses USAGE_VIRTUAL_SOURCE and
the mix registration address, mono48kPCM16 streaming/nonblocking writes.
Actual built criteria are audited, not merely requested. **UID RULES=NONE.**
Ordinary test app UID10213 requests AudioRecord(source), with RECORD_AUDIO,
no preferred device, private ring, callback, helper Binder or UID criterion.
Input type25/address of the owned dynamic submix is selected naturally;
physical type15 inputs remain enumerated. Policy lifetime is Session lifetime.

## Real-Time PCM Policy

No active unsilenced consumer on the owned route: discard real remote PCM;
feed only maintenance zeros. Callback device/patch resolution runs outside
the feed monitor. Consumer close pause/flush/play also runs outside that
monitor. Opening does NOT flush AudioTrack or reduce its start threshold.
Those earlier unsuccessful experiments remain removed.

On open/reopen, a local monotonic consumer-window guard rejects frames enqueued
before the observed active window. Partial IPC retries preserve original age
and generation. Existing fixed freshness is **40ms**, not the old prompt80ms.
Expired FIFO heads cannot displace fresh audio; active full valid queues still
report true rejection. No consumer does not justify retaining old voice.

Queue capacity16 nominal240-frame slots is an80ms allocation ceiling, NOT an
80ms eligibility budget. Every eligible IPC frame must be no older than40ms;
late dequeues are discarded. This measures same-Host queue age after decode,
not unsynchronized Client-capture-to-Host-consumer latency. A local guard
cannot certify that acoustic capture itself happened after an unrelated
device's wall-clock T. No raw voice/PCM recording is persisted.

## Discriminating Investigation And Preserved Failures

| Attempt | First trustworthy observation | Result/classification |
| --- | --- | --- |
| 43FB71D6 r1/r2 | Peer/media loss, Tablet radio loss then Android Debug wireless0 | FAIL;2/3 not run; environmental prerequisite, initiator INCONCLUSIVE |
| 43FB71D6 r3 | UI Streaming is video-specific, fresh authenticated microphone already advances | Harness/readiness distinction; separate video anomaly retained |
| 43FB71D6 r4 | First steady DEBUG under+1/track+2; reopen valid240-frame rejection | Quality FAIL, not closed by functional signal |
| 925727BB observer | Atomic240-frame block,224 free frames, regular256-frame callbacks | Proven callback-phase/frame-quantization admission defect |
| 81F10D24 final r1 | First track+3; reopen DEBUG under+7/track+13; source protected-send gap83.009ms and matching Host kernel gap83.510ms | FAIL;2/3 not run; source/runtime stall trigger INCONCLUSIVE, not proven Wi-Fi or GC |
| 0FA230D4 source-stage r1 | Host InputManager duplicate-PID listener SecurityException, later S9 InvalidPresence is downstream | FAIL; one explicit app restart gives nearest PASS, exact platform cause INCONCLUSIVE |
| 0FA230D4 final | Three complete primary Sessions, all sampled active quality deltas0 | Historical exact-APK3/3 PASS; Input failure still visible and investigated |
| Input regression | Valid AllDevices reset slot65535 rejected by JVM bridge | Proven pre-existing local correctness bug; reserved slot accepted only subject to existing typed-event validation;4 targeted tests |
| 4B809B21 final r1 | Cycle1 PASS; cycle2 AudioTrack steady+2, DEBUG/IPC0 | FAIL;cycle3 not run, not replaced by earlier3/3 |
| 9F356B25 observer | Active underrun at17.222s, accepted head equals written total, PCM age30.061ms/feed gap6.006ms; DEBUG/IPC queue rejection0 | Reproduced lack of Android-track timing reserve, not packet corruption; diagnostics do not fabricate sound |
| C782DD9D quantum diagnostic | Same capacity, absent maintenance960 instead of240 frames; first60s/reopen10s all active quality deltas0 | Controlled single-change diagnostic PASS, not acceptance |
| C782DD9D final r1 | Steady first60s all0; reopen runner includes222 DEBUG underruns AFTER Client capture stops/Host VIDEO_STOP | Original FAIL preserved; TEST_HARNESS_DEFECT/TEARDOWN_METRIC_ARTIFACT, not retroactive PASS;2/3 not run |
| C782DD9D preset/Input control r1 | S9 intercepts F1 and opens OS shortcut help, preventing reliable acoustic-tone start and normal Disconnect lookup | TEST_HARNESS_DEFECT; route25 observed, not valid tone proof; explicit same-Session cleanup, no Connect retry |
| Same control cleanup r1 | One-shot UI assertion before dialog-dismiss transition completes | TEST_HARNESS_DEFECT; bounded state wait and conditional dismissal complete same existing Session |
| C782DD9D normal Enable/Disable control r1 | Helper regex counts only2 because it omits `videocapture`; raw ps and authenticated readiness show all3 | TEST_HARNESS_DEFECT; correct observer, continue same enabled Host without another Enable, normal Disable PASS |

The observed receiver scheduling defect was separate: normal-priority runnable
wait33913ms/~111s, max136ms, socket age932ms; microphone-only actual receive
thread audio priority reduces measured runnable wait to2469ms/max24ms and
socket age about7.5ms. Existing Android priority helper is applied on the
actual worker, including adopted prepared transports, not Session/main thread.
SystemAudio priority and socket/codec/security primitives are unchanged.

One output-burst admission wait prevents discarding240 good frames for a
16-frame shortage. Tablet burst256/48k =5.333333ms maximum requested wait,
at most250us per poll; no added queue, capacity growth or suppressed counter.
OS preemption may overshoot sleep, so this is not a hard real-time guarantee.

Concurrent-GC total durations and source stalls were observed, but scheduler
trace integrity/coverage limits prevent calling GC the sole cause. Multiple
later117-140ms concurrent collections occur without audio loss. New bounded
read/timestamp/pacer/sink/thread-CPU probes preserve this distinction.

Final causal model separates three proven local defects, not one guessed Wi-Fi
fault: recoverable source pacing debt, runnable receive-worker starvation, and
atomic whole-block admission near a regular output callback. A FOURTH destination
boundary is Android-track starvation on initial/reopened consumer: helper accepts
real PCM, head catches ALL written frames despite short feed gaps and DEBUG
reserve still healthy. Previous maintenance supply240 per incoming packet does
not establish a usable Android-track reserve through absent/route transitions.
The controlled960-zero supply using the existing buffer eliminates recurring
active starvation on the final exact3/3 path. It does not imply every rare source
stall or initial platform route transition is fixed universally.

Kernel diagnostics use DEBUG recvmsg SCM_TIMESTAMPNS in local CLOCK_REALTIME,
not an RF/NIC hardware timestamp and not a Kotlin recvfrom-return timestamp.
Socket age uses that process's realtime now; dispatch/service durations remain
local monotonic. Equal source/send and kernel receive gaps can correlate by
unit identity without subtracting independent device clocks. No proof that
application sendto time equals actual RF departure or Wi-Fi is the sole residual
mechanism. Decoder/JNI cannot cause a gap already present in ancillary receive
timestamps; runnable-wait measurement separately proves delayed local draining.

## Final Candidate And Latency Tradeoff

Frozen SHA256 `C782DD9D0FD63251C8895CC13344F7EE4EE60E6AF6472FD6F6EC499182AB508F`,
verified from installed base.apk on both primary devices. The final one-line
policy change feeds the already allocated960-frame zero block while absent,
instead of binding maintenance supply to incoming240-frame real packets.
It does not enlarge Android's4104-frame buffer, DEBUG ring, IPC queue, codec
packet, socket buffer or source pacer. Writes remain nonblocking. Active
consumers never receive maintenance zeros instead of current real PCM.

This can keep Android's existing buffer supplied before route activation and
therefore has a latency tradeoff: the default4104/48k =85.5ms track capacity
can contain maintenance silence, not pre-open speech. Existing HAL pipe latency
is separately platform-owned. Controlled quantum diagnostic active IPC
residence p50<20/p95<30/p99<30ms versus observer p50<1/p95<3/p99<20ms;
no new mandatory40ms delay is imposed, but smoothing is not free.
Final numeric occupancy/residence is reported below, not called zero-latency.
The40ms limit bounds PCM at injection admission, not its residence inside the
Android track/HAL after acceptance. Absolute physical end-to-end latency remains
Phase7; the existing Android buffer is not a new Warpnect capacity allocation.
Nominal combined admission-age plus full track capacity can therefore reach
40+85.5=125.5ms BEFORE any additional platform HAL/scheduling latency; this is
component accounting, not a measured end-to-end bound or an imposed125.5ms delay.

DEBUG80ms ring/70ms initial reserve is unchanged. Pacer redistribution can add
up to15ms for a four-chunk HAL batch; receiver-priority adds no intentional
delay; conditional ring admission adds up to one actual burst. These are not
the40ms injection freshness limit. Fixed40ms policy is retained for the validated
path: eligible maximum38.112ms, no steady stale loss, bounded queue with headroom.
**Adaptive policy: NOT NEEDED on this evidence.** No adaptive implementation or
fictional fixed/adaptive A/B is claimed; other workloads may require new evidence.

## Final Campaign

C782DD9D r2 uses one normal Enable Host. Each cycle: absent physical tone2s,
ordinary MIC72s including OFF/997Hz/OFF, absent/closed about10s, reopen28s with
another OFF/ON/OFF, consumer RELEASED verified BEFORE Disconnect, Ready/Waiting,
physical MIC4s control and fresh rediscovery. Counting stops at any failure.
The runner correction separates teardown before comparing quality; raw teardown
counters remain in logs, not erased from telemetry.

Enable Host once at **2026-10-03T17:37:46.2278505Z**. Installed base.apk hashes
match C782DD9D on both devices before acceptance and after regressions. No Host
toggle, app restart, hidden Connect retry or Wireless Debugging change within
the three counted Sessions. Host readiness generation6 freezes SINGLE/DIRECT_SHELL
for Video/SystemAudio/Input; shizuku_started=false.

| Cycle | Session generation | Microphone Streaming UTC | Disconnect UTC | Ready/Waiting UTC | Observed Session seconds | Ordinary first/reopen seconds | Result |
| --- | --- | --- | --- | --- | ---: | --- | --- |
| 1 | 7547433216150259640 | 17:38:17.623637 | 17:40:19.870460 | 17:40:30.027893 | 122.246823 | 72.022 / 28.027 | PASS |
| 2 | 2603352320720249039 | 17:40:55.521087 | 17:42:57.013545 | 17:43:07.165077 | 121.492458 | 72.053 / 28.028 | PASS |
| 3 | 7831846463053171508 | 17:43:36.298279 | 17:45:37.691718 | 17:45:47.826520 | 121.393439 | 72.025 / 28.042 | PASS |

Fresh discovery/Connect/Streaming, ordinary consumer released before Disconnect,
Client Ready, Host Waiting/Available, then next discovery work every cycle.
Final campaign result at17:46:03.819657Z. All times above are UTC on2026-10-03;
Session duration is control observation, not cross-device media latency.

### Ordinary Consumer Physical Signal

All consumers uid10213/sourceMIC1/preferred=false. Routes25/2741,25/2759,25/2777
are distinct Session submixes, not hardcoded product IDs. Every initial/reopened
window has low OFF energy, strong997Hz correlation ON, and low energy again OFF.
An earlier2s tone while absent does not reappear in the first OFF window. Built-in
bottom/back inputs remain enumerated during policy; after each Disconnect MIC
returns to15/11, without selecting a device or knowing the helper/PCM ring.

| Cycle | First OFF minimum / ON maximum RMS | First997 amplitude maximum | Reopen OFF / ON RMS | Reopen997 maximum | Restored physical RMS range /997 maximum |
| --- | --- | ---: | --- | ---: | --- |
| 1 | 43.917 / 4442.555 | 6280.676 | 46.615 / 4468.498 | 6317.685 | 29.715-68.280 /0.124 |
| 2 | 19.326 / 4480.463 | 6335.239 | 19.579 / 4614.765 | 6523.991 | 22.323-29.739 /0.138 |
| 3 | 18.474 / 4808.980 | 6799.340 | 20.798 / 4805.200 | 6794.560 | 34.253-56.076 /0.326 |

These are PCM16 units from numeric one-second windows, not persisted recordings.
The consumer reports997Hz correlation, not a general FFT dominant-frequency
estimate. First/reopen windows contain71/27 reported numeric signal windows per
cycle; decoded Host tone windows reach RMS4247.922/4200.169/4544.216 and peaks
below PCM16 clipping. Physical sound from S9 speaker traverses S9's actual mic,
not a synthetic transport injection. Final human audibility **NOT_CONFIRMED**.
For example, first Client capture OFF/ON/OFF windows have RMS50.423/4244.299/
75.575 and997 amplitude0.338/5873.914/0.517; corresponding Host decode windows
RMS50.073/4247.922/75.712 with997 amplitude0.304/5880.416/0.609. This is signal
presence/correlation across endpoints, not a subtraction of device timestamps.

### IPC Release Accounting

All numbers are PCM frames. Submitted is enqueue-intent accounting, written is
real PCM accepted by the helper/track; maintenance silence is separate. HWM
measures actual FIFO blocks, not a fictitious device-clock latency estimate.

| Cycle | Submitted | Written | Dropped no consumer | Dropped stale (includes admission expiry) | Pre-consumer window | Unexpected queue rejection | HWM blocks | Max eligible source age ms | Max dequeue age ms | Residence p50/p95/p99 upper ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 6115680 | 4795680 | 1313520 | 4800 (1680 admission) | 1680 | 0 | 9 | 37.591384 | 42.721538 | <1 / <3 / <10 |
| 2 | 5939760 | 4790976 | 1142064 | 6480 (1440 admission) | 240 | 0 | 9 | 38.038154 | 42.612846 | <1 / <30 / <30 |
| 3 | 5975760 | 4791296 | 1180688 | 2576 (240 admission) | 1200 | 0 | 9 | 38.112077 | 41.523308 | <5 / <20 / <20 |

Written + no-consumer + stale + pre-window equals submitted in every release;
no unaccounted retained frames. Generation/teardown-specific drop counters are
NOT EXPOSED individually; tests/ownership reject them, but no invented physical
zero is assigned. Actual helper max accepted age38.906461/39.908539/39.472ms,
all below40ms. A41/Tablet clocks are not subtracted. Queue residence is the
existing coarse-bin distribution including expired work, not exact percentiles.
41-43ms expired dequeue tails are discarded, not delivered. Active sampled
occupancy first0-1/0-1/0-3, reopened0-1/3-5/1-3; no sustained near16-slot capacity.
HWM9 nominal blocks is45ms allocation at240 frames, not permission to retain
voice past40ms. Android's already accepted track/HAL audio has its own latency.

### Startup, Active, Close And Teardown

| Cycle | First / reopened sampled steady seconds | DEBUG rejected / underrun / xrun deltas | Track underrun delta | IPC rejected / stale / pre-window active deltas | Policy owners / decoder generations |
| --- | --- | --- | ---: | --- | --- |
| 1 | 65.002 /15.000 | 0 /0 /0 in both | 0 in both | 0 /0 /0 in both | one Session policy / audio generation1 |
| 2 | 60.003 /15.000 | 0 /0 /0 in both | 0 in both | 0 /0 /0 in both | one Session policy / audio generation1 |
| 3 | 59.999 /19.997 | 0 /0 /0 in both | 0 in both | 0 /0 /0 in both | one Session policy / audio generation1 |

Reader durations exceed72s in all cycles;59.999 is periodic snapshot coverage,
not a short Session. First five seconds are excluded from steady comparisons.
Absent/open/close/reopen transition drops are intentionally visible in release
totals. No policy recreation or decoder failure is observed; closed consumer
causes real-PCM discard/maintenance only, not Session reset. No active queue
rejection is hidden by DEBUG independence. Teardown begins only after ordinary
reader RELEASED; post-stop zeros cannot be mislabelled continuous live underrun.
Closed native counters are not retained as a separate final teardown snapshot;
no zero teardown delta is invented. Release balance, stopped capture/consumer,
policy removal and physical restoration establish that ownership boundary.

Last active Host snapshots (not release totals): decoded PCM5994240/5756400/
5756400, DEBUG written identical, consumed5991168/5753344/5753088; rejected,
underrun and xrun all0; ring HWM3600/3840/3840, max DEBUG residence73.989154/
75.841846/78.833307ms. Priming22/13/15 callbacks fills5632/3328/3840 silence
frames BEFORE ready audio, reported separately from underrun. Receiver
datagrams24977/23986/23987, complete/delivered24976/23985/23986. Bounded kernel
sequence windows after initial configuration show missing/duplicate/reorder0;
window0's one configuration boundary is not proof of lost microphone media.
There is no full-session packet capture, so universal zero network loss is not
claimed from asynchronously sampled sender/receiver totals.

### RemoteSubmix Write Observation

Last five-second helper snapshots, not fabricated end-of-Session totals:

| Cycle | Real written | Idle silence written | Zero writes | Partial writes | Maximum write ms | Maximum close transition ms | Track underruns |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 4795680 | 1161048 | 9 | 0 | 4.525616 | 40.288 | 0 |
| 2 | 4784736 | 930832 | 11121 | 4242 | 4.948615 | 44.696538 | 0 |
| 3 | 4750336 | 969744 | 15292 | 4245 | 3.402153 | 24.965846 | 0 |

Nonblocking zero/partial results are retried with ORIGINAL PCM age and bounded
FIFO ownership; they are not automatically lost valid buffers. Failed/stale
tails remain counted. No native blocking write mode is added. Framework
AudioTrack/Binder may still consume several milliseconds locally; nonblocking
does not promise hard real-time scheduling. Track software starvation and
Oboe xrun are distinct counters. Consumer transitions run outside the feed lock.

## Final Bounded Regressions And Cleanup

Fresh preset control `final-C782DD9D-presets-clean-r2` starts Streaming17:59:35Z,
Disconnect18:00:09Z, Ready/Waiting and physical controls complete18:00:35Z.
No pre-tone keyboard shortcut is sent. Same global rule set, UID NONE:

| Preset | Natural during route | OFF ->997Hz ON ->OFF RMS |997 correlation peak | After policy |
| --- | --- | --- | ---: | --- |
| MIC1 | type25, each final Session address | See3/3 table | 6280.676-6799.340 | bottom15/11 |
| VOICE_COMMUNICATION7 | type25/id2819 | 85.338 ->4019.960 ->52.553 | 5677.675 | bottom15/11 |
| CAMCORDER5 | type25/id2819 | 24.028 ->4023.097 ->35.631 | 5687.163 | back15/12 |

Each additional consumer14s, source-aware ordinary AudioRecord, no preferred
device. Focus transitions exercise real protected Input resets: received6,
mapped2, mapping/injection failure0, receiverRunning. This is a reset/lifecycle
smoke, not a claimed new physical key/touch E2E qualification. Video encodes152
AUs/transmits830datagrams, encoder/control/transport failure0; Client reaches
Streaming. No full new video/input matrix is claimed.

SystemAudio control starts18:01:04Z, Streaming18:01:18Z, lifecyclePASS18:01:53Z,
20s requested Streaming plus normal UI/teardown. Eight-second Host tone reaches
S9 through DirectShell/protected SystemAudio; no microphone opt-in. Last active
epoch decoded234240/written214800/rejected19440/underrun58/xrun0, ring960frames
unchanged. Earlier epoch snapshot rejected9840/underrun96; cumulative ring-full
blocks207 survives local output resets while rejected/under counters restart.
These are NOT whole-run summed counters or comparable to old unequal-duration
30720/373/0. Technical/lifecycle PASS, quality STILL_OPEN; no new human routing
claim. No change to REMOTE_ONLY mechanism or application of80/70ms to SystemAudio.

Intentional helper-loss control:18:03:01.783883Z KILL exact PCM helper22534,
UID2000/generation3447085637107398702. Owner LOST at468109.777 local monotonic,
publication WITHDRAWN reasonDIRECTSHELL_OWNER_LOST at468109.778 (about1ms between
observed notifications), migration=false. Next ordinary MIC sample468110.495
routes15/11 instead of25/2851. Dynamic policy registration1971008002:ap:115mixr:0
is absent in policy dump three seconds after loss; remaining helpers are released.
Consumer continues physical capture then cleanly stops. Client becomes Failed
as expected for provider loss; Host returns Ready/not Available, not stale Running.
No same-provider retry or provider migration occurs.

This separate abnormal test reports TWO track starvation intervals at consumer
elapsed185.691/193.263ms, feed gaps4.914/2.546ms, ages36.549/39.539ms. No further
increment before kill~8s after open. They are a bounded initial route-activation
transient, not erased and not attributed to the later kill; DEBUG/IPC rejection
remain separate. They do not replace the exact3/3 zero-steady campaign or establish
universal artifact-free startup. Perceptual effect is unconfirmed.

Explicit non-campaign Enable18:05:53Z, actual three authenticated helpers
23500/23545/23599, generation9 READY/SINGLE/DIRECT_SHELL. Corrected observer
continues SAME Enable, normal Disable18:07:53Z then Ready. Helpers0/secrets0
on primary devices, no dynamic Warpnect input/policy, no active Warpnect
AudioTrack/AudioRecord or capture mirror in audio/surface diagnostics. Static
platform REMOTE_SUBMIX address0 remains; it is not an orphan Warpnect device.
No global FD leak-freedom claim. Idle S9 restart explicitly clears the expected
provider-loss UI at18:08:00Z, not an acceptance retry. S9/Tablet Ready, Host
disabled; S9 original music15/15 restored with supported `media volume` command
after `cmd media_session volume` was found unsupported. Own global-final-probe
jar removed from Tablet/A41/S7; other historical device diagnostics preserved.

## Framework Portability And Optional Priority

The same backend reprobe on A41 returns PERMISSION_DENIED before policy/PCM
activation; UID2000 lacks MODIFY_AUDIO_ROUTING. CAPTURE_AUDIO_OUTPUT,
RECORD_AUDIO and MODIFY_AUDIO_SETTINGS grants do not substitute for recorder
mix routing authority. Android12 AudioService.registerAudioPolicy ->
isPolicyRegisterAllowed checks the actual Binder caller's routing permission;
the valid-MediaProjection exception is playback capture RENDER|LOOPBACK, not
this LOOPBACK recorder-injection contract. Other routing-role/device-state
entrypoints do not confer the missing authority. No equivalent legitimate
non-root global path was demonstrated; app-specific capture/UID injection is
not promoted as an equivalent fallback. [AOSP AudioService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/services/core/java/com/android/server/audio/AudioService.java).

S7 current product adapter reports API_LT_31_CONTEXT_UNSUPPORTED. Preserved
legacy-context diagnostics reach policy registration and hit routing permission,
so Android8 primitive absence is NOT established. No adapter is promoted to
product merely to reach a second known permission denial. No model/OEM branch,
Shizuku launch or different device architecture is introduced. Existing
DirectShell prerequisites and explicit DEBUG Session opt-in are unchanged;
additional per-app UID/preferred-device/user audio configuration=NONE.

Tablet preference getters for MIC1/VOICE7/CAMCORDER5 succeed with empty lists;
valid setters/clearers are NOT INVOKED here. Shell routing permission is present,
but getter permission and an earlier invalid-preset authorization-only probe
are not valid preference/abnormal-cleanup proof. Natural routing works, so no
forced preference is needed to qualify injection on the tested build.
Android13 stores capture preferred roles in service-wide device inventory,
not the dynamic-policy Binder lease: helper death cleanup cannot be promised
from an ordinary finally block. No forced preference is added without that
crash-safe ownership boundary. [AOSP AudioDeviceInventory](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceInventory.java).

## Health, Security And Deferred Recovery

Required Host remains Video+SystemAudio+Input; microphone is optional. Actual
admission logs SINGLE/DIRECT_SHELL for all three, shizuku_started=false.
Owner-bound helper lifetime remains checkpoint715b215. Historical failed
replacement on lost Debug wireless produces FAIL_WIRELESS_DEBUGGING_DISABLED,
withdraws publication, STALE then USER_ACTION_REQUIRED with fresh generation;
focused registry regression remains green. That exact replacement-bootstrap
failure is historical, not newly reproduced by toggling a user prerequisite.
Current controlled helper loss verifies immediate owner-loss withdrawal above;
explicit subsequent normal preparation generation9 is not hidden auto-recovery.

Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
remain NOT IMPLEMENTED in current RFC-006J. Composite planning selects a
Complete Host BEFORE Session admission and freezes it; it does not recover,
remediate, re-plan or migrate an already failed active runtime. New health
invalidation is not a hidden implementation of those future capabilities.
No change to pairing/trust/authentication/anti-replay/generation/wire payload,
protected channel ownership, provider identity or plan-freeze semantics.

HOST_AND_REMOTE SystemAudio, display policy, RFC-005J/005K, full background/
power/timeout audit, physical DirectRoot and broad app/Bluetooth/USB/headset
qualification remain deferred. SystemAudio quality remains a separate debt.

## Tests, Gates And Evidence Disposition

Focused tests: six MicrophoneChunkPacer timing/catch-up/actual-capture-capacity
cases; four MicrophoneReceiverPriority source/adopted configuration cases;
bounded cadence/receive-gap/capture-stage probes; native one-burst admission
expiry/fitting/invalid/backward-clock and16-frame-shortage cases; four new
InputReceiverBridgeDecoder reserved-slot/global/device/key validation cases.
Inherited frame-window/freshness, route-not-UID, silence-vs-real-consumer,
close-before-acquire, late-generation/closed destination, FIFO expiration and
failed replacement-health tests remain active. None mocks speaker audibility
or Android policy registration as physical proof.

Full post-candidate gates pass: ktlintCheck, lintDebug, testDebugUnitTest
--rerun-tasks, assembleDebug, assembleDebugAndroidTest, packageDirectShellProbeDex,
max-workers2,100 executed tasks,9m18s. Final recounted JVM XML **1022 tests /
163 suites /0 failure /0 error /0 skipped**. Native final Debug22/22 in5.90s;
Release22/22 in6.78s. Android native build passes all configured ABIs.
Earlier two observer ktlint failures and new test long-line ktlint failure
are preserved and corrected, not product changes to make gates pass.

Generated logs/APKs/traces/harnesses live at
`D:/WarpnectDiagnostics/phase6_5/2026-10-03`, excluded from commits. When C:
reached28,880,896 free bytes, two generated logs were hash-verified and moved
to `archived-build-reports`; its README records hashes/original paths. No source,
unrelated file or failed evidence was discarded. Large inherited native build
output and unrelated screenshots/XML/JVM crash reports remain untouched.
Final `git diff --check` passes (only existing LF->CRLF advisories). The new
untracked report is separately checked for trailing whitespace. No product
source changes follow the frozen candidate's final full gates.

No commit is made: the validated solution depends on interleaved pre-existing
uncommitted capture/cadence/injection/native/session work, so automatic isolation
of only this milestone is unsafe. HEAD remains715b215, staging0, tracked dirty266
(220 generated native build entries), collapsed untracked102 including this report.
The empty historical index.lock is untouched. Source/debug changes include the
mic receive-worker priority, bounded pacer catch-up, conditional one-burst ring
admission, numeric probes, maintenance-silence supply and reserved Input reset
fix. Tests and docs are separate from generated builds/logs/APKs/screenshots;
pre-existing attachments/UI dumps/crash reports remain preserved. Final docs-only
whitespace verification passes after editing. Collapsed classification including
untracked entries: source/debug56, tests21, docs18, generated native220,
inherited other53. Generated diagnostics/harnesses onD are outside the repository.

Focused added/updated case files:

- `app/src/test/java/io/warpnect/platform/audio/capture/MicrophoneChunkPacerTest.kt`:
  four-chunk HAL cadence; jitter versus beyond-backlog reset; frame/sample/byte
  accounting and fresh clock; repeated recoverable gaps; bounded catch-up with
  actual late delivery; actual capture capacity rather than packet-count limit.
- `app/src/test/java/io/warpnect/platform/audio/transport/MicrophoneReceiverPriorityTest.kt`:
  actual calling worker, SystemAudio unchanged, missing source, unavailable priority.
- `app/src/test/java/io/warpnect/platform/audio/MicrophoneCaptureTimingDebugProbeTest.kt`:
  three bounded/debug-only stage/CPU probe cases.
- `app/src/test/java/io/warpnect/platform/audio/AudioReceiveTimingDebugProbeTest.kt`:
  added bounded large-gap/sequence case; three existing realtime/monotonic,
  unavailable-diagnostics and bounded combined-window cases retained.
- `app/src/test/java/io/warpnect/input/transport/InputReceiverBridgeDecoderTest.kt`:
  four reserved all-devices/reset/key/normal-range correctness cases.
- `native/tests/audio_playback_ring_tests.cpp`: two one-burst admission/timeout
  and224-versus240-frame atomic-block cases; existing priming cases retained.
- Existing `DirectShellMicrophoneFrameTest`, `RemoteMicrophonePcmQueueTest`,
  `AsyncRemoteMicrophoneDestinationTest`, `DirectShellMicrophonePlaybackTest`,
  `AudioSessionControllerTest`, `PrivilegedRuntimeRegistryTest` cover consumer
  window/freshness/route-not-UID/expiration/generation/sink independence/failed
  replacement health. They do not fake Android devices or audibility.

Current docs updated: this report, DirectShellGlobalMicrophoneInjection,
DirectShellGlobalMicrophoneInjectionQuality, DirectShellMicrophoneInjectionQualification,
MicrophoneAudioE2EQualification, MicrophoneAudioVirtualInputArchitecture,
MicrophoneAudioCadenceRootCause, MicrophoneAudioCadenceAndVirtualInput,
CurrentNonRootDebt, README, FutureRoadmap, AndroidCompatibilityMatrix, and status
links in MicrophoneReceiverSchedulingQuality/MicrophoneSourceClockRecovery/
S9TabletPeerLinkRecovery. Earlier campaigns remain historical. RFC-006J deferred
status is inspected at start/end and not changed to imply implemented recovery.

## Final Classification And Remaining Scope

| Host/capability | Truthful state |
| --- | --- |
| Tablet/API33 global capture-preset injection | IMPLEMENTED / TESTED / FUNCTIONALLY VALIDATED; exact primary MIC quality3/3 VALIDATED |
| Tablet natural MIC/VOICE_COMMUNICATION/CAMCORDER | Physically VALIDATED, no forced preference |
| Tablet forced capture priority | DIRECTSHELL_REMOTE_MICROPHONE_PRIORITY_CONTROL_NOT_TESTED for valid set/clear/abnormal ownership |
| A41/API31 | GLOBAL_CAPTURE_PRESET_INJECTION_PERMISSION_BLOCKED at actual AudioService MODIFY_AUDIO_ROUTING enforcement |
| S7/API26 product | GLOBAL_CAPTURE_PRESET_INJECTION_ADAPTER_BLOCKED; bounded older-context probe reaches routing-permission denial, not primitive absence |
| Portability extension beyond Tablet | NOT VALIDATED; no new generic non-root alternative demonstrated |
| Release / all-app/input-route qualification | NOT PROMOTED / NOT QUALIFIED / NOT PRODUCTION_READY |

Retained `DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED` and historical
`MICROPHONE_AUDIO_CADENCE_AND_PLAYBACK_QUALITY_FIXED_AND_VALIDATED`.
New exact-path `DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_QUALITY_VALIDATED`.
No priority/portability-extension token. Real remaining risks: rare source/OS
stalls beyond reserve, initial route-activation transient, Android track/HAL
latency, absent crash-safe forced-preference ownership, unsupported Host
privileges, longer soak/clock drift and untested ordinary applications/routes.
Network initiator remains INCONCLUSIVE despite restored stable counted peer link.

Exactly ONE next recommended task: **SystemAudio jitter/playout causal analysis
and low-latency quality closure**. Do not automatically implement it; do not copy
the microphone reserve without its own measurement. Release promotion, forced
priority and RFC-006J recovery remain separately reviewed work.
