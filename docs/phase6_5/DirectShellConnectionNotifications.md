# DirectShell Wireless Debugging connection notifications

Tablet X700_EEA / API33, 2026-09-28. This is a follow-up to the
[Tablet runtime reliability report](TabletDirectShellRuntimeReliability.md),
which remains a historical account of the previous APK.

## Cause

During initial Host preparation, `DirectShellRuntime.launchInitialFeatureServers`
launched the PCM, Input and Video feature servers sequentially. It reused one
`DirectShellSelfBootstrapController`, but each call to
`bootstrapPreviouslyAuthorizedFeatureServer` rediscovered the trusted
`_adb-tls-connect` endpoint, opened a new app-originated ADB/TLS connection,
launched one `app_process` server, and disconnected. The three servers were
necessary for the existing capability architecture; three ADB connections were
not. Android, not Warpnect, published notification `android/62` on its
`DEVELOPER_IMPORTANT` channel, shown as `Wireless debugging connected`, for
each connection. These were not new pairing requests or audio notifications.

The previous final-APK trace has three role-specific `CONNECT result=PASS`
events per Host enable. A separate normal baseline Host cycle reached Host
Available and yielded three `android/62` notification enqueues, at device
epoch 1790572727.900, 1790572730.658 and 1790572733.645.

## Change

Initial Host preparation keeps the authenticated ADB/TLS transport open for
the fixed PCM, Input and Video launch sequence, then closes it when the
controller exits. Each feature still has its own fresh generation secret,
`app_process` server, HMAC readiness/negative-auth check, shell identity
check and independent qualification result. A failed launch closes the
transport; the next role may attempt an ordinary fresh connection. Individual
feature replacements retain their existing one-shot connection behavior.

No notification is suppressed. One Android Wireless Debugging connection
warning per new Host acquisition is expected and is a useful security signal.

## Hardware result

The changed debug APK SHA-256 was
`D3EC05EB7F0CEC439B968CA16A3A6ABAD6C74E92C6E4E55F22C93FB093BBC67F`.
It was installed on the Tablet without clearing app data. Three consecutive
silent, normal UI Enable Host -> Host Available -> Disable Host -> Ready cycles
passed, without a hidden retry:

| Cycle | Audio/Input/Video launch | ADB connect/reuse | Android notification enqueues | Ready after disable |
| ---: | --- | --- | ---: | --- |
| 1 | 3/3 PASS | 1 connect, 2 reuse | 1 | PASS |
| 2 | 3/3 PASS | 1 connect, 2 reuse | 1 | PASS |
| 3 | 3/3 PASS | 1 connect, 2 reuse | 1 | PASS |

The runtime reported `uid=2000`, `package=com.android.shell`, all three
backends `DIRECT_SHELL`, and all Shizuku backends `false`. After each disable,
the runtime released three prewarmed servers; final inspection found zero
DirectShell helper processes and zero temporary bootstrap secret files. No
remote Session or audio tone was used in this investigation.

This result closes the initial Host-preparation notification churn on the
tested Tablet. It does not claim that Session-time feature replacement or
other Android versions can never issue additional system warnings.
