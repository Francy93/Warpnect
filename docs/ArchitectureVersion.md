# Architecture Version

Architecture Version: 1.0

Status:
Frozen

Date:
2026-08-06

This version represents the stable architecture foundation before SCL Core Networking implementation.

Protocol Version: 1

Native ABI Version: 1

## Frozen Decisions

Warpnect and SCL are separate architectural identities:

- Warpnect is the application layer.
- SCL is the protocol and synchronization layer.

The Android package root is frozen as:

```text
io.warpnect
```

The native namespace root is frozen as:

```cpp
warpnect::scl
```

The Android native shared library target is frozen as:

```text
scl_core
```

The native boundary is Kotlin `NativeBridge.kt` to `jni_bridge.cpp` to the C++ SCL core. JNI remains a bridge only.

Packet structures are part of the SCL protocol contract. Breaking packet layout changes require an explicit protocol version change.

Breaking native bridge changes require explicit ABI version documentation.

Changes to the Warpnect/SCL separation, package root, namespace root, native library name, or transport philosophy require an ADR.
