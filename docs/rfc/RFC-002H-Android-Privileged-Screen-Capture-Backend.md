# RFC-002H - Android Privileged Screen Capture Backend & Privilege Boundary

Project: Warpnect

Status: Implemented. Production hardware validation is complete; H1 remains open.

Baseline: Architecture Version 1.0, SCL Protocol Version 1, Native Bridge ABI Version 1.

## Purpose

RFC-002A through RFC-002G remain the completed Phase 2 video foundation. Hardware validation
exposed an Android capture-backend compatibility gap: the RFC-002A legacy SurfaceControl primitive
is unavailable on tested modern Android hardware even though a privileged compositor-to-Surface
path is available through a different framework adapter.

This supplemental RFC defines the production ownership boundary and a capability-driven strategy
model for Android privileged display mirroring. Its implementation does not change Phase 2 history,
resume H1, or change any media, Session, or SCL contract.

## Evidence Status

The following sections distinguish experimental hardware evidence from the architectural decision.
Hardware observations apply only to the tested devices and builds; they are not API-level support
guarantees.

### Experimentally Demonstrated

All successful experiments used non-root Shizuku shell identity, UID `2000`, with SELinux context
`u:r:shell:s0`. They used no MediaProjection and no application-level frame readback or full-frame
copy.

| Device class | Android/API | Modern DisplayManager mirror | Legacy SurfaceControl mirror | Real-frame evidence |
| --- | --- | --- | --- | --- |
| Galaxy S22 (two physical devices) | Android 16 / API 36 | Qualified and passed | `createDisplay(String, boolean)` unavailable | DisplayManager mirror to MediaCodec Surface produced a real encoded frame on both devices |
| Galaxy A41 | Android 11 / API 30 | Not qualified in this experiment | Passed with `secure=true` and an explicit transaction | App-owned MediaCodec plus legacy mirror produced a real encoded frame |
| Galaxy A41 | Android 12 / API 31 | API 36 static mirror route was not available | Passed with `secure=false` and an explicit transaction | App-owned MediaCodec plus legacy mirror produced a real encoded frame |

On the two S22 devices, the qualified modern path was structurally equivalent to:

~~~text
physical display
        -> privileged DisplayManager mirror
        -> VirtualDisplay
        -> MediaCodec input Surface
        -> first real encoded frame
~~~

The resolved framework operation was equivalent to
`DisplayManager.createVirtualDisplay(name, width, height, displayIdToMirror, surface)`. Its reflected
class and method signature remain Android adapter details, not Warpnect cross-version contracts.

On API 30, invoking legacy `setDisplaySurface()` outside the required SurfaceControl transaction
caused a target-side null-pointer failure. On API 31, `createDisplay(..., secure=true)` returned no
token while the `secure=false` transaction lifecycle passed. These are compatibility observations,
not universal SDK rules.

The split ownership experiment proved on both A41 devices that a normal application UID can own
MediaCodec while the Shizuku UserService owns only display mirroring. The actual MediaCodec input
Surface crossed Binder to the helper, which mirrored the real display into it. The app process then
received an encoded access unit. Observed startup-to-first-frame values of approximately 90 ms,
186 ms, 257 ms, and 315 ms were experimental startup observations only. They are not capture,
end-to-end, or steady-state latency measurements.

### Architectural Decision

The selected production direction is a narrow privileged display-mirroring boundary. Warpnect
owns the media runtime and supplies its encoder-owned Surface to a privileged helper that performs
only the Android display operation.

~~~text
Warpnect normal runtime
        -> MediaCodec
        -> encoder input Surface
        -> privileged display mirror helper
        -> Android display compositor
~~~

Shizuku is the current privilege provider, not the media backend. Modern DisplayManager and legacy
SurfaceControl are display-mirror strategies, not privilege providers.

The media-data direction is Android display compositor to the selected mirror strategy, then to the
encoder input Surface and MediaCodec. Binder transfers only the Surface resource reference needed to
configure that relationship; it does not carry frame payloads.

### Future Possibilities

A future Warpnect-owned Wireless Debugging/ADB shell bootstrap or root-backed helper could provide
the same narrow privileged execution boundary. Neither provider, its bootstrap protocol, nor root
support is specified or implemented by this RFC.

## Ownership and Privilege Boundary

The normal Warpnect application runtime MUST own:

- MediaCodec configuration, input Surface creation, start, output consumption, and teardown;
- encoded access units and their existing RFC-002B/RFC-002C ownership path;
- capture/session orchestration and typed failure publication;
- packetization, transport, FEC, decoder, and telemetry hot paths.

The privileged helper MUST own only the selected display-mirror operation and its platform display
resource lifecycle. It MUST NOT own MediaCodec, encoder output, encoded frames, packetization,
transport, Session state, FEC, decoder logic, or telemetry hot paths.

The debug-only `io.warpnect:codecExperiment` process used for crash containment is not a production
architecture decision. Production SHOULD retain the existing normal-UID MediaCodec owner unless a
separate design establishes another normal-UID owner.

## Minimal Privileged Contract

The production-facing contract evolves the existing capture gateway/controller boundary rather than
creating a second media pipeline. Conceptually, it provides:

~~~text
startMirror(targetSurface, displayMirrorConfiguration) -> typed result
stopMirror() -> typed result
~~~

`targetSurface` is the existing encoder-owned `android.view.Surface`. Android Binder/Parcelable
semantics transfer only the Surface handle/reference across the privilege boundary. The contract
MUST NOT transfer pixels, Bitmaps, YUV buffers, encoded access units, or full-frame byte arrays.
The helper does not read frame contents.

The application retains Surface ownership. The helper borrows it only between successful mirror
start and stop completion.

## Privilege Providers and Mirror Strategies

### Privilege Provider

The privilege provider is how Warpnect obtains authority to execute the helper's platform call.
The current provider is a Shizuku shell UserService. Provider-specific behavior MUST remain below
the capture/media boundary so that MediaCodec, Session orchestration, packetization, transport,
decoder, and payload formats do not depend on Shizuku.

### Display Mirror Strategy

The display mirror strategy is the Android adapter invoked by the privileged helper:

- Modern DisplayManager mirroring creates a mirror/VirtualDisplay targeting the supplied Surface
  when cold qualification succeeds.
- Legacy SurfaceControl mirroring creates and configures the legacy display resource when cold
  qualification succeeds.

The selected strategy owns only its Android display resource. It releases that resource
deterministically and drops its borrowed Surface reference during stop.

## Capability Qualification and Selection

Selection MUST be capability-driven, deterministic, and performed before active media capture. It
MUST NOT be an SDK threshold such as `SDK_INT < X`.

Qualification is a bounded cold-path operation. It may verify required classes/methods, safe
argument/lifecycle prerequisites, privilege availability, and a safe create/release lifecycle probe
where justified. Qualification yields a typed available/unavailable result per strategy.

The selection policy is:

1. Prefer a qualified modern DisplayManager strategy.
2. Otherwise select a qualified legacy SurfaceControl strategy.
3. Otherwise report `CaptureBackendUnavailable` through the existing typed capture failure path.

Modern preference is a compatibility/stability decision: it is the only demonstrated strategy on
tested API 36 hardware where the legacy primitive is absent. It is not a claim that modern
DisplayManager has lower steady-state latency than legacy SurfaceControl. Relative backend latency
belongs to later controlled Phase 7 qualification.

This deterministic pre-stream selection is not a silent fallback. The selected strategy and its
typed qualification outcome are explicit control-plane state. Once capture starts, an unexpected
runtime failure MUST surface as a typed capture failure. Warpnect MUST NOT switch strategies,
retry secure modes, or reconstruct a different backend invisibly during an active Session.

### Legacy Adapter Requirements

When selected, the legacy adapter MUST use a SurfaceControl transaction for the capture-display
configuration lifecycle, including surface attachment, display projection, and layer-stack
configuration. It must preserve platform-specific qualified configuration semantics, including
secure-display behavior, behind its compatibility adapter. API 30/API 31 observations do not
authorize a universal secure-flag rule or an unobserved runtime `true -> false` retry.

### Modern Adapter Requirements

When selected, the modern adapter mirrors the physical display into the supplied Surface and owns
the resulting VirtualDisplay lifecycle. The application still owns MediaCodec, the Surface, and
encoder output. The adapter MUST release its VirtualDisplay/resource reference deterministically.

## Lifecycle and Failure Semantics

RFC-002F transmitter ordering remains authoritative. At the capture boundary, startup is
conceptually:

~~~text
app configures encoder and obtains input Surface
        -> app asks privileged helper to start mirror(Surface)
        -> helper creates/configures selected mirror resource
        -> helper reports success
        -> app continues the existing media pipeline
~~~

Shutdown is reverse ownership order:

~~~text
helper stops mirror
        -> helper destroys/releases its display resource
        -> helper drops Surface reference
        -> app stops/releases encoder
        -> app releases encoder Surface
~~~

Partial startup rolls back acquired resources. No virtual display, legacy display token, or
privileged helper capture state may survive a failed start or stop.

If the privileged helper or Binder dies, the mirror is failed. The capture controller MUST publish
the existing typed failure/recoverable state and the active media runtime MUST terminate or fail
startup according to existing ownership. No silent helper reconstruction or active-backend switch
is permitted by this RFC.

## Latency and Copy-Path Constraints

The privileged boundary is control-plane only. Per-frame traffic MUST NOT cross it. This design
introduces no application-level CPU pixel readback, Bitmap transfer, or full-frame application copy
between the compositor and the encoder Surface. It does not claim a platform-level zero-copy
guarantee beyond what Android and the vendor implementation provide.

The design preserves Warpnect's ultra-low-latency objective by avoiding application image buffers,
per-frame Binder traffic, additional frame queues, and hot-path privilege synchronization.
Experimental first-frame deltas are not streaming-latency claims. Relative strategy performance,
thermal behavior, and steady-state latency remain Phase 7 qualification work.

## Scope Boundaries

MediaProjection is not selected by this RFC. No MediaProjection consent flow, foreground
projection service, or MediaProjection fallback is introduced.

Root is not required by the demonstrated design and is outside this RFC. All demonstrated capture
success used non-root Shizuku shell identity.

RFC-002H does not define a Wireless Debugging/ADB bootstrap, ADB pairing, TLS, shell launch
mechanics, or discovery. It requires only that a future provider can replace Shizuku below the
privileged mirror contract without changing the media pipeline.

RFC-005J authenticated Direct-path admission/migration and RFC-005K Host availability/background
persistence remain separate. This RFC defines capture ownership only; it does not define Session
migration, reconnect behavior, helper persistence, or screen-off policy.

## Related Deferred Findings

- On tested MediaTek A41 hardware, the RFC-002B exact strict-CBR active capability probe can abort
  vendor codec code. This is a production-safety follow-up for RFC-002B capability handling, not a
  capture-backend semantic change.
- The tested A41 devices reported `InputApiUnavailable`. This is a privileged Input/Phase 4
  compatibility issue, independent of proven screen-capture capability.

## Architecture Compliance

RFC-002H changes no Architecture Version, SCL Protocol Version, Native Bridge ABI Version,
PacketHeader, PayloadType, Video Payload V1, Video Resync Control V1, Audio Payload V1, Input
Payload V1, WNSH, WNSD, WNTM, WNDE, MetricId, EventTypeId, or report schema. It introduces no JNI
contract and no wire change.

## Decision Summary

1. MediaCodec remains in normal Warpnect application ownership.
2. Privileged execution receives only the encoder Surface and bounded mirror configuration.
3. No per-frame media crosses Binder between Warpnect and the privileged helper.
4. Shizuku is replaceable as a privilege provider and is not the media backend.
5. Modern DisplayManager and legacy SurfaceControl are separate privileged mirror strategies.
6. Strategy qualification and selection are cold, capability-driven, deterministic, and explicit.
7. No silent runtime backend fallback or active-Session strategy switch is allowed.
8. MediaProjection and root are outside the selected architecture.
9. Relative strategy performance remains to be qualified later.

## Implementation Status

The supporting experiments established the architecture. Production implementation and focused
regression coverage are complete, with hardware validation of legacy SurfaceControl on API 30/API
31 and modern DisplayManager mirroring on API 36. H1 remains open for its separate end-to-end
streaming validation.
