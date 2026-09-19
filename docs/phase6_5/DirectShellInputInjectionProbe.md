# DirectShell Privileged Input Injection Probe

## Scope

This Phase 6.5 DEBUG-only feasibility probe tests whether a Warpnect-owned
`app_process` server running as Android shell can use the existing Warpnect
input injection implementation. It does not change Input Payload V1, WNCP,
Session behavior, Shizuku production gateways, Video, SystemAudio, or the
production Host input selector.

```text
Normal Warpnect DEBUG receiver
  -> authenticated bounded loopback control request
  -> Warpnect DirectShell app_process server (UID 2000)
  -> existing ReflectivePrivilegedInputManagerApi
  -> existing AndroidInjectedEventFactory + InputInjectionStateTracker
  -> Android input subsystem
  -> foreground DEBUG InputSessionTargetActivity
```

The loopback channel is an experimental control plane only. It is not proposed
as the production Input hot path.

## Automated validation and artifact

The final source gates passed with 625 JVM tests, zero failures, zero errors,
and zero skipped tests:

```text
:app:ktlintCheck                         PASS
:app:lintDebug                           PASS (0 errors; 75 existing warnings)
:app:testDebugUnitTest --rerun-tasks     PASS
:app:assembleDebug                       PASS
:app:assembleDebugAndroidTest            PASS
:app:packageDirectShellProbeDex          PASS
```

The hardware matrix used the resulting DEBUG APK:

```text
SHA-256: 3A0477E03E23265317416EDA0A999297D2C2DF925A43362C16610A2D4984C942
Size:     29,117,959 bytes
Built:    2026-09-19 20:45:50 UTC
```

No native source changed, so native CTest was not rerun for this DEBUG-only
probe.

## Current production input architecture

The existing path is:

```text
Input Payload V1
  -> Host input-event reconstruction/controller
  -> AndroidInputInjectionController
  -> ShizukuInputInjectionGateway
  -> PrivilegedInputInjectionUserService
  -> ReflectivePrivilegedInputManagerApi
  -> AndroidInjectedEventFactory + InputInjectionStateTracker
  -> InputManagerGlobal, or InputManager fallback
  -> Android input subsystem
```

`AndroidInputInjectionController` owns the normal-process lifecycle facade.
`ShizukuInputInjectionGateway` owns UserService bind, Binder control calls, and
the privileged-process dependency. `PrivilegedInputInjectionUserService` owns
the same resolver, factory, and state tracker used by this probe. Therefore
Shizuku is the bootstrap/control-plane provider and the privileged execution
location; it is not intrinsic to event construction, hidden-API resolution, or
injection semantics.

## Probe design and security

The probe adds a DEBUG-only `WarpnectPrivilegedInputProbeServer` and a normal
app `DirectShellInputProbeReceiver`. The server is launched by `app_process`
and accepts only bounded commands: prepare, key sequence, touch gesture,
pointer, joystick, reset, diagnostics, and shutdown.

It reuses the established DirectShell framing: protocol magic/version,
bounded frames, explicit request IDs, and a fresh per-launch HMAC-SHA-256
secret. Secrets are not logged. The app receiver retains the secret only for
its bounded request and clears its byte array afterwards. The server reports
one ordered response per request and rejects malformed or unauthenticated
frames through the existing protocol path. There is no Shizuku dependency or
fallback in this probe.

The engine reuses the production `ReflectivePrivilegedInputManagerApi`,
`AndroidInjectedEventFactory`, and `InputInjectionStateTracker`; it does not
create a third Android input implementation. The server validates it is really
`Process.SHELL_UID` before accepting control requests.

## Target and test operations

`InputSessionTargetActivity` is a DEBUG-only foreground target. It emits
bounded event records containing a monotonically increasing target sequence,
event kind, source, synthetic key scan code, coordinates where relevant, and
event time. The probe uses:

```text
Key:       A down -> A up, synthetic scan code 22222
Touch:     DOWN(160,220) -> MOVE(420,520) -> UP(420,520)
Pointer:   mouse hover at (300,360)
Joystick:  left stick (0.5,-0.5)
Reset:     B down -> production state-tracker reset -> synthetic B up
```

The same target proves framework success is not being mistaken for event
delivery. Touch and key soaks run 100 ordered operations each with no retries.

## Hardware evidence

All hardware uses the DEBUG probe DEX built from this branch.

| Device | API | Shell bootstrap | Injection resolver | Key | Touch | Pointer | Joystick | Reset |
|---|---:|---|---|---|---|---|---|---|
| Aocwei X700_EEA tablet | 33 | PASS | `InputManager` fallback | PASS | PASS | PASS | PASS | PASS |
| Samsung SM-S901B S22 | 36 | PASS | `InputManagerGlobal` | PASS | PASS | PASS | PASS | PASS |
| Samsung SM-A415F A41 | 31 | PASS | `InputManager` fallback | PASS | PASS | PASS | PASS | PASS |

### Tablet, Android 13 / API33

The server ran as `pid=12021`, `uid=2000`, `gid=2000`,
`uid_kind=SHELL`, `selinux_context=u:r:shell:s0`, API 33. The shell context
strategy was `ACTIVITY_THREAD_SYSTEM_MAIN_WITH_THREAD_LOOPER` and reported
`package_name=com.android.shell`, `op_package_name=com.android.shell`,
`attribution_uid=2000`, `attribution_package=com.android.shell`, and
`shell_package_uid=2000`.

The first framework candidate, `InputManagerGlobal`, was unavailable on this
runtime. The existing production fallback, `InputManager`, resolved with all
needed capabilities: async injection, wait-for-result diagnostics, and display
targeting. This is a framework-resolution difference, not a new Tablet-specific
implementation or device rule.

The normal-app initiated run reported:

```text
key_sequences=102      touch_gestures=101
key_submitted=203      touch_submitted=303
pointer_events=1       joystick_events=1
reset_requests=1       synthetic_key_ups=1
synthetic_joystick_neutral_events=1
active_pressed_keys=0  active_touch_streams=0
last_error=None
```

The target received the expected initial ordered records: key down/up, touch
down/move/up, pointer, joystick, B down, joystick neutral, and synthetic B up.
The two 100-operation soaks passed: 100/100 key sequences and 100/100 touch
gestures, with no missing release, ordering corruption, server crash, or
injection error. The final bounded normal-app request times were 51 ms prepare,
31 ms key, 36 ms touch, 14 ms pointer, 15 ms joystick, 35 ms reset, 749 ms for
the 100-key batch, and 466 ms for the 100-touch batch.

Shizuku Manager 13.6 was deliberately left unchanged. No Shizuku UserService
was bound or used by the probe. Consequently the result demonstrates
`DIRECTSHELL_INPUT_BYPASSES_SHIZUKU_USERSERVICE_REGRESSION` for the tablet:
the known Shizuku pre-Binder `LoadedApk.makeApplication` issue is not on this
DirectShell injection path.

### S22, Android 16 / API36

The server ran as `pid=26180`, `uid=2000`, `gid=2000`,
`uid_kind=SHELL`, `selinux_context=u:r:shell:s0`, API 36. It reported the same
shell-oriented context and attribution identity as the Tablet.

`InputManagerGlobal` resolved directly; the fallback was not attempted. The
normal-app initiated run reported the same final accounting:

```text
key_sequences=102      touch_gestures=101
key_submitted=203      touch_submitted=303
pointer_events=1       joystick_events=1
reset_requests=1       synthetic_key_ups=1
synthetic_joystick_neutral_events=1
active_pressed_keys=0  active_touch_streams=0
last_error=None
```

The target observed two synthetic A key sequences plus 100 soak sequences,
101 exact touch triplets at the configured coordinates, pointer delivery,
the `(0.5,-0.5)` joystick event, and reset's B-up/neutralization. This S22 had
unrelated physical joystick traffic while the target was foreground; synthetic
records were identified by scan code `22222`, the configured touch coordinates,
and the direct server/target time interval. It does not affect the exact
synthetic operation counts above.

Normal-app request times were 10 ms prepare, 25 ms key, 25 ms touch, 9 ms
pointer, 5 ms joystick, 28 ms reset, 292 ms for 100 keys, and 466 ms for 100
touches. These are coarse control-plane observations only, not a latency
benchmark.

### A41, Android 12 / API31

The final validation used Samsung A41 `SM-A415F`, API 31, build fingerprint
`samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys`. The
DirectShell server ran as `pid=15699`, `uid=2000`, `gid=2000`,
`uid_kind=SHELL`, and `selinux_context=u:r:shell:s0`. It reported the same
`com.android.shell` package, op-package, and attribution identity as the
Tablet and S22.

`InputManagerGlobal` was unavailable and the existing `InputManager` fallback
was selected. The full normal-app initiated run passed: 102 key sequences,
101 touch gestures, one pointer event, one joystick event, and one reset.
It submitted 203 key events and 303 touch events, delivered the expected 204
synthetic A key records and 101 exact touch triplets to the target on display
0, and ended with zero active pressed keys and touch streams. Reset emitted
one synthetic key up and one joystick-neutral event. The server released its
endpoint and exited with code 0. Bounded normal-app request times were 57 ms
prepare, 18 ms key, 18 ms touch, 10 ms pointer, 9 ms joystick, 24 ms reset,
630 ms for 100 keys, and 371 ms for 100 touches.

## Display targeting observation

The injector applies production display targeting with `display_id=0`; both
resolvers reported `display_targeting_supported=true`. The foreground target
received every synthetic event and now reports its public View display as
`target_display_id`. This deliberately establishes the actual delivery
surface without reflecting a restricted `InputEvent` API.

## Ordering, reset, and lifecycle

Each key and touch command is injected sequentially by the server and target
sequence records preserve the specified order. The reset test deliberately
leaves B down then invokes the existing `InputInjectionStateTracker` reset.
Both devices delivered the synthetic B up and joystick-neutral event and
finished with zero active pressed keys and zero active touch streams.

Both final runs performed authenticated shutdown. The server released its
loopback endpoint and exited with code 0; the harness found no remaining shell
server. No production Shizuku UserService was created by either probe.

Two earlier Tablet harness invocations were classified
`INVALID_HARNESS_PRE_SERVER_STALL`: `am start -W` waited on an already-foreground
target before a server was launched. They executed no DirectShell injection and
do not count as product pass or failure. The target now emits readiness on
`onNewIntent`, and the final counted Tablet run passed.

## Scope and implication

FACT: A Warpnect-owned `app_process` process at shell UID 2000 can invoke the
existing injection resolver/factory/tracker and deliver real ordered input to a
normal Warpnect Activity on API33 and API36 without Shizuku input execution.

INFERENCE: The privileged execution seam can migrate from the Shizuku
UserService to DirectShell without changing Input Payload V1. This has not yet
migrated Host Session ownership, production capability qualification, or the
Input hot-path IPC; those are a separate integration task.

UNKNOWN: API31 repeat coverage and legacy API29/API26 behavior. The latter is
outside DirectShell scope and belongs to the later legacy/root investigation.

PROPOSAL: Integrate the DirectShell source at the existing Host input gateway
seam, retain the existing payload/reconstruction/state semantics, then rerun
the previously blocked modern Host readiness and Session compatibility tests.

## Files introduced for the probe

```text
app/src/debug/java/io/warpnect/debug/directshell/
  DirectShellInputProbeClient.kt
  DirectShellInputProbeEngine.kt
  DirectShellInputProbeReceiver.kt
  DirectShellInputProbeRequest.kt
  WarpnectPrivilegedInputProbeServer.kt
app/src/debug/java/io/warpnect/debug/input/InputSessionTargetActivity.kt
tools/directshell/Run-DirectShellInputInjectionProbe.ps1
app/src/test/java/io/warpnect/debug/directshell/DirectShellInputProbeRequestTest.kt
```

The only existing code changed is DEBUG-only protocol/manifest/target support.
No main-production source or native source is changed.

## Decision

`DIRECTSHELL_INPUT_INJECTION_FEASIBLE`
