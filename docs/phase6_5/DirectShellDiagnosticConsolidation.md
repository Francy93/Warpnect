# G5 Optional Diagnostic Consolidation

Date:2026-10-25. Base20928f4 is the frozen, clean G1-G4 source receipt.
G5 uses a separate `codex/phase6-5-diagnostics-consolidation` worktree.
No device installation, Session, tone, provider work, merge or push is performed.
The original development tree and the frozen functional tree are preservation
targets, not build dependencies. Hardware evidence keeps its original APK scope.

## Selected Logical Groups

G5A:99f0bfc4fed2c40fa5040829349a20ff27f0a26f,21 files. Portable bounded
flight storage, numeric wire observations, protection-duration arithmetic,
native replays and offline flight/output-contract/terminal/volume/APK/Perfetto/
symbol guards. Its receipt is `DirectShellDiagnosticInfrastructureCheckpoint.md`.
No runtime hook or application functional delta is present at that checkpoint.

G5B adds only passive, opt-in DEBUG integration. Source/test manifests, raw-file
hashes, exact patches and full gate logs are external receipts, not private
evidence to commit. The shared-file changes are reconstructed at hunk level:

| Area | Exact source/test scope |
| --- | --- |
| Native owners | `include/audio_transport.h`, `include/audio_receiver_runtime.h`, `include/udp_socket.h` |
| Native observations | `src/audio_transport.cpp`, `src/audio_receiver_runtime.cpp`, `src/audio_oboe_playback.cpp`, `jni_bridge.cpp` |
| DEBUG wrappers | `src/internal/system_audio_flight_debug.h`, `audio_wire_timing_debug.h`, `microphone_ring_timing_debug.h` |
| Kotlin boundaries | `NativeBridge.kt`, `AudioReceiverSessionController.kt`, `NativeOpusAudioDecoderController.kt`, `NativeSclAudioReceiverController.kt`, `DefaultAndroidSessionPipelineBindings.kt` |
| Optional monitor scopes | `AudioPathTrace.kt`, `AndroidAudioPathTrace.kt`, `AudioPathTraceTest.kt`, slow-snapshot test in `AudioSessionControllerTest.kt` |
| Bounded host cost replay | `native/tests/audio_causal_flight_recorder_tests.cpp` |
| Isolation guard | `tools/hardware/VerifyAudioDiagnosticIsolation.ps1` |

Native paths in this table are relative to `app/src/main/cpp`; Kotlin paths are
the existing package locations. Manifests, application Gradle dependencies and
Android CMake linkage are unchanged. No PCM queue, decoder dispatch, privileged
command, public wire format or native ABI version is introduced.

## Default State And Lifetime

| Selector | Default | Scope / cost when explicitly enabled |
| --- | --- | --- |
| `debug.warpnect.system_flight=1` | OFF | One Session-owned recorder; fixed80-byte records,12544 slots, below1MiB |
| `debug.warpnect.audio_wire_timing=1` | OFF | Protected audio socket metadata;32768 fixed numeric records;120s collection budget |
| `debug.warpnect.mic_ring_timing=1` | OFF | Two1024-event arrays; single producer/callback writers;250ms post-first-full window |

Missing/other property values do not enable a probe. Non-lock-free64-bit
atomic platforms fail closed for callback recorders. Wire opt-in is attempted
once per protected audio socket, reset only on socket rebind; unauthenticated
datagrams cannot cause repeated property/allocator setup attempts. Sender
timestamp options/drains are restricted to the explicit wire probe, not enabled
by default. No receive implementation or socket capacity is replaced.

The flight append tries one atomic claim and counts contention instead of
waiting. The callback holds its cold-created owner; it does not copy shared
ownership, allocate, lock a mutex, format, write files, decode or perform I/O.
Enabled observations do use clock reads, atomics and a gettid syscall; there is
no callback-lazy C++ TLS. These are observations, not hard real-time guarantees.
An export occurs only after all component owners are quiescent; microphone-ring
export follows stream close and ingress ownership exclusion. Wire exports occur
on socket owner close/rebind, not in the audio callback. Metadata exports are
bounded per owner, not an automatic disk-quota manager for unlimited manual
Sessions. Cache retention must be budgeted by any future hardware campaign.

OFF hot paths add only nullable/boolean branches; no new hot-path clocks,
storage, socket option, trace section or SDK query is executed. Cold component
creation may check a property and registry. Existing G1-G4 DEBUG diagnostics
remain as before; this does not claim that every legacy DEBUG log is opt-in.

The Kotlin scopes remain on the same thread and monitor, with balanced finally
closure. They are wired only for a DEBUG receiver with an attached flight
recorder, never for MicrophoneAudio. The read-only private JNI availability
query's native implementation/export exists only in DEBUG and both call sites
short-circuit on BuildConfig. Its unused Kotlin native declaration may remain.
Release may contain the unselected Kotlin trace types; they confer no privilege
and cannot activate a native recorder. Native NDEBUG compilation removes hooks.

Flight phase3 means stop/close REQUESTED, not proof that the SDK has completed
stopping. Xrun observation brackets the SDK counter read; it is not the exact
physical xrun instant. DecoderCreated marks the creator's attempt, not proof of
successful decoder prepare. Kernel software timestamps are not RF timestamps.
MONOTONIC, BOOTTIME, REALTIME and thread CPU clocks retain their explicit roles;
no uncalibrated cross-device or cross-domain subtraction is introduced.

## Excluded Material

| Class | Disposition |
| --- | --- |
| 1: safe reusable infrastructure | Selected G5A/G5B only; no automatic whole-file import of mixed shared source |
| 2: needs further isolation | Always-on protection/ATrace timing; capture-probe CPU/BOOT extensions; lifecycle/discovery logging; old file-dependent overhead tool |
| 3: obsolete or superseded experiments | Earlier global/scoped/virtual microphone feasibility paths and one-off syscall experiments; preserved, not promoted |
| 4: behavior-changing experiments |24ms output selector, callback/RX/decode stalls, synthetic output controller, extra sender log windows, backend/output probe library and test package |
| 5: Phase7 | Resilience R2, S0 planning and simulator; no source, milestone or default imported |
| 6: private/generated/unattributed | RF/USB/Npcap/WSL/network scripts, APKs, traces, screenshots, crash logs, attachments and unrelated inventory utilities |

The capture timing extension is specifically excluded because the baseline
capture observer is already active in DEBUG: copying its new clock queries
would introduce default-on work. Pure protection arithmetic tests do not imply
acceptance of the original always-on JNI protection timing hooks. Fault event
identifiers remain solely to read historical exports; there is no fault selector
or pause implementation. The standalone output library is not a dependency of
the selected symbol guard.

## Security And Lint Disposition

This is scoped change review, not a full security audit or release certification.
No warning suppression, trust-manager change or permission fabrication is used.

| Historical category | Source set / reachability / boundary / action |
| --- | --- |
|7 TrustAllX509TrustManager | DEBUG libadb/BouncyCastle classpath; self-ADB transport is reachable on request, but reachability of every flagged dependency method is not proven. Not SCL AES-GCM. Trust-all methods are not certified safe by this review. Existing user-consented pairing/installed artifact/endpoint checks do not replace TLS peer validation. Separate dependency/peer-auth review required before release promotion; dependencies absent from Release runtime. |
|3 exported providers | DEBUG; FD/PCM/Surface endpoints require actual shell Binder UID plus live-generation HMAC/lease. Existing Root laboratory methods have separate UID/PID/provider/proof leases. No generic object/file broker. Keep explicit negative-caller tests and review all methods before release exposure. |
|8 exported receivers | DEBUG, no manifest permission: externally reachable. Control requests use fixed commands and current secret/generation to reach an authenticated loopback helper, not arbitrary shell. Session/selector/metric mutation and denial-of-service exposure remain historical DEBUG-surface debt, not universally shell-gated receivers. Restrict/remove before release distribution; no new receiver added here. |
|39 PrivateApi +13 DiscouragedPrivateApi | Predominantly DEBUG helper/framework reflection, plus existing main provider adapters. Reachable only through selected provider/capability paths, subject to Android permission/UID checks; reflection itself grants no authority. API compatibility and release exposure review remain open. No new hidden API added by G5. |
|1 UnsafeDynamicallyLoadedCode | DEBUG DirectShellNativeMicrophoneClock loads packaged libraries from PackageManager-installed APK/nativeLibraryDir, requires shell identity, API31+, matching classpath owner and owned-submix route. Not arbitrary external code loading; retains artifact integrity/update/ABI review debt. Byte-identical to G4. |
|2 new UnclosedTrace | Main Kotlin adapter type, selected only in DEBUG with an attached recorder; not a new IPC or privileged surface. Lint cannot match wait/held/finished across the interface methods. The same-thread inline monitor closes held scopes in finally, including early return/exception and nesting, covered by replays. Retained without suppression; SDK trace implementations must remain nonthrowing with these fixed short labels. Release selection is constant-false. |
| Native/JNI | New private read-only DEBUG attachment query, no privileged operation or payload. Other JNI marshalling, auth/replay, handles and generation checks unchanged outside conditional metadata. Release symbols/preprocessing checked separately. |
| Remaining warnings | Existing dependency/API/UI/resources/deprecation/static-field findings retained. Zero lint errors is not warning-free or production-ready. |

The existing Shizuku provider in the main manifest is permission-protected by
INTERACT_ACROSS_USERS_FULL; it is not a G5 fallback or new dependency. Selected
DirectShell destination/helper sources and all provider planners remain unchanged.
Debug lint therefore reports134 warnings, not the frozen baseline132: the two
new trace-scope warnings above are explicit. The other132 findings match by
ID, message and normalized location. Of52 private-API warnings,37 are in DEBUG
and15 in main adapters; main-source reachability is not erased by DEBUG gating.

## Verification And Final Decision

Final gates, artifact hashes, preservation counts, commit receipt and the
three-cohort comparison are recorded below after verification. No result from
an APK build is labeled device validation. Remaining hardware reconciliation,
release-default availability and the independent SystemAudio/Video/timestamp
failures are not closed by diagnostic consolidation.

| Gate | Verified result |
| --- | --- |
| Full Android DEBUG rerun | PASS,100 executed tasks,20m57s; ktlint/lint/JVM/APK/AndroidTest/helper DEX |
| JVM |1155/1155,178 suites,0 failures/errors/skips |
| Native fresh configure/build | PASS, external fresh tree; Debug26/26,Release26/26 |
| Offline analyzer replays |40/40 Node:23 flight +17 output-contract |
| Offline PowerShell guards |36 synthetic:11 volume +6 APK +7 readiness +9 terminal +3 symbol;4 optional historical fixtures SKIPPED |
| Native DEBUG symbols |3 ABIs PASS; flight present, no flight emulated TLS |
| Native NDEBUG comparison |4 modified translation units match G1-G4 after whitespace-normalized preprocessing; known pre-G1 JNI delta is correctly rejected |
| Diagnostic core cost replay |4096 samples each OFF/record/frozen; zero ordinary C++ heap allocations in measured paths; fixed1003744-byte core |
| Android Release and final ABI refresh | PASS,161 tasks:72 executed/89 up-to-date,18m54s; final Debug cohort refreshed plus unsigned Release APK and lintRelease |
| Release lint | PASS,0 errors/42 warnings, including the2 cross-method trace findings; no blanket release-security signoff |
| Release boundaries |3 native ABIs contain none of the new diagnostic classes/export; both Kotlin queries constant false, binding captures null; DEBUG components/libadb/BouncyCastle absent from Release artifact markers |
| Source closure |340 unique tracked sources in3 Android compile databases, no original-tree/untracked source dependency |

Host cost replay Debug p50 OFF/record/frozen=100/200/200ns and p95=100/300/200ns;
Release p50=0/100/100ns and p95=100/100/100ns. The host timer is quantized;
maximums include scheduler outliers up to58.4us, not a callback execution bound.
This measures the portable storage path plus harness clocks/atomic sink, not
Android clocks/gettid/SDK/trace overhead, cache export or physical playout.
Current Android enabled/disabled overhead and hardware timing equivalence are
NOT newly validated. Historical recorder cost is not reused as a universal bound.

| Cohort | Evidence / limits |
| --- | --- |
| A:frozen G1-G4 |1152 JVM,24+24 native; committed software baseline, hardware reconciliation pending |
| B:G1-G5 OFF |1155 JVM,26+26 native; same geometry/auth/replay/ownership, nullable diagnostic paths; software comparison, not device timing equivalence |
| C:G1-G5 offline observations ON | Bounded recording/freeze/overflow/contention/clock/admission replays, trace monitor/nesting/exception/contended-snapshot tests,40 analyzers,36 guards; no audio fault or Android experiment enabled |

The three added JVM cases are two AudioPathTrace tests and one slow-snapshot
contention test. The dirty research tree's additional capture-cost and lifecycle
logging tests remain excluded with their runtime additions, not lost functional
coverage. Native adds only protection arithmetic and recorder tests to24; the
Phase7 simulator and output-fault assertions are not imported.

The preservation inventory covers496 original paths:39 selected diagnostic
projections,85 already owned by G1-G4 (residual hunks not imported),11 requiring
further isolation,4 superseded/one-off,10 behavior-changing experiments,6 Phase7,
and341 historical/private/generated/unattributed items. This is a disposition,
not deletion authority or an authorship claim. All exclusions stay in place.
The original index remains empty at3253518;85 modified tracked and411 expanded
untracked paths remain. Hash comparison covers492 files; four private/large
files intentionally have metadata-only verification. The frozen20928f4 tree is
clean. R2 and RFC-006I/J retain their original hashes/status.

Preserved non-product failures: a supplementary empty-library guard first used
an unavailable PowerShell exception type, then passed with the verified error
identifier; the original failed harness output is retained. A strict132-warning
comparison failed on the two new cross-method trace findings,
which are dispositioned above, not suppressed. Android Release compilation
reported a Kotlin compile-daemon connection failure and selected its normal
in-process fallback; the final build passed without a product/configuration
change. None of
these observations is an audio root cause or permission to tune production.
A first bytecode guard also mistook a null-captured parameter TYPE for trace
activation. Its FAIL and bytecode are preserved; the corrected check requires
actual activation instructions and verifies both query wrappers return constant
false. The Release bindings capture null, without an INSTANCE access or query
invocation. No production source was changed to accommodate the observer.

## Remaining Phase6.5 Boundaries

Static RFC-006I capability planning/admission and controlled optional Shizuku
composition already exist. Do not begin a duplicate planner RFC. DirectShell
remains independent; Complete Host=Video+SystemAudio+Input, microphone optional.
Existing DirectRoot bootstrap/Input/Video/SystemAudio/normal Host admission
have exact AOSP/API26 laboratory evidence in prior history. Physical DirectRoot,
production Root-manager authorization, broader Shizuku/API compatibility and
release-default provider availability still require their separate gates.
No Root/Shizuku code, provider fallback, remediation or migration is added.
Same-provider Recovery, Root-assisted Remediation and Live Provider Migration
remain unimplemented, distinct from static composite planning.

Original dirty-branch reconciliation requires a separate authorized hunk-level
integration decision using the G1-G5 manifests. Do not merge it blindly over
experiments or replace it with this projection. Before hardware promotion use
one frozen consolidated APK and the established S9/API29 ->Tablet/API33 ordinary
MIC OPEN/REOPEN first-frame/three-Session gate, policy/helper-loss cleanup and
bounded SystemAudio/Video/Input regressions. Preset/API/release qualification is
not inherited from source compilation. No hardware campaign starts in G5.

Historical global microphone functional/quality and exact A41->S9 AP-only quality
tokens remain intact in their original scope. Independent kernel-RX gaps,
AAudio wakeup/xrun/rejection, unassigned dispatch-to-decode stall, Video
HOME/REOPEN reassembly/EADDRINUSE and S9 microphone hardware-timestamp rollback
remain open. Phase7 R2 stays proposed, S0 not started, adaptive/output24ms/fault
production changes absent. SOFTWARE TESTED is not HARDWARE VALIDATED,
QUALIFIED or PRODUCTION-READY.

## Final Receipt

| Checkpoint | Commit / scope |
| --- | --- |
| Frozen functional base |20928f4f3f971499d18804142ca10a4381f1b0e5, untouched G1-G4 receipt |
| G5A |99f0bfc4fed2c40fa5040829349a20ff27f0a26f,21-file offline/storage group |
| G5B |f8cf4910c4eea18d41209aa8886bb4594a5bf15f,22-file passive DEBUG group |
| Final report/index |Following docs-only receipt commit; no source delta after the tested G5B |

The43 G5A/B file instances resolve to42 unique files; all raw hashes and Git
blobs match their final selected owner. Source/test/tool files were frozen
through the final Android build; no source dependency points into the original
dirty worktree. Exact manifests/patches/gate logs are retained at
`C:/wg5-artifacts-r1591`, fresh native builds at `C:/wg5-native-final-r1591`.
Generated outputs and those machine-local receipts are not committed.

Post-G5B integrated Gradle verification: PASS,161 tasks,14 executed and147
up-to-date,42s, including every required Android gate and Release build/lint.
The earlier100-task rerun executed the complete JVM cohort; subsequent results
are explicitly incremental, not claimed as a second full rerun. Post-commit
CTest Debug26/26 (3.60s) and Release26/26 (6.36s) pass on the fresh native tree.
The first post-commit command failed before task execution because PowerShell
split an unquoted dotted Gradle property. Its failed log/result are retained;
quoting the CLI-only compiler option corrected the invocation, with no product
or permanent build-configuration change. No failure is counted as a PASS.

| Built artifact, NOT installed | Bytes | SHA256 |
| --- | ---: | --- |
| Debug APK |37244664|0297F044C77CF1C80EFCDC4017581D53D8EE30807D2D06E6C1D3C1E3586AE0C9 |
| AndroidTest APK |1237860|0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F |
| Helper DEX JAR |13287435|002DD42AF5954BDF71300A33CA25DA1631848CB48CDBBB247D9433913D23D3AC |
| Unsigned Release APK |22484219|DCE915BA4522D50CC422A944F1DE5FC4C3A65731161FF57A2355F5C796CFB030 |

Reproduction uses the checked-out committed source and pinned Android toolchain,
without local evidence, an original-tree source copy or a device:

```powershell
git diff 20928f4 --check
.\gradlew.bat --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex :app:assembleRelease :app:lintRelease
cmake -S native -B <fresh-build-directory>
cmake --build <fresh-build-directory> --config Debug --parallel 2
ctest --test-dir <fresh-build-directory> -C Debug --output-on-failure
cmake --build <fresh-build-directory> --config Release --parallel 2
ctest --test-dir <fresh-build-directory> -C Release --output-on-failure
node --test tools/hardware/SystemAudioFlight.test.cjs tools/hardware/SystemAudioOutputContract.test.cjs
```

The opt-in runtime paths are IMPLEMENTED and SOFTWARE TESTED, not newly
HARDWARE VALIDATED. The excluded experiments are preserved/deferred, not an
unresolved dependency of the selected projection. No playback fix, latency
improvement, device qualification or production promotion is claimed.

**G5_DIAGNOSTICS_CONSOLIDATED**, for the selected category1 infrastructure.
**G1_G5_SOFTWARE_BASELINE_READY_FOR_INTEGRATION**, for controlled source
integration only: self-contained, tested branch; G1-G4 behavior preserved by
source/compiled guards and regression tests; new probes default OFF; known
warning/overhead/hardware/release limitations explicit. This is not authorization
to merge the dirty tree, install a package or start the next provider/Phase7 task.
