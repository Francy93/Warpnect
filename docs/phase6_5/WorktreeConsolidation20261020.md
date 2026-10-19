# Worktree Consolidation - 2026-10-20

## Verified Baseline

Branch `codex/directshell-production-runtime-integration`, starting HEAD
`1cc82af6ef939a2440a150f459d36ba7fff412b8`, master `032b592`, upstream `9d0b4dc`;
staging empty. The local checkpoints `596a534`, `b812d82`, `1cc82af` exist in
that order. There is no separate PROJECT_STATUS, VALIDATION_STATUS or
CURRENT_HANDOFF file: Phase 6.5 README, CurrentNonRootDebt, FutureRoadmap and
the dated focused reports are the current status sources.

The complete read-only inventory is
`app/build/reports/r1194-preserved-worktree-inventory.json`. Every Git entry has
status, role, size, timestamp and, for non-private regular files up to 1 MiB,
content SHA256. No contents or secrets are exported by the inventory tool.
Content duplicates are candidates for review, NOT authorization to delete.

| Role at initial inventory | Tracked modifications | Untracked files |
| --- | --- | --- |
| Product candidates | 48 | 24 |
| Automatic tests | 19 | 15 |
| Reusable diagnostics / probes | 6 | 30 |
| Documentation | 12 | 44 |
| Build/generated/crash output | 246 | 113 |
| Evidence or temporary files needing review | 0 | 215 |
| Unattributed | 0 | 33 |

The two newly added inventory-tool files are included in the initial untracked
counts; the original baseline had 803 Git entries. Separately, ignored
`app/build/reports` contains 13928 files / 9498621473 bytes at inventory time.
`D:/WarpnectDiagnostics` remains in place and is not imported into Git.
The inventory is repeatable using `node tools/worktree/inventory.cjs`; its
functional tests are `node --test tools/worktree/inventory.test.cjs`.

## Coherent Groups And Provenance

1. **SystemAudio admission correction:** capacity-wait helper, the SystemAudio
   call-site argument and the recorded callback-delay replay. Physically tested
   as part of frozen APK `1C0AEA78...BFA2C24`; exact reference evidence is
   the preserved working-tree report `SystemAudioReferenceNetworkValidation.md`.
   The other changes in the same playback/test files are NOT automatically part
   of this group. A separate HEAD-based source export checks the isolated delta.
2. **Microphone destination transition work:** DEBUG DirectShell input policy,
   native/app-owned clock, destination/feed/result pumps, consumer-window and
   queue/freshness ownership; shared JNI/playback/ring/receiver changes and their
   tests. Working-tree reports `RemoteSubmixOpenReopenPlatformBoundary.md`,
   `MicrophoneAudioNativeRingAdmission.md` and
   `MicrophoneAudioAndroidCompatibility.md` contain the association history. Compiled
   candidate source is not equivalent to full OPEN/REOPEN qualification. Keep
   these changes uncommitted until their dependencies and physical acceptance
   can be reviewed together. Historical microphone tokens retain exact scope.
3. **Session/discovery/Input/shared runtime:** lifecycle and controller changes,
   completed-setup transport ownership, LAN/Direct telemetry, native video/
   protection timing and thread-priority helpers. They are referenced by the
   current candidate but not fully attributable to this compatibility restart.
   Preserve them with tests; do not fold them into an audio-admission commit.
4. **Diagnostic tools:** DEBUG probe classes, temporary standalone RF/UDP/UI
   harnesses and the reusable `tools/` scripts. DEBUG compilation does not make
   them release product features. Old RF experiments stay suspended. This task
   adds only bounded microphone compatibility/inventory diagnostics.
5. **Hardware evidence:** numeric CSV, packet/ETW/Perfetto journals, screenshots,
   UI XML, APK copies and private restoration state. Preserve in ignored local
   reports / diagnostics; never put them in a checkpoint merely because they
   explain a product change. No persistent raw PCM is required.
6. **Generated artifacts:** native CMake cache/projects, object/library/test
   binaries, Gradle outputs and JVM crash/replay files. They are not source.
   `.gitignore` now names the existing `native/build-rfc006e/` qualification
   directory narrowly. Previously tracked generated files remain tracked and
   unchanged by this ignore rule: no `git rm`, deletion or cleanup is performed.
7. **Unattributed files:** root attachments, legacy fixtures and other entries
   without a sufficiently certain ownership chain remain untouched. A path/name
   or duplicate hash alone does not establish that they are dispensable.

## SystemAudio Consistency

The current helper has a hard, nonrenewable callback-jitter margin. At 48 kHz,
96-frame output bursts and 240-frame PCM blocks, 6 ms becomes 8 ms. Fitting
blocks do not wait. The recorded r866 replay preserves the old 240-frame loss
and the corrected zero-loss result. SystemAudio remains 85 ms ring / 70 ms
reserve; microphone remains 80/70 ms and 40 ms IPC freshness.

The reference token is ONLY `SYSTEM_AUDIO_A41_TO_S9_AP_ONLY_QUALITY_VALIDATED`:
A41/API31 Host to S9/API29 Client, S9 AP-only 5745 MHz, three consecutive
Sessions with 75 s tone each. It is not a resolution of degraded-network gaps,
RF observer qualification or a claim of universal Android quality. The UDP
series remains 5/6 strictly lossless with one missing datagram; no historical
failure is erased. The old RF-only next gates are explicitly superseded in the
current canonical introductions, not silently deleted from the history.

## Checkpoint Rules

An isolated source commit may contain only the reviewed admission helper API,
SystemAudio-only margin selection and its replay test. Default helper arguments
retain the HEAD microphone behavior; uncommitted microphone call-site and clock
changes remain in the working tree. Native tests of that isolated source tree
must be distinguished from tests of the larger current candidate. No claim is
made that a subset source commit is the exact complete frozen APK source tree.

Inventory tooling/documentation can be a separate bookkeeping checkpoint after
review. Compatibility tests can be checkpointed only after their real-device
results. No omnibus staging, reset, clean, blind stash, merge, rebase or push.
The existing empty October 4 index lock requires provenance/active-writer review
and a preserved copy before any removal. Read-only Codex `git diff` processes
are not assumed to own this old lock.

## Architecture Boundaries

No new provider or hidden Root/Shizuku dependency. Complete Host remains
Video + SystemAudio + Input; optional microphone failure does not fabricate a
partial normal Host. The S7 API26 legacy audio-policy adapter already exists;
missing MODIFY_AUDIO_ROUTING is distinct from ordinary AudioRecord capture.
RFC-006J same-provider recovery, Root-assisted remediation and live provider
migration remain NOT IMPLEMENTED. RFC-005J, radio investigation and Phase 7
adaptive occupancy/latency-debt recovery are not reopened by consolidation.

## Consolidation Outcome

Local checkpoint `ac35e8f`, `fix(audio): bound SystemAudio admission callback jitter`:
exactly four files, 122 insertions / 5 deletions, two production files, one native
test file and self-contained `SystemAudioAdmissionCheckpoint.md`. Fresh isolated
CTest Debug 22/22 (9.59 s), Release 22/22 (8.95 s), NDK arm64/API26 call-site
syntax PASS. The admission helper and replay match the qualified candidate;
the isolated caller deliberately retains the old default microphone behavior.
No working source file was replaced while selecting index hunks: before/after
SHA checks match. Remaining shared-file changes stay unstaged, not discarded.

The October 4 empty index lock was first left alone when process identity was
uncertain. A later observation finds only read-only diff processes; exclusive
open succeeds, unchanged timestamp/length are verified, copy and metadata are
preserved as `r1204-stale-index-lock-*`, and only that stale lock is removed.
No active Git process is killed and no index/source is removed.

An additional ignored local snapshot preserves all 198 inventoried source,
test, diagnostic-tool and documentation candidates at their relative paths:
`app/build/reports/r1200-preserved-source-test-doc-snapshot.zip`, 4142775 bytes,
SHA256 `2A26513ED9CE6EDA6E18D19C5886CC41369C6523EB0910DEF87449B7B161EFA7`.
Together with the starting HEAD and path/hash inventory this identifies the
preserved source cohort without adding private evidence or generated binaries
to Git. Unattributed files and hardware evidence remain in place, not deleted,
renamed or implicitly classified as validated product code.

The inventory tool passes 8/8 functional tests, including actual file inspection
and fail-closed path/rename handling. Narrow build-directory ignore plus this
ledger/tooling form an independent bookkeeping group; they do not promote the
remaining microphone transition code. The current compatibility investigation
will append its real-device results separately.
