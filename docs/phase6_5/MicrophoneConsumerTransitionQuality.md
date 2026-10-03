# Global microphone consumer-transition continuation - 2026-10-02

## Result and repository boundary

**PARTIAL.** The current candidate improves active-consumer IPC delivery, but
neither primary campaign completed three qualifying Sessions. Repeated Tablet
radio/network loss interrupts qualification. No quality, portability or forced
priority completion token is earned. Preserve
`DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED` and the separately validated
`MICROPHONE_AUDIO_CADENCE_AND_PLAYBACK_QUALITY_FIXED_AND_VALIDATED` for their
original APKs, routes and measurement scopes. Release behavior is unchanged.

Branch: `codex/directshell-production-runtime-integration`. Starting/ending HEAD:
`715b2151af2c8ecac165729178266b24e5cae91b`; master:
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream:
`9d0b4dce7ea04a82e81778de6296cc1096775d31`.
Historical checkpoints92347d0/809b7dd/715b215 remain. At resumption staging0,
tracked unstaged202 and directory-collapsed untracked91; before documentation
updates staging0/unstaged255/untracked92. Native rebuilds account for much of
the additional generated churn. No commit/reset/clean/stash/merge/rebase/push.
The pre-existing empty `.git/index.lock` is preserved, not treated as source.
Final documentation-inclusive status: staged0/unstaged255/untracked93
(directory-collapsed). These counts include inherited/generated files; they are
not a claim that this continuation authored the whole worktree.

## Network prerequisite and new external boundary

Tablet returned over USB with Wireless Debugging ON. S9/Tablet IPv4 ping passed
both directions; the bounded independent Java TCP/UDP token/echo probe passed
all eight primary/control tests. Correct scoped IPv6 Tablet-to-S9 ping passed
3/3, completing the earlier opposite-direction control. Same wlan0 IPv4 /24
addresses, SSID and initial BSSID as [the network report](S9TabletPeerLinkRecovery.md).
No media workaround, router change, VPN/security change or new Wi-Fi recovery.

The first40ms campaign's cycle2 lost its media path shortly after Streaming:
S9 logs `lifecycle_suspended`, `MIC_CAPTURE_STOPPED` and
`session_start_failed reason=InvalidPresence`. Tablet later logs wlan0 down,
locally generated disassociation reason5, network106 disconnected and
`Network disconnected. Disabling adbwifi.` Both peer probes initially fail;
Tablet returns `Network is unreachable`. Reassociation subsequently occurs
without an agent Wi-Fi write; peer ping/TCP/UDP then pass. The operator manually
restored Wireless Debugging. This failed Session is not retroactively PASS.

A new campaign on the full-gate APK reaches two complete Sessions, then fails
again in cycle3. At Tablet monotonic390339.064 wlan0 goes down;
390339.067 Android reports `com.android.bluetooth` PID14257 died;
390339.070 Zygote reports signal9;390339.090 supplicant reports locally generated
reason5;390339.235 ADB disables wireless debugging. Android exit-info says
SIGNALED/status9, not LOW_MEMORY; Bluetooth manager records CRASH/RESTARTED.
A similar SIGKILL/radio interruption exists in the previous failed campaign.
Later connection to the sibling BSSID ending64 is observed, not assumed causal.

**First established boundary:** media/lifecycle path loss, followed by an
independently observable platform radio/link interruption. The origin of SIGKILL
and the precise causal ordering below framework observations remain INCONCLUSIVE.
Do not call AP isolation, DFS, low memory, GC or Warpnect the proven cause.
Kernel logging is permission-blocked (`klogctl: Permission denied`); no privilege
escalation was attempted. Classification: external platform/environment
precondition blocks the primary campaign; the lower radio mechanism is unproven.
No repeated blind reconnect, substitute qualification pair or router mutation.

## Candidate and discriminating experiments

The exact87EF556C consumer-window candidate was tested before new edits. It
retained80ms IPC freshness,16-packet FIFO, authenticated generation/header,
maintenance silence and no-UID Session-owned recorder policy. Ordinary MIC signal
and physical restoration pass, but quality fails. Each subsequent row is a new
explicit diagnostic build/attempt, not a hidden retry or a counted PASS.

| Evidence prefix / APK prefix | Submitted / written PCM frames | Unexpected queue rejection | No-consumer discard | Stale / pre-window discard | Result |
| --- | --- | ---: | ---: | --- | --- |
| global-window-return /87EF556C | 5229360 /4274024 | 13200 | 937312 | 17064 /960 | Original guard candidate FAIL,108.424s |
| global-window-timing /FB43828D | 5246160 /4302480 | 12480 | 926640 | 16320 /720 | Timing-only diagnostic FAIL,108.324s |
| global-window-nodelay /E844DE84 | 5258400 /4305840 | 9840 | 933360 | 15360 /3840 | TCP_NODELAY did not close quality; fully reverted,108.197s |
| global-window-priority /A71BF7B5 | 5244000 /4288080 | 18240 | 928080 | 27840 /0 | Worker priority alone FAIL,108.469s |
| global-window-unlocked /22ECEBFC | 5355840 /4319944 | 5040 | 1026080 | 8616 /1200 | Outside-lock close transition alone FAIL,108.349s |
| global-window-age40 /5E42F550 | 5280480 /4312216 | 720 | 933680 | 31704 /2880 | First active windows improve; second Session terminates before qualification |

Evidence explains multiple narrow defects, not a single magic timeout:

- `pause/flush/play` on consumer disappearance held the feed monitor. Paired
  request-ID timing proves a35.443ms close transition and corresponding blocked
  feed in the priority experiment. Move that framework work outside the monitor;
  a transition flag makes feed discard unheard real PCM without concurrent
  maintenance writes. Android route/patch lookup was already outside the monitor.
- App worker scheduling is material: default-nice sample has11.165s cumulative
  runnable wait; owned audio-priority sample has0.705s, with maximum wait6.801ms.
  These are separate diagnostic snapshots, not synchronized E2E latency. Priority
  reduces scheduling delay but did not independently solve rejection.
- A FIFO can retain a60-70ms offset after a startup stall when average production
  and consumption rates are equal. Expiration only at dequeue can reject fresh
  PCM at admission while expired history remains queued. The new bounded queue
  expires/wipes only stale heads before admission and counts those frames.
  Fresh valid heads are never evicted merely to hide overflow.
- An80ms budget permits this offset to persist. The combined admission/freshness
  candidate uses40ms locally, without increasing capacity. Sequential experiments
  support improved bounded occupancy, not a clean factorial attribution to each
  individual change. Exact causes of occasional long RPC stalls remain unproven.

Timing probes distinguish client call, helper feed and AudioTrack write. The
timing-only experiment has max call59.030ms versus max write7.029ms; the unlocked
experiment reaches96.175ms call versus3.145ms write. Do not equate RPC latency
with an AudioTrack write stall. Slow samples are bounded to16 per owner. TCP
NODELAY, opening flush and reduced start-threshold experiments are not retained.
The latter two earlier failures remain in the [historical quality report](DirectShellGlobalMicrophoneInjectionQuality.md).

## Final candidate PCM lifecycle and latency

`DirectShellRemoteMicrophoneSource` submits decoded48kHz mono PCM16 (240frames,
480bytes per nominal5ms unit) to `RemoteMicrophonePcmQueue(16)` with original
Host monotonic enqueue time. The authenticated purpose-specific RPC carries
generation/frames/time. Partial retries preserve time. App and helper both
reject age above40ms; negative/future ages are ineligible. The helper rejects
PCM enqueued before its observed consumer-open window. No UID criterion exists.

No consumer: real PCM is discarded at helper before AudioTrack; only nonblocking
zeros maintain route selectability. Disappearance clears the window and performs
pause/flush/play outside feed lock. Reopen establishes a new temporal window.
Session owns one policy; decoder recreation does not register a competing policy.
Policy uses recorder capture-preset rules MIC1/CAMCORDER5/VOICE_COMMUNICATION7,
LOOP_BACK and USAGE_VIRTUAL_SOURCE. Ordinary consumer uses AudioRecord(MIC),
no UID agreement, privileged API, preferred device, shared ring or callback PCM.

Capacity remains16 packets (normally80ms of nominal units), not a new larger
queue. Expired admission counts are part of total stale counts, not additional
loss to add twice. Accepted write age is locally bounded by40ms freshness;
this is a discard ceiling, NOT a deliberately added40ms delay. Queued data may
remain during process suspension but cannot later pass the age check. FIFO,
current RPC and payload allocations are bounded. No seconds-old voice reservoir.

The public AudioTrack minimum remains4104frames (85.5ms); platform RemoteSubmix
pipe/consumer buffering is outside the app enqueue-age bound. Do not promise
40ms whole-platform latency or original acoustic capture age. No cross-device
clock subtraction. Existing DEBUG Oboe ring3840frames/80ms and reserve3360frames/
70ms, pacer, codec, bitrate, packet duration and socket buffers are unchanged.
Adaptive IPC age is **NOT IMPLEMENTED / PARTIAL decision**: no demonstrated
need for complexity; retain the simpler fixed40ms candidate provisionally, not
as a release-qualified replacement for the previous80ms candidate.

## Frozen primary campaign

Full SHA256 of the final gate APK installed on S9/Tablet (later A41 control):
`858A76A08C0F2395670308E2503A2D7DEFBCE6842E8A69B024349356F7A9F463`.
Earlier40ms build was
`5E42F550E73D9B625B2EC392CAAA3D82994118114A182F38418029AA4249EDC3`;
rerun compilation produced a different APK hash, so it is a distinct frozen
campaign, not a claim of byte-identical earlier hardware evidence.

Normal Enable Host once at20:20:34.015UTC; frozen SINGLE/DIRECT_SHELL required
Video/SystemAudio/Input plan. No Disable/Enable between counted attempts.

| Cycle | Streaming UI -> Disconnect request | Generation | Enqueued / written | Rejected | No consumer | Stale (admission subset) | Pre-window | HWM | Result |
| --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- |
| 1 | 20:21:04.380 ->20:22:52.560UTC,108.180s | 8645117529229519507 | 5272080 /4316728 | 240 | 916656 | 37976 (7680) | 720 | 16 | Complete; continuous-reader quality improved; one5ms fresh rejection at reopen |
| 2 | 20:23:59.738 ->20:25:48.353UTC,108.615s | 5239074919033046114 | 5316480 /4342728 | 0 | 950712 | 22320 (8160) | 720 | 16 | Complete; no unexpected IPC rejection |
| 3 | Streaming20:26:38.626UTC; terminated before qualifying interval | 7551528650500416157 | 177600 /0 | 0 | 177600 | 0 (0) | 0 | 4 | FAIL: path/radio loss, no qualifying remote consumer; counting stops |

No fourth attempt or replacement pair. Cycles1/2 each have72s ordinary readers,
6s closed/absent and a fresh28s reopen through Disconnect. Active sampled queue
is generally0-6, not persistent near16. In cycle1 stale total13256 is flat during
the first continuous interval; cycle2 total7200 is flat. Unexpected rejection
delta is0 in both first continuous intervals. Reopen discards stale data and
cycle1 rejects one240-frame packet; cycle2 rejects none. These are NOT zero
whole-Session loss or a fully accepted transition-quality milestone.

App max eligible dequeue age38.795/38.213ms; helper max eligible age39.980/
39.821ms. Cycle2 max RPC68.521ms is discarded/recovered under freshness, not a
permanent latency target. Global HWM includes transitions. Complete queue
residence p50/p95/p99 and separately instrumented teardown/generation drop totals
are NOT AVAILABLE. Exact release accounting balances enqueued = written + absent
+ stale + pre-window for both completed cycles; no additional unexplained
release remainder, but that is not a direct generation-drop counter.

Raw RemoteSubmix AudioTrack underruns include idle/activation starvation:
cycle1 is stable59 in the late first reader and78 after reopen; cycle2 is15
at final samples. These are not zero whole-Session track-underrun results.
Cycle2 final policy snapshot has zero-write12274, partial-write3287, max write
5.532ms and close transition20.023ms. Zero/partial nonblocking retries are
backpressure, not automatically lost frames. No exact write-residence percentile
is claimed. Unexpected recurring steady IPC loss is not observed in the two
completed windows, but three-cycle acceptance remains unfulfilled.

DEBUG last sink snapshots are cycle1 rejected11280/underrun41/xrun0;
cycle2 rejected3120/underrun29/xrun0. Earlier cycle1 sink has xrun1 and decoder/
sink counter resets during cold startup; do not erase it with the last snapshot.
Cycle3 last snapshot underrun1105 occurs after microphone transport stops.
These DEBUG counters are separate from Android-input delivery. Startup recovery
still needs qualification; no fresh zero-loss DEBUG token or Session-level
reset sum is fabricated. Policy addresses68/69 remain single-owned throughout
their completed Sessions despite early decoder counter resets.

## Acoustic, routing and restoration evidence

S9 built-in physical microphone captures its bounded997Hz speaker tone at
moderate volume, through the normal app microphone, encoder and protected
Session. No synthetic PCM injection or voice recording is used. Example5s
source window RMS612.01/component709.24, Host decoded RMS708.58/component927.66;
different local windows are not synchronized gain/latency measurements.

| Ordinary Tablet reader | OFF RMS /997 component | ON RMS /997 component | OFF after tone | Actual route |
| --- | --- | --- | --- | --- |
| Cycle1 first open | 33.04 /0.42 | 749.06 /1057.41 | 28.56 /0.10 | type25/id1860 |
| Cycle1 reopen | 27.32 /0.04 | 714.82 /1008.84 | 35.53 /0.59 | type25/id1860 |
| Cycle2 first open | 18.29 /0.39 | 588.02 /830.85 | 17.90 /0.17 | type25/id1890 |
| Cycle2 reopen | 23.70 /0.34 | 575.32 /812.86 | 27.09 /0.27 | type25/id1890; then15/id11 after stop |

Full tone windows have about48128 samples/second and peaks far below32767;
997Hz correlation is measured, not a fabricated FFT dominant-frequency sweep.
Quiet first-open/reopen samples precede the new tone; old queued PCM is rejected
by the temporal guard. This is bounded signal/window evidence, not proof of
zero age for arbitrary speech inside Android's own pipe. Human audibility of
this APK is NOT_CONFIRMED; Tablet DEBUG speaker volume was0 during input tests.

Cycles1/2 Disconnect return Client Ready/Host Waiting at20:23:06.275 and
20:26:02.017UTC; next discovery works and generation changes. Ordinary physical
control returns type15/id11 after policy removal. Third failure also removes
the policy and subsequent ordinary consumer uses physical input. Built-in mic
is not deleted; no forced preference was set. Global UID RULES = NONE.

## Health, portability, priority and regression boundary

Failed helper replacement during real Wireless Debugging loss is physically
observed: FEATURE_PCMSHAREDRING_WIRELESS_DEBUGGING fails with
WirelessDebuggingDisabledException, then HOST_PUBLICATION WITHDRAWN
DIRECTSHELL_OWNER_LOST, readiness generation3/4 STALE and generation5
USER_ACTION_REQUIRED/WIRELESS_DEBUGGING_DISABLED. No stale Available after
loss, hidden helper relaunch or provider migration is introduced.

Portability/priority are **NOT ADVANCED** because the quality gate did not pass.
Historical A41 result remains MODIFY_AUDIO_ROUTING permission-blocked under
UID2000; S7 remains context-adapter-blocked below31, not a proof of Android8
framework impossibility. Historical MIC/VOICE_COMMUNICATION/CAMCORDER natural
selection remains valid; only MIC is re-exercised here. Setter/clearer and
abnormal forced-preference cleanup are NOT TESTED. No model/OEM branches added.
Existing S9 compatibility-service startup logs are not a microphone capture or
Host-provider fallback: observed MIC_CAPTURE_STARTED uses app AudioRecord,
and counted Host plans are SINGLE/DIRECT_SHELL. Do not claim Shizuku never started
anywhere; no hidden Shizuku microphone/Host fallback was used.

One separate bounded A41 ->S9 SystemAudio control uses the same final APK and
normal SINGLE/DIRECT_SHELL. Streaming20:33:04.777UTC, Ready/Waiting20:33:35.666,
with an8s controlled Host tone and20s intentional Streaming wait. Last snapshot:
2118960 decoded frames, rejected69120, underrun789, xrun0. Audio starts before
UI Streaming, so this is not an equal-duration comparison to historical30720/
373/0. Quality remains open; neither improvement nor worsening is inferred
from unequal cumulative windows. SystemAudio ring960frames/stereo and reserve
were not changed. REMOTE_ONLY historical human evidence is not re-earned here.
Video control decodes/releases60 frames; Input channel starts/reset/stops with
zero injection errors, no deliberate human input event qualification.

Normal disable leaves Tablet/A41 purpose-specific helpers0/secrets0 and no
owned policy mix; all devices Ready/Host disabled. Volumes restored Tablet2/15,
S9 15/15; collectors explicitly stopped. No global FD-leak claim.

## Tests, gates and preserved worktree

Six new cases this continuation: queue expired-history admission, fresh overflow
preservation, exact age boundary, bounded sweep/frame accounting; closed-transition
maintenance exclusion and active-consumer maintenance exclusion. Existing frame
freshness boundary now tests40ms. Generation, consumer-window and authentication
tests remain; no unit test pretends to prove speaker/framework audibility.

Exact new/modified case names:

- `RemoteMicrophonePcmQueueTest.expiredHistoryCannotRejectFreshAudioAtCapacity`
- `RemoteMicrophonePcmQueueTest.freshValidAudioIsNotSilentlyEvictedToHideOverflow`
- `RemoteMicrophonePcmQueueTest.exactAgeBoundaryRemainsEligible`
- `RemoteMicrophonePcmQueueTest.boundedSweepCountsEveryExpiredFrameAndPreservesOrder`
- `DirectShellMicrophoneFrameTest.closedConsumerTransitionDoesNotWriteMaintenanceAgainstPauseOrFlush`
- `DirectShellMicrophoneFrameTest.activeConsumerNeverReceivesMaintenanceSilenceInPlaceOfRealPcm`

- git diff --check PASS.
  An extra diagnostic invocation overriding `core.autocrlf=false` failed by
  treating existing Windows CRLF content/generated logs as whitespace. This was
  a check-configuration error (TEST_HARNESS_DEFECT), not a product change;
  no file/configuration was modified. The exact requested command without that
  override was rerun explicitly and passed.
- ktlintCheck/lintDebug/testDebugUnitTest --rerun-tasks/assembleDebug/
  assembleDebugAndroidTest/packageDirectShellProbeDex PASS,100 executed tasks,
  bounded workers2/no parallel;1004 JVM tests,161 suites,0 failures/errors/skips.
- Native Debug and Release rebuilt and CTest22/22 each. Initial Debug build
  fails LNK1168 executable write and LNK1201 PDB write; preserved. No matching
  owned native process found. One explicit worker1 reproduction passes. Exact
  locking actor is INCONCLUSIVE, local build environment, not product behavior.
- No new native production edit in this continuation; inherited native changes
  remain. Generated binaries/reports/logs are not checkpoint material.

New/changed continuation code is DEBUG client/server bounded timing, owned audio
worker priority, policy transition locking,40ms frame freshness, bounded queue,
and two test files. Ignored harness changes are waited launch and SystemAudio
tone control. Inherited large source/test/docs/native/machine-local changes
remain, not all authored here. No clean milestone commit while quality is
PARTIAL; no generated evidence or `.git/index.lock` included.

Documentation updated in this continuation:

- `docs/phase6_5/MicrophoneConsumerTransitionQuality.md` (new focused report)
- `docs/phase6_5/S9TabletPeerLinkRecovery.md`
- `docs/phase6_5/DirectShellGlobalMicrophoneInjectionQuality.md`
- `docs/phase6_5/DirectShellGlobalMicrophoneInjection.md`
- `docs/phase6_5/DirectShellMicrophoneInjectionQualification.md`
- `docs/phase6_5/MicrophoneAudioE2EQualification.md`
- `docs/phase6_5/MicrophoneAudioVirtualInputArchitecture.md`
- `docs/phase6_5/MicrophoneAudioCadenceRootCause.md`
- `docs/phase6_5/CurrentNonRootDebt.md`
- `docs/phase6_5/README.md`
- `docs/FutureRoadmap.md`
- `docs/testing/AndroidCompatibilityMatrix.md`

Evidence remains excluded under `app/build/reports/global-window-*`; relevant
frozen campaign prefixes are `global-window-age40` and `global-window-final40`.
Gradle results are in `global-window-age40-gates.txt`; native results are in
`global-window-native-debug-ctest.txt` and `global-window-native-release-ctest.txt`.
Failed attempts and the initial native build failure remain alongside them.

Security/Complete Host unchanged: Video + SystemAudio + Input; optional microphone,
authenticated Session/RPC, generation, trust, anti-replay and frozen plan. No
Root, flashing, SELinux weakening, framework/HAL/vendor edit or generic shell.
RFC-006J still says Same-provider Recovery, Root-assisted Remediation and Live
Provider Migration NOT IMPLEMENTED. Existing pre-Session composite planning is
not post-failure recovery. RFC-005J/K, display policy, HOST_AND_REMOTE, broader
input routes and release promotion remain deferred.

Next single task: stabilize/diagnose the Tablet radio prerequisite sufficiently
to complete a fresh3x>=60s S9 ->Tablet consumer-transition qualification on the
preserved40ms candidate. Do not start SystemAudio quality redesign or global
microphone portability/priority promotion before that gate closes.
