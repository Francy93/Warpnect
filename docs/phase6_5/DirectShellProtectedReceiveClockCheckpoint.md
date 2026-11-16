# G1 - Android Protected Receive Clock Checkpoint

Status: isolated G1 code/test gates PASS; proposed approved logical checkpoint,
not a new hardware-validation claim. No commit or device installation.
Base:3253518088efcbfebc5cd8b60f2ff9d606e0a149.
Scope: Android authenticated media receive/liveness clock ONLY.

## Proposed Contents

Exactly three files:

1. `app/src/main/cpp/jni_bridge.cpp`: two hunks. Include time.h only on Android;
   select CLOCK_BOOTTIME immediately before the existing protected receive call.
2. `app/src/androidTest/java/io/warpnect/audio/transport/ProtectedAudioReceiveClockInstrumentationTest.kt`:
   the existing preserved test, copied byte-for-byte, not a new audio harness.
3. This focused source/test/evidence checkpoint record.

No production helper/header, CMake change, NativeBridge signature change,
microphone receiver count, priority floor, Flight Recorder, SDK playback
diagnostic or G2 control/lifecycle change is required. The candidate is a clean
HEAD archive plus these files/hunks, not a copy of the dirty worktree.

## Defect And Contract

Native media receives pass a MONOTONIC timestamp into the datagram protector.
Android SessionControl/lifecycle use elapsedRealtime/BOOTTIME. The shared
SessionProtectionRuntime updates last authenticated receive with a monotonic max.
After a newer BOOTTIME control sample, a valid media sample from the lower
MONOTONIC domain could decrypt successfully without advancing liveness.

G1 samples BOOTTIME under the existing protection-state lock and supplies that
time only to SessionProtectionRuntime::unprotect. Conversion is nanoseconds to
microseconds by truncation. An unavailable/negative clock returns the existing
typed Failed result without calling crypto receive. A closed runtime is still
rejected before reading the clock. Non-Android continues to use caller now_us.

The crypto runtime, endpoint checks, replay window, epoch/generation rules,
packet layouts and security keys are unchanged. Rejected replay/authentication/
endpoint traffic cannot advance liveness. Media receive deadlines, pacing,
source timestamps and wire timestamps remain in their existing domains. This
is an authentication-processing clock sample, NOT a kernel/RF arrival stamp.

## Dependencies And Exclusions

All production dependencies already exist in HEAD: Bionic time.h/CLOCK_BOOTTIME
at the current minSdk26, NativeSessionProtectionState, the existing state mutex,
SessionProtectionRuntime and its existing protection-result/telemetry handling.

The Android test uses HEAD's NativeBridge prepared endpoint, Session protection,
audio transport and audio receiver APIs; its capture-source enum also exists.
It needs no CompletedSetupControlTransport, retry dispatch, destination pump,
native microphone clock, priority helper or other untracked code. No G2
dependency is imported and no provider is started by this build.

The main dirty JNI file contains additional unrelated scheduling, ownership,
playback and diagnostic hunks. They are deliberately absent. All other HEAD
production/test/vendor files remain identical. Current85/70ms SystemAudio,
8ms admission,4ms output and committed microphone contracts are not retuned.
Phase7 R2, S0, adaptive buffering, planner/Root/Shizuku and recovery are excluded.

## Historical Device Evidence, Not A New Isolated Campaign

The preserved October12 protected-receive clock investigation records:

- r454/r458: old APK fails actual JNI/UDP clock-domain assertions on S9/Tablet.
- r461: corrected larger-cohort APK passes on both, including replay not
  refreshing liveness. A stale-APK installation-order attempt is NOT counted
  as a post-fix failure or hidden retry.
- Example S9 BOOTTIME control3449264373499us vs native MONOTONIC3060726879392us;
  Tablet1382057724962us vs973150614014us. These are within-device domain offsets,
  not E2E latency or clocks subtracted between devices.

That evidence is in the original preserved worktree report
`docs/phase6_5/MicrophoneAudioVerified5GhzAndProtectionClock.md` and ignored r45x/
r46x logs. It is corroborating evidence for this exact extracted clock block,
not a claim that this isolated build was installed or hardware validated.
The larger report is NOT imported as a dependency of this small checkpoint.

## Verification Scope

An external verification-only driver extracts the exact HEAD/candidate adapter
class and state declaration, retaining the original map/lock/security calls.
It reuses HEAD's real SessionProtectionRuntime, AEAD and native security fixtures.
Only the Android clock function is replaced with deterministic inputs. It tests
BOOTTIME/MONOTONIC separation, accepted-media progress, replay/authentication/
endpoint rejection, unchanged wire/content timestamp, fail-closed clock errors,
closed state and the unchanged non-Android branch. No fake crypto or substitute
production adapter is proposed for commit.

The driver and generated adapter projections are verification artifacts OUTSIDE
the candidate, not new application/native dependencies. Its Android macro path
does not emulate the Android kernel/JNI runtime. A passing APK build is not
execution of the Android instrumentation test.

| Fresh isolated gate | Result |
| --- | --- |
| ktlintCheck / lintDebug |PASS; lint retains129 warnings,0 errors, not static-security signoff |
| JVM rerun |1043/1043 PASS,166 XML result files,0 failures/errors/skips |
| assembleDebug |PASS, arm64-v8a/armeabi-v7a/x86_64 native compilation |
| assembleDebugAndroidTest |PASS; instrumented test COMPILED, NOT executed on a device |
| packageDirectShellProbeDex |PASS |
| Original HEAD CTest Debug / Release |22/22 each,1.83s /2.13s |
| Exact pre-fix adapter, both native configurations |Expected exit1: nine clock assertions fail among57 checks |
| Exact G1 adapter / Android clock stub, both configurations |57/57 checks PASS |
| Exact G1 adapter / non-Android branch, both configurations |33/33 checks PASS |
| Isolated diff scope/whitespace |Only these three files; JNI has exactly two hunks |

The isolated Gradle run executes100/100 tasks with --rerun-tasks,
--no-daemon --max-workers=2 and finishes in16m7s. Native verification uses a
fresh Visual Studio2022 x64 tree. It does not reuse the dirty cohort's native
objects. Generated drivers are not added to CTest's22 original HEAD targets.

The previous full dirty-cohort audit's1157 JVM/178 files and27 native targets
are DIFFERENT scopes: uncommitted tests/diagnostics and Phase7's old simulator
are absent here. No HEAD test was deleted to obtain smaller totals. Passing
this subset neither qualifies the omitted microphone destination work nor
resolves the independent AAudio/network/dispatch/Video/timestamp debts.

Built main APK SHA256:
`266279F44A8E03C43C0D173FA51D8BEF4B02D57D822A6DD630A7B61B300FBE0F`.
Built test APK SHA256:
`B29F9F454FD677863532D35CDF5C61E29A1E58734DED5135CAADAE4E68304675`.
Neither is installed or equated to a historical hardware APK. No APK binary
reproducibility guarantee follows from equal source content.

## Reproducibility And Approval Boundary

The deliverable is HEAD plus a reviewed three-file patch, exact source/test/doc
hash manifest and clean-export replay. Verification commands/results and build
artifacts are retained separately; generated APKs/logs/driver binaries are not
proposed commit contents. No original worktree/index/source is replaced.

No automatic staging of the real index, commit, merge, push, reset or cleanup.
No hardware install, device campaign, Root, network/volume change or G2 work.
Readiness for a logical commit and fresh hardware qualification are separate
claims; any future device execution requires separate authorization.

The supplied final manifest/patch and independent clean-export replay must be
checked together before approval. Raw SHA256 and Git-text SHA256 distinguish
Windows checkout CRLF from source-content identity. The original repository's
496 nonignored starting paths and real index are preserved; all candidate and
verification files live outside that worktree or in its ignored audit directory.

IMPLEMENTED: exact G1 correction already present in the larger dirty cohort,
now isolated. TESTED: source-exact deterministic adapter replay, unchanged
security core/regressions and Android compilation above. HARDWARE VALIDATED:
historical larger-cohort component evidence only; isolated APK NOT RUN.
QUALIFIED / PRODUCTION-READY: no new claim. G1 has no G2 dependency. It is ready
for an approved software consolidation commit once the supplied package identity
checks pass; do not automatically proceed to G2 or a device campaign.
