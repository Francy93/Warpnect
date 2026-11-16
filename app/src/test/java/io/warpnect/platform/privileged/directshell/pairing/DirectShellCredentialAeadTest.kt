package io.warpnect.platform.privileged.directshell.pairing

import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real host JCA, not Android Keystore execution or hardware backing. */
class DirectShellCredentialAeadTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val crypto get() = DirectShellJcaCredentialAead.software(key)!!
    private val aad = ByteArray(52) { it.toByte() }

    @Test
    fun `authenticated round trip keeps exact plaintext and caller input`() {
        val clear = "synthetic credential material".toByteArray()
        val before = clear.copyOf()
        val sealed = crypto.seal(clear, aad)
        assertArrayEquals(before, clear)
        assertArrayEquals(clear, crypto.open(sealed.ciphertext, sealed.iv, aad))
        assertEquals(12, sealed.iv.size)
        assertEquals(clear.size + 16, sealed.ciphertext.size)
        assertFalse(sealed.toString().contains("synthetic"))
    }

    @Test
    fun `provider generates fresh encryption IVs`() {
        val a = crypto.seal(ByteArray(100), aad)
        val b = crypto.seal(ByteArray(100), aad)
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun `wrong key is authenticated failure`() {
        val sealed = crypto.seal(ByteArray(100), aad)
        val other = DirectShellJcaCredentialAead.software(SecretKeySpec(ByteArray(32) { 99 }, "AES"))!!
        assertThrows(AEADBadTagException::class.java) { other.open(sealed.ciphertext, sealed.iv, aad) }
    }

    @Test
    fun `altered AAD is authenticated failure`() {
        val sealed = crypto.seal(ByteArray(100), aad)
        assertThrows(AEADBadTagException::class.java) { crypto.open(sealed.ciphertext, sealed.iv, ByteArray(52)) }
    }

    @Test
    fun `altered tag and IV are authenticated failure`() {
        val sealed = crypto.seal(ByteArray(100), aad)
        val damaged = sealed.ciphertext.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { crypto.open(damaged, sealed.iv, aad) }
        val iv = sealed.iv.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { crypto.open(sealed.ciphertext, iv, aad) }
    }

    @Test
    fun `plaintext ciphertext AAD and IV limits reject before work`() {
        assertThrows(IllegalArgumentException::class.java) { crypto.seal(ByteArray(65_537), aad) }
        assertThrows(IllegalArgumentException::class.java) { crypto.seal(ByteArray(1), ByteArray(53)) }
        assertThrows(IllegalArgumentException::class.java) { crypto.open(ByteArray(15), ByteArray(12), aad) }
        assertThrows(IllegalArgumentException::class.java) { crypto.open(ByteArray(65_537), ByteArray(12), aad) }
        assertThrows(IllegalArgumentException::class.java) { crypto.open(ByteArray(16), ByteArray(11), aad) }
    }

    @Test
    fun `maximum supported plaintext round trips without growth beyond record budget`() {
        val clear = ByteArray(DirectShellJcaCredentialAead.MAX_PLAINTEXT) { (it % 97).toByte() }
        val sealed = crypto.seal(clear, aad)
        assertEquals(DirectShellCredentialCodec.MAX_RECORD, aad.size + sealed.iv.size + sealed.ciphertext.size)
        assertArrayEquals(clear, crypto.open(sealed.ciphertext, sealed.iv, aad))
    }

    @Test
    fun `software compatibility still rejects AES128 or unqualified opaque key`() {
        assertNull(DirectShellJcaCredentialAead.software(SecretKeySpec(ByteArray(16), "AES")))
        val opaque = object : SecretKey {
            override fun getAlgorithm() = "AES"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray? = null
        }
        assertNull(DirectShellJcaCredentialAead.software(opaque))
        assertNotNull(crypto)
    }

    @Test
    fun `legacy provider lookup stays read only and creates nothing`() {
        var reads = 0
        val provider = DirectShellPairingWrappingKey {
            reads++
            null
        }
        assertEquals(0, reads)
        assertNull(provider.existingAead())
        assertEquals(1, reads)
        assertTrue(provider.existing() == null)
    }
}
