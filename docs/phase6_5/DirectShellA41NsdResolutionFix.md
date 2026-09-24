# DirectShell A41 NSD Endpoint Resolution Fix

## Scope and Status

This report records the narrow repair for the A41/API31 DirectShell Wireless
Debugging bootstrap failure. It does not introduce DirectRoot, automatic
Wireless Debugging enablement, runtime remediation, or live capability-provider
migration.

The repaired result is
`WARPNECT_NSD_FOREIGN_SERVICE_STARVATION_AND_STALE_ACTIVE_RESOLUTION_BUG`.

## Observed Failure

The A41 retained a valid Wireless Debugging pairing, had `adbd` and `mdnsd`
running, and published a usable local TLS-connect endpoint. Desktop observation
showed `adb-R58N52B32JJ-1jXjic` at `192.168.1.36:40075`. Warpnect nevertheless
failed before `app_process` launch after finding several `_adb-tls-connect`
services and receiving NSD resolve error `3`.

On API31, the exact callback is `NsdManager.ResolveListener.onResolveFailed()`
and `NsdManager.FAILURE_ALREADY_ACTIVE` is numeric value `3`. The A41 framework
permits only one active NSD resolve for the client. This was not an absence of
an ADB TLS listener and was not a SystemAudio failure.

The old resolver accepted every discovered TLS-connect service. A foreign
service could occupy the sole resolve slot, the bounded caller could time out,
and API31 exposes no public `stopServiceResolution()` to cancel the still-active
framework operation. A late callback could then advance stale queued work. The
result was a Warpnect lifecycle bug, not an A41-specific branch or an Android
framework defect.

## Repair

`DirectShellSelfAdbEndpointDiscovery` now:

* persists the ADB service identity observed during the real pairing flow;
* resolves only the paired device's TLS-connect service;
* accepts the exact DNS-SD conflict alias `"<trusted> (n)"` while rejecting
  foreign or malformed aliases;
* owns each candidate by generation and invalidates lost candidates;
* serializes one resolve at a time;
* closes its queue when the bounded discovery caller completes, preventing a
  late callback from starting another resolve; and
* maps a fresh `FAILURE_ALREADY_ACTIVE` terminally and truthfully instead of
  misclassifying it as a missing service.

The existing one-time stale-endpoint refresh remains constrained to a concrete
connection refusal: it rediscovers once, excludes the refused endpoint, and
never retries authorization, TLS, or arbitrary failures.

The trusted identity is the non-secret ADB DNS-SD service name. It is stored in
the existing encrypted debug keystore after pairing. A historical pairing with
no recorded identity returns `CONNECTION_SERVICE_IDENTITY_REQUIRED` and asks
for a normal re-pair rather than guessing among local ADB services.

## Hardware Evidence

On the A41, a new user-authorized pairing recorded
`adb-R58N52B32JJ-1jXjic`; pairing used port `40235` and the connection service
resolved to port `40075`. Warpnect then completed local ADB, artifact validation,
`app_process`, shell UID/GID `2000`, authenticated HMAC control, and shutdown.
A normal Host admission subsequently selected:

```text
plan_type=SINGLE
video_provider=DIRECT_SHELL
system_audio_provider=DIRECT_SHELL
input_provider=DIRECT_SHELL
shizuku_started=false
```

The Tablet/API33 control also passed. Its base service record resolved a stale
port and the current endpoint appeared as the canonical DNS-SD alias
`adb-Z1382023HGM1895372-1laj6c (2)`. The alias resolved to the current local
ADB endpoint and completed a self-bootstrapped DirectShell Input smoke test.
This proves the alias rule serves standard DNS-SD conflict publication rather
than a device-name rule.

## Controlled Composite Validation

The one-shot DEBUG input suppression was then armed on the A41. The frozen
plan was real and compositional:

```text
plan_type=COMPOSITE
video_provider=DIRECT_SHELL
system_audio_provider=DIRECT_SHELL
input_provider=SHIZUKU
```

An A41 Host to S9 Client Session reached discovery, authentication, WNCP, and
Streaming without a deliberate tone. Video and SystemAudio were explicitly
DirectShell; the Host Input backend was `ShizukuUserService`. Approximately
five to six physical user touch interactions on the S9 capture surface produced
26 captured and forwarded Warpnect touch events and 30 input datagrams. The A41
received 30 datagrams, mapped and submitted the corresponding 26 events through
Shizuku with no injection error, and the Host target observed the injected
touch. The 26-event count is protocol/input-event evidence, not 26 separate
human taps. This is human reverse-input E2E, not an ADB-input substitute.

After semantic Disconnect and Disable Host, both UIs returned to `Ready`.
There were no DirectShell `app_process` servers and no Warpnect-owned Shizuku
UserService left on the A41. The external Shizuku server remains an independent
expected persistent process. A subsequent unsuppressed A41 admission returned
to the all-DirectShell `SINGLE` plan, proving no DEBUG suppression leaked.

## Tests and Limits

Focused JVM coverage includes trusted-service filtering, exact conflict-alias
acceptance, foreign and malformed-alias rejection, identity validation, and
the `FAILURE_ALREADY_ACTIVE` mapping. The implementation does not expand the
local ADB command surface, log credentials, create a device/OEM allowlist, or
modify media protocols.

## Validation Build

The final DEBUG APK used for the post-fix A41, Tablet, and S9 checks was
`app-debug.apk` with SHA-256
`A71955FF702D1ACC4FF879291B6A42BDD4711EA28CF927367F67C7DE1929A50A`, size
`33,427,909` bytes, timestamp `2026-09-24 19:47:50 UTC`, and ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. The identical installed `base.apk`
hash was verified on all three devices. Full Android gates passed with 709 JVM
tests, zero failures, zero errors, and zero skipped tests. Native source was
unchanged; the established Debug and Release CTest evidence remains 22/22 for
each configuration.

DirectRoot is `NOT_IMPLEMENTED`. Runtime Remediation is `NOT IMPLEMENTED`.
Live Migration is `NOT IMPLEMENTED`.
