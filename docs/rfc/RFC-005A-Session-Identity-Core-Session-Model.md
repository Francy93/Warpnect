# RFC-005A - Session Identity & Core Session Model

**Project:** Warpnect
**Phase:** 5 - Discovery and Secure Session Management
**Status:** Complete
**Architecture Version:** 1.0 (frozen)
**SCL Protocol Version:** 1 (frozen)
**Native Bridge ABI Version:** 1 (frozen)

## Purpose

RFC-005A establishes a Kotlin-only, platform-neutral model for Warpnect sessions. It composes no
networking or media runtime. Its job is to make future discovery, trust, handshake, capability,
path, and lifecycle work representable without confusing identity layers.

The model distinguishes:

~~~text
Device != Peer != Session != Network path != Channel != Peripheral
~~~

The existing local WarpnectRole.Receiver and WarpnectRole.Transmitter UI selection remains a
legacy pipeline-selection state. It is not a Phase 5 device role. Session topology uses Host for
the device exposing the game/application and Client for the remote device. A channel, not the
device role, declares its own direction.

## Identity Model

DeviceId and SessionId are distinct opaque 128-bit Kotlin types with two unsigned 64-bit parts.
Their all-zero values are reserved and their public factories reject them. The identifiers are
fixed-size runtime values; they do not use string parsing, UUID parsing, or ByteArray as their
primary representation.

DeviceId is device identity only. It is not an IP address, port, hostname, Android device ID, trust
result, credential, or cryptographic identity.

SessionId identifies one logical live session instance. It is not a permanent device identifier,
credential, secret, or authentication result. SessionGeneration is a separate non-zero local
incarnation marker and is not an SCL sequence number.

PeerReference currently holds a remote DeviceId only. It intentionally has no endpoint or trust
fields. A future peer can own more than one path while retaining one peer identity.

ChannelId and PathId are separate non-zero session-local unsigned identifiers. ParticipantIndex is
a bounded local ordering value for Host-owned Client sessions and is not an Android player slot or
input device ID.

## Session Topology

RFC-005A models exactly one Host and one Client in each session. Multi-client host support is
represented as independent sessions:

~~~text
Host
  +-- Session A <-> Client A
  +-- Session B <-> Client B
~~~

This preserves independent future authentication, keys, paths, channels, failures, reconnect
behavior, and telemetry per peer. Shared Host media sources can later feed distinct session
channels; the model does not imply one capture or encoder per Client.

SessionManager has one synchronized, bounded LinkedHashMap of sessions. It has no coroutine,
worker, background loop, discovery operation, socket, or media ownership.

| Bound | Default | Hard maximum |
| --- | ---: | ---: |
| Concurrent Host Client sessions | 1 | 8 |
| Registered sessions | 8 | 8 |
| Paths per session | 4 | 4 |
| Channels per session | 32 | 32 |
| Logical peripherals per session | 64 | 64 |

The default duplicate-peer policy is SingleSessionPerPeer; a second live session from the same
remote device is rejected rather than replacing the first. MultipleSessionsPerPeer is an explicit
alternative and remains subject to the configured bounds.

Session lifecycle vocabulary is Created, Establishing, Ready, Running, Suspended, Stopping,
Stopped, Failed, and Closed. RFC-005A creates a session only in Created; it does not claim that a
session is connected, authenticated, or running.

## Channels

Channels are owned by one Session and have an independent ChannelId, kind, direction, and local
state. Multiple channels of the same kind are allowed.

| Channel kind | Default direction |
| --- | --- |
| Control | Bidirectional |
| Video | HostToClient |
| SystemAudio | HostToClient |
| MicrophoneAudio | ClientToHost |
| Input | ClientToHost |
| Telemetry | Bidirectional |

The model does not bind a channel directly to one SCL PayloadType, UDP port, or socket. In
particular, multiple microphone channels remain distinguishable before any future routing or mixer
policy is applied.

## Logical Peripherals and Microphones

LogicalPeripheralId is SessionId plus PeripheralKind plus deviceSlot. It makes:

~~~text
Session A / Gamepad / slot 0 != Session B / Gamepad / slot 0
~~~

and permits multiple controller and microphone instances in one session. Device slots remain
session-scoped; they are not global identity or Android device IDs.

Physical/source presence and target exposure are represented separately:

~~~text
SourcePeripheralPresence: Absent | Present
TargetPeripheralExposureState: NotExposed | Active | RetainedInactive
~~~

MirrorPhysicalPresence is the default for every peripheral kind. Absent source hardware must be
NotExposed. StableSessionPresence may model an absent source with RetainedInactive, preserving a
future target-side logical presence while input is neutral. No UHID device, uinput device, virtual
microphone, or persistent Android input device is created by this RFC.

MicrophoneRoutingPolicy is a Host preference only:

- SeparatePerPeer: each Client microphone stays independently identifiable.
- MixToSingleHostStream: records future user intent to combine sources.

There is no PCM mixing, gain, resampling, queue, or mixer thread in RFC-005A.

## Network Paths

Each session may model bounded Direct and Lan paths with independent PathId values and states:

~~~text
Candidate -> Validated -> Standby / Active -> Degraded / Failed / Closed
~~~

Only one path is active in the RFC-005A normal data-path model. A second path can be Standby.
Changing Direct to Lan preserves the same SessionId, participants, channels, and peripheral
identity. No endpoint allocation, connectivity validation, health check, failover, reconnection, or
dual media transmission is implemented.

The immutable effective SessionBehaviorPolicy supports:

- PathPreferencePolicy: PreferDirectThenLan, PreferLan, DirectOnly, or LanOnly.
- SecondaryPathPolicy: Disabled or KeepValidatedStandby.

KeepValidatedStandby is the default policy intent. It means future control/health continuity is
possible; it does not mean that media or input is copied to two paths.

## Snapshots, Errors, and Threading

SessionSnapshot and SessionManagerSnapshot copy bounded topology into read-only lists. They include
participants, roles, generation, state, channels, paths, active path, peripheral counts, effective
policy, local monotonic timestamps, and typed last error. They contain no event history, SSID, user
name, microphone metadata, endpoint identity, or cross-device time calculation.

The manager reports typed errors for invalid roles/policies, duplicate IDs, capacity limits,
multiple active paths, invalid peripheral presence, invalid state transitions, missing sessions, and
closed manager use. Policy replacement is explicit and validated. It does not mutate the immutable
policy snapshots held by already-created sessions.

All manager mutation and snapshot creation is synchronized on one internal lock. It is bounded state
management, not an execution context.

## Protocol and Native Boundary

RFC-005A does not change:

~~~text
PacketHeader
PayloadType
Video Payload V1
Audio Payload V1
Input Payload V1
ClockSync
NACK
FEC
VideoResyncRequest
~~~

It adds no SCL packet fields, control messages, JNI entry points, native type, AIDL/Binder method,
network thread, or wire serializer. Architecture Version remains 1.0; SCL Protocol Version,
Native Bridge ABI Version, Audio Payload Version, Video Payload Version, Input Payload Version,
and Privileged Input Injection Service Version remain 1.

## Test Coverage

JVM coverage verifies:

- typed ID equality, sentinel rejection, and diagnostics;
- one-Client default and bounded multi-Client/same-peer policies;
- session lookup, removal, lifecycle, close, and copied snapshots;
- channel defaults and multiple microphone channels;
- session-scoped peripheral identity and multiple controller/microphone instances;
- physical-versus-stable exposure semantics and microphone routing policy;
- Direct/Lan active-standby topology, single-active validation, and identity-preserving migration;
- channel, path, peripheral, and session capacity enforcement.

## Deferred Work

RFC-005A does not implement discovery, pairing, trust storage, cryptographic identity, handshake,
authentication, encryption, anti-replay, endpoint/channel negotiation, media fan-out, microphone
mixing, persistent virtual peripherals, path validation/failover, reconnection, or Phase 5 network
workers.

Next: RFC-005B - Local Network Discovery & Presence.
