# DirectShell Privileged-Server Bootstrap Probe

## Purpose and boundary

This DEBUG-only Phase 6.5 experiment establishes whether Warpnect-owned Java/Dex can run through
`app_process` as Android shell and accept authenticated local diagnostic control from the normal
Warpnect application process. It does not modify Shizuku, Session ownership, SCL, media, Input,
or any frozen protocol contract.

The server exposes only `PING`, `GET_RUNTIME_INFO`, and `SHUTDOWN`. It never opens MediaCodec,
AudioPolicy, AudioRecord, capture, Input, networking, or a media ring.

## Components

```text
Normal Warpnect DEBUG app process (application UID)
  DirectShellProbeActivity / DirectShellProbeClient
             |
             | literal AF_INET 127.0.0.1:<launch-randomized-port>
             | bounded WNDP frames + HMAC-SHA-256
             v
app_process shell process (expected UID 2000)
  WarpnectPrivilegedServer
```

Source locations:

- `app/src/debug/java/io/warpnect/debug/directshell/WarpnectPrivilegedServer.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellProbeClient.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellProbeProtocol.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellProbeActivity.java`
- `app/src/debug/java/io/warpnect/debug/directshell/DirectShellProbeIpv4Socket.java`
- `tools/directshell/Run-DirectShellProbe.ps1`

`packageDirectShellProbeDex` packages the DEBUG APK's `classes*.dex` files into
`app/build/outputs/directshell-probe/warpnect-directshell-probe.jar`. The artifact establishes
Java/Dex loading only. It does not establish native-library loading or application-resource use.

## Transport decision

**FACT:** On the A41/API31 hardware target, an Android abstract Unix-domain socket did not
establish across the normal-app and shell SELinux domains in either tested direction. The
shell-to-app attempt returned `IOException: Permission denied`; the app-to-shell attempt returned
an I/O connection failure and the shell listener never accepted a client.

**FACT:** This probe therefore uses loopback TCP exclusively for a bounded authenticated control
experiment. `DirectShellProbeIpv4Socket` uses `android.system.Os.socket` with literal `AF_INET`,
binds only to `127.0.0.1`, and uses a launch-randomized high port. It does not rely on an
IPv4-mapped IPv6 listener or a Java `ServerSocket` address-selection default.

**INFERENCE:** Loopback TCP is sufficient to determine whether the shell runtime, class loading,
authentication, ordering, and process lifecycle are viable independently of the UDS policy.

**UNKNOWN:** Loopback TCP is not suitable for the future media hot path and does not prove
file-descriptor transfer. A later, separate Android IPC experiment must establish a permitted
FD-capable bridge, likely Binder or another platform-approved primitive, before any shared-memory
media migration is considered.

**PROPOSAL:** Keep this transport confined to DEBUG probe control until that later FD-capable IPC
decision is separately designed and reviewed.

## Local protocol and trust model

Every bounded frame has magic `WNDP`, version `1`, a strictly increasing per-connection request
ID, command/status, a payload length capped at 4 KiB, and an HMAC-SHA-256. The launcher creates a
fresh random 32-byte secret for every server process. The client and server both require it;
malformed or unauthenticated frames are closed without dispatching a diagnostic command. Responses
are also HMAC-authenticated.

Loopback TCP provides no peer-UID check, so HMAC possession is the command authorization boundary
for this experiment. The secret is supplied only through controlled development ADB launch
arguments and DEBUG Activity extras. No secret or key material is logged. This proves a caller
without the current secret cannot invoke `GET_RUNTIME_INFO` or `SHUTDOWN`; it does **not** establish
a production-safe secret-provisioning or privileged-runtime bootstrap design.

## Context strategy

The server records `ActivityThread.currentApplication()` and, only when an existing activity
thread is present, its `getSystemContext()`. It deliberately does not invoke
`ActivityThread.systemMain()` and does not manufacture an `Application`.

When a Context is available, the probe records package-manager availability, package name,
op-package name, API-31-plus attribution information, and a legitimate `createPackageContext`
attempt. When unavailable, the response records `UNAVAILABLE`. Future adapters may only rely on a
Context strategy proven by a dedicated probe on the actual target runtime.

## Build and launch

Build the DEBUG APK and DEX classpath artifact:

```powershell
.\gradlew.bat :app:packageDirectShellProbeDex
```

Install the DEBUG APK and push the artifact. The serial is intentionally a command argument, not
repository configuration:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = '<device-serial>'
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial push app\build\outputs\directshell-probe\warpnect-directshell-probe.jar /data/local/tmp/warpnect-directshell-probe.jar
```

Generate a URL-safe Base64 32-byte secret and a random unprivileged port. Start the shell server
without Shizuku or root:

```powershell
& $adb -s $serial shell sh -c 'CLASSPATH=/data/local/tmp/warpnect-directshell-probe.jar app_process /system/bin --nice-name=warpnect-directshell io.warpnect.debug.directshell.WarpnectPrivilegedServer --port 39001 --secret-base64 <random-32-byte-secret> --declared-package io.warpnect --idle-timeout-ms 15000 >/data/local/tmp/warpnect-directshell.log 2>&1 &'
```

Run the normal-app client first with `mode=unauthenticated`, then with `mode=authenticated` and
`requestShutdown=true`. The reusable 20-cycle harness performs both operations:

```powershell
.\tools\directshell\Run-DirectShellProbe.ps1 -Serial '<device-serial>' -Cycles 20
```

The harness writes per-cycle server stdout logs under
`app/build/reports/directshell-probe/`. A passing cycle requires shell identity, an HMAC rejection,
authenticated `PING`, runtime info, authenticated shutdown, endpoint release, process exit, and no
named server process remaining. It never kills a server as success-path recovery.

## Expected diagnostics

The server writes concise, secret-free `WarpnectDirectShell` records to logcat and stdout:

- `START`: PID, UID, UID kind, API, loopback endpoint, Context strategy, class loader.
- `AUTH`: success or unauthenticated/malformed rejection.
- `REQUEST`: request ID, command, result.
- `SHUTDOWN`, `ENDPOINT_RELEASED`, and `PROCESS_EXIT`.

`GET_RUNTIME_INFO` returns PID, UID/GID, SELinux context when readable, classpath/class-loader
information, process name, declared package, and Context/package/op-package/attribution results.
It never returns the secret.

## Lifecycle rules

Only one listener may bind a selected port. A duplicate bind exits with `ENDPOINT_BIND_FAILED`.
An unauthenticated or disconnected client leaves the listener available until the bounded idle
timeout; it does not dispatch a command. A valid `SHUTDOWN` response is flushed before endpoint
release and process exit. The listener uses a short accept timeout so idle shutdown is deterministic.

## Hardware evidence

Final hardware validation used the A41 (`SM-A415F`, Android 12/API31,
`samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys`) with the DEBUG APK:

```text
APK SHA-256: E9A7101AA465DE42B660EA4938BFE6B6DAEF287ED0896B9F99D09334FA3DC2DB
APK size:     29,003,007 bytes
DEX JAR SHA-256: 5E6AE9F2F3EC7FD5E98FB92480A5B1E00760F2ED5DBE4CF05068B92D25A274F5
```

The installed A41 `base.apk` hash matched the APK hash above. The server's final-cycle runtime
record established:

- PID `15536`, UID/GID `2000`, and `uid_kind=SHELL`; this is an authoritative
  `Process.myUid() == Process.SHELL_UID` result, not an inference from successful launch.
- API `31`, declared package `io.warpnect`, process name `warpnect-directshell-20`, SELinux
  domain `u:r:shell:s0`, class path `/data/local/tmp/warpnect-directshell-probe.jar`, and class
  loader `dalvik.system.PathClassLoader` with `java.lang.BootClassLoader` parent.
- `context_strategy=UNAVAILABLE`; `ActivityThread.systemMain()` was deliberately not invoked.
  Consequently package Context, package manager, op-package, and attribution are all
  `UNAVAILABLE` in this bare shell process. This is a measured limitation, not a failed attempt
  to pretend that the server is a normal application.

The final artifact completed 20/20 clean start/authenticate/ping/runtime-info/shutdown cycles.
Every cycle used a fresh random 32-byte HMAC secret and port, rejected an unauthenticated
`GET_RUNTIME_INFO` request, accepted strictly ordered authenticated request IDs `1`, `2`, and
`3`, released the endpoint, exited with code `0`, and left no named `warpnect-directshell-*`
process. The harness waits for the server's `START result=READY` record rather than using a
startup sleep, so client startup cannot race a not-yet-bound listener.

A duplicate-server test on an already-bound endpoint produced `EADDRINUSE` and exited code `3`;
the first server remained usable and subsequently completed its authenticated shutdown sequence.
This establishes deterministic conflict behavior without killing either process as recovery.

No native code was added. The focused protocol suite adds six JVM tests; the full debug unit suite
completed with 608 tests, zero failures, zero errors, and zero skips. `ktlintCheck`, `lintDebug`,
`assembleDebug`, `assembleDebugAndroidTest`, and `packageDirectShellProbeDex` all passed.

## Remaining limitations

- This is one physical-device result on A41/API31. It does not establish universal Android,
  OEM, or Android-version behavior.
- The DEBUG launcher injects a fresh secret through development ADB arguments. Production secret
  provisioning and authorization policy remain unproven.
- Loopback TCP is a control transport only. It does not prove descriptor passing, shared-memory
  ownership, native-library loading, or media-hot-path suitability.
- A future privileged adapter cannot assume a normal `Application`, package Context,
  op-package, or attribution source. It needs a dedicated, platform-legitimate Context strategy
  on each target runtime.

## Implication for the next probe

Only after the loopback control experiment validates bootstrap, authenticated ordering, runtime
identity, and teardown should a separate DirectShell Context plus FD-capable Android IPC probe be
considered. This experiment provides no evidence that AudioPolicy, AudioRecord, display capture,
native libraries, Input injection, or shared-memory descriptor transfer will work under shell.
