# DirectShell Pairing Enrollment Owner (P1)

2026-10-26. Scoped RFC-006J groundwork, not a new RFC or Release activation.

Subsequent authorized consolidation: [P1 checkpoint receipt](DirectShellPairingP1Checkpoint.md).
The source/test cohort is now committed at1271765; the uncommitted/no-commit
statements below describe the original P1 delivery, not the later checkpoint.

Subsequent authorized [reservation/receipt model amendment](DirectShellPairingReservationModel.md)
is isolated from this checkpoint. The callback-based commit and Boolean staged
resolution below describe original P1, not its amended ports. Original evidence
is retained, not rewritten into a new backend or hardware result.

## Source And Scope

Parent: `faa9f0f695c56ad4fb8d0f729bae0cb1d623c80c`, containing the reviewed
architecture and the f7dd2cf/2a6528d DEBUG setup checkpoints. New isolated branch:
`codex/phase6-5-pairing-enrollment-owner`, worktree
`C:/Users/Francy/.codex/worktrees/phase6-5-pairing-owner/Warpnect`.
No commit, push, merge or device operation is authorized/performed by this task.

Exact application delta (new files only):

- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingContract.kt`
- `app/src/main/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinator.kt`
- `app/src/test/java/io/warpnect/platform/privileged/directshell/pairing/DirectShellPairingCoordinatorTest.kt`

The internal model is **IMPLEMENTED / SOFTWARE TESTED** and deliberately
**unwired**. All final automated gates below pass. There is no real
backend, Android adapter, credential store, NSD, service, notification or network
implementation of its ports. Existing DEBUG setup and its launch guard are
unchanged. Release setup adapter remains null; no Release onboarding is supplied.

## Ownership And Contracts

One application-owned coordinator exposes immutable StateFlow metadata only.
Activity recreation must reattach to that owner; it must not construct/resubmit
an enrollment. A new process constructs an idle owner and cannot restore a prior
ticket. No persistence, SavedState or automatic retry is provided.

Explicit `request(currentGeneration)` reuses `hostPairingSetupGeneration`:
only current pairing-required, actively requested Host readiness plus an
available backend and an unoccupied runtime can issue consent. Constructor,
StateFlow observation and waiting for code do not call backend/committer.
Permission availability is checked on submission, subsequent result and commit;
this pure model never requests an Android permission.

Identity domains remain separate:

| Domain | Ownership / rule |
| --- | --- |
| Origin readiness generation | Eligibility at explicit request; explicit Host-origin cancellation withdraws only its own enrollment |
| Owner epoch / nonce / ticket | Injected bounded identifiers, opaque object-identity ticket; not serialized, not a Session token |
| Network epoch | Invalidates enrollment on observed network replacement; no rebinding/fallback |
| Credential revision | Checked at result and final publication; forwarded in the ticket to the future atomic writer |
| Runtime/Session generation | Not created, changed, converted or acquired by this component |

Passive readiness refresh neither issues fresh consent nor automatically cancels
an enrollment while the user is in Settings. The originating Host's explicit
cancel/owner close does withdraw it. Pairing outcome is
`PAIRED_NEEDS_EXPLICIT_HOST_RETRY`, never Host availability, provider support,
planner qualification or automatic Enable Host.

Transitions:

```text
IDLE / terminal
  -> explicit eligible request -> AWAITING_CONSENT_AND_CODE
  -> valid explicit submit     -> DISCOVERING -> PAIRING
  -> staged authorization     -> COMMITTING
  -> atomic publish           -> CLOSING -> PAIRED_NEEDS_EXPLICIT_HOST_RETRY

cancel / expiry / failure -> CANCELLING -> confirmed drain -> terminal
                                    -> cleanup deadline/error -> CLEANUP_INCOMPLETE
```

The backend creates an **inert**, non-reentrant handle before any start or
callback. `start/cancel/close` dispatch bounded, nonblocking work and may deliver
synchronous callbacks. Ordinary exceptions become closed reason enums, not
Throwable/message/cause fields in status; fatal VM Errors are not swallowed.
A factory exception must own no
resources. These are port obligations to prove in P2, not facts about libadb.

The committer receives the original ticket, opaque staged handle and one
publication permit. Preparation may be deferred but belongs to the same
operation's drain; no independent worker/queue may outlive it. The permit
serializes cancellation versus one final **bounded atomic** publication, checking
current ticket, expiry, permission/backend/runtime availability and network/key
revision. It is not permission to do file preparation or blocking I/O under the
owner's monitor. The future credential writer must atomically compare expected
revision at its own store boundary; a getter check does not solve external store
or network TOCTOU races. Real store transaction/epoch ownership remains P2 work.

Cancel before publication prevents the writer from being invoked. Publication
before cancel wins; committed trust is not revoked or deleted by late cancel.
Staged resources resolve exactly once (commit/discard), including duplicate or
late callbacks; no unbounded disposed-handle history is kept. Failed finalization
fails closed as CleanupIncomplete, not a claim that secrets were wiped.

## Bounds, Deadlines And Secrets

| Bound | P1 behavior / limitation |
| --- | --- |
| Consent |120s absolute, injected suspend-inclusive elapsed milliseconds |
| Work |60s total after explicit code submit, not reset by stage progress |
| Cleanup |3s observation deadline, never proof that physical I/O stopped |
| Active state |One attempt, operation, staged handle and live timer; no enrollment queue/history or worker created by the model |
| Identifiers |Owner epoch/nonce nonempty and at most64 chars each |
| Code |Exactly six ASCII digits; consume/wipe caller array on every submit path; retain at most six chars plus one six-char borrowed copy |
| Status |Phase, origin generation, closed reason and cleanup-failure flag only; redacted opaque handles |

Timers are injected, nonthrowing, non-inline and cancellable. Cancellation must
release scheduled closures. Old timer versions are inert. Submit, result and
publication recheck elapsed deadlines even if delivery of the timer was delayed
by suspend. No wall clock, media clock or Session pairing-clock change occurs.

Logical termination immediately withdraws publication authority. The operation
slot stays occupied until `close(drained)` actually confirms that **all** owned
work/resources/secret copies, including deferred preparation, are gone. Cleanup
timeout blocks replacement work; a late truthful drain may finish that attempt.
Cancellation/close/finalization exceptions retain sticky backpressure, without
blind retry or claiming recovery. A P2 integration must define explicit safe
resolution of that condition, not merely instantiate a replacement owner.

`DirectShellPairingCode` is borrowed only during `start`; temporary copies are
wiped in finally blocks, including exceptions. A real async backend must own and
wipe its copy through physical drain. No code/key/credential bytes enter
StateFlow, tickets, outcomes, logs or saved snapshots. This is best-effort JVM
wiping, not proof against immutable String/OS/GC/dependency copies. The fake tests
use a synthetic canary, never real pairing material.

## Verification Receipt

Receipts and preserved failed formatting attempts are under
`C:/wg-pairing-owner-r1600/`; no generated artifact belongs in Git.

| Gate | Result / scope |
| --- | --- |
| Final focused JVM |49/49, one suite,0 failures/errors/skips; virtual time, no sleeps or real I/O |
| Full JVM |1233/1233 in181 suites,0 failures/errors/skips; all180 baseline suites/counts retained plus49 tests in one new suite |
| ktlintCheck |PASS, no exclusions/assertion weakening |
| Offline Flight/output replay |40/40 PASS; unchanged tools and production media source |
| lintDebug / lintVitalRelease |PASS; Debug0 errors/134 warnings, identical issue multiset to baseline, no new warnings |
| Android builds |assembleDebug, assembleDebugAndroidTest, packageDirectShellProbeDex, assembleRelease PASS; native arm64-v8a/armeabi-v7a/x86_64 |
| Existing isolation |21/21 source, bytecode, Debug/Release APK DEX/resource/manifest guards PASS, unchanged expectations |
| P1 isolation |17/17 PASS, including unwired composition, unchanged existing app/build/native files and corrected Exception handlers in both variants' owner/observer/staged-handle bytecode |
| Compiled native guards |6/6 PASS: three Debug RT-TLS and three Release Flight Recorder exclusions; unchanged native source |
| CTest |Not rerun: no C++/JNI/native build-input changes; inherited26/26 Debug/Release is historical, not a new result |
| Hardware |NOT EXECUTED, including no ADB read-only access |

The focused tests cover readiness/reason/adapter/permission mapping, zero passive
work, duplicate intent/code, invalid ASCII codes, absolute expiry with late and
early timers, work budget across stages, cancel at consent/discovery/pair/commit,
physical-drain backpressure and exceptions, linearized commit/cancel, revision
changes at publish, duplicate/late results, new process ticket rejection,
reattachment without resubmission, reentrant callbacks, exactly-once disposal,
secret canaries/wiping and100 repeated fake enrollments without a live timer or
operation leak. These are modeled lifecycle/permission tests, not actual Android
rotation, process-kill, notification or Keystore acceptance.

Two initial formatting attempts stopped on newly added overlong lines before
running tests (`focused-initial.log`, `focused-second.log`). They are preserved,
then corrected without rule suppression. Initial43 tests passed in
`focused-third.log`; after final ticket-writer contract refinement and three
additional cases, initial46 passed in `focused-final.log`. Review then found
that ordinary checked port exceptions could escape RuntimeException-only guards,
including skipping a requested operation close after staged disposal failed.
Three deterministic negative tests reproduced this before correction:3/3 FAIL,
not a hardware/baseline regression. The focused XML, exit code and log are
preserved as `checked-before-fix*` / `jvm-checked-before-fix*`; canaries are synthetic.

The minimal fix broadens six catches in the two **new** model files to Exception,
still not catching fatal Errors. The three new tests exercise factory/start,
committer/publisher, cancel/close and staged finalization. Final49/49 and
full1233/1233 pass in `focused-corrected.log` / `jvm-ktlint-corrected.log`, with
`jvm-focused-corrected-results.json` / `jvm-full-corrected-results.json` and their
XML copies. Initial sources/four built artifacts and their hashes are preserved
under `pre-checked-exceptions/`; no historical evidence is replaced by the fix.
Full XML/summary is saved separately from filtered XML; counts are not added.

Final application source SHA256, frozen before integrated builds:

| File basename | SHA256 |
| --- | --- |
| DirectShellPairingContract.kt | `837FE0DCE3A18ED7F03043AE37343EB347D926C4F0B6E0EFF640E82F3F444E5A` |
| DirectShellPairingCoordinator.kt | `43F5298835487467CFF04E80B0B245CF27B3CDB49B9CCAE0EF98335EDEAA0AA0` |
| DirectShellPairingCoordinatorTest.kt | `D2E125B83716CD24F06EA51A8AE24DFB7C6DECB8FC16E47B5A89AD449D56934D` |

The only documentation delta is this new receipt plus updated
`CURRENT_HANDOFF.md`, `DirectShellReleasePairingArchitecture.md`, the executed-P1
banner in `DirectShellReleasePairingImplementationPrompt.md`, `README.md`,
`CurrentNonRootDebt.md` and `docs/FutureRoadmap.md`. No unrelated source/test or
historical failure report is modified. APK/build/receipt identities and final
preservation results are recorded below.

Final artifacts, relative to the isolated worktree (not installed anywhere):

| Artifact | SHA256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `A71135D910F3FC57256AE817A2221AABD2DFF699D3475660E5525FE80E0BEAF6` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `0E1450B362F14436E7FA37512FA0B34DD77B989C935FDFF6B32ED9457F86084F` |
| `app/build/outputs/directshell-probe/warpnect-directshell-probe.jar` | `25672C572A44D5207D86AFE0D0DD454A8F2BA3F327DC17972C49F005A2DA86BC` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `5F469C06CEC033E704C8297F19F738D77E89636A5C20648D5D0E566D6D7DA171` |

Traceability is **parentfaa9f0f + the exact three uncommitted application files
above -> final builds -> artifact SHA**. These are not artifacts of HEAD alone,
not a signed Release deployment and not evidence from the original dirty tree.
AndroidTest's APK hash remains unchanged because its source/configuration did
not change; it was built/checked, not run on a device. Helper is the existing
DEBUG DEX package, not a newly supplied Release runtime bundle.

Final logs: `focused-corrected.log`, `jvm-ktlint-corrected.log`,
`android-builds-corrected.log`, `source-set-final.log`, `owner-isolation.json`,
`lint-final-comparison.json`, `debug-native-symbols-final.json`,
`release-native-symbols-final.json`, `offline-replay.log`, `jvm-delta-final.json`,
`source-freeze.json`, `artifacts.json`, `final-source-test-doc-manifest.json`.
The initial full Android build also passed (24m09s); final corrected build passed
(7m54s), reusing unchanged native outputs. No compiler/lint rule was suppressed;
preexisting Kotlin/deprecation/third-party/native warnings remain in the logs.

Reproduction uses the isolated source cohort, JDK21.0.2/toolchain17,
Gradle8.12, SDK35/min26, NDK27.0.12077973/CMake3.22.1 and
`CMAKE_BUILD_PARALLEL_LEVEL=2`. Execute with `--max-workers=1 --console=plain`:

```text
gradlew.bat :app:testDebugUnitTest --tests *DirectShellPairingCoordinatorTest
gradlew.bat :app:ktlintCheck :app:testDebugUnitTest
gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:packageDirectShellProbeDex :app:assembleRelease
node --test tools/hardware/SystemAudioFlight.test.cjs tools/hardware/SystemAudioOutputContract.test.cjs
```

Final scope is exactly10 paths: three new app/test files, six modified status/
planning documents and this new report. Index stays empty, HEAD staysfaa9f0f,
and all P1 changes remain uncommitted by instruction. Diff hygiene covers tracked
and new files; SHA256 checks confirm no source edits during final gates.

Preservation check PASS: the source pairing worktree atfaa9f0f stays clean;
Video/report7ef9960 retains its11 dirty reporting paths; hardware656c378 retains
its6 reporting paths; frozen G5/functional worktrees remain clean at656c378/
20928f4. Initial/final branch, HEAD, index SHA and dirty content SHA/mtime match.
The original research tree remains at3253518,85 tracked/411 untracked paths;
492 content hashes plus4 metadata records match its preserved inventory. Master
032b592/upstream9d0b4dc and Phase7 R2 SHA256
`0D279F7E8B17CAA6D6F25771CE4D7F75764231B2202040F9D0678146663A6CF6`
are unchanged. See `preservation-start.json`, `preservation-final.json` and
`original-final.json` in the task receipt directory. No reset/stash/clean/staging,
commit, merge, push or removal of evidence occurred.

No hardware validation was executed. Fake close/drain assertions prove the model's
state ordering only, **not** cancellation of the real libadb3.4.0 pair context,
socket or TLS/PAKE exchange. No native source change or new CTest claim.

## Preserved Boundaries And Remaining Acceptance

No Activity/service/manifest/permission/dependency edits. No backend is wired into
main/debug/release composition. Main contains no libadb/BC/DEBUG imports. Release
continues to exclude them and expose no nonfunctional setup action. No Host,
Registry, provider planner/cache, helper, shell-command or Session API is a port
of the new owner. Client operation and the existing DEBUG user-controlled flow
are unaffected.

No media, native ABI, protocol, authentication, anti-replay, provider ranking or
COMPLETE-plan changes. Video + SystemAudio + Input remain mandatory; microphone
remains optional. Audio85/70ms, admission8ms, output4ms, microphone80/70ms and
IPC freshness40ms remain unchanged. Video observer is not imported. Phase7 R2
remains proposed/unchanged; S0 is not begun.

P2 is NOT begun. Its separately approved security/lifecycle addendum must first
prove real cancellation/physical drain, bounded stalled-peer behavior, endpoint/
network identity, dependency log redaction and atomic credential transactions.
The inspected3.4.0 manager timeout/close does not prove those properties. Do not
promote DEBUG dependencies, create a second ADB implementation, adopt/replace old
trust, or register a Release runtime as a shortcut. P3-P5 UI/runtime/activation
remain separate gates in the [architecture](DirectShellReleasePairingArchitecture.md).

Later hardware acceptance requires a separately authorized, legitimately unpaired
fixture and verified rollback. It must exercise API30+ permission/Settings/code
entry, rotation/process death, denied/expired/cancelled/stalled pairing, network/
trust changes and zero orphan work, then explicit Host acquisition/COMPLETE
qualification and Client/media regressions. No existing device needs to be
unpaired merely to test this pure P1 model.

G1-G5 hardware remains PARTIAL; historical audio delivery/wakeup/admission/
timestamp and Video HOME/REOPEN/EADDRINUSE debt remains OPEN. This groundwork is
not end-user Release IMPLEMENTED, HARDWARE VALIDATED, QUALIFIED or PRODUCTION-READY
and does not complete Phase6.5.
