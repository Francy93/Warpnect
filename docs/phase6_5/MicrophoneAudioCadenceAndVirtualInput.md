# MicrophoneAudio cadence and first-class Android input

Current [final global-quality evidence](DirectShellGlobalMicrophoneFinalClosure.md)
supersedes partial status below: frozen C782DD9D r2 primary MIC3/3 above121s,
ordinary close/reopen continuous quality deltas0, physical997Hz, UID NONE,
natural routing and physical restoration. Global integration and historical
DEBUG cadence tokens retained; quality token earned on exact S9 ->Tablet path.
No broader first-class device/default selection, release or valid forced-role
claim. A41 permission/S7 adapter boundaries are capability data, not separate
architectures; no model-specific implementation is added.

Latest continuation: [final global DirectShell injection](DirectShellGlobalMicrophoneInjection.md)
physically validates capture-preset-wide ordinary MIC/VOICE_COMMUNICATION/
CAMCORDER routing on Tablet with UID rules NONE, protected S9 acoustic signal
and normal/helper-loss restoration. This is not universal explicit-device
selection or release readiness. New integration quality remains PARTIAL:
startup/no-consumer IPC pressure and MIC1 late DEBUG underrun are preserved;
forced priority is NOT TESTED. The older cadence APK's accepted80/70ms all-zero
result below is unchanged but not automatically transferred to that new APK.
All older first-class/scoped/cadence claims below remain historical exact paths.

Current continuation: [cadence root cause and bounded playout reserve](MicrophoneAudioCadenceRootCause.md)
records3/3 final S9 -> Tablet Sessions above60s, all rejection/underrun/xrun0.
The original20ms ring/5ms reserve could not absorb measured ordered arrival
phase; cold output startup was a separate admission problem. The final80ms
ring/70ms reserve and warm clock add65ms nominal priming, while network gaps
remain. This closes exact-pair DEBUG quality, not first-class input, human
Tablet audibility or universal networking. No AudioPolicy integration began.
The failed campaigns below remain historical, including the rejected60/50ms
candidate and the earlier source-pacer-only result in the linked reports.

Earlier continuation: the [resumed final paced campaign](DirectShellMicrophoneInjectionQualification.md#32-resumed-final-apk-campaign-after-prerequisite-restoration)
completed three S9 -> Tablet Sessions above60s after normal Host prerequisites
were restored. Lifecycle3/3 PASS, quality0/3 PASS: recurring steady rejection
and underrun remain despite improved source cadence. The report below preserves
earlier experiments; no-UID capture-preset policy integration is still gated,
not disproved by the earlier scoped/explicit-port result.

Campaign: 2026-09-30 / 2026-10-01. This continues the partial microphone work,
not another Phase 6.5 item. Physical capture and protected delivery are real;
DEBUG speaker playback, scoped injection and first-class Android input are
three different boundaries. None is silently substituted for another.

Later same-day [portable microphone ordered gate](DirectShellMicrophoneInjectionQualification.md)
supersedes only current status: first read batching is now proved at AudioRecord;
a no-extra-queue pacing candidate has local physical S9 evidence, but full quality
acceptance remains incomplete. The next Session's control suspension and later
Wi-Fi/ADB loss are preserved, not a PASS. Capture-preset-wide AudioPolicy remains
NOT TESTED, and cannot be classified from this report's older UID-scoped test.
The historical campaigns, counts, APKs and conclusions below remain unchanged.

## 1. Repository baseline and checkpoint policy

Branch `codex/directshell-production-runtime-integration`; starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b` (owner-bound DirectShell liveness).
Prior microphone checkpoint `809b7ddd48e9db192a86de17b59abdfd607916fd`;
Session terminal/rediscovery checkpoint `92347d0`.
Master `032b59276a0e01e4674e807a8ffb5b5b86e9150e`;
upstream `origin/codex/directshell-production-runtime-integration` at
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, 19 ahead / zero behind.
Staging was empty. Partial microphone source/tests/docs, tracked native build
output, older screenshots/UI dumps, `artifacts/`, `.codex-remote-attachments/`
and a line-ending-only Input capture status were preserved. An empty existing
`.git/index.lock` was not removed. No new checkpoint, push, reset, clean,
discard, merge, rebase or force-push. A partial quality investigation is not
a validated checkpoint boundary.

## 2. Exact tested environments

| Device / role | Serial | Build fingerprint |
| --- | --- | --- |
| Tablet X700_EEA, Android 13/API33, primary Host | Z1382023HGM1895372 | `Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys` |
| A41 SM-A415F, Android 12/API31, comparison Host/ordinary input consumer | R58N52B32JJ | `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys` |
| S9 SM-G960F, Android 10/API29, physical Client microphone | 23eaec0f12047ece | `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys` |

Tablet Wireless Debugging was initially OFF; the operator explicitly enabled
it. Existing trust was reused. All device actions used explicit serials.
S7/API26 was connected but not used or unlocked. S22/API36 was unavailable
in this task; its earlier scoped experiment remains historical evidence.

## 3. Protected baseline before any buffering change

Baseline APK SHA-256:
`64948EA3A589CCE8F79B085F039CCF45C301E19740908EAA5C7D66E040D0CCA1`.
One S9 -> A41 normal Session and one S9 -> Tablet normal Session reached
Streaming and returned to Client Ready / Host Waiting. Both failed quality.
The operator confirmed hearing the S9 microphone on A41: **HUMAN AUDIBLE
CONFIRMED for that DEBUG sink**. This does not erase rejection/zero-fill,
prove Tablet audibility or qualify a virtual input.

Tablet samples included rejected0/underruns2/zero-fill272 at first start,
then rejected7200 within the early window. At local MONOTONIC367868017 ms,
native decoded PCM4962720, accepted4863120, rejected99600, underruns829,
zero-fill97552, xrun0. Counter differences must not cross a decoder/playback
recreation. Earlier 62640 / 100080 / 75120 rejected-frame campaigns remain
FAIL in [the previous report](MicrophoneAudioVirtualInputArchitecture.md).

## 4. Boundary instrumentation and clock contract

`AudioCadenceDebugProbe` records bounded local interval bins, sequence/frame
position continuity, duplication/reordering and bursts. Its frame-position
step comes from the actual negotiated sample rate/frame duration; a 5 ms
media unit is not asserted to arrive every 5 ms.
`AudioReceiveTimingDebugProbe` observes:

| Boundary | Clock / producer | Meaning |
| --- | --- | --- |
| Protected-submit completion | Client `System.nanoTime`, after synchronous native send returns | Local submission completion, not physical radio transmission |
| Accepted unit's kernel receive stamp | Host `SO_TIMESTAMPNS` / CLOCK_REALTIME, carried from `recvmsg` | Kernel incoming timestamp, before application authentication; only accepted complete audio units are logged |
| Socket-read / authenticated unit | Host native steady/MONOTONIC | Local receive and verification/SCL parse boundaries |
| Socket age | Host REALTIME now minus its own kernel REALTIME stamp | Local socket residence diagnostic, bounded to less than one second; native0 is an unavailable sentinel, not measured zero latency |
| Pump mutex wait / listener duration | Host MONOTONIC | Local native lock wait and synchronous decoder/playback listener work |
| Decoded PCM delivery | Host `System.nanoTime` | Numeric energy and arrival cadence, no raw PCM logging |

No Host timestamp is subtracted from a Client timestamp. Kernel REALTIME is
not subtracted from MONOTONIC. Backward/missing stamps are skipped, not
presented as precise latency. The ancillary timestamp path and additional
native timing clocks are Android DEBUG-only. JNI metadata changed locally;
no Audio Payload/SCL/protected wire field changed. Rebind enables diagnostics
on the replacement socket and clears stale diagnostic timing, not media.
The [kernel timestamp contract](https://docs.kernel.org/networking/timestamping.html)
defines software receive timestamps; these are not physical Wi-Fi ingress or
remote send timestamps. Only stages with valid local stamps are comparable.

## 5. First additional burst/gap boundary

Diagnostic APK `39F6CF8A6EB768768B81118B85DFD159992466EA572C42E393EB05F4A065CB36`,
one S9 -> Tablet Session, twelve five-second windows:

| Local boundary | Observations | Interval bins <1/<3/<7/<10/<20/<40/else ms | Maximum gap / burst | Missing/reordered/duplicate |
| --- | --- | --- | --- | --- |
| S9 protected submit | 12010 | 478,8023,511,38,2949,10,0 | 25.850 ms / 4 | 0/0/0 |
| Tablet kernel receive | 12010 | 2594,5455,974,111,2784,81,10 | 52.673 ms / 9 | 0/0/0 |
| Tablet socket read | 12010 | 11,7729,1361,278,2564,58,8 | 49.120 ms / 6 | 0/0/0 |
| Tablet authenticated | 12010 | 12,7724,1365,275,2566,59,8 | 49.242 ms / 5 | 0/0/0 |
| Tablet pre-decoder callback | 12010 | 10,7824,1266,250,2592,59,8 | 49.473 ms / 5 | 0/0/0 |

The first extra observed disturbance is **after Client protected send and
already at Host kernel receipt**, not first introduced by the decoder or
playback ring. Source capture/send itself emits groups around its AudioRecord
delivery period; negotiated 240-frame units do not prove 5 ms wall cadence.
Steady authentication was sub-ms; pump-lock maxima were sub-ms; listener
maxima mostly about 2.6-3.4 ms. Startup listener/OS work was longer.
These distributions are local, not a synchronized per-packet network latency.

## 6. Exact thread / queue audit and unresolved root cause

`AndroidMicrophoneAudioCaptureController` owns `WarpnectMicrophoneCapture`
(audio thread priority), blocking AudioRecord reads and synchronous PCM sink.
Opus output calls `NativeSclAudioTransportController.submitAudioFrame` -> native
`AudioTransportSender` -> Session protection -> UDP send on that path.
`NativeSclAudioReceiverController` owns `WarpnectAudioReceiver`, invokes native
`AudioReceiverRuntime.receive_one`, protection/SCL and synchronous listener ->
`DefaultAudioReceiverSessionController` -> decoder -> Oboe ring. Ready-slot
ownership is released in finally; sampled ready-slot HWM is1.

The 20000 us pump argument is a maximum select wait, **not** a 20 ms batching
sleep: readable sockets wake immediately. No extra timed audio delivery queue
or main-thread dispatch was found. The DEBUG runtime sampler can contend for
the same native mutex, but measured wait does not explain the large gaps.
No evidence proves whether the additional kernel-arrival disturbance is
radio/AP/driver scheduling, sender kernel scheduling or another external
boundary. **Root cause remains INCONCLUSIVE**, narrowed more precisely than
the old protected-submit -> application-callback boundary.

## 7. Wi-Fi diagnostic control, not a product fix

APK `3422D914EC0325A5B8B590C650DAF7BDCBFD39BECF6568471E4B213A8A9A1A3E` ran
one separately counted normal 65-second Session. A DUMP-protected DEBUG receiver
requested Android's low-latency Wi-Fi lock for at most120 seconds. It never
changed Wi-Fi settings, codecs or ring size. Acquire/release each1, explicit
release after Disconnect, automatic fallback timeout. Request held does not
prove activation; Android diagnostics did not demonstrate activated low-latency
mode. This control is **INCONCLUSIVE**, not proof that a production lock fixes
or cannot fix radio jitter.
Android documents [low-latency Wi-Fi mode](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY)
as conditional on platform/runtime state. A held request is not proof of radio
activation, and this task does not turn the experiment into production policy.

The control still had kernel gap53.348 ms/burst8, recurring rejection/zero-fill,
and four missing accepted frame/unit positions in the early observations;
later windows were continuous. No reordering/duplicates in the twelve windows.
This is not relabelled PASS or conclusive raw-wire packet loss: accepted-unit
diagnostics do not timestamp every rejected/configuration datagram.

## 8. Rejection, underrun and unchanged buffers

`PcmPlaybackRing::submit` rejects invalid/generation-mismatched ranges and
full occupied slots. Here `PlaybackRingFull` / ring-full buffer counters show
**valid decoded PCM loss**, not a benign counter. A partial head still owns
its slot. `consume` zero-fills missing frames, counting callback events and
exact missing frames. AAudio platform xrun is separate.

48 kHz mono PCM16, 5 ms =240 frames =480 bytes; Opus64 kbit/s.
Ring remains four codec slots /960 frames /20 ms; start threshold240 frames.
Tablet AAudio LowLatency/Shared burst256, buffer512, capacity2048.
Initial 512-frame callbacks with only240 ready explain272 initial zero-fill
frames, **not** the continuing failure. Bursts fill the ring; subsequent gaps
empty it. No buffer increase, priming change, packet-duration change, guessed
pacer, counter suppression or drop-policy change is credited as a fix.

## 9. Final same-APK Session campaign

Final full-gate APK SHA-256:
`3968634A4E4F99D524AA637AE53992EAE5B12C22A80B1B8FBD7203AEC926C070`.
Post-campaign `sha256sum` of the actual installed base APK on both Tablet and
S9 matches this hash; no inferred equality from version labels alone.
Tablet appPID12865 / S9 appPID12471. One normal Enable Host at local
00:49:10; owner-attached feature helpers; readiness generation2 / runtime1;
frozen `SINGLE / DIRECT_SHELL` and publication AVAILABLE at00:49:21.716.
No Root/Shizuku, debug runtime arm, Connect retry, Host toggle or app restart
between the following attempts. The new probes did not change media pacing.

| Counted attempt | UTC Streaming | UTC Ready / Host Waiting confirmed | Requested steady Streaming interval | Lifecycle | Quality |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-09-30T22:49:48.3005706Z | 22:51:07.3364788Z | 65 s | PASS | FAIL |
| 2 | 22:51:21.4924732Z | 22:52:40.3483619Z | 65 s | PASS | FAIL |
| 3 | 22:52:54.8687199Z | 22:54:13.6295578Z | 65 s | PASS | FAIL |

Local calendar date is2026-10-01 (UTC+02). Each row includes bounded teardown
after the interval. The kernel/read/authentication probes are diagnostic,
not a successful cadence fix. Overall playback quality status: **PARTIAL**;
all three counted quality controls FAIL, never retroactively PASS.

Counters at the last periodic sample before each Disconnect:

| Attempt | Sender protected datagrams / audio units | Host datagrams / decoded units | Decoded PCM frames | Ring accepted / rejected frames | Underrun callbacks / zero-fill frames | Xrun | Sampled occupancy min/max; HWM |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 13993 /13992 | 13982 /13980 | 3355200 | 3265680 /89520 | 797 /84336 | 0 | 0/528;960 |
| 2 | 13993 /13992 | 13988 /13986 | 3357120 | 3275520 /81600 | 648 /78976 | 0 | 0/960;960 |
| 3 | 13993 /13992 | 13978 /13976 | 3354240 | 3277200 /77040 | 626 /74224 | 0 | 0/800;960 |

Sender snapshots are not simultaneous with receiver snapshots. Their totals
must not be subtracted to infer packet loss. In all three sampled native
timelines counters were continuous with **zero resets**, decoder failures0.
Capture/encode/send and receive/decode/playback progressed. The ring HWM960
captures a peak not necessarily present at any periodic occupancy sample.
Last Client capture totals were3357600 /3358080 /3358080 physical microphone
frames respectively; no digital fixture entered the capture/transport path.

Separating startup (first approximately5 s) from a continuous local steady window:

| Attempt | Local MONOTONIC window ms | Exact duration | Startup rejected / underrun callbacks / zero-fill | Steady rejected delta / underrun delta / zero-fill delta | Xrun delta |
| --- | --- | --- | --- | --- | --- |
| 1 | 373276897 ->373341905 | 65.008 s | 12000 /67 /6896 | 77520 /730 /77440 | 0 |
| 2 | 373370190 ->373435189 | 64.999 s | 13680 /81 /11024 | 67920 /567 /67952 | 0 |
| 3 | 373463691 ->373528692 | 65.001 s | 9360 /53 /6496 | 67680 /573 /67728 | 0 |

There is no zero-loss steady state. The ring-full rejection and zero-fill
continue after startup, so this cannot be closed by priming alone.

Same diagnostic bins as the earlier baseline: <1/<3/<7/<10/<20/<40/else ms.

| Attempt / local boundary | Observed units / windows | Interval bins | Max gap / burst | Missing positions / reorder / duplicate |
| --- | --- | --- | --- | --- |
| 1 sender | 12008 /12 | 421,8449,138,29,2958,12,0 | 25.059 ms /4 | 0/0/0 |
| 1 kernel | 11015 /11 | 2688,5157,440,65,2574,86,4 | 45.102 ms /8 | 0/0/0 |
| 1 pre-decoder receive | 12009 /12 | 4,8209,903,223,2607,60,2 | 43.887 ms /2 | 0/0/0 |
| 2 sender | 12009 /12 | 467,7539,1006,97,2894,5,0 | 22.222 ms /4 | 0/0/0 |
| 2 kernel | 12008 /12 | 1834,5449,1726,190,2715,81,12 | 60.756 ms /10 | 2/0/0 |
| 2 pre-decoder receive | 12009 /12 | 15,7216,1845,260,2613,47,12 | 54.621 ms /3 | 2/0/0 |
| 3 sender | 12007 /12 | 171,7117,1722,118,2866,12,0 | 23.285 ms /4 | 0/0/0 |
| 3 kernel | 12007 /12 | 1874,5094,2032,204,2730,69,3 | 48.012 ms /8 | 0/0/0 |
| 3 pre-decoder receive | 12012 /12 | 11,7075,1971,289,2610,53,2 | 46.173 ms /3 | 0/0/0 |

Stage window boundaries differ slightly; the bounded twelve-window owner
stops observation even if another stage has not emitted its final window.
Unequal diagnostic counts are not frame loss. Attempt2 has two missing
accepted packet/frame positions; the diagnostic does not prove whether raw
datagrams were lost, rejected before completion or crossed a configuration
boundary. No accepted reorder/duplicate is observed. The missing positions
do not cause a decoder reset in these sampled counters.

Steady maximum local authentication durations1.013/0.886/4.635 ms,
pump-lock waits0.209/0.157/0.216 ms, listener4.968/3.226/7.098 ms;
startup listener maxima14.281/14.487/23.279 ms. Receiver work is not free,
but these samples do not causally explain ingress gaps45-61 ms. Additional
network/kernel scheduling remains unresolved, not relabelled a proven
receiver dispatch defect.

Tablet AudioFlinger records all three DEBUG mono tracks under
`AUDIO_DEVICE_OUT_SPEAKER`, with track removal at each Disconnect. This is
technical route/ownership evidence, **not** human Tablet audibility. A41
human audibility remains the operator's earlier confirmation only.
Before the inherited DEBUG Media routing change the recorded Tablet sink used
EARPIECE; after the change the current tracks use SPEAKER. This route correction
is independent of unresolved cadence/rejection and never a product destination.

## 10. Android input baseline / actual shell privileges

| Host | UID2000 MODIFY_AUDIO_ROUTING / CAPTURE_AUDIO_OUTPUT / RECORD_AUDIO / MODIFY_AUDIO_SETTINGS | Empty AudioPolicy |
| --- | --- | --- |
| Tablet/API33 | true / true / true / true | register0, unregister successful |
| A41/API31 | false / true / true / true | register-1, no registered policy |

Tablet inputs before/after the empty policy: built-in bottom11/back12 (type15),
telephony14(type18), existing remote-submix21(type25,address0), FM16(type16),
echo-reference17(type28). Physical rates8000/16000/32000/48000/44100, mono/stereo
PCM16; existing submix48000 stereo PCM16.
A41: bottom8/back10(type15), telephony9(type18), existing submix13(type25,address0).
Physical rates8000/12000/16000/24000/32000/48000/11025/22050/44100, mono/stereo
PCM16; submix48000 stereo PCM16. Enumeration is not proof of normal-app access.

Ordinary MIC baseline consumer used only public AudioRecord, its existing
RECORD_AUDIO grant, no Warpnect PCM ring/controllers/protected receive access.
Tablet default route11/bottom, RMS about21.7-22.7; A41 default8/bottom, sampled
ambient RMS about67-70. Both stopped/released. Device IDs are observations,
not hardcoded product policy.

## 11. First-class selection experiment on Tablet

A fixed DEBUG injector matching **shell UID2000 only**, not consumer UID10212,
registered/started. Android enumerated a distinct dynamic remote-submix:
ID397, type25, address`1324113522:ap:2mixr:0`, product`remote-submix`.
Built-in inputs11/12 remained. This is not named product device integration.

Normal unprivileged AudioRecord(MIC) consumer explicitly selected this actual
enumerated input. `setPreferredDevice` returnedtrue, then AudioFlinger
`getInputForAttr` returned-1. `AudioRecord.start` returned-1 and first read-3
(`ERROR_INVALID_OPERATION`); **samples0 / RMS0 / tone amplitude0**. Last
reported routed device remained11/type15/bottom; no valid remote capture.
Android's own `restoreRecord_l` made three internal attempts; Warpnect/harness
called start once and did not retry it.

The first failed probe was a **TEST_HARNESS_DEFECT**: AudioTrack.Builder
`setContext` is absent on this API33 build. After inspecting the legacy
framework op-package path, one bounded corrected experiment used the older
builder's normal UID attribution; no application impersonation/framework patch.
The first failure remains in its own artifact.

## 12. Scoped injection control and cleanup

Separately, a ten-second policy matching the DEBUG Warpnect consumer UID10212
registered0. Its generated1193 Hz/amplitude2000 fixture was consumed through
ordinary Android AudioRecord, not Warpnect's PCM ring: dynamic device416,
type25,address`203500254:ap:3mixr:0`, RMS1413.67-1413.84, spectral amplitude
about1999.1-1999.6, peak2000. It wrote485384 frames. This is **scoped generated
framework evidence**, not acoustic Client/protected Session product evidence.
Normal default routing was overridden only for that bounded matching UID.
After unregister, Android restored route11/bottom; a separate post-cleanup
ordinary MIC probe passed. The transition window still contains samples read
before stop, so its RMS is not mislabelled as immediate physical-mic signal.
No dynamic probe remained, no physical microphone was deleted/disabled.

## 13. Android mechanisms / authoritative boundaries

- [AOSP API33 AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android13-release/services/audiopolicy/managerdefault/AudioPolicyManager.cpp): dynamic mixes use the existing remote-submix HAL, add profiles and address-qualified ports; this is not an arbitrary new HAL microphone. Explicit input preference and matching recorder mixes take different selection paths.
- [AOSP API33 AudioPolicyService](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android13-release/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp): unscoped submix capture is permission-gated; legacy/matching-recorder injection differs from unrestricted ordinary input selection. The observed-1 is consistent with this gate, not proof of a codec defect.
- [AOSP API33 AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/audio/AudioService.java): policy registration authorization is separate from receiver RECORD_AUDIO consent. Empty-policy success proves only a prerequisite.
- [AOSP VirtualAudioSession](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/companion/virtual/audio/VirtualAudioSession.java): companion virtual audio uses app-UID AudioMix routing, not a freely selectable microphone for every local app.
- [Audio policy configuration](https://source.android.com/docs/core/audio/implement-policy): normal HAL ports/profiles/routes are platform configuration, not writable app-local device objects.

AudioSystem connection-state methods resolved on both Hosts but were **not
invoked**. Announcing a port is not a PCM backend or authorization bypass.
No fake enumeration, AudioPolicy XML/HAL/vendor/system edit, generic Binder
surface or arbitrary device-connect experiment. Root may change helper
authorization but does not automatically change ordinary consumer permissions,
HAL profiles or Android input selection. No physical Root hypothesis is tested.

## 14. Per-device feasibility and product result

- Tablet/API33: **SCOPED_AUDIOPOLICY_INJECTION_ONLY** for this tested framework
  path. Enumeration succeeds, independent ordinary selection fails.
- A41/API31: **REQUIRES_DIRECTROOT_OR_STRONGER_PRIVILEGE** at the tested policy
  authorization prerequisite; this category is not proof that Root supplies
  the desired first-class input. No grant/escalation was attempted.
- S22/API36: NOT TESTED here; previous scoped experiment is not first-class proof.
- S9/S7: Client/acoustic roles, no Host virtual-input qualification.

Product result: **FIRST_CLASS_VIRTUAL_MICROPHONE_BLOCKED_ON_DIRECTSHELL** for
the investigated non-Root boundaries, not a universal Android impossibility
claim. Product provider remains NOT IMPLEMENTED; no remote-device Session
appearance/removal, default selection, ordinary-app acoustic ON/OFF, multi-app
compatibility or new-generation virtual input claim. All are NOT VALIDATED.

## 15. Security, lifetime and regressions

The actual microphone Sessions still use authenticated, anti-replay,
generation-bound Session protection and negotiated MicrophoneAudio. Client
RECORD_AUDIO consent remains required. Product destination is first-class
Android input; DEBUG playback and scoped fixed-signal probes are non-production.
The synthetic policy fixture is local-only; no remote microphone media is
sent outside authenticated Session transport.
Complete Host still requires exactly Video/SystemAudio/Input, not microphone.
Owner-bound DirectShell checkpoint715b215 is unchanged; no post-freeze provider
switch or hidden Shizuku/Root. Full background/power policy is not redesigned.
Final SystemAudio/cleanup observations are recorded in section16.

## 16. Automated tests, gates and final resources

Final software gates all PASS:

- `git diff --check`: exit0, line-ending normalization warnings only.
- `:app:ktlintCheck`, `:app:lintDebug`.
- `:app:testDebugUnitTest --rerun-tasks`: **959 tests /154 suites**, zero
  failures, errors or skipped tests. Actual XML total, not inferred hardware proof.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`,
  `:app:packageDirectShellProbeDex`.
- Native **Debug CTest22/22** (4.32 s), **Release CTest22/22** (2.67 s), fresh
  build root `app/build/cadence-native` with unchanged native CMake conventions.

Five additional JVM scenarios beyond the prior954 baseline:
`AudioCadenceDebugProbeTest.mediaFrameDurationDoesNotPretendToBeWallClockPacketCadence`,
`separatesMissingDuplicateAndReorderedPositionsFromArrivalGaps`,
`doesNotDeriveCadenceFromMissingOrBackwardClockSamples`, and
`AudioReceiveTimingDebugProbeTest.keepsKernelRealtimeIntervalsSeparateFromMonotonicProcessing`,
`missingNativeDiagnosticsAreNotReportedAsMeasuredTiming`.
Existing bounded cadence/PCM/destination/mono/ring/lifecycle/owner tests remain.
The native receiver test now retains packet identity and asserts no invented
Windows kernel stamp. Android instrumentation observes ordinary MIC baseline
and explicit dynamic-device selection: `OK(1 test)` proves fixture completion,
not successful input qualification or speaker audibility.

The first final Gradle gate attempt failed import-order ktlint (format/check
ordering). It is retained as `cadence-final-gates.log`; the corrected separate
run is `cadence-final-gates-2.log`. The old native build cache failed MSVC
C1090/PDB API error3, retained in `cadence-native-debug-build.log` with the
dirty cache. A fresh directory then built/tested both configurations. Neither
failure was hidden or reclassified as a product/hardware PASS.

One additional normal SystemAudio Session on the same continuously enabled
Tablet Host: UTC22:55:07.180 Streaming ->22:55:59.569 Ready/Waiting.
Mic DEBUG opt-in OFF, no Shizuku. An eight-second Warpnect-owned997 Hz
AudioTrack source wrote388104 frames. S9 decoded PCM windows had RMS0/peak0
before, RMS1203.53 and1390.76/peak2152 and2139 during, RMS0/peak0 after.
This is **technical regression PASS**, not new human audibility or loss-free
quality proof. REMOTE_SUBMIX input source8/output route and the release patch
are in AudioFlinger diagnostics; existing REMOTE_ONLY policy is unchanged.
Video reached ordinary decoder/Surface handoff Streaming; Input gateway and
receiver were acquired/released with no errors, no new human injection claim.

Final normal Disable Host at local00:56:49.576 withdrew lifecycle/publication;
UI Ready, S9 Ready, active Session none. Exact-serial process census shows
only appPID12865, **DirectShell feature helpers0**, probe processes0, temporary
bootstrap secret filenames0. Dynamic probe addresses are absent from the final
audio-policy dump. Each DEBUG playback track has a remove event; the SystemAudio
REMOTE_SUBMIX patch is released. Numeric microphone/receiver sampling ends
with Session ownership; no generated tone or capture remains active. The DEBUG
Wi-Fi request shows acquired1/released1, no held request. Logcat collectors are
stopped by verified PID/command, not broad process kills.

App FD count188 after teardown (11 socket FDs,5 ashmem FDs) is a single census,
not a trend or proof of global leak-freedom. The FD list is preserved, with
framework/JAR, graphics, Binder/socket resources not automatically attributed
to Warpnect leaks. No
pre/post repeated FD classification claim is made; semantic media/helper
cleanup is what was verified.

Documentation updated: this report, `CurrentNonRootDebt.md`, Phase6.5 `README.md`,
`FutureRoadmap.md`, `AndroidCompatibilityMatrix.md`,
`MicrophoneAudioE2EQualification.md`, `MicrophoneAudioVirtualInputArchitecture.md`,
and the liveness/quality report's dated follow-up. Documentation edits after
the frozen APK do not change the hardware-tested source. No Root-lab tooling
changed; no Root-lab gate applies to these generated non-Root diagnostics.

## 17. Anomaly ledger

| Preserved result | First trustworthy boundary | Classification | Action / comparison |
| --- | --- | --- | --- |
| Tablet initially unavailable for Host | Wireless Debugging setting0 | ENVIRONMENTAL_PRECONDITION | Operator enabled it; baseline comparison on A41 continued without privileged toggling |
| Baselines / diagnostic playback quality | Ring-full decoded rejection and callback zero-fill, continuing after startup | INCONCLUSIVE | Source of additional disturbance now includes kernel arrival; no guessed buffer/pacing fix; old FAIL remains |
| Diagnostic harness before Connect | Desktop ADB5037 timeout / empty UI | ENVIRONMENTAL_PRECONDITION | One explicit bounded new attempt succeeded; no daemon reset, Host toggle or hidden Connect retry |
| First selection fixture | Missing API33 AudioTrack.Builder.setContext | TEST_HARNESS_DEFECT | Legacy builder attribution corrected; first FAIL retained, one new bounded selection test |
| Ordinary dynamic input selection | AudioFlinger getInputForAttr=-1 / AudioRecord read=-3 | PLATFORM_CAPABILITY_LIMIT | Scoped matching-UID control succeeds; do not rename that as first-class input |
| A41 policy registration | MODIFY_AUDIO_ROUTING false / register-1 | PLATFORM_CAPABILITY_LIMIT | Tablet same prerequisite succeeds; no privileged grant/rooting workaround |
| Close-time ADB warnings | Library Socket closed / DestroyFailedException after successful launch/replacement | EXTERNAL_PLATFORM_OR_PROVIDER_FAILURE | Normal Ready/Waiting and owner replacement remained successful; existing warnings retained |
| Native build in old cache | MSVC C1090 PDB API error3 | ENVIRONMENTAL_PRECONDITION | Old generated directory preserved; same source configured in a fresh generated build directory |
| First software gate invocation | ktlint import ordering, check ran before requested formatter | TEST_HARNESS_DEFECT | Formatter completed; final gate invocation excludes concurrent format/check; failed log retained |

## 18. Worktree / evidence index / deferred queue

New source is bounded diagnostics and DEBUG policy/consumer investigation,
not a claimed cadence fix or first-class input provider. Inherited destination
guards, DEBUG Media route and PCM observation remain uncommitted. Tests/docs
remain reviewable. Generated app reports/APKs/DEX, native builds, diagnostic
scripts/logs are excluded from source checkpoint assets. Unrelated prior files
and Git lock remain untouched. No raw microphone audio recording was saved.
Final `git status`: staged0, unstaged184, untracked62. Classification:
26 source/DEBUG paths (including the pre-existing line-ending-only Input path),
6 test paths,8 docs,159 generated native paths,47 unrelated prior artifacts.
Generated app build/evidence paths are ignored and not counted as source.
HEAD/master/upstream are unchanged; the existing empty `.git/index.lock`
still has its original2026-09-30 timestamp. No checkpoint is created for this
PARTIAL slice.

Evidence under `app/build/reports/`: `cadence-baseline-*`, `cadence-timing-*`,
`cadence-radio-*`, `cadence-virtual-prerequisite-*`,
`cadence-shell-only-input-tablet[/-2].txt`,
`cadence-dynamic-input-consumer-tablet.txt`, `cadence-input-selection-tablet.log`,
`cadence-scoped-input-tablet.txt`, `cadence-scoped-consumer-tablet.txt`,
`cadence-standard-input-*`, plus build/gate/native logs. Temporary
`CadenceSession.ps1` is generated harness output, not product tooling.
Final evidence: `cadence-final-sessions.txt`, `cadence-final-<serial>.log`,
`cadence-final-systemaudio-session.txt`, AudioFlinger/audio-policy/Wi-Fi/FD
after-teardown dumps. Final JVM XML and lint report are the standard app
build outputs. The temporary PowerShell harness parses with zero errors;
Root-lab tooling was not touched. No raw PCM/voice media artifact is saved.

Deferred unchanged: HOST_AND_REMOTE SystemAudio; Host display policy;
RFC-005J/005K; physical DirectRoot; Bluetooth/USB/headset input qualification;
broad third-party app compatibility; full background/timeout audit; AEC;
provider recovery/migration; Phase 7 cross-device/physical latency measurement.

## 19. Completion discipline / next single task

Cadence: **MICROPHONE_AUDIO_RECEIVER_CADENCE_PARTIAL**, not fixed/validated.
Product architecture: **FIRST_CLASS_VIRTUAL_MICROPHONE_BLOCKED_ON_DIRECTSHELL**
for the exact tested boundaries. Combined result **PARTIAL**. Do not claim
production readiness, quality CLOSED or a virtual microphone implementation.
Recommended single next task: **MicrophoneAudio capture/send-to-kernel cadence
closure**, using sequence-correlated local transmit/receive diagnostics to
separate source batching from radio/driver delivery before choosing a minimal
pacing/quality correction. This recommendation is not implemented automatically.
