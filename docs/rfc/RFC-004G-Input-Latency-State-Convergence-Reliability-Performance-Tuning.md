# RFC-004G - Input Latency, State Convergence, Reliability and Performance Tuning

## Status

Implemented for Phase 4 with host-native benchmark and JVM deterministic convergence
coverage. Android privileged injection, device identity, UHID availability, physical input,
real-game compatibility, and two-device latency measurements remain device-pending.

## Production Profile

`InputPerformanceProfile.UltraLowLatencyConvergent` retains a zero-wait architecture:

```text
network reorder wait       = 0 us
critical copies            = 2 immediate submissions
ResetState copies          = 3 immediate submissions
recent transport sequences = 64
recent semantic identities = 32
tracked device slots       = 32
tracked HID keys / slot    = 64
max touch repair events    = 64
NACK / FEC / ACK / retry   = disabled
```

`BestEffortBaseline` remains available for before/after testing and retains the RFC-004F
single-send, arrival-order behavior.

## Reliability Architecture

Source-side `InputReliabilityClassifier` keeps only bounded current pointer/gamepad button
metadata. It classifies Key and touch transitions as critical, detects pointer-button and
gamepad-button transitions, and lets movement-only snapshots and deltas remain single-copy.
`SclInputEventSink` immediately calls the existing RFC-004C sender once per configured copy;
each call has its own SCL sequence number. No encoded payload is retained.
When every immediate copy fails, the sender classifier restores only its prior bounded
pointer/gamepad button metadata so a later identical source observation is still classified as
critical. This is not a retry or payload cache.

Target-side `InputStateConvergenceController` executes synchronously in
`WarpnectInputReceiver` before `AndroidTargetInputMapper`. It maintains fixed recent transport
sequence and semantic identity caches, per-key sequence watermarks, per-slot snapshot
watermarks, pointer-button watermarks, reset watermarks, and at most 32 contacts for one
touch slot. It either dispatches, drops, rewrites stale pointer button metadata, repairs touch,
or issues one local per-slot reset before returning.

The source reliability sink and target receive session publish fixed, bounded logarithmic timing
histograms for sender submission, convergence-and-dispatch, and mapping-and-injection work. These
are local duration measurements only. They do not turn unrelated source/target timestamp domains
into a fabricated cross-device latency figure.

The semantic identity is `(sourceEventTimeUs, complete portable event)`, so it includes message
type, device slot, and all event fields. It is only considered a duplicate within the configured
immediate-copy sequence distance for that event class (one adjacent SCL sequence value for
production critical copies, two for resets). Timestamp-only deduplication is never used, and a
later legitimate identical observation is not suppressed merely because Android reported the
same coarse event time.

## Sequence Semantics

| Input class | Handling |
| --- | --- |
| Key | Per `(device slot, HID page, HID usage)` newest transition wins; an orphan newer Up is still delivered. |
| TouchFrame | Latest frame per slot wins; stale frames are dropped before pointer reconciliation. |
| PointerAbsolute | Latest snapshot per slot wins. |
| PointerRelative | Every unique delta is delivered; stale button metadata is replaced with the newest accepted button mask. |
| Scroll | Every unique delta is delivered; stale button metadata is replaced similarly. |
| GamepadState | Latest complete snapshot per slot wins. |
| ResetState | Successful reset creates global or per-slot watermarks; earlier events are dropped. Redundant reset copies advance the watermark without re-injecting reset. |

All comparisons use tested unsigned 32-bit wrap-safe sequence arithmetic. The recent sequence
tracker suppresses a real same-sequence network duplicate immediately and never waits for
reordering.

A stale distinct ResetState that is older than already accepted state is also dropped. This keeps a
late reset from clearing newer post-reset state, while a true immediate redundant reset copy only
advances the reset watermark and does not clear that newer state.

## Touch Reconciliation

Touch repair uses stable pointer IDs, not Android pointer indexes. For a newest complete touch
observation it derives the intended post-action contact set. If it differs from accepted local
state, it emits bounded synthetic PointerUp/Up repairs for disappearing contacts, then Down or
PointerDown repairs for missing contacts, followed by a current Move snapshot. A lost gesture
start is rebuilt at the freshest known positions as Down, PointerDown..., Move. Cancel clears
the local stream. If repair would exceed 64 events, one local `ResetState(ThisDevice,
ErrorRecovery)` is dispatched and state is cleared. Synthetic events stay local and carry the
source timestamp only as diagnostic metadata.

## Latency And Clock Domain

The input source event timestamp remains exactly `PacketHeader.timestamp_us`. Android
`MotionEvent` time uses an uptime timebase, and RFC-004G does not assume it is directly
subtractable from a remote SCL monotonic clock. The current input runtime has no qualified
InputTimestampDomainMapper wired to RFC-001F, so cross-device one-way input latency is reported
as unavailable. Local stage timings and host packetization/parse timing remain valid diagnostics.

## InputManager And UHID

The baseline injection backend is `InputManager` through the existing Shizuku/Sui UserService.
The requested `androidDeviceId` is an input to event construction, not a guarantee of a physical
target InputDevice identity after InputDispatcher processes an injected event. The default target
gamepad device policy is consequently `SyntheticDefault`; `PreferSourceCompatible` remains an
explicit mapping option but makes no identity-preservation claim for this backend.

RFC-004G adds a cold, side-effect-minimal `/dev/uhid` probe to capabilities: it stats the node,
opens it `O_RDWR | O_NONBLOCK`, then closes it without registering a device. Results are typed as
`Unavailable`, `Missing`, `PermissionDenied`, `Accessible`, or `OpenFailed`, with errno where
available. A persistent UHID backend is not implemented. It needs real Shizuku/Sui device
validation before it can be considered a compatibility path.

## Queue, Copy, Allocation, And Threading Audit

```text
capture queue             = 0
sender queue              = 0
retry / ACK queue         = 0
reorder queue             = 0
receiver event queue      = 0
mapping queue             = 0
injection worker queue    = 0
reliability timer         = 0
wire/media payload copies added by RFC-004G = 0
```

The bounded recent-state structures are caches, not queues: none represents future work or
defers an event. The source operates in Android input dispatch; target convergence, mapping, and
Binder submission operate synchronously on `WarpnectInputReceiver`; the UserService retains its
existing bounded state lock. There is no reliability or touch-repair worker.

## Verification

JVM tests cover profile validation, immediate copy counts, partial copy success, semantic versus
transport deduplication, per-key freshness, unsigned wrap, gamepad/absolute freshness, relative
delta preservation, reset watermarks, lost touch release/start repair, and repair overflow.
Native `scl_phase4_input_benchmarks` measures all normal Input V1 shapes through packetization,
strict parse, and receiver acceptance. See `docs/benchmarks/Phase4ReverseInputBaseline.md` for
the host results and device limitations.

No PacketHeader, PayloadType, Audio Payload V1, Video Payload V1, Input Payload V1,
VideoResyncRequest, NACK, FEC, ClockSync, Native Bridge ABI, or privileged service method was
changed. The additional capability Bundle keys are backward-compatible optional diagnostics under
Privileged Input Injection Service Version 1.

## Security Limitation

Endpoint filtering remains isolation only, not authentication. RFC-004G adds no session token,
encryption, or authentication mechanism. Secure session establishment remains Phase 5 work.
