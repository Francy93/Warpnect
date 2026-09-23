# DirectShell Privileged Display Capture Integration

## Scope

This Phase 6.5 DEBUG-only spike moves only the privileged display-mirror
control boundary from Shizuku to Warpnect's existing DirectShell runtime. It
does not make DirectShell the production default and does not change WNCP,
WNSN, SCL, Video Payload V1, encoder configuration, decoder behavior, or
native code.

```text
Normal app MediaCodec encoder owns its input Surface
  -> authenticated, one-generation ContentProvider handoff
  -> app_process DirectShell server (uid/gid 2000, u:r:shell:s0)
  -> existing qualified privileged display-mirror implementation
  -> same app-owned encoder Surface
  -> existing AVC encoder / video payload / protected SCL / UDP
  -> existing Client decoder / SurfaceView
```

The previous production path remains available and unchanged by default:

```text
Host Session -> AndroidVideoCaptureController -> ShizukuCaptureGateway
  -> PrivilegedCaptureUserService -> privileged display mirror
  -> normal-process MediaCodec encoder
```

`CaptureGatewaySelector` continues to select that Shizuku gateway in release
builds and ordinary DEBUG runs. A DirectShell gateway is installed only by an
explicit DEBUG receiver for a single test generation; there is no DirectShell
to Shizuku fallback during a counted run.

## Existing capture seam

The existing normal-process `AndroidVideoCaptureController` already owns the
encoder lifecycle through `VideoSenderComponent` and passes its encoder input
`Surface` to `PrivilegedCaptureGateway.startCapture`. The actual privileged
work is isolated behind `QualifiedPrivilegedDisplayCaptureApi`, which selects
the established `DisplayManagerMirrorCaptureApi` or
`SurfaceControlDisplayCaptureApi`. The DirectShell spike reuses that
implementation unchanged in the shell process.

This preserves the important ownership boundary: DirectShell never owns a
MediaCodec and never transports raw or encoded frames over Binder or TCP. It
only receives a parcelled copy of the app-owned encoder Surface and controls
the privileged display mirror targeting it.

## Surface handoff and trust model

`DirectShellVideoCaptureProbeSession` arms one app-owned Surface for one
fresh generation. `DirectShellVideoCaptureProbeContentProvider` grants that
Surface only when all of these conditions hold:

- the framework Binder caller is `Process.SHELL_UID`;
- the request magic and version are `WNVS` / v1;
- the generation, 1280x720 dimensions, and HMAC-SHA-256 proof match;
- the Surface was not previously issued for that generation.

The shell opens the external provider using the same framework-mediated
pattern established by the DirectShell FD probe, requests one typed `Surface`
Parcelable, and releases its own parcelled copy after mirror shutdown. There
is no generic Surface or file-descriptor broker, no static secret, and no
cross-process frame copy. Old generations and repeated requests are rejected.

## Standalone capture qualification

Each probe created an app-owned 1280x720 AVC encoder Surface, passed it to the
shell server, mirrored display 0 into it, and changed a DEBUG visual fixture.
The two samples have distinct encoded byte counts and SHA-256 samples, so the
result is real captured display content rather than merely a valid Surface.

| Host | API | Shell identity | Mirror backend | AVC evidence | Cleanup |
|---|---:|---|---|---|---|
| Tablet X700_EEA | 33 | uid/gid 2000, `u:r:shell:s0` | `SurfaceControlDisplay` | 1 -> 3 AUs; 5,086 -> 18,446 bytes | PASS |
| Samsung A41 | 31 | uid/gid 2000, `u:r:shell:s0` | `SurfaceControlDisplay` | 2 -> 3 AUs; 3,738 -> 10,218 bytes | PASS |
| Samsung S22 | 36 | uid/gid 2000, `u:r:shell:s0` | `DisplayManagerMirror` | 1 -> 3 AUs; 3,107 -> 12,022 bytes | PASS |

All three standalone runs reported a valid target Surface, one AVC output
format, zero encoder errors, `VIDEO_MIRROR_STOPPED`, and
`PROCESS_EXIT code=0`.

## Tablet Shizuku regression bypass

The Tablet remained on the known incompatible Shizuku 13.6 environment. Its
historical capture failure occurs before a Shizuku UserService publishes a
Binder, so no AVC configuration or access unit can reach the Client. The
DirectShell probe instead launched Warpnect-owned code directly with
`app_process`, used shell identity, received the normal app encoder Surface,
and started `SurfaceControlDisplay` successfully.

Therefore the evidence supports this exact conclusion:

```text
DIRECTSHELL_VIDEO_BYPASSES_SHIZUKU_USERSERVICE_REGRESSION
```

It does not claim that the Shizuku provider/framework incompatibility itself
is fixed.

## Silent remote visual validation

All remote runs below used the same APK on Host and Client:

```text
SHA-256: E3B785C46F62A52605D53AC9280610BD9E1C9B4577A6A776D65932DA60201A92
Size:    29,543,661 bytes
```

`SkipTone=true` was used for every run in this document. SystemAudio remained
selected through DirectShell where configured, but no tone or other deliberate
audio playback was generated. These are video-only validation results.

| Topology | First-attempt result | DirectShell video | Client visual result |
|---|---:|---|---|
| Tablet API33 -> S9 API29 | 3/3 PASS | `SurfaceControlDisplay`, Shizuku video false | `VISIBLE_CONFIRMED` |
| A41 API31 -> S9 API29 | 1/1 PASS | `SurfaceControlDisplay`, Shizuku video false | `VISIBLE_CONFIRMED` |
| S22 API36 -> S9 API29 | 3/3 PASS | `DisplayManagerMirror`, Shizuku video false | `VISIBLE_CONFIRMED` |

Every counted Session logged the same causal chain:

```text
DirectShell video selected
  -> encoder Surface issued to shell uid 2000
  -> display mirror running
  -> first Host encoded AU
  -> first Host video datagram
  -> first Client video datagram
  -> Client decoder input/output and Surface release advance
  -> screenshot shows the Host UI on S9
```

For example, the second counted Tablet cycle recorded 33 Host AUs and 103
sent video datagrams; the S9 received 103 video datagrams, delivered 33
completed access units, released 25 decoder outputs, and rendered 29 frames.
The equivalent S22 cycle recorded 52 Host AUs, 203 sent datagrams, 169 S9
video datagrams, 15 released decoder outputs, and 24 render-now operations.
No Host transport failures or encoder errors were recorded.

### Screenshot acceptance and the historical black rectangle

The synchronized artifacts under
`app/build/reports/directshell-systemaudio-remote-e2e/` visibly show the Host
Warpnect UI inside the S9 SurfaceView. A host-side visual pulse changes the
corresponding Client screenshot, proving ongoing image delivery. The Client
is not displaying a decoder-generated black frame.

The black regions surrounding the captured image are expected letterboxing:
all Hosts were portrait (`800x1280` Tablet, `1080x2400` A41, `1080x2340` S22)
while the frozen encoder profile is landscape `1280x720`. The S9 itself uses
a `1080x2220` display override. The remote portrait Host image is therefore
rotated/scaled inside a landscape video frame and black padding remains around
the content rectangle. This is intentionally not treated as a capture or
decoder failure.

The legacy `OnFrameRendered` checkpoint remains an incomplete observability
signal on the S9. Visual screenshots, decoder output/release counters, and
the explicit changing-image test are the authoritative presentation proof for
this spike. That legacy observability debt remains separate from this result.

## Lifecycle and cleanup

Each session has a fresh DirectShell video generation and one fresh Surface
handoff. Normal teardown performs:

```text
Session disconnect
  -> DirectShell mirror stop
  -> shell parcelled Surface release
  -> provider client close
  -> normal encoder and capture-controller teardown
  -> authenticated DirectShell shutdown
```

The counted reports show `VIDEO_MIRROR_STOPPED` followed by `PROCESS_EXIT
code=0` for the audio, input, and video shell servers. A post-run process
inventory found no current-run `warpnect-directshell` or `app_process`
servers on Tablet, A41, S22, or S9. No stale Surface generation or delayed ADB
install overlapped a counted Session.

## Limits and conclusion

FACT: DirectShell display capture works through the existing privileged mirror
implementation on API31, API33, and API36 without moving MediaCodec or video
payload processing across the privilege boundary.

FACT: The Tablet now produces and visibly delivers video to S9 while its
Shizuku 13.6 UserService bootstrap remains incompatible.

FACT: The same DEBUG implementation passed the required silent modern remote
matrix without using Shizuku as the privileged video producer.

INFERENCE: Modern Warpnect Hosts can use DirectShell for SystemAudio,
privileged Input, and full-display capture in the Phase 6.5 DEBUG path. A
separate explicit task is still required before changing the production
default or removing the Shizuku dependency.
