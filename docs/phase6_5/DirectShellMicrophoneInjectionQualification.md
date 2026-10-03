# Portable DirectShell microphone injection - ordered gate investigation

Current [final exact-path closure](DirectShellGlobalMicrophoneFinalClosure.md)
passes the primary quality gate3/3 on C782DD9D, with no UID rule and natural
MIC/VOICE_COMMUNICATION/CAMCORDER selection/restoration. One portable backend:
A41 actual AudioService MODIFY_AUDIO_ROUTING permission blocks registration;
S7 product context adapter is unsupported, while older-context diagnostics reach
the same routing permission boundary. No generic equivalent non-root fallback
or model branch is added. Valid priority set/clear/helper-loss rollback remains
NOT TESTED; natural routing needs no forced role.1022 JVM/22+22 native gates pass.
All following partial campaigns remain historical and no release is promoted.

Current [residual quality continuation](MicrophoneReceiverSchedulingQuality.md):
network prerequisite restored. One-burst microphone admission corrects whole
block loss near a callback, but81F10D24 cycle1 FAIL on residual starvation;
2/3 NOT RUN.1017 JVM/22+22 native pass. Portability/forced priority remain
quality-gated; one portable UID-free policy, no additional provider/OEM path.

Latest [receiver scheduling continuation](MicrophoneReceiverSchedulingQuality.md):
final43FB71D6 first counted cycle FAIL on lost peer/Tablet DirectShell prerequisite,
not3/3 quality closure. One stable priority diagnostic is preliminary evidence.
1016 JVM/22+22 CTest pass; no new OEM/version/provider implementation. A41/S7
portability and real priority setter/abnormal cleanup remain quality-gated.

Latest [source-clock/primary quality continuation](MicrophoneSourceClockRecovery.md)
keeps portability and priority behind the unclosed quality gate. One counted
D28F6136 cycle FAIL; cycles2/3 NOT RUN. Corrected source pacing and a stable80s
continuous-reader diagnostic are not enough to promote global quality. No new
provider/API/OEM path or priority role is added. Tablet prerequisite is lost
again; manual Debug wireless restoration is required after automatic Wi-Fi
re-association. Existing A41/S7 permission and priority evidence below is retained.

Current [2026-10-03 campaign](MicrophoneConsumerClockContinuity.md): global MIC
functional3/3 on final66A8B330, but quality PARTIAL due recurring continuous
DEBUG and AudioTrack underruns in cycle3. No quality-gated release/portability
implementation. Bounded real-shell framework probes add precise capability data:
A41 registerAudioPolicy-1 with MODIFY_AUDIO_ROUTING denied; S7 legacy shell
context/rule construction works, then AudioService denies the same permission.
Tablet invalid-preset priority setter/clearer pass Binder permission enforcement,
but no valid preference is applied or crash-safe priority cleanup validated.
Natural MIC/VOICE_COMMUNICATION/CAMCORDER input is reconfirmed. Gates1007 JVM/
161 suites, CTest22/22 each; source remains uncommitted.

Historical preceding continuation:

Latest [consumer-transition continuation](MicrophoneConsumerTransitionQuality.md)
keeps portability behind the unclosed primary quality gate. The fixed40ms FIFO
candidate completes two S9 ->Tablet Sessions with ordinary MIC signal and
restoration; the third fails during platform path/radio loss. Quality PARTIAL,
no new A41/S7 capability or capture-priority setter evidence. The earlier
Tablet-unavailable/guard-unvalidated state below is historical. Full software
gates1004 JVM/161 suites and native Debug/Release22/22 pass.

Historical preceding continuation:

Newest [global-input quality investigation](DirectShellGlobalMicrophoneInjectionQuality.md)
is PARTIAL, preserving all accepted/failing APK evidence below. After manually
restored Wireless Debugging, the first counted111.017s candidate cycle failed
quality. Opening-flush and smaller track-threshold experiments failed and were
reverted. The new consumer-window guard remains physically unvalidated; a fresh
primary campaign is blocked by bidirectional S9/Tablet peer LAN failure, not by
Wireless Debugging, which is ON. Real997Hz ordinary MIC reception/restoration
still works on the completed candidate run; no quality token was earned.
A41 alternatives/S7 adapters/priority setter are deferred until Part A passes.

Current follow-up: [global integration and preserved failures](DirectShellGlobalMicrophoneInjection.md).
The final CF5C311C APK physically validates the one no-UID DEBUG backend on
Tablet/API33: three60s ordinary MIC consumers in three fresh protected Sessions,
plus separate VOICE_COMMUNICATION/CAMCORDER consumers, receive the real S9
microphone997Hz OFF/ON/OFF signal through Android. Natural routing selects
RemoteSubmix; normal Disconnect and intentional helper loss restore physical MIC.
**DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED** applies to that exact
functional boundary, not release readiness or lossless quality. Final MIC1
late DEBUG underruns and IPC startup/consumer-absent backpressure remain open;
MIC2/3 DEBUG counters are0. A41 is permission-blocked; S7 stops at the existing
context adapter. Forced priority remains NOT TESTED, getter-only research done.
The historical zero/scoped probes below are not the basis for this new token.

Historical ordered-gate baseline, 2026-10-01: global injection was NOT IMPLEMENTED.
The subsequent [autonomous cadence investigation](MicrophoneAudioCadenceRootCause.md)
closes DEBUG playback quality on the exact S9/API29 -> Tablet/API33 path:
three final Sessions above60s under one Enable Host, rejection/underrun/xrun0.
The measured correction uses an80ms bounded PCM ring with70ms initial reserve
and a warm output clock; nominal priming adds65ms, not zero latency. Kernel
gaps remain. Global policy/priority work was deliberately NOT STARTED in that
task and requires separate review. This is not product microphone qualification.

Earlier resumed final-APK campaign: **3/3 lifecycle PASS, 0/3 quality PASS**.
See section32 for the complete post-pacer evidence. Part B/C integration had
not begun because recurring steady-state rejection/underrun remained. Sections1-30
preserve the initial turn's observations; earlier failures and successful scoped
experiments remain historical, not silently replaced by the resumed campaign.

## 1. Repository baseline

Branch `codex/directshell-production-runtime-integration`; starting/current HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`. Existing checkpoints: Session lifecycle
`92347d0`, microphone technical E2E `809b7dd`, owner-bound liveness `715b215`.
Master `032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`origin/codex/directshell-production-runtime-integration` at
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, ahead19/behind0.
At reconciliation: staged0, unstaged184, untracked62. Partial microphone work,
tracked generated native output, older unrelated artifacts and the existing
empty `.git/index.lock` were preserved. No checkpoint, push, reset, discard,
clean, merge, rebase or force-push. This slice is not a validated commit boundary.

## 2. Cadence root cause: first proven boundary

`AndroidMicrophoneAudioCaptureController.captureLoop`, `WarpnectMicrophoneCapture`
thread: blocking AudioRecord reads request240 mono frames/480 bytes, but complete
in four-chunk groups. In a representative steady5-second window there are750
reads shorter than1ms and250 blocking10-20ms reads. Maximum blocking read17.941ms;
post-read timestamp/tracker/numeric bookkeeping0.644ms; synchronous sink2.809ms.
Three quick reads follow one approximately20ms read. This is observed at the
AudioRecord API boundary, not proof of a particular internal HAL implementation.
S9's actual record buffer is2880 frames/60ms. It was not resized.

Encoder input/output and synchronous protected/socket sends inherit the grouping.
The native send path has no pacing worker or audio queue: packetize, protect,
`UdpSocket.send_to`. The receiver's select20ms is a maximum wait, not fixed20ms
batch polling; readability wakes it. Authentication and dispatch are later stages.
Source batching is proven; it is **not yet proven to explain all playback loss**.
Packet duration is not an Android guarantee of equally spaced read completion.

Baseline aggregate intervals, bins `<1,<3,<7,<10,<20,<40,else` milliseconds:

| Local boundary | Observations | Interval bins | Maximum gap |
| --- | ---: | --- | ---: |
| Microphone read complete |12007|2488,6003,515,19,2975,6,0|24.559ms|
| Encoder input |12007|2591,6045,370,11,2981,8,0|24.566ms|
| Encoder output |12008|2885,5996,129,7,2972,18,0|25.711ms|
| Protected submit completion |12008|3121,5794,94,6,2971,21,0|25.920ms|
| Socket completion, steady windows1-11 |11005 intervals|2616,5547,94,6,2721,21,0|25.913ms|
| Host kernel receipt, REALTIME |11005|4524,3334,399,83,2466,187,11|74.808ms|
| Host socket read, MONOTONIC |12003|18,8291,823,188,2596,72,14|70.258ms|
| Host authenticated, MONOTONIC |12003|20,8292,820,185,2599,72,14|70.258ms|
| Before decoder |12003|19,8411,700,168,2618,72,14|70.629ms|

Socket all-window protection/send maxima3.071/3.271ms. Window0 includes the
stream-config-to-first-capture127.045ms gap; it is not a steady audio gap.
Sender frame-position continuity: missing0/reordered0/duplicates0. Sampled
receiver metadata shows8 holes, no reordering/duplicates. This alone does not
establish UDP loss: accepted-frame diagnostics are not a capture of every raw
packet or every authentication rejection. Different stage window counts are
not packet-loss counts. No cross-device timestamp subtraction was performed.
Actual radio TX timestamps and packet-correlated radio delay remain unobserved;
`send_to` completion is not RF transmission. Kernel receipt remains independently
more irregular; do not conclude that source pacing solves radio/receiver jitter.

## 3. Minimal candidate change

New `MicrophoneChunkPacer`, instantiated per microphone capture lifetime, derives
deadlines from PCM frames/sample rate. The same capture thread waits before
synchronous encoder submission; no additional PCM buffer, queue or worker.
Spurious wakeups recheck the deadline and stop ownership. A scheduling gap larger
than one period reanchors rather than causing unbounded catch-up. Capture PTS
still describes capture, not send time. No SystemAudio source change.

48kHz mono PCM16,5ms/240 frames/240 samples/480 bytes,64kbit/s remain unchanged.
The negotiated Opus packet target is40 bytes, not480 PCM bytes. Ring remains
four240-frame slots/960 frames/20ms; start threshold remains240 frames/5ms.
Oboe remains AAudio LowLatency/Shared, burst256, buffer512, backend capacity2048
on the observed Tablet path. DEBUG playback remains Media/Speech/SPEAKER.
Pacing spreads an already delivered four-chunk batch over15ms; this is a real
bounded scheduling/latency tradeoff, not free latency reduction. No quality
closure or universal timing guarantee is claimed. The change is uncommitted.
After pacing is enabled, capture timing `max_sink_ns` includes the deliberate
wait plus synchronous sink work; it must not be interpreted as encoder time.
The pre-change sink timing above did not contain this wait.

## 4. Counted physical attempts and quality gate

| Attempt | Build / path | Result |
| --- | --- | --- |
| Source diagnostic baseline1 |6180A8BB..., S9 -> Tablet, requested65s, Streaming06:10:13.463Z to Ready/Waiting06:11:33.067Z|Lifecycle PASS; quality FAIL|
| Paced Session1 |6F520EF7..., same pair, one Connect at06:16:29.423Z|FAIL before a bounded steady interval could be established; lifecycle control suspended, microphone deliberately stopped|
| Local physical capture1 |Final E080A820..., S9 normal AudioRecord -> diagnostic sink,3s|PASS; not a protected Session or playback-quality acceptance|

Baseline last Client snapshot: captured/encoder PCM3358080 frames, encoded13992
units, sent13993 datagrams including config, WouldBlock0/send failure0. Last Host
snapshot: received14909 datagrams, decoded14908 units, PCM3491760, accepted3386160,
rejected105600 frames, ring-full drops491, underrun callbacks978/zero-fill120272,
xrun0, occupancy0 at snapshot/HWM960. These snapshots are asynchronous; native
decoder/playback counters can reset during early freshness resets. Do not
subtract endpoint totals as wire loss or equate lifetime decode-unit and reset
PCM counters. Rejection/zero-fill continue after startup.

The paced failed attempt reached390 encoded units/93600 captured frames in the
last Client snapshot; receiver later stopped at535 units. Post-stop underruns
are **not** steady-state quality measurements of a working microphone stream.
At the end of the initial turn, no three60-second final-APK Sessions were completed. Playback quality status:
**PARTIAL**, with the old failures retained, not changed to PASS.

## 5. Post-change local evidence

Final S9 physical probe:142320 PCM frames,134047 nonzero samples, RMS27.1424,
peak142; ordinary VOICE_RECOGNITION AudioRecord, input type15/built-in mic,
48kHz mono, buffer2880 frames.592 delivery intervals all in3-7ms bin; maximum
5.366692ms. No tone-frequency claim from ambient signal. Clean stop/close,
instrumentation1/1 PASS. This validates local paced delivery only, not Opus,
network quality, Host speaker audibility or virtual microphone injection.

## 6. Portable microphone architecture

Product hypothesis: **one** authenticated generation-owned DirectShell capability
using recorder mixes with capture-preset criteria. DEBUG playback remains a
separate diagnostic destination. No new production capability/class/interface
was introduced because Part A has not passed. Existing product destination stays
unimplemented/fail-closed; microphone remains optional, not a Complete Host cell.

## 7. AOSP mechanism and Java boundary

Read-only inspection of the Android13
[AudioPolicyManager record-injection test](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android13-release/services/audiopolicy/tests/audiopolicymanager_tests.cpp)
shows MIX_TYPE_RECORDERS, LOOP_BACK, IN_REMOTE_SUBMIX, and capture-preset criteria
MIC/CAMCORDER/VOICE_COMMUNICATION. Virtual-source playback feeds the registered
submix address; ordinary matching input requests select its extraction port.
This native policy-manager test is not a Java/app_process/OEM qualification.

The relevant Java boundary is
[AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/audiopolicy/AudioMixingRule.java):
capture-preset AudioAttributes criteria select recorder mix semantics. Candidate
framework access is encapsulated reflection for capture-preset attributes,
mix/rule builders, AudioPolicy registration and createAudioTrackSource; resolve
actual signatures/results centrally before implementing. No generic audio-policy
or device-connection RPC was added. Existing scoped probes are not reused as
global proof. Optional input priority must remain a separate capability.

## 8. UID independence

Required future global rule set: application UID criteria **NONE**. Current old
scoped probe still deliberately contains a UID criterion and remains DEBUG-only.
It does not meet global acceptance and was not reclassified. No global policy
was registered in this task, so no runtime no-UID policy PASS is claimed.

## 9. Tablet MIC / VOICE_COMMUNICATION / CAMCORDER

All three: global policy registration, ordinary unpreferred AudioRecord start,
routed input, remote acoustic signal, and Disconnect fallback **NOT TESTED**.
Prior Tablet UID2000 permission/empty-register0 and matching-UID tone proof are
retained, not substituted. Tablet lost Wi-Fi during the paced attempt; Android
subsequently disabled Wireless Debugging. Final setting0 blocks fresh normal
DirectShell acquisition. No privileged re-enable or manual runtime arm.

## 10. A41 portability comparison

Same future capability, no workaround. Prior exact API31 evidence:
MODIFY_AUDIO_ROUTING denied; CAPTURE_AUDIO_OUTPUT, RECORD_AUDIO,
MODIFY_AUDIO_SETTINGS granted; empty policy register-1. This is a known
registration-permission prerequisite, not a new global experiment. Current
Wireless Debugging0 prevented a fresh normal Host comparison. No UID/OEM branch.

## 11. S7 / exact environments

S7/API26 connected, not used/unlocked/probed. No API26 alternate implementation.
S9 SM-G960F Android10/API29, fingerprint
`samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys`.
Tablet X700_EEA Android13/API33,
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`.
A41 SM-A415F Android12/API31,
`samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys`.
S22 absent; no API36 inference or waiting for it.

## 12. User configuration

No new product manual UID/app selection, mic selection, PC/ADB dependency or
settings step was added. Integration is **not implemented**, so zero-extra-setup
working product behavior is not yet validated. Existing DirectShell Wireless
Debugging remains a real prerequisite; the operator was asked to restore it,
not bypassed. No response/re-enable is assumed.

## 13-16. Coexistence, default selection and optional priority

New before/during/after global input enumeration: NOT TESTED. Prior scoped report
shows built-ins remaining enumerated, but it is not a global-lifecycle result.
Ordinary MIC/VOICE_COMMUNICATION/CAMCORDER default selection with no preference:
NOT TESTED under global policy. `setPreferredDevicesForCapturePreset`, preferred
roles, permission probing, forced MIC priority and automatic clear: NOT TESTED.
Part C was not entered; successful scoped registration does not prove priority.
Do not silently replace physical mic globally or expose HOTWORD/other presets.

## 17. Portability

No model/manufacturer/OEM branch or API-specific microphone product path added.
No version adapter added for global injection; actual API differences are still
to be probed centrally. Never infer support from API33/Tablet name. Runtime
authorization and backend evidence, not an allowlist, must choose support.

## 18. Anomalies and cleanup

| Attempt / boundary | Classification | Comparison / action |
| --- | --- | --- |
| Baseline continuing ring-full rejection/zero-fill | INCONCLUSIVE | Source grouping now proven, but full quality causality remains open; baseline lifecycle PASS is not quality PASS |
| Paced Session control suspension, S9 MONOTONIC2078741.774s; MIC stopped2078741.884s | INCONCLUSIVE | Closest baseline sustained Session succeeds. Control silence/Session suspension precedes confirmed Wi-Fi disconnection; do not assert pacing or Wi-Fi as the sole first cause. No bounded Session reproduction was possible after prerequisites disappeared |
| Tablet WLAN disconnect256685.335s, AdbDebuggingManager disables Wi-Fi ADB256685.385s; final Tablet and A41 setting0 | ENVIRONMENTAL_PRECONDITION | Blocks fresh normal DirectShell validation, not proof of audio-policy incompatibility. No hidden retry/toggle/Root workaround |
| First ktlintFormat mapped-file Windows error | ENVIRONMENTAL_PRECONDITION | One explicit later format succeeds; no production source change to hide tooling failure |
| First local histogram aggregation PowerShell parser error | TEST_HARNESS_DEFECT | No device operation occurred; syntax corrected and aggregate recomputed |

Baseline Disconnect returns S9 Ready/Tablet Waiting. Failed paced Session stopped
Client mic through existing lifecycle semantics; Tablet returned Waiting, followed
by normal Disable Host. Helpers/control ownership released; collectors stopped
by verified PID/command, no broad kill. No global policy, virtual input, preferred
role or injection ring was created, so no new injection resource can orphan.
Local capture stop/close PASS. Final process/name census finds no DirectShell or
Warpnect privileged feature service on Tablet/S9; app files contain only
`profileInstalled`, no bootstrap secret file. Tablet app24572 remains alive and
Host disabled; the instrumentation runner ended the S9 target process normally.
No logcat collector remains. No leak-free FD trend or new abnormal injection
cleanup qualification is claimed. No automatic same-provider recovery added.
In the failed Session, post-suspension zero-fill continued until the existing
teardown path closed the sink. Tablet-local MONOTONIC suspension256676.963s,
AudioTrack stop256685.928s, and AAudio close returned0 at256685.942s establish
bounded sink release8.979s after suspension in the same clock domain. The final
AudioFlinger speaker mixer reports0 active tracks; earlier track records are
removal history, not active output. This proves sink teardown for that attempt,
not working steady playback or a new global-injection cleanup qualification.
The first control-suspension cause remains unresolved for one bounded diagnostic
reproduction when normal runtime prerequisites return.

## 19. Security

Normal authenticated/protected Session remains the source for remote PCM.
Pairing, trust, anti-replay, generation, payload and SCL protection unchanged.
No raw microphone recording, unprotected media shortcut, generic privileged
audio injection surface, Root command or permission escalation. Late media still
uses existing Session ownership; the pacer instance never persists across it.

## 20-21. SystemAudio and DirectShell regressions

New bounded physical SystemAudio/Video/Input smoke: NOT COMPLETED after external
precondition loss. Automated regressions pass. No change to SystemAudio REMOTE_ONLY
semantics or owner-bound liveness at715b215. The source baseline used normal
SINGLE/DIRECT_SHELL with all three required providers, UID2000; Shizuku was not
the Host plan. Legacy S9 readiness may inspect its optional provider separately.

## 22-25. Tests, totals and gates

New JVM tests: blocking-read/post-read/delivery timing separation; invalid local
clock order and bounded output; four-chunk pacing; jitter versus bounded gap
reanchor; frame-not-byte accounting and fresh per-Session pacing origin.
Modified existing physical mic instrumentation adds only numeric local delivery
histogram/max, not a speaker or global-injection assertion.
Exact new suites: `MicrophoneCaptureTimingDebugProbeTest` (2 tests) and
`MicrophoneChunkPacerTest` (3 tests). Modified physical suite:
`AndroidMicrophoneAudioCaptureInstrumentationTest` (existing capture test).

**964 JVM tests /0 failures /0 errors /0 skipped**,156 suites. Complete Gradle
gate invocation with testDebugUnitTest --rerun-tasks succeeds. After the final
AndroidTest-only numeric probe edit, ktlintCheck/lintDebug/assembleDebug/
assembleDebugAndroidTest/packageDirectShellProbeDex also succeed. `git diff
--check` PASS. Android DEBUG native diagnostics build for all configured ABIs.
Native Debug CTest22/22 and Release22/22 PASS, existing generated native artifacts
preserved; tests were rebuilt in `app/build/cadence-native`. No Oboe buffer change.

Final debug APK SHA256:
`E080A820000DCB33DFE8487B24FF349EC06D14808CF904C1B9A0873A98ABAECF`.
It ran the local S9 probe, **not** the missing final three E2E Sessions. Previous
6180/6F52 physical attempts remain separate APKs. No claim from source equivalence
substitutes for an exact final-APK acceptance campaign.

## 26-27. Documentation / worktree / evidence

Updated current debt, README, FutureRoadmap, compatibility matrix, microphone
E2E/destination/cadence reports and this report. New source/test diagnostics and
candidate pacing remain uncommitted with inherited partial work. Generated
native output, APK/DEX, test reports, collectors, UI dumps and local machine
state are not source checkpoint assets. Prior unrelated artifacts/Input
line-ending status and Git lock remain untouched.

Exact documentation files updated in this follow-up:

- `docs/phase6_5/CurrentNonRootDebt.md`
- `docs/phase6_5/README.md`
- `docs/FutureRoadmap.md`
- `docs/testing/AndroidCompatibilityMatrix.md`
- `docs/phase6_5/MicrophoneAudioE2EQualification.md`
- `docs/phase6_5/MicrophoneAudioVirtualInputArchitecture.md`
- `docs/phase6_5/MicrophoneAudioCadenceAndVirtualInput.md`
- `docs/phase6_5/DirectShellMicrophoneInjectionQualification.md` (new report)

Final status: staged0, unstaged189, untracked67;256 paths comprising32 source/
DEBUG,9 tests,9 docs,159 pre-existing generated-native paths and47 unrelated
prior paths. Of the9 docs,8 were updated in this follow-up; the liveness report
was already modified in the inherited partial work. Source diagnostics include
native socket timing and capture/encoder probes; no microphone-policy product
tooling was added. Existing Git lock length0/timestamp2026-09-30T20:21:31 remains.

Evidence under `app/build/reports/`: `portable-mic-source-*`, `portable-mic-paced-*`,
`portable-mic-s9-local-capture*`, build/format/gate/native logs. Temporary
`CadenceSession.ps1` remains generated harness output. No raw microphone content.

## 28. Capability matrix

| Exact Host | Global capture-preset injection | Priority | Evidence boundary |
| --- | --- | --- | --- |
| Tablet/API33 | INCONCLUSIVE / NOT TESTED | INCONCLUSIVE / NOT TESTED | Ordered Part A gate not passed; old scoped registration works |
| A41/API31 | Known registration prerequisite PERMISSION_BLOCKED; global path NOT TESTED | NOT TESTED | Earlier shell permission denied; fresh normal runtime unavailable |
| S7/API26 | NOT TESTED | NOT TESTED | Optional, not used |

These are data for one proposed architecture, not device-specific implementations.

## 29-30. Completion discipline

**MICROPHONE_AUDIO_RECEIVER_CADENCE_PARTIAL**.
Capture-side grouping is root-caused and local paced delivery physically tested;
complete playback quality is not validated/qualified. Global DirectShell
microphone injection is **NOT IMPLEMENTED / NOT TESTED** in this ordered slice.
Do not emit either a global-injection validated token or a platform-blocked token
from the earlier UID-scoped result. No microphone production-ready claim.

## 31. One recommended next task / deferred requirements

**MicrophoneAudio post-pacer socket-send-to-kernel cadence root-cause closure**:
the resumed final-APK campaign in section32 is complete and fails quality.
Separate remaining network/driver/platform arrival irregularity from local
receiver/playback defects before another minimal correction. Only a passed
quality gate permits this task's portable capture-preset injection slice.
No unrelated item is started automatically.

HOST_AND_REMOTE SystemAudio, Host display policy, RFC-005J/005K, physical DirectRoot,
Bluetooth/USB/headset inputs, broad third-party compatibility, AEC, recovery/
migration and Phase7 general optimization remain deferred. No rooting/flashing,
SELinux/system/vendor/HAL mutation, arbitrary AudioPolicy modification or model
allowlist. Complete Host remains Video/SystemAudio/Input.

## 32. Resumed final-APK campaign after prerequisite restoration

The operator restored Wireless Debugging on Tablet and A41. Both settings were
verified1, with no privileged toggle or manual runtime arm. Branch/HEAD/master,
source and tests are unchanged from section1. Both S9 and Tablet installed
base.apk hashes match final `E080A820000DCB33DFE8487B24FF349EC06D14808CF904C1B9A0873A98ABAECF`.
The same no-extra-queue pacer, 48kHz mono PCM16/240-frame5ms/64kbit/s contract,
20ms ring, Oboe settings, codec and socket buffers were retained. No radio-mode,
priority, priming or buffer experiment was performed in this resumed campaign.

### 32.1 Counted normal product path

One normal Enable Host selected `SINGLE / DIRECT_SHELL`, Video/SystemAudio/Input
all DIRECT_SHELL, `shizuku_started=false`. Three explicit Find/Connect/Disconnect
cycles ran under that continuously enabled Host. Each harness sleep was65s
after the observed Streaming state; first/last Host Streaming telemetry spans
approximately69.7-69.9s, excluding subsequent teardown. UI automation overhead
is not an audio latency measurement. No hidden Connect retry, Host toggle,
app restart or Wireless Debugging toggle occurred between these three cycles.

| Cycle | Connect attempt UTC | UI Streaming UTC | Ready/Waiting UTC | Sampled Host Streaming duration | Lifecycle | Quality |
| --- | --- | --- | --- | ---: | --- | --- |
|1|13:30:01.793|13:30:19.781|13:31:39.172|69.739s|PASS|FAIL|
|2|13:31:39.185|13:31:58.656|13:33:17.578|69.891s|PASS|FAIL|
|3|13:33:17.579|13:33:31.958|13:34:50.891|69.904s|PASS|FAIL|

The earlier6F520EF7 control-suspension attempt remains FAIL/INCONCLUSIVE.
The new successful lifecycle campaign does not identify or erase its cause.

### 32.2 Numeric pipeline snapshots

| Cycle | Captured PCM frames | Encoded units | Sent datagrams incl config | Received incl config | Decoded units | Decoded PCM | PCM accepted | PCM rejected | Underrun callbacks | Zero-fill frames | Xrun | Sampled occupancy min/max | Ring HWM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
|1|3357120|13989|13990|13978|13976|3354240|3301440|52800|278|48464|0|0/656|960|
|2|3356160|13985|13986|13980|13978|3354720|3303600|51120|282|49952|0|0/912|960|
|3|3356880|13987|13988|13983|13981|3355440|3311040|44400|252|43264|0|224/720|960|

Client/Host snapshots are independently sampled; their endpoint totals must not
be subtracted to manufacture packet loss. Sender reported WouldBlock/send
failures0. All12 bounded received sequence windows per cycle show missing0,
reordered0, duplicate0. This is sampled sequence continuity, not proof that no
packet anywhere was lost. Native socket timing logs do not expose these three
counters; absent values are UNKNOWN/null rather than fabricated0. Occupancy
min/max is from sparse snapshots only; cycle3's nonzero sampled minimum does
not contradict zero-fill evidence that the ring was empty between samples.

### 32.3 Startup versus recurring steady-state loss

The steady interval begins at the first Host snapshot at least10s after first
Streaming; telemetry cadence places it approximately14.7-14.9s after startup.
No decoder/rejection/underrun counter reset occurred within these intervals.

| Cycle | Steady Host MONOTONIC interval, seconds | Rejected start -> end / delta | Underrun start -> end / delta | Zero-fill start -> end / delta | Xrun delta |
| --- | --- | --- | --- | --- | ---: |
|1|282689.359 -> 282744.362 (55.003s)|17520 -> 52800 /35280|74 -> 278 /204|12688 -> 48464 /35776|0|
|2|282789.309 -> 282844.306 (54.997s)|15360 -> 51120 /35760|77 -> 282 /205|14048 -> 49952 /35904|0|
|3|282881.409 -> 282936.409 (55.000s)|10560 -> 44400 /33840|57 -> 252 /195|9200 -> 43264 /34064|0|

Thus rejection and silence fill are not bounded startup-only artifacts.
Playback quality on the exact final pair/build is **FAIL**; overall unfinished
cadence closure remains **PARTIAL**. No buffer inflation or counter suppression.

### 32.4 Pre/post local cadence comparison

All boundaries retain their own local clocks. No Client timestamp is subtracted
from a Tablet timestamp. Socket completion is not a radio TX timestamp. The
initial baseline and resumed campaign are not a controlled radio-state A/B, so
the magnitude of end-to-end improvement is not attributed solely to pacing.

| Boundary | Pre-pacer baseline | Paced cycle1 | Paced cycle2 | Paced cycle3 |
| --- | --- | --- | --- | --- |
| Encoder input intervals3-7ms |370/12007 (3.08%)|11858/12006 (98.77%)|11871/12000 (98.92%)|11836/12002 (98.62%)|
| Steady socket intervals3-7ms |5547/11005 (50.40%)|8925/11005 (81.10%)|8263/11006 (75.08%)|8145/11001 (74.04%)|
| Steady socket maximum gap |25.913ms|14.596ms|14.521ms|14.398ms|
| Kernel maximum gap, Host REALTIME |74.808ms|43.808ms|41.752ms|46.173ms|
| Before decoder maximum gap, Host MONOTONIC |70.629ms|44.185ms|42.353ms|46.530ms|
| Last rejected PCM frames |105600|52800|51120|44400|
| Last underrun callbacks / zero-fill |978/120272|278/48464|282/49952|252/43264|
| Last xrun |0|0|0|0|

Remaining first additional measured irregularity is already at kernel receipt,
before authentication/decode: kernel `<1,<3,<7,<10,<20,<40,else`ms bins are
cycle1 `493,1019,9149,1180,124,45,3`, cycle2 `549,1319,8398,1558,123,47,4`,
cycle3 `517,1260,8489,1575,118,44,3`. All three have multiple40ms-plus gaps
despite sampled continuous sequences. Local Host maximum authentication is
2.118/1.638/1.012ms; listener maxima18.325/15.167/8.477ms and diagnostic socket
age maxima17.653/17.064/15.013ms also remain observational, not excluded causes
of additional local delay. Real radio TX/driver queues are not observed, so
sender-versus-network/OS coalescing causality remains **INCONCLUSIVE**.
The unchanged ring can reject valid240-frame buffers when full and zero-fill
callbacks when empty; the pacer has not removed this recurring quality loss.

### 32.5 Separate SystemAudio and lifecycle regression

After the three counted mic cycles, DEBUG microphone opt-in was unchecked.
One normal Tablet -> S9 SystemAudio Session used the same enabled Host, with
requested20s Streaming, 13:38:09.513Z to Ready/Waiting13:38:44.757Z. Capture,
stereo Opus/protected send, decode and playback progressed; normal Disconnect
and then Disable Host succeeded. This is **technical/lifecycle PASS**, not a
zero-loss quality or new human-audible qualification.

Host last snapshot: captured958800 frames, encoded3995 units, sent3996,
WouldBlock/send failures0. S9 last snapshot: received4628, decoded4626,
decoded PCM1038240, accepted987360, rejected50880, underruns436/zero-fill39360,
xrun0. These endpoint snapshots are asynchronous and receiver PCM counters
reset during startup; do not compare totals as loss. The shell producer's
separate diagnostics show2 ring overruns/chunks dropped, stable between its
10s and20s snapshots, despite app-drain counters0; neither is hidden.

Closest earlier same-pair technical SystemAudio control already showed
71760 rejected frames,804 underruns/70992 zero-fill and xrun0. That precedent
was not a zero-loss PASS. Current recurring playback pressure is preserved as
**INCONCLUSIVE** quality debt, not declared a new pacer regression in an
untouched SystemAudio source or silently counted as quality PASS.
Video capture/encoder/control and DirectShell Input gateway ran without reported
provider errors; no new human-visible/touch-injection qualification was attempted.
Static Host UI can produce few video AUs; that count is not a presentation test.

### 32.6 Cleanup, ordering, software and final state

Each mic cycle logged `MIC_CAPTURE_STOPPED`/`MIC_CAPTURE_CLOSED`; Tablet AAudio
close returned0 for each stream. S9 Ready -> rediscovery -> new Session and
Tablet Waiting/Available were verified without Host toggles. Warm Host helper
ownership intentionally survived Disconnect. Final normal Disable Host leaves
Tablet Ready, no DirectShell helpers, no bootstrap files (`files/profileInstalled`
only), and0 current AudioFlinger speaker tracks. Logcat collectors were stopped
only by their verified PIDs/commands. No global policy/preferred input was created.
No FD trend or universal leak-freedom claim is made.

Part B/C remains **NOT IMPLEMENTED / NOT TESTED** because the actual quality gate
failed. A41's restored Wireless Debugging is only a prerequisite check, not a
new AudioPolicy capability result; there is no separate device workaround.
No policy UID rule, forced preference, Root command, protected-session shortcut,
new production RPC, new capability requirement or post-freeze migration was added.
No root/device capability is inferred from the improved microphone source timing.

No product source/test changed during this resumed hardware-only validation.
The section22-25 software gates for this exact frozen source/APK remain applicable:
964 JVM/0/0/0 and native Debug/Release22/22 each; they are not represented as
newly rerun here. Resumed edits are documentation and generated analysis tooling
only. The numeric analysis script parses with0 PowerShell errors and executes
successfully; final `git diff --check` PASS. Wireless Debugging remains1 on both
Tablet/A41. Git status remains staged0/unstaged189/untracked67, with inherited
source/tests/generated/unrelated classifications unchanged. Partial work remains
uncommitted, no push/merge/rebase/reset/clean.

Documentation updated in this resume: this report, `CurrentNonRootDebt.md`,
`README.md`, `MicrophoneAudioE2EQualification.md`,
`MicrophoneAudioCadenceAndVirtualInput.md`, `docs/FutureRoadmap.md`, and
`docs/testing/AndroidCompatibilityMatrix.md`. No new product implementation or
unit test is credited merely for completing this hardware campaign.

New bounded evidence: `portable-mic-final-pacer-<serial>.log/.err`,
`portable-mic-final-pacer-sessions.txt`, `portable-mic-final-pacer-analysis.json`,
`portable-mic-final-pacer-systemaudio-regression.txt`, and
`portable-mic-final-pacer-audioflinger-after.txt`, all under `app/build/reports/`.
`AnalyzePacedMicrophoneCampaign.ps1` is generated local numeric-analysis tooling,
not a product or checkpoint asset. No raw PCM/speech was saved.

Completion remains **MICROPHONE_AUDIO_RECEIVER_CADENCE_PARTIAL**.
Next single task: **MicrophoneAudio post-pacer socket-send-to-kernel cadence
root-cause closure**, retaining the same format, ring and protected path until
an additional causal correction is proven. Portable no-UID capture-preset policy
integration stays gated; do not substitute old scoped injection for that result.
