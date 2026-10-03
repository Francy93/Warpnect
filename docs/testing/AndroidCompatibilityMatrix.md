# Android Compatibility Matrix

## 2026-10-03 Final Global Microphone Quality

[Detailed final evidence](../phase6_5/DirectShellGlobalMicrophoneFinalClosure.md)
supersedes partial status below without erasing failures.

| Host/path | Final result | Remaining boundary |
| --- | --- | --- |
| S9/API29 ->Tablet/API33, C782DD9D r2 | MIC3/3 above121s under one Enable; ordinary72s/reopen28s; steady IPC/track/DEBUG quality deltas0; real997Hz, natural25 input, restoration15/11 | Exact-path quality VALIDATED, not release/universal app qualification |
| Same Tablet, VOICE_COMMUNICATION/CAMCORDER | Fresh14s ordinary consumers: real997Hz/type25, then physical15/11 and15/12 | Natural selection, no UID or forced preference |
| A41/API31, same backend | AudioService recorder-policy registration requires missing MODIFY_AUDIO_ROUTING under UID2000 | GLOBAL_CAPTURE_PRESET_INJECTION_PERMISSION_BLOCKED, no workaround |
| S7/API26 | Product API_LT_31_CONTEXT_UNSUPPORTED; older-context diagnostic reaches routing permission denial | Adapter limitation separate from framework primitive/permission |
| Tablet valid forced priority | Getter empty/authorized; no valid preferred role applied | NOT TESTED for set/clear and abnormal ownership; no priority token |
| Tablet ->S9 SystemAudio control | Technical/lifecycle PASS; last epoch rejected19440/underrun58/xrun0, with earlier counter reset | Separate quality debt, not equal-window improvement or regression proof |

Final gates1022 JVM/163 suites/zero failures/errors/skips, native22/22 Debug and
Release. No model-specific product branches, Root, hidden Shizuku fallback,
release availability promotion or RFC-006J recovery implementation.

## 2026-10-03 Residual Global Microphone Quality

[Current detailed evidence](../phase6_5/MicrophoneReceiverSchedulingQuality.md).
S9/API29 ->Tablet/API33 peer link restored.81F10D24 one-burst DEBUG admission
candidate has stable diagnostics but final cycle1 quality FAIL: first steady
track+3, reopen DEBUG underrun+7/track+13; IPC steady loss0.2/3 NOT RUN.
Ordinary UID-free MIC signal/restoration remain functional.1017 JVM/22+22
native gates pass. No portability/priority/release promotion or new token.

## 2026-10-03 Receiver Scheduling Continuation

[Detailed evidence](../phase6_5/MicrophoneReceiverSchedulingQuality.md) supersedes
current status below without changing prior records or capability boundaries.

| Pair / role | Latest evidence | Classification |
| --- | --- | --- |
| S9/API29 ->Tablet/API33,49D62210 diagnostic | Microphone-only audio-priority worker; measured runnable wait33913->2469ms; first60s and reopened5s steady quality deltas0; real997Hz/type25 then physical15/11 | Preliminary physical diagnostic PASS, not acceptance |
| Same pair, final gated43FB71D6 | One Enable Host; cycle1 loses peer/media then Tablet Wi-Fi/Debug wireless, Host Available withdrawn; policy released;2/3 NOT RUN | Functional milestone retained; quality PARTIAL, cycle1 FAIL |
| A41/API31; S7/API26; Tablet valid priority | No new implementation/forced-role attempt while quality gate remains open | Existing permission/adapter/authorization-only boundaries retained |

## 2026-10-03 Source-Clock Continuation

[Detailed primary evidence](../phase6_5/MicrophoneSourceClockRecovery.md) retains
all previous campaigns and capability boundaries; no device-model branch.

| Pair / role | Latest evidence | Classification |
| --- | --- | --- |
| S9/API29 ->Tablet/API33, D28F6136 | Counted108.943s consumer-window cycle1: recurring first steady DEBUG rejected+9360/underrun+69/xrun0, track+37, IPC+720; reopen pressure worse; Input replacement HMAC fails, Host publication withdrawn | Functional input retained; quality/lifecycle FAIL;2/3 NOT RUN |
| Same pair,388C3A75 extended diagnostic |117.758s Session,90.037s ordinary consumer;80s sampled steady DEBUG/track/IPC deltas0, natural25 then physical15/11, Ready/Waiting | Diagnostic PASS only, not required3x close/reopen acceptance |
| Same continuously enabled diagnostic Host, next attempt | Early lifecycle/media suspension then Wi-Fi reason5, Bluetooth service death, Android adbwifi disable; later ping3/3/TCP marker PASS each way without product changes | Initiator INCONCLUSIVE; lost network/DirectShell prerequisite proven, manual Debug wireless required |
| A41/API31 and S7/API26 | No new workaround/adapter or provider test before quality gate | Prior actual MODIFY_AUDIO_ROUTING registration barrier retained |
| Tablet forced priority | No valid preference set | Authorization-only PARTIAL retained; abnormal rollback NOT TESTED |

Frozen80/70ms DEBUG playout and provisional40ms IPC are distinct. Source pacer
now repays bounded debt based on existing AudioRecord capacity, not added PCM
buffering. No new quality/priority token or universal qualification claim.

## 2026-10-03 Clock Continuity And Framework Boundaries

[Full campaign](../phase6_5/MicrophoneConsumerClockContinuity.md), final66A8B330;
no model-specific product branch or UID rule, unchanged80/70ms DEBUG reserve.

| Pair / role | New evidence | Classification |
| --- | --- | --- |
| S9/API29 ->Tablet/API33 global MIC | Three108.562/108.325/108.292s Sessions under one Enable Host; ordinary MIC open/reopen997Hz, natural25/2031,2052,2073, restored15/11; IPC rejected0/0/0, HWM13/12/14, local write age<40ms | Functional VALIDATED retained; quality PARTIAL: cycle3 continuous DEBUG underflow+13 and AudioTrack0->9 |
| Tablet natural VOICE_COMMUNICATION/CAMCORDER | Separate18s ordinary readers, type25/id2107; RMSmax500.6899/500.8965 and997Hz component706.7688/702.9374; physical15/11 afterward | Functional spot PASS, not quality soak |
| A41/API31 shell policy | MODIFY_AUDIO_ROUTING denied; actual registerAudioPolicy-1; priority methods SecurityException | GLOBAL_CAPTURE_PRESET_INJECTION_PERMISSION_BLOCKED; no workaround |
| S7/API26 shell policy | Legacy ActivityThread shell context and recorder rule reachable; AudioService denies actual registration for uid2000 requiring MODIFY_AUDIO_ROUTING | Current product adapter blocked, followed by proven permission barrier; not framework-primitive absence |
| Tablet priority authorization | Invalid-preset setter-2/clearer1 without SecurityException; all valid preferences empty before/after | Authorization-only PARTIAL; valid forced priority and abnormal rollback NOT TESTED |
| Tablet helper SIGKILL | Owner LOST and publication WITHDRAWN at same local timestamp; all helpers/secrets gone, ordinary consumer25->15 | Health/policy cleanup PASS; no automatic recovery or forced-role cleanup claim |
| Tablet later diagnostic | UI Streaming not observed; later reason5 Wi-Fi loss, Bluetooth signal9, Android adbwifi disable, typed replacement failure, publication withdrawal | External prerequisite lost; early media stall/initiating radio cause INCONCLUSIVE |
| Tablet ->S9 SystemAudio | Bounded technical/lifecycle PASS, last decoded1171440/rejected20160/underrun217/xrun0 | Separate quality debt; not equal-duration comparison or new human REMOTE_ONLY proof |

All requested source gates PASS:1007 JVM/161 suites,0 failures/errors/skips;
native Debug22/22 and Release22/22. Evidence and intermediate failures remain
uncommitted/excluded from checkpoint. No quality/priority token or release promotion.

## 2026-10-02 Tablet-return consumer-transition continuation

[Exact campaign and APK](../phase6_5/MicrophoneConsumerTransitionQuality.md):
858A76A0 final-gate build, unchanged80/70ms DEBUG playout, provisional40ms IPC
freshness-aware FIFO. No model branches or UID criteria.

| Pair / role | New evidence | Classification |
| --- | --- | --- |
| S9/API29 ->Tablet/API33 global MIC | Two complete108.180/108.615s Sessions under one Enable Host, ordinary MIC OFF/997Hz/OFF, natural25/id1860 and1890, restored15/id11, fresh generations. IPC steady rejection/stale deltas0; cycle1 one240-frame reopen rejection; cycle3 interrupted by radio/path loss | PARTIAL; not3/3 accepted, no quality token |
| Tablet platform prerequisite | Repeated locally generated reason5 wlan0 loss, Bluetooth SIGKILL/status9, Android Debug wireless OFF. Publication withdrawn after failed replacement with typed prerequisite | External boundary; radio/kill origin INCONCLUSIVE; no router/OS workaround |
| A41/API31 ->S9/API29 SystemAudio control | Same APK,8s Host tone,20s intended Streaming wait, decoded2118960; rejected69120/underrun789/xrun0; Ready/Waiting and helpers/secrets0 after disable | Technical/lifecycle PASS; separate quality debt, unequal prior windows |
| A41/S7 injection capability and forced priority | No new probe after unclosed gate | Historical permission/context boundaries preserved; priority NOT TESTED |

All seven requested software gates PASS:1004 JVM/161 suites/0 failures/errors/
skips; native Debug/Release22/22 after a preserved local linker-write failure.
The preceding unavailable-Tablet pause and previous campaigns below are historical.

## 2026-10-02 S9 / Tablet peer-link continuation

[Network evidence](../phase6_5/S9TabletPeerLinkRecovery.md): initial IPv4
ping/TCP fail both ways; S9-to-Tablet UDP token arrives but reply fails;
Tablet-to-S9 UDP fails; A41 controls pass. One explicit Tablet rejoin restores
bidirectional ping and all eight TCP/UDP token/echo tests, same IP/MAC/BSSID.
ENVIRONMENTAL_PRECONDITION cleared; exact underlying mechanism INCONCLUSIVE.
IPv6 initial `-I` probes are a local harness error, not packet-loss evidence;
corrected scoped S9-to-Tablet ping3/3. Android turned Debug wireless off on
rejoin, then Tablet USB disappeared; operator confirmed Tablet unavailable and
authorized pausing. No new microphone Session or S22 substitution. Unchanged
87EF556C candidate build PASS; physical consumer-window gate still pending.
New full-gate rerun interrupted before tests; prior998-test result unchanged.

## 2026-10-02 global-input quality candidates

[Exact failed experiments, queues, clocks and gates](../phase6_5/DirectShellGlobalMicrophoneInjectionQuality.md).
This follow-up does not revoke the frozen functional/cadence evidence below.

| Exact Host | New quality evidence | Current qualification |
| --- | --- | --- |
| Tablet/API33 `2023264` | After Wireless Debugging restoration, EBE1B1B2 first counted111.017s cycle fails: unexpected IPC rejection3120/stale2952/HWM16; MIC25/id1460 real997Hz signal and physical15/id11 restoration PASS. Opening-flush and smaller track-threshold experiments failed and were reverted. Subsequent IPv4 peer-link restored by rejoin, as above | PARTIAL; Tablet now unavailable by operator confirmation, primary campaign paused. Consumer-window guard and failed-replacement health branch physically unvalidated |
| A41/API31 `A415FXXS8DXE2` | Same smaller-threshold backend as microphone CLIENT -> Tablet comparison:112.479s, unexpected IPC rejection306720/stale71976, quality FAIL; self-tone clips and is not qualified signal proof. No new Host registration/priority experiment | Prior Host MODIFY_AUDIO_ROUTING denial unchanged; exact Binder-boundary expansion deferred. Client comparison is not evidence of A41 Host injection support |
| S7/API26 | No new context adapter experiment before Part A passes | Prior API_LT_31_CONTEXT_UNSUPPORTED is an adapter limit, not proof of fundamental Android8 impossibility |

UID rules remain NONE; no model/OEM branch, Root, protocol change or ring-size
increase. Forced preference setter/clear remains NOT TESTED. Release unchanged.
Pre-window-guard software gates996 JVM/160 suites/0 failures/errors/skips,
native Debug/Release22/22; newest guard's final software gates pass998 JVM/
160 suites/0 failures/errors/skips, but its physical quality gate remains pending.
Latest restored5145B951 A41->S9 SystemAudio control completes Streaming/Ready/Waiting
with last sink rejected30720/underrun373/xrun0. A partial late acoustic window
has nonzero decoded PCM, not a full quality/frequency/human proof. Earlier27840/
108/0 control remains historical. Video output/release3 and Input START/RESET/
STOP OK are bounded smokes only. Normal Tablet/A41 disable leaves helpers/secrets0.

## 2026-10-02 portable global microphone integration

[Final CF5C311C APK, ordinary consumer and all failed attempts](../phase6_5/DirectShellGlobalMicrophoneInjection.md).
This entry does not replace the accepted cadence APK or earlier scoped probes.
Functional global injection is validated on the exact Tablet build, not universal
Android/release support or lossless input quality.

| Exact Host | Same no-UID capture-preset backend | Ordinary consumer / qualification |
| --- | --- | --- |
| Tablet X700_EEA/API33 `2023264` | GLOBAL_CAPTURE_PRESET_INJECTION_SUPPORTED; recorder mix MIC1/VOICE_COMMUNICATION7/CAMCORDER5, LOOP_BACK; UID criteria NONE | Three fresh protected S9 Sessions with60s ordinary MIC consumers, plus30s VOICE/CAMCORDER: physical997Hz OFF/ON/OFF PASS. Natural type25/id1136,1206,1221,1236; built-in inputs intact. Normal stop/helper loss restore physical type15. Forced priority NOT TESTED, getter authorized only |
| A41 SM-A415F/API31 `A415FXXS8DXE2` | PERMISSION_DENIED at MODIFY_AUDIO_ROUTING | No policy mutation; no OEM workaround; input enumeration unchanged |
| S7 SM-G935F/API26 | API_UNAVAILABLE at existing API_LT_31 shell-context adapter | Not a claim that all API26 framework paths are impossible; no alternate architecture |

Tablet integration candidates preserve explicit warm-start/ingress-blocking,
decoder-reset owner-loss failures and a separate Wi-Fi disconnection that disabled
Wireless Debugging. Session-scoped ownership fixes policy reacquisition on decoder
reset. Final MIC1 DEBUG rejected7920/underrun10/xrun1 (local sink counters),
MIC2/3 zero; IPC startup/no-consumer pressure remains open. Global signal proof
does not qualify lossless quality. Final A41 control normal Host/technical mic
PASS with truthful PERMISSION_DENIED, no policy/IPC and DEBUG counters0; S7
same-source probe stops at API_LT_31 context. Gates990 JVM/0/0/0; native22/22 each.
No forced priority was set. S22 was not required or tested in this follow-up.

## 2026-10-01 autonomous MicrophoneAudio cadence closure

[Complete chronology, failed candidates, local clocks and latency cost](../phase6_5/MicrophoneAudioCadenceRootCause.md).
Final APK SHA256 `4B4B25989EF79D7C2C7FD5E69DFFA515B973F5CC1DC2A2FD1E8EE35538CB071D`,
installed hash verified on both endpoints. S9/API29 `G960FXXUHFVG4` built-in mic
-> Tablet/API33 `2023264` DEBUG SPEAKER sink,48kHz mono5ms Opus64kbit/s.
One normal Enable Host, `SINGLE / DIRECT_SHELL`, no hidden retry or Shizuku.

| Cycle | Sampled Streaming | PCM rejected / underrun / xrun | Kernel max gap | Ring HWM /3840frames | Local PCM residence max | Result |
| --- | ---: | --- | ---: | ---: | ---: | --- |
|1|69.902s|0 /0 /0|40.848ms|3568|74.041ms|Quality/lifecycle PASS |
|2|69.748s|0 /0 /0|41.790ms|3664|76.054ms|Quality/lifecycle PASS |
|3|69.898s|0 /0 /0|45.482ms|3600|74.873ms|Quality/lifecycle PASS |

Startup and approximately55s steady deltas are0. Intentional warm pre-PCM
silence has separate priming counters. Capacity80ms/reserve70ms correct the
old20ms/5ms arrival-tolerance contract; nominal reserve adds65ms. Codec,
pacer, socket and Oboe hardware buffers remain unchanged. Sequence windows
have startup missing0/1/1, then missing/duplicate/reorder0; endpoint totals
are asynchronous, not a loss subtraction. Rare154ms earlier stalls remain
unexplained, beyond the bounded budget. No universal network qualification.

Physical S9 speaker -> built-in mic OFF/ON/OFF RMS about45/3513/41 reaches
Tablet decoded RMS46/3516/41. SPEAKER track consumption is observed, but
final Tablet human audibility **NOT_CONFIRMED**. Each Disconnect restores
Ready/Waiting and rediscovery. Final Disable leaves helpers/secrets0 and
closed sinks. SystemAudio control is technical/lifecycle PASS, not quality
closure: unchanged playback pressure remains separately recorded.
Gates972 JVM/0 failures/errors/skips; native Debug/Release22/22 each.
No Root, framework/HAL change or global AudioPolicy integration. Earlier
failed paced and60/50ms campaigns remain historical below/in the report.

## 2026-10-01 portable microphone ordered gate follow-up

[Exact attempts, local clocks, APKs and gates](../phase6_5/DirectShellMicrophoneInjectionQualification.md).
The earlier sections remain historical, including their failed campaigns.

| Exact boundary | New evidence | Status |
| --- | --- | --- |
| S9/API29 AudioRecord -> synchronous sender | Reads return four240-frame blocks in approximately20ms groups; grouping already precedes Opus/socket | First source boundary PROVEN; total playback cause not closed |
| S9/API29 local paced capture, final E080A820... |142320 physical PCM frames;592 intervals all3-7ms, max5.367ms; RMS27.1424; clean stop/close | Local physical1/1 PASS, not protected E2E quality |
| S9 -> Tablet source diagnostic baseline6180A8BB... | Normal Session/Disconnect PASS; recurring rejection105600 and zero-fill120272 in last Host snapshot | Quality FAIL retained |
| S9 -> Tablet paced attempt6F520EF7... | Control suspension stops mic before steady window; later WLAN disconnect disables wireless ADB | Attempt FAIL/first control cause INCONCLUSIVE; fresh Host setup ENVIRONMENTAL_PRECONDITION |
| Resumed final E080A820... S9 -> Tablet, cycle1 |69.739s sampled Streaming; steady rejected35280, underruns204, xrun0; kernel maximum43.808ms | Lifecycle PASS; quality FAIL |
| Resumed final E080A820... S9 -> Tablet, cycle2 |69.891s sampled Streaming; steady rejected35760, underruns205, xrun0; kernel maximum41.752ms | Lifecycle PASS; quality FAIL |
| Resumed final E080A820... S9 -> Tablet, cycle3 |69.904s sampled Streaming; steady rejected33840, underruns195, xrun0; kernel maximum46.173ms | Lifecycle PASS; quality FAIL |
| Separate resumed Tablet -> S9 SystemAudio control | Capture/send/decode/playback progress; normal Disconnect/Disable. Existing playback pressure remains, including rejected50880/underruns436 at last S9 snapshot | Technical/lifecycle PASS; no zero-loss or new human-audible claim |
| Tablet/API33 global MIC/VOICE_COMMUNICATION/CAMCORDER policy | NOT TESTED: operator restored Wireless Debugging, but ordered quality gate failed3/3 | INCONCLUSIVE; previous scoped evidence is not global proof |
| A41/API31 global path | NOT TESTED; restored Wireless Debugging verified1. Prior shell routing-permission denial unchanged as historical exact-key evidence | Registration prerequisite previously PERMISSION_BLOCKED; no workaround |
| S7/API26 / S22/API36 | No new microphone qualification | NOT TESTED; no physical Root or API36 inference |

No ring, Opus duration/bitrate, socket buffer or Oboe priority/priming change.
No successful global-policy, priority, coexistence or fallback campaign yet.
Software gates964 JVM/0 failures/errors/skips, native Debug/Release22/22 each.

## 2026-10-01 microphone cadence / first-class input investigation

[Exact clocks, attempts, counters and cleanup](../phase6_5/MicrophoneAudioCadenceAndVirtualInput.md).
The earlier reports below remain historical. This is not new production
MicrophoneAudio device support or a universal API/OEM statement.

| Exact path | Current evidence | Classification |
| --- | --- | --- |
| S9/API29 microphone -> A41/API31 DEBUG sink | Normal protected baseline progressed; operator heard S9 audio on A41 | HUMAN AUDIBLE CONFIRMED on DEBUG path; quality still fails |
| S9/API29 microphone -> Tablet/API33 DEBUG sink | Baseline and local kernel/read/authentication diagnostics progress; additional bursts exist at kernel ingress | Technical E2E/lifecycle separate from recurring ring rejection/zero-fill; root cause INCONCLUSIVE |
| Tablet/API33 `2023264`, UID2000 | Four tested audio permissions granted; AudioPolicy register0; dynamic submix is actually enumerated alongside bottom/back mic | SCOPED_AUDIOPOLICY_INJECTION_ONLY for tested path |
| Same Tablet ordinary consumer, unmatched UID | Preferred dynamic input accepted as a preference, but getInputForAttr=-1/start fails/read=-3 | PLATFORM_CAPABILITY_LIMIT; no first-class usable input PASS |
| Same Tablet matching-UID control | Normal AudioRecord receives generated1193 Hz, RMS1413.67-1413.84; unregister restores bottom mic | Scoped framework control, not protected remote microphone product integration |
| A41/API31 `A415FXXS8DXE2`, UID2000 | MODIFY_AUDIO_ROUTING denied, empty policy register-1; other three tested permissions granted | REQUIRES_DIRECTROOT_OR_STRONGER_PRIVILEGE at registration prerequisite, not proof that Root solves product input |
| S22/API36 | NOT TESTED in this task | Prior scoped and SystemAudio evidence unchanged |
| S7/API26 | Connected but not used/unlocked | No new qualification |

Final APK and three separately counted >=60-second Sessions are detailed in
the current report. No buffer or protocol change; owner-bound DirectShell is
unchanged. Software gates959 JVM/0 failures/errors/skips and native Debug/Release
22/22 each. New diagnostics/feasibility work remains uncommitted.

## 2026-09-30 virtual-input feasibility / DEBUG playback follow-up

[Exact experiment and final-APK campaign](../phase6_5/MicrophoneAudioVirtualInputArchitecture.md).
Production intent is Android Host virtual microphone input; speaker playback
is DEBUG-only. These results do not upgrade physical remote-mic product support.

| Exact target / path | Result | Boundary |
| --- | --- | --- |
| A41/API31, `A415FXXS8DXE2`, shell UID2000 | `MODIFY_AUDIO_ROUTING=false`, empty AudioPolicy register=-1 | Input-policy prerequisite unavailable on this build; SystemAudio capture remains a separate proven capability |
| S22/API36, `S901BXXSOGZH3`, shell UID2000 -> normal consumer UID10384 | Corrected scoped injector wrote477192 frames; standard AudioRecord received 1193 Hz with RMS1413.67-1413.84; after unregister it returned to physical mic | UID-scoped rerouting experiment only; no protected Client acoustic -> virtual Host input path or arbitrary-app selectable-device qualification |
| S9/API29 microphone -> A41/API31 DEBUG playback | Final APK `64948EA3...`: 3/3 normal technical/lifecycle Sessions under one Host enable; AudioFlinger output SPEAKER | Quality FAIL in each 65-second steady window; real PCM rejected and zero-fill. Human audible result NOT CONFIRMED |
| A41/API31 SystemAudio -> S9/API29 control | Same final APK, normal Session, eight-second source OFF/ON/OFF observed in decoded PCM; Disconnect/Disable clean | Technical regression only; playback pressure remains, no new human routing or zero-loss claim |
| Tablet/API33 | NOT TESTED / disconnected for this follow-up | Earlier idle, microphone/earpiece and SystemAudio evidence retained |
| S7/API26 | Connected but credential-locked, not used | No unlock bypass or new microphone qualification |

S22 became available and was tested in this follow-up; older task-specific
unavailability statements remain historical. Liveness checkpoint `715b215`
retains the A41 almost18-hour and Tablet >18-minute evidence. The first scoped
S22 attempt failed real attribution validation and remains FAIL; only the
later corrected DEBUG fixture experiment passed. No Root, model allowlist,
framework/vendor change or system-wide physical-microphone replacement.
Final gates:954 JVM/0 failures/errors/skips; native Debug and Release22/22 each.

## 2026-09-30 owner liveness and microphone quality follow-up

[Exact artifacts, anomalies and counters](../phase6_5/DirectShellIdleLivenessAndAudioPlaybackQuality.md).
These are non-Root physical controls, not a universal API/model qualification.

| Target / path | Observed result | Boundary / limitation |
| --- | --- | --- |
| A41/API31 DirectShell Host -> S9/API29 | Same app/helper PIDs across almost 18 hours idle; first normal post-idle Session Streaming; Disconnect restored waiting | Liveness APK `3B6EBB...`; helper-loss publication withdrawal and app-death cleanup also tested separately |
| Tablet/API33 DirectShell Host -> S9/API29 | Same app/helper PIDs across approximately 18 minutes idle; first normal post-idle Session Streaming; normal disable left helpers/secrets zero | Same liveness APK; Video/SystemAudio startup and Input preparation/lifecycle, not a new human Input proof or zero-loss audio claim |
| S9/API29 microphone -> Tablet/API33 | Physical capture, Opus, protected receiver and Oboe consumption progressed; two later human controls reported Tablet silent | Persistent steady-state ring rejection/zero-fill; physical output NOT CONFIRMED; actual track EARPIECE |
| A41/API31 microphone -> same Tablet/API33 | Same diagnostic APK/configuration; decoded PCM progressed with additional resets and burst delivery; normal Disconnect restored waiting | Quality PARTIAL; no human listening answer received before the operator became unavailable |
| S9 built-in speaker -> acoustic A41 microphone -> protected Tablet receiver | Eight-second fixture: A41 ambient RMS about 55-59, ON window 637, OFF about 54; decoded ON RMS about 650-657 versus ambient about 60-90 | Acoustic capture/transport control only; not digital injection, synchronized latency, exact spectral correlation or proof of Tablet speaker emission |
| S7/API26 source candidate | Connected but credential-locked; not used for the acoustic fixture | No unlock bypass or manual intervention; S9 used instead |
| S22/API36 | Disconnected in the final acoustic control | Historical accepted evidence preserved; no dependency on S22 |

No microphone source/format, ring capacity, priming, output route or protocol
was changed to obtain these results. Source volume was temporarily moderated
and restored. Later software gates passed 950 JVM tests on a distinct final
diagnostic rebuild; the report records its hash and physical-test boundary.

## 2026-09-29 MicrophoneAudio Client-to-Host technical E2E

[Detailed report](../phase6_5/MicrophoneAudioE2EQualification.md).
The three-cycle DEBUG APK SHA-256 was
`243AB3026EEF21A1C06A38A9A62B4ABEF30F59AC9003E1DFFEB497A932C3E5D5`;
the full-gate rebuild SHA-256 was
`B4D186D836428FE7C03808703C814A8589827E87ED56BA04C353D19CE1A2A376`
and passed a separate 1/1 normal microphone technical Session.

| Client / Host | Physical capture | Protected remote pipeline | Human Host audibility | Lifecycle |
| --- | --- | --- | --- | --- |
| S9 SM-G960F/API29 -> Tablet X700_EEA/API33 | Built-in mic, VOICE_RECOGNITION, 48 kHz mono PCM16; local numeric probe 2/2 | 3/3 normal MicrophoneAudio Sessions plus 1/1 final APK: Client Opus/datagrams, Host receive/decode/Oboe consumption | NOT CONFIRMED | 3/3 plus 1/1 normal Disconnect to Client Ready/Host Waiting; restart and abnormal Client loss control passed |

MicrophoneAudio is separate from the Complete Host Video/SystemAudio/Input set.
Its physical speaker audibility and other input/output routes are not qualified.
During a later mic-disabled SystemAudio control after hours of Host idle, the
Tablet still advertised Host Available with zero DirectShell helpers and setup
failed `ExactAudioConfigurationUnavailable`; this is retained as a distinct
pre-existing Host liveness defect. Normal Disable/Enable Host restored the
three helpers and SystemAudio Streaming in one comparison run. This does not
erase the failed first attempt or imply a microphone defect. Nonzero Oboe
playback rejection/underrun counters are recorded in the focused report.

This matrix records bounded physical-device observations. It is not a guarantee for every device with the same Android version, SoC, or model family.

## 2026-09-28 post-Disconnect Host lifecycle

[Detailed Phase 6.5 report](../phase6_5/PostDisconnectHostLifecycleReliability.md).
The final APK was `A3FD45535FD1C74417A11C6E43EED979198ED816B32245519EC1D6928D3DA884`.

| Host / Client | Normal Session under one Host enable | After Disconnect | Scope |
| --- | --- | --- | --- |
| A41 SM-A415F/API31 -> S9/API29 | 10/10 discovery, Streaming, Disconnect, repeat; `SINGLE / DIRECT_SHELL` | 10/10 A41 `Waiting for clients`, S9 `Ready`; no Host toggle | Final APK, normal Disconnect, no tone |
| A41 SM-A415F/API31 -> S7/API26 | 1/1 alternate-Client discovery and Streaming after the S9 campaign | A41 returned to waiting | Technical Session, no visible/audible claim |
| A41 SM-A415F/API31 -> S9/API29 after Host app restart | 2/2 Session/Disconnect under a new Host enable | Waiting restored; Disable Host left zero feature helpers/secrets | Separate restart control |
| Tablet/API33 and S22/API36 | NOT TESTED in this campaign | Earlier stale-Running observations remain historical | Devices unavailable |

The prior APK's A41 cycle 5 failed at Host `InputConfigurationUnavailable`
because a fragmented `pid=` self-ADB launch response was parsed before its
newline. That failed attempt was retained, fixed, and excluded from the new
final-APK count; it is not a pass. No Root or Shizuku fallback was selected.

## 2026-09-29 Tablet post-Disconnect regression gate

The same final APK passed Tablet X700_EEA/API33 -> S9/API29 `5/5` normal
discovery, Streaming, Disconnect, S9 `Ready`, Tablet `Waiting for clients`,
and rediscovery under one Host enable. DirectShell supplied Input,
SystemAudio and Video; no Shizuku producer was active. Normal Disable Host
left zero matching feature helpers and temporary bootstrap secret files.
An earlier exploratory Tablet check falsely failed by observing `Running`
before the bounded, serial feature-server teardown finished; the later
`host_registration_active` and waiting state were observed. That check is
classified as a harness timing defect, not folded into the counted `5/5`.
S22 was not tested in this gate.

## 2026-09-28 normal-Session SystemAudio routing

This records Host-speaker behavior separately from capture and Client playback.
[Detailed routing report](../phase6_5/SystemAudioRoutingSemantics.md).

| Host / Client | Normal Session tone | After Disconnect | Local-output classification |
| --- | --- | --- | --- |
| Tablet X700_EEA/API33 -> S9/API29 | 3/3 operator-confirmed S9 only; Host `STREAM_MUSIC` route `remote_submix` | 3/3 operator-confirmed Tablet only; route `speaker` | `REMOTE_ONLY` in tested Sessions, local restoration PASS |
| A41 SM-A415F/API31 -> S9/API29 | 2/2 returned listening reports S9 only; a third technical Session had `remote_submix` and real captured PCM but no listening answer | 2/2 normal Disconnect reports A41 only; third Client force-stop returned route to `speaker` in bounded observation | `REMOTE_ONLY` for human-confirmed Sessions; abnormal route restoration observed |
| S22 SM-S901B/API36 -> S9/API29 | 3/3 operator-confirmed S9 only; Host `STREAM_MUSIC` route `remote_submix`, three DirectShell source generations with `shizuku_audio_producer=false`, real ring PCM and Client playback | 3/3 operator-confirmed S22 only; route `speaker` | `REMOTE_ONLY` in tested Sessions, local restoration PASS |

The separate older A41 standalone-probe report observed local speaker playback
during capture. It is not overwritten or promoted to a normal-Session promise.
The A41 first admission attempt in this task failed before audio because
`_adb-tls-connect` was absent; the operator's manual Wireless Debugging
restart restored publication. No Shizuku audio producer was selected in the
counted DirectShell Sessions. S22 first local baseline at volume 3/15 was not
audible; at 7/15 it was audible before Host activation, and 3/15 was restored
after the campaign. This routing result concerns built-in speakers, not
Bluetooth, wired or USB playback.

## 2026-09-28 rendered-frame observability qualification

This is a measurement-only addendum; it does not replace older visible-video
or Client compatibility decisions. [Detailed Phase 6.5 report](../phase6_5/LegacyRenderedFrameObservability.md).
Older rows that say "rendered" record their original decoder/callback milestone
or separate human-visible proof; they must not be read as millisecond-accurate
physical-display timing.

| Target | Video observation | Interpretation |
| --- | --- | --- |
| S7/API26, `OMX.Exynos.avc.dec` | Local fixture: 64/64 PTS-matched release/callback observations, median 87.7 ms to callback receipt; 63 PTS-matched ADB SurfaceFlinger rows | Fixture visible; callback and layer rows are diagnostics, not photon timestamps. New remote Session not completed because SAS confirmation timed out. |
| S9/API29, `OMX.Exynos.avc.dec` | Local fixture: 64/64, median 85.1 ms; Tablet-hosted protected Session: 64/64 bounded PTS sample, median 281 ms, p95 57.45 s, max 70.705 s from release to callback receipt | Host UI visibly streamed; sparse-content callback delay is not display delay. SurfaceFlinger layer rows are ADB-only diagnostics. |
| A41/API31, `c2.mtk.avc.decoder` | Local fixture: 64/64; callback `nanoTime` = PTS ns on the bounded sample | Supplied callback time is not qualified as local MONOTONIC, even though callback delivery itself was prompt. |
| Tablet/API33 | DirectShell Host for S9 remote sample; no Tablet Client callback campaign | Host role only here. |
| S22/API36 | NOT TESTED IN THIS TASK - temporarily unavailable | Prior accepted S22 results are unchanged. |


## Batch 1: Modern Host and Legacy Clients

Test artifact: debug APK built from `99b83049671f43a026e8f446f005c944b996368c`, SHA-256 `768550E2DA5784218AF0DCE71F212ACB0F468283B637A0352D44C8DC11CF4E60`, ABI set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`.

| Device class | Android/API | ABI | Tested role | Install / launch | Client video result | Input | Capture | Encoder / CBR diagnostic | Classification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | arm64-v8a | Reference Host | PASS | Host authenticated, negotiated, captured, encoded, and sent real video for each Client trace | Available for Host | DisplayManager strategy previously production-validated | Strict-CBR production qualification previously PASS | Reference modern Host |
| Samsung SM-G960F | Android 10 / API 29 | arm64-v8a | Client | PASS | S22 Host to Client reached decoder start, remote decode, and first real rendered frame | Not required for Client; reported `NotApplicable` | Not tested | Safe exact-format probe reported supported; not a Host qualification | `CLIENT_SUPPORTED` |
| Samsung SM-G935F | Android 8.0 / API 26 | arm64-v8a | Client | PASS | Secure authentication passed, then local Client video capability was unavailable before WNSN: `HardwareClassificationUnavailable` | Not required for Client; reported `NotApplicable` | Not tested | Not required for Client; no Host qualification performed | `CLIENT_UNSUPPORTED_HARDWARE_DECODER_CLASSIFICATION_UNAVAILABLE` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | arm64-v8a | Client | PASS | S22 Host to Client reached protected receive, decoder start, remote decode, and first real rendered frame | Not required for Client; reported `NotApplicable` | Not tested | Safe exact-format probe reported supported; not a Host qualification | `CLIENT_SUPPORTED` |

The tablet was landscape during the trace. Its 16:9 render surface occupied the available height, so the Compose status label was not exposed to the UI-automation tree. The production decoder nevertheless emitted the first-rendered-frame event. This is recorded as a layout-observability limitation, not as a media failure or a latency measurement.

All remote-frame results above used the production Session, protected UDP, decoder, and render surface. No screenshots, pixel readback, local loopback frames, or captured media were used as proof.

## RFC-002I Production Validation

Test artifact: debug APK from `3dce8cca8e9d9717386524d0d2a727485834635d`, SHA-256
`E49F01173420238D4C0B49236A45EE539D98CF12B7A245C9E374AF3E3231B31C`, 28,887,903 bytes,
ABI set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`. The only tracked
media fixture is the RFC-002I qualification asset; no capture output, device log, screenshot, or
device identifier is recorded here.

| Device class | Android/API | Tested role | Decoder qualification | Remote render | Surface recreation | Classification |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | Reference Host / modern Client check | Framework hardware path; no `:decoderProbe` | Reference Host sent real production video in each trace | Not required | `REFERENCE_MODERN_DEVICE` |
| Samsung SM-G960F | Android 10 / API 29 | Client | Framework hardware path; no `:decoderProbe` | S22 Host to Client reached `FIRST_REAL_RENDERED_FRAME_ON_CLIENT` | Not required | `CLIENT_SUPPORTED` |
| Samsung SM-G935F | Android 8.0 / API 26 | Client | `OMX.Exynos.avc.dec` passed same-UID full-envelope active qualification; exact-key cache reused in-process and after restart | S22 Host to Client reached `FIRST_REAL_RENDERED_FRAME_ON_CLIENT` | Not required | `CLIENT_SUPPORTED_ACTIVE_QUALIFICATION` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | Client | Framework hardware path | S22 Host to Client reached `FIRST_REAL_RENDERED_FRAME_ON_CLIENT` | Real generation 1 destruction, generation 2 preparation, and remote frame presentation on generation 2 | `CLIENT_SUPPORTED` |

These are device/build observations, not support claims for all API 26, Exynos, MediaTek, or tablet
devices. The S7 was admitted because its own exact static and active qualification passed; the known
software-family `OMX.google.h264.decoder` remains unavailable as a production fallback.

## Client Visual Presentation Investigation

Debug APK from `2ffddbe`, SHA-256
`F695FD9AD8E6E636DD5DCCC0B0FB45D5F96C6F9C93D3269A810DB4D41EB410ED`, 28,953,663 bytes,
ABI set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`.

The Client decoder released valid frames to the active Surface while the default `SurfaceView` media
layer remained behind the opaque Compose window buffer on the affected Samsung UI composition. The
production view now calls `setZOrderOnTop(true)`. This is a local Android presentation change; it
does not alter video payloads, decoder qualification, capture, or Session/protocol semantics.

| Device class | Android/API | Production remote result | Local composition control | Current classification |
| --- | --- | --- | --- | --- |
| Samsung SM-G935F | Android 8.0 / API 26 | A41 API 31 Host reached authenticated, committed media setup, decoder output, immediate release, and an active SurfaceFlinger buffer. A human then clearly saw the current A41 screen in the Client video surface. | Same `SurfaceView` and decoder path visibly presented the immutable AVC fixture. | `VISIBLE_REMOTE_VIDEO_PASS` |
| Samsung SM-G960F | Android 10 / API 29 | A41 API 31 Host-to-Client completed authentication, WNCP, setup, receiver startup, AVC configuration, decoder startup, first access-unit decode, and output release to the current Surface. The user then physically confirmed visible remote video, including the Warpnect Host screen, Android Home, and ordinary Host applications. | Same `SurfaceView` visibly presented the immutable AVC fixture through `OMX.Exynos.avc.dec`. | `S9_PRODUCTION_VIDEO_PIPELINE_VALIDATED` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | Tablet Host-to-S9 Client reached authentication, WNCP, setup, capture, encoder output, protected video transport, Client decode, and output release in five consecutive first-attempt Sessions. A supplementary screenshot showed the current Tablet Host display in the S9 Client surface. | Same `SurfaceView` visibly presented the immutable AVC fixture through `c2.mtk.avc.decoder`. | `TABLET_REMOTE_SESSION_VALIDATED` |

The local fixture is a bounded debug-only composition control and is not substituted for a protected
remote Session result. The tablet screenshot supplements the protected-session milestones and Client
output-release evidence; the S7 remote result above additionally includes explicit human confirmation.

## Host Full-Display Capture Scope

On A41 Android 12/API 31, the legacy privileged capture backend selected the physical logical display
with `source_display_id=0` and `layer_stack=0`, mirrored it into the app-owned encoder Surface, and
remained active after the Warpnect Activity left foreground. In one protected A41 Host-to-S7 Client
Session, the Client visibly followed the Host through Warpnect, Android Home, Settings, and the
notification shade. The user directly confirmed the Settings presentation on the S7.

| Host | Client | Capture backend | Verified display states | Current classification |
| --- | --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 | Samsung SM-G935F, Android 8.0 / API 26 | `SurfaceControlDisplayCaptureApi`, legacy privileged display mirror | Warpnect, Home, Settings, notification shade | `HOST_FULL_DISPLAY_CAPTURE_VALIDATED` |

This is display-scope evidence for the tested device/runtime, not a claim that secure or protected Android
content is capturable. The former growing-latency and tablet remote-session observations are recorded below
with their resolved boundaries; they do not alter this capture-scope evidence.

## Privileged UserService Lifecycle and Cleanup

The A41/API 31 Host uses three non-daemon, shell-UID Shizuku UserServices: `capture`, `audio`, and
`input-injection`. They are `EXPECTED_BOUNDED`: a Session or cold capability query owns each connection,
and no helper is retained after its final owner releases it. The main `io.warpnect` process and the
external Shizuku server are independently `EXPECTED_PERSISTENT` while their respective application/runtime
is alive. The same-UID `codecProbe` is an independent bounded qualification process, not a Shizuku helper.

The prior accumulation was a production ownership defect. Shizuku unbind disconnects a UserService but does
not itself terminate its process, so each Warpnect helper now exposes the reserved UserService destroy
transaction, stops/resets its local resource, and exits after the gateway's final unbind. The SystemAudio
capability query also now closes its temporary controller in `finally`. The final validation APK was
`2AA9C9C0AC0B8A05BC286905846087CA873880868D96A08A0566DDD99B0FABEB`, 28,953,663 bytes, with ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`.

| Host / Client | Exercise | Helpers after normal teardown | Result |
| --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G935F, Android 8.0 / API 26 | Four protected media Sessions, including one Host Home/background/return interval | capture=0, audio=0, input=0 | `PRIVILEGED_USERSERVICE_CLEANUP_VALIDATED` |
| Same pair | Session/UI/authentication attempts aborting before privileged acquisition | capture=0, audio=0, input=0 | No partial-acquisition retention observed |

One repeated harness batch initially left the Client UI in `Streaming` because its real `Disconnect` control
was below the visible `ScrollView` viewport and was not tapped. The harness now performs one bounded viewport
search before declaring that action absent; this is a harness cleanup correction, not a change to Session or
SAS semantics. A post-`Disconnect` Client coordinator is terminal by current design, so a same-process
reconnect attempt is not used as a cold-capability result; it did not acquire privileged helpers or cause
count growth.

## Cold Capability Preparation and Session Negotiation

The prior cold-start failure was a local readiness ordering defect. The Host collected its required capability
snapshot synchronously only after receiving the Client WNCP offer, while the Client WNCP window had already
started. On a cold A41, strict-CBR qualification and privileged capability checks could therefore consume the
remote peer's negotiation budget. `PreparedHostCapabilityCollector` now completes the required Host snapshot
on the existing session-control scheduler before Host discovery advertisement. The responder reuses that exact
prepared snapshot when it later receives the Client offer. No WNCP timeout, qualification threshold, codec
profile, cache key, or wire state changed.

The final validation APK was
`0A78B3F3D61FAAB5F0788A0A17163B029F267652D195C682FCAD6FB222DF3D94`, 28,953,707 bytes, with ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`.

| Host / Client | Qualification state | Negotiation evidence | Result |
| --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G935F, Android 8.0 / API 26 | Host strict-CBR cache miss; Client exact RFC-002I decoder cache miss | Host capability preflight completed before advertisement (4,613 ms total; video component 1,014 ms). Client active decoder qualification completed before its offer (6,602 ms). After the offer, Host selection took 13 ms in the Host local clock and arrived after 38 ms in the Client local clock. | First cold Session authenticated, completed setup, and started media without a retry: `COLD_CAPABILITY_NEGOTIATION_VALIDATED` |
| Same pair | Host and Client exact qualification cache hits; Host app process retained and Client app process restarted | Host selection took 2 ms after the offer in the Host local clock; Client received it after 11 ms in its local clock. | Warm cache regression passed; legacy rendered-frame callback timing remains a separate observability concern. |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G960F, Android 10 / API 29 | Host readiness preflight; Client framework decoder classification | WNCP, setup, decoder startup, first access-unit decode, and Surface release completed. The user physically confirmed the A41 Host screen, Home, and ordinary Host applications on the S9. | `S9_PRODUCTION_VIDEO_PIPELINE_VALIDATED` |

The debug-only cold control invalidates only the exact RFC-002I decoder-qualification cache key; it does not
clear pairing, trust, session security, or unrelated capability state. The primary cold/cold validation did
not reboot either device, restart Shizuku, clear app data, or hide a warm retry.

## Persistent Exact Encoder Qualification Cache

RFC-002B active strict-CBR encoder qualification now persists only a typed `Supported` result in
app-private storage. The persisted record is addressed by a SHA-256 of the complete compatibility
key: qualification algorithm version, probe workload version, target profile version, codec name,
MIME, width, height, frame rate, bitrate, bitrate mode, I-frame interval, Build fingerprint, and
media-runtime compatibility version. An application version is intentionally not part of that key;
an explicit qualification/profile version changes when qualification semantics change.

`Unsupported`, timeouts, probe-process death, service failures, and other transient probe failures
remain current-process results only. The existing current-process probe-death quarantine is unchanged.
Malformed, missing, or incompatible persisted records are exact-key misses and run normal qualification;
they are never interpreted as support.

| A41 API 31 action | Exact-key cache result | Active `:codecProbe` work | Encoder capability collection |
| --- | --- | --- | --- |
| First normal Host enable with no record | miss | 2 eligible encoder probes, both `Supported` | 988 ms |
| New process, normal Host enable | 2 persistent hits | 0 probes | 100 ms |
| Second new process, normal Host enable | 2 persistent hits | 0 probes | 108 ms |
| `am force-stop` then normal relaunch | 2 persistent hits | 0 probes | 107 ms |
| `adb install -r` preserving data then normal Host enable | 2 persistent hits | 0 probes | 122 ms |

The final APK for this validation was SHA-256
`CBB4B4ED0589C02A4789192D35584FFC501D31301EE7223893C461B13BCFFCB5`, 28,953,707 bytes,
with ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64`. A normal A41 API 31 Host-to-S9 API 29
Session after a persistent hit completed authentication, WNCP, setup, decoder startup, first access
unit, and first decoded frame. After normal teardown, no `capture`, `audio`, or `input-injection`
Shizuku UserService remained.

## Session Establishment and Restart Reliability

The user-observed A41 API 31 Host-to-S9 API 29 first-attempt connection defect was reproduced with
the prior validated APK without clearing data, restarting Shizuku, rebooting, or force-stopping either
application. Two independent normal UI attempts discovered and accepted the Host presence, but did not
start pairing or authentication. The Client UI retained a Host row and displayed `Ready`, even though
the underlying Client coordinator had already reached its terminal `Closed` state.

The first failed boundary was `CLIENT_COORDINATOR_TERMINAL_STATE_REUSED`. Before the correction,
`SecureSessionCoordinator.disconnect()` left the application-scoped Client coordinator `Closed` after
normal attempt cancellation. Discovery could continue to refresh the old UI model, but a later
`SecureSessionApplicationController.connect()` correctly rejected the terminal coordinator as busy.
The UI did not surface that rejection. A related ownership defect was also found:
`ControllerBackedClientSessionPhaseDriver.closeAttempt(keepDiscovery = true)` closed its
`SessionProtectionController` but retained that terminal controller for a later authenticated attempt.
This is classified as `RETRY_REUSES_CANCELLED_RUNTIME_OWNER`.

The production correction returns the Client coordinator to reusable `Idle` on normal disconnect while
still invalidating the completed attempt token, and gives a discovery-preserving subsequent attempt a
fresh `SessionProtectionController`. Per-attempt state remains new; discovery, SAS, authentication,
WNCP, WNSN, payload formats, and transport semantics are unchanged. This is not an automatic retry.

The final reliability candidate was SHA-256
`6A69E3F04F265D138A50D917D941967D8B13B55BC8BED79B8292DA27D77614A7`, 28,953,707 bytes,
with ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64`, built from `79078df`. The same artifact was
installed on all devices in the validation rows below.

| A41 API 31 Host / Client scenario | First-attempt result | Evidence |
| --- | --- | --- |
| S9 API 29, ten consecutive normal enable/discover/connect/disconnect cycles | 10 / 10 PASS | Each cycle authenticated, completed capabilities, setup, video-channel readiness, media start, first encoded and received video datagrams, decoder start, first access-unit submission, and first decoded frame. Each ended through the product Disconnect/Host-disable path before the next cycle. |
| S9 API 29, Client process restart only | PASS | App data and Host process were preserved; one normal Connect reached media on its first attempt. |
| S9 API 29, Host process restart only | PASS | App data and Client process were preserved; the Host restored the RFC-002B persistent exact-key result and one normal Connect reached media on its first attempt. |
| S9 API 29, both application processes restarted | PASS | App data and Shizuku were preserved; one normal Connect reached media on its first attempt. The Host log recorded `encoder_cbr_persistent_cache_hit` and no active `:codecProbe`. |
| S7 API 26 regression control | PASS | Authentication, capabilities, setup, media start, decoder start, and first decoded frame passed. The legacy `OnFrameRendered` callback remains a separate observability signal and is not used as a Session-success gate. |

After final semantic teardown, A41, S9, and S7 each reported zero `capture`, `audio`,
`input-injection`, `codecProbe`, and `decoderProbe` processes. The lifecycle cleanup invariant remains
intact. `SESSION_ESTABLISHMENT_RESTART_RELIABILITY_VALIDATED` applies to the tested A41 API 31-to-S9
API 29 topology. The earlier tablet “pre-authentication blocker” label was superseded by the later
Tablet Host-to-S9 investigation; its validated result is recorded below. The later growing-streaming-latency
investigation is recorded below.

## Growing Streaming Latency / Backpressure

The user-reported A41 API 31 Host-to-S9 API 29 progressively stale stream was reproduced with a
changing Host display. The first failed boundary was
`VIDEO_RESYNC_CONTROL_NOT_PUMPED_BY_PRODUCTION_SENDER`, not an accumulating encoder, transport,
reassembly, decoder, or Surface queue. After a receiver discontinuity, the Client correctly entered
`WaitingForKeyFrame` and emitted Video Resync Control V1 requests. The Host continued encoding and
sending, but the Android production binding left `DefaultVideoTransmitterSessionController` on its
default no-op sender-control runtime. It therefore never pumped the already-negotiated control channel
or forwarded the request to `MediaCodec.requestKeyFrame()`. The last visible frame consequently became
progressively stale while fresh non-key frames could not be safely delivered.

The production binding now installs `NativeVideoSenderControlRuntime`. It pumps the existing V1 control
channel and asks the active encoder for an IDR on a real resync request. No video profile, frame rate,
bitrate, payload, FEC, transport, or decoder-selection behavior changed. A focused unit test covers
sender-control start and final stop with the video pipeline. Bounded debug-only five-second snapshots
are retained for future local diagnostics; they record counts and local PTS only, never frame content,
peer addresses, or cross-device latency.

| Stage | Queue / buffer | Bound / policy | Final sustained evidence |
| --- | --- | --- | --- |
| Encoder output to packetizer | Synchronous encoder sink | No retained application AU queue | Host encoded and submitted 2,148 (S9) / 2,151 (S7) AUs without failed submission. |
| Native video reassembly | Reassembly slots | 8 slots, 50 ms expiry | Final occupancy 0; high-water 1; no timeout or full-window event. |
| Native ready AUs | Ready slots | 8 slots, ordered bounded delivery | Final occupancy 0; high-water 1; no full-window event. |
| Decoder to surface | MediaCodec output with immediate release | `releaseOutputBuffer(index, true)` | Decoder output/release remained within 2-5 AUs of input and continued through teardown. |

The final debug APK was SHA-256
`B9E834D546BD673F4A1E333B45CB303D808C260696CC6C28A7206EBC64BEB1E7`, 29,235,744 bytes,
with ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64` (`minSdk 26`, `targetSdk 35`). Both runs used the
same artifact, one normal authenticated Session, and harmless Host display pulses every two seconds;
they did not use a restart, reconnect, quality reduction, or frame-dropping workaround.

| Host / Client | Duration | Control recovery | Queue / freshness result | Classification |
| --- | --- | --- | --- | --- |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G960F, Android 10 / API 29 | 180 s; 61 Host pulses | 12 resync requests received, 11 keyframe requests forwarded, zero control errors | Client remained `Streaming` after startup. Reassembly and ready high-water were 1; final decoder queued/output/released were 2,142 / 2,139 / 2,139. The local input-output PTS delta was 50 ms at the final sample and did not grow monotonically. | `GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` |
| Samsung SM-A415F, Android 12 / API 31 / Samsung SM-G935F, Android 8.0 / API 26 | 180 s; 62 Host pulses | 16 resync requests received, 14 keyframe requests forwarded, zero control errors | Client remained `Streaming` after startup. Reassembly and ready high-water were 1; final decoder queued/output/released were 2,166 / 2,164 / 2,164. The final local input-output PTS delta was 33 ms. | `GROWING_STREAMING_LATENCY_BACKPRESSURE_VALIDATED` |

This proves the tested stream does not retain stale work unboundedly after recovery. It does not claim a
cross-device absolute latency value because Warpnect has no accepted clock-synchronization provenance.
The historical delayed/batched legacy `OnFrameRendered` callback remains an observability limitation,
not a freshness or Session-success gate. `GROWING_STREAMING_LATENCY` is closed as a correctness debt;
residual fixed latency and broader throughput tuning remain Phase 7 concerns.

## Privileged Input Compatibility Investigation

Test-only investigation artifact: debug APK on branch `investigate/a41-privileged-input` from base
`174b7047cd2725fd587601179991b624548c3b37` plus debug-only diagnostics, SHA-256
`BABD7CC8C57020440065C6C3E2D9053F407B2E677EB95A30298E28031956A7C2`, 28,983,833 bytes, ABI
set `arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk 26`, `targetSdk 35`. The diagnostics bind a
normal Shizuku UserService and inject only F1, touch, pointer-hover, and joystick-motion events into
a foreground Warpnect-owned debug Activity. They do not exercise a Session, peer, or user application.

| Device class | Android/API | Current production resolver | Legacy `InputManager` candidate | Local injection evidence | Investigation state |
| --- | --- | --- | --- | --- | --- |
| Samsung SM-S901B | Android 16 / API 36 | `InputManagerGlobal` resolved; current resolver available | Also resolved | Key, touch, pointer, and joystick accepted and observed | `INPUT_SUPPORTED_CURRENT_PATH` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | `InputManagerGlobal` class unavailable; current resolver reports `InputApiUnavailable` | Two- and three-argument injection overloads resolved | Key, touch, pointer, and joystick accepted and observed | `API_PATH_DISCOVERED`, `LOCAL_INJECTION_PROVEN` |
| Samsung SM-A415F | Android 12 / API 31 | `InputManagerGlobal` class unavailable; current resolver reports `InputApiUnavailable` | Two-argument injection overload resolved; target-UID overload unavailable | Key, touch, pointer, and joystick accepted and observed | `API_PATH_DISCOVERED`, `LOCAL_INJECTION_PROVEN` |
| Samsung SM-A415F | Android 11 / API 30 | `InputManagerGlobal` class unavailable; current resolver reports `InputApiUnavailable` | Two-argument injection overload resolved; target-UID overload unavailable | Key, touch, pointer, and joystick accepted and observed | `API_PATH_DISCOVERED`, `LOCAL_INJECTION_PROVEN` |

Every tested UserService ran under shell UID 2000 after a successful Warpnect-side Shizuku permission
check. The framework classes resolved from the boot class loader; this is an API-surface compatibility
finding, not a Shizuku permission, Binder-service, SELinux, or device-model finding.

## Privileged Input Legacy Android Implementation (A41 E2E Validated)

The production resolver now selects `InputManagerGlobal` when its complete modern API is available and
otherwise qualifies the legacy `InputManager.getInstance().injectInputEvent(InputEvent, int)` adapter.
The resolver remains capability-driven, caches its selected adapter for the UserService lifetime, and
keeps explicit target-UID injection unavailable on the legacy two-argument API. The final validation APK
was built from `b609f4af14fbcd9fd4293e3ea67788f5819ad04f`, SHA-256
`7E48686B4A5D6542286FB2B081B441993254331F28DB275433524CD6C645C1DB`, 28,937,243 bytes, with
ABIs `arm64-v8a`, `armeabi-v7a`, and `x86_64` (`minSdk 26`, `targetSdk 35`).

| Device class | Android/API | Production backend result | Local production validation | End-to-end reverse input | Current status |
| --- | --- | --- | --- | --- | --- |
| Samsung SM-A415F | Android 11 / API 30 | `LegacyInputManager`; `input_available=true` | Key, touch, pointer, and joystick accepted and observed in a Warpnect-owned target; reconfirmed after helper reset | Human touch on S7 Client captured, sent as Input Payload V1, received by Host, forwarded to legacy injection, accepted by Android, and observed by the Host target; SystemAudio unadvertised/unselected | `PRODUCTION_PRIVILEGED_INPUT_SUPPORTED_LEGACY_BACKEND`; `REAL_REVERSE_INPUT_E2E_VALIDATED` |
| Samsung SM-A415F | Android 12 / API 31 | `LegacyInputManager`; `input_available=true` | Key, touch, pointer, and joystick accepted and observed in a Warpnect-owned target; reconfirmed after helper reset | Human touch on S7 Client captured, sent as Input Payload V1, received by Host, forwarded to legacy injection, accepted by Android, and observed by the Host target; SystemAudio unadvertised/unselected | `PRODUCTION_PRIVILEGED_INPUT_SUPPORTED_LEGACY_BACKEND`; `REAL_REVERSE_INPUT_E2E_VALIDATED` |
| Aocwei X700_EEA tablet | Android 13 / API 33 | `LegacyInputManager`; `input_available=true` | Key, touch, pointer, and joystick accepted and observed in a Warpnect-owned target | Not run | `LOCAL_PRODUCTION_LEGACY_INPUT_PASS` |
| Samsung SM-G935F | Android 8.0 / API 26 | Not resolved because Shizuku was not running | Not run | Not run | `API26_LEGACY_INPUT_INCONCLUSIVE_SHIZUKU_UNAVAILABLE` |
| Samsung SM-S901B | Android 16 / API 36 | `ModernInputManagerGlobal`; `input_available=true` | Final APK selected the modern backend; key, touch, pointer, and joystick returned `SubmittedAsync` and were observed by the Warpnect target | SystemAudio is now omitted before WNCP because the real privileged loopback `AudioRecord` is uninitialized; no reverse-input event counted | `MODERN_PATH_PHYSICAL_LOCAL_REGRESSION_PASS`; SystemAudio truthfully unavailable on this runtime |

The API 30 and API 31 A41 targets are now validated through real reverse-input Sessions. The S7 Client
was the human-input source in both runs; no Host-side touch, ADB input, UI automation, or local injection
was used as E2E evidence. The physical API 36 modern-backend regression is now locally validated on the
S22 with the final APK. The S22 SystemAudio preflight now rejects the actual unstartable privileged
`AudioRecord` before WNCP instead of reaching `SystemAudioStartFailed`; no S22 reverse-input event is
counted as E2E evidence.

The final API 30 and API 31 traces reached `setup_committed`, media startup, Input Payload V1 capture and
transport, Host payload receipt, `LegacyInputManager` `SubmittedAsync` injection, and
`INPUT_SESSION_MAIN_TARGET_TOUCH_OBSERVED` on the A41 Host. The runner's media-readiness result remains
separate from this human-event correlation and was not used as a substitute for it.

The 2026-09-05 completion pass reused the exact APK above, including verification of installed
`base.apk` digests on all four attached devices. A bounded cleanup of residual Warpnect-owned shell
helpers left both Shizuku servers running; both A41 local production targets then observed key=1,
touch=1, pointer=2, joystick=1, with `SubmittedAsync` results and helper UID 2000.

### SystemAudio Startup Correction

The SystemAudio production capability probe previously checked `MODIFY_AUDIO_ROUTING` against the
package attribution of the current context. On both tested A41s, the actual AudioPolicy registration
was made by the Shizuku UserService as shell UID 2000 and AudioService rejected it before AudioRecord
creation. The production capability check now uses the UserService's own permission (`checkSelfPermission`),
which conservatively reports SystemAudio unavailable when registration would be denied. It does not
change Audio payload, channel, Session, or Input semantics; a committed SystemAudio channel still fails
the Session if its source later cannot start.

Debug APK built from `fa466f0b510e782ad80a33b666e0eba579e1bf30`, SHA-256
`9EE0A01950C7EA30AB8B44635E88762C875AEFAC473448AC74613343D6ACB4EF`, 28,920,859 bytes, was installed
on both A41s. Each A41 Host-to-S7 Client Session then reached authentication, committed setup, video
channel readiness, and media start without `SystemAudioStartFailed`. This removes
`A41_INPUT_E2E_BLOCKED_BY_SYSTEM_AUDIO_STARTUP`; it does not prove remote reverse input. The next Input
proof must correlate a human Client touch with Input Payload receipt, legacy adapter injection, and an
event observed by the Warpnect-owned Host target.

The API 33 tablet originally reached `createAudioRecordSink` and returned
`AudioRecordCreationFailed`. Revalidation after the generic UserService Binder-identity correction
now passes the exact production preflight (`prepare` -> `start` -> `stop`) and publishes SystemAudio
truthfully. A bounded debug-only verification used the same production controller with a normal-app
PCM16/stereo/48 kHz `USAGE_GAME` AudioTrack tone: 68,400 frames were captured, with 116,112 non-zero
samples, peak 8,191, and RMS 5,340.6. This is `TABLET_API33_SYSTEM_AUDIO_SUPPORTED`; it changed no
payload, mix, fallback, or model-specific behavior. The later Tablet-to-S9 campaign completed five
first-attempt Host/video Sessions without restart.

On 2026-09-09, after the tablet's Shizuku Manager was updated to 13.6.0.r1086.2650830c, a direct
production-controller run exposed a provider bootstrap failure before the privileged Binder was delivered. A
bounded isolator repeated the same failure for a minimal Binder and the production capture, audio, and input
UserServices. On this tablet's MediaTek `LoadedApk`, `makeApplication` calls
`Application.getProcessName().equals(mPackageName)` in an OEM resource-preload guard. The Shizuku
`ActivityThread.systemMain()` bootstrap has no application process name, so that forced 13.6 application path
throws before the UserService class loader, constructor, AudioPolicy, or AudioRecord is reached. The same APK
and Shizuku 13.6 publish all four Binders on the A41. The precise result is
`SHIZUKU_13_6_MAKEAPPLICATION_TABLET_FRAMEWORK_INCOMPATIBILITY`; it does not generalize to API33, an OEM, a
payload, or an audio format. The manager removes an unstarted service after its 30-second startup deadline
without delivering a `ServiceConnection` callback. Warpnect bounds that local bind by the same deadline and
reports `PrivilegedServiceUnavailable` before negotiation rather than wedging Host readiness. A controlled A/B
kept the Tablet Android build, Warpnect APK/app data, and UserService arguments fixed and changed only the official
signed Shizuku provider. With 13.5.4.r1049.0e53409 (APK SHA-256
`A05832CE3716AFB1FCCCF46F348006D2A296CA777E1FF3D223797DC74D06B31F`), all four Tablet probes (minimal,
capture, audio, input) published their Binders; the same minimal/capture/audio/input matrix also passed on the A41
control. The production tablet SystemAudio controller then prepared, started, and captured the normal Android
48 kHz stereo tone (70,080 frames, 115,632 non-zero samples, peak 8,191, RMS 5,265.3) before clean stop. The
exact differential is `SHIZUKU_13_6_PROVIDER_REGRESSION_CONFIRMED_FOR_TABLET_FRAMEWORK`, not a broad Android,
OEM, or Warpnect packaging claim. The old provider remains diagnostic only: Shizuku 13.6 was still the current
official stable and no official fixed revision was available during this run. On 2026-09-09, a clean Tablet-to-S9
Session under 13.5.4 ran the normal 997 Hz PCM16/stereo/48 kHz `AudioTrack` tone for 60 seconds (2,875,396 source
frames). The Tablet SystemAudio runtime stayed `Running`: capture and encoder progress matched, the Shared PCM
Ring high-water mark was 7 with no overrun/drop, and 69,998 payloads plus 69,999 generated/sent datagrams with zero
`WouldBlock`, transport failures, or Session errors at the 50-second sample. The S9 SystemAudio receiver and native
Oboe playback runtime stayed `Running`; at the 60-second sample it had received 71,439 audio datagrams, delivered
71,438 payloads, decoded 71,437 frames, and advanced the playback ring from 13,499,760/13,499,760 written/consumed
frames to 16,251,120/16,250,976. Its occupancy remained bounded (high-water mark 960), with zero xruns and no
receiver or Session error. The S9 stayed attached to ADB for the complete run. This is
`TABLET_SYSTEM_AUDIO_REMOTE_E2E_PASS` technical evidence through the real Client playback consumer; human
audibility remains pending. The provider was then restored to official 13.6, whose Tablet incompatibility remains
separate from this controlled 13.5.4 validation.

### API 36 SystemAudio Startability Qualification

The S22/API 36 had a different false-positive boundary. Its Shizuku audio UserService runs as shell UID
2000 and reports the routing permission, so the earlier static capability check published SystemAudio. The
baseline production Session consequently committed SystemAudio and then exposed only the generic
`SystemAudioStartFailed`. With the incoming application Binder identity cleared, AudioPolicy registration
passes, but the same production `createAudioRecordSink` returns an uninitialized AudioRecord: AudioFlinger
rejects `uid=2000, package=io.warpnect` attribution. This is recorded as
`S22_AUDIO_RECORD_INITIALIZATION_FAILED` / `ANDROID_API36_AUDIORECORD_ATTRIBUTION_RESTRICTION`, not as a
Samsung-specific rule.

SystemAudio capability now performs the exact bounded production prepare/start/stop sequence after static
prerequisites pass. On the tested S22 that sequence returns `AudioRecordUninitialized`, so SystemAudio is
unavailable and omitted before WNCP. The implementation does not change the audio payload, add a microphone
substitute, or allow a committed SystemAudio channel to continue after startup failure. The same
Binder-identity correction allows the tested API 33 tablet to complete the production capture path; that is
a different outcome from the API 36 attribution restriction, not a general API-level or OEM rule.

The validation APK built from production commit `9e04f81d90949afc970f4480f657743e51281b85` was SHA-256
`4C14A9854AD10DDF39C53DF877C85D56CC2A25C67AF8C1B4673A4FA3BCACA0C2`, 29,235,744 bytes, with ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. Its installed `base.apk` digest was verified on the tested S22,
S9, and A41 without clearing application data.

### S9 / S7 SystemAudio Host Coverage

The 2026-09-09 host-coverage pass used production commit `e51d7e3`, debug APK SHA-256
`A7AD7211B7F82F672EE4B0F3C8F0650607D29EF805400D56D612690F5CCD2389`, 28,986,547 bytes, and ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. The installed `base.apk` digest matched on both Hosts; app
data was retained. Each device ran the official Shizuku Manager package
`13.6.0.r1086.2650830c` with an ADB-started server and authorized Warpnect. The debug bootstrap probe
published minimal, capture, audio, and input Binders as shell UID 2000 on both devices before capability
collection; no pending install operation overlapped a measured result.

| Host | Android/API | First SystemAudio boundary | Local PCM / remote E2E | Final result |
| --- | --- | --- | --- | --- |
| Samsung SM-G960F | Android 10 / API 29 | The actual shell-UID audio UserService lacks `MODIFY_AUDIO_ROUTING`; the production preflight returns `PermissionDenied` before `AudioPolicy` preparation. | Not applicable: SystemAudio is absent from the Host capability snapshot and is therefore omitted before WNCP. | `S9_PRIVILEGED_PERMISSION_UNAVAILABLE`; `SYSTEM_AUDIO_TRUTHFULLY_UNAVAILABLE` |
| Samsung SM-G935F | Android 8.0 / API 26 | PCM Shared Ring V1 requires `android.os.SharedMemory`, which Android adds in API 27. The production preflight now returns `UnsupportedPlatform` before a gateway bind, bundle unmarshalling, `AudioPolicy`, or `AudioRecord`. | Not applicable: SystemAudio is absent from the Host capability snapshot and is therefore omitted before WNCP. | `S7_SHARED_MEMORY_PLATFORM_UNSUPPORTED`; `SYSTEM_AUDIO_TRUTHFULLY_UNAVAILABLE` |

The API26 gate is a generic platform-contract correction, not an API26 success allowlist: the existing
SystemAudio transport requires a real `SharedMemory` parcelable across the privileged and ordinary app
processes. It also makes a direct erroneous `prepare` call return `UnsupportedPlatform` rather than resolving
the unavailable framework class. No Audio Payload V1, PCM ring layout, format, Session, WNCP, fallback, or
device/OEM branch changed. Since neither Host can truthfully select SystemAudio, no local tone or remote
audio E2E is claimed or required for these two results. Probe teardown left current-run capture, audio, and
input helpers at zero.

Earlier startup retries included one clean-state pairing attempt on the API 30 Host and S7 Client;
pairing was re-established normally and the API 30 recording permission was restored. The final
post-helper-reset comparisons did not clear application data. Test applications were stopped after
the bounded campaign; Shizuku servers were not restarted.

An initial `InputSessionHold` harness result incorrectly treated early Client decode as readiness
after Host startup had failed. That result is invalid as Input readiness or E2E evidence. The harness
now requires both media-start acceptances, preserves the terminal startup reason, and explicitly
labels readiness as NOT proving reverse-input E2E. Thirteen deterministic outcome assertions cover
this distinction. Existing 576 JVM tests (zero failures/errors/skips), lint/build gates and unchanged
native Debug/Release CTest 22/22 remain carried evidence for the unchanged application artifact.

## RFC-002I Decoder Investigation (Historical)

This is laboratory evidence retained from before RFC-002I implementation. At that time, the tested
Android 8.0 / API 26 Samsung SM-G935F had framework decoder hardware classification unavailable,
so the then-current Client capability was unavailable with `HardwareClassificationUnavailable`
before WNSN.

The selected `OMX.Exynos.avc.dec` reports 1280x720 at 60 fps support. Follow-up RFC-002I
calibration replayed a byte-identical six-second 1280x720/60 AVC fixture with an actual 8.60 Mbps
average envelope on the S22, S9, and S7 Exynos decoders. Each S7 Exynos run produced all 360
decoder outputs and 359-360 Surface callbacks without a codec error. This is pre-implementation
qualification evidence only at that time. The final production results are recorded in the RFC-002I
Production Validation section above.

On the API 33 tablet, production S22-to-tablet traces rendered a real remote frame in portrait and
landscape. A bounded rotation during streaming did not fail the Session, but these earlier
observations did not force Surface destruction/recreation or establish a separate post-recreation
presentation event. A later tablet-Host attempt still reported `ShizukuPermissionRequired` during local Host
capability collection despite a Shizuku permission update; that Input/privilege-provider boundary
is separate from Client decoder qualification and leaves the reverse-role trace inconclusive.

## Phase 6.5 DirectShell Modern Host Runtime

This current-status section records the validated app-owned DirectShell modern
Host baseline. It does not rewrite the historical Shizuku evidence above.
Counted DirectShell Host runs use user-authorized Wireless Debugging, app-owned
local ADB, app_process as shell uid 2000, and purpose-specific DirectShell
servers. Desktop ADB may install or observe, but does not launch the privileged
server. Shizuku is not the privileged Video, SystemAudio, or Input provider in
the counted DirectShell runs.

| Host | API | Bootstrap and three privileged features | Video to S9 | SystemAudio to S9 | Current result |
| --- | ---: | --- | --- | --- | --- |
| Samsung SM-A415F | 31 | DirectShell Video, SystemAudio, Input PASS | Visible E2E PASS | AUDIBLE_CONFIRMED | Modern DirectShell Host validated |
| Tablet X700_EEA | 33 | DirectShell Video, SystemAudio, Input PASS while Shizuku 13.6 remains bootstrap-incompatible | 3/3 visible E2E PASS | AUDIBLE_CONFIRMED | DirectShell bypasses the documented Shizuku UserService boundary |
| Samsung SM-S901B | 36 | DirectShell Video, SystemAudio, Input PASS | 3/3 visible E2E PASS | 3/3 technical PASS; AUDIBLE_CONFIRMED | Shell identity avoids the historical Shizuku attribution boundary |

The A41 initially retained a valid Wireless Debugging pairing and a usable
local TLS-connect endpoint, but Warpnect accepted foreign `_adb-tls-connect`
records. On API31, the foreign resolve occupied the one active NSD resolve slot
and the later local candidate failed with `NsdManager.FAILURE_ALREADY_ACTIVE`
(`3`). The repaired client binds discovery to the ADB service identity observed
during pairing, accepts only the canonical DNS-SD conflict alias, and owns
candidate resolution by generation. The result is
`WARPNECT_NSD_FOREIGN_SERVICE_STARVATION_AND_STALE_ACTIVE_RESOLUTION_BUG`, a
portable Warpnect lifecycle correction rather than a device-specific branch or
an ADB publication failure.

DirectShell hardware evidence is now sufficient for the modern baseline. The
2026-09-23 final static-planner debug APK (SHA-256
`32017CC0DC069E0F380A10C6FB5FCF1CBDD0FCCF4AD938ADD460A70E94CD00C5`,
33,669,698 bytes) was installed identically on A41/API31, Tablet/API33,
S9/API29, and S7/API26 without clearing app data. A41 and Tablet each performed ordinary
`Enable Host` admission through app-owned Wireless Debugging and DirectShell.
Their explicit matrix was `DIRECT_ROOT=NOT_IMPLEMENTED`,
`DIRECT_SHELL={VIDEO,SYSTEM_AUDIO,INPUT}=QUALIFIED`, and
`SHIZUKU=NOT_AVAILABLE`; the frozen selected plan was the all-DirectShell
`SINGLE` plan. The UI remained unavailable until that freeze, then displayed
`Host: Available` and `Waiting for clients`.

With both Hosts active, the S9 Client discovered two separate rows by their
device aliases, `Galaxy A41 di Massima` and `Z138_EEA`. A silent
Tablet-to-S9 Session then reached Streaming with the frozen DirectShell plan:
the Host reported DirectShell Video, SystemAudio, and Input, while the Client
ran its normal receive/decode/playback pipeline. No deliberate tone was played
and this run makes no new human-audibility claim. Semantic Disconnect followed
by Disable Host returned S9, Tablet, and A41 to `Ready`/`Host: Not checked`;
each host had zero remaining DirectShell shell processes.

This validates current-build DirectShell-only Host admission, plan freeze, and
cleanup. An S7/API26 natural preflight control returned
`DIRECTSHELL_UNSUPPORTED_PLATFORM` / `wireless_debugging_requires_api_30` before
any shell runtime launch. The final UI stated `Host unavailable: DirectShell
requires Android 11 or later`; the Host-only control left Client mode unblocked
(`Client: Not checked`), and no Shizuku fallback was activated. DirectRoot remains
not implemented. Controlled DEBUG composite activation is now hardware-proven:
the A41/API31 to S9/API29 Session froze
`COMPOSITE { Video=DIRECT_SHELL, SystemAudio=DIRECT_SHELL, Input=SHIZUKU }`.
It reached Streaming without a deliberate tone. Approximately five to six
physical user touch interactions on the S9 produced 26 captured Warpnect touch
events; all 26 corresponding events were injected on the A41 through
`ShizukuUserService`, with no injection error. The 26-event count is
protocol/input-event evidence, not 26 separate human taps. Normal A41 admission
after teardown again selected all-DirectShell `SINGLE`. This is not live provider
migration and does not make Shizuku the release default. The normal Host
continues to require Video, SystemAudio, and Input together; a missing required
capability means Host unavailable rather than a partial advertised Host.

## AOSP Emulator / Root Laboratory

This section is separate from physical-device qualification. Emulator evidence
does not establish Magisk authorization, OEM SELinux, vendor/kernel, physical
SurfaceFlinger/codecs/audio HAL or production Root acquisition.

| Target | API / image | Root runtime | Root Input | Root Video | Root SystemAudio | Normal Root-only Host |
| --- | --- | --- | --- | --- | --- | --- |
| Warpnect_Root_API25 | 25 / AOSP-default x86_64 r1 | ADB-root environment qualified; app helper not tested | NOT_TESTED; minSdk26 blocks APK | NOT_IMPLEMENTED | NOT_IMPLEMENTED | Unavailable; API25 is not supported by the app |
| Warpnect_Root_API26 | 26 / AOSP-default x86_64 r1 | Authenticated DEBUG/lab UID0 helper, Enforcing u:r:su:s0 | QUALIFIED for exact lab;3/3 same-generation requalifications on final Audio APK | QUALIFIED for exact lab;3/3 same-generation decoded fixture A/B requalifications | QUALIFIED for exact lab;3/3 OFF/ON ring/drain cycles, plus3/3 isolated audio resource cycles | Normal Enable/Disable Host: eight consecutive PASS, 8/9 counted attempts; SINGLE/DIRECT_ROOT, Host Available and LAN registration; restart1/1, controlled loss2/2; physical-peer visibility/remote Session NOT_TESTED |

API26 fingerprint:
`Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.
2026-09-27 Input artifact SHA-256:
`713A775E7350ECADB77D9C368C90DAB4B0845044CF60E1C09DAC54F7E82F33E1`.
Provenance is LABORATORY_EXTERNAL_ROOT, not production Root-manager approval.
The app cannot invoke the image's AOSP su; the explicitly verified emulator-only
harness launches the helper. No API25 installation floor change was made.

A separate active-key/touch owner-connection loss passed 1/1 with target-observed
KEY_UP/TOUCH_CANCEL. Stale Input gateway and wrong-HMAC typed request were
rejected; helper loss removed volatile readiness without deleting the scoped
Input cache. Process restart loaded one Root Input record with runtime absent,
not falsely READY. Final helpers/listeners/bootstrap files were 0; adbd returned
to UID2000 and API26 was stopped. Earlier exploratory and reset-fixture failures
are retained in `../phase6_5/DirectRootInputQualification.md`, including the
0/1 counted pre-final-artifact failure, not silently retried.

The exact-build A41/API31 non-Root control loaded advisory SINGLE / DIRECT_SHELL,
but its one live admission failed 0/1 at WIRELESS_DEBUGGING_ENDPOINT_UNAVAILABLE
before feature qualification. Root was not selected, no Warpnect privileged
process remained and no fallback ran. This does not establish a new successful
physical regression or extend the historical modern media evidence.

The subsequent Video artifact on2026-09-27 has SHA-256
`BB3B4D5B890F5869CF4788B44AF63161A0DD034EB3FB066EBABFC0AF507346FC`.
Three fresh Root/Surface generations passed Binder handoff, shared
SurfaceControlDisplay mirror, existing app MediaCodec AVC and offline decoded
circle/checkerboard content. All helpers observed UID/GID0, u:r:su:s0;
Enforcing unchanged. Two additional active helper/app-loss cases cleaned up
mirror/helper/listener/lease/bootstrap files; cache restored VIDEO+INPUT with
runtime/capture absent. At that Video checkpoint Root audio was NOT_IMPLEMENTED. The earlier
diagnostic codec-metadata failure is preserved separately, not a hidden retry.

No native or physical Root claim follows. App graphics FD samples varied
modestly, so indefinite soak leak-freedom is not claimed. Exact identities,
AVC/decoded-image evidence,841-test gates and boundaries are in
`../phase6_5/DirectRootVideoQualification.md`. The optional same-artifact Tablet
non-Root admission observations were1/2: first Host Available, second failed at
privileged runtime preparation. Exact plan telemetry was unavailable; no new
complete physical media regression is claimed. All physical devices remained
unrooted/unflashed/unwiped, no tone was played; final adbd was UID2000 and the
API26 AVD stopped.

The later 2026-09-28 physical Tablet/API33 non-Root follow-up passed 10/10
normal UI Enable/Disable Host cycles on the exact final APK (SHA-256
`87531FE0D250D9963FAA7ECFF9AD6A45D468A313627045F4F06A436CAE72E1B0`).
An earlier diagnostic APK passed two separate 10/10 campaigns. A
further 1/1 plan audit logged `SINGLE` with Video, SystemAudio and Input all
`DIRECT_SHELL` and `shizuku_started=false`. Each counted teardown found zero
Warpnect `app_process` helpers and bootstrap secret files. The old 1/2 failure
remains historical and its first failing stage is inconclusive; the new runs
do not retroactively explain it. This was Host-only admission, not a new
remote-media E2E. Exact per-cycle evidence and harness anomalies are in
`../phase6_5/TabletDirectShellRuntimeReliability.md`.

The subsequent SystemAudio artifact has SHA-256
`d3af77d3ed000b02bb6d0402cb15b4ffe161a428bc18bec176c5cfe79aaa43b6`.
Every final combined cycle requalified all three cells on its own authenticated
UID0/helper-version4 generation. API26 ashmem uses the unchanged PCM Ring V1,
true Binder-transferred memory/notify/ACK descriptors and the original drain.
App-side silence RMS0 contrasts with997Hz tone RMS2463.721-2463.755, peak3485
and143502-145422 nonzero samples. Ring HWM7/8 and10 intentionally induced drops
were bounded; final occupancy0 and notify=ACK in each cycle. Existing common
controller/Opus smoke produced93/85/93 units with0 encoder errors.

The real cached-plan builder, late revalidation and admission freeze returned
SINGLE/DIRECT_ROOT3/3 using current-generation observations, not synthetic cells.
This is not ordinary product Host Available/discovery/network admission: the
product Root-manager adapter is absent and normal hardware-codec policy was
not bypassed for AOSP's DEBUG software Video fixture. No partial Host was added.
The first combined campaign2/3 is retained separately: cycle3 stopped before
Audio because the DEBUG observer required a global order for independent reset
sources. Only that observation rule changed; every release is still required.

The isolated audio resource campaign passed3/3 with app FD60/60/60 and helper
FD30-before/33-after per generation; complete visual fixture samples65/68/70
are recorded as broader graphics/resource-observability debt, not proof of
indefinite whole-app leak freedom. PCM maps/leases/helpers/listeners/bootstrap
files were checked at teardown. Three compatibility records reload after app
restart while runtime/audio/leases stay absent. Full loss/cleanup accounting,
904-test gates and exact per-cycle metrics are in
`../phase6_5/DirectRootSystemAudioQualification.md`.

No physical devices were modified or newly Root-qualified by this campaign.
Routing/human audibility remain UNKNOWN/NOT_TESTED in the no-audio AVD. Magisk,
production Root authorization, remediation, recovery and migration remain deferred.

The later normal Host artifact SHA-256 is
`5ccd6dfe064b8c785c65b3c6d77d17e7db76147c044baa78a7db012f67c3a70a`.
Its real application Enable/Disable path reached SINGLE/DIRECT_ROOT and Host
Available for eight consecutive cycles. Formal accounting is8/9: attempt9
failed at an inconclusive ADB inspection precondition before Root acquisition;
it was not retried or reclassified as PASS. Normal LAN registration was active;
direct discovery is unsupported in this AVD. Physical-peer visibility and a
remote Root Session were NOT_TESTED. App restart admission passed1/1 and
controlled pre-/post-freeze helper loss2/2 without provider migration.

Categorized app FDs stabilized at62 for cycles2-8 after emulator graphics warmup,
with no observed monotonic Warpnect-owned control/PCM/pipe growth. Compatibility
cache bytes stayed unchanged; runtime/capture/input availability stayed volatile.
This is normal Host admission validated in the AOSP lab, not physical Root or
production authorization. See `../phase6_5/DirectRootHostAdmissionValidation.md`
for every attempt, anomaly, generation, ownership metric and931-test gates.
