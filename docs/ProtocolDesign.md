# SCL Protocol Design

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document records the SCL packet foundation, UDP transport boundary, Version 1 fragmentation semantics, Version 1 NACK recovery control payload, Version 1 Reed-Solomon FEC parity control payload, Version 1 clock synchronization control payloads, Video Payload Version 1, VideoResyncRequest Version 1, and Audio Payload Version 1. It does not define discovery, encryption, input injection, or session negotiation.

## RFC-004F Input Runtime Profile

RFC-004F does not change any wire table. Input Payload V1 remains one datagram per observation with a 396-byte maximum logical payload and a 417-byte maximum SCL datagram. The runtime uses `slice_index = 0`, `total_slices = 1`, strict expected-endpoint filtering, arrival-order processing, and sequence diagnostics only. Absolute Input V1 coordinates represent remote-control content coordinates after endpoint-local viewport mapping; Android target pixel conversion remains outside the wire protocol.

## Protocol Purpose

SCL exists to keep two endpoints coherent under strict latency pressure. It will eventually transport synchronized channels for:

- Video.
- System audio.
- Microphone audio.
- Input events.
- Telemetry.
- Handshake.
- Session control.

## Version 1 Wire Header

The SCL Version 1 wire header is exactly 21 bytes.

All multi-byte fields use network byte order, meaning big-endian.

| Offset | Size | Field | Wire type |
| ---: | ---: | --- | --- |
| 0 | 2 | `protocol_version` | unsigned 16-bit |
| 2 | 2 | `flags` | unsigned 16-bit |
| 4 | 4 | `sequence_number` | unsigned 32-bit |
| 8 | 8 | `timestamp_us` | unsigned 64-bit |
| 16 | 1 | `payload_type` | unsigned 8-bit |
| 17 | 2 | `slice_index` | unsigned 16-bit |
| 19 | 2 | `total_slices` | unsigned 16-bit |

The public constant is:

```cpp
kPacketHeaderWireSize == 21
```

Field offsets are exposed as named constants in `protocol.h`.

## Wire and Runtime Separation

The wire representation is a sequence of bytes. It must not depend on:

- C++ structure packing.
- Host endianness.
- Native alignment.
- `reinterpret_cast`.
- Direct transmission of a runtime structure.
- Raw copying of a runtime structure.

`PacketHeader` is the aligned runtime representation used after decoding and before encoding. It is not packed and is not serialized directly.

## Payload Types

Version 1 payload type values are frozen:

| Value | Payload type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Video` |
| 2 | `SystemAudio` |
| 3 | `MicrophoneAudio` |
| 4 | `Input` |
| 5 | `Telemetry` |
| 6 | `SessionControl` |
| 7 | `Handshake` |

`Unknown` is reserved and must not be emitted by encoders.

Decoders must distinguish reserved `Unknown` from unsupported numeric payload values.

`Handshake` is reserved for capability negotiation, endpoint compatibility, and protocol compatibility checks.

`SessionControl` is reserved for active session lifecycle and versioned control/recovery payloads after negotiation, including NACK and FEC parity subtypes.

## Flags

The `flags` field is 16 bits.

Version 1 assigns no feature semantics to individual flag bits. Packet encoding and decoding must preserve the full 16-bit value without rejecting unknown or unassigned bits.

Future RFCs may define flag semantics.

## Validation Rules

Version 1 validation rules:

- `protocol_version` must equal Protocol Version 1.
- `payload_type` must be a defined Version 1 value.
- `payload_type` must not be `Unknown`.
- `total_slices >= 1`.
- `slice_index < total_slices`.
- Every 32-bit sequence number is structurally valid.
- Every 64-bit timestamp value is structurally valid.
- All flag bits are preserved and are structurally valid.

Validation does not implement recovery, loss detection, retransmission, ordering policy, or timestamp comparison.

## Packet Views

Decoded packets use a non-owning `PacketView`.

The view contains:

- A decoded runtime `PacketHeader`.
- A `std::span<const std::byte>` payload view.

The payload span points into the caller-provided packet buffer, beginning at byte offset 21.

The caller must keep the source packet buffer alive while the view is used.

Parsing does not allocate and does not copy payload bytes.

## Timestamp Domain

`timestamp_us` represents microseconds in a monotonic timestamp domain.

A packet timestamp is meaningful only inside its documented clock domain unless a clock synchronization model has been established.

RFC-001F does not automatically interpret `PacketHeader::timestamp_us` as a UDP transport-send timestamp. One-way delay estimation is valid only when the caller explicitly supplies a remote timestamp whose semantics are suitable for delay measurement.

SCL must not use wall-clock time, Unix epoch time, local time, or calendar time for protocol latency decisions.

## Allocation and Complexity

Packet header encoding and decoding are allocation-free and constant time.

Packet parsing is allocation-free and constant time.

Full packet serialization writes the header and copies the caller-provided payload exactly once into the caller-owned output buffer.

No packet codec function owns payload memory.

## UDP Transport

RFC-001B establishes a packet-agnostic UDP datagram transport.

Transport APIs operate on caller-owned `std::span` buffers and platform-neutral endpoints. Public UDP headers must not expose `sockaddr`, Winsock, POSIX socket handles, Android framework types, JNI, or Kotlin concepts.

One successful `send_to()` call sends exactly one UDP datagram. One successful `receive_from()` call receives exactly one UDP datagram. The transport does not emulate stream semantics, concatenate datagrams, split datagrams, retry sends, hide loss, or guarantee ordering.

UDP transport is non-blocking. An empty receive queue returns `WouldBlock`; this is not an error condition and must not cause sleeping, spinning, hidden retries, or logging in production code.

Transport truncation is explicit. If an incoming datagram exceeds the caller-provided receive buffer, the result is `DatagramTruncated` and the datagram must not be passed upward as a complete SCL packet.

The structural UDP payload limit is:

```cpp
kUdpMaxDatagramPayloadSize == 65507
```

This is the IPv4 theoretical UDP payload maximum. It is not the SCL MTU and must not be treated as a recommended packet size.

SCL will normally use much smaller datagrams to avoid IP-layer fragmentation. Application-level fragmentation and selective NACK recovery are implemented above UDP. Packet sizing policy, path MTU behavior, pacing, FEC, and congestion behavior remain deferred to later RFCs.
SCL FEC also uses caller-selected datagram budgets and does not freeze a protocol MTU.

The UDP layer moves opaque datagrams. It must not inspect `PacketHeader`, validate protocol versions, interpret `PayloadType`, read sequence numbers, parse timestamps, or mutate packet bytes.

## Fragmentation And Reassembly

RFC-001C defines deterministic application-level fragmentation for logical SCL payloads that exceed a caller-selected datagram budget.

The Version 1 binary header is unchanged. Fragmentation uses the existing `sequence_number`, `slice_index`, and `total_slices` fields.

One algorithmic fragment is one on-wire SCL slice:

```text
fragment index == slice_index
fragment count == total_slices
```

The datagram budget is caller-selected:

```text
max_datagram_size = 21-byte SCL header + fragment payload
```

The fragment payload capacity is:

```text
max_fragment_payload = max_datagram_size - kPacketHeaderWireSize
```

No global SCL MTU is frozen by Protocol Version 1. Test budgets such as 64, 128, 256, 512, or 1200 bytes are examples only.

For a logical payload of size `P` and fragment payload capacity `C`, non-empty payloads use:

```text
total_slices = ceil(P / C)
```

An empty logical payload is represented as one fragment:

```text
slice_index = 0
total_slices = 1
payload size = 0
```

The Version 1 fragmentation sequence rule is:

```text
For slice i:

sequence_number = base_sequence_number + slice_index

Therefore:

base_sequence_number =
    sequence_number - slice_index

All arithmetic is modulo 2^32.
```

This means each transmitted UDP datagram has a unique packet-level sequence number, including slices from the same logical payload.

A runtime-only fragment group key is derived from:

- Protocol version.
- Base sequence number.
- Timestamp.
- Payload type.
- Flags.
- Total slice count.

This key is never serialized as an additional wire structure.

Fragmentation is zero-copy. `FragmentView` payload spans reference the caller-owned logical payload.

Reassembly copies fragment payload bytes once into caller-owned persistent storage. The reassembly slot owns no heap buffer and represents one fragment group at a time.

Reassembly supports out-of-order fragments, final-slice-first arrival, duplicate detection, conflicting duplicate rejection, and reset/reuse.

Reassembly does not implement loss detection, NACK, retransmission, timeout eviction, FEC, pacing, RTT, or clock synchronization. RFC-001D recovery primitives compose with reassembly through packet sequence numbers and decoded packet views.

## Video Payload Version 1

RFC-002C defines the Version 1 logical payload carried inside `PayloadType::Video`.

This payload version is independent from:

- SCL Protocol Version.
- Native Bridge ABI Version.
- NACK Control Payload Version.
- FEC Parity Control Version.
- Clock Sync Control Version.

Constants:

```cpp
kVideoPayloadVersion == 1
PayloadType::Video == 1
```

The 21-byte SCL `PacketHeader` is unchanged. RFC-002C does not add a new `PayloadType`.

### Video Message Types

| Value | Message type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `StreamConfig` |
| 2 | `AccessUnit` |

Only `StreamConfig` and `AccessUnit` are emitted by Version 1 encoders.

### Video Codec IDs

| Value | Codec |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Avc` |

RFC-002C implements AVC/H.264 only. Android `MediaCodecInfo.CodecProfileLevel` constants are not protocol codec IDs.

### Common Video Message Header

Every Version 1 video logical payload begins with this exact 12-byte header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `video_version` |
| 1 | 1 | `message_type` |
| 2 | 1 | `codec` |
| 3 | 1 | `flags` |
| 4 | 4 | `config_generation` |
| 8 | 4 | `item_id` |

Constants:

```cpp
kVideoMessageHeaderWireSize == 12
```

All multi-byte video payload fields use big-endian byte order.

### Configuration Generation

`config_generation` identifies the decoder configuration required by an access unit.

Rules:

```text
0 = invalid / no active configuration
first accepted configuration = 1
subsequent format change = previous + 1
UINT32_MAX + 1 wraps to 1, skipping 0
```

Every `AccessUnit` must reference a successfully emitted non-zero configuration generation.

### Item ID

For `StreamConfig`:

```text
item_id = 0
```

For `AccessUnit`:

```text
item_id = frame_id
```

The frame ID is a 32-bit wrapping access-unit counter. It is independent from SCL packet sequence numbers.

### StreamConfig

`StreamConfig.flags` must be zero in Version 1.

After the 12-byte common header, a `StreamConfig` contains:

| Offset | Size | Field |
| ---: | ---: | --- |
| 12 | 2 | `width` |
| 14 | 2 | `height` |
| 16 | 1 | `csd_count` |
| 17 | 3 | reserved, zero |

The fixed StreamConfig prefix is exactly 20 bytes.

After the prefix, exactly `csd_count` codec-specific-data entries follow. Each entry is:

```text
uint32 big-endian length
exact CSD bytes
```

AVC Version 1 accepts 1 through 4 CSD entries. CSD bytes are preserved exactly. SCL does not parse SPS/PPS, rewrite start codes, convert Annex B to AVCC, convert AVCC to Annex B, merge NAL units, or split NAL units.

### AccessUnit

An `AccessUnit` payload is:

```text
12-byte common video header
exact encoded access-unit bytes
```

There is no additional fixed access-unit metadata header.

For `AccessUnit.flags`:

```text
bit 0 = KeyFrame
bits 1..7 = reserved, zero
```

Reserved flag bits are rejected by the parser. SCL does not parse AVC NAL units to infer keyframes.

### Video Timestamps

For Version 1 `AccessUnit` SCL packets:

```text
PacketHeader::timestamp_us = MediaCodec BufferInfo.presentationTimeUs
```

All fragments of one access unit carry the same timestamp.

This timestamp is the encoder presentation timestamp supplied by RFC-002B. It is not automatically interpreted as UDP send time, network time, synchronized cross-device monotonic time, or wall-clock time. Later video orchestration and latency RFCs will define how media PTS relates to synchronized endpoint clocks.

Negative Kotlin `presentationTimeUs` values are rejected before unsigned native conversion.

### Video Fragmentation

The logical payload being fragmented is:

```text
StreamConfig:
20-byte StreamConfig prefix + length-prefixed CSD entries

AccessUnit:
12-byte VideoMessageHeader + encoded AU bytes
```

Fragmentation uses RFC-001C unchanged. Each fragment has its own unique SCL packet sequence number, and the fragment group base remains:

```text
base_sequence_number = sequence_number - slice_index modulo 2^32
```

When FEC is disabled:

```text
fragmentation datagram budget = max_wire_datagram_size
```

When FEC is enabled:

```text
fragmentation datagram budget =
max_wire_datagram_size - 21 - 16 - 2
```

No global MTU is frozen by RFC-002C.

### Segmented Packetization

RFC-002C packetizes access units as segmented logical payloads:

```text
segment 0 = 12-byte video header
segment 1 = borrowed MediaCodec encoded AU bytes
```

The native packetizer copies only the bytes intersecting each SCL fragment into the outgoing datagram scratch buffer. It does not allocate or require a complete temporary `12 + AU size` staging buffer.

### RFC-002F Runtime Composition

RFC-002F adds end-to-end sender/receiver runtime orchestration for Video Payload Version 1. It does not change the Video V1 wire layout, the 21-byte SCL `PacketHeader`, `PayloadType::Video`, NACK payloads, FEC parity payloads, or clock-sync payloads.

The receiver runtime uses existing SCL packet decode, loss detection, NACK generation, Reed-Solomon FEC recovery, RFC-001C reassembly, and Video V1 parsing. Completed AccessUnit payload bytes remain owned by bounded native receiver storage until JNI synchronously fills a MediaCodec-owned direct input buffer. No complete access-unit Kotlin payload representation is part of the protocol contract.

## Audio Payload Version 1

RFC-003C defines the Version 1 logical payload carried inside:

```cpp
PayloadType::SystemAudio == 2
PayloadType::MicrophoneAudio == 3
```

Both payload types carry the same Audio Payload Version 1 layout. The payload type identifies the logical source, so the audio payload does not duplicate `SystemAudio` or `MicrophoneAudio` identity.

This payload version is independent from SCL Protocol Version, Native Bridge ABI Version, PCM Shared Ring Version, Video Payload Version, and Video Resync Control Version.

Constants:

```cpp
kAudioPayloadVersion == 1
```

The 21-byte SCL `PacketHeader` is unchanged. RFC-003C does not add a new `PayloadType`.

### Audio Message Types

| Value | Message type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `StreamConfig` |
| 2 | `AudioFrame` |

No EndOfStream or network-control audio message exists in Version 1.

### Audio Codec IDs

| Value | Codec |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Opus` |

RFC-003C supports raw Opus packets only. This codec ID is the SCL Audio Payload V1 codec ID and is distinct from Kotlin application enum implementation details.

### Common Audio Message Header

Every Version 1 audio logical payload begins with this exact 8-byte header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `audio_version` |
| 1 | 1 | `message_type` |
| 2 | 1 | `codec` |
| 3 | 1 | `flags` |
| 4 | 4 | `config_generation` |

Constants:

```cpp
kAudioMessageHeaderWireSize == 8
```

All multi-byte audio payload fields use big-endian byte order.

### Audio Configuration Generation

`config_generation` identifies the decoder-critical audio configuration required by an AudioFrame.

Rules:

```text
0 = invalid / no active configuration
first accepted configuration = 1
subsequent format change = previous + 1
UINT32_MAX + 1 wraps to 1, skipping 0
```

SystemAudio and MicrophoneAudio sender instances maintain independent generation and SCL sequence-number state.

### Audio StreamConfig

`StreamConfig.flags` must be zero in Version 1.

After the 8-byte common header, a `StreamConfig` contains:

| Offset | Size | Field |
| ---: | ---: | --- |
| 8 | 4 | `sample_rate_hz` |
| 12 | 1 | `channel_count` |
| 13 | 1 | `frame_duration_code` |
| 14 | 2 | reserved, zero |
| 16 | 4 | `lookahead_samples` |

The StreamConfig payload is exactly 20 bytes:

```cpp
kAudioStreamConfigWireSize == 20
```

Supported Opus sample rates are:

```text
8000, 12000, 16000, 24000, 48000 Hz
```

Supported channel counts are:

```text
1 = mono
2 = stereo
```

Frame duration codes are:

| Value | Duration |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `2.5 ms` |
| 2 | `5 ms` |
| 3 | `10 ms` |
| 4 | `20 ms` |

`lookahead_samples` is copied from the RFC-003B encoder format. Transport does not calculate a replacement. StreamConfig V1 does not serialize bitrate, complexity, CBR/CVBR mode, signal hint, DTX, or in-band FEC.

### AudioFrame

One raw Opus packet maps to one AudioFrame logical message:

```text
8-byte common audio header
8-byte first_frame_position
raw Opus packet
```

The fixed AudioFrame prefix is exactly 16 bytes:

```cpp
kAudioFramePrefixWireSize == 16
```

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common audio header |
| 8 | 8 | `first_frame_position` |
| 16 | ... | raw Opus packet |

`first_frame_position` is an unsigned 64-bit big-endian PCM sample position per channel. It is the audio continuity timeline and is independent from SCL packet sequence numbers.

For `AudioFrame.flags`:

```text
bit 0     = DiscontinuityBefore
bits 1..2 = TimestampQuality
bits 3..7 = reserved, zero
```

Timestamp quality values:

| Bits | Quality |
| ---: | --- |
| `00` | `Unavailable` |
| `01` | `AudioRecordTimestamp` |
| `10` | `EstimatedFromReadCompletion` |
| `11` | reserved, rejected |

`DiscontinuityBefore` means captured PCM continuity was broken immediately before this encoded frame. The sender may retain one local pending-discontinuity boolean after an encoder discontinuity callback; the next successfully submitted AudioFrame carries the flag and clears it.

### Audio Timestamps

For Version 1 `AudioFrame` SCL packets:

```text
PacketHeader::timestamp_us = floor(captureTimeNs / 1000)
```

`captureTimeNs` belongs to the first PCM sample represented by the encoded Opus packet. All fragments of one AudioFrame carry the same timestamp.

Negative Kotlin `captureTimeNs` values are rejected before unsigned native conversion.

### Audio Fragmentation

The logical payload being fragmented is:

```text
StreamConfig:
20-byte StreamConfig payload

AudioFrame:
16-byte AudioFrame prefix + raw Opus packet
```

Fragmentation uses RFC-001C unchanged. Each fragment receives its own SCL sequence number and the same `PacketHeader::timestamp_us` for one logical AudioFrame.

Typical 5 ms Opus packets should normally fit one datagram, but Version 1 does not require that. Oversized packets are fragmented as one logical AudioFrame; Warpnect must not aggregate additional Opus packets to avoid or amortize fragmentation overhead.

### Audio Segmented Packetization

RFC-003C packetizes audio frames as segmented logical payloads:

```text
segment 0 = 16-byte AudioFrame prefix
segment 1 = borrowed raw Opus packet bytes
```

The native packetizer copies only the bytes intersecting each SCL fragment into the outgoing datagram scratch buffer. It does not allocate or require a complete temporary `16 + Opus packet size` staging buffer.

### Audio Transport Policy

RFC-003C emits one AudioFrame per RFC-003B encoded Opus packet and creates no encoded-audio sender queue or audio worker thread. UDP sends are non-blocking. `WouldBlock` and send failures surface immediately to the caller rather than causing hidden backlog.

Audio NACK, SCL FEC, Opus in-band FEC, decoder loss policy, network jitter/playout policy, packet pacing, congestion control, and automatic bitrate adaptation remain outside Audio Payload V1 transport. RFC-003E's bounded PCM handoff ring is a local playback ownership boundary, not an Audio Payload or SCL transport policy. RFC-003G's A/V synchronization is a metadata-only runtime layer above the existing audio and video payload contracts.

### RFC-003F Runtime Behavior

RFC-003F composes Audio Payload V1 into end-to-end audio streaming without changing the wire layout. The transmitter emits each Opus packet immediately as one AudioFrame. The receiver runtime accepts only the configured `SystemAudio` or `MicrophoneAudio` payload type, uses RFC-001C reassembly for fragmented logical messages, publishes `StreamConfigReady` and `AudioFrameReady` events, and exposes bounded native ready-slot storage to the decoder through borrowed direct buffers.

The receiver session uses `first_frame_position` as the audio media timeline. It decodes contiguous frames immediately, drops late/duplicate frames, generates at most a small configured number of immediate Opus PLC frames for aligned gaps, and performs a freshness reset for large or misaligned gaps. It does not add a timed network reorder window, NACK, SCL FEC, Opus in-band FEC, pacing, congestion control, adaptive bitrate, jitter buffer, playback scheduling by sender clock, or A/V synchronization.

These are runtime policies only. They do not modify `PacketHeader`, `PayloadType`, Audio Payload V1, NACK, FEC, ClockSync, Video Payload V1, or VideoResyncRequest.

### RFC-003G Runtime Synchronization

RFC-003G consumes existing Audio Payload V1 capture timing and existing Video Payload V1 presentation timestamps without changing either wire contract.

The audio side provides:

```text
captureTimeUs
firstFramePosition
timestampQuality
lookaheadSamples
```

The video side provides:

```text
PacketHeader::timestamp_us = MediaCodec presentationTimeUs
```

RFC-003G does not assume these timestamp domains are equal. It qualifies video PTS compatibility from bounded timing observations before using synchronized video scheduling. If compatibility is unknown, rejected, stale, or audio presentation is unavailable, video remains on immediate rendering.

Audio presentation through Oboe is the receiver-side synchronization master. The native playback backend publishes small source/output timing anchors and a cold-path presentation query maps remote audio source content time to receiver-local presentation time. Opus lookahead from StreamConfig contributes to this model, but no Audio Payload V1 timestamp is rewritten and no decoded PCM is physically pre-skipped by the sync layer.

Synchronized video uses the existing receiver-local `RenderAt` mechanism. RFC-003G adds no decoded-video queue, no network jitter buffer, no PCM queue, no A/V payload copy, no audio resampling/time stretching, and no new control message.

These are runtime policies only. They do not modify `PacketHeader`, `PayloadType`, Audio Payload V1, Video Payload V1, VideoResyncRequest, NACK, FEC, or ClockSync.

### RFC-003H Runtime Tuning

RFC-003H adds Phase 3 audio performance configuration, deterministic host-native benchmarks, and documented production recovery/playback/A/V tuning decisions without changing the wire contract.

The production audio recovery default remains ImmediateFreshness:

```text
networkReorderWaitUs = 0
maxImmediatePlcFrames = 2
audio NACK = disabled
SCL audio FEC = disabled
Opus in-band FEC = disabled
```

TinyReorderWindow, NACK, FEC, codec-bitrate, frame-duration, and Oboe-buffer experiments are local policy/benchmark choices. They do not add fields to Audio Payload V1, change raw Opus payload opacity, or introduce an A/V synchronization control payload.

RFC-003H does not modify `PacketHeader`, `PayloadType`, Audio Payload V1, Video Payload V1, VideoResyncRequest, NACK, FEC, or ClockSync.

## Input Payload Version 1

RFC-004A defines the Version 1 logical payload carried inside:

```cpp
PayloadType::Input == 4
```

Input Payload Version 1 is independent from SCL Protocol Version, Native Bridge ABI Version, Audio Payload Version, Video Payload Version, and Video Resync Control Version.

Constants:

```cpp
kInputPayloadVersion == 1
kInputMessageHeaderWireSize == 8
```

The 21-byte SCL `PacketHeader` is unchanged. RFC-004A does not add a new `PayloadType`.

### Input Timestamp

For future RFC-004C packetization, `PacketHeader::timestamp_us` carries source-device local monotonic event time in microseconds. Input Payload V1 does not duplicate this timestamp inside the payload.

Input timestamps must not be wall-clock, UTC, calendar, or `System.currentTimeMillis()` values.

### Device Model

Every Input Payload V1 message contains:

```text
device_kind : u8
device_slot : u16
```

`device_slot` is a session-local Warpnect logical device slot:

```text
0        = primary/default device slot
1..65534 = additional session-local slots
65535    = reserved, except ResetState AllDevices
```

Input Payload V1 does not serialize Android `InputDevice.deviceId`, scan codes, `KeyEvent.KEYCODE_*`, `MotionEvent` source bitmasks, Android display IDs, USB bus addresses, Bluetooth addresses, or hardware serials.

### Common Input Header

Every Version 1 input payload starts with this exact 8-byte header:

| Offset | Size | Field | Wire type |
| ---: | ---: | --- | --- |
| 0 | 1 | `input_version` | unsigned 8-bit |
| 1 | 1 | `message_type` | unsigned 8-bit |
| 2 | 1 | `device_kind` | unsigned 8-bit |
| 3 | 1 | `flags` | unsigned 8-bit |
| 4 | 2 | `device_slot` | unsigned 16-bit |
| 6 | 2 | reserved | zero |

All multi-byte fields use big-endian byte order.

Common `flags` are reserved and must be zero in Version 1. Reserved bytes must be zero.

### Device Kinds

| Value | Kind |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Keyboard` |
| 2 | `Touchscreen` |
| 3 | `Mouse` |
| 4 | `Gamepad` |
| 5 | `Stylus` |
| 6 | `Touchpad` |

`Unknown` is valid only for `ResetState` with `AllDevices`.

### Message Types

| Value | Message type |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Key` |
| 2 | `TouchFrame` |
| 3 | `PointerAbsolute` |
| 4 | `PointerRelative` |
| 5 | `Scroll` |
| 6 | `GamepadState` |
| 7 | `ResetState` |

Unknown or unsupported message values are rejected.

### Message And Device Compatibility

Version 1 uses this strict compatibility matrix:

| Message | Device kinds |
| --- | --- |
| `Key` | `Keyboard` |
| `TouchFrame` | `Touchscreen`, `Touchpad`, `Stylus` |
| `PointerAbsolute` | `Mouse`, `Stylus`, `Touchpad` |
| `PointerRelative` | `Mouse`, `Touchpad` |
| `Scroll` | `Mouse`, `Touchpad` |
| `GamepadState` | `Gamepad` |
| `ResetState` | valid device for `ThisDevice`, `Unknown` for `AllDevices` |

Gamepad buttons are not encoded as keyboard key messages.

### Key

`Key` is exactly 20 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 2 | `usage_page` |
| 10 | 2 | `usage_id` |
| 12 | 1 | `action` |
| 13 | 1 | reserved, zero |
| 14 | 2 | `repeat_count` |
| 16 | 2 | `modifier_mask` |
| 18 | 2 | reserved, zero |

Keyboard semantics use USB HID Usage Page and USB HID Usage ID, referencing USB HID Usage Tables 1.7 as the semantic baseline. The protocol does not restrict parsing to a small platform whitelist of known usages.

Key actions:

| Value | Action |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Down` |
| 2 | `Up` |

`Unknown` and other values are rejected. `Up` requires `repeat_count == 0`; repeated `Down` may use a nonzero repeat count.

Modifier mask:

| Bit | Meaning |
| ---: | --- |
| 0 | LeftControl |
| 1 | LeftShift |
| 2 | LeftAlt |
| 3 | LeftGui |
| 4 | RightControl |
| 5 | RightShift |
| 6 | RightAlt |
| 7 | RightGui |
| 8..15 | reserved, zero |

Modifier keys remain representable as ordinary HID key usages. Input Payload V1 does not define Unicode text, IME composition, clipboard, or paste messages.

### TouchFrame

`TouchFrame` contains one source multi-contact observation. The prefix is 12 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 1 | `action` |
| 9 | 1 | `action_pointer_id` |
| 10 | 1 | `pointer_count` |
| 11 | 1 | reserved, zero |

Each contact is exactly 12 bytes:

| Relative offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `pointer_id` |
| 1 | 1 | `tool_type` |
| 2 | 2 | `pointer_flags` |
| 4 | 2 | `x_normalized` |
| 6 | 2 | `y_normalized` |
| 8 | 2 | `pressure` |
| 10 | 2 | `size` |

Exact payload size:

```text
12 + pointer_count * 12
```

`pointer_count` is bounded to 32. Non-cancel observations require at least one contact. `Cancel` may carry zero through 32 contacts depending on adapter semantics. Pointer IDs are 0..31 and must be unique within one frame.

Touch actions:

| Value | Action |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Down` |
| 2 | `Up` |
| 3 | `Move` |
| 4 | `Cancel` |
| 5 | `PointerDown` |
| 6 | `PointerUp` |

`Down`, `Up`, `PointerDown`, and `PointerUp` require `action_pointer_id` to name a contact present in the frame. `Move` and `Cancel` require `action_pointer_id == 0xFF`.

Tool types:

| Value | Tool |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `Finger` |
| 2 | `Stylus` |
| 3 | `Eraser` |
| 4 | `Mouse` |

Pointer flags:

| Bit | Meaning |
| ---: | --- |
| 0 | PressureValid |
| 1 | SizeValid |
| 2..15 | reserved, zero |

When pressure or size validity is false, the corresponding field must be zero. Coordinates are normalized unsigned 16-bit values: x=0 left, x=65535 right, y=0 top, y=65535 bottom.

### PointerAbsolute

`PointerAbsolute` is exactly 20 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 2 | `x_normalized` |
| 10 | 2 | `y_normalized` |
| 12 | 2 | `button_mask` |
| 14 | 2 | `pointer_flags` |
| 16 | 2 | `pressure` |
| 18 | 2 | reserved, zero |

Absolute pointer coordinates are normalized to the logical Warpnect remote-control surface. Pixel coordinates are not serialized.

PointerAbsolute flags currently define bit 0 as `PressureValid`; bits 1..15 are reserved. If pressure is not valid, the pressure field must be zero.

### PointerRelative

`PointerRelative` is exactly 20 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 4 | `delta_x_q16_16` |
| 12 | 4 | `delta_y_q16_16` |
| 16 | 2 | `button_mask` |
| 18 | 2 | reserved, zero |

Relative motion uses signed Q16.16 normalized surface deltas:

```text
65536  = +1.0 logical surface width/height
-65536 = -1.0 logical surface width/height
```

Zero relative motion is valid when button state changed. Pointer acceleration, DPI scaling, sensitivity, and target-pixel conversion are outside Input Payload V1.

### Scroll

`Scroll` is exactly 16 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 2 | `horizontal_q8_8` |
| 10 | 2 | `vertical_q8_8` |
| 12 | 2 | `button_mask` |
| 14 | 2 | reserved, zero |

Scroll uses signed Q8.8 logical units:

```text
256 = +1.0 logical scroll unit
```

Both horizontal and vertical zero is rejected as a no-op.

### Pointer Button Mask

Pointer button mask:

| Bit | Button |
| ---: | --- |
| 0 | Primary |
| 1 | Secondary |
| 2 | Tertiary |
| 3 | Back |
| 4 | Forward |
| 5..15 | reserved, zero |

### GamepadState

`GamepadState` is exactly 28 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 4 | `button_mask` |
| 12 | 2 | `left_x` |
| 14 | 2 | `left_y` |
| 16 | 2 | `right_x` |
| 18 | 2 | `right_y` |
| 20 | 2 | `left_trigger` |
| 22 | 2 | `right_trigger` |
| 24 | 4 | reserved, zero |

Each GamepadState is a complete common-controller snapshot. It is not a stream of independent axis/button deltas.

Gamepad button mask:

| Bit | Button |
| ---: | --- |
| 0 | A |
| 1 | B |
| 2 | X |
| 3 | Y |
| 4 | LeftShoulder |
| 5 | RightShoulder |
| 6 | LeftTriggerButton |
| 7 | RightTriggerButton |
| 8 | Select/Back |
| 9 | Start |
| 10 | Guide/Mode |
| 11 | LeftStickButton |
| 12 | RightStickButton |
| 13 | DpadUp |
| 14 | DpadDown |
| 15 | DpadLeft |
| 16 | DpadRight |
| 17..31 | reserved, zero |

Stick axes use signed 16-bit normalized values:

```text
-32767 = -1.0
0      = center
32767  = +1.0
```

`-32768` is reserved and rejected. X negative is left, X positive is right, Y negative is up, and Y positive is down. Triggers use unsigned 16-bit normalized values from released 0 to fully pressed 65535.

No deadzone, sensitivity curve, or platform gamepad keycode is part of the protocol.

### ResetState

`ResetState` is exactly 12 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | common input header |
| 8 | 1 | `scope` |
| 9 | 1 | `reason` |
| 10 | 2 | reserved, zero |

Scopes:

| Value | Scope |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `ThisDevice` |
| 2 | `AllDevices` |

`Unknown` scope is rejected. `ThisDevice` requires a valid non-unknown device kind and device slot 0..65534. `AllDevices` requires `device_kind = Unknown` and `device_slot = 65535`; this is the only Version 1 use of slot 65535.

Reasons:

| Value | Reason |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `SessionStop` |
| 2 | `DeviceDisconnected` |
| 3 | `FocusLost` |
| 4 | `ErrorRecovery` |
| 5 | `UserRequest` |

Reason is diagnostic. ResetState is not a device descriptor, capability negotiation, or session handshake.

### Delivery Class

Input Payload V1 defines a local helper classification, not a wire field:

| Class | Examples |
| --- | --- |
| `FreshState` | GamepadState, PointerAbsolute, PointerRelative, Scroll, Touch Move |
| `CriticalTransition` | Key Down/Up, Touch Down/Up/PointerDown/PointerUp/Cancel |
| `Reset` | ResetState |

The delivery class prepares RFC-004C/RFC-004G. RFC-004A does not implement input retransmission, duplication, NACK, FEC, reliable queues, or transport policy.

### Serialization Rules

Input Payload V1 uses explicit big-endian field encoding. Native runtime structures are not packed or copied directly onto the wire. Fixed-size messages require exact length. TouchFrame length is calculated from the bounded pointer count. Trailing bytes, reserved bits, reserved bytes, invalid enum values, duplicate pointer IDs, invalid action pointers, and reserved gamepad axis/button values are rejected.

Input Payload V1 contains no input-specific fragmentation model, no batching message, no compression chain, and no input-specific sequence field. RFC-004C may use the existing SCL `PacketHeader.sequence_number`, `PacketHeader.timestamp_us`, and RFC-001C fragmentation primitives when transport is introduced.

RFC-004B is the Android source adapter for these existing Input Payload V1 semantics. It translates delivered Android `KeyEvent` and `MotionEvent` values into portable Kotlin input models but does not alter the wire layout, add Kotlin binary serialization, add JNI input transport, or introduce UDP input runtime.

## Loss Detection, NACK, And Recovery

RFC-001D defines bounded packet-level loss detection and selective retransmission primitives for one caller-scoped recovery domain.

A recovery domain is an ordered sequence-number context managed by the caller. It may correspond to a payload type, direction, and session or stream context, but no recovery-domain identifier is added to the Version 1 packet header.

Do not feed NACK control packets into the same loss detector that tracks the data packets they request.

### Sequence Ordering

Loss detection uses 32-bit modular sequence arithmetic.

A sequence `a` is newer than sequence `b` when:

```text
a != b
and
(a - b) modulo 2^32 < 2^31
```

The exact half-range distance `0x80000000` is ambiguous and must not be silently ordered.

### Local Timing

NACK scheduling uses caller-supplied local monotonic timestamps:

- `reorder_delay_us`: how long a missing candidate waits before the first NACK.
- `renack_interval_us`: fixed interval between repeated NACK attempts.
- `max_nack_attempts`: maximum emitted NACK attempts per missing sequence.

The recovery layer owns no timers and creates no threads.

### NACK Wire Payload

NACK is a Version 1 `SessionControl` payload subtype. It does not add a new `PayloadType`.

The NACK payload is exactly 16 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 1 | `target_payload_type` |
| 3 | 1 | `reserved` |
| 4 | 4 | `base_sequence_number` |
| 8 | 8 | `missing_bitmap` |

Constants:

```cpp
kNackPayloadWireSize == 16
kNackControlVersion == 1
SessionControlType::Nack == 1
```

All multi-byte NACK payload fields use big-endian byte order.

### NACK Bitmap

Bit `i` in `missing_bitmap` requests:

```text
base_sequence_number + i modulo 2^32
```

The bitmap must be non-zero. `target_payload_type` must be a defined non-`Unknown` Version 1 payload type. The reserved byte must be zero.

### Retransmission

A retransmission reuses the exact original encoded datagram.

Retransmission must not regenerate or mutate:

- Protocol version.
- Flags.
- Sequence number.
- Timestamp.
- Payload type.
- Slice metadata.
- Payload bytes.

The retransmission cache is bounded, caller-owned, and deterministic. Lookup returns a non-owning span over cached bytes. Cache eviction uses oldest-slot ring replacement in RFC-001D.

## Reed-Solomon FEC

RFC-001E defines proactive Forward Error Correction using systematic Reed-Solomon erasure coding over `GF(256)` with primitive polynomial `0x11D`.

FEC protects complete encoded original SCL data datagrams:

```text
PacketHeader + payload
    -> encoded SCL datagram
    -> 2-byte original length prefix
    -> zero-padded systematic RS shard
```

The Reed-Solomon block contains:

```text
K data shards + M parity shards
```

Any `K` valid shards from the `K + M` block are sufficient to reconstruct the systematic data shards. This is erasure correction only: the receiver must know which shards are present or missing. RFC-001E does not detect silent byte corruption.

All data datagrams in one FEC block belong to one caller-defined recovery domain. At minimum, they share:

- Target payload type.
- Base sequence number.
- Data shard count.
- Parity shard count.
- Shard size.

Data shard `i` protects the original encoded SCL datagram whose sequence number is:

```text
base_sequence_number + i modulo 2^32
```

FEC does not add fields to the 21-byte SCL packet header and does not change Protocol Version 1.

### FEC Parity Control Payload

FEC parity is transported as a `SessionControl` payload subtype. It does not add a new `PayloadType`.

Constants:

```cpp
SessionControlType::FecParity == 2
kFecParityControlVersion == 1
kFecParityHeaderWireSize == 16
```

The FEC parity payload starts with a 16-byte metadata header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 1 | `target_payload_type` |
| 3 | 1 | `parity_index` |
| 4 | 4 | `base_sequence_number` |
| 8 | 1 | `data_shards` |
| 9 | 1 | `parity_shards` |
| 10 | 2 | `shard_size` |
| 12 | 4 | `reserved` |

Immediately after the header are exactly `shard_size` parity bytes.

All multi-byte FEC parity fields use big-endian byte order.

FEC parity validation rejects malformed payloads, unsupported control versions, `Unknown` or undefined target payload types, `SessionControl` as the target payload type, zero shard counts, `data_shards + parity_shards > 255`, out-of-range parity indexes, `shard_size < 2`, nonzero reserved bits, and payload length mismatches.

### FEC Datagram Budget

FEC budget calculation uses a caller-selected maximum complete wire datagram size:

```text
max protected datagram =
max wire datagram
- SCL header
- FEC parity header
- original length prefix
```

In constants:

```text
max_protected_datagram_size =
max_wire_datagram_size - 21 - 16 - 2
```

No global SCL MTU is frozen by RFC-001E.

When FEC is enabled, the caller must choose an RFC-001C fragmentation datagram budget no larger than `max_protected_datagram_size`, so parity packets fit the same selected wire budget.

### FEC And NACK

FEC is the first recovery opportunity. Selective NACK/retransmission remains the correctness fallback when fewer than `K` shards are available.

FEC primitives do not generate NACKs, retransmit parity, own UDP sockets, create threads, estimate RTT, adapt parity ratios, pace traffic, or perform congestion control.

## Clock Synchronization And Telemetry

RFC-001F defines local runtime clock synchronization and telemetry measurement.

SCL never modifies operating-system clocks. It maintains an internal affine model between endpoint monotonic clock domains:

```text
remote_time ~= reference_remote + rate_ratio * (local_time - reference_local)
```

Clock synchronization uses `PayloadType::SessionControl` and does not add a new `PayloadType`.

Constants:

```cpp
SessionControlType::ClockSyncRequest == 3
SessionControlType::ClockSyncResponse == 4
kClockSyncControlVersion == 1
kClockSyncRequestWireSize == 16
kClockSyncResponseWireSize == 32
```

The 16-byte request payload is:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 2 | `reserved` |
| 4 | 4 | `exchange_id` |
| 8 | 8 | `t0_us` |

The 32-byte response payload is:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type` |
| 1 | 1 | `control_version` |
| 2 | 2 | `reserved` |
| 4 | 4 | `exchange_id` |
| 8 | 8 | `t0_us` |
| 16 | 8 | `t1_us` |
| 24 | 8 | `t2_us` |

All multi-byte fields use big-endian byte order. `t3_us` is recorded locally by the initiator and is not transmitted.

For a valid four-timestamp exchange:

```text
RTT = (t3 - t0) - (t2 - t1)

offset = ((t1 - t0) + (t2 - t3)) / 2
```

`offset > 0` means the remote clock is numerically ahead of the local clock for that exchange.

Accepted samples expose safe local and remote midpoints. Model fitting uses midpoint pairs and centered affine least-squares estimation to reduce precision loss from large monotonic timestamps.

Four-timestamp synchronization cannot independently determine clock offset and path asymmetry. The offset and one-way delay values are estimates, not exact truth.

Clock quality states are:

- `Unsynchronized`.
- `WarmingUp`.
- `Synchronized`.
- `Degraded`.
- `Stale`.

Remote-to-local and local-to-remote conversion require a `Synchronized` model. One-way delay estimation also requires explicitly valid timestamp semantics and a synchronized model.

Network telemetry is local runtime measurement only. RFC-001F defines no `PayloadType::Telemetry` wire schema.

Telemetry counters are saturating and preserve separate observations for transport, loss/reordering, NACK, retransmission, FEC, and clock synchronization. Telemetry also tracks bounded RTT and one-way-delay rolling windows and jitter:

```text
J(i) = J(i-1) + (abs(d(i) - d(i-1)) - J(i-1)) / 16
```

Telemetry is observational. It must not alter FEC ratios, NACK timing, congestion behavior, datagram sizing, pacing, bitrate, packet bytes, or transport behavior.

## Video Resynchronization Control

RFC-002G adds a compact video resynchronization request as a `SessionControl` payload subtype. It does not change the 21-byte SCL `PacketHeader`, does not add a new `PayloadType`, and does not change Video Payload Version 1.

Constants:

```cpp
SessionControlType::VideoResyncRequest == 5
kVideoResyncControlVersion == 1
kVideoResyncRequestWireSize == 8
```

The 8-byte VideoResyncRequest V1 payload is:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | `control_type = VideoResyncRequest` |
| 1 | 1 | `control_version = 1` |
| 2 | 1 | `reason` |
| 3 | 1 | reserved, zero |
| 4 | 4 | `receiver_config_generation` |

All multi-byte fields use big-endian byte order.

Version 1 reason values are:

| Value | Reason |
| ---: | --- |
| 0 | `Unknown` |
| 1 | `NeedConfiguration` |
| 2 | `NeedKeyFrame` |
| 3 | `Discontinuity` |
| 4 | `DecoderRestart` |
| 5 | `SurfaceRecreated` |
| 6 | `ReceiverOverflow` |

Validation rejects wrong payload size, wrong control type, unsupported control version, undefined reason values, and nonzero reserved bytes.

`receiver_config_generation == 0` means the receiver has no usable configuration. A nonzero value reports the receiver's currently known generation and must not be interpreted as proof that it matches the sender's latest generation.

On a valid request, the transmitter may resend its current StreamConfig and ask the hardware encoder for a sync frame through the existing encoder control mechanism. The transmitter must not retain encoded media access units for resynchronization.

Unsupported peers that do not recognize subtype `5` must reject or ignore it safely rather than reinterpret it as NACK, FEC, clock sync, or another control.

## Stability

## Session Handshake Protocol Version 1

RFC-005D defines a separate pre-session WNSH control protocol, not SCL. Its fixed 32-byte header
uses magic `WNSH`, version 1, message type, flags, non-zero attempt ID, logical sequence, payload
length, and zero reserved bytes. ClientHello/HelloRetry/ServerHello are plaintext bootstrap
messages; ServerAuth, ClientAuth, and ServerComplete are AES-128-GCM records with the WNSH header
as AAD. The logical sequence is 0 through 6 and retransmissions are byte-identical cached
datagrams, not new transcript entries.

Session Handshake Protocol V1 uses P-256 ECDH, P-256/SHA-256 identity signatures, SHA-256,
HKDF-SHA256, AES-128-GCM, and HMAC-SHA256. It does not add SessionId, DeviceId, ChannelId, PathId,
or a security field to PacketHeader or existing media/input payloads.

Packet layout is part of the SCL protocol contract.

Breaking packet layout or protocol semantic changes require a Protocol Version change and an RFC.

Transport API changes that affect native callers require an RFC and may require Native ABI documentation if exposed through the bridge in a later phase.

## Reverse Input Transport Profile

RFC-004C adds a transport profile above the unchanged Input Payload Version 1 layout. Every input observation is exactly one SCL datagram with `PayloadType::Input`, `slice_index = 0`, and `total_slices = 1`; Input does not use RFC-001C fragmentation or reassembly.

The largest Input Payload V1 message is a 32-contact TouchFrame at 396 bytes. Together with the frozen 21-byte PacketHeader, the maximum reverse-input datagram is 417 bytes. `InputTransportConfig.maxWireDatagramSize` must be at least 417 bytes; larger budgets are allowed but unused by Input V1.

`PacketHeader.timestamp_us` is RFC-004B's source Android monotonic event time, preserved exactly rather than replaced by JNI or send time. One unsigned 32-bit Input sequence domain spans all logical device slots and message types for an `InputTransportSender` lifecycle. A sequence is consumed after a valid message reaches its send attempt, including `WouldBlock` or send failure, so transport loss remains observable.

The RFC-004C production policy is BestEffortImmediate for FreshState, CriticalTransition, and Reset events. Delivery class is local telemetry metadata, not a wire field. NACK, FEC, retransmission caching, duplication, pacing, timer flushes, batching, and receiver ordering/reassembly are intentionally absent.

## Privileged Input Injection

RFC-004D adds no SCL wire message, PacketHeader change, PayloadType change, NativeBridge ABI change, or Input Payload Version change. It is an Android target-side primitive that consumes already Android-ready events over an internal synchronous AIDL connection to a Shizuku/Sui UserService. Portable HID/key mapping and normalized-coordinate mapping remain RFC-004E work.

## Input Mapping Semantics

RFC-004E adds no normative wire change. Input Payload V1 absolute coordinates represent normalized remote-control content coordinates after endpoint-local receiver viewport mapping. Android target pixel conversion, target display geometry, rotation diagnostics, Android HID/keycode mapping, and Android device-ID resolution remain outside the SCL protocol and never modify Input Payload V1 bytes.

## Input Convergence Profile

RFC-004G adds no normative wire change. Input Payload V1 remains unchanged. Endpoint-local
reliability uses existing packet sequence numbers, full-state message semantics, bounded state, and
additional immediate copies of existing logical messages. There is no input ACK, NACK, FEC, session
token, transport reordering wait, or new payload/control message.

## Session Model Non-Wire Semantics

RFC-005A defines application/session semantic identities that later Phase 5 RFCs may encode into
authenticated control or session messages. It does not add DeviceId, SessionId, ChannelId, PathId,
or endpoint data to the frozen SCL PacketHeader, PayloadType values, Video Payload V1, Audio
Payload V1, Input Payload V1, ClockSync, NACK, FEC, or VideoResyncRequest.

Input Payload V1 device slots remain session-scoped portable peripheral identifiers. They are not
global peer identity, Android device identity, or a replacement for a future SessionId.

## Session Packet Protection Version 1

RFC-005E defines a new outer secure datagram envelope, not an SCL packet format. WNSD has a fixed
28-byte clear authenticated header: `WNSD`, version 1, zero flags, zero reserved bytes, an opaque
u64 protection context ID, u32 key epoch, and u64 security packet number. The header is AES-128-GCM
AAD. The ciphertext is exactly one existing complete SCL datagram and is followed by a 16-byte tag.

WNSD has 44 bytes of fixed overhead. It does not modify PacketHeader, PayloadType, Video Payload
V1, Audio Payload V1, Input Payload V1, NACK, FEC, ClockSync, VideoResyncRequest, the discovery
schema, the pairing bootstrap, or WNSH. Its packet numbers are a separate security sequence space.

## Discovery Presence Schema V1

RFC-005B's Discovery Presence Schema Version 1 is DNS-SD control-plane metadata only. Its
ephemeral DiscoveryPresenceId, discovery route token, service name, and TXT fields are not SCL
packet fields. No DeviceId, SessionId, ChannelId, PathId, discovery identifier, or discovery
metadata has been added to PacketHeader, PayloadType, Video Payload V1, Audio Payload V1, Input
Payload V1, ClockSync, NACK, FEC, or VideoResyncRequest.

## Pairing Bootstrap Protocol V1

RFC-005C adds a separate pre-session Pairing Bootstrap Protocol Version 1. It is not an SCL
packet or media protocol. Its standalone datagram header is `WNPB`, version 1, message type,
zero flags, a 128-bit PairingAttemptId, payload length, and zero reserved bytes. The only V1
messages are Commit, Response, Reveal, Confirm, Reject, and Abort.

The protocol carries no SessionId and changes no SCL PacketHeader, PayloadType, Video Payload V1,
Audio Payload V1, Input Payload V1, ClockSync, NACK, FEC, VideoResyncRequest, or Discovery
Presence Schema V1. It establishes a persistent DeviceId-to-public-key trust binding only; live
session authentication and packet protection remain later Phase 5 work.
