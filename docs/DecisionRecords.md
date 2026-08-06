# Decision Records

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Architecture decisions that freeze or change project direction are recorded under:

```text
docs/adr/
```

Current ADRs:

- [ADR-0001 - C++20 Core](adr/ADR-0001-cpp20-core.md)
- [ADR-0002 - SCL Over UDP](adr/ADR-0002-scl-over-udp.md)
- [ADR-0003 - Android First](adr/ADR-0003-android-first.md)

## Rules

An ADR is required for changes to:

- Warpnect/SCL separation.
- Android package root.
- Native namespace root.
- Native library name.
- Transport philosophy.
- Protocol versioning rules.
- Native ABI versioning rules.

Architecture Version 1.0 is frozen by RFC-000C.
