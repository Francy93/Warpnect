# Phase 6.5 MicrophoneAudio Client-to-Host Qualification

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
