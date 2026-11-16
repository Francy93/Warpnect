# R1711 DirectShell Control Qualification

Parent `015a7b2480b3bff3b4e0917c0e6c1df7e8c40188` (R1710). Production
credentials, real pairing, Host and Session, media/injection, Release
activation, emulator and R0b were not exercised. **P2_PARTIAL_BLOCKED**.

## Physical Control

The only executed artifact was the R1710 Control v2 DEX, 12,040 bytes,
SHA256 `0A53DC6D79FFC7D124C2B0E09153D9858F265B16F047634AABDCE941948B80FD`.
Its four sealed source digests matched. Debug v1 and the ordinary app
were not modified on the phones.

The Windows host had restarted after R1710. A pre-existing loopback SDK
ADB process was verified using Windows' limited process-image query,
absolute image path, binary hash and creation time. It was not restarted
or reconfigured. Each handset had an exact USB witness, SM-A415F model,
exact firmware fingerprint, API, shell UID 2000, `u:r:shell:s0`, SELinux
Enforcing, user 0 and active ADB. The control program was copied to an
exclusive fresh shell-owned mode-0700 directory and set mode 0400.
There was no new association, ADB forward, APK or host listener.

The first API30 launch executed the mapped DEX and opened the observed
listener, then failed the first response HMAC through the Windows
`adb shell -T` client pipe. The owned process drained and its files
were removed. The read-only binary canary independently showed 256
input bytes becoming 27, first differing at byte index 10. The corrected
driver used the documented ADB [serial-specific smart socket and adbd services](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/services.md)
on the same verified USB transport: raw shell v2 for launch, `tcp:<port>`
for the observed device-local loopback socket. The canary then preserved
all 256 values before the new attempt. No change to the program or its
protocol was needed.

| Exact hardware | Positive run | Wrong generation | Wrong HMAC | Teardown |
| --- | --- | --- | --- | --- |
| TESTUNIT001, API30 | PING, unknown command rejected, authenticated identity and SHUTDOWN | No frame; owned exit 2 | No frame; owned exit 2 | PASS |
| TESTUNIT002, API31 | Same | Same | Same | PASS |

Each case uses a separate master secret sent as a bounded binary packet on
stdin only, wiped from the driver's owned buffer. No key, command data,
request or response frame is logged. The launcher connection is owned.
Before sending a Control frame, a separate command observed the real UID,
process creation ticks and argv, `/system/bin/app_process64`, exact DEX
path and transferred inode in process mappings, and a listening IPv4
loopback port (reported as IPv4-mapped IPv6 in the kernel table). The
kernel listener inode matched a descriptor in the exact process's
`/proc/<pid>/fd`. Generation, request sequence, response type, and HMAC
were checked by the separate client. This corroborates DEX provenance
on these handsets but is not hardware attestation of ART instructions,
integrity against a malicious peer with the same UID, or production
bootstrap qualification.

Shutdown acknowledgement is a frame, not release: only after it did
the gate accept EOF, exit status 0, absence of the exact process and
its task directory, and no remaining live TCP endpoint at the owned
address/port. The negative cases used their own exact processes. Their
lack of authenticated output, exit 2, EOF and the same cleanup witnesses
were required. Both namespaces and artifacts were removed only after
drain. Original app APK, user profiles, debug settings, measured ADB
authorization digest and shared server identity were unchanged. No
intervention on the other phones.

External receipt root: `C:/wg-directshell-r1711-evidence`. First stop:
`control-api30/result.json`. Complete gates: `control-api30-raw/` and
`control-api31-raw/`. Transport canaries are recorded separately. Three
new host tests cover v2 wire layout, authentication/response rejection,
and extraction of independent process creation identity. The R1710
eight-case host artifact evidence was not repeated.

## P4 Independent Role Boundaries

- InputSession: a closed typed command set and exact WNIS v1 generation
  framing check against the existing payload codec. This is a precheck,
  not full input event validation or injection. The existing
  `ReflectivePrivilegedInputManagerApi` still needs an approved runtime
  decision; the Debug gateway stays untouched.
- PcmSharedRing: exact typed commands and the WNPR transfer proof,
  cross-checked against the real Debug codec. The tentative lease owns
  one immutable triple: SharedMemory, notify-write and ack-read.
  UID, exact owner, generation, metadata and HMAC must agree. Denials
  and extra/stale receipts do not grant release. The actual Binder/FD
  gateway still uses hidden external provider APIs and is unchanged.
- VideoCapture: exact typed commands and WNVS Surface proof matching
  Debug bytes, an independent one-shot lease, no PCM proof substitution.
  Hidden external provider acquisition and physical Surface teardown
  remain blocked by the handoff decision; no capture was attempted.

The role code cannot transfer kernel handles or attest their release.
Its terminal receipts are modeled. No new media/input DEX/native
package was built; Java/API/native closure of their actual production
runtime remains open. Existing Debug v1 is not used as a substitute.

## P3 SystemUI And Lifecycle

The R1710 physical carrier fixture is historical, not qualification of
the new lock policy or OEM SystemUI. The [Android action API](https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean))
supports a platform authentication requirement on API31+. The
[AOSP Android 12 interaction handler](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/NotificationRemoteInputManager.java)
handles RemoteInput before the normal action path; the notification flag
is not treated here as proof of the user gesture or a substitute for
checking lock state in the receiver. The private carrier now blocks
posting/delivery while device or keyguard is locked or not observable,
rechecks immediately before submission, and disables suggestions. A test
verifies denial, wipe, cancellation and no revival after unlock. The
carrier remains explicit, process-local and single-owner; it never
launches a coordinator from an intent, restarts consent or reuses a code.

No permission prompts or lockscreen gestures were simulated. A fresh
SystemUI test needs explicit operator interaction and separate observation
of lock/unlock, denial, removal and the system reply-history boundary.
`POST_NOTIFICATIONS` on API33+ remains required for this carrier and is
not proven by API30/31. No FGS was introduced: [background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)
must be reviewed before any service-backed continuation. Denied
notifications are not a reason to route around consent with an FGS.

## P2 Network And Keystore

The new unregistered observer accepts the Android default Network only
after Wi-Fi, non-VPN, not-suspended, unblocked and IPv4 LinkProperties
observations agree. It delivers one attempt per observation owner and
keeps the numeric endpoint on the exact Network and epoch. A loss, blocked
state or changed link invalidates the previous epoch and stops the
owned transport, without callback migration or silent retry.
Synchronous Network queries occur outside callbacks and recheck both
identity and epoch. Visible competing local addresses remain an
admission check, not peer identity. That identity, P1 authorization,
recovery/publication and later Host acquisition are still separate.
The new callback/epoch transitions are host-modeled; the old physical
binding and numeric UI observations are unchanged, not proof of this
adapter, real adbd pairing, blocked TLS writes or Android suspend.

The accepted D-P1-D-P4 no-replace requirement is unchanged. Production
writer disabled: only a provider with independently verifiable atomic
no-replacement, or an authenticated separate provisioner with a reviewed
trust/ownership boundary, can preserve the contract. A probabilistic
alias needs an explicit RFC/security decision and was not implemented.

## Software And APK Boundaries

The fresh complete Debug JVM cohort passed **1,699/1,699** in **210**
suites, with no failures, errors or skips (five role, three Network
epoch and one new lock policy method). The additional platform
generated-reply flag missed the first compiler snapshot. On the final
source, the **64/64** focused methods passed and direct class disassembly
confirmed the flag is false, the authentication flag is present and
Keyguard state is checked. The extracted role classes' disassembly
has no Android, reflection or privileged process-driver references.
AndroidTest compiled; no P3 fixture was installed or run in this turn.

Ordinary Debug and Release assembled. Direct DEX-definition and manifest
inspection confirmed the new components only in Debug, inactive, and
no standalone Control or instrumentation in either ordinary APK.
Native ABI inventory remains arm64-v8a, armeabi-v7a and x86_64. Release
was byte-identical to the archived R1710 APK, not runtime-qualified.
Final Debug SHA256:
`17BC778F91535CAE4A87656DDE532EF613D14A715B05BB6250807FFC199B8F14`.
Release SHA256:
`65F0831267473BCCCBAF762309E25E57A44B2C2CDA241E8665B2CAEEF5349565`.
The aggregate's nonzero result is the inherited Debug KtLint failure:
the 67 findings are identical to R1710, with no new findings after
formatting; TestDebug KtLint passed. No old failures were overwritten.

## Remaining Scope

P1 platform owner/drain baseline retained. P2 pairing and publication
remain gated. P3 real SystemUI and permission gesture, FGS and production
registration are pending. P4 standalone Control is physically qualified
on exactly two known firmwares but not integrated; the media/input
gateways need the decisions above. P5 is inactive. Do not infer Release
readiness from builds or move to Host/Session automatically.

Smallest independent next hardware step: one explicitly operated
SystemUI lock/unlock and synthetic reply on the isolated API31 carrier,
with no pairing, and verification of what SystemUI retains. Do not
rerun identical Control trials to substitute for production launch
ownership or the typed FD/Surface and input authority decisions.
