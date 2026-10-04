# SystemAudio jitter / playout investigation

## Status

SystemAudio quality is **FIXED_AND_VALIDATED ON A41/API31 ->S9/API29** with
frozen FC7D151F:3/3 consecutive Sessions under one Enable Host,70s deterministic
audio per cycle, rejection/underrun/xrun0 throughout sampled active lifetimes,
including the closing sample. This is not universal Android qualification or
production-readiness. Earlier FAILs below remain FAIL. Previously validated
MicrophoneAudio milestones remain valid on their reported APK/path; the bounded
new-APK microphone regression and its startup/teardown anomalies are separate.

## Repository and scope

Starting branch: `codex/directshell-production-runtime-integration`.
Starting HEAD: `715b2151af2c8ecac165729178266b24e5cae91b`.
Master: `032b59276a0e01e4674e807a8ffb5b5b86e9150e`.
Upstream: `9d0b4dce7ea04a82e81778de6296cc1096775d31` (ahead 19, behind 0).
Initial index was empty. The large inherited worktree and generated native
outputs were preserved. The operator authorized archiving/removing only the
empty September 30 index lock; its metadata and identical backup are outside
the repository.

Primary pair: A41 `R58N52B32JJ`, API31 Host, to S9
`23eaec0f12047ece`, API29 Client. Initial peer pings were 3/3 in each
direction. A41 Wireless Debugging was already enabled; it was not toggled.
No Root, provider migration, hidden fallback, or router change was used.

## Frozen pre-fix baseline

APK SHA256:
`C782DD9D0FD63251C8895CC13344F7EE4EE60E6AF6472FD6F6EC499182AB508F`.
The previously validated MicrophoneAudio APK was installed on A41; S9 already
used the same artifact. Normal Enable Host at `2026-10-03T20:48:46.9435519Z`.
Streaming at `20:49:08.4438534Z`; normal Disconnect/Ready/Waiting complete at
`20:50:30.0408077Z`. This is approximately 81.597 seconds including UI
observation/teardown latency, with a 70-second harness hold. A bounded 997 Hz
normal Android AudioTrack source ran for eight seconds near the start.

Result: lifecycle PASS; quality FAIL. No retry was hidden.

- Last sampled receiver: 13,765 datagrams, 13,763 decoded audio units;
  cumulative playback-ring-full drops: 799 blocks (240 PCM frames each).
- Last **output epoch**, not whole Session: 331,200 decoded PCM frames,
  300,960 submitted, 30240 rejected, 134 underrun callbacks, zero xrun.
- Seven Oboe output opens occurred in this one Session. Native playback and
  decoder counters restart at output reconstruction; summing snapshots or
  presenting the last epoch as a whole-Session total would be incorrect.
- Host privileged capture sampled 3,367,920 captured frames and 3,363,120
  published frames, 20 dropped chunks, 8/8 shared-ring high-water, no read
  error. A41 app-drain ring metrics are distinct from these producer counters.
- SystemAudio remains 48 kHz stereo PCM16, 240 frames / 960 bytes per 5 ms
  codec unit. Client playback ring: 960 frames / 20 ms; initial threshold:
  240 frames / 5 ms. S9 AAudio Exclusive/LowLatency: 96-frame burst,
  192-frame device buffer, 3072-frame device capacity.

Evidence lives under `D:/WarpnectDiagnostics/phase6_5/2026-10-03/`, prefix
`systemaudio-baseline-C782`. Logs and APKs are not commit content.

## First discriminating observations

Host encoder input already groups nominal 5 ms units: first five-second window
had interval bins (<1, <3, <7, <10, <20, <40, >=40 ms)
`0,597,168,30,202,0,0`, maximum gap 19.096 ms; the next window maximum
was 20.495 ms. This is an observed source/drain cadence, not proof of a
scheduler defect or a requirement for perfectly periodic UDP arrival.

Client kernel socket-enqueue timestamps show both ordinary burst/gap delivery
and larger anomalies. One sequence-continuous window has a 59.416 ms kernel
gap; another has a 208.802 ms kernel gap and 20 missing sequence units.
The latter also has 313.665 ms userspace read gap, 373.825 ms socket age, and
312.545 ms receiver listener time. A later listener maximum is 287.280 ms.
Authentication was much smaller in those windows (3.887 / 6.039 ms).

`DefaultAudioReceiverSessionController.resetForFreshFrameLocked` reconstructs
both decoder and output for SystemAudio large/misaligned gaps. The receiver
calls this synchronously inside its audio-frame listener. S9 logs show
AAudio stop, close and open on that same worker. This is a trustworthy
**additional local amplification boundary**: it is not evidence that every
upstream gap is caused by Warpnect, or that RF transmission was measured.

Oboe deliberately has close synchronization/workarounds for callbacks that
may outlive stop. Removing those safety delays is not the proposed fix.
See [Oboe AudioStream close contract](https://github.com/google/oboe/blob/main/include/oboe/AudioStream.h).

Kernel `SO_TIMESTAMPNS` is a local CLOCK_REALTIME socket receive diagnostic,
not NIC/RF hardware arrival. Intervals within that domain are usable; no
Host/Client clock subtraction is used. JVM GC logs report short stop-the-world
pauses; concurrent GC total durations alone do not prove 300 ms application
pauses.

## Ordered investigation

1. Baseline above: old ring and all current MicrophoneAudio behavior unchanged.
2. E895C2FC retained a healthy same-generation output across codec-only
   freshness reset. Output opens fell from seven to one and listener maxima
   fell from ~300 ms to single-digit ms. The unchanged 20/5 ms ring still
   rejected 110880 frames and reported 1327 underruns: quality FAIL.
3. 9A9FCEB3 moves capture admission after the blocking read and advances the
   source position even for a discarded physical chunk. Unit tests prove
   truthful gaps rather than compressed time. Quality remained FAIL. A first
   harness attempt failed with a null UI root before Enable Host; its output
   is preserved. Unique fresh UI dumps fixed the harness; the explicit r2
   attempt is separate, not a retroactive PASS.
4. 7043F8C7 applies audio priority to the app shared-ring drain. At 45 seconds
   it had 93360 rejected frames and 1158 underruns. Priority alone FAIL.
5. D4A55791 additionally gives the capture helper audio priority. The last
   observation had 119280 rejected frames / 1459 underruns / xrun0. Callback
   maximum gap was only 4.326 ms (96-frame / 2 ms cadence): recurring silence
   is ring starvation, not an Android output xrun. App capture-drop counters
   are not authoritative helper drop totals.
6. 8D197752 applies one-burst bounded capacity admission to SystemAudio too
   (S9 budget 2 ms). The unchanged ring still lost 83520 frames / 1074
   underruns / xrun0. Improvement is not closure.
7. 731C8898 waits at most one 5 ms captured chunk for a shared-ring slot.
   Helper final counters: 3543040 captured and published, zero dropped
   chunks, 127 capacity waits, maximum observed wait 4.184 ms. Frame gaps and
   decoder resets disappeared in that diagnostic, but quality remained FAIL.
   A separate 70-second continuous-tone control on the same APK also retained
   a single output/decoder, with zero helper loss (4152320 frames, 129 waits,
   max 3.219 ms), yet the last snapshot rejected 91200 frames / 998 underruns.
   This distinguishes source admission from playback reserve.
8. A60BA587 tests a separate SystemAudio 60/50 ms ring/reserve, not the
   MicrophoneAudio 80/70 ms values. Ordered stereo replay proves that 30 ms
   cannot cover a 40 ms arrival-phase excursion. The physical candidate is
   still FAIL: last snapshot rejected 22320 / underrun235 / xrun0. A remaining
   capture-completion gap of 162.225 ms precedes the matching socket/receive
   gap, while helper GC is active. Correlation does NOT yet establish the
   mechanism. The next diagnostic splits blocking-read and between-read time.
9. 3252D184 proves 83-164 ms gaps BETWEEN reads, followed by reads of only
   0.06-0.15 ms. AudioRecord blocking-read duration is not the explanation
   for those events. 03B43C1F separates stages: 153-160 ms is before the
   second ACK, while payload publication and notification remain sub-ms.
   Perfetto's bounded 32 MB trace shows capture TID30006 sleeping for
   154.699 ms while the same helper's HeapTaskDaemon is active. The trace has
   OEM task-state import errors and no named ART allocation stack, so it
   does not prove a specific internal ART function. Log/phase correspondence
   is local-device only. The earlier 8 MB trace missed the relevant interval.
10. 442D3DCF reuses the already little-endian mapping for absolute metadata
    access instead of allocating a duplicate for each access. Source periodic
    pauses largely disappear, but a cold first diagnostic report still costs
    77 ms; last snapshot rejects3840 / underrun56 / xrun0. Quality FAIL.
11. A24487AA moves cadence report formatting/logging off the media thread to
    a bounded DEBUG queue. Quality still FAIL. A GC-correlated 103.300 ms pause
    is measured inside per-frame cadence observation at frame1643760, not at
    report emission. Nullable Long position state allocates on that path.
12. 4D30AF9D candidate removes per-frame position boxing and caches capture
    payload/source ByteBuffer views for the mapping lifetime. Mapping changes
    rebuild views; generation and source timeline semantics are unchanged.
    This is an allocation-path experiment, not a larger reserve or a final
    physical quality claim. The same 60/50 ms candidate remains in use.
13. 7C352B98 temporarily disables capture cadence observation. A 102.208 ms
    pause moves to the second ACK segment: the probe alone is not the cause.
    Last snapshot rejects40800 / underrun439 / xrun0, six gap/reset events.
    The temporary probe-disable experiment was removed.
14. EE508D06 removes the remaining hot-path signal-record allocations and
    replaces allocating InputStream.available() readiness with preallocated
    Os.poll/8-byte ACK decoding. The 120-second observation publishes all
    5949440 captured frames, zero source drops, only two capacity waits
    (max1.748ms), no observed >=30ms helper pauses. Last receiver snapshot:
    5730240 decoded, 5730000 accepted,240 rejected, two underruns/192 missing
    frames,xrun0, no gap/reset. Quality still FAIL. One partial-head ring
    admission at occupancy2688 has only192 free frames in the twelve-slot
    ring; a240-frame unit cannot fit before a delayed callback.
15. E725579C adds exactly one five-ms slot: SystemAudio65ms capacity/50ms
    reserve. Native replay checks partial-head twelve-slot failure versus
    thirteen-slot acceptance. Neither the initial reserve nor the one-burst
    wait was increased. MicrophoneAudio80/70ms remains unchanged.

## First counted 65/50 ms campaign: failed, not superseded by a retry

Frozen SHA256:
`E725579CF99DA0A0B11EA4F768E1AACBDF3A92A8F619F1F0EDAEBCC3AA8F4609`.
One Enable Host; same APK on A41 and S9. Normal music volume5/15.

| Cycle | Observed Streaming to Ready/Waiting | Last sampled rejected / underrun / xrun | Outcome |
| --- | --- | --- | --- |
| 1 | 92.900s | 0 / 0 / 0 | PASS in available samples; final few seconds were not sampled |
| 2 | 92.556s | 14640 / 204 / 0 | FAIL; counting stopped |
| 3 | NOT RUN | NOT RUN | Not counted around failure |

Cycle1 sampled3810240 decoded/accepted frames, HWM2976 (above the former
2880-frame capacity), max callback gap5.859ms and max ring residence60.857ms.
Cycle2 first failure follows sequence2395: kernel socket gap62.109ms,
userspace gap62.381ms, socket age0.922ms. Later kernel gap250.491ms precedes
an ordered burst up to41units; windows show missing6/8/11/4 sequence units.
Four decoder freshness resets occur, but no output recreation. Callback
max4.495ms and xrun0 do not explain the upstream gap. Host capture publishes
all4075520frames with zero drops/waits; sampled send maxima remain below30ms
outside startup. This narrows the residual boundary but does not prove an
RF/driver/AP cause: kernel cadence probes only observe delivered frames.

A single explicit diagnostic reproduction on the identical E725 APK, not a
new acceptance cycle, completes113.184s with last sampled5011440 decoded
and accepted frames, rejection/underrun/xrun0, no sequence/reset events.
Concurrent bounded /proc UDP observation shows unchanged system RcvbufErrors
(36881), zero drops on Warpnect UID10140 sockets and observed RX backlog
HWM11264bytes. This reproduction does not retrospectively pass cycle2.
Native bounded raw-socket-gap and protection-rejection diagnostics are added
to distinguish pre-authentication delay from discarded protected datagrams.

Two measurement defects were found separately: the OFF-before-tone harness
delay was accidentally after the cycle loop (the earlier70s tone duration
was still real), and the audio runtime sampler lacked a closing sample.
Both are corrected before another frozen campaign. Do not label the earlier
initial interval as a deliberate10s OFF window. A JVM diagnostic assertion
still expected12 reports after the limit became24; corrected. Full JVM run
then passes1036tests/165suites, but lint detects a DEBUG route-address API28
call without its API guard; the guard is added. These failures remain logged.

The numbered experiments are exploratory single Sessions. The counted campaign
above remains a failed acceptance campaign, not a successful qualification.
The first new JVM build failed because a test returned a value rather than
Unit; that harness/test defect is preserved, fixed and rerun. Initial native
Debug build failed LNK1201 writing a PDB. Generated native artifacts were
losslessly NTFS-compressed, not deleted; bounded single-worker rerun passed
CTest Debug22/22 and Release22/22. Earlier native failure remains evidence.

Local checkpoints created after the fresh pre-checkpoint gates:
`596a53453c0231fc7a49155643d3166b960eda0f` (87 source/test/doc files, validated
MicrophoneAudio milestone) and `b812d82b4975a6cb4abd3dfcf22d013b78e29f60`
(two Input bridge source/test files for reserved ResetState(AllDevices)).
SystemAudio experiments remain uncommitted. No push or merge.

No final three-session quality campaign has passed at this point. Human
audibility on this baseline is NOT CONFIRMED: the operator is no longer near
the devices. Technical signal and routing observations must remain separate.
The operator requested moderate audio volume: A41 and S9 music are now 5/15
through standard device volume controls, with no security/privacy changes.

## Full-coverage counted candidate and startup discrimination

Frozen APK SHA256:
`1524266D46B88571539FFD7E2CAF08A909B3317EDE2558B5F50FACE2B00235BE`.
The first harness launch failed before Enable Host because the launcher rather
than the normal app screen was visible after installation. No Session counted.
After explicit normal MainActivity launch, a new counted campaign failed cycle1:
18823 decoded units /4517520 decoded frames,4515120 accepted,2400 rejected,
four underruns /336 missing frames,xrun0,HWM3120. Cycles2/3 NOT RUN.
Sequence gaps, PLC, duplicate/late units and decoder resets were all0.
The closing snapshot is now included, unlike the earlier sampled-only cycle.
Initial ordered bursts reach17 units; kernel socket phase moves about89ms.
A later57.734ms socket-enqueue gap precedes an active-stream underrun.
This is not the same missing-sequence failure as the older E725 cycle2.

A bounded startup probe was added without changing65/50ms reserve, codec,
socket buffers or packet duration. Candidate
`A6F8E27AEE616BFAEF0361C3C071CE18351D81759BCE1025708CB72F06C6B734`
records first32 unit positions/timestamps at capture, shared-ring drain,
encoder, protected-send, kernel socket enqueue and receiver boundaries.
One explicit diagnostic Session fails1920 rejected /21 underruns /xrun0.
Capture worker scheduling0.982ms, setup16.043ms, first read5.618ms do not
support a90ms worker-setup explanation in this reproduction. Send maxima
17.933/22.803ms versus sequence-continuous kernel gaps up to78.759ms
localize additional delay below application send and before socket enqueue;
they do not identify RF, AP or driver individually.
An unchanged-build, bounded Wi-Fi low-latency-lock A/B/A experiment follows.
It is diagnostic only; no product Wi-Fi/power policy is added by inference.

That A/B/A completes with240 rejected /underrun0 /xrun0: the rejection is
initial, while all three steady windows have zero counter delta. Ordered
kernel gap maxima remain roughly33-46ms with the lock held and after release;
no causal elimination of the observed receive burstiness is established.
Both locks are explicitly released. No product Wi-Fi lock is introduced.
The initial full-ring rejection at occupancy2976 has only144 free frames;
the next240-frame block cannot fit until a96-frame callback runs. Measured
callback gaps reach4.331ms, but the existing deadline is only2ms. At a full
ring, up to three96-frame callbacks are needed for a240-frame packet.
The next isolated candidate leaves65/50ms buffering unchanged and gives
SystemAudio admission one packet rounded up to hardware bursts (288frames,
6ms on S9). MicrophoneAudio retains its existing one-burst wait. A focused
native replay checks both the old2ms expiry and new bounded6ms admission.

The prior complete software gates pass1036 JVM tests /165 suites and native
CTest Debug22/22, Release22/22. The startup probe adds one focused test;
its build/test run passes1037 JVM tests,165 suites, zero failures/skips/errors.

## Additional discriminating experiments (2026-10-04)

1. Frozen `380CD666E705B272AC3B6B4F93C9F899D87099891029B7A2B8CF565EC7020336`
   tests the block-sized6ms SystemAudio admission deadline, retaining65/50ms.
   Counted cycle1 lasts101.763s from observed Streaming to Ready/Waiting:
   accepted4503600, rejected0, underrun103 /9792 missing frames,xrun0,
   HWM3120, five gap/decoder freshness resets. Cycles2/3 NOT RUN.
   Raw socket gap219.017ms and missing authenticated sequence groups precede
   starvation; no protection rejection is observed. Late socket residence
   reaches156.220ms and listener duration17.125ms, despite the6ms logical
   admission deadline. This candidate fixes local rejection but not quality.
2. A standalone, bounded timing-only UDP probe carries161-byte padding plus
   sequence/magic, never audio. SO_TIMESTAMPNS records socket-enqueue time;
   SO_RXQ_OVFL independently checks socket overflow. Shell UID2000 probes
   are controls, not authenticated Session replacements or RF timestamps.
   Regular5ms:9000/9000 units, no loss/duplicate/reorder/overflow, send max
   11.029ms, kernel max37.507ms. Four-unit20ms groups:9000/9000, same zero
   integrity/overflow counters, send max27.851ms, kernel max48.964ms.
   During a normal SystemAudio+Video Session, regular control still receives
   9000/9000, send max9.454ms, kernel max64.828ms, socket age max4.696ms.
   The accompanying SystemAudio diagnostic has rejection0/underrun11/xrun0
   and kernel gap72.487ms. These observations demonstrate additional delivery
   variation under Session load, not a specific RF/AP/firmware root cause.
   The first probe launch lacked executable mode after adb push (harness
   failure, no probe traffic); corrected using normal shell chmod, not Root.
3. Frozen `D0A4D1C7F5DD4216C3DE428F8C3724B3F844034D2FC3CDF8BF4A0E748EF345AD`
   tries the existing AudioRecord pacer in the normal app's shared-ring drain.
   Source cadence improves but privileged capture drops12 chunks, inducing
   twelve PLC frames; the counted cycle1 also ends underrun56, rejection0,
   xrun0. Cycles2/3 NOT RUN. This placement is rejected and removed; the
   entire failed diff remains in excluded diagnostics. No new source pacer
   or changed MicrophoneChunkPacer behavior is retained by inference.
4. The next isolated candidate returns to the unchanged, unpaced SystemAudio
   capture path and extends the existing audio receive-worker priority to
   SystemAudio. Nice0 listener wakeup/processing exceeds6ms and accumulates
   socket residence above100ms; the audio-priority experiment tests that
   local scheduling boundary independently. MicrophoneAudio's already-used
   audio priority,80/70ms reserve and40ms IPC freshness remain unchanged.

The first full native rebuild for block admission fails LNK1168/LNK1201;
no live native test process is found at inspection. No source workaround or
artifact deletion is applied. Bounded rerun succeeds: Debug22/22 (3.95s),
Release22/22 (4.15s). Complete Gradle gates for that build and the failed
drain-pacer build both pass1037 JVM tests /165 suites. These software results
do not promote either failed physical campaign.

Bounded Input/Video regression on D0A4: the initial harness wrongly seeks a
SurfaceView instead of the normal input-capture View, and cannot return from
the owned DEBUG target. Failure retained; normal app navigation/Disable Host
cleans resources. Corrected bounded run captures two touch events, sends four
protected datagrams, injects DOWN/UP into the owned Host target, and has zero
mapping/injection failures. Client renders63 decoded Video frames, dropped0;
normal Disconnect returns Ready/Waiting and Disable Host leaves no helpers
or temporary secrets. This is not a universal Video/Input qualification.

## Final frozen campaign (2026-10-04)

APK SHA256:
`FC7D151F668916F0D8B6DB21F2ED10CF848DF574BFBA6C831EF39A89673026EB`.
The same frozen artifact is installed on A41, S9 and Tablet; later gate rebuilds
are not substituted into the counted campaign. One normal Enable Host occurs
at06:34:34.260527Z. No Host toggle, app restart, hidden Connect retry, Wireless
Debugging change, provider replacement or Wi-Fi product lock occurs between
these three cycles. Each tone runs70s, with at least10s initial OFF and a later
OFF interval. Streaming-to-Ready duration includes UI observation/teardown, not
an invented active-signal duration.

| Cycle | Streaming UTC | Ready/Waiting UTC | Observed duration s | Tone s | Captured/published PCM frames | Decoder/accepted PCM frames | Rejected /underrun /xrun | Result |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| 1 | 06:35:01.813973 | 06:36:45.124953 | 103.310980 | 70 | 4970496 /4970496 | 4968240 /4968240 | 0 /0 /0 | PASS |
| 2 | 06:37:01.043177 | 06:38:44.430785 | 103.387608 | 70 | 4563968 /4563968 | 4562160 /4562160 | 0 /0 /0 | PASS |
| 3 | 06:38:58.760607 | 06:40:40.373796 | 101.613189 | 70 | 4555776 /4555776 | 4553520 /4553520 | 0 /0 /0 | PASS |

Final startup, steady and closing rejection/underrun/xrun deltas are all0.
Priming135/161/144 callbacks is separately reported, not relabeled underflow.
No playback or decoder counter epoch resets occur in these final Sessions.

| Cycle | Generated/sent datagrams (Host snapshot) | Received datagrams | Delivered audio units | Decoded units | Consumed PCM | Sequence gaps /PLC /resets /duplicates /late |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 20706 /20706 | 20703 | 20702 | 20701 | 4965408 | 0 /0 /0 /0 /0 |
| 2 | 19013 /19013 | 19011 | 19010 | 19009 | 4559232 | 0 /0 /0 /0 /0 |
| 3 | 18976 /18976 | 18975 | 18974 | 18973 | 4550784 | 0 /0 /0 /0 /0 |

Host encoded/payload units20705/19012/18975,128kbit/s CBR Opus,80-byte
encoded units, one161-byte protected datagram per ordinary5ms unit. Transport
WouldBlock/send failures0 in all closing Host snapshots. Snapshot endpoints
are asynchronous: sent-minus-received is not a measured packet-loss count.
No missing/reordered/duplicate unit is observed in final cadence windows.
Capture has zero dropped chunks/read failures. Source-ring HWM8/6/7 of8;
cycle1 has three bounded admission waits, maximum2.296ms; cycles2/3 have none.

| Local metric (maximum unless noted) | Cycle1 | Cycle2 | Cycle3 |
| --- | ---: | ---: | ---: |
| Shared-ring drain interval ms | 21.559 | 23.516 | 27.144 |
| Encoder-input interval ms | 21.858 | 23.680 | 27.304 |
| Encoder-output interval ms | 24.557 | 23.853 | 27.549 |
| Socket-send interval after bootstrap ms | 26.263 | 22.850 | 26.458 |
| Kernel socket-enqueue interval ms | 60.638 | 48.292 | 63.616 |
| Within-window ordered enqueue phase span ms | 62.646 | 51.709 | 66.075 |
| Socket residence ms | 15.134 | 18.295 | 22.817 |
| Authenticated listener duration ms | 5.345 | 11.203 | 4.725 |
| Oboe callback interval ms | 4.309 | 4.399 | 4.486 |
| Ring HWM /LWM frames | 4080 /720 | 3936 /816 | 3984 /288 |
| Ring residence ms | 86.116 | 80.500 | 82.446 |

These are distinct local measurements, not additive cross-device E2E latency.
The socket-send bootstrap window includes configuration-to-first-media wait
(414.453ms in cycle1); it is not a recurring active-media send gap. Capture
worker wakeup1.042/0.957/0.955ms; setup6.024/13.103/5.877ms; first blocking
read12.864/7.189/14.597ms. Source batching remains roughly hardware20ms read
granularity rather than an imposed uniform5ms RF departure requirement.

Kernel interval histogram bins(<1,<3,<7,<10,<20,<40,>=40ms):
cycle1 `3060,8211,4099,304,4196,122,28`;
cycle2 `3718,6717,3377,282,3772,123,27`;
cycle3 `4022,6341,3471,277,3771,117,23`.
Stable output despite these arrival distributions is the acceptance result,
not perfectly regular packet arrivals. The particular RF/AP/driver mechanism
is not proven. No arrival timestamp is described as RF hardware time.

### Signal and REMOTE_ONLY

Client first-channel decoded PCM is0 RMS before and after the bounded tone.
Steady ON RMS ranges129.827-129.830,129.695-129.698,129.829-129.833 for the
three cycles; observed positive-crossing frequency996.985-997.012,
996.806-997.009,997.000-997.009Hz respectively. The997Hz projection is about
183.6/183.4/183.6. This is real Host AudioTrack ->REMOTE_SUBMIX ->protected
Session ->decoder signal, not synthetic receiver PCM.

Host tone AudioTrack reports type25(REMOTE_SUBMIX) during every final cycle;
post-Disconnect bounded5s tone reports type2(BUILTIN_SPEAKER), track underrun0.
S9 decoded PCM and Oboe consumption advance. This is technical routing and
signal evidence preserving the previously human-validated REMOTE_ONLY
semantic, not a new human listening claim. Human audibility: NOT_CONFIRMED.
Music volume is5/15 on all used devices, including the activated submix route,
before emitting the quarter-scale tone. No Bluetooth/USB/headset claim.

### Final causal solution and latency tradeoff

The harmful Warpnect assumptions were compound, not a single networking bug:

1. Codec-only freshness reconstruction synchronously reopened Android output
   on the protected receiver (~300ms), amplifying an upstream gap. Retaining a
   healthy same-generation clock removes this amplification; generation,
   format, gated-start and unhealthy-output boundaries still rebuild.
2. Pre-read slot admission and allocation-heavy capture signaling could reject
   PCM unnecessarily or pause privileged production during GC-related
   scheduling. Admission after the read, truthful source positions, bounded5ms
   admission, cached views/reusable8-byte signaling and poll arrays remove
   measured capture drops without changing Shared Ring V1 or its8-slot size.
   The exact ART internal pause stack is not asserted from incomplete Perfetto.
3. A nice0 SystemAudio receive worker and a one96-frame-burst admission budget
   could stall or reject a240-frame stereo packet. Audio priority and a bounded
   packet-rounded288-frame/6ms deadline admit it without blocking Oboe itself.
   MicrophoneAudio retains its already-used audio priority/one-burst contract.
4. Lossless ordered arrival phase excursions exceed the old5ms and candidate
   50ms reserves. The measured63.38ms failed window and smaller-reserve native
   replays justify a fixed70ms initial reserve with85ms hard ring capacity.
   Final excursion reaches66.075ms without starvation. Capacity-only or50/60ms
   reserve alternatives fail. No counter suppression or PCM-discard fix is used.

SystemAudio reserve5->70ms adds65ms initial playout reserve; compared with the
failed50ms candidate it adds20ms. Capacity20->85ms is a maximum payload-memory
bound, not another85ms added to reserve:4080 stereo frames/16320 PCM bytes.
Source admission is nominally bounded5ms (observed max2.296ms), playback
admission6ms on S9 (previously2ms); scheduler overshoot is not a hard realtime
guarantee. S9 device buffer192frames/4ms and burst96/2ms remain unchanged.
No SystemAudio pacer is retained; capture hardware buffering, codec5ms/
128kbit/s, socket buffers and wire/security contracts remain unchanged.
MicrophoneAudio's80/70ms DEBUG and40ms IPC freshness are not SystemAudio policy.

Fixed versus adaptive decision: FIXED, NO ADAPTIVE ALGORITHM NEEDED FOR THIS
VALIDATED WINDOW. Distribution-aware fixed reserve suffices after artificial
pauses/backpressure are corrected. No adaptive implementation/A-B success is
claimed. Longer clock drift, other networks and extreme loss remain unqualified.

### Bounded regressions and anomalies

Same-FC7D151F S9 ->Tablet ordinary MIC regression has real acoustic997Hz at
type25 input, UID rules NONE/preferred-device false, then type15/id11 physical
capture after Disconnect. First cold start has DEBUG1440 rejected/underrun0/
xrun0, a119.197ms callback gap, four initial source gaps/two decoder resets;
submix track underruns7 within the first308.6ms of opening, then no steady
counter increments. This is preserved as a bounded startup anomaly, not
retroactively called a zero-event PASS; its exact cold scheduling mechanism
is INCONCLUSIVE. Historical C782 microphone milestones are not generalized.

One explicitly bounded same-APK reproduction has active DEBUG rejection0/
underrun0/xrun0 and submix track underruns0, queue rejection0, HWM8, fresh PCM
max38.93ms, natural type25 selection. Ordinary ON RMS about1172-1196 and
997Hz projection1657-1690, versus OFF/background projection below2; physical
type15 returns after Disconnect. Initial one source gap/two PLC units does not
recur. Closing sample reports21 DEBUG underruns/5200 zero-filled frames after
input ResetState(513915.132), Video stop(513915.217) and before audio close
sample(513915.317): teardown, not continuous-consumer starvation. The first
run's closing ReceiverFailed/PacketEncodeFailed is also observed only during
normal teardown, not presented as an active success/failure metric.
No further retry or microphone redesign is performed. Its bounded functional/
steady regression passes with these startup/teardown limitations visible.

Same-APK A41 ->S9 Input/Video smoke: two touch events mapped/injected into the
owned DEBUG target, injection/mapping failures0, DirectShell backend. ResetState
sent in three protected copies; semantic duplicates are rejected as designed.
Video63 decoded/released/rendered frames, decoder/render drops0. Normal
Disconnect Ready/Waiting and Disable Host cleanup pass. Initial earlier Input
harness SurfaceView failure is retained separately, not a product PASS.

Final A41/S9/Tablet UI is Ready, Host disabled. Exact-process checks find zero
Warpnect privileged helpers and zero temporary DirectShell secret files; no
dynamic microphone mix remains. Oboe/audio sinks, SystemAudio recorder and
Video mirror close. No global FD leak-freedom or18h repeat is claimed.

### Software gates and checkpoint scope

Final source gates: git diff --check, :app:ktlintCheck, :app:lintDebug,
:app:testDebugUnitTest --rerun-tasks, :app:assembleDebug,
:app:assembleDebugAndroidTest, :app:packageDirectShellProbeDex all PASS.
Gradle workers1,100 tasks rerun,13m16s. Exact JVM1037 tests/165 suites,
failures/errors/skips0. Native CTest Debug22/22(5.94s), Release22/22(5.83s).
Earlier build/lint/test/linker failures remain in the chronological evidence.

Focused source/tests: SharedPcmCapturedChunkWriterTest(5 actual admission,
timeline and generation cases), SharedPcmAudioRingLayoutTest(metadata views/
signaling), SystemAudioPlayoutReserveTest(4 duration, isolation and contract
cases), AudioSessionControllerTest(clock retention/warm-start gate),
MicrophoneReceiverPriorityTest(SystemAudio worker-only extension),
AudioCadenceDebugProbeTest(bounded/deferred/startup reporting),
PcmDeliveryDebugProbeTest(stereo numeric signal/limits), native
audio_playback_ring_tests(phase reserve, partial-head and block-rounded wait).
New tests do not fabricate physical audibility or hardware success.

Microphone checkpoint596a53453c0231fc7a49155643d3166b960eda0f contains87 files:
67app source/test,18docs,2native tests,10495 additions/59 deletions.
Independent Input checkpointb812d82b4975a6cb4abd3dfcf22d013b78e29f60 contains
only InputReceiverBridgeDecoder.kt and its tests(60 additions/1 deletion).
The SystemAudio checkpoint scope is the reviewed source/tests/current docs
described here; exact commit identity belongs to Git history/final task report.
Unrelated Wi-Fi probe manifest/code, metadata-only IPv4/input files, scoped
virtual-mic probes, screenshots, XML, crash logs, native/build-rfc006e generated
changes, APK/log/Perfetto/timing-only UDP evidence remain excluded/preserved.
No push, merge, reset, clean, stash or recovery/migration implementation.

The first final staging attempt fails on another empty index.lock, unchanged
since2026-10-03T21:08:43Z, with empty staging/no live Git processes. Under the
operator's existing lock-only authorization, its hash, metadata and identical
copy are preserved outside the repo before removing only that exact stale
file after a second process/metadata check. No index/worktree evidence is lost.

Accepted completion: SYSTEM_AUDIO_JITTER_PLAYOUT_QUALITY_FIXED_AND_VALIDATED,
scoped only to the final A41/API31 ->S9/API29 campaign. IMPLEMENTED, TESTED and
HARDWARE_VALIDATED are supported; universal QUALIFIED/PRODUCTION_READY are not.
Next ONE task: MicrophoneAudio Android Compatibility Expansion (A41/API31 and
S7/API26, capability-driven, no OEM hacks; optional forced capture priority
only after baseline compatibility). It is not begun in this task.

### Audio-priority result and measured-reserve candidate (historical)

Frozen `73842D176DBBB0E01FB592A50EFA41420BFF87F7A6AB0B96BCB43FF70337E968`
is a counted FAIL in cycle1 (102.446s observed Streaming to Ready/Waiting):
4553280 decoded/accepted frames, rejected0, underrun7 /624 missing frames,
xrun0, no gaps/PLC/decoder resets/duplicates/late units. Cycles2/3 NOT RUN.
Receiver TID13013 is the worker that now uses audio priority. Socket residence
maxima are5.511-15.530ms across observed windows, versus156.220ms previously;
listener maxima stay3.069-6.659ms. SystemAudio capture publishes all4554752
captured frames, with zero dropped chunks or capacity waits. The sampled
UID10140 sockets have zero drops; global UDP RcvbufErrors stays36882.

Seven underrun callbacks occur during active tone, not startup or teardown.
Their window has ordered kernel socket-enqueue phase range63.375730ms
(-15.959453 to+47.416277ms), exceeding the50ms reserve. Callback gap maximum
is4.292ms, xrun0. Ordered delivery still differs from perfectly spaced5ms
packets even after local scheduling/backpressure fixes. SO_TIMESTAMPNS does
not identify the particular AP/RF/driver mechanism; send-call time is not RF
departure. No network recovery or Wi-Fi lock is introduced.

A native stereo replay rounds this measured excursion to65ms:50ms and60ms
reserves both starve;70ms reserve with three spare5ms packets accepts all
frames without underrun/rejection and stays bounded. The next isolated
candidate changes only SystemAudio reserve50->70ms and capacity65->85ms
(4080 stereo frames /16320 PCM bytes). This costs20ms additional initial
reserve compared with the failed65/50 candidate,65ms over the original5ms
reserve. It is not yet physical validation. MicrophoneAudio80/70ms and40ms
IPC freshness remain separate and unchanged. No adaptive algorithm or source
pacer is retained. Volume is explicitly5/15 after REMOTE_SUBMIX activation,
before sound, as well as on the receiving speaker; human audibility is not
available for this run.

## Preserved boundaries

MicrophoneAudio IPC freshness is 40 ms; its DEBUG reserve is 80/70 ms. Neither
is changed or copied into SystemAudio here. REMOTE_ONLY, authentication,
anti-replay, generation isolation, plan freeze, and Complete Host
Video + SystemAudio + Input remain required. MicrophoneAudio is optional.
Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
remain NOT IMPLEMENTED in current RFC-006J. A41/S7 microphone compatibility
and forced capture priority are deferred to a separate task.
