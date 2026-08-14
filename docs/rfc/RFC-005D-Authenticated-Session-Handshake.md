# RFC-005D - Authenticated Session Handshake

## Status and Boundary

Implemented as Session Handshake Protocol Version 1. `WNSH` is Kotlin/JCA pre-session control
traffic, not SCL: it changes no PacketHeader, PayloadType, media/input payload, ClockSync, NACK,
FEC, JNI, or Native Bridge ABI contract. Pairing trust identifies a previously user-verified
public key; this RFC proves a peer at a current endpoint controls that key in a fresh exchange.
It creates no Running session, channel, media stream, or SCL traffic protection.

The current runtime transport is UDP over a usable LAN discovery route. Wi-Fi Direct DNS-SD is
still discovery only: this RFC does not call `connect()` or create a P2P group.

## WNSH V1

Every datagram is at most 1200 bytes, without fragmentation. Its exact 32-byte big-endian header
is `WNSH` (4), version (1), type (1), flags (2), non-zero attempt ID (16), sequence (2), payload
length (2), and reserved zero (4). Flags are `HasRetryCookie` bit 0 and `EncryptedPayload` bit 1.
The fixed logical order is ClientHello 0, HelloRetry 1, cookie ClientHello 2, ServerHello 3,
ServerAuth 4, ClientAuth 5, and ServerComplete 6. Unknown flags/types, reserved data, bad
lengths, trailing data, illegal flag/type combinations, and oversize packets are rejected.

Plaintext ClientHello includes suite 1, fresh SessionId, SessionGeneration 1, Client/Host roles,
optional 16-byte DiscoveryPresenceId binding, nonce, P-256 SPKI key share, and retry cookie.
ServerHello accepts the same session/role values with a fresh Host nonce and key share. Neither
message contains DeviceId or fingerprint. The all-zero discovery binding explicitly means no
discovery binding and remains a route hint rather than identity.

## Cookie, Authentication, and Keys

The responder initially returns a short stateless HelloRetry. Its opaque 40-byte cookie is
`issuedAtMonotonicMs || HMAC-SHA256`; a random current plus previous 32-byte key rotates every 10
minutes and validates for 30 seconds. Its MAC binds raw IPv4/IPv6 address bytes, port, attempt ID,
initial ClientHello payload hash, SessionId, generation, discovery binding, and issue time. The
cookie is return-routability/DoS control, not authentication. No responder ECDH state or trust
lookup happens before validation.

V1 fixes one suite: ECDH P-256, ECDSA P-256/SHA-256, SHA-256, HKDF-SHA256, AES-128-GCM, and
HMAC-SHA256. Every attempt has fresh attempt ID, nonces, SessionId, and ECDH key pairs. The
canonical transcript includes each logical WNSH header plus payload once, including retry, but
never duplicate retransmissions. `HKDF-Extract(earlyTranscriptHash, dhSecret)` derives separate
client/server handshake key and IV, client/server Finished, and ServerComplete keys. AES-GCM uses
the exact WNSH header as AAD and an IV XORed with the 96-bit message sequence. Retries resend the
identical cached ciphertext, never re-encrypt it.

ServerAuth, ClientAuth, and ServerComplete are encrypted. ServerAuth proves the exact stored
RFC-005C Host key with a role/session/transcript-bound ECDSA signature and Finished MAC. The Client
requires stored DeviceId/fingerprint match, signature, and Finished before emitting ClientAuth, so
an unauthenticated endpoint cannot solicit the Client stable identity. ClientAuth repeats this
process in the other direction. ServerComplete confirms the full authenticated transcript.

`AuthenticatedSessionRootSecret` is a fresh 32-byte expansion labelled `Warpnect Authenticated
Session Root v1 || authenticatedTranscriptHash`. It is redacted, memory-only, explicitly
destroyable, and zeroes owned storage. It is reserved for RFC-005E; it is not a traffic key.

## Admission, Router, and Limits

The advertised RFC-005B socket has one `AndroidBootstrapDatagramRouter` reader. It dispatches
only `WNPB` to RFC-005C pairing and `WNSH` to handshake; unknown magic drops. Pairing bytes remain
unchanged and the two protocols cannot race on the contact socket.

After trusted ClientAuth only, SessionManager atomically reserves Host capacity and same-peer
policy for 30 seconds. A reservation creates no Session or media work; later negotiation can
consume or release it. Limits are 8 hard/4 default incoming attempts, 5 retries at
100/250/500/1000/2000 ms, 8-second attempt timeout, 64 recent completions retained 30 seconds,
two cookie keys, and the existing 8-session SessionManager bound. Android serializes callbacks,
crypto, timers, and admission on `WarpnectSessionHandshake`, never a media/input context.

## Tests and Non-Goals

JVM tests cover strict framing, AEAD AAD/nonce behavior, cookie binding/expiry, a full two-party
JCA P-256 exchange, Server-first disclosure, root destruction, and capacity reservations. Android
Keystore, AES-GCM, UDP loopback, two-device LAN, and Direct path checks require hardware and are
reported only when actually run.

There is no SCL encryption/MAC, packet anti-replay, traffic-key rotation, resumption, 0-RTT,
capability or channel negotiation, path migration, reconnect, automatic pairing, or Direct P2P
connection. Discovery metadata remains spoofable until encrypted trusted authentication succeeds.
