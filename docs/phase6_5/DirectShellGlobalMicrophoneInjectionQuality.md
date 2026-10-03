# Global microphone input quality follow-up - 2026-10-02

## 2026-10-03 Final Quality Closure

[Final campaign and preserved failures](DirectShellGlobalMicrophoneFinalClosure.md):
C782DD9D r2 passes3/3 consecutive S9 ->Tablet ordinary MIC absent/open/close/
reopen Sessions above121s. All sampled steady queue rejection/stale/pre-window,
track starvation and DEBUG rejected/underrun/xrun deltas0. Independent maintenance
silence supply corrects Android-track reserve; no buffer capacity growth.
Fixed40ms freshness retained, no adaptive algorithm needed on this evidence.
An additional abnormal test has two bounded185/193ms initial track transients,
not recurring steady loss; they remain documented. Earlier PARTIAL/FAIL notes
below are historical, not erased. Quality token applies only to the exact path;
valid forced preference and release promotion remain unimplemented/unvalidated.

## 2026-10-03 Current Quality Boundary

Latest [residual admission/cadence evidence](MicrophoneReceiverSchedulingQuality.md)
supersedes the note below: peer link restored, one-burst DEBUG ring admission
corrected without buffer growth. Final81F10D24 first cycle still quality FAIL,
DEBUG rejection0 but track+3 during first steady period; reopen DEBUG underrun+7,
track+13 with matching sender gaps up to83ms. IPC steady rejection/stale0.
No2/3 count, fixed40ms age unchanged, no adaptive/priority claim. Gates1017 JVM
and22/22 each native pass. Functional global injection remains validated.

Latest [receiver scheduling investigation](MicrophoneReceiverSchedulingQuality.md)
identifies a local socket-drain scheduling defect and tests a microphone-only
audio-priority correction. Diagnostic steady DEBUG/track/IPC deltas0 do not
replace final acceptance: frozen43FB71D6 cycle1 FAIL after peer/media loss and
Tablet Wi-Fi/Debug wireless loss;2/3 NOT RUN. TDLS correlation is not sole-cause
proof. Quality PARTIAL,1016 JVM gates pass, fixed40ms IPC and80/70ms DEBUG remain
separate. No adaptive policy, forced priority or new quality token.

[Source-clock follow-up](MicrophoneSourceClockRecovery.md) is the current
boundary: recoverable pacer debt is corrected without larger buffers, but the
first counted D28F6136 cycle fails with recurring steady DEBUG/RemoteSubmix
underflow and IPC rejection. Counting stops at1, no2/3 or3/3 claim. An80s stable
continuous-reader diagnostic does not replace close/reopen acceptance. A later
attempt suspends media before the subsequently logged Wi-Fi disconnection;
Android then disables Debug wireless. Initiating cause remains INCONCLUSIVE,
lost prerequisite is proven. Quality PARTIAL; no adaptation/forced priority.
Provisional40ms IPC freshness remains distinct from the frozen80/70ms DEBUG
reserve. The following report is historical, not overwritten evidence.

[Full continuation](MicrophoneConsumerClockContinuity.md) preserves858A76A0,
62309A1A and1F6666D1 failures before the final66A8B330 build. Same-generation
healthy microphone output now survives decoder freshness resets; explicit
discontinuity rearms the existing reserve without hiding ordinary starvation.
Final3x108s functional campaign has IPC rejection0 and balanced no-consumer/
stale/pre-open drops. It is NOT a quality PASS: cycle3 DEBUG steady underrun
delta13 and RemoteSubmix AudioTrack0->3->5->7->9 occur while the reader remains
continuously open. Exact upstream cause remains unresolved; no larger queue.
Follow-up diagnostic fails before required UI Streaming, followed by proven
Wi-Fi disconnect ->Android adbwifi disable ->typed failed replacement ->Host
publication withdrawal. Radio/stall initiating cause remains INCONCLUSIVE.
40ms IPC freshness is provisional and separate from80/70ms DEBUG playout.
1007 JVM/161 suites and native22/22 each pass. No quality/priority token or commit.

## Latest continuation: Tablet returned, primary campaign interrupted again

Historical continuation, superseded by the current boundary above.

[Consumer-transition report](MicrophoneConsumerTransitionQuality.md) preserves
the exact87EF556C guard-candidate FAIL and sequential timing/TCP_NODELAY/worker/
lock experiments. Freshness-aware FIFO admission and a provisional40ms local
IPC limit improve active-reader occupancy without changing80/70ms DEBUG playout.
The full-gate858A76A0 APK completes two108s Sessions under one Enable Host;
continuous IPC rejection/stale deltas are0, but cycle1 has one240-frame reopen
rejection and startup/transition stale drops remain. Cycle3 loses its path;
Tablet radio/Bluetooth SIGKILL/Wi-Fi disconnection disables Wireless Debugging.
Counting stops, no substitute pair or quality token. Physical input/restoration
and failed-replacement publication withdrawal are observed. Portability and
forced priority remain NOT ADVANCED. Full gates1004 JVM/161 suites, native
Debug/Release22/22; first native linker failure is preserved. Quality PARTIAL.

## Historical continuation: peer-link restored, primary hardware unavailable

[Bounded network investigation](S9TabletPeerLinkRecovery.md) reproduced the
pair-specific failure outside Warpnect. S9 UDP reached Tablet, but Tablet-to-S9
reply/data failed; A41 controls passed. One explicit Tablet Wi-Fi reassociation
restored bidirectional ping and all eight TCP/UDP token/echo controls, with
unchanged IP/MAC/SSID/BSSID. The exact lower-layer fault remains INCONCLUSIVE.
Initial IPv6 `-I` probes failed locally; corrected scoped S9-to-Tablet ping
passed3/3. No product network recovery or media change was introduced.

The unchanged87EF556C candidate rebuilt successfully; full SHA256 is in the
network report. Android switched Wireless Debugging off during rejoin; before
normal UI restoration completed, Tablet USB disappeared. The operator confirmed
the Tablet is unavailable/replaced by S22 and authorized pausing. **No new
microphone Session was counted.** S22/A41 was not substituted. The consumer-window
guard and failed-replacement health branch remain physically unvalidated.
Quality stays PARTIAL; the functional global-input and DEBUG cadence tokens
remain preserved. Portability, forced priority and SystemAudio redesign did not
proceed. A new full-gate rerun was interrupted at the pause, before tests; this
does not replace the previous completed998-test gates. Historical attempts below
remain unchanged. Resume the same primary acceptance when Tablet returns.

## Resumed final-closure attempt - current status

**PARTIAL. Wireless Debugging was restored and verified ON. The existing
candidate then failed the physical quality gate; subsequent S9/Tablet unicast
reachability failed independently of Warpnect.** No quality completion token,
portable privilege extension, forced preference or release promotion is earned.
`DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED` and the separate frozen-APK
DEBUG cadence milestone remain accepted, with their original scope below.

Repository start/end HEAD remains `715b2151af2c8ecac165729178266b24e5cae91b`,
branch `codex/directshell-production-runtime-integration`, master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`, upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31` (ahead19/behind0).
This resumed attempt started with staging0/unstaged202/untracked86,
directory-collapsed:52 source/debug,18 tests,12 docs,159 generated-native and47
pre-existing unrelated paths. Checkpoints92347d0/809b7dd/715b215 are verified;
the pre-existing empty `.git/index.lock` is preserved. No commit, stash, reset,
clean, discard, rebase, merge or push. Unsuccessful experiments below were
removed from our own edits only; inherited partial work/evidence was preserved.

### Candidate, physical attempts and failed experiments

The initial EBE1B1B2 candidate is exactly the preceding implementation:
one Session-owned policy, observer address/type/silencing matching without UID,
Binder route lookup outside the feed monitor, nonblocking maintenance zeros
when absent, real PCM discarded when absent, original enqueue timestamps kept
through partial-write retries and80ms local freshness checks. Android input
delivery is independent of a full DEBUG speaker ring. Initial decoder-only
priming may preserve the output clock; failed consumed-helper replacement
invalidates existing Host health. Ring80ms/reserve70ms, pacer, codec,5ms packets
and socket buffers were not changed in any experiment here.

| Physical device | Exact serial | Android / API | Build fingerprint | Role in this resumed attempt |
| --- | --- | --- | --- | --- |
| X700_EEA Tablet | Z1382023HGM1895372 | 13 /33 | Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys | Global MIC Host; ordinary consumer and restoration |
| SM-G960F S9 | 23eaec0f12047ece | 10 /29 | samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys | Primary physical microphone Client; SystemAudio Client |
| SM-A415F A41 | R58N52B32JJ | 12 /31 | samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys | Comparison microphone Client; bounded SystemAudio Host |
| SM-G935F S7 | 9886734e4937395657 | 8.0.0 /26 | samsung/hero2ltexx/hero2lte:8.0.0/R16NW/G935FXXU8EVH2:user/release-keys | Identified only; no new microphone capability probe |

S22 was not required or tested in this resumed attempt. Hardware actions use
explicit serials. Tablet/A41 Wireless Debugging was verified ON; no manual
UID setup, preferred-device selection or additional injection configuration.

Each row is an explicitly separate attempt. Quality failure stopped acceptance
counting at cycle1. Sessions2/3 of a final campaign were NOT RUN; diagnostic
success is never retroactively substituted for a failed counted cycle.

| Evidence / APK | Path and Streaming-to-Disconnect interval | Ordinary MIC lifecycle | Unexpected IPC rejected frames | Intentional stale frames | No-consumer discarded frames | HWM | Last DEBUG rejected / underrun / xrun | Result |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| `global-final-fixed`, EBE1B1B284AD36B5867BB4BAD13AA8165DF90701AFE2D1BD591AD67439193A12 | S9 -> Tablet,111.017s; normal Enable15:08:35.430UTC, Streaming15:09:01.902, Disconnect requested15:10:52.919 | absent8s, ordinary MIC72s, closed/absent, reopen28s through Disconnect; Ready/Waiting15:11:03.442 | 3120 | 2952 | 943440 | 16 | 8400 / 0 / 0 | Lifecycle PASS, quality FAIL; original candidate not accepted |
| `global-final-flush-open`, E26A02C3618FBA538E5749604FAE6A42B016DCE4F1C9460B1B323BD4E7B00B87 | S9 -> Tablet,109.657s; separate normal Enable15:16:21.400UTC, Streaming15:20:59.095, Disconnect15:22:48.751 | same72s/28s consumer protocol; Ready/Waiting15:23:02.567 | 11520 | 17024 | 1082880 | 16 | 6240 / 44 / 0 | Opening flush hypothesis NOT supported; reverted |
| `global-final-small-threshold*`, CEBCBE958E9593488124CEF2E9196F07ACC37A841AD4CEE5D64436A013678183 | S9 -> Tablet;3 actual handshakes time out before Streaming, no microphone media | No consumer test reached | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Environmental unicast boundary; threshold not evaluated on S9 |
| `global-final-threshold-a41`, same CEBCBE95 implementation | A41 microphone -> Tablet,112.479s; normal Enable15:47:07.318UTC, Streaming15:47:58.226, Disconnect15:49:50.705 | same72s/28s consumer protocol; Ready/Waiting15:50:00.759 | 306720 | 71976 | 1388400 | 16 | 10080 / 27 / 0 | Comparison quality FAIL; reduced threshold reverted; NOT primary acceptance |

DEBUG numbers are last native-sink snapshots, not fabricated Session sums:
sink reconstruction resets these counters. Session-level ring-full drop
snapshots were83/68/4739 respectively. A zero last underrun snapshot does not
erase an earlier sink's history. Capture/transport/decoder advanced, but full
end-of-Session sequence/loss/reorder totals were not collected by this harness.
No cross-device clock subtraction or absolute acoustic latency is claimed.

Initial IPC release accounting: enqueued5397120 = written4450728 + absent943440
+ stale2952. Flush experiment:5445600 =4345696 +1082880 +17024.
A41 comparison:5232000 =3771624 +1388400 +71976. Unexpected queue rejections
are outside enqueued totals. These are PCM frames, not protected-packet loss.
Generation-mismatch and teardown-discard counters are not available in the
current probe; tests prove rejection/cleanup guards, not measured zero counts.

### First trustworthy quality boundary and limits of the diagnosis

On EBE1B1B2, while absent the app queue was0-2, rejected/stale0. At the owned
recording callback's active transition373545.726 (local log ordering), the
first progress sample already reports queue13/HWM16, rejected2400/stale2088.
Queue occupancy then stays about11-14, with age close to the80ms ceiling.
During the first continuous reader, rejection rises2400 ->2640 before close;
late stale drops also recur. Final rejection3120 is not explained away as an
initial transient. This local delivery-pressure boundary is established;
the precise cause of persistent AudioTrack/IPC backlog is **INCONCLUSIVE**.

Raw input-track underruns in the original candidate were0 ->31 while absent,
then65 on first open and stable65 in later samples. This is a different metric
from DEBUG Oboe callback zero-fill and hardware xrun. Idle maintenance-silence
underruns do not establish lost user audio. Opening flush instead reaches70
and raises IPC call maximum from29.600ms to53.243ms; it does not solve backlog.

The active AudioFlinger diagnostic of the flush experiment reports virtual
AudioTrack capacity4104 frames (85.5ms at48kHz),3800 ready frames, RemoteSubmix
HAL period1024 frames, and output/input thread-loop latency statistics near64ms.
Its reported track latency142.48ms is a software diagnostic, NOT physical
presentation or microphone E2E latency. The reference
[Android13 RemoteSubmix HAL](https://android.googlesource.com/platform/hardware/libhardware/+/android-13.0.0_r1/modules/audio_remote_submix/audio_hw.cpp)
has its own pipe, including conditional blocking when an input exists; pausing
and flushing AudioTrack does not prove that HAL pipe was flushed. OEM identity
with that reference is not assumed. The public
[AudioTrack contract](https://developer.android.com/reference/android/media/AudioTrack)
explains the default full-buffer streaming start threshold. Lowering only this
threshold to240 frames, keeping capacity4104, did not produce a quality PASS:
A41 comparison raw track underruns reach522. Different sender/pair conditions
prevent a clean S9 A/B attribution, so that candidate is not retained.

### Signal, routing, consumer and restoration

The EBE1B1B2 ordinary app consumer uses standard AudioRecord(MIC), UID10213,
no privileged read, no ring/callback handoff and no preferred-device selection.
Natural input is type25/id1460. OFF samples around373549-373550 have RMS29.15/
26.70 and997Hz component0.58/0.23; ON samples373552-373555 have RMS716.49-
751.24, component1011.82-1061.72 and peak at most1188; OFF373557 is RMS21.11,
component0.46. This is non-clipping physical S9-speaker -> S9-microphone ->
protected Session -> Android ordinary-consumer signal evidence, not a digital
PCM fixture. Frequency evidence is the fixed997Hz correlation, not a measured
dominant-frequency spectrum or an E2E latency. No speech/raw PCM was saved.

The reader closes and reopens on the SAME policy/address/helper generation;
registration is not recreated for its decoder resets. At normal Disconnect the
still-running consumer moves to built-in type15/id11, then a separate4s ordinary
physical-input control runs. Flush and A41 comparison also return to type15/id11.
Input enumeration preserves built-in devices alongside the dynamic submix.
MIC functional evidence is retained; VOICE_COMMUNICATION/CAMCORDER are not newly
run in this attempt because quality did not pass. Their prior physical proofs
remain accepted. No forced preference was applied.

The A41 comparison's self-speaker tone clipped (peak32768, ON RMS up to20766).
Its initial media volume was15/15; that acoustic window is **NOT** a qualified
non-clipping signal test. This is a TEST_HARNESS_DEFECT in comparison gain setup,
not evidence that clipping explains IPC timing. The tone was bounded5s and not
repeated. Primary S9 acoustic windows used3/15 and did not clip. Human final
audibility is NOT_CONFIRMED. Tone OFF while absent followed by immediate open,
or a speech-replay measurement, was not performed; no such PASS is inferred.

### Bounded new correction: consumer-window admission

Source inspection proves a separate stale-admission race: the old helper tests
consumer presence at DELIVERY time only. A frame enqueued while absent can be
delivered after open and wrongly admitted as live real PCM if still below80ms.
The final uncommitted candidate now stamps the beginning of each observed
active-consumer window with helper System.nanoTime. A packet's original local
enqueue timestamp must belong to that window; otherwise it returns the explicit
`DROP_CONSUMER_TRANSITION` result. App and helper count transition drops
separately from absence, age expiration, unexpected queue overflow and writes.
Partial tails preserve their original timestamp, and closure resets the window.
This is the collective owned-submix recording observation, NOT per-app/UID
tracking or the exact timestamp of an application's AudioRecord request.
The same-Host cross-process timestamp assumption is Android-specific: the
[Android13 libcore implementation](https://android.googlesource.com/platform/libcore/+/android-13.0.0_r1/ojluni/src/main/native/System.c)
maps System.nanoTime to CLOCK_MONOTONIC without a process-specific offset.
This is not a portable Java multi-VM guarantee or independent-device clock
synchronization. The stamp is Host decoded-PCM enqueue time, not Client acoustic
capture time; it cannot bound transport age or audio already inside the HAL.

Changed source: `DirectShellMicrophoneFrame.java`,
`DirectShellRemoteMicrophonePolicy.java`, `DirectShellRemoteMicrophoneSource.kt`.
New focused tests in `DirectShellMicrophoneFrameTest.java`:
`delayedDeliveryDoesNotAdmitPcmFromBeforeConsumerActivation` and
`closedAndReopenedConsumerCannotAdmitPreviousWindowPcm`.
These prove local admission logic, not Android consumer sound or pressure closure.
The guard remains physically UNVALIDATED because the required S9 peer-link is
unavailable. It is not claimed to fix queue fullness or DEBUG sink resets.

### Age policy, buffering and latency decision

Fixed80ms remains a candidate local freshness limit, NOT an accepted final
quality decision. Adaptive evaluation is **PARTIAL / NOT IMPLEMENTED**:
there is no measured benefit supporting added adaptive complexity. Current
probe reports maxima, not queue-residence percentiles; p50/p95/p99 cannot be
reconstructed from5s maxima. Original max admitted app enqueue age79.034ms,
helper pre-write age79.969ms; IPC round-trip maximum29.600ms. These are local
Host monotonic observations, not AudioTrack write duration or ordinary-consumer
PCM age. Clock sources from independent devices are never subtracted.

Queue capacity stays16 packets (nominal3840 frames/80ms of5ms mono data),
HWM16, nonblocking enqueue, serialized nonblocking AudioTrack write/retry on a
separate worker. The queue cannot grow without bound. Expired data is discarded
on dequeue/feed; a blocked worker can retain expired bytes until it resumes,
so80ms is an admission rule, not a guaranteed maximum in-memory residence.
Nor does it bound Android's additional Track/HAL/consumer buffering. Absent
real PCM is discarded and zeros maintain selectability; no real PCM is deliberately
kept for a future reader. The new window guard adds NO intentional delay or
buffer capacity. Injected-audio total latency is NOT measured. The separate
80/70ms DEBUG playout reserve remains unchanged and must not be added to, or
confused with, this IPC deadline as if it were the same pipeline boundary.

### External boundary and anomaly record

After the two S9 quality attempts, CEBCBE95's S9 handshakes show ClientHello
followed by a4s Timeout, mapped to AuthenticationFailed. They never reach audio.
Both devices have coherent192.168.1/24 routes/ARP identities and reach the router
3/3. S9 -> Tablet ping0/3 and Tablet -> S9 ping0/3, while A41 reaches each3/3.
One explicit S9 Wi-Fi off/on outside any Session retained its address and still
reported Destination Host Unreachable. A later S9 -> Tablet recheck also0/3.
This establishes an **ENVIRONMENTAL_PRECONDITION: peer unicast unavailable**,
not an AudioPolicy/codec/security failure; exact AP/driver mechanism is unknown.
No router, firewall, OS image, SELinux or Root change was attempted. A single
manual Tablet network rejoin was requested; no automatic product recovery exists.

Initial original-candidate quality FAIL is preserved; closest functional PASS
is CF5C311C, not a lossless input-quality PASS. Opening-flush comparison closest
is EBE1B1B2; threshold comparison is not an equivalent S9 A/B. Two additional
harness failures (failed Client-state actions and a cold null-root UI dump) did
not produce media and are preserved separately. All3 actual timeout handshakes
and all harness attempts remain in `global-final-small-threshold*` evidence;
none is an invisible successful retry. New laboratory checks reject terminal
Failed state early; foreground launch readiness still requires a real UI.

The first rerun after the consumer-window guard also failed in Gradle workers:
native malloc failures/insufficient Java runtime memory, exit1, with crash
reports under `.gradle/workers/hs_err_pid*.log`. This is an environment/build
resource failure, not a passing gate or a microphone product regression.
The same gates are repeated visibly with max-workers2/no-parallel/no-daemon,
without changing project or audio runtime settings. Two subsequent bounded runs
fail ktlint on the new boolean-expression formatting (newlines, then closing
indentation); these are source style defects, corrected without behavioral
change. A further full rerun is preserved separately. Final results are recorded
below once completed. No failed log is deleted.

| Failed attempt | First trustworthy boundary | Classification / closest comparison | Disposition |
| --- | --- | --- | --- |
| Existing-candidate counted cycle1 | App IPC queue remains11-14/HWM16, valid enqueue rejected during continuous consumer | PREEXISTING_PRODUCT_DEFECT; CF5C311C is closest functional milestone, not an input-quality PASS | Acceptance stopped; pressure cause still INCONCLUSIVE |
| Opening-flush candidate | Unexpected queue loss persists and IPC call stalls remain | Existing pressure unresolved; causal benefit NOT supported versus EBE1B1B2 | Experimental change reverted |
| Smaller threshold A41 comparison | IPC rejection and raw track underruns recur | INCONCLUSIVE for the threshold hypothesis because primary sender/pair differs | Experimental change reverted; no qualifying S9 A/B |
| S9 three handshake timeouts | ClientHello times out before media; independent peer ping fails both ways | ENVIRONMENTAL_PRECONDITION; earlier S9 -> Tablet Streaming is nearest PASS | No security/timeout workaround; one explicit network rejoin did not restore peers |
| UI launch/action failures | Missing app/root or terminal Client state before media | TEST_HARNESS_DEFECT; successful normal-UI runs are nearest comparison | Preserved; no failed action counted as a successful Connect |
| A41 comparison acoustic window | PCM peak32768 at original15/15 volume | TEST_HARNESS_DEFECT; non-clipping primary S9 window is comparison | Not qualified; original volume restored, no extra loud-tone attempt |
| First guard software rerun | Gradle workers cannot allocate JVM/native memory | ENVIRONMENTAL_PRECONDITION; earlier996-test full run PASS | Bounded worker-count rerun, no runtime/config change |
| Guard ktlint reruns | Boolean-expression newline/indentation violations | New build/style defect, not hardware evidence; previous style gate PASS | Fixed; both failed logs preserved before full final rerun |

No exploratory counter improvement is labelled a root-cause fix; no failed
counted campaign is turned into PASS or silently restarted.

### Architecture/capability and remaining boundaries

UID RULES = NONE: actual built criteria remain capture-preset rules1/7/5 only.
No model/OEM branch, additional product user setup, generic privileged endpoint,
new provider, Root, protocol redesign or forced routing role is introduced.
Client consent, trust, Session authentication/anti-replay/generation, existing
Audio Payload V1 and DirectShell secret/HMAC/owner lifetime remain unchanged.
The added result is on existing authenticated LOCAL feature IPC only.

| Boundary | Current resumed result |
| --- | --- |
| Tablet MIC global input | FUNCTIONALLY VALIDATED exact prior APK; candidate quality FAIL/PARTIAL; consumer-window guard software-only pending physical gate |
| Tablet VOICE_COMMUNICATION/CAMCORDER | Prior functional evidence preserved, NOT RETESTED here |
| A41 global input | Prior local MODIFY_AUDIO_ROUTING precheck PERMISSION_DENIED; exact AudioService/Binder enforcement and generic alternatives NOT INVESTIGATED before Part A PASS; A41 microphone Client comparison is NOT A41 Host injection support |
| S7 global input | Prior API_LT_31_CONTEXT_UNSUPPORTED remains ADAPTER_BLOCKED, not Android8 impossibility; no new adapter |
| Natural Android choice | MIC type25 observed on new Tablet attempts; built-in type15 returns after stop |
| Priority getter/setter/clear | Prior getter-only evidence retained; setter/clear authorization and forced abnormal-cleanup NOT TESTED; no preference is applied |
| Failed helper-replacement health withdrawal | Inherited source fix/tests preserved; specific physical failed-bootstrap branch NOT RETESTED; normal disable cleanup is a different boundary |
| Recovery/remediation/migration | Same-provider Recovery NOT IMPLEMENTED; Root-assisted Remediation NOT IMPLEMENTED; Live Provider Migration NOT IMPLEMENTED |

Current composite planner composes required Video/SystemAudio/Input providers
BEFORE admission and freezes that plan. It does not restore a failed runtime,
recompute/remediate an active plan or migrate an active Session. Existing health
invalidation and ordinary consumed-feature replenishment are not those future
RFC-006J features. Microphone remains OPTIONAL, outside Complete Host.
Full background/power/screen-off/timeout audit remains deferred to RFC-005K or
the appropriate future RFC; HOST_AND_REMOTE/display policy/005J/005K/physical
DirectRoot and broad input-route/app qualification are unchanged.

### Regression, final gates and disposition

On restored pre-window APK5145B951992816B11B6A51C38AEF6552C6427E1B6F3D4B85428117FB0F66750C,
one normal A41 -> S9 SystemAudio Session reached Streaming16:07:50.501UTC and
Ready/Waiting16:08:31.116. The timed steady window was30s; tone started31.1s
after Streaming during the still-active Session's UI teardown interval, so it
is NOT a full steady tone window. The final partial decoded window nevertheless
has RMS1208.06/peak3726 versus preceding zero-energy windows. Last full sink
snapshot: received5826, decoded5824 frames/1397760 PCM frames, written1367040,
rejected30720, underrun373, xrun0, ring HWM960. Technical/lifecycle PASS only;
quality remains open, and unmatched earlier windows do not prove numerical
quality non-worsening. No microphone80/70 values were copied into SystemAudio.
No new human REMOTE_ONLY listening claim; historical routing result is preserved.

Same protected Session: Video queued/output/released3, encoder3 AUs and no
transport failure; Input START/RESET/STOP OK. These are bounded provider/media
smokes, not a new full physical reverse-input or visible-video campaign.
Frozen plan SINGLE/DIRECT_SHELL; no Shizuku input producer/fallback. Normal
Disable Host on Tablet and A41 leaves helpers0/secrets0 and releases the owned
dynamic microphone policy. No global FD-leak claim. Tested stop restoration
does not qualify forced-preference/helper-loss cleanup. Volumes restored to
Tablet2/15, S915/15, A4115/15; comparison high-gain failure is not hidden.

Software before the window guard: all6 Gradle tasks PASS,100 rerun tasks,
12m12s, JVM996/160 suites/0 failure/error/skip. After the guard and explicit
style repairs, the final full bounded rerun also PASSES all6 tasks,100 executed,
11m14s. XML totals: **998 JVM tests /160 suites /0 failures /0 errors /0 skips**.
Native Debug22/22 (2.26s), Release22/22 (2.51s) PASS; no new native production
edit in this resumed attempt. Earlier memory/style failures remain preserved.

| Final gate | Result | Evidence |
| --- | --- | --- |
| git diff --check | PASS | Final worktree whitespace check; Git CRLF warnings are not errors |
| :app:ktlintCheck | PASS | global-final-consumer-window-final-gates.txt |
| :app:lintDebug | PASS | Same full rerun; existing compiler/deprecation warnings retained |
| :app:testDebugUnitTest --rerun-tasks | PASS,998/0/0/0 | XML suite totals after final rerun |
| :app:assembleDebug | PASS | Same full rerun |
| :app:assembleDebugAndroidTest | PASS | Same full rerun |
| :app:packageDirectShellProbeDex | PASS | Same full rerun |
| Native Debug / Release CTest | PASS,22/22 each | global-final-closure-ctest-debug.txt / release.txt |

Final guard APK SHA256:
`87EF556C31EE47441B63F3CCDCCB487679E8C47FF8B13A3920180063218A6044`.
It contains neither the failed opening-flush nor the lower track-threshold
experiment. Installation is a deployment boundary, NOT a new physical quality
PASS. Release behavior, playout80/70ms, pacer, codec and sockets remain unchanged.
The identical final APK was installed with normal adb install -r on Tablet,
S9 and A41, and the matching AndroidTest APK on Tablet, without permission-grant
flags. All three final UI checks show Ready/Host disabled; Tablet/A41 helper
processes0 and temporary secret files0. Recorded campaign mix registrations
are absent. Owned diagnostic logcat collectors are stopped; no ongoing tone,
microphone Session or required build/harness exec is left running.

New source delta in this resumed attempt is limited to the consumer-window
admission guard/counter in the three DEBUG files named above; the two named
JUnit cases are in the existing partial test file. All other inherited source,
native and test modifications remain uncommitted and are not newly qualified
by this attempt. The ignored laboratory harness/timing/UI improvements and
logs/APKs/dumps are generated diagnostic evidence, not committed product tooling.

Current documentation updated in this resumed attempt:

- DirectShellGlobalMicrophoneInjectionQuality.md (this complete attempt/report).
- DirectShellGlobalMicrophoneInjection.md.
- DirectShellMicrophoneInjectionQualification.md.
- MicrophoneAudioE2EQualification.md.
- MicrophoneAudioVirtualInputArchitecture.md.
- CurrentNonRootDebt.md.
- README.md.
- ../FutureRoadmap.md.
- ../testing/AndroidCompatibilityMatrix.md.

Ending HEAD remains715b215, master/upstream unchanged, staged0/unstaged202/
untracked90 directory-collapsed paths. Ownership:52 source/debug,18 tests,
12 docs,159 preserved generated-native,47 pre-existing unrelated, plus4 new
generated root JVM crash/replay logs from the memory failure. Ignored app/build
evidence is additional, not hidden in source counts. The empty index lock is
untouched. No checkpoint: quality is not physically closed and the guard still
needs primary acceptance. No push, merge or recovery/migration implementation.
Overall: IMPLEMENTED candidate corrections, TESTED software, accepted prior
functional VALIDATION only, PARTIAL current quality, NOT QUALIFIED,
NOT PRODUCTION-READY. No new quality/priority token.

Exactly one next task: **finish this global-input quality gate on restored
S9 -> Tablet peer connectivity**, including the consumer-window guard and a
fresh3x>=60s campaign before portability/priority or separate SystemAudio work.

## Previous follow-up, before manual Wireless Debugging restoration

## Current result

**PARTIAL; final Tablet acceptance is environmentally blocked.** The previously
earned `DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED` applies to the frozen
CF5C311C functional input campaign, not lossless quality or these candidate APKs.
The separate `MICROPHONE_AUDIO_CADENCE_AND_PLAYBACK_QUALITY_FIXED_AND_VALIDATED`
milestone remains accepted for its original frozen DEBUG playback APK.

No new quality, portability-extension or forced-priority token is earned.
Part B alternatives/API26 expansion and Part C priority setters remain deferred:
Part A has not passed. Release behavior is unchanged and fails closed for the
unimplemented production destination. Microphone input is not a fourth required
Complete Host capability.

## Repository and ownership

Branch `codex/directshell-production-runtime-integration`; starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`; master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`, ahead19/behind0.
Checkpoints92347d0,809b7dd,715b215 exist. Initial staging0, unstaged201,
untracked85 directory-collapsed paths:52 source/debug,17 tests,11 docs,
159 generated native and47 pre-existing unrelated paths. The existing empty
`.git/index.lock` is preserved. No commit, reset, clean, discard, merge, rebase
or push. Inherited native/cadence changes are not this task's new source edits.
Ending HEAD is unchanged. Final staging0/unstaged202/untracked86: the additional
tracked test and new current report are not staged. Directory-collapsed ownership
is52 source/debug,18 tests,12 docs,159 generated native,47 pre-existing unrelated.
Logs, screenshots, binaries, build output and machine-local state remain outside
any checkpoint; no clean validated new boundary has been reached for a commit.

Primary pair: S9 `23eaec0f12047ece`, Android10/API29 `G960FXXUHFVG4`, physical
built-in input type15 -> Tablet `Z1382023HGM1895372`, Android13/API33 `2023264`,
ordinary test-app MIC consumer. Full fingerprints remain in the preceding
[functional report](DirectShellGlobalMicrophoneInjection.md).

Numeric evidence and hidden laboratory collectors/harnesses are under ignored
`app/build/reports/global-quality-*`. No speech or raw PCM is saved. Every device
action uses the exact serial. No Root or Android service/system modification.

## Preserved baseline and discriminating experiments

Each row is a separate candidate/experiment, not a retry of a failed acceptance
cycle. The first three candidates changed only the input-side consumer policy;
codec, 5ms packetization, pacer, socket buffers and80/70ms DEBUG ring/reserve
were not enlarged or retuned.
Closest functional PASS for the two physical-mic-selection regressions is
CF5C311C's natural type25 MIC consumer, reproduced again by idle-silence803AE903.
Closest complete candidate lifecycle PASS for the interrupted DBA3D740 attempt
is that idle-silence open/close/reopen run. Neither is a global quality PASS;
the earlier4B4B2598 DEBUG-only cadence success is a different qualified path.

| Evidence prefix / APK | Hypothesis / experiment | Observation | Classification / disposition |
| --- | --- | --- | --- |
| `global-quality-baseline`, CF5C311C | Reproduce absent -> MIC open -> close -> reopen with old unconditional input writes | Generation5699213994912876568; submitted/written4712640, queue rejected81840, HWM16, zero-write retries15282 at last progress sample; DEBUG last rejected13680/underrun46/xrun1 | Original quality FAIL, PREEXISTING_PRODUCT_DEFECT in unbounded-age retry semantics; retained |
| `global-quality-candidate`, 98C8AF86 | Start virtual output only when matching recorder is observed | Ordinary MIC remained physical type15; real PCM written0, no-consumer discarded4950960 | PRODUCT_REGRESSION; chicken-and-egg activation, reverted |
| `global-quality-active-source`, FD31B334 | Call AudioTrack.play but submit no output while absent | Ordinary MIC still physical type15 | PRODUCT_REGRESSION; play alone insufficient, replaced |
| `global-quality-idle-silence`, 803AE903 | Keep virtual output active with zeros, discard unheard real PCM, expire old IPC frames | Natural RemoteSubmix type25/id1404; absent/open/close/reopen works on one Session/policy. Input queue rejected8400 remains; DEBUG last rejected11040/underrun26/xrun2 | Functional partial improvement, quality FAIL; investigate setup/transition stalls, not acceptance PASS |
| Same candidate, initial harness invocation | Begin from launcher without bringing Client activity forward | Failed before Connect; lifecycle output empty | TEST_HARNESS_DEFECT; next invocation explicitly opens existing MainActivity. No hidden Connect retry |
| `global-quality-priming`, DBA3D740 | Move recording-device resolution outside feed lock; preserve output clock during initial codec freshness resets | One Connect issued; Host logs Streaming, media advances429datagrams then stops. Client Streaming was not observed by harness. Later Wi-Fi loss disables Wireless Debugging; policy released | Failed attempt retained. First media halt INCONCLUSIVE; current prerequisite ENVIRONMENTAL_PRECONDITION. No final3x60s campaign |

Complete candidate APK hashes are in prepare logs. Baseline prepare UTC
05:43:07.1712118; absent05:44:25.3132768, first consumer05:44:40.3299931,
close05:45:07.8533247, second consumer05:45:19.8570194, close05:45:49.9165595,
normal Ready/Waiting05:46:14.2640751.

Idle-silence prepare UTC06:03:42.1625125; actual successful harness invocation
`global-quality-idle-silence-lifecycle-attempt2.txt`: absent06:06:56.9596992,
first consumer06:07:12.1798431, Ready/Waiting06:08:46.9926180.
Its single policy generation7899777770346172397, address
`1971008002:ap:56mixr:0`, survives both consumer openings. It is not three
fresh Sessions or a continuously reading60s quality acceptance.

The final priming candidate's input branch admitted102480frames, wrote0real
frames, discarded102480 for absent consumers, queue rejected0/HWM1; it stops
before an ordinary consumer opens. Those zero rejections do not constitute a
quality PASS. The initial progress sample reports track underruns0, not a
measured60s steady-state interval.

## First boundaries and causal evidence

### No-consumer pressure

The old `DirectShellRemoteMicrophoneSource.drain` retried a zero nonblocking
AudioTrack write indefinitely. Its16-entry queue bounded memory, but not the age
of its retained/in-flight microphone content. With no reader, framework/track
backpressure could fill that queue and reject newly decoded live audio.

The [AOSP Android13 RemoteSubmix HAL](https://android.googlesource.com/platform/hardware/libhardware/+/android-13.0.0_r1/modules/audio_remote_submix/audio_hw.cpp)
distinguishes an input which has never activated from an existing standby input:
the former may block preserving the first output, while the latter can discard
unread content. This is framework behavior, not proof that every OEM HAL has
identical timing. A live remote microphone must not retain that old content
indefinitely merely because no application is recording.

Candidate design: observe Android recording configurations for this policy's
own type25/address and non-silenced status; when no matching consumer exists,
discard real PCM intentionally and feed zeros nonblocking to keep normal
recorder selection possible. On consumer close, pause/flush/play the output
track before idle zeros. Never target a consumer UID or require preferred-device
selection. Candidate1/2 show why withholding all output was not equivalent.

### Consumer-opening stall

The initial observer called `AudioRecordingConfiguration.getAudioDevice()`
while holding the same monitor as `feed()`. The
[framework implementation](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/media/java/android/media/AudioRecordingConfiguration.java)
resolves audio patches/device ports, not an app-local cached field. The
idle-silence experiment observes a59.148ms maximum feed call during the first
consumer transition, queue HWM16/rejection8400, and stale discard4800. The final
candidate performs framework device resolution outside that monitor, then
commits only the owned-address state change under lock. This explains a plausible
blocking path; its physical elimination is NOT validated by the interrupted run.

All framework preparation and teardown remain on the asynchronous Session
destination/feature worker, not the protected receiver/decode thread. Decode
only copies/offers into the bounded queue. The diagnostic speaker ring-full error
is still reported, but no longer suppresses forwarding valid PCM to the separate
Android-input branch. No error counter is hidden.

### Cold diagnostic output

Repeated freshness resets during initial priming could reopen the cold Oboe
output before any PCM was consumed, despite Session-owned policy lifetime.
The candidate `AudioReceiverSessionController` preserves the already-warm output
only for same-stream MicrophoneAudio initial priming with zero consumed frames
and no A/V gate; it recreates the decoder, not the sink. Once output has consumed
PCM, and for new configurations, SystemAudio or teardown, the existing complete
pipeline reset remains. A unit regression covers priming and fresh generation.
This is a candidate startup correction, not a hardware quality closure.

## Queue, clocks and age contract

| Boundary | Current candidate contract | Measured evidence / limitation |
| --- | --- | --- |
| DEBUG Oboe ring |3840frames/80ms, initial3360frames/70ms reserve; warm clock | Unchanged frozen budget, not a SystemAudio recommendation |
| Authenticated input IPC queue |16 entries, normally240frames per5ms packet, plus one in-flight entry | No capacity increase; idle-silence absent snapshots occupancy0/1. Entire-run HWM16 still reaches capacity at first opening |
| Local PCM age |Timestamp taken at app-side injection enqueue, same-device System.nanoTime; at most80ms at actual helper write attempt | Idle-silence accepted maximum77.773ms; partial tails preserve original timestamp, never renew it |
| No matching recorder |Count/discard unheard real PCM, nonblocking zeros activate route; close flushes track | Candidate ordinary open/reopen sees live physical signal, but final build still requires acceptance |
| Stale/absent drop |Distinct -1001/-1002 protocol results and counters | Not confused with Android write errors or unexpected queue/ring rejection |
| AudioTrack / HAL |Existing observed capacity4104frames/85.5ms, HAL1024frames/about21.3ms | Additional platform buffering; the80ms IPC bound is NOT a bound on every downstream sample's total age |

Nominal current packet PCM is48kHz monoPCM16,240frames=240samples=480bytes;
new purpose-specific authenticated IPC header20bytes, payload500bytes.
Nominal queue16x500=8000bytes plus500in flight. Accepted maximum960-frame entry
is1940bytes, so protocol-wide bound is31040bytes queued plus1940in flight.
The header timestamp is local IPC only, not Audio Payload V1 or Session wire.

Idle-silence release: submitted5003040, injected2390224, intentional absent
discard2608016, stale discard4800, unexpected queue rejection8400, HWM16.
Last progress zero-write count2883; maximum accepted enqueue age77.773ms.
These totals account for admitted PCM; rejected frames are outside submitted.
No claim of zero unexpected loss or zero steady underflow is made.

The age check adds no deliberate reserve; it expires stale work. Existing
70ms initial playout reserve and downstream AudioTrack buffers still have their
declared latency costs. No Client/Host clocks are subtracted; no acoustic E2E
latency, photon timestamp or hardware presentation precision is inferred.

## Ordinary consumer and physical signal

Candidate idle-silence retains **UID RULES = NONE**, the same MIC1 OR
VOICE_COMMUNICATION7 OR CAMCORDER5 recorder mix, LOOP_BACK and virtual-source
AudioTrack. Standard MIC consumer UID10213 neither accesses the Warpnect ring
nor sets preferred devices. Injection helper is shell UID2000.

First opening selected RemoteSubmix25/id1404. Representative1s consumer windows:
RMS27.50 ->392.14 ->42.62;997Hz amplitude0.272 ->549.82 ->1.039, then quiet.
S9 physical capture5s windows RMS28.11 ->301.82 -> approximately32;
Tablet decoded window RMS350.04 during tone, followed by36.06/31.90.
Partial tone/window lengths differ, so these are signal-presence observations,
not gain calibration. Source is a bounded5s tone from S9's physical speaker
captured by its physical mic; no synthetic PCM enters microphone capture.
Post-Disconnect ordinary capture returns to physical type15. Human listening
on these candidate APKs is **NOT_CONFIRMED**.

VOICE_COMMUNICATION/CAMCORDER are not newly measured in this quality follow-up;
their earlier functional CF5C311C evidence remains accepted. Natural selection
and built-in coexistence remain prior validated evidence, not new priority tests.

## Interrupted candidate, runtime publication and cleanup

Priming candidate prepare UTC06:23:50.0651113. Session policy generation
2233429170483544656, address `1971008002:ap:57mixr:0`, helperPID1502.
Host Streaming monotonic342142.601; media stops around342144 before Android's
network-loss messages342153.432-342153.560. Do not attribute the earlier halt to
later Wi-Fi notifications. The first media halt remains INCONCLUSIVE.
Policy/control closes342147.138; feature replacement subsequently fails with
Wireless Debugging disabled at342153.712/342153.779/342153.809. Wi-Fi reconnects
342156 but `adb_wifi_enabled=0` remains. Manual reactivation has been requested;
no privileged toggle or repeated admission is performed to bypass that condition.

A distinct confirmed **PREEXISTING_PRODUCT_DEFECT**: after failed consumed-helper
replacement, Host UI still says Available/Waiting with zero helpers. In
`DirectShellRuntime.prewarmReplacement`, failed bootstrap recorded/logged a
failure but skipped existing readiness invalidation. The source now invokes
that invalidation and retains the typed Wireless Debugging reason. The same
typed classification is used by the direct feature-acquisition failure path.
No new automatic recovery or provider fallback. Unit health notification passes
in the final software run; physical failed-replacement publication regression
is still required. This is not a redesign of owner-bound idle liveness715b215.

Normal cleanup was requested after the interrupted run. At verification there
are no Tablet DirectShell helpers/temporary-secret paths reported, no running
S9 RECORD_AUDIO app-op, and no dynamic policy survives. Exact final cleanup
and bounded SystemAudio control are recorded below after their completion.

### Final bounded regression and cleanup

Final software APK SHA256:
`EBE1B1B284AD36B5867BB4BAD13AA8165DF90701AFE2D1BD591AD67439193A12`.
Installed on A41/S9 for one new normal SystemAudio control; Tablet's last tested
candidate remains DBA3D740, not this final runtime-health APK. No source change
follows the final gates; subsequent edits are documentation/evidence only.

`global-quality-a41-system-control`: normal Enable Host07:05:58.6571974Z,
Streaming07:06:29.8666275Z -> Client Ready/Host Waiting07:07:05.2016436Z,
35.335s UI interval (requested25s). Frozen SINGLE/DIRECT_SHELL,
Video/SystemAudio/Input all DirectShell, shizuku_started=false. No reconnect.
SystemAudio capture/encode/protected receive/decode/output progress with ordinary
Disconnect: **technical pipeline/lifecycle PASS, quality NOT CLOSED**. Last
sampled S9 sink rejected27840/underrun108/xrun0, HWM960frames, delivered4818,
decoded4817; output counters are sink-local and can reset during recovery.
The source was quiet: measured decoded RMS0. The intended5s tone was issued too
late, after Session termination, so it is **not a remote-signal PASS**. This
companion timing mistake is TEST_HARNESS_DEFECT, recorded without another hidden
Session. No new SystemAudio audibility or REMOTE_ONLY routing claim; previous
human routing evidence remains accepted. Separate causal SystemAudio quality debt
is neither solved nor given microphone80/70ms values.

Video smoke: protected AUs reach decoder, output/release2 at sampled static Host
screen, decoder_dropped0. This is not a new visible/camera or performance campaign.
Input initialization: Running, socket/injection errors0; terminal InputResetState
submission copies3/successes3. No deliberate reverse user touch or physical input
effect, so input is initialization/reset smoke only, not full new qualification.
Owner-bound liveness715b215 remains unchanged; no needless18h repeat.

Normal A41 Disable07:08:25.5604317Z returns Ready. Final device inspections show
Tablet/A41 helpers0, temporary-secret files0 and dynamic policy address entries0.
S9 and A41 RECORD_AUDIO operations are completed, not running. No global FD-leak
claim or new app-death/forced-priority cleanup proof. All task-owned logcat
collectors are stopped; ADB server and unrelated processes are preserved.
Temporary music volume restored Tablet2/15,S9 15/15 and checked; A41 volume
unchanged. API29 does not implement `cmd media_session volume`; standard
`media volume` successfully restores/reads S9 volume. No further tone is started.
Tablet Wireless Debugging is still0; no manual-restoration answer was received.

## Gates, final acceptance and next boundary

Final gates (`global-quality-health-final-gates-v2.txt`): **PASS**,10m11s.
Exact JVM XML total: **996 tests,160 suites,0 failures,0 errors,0 skipped**.
All tasks: `:app:ktlintCheck`, `:app:lintDebug`,
`:app:testDebugUnitTest --rerun-tasks`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`, `:app:packageDirectShellProbeDex` PASS.
`git diff --check` PASS (line-ending warnings only). No source edits after these
gates. Native production source is not newly modified by this task; existing
native Debug CTest22/22 (3.53s), Release22/22 (4.23s) rerun PASS, covering the
inherited current native worktree/binaries rather than a new native correction.
The preceding full run counted996 JVM tests but failed one new fixture assertion;
that failure is preserved in `global-quality-health-final-gates.txt`. The fixture
returned READY on any artificial reacquisition; the assertion now checks the
actual health snapshot/observer without invoking a fictional reacquisition.
This does not prove a physical helper replacement through a mock registry.
Earlier995-test all-green build and CTest Debug22/22, Release22/22 are preserved.
No native production source is newly edited in this task.

New focused scenarios: IPC timestamp/age boundary and partial-tail identity;
consumer match uses owned Android address, never UID; diagnostic ring-full
does not suppress input; initial priming retains output but new generation does
not; failed helper replacement reports exact prerequisite health and no fallback.

Exact changed test files/cases relative to the990 baseline:

- `DirectShellMicrophoneFrameTest`: freshness boundary/stale/future/zero;
  same enqueue timestamp through decode; consumer ownership by Android address,
  type and silencing, never application UID (3 additional tests).
- `DirectShellMicrophonePlaybackTest`: valid input still forwarded on diagnostic
  ring-full while returning the real DEBUG error (1 additional test).
- `AudioSessionControllerTest`: initial same-stream priming gap retains warm
  output; new generation and stop release it (1 additional test).
- `PrivilegedRuntimeRegistryTest`: failed consumed-helper replacement health
  observer/snapshot carries exact prerequisite and no health-path reacquisition
  (1 additional test). It does not simulate Android speaker/AudioPolicy success.

Current-task source scope: `DirectShellRemoteMicrophonePolicy.java`,
`DirectShellMicrophoneFrame.java`, `DirectShellRemoteMicrophoneSource.kt`,
`DirectShellMicrophonePlayback.kt`, `WarpnectPrivilegedPcmSharedRingProbeServer.java`,
`DirectShellRuntime.kt`, `AudioReceiverSessionController.kt`. Required laboratory
changes are ignored `CadencePrepareBuild.ps1`, `GlobalMicSession.ps1`, and
`GlobalMicConsumerLifecycle.ps1`; logs/APKs/dumps are generated evidence, not
product tooling staged for commit. All other dirty audio/native/source/test/docs
paths from the initial reconciliation remain preserved.

Updated docs: this report; `DirectShellGlobalMicrophoneInjection.md`,
`DirectShellMicrophoneInjectionQualification.md`,
`MicrophoneAudioE2EQualification.md`, `MicrophoneAudioVirtualInputArchitecture.md`,
`CurrentNonRootDebt.md`, `README.md`, `../FutureRoadmap.md`,
`../testing/AndroidCompatibilityMatrix.md`. Historical reports/tokens are not
rewritten as if failures or accepted exact-APK results never happened.

| Host capability | Preserved result | This follow-up |
| --- | --- | --- |
| Tablet global input | GLOBAL_CAPTURE_PRESET_INJECTION_SUPPORTED, MIC/VOICE/CAMCORDER physically functional | Quality candidates PARTIAL; no final3x60s; helper-loss/physical-mic restoration previously validated, not newly requalified |
| A41 global input | GLOBAL_CAPTURE_PRESET_INJECTION_PERMISSION_BLOCKED at local MODIFY_AUDIO_ROUTING precheck | Exact AudioService/Binder enforcement and legitimate alternative research NOT TESTED until Part A passes; no new mechanism |
| S7 global input | GLOBAL_CAPTURE_PRESET_INJECTION_ADAPTER_BLOCKED at API_LT_31_CONTEXT_UNSUPPORTED | Generic pre31 adapter expansion NOT TESTED; no Android8 fundamental impossibility claim |
| Tablet priority | Getter succeeds with empty lists, natural selection works | Setter/clear runtime authorization NOT TESTED; no preferred role is applied, so no new forced-preference cleanup claim |
| A41/S7 priority | Earlier A41 getter SecurityException; S7 context stops earlier | No new priority probe |

No model/manufacturer/brand/device branches are introduced; actual policy audit
contains only capture-preset rules. No additional product per-app/UID/route
configuration after DirectShell prerequisite and Client microphone consent.
Pairing, trust, per-generation secrets/HMAC, owner connections, authentication,
anti-replay, negotiation, Audio Payload V1 and protected network framing are
unchanged. New timestamp travels only on the authenticated local feature IPC.

Overall: **IMPLEMENTED candidate corrections / TESTED software / PARTIAL new
physical validation / NOT QUALIFIED / NOT PRODUCTION-READY**. No portable
alternative or forced-priority extension is claimed. No quality completion token.

Final required3x>=60s MIC campaign: **NOT COMPLETED**. No counts are fabricated
for Sessions2/3 after the interrupted candidate. No candidate meets quality
acceptance merely by improving a counter. Current global quality stays PARTIAL;
A41 exact Binder enforcement/alternatives, S7 generic context expansion and
priority setter/clear authorization are NOT TESTED in this follow-up.

Exactly one next task: **complete the S9 -> Tablet global-input quality gate
after manual Wireless Debugging restoration**, including continuous consumers,
absent/open/close/reopen, helper-loss and final health/publication cleanup.
Only after that passes should the already-planned portability/priority work
resume. SystemAudio causal quality analysis remains separately open, not started.

Full background/power/screen-off/timeout audit stays deferred to RFC-005K or the
appropriate future RFC. HOST_AND_REMOTE, Host display policy, RFC-005J/005K,
physical DirectRoot and broad Bluetooth/USB/headset/app qualification are not
implemented or closed here.
