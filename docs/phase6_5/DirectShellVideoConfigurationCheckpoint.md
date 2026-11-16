# G3 - Early Video Configuration Request

Existing sender correction only, on G1/G2. A single pending boolean coalesces
NeedConfiguration requests received before codec readiness. The first valid
configuration is sent normally and once more using the same config generation;
the existing keyframe event is raised once. No request payload queue, new retry
loop, new Session admission or encoder/decoder reconstruction is introduced.
Close clears the pending state. Later configurations cannot inherit a serviced
request. Previously committed receiver-side recovery is reused unchanged.

The existing authenticated Session/protector owns replay/generation admission.
VideoResync receiver_config_generation is an advisory codec-generation hint,
not another Session credential: an older hint can legitimately request the
current configuration. Do not invent a new wire-level rejection rule here.
Malformed reason/version/reserved bytes remain rejected by the existing codec;
stale protected generations/replays remain rejected by Session protection.

Real-UDP regression adds twenty early requests, no send before readiness, one
coalesced resend, exact generation, normal next configuration and closed-owner
rejection. Existing malformed, resync/keyframe, cooldown, cache-miss and protected
generation tests remain in the complete native suite. Exact commands/results and
file hashes are in the consolidation artifacts and integrated report.

Historical prerequisite report MicrophoneAudioVideoConfigurationPrerequisite in
the preserved worktree records failed-before and subsequent larger-candidate
hardware evidence. No current device installation or physical validation is
performed by this consolidation. Later HOME/REOPEN reassembly and pre-Session
EADDRINUSE failures remain separate OPEN debts.

Isolated gates: clean native configure/build, CTest Debug22/22 (1.94s) and
Release22/22 (2.91s), zero failures; Android assembleDebug PASS (42s), and
git diff --check PASS. G2 JVM/security/lifecycle source is unchanged. No hardware
test is performed and no existing failure evidence is overwritten.
