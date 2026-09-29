# Post-Disconnect Host Lifecycle and Rediscovery (2026-09-28)

## Scope and baseline

This is a normal DirectShell Host Session test, not an audio-routing, Root, or
Wi-Fi Direct qualification. The prior SystemAudio built-in-speaker result remains
`REMOTE_ONLY`; no tone was played in this campaign. The preceding routing report
was checkpointed locally as `ea488d6`. This work remains uncommitted for review.

| Role | Hardware | API | Fingerprint |
| --- | --- | --- | --- |
| Primary Host | A41 SM-A415F | 31 | `samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys` |
| Primary Client | S9 SM-G960F | 29 | `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys` |
| Alternate Client | S7 SM-G935F | 26 | `samsung/hero2ltexx/hero2lte:8.0.0/R16NW/G935FXXU8EVH2:user/release-keys` |

The second connected A41 was API30 with Wireless Debugging off and was not
used as a DirectShell Host. Tablet/API33 and S22/API36 were not connected for
this campaign; their earlier routing/visible-video evidence is unchanged.

## Original failure and first boundary

With the previous APK, the A41 reached `Waiting for clients`; S9 discovered it,
connected, and reached `Streaming`. After the normal S9 Disconnect, S9 returned
to `Ready`, but A41 remained `Running` while its capability label still said
`Host: Available`. A new S9 discovery found no Host. This reproduced the
historical Tablet/S22 symptom without a Host toggle.

The first incorrect boundary was **Session completion -> Host coordinator**.
`SessionLifecycleController.finishClose()` stopped capture, ring, Input state,
control and capacity ownership, but never called the existing
`SessionLifecycleRuntimeListener.onClosed()` bridge. The coordinator therefore
kept its `RunningSessionRuntime` and `Running` state. Separately, the Host
availability refresh performed at Session start had consumed the only slot and
withdrawn the advertisement; no post-close refresh republished it. The Client's
lost presence was correct for the occupied slot, not a stale Client cache.

Ownership remains distinct:

| Lifetime | Owner | Post-Disconnect result |
| --- | --- | --- |
| Host intent/readiness | `SecureSessionApplicationController`, Host phase driver | Remains enabled |
| Session and media | `SecureSessionCoordinator`, lifecycle controller, pipeline | Closed for the old token |
| Host presence | `LocalDiscoveryController`, `SessionManagerHostAvailabilityProvider` | Refreshed after capacity release |
| DirectShell feature servers | DEBUG `DirectShellRuntime` | Session leases released; at most one prewarmed server per feature while Host is enabled |
| UI status | Application snapshot of coordinator state | `Running` only for an owned Session; otherwise `Waiting for clients` |

The fix adds `onSessionTerminated` after terminal resource/capacity release.
Fresh-generation handoff and explicit owner `close()` do not emit that event.
The Android Host bridge dispatches `onClosed()` on the serialized control owner.
The coordinator clears the old runtime and registry lease, invalidates its token,
returns to `Discovering`, and refreshes availability. It does not toggle Host or
blindly restart discovery. A late old-token callback cannot close Session N+1.

## Separate reacquisition failure

The first diagnostic APK with the lifecycle fix passed four consecutive normal
Sessions under one Host enablement. Cycle 5 failed at Host
`InputConfigurationUnavailable`; the S9 `SetupTimeout` was downstream. Host
logs located the earlier DirectShell Input replacement failure at
`invalid_launch_process_id`, while its startup log already contained
`WRAPPER_START`. `readLaunchOutput()` had accepted a partial `pid=` stream
fragment before its newline; the strict PID parser then rejected that fragment.
This was a pre-existing DEBUG self-ADB bootstrap defect exposed by repeated
Session replacement, not an Input framework or Shizuku failure. The reader now
waits for a complete result line; the parser also rejects unterminated lines.
No arbitrary sleep or hidden retry was added. Cycle 5 remains a FAIL in that
exploratory run; the final campaign below began with a newly built APK.

## Final A41 -> S9 campaign

Final APK SHA-256:
`A3FD45535FD1C74417A11C6E43EED979198ED816B32245519EC1D6928D3DA884`.
The A41 enabled Host once. Each cycle performed one Find Hosts, one Connect,
reached S9 `Streaming` and A41 `Running`, used the real Client Disconnect, and
ended with S9 `Ready` and A41 `Waiting for clients`. No Host toggle, Connect
retry, force-stop, or Wireless Debugging toggle occurred within the campaign.

| Session | Result | Elapsed, including UI observations |
| --- | --- | ---: |
| 1 | PASS | 30.9 s |
| 2 | PASS | 29.7 s |
| 3 | PASS | 30.1 s |
| 4 | PASS | 31.0 s |
| 5 | PASS | 29.6 s |
| 6 | PASS | 31.0 s |
| 7 | PASS | 31.9 s |
| 8 | PASS | 30.8 s |
| 9 | PASS | 30.1 s |
| 10 | PASS | 31.0 s |

`10/10` normal Sessions passed under one continuous Host enablement. These
elapsed values include ADB/UI-dump overhead and are not media latency metrics.
The actual frozen Host plan was `SINGLE / DIRECT_SHELL`; cached advisory evidence
was late-revalidated and frozen at Host admission. No Root or Shizuku provider was
selected for Video, SystemAudio or Input. Distinct feature-server generations
were logged as consumed servers were replaced; each new Client handshake created
a fresh Session rather than reusing the previous terminal runtime.

After cycle 10, S7/API26 independently discovered the still-enabled A41,
reached `Streaming`, and normally disconnected; A41 returned to waiting. This
is one alternate-Client control, not a 10-cycle S7 qualification. The test did
not establish human-visible S7 video or audible SystemAudio.

## Abnormal loss, restart, and resources

In a separate S9 Session, only the S9 app process was force-stopped. A41 entered
the existing suspended/reconnect path and returned to waiting after bounded
recovery expiry; LAN registration became active again. This is safe teardown,
not same-provider recovery success or provider migration.

At Session close the A41 logs showed `INPUT_SESSION_RESET`,
`PCM_RING_AUDIO_STOP`, app ring release, `VIDEO_MIRROR_STOPPED`, and encoder
release. After cycle 10, three shell-UID feature processes were present because
Host mode intentionally retains one prewarmed Input, PCM-ring and Video server;
this is Host-level readiness, not ten retained Session producers. Normal Disable
Host returned the UI to `Ready` and left zero matching feature helpers, zero
`warpnect-directshell-secret-*` files and zero bootstrap logs under
`/data/local/tmp`. FD totals were not measured, so no FD leak-free claim is made.

With Host disabled, the A41 app process was restarted. It returned to `Ready`
with zero feature helpers; cached DirectShell evidence was advisory, not a live
Session. A new normal Host enable then completed two more S9 Session/Disconnect
cycles (`2/2`), with rediscovery under that new Host enablement. Final Disable
Host again left zero helpers and secret files.

## Limits and deferred decisions

The A41 result is strong for the exact tested build and devices; Tablet and S22
post-Disconnect confirmation remain untested here. The older A41 missing
`_adb-tls-connect` advertisement remains a separate external Wireless Debugging
precondition. This campaign did not toggle that setting. No audible tone,
physical Root, protocol redesign, screen policy, or microphone work occurred.

The accepted SystemAudio mode is `REMOTE_ONLY` for the tested built-in-speaker
tier; user-selectable `HOST_AND_REMOTE` remains future product work. Host display
`KEEP_ON`, `PRIVACY_BLANK`, and true `SCREEN_OFF` require a separate feasibility
decision before or during RFC-005K. MicrophoneAudio E2E remains a separate task.

Classification for the original lifecycle failure and fragmented PID response:
`PREEXISTING_PRODUCT_DEFECT`. The normal A41 post-Disconnect path is now
validated; no Tablet/S22 result is inferred from it.

## Automated regression and gates

New focused JVM coverage checks terminal notification exactly once for a
duplicated remote Disconnect, suppression on fresh-generation handoff and
explicit owner close, two consecutive Host Sessions without stopping discovery,
late old-token rejection, and split `pid=` output before newline. An initial
test-only Host fixture omitted its required capacity owner and failed at
`start()`; the fixture was corrected before the final run. It was not a product
or hardware failure.

The final `:app:testDebugUnitTest --rerun-tasks` report contains 939 tests in
149 suites, with 0 failures, 0 errors and 0 skipped. `git diff --check`,
`:app:ktlintCheck`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`, and `:app:packageDirectShellProbeDex` passed.
No native source changed, so native CTest was not rerun. Android instrumentation
was assembled, not executed on the devices.

## 2026-09-29 Tablet/API33 cross-device gate

The same final APK (`A3FD45535FD1C74417A11C6E43EED979198ED816B32245519EC1D6928D3DA884`)
was installed on the Aocwei X700_EEA Tablet (Android 13/API33,
`Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys`)
and S9/API29. Wireless Debugging was already enabled; no setting was toggled.
The Tablet entered normal `Waiting for clients` / `Host: Available` after one
Enable Host. No manual DirectShell arm, Root or Shizuku fallback was used.

An exploratory two-cycle observation passed cycle 1 but marked cycle 2 FAIL
because the UI harness checked `Running` before synchronous Session pipeline
teardown finished. The subsequent check reached `Waiting for clients` and LAN
logged `host_registration_active`. Logs located the interval in serial
DirectShell Input, PCM-ring and Video replacement during `pipeline.close()`;
these took roughly three seconds each. Classification of this exploratory
false negative: `TEST_HARNESS_DEFECT` (premature observation), not a second
terminal-ownership defect. It is not counted as a pass in the campaign below.

After normal Disable Host and a new single Enable Host, a fresh counted
campaign used one Find Hosts, one Connect and one normal Client Disconnect per
cycle. The harness observed for up to 30 seconds after Disconnect; it did not
retry Connect, restart the app or toggle Host/Wireless Debugging between cycles.

| Cycle | S9 found Tablet | Active | After Disconnect | Observation elapsed* | Result |
| --- | --- | --- | --- | ---: | --- |
| 1 | `Z138_EEA` | S9 `Streaming`, Tablet `Running` | S9 `Ready`, Tablet `Waiting for clients` / `Host: Available` | 15.5 s | PASS |
| 2 | `Z138_EEA` | Same | Same | 15.6 s | PASS |
| 3 | `Z138_EEA` | Same | Same | 15.7 s | PASS |
| 4 | `Z138_EEA` | Same | Same | 15.6 s | PASS |
| 5 | `Z138_EEA` | Same | Same | 15.5 s | PASS |

*Elapsed includes bounded waiting and UI dump overhead, not pure teardown
latency. Each new discovery reached a new Session; the old terminal state was
not reused. The retained logs show DirectShell Input, PCM-ring and Video
feature generations being replaced and `host_registration_active` after
Session teardown. For the final Session they show `INPUT_SESSION_RESET`,
`PCM_RING_AUDIO_STOPPED`, `VIDEO_MIRROR_STOPPED`, and
`shizuku_input_producer=false`, `shizuku_audio_producer=false`,
`shizuku_video_capture=false`. Host availability requires the frozen admission
plan; the live feature logs identify DirectShell for all three providers. The
exact plan-selection log for this campaign had rolled out of the device buffer,
so `SINGLE / DIRECT_SHELL` is inferred from that complete provider set rather
than quoted from a retained freeze line.

After the fifth cycle, normal Disable Host returned the Tablet to `Ready`.
`ps -A` showed only the ordinary `io.warpnect` app process, with no matching
`app_process` helper; `/data/local/tmp` had zero matching
`warpnect-directshell-secret-*` files. No global FD count or human audio/video
acceptance is claimed by this lifecycle gate. S22 post-Disconnect confirmation
remains untested.
