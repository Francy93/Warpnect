# Native Boundary

## RFC-004F Reverse Input

Source: Android capture -> portable Kotlin model -> viewport mapper -> `SclInputEventSink` -> JNI -> native `InputTransportSender` -> UDP.

Target: UDP -> native `InputReceiverRuntime` -> native `InputDatagramParser` -> persistent direct native-order bridge -> portable Kotlin model -> `AndroidTargetInputMapper` -> RFC-004D Binder/UserService.

The target creates no Input Payload `ByteArray` and Kotlin does not parse Input Payload V1 wire bytes. The bridge is a private scalar record, not a second wire format.

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

The native boundary is the relationship between Kotlin, JNI, and the C++20 SCL core.

## Boundary Shape

```text
Kotlin Warpnect layer
        |
        v
NativeBridge.kt
        |
        v
jni_bridge.cpp
        |
        v
warpnect::scl C++ interfaces
```

## Kotlin Side

`NativeBridge.kt` is the only Kotlin-side entry point for direct native calls.

It may:

- Load `scl_core`.
- Expose simple bridge metadata.
- Call stable native SCL entry points through `NativeBridge.kt`.
- Forward RFC-002B encoded direct `ByteBuffer` access units synchronously into native RFC-002C transport.

It must not:

- Encode UDP packet layout in Kotlin.
- Implement native fallback logic.
- Contain transport behavior.
- Bypass the orchestrator for application decisions.

## JNI Side

`jni_bridge.cpp` is glue code only.

It may:

- Convert JNI parameters and return values.
- Call native bridge functions.
- Report native errors in a structured way later.
- Validate direct-buffer address, capacity, offset, and size for borrowed encoded access units.
- Copy cold-path codec-specific-data byte arrays during output-format submission.

It must not:

- Implement networking.
- Implement serialization.
- Implement session policy.
- Own business logic.
- Call Android UI APIs.

## C++ Side

SCL C++ owns protocol, timing, telemetry, and future transport interfaces.

SCL must not include Android lifecycle, Compose, Activity, Shizuku, or application UI concepts.

Android display capture remains entirely on the Kotlin/Android platform side. RFC-002A does not add native capture entry points, and `NativeBridge.kt` does not expose `startCapture`, `stopCapture`, or capture-surface JNI methods.

Android hardware video encoding remains a Kotlin/Android `MediaCodec` platform responsibility. RFC-002B does not add video JNI entry points, encoded-video transport calls, or native bridge ABI changes.

RFC-002C adds encoded-video transport entry points as additive Native Bridge ABI Version 1 calls. The hot path is:

```text
MediaCodec encoded bytes
        |
borrowed DirectByteBuffer
        |
NativeBridge JNI
        |
portable SCL video transport
```

Kotlin owns the `MediaCodec` output buffer lifetime. JNI borrows the direct-buffer pointer only for the duration of the native call. C++ must finish video header generation, fragmentation, packet encoding, cache storage, optional FEC acceptance, and UDP submission before returning. It must not retain the direct-buffer pointer.

Codec-specific data is submitted on output-format changes and may be copied as cold-path configuration data. This is distinct from the per-access-unit media hot path.

RFC-002D hardware decoding remains entirely on the Android/Kotlin platform side and introduces no decoder JNI API. Decoder production input is pull-based from MediaCodec-owned input buffers, and decoder output is a caller-owned Android `Surface`.

The intended future RFC-002F receive boundary is:

```text
native reassembled encoded AU
        |
copy directly into codec-owned decoder input ByteBuffer
        |
MediaCodec decoder
        |
caller-owned Surface
```

That direct native-reassembly to decoder-input handoff is not implemented in RFC-002D. `NativeBridge.kt` remains unchanged for decoder purposes, and Native Bridge ABI Version 1 remains valid.

RFC-002E rendering is entirely Android/Kotlin-side and adds no JNI rendering API.

RFC-002F adds an additive Native Bridge ABI Version 1 receiver runtime boundary:

```text
native SCL receiver
        |
bounded reassembly slot owning complete encoded AU
        |
MediaCodec-owned direct input ByteBuffer
        |
JNI synchronous fill
        |
RFC-002D decoder
        |
RFC-002E SurfaceView
```

Native owns the completed AU until a successful fill. MediaCodec owns the destination input buffer. JNI borrows the destination pointer only during the fill call and must not retain it. Kotlin receives only small AU metadata and never owns the complete AU payload on the receiver hot path.

RFC-002G adds additive Native Bridge ABI Version 1 controls and snapshot fields for performance tuning. These include receiver resync requests, receiver freshness/queue/clock telemetry, and sender resync/clock counters.

Diagnostics remain metadata and counters only. No complete encoded media payload crosses Kotlin for telemetry, resynchronization, clock sync, or performance reporting.

The RFC-002G hot receiver media boundary remains:

```text
native receiver reassembly slot
        |
MediaCodec input DirectByteBuffer
        |
JNI synchronous fill
        |
MediaCodec decoder
```

RFC-003A introduces no SCL audio JNI transport and no NativeBridge audio entry points. System audio crosses an Android IPC boundary, not the native SCL boundary:

```text
privileged Shizuku/Sui process
        |
SharedMemory PCM ring + FD notification/ACK
        |
ordinary Warpnect app process
        |
PcmAudioSink borrowed ByteBuffer view
```

PCM payload bytes do not cross Binder. Binder transfers setup/control metadata and descriptors only.

RFC-003B adds an additive Native Bridge ABI Version 1 Opus encoder boundary:

```text
borrowed direct PCM ByteBuffer
        |
NativeBridge JNI
        |
portable warpnect::audio Opus encoder
        |
persistent borrowed encoded output buffer
```

Kotlin owns the RFC-003A PCM callback lifetime. JNI borrows the PCM pointer only during the synchronous submit call. Native code owns the libopus encoder state, the single incomplete-frame accumulator, and the encoded packet scratch buffer. `EncodedAudioSink` borrows the output `DirectByteBuffer` only for the duration of `onEncodedFrame()`.

RFC-003B introduces no SCL audio transport JNI. No complete PCM or encoded audio media payload crosses JNI as a Kotlin `ByteArray`.

RFC-003C adds additive Native Bridge ABI Version 1 audio transport calls:

```text
native libopus output scratch
        |
borrowed DirectByteBuffer
        |
Kotlin EncodedAudioSink
        |
NativeBridge JNI
        |
SCL Audio Payload V1 packetization
        |
non-blocking UDP
```

Kotlin owns the encoded callback lifetime. JNI borrows the Opus packet pointer only during the synchronous audio frame submission call. Native packetization copies only fragment-sized ranges into the datagram scratch buffer and must not retain the encoded pointer. No complete encoded packet crosses JNI as a Kotlin `ByteArray`, and no complete native logical-message staging buffer is required.

RFC-003D adds an additive Native Bridge ABI Version 1 Opus decoder boundary:

```text
borrowed Opus DirectByteBuffer
        |
NativeBridge JNI
        |
portable warpnect::audio OpusAudioDecoder
        |
native PCM scratch
        |
persistent borrowed DirectByteBuffer
        |
DecodedPcmAudioSink
```

Kotlin owns the encoded packet lifetime. JNI borrows the encoded pointer only during the synchronous decode call. Native owns the libopus decoder state and the preallocated PCM scratch buffer. Kotlin receives a persistent direct output view but does not own or copy the PCM payload. `DecodedPcmAudioSink` borrows the output only for the duration of `onPcmFrame()` and must not retain it.

RFC-003D introduces no SCL parser, network receive runtime, AudioTrack playback, audio jitter buffer, encoded packet queue, decoded PCM queue, per-frame encoded `ByteArray`, or per-frame PCM `ByteArray`.

RFC-003E adds an additive Native Bridge ABI Version 1 Android playback boundary:

```text
RFC-003D native PCM scratch
        |
borrowed DirectByteBuffer
        |
Kotlin DecodedPcmAudioSink
        |
NativeBridge JNI
        |
native playback ring
        |
Oboe callback
        |
Android audio device
```

The decoded PCM pointer is borrowed only during synchronous JNI submission. Native playback owns one fixed-capacity SPSC PCM ring and performs one necessary ownership copy into that ring because Oboe consumes output asynchronously on the hardware audio clock. Kotlin does not own or copy PCM payload bytes, and no PCM payload crosses JNI as a `ByteArray`.

The Oboe data callback is entirely native. It must not invoke JNI, call Kotlin/Java, allocate, block on a mutex/condition variable, perform network or file I/O, log per burst, query timestamps, or decode codecs.

RFC-003E introduces no SCL protocol change, SCL parser, network receive runtime, `AudioTrack` fallback, playback worker queue, network jitter buffer, per-frame coroutine, sample-rate conversion, channel mixer, effects, or A/V synchronization.

RFC-003F adds an additive Native Bridge ABI Version 1 audio receiver/runtime boundary:

```text
native SCL audio ready slot
        |
persistent borrowed DirectByteBuffer
        |
Kotlin receiver session
        |
RFC-003D decoder JNI
        |
native PCM scratch
        |
borrowed DirectByteBuffer
        |
RFC-003E playback JNI
        |
native playback ring
        |
Oboe
```

The native audio receiver owns bounded reassembly slots and ready slots. Kotlin receives slot metadata and borrows a persistent direct view only until the synchronous decoder call returns. The session releases the slot in `finally` so decoder or playback failures cannot leak ready capacity.

RFC-003F does not return encoded packets as Kotlin `ByteArray` values and does not create a second complete encoded-packet staging copy. The decoder/playback boundary remains the single required decoded-PCM ownership copy into RFC-003E's native playback ring.

RFC-003F adds no PacketHeader, PayloadType, Audio Payload V1, NACK, FEC, ClockSync, Video Payload V1, or VideoResyncRequest wire change.

RFC-003G adds additive Native Bridge ABI Version 1 audio presentation-anchor query support:

```text
Oboe callback
        |
small source/output timing anchor
        |
native playback backend
        |
cold-path JNI query
        |
AvSyncController
```

The Oboe callback publishes only fixed metadata relating remote source PCM timing to native output-frame positions. It does not invoke JNI, Kotlin/Java, MediaCodec, network code, codec decode, timestamp queries, allocation, logging, or blocking synchronization.

The cold-path query combines the latest native source/output anchor with Oboe `CLOCK_MONOTONIC` presentation timestamp data and returns a small synchronization result. No media payload crosses this boundary.

RFC-003G also publishes a small immutable A/V model to the video render policy:

```text
AvSyncModel
        |
atomic read
        |
VideoRenderPolicy
```

The render policy consumes metadata only and returns existing `RenderImmediately` or receiver-local `RenderAt` decisions. It does not copy video bitstreams, decoded pixels, Opus packets, or PCM frames.

RFC-003G adds no PacketHeader, PayloadType, Audio Payload V1, Video Payload V1, VideoResyncRequest, NACK, FEC, or ClockSync wire change.

RFC-003H adds performance configuration, bounded telemetry visibility, and host-native benchmark coverage only. It does not add a new hot-path JNI media boundary and does not introduce any additional Opus, PCM, AVC, or decoded-video payload copy across JNI.

Tuning controls remain explicit metadata:

```text
AudioPerformanceConfig
        |
subsystem config snapshots
        |
existing NativeBridge calls
```

The production media boundaries from RFC-003A through RFC-003G remain unchanged. Benchmark CSV output is generated under native build directories and is not a runtime media path.

RFC-004A introduces no JNI input boundary and no NativeBridge entry point. It adds only portable C++20 Input Payload V1 encode/decode/validation and a platform-neutral Kotlin input model.

Future Phase 4 boundaries are expected to compose as:

```text
Android input capture
        |
portable Kotlin input model
        |
JNI / SCL input transport
        |
Input Payload V1
```

RFC-004B capture is Android-side and remains transport-agnostic:

```text
Android KeyEvent / MotionEvent
        |
WarpnectInputCaptureView
        |
Android input adapters
        |
portable Kotlin Input model
        |
InputEventSink
```

RFC-004C establishes the additive send boundary:

```text
portable Kotlin Input model
        |
SclInputEventSink
        |
NativeBridge primitive fields
        |
InputTransportSender
        |
PacketHeader + Input Payload V1
        |
non-blocking SCL UDP
```

Fixed-size events cross JNI as primitive fields. A TouchFrame crosses in one JNI call through a controller-owned 896-byte direct scratch buffer holding seven native-order `Int` fields per contact: pointer ID, tool type, flags, normalized X/Y, pressure, and size. The scratch is not Input Payload wire format.

RFC-004C creates no Input Payload `ByteArray`, no Kotlin wire serializer, no per-contact JNI call, and no receiver JNI path. Native packet encoding writes directly into the sender's fixed 417-byte datagram storage. Privileged injection remains RFC-004D.

RFC-004D adds no NativeBridge or native C++ input-injection path. Its Android-only boundary is:

```text
Android-ready event
        |
synchronous AIDL / Binder
        |
Shizuku/Sui UserService
        |
cached Android InputManager reflection
```

The UserService constructs and recycles Android `InputEvent` instances locally. No SCL payload, native input packet, JNI buffer, or media payload crosses this injection boundary.

RFC-004E adds no JNI or Binder interface. It composes endpoint-local Kotlin model transforms around the existing boundaries:

```text
receiver Android capture
        -> portable Input model
        -> viewport mapper
        -> RFC-004C JNI / SCL send

target portable Input model
        -> Android target mapper
        -> RFC-004D Binder / UserService
```

The renderer publishes only immutable `VideoViewportGeometry` metadata to the receiver mapper. Target display geometry and target Android device IDs remain local mapping data. No wire payload or media payload is copied by RFC-004E.

RFC-004G inserts one local metadata stage after the existing native receiver bridge:

```text
UDP
        -> native input parser
        -> persistent scalar bridge
        -> portable input envelope
        -> InputStateConvergenceController
        -> AndroidTargetInputMapper
        -> RFC-004D Binder / UserService
```

The convergence stage owns only sequence numbers, source timestamps, bounded semantic state, and
portable models. It crosses no new wire or JNI boundary and copies no input payload. The privileged
capability query may probe `/dev/uhid` cold, but it does not create a HID device or add a new Binder
method.

## RFC-005A Session Core

RFC-005A adds a Kotlin-only, platform-neutral session model:

~~~text
DeviceId / PeerReference / SessionId
        -> bounded SessionManager
        -> session channels, paths, and logical peripherals
~~~

It adds no JNI entry point, native structure, packet field, payload copy, or Binder method.
Existing video, audio, and reverse-input JNI boundaries remain unchanged. Discovery, handshake,
packet security, endpoint negotiation, and session composition are later Phase 5 work.

## RFC-005B Local Discovery

RFC-005B is Android/Kotlin control-plane work only:

```text
NsdManager / WifiP2pManager
        -> platform discovery backends
        -> platform-neutral presence controller/cache
```

It adds no JNI entry point, native SCL structure, PacketHeader field, payload field, packet copy, or
Binder method. LAN DNS-SD and Wi-Fi Direct DNS-SD metadata is not an SCL wire protocol.

## Error Handling

Future native errors should cross the JNI boundary as explicit status values or structured results. Exceptions must not become the primary hot-path error mechanism.

## ABI Stability

Native bridge ABI changes require an explicit bridge ABI version change and documentation. Protocol layout changes require an explicit protocol version change.
