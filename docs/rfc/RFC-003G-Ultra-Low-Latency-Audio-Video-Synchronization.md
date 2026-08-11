# RFC-003G - Ultra-Low-Latency Audio/Video Synchronization

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1.

RFC-003G adds a receiver-side A/V synchronization foundation above the completed video and audio pipelines. It does not change SCL wire formats, add media payload copies, add a jitter buffer, or alter audio playback speed.

```text
audio source timing
        -> Oboe source/output anchor
        -> cold-path presentation timestamp
        -> audio-master sync model
        -> video RenderAt / RenderNow policy
```

## Clock-Domain Model

Audio `captureTimeUs` is the sender-side audio capture timeline from Audio Payload V1. Video `presentationTimeUs` is the MediaCodec PTS preserved by Video Payload V1.

RFC-003G does not assume those timestamps share a clock domain merely because both are expressed in microseconds. Video synchronization is enabled only after bounded observations qualify the video PTS as `SenderMonotonicCompatible` with the audio capture timeline.

The validator samples:

```text
audioOffsetSample = audioReadyLocalUs - audioCaptureTimeUs
videoOffsetSample = videoReadyLocalUs - videoPresentationTimeUs
```

If audio and video use the same sender epoch/rate, the sender-to-receiver clock offset cancels in the path-offset difference. The validator requires a configured minimum video sample count, rejects incompatible epochs with `maxCrossMediaPathDifferenceUs`, rejects implausible rate error with `maxTimestampRateErrorPpm`, and marks stale observations when they exceed `maxSyncModelAgeUs`.

The provisional defaults are:

```text
minimumVideoCalibrationSamples = 8
maxCrossMediaPathDifferenceUs = 250000 us
maxTimestampRateErrorPpm = 50000
maxSyncModelAgeUs = 100000 us
```

These are bootstrap tolerances for RFC-003G. RFC-003H owns measurement-driven tuning.

## Audio Master Clock

Audio hardware presentation is the receiver-side master. The Oboe callback publishes a bounded native source/output anchor when real decoded source PCM begins being consumed:

```text
sourceFramePosition
sourceCaptureTimeUs
outputFramePosition
timestampQuality
configGeneration
DecodedAudioFrameKind
```

The callback only updates fixed native metadata and counters. It performs no JNI, Kotlin/Java callback, allocation, blocking lock, timestamp query, network operation, codec decode, logging, or video calculation.

A cold-path query combines the latest source/output anchor with Oboe `CLOCK_MONOTONIC` presentation timestamp data and interpolates the local presentation time of the source anchor from output-frame positions:

```text
anchorLocalNs =
    oboeTimestampNs
    + (anchorOutputFramePosition - oboeTimestampFramePosition) / sampleRate
```

The query also validates that a real source anchor exists. Underrun silence is not remote source audio and invalidates the source-to-output mapping until a new source PCM anchor appears.

## Opus Lookahead

RFC-003B exposes encoder lookahead and RFC-003C transports it in Audio Payload V1 StreamConfig. RFC-003D deliberately avoids blind decoder pre-skip because a receiver may join an already-running stream.

RFC-003G incorporates lookahead in the synchronization model only:

```text
lookaheadDurationUs = lookaheadSamples * 1000000 / sampleRateHz
sourceContentTimeUs = sourceCaptureTimeUs - lookaheadDurationUs
```

Audio Payload V1 timestamps are not mutated, and decoder PCM is not physically discarded by the sync layer.

## Sync Controller

`DefaultAvSyncController` owns:

- `VideoTimestampDomainValidator`
- `AvSyncPlaybackStartGate`
- `AvSynchronizedVideoRenderPolicy`
- a low-frequency `WarpnectAvSync` sampler
- immutable/atomic sync model publication

The sampler runs at the configured `syncSampleIntervalUs` default of `20000 us`. It queries audio presentation anchors, updates bounded validation statistics, publishes `AvSyncModel`, and expires stale models. It does not decode media, render video, copy audio/video payloads, or perform network I/O.

Synchronization states are:

```text
Disabled
WaitingForAudio
WaitingForVideoTimestampDomain
Calibrating
Synchronized
Degraded
Unsupported
Closed
```

Video timestamp domain states are:

```text
Unknown
Calibrating
SenderMonotonicCompatible
Rejected
Stale
```

## Startup Alignment

Audio-only RFC-003F behavior is unchanged:

```text
first decoded frame -> one-frame prime -> start Oboe
```

For A/V sessions, an optional playback-start gate can briefly hold Oboe startup after the first audio frame has primed the playback ring. The gate releases when the first usable video frame is observed, when the configured hold expires, or before another audio submission would fill the playback ring.

The hold uses only existing playback-ring slack:

```text
availableExtraCodecFrames =
    ringCapacityCodecFrames - startThresholdCodecFrames
```

With the RFC-003E defaults:

```text
ring capacity = 4 codec frames
start threshold = 1 codec frame
frame duration = 5 ms
capacity-derived maximum hold = 15 ms
```

This is startup-only alignment. It is not a continuous audio jitter buffer, and RFC-003G does not increase playback-ring capacity.

## Video Scheduling

`AvSynchronizedVideoRenderPolicy` implements the existing `VideoRenderPolicy`. It reads a small immutable model on the `WarpnectVideoDecoder` thread and returns immediately. It does not query Oboe, call JNI, wait on a mutex, access network I/O, or allocate media payloads.

For a valid synchronized model:

```text
targetLocalNs =
    audioAnchor.localPresentationTimeNs
    + (videoPresentationTimeUs - audioAnchor.sourceContentTimeUs) * 1000
    + manualAvOffsetUs * 1000
```

Sign convention:

```text
positive manualAvOffsetUs = present video later relative to audio
```

If the target is in the future and within `maxVideoSyncScheduleAheadUs`, the policy returns `RenderAtLocalTime(targetLocalNs)`.

If the target is in the past, it returns `RenderImmediately` and records lateness. RFC-003G does not add a new video drop policy.

If the target is too far in the future, the policy clamps the schedule to the configured maximum ahead time and records `videoScheduleClamped`. The provisional default is:

```text
maxVideoSyncScheduleAheadUs = 20000 us
```

If the model is invalid, stale, disabled, or the video timestamp domain is unqualified, video falls back to `RenderImmediately`.

## Invalidations

A/V synchronization is invalidated or degraded on:

- audio underrun/silence insertion
- playback ring full or dropped PCM reported by session orchestration
- RFC-003F large-gap audio reset
- audio configuration generation change
- Oboe stream restart
- audio source `DiscontinuityBefore`
- video decoder/configuration reset for video-side statistics

After fresh real source PCM anchors and fresh video timing observations are available, the controller can reacquire synchronization.

## ClockSync

RFC-001F ClockSync remains supplementary evidence for diagnostics and sanity checks. It is not the A/V master. Final receiver presentation scheduling uses the actual Oboe source-to-output timeline because audio hardware presentation is the synchronization master.

If audio and video source timestamps qualify as the same sender clock, A/V synchronization can continue even when ClockSync samples are stale.

## Latency Accounting

Snapshots expose bounded accounting:

```text
startupHoldUs
latestVideoScheduleAheadUs
currentEstimatedAvSkewUs
videoFramesScheduled
videoFramesRenderedImmediately
videoScheduleClamped
syncAcquisitions
syncLosses
```

Skew sign:

```text
positive = video late relative to audio
negative = video early relative to audio
```

Oboe timestamps are platform presentation estimates, not physical acoustic proof. `RenderAt` targets are requested video presentation times, not guaranteed photon times. Bluetooth and external DAC/display paths may add device-specific latency outside RFC-003G's software model.

## Buffering And Copies

RFC-003G introduces:

```text
new network jitter buffer = 0
new decoded-video queue = 0
new PCM queue = 0
new media payload copies = 0
```

The sync layer processes only timing metadata. It does not copy Opus, PCM, AVC, decoded pixels, or packet payloads.

## Tests

Coverage includes:

- exact lookahead sample-to-time conversion
- source/output frame-position interpolation
- compatible synthetic audio/video timestamp domains
- different epoch rejection
- rate mismatch rejection
- bounded calibration jitter
- stale model fallback
- video target calculation
- future, past, excessive-future, and manual-offset render decisions
- startup gate capacity derivation, early video release, timeout release, and ring-full avoidance
- audio-only startup regression through unchanged default session behavior
- underrun/reset invalidation and reacquisition hooks
- native playback anchor publication across whole slots, partial slots, PLC slots, multi-slot callback consumption, and underrun invalidation

Android synthetic A/V, privileged real-capture timestamp qualification, and two-device A/V tests remain device-dependent and must report actual device status only.

## Versioning

RFC-003G adds no wire change.

```text
Architecture Version: 1.0
SCL Protocol Version: 1
Native Bridge ABI Version: 1

PCM Shared Ring Version: 1
PCM Playback Ring Version: 1

Audio Payload Version: 1

Video Payload Version: 1
Video Resync Control Version: 1
```

PacketHeader, PayloadType, Audio Payload V1, Video Payload V1, VideoResyncRequest, NACK, FEC, and ClockSync wire layouts are unchanged.

## Next

RFC-003H owns Audio Latency, Recovery, and Performance Tuning.
