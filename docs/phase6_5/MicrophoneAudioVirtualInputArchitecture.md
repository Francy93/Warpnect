# MicrophoneAudio virtual input architecture and DEBUG playback

Latest [final integration evidence](DirectShellGlobalMicrophoneFinalClosure.md)
validates exact-path quality3/3 using the same Session-owned global recorder
mix, capture-preset rules1/7/5, UID NONE and independent DEBUG sink. Real PCM is
dropped with no consumer; maintenance zeros retain selectability; original
consumer-window/generation/fixed40ms freshness guard remains. Android's existing
4104-frame track latency is disclosed separately from80/70ms DEBUG. This is
capture-policy integration, not universal independently selectable external-mic
identity. A41 permission/S7 product-adapter boundaries remain, priority roles
are not applied, release unchanged. Following partial notes are historical.

Current [residual quality evidence](MicrophoneReceiverSchedulingQuality.md)
adds at most one output-burst admission wait for an almost-fitting DEBUG block,
not a new queue or Android-input policy.81F10D24 final cycle1 remains FAIL on
residual starvation. UID NONE, Session ownership, natural routing and40ms IPC
freshness remain unchanged; portability/priority/recovery still quality-gated.

Latest [receiver scheduling evidence](MicrophoneReceiverSchedulingQuality.md)
changes only the MicrophoneAudio receive worker scheduling plus bounded probes,
not the Session-owned global recorder policy or destinations. UID RULES=NONE,
natural input selection, no-consumer discard/window guard and fixed40ms IPC
remain. Final quality campaign stops on first peer/prerequisite loss; no new
virtual-device/priority/recovery architecture or release promotion.

Latest [source-clock follow-up](MicrophoneSourceClockRecovery.md) changes only
microphone pacing and bounded DEBUG observation, not this architecture. One
Session-owned global recorder policy, UID NONE, no ordinary-consumer preference,
maintenance silence/no-consumer discard and consumer temporal guard are retained.
First counted primary quality cycle fails; no global-quality/priority promotion.
No additional device-model path, Root or frozen-plan recovery is introduced.

Current [2026-10-03 evidence](MicrophoneConsumerClockContinuity.md) retains ONE
Session-owned capture-preset recorder/RemoteSubmix backend, UID RULES = NONE.
The three-cycle functional path works, but shared DEBUG/AudioTrack continuous
underflow keeps quality PARTIAL. No-consumer/window/fixed40ms IPC drops remain
distinct from80/70ms DEBUG reserve. No first-class HAL device or second OEM path
is claimed. A41's real registration and S7's reachable legacy context both hit
MODIFY_AUDIO_ROUTING authority; no Root/Shizuku workaround. Tablet setter/clearer
authorization is observed only with an invalid preset and unchanged roles;
valid forced routing/abnormal role rollback remain NOT TESTED.

Historical preceding continuation:

Current [consumer-transition evidence](MicrophoneConsumerTransitionQuality.md)
physically exercises the temporal guard, no-consumer drops, MIC open/reopen,
generation freshness and restoration. The local FIFO now expires stale heads
before admission; its provisional fixed age ceiling is40ms, not the separate
80/70ms DEBUG reserve. Two complete primary Sessions improve steady IPC delivery,
but the third fails with platform link/radio loss. Quality remains PARTIAL;
no new input architecture, UID rules, priority state or release behavior.

Historical preceding continuation:

Current [quality follow-up](DirectShellGlobalMicrophoneInjectionQuality.md)
keeps one no-UID recorder-policy architecture. Candidate consumer observation
matches Android's own RemoteSubmix address; absent consumers receive no retained
real microphone content, while idle zeros keep the route selectable. A new
local consumer-window guard rejects queued PCM predating observed activation
or reopening; it is software-tested but physically unvalidated.80ms age
expiration is local authenticated IPC, not a whole-platform/E2E latency bound.
Session owns policy; decoder recreation does not. Final quality remains PARTIAL
and priority/alternative privileges are not promoted on this candidate evidence.
Restored Wireless Debugging allowed a first counted candidate cycle, which
failed quality despite ordinary MIC997Hz signal and restoration. Opening-flush
and smaller track-threshold experiments were reverted. A later S9/Tablet peer
LAN failure blocks the fresh primary campaign, not policy registration.

Latest [global DirectShell integration](DirectShellGlobalMicrophoneInjection.md)
physically validates one DEBUG capture-preset recorder policy without UID rules
on Tablet/API33, final CF5C311C APK. Independent ordinary MIC consumers receive
the protected physical S9 signal in3/3 fresh Sessions; VOICE_COMMUNICATION and
CAMCORDER also receive it. A dynamic Android input is enumerated alongside
built-in inputs; normal stop and helper death restore physical routing. This
is global preset redirection, not a freely selectable universal external mic,
lossless quality or release product claim. Session ownership now survives
decoder resets. Startup/consumer-absent IPC pressure and late DEBUG loss remain
open; forced preference is NOT TESTED (getter-only capability investigation).
A41 permission/S7 context limits use the same backend, not OEM alternatives.
Earlier scoped experiments and this report's older PARTIAL snapshot remain
historical; the current report supersedes only their current-status wording.

Later 2026-10-01 [portable injection investigation](DirectShellMicrophoneInjectionQualification.md)
distinguishes capture-preset-wide record injection from this report's scoped
injection and freely selectable first-class input questions. That earlier ordered
gate had not passed and no global policy or optional priority was implemented
at the time; later cadence closure and the global follow-up supersede its status.
Earlier selection/permission failures below remain evidence for their exact
mechanisms, not proof that all capture-preset-wide routing is impossible.

Date: 2026-09-30. Overall result: **PARTIAL**. This report supersedes
underspecified microphone destination language, not the historical Phase 3
implementation or protected-transport evidence. Speaker playback is not a
production virtual microphone.

## 1. Repository baseline

Branch: `codex/directshell-production-runtime-integration`.
Starting HEAD: `809b7ddd48e9db192a86de17b59abdfd607916fd`.
Master: `032b59276a0e01e4674e807a8ffb5b5b86e9150e`.
Upstream: `origin/codex/directshell-production-runtime-integration`,
`9d0b4dce7ea04a82e81778de6296cc1096775d31`.
The initial worktree contained validated owner-liveness changes, retained
numeric PCM diagnostics, generated native binaries/reports, older screenshots,
UI dumps, `artifacts/`, `.codex-remote-attachments/`, and a line-ending-only
`AndroidInputCaptureController.kt` status. Nothing was reset or discarded.
An empty stale Git index lock was removed only after verifying its age and
that no Git process was using it; no index contents or repository changes
were discarded.

The owner-liveness slice was checkpointed locally as
**`715b2151af2c8ecac165729178266b24e5cae91b`**,
`fix(privileged): bind DirectShell helper lifetime to Host ownership`.
There are now 19 local commits ahead of upstream, zero behind; staging is empty.
New destination/probe/routing/diagnostic/test/docs work remains uncommitted.
No push, merge, rebase or force-push.

## 2. Product versus diagnostic destination

- **PRODUCTION INTENT:** Client physical microphone -> protected Session ->
  Host decode -> Android virtual microphone input -> ordinary Host consumer.
- **DEBUG:** the same remote decoded PCM -> Oboe playback -> Host output.

`MicrophoneAudioDestination` explicitly distinguishes
`HostVirtualInputNotImplemented` from `DebugPlayback`. Release capability
observation does not offer the diagnostic receiver, and normal pipeline
bindings reject a release microphone playback receiver. DEBUG opt-in is now
labelled `Microphone to Host speaker (DEBUG)` and telemetry reports
`microphone_destination=DEBUG_PLAYBACK_ONLY` on the Host receiver.
MicrophoneAudio stays optional. RFC-006I still requires Video, SystemAudio,
Input only. Audio Payload V1, channel direction and protection are unchanged.

## 3. Sink audit

| Sink / component | Classification | Actual role |
| --- | --- | --- |
| Host microphone branch of `DefaultAndroidSessionPipelineBindings` / `DefaultAudioReceiverSessionController` / `NativeOboeAudioPlaybackController` | DEBUG | Diagnostic remote-microphone playback only; release branch unavailable |
| The same generic receiver and Oboe controller for Client SystemAudio | PRODUCTION | Existing Host-to-Client speaker playback, unchanged |
| `OboeDecodedPcmAudioSink` | TEST / unused normal-session adapter | Referenced by playback unit tests; normal Session uses its own decoded sink |
| `SystemAudioE2eToneActivity`, `SystemAudioProductionValidationActivity` AudioTracks / structural sinks | DEBUG / TEST | Bounded normal playback sources and capture fixtures, not microphone destinations |
| Fake decoder/playback/sink implementations in JVM / Android tests | TEST | No hardware or product capability claim |
| `ScopedVirtualMicrophoneProbe` virtual-source AudioTrack | DEBUG EXPERIMENT | Fixed generated signal to one Android input policy; no Session media ingress |
| Production virtual microphone provider / discard fallback | MISSING | No silent speaker/null substitution is installed |

Historical roadmap `sink/output` language was underspecified, not evidence
that a system-wide microphone input was implemented in Phase 3.

## 4. Framework feasibility and exact devices

| Device | Build / scope | Observation |
| --- | --- | --- |
| A41 SM-A415F, Android 12/API31, `R58N52B32JJ` | `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys` | Shell UID2000 lacks `MODIFY_AUDIO_ROUTING`; empty policy registration returns -1. DirectShell input-policy prerequisite unavailable on this exact build |
| S22 SM-S901B, Android 16/API36, `R3CTB0MLRMF` | `samsung/r0sxeea/r0s:16/BP2A.250605.031.A3/S901BXXSOGZH3:user/release-keys` | Shell has routing permission. Registered UID-scoped injection reaches a normal-process AudioRecord consumer; not a product remote-mic qualification |
| Tablet X700_EEA/API33 | Not connected in this campaign | NOT TESTED. Earlier playback and liveness evidence is preserved, not extended |
| S9 SM-G960F, Android 10/API29, `23eaec0f12047ece` | `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys` | Physical microphone Client and SystemAudio Client. Modern DirectShell Host/bootstrap/context boundary unsupported; no virtual-input experiment |
| S7 edge SM-G935F, Android 8/API26, `9886734e4937395657` | `samsung/hero2ltexx/hero2lte:8.0.0/R16NW/G935FXXU8EVH2:user/release-keys` | Connected but credential-locked; no unlock bypass and no new input qualification |

Android's input AudioMix is a replacement route for matching recorders, not
proof of an independently selectable microphone device for every application.
The injector role and UID rule coexist in
[Android 13 AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/audiopolicy/AudioMixingRule.java)
and [Android 16 AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/audiopolicy/AudioMixingRule.java).
The older API31 rule builder does not expose that same explicit role API.
None of this supplies an OEM permission grant automatically.

The registration gate requires routing authorization for this non-projection
input experiment; playback-capture/MediaProjection permission is not an
alternative input-injection authorization. See
[Android 12 AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/audio/AudioService.java).
The measured A41 denial and S22 grant are deliberately not a model allowlist.

## 5. DirectShell result

Two DEBUG-only, zero-argument `app_process` probes use the existing verified
real shell Context and reject any UID other than 2000:

- `VirtualMicrophonePolicyProbe`: permission/API resolution, empty policy
  registration and unconditional unregister; no routing mix.
- `ScopedVirtualMicrophoneProbe`: ten-second, Warpnect-debug-UID-only injector
  mix, fixed generated signal, bounded write deadline, release/unregister.

The scoped probe resolves the actual debuggable `io.warpnect` UID, checks
`MODIFY_AUDIO_ROUTING`, uses `MIX_ROLE_INJECTOR`, `RULE_MATCH_UID` and
`ROUTE_FLAG_LOOP_BACK`. It accepts no arbitrary target, command, Binder call,
PCM payload, path, descriptor or duration. It has no listening endpoint.
This is external trusted ADB/shell diagnostic execution, not production
bootstrap or authenticated Session injection.

Result: **A41 prerequisite unavailable; S22 scoped framework injection
possible. Universal DirectShell virtual microphone support is NOT established.**
For the exact A41/build the result is
`VIRTUAL_MICROPHONE_DIRECTSHELL_NOT_AVAILABLE` at policy authorization.
Do not emit a blanket `VIRTUAL_MICROPHONE_DIRECTSHELL_NOT_AVAILABLE` verdict
when the S22 counterexample exists.

## 6. DirectRoot implication

UID0 may change registration authorization, but it does not itself create a
selectable HAL input, establish valid attribution, preserve physical-mic
coexistence or provide Session ownership. No Root commands or emulator work
were run. Root virtual input and production root-manager authorization remain
NOT IMPLEMENTED. Physical Root input remains NOT VALIDATED.
No custom Audio HAL, framework image, vendor policy, SELinux or persistent
platform patch was attempted.

## 7. Implemented versus missing virtual slice

Implemented: bounded framework probes, ordinary AudioRecord observer, truthful
destination model and DEBUG routing. Missing: production virtual-input provider,
protected Session-to-policy PCM handoff, target selection/consent, consumer
coexistence policy, per-Session generation authority and capability acquisition.
No generic privileged AudioPolicy service was introduced.

## 8. Standard Host consumer proof

`StandardMicrophoneConsumerInstrumentationTest` uses only normal Android APIs:
existing RECORD_AUDIO permission, `AudioRecord.Builder`, source `MIC`,
48 kHz mono PCM16, a bounded 12-second nonblocking read, numeric RMS and a
1193 Hz projection. It neither calls Warpnect capture/controllers nor reads a
Warpnect PCM ring. It runs at normal app UID10384, not shell.

S22 corrected probe owner PID25487/UID2000 wrote **477192 frames**; ordinary
consumer PID25522 received the generated input through Android. Eight ON
windows had RMS **1413.67-1413.84**, with 1193 Hz amplitude
**1999.09-1999.60**, matching the producer amplitude 2000. After unregister,
the same consumer returned to physical input: OFF RMS **33.72 / 95.26** and
1193 Hz amplitude **1.31 / 0.62**. It then released its AudioRecord.
Both instrumentation executions completed 1/1; the first execution's test
PASS was only ordinary physical capture, not successful virtual injection.

## 9. Acoustic proof and its limitation

The virtual-source experiment above uses generated PCM. It is **not** the
required acoustic Client -> protected Session -> virtual Host input chain.
That chain remains NOT IMPLEMENTED / NOT VALIDATED.

A separate final-APK S9 -> A41 DEBUG Session used an eight-second S22 speaker
fixture. S9 capture RMS changed from ambient approximately 28-38 to 53.95;
A41 decoded RMS was 54.30 with peak1317, then returned to approximately
22-31. This modest energy change lacks frequency/source-isolation evidence;
do not treat it as conclusive acoustic-source correlation or A41 speaker
emission proof. No voice or raw microphone recording was saved.

The stronger earlier S9 speaker -> A41 physical mic -> Tablet protected decode
OFF/ON/OFF control remains valid historical acoustic capture/transport evidence
in [the prior report](DirectShellIdleLivenessAndAudioPlaybackQuality.md), not
virtual-input or physical Tablet-output evidence.

## 10. Input device identity

S22 consumer reported new input **ID5213, type25 / REMOTE_SUBMIX** while
the scoped policy was registered. Physical input ID16/type15 remained
enumerated. The pre-existing type25 input ID21 is not evidence of a Warpnect
device. The route changed from 25/5213 to 15/16 after unregister.
IDs are observations, never constants in product logic. Registered and consumed
format was 48 kHz mono PCM16. Device name/address and full advertised format
lists were not captured; arbitrary-app preferred-device selection was not tested.

## 11. Physical microphone coexistence

Physical input stayed enumerated and resumed after the policy ended. This
does **not** prove simultaneous independent local/remote selection. The test
rerouted only matching `io.warpnect` recorders, rather than adding a globally
selectable Warpnect microphone. Other application UIDs were not targeted.
Do not silently adopt global physical-microphone replacement as product policy.

## 12. Session / policy lifecycle

The policy experiment stops its AudioTrack and unregisters in `finally`,
including the failed-construction case. No experiment process remained.
It is not yet a Session resource. Production virtual start/Disconnect/app-loss
cleanup remains NOT IMPLEMENTED and therefore is not credited by these tests.
The actual DEBUG Session sink still starts/stops with negotiated ownership.

## 13. Generation safety

Existing protected MicrophoneAudio uses negotiated channel/configuration
generation, receiver slot ownership and playback metadata generation checks.
Its teardown/restart tests remain passing. No new virtual source has access
to Session PCM, so stale Session PCM cannot enter the experimental policy.
A production virtual generation/handoff lifecycle must be designed and tested
before connecting these paths. Config generation1 in each independent Session
is not a claim that their Session/channel owners are reusable.

## 14. DEBUG playback route

Previous Tablet track: `VoiceCommunication` / `Speech`, **EARPIECE**.
Current MicrophoneAudio diagnostic Oboe usage: **Media / Speech**; SystemAudio
stays Game / Music. On the A41, AudioFlinger associated active app PID29939's
48 kHz mono track with output device0x2, **SPEAKER**. AAudio reported usage1.
No numeric device ID, communication-mode/global-route mutation or model
conditional was added. This uses standard media/default output semantics;
it does not force a speaker when an external route is selected.
Tablet route-after-fix evidence is NOT TESTED because it is disconnected.

## 15. Human audibility

**NOT_CONFIRMED.** The operator was away. AudioFlinger route and consumption
are technical evidence, not proof that anyone heard physical output. The two
earlier Tablet silence answers remain failures, not retroactively converted.

## 16. playback_rejected semantics / cause

`PcmPlaybackRing::submit` in `audio_playback_ring.cpp` increments
`pcm_frames_rejected` for generation, frame-count, byte-range failures and
full occupied-slot capacity. In these Sessions `PlaybackRingFull` and
`playback_ring_full_drops` identify valid decoded buffers rejected by a full
four-slot ring. This is real loss, not a harmless counter to suppress.
Partially consumed head slots still own their slot; available frame space
alone does not permit overwriting them.

Proven immediate causal chain: irregular delivery / queued bursts -> bounded
ring full -> decoded buffer rejected. The deeper cause of arrival irregularity
is unresolved. No guessed pacing or buffer change is credited as a fix.

## 17. underrun semantics / cause

The Oboe callback consumes available frames. `PcmPlaybackRing::consume`
zero-fills any missing remainder and increments `underrun_callbacks` once for
that callback plus exact `underrun_frames`. Platform AAudio xrun is a separate
counter. Telemetry counter labels containing samples at this boundary count
PCM frames; mono 240 frames happens to equal 240 samples, stereo does not.

Starting on one codec frame (240) can precede an initial 512-frame request;
the observed first callbacks inserted 272 silence frames / two underruns.
This explains a bounded initial event, **not** the continuing steady-state
zero-fill. A priming-only change cannot close the measured ongoing failure.

## 18. First irregular boundary

New bounded `AUDIO_CADENCE` snapshots separately observe local protected-submit
completion and pre-decoder protected-receiver delivery. `PCM_DELIVERY` observes
physical capture and decoded playback. Each uses its own process-local
`System.nanoTime`; remote capture timestamps are not subtracted.

Exploratory S9 -> A41: capture/send gaps mostly 15.7-21 ms; receiver gaps
33-44 ms with bursts up to7, mirrored by decoded delivery. Final cycle1
receiver max gap57.1 ms; steady windows included 23.5-48.2 ms, while capture
gaps were about16-20.9 ms and submit gaps18.8-22.9 ms. Cycle2 receiver gap
49.5 ms/burst5 was followed by comparable decoded cadence.

The first demonstrated **additional** irregularity is after protected submit
and before the receiver's pre-decoder callback. Current evidence does not
separate Wi-Fi/kernel delivery, thread scheduling, or time spent within receive/
protection/pump ownership. Decoder output is not the first added boundary.
Classification: **INCONCLUSIVE**, not a fabricated scheduler/network root cause.
The older capture/send batching and long-run playback resets remain recorded.

## 19. Minimal changes actually made

Destination availability is explicit and release-fail-closed; DEBUG sink usage
changed to Media; UI/telemetry label the sink; bounded numeric cadence was
added. The policy experiment's attribution was corrected after its first
failed run. No Audio Payload, Opus, SCL, Session, decoder scheduling, clocking,
drop policy, production virtual provider or full playback-quality fix changed.

## 20. Buffer / priming contract unchanged

Microphone format: **48 kHz mono PCM16, 5 ms / 240 frames / 480 bytes**;
Opus64 kbit/s. DEBUG ring: four codec frames = **960 frames / 20 ms**.
Start threshold: one frame = **240 / 5 ms**. A41 AAudio LowLatency/Shared:
burst256, buffer512, backend capacity2048. Requested exclusive fell back to
shared. None was increased; no new buffering latency is introduced. SystemAudio
still negotiates stereo on the control path.

## 21. Steady-state measurements on final APK

Final APK SHA-256:
`64948EA3A589CCE8F79B085F039CCF45C301E19740908EAA5C7D66E040D0CCA1`.
Exploratory APK: `5A07437EFE65F05A0D01A37F28603698CA62CDAF13C7DF932E4E7CA5CE57EFA5`.
No source changed after the final build; subsequent changes are documentation.

The windows start approximately five seconds after Host microphone media start.
All three are one uninterrupted decoder/playback instance. Do not subtract
native-instance counters across resets.

| Cycle | Host local monotonic ms start/end | Decoded PCM delta | Ring accepted delta | Rejected start/end/delta | Underrun callbacks start/end/delta | Zero-fill frames delta | Ring-full buffers delta | Platform xrun delta |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 570997562 / 571062562 (65.000 s) | 3120000 | 3057360 | 7200 / 69840 / **62640** | 41 / 741 / **700** | 62576 | 261 | 0 |
| 2 | 571264937 / 571329938 (65.001 s) | 3120000 | 3019920 | 12000 / 112080 / **100080** | 69 / 968 / **899** | 99952 | 417 | 0 |
| 3 | 571470347 / 571535347 (65.000 s) | 3120000 | 3044880 | 14640 / 89760 / **75120** | 73 / 725 / **652** | 74912 | 313 | 0 |

The first-five-second accumulated rejected/underrun values are the table's
start values, not claimed benign startup transients. Capture/encode/send,
receive/decode and consumption all advanced; no capture drops or transport
send failures in the sampled final windows. Ring occupancy stayed within
0-960 with HWM960. It being bounded does not make the quality acceptable.
Current quality acceptance is **FAIL / PARTIAL**, not CLOSED.

Exploratory baseline also failed quality: a 65.002-second window had decoded
3120000, accepted3022560, rejected97440, underrun callbacks820, zero-fill97568,
ring-full buffers406, platform xrun0. It remains separate from final attempts.

## 22. Repeated Session campaign / all attempts

One continuous normal A41 Enable Host, starting 21:25:50.577 in its own log,
froze **SINGLE / DIRECT_SHELL**. App PID29939 and runtime generation1 remained;
three helper owners authenticated, no Root/Shizuku producer or hidden fallback.
S9 PID29522 opted into DEBUG microphone playback through the normal UI.

| Attempt | Host media accepted | Client mic stopped / closed | Technical path / lifecycle | Quality |
| --- | --- | --- | --- | --- |
| 1 | 21:26:43.723 | 21:28:50.550 / .593 in Client log | Streaming -> normal Disconnect -> S9 Ready / A41 Waiting; rediscovered for next Session | FAIL: persistent rejected/underrun |
| 2 | 21:31:11.006 | 21:32:44.704 / .743 in Client log | Same enabled Host, fresh channels/sink, Streaming -> normal Disconnect -> Ready / Waiting | FAIL: persistent rejected/underrun |
| 3 | 21:34:36.471 | 21:36:07.047 / .072 in Client log | Same enabled Host, Streaming -> normal Disconnect -> Ready / Waiting | FAIL: persistent rejected/underrun |

These device-local log times identify events, not cross-device latency. Each
Connect was counted once; no retries or Host toggles between the three.
They are **3/3 technical/lifecycle PASS, 0/3 quality PASS**. No virtual-input
Session was exercised. A prior final-APK automation tap while the A41 launcher
was foreground did not invoke Enable Host; it is recorded below as a harness
failure, not counted as an admission or replaced by a fictitious PASS.

## 23. Liveness checkpoint and regression

Before commit, the previous full-gate APK
`7491FEA515D013B0DFF3137CEFDB7E43AFAC4CB5DFBD905D4BA1B8116AF6FA42`
passed one bounded A41 -> S9 normal SystemAudio Session: Enable
**20:06:51.975**, frozen plan20:06:59.097,
Host Available20:06:59.168, Connect20:08:32.570,
Disconnect20:09:12.291, Disable20:10:27.979. Owner generations were
7456415638232241469 (PCM PID10661), 4047611111288626987 (Input PID10767),
3690418802692944952 (Video PID10811), UID2000. Disconnect preserved Available,
and disable left helpers/secrets zero.

The corrected final-APK campaign above preserved these semantics. The three
feature owners began at generations286034152956453942,3322479255695297004,
3971651921114218506; expected consumed-feature replacements did not trigger
unexpected loss. Disable21:40:26.816 released runtime1 with leases0/warm3.
Prior unchanged-helper ~18-hour A41 / >18-minute Tablet idle, unexpected-loss
withdrawal and app-death EOF evidence remain in
[the liveness report](DirectShellIdleLivenessAndAudioPlaybackQuality.md).
The intermediate normal-Disconnect misclassified-as-owner-loss failure remains
historical. No second lifetime redesign or unnecessary 18-hour rerun.

## 24. SystemAudio regression

After the three microphone Sessions, DEBUG mic opt-in was unchecked. One normal
A41 -> S9 SystemAudio Session reached Streaming through the same Host with
DirectShell stereo capture, ring, Opus and protected receiver. A41 tone ran
21:38:20.607-21:38:28.621,389128 frames. S9 decoded windows went from RMS0
to1058.72/2305.62/1442.68 and back to0. At Host21:39:11.698,
capture3356400 and encoded3356640 frames advanced, with ring overruns0 and
send failures0. Disconnect restored Waiting; normal Disable released helpers.
This is technical regression, not new human-audible/zero-loss qualification:
S9's existing playback pressure was nonzero. SystemAudio route code is unchanged.

## 25. Video / Input regression

Normal frozen DirectShell acquisition and Session Video encode/receive/Surface
release advanced in the final Sessions. Input gateway prepared/ran with the
DirectShell backend and no injection errors. No new human reverse-Input or
physical-display presentation proof is claimed. Existing provider, planner,
Session and Input regression tests pass. No post-freeze provider migration.

## 26. Resources and anomalies

After every Disconnect the microphone stopped/closed, Oboe stream stop/close
and track removal were observed, and Host returned Waiting. Host-level warm
helpers intentionally remained until Disable. Final disable: helper and wrapper
rows0, temporary DirectShell secrets0, UI Ready, runtime leases0 and publication
stopped. Scoped injection process0; policy unregistered; normal observer released.
No continuous audio recording was persisted. A41 music volume15 and S22 volume9
were temporarily7 and restored/verified. Verified-PID logcat collectors stopped.

A final A41 FD census contained162 descriptor links (163 listing rows including
the `total` header), including11 sockets and7
ashmem rows, plus graphics/framework descriptors. There is no equivalent
per-cycle census/ownership classification here, so **no global FD leak-freedom
or monotonic-growth verdict** is claimed. A closed AudioRecord/Oboe process
state is not proof that every framework FD has vanished.
An independent final `ss` listener census was unavailable because Android
denied its netlink socket. Helper/wrapper disappearance and semantic socket
closure are proven; no global or separately measured zero-listener claim.

| Preserved anomaly | First trustworthy boundary | Classification | Comparison / action |
| --- | --- | --- | --- |
| A41 policy unavailable | Shell routing permission false; register=-1 | ENVIRONMENTAL_PRECONDITION | S22 shell has permission. Do not grant privileged permissions or call A41 capture compatibility broken |
| First S22 scoped injection | AudioFlinger rejects UID2000 attribution package `android`; track uninitialized | TEST_HARNESS_DEFECT | Empty policy registration had passed. Corrected only fixture AudioTrack Context to actual shell; one new bounded experiment passed. Original FAIL remains |
| Initial final-campaign UI tap | A41 launcher, not Warpnect MainActivity, was resumed; no Host preparation event | TEST_HARNESS_DEFECT | Explicitly open MainActivity and verify Ready before new counted campaign; no product timeout/retry change |
| Exploratory and each final DEBUG quality window | Real PlaybackRingFull and callback zero-fill in steady state | INCONCLUSIVE | Same failure across baseline and three technical cycles. Extra cadence disturbance first bounded to submit->receiver; no guessed buffer/sleep fix |
| Close-time Socket closed / DestroyFailedException warning | Existing ADB library cleanup after successful feature launch/replacement | EXTERNAL_PLATFORM_OR_PROVIDER_FAILURE | No runtime owner LOST/publication withdrawal; Ready/Waiting and replacements pass. Warning is not converted to a failed capability or suppressed |

The failed original Tablet human controls and prior playback resets are retained
in their original report. They were not reproduced on a disconnected Tablet.

## 27. Focused tests

- `MicrophoneAudioDestinationTest.releaseDoesNotOfferPlaybackAsVirtualInput`.
- `MicrophoneAudioDestinationTest.debugOffersOnlyAnExplicitDiagnosticDestination`.
- `AudioCadenceDebugProbeTest.reportsLocalBatchingWithoutUsingRemoteTimestamps`.
- `AudioCadenceDebugProbeTest.observationsAreBoundedAndDoNotChangeFrameIdentity`.
- Retained `PcmDeliveryDebugProbeTest` two cases: borrowed-buffer position/energy,
  local gaps/bursts and bounded logging; not new physical audibility tests.
- New Android standard-consumer test: public AudioRecord, numeric observations,
  bounded stop/release. Its PASS alone does not assert virtual input.

Existing owner-lease, lost-runtime publication, decoder/playback generation,
mono accounting, ring-full/zero-fill, stop/restart and SystemAudio tests remain.
No tests pretend to prove an unimplemented virtual Session lifecycle or sound.

## 28. JVM total

Final `testDebugUnitTest --rerun-tasks`: **954 tests / 153 suites;
zero failures, errors, skipped**. Previous working-tree baseline was950,
including the two retained PCM diagnostic tests; four new destination/cadence
cases make954. This is the exact XML total, not inferred from task output.

## 29. Native tests

Native Oboe usage selection changed. Debug CTest **22/22 PASS** (22.73 s),
Release CTest **22/22 PASS** (4.58 s), both rebuilt first. The host CTest suite
does not compile the Android-only Oboe route branch; Android APK builds and
the real A41 AudioFlinger route evidence cover that separate boundary.

## 30. Software gates

| Gate | Final result |
| --- | --- |
| `git diff --check` | PASS |
| `:app:ktlintCheck` | PASS |
| `:app:lintDebug` | PASS |
| `:app:testDebugUnitTest --rerun-tasks` | PASS,954/0/0/0 |
| `:app:assembleDebug` | PASS |
| `:app:assembleDebugAndroidTest` | PASS |
| `:app:packageDirectShellProbeDex` | PASS |

Full final Gradle run completed in9m28s/100 executed tasks. Earlier formatting
and compile diagnostics are retained in build output, repaired before the
final APK. Gates do not upgrade physical quality or virtual-product status.

## 31. Documentation

This report, `CurrentNonRootDebt.md`, `README.md`, `FutureRoadmap.md`,
`AndroidCompatibilityMatrix.md`, `MicrophoneAudioE2EQualification.md` and
`DirectShellIdleLivenessAndAudioPlaybackQuality.md` distinguish current status
from historical facts. RFC-005K is not edited or declared implemented.
No RFC-003 historical implementation-complete claim is erased.

## 32. Remaining worktree / evidence index

- Source: explicit destination guards, DEBUG UI/telemetry, native usage change,
  cadence observation, retained PCM observation and two debug policy probes.
- Tests: new destination/cadence JVM cases, retained PCM JVM cases, standard
  Android AudioRecord observer.
- Docs: seven files listed above. No new PowerShell/tooling changes.
- Generated: app build/lint/test reports/APKs/DEX, dirty native Debug/Release
  binaries/test logs. They are not checkpoint assets.
- Preserved unrelated: line-ending-only Input capture status, old screenshot/UI
  dumps, `artifacts/` and `.codex-remote-attachments/`.
- A later empty `.git/index.lock` dated20:21:31 is present at final inspection;
  it was left untouched. There is no further commit/push in this task. It is
  machine-local Git state, never a checkpoint asset.

Local evidence, excluded from commits, is under `app/build/reports/`:

- `virtual-mic-prerequisite-R58N52B32JJ.txt` and `-R3CTB0MLRMF.txt`.
- `scoped-virtual-input-s22.txt`, native/service logs and first standard-consumer
  log/result: preserved attribution failure.
- `scoped-virtual-input-s22-2.txt`, `standard-mic-consumer-s22-2.log` and result:
  corrected scoped framework experiment.
- `mic-route-baseline-R58N52B32JJ.log`, `-23eaec0f12047ece.log` and A41
  AudioFlinger dump: exploratory route/quality failure.
- `mic-final-R58N52B32JJ.log`, `-23eaec0f12047ece.log`: three counted final
  microphone Sessions and the separate SystemAudio control.
- `virtual-mic-final-gates.log` and standard Gradle/native test output.

## 33. Deferred architecture and security

The first A41 limiting boundary is policy authorization. The S22 experiment
passes that boundary, but product additional/selectable input, physical-mic
coexistence, target consent and protected generation-bound PCM handoff remain
open. A scoped injector that reroutes another UID is not automatically the
required independently selectable virtual device. Define this distinction in
a separate remote-microphone destination/AudioPolicy ownership design before
implementation. If HAL/framework/vendor changes prove necessary for the hard
product requirement, stop at that architecture boundary; no platform fork here.

No Root, flashing, unlock, wipe, SELinux/vendor changes, arbitrary AudioPolicy
mutation, generic Binder/FD/command RPC, unauthenticated microphone transport,
AEC, Microphone-to-speaker product substitution, hidden retry or provider switch.
The ten-second specific policy experiment is explicitly an intentional temporary
debug route, not a claim that no routing was touched. Ordinary app RECORD_AUDIO
consent and authenticated Session protection are unchanged.
Full background/screen-off/power/timeout audit remains RFC-005K/future-RFC work.
HOST_AND_REMOTE and KEEP_ON / PRIVACY_BLANK / SCREEN_OFF remain deferred;
RFC-005J and RFC-005K NOT STARTED; physical DirectRoot remains separate/blocked
on already-rooted hardware.

## 34. Status / completion discipline

Owner-bound DirectShell: checkpointed, prior exact-path validation retained,
bounded current lifecycle regression PASS.
Virtual input: **PARTIAL**, with S22 scoped Android-input feasibility evidence
and A41 authorization limit, no product remote-mic implementation.
DEBUG speaker route: corrected and technically observed on A41, Tablet and human
output NOT CONFIRMED. Steady-state playback quality: **PARTIAL / NOT FIXED**.

Do not emit `MICROPHONE_AUDIO_VIRTUAL_INPUT_VALIDATED`, a universal
`MICROPHONE_AUDIO_VIRTUAL_INPUT_DIRECTSHELL_LIMIT_CLASSIFIED`,
`MICROPHONE_AUDIO_DEBUG_PLAYBACK_QUALITY_CLOSED`, or the combined validated token.
The truthful overall completion status is **PARTIAL**.

## 35. Next single task

**MicrophoneAudio protected-receive cadence root-cause closure**: separate
kernel/network arrival from receive/protection-thread delivery on an available
normal non-Root pair, preserve current failure windows, fix only a proven
bounded cause, then run a fresh quality campaign. Do not enlarge the ring or
start broader Phase 7 optimization to mask it. This task is recommended only,
not automatically implemented.

## 2026-10-01 superseding first-class selection / cadence evidence

[Current report](MicrophoneAudioCadenceAndVirtualInput.md) keeps this report's
failed campaigns and scoped S22 experiment historical. Tablet is now connected.
Its shell has routing permission and a dynamic recorder mix produces a distinct
Android-enumerated submix port alongside the built-in mic. But a normal consumer
outside the matching-UID policy cannot open the explicitly preferred port:
getInputForAttr=-1, start fails, read=-3. A matching-UID ten-second control
receives1193 Hz/RMS1413.67-1413.84 through ordinary AudioRecord, then unregister
restores the built-in route. This proves scoped injection, **not** first-class
input selection. A41 still fails routing permission/policy registration.

The first API33 selection fixture failed because AudioTrack.Builder.setContext
does not exist there; one corrected legacy-attribution run is preserved separately.
No framework/vendor/HAL mutation, generic device-connection RPC, permission grant
or Root. Overall first-class product input remains blocked on investigated
DirectShell boundaries, not universally impossible; Root is not automatically
sufficient. S9 -> A41 DEBUG speaker audibility is now operator-confirmed.

Kernel ingress and local read/authentication measurements narrow the extra
cadence disturbance before decoder/ring. Causality/quality remain PARTIAL;
no larger ring, pacer or codec change. Final same-APK campaign and exact gates
are in the new report:959 JVM, zero failures/errors/skips; CTest22/22 in each
native configuration. All new partial microphone work remains uncommitted.
