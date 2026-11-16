# DirectShell Debug Pairing: A41 Evidence and Consolidation

## Observed On Hardware

On 2026-11-12, r1702 completed one user-authorized operation from the Host UI
of the side-by-side Debug APK on the Galaxy A41 SM-A415F, Android 12/API31:

1. Temporarily enabled Wireless Debugging through Android Settings, with no
   active Wi-Fi ADB transport. The new TLS listener was observed in the target
   TCP tables; its port differed from the earlier listener.
2. Before asking for a code, resolved the expected connect instance through an
   actual NSD browse callback on the same local Wi-Fi address. The platform SRV
   result matched the listener. This attests handset NSD visibility, not an
   independent over-the-air advertisement.
3. Consumed one notification authorization, completed real adbd TLS/PAKE, and
   bound the next NSD resolution to the freshly authenticated GUID and the
   pairing address. Reconnection completed using the existing ADB credentials.
4. Launched the temporary shell helper, verified positive and negative HMAC
   behavior, and completed authenticated shutdown with no helper residue.
   There was no Host or Session launch and no Root/Shizuku/Sui activation.
5. Revoked only the test association, removed only the test APK, and restored
   Wireless Debugging to Off. The original APK, USB Debugging and both existing
   ADB associations were preserved.

Outcome: `PASS_PAIR_CONNECT_LAUNCH_HMAC_SHUTDOWN`. The tested APK was
`io.warpnect.smoketest`, SHA256
`433DF094FDE5B55BF9599A6B22FBCDF7D7844203E8B4BCBCE39BF89BECFA50EB`.
The external report, sanitized trace, listener, shutdown and cleanup receipts,
and 18-path source archive are in
`C:/wg-pairing-phone-smoke-r1702-20261108/`. The trace SHA256 is
`47AA8AD0E337AC95CE2E51EF99E3E4446764CBA9F7E3065225E449185183A1E5`.
The earlier duplicate entry and discovery failures (r1696-r1698), r1699/r1700
read-only findings and inconclusive r1701 unicast query remain historical facts.
r1702 does not establish a root cause for those timeouts or a portable benefit
from toggling Wireless Debugging.

## Consolidated Scope

The ordinary Debug path retains:

- An explicit Host launch grant tied to a boot and elapsed-time window.
  Package-bound one-shot notification actions and an app-private atomic
  consumed record reject duplicates, stale deliveries and reuse after process
  recreation. Result delivery is tied to the active worker lease.
- For local credential state, only a wholly absent identity requests pairing.
  Partial, malformed or unreadable stored material fails closed without
  replacing credentials or regenerating a missing wrapping key while reading
  or recording the paired GUID.
- Authenticated TLS/PAKE peer-info supplies the GUID, not the pairing service
  label or a serial-number guess. Connect discovery retains the GUID/allowed
  DNS-SD conflict alias, local address and port checks, and the fresh pairing
  address constraint. DNS-SD metadata alone is not cryptographic trust.
- An operation-scoped MulticastLock, serial resolves, callback/generation
  fences and the existing bounded discovery budget. The exact-name resolve
  shares that budget; it is not an independent fallback. API31 cannot
  publicly cancel an already-dispatched resolve; late results cannot be used.

The regular package and ADB host label are unchanged. The A41 historical-GUID
preflight, smoke package, manifest overrides and any USB port diagnostics remain
external to the regular APK. No automatic Wireless Debugging toggle, guessed
port, new transport fallback, production credential migration, or Release
setup backend is introduced. The shared Host UI only passes the consent grant
when its source-set adapter can provide a setup Intent; Release returns null.

The hardware result belongs to the sealed side-by-side r1702 APK. The ordinary
Debug build is a software-verified consolidation, not a new installation or
physical run of those exact bytes. It preserves the hardware-proven path, not
an always-available NSD guarantee or a new reconnect certificate pin.

## Consolidation Verification

Source/test commit `dbfa8f4c571e118aaeb81bf203cea2b505423930` contains 14 paths
and directly descends from the r1634 checkpoint
`747724268ecb8506d61905e0e7f63160c3ba0140`. The tested blobs match the commit.
The original dirty Desktop checkout and the isolated hardware worktree are
not the integration targets. The r1696/r1697/r1698/r1702 archives and five
historical APK hashes were rechecked before consolidation. No push or merge.

| Fresh software gate | Result |
| --- | --- |
| Unfiltered `:app:testDebugUnitTest` | 1,632/1,632 in 202 suites; zero failures, errors or skips. The 11 relevant pairing/Host suites account for 131 tests within this run, not a second execution. |
| Offline media regressions | 40/40. |
| `:app:assembleDebug`, `:app:assembleRelease` | PASS for arm64-v8a, armeabi-v7a and x86_64; Release lint-vital task passed as part of assembly. The existing SDK metadata warning CXX5304 was nonfatal. |
| `:app:verifyOwnedPairingReproduction` | PASS, unchanged pinned AAR SHA256 `BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0`. |
| Packaged APK guards | Both package IDs are `io.warpnect`; expected Debug signing certificate, nonexported setup Activity and pairing service, helper and libadb in Debug; no tested pairing Debug/ADB classes in Release; no temporary diagnostic markers in either APK. |

Debug APK SHA256 `0D50A35C78AEB0F72A8733F9465EBD85F84B258E609EDCC22624548B3563B5EC`;
unsigned Release APK SHA256 `68CFC2F88DD65E5554A2971CFA32E5AED9DC124E530A96F6108C7FBB33D7D9ED`.
Build/replay logs, test XML identities, source freeze, verified source backup,
APK guards and Git receipt: `C:/wg-pairing-debug-r1703/evidence/`.
No AndroidTest, CTest, separate Release JVM run or hardware execution was performed.

## Remaining P2 Acceptance

Overall: **P2_PARTIAL_BLOCKED**. This is separate from the canonical
[API30 fixture checkpoint](DirectShellPairingAndroidApi30CompositionFixture.md#2026-11-03-independent-software-checkpoint-review-r1634)
and [pair-only architecture](DirectShellReleasePairingArchitecture.md).

| Gate | Remaining evidence |
| --- | --- |
| Android P1/P2 synthetic composition | Execute the exact-API30 localhost fixture: real AndroidKeyStore AES test alias, private storage, authenticated recovery, file/directory sync, native no-replace publication, separate P/S, and owned socket/worker/storage/observer drain. The r1634 fixture has 69 methods compiled and zero executed. |
| Production identity and lifecycle | Explicit decisions D-P1-D-P4 (RSA/AES provisioning, adoption and recovery); application-owned clock/timer/consent wiring and Android process lifecycle. No automatic migration or production key change. |
| Production transport and endpoint | Network/NSD binding, the pair-only P1/P2 path against real adbd, Android blocked TLS-write cancellation and suspend/connect qualification. One legacy Debug self-bootstrap success does not qualify these. |
| Compatibility and onboarding | Exact API/OEM/device coverage, Release pairing setup/backend and admission. A Release APK build is not Release runtime qualification. |

Host-side synthetic and owner-drain checkpoints remain verified for software
only. Historical Windows lease failure remains unattributed.

## Smallest Next Runtime Gate

Separately authorize the **physical Galaxy A41 Android 11/API30** for the
existing Android synthetic fixture, not another real pairing. First verify
its exact serial, API30, fingerprint, ABI, runner, ordinary UID and enforcing
SELinux, and establish an empty isolated test target package/private directory
with no existing data or alias. Preserve any installed Warpnect. Inspect
inherited startup providers; unexpected privileged behavior is a stop.

Run only `af02ExistingOnlyKeyReaderNeverProvisions` as the test-alias preflight,
then `af08ActualAndroidSyntheticRpsdfAndReadOnlyRestart` once if the preflight
passes. AF08 is the smallest positive witness of the actual combined Android
storage/native/owned-protocol/drain path. It uses the synthetic localhost peer,
not adbd, NSD, or production credentials. Stop on first failure or ambiguous
ownership; clean up only fixture-owned state after drain. Passing would cover
that exact Android P/S/D/F run, not power loss, all 69 methods, API/OEM breadth,
the emulator's R0b, or Release. If no safe side-by-side target/runner or empty
test sandbox is available, stop before installing. No execution is authorized
by this recommendation. The emulator and R0b remain suspended.
