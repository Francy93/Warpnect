# DirectShell SystemAudio Modern Compatibility Campaign

## Scope

This Phase 6.5 DEBUG-only campaign evaluates the already validated
DirectShell SystemAudio path on two modern Hosts without making it a
production backend. It does not change WNCP, SCL, Audio Payload V1, PCM Shared
Ring V1, Shizuku production gateways, Video, Input, or MicrophoneAudio.

```text
Host normal Android AudioTrack tone
  -> Warpnect-owned app_process server (shell UID 2000)
  -> AudioRecord(REMOTE_SUBMIX)
  -> existing SharedPcmAudioRingLayout V1 and notify/ack descriptors
  -> existing SharedPcmAudioDrain in the normal app
  -> existing Opus / Audio Payload V1 / protected SCL / UDP
  -> existing Client decode, PCM Playback Ring, and Oboe consumer
```

The DEBUG DirectShell bridge is selected explicitly. The normal production
audio selector remains Shizuku-backed unless a DEBUG source has been armed.
There is no DirectShell-to-Shizuku audio fallback.

## Runtime API telemetry correction

The DirectShell DEX is compiled once but runs under multiple framework
releases. On the S22, `Build.VERSION.SDK_INT` in the probe reported the stale
compile-time value `31` while `getprop ro.build.version.sdk` reported `36`.
This campaign adds a bounded, shell-local `getprop` read to the DEBUG probe and
labels the source as `api_source=ro.build.version.sdk`; it falls back to the
framework value only if that read is unavailable. API gates and diagnostics in
the probe now use that runtime value. This is a DEBUG-only diagnostic fix, not
a platform or production-audio behavior change.

The remote E2E harness also now rejects a counted `-ReuseRunningApps` attempt
unless both installed Warpnect instances visibly report `Ready`. This excludes
post-install process-start contamination; it does not restart an app, add a
retry, or change Session behavior.

## Hardware and provider state

| Role | Device | API | Build fingerprint | Shizuku state |
|---|---|---:|---|---|
| Reference Host | Samsung A41 | 31 | historical Phase 6.5 reference | not used for DirectShell audio |
| Host under test | Samsung S22 (SM-S901B) | 36 | `samsung/r0sxeea/r0s:16/BP2A.250605.031.A3/S901BXXSOGZH3:user/release-keys` | 13.6.0.r1086.2650830c present, not audio producer |
| Host under test | Aocwei X700_EEA tablet | 33 | `Aocwei/X700_EEA/X700_EEA:13/TP1A.220624.014/2023264:user/release-keys` | 13.6.0.r1086.2650830c present |
| Client | Samsung S9 (SM-G960F) | 29 | `samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXUHFVG4:user/release-keys` | unchanged Client path |

Both modern Hosts used the same DEBUG APK:

```text
SHA-256: DE36DB87E5C93858C378E17AD8FE0703834E809F8DC614E6E6B6C8BF3BFA2987
Size:     29,275,359 bytes
```

## Validation gates

The hardware observations above are tied to that installed artifact. The
post-campaign source gates passed with 623 JVM tests, zero failures, zero
errors, and zero skipped tests:

```text
:app:ktlintCheck                         PASS
:app:lintDebug                           PASS (0 errors; 68 existing warnings)
:app:testDebugUnitTest --rerun-tasks     PASS
:app:assembleDebug                       PASS
:app:assembleDebugAndroidTest            PASS
:app:packageDirectShellProbeDex          PASS
```

The final gate build produced a DEBUG APK with SHA-256
`E26F2A4017423919D6D547F3051EE8EB8132697ACF3E67C6DAF24A27851A206A`, size
29,101,551 bytes, at 2026-09-19 16:37:01 UTC. The only source adjustment
after the installed hardware artifact was static `@TargetApi` annotation for
lint; the DirectShell runtime behavior and test path were unchanged.

## S22 / API36

The S22 DirectShell bootstrap succeeded with `uid=2000`, `gid=2000`,
`uid_kind=SHELL`, `selinux_context=u:r:shell:s0`,
`dalvik.system.PathClassLoader`, and runtime `api=36`. The shell-oriented
context reported:

```text
package_name=com.android.shell
op_package_name=com.android.shell
attribution_uid=2000
attribution_package=com.android.shell
shell_package_uid=2000
record_audio_app_op_mode=0
```

Standalone `REMOTE_SUBMIX` used PCM16, 48 kHz, stereo, and a 15,360-byte
buffer. A silent window produced zero PCM. During the 997 Hz normal Android
tone, it produced 84,480 frames, 337,920 bytes, 161,212 non-zero samples,
peak 8,191, RMS 5,662.623, and no read error. Standalone prepare/start/sample/
stop passed 10/10.

The same existing SharedMemory plus notify/ack descriptor contract passed
10/10. The normal-process drain observed real tone PCM; one representative
tone window recorded RMS 5,790.757, 178,048 non-zero samples, no drain error,
final occupancy zero, and high-water mark 5 of 8.

One Ready-preflighted S22 -> S9 Session completed technical DirectShell E2E.
The source log explicitly recorded
`shizuku_audio_producer=false`. During the measured tone interval, the normal
Host drain observed RMS 5,795.749 and 2,936,376 non-zero samples. The shell
recorded 3,852,240 captured frames, 3,850,320 written frames, 8 bounded drops,
and no read error. The regular Host runtime recorded 16,990 Opus/payload
frames, 16,991 sent datagrams, zero WouldBlock, and zero transport failure.
The S9 recorded 17,403 audio datagrams, 17,401 decoded frames, 4,176,240
decoded PCM frames, 3,826,800 playback-ring frames written, 3,826,512
consumed, and zero xruns, decoder failures, receiver error, or playback error.

The remaining two counted S22 -> S9 attempts did not establish technical
audio evidence because the Client terminated at `InvalidPresence` and
`LifecycleStartFailed`, respectively. Both occurred outside the DirectShell
AudioRecord/ring boundary. Therefore the formal remote matrix is **1/3
technical PASS**, not 3/3. Host-local audibility and Client human audibility
were not deliberately confirmed: `UNKNOWN` and `NOT_CONFIRMED`.

### S22 attribution answer

**Yes.** The earlier Shizuku path used shell UID 2000 with app attribution
`io.warpnect` and reached an uninitialized AudioRecord. DirectShell uses the
real `com.android.shell` package, op-package, and AttributionSource, and on
this S22 the AudioRecord initialized, entered `RECORDING`, and captured real
playback PCM. The previous attribution boundary is absent for this path.

## Tablet / API33

With Shizuku 13.6 still installed and its server running, the tablet
DirectShell bootstrap independently succeeded as UID/GID 2000 in
`u:r:shell:s0`, with runtime `api=33`. No Shizuku UserService was involved in
the DirectShell bootstrap or standalone audio test.

Standalone `REMOTE_SUBMIX` used PCM16, 48 kHz, stereo, and a 15,360-byte
buffer. The silent window was all-zero. The 997 Hz tone produced 88,320
frames, 353,280 bytes, 157,374 non-zero samples, peak 2,057, RMS 1,374.429,
and zero read errors. Standalone lifecycle passed 10/10.

The existing PCM ring also passed 10/10 with the same external framework FD
handoff (`SharedMemory`, notify write FD, and ack read FD). A representative
tone drain reported 94,080 frames, 188,080 non-zero samples, peak 2,057, RMS
1,455.868, final occupancy zero, high-water mark 5, and zero drain error.
The shell reported normal notify/ack progression and no read error.

This is direct evidence that DirectShell bypasses the tablet's documented
Shizuku 13.6 pre-Binder `LoadedApk.makeApplication` regression for SystemAudio:
the problematic provider remained installed, yet shell DirectShell captured
and delivered real PCM to the normal app.

Tablet -> S9 remote E2E was attempted three times after the two apps had
visibly reached `Ready`. Every attempt stopped before discovery because Host
capability collection entered `input_injection` and did not complete in the
official 15-second Host-readiness window:

```text
video_encoder complete
video_decoder complete
system_audio complete
microphone complete
audio_encoder complete
input_injection started
Host did not reach Waiting for clients
```

The current Input capability path is still
`AndroidInputInjectionController -> ShizukuInputInjectionGateway ->
PrivilegedInputInjectionUserService`. The earlier provider experiment proved
that this tablet/Shizuku 13.6 combination dies before UserService Binder
publication. This campaign did not modify, restart, downgrade, or replace that
provider. The exact current E2E boundary is therefore
`TABLET_HOST_INPUT_INJECTION_CAPABILITY_QUERY_NOT_COMPLETED_BEFORE_DISCOVERY`,
not a DirectShell audio failure. Remote E2E and human audibility remain
`NOT_TESTED` for DirectShell audio on this tablet.

## Compatibility matrix

| Layer | A41 API31 reference | S22 API36 | Tablet API33 |
|---|---|---|---|
| DirectShell bootstrap / shell identity | PASS | PASS | PASS |
| REMOTE_SUBMIX prepare/start | PASS | PASS | PASS |
| Real playback PCM | PASS | PASS | PASS |
| Existing PCM ring / notify / ack / drain | PASS | PASS | PASS |
| Standalone/ring lifecycle | PASS | 10/10, 10/10 | 10/10, 10/10 |
| Remote Opus/SCL/Client playback | 5/5 technical PASS | 1/3 technical PASS | NOT_TESTED: blocked before discovery by existing Input provider |
| Human Client audio | NOT_CONFIRMED | NOT_CONFIRMED | NOT_CONFIRMED |
| Host-local audio | AUDIBLE (reference) | UNKNOWN | UNKNOWN |

No device, OEM, or blind API-level branch was added. No production capability
advertisement changed. Bounded pressure remained bounded: the S22 remote path
observed high-water 8 and 8 producer drops with final occupancy one, while the
Session, transport, decoder, and Oboe consumer stayed healthy. This campaign
does not tune the frozen ring.

## Lifecycle and cleanup

After every standalone and ring cycle, the shell server logged controlled
close and `PROCESS_EXIT code=0`; no `warpnect-directshell`/probe `app_process`
remained on either modern Host. Fresh ring generations were issued for every
cycle and the FD soak ceiling remained stable (S22: 116; tablet: 123). The
tablet's normal application was intentionally left in its observed
Input-capability wait rather than force-stopped to manufacture a clean state.

## Conclusion

FACT: The unchanged DirectShell implementation provides real shell-identity
`REMOTE_SUBMIX` capture and the existing PCM ring contract on API31, API33,
and API36.

FACT: On S22/API36, shell `com.android.shell` attribution removes the prior
Shizuku `io.warpnect` AudioRecord initialization boundary and reaches one full
technical remote Session.

FACT: On the tablet/API33, DirectShell SystemAudio works while the known
Shizuku 13.6 UserService bootstrap incompatibility remains present.

INFERENCE: DirectShell SystemAudio is sufficiently portable at the
privileged-audio boundary across these modern API levels to justify a later
production-backend abstraction investigation.

UNKNOWN: The campaign does not establish 3/3 remote Session reliability on
S22, tablet DirectShell remote E2E while Input still depends on Shizuku 13.6,
or human audibility on the new Hosts.

PROPOSAL: First isolate the tablet's pre-discovery Input UserService dependency
under Shizuku 13.6, or decouple that debug Session prerequisite from the
DirectShell audio experiment, before treating tablet remote audio as blocked
by DirectShell.

Decision: `DIRECTSHELL_SYSTEM_AUDIO_MODERN_COMPATIBILITY_PARTIAL`.
