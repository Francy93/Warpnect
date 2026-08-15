# Mbed TLS 3.6.7 provenance

RFC-005E vendors Mbed TLS 3.6.7 as the native cryptographic provider for
Warpnect Session Datagram Protection V1. The source archive was obtained from
the official Mbed TLS GitHub release:

`https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2`

The archive SHA-256 was verified against the release checksum:

`a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6`

Warpnect selects the upstream Apache-2.0 licensing option. The source retains
the upstream license files and notices.

Only the `mbedcrypto` target is linked by Warpnect. The application does not
link the Mbed TLS TLS, DTLS, X.509 or example-program targets. RFC-005E uses
Mbed TLS solely for standard AES-128-GCM, HMAC-SHA256, HKDF-SHA256 and secure
zeroization primitives in the portable native packet-protection runtime.
