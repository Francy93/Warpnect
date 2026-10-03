# Microphone consumer clock continuity - 2026-10-03 continuation

Current classification: PARTIAL. The new frozen APK completes three consecutive
functional Sessions, but the third has recurring steady-state DEBUG and
RemoteSubmix AudioTrack underruns. A follow-up diagnostic then loses the Tablet
radio/Wireless Debugging prerequisite. No quality closure is claimed.
This report continues, and does not replace, `MicrophoneConsumerTransitionQuality.md`.
The functional milestone `DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED`
remains valid. No new quality, portability or forced-priority token is earned
by a technical tone result alone.

## Reconciliation and environment

Branch `codex/directshell-production-runtime-integration`, starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`, master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`, upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`. At resume: staged0,
unstaged255, collapsed untracked93. Existing source/test/docs/native/tooling
changes, generated evidence and the pre-existing empty `.git/index.lock`
are preserved. No commit, reset, cleanup, stash, merge, rebase or push.

Tablet X700_EEA/API33 serial `Z1382023HGM1895372` remains the primary Host;
S9 SM-G960F/API29 serial `23eaec0f12047ece` is the physical microphone Client.
A41/API31 is a network and permission control; S7/API26 receives a bounded
framework/permission probe, not an alternate product implementation. No S22 actions.
Tablet and A41 Wireless Debugging were manually restored by the user.

At resume, wlan0 addresses remain Tablet192.168.1.57/24, S9 192.168.1.4/24,
A41 192.168.1.36/24. S9 <-> Tablet ping3/3 in each direction and all eight
independent Java TCP/UDP token/echo peer tests pass. No router, security,
Wi-Fi reassociation or privileged Wireless Debugging change was performed
in this continuation. This demonstrates restored peer reachability; it does
not retroactively establish the precise cause of the prior radio failure.
Evidence prefix: `app/build/reports/global-window-oct03-network`.

The initial S9 reinstall waited on a Play Protect upload-to-Google question.
Back dismissed that question without uploading the APK or changing security
settings. Installation subsequently succeeded. A later abandon of the already
completed install session returned a SecurityException, preserved as a harness
cleanup attempt, not an installation/product failure. Subsequent installs
did not repeat that dialog. All device operations use exact serials.

## Preserved physical attempts

Ordinary consumer is the separate normal-UID10213 test application using
AudioRecord(MIC), no preferred device, no privileged read or Warpnect PCM access.
Policy uses capture-preset criteria only: UID RULES = NONE. The microphone
remains Client -> Host within the authenticated protected Session. Controlled
997Hz acoustic tones are emitted by S9 at moderate volume; Tablet DEBUG output
is muted to avoid acoustic feedback. Only numeric signal/timing data is saved.

Main APK `858A76A08C0F2395670308E2503A2D7DEFBCE6842E8A69B024349356F7A9F463`
is the previous exact candidate. One normal Enable Host at05:03:05.425UTC.

| Attempt | Streaming UTC | Duration | PCM enqueued | Written | Queue rejected | No-consumer drop | Stale drop | Window drop | HWM | Eligible dequeue max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 |05:03:48.854-05:05:37.570|108.716s|5411280|4346472|0|1046400|17448|960|15|38.842231ms|
| 2 |05:07:33.483-05:09:21.915|108.432s|5309520|4351920|480|945840|9600|2160|16|36.871077ms|

Expired-at-admission is a subset of stale:8400 and720 respectively, not an
additional drop count. Written + no-consumer + stale + window equals enqueued
exactly in both attempts. Rejected admission is separately accounted. Both
return Client Ready/Host Waiting, ordinary capture returns type15/id11, and
the next Session has a fresh generation/policy. Campaign stops after attempt2;
there is no attempt3 and no retroactive PASS.

Generation7488747972498870467 owns policy `1971008002:ap:71mixr:0` (type25/id1926);
generation2987888589411984190 owns ap:72mixr:0 (type25/id1956).
First active periods show no recurring stale/admission pressure after the
initial transition, but attempt2 admission480 occurs in a five-second interval
straddling consumer close. That imprecise old aggregate is not called benign.
DEBUG last snapshots9840/29/1 and480/7/0 (rejected/underrun/xrun) include cold
startup or close/reopen effects and may reset when the sink is reconstructed.
They are not a complete monotonic whole-Session loss total.

Ordinary consumer first/reopen RMS maxima:713.5446/654.6039 and602.0551/535.5295;
997Hz amplitude maxima1008.5697/925.2684 and851.0454/756.9445. RemoteSubmix
selection and physical restoration are demonstrated, not quality closure.

### Exact admission diagnostics

Bounded DEBUG logging records the first eight queue rejections with local
monotonic ns, frames, queue size and last completed feed response/time. It does
not change admission or PCM delivery and never logs content.

Diagnostic APK `62309A1A867147F1B8BE20A138D338872C91857A6CEDC077B4D879CD358F4000`:
one explicit108.561s reproduction,05:32:16.108-05:34:04.669UTC, generation
3665164000629638950, one ap:73mixr:0/type25/id1977.
Enqueued5531760, written4337832, rejected720, absent1177200, stale16488
(admission1920 subset), window240, HWM16, eligible dequeue max39.056231ms.
Balance of enqueued outcomes is exact.

Consumer active423073.475 local log seconds. Admission240 at423074.516 follows
feed64; another240 at423074.523 follows feed0, queue16. Reopen active423155.242;
admission240 at423155.811 follows feed240, queue16. These are active-consumer
events, not simply absence drops. AudioTrack partial/zero writes are real
nonblocking backpressure. Later continuous windows settle without new queue
rejection. First active stale10728 then flat, reopen adds5760 then flat.

The same-generation stream configuration remains1 while AAudio clocks are
closed/reopened repeatedly and decoder diagnostic windows reset. Framework
allocation/GC near consumer open is observed, but205.498ms GC elapsed is NOT
a205ms stop-the-world pause (reported pause13.207ms+105us). Do not attribute
the exact scheduling cause solely to GC or Wi-Fi.

## Decoder/output ownership defect and discriminating experiment

`DefaultAudioReceiverSessionController.resetForFreshFrameLocked` previously
retains a warm microphone clock only while `pcmFramesConsumed == 0`. Once any
PCM has been consumed, a large/misaligned source-position gap synchronously
recreates the entire output pipeline, even for the same generation/format and
healthy running output. This puts cold AAudio work on the decoder delivery path
and resets the observable sink counters. AudioPolicy ownership correctly stays
Session-local and is not reconstructed by these decoder resets.

Three focused tests cover same-generation running microphone freshness,
SystemAudio unchanged behavior, and explicit start-gate unchanged behavior.
Before correction, the valid class run has17 tests/1 failure: expected one
playback instance, actual two. Earlier NoTests/non-void JUnit initialization
failures are preserved TEST_HARNESS_DEFECT, not proof of a product regression.
The corrected class run passes17/17.

Candidate1 retains a healthy Running microphone clock rather than checking
whether it has ever consumed PCM. New config/generation still rebuilds and old
generation frames remain rejected. No ring/codec/socket change.
APK `1F6666D124B20C2467EB418EE88F395460C6359FF816653F32BCB4D237162E77`, one
Enable Host05:56:36.264UTC, one108.415s attempt05:57:08.442-05:58:56.857UTC:
enqueued5423280, written4339920, rejected0, absent1074480, stale7200,
window1680, HWM13, eligible dequeue max38.375385ms. Input delivery improves,
but DEBUG underrun/rejection still grows after discontinuities. No second or
third counted attempt. This alone is NOT quality closure.

The existing native priming gate is deliberately one-shot: ordinary underflow
remains visible. Preserving a clock across an explicit source freshness reset
also needs to rearm its existing reserve. Candidate2 marks that reset's first
decoded frame discontinuous and signals the callback through an atomic flag
only after a valid normal PCM write. The callback, not the producer, rearms
the existing70ms microphone reserve. It does not reset the stream, frame cursor,
loss counters, Session, generation, or AudioPolicy. PLC and normal starvation
do not automatically rearm it. SystemAudio's zero-threshold path is unchanged.
Native tests preserve the ordinary-underflow assertion and add explicit
discontinuity/reserve/SystemAudio behavior. The physical results below improve
clock continuity, but do not close the remaining recurring underflow.

## Frozen final candidate and three counted Sessions

Main APK SHA256:
`66A8B330C2DB74716122DBA0B8FB846E3F485C5ACBA5AB34ABDD2DA5E0B566C8`.
Identical installed APK hashes were verified on S9 and Tablet. Test APK SHA256:
`107D229EC519026440E5F9AF58DE7084F1926C720074C3C5E3AD94DBB8336A75`.
Normal Enable Host once at06:18:12.884UTC, SINGLE / DIRECT_SHELL for required
Video/SystemAudio/Input. No Host toggle, application restart, Wireless Debugging
toggle, hidden retry or helper relaunch between these three Sessions.

Each cycle has initial absence, ordinary MIC72s, close/absence about9.6s,
ordinary MIC reopen28s through Disconnect, then a separate physical MIC control.
All times below are UTC on2026-10-03; diagnostic timelines below use the Tablet's
local monotonic log clock, never another device's clock.

| Cycle | Streaming -> Disconnect requested | Duration | Generation | Policy mix / input ID | Ready / Waiting |
| --- | --- | --- | --- | --- | --- |
| 1 |06:19:12.294 ->06:21:00.855|108.562s|746333640696678431|ap:75mixr:0 /2031|06:21:14.406|
| 2 |06:22:11.566 ->06:23:59.891|108.325s|3730195589254727151|ap:76mixr:0 /2052|06:24:13.601|
| 3 |06:25:12.731 ->06:27:01.023|108.292s|1259673058500970633|ap:77mixr:0 /2073|06:27:14.633|

All three ordinary consumers select type25 REMOTE_SUBMIX naturally, without
setPreferredDevice or UID rules. Each reopened reader changes to type15/id11
after policy removal; the subsequent ordinary physical MIC control also selects
15/11. Built-in input remains available. Discovery and the next fresh generation
work under the same enabled Host. Policy lifetime is Session-local, not decoder-
local: one registration per generation, no competing/recreated policies.

### IPC outcomes and age

All values are PCM frames, except queue HWM, measured in packets. Admission
expiration is included in stale, not an additional outcome. Each row's written
+ absent + stale + pre-window exactly equals enqueued; admission rejection is
separate. This is an IPC accounting result, not proof of zero UDP loss.

| Cycle | Enqueued | Written | Unexpected admission rejected | No-consumer dropped | Stale dropped (admission subset) | Pre-consumer-window dropped | Queue HWM /16 | Eligible dequeue max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 |5418480|4351200|0|1056480|9600 (1680)|1200|13|37.867615ms|
| 2 |5298960|4339504|0|954480|3296 (720)|1680|12|38.119924ms|
| 3 |5290560|4343248|0|941520|5552 (1440)|240|14|37.715385ms|

After the bounded open/reopen transitions, sampled stale/rejection totals are
flat during continuous reader windows; queue occupancy drains and does not grow
without bound. No-consumer real PCM is discarded as soon as the helper observes
absence; maintenance silence, not unheard real speech, keeps the recorder mix
selectable. Observer delay is not zero: the generation-local consumer-window
guard rejects already queued real PCM predating the observed open/reopen.
Consumer close pauses/flushes/restarts the virtual track outside the feed lock.
The failed opening-flush and reduced-start-threshold experiments are NOT restored.

Capacity16 nominal240-frame packets is80ms of PCM, with a separate40ms freshness
ceiling at admission sweep, worker dequeue and helper write. Expired content can
remain physically allocated until the next bounded sweep, but is never admitted
for real playback merely because a consumer later appears. Partial writes keep
the original enqueue timestamp. The IPC policy adds no deliberate holding delay.
Measured eligibility and write age are local injection residence, not total
Client-acoustic-to-Host latency. Already accepted Android track/HAL buffering is
outside that local freshness ceiling and is not a proven40ms whole-system bound.

| Cycle | Helper eligible age max | Zero writes | Partial writes | Max nonblocking write call | Consumer-close framework transition | Track underruns |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 |39.351077ms|2501|672|5.800ms|44.191692ms|7|
| 2 |39.961846ms|4350|2301|10.464385ms|10.757770ms|5|
| 3 |39.451692ms|3404|800|3.258230ms|24.588384ms|9|

These helper values are the last five-second samples, not fabricated exact
release totals. A zero write is ordinary nonblocking backpressure/retry, not
automatically a dropped valid frame. The maximum call includes scheduler time.
Full residence p50/p95/p99, oldest allocated age, exact teardown/generation-drop
counts and final UDP loss/duplicate/reorder totals were NOT MEASURED separately.
No BAD_GENERATION was observed; unit tests cover rejection, not physical proof.
The S9 monotonic log collector produced no content despite remaining alive.
Read-only threadtime buffer recovery starts during cycle2; cycle1 sender history
is unavailable. This is a preserved observation-harness gap, not media failure.

### Physical OFF -> 997Hz ON -> OFF

The ordinary consumer is UID10213, uses public AudioRecord with48kHz mono PCM16,
and logs one-second numeric RMS/peak/997Hz correlation only. No direct PCM ring,
privileged read, UID criterion, preferred device or microphone recording artifact.
Tone comes acoustically from S9's speaker into the real S9 microphone, traverses
the protected Session, and reaches the Android consumer on type25. Tablet DEBUG
speaker is muted during the three-cycle campaign to avoid feedback.

| Cycle | First consumer RMS range | 997Hz component peak | Reopened consumer RMS peak /997Hz component | Restored physical route |
| --- | --- | --- | --- | --- |
| 1 |13.9403-565.9354|799.0633|554.5800 /783.2242|15/11|
| 2 |12.0259-545.4714|770.4951|530.0963 /749.1873|15/11|
| 3 |12.6330-515.7969|728.9584|468.6630 /661.4359|15/11|

Cycle1 local samples: OFF425895.676 RMS24.6648/component0.1166; ON425900.682
RMS565.9354/component799.0633/peak919; OFF425902.687 RMS25.1265/component0.4183.
Cycle3: OFF426254.896 RMS14.1918/component0.2063; ON426259.903 RMS515.7969/
component728.9584/peak797; OFF426262.906 RMS16.3877/component0.2318. This is
non-clipping physical signal evidence, not an external synchronized latency
measurement. Source-side current PCM RMS was not independently instrumented;
decoded PCM diagnostics also show tone energy (cycle1 RMS493.2919/component
608.276 in a five-second mixed window). A dominant-frequency FFT is NOT measured;
the fixture measures a997Hz correlation component, not a frequency search.
Each first/reopened reader has71/27 complete one-second windows with per-window
sample counts, generally about48000. No new human audibility confirmation.
Opening/reopening samples show live OFF/ON/OFF, not a retained old tone burst;
arbitrary speech replay was not tested or recorded.

### DEBUG and RemoteSubmix quality remain separate, both visible

| Cycle | Last DEBUG rejected / underrun / xrun | Ring HWM | Last decoded PCM frames | First continuous DEBUG delta after open+5s | Reopened continuous DEBUG delta after open+5s |
| --- | --- | ---: | ---: | --- | --- |
| 1 |12240 /60 /0|3840|5204640|0 /0|0 /0|
| 2 |240 /6 /0|3840|5274720|0 /0|0 /0|
| 3 |0 /25 /0|3808|5273520|0 /13|0 /0|

The five-second boundary sampling is explicitly approximate; no exact startup/
teardown aggregate is invented. Cycle1/2 rejection/underflow increments occur
before or around the first open and then remain flat through continuous/reopen
windows. Cycle3 DEBUG underrun is0 at426254.552,2 at426274.548,10 at426299.549,
13 at426319.549, then25 after reopening. The first continuous window's13
callbacks correspond to1872 missing frames filled with silence. This is NOT
classified as startup-only. Ring occupancy falls from roughly3300 to1200-1800
frames; decoded five-second windows include238560-frame periods rather than
exact240000. That establishes missing/late local supply, not its exact upstream
cause. No output clock reconstruction occurs during those continuous windows.

RemoteSubmix AudioTrack independently reports0 at426254.918,3 at426274.925,
5 at426299.936,7 at426309.941 and9 at426319.946, all before consumer closes
at426321.915. Its real-PCM stale/zero/partial-write counters are flat over that
interval. Thus the candidate solves observed admission rejection/cold output
reconstruction, but still does not prove continuous Android-input quality.
AudioTrack underruns and DEBUG ring underruns are different counter owners;
xrun0 is the AAudio platform counter, not proof that either application buffer
never starved. No larger ring, codec, socket buffer or packet-duration experiment
was used to hide this residual defect.

An initial reading of IPC rejection0 and the first two steady DEBUG windows
suggested a pass. The later full timeline audit invalidates that interpretation.
All three attempts remain visible; functional3/3, quality PARTIAL, no fourth
counted cycle or retroactive substitution. Non-mutating permission/preset probes
were performed before this deeper audit; they are boundary evidence only, not
quality-gated feature advancement or a priority implementation.

## Bounded framework, portability and priority evidence

An ignored app_process probe uses real UID2000/com.android.shell and the same
capture-preset recorder/LOOP_BACK formulation. It has no PCM feed, UID rule,
valid preferred-device assignment, model branch or generic service endpoint.
It is not a production adapter. An initial zero-channel-mask AudioFormat was
invalid (TEST_HARNESS_DEFECT); corrected explicit48kHz mono PCM16 reproductions
are preserved. S7's first malformed-probe process exits137 after attempted
unregistered-policy cleanup, exact kill actor INCONCLUSIVE; corrected probe
only unregisters successful registration and completes normally.

| Host | MODIFY_AUDIO_ROUTING /CAPTURE_AUDIO_OUTPUT /RECORD_AUDIO /MODIFY_AUDIO_SETTINGS | Actual registration | Priority boundary |
| --- | --- | --- | --- |
| Tablet API33 |granted /granted /granted /granted|0, clean unregister|invalid-preset setter-2, clearer1; no SecurityException; valid preferences remain empty|
| A41 API31 |denied /granted /granted /granted|-1, no policy|getter/setter/clearer throw Missing MODIFY_AUDIO_ROUTING permission|
| S7 API26 |denied /denied /granted /granted|-1, no policy|not tested|

A41's context check is no longer the only observation: bypassing that early
check for this bounded probe still returns framework registration-1. AOSP
AudioService requires MODIFY_AUDIO_ROUTING for recorder LOOP_BACK policies;
CAPTURE_AUDIO_OUTPUT or MediaProjection playback capture does not supply an
equivalent global recorder-injection authorization. No general equivalent
non-root path was found; no OEM workaround or silent Shizuku was attempted.
The OEM's precise internal registration-denial log is unavailable, so the
framework enforcement mapping combines runtime failure with AOSP source, not
a claim to have instrumented Samsung's service internals.

S7's legacy ActivityThread systemMain -> system context -> shell package context
works under actual UID2000 and constructs the recorder mix. AudioService logs:
registration for pid16524/uid2000 needs MODIFY_AUDIO_ROUTING. The product's
API_LT_31_CONTEXT_UNSUPPORTED remains its present adapter boundary, but removing
that alone cannot qualify this build: a real permission boundary follows it.
This is not proof that Android8 lacks recorder-mix primitives.

Tablet priority probe passes actual setter/clearer Binder permission gates using
invalid capture preset-1: AOSP rejects the source before role-map mutation.
MIC1/VOICE7/CAM5 preferences are empty before/after. This establishes authorization
only, NOT valid route assignment, automatic priority, or crash-safe cleanup.
No forced priority was set. Global injection, natural selection and forced
priority remain orthogonal. Classification: priority PARTIAL authorization-only;
forced priority behavior/normal/helper-loss rollback NOT TESTED. Natural routing
already works, so no demonstrated need justifies adding persistent role state.

Authoritative framework boundaries inspected (small paraphrases, not vendor code):

- [Android12 AudioManager](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/media/java/android/media/AudioManager.java): registration maps service rejection to ERROR; another Java entry point does not grant authority.
- [Android12 AudioService](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/audio/AudioService.java): recorder-policy routing permission; projection exception is playback render/loopback, not recorder injection.
- [Android13 AudioService](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/audio/AudioService.java): setters and clearers enforce routing permission independently of getters.
- [Android13 EngineBase](https://android.googlesource.com/platform/frameworks/av/+/android-13.0.0_r1/services/audiopolicy/engine/common/src/EngineBase.cpp): capture-source validation precedes role mutation; roles are not an owner-bound policy lease.

No additional product-time setup is introduced beyond existing DirectShell
pairing/Wireless Debugging and DEBUG microphone opt-in/normal permission intent.
Normal app consumers require no configuration. Release behavior is unchanged.

## Preset, security, lifecycle and shared regressions

Same66A8B330 APK natural-routing spot Session06:43:09.931-06:43:56.906UTC,
46.975s. Separate18s ordinary consumers for source7 and5 naturally select
type25/id2107. VOICE_COMMUNICATION RMSmax500.6899/component706.7688;
CAMCORDER RMSmax500.8965/component702.9374. MIC afterward selects15/11.
Ready/Waiting06:44:07.159. No per-UID or forced preference; policy criteria
are exactly MIC1, VOICE_COMMUNICATION7, CAMCORDER5, UID RULES = NONE. Historical
functional global-input token remains valid; short spots are not quality soaks.

One bounded SystemAudio Tablet ->S9 control, Streaming06:40:50.406UTC,
approximately20s wait plus UI teardown, Ready/Waiting06:41:25.228. Last Client
snapshot decoded1171440 frames, rejected20160/underrun217/xrun0. Controlled8s
Host tone; existing stereo960-frame ring/threshold1 unchanged. Technical and
lifecycle PASS only: unequal prior windows cannot prove quality improvement or
worsening. Historical REMOTE_ONLY human evidence is preserved, not re-earned.
Video reaches Streaming with decoded/surface-release progress; Input channel
starts/reset/stops without reported injection errors. No deliberate human input
event or full matrix is claimed. No protocol/security/plan change.

Intentional separate audio-helper SIGKILL at06:46:29.799UTC, PID6344,
generation5846017331051673910/ap:80. At local427518.616 owner state LOST and
HOST_PUBLICATION WITHDRAWN reason DIRECTSHELL_OWNER_LOST, migration=false.
At06:46:33.329 helpers0; ordinary reader changes25/2125 ->15/11, subsequent
physical capture works. Owned policy mixes and temporary secrets are gone.
Client failure is expected for this abnormal test, not a normal-cycle PASS.
No automatic restart/provider migration occurs. No preferred role existed,
so this does NOT validate forced-preference cleanup after SIGKILL.
Previously tested failed-replacement Wireless Debugging branch is preserved;
the next diagnostic provides another typed-prerequisite withdrawal below.

## Follow-up diagnostic and externally imposed boundary

After observing recurring continuous underflow, one explicit diagnostic starts
at06:54:21.009UTC to inspect active AudioFlinger/submix frame depth before any
new buffer change. UI never reaches its required Streaming observation; it
remains a FAILED attempt, despite native media briefly advancing. Generation
926762157882085958/ap:81 is SUPPORTED, then microphone receive stops advancing
at566 frames while DEBUG underflow grows. Input ReceiverFailure is observed
at428016.175. No ordinary consumer/pressure measurement is completed.

Subsequent trustworthy platform sequence on the SAME Tablet clock:

| Local log seconds | Observation |
| --- | --- |
|428022.243|Wi-Fi HAL NL80211_CMD_DISCONNECT|
|428022.255|wlan0 locally generated disconnect reason5, BSSID6c:99:61:16:23:65|
|428022.292|ConnectivityService CONNECTED ->DISCONNECTED|
|428022.302-304|Bluetooth service death; process16652 exits signal9|
|428022.314|AdbDebuggingManager: Network disconnected. Disabling adbwifi.|
|428022.326|adbd TLS enable becomes0; adb wifi stopped|
|428022.505|replacement preparation FEATURE_PCMSHAREDRING_WIRELESS_DEBUGGING FAIL, WirelessDebuggingDisabledException|
|428022.611|Host publication WITHDRAWN, DIRECTSHELL_OWNER_LOST, migration=false|

The Wi-Fi/Bluetooth initiating actor and the earlier media stall's exact cause
remain INCONCLUSIVE. Do not claim Bluetooth death caused Wi-Fi: Wi-Fi's logged
disconnect precedes that death. The later loss of the DirectShell prerequisite
is directly proven EXTERNAL_PLATFORM_OR_PROVIDER_FAILURE / ENVIRONMENTAL_PRECONDITION.
App PID3909 survives, helpers0, Host no longer Available. Wi-Fi subsequently
reconnects at192.168.1.57; adb_wifi_enabled remains0. No privileged setting toggle
or automatic recovery is introduced. Manual prerequisite restoration is requested.
Final read-only peer ping2/2 passes in both directions; RTT remains variable
(Tablet ->S9 roughly59-77ms, S9 ->Tablet8-108ms). The current blocker is the
disabled authenticated DirectShell prerequisite, not a claim that ICMP is still
blocked. No new TCP/UDP acceptance is inferred from that final ICMP spot.
The failed AudioFlinger dump contains only removed/historical submix output,
not an active-path clock-depth proof. No candidate correction is based on it.

Nearest PASS is the same APK's three-cycle functional campaign; nearest quality
failure is cycle3's continuous underflow. The new failure does not erase either.
Acceptance remains PARTIAL, not an A41 substitute or hidden retry.

## Tests, gates and review boundary

New focused cases in this continuation:

- AudioSessionControllerTest.runningMicrophoneGapResetsDecoderWithoutRestartingOutputClock
- AudioSessionControllerTest.warmSystemAudioGapStillRecreatesPipeline
- AudioSessionControllerTest.gatedMicrophoneGapRetainsExistingFreshnessRebuild
- native audio_playback_ring_tests: explicit_discontinuity_rearms_reserve_without_hiding_ordinary_underflow

Existing ordinary-underflow, zero-threshold SystemAudio, frame accounting,
generation/consumer-window/freshness and lifecycle tests remain. No test fabricates
physical audibility or Android permission success.

All requested source gates PASS on final66A8B330:
git diff --check; :app:ktlintCheck; :app:lintDebug;
:app:testDebugUnitTest --rerun-tasks; :app:assembleDebug;
:app:assembleDebugAndroidTest; :app:packageDirectShellProbeDex.
Exact JVM1007 tests/161 suites,0 failures/errors/skips;100 executed Gradle tasks.
Native Debug CTest22/22 (4.38s), Release22/22 (5.47s), rebuilt with worker1.
The initial new-source ktlint blank-line failure is preserved and corrected;
earlier expected pre-fix regression and malformed test/probe harness attempts
remain beside final PASS outputs. No product source changes after these gates.

New continuation scope: bounded DEBUG admission logging; Kotlin decoder/healthy
output ownership correction; native callback discontinuity re-priming; three
JVM cases and one native case; current documentation. Inherited microphone/
transport/runtime diagnostics remain mixed in the large uncommitted worktree.
Ignored evidence/tools/probe outputs are not checkpoint material. Previously
unrelated screenshots/XML/crash logs/attachments, tracked generated native
outputs and the pre-existing index.lock remain preserved. No commit is created
because the physical quality milestone remains partial, not merely because a
large diff exists. Starting and ending HEAD remain715b215.

Final `git status --porcelain` groups: source/debug34 modified +20 untracked,
tests5 modified +14 untracked, docs6 modified +9 untracked, generated-native210
modified, pre-existing unrelated artifacts/attachments51 untracked entries.
These are directory-collapsed status entries, not a count of files recursively
inside artifacts. `git diff --name-only` lists253 content changes; status lists
255 tracked entries, including generated status differences. Staging0;
`git ls-files --others --exclude-standard --directory` lists103 entries under
its own directory-collapsing rules. No entries are removed to reconcile those
different enumeration definitions. Ending HEAD/master/upstream are unchanged;
the empty index.lock's timestamp remains2026-09-30T18:21:31.1226059Z.

Continuation documentation updated:

- docs/phase6_5/MicrophoneConsumerClockContinuity.md
- docs/phase6_5/DirectShellGlobalMicrophoneInjection.md
- docs/phase6_5/DirectShellGlobalMicrophoneInjectionQuality.md
- docs/phase6_5/DirectShellMicrophoneInjectionQualification.md
- docs/phase6_5/MicrophoneAudioE2EQualification.md
- docs/phase6_5/MicrophoneAudioVirtualInputArchitecture.md
- docs/phase6_5/MicrophoneAudioCadenceRootCause.md
- docs/phase6_5/CurrentNonRootDebt.md
- docs/phase6_5/README.md
- docs/FutureRoadmap.md
- docs/testing/AndroidCompatibilityMatrix.md
- docs/phase6_5/S9TabletPeerLinkRecovery.md

Cleanup is outside acceptance counting: the failed Client process is explicitly
closed/reopened into Ready, Tablet test consumer is closed, Tablet already Ready/
Host disabled. Client RECORD_AUDIO AppOps reports a completed6s diagnostic access,
not ongoing capture. Final helper count0/secrets0; Audio Policy Mix list empty,
Audio sources0, capture-preset role list empty. Baseline platform RemoteSubmix
port@0 remains, not a leaked Warpnect mix. Owned temporary probe jar/DEX is
removed from each probed device; local evidence remains. Verified collectors
are stopped. Volumes restored Tablet2/15, S9 15/15; no subsequent tone emitted.
No global FD/resource leak-freedom claim.

Fixed40ms IPC freshness: retained PROVISIONALLY, not newly qualified. The prior
80ms candidate was superseded before this continuation; this task does not
reintroduce it. Adaptive age: NOT NEEDED FOR THIS INVESTIGATION / NOT IMPLEMENTED,
no equivalent-run A/B benefit is proven, and adaptation cannot explain or cure
the continuous-underflow boundary. Exact Android injection latency still needs
valid active-track depth/cadence measurements; local eligible residence above
is bounded, not an added fixed delay. DEBUG80/70 reserve and source pacer remain
separate, declared costs from the accepted cadence report.

Overall: global injection IMPLEMENTED/TESTED/FUNCTIONALLY VALIDATED on exact
Tablet path; quality PARTIAL; broader QUALIFIED and PRODUCTION_READY are NOT
claimed. A41 permission blocked; S7 product-adapter blocked with an additional
permission barrier demonstrated; priority authorization-only PARTIAL, forced
behavior NOT TESTED. Preserve DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED;
do not emit global-quality or forced-priority completion tokens.

Exactly one next task: continue the shared decoded-PCM/RemoteSubmix AudioTrack
continuous-underflow diagnosis on S9 ->Tablet after manual Wireless Debugging
restoration, retaining the frozen buffer/codec contracts until a causal boundary
supports a correction. SystemAudio quality redesign is still a later task.

## Frozen contracts and remaining scope

Microphone DEBUG ring3840frames/80ms, reserve3360frames/70ms, 48kHz mono PCM16,
240frames/5ms Opus, pacer and socket buffers remain unchanged. Explicit freshness
re-priming reuses the same reserve, not additional capacity. RemoteSubmix local
IPC stale limit remains40ms from the preceding investigation, not80/70 playout.
No adaptive age algorithm, priority role or alternative provider was introduced.
No-consumer real PCM is discarded; only maintenance silence keeps the recorder
mix selectable. Consumer-window guard prevents pre-open queued real PCM writes.
The age metric is local IPC residence, not synchronized capture-to-speaker delay.

Product portability/forced-priority implementation remains gated behind quality.
The boundary-only probes and natural-preset spots above add precise evidence,
not a second product architecture or a release capability promise.

Required Host stays Video + SystemAudio + Input; microphone is optional.
RFC-006J recovery/remediation/live migration remain NOT IMPLEMENTED. Existing
pre-Session composite planning does not authorize replacing a frozen active plan.
No Root, hidden Shizuku fallback, generic privileged control, OS modification,
display policy, RFC-005J/K or SystemAudio quality redesign.

Evidence, all excluded from checkpoint, is under `app/build/reports/` prefixes
`global-window-oct03`, `global-window-oct03-reject`, `global-window-oct03-clock`
and `global-window-oct03-reprime`. No user speech/raw PCM is persisted.
