# R1709 DirectShell Network And Integration Evidence

Parent: `74cf69bc5ef55fc208a689861c7d09a948b44600`. This increment keeps
**P2_PARTIAL_BLOCKED**. R1708 AF25 and the 69 earlier API30 methods were not
rerun. No real adbd pairing, production key, Host, Session or Release
onboarding. Emulator and R0b remain suspended.

## Physical Network

The opt-in `-PnetworkPhysicalGate=true` build creates the isolated
`io.warpnect.networkgate` target and `io.warpnect.networkgate.test` runner.
The target adds `ACCESS_NETWORK_STATE` to the existing isolated test
manifest. It has no application initializer, providers, activities,
receivers or services. The runner registers only AndroidJUnitRunner.
Both APKs were sealed, v2-signed, and installed without replacement.

| A41 handset | Result | Actual observation |
| --- | --- | --- |
| `TESTUNIT001`, API30, ARM64 | PASS, one method | Android default Network, `wlan0` /24, bind before connect, fixed bidirectional TCP exchange, physical cleanup |
| `TESTUNIT002`, API31, ARM64 | PASS, one method | Same evidence on its own Network and same-device numeric endpoint |

Each method used the selected Android Network's capabilities and link
callbacks, a selected interface and one local IPv4, one client and one
listener on that same address, and the exact expected accepted TCP tuple.
It refused foreign and stale inputs before connecting and blocked a second
connect. The client, accepted socket and listener closed and the platform
callback was unregistered. No loss was induced or observed; loss during
binding and after connection remains covered by deterministic routing tests,
not by physical Wi-Fi loss. This is not RF, NSD, TLS, PAKE, real adbd or
production admission evidence.

The first API30 install readback incorrectly treated `pm list packages -u`
as evidence that the app was installed for profile 150. Android reported
`installed=false`, zero CE inode and never launched for that profile,
confirmed without `-u`. Only user 0 was installed, and the running app
was absent. The next readback adopted that exact installed APK by hash
and ownership receipt, without reinstalling or starting a second test.
The original failure remains in `api30/first-failure.json`.

Both fixtures were removed after process drain; original APK SHA and UID,
user inventory (including API30 profile 150), ADB association fingerprint
and debug settings matched their own baselines. Only the two named USB
transports were addressed. The conventional SDK ADB server was initially
absent and was started using the existing host key; the same verified
server was left running, not restarted or stopped.

Receipts in `C:/wg-directshell-r1709-evidence`: `network-build-first.log`,
`api30-verified-profile/`, `api31-verified-profile/`. Identical sealed
APKs SHA256: target
`9004A7848315D6B9CD70B440A662A74E22AC0D7CF9CD6D09A9E94DE7635F3935`,
runner `C5DF4BA91634BF91C1B875350351B1994682617D025A26AF5BEFC5526CD5600F`.

## Numeric Settings Path

[Android's ADB workflow](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)
documents the IP address and temporary pairing port in the code dialog,
not a reconnect port. The prior r1702 A41/API31 UI operation demonstrated
an actual code dialog and Debug reconnect; it did not record the numeric
tuple shown. In the current passive activity reading, neither phone had
the Wireless Debugging page visible. No screen was opened, no code
requested and no settings changed. Samsung tuple visibility remains
unverified on both exact firmware builds. Small next observation: read
only the visible IP/port labels on each device, without sending a code.

## D-P1-D-P4 Writer

* **Public atomic no-replace:** no such AES alias insertion contract is
  exposed by the public [KeyGenParameterSpec builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder).
  [API30 AOSP](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreKeyGeneratorSpi.java#L291)
  deletes existing alias entries before generation and on failure.
  [API31 AOSP](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/keystore/java/android/security/keystore2/AndroidKeyStoreKeyGeneratorSpi.java#L359)
  instead calls Keystore2 generation with a named descriptor. At the same
  Android tag, [security_level.rs](https://android.googlesource.com/platform/system/security/+/android-12.0.0_r1/keystore2/src/security_level.rs)
  calls `store_new_key`, and
  [database.rs](https://android.googlesource.com/platform/system/security/+/android-12.0.0_r1/keystore2/src/database.rs)
  rebinds the alias, unlinking the prior live key if present. Atomic
  replacement is not atomic create-if-absent. A prior absent lookup and
  the writer lease do not change either provider's contract.
* **Random one-use alias:** a uniformly random 256-bit alias scoped to
  an exclusive writer domain makes accidental collision negligible
  (about `n(n-1)/2^257` for `n` independent aliases), not impossible.
  This assumes uncompromised randomness, exclusive namespace discipline
  and no deliberate precreation. A journal must still fence ambiguous
  success and never adopt or delete an unknown entry. Not equivalent to
  no-replace and not adopted.
* **Alternative:** retain an existing-only application reader; evaluate
  an independently provisioned, authenticated key capability under a
  separate reviewed trust/ownership boundary, or a provider with an
  actual create-if-absent primitive. Require proof of namespace ownership,
  non-replacement, uncertain acknowledgement, crash recovery and revocation.
  Merely moving the same public key-generation call to another process/UID
  does not prove the contract. Do not use hidden APIs or weaken the key
  storage model to ship first enrollment. No device writer was exercised.

## P3 Private Android Delivery

Added actual Debug Activity, BroadcastReceiver, NotificationChannel,
RemoteInput and PendingIntent implementations. No manifest entries,
automatic owner installation, FGS or new normal runtime route. Only an
explicitly injected existing owner may arm one process-local carrier,
and only after an explicit click, current readiness and applicable
notification access. The screen reattaches through rotation without
reissuing authority. The new JVM case covers dismissal during a detached
screen and refusal of an old action against the next ticket.

Reply and dismissal use explicit components, distinct actions and unique
data URIs. API31+ reply is mutable for Android RemoteInput; dismissal is
immutable, and both are one-shot. API30 reply relies on legacy mutable
behavior. The code is neither part of the intent identity nor persisted;
the owned character buffer is wiped, not Android's presentation buffer.
Only the current in-memory action may deliver or cancel; state changes
retire the owned notification and intents. After process death an action
cannot recreate consent, although Android may still display an inert
notification. Denial blocks admission without requesting permissions.
No actual Android delivery, lock-screen/OEM behavior, or process-death
notification UI was exercised.

Foreground execution still needs a separate owner and lifetime review:
[Android background FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
do not turn a notification reply into unrestricted background execution.
No background exemption, wakeup, API34+ service type or permission
assumption is implemented here. P1 deadlines and physical drain are
unchanged.

## P4 Actual Helper Review

Reviewed real class dependencies from the built Debug runtime-classes
JAR (SHA256 `D2B24F7423C6EBA9F3F8E794E1139A1B96323CEFF346F82533BD2CB2E300FD84`)
with `jdeps`, `javap` and source-level command dispatch comparison.
The receipt `p4-actual-closure.json` records full class and command sets.
Counts distinguish classes in the input JAR from all external references,
including ordinary platform/JDK types. They do not qualify reflection,
Binder, native or runtime dispatch.

| Role | Static JAR / external classes | Observed blockers |
| --- | ---: | --- |
| Control | 21 / 83 | Three dispatch commands, but shared 61-command probe protocol, hidden ActivityThread reflection and no standalone production generation/ownership proof |
| InputSession | 104 / 159 | Input smoke/diagnostics 60-66 and probe owner attachment 91 share the entry point |
| PcmSharedRing | 69 / 171 | Signal/diagnostic/fault cases 41, 45, 47-51 and microphone cases 100-105 share the entry point and dependency graph |
| VideoCapture | 71 / 137 | Probe diagnostics command 84 remains dispatchable |

No actual runtime DEX was produced. Packaging a subset of class names
alone would retain forbidden dispatch behavior. Next extract a fixed
Control decoder with its own three commands, direct UID2000 observation
and generation-bound HMAC plus endpoint/process ownership; do not use
debug reflection or arbitrary shell. Verify shutdown acknowledgement and
actual endpoint/process/thread termination independently. Port only the
required role functionality into separate entry points, explicitly prove
Binder/native dependencies and reject all out-of-profile commands before
attempting a DEX. The synthetic review package is not a runtime substitute.
Its four packaging tests passed with the installed JDK exposed in the test
environment, including command, linkage and class-drift negatives.
`p4-package-checks.log` preserves the first wrapper failure before those
assertions ran (the shell did not supply `JAVA_HOME`).

## Windows Regression

The first R1709 wrapper invocation failed before test discovery because the
shell did not supply the installed Android SDK. That log is retained. With
process-local SDK configuration, the unfiltered offline Debug JVM suite ran
with one Gradle worker and a 1 GiB Gradle heap: **1,687/1,687** methods in
**207** suites, zero skips, failures or errors. The evidence includes the
new dismissal case and a hash for each copied XML file. The boot witness
did not change during the run. The earlier R1708 abrupt restart remains
unattributed; this successful run is not a diagnosis of it.

Ordinary merged Debug and Release manifests retain package `io.warpnect`
and contain none of the private P3 components. Debug compiled classes
contain those review components. The ordinary Debug APK assembled and its
DEX defines the private classes, but its manifest does not register them
and its DEX does not include the Network instrumentation class. Release
runtime was not tested. The build retained the historical CXX5304 SDK
metadata warning without changing the SDK.

After formatting, **22/22** focused owner and route tests passed;
AndroidTest Kotlin recompiled without execution. The Network fixture and
P3 component instruction disassemblies match before/after formatting.
The final scoped ktlint reports contain no findings on changed files.
The broader Debug source set still fails with the same 67 inherited
findings; the first report with the new formatting issues was preserved.
Receipts:
`full-jvm.log` (configuration failure), `full-jvm-sdk-bound.log`,
`full-jvm-results.json`, `full-jvm-xml-sha256.json` and
`p3-compiled-boundary.json`, `post-style-bytecode.json`,
`default-debug-apk.json`, `ktlint-scope.json`.

## Remaining Gates

| Phase | R1709 boundary |
| --- | --- |
| P1 | AF25 physical runtime evidence retained. No ordinary application lifecycle registration and no Android suspend qualification. |
| P2 | Real Network-bound synthetic TCP on two exact phones. No production writer, numeric Settings qualification, production endpoint policy, pair-only adbd run, suspend or blocked-write qualification. |
| P3 | Actual Android components prepared and unregistered. Approve and qualify an isolated delivery/permission and lifecycle gate before registration. |
| P4 | Actual Debug helper rejected for minimal DEX; extraction and physical UID/HMAC/ownership/drain qualification remain. |
| P5 | Inactive. No normal Host, Session, preference or Release enablement. |
