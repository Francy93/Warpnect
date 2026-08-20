# RFC-005I - End-to-End Discovery & Secure Session Integration

## Status

RFC-005I is implementation-complete. It introduces no wire protocol or version and composes RFC-005B
through RFC-005H controllers through `SecureSessionCoordinator`.

`AndroidSecureSessionComposition`, owned by `WarpnectApplication`, builds the production discovery,
pairing, WNSH, WNSD, WNCP, WNSN, WNSL, lifecycle, pipeline-factory, application-controller, shared
Host Direct Group Owner manager, and Host-runtime-registry graph. `MainActivity` observes only this
application-owned graph. The normal Compose Host/Client surface has no manual endpoint fields; the
legacy Receiver/Transmitter screen is an explicit Developer Manual surface.

`AndroidSessionPipelineFactory` and `DefaultAndroidSessionPipelineBindings` transfer the exact
stopped RFC-005G native handles into the Phase 2 Video, Phase 3 Audio, and Phase 4 Input controllers
without allocating another endpoint or deriving another Channel context. Same-generation migration
rebinds that adopted live native transport in place, retaining its ChannelId and RFC-005E state.
Generation reconnection closes the generation-N protection controller and creates a fresh
generation-N+1 controller from the new WNSH root; a closed protection controller is never revived.

The final current-worktree verification passed: `ktlintCheck`, `lintDebug`, 422 JVM tests,
`assembleDebug`, `assembleDebugAndroidTest`, three Android native ABIs, native Debug/Release builds,
and 20/20 CTest in both configurations. `adb` was found and executed but reported zero devices, so
Android, Wi-Fi Direct, real-media, migration, reconnect, and multi-device results remain validation
debt rather than inferred success.

## Flow

```text
DiscoveryPresence -> explicit Connect -> WNSH -> WNSD SessionControl -> WNCP -> WNSN
    -> PreparedSessionBootstrap -> WNSL lifecycle -> stopped pipeline construction
    -> inbound consumers/transports -> processors -> physical sources -> Running
```

`SecureSessionCoordinator` owns that ordering for one logical `SessionId`. It exposes immutable
`StateFlow` state and generation tokens, so stale discovery, pairing, handshake, setup, lifecycle,
or pipeline callbacks cannot revive a newer or closed Session. It does not reimplement a protocol
state machine. Host enable/disable starts or stops only advertising, pairing, and WNSH responder
ownership; closing one Host Session leaves the responder available for the next bounded Session.

## Discovery, Trust, and Control

`SecureSessionConnectRequest` contains a selected RFC-005B `DiscoveredPresence`, a capability
request, RFC-005G preferences, and an optional expected trusted-peer constraint. It deliberately
contains no remote IP address or Video, Audio, Input, or Telemetry port. Client bootstrap resolves
the ephemeral LAN descriptor and opens its own ephemeral bootstrap socket. It never borrows the
Host discovery port. The application-scoped `AndroidDirectPathBackend` owns one public-API
`WifiP2pManager` controller, one ref-counted Host Group Owner manager, and one bounded candidate
dispatcher. A Client setup receives only a coordinator for its selected ephemeral Direct locator.
Direct discovery remains only a route candidate until RFC-005G creates a group, binds a candidate
socket, and completes authenticated Direct probing.

An untrusted WNSH result enters `PairingRequired`; it never starts pairing automatically. A
user-authorized action starts RFC-005C. Its SAS prompt is exposed as bounded state, and completion
is emitted only after the trusted-peer store accepted the authenticated binding. Completion starts
a fresh WNSH attempt with new SessionId, attempt ID, nonces, and ephemeral ECDH.

After WNSH, the root is consumed once by `SessionProtectionController`, producing only protected
SessionControl. WNCP, WNSN, WNSL, and ClockSync remain WNSD/`PayloadType.SessionControl` data.
The bootstrap router still accepts only WNPB, WNSH, and WNSD from raw UDP; raw WNCP, WNSN, and
WNSL remain forbidden.

## Ownership and Startup

`PreparedSessionBootstrap` is consumed exactly once by RFC-005H. Its existing admission
reservation becomes the lifecycle-owned capacity slot. `HostSessionRuntimeRegistry` is bounded to
the RFC-005A hard maximum of eight while the configured Host client policy remains authoritative.
It maps one logical SessionId to its current generation and never consumes a second slot.

`SessionPipelineRuntime` validates that components cover exactly the committed selected channels.
The startup transaction is:

```text
InboundSink -> InboundTransport -> OutboundProcessor -> PhysicalSource
```

Display capture, system-audio capture, microphone capture, and input capture are physical sources.
They start only after their local processor and protected transport are ready. `Running` is
published only after all selected components started. A missing or failed selected channel fails the
whole transaction, reverses started work, applies input safety where relevant, sends best-effort
fatal disconnect when possible, and releases the existing lifecycle/capacity ownership once.

## Protected Channel Paths

```text
Video Host: privileged Surface capture -> MediaCodec AVC -> SCL/FEC -> WNSD -> UDP
Video Client: UDP -> WNSD -> FEC/reassembly -> MediaCodec AVC -> UI-owned Surface
Audio: source -> Opus -> SCL Audio -> WNSD -> UDP
Input: capture -> Input V1 -> native transport -> WNSD -> UDP -> 004G -> 004E -> 004D injection
```

The runtime model requires every committed channel to reuse its RFC-005G `ProtectedRequired`
prepared transport. `AndroidSessionPipelineFactory` enforces that handoff for Video, SystemAudio,
MicrophoneAudio, and Input through one-time native-handle adoption. The negotiated datagram budget
propagates to every packetizer: a 1200-byte outer budget
leaves 1156 bytes of inner SCL after the fixed 44-byte WNSD overhead. FEC remains inside protection
and authenticated NACK retransmits the exact protected cached datagram. RFC-005I adds no packet
queue, PCM queue, encoded AU queue, jitter buffer, per-packet Kotlin crypto, or per-packet JNI.

SystemAudio and MicrophoneAudio retain RFC-005G's exact Phase 3 Opus selection, normally 48 kHz and
5 ms, with no new recovery policy. Separate microphone routing remains one independent
MicrophoneAudio ChannelId per Session; this RFC adds no mixer. One Input Channel continues to carry
multiple session-scoped controller `deviceSlot` values. InputManager limitations do not become a
claim of stable gamepad identity or a production UHID backend.

## Lifecycle, Limits, and Audit

The pipeline runtime is the RFC-005H continuity participant. Same-generation migration preserves
pipeline objects, ChannelIds, contexts, key epochs, packet numbers, and replay windows; the existing
adopted native transport receives the replacement pre-bound socket and remote endpoint under its
per-transport native lock. Video may request existing resync/keyframe behavior. No codec is
recreated merely for a path switch.

The production lifecycle candidate I/O moves its armed candidate socket into the existing protected
SessionControl transport only at commit. It never exposes a null or half-updated binding to a packet
path. The executed native Video transport test proves the adopted handle retains its
ProtectionContextId and increments its security packet number across rebind, rejects the old endpoint,
and preserves replay rejection for an already accepted packet.

Suspension stops physical sources and sends hit the existing immediate `PathUnavailable`/drop gate.
There is no Video, Audio, microphone, Input, migration, or reconnect backlog. Existing
`ResetState`/AllSlots semantics handle Input safety. A fresh reconnect closes the old generation and
reruns WNSH, WNSD, WNCP, and WNSN before creating a new pipeline runtime from the new bootstrap.
The old `SessionProtectionController` is terminal after its runtime closes; its replacement is a
new controller scoped to the fresh generation. A Host controller prunes only already-closed runtime
entries before accepting a future bounded Session, never an active security runtime.

The coordinator uses the existing serialized Phase 5 control context and original bounded timers.
It creates no worker per channel. Closed runtime objects are removed rather than stored as history.
RFC-005I adds no cloud discovery, relay/NAT traversal, process-death resume, background policy,
adaptive bitrate, multipath duplication, microphone mixer, persistent UHID backend, or wire format.

PacketHeader V1, PayloadType, Video/Audio/Input Payload V1, FEC, NACK, ClockSync, Discovery
Presence V1, WNPB, WNSH, WNSD, WNCP, WNSN, and WNSL are unchanged. Architecture Version remains
1.0; SCL Protocol Version and Native Bridge ABI Version remain 1. Device and two-device validation
remains reported as validation debt when hardware is unavailable.
