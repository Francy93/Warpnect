package io.warpnect.session.identity

import io.warpnect.session.DeviceId
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.pairing.PairingCryptoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDeviceIdentityRepositoryTest {
    private val crypto = JcaPairingCryptoProvider()

    @Test
    fun firstInitializationPersistsOneDeviceIdAndOneKey() {
        val storage = FakeStorage()
        val keys = FakeKeyProvider()
        val repository = repository(storage, keys)

        val created = requireReady(repository.loadOrCreate())
        val reloaded = requireReady(repository.loadOrCreate())

        assertEquals(created.deviceId, reloaded.deviceId)
        assertEquals(created.publicKey, reloaded.publicKey)
        assertEquals(LocalIdentityConsistency.Ready, LocalIdentityConsistency.Ready)
        assertTrue(keys.contains(LocalDeviceIdentityRepository.DEFAULT_KEY_ALIAS).valueOrFalse())
    }

    @Test
    fun missingHalfOfIdentityNeverSilentlyRegenerates() {
        val storageOnly = FakeStorage(DeviceId.requireValid(0u, 1u))
        val keysMissing = FakeKeyProvider()
        val storageOnlyResult = repository(storageOnly, keysMissing).loadOrCreate()
        assertTrue(storageOnlyResult is LocalDeviceIdentityResult.Inconsistent)
        assertTrue((storageOnlyResult as LocalDeviceIdentityResult.Inconsistent).deviceIdPresent)
        assertFalse(storageOnlyResult.keyPresent)

        val keyOnlyStorage = FakeStorage()
        val keyOnlyKeys = FakeKeyProvider().also { it.generate(LocalDeviceIdentityRepository.DEFAULT_KEY_ALIAS) }
        val keyOnlyResult = repository(keyOnlyStorage, keyOnlyKeys).loadOrCreate()
        assertTrue(keyOnlyResult is LocalDeviceIdentityResult.Inconsistent)
        assertFalse((keyOnlyResult as LocalDeviceIdentityResult.Inconsistent).deviceIdPresent)
        assertTrue(keyOnlyResult.keyPresent)
    }

    @Test
    fun explicitResetClearsTrustCallbackThenCreatesNewIdentity() {
        val storage = FakeStorage()
        val keys = FakeKeyProvider()
        val generator = SequenceDeviceIdGenerator()
        val repository = LocalDeviceIdentityRepository(storage, keys, generator, crypto::sha256)
        val first = requireReady(repository.loadOrCreate())
        var trustCleared = false

        val reset = requireReady(
            repository.resetLocalIdentityAndTrust {
                trustCleared = true
                true
            },
        )

        assertTrue(trustCleared)
        assertNotEquals(first.deviceId, reset.deviceId)
        assertNotEquals(first.publicKey, reset.publicKey)
    }

    private fun repository(storage: FakeStorage, keys: FakeKeyProvider): LocalDeviceIdentityRepository =
        LocalDeviceIdentityRepository(storage, keys, SequenceDeviceIdGenerator(), crypto::sha256)

    private fun requireReady(result: LocalDeviceIdentityResult): LocalDeviceIdentity = when (result) {
        is LocalDeviceIdentityResult.Ready -> result.identity
        else -> throw AssertionError(result)
    }

    private class FakeStorage(
        private var deviceId: DeviceId? = null,
    ) : LocalDeviceIdStorage {
        override fun read(): DeviceIdStorageReadResult = deviceId?.let(DeviceIdStorageReadResult::Value)
            ?: DeviceIdStorageReadResult.Missing

        override fun write(deviceId: DeviceId): Boolean {
            this.deviceId = deviceId
            return true
        }

        override fun clear(): Boolean {
            deviceId = null
            return true
        }
    }

    private class FakeKeyProvider : DeviceIdentityKeyProvider {
        private var key: IdentityPublicKey? = null

        override fun contains(alias: String): IdentityKeyResult<Boolean> = IdentityKeyResult.Value(key != null)

        override fun generate(alias: String): IdentityKeyResult<IdentityPublicKey> {
            val ephemeral = JcaPairingCryptoProvider().generateEphemeralKeyPair()
            val encoded = when (ephemeral) {
                is PairingCryptoResult.Value -> {
                    ephemeral.value.publicKey.encodedSpki().also { ephemeral.value.close() }
                }
                is PairingCryptoResult.Failed -> {
                    return IdentityKeyResult.Failed(ephemeral.detail)
                }
            }
            val generated = IdentityPublicKey.fromSpki(encoded)
                ?: return IdentityKeyResult.Failed("Invalid generated test key")
            key = generated
            return IdentityKeyResult.Value(generated)
        }

        override fun loadPublicKey(alias: String): IdentityKeyResult<IdentityPublicKey> =
            key?.let { IdentityKeyResult.Value(it) } ?: IdentityKeyResult.Failed("Missing")

        override fun sign(alias: String, data: ByteArray): IdentityKeyResult<ImmutableBytes> =
            IdentityKeyResult.Failed("Not required by this repository test")

        override fun securityLevel(alias: String): IdentityKeyResult<IdentityKeySecurityLevel> =
            IdentityKeyResult.Value(IdentityKeySecurityLevel.Software)

        override fun delete(alias: String): IdentityKeyResult<Unit> {
            key = null
            return IdentityKeyResult.Value(Unit)
        }
    }

    private class SequenceDeviceIdGenerator : DeviceIdGenerator {
        private var next = 1uL

        override fun next(): DeviceId = DeviceId.requireValid(0u, next++)
    }

    private fun IdentityKeyResult<Boolean>.valueOrFalse(): Boolean = when (this) {
        is IdentityKeyResult.Value -> value
        is IdentityKeyResult.Failed -> false
    }
}
