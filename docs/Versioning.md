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
