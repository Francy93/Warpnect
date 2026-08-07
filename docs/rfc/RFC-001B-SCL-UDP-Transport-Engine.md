# RFC-001B - SCL UDP Transport Engine

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

## Problem

RFC-001A created a safe packet codec, but SCL still lacked a real transport primitive for moving opaque datagrams between endpoints.

The repository needed a UDP layer that preserves native datagram semantics without introducing packet parsing, reliability, fragmentation, queues, threads, or Android/JNI coupling.

## Decision

Implement a portable C++20 UDP transport API in `warpnect::scl`.

The public API uses:

- `IpVersion` for IPv4 and IPv6 selection.
- `IpAddress` as a fixed-size address value.
- `UdpEndpoint` as address plus UDP port.
- `UdpStatus` and transport-specific result values.
- `UdpSocket` as a move-only RAII socket abstraction.

OS socket details are private implementation details behind `src/internal/socket_platform.h`.

## Transport Architecture

Layering is:

```text
SCL higher-level components
        |
        v
UDP transport API
        |
        v
internal socket adapter
        |
   POSIX/Android or Winsock
```

Public headers expose no `sockaddr`, POSIX socket descriptors, Winsock `SOCKET`, Windows headers, Android framework types, JNI types, or Kotlin concepts.

## Endpoint Model

`IpAddress` stores an explicit `IpVersion` and 16 bytes. IPv4 uses the first four bytes and leaves the remainder zeroed.

`UdpEndpoint` stores an `IpAddress` and a host-order `std::uint16_t` port.

Port `0` is valid for bind and requests an OS-assigned ephemeral port. Port `0` is invalid for outbound `send_to()`.

Unspecified addresses are valid for bind and invalid as outbound destinations.

Numeric address parsing supports IPv4 and IPv6 text forms without DNS or hostname resolution.

## Socket Lifecycle

`UdpSocket` is RAII-managed, non-copyable, and movable.

Lifecycle states are closed, open, and bound. Moved-from sockets behave as closed sockets.

`open()` configures a non-blocking UDP socket. IPv6 sockets are configured as IPv6-only so IPv4 and IPv6 behavior is deterministic.

`close()` is safe on an already closed socket.

## Send And Receive Semantics

One successful `send_to()` call sends exactly one UDP datagram.

One successful `receive_from()` call receives exactly one UDP datagram.

The transport does not combine, split, retry, queue, parse, mutate, or validate SCL packet bytes.

`receive_from()` writes into caller-owned memory and reports the source endpoint.

An empty receive queue returns `WouldBlock`.

Zero-length UDP datagrams are allowed by the transport. The SCL packet layer remains responsible for rejecting buffers smaller than the 21-byte SCL header.

## Datagram Truncation

Datagram truncation is reported as `DatagramTruncated`.

Truncated bytes are not reported as a successful complete datagram and must not be passed upward as a valid SCL packet.

POSIX/Android uses `recvmsg` with `MSG_TRUNC`.

Windows uses Winsock `recvfrom` and maps `WSAEMSGSIZE` to `DatagramTruncated`.

## Error Model

`UdpError` values are:

- `None`
- `NotOpen`
- `AlreadyOpen`
- `NotBound`
- `AlreadyBound`
- `InvalidAddress`
- `InvalidPort`
- `AddressFamilyMismatch`
- `AddressFamilyUnsupported`
- `SocketCreationFailed`
- `SocketOptionConfigurationFailed`
- `NonBlockingConfigurationFailed`
- `BindFailed`
- `WouldBlock`
- `DatagramTooLarge`
- `DatagramTruncated`
- `SendFailed`
- `ReceiveFailed`
- `LocalEndpointQueryFailed`

Each status may preserve a native OS error code for diagnostics. Callers branch on `UdpError`.

## Allocation And Threading

`send_to()`, `receive_from()`, and runtime endpoint conversion allocate no heap memory.

The transport creates no threads, queues, callbacks, event loops, locks, or background workers.

Concurrent calls on the same transport object require external coordination.

## Platform Adapters

The POSIX/Android adapter uses native UDP socket APIs internally, including `socket`, `bind`, `sendto`, `recvmsg`, `getsockname`, `close`, `fcntl`, and `inet_pton`.

The Windows host adapter uses Winsock internally, including `WSAStartup`, `WSACleanup`, `socket`, `bind`, `sendto`, `recvfrom`, `getsockname`, `ioctlsocket`, `InetPtonA`, and `closesocket`.

Winsock startup is managed internally and is not exposed to callers.

## Compatibility

RFC-001B does not change the SCL packet wire format.

Architecture Version remains 1.0.

Protocol Version remains 1.

Native Bridge ABI Version remains 1.

No JNI methods are added.

No Kotlin networking logic is added.

## Test Coverage

Native tests cover:

- Public header compilation.
- Socket lifecycle and RAII move behavior.
- Non-blocking `WouldBlock`.
- IPv4 loopback datagrams.
- IPv6 loopback when available.
- Binary-safe payloads.
- Datagram boundary preservation.
- Zero-length datagrams.
- Truncation detection.
- Structural oversize rejection.
- Endpoint validation.
- Ephemeral port lookup.
- WouldBlock stress sanity.
- Representative datagram sizes.
- Packet-over-UDP integration using the RFC-001A packet codec.

## Deferred Work

Deferred to later RFCs:

- Fragmentation and reassembly.
- Loss detection, NACK, and recovery.
- Reed-Solomon FEC.
- Clock synchronization and network telemetry.
- Phase 1 integration and benchmarks.
