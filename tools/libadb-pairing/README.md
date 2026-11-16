# Controlled DEBUG Pairing Extension

This retains `com.github.osservatorionessuno:libadb-android-bc:3.4.0`, including
its original dependency metadata and all non-pairing class bytes. It is NOT a
new ADB implementation, public onboarding adapter or library version update.

Original source: upstream revision `dcd931071b5e4b9ebc0ac598201755d5a8814b71`,
`libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java`.
The `upstream/` copy is immutable evidence; `patched/` is the reviewed derived
source compiled by the Gradle transform. `connected-socket.patch` records their
exact difference. No generated JAR/AAR is committed.

Original AAR SHA256:
`62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB`.
Original source LF SHA256:
`9E7B43ECCCC21B9DC75146BB7AEAEF9F788404EECD95943061512878E9A7D899`.
The build rejects a different original AAR. Other AARs pass through byte-for-byte.
`OwnedPairingTransform` changes only the context/nested-class closure; all other
archive-entry payloads and module dependencies are preserved. Standard Gradle
derived transform caching is used; the original dependency cache is never edited.

## Reproduce

Use the project's JDK/SDK and cached pinned dependencies:

```powershell
./gradlew.bat :app:verifyOwnedPairingReproduction :app:reportOwnedPairingArtifact --offline
./gradlew.bat :app:testDebugUnitTest --tests '*DirectShellOwnedPairingProtocolTest' --offline
./gradlew.bat assembleDebug assembleDebugAndroidTest packageDirectShellProbeDex assembleRelease --offline
node tools/libadb-pairing/VerifyOwnedPairing.cjs <selected-patched-aar> <receipt.json> --apks
```

The reproduction task builds twice in independent output directories and compares
both with the actual Gradle-selected AAR. ZIP ordering and timestamps are fixed;
compiler version/classpath are part of the provenance (JDK bytecode target 8).
Debug consumers request the derived artifact; no Release dependency is added.
Host tests load that actual selected library, not a protocol-class overlay.
Only a no-op `android.util.Log` test shim substitutes an Android framework API.
It is never included in an APK, nor is it an Android-runtime qualification.

## Scope

Legacy constructors/start remain. Only the opt-in connected path borrows an
already-connected, exact-endpoint socket and invokes CPU-only owner checkpoints.
The caller owns close, absolute time and worker lifetime. Cleanup/destruction
stays on the protocol worker after owner socket closure. Evidence describes
verified PAKE/peer-info, not Android trust, durable storage, Session or Host authority.
The GUID is bounded opaque NUL-terminated data; no UUID/255-byte policy is invented.
Raw peer-info logging is removed. No custom cryptography or wire fields are added.

## Attribution And License

The upstream file declares `GPL-3.0-or-later OR Apache-2.0`; this derived file is
distributed under its Apache-2.0 option. Its SPDX notice and upstream provenance
are retained, and the complete selected license is in `LICENSE-Apache-2.0`.
Warpnect changes: connected-socket/checkpoint/result interface, worker cleanup,
bounded peer validation and removal of raw peer-info logging, 2026-10-28.
Unchanged dependency files retain their existing notices and licenses; this does
not relicense libadb, Bouncy Castle or Warpnect as a whole.

Wire references: AOSP Android 11
[PeerInfo layout/types](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-11.0.0_r1/adb/pairing_connection/include/adb/pairing/pairing_connection.h),
[pairing exchange](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-11.0.0_r1/adb/pairing_connection/pairing_connection.cpp).
Remote GUID/certificate evidence must not be inferred from mDNS or treated as
proof that any particular real daemon saved trust. No device pairing is tested here.
