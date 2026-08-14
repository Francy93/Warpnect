package io.warpnect.platform.session.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.session.identity.LocalDeviceIdentityRepository
import io.warpnect.session.identity.LocalDeviceIdentityResult
import io.warpnect.session.identity.SecureRandomDeviceIdGenerator
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocalDeviceIdentityInstrumentationTest {
    @Test
    fun keystoreIdentityPersistsAndSignsWithoutExportingPrivateKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val crypto = JcaPairingCryptoProvider()
        val alias = "warpnect.test.identity.instrumentation.v1"
        val fileName = "warpnect.test.identity.instrumentation.v1.bin"
        val storage = AndroidLocalDeviceIdStorage(context, fileName)
        val keyProvider = AndroidDeviceIdentityKeyProvider()
        val repository = LocalDeviceIdentityRepository(
            deviceIdStorage = storage,
            keyProvider = keyProvider,
            deviceIdGenerator = SecureRandomDeviceIdGenerator(),
            fingerprintCalculator = crypto::sha256,
            keyAlias = alias,
        )
        try {
            val first = requireReady(repository.loadOrCreate())
            val signer = requireNotNull(repository.signer())
            val data = "Warpnect pairing identity test".encodeToByteArray()
            val signature = signer.sign(data)
            val signatureBytes = when (signature) {
                is io.warpnect.session.identity.IdentityKeyResult.Value -> signature.value.toByteArray()
                is io.warpnect.session.identity.IdentityKeyResult.Failed -> throw AssertionError(signature.detail)
            }
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(first.publicKey.encodedSpki()),
            )
            assertTrue(
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(data)
                    verify(signatureBytes)
                },
            )

            val reopened = LocalDeviceIdentityRepository(
                deviceIdStorage = storage,
                keyProvider = keyProvider,
                deviceIdGenerator = SecureRandomDeviceIdGenerator(),
                fingerprintCalculator = crypto::sha256,
                keyAlias = alias,
            )
            val second = requireReady(reopened.loadOrCreate())
            assertEquals(first.deviceId, second.deviceId)
            assertArrayEquals(first.publicKey.encodedSpki(), second.publicKey.encodedSpki())
        } finally {
            keyProvider.delete(alias)
            storage.clear()
        }
    }

    private fun requireReady(result: LocalDeviceIdentityResult) = when (result) {
        is LocalDeviceIdentityResult.Ready -> result.identity
        else -> throw AssertionError(result)
    }
}
