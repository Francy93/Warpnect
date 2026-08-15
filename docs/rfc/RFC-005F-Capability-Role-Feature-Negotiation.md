# RFC-005F - Capability, Role and Feature Negotiation

## Status

Implemented. Capability Negotiation Protocol Version is 1. Architecture Version remains 1.0;
SCL Protocol Version, Native Bridge ABI Version, Discovery Presence Schema Version, Pairing
Bootstrap Protocol Version, Trusted Peer Store Schema Version, Session Handshake Protocol Version,
and Session Packet Protection Version all remain 1.

## Boundary

RFC-005F creates a compatibility agreement after RFC-005D mutual authentication and RFC-005E
Session Packet Protection. It does not create a Running Session, channel, UDP media endpoint,
codec, capture stream, playback stream, input injection stream, Direct group, or active/standby
path. RFC-005G must revalidate exact resources and configure them explicitly.

Capability is what production Warpnect code can support. Availability is whether the prerequisite
is usable now. Request and Host policy express permitted choices. `NegotiatedCapabilityProfile` is
the frozen accepted intersection; it is not a resource reservation or a future runtime guarantee.
Required items fail closed. Preferred items may be absent. Disabled items never appear. No fallback
is inferred: microphone fallback is selected only when it is in the explicit request.

## Secure Control Path

```text
WNCP canonical payload
  -> existing SCL PacketHeader V1, PayloadType.SessionControl
  -> RFC-005E SessionControl WNSD protect
  -> UDP
```

The reverse path decrypts and verifies WNSD, parses the existing SCL header, requires
`SessionControl`, and only then dispatches WNCP. Raw UDP `WNCP` is never accepted. The one-reader
bootstrap router now recognizes `WNPB`, `WNSH`, and `WNSD`; WNSD is demultiplexed using the opaque
RFC-005E `ProtectionContextId`. It is not peer identity. The same authenticated bootstrap endpoint
can therefore carry pairing, handshaking, and the bounded secure control plane without competing
receive loops.

The native/JNI addition is deliberately cold-path: one Kotlin control payload is framed as one
existing SessionControl SCL datagram and passed to the native WNSD runtime. Video, audio, input,
FEC, and NACK packetizers remain unchanged.

## WNCP V1 Wire Format

Every WNCP message fits in one protected SessionControl datagram. Maximum WNCP bytes, including
the header, is 1024. There is no WNCP fragmentation.

```text
0..3   magic "WNCP"
4      version = 1
5      message type
6..7   flags = 0
8..15  non-zero u64 CapabilityNegotiationId, big endian
16..17 u16 body length, big endian
18..19 reserved = 0
```

Message types are `ClientOffer(1)`, `HostSelection(2)`, `ClientConfirm(3)`,
`HostComplete(4)`, and `NegotiationReject(5)`. The exact bodies of ClientConfirm and HostComplete
are each three SHA-256 values (96 bytes). NegotiationReject is 36 bytes: stage, reason, zero
reserved field, and a related hash.

Capability bodies use sorted canonical TLVs: `u16 type`, `u16 length`, then exact value. There are
at most 16 TLVs and each value is at most 512 bytes. Duplicates and non-ascending wire types are
rejected. Bit 15 is critical: an unknown critical TLV rejects the negotiation; unknown optional
TLVs are ignored semantically but stay in exact canonical message hashes.

V1 sections are CoreTransport (16 bytes), Path (8), Video (20), Audio (16), Input (16), Behavior
(8), CapabilityRequest (24), and NegotiatedCapabilityProfile (52). ClientOffer always carries the
six capability sections plus request. HostSelection starts with `ClientOfferHash`, carries the six
Host sections, and carries exactly one profile.

`ClientOfferHash` and `HostSelectionHash` are SHA-256 over the exact 20-byte WNCP header plus its
body. `NegotiatedProfileHash` is SHA-256 over the exact profile TLV header and value. These hashes
correlate application state and idempotency only; RFC-005E provides authentication and encryption.

## Selection and Roles

Authenticated roles are fixed: Client offers and Host selects. WNCP cannot alter RFC-005D roles.
The Host produces one deterministic profile that is bounded by Client and Host snapshots and Host
policy. The Client verifies the profile against its frozen snapshot and original request before it
sends ClientConfirm. HostComplete repeats the same three hashes. Neither side repairs an invalid
selection locally.

Core compatibility requires SCL V1, Session Packet Protection V1, protected SessionControl, and a
common secure-datagram ceiling. The negotiated ceiling and maximum channel count are minima. Paths
intersect only implemented-and-currently-available kinds. Current production mapping declares LAN
as implemented; Wi-Fi Direct DNS-SD discovery does not declare Direct path implementation or
availability until RFC-005G establishes it.

Video V1 admits AVC only, with Host hardware surface encoding and Client hardware surface decoding.
The exchanged width, height, FPS, and bitrate are ceilings, never an assurance that every tuple
below them works. Exact MediaCodec format selection is deferred to RFC-005G, with no software
fallback.

Audio V1 admits Opus only. Frame-duration and sample-rate masks intersect, while the direction
checks distinguish Host system-audio capture/encode from Client decode/playback and Client
microphone capture/encode from Host decode. Capability collection opens no audio stream.

Input V1 intersects Client capture kinds with Host injection kinds. The current InputManager path
can report privileged injection when usable, but never reports `DistinctGamepadIdentity` or
`StableVirtualGamepadPresence`. A cold `/dev/uhid` accessibility probe is not a production virtual
device backend. Dynamic physical peripheral presence is not a stable capability.

Behavior V1 models SeparatePerPeer and MixToSingleHostStream microphone routing plus mirror/stable
peripheral presence. The Android collector advertises neither microphone mixing nor stable virtual
gamepads unless an actual production implementation is supplied. This RFC does not add either.

## Controller, Retries and Bounds

`CapabilityNegotiationController` freezes one local snapshot per attempt, has no application queue,
and keeps active state bounded to 4 by default and 8 hard. It has one logical-flight retry timer:
100, 250, 500, 1000, and 2000 ms, with an 8 s total timeout. The recent completion cache is 64
entries retained for 30 s. Failed, timed-out, cancelled, or closed negotiations release their
RFC-005D admission reservation. Successful Host confirmation renews the same reservation for 30 s;
it never consumes a second capacity slot and never creates a Running Session.

An RFC-005F retry preserves the same WNCP bytes, negotiation ID, and hash but is sent as a new SCL
sequence/WNSD packet number. That is distinct from RFC-005E exact protected retransmission, whose
old packet number would correctly be rejected by anti-replay. Receivers deduplicate semantic retries
by `(NegotiationId, message type, canonical hash)` and fail a same-ID/same-type content change.

The controller and collectors are cold control-plane work. They have no media/audio/input callback,
per-datagram worker, reorder queue, busy poll, or long-lived capability cache. Android collection
maps existing controller probe data and does not start codecs, audio capture/playback, injection,
UHID, or Wi-Fi Direct connection.

## Security and Current Limits

WNCP capability data is confidential and authenticated only through the RFC-005E SessionControl
context. It is absent from discovery TXT, pairing, and plaintext WNSH hello messages. No new
PayloadType, PacketHeader field, media/input payload field, NACK, FEC, ClockSync, or
VideoResyncRequest field was added.

The current runtime is LAN secure-control only. Direct-only negotiation awaits an established Direct
IP path. There is no exact video/audio configuration, endpoint selection, media startup, mixer,
persistent UHID backend, peripheral-instance synchronization, path selection, reconnect, or
session creation in this RFC.

## Verification

JVM coverage includes WNCP header/body vectors, strict parser behavior, optional/critical TLVs,
hash binding, deterministic intersection, required/preferred/disabled semantics, microphone
fallback, hardware-video truthfulness, Direct overclaim prevention, input/UHID behavior,
completion-cache idempotency, semantic conflict, timeout, and reservation lifecycle. Android
capability/runtime and two-device negotiation checks remain device-dependent and must be reported
only when actually run.
