# RFC-005G - Endpoint, Channel & Stream Negotiation

## Scope

Session Setup Protocol V1 transforms the immutable RFC-005F capability profile into a bounded
`PreparedSessionBootstrap`. It runs only after RFC-005D mutual authentication and RFC-005E packet
protection exist. WNSN is carried as `PayloadType.SessionControl` inside WNSD and is never accepted
as raw UDP.

The implementation contains the portable WNSN codec/controller/planner, stopped endpoint and
protection leases, exact Android stream validators, protected native channel transports,
authenticated Direct validation, and public-API Android Wi-Fi Direct group management. It does not
start capture, codecs, audio, input, media receive loops, or mark a Session Running.

## Security Boundary

RFC-005E authenticates, decrypts, endpoint-filters, and anti-replay checks each secure record before
WNSN parsing. WNSN adds no signature, ECDH, HMAC, or traffic key. It binds setup semantics with a
fresh non-zero SetupId, the exact RFC-005F profile hash, canonical message hashes, proposal
generations, and bounded state.

DiscoveryRoute, SessionPath, PathId, ChannelId, UDP port, PacketHeader sequence, WNSD packet number,
and SetupId remain distinct. No WNSN structure carries an IP address. The remote channel address is
always inherited from the validated Active SessionPath, preventing peer-directed third-party UDP.

## WNSN V1

Every message is at most 1024 bytes, is never fragmented, and begins with this 20-byte header:

```text
offset size field
0      4    magic = "WNSN"
4      1    version = 1
5      1    message type
6      2    flags = 0
8      8    non-zero SetupId u64 BE
16     2    body length u16 BE
18     2    reserved = 0
```

Message IDs are:

```text
1  ClientSetupRequest
2  HostPathDirective
3  DirectPathProbe
4  DirectPathAck
5  PathFailure
6  ClientEndpointOffer
7  HostConfigurationProposal
8  ClientConfigurationAccept
9  ClientConfigurationDecline
10 HostCommit
11 SetupReject
```

Fixed bodies are HostPathDirective 50 bytes, DirectPathProbe/Ack 40, PathFailure 44,
ClientConfigurationAccept/HostCommit 96, ClientConfigurationDecline 40, and SetupReject 36.
ClientEndpointOffer is `36 + 4*N`. HostConfigurationProposal starts with an 80-byte prefix, then
20-byte ChannelDescriptor entries, then configuration TLVs. Integers are big endian and reserved
bytes must be zero.

`ClientEndpointOfferHash` and `HostConfigurationProposalHash` are SHA-256 over the exact canonical
20-byte WNSN header and exact body. Semantic retry preserves SetupId, complete WNSN bytes, and hash,
but sends them in a fresh SessionControl record with a new SCL sequence and WNSD packet number. An
exact protected-record replay remains an RFC-005E anti-replay drop. Same SetupId/type with changed
content is SetupConflict, except a valid recoverable decline may advance proposal generation.

## Client Request and Proposal

ClientSetupRequest binds the 32-byte negotiated profile hash, exact selected-channel mask, path and
standby policies, and bounded explicit Video, SystemAudio, MicrophoneAudio, and Input preferences.
Video and Audio lists contain at most four exact allowed modes. The Host never invents an unlisted
fallback. Proposal generation starts at one and is bounded to four.

Client endpoint entries are exactly four bytes: ChannelKind, instance zero, and the already-bound
local UDP port. There is exactly one entry for every selected V1 channel and none for SessionControl.

The 20-byte ChannelDescriptor contains non-zero ChannelId, canonical kind/direction, instance zero,
zero flags, Active PathId, Host port, Client port, maximum secure datagram bytes, and recovery flags.
Descriptors are emitted in Video, SystemAudio, MicrophoneAudio, Input, Telemetry order.

Configuration TLVs are `u16 type / u16 length / value`, ordered by ChannelId then type with no
duplicates. Implemented V1 values are:

```text
1 VideoStreamConfig             18 bytes
2 SystemAudioStreamConfig       18 bytes
3 MicrophoneAudioStreamConfig   18 bytes
4 InputStreamConfig             22 bytes
5 TelemetryStreamConfig          8 bytes
6 RecoveryConfig                12 bytes
7 SessionTimingConfig           reserved; not emitted by the current V1 runtime
```

Video carries ChannelId, AVC, negotiated flags, exact width/height/fps/bitrate, and zero reserved.
Audio carries ChannelId, Opus, exact sample rate/frame duration/channel count/bitrate, and zero
reserved. Input carries selected kind/presence/feature masks plus exact RFC-004G convergence
bounds. Recovery carries enabled flags, exact FEC K/M, retransmission-cache slots, and zero
reserved. Existing protected ClockSync remains authoritative, so no SessionTiming TLV is needed.

## Path Selection

Policy is deterministic. LanOnly performs no P2P call. DirectOnly fails on any Direct failure.
PreferDirectThenLan records failure and may choose LAN because that fallback is explicit. PreferLan
chooses LAN and attempts Direct only when KeepValidatedStandby requests it. Direct Active plus LAN
Standby and LAN Active plus Direct Standby are representable.

A standby retains validated path identity, endpoint metadata, and a shared Direct group lease when
applicable. It owns no duplicate media sockets or packets, heartbeat, health worker, or failover
logic. Runtime migration belongs RFC-005H.

## Android Wi-Fi Direct

`AndroidDirectPathController` uses public WifiP2pManager APIs and waits for actual group/connection
information. ActionListener success is only request acceptance. The Host must be Group Owner;
unexpected Client ownership fails the Direct attempt. `HostDirectGroupManager` serializes group
creation, owns at most one Warpnect Host group, and reference-counts independent Session leases.
Releasing one Client cannot remove a group still leased by another.

The implementation uses no hidden API, shell command, Shizuku Wi-Fi operation, P2P credential wire
field, SSID, passphrase, BSSID, or MAC address. Permission and P2P availability failures are typed.
No process-wide network binding is used and no `p2p0` interface name is assumed. Sockets bind to an
address resolved from the actual WifiP2pGroup interface; Android details stay outside portable SCL.

## Authenticated Direct Validation

One fresh non-zero PathAttemptId identifies each Direct attempt. The Host opens a temporary
Direct-local ephemeral probe socket before sending its port over protected SessionControl. The
Client uses Android's Group Owner address and its own Direct-local socket.

The candidate socket accepts an unbound source only during the five-second window and only after
RFC-005E validates context, epoch, AEAD, and SessionControl. The inner record must be the expected
DirectPathProbe/Ack with matching profile hash, SetupId, and PathAttemptId. The Host learns the
Client endpoint from the authenticated probe source and replies to that endpoint. Generic valid
SessionControl cannot migrate an endpoint.

When policy selects Direct Active, the one initial pre-Running SessionControl rebind moves from LAN
to this authenticated Direct endpoint. This is initial selection, not RFC-005H runtime failover.

## Exact Stream Validation

The exact Android validator reuses existing hardware AVC encoder and decoder discovery. It checks
the complete width/height/fps/bitrate tuple, Surface input/output, hardware classification, and
selected low-latency behavior without starting MediaCodec. WNCP ceilings alone are not sufficient.

Audio reuses existing Phase 3 Opus capture/decode/playback validators and selects only explicit
48 kHz/frame-duration/channel/bitrate modes without opening AudioRecord or Oboe. Input uses the
RFC-004G UltraLowLatencyConvergent profile: critical copies 2, reset copies 3, network reorder wait
0 us, transport duplicate window 64, and semantic identity cache 32. InputManager gains no stable
or distinct virtual gamepad identity, and a UHID probe does not create that capability.

SeparatePerPeer creates one independent MicrophoneAudio ChannelId per Client Session; no mixer is
added. Multiple controllers from one Client remain session-scoped deviceSlot values inside one
Input channel.

## Packet Protection Integration

Each selected channel gets an independent RFC-005E `ProtectionScope.Channel(ChannelId)` and stopped
native `ProtectedRequired` transport. The same already-bound endpoint socket is adopted by the
native transport, so the advertised port is real and no replacement socket is opened.

Send ordering is SCL packetization/fragmentation, FEC, WNSD protection, UDP. Receive ordering is
UDP, WNSD authentication/anti-replay, inner SCL, FEC/reassembly, subsystem delivery. Video NACK is
protected and authenticated before retransmission; the cache returns the exact original protected
datagram. Input security anti-replay precedes but does not replace RFC-004G semantic deduplication.

Integration exists for Video, SystemAudio, MicrophoneAudio, Input, and bounded generic Telemetry.
There is no per-packet JNI, Kotlin crypto, added media queue, or crypto worker.

The default secure outer budget is 1200 bytes. WNSD consumes 44 bytes, leaving 1156 bytes for the
complete inner SCL datagram, including PacketHeader and FEC. The packetizer and FEC storage use the
inner limit; retransmission storage uses the outer wire limit. No protected datagram grows past the
negotiated outer budget.

## Prepared Result and Admission

ClientConfigurationAccept and HostCommit carry the same profile, endpoint-offer, and proposal hash
triple. Duplicate Accept/Commit processing never reallocates ports, ChannelIds, contexts, or Host
capacity. A 64-entry/30-second completion cache can emit a fresh protected HostCommit for a late
semantic retry.

`PreparedSessionBootstrap` owns Session/peer metadata, immutable profile/hash, Active and optional
Standby paths, IDs, endpoint leases, exact configs, opaque protection contexts, stopped transports,
secure SessionControl, Direct group lease, and the original authenticated admission reservation.
It exposes no key. Close is deterministic/idempotent and a production timer closes an unconsumed
result after 30 seconds. Successful setup renews the same reservation; it creates no second slot and
no Running Session.

## Bounds and Threading

```text
WNSN payload                         1024 bytes
selected V1 channels                 5
configuration TLVs                   12 maximum
proposal generations                 4
active setups                        4 default / 8 hard
completion cache                     64 / 30 seconds
semantic retry delays                100, 250, 500, 1000, 2000 ms
LAN setup timeout                    8 seconds
Direct group/connect timeout         15 seconds
Direct candidate validation          5 seconds
Direct overall setup ceiling         30 seconds
active Direct attempts               8
candidate dispatcher sockets         16
Host Direct groups                   1
PreparedSessionBootstrap TTL         30 seconds
```

WNSN runs on the serialized Phase 5 control context. Android P2P uses callbacks and bounded timers,
not polling. Setup adds zero application queues, media queues, reorder queues, or standby packet
queues. Prepared endpoints start no permanent media/channel thread.

## Startup Audit

At completion, display capture, encoders, decoders, AudioRecord, system/microphone capture, Opus
loops, Oboe, input capture/injection, and media receive workers are stopped. The real P2P group and
bound endpoint leases are intentional preparation resources.

## Protocol Audit and Deferrals

New wire domain: Session Setup Protocol Version 1 only. Architecture 1.0, SCL 1, Native Bridge ABI
1, Discovery Presence Schema 1, Pairing Bootstrap 1, Trusted Peer Store Schema 1, Session Handshake
1, Session Packet Protection 1, Capability Negotiation 1, PacketHeader, PayloadType, Video/Audio/
Input Payload V1, NACK, FEC, ClockSync, and VideoResyncRequest are unchanged.

RFC-005H owns Running lifecycle, health, live failover, disconnect, reconnect, and resume. RFC-005I
owns end-to-end startup/consumption. Direct-only pairing or RFC-005D bootstrap still needs an
already established authenticated path. RFC-005G adds no microphone mixer, persistent production
UHID backend, multiple microphone channels from one Client, or standby media duplication.

## Verification Status

Pure JVM tests cover golden WNSN messages, malformed input, hashing, deterministic planning,
semantic retries, loss/idempotency, timeout/cleanup, TTL, path validation, exact stream validation,
fallback policy, and multi-session isolation. Native tests cover candidate endpoint rebind, secure
Audio, protected Video FEC recovery, protected NACK, exact retransmission, and replay rejection.
Android P2P and two-device results are reported from actual connected hardware only.

Verification run on 2026-08-15:

```text
ktlintCheck                         PASS
lintDebug                          PASS
testDebugUnitTest                  PASS (383 tests)
assembleDebug                      PASS
assembleDebugAndroidTest           PASS
Android native ABIs                PASS (arm64-v8a, armeabi-v7a, x86_64)
native Debug configure/build       PASS
native CTest                       PASS (20/20)
native Release build               PASS
Release protection/video/audio/input smoke
                                    PASS
git diff --check                   PASS (line-ending warnings only)
adb devices                        no connected devices
```

Consequently Wi-Fi Direct group creation, two-device Direct connection, authenticated hardware
path probe, simultaneous LAN/Direct behavior, Android per-network socket behavior, real codec/audio/
input preparation, real secure media endpoint transport, and multi-client P2P hardware tests were
not run. No hardware result is inferred from JVM/model coverage.
