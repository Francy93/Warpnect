# RFC-006B - Media Pipeline Metrics Integration

## Status

RFC-006B integrates Runtime Telemetry Model V1 into the existing Video, SystemAudio, and reverse
Input pipelines. It is local instrumentation only: `PayloadType.Telemetry` remains unused and no
wire contract changes.

## Descriptor Groups

Video uses `0x0301` through `0x0320`: encoder access units, bytes, keyframes, AU-size histogram,
format changes/errors, decoder input/output/release/drop/render events, and resync requests. The AU
histogram uses fixed byte boundaries from 512 through 1,048,576.

Audio uses `0x0401` through `0x0424`: accepted PCM sample frames, shared-ring overruns, Opus
encode/decode/PLC results, and Oboe callback/playback-ring health. `Samples` means PCM sample
frames, not scalar interleaved channel values. The Opus-size histogram uses the frozen 32 through
2,048 byte boundaries.

Input uses `0x0501` through `0x0531`: aggregate capture/hotplug, mapping drops, semantic sender
and convergence/reset behavior, and injection attempts/failures. No descriptor represents key,
character, axis, button, or coordinate content.

All descriptors use the fixed Runtime Telemetry Model V1 kind and unit definitions. Video access
unit counts use `Frames` to mean encoded AVC access units; audio `Frames` means configured Opus
frames; and audio `Samples` means PCM sample frames, never scalar interleaved channel values.

## Ownership and Lifecycle

`AndroidSessionPipelineFactory` registers bounded Channel-scoped sources while it constructs the
real selected controller. It passes direct handles to the existing MediaCodec, audio capture/Opus,
and input controller boundaries, then closes sources after their owning pipeline stops. Telemetry
source registration failure yields no-op handles and never fails Session startup.

An in-place RFC-005H path migration preserves the pipeline and therefore its source identity and
cumulative values. A fresh SessionGeneration creates a new pipeline, new source IDs, and fresh
counters; generation-N sources are closed.

The normal Host profile registers one source each for Video encoder, SystemAudio capture/encoder,
and Input receiver/injection. The normal Client profile registers one Video decoder source, one
SystemAudio decoder source, one native playback source, and one Input capture/sender source. The
largest of those profiles therefore uses four sources per Session and at most 32 across the
architectural eight-Session bound, leaving 480 of the 512 source slots free for framework and later
diagnostic sources.

## Semantic Event Points

The asynchronous encoder counts an AU only after the borrowed `ByteBuffer` is accepted by its
downstream sink; CSD-only output is excluded. The decoder distinguishes output, explicit Surface
release, policy drop, and Android render notification. Render notification is observational, not a
cross-version physical display guarantee.

SystemAudio counts PCM frames only after its existing shared-ring drain hands them to Opus. Its
existing shared-ring overrun counter is projected without a new ring. Opus counts a frame after
successful downstream handoff. Native Oboe counts callback, requested/delivered PCM frames,
underruns, and current ring fill through pre-bound native WNTM instruments. The callback makes no
JNI call, allocation, snapshot, lock, timestamp query, or logging call.

The generic audio handles can also observe existing MicrophoneAudio capture/encoder components when
they are instantiated in development or test paths. This RFC does not alter RFC-005I capability
truthfulness: MicrophoneAudio remains non-negotiable in the normal product flow until a legitimate
Host-side sink exists. The SCL Telemetry Channel likewise remains unselected.

Input counts semantic sender events once regardless of RFC-004G redundancy copies. Target metrics
count convergence acceptance, semantic duplicate drops, reset application, and actual injection
attempts/failures. Metrics contain no Input Payload V1 content.

Kotlin owns the MediaCodec and Android input semantic events. Native Opus owns encode/decode/PLC
outcomes, while native Oboe owns playback callback, requested/delivered sample-frame, underrun, and
ring-fill observations. No event crosses JNI merely to update a metric. The only native JNI activity
is the pre-existing one-batch WNTM snapshot collection on a cold path.

## Failure, Migration, and Reconnect

Metric-source registration is best effort. A disabled hub, exhausted source capacity, or unavailable
native playback source produces no-op handles and cannot fail a selected media or input pipeline.
Pipeline rollback stops controllers first, then closes their telemetry sources. Same-generation
migration leaves healthy controller and source identities intact, so cumulative metrics continue
across an endpoint rebind. Fresh-generation reconnect closes the old sources and creates a new
generation-scoped source set; no media or telemetry backlog is retained.

## Boundaries and Non-Goals

Every hot update uses a direct primitive: no string lookup, global hub lookup, queue, payload copy,
or control feedback. Native metrics appear through the existing one-WNTM-batch-per-snapshot provider.
Snapshot consistency remains weak and non-destructive.

RFC-006B does not add packet/FEC/NACK/retransmission metrics, path migration/reconnect diagnostics,
ClockSync/latency tracing, event history, UI, export, remote telemetry, or adaptive decisions.

## Cost, Privacy, and Verification

Media and input producer paths use pre-bound atomic counters, gauges, or a fixed 12-boundary
histogram only. They perform no source or descriptor lookup, allocation, mutex acquisition, queue
operation, snapshot, payload copy, logging, or telemetry thread dispatch. Oboe performs only direct
atomic operations in its callback. Snapshot reads remain non-destructive and weakly consistent.

JVM tests cover catalog boundaries, source lifecycle, successful Opus handoff, one-error failure
accounting, PLC, and semantic Input redundancy. Native tests cover caller-owned WNTM source IDs;
Debug and Release CTest cover the telemetry, audio, video, input, and protection suites. Android ABI
builds cover arm64-v8a, armeabi-v7a, and x86_64. No Android device was attached for runtime telemetry
validation.
