# DirectShell: Physical Android API30 Gates

Scope: SM-A415F Android 11/API30, ARM64, build `A415FXXS1CVC1`, serial
`TESTUNIT001`. The test target and runner use an isolated user-0 package and
ordinary app UID/domain. No OEM breadth, production trust, Release readiness,
or power-loss durability follows. Overall: **P2_PARTIAL_BLOCKED**.

## Evidence And Coverage

- r1702 remains a separate Debug/API31 real-adbd pairing and helper smoke, not
  production P2 or Release. r1703 is the Debug consolidation (`dbfa8f4`,
  `8f8cb12`).
- r1704: AF02 and AF08 PASS on the exact API30 handset. Evidence in
  `C:/wg-af30-r1704/evidence/resume-gate-20261113/`. The first out-of-space
  rejection is historical and preserved.
- r1705: 55 methods passed with the sealed r1704 pair; AF12 initially failed
  on an incorrect test expectation. Raw logs, per-method SHA256, the AF12
  failure, and the verified first cleanup are in
  `C:/wg-af30-r1704/evidence/mission-r1705/`. A shared ADB interruption stopped
  the storage group; S02 was not rerun: complete device-side JUnit and teardown
  output plus revalidated ownership and empty storage were retained. The
  interrupted host exit receipt was unreadable and is not claimed as exit 0.
- Corrected AF12 and M01-M11 then passed with the new isolated APK pair, for
  **67 distinct PASS in r1705** and **69/69 across r1704 and r1705**. This is
  aggregate coverage across two artifact cohorts, not a single all-green run.
  Corrected artifact hashes, logs and final teardown are under
  `C:/wg-af30-r1704/evidence/mission-r1705/corrected/`.
- Both pairs were removed after drain; final checks found no fixture package,
  process or retained package data. The original APK hash, UID/profile state
  and observed ADB key-set digest and settings were unchanged. The host ADB
  instance was not started or stopped by the campaign. The A41/API31, S7 and
  S9 were not operated on in r1705.

| Group | Cases | What it does and does not prove |
| --- | --- | --- |
| Passive, consent and ticket | AF01, AF19, AF20 | Passive construction, stale-generation rejection, virtual expiry. Not the Android application owner or wakeup timing. |
| Key, pre-protocol recovery and exclusion | AF02-AF06, AF21, AF23 | Fresh test aliases, no implicit provision/repair, real recovery reads and sync before protocol; the sync failure is injected. |
| Collision, positive composition, unknown publication and sync | AF07, AF08, AF12, AF13 | Real native collision and publication, separated P/S, authenticated loopback and reconciliation; lost acknowledgements are injected. AF12's original failure is not erased. |
| Transport, authentication and cancellation | AF09-AF11, AF22, AF24 | Real loopback TLS/PAKE stalls/negative inputs. No platform suspend, blocked TLS write, NSD or real-adbd enrollment claim. |
| Drain and deadline | AF14-AF18 | Real held resources and owned socket; modeled scheduler, missing receipt and clock advancement. |
| Android storage and JNI | S01-S18, N01-N08 | Real app-private storage/AES, no-replace, corruption and restart combinations, bounded concurrent reads. N03 uses a modeled API29 input on a real API30 phone. N04 reported the test AES key non-exportable and inside secure hardware; no production key or RSA hardware claim. |
| Storage lifecycle | L01-L08 | Actual private storage, modeled protocol, receipts and time. No real-remote effect. |
| Multiprocess | M01-M11 | Existing finite Binder protocol with same-UID verification, non-exported component and test-only root/alias. M08 kills only the service's own verified disposable process; no arbitrary PID or normal Warpnect process. |

The first AF12 run observed the committed file and missing pending file, and
asserted publication `UNKNOWN` before reaching the incorrect phase assertion.
P1 deliberately reports `COMMIT_OUTCOME_UNKNOWN` even after D, and retains the
admission fence. The fixture now expects that exact phase without changing P1,
publication semantics, the drain assertion or the denied-replacement assertion.

## Separate API31 KeyStore Probe

On A41 SM-A415F/API31 ARM64 build `A415FXXS8DXE2` (`TESTUNIT002`), the single
`existingOnlyReaderClassifiesOpaqueAesAndAuthenticatesAad` method passed in the
same isolated packaging under a fresh app UID and ordinary SELinux domain.
The target's KeyStore inventory stayed empty across missing-key reads. The
fixture explicitly created one test alias; the actual Android
[KeyInfo security level](https://developer.android.com/reference/android/security/keystore/KeyInfo#getSecurityLevel())
was 1 (`TRUSTED_ENVIRONMENT`), and the reader independently exposed the same
backing for the non-exportable AES256 handle. AES-GCM authenticated the valid
payload and rejected altered AAD. The fixture proved an empty alias inventory,
no owned descriptors and no fixture files after teardown. Both temporary
packages and their processes were absent; the original app APK/UID, existing
runner, two observed ADB keys and debugging settings matched the new baseline.
No production alias, peer connection, real pairing or composition was run on
API31. This does not extend the 69-method API30 cohort or r1702's Debug claim.
Receipts are in `C:/wg-af30-r1704/evidence/mission-r1705/api31-key/`, including
the pre-install host process-list argument failure (no method attempted).
The APK used on-device contains the test as checkpointed at `22f190e`.
The later lint-only follow-up replaces its redundant `@RequiresApi` with a
local SDK check, retaining the exact API31 test guard and the same assertions.
The corrected annotation/check was not a second handset run.

## Software And Packaging

Pinned libadb AAR reproduction, fixture Debug/AndroidTest assembly and the
focused JVM run passed: **284 tests across 10 suites**, zero failures, errors
or skips. The API31 follow-up assembly and expanded regression passed:
**327 tests across 15 suites**, including the Debug discovery, identity,
authorization and pairing regressions, with zero failures, errors or skips.
The 40 offline media replays also passed. The packaging opt-in
`-Papi30PhysicalGate=true` uses ARM64 and
test-only manifests, does not include app startup or privilege providers, and
registers only the private same-UID fixture service. Release assembly is
unavailable in that mode. Ordinary Debug/Release manifest processing passed.
Ordinary unsigned Release assembly and the compiled artifact audit passed:
the r1703 manifest matches exactly, the three ordinary ABIs are retained, and
both DEX files exclude the checked Debug/fixture/libadb classes. The Release
APK hash differs from r1703, so this is not a byte reproducibility claim.
No production source, JNI, Keystore or protocol behavior changed. The first
ordinary ktlint aggregate failed on seven Debug files unchanged from r1703;
see `ordinary-style-inherited.json`. This is not a claim of an all-green
style gate.

The initial ordinary Debug Lint found an inherited API-floor mismatch:
`InetAddresses.parseNumericAddress` requires API29. Its Debug caller now
explicitly rejects operation below the already-supported API30 minimum, before
parsing or making a connection. This adds neither a DNS fallback nor a change
to GUID/local-address binding on supported versions. A new JVM test covers
the early rejection; the initial XML report is preserved. Final ordinary
Debug Lint passed with the same 127 warning identities and no errors;
ordinary Release Lint passed with 34 warnings and no errors. The intermediate
instrumentation-annotation lint failure and its correction are also retained.

The local source checkpoints are `d446643` (API30 fixture), `e6376a0`
(Debug API floor) and `22f190e` (API31 key reader probe), all descended from
r1703. `273d96a` is the API31 instrumentation lint follow-up. The external
`physical-runtime-inventory.json` hashes all 69 API30
method logs, the first AF12 negative and the separate API31 method. There
was no additional real Wireless Debugging enrollment or host-session launch.

## Remaining Decisions And Dependencies

| Increment | Implemented / tested | Still needed |
| --- | --- | --- |
| P1 | Pure coordinator and observed protocol/commit/drain semantics checkpointed; composed fixture exercises consent, original work ticket, publication fencing and cancellation. | Android owner, clock/timer and process lifecycle policy and integration. Android's [elapsed time](https://developer.android.com/reference/android/os/SystemClock) includes deep sleep; [Handler delivery](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,long)) does not. A delayed callback cannot confer a new work budget. |
| P2 | Host software checkpoints and scoped Android physical evidence in the groups above; exact runtime limitations retained. | D-P1-D-P4, admitted Network/NSD endpoint, authenticated pair-only path against real adbd, blocked-write and suspend/connect evidence, and remaining API/OEM qualification. Keep the current transport loopback-only until the Network admission is reviewed. |
| P3 | Existing Debug pairing is not this production increment. | Private production setup, token-bound action/FGS with same owner, lifecycle/consent policy, dependency review. |
| P4 | Prior Debug runtime/bootstrap/capability evidence, not production integration. | Reviewed closed Release runtime/helper extraction with HMAC, UID2000, generation and admission/cleanup; no generic command interface or media changes. |
| P5 | Not activated or qualified. | Both P3/P4 approved bundles, Release adapter activation and signed hardware qualification under the accepted addendum. |

The r1627 boundary review survives in snapshot `fe97553`, path
`docs/phase6_5/DirectShellPairingAndroidCompositionBoundaryReview.md`. Its D-P
questions remain open. Recommended review scope, not approved behavior:

- **D-P1:** explicit first-enrollment authority only; one cross-process lease
  covering admission, key creation and recovery-anchor publication; no replace.
- **D-P2:** fail closed on orphan alias, unreadable records or key loss; no
  automatic repair, deletion or restored consent. Qualify the crash states.
- **D-P3:** preserve current exportable PKCS8 software RSA inside an encrypted
  record with opaque AndroidKeyStore AES. No library/signer change by default.
- **D-P4:** no automatic adoption or migration, especially from Debug. First
  enrollment stays revision zero, with preexisting material left untouched.

The current `DirectShellCredentialStorage.begin` expects an identity and an
existing key, and obtains the stable namespace lease only after preparing the
encrypted bytes. Adding a key generator before this call would leave the
alias-creation race outside the lease. An approved successor needs one
admission transaction using the same stable lease for inspection, key creation
and authenticated anchor reread, with an explicit handoff to the existing
operation. It must not try to acquire the same non-reentrant lease twice.
Recheck the exact P1 ticket/deadline before each next irreversible step; an
elapsed deadline does not erase what was already written.

| First-enrollment observation | Recommended disposition, pending D-P approval |
| --- | --- |
| Known empty namespace, alias absent, exact fresh authorization and lease held | Only state eligible for first creation; existing-key reads remain read-only. |
| Alias exists without an authenticated anchor (including ambiguous create) | Block and retain; do not adopt, replace, delete or automatically try creation again. |
| Recovery/pending/committed record exists but key is missing or unusable | Block and retain ciphertext; do not generate a substitute key or delete the record. |
| Anchor exists and authenticates, but previous operation or publication outcome is uncertain | Inspect and report reconciliation only; never restore previous consent, R or D. |
| Foreign, malformed, partially written or unreadable artifacts, or uncertain lock ownership | Block. No peer socket, fallback or implicit repair. |
| Authenticated committed record already exists | Reject first enrollment; any later use or adoption has its own authority and evidence. |

The D-P approval would unlock only the production storage admission design and
implementation. It would not by itself approve application startup, peer
selection, a real adbd test, Release exposure or hardware qualification.

Review the Android owner/scheduler lifetime before wiring an application owner.
Use suspend-inclusive time for checks; decide how resume signals and a timer
request physical stop without a wake lock, late grant or fabricated D. Review
typed Network/epoch/callback admission separately. Bind the owned raw socket
using [Network.bindSocket](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket))
before connect; DNS-SD is routing input, not authentication. This is not
permission to create a second socket, loosen loopback, or start real pairing.

For that later admission review, require one scoped pairing-discovery result
with exact service type, resolved numeric address and port, same-device
address membership on the selected Network, and the current owner's ticket
and network epoch. Reject lost/replaced Networks, unscoped or ambiguous
addresses and stale callbacks before connect; if invalidation arrives
during work, request stop of the same owned socket and preserve conservative
effect knowledge. Keep discovered service names separate from authenticated
PAKE peer identity. In particular, no Debug remembered GUID or port imported
from USB is production pairing authority. No fallback or migration between
Networks is proposed.

For the first isolated owner increment, the recommended policy is no wakeup
alarm, no wake lock and no background continuation entitlement. Absolute
elapsed deadlines remain authoritative at each sensitive boundary; resume
and owner disposal request stop and only the existing worker evidence proves
D. A parked timer or sleeping process is not evidence of physical closure at
the deadline. Confirm whether this limitation is acceptable before wiring
the owner; if stop during suspension itself is required, it is a different
Android power/lifecycle design. Genuine blocked TLS writes also remain open:
loopback stalls in these cases are not a witness of TLS write backpressure.

The smallest next implementation after that decision is an unregistered,
key-free owner using the original P1 work context and the existing drain
handoff. Acceptance must cover missed/late delivery after an elapsed-time
jump, owner disposal before and during transport, duplicate stop delivery,
stale callbacks across generations and a late physical drain. Verify that
destruction or process restart does not restore consent or synthesize D.
Keep production key creation and Network admission out of this first delta.
