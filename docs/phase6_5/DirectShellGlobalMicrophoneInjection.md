# DirectShell global MicrophoneAudio integration

## 2026-10-03 Final Exact-Path Closure

[Final evidence](DirectShellGlobalMicrophoneFinalClosure.md) supersedes current
status notes below, which remain historical. Frozen C782DD9D r2 completes3/3
S9/API29 ->Tablet/API33 MIC Sessions122.247/121.492/121.393s under one Enable.
Ordinary close/reopen steady IPC/track/DEBUG rejection/underrun/xrun deltas0;
real997Hz, UID NONE, natural routing, restoration and helper-loss withdrawal.
`DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_QUALITY_VALIDATED` is exact-path only.
Fixed40ms IPC remains separate from80/70ms DEBUG reserve; no release, universal
qualification, portability-extension or forced-priority token is claimed.

## 2026-10-03 Current Status

Current continuation: [residual scheduling/admission evidence](MicrophoneReceiverSchedulingQuality.md).
Peer communication and Debug wireless are restored. A one-burst microphone
admission wait corrects whole240-frame rejection with only224 free frames,
without capacity/codec/socket changes. Final81F10D24 cycle1 still FAIL:
AudioTrack starvation and reopened sender-side gaps remain;2/3 NOT RUN.
1017 JVM/22+22 native gates pass. Functional global token retained, quality
PARTIAL, no portability/priority/release/recovery promotion or commit.

[Receiver scheduling continuation](MicrophoneReceiverSchedulingQuality.md) is
the latest boundary: microphone-only audio priority reduces measured receiver
starvation without changing buffers; one diagnostic is stable. Final43FB71D6
cycle1 loses peer communication/Tablet Wi-Fi, Debug wireless becomes0, and Host
publication is truthfully withdrawn; cycles2/3 NOT RUN. Gates1016 JVM and22/22
CTest each pass. Functional token retained; quality PARTIAL, no checkpoint or
priority/release promotion. Earlier campaigns below remain historical.

[Latest source-clock continuation](MicrophoneSourceClockRecovery.md) supersedes
the following campaign status without erasing it. The pacer now repays bounded
scheduling debt, but the D28F6136 first counted consumer-window cycle still
fails steady quality and normal teardown; cycles2/3 are NOT RUN. A separate
extended continuous-reader diagnostic has80s steady deltas0, not3/3 acceptance.
A subsequent diagnostic loses the Tablet network/Debug wireless prerequisite.
Functional global injection remains VALIDATED; quality PARTIAL, no new token,
checkpoint, priority or release promotion. Existing80/70ms and fixed40ms IPC
are separate and unchanged. Full details/counters/anomalies are in that report.

[Clock-continuity campaign](MicrophoneConsumerClockContinuity.md): final66A8B330
completes three108s S9 ->Tablet Sessions under one Enable Host, ordinary MIC
open/close/reopen,997Hz signal, natural RemoteSubmix selection and physical
restoration. IPC admission rejection0/0/0; quality remains PARTIAL because
cycle3 has recurring continuous DEBUG/AudioTrack underruns. A later diagnostic
fails as Tablet Wi-Fi loss makes Android disable Debug wireless again. Healthy
output-clock retention/explicit-discontinuity re-priming preserve80/70ms; no
new buffer/codec/socket change. Global functional token remains valid, not a
quality token. A41 and S7 actual policy registration is permission blocked;
Tablet priority authorization-only probe does not set a valid preference.
Gates1007 JVM/161 suites, native Debug/Release22/22. No checkpoint or release promotion.

## Current quality follow-up

The following continuation is historical; the current-status section above supersedes it.

Latest [consumer-window/queue continuation](MicrophoneConsumerTransitionQuality.md)
supersedes the current-status paragraph below: Tablet is connected again,
ordinary MIC open/reopen/restoration is physically exercised, and the provisional
40ms freshness-aware IPC queue improves two continuous-reader Sessions on
858A76A0. The third attempt fails during platform radio/path loss. Quality is
still PARTIAL; the functional token remains valid. Failed replacement withdraws
Host publication with a typed Wireless Debugging prerequisite. No portability,
priority, recovery or release promotion. DEBUG playout80/70ms is unchanged.

Historical preceding continuation:

[Startup/no-consumer investigation](DirectShellGlobalMicrophoneInjectionQuality.md)
preserves the functional result below, but quality remains PARTIAL. Candidate
consumer-aware discard/idle silence,80ms local IPC age expiration, outside-lock
Android device resolution and initial output-clock retention have not closed
quality: after Wireless Debugging restoration, the unchanged candidate's first
counted111.017s cycle failed, while natural MIC open/reopen and physical997Hz
signal worked. Opening-flush and smaller track-threshold experiments failed
and were reverted. A new local consumer-window admission guard is physically
unvalidated. A later bidirectional S9/Tablet peer LAN failure was
[cleared by one Tablet rejoin](S9TabletPeerLinkRecovery.md), with ping/TCP/UDP
controls passing. Tablet then became unavailable by operator confirmation;
primary acceptance is paused, not substituted on S22/A41. No new Session counted.
Wireless Debugging needs normal restoration after rejoin. Earlier Wi-Fi/media halts
remain historical and INCONCLUSIVE where their cause was not established. A distinct
failed helper-replacement publication bug is corrected in source, not yet
physically revalidated. No new quality/portability/priority token or checkpoint.
This section supersedes current-status wording only, not any historical counts.

## Current result after resumed physical acceptance - 2026-10-02

**DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED**, limited to the exact
S9/API29 -> Tablet/API33 DEBUG integration and ordinary consumers below.
This is global capture-preset routing, not a UID-targeted test, universal app
compatibility, a freely selectable external microphone, or release readiness.
**DIRECTSHELL_REMOTE_MICROPHONE_PRIORITY_CONTROL_NOT_TESTED** refers to forced
set/clear: API inspection and a read-only getter probe were completed separately.
Overall release integration and supported-path quality remain **PARTIAL**.
In particular, the final first MIC cycle has late DEBUG underruns; no new
all-zero quality token is earned. The previous frozen cadence APK/token remains
accepted for its original path, not automatically transferred to this APK.

### Repository, frozen build and evidence ownership

Branch `codex/directshell-production-runtime-integration`; starting and ending
HEAD `715b2151af2c8ecac165729178266b24e5cae91b`; master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, ahead19/behind0.
Checkpoints `92347d0`, `809b7dd`, `715b215` were verified. No commit/push/merge/
rebase/reset/clean. The pre-existing empty `.git/index.lock` is preserved.
Initial reconciliation was staging0/unstaged196/untracked71, with39 source/debug,
12 tests,10 docs,159 generated native and47 unrelated directory-collapsed paths.
Final status is staging0/unstaged201/untracked85:52 source/debug,17 tests,
11 docs,159 generated native and47 unrelated paths. The large inherited cadence/
native changes are not silently included in an injection checkpoint.

Frozen final APK SHA256:
`CF5C311C3C7E30E8A7896EA44EA4DE1395859F4835AF866FF9018FB4699DF962`.
Installed on Tablet/S9 and A41. No source changes after the full gates/final
campaign; later changes are reports and ignored laboratory scripts only.
Installed `base.apk` SHA256 was independently checked on Tablet and S9 after
the final regressions and matches the frozen APK above on both endpoints.
Numeric logs, UI captures, policy dumps, candidate APK results and PowerShell
harnesses remain under ignored `app/build/reports`; no raw audio/speech saved.

### One capability and exact framework mapping

`DirectShellRemoteMicrophonePolicy` uses the existing shell app_process context,
real UID2000 and runtime `MODIFY_AUDIO_ROUTING` authorization. It reflects
`AudioAttributes.Builder.setCapturePreset`,
`AudioMixingRule.Builder.addRule(RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET)`,
`AudioMix.Builder.setRouteFlags(ROUTE_FLAG_LOOP_BACK)`,
`AudioPolicy.Builder.addMix`, `AudioManager.registerAudioPolicy`, and a mono
48kHz PCM16 streaming AudioTrack with `USAGE_VIRTUAL_SOURCE` and the registered
mix address. Capture criteria infer the recorder mix type; no UID criterion is
added. The actual built criteria are audited before registration: every rule
must be the capture-preset rule and the count must match the requested presets.

Presets are **MIC1 OR VOICE_COMMUNICATION7 OR CAMCORDER5**, one recorder mix.
This maps the
[AOSP record-injection test](https://android.googlesource.com/platform/frameworks/av/+/e1368e4257fa747e78eee204f136e67e176fbef9/services/audiopolicy/tests/audiopolicymanager_tests.cpp)
through the real Java framework boundary, rather than invoking a native test
as a product API. Rule behavior and direction follow
[AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/media/java/android/media/audiopolicy/AudioMixingRule.java)
and the native
[AudioPolicyMix](https://android.googlesource.com/platform/frameworks/av/+/android-13.0.0_r1/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp).
The AudioTrack is the injection side, not capture of Host playback.
**UID RULES = NONE**. Historical scoped probes still have their own UID rules
and are not invoked by this global backend.

Typed states include SUPPORTED, API_UNAVAILABLE, PERMISSION_DENIED,
CAPTURE_PRESET_RULE_UNAVAILABLE, POLICY_REGISTRATION_FAILED,
REMOTE_SUBMIX_UNAVAILABLE, START_FAILED and BAD_GENERATION. No model/OEM branch
was added. Reflection/permission/context decisions are centralized here and
in the existing shell-context adapter; only diagnostic address enumeration
guards `getAddress` at API28. Unsupported capability does not disable Host.

### Ownership and protected PCM path

Normal Enable Host installs the optional DEBUG override using the existing
authenticated DirectShell PCM feature server; fixed commands100-103 are
PREPARE/START/FEED/STOP. No extra generic privileged daemon or arbitrary shell
entry point. HMAC, loopback control, secret/generation and owner connections
are unchanged. FEED checks generation and the fixed mono frame/length contract.

Physical S9 AudioRecord -> MicrophoneChunkPacer -> native Opus -> Audio Payload
V1 -> existing authenticated/protected Session/SCL -> Tablet receiver/Opus
decode -> `DirectShellMicrophonePlayback` -> asynchronous bounded
`DirectShellRemoteMicrophoneSource` -> authenticated helper -> virtual-source
AudioTrack -> Android recorder mix -> independent AudioRecord.
S9 uses UNPROCESSED when its advertised property supports it, otherwise
VOICE_RECOGNITION; observed input type15. No generated PCM is injected at capture.

`DirectShellMicrophoneInputSession` now owns one asynchronous policy destination
and feature lease **per negotiated Session**, shared by every replacement decoder
wrapper. Decoder freshness resets only detach that wrapper. Pipeline stop,
close and configuration rollback close the Session owner once. Late PCM is
rejected by both decoded config generation and Session-owner closure. This fixes
the preserved A211 decoder-reset/bootstrap-storm regression without changing
ring/codec/socket buffers, provider liveness or media wire format.

The normal app does not register privileged policies. Release product mic
destination still fails closed; the qualified integration is explicitly DEBUG.
Complete Host remains Video/SystemAudio/Input; optional injection failure is
not a fourth mandatory Host capability or a silent speaker/null substitution.

### Exact hardware and final counted Sessions

Tablet X700_EEA, Android13/API33, `Z1382023HGM1895372`, fingerprint
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`.
S9 SM-G960F, Android10/API29, `23eaec0f12047ece`, fingerprint
`samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys`.
Host publication name is `Z138_EEA`. A41/S7 exact fingerprints are preserved
in the architecture report/matrix. S22 was not required or tested here.

One normal Enable Host at **2026-10-01T23:15:34.6529120Z**, readiness generation2,
frozen **SINGLE/DIRECT_SHELL**, all three required providers DIRECT_SHELL,
`shizuku_started=false`. No Host toggle/app restart/retry between the following
MIC cycles. Times below are laboratory UTC UI observations, not media latency.

| Counted MIC cycle | Streaming UTC | Client Ready + Host Waiting UTC | UI interval | Injection generation / routed input | Consumer interval |
| --- | --- | --- | --- | --- | --- |
|1|23:16:27.2693961|23:17:45.2288248|77.959s|8186997418791173643 / type25,id1136|60.034s,59 numeric windows|
|2|23:18:03.7160589|23:19:21.1600331|77.444s|7746196679905406146 / type25,id1206|60.021s,59 windows|
|3|23:19:39.1335438|23:20:56.6669698|77.533s|2868745471914763259 / type25,id1221|60.036s,59 windows|

**3/3 functional injection and normal lifecycle PASS, not3/3 all-zero quality**.
Each consumer starts ordinary AudioRecord(MIC),48k monoPCM16, with no preferred
device, UID configuration, Warpnect Binder/ring/PCM callback. It runs as the
separate normal test-APK UID10213; main app is UID10212 and injector UID2000.
Consumers require ordinary RECORD_AUDIO consent, not privileged capture access.
Each ends/release is observed, and a fresh post-Disconnect ordinary MIC record
returns to physical type15/id11. Later cycles prove rediscovery and fresh policy
addresses, not migration or automatic recovery.

The same continuously enabled Host then ran one additional Session:
Streaming23:22:42.8132123Z -> Ready/Waiting23:24:00.7889266Z,77.976s.
VOICE_COMMUNICATION7 and CAMCORDER5 each ran a separate ordinary30s consumer
and physical5s tone, sharing this Session's generation8732749961786770773,
type25/id1236. No preferred route or UID criterion for either.

| Ordinary source | Natural Android selection | Consumer quiet/ON RMS range |997Hz amplitude maximum|Post-Disconnect source|
| --- | --- | --- | --- | --- |
|MIC,cycle1|RemoteSubmix25/id1136|15.13 ->555.07 -> quiet|784.30|physical15/id11|
|MIC,cycle2|RemoteSubmix25/id1206|13.91 ->548.23 -> approximately14|774.77|physical15/id11|
|MIC,cycle3|RemoteSubmix25/id1221|13.36 ->554.63 -> quiet|783.87|physical15/id11|
|VOICE_COMMUNICATION|RemoteSubmix25/id1236|11.62 ->505.80 -> quiet|714.80|physical15/id11, separate4s control|
|CAMCORDER|RemoteSubmix25/id1236|11.09 ->495.04 -> quiet|699.70|physical15/id12, separate4s control|

### Physical OFF/ON/OFF proof and input coexistence

A bounded997Hz tone was emitted by the S9's **physical speaker**, captured by
its physical microphone, protected and decoded, not fed digitally into capture.
Representative cycle2 local5s windows: S9 RMS17.43 ->471.88 ->14.28,
997Hz amplitude0.14 ->576.05 ->0.12; decoded Tablet RMS approximately17 ->
472.45 ->14.14,997Hz amplitude577.30 during ON. Independent ordinary consumer
1s windows RMS13.91 ->548.23 ->13.99,997Hz0.17 ->774.77 ->0.13.
Different window widths/partial-tone windows explain amplitude differences;
no cross-device clock subtraction, E2E latency or sample-exact gain claim.
This is automated physical acoustic evidence, not new human audibility proof.
Human listening on the final APK is **NOT_CONFIRMED**.

Tablet GET_DEVICES_INPUTS during injection enumerates dynamic type25,
product `remote-submix`, address e.g. `1971008002:ap:48mixr:0`, alongside
built-in type15/id11 bottom and type15/id12 back (rates8/16/32/44.1/48k,
channels1/2,PCM16). The dynamic entry exposes empty rate/channel/encoding arrays
on this build: do not manufacture an advertised device profile from the mix
configuration. The pre-existing address0 submix remains separate. Dynamic
policy address disappears after stop; physical inputs remain before/during/after.
This proves normal preset routing, not arbitrary explicit-port selection or
all third-party capture sources/apps. HOTWORD/etc. were not added.

### Quality regression: do not transfer the old all-zero result

The80ms/70ms DEBUG ring/reserve, codec, pacer and socket buffers are unchanged.
This DEBUG wrapper forwards PCM to the input branch only after a successful
diagnostic sink submission. Therefore diagnostic ring rejection can also omit
that PCM from injection: release integration must not silently depend on a
speaker sink. No independent production input acceptance/clock is claimed yet.
The global path adds a16-entry authenticated IPC queue (normally240 frames each:
80ms nominal, plus the in-flight item; protocol permits entries up to960 frames).
AudioTrack/platform buffering is additional: observed client capacity4104 frames
(85.5ms), HAL1024 frames; platform latency reports are diagnostics, not physical
E2E timing. No hidden buffer optimization or SystemAudio reserve copy.

| MIC cycle | Last sampled DEBUG rejected / underrun / xrun | Session ring-full drop chunks | IPC queue rejected first sample -> release | IPC submitted / written at release |
| --- | --- | --- | --- | --- |
|1|7920 /10 /1|37|0 ->9840|3165120 /3165120|
|2|0 /0 /0|0|480 ->12960|3332400 /3332400|
|3|0 /0 /0|0|6720 ->15840|3316080 /3316080|

Decoder-local snapshots reset when decoder freshness replaces its sink; these
are **not** final cumulative decoded/playback totals. `playback_ring_full_drops`
is the separate Session-level chunk counter. In cycle1, startup resets included
an early4-underrun snapshot; after the new sink,7920 rejected/1xrun was already
present by local316390.910. Rejection stays constant thereafter, but10 underruns
(400 zero-filled frames) appear at316445.909, roughly65s after policy capability,
just after the independent consumer ends at316444.136. First trustworthy late
boundary: Oboe playback counter, not proven Wi-Fi/AudioPolicy causality.
Classification **INCONCLUSIVE**; nearest same-APK PASS controls are cycles2/3.
No invisible diagnostic reconnect and no change to the validated buffer contract.

IPC rejection stays fixed during the long active-consumer intervals in cycles2/3,
then grows after the consumer stops while microphone Session remains active.
Cycle1 IPC loss begins only after its consumer stop. This is real bounded queue
backpressure, not a renamed zero result. AOSP
[RemoteSubmix output](https://android.googlesource.com/platform/hardware/libhardware/+/android-13.0.0_r1/modules/audio_remote_submix/audio_hw.cpp)
can simulate realtime writes when no input is consuming; framework behavior
supports that hypothesis but does not prove the complete OEM cause. Initial
queue loss and the high occupancy/zero-write retries in cycle2 also remain
quality debt. Consumer routing/signal success does not establish lossless input.
VOICE/CAMCORDER Session last DEBUG snapshot3600/0/1,41 ring-full drop chunks,
IPC17520 rejected at release; these are retained, not quality PASS.

### Abnormal loss, restoration and regressions

One explicit intentional-loss Session, Streaming23:26:29.0342202Z: ordinary MIC
remains open while **only authenticated owned UID2000 PCM helper PID19818** is
terminated at23:26:37.5160538Z. Within the sampled3.5s boundary all three helpers
are gone and the policy dump has zero dynamic mixes. Logs at local316993.366
withdraw Host publication `DIRECTSHELL_OWNER_LOST migration=false`, release
generation6780951131581339972, and the same ordinary consumer changes from
type25/id1265 to physical15/id11 at316994.145. It continues physical capture
until its bounded40s end, then a fresh MIC control also routes physically.
No same-provider restart/provider fallback. Host app death was not requalified
under an active policy here; accepted owner-EOF evidence at715b215 is preserved.

After that completed loss test and a later user resume, an **explicit S9 app
restart and new normal Host enable** start a separate SystemAudio regression,
not a retry of the injection campaign. Final CF APK Tablet ->S9 Streaming
2026-10-02T04:39:31.2432119Z ->Ready/Waiting04:40:06.8823314Z,35.639s UI interval
(requested20s). Technical/lifecycle PASS, quality still open: last S9 snapshot
rejected29040/underrun146/xrun0, delivered3843/decoded3842. No mic80/70 values
were copied into SystemAudio; historical REMOTE_ONLY evidence remains accepted,
not newly inferred from this non-listening control.

Video transported/decoded protected AUs to Streaming. DirectShell Input receiver
initialized Running with injection/socket errors0; no deliberate human Input
event, so only initialization smoke. Owner liveness was not redesigned/re-soaked.
Normal final Disable Host at04:40:15.0133376Z leaves Tablet helpers0/secrets0/
dynamic mixes0 and UI Ready. Sink/capture stop follows normal pipeline teardown;
final AudioFlinger output threads are in standby with0 tracks, and ordinary
consumer/S9 RECORD_AUDIO AppOps show completed operations, not running access.
No global FD-leak claim. Temporary volume changes restored Tablet2/15,S9 15/15,
with no subsequent tone. Owned logcat collectors are stopped, evidence retained.

### Portability and priority capability matrix

| Host | Global capability using same backend | Priority investigation | Current physical scope |
| --- | --- | --- | --- |
|Tablet/API33|GLOBAL_CAPTURE_PRESET_INJECTION_SUPPORTED|Getter authorized for1/7/5, preferences[]; setter/clearer present but not invoked|Three MIC +VOICE/CAMCORDER physical Session consumers; normal and helper-loss restoration|
|A41/API31|GLOBAL_CAPTURE_PRESET_INJECTION_PERMISSION_BLOCKED:MODIFY_AUDIO_ROUTING|Read-only getter throws SecurityException|Final CF capability re-probe; no policy, input list unchanged; optional failure does not stop normal Host|
|S7/API26|GLOBAL_CAPTURE_PRESET_INJECTION_API_UNAVAILABLE at existing API_LT_31_CONTEXT_UNSUPPORTED|Not reached|Same probe/context adapter only, not all-API26 impossibility|

Final CF S9->A41 mic control: normal Enable04:41:40.4645759Z, Streaming
04:42:01.3332142Z ->Ready/Waiting04:42:31.5293584Z (requested20s,30.196s UI
interval). PERMISSION_DENIED/no policy/IPC0; last decoded/written956160 frames,
rejected/underrun/xrun0, occupancy2816/HWM3360. Complete Host stays
SINGLE/DIRECT_SHELL, no hidden Shizuku. Normal Disable04:42:39.3467136Z leaves
helpers0/secrets0/mixes0. This is a capability control, not an A41 workaround.

Natural Tablet MIC/VOICE/CAMCORDER routing already chooses the registered mix
without forcing a role. Android13 exposes setPreferredDeviceForCapturePreset,
getPreferredDevicesForCapturePreset and clearPreferredDevicesForCapturePreset
under MODIFY_AUDIO_ROUTING. The read-only probe proves **getter authorization
only**, not setter authorization. Capture preference state in
[AudioDeviceInventory](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceInventory.java)
is not the dynamic policy's binder-owned resource; normal finally cleanup would
not prove rollback after helper death. No preference was set or cleared, no
forced-priority token and no assertion of Tablet priority permission denial.
Crash-safe role ownership remains a separate unresolved implementation boundary.

Additional injection-specific user configuration after existing DirectShell
setup and ordinary Client mic consent: **NONE**. Laboratory ADB launches test
consumers/tone/UI only; normal application acquisition does not need a desktop.
No UID list, per-app setup, RemoteSubmix selection or model-specific product code.

### Tests, gates, anomalies and remaining work

New integration JVM cases relative to the972 cadence baseline:8 frame/contract
tests,4 asynchronous destination tests,5 playback/Session owner tests, plus1
additional997Hz numeric correlation/window-reset test =18, total **990**.
Tests cover mono240/480 accounting; stale/invalid generation; oversize/empty/
truncated PCM; invalid/duplicate presets; off-ingress acquisition; close-before-
acquisition late release; unavailable capability; idempotent shutdown; warm
AlreadyRunning; decoded-generation rejection; reprepare; decoder replacement
sharing one policy; no PCM after Session close. No mocked speaker/device claim.
Real framework rule/registration/routing/restoration proof comes from hardware.

Final requested gates PASS: git diff --check, :app:ktlintCheck, :app:lintDebug,
:app:testDebugUnitTest --rerun-tasks, :app:assembleDebug,
:app:assembleDebugAndroidTest, :app:packageDirectShellProbeDex.
Full Gradle build11m12s/100 executed tasks,160 suites/990 tests/0 failures/
0 errors/0 skipped. Native Debug22/22 (3.86s), Release22/22 (8.22s) also rerun;
no new native production edit in this injection follow-up. Inherited native
cadence changes remain uncommitted. Final documentation edits follow the frozen
APK and do not claim a new binary/gate run.

Preserved failures: A211 decoder-reset owner-loss/bootstrap storm is
PRODUCT_REGRESSION, fixed by Session ownership; no cycles2/3 counted after its
failure. DFDC high-volume clipped acoustic source is TEST_HARNESS_DEFECT,
corrected with one explicit moderate-volume comparison. The first Session-bound
ktlint function-signature failure and v2 lint API28 getAddress failure are
PRODUCT_REGRESSION in the new diagnostic code; format/API guards corrected and
v3 full gates pass. Old warm-start/ingress/Wi-Fi failures remain below. Final
MIC1 late DEBUG underrun is INCONCLUSIVE, not erased by MIC2/3. A41 permission
and S7 context failures are PLATFORM_CAPABILITY_LIMIT, not retries/OEM fixes.
Intentional helper termination is a passing safety test, not an unexplained
hardware anomaly. Intermediate B6 pre-API-guard three-cycle and preset results
are preserved separately, never substituted for final CF acceptance.

Changed documentation: this report; DirectShellMicrophoneInjectionQualification;
MicrophoneAudioVirtualInputArchitecture; MicrophoneAudioE2EQualification;
MicrophoneAudioCadenceRootCause; MicrophoneAudioCadenceAndVirtualInput;
CurrentNonRootDebt; Phase6.5 README;
FutureRoadmap; AndroidCompatibilityMatrix. Earlier reports remain historical.
No local checkpoint: release gating, input startup/consumer-absent pressure,
late DEBUG loss and forced-role lifecycle are not a clean fully closed slice;
large unrelated inherited work and generated evidence are not bundled.

Integration source/test boundary: `DirectShellRemoteMicrophonePolicy.java`,
`DirectShellRemoteMicrophoneSource.kt`, `AsyncRemoteMicrophoneDestination.kt`,
`DirectShellMicrophoneInputSession.kt`, `DirectShellMicrophonePlayback.kt`,
`DirectShellMicrophoneFrame.java`, fixed protocol/PCM-client/server changes,
`DirectShellRuntime.kt`, `DebugMicrophoneInjectionOverride.kt`,
`DefaultAndroidSessionPipelineBindings.kt`, `AndroidSessionPipelineFactory.kt`,
truthful UI/diagnostic strings; independent test Manifest/consumer,
`GlobalMicrophonePolicyProbe.java`, bounded numeric PCM997Hz probe and tone phase
fixture. Tests are `DirectShellMicrophoneFrameTest`,
`AsyncRemoteMicrophoneDestinationTest`, `DirectShellMicrophonePlaybackTest`,
and the added correlation scenario in `PcmDeliveryDebugProbeTest`. Earlier
scoped/first-class probes, native cadence work and unrelated artifacts remain
separately inherited, not relabelled part of this global implementation.

No Root, flashing, HAL/framework/system/vendor/SELinux changes, arbitrary policy
controls, media protocol/security changes, hidden retry/Shizuku fallback or new
RFC. HOST_AND_REMOTE, display policy, RFC-005J/K, physical DirectRoot and broad
Bluetooth/USB/headset/third-party compatibility remain deferred.
Exactly one recommended next task: **MicrophoneAudio global-injection startup
and consumer-absent backpressure quality closure**. SystemAudio causal playout
analysis remains next in the broader queue, not silently started here.

## Historical integration snapshot before final resumption

Everything below retains the earlier partial classification, candidate results
and pending prerequisites as history. The current result above supersedes those
status/next-task statements, not the failed evidence or old APK boundaries.

Date: 2026-10-01/02. This report supersedes current-status statements, not the
historical failed/scoped experiments in the linked reports.

## Repository and accepted baseline

Branch `codex/directshell-production-runtime-integration`; starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`. Master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, ahead19/behind0.
Previous checkpoints `92347d0`, `809b7dd`, `715b215` remain unchanged.
Initial staging0, unstaged196, untracked71:39 source/debug,12 tests,10 docs,
159 generated native paths,47 unrelated paths. Existing empty `.git/index.lock`,
screenshots/XML, attachments and native output were preserved. No automatic
checkpoint of the large partial worktree; no push/reset/clean/rebase/merge.

The [validated cadence baseline](MicrophoneAudioCadenceRootCause.md) remains
accepted: S9/API29 -> Tablet/API33 DEBUG Oboe,3/3 approximately70s Sessions,
rejected/underrun/xrun0,80ms ring/70ms reserve, nominal65ms additional priming.
This task does not change that ring, reserve, codec, pacer or socket buffers.
New integration regressions below do not retroactively replace the old PASS.

## Architecture and Android contract

Product intent is Client physical microphone -> protected Session -> Android
ordinary Host input. Speaker playback is a separate DEBUG diagnostic, not the
product destination. This integration is currently DEBUG-only and optional;
release still fails closed for an unimplemented product microphone destination.
Complete Host remains Video/SystemAudio/Input, with no new wire capability.

One backend, `DirectShellRemoteMicrophonePolicy`, reflects the existing Android
AudioPolicy APIs. It checks actual UID2000 shell context/permission, constructs
capture-preset AudioAttributes and `AudioMixingRule.addRule`, audits the built
criteria, builds a recorder mix with LOOP_BACK and48kHz mono PCM16, and registers
it with `AudioManager.registerAudioPolicy`. The injection AudioTrack uses
`USAGE_VIRTUAL_SOURCE` with the registered mix address. Its PCM therefore flows
into matching ordinary AudioRecord input requests, not the opposite playback
capture direction. Fixed capture criteria are MIC1, VOICE_COMMUNICATION7,
CAMCORDER5; matching criteria of the same type are alternatives.

**UID RULES = NONE** in this backend, its authenticated RPC and built criteria.
No caller-supplied UID/source/device-policy command exists. The old UID-scoped
probes remain diagnostic history only.

Authoritative references:

- [AOSP record-injection policy-manager test](https://android.googlesource.com/platform/frameworks/av/+/e1368e4257fa747e78eee204f136e67e176fbef9/services/audiopolicy/tests/audiopolicymanager_tests.cpp).
- [Android13 AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/media/java/android/media/audiopolicy/AudioMixingRule.java).
- [Android13 native mix input-source selection](https://android.googlesource.com/platform/frameworks/av/+/android-13.0.0_r1/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp).

These sources establish the framework mechanism, not universal OEM permission
or signal qualification. Runtime evidence takes precedence on each build.

## Ownership and control path

Normal Enable Host installs an optional DEBUG microphone destination over the
existing PCM feature helper; no fourth daemon, desktop runtime arm or generic
privileged surface. `DirectShellMicrophonePlayback` retains the original Oboe
controller and tees accepted decoded mono PCM for the current config generation.
`AsyncRemoteMicrophoneDestination` runs policy acquisition, activation and
release on a Session-local control worker, never in protected ingress.
`DirectShellRemoteMicrophoneSource` uses the existing loopback/HMAC envelope
with the per-helper generation and secret. The PCM worker handles partial
nonblocking AudioTrack writes. Its16-entry queue is bounded; nominal240-frame
entries represent80ms, while the protocol maximum960-frame entry allows a
larger theoretical bound. Queue fullness is counted, not hidden.

The helper accepts only fixed-purpose PREPARE/START/FEED/STOP. FEED checks exact
length, generation and maximum frame count. Policy/AudioTrack close on control
EOF, explicit stop, owner loss and helper teardown. Late acquisition after a
Session close releases its lease without activation; old config PCM is rejected.
Policy registration failure remains an optional-capability failure and does
not invalidate Complete Host. Authentication, trust, anti-replay, Session/SCL,
Audio Payload and715b215 owner-bound lifetime are unchanged.

## Ordinary consumer and prerequisite evidence

The separate `io.warpnect.test` APK consumer runs as UID10213, versus Warpnect
UID10212 and helper UID2000. Its pure-Java Activity uses ordinary AudioRecord,
MIC/VOICE_COMMUNICATION/CAMCORDER, RECORD_AUDIO, no preferred device, no special
Binder, no private ring or PCM callback. It reports only numeric frames/RMS/peak,
997Hz amplitude and routed device; no audio recording is persisted.

Tablet X700_EEA Android13/API33, serial `Z1382023HGM1895372`, fingerprint
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`.
Its published Warpnect Host name is `Z138_EEA`, not the hardware model.
The25s standalone prerequisite probe registers a three-criterion recorder
policy with UID rules NONE. Native dumpsys verifies MIX_TYPE_RECORDERS and
the three capture-preset criteria. A new dynamic input type25 appears and
disappears after cleanup. Ordinary MIC naturally changes from built-in
type15/id11 with ambient RMS26-59 to dynamic type25/id511 with RMS0 while
the probe writes zero PCM. No forced preference or UID rule was used.

**This is prerequisite/natural-routing proof, not protected remote acoustic
signal E2E acceptance.** The zero fixture cannot earn the global completion token.
Built-in devices remain enumerated before/during/after: bottom/back type15,
type18, default type25 and other platform inputs. Dynamic IDs are not hardcoded.

## Portability and privileges

| Host | Same backend result | First boundary |
| --- | --- | --- |
| Tablet/API33 | Prerequisite SUPPORTED; global E2E pending | UID2000 has MODIFY_AUDIO_ROUTING, CAPTURE_AUDIO_OUTPUT, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS; policy registration and natural MIC routing work |
| A41 SM-A415F/API31, `R58N52B32JJ` | GLOBAL_CAPTURE_PRESET_INJECTION_PERMISSION_BLOCKED | Current shell lacks MODIFY_AUDIO_ROUTING; other three relevant permissions granted; no policy registered, physical inputs unchanged |
| S7 SM-G935F/API26, `9886734e4937395657` | GLOBAL_CAPTURE_PRESET_INJECTION_API_UNAVAILABLE in current adapter | `API_LT_31_CONTEXT_UNSUPPORTED` shell-context construction; not proof that all API26 framework mechanisms are impossible |

A41 fingerprint `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys`.
S9 Client SM-G960F Android10/API29, `23eaec0f12047ece`, fingerprint
`samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys`.
No model/OEM branch or A41 workaround was added. Existing shell-context API
adapter remains the platform boundary; reflection availability is probed at
runtime. No Root fallback. S22 is not required or tested here.

## Preserved failures and candidate corrections

| Attempt / boundary | Classification | Observation / correction |
| --- | --- | --- |
| Separate consumer first launch | TEST_HARNESS_DEFECT | Kotlin standalone test Activity lacked kotlin/collections; replaced only the fixture with pure Java before AudioRecord proof |
| Normal Enable Host before pairing | ENVIRONMENTAL_PRECONDITION | TLS CERTIFICATE_VERIFY_FAILED / DIRECTSHELL_NOT_PAIRED; user completed normal Warpnect pairing, HMAC shutdown probe passed |
| Enable after pairing still used unavailable runtime snapshot | INCONCLUSIVE | AUTHORIZATION_REQUIRED persisted without new bootstrap; one explicitly reported app restart refreshed it. No microphone fix or automatic recovery claim |
| Harness setup screen/UI root and wrong expected Host name | TEST_HARNESS_DEFECT | No counted Connect; corrected own harness to published Z138_EEA and foreground normal UI |
| Initial integration APK `2D357FDA...` | PRODUCT_REGRESSION | Warm Oboe second start returns AlreadyRunning; wrapper incorrectly closed policy. One complete Session and a second explicitly aborted attempt retained; fixed idempotent warm-start ownership |
| Warm-start correction `E03AA090...`, one bounded diagnostic | PRODUCT_REGRESSION | Policy creation in first-config ingress stalled delivery, caused backlog/ring-full/reset; not global quality PASS |
| Pre-acquired-policy candidate `D34959AA...`, first campaign Session aborted | PRODUCT_REGRESSION | Synchronous optional policy lifecycle still stalls composition/ingress; startup ring-full10 drops, subsequent reset/prewarm churn. Current candidate isolates all policy operations on a control worker |
| Same candidate subsequently loses Tablet Wi-Fi | ENVIRONMENTAL_PRECONDITION | Wi-Fi NETWORK_DISCONNECTION_EVENT at23:36:36 local, reconnect; Android disables Wireless Debugging. Terminal receiver failure is not attributed solely to earlier ring pressure |
| Async candidate `82296176...`, first pre-Session attempt | ENVIRONMENTAL_PRECONDITION | Normal Enable reports WIRELESS_DEBUGGING_DISABLED; no Session/audio attempt. Requested manual re-enable, not privileged remediation |

Local helper envelopes are now emitted with one complete write, preserving
identical length/body/HMAC bytes, rather than three fragmented writes. This
removes a local request/response fragmentation risk; measured~40ms transactions
on the previous candidate alone do not prove a specific TCP/Nagle cause.
These are new integration corrections, not changes to validated UDP playout.
No failed result was retroactively made PASS; no hidden Connect retry.

## Final acceptance and priority

### Resumed campaign and Session ownership correction (2026-10-02)

After the user restored Wireless Debugging, APK `A2111576...` was installed
normally on Tablet/S9. Normal Enable Host published readiness generation2,
SINGLE/DIRECT_SHELL. The first counted resumed MIC attempt is **FAIL**, not
replaced by any later diagnostic: UTC22:20:11.4605151 start,
22:20:37.9965898 UI Streaming. The campaign was explicitly aborted; cycles2/3
were not attempted. The consumer stayed on physical input type15/id11.

First new integration defect: policy ownership was incorrectly tied to each
decoder/playback instance. A decoder freshness reset closed the policy and
created another async acquisition. Repeated resets exhausted the role pool
and launched concurrent bootstrap operations. Publication correctly withdrew
on DIRECTSHELL_OWNER_LOST rather than claiming healthy Host availability.
Classification: **PRODUCT_REGRESSION**. Closest accepted non-injection control
is the A41 permission-denied Session below; it does not prove Tablet injection.

`DirectShellMicrophoneInputSession` now owns one optional destination per
negotiated receiver Session. Decoder wrappers share it but retain config-generation
and stopped-instance PCM rejection. AudioReceiverComponent stop/close and
composition rollback close that Session owner. Decoder resets no longer create
policies or bootstrap helpers. The validated native playout is unchanged.

The DFDC7F0B candidate completed two explicitly separate diagnostic Sessions,
not the final three-cycle campaign. The first high-volume acoustic fixture
clipped already at S9 capture (peak32768), so it is not clean signal-quality
acceptance. The source volume was then lowered from15/15 to3/15 and Tablet
DEBUG playback temporarily muted (original2/15) to avoid acoustic feedback.
The second controlled fixture showed ordinary MIC selecting type25/id1006,
ambient RMS23-27 -> remote tone RMS638-654 -> RMS23-27, and physical type15/id11
again after Disconnect. No UID rule or preferred-device call was used.

Numeric IPC telemetry exposes queue-full separately from DEBUG ring-full.
With the ordinary consumer active, sampled source written count advanced240000
frames per5s, queue0-2, and rejection remained7680 (startup), without recurring
increment. After consumer stop it increased12480 ->21120 ->29520 while writes
fell below nominal cadence. The AOSP remote-submix HAL's no-input/shutdown path
discards output and simulates realtime with sleep; this is not proof of audio
loss to an active recording app. It also does not justify suppressing the
observed queue counters or claiming the injection's quality universally closed.
The controlled DEBUG Oboe snapshots had bounded startup/reset loss5760, then
steady rejection delta0, underrun0/xrun0. Initial integration startup pressure
remains visible, distinct from the earlier accepted baseline.

The final candidate adds only Session ownership, bounded IPC timing/progress,
mono997Hz numeric correlation and a continuous-phase DEBUG acoustic fixture.
No ring, pacer, codec or socket-buffer changes. Final physical acceptance is
pending the frozen rebuilt APK. Evidence prefixes `global-mic-resumed`,
`global-mic-session-owner`, `global-mic-low-tone-control` retain all failed and
diagnostic results under ignored app/build/reports.

The initial Session-bound gate attempt failed one ktlint function-signature
format rule; corrected without behavior changes. Its failed log remains.

Final remote-signal MIC3-cycle acceptance remains pending. Prerequisite proof
does not establish remote physical tone OFF/ON/OFF in the ordinary consumer.
VOICE_COMMUNICATION/CAMCORDER remote-signal qualification is likewise pending.
The latest candidate keeps native DEBUG playout unchanged and moves only the
optional control work off ingress. Supported-path final counters remain
unmeasured; software gates and the permission-denied control are recorded below,
not invented from mocks or earlier APKs.

Forced priority is **NOT TESTED**, and no preference setter/clearer was called.
Natural MIC routing works in the prerequisite probe. Android13 offers
`setPreferredDeviceForCapturePreset`, `getPreferredDevicesForCapturePreset`
and `clearPreferredDevicesForCapturePreset` under MODIFY_AUDIO_ROUTING, but
registration authorization alone does not prove those calls are usable.
[AudioDeviceInventory](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceInventory.java)
stores capture preferences in AudioService state, unlike a binder-owned dynamic
policy. Normal finally cleanup alone would not prove helper-death restoration.
No persistent preference is introduced before global E2E acceptance and safe
abnormal cleanup can be validated. No permission-denial claim is inferred for
priority on Tablet; API contract research is separate from a runtime probe.

## Safety, remaining boundary and next task

No Root, OS/framework/HAL/vendor modification, SELinux changes, generic shell,
UID targeting, extra daemon, media protocol change, hidden Shizuku fallback,
codec/ring retuning or new RFC. No raw speech/PCM files committed or saved.
Additional injection-specific per-app/UID/PC setup is absent; existing DirectShell
trust/Wireless Debugging and explicit DEBUG microphone Session intent remain
prerequisites. Product release integration is not claimed completed.

Current classification: **DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_PARTIAL**.
Priority: **DIRECTSHELL_REMOTE_MICROPHONE_PRIORITY_CONTROL_NOT_TESTED**.
No validated injection checkpoint has been created. SystemAudio pressure remains
separately open; owner liveness and cadence completion tokens remain accepted.
Full background/screen-off/timeout work remains RFC-005K scope, not implemented.

## Bounded current-candidate regressions

The async integration APK SHA256
`9923524987D451FA3F54F9F3460BED4CAB6C5928C693CE79ACA287D680EF513E`
was installed on A41 and S9. Both following Sessions use **one normal Enable
Host**, readiness generation2, frozen SINGLE/DIRECT_SHELL for Video/SystemAudio/
Input. No Shizuku fallback or Host toggle between them.

| Counted control | Streaming UTC -> Client Ready / Host Waiting UTC | Result |
| --- | --- | --- |
| A41 SystemAudio -> S9, requested20s |21:53:32.3867191 ->21:54:02.8550114|Technical/lifecycle PASS; not audio-quality PASS|
| S9 microphone -> A41, requested60s |21:55:23.2804727 ->21:56:33.4730996|70.193s UI interval; technical/DEBUG quality and lifecycle PASS; injection correctly unavailable|

Microphone: `REMOTE_MIC_CAPABILITY result=PERMISSION_DENIED uid_rules=NONE`,
no registered policy and submitted/written/rejected IPC0. Normal Host remains
Available, decode and DEBUG playback continue. Last sampled Client: captured/
encoded PCM2877360, encoded11989, datagrams11990, WouldBlock/send failure0.
Last sampled A41: datagrams11967, delivered11966, frames received11965,
decoded units11962, decoder PCM2871840, playback accepted2871840,
consumed2868992, rejected0, underrun0, xrun0, occupancy2848/HWM3632 of3840
frames. These are asynchronous snapshots, not final packet-loss accounting.
Zero rejection/underrun persists in sampled startup and steady windows.
No ordinary Android injection/remote-signal success is inferred on A41.
Human audibility on this new APK is NOT_CONFIRMED.

SystemAudio: last Host captured/encoded953280 frames, sent3973 datagrams,
capture overruns/drop0, send failure0. Last S9 receiver delivered3794/decoded
3793 units, decoder PCM898560, accepted844800, rejected53760, underrun472,
xrun0. This preserves the existing separate pressure debt, not a measured
regression claim or quality closure. No80/70ms change was copied to SystemAudio.
REMOTE_ONLY human-routing evidence from the earlier accepted campaign is unchanged;
this control did not repeat human listening or local-output routing qualification.

Video produced protected AUs and Streaming; DirectShell Input controller was
Running with injection error0, but no human Input event was supplied. These are
bounded initialization/media smokes, not a new full Video/Input qualification.
Owner liveness was not redesigned or re-soaked. Normal Disable Host after the
two controls leaves A41 helpers0/secrets0. Tablet at its unavailable prerequisite
also has helpers0/secrets0. Both policy dumps have no registered dynamic mix.
AppOps shows completed, not currently running, RECORD_AUDIO operations on S9
and the independent Tablet consumer. No orphan ordinary capture remains.

Fresh Tablet MIC3-cycle final acceptance, remote VOICE_COMMUNICATION/CAMCORDER,
remote OFF/ON/OFF consumer frequency correlation, supported-path abnormal loss,
fresh-generation routing and forced-priority restoration are **NOT VALIDATED**.
The standalone zero probe's normal cleanup is the only qualified global-policy
restoration boundary so far. This is why the global token is not earned.

## Tests, gates and exact change scope

New JVM tests relative to the accepted972-test baseline:

- `DirectShellMicrophoneFrameTest`:8 cases, mono240-frame wire roundtrip,
  stale generation, oversized frame, empty frame, truncated frame, disallowed
  preset, duplicate preset and invalid owner.
- `DirectShellMicrophonePlaybackTest`:4 cases, AlreadyRunning warm start,
  stale config PCM, close with no decoded configuration, and reprepare/late stop.
- `AsyncRemoteMicrophoneDestinationTest`:4 cases, no policy work on ingress,
  close-before-acquire late-owner release, off-ingress idempotent close/late PCM,
  and unavailable acquisition without invented activation.

The first complete async software run passed all requested Gradle gates:
ktlintCheck, lintDebug, testDebugUnitTest --rerun-tasks, assembleDebug,
assembleDebugAndroidTest and packageDirectShellProbeDex; BUILD SUCCESSFUL10m45s.
JVM988, failures/errors/skips0. Native CTest was also rerun: Debug22/22 PASS
3.65s, Release22/22 PASS5.07s. No native production source was edited in this
task; inherited uncommitted native changes and tests are preserved.
git diff --check passes (only Git LF/CRLF advisory warnings).

A subsequent truthful DEBUG label/telemetry-only change replaced the speaker-only
checkbox and `DEBUG_PLAYBACK_ONLY` event label. Actual input capability remains
reported independently by REMOTE_MIC_CAPABILITY, never inferred from that label.
Its first gate attempt failed ktlint max-line-length120; the line was wrapped,
and the final full gate rerun passed: **BUILD SUCCESSFUL11m42s,100 tasks executed**.
All six requested Gradle tasks passed; JVM988/failures0/errors0/skips0.
Final git diff --check exit0. The final wording-only APK SHA256 is
`A2111576E8BA63135B29EF007D6268501DADBBDD6EA8FA0092230BD8DE4E1A5F`.
Its hardware campaign is NOT RUN; supported Tablet E2E remains blocked by
Wireless Debugging0 at the final check, after the reported Wi-Fi interruption.
Normal user pairing was completed earlier and is not reported still pending.
No hardware
behavior was changed by this wording patch. The A41 control APK above precedes
only that wording difference; supported Tablet candidate behavior remains unvalidated.

New/changed integration source: `DirectShellRemoteMicrophonePolicy.java`,
`DirectShellRemoteMicrophoneSource.kt`, `AsyncRemoteMicrophoneDestination.kt`,
`DirectShellMicrophonePlayback.kt`, `DirectShellMicrophoneFrame.java`,
`DirectShellProbeProtocol.java`, `DirectShellPcmRingProbeClient.java`,
`WarpnectPrivilegedPcmSharedRingProbeServer.java`, `DirectShellRuntime.kt`,
`DebugMicrophoneInjectionOverride.kt`, `DefaultAndroidSessionPipelineBindings.kt`,
and the truthful UI/diagnostic strings. DEBUG test tooling:
`GlobalMicrophonePolicyProbe.java`, independent AndroidTest Manifest and pure
Java `StandardMicrophoneConsumerActivity.java`. Temporary PowerShell campaign
scripts and numeric logs/dumps live only under ignored app/build/reports.

Current worktree classification at reconciliation after these edits:
staging0, unstaged200, untracked84;50 source/debug,17 tests,11 docs,
159 generated native paths and47 pre-existing unrelated paths. Directory-collapsed
Git status counts differ from expanded file counts. Ignored build/hardware
evidence is not staged. No source or evidence was discarded; HEAD unchanged.

Docs updated by this follow-up: this report, DirectShellMicrophoneInjectionQualification,
MicrophoneAudioVirtualInputArchitecture, MicrophoneAudioE2EQualification,
MicrophoneAudioCadenceRootCause, CurrentNonRootDebt, Phase6.5 README,
FutureRoadmap and AndroidCompatibilityMatrix. Earlier reports remain historical.

Exactly one recommended next task: **complete the current Tablet global MIC
physical acceptance campaign after normal Wireless Debugging is restored**.
Do not start SystemAudio quality work or forced priority before that boundary.

Remaining risks: supported-path startup/steady IPC queue pressure, total extra
AudioTrack/queue latency, async teardown ordering versus Host rediscovery,
supported-path helper/client death cleanup, fresh-session input restoration,
VOICE_COMMUNICATION/CAMCORDER signal semantics and priority authorization/
crash-safe restoration are unvalidated. No universal Android or third-party
app compatibility claim. No new global/priority completion token is earned.
All collectors started by this task are stopped; only their preserved numeric
logs remain. Owned temporary global-probe jars were removed from the three
devices; older logs/artifacts and the installed test APK were not discarded.
