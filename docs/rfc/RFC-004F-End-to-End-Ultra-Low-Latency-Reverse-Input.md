# RFC-004F - End-to-End Ultra-Low-Latency Reverse Input

Status: complete implementation integration. Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1, Input Payload Version 1, and Privileged Input Injection Service Version 1 remain unchanged.

## Composition

The source session is caller-driven and contains no sender worker:

```text
WarpnectInputCaptureView
  -> InputCaptureController
  -> RemoteVideoViewportInputMapper
  -> SclInputEventSink
  -> InputTransportController
  -> native InputTransportSender
  -> UDP
```

`ReverseInputSenderSessionController` starts transport preparation and running state before it constructs the viewport mapper or permits capture to run. It stops capture first so RFC-004B's `ResetState(AllDevices, SessionStop)` has one normal transport send attempt, then stops transport and clears mapper state. Start failures roll back the already prepared capture, mapper, and transport stages. The production session configuration requires a nonzero local source port because the target uses exact source IP/UDP-port filtering.

The target uses one persistent `WarpnectInputReceiver` context:

```text
UDP -> InputReceiverRuntime -> InputDatagramParser -> direct bridge
    -> portable Kotlin Input V1 model -> AndroidTargetInputMapper
    -> InputInjectionController -> Shizuku/Sui UserService -> InputManager
```

Injection is prepared and started before the native socket opens. The receiver thread blocks in native UDP readiness waiting, decodes one already-parsed bridge record, maps it synchronously, and invokes the existing synchronous Binder injection path. There is no UI receive, mapping, injection, sender, reorder, retry, or per-event coroutine worker.

## Receiver Runtime

`InputReceiverRuntime` owns a non-blocking `UdpSocket`, an explicit numeric bind endpoint, an explicit numeric expected sender endpoint, one fixed 418-byte receive scratch, `InputDatagramParser`, and counters. It accepts only the exact configured remote IP and UDP port before parsing. Unexpected endpoints, malformed datagrams, unsupported/fragmented input, and oversize datagrams are dropped and counted without ending the session. Unusable socket failures are surfaced to the receiver session.

Input is one datagram per logical observation: PayloadType `Input`, payload at most 396 bytes, datagram at most 417 bytes, `slice_index = 0`, and `total_slices = 1`. RFC-004F introduces no input reassembly.

The persistent Kotlin-owned direct bridge is 1,024 bytes. Its largest possible scalar record is 960 bytes: a 64-byte fixed prefix plus 32 contacts at 28 native-order bytes each. It is a private native-order record, not Input Payload V1. Native remains the only PacketHeader/Input V1 parser; Kotlin reads absolute scalar offsets and creates the existing portable models. No target Input Payload `ByteArray` is created and no `NewDirectByteBuffer` occurs per event.

## Ordering, Time, And Reset

The policy is `ArrivalOrderBestEffort`. Network reorder wait is zero. Every valid arrival is delivered in arrival order; sequence gaps, duplicates, and older sequence numbers are counters only. They do not cause waiting, reordering, drops, retransmission, duplication, NACK, or FEC.

`PacketHeader.timestamp_us` is preserved through the native event, bridge record, portable model, target mapper, and RFC-004D `sourceEventTimeUs`. It remains source-device monotonic metadata and is never substituted for a target Android event time or used to claim cross-device one-way latency.

Source stop attempts RFC-004B's normal `ResetState(AllDevices, SessionStop)` while transport remains running and exposes whether the attempt was sent or failed. Network `ResetState` flows through target mapping. Target stop interrupts and joins receive before one bounded local all-slot reset, then stops injection and releases the receiver runtime. Emergency local reset uses one atomic request plus native wakeup and is consumed on `WarpnectInputReceiver`; it is not a generic command queue.

## Boundedness And Security

Fixed or bounded state consists of the source device registry, viewport mapper slots, sender scratch, kernel socket buffers, native receive scratch, one bridge, target mapper slots, injection service slots/keys/pointers, and counters. There is no application input queue, retry backlog, reorder queue, event history, input fragmentation store, or intentional input jitter buffer.

Endpoint filtering is a pre-session safety boundary, not authentication. Reverse input is not suitable for hostile or untrusted networks until authenticated and encrypted session establishment exists. Packet loss and reordering can still lose or reorder critical transitions; RFC-004G owns reliability, state repair, and latency tuning.

## Tests And Device Status

Host-native coverage verifies fixed-port UDP loopback, Input V1 parser reuse, exact key/timestamp/sequence bridge values, strict endpoint rejection before parsing, malformed and oversize drops, interrupt handling, and gap/same/out-of-order diagnostics. JVM coverage verifies persistent direct bridge reuse and target lifecycle ordering, including serialized emergency and final reset.

Android network loopback, capture-to-network-to-target mapping, privileged injection, physical keyboard/touch/mouse/gamepad, and two-device LAN validation are device-dependent and remain not run when no connected device or emulator is available. No commercial-game compatibility claim is made.

## Deferred Work

Only RFC-004G - Input Latency, Reliability and Performance Tuning remains for Phase 4.
