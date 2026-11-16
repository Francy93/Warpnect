# R1713 Private Code Entry And Local Role Resources

Parent `b92be4995fee788b11ef0bfadf9136d52080c3cb`, branch
`codex/phase6-5-directshell-runtime-gates`. No real adbd pairing,
production credentials, Host/Session, media capture, input injection,
emulator, R0b or Release activation. **P2_PARTIAL_BLOCKED**.

## Private code entry

The experimental Activity is registered only in the isolated Debug
fixture. It uses a local numeric keypad with a masked, non-editable
indicator. It submits a mutable six-character array to the exact
process-scoped P1 owner. Explicit consent is separate from confirmation;
resume, rotation and restoration never issue a fresh ticket. The UI
checks the current screen, action, readiness generation, focus and
unlock state. An obscured touch, cancellation, loss of focus or pause
retires the exact attempt. Expiry is driven by the existing P1 clock;
incoming state changes clear the owned UI buffer. The owned buffer is
also cleared after submit and on destruction. The two carrier types
compete for one owner and one current ticket; neither is a fallback
for a stale action or automatic retry.

The digits never enter an EditText, input connection, clipboard,
autofill, saved UI state, notification, preference, file or log. The
window uses [FLAG_SECURE](https://developer.android.com/security/fraud-prevention/activities#flag_secure);
the report measures the flag, not resistance to every capture path.
Wiping app-owned buffers does not attest Android, IME, accessibility
service, hardware or system memory erasure.

The exact package has no Internet permission, no provider/service,
and no production application initializer. The instrumentation owner
stops at the pre-recovery checkpoint and cannot acquire storage,
credentials or transport. The isolated operator usability activity
creates no ticket or work unless an explicit action is taken; the
observation itself asks the operator not to enter any digits.

On exact Galaxy A41 API30 `TESTUNIT001` and API31 `TESTUNIT002`,
the automated gate observed the secure-window flag, masked entry,
one confirmation only, rotation, real background/return,
cancellation, modeled advance to the real consent cutoff, wrong
readiness generation, a forced death in the owned process and a
different PID with no restored ticket or code. It used synthetic input
and did not test lockscreen authentication during code entry. API31
streamed installation stalled twice before instrumentation; both
negative receipts and absence readback are retained. The same APK
pair was then installed using bounded local staging under unique names.
Both stage paths and both fixture packages were absent after cleanup.

## Samsung settings interaction

An Activity in a split may remain resumed, per
[Android multi-window lifecycle](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#lifecycle),
without retaining focus. The prototype deliberately clears an armed
entry when it loses focus even if it remains visible. Split-screen
support or prompt persistence on a particular Samsung must be physically
observed. No overlay, global settings change, or assumption based on
the manifest's resizeable flag is used as usability evidence.

The useful workflow must let the operator read the Settings code and
then explicitly arm and type it while the Samsung pairing dialog remains
valid. A working test keypad by itself is not proof that the end-to-end
user workflow works.

- API31: the first observation stopped before the Activity was ready.
  In the second, the operator saw the Settings pairing dialog only
  in the Recents preview after switching away. The operator reported
  entering digits in the test keypad but not submitting them.
  The instrumented guard recorded zero dispatches, then flagged an
  armed synthetic ticket at the observation cutoff; teardown passed.
  No raw digits or endpoint were collected as evidence, persisted or reported. The
  observation did not verify that the dialog was live after returning
  into Settings and did not reach a split-screen observation.
- API30: the fixture observed focus loss, no multi-window and zero
  dispatches. The operator saw a Settings preview and a white preview
  for the fixture, consistent with a secure window, but did not observe
  the live Settings dialog after return. The fixture closed at the
  time limit. Neither this nor the preview identifies what Settings
  kept alive. Preserve the first host drain observation failure
  separately from later cleanup verification.

Neither result establishes that the foreground flow can preserve a
live Samsung pairing prompt, nor that switching necessarily closes it.
The smallest next usability observation is one return into Settings
while the fixture is still open: no arming, no typing, and no recording
the code. Test split-screen only if the stock Recents menu offers it.
Do not switch on RemoteInput as a fallback. No pairing was attempted.

## One unlocked SystemUI send

On A41/API31 the fixture published one notice with one RemoteInput
action and observed one actual SystemUI submission while unlocked.
It never called PendingIntent.send as a substitute for that test.
The synthetic owner reached the no-I/O checkpoint once, drained and
removed its own notification. No app-visible reply history was sampled.
The operator no longer saw the synthetic text in the notification shade
after sending; the user-accessible notification history was not
independently inspected. This is **send observed, no text visible on the
observed surface**, not a guarantee of erasure inside SystemUI, across
OEMs, or from data not inspected. RemoteInput is not approved for real
pairing codes. Do not repeat the lock campaign or this one-shot test.

## P4 Input, PCM and Video

The independent Input role now consumes the real WNIS STOP shape and
requires a canonical reset-all request; bare, noncanonical and no-reset
stops are rejected. The legacy gateway and codec are unchanged.
Resource bounds reuse the existing pure event checks for key, touch,
pointer and joystick; they do not replace the real tracker's validation
of event actions and ordering. The exact target UID, normal mode,
generation and owner remain required. Concurrent contenders can
reserve only one event. Cancelled work cannot advance a later session
or attest teardown. The existing privileged input-manager API and
production target authority still require review.

On A41/API30, the isolated PCM test transferred local Parcel duplicates
of real SharedMemory, notify-write and ack-read descriptors. The
duplicate observed shared bytes and both directional pipe signals;
closed local copies were independently checked while originals remained
usable. The isolated video test created an ImageReader Surface with no
producer, transferred a local Parcel duplicate, released the duplicate
without invalidating the original, and required actual worker exit
before its release ledger could complete. These are Android handle
tests, not media quality, inter-process Binder attribution, remote FD
closure or renderer qualification. The role proof's UID is a fixture
input; neither test claims a measured remote Binder calling UID.

Role entrypoints may only close over their own commands and measured
generation/identity. A production artifact still needs a reviewed
launcher, actual shell/app attribution, exact typed inter-process handoff,
partial-parcel and death cleanup, and independently observed teardown
at both ends. No full Debug DEX or hidden provider substitute was shipped.

## P2 and remaining state

The owned transport now rejects blocking self-join by either owned
worker; a zero-budget sample remains an observation, not a termination
claim. The existing numeric exact-local Network/epoch, owned socket,
pinning, recovery and no-implicit-fallback boundaries are unchanged.
Credential storage, authenticated peer identity and routing metadata
remain separate. Production AndroidKeyStore writer stays disabled under
D-P1-D-P4 strict no-replace; no probabilistic alias was introduced. The
older JVM TLS backpressure result is retained, not Android A06. This
no-network fixture cannot qualify a TLS writer on Android.

The private foreground route does not require an FGS to hold consent;
leaving the Activity before confirmation retires the ticket. Android 33+
notification denial never needs to start an FGS or replay a ticket;
real API33+ behavior of a future registered app remains unqualified.
P1 invariants and the standalone Control checkpoint are unchanged,
P2/P3/P4 remain partial, and P5 is inactive.

## Verification

The isolated fixture APKs were sealed separately from the final
ordinary APKs. Fixture target SHA256:
`5258D6B15BF2CEC862A9F20FC2AC1ED4FB14497CDD551EF843283BC417C45AFD`;
instrumentation APK SHA256:
`09F2218EB8FCC39DEE33C1E6DCDA2732A8285C7280E820D2E9FEB78587F342E1`.
The exercised carrier predates formatting-only edits to its source;
the independent Input and transport changes are verified by the
final JVM cohort, not inferred from fixture installation.

Focused **107/107** (seven suites); complete non-instrumented DEBUG
**1713/1713** (211 suites), no failures, errors or skips. Ordinary
Debug and Release assembled; AndroidTest Kotlin compiled. The
ordinary manifest declarations match R1712. DEX definitions and
ABIs confirm Debug-only code, no registration or test artifacts in
ordinary APKs and no Release changes. Debug SHA256:
`2E1D62F3B371D7AFEAE45CD6C63D854458D9B04499709F6422330C49844AC627`;
Release byte-identical to R1712:
`65F0831267473BCCCBAF762309E25E57A44B2C2CDA241E8665B2CAEEF5349565`.
The reproduced AAR matches its existing pin. Aggregate Gradle exit
1 is only the same 67 inherited Debug KtLint findings, with no final
new findings. First style and hardware/preflight negatives remain sealed.

External evidence: `C:/wg-directshell-r1713-evidence`.
All Android gates targeted the authorized USB serials. The existing
shared ADB listener disappeared before the first instrumentation start
and was not restarted. After the operator confirmed that only the two
A41s were attached, the exact SDK binary ran as an owned loopback
server on port 5066 with mDNS and emulator discovery disabled, using
the existing unchanged host keys. Its final process exit and port
release were verified. Cleanup receipts compare original Warpnect
APK identity, profiles, ADB settings and association digests; API30
profile 150 was not an installation target or changed.
