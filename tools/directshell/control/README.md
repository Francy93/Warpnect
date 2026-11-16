# DirectShell Control Review Artifact

This source is not part of a Warpnect APK or a launcher. On a configured
Windows host, `Build-Control.ps1` accepts explicit JDK 21 and Android SDK
roots, and a new external output directory. It compiles the two program
classes separately from the host verifier/tests, runs loopback tests, and
produces a DEX, exact class inventory, disassembly and SHA256 receipt.
It does not run Android, ADB or a privileged process.

## Wire Profile

The Control profile retains the Debug framing family and status/command
numbers but is intentionally not wire-compatible with the legacy probe:

- Big-endian 4-byte body-plus-MAC length, then magic `WNDP`, version 2,
  direction byte (1 request, 2 response), positive 8-byte request ID,
  one command/status byte, positive 8-byte generation and 4-byte payload
  length. The fixed header is 28 bytes; requests have no payload.
- HMAC-SHA256 authenticates the header and payload. The per-launch
  32-byte secret derives its session key by HMAC of ASCII
  `warpnect-control-v2\n<generation>\n<lowercase-dex-sha256>`.
  A launch protocol must ensure that secrets and generation are never
  implicitly reused. Authentication failures close without a reply.
- Only PING (1), GET_RUNTIME_INFO (2) and SHUTDOWN (3) are dispatched.
  Shutdown requires a previous authenticated identity exchange on the
  same connection. Unsupported IDs do not invoke any other role.
- One IPv4 loopback connection, at most 16 requests, one 15-second
  startup/connection/exchange budget, no background worker, no retry.
  The launcher must drain the owned ready pipe; no pipe ownership or
  device launcher is supplied here.

The entry point rejects non-UID2000 launch and corroborates the digest
of the single DEX path found in CLASSPATH. This is not a proof of which
bytes ART loaded. The launch side must measure process creation identity
and pin the actual artifact, endpoint and launch handles independently.
The offline witness validates only supplied measurements and
authenticated frames. An acknowledged shutdown alone leaves the
witness incomplete; physical socket, process and launcher-resource
release need separate evidence. Neither the DEX nor the verifier is
registered or qualified as an Android runtime.
