# Versioning

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect uses independent version domains. They must not be conflated.

## Architecture Version

Current value:

```text
1.0
```

Architecture Version describes repository structure, layer boundaries, naming, and frozen architecture decisions.

Architecture Version 1.0 was frozen by RFC-000C.

Changing the architecture version requires an ADR.

## Protocol Version

Current value:

```text
1
```

Protocol Version describes SCL packet layout and protocol-level compatibility.

The C++ constant is:

```cpp
kSclProtocolVersion
```

Breaking packet layout or protocol semantic changes require incrementing Protocol Version.

## Native ABI Version

Current value:

```text
1
```

Native ABI Version describes Kotlin/JNI/native boundary compatibility.

The C++ constant is:

```cpp
kNativeBridgeAbiVersion
```

Breaking JNI/native entry point or bridge result changes require explicit ABI documentation and version handling.

## Video Payload Version

Current value:

```text
1
```

Video Payload Version describes the logical payload layout carried inside existing `PayloadType::Video` packets.

It is independent from SCL Protocol Version and Native ABI Version. RFC-002C defines Version 1 `StreamConfig` and `AccessUnit` messages without changing the 21-byte SCL `PacketHeader` or adding a new payload type.

## Video Resync Control Version

Current value:

```text
1
```

Video Resync Control Version describes the compact `VideoResyncRequest` SessionControl subtype defined by RFC-002G. RFC-003G does not change it.

## PCM Shared Ring Version

Current value:

```text
1
```

PCM Shared Ring Version describes the privileged system-audio SharedMemory ring layout defined by RFC-003A. It is not an SCL wire version.

## PCM Playback Ring Version

Current value:

```text
1
```

PCM Playback Ring Version describes the internal RFC-003E native playback handoff ring. RFC-003G adds source/output timing anchor metadata on top of that native playback path without changing SCL wire protocol.

## Audio Payload Version

Current value:

```text
1
```

Audio Payload Version describes the logical payload layout carried inside existing `PayloadType::SystemAudio` and `PayloadType::MicrophoneAudio` packets.

It is independent from SCL Protocol Version, Native ABI Version, PCM Shared Ring Version, Video Payload Version, and Video Resync Control Version. RFC-003C defines Version 1 `StreamConfig` and `AudioFrame` messages without changing the 21-byte SCL `PacketHeader` or adding a new payload type.

## Input Payload Version

Current value:

```text
1
```

Input Payload Version describes the logical payload layout carried inside existing `PayloadType::Input` packets.

It is independent from SCL Protocol Version, Native ABI Version, Audio Payload Version, Video Payload Version, and Video Resync Control Version. RFC-004A defines Version 1 keyboard, touch, pointer, scroll, gamepad-state, and reset messages without changing the 21-byte SCL `PacketHeader` or adding a new payload type.

## Privileged Input Injection Service Version

Current value:

```text
1
```

Privileged Input Injection Service Version describes RFC-004D's internal Android AIDL/Binder contract. RFC-004F composes the existing injection controller without changing this service version or its Binder methods.

RFC-004G keeps Privileged Input Injection Service Version `1`. Its optional UHID capability fields
are additive keys in the existing capabilities Bundle and do not alter a service method or input
wire contract.

## RFC-005A Session Model

RFC-005A adds a Kotlin application-model layer only. It introduces no Session Model wire version
and does not change Architecture Version, SCL Protocol Version, Native Bridge ABI Version, PCM
ring versions, Video Payload Version, Audio Payload Version, Input Payload Version, Video Resync
Control Version, or Privileged Input Injection Service Version.

## Discovery Presence Schema Version

Current value:

```text
1
```

Discovery Presence Schema Version describes RFC-005B's small DNS-SD TXT metadata schema for
ephemeral unauthenticated local presence. It is not an SCL Protocol Version, Native Bridge ABI
Version, media/input payload version, or session-security version. It adds no field to PacketHeader
or any existing SCL/media/input wire contract.

## Pairing Bootstrap Protocol Version

Current value:

```text
1
```

Pairing Bootstrap Protocol Version describes RFC-005C's standalone pre-session pairing datagram
format. It is independent from SCL Protocol Version and does not alter PacketHeader, PayloadType,
or any existing media/input payload.

## Trusted Peer Store Schema Version

Current value:

```text
1
```

Trusted Peer Store Schema Version describes local application-private persistence of public trust
bindings. It is neither a wire version nor a cryptographic key version.

## Application Version

## Session Handshake Protocol Version

Current value:

```text
1
```

Session Handshake Protocol Version defines RFC-005D's standalone pre-session `WNSH` datagram
format. It is distinct from the SCL, discovery, pairing, and trusted-store version domains.

## Session Packet Protection Version

Current value:

```text
1
```

Session Packet Protection Version defines RFC-005E's outer `WNSD` envelope only. It is neither an
SCL Protocol Version nor a media/input payload version and changes no frozen inner SCL wire field.

## Capability Negotiation Protocol Version

Current value:

```text
1
```

Capability Negotiation Protocol Version defines RFC-005F's encrypted `WNCP` application-control
format inside existing `PayloadType.SessionControl`. It is independent from SCL, discovery,
pairing, handshake, packet-protection, native ABI, and media/input payload version domains.

## Session Setup Protocol Version

Current value:

```text
1
```

Session Setup Protocol Version defines RFC-005G's bounded `WNSN` application-control format. It
is carried only inside RFC-005E protected existing `PayloadType.SessionControl` datagrams and does
not alter SCL, discovery, pairing, handshake, packet-protection, capability-negotiation, native
ABI, or existing media/input payload version domains.

## Session Lifecycle Protocol Version

Current value:

```text
1
```

Session Lifecycle Protocol Version defines RFC-005H's protected `WNSL` application-control format
inside existing `PayloadType.SessionControl`. It does not alter Architecture Version 1.0, SCL
Protocol Version 1, Native Bridge ABI Version 1, or any discovery, pairing, handshake,
packet-protection, capability, setup, video, audio, or input wire version.

## RFC-005I Integration

RFC-005I is integration-only. It introduces no protocol version and does not bump Architecture,
SCL, Native ABI, Discovery, Pairing, Handshake, Packet Protection, Capability Negotiation, Session
Setup, Session Lifecycle, Video, Audio, or Input versions.

## Runtime Telemetry Model Version

Current value:

```text
1 (LOCAL / NON-WIRE)
```

Runtime Telemetry Model Version defines RFC-006A's local in-process descriptor, scope, snapshot,
and JNI batch representation. It is not an SCL, discovery, pairing, Session, media, input, or
network protocol version. RFC-006A does not activate or define a payload for `PayloadType.Telemetry`
and does not change Architecture Version 1.0, SCL Protocol Version 1, or Native Bridge ABI Version 1.

## Diagnostic Event Model Version

Current value:

```text
1 (LOCAL / NON-WIRE)
```

Diagnostic Event Model Version defines RFC-006E's static event descriptors, fixed scalar payloads,
scope snapshots, and bounded process-local history. It does not change any SCL or Session wire
format.

## Native Diagnostic Event Bridge Version

Current value:

```text
1 (LOCAL / JNI ONLY)
```

Native Diagnostic Event Bridge V1 is the little-endian `WNDE` batch format. It is an in-process JNI
representation only, never a `PayloadType.Telemetry` packet or a Warpnect network protocol.

## Diagnostic Report Schema Version

Current value:

```text
1 (LOCAL / FILE EXPORT ONLY)
```

Diagnostic Report Schema V1 is the UTF-8 JSON file format used by RFC-006G. It is not a Runtime
Telemetry, Diagnostic Event, SCL, or native bridge version and introduces no network protocol.

## RFC-006H Integration

RFC-006H validates the existing Phase 6 contracts only. Runtime Telemetry Model V1, WNTM V1,
Diagnostic Event Model V1, WNDE V1, and Diagnostic Report Schema V1 remain unchanged, as do
Architecture Version 1.0, SCL Protocol Version 1, and Native Bridge ABI Version 1.

## Application Version

Current value:

```text
0.0.1
```

Application Version is the Android app version declared in `app/build.gradle.kts`.

It may change for product releases without changing Architecture Version, Protocol Version, or Native ABI Version.

## Future Release Version

Future release versions will represent packaged Warpnect releases.

Release versions may include:

- Android application version.
- Protocol Version.
- Native ABI Version.
- Build metadata.

## Relationship Rules

An application release may keep the same Protocol Version.

A Protocol Version change may require a Native ABI Version change, but not always.

A Native ABI Version change may happen without a Protocol Version change.

Architecture Version changes require ADR approval.
