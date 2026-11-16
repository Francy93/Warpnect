package io.warpnect.platform.privileged.directshell.pairing

import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Injected KeyInfo evidence; never calls an Android Keystore or claims platform execution. */
class AndroidDirectShellCredentialKeyStoreTest {
    private val alias = AndroidDirectShellCredentialKeyStore.PRODUCTION_ALIAS
    private var exports = 0
    private val opaque = object : SecretKey {
        override fun getAlgorithm() = "AES"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray {
            exports++
            error("unexpected_key_export")
        }
    }

    private fun material(
        owner: String = alias,
        key: SecretKey = opaque,
        bits: Int = 256,
        purposes: Int = 3,
        modes: Set<String> = setOf("GCM"),
        padding: Set<String> = setOf("NoPadding"),
        generated: Boolean = true,
        authentication: Boolean = false,
        backing: DirectShellKeyBacking = DirectShellKeyBacking.UNKNOWN,
    ) = DirectShellAndroidKeyMaterial(key, owner, bits, purposes, modes, padding, generated, authentication, backing)

    @Test
    fun `construction and evidence read never open keystore`() {
        var reads = 0
        val provider = AndroidDirectShellCredentialKeyStore(reader = {
            reads++
            null
        })
        repeat(10) { assertEquals(DirectShellKeyAvailability.NOT_CHECKED, provider.evidence.availability) }
        assertEquals(0, reads)
    }

    @Test
    fun `qualified opaque handle creates AEAD without key export`() {
        val provider = AndroidDirectShellCredentialKeyStore(reader = {
            assertEquals(alias, it)
            material()
        })
        assertNotNull(provider.existingAead())
        assertSame(opaque, provider.existing())
        assertEquals(0, exports)
        assertEquals(DirectShellKeyAvailability.AVAILABLE, provider.evidence.availability)
        assertEquals(DirectShellKeyBacking.UNKNOWN, provider.evidence.backing)
    }

    @Test
    fun `missing alias never creates or adopts a key`() {
        var reads = 0
        val provider = AndroidDirectShellCredentialKeyStore(reader = {
            reads++
            null
        })
        assertNull(provider.existingAead())
        assertEquals(1, reads)
        assertEquals(DirectShellKeyAvailability.MISSING, provider.evidence.availability)
    }

    @Test
    fun `foreign alias metadata cannot authorize a handle`() {
        val provider = AndroidDirectShellCredentialKeyStore(reader = { material(owner = "foreign") })
        assertNull(provider.existingAead())
        assertEquals(DirectShellKeyAvailability.POLICY_REJECTED, provider.evidence.availability)
    }

    @Test
    fun `wrong size purposes mode padding import or auth requirement fail closed`() {
        val variants = listOf(
            material(bits = 128),
            material(purposes = 1),
            material(purposes = 7),
            material(modes = setOf("CBC")),
            material(modes = setOf("GCM", "CBC")),
            material(padding = setOf("PKCS7Padding")),
            material(generated = false),
            material(authentication = true),
        )
        variants.forEach { value ->
            val provider = AndroidDirectShellCredentialKeyStore(reader = { value })
            assertNull(provider.existingAead())
            assertEquals(DirectShellKeyAvailability.POLICY_REJECTED, provider.evidence.availability)
        }
    }

    @Test
    fun `wrong algorithm cannot bypass metadata qualification`() {
        val key = object : SecretKey {
            override fun getAlgorithm() = "HmacSHA256"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray? = null
        }
        val provider = AndroidDirectShellCredentialKeyStore(reader = { material(key = key) })
        assertNull(provider.existingAead())
    }

    @Test
    fun `reader failure reports safe unknown without Throwable details`() {
        val provider = AndroidDirectShellCredentialKeyStore(reader = { throw Exception("synthetic-secret-canary") })
        assertNull(provider.existingAead())
        assertEquals(DirectShellKeyAvailability.UNKNOWN, provider.evidence.availability)
        assertFalse(provider.evidence.toString().contains("canary"))
    }

    @Test
    fun `hardware evidence is not inferred from successful lookup`() {
        val provider =
            AndroidDirectShellCredentialKeyStore(reader = { material(backing = DirectShellKeyBacking.SOFTWARE) })
        assertNotNull(provider.existingAead())
        assertEquals(DirectShellKeyBacking.SOFTWARE, provider.evidence.backing)
        assertFalse(provider.evidence.toString().contains(alias))
    }

    @Test
    fun `subsequent missing key does not reuse cached authorization`() {
        var exists = true
        val provider = AndroidDirectShellCredentialKeyStore(reader = { if (exists) material() else null })
        assertNotNull(provider.existingAead())
        exists = false
        assertNull(provider.existingAead())
        assertEquals(DirectShellKeyAvailability.MISSING, provider.evidence.availability)
    }

    @Test
    fun `DEBUG arbitrary and malformed aliases are rejected before lookup`() {
        listOf(
            "warpnect.directshell.selfadb.debug.v1",
            "arbitrary",
            "warpnect.test.directshell.pairing.aes256.v1../x",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { AndroidDirectShellCredentialKeyStore(it) }
        }
    }
}
