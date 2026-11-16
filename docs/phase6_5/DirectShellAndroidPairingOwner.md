# DirectShell Android Pairing Owner

R1707 update: [D-P1-D-P4 are now accepted](DirectShellR1707FirstEnrollment.md)
within the approved scope. The AF25 preflight stopped before installation
because Android reported LOW storage. The unapproved state and ADB stop
below are the preserved r1706 baseline, not the current approval status.

## Scope And Contract

R1706 continues from `f017d978083b21e5b56d3857be490333fe6389bd`.
The approved first Android owner is DEBUG-only and unregistered. An explicit
fixture caller owns one instance per application-process lifetime. Each
instance composes one P1 coordinator, one existing drain handoff and the
existing synthetic composition. There is no application initializer,
singleton service, second coordinator, production credential generator,
Host/Session action, NSD or external endpoint. No manifest or permission
changes are required.

P1 and the transport use the same `SystemClock.elapsedRealtime()` domain and
the original 120s consent, 60s work and 3s cleanup limits. The
[Android clock](https://developer.android.com/reference/android/os/SystemClock)
includes time in deep sleep; [Handler delays](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,long))
use uptime. The queue is therefore a wake hint, not the deadline authority.
On an explicit resume signal P1 rechecks the current stage under its own
monitor, even if a timer is overdue or already dequeued. Old callbacks are
fenced by the existing attempt and timer versions. Environment invalidation
is a separate input, not a substitute for resume: it has distinct
reconciliation semantics. No wake lock, alarm, polling, background privilege
or consent replay is introduced.

Disposal closes P1 before requesting its observer stop. Concurrent disposal
does not repeat that handoff. The one-shot observation and independent
observer termination rules remain D-O1/D-O2. Cleanup expiry blocks admission
without claiming D; a real receipt can complete an observation still in
progress. If that observation ends without proof, nothing rearms it and
later worker exit alone grants neither D nor readmission. Physical socket
closure during deep sleep is **not guaranteed**. Process recreation has a
new in-memory epoch and no restored consent, ticket or code.

## Worker Effect Boundaries

The same original work context guards entry to recovery setup, anchor and
pending writes, native publication and transport connect. The storage
guard is a nonblocking worker checkpoint, not P1 authority. No rollback is
claimed if a native operation or I/O already entered. These are admission
checks at the call boundary, not a kernel-level deadline for a syscall.
A refused publication
after claim yields a truthful NOT_PUBLISHED receipt, not retry or restored R;
the already-written recovery/pending artifacts are retained. The existing
publication, durability, protocol, crypto and drain contracts are unchanged.
The former minimum 1ms connect allowance is not granted after expiry.

## Remaining Path

| Increment | Current result | Next boundary |
| --- | --- | --- |
| P1 | Unregistered Android owner and deadline lifecycle; existing coordinator and drain rules retained. | Production application registration/lifecycle and OS-level suspend/process-loss qualification. |
| P2 | Scoped r1704/r1705 physical synthetic evidence retained. Deadline checkpoints and modeled Network epoch invalidation exercise only the admitted synthetic operation. | D-P1-D-P4, exact Network/NSD authority, real pair-only adbd, actual blocked TLS writes and suspend/connect qualification. |
| P3 | The existing launch guard and owner lifecycle may be reused; neither presents a new production UI. | Approve private setup, notification/permission denial behavior and service lifetime. Rotation reattaches to the same owner; process recreation demands fresh user action. |
| P4 | Existing DEBUG runtime and helper remain untouched. | Approve a separately inventoried closed role/command/artifact set and verify its class/manifest closure before any Release extraction. |
| P5 | Not activated. | Approved P3/P4 bundles, signed Release and supported-device qualification. |

The existing Host setup controller can authorize opening a setup UI, not
authorizing an enrollment. A future private code action must bind to the
same live owner and exact P1 ticket, use no stored code, and never mint a
new attempt on delivery or on Activity return. The current debug probe JAR
packages all Debug DEX files; it cannot be the proposed P4 production
artifact as-is. The first P4 review should inventory its allowed control,
input, PCM and video roles separately, keep smoke roles and diagnostic
entry points out, and bind launch/shutdown to authenticated UID2000,
generation and verified artifact. Do not copy the Debug registration
provider or expose generic shell commands.

The full JVM run also rechecked the independent boundaries without enabling
them: 18 Host setup launch-guard tests, five Debug permission-action tests,
and 12 runtime registry/resolver/Host ownership tests. The future P4 control
server already has a closed `PING`, `GET_RUNTIME_INFO`, `SHUTDOWN` verb set,
but the current launcher also has an `InputSmoke` role sharing the Input
server class. A production class/entry allowlist must reject the smoke role
and its argument path, not just filter names in the package.

For Network admission, a discovered DNS-SD endpoint is routing metadata,
never evidence of trust. Review selection of one exact Network and epoch,
numeric same-device address scope, stale callback rejection and raw socket
binding before connect. On loss, only the current exact operation may be
stopped; no network migration, fallback port or second socket. R1706 tests
use a modeled epoch signal and do not imply a working Network/NSD backend.

The SDK 35 API inventory dates the public Network-specific discover overload
and [NsdServiceInfo.getNetwork](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#getNetwork())
to API33 or T Extension 3, and the public stop-resolution method to API34 or
extension 7. API30/31's legacy discovery cannot supply that same callback
provenance. The strict initial contract should fail closed without the
necessary Network evidence. Supporting API30/31 production enrollment needs
a separately reviewed provenance design, not copying the Debug
all-interface filter or assuming bindSocket authenticates DNS-SD. For
physical cancellation, require an observed blocked write inside the pinned
TLS path and subsequent socket/worker/borrow release. A delayed peer read,
an injected error or a raw-TCP write stall does not qualify TLS backpressure.

D-P1-D-P4 remain **unapproved**. The preferred next storage design and
alternatives are:

| Decision | Recommended | Alternative and tradeoff |
| --- | --- | --- |
| D-P1, first creation | Exact fresh ticket under the same stable cross-process lease, known empty namespace and alias absent; keep ownership through authenticated anchor reread. | Create key before the lease: simpler code, but can race with another app process and orphan or replace an alias. Do not use. |
| D-P2, interrupted provisioning | Preserve alias/ciphertext and block on uncertainty, including orphan alias and key loss; read-only reconciliation. | An explicit repair transaction might recover availability, but needs separate ownership proof and destructive-action authority; never automatic. |
| D-P3, RSA backing | Existing PKCS8 software RSA encrypted with opaque AndroidKeyStore AES. Report the AES backing separately from RSA. | Non-exportable RSA would need a different signer/codec and fresh pinned TLS compatibility review. |
| D-P4, adoption | First enrollment revision zero, no implicit adoption or Debug migration. | Separate explicit verified import can preserve trust where compatible; needs new provenance/rollback policy and device qualification. |

Approving that storage delta does not approve Network/NSD selection, real
adbd, UI/FGS registration, a Release dependency, or Release activation.
Overall **P2_PARTIAL_BLOCKED**. Emulator and R0b remain suspended.

## Verification Receipts

`C:/wg-af30-r1704/evidence/mission-r1706/` preserves the first resume
reconciliation mismatch and the first publication test's incorrect counter,
alongside the passing 1,648-method JVM run (203 suites, no skips). The only
later change was a JVM test rename/formatting correction; see the final
source manifest and the passing 116-test focused rerun. The 40 offline
replays passed. Global ktlint still reports 111 preexisting findings
(67 Debug production-source findings and 44 JVM test-source findings);
the affected paths are unchanged from r1705. The introduced findings were
corrected and the affected TestDebug source-set check passed.
Pinned patched-libadb reproduction
passed with the unchanged SHA256
`BC687AD0F7744449F0DDCBA57D8500CD3AE564D648F213CF1110BB43020DB3D0`.
Ordinary unsigned Release assembly and the compiled isolation audit
passed. The audited manifest matches r1703, all three ordinary ABIs remain,
and the checked Debug owner, fixture, synthetic transport and libadb classes
are absent from Release DEX. Ordinary Debug and Release Lint passed with
127 and 34 warnings respectively, and no errors. No Release APK was
installed or qualified.

The exact API30 owner method compiled. The sealed target/runner, v2 signer,
manifest and ARM64 checks are in `owner-api30/apks.json`. It was not
installed or executed: the existing ADB listener on `127.0.0.1:5037` went
away and the task did not start, restart or replace it. No phone was changed.
When the existing server is restored, run only
`explicitAndroidOwnerUsesPlatformClockAndDrainsSyntheticPairing` on the
reidentified A41/API30, with the existing no-replace install, UID/profile
checks and owned teardown. Prior r1704/r1705 physical results are unchanged.
