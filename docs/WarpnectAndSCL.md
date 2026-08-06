# Warpnect and SCL Naming

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect and SCL are separate names because they refer to separate architectural responsibilities.

## Warpnect

Warpnect is the application and product ecosystem.

Use Warpnect for:

- User-facing app concepts.
- UI text.
- Android application modules.
- App-level orchestration.
- Documentation about product behavior.
- Release artifacts and app identity.

Examples:

- `WarpnectRole`
- `CoreOrchestrator`
- `MainScreen`
- `io.warpnect`

## SCL

SCL means State Coherence Layer. It is the protocol and synchronization layer.

Use SCL for:

- Packet structures.
- Transport interfaces.
- Telemetry structures.
- Timing and synchronization.
- Native C++ protocol namespaces.
- Future cross-platform core libraries.

Examples:

- `warpnect::scl`
- `PacketHeader`
- `PayloadType`
- `FrameTelemetry`
- `UdpEngine`

## Package and Namespace Rules

The final Android package root is:

```text
io.warpnect
```

The native namespace root is:

```cpp
namespace warpnect::scl
```

Temporary sample package names are forbidden.

## Artifact Naming

The current Android native shared library target is:

```text
scl_core
```

This is the frozen native artifact name for the State Coherence Layer core. The produced Android shared library is `libscl_core.so`.

The native artifact name must not be treated as a third product identity.

## Rule of Thumb

If code describes what the user is doing, it is probably Warpnect.

If code describes how endpoints stay synchronized, it is probably SCL.
