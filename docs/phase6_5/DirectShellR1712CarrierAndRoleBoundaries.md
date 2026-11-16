# R1712 Private Carrier And Role Boundaries

Parent `6ca9af6f2d262d4f1af5af52863e640176e82842`, branch
`codex/phase6-5-directshell-runtime-gates`. R1711 physical Control v2
qualification on the exact API30 and API31 handsets was not repeated.
**P2_PARTIAL_BLOCKED**. No real pairing, production credential writer,
Host/Session, release activation, media capture, injection, emulator or R0b.

## Samsung SystemUI API31

One isolated fixture method, `api31ManualSystemUiLockAndReply`, on the
A41 `TESTUNIT002`, fingerprint
`samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys`.
Installed only `io.warpnect.privatesetupgate` and its exact instrumenter,
with distinct UIDs and matching signatures, on user 0. The merged APK
has a private Activity and receiver, no Internet permission, provider,
service or production Application. The fixture deliberately stops
before recovery, identity, endpoint selection or transport if code
delivery reaches P1. Both physical trials use the sealed R1711 carrier
plus the first R1712 instrumentation method, before the later
foreground-lifecycle changes. No notification dump, screenshots, personal
notification data, pairing codes or SystemUI history were collected.

The first attempt posted exactly one notice with
`VISIBILITY_SECRET`, mutable one-shot RemoteInput and the API31
authentication requirement. The operator saw it in the unlocked
shade and not on the lock screen. The fixture observed actual lock,
but no unlock or delivery before its waiting phase ended. Because
the first gesture did not complete and the operator was available,
one recovery attempt reused the exact same sealed APKs and abbreviated
the operator prompt. It reproduced the visible-then-missing notice,
with a total instrumentation time of about 26 seconds; the ordinary
120-second consent deadline alone cannot explain the second result.
Do not attribute the disappearance to a specific callback or OEM rule:
neither device receipt captured the terminal P1 phase or retirement
reason. No further trial was launched. The current source adds a
phase marker for a future, distinct fixture.

Observed: unlocked visibility, lock-screen absence (operator), lock
(Keyguard readback), app policy flags (instrumentation), and removal
and cleanup (owned Android observations). **Not observed:** a SystemUI
authentication prompt, an accepted synthetic send, post-send history or
successful real lock/unlock delivery. App-visible history was absent in
these two **no-delivery** attempts; this says nothing about retention
after a send. Both runs retained their failure and exact artifact
hashes, verified fixture drain, removed the two owned APKs only, and
remeasured the original APK, profiles, ADB settings and association
digest. The shared server was not restarted, reconfigured or enumerated.
Historical R1710 direct PendingIntent delivery, rotation, expiry,
dismissal and process-death cases remain distinct (not SystemUI gestures).

The app can stop accepting a ticket, retire its one-shot intent and
remove its notice. It cannot attest that Samsung SystemUI erased an
editor buffer, a rendered reply or historical text. Even on the AOSP
[RemoteInput path](https://android.googlesource.com/platform/frameworks/base/%2B/5ab3aa0c7b913079e1a8146d347faf66c49761a3/packages/SystemUI/src/com/android/systemui/statusbar/NotificationRemoteInputManager.java)
SystemUI owns reply history beyond the app's carrier. Prefer an explicit
unlocked in-app entry for sensitive data and keep RemoteInput
unqualified for secrets; do not lower the lock policy or change global
history. A future isolated retention diagnostic should first prove
one real SystemUI send while already unlocked, as a distinct question
from the lock test. Never use a real pairing code for that diagnostic.

## P3 Lifecycle

The unregistered private Activity now signals pause. The unregistered
foreground alternative binds exactly one resumed screen and the same
P1 ticket and generation; it does not arm from resume, a permission
callback or an intent. Pause or rotation retires unsubmitted foreground
entry; stale screen actions cannot cancel newer work. Both input paths
wipe owned character buffers and retire an exact rejected submission
instead of leaving a ticket armed with no usable action. Process death
does not install a new process owner or revive consent. The foreground
option is an ownership model only, not a shipped input prompt.

Use a local masked entry in a private Activity, no RemoteInput,
clipboard, save-state or notification fallback for the secret. A
notification denied or hidden on API33+ must not auto-start enrollment:
offer an explicit foreground route or a separately initiated permission
action; neither permission return nor unblocking a channel replays
consent. The [API33 permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
is not exercised on API31. No FGS was added. The
[Android background start limits](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [Android service type/deadline rules](https://developer.android.com/develop/background-work/services/fgs/service-types)
are separate from P1 clocks; a shortService nominal budget does not
cover the whole 120s/60s/3s sequence and connectedDevice has use-case and
permission prerequisites. Neither is justified simply to keep the
private carrier alive. Production process registration and a real API33+
permission/denial exercise remain pending.

## P2 Network And Cancellation

Wi-Fi selection re-samples the actual capabilities and link outside the
Android callback lock, checks exact default Network and epoch before and
after inspection, and fails closed on unavailable permission or data.
Modeled interleavings cover stale equal-looking snapshots and
replacement during inspection. This is a sample, not atomic platform
attestation. Existing stop-on-loss, no migration and one operation-owned
socket remain. A numeric route and DNS-SD metadata do not authenticate
a peer; only the owned pinned TLS/PAKE path supplies verified peer
evidence. No new DNS-SD fallback or recovery power was added.

Timer-registration or worker-dispatch startup failure retires the exact
attempt's copied code if no I/O worker exists, signals any tracked
closer, and creates no authorization to retry. Retirement is distinct
from worker termination. D-P1-D-P4 strict no-replace and the disabled
production AndroidKeyStore writer are unchanged.

The controlled JVM TLS-write experiment uses real Bouncy Castle TLS 1.3
records and the actual owned transport close path. The synthetic peer
stops reading after the handshake; the test requires two observations
of a live socket write beneath the TLS record writer with no completed
write progress, before cancellation and independent drain. This is
TLS application stress, not an unmodified bounded pairing message or
Android suspend/connect evidence; it does not close A06.

## P4 Typed Roles

InputSession admits only its current owner and generation and validates
the full existing typed WNIS payload before giving out a single
in-flight call with a defensive payload copy. PREPARE requires the
explicitly bound application UID and the normal asynchronous mode;
wildcard, another target and the legacy diagnostic mode are denied.
Cancel, uncertain outcome
or STOP is terminal until separate input-release and worker-exit
witnesses. No InputSmoke, diagnostic protocol, injection or discovery.
The actual hidden `ReflectivePrivilegedInputManagerApi` remains an
explicit dependency: it needs an approved runtime/authority decision.

The PCM release adapter binds the exact named three-handle ledger and
closes each local resource once. A failed close does not skip the other
handles or supply its own release witness. The JVM witness uses real
pipe ends and a controlled backing-memory witness; it is not Android
SharedMemory or Android Binder FD delivery. Video has a separate
exact Surface ledger and requires both independent release and worker
exit; this is modeled, not a real Surface or capture. The existing
working Debug audio/video paths, fault injection and diagnostics were
not modified. Actual roles still rely on hidden ActivityManager
external provider acquisition and existing attribution boundaries.
Before activating those gateways, choose and review the transport
authority, authenticate the exact peer/process, bound partial
parcel/descriptor ownership at both ends, and physically observe FD
duplication/unmap/closure and Surface/renderer release. Neither the
local adapter nor Control v2 can attest the other process's handles.

## Software Verification

- An initial focused run passed 129 tests in nine suites. The sealed
  source then passed the complete unfiltered JVM run, **1711/1711 in
  211 suites**, with no failures, errors or skips. This includes the
  final target UID and diagnostic-mode negatives and the TLS
  application-write cancellation test.
- Ordinary Debug and Release assembled; final AndroidTest sources
  compiled but were not run on Android. The separate sealed fixture
  above is the only AndroidTest artifact executed here.
- Both ordinary manifest declaration lists match R1711. The DEX
  definition tables contain the new components in Debug only; neither
  APK defines the test fixture, test classes or standalone Control
  classes. Debug signing identity and the arm64-v8a, armeabi-v7a,
  x86_64 native inventories were checked. Debug SHA256:
  `5D483DDC465AEC7AEE86654446B7488F368283B3898807100C80CAE1176C0DAC`.
  Release is byte-identical to R1711:
  `65F0831267473BCCCBAF762309E25E57A44B2C2CDA241E8665B2CAEEF5349565`.
- The selected and reproduced patched AAR remained
  `BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0`.
- Aggregate Gradle exit 1 is the exact inherited set of 67 Debug KtLint
  findings. There are no added findings; TestDebug and AndroidTestDebug
  reports are empty. Intermediate style findings and the observed SDK
  XML warning `CXX5304` remain in their original logs.
- Sealed source, full XML identities, APK inspection and actual Gradle
  task outcomes are retained in the external receipt root. Native CTest,
  emulator and any additional Android/device campaign were not run.

## Current Gate

P1 preserves the owner/deadline/drain contract. P2 is still partial
(atomic writer, physical platform-admission/loss and Android write
backpressure open). P3 is partial (local policy and one OEM lock-screen
observation, no qualified sensitive reply or API33+ operation). P4 is
partial (Control already qualified standalone, other roles unregistered
and no privileged gateways). P5 stays inactive.

Receipts: `C:/wg-directshell-r1712-evidence`; both device attempts and
the negative instrumentation logs are retained under separate
`api31-p3-channel-SamsungSystemUI*` directories.
