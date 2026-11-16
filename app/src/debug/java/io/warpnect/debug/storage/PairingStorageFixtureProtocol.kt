package io.warpnect.debug.storage

import java.util.UUID

/** Fixed DEBUG fixture protocol. No paths, key bytes, commands or production alias cross Binder. */
internal object PairingStorageFixtureProtocol {
    const val DESCRIPTOR = "io.warpnect.debug.storage.fixture.v1"
    const val PROCESS = "io.warpnect:pairing_storage_fixture"
    const val PREFIX = "wn-multiprocess-storage-"
    const val ALIAS = "warpnect.test.directshell.pairing.aes256.v1."
    const val CONFIGURE = 1
    const val IDENTITY = 2
    const val ACQUIRE = 3
    const val RELEASE = 4
    const val INSPECT = 5
    const val ARM = 6
    const val RESULT = 7
    const val DESCRIPTORS = 8
    const val EXIT = 9
    const val DEADLINE_MS = 10_000L

    fun validToken(value: String?): Boolean = try {
        value != null && value.length == 36 && UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    fun allowed(operation: Int): Boolean = operation in CONFIGURE..EXIT
}
