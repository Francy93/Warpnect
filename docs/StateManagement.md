# State Management

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

Warpnect application state is owned by the Kotlin orchestration layer.

## Current State Owner

`CoreOrchestrator` is the current owner of role state.

Current states:

```text
Idle
Receiver
Transmitter
```

The default role is `Receiver`.

## Current State Meaning

`Idle` means Warpnect is not acting as either endpoint.

`Receiver` means Warpnect is prepared to act as the display and input-source side for a future remote session.

`Transmitter` means Warpnect is prepared to act as the capture and device-control side for a future remote session.

These states are currently structural only. They do not start streaming, discovery, networking, audio, input, or privileged operations.

## Future States

Future phases may add:

- `Connecting`
- `Streaming`
- `Reconnecting`
- `Error`

Additional states must describe application/session lifecycle, not packet-level protocol internals.

## State Direction

Application state may trigger native SCL setup later through a narrow bridge.

SCL must not own user-facing application state. SCL may expose protocol status, telemetry, and transport events for the orchestrator to interpret.

## Failure State

Future failures should be explicit and recoverable. Privileged-access failures should lead to user-facing recovery paths such as enabling Wireless Debugging or Shizuku.

## UI Access

Compose UI observes state and sends user intents to the orchestrator. UI must not mutate protocol, transport, or native state directly.
