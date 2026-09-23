# DirectShell Input Session Integration

## Scope

This Phase 6.5 DEBUG-only integration connects the proven DirectShell
privileged Input implementation to the existing Host composition seam. It
does not change `Input Payload V1`, SCL, WNCP, Session wire semantics, the
normal Shizuku default, SystemAudio production selection, Video, or native
code.

```text
Client Input Payload V1
  -> existing Host input receiver and AndroidTargetInputMapper
  -> AndroidInputInjectionController
  -> DirectShellInputSessionGateway       [explicit DEBUG override only]
  -> authenticated DirectShell server, shell UID 2000
  -> existing PrivilegedInputInjectionUserService implementation
  -> existing ReflectivePrivilegedInputManagerApi
  -> existing AndroidInjectedEventFactory + InputInjectionStateTracker
  -> Android input subsystem
```

The ordinary path remains:

```text
AndroidInputInjectionController
  -> ShizukuInputInjectionGateway
  -> Shizuku UserService
```

`InputInjectionControllerSelector` uses the DirectShell source only when a
process-local DEBUG receiver has explicitly armed it. Release builds and
ordinary DEBUG runs construct the established Shizuku controller. There is no
DirectShell-to-Shizuku fallback in a counted DirectShell run.

## Integration seam

The selector is used by all three existing Host construction sites:

- Host capability collection;
- exact capability validation before publication; and
- `DefaultAndroidSessionPipelineBindings.createInputReceiver`.

The existing `AndroidInputInjectionController`, event factory, resolver, and
state tracker are unchanged in behavior. The DirectShell adapter only changes
the privileged execution location. Its shell-side bridge instantiates the
existing `PrivilegedInputInjectionUserService` directly, rather than binding
it through Shizuku.

The DirectShell control path is deliberately bounded:

```text
normal app source
  -> fresh HMAC-SHA-256 secret and generation
  -> one ordered loopback control connection per controller
  -> bounded WNIS v1 event frame
  -> shell bridge
```

Each request has a strictly increasing request ID, all event frames carry the
current generation, malformed/trailing payloads are rejected, and no generic
shell-command or FD-broker operation is exposed. The loopback channel is a
DEBUG integration control path; it is not proposed as a final optimized Input
hot path.

## Capability and lifecycle semantics

The source does not claim availability merely because `app_process` launches.
It verifies the server identity and a real input capability response:

```text
uid = 2000
uid_kind = SHELL
package = com.android.shell
op-package = com.android.shell
attribution uid/package = 2000 / com.android.shell
resolver, display targeting, key, touch, pointer, and joystick = available
```

The session gateway owns one persistent authenticated connection for a
controller lifetime, so host events retain transport order. A control or HMAC
failure is terminal for that gateway; it neither reconnects nor falls back to
Shizuku. `close()` releases tracked input through the existing state tracker,
stops the shell service, and requests authenticated server shutdown.

## Tablet readiness debt closure

The original Tablet/API33 DirectShell SystemAudio campaign was blocked before
discovery by the Shizuku Input UserService bootstrap failure. With the Tablet
left on the same problematic Shizuku 13.6 environment, a fresh DirectShell
Input server reported:

```text
uid=2000 gid=2000 uid_kind=SHELL api=33
selinux_context=u:r:shell:s0
package_name=com.android.shell
op_package_name=com.android.shell
attribution_uid=2000
attribution_package=com.android.shell
```

The real Host capability collector then emitted
`backend=DirectShell`, `available=true`, and
`shizuku_input_producer=false`; Host lifecycle and registration became active.
The UI reached `Waiting for clients` / `Host: Available`.

This closes the specific readiness boundary:

```text
TABLET_HOST_INPUT_INJECTION_CAPABILITY_QUERY_NOT_COMPLETED_BEFORE_DISCOVERY
  -> CLOSED by DirectShell Input capability qualification
```

This is direct evidence that DirectShell Input bypasses the Tablet's Shizuku
13.6 pre-Binder `LoadedApk.makeApplication` incompatibility. It does not claim
that Shizuku itself is fixed or required by DirectShell.

## Normal-process gateway hardware proof

`Run-DirectShellInputSessionIntegration.ps1` drives the actual normal-process
selector/controller/gateway route, not a second injector. It foregrounds the
DEBUG `InputSessionTargetActivity`, launches a fresh shell server and source
generation, then runs:

```text
query capabilities
  -> prepare
  -> start
  -> A down / A up
  -> touch down / move / up
  -> pointer hover
  -> joystick state
  -> reset all tracked state
  -> stop / authenticated shutdown
```

The target Activity observed the ordered events on display 0. The local
integration is intentionally distinct from reverse-input-over-a-network
Session evidence.

| Host | API | Resolver evidence | One-cycle integration | Restart soak | Shell cleanup |
|---|---:|---|---|---|---|
| Aocwei X700_EEA Tablet | 33 | established `InputManager` fallback | PASS | 5/5 PASS | PASS |
| Samsung SM-S901B S22 | 36 | `ModernInputManagerGlobal` | PASS | 5/5 PASS | PASS |
| Samsung SM-A415F A41 | 31 | `LegacyInputManager` fallback | PASS | 5/5 PASS | PASS |

Every counted cycle had a fresh server generation and observed:

```text
backend=DirectShell
shizuku_input_producer=false
key_submitted=2
touch_submitted=3
pointer_submitted=1
joystick_submitted=1
reset_complete=1
```

The target logs prove `KEY_DOWN`, `KEY_UP`, `TOUCH_DOWN`, `TOUCH_MOVE`,
`TOUCH_UP`, `POINTER`, and `JOYSTICK` delivery. Reset produced a joystick
neutralization event and the gateway snapshot required zero active keys,
touch streams, pointer buttons, and joystick states before success. Every
server logged endpoint release and `PROCESS_EXIT code=0`; no current-run
`warpnect-directshell-input-session` process remained.

The runtime identity and target event records are stored under:

```text
app/build/reports/directshell-input-session-integration/
```

## S9 reconnection and discovery correction

Once the S9/API29 Client reconnected, the first valid Tablet -> S9 attempt
reached discovery but found two Warpnect NSD records. The Client asked the
API29 framework to resolve a second service while the first resolution was
still active, and received `NsdManager.FAILURE_ALREADY_ACTIVE` (`code=3`).
The Android reference defines that code as an already-active operation; the
matching AOSP NSD service keeps one active resolution per client.

This was not a DirectShell audio or input failure. The repository's prior
pending-resolution collection permitted concurrent framework resolves. The
generic fix is `SerializedServiceResolutionQueue`: it permits one active
framework resolution, queues distinct work FIFO, and retains a cancelled
active item until its terminal framework callback releases the slot. It has no
device, OEM, API-level, protocol, or retry branch.

The post-fix Tablet -> S9 run passed discovery, authentication, WNCP, setup,
both video-channel-ready checkpoints, and DirectShell Input
`capability -> prepare -> start` with `shizuku_input_producer=false`. This
closes the exact discovery boundary:

```text
S9_API29_NSD_RESOLUTION_ALREADY_ACTIVE
  -> CLOSED by serialized framework resolution ownership
```

## Real Session evidence and remaining boundaries

### Tablet/API33 -> S9/API29

The post-fix Session advanced beyond the historical Shizuku Input blocker,
but the Host produced no first encoded video access unit or video datagram
after setup. The first unresolved boundary is therefore:

```text
TABLET_HOST_VIDEO_FIRST_AU_NOT_PRODUCED_AFTER_SETUP
```

DirectShell Input was prepared and running before that failure; no Shizuku
Input UserService produced events. DirectShell SystemAudio did not reach its
runtime stage because the Session stopped at Host video startup, so this is
not evidence of an audio regression. The Tablet UI remained in its observed
Host wait/disable state after the failed Session. No force-stop, reboot, data
clear, or Shizuku restart was used to manufacture a recovery; Wi-Fi was only
temporarily isolated afterward so the stale Tablet advertisement could not
interfere with the S22 control topology.

### S22/API36 -> S9/API29

With the same installed DEBUG APK and both DirectShell SystemAudio and Input
explicitly selected, three independently started S22 -> S9 Sessions completed
technical remote audio E2E on the first attempt. DirectShell Input reached
capability, prepare, and start with `shizuku_input_producer=false`; the
DirectShell audio source was selected with `shizuku_audio_producer=false`.
The current result is **3/3 first-attempt technical PASS**.

One representative 60-second cycle recorded 2,637,600 Host capture frames,
10,990 Opus/payload frames, 10,991 sent datagrams, zero send failures, and
zero `WouldBlock`. The normal Host drain observed real PCM (RMS 5,795.747,
peak 8,191). The S9 received 11,334 audio datagrams, decoded 11,332 frames,
consumed 2,524,080 playback-ring frames, and reported zero Oboe xruns or
decoder failures. `PlaybackRingFull` was recorded as a bounded real-time drop
condition, not an audio correctness failure: ring occupancy returned to zero
and playback continued.

The operator confirmed that the test tone was heard from the S9 Client:
`AUDIBLE_CONFIRMED` for S22 -> S9. This is the first human-audibility result
in the modern DirectShell campaign.

### A41/API31 -> S9/API29 reference regression

The A41 regression Session passed authentication, capability collection,
setup, video-channel readiness, and DirectShell Input
`capability -> prepare -> start`. The Host then failed at the existing
Shizuku-owned video capture path:

```text
video_sender_start_failed
source=Capture
reason=CaptureFailed
detail=ShizukuUnavailable
```

The encoder had configured, but no first video AU was produced. The exact
current boundary is `A41_SHIZUKU_CAPTURE_UNAVAILABLE`, surfaced to Session as
`VideoPipelineStartFailed`. It is outside the DirectShell SystemAudio/Input
scope and was not remediated here. Both DirectShell shell servers completed
authenticated shutdown; no current-run shell process remained.

## Current modern compatibility status

| Layer | A41 API31 | Tablet API33 | S22 API36 |
|---|---|---|---|
| DirectShell bootstrap/identity | PASS | PASS | PASS |
| DirectShell SystemAudio + PCM ring | existing PASS | existing PASS | existing PASS |
| DirectShell Input controller/gateway | PASS | PASS | PASS |
| Input reset and shell teardown | 5/5 PASS | 5/5 PASS | 5/5 PASS |
| Host readiness | PASS through setup; then `A41_SHIZUKU_CAPTURE_UNAVAILABLE` | PASS through setup; then `TABLET_HOST_VIDEO_FIRST_AU_NOT_PRODUCED_AFTER_SETUP` | PASS |
| DirectShell audio + input remote E2E to S9 | FAIL before audio at Shizuku video capture | FAIL before audio at Host video first AU | 3/3 first-attempt technical PASS |
| Reverse Input Payload V1 E2E to Host | NOT_TESTED in this pass | NOT_TESTED: Session stopped at video boundary | NOT_TESTED: remote runner does not inject Client Payload V1 events |
| Human Client audio | NOT_CONFIRMED | NOT_CONFIRMED | AUDIBLE_CONFIRMED |

The prior Tablet pre-discovery Input boundary and S22 `1/3` historical
Session matrix are closed. The former is closed by DirectShell Input; the
latter is superseded by the post-queue-fix S22 -> S9 3/3 result. The two new
video capture boundaries are separately classified and are not attributed to
DirectShell Input or SystemAudio.

## Failure recovery

The controlled integration release path is:

```text
running input state
  -> InputInjectionStateTracker reset(AllSlots)
  -> DirectShell stop(resetAll)
  -> authenticated shutdown
  -> endpoint release and shell process exit
```

No key, touch, pointer, or joystick state remained active after the counted
cycles. The S22 remote Session matrix also left no DirectShell audio or Input
server behind after each semantic teardown. A manually created diagnostic
shell server was terminated once after its intentionally non-recoverable test
secret was lost during harness isolation; it is not included in the counted
lifecycle matrix and did not interact with application or production helper
state.

## Contracts and limitations

No frozen contract changed:

- Architecture V1.0;
- Input Payload V1;
- SCL Protocol V1;
- PacketHeader/PayloadType;
- WNCP/WNSN/WNSD;
- PCM/audio/video contracts; or
- native bridge ABI.

The serialized NSD resolver change is a generic Android discovery lifecycle
correction. It changes neither the discovery wire data nor any frozen Session
or media contract.

FACT: The existing Warpnect privileged Input implementation can execute in a
Warpnect-owned DirectShell process on API31, API33, and API36, while retaining
the existing Host controller and Input Payload V1.

INFERENCE: Modern Hosts can qualify DirectShell Input without requiring the
Shizuku UserService for that feature, including the affected Tablet/API33.

UNKNOWN: A real Client -> Input Payload V1 -> Host DirectShell reverse-input
Session remains unmeasured. Tablet remote SystemAudio is blocked before its
audio runtime by a separately observed Host video first-AU boundary. A41 is
currently blocked by the existing Shizuku-owned video capture provider.

PROPOSAL: Isolate the Tablet Host video first-AU/media-start lifecycle
boundary before treating the modern Host runtime as fully closed. That work
must retain the validated DirectShell audio/input sources and must not
reclassify the failure as an audio or input defect.

## Silent Tablet video investigation

On 2026-09-20, the Tablet/API33 -> S9/API29 topology was rerun once with the
same DEBUG APK on both devices and the DirectShell audio/input probes armed.
The run explicitly set `tone_skipped=true`: no `SystemAudioE2eToneActivity`
or other test-tone source was launched.

The Session reached authentication, capability collection, setup, and both
video-channel-ready checkpoints. The first failed boundary was then:

```text
TABLET_SHIZUKU_CAPTURE_USERSERVICE_BOOTSTRAP_TIMEOUT_BEFORE_BINDER
  -> CaptureFailed / PrivilegedServiceUnavailable
  -> VideoPipelineStartFailed
```

`ShizukuCaptureGateway` is still the production capture backend used by
`AndroidVideoCaptureController`. On this Tablet, the current Shizuku 13.6
provider environment never delivers that capture UserService's Binder. This
matches the already-recorded provider/framework bootstrap incompatibility; it
is upstream of display mirroring, MediaCodec output, UDP, client decode, and
the DirectShell audio/input paths.

The S9 did create a `WarpnectVideoSurfaceView` while the Session was being
prepared, but the Host produced no encoded AU or video datagram. Accordingly,
the Client received no AVC configuration, AU, decoder submission, decoded
frame, or rendered frame before the Session closed. A black client rectangle
in the earlier Tablet experiments therefore represented an empty video
surface, not evidence that the decoder rendered a black captured frame.

The previous unbounded capture-Service bind also allowed this provider fault to
hold video startup indefinitely. `ShizukuCaptureGateway` now uses the same
30-second bounded UserService-start policy as the audio gateway and maps a
missing Binder to `PrivilegedServiceUnavailable`. Platform network callbacks
are dispatched on the serialized Session-control scheduler instead of calling
the lifecycle controller inline from the Android main callback. In the silent
rerun, both applications returned to `Ready`, no Android input-dispatch ANR
occurred, and no DirectShell server process remained.

This hardening makes the failure truthful and recoverable; it does not make
Tablet video capture available. The smallest architectural resolution is a
separate DirectShell display-capture feasibility/integration task that can
replace the Shizuku-owned `AndroidVideoCaptureController` capture backend. A
decoder, Opus, SystemAudio, or Input change would not address this boundary.

## Files

```text
app/src/main/java/io/warpnect/platform/input/injection/
  InputInjectionControllerSelector.kt
app/src/main/java/io/warpnect/platform/discovery/
  SerializedServiceResolutionQueue.kt
app/src/debug/java/io/warpnect/debug/directshell/
  DirectShellInputSessionBridge.kt
  DirectShellInputSessionClient.kt
  DirectShellInputSessionCodec.kt
  DirectShellInputSessionGateway.kt
  DirectShellInputSessionIntegrationReceiver.kt
  WarpnectPrivilegedInputProbeServer.kt
tools/directshell/Run-DirectShellInputSessionIntegration.ps1
app/src/test/java/io/warpnect/platform/discovery/
  SerializedServiceResolutionQueueTest.kt
```

## Validation artifacts

The focused regression suite and Android build gates completed from this
branch with 629 JVM tests, zero failures, zero errors, and zero skipped
tests. `:app:ktlintCheck`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`, and `:app:packageDirectShellProbeDex` also
passed. Native sources were unchanged, so the existing Debug and Release
CTest 22/22 results remain applicable.

The validated DEBUG artifacts were:

```text
app-debug.apk
SHA-256: F0DCA98AE98F3491649C4719440DF53E93A87A1035BB9E1C98D19E031739E26F
size: 29,150,763 bytes
timestamp: 2026-09-19T22:37:04Z

warpnect-directshell-probe.jar
SHA-256: 0CE998621F027F55265300504515125F0C2F022316B8F14EA0C84747AD9B25F3
size: 10,220,401 bytes
timestamp: 2026-09-19T22:37:20Z
```

## Decision

`DIRECTSHELL_MODERN_HOST_RUNTIME_NEEDS_MORE_EVIDENCE`
