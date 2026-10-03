# Global Microphone Receiver Scheduling - 2026-10-03

> Current status is superseded by [final exact-path quality closure](DirectShellGlobalMicrophoneFinalClosure.md): C782DD9D r2 primary3/3 PASS. This report preserves earlier scheduling/admission hypotheses and failed campaigns, not current release/priority qualification.

## Current Classification

PARTIAL:43FB71D6 r1/r2 lose the environmental prerequisite; r3 exposes a video
readiness/harness distinction; r4 completes microphone lifecycle but fails
quality. Every failed campaign stops counting; cycles2/3 NOT RUN. Preserve DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED
and the historical exact-pair DEBUG cadence token. Neither functional signal
delivery nor one stable diagnostic is a new quality closure.

## Residual Quality Investigation

After the documented local Wi-Fi reassociation and manual Debug wireless
restoration, r3 remains active but the Client video stays WaitingForConfig.
SecureSessionScreen's Streaming label depends on video, not microphone. The
runner's readiness check was corrected to require a fresh authenticated
microphone generation and actual MicrophoneAudio Streaming telemetry. This
does not fix or hide the separate video anomaly. No media/protocol/UI change.

43FB71D6 r4: one Enable13:11:26.670379UTC, generation6430610833400954388.
MIC consumer72s, absent6s, reopen28s, normal Disconnect and physical MIC
restoration succeed. First active steady59.996s: DEBUG rejection0/underrun+1/
xrun0, AudioTrack underrun+2. Reopen steady10s: DEBUG rejection+240/underrun0/
xrun0, AudioTrack0. IPC steady rejection/stale/pre-window deltas0 in both.
This is a real quality FAIL, not an environmental substitute or3/3 pass.

Release5331120 submitted,4335120 written,967200 no-consumer,27120 stale
(10800 admission-expired is included),1680 pre-window, unexpected rejection0;
balance0, HWM15, sampled active queue0..2, dequeue max53.200385ms, eligible
max38.450154ms, p50<1/p95<3/p99<20ms. Ordinary consumer RMS8.523->2199.750,
997Hz component3110.288; reopen10.290->2286.864/component3233.544. Human
audibility NOT CONFIRMED. No voice or raw PCM persisted.

Same43FB71D6 diagnostic rx-residual-trace-r1, generation8506353047199515817,
is stable: first60.002s and reopen10s all steady DEBUG/AudioTrack/IPC quality
deltas0. It is diagnostic only and does not replace the failed campaign.

Bounded observer925727BB adds48 receive-gap samples and separate startup/late
DEBUG event budgets. It changes no codec/ring/socket/pacing behavior. Its
diagnostic cycle7849569525948109863 reproduces one steady240-frame ring-full
rejection, no steady underrun/xrun/IPC loss. Full event459293.466662141 local
monotonic seconds, position3439200. Perfetto: receiver runs459293.465716
through459293.467354, callback runs459293.466490 through459293.466585; callback
has regular~5.33ms wakeups and~0.02ms runnable delay. No long callback stall.
After concurrent256-frame consumption, logged occupancy3360 implies3616
before it:224 free frames for a240-frame atomic block. Only16 frames short,
yet all240 were rejected. This establishes a separate frame-quantization/
callback-phase admission defect; it is not a reason to enlarge the ring.
Trace ftrace overruns0. Socket ages in that5s window max7.647462ms; sequence
missing/duplicates/reordering0. It does not explain every prior underrun.

Candidate81F10D24 adds microphone-only, running-output admission wait before
the actual ring submit. The bound is one actual output burst (256/48000=
5.333333ms on Tablet), checked in at-most250us intervals. Fitting blocks,
invalid sizes, unknown burst and SystemAudio do not wait. Callback stalls
still expire and preserve the real ring-full counter; no drop suppression,
new PCM queue or capacity change. A callback-phase coincidence can now admit
the intact240-frame block after space becomes available. OS scheduling can
overshoot the requested sleep; this is not a hard real-time latency promise.
Added native deterministic cases cover16-frame shortage/next callback/zero
loss and expiry/already-fitting/invalid/backward-time/unknown-burst bounds.
Debug/Release CTest22/22 each pass; final full software gates in progress.
81F10D24 diagnostic1971269393643673118 passes first59.999s and reopen10s,
all steady quality deltas0. Release5346480 submitted,4343040 written,
983280 no-consumer,19440 stale (9600 admission included),720 pre-window,
rejection0/balance0/HWM13; p50<10/p95<20/p99<30ms. This is not3/3 acceptance.

New final81F10D24 r1 Enable15:53:18.404009UTC, cycle1 generation136466498362292517,
fails quality;2/3 NOT RUN. First59.999s: DEBUG0/0/0, track underrun+3.
Reopen9.999s: DEBUG rejected0/underrun+7/xrun0, track+13. IPC steady loss0
through both periods. Release5364480 submitted,4348560 written,1008000
no-consumer,7920 stale (1200 admission included),pre-window0, rejection0,
balance0/HWM9, eligible max37.899616ms; p50<1/p95<3/p99<20ms. Consumer
RMS38.118->4692.449/tone6634.075, reopen45.178->4759.481/tone6728.735.
Both ordinary input/lifecycle/restoration remain functional, no quality token.

This failure separates causes: whole-block ring rejection disappears, but
starvation remains. Receiver window21: socket age max13.689308ms, listener
max3.401154ms, sequence-continuous kernel enqueue gap83.510462ms and nominal
phase excursion206.331007ms. Matching source frame positions: sender encoder
input max68.873192ms and protected-send max83.009731ms, phase208.547883ms.
Do not subtract these devices' timestamps or call socket enqueue RF arrival.
The reopened starvation has a real sender-side irregularity too, not merely
slow Host authentication/IPC. Exact sender stall mechanism remains under
investigation; this is not proof that the ring needs to be enlarged.

S9 Perfetto service unavailable. Standard shell atrace16MiB-per-CPU allocation
fails ENOMEM; bounded2MiB and4MiB-per-CPU tracing succeeds, no Root/property
override. First dual trace captures mainly post-stop due ring overwrite and
does not prove the earlier stall. Diagnostic7745576586800234650 passes steady
quality, no failed result overwritten. Collection now snapshots before
Disconnect too. Trace observer effect and initiating sender cause unresolved.

All software gates PASS:1017 JVM tests/162 suites, failures/errors/skips0;
Debug CTest22/22(3.84s), Release22/22. Full Gradle14m38s. Fully rerun assembly
hash C15ADA63B45F2DAE6345E835A9784FB80A04D92A3C17A5FFEA6399FE626BB058 differs
from the preserved incremental frozen81F10D24 artifact; no claim that they are
the same APK. No further production source changes after that gate; additional
native test refinement reproduces224-frame partial consumption explicitly.
Physical final quality remains PARTIAL; no checkpoint.

This continues [source-clock recovery](MicrophoneSourceClockRecovery.md), not a
new microphone architecture. The counted D28F6136 cycle remains FAIL; cycles2/3
were not run. The later388C3A75 stable continuous-reader diagnostic and subsequent
radio/media failure remain historical evidence.

## Repository And Environment

Branch codex/directshell-production-runtime-integration; start HEAD
715b2151af2c8ecac165729178266b24e5cae91b; master
032b59276a0e01e4674e807a8ffb5b5b86e9150e; upstream
9d0b4dce7ea04a82e81778de6296cc1096775d31, ahead19. Staging empty.
Historical92347d0/809b7dd/715b215 verified. Large inherited source/test/doc/native
output worktree preserved, including unrelated evidence and index.lock.

Primary: S9 serial23eaec0f12047ece/API29/SM-G960F microphone to Tablet
Z1382023HGM1895372/API33/X700_EEA. No A41/S22 substitute, Root or Shizuku.
User restored Tablet Debug wireless; verified1. Direct peer ping3/3 each way
passes, unchanged192.168.1.4 and192.168.1.57/24. Prior bidirectional TCP controls
remain preserved. No router/security modification or product Wi-Fi recovery.

C: became full after the preceding gates. The incomplete pulled388 APK is
preserved on D: as failed-partial-physical-388C3A75.apk; the full physical APK
and gatedBB60 APK were subsequently archived there. One log generated by this
continuation, global-clock-oct03-pacer-tablet.log, was copied to D: and hash-
verified before removing only its C: duplicate (210778681bytes, SHA256
E4268F5917B66E94BC40419FB8CFA33D1869A8C68FCD79F49B79829C523F7751).
New evidence resides under D:/WarpnectDiagnostics/phase6_5/2026-10-03, excluded
from commit. No prior unrelated evidence was deleted. No voice/PCM recording.

## Hypotheses And Discriminating Runs

BB60AEA0F027B3990B15AB37ABF41522ABAB969C409E5F7108C017859D7DB159 is the fully
gated source-clock/extended-observer candidate:1012 JVM tests,161 suites;
Debug/Release CTest22/22 each. Installed identically on S9/Tablet.

1. A long IPC call may be an AudioTrack/Binder write stall. Scheduler/Binder/
   ART tracing instead places the46.467ms call alongside a50.050ms app GC
   SweepSystemWeaks interval. App IPC worker sleeps48.094ms and helper main
   sleeps47.964ms waiting for the next request. Helper feed/write maxima are
   much smaller. This is not proof that every long RPC has this cause.
2. DataInputStream may read the length prefix byte-by-byte. Exact Android13
   libcore source explicitly uses readFully(readBuffer,0,4) in readInt. Reject
   that hypothesis; no protocol/parser/crypto rewrite or buffered read-ahead.
3. Source pacing is now bounded, but local receiver scheduling may prevent
   draining the socket. A fresh BB60 trace confirms receiver runnable wait
  33913.439ms over approximately111s, maximum136.730ms. Socket enqueue-to-read
   age grows to931.916ms; listener maxima106.881/137.804ms and repeated codec
   freshness reconstruction occur. A41/Tablet network identity is not used as
   a product branch. Kernel timestamps refer to socket-enqueue timestamps of
   observed packets, not RF arrival or an independent complete packet capture.
4. Apply Android audio priority only to the MicrophoneAudio receiver worker;
   retain all other settings. A physical trace verifies nice-16/sched priority104
   and foreground cpuset. Runnable wait becomes2469.018ms, maximum24.286ms;
   this run has zero observed steady quality deltas in both consumer windows.
   This is preliminary causal evidence, not universal scheduling qualification.

ART weak-reference blocking is consistent with the
[AOSP concurrent-copying collector](https://android.googlesource.com/platform/art/+/c5288040c3/runtime/gc/collector/concurrent_copying.cc).
The trace shows timing/state correlation; it does not identify an exact Java
allocation stack or prove every concurrent GC interval is stop-the-world.
[Perfetto CLI analysis](https://perfetto.dev/docs/getting-started/command-line-analysis)
is used locally; Windows binary SHA verified against its official manifest.

## Diagnostic Results

Every row is diagnostic, NOT counted final acceptance.

| Run | Reader steady window | DEBUG rejected/underrun/xrun delta | Track underrun delta | IPC rejection/stale delta | Lifecycle |
| --- | --- | --- | ---: | --- | --- |
| BB60 scheduler-r2, gen612447732673566331 |59.995s first /4.987s reopened |240/0/0 first;240/0/0 reopened |0 /0 |0/0 both |Ready/Waiting;physical MIC restored |
| BB60 scheduler-r3, gen7575949004766140340 |65.002s first /4.997s reopened |7440/47/0 first;0/0/0 reopened |28 /71 |0/23040 first;3840/45360 reopened |Ready/Waiting;quality FAIL |
|49D62210 priority-r1, gen2493229794637665082 |60.005s first /4.999s reopened |0/0/0 both |0 /0 |0/0 both |Ready/Waiting;physical MIC restored |

BB60-r2 release: submitted9100560, written4327440, absent4758000,
stale13440, pre-window1680, unexpected admission rejection0; balance0;
HWM16; eligible admission age38.177ms, maximum dequeue age66.771ms;
dequeue histogram p50<1ms/p95<3ms/p99<20ms. IPC retries/partial writes are
counted, not relabeled as packet loss. Tone volume15 produced a very large
signal; it is not the non-clipping acceptance source.

BB60-r3 release: submitted5303520, written4110240, absent860640,
stale332160 (admission107040 included), pre-window480, unexpected rejection17280;
balance0; HWM16; eligible38.695ms/dequeue65.794ms;
p50<1ms/p95<40ms/p99<80ms. Source encoder nominal phase remains bounded while
receiver queue age/sequence gaps and scheduling delay grow. Do not infer that
all missing observed sequences were lost over RF; /proc/net/udp is denied even
to the normal debug app identity. No Root used to bypass that boundary.

Priority diagnostic APK SHA256
49D622104E23FADB9305279F60DE5C9B6415DF6713ABD2EC522525A1B53A30FC.
Release: submitted6162000, written4345200, absent1804080, stale9840
(admission1440 included), pre-window2880, unexpected rejection0, balance0;
HWM13; admission36.684ms/dequeue45.240ms;
p50<1ms/p95<3ms/p99<20ms. DEBUG startup rejected9360/underrun46/xrun0 then flat;
track14 during opening and20 after reopening, flat in observed steady periods.
Ordinary consumer naturally routes to type25, no preferred device or UID rule;
RMS17.488->3551.343->baseline,997Hz component5021.105 at volume7/15.
Reopen RMS16.107->3222.299,997Hz4556.037. These are numeric acoustic results,
not a new human-audible DEBUG-speaker claim or a dominant-frequency search.

Trace setup-r1 failed because the perfetto identity cannot read a shell file in
/data/local/tmp. Configuration via stdin works on the SAME already-connected
Session; no Connect retry. Preserve TEST_HARNESS_DEFECT. First trace lacked a
periodic-flush setting, causing an analysis memory warning; corrected on later
traces, not audio behavior. Priority-r1 initial uiautomator returned null root;
its old XML was then read. That UI precondition is not counted acceptance;
runtime/audio evidence remains useful. The final harness rejects unsuccessful
dump output and stale XML. No hidden retry.

## Minimal Candidate And Frozen Contracts

NativeSclAudioReceiverController requests the existing Android audio-thread
priority helper on its actual worker only when receiver source=MicrophoneAudio.
No scheduling change for SystemAudio, no provider/planner/Session plan change,
no shell-helper priority change, no foreground service/background redesign.
The request may fail truthfully; it cannot fabricate hardware quality.

Retain pacer correction,80ms/3840frame ring,70ms/3360frame reserve,48kHz mono
PCM16,240frame/5ms Opus,bitrate/socket settings. No buffer enlargement or new
fixed delay. Existing HAL batching may still imply up to15ms redistribution.
Keep provisional40ms authenticated IPC freshness separate from playout reserve.
No adaptive stale-age algorithm is introduced before final quality closure.
Session-owned policy, pre-consumer temporal guard, maintenance silence and
separate DEBUG/product sink ownership remain unchanged.

## Gates And Final Acceptance

Four focused tests in MicrophoneReceiverPriorityTest cover:
microphonePriorityIsAppliedOnTheCallingWorkerOnly;
systemAudioRetainsItsExistingSchedulingPolicy;
missingSourceDoesNotAcquireMicrophoneScheduling;
unavailablePriorityRemainsAReportedFailureInsteadOfFalseSuccess.
Targeted4/4 and assembly/probe DEX pass. The first full gate fails on formatting
of the new short function signature; only formatting corrected.

Full rerun passes14m1s,100 tasks executed: ktlintCheck, lintDebug,
testDebugUnitTest --rerun-tasks, assembleDebug, assembleDebugAndroidTest and
packageDirectShellProbeDex. XML count1016 tests/162 suites/zero failures,
errors or skipped. git diff --check exits0 (CRLF warnings only).
Native CTest Debug22/22 in3.99s and Release22/22 in6.01s. No new native production
edit in this resumed segment; inherited native audio changes still required
these checks. Tool logs are on D:, not committed.

## Final Frozen APK Attempt

Final SHA25643FB71D6F50D32C00925BCA1CA1C40CA37647ACA79B79D1C6EFB228950412075;
identical installs on S9 and Tablet. Test APK SHA256
107D229EC519026440E5F9AF58DE7084F1926C720074C3C5E3AD94DBB8336A75.
APK snapshot mic-rx-priority-final-gated.apk on D: is hash-verified.

Initial runner setup returns null UI root BEFORE Enable Host. Preserve
TEST_HARNESS_DEFECT, zero Session attempts. Add3s Activity stabilization,
retain strict dump-success checking; no Connect retry or APK change.

| Counted cycle | Enable / Streaming UTC | Consumer / outcome | Result |
| --- | --- | --- | --- |
|1, generation4777285110114874359 | One Enable12:26:14.392535; Streaming observed12:26:42.297197 | Absent acoustic2s tone12:26:44.446267; consumer opens12:26:50.631182, but normal MIC routes physical15/id11, not submix; Session already loses peer communication | FAIL; no steady quality window |
|2 | Not attempted | Campaign stopped after1 | NOT RUN |
|3 | Not attempted | Campaign stopped after1 | NOT RUN |

Runner rx-final-43FB71D6-r1 stops with Streaming lost at12:28:30.865818 and
closes its collectors. It does not Connect again. The nominal runner elapsed
time is NOT counted as continuous Streaming duration. Do not interpret silence
or local acoustic pickup from physical15/id11 as remote input success.

Tablet local timeline: TDLS peer46:af:fe:01:ec:e9 creation447929.781, TPK M1
447930.809, observed microphone datagrams cease at591, then wlan0 locally
generated CTRL-EVENT-DISCONNECTED447940.892 reason7. Policy release447941.340;
bootstrap447941.422 fails WirelessDebuggingDisabledException; publication
WITHDRAWN447941.503 reason DIRECTSHELL_OWNER_LOST, migration=false. Readiness
USER_ACTION_REQUIRED447951.222 generation5/WIRELESS_DEBUGGING_DISABLED.
S9 local lifecycle_suspended2273728.100 stops capture and sends InputResetState.
Do not subtract these separate device clocks. Communication/media loss precedes
the logged AP disconnection, so TDLS is a correlated hypothesis, not proven sole
initiator. Classification: initiating boundary INCONCLUSIVE; subsequent loss
of network/DirectShell prerequisite ENVIRONMENTAL_PRECONDITION, proven.

Release accounting: submitted140880, written0, absent140880; stale0,
pre-window0, admission rejection0; HWM2, max dequeue14.607308ms;
p50<1ms/p95<1ms/p99<5ms, balance0. There is no active-submix steady window.
DEBUG rejected9360 and underrun already1275 in an early post-suspension sample,
xrun0; receive count is flat. These are real starvation after source stops, not
accepted startup counters or evidence that steady quality is closed. Decoder
freshness reconstruction occurs during the failed startup; no false no-reset
claim. No new OFF/ON/OFF global-input proof on this final APK.

The failure itself revalidates truthful health invalidation after failed helper
replacement: Host Available withdrawn, all helpers authenticated-shutdown,
UI Ready / Host setup required: enable Wireless debugging. It does not prove
normal post-Disconnect Waiting. No automatic recovery/migration added.

## Bounded Environmental Discrimination

Direct peer ping immediately before the campaign3/3 each direction; same AP,
IPv4 addresses and SSID. After loss Android automatically reassociates with
the same AP, while Debug wireless remains0. No privileged setting re-enabled.
A single asynchronous manual-restoration request was made.

One-shot shell diagnostic calls the standard framework Binder equivalent of
WifiManager.setTdlsEnabledWithMacAddress(peer,false), on each endpoint. Both
requests are accepted; this is teardown of a peer link, not disabling Wi-Fi,
changing router/security, or proof that firmware will not negotiate again.
No Warpnect product TDLS branch, network recovery or new APK is introduced.
Source search finds no TDLS operation in app/src or native source.

A separate45s numeric UDP probe,200 datagrams/s,224bytes each, without Warpnect
Session/audio, sends9000. Its earlier-started45s receive window observes8837
consecutive units, missing0/duplicate0/reorder0. The163-unit difference includes
non-overlapping end windows, NOT proven packet loss. Max local send gap17.984ms,
receive gap74.095ms. This restores an independent direct-peer control but does
not prove the initiating TDLS/radio cause or fulfill microphone acceptance.
Probes are bounded diagnostics only, no listener left active, no generic
privileged production interface and no media sent.

## Freshness / Latency Decision

Actual retained IPC freshness is fixed40ms, not the old prompt80ms. No new
algorithm or budget change here. Adaptive policy NOT TESTED/QUALITY-GATED,
not claimed unnecessary from one stable diagnostic. Scheduler correction adds
no intentional queue/delay. Existing producer redistribution can reach15ms;
DEBUG80/70ms reserve remains separate. Diagnostic dequeues above40ms are
rejected/absent, not evidence that accepted PCM has an80ms budget. Consumer-
window guard and maintenance-silence policy are unchanged, but final repeated
open/reopen acceptance remains pending.

New SystemAudio/Video/Input physical regressions are NOT RUN because primary
acceptance loses its prerequisite first. Historical bounded controls remain
historical, not retested passes. All automated gates are green.
The preceding66A8B330 SystemAudio20s technical/lifecycle control reports
decoded1171440 frames, rejected20160/underrun217/xrun0; it does not verify this
final APK or close its separate quality debt.

### Fresh Campaign After User Restoration

User restores Debug wireless again; verified1 and peer ping3/3 both ways.
Same final43FB71D6 APK, no rebuild, new explicit campaign rx-final-43FB71D6-r2.
One Enable12:44:57.919156UTC; first Connect attempt only. Runner stops
12:45:28.321336 with Client terminal failure waiting for Streaming. No tone or
ordinary consumer starts. Cycles2/3 NOT RUN, no invisible Connect retry.

Tablet local runtime start449051.161; initial protected microphone decoding
progresses, UID NONE policy registration1971008002:ap:91mixr:0/generation
6021939270210350898. TDLS peer creation449052.475, TPK M1 449053.502;
lifecycle_suspended449055.587. Last sample datagrams671/decoder161040 frames,
DEBUG rejected0/underrun258/xrun0 after source ceases, HWM3600. Runtime briefly
streams, but the UI runner never observes Streaming and there is no accepted
steady window. No consumer/window/signal quality result. Release totals are
not captured before collector close; do not invent them.

Debug wireless subsequently reads0; independent peer pings recover2/2 both
ways and A41 control2/2 to each endpoint. This repeats failure BEFORE acoustic
tone, refuting tone as a necessary trigger. One-shot TDLS teardown did NOT
prevent the new handshake/failure. It does not prove driver/AP root cause.
Initiating mechanism remains INCONCLUSIVE; missing DirectShell prerequisite
is independently observed. Both campaigns remain FAIL.

One explicit bounded local Wi-Fi reconnect at12:51:57UTC uses cmd wifi
set-wifi-enabled disabled/enabled, not a product recovery change. Returns to
same saved SSID, BSSID6c:99:61:16:23:65,5320MHz, address192.168.1.57, MAC
5e:42:d8:d0:6d:ad, network3. No password/router/security setting changed.
Peer ping3/3 each way passes; Debug wireless remains0 and a manual restoration
request is issued. Effect on Session stability NOT YET VALIDATED.

### Capability And Test Boundaries

| Host / feature | Current scope |
| --- | --- |
| Tablet global input | Functional VALIDATED historically; final candidate quality PARTIAL |
| Tablet MIC/VOICE_COMMUNICATION/CAMCORDER natural selection | Earlier type25 remote signal retained; no new three-preset requalification |
| A41/API31 | Real shell AudioService registration/MODIFY_AUDIO_ROUTING permission blocked; no new alternative before quality gate |
| S7/API26 | Product adapter API_LT_31_CONTEXT_UNSUPPORTED; prior separate legacy context reaches registration but permission blocks it, not an absent Android8 primitive |
| Tablet forced priority | Earlier invalid-preset authorization-only PARTIAL; valid set/clear/helper-loss restoration NOT TESTED; no preference applied here |

No new API adapters or model/OEM branches, no new user setup beyond existing
DirectShell prerequisite. Priority stays orthogonal to useful natural routing.

Continuation tests also include the six MicrophoneChunkPacerTest frame/clock/
capacity/delivery cases and bounded probes:
AudioCadenceDebugProbeTest.extendedQualityObservationRemainsBoundedToTwentyFourWindows;
AudioReceiveTimingDebugProbeTest.extendedTimingAndKernelCadenceShareTheSameBoundedWindow.
No fabricated physical speaker or Android framework success.

After r1 S9 is explicitly restarted only after failure, no hidden Session retry.
Tablet helper/secret/policy-mix counts0; ordinary consumer stop events recorded.
One-shot diagnostic DEX files removed, collectors/probes exit. Volumes were
restored2/15 Tablet and15/15 S9, then S9 reset to7/15 for the explicitly new r2
test. This is owned cleanup, not global FD leak-freedom.

No commit: this candidate is implemented/tested with promising physical
diagnostic evidence, not final physically validated quality. HEAD unchanged;
staging0,255 tracked dirty,99 collapsed untracked. Tracked classification:
source/debug34, tests5, docs6, generated native210. Untracked: source20, tests15,
docs11, other53 (51 inherited unrelated plus2 earlier JVM crash/replay outputs).
D: diagnostics/APKs/traces are generated evidence excluded from commit.
index.lock is preserved unchanged. No QUALIFIED/PRODUCTION_READY claim.

Next ONE task: restore the normal Tablet DirectShell prerequisite and complete
the final S9->Tablet consumer-window quality campaign on this frozen candidate,
after verifying peer stability; no A41 substitution or SystemAudio redesign.

## Preserved Boundaries

UID RULES=NONE. DirectShell authenticated owner/generation/HMAC, protected
Session, anti-replay, trust and packet/payload semantics unchanged. No Root,
framework/HAL/vendor/SELinux modification, hidden Shizuku or generic injection.
Complete Host remains Video+SystemAudio+Input; microphone additional.

A41 global registration remains MODIFY_AUDIO_ROUTING permission-blocked; S7
legacy probe reaches the primitive but permission blocks registration, product
API<31 adapter still unimplemented. Portability extension and real forced
priority remain quality-gated. Natural routing is preferred; invalid-preset
setter authorization is not crash-safe preference validation.

Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
remain NOT IMPLEMENTED. Composite planning constructs a Complete Host BEFORE
a Session; publication withdrawal after runtime loss is not post-failure
recovery/migration. RFC-005J/005K, display policy, HOST_AND_REMOTE, physical
DirectRoot, broader app/input-route qualification and SystemAudio quality
redesign remain deferred. No production-release microphone promotion.
