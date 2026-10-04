# Host / Client SystemAudio Routing Semantics (2026-09-28)

## 2026-10-04 Technical Quality Control

[SystemAudioQualityRootCause.md](SystemAudioQualityRootCause.md) records frozen
FC7D151F A41/API31 ->S9/API29,3/3 consecutive70s tones under one Enable,
rejection/underrun/xrun0 and real997Hz decoded/consumed PCM. Host AudioTrack
reports REMOTE_SUBMIX(type25) during the final Sessions; a bounded post-
Disconnect tone reports BUILTIN_SPEAKER(type2), track underrun0. Ready/Waiting,
rediscovery and Disable Host cleanup pass. Volume5/15, tone quarter-scale.
This is new technical routing evidence, not human listening: the operator is
away. The previously human-validated REMOTE_ONLY result below is preserved;
HOST_AND_REMOTE and external output routes are not implemented/qualified here.

## Scope and result

This is a physical observation of the *normal* DirectShell Host Session, not
a new audio backend or a universal Android routing guarantee. Tablet X700_EEA
(Android 13 / API33), Samsung A41 SM-A415F (Android 12 / API31), and Samsung
S22 SM-S901B (Android 16 / API36) sent a Warpnect-owned, bounded 997 Hz tone
to S9 SM-G960F (Android 10 / API29). The operator listened to the named
devices; `dumpsys audio` supplied independent route diagnostics. S22 became
available later on the same date and completed the previously missing row.

Observed build fingerprints: Tablet
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`,
A41 `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys`,
S22 `samsung/r0sxeea/r0s:16/BP2A.250605.031.A3/S901BXXSOGZH3:user/release-keys`,
S9 `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys`.

| Host | Normal Session tone | After Client Disconnect | Observed semantic |
| --- | --- | --- | --- |
| Tablet/API33 | 3/3 operator reports: S9 audible, Tablet not audible; `STREAM_MUSIC` route `remote_submix` | 3/3 operator reports: Tablet audible, S9 not audible; route `speaker` | `REMOTE_ONLY` during these Sessions; local output restored |
| A41/API31 | 2/2 returned operator reports: S9 audible, A41 not audible; third tone produced real captured PCM but has no operator answer; route `remote_submix` in all three | 2/2 normal Disconnect reports: A41 audible, S9 not audible; route `speaker`; third Client force-stop returned route to `speaker` within the next 8-second observation | `REMOTE_ONLY` for the human-confirmed normal Sessions; normal restoration confirmed |
| S22/API36 | 3/3 operator reports: S9 audible, S22 not audible; `STREAM_MUSIC` route `remote_submix`; real PCM and Client consumption in all cycles | 3/3 operator reports: S22 audible, S9 not audible; route `speaker` | `REMOTE_ONLY` during these Sessions; local output restored |

The A41 baseline tone before a Session was audible on A41. The operator first
named S7 but immediately corrected that observation to **A41**; S7 must not be
credited with this tone. The Tablet's first baseline tone had no operator
answer, so its local-only baseline is established by the three post-Session
speaker observations. Tablet speaker volume was initially 0/15, was raised for
the bounded test at the operator's request, and was restored to 0/15 at the
end. S22 speaker volume was initially 3/15, and its first baseline tone was
not audible. At 7/15 the operator heard the local baseline tone; 3/15 was
restored at task end. The selected routes were built-in speaker and
`remote_submix`, not a verified wired/Bluetooth/USB output. Volume was
nonzero during listening.

## Actual capture and routing path

`AudioSenderComponent` starts physical SystemAudio when the committed Session
owns it. The DirectShell selected source uses a shell-UID `AudioRecord.Builder`
with `MediaRecorder.AudioSource.REMOTE_SUBMIX`, shell context/op-package, PCM16,
48 kHz and stereo. It does **not** register an explicit `AudioPolicy` or
`AudioMix`. The privileged producer writes existing PCM Shared Ring V1; the
normal app drains through `SharedPcmAudioDrain`, encodes with existing Opus,
sends existing Audio Payload over the protected Session, and S9 uses the
existing decoder/playback sink. No capture-only Host lifecycle was added.

The separate optional Shizuku implementation constructs an `AudioMix` using
`ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER`; it was **not** selected in these
counted Sessions. AOSP documents loopback+render as a plausible simultaneous
render/capture route on supported framework versions, but that is neither the
current DirectShell configuration nor qualified Host+Client behavior here.
Older framework versions may reject the combined flags. It would require a
separate provider-specific feasibility and routing qualification, not a
silent change to the working REMOTE_SUBMIX capture or wire protocol.

References: [AOSP AudioMix route flags](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/audiopolicy/AudioMix.java),
[AOSP AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/7e8d0478d11175048f05b2323a74db1e86dab328/services/audiopolicy/managerdefault/AudioPolicyManager.cpp),
[Android 28 AudioMix](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-28/+/4d01eeaffaa720e4458a118baa137a11614f00f7/android/media/audiopolicy/AudioMix.java).
These are framework contracts, not proof of a particular vendor AudioPolicy.

## Counted campaign

| Host/cycle | Admission / provider | During capture | Normal or abnormal stop | Restoration |
| --- | --- | --- | --- | --- |
| Tablet 1 | Normal Enable Host, Session/Client `Streaming`, DirectShell audio | S9 only by operator; real nonzero ring PCM and Client playback progress | Normal S9 Disconnect | Tablet only by operator, `speaker` route; later Host rediscovery failed until normal Disable/Enable |
| Tablet 2 | Normal Enable Host, Session/Client `Streaming`, DirectShell audio | S9 only by operator | Normal S9 Disconnect | Tablet only by operator, `speaker` route |
| Tablet 3 | Normal Enable Host, Session/Client `Streaming`, DirectShell audio | S9 only by operator | Normal S9 Disconnect | Tablet only by operator, `speaker` route |
| A41 1 | Frozen `SINGLE`, Video/Audio/Input=`DIRECT_SHELL`, `shizuku_started=false`; Client `Streaming` | S9 only by operator | Normal S9 Disconnect | A41 only by operator, `speaker` route |
| A41 2 | Same frozen plan and Client `Streaming` | S9 only by operator | Normal S9 Disconnect | A41 only by operator, `speaker` route |
| A41 3 | Same frozen plan and Client `Streaming`; PCM ring measured 484,934 nonzero samples and RMS 946.547 in its bounded interval | `remote_submix`; third listening answer not yet recorded | Bounded `am force-stop io.warpnect` on **S9 Client only** after the tone | A41 `speaker` route within the next 8-second check; UI entered `Recovering`, then normal Disable Host returned `Ready` |
| S22 1 | Normal Enable Host and Client `Streaming`; DirectShell source generation `5365174778543002253`, `shizuku_audio_producer=false`; ring sampled 579,342 nonzero samples, 0 overruns | S9 only by operator; `remote_submix` | Normal S9 Disconnect | S22 only by operator; `speaker` route |
| S22 2 | Same normal path, generation `6093131172242220047`; ring sampled 485,172 nonzero samples, 0 overruns | S9 only by operator; `remote_submix` | Normal S9 Disconnect | S22 only by operator; `speaker` route |
| S22 3 | Same normal path, generation `6509374115624186132`; ring sampled 382,752 nonzero samples, 0 overruns | S9 only by operator; `remote_submix` | Normal S9 Disconnect | S22 only by operator; `speaker` route |

The A41 `privileged_plan_selected` logs show `SINGLE` with all three
capabilities on `DIRECT_SHELL` and `shizuku_started=false` for its three
successful admissions. The Tablet source log recorded
`source=REMOTE_SUBMIX`, `ring=SharedPcmAudioRingV1`, and
`shizuku_audio_producer=false`; its older frozen-plan lines had rotated from
logcat, so this campaign does not claim a separately retained per-cycle plan
line for Tablet. Both Hosts were normally disabled at task end; S9 was
reopened in `Ready`. Post-disable checks found no matching DirectShell helper
process or `/data/local/tmp/warpnect-directshell-secret-*.bin` file on either
Host. The three S22 source logs independently record
`shizuku_audio_producer=false`; after its final normal Disable Host, S22 and
S9 were `Ready`, S22 speaker volume was back to 3/15, and no matching
DirectShell helper or temporary secret file was found. No full
per-descriptor FD ledger was taken.

A41 cycle 3 also recorded 15 PCM ring overruns in the sampled interval and
Client playback-ring pressure. That is media-pressure telemetry, not evidence
for local duplication. The historical
[DirectShell remote-audio experiment](DirectShellSystemAudioRemoteE2E.md)
reported A41 local audio during a standalone capture. It remains a valid
historical observation, but its setup is not equivalent to these three
normal Host Sessions; current counted results must not be rewritten to match
it or used to assert a universal Android rule.

Before the A41 campaign, one Host admission failed at
`FEATURE_INPUTSESSION_DISCOVER_CONNECTION` /
`FEATURE_VIDEOCAPTURE_DISCOVER_CONNECTION` with
`FAIL_WIRELESS_DEBUGGING_CONNECTION_SERVICE_NOT_DISCOVERED`, before audio.
This is an **ENVIRONMENTAL_PRECONDITION**, not an audio failure. A manual
Wireless Debugging off/on by the operator restored `_adb-tls-connect`; the
following three admissions froze DirectShell-only plans. Tablet cycle 1
separately exposed a post-Disconnect `Running`/Host Available state with no
rediscovery until normal Host Disable/Enable. Its first causative logs rolled
before preservation, so it remains **INCONCLUSIVE** and separate from the
verified audio route restoration. No hidden retry converted either failed
attempt to PASS. S22 also showed `Running` after each normal Disconnect;
the next routing cycle began with a normal Host Disable/Enable, without
claiming that the Session/discovery state was healthy.

## Product contract and remaining boundary

Current debt classification: **CLOSED_REMOTE_ONLY** for the normal
DirectShell built-in-speaker path on the three tested modern Hosts. This
does not validate other output routes or Host+Client duplication.

Keep capture, Client playback and Host-local playback as separate claims.
For the tested normal DirectShell Sessions on the built-in speakers of all
three modern reference Hosts, the validated mode is `REMOTE_ONLY`; an untested
host or output route has `UNKNOWN` local-output semantics, not a
promised `HOST_AND_REMOTE` or `REMOTE_ONLY` guarantee. `LOCAL_ONLY` describes
the stopped/baseline state, not SystemAudio capture. The code currently makes
no explicit Host+Client duplication promise and no user routing selection.
Do not label client PCM counters as proof of speaker audibility. A local Host
presentation/routing policy or qualification, if later needed, does not
require a new SCL/Audio Payload/WNCP capability by default; the Client needs
the SystemAudio stream, not a promise about the Host speaker.

No production routing telemetry or behavior was changed in this task.
`dumpsys audio` / `dumpsys media.audio_policy` are ADB-only diagnostics, not
production sensing. The current capture path supplies no authoritative
cross-device local-speaker observability. Future `HOST_AND_REMOTE` support
requires an explicit supported route, physical duplication proof and teardown
validation per compatibility key. Bluetooth, routing after a failed audio
start, and arbitrary third-party playback remain unqualified.
