# Video Decoder Wakeup And Lifecycle Checkpoint

2026-10-20. Scope: AndroidMediaCodecVideoDecoder,
VideoReceiverSessionController, VideoDecoderNotificationInstrumentationTest
and VideoReceiverLifecycleTest. No microphone clock/buffer/provider delta.

The decoder admitted one Handler Runnable per ready signal, even while blocked.
The real owned-Looper test generated10001 dispatches for10000 signals plus a
marker. A reusable wakeup/atomic pending flag now bounds admission to one
pending wakeup plus current drain; rearm before drain prevents lost work.
The after test observes2 and proves subsequent notification rearming.

Separately, concurrent suspended preparation could prepare twice, start after
Disconnect, or let an old Surface destroy its replacement. One coroutine Mutex
serializes decoder prepare/start/stop. Session config invalidation occurs before
waiting, but Idle is not exposed until old stop completes. Identity/generation
checks after suspension prevent obsolete work from starting a new owner.
The ordinary state monitor is never held across suspension.

Five deterministic JVM cases cover concurrent prepare, Disconnect during
prepare, late old destruction, replacement during prepare, and restart while
stop is suspended. Three red cases and the intermediate early-Idle fifth-case
failure remain preserved. Corrected focused5/5 and full1153/177 suites PASS.

Frozen30C3A3C5 r1334 A41/API31 -> S9/API29 has three90s progressive Video
windows including real Activity recreation, current visual scenes and12 actual
protected reverse Input events in total; no AlreadyPrepared or fatal exception.
The APK also includes separately reviewed native receiver fixes and uncommitted
Session-owned Input View rebinding. This checkpoint alone is not the full APK.
Final actual Looper/render/component cases pass on API31/API29/API26; S7's
separate hardware-timestamp handover assertion fails and is not suppressed.

ktlint/lint/assembleDebug/test-APK/probe-Dex, JVM1153 and native25/25 both PASS.
No deliberate frame buffering/delay, frame rate, codec, Session authentication,
plan freeze, audio reserve or provider recovery semantics are changed.
