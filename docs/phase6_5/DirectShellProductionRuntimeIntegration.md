# DirectShell Production Runtime Integration

## Scope

This Phase 6.5 DEBUG integration joins the previously qualified DirectShell
bootstrap, SystemAudio, Input, and video-capture paths behind one normal Host
enable flow. It does not remove Shizuku from the repository, enable a release
runtime, implement DirectRoot, or expose a generic shell-command API.

```text
Enable Host
  -> PrivilegedRuntimeResolver
  -> DirectShellRuntime
  -> user-authorized Wireless Debugging TLS ADB
  -> known installed base.apk verification
  -> app_process as shell uid 2000
  -> authenticated Audio / Input / Video feature servers
  -> capability qualification
  -> Host discovery and Session
```

The runtime is acquired lazily on Host enable. It is not started during normal
application startup and remains separate from the temporary Shizuku path.

## Resolver and security boundary

`PrivilegedRuntimeResolver` retains the product ordering without pretending
that unavailable runtimes exist:

```text
DirectRoot        -> ROOT_NOT_IMPLEMENTED
DirectShell       -> selected on Android 11+ when Wireless Debugging is ready
Client-only       -> truthful result when DirectShell cannot be acquired
Temporary Shizuku -> retained in source during migration, never used by the
                     counted DirectShell Host runs
```

`DirectShellRuntime` owns endpoint discovery, the persisted app ADB key,
installed-artifact SHA-256 verification, purpose-limited `app_process`
launches, a fresh generation and secret for each server, identity validation,
and authenticated shutdown. The ADB surface stays closed: callers cannot send
an arbitrary command, class, artifact, or file descriptor through it.

The secret is transferred as bounded TLS ADB stream data, never in a shell
argument, then consumed from a generation-scoped restricted file before the
server accepts HMAC-SHA-256 control. Invalid HMAC and stale generations remain
rejected. Teardown removes the server and its generation-scoped handoff file.

## Wireless Debugging state and recovery

The runtime reports the distinction between `WIRELESS_DEBUGGING_DISABLED`,
`UNPAIRED`, endpoint discovery, ADB connection, server launch, feature
qualification, and `RUNTIME_READY`. Host capability qualification begins only
after runtime readiness.

On the Tablet, disabling Wireless Debugging produced the bounded truthful
result `DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED`, with no shell server, secret
file, or Shizuku fallback. Re-enabling it exposed a framework/mDNS transition:
the first `_adb-tls-connect._tcp` result could describe a closed endpoint.

The recovery is deliberately narrow:

```text
first local endpoint
  -> TCP ConnectException wrapped by the ADB library as IOException
  -> discard only that exact endpoint and close its transport manager
  -> one bounded rediscovery excluding the refused endpoint
  -> connect one newly resolved local endpoint
```

All other I/O, TLS, authorization, and discovery failures remain terminal. The
implementation does not sleep-and-retry, retain a port, or mask a pairing
failure. Unit tests cover exclusion of the refused endpoint, acceptance of a
replacement endpoint, and recognition of the ADB library's wrapped
`ConnectException`.

The final Tablet hardware recovery passed with the DEBUG APK:

```text
SHA-256: 74DA65065AA4CCC448F0DF35C42534F9C81FCABA0ACD1B11B0EADDD4FEA1A702
Size:    33,378,725 bytes
Built:   2026-09-23 02:50:37 UTC
```

It followed the refused stale endpoint with one different local endpoint,
validated shell identity, verified the installed base.apk, started all three
feature servers, and reached `Host: Available`. A subsequent semantic Disable
left no current-run DirectShell process or secret file. The same APK hash was
verified on Tablet, S9, and A41 before the final quiet validation.

## Feature ownership in counted modern runs

| Privileged feature | Counted provider | Shizuku provider |
|---|---|---|
| SystemAudio | DirectShell | false |
| Input injection | DirectShell | false |
| Video capture | DirectShell | false |
| Runtime bootstrap | app-owned Wireless Debugging / DirectShell | false |

DirectShell continues to use the existing PCM Shared Ring, input resolver,
display-mirror implementation, encoder, Opus, SCL, packet formats, Session,
decoder, and Client playback architecture. No protocol or ABI contract changed.

## Quiet hardware evidence

All final runs below were deliberately silent: no controlled `AudioTrack`
tone, display pulse, or human-audibility request was made during nighttime
validation.

| Device | API | Runtime acquisition | Quiet Host result | Cleanup |
|---|---:|---|---|---|
| Tablet X700_EEA | 33 | PASS, including stale-endpoint recovery | PASS: `Host: Available`; Tablet -> S9 Session PASS | PASS |
| Samsung A41 | 31 | PASS | PASS: `Host: Available` | PASS |
| Samsung S22 | 36 | NOT_TESTED_DEVICE_UNAVAILABLE | NOT_TESTED_DEVICE_UNAVAILABLE | NOT_TESTED |
| Samsung S9 | 29 | Client only in this campaign | Tablet -> S9 Client Session PASS | PASS |

The final Tablet -> S9 silent Session used the exact APK above on both
devices. It passed first-attempt authentication, capability qualification,
setup, video-channel readiness, media start, Host AVC configuration/access
units/datagrams, and Client datagram/config/decoder/access-unit output. The
initial bounded harness snapshot occurred before the delayed legacy rendering
callback, while the same run's log later recorded `first_frame_rendered` and
surface release. Rendering telemetry is supplemental in any case. The prior
synchronized screenshot evidence and changing-image test remain the visual
acceptance evidence for this topology.

The quiet run exercises SystemAudio lifecycle and transport without asserting
human audibility. Human audio is therefore `AUDIBLE_DEFERRED_NIGHTTIME`, not
`AUDIBLE_CONFIRMED`.

Earlier counted DirectShell input evidence remains applicable: normal Client
input reached the existing Input Payload V1 and SCL receiver, then the Host
DirectShell injector and visible Host response. Aggregate sender/receiver
counters showed ordered key and touch delivery with no malformed, mapping, or
injection failure. No input payload contents or peer addresses are retained in
the runtime diagnostics.

## Lifecycle

The intended lifetime is Host-mode scoped:

```text
Host enable -> acquire runtime -> qualify features -> one or more Sessions
Host disable -> reset input -> stop capture/audio -> release descriptors
             -> authenticated DirectShell shutdown -> clear overrides
```

The final Tablet recovery cycle, quiet Tablet -> S9 Session, the final
Tablet Host-only acquisition/release cycle, and the A41 host cycle each ended
with zero current-run DirectShell processes and zero generation-scoped secret
files. The final Host-only cycle observed all three feature servers as shell
processes before semantic Disable. The observed harness condition where
Samsung Settings remained foreground after visiting Developer Options is
explicitly separate from runtime behavior; returning to Warpnect before a
desktop-driven harness run resolves that surface condition.

## Status and remaining work

FACT: a normal Warpnect DEBUG Host can self-bootstrap DirectShell through a
user-authorized Wireless Debugging connection, without desktop `app_process`
launch and without Shizuku providing bootstrap, audio, input, or video.

FACT: the modern integration has final quiet hardware evidence on Tablet/API33
and A41/API31, including bounded recovery after Wireless Debugging was toggled.
The final Gradle validation recorded 674 JVM tests with zero failures, errors,
or skips; ktlint and lintDebug passed, and rebuilt native Debug and Release
CTest each passed 22/22.

UNKNOWN: current S22/API36 self-bootstrap qualification, because the device
was not connected for this run.

NOT YET CONFIRMED: human SystemAudio audibility on this exact final runtime
build; it was intentionally deferred for nighttime validation.

PROPOSAL: qualify the S22 self-bootstrap path and perform the deliberate
daytime audible confirmation, then proceed with the explicit Shizuku dependency
removal and final modern Host regression task. Do not implement DirectRoot as
part of this integration.

## Final Human-Audible SystemAudio Validation

This addendum supersedes the earlier daytime-audibility TODO. It used the
final DEBUG artifact built after the display-reconfiguration regression fix:

```text
SHA-256: 61A6260044BEF19BA3ED5FA3DD3DB262EE9212BD8E74F00E29EF60DF0DE6137D
Size:    33,378,725 bytes
Built:   2026-09-23 13:38:18 UTC
```

The identical APK hash was verified on each counted Host and on the S9 Client
before Session startup. No `adb install` process overlapped a counted Session.
The normal Host UI acquired DirectShell through the app-owned Wireless
Debugging path; desktop ADB only installed, observed, and drove the bounded
normal Android tone fixture. Shizuku was not the bootstrap, video, audio, or
input provider in the counted DirectShell Host runs.

| Host -> Client | Technical result | Human result | Notes |
|---|---|---|---|
| Tablet API33 -> S9 API29 | PASS | `AUDIBLE_CONFIRMED` | Previously confirmed by the operator using the same normal `AudioTrack` 997 Hz fixture. |
| S22 API36 -> S9 API29 | PASS | `AUDIBLE_CONFIRMED` | The operator explicitly confirmed that the S9 emitted the repeated 8-second 997 Hz tone. |
| A41 API31 -> S9 API29 | PASS | `AUDIBLE_CONFIRMED` | A self-bootstrapped DirectShell Host completed a normal Session and the operator heard the controlled tone on S9. |

For the S22 -> S9 run, DirectShell was explicitly selected as the Host source:

```text
source=REMOTE_SUBMIX
ring=SharedPcmAudioRingV1
shizuku_audio_producer=false
shell package/op-package=com.android.shell
shell uid=2000
```

Before the tone, the S22 capture, encoder, payload, and datagram counters and
the S9 receiver, decoder, playback-ring, and Oboe-consumer counters were all
advancing. During the first post-fix tone window, the normal-process drain
reported real PCM with 771,774 non-zero samples, peak 8,191, and non-zero RMS.
The Host emitted no transport error, send failure, or WouldBlock event. The S9
continued to decode and consume PCM with zero Oboe xruns. Its bounded
`PlaybackRingFull` rejections remained the existing real-time drop policy:
the ring did not grow, its consumer continued, and the final occupancy was
bounded.

The first S22 tone attempt exposed a genuine unrelated regression: opening the
fixture changes display configuration, and the main-looper display callback
performed DirectShell video-control socket I/O. Android correctly raised
`NetworkOnMainThreadException`, which ended that Host process before it could
count as an audio result. The generic correction dispatches display-mirror
reconfiguration on `Dispatchers.IO`; no device, OEM, API-level, protocol, or
Shizuku rule was added. A focused JVM test protects the dispatcher selection.
The rebuilt S22 -> S9 Session remained `Streaming` through two subsequent
fixture executions, then completed semantic Disconnect and Disable Host with
zero current-run DirectShell processes and zero secret files.

The A41 -> S9 audible spot-check used the same exact APK on both devices and
the normal app-owned DirectShell acquisition path. Desktop ADB installed,
observed, and drove the bounded normal Android fixture, but did not launch
`app_process`. The A41 runtime reported DirectShell for SystemAudio, Input,
and Video, with all Shizuku privileged producers false. The 20-second
PCM16/48 kHz/stereo 997 Hz `SystemAudioE2eToneActivity` wrote 964,616 fixture
frames. During the same streaming interval, Host capture, encoder, payload,
and datagram counters advanced without WouldBlock, send failure, or transport
error; the S9 receiver, decoder, playback ring, and playback consumer also
advanced with zero xruns. The operator explicitly confirmed that the S9 emitted
an audible tone. Semantic Disconnect and Disable Host returned both apps to
Ready, with zero A41 current-run DirectShell processes and secret files.

The initial A41 observation was separate from SystemAudio: Wireless Debugging
was enabled and the app was paired, but neither the app's NSD discovery nor
desktop ADB mDNS observation saw a local `_adb-tls-connect._tcp` connection
service. The resolver therefore returned the truthful bounded
connection-service-not-discovered result, launched no shell server, and did
not advertise a Host capability.

## A41 Wireless Debugging service-publication investigation

FACT: pairing and connection-service publication are distinct adbd states. The
A41 retained Warpnect in Android's paired-device list while its own
`_adb-tls-connect._tcp` record was absent.

FACT: a controlled off/on transition of the standard Wireless Debugging switch
caused the A41 connection service to reappear. Warpnect then resolved its own
DNS-SD record, established its app-originated local ADB connection, verified
shell UID 2000 and the installed artifact, launched the audio/input/video
DirectShell servers, and reached `Host: Available`. Semantic Disable Host left
zero current-run shell processes and zero secret files. No Session or audio
fixture was started for this investigation.

FIX: connection-service discovery now allows one bounded 30-second publication
grace after Wireless Debugging is enabled. Pairing discovery remains bounded at
eight seconds. This is not a retry loop and has no device, OEM, or API-level
branch; it accounts for the asynchronous TLS-service publication documented by
AOSP for Wireless Debugging.

CLASSIFICATION: `A41_WIRELESS_DEBUGGING_CONNECTION_SERVICE_PUBLICATION_DELAY_RECOVERED`.
It is a bootstrap-environment issue, not an AudioRecord, SystemAudio, or
DirectShell feature failure. If no local connection service appears within the
bounded grace, Warpnect must remain truthfully unavailable and direct the user
to reactivate Wireless Debugging; an ordinary app cannot legitimately force
adbd to republish the service.

The final A41 verification used this exact DEBUG APK:

```text
SHA-256: BB29988D85971AE19F6E229951F4BAFB3FF418508EC23FC23113DF4FCA636703
Size:    33,378,725 bytes
Built:   2026-09-23 14:42:14 UTC
```

The installed base APK hash matched. With no pending installation activity, one
normal `Enable Host` reached `Host: Available` with DirectShell audio, input,
and video feature servers; its final `Disable Host` returned to `Host: Not
checked` with no current-run shell process or secret file. The run was
bootstrap-only and intentionally did not start a Session, video stream, or
audio fixture.

The supporting platform reference is AOSP's Wireless Debugging architecture:
when Wireless Debugging enables the TLS server, adbd publishes the
`_adb-tls-connect._tcp` DNS-SD service asynchronously. See
https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md.

## Consolidated Validation Status

The later final audible spot-check completed the last modern SystemAudio matrix
cell: A41/API31 to S9/API29 is AUDIBLE_CONFIRMED. Together with the already
confirmed Tablet/API33 and S22/API36 results, the modern DirectShell
production-runtime evidence is:

    A41 API31    -> S9 API29    AUDIBLE_CONFIRMED
    Tablet API33 -> S9 API29    AUDIBLE_CONFIRMED
    S22 API36    -> S9 API29    AUDIBLE_CONFIRMED

The full modern privileged feature baseline is therefore
DIRECTSHELL_PRODUCTION_RUNTIME_INTEGRATION_VALIDATED. This conclusion is
limited to the tested DirectShell architecture and hardware evidence. It does
not delete Shizuku, make DirectShell the release default, or claim that a
future composite provider plan has been implemented.
