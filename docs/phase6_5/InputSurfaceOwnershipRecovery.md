# Input Surface Ownership Recovery

## Isolated Scope

The Session owns the Android input capture controller and transport sink. An
Activity/View recreation replaces only its UI endpoint. Rebinding resets held
input safely, detaches the old View and binds the latest View without creating
a second Session owner. Pending prepare/start resolves the current Surface;
late old-View removal and old binding closure cannot clear a newer owner.

The intended checkpoint includes only AndroidInputCaptureController, the Input
hunks of AndroidSecureSessionComposition/AndroidSessionPipelineFactory/
DefaultAndroidSessionPipelineBindings, the four-case actual Android regression,
and this scope report. It deliberately excludes Session closure/discovery
logging and microphone onClosing hooks sharing those files. The complete
physical APK contains those other inherited changes; an isolated checkpoint is
not asserted to reconstruct the whole APK.

## Evidence

The four actual Android cases cover Activity recreation and late clear, removed
Surface/reopen with unchanged sink, pending start with a newer View, and
exclusive binding/late old-owner close. r1349 passes the17-case component set
on EACH API31/API29/API26 endpoint, including these four cases.

Frozen main30C3A3C589285B8A911EB7E1A5A49DC13EBE039FAD6134430D432BDF020A78A3
also passes r1334 three90s Video/recreation windows with12 protected remote
touch/keyboard events in total. See
[the preserved physical report](VideoSystemAudioRegressionRecovery.md).
These results are scoped functional evidence, not universal input/device
qualification or proof that all later unrelated audio failures disappeared.

## Separation Checks

r1368 constructs an explicit Input-only patch against0a854cd and checks/applies
it to a separate HEAD source export under ignored build/validation. The live
worktree is not reset, stashed, replaced or cleaned. Unrelated inherited hunks
remain intact. Compilation and checkpoint results are recorded after completion.
No generated export, patch, private diagnostic data or artifact belongs in Git.

r1386 isolated HEAD+Input-only source compile PASS8m39s,28 executed tasks:
compileDebugKotlin and compileDebugAndroidTestKotlin, offline/one worker.
The newly created export is under ignored build/validation, outside app; no
historical evidence was moved. Its four Input source files plus four-case test
compile without the excluded inherited microphone/Session/discovery hunks.
Current full-worktree r1385 JVM rerun also PASS1154/177 suites,0 failures/errors/
skips. r1373 components17/17 EACH and r1372 HOME/reopen with four fresh protected
Input events independently exercise the actual current implementation.

r1389 full Android gates PASS8m54s: ktlintCheck, lintDebug, assembleDebug,
assembleDebugAndroidTest and packageDirectShellProbeDex. Main/test APK hashes
remain30C3/C2E823AF; no production source changes are needed for the final
startup controls. CTest Debug25/25 and Release25/25 pass separately. Index-only
selection is limited to the six files described above; unrelated live source
hunks and diagnostic evidence remain outside this checkpoint.
