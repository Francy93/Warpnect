# DirectShell SystemAudio to Existing PCM Shared Ring Integration Spike

## Scope and decision

This DEBUG-only Phase 6.5 spike answers one narrow question on the tested
hardware: can a Warpnect-owned `app_process` shell producer write real
`REMOTE_SUBMIX` PCM into the unchanged production PCM Shared Ring V1 and have
the normal app drain it through the unchanged `SharedPcmAudioDrain`?

It does not change the production Shizuku backend, capability negotiation,
Session, SCL, Opus, Audio Payload V1, Video, Input, or native code. No
`src/main` production behavior changed. The new code is in `src/debug`, a
focused JVM test, and a DEBUG hardware harness.

Result on the A41 / Android 12 / API31:

```text
Android AudioTrack tone
  -> DirectShell shell AudioRecord(REMOTE_SUBMIX)
  -> unchanged PCM Shared Ring V1
  -> real notify and ack pipe descriptors
  -> unchanged normal-process SharedPcmAudioDrain
  -> non-zero normal-process PCM metrics
```

Decision: `DIRECTSHELL_PCM_SHARED_RING_INTEGRATION_FEASIBLE`.

## Existing production ring contract

The authoritative production implementation remains:

- `PrivilegedAudioCaptureUserService.kt`
- `SharedPcmAudioDrain.kt`
- `SharedPcmAudioRingLayout.kt`
- `PcmRingSignalRecord.kt`

The production privileged producer creates and maps `SharedMemory`, initializes
PCM Shared Ring V1, and creates two pipes. It retains the producer endpoints;
the normal process receives the complementary endpoints and starts
`SharedPcmAudioDrain` before capture starts.

```text
producer                              normal app
--------                              ----------
SharedMemory RW mapping               SharedMemory mapping in SharedPcmAudioDrain
notify pipe write end  ---- notify -> notify pipe read end
ack pipe read end       <- ack ------ ack pipe write end
```

Ring V1 is unchanged: magic `WAPC` (`0x57415043`), version 1, 64-byte header,
64-byte slot metadata, eight slots, and a 960-byte PCM payload per slot for
48 kHz stereo PCM16 5 ms chunks. A producer with no free slot receives `-1`
from `beginWrite()`, which increments the existing overrun counter; it reads
the current chunk into its discard buffer and records a dropped chunk instead
of growing an unbounded queue.

## Integration architecture

The spike replaces only the privileged producer setup and producer process.
The normal consumer and ring layout are the production classes.

```text
normal Warpnect DEBUG app                         shell app_process, UID 2000
--------------------------------                  ---------------------------
DirectShellPcmRingProbeSession                    WarpnectPrivilegedPcmSharedRingProbeServer
  creates SharedMemory + both pipes                 shell Context/AttributionSource
  initializes exact PCM Shared Ring V1              AudioRecord(REMOTE_SUBMIX)
  starts actual SharedPcmAudioDrain                 writes exact V1 slots
               |                                    signals notify pipe
               | ContentProvider external path     drains ack pipe
               +--- SharedMemory, notify-write, ack-read --->
```

`DirectShellPcmRingProbeContentProvider` has exactly one DEBUG-only operation,
`open_pcm_ring_v1`. It does not expose a file API, a query API, arbitrary
commands, generic file descriptors, or arbitrary file opening. The shell
acquires it through the previously validated external ContentProvider framework
route. Loopback TCP continues to carry only bounded authenticated control
requests and diagnostics; it never carries PCM or emulates descriptor passing.

## Descriptor and signal proof

Each A41 cycle transferred all three live descriptors through real framework
IPC:

1. `SharedMemory` for the exact V1 ring backing region.
2. The producer notify-write `ParcelFileDescriptor`.
3. The producer ack-read `ParcelFileDescriptor`.

The shell reported a stable FD transition of `53 -> 56 -> 53` on every cycle:
the three additional descriptors were present after handoff and gone after
remote close. The normal app retained its complementary pipe ends inside the
actual drain.

Before audio capture, every cycle performed an explicit real signaling
transaction using the production structures:

```text
SHELL_WRITE marker=ZERO_PCM_FRAME
NOTIFY
APP_WAKE_APP_READ
SHELL_ACK_OBSERVED
```

This passed in 10/10 formal soak cycles. It proves the shell wrote an actual
V1 slot, the normal process woke on the real notify pipe and read that slot via
`SharedPcmAudioDrain`, and the shell received the actual ack record. The
producer's aggregate ack statistic is intentionally a non-blocking snapshot
and may trail notification count by a few records at an instantaneous sample;
the marker transaction is the authoritative symmetric ack proof.

## Security model and negative evidence

A descriptor set is issued only when all of the following are true:

1. The provider observes `Binder.getCallingUid() == Process.SHELL_UID`.
2. The framework identifies the real shell caller as `com.android.shell`.
3. The request carries a fresh random 32-byte HMAC-SHA-256 secret.
4. The proof binds `WNPR` magic, protocol version 1, generation, exact region
   size, slot count, and payload capacity.
5. The active generation has not previously issued a descriptor set.

The A41 hardware negative checks rejected a normal app caller (`uid=10367`),
stale generation, invalid magic, oversized metadata, invalid HMAC, and replay.
The corresponding results were `CALLER_UID_REJECTED`, `GENERATION_REJECTED`,
`PROTOCOL_REJECTED`, `METADATA_REJECTED`, `AUTH_REJECTED`, and
`GENERATION_REPLAY_REJECTED`. No secret was logged.

This is a purpose-specific DEBUG trust model, not a completed production
secret-provisioning or public-API decision. The external-provider framework
path remains reflective feasibility evidence and must not be promoted silently
to a production dependency.

## A/B PCM evidence

The shell used the previously validated DirectShell configuration:

```text
source:      REMOTE_SUBMIX
sample rate: 48,000 Hz
format:      PCM16
channels:    stereo
buffer:      15,360 bytes / 3,840 frames
```

For every formal cycle, the normal-process drain was measured twice while the
same shell AudioRecord was running:

| Window at normal-process drain | Result across 10 cycles |
| --- | --- |
| Before the DEBUG 997 Hz AudioTrack tone | RMS `0.000`, zero non-zero samples |
| During the tone | RMS `5790.725` to `5799.206`, `174,220` to `202,534` non-zero samples |

The consumer observed non-zero PCM only after the controlled normal Android
playback started. Those values were measured after `SharedPcmAudioDrain`, not
from the shell-local AudioRecord. Consumer wakeups were non-zero in all ten
cycles; consumer drain errors were zero.

## Backpressure and lifecycle

The formal no-retry soak completed exactly 10 independent generations,
`500001` through `500010`:

```text
create ring -> secure descriptor handoff -> signal/ack marker
-> prepare/start DirectShell AudioRecord -> silent window -> tone window
-> stop -> close descriptors -> finish normal drain -> authenticated shutdown
```

Result: **10/10 PASS**. No named shell server remained after the final check,
and the normal app's post-release FD count stabilized at 105 across all cycles.

Nine cycles reported no ring overrun or producer drop. The first cold tone
cycle recorded 23 bounded producer drops and 23 V1 overruns, with high-water
mark 7 of 8 and final occupancy 0. This is the existing bounded V1 policy,
not a new queue: capture continued into the producer discard buffer until a
slot became free. The remaining nine cycles had high-water marks 5 to 6, zero
overruns, zero drops, final occupancy 0, and zero shell read errors. This
exercise demonstrates that the existing contract remains bounded under a brief
consumer delay; it does not claim the cold UI transition is a performance pass.

An earlier console-attached attempt completed the product path but its host
PowerShell metrics parser failed after collection. It was classified
`INVALID_HARNESS_ATTEMPT` and excluded from the 10/10 result. The formal soak
used redirected report files and has no retry or force-kill recovery path.

After the final style-only source rebuild, the exact final APK was installed
and completed one additional separate confirmation cycle: **1/1 PASS**. That
confirmation is not counted as a hidden eleventh soak retry.

## Ownership and teardown

For one generation:

1. The normal app creates, initializes, and owns the ring generation.
2. It starts the real drain with notify-read and ack-write ends.
3. Framework IPC duplicates SharedMemory, notify-write, and ack-read into the
   shell server after caller, HMAC, metadata, and generation validation.
4. The shell maps the memory, stops AudioRecord, closes its pipe descriptors,
   unmaps, closes SharedMemory, and releases the provider token.
5. The normal app closes the drain, its complementary endpoints, and its ring
   owner; it zeroes the generation secret.

Closing the shell copies did not close the normal drain. A new generation had a
fresh secret and could not replay the old descriptor request. No shell process,
stale provider reference, or monotonically growing shell/app FD count remained.

## Implementation and validation

Implementation commit: `58a18b2` (`feat(debug): spike DirectShell PCM ring integration`).

New DEBUG components include the narrow binder protocol, normal ring owner,
provider, shell external-provider client, shell PCM-ring server, receiver,
loopback control client, and the no-retry PowerShell harness. The only tests
added are pure protocol tests for fresh/replayed/stale/invalid descriptor
requests. Existing production ring source is reused unchanged.

Final validation:

```text
:app:ktlintCheck                         PASS
:app:lintDebug                           PASS
:app:testDebugUnitTest --rerun-tasks     619 tests, 0 failures, 0 errors, 0 skipped
:app:assembleDebug                       PASS
:app:assembleDebugAndroidTest            PASS
:app:packageDirectShellProbeDex          PASS
```

Native source is unchanged; existing Debug and Release CTest evidence remains
22/22 for each configuration.

Final installed A41 artifacts:

```text
app-debug.apk
  SHA-256: 738317E9F1C462B5EA155EA30C4CE92A37CA7C1FC10FBB043747921954A96AA5
  bytes: 29,085,131
  timestamp: 2026-09-13 14:56:44 UTC

warpnect-directshell-probe.jar
  SHA-256: EA40492C6FEA5A8513BE4D8C92537761C80F9203B24C96392064A36197CF8E98
  bytes: 10,149,353
  timestamp: 2026-09-13 14:56:54 UTC
```

The installed A41 `base.apk` SHA-256 matched the final APK exactly. The APK
contains `arm64-v8a`, `armeabi-v7a`, and `x86_64` ABIs.

## Hardware matrix

| Device | Result | Evidence |
| --- | --- | --- |
| A41 / API31 | `PASS_FD_SHARED_MEMORY` | Three-FD handoff, real notify/ack, DirectShell REMOTE_SUBMIX to real normal-process PCM, 10/10 soak and final-artifact 1/1 confirmation |
| S22 / API36 | `NOT_TESTED` | Outside this A41-first spike |
| Tablet / API33 | `NOT_TESTED` | Outside this A41-first spike |

## Fact, inference, unknown, proposal

**FACT:** On A41/API31, a real shell `app_process` can receive the complete
descriptor set for Warpnect's existing PCM Shared Ring V1, drive the unchanged
notify/ack semantics, and feed real `REMOTE_SUBMIX` PCM to the unchanged normal
app drain.

**INFERENCE:** DirectShell SystemAudio is a credible candidate to retain the
existing PCM ring and downstream audio architecture without Shizuku on
equivalent runtimes. PCM did not traverse Binder or loopback TCP.

**UNKNOWN:** This does not establish API33/API36/OEM portability, a public-API
production FD handoff route, downstream Opus/SCL/client playback, or a complete
DirectShell runtime lifecycle/capability design.

**PROPOSAL:** The next isolated task should be a DirectShell SystemAudio full
downstream Opus/SCL/client playback E2E spike. Do not switch the production
backend until that path and the provider/runtime compatibility campaign have
their own evidence.
