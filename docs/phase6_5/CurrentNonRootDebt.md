# Current Phase 6.5 Non-Root Debt (2026-10-03)

This is the current debt index, not a rewrite of historical experiment reports.
Evidence order: repository/runtime, current Phase 6.5 reports and compatibility
matrix, accepted RFC-006I/J, then older reports/checklists. Implementation,
hardware qualification, and product policy are distinct states.

Current [final global microphone closure](DirectShellGlobalMicrophoneFinalClosure.md)
supersedes the historical continuations below. Frozen C782DD9D r2 MIC3/3 above121s
passes ordinary consumer close/reopen with steady IPC/track/DEBUG quality deltas0.
Quality VALIDATED on exact S9/API29 ->Tablet/API33 path, UID NONE, physical997Hz,
natural routing and cleanup.1022 JVM/22+22 native gates pass. No release, valid
forced preference or additional portable mechanism is promoted. SystemAudio
causal jitter/playout investigation is the ONE recommended next separate task.

Current [residual quality continuation](MicrophoneReceiverSchedulingQuality.md):
peer link restored, bounded microphone admission prevents whole-block loss
near a callback without ring growth. Final81F10D24 cycle1 still FAIL on residual
AudioTrack/source starvation;2/3 NOT RUN.1017 JVM/22+22 native pass. Global
functional token retained; quality PARTIAL, no portability/priority promotion.

Latest [receiver scheduling continuation](MicrophoneReceiverSchedulingQuality.md)
supersedes the next note: microphone-only worker priority corrects a measured
local drain starvation, but one stable diagnostic is not final closure.
Frozen43FB71D6 counted cycle1 FAIL on peer/media loss then Tablet Wi-Fi/Debug
wireless loss; cycles2/3 NOT RUN. Host publication withdrawal is truthful.
1016 JVM and22/22 native each pass; no commit. Global functional token retained,
quality PARTIAL, adaptation/portability/forced priority still behind this gate.

Latest [source-clock recovery evidence](MicrophoneSourceClockRecovery.md)
supersedes the following current-status note: nominal pacing debt is corrected
and focused tests pass, but D28F6136 cycle1 FAIL stops acceptance;2/3 NOT RUN.
An extended80s continuous-reader diagnostic has zero steady deltas, not the
required close/reopen3x60s gate. A later media stall precedes logged Tablet
Wi-Fi loss and Android's automatic Debug wireless disablement. Peer ping/TCP
later recover both ways without a product change; manual Debug wireless is
still needed. Global injection functional token retained, quality PARTIAL.
No portability/priority/release/recovery promotion or checkpoint.

Current [clock-continuity evidence](MicrophoneConsumerClockContinuity.md):
final66A8B330 completes functional3/3 consecutive108s S9 ->Tablet MIC Sessions,
but quality remains PARTIAL: cycle3 has recurring steady DEBUG/AudioTrack
underflow despite IPC rejection0. No larger buffer or codec/socket change.
One later diagnostic fails, followed by Wi-Fi disconnect and Android disabling
Debug wireless; Host publication is withdrawn with the typed prerequisite.
A41/S7 actual registration is permission blocked; S7's legacy context is reachable
but no product adapter is promoted. Tablet priority authorization-only probe
does not validate valid preference/abnormal cleanup. Gates1007 JVM/161 suites,
native22/22 each, no commit. This current note supersedes the status prose below;
all previous campaigns remain historical and the functional token is retained.

Historical preceding continuation:

Latest [consumer-transition continuation](MicrophoneConsumerTransitionQuality.md):
Tablet is connected again. The exact guard candidate fails before new changes;
bounded FIFO expiration and provisional40ms IPC freshness improve active-reader
delivery in two complete108s primary Sessions. The third fails during renewed
Tablet path/radio loss, with Bluetooth SIGKILL, wlan0 disassociation and Android
disabling Wireless Debugging. Quality remains PARTIAL; no portability/priority
or release advancement. Failed replacement withdraws publication with the
typed prerequisite on this physical boundary. Full gates1004 JVM/161 suites,
native Debug/Release22/22. No checkpoint;80/70ms DEBUG playout unchanged.

Previous continuation (historical): [quality follow-up](DirectShellGlobalMicrophoneInjectionQuality.md): global
input quality remains PARTIAL. With manually restored Wireless Debugging, the
unchanged candidate's first counted S9 -> Tablet cycle failed quality after
111.017s; acceptance stopped. Opening-flush and smaller AudioTrack-start-threshold
experiments did not close pressure and were reverted. Later S9/Tablet peer
unicast failed in both directions while each reached the gateway and A41
reached both. A subsequent [network-only continuation](S9TabletPeerLinkRecovery.md)
restored both-direction IPv4 ping/TCP/UDP through one Tablet Wi-Fi rejoin,
without changing product media code. The exact network mechanism remains
INCONCLUSIVE. Android disabled Wireless Debugging on rejoin; Tablet USB then
disappeared and the operator confirmed the Tablet is unavailable/replaced by S22.
Primary acceptance is paused for Tablet return, not substituted on S22/A41.
A local consumer-window admission guard has software tests but is physically
unvalidated. Candidate fixes have not earned a closure token. A separate
failed helper-replacement health/publication branch
has a source correction pending physical validation. Original idle-liveness and
functional global-input milestones are not revoked or generalized to candidates.

| Concern | Historical status | Current status and evidence | Next boundary |
| --- | --- | --- | --- |
| Black Client SurfaceView / visible video | Open during Shizuku Tablet failure | **CLOSED** for tested modern DirectShell paths: Surface stacking fix and synchronized visible A41/Tablet/S22 -> S9 proof; see [compatibility matrix](../testing/AndroidCompatibilityMatrix.md). The old Tablet Shizuku failure remains historical. | No repeat of the old black-frame investigation. |
| Cold Host capability qualification versus Session timeout | Historical timeout | **CLOSED** by pre-discovery qualification and cold A41 -> S7 first-attempt Session, documented in [FutureRoadmap](../FutureRoadmap.md). | Do not raise protocol timeouts for this closed cause. |
| Privileged Shizuku UserService helper leakage | Historical orphan risk | **CLOSED** for the tested non-daemon Shizuku path by destroy/close ownership and four A41 -> S7 cycles in [FutureRoadmap](../FutureRoadmap.md). | Normal lifecycle regression only when that provider is selected. |
| A41 `_adb-tls-connect` discovery starvation | Historical blocked bootstrap | **PRODUCT RESOLVER FIX CLOSED; EXTERNAL SERVICE AVAILABILITY CONDITIONAL**: trusted DNS-SD identity and serialized resolver lifecycle remain fixed in [DirectShellA41NsdResolutionFix.md](DirectShellA41NsdResolutionFix.md). In the 2026-09-28 routing campaign, an initial A41 admission again found no `_adb-tls-connect` even though Wireless Debugging was enabled; a manual off/on restored service publication and the next 3/3 admissions succeeded. | Report missing daemon publication as a recoverable external precondition, not an audio or capability incompatibility. Do not silently toggle Android settings. |
| Tablet Shizuku 13.6 UserService bootstrap | External provider incompatibility | **SUPERSEDED for primary modern Host** by Tablet DirectShell Video/SystemAudio/Input and visible/audible E2E; Shizuku remains an optional capability provider, so its own upstream defect is not claimed fixed. | Revisit only for an explicitly selected Shizuku compatibility plan. |
| S22 Shizuku SystemAudio attribution | Historical AudioRecord uninitialized | **SUPERSEDED for primary modern Host** by real-shell DirectShell capture and S22 -> S9 audible E2E. The Shizuku backend itself was not repaired. | Preserve the distinction in provider-specific qualification. |
| Modern S9 SystemAudio human audibility | Earlier NOT_CONFIRMED | **CLOSED for A41, Tablet and S22 -> S9** by the later operator confirmations indexed in [Phase 6.5 README](README.md) and [compatibility matrix](../testing/AndroidCompatibilityMatrix.md). | New topologies require their own listening proof. |
| Tablet DirectShell Host preparation 1/2 | `FAIL_PRIVILEGED_RUNTIME_PREPARATION` with no app trace | **STABLE NOW, HISTORICAL CAUSE INCONCLUSIVE**: exact final APK 10/10 UI admissions with per-cycle frozen-plan traces; prior diagnostic APK two 10/10 campaigns plus 1/1 plan audit. See [Tablet reliability report](TabletDirectShellRuntimeReliability.md). Current Wireless Debugging was initially OFF but does not explain the old failure. | If it recurs, use first failed `DIRECT_SHELL_PREPARATION` stage; do not infer a cause from the high-level code. |
| Legacy `OnFrameRendered` observability | Delayed/weak callback concern | **CLASSIFIED_PHASE7_MEASUREMENT_DEBT**: [S9 remote measurements](LegacyRenderedFrameObservability.md) PTS-matched 64 callbacks with release-to-receipt up to 70.7 s; local S7/S9 fixture callbacks were prompt, and A41 codec `nanoTime` was PTS ns rather than the app's MONOTONIC clock. Session success/recovery does not require callbacks; UI and metric semantics now say Surface handoff or callback receipt, never physical presentation. Exact-key qualification still counts offscreen fixture callbacks and passed on S7; no false admission result was demonstrated. | Phase 7 needs external physical-display methodology and cross-device clock/identity provenance. Revisit qualification only if an exact-probe false negative is observed. |
| Host-local versus Client SystemAudio routing | Duplication/mute policy unresolved | **CLOSED_REMOTE_ONLY for the tested modern DirectShell built-in-speaker tier**: [normal Session routing campaign](SystemAudioRoutingSemantics.md) observed Tablet 3/3, A41 2/2 returned human answers plus a third technical Session, and S22 3/3 Client-only tones, with Host-only sound restored after normal Disconnect. The older standalone A41 local-speaker observation remains historical and is not treated as a normal-Session guarantee. Bluetooth, other outputs and untested Hosts remain UNKNOWN. | Do not promise `HOST_AND_REMOTE` or generalize beyond the tested route. Any duplication/other-output support needs separate provider-specific qualification and UX policy. |
| Post-Disconnect Host rediscovery | Tablet/S22 stale `Running` and lost rediscovery during routing tests | **FIXED AND VALIDATED ON A41/API31 AND TABLET/API33**: terminal lifecycle now notifies Host ownership after capacity release; fragmented self-ADB PID handling is fixed. [Post-Disconnect report](PostDisconnectHostLifecycleReliability.md) records A41 -> S9 `10/10` under one Host enable, S7 alternate Client, abnormal Client loss, `2/2` after app restart, and a later Tablet -> S9 `5/5` under one Host enable. An exploratory Tablet false negative came from a UI harness checking before bounded pipeline teardown completed; it remains recorded separately. | S22 post-Disconnect confirmation remains untested. No Host-toggle workaround or new discovery architecture. |
| Long-idle DirectShell Host liveness | Pre-existing stale Available after helper idle expiry; next SystemAudio setup failed `ExactAudioConfigurationUnavailable` | **OWNER-BOUND LIVENESS VALIDATED ON REPORTED PHYSICAL PATHS**: authenticated per-generation owner sockets replace the post-owner inactivity expiry. A41 crossed almost 18 hours and Tablet about 18 minutes with unchanged helper PIDs, then the first normal post-idle Session succeeded. Unexpected helper loss withdrew publication without a Session; normal disable and app-death EOF released helpers. See [liveness/quality report](DirectShellIdleLivenessAndAudioPlaybackQuality.md) for APK boundaries and the preserved exploratory watcher regression. | No full background/Doze/screen-off claim. Hung-helper detection and systematic fixed-duration ownership-versus-expiry audit remain separately scoped; no automatic recovery. |
| MicrophoneAudio production destination | Historical `sink/output`, scoped S22 injection, unmatched explicit-port selection failure | **GLOBAL DIRECTSHELL DEBUG INPUT VALIDATED / RELEASE PRODUCT NOT PROMOTED**: [final C782DD9D evidence](DirectShellGlobalMicrophoneFinalClosure.md), primary MIC3/3, natural MIC/VOICE_COMMUNICATION/CAMCORDER capture-preset routing, physical997Hz, UID NONE, restoration and helper-loss withdrawal. One Session-owned policy independent of decoder. A41 actual AudioService MODIFY_AUDIO_ROUTING-blocked; S7 product adapter unavailable while older-context probe reaches routing permission denial. | Release capability/UX and crash-safe forced priority remain separately unimplemented/unvalidated. Natural routing requires no forced role on Tablet. Valid set/clear/abnormal preference rollback NOT TESTED. No UID fallback/OEM workaround/Root/framework/HAL patch or universal all-app claim. |
| Global microphone injection quality | Authenticated IPC/AudioTrack timing reserve and no-consumer pressure | **CLOSED_ON_EXACT_PRIMARY_PATH / VALIDATED**: [final C782DD9D r2](DirectShellGlobalMicrophoneFinalClosure.md)3/3 consecutive Sessions122.247/121.492/121.393s under one Enable; ordinary MIC72s plus reopened28s, all sampled steady IPC/track/DEBUG rejected/underrun/xrun deltas0. Independent maintenance silence supply, bounded source catch-up/mic receiver scheduling/one-burst admission; no capacity/codec/socket growth.1022 JVM/22+22 native pass. Fixed40ms IPC and80/70ms DEBUG remain separate. | An additional helper-loss test has two initial185/193ms track transients, no recurrence before kill; disclosed latency/rare scheduling stalls/longer soak remain qualification risk. No adaptive policy, release or forced-priority promotion. Earlier failed campaigns are preserved, not retroactively passed. |
| Failed DirectShell consumed-helper replacement | Host Available with helpers0 after replacement bootstrap failure | **WITHDRAWAL PHYSICALLY OBSERVED FOR WIRELESS-DEBUGGING LOSS**: failed PcmSharedRing/InputSession bootstrap reports FAIL_WIRELESS_DEBUGGING_DISABLED, publication withdraws, readiness becomes STALE then USER_ACTION_REQUIRED with fresh generation. [Chronology](MicrophoneConsumerTransitionQuality.md). Focused regression test passes. | Other replacement-failure modes remain unqualified; no same-provider recovery, migration or owner-lifetime redesign. |
| MicrophoneAudio DEBUG playback quality / output | Technical E2E; pacer-only3/3 lifecycle PASS,0/3 quality PASS; kernel ingress burstiness | **FIXED_AND_VALIDATED ON EXACT S9/API29 -> TABLET/API33 DEBUG PATH**: [autonomous investigation](MicrophoneAudioCadenceRootCause.md) proves an inadequate20ms/5ms decoded-playout budget and a separate cold-output startup boundary. Final80ms capacity/70ms reserve with warm output clock completed3/3 Sessions69.902/69.748/69.898s, startup/steady rejection and underrun0, xrun0. Kernel gaps40.9-45.5ms remain. Earlier failed campaigns and the rejected60/50ms candidate are preserved. | Nominal priming adds65ms; no universal latency/network qualification. Tablet human DEBUG output remains unconfirmed; rare stalls beyond80ms, longer clock drift and other routes need separate evidence. This is not Android virtual-input support. |
| SystemAudio playback pressure | Previously observed valid PCM rejection and zero-fill in technical controls | **STILL_OPEN / CAUSE INCONCLUSIVE**: [final bounded C782DD9D control](DirectShellGlobalMicrophoneFinalClosure.md)20s requested Streaming,8s tone, technical/lifecycle PASS. Last local output epoch rejected19440/underrun58/xrun0; earlier epoch9840/96/0 and cumulative207 ring-full blocks show counter resets. Earlier unequal controls remain historical. | Next separate causal jitter/playout quality task. Do not copy microphone reserves, sum reset snapshots as full-run totals, or infer improvement/regression from unequal windows. No new human REMOTE_ONLY claim. |
| Final reverse-input coverage | Some early matrices NOT_TESTED | **PARTLY CLOSED**: physical human S7 -> A41 reverse Input and approximately 5-6 S9 physical interactions yielding 26 protocol events through the controlled Shizuku composite are proven; DirectShell Client-to-Host payload path also has technical E2E evidence in [runtime integration](DirectShellProductionRuntimeIntegration.md). The 26 events are not 26 human taps. | Deliberate human DirectShell reverse-input and broad final topology regression when an operator is present. |
| Normal-user Host/Client and SAS pairing UX | Engineering flows validated, user journey incomplete | **STILL_OPEN**; no current broad normal-user acceptance campaign is documented. | Operator-led end-to-end UX validation with explicit SAS/pairing results. |
| Wi-Fi Direct E2E | Discovery/control pieces exist | **STILL_OPEN** for physical Direct-path group, authenticated Session and media; [FutureRoadmap](../FutureRoadmap.md) does not claim that campaign. | Separately scoped Wi-Fi Direct hardware E2E. |
| Three Wireless Debugging notifications per Host preparation | Newly observed Tablet UX churn | **CLOSED for initial Host preparation on Tablet**: one local ADB/TLS connection now launches the three separately authenticated feature servers. Android system notification enqueues fell from 3 to 1; three silent normal Host Enable/Disable cycles passed. See [connection-notification investigation](DirectShellConnectionNotifications.md). | A new Host enable still needs one system warning; later individual feature replacement may open another connection and remains outside this bounded result. |
| Physical DirectRoot and production root-manager authorization | AOSP API26 lab qualified | **BLOCKED_ON_ROOT_HARDWARE** for physical/OEM and root-manager evidence; laboratory `SINGLE / DIRECT_ROOT` does not imply Magisk or production readiness. [Root Host report](DirectRootHostAdmissionValidation.md). | Obtain an already-rooted physical test device before physical claims. |
| Root-assisted remediation, same-provider recovery, live migration | RFC-006J future design | **PLANNED_FUTURE_RFC / NOT IMPLEMENTED**; no automatic setting changes or post-freeze switch. [RFC-006J](../rfc/RFC-006J-Privileged-Runtime-Orchestration-and-Host-Readiness.md). | Separate design/implementation tasks; no opportunistic rollout. |
| RFC-005J / RFC-005K | Historical roadmap items | **PLANNED_FUTURE_RFC**, not started by this stabilization task. The bounded DirectShell owner-channel fix is not RFC-005K completion. | RFC-005K must audit fixed-duration process/service lifetimes against real ownership, background Host persistence, screen-off, Doze and power policy. Discovery-specific timeout review belongs to the appropriate future discovery/session RFC. |
| Ring pressure, codec/presentation latency and performance | Bounded technical evidence | **PHASE7** measurement/optimization debt, not an excuse to redesign the working media pipeline now. | Dedicated Phase 7 telemetry/performance work. |

Current Shizuku policy is **optional, lazy, per-capability compatibility
provider** under [RFC-006I](../rfc/RFC-006I-Privileged-Capability-Planning-and-Composite-Runtime.md).
It is not the default modern DirectShell provider, not an invisible post-freeze
fallback, and not scheduled for unconditional deletion by an old roadmap line.

## Prioritized queue

- **READY_NOW:** review the exact-path final microphone closure, then perform the separate SystemAudio causal jitter/playout quality investigation. C782DD9D r2 MIC3/3 closes steady consumer-window quality on S9 ->Tablet; earlier failures, bounded initial route transient and latency cost remain documented. Release promotion and optional crash-safe priority stay separate; permission probes are capability data, not an additional portability implementation. Owner liveness at `715b215` and accepted80/70ms microphone budget are not redesigned.
- **ROOT_HARDWARE_BLOCKED:** physical DirectRoot bootstrap, Video, SystemAudio, Input, complete Host and production root-manager authorization evidence.
- **HUMAN_VALIDATION_BLOCKED:** Tablet MicrophoneAudio DEBUG speaker/intelligible-voice confirmation (A41 DEBUG audibility is now confirmed); normal-user Host/Client/SAS UX; final human DirectShell reverse-input/topology matrix; additional audio output routes. Listening requires operator availability. Tablet/A41/S22 SystemAudio routing evidence remains distinct from MicrophoneAudio virtual input.
- **PLANNED_RFC:** RFC-005J, RFC-005K, root-assisted remediation, same-provider recovery and live provider migration.
- **PHASE7:** physical-display/E2E latency instrumentation and cross-device clock provenance per [measurement contract](LegacyRenderedFrameObservability.md), long-running media resource/pressure analysis and optimization.
