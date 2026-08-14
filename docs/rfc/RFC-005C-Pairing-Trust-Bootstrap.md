# RFC-005C - Pairing & Trust Bootstrap

Status: Phase 5 security foundation.

RFC-005C adds persistent device identity and explicit human-verified pairing. It is a pre-session
control protocol: it neither creates a `Session` nor authenticates a future endpoint, derives
media keys, adds packet authentication, or changes an SCL/media/input wire format.

## Security Boundary

Discovery, pairing, trust, live-session authentication, and session-key establishment remain
separate. RFC-005B's `DiscoveryPresenceId`, DNS-SD service name, TXT metadata, IP address, and
Wi-Fi Direct locator are untrusted route hints. They never become persistent peer identity.

After pairing, the only durable security binding is:

```text
peer DeviceId -> exact P-256 identity public key + SHA-256 fingerprint
```

The store has no private key, ephemeral ECDH secret, HKDF PRK, confirmation key, SAS material, or
persistent symmetric pairing secret. RFC-005D must authenticate a fresh live endpoint again.

## Local Identity and Trust Store

`LocalDeviceIdentityRepository` owns one non-zero opaque 128-bit `DeviceId` and the Android
Keystore alias `warpnect.device.identity.v1`. First initialization generates both together:

- `DeviceId` uses `SecureRandom` and is atomically stored in app-private no-backup storage.
- The long-term key is Android Keystore EC P-256 (`secp256r1`) with
  `PURPOSE_SIGN | PURPOSE_VERIFY` and `SHA-256` authorization.
- The private key is used only for `SHA256withECDSA` and is never exported.
- The public key is canonical X.509 SubjectPublicKeyInfo DER, bounded to 256 bytes.
- `IdentityFingerprint` is exactly `SHA-256(SPKI)`, 32 bytes.

StrongBox is not requested, attestation is not implemented, and per-signature biometric or
lock-screen authentication is not required. `KeyInfo` is used only to report `Software`,
`TrustedEnvironment`, `StrongBox`, or `Unknown` security level.

If only a DeviceId or only a Keystore key remains, the result is `LocalIdentityInconsistent`; the
repository never silently manufactures the missing half. The explicit destructive
`resetLocalIdentityAndTrust()` clears trusted peers, deletes the key, clears the DeviceId, and
creates a fresh identity. Existing peers must pair again.

`TrustedPeerStore` uses local schema version 1 and a 128-record maximum. Android persistence uses
one app-private `AtomicFile` replacement. Each record holds peer DeviceId, algorithm, public SPKI,
fingerprint, pairing/verification wall-clock metadata, and an optional bounded presentation alias.
It contains no private or symmetric secret. Duplicate bindings, a changed key for a known DeviceId,
and a known key bound to a different DeviceId all fail closed. Explicit forget removes one binding.

## Pairing Bootstrap Protocol V1

Pairing Bootstrap V1 is standalone UDP, not SCL. Every packet is below 1200 bytes and has this exact
28-byte big-endian header:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII `WNPB` |
| 4 | 1 | version = 1 |
| 5 | 1 | message type |
| 6 | 2 | flags = 0 |
| 8 | 16 | non-zero PairingAttemptId |
| 24 | 2 | payload length |
| 26 | 2 | reserved = 0 |

The parser rejects malformed magic/version/type, non-zero flags/reserved bytes, zero attempt IDs,
length mismatch, truncation, oversize fields, and trailing data. There is no fragmentation or
reassembly. V1 message types are `Commit(1)`, `Response(2)`, `Reveal(3)`, `Confirm(4)`,
`Reject(5)`, and `Abort(6)`. Public keys are at most 256 bytes, DER ECDSA signatures at most 128
bytes, and nonces, hashes, and confirmation MACs are exactly 32 bytes.

## Cryptographic Flow

The sole V1 suite is ECDSA P-256/SHA-256 identity signatures, ECDH P-256, SHA-256 transcript hash,
HKDF-SHA256, and HMAC-SHA256 confirmation. JCA and Android Keystore implement all crypto; Warpnect
contains no custom ECC, ECDSA, ECDH, SHA-256, or HMAC implementation. Peer SPKI must parse as EC
P-256 before it is accepted.

For each fresh attempt, both sides create a fresh P-256 ephemeral key pair and fresh 32-byte nonce.
Before the responder reveals its ephemeral key, the initiator sends:

```text
commitHash = SHA-256(
  "Warpnect Pairing Commit v1" || canonical(InitiatorRevealMaterialV1)
)
```

The committed material contains attempt ID, initiator DeviceId, suite, long-term public key,
ephemeral public key, and nonce. `Response` contains the responder identity/ephemeral material,
commit hash, and a long-term signature. `Reveal` carries the committed initiator material and a
signature binding the response hash. Both signatures are verified before SAS presentation.

The canonical transcript is domain-separated and length framed:

```text
"Warpnect Pairing Transcript v1"
|| PairingAttemptId
|| len(canonical Commit)
|| len(canonical Response)
|| len(canonical Reveal)
```

The ECDH secret is extracted with transcript hash salt. Separate HKDF labels derive initiator
confirm key, responder confirm key, and SAS material. No pairing-derived key becomes a future
session, media, or input key. The SAS is a six-digit `000000..999999` value from unbiased rejection
sampling. It is not secret and is not logged. Both users explicitly accept matching codes or reject.

`Confirm` uses a role-directional HMAC over attempt ID, transcript hash, canonical initiator and
responder DeviceIds, sender role, and `accepted`. Trust persists only after valid transcript,
signatures, local user acceptance, and valid peer confirmation. Directional confirmation keys
prevent reflection; duplicates are idempotent.

## Runtime and Transport

`PairingController` is bounded control state: default one active attempt, hard maximum four,
120-second explicit responder window, 90-second attempt deadline, and 60-second SAS deadline.
After Commit, an attempt is bound to observed source host and UDP port. This filter is not identity
or authentication, and endpoint changes abort rather than migrate an attempt.

Logical packets retry byte-identically after 250 ms, 500 ms, 1000 ms, and 2000 ms; exhaustion
produces `PairingTransportTimeout`. Confirm retries remain briefly after local completion to reduce
final-message asymmetry. There is no infinite retry, generic ACK stream, fragmentation, Session
creation, media/input queue, or distributed commit.

Android `AndroidPairingController` serializes datagram delivery, protocol state, crypto, prompt
delivery, window, and bounded timers on `WarpnectPairing`. Its listener is attached only during an
explicit pairing window or active attempt. This work never enters media/input hot paths.

The initial runtime is a selected LAN DNS-SD route. RFC-005B's reserved contact socket can be
borrowed by one responder pairing controller, so the advertised port is the listening port while
discovery retains socket ownership. Direct DNS-SD remains discovery only: RFC-005C never calls
`connect()` or `createGroup()`, and a Direct-only candidate returns `PairingTransportUnavailable`.

## Verification and Non-Goals

JVM coverage includes packet parsing, RFC 5869 HKDF vector one, P-256 agreement, commitment and
signature/MAC tampering, full two-peer SAS flow, reflection, duplicate/out-of-order packets, trust
conflicts/capacity, explicit rejection, source endpoint binding, pairing window, and retry timing.
An Android instrumentation test is compiled for Keystore generate/load/sign/verify and persistence.

RFC-005C does not implement live-session authentication, session keys, media encryption, packet
MACs, anti-replay, authorization, automatic pairing, discovery schema changes, Direct-only runtime
pairing, Wi-Fi Direct connection, channel negotiation, reconnection, or native/JNI/SCL changes.
