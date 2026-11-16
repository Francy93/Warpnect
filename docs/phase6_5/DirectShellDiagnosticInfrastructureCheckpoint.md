# G5A - Bounded Storage And Offline Diagnostic Infrastructure

Base:20928f4, the frozen G1-G4 functional checkpoint. This first diagnostic
subgroup adds no runtime hook, new privileged command or source-set manifest.
It does not install packages, generate audio, start a Session or change any
playback parameter. Phase7 R2 and S0 remain outside this consolidation.

Included: the existing portable causal recorder, numeric wire-sample identity
and protection-timing arithmetic; their native replay tests; existing offline
flight/output-contract analyzers; volume-scale, APK-marker, Perfetto-readiness,
terminal-quality and compiled-symbol guards with deterministic fixtures.

Recorder records are fixed80-byte metadata, five bounded lanes, capacity12544
and total object size below1MiB. Append uses a single atomic try-claim, drops
contention rather than waiting, never allocates and contains no I/O. Export and
stats require ALL writers to be quiescent. This is not a concurrent snapshot API.
No audio, keys or credential payloads are stored. Fault event IDs are retained
for old offline exports, not as authorization to install fault-injection hooks.

The symbol guard's explicit library list defaults to the selected libscl_core;
the standalone output probe is not a dependency. Its original checks remain
unchanged. The excluded library can only be inspected by an explicit argument.
Offline tests do not call ADB or change a device. Runtime hooks are a separate
review/checkpoint; passing these storage tests does not qualify callback overhead
or Android wait attribution. Source/test/hash manifests and exact results are
retained outside the repository with the G5 consolidation receipts.

Excluded: Phase7 simulator, RF/USB/WSL work, device-control receiver, synthetic
microphone probes, standalone backend/output probes and their runtime faults.
The original files and all hardware evidence remain preserved in place.

Isolated G5A gates: Node40/40 PASS; PowerShell36 synthetic checks PASS, plus
an empty-library-set rejection check. Four optional historical terminal-log
fixtures are explicitly SKIPPED, not silently imported from the dirty worktree.
Clean native configure/build and CTest Debug26/26 and Release26/26 PASS.
Application production sources/JNI/manifest are byte-identical to G1-G4 at
this checkpoint; Android gates are reused with that identity, not claimed as a
fresh application or device run. The integrated G5 gates will run separately.
