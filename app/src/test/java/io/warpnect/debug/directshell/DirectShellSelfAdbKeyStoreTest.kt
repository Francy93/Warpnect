package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellSelfAdbEndpointDiscovery.DiscoveryException
import io.warpnect.debug.directshell.DirectShellSelfAdbEndpointDiscovery.Failure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectShellSelfAdbKeyStoreTest {
    private val records = mapOf(
        "private_key" to "encrypted-private",
        "certificate" to "encrypted-certificate",
        "trusted_connection_service_name" to "encrypted-service",
    )

    @Test
    fun onlyAbsentIdentityRequestsPairingWithoutDecrypting() {
        val error = assertThrows(DiscoveryException::class.java) {
            read(mapOf("unrelated_setting" to true)) { throw AssertionError("unexpected decryption") }
        }
        assertEquals(Failure.CONNECTION_SERVICE_PAIRING_REQUIRED, error.failure)
    }

    @Test
    fun partialIdentityDoesNotRequestPairingOrDecrypt() {
        records.keys.forEach { missing ->
            assertUnreadable(records - missing) { throw AssertionError("unexpected decryption") }
        }
    }

    @Test
    fun mistypedOrEmptyIdentityRecordsAreNotAbsent() {
        assertUnreadable(records + ("trusted_connection_service_name" to 5)) {
            throw AssertionError("unexpected decryption")
        }
        assertUnreadable(records + ("private_key" to "")) { throw AssertionError("unexpected decryption") }
    }

    @Test
    fun decryptionFailureDoesNotRequestPairing() {
        assertUnreadable(records) { throw IllegalStateException("key unavailable") }
    }

    @Test
    fun malformedDecryptedServiceIsRejectedAndBufferIsWiped() {
        val bytes = "invalid service".toByteArray()
        assertUnreadable(records) { bytes }
        assertArrayEquals(ByteArray(bytes.size), bytes)
    }

    @Test
    fun malformedUtf8CannotBecomeADifferentServiceName() {
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertUnreadable(records) { bytes }
        assertArrayEquals(ByteArray(bytes.size), bytes)
    }

    @Test
    fun readableServiceRetainsTheExactNameAndWipesTheBuffer() {
        val name = "adb-local-abc"
        val bytes = name.toByteArray()
        assertEquals(
            name,
            read(records) {
                assertEquals("encrypted-service", it)
                bytes
            },
        )
        assertArrayEquals(ByteArray(bytes.size), bytes)
    }

    private fun assertUnreadable(records: Map<String, *>, decrypt: (String) -> ByteArray) {
        val error = assertThrows(DiscoveryException::class.java) { read(records, decrypt) }
        assertEquals(Failure.CONNECTION_SERVICE_IDENTITY_REQUIRED, error.failure)
    }

    private fun read(records: Map<String, *>, decrypt: (String) -> ByteArray): String =
        DirectShellSelfAdbKeyStore.readTrustedConnectionServiceName(records, decrypt)
}
