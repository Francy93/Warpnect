# Native Boundary

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

## Error Handling

Future native errors should cross the JNI boundary as explicit status values or structured results. Exceptions must not become the primary hot-path error mechanism.

## ABI Stability

Native bridge ABI changes require an explicit bridge ABI version change and documentation. Protocol layout changes require an explicit protocol version change.
