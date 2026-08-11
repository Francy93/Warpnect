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
