# RFC-004C - Reverse SCL Input Transport

## Scope

RFC-004C connects RFC-004B's synchronous `InputEventSink` to the native SCL sender. It does not add target-side receiver orchestration, privileged Android injection, target device mapping, input reliability policy, or end-to-end input sessions.

## Data Path

```text
Android KeyEvent / MotionEvent
        -> RFC-004B portable input model
        -> SclInputEventSink
        -> NativeBridge
        -> InputTransportSender
        -> PacketHeader + Input Payload V1
        -> one non-blocking UDP datagram
```

`InputTransportController` is deliberately separate from `InputCaptureController`. RFC-004F will compose their lifecycles; RFC-004C does not auto-wire or auto-start them.

## Datagram Contract

Input Payload V1 is unchanged. The largest valid logical payload is a 32-contact TouchFrame: 12-byte prefix plus 32 12-byte contact entries, or 396 bytes. The frozen PacketHeader is 21 bytes, so the transport maximum is 417 bytes.

Every input message has `PayloadType::Input`, `slice_index = 0`, and `total_slices = 1`. A configured wire budget below 417 bytes is rejected. Input has no fragmentation, reassembly storage, or receiver runtime in this RFC.

## Timing And Sequence

`PacketHeader.timestamp_us` is exactly RFC-004B's source-local monotonic `eventTimeUs`. It is not JNI-call, socket-send, or target time.

One unsigned 32-bit sequence domain spans keyboard, touch, pointer, scroll, gamepad, reset, and all logical device slots for a sender lifecycle. The sequence advances once a valid message reaches its send attempt, even when that attempt returns `WouldBlock` or another send failure. This exposes transport gaps rather than disguising local drops. Normal unsigned wrap from `UINT32_MAX` to zero is preserved.

## JNI Boundary

All external declarations reside in `NativeBridge.kt`. Fixed input events cross as compact primitive fields. Touch uses one reusable 896-byte direct native-order scratch buffer per `NativeSclInputTransportController`; each of its 32 possible records contains seven `Int` scalar fields. The native sender validates the reconstructed portable event and serializes the actual RFC-004A big-endian payload directly into its fixed datagram buffer.

There is no Kotlin Input Payload `ByteArray`, Kotlin binary serializer, per-contact JNI call, or separate complete native payload staging buffer.

## Delivery Policy

FreshState, CriticalTransition, and Reset remain local RFC-004A classifications. RFC-004C uses them for counters only. Every class follows BestEffortImmediate: one send attempt, immediate result, no queue.

`WouldBlock`, UDP failure, and partial send are visible in the snapshot. Fresh-state drops, critical-transition drops, and reset-send failures are counted separately. RFC-004C does not add NACK, FEC, retransmission cache, duplication, ACK, retry, coalescing, pacing, batching, or a timer flush. Their latency and state-repair trade-offs are reserved for RFC-004G.

`InputTransportSnapshot` exposes the configured remote endpoint and the actual local bind endpoint when the OS assigns an ephemeral port, alongside the shared sequence, per-type and per-delivery-class counters, latest source timestamp/sequence values, and last error. It retains counters and latest values only; it never keeps an event history.

## Receive Validation

`InputDatagramParser` is a portable native helper used by host loopback tests. It validates the frozen PacketHeader, protocol version, `PayloadType::Input`, the one-slice contract, 396-byte payload limit, and strict RFC-004A message parser. It exposes source sequence number, source timestamp, common input header, and a non-owning payload span. It does not reorder, deduplicate, wait, or deliver to Kotlin.

## Threading And Boundedness

The normal send path stays on the caller that emitted the capture event. It adds no sender thread, receiver thread, coroutine, handler hop, timer, queue, or blocking mutex. The native sender owns one fixed 417-byte datagram scratch; the Kotlin controller owns one fixed 896-byte direct touch scratch. No input transport storage grows with event rate or session duration.

## Tests And Device Status

Host-native tests cover all RFC-004A message types, maximum TouchFrame datagrams, budget validation, strict parser rejection, sequence progression/wrap on forced send failure, per-delivery-class telemetry, and UDP localhost loopback. JVM tests cover the synchronous sink forwarding contract, error mapping, transport configuration, and touch scratch reuse/layout. Android instrumentation covers native fixed-event/touch submission plus synthetic capture-to-sink composition.

Instrumentation requires a connected device or emulator. Device-specific results are reported only when such a runtime is available.

## Deferred Work

- RFC-004D - Android Privileged Input Injection
- RFC-004E - Input Mapping, Coordinate and Device Semantics
- RFC-004F - End-to-End Reverse Input
- RFC-004G - Input Latency, Reliability and Performance Tuning
