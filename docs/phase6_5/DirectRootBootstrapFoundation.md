# DirectRoot Bootstrap And Recovery Authority Foundation

## Scope And Provenance

Starting application checkpoint: `5967608`; Root laboratory checkpoint: `1766fa1`.
Branch: `codex/directshell-production-runtime-integration`. Master remains
`032b592`. The new implementation is intentionally uncommitted for review.

This slice implements a DEBUG/laboratory acquisition adapter and a finite UID0
helper. It does not implement production root-manager authorization, any Root
media/input capability, or any remediation. Root is NOT selected for Host plans.

```text
Normal Warpnect app (ordinary UID)
  -> private generation / secret / installed-base.apk SHA-256
  -> external, emulator-only RootHelper.ps1
  -> explicitly verified adb-root UID0
  -> WarpnectRootHelper app_process from the exact installed base.apk
  -> authenticated loopback control / observed UID0 identity
  -> DirectRootRuntime / volatile RFC-006J observation
  -> RootRecoveryAuthority availability, supported actions = NONE
```

`LABORATORY_EXTERNAL_ROOT` is not `PRODUCTION_ROOT_MANAGER`. The API26 AOSP
`/system/xbin/su` exists but the ordinary app cannot execute it. ADB-root capability
does not prove app-originated Root acquisition or root-manager consent. Future
production acquisition must cross explicit root-manager authorization; it must
not depend on emulator ADB or silently use Root merely because Root exists.

## Reused And Root-Specific Components

Reused unchanged framing/authentication primitives: `DirectShellProbeProtocol`
(WNDP/version 1, maximum 4 KiB payload, HMAC-SHA-256, positive request IDs),
`DirectShellProbeIpv4Socket` (127.0.0.1 only, bounded reads/writes), runtime API
inspection and installed APK packaging. `DirectShellSecretFile` gains a closed
Root-specific private-file path; existing DirectShell paths are preserved.

Root-specific pieces: `RootControlLease`, `WarpnectRootHelper`,
`DirectRootLabReceiver`/`LaboratoryRootBootstrap`, `RootLocalControl`,
`DirectRootRuntime`, `RootRuntimeBootstrap`, `RootControlSession`,
`DirectRootRuntimeRegistry`, and `RootRecoveryAuthority`.

The Root helper is DEBUG-only and requires actual `Process.myUid()==0` and
`Os.getgid()==0`. It does not manufacture an Application/Context/package identity.
There is no generic command, file, launch, settings, property, process-kill or
descriptor operation. Other existing protocol commands are explicitly unsupported.

### File Inventory

New application files:

* `app/src/main/java/io/warpnect/platform/privileged/runtime/DirectRootRuntime.kt`
* `app/src/debug/java/io/warpnect/debug/directshell/DirectRootLabController.kt`
* `app/src/debug/java/io/warpnect/debug/directshell/WarpnectRootHelper.kt`
* `app/src/debug/java/io/warpnect/debug/directshell/RootControlLease.java`
* `app/src/test/java/io/warpnect/platform/privileged/runtime/DirectRootRuntimeTest.kt`
* `app/src/test/java/io/warpnect/debug/directshell/RootControlLeaseTest.java`

Existing application files changed:

* `app/src/debug/AndroidManifest.xml`: DUMP-gated laboratory receiver only.
* `app/src/debug/java/io/warpnect/debug/directshell/DirectShellProbeProtocol.java`:
  internal DEBUG Root capability-query command; framing/HMAC unchanged.
* `app/src/debug/java/io/warpnect/debug/directshell/DirectShellSecretFile.java`:
  closed Root-private-file variant; prior DirectShell path unchanged.
* `app/src/debug/java/io/warpnect/debug/directshell/DirectShellRuntimeAutoRegistrationProvider.kt`:
  abandoned Root handoff cleanup only, no Root acquisition.
* `app/src/main/java/io/warpnect/platform/privileged/readiness/AndroidHostReadinessWork.kt`:
  passive Root authority observation and refresh, never Root Host admission.
* `app/src/main/java/io/warpnect/platform/privileged/readiness/PrivilegedRuntimeReadinessModel.kt`:
  retains typed Root foundation/provenance independently of capability selection.
* `app/src/main/java/io/warpnect/platform/privileged/runtime/PrivilegedRuntime.kt`:
  comment clarifies capability provider versus authority; behavior unchanged.

New tools: `tools/root-lab/RootHelper.ps1`, `tools/root-lab/Test-RootHelper.ps1`.
Documentation: this report, `AndroidRootLab.md`, Phase 6.5 `README.md`,
`docs/FutureRoadmap.md`, and RFC-006J. The existing RootLab environment tooling
was checkpointed unchanged. No network/media contract or native source changed.

## Authentication And Artifact Trust

The normal app generates 32 unpredictable secret bytes and a fresh positive
generation. It hashes its installed APK. The harness verifies its exact local APK
hash against `pm path io.warpnect` and the device-side SHA-256. The helper checks
its own `java.class.path` installed-base.apk path and SHA-256 again. The app checks
the authenticated helper's implementation/version/generation/hash/PID/UID/GID.

The secret is written with owner-only access to the app-private generation path
`files/root-lab/<hex-generation>.secret`. The external UID0 harness never reads or
prints its contents; it launches the fixed main class. The helper consumes and
unlinks that one file. No secret is in arguments, broadcasts, logs, or capability
evidence. `request.json` contains bounded NON-secret launch metadata only.
Actual API26 files were 32 bytes, mode `0600`, owned by the ordinary app UID10065.

Each connection receives a fresh, HMAC-authenticated challenge. HELLO must bind
the expected generation and that challenge, then all commands must include the
generation and strictly increasing request IDs. Requests/responses retain the
existing HMAC. One authenticated owner is allowed; a duplicate is rejected.
An unsupported operation cannot invoke a privileged adapter.

Loopback TCP does not provide Binder caller UID or Unix peer credentials.
App identity is therefore possession of private generation material, NOT an
assertion of an OS-authenticated socket UID. Helper identity relies on the trusted
laboratory launch chain, verified artifact, helper-side identity checks and HMAC;
this is not remote attestation against a malicious Root process. Authorized ADB,
the debugger of this DEBUG APK, the app UID and existing Root are trusted in this
laboratory. No LAN listener or production laboratory endpoint is added.

## Lifecycle And Readiness

The app owns the generation and authenticated control connection. The helper has
a maximum 90-second laboratory generation, 10-second idle limit and bounded
control parsing. App heartbeat is once per second. Authenticated shutdown or
owner connection loss ends the helper; it cannot be adopted by a restarted app.
Canceled preparations delete their private files; the app also expires an unused
preparation. Temporary stale credentials exist only in memory during the explicit
negative-generation test and are erased on final shutdown.
On app-process startup the DEBUG provider removes only abandoned Root handoff
files; it never reloads them as authorization. No Root acquisition is automatic.

Typed volatile states distinguish implementation absence, production bootstrap
not implemented, lab bootstrap available/helper absent, bootstrapping, helper
ready, bootstrap failure, helper loss, released and future authorization required.
Ordinary physical devices report production bootstrap NOT IMPLEMENTED, never an
invented root-manager permission prompt.

RFC-006J observes Root availability independently. Root changes refresh passive
readiness; no observer acquires Root. Root authority READY does not grant a Host
plan: all RFC-006I Root capability cells remain NOT_IMPLEMENTED, the existing
planner and provider acquisition remain unchanged, and persistent evidence still
rejects positive DirectRoot capability records. Authority has no supported
remediation actions; the future finite taxonomy is not executable.

## Reproduction

Build the exact DEBUG APK, install it on the verified API26 AVD, then:

```powershell
.\tools\root-lab\RootLab.ps1 -Action Start -Api 26
$serial = 'emulator-5556' # Verify the AVD name/API/qemu identity, not this port alone.
.\tools\root-lab\RootHelper.ps1 -Action Prepare -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action Start -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action NegativeAuth -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action NegativeGeneration -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action Acquire -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action Duplicate -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action Inspect -Serial $serial
.\tools\root-lab\RootHelper.ps1 -Action Stop -Serial $serial
```

Use `Invalidate` instead of Stop only for the explicit stale-credential test,
then Prepare/Start/NegativeAuth/Acquire for the new generation. Finish with Stop.
The harness always uses an explicit emulator serial, requires the exact
`Warpnect_Root_API26` AVD, qemu=1, API26 and matching installed APK. It has no
automatic wipe or broad kill. API25 remains untouched and minSdk stays 26.
Run the bounded qualification with:

```powershell
.\tools\root-lab\Test-RootHelper.ps1 -Serial $serial
```

`LoseHelper` is a harness-only failure test: it verifies the current generation,
exact process name and UID0, then terminates only that Warpnect helper. It is not
a Root-helper RPC or a remediation action. `NegativeUid` requires unrooted adbd
and proves that the fixed helper rejects UID2000 before opening its endpoint.
Return adbd to shell with targeted `adb -s <verified-emulator> unroot`, verify
cleanup, then stop with RootLab. Root helper startup itself is externally owned,
unlike the separately validated app-owned DirectShell self-bootstrap.

## Validation Results

### Artifact And Environment

Final DEBUG APK, built from application checkpoint `5967608` plus the uncommitted
foundation diff (Root lab checkpoint `1766fa1`):

```text
SHA-256: 113A334020D757FE96FB2BAAB6747FDB907FA4CDE2A0AFBB8A17CC462043AAD4
Size: 33,526,313 bytes
Package/version: io.warpnect / 1 / 0.0.1
ABIs: arm64-v8a, armeabi-v7a, x86_64
minSdk/targetSdk/compileSdk: 26 / 35 / 35
```

Installation completed before qualification. The harness verified the installed
`base.apk` hash before every operation; the helper independently verified it.

Target: `Warpnect_Root_API26`, `emulator-5556`, AOSP/default x86_64 r1,
Android 8.0.0/API26, `ro.kernel.qemu=1`, userdebug/test-keys:

```text
Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys
```

Natural SELinux remained **Enforcing**. No AVD configuration, snapshot, partition,
SELinux policy or production platform floor was changed. API25 was not booted or
used for Warpnect DirectRoot; its existing lab configuration remains intact.

### API26 Qualification

Formal qualification: **8/8 cases passed, no retries**. Four of these acquire an
authenticated UID0 authority; the other cases exercise truthful unavailability
and rejection. Two additional resource-check acquisitions passed **2/2**.
Thus authenticated acquisition was **6/6** on the final exact APK, with five
formal helper starts (including idle expiry) and two resource-check starts.

| Case | Actual result |
| --- | --- |
| Generation A, wrong HMAC, wrong generation, duplicate owner, invalidation | PASS; bad HMAC closed as ProtocolException, correctly authenticated wrong generation rejected, owner conflict rejected, shutdown released endpoint |
| Generation B, prior-generation secret, normal shutdown | PASS; fresh generation and secret, stale credentials rejected, new owner authenticated |
| App process force-stop/restart | PASS; owner EOF ended old helper; restarted app reported PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED/generation=null, never adopted old authority |
| Current helper loss | PASS; only verified helper PID3741 terminated by lab harness; heartbeat changed state to HELPER_LOST and cleared authority |
| Acquisition without a launched helper | PASS; BOOTSTRAP_FAILED after 15.079 seconds, no false READY or Host capability |
| Unowned helper plus invalid authentication | PASS; invalid caller did not refresh the 10-second idle limit; helper and listener disappeared |
| Preparation canceled before launch | PASS; private handoff directory empty |
| Helper launched through unrooted adbd | PASS; FAIL_ROOT_UID_REQUIRED uid=2000 gid=2000 before secret access/listener creation |

Actual helper-side identities (protocol version 1, implementation
`warpnect-root-foundation-v1`, provenance `LABORATORY_EXTERNAL_ROOT` throughout):

| Purpose | Generation | PID | UID/GID | SELinux | Port | Owned FD count |
| --- | --- | --- | --- | --- | --- | --- |
| A | 8307825500211064722 | 3180 | 0/0 | u:r:su:s0 | 40960 | 30 |
| B | 2190875940695075377 | 3394 | 0/0 | u:r:su:s0 | 40831 | 30 |
| App restart | 3357569469271055551 | 3556 | 0/0 | u:r:su:s0 | 45640 | 30 |
| Helper loss | 1336271292601786868 | 3741 | 0/0 | u:r:su:s0 | 43358 | 30 |
| Idle expiry | 8084789921400840904 | 4015 | 0/0 | u:r:su:s0 | 41331 | Not sampled; intentionally unowned |
| Resource cycle 1 | 9028191509943865077 | 4396 | 0/0 | u:r:su:s0 | 38784 | 30 |
| Resource cycle 2 | 869460157261303048 | 4533 | 0/0 | u:r:su:s0 | 36223 | 30 |

During A, device-local log timestamps show helper READY at 21:58:11.633, authenticated
identity/control at 21:58:16, RFC-006J Root READY with authority available and all
capabilities NOT_IMPLEMENTED at 21:58:16.310, and invalidation/shutdown at
21:58:19. The bounded protocol performed authenticated HELLO/GET_RUNTIME_INFO,
ROOT_CAPABILITIES, PING and SHUTDOWN. No mutation or media command was executed.
Normal app PID3047 (then PID3626 after restart) remained UID/GID10065,
`u:r:untrusted_app:s0:c512,c768`; acquiring an authority did not elevate the app.

Quiescent app PID3626 had **63 FDs before, 63 after resource cycle 1, 63 after
cycle 2**. Owned helpers each had 30 FDs. This is bounded leak evidence, not
an exhaustive resource/performance campaign. Root READY did not make an API26
Host available: the normal UI reported privileged runtime unsupported and the
planner tests retain all Root capability cells as NOT_IMPLEMENTED.

Attempt accounting: the first qualification invocation stopped in preflight
because API26 rejected `logcat -c`, before any counted helper attempt. The
harness now preserves logs and filters from a device-side start timestamp;
this was a tooling-only correction. A prior preliminary-APK pilot passed 1/1
control cycle and remains separate from final-APK counts. No attempt was retried
or omitted from the formal eight-case result.

### Cleanup And Physical Regression

Final checks: Root helper processes **0**, Root listeners **0**, private
generation secret/metadata files **0**. Root-owned diagnostics were archived
before deleting only the current task's exact log paths. Secrets were never
read back by the harness. adbd returned to UID/GID2000, SELinux remained Enforcing,
then RootLab stopped API26. API25 and physical Root state were untouched.

A41/API31 ordinary regression passed **1/1**, on the same exact installed APK:
passive startup loaded three cached qualifications and prepared
`SINGLE / DIRECT_SHELL`; Enable Host late-revalidated all three, froze the
DirectShell-only plan and reached **Host: Available / Waiting for clients**.
Root stayed PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED, Root capabilities were all
NOT_IMPLEMENTED, and Shizuku remained DEFERRED/started=false. Disable Host
released three warm DirectShell feature servers and left no Warpnect privileged
helper/wrapper. The existing Shizuku server was left alone. No audio tone or
remote Session was needed for this readiness regression.

A41 fingerprint:

```text
samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys
```

Tablet was NOT_CONNECTED and therefore NOT_TESTED. S9/API29 was connected but
not modified or used for Root. No physical device was rooted, flashed or wiped;
A41 received only an ordinary APK update and semantic Host enable/disable.

### Software Gates And Repository State

* `git diff --check`: PASS.
* ktlintCheck, lintDebug, testDebugUnitTest --rerun-tasks, assembleDebug,
  assembleDebugAndroidTest and packageDirectShellProbeDex: PASS in one final
  rerun (100 executed tasks). Existing warnings are not claimed as zero warnings.
* JVM: **783 tests, 0 failures, 0 errors, 0 skipped**; 29 new tests across
  DirectRootRuntimeTest (17) and RootControlLeaseTest (12).
* Coverage includes valid control, UID/version/implementation/hash/provenance
  mismatch, HMAC, challenge replay, wrong/expired generation, request ordering,
  malformed/oversized framing, duplicate ownership, shutdown/loss, authority with
  zero remediation actions, and unchanged DirectShell/Shizuku complete plans.
* RootLab/RootHelper/Test-RootHelper PowerShell parser and physical-target
  rejection checks: PASS. Emulator identity guards and finite fixed operations
  were also exercised by the actual qualification.
* No native source change; no new CTest run or result is claimed.

New main model/readiness observation, DEBUG helper/controller/protocol extension,
JVM tests, docs and companion lab scripts remain **uncommitted**. The local lab
checkpoint is `1766fa1`; master/upstream are unchanged, no push or merge.
Pre-existing generated native changes, the line-ending-only input-controller
change and laboratory artifacts are preserved and excluded from that checkpoint.

Evidence (uncommitted laboratory output): `artifacts/directroot/` contains the
formal qualification, control logs, resource counts, cleanup checks, preliminary
pilot and A41 readiness/UI evidence. Build logs remain under
`app/build/reports/root-lab/`. Machine-local AVD/userdata/snapshots are not assets.

Decision: **DIRECTROOT_LAB_BOOTSTRAP_AND_RECOVERY_AUTHORITY_FOUNDATION_VALIDATED**.
Next recommended task, not implemented: **DirectRoot Per-Capability
Qualification**, beginning with a separately scoped capability on API26. This
does not imply production root-manager acquisition or physical qualification.

## Deferred Boundaries

Production Root authorization / Magisk adapter: NOT IMPLEMENTED.
DirectRoot Video: NOT IMPLEMENTED.
DirectRoot SystemAudio: NOT IMPLEMENTED.
DirectRoot Input: NOT IMPLEMENTED.
Root-assisted Runtime Remediation: NOT IMPLEMENTED.
Same-provider Recovery: NOT IMPLEMENTED.
Live Provider Migration: NOT IMPLEMENTED.
Physical Root validation: NOT VALIDATED.

The emulator cannot qualify OEM policy, real root-manager lifecycle, vendor
SurfaceFlinger/codecs/audio HAL or physical thermal behavior.

## Input Follow-up

The foundation above is historical evidence and was locally checkpointed as
`e762a1a` before the next slice. Its original no-capability statements describe
that checkpoint, not the subsequent Input implementation.
`DirectRootInputQualification.md` records the new typed Root Input gateway and
version-2 helper identity (`warpnect-root-input-v1`), retaining the foundation's
HMAC envelope, generation ownership and launch trust boundary. The exact
AOSP/API26 lab now has target-observed Input `QUALIFIED` evidence: 3/3 final-APK
cycles plus 1/1 active connection-loss reset, with 807 passing JVM tests.
The new Input work remains uncommitted for review; master/upstream are unchanged.

Only Input is superseded. Root Video/SystemAudio, production Root-manager
authorization and actual remediation remain NOT_IMPLEMENTED. Lab provenance
cannot satisfy future physical/Magisk qualification, helper liveness is not
persisted, and Input alone cannot make a normal Host available. The historical
8/8 foundation and A41 PASS above remain separate from the Input slice's attempt
accounting and its A41 live-admission endpoint failure.

## Video Follow-up

The validated Input slice was locally checkpointed as `ae431d9` before Video
work. `DirectRootVideoQualification.md` now records the second capability:
generation-bound Root mirror control, an authenticated provider/UID/PID-specific
Binder Surface lease and the existing normal-process encoder. Helper identity
is version3 / `warpnect-root-video-input-v1`; the HMAC foundation and laboratory
launch trust boundary are retained, with no Surface/encoded video on TCP.

The frozen APK passed3/3 AOSP/API26 fixture A/B qualifications with objective
decoded content, and two additional active helper/app-loss cases. Video/Input
compatibility is QUALIFIED for the exact laboratory key; liveness remains
volatile. Software gates passed841 tests with0 failures/errors/skips. New Video
work remains uncommitted; no push/merge. Earlier foundation and Input evidence
above is unchanged historical evidence, not relabeled as Video qualification.

Root SystemAudio, production Root-manager authorization, actual remediation,
same-provider recovery and live migration remain NOT_IMPLEMENTED. Root-only
Complete Host remains incomplete, missing SystemAudio. No physical Root claim.

## SystemAudio Follow-up

The later SystemAudio slice is recorded in `DirectRootSystemAudioQualification.md`.
Root identity version4 / `warpnect-root-video-audio-input-v1` extends the same
finite authenticated authority with purpose-specific Audio prepare/start/status/
stop/close. Actual descriptor delivery is Binder-mediated, UID0/PID/generation/
provider/HMAC scoped; continuous PCM remains in the original ring, not TCP.
CAPABILITIES now explicitly reports all three implemented features and recovery
actions NONE. Qualification is still independent from implementation or UID0.
Production Root authorization/Magisk, remediation and physical Root remain
unimplemented/unvalidated; no emulator launcher is registered as product Root
authorization. The unchanged Root lease cleans active audio on owner EOF/loss.

The final Audio APK passed3/3 three-capability same-generation qualifications
and actual Root-only late plan freezes, plus3/3 isolated Audio resource cycles.
All904 JVM tests and requested gates pass. The authority still exposes no
remediation actions, and the laboratory Root foundation is not production Root
authorization. Ordinary product Host UI/network admission is not inferred from
the explicit lab plan probe; complete qualification is scoped to the tested lab.
See the focused report for exact generations, artifact, resource sampling
limitations and the optional physical admission observations.
