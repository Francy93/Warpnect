# DirectShell FD-Capable IPC and SharedMemory Handoff Probe

## Purpose and scope

This DEBUG-only Phase 6.5 experiment determines whether a normal Warpnect app process can hand an FD-backed SharedMemory region to a Warpnect-owned app_process shell server and both processes can observe writes in the same backing region.

It does not migrate SystemAudio, replace Shizuku, transfer live PCM, alter PCM Shared Ring V1, or change Session, SCL, WNCP, payload, native, Video, or Input contracts. No src/main source was changed.

    Normal Warpnect DEBUG app                    app_process shell (UID 2000)
    creates and maps SharedMemory                authenticated DirectShell server
               |                                            |
               | framework Binder Parcelable FD             | maps received FD
               v                                            v
    one-operation DEBUG ContentProvider <- external-provider framework path
               |                                            |
               +-- A app pattern -> shell verifies          |
               +-- B shell pattern -> app verifies          |
               +-- C app pattern -> shell verifies          |

Loopback TCP remains only the authenticated DEBUG control plane. It carries commands and bounded diagnostics, never SharedMemory contents, PCM, Base64 payload copies, or an FD emulation.

## Existing production PCM IPC

The unchanged Shizuku SystemAudio path is:

    PrivilegedAudioCaptureUserService
      -> SharedMemory PCM ring mapping
      -> notify-write and ack-read pipe ends retained by service
      -> SharedMemory + notify-read + ack-write ParcelFileDescriptors over Binder
      -> SharedPcmAudioDrain maps ring, drains notifications, and acknowledges

SharedPcmAudioDrain owns the normal-process mapping and pipe ends. The privileged service owns its mapping and complementary pipe ends. Both close pipes, unmap, and close descriptors during normal teardown.

PCM Shared Ring V1 is untouched. This probe uses a separate 64 KiB WNFD diagnostic layout with a 64-byte header and a 256-byte deterministic payload.

## IPC candidate analysis

FACT: The prior DirectShell bootstrap probe showed abstract Unix-domain sockets denied across the A41 untrusted_app <-> shell SELinux boundary. They were not retried.

FACT: An explicit normal-app bindService candidate failed on A41 before Binder delivery with SecurityException: Unable to find app for caller. A bare app_process shell runtime is not a registered Android application process for that binding route.

FACT: Android's external ContentProvider acquisition route accepts a null application thread. On A41, IActivityManager.getContentProviderExternal succeeded and the provider observed the real shell Binder UID.

DECISION: The DEBUG probe uses a one-operation ContentProvider acquired through the external-provider path. It is not a generic FD broker.

LIMITATION: getContentProviderExternal and direct IContentProvider.call are framework APIs reached reflectively. This is feasibility evidence, not approval to depend on a hidden API in production.

References inspected:

- [Android 12 IActivityManager external-provider API](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/app/IActivityManager.aidl)
- [Android 12 ContentProviderHelper external-provider path](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/am/ContentProviderHelper.java)
- [Android SharedMemory API](https://developer.android.com/reference/android/os/SharedMemory)
- [scrcpy FakeContext reference](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/FakeContext.java), studied for shell identity only; no code was copied.

## Implementation

Source commit: d55d820, feat(debug): add DirectShell SharedMemory handoff probe.

DEBUG-only components:

- DirectShellFdProbeActivity and DirectShellFdProbeClient: bounded normal-app controller.
- DirectShellFdProbeSession: one mapped normal-app generation.
- DirectShellFdProbeContentProvider: exported, single-operation descriptor endpoint.
- DirectShellFdProbeExternalProviderClient: shell-side external-provider acquisition and release.
- DirectShellFdProbeFrameworkBridge: shell Context and AttributionSource setup on a live Looper.
- WarpnectPrivilegedFdSharedMemoryProbeServer: app_process server.
- DirectShellFdProbeBinderProtocol and DirectShellFdSharedMemoryLayout: probe-specific protocol and memory layout.
- tools/directshell/Run-DirectShellFdSharedMemoryProbe.ps1: deterministic no-retry harness.

The provider exposes no query, insert, update, delete, file, or arbitrary command API. Its only method is open_shared_memory_v1.

The current external-provider shape requires API31 for a valid shell AttributionSource. On older APIs, the DEBUG activity, provider, and server return UNSUPPORTED_PLATFORM_API31_REQUIRED before the SharedMemory path. This is a scope boundary, not an API26 solution.

## Reproducible hardware run

Build the DEBUG APK and the app_process classpath artifact, then use the harness. The serial is a
development-machine argument and is never stored in source or documentation.

    .\\gradlew.bat :app:assembleDebug :app:packageDirectShellProbeDex
    .\\tools\\directshell\\Run-DirectShellFdSharedMemoryProbe.ps1 -Serial '<device-serial>' -Cycles 20

The harness installs the DEBUG APK, pushes warpnect-directshell-probe.jar to
/data/local/tmp, generates a fresh 32-byte secret and generation for each cycle, launches:

    CLASSPATH=/data/local/tmp/warpnect-directshell-probe.jar app_process /system/bin
      --nice-name=warpnect-directshell-fd-N
      io.warpnect.debug.directshell.WarpnectPrivilegedFdSharedMemoryProbeServer
      --port <random> --secret-base64 <fresh-secret> --generation <N>

It drives the normal DEBUG controller through arm, negative tests, A/B/C, close, finish, and
authenticated shutdown. It does not retry a failed cycle.

## Security model

A descriptor is issued only when all conditions hold:

1. The provider sees Binder.getCallingUid() equal to Process.SHELL_UID.
2. getCallingPackage() lets framework AppOps validate the incoming AttributionSource. A41 recorded caller_package=com.android.shell for the shell call.
3. The caller supplies a fresh per-launch 32-byte random HMAC-SHA-256 secret.
4. The proof binds WNFD magic, version 1, the current generation, and exactly 65,536 bytes.
5. A generation issues exactly one descriptor; replay is rejected.

The normal app's direct provider call was rejected as CALLER_UID_REJECTED, using UID 10367 in the recorded A41 run. Shell negative requests rejected stale generation, invalid magic, oversized metadata, replay, and invalid HMAC as GENERATION_REJECTED, PROTOCOL_REJECTED, METADATA_REJECTED, GENERATION_REPLAY_REJECTED, and AUTH_REJECTED.

The secret is never logged. This remains a DEBUG development trust model, not a final production secret-provisioning design.

## Runtime and Context evidence

Primary device: Samsung A41, SM-A415F, Android 12/API31.

    UID=2000, GID=2000, uid_kind=SHELL
    SELinux=u:r:shell:s0_
    ClassLoader=dalvik.system.PathClassLoader

The framework bridge recorded:

    package Context: package=com.android.shell, op_package=android
    shell identity Context: package=com.android.shell, op_package=com.android.shell
    AttributionSource: uid=2000, package=com.android.shell

The package Context is diagnostic only. Provider acquisition uses the valid shell identity Context; the server does not manufacture a Warpnect Application.

## Descriptor and memory proof

The normal app creates the 64 KiB region, maps it read/write, initializes APP_A, and returns the SharedMemory Parcelable only after validation. Binder duplicates the descriptor into the shell process. The shell maps it read/write and verifies the header and size.

    A  app writes APP_A, sequence 1       -> shell verifies
    B  shell writes SHELL_B, sequence 2   -> app verifies after original app FD close
    C  app writes APP_C, sequence 3       -> shell verifies

Generation 100020 recorded:

    APP_A    checksum 0x59ae110f
    SHELL_B  checksum 0xb7a1d92e
    APP_C    checksum 0xcf8f3d1c

Closing the app's original SharedMemory object before B while retaining its mapping, then observing SHELL_B, demonstrates the shared backing region rather than a byte copy. C proves the reverse direction again.

Ownership order:

1. The normal app creates and maps the region.
2. Framework Binder duplicates the descriptor to shell.
3. The app may close its original SharedMemory object while retaining its mapping.
4. The shell unmaps, closes its descriptor, and releases the external provider token.
5. The app unmaps, zeroes its secret, and releases the generation.

An additional notification/ack pipe descriptor handoff remains NOT_YET_PROVEN.

## Lifecycle and resource evidence

The final harness performed exactly 20 independent A41 cycles:

    create -> arm -> caller/HMAC/generation negatives -> FD handoff -> A/B/C
    -> unmap/close -> external-provider release -> app release -> authenticated shutdown

Result: 20/20 PASS, generations 100001 through 100020, no hidden retry.

Every shell cycle observed FD transition 55 -> 56 -> 55. The app final quiescent FD count was 95 for all 20 cycles. No warpnect-directshell-fd process or stale endpoint remained.

An earlier console-attached soak stalled after cycle 10 even though ADB was healthy and no server remained. It was an output-channel problem, classified INVALID_HARNESS_SOAK, and excluded from the 20/20 result. The final run redirected host output to report files and completed normally.

After adding the API31 runtime gate required by lint, the rebuilt APK and DEX completed one additional A41 install-and-run cycle: 1/1 PASS. The API31 handoff path is otherwise unchanged.

## Build and hardware artifacts

Validation for d55d820:

    :app:ktlintCheck                         PASS
    :app:lintDebug                           PASS
    :app:testDebugUnitTest --rerun-tasks     616 tests, 0 failures, 0 errors, 0 skipped
    :app:assembleDebug                       PASS
    :app:assembleDebugAndroidTest            PASS
    :app:packageDirectShellProbeDex          PASS

Native source is unchanged; existing Debug and Release CTest evidence remains 22/22 for each.

    app-debug.apk
      SHA-256: 2DB811F3C5FDD320718319657943BCFB4534536DCFFA7D1815C854F8F3A923D2
      bytes: 29,052,295
      ABIs: arm64-v8a, armeabi-v7a, x86_64

    warpnect-directshell-probe.jar
      SHA-256: 5B757E6BBD778014DF6D1340FC7C591A467CD315E3B3C20F5C390D7301FA06B9
      bytes: 10,121,809

The installed A41 APK SHA-256 matched the host APK exactly.

## Hardware matrix

| Device | FD handoff | Shared mapping | Bidirectional proof | Lifecycle |
|---|---|---|---|---|
| A41 API31 | PASS_FD_SHARED_MEMORY | PASS | PASS A/B/C | PASS 20/20 |
| S22 API36 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| Tablet API33 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |

## Fact, inference, unknown, proposal

FACT: A normal Warpnect DEBUG app can issue a bounded SharedMemory descriptor to a real shell app_process server on A41/API31 through framework-mediated Binder IPC. Both processes map the same region and observe deterministic bidirectional writes.

INFERENCE: DirectShell SystemAudio can plausibly retain the existing PCM Shared Ring architecture: the normal app can create the ring and hand it to a shell producer without placing PCM on Binder or the loopback TCP control plane.

UNKNOWN: This provider mechanism has not been demonstrated beyond A41/API31, has not handed off pipe descriptors, and relies on a hidden external-provider framework path. It is not a production compatibility commitment.

PROPOSAL: The next isolated experiment should connect the already-proven DirectShell REMOTE_SUBMIX capture to existing PCM Shared Ring semantics using a descriptor handoff, while leaving Shizuku production behavior unchanged.
