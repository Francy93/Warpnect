# Video Receiver Recovery Investigation

## Scope

This note records the investigation prompted by an S9 Client SurfaceView that
appeared black during a modern DirectShell Host campaign. It covers the generic
video receiver recovery path only. It does not change DirectShell privilege
selection, video payloads, SCL, or encoder configuration.

## Facts

The native receiver emits an immediate `VideoResyncRequest` when
`mark_discontinuity()` detects loss or an expired reassembly deadline. The
native sender responds by resending the current StreamConfig generation and
requesting a key frame. A resync response is therefore allowed to contain the
same immutable StreamConfig generation that the Client already admitted.

Before this investigation, `DefaultVideoReceiverSessionController` treated
every delivered StreamConfig as a new configuration. A recovery retransmission
could therefore stop and recreate the decoder even though its generation had
not changed. The controller also issued another immediate resync after the
native receiver had already issued one. Under loss/recovery this created a
decoder-restart and resync feedback loop.

The historical black Client rectangle has two distinct causes that must not be
conflated:

* A Host that cannot start capture produces no AVC configuration or access
  units. Its Client SurfaceView is empty and black.
* Portrait Host content inside the frozen landscape encoder profile has normal
  black letterboxing around the visible image.

Neither case means that a decoder has intentionally generated a black video
frame.

## Change

`VideoReceiverStreamConfigAdmission` now admits each StreamConfig generation
once. A retransmission of the active generation remains recovery data and does
not recreate the decoder. A new generation is still admitted and prepares the
decoder normally.

Starting a new receiver Session resets this admission state before any new
configuration arrives. A controller instance that is stopped and restarted can
therefore admit the same generation number for its new transport lifetime.

The Kotlin session controller no longer duplicates the immediate discontinuity
resync already sent by the native receiver. Its existing delayed keyframe retry
remains as bounded recovery behavior.

DEBUG discovery diagnostics expose the duplicate-config count, resync
suppression/reason, and receiver timing values so a future recovery sequence
can be inspected without treating presentation counters as a success gate.

## Silent Hardware Evidence

On 2026-09-22, a silent S22 API36 Host to S9 API29 Client DirectShell session
remained visible while the Host UI changed from the main screen to Runtime
Diagnostics. The Client screenshot changed with it. The S22 Host continued
capturing and sending access units without transport failures. The S9 recorded
continuous completed and delivered access units, zero reassembly timeouts, one
ignored duplicate StreamConfig, and advancing decoder output/release counts.

No tone was generated during this verification. The run establishes video
recovery and visual presentation only; it does not add SystemAudio audibility
evidence.

Artifacts are retained under:

`artifacts/directshell-runtime/s22-s9-silent-video-recovery-2026-09-22/`

## Scope of the Result

This is a protocol-contract-driven correction, not a device workaround. It has
no model, manufacturer, or API-level condition. DirectShell remains the
selected video capture backend for the counted silent run, and no Shizuku video
UserService was started after log capture began.

The broader self-bootstrap production-runtime matrix remains open: this result
does not replace its Tablet and A41 qualification or its later audible-audio
validation.
