# DirectShell owner liveness and MicrophoneAudio playback quality

Date: 2026-09-30. This report distinguishes the previously observed Tablet
failure from the current software change and from each physical control. It
does not qualify RFC-005K background persistence or universal audio quality.

## Historical failure and first boundary

The previous S9 microphone -> Tablet control found the Tablet UI/publication
still at `Host: Available` after hours of Host idle, with zero DirectShell
feature helpers. The next SystemAudio Session failed at
`ExactAudioConfigurationUnavailable`; normal Disable/Enable relaunched the
helpers and a subsequent Session reached Streaming. This is a pre-existing
product liveness/publication defect, not a microphone failure. The historical
run did not capture the exact helper exit timestamp.

Source inspection identified the absolute-in-practice idle guard: the Host
launcher passed `900000` ms to each of the PCM ring, Input and Video feature
servers. Their watchdogs independently compared elapsed time since the last
authenticated feature request. No persistent Host owner connection existed;
when no Session used a server, each reached its own 15-minute inactivity
limit. The app registry kept its last READY snapshot and did not learn of the
process exits. The timeout's code comment described a bounded Host runtime
lease/orphan guard. This is an inactivity timeout, not evidence that Android
itself killed the helpers at the historical moment.

## Bounded ownership change

The DEBUG DirectShell protocol now has an HMAC-authenticated,
generation-bound `HOST_OWNER_ATTACH` request. Each feature server accepts at
most one owner connection. Before attachment, a 60-second acquisition guard
still bounds an orphan bootstrap; standalone probe timeout semantics remain
unchanged. After attachment, ordinary idle time has no expiry. Owner socket
EOF/error, explicit authenticated shutdown, generation teardown and failed
bootstrap still terminate the purpose-specific helper. The normal app retains
the three owner sockets for the explicitly enabled Host, including between
Sessions. It does not use desktop ADB to renew them.

The app-side owner watcher invalidates DirectShell runtime health when a
helper connection disappears. The Host publication owner then stops the Host
and withdraws `Available` instead of silently migrating providers or
restarting a Session. Pre-publication and immediate post-start health checks
close the admission window. This detects connection/process loss; it does not
claim to detect a helper that is hung while its socket remains open.

Normal Session shutdown suppresses expected owner-loss reporting before
closing a consumed Input gateway. A Host-owned audio controller stops its
AudioRecord/ring first and leaves helper shutdown to the feature lease owner.
The existing single replacement attempt after Session teardown is retained;
failure to prepare its required replacement now reports runtime unavailable
and withdraws publication instead of leaving a stale READY snapshot.

The per-generation secret, request HMAC, purpose-specific feature protocol,
APK verification and loopback-only transport are unchanged. The owner
connection carries no media or arbitrary commands. Full background,
screen-off, power-management and other fixed-duration lifecycle assumptions
remain for RFC-005K or the relevant future discovery/recovery RFC.

## Preserved controls and anomalies

The first exploratory DEBUG APK SHA-256 was
`E1BD50DA4A0D2558C7A2EF88784126B5C5AB83D1341F483FCB7BCEB285621C37`.
The Tablet/API33 was disconnected during this task; a pre-fix Host attempt
begun there at 17:13 local time ended before the old 15-minute boundary and
is not counted as an idle validation.

An S22/API36 normal Enable Host attempt on the changed APK failed before any
helper launch: `FEATURE_PCMSHAREDRING_TRUSTED_IDENTITY` reported
`CONNECTION_SERVICE_IDENTITY_REQUIRED`; the other two roles failed at the
same stage. Wireless Debugging was on, but the Warpnect trusted service
identity was absent. This is an environmental pairing precondition, not an
owner-channel or audio result. There was no hidden retry.

A41/API31 normal Enable Host at 20:27:44 local time reached `Host: Available`
with `SINGLE / DIRECT_SHELL`; PCM, Input and Video helpers each authenticated
an owner connection. At 20:43:14 the same helpers remained alive after 15 min
22 s. Its first post-idle S9 Session reached Streaming. Microphone playback
on this alternate Host failed at playback preparation: receiver state Error,
`PlaybackFailed`, zero decoded frames, then AAudio closed the newly opened
stream. This is a separate output-route boundary; the preserved trace does
not establish the exact nested playback error or the Tablet quality cause.
Current classification: `INCONCLUSIVE`. The earlier draft provisionally called
this an external output-provider failure, but its trace lacks the nested
playback error. That provisional label is not sufficient root-cause evidence.

At normal Disconnect, the Input gateway's intentional SHUTDOWN closed the
owner socket before the runtime suppressed loss. At 20:46:41.351 the watcher
reported LOST and withdrew Host publication, although Session teardown was
normal. Classification: `PRODUCT_REGRESSION` in the new owner watcher. The
release ordering and Host-owned audio shutdown described above correct this
cause. This exploratory Disconnect remains FAIL.

## Owner liveness campaign

The subsequent APK SHA-256 was
`3B6EBBCE41E6D0614ADF65364B5D3DCFAE58DC8CEE8E97AA56DEAD64DC968BA8`.
On A41, normal Enable Host began 2026-09-29 20:53:30 Europe/Berlin;
publication became AVAILABLE at 20:53:38 in the device log. App PID 12236 and
PCM/Input/Video PIDs 12469/12577/12626 were still identical on 2026-09-30
14:50-14:53, after almost 18 hours with no Session. UI remained Waiting for
clients / Host Available. The former 15-minute boundaries were crossed.

The first normal S9 Find Hosts rediscovered A41; Connect at 14:53:48 reached
Streaming without Host toggle, process restart or pre-Session helper relaunch.
DirectShell Video encoded frames and SystemAudio capture started. Disconnect
at 14:55:10 returned S9 to Ready and A41 to Waiting for clients. Consumed
feature servers were replaced through the existing post-Session ownership
path; no owner-loss warning was emitted for their expected shutdown.

Normal Disable Host at 14:55:57 left zero feature helpers and zero temporary
secrets. A separate Enable Host followed by a verified kill of only Input
PID 15823 produced LOST at 14:57:59.003, publication WITHDRAWN at .005 and
Host stop at .070. No Session was needed to discover the loss. All other
helpers were released. After another normal Enable Host, app force-stop at
14:59:54 left zero helpers/secrets at the next inspection. This proves owner
EOF cleanup on that exact A41 path, not general background/Doze behavior.

Tablet/API33 returned on 2026-09-30 with manually enabled Wireless Debugging.
Normal Enable Host attached PCM/Input/Video owners at device-local
14:54:50.483 / 14:54:53.458 / 14:54:56.334; publication followed at
14:54:57.542. App PID 14243 and helper PIDs 14403/14445/14485 were identical
at 15:12:15. The first post-idle Connect at host-side 15:13:18 reached
Streaming after approximately 18 minutes, without Host toggle, helper
relaunch, app restart or Wireless Debugging toggle before that attempt.
All three required providers were DIRECT_SHELL, with authenticated UID2000;
Shizuku was not selected. Video encoded and SystemAudio AudioRecord/ring/Opus
progressed. Input preparation/start/stop passed; an exploratory Client tap
did not yield a sampled injected-event proof and is not credited as one.

Normal Disconnect returned S9 Ready / Tablet Waiting; normal Disable Host
left zero feature helpers and temporary secrets. The existing consumed-feature
replacement mechanism remains separate from automatic unexpected-loss recovery.
The SystemAudio producer exposed two startup ring drops, then stable sampled
helper FD count 62 after handoff (58 before bind, 64 after handoff). Client
playback rejection/underrun was nonzero. This control proves post-idle runtime
and media startup, not zero-loss audio or human audibility.

These physical idle campaigns used the `3B6EBB...` APK above. Later APKs add
replacement-failure health reporting and DEBUG audio observations; they must
not be described as the exact artifact used for the earlier idle campaign.

Before the owner-liveness checkpoint, a final bounded normal A41/API31 ->
S9/API29 regression used the software-gate APK `7491FEA5...` on 2026-09-30.
Enable Host began at host-side 20:06:51; all three owners attached and normal
publication followed. First Connect at 20:08:32 reached Streaming; Video encoded,
SystemAudio capture/Opus/transport advanced, and Input prepared/running without
injection errors. No audible tone or human Input proof was added. Normal
Disconnect at 20:09:12 returned A41 to Waiting / Host Available, with expected
feature replacement and no owner-loss withdrawal. Normal Disable Host at
20:10:27 left zero named feature helpers and zero bootstrap secret files. This
is 1/1 final lifecycle regression, not a repeated 18-hour campaign or zero-loss
SystemAudio quality claim. Existing idle and failed intermediate evidence remain.

## Playback counter contract

On the prior S9 -> Tablet technical MicrophoneAudio Sessions, one sampled
point reported 16,560 rejected PCM frames and 135 underrun callbacks; another
reported 19,440 and 140, with Oboe xrun count zero. Those are in-Session
samples, not a root-cause diagnosis. In `PcmPlaybackRing::submit`, rejected
frames count invalid generation/count/range and ring-full submissions. The
receiver treats `PlaybackRingFull` as a dropped decoded frame, while other
submission errors fail the pipeline. In `PcmPlaybackRing::consume`, an
underrun callback means the requested output frames exceeded queued frames;
the remainder is zero-filled. This is distinct from Oboe's platform xrun
counter.

The current 48 kHz mono microphone stream has 5 ms codec frames, a four-frame
PCM playback ring (20 ms capacity), one-frame start threshold and a requested
two-burst Oboe buffer. The normal MicrophoneAudio receiver does not install an
A/V playback start gate. No buffer-size or priming
change has been made without a measured startup/steady-state distinction.

DEBUG diagnostics now include actual Oboe API/mode/burst/buffer, ring capacity,
ring-full drop count, zero-fill frames and nested playback error. Five bounded
startup samples precede the existing five-second samples. These observe
startup and steady state without changing the capture/playback contract.

## Physical playback-quality baseline (not an acceptance campaign)

The first instrumented baseline used APK
`870CC2AA41688A866BCCF8980DF07D50E061C474105C55DAC68C2FA7C6B04F84`.
S9 -> Tablet normal microphone Connect began at host-side 15:18:19 on
2026-09-30. Tablet playback was 48 kHz mono PCM16, AAudio, LowLatency/Shared,
256-frame bursts, 512 actual buffer frames, 2048 backend capacity frames,
960 playback-ring frames and one 240-frame startup threshold. The physical
S9 VOICE_RECOGNITION input was built-in microphone type 15, with a 2880-frame
AudioRecord buffer. No source, format, priming or buffer default was changed.

The initial snapshot already had two ring underrun callbacks / 272 zero-fill
frames. That matches the initial 512-frame consumption versus only 240
primed frames. Ring-full rejection then appeared and continued at steady
state; this is not classified as a startup-only transient.

| Tablet local sample | Decoded PCM frames | Rejected PCM frames | Ring-full drops | Underrun callbacks | Zero-fill frames | Oboe xruns |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 15:18:22.849 | 237120 | 11040 | 46 | 73 | 6432 | 0 |
| 15:19:22.847 | 3117120 | 72480 | 302 | 617 | 67808 | 0 |
| 15:20:22.847 | 5997120 | 173280 | 722 | 1549 | 168736 | 0 |
| 15:21:17.846 | 8637120 | 251520 | 1048 | 2323 | 246720 | 0 |

The first two samples span 59998 ms on the same Tablet monotonic clock:
2880000 additional decoded PCM frames, 61440 additional rejected frames,
256 additional ring-full drops, 544 additional underrun callbacks and
61376 additional zero-fill frames; Oboe xrun delta remained zero. Capture,
Opus and protected transport progressed with no sampled capture overrun,
send failure or decode failure. The user explicitly reported that the
Tablet stayed silent. Human audibility: **NOT_CONFIRMED / not audible in this
observation**, not inferred from PCM consumption. Disconnect stopped and
closed the S9 microphone and returned the Tablet to Waiting.

The unchanged-configuration diagnostic APK was
`45EB833D4270B2F9A0C9A2559DDF8ADEEAEEE88907B6B158145E7BA847ECBD71`.
`PcmDeliveryDebugProbe` observes borrowed PCM without storing audio, records
numeric energy and local delivery gaps/bursts in five-second windows, and is
absent in release behavior. Twelve windows per instance and a process-wide
log limit in the later final rebuild bound diagnostics, including freshness-reset
cases; that later process-wide cap was not part of the physical `45EB...` APK. It does not
subtract clocks between devices or claim end-to-end latency.

| Attempt | Normal path and result | Quality / listening | Classification |
| --- | --- | --- | --- |
| Baseline S9 -> Tablet | protected microphone pipeline progresses; normal stop | persistent ring rejection/underrun; user reports Tablet silent | `INCONCLUSIVE` quality root cause; exact measured boundary is playback handoff/output |
| Diagnostic first Session | microphone opt-in had reset on app restart; SystemAudio only; stopped normally | not a microphone test | `TEST_HARNESS_DEFECT`; preserved, not silently relabelled |
| Diagnostic S9 -> Tablet, 15:37:38 to 15:44:03 host-side interval | checkbox verified checked; physical mic -> Opus -> protected receiver -> Oboe; normal Disconnect | steady-state loss recurs; user again reports Tablet silent | `INCONCLUSIVE` quality root cause; one bounded diagnostic reproduction |
| User-requested A41 -> Tablet, 15:46:08 to 15:51:32 host-side interval | same APK, Tablet kept enabled, A41 explicit opt-in and existing RECORD_AUDIO grant; normal stop | additional playback resets / burst delivery; no listening answer before operator became unavailable | `INCONCLUSIVE`; separate topology, not a successful quality retry |
| Unattended acoustic control, A41 -> Tablet, Connect at host-side 16:19:58 and Disconnect at 16:22:07 | S9 emits eight-second fixture; A41 physical mic -> protected Session -> Tablet decoded PCM; normal stop | clear OFF/ON/OFF energy change; playback resets remain; physical Tablet output unobserved | Capture/transport control PASS; quality/root cause still `INCONCLUSIVE`, not an audible E2E PASS |

S9 numeric windows include capture RMS 704/2317 and decoded RMS 707/2281
(different local window origins), followed by low ambient energy. This proves
nonzero signal reaches decoded PCM, not a synchronized correlation measurement
or intelligible speech. S9 capture completion gaps reached about 20 ms with
up to four consecutive deliveries below 1 ms; Tablet decoded delivery windows
showed gaps up to about 53 ms. Android's S9 input HAL period was 960 frames /
20 ms despite the app's 240-frame / 5 ms reads. These are relevant scheduling
boundaries, not proof that increasing buffering is the correct fix.

The A41 source likewise captured and sent normally, but Tablet decoded
delivery gaps reached roughly 388 ms and bursts up to 18 deliveries in the
sampled windows. Playback/decoder snapshots repeatedly reset while cumulative
Session counts continued. At the final sample, 62951 frames were decoded
cumulatively and ring-full drops totalled 8554; the current playback-instance
counts were 703440 decoded PCM / 687120 written / 686928 consumed / 16320
rejected. Those per-instance values must not be misrepresented as cumulative
Session totals. Both endpoints were connected on the same 5 GHz WLAN; this
does not rule out network scheduling, CPU contention or transport ordering.
The exact first upstream cause of the additional A41 discontinuities remains
unresolved. The nearest successful comparison is the earlier S9 technical
path with the same Tablet sink, not a human-audible PASS.

## Unattended physical acoustic control

The operator left and could no longer listen or perform manual actions. S22 was
disconnected and S7 SM-G935F/API26 connected, but S7 was credential-locked.
No unlock bypass was attempted. The accessible S9 served as the external
speaker source instead; its music volume was temporarily reduced from 15/15
to 7/15 and restored to 15/15 after the fixture stopped.

The existing normal Android `SystemAudioE2eToneActivity` generated an
eight-second configured 997 Hz fixture through S9 AudioTrack only. It does not
inject PCM into A41 capture, Opus, the ring or transport. S9's own local log
records start at 16:20:29.559, stop at 16:20:37.596 and 385928 frames written.
These are source-local timestamps, not synchronized latency endpoints.

| Local boundary | OFF / baseline RMS | ON RMS | Later OFF RMS |
| --- | ---: | ---: | ---: |
| A41 physical microphone, 5-second windows | 55-59 | 637; adjacent mixed window 513 | 54-57 |
| Tablet decoded PCM, shorter reset-bounded windows | about 60-90 | 650-657 in four consecutive windows | about 60-90, with occasional discontinuity spikes |

The repeatable energy change proves a physical acoustic signal enters A41 and
reaches the normal protected Tablet decoder. No exact spectral correlation,
intelligible speech or cross-device latency is claimed. A41 displayed Streaming;
normal Disconnect closed its microphone and returned Tablet to Waiting. This
does not prove that Tablet emitted the received sound. Another device's mic
would also hear the source S9 directly unless the source/Host are acoustically
isolated or an output-only control is designed; that ambiguity was not counted
as a physical-output PASS. No microphone recordings were saved.

## Actual output route and remaining boundary

Tablet AudioFlinger diagnostics during both source topologies showed the
Warpnect mono 48 kHz track using usage VoiceCommunication / content Speech,
stream 0, on `AUDIO_DEVICE_OUT_EARPIECE`. The separate built-in-speaker output
was standby with no active Warpnect track. Voice-call volume was nonzero;
track volume was 0 dB. This is stronger than simply assuming music volume
controls the microphone sink. It does not by itself prove that route selection
is the sole cause of silence or that the advertised earpiece is physically
audible on this Tablet.

Android exposes an application-scoped communication-device request and
explicit release through
[AudioManager API31](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).
A supported speaker-selection experiment is the next bounded diagnostic
boundary; it has **not** been implemented or credited here. The user-requested
A41 comparison was run first with routing unchanged. No communication route,
global mode, audio policy, source, format, ring capacity or priming default was
changed to force a PASS. The outstanding work is owned output-route validation
and causal delivery/priming analysis before a minimal quality fix; it is not
closed by the technical transport PASS.

## Software, ownership and repository result

Starting HEAD was `92347d0`. The previously validated microphone slice was
checkpointed locally as `809b7ddd48e9db192a86de17b59abdfd607916fd`,
`feat(audio): validate MicrophoneAudio end-to-end path`. Branch remains
`codex/directshell-production-runtime-integration`; master remains
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream remains the origin branch
(18 local commits ahead, zero behind). New liveness/diagnostic/docs changes
remain unstaged and uncommitted. No push, merge, reset or cleanup of old evidence.

The final software-gate APK SHA-256 is
`7491FEA515D013B0DFF3137CEFDB7E43AFAC4CB5DFBD905D4BA1B8116AF6FA42`.
This is a later diagnostic rebuild, not the APK used for the physical campaigns.
Gates passed: diff check, ktlintCheck, lintDebug, testDebugUnitTest with
rerun-tasks, assembleDebug, assembleDebugAndroidTest and packageDirectShellProbeDex.
The 151 JVM result suites contain exactly **950 tests, zero failures/errors/skips**.
No native source changed; native CTest was therefore not rerun. Existing dirty
native binaries and native-test reports were preserved as generated artifacts.

Focused new tests cover authenticated generation-bound owner attachment,
unowned bootstrap expiry versus a healthy owner beyond the old TTL, publication
ownership, stale/released runtime-health rejection, and borrowed PCM energy /
delivery-burst diagnostics with bounded output. Physical A41 controls separately
prove helper-loss withdrawal, normal disable and process-death EOF cleanup.

The nine new JVM cases are:

- `DirectShellProbeProtocolTest.hostOwnerAttachUsesAuthenticatedGenerationBoundFraming`.
- `DirectShellProbeProtocolTest.authenticatedOwnerOutlivesOldIdleLimitButUnownedBootstrapExpires`.
- `DirectShellHostOwnershipTest`: frozen plan alone does not publish Host; published DirectShell Host withdraws on owner loss or generation change.
- `PrivilegedRuntimeRegistryTest`: owner loss notifies without fallback; stale generation cannot withdraw current runtime; late loss after release cannot revive health.
- `PcmDeliveryDebugProbeTest.observesBorrowedPcmWithoutChangingBufferPosition`.
- `PcmDeliveryDebugProbeTest.separatesDeliveryBurstsFromLongLocalGapsAndBoundsOutput`.

No priming, route restoration or physical audibility test is claimed by these
cases, because those behavior changes have not been implemented.

Normal final Tablet disable at host-side 16:03:06 reported runtime released
(`leases=0`, three warm roles) and left zero feature helpers / secret files.
Microphone source stop/close and Host Waiting were observed after both source
topologies. After the subsequent unattended acoustic control, normal Disable Host
at host-side 16:23:49 again released the runtime and left helpers/bootstrap
secret files zero; A41/Tablet/S9 UI were Ready. All six owned host-side logcat
collectors were stopped by verified PID/command line, not a broad process kill.
No global FD leak-freedom is claimed: the short helper FD samples
above are not a repeated long-running whole-app descriptor census.
No post-freeze Shizuku/Root fallback or unexpected-loss relaunch occurred.

New application source is limited to DirectShell owner/control lifecycle,
runtime health/publication, and bounded DEBUG audio observations; new tests and
the linked Phase 6.5 documentation are separate from generated lab logs.
The pre-existing line-ending-only AndroidInputCaptureController status, old
screenshots/UI dumps, artifacts and .codex-remote-attachments were not reverted.
Generated runtime logs remain under app/build/reports and are not commit assets.
No raw microphone recording or user speech was saved/committed.

Deferred: full background/screen-off/Doze/power and fixed-duration timeout audit
belongs to RFC-005K or the relevant future discovery/recovery RFC. This is not
RFC-005K completion. HOST_AND_REMOTE, KEEP_ON / PRIVACY_BLANK / SCREEN_OFF,
RFC-005J, RFC-005K, physical DirectRoot, provider migration and full same-provider
recovery remain unchanged/deferred. No Root, flashing, wipe, SELinux or vendor
mutation, new audio payload/protection, arbitrary shell API or hidden retry.

Part A: `DIRECTSHELL_OWNER_BOUND_LIVENESS_VALIDATED` on the exact reported
physical paths. Part B: **PARTIAL**, with human audibility not confirmed and
steady-state ring loss not fixed. Do not emit the combined CLOSED token.
Next single recommended task: **MicrophoneAudio owned speaker-route validation
and playback-delivery root-cause closure**, beginning with the public-API output
route experiment and preserving the existing failed controls.

## Later checkpoint / destination clarification

Owner-bound liveness is now checkpointed locally at
`715b2151af2c8ecac165729178266b24e5cae91b`,
`fix(privileged): bind DirectShell helper lifetime to Host ownership`.
Before checkpoint, full-gate APK `7491FEA5...` passed one bounded A41 -> S9
normal Host/Session/Disconnect/Disable control. Host stayed Available after
Disconnect, and disable left helpers/secrets zero. Prior long idle and orphan
evidence above is preserved; the owner model was not redesigned again.

The [current destination/quality report](MicrophoneAudioVirtualInputArchitecture.md)
records the corrected product intent: Android Host virtual microphone input;
speaker playback is DEBUG-only. A41 shell policy registration is denied,
S22 supports the tested UID-scoped recorder injection, but the additional/
selectable remote-mic product path and physical-mic coexistence remain open.
DEBUG Media usage now gives an A41 SPEAKER track; Tablet is absent, so its
route-after-fix/human output is not claimed. Three final S9 -> A41 normal
technical/lifecycle Sessions passed, but rejection/zero-fill persisted in all
three steady windows. This does not close the quality defect. Protected submit
to pre-decoder receive is the first additional irregular boundary; no speculative
buffer/priming/pacing fix was applied. Final gates954 JVM tests and native
Debug/Release22/22 each. New microphone work stays uncommitted; full RFC-005K
background and timeout audit remains deferred.

## 2026-10-01 bounded regression / current quality boundary

Owner-bound liveness at715b215 is unchanged. The
[current cadence/input report](MicrophoneAudioCadenceAndVirtualInput.md)
records three normal S9 -> Tablet DEBUG microphone Sessions and a separate
SystemAudio control under one Enable Host, each returning Host Waiting and
Client Ready. Final Disable Host leaves helpers/secrets0 and publication stopped.
No idle-life redesign or repeated18-hour test was needed for diagnostic-only
changes. Full background/timeout/power work remains deferred to RFC-005K.

Additional cadence disturbance is already seen at Tablet kernel receipt,
before application authentication/decoder, but exact radio/send-kernel cause
is not proven. Quality remains PARTIAL: three >=60-second steady windows
retain rejected decoded PCM and callback zero-fill, buffers unchanged. Tablet
DEBUG tracks now route to SPEAKER technically; A41 audibility is confirmed
by the operator, Tablet human audibility is not. Scoped Tablet input injection
is separate from first-class product microphone integration, which is blocked
at the tested normal-consumer boundary. Final gates959 JVM/0/0/0 and native
Debug/Release22/22 each; new partial microphone diagnostics stay uncommitted.
