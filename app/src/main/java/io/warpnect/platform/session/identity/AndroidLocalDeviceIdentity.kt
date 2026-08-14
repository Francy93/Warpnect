package io.warpnect.platform.session.identity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.warpnect.session.DeviceId
import io.warpnect.session.identity.DeviceIdStorageReadResult
import io.warpnect.session.identity.DeviceIdentityKeyProvider
import io.warpnect.session.identity.IdentityKeyResult
import io.warpnect.session.identity.IdentityKeySecurityLevel
import io.warpnect.session.identity.IdentityPublicKey
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.identity.LocalDeviceIdStorage
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** App-private, atomic persistence for the opaque local DeviceId only. */
class AndroidLocalDeviceIdStorage(
    context: Context,
    fileName: String = FILE_NAME,
) : LocalDeviceIdStorage {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))

    override fun read(): DeviceIdStorageReadResult = try {
        val bytes = file.openRead().use { it.readBytes() }
        if (bytes.size != SERIALIZED_BYTES || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ||
            bytes[MAGIC.size].toInt() != VERSION
        ) {
            DeviceIdStorageReadResult.Failed("Local DeviceId storage is malformed")
        } else {
            val high = bytes.toUnsignedLong(MAGIC.size + 1)
            val low = bytes.toUnsignedLong(MAGIC.size + 9)
            DeviceId.fromParts(high, low)?.let(DeviceIdStorageReadResult::Value)
                ?: DeviceIdStorageReadResult.Failed("Local DeviceId used the reserved zero value")
        }
    } catch (_: FileNotFoundException) {
        DeviceIdStorageReadResult.Missing
    } catch (_: IOException) {
        DeviceIdStorageReadResult.Failed("Unable to read local DeviceId storage")
    }

    override fun write(deviceId: DeviceId): Boolean = replace(
        ByteArray(SERIALIZED_BYTES).also { bytes ->
            MAGIC.copyInto(bytes)
            bytes[MAGIC.size] = VERSION.toByte()
            deviceId.high.copyTo(bytes, MAGIC.size + 1)
            deviceId.low.copyTo(bytes, MAGIC.size + 9)
        },
    )

    override fun clear(): Boolean = try {
        file.delete()
        true
    } catch (_: Exception) {
        false
    }

    private fun replace(bytes: ByteArray): Boolean {
        var stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return false
        }
        return try {
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: IOException) {
            file.failWrite(stream)
            false
        }
    }

    private fun ByteArray.toUnsignedLong(offset: Int): ULong {
        var value = 0uL
        repeat(8) { index -> value = (value shl 8) or (this[offset + index].toInt() and 0xff).toULong() }
        return value
    }

    private fun ULong.copyTo(destination: ByteArray, offset: Int) {
        for (index in 0 until 8) destination[offset + index] = (this shr (56 - (index * 8))).toByte()
    }

    private companion object {
        val MAGIC = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte())
        const val VERSION: Int = 1
        const val SERIALIZED_BYTES: Int = 21
        const val FILE_NAME: String = "warpnect.device.identity.v1.bin"
    }
}

/** Android Keystore implementation for Warpnect's non-exportable P-256 signing key. */
class AndroidDeviceIdentityKeyProvider : DeviceIdentityKeyProvider {
    override fun contains(alias: String): IdentityKeyResult<Boolean> = try {
        IdentityKeyResult.Value(keyStore().containsAlias(alias))
    } catch (_: Exception) {
        IdentityKeyResult.Failed("Unable to inspect Android Keystore identity key")
    }

    override fun generate(alias: String): IdentityKeyResult<IdentityPublicKey> = try {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE_NAME))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        val keyPair = generator.apply { initialize(spec) }.generateKeyPair()
        identityPublicKey(keyPair.public.encoded)
            ?: IdentityKeyResult.Failed("Android Keystore generated an invalid identity public key")
    } catch (_: Exception) {
        IdentityKeyResult.Failed("Unable to generate Android Keystore identity key")
    }

    override fun loadPublicKey(alias: String): IdentityKeyResult<IdentityPublicKey> {
        return try {
            val encoded = keyStore().getCertificate(alias)?.publicKey?.encoded
                ?: return IdentityKeyResult.Failed("Android Keystore identity key is missing")
            identityPublicKey(encoded) ?: IdentityKeyResult.Failed("Android Keystore identity public key is invalid")
        } catch (_: Exception) {
            IdentityKeyResult.Failed("Unable to load Android Keystore identity public key")
        }
    }

    override fun sign(alias: String, data: ByteArray): IdentityKeyResult<ImmutableBytes> {
        return try {
            val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: return IdentityKeyResult.Failed("Android Keystore identity key is missing")
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(entry.privateKey)
                update(data)
                sign()
            }
            IdentityKeyResult.Value(ImmutableBytes.copyOf(signature))
        } catch (_: Exception) {
            IdentityKeyResult.Failed("Android Keystore identity signing failed")
        }
    }

    @Suppress("DEPRECATION")
    override fun securityLevel(alias: String): IdentityKeyResult<IdentityKeySecurityLevel> {
        return try {
            val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: return IdentityKeyResult.Failed("Android Keystore identity key is missing")
            val info = KeyFactory.getInstance(entry.privateKey.algorithm, KEYSTORE_PROVIDER)
                .getKeySpec(entry.privateKey, KeyInfo::class.java)
            IdentityKeyResult.Value(
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> when (info.securityLevel) {
                        KeyProperties.SECURITY_LEVEL_STRONGBOX -> IdentityKeySecurityLevel.StrongBox
                        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> IdentityKeySecurityLevel.TrustedEnvironment
                        KeyProperties.SECURITY_LEVEL_SOFTWARE -> IdentityKeySecurityLevel.Software
                        else -> IdentityKeySecurityLevel.Unknown
                    }
                    info.isInsideSecureHardware -> IdentityKeySecurityLevel.TrustedEnvironment
                    else -> IdentityKeySecurityLevel.Software
                },
            )
        } catch (_: Exception) {
            IdentityKeyResult.Value(IdentityKeySecurityLevel.Unknown)
        }
    }

    override fun delete(alias: String): IdentityKeyResult<Unit> = try {
        keyStore().deleteEntry(alias)
        IdentityKeyResult.Value(Unit)
    } catch (_: Exception) {
        IdentityKeyResult.Failed("Unable to delete Android Keystore identity key")
    }

    private fun identityPublicKey(encoded: ByteArray): IdentityKeyResult<IdentityPublicKey>? =
        IdentityPublicKey.fromSpki(encoded)?.let { key ->
            if (isP256PublicKey(key.encodedSpki())) IdentityKeyResult.Value(key) else null
        }

    private fun isP256PublicKey(encoded: ByteArray): Boolean =
        JcaPairingCryptoProvider.parseP256PublicKey(encoded) != null

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private companion object {
        const val KEYSTORE_PROVIDER: String = "AndroidKeyStore"
        const val P256_CURVE_NAME: String = "secp256r1"
    }
}
