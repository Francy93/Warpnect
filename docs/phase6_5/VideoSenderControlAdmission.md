# Video Sender Control Admission Checkpoint

2026-10-20. Scope: NativeSclVideoTransportController.kt and the actual-JNI
VideoControlContentionInstrumentationTest. No audio or provider changes.

The sender control poll held a shared native handle mutex while waiting for
datagrams. Encoder submission competed with that wait. On S7/API26 real JNI
loopback, the bounded120-unit/2s fixture delivered93 with polling (max41.317ms),
versus120 without polling (max2.020ms). This is not a fabricated Complete Host.

Poll native control nonblocking, then park for the existing requested timeout
outside the handle mutex only on NoData. No new queue, socket setting, priority
or buffer. The feedback tradeoff is explicit: an arrival after an empty poll
can wait up to the existing20ms poll interval; this is not zero-cost scheduling.

Actual after controls: S7 polling120/max2.112ms and A41 polling120/max1.303ms;
comparators120. Final frozen30C3A3C5 component transport cases pass on API31,
API29 and API26. The S7 microphone timestamp test is a separate preserved FAIL,
not hidden by those transport cases. r1334 three physical A41->S9 progressive
Video/recreation/Input windows have no reassembly exhaustion.

Full JVM1153, ktlint/lint/Android builds/probe-Dex and native25/25 both PASS.
No universal timing guarantee or UDP/RF qualification follows from loopback.
