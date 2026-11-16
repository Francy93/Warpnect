# G2 - Authenticated Control And Session Ownership

Consolidation of existing work only. Base is G1 ad1d5f9 on3253518.
WNCP/WNSN payloads, protection, admission and Session-generation contracts are
unchanged. No Flight Recorder or lifecycle tracing is included.

Functional scope:

- Completed authenticated WNCP replies remain routable while Setup starts.
- One existing HostCommit reply survives Setup-to-lifecycle ownership transfer,
  with original expiry, setup/profile/endpoint/proposal hash checks and close.
- Graceful Client termination returns Idle; fatal failures remain Failed and
  close owned runtimes outside the coordinator lock. Old callbacks cannot affect
  a replacement attempt. No duplicate admission or protocol exchange is added.
- Generic pipeline closing notifications precede physical-source stop. Audio
  output is stopped before joining receive ingress; late PCM is rejected.
- Emergency reverse-input reset acknowledges enqueue without a privileged
  snapshot round trip on the closing caller.

The optional microphone interface gains a default no-op closing notification.
The G4 selected destination supplies its actual PCM-revocation override later;
G2 does not claim destination-specific quiescence before that integration.
Generic sink/source ordering and final ownership are independently tested here.
This is a documented runtime integration edge, not a G4 compile dependency.

Sources: control phase driver, Setup controller and CompletedSetupControlTransport,
coordinator/model/pipeline runtime, Android composition/factory/bindings,
DebugMicrophoneInjectionSession interface, audio receiver stop and reverse input.
Tests cover lost replies, conflicts, fixed expiry, close, stale-generation
callbacks, graceful/fatal termination, pipeline ordering and nonblocking reset.
Exact file/patch/hash manifest and gate results are retained with consolidation
artifacts; no generated evidence or private restoration data belongs to commit.

Historical evidence: provider finalization and capability-retry reports in the
preserved development worktree, plus later microphone campaigns, exercised
these corrections in larger APK cohorts. This isolated source is software-tested
only; no APK is installed and no new hardware qualification is claimed.
Independent audio/network/timestamp/Video debts remain open. Native/codec/media
parameters, provider independence, Complete Host requirements and RFC-006I are
not changed. No recovery/migration implementation is introduced.

Isolated verification: full JVM rerun 1055/1055, 166 suites, zero failures,
errors or skipped tests; ktlintCheck PASS. G2 has no native production delta.
Android lint, DEBUG APK, AndroidTest APK and helper DEX all PASS in the isolated
candidate (83 tasks, 12m48s). Both configured ARM ABIs compile. Exact results are
recorded in the per-checkpoint artifact manifest and integrated report.
