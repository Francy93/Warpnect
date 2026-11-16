# DirectShell R1708: Physical Owner And Independent Prototypes

Baseline `acc86b5014977822b0fa9b0cdbb4aa6ad2d81afa`, branch
`codex/phase6-5-directshell-runtime-gates`. Overall **P2_PARTIAL_BLOCKED**.
The R1708+ operator authorized this isolated experiment and software tests,
not a production Android writer, real adbd enrollment, final Network policy,
component registration or Release/Host/Session activation. The accepted
[D-P1-D-P4 contract](DirectShellR1707FirstEnrollment.md) is unchanged.

## AF25: Physical Owner PASS

Only the exact A41 SM-A415F/API30, `TESTUNIT001`, was addressed. The shared
SDK ADB process was already listening at `127.0.0.1:5037`; it was reused.
The authorized storage check was 4,024,672 KiB available and `NORMAL`.
The previously sealed, v2-signed r1706 target and runner were verified and
installed without replacement for user 0 only. One invocation of
`explicitAndroidOwnerUsesPlatformClockAndDrainsSyntheticPairing` passed.
The receipt records UID 10350 in `untrusted_app`, platform clock and Handler,
physical operation drain, observer termination and zero owned kernel FDs.
The one owned test alias was removed. Both temporary packages and their
processes are absent; the original APK, user 150, USB/Wireless Debugging and
the ADB association set match the preserved baseline. The server was left
running after the test; no other test or phone was addressed.

Receipts: `C:/wg-directshell-r1708-evidence/AF25-result.json`,
`AF25-instrumentation.log` (SHA256
`AA2E6A619EBDE7DD93E0F14FFA4DEFA232D73D03483C5E27A737E6083E823210`)
and `final-device.json`. The r1707 LOW stop is historical and unchanged.
The other 69 methods were not rerun. AF25 does not qualify a production
owner registration, cross-suspend timing, an Android writer or real adbd.

## Isolated Writer Prototype

`DirectShellEnrollmentIntentPrototype` has its own directory and alias prefix.
There is no caller in Android runtime wiring and no AndroidKeyStore writer.
It uses the existing stable cross-process lease, provider-independent AES-GCM
record codec and filesystem no-replace publication. The attached identity is
the existing fixture's software RSA PKCS8/X.509 material, not a new algorithm.

1. Exact current P1 work and revision zero, stable lease and lock-only
   namespace. Generate 256 random bits for the versioned alias. A lock left
   before any intent may be reacquired by a fresh explicit attempt.
2. Create intent with `CREATE_NEW`, force file and sync directory, reread
   exact bytes. It contains a version, a digest of the exact ticket binding
   and the nonce; it contains no pairing code, key or restorable consent.
   No key exists to authenticate the initial intent. Unknown/torn intent
   bytes or simply an intent without an anchor fence new generation.
3. Require confirmed absence plus an independently no-replace key capability.
   Call the provider once, only with current authority. Lookup failure,
   generation error, collision or lost acknowledgement never rearm it.
4. Seal a pending recovery anchor using the verified key and an authenticated
   digest of the entire intent. Sync and publish no-replace, independently
   sync the directory and reread the exact authenticated anchor under the
   same lease. Cancellation after entry does not claim rollback. This
   prototype result does not admit a real pairing operation.
5. Read-only restart never creates the lock or key and has no resume path.
   It checks the stored bytes and binding, not historical consent or a past
   sync acknowledgement. Even after successful readback the intent remains a
   fence. Missing/mismatched artifacts are left intact for explicit recovery.

**Threat model:** private same-UID filesystem and cooperating writers share
the lease; a trusted OS/provider implements the capability and all persistent
handoffs. The lease does not serialize hostile same-UID code, privileged
restore/delete, external Keystore writers or whole-store rollback. A random
alias makes accidental collisions improbable, not impossible or atomic. A
malicious or damaged intent before the anchor has no secret-backed integrity;
its bytes can never authorize anything after restart. The tests use real
files/OS locking, a separate JVM holding the lock, a fake atomic no-replace
provider and modeled directory sync/crashes. They are not actual Android
Keystore concurrency, process-kill or power-loss qualification.

API30 AOSP [existence](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/KeyStore.java#L267)
can collapse a service error into `false`, and the
[generator can delete before creating](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreKeyGeneratorSpi.java#L292).
API31 [Keystore2 lookup](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/keystore/java/android/security/keystore2/AndroidKeyStoreSpi.java#L137)
still loses failure distinctions for metadata; the
[generator](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/keystore/java/android/security/keystore2/AndroidKeyStoreKeyGeneratorSpi.java#L370)
does not expose a public insertion precondition. The Samsung receipts qualify
an existing-only reader and test aliases, not a no-replace writer. No public
API with all the required guarantees was established. Keep strict D-P1; if
considering a probabilistic policy for a fresh reserved namespace instead,
that explicitly changes the guarantee and needs independent design acceptance.

## Network And NSD

The isolated routing model accepts only a canonical numeric IPv4/port and a
coherent selected link snapshot, excluding broadcast, network, loopback,
link-local, multicast, foreign and ambiguous addresses. It checks exact work,
Network token/epoch, interface, address set and competing links. The modeled
socket is already owned by the operation; bind occurs once before connect,
with checks before/after and the original remaining deadline. Link loss or
snapshot drift stops only that operation; no fallback socket or endpoint.
Route eligibility does not authenticate peer identity or authorize protocol.

For a platform adapter, build snapshots from ordered
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
capabilities and LinkProperties, not an independent getter inside the callback.
An unconnected raw socket can use
[`Network.bindSocket`](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)).
The pinned patched `PairingConnectionCtx.startConnected` already borrows a
connected socket and checks its exact endpoint; the existing carrier remains
loopback-only. No libadb modification, NSD registration or live adapter was
needed for the routing model. Recheck the epoch at protocol entry in any
future integration; a host test cannot guarantee actual netd routing.

[Android's pairing guide](https://developer.android.com/tools/adb) exposes a
pairing IPv4/port in the pairing-code dialog, distinct from the connect port.
The Samsung r1699 source/runtime proof covers NSD forwarding, and r1702
observed the API31 pairing dialog and then successful NSD/TLS. Neither attests
the exact manual tuple from the Settings UI on both Samsung API30 and API31.
AF25 does not exercise Settings. Prefer a deliberately reviewed numeric
first path; qualify tuple visibility and lifetime plus same-device binding
before enabling it. Legacy NSD remains a possible future routing hint, never
Network provenance or pairing identity. No automatic fallback is approved.

## P3 And P4

The private setup model holds one existing owner, a current screen attachment
and one process-local notification action. Rotation reattaches without new
consent; a stale screen cannot cancel new work. Detached UI does not serialize
the action or code. Process recreation starts empty. Denied permissions or a
disabled channel gate action; cancellation/expiry retire the action and P1
still owns cleanup. API33+ notification permission is modeled separately from
app/channel visibility on API30/31. No registered Activity, receiver,
permission request, PendingIntent, FGS or actual notification is added.
Next: review a private delivery/visibility policy and real Android lifecycle,
including the exact RemoteInput mutability and notification cancellation.

The offline `tools/directshell/p4-review-package.cjs` locks the four proposed
roles and their exact command-number sets. It accepts pinned `.class` bytes
only from the proposed neutral helper package, checks entrypoint signatures and
transitive static references (`javap`/`jdeps`), rejects unknown/out-of-profile
and unreachable classes, builds a deterministic review JAR and emits its
hash/closure manifest. No shipping helper classes or DEX are produced. Tests
package only synthetic Java classes. The profile's command manifest is a
review contract, not proof of what arbitrary bytecode dispatches. Static
dependencies do not attest reflection, JNI or loaded Android class closure.

The independent Control proof checks modeled exact process creation identity,
generation, measured UID2000 and artifact digest, and validates the real
existing HMAC frame format and request IDs. Signed shutdown acknowledgement
does not prove exit; process termination, channel close and secret cleanup
remain separate inputs. No process launch, generic shell, Debug provider,
Root route, probe role or runtime Release packaging is wired. Next: review
the four actual helper closures and commands, then seal and independently
inspect a D8 artifact and validate real ownership and teardown on isolated
hardware. A Control-only artifact is not eligible for Host/Release.

## Verification And Preservation

- The first broader filtered run passed 485 JVM methods (22 suites); its
  aggregate failed style checks, which are preserved. The final frozen
  candidate compiled Debug and passed 34 JVM tests in the four affected
  suites, with zero failures, errors or skips. The final TestDebug style
  check passed. The earlier Debug style report after substantive formatting
  fixes contained the same 67 inherited findings on unchanged files.
- Four Node packaging tests passed, including deterministic JAR hashes,
  role/command and bytecode negative controls and no-replace output handling.
- The attempted full JVM campaign was interrupted by an unexpected host
  reboot before the full test task produced results. Windows Kernel-Power
  event 41 and event 6008 were preserved; they do not establish the cause.
  No full retry was attempted. The bounded follow-up used one Gradle worker,
  a 1 GiB Gradle heap and in-process Kotlin. An initial PowerShell argument
  parsing failure was preserved separately before the corrected execution.
- No new product APK, Release build, emulator, native regression or media
  replay was run in this increment. The installed AF25 APKs were the old
  sealed fixture pair, not a build of this experimental source cohort.
- A byte-verified external copy of all 15 pre-checkpoint paths was made
  before the bounded run. Logs, XML identities and the source inventory
  are in `C:/wg-directshell-r1708-evidence/`.

## Status And Next Decisions

| Increment | Demonstrated | Still required |
| --- | --- | --- |
| P1 | AF25 on one exact handset, single synthetic owner and drain. | Application lifecycle registration, process/suspend qualification. |
| P2 | Approved transaction and isolated intent/route models; existing synthetic Android evidence retained. | No-replace writer policy, actual Android safe alias qualification, Network policy/binding, pair-only real adbd and blocked-write/suspend/OEM gates. |
| P3 | Private setup lifecycle and code carrier tested in JVM. | Approved carrier policy and Android registration/runtime validation, not production enabled. |
| P4 | Review-only packaging and Control witness tests. | All four actual command/class closures, DEX and physical execution/teardown. |
| P5 | Inactive. | P3/P4 and supported hardware/Release gates. |

Recommend protecting the strict writer guarantee and reviewing the manual
API30/31 Network path separately. The least intrusive next hardware
observation is Settings tuple availability and owned socket binding to a
synthetic endpoint, without creating trust or touching personal credentials.
Neither a new pairing attempt nor Release activation is needed now.
