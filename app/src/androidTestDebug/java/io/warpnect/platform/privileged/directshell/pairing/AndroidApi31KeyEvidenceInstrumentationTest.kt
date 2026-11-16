package io.warpnect.platform.privileged.directshell.pairing

import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real API31 platform key evidence; no production alias or external peer is used. */
@SdkSuppress(minSdkVersion = 31, maxSdkVersion = 31)
class AndroidApi31KeyEvidenceInstrumentationTest {
    @Test fun existingOnlyReaderClassifiesOpaqueAesAndAuthenticatesAad() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Requires the isolated physical gate package",
            instrumentation.targetContext.packageName == "io.warpnect.af30gate",
        )
        assertTrue(aliases().isEmpty())
        try {
            AndroidPairingStorageFixture(expectedApi = 31).use(::verifyReader)
        } finally {
            assertTrue(aliases().isEmpty())
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("stream", "API31_KEY_GATE alias_inventory_empty=true\n") },
            )
        }
    }

    private fun verifyReader(f: AndroidPairingStorageFixture) {
        assertNull(f.keys.existing())
        assertNull(f.keys.existingAead())
        assertEquals(DirectShellKeyEvidence(DirectShellKeyAvailability.MISSING), f.keys.evidence)
        assertTrue(aliases().isEmpty())

        f.provision()
        assertEquals(setOf(f.alias), aliases())
        val platformKey = f.syntheticKey()
        assertNull(platformKey.encoded)
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            .getKeySpec(platformKey, KeyInfo::class.java) as KeyInfo
        assertEquals(f.alias, info.keystoreAlias)
        assertEquals(256, info.keySize)
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val level = info.securityLevel
        val backing = when (level) {
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> DirectShellKeyBacking.SOFTWARE
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> DirectShellKeyBacking.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> DirectShellKeyBacking.STRONGBOX
            else -> DirectShellKeyBacking.UNKNOWN
        }

        assertNull(requireNotNull(f.keys.existing()).encoded)
        val aead = requireNotNull(f.keys.existingAead())
        assertEquals(DirectShellKeyEvidence(DirectShellKeyAvailability.AVAILABLE, backing), f.keys.evidence)
        val payload = byteArrayOf(4, 2)
        val aad = byteArrayOf(1)
        val sealed = aead.seal(payload, aad)
        assertArrayEquals(payload, aead.open(sealed.ciphertext, sealed.iv, aad))
        assertThrows(AEADBadTagException::class.java) {
            aead.open(sealed.ciphertext, sealed.iv, byteArrayOf(2))
        }
        f.emit("api31_key_reader=available level=$level backing=$backing exportable=false aad_rejected=true")
    }

    private fun aliases(): Set<String> = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .aliases().toList().toSet()
}
