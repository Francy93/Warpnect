# Native Boundary

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

The native boundary is the relationship between Kotlin, JNI, and the C++20 SCL core.

## Boundary Shape

```text
Kotlin Warpnect layer
        |
        v
NativeBridge.kt
        |
        v
jni_bridge.cpp
        |
        v
warpnect::scl C++ interfaces
```

## Kotlin Side

`NativeBridge.kt` is the only Kotlin-side entry point for direct native calls.

It may:

- Load `scl_core`.
- Expose simple bridge metadata.
- Later call stable native SCL entry points.

It must not:

- Encode UDP packet layout in Kotlin.
- Implement native fallback logic.
- Contain transport behavior.
- Bypass the orchestrator for application decisions.

## JNI Side

`jni_bridge.cpp` is glue code only.

It may:

- Convert JNI parameters and return values.
- Call native bridge functions.
- Report native errors in a structured way later.

It must not:

- Implement networking.
- Implement serialization.
- Implement session policy.
- Own business logic.
- Call Android UI APIs.

## C++ Side

SCL C++ owns protocol, timing, telemetry, and future transport interfaces.

SCL must not include Android lifecycle, Compose, Activity, Shizuku, or application UI concepts.

## Error Handling

Future native errors should cross the JNI boundary as explicit status values or structured results. Exceptions must not become the primary hot-path error mechanism.

## ABI Stability

Native bridge ABI changes require an explicit bridge ABI version change and documentation. Protocol layout changes require an explicit protocol version change.
