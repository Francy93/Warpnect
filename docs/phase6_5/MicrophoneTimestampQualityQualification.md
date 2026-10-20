# Microphone Timestamp Quality Qualification

2026-10-21 follow-up to the Video/SystemAudio recovery. This is a focused test
correction, NOT a microphone clock, latency, provider or global-input redesign.

## Proven Failure And Contract

r1338 S7/API26 produces real PCM with continuous frame positions, but the old
compound assertion fails when captureTimeNs goes backward8.177346ms at frame480.
The previous quality is EstimatedFromReadCompletion; the new quality is
AudioRecordTimestamp. Preserve the original17-case result:16PASS/1FAIL.

[RFC-003A timestamp contract](../rfc/RFC-003A-Android-Low-Latency-Audio-Capture-Foundation.md#timestamp-contract)
defines the fallback as read-completion monotonic time minus chunk duration.
Once available, the AudioRecord hardware anchor estimates the actual first
sample time instead. A monotonic clock DOMAIN does not guarantee that two
different-quality estimates of capture time cannot revise an earlier estimate.
The old test incorrectly compared those estimates as one qualified timeline.
This is a HARNESS_DEFECT, not evidence that Android's monotonic clock reversed.

The added deterministic JVM test uses the exact observed numbers: estimated
170444422700780ns, first hardware170444414523434ns; the subsequent hardware
chunk advances5ms and positions480->720 remain continuous. Production tracker
and payload timestamps remain byte-for-byte unchanged: no clamp, smoothing,
new buffer or artificial capture delay is introduced.

The physical compatibility test still rejects invalid PCM shapes, nonpositive
timestamps, discontinuous frame positions, backwards SAME-QUALITY timestamps,
missing PCM, late delivery after stop, resource leaks and typed capture errors.
It separately logs every quality transition and its delta instead of pretending
the estimate became authoritative before hardware timing was available.

## Results And Limits

Main APK remains exactly30C3A3C589285B8A911EB7E1A5A49DC13EBE039FAD6134430D432BDF020A78A3.
Only the test APK changes to
DB5DA40494DDF8278B8C14F0EEEA3A9F24BED14A06CC26E5BFA23CCB88460A92.
The r1344 first build FAILs one max-line-length check; r1346 corrects whitespace
only and passes ktlint/assembleDebug/assembleDebugAndroidTest/probe-Dex1m35s.
r1347 full JVM rerun PASS1154 cases/177 suites, no failure/error/skip,7m43s.
r1349 physical suites PASS17/17 EACH on S7/API26, A41/API31 and S9/API29.
Final r1351 lintDebug PASS13m11s; git diff --check PASS. Native production is
unchanged by this test correction; final r1356 Debug25/25 and Release25/25 PASS.
The S7 replay has three actual capture lifetimes with52320/52800/52560 frames,
buffer2880, no late PCM/shape error. Those replays need not reproduce every
framework startup quality transition; the exact revision is independently
forced by the deterministic JVM regression, not claimed to recur on command.

This establishes applicable capture/component behavior, not a new API26
Complete Host, global microphone input, physical remote audibility or Tablet
qualification. The Tablet reference remains unavailable. The late SystemAudio
startup xrun observations and moderate-volume usability debt are unrelated and
remain visible in VideoSystemAudioRegressionRecovery.md. No provider fallback,
root, hidden API or model allowlist is added. The old failed XML/logs are retained.
