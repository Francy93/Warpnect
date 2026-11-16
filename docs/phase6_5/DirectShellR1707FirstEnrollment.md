# DirectShell R1707: First Enrollment And Runtime Gates

R1708 successor: [AF25 physical PASS and isolated prototypes](DirectShellR1708Prototypes.md).
The LOW refusal below remains the original r1707 result.

Baseline: `00eae779f3dfb0047789f91607da799ad1225de0`, branch
`codex/phase6-5-directshell-runtime-gates`. Overall **P2_PARTIAL_BLOCKED**.

## Accepted RFC-006J Addendum: D-P1 Through D-P4

The operator's explicit R1707+ mission approved these production design
contracts, not execution against real adbd, use of production keys on a
personal phone, Network/NSD policy, or activation of Release/Host/Session.

| Decision | Accepted contract |
| --- | --- |
| D-P1 | Explicit current P1 work, revision zero, demonstrably empty credential namespace and absent alias. One stable exclusive cross-process lease covers admission, key creation, durable encrypted anchor and authenticated reread through handoff. Recheck exact ticket, generation, environment and original deadline at irreversible call boundaries. |
| D-P2 | Fail closed on ambiguity, loss of acknowledgement, orphan alias, missing key, invalid ciphertext, collision or uncertain ownership. Preserve artifacts. Read-only inspection neither resumes consent nor repairs, deletes or regenerates keys. Recovery needs separate authority. |
| D-P3 | Keep the software RSA PKCS8 identity accepted by the pinned TLS implementation. Use opaque AndroidKeyStore AES-256-GCM for wrapping; report AES hardware backing separately from exportable RSA. No algorithm, provider, library or protocol change. |
| D-P4 | Revision zero starts only from known-empty state. No implicit adoption or migration from Debug or any prior credential. Import and recovery require separately accepted provenance and consent. |

## Implemented Boundary

The storage worker owns one lease. Except for the stable lock file, any entry
blocks first creation, even if no key can be read. A first-enrollment key port
must distinguish certified absence from lookup failure and must recheck at
the creation boundary. The P1 work context validates live, exact authority
for this path; the coordinator monitor guards only the authority read, not
filesystem, key, TLS or join operations. A stop can prevent entry to a later
effect; it cannot recall an already entered provider call or write.

An acknowledged key is reread using the existing wrapping-key boundary. The
existing codec, authenticated anchor, R/P/S, owned socket, and D/F are reused;
the original existing-only path is unchanged. Anchor creation is no-clobber.
During any interrupted or uncertain attempt, an alias or encrypted artifact
may remain; no deletion, restoration, or regenerated identity is claimed.
The storage is single-use even on failure. No legacy begin call can adopt a
key supplied through the first-enrollment port.

## Android Platform Gap

The new key port has **no Android writer implementation**. In
[AOSP Android 11's Keystore client](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/KeyStore.java#L267),
an existence query can return false for a service failure, not just absence;
public alias enumeration also maps a failed list to empty in the
[SPI](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreSpi.java).
The [Android 11 generator](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreKeyGeneratorSpi.java#L292)
can delete an existing entry before generation. A file lock serializes our
writers; it cannot disambiguate the provider result. A repeated ambiguous
check, `getKey() == null`, or inspecting only the filesystem is not proof
that a fixed alias may be safely generated. Android 12's
[Keystore2 SPI](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/keystore/java/android/security/keystore2/AndroidKeyStoreSpi.java#L98)
distinguishes more get-key errors but `containsAlias` still collapses
metadata failures; an OEM-safe, collision-safe contract has not been shown.
No hidden SDK access and no privileged Keystore service are proposed.

Smallest next design decision if a public no-replace primitive cannot be
qualified: review a versioned, durable first-creation intent with a fresh,
single-use random alias under the existing lease. Specify how the intent is
authenticated, its collision model, reread and crash ordering, and treatment
of an alias left without its record. A nonce by itself is **not** proof of
absence and does not satisfy the currently accepted contract. Do not change
the alias, record format, reader, or recovery behavior before that review.

## AF25 Attempt

The standard SDK ADB binary `37.0.1-15733141` was started on its existing
default `127.0.0.1:5037` configuration after finding no process or listener.
Only `TESTUNIT001` was targeted. Its USB PnP match, model SM-A415F, API30,
ARM64, baseline fingerprint, user 0 and Enforcing SELinux passed. APK hashes,
manifests and v2 signers matched the sealed r1706 pair. Existing Warpnect,
profile 150, ADB association and settings checks passed; both temporary
packages were absent. The first failure was **Insufficient storage**:
`df` showed 896,784 KiB free and Android reported `LOW`. The inherited gate
requires 2 GiB and no LOW/FULL state. Nothing was installed or executed and
there was nothing to uninstall. The shared server was left running.
Receipts: `C:/wg-directshell-r1707-evidence/`. The r1704/r1705 69 distinct
methods remain historical evidence; AF25 is not one of them and remains
unexecuted. Do not repeat the 69 tests or delete phone data to reach AF25.

## Software Verification

- Full Debug JVM: 1,664/1,664 in 204 suites, no failures/errors/skips. The
  final change was formatting in one JVM test helper only; the frozen
  source passed 466/466 focused methods in 20 suites, including all pairing
  and Host setup launch-guard tests. The two XML inventories are separate.
- New tests cover an authorized encrypted anchor and real synthetic
  P1/TLS/PAKE/R/P/S/D/F, wrong generation and expiry at the provider
  boundary, duplicate and concurrent delivery, no-replace collision,
  key loss, orphaned/uncertain creation and read-only reconciliation.
  An independent JVM holds the real OS lease and excludes first creation;
  positive sync and key creation are modeled, not Android or power-loss
  qualification. No production key was generated.
- Ordinary Debug and unsigned Release assembled; AndroidTest Kotlin compiled
  but did not execute. Release manifest matches r1703, all three ABI entries
  remain and the Debug owner, test fixture and libadb closure are absent
  from Release DEX. Release build success is not a Release pairing gate.
- Pinned AAR reproduction passed with SHA256
  `BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0`.
  The APK hashes are Debug `F2EB07A3ACF77A505BE4B2DB04B3275329EB77605159BA8660AE7A0E39934F1F`
  and unsigned Release `65F0831267473BCCCBAF762309E25E57A44B2C2CDA241E8665B2CAEEF5349565`.
- The aggregate log retains the first formatting failures. Main and final
  TestDebug ktlint pass. All 67 Debug findings match the inherited paths
  and per-file counts; the other 44 inherited JVM-test findings were not
  remeasured. The unchanged SDK metadata diagnostic `CXX5304` was nonfatal.
  Offline media replays were not rerun; no media/native inputs changed.
  Native CTest and Android Lint beyond assembly's Release vital check were
  not run.

Frozen source manifest, XML identities, build logs and final receipts are
under `C:/wg-directshell-r1707-evidence/`; twelve r1706 evidence hashes were
reverified. Desktop, r1703, master and origin/master were not advanced.

## Network Decision To Review

[NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)
Network-specific discovery and [NSD result Network attribution](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#getNetwork())
are not portable on API30/31. Prove the selected Network from ConnectivityManager
and a process-local epoch, using an immutable LinkProperties snapshot; refresh
the epoch on loss, replacement, interface or address changes. Reject stale
callbacks and ambiguous ownership. For the first same-device slice, keep only
a numeric IPv4 address assigned to that exact Network, with exact local
equality and a valid ephemeral port. Reject unspecified, multicast, broadcast,
wrong-interface and foreign addresses; defer scoped IPv6 until interface/scope
equality is proven. Bind the one operation-owned unconnected raw socket with
[`Network.bindSocket`](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket))
before connect. Check current epoch immediately before and after bind/connect.
Loss requests stop only for that operation and closes its socket; no movement
to another Network, another endpoint, guessed port or retry.

Preferred API30/31 baseline for review: explicit numeric endpoint from the
phone's own active pairing dialog, with the above local binding and existing
PAKE/TLS peer evidence. This is an explicit flow, not a fallback from failed
NSD. An optional later convenience path could use legacy NSD strictly as a
routing hint, not Network provenance, and would require an independent
admission policy plus proof against wrong-link and late results. Alternatively
an interface-scoped DNS-SD implementation could provide extra provenance but
adds lifecycle, multicast and OEM compatibility work and still does not
authenticate adbd. Do not ship either automatic path without acceptance.
For reconnect, a pairing service name, IP, or reverse DNS is not authority;
require authenticated protocol identity/credentials and distinct reconnect
evidence. No network implementation or connection was enabled here.

## Independent P3 And P4 Work

Opening Host setup grants navigation only. P3 now has an unregistered,
process-local DEBUG carrier that submits once to the same exact P1 ticket,
requires the current readiness generation, and wipes both accepted and
rejected input. Rotation must reattach to the same application owner; process
death requires fresh explicit consent. Treat a launch token saved for returning
to UI as navigation only. A production component/notification decision still
needs private delivery, denial/expiry/cancel cleanup, explicit receiver
mutability and foreground-service lifetime. No Android component was registered.

P4 source inventory, not a Release allowlist or build authorization:

| Existing DEBUG entry / role | Candidate boundary and exclusions |
| --- | --- |
| `WarpnectPrivilegedServer` / `Control` | `PING`, `GET_RUNTIME_INFO`, `SHUTDOWN`. Select an exact verified installed artifact, authenticate HMAC, require UID2000, bind the launch owner/generation and demand observed teardown. No arbitrary `shell` or file commands. |
| `WarpnectPrivilegedInputProbeServer` / `InputSession` | Review only `INPUT_SESSION_{CAPABILITIES,PREPARE,START,KEY,TOUCH,POINTER,JOYSTICK,RESET,STOP,SNAPSHOT}` and control verbs that the implementation actually accepts. Remove `InputSmoke`, probe `INPUT_*` commands and smoke arguments, even though the class is shared. |
| `WarpnectPrivilegedPcmSharedRingProbeServer` / `PcmSharedRing` | Review owned ring bind, audio prepare/start/stop and close, plus authenticated runtime control. Exclude fault/replay test requests and microphone injection commands from the first production bundle. State/diagnostics need explicit bounded review. |
| `WarpnectPrivilegedVideoCaptureProbeServer` / `VideoCapture` | Review `VIDEO_QUALIFY`, `VIDEO_START`, `VIDEO_UPDATE`, `VIDEO_STOP` and authenticated control. Do not include diagnostic-only output by default. |

The current `packageDirectShellProbeDex` copies every DEBUG DEX; it cannot
be reused as a production artifact with a filtered entry-point list alone.
Next P4 gate: a separate manifest/DEX closure with only accepted transitive
classes and a negative check for smoke, Root labs, registration providers and
generic shell access. Secret transfer must remain authenticated and bounded.
Pair-only F never launches a helper; a separate explicit Host acquisition
would need its own admission before any runtime is used. P5 remains inactive.
