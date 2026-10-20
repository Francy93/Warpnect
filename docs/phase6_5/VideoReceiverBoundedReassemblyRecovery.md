# Bounded Video Receiver Recovery Checkpoint

2026-10-20. Scope: video_receiver_runtime.cpp and scl_video_runtime_tests.cpp.
This checkpoint does not include inherited microphone/audio/Session changes.

Reconciled pre-existing late-config admission has known provenance in
MicrophoneAudioVideoConfigurationPrerequisite.md: r535 failure, r536 red tests,
r537 correction, r538/r540 native gates and r547 physical continuation.
Unconfigured ready AUs may yield to their late config and evict one oldest
unconfigured ready AU. Configured decoder backpressure must not use that bypass.

Current recovery additionally fixes four independent receiver boundaries:

- Return configured ready AUs before reading more datagrams into the eight slots.
- Rebase exhausted bounded NACK history after unrecoverable loss; retain ordinary
  discontinuity/keyframe recovery. This is not authentication/anti-replay reset.
- Reject supplementary ClockSync samples as diagnostics, not fatal valid-media
  delivery. DEBUG reason telemetry is capped at eight observations/process.
- Honor already detected duplicate SCL fragments before allocating slots, after
  FEC ingestion. A late retransmission otherwise recreates a ghost incomplete AU
  in front of seven complete AUs. TooOld is not blanket-dropped. DEBUG pressure
  telemetry is capped at eight observations/process and contains no media bytes.

The last case is proven before/after: ready0/slots8 and eight failed assertions
before, ready7/slots7 and ordered progress after. Physical pre-fix metadata has
the identical incomplete1/complete7/ready0 fingerprint; FEC was disabled.
Thirteen runtime test functions cover original delivery, config admission,
active backpressure, three duplicate cases, loss-window and clock rejection.
No queue/window/MTU/codec/protocol/security/buffer capacity is enlarged.

Isolation proof r1335: HEAD8adc865 native sources plus ONLY the two checkpoint
files builds/tests Debug22/22 and Release22/22. Full current dirty-worktree
native gates independently PASS25/25 each. This proves the checkpoint does not
require inherited microphone CMake/JNI/audio deltas; the22 are not the full gate.

Frozen APK30C3A3C589285B8A911EB7E1A5A49DC13EBE039FAD6134430D432BDF020A78A3
also includes separately reviewed Kotlin decoder/Input corrections. r1334
A41/API31 -> S9/API29 on NETGEAR96-5G5220MHz passes three90s progressive visual
and recreation/Input windows, reassembly/ready full0 throughout, slotsHWM1,
actual Input4 per cycle. This is scoped functional validation, not zero-loss
or universal60fps/network qualification. Failed r1326/r1329 and the diagnostic
guard's former false PASS remain preserved locally and in the recovery report.

JVM1153/177 suites, ktlint/lint/build/test-APK/probe-Dex PASS. Audio85/70 and
microphone80/70+40ms freshness remain unchanged. No provider fallback/recovery.
