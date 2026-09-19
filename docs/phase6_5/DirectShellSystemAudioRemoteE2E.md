# DirectShell SystemAudio Remote E2E Spike

## Scope

This Phase 6.5 DEBUG-only spike validates a Warpnect-owned `app_process` shell
SystemAudio producer through the existing downstream audio and Session stack. It
does not make DirectShell a production backend and does not alter WNCP, SCL,
Audio Payload V1, PCM Shared Ring V1, Video, Input, or MicrophoneAudio.

```text
A41 normal AudioTrack tone (997 Hz, PCM16, 48 kHz, stereo)
  -> DirectShell AudioRecord(REMOTE_SUBMIX), shell UID 2000
  -> existing SharedPcmAudioRingLayout V1 plus notify/ack FDs
  -> existing SharedPcmAudioDrain in the normal app process
  -> existing Opus encoder and Audio Payload V1
  -> existing protected SCL / UDP Session
  -> S9 existing receiver, Opus decoder, PCM Playback Ring, and Oboe consumer
```

The authenticated loopback control channel only orchestrates the DEBUG probe.
PCM uses the existing local shared ring and existing protected network path;
it is not copied over control TCP.

## Source selection and production behavior

The narrow seam is the existing Host `AudioCaptureController`. The new
`SystemAudioCaptureControllerSelector` returns the established
`AndroidSystemAudioCaptureController` in release and ordinary DEBUG runs. A
process-local DEBUG receiver must explicitly install
`DirectShellSystemAudioRemoteE2eSource` before it can substitute the source.

No WNCP capability, persistent cache, protocol, or default production behavior
changes. There is no DirectShell-to-Shizuku fallback. The Host log proves the
selected source for every run:

```text
DIRECT_SHELL_REMOTE_E2E_SOURCE_SELECTED ... source=REMOTE_SUBMIX \
  ring=SharedPcmAudioRingV1 shizuku_audio_producer=false
```

The existing Shizuku runtime was available only for unchanged Host Input
requirements of the real Session harness, never as the audio producer.

Changes in this spike:

- DEBUG-only source, receiver, source contract, harness, manifest receiver,
  and the existing DirectShell ring-session downstream-drain hook.
- Focused JVM contract tests.
- A small main-source composition selector used by the existing Session
  composition and bindings. It is fenced by `BuildConfig.DEBUG` and explicit
  registration.

## Hardware evidence

| Role | Device | API |
|---|---|---:|
| Host | Samsung A41 (SM-A415F) | 31 |
| Client | Samsung S9 (SM-G960F) | 29 |

Both devices used the same DEBUG APK:

```text
SHA-256: A120C8BD44B5196BE1FB1225F8D6CFAC9CF46AF98AD7A63BC084FCA457E040D1
Size: 29,101,551 bytes
```

The shell server recorded `uid=2000`, `uid_kind=SHELL`,
`selinux_context=u:r:shell:s0`, `package_name=com.android.shell`,
`op_package_name=com.android.shell`, valid shell attribution, and
`PCM_RING_FD_HANDOFF_OK` with framework caller UID 2000.

Each Session used a new HMAC secret, ring generation, descriptor set, and
shell process. The official harness performed real discovery, SAS,
authentication, setup, media startup, and semantic teardown without retries.

## Silent-before-tone and remote E2E matrix

The formal matrix used a 60-second 997 Hz Host tone after a measured silent
window. The normal-process drain measured RMS `0.000` before tone and real
non-zero PCM after tone in every cycle.

| Cycle | Tone RMS | Host payloads | Host datagrams | S9 decoded | S9 consumed | S9 xruns | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 5826.803 | 16,944 | 16,945 | 17,122 | 1,280,592 | 0 | PASS |
| 2 | 5826.839 | 16,945 | 16,946 | 16,194 | 3,746,016 | 0 | PASS |
| 3 | 5826.802 | 16,960 | 16,961 | 17,173 | 3,950,640 | 0 | PASS |
| 4 | 5826.808 | 16,946 | 16,947 | 17,153 | 3,963,840 | 0 | PASS |
| 5 | 5826.786 | 16,975 | 16,976 | 17,188 | 3,966,480 | 0 | PASS |

Result: **5/5 first-attempt technical PASS**. Each cycle showed shell
AudioRecord initialized and recording; normal-process ring drain wakeups and
non-zero PCM; Host Opus frames, Audio Payload V1 frames, and UDP sends; and
S9 receive, decode, playback-ring writes, and playback consumption. The S9
backend stayed `Running`, with `receiver_error=None` and `playback_xruns=0`.
Host video capture/transport and S9 video decode/surface presentation remained
healthy alongside audio.

## Bounded backpressure observations

The DirectShell producer uses the existing eight-slot ring. The 60-second
cycles observed 23 to 38 bounded producer drops during cold/start pressure,
with no drain error, transport error, Session failure, or growing queue.

The concurrent V1 header high-water diagnostic occasionally observed 9 while
the ring has eight physical slots. This is not a ninth allocation:
`SharedPcmAudioRingLayout.beginWrite()` scans only the eight slot states and
returns `-1` when none is free. It is a non-atomic diagnostic-header
observation, not evidence of an unbounded queue. This spike does not redesign
the frozen ring contract.

The S9 playback ring reached its 960-frame high-water mark. One formal cycle
reported sticky `PlaybackRingFull`; existing receiver semantics intentionally
treat it as a bounded real-time drop, not a terminal pipeline error. The
consumer remained running, PCM consumption continued, and xruns stayed zero.
The harness records this condition explicitly and still rejects all other
playback errors, stopped consumers, xruns, and missing consumption.

## Lifecycle and human confirmation

The formal 5/5 matrix and a separate 120-second tone Session completed
authenticated shutdown, descriptor/ring release, source disarm, and semantic
Session teardown. Afterwards the A41 and S9 UIs were both `Ready` and no
DirectShell process remained.

The 120-second Session also passed technically:

```text
Host capture frames:       6,739,120
Host encoded/payload:         28,914
Host datagrams sent:          28,915
S9 audio datagrams:           29,132
S9 decoded frames:            29,130
S9 playback consumed:      6,724,512
S9 playback xruns:                 0
```

An in-window listening confirmation was requested, but no response arrived
while the tone was active. The truthful human result is therefore
`NOT_CONFIRMED`; technical counters are not promoted to physical audibility.
The A41 Host-local speaker remained audible during REMOTE_SUBMIX capture, an
observation only and not a routing-semantics guarantee.

## Validation

```text
:app:ktlintCheck                   PASS
:app:lintDebug                     PASS (0 errors; existing warnings reported)
:app:testDebugUnitTest --rerun-tasks
  621 tests, 0 failures, 0 errors, 0 skipped
:app:assembleDebug                 PASS
:app:assembleDebugAndroidTest      PASS
:app:packageDirectShellProbeDex    PASS
```

Native source is unchanged; existing Debug and Release CTest evidence remains
22/22 for each.

## Conclusion

FACT: On A41/API31, DirectShell `REMOTE_SUBMIX` capture reached the existing
ring, existing Host Opus and Audio Payload V1 path, protected Session, and
existing S9 decode/playback consumer without Shizuku as the audio producer.

INFERENCE: DirectShell SystemAudio is a credible candidate backend below the
existing downstream audio and protocol contracts for equivalent environments.

UNKNOWN: This is one A41/API31 Host and one S9/API29 Client. It does not prove
DirectShell compatibility on S22/API36, Tablet/API33, Android 11, API26, other
OEMs, or long-duration routing behavior.

PROPOSAL: Run a DirectShell SystemAudio compatibility campaign on S22/API36
and Tablet/API33 before considering production capability or backend-selection
work.

Decision: `DIRECTSHELL_SYSTEM_AUDIO_REMOTE_E2E_TECHNICALLY_VALIDATED`.
