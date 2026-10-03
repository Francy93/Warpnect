# Phase 6.5 MicrophoneAudio Client-to-Host Qualification

Latest [final global-input quality closure](DirectShellGlobalMicrophoneFinalClosure.md)
validates C782DD9D r2 on S9/API29 ->Tablet/API33:3/3 consecutive above121s,
ordinary MIC72s plus reopened28s each, active IPC/track/DEBUG quality deltas0,
physical997Hz and natural capture-preset routing, physical restoration and
helper-loss withdrawal. Source built-in microphone uses normal RECORD_AUDIO;
protected transport unchanged. Final human DEBUG speaker audibility is NOT
CONFIRMED. This supplements the historical cadence/function tokens, not broad
app/device-route qualification or release readiness.1022 JVM/22+22 native pass.
Older residual campaigns below remain preserved, superseded as current status.

Current [residual quality evidence](MicrophoneReceiverSchedulingQuality.md):
peer link restored, real997Hz/type25 ordinary capture and physical restoration
remain proven. Bounded one-burst admission prevents whole-block DEBUG loss,
but final81F10D24 cycle1 FAIL on residual track/source starvation;2/3 NOT RUN.
1017 JVM/22+22 native pass. No new quality, audible, priority or release token.

Latest [receiver scheduling campaign](MicrophoneReceiverSchedulingQuality.md)
retains functional global input and historical DEBUG cadence tokens. A local
receiver scheduling correction has a stable diagnostic with real997Hz at the
ordinary consumer, but final43FB71D6 cycle1 FAIL after peer/media and Tablet
network/Debug wireless loss.2/3 NOT RUN, no final audible/quality claim.1016
JVM and22/22 Debug/Release native pass. No production-release promotion.

Latest [source-clock continuation](MicrophoneSourceClockRecovery.md): one counted
D28F6136 S9 ->Tablet cycle108.943s FAIL, steady DEBUG rejected+9360/underrun+69,
RemoteSubmix underrun+37 and IPC rejection+720 during the first continuous
reader; reopen worsens. Cycles2/3 NOT RUN. Real997Hz ordinary input/restoration
remains proven, not quality closure. A separate extended diagnostic has80s
steady all-zero deltas and Ready/Waiting, but no close/reopen acceptance. Later
radio/Debug wireless loss blocks further qualification. Pacer debt correction
is implemented/tested;80/70ms DEBUG and fixed40ms IPC remain separate/unchanged.
No new human audibility, release promotion, quality token or checkpoint.

Current [2026-10-03 clock-continuity continuation](MicrophoneConsumerClockContinuity.md):
final66A8B330 completes three108s S9 ->Tablet ordinary MIC Sessions under one
Enable Host with real997Hz OFF/ON/OFF, natural type25, physical15/11 restoration
and fresh generations. IPC rejected0/0/0 does not establish quality PASS:
cycle3 DEBUG steady underruns and RemoteSubmix AudioTrack underflows recur.
Global functional milestone remains valid; quality PARTIAL. A later diagnostic
is blocked by renewed Tablet radio loss/Android Debug wireless disablement.
No human audibility confirmation or release/forced-priority promotion. Existing
80/70ms DEBUG reserve is unchanged; output continuity/re-priming is tested.
Final gates1007 JVM/161 suites, CTest Debug/Release22/22; no checkpoint.

Historical preceding continuation:

Latest [consumer-transition qualification](MicrophoneConsumerTransitionQuality.md):
Tablet returned; the exact old guard candidate fails before new edits. Final
858A76A0 APK has two complete108s S9 ->Tablet MIC Sessions with real997Hz ordinary
input and physical restoration, followed by a failed third attempt during
platform radio/link loss. No3/3 quality closure. New fixed40ms local IPC/admission
candidate remains provisional;80/70ms DEBUG playout is unchanged. Gates1004 JVM/
161 suites, CTest Debug/Release22/22. No new token, commit or release promotion.

Historical preceding continuation:

Current [global-input quality follow-up](DirectShellGlobalMicrophoneInjectionQuality.md)
remains PARTIAL. With Wireless Debugging restored, the unchanged candidate's
first counted111.017s S9 -> Tablet Session failed quality; natural MIC open/reopen,
997Hz signal and physical route restoration passed. Failed opening-flush and
smaller track-threshold experiments were reverted. Consumer-aware discard,
80ms local IPC expiration, cold-clock retention and the new consumer-window
guard are not a new3x60s hardware quality qualification. Bidirectional S9/Tablet
IPv4 [peer controls now pass after one rejoin](S9TabletPeerLinkRecovery.md).
Tablet is temporarily unavailable by operator confirmation, so primary acceptance
is paused without a new Session or S22/A41 substitution. Debug wireless needs
normal restoration on Tablet return. No product audio code changed in this continuation.
Prior tokens/APKs below remain historical
accepted evidence; no release or priority promotion.

Latest integration: [portable global recorder policy](DirectShellGlobalMicrophoneInjection.md).
Capture-preset rules contain no UID. Final CF5C311C APK completed3/3 Tablet
ordinary MIC consumer Sessions with physical S9 OFF/ON/OFF signal, plus
VOICE_COMMUNICATION/CAMCORDER controls. Natural Android routing selects the
dynamic submix without per-app setup; Disconnect/helper death restore physical
capture. **DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED** is functional
DEBUG integration evidence, not release readiness or all-app/lossless quality.
MIC1 has late DEBUG underruns; IPC startup and consumer-absent rejection remain
open, while MIC2/3 DEBUG counters are0. The older cadence APK's accepted all-zero
result is not transferred automatically. Session-owner/control regressions and
external Wi-Fi/Debug wireless loss remain preserved. Forced priority is NOT TESTED.

Current DEBUG quality: [autonomous cadence closure](MicrophoneAudioCadenceRootCause.md)
validated3/3 consecutive S9 -> Tablet Sessions above60s under one Enable Host,
with rejection/underrun/xrun0 in startup and steady samples. An80ms bounded
ring/70ms initial reserve adds65ms nominal priming; the output clock starts
warm and holds PCM until primed. Opus, packet duration and socket buffers are
unchanged. A physical S9 speaker/microphone OFF/ON/OFF signal reached Tablet
decoded PCM, and AudioFlinger shows SPEAKER. Human Tablet audibility remains
NOT_CONFIRMED; DEBUG playback is not production Android microphone input.
Final gates972 JVM/0/0/0 and native Debug/Release22/22 each. The original
technical E2E and failed quality campaigns remain preserved below.

Earlier resumed final-APK quality gate: [three S9 -> Tablet Sessions above60s](DirectShellMicrophoneInjectionQualification.md#32-resumed-final-apk-campaign-after-prerequisite-restoration),
3/3 lifecycle PASS but0/3 quality PASS. The pacing candidate improves local
encoder/socket cadence without changing ring/codec/socket buffers; recurring
steady PCM rejection and zero-fill remain. This does not downgrade the earlier
technical physical-microphone E2E or A41 human DEBUG audibility evidence, but it
does not close playback quality or validate product microphone injection.

The initial 2026-10-01 [ordered cadence/injection follow-up](DirectShellMicrophoneInjectionQualification.md)
proves source AudioRecord read batching and local S9 paced delivery (592 intervals
all3-7ms, clean stop), but does not close protected E2E playback quality. A new
Session failed before steady measurement; Tablet then lost Wi-Fi/Debug wireless.
Global capture-preset injection stays pending. Historical technical Sessions and
human-audibility boundaries below are unchanged, not upgraded by a local probe.

Date: 2026-09-29. This is a DEBUG, exact-pair hardware result, not a
production-ready or universal microphone qualification. No speech/PCM was
recorded to disk. The three-cycle APK SHA-256 was
`243AB3026EEF21A1C06A38A9A62B4ABEF30F59AC9003E1DFFEB497A932C3E5D5`.
The subsequent full rerun generated SHA-256
`B4D186D836428FE7C03808703C814A8589827E87ED56BA04C353D19CE1A2A376`
from unchanged application source; that exact artifact was installed on both
devices and passed a separate 1/1 normal-Session technical spot-check.

## Ordered Tablet gate

The current lifecycle APK was tested before any microphone source changes.
Tablet X700_EEA/API33 as Host and S9 SM-G960F/API29 as Client completed 5/5
normal discovery, Streaming, Disconnect, Client Ready, Tablet Waiting and
rediscovery cycles under one Enable Host. There was no Host toggle between
cycles, no hidden Connect retry, and no Shizuku feature fallback. An earlier
exploratory UI check incorrectly treated the asynchronous teardown window as
a failure; the bounded counted campaign began afterward. See
[PostDisconnectHostLifecycleReliability.md](PostDisconnectHostLifecycleReliability.md)
for each cycle and its resource evidence. Only after this gate passed was the
local lifecycle checkpoint `92347d0` created.

## Existing path and bounded integration

Phase 3 already implemented the ordinary-app Android microphone controller,
Opus sender/receiver, Audio Payload V1, protected SCL transport and Oboe
playback. The current Session factory already recognized the distinct
`MicrophoneAudio` channel in the Client-to-Host direction. What was missing
from normal Session validation was a truthful Client opt-in/permission path,
a Host policy that admits the existing one-Client speaker sink, and exact
Host playback validation. The new DEBUG-only, transient `Microphone to Host`
selection requests MicrophoneAudio and omits SystemAudio for that test Session.
It does not change the release default or the RFC-006I Complete Host set of
Video, SystemAudio and Input. It uses the normal Find Hosts/Connect path, not
a direct pipeline test hook.

| Stage | Current component | Result |
| --- | --- | --- |
| Permission and physical capture | `RECORD_AUDIO`, `AndroidMicrophoneAudioCaptureController` | Implemented; S9 hardware-tested |
| PCM and Opus | `AudioCaptureControllerCore`, `DefaultAudioTransmitterSessionController`, `NativeOpusAudioEncoderController` | Implemented; E2E counters advance |
| Wire identity and transport | MicrophoneAudio channel, Audio Payload V1, protected `NativeSclAudioTransportController` | Existing implementation; normal Session exercised |
| Host receive and playback | `NativeSclAudioReceiverController`, `NativeOpusAudioDecoderController`, `NativeOboeAudioPlaybackController` | Implemented; technical sink consumption observed |
| Session ownership | `AndroidSessionPipelineFactory`, Session lifecycle/controller | Three normal Disconnect cycles and one abnormal Client loss tested |
| Human-audible Host output | Physical Tablet speaker | NOT CONFIRMED; no operator answer for this campaign |

The audio payload format and Session protection were unchanged. MicrophoneAudio
and SystemAudio have different channel/payload identities. No microphone
media was sent on a raw unauthenticated debug socket. The current DEBUG
one-Client `SeparatePerPeer` Host policy uses the existing Host speaker sink;
it is not a new mixer or a multi-peer/bidirectional voice feature. The exact
validator now checks the mono Host playback configuration instead of accepting
decode alone. Capability negotiation requires a playback flag on the Host.

## Physical environment and local capture

Client: Samsung SM-G960F, Android 10/API29, fingerprint
`samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys`.
Host: Aocwei X700_EEA, Android 13/API33, fingerprint
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`.
Host used the already validated app-owned DirectShell Host runtime. No Root
or Shizuku microphone capture was used.

On S9 the microphone request selected 48 kHz, mono, PCM16, 5 ms codec frames,
64 kbit/s Opus and a 2,880-frame AudioRecord buffer. Android routed the
recording to device type 15 (`TYPE_BUILTIN_MIC` in the installed Android SDK).
`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` was false, so the existing source
selection used `MediaRecorder.AudioSource.VOICE_RECOGNITION`. AGC/NS/AEC
activity and the Tablet's physical output route were not instrumented. The
local AndroidTest used real AudioRecord input for three seconds, kept only
numeric metrics in memory, and passed twice:

| Probe | Frames | Nonzero samples | RMS | Peak | Stop |
| --- | ---: | ---: | ---: | ---: | --- |
| Initial | 142,080 | 133,404 | 22.49 | 146 | PASS |
| Source-observable rerun | 143,040 | 134,288 | 27.10 | 331 | PASS |

This proves nonzero physical microphone capture, not correlation with a known
external acoustic tone or intelligible voice. Android's microphone privacy
indicator and global mic privacy toggle were not observed/tested here.

## Normal Session campaign

Each of the first three counted S9 -> Tablet MicrophoneAudio Sessions used one
normal Connect and Disconnect, with the Tablet Host continuously enabled.
The sampled counters below are in-Session snapshots, not a fabricated final
counter at stop. Host received, decoded, wrote and consumed real payloads;
Client AudioRecord, Opus and protected sender advanced. No video or SystemAudio
correctness/audibility claim is derived from these microphone counters.

| Cycle | Host decoded audio frames (sample) | Host PCM consumed (sample) | Client capture | Disconnect / Host | Result |
| --- | ---: | ---: | --- | --- | --- |
| 1 | 11,995 | 2,812,240 | started; stopped/closed | S9 Ready; Tablet Waiting | TECHNICAL PASS |
| 2 | 2,976 | 697,680 | started; stopped/closed | S9 Ready; Tablet Waiting | TECHNICAL PASS |
| 3 | 3,983 | 936,528 | started; stopped/closed | S9 Ready; Tablet Waiting | TECHNICAL PASS |

The first Session exceeded 60 seconds. At one first-cycle snapshot Client
reported 11,993 encoded frames / 11,994 sent datagrams, no capture overrun,
no send failure. Host reported no decoder failure and zero Oboe xruns, but
the small playback ring did reject PCM intermittently. At the sampled second
cycle point `playback_rejected=16,560` frames and
`playback_underruns=135`; at the third-cycle point these were 19,440 and
140. This is quality/buffer-pressure evidence, not a zero-drop claim.
There was no human confirmation that the Tablet speaker produced audible or
intelligible Client-microphone sound. Status is therefore
`REMOTE_AUDIO_TECHNICAL_PASS` / `HUMAN_AUDIBLE_NOT_CONFIRMED`.

After a stopped Session, a controlled S9 process restart retained the Android
permission but reset the transient microphone opt-in. A fresh opt-in and
Connect again reached Streaming; one Host snapshot had 2,966 decoded frames
and 688,144 consumed PCM frames. Force-stopping only the S9 Warpnect process
in this fourth active Session returned the Tablet to Waiting in the bounded
observation. This fourth run is an abnormal-loss/restart control, not a
fourth normal-Disconnect counted cycle. Normal Host disable left zero matching
DirectShell app_process helpers and zero temporary bootstrap secret files.
No global FD-leak claim is made.

The final full-gate APK had one additional normal S9 -> Tablet microphone
Session: S9 Streaming/Tablet Running, 1,983 Host decoded audio frames and
463,024 consumed PCM frames at the observed sample, normal Disconnect back to
S9 Ready/Tablet Waiting, then normal Host disable. This is 1/1 final-artifact
technical PASS, kept separate from the earlier 3/3 campaign. Final teardown
again showed zero matching DirectShell helpers and zero temporary secret files.

## Permission and SystemAudio controls

After a normal Disconnect, `RECORD_AUDIO` was revoked on the test S9. The
ordinary app checkbox opened Android's permission dialog. Selecting `Rifiuta`
left the checkbox unchecked and showed `Microphone permission denied`; no new
microphone capture started. Permission was restored using Android's `Consenti`
dialog, not a privileged grant for the qualifying run. Active revocation and
the global mic privacy toggle were NOT TESTED.

A separate mic-disabled SystemAudio control was attempted after a long pause.
The first attempt failed before media at Host exact setup validation with
`ExactAudioConfigurationUnavailable`. The Tablet still displayed Host
Available while `ps` showed zero DirectShell feature helpers. The helpers'
observed launch argument was `--idle-timeout-ms 900000`; after hours of idle,
their absence and the stale Host advertisement are a pre-existing Host
liveness/truthfulness defect, not a microphone capture failure. The original
FAIL is retained. One bounded comparison used normal Disable/Enable Host,
which launched new UID2000 Video/SystemAudio/Input helpers; a fresh normal
SystemAudio Session reached Streaming with Host capture, Opus and datagrams
advancing (e.g. 1,199,040 captured frames / 4,998 sent datagrams in one
snapshot). No deliberate tone or human SystemAudio audibility test was run.
The first comparison tap on a stale Client row after the setup failure did not
start negotiation while a `Disconnect` action was still present. The operator
used that normal Disconnect to clear the failed attempt, then ran fresh Find
Hosts/Connect. That no-op UI tap is preserved, not counted as a successful
Session or hidden retry. Its UI semantics need a separate reproduction if it
recurs.
This comparison does not retroactively convert the failed idle attempt to
PASS. It also does not establish the exact helper death timestamp, only the
first trustworthy failed boundary and the observed 15-minute idle limit.

## Scope and remaining work

The MicrophoneAudio direction tested is Client microphone -> Host playback.
It does not enter the RFC-006I Complete Host requirement, alter REMOTE_ONLY
SystemAudio routing, implement HOST_AND_REMOTE, or change RFC-005J/005K. A
human listening/known acoustic-source check is still needed to claim physical
speaker E2E. Buffer pressure deserves a separate quality assessment. The
highest-priority newly exposed product defect is DirectShell Host publication
remaining Available after all feature helpers expire during long idle. Repair
must revalidate liveness or maintain bounded Host ownership, not silently
retry or publish an unowned complete capability set.

Software gates for this slice: `git diff --check` PASS; Gradle
`ktlintCheck`, `lintDebug`, `testDebugUnitTest --rerun-tasks`, `assembleDebug`,
`assembleDebugAndroidTest`, `packageDirectShellProbeDex` PASS; 941 JVM tests,
zero failures/errors/skips. The local microphone AndroidTest passed 2/2 runs.
Native source was unchanged, so CTest was not rerun. New MicrophoneAudio work
remains uncommitted for review. No push, merge, Root, physical modification,
production Magisk, remediation, provider migration, or protocol redesign.

## 2026-09-30 superseding liveness / quality follow-up

The preceding implementation and technical campaign remain historical. The
slice was checkpointed locally as `809b7ddd48e9db192a86de17b59abdfd607916fd`.
The [owner-liveness and playback-quality report](DirectShellIdleLivenessAndAudioPlaybackQuality.md)
is the current source for the two exposed debts. Its authenticated owner-socket
change crossed the former idle boundary on A41 and Tablet and withdraws Host
publication on unexpected helper loss; it is not full RFC-005K background work.

Later S9 -> Tablet controls explicitly reported no audible Tablet output despite
nonzero capture/decode energy and progressing consumption. Rejection/zero-fill
continued at steady state. A user-requested A41 -> the same Tablet comparison
also reached decoded PCM but added frequent playback resets; no listening answer
was received for that topology before the operator became unavailable.

An unattended eight-second S9 speaker fixture was then captured acoustically by
the A41 built-in mic and transported through the normal protected Session.
Capture RMS rose from about 55-59 to 637, Tablet decoded RMS reached about
650-657, and OFF windows returned toward ambient. This is not digital PCM
injection, intelligible-voice proof, a cross-device latency measurement or
physical Tablet speaker confirmation. AudioFlinger placed the microphone sink
on EARPIECE while the speaker output was standby. No route or buffering fix has
yet been validated. Quality/physical-output status remains PARTIAL.

The follow-up software gates pass with 950 JVM tests, zero failures/errors/skips;
native source is unchanged. At that report boundary, new owner-liveness/diagnostic
work remained uncommitted.

## 2026-09-30 destination clarification and later follow-up

The product destination is now explicit: **remote Client microphone -> Android
Host virtual microphone input**. The Phase 3 codec/payload/transport implementation
and the technical campaigns above remain valid, but their Oboe speaker/earpiece
sink is **DEBUG validation**, not completed product input integration. No historical
speaker campaign is retroactively a virtual-input PASS. Complete Host still means
Video/SystemAudio/Input, not mandatory microphone support.

Owner-bound liveness was checkpointed as `715b215`; unchanged prior long-idle,
loss-withdrawal, app-death and cleanup evidence is retained. The
[virtual-input architecture report](MicrophoneAudioVirtualInputArchitecture.md)
records A41 shell routing authorization denial and a corrected S22 UID-scoped
input experiment consumed by a standard normal-process AudioRecord. That generated
fixture is not the Client acoustic/protected Session product path and does not
establish an additional/selectable source coexisting with physical mic for all apps.

Release does not offer diagnostic playback as virtual input. DEBUG UI/telemetry
name the destination; Oboe uses Media/Speech rather than VoiceCommunication/Speech.
The current A41 output is technically SPEAKER. Tablet is disconnected; human
output is NOT CONFIRMED. Three final S9 -> A41 DEBUG Sessions passed transport,
decode/consume and normal Disconnect/rediscovery, but all three steady quality
windows failed with real ring rejection and zero-fill. The causal boundary is
narrowed to added irregularity between protected submit and pre-decoder receive,
not fully root-caused. No buffer, source, format, Opus, payload or security change.
Final gates954 JVM/0/0/0, native Debug/Release CTest22/22 each. New virtual-input
investigation and diagnostic routing/quality work remain uncommitted and PARTIAL.

## 2026-10-01 cadence / first-class input follow-up

[Current report](MicrophoneAudioCadenceAndVirtualInput.md) preserves those
quality failures, then adds local kernel ingress, socket read, authentication,
sequence and listener measurements. Additional disturbance is already visible
at Tablet kernel receive; no exact radio/AP/driver or sender-kernel cause is
proven. Buffers and wire contracts remain unchanged, quality PARTIAL. The
operator confirmed hearing S9 microphone audio on A41 DEBUG playback; no
Tablet human-audible claim is inferred.

Tablet shell registration and scoped ordinary-Recorder injection work. A
distinct dynamic submix is enumerated, but a normal consumer outside the
matching-UID policy cannot open it. That is not a first-class remote microphone
product destination. A41 registration is still authorization-blocked. No new
production virtual provider is offered, and microphone remains optional rather
than part of Complete Host. Final gates959 JVM/0/0/0 and native22/22 in both
configurations; final same-APK Session/cleanup details are in the report.
