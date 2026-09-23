# DirectShell Production Bootstrap Feasibility

## Scope

This Phase 6.5 document records the DEBUG-only feasibility result for
acquiring Warpnect's existing DirectShell runtime without a desktop computer
launching `app_process`. It does not make DirectShell a release feature, does
not remove Shizuku, and does not add DirectRoot or a general shell-command
interface.

```text
Warpnect normal app
  -> user-authorized Android Wireless Debugging pairing
  -> app-owned TLS ADB connection to 127.0.0.1
  -> constrained shell identity/hash/provision/launch commands
  -> app_process running Warpnect's known server class
  -> authenticated Warpnect loopback control
```

The final physical Tablet verification used its Android 13 / API 33 build and
the DEBUG APK whose SHA-256 was
`a86157045c1f5d32b1b75d0d3117499f27eac5580f0f681fae66e53e38c46839`.

## Existing external bootstrap versus this probe

The earlier DirectShell experiments depended on a development machine to push
an artifact and run `adb shell app_process`. This probe instead derives the
artifact from `ApplicationInfo.sourceDir`, verifies its SHA-256 locally and
through the app's own authenticated ADB connection, then asks local `adbd` to
launch only the known Warpnect server class.

The development machine was used to install the DEBUG APK, observe logcat,
and tap the already-posted notification action during automated testing. It
never issued `app_process` for a counted run. The app's local ADB connection
issued the shell launch command.

## Pairing and authorization model

`DirectShellSelfAdbEndpointDiscovery` discovers Android's current DNS-SD
pairing and connection endpoints. `DirectShellSelfAdbKeyStore` retains the
app-owned ADB host key only after the user completes Android's Wireless
Debugging pairing ceremony. The app cannot enable Wireless Debugging, approve
pairing, or bypass Android's consent UI.

The DEBUG notification deliberately has one `RemoteInput` field: the six-digit
pairing code. Host/port fields were removed because mDNS discovers both ports.
The notification exposes an explicit **Invia codice** action and a **Riprova
avvio** action for an already authorized key.

On the Tablet, the notification editor is rendered by vendor SystemUI, not by
Warpnect. The public `RemoteInput` API does not let an app set its IME action
or force Enter to submit. AOSP's generic SystemUI treats a confirm key as
send, but this SystemUI/IME inserts a newline. `pairingCode()` therefore
normalizes surrounding whitespace, so a trailing newline is harmless when the
user taps **Invia codice**. An app-owned Activity could control Enter, but
would force the user to leave Android Settings and is intentionally not used
for this flow.

After a successful pairing, the same app-owned key connected again without a
new pairing code in all counted restart cycles. The connection port observed
on this Tablet was `43923`; that is diagnostic evidence only, not a hardcoded
production value.

## Artifact and secret handling

The launch contract is purpose-specific:

- accepts only the installed Warpnect `base.apk` path under `/data/app/...`;
- compares local and remote SHA-256 before launch;
- launches only `io.warpnect.debug.directshell.WarpnectPrivilegedServer`;
- generates a fresh 32-byte secret and a fresh generation/port per launch;
- transfers the secret as TLS ADB stream data, never as a shell argument;
- writes the exact 32 bytes to one generation-scoped `0600` file;
- has the server consume and delete that file before accepting control;
- removes the secret and bounded startup-log files during teardown.

The process wrapper emits `WRAPPER_START` before `app_process` and the app
waits for that explicit, bounded marker before it closes the ADB launch stream.
This is a lifecycle handoff, not a retry or an arbitrary timeout increase. It
fixes the observed race where the earlier code accepted `$!` before the
detached wrapper had begun executing.

No secret appeared in the observed `adbd` command logs. The server retained
the established HMAC-SHA-256 control protocol, rejected an unauthenticated
request, and accepted authenticated `PING`, runtime-info, and shutdown only.

## Hardware evidence

| Device | API | Local ADB | Shell identity | app_process | HMAC / negative auth | Cleanup | Result |
|---|---:|---|---|---|---|---|---|
| Tablet X700_EEA | 33 | PASS | uid/gid 2000 | PASS | PASS / rejected | PASS | 5/5 handoff cycles; final APK 1/1 |
| A41 | 31 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| S22 | 36 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |

Each counted Tablet cycle established the following causal chain:

```text
app mDNS discovery
  -> app TLS ADB connection
  -> shell `id` reports uid 2000
  -> matching installed-base-APK hash
  -> app-originated app_process launch
  -> Warpnect server START result=READY
  -> authenticated runtime query
  -> unauthenticated HMAC request rejected
  -> authenticated shutdown
  -> PROCESS_EXIT code=0
```

No `app_process`, generation-scoped secret file, or generation-scoped startup
log remained after each cycle. The five handoff cycles used the same runtime
implementation before the final API-guard-only notification rebuild; the
final exact APK independently completed one full cycle. A synchronized final
notification screenshot is saved as `build-self-bootstrap-final-pass-tablet.png`.
One previous cold-start attempt exposed the
pre-marker launch race as `FAIL_DIRECTSHELL_APP_PROCESS_START`; the explicit
wrapper-start handoff replaced it, and the five subsequent counted cycles
passed without a hidden retry.

`adbd` also logged an existing `Invalid base64 key` warning while loading its
device key set. The app's TLS handshake nevertheless succeeded on every
counted cycle. This is external device key-store noise, not a Warpnect secret
or launch-command failure.

## Self-bootstrapped Input smoke

The same Tablet and paired app-owned ADB key were then used to launch the
existing `WarpnectPrivilegedInputProbeServer` through the same closed
`--secret-file` contract. The normal process started the DEBUG-owned
`InputSessionTargetActivity`, then issued only four authenticated probe
commands:

```text
INPUT_PREPARE
  -> INPUT_KEY_SEQUENCE count=1
  -> INPUT_TOUCH_GESTURE count=1
  -> INPUT_RESET
```

This is a bounded capability smoke test, not Input Session migration and not a
new hot-path IPC design. The server continues to reuse Warpnect's established
InputManager resolver, event factory, and input-state tracker.

| Device | Shell identity | Target-observed events | HMAC negative auth | Cleanup | Result |
|---|---|---|---|---|---|
| Tablet X700_EEA / API 33 | uid/gid 2000, `u:r:shell:s0`, `com.android.shell` | `KEY_DOWN`, `KEY_UP`, `TOUCH_DOWN`, `TOUCH_MOVE`, `TOUCH_UP` | rejected | no process, secret, or startup-log residue | implementation build 3/3 PASS; final exact APK 1/1 PASS |

Each counted cycle followed this causal sequence:

```text
normal Warpnect DEBUG action
  -> app-owned TLS ADB connection
  -> installed base.apk SHA-256 verification
  -> app-originated app_process Input server launch
  -> shell InputManager preparation
  -> real target Activity receipt of ordered key/touch events
  -> reset releases the deliberately pressed key
  -> authenticated server shutdown and process exit 0
```

Two pre-count repetitions were `INVALID_HARNESS_ATTEMPT`s: one test tap landed
on the already foreground Input target instead of the DEBUG launch action, and
one occurred before the post-install UI surface was ready. Neither launched a
server or indicated a DirectShell/Input failure. The corrected harness uses
`--activity-clear-top` and waits for the explicit normal-app action to appear
before tapping it.

## Security boundary

Wireless Debugging gives an authorized local app shell execution capability.
This probe constrains that power to known artifacts and a known server class;
it is not a terminal, arbitrary command API, arbitrary file opener, or FD
broker. The normal network Session cannot invoke the ADB transport directly.

## Limitations and next production work

FACT: Tablet/API33 DEBUG self-bootstrap is feasible after user-authorized
Wireless Debugging pairing.

FACT: The result proves bootstrap/control, not a release-ready Host runtime.

FACT: a real DirectShell Input capability can be invoked by the
self-bootstrapped server in the same run, with real target-Activity key and
touch receipt on Tablet/API33.

NOT YET PROVEN: self-bootstrapped DirectShell Video or SystemAudio in the same
run, and the pairing-key behavior across reboot, Wireless Debugging toggle,
and port rotation.

PROPOSAL: the next focused task should integrate the now-proven,
self-bootstrapped DirectShell Input runtime behind a production-ready
privileged-runtime resolver while retaining Shizuku until all release
lifecycle and compatibility gates are defined. That work should not remove
Shizuku or switch release capability selection yet.
