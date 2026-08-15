# RFC-005E - Session Keys, Packet Authentication & Anti-Replay

Status: Complete. Architecture Version remains 1.0, SCL Protocol Version remains 1, and Native Bridge ABI Version remains 1.

## Scope

RFC-005E adds Session Packet Protection Version 1: a portable C++20 WNSD envelope around a complete, pre-existing SCL datagram. It consumes RFC-005D's `AuthenticatedSessionRootSecret` once, derives memory-only session protection material, and provides bounded generic protection contexts for `SessionControl` and future `Channel(ChannelId)` scopes.

It does not alter `PacketHeader`, `PayloadType`, Video/Audio/Input Payload V1, NACK, FEC, ClockSync, or VideoResyncRequest. It does not attach protection to an active media, audio, input, or control transport. RFC-005G owns negotiated channel attachment and budget propagation to those packetizers.

## WNSD Envelope V1

Every protected wire datagram is exactly:

```text
[28-byte clear authenticated WNSD header][ciphertext inner SCL datagram][16-byte GCM tag]
```

The overhead is fixed at 44 bytes. The header is big-endian and has this exact layout:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII `WNSD` |
| 4 | 1 | protection version = 1 |
| 5 | 1 | flags = 0 |
| 6 | 2 | reserved = 0 |
| 8 | 8 | opaque `ProtectionContextId` |
| 16 | 4 | key epoch |
| 20 | 8 | security packet number |

All non-zero flags/reserved bytes, unsupported versions, zero/unknown context IDs, oversized records, and records shorter than `28 + 21 + 16` are rejected before inner SCL parsing. The exact 28-byte header is AES-GCM AAD. Packet timing, outer source/destination, protected size, context ID, epoch, and security packet number remain visible; V1 adds no padding.

## Key Schedule

The only suite is AES-128-GCM, SHA-256, HKDF-SHA256, and a 128-bit GCM tag. The native runtime derives the 32-byte `SessionProtectionMasterSecret` with:

```text
HKDF-Expand(AuthenticatedSessionRootSecret,
  "Warpnect Session Protection Master v1" || SessionId || generation-u32-BE || transcriptHash,
  32)
```

For scope bytes `scope_type-u8 || scope_id-u32-BE`, it derives a context secret with `Warpnect Protection Context v1`, then independent canonical ClientToHost and HostToClient secrets. Each direction derives an opaque 64-bit context ID, epoch zero secret, AES key, and 12-byte base IV using these frozen labels:

```text
Warpnect Protection ClientToHost v1
Warpnect Protection HostToClient v1
Warpnect Protection Context Id v1
Warpnect Protection Epoch 0 v1
Warpnect Protection Key v1
Warpnect Protection IV v1
Warpnect Protection Key Update v1
```

The root is copied only on the cold JNI initialization path, destroyed by Kotlin whether native initialization succeeds or fails, and is never persisted. The native runtime retains only derived memory-only material. Closing it wipes owned master, directional, epoch, key, IV, and replay state with Mbed TLS secure zeroization where the C++ runtime owns the bytes. JVM/JCA internals remain subject to normal runtime zeroization limits.

## Packet Numbers, Replay, and Epochs

Security packet numbers are independent unsigned 64-bit values. Each direction/epoch begins at zero. The sender reserves the number before AES-GCM, never rolls it back after a send failure, and exact protected retransmissions reuse the cached datagram rather than encrypting again.

The nonce is `baseIV XOR (four zero bytes || packet-number-u64-BE)`. This permits no key/nonce reuse. Receive replay state is a fixed circular bitmap per directional context and epoch. The default window is 4096 packet numbers (512 bytes); valid configured values are 64 through 16384. Duplicate and too-old records are rejected before AEAD, but replay state advances only after a successful GCM authentication. Valid out-of-order records inside the window are delivered immediately without a queue.

The default send epoch limit is `2^20` packets. A sender rotates before the next record would reuse an epoch packet number. Receivers retain only the current and a previous epoch, with a default two-second monotonic overlap. A candidate `current + 1` epoch is installed only after a valid AEAD record; farther-future epochs, skipped epochs, and epoch wrap are rejected.

## Datagram Budget and Existing SCL Semantics

`maxInnerSclDatagram = configuredUdpPayloadBudget - 44`; the configured wire budget is never increased. A budget must be greater than `44 + 21`. The native API exposes this reduced inner budget so RFC-005G can configure existing fragmenters correctly.

When channel attachment is introduced, FEC generation remains before WNSD protection and FEC recovery remains after successful WNSD authentication. A reconstructed SCL packet derives its trust from authenticated data/parity shards and is not nested in another WNSD envelope. NACK is an ordinary inner SCL control datagram and is protected like any other final SCL datagram. A record successfully authenticated at the security layer remains replayed even if a later local SCL layer drops it.

## Runtime, Bounds, and Threading

The portable `warpnect::scl::security::SessionProtectionRuntime` has a fixed 64-context registry. It supports `SessionControl` immediately and generic `Channel(ChannelId)` derivation, but creates no channel, socket, media pipeline, or queue. Each runtime uses bounded replay storage and has no per-datagram heap allocation. Endpoint filtering is checked before AEAD; it is a current-path filter, not a peer identity or migration mechanism.

Cold JNI can create/destroy a runtime, create/destroy contexts, and read a summary snapshot. It does not protect or unprotect packets. `SessionProtectionController` bounds runtimes to the Phase 5 hard session limit of eight and creates `SessionControl` while retaining a successful RFC-005D admission reservation for RFC-005F. Initialization failure releases that reservation. The hot path is native and caller-serialized; no media, audio, input, UI, coroutine, packet worker, or JNI hop was added.

Mbed TLS 3.6.7 is vendored with a verified upstream SHA-256 and Apache-2.0 license selection. Warpnect links only `mbedcrypto`, not Mbed TLS/DTLS/X.509 targets. Android bypasses its optional host pthread discovery probe because no Mbed TLS threading feature is enabled.

## Security Boundary

WNSD authenticates and encrypts an inner SCL datagram only when a future negotiated channel routes through this runtime. RFC-005E does not create a live channel, derive per-media policies, provide congestion control, negotiate algorithms, enable packet resumption, or establish path migration. It neither replaces RFC-004G semantic input freshness nor authenticates a future endpoint without the completed RFC-005D handshake.

## Verification

Host-native tests cover the exact 28-byte header vector, 44-byte overhead, bidirectional context agreement, AES-GCM/AAD tampering, replay non-advancement on failed authentication, out-of-order acceptance, endpoint filtering, previous-epoch overlap, generic channel context isolation, and one-time root initialization. JVM tests cover root consumption, admission-release-on-failure, and bounded controller ownership. `scl_session_protection_benchmarks` samples native protect-and-unprotect latency for 64-byte and 512-byte payloads without claiming streaming latency. Android runtime instrumentation was not run because no device was attached; Android ABI compilation was run successfully.

Deferred work is limited to RFC-005F through RFC-005I.
