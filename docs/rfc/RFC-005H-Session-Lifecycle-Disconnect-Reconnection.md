# RFC-005H - Session Lifecycle, Disconnect & Reconnection

## Status

Implemented as the Phase 5 lifecycle foundation. It owns secure-control health, one bounded
same-generation standby migration, graceful close, and fresh-generation recovery. It does not
start MediaCodec, AudioRecord, Oboe, capture, input injection, or a Running media session; that
composition remains RFC-005I.

## Model

`SessionLifecycleEngine` is a portable, deterministic state machine. Its states are `Prepared`,
`Establishing`, `Active`, `Degraded`, `MigratingPath`, `Suspended`, `Reconnecting`,
`Disconnecting`, `Closed`, and `Failed`. `SessionLifecycleController` supplies serialized control
plane I/O, bounded timers, path events, resource ownership, and immutable snapshots.

Path failure is not session failure. A same-generation migration preserves SessionId,
SessionGeneration, the RFC-005F profile, committed RFC-005G configuration, ChannelIds, RFC-005E
contexts, key epochs, security packet numbers, and replay state. It replaces only path-bound socket
and endpoint bindings. Full path loss enters bounded recovery: it keeps the logical SessionId but
increments SessionGeneration exactly once and performs new RFC-005D, RFC-005E, RFC-005F, and
RFC-005G work. No generation-N key, IV, root, ECDH key, replay window, or packet-number space is
retained for generation N+1.

## Path Health

Health has two inputs. `AndroidNetworkPathMonitor` and the Direct-group adapter forward local path
loss or advisory callbacks onto the lifecycle control context. They are fast hints only and never
authenticate a peer. Every successful RFC-005E unprotect updates the native monotonic authenticated
receive timestamp; malformed, replayed, endpoint-mismatched, and AEAD-invalid records do not.

When authenticated traffic is idle, WNSL sends a Heartbeat after 500 ms by default. The response
timeout is 300 ms and two consecutive misses declare the Active path unavailable. Configuration is
bounded to 250..5000 ms idle time, 100..2000 ms response time, and 1..5 misses. Busy authenticated
traffic suppresses lifecycle probes. There is no high-rate polling or heartbeat worker per channel.

The normal topology has exactly one Active path and at most one `ValidatedStandby` path. Losing the
standby while the active path works enters `Degraded`; it does not reconnect. There is no automatic
preferred-path bounce after recovery.

## WNSL V1

All lifecycle records use the existing secure control path:

```text
UDP -> RFC-005E WNSD -> SCL PacketHeader(payload_type = SessionControl) -> WNSL
```

Raw UDP `WNSL` is not routed to the lifecycle parser. WNSL V1 has magic `WNSL`, version `1`, a
fixed 20-byte header, zero flags and reserved fields, non-zero big-endian `LifecycleMessageId`, and
a body length. A complete message is at most 512 bytes and is not fragmented.

The V1 message types are Heartbeat, HeartbeatAck, PathChallenge, PathResponse,
PathMigrationPrepare, PathMigrationReady, PathMigrationCommit, PathMigrationAck,
DisconnectNotice, and DisconnectAck. Heartbeat bodies are 16 bytes. Challenge/Response bodies are
32 bytes and contain a non-zero `PathMigrationId`, target PathId/kind, and 16 random challenge
bytes. Prepare/Ready carry the committed RFC-005G configuration hash and unique sorted
`(ChannelId, local port)` entries. Commit/Ack use `MigrationPlanHash`, SHA-256 over the exact
canonical Prepare and Ready WNSL records. Disconnect bodies are 12 bytes.

Application semantic retries reuse the same canonical WNSL bytes and logical ID but create a new
SCL/WNSD record. That is intentionally different from retransmitting an exact protected datagram,
which RFC-005E anti-replay drops before WNSL.

## Path Migration

The Client is the V1 migration coordinator. It arms a 1000 ms candidate window for its standby
path, sends PathChallenge, and accepts only a successfully authenticated expected PathResponse with
matching migration ID, path, kind, and challenge. A valid SessionControl packet from a new endpoint
does not rebind anything outside that narrow window.

After validation, the initiator allocates real target-path channel sockets before advertising ports,
sends Prepare, receives the responder's already-bound port list in Ready, then sends Commit.
Remote IP addresses are inherited from the authenticated `SessionPath`; WNSL has no peer-supplied
IP field. On Commit, stopped prepared channel leases are atomically replaced and RFC-005E expected
channel/control endpoints are rebound without reconstructing protection contexts. Ack is emitted on
the new path. The migration timeout is 1500 ms.

No normal Video, Audio, Input, or Telemetry packet is intentionally sent on both paths. If the old
path is already unavailable, hot-path sends drop with the transport gate instead of creating a
failover backlog. Existing short-lived NACK behavior is not extended by lifecycle migration.

## Input and Continuity Hooks

`SessionContinuityParticipant` gives later pipeline integration bounded migration, suspend,
reconnect, and close callbacks. It cannot request a media queue. The input recovery seam resets
remote target state on suspension/close; later input integration can emit the existing Input V1
ResetState and then rebuild known source state. Gamepad snapshots and normal pointer/button state
continue to use their existing V1 semantics. No new media or input payload is introduced.

## Disconnect

`gracefulDisconnect` changes state to `Disconnecting`, calls continuity pre-close hooks, sends one
protected DisconnectNotice and at most one semantic retry within the 250 ms close budget. Ack is
best effort only. A valid remote notice is acknowledged, disables recovery, and releases local
resources idempotently. Abrupt path loss has no such notice and instead follows the recovery policy.

## Reconnection

`SessionRecoveryPolicy` is `Disabled` or `FastReconnect` (the default). FastReconnect keeps a
bounded in-memory, non-secret `RecoverableSessionRecord` for 15 seconds by default, with at most
six sequential attempts at `0, 250, 500, 1000, 2000, 4000` ms. It stores SessionId, old generation,
expected DeviceId, roles, explicit capability/setup/path preferences, diagnostic hashes, deadline,
and attempt count. It never stores traffic secrets, roots, IVs, ECDH private keys, tickets, or PSKs.

The Client reconnects using `ReconnectSession(SessionId, previousGeneration + 1, ExactTrustedPeer)`
through the existing WNSH V1 wire format. The Host's recovery lease is only a pre-auth lookup. After
authentication it requires the exact expected DeviceId, SessionId, role, and next generation, then
atomically transfers the existing capacity slot to the fresh authenticated admission. One logical
Session therefore owns exactly one Host slot while active, migrating, suspended, reconnecting, or
recovering. Recovery records expire and release the slot exactly once.

`ReconnectRoutePlanner` is a pure bounded route ordering seam: still-usable last standby routes are
tried first, then last active routes, then current discovery routes, all filtered by the original
path-preference policy. A route carries no identity assertion; each attempt remains sequential and
must pass `ExactTrustedPeer` authentication.

The fresh root creates a new SessionProtectionRuntime and new context IDs before WNCP and WNSN run
again. Old profile/configuration values are diagnostics only; user intent may be reused but current
capability and exact resource configuration are negotiated afresh. Process death has no session
resume, no persistent recovery record, no PSK resumption, and no 0-RTT path.

## Android and Threading

Android network callbacks are registered and unregistered deterministically per represented path,
with a hard bound of four registrations. Direct group loss remains a shared-group event that marks
only affected Session paths; Client A cannot close Client B. Android APIs remain in platform
adapters and portable SCL contains no Android network types.

All state transitions occur on the serialized lifecycle control context. Native crypto remains
synchronous data-path work. Normal hot paths see a low-cost gate/endpoint binding only; RFC-005H
creates no permanent per-channel lifecycle thread, crypto worker, migration queue, media outage
queue, audio queue, input queue, or reconnect backlog.

## Bounds and Ownership

- WNSL payload: 512 bytes; no fragmentation.
- Candidate window: 1000 ms, bounded to 250..5000 ms.
- Path migration: one active transaction per Session; 1500 ms timeout.
- Migration completion state: capacity 4 per Session, retained up to 5 seconds.
- Channel migration entries: current V1 selected-channel maximum, five.
- Recovery window: 15 seconds, bounded to 2..60 seconds.
- Reconnect attempts: maximum 6; one WNSH attempt at a time.
- Recoverable Host sessions: bounded by the existing hard concurrent-client maximum of 8.

`PreparedSessionBootstrap.transferToLifecycle` cancels the prepared TTL and transfers ownership to
the lifecycle controller. Close is idempotent and releases stopped endpoint leases, candidate
resources, Direct group/session leases, protection runtime, admission/recovery capacity, callbacks,
and timers. A successful recovery hands capacity to the new generation controller without consuming
a second slot.

## Tests and Runtime Status

Pure JVM coverage exercises WNSL canonical encode/decode, malformed records, health timing,
heartbeat threshold, path migration state, recovery deadline/backoff, generation progression,
SessionManager recovery-capacity transfer, and reconnect handshake intent. Native protection tests
exercise endpoint rebind while preserving context identity and replay state. Android callback,
Wi-Fi Direct loss, two-device migration/reconnect, and real pipeline recovery are runtime tests and
must be reported only when an attached device setup actually executes them.

## Deliberate Limitations

RFC-005H does not start a complete production media lifecycle, persist session recovery, resume
after process death, use PSK/0-RTT, duplicate multipath traffic, rebalance automatically to a
preferred path, establish an arbitrary new same-generation path after total loss, add a permanent
Android service policy, add a microphone mixer, add a persistent UHID backend, or add multiple
microphone channels from one Client. RFC-005I owns full end-to-end startup and pipeline integration.
