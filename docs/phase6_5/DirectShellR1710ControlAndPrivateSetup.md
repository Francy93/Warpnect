# R1710 DirectShell Control And Private Setup

Parent `ca26e7df14922ac0f24d8fba3663bb0cb8a1c9f7`. This work does not
authorize normal pairing, a credential writer, a privileged helper launch,
Host/Session publication or Release activation. **P2_PARTIAL_BLOCKED**.
Emulator and R0b remain suspended.

## Samsung Numeric Pairing Tuple

The exact A41/API30 and A41/API31 USB identities, firmware, user 0 and
Enforcing state were read before the observation. Wireless Debugging was
already enabled on both. The operator independently observed the Samsung
code dialog on each: the IPv4 matched the main page, the dialog port
differed from the main-page reconnect port, and reopening produced a new
pairing port. This is operator UI observation, not protocol evidence.
No address, port number, code, screenshot or view dump was retained;
no code was sent, no toggle was changed and no pairing command was run.
Closure was requested; no independent modal-close witness was captured.
The scoped before/after device inventories retained the observed
associations and debug settings.

Prepared an unregistered numeric pairing-only transport. It accepts
only the typed local IPv4/port bound to the exact work ticket and selected
Network snapshot/epoch; the normal transport's loopback guard is unchanged
unless it has the typed numeric binding. On Android the binding adapter
uses that exact `Network.bindSocket(Socket)` before connect. One transport
owns one socket and the original deadline. Address, interface and epoch
are rechecked at the connection and protocol boundaries. A matching
loss signal stops only that transport; no reuse or alternate endpoint.
There is no pairing UI/NSD/reconnect integration, retry, publication
authority or credential creation. Verified PeerInfo still originates
only from pinned TLS/PAKE; IP and port are route inputs, not trust.
Once the network invalidates, cached PeerInfo is no longer exposed by the
adapter; previous possible remote effect is not rewritten.

The JVM integration sends the real pinned protocol over a fixture-owned
loopback connection with **modeled numeric routing**. The prior physical
Network proof remains distinct, as do real numeric `adbd`, blocked TLS
write, suspend/resume and production callback registration.

## Private Setup Fixture

The opt-in `-PprivateSetupPhysicalGate=true` selects
`io.warpnect.privatesetupgate` plus its instrumentation runner, no network
permission, no Shizuku or application initializer, and just the private
non-exported Activity and receiver. It never opens a socket or acquires a
key or storage capability. The delivery method stops deterministically
before recovery. The fixture exercises Activity lifecycle, platform
PendingIntent/RemoteInput, duplicate/stale handling, synthetic elapsed
expiry, dismissal and denial. Direct delivery uses the actual framework
PendingIntent, not a simulated receiver; it does not qualify Samsung
SystemUI gestures or API33+ runtime notification permission. Readiness and
the elapsed-clock advance are test inputs. No code is written by the
fixture and nothing is restored from Activity state.

| Exact target | Delivery, rotation, stale generation and expiry | Fresh fixture with blocked channel |
| --- | --- | --- |
| A41/API30 `TESTUNIT001` | PASS | PASS |
| A41/API31 `TESTUNIT002` | PASS | PASS |

A separate runner on each API armed an actual notification without
dispatching work, deliberately terminated its own fixture PID, and
witnessed the exit before starting a separately measured process.
In both cases the old notification remained and its PendingIntent was
dispatched through the platform in the new process. It remained inert:
no owner, consent or operation was recreated. The new process removed the
old owned notification. Six ordinary method executions passed, plus two
expected armed-process exits. These are fixture lifecycle observations, not
a guarantee about Samsung SystemUI itself or a production app restore.
The synthetic input was not present in the scoped instrumentation logs;
the fixture does not implement application code persistence. No broad
system log or personal storage scan was performed.

API30's first denial harness used a package-local AppOps override. The
test did not pass and its cleanup exception hid the primary condition.
This negative remains sealed. A separate fresh installation used an
actual platform `IMPORTANCE_NONE` channel with app notifications otherwise
enabled. The passing API30 delivery case was not repeated. No global
notification settings changed. What passed is app/channel denial on
API30/31, not a physical API33+ permission denial.

Each clean run checked application process termination, removed only
the two test packages, and verified the original app identity, profile
inventory, ADB associations and debug settings. The shared ADB server
and other phones were not reconfigured. No privileged product startup
occurred. FGS and ordinary app registration remain separate.

## Minimal Control

The standalone `tools/directshell/control` source has a new entry point,
not an APK source set and not invoked by Warpnect. Only numeric command
IDs 1/2/3 dispatch `PING`, `GET_RUNTIME_INFO`, `SHUTDOWN`. It uses the
existing WNDP magic and HMAC-SHA256 pattern in an explicitly distinct v2
Control profile: a signed generation, bounded length, strict request
order and a role/artifact/generation-derived key. There is no v1 fallback
or Debug change. The entry point checks UID2000 through public APIs,
requires a single canonical DEX classpath with a matching SHA256, and
accepts the exact 32-byte secret via owned stdin rather than arguments,
logs or a disk file. The bounded endpoint listens once on IPv4 loopback,
accepts one client, closes the listener, and uses the same time budget for
non-blocking input and output. It creates no workers itself.

The reply is not proof of exit. The offline caller witness requires the
same externally observed owner, process creation identity, PID, UID,
generation, API and artifact before accepting authenticated runtime/stop
frames; endpoint, process and launcher teardown must be observed separately.
Reading the declared classpath from the helper is corroboration, not an
independent proof of ART code provenance. A future launcher must pin its
file/descriptor and process identity, drain the launch pipe, and observe
kernel termination and cleanup, not trust the reply alone. No such
Android launcher executed here. The DEX is externally stored, not in any
app, and has exactly the two program classes, the Ready interface and two
D8 synthetic adapters. The build checks actual class definitions,
disassembled references and host behavior, not only source names.
Eight host cases passed; independent builds with and without the offline
witness produced identical 12,040-byte DEX files:
`0A53DC6D79FFC7D124C2B0E09153D9858F265B16F047634AABDCE941948B80FD`.
The first verifier report preserves a split-package warning incorrectly
matched by a check over the whole `jdeps` output. The corrected check
classifies actual dependency edges and still rejects unknown edges.

### Other Roles

| Role | Independent pieces | Why no runtime artifact |
| --- | --- | --- |
| InputSession | Bounded generation/event codec (commands 67-76), typed event validation and reset policy | The actual engine imports `ReflectivePrivilegedInputManagerApi` and shares smoke and probe ownership. Extract only the pure request and state contract next; do not promote reflective injection without an accepted platform decision. |
| PcmSharedRing | Fixed ring layout and generation/HMAC metadata are separable from FD acquisition | `DirectShellPcmRingProbeExternalProviderClient` uses hidden `IContentProvider`, ServiceManager and IActivityManager to acquire app descriptors. A production owner must prove same-generation SharedMemory and both event FD lifetimes and exclude test signal, faults and microphone commands. |
| VideoCapture | Generation/dimensions/capture policy can be separated from the Surface lease | `DirectShellVideoCaptureProbeExternalProviderClient` uses the same hidden external-provider acquisition, and the probe dispatches 84. A separate accepted Surface handoff/ownership and release witness is needed before removing the probe gateway. |

No media or input gateway changed. The pure pieces are extraction
candidates, not new runtime or OEM qualification. Do not package the
historic Debug servers to fill these roles.

## Keystore Decision

The accepted D-P1-D-P4 record remains unchanged. The prior AOSP
review is sufficient to rule out a public create-if-absent promise for
the current AndroidKeyStore writer. No new key operation was performed.

| Option | Guarantee and overwrite risk | Dependencies, recovery and qualification |
| --- | --- | --- |
| A. Strict provider | Absolute no-replace only if the provider independently reports certified absence and implements atomic insert/reject. Existing lookup followed by generation is not this. | Requires a supported authority/provider on each API30/31 OEM, no hidden API assumption. Keep the existing lease, journal, authoritative reread, durable encrypted anchor, and fail-closed orphan handling. Qualify occupied/absent/error distinctions, two competing writers, acknowledge-loss and restart, no replacement, Android storage and TLS interoperability. RFC accepts this guarantee already; qualify the provider binding before adding it. |
| B. Random one-use 256-bit alias | Random independent aliases bound accidental collision by approximately `n(n-1)/2^257`. Android can still replace on actual collision or alias precreation. Not no-replace. | Public AES usable on API30/31, but relies on trustworthy entropy and a dedicated single writer namespace. Needs a versioned authenticated durable intent, one generation, ambiguity fence and no adopt/delete of uncertain entries. Requires explicit RFC amendment and operator acceptance, collision/fault tests, restart and migration qualification. Not selected. |
| C. Existing-only deployment | The app never creates, replaces or adopts an unknown alias. A pre-existing AES wrapping capability must be independently provisioned, attested to the exact namespace and explicitly granted; the workflow does not itself supply first enrollment. | Requires an approved separate provisioner/trust domain with enforceable non-replacement and provenance. AndroidKeyStore AES is opaque; a shell copy, cross-UID alias guess or TLS RSA substitution is not a solution. Keep exportable PKCS8 for the pinned TLS signer and the authenticated AES-GCM record. If deployed, specify import authority, rollback/revocation, crash/unknown acknowledgement and proof of explicit ownership in a separate amendment. Qualify both firmware paths without exporting the wrapping key. |

Recommend A as the security requirement and C only as a separately
reviewed deployment mode if a real provisioner exists. Keep the production
writer disabled. Do not treat B as an implementation shortcut.

## Software And Isolation

Fresh complete Debug JVM: **1,690/1,690** methods in **208** suites,
no errors, failures or skips, including the three new numeric transport
cases. AF25, the earlier 69 API30 methods and the two physical Network
cases were not rerun. After formatting, 55 focused JVM methods passed.
Numeric transport and test instruction disassemblies matched across
the formatting change. The 67 inherited Debug KtLint findings are
identical to R1709's. The final changed AndroidTest and scoped Kotlin
checks have no new findings; the inherited Debug check remains non-green.
Ordinary Debug and unsigned Release assembled.
Their manifests retain `io.warpnect` and omit P3 registrations and the
temporary package. Inspecting actual DEX definitions found no private
P3 classes or numeric adapter in Release and no Control or instrumentation
test classes in either normal APK. The three native ABIs were retained;
Release assembly is not runtime qualification. CXX5304 remained a
nonfatal SDK metadata warning. The previous Windows restart is still
unattributed; boot identity did not change during this suite.

External receipts: `C:/wg-directshell-r1710-evidence`. This includes
the original P3 build and test negatives, APK signatures, per-device
test/cleanup records, entire XML inventory and hashes, normal APK
DEX inspection, exact Control DEX and independent reproduction.

## Remaining Gates

| Phase | Verified boundary and next unresolved gate |
| --- | --- |
| P1 | Prior Android owner and physical drain remain intact. Normal application acquisition and Android suspend/connect policy are not enabled. |
| P2 | Two numeric tuple observations and offline transport/protocol composition. Need a certified writer, approved production Network admission/callback owner, pair-only real adbd, blocked-write and suspend qualification. |
| P3 | Isolated Activity and actual carrier on the two A41 handsets. Production registration, SystemUI and API33+ behavior, FGS/lifetime and supported OEM qualification remain separate. |
| P4 | Narrow Control artifact and host witness. Next independently qualify exact UID2000, actual DEX origin, generation-bound HMAC and physical exit on an authorized target. InputSession, PCM and video are still behind platform/ownership decisions. |
| P5 | Inactive. No production Host, Session, Release onboarding or provider fallback. |
