# S9 / Tablet peer-link recovery - 2026-10-02

> Final follow-up: [C782DD9D primary campaign](DirectShellGlobalMicrophoneFinalClosure.md) completes three consecutive Sessions above121s on the restored S9/Tablet link. Exact initiating driver/AP/TDLS mechanism remains INCONCLUSIVE; no product Wi-Fi recovery or router/security modification is added.

## 2026-10-03 Continuation

At resume, restored Wireless Debugging1 and peer connectivity: bidirectional
IPv4 ping3/3 and all eight independent TCP/UDP token/echo controls pass without
any router/Wi-Fi/security corrective action. The later three-cycle microphone
functional campaign completes. A separate diagnostic then stalls before required
UI Streaming; local428022.243 Wi-Fi disconnect,428022.255 locally generated
reason5,428022.302 Bluetooth service death/signal9,428022.314 Android disables
adbwifi,428022.505 typed failed preparation,428022.611 Host publication withdrawn.
Do not reverse that observed order or infer the initiating actor. Wi-Fi later
rejoins192.168.1.57 but Debug wireless remains0. No privileged restoration or
product recovery is introduced. [Current report](MicrophoneConsumerClockContinuity.md)
preserves the earlier media stall, platform boundary and all quality failures.

## Scope and repository boundary

Latest [Tablet-return continuation](MicrophoneConsumerTransitionQuality.md):
initial bidirectional IPv4/IPv6 and all eight TCP/UDP controls pass. Later two
primary campaigns are interrupted by renewed link loss; Android observes
Bluetooth SIGKILL and locally generated Wi-Fi reason5 disassociation, disables
Wireless Debugging and Warpnect withdraws publication. Manual Debug wireless
restoration permits a new explicit campaign, not a retroactive PASS. Exact
radio/kill origin remains INCONCLUSIVE; no router/media recovery workaround.
The earlier Tablet-unavailable pause below is historical, not current availability.

This continues the preserved global-microphone quality investigation, not a
new media architecture. Functional
`DIRECTSHELL_GLOBAL_MICROPHONE_INJECTION_VALIDATED` remains accepted; input
quality remains PARTIAL until the consumer-window candidate passes its own
primary physical campaign. A41 is a network control, not a replacement pair.

Branch `codex/directshell-production-runtime-integration`; starting HEAD
`715b2151af2c8ecac165729178266b24e5cae91b`; master
`032b59276a0e01e4674e807a8ffb5b5b86e9150e`; upstream
`9d0b4dce7ea04a82e81778de6296cc1096775d31`. Staging empty; preserved worktree
has 202 tracked changes and 90 untracked entries at directory-collapsed
reconciliation. Existing source/tests/docs, generated native outputs, local
crash logs and unrelated screenshots/attachments were not discarded. The
pre-existing empty `.git/index.lock` remains untouched. No commit or push.

## Initial network state

All three observed Wi-Fi connections use `wlan0`, SSID `FibreBox_X6-162367`,
BSSID `6c:99:61:16:23:65`, 5320 MHz. IPv4 gateway is `192.168.1.1`, observed
gateway MAC `6c:99:61:16:23:67`. All addresses have prefix /24.

| Device | Exact ADB serial | IPv4 | Wi-Fi MAC | Saved network / Android network ID |
| --- | --- | --- | --- | --- |
| S9 / API29 | 23eaec0f12047ece | 192.168.1.4 | 46:af:fe:01:ec:e9 | 89 / 602 |
| Tablet / API33 | Z1382023HGM1895372 | 192.168.1.57 | 5e:42:d8:d0:6d:ad | 3 / 105 before rejoin |
| A41 / API31 | R58N52B32JJ | 192.168.1.36 | 9a:da:f5:2b:f8:d2 | 5 / 100 |

Direct routes use wlan0 and the correct source addresses. Neighbour tables
show the expected peer MACs: no duplicate-IP/wrong-neighbour evidence was
found. Locally administered MACs remained unchanged after the corrective
action; randomized MAC operation is not proven causal. Actual active Wi-Fi
network agents are NOT_VPN; no active VPN agent was observed. Tethering
diagnostics show no active upstream; Tablet UI also reports VPN None and
hotspot off. Same BSSID/subnet does not prove the router's VLAN/isolation
configuration; that configuration was not changed or inspected externally.

Initial IPv4 ping, three packets per test:

| From | Gateway | S9 | Tablet | A41 |
| --- | --- | --- | --- | --- |
| S9 | 3/3 | - | 0/3 | 3/3 |
| Tablet | 3/3 | 0/3 | - | 3/3 |
| A41 | 3/3 | 3/3 | 3/3 | - |

## Transport-level discrimination

A bounded Java shell-process probe binds each device's actual IPv4 and uses
one fixed token (`warpnect-peer-check-v1`) on TCP/UDP port48671. It accepts
only the expected peer, caps reads at128 bytes, has socket timeouts and an
outer process timeout. It is local diagnostic tooling, not a Warpnect media
or privileged-command endpoint. No Session result is used as network proof.

| Direction | TCP token / echo | UDP token / echo |
| --- | --- | --- |
| S9 -> Tablet | NO / NO, connect timeout | YES / NO, reply timeout |
| Tablet -> S9 | NO / NO, connect timeout | NO / NO, timeout |
| A41 -> S9 | YES / YES | YES / YES |
| A41 -> Tablet | YES / YES | YES / YES |

The first proven directional failure is Tablet-to-S9 data/reply delivery.
S9-to-Tablet UDP arrival was possible, but its response did not reach S9.
This is not ICMP-only filtering or an audio/Session defect. The evidence
cannot distinguish Tablet egress, AP forwarding or S9 ingress. AP isolation,
TDLS, driver queues, firmware and router state remain hypotheses, not a
claimed root cause.

## Minimum corrective action and result

With Host disabled and no Session, the Tablet alone was reassociated:

- Begin: `2026-10-02T17:56:44.3065647Z`.
- Explicit exact-serial `svc wifi disable`, two-second pause, `svc wifi enable`.
- Twelve-second association wait; end `17:57:03.5350996Z`.
- Same IP, MAC, SSID and BSSID afterwards; no router/security configuration
  change and no Warpnect automatic Wi-Fi recovery code.

S9 -> Tablet ping 3/3, RTT44.822/91.826/125.080 ms min/mean/max.
Tablet -> S9 ping 3/3, RTT11.578/73.649/116.271 ms.
All eight fresh bounded TCP/UDP tests (both primary directions plus both A41
controls) then received both token and echo, with client/server exit0.
Subsequent S9 -> Tablet ping remained3/3, mean14.914 ms.

**IPv4 peer-link prerequisite: PASS.** This is a reassociation-cleared,
pair-specific environmental forwarding failure. The exact lower-layer
mechanism remains INCONCLUSIVE; reconnect success alone is not root-cause
proof. No product media code was modified to compensate for it.

IPv6 addresses share a /64 and have link-local interfaces. Initial `ping6 -I
wlan0` probes failed locally with `sendmsg: Invalid argument`, including A41
controls; they are TEST_HARNESS_DEFECT, not evidence of IPv6 packet loss.
After correction to `%wlan0` scope syntax, S9 -> Tablet link-local ping
passed3/3. Reverse scoped IPv6 verification remains pending Tablet access.
IPv4 TCP/UDP acceptance does not depend on an unproven IPv6 result.

## Candidate and next physical boundary

An unchanged-source build passed `assembleDebug`, `assembleDebugAndroidTest`
and `packageDirectShellProbeDex` (44s;74 tasks,6 executed/68 up-to-date).
Frozen APK SHA256 remains:

`87EF556C31EE47441B63F3CCDCCB487679E8C47FF8B13A3920180063218A6044`.

The consumer-window guard, fixed80ms IPC age, Session-owned no-UID policy,
maintenance silence and separate80/70ms DEBUG playout are unchanged. Opening
flush and lowered start-threshold experiments remain reverted. No new
microphone Session has been counted in this network-recovery stage.

Android turned Wireless Debugging off during the explicit Wi-Fi rejoin.
Normal Settings navigation was used to restore the existing prerequisite,
not a privileged settings write. Before reaching the Wireless Debugging
switch, Tablet serial Z1382023HGM1895372 disappeared from the USB inventory;
its Wi-Fi IP still responds. No Tablet `_adb-tls-connect` service is currently
advertised. USB reconnection / normal Wireless Debugging restoration was
requested. The operator then confirmed that the Tablet was unavailable and
had been replaced by S22, and authorized pausing until the Tablet returns.
Physical work stopped: S22 was not acted on or substituted for qualification.
No microphone Session was counted, no Host was enabled, and no tone was played.

The new full six-task software-gate rerun was explicitly interrupted at this
pause, before unit tests (`peer-link-continuation-final-gates.txt`). It is
INTERRUPTED, not PASS or a product failure. The completed unchanged-source
three-task build above remains PASS. Previous candidate gates remain998 JVM
tests /160 suites /0 failures/errors/skips, native Debug22/22 and Release22/22;
they are prior evidence, not a newly completed full rerun. No production native
or application/test source changed in this continuation.

## Preserved diagnostics and anomalies

Ignored evidence resides in `app/build/reports/peer-link-initial-*`,
`peer-link-java-initial-*`, `peer-link-java-after-tablet-rejoin-*` and
`peer-link-current-candidate-build.txt`. No raw audio was captured.

- Old Toybox UDP listener failed `nc: listen` on S9; two owned, hung local
  ADB clients were explicitly terminated after command-line verification.
  TEST_HARNESS_DEFECT; replaced with the bounded Java probe. Failure logs
  remain preserved; they are not substituted as network evidence.
- UID2000 `ip link show` / some socket diagnostics have netlink permission
  limitations; route/address/neighbour alternatives worked. S9 `cmd wifi
  status` is permission-restricted; `dumpsys wifi` supplied the connection
  state. No privilege escalation was attempted.
- Wi-Fi rejoin disabling Wireless Debugging and the later missing USB
  transport are ENVIRONMENTAL_PRECONDITION boundaries, not microphone quality
  failures. The historical failed quality campaigns remain failed.

Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
remain NOT IMPLEMENTED in RFC-006J. Composite planning remains pre-Session
Complete Host selection, not runtime migration. No frozen plan, authentication,
anti-replay, payload, provider identity or Complete Host invariant was changed.
Portability, priority, stale-budget adaptation and the new SystemAudio control
remain behind the outstanding primary microphone quality gate.

## Resume boundary and classification

- Network: IPv4 bidirectional peer TCP/UDP PASS after one Tablet rejoin;
  precise AP/driver/ingress mechanism INCONCLUSIVE.
- Candidate: unchanged87EF556C; consumer-window physical acceptance NOT RUN.
- Global input: functional VALIDATED token preserved; quality PARTIAL.
- New signal/IPC/queue residence/DEBUG metrics: NOT MEASURED, not zero.
- Fixed80ms IPC decision/adaptive comparison: pending quality gate; unchanged.
- MIC/VOICE_COMMUNICATION/CAMCORDER: original functional evidence preserved,
  no fresh quality/natural-selection campaign in this continuation.
- Portability: A41 previous permission block and S7 previous adapter block
  unchanged; no alternative-provider or priority experiment started.
- Forced priority: NOT TESTED; no preferred routing state was set.
- Failed-replacement health invalidation: source correction remains physically
  unvalidated; no recovery/migration implementation added.
- SystemAudio/Video/Input: no new physical regression in this paused run.
  Previous SystemAudio rejected30720/underrun373/xrun0 remains debt, not PASS
  for quality. No microphone buffer values copied into it.
- Worktree: documentation and ignored bounded network diagnostics only added
  here; inherited microphone/runtime/source/test/generated/unrelated files
  preserved. No local checkpoint created. Ending HEAD equals starting HEAD;
  final staging0/unstaged202/untracked91. `git diff --check` PASS. No new tests
  added; prior XML recount confirms998 tests/160 suites/0 failures/errors/skips.

Current-state documentation updated in this continuation: this report,
DirectShellGlobalMicrophoneInjectionQuality, DirectShellGlobalMicrophoneInjection,
MicrophoneAudioE2EQualification, CurrentNonRootDebt, Phase6.5 README,
FutureRoadmap and AndroidCompatibilityMatrix. Historical numeric results and
accepted tokens are preserved. No Root, Shizuku fallback, OS/framework/vendor
modification, preferred route, generic shell or release promotion.

Exactly one next task: resume the current87EF556C S9 -> Tablet consumer-window
quality acceptance, starting from normal DirectShell admission and one Enable
Host, then a fresh3x>=60s primary campaign. First verify peer connectivity and
Wireless Debugging after the Tablet returns; do not repeat the entire historical
investigation or substitute S22/A41. On a quality failure, stop counting and
continue the already-scoped root-cause work before portability or priority.
