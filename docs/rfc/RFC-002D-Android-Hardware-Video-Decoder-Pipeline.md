# RFC-002D - Android Hardware Video Decoder Pipeline

Project: Warpnect

Architecture Version: 1.0

SCL Protocol Version: 1

Native Bridge ABI Version: 1

Video Payload Version: 1

Status: Implemented

## Purpose

RFC-002D adds the Android hardware AVC decoder foundation used by the future receiver pipeline:

```text
complete encoded AVC access unit
        |
MediaCodec-owned input ByteBuffer
        |
hardware AVC decoder
        |
caller-owned Surface
```

It does not implement SCL receive orchestration, jitter buffering, rendering policy, or end-to-end streaming.

## AVC Baseline

The only implemented decoder format is H.264 / AVC:

```text
MIME = video/avc
```

The decoder accepts already decoded RFC-002C metadata through `VideoDecoderConfig` and `VideoDecoderInputResult.AccessUnit`. It does not parse `PacketHeader`, Video Payload Version 1 wire bytes, SPS, PPS, or AVC NAL units.

## Hardware Discovery

`AndroidVideoDecoderDiscovery` uses Android `MediaCodecList` / `MediaCodecInfo` metadata. Candidates must:

- be decoders;
- support `video/avc`;
- support the requested width and height;
- not be reported software-only;
- have explicit hardware/software classification when Android can provide it.

Unknown hardware classification is reported as `HardwareClassificationUnavailable`, matching the strict RFC-002B hardware policy. Multiple valid candidates are selected deterministically by preferring low-latency-capable hardware, non-alias codecs, canonical name, and codec name.

## Decoder MediaFormat

`AndroidVideoDecoderFormatFactory` configures only decoder-appropriate fields:

- `KEY_MIME = video/avc`;
- `KEY_WIDTH`;
- `KEY_HEIGHT`;
- exact `csd-0`, `csd-1`, ... entries from the active StreamConfig;
- optional `KEY_MAX_INPUT_SIZE`;
- optional `KEY_LOW_LATENCY = 1` when the selected decoder reports `FEATURE_LowLatency` and the Android API supports the key.

Encoder-only keys such as bitrate, bitrate mode, I-frame interval, encoder latency, B-frame count, and max FPS to encoder are not used for decoding.

## CSD Contract

Codec-specific data is supplied by RFC-002C StreamConfig messages and copied on the cold configuration path into Android `MediaFormat` `csd-N` entries. Order and bytes are preserved exactly.

RFC-002D does not:

- parse SPS/PPS;
- rewrite codec-specific data;
- convert Annex B and AVCC;
- enqueue CSD again as ordinary decoder input after configure.

## Pull-Based Input

`VideoDecoderInputSource.fillInput(target, capacity)` runs only when `MediaCodec.Callback.onInputBufferAvailable()` supplies a codec-owned input buffer. The source writes one complete encoded access unit directly into that buffer and returns metadata:

- size;
- presentation timestamp;
- configuration generation;
- frame ID;
- keyframe flag.

`NoData` retains the codec input index in a bounded index-only tracker. `notifyInputAvailable()` retries retained indices on the decoder thread. No encoded payload bytes are queued internally.

An access unit is rejected if it is empty, larger than the codec input buffer capacity, has negative PTS, has generation zero, has a mismatched configuration generation, or has an invalid frame ID.

## Configuration Generation

A decoder session has one active `configGeneration`. Every submitted AU must match it. A mismatch returns `ReconfigurationRequired`; RFC-002D does not attempt transparent in-place decoder reconfiguration.

## Presentation Timestamps

Decoder input is queued using the RFC-002C access-unit `presentationTimeUs` exactly. RFC-002D does not replace it with receiver wall time, monotonic time, render deadline, or network receive time.

## Surface Output

The caller owns the output `Surface`. RFC-002D borrows it for `MediaCodec.configure()` and never releases it. Decoded raw pixels remain inside Android's codec/compositor path.

Production decoder code does not use:

- `ImageReader`;
- `Image`;
- `Bitmap`;
- `YUV_420_888`;
- `getOutputImage`;
- raw output `ByteBuffer`;
- CPU pixel conversion.

## Output Actions

`DecodedVideoSink.onFrameAvailable()` receives metadata only and synchronously returns one action:

- `RenderNow` -> `releaseOutputBuffer(index, true)`;
- `Drop` -> `releaseOutputBuffer(index, false)`;
- `RenderAt(timestampNs)` -> `releaseOutputBuffer(index, timestampNs)`.

RFC-002D provides the mechanism but does not decide render scheduling, late-frame dropping, jitter-buffer policy, or VSYNC prediction.

Output sink failure drops the affected buffer and transitions to a typed decoder error so codec-owned output indices are not leaked.

## Frame-Rendered Diagnostics

`AndroidMediaCodecVideoDecoder` registers `MediaCodec.OnFrameRenderedListener` for diagnostics where supported by the framework. It records presentation timestamp and render callback nanotime. This callback is not a correctness requirement and is not assumed to fire once per frame on every device.

## Threading

MediaCodec lifecycle, input callbacks, output callbacks, output-format changes, frame-rendered callbacks, EOS/drain, and cleanup run on:

```text
WarpnectVideoDecoder
```

This is a dedicated Android `HandlerThread`. It has no networking responsibility.

## Lifecycle

The lifecycle is:

```text
Stopped
  -> Preparing
  -> Prepared
  -> Starting
  -> Running
  -> Draining / Stopping
  -> Stopped
```

Prepare is transactional: codec creation, callback registration, format construction, and `configure()` must all succeed or acquired codec resources are released. `stop()` is idempotent when already stopped. `close()` is idempotent and releases the codec and decoder thread without releasing the caller-owned `Surface`.

Graceful EOS queues `BUFFER_FLAG_END_OF_STREAM` into a codec input buffer and drains output until decoder EOS or a bounded timeout. Timeout is a lifecycle safety guard, not a latency policy.

## Telemetry

`VideoDecoderSnapshot` tracks:

- state;
- codec identity and hardware classification;
- low-latency support/request state;
- dimensions and active generation;
- queued access units and encoded bytes;
- input backpressure and input-buffer-too-small counters;
- decoded output buffers;
- render/drop/scheduled-release counters;
- output format changes;
- codec/runtime errors;
- last input/output/rendered timestamps;
- CodecException diagnostics.

## Tests

JVM tests cover:

- hardware candidate selection;
- software-only rejection;
- unknown hardware classification;
- unsupported dimensions;
- deterministic tie-breaking;
- low-latency preference without requiring the feature;
- decoder format planning;
- CSD validation and byte preservation;
- lifecycle transitions;
- generation mismatch and oversized AU rejection;
- PTS preservation;
- output action accounting;
- codec diagnostics;
- drain timeout;
- bounded input-index retention;
- output-release mapping.

Instrumentation includes a device-gated encoder-to-decoder round trip:

```text
Synthetic EGL frames
        |
RFC-002B hardware AVC encoder
        |
test-owned encoded AU copies
        |
RFC-002D hardware AVC decoder
        |
SurfaceTexture-backed test Surface
```

The encoded AU copies live only in `androidTest` because RFC-002B output buffers are borrowed and must be released.

## Device Status

Device execution requires a connected Android device or emulator with suitable AVC hardware codecs. If no device is connected, instrumentation execution is reported as not run. Emulator/software-only decoder results must not be reported as hardware decoder verification.

## Limitations

RFC-002D does not implement:

- SCL receive orchestration;
- decoder JNI;
- encoded AU payload queues;
- raw-frame CPU rendering;
- jitter buffering;
- NACK/FEC recovery;
- automatic keyframe recovery;
- renderer policy;
- end-to-end streaming.

## Architecture Compliance

No SCL wire format changes were made. `PacketHeader` remains unchanged, no new `PayloadType` was added, and Video Payload Version remains 1.

