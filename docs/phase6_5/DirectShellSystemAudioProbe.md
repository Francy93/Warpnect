# DirectShell Standalone SystemAudio / REMOTE_SUBMIX Probe

## Purpose and boundary

This DEBUG-only Phase 6.5 experiment establishes a narrow fact: a Warpnect-owned Java/Dex
server started with `app_process` can run as Android shell and capture real device playback through
`AudioRecord(REMOTE_SUBMIX)` on the tested A41/API31 hardware.

It does not replace, call, or modify the production Shizuku SystemAudio backend. It does not use
AudioPolicy, MediaProjection, Session, SCL, Opus, Shared PCM Ring V1, transport, Input, or Video.
The control socket returns bounded aggregate diagnostics only; it is not PCM transport.

## Components

```text
Normal Warpnect DEBUG app process
  SystemAudioE2eToneActivity -- normal AudioTrack playback only
  DirectShellSystemAudioProbeReceiver -- authenticated control only
                         |
                         | HMAC-authenticated WNDP control frames over 127.0.0.1
                         v
app_process shell process, UID/GID 2000
  WarpnectPrivilegedSystemAudioProbeServer
  DirectShellShellAudioContext
  AudioRecord(REMOTE_SUBMIX) -- bounded PCM sampling only
```

Source locations:

- `app/src/debug/java/io/warpnect/debug/directshell/WarpnectPrivilegedSystemAudioProbeServer.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellShellAudioContext.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellAudioProbeMetrics.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellSystemAudioProbeReceiver.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellSystemAudioProbeRequest.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellSystemAudioProbeClient.java`
- `tools/directshell/Run-DirectShellSystemAudioProbe.ps1`

Only `src/debug`, focused JVM tests, this document, and the DEBUG harness change. Production
Shizuku gateways and normal Session/media/protocol source remain untouched.

## Reference findings

### FACT: current scrcpy behavior

Current scrcpy's [AudioDirectCapture](https://raw.githubusercontent.com/Genymobile/scrcpy/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioDirectCapture.java)
uses `MediaRecorder.AudioSource.REMOTE_SUBMIX`. On Android 12 and later it provides an
`AudioRecord.Builder` Context from [FakeContext](https://raw.githubusercontent.com/Genymobile/scrcpy/master/server/src/main/java/com/genymobile/scrcpy/FakeContext.java).
That context reports `com.android.shell` and, on API31+, a shell-UID `AttributionSource`.

scrcpy additionally contains Android 11 activity and OEM-specific reflection paths, and its
[Workarounds](https://raw.githubusercontent.com/Genymobile/scrcpy/master/server/src/main/java/com/genymobile/scrcpy/Workarounds.java)
can manipulate private ActivityThread state. Those paths were not copied. This probe does not
manufacture an `Application`, use an Android 11 workaround, or use a vendor fallback.

### FACT: framework contract relevant to this probe

The Android reference for [MediaRecorder.AudioSource.REMOTE_SUBMIX](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)
states that it requires `CAPTURE_AUDIO_OUTPUT` and redirects applicable device output to the remote
submix while capture is active. The Android 12 [AudioRecord source](https://android.googlesource.com/platform/frameworks/base/%2B/android-12.0.0_r34/media/java/android/media/AudioRecord.java)
uses a Builder Context to obtain attribution information. The Android 12
[ActivityThread source](https://android.googlesource.com/platform/frameworks/base/%2B/android-12.0.0_r34/core/java/android/app/ActivityThread.java)
prepares a Looper before constructing its normal process ActivityThread.

### RELEVANCE

The bare Warpnect `app_process` main had no thread Looper. Its first context attempt failed before
AudioRecord with `RuntimeException: Can't create handler inside thread ... that has not called
Looper.prepare()`, rooted at `android.os.Handler#<init>`. A conditional thread-local
`Looper.prepare()` is therefore required before `ActivityThread.systemMain()` can create its
framework handlers on the tested runtime.

The probe then obtains a real framework system Context, wraps it only with the actual shell package
and attribution identity, and verifies that `com.android.shell` is installed as UID 2000. It does
not call `Looper.prepareMainLooper()`, manipulate `sMainLooper`, create an Application, bind an
application package, or claim Warpnect's application identity.

### NOT REQUIRED

This experiment does not establish a Context strategy for Android 11, API29 and below, all OEMs, or
all future privileged adapters. It does not establish a route for FD passing, SharedMemory handoff,
or continuous PCM transport.

## Shell identity and Context result

On the A41, the started server reported:

```text
Process.myUid() = 2000
GID             = 2000
uid_kind        = SHELL
API             = 31
SELinux         = u:r:shell:s0
```

After the minimal Looper preparation, the accepted Context diagnostics were:

```text
context_strategy        = ACTIVITY_THREAD_SYSTEM_MAIN_WITH_THREAD_LOOPER
base_system_package     = android
package_name            = com.android.shell
op_package_name         = com.android.shell
attribution_uid         = 2000
attribution_package     = com.android.shell
shell_package_uid       = 2000
```

The server was loaded from `/data/local/tmp/warpnect-directshell-probe.jar` through
`dalvik.system.PathClassLoader` with the boot class loader as parent. That is the same standalone
DEX packaging and class-loading baseline established by `DirectShellBootstrapProbe.md`; this probe
adds AudioRecord only after that bootstrap succeeds.

`dumpsys package com.android.shell` independently showed `CAPTURE_AUDIO_OUTPUT` and
`RECORD_AUDIO` granted to UID 2000 on this device. This is an observed A41 framework and
permission result, not a claim about all shell environments.

## Audio configuration and protocol

The server uses the standard API31 `AudioRecord.Builder` path:

```text
source       = REMOTE_SUBMIX
format       = PCM 16-bit
sample rate  = 48,000 Hz
channels     = stereo
minimum buf  = 7,680 bytes
configured   = 15,360 bytes / 3,840 frames
```

The DEBUG server accepts `AUDIO_PREPARE`, `AUDIO_START`, `AUDIO_SAMPLE`, `AUDIO_STOP`,
`GET_AUDIO_DIAGNOSTICS`, and `SHUTDOWN`. It reuses the bootstrap probe's bounded `WNDP` V1 frame:
launch-randomized 32-byte secret, HMAC-SHA-256 on every request and response, 4 KiB payload cap,
and strictly increasing request IDs per connection. It never logs a secret or PCM samples.

The normal-app controller is an explicit DEBUG broadcast receiver so it can issue a bounded sample
without obscuring the foreground tone Activity. It does not start the shell process; the harness
starts `app_process` explicitly through ADB.

## Hardware validation

Primary hardware:

```text
Device:      Samsung SM-A415F (A41)
Android:     12 / API31
Fingerprint: samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys
```

Tested implementation commit: `650b22b`.

```text
DEBUG APK SHA-256: 9E154A4A233300CFC306AAC243C9FB46509C2F4D26A3C550EF976EF92B0AEC9D
DEBUG APK size:     29,019,443 bytes
DEX JAR SHA-256:   44AF1C07E071846632D03F2FDB569461CFDE5F385E347E18D9E8751FD78AD322
DEX JAR size:       10,100,320 bytes
```

The installed A41 `base.apk` hash matched the APK hash above.

The normal DEBUG `SystemAudioE2eToneActivity` generated only a 997 Hz, PCM16, stereo, 48 kHz
`USAGE_GAME` AudioTrack tone with amplitude 8192. The shell process performed two bounded
1,200 ms capture windows in the same recording lifecycle:

| Window | Frames | Non-zero samples | Peak | RMS | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Before tone | 69,120 | 0 | 0 | 0.000 | `PCM_ZERO_ONLY` |
| During tone | 69,120 | 130,544 | 8,235 | 5,618.057 | `REAL_PCM_CAPTURED` |

This A/B proof is authoritative for real playback provenance: the same initialized recorder was
silent before the controlled Android playback and carried high-energy PCM only while that playback
was active. `R_SUBMIX` / `RECORD_R_SUBMIX_48000` were simultaneously present in AudioFlinger.

The bounded zero-crossing estimate did not equal the nominal 997 Hz on this Samsung mixer path, so
the probe reports it as diagnostic only and does not make an unsupported exact-frequency claim.
The zero-to-high-energy A/B result, known source amplitude, time correlation, and active r_submix
route establish the required PCM evidence without retaining raw audio.

The framework contract says RemoteSubmix redirects applicable playback while recording. AudioFlinger
showed that route; physical local-speaker audibility was not instrumented, so this report does not
claim a human speaker-silence result.

## Lifecycle and security evidence

The final A41 soak completed **10/10** independent cycles:

```text
prepare -> start -> pre-tone sample -> tone sample -> stop -> authenticated shutdown
```

Each cycle had a fresh random secret and endpoint, initialized and recorded successfully, produced
the silent pre-tone / real-PCM tone pair, released the endpoint, exited code 0, and left no named
`warpnect-directshell-audio-*` process. The harness has no force-kill or retry recovery path.

A separate negative run sent `GET_RUNTIME_INFO` with a one-bit-mutated HMAC secret. The normal app
observed `DIRECT_SHELL_CLIENT_NEGATIVE_AUTH result=REJECTED`; the shell log recorded
`AUTH result=UNAUTHENTICATED`. A subsequent correctly authenticated `SHUTDOWN` was accepted and
the server exited normally. Thus unauthenticated callers cannot dispatch an audio or control command.

## Reproduction

Build the DEBUG controller APK and standalone DEX artifact:

```powershell
.\gradlew.bat :app:assembleDebug :app:packageDirectShellProbeDex
```

The harness installs the DEBUG APK unless `-SkipInstall` is deliberately supplied, pushes the DEX
artifact, launches the shell server, and performs the bounded lifecycle:

```powershell
.\tools\directshell\Run-DirectShellSystemAudioProbe.ps1 `
  -Serial '<device-serial>' `
  -Cycles 10 `
  -ToneDurationSeconds 5 `
  -SampleDurationMilliseconds 1200
```

Its server launch is equivalent to:

```sh
CLASSPATH=/data/local/tmp/warpnect-directshell-probe.jar \
app_process /system/bin \
  --nice-name=warpnect-directshell-audio \
  io.warpnect.debug.directshell.WarpnectPrivilegedSystemAudioProbeServer \
  --port <random-high-port> --secret-base64 <fresh-random-32-byte-secret>
```

The loopback socket remains DEBUG control-plane only. It is not a proposed media transport.

## Hardware matrix

| Device | API | Result | Scope |
| --- | ---: | --- | --- |
| A41 / SM-A415F | 31 | `PASS_REAL_PCM` | Shell identity, Context attribution, AudioRecord start, real PCM A/B, and 10/10 lifecycle soak |
| S22 | 36 | `NOT_TESTED` | Not connected during this experiment |
| Tablet | 33 | `NOT_TESTED` | Not connected during this experiment |
| A41 Android 11 target | 30 | `NOT_TESTED` | No API30 A41 target connected |

S9/API29 and S7/API26 were intentionally not tested: legacy/direct-root work is out of scope.

## Limits and implication

**FACT:** DirectShell standalone SystemAudio capture is viable on the tested A41/API31 runtime.

**INFERENCE:** A future DirectShell SystemAudio backend is a credible candidate for later
architecture work on API31-like environments because it can obtain real playback PCM as UID 2000
without Shizuku or a normal Warpnect Application identity.

**UNKNOWN:** Whether this Context/attribution path works on API30, API33, API36, other OEMs, or
whether shell audio capture can safely transfer a SharedMemory descriptor to the normal application.

**PROPOSAL:** Keep this code DEBUG-only. Before any production migration, run a dedicated
FD-capable local IPC / SharedMemory handoff experiment; do not treat loopback TCP control or this
single-device result as a production runtime design.
