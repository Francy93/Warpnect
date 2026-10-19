# SystemAudio Admission Checkpoint

## Isolated Change

This source checkpoint is based on `1cc82af`. It contains only:

- `audio_playback_capacity_wait.h`: optional wait-enable and callback-margin
  parameters; defaults retain existing behavior.
- `audio_oboe_playback.cpp`: enable one output-burst margin only for SystemAudio.
  This checkpoint does not disable microphone wait or change microphone clocks.
- `audio_playback_ring_tests.cpp`: replay the recorded r866 callback delay,
  including the failing old deadline and successful corrected deadline.

At 48 kHz, a full ring receiving 240-frame packets needs three 96-frame output
bursts to free a whole packet. The nominal 6 ms deadline expired at 6.044 ms;
the recorded callback released credit by 6.924 ms. An additional burst provides
a hard 8 ms deadline. It is never renewed; fitting/invalid blocks do not wait,
and a disabled wait remains disabled. No extra queue or permanent reserve.

SystemAudio ring capacity remains 4080 frames / 85 ms and initial reserve
3360 frames / 70 ms. Compared with the old admission limit, the maximum added
producer wait is 2 ms under pressure, not 2 ms added to every packet.

## Physical Evidence Scope

The code was exercised as part of the larger preserved working-tree APK:
`1C0AEA7857597BD44A318AEAF01CA2395798F76222344FCE693F8E353BFA2C24`.
This subset commit is not asserted to reconstruct that complete APK: unrelated
microphone transition and diagnostic changes intentionally remain uncommitted.

2026-10-19, A41/API31 Host -> S9/API29 Client, S9 AP-only 5745 MHz, one Enable
Host, three fresh consecutive Sessions:

| Cycle | Positive playback progression to stop, s | Active 997 Hz, s | Rejected / underrun / xrun |
| --- | --- | --- | --- |
| 1 | 115.478 | 75 | 0 / 0 / 0 |
| 2 | 112.106 | 75 | 0 / 0 / 0 |
| 3 | 117.960 | 75 | 0 / 0 / 0 |

Counters include startup through native stop. OFF/ON/OFF numeric signal,
REMOTE_ONLY routing/restoration, Disconnect/Ready/Waiting/rediscovery, Video and
Input smoke and cleanup are recorded. The owner confirms continuous/clean S9
audio, but faint at 7/15 and manually raises volume. Keep that gain/volume caveat
separate. No human confirmation is invented for silent/inconclusive fixtures.

The exact completion scope is
`SYSTEM_AUDIO_A41_TO_S9_AP_ONLY_QUALITY_VALIDATED`, not general Android/network
qualification. Independent reference UDP controls were 5/6 strictly zero-loss,
one missing of 84000. Historical 200..600+ ms network gaps remain unresolved;
the local admission fix does not absorb them. RF observer work stays suspended.

Preserved detailed evidence (not Git contents):
`app/build/reports/r1179-systemaudio-reference-final-three/`,
`r1182-reference-campaign-analysis.json`, `r1186-reference-media-cadence-analysis.json`,
and the current-worktree report `SystemAudioReferenceNetworkValidation.md`.

## Verification And Separation

A separate HEAD-based export contains just these three code/test changes.
Its admission header and replay body match the physical candidate after normal
line-ending normalization. The Android call site's SystemAudio arguments are
equivalent; old microphone defaults remain in this isolated source tree.
NDK arm64/API26 syntax checking of the isolated Android call site passes, with
only three pre-existing Oboe unused-parameter warnings.

The isolated native suite and the larger worktree suite are separate gates.
Fresh isolated CTest is Debug 22/22 (9.59 s) and Release 22/22 (8.95 s), both
zero failures. The isolated ring target has 17 functional cases. Do not report the larger
candidate's 25 targets as the isolated HEAD tree's 22 targets. The first export
omitted benchmark inputs required by CMake: preserve that harness failure;
the corrected export adds unchanged HEAD benchmarks, not product changes.
An initial CRLF patch-check failure is preserved and corrected by emitting
LF patch data; no source/index was changed by the failed check.

No microphone 80/70 ms reserve, 40 ms freshness, decoder/clock policy, codec,
socket, authentication, anti-replay, Session generation, provider planner,
capability negotiation or recovery/migration behavior is changed here.
Remaining shared-file microphone modifications stay in the working tree.
