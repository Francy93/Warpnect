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

## RFC-005C Pairing and Trust

RFC-005C is Kotlin control-plane work only:

```text
platform-neutral PairingEngine
        + JCA crypto abstraction
        + Android Keystore identity-key adapter
        + cold-path UDP pairing transport
```

It adds no JNI entry point, native SCL structure, PacketHeader field, payload field, or Binder
method. The pairing protocol is a separate pre-session datagram format; it never enters media,
audio, or reverse-input hot paths.

## RFC-005D Authenticated Session Handshake

RFC-005D remains Android/Kotlin control-plane work:

```text
bootstrap UDP/router -> SessionHandshakeTransport -> SessionHandshakeEngine -> JCA/Android Keystore
```

It adds no PacketHeader field, payload field, or media/input hot-path work. The resulting root
secret remains in Kotlin memory until RFC-005E performs its one-time cold-path transfer into native
packet-protection state.

## RFC-005E Session Packet Protection

RFC-005E moves only per-datagram protection into the portable C++20 core:

```text
AuthenticatedSessionRootSecret (cold Kotlin ownership transfer)
        -> cold JNI runtime/context creation
        -> native SessionProtectionRuntime / mbedcrypto
        -> future negotiated SCL transport attachment
```

JNI creates, destroys, configures, and snapshots native protection contexts only. It never encrypts
or decrypts a packet. AES-GCM, HKDF, epoch/replay state, and WNSD parsing stay in the native hot
path. Mbed TLS 3.6.7 contributes only `mbedcrypto`; TLS, DTLS, X.509, media, input, and Binder
paths remain outside this boundary.

## RFC-005F Secure SessionControl

RFC-005F adds one cold control-message bridge, not media integration:

```text
Kotlin WNCP model and codec
        -> existing PacketHeader V1 / PayloadType.SessionControl framing
        -> native RFC-005E WNSD protect or unprotect
        -> UDP
```

The JNI bridge accepts at most one 1024-byte WNCP payload per control submission, creates no
media-side Kotlin crypto path, and returns only authenticated SessionControl payloads. Native
WNSD continues to own AES-GCM, anti-replay, epoch, context-ID, and endpoint filtering. The bridge
does not modify PacketHeader, PayloadType, packetizers, NACK, FEC, or media/input payloads.

## RFC-005G Prepared Secure Channels

RFC-005G performs one cold Kotlin/JNI preparation transition per selected channel. JNI adopts the
already-bound UDP endpoint into the existing native Video, Audio, Input, or generic Telemetry
transport and attaches an opaque RFC-005E `Channel(ChannelId)` protector. No traffic key is copied
into the Kotlin prepared model.

```text
Video: MediaCodec callback -> existing JNI -> SCL packetize -> FEC -> WNSD protect -> UDP
Audio: native Opus/SCL -> WNSD protect -> UDP
Input: capture -> existing JNI -> SCL Input -> WNSD protect -> UDP
Receive: UDP -> WNSD authenticate/anti-replay -> SCL/FEC/reassembly -> subsystem delivery
```

Video NACK control is authenticated before retransmission and the retransmission cache retains the
exact protected wire datagram. Android Wi-Fi Direct and path-local socket selection remain in
platform adapters; portable SCL contains no `WifiP2pManager`, Android `Network`, or process-binding
API. Prepared transports own no media worker until later startup orchestration.

## RFC-005H Lifecycle Rebind and Reconnect

Same-generation migration is a cold control-plane operation:

```text
Lifecycle controller -> allocate target path socket -> protected WNSL validation
        -> native endpoint rebind -> unchanged Channel protection context -> UDP
```

The additive JNI surface rebinds a SessionControl or `Channel(ChannelId)` expected endpoint and
reads a monotonic `lastAuthenticatedReceive` diagnostic. It does not copy keys, reset security
packet numbers, create a per-packet JNI call, or perform media work. A reconnect destroys the old
runtime and builds a new one only after fresh WNSH ECDH, then repeats WNCP and WNSN preparation.

## RFC-005I Running Channel Integration

`AndroidSessionPipelineFactory` consumes RFC-005G stopped endpoint leases rather than reopening
guessed ports. Its final paths are `Surface capture -> MediaCodec -> SCL/FEC -> Channel protection
-> UDP` for Host Video, the inverse for Client Video, `Opus/SCL -> Channel protection -> UDP` for
Audio, and `Input V1 -> native transport -> Channel protection -> UDP -> convergence/mapping/
injection` for Input. Receive authenticates WNSD before FEC, reassembly, or input convergence.

Pipeline composition adds no per-packet JNI or Kotlin crypto. RFC-005H rebinds the adopted native
transport in place with its pre-bound replacement socket; it does not recreate the Channel context,
keys, packet-number space, or replay state. Android Surface and per-socket network binding remain
platform-owned; portable SCL remains free of Android network APIs.

## Error Handling

Future native errors should cross the JNI boundary as explicit status values or structured results. Exceptions must not become the primary hot-path error mechanism.

## ABI Stability

Native bridge ABI changes require an explicit bridge ABI version change and documentation. Protocol layout changes require an explicit protocol version change.

## RFC-006A Native Runtime Telemetry

```text
native runtime producer thread
        -> direct relaxed atomic metric update
native RuntimeTelemetrySource
        -> RuntimeTelemetryRegistry
        -> one WNTM direct-buffer snapshot JNI call
Kotlin NativeTelemetrySnapshotProvider
        -> immutable TelemetrySnapshot
```

`WNTM` is a bounded, little-endian, in-process JNI representation only. It is never sent over UDP
and does not use `PayloadType.Telemetry`. Counter, gauge, and histogram updates stay in native code;
no packet, frame, audio, or input update performs JNI. The direct snapshot buffer is caller-owned
and reused up to 256 KiB, while Kotlin may make bounded immutable copies only on the cold snapshot
path.

## RFC-006B Media and Input Instrumentation

MediaCodec and Android input callbacks update pre-bound Kotlin telemetry primitives directly. Native
Opus and Oboe playback update native primitives. Oboe's realtime callback records only callback,
requested/delivered PCM-frame, underrun, and ring-fill atomics; the values cross to Kotlin solely in
the existing WNTM batch during an explicit snapshot. No media/input telemetry update performs JNI,
payload copying, or a registry lookup.
