# Tablet DirectShell Host Preparation Reliability (2026-09-28)

## Scope and baseline

This is a non-Root, Host-only stabilization campaign on physical Aocwei
X700_EEA, Android 13/API33, arm64-v8a, fingerprint
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`.
No Session, audio tone, Root command, flash, wipe, or system modification was
used; installing the debug APK and normal UI interaction were the test actions.
The local predecessor checkpoint is `591588d`. The first diagnostic APK was
34,090,037 bytes, SHA-256
`018391278DED6E96FF0B7B726F2D04FD1DBD9DE2A5E19241B43CDE8F540AD693`.
The source-frozen final APK is 34,090,400 bytes, SHA-256
`87531FE0D250D9963FAA7ECFF9AD6A45D468A313627045F4F06A436CAE72E1B0`;
the installed Tablet `base.apk` returned the same digest. Both were installed
with `adb install -r`, preserving application data. Desktop
ADB observed the app and tapped its normal Enable/Disable Host UI; it did not
launch `app_process` or provide privilege to Warpnect.

## Historical 1/2 observation

The earlier optional control in [DirectRootVideoQualification.md](DirectRootVideoQualification.md)
reported one normal `Host: Available` admission and one
`FAIL_PRIVILEGED_RUNTIME_PREPARATION`. Its main application log artifact is
zero bytes. The broad companion log does not contain an attributable Warpnect
preparation trace. The first failing discovery/TLS/artifact/launch/HMAC boundary
cannot be reconstructed. **Historical failure classification: INCONCLUSIVE.**
The current task initially observed `adb_wifi_enabled=0`; the operator manually
enabled Wireless Debugging before the counted campaign. That current OFF state
is not evidence that Wireless Debugging caused the historical failure.

## Preparation path and observability

Normal Enable Host joins `BackgroundHostReadinessCoordinator`, loads three
compatible cached cells, performs late revalidation, then
`DirectShellRuntime.acquireForHost` launches separate PCM-ring, Input and Video
servers through `DirectShellSelfBootstrapController`. Each launch verifies the
trusted `_adb-tls-connect` service, local ADB/TLS, shell UID, installed APK,
temporary generation secret, `app_process` wrapper, HMAC readiness, negative
authentication, and final UID2000 identity. Admission then freezes the
RFC-006I plan before publishing Host availability.

| Boundary | Code owner |
| --- | --- |
| Normal Host action and UI state | `SecureSessionScreen`, `AndroidSecureSessionComposition` |
| Single-flight background/Host readiness | `BackgroundHostReadinessCoordinator`, `AndroidHostReadinessWork` |
| Debug runtime and feature ownership | `DirectShellRuntime`, `DirectShellRuntimeAutoRegistrationProvider` |
| Trusted service and endpoint resolution | `DirectShellSelfAdbKeyStore`, `DirectShellSelfAdbEndpointDiscovery` |
| Local ADB/TLS | `DirectShellSelfAdbConnectionManager` |
| Secret, fixed launch, HMAC and shutdown | `DirectShellSelfBootstrapContract`, `DirectShellSelfBootstrapController` |
| Capability acquisition and freeze | `AndroidHostPrivilegedCapabilityAdmission` |

DEBUG telemetry now emits bounded `DIRECT_SHELL_PREPARATION` BEGIN/PASS/FAIL
with stage and elapsed milliseconds; launch-local stages include the generation.
`DIRECT_SHELL_RUNTIME_PREPARATION` correlates feature results by attempt, and
release logs the runtime generation. It does not log keys, HMAC material,
pairing codes, command contents, or PCM. No retry, timeout, protocol, or
capability policy was changed.

## Diagnostic campaigns

Campaign A: **10/10** consecutive normal UI Enable Host -> `Host: Available`
and `Waiting for clients` -> normal Disable Host -> `Ready`. Each post-disable
check found zero Warpnect `app_process` helpers and zero
`warpnect-directshell-secret-*` files. Its cycle 2 reached Host Available but
the PowerShell observer stopped before Disable Host because it tried to assign
the reserved `$Host` variable. Disable Host was completed manually through
the same normal UI. This is a **TEST_HARNESS_DEFECT**, not a product failure;
the cycle remains counted. The Tablet's small logcat ring retained full traces
only for the last two cycles, so the earlier UI/process observations are not
misrepresented as full stage logs.

After normal Host disable, an app-process restart changed PID 15957 -> 18953.
Background startup loaded three valid capability records and prepared advisory
`SINGLE / DIRECT_SHELL` without launching helpers. Campaign B then ran on the
same final APK, saving a filtered log immediately after every cycle. Its first
cycle completed normally, but the observer stopped after saving its log because
it tried to assign PowerShell's reserved `$PID`; this second harness error did
not interrupt Host disable. The command resumed at cycle 2, not by repeating
cycle 1. All ten B cycles passed without product retry:

| B cycle | Readiness generation | Runtime generation | Three feature probes | Host / disable | Begin-to-end s | App FD after disable |
| ---: | ---: | ---: | --- | --- | ---: | ---: |
| 1 | 2 | 1 | PASS | PASS / PASS | 20.9 | 187 |
| 2 | 4 | 2 | PASS | PASS / PASS | 21.3 | 186 |
| 3 | 6 | 3 | PASS | PASS / PASS | 17.0 | 182 |
| 4 | 8 | 4 | PASS | PASS / PASS | 16.9 | 182 |
| 5 | 10 | 5 | PASS | PASS / PASS | 17.1 | 182 |
| 6 | 12 | 6 | PASS | PASS / PASS | 16.8 | 184 |
| 7 | 14 | 7 | PASS | PASS / PASS | 16.8 | 180 |
| 8 | 16 | 8 | PASS | PASS / PASS | 17.0 | 188 |
| 9 | 18 | 9 | PASS | PASS / PASS | 17.1 | 196 |
| 10 | 20 | 10 | PASS | PASS / PASS | 16.8 | 187 |

Every saved B-cycle log contains one runtime READY, one runtime RELEASED,
three feature-server PASS records (each with a fresh server generation), zero
stage failures, and cached `SINGLE / DIRECT_SHELL` preparation with late
revalidation. After each cycle: zero helper processes and zero temporary
bootstrap secret files. The log files are generated evidence under
`app/build/reports/tablet-directshell-reliability/verified-cycle-*.log`.
They are not repository source assets.

A separate **1/1 diagnostic plan-audit control** recorded
`privileged_plan_type=SINGLE`, Video/SystemAudio/Input all `DIRECT_SHELL`,
`privileged_shizuku_deferred ... shizuku_started=false`, Host Available, and
release of runtime generation 11. It returned to Ready with no helper or
secret. Its generated trace is `plan-audit.log` in the same build-report
directory. This control is not silently folded into either 10/10 campaign.
After these runs, two result checks were moved inside their traced stages
and trusted ADB identity gained its own stage. These changes affect failure
telemetry but not bootstrap control flow. The APK hash therefore changed;
the two campaigns above remain exploratory evidence, not final-APK evidence.

## Exact final APK campaign

After all software gates passed, the final APK was installed without clearing
app data. Its startup process was PID 22755, loaded three cached `QUALIFIED`
records and prepared advisory `SINGLE / DIRECT_SHELL` with zero privileged
helpers. The exact final APK then passed **10/10 consecutive normal UI**
Enable Host -> Host Available / Waiting for clients -> Disable Host -> Ready
cycles. No force-stop, reboot, hidden retry, or audio tone occurred between
cycles. Each `final-cycle-*.log` contains three role-specific feature-server
PASS records with fresh server generations; one runtime READY and RELEASED;
`privileged_plan_type=SINGLE` with Video, SystemAudio and Input all
`DIRECT_SHELL`; `privileged_shizuku_deferred` and `shizuku_started=false`;
and zero preparation-stage failures. Each final cycle also logged three
trusted ADB endpoint resolutions, three resolver closes, three HMAC-ready
handshakes and three final shell-identity PASS results.

| Final cycle | Readiness generation | Runtime generation | UI admission / disable | Begin-to-end s | App FD after disable |
| ---: | ---: | ---: | --- | ---: | ---: |
| 1 | 2 | 1 | PASS / PASS | 21.1 | 187 |
| 2 | 4 | 2 | PASS / PASS | 16.1 | 186 |
| 3 | 6 | 3 | PASS / PASS | 17.2 | 184 |
| 4 | 8 | 4 | PASS / PASS | 16.9 | 184 |
| 5 | 10 | 5 | PASS / PASS | 16.9 | 184 |
| 6 | 12 | 6 | PASS / PASS | 16.8 | 188 |
| 7 | 14 | 7 | PASS / PASS | 17.1 | 186 |
| 8 | 16 | 8 | PASS / PASS | 17.1 | 187 |
| 9 | 18 | 9 | PASS / PASS | 16.8 | 195 |
| 10 | 20 | 10 | PASS / PASS | 16.7 | 182 |

All ten final post-disable inspections found zero Warpnect `app_process`
helpers and zero temporary secret files. The final app PID remained 22755.
The filtered traces are generated evidence under
`app/build/reports/tablet-directshell-reliability/final-cycle-*.log`.

## Resources and limits

Runtime release reported `leases=0 warm=3` for these Host-only admissions: no
remote Session consumed the prewarmed servers or started media. A baseline app
FD count of 170 after process restart rose with framework warmup, then varied
between 180 and 196 after the ten B cycles and ended at 187. On the exact
final APK, FDs began at 162 after install, varied from 182 to 195 after
ordinary Host disable, and ended at 182. An exploratory sample after the
earlier plan audit had 195 descriptors: 62 anon-inode, 15 socket, 16 pipe,
23 device, 44 framework, 5 `/proc`, 2 `/data`, and 28 other (including
graphics dma-bufs). This is **not monotonic Warpnect-owned FD growth** in the
bounded run; it is not an indefinite leak-freedom claim. No bootstrap secret
file or DirectShell helper remained after teardown. Historical files under
`/data/local/tmp` were preserved, not mistaken for active generations.

The Tablet's Android notification is `Wireless debugging connected`. The
current runtime opens and closes one app-originated ADB connection per feature
server, so three system notifications may appear in sequence during Host
preparation. They are not three pairing ceremonies. Reducing notification
churn is an optional UX/orchestration follow-up, not a correctness prerequisite
and not changed in this task.

Wireless Debugging OFF was observed before the operator enabled it, but no
controlled Enable Host attempt while OFF was run. Thus the explicit disabled
failure-control result is **NOT TESTED**. No Shizuku fallback, Root command,
audio tone, remote Session, or physical Root validation occurred here.

## Conclusion

The current exact build is stable for **10/10** ordinary Host admissions with
per-cycle frozen-plan evidence. The preceding diagnostic APK passed two
separate 10/10 campaigns and a 1/1 plan audit. The prior 1/2 failure's first
boundary remains unknowable from preserved historical telemetry; no product
root cause or code fix is claimed. New staged logs make a future recurrence
diagnosable without treating this successful run as a retroactive explanation.
